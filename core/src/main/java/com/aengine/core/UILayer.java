package com.aengine.core;

/**
 * The seam between the engine loop and whatever draws the editor interface.
 *
 * <p>{@link Engine} drives the interface through this contract and never names a concrete
 * UI implementation, which is what keeps the engine core free of any dependency on the
 * editor or on a UI toolkit. Today the editor supplies a Dear ImGui implementation; the
 * in-house framework described in {@code docs/UI_FRAMEWORK_ARCHITECTURE.md} will replace
 * it by implementing this same interface, with no change to {@code Engine}.</p>
 *
 * <p>Every method has a no-op default so a headless or runtime-only host can ignore the
 * interface entirely — see {@link #NONE}. All calls are made from the main GL thread.</p>
 */
public interface UILayer {

    /** Shared no-op implementation, used when no interface is installed. */
    UILayer NONE = new UILayer() {};

    /**
     * Called once after {@code Input.init()}, so that any GLFW callbacks this layer
     * installs chain onto the engine's callbacks rather than replacing them.
     *
     * @param windowHandle the GLFW window handle
     */
    default void init(long windowHandle) {}

    /** Called once per frame, before the host submits its panels. */
    default void beginFrame() {}

    /** Called once per frame, after the host has submitted its panels. Draws them. */
    default void endFrame() {}

    /** Called before the GLFW window is destroyed. */
    default void cleanup() {}

    /**
     * Width in pixels of the region the scene should render into, or {@code 0} when the
     * interface has no opinion. The engine resizes its FrameBuffer to match.
     *
     * @return the viewport width in pixels, or 0
     */
    default float viewportWidth() { return 0.0f; }

    /**
     * Height counterpart to {@link #viewportWidth()}.
     *
     * @return the viewport height in pixels, or 0
     */
    default float viewportHeight() { return 0.0f; }

    /**
     * Presents the rendered scene texture as the viewport surface.
     *
     * @param textureID    OpenGL texture ID of the scene FrameBuffer
     * @param windowWidth  current window width in pixels
     * @param windowHeight current window height in pixels
     * @param contextMenu  submitted when the user opens the viewport context menu; never
     *                     {@code null}. Retain it rather than allocating per frame.
     */
    default void renderViewport(int textureID, int windowWidth, int windowHeight,
                                Runnable contextMenu) {}
}
