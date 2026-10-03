package com.aengine.aegis;

/**
 * Rows and columns that place their children — layer L3 of the design document.
 *
 * <p>A layout is a tree built once and solved every frame. Building says what contains what
 * and how big things are; solving turns that into a rectangle per node, which drawing then
 * reads. The two are separate because the tree rarely changes and the space it is given
 * often does — resize the window and only the solve runs again.</p>
 *
 * <pre>{@code
 * AegisLayout layout = aegis.layout();
 *
 * // once
 * int row = layout.row(AegisLayout.NONE);   // a root: it has no parent
 * layout.setPadding(row, 12f);
 * layout.setGap(row, 8f);
 * int save = layout.box(row);
 * layout.setSize(save, 80f, 60f);
 *
 * // every frame
 * layout.solve(row, 40f, 490f, 500f, 84f);
 * aegis.addRoundedRect(layout.x(save), layout.y(save), layout.width(save), layout.height(save), ...);
 * }</pre>
 *
 * <h2>Why it is shaped like this</h2>
 *
 * <p><strong>Nodes are integer handles, not objects.</strong> A handle is an index into
 * parallel arrays, one array per property, all sized once at construction. Java has no value
 * types yet, so an object per node would be a heap object per node; §6 and §9 of the design
 * document rule that out, and arrays are how a tree is kept without one.</p>
 *
 * <p><strong>Children are a linked list threaded through the arrays</strong> — each node knows
 * its first child and its next sibling. Walking the children is then a loop over two array
 * reads, with no list object and no iterator to allocate.</p>
 *
 * <p><strong>A child's size is what it asked for, plus its share of what is left.</strong>
 * Children are placed one after another, separated by the gap and inset by the padding
 * (3c-1). Whatever length of the row or column they leave unused is then shared among the
 * children that have a {@linkplain #setGrow grow} factor, in proportion to it (3c-2): grow 1
 * and grow 2 split the spare space one third to two thirds. A child without one keeps
 * exactly the size it asked for.</p>
 *
 * <p><strong>Alignment is set per screen axis, X and Y, by the container</strong> (3c-3), and
 * applies to all its children alike. Along the axis the children follow each other on — X in a
 * row, Y in a column — it moves the group as a whole: to the start, the middle or the end of
 * whatever space they leave. Across the other axis it places each child on its own, and can
 * also stretch it edge to edge. Stretch is what lets panels nest into a frame: a pane inside a
 * row fills the row's height without knowing what that height is. Naming the axis rather than
 * "main" and "cross" means {@code setAlignY(CENTER)} centres vertically whether the node is a
 * row or a column.</p>
 *
 * <p><strong>A solve that could not move anything is skipped</strong> (3c-4). Every change to
 * the tree bumps a version number, and each root remembers the version and the rectangle it
 * was last solved with; when both match, {@link #solve} returns at once. A setter handed the
 * value a node already has changes nothing and bumps nothing, so rebuilding a tree with the
 * same values every frame keeps the cache.</p>
 *
 * <p><strong>Edges land on whole pixels.</strong> Sharing spare space divides it, so a grown
 * child's width is usually fractional, and a fractional edge is drawn half-covered — a soft
 * edge on what should be a crisp box. So along the main axis each child's start and end are
 * rounded, not its size. Rounding the size instead would let the error build up child by
 * child; rounding the edges keeps every gap exactly the gap asked for, and the last child
 * ending exactly where it should.</p>
 *
 * <p>Not thread-safe, and not meant to be: it belongs to the thread that owns the
 * interface.</p>
 */
public final class AegisLayout {

    /** "No node" — the parent of a root, and the end of a child list. */
    public static final int NONE = -1;

    /**
     * Where a row or column puts its children along one screen axis — see {@link #setAlignX}
     * and {@link #setAlignY}.
     */
    public enum Align {
        /** Against the left edge on X, the top edge on Y. */
        START,
        /** In the middle. */
        CENTER,
        /** Against the right edge on X, the bottom edge on Y. */
        END,
        /**
         * Edge to edge of the padded area, ignoring the size asked for on that axis. Only across
         * the children's flow — Y in a row, X in a column; along the flow, grow does this.
         */
        STRETCH
    }

