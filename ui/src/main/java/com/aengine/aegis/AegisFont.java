package com.aengine.aegis;

import com.aengine.graphics.RenderContext;
import com.aengine.graphics.TextureAPI;
import com.aengine.graphics.TextureFormat;
import com.aengine.utils.Logger;
import org.lwjgl.stb.STBTTFontinfo;
import org.lwjgl.stb.STBTTPackContext;
import org.lwjgl.stb.STBTTPackRange;
import org.lwjgl.stb.STBTTPackedchar;
import org.lwjgl.system.MemoryUtil;

import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.IntBuffer;

import static org.lwjgl.stb.STBTruetype.stbtt_GetCodepointKernAdvance;
import static org.lwjgl.stb.STBTruetype.stbtt_GetFontVMetrics;
import static org.lwjgl.stb.STBTruetype.stbtt_InitFont;
import static org.lwjgl.stb.STBTruetype.stbtt_PackBegin;
import static org.lwjgl.stb.STBTruetype.stbtt_PackEnd;
import static org.lwjgl.stb.STBTruetype.stbtt_PackFontRanges;
import static org.lwjgl.stb.STBTruetype.stbtt_PackSetOversampling;
import static org.lwjgl.stb.STBTruetype.stbtt_ScaleForPixelHeight;

/**
 * A TrueType font baked into a glyph atlas at one fixed size.
 *
 * <p>This is stage 1 of the text stack in §6 of the design document: stb_truetype, Latin-1
 * plus the punctuation pasted text carries, legacy {@code kern}-table kerning, measuring and
 * wrapping, one pixel size per font. Scale-independent glyphs (MSDF) and real shaping
 * (HarfBuzz) are later stages, and replace the rasterising and the pair lookup here rather
 * than extending them.</p>
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
     * Portuguese, Spanish, French, German and Italian. Packed as one contiguous range, the
     * 33 unassigned control codes inside it included: they cost a few empty atlas cells, and
     * a contiguous range keeps a character's glyph row a subtraction away.
     */
    public static final int CHAR_COUNT = LAST_CHAR - FIRST_CHAR + 1;

    /**
     * Punctuation outside Latin-1 that pasted text brings with it — dashes, curly quotes, the
     * ellipsis, the bullet, the euro and the trade mark. Without these, text copied from a
     * browser or a document shows {@code ?} where a word processor put a nicer character.
     * Packed as a second range after Latin-1; their glyph rows follow its rows, in this order.
     *
     * <p>Not kerned: the kerning table covers Latin-1 pairs only, and a missing correction
     * beside a dash or a quote is far less visible than a missing glyph.</p>
     */
    private static final int[] EXTRA_CODEPOINTS = {
        0x2013,   // – en dash
        0x2014,   // — em dash
        0x2018,   // ‘ left single quote
        0x2019,   // ’ right single quote, also the apostrophe word processors type
        0x201C,   // “ left double quote
        0x201D,   // ” right double quote
        0x2022,   // • bullet
        0x2026,   // … ellipsis
        0x20AC,   // € euro
        0x2122,   // ™ trade mark
    };

    /** Every glyph in the atlas: Latin-1, then the extra punctuation. */
    private static final int GLYPH_COUNT = CHAR_COUNT + EXTRA_CODEPOINTS.length;

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
     * glyph's top-left) and {@code xadvance}, per glyph — Latin-1's rows first, then one row
     * per {@link #EXTRA_CODEPOINTS} entry.
     *
     * <p>Copied rather than read through {@code STBTTPackedchar.Buffer.get(i)}, which creates a
     * new wrapper object on every call — one allocation per character per frame.</p>
     */
    private final float[] metrics = new float[GLYPH_COUNT * STRIDE];

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
     * Where laid-out paragraphs are remembered.
     *
     * <p>Owned by the font rather than sitting beside it, because a layout is only valid for
     * the font that produced it. Tying the two together means a different font simply has a
     * different cache, and there is no way to ask one font's cache about another's text.</p>
     */
    private final AegisTextCache layoutCache = new AegisTextCache();

    /**
     * Reads a TrueType font from the classpath, rasterises its glyphs into an atlas, reads its
     * metrics and kerning, and uploads the atlas to the GPU. All native memory used on the way
     * is freed before this returns.
     *
     * @param resourcePath classpath path of the .ttf, e.g. {@code /fonts/DejaVuSans/DejaVuSans.ttf}
     * @param pixelHeight  size the glyphs are rasterised at, in pixels
     * @param atlasWidth   atlas width in pixels
     * @param atlasHeight  atlas height in pixels; baking fails loudly if the glyphs do not fit
     * @throws IllegalStateException if the resource is missing, is not a font stb_truetype can
     *                               read, or its glyphs do not fit the atlas
     */
    public AegisFont(String resourcePath, float pixelHeight, int atlasWidth, int atlasHeight) {
        this.pixelHeight = pixelHeight;
        this.atlasWidth  = atlasWidth;
        this.atlasHeight = atlasHeight;

        ByteBuffer ttf    = readResource(resourcePath);
        ByteBuffer bitmap = MemoryUtil.memAlloc(atlasWidth * atlasHeight);
        STBTTFontinfo info = STBTTFontinfo.malloc();

        STBTTPackContext        packer      = STBTTPackContext.malloc();
        // calloc, not malloc: a range has fields this code never sets, and stb reads them.
        // The Latin-1 range leaves array_of_unicode_codepoints alone, meaning "none, count up
        // from the first code point" - but only if it is zero. malloc hands back whatever the
        // heap held; on Windows that is often not zero, and stb then read a stray pointer as
        // the list of characters to bake, filling the atlas with glyphs from nowhere.
        STBTTPackRange.Buffer   ranges      = STBTTPackRange.calloc(2);
        STBTTPackedchar.Buffer  latinChars  = STBTTPackedchar.malloc(CHAR_COUNT);
        STBTTPackedchar.Buffer  extraChars  = STBTTPackedchar.malloc(EXTRA_CODEPOINTS.length);
        IntBuffer               extraPoints = MemoryUtil.memAllocInt(EXTRA_CODEPOINTS.length);

        try {
            if (!stbtt_InitFont(info, ttf)) {
                throw new IllegalStateException("Not a font stb_truetype can read: " + resourcePath);
            }

            // The same scale the packer derives from a positive font size, so the metrics and
            // the packed glyphs describe one font at one size rather than two near-agreeing ones.
            float scale = stbtt_ScaleForPixelHeight(info, pixelHeight);

            int[] a = new int[1], d = new int[1], g = new int[1];
            stbtt_GetFontVMetrics(info, a, d, g);

            this.ascent  =  a[0] * scale;
            this.descent = -d[0] * scale;  // stb reports it negative: font units point up
            this.lineGap =  g[0] * scale;
            this.lineHeight = Math.round(this.ascent + this.descent + this.lineGap);

            // Two ranges in one atlas: Latin-1 as a contiguous run, then the extra punctuation
            // as a list of scattered code points. stb's older one-call baker takes a single
            // contiguous range only, which is why the packer is used. At 1x oversampling it
            // rasterises exactly as the baker did, so existing text looks the same; 1 pixel of
            // padding keeps neighbouring glyphs from bleeding into each other when sampled.
            extraPoints.put(EXTRA_CODEPOINTS).flip();

            ranges.get(0)
                  .font_size(pixelHeight)
                  .first_unicode_codepoint_in_range(FIRST_CHAR)
                  .chardata_for_range(latinChars);
            ranges.get(0).num_chars(CHAR_COUNT);

            ranges.get(1)
                  .font_size(pixelHeight)
                  .first_unicode_codepoint_in_range(0)
                  .array_of_unicode_codepoints(extraPoints)
                  .chardata_for_range(extraChars);
            ranges.get(1).num_chars(EXTRA_CODEPOINTS.length);

            if (!stbtt_PackBegin(packer, bitmap, atlasWidth, atlasHeight, 0, 1, MemoryUtil.NULL)) {
                throw new IllegalStateException("Could not start packing the glyph atlas for " + resourcePath);
            }
            stbtt_PackSetOversampling(packer, 1, 1);
            boolean packed = stbtt_PackFontRanges(packer, ttf, 0, ranges);
            stbtt_PackEnd(packer);

            if (!packed) {
                throw new IllegalStateException(String.format(
                    "Glyph atlas too small: %s at %.0fpx does not fit %d glyphs in %dx%d.",
                    resourcePath, pixelHeight, GLYPH_COUNT, atlasWidth, atlasHeight));
            }

            for (int i = 0; i < CHAR_COUNT; i++) {
                copyGlyph(latinChars.get(i), i);
            }
            for (int k = 0; k < EXTRA_CODEPOINTS.length; k++) {
                copyGlyph(extraChars.get(k), CHAR_COUNT + k);
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
                "Font baked: %s at %.0fpx, %d glyphs (Latin-1 + %d punctuation), atlas %dx%d; "
                + "ascent %.1f, descent %.1f, gap %.1f, line height %d; %d kerning pairs.",
                resourcePath, pixelHeight, GLYPH_COUNT, EXTRA_CODEPOINTS.length, atlasWidth, atlasHeight,
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
            packer.free();
            ranges.free();
            latinChars.free();
            extraChars.free();
            MemoryUtil.memFree(extraPoints);
            MemoryUtil.memFree(bitmap);
            MemoryUtil.memFree(ttf);
        }
    }

    /** Copies one packed glyph's placement into its row of {@link #metrics}. */
    private void copyGlyph(STBTTPackedchar glyph, int row) {
        int m = row * STRIDE;
        metrics[m]     = glyph.x0();
        metrics[m + 1] = glyph.y0();
        metrics[m + 2] = glyph.x1();
        metrics[m + 3] = glyph.y1();
        metrics[m + 4] = glyph.xoff();
        metrics[m + 5] = glyph.yoff();
        metrics[m + 6] = glyph.xadvance();
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
     * <p>One place, because measuring and drawing must agree about it. A character with no
     * glyph — beyond Latin-1 and not in the extra punctuation, or one of the control codes
     * inside Latin-1 — resolves to the replacement glyph; so if the two resolved it
     * differently, a measured width and a drawn width would disagree exactly on the strings
     * hardest to notice it in.</p>
     *
     * <p>Latin-1 is a subtraction. The punctuation is a scan of ten entries, reached only by
     * characters above Latin-1, which in editor text are rare.</p>
     */
    private int glyphIndex(char c) {
        if (c >= FIRST_CHAR && c <= LAST_CHAR) {
            boolean control = c >= CONTROLS_FIRST && c <= CONTROLS_LAST;
            return (control ? REPLACEMENT : c) - FIRST_CHAR;
        }
        if (c > LAST_CHAR) {
            for (int k = 0; k < EXTRA_CODEPOINTS.length; k++) {
                if (EXTRA_CODEPOINTS[k] == c) return CHAR_COUNT + k;
            }
        }
        return REPLACEMENT - FIRST_CHAR;
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
     *
     * @param text the characters to measure
     * @return the width in pixels, kerning included
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
     *
     * @param text the characters
     * @param from first index measured, inclusive
     * @param to   last index measured, exclusive
     * @return the width in pixels, kerning included
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
     * @param text       the paragraph
     * @param maxWidth   widest a line may be, in pixels
     * @param lineStarts filled with the first index of each line
     * @param lineEnds   filled with one past the last index of each line; a paragraph needing
     *                   more lines than the arrays hold is truncated rather than growing
     *                   them, since growing would allocate
     * @return the number of lines written
     */
    public int wrap(CharSequence text, float maxWidth, int[] lineStarts, int[] lineEnds) {
        return wrapUncached(text, maxWidth, lineStarts, lineEnds,
                            0, Math.min(lineStarts.length, lineEnds.length));
    }

    /**
     * {@link #wrap} writing into a slice of the arrays rather than the whole of them.
     *
     * <p>The layout cache gives each of its entries a fixed slice of one flat array, so it
     * needs to say where this paragraph's lines go. Named <em>uncached</em> because it always
     * does the work: it is what the cache calls on a miss, and calling it directly is how a
     * caller opts out of caching.</p>
     *
     * @param base     index in both arrays where this paragraph's first line is written
     * @param capacity how many lines may be written from {@code base}
     */
    int wrapUncached(CharSequence text, float maxWidth, int[] lineStarts, int[] lineEnds,
                     int base, int capacity) {
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
                lineStarts[base + lines] = lineStart;
                lineEnds[base + lines++] = i;
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

                lineStarts[base + lines] = lineStart;
                lineEnds[base + lines++] = breakAt;

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
            lineStarts[base + lines] = lineStart;
            lineEnds[base + lines++] = text.length();
        }
        return lines;
    }

    /**
     * The cached layout of a paragraph, wrapping it only if it is not already known.
     *
     * <p>This is what interface code should reach for: an unchanged paragraph at an unchanged
     * width costs a hash and a comparison instead of a walk over every character. A paragraph
     * the cache declines to hold still lays out correctly, just without being remembered.</p>
     *
     * @return the cache slot, or {@link AegisTextCache#UNCACHEABLE}
     */
    int cachedLayout(CharSequence text, float maxWidth) {
        return layoutCache.slotFor(this, text, maxWidth);
    }

    /**
     * How many lines this paragraph wraps to at this width, through the cache.
     *
     * <p>Public because sizing comes before drawing: a panel has to know how tall its text
     * will be in order to be drawn around it, and asking must not cost a second walk over the
     * paragraph. Layout in step 3c needs exactly this question answered cheaply.</p>
     *
     * @param text     the paragraph
     * @param maxWidth widest a line may be, in pixels
     * @return the number of lines; at most 256 for text too long for the cache
     */
    public int wrappedLineCount(CharSequence text, float maxWidth) {
        int slot = cachedLayout(text, maxWidth);
        if (slot != AegisTextCache.UNCACHEABLE) return layoutCache.lineCount(slot);

        // Not held by the cache, so it has to be counted the long way. Nothing is kept.
        return wrapUncached(text, maxWidth, scratchStarts, scratchEnds, 0, scratchStarts.length);
    }

    /** Somewhere for {@link #wrappedLineCount} to put line ranges it is going to discard. */
    private final int[] scratchStarts = new int[256];
    private final int[] scratchEnds   = new int[256];

    int cachedLineCount(int slot)        { return layoutCache.lineCount(slot); }
    int cachedLineStart(int slot, int i) { return layoutCache.lineStart(slot, i); }
    int cachedLineEnd(int slot, int i)   { return layoutCache.lineEnd(slot, i); }

    /**
     * How many paragraph layouts have been computed rather than remembered.
     *
     * <p>The cache's only observable effect, since a working cache changes nothing on screen.
     * It climbs while text or widths are new and stops climbing once they are not; a
     * steady-state frame that still increments it is a cache that is not working.</p>
     *
     * @return the count since the font was baked
     */
    public int layoutRecomputes() { return layoutCache.recomputes(); }

    /**
     * How many paragraph layouts have been served from memory.
     *
     * @return the count since the font was baked
     */
    public int layoutHits() { return layoutCache.hits(); }

    /**
     * The spacing correction between two characters, in pixels; negative pulls them together.
     *
     * <p>Zero when either character is outside the baked range, when the pair carries no
     * correction, or when kerning is switched off. Public because measuring a string in step
     * 3b-4 has to add the same corrections the drawing does, or the measured width and the
     * drawn width disagree.</p>
     *
     * @param prev the character before, or {@code 0} at the start of a run
     * @param c    the character being placed
     * @return the correction in pixels, usually 0
     */
    public float kerning(char prev, char c) {
        if (!kerningEnabled) return 0.0f;
        if (prev < FIRST_CHAR || prev > LAST_CHAR) return 0.0f;
        if (c    < FIRST_CHAR || c    > LAST_CHAR) return 0.0f;
        return kerning[(prev - FIRST_CHAR) * CHAR_COUNT + (c - FIRST_CHAR)];
    }

    /**
     * Switches kerning on or off, for comparing kerned and unkerned text. On by default; not a
     * styling option. Cached layouts are kept per setting, so toggling costs nothing.
     *
     * @param enabled {@code true} to apply the font's pair corrections
     */
    public void setKerningEnabled(boolean enabled) { kerningEnabled = enabled; }

    /**
     * Whether kerning is applied.
     *
     * @return {@code true} unless it was switched off
     */
    public boolean isKerningEnabled() { return kerningEnabled; }

    /**
     * Baseline for a line of text whose top edge sits at {@code top}.
     *
     * <p>Text is placed by its top almost everywhere — a label centred in a button, a row in
     * a list — while glyphs hang from a baseline, and this is the one conversion between the
     * two. Rounded, so that every line stacked from here starts on a whole pixel.</p>
     *
     * @param top y of the top of the line box
     * @return y of the baseline, a whole pixel
     */
    public float baselineForTop(float top) {
        return Math.round(top + ascent);
    }

    /**
     * Height above the baseline, pixels.
     *
     * @return the ascent, positive
     */
    public float ascent()  { return ascent; }

    /**
     * Depth below the baseline, positive downwards, pixels.
     *
     * @return the descent, positive
     */
    public float descent() { return descent; }

    /**
     * Extra leading the font asks for between lines, pixels; often zero.
     *
     * @return the line gap
     */
    public float lineGap() { return lineGap; }

    /**
     * Distance from one line's top to the next, in whole pixels. Adding this repeatedly is
     * how lines stack; see the field for why it is not fractional.
     *
     * @return the line height
     */
    public int lineHeight() { return lineHeight; }

    /**
     * Backend texture handle of the atlas.
     *
     * @return the handle, for {@link AegisDrawList#addTexturedQuad} or a renderer
     */
    public int   atlasHandle() { return atlas.getID(); }

    /**
     * Width of the glyph atlas.
     *
     * @return width in pixels, as asked for at construction
     */
    public int   atlasWidth()  { return atlasWidth; }

    /**
     * Height of the glyph atlas.
     *
     * @return height in pixels, as asked for at construction
     */
    public int   atlasHeight() { return atlasHeight; }

    /**
     * The size the glyphs were rasterised at. Not a line height and not an ascent — use
     * {@link #lineHeight()} and {@link #baselineForTop(float)} to position text.
     *
     * @return the rasterisation size in pixels
     */
    public float pixelHeight() { return pixelHeight; }

    /** Deletes the atlas texture. The font must not be drawn with afterwards. */
    public void cleanup() {
        atlas.cleanup();
    }
}
