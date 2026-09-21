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

    /**
     * position(2) + localPos(2) + halfSize(2) + radius(1) + colour(4) + uv(2) + mode(1)
     * + borderColour(4) + borderWidth(1).
     */
    public static final int[] VERTEX_LAYOUT = { 2, 2, 2, 1, 4, 2, 1, 4, 1 };

    public static final int FLOATS_PER_VERTEX = 21;

    // -----------------------------------------------------------------------------------
    // Shading modes
    //
    // Carried per vertex rather than split across two shader programs. Switching program
    // between commands would cost a bind per switch, and a frame alternating panels and
    // text switches constantly. One program that branches on an attribute keeps the whole
    // frame on a single pipeline.
    //
    // The redundancy is accepted: a shape vertex carries unused UVs, a textured vertex
    // carries unused distance-field parameters. Packing them into shared slots would save
    // two floats per vertex at the cost of a vertex format nobody can read.
    // -----------------------------------------------------------------------------------

    /** Rounded-rectangle signed distance field, coloured by the vertex colour. */
    public static final float MODE_SHAPE = 0.0f;

    /** Samples the bound texture as RGBA, multiplied by the vertex colour. */
    public static final float MODE_TEXTURE = 1.0f;

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
    private final int[]   cmdTexture;     // backend texture handle, 0 when untextured
    private final int[]   cmdIndexOffset;
    private final int[]   cmdIndexCount;
    private int cmdCount = 0;

    /** Texture the shapes being accumulated sample from. A change closes the command. */
    private int currentTexture = 0;

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
        this.cmdTexture     = new int[maxQuads];
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
        currentTexture   = 0;

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

        cmdTexture[cmdCount]     = currentTexture;
        cmdIndexOffset[cmdCount] = currentCmdStart;
        cmdIndexCount[cmdCount]  = pending;
        cmdCount++;

        currentCmdStart = indexCount;
    }

    /**
     * Submits a rounded rectangle.
     *
     * @param x       left edge in pixels, origin top-left
     * @param y       top edge in pixels
     * @param width   width in pixels
     * @param height  height in pixels
     * @param radius  corner radius in pixels; clamped to half the shorter side
     * @param r,g,b,a colour, straight alpha, components in 0..1
     */
    public void addRoundedRect(float x, float y, float width, float height, float radius,
                               float r, float g, float b, float a) {
        addRoundedRect(x, y, width, height, radius, r, g, b, a, 0, 0, 0, 0, 0.0f);
    }

    /**
     * Submits a rounded rectangle with a border drawn inside its own outline.
     *
     * <p>The border costs no extra geometry. It is the same distance field read twice: once
     * for the outer silhouette and once offset inward by the border width, with the fill
     * blended over the border between them. That is why a 1-pixel border stays exactly one
     * pixel and stays sharp at any corner radius — it follows the curve analytically rather
     * than being a second, slightly smaller shape drawn behind the first.</p>
     *
     * @param borderWidth thickness in pixels, measured inward from the edge; 0 disables
     */
    public void addRoundedRect(float x, float y, float width, float height, float radius,
                               float r, float g, float b, float a,
                               float br, float bg, float bb, float ba, float borderWidth) {

        if (vertexCount / VERTICES_PER_QUAD >= maxQuads) return;

        if (currentTexture != 0) {
            flushCommand();
            currentTexture = 0;
        }

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
        pushVertex(centreX - outerW, centreY - outerH, -outerW, -outerH, halfW, halfH, clampedRadius, r, g, b, a, 0, 0, MODE_SHAPE, br, bg, bb, ba, borderWidth);
        pushVertex(centreX + outerW, centreY - outerH,  outerW, -outerH, halfW, halfH, clampedRadius, r, g, b, a, 0, 0, MODE_SHAPE, br, bg, bb, ba, borderWidth);
        pushVertex(centreX + outerW, centreY + outerH,  outerW,  outerH, halfW, halfH, clampedRadius, r, g, b, a, 0, 0, MODE_SHAPE, br, bg, bb, ba, borderWidth);
        pushVertex(centreX - outerW, centreY + outerH, -outerW,  outerH, halfW, halfH, clampedRadius, r, g, b, a, 0, 0, MODE_SHAPE, br, bg, bb, ba, borderWidth);

        indices[indexCount++] = base + 0;
        indices[indexCount++] = base + 1;
        indices[indexCount++] = base + 2;
        indices[indexCount++] = base + 2;
        indices[indexCount++] = base + 3;
        indices[indexCount++] = base + 0;
    }

    private void pushVertex(float px, float py, float lx, float ly,
                            float halfW, float halfH, float radius,
                            float r, float g, float b, float a,
                            float u, float v, float mode,
                            float br, float bg, float bb, float ba, float borderWidth) {
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
        vertices[i++] = u;
        vertices[i++] = v;
        vertices[i++] = mode;
        vertices[i++] = br;
        vertices[i++] = bg;
        vertices[i++] = bb;
        vertices[i++] = ba;
        vertices[i++] = borderWidth;

        vertexFloatCount = i;
        vertexCount++;
    }

    /**
     * Submits a textured quad, sampling {@code textureHandle} over the given UV rectangle.
     *
     * <p>No rounding and no distance field — the shape is the quad. Changing texture closes
     * the current command, exactly as changing the clip rectangle does, because a texture
     * binding is pipeline state too.</p>
     *
     * <p>UVs follow the texture's own convention. An OpenGL framebuffer attachment has its
     * origin at the bottom-left, so presenting one upright means passing {@code v0 = 1} and
     * {@code v1 = 0}.</p>
     *
     * @param textureHandle backend texture handle
     * @param r,g,b,a       tint multiplied over the sample; use white for the image as-is
     */
    public void addTexturedQuad(float x, float y, float width, float height,
                                float u0, float v0, float u1, float v1,
                                int textureHandle,
                                float r, float g, float b, float a) {

        if (vertexCount / VERTICES_PER_QUAD >= maxQuads) return;

        if (textureHandle != currentTexture) {
            flushCommand();
            currentTexture = textureHandle;
        }

        int base = vertexCount;

        // Distance-field parameters are unused in this mode; zero keeps them harmless.
        pushVertex(x,         y,          0, 0, 0, 0, 0, r, g, b, a, u0, v0, MODE_TEXTURE, 0, 0, 0, 0, 0);
        pushVertex(x + width, y,          0, 0, 0, 0, 0, r, g, b, a, u1, v0, MODE_TEXTURE, 0, 0, 0, 0, 0);
        pushVertex(x + width, y + height, 0, 0, 0, 0, 0, r, g, b, a, u1, v1, MODE_TEXTURE, 0, 0, 0, 0, 0);
        pushVertex(x,         y + height, 0, 0, 0, 0, 0, r, g, b, a, u0, v1, MODE_TEXTURE, 0, 0, 0, 0, 0);

        indices[indexCount++] = base + 0;
        indices[indexCount++] = base + 1;
        indices[indexCount++] = base + 2;
        indices[indexCount++] = base + 2;
        indices[indexCount++] = base + 3;
        indices[indexCount++] = base + 0;
    }

    float[] vertices()      { return vertices; }
    int[]   indices()       { return indices; }
    int     vertexFloats()  { return vertexFloatCount; }
    public int indexCount() { return indexCount; }

    // Command accessors, read by UIRenderer while issuing the frame.
    int   commandCount()             { return cmdCount; }
    int   commandTexture(int i)      { return cmdTexture[i]; }
    int   commandIndexOffset(int i)  { return cmdIndexOffset[i]; }
    int   commandIndexCount(int i)   { return cmdIndexCount[i]; }
    float commandClipX(int i)        { return cmdClip[i * 4]; }
    float commandClipY(int i)        { return cmdClip[i * 4 + 1]; }
    float commandClipW(int i)        { return cmdClip[i * 4 + 2]; }
    float commandClipH(int i)        { return cmdClip[i * 4 + 3]; }
}
