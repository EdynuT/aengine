package com.aengine.ui;

import com.aengine.graphics.RenderContext;
import com.aengine.graphics.TextureAPI;
import com.aengine.graphics.TextureFormat;
import com.aengine.utils.Logger;
import org.lwjgl.stb.STBTTBakedChar;
import org.lwjgl.stb.STBTTFontinfo;
import org.lwjgl.system.MemoryUtil;

import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;

import static org.lwjgl.stb.STBTruetype.stbtt_BakeFontBitmap;
import static org.lwjgl.stb.STBTruetype.stbtt_GetFontVMetrics;
import static org.lwjgl.stb.STBTruetype.stbtt_InitFont;
import static org.lwjgl.stb.STBTruetype.stbtt_ScaleForPixelHeight;

/**
 * A TrueType font baked into a glyph atlas at one fixed size.
 *
 * <p>This is stage 1 of the text stack in §6 of the design document: stb_truetype, Latin-1
 * only, no kerning, one pixel size per font. It exists so every other layer has real text to
 * be built against. Kerning, wrapping and scale-independent glyphs come in later stages and
 * replace what is here rather than extending it.</p>
 *
 * <p>The atlas is one byte per pixel, where each byte is glyph coverage rather than colour,
 * so the shader tints it with the vertex colour.</p>
 *
 * <p>Must be created on the GL thread, since baking ends with a texture upload.</p>
 */
public final class UIFont {

    /** First character baked: the space. */
    public static final int FIRST_CHAR = 32;

    /** Last character baked: {@code ÿ}, the end of Latin-1. */
    public static final int LAST_CHAR = 255;

    /**
     * Characters 32 to 255 — printable ASCII plus the Latin-1 supplement, which covers
     * Portuguese, Spanish, French, German and Italian. Baked as one contiguous range
     * because stb bakes ranges, and the 33 unassigned control codes inside it cost a few
     * empty atlas cells rather than a second range to manage.
     */
    public static final int CHAR_COUNT = LAST_CHAR - FIRST_CHAR + 1;

    /** First and last of the C1 control codes, which sit inside the range but have no glyph. */
    private static final int CONTROLS_FIRST = 0x7F; // DEL
    private static final int CONTROLS_LAST  = 0x9F;

    /**
     * Drawn in place of any character without a glyph. A visible substitute rather than
     * nothing, so a missing character reads as missing instead of silently vanishing.
     */
    private static final char REPLACEMENT = '?';

    /** Floats stored per glyph — see the layout comment on {@link #metrics}. */
    private static final int STRIDE = 7;

    private final float pixelHeight;
    private final int   atlasWidth;
    private final int   atlasHeight;

    /**
     * Vertical metrics in pixels, read from the font and scaled to {@link #pixelHeight}.
     *
     * <p>All three are positive and measured the way the interface addresses the screen,
     * y downwards: {@code ascent} is how far the tallest glyphs rise above the baseline,
     * {@code descent} how far descenders drop below it, {@code lineGap} the extra leading
     * the designer asked for between lines. stb reports the descent as negative, since it
     * works in font units with y upwards; it is negated once here so nothing above this
     * class has to remember which way each number points.</p>
     *
     * <p>They come from the font rather than from {@code pixelHeight}, which is only the
     * size stb rasterises at and says nothing about where the ink actually lands. Deriving
     * a baseline from it is what makes text drift when the font changes.</p>
     */
    private final float ascent;
    private final float descent;
    private final float lineGap;

    /**
     * {@code ascent + descent + lineGap}, rounded to a whole pixel.
     *
     * <p>Rounded because line spacing is repeatedly added. {@link #placeGlyph} snaps each
     * glyph to a whole pixel to keep the atlas sampled 1:1, so a fractional line height
     * would put successive baselines at 14.6, 29.2, 43.8 — and those snap to gaps of 15,
     * 14, 15. Two lines hide it; a paragraph does not.</p>
     */
    private final int lineHeight;

    private final TextureAPI atlas;

