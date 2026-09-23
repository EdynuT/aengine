package com.aengine.aegis;

/**
 * Remembers where a paragraph's lines fell, so unchanged text costs nothing to lay out again.
 *
 * <p>Wrapping walks every character summing advances and kerning. A panel redrawing the same
 * sentence at 200 frames a second does that walk 200 times a second to arrive at the same
 * answer, which is the waste step 3b-5 of the design document exists to remove.</p>
 *
 * <h2>Why it is shaped like this</h2>
 *
 * <p><strong>Direct-mapped, not a hash map.</strong> A slot is {@code hash & (CAPACITY-1)} and
 * an entry landing on an occupied slot simply replaces it, the way a CPU cache does. There is
 * no probing, no eviction policy and no growth — so there is nothing to allocate, nothing to
 * leak, and no per-frame bookkeeping. The cost is that two paragraphs colliding on one slot
 * will evict each other every frame; with {@value #CAPACITY} slots and an editor showing a
 * handful of paragraphs, that is a worse-than-nothing case, not a common one.</p>
 *
 * <p><strong>The key is verified, not trusted.</strong> A hash alone would eventually serve
 * one paragraph's line breaks for another, which would be a rare, silent and baffling bug. So
 * an entry stores a copy of its own text and a lookup compares it character by character
 * after the hash matches. The comparison is the cheap part; the hash is what makes it rare.
 * {@code maxWidth} is part of the key too, because the same string wraps differently at a
 * different width.</p>
 *
 * <p><strong>Everything is bounded and pre-allocated.</strong> Text longer than
 * {@value #MAX_CHARS} characters, or wrapping to more than {@value #MAX_LINES} lines, is not
 * cached at all: {@link #slotFor} returns {@link #UNCACHEABLE} and the caller wraps directly.
 * Refusing to store it is better than growing a buffer inside the frame loop, which §9
 * forbids, and better than silently truncating the text.</p>
 *
 * <p>This is not thread-safe and is not meant to be. It belongs to one {@link AegisFont},
 * which belongs to the thread that owns the interface.</p>
 */
final class AegisTextCache {

    /** Returned by {@link #slotFor} for text this cache will not hold. */
    static final int UNCACHEABLE = -1;

    /** Slots. A power of two, so the modulo is a mask. */
    private static final int CAPACITY  = 128;

    /** Longest text held, in characters. */
    private static final int MAX_CHARS = 512;

    /** Most lines held for one entry. */
    private static final int MAX_LINES = 64;

    /** {@code -1} marks an empty slot; otherwise the stored text's length. */
    private final int[]   keyLength = new int[CAPACITY];
    private final long[]  keyHash   = new long[CAPACITY];
    private final float[] keyWidth  = new float[CAPACITY];

    /**
     * Whether the entry was laid out with kerning on.
     *
     * <p>Part of the key rather than a reason to empty the cache. Kerning changes where every
     * line breaks, so entries made under one setting are wrong under the other — but clearing
     * on every toggle punishes anything that toggles often, and the scaffolding's kerned and
     * unkerned comparison toggles twice a frame. Keyed, the two simply coexist.</p>
     */
    private final boolean[] keyKerned = new boolean[CAPACITY];

    /** The stored text itself, {@value #MAX_CHARS} characters per slot, for verification. */
    private final char[] keyChars = new char[CAPACITY * MAX_CHARS];

    private final int[] lineCount  = new int[CAPACITY];
    private final int[] lineStarts = new int[CAPACITY * MAX_LINES];
    private final int[] lineEnds   = new int[CAPACITY * MAX_LINES];

    /** Counted so the cache can be shown to work; see {@link #recomputes()}. */
    private int hits;
    private int recomputes;

    AegisTextCache() {
        clear();
    }

    /**
     * The slot holding this paragraph's line breaks, wrapping it first if they are not known.
     *
     * @return a slot to read through {@link #lineCount}, {@link #lineStart} and
     *         {@link #lineEnd}, or {@link #UNCACHEABLE} if this text will not be held
     */
    int slotFor(AegisFont font, CharSequence text, float maxWidth) {
        int length = text.length();
        if (length > MAX_CHARS) {
            recomputes++;
            return UNCACHEABLE;
        }

        boolean kerned = font.isKerningEnabled();
        long hash = hash(text, maxWidth, kerned);
        int slot  = (int) (hash & (CAPACITY - 1));

        if (keyLength[slot] == length
                && keyHash[slot] == hash
                && keyWidth[slot] == maxWidth
                && keyKerned[slot] == kerned
                && sameText(slot, text, length)) {
            hits++;
            return slot;
        }

        // Miss. Wrap into this slot's own range of the line arrays, which is why the ranges
        // are one flat array rather than an array per entry.
        int base  = slot * MAX_LINES;
        int lines = font.wrapUncached(text, maxWidth, lineStarts, lineEnds, base, MAX_LINES);
        recomputes++;

        // A paragraph that filled the line budget may well have had more to say, and storing
        // a truncated layout would serve it again silently. Leave the slot empty instead.
        if (lines >= MAX_LINES) {
            keyLength[slot] = -1;
            return UNCACHEABLE;
        }

        lineCount[slot] = lines;
        keyLength[slot] = length;
        keyHash[slot]   = hash;
        keyWidth[slot]  = maxWidth;
        keyKerned[slot] = kerned;
        for (int i = 0; i < length; i++) {
            keyChars[slot * MAX_CHARS + i] = text.charAt(i);
        }
        return slot;
    }

    int lineCount(int slot)          { return lineCount[slot]; }
    int lineStart(int slot, int i)   { return lineStarts[slot * MAX_LINES + i]; }
    int lineEnd(int slot, int i)     { return lineEnds[slot * MAX_LINES + i]; }

    /** How many layouts were served from memory. */
    int hits() { return hits; }

    /**
     * How many layouts had to be computed.
     *
     * <p>The number that makes the cache visible: it climbs while text or widths are new and
     * stops climbing once they are not. A steady-state frame that still increments this is a
     * cache that is not working.</p>
     */
    int recomputes() { return recomputes; }

    /**
     * Forgets everything.
     *
     * <p>Nothing calls this in normal use: kerning is part of the key, and a different font
     * gets a different cache along with it. It exists so that a future change which really
     * does invalidate everything has somewhere obvious to say so.</p>
     */
    void clear() {
        for (int i = 0; i < CAPACITY; i++) keyLength[i] = -1;
        hits = 0;
        recomputes = 0;
    }

    /**
     * FNV-1a over the characters, then the width folded in.
     *
     * <p>Chosen for being a few lines with no state and no allocation. It is not a
     * cryptographic hash and does not need to be: a collision here costs one character-by-
     * character comparison that fails, not a wrong answer.</p>
     */
    private static long hash(CharSequence text, float maxWidth, boolean kerned) {
        long h = 0xCBF29CE484222325L;
        for (int i = 0; i < text.length(); i++) {
            h = (h ^ text.charAt(i)) * 0x100000001B3L;
        }
        h = (h ^ Float.floatToIntBits(maxWidth)) * 0x100000001B3L;
        h = (h ^ (kerned ? 1 : 0)) * 0x100000001B3L;
        return h;
    }

    /** Character-by-character, because a hash match is evidence and not proof. */
    private boolean sameText(int slot, CharSequence text, int length) {
        int base = slot * MAX_CHARS;
        for (int i = 0; i < length; i++) {
            if (keyChars[base + i] != text.charAt(i)) return false;
        }
        return true;
    }
}