    // What a node is. A row lays its children left to right, a column top to bottom, and a
    // box is a leaf: something to be placed, with no children of its own.
    private static final byte ROW    = 0;
    private static final byte COLUMN = 1;
    private static final byte BOX    = 2;

    private final int capacity;
    private int count;

    // The tree.
    private final byte[] kind;
    private final int[]  parent;
    private final int[]  firstChild;
    private final int[]  lastChild;      // kept so appending a child is not a walk to the end
    private final int[]  nextSibling;

    // What the node was asked for.
    private final float[] requestedWidth;
    private final float[] requestedHeight;
    private final float[] padding;
    private final float[] gap;
    private final float[] grow;
    private final Align[] alignX;
    private final Align[] alignY;

    // A name given in code, for a theme or a layout file to find the node by. Null for a node without one.
    private final String[] name;

    // What a row or column asking for no size on an axis asks for instead: what its children
    // take up there. Measured at the start of every solve that runs, before anything is placed.
    private final float[] contentWidth;
    private final float[] contentHeight;

    // What the solve gave it.
    private final float[] solvedX;
    private final float[] solvedY;
    private final float[] solvedWidth;
    private final float[] solvedHeight;

    /**
     * The tree's version: bumped by every change that could move a rectangle. A root remembers
     * the version it was last solved at in {@link #solvedVersion}; while the two agree and the
     * rectangle handed to {@link #solve} is the same as last time, nothing can have moved and
     * the solve is skipped. See {@link #solve}.
     */
    private int version;
    private final int[] solvedVersion;

    /** Counted so the cache can be shown to work; see {@link #solves()}. */
    private int solves;
    private int solveSkips;

    /**
     * Creates an empty layout.
     *
     * @param capacity the most nodes this layout will ever hold, allocated now so that
     *                 building and solving never allocate
     */
    public AegisLayout(int capacity) {
        this.capacity = capacity;

        kind        = new byte[capacity];
        parent      = new int[capacity];
        firstChild  = new int[capacity];
        lastChild   = new int[capacity];
        nextSibling = new int[capacity];

        requestedWidth  = new float[capacity];
        requestedHeight = new float[capacity];
        padding         = new float[capacity];
        gap             = new float[capacity];
        grow            = new float[capacity];
        alignX          = new Align[capacity];
        alignY          = new Align[capacity];

        name = new String[capacity];

        contentWidth  = new float[capacity];
        contentHeight = new float[capacity];
        solvedX      = new float[capacity];
        solvedY      = new float[capacity];
        solvedWidth  = new float[capacity];
        solvedHeight = new float[capacity];

        solvedVersion = new int[capacity];
    }

    // -----------------------------------------------------------------------------------
    // Building
    // -----------------------------------------------------------------------------------

    /**
     * A node that lays its children out left to right.
     *
     * @param parent the node to add it to, or {@link #NONE} to make it a root
     * @return its handle
     */
    public int row(int parent) { return add(ROW, parent); }

    /**
     * A node that lays its children out top to bottom.
     *
     * @param parent the node to add it to, or {@link #NONE} to make it a root
     * @return its handle
     */
    public int column(int parent) { return add(COLUMN, parent); }

    /**
     * A leaf: a rectangle to be placed, which holds nothing itself.
     *
     * @param parent the row or column to add it to
     * @return its handle
     */
    public int box(int parent) { return add(BOX, parent); }

    /**
     * Moves a node, with everything under it, to the end of another node's children — or
     * makes it a root, when {@code newParent} is {@link #NONE}.
     *
     * <p>How a layout file places what code made: a panel built by code as a root is attached
     * to the slot the file gives it, and a widget is moved into a row the file groups it in.
     * Handles do not change; only where the node hangs.</p>
     *
     * @param node      the node to move
     * @param newParent a row or a column, or {@link #NONE}
     * @throws IllegalArgumentException if {@code newParent} is a box, or is {@code node} itself
     *                                  or under it — the tree would loop
     */
    public void attach(int node, int newParent) {
        if (newParent != NONE) {
            if (kind[newParent] == BOX) {
                throw new IllegalArgumentException("A box holds nothing; attach to a row or a column instead.");
            }
            for (int up = newParent; up != NONE; up = parent[up]) {
                if (up == node) {
                    throw new IllegalArgumentException("A node cannot be attached under itself.");
                }
            }
        }
        detach(node);
        if (newParent == NONE) return;

        parent[node] = newParent;
        if (firstChild[newParent] == NONE) {
            firstChild[newParent] = node;
        } else {
            nextSibling[lastChild[newParent]] = node;
        }
        lastChild[newParent] = node;
        version++;
    }

