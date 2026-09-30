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
     * Creates the renderer, draw list, layout, tree and widgets. No font is loaded yet; call
     * {@link #loadFont} before drawing text. Must be called on the GL thread.
     *
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
     * @return the new font, now the current one
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

    /**
     * Starts a frame. Everything submitted after this lands in one draw list.
     *
     * @param width  target width in pixels
     * @param height target height in pixels
     */
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

    /**
     * Draws a rounded rectangle.
     *
     * @param x      left edge in pixels, origin top-left
     * @param y      top edge in pixels
     * @param width  width in pixels
     * @param height height in pixels
     * @param radius corner radius in pixels
     * @param r      red, 0..1
     * @param g      green, 0..1
     * @param b      blue, 0..1
     * @param a      alpha, 0..1
     * @see AegisDrawList#addRoundedRect(float, float, float, float, float, float, float, float, float)
     */
    public void addRoundedRect(float x, float y, float width, float height, float radius,
                               float r, float g, float b, float a) {
        drawList.addRoundedRect(x, y, width, height, radius, r, g, b, a);
    }

    /**
     * Draws a rounded rectangle with a border inside its edge.
     *
     * @param x           left edge in pixels, origin top-left
     * @param y           top edge in pixels
     * @param width       width in pixels
     * @param height      height in pixels
     * @param radius      corner radius in pixels
     * @param r           fill red, 0..1
     * @param g           fill green, 0..1
     * @param b           fill blue, 0..1
     * @param a           fill alpha, 0..1
     * @param br          border red, 0..1
     * @param bg          border green, 0..1
     * @param bb          border blue, 0..1
     * @param ba          border alpha, 0..1
     * @param borderWidth border thickness in pixels; 0 for none
     * @see AegisDrawList#addRoundedRect(float, float, float, float, float, float, float, float, float, float, float, float, float, float)
     */
    public void addRoundedRect(float x, float y, float width, float height, float radius,
                               float r, float g, float b, float a,
                               float br, float bg, float bb, float ba, float borderWidth) {
        drawList.addRoundedRect(x, y, width, height, radius,
                                r, g, b, a, br, bg, bb, ba, borderWidth);
    }

    /**
     * Draws a texture, or part of one.
     *
     * @param x             left edge in pixels, origin top-left
     * @param y             top edge in pixels
     * @param width         width in pixels
     * @param height        height in pixels
     * @param u0            texture U at the left edge
     * @param v0            texture V at the top edge
     * @param u1            texture U at the right edge
     * @param v1            texture V at the bottom edge
     * @param textureHandle backend texture handle
     * @param r             tint red, 0..1
     * @param g             tint green, 0..1
     * @param b             tint blue, 0..1
     * @param a             tint alpha, 0..1
     * @see AegisDrawList#addTexturedQuad
     */
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
     * @param x    left edge of the first character
     * @param top  top of the line box
     * @param text the characters; read, not kept
     * @param r    red, 0..1
     * @param g    green, 0..1
     * @param b    blue, 0..1
     * @param a    alpha, 0..1
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
     * @param x        left edge of the first character
     * @param baseline y of the baseline
     * @param text     the characters; read, not kept
     * @param r        red, 0..1
     * @param g        green, 0..1
     * @param b        blue, 0..1
     * @param a        alpha, 0..1
     * @return the pen position after the last character
     * @see AegisDrawList#addText
     */
    public float addText(float x, float baseline, CharSequence text,
                         float r, float g, float b, float a) {
        return drawList.addText(requireFont(), x, baseline, text, r, g, b, a);
    }

    /**
     * Draws a paragraph broken to fit {@code maxWidth}, in the current font.
     *
     * @param x        left edge of every line
     * @param top      top of the first line box
     * @param maxWidth widest a line may be, in pixels
     * @param text     the paragraph; read, not kept
     * @param r        red, 0..1
     * @param g        green, 0..1
     * @param b        blue, 0..1
     * @param a        alpha, 0..1
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
     *
     * @param text     the paragraph
     * @param maxWidth widest a line may be, in pixels
     * @return the height of all its lines
     */
    public float wrappedHeight(CharSequence text, float maxWidth) {
        AegisFont f = requireFont();
        return f.wrappedLineCount(text, maxWidth) * f.lineHeight();
    }

    /**
     * How many paragraph layouts have been computed rather than remembered.
     *
     * @return the current font's count since it was loaded
     */
    public int layoutRecomputes() { return requireFont().layoutRecomputes(); }

    /**
     * How many paragraph layouts have been served from memory.
     *
     * @return the current font's count since it was loaded
     */
    public int layoutHits() { return requireFont().layoutHits(); }

    /**
     * The width the current font would draw this string at, in pixels.
     *
     * @param text the characters to measure
     * @return the width, kerning included
     */
    public float measure(CharSequence text) { return requireFont().measure(text); }

    /**
     * Distance from one line's top to the next, in whole pixels, in the current font.
     *
     * @return the line height
     */
    public int lineHeight() { return requireFont().lineHeight(); }

    // -----------------------------------------------------------------------------------
    // Clipping
    // -----------------------------------------------------------------------------------

    /**
     * Restricts what is drawn next to a rectangle, inside any clip already in effect.
     *
     * @param x      left edge in pixels
     * @param y      top edge in pixels
     * @param width  width in pixels
     * @param height height in pixels
     * @see AegisDrawList#pushClipRect
     */
    public void pushClipRect(float x, float y, float width, float height) {
        drawList.pushClipRect(x, y, width, height);
    }

    /**
     * Restores the clip in effect before the matching {@link #pushClipRect}.
     *
     * @see AegisDrawList#popClipRect
     */
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
     *
     * @return the layout, the same instance for this object's lifetime
     */
    public AegisLayout layout() { return layout; }

    /**
     * The interaction state over the layout's nodes: which one the pointer is over, pressed
     * or clicked.
     *
     * <p>Shares the layout's handles — a node is one {@code int} in both — and is handed out
     * as an object for the same reason the layout is. Update it once a frame, after the
     * layout's solve.</p>
     *
     * @return the tree, the same instance for this object's lifetime
     */
    public AegisTree tree() { return tree; }

    /**
     * The widgets: buttons, checkboxes, sliders, text fields and text boxes.
     *
     * <p>Made on the layout's nodes and the tree's state, and handed out as an object like
     * both. Kept in a short variable, it is called {@code ae} — never a generic {@code ui}:</p>
     *
     * <pre>{@code
     * AegisWidgets ae = aegis.widgets();
     * int play = ae.button(toolbar, "Play");
     * }</pre>
     *
     * @return the widgets, the same instance for this object's lifetime
     */
    public AegisWidgets widgets() { return widgets; }

    // -----------------------------------------------------------------------------------
    // Escape hatches and lifecycle
    // -----------------------------------------------------------------------------------

    /**
     * The current font, for code that needs its metrics or wants to pass it explicitly.
     *
     * @return the font, or {@code null} before {@link #loadFont}
     */
    public AegisFont font() { return font; }

    /**
     * The draw list underneath, for code working at the layer rather than at the front door.
     *
     * @return the draw list
     */
    public AegisDrawList drawList() { return drawList; }

    /** Releases the font and the renderer. Must run on the GL thread, before the context goes. */
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
