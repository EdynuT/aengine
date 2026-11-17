package com.aengine.graphics;

/**
 * The type of one vertex attribute in a {@link DynamicMeshAPI} layout.
 *
 * <p>Every attribute occupies a whole number of 4-byte slots, which is what lets a vertex be
 * stored as a flat {@code int[]} of words: a float goes in through
 * {@link Float#floatToRawIntBits}, a packed colour goes in as-is.</p>
 */
public enum VertexAttribute {

    /** One {@code float}, read as {@code float}. */
    FLOAT1(1),
    /** Two {@code float}s, read as {@code vec2}. */
    FLOAT2(2),
    /** Three {@code float}s, read as {@code vec3}. */
    FLOAT3(3),
    /** Four {@code float}s, read as {@code vec4}. */
    FLOAT4(4),

    /**
     * Four unsigned bytes in one slot, read by the shader as a normalised {@code vec4} in
     * 0..1. A colour costs one word instead of four floats.
     *
     * <p>Bytes are read in memory order, so on the little-endian machines the engine
     * targets the lowest byte of the word is the first component: pack as
     * {@code (a << 24) | (b << 16) | (g << 8) | r}.</p>
     */
    RGBA8(1);

    /** How many 4-byte slots this attribute occupies. */
    public final int slots;

    VertexAttribute(int slots) {
        this.slots = slots;
    }
}