    /**
     * Takes a node, with everything under it, out of its parent's children, leaving it a root.
     * A node that is already a root is left as it is.
     *
     * <p>Walks the parent's children to find the one before it, since siblings link forward
     * only; this is for building, not for the frame loop.</p>
     *
     * @param node the node to take out
     */
    public void detach(int node) {
        int from = parent[node];
        if (from == NONE) return;

        int before = NONE;
        for (int c = firstChild[from]; c != node; c = nextSibling[c]) before = c;

        if (before == NONE) firstChild[from] = nextSibling[node];
        else                nextSibling[before] = nextSibling[node];
        if (lastChild[from] == node) lastChild[from] = before;

        parent[node]      = NONE;
        nextSibling[node] = NONE;
        version++;
    }

    /**
     * The size a node asks for, in pixels.
     *
     * <p>A root does not need one: it takes the rectangle {@link #solve} hands it.</p>
     *
     * @param node   the handle
     * @param width  width asked for
     * @param height height asked for
     */
    public void setSize(int node, float width, float height) {
        if (requestedWidth[node] == width && requestedHeight[node] == height) return;
        requestedWidth[node]  = width;
        requestedHeight[node] = height;
        version++;
    }

    /**
     * The width a node asks for, in pixels, leaving the height it asks for as it is — how a
     * layout file that writes only {@code "width"} changes a panel without undoing its code.
     *
     * @param node  the handle
     * @param width width asked for
     */
    public void setWidth(int node, float width) {
        setSize(node, width, requestedHeight[node]);
    }

    /**
     * The height a node asks for, in pixels, leaving the width as it is. See {@link #setWidth}.
     *
     * @param node   the handle
     * @param height height asked for
     */
    public void setHeight(int node, float height) {
        setSize(node, requestedWidth[node], height);
    }

    /** The width a node asks for, as last set; 0 when none was. For the widgets' own sizing. */
    float requestedWidth(int node) { return requestedWidth[node]; }

    /** The height a node asks for, as last set; 0 when none was. */
    float requestedHeight(int node) { return requestedHeight[node]; }

    /**
     * Whether a node is a row or a column — something that holds children — rather than a box.
     *
     * @param node the handle
     * @return {@code true} for a row or a column
     */
    public boolean isContainer(int node) { return kind[node] != BOX; }

    /**
     * Whether a node is a row — lays its children out left to right.
     *
     * @param node the handle
     * @return {@code true} for a row; {@code false} for a column or a box
     */
    public boolean isRow(int node) { return kind[node] == ROW; }

    /**
     * The space kept clear inside a row or column's edges, as set with {@link #setPadding}.
     *
     * @param node the handle
     * @return the padding in pixels; 0 for a box
     */
    public float padding(int node) { return padding[node]; }

    /**
     * Space kept clear inside a row or column's edges, on all four sides, in pixels.
     *
     * @param node   the row or column
     * @param pixels the padding
     */
    public void setPadding(int node, float pixels) {
        if (padding[node] == pixels) return;
        padding[node] = pixels;
        version++;
    }

    /**
     * Space between one child and the next, in pixels. Not added before the first or after the last.
     *
     * @param node   the row or column
     * @param pixels the gap
     */
    public void setGap(int node, float pixels) {
        if (gap[node] == pixels) return;
        gap[node] = pixels;
        version++;
    }

    /**
     * How much of its parent's spare space this node takes, relative to its siblings.
     *
     * <p>Spare space is the length of the row (or column) its children leave unused. It is
     * shared among the children with a factor above zero, in proportion to it: a child with 2
     * gets twice what a child with 1 gets. The share is added to the size the child asked for
     * along that axis, so a child asking for 0 and growing is sized by its share alone.</p>
     *
     * <p>0, the default, means the node keeps exactly the size it asked for. When the children
     * already need more than the row has, there is no spare space and nothing grows; fixed
     * children then run past the end, since shrinking them is not part of this step.</p>
     *
     * @param node   the child
     * @param factor its share; 0 to keep its own size
     */
    public void setGrow(int node, float factor) {
        if (grow[node] == factor) return;
        grow[node] = factor;
        version++;
    }

