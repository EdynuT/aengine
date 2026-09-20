package com.aengine.graphics;

import org.joml.Vector4f;

/**
 * The hardware command driver interface.
 *
 * <p>Everything above this line — the 2D and 3D renderers, and the UI framework in
 * {@code :ui} — issues drawing through this contract and never touches a graphics API
 * directly, which is what allows the OpenGL backend to be swapped for Vulkan without
 * changing a line of calling code.</p>
 *
 * <p>Render state set here is global to the backend until changed again. Callers are
 * responsible for restoring what they altered: the engine loop and the UI layer both draw
 * in the same frame and neither may leave the pipeline in a state the other did not
 * expect.</p>
 */
public interface RendererAPI {

    void init();

    void initBatchBuffers(int maxVertices, int maxIndices, int vertexSizeFloats);

    void setClearColor(Vector4f color);

    void clear();

    void drawBatch(float[] vertices, int vertexCount, int indexCount);

    void cleanup();

    // -------------------------------------------------------------------------------------
    // Render state
    //
    // Added for the UI framework, which needs the pipeline in a different configuration
    // from scene rendering: no depth, alpha blended, and clipped per panel.
    // See docs/UI_FRAMEWORK_ARCHITECTURE.md.
    // -------------------------------------------------------------------------------------

    /**
     * Enables or disables depth testing.
     *
     * <p>Scene rendering needs it on; interface rendering needs it off, because UI draws in
     * submission order and depth would fight that ordering. {@link #init()} leaves it
     * enabled, so a UI pass must turn it off and restore it.</p>
     */
    void setDepthTest(boolean enabled);

    /**
     * Enables or disables standard alpha blending — source alpha against one minus source
     * alpha, the blend every antialiased edge and translucent panel depends on.
     */
    void setBlend(boolean enabled);

    /**
     * Tells the backend the size of the surface currently being drawn into.
     *
     * <p>Required before {@link #setScissor}, which is expressed in top-left coordinates and
     * therefore needs the height to convert for backends whose framebuffer origin is at the
     * bottom-left. Set this whenever the render target changes — window resize, or binding a
     * FrameBuffer of a different size.</p>
     */
    void setRenderTargetSize(int width, int height);

    /**
     * Restricts drawing to a rectangle; fragments outside it are discarded.
     *
     * <p>Coordinates are in pixels with the origin at the <strong>top-left</strong> of the
     * render target, which is how interface code addresses the screen. Backends with a
     * bottom-left origin convert internally using {@link #setRenderTargetSize}.</p>
     *
     * <p>This is how a UI clips its panels: a scrolling list draws every row and lets the
     * rectangle cut away what falls outside the visible area.</p>
     */
    void setScissor(int x, int y, int width, int height);

    /** Removes any scissor restriction, allowing drawing across the whole target. */
    void disableScissor();

    /**
     * Binds a texture to a sampler slot for subsequent draws.
     *
     * <p>Takes a raw backend texture handle rather than a {@link TextureAPI}, because the
     * textures a UI draws with are usually not assets: a glyph atlas it generated, or the
     * scene FrameBuffer's colour attachment being presented as the viewport.</p>
     */
    void bindTexture(int slot, int textureHandle);
}
