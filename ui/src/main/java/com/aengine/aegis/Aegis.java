package com.aengine.aegis;

/**
 * Aegis — the entry point to AEngine's UI framework.
 *
 * <p>One object to create and one object to call: the renderer, the draw list and the
 * current font are wired together here instead of at every call site, and a frame reads as
 * a sequence of drawing calls rather than as a coordination of three collaborators.</p>
 *
 * <pre>{@code
 * Aegis aegis = new Aegis(512);
 * aegis.loadFont("/fonts/DejaVuSans/DejaVuSans.ttf", 18.0f, 512, 128);
 *
 * // once per frame
 * aegis.begin(width, height);
 * aegis.addRoundedRect(x, y, 220f, 36f, 8f, 0.16f, 0.18f, 0.23f, 1f);
 * aegis.addTextTop(x + 14f, y + 9f, "Save scene", 0.9f, 0.92f, 0.95f, 1f);
 * aegis.end();
 * }</pre>
 *
 * <p>The layers underneath stay public. {@link AegisDrawList}, {@link AegisRenderer} and
 * {@link AegisFont} are independently useful and independently testable, which is what makes
 * the framework's design work; this class is the front door, not a wall. Code that needs the
 * raw layer reaches it through {@link #drawList()}.</p>
 *
 * <p><strong>What must not happen to this class.</strong> Every drawing call added below is a
 * forwarding method, which is cheap until it is not. When the retained widget tree arrives,
 * it must be reached <em>through</em> Aegis — a tree object handed out from here — rather than
 * flattened into one method per widget, or this becomes a class with two hundred members and
 * the layering it fronts stops meaning anything.</p>
 *
 * <p>Must be created on the GL thread: the renderer compiles shaders and the font uploads a
 * texture.</p>
 */
public final class Aegis {

    /**
     * Layout nodes available, allocated once. An editor frame of a few dozen panels and a few
     * hundred controls sits well inside it; filling it fails loudly at build time, never in
     * the frame loop.
     */
    private static final int LAYOUT_NODES = 1024;

    private final AegisRenderer renderer;
    private final AegisDrawList drawList;
    private final AegisLayout   layout;
    private final AegisTree     tree;
    private final AegisWidgets  widgets;

    /**
     * The font text is drawn with when a call does not name one.
     *
     * <p>Held here rather than passed per call because every label in an editor uses the same
     * font, and threading it through every text call is the kind of repetition a front door
     * exists to remove.</p>
     */
    private AegisFont font;

    /** Viewport of the frame being recorded, kept so {@link #end()} can present it. */
    private int viewportWidth;
    private int viewportHeight;

    /**
     * @param maxQuads shapes per frame, pre-allocated; one visible character is one quad, so
     *                 a screen of text counts for more than it looks
     */
    public Aegis(int maxQuads) {
        this.renderer = new AegisRenderer(maxQuads);
        this.drawList = new AegisDrawList(maxQuads);
        this.layout   = new AegisLayout(LAYOUT_NODES);
        this.tree     = new AegisTree(layout, LAYOUT_NODES);
        this.widgets  = new AegisWidgets(this, layout, tree, LAYOUT_NODES);
    }

    /**
     * Bakes a font and makes it the one text is drawn with.
     *
     * <p>Calling it again replaces the current font; the previous one is released, since
     * nothing else holds it. Keeping several fonts alive at once is what a font set will do
     * later, and is deliberately not this method.</p>
     *
     * @param resourcePath classpath path of the .ttf
     * @param pixelHeight  size the glyphs are rasterised at, in pixels
     * @param atlasWidth   atlas width in pixels
     * @param atlasHeight  atlas height in pixels; baking fails loudly if the glyphs do not fit
     */
    public AegisFont loadFont(String resourcePath, float pixelHeight,
                              int atlasWidth, int atlasHeight) {
        AegisFont loaded = new AegisFont(resourcePath, pixelHeight, atlasWidth, atlasHeight);
        if (font != null) font.cleanup();
        font = loaded;
        return loaded;
    }

    // -----------------------------------------------------------------------------------
    // Frame
    // -----------------------------------------------------------------------------------

    /** Starts a frame. Everything submitted after this lands in one draw list. */
    public void begin(int width, int height) {
        viewportWidth  = width;
        viewportHeight = height;
        drawList.begin(width, height);
    }

    /**
     * Closes the frame and draws it.
     *
     * <p>Recording and presenting are one call because every call site did both, in that
     * order, every time. Separating them bought no freedom and only offered the chance to
     * forget the second.</p>
     */
    public void end() {
        drawList.end();
        renderer.render(drawList, viewportWidth, viewportHeight);
    }

    // -----------------------------------------------------------------------------------
    // Shapes
    // -----------------------------------------------------------------------------------

    /** @see AegisDrawList#addRoundedRect(float, float, float, float, float, float, float, float, float) */
    public void addRoundedRect(float x, float y, float width, float height, float radius,
                               float r, float g, float b, float a) {
        drawList.addRoundedRect(x, y, width, height, radius, r, g, b, a);
    }

    /** @see AegisDrawList#addRoundedRect(float, float, float, float, float, float, float, float, float, float, float, float, float, float) */
    public void addRoundedRect(float x, float y, float width, float height, float radius,
                               float r, float g, float b, float a,
                               float br, float bg, float bb, float ba, float borderWidth) {
        drawList.addRoundedRect(x, y, width, height, radius,
                                r, g, b, a, br, bg, bb, ba, borderWidth);
    }