    /**
     * Where a row or column puts its children horizontally. {@link Align#START} by default.
     *
     * <p>In a <strong>row</strong> the children sit side by side, so this moves them as a group
     * within the width they leave spare: {@code CENTER} puts the whole row of them in the
     * middle. If any child grows it takes the spare width and this has nothing to move.
     * {@code STRETCH} is refused here — widening children in a row is what grow is for.</p>
     *
     * <p>In a <strong>column</strong> each child is placed on its own: against the left, in the
     * middle, against the right, or stretched to the column's full width.</p>
     *
     * @param node the row or column
     * @param how  the alignment
     * @throws IllegalArgumentException for {@code STRETCH} on a row
     */
    public void setAlignX(int node, Align how) {
        if (how == Align.STRETCH && kind[node] == ROW) {
            throw new IllegalArgumentException(
                "STRETCH on X does not apply to a row: its children follow each other along X. "
                + "Give them a grow factor to widen them instead.");
        }
        if (alignX[node] == how) return;
        alignX[node] = how;
        version++;
    }

    /**
     * Where a row or column puts its children vertically. {@link Align#START} by default.
     *
     * <p>In a <strong>column</strong> the children are stacked, so this moves them as a group
     * within the height they leave spare: {@code CENTER} puts the whole stack in the middle.
     * If any child grows it takes the spare height and this has nothing to move.
     * {@code STRETCH} is refused here — heightening children in a column is what grow is for.</p>
     *
     * <p>In a <strong>row</strong> each child is placed on its own: against the top, in the
     * middle, against the bottom, or stretched to the row's full height — which is what lets a
     * pane fill a row without knowing how tall the row is.</p>
     *
     * @param node the row or column
     * @param how  the alignment
     * @throws IllegalArgumentException for {@code STRETCH} on a column
     */
    public void setAlignY(int node, Align how) {
        if (how == Align.STRETCH && kind[node] == COLUMN) {
            throw new IllegalArgumentException(
                "STRETCH on Y does not apply to a column: its children follow each other along Y. "
                + "Give them a grow factor to heighten them instead.");
        }
        if (alignY[node] == how) return;
        alignY[node] = how;
        version++;
    }

    /**
     * Names a node, so a theme can reach it.
     *
     * <p>A theme finds a widget by the path of names from the top of the tree down to it —
     * {@code viewport.stopButton} — never by handle, since handles shift whenever a node is
     * added before another. Nodes without a name are left out of the path: wrapping part of a
     * panel in a new row changes no path, so no theme breaks.</p>
     *
     * <p>That makes names part of the contract with theme authors. Renaming one silently drops
     * every theme rule that named it; do it as deliberately as renaming a public method.</p>
     *
     * @param node the handle
     * @param name letters, digits and underscores, starting with a letter — no dots, since a
     *             dot is what separates the parts of a path
     * @throws IllegalArgumentException for a name that could not appear in a path
     */
    public void setNodeName(int node, String name) {
        checkNodeName(name);
        this.name[node] = name;
    }

    /** Throws, saying why, for a name {@link #setNodeName} would refuse; for callers that check before building. */
    static void checkNodeName(String name) {
        if (!isValidNodeName(name)) {
            throw new IllegalArgumentException(
                "\"" + name + "\" cannot be a node name: use letters, digits and underscores, "
                + "starting with a letter. A dot would split it into two parts of a theme path.");
        }
    }

    /**
     * A node's name, or {@code null} if it was not given one.
     *
     * @param node the handle
     * @return the name, or {@code null}
     */
    public String nodeName(int node) { return name[node]; }

    /**
     * Whether a name is usable as a node's name: a letter, then letters, digits or underscores.
     *
     * @param name the candidate
     * @return {@code true} if {@link #setNodeName} would accept it
     */
    public static boolean isValidNodeName(String name) {
        if (name == null || name.isEmpty() || !Character.isLetter(name.charAt(0))) return false;
        for (int i = 1; i < name.length(); i++) {
            char c = name.charAt(i);
            if (!Character.isLetterOrDigit(c) && c != '_') return false;
        }
        return true;
    }

