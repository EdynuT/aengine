package com.aengine.aegis;

import com.aengine.graphics.VertexAttribute;

/**
 * The frame's interface geometry, accumulated as vertices and indices.
 *
 * <p>This is layer L1 of the framework described in
 * {@code docs/UI_FRAMEWORK_ARCHITECTURE.md}: everything above it — text, layout, widgets —
 * ultimately describes what it should draw, and {@link AegisRenderer} is the only thing that
 * reads it. Nothing here touches a graphics API.</p>
 *
 * <p><strong>Allocation:</strong> both arrays are reserved once at construction and
 * rewritten in place. {@link #begin(float, float)} rewinds the write cursors rather than
 * clearing storage, so a frame of interface geometry allocates nothing.</p>
 */
public final class AegisDrawList {

    /**
     * position + localPos + halfSize + radius + colour + uv + mode + borderColour +
     * borderWidth. Both colours are packed RGBA8, one word each instead of four floats.
     */
    public static final VertexAttribute[] VERTEX_LAYOUT = {
        VertexAttribute.FLOAT2,   // position
        VertexAttribute.FLOAT2,   // localPos
        VertexAttribute.FLOAT2,   // halfSize
        VertexAttribute.FLOAT1,   // radius
        VertexAttribute.RGBA8,    // colour
        VertexAttribute.FLOAT2,   // uv
        VertexAttribute.FLOAT1,   // mode
        VertexAttribute.RGBA8,    // borderColour
        VertexAttribute.FLOAT1,   // borderWidth
    };

    /** 4-byte words per vertex. Was 21 floats before the colours were packed. */
    public static final int WORDS_PER_VERTEX = 13;

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

    /**
     * Samples the red channel of a one-channel glyph atlas as coverage, tinted by the vertex
     * colour. Separate from {@link #MODE_TEXTURE} because the atlas holds coverage, not
     * colour: sampled as RGBA it would come out red on black.
     */
    public static final float MODE_TEXT = 2.0f;

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

    /**
     * Vertex words. {@code int} rather than {@code float} so packed colours travel
     * bit-exact; floats are stored through {@link Float#floatToRawIntBits}.
     */
    private final int[] vertices;
    private final int[] indices;

    private int vertexWordCount = 0;
    private int vertexCount     = 0;
    private int indexCount      = 0;

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

    /** One glyph's quad, written by {@link AegisFont#placeGlyph} and reused for every glyph. */
    private final float[] glyphQuad = new float[8];

    public AegisDrawList(int maxQuads) {
        this.maxQuads = maxQuads;
        this.vertices = new int[maxQuads * VERTICES_PER_QUAD * WORDS_PER_VERTEX];
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
        vertexWordCount  = 0;
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

        int fill   = packColor(r, g, b, a);
        int border = packColor(br, bg, bb, ba);

        int base = vertexCount;

        // Counter-clockwise from the top-left, matching the index pattern below.
        pushVertex(centreX - outerW, centreY - outerH, -outerW, -outerH, halfW, halfH, clampedRadius, fill, 0, 0, MODE_SHAPE, border, borderWidth);
        pushVertex(centreX + outerW, centreY - outerH,  outerW, -outerH, halfW, halfH, clampedRadius, fill, 0, 0, MODE_SHAPE, border, borderWidth);
        pushVertex(centreX + outerW, centreY + outerH,  outerW,  outerH, halfW, halfH, clampedRadius, fill, 0, 0, MODE_SHAPE, border, borderWidth);
        pushVertex(centreX - outerW, centreY + outerH, -outerW,  outerH, halfW, halfH, clampedRadius, fill, 0, 0, MODE_SHAPE, border, borderWidth);

        indices[indexCount++] = base + 0;
        indices[indexCount++] = base + 1;
        indices[indexCount++] = base + 2;
        indices[indexCount++] = base + 2;
        indices[indexCount++] = base + 3;
        indices[indexCount++] = base + 0;
    }

    private void pushVertex(float px, float py, float lx, float ly,
                            float halfW, float halfH, float radius,
                            int color, float u, float v, float mode,
                            int borderColor, float borderWidth) {
        int i = vertexWordCount;

        vertices[i++] = Float.floatToRawIntBits(px);
        vertices[i++] = Float.floatToRawIntBits(py);
        vertices[i++] = Float.floatToRawIntBits(lx);
        vertices[i++] = Float.floatToRawIntBits(ly);
        vertices[i++] = Float.floatToRawIntBits(halfW);
        vertices[i++] = Float.floatToRawIntBits(halfH);
        vertices[i++] = Float.floatToRawIntBits(radius);
        vertices[i++] = color;
        vertices[i++] = Float.floatToRawIntBits(u);
        vertices[i++] = Float.floatToRawIntBits(v);
        vertices[i++] = Float.floatToRawIntBits(mode);
        vertices[i++] = borderColor;
        vertices[i++] = Float.floatToRawIntBits(borderWidth);

        vertexWordCount = i;
        vertexCount++;
    }

