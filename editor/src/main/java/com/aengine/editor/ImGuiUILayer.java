package com.aengine.editor;

import com.aengine.core.UILayer;
import com.aengine.debug.DebugOverlay;

/**
 * Dear ImGui implementation of {@link UILayer}, adapting {@link DebugOverlay}'s static
 * lifecycle onto the engine's interface contract.
 *
 * <p>Transitional: this exists so the engine core carries no ImGui dependency while ImGui
 * still draws the editor. It is deleted when the in-house UI framework takes over — see
 * {@code docs/UI_FRAMEWORK_ARCHITECTURE.md}.</p>
 */
public final class ImGuiUILayer implements UILayer {

    /**
     * The size the scene renders at: the Aegis viewport's, as last solved — Phase 4. The
     * engine resizes its framebuffer to it at the start of each frame.
     */
    private float viewportWidth, viewportHeight;

    /** Creates the layer; ImGui itself starts in {@link #init(long)}. */
    public ImGuiUILayer() {}

    /**
     * Says how big the viewport is, for the scene to render at that size from the next frame.
     *
     * @param width  in pixels
     * @param height in pixels
     */
    public void setViewportSize(float width, float height) {
        this.viewportWidth  = width;
        this.viewportHeight = height;
    }

    @Override public void init(long windowHandle) { DebugOverlay.init(windowHandle); }
    @Override public void beginFrame()            { DebugOverlay.beginFrame(); }
    @Override public void endFrame()              { DebugOverlay.endFrame(); }
    @Override public void cleanup()               { DebugOverlay.cleanup(); }

    @Override public float viewportWidth()  { return viewportWidth; }
    @Override public float viewportHeight() { return viewportHeight; }

    /** The scene is shown by the editor's Aegis viewport now; ImGui no longer draws one. */
    @Override
    public void renderViewport(int textureID, int windowWidth, int windowHeight, Runnable contextMenu) { }
}
