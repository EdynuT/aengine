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
     * The size a node asks for, in pixels.
     *
     * <p>A root does not need one: it takes the rectangle {@link #solve} hands it.</p>
     */
    public void setSize(int node, float width, float height) {
        if (requestedWidth[node] == width && requestedHeight[node] == height) return;
        requestedWidth[node]  = width;
        requestedHeight[node] = height;
        version++;
    }

    /** Space kept clear inside a row or column's edges, on all four sides, in pixels. */
    public void setPadding(int node, float pixels) {
        if (padding[node] == pixels) return;
        padding[node] = pixels;
        version++;
    }

    /** Space between one child and the next, in pixels. Not added before the first or after the last. */
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
     * Forgets every node, so the tree can be built again.
     *
     * <p>Handles given out before this are no longer valid.</p>
     */
    public void clear() {
        count = 0;
        version++;
    }

    /** How many nodes the tree holds. */
    public int nodeCount() { return count; }

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
     */
    public void solve(int root, float x, float y, float width, float height) {
        if (solvedVersion[root] == version
                && solvedX[root] == x && solvedY[root] == y
                && solvedWidth[root] == width && solvedHeight[root] == height) {
            solveSkips++;
            return;
        }
        place(root, x, y, width, height);
        solvedVersion[root] = version;
        solves++;
    }

    /**
     * How many solves actually ran.
     *
     * <p>The number that makes the cache visible, as in the text layout cache: it climbs while
     * the tree or the window is changing and stops climbing once they are not. A steady-state
     * frame that still increments it is a cache that is not working.</p>
     */
    public int solves() { return solves; }

    /** How many solves were skipped because nothing could have moved. */
    public int solveSkips() { return solveSkips; }

    /** Left edge of a node after the last solve, in pixels. */
    public float x(int node)      { return solvedX[node]; }

    /** Top edge of a node after the last solve, in pixels. */
    public float y(int node)      { return solvedY[node]; }

    /** Width of a node after the last solve, in pixels. */
    public float width(int node)  { return solvedWidth[node]; }

    /** Height of a node after the last solve, in pixels. */
    public float height(int node) { return solvedHeight[node]; }

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
            asked     += horizontal ? requestedWidth[child] : requestedHeight[child];
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
            float main = horizontal ? requestedWidth[child] : requestedHeight[child];
            if (totalGrow > 0.0f) main += spare * (grow[child] / totalGrow);

            float start = Math.round(cursor);
            float end   = Math.round(cursor + main);

            float askedCross = horizontal ? requestedHeight[child] : requestedWidth[child];
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