    /**
     * Forgets every node, so the tree can be built again.
     *
     * <p>Handles given out before this are no longer valid.</p>
     */
    public void clear() {
        count = 0;
        version++;
    }

    /**
     * How many nodes the tree holds.
     *
     * @return the count; handles run from 0 to this, exclusive
     */
    public int nodeCount() { return count; }

    /**
     * The node this one was added to, or {@link #NONE} for a root.
     *
     * @param node the handle
     * @return the parent's handle
     */
    public int parent(int node) { return parent[node]; }

    /**
     * A node's first child, or {@link #NONE} if it has none.
     *
     * @param node the handle
     * @return the child's handle
     */
    public int firstChild(int node) { return firstChild[node]; }

    /**
     * A node's last child, or {@link #NONE} if it has none.
     *
     * @param node the handle
     * @return the child's handle
     */
    public int lastChild(int node) { return lastChild[node]; }

    /**
     * The child added to the same parent after this one, or {@link #NONE} if it was the last.
     *
     * @param node the handle
     * @return the sibling's handle
     */
    public int nextSibling(int node) { return nextSibling[node]; }

    // -----------------------------------------------------------------------------------
    // Solving
    // -----------------------------------------------------------------------------------

    /**
     * Places {@code root} in the given rectangle, and everything under it — unless nothing
     * could have moved since the last time, in which case it does nothing at all.
     *
     * <p>Meant to be called every frame. A rectangle can only move if the tree changed or the
     * space handed to the root did, so the solve is skipped when neither has: the tree's
     * version is the one this root was last solved at, and the rectangle equals the one it
     * holds from then. The rectangles read back through {@link #x} and friends are still the
     * right ones, because they are exactly what that last solve wrote.</p>
     *
     * <p>Any change to the tree re-solves the whole of it, not just the part that changed.
     * With trees of dozens of nodes a full solve is cheap; tracking which subtree is dirty is
     * worth its complexity only once widgets change on their own, which is L4's business.</p>
     *
     * <p>It writes into arrays that already exist and allocates nothing.</p>
     *
     * @param root   the node to place, usually a root
     * @param x      left edge of the space it is given
     * @param y      top edge
     * @param width  width of the space
     * @param height height of the space
     */
    public void solve(int root, float x, float y, float width, float height) {
        if (solvedVersion[root] == version
                && solvedX[root] == x && solvedY[root] == y
                && solvedWidth[root] == width && solvedHeight[root] == height) {
            solveSkips++;
            return;
        }
        measure(root);
        place(root, x, y, width, height);
        solvedVersion[root] = version;
        solves++;
    }

    /**
     * What a row or column takes up when it asks for no size: along its own direction, its
     * children one after the other with the gaps between; across it, the largest child; padding
     * on both sides of each. Bottom up, so a row's measure already holds its rows' and columns'.
     *
     * <p>This is what lets a layout file group widgets in a new row without giving it a size:
     * the row is as tall as its tallest widget. A size asked for — {@code setSize}, or a
     * layout file's {@code "width"} — still wins, and a stretching or growing parent may still
     * make it larger.</p>
     */
    private void measure(int node) {
        if (kind[node] == BOX) return;
        boolean horizontal = kind[node] == ROW;
        float along = 0.0f, across = 0.0f;
        int children = 0;
        for (int child = firstChild[node]; child != NONE; child = nextSibling[child]) {
            measure(child);
            along  += horizontal ? askedWidth(child) : askedHeight(child);
            across  = Math.max(across, horizontal ? askedHeight(child) : askedWidth(child));
            children++;
        }
        if (children > 1) along += gap[node] * (children - 1);
        float pad = padding[node] * 2.0f;
        contentWidth[node]  = (horizontal ? along : across) + pad;
        contentHeight[node] = (horizontal ? across : along) + pad;
    }

    /** The width a node asks for: what it was given, or, for a row or column given none, its content's. */
    private float askedWidth(int node) {
        return requestedWidth[node] > 0.0f || kind[node] == BOX ? requestedWidth[node] : contentWidth[node];
    }

    /** The height a node asks for; see {@link #askedWidth}. */
    private float askedHeight(int node) {
        return requestedHeight[node] > 0.0f || kind[node] == BOX ? requestedHeight[node] : contentHeight[node];
    }

