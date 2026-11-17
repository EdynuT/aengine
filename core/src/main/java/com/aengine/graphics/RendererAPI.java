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

    /**
     * Sets the backend's starting state: depth testing on, with nearer fragments winning.
     * Call once, on the GL thread, after the context exists.
     */
    void init();

    /**
     * Creates the buffers used by {@link #drawBatch}: a vertex buffer, and an index buffer
     * pre-filled with the quad pattern {@code 0,1,2, 2,3,0} repeated for every group of
     * four vertices.
     *
     * <p>The vertex layout is fixed at ten floats per vertex: position (3), texture
     * coordinates (2), colour (4) and texture slot index (1), at attribute locations
     * 0 to 3.</p>
     *
     * @param maxVertices      how many vertices one batch can hold
     * @param maxIndices       how many indices one batch can hold; must be a multiple of 6
     * @param vertexSizeFloats floats per vertex; must match the fixed layout, i.e. 10
     */
    void initBatchBuffers(int maxVertices, int maxIndices, int vertexSizeFloats);

    /**
     * Sets the colour {@link #clear()} fills the target with.
     *
     * @param color RGBA from 0 to 1; read, not kept
     */
    void setClearColor(Vector4f color);

    /** Clears the colour and depth of the current render target. */
    void clear();

    /**
     * Uploads a batch of quads and draws it as triangles, using whatever shader and
     * textures are bound.
     *
     * @param vertices    vertex data in the layout set by {@link #initBatchBuffers}
     * @param vertexCount how many <em>floats</em> of {@code vertices} to upload (not vertices,
     *                    despite the name)
     * @param indexCount  how many indices of the pre-built quad pattern to draw; 0 draws nothing
     */
    void drawBatch(float[] vertices, int vertexCount, int indexCount);

    /** Deletes the batch buffers and frees the staging memory. */
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
     *
     * @param enabled {@code true} to test and write depth, {@code false} to draw in order
     */
    void setDepthTest(boolean enabled);

    /**
     * Enables or disables standard alpha blending — source alpha against one minus source
     * alpha, the blend every antialiased edge and translucent panel depends on.
     *
     * @param enabled {@code true} to blend, {@code false} to overwrite
     */
    void setBlend(boolean enabled);

    /**
     * Tells the backend the size of the surface currently being drawn into.
     *
     * <p>Required before {@link #setScissor}, which is expressed in top-left coordinates and
     * therefore needs the height to convert for backends whose framebuffer origin is at the
     * bottom-left. Set this whenever the render target changes — window resize, or binding a
     * FrameBuffer of a different size.</p>
     *
     * @param width  target width in pixels
     * @param height target height in pixels
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
     *
     * <p>Stays in effect for every later draw until {@link #disableScissor()}.</p>
     *
     * @param x      left edge in pixels
     * @param y      top edge in pixels, measured down from the top of the target
     * @param width  rectangle width in pixels; negative is treated as 0
     * @param height rectangle height in pixels; negative is treated as 0
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
     *
     * <p>Leaves {@code slot} as the active texture unit.</p>
     *
     * @param slot          texture unit to bind to
     * @param textureHandle backend texture handle, e.g. from {@link TextureAPI#getID()}
     *                      or {@link FrameBuffer#getTextureID()}
     */
    void bindTexture(int slot, int textureHandle);
}
