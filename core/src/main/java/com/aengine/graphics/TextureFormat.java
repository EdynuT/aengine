package com.aengine.graphics;

/**
 * Pixel layout of a texture created from memory — see
 * {@link RenderContext#createTexture(int, int, TextureFormat, java.nio.ByteBuffer)}.
 */
public enum TextureFormat {

    /**
     * One byte per pixel. Sampled in a shader as {@code (r, 0, 0, 1)}: the value arrives in
     * the red channel. The format of a glyph atlas, where each pixel is coverage rather than
     * colour, at a quarter of the memory of RGBA.
     */
    R8(1),

    /** Four bytes per pixel, red first. */
    RGBA8(4);

    /** Bytes per pixel. */
    public final int bytesPerPixel;

    TextureFormat(int bytesPerPixel) {
        this.bytesPerPixel = bytesPerPixel;
    }
}
