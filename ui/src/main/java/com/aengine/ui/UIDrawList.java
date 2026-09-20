package com.aengine.ui;

/**
 * The frame's interface geometry, accumulated as vertices and indices.
 *
 * <p>This is layer L1 of the framework described in
 * {@code docs/UI_FRAMEWORK_ARCHITECTURE.md}: everything above it — text, layout, widgets —
 * ultimately describes what it should draw, and {@link UIRenderer} is the only thing that
 * reads it. Nothing here touches a graphics API.</p>
 *
 * <p><strong>Allocation:</strong> both arrays are reserved once at construction and
 * rewritten in place. {@link #begin()} rewinds the write cursors rather than clearing
 * storage, so a frame of interface geometry allocates nothing.</p>
 */
public final class UIDrawList {

    /** position(2) + localPos(2) + halfSize(2) + radius(1) + colour(4) — see ui.vert. */
    public static final int[] VERTEX_LAYOUT = { 2, 2, 2, 1, 4 };

    public static final int FLOATS_PER_VERTEX = 11;

    private static final int VERTICES_PER_QUAD = 4;
    private static final int INDICES_PER_QUAD  = 6;

    /**
     * Antialiasing reads the distance field just outside the shape, so the quad is grown by
     * this margin. Without it the smoothstep would be clipped by the quad's own edge and
     * the outline would come out hard.
     */
    private static final float AA_PADDING = 2.0f;

    /** Maximum depth of nested clip rectangles. Panels inside panels inside a dock. */
    private static final int MAX_CLIP_DEPTH = 32;

    private final float[] vertices;
    private final int[]   indices;

    private int vertexFloatCount = 0;
    private int vertexCount      = 0;
    private int indexCount       = 0;

    private final int maxQuads;

    // -----------------------------------------------------------------------------------
    // Command list
    //
    // Geometry alone cannot be drawn in one call: a clip rectangle is pipeline state, not
    // vertex data, so every change of clip closes the current command and opens a new one.
    // Stored as parallel primitive arrays rather than objects, so a frame's commands cost
    // no allocation.
    // -----------------------------------------------------------------------------------

    private final float[] cmdClip;        // x, y, w, h per command
    private final int[]   cmdIndexOffset;
    private final int[]   cmdIndexCount;
    private int cmdCount = 0;

    /** Index into the stream where the command currently being accumulated started. */
    private int currentCmdStart = 0;

    private final float[] clipStack;      // x, y, w, h per level
    private int clipDepth = 0;

    public UIDrawList(int maxQuads) {
        this.maxQuads = maxQuads;
        this.vertices = new float[maxQuads * VERTICES_PER_QUAD * FLOATS_PER_VERTEX];
        this.indices  = new int[maxQuads * INDICES_PER_QUAD];

        // Worst case is one command per shape — every shape under a different clip.
        this.cmdClip        = new float[maxQuads * 4];
        this.cmdIndexOffset = new int[maxQuads];
        this.cmdIndexCount  = new int[maxQuads];

        this.clipStack = new float[MAX_CLIP_DEPTH * 4];
    }

    /**
     * Rewinds the cursors and resets the clip to the whole target.
     *
     * @param viewportWidth  target width in pixels
     * @param viewportHeight target height in pixels
     */
    public void begin(float viewportWidth, float viewportHeight) {
        vertexFloatCount = 0;
        vertexCount      = 0;
        indexCount       = 0;
        cmdCount         = 0;
        currentCmdStart  = 0;

        clipDepth = 0;
        setClip(0, 0.0f, 0.0f, viewportWidth, viewportHeight);
        clipDepth = 1;
    }

    /**
     * Closes the last open command. Call once per frame after the final shape, before
     * handing the list to the renderer.
     */
    public void end() {
        flushCommand();
    }

    // -----------------------------------------------------------------------------------
    // Clipping
    // -----------------------------------------------------------------------------------

    /**
     * Restricts subsequent shapes to a rectangle, intersected with the clip already in
     * effect — a child panel can never draw outside its parent, however it is positioned.
     *
     * <p>Coordinates are in pixels, origin top-left.</p>
     */
    public void pushClipRect(float x, float y, float width, float height) {
        if (clipDepth >= MAX_CLIP_DEPTH) return;

        int parent = (clipDepth - 1) * 4;

        float px = clipStack[parent];
        float py = clipStack[parent + 1];
        float pw = clipStack[parent + 2];
        float ph = clipStack[parent + 3];

        float left   = Math.max(x, px);
        float top    = Math.max(y, py);
        float right  = Math.min(x + width,  px + pw);
        float bottom = Math.min(y + height, py + ph);

        flushCommand();
        setClip(clipDepth, left, top, Math.max(0.0f, right - left), Math.max(0.0f, bottom - top));
        clipDepth++;
    }