    /**
     * Packs a straight-alpha colour into one RGBA8 word, red in the lowest byte — see
     * {@link VertexAttribute#RGBA8}. Components outside 0..1 are clamped.
     */
    private static int packColor(float r, float g, float b, float a) {
        return (channel(a) << 24) | (channel(b) << 16) | (channel(g) << 8) | channel(r);
    }

    private static int channel(float c) {
        if (c <= 0.0f) return 0;
        if (c >= 1.0f) return 255;
        return (int) (c * 255.0f + 0.5f);
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

        useTexture(textureHandle);
        pushSampledQuad(x, y, x + width, y + height, u0, v0, u1, v1,
                        packColor(r, g, b, a), MODE_TEXTURE);
    }

    /**
     * Submits a line of text with its top edge at {@code top}, the line occupying
     * {@link AegisFont#lineHeight()} pixels downwards from there.
     *
     * <p>This is how interface code positions text: a label in a button, a row in a list and
     * a wrapped paragraph all know where the line box starts, not where the baseline falls.
     * The conversion belongs to the font, which is the only thing that knows its ascent.</p>
     *
     * @return the pen position after the last character — the right edge of the line
     */
    public float addTextTop(AegisFont font, float x, float top, CharSequence text,
                            float r, float g, float b, float a) {
        return addText(font, x, font.baselineForTop(top), text, r, g, b, a);
    }

    /**
     * Submits a line of text with its baseline at {@code baseline}.
     *
     * <p>The baseline rather than the top, because a font's glyphs hang from it: capitals
     * sit on it, descenders like {@code g} and {@code p} drop below. Most callers want
     * {@link #addTextTop} instead; this is the form to use when the baseline itself is what
     * must line up, such as text of two sizes sharing one row.</p>
     *
     * <p>Takes a {@link CharSequence} so callers can pass a reused {@code StringBuilder}
     * instead of building a {@code String} per frame. One quad per character, all in the
     * font's atlas, so a line of text is one command unless a clip or texture change
     * interrupts it.</p>
     *
     * <p>Stage 1 limits: Latin-1 only, no kerning, no wrapping, the font's single baked
     * size. A character without a glyph is drawn as {@code ?}.</p>
     *
     * @return the pen position after the last character — the right edge of the line
     */
    public float addText(AegisFont font, float x, float baseline, CharSequence text,
                         float r, float g, float b, float a) {

        useTexture(font.atlasHandle());
        int tint = packColor(r, g, b, a);

        float pen = x;
        for (int i = 0; i < text.length(); i++) {
            if (vertexCount / VERTICES_PER_QUAD >= maxQuads) break;

            char c = text.charAt(i);
            float next = font.placeGlyph(c, pen, baseline, glyphQuad);

            // A space advances the pen but has no visible pixels, so it gets no quad.
            if (c != ' ') {
                pushSampledQuad(glyphQuad[0], glyphQuad[1], glyphQuad[2], glyphQuad[3],
                                glyphQuad[4], glyphQuad[5], glyphQuad[6], glyphQuad[7],
                                tint, MODE_TEXT);
            }
            pen = next;
        }
        return pen;
    }

    /** Switches the texture the next shapes sample from, closing the command if it changes. */
    private void useTexture(int textureHandle) {
        if (textureHandle != currentTexture) {
            flushCommand();
            currentTexture = textureHandle;
        }
    }

    /** One axis-aligned quad sampling the current texture. No distance field. */
    private void pushSampledQuad(float x0, float y0, float x1, float y1,
                                 float u0, float v0, float u1, float v1,
                                 int tint, float mode) {
        int base = vertexCount;

        // Distance-field parameters are unused in these modes; zero keeps them harmless.
        pushVertex(x0, y0, 0, 0, 0, 0, 0, tint, u0, v0, mode, 0, 0);
        pushVertex(x1, y0, 0, 0, 0, 0, 0, tint, u1, v0, mode, 0, 0);
        pushVertex(x1, y1, 0, 0, 0, 0, 0, tint, u1, v1, mode, 0, 0);
        pushVertex(x0, y1, 0, 0, 0, 0, 0, tint, u0, v1, mode, 0, 0);

        indices[indexCount++] = base + 0;
        indices[indexCount++] = base + 1;
        indices[indexCount++] = base + 2;
        indices[indexCount++] = base + 2;
        indices[indexCount++] = base + 3;
        indices[indexCount++] = base + 0;
    }

    int[] vertices()        { return vertices; }
    int[] indices()         { return indices; }
    int   vertexWords()     { return vertexWordCount; }
    public int indexCount() { return indexCount; }

    // Command accessors, read by AegisRenderer while issuing the frame.
    int   commandCount()             { return cmdCount; }
    int   commandTexture(int i)      { return cmdTexture[i]; }
    int   commandIndexOffset(int i)  { return cmdIndexOffset[i]; }
    int   commandIndexCount(int i)   { return cmdIndexCount[i]; }
    float commandClipX(int i)        { return cmdClip[i * 4]; }
    float commandClipY(int i)        { return cmdClip[i * 4 + 1]; }
    float commandClipW(int i)        { return cmdClip[i * 4 + 2]; }
    float commandClipH(int i)        { return cmdClip[i * 4 + 3]; }
}