    /**
     * How many solves actually ran.
     *
     * <p>The number that makes the cache visible, as in the text layout cache: it climbs while
     * the tree or the window is changing and stops climbing once they are not. A steady-state
     * frame that still increments it is a cache that is not working.</p>
     *
     * @return the count since the layout was created
     */
    public int solves() { return solves; }

    /**
     * How many solves were skipped because nothing could have moved.
     *
     * @return the count since the layout was created
     */
    public int solveSkips() { return solveSkips; }

    /**
     * Left edge of a node after the last solve, in pixels.
     *
     * @param node the handle
     * @return the x
     */
    public float x(int node)      { return solvedX[node]; }

    /**
     * Top edge of a node after the last solve, in pixels.
     *
     * @param node the handle
     * @return the y
     */
    public float y(int node)      { return solvedY[node]; }

    /**
     * Width of a node after the last solve, in pixels.
     *
     * @param node the handle
     * @return the width
     */
    public float width(int node)  { return solvedWidth[node]; }

    /**
     * Height of a node after the last solve, in pixels.
     *
     * @param node the handle
     * @return the height
     */
    public float height(int node) { return solvedHeight[node]; }

    // -----------------------------------------------------------------------------------
    // Hit-testing
    // -----------------------------------------------------------------------------------

    /**
     * The deepest node under a point, as the last solve placed it — or {@link #NONE} if the
     * point is outside {@code root}.
     *
     * <p>"Deepest" because a box inside a pane inside the frame is under the point too, and
     * the one the pointer is <em>on</em> is the innermost. Where siblings overlap, the later
     * one wins: children are drawn in the order they were added, so the later one is on top,
     * and the one on top is the one the pointer touches.</p>
     *
     * <p>A rectangle holds its left and top edges but not its right and bottom ones, so a
     * point on the seam between two touching boxes belongs to exactly one of them.</p>
     *
     * <p>Reads the rectangles, so it answers for the layout as last solved. Called after this
     * frame's solve, that is this frame; called before, it is the previous frame's, which is
     * one frame late and not visible. It allocates nothing.</p>
     *
     * @param root the tree to search
     * @param px   point x, in the pixels the layout was solved in
     * @param py   point y
     * @return the deepest node containing the point, or {@link #NONE}
     */
    public int nodeAt(int root, float px, float py) {
        if (!contains(root, px, py)) return NONE;

        // Walk every child rather than stopping at the first hit, so that of two overlapping
        // siblings the later — the one drawn on top — is the one that answers.
        int deepest = root;
        for (int child = firstChild[root]; child != NONE; child = nextSibling[child]) {
            int hit = nodeAt(child, px, py);
            if (hit != NONE) deepest = hit;
        }
        return deepest;
    }

    /** Whether a point lies in a node's solved rectangle: left and top edges in, right and bottom out. */
    private boolean contains(int node, float px, float py) {
        return px >= solvedX[node] && px < solvedX[node] + solvedWidth[node]
            && py >= solvedY[node] && py < solvedY[node] + solvedHeight[node];
    }

    // -----------------------------------------------------------------------------------
    // Internals
    // -----------------------------------------------------------------------------------

    private int add(byte nodeKind, int parentNode) {
        if (count == capacity) {
            throw new IllegalStateException(
                "Layout is full at " + capacity + " nodes. Raise the capacity it was created with.");
        }
        if (parentNode != NONE && kind[parentNode] == BOX) {
            throw new IllegalArgumentException(
                "A box holds nothing; add children to a row or a column instead.");
        }

        int node = count++;
        kind[node]        = nodeKind;
        parent[node]      = parentNode;
        firstChild[node]  = NONE;
        lastChild[node]   = NONE;
        nextSibling[node] = NONE;

        requestedWidth[node]  = 0.0f;
        requestedHeight[node] = 0.0f;
        padding[node]         = 0.0f;
        gap[node]             = 0.0f;
        grow[node]            = 0.0f;
        alignX[node]          = Align.START;
        alignY[node]          = Align.START;
        name[node]            = null;

        // Never solved. A handle reused after clear() must not inherit the version its
        // previous owner was solved at, or its first solve could be skipped.
        solvedVersion[node] = -1;
        version++;

        if (parentNode != NONE) {
            if (firstChild[parentNode] == NONE) {
                firstChild[parentNode] = node;
            } else {
                nextSibling[lastChild[parentNode]] = node;
            }
            lastChild[parentNode] = node;
        }
        return node;
    }

