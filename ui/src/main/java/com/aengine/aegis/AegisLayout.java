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
 * exactly the size it asked for. Alignment across the row (3c-3) and skipping a solve when
 * nothing changed (3c-4) arrive next, on top of this.</p>
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

    // What the solve gave it.
    private final float[] solvedX;
    private final float[] solvedY;
    private final float[] solvedWidth;
    private final float[] solvedHeight;

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

        solvedX     = new float[capacity];
        solvedY      = new float[capacity];
        solvedWidth  = new float[capacity];
        solvedHeight = new float[capacity];
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
        requestedWidth[node]  = width;
        requestedHeight[node] = height;
    }

    /** Space kept clear inside a row or column's edges, on all four sides, in pixels. */
    public void setPadding(int node, float pixels) { padding[node] = pixels; }

    /** Space between one child and the next, in pixels. Not added before the first or after the last. */
    public void setGap(int node, float pixels) { gap[node] = pixels; }

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
    public void setGrow(int node, float factor) { grow[node] = factor; }

    /**
     * Forgets every node, so the tree can be built again.
     *
     * <p>Handles given out before this are no longer valid.</p>
     */
    public void clear() { count = 0; }

    /** How many nodes the tree holds. */
    public int nodeCount() { return count; }

    // -----------------------------------------------------------------------------------
    // Solving
    // -----------------------------------------------------------------------------------

    /**
     * Places {@code root} in the given rectangle, and everything under it.
     *
     * <p>Runs every frame. It writes into arrays that already exist and allocates nothing.</p>
     */
    public void solve(int root, float x, float y, float width, float height) {
        place(root, x, y, width, height);
    }

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
     * Across the other axis every child sits at the start of the padded area — alignment is
     * step 3c-3.</p>
     */
    private void place(int node, float x, float y, float width, float height) {
        solvedX[node]      = x;
        solvedY[node]      = y;
        solvedWidth[node]  = width;
        solvedHeight[node] = height;

        if (kind[node] == BOX) return;

        boolean horizontal = kind[node] == ROW;
        float pad       = padding[node];
        float innerX    = x + pad;
        float innerY    = y + pad;
        float innerMain = (horizontal ? width : height) - pad * 2.0f;

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

        // Second walk: place them. The cursor stays exact and only the edges drawn from it are
        // rounded, so rounding never accumulates — see the class comment.
        float cursor = horizontal ? innerX : innerY;
        for (int child = firstChild[node]; child != NONE; child = nextSibling[child]) {
            float main = horizontal ? requestedWidth[child] : requestedHeight[child];
            if (totalGrow > 0.0f) main += spare * (grow[child] / totalGrow);

            float start = Math.round(cursor);
            float end   = Math.round(cursor + main);

            if (horizontal) {
                place(child, start, innerY, end - start, requestedHeight[child]);
            } else {
                place(child, innerX, start, requestedWidth[child], end - start);
            }
            cursor += main + gap[node];
        }
    }
}
