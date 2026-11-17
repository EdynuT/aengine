package com.aengine.graphics;

/**
 * A 2D texture on the GPU. Create one through {@link RenderContext#createTexture(String)}
 * (an asset, loaded in the background) or
 * {@link RenderContext#createTexture(int, int, TextureFormat, java.nio.ByteBuffer)}
 * (pixels already in memory). Every method must run on the GL thread.
 */
public interface TextureAPI {
    /**
     * Binds the texture to a sampler slot for the next draws.
     * An asset texture that has not finished loading binds nothing and leaves the slot
     * as it was.
     *
     * @param slot texture unit, from 0 to {@link HardwareCapabilities#getMaxTextureSlots()} - 1
     */
    void bind(int slot);

    /** Unbinds whatever 2D texture is bound on the currently active slot. */
    void unbind();

    /**
     * Returns the width in pixels.
     *
     * @return the width; an asset texture reports 1 until it has loaded
     */
    int  getWidth();

    /**
     * Returns the height in pixels.
     *
     * @return the height; an asset texture reports 1 until it has loaded
     */
    int  getHeight();

    /**
     * Returns the backend's handle for the texture, e.g. the OpenGL texture name, for
     * APIs that bind raw handles such as {@link RendererAPI#bindTexture(int, int)}.
     * The handle is valid from creation, even before an asset has loaded.
     *
     * @return the backend texture handle
     */
    int  getID();

    /** Deletes the GPU texture and any pixels still held in memory. */
    void cleanup();
}
