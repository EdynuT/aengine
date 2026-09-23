package com.aengine.aegis;

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
import static org.lwjgl.stb.STBTruetype.stbtt_GetCodepointKernAdvance;
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
public final class AegisFont {

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
     * Per-pair spacing corrections in pixels, indexed {@code previous * CHAR_COUNT + current}.
     *
     * <p>Kerning is what stops {@code AV} and {@code To} from leaving a hole: the advance
     * stored per glyph is correct in isolation and wrong beside certain neighbours, so the
     * font ships a correction for the pairs that need one. Almost all are negative, pulling
     * the second glyph left.</p>
     *
     * <p>Computed once at bake and stored dense. Only about 2% of the pairs in Latin-1 carry
     * a correction, so the table is mostly zeros — but dense costs 200 KB and makes the
     * lookup an array index, where sparse would cost a hash per character. <strong>This
     * choice is bounded by the alphabet.</strong> It holds for Latin, Greek and Cyrillic; the
     * moment the atlas covers CJK the square grows past any sane allocation and this has to
     * become a sparse structure.</p>
     *
     * <p>Read from the legacy {@code kern} table, which is all stb_truetype parses. Fonts
     * that keep their kerning only in {@code GPOS} yield nothing here and need HarfBuzz —
     * stage 3 of the text stack. DejaVu Sans was checked before this was written: it has a
     * {@code kern} table of 2,727 entries.</p>
     */
    private final float[] kerning = new float[CHAR_COUNT * CHAR_COUNT];

    /**
     * Whether {@link #placeGlyph} applies the corrections above.
     *
     * <p>On by default. The switch exists to put kerned and unkerned text side by side, which
     * is the only way to see what kerning did — it is a comparison and debugging affordance,
     * not a styling knob, and nothing in a theme should reach it.</p>
     */
    private boolean kerningEnabled = true;