    /**
     * Gives a node its rectangle, then lays out its children inside it.
     *
     * <p>A row and a column are the same walk with the axes swapped, so the code speaks of
     * the <em>main</em> axis — the one children follow each other along — rather than of
     * width or height. Two walks over the children: the first adds up what they ask for, which
     * is what tells how much is spare; the second places them, handing each grower its share.
     * The public API names screen axes (X, Y); in here they are translated once into main and
     * cross, because that is what the walk needs. When nothing grows, the main-axis alignment
     * shifts where the walk starts, moving the whole group. Across the other axis — the
     * <em>cross</em> axis — each child is placed by the cross alignment, see
     * {@link #crossStart} and {@link #crossSize}.</p>
     */
    private void place(int node, float x, float y, float width, float height) {
        solvedX[node]      = x;
        solvedY[node]      = y;
        solvedWidth[node]  = width;
        solvedHeight[node] = height;

        if (kind[node] == BOX) return;

        boolean horizontal = kind[node] == ROW;
        float pad        = padding[node];
        float innerX     = x + pad;
        float innerY     = y + pad;
        float innerMain  = (horizontal ? width : height) - pad * 2.0f;
        float innerCross = (horizontal ? height : width) - pad * 2.0f;
        float crossFrom  = horizontal ? innerY : innerX;
        Align mainAlign  = horizontal ? alignX[node] : alignY[node];
        Align crossAlign = horizontal ? alignY[node] : alignX[node];

        // First walk: what the children ask for along the main axis, gaps included, and how
        // many shares the spare space is to be cut into.
        float asked     = 0.0f;
        float totalGrow = 0.0f;
        int   children  = 0;
        for (int child = firstChild[node]; child != NONE; child = nextSibling[child]) {
            asked     += horizontal ? askedWidth(child) : askedHeight(child);
            totalGrow += grow[child];
            children++;
        }
        if (children > 1) asked += gap[node] * (children - 1);

        // Negative spare space means the children already overflow; growing would only make
        // that worse, so nothing grows.
        float spare = Math.max(0.0f, innerMain - asked);

        // Where the group starts along the main axis. With a grower present the spare space is
        // handed out and there is nothing left to shift by; without one, alignment decides
        // where the unused space goes — all after (START), half each side (CENTER), or all
        // before (END).
        float shift = 0.0f;
        if (totalGrow == 0.0f) {
            if (mainAlign == Align.CENTER) shift = spare * 0.5f;
            else if (mainAlign == Align.END) shift = spare;
        }

        // Second walk: place them. The cursor stays exact and only the edges drawn from it are
        // rounded, so rounding never accumulates — see the class comment.
        float cursor = (horizontal ? innerX : innerY) + shift;
        for (int child = firstChild[node]; child != NONE; child = nextSibling[child]) {
            float main = horizontal ? askedWidth(child) : askedHeight(child);
            if (totalGrow > 0.0f) main += spare * (grow[child] / totalGrow);

            float start = Math.round(cursor);
            float end   = Math.round(cursor + main);

            float askedCross = horizontal ? askedHeight(child) : askedWidth(child);
            float cross      = crossSize(crossAlign, askedCross, innerCross);
            float crossAt    = crossStart(crossAlign, crossFrom, innerCross, cross);

            if (horizontal) {
                place(child, start, crossAt, end - start, cross);
            } else {
                place(child, crossAt, start, cross, end - start);
            }
            cursor += main + gap[node];
        }
    }

    /** A child's size across the main axis: what it asked for, unless it is stretched. */
    private static float crossSize(Align how, float asked, float available) {
        return how == Align.STRETCH ? available : asked;
    }

    /**
     * Where a child starts across the main axis, given the space there and its size in it.
     *
     * <p>Rounded for the same reason main-axis edges are: centring a 40-pixel box in 61 pixels
     * would otherwise put its edge on a half pixel.</p>
     */
    private static float crossStart(Align how, float from, float available, float size) {
        switch (how) {
            case CENTER: return Math.round(from + (available - size) * 0.5f);
            case END:    return Math.round(from + available - size);
            default:     return from;   // START and STRETCH both begin at the padded edge
        }
    }
}