    /**
     * Glyph placement, copied out of stb's native structs at construction:
     * {@code x0, y0, x1, y1} (atlas pixels), {@code xoff, yoff} (offset from the pen to the
     * glyph's top-left) and {@code xadvance}, per glyph.
     *
     * <p>Copied rather than read through {@code STBTTBakedChar.Buffer.get(i)}, which creates a
     * new wrapper object on every call — one allocation per character per frame.</p>
     */
    private final float[] metrics = new float[CHAR_COUNT * STRIDE];

    /**
     * @param resourcePath classpath path of the .ttf, e.g. {@code /fonts/DejaVuSans/DejaVuSans.ttf}
     * @param pixelHeight  size the glyphs are rasterised at, in pixels
     * @param atlasWidth   atlas width in pixels
     * @param atlasHeight  atlas height in pixels; baking fails loudly if the glyphs do not fit
     */
    public UIFont(String resourcePath, float pixelHeight, int atlasWidth, int atlasHeight) {
        this.pixelHeight = pixelHeight;
        this.atlasWidth  = atlasWidth;
        this.atlasHeight = atlasHeight;

        ByteBuffer ttf    = readResource(resourcePath);
        ByteBuffer bitmap = MemoryUtil.memAlloc(atlasWidth * atlasHeight);
        STBTTBakedChar.Buffer baked = STBTTBakedChar.malloc(CHAR_COUNT);
        STBTTFontinfo info = STBTTFontinfo.malloc();

        try {
            if (!stbtt_InitFont(info, ttf)) {
                throw new IllegalStateException("Not a font stb_truetype can read: " + resourcePath);
            }

            // The same scale stbtt_BakeFontBitmap derives internally, so the metrics and the
            // baked glyphs describe one font at one size rather than two near-agreeing ones.
            float scale = stbtt_ScaleForPixelHeight(info, pixelHeight);

            int[] a = new int[1], d = new int[1], g = new int[1];
            stbtt_GetFontVMetrics(info, a, d, g);

            this.ascent  =  a[0] * scale;
            this.descent = -d[0] * scale;  // stb reports it negative: font units point up
            this.lineGap =  g[0] * scale;
            this.lineHeight = Math.round(this.ascent + this.descent + this.lineGap);

            int result = stbtt_BakeFontBitmap(ttf, pixelHeight, bitmap,
                                              atlasWidth, atlasHeight, FIRST_CHAR, baked);

            // Positive: the first unused row. Zero or negative: not every glyph fit.
            if (result <= 0) {
                throw new IllegalStateException(String.format(
                    "Glyph atlas too small: %s at %.0fpx fits only %d of %d characters in %dx%d.",
                    resourcePath, pixelHeight, -result, CHAR_COUNT, atlasWidth, atlasHeight));
            }

            for (int i = 0; i < CHAR_COUNT; i++) {
                STBTTBakedChar glyph = baked.get(i);
                int m = i * STRIDE;
                metrics[m]     = glyph.x0();
                metrics[m + 1] = glyph.y0();
                metrics[m + 2] = glyph.x1();
                metrics[m + 3] = glyph.y1();
                metrics[m + 4] = glyph.xoff();
                metrics[m + 5] = glyph.yoff();
                metrics[m + 6] = glyph.xadvance();
            }

            this.atlas = RenderContext.createTexture(atlasWidth, atlasHeight, TextureFormat.R8, bitmap);

            Logger.info(Logger.System.RENDERER,
                "Font baked: %s at %.0fpx, %d glyphs, atlas %dx%d (%d of %d rows used); "
                + "ascent %.1f, descent %.1f, gap %.1f, line height %d.",
                resourcePath, pixelHeight, CHAR_COUNT, atlasWidth, atlasHeight, result, atlasHeight,
                this.ascent, this.descent, this.lineGap, this.lineHeight);
        } finally {
            // The texture holds its own copy of the pixels, and both the glyph placements and
            // the vertical metrics are copied out, so none of the native memory is needed past
            // this point. Kerning in step 3b-3 asks the font for pair corrections at layout
            // time, which will mean keeping the buffer and the font info alive; not yet.
            info.free();
            baked.free();
            MemoryUtil.memFree(bitmap);
            MemoryUtil.memFree(ttf);
        }
    }