    /** Restores the clip in effect before the matching {@link #pushClipRect}. */
    public void popClipRect() {
        if (clipDepth <= 1) return;

        flushCommand();
        clipDepth--;
    }

    private void setClip(int level, float x, float y, float w, float h) {
        int i = level * 4;
        clipStack[i]     = x;
        clipStack[i + 1] = y;
        clipStack[i + 2] = w;
        clipStack[i + 3] = h;
    }

    /**
     * Emits everything submitted since the last boundary as one command, tagged with the
     * clip currently in effect. Does nothing when no geometry accumulated, so pushing a
     * clip and immediately popping it costs no draw call.
     */
    private void flushCommand() {
        int pending = indexCount - currentCmdStart;
        if (pending <= 0 || cmdCount >= maxQuads) {
            currentCmdStart = indexCount;
            return;
        }

        int active = (clipDepth - 1) * 4;
        int c = cmdCount * 4;

        cmdClip[c]     = clipStack[active];
        cmdClip[c + 1] = clipStack[active + 1];
        cmdClip[c + 2] = clipStack[active + 2];
        cmdClip[c + 3] = clipStack[active + 3];

        cmdIndexOffset[cmdCount] = currentCmdStart;
        cmdIndexCount[cmdCount]  = pending;
        cmdCount++;

        currentCmdStart = indexCount;
    }

    /**
     * Submits a rounded rectangle.
     *
     * @param x      left edge in pixels, origin top-left
     * @param y      top edge in pixels
     * @param width  width in pixels
     * @param height height in pixels
     * @param radius corner radius in pixels; clamped to half the shorter side
     * @param r,g,b,a colour, straight alpha, components in 0..1
     */
    public void addRoundedRect(float x, float y, float width, float height, float radius,
                               float r, float g, float b, float a) {

        if (vertexCount / VERTICES_PER_QUAD >= maxQuads) return;

        float halfW = width  * 0.5f;
        float halfH = height * 0.5f;

        // A radius larger than the shape would make the distance field fold in on itself.
        float clampedRadius = Math.min(radius, Math.min(halfW, halfH));

        float centreX = x + halfW;
        float centreY = y + halfH;

        float outerW = halfW + AA_PADDING;
        float outerH = halfH + AA_PADDING;

        int base = vertexCount;

        // Counter-clockwise from the top-left, matching the index pattern below.
        pushVertex(centreX - outerW, centreY - outerH, -outerW, -outerH, halfW, halfH, clampedRadius, r, g, b, a);
        pushVertex(centreX + outerW, centreY - outerH,  outerW, -outerH, halfW, halfH, clampedRadius, r, g, b, a);
        pushVertex(centreX + outerW, centreY + outerH,  outerW,  outerH, halfW, halfH, clampedRadius, r, g, b, a);
        pushVertex(centreX - outerW, centreY + outerH, -outerW,  outerH, halfW, halfH, clampedRadius, r, g, b, a);

        indices[indexCount++] = base + 0;
        indices[indexCount++] = base + 1;
        indices[indexCount++] = base + 2;
        indices[indexCount++] = base + 2;
        indices[indexCount++] = base + 3;
        indices[indexCount++] = base + 0;
    }

    private void pushVertex(float px, float py, float lx, float ly,
                            float halfW, float halfH, float radius,
                            float r, float g, float b, float a) {
        int i = vertexFloatCount;

        vertices[i++] = px;
        vertices[i++] = py;
        vertices[i++] = lx;
        vertices[i++] = ly;
        vertices[i++] = halfW;
        vertices[i++] = halfH;
        vertices[i++] = radius;
        vertices[i++] = r;
        vertices[i++] = g;
        vertices[i++] = b;
        vertices[i++] = a;

        vertexFloatCount = i;
        vertexCount++;
    }

    float[] vertices()      { return vertices; }
    int[]   indices()       { return indices; }
    int     vertexFloats()  { return vertexFloatCount; }
    public int indexCount() { return indexCount; }

    // Command accessors, read by UIRenderer while issuing the frame.
    int   commandCount()             { return cmdCount; }
    int   commandIndexOffset(int i)  { return cmdIndexOffset[i]; }
    int   commandIndexCount(int i)   { return cmdIndexCount[i]; }
    float commandClipX(int i)        { return cmdClip[i * 4]; }
    float commandClipY(int i)        { return cmdClip[i * 4 + 1]; }
    float commandClipW(int i)        { return cmdClip[i * 4 + 2]; }
    float commandClipH(int i)        { return cmdClip[i * 4 + 3]; }
}