    /**
     * @param resourcePath classpath path of the .ttf, e.g. {@code /fonts/DejaVuSans/DejaVuSans.ttf}
     * @param pixelHeight  size the glyphs are rasterised at, in pixels
     * @param atlasWidth   atlas width in pixels
     * @param atlasHeight  atlas height in pixels; baking fails loudly if the glyphs do not fit
     */
    public AegisFont(String resourcePath, float pixelHeight, int atlasWidth, int atlasHeight) {
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

            // Every pair in the baked range, asked once here rather than per character per
            // frame. 50k native calls at startup buys an array index at draw time, which is
            // what §9 of the design document demands of anything inside the frame loop.
            int kernPairs = 0;
            for (int prev = 0; prev < CHAR_COUNT; prev++) {
                int prevCode = FIRST_CHAR + prev;
                for (int cur = 0; cur < CHAR_COUNT; cur++) {
                    int raw = stbtt_GetCodepointKernAdvance(info, prevCode, FIRST_CHAR + cur);
                    if (raw == 0) continue;
                    kerning[prev * CHAR_COUNT + cur] = raw * scale;
                    kernPairs++;
                }
            }

            this.atlas = RenderContext.createTexture(atlasWidth, atlasHeight, TextureFormat.R8, bitmap);

            Logger.info(Logger.System.RENDERER,
                "Font baked: %s at %.0fpx, %d glyphs, atlas %dx%d (%d of %d rows used); "
                + "ascent %.1f, descent %.1f, gap %.1f, line height %d; %d kerning pairs.",
                resourcePath, pixelHeight, CHAR_COUNT, atlasWidth, atlasHeight, result, atlasHeight,
                this.ascent, this.descent, this.lineGap, this.lineHeight, kernPairs);

            // A font with no readable kern table is not an error — it is a font whose kerning
            // lives in GPOS, which stb_truetype does not parse. Saying so is better than text
            // quietly staying loose and nobody knowing why.
            if (kernPairs == 0) {
                Logger.warn(Logger.System.RENDERER,
                    "No kerning available in %s: stb_truetype reads only the legacy 'kern' "
                    + "table, and this font has none. Text will render unkerned.", resourcePath);
            }
        } finally {
            // The texture holds its own copy of the pixels, and the glyph placements, the
            // vertical metrics and the kerning table are all copied out, so none of the native
            // memory is needed past this point. Baking the kerning table here rather than
            // querying per pair at layout time is what keeps it that way.
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
     * <p>Positions are rounded to whole pixels for drawing, but the pen is not: the returned
     * position keeps its fraction. Rounding the pen would swallow kerning, whose corrections
     * are often well under a pixel, and would accumulate drift across a line.</p>
     *
     * <p>The kerning correction for {@code prev → c} is applied to the pen before the glyph
     * is placed, so a caller cannot forget it.</p>
     *
     * @param prev     the character before this one, or {@code 0} at the start of a run
     * @param c        the character; one without a glyph is drawn as the replacement
     * @param penX     pen position, pixels
     * @param baseline baseline position, pixels, origin top-left
     * @param out      at least 8 floats, reused by the caller
     * @return the pen position after this character
     */
    float placeGlyph(char prev, char c, float penX, float baseline, float[] out) {
        // Outside Latin-1, or one of the control codes inside it: both baked to nothing, and
        // a character the font cannot show should read as missing rather than as a blank.
        int index = glyphIndex(c);

        penX += kerning(prev, c);

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
        try (InputStream in = AegisFont.class.getResourceAsStream(path)) {
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
     * The row in {@link #metrics} a character draws from.
     *
     * <p>One place, because measuring and drawing must agree about it. A character outside
     * Latin-1, or one of the control codes inside it, resolves to the replacement glyph — so
     * if the two resolved it differently, a measured width and a drawn width would disagree
     * exactly on the strings hardest to notice it in.</p>
     */
    private int glyphIndex(char c) {
        boolean drawable = c >= FIRST_CHAR && c <= LAST_CHAR
                        && !(c >= CONTROLS_FIRST && c <= CONTROLS_LAST);
        return (drawable ? c : REPLACEMENT) - FIRST_CHAR;
    }

    /** How far the pen moves past this character, before kerning, in pixels. */
    private float advance(char c) {
        return metrics[glyphIndex(c) * STRIDE + 6];
    }

    /**
     * The width a string occupies when drawn, in pixels.
     *
     * <p>Sums the same advances and the same kerning corrections {@link #placeGlyph} applies,
     * which is why both go through {@link #glyphIndex}: a layout that measures one width and
     * draws another is the defect this exists to avoid.</p>
     *
     * <p>Takes a {@link CharSequence} so a reused {@code StringBuilder} can be measured
     * without building a {@code String}, and allocates nothing.</p>
     */
    public float measure(CharSequence text) {
        return measure(text, 0, text.length());
    }

    /**
     * The width of {@code text} between {@code from} inclusive and {@code to} exclusive.
     *
     * <p>The range form exists because wrapping measures candidate lines out of a paragraph
     * without cutting it into substrings first — {@code String.substring} per candidate line
     * per frame is exactly the allocation §9 forbids.</p>
     *
     * <p>Kerning is measured <em>inside</em> the range only: the character before
     * {@code from} is not a neighbour, because a wrapped line does not sit beside it.</p>
     */
    public float measure(CharSequence text, int from, int to) {
        float width = 0.0f;
        char prev = 0;
        for (int i = from; i < to; i++) {
            char c = text.charAt(i);
            width += kerning(prev, c) + advance(c);
            prev = c;
        }
        return width;
    }

    /**
     * Breaks a paragraph into lines that fit {@code maxWidth}, writing where each one ends.
     *
     * <p>Nothing is cut up and nothing is allocated: the caller owns both arrays, and line
     * {@code i} is the range {@code [lineStarts[i], lineEnds[i])} of {@code text} — which is
     * why {@link #measure(CharSequence, int, int)} takes a range.</p>
     *
     * <p>Both ends are reported rather than just the ends, because they are not adjacent. A
     * line that breaks at a space ends before it and the next begins after it; inferring the
     * start from the previous end would put that space at the head of the next line, where it
     * draws nothing but still advances the pen — an indent nobody asked for and nobody could
     * see the cause of.</p>
     *
     * <p>Breaks happen at spaces, and at {@code \n}, which is honoured as a forced break so a
     * paragraph can contain deliberate ones.</p>
     *
     * <p><strong>A word longer than {@code maxWidth} is broken mid-word.</strong> It is the
     * only option that terminates: refusing to break would loop forever, and letting it
     * overflow would put text outside the panel it was asked to fit. Proper hyphenation is
     * not in this stage and may never be.</p>
     *
     * @param lineStarts filled with the first index of each line
     * @param lineEnds   filled with one past the last index of each line; a paragraph needing
     *                   more lines than the arrays hold is truncated rather than growing
     *                   them, since growing would allocate
     * @return the number of lines written
     */
    public int wrap(CharSequence text, float maxWidth, int[] lineStarts, int[] lineEnds) {
        int capacity = Math.min(lineStarts.length, lineEnds.length);
        int lines = 0;
        int lineStart = 0;

        // Where the current line could break, and how wide it would be if it did. Both -1
        // and 0 mean "no break seen yet on this line".
        int   lastSpace = -1;
        float width     = 0.0f;
        char  prev      = 0;

        for (int i = 0; i < text.length() && lines < capacity; i++) {
            char c = text.charAt(i);

            if (c == '\n') {
                lineStarts[lines] = lineStart;
                lineEnds[lines++] = i;
                lineStart = i + 1;
                lastSpace = -1;
                width     = 0.0f;
                prev      = 0;
                continue;
            }

            float next = width + kerning(prev, c) + advance(c);

            if (next > maxWidth && i > lineStart) {
                // Break at the last space if there was one, otherwise mid-word.
                boolean atSpace = lastSpace >= lineStart;
                int breakAt = atSpace ? lastSpace : i;

                lineStarts[lines] = lineStart;
                lineEnds[lines++] = breakAt;

                // A line broken at a space resumes after it, so the space is dropped rather
                // than indenting the next line. One broken mid-word resumes at the character
                // that did not fit, so nothing is lost.
                lineStart = atSpace ? breakAt + 1 : breakAt;
                lastSpace = -1;
                width     = 0.0f;
                prev      = 0;

                i = lineStart - 1;  // the loop's i++ puts us back on lineStart
                continue;
            }

            if (c == ' ') lastSpace = i;
            width = next;
            prev  = c;
        }

        // Whatever is left over is the last line, unless the text ended exactly on a break.
        if (lines < capacity && lineStart < text.length()) {
            lineStarts[lines] = lineStart;
            lineEnds[lines++] = text.length();
        }
        return lines;
    }

    /**
     * The spacing correction between two characters, in pixels; negative pulls them together.
     *
     * <p>Zero when either character is outside the baked range, when the pair carries no
     * correction, or when kerning is switched off. Public because measuring a string in step
     * 3b-4 has to add the same corrections the drawing does, or the measured width and the
     * drawn width disagree.</p>
     *
     * @param prev the character before, or {@code 0} at the start of a run
     */
    public float kerning(char prev, char c) {
        if (!kerningEnabled) return 0.0f;
        if (prev < FIRST_CHAR || prev > LAST_CHAR) return 0.0f;
        if (c    < FIRST_CHAR || c    > LAST_CHAR) return 0.0f;
        return kerning[(prev - FIRST_CHAR) * CHAR_COUNT + (c - FIRST_CHAR)];
    }

    /** @see #kerningEnabled */
    public void setKerningEnabled(boolean enabled) { kerningEnabled = enabled; }

    /** @see #kerningEnabled */
    public boolean isKerningEnabled() { return kerningEnabled; }

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