    /** @see AegisDrawList#addTexturedQuad */
    public void addTexturedQuad(float x, float y, float width, float height,
                                float u0, float v0, float u1, float v1,
                                int textureHandle,
                                float r, float g, float b, float a) {
        drawList.addTexturedQuad(x, y, width, height, u0, v0, u1, v1, textureHandle, r, g, b, a);
    }

    // -----------------------------------------------------------------------------------
    // Text
    // -----------------------------------------------------------------------------------

    /**
     * Draws a line of text in the current font, with the top of its line box at {@code top}.
     *
     * @return the pen position after the last character — the right edge of the line
     * @see AegisDrawList#addTextTop
     */
    public float addTextTop(float x, float top, CharSequence text,
                            float r, float g, float b, float a) {
        return drawList.addTextTop(requireFont(), x, top, text, r, g, b, a);
    }

    /**
     * Draws a line of text in the current font, sitting on {@code baseline}.
     *
     * <p>{@link #addTextTop} is the usual one; this is for the case where baselines themselves
     * must align, such as two sizes sharing a row.</p>
     *
     * @see AegisDrawList#addText
     */
    public float addText(float x, float baseline, CharSequence text,
                         float r, float g, float b, float a) {
        return drawList.addText(requireFont(), x, baseline, text, r, g, b, a);
    }

    /**
     * Draws a paragraph broken to fit {@code maxWidth}, in the current font.
     *
     * @return the y below the last line — where the next thing can start
     * @see AegisDrawList#addTextWrapped
     */
    public float addTextWrapped(float x, float top, float maxWidth, CharSequence text,
                                float r, float g, float b, float a) {
        return drawList.addTextWrapped(requireFont(), x, top, maxWidth, text, r, g, b, a);
    }

    /**
     * How tall a paragraph will be when wrapped to {@code maxWidth}, in pixels.
     *
     * <p>Answered from the layout cache, so asking in order to size a panel and then drawing
     * the paragraph into it costs one wrap between them, not two.</p>
     */
    public float wrappedHeight(CharSequence text, float maxWidth) {
        AegisFont f = requireFont();
        return f.wrappedLineCount(text, maxWidth) * f.lineHeight();
    }

    /** How many paragraph layouts have been computed rather than remembered. */
    public int layoutRecomputes() { return requireFont().layoutRecomputes(); }

    /** How many paragraph layouts have been served from memory. */
    public int layoutHits() { return requireFont().layoutHits(); }

    /** The width the current font would draw this string at, in pixels. */
    public float measure(CharSequence text) { return requireFont().measure(text); }

    /** Distance from one line's top to the next, in whole pixels, in the current font. */
    public int lineHeight() { return requireFont().lineHeight(); }

    // -----------------------------------------------------------------------------------
    // Clipping
    // -----------------------------------------------------------------------------------

    /** @see AegisDrawList#pushClipRect */
    public void pushClipRect(float x, float y, float width, float height) {
        drawList.pushClipRect(x, y, width, height);
    }

    /** @see AegisDrawList#popClipRect */
    public void popClipRect() {
        drawList.popClipRect();
    }

    // -----------------------------------------------------------------------------------
    // Layout
    // -----------------------------------------------------------------------------------

    /**
     * The layout: rows and columns that place what they contain.
     *
     * <p>Handed out as an object rather than forwarded method by method — the rule in the
     * class comment. Build the tree on it once, solve it each frame, and read each node's
     * rectangle back to draw there.</p>
     */
    public AegisLayout layout() { return layout; }

    /**
     * The interaction state over the layout's nodes: which one the pointer is over, pressed
     * or clicked.
     *
     * <p>Shares the layout's handles — a node is one {@code int} in both — and is handed out
     * as an object for the same reason the layout is. Update it once a frame, after the
     * layout's solve.</p>
     */
    public AegisTree tree() { return tree; }

    /**
     * The widgets: buttons now, and the rest of the controls as they arrive.
     *
     * <p>Made on the layout's nodes and the tree's state, and handed out as an object like
     * both. Kept in a short variable, it is called {@code ae} — never a generic {@code ui}:</p>
     *
     * <pre>{@code
     * AegisWidgets ae = aegis.widgets();
     * int play = ae.button(toolbar, "Play");
     * }</pre>
     */
    public AegisWidgets widgets() { return widgets; }

    // -----------------------------------------------------------------------------------
    // Escape hatches and lifecycle
    // -----------------------------------------------------------------------------------

    /** The current font, for code that needs its metrics or wants to pass it explicitly. */
    public AegisFont font() { return font; }

    /** The draw list underneath, for code working at the layer rather than at the front door. */
    public AegisDrawList drawList() { return drawList; }

    public void cleanup() {
        if (font != null) font.cleanup();
        renderer.cleanup();
    }

    /**
     * Fails with a direct explanation rather than a {@code NullPointerException} from inside
     * the draw list, which is where the mistake would otherwise surface.
     */
    private AegisFont requireFont() {
        if (font == null) {
            throw new IllegalStateException(
                "No font loaded. Call loadFont(...) once before drawing text, "
                + "or pass a font explicitly through drawList().");
        }
        return font;
    }
}