    /**
     * Places one character at the pen and writes its quad into {@code out}:
     * {@code x0, y0, x1, y1} in screen pixels, then {@code u0, v0, u1, v1} in the atlas.
     *
     * <p>Positions are rounded to whole pixels. The atlas is rasterised at 1:1, so a glyph
     * landing between pixels would be resampled and blurred.</p>
     *
     * @param c        the character; one without a glyph is drawn as the replacement
     * @param penX     pen position, pixels
     * @param baseline baseline position, pixels, origin top-left
     * @param out      at least 8 floats, reused by the caller
     * @return the pen position after this character
     */
    float placeGlyph(char c, float penX, float baseline, float[] out) {
        // Outside Latin-1, or one of the control codes inside it: both baked to nothing, and
        // a character the font cannot show should read as missing rather than as a blank.
        int index = (c < FIRST_CHAR || c > LAST_CHAR || (c >= CONTROLS_FIRST && c <= CONTROLS_LAST))
                  ? REPLACEMENT - FIRST_CHAR
                  : c - FIRST_CHAR;

        int m = index * STRIDE;
        float gx0 = metrics[m],     gy0 = metrics[m + 1];
        float gx1 = metrics[m + 2], gy1 = metrics[m + 3];

        float left = (float) Math.floor(penX     + metrics[m + 4] + 0.5f);
        float top  = (float) Math.floor(baseline + metrics[m + 5] + 0.5f);

        out[0] = left;
        out[1] = top;
        out[2] = left + (gx1 - gx0);
        out[3] = top  + (gy1 - gy0);

        out[4] = gx0 / atlasWidth;
        out[5] = gy0 / atlasHeight;
        out[6] = gx1 / atlasWidth;
        out[7] = gy1 / atlasHeight;

        return penX + metrics[m + 6];
    }

    /**
     * Reads a classpath resource into native memory, which stb_truetype requires.
     *
     * <p>Deliberately not {@code FileSystem}: that resolves paths inside the mounted project,
     * and a font bundled with the editor is not a project asset.</p>
     */
    private static ByteBuffer readResource(String path) {
        try (InputStream in = UIFont.class.getResourceAsStream(path)) {
            if (in == null) {
                throw new IllegalStateException("Font resource not found on the classpath: " + path);
            }
            byte[] bytes = in.readAllBytes();
            ByteBuffer buffer = MemoryUtil.memAlloc(bytes.length);
            buffer.put(bytes).flip();
            return buffer;
        } catch (IOException e) {
            throw new IllegalStateException("Could not read font resource: " + path, e);
        }
    }

    /**
     * Baseline for a line of text whose top edge sits at {@code top}.
     *
     * <p>Text is placed by its top almost everywhere — a label centred in a button, a row in
     * a list — while glyphs hang from a baseline, and this is the one conversion between the
     * two. Rounded, so that every line stacked from here starts on a whole pixel.</p>
     */
    public float baselineForTop(float top) {
        return Math.round(top + ascent);
    }

    /** Height above the baseline, pixels. */
    public float ascent()  { return ascent; }

    /** Depth below the baseline, positive downwards, pixels. */
    public float descent() { return descent; }

    /** Extra leading the font asks for between lines, pixels; often zero. */
    public float lineGap() { return lineGap; }

    /**
     * Distance from one line's top to the next, in whole pixels. Adding this repeatedly is
     * how lines stack; see the field for why it is not fractional.
     */
    public int lineHeight() { return lineHeight; }

    /** Backend texture handle of the atlas. */
    public int   atlasHandle() { return atlas.getID(); }
    public int   atlasWidth()  { return atlasWidth; }
    public int   atlasHeight() { return atlasHeight; }

    /**
     * The size the glyphs were rasterised at. Not a line height and not an ascent — use
     * {@link #lineHeight()} and {@link #baselineForTop(float)} to position text.
     */
    public float pixelHeight() { return pixelHeight; }

    public void cleanup() {
        atlas.cleanup();
    }
}
