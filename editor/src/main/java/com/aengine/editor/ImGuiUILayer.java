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
     * Scaffolding: runs after ImGui has drawn, so the in-house UI framework composites on
     * top of the transitional interface instead of underneath it. Removed once the
     * framework draws the editor itself rather than alongside it.
     */
    private Runnable afterImGui = null;

    public void setAfterImGui(Runnable hook) { this.afterImGui = hook; }

    @Override public void init(long windowHandle) { DebugOverlay.init(windowHandle); }
    @Override public void beginFrame()            { DebugOverlay.beginFrame(); }
    @Override public void cleanup()               { DebugOverlay.cleanup(); }

    @Override
    public void endFrame() {
        DebugOverlay.endFrame();
        if (afterImGui != null) afterImGui.run();
    }

    @Override public float viewportWidth()  { return DebugOverlay.getViewportImageW(); }
    @Override public float viewportHeight() { return DebugOverlay.getViewportImageH(); }

    @Override
    public void renderViewport(int textureID, int windowWidth, int windowHeight,
                               Runnable contextMenu) {
        DebugOverlay.renderViewport(textureID, windowWidth, windowHeight, contextMenu);
    }
}
