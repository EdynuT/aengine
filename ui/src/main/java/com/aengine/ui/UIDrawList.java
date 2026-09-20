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

    private final float[] vertices;
    private final int[]   indices;

    private int vertexFloatCount = 0;
    private int vertexCount      = 0;
    private int indexCount       = 0;

    private final int maxQuads;

    public UIDrawList(int maxQuads) {
        this.maxQuads = maxQuads;
        this.vertices = new float[maxQuads * VERTICES_PER_QUAD * FLOATS_PER_VERTEX];
        this.indices  = new int[maxQuads * INDICES_PER_QUAD];
    }

    /** Rewinds the cursors. Call once per frame before submitting shapes. */
    public void begin() {
        vertexFloatCount = 0;
        vertexCount      = 0;
        indexCount       = 0;
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
}
