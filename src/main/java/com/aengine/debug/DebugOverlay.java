package com.aengine.debug;

import imgui.ImGui;
import imgui.ImGuiIO;
import imgui.ImVec2;
import imgui.flag.ImGuiConfigFlags;
import imgui.flag.ImGuiWindowFlags;
import imgui.gl3.ImGuiImplGl3;
import imgui.glfw.ImGuiImplGlfw;
import imgui.type.ImBoolean;
import com.aengine.utils.Logger;

/**
 * Manages the lifecycle of the Dear ImGui debug overlay.
 *
 * <p>Architecture constraint: this overlay is <em>exclusively</em> for intra-viewport
 * debug tooling (collision wireframes, entity inspectors, performance metrics).
 * Window-frame layout and editor panels are the responsibility of the Tauri/WebKit
 * frontend, not this class.</p>
 *
 * <p>Lifecycle:
 * <ol>
 *   <li>{@link #init(long)} — after {@code Input.init()} so callbacks chain correctly</li>
 *   <li>{@link #beginFrame()} / widget submissions / {@link #endFrame()} — once per frame</li>
 *   <li>{@link #cleanup()} — before the GLFW window is destroyed</li>
 * </ol>
 * </p>
 *
 * <p>Disable in release/headless builds with: {@code -Dengine.debug=false}</p>
 */
public final class DebugOverlay {

    /**
     * Global enable switch.
     * When {@code false} all methods return immediately with zero overhead.
     * Default: {@code true} (set {@code -Dengine.debug=false} to suppress).
     */
    public static final boolean ENABLED =
        System.getProperty("engine.debug", "true").equalsIgnoreCase("true");

    // -------------------------------------------------------------------------
    // ImGui backends
    // -------------------------------------------------------------------------

    /** GLFW platform backend — handles input events and frame timing. */
    private static final ImGuiImplGlfw implGlfw = new ImGuiImplGlfw();

    /** OpenGL3 renderer backend — submits ImGui draw lists to the GPU. */
    private static final ImGuiImplGl3  implGl3  = new ImGuiImplGl3();

    /** Prevents double-initialisation or premature widget calls. */
    private static boolean initialized = false;

    // -------------------------------------------------------------------------
    // Pre-allocated temporaries (avoid per-frame GC pressure)
    // -------------------------------------------------------------------------

    /**
     * Reusable vector for querying available content region.
     * Safe because beginFrame/endFrame are always called from the main GL thread.
     */
    private static final ImVec2 CONTENT_SIZE = new ImVec2();

    /** Pre-allocated temporaries for viewport screen-space bound calculation. */
    private static final ImVec2 VP_WINDOW_POS = new ImVec2();
    private static final ImVec2 VP_CURSOR_POS  = new ImVec2();
    private static final ImVec2 VP_MOUSE_POS   = new ImVec2();

    // -------------------------------------------------------------------------
    // Viewport interaction state (written by renderViewport, read by Main)
    // -------------------------------------------------------------------------

    /** True for exactly one frame when the user left-clicks inside the viewport image. */
    private static boolean viewportLeftClicked = false;

    /** NDC X coord of the last left-click on the viewport image (range −1 … +1). */
    private static float viewportClickNdcX = 0.0f;

    /** NDC Y coord of the last left-click on the viewport image (range −1 … +1). */
    private static float viewportClickNdcY = 0.0f;

    // Screen-space bounds of the rendered viewport image (updated every frame).
    private static float viewportImageX = 0, viewportImageY = 0;
    private static float viewportImageW = 0, viewportImageH = 0;

    // -------------------------------------------------------------------------
    // Panel toggle state (persistent across frames via imgui_aengine.ini)
    // -------------------------------------------------------------------------

    private static final ImBoolean showViewport     = new ImBoolean(true);
    private static final ImBoolean showPhysicsPanel = new ImBoolean(true);
    private static final ImBoolean showEnginePanel  = new ImBoolean(true);

    private DebugOverlay() {}

    // =========================================================================
    // Lifecycle
    // =========================================================================

    /**
     * Initialises the Dear ImGui context and binds the GLFW + OpenGL3 backends.
     *
     * <p><strong>Call order matters:</strong> this must be invoked <em>after</em>
     * {@code Input.init(windowHandle)}. With {@code installCallbacks = true}, ImGui
     * saves each GLFW callback it replaces and forwards events to the old handler,
     * so {@code Input} and ImGui both receive all GLFW input events.</p>
     *
     * @param windowHandle native GLFW window handle obtained from {@code Window.getHandle()}
     */
    public static void init(long windowHandle) {
        if (!ENABLED) return;

        ImGui.createContext();
        ImGuiIO io = ImGui.getIO();

        // Keyboard navigation + docking: allows panels to be snapped/tabbed together
        io.addConfigFlags(ImGuiConfigFlags.NavEnableKeyboard);
        io.addConfigFlags(ImGuiConfigFlags.DockingEnable);

        // Persist window layout alongside the engine binary between runs
        io.setIniFilename("imgui_aengine.ini");

        // installCallbacks=true → ImGui chains onto existing Input.init() GLFW callbacks
        implGlfw.init(windowHandle, true);
        implGl3.init("#version 330");

        initialized = true;
        Logger.info(Logger.System.CORE,
            "ImGui debug overlay initialised (GLFW + OpenGL3 backend, docking enabled).");
    }

    /**
     * Signals the start of a new ImGui frame.
     * Must be called before any {@code ImGui.*} widget submissions.
     */
    public static void beginFrame() {
        if (!initialized) return;
        implGlfw.newFrame();
        ImGui.newFrame();
    }

    /**
     * Renders the engine scene FBO as an ImGui "Viewport" dockable window.
     *
     * <p>UV Y-axis is flipped ({@code uv0=(0,1), uv1=(1,0)}) to correct the OpenGL
     * bottom-left texture origin to ImGui's expected top-left convention.
     * The image fills the entire available content area and resizes with the panel.</p>
     *
     * @param textureID        OpenGL texture ID produced by the engine {@code FrameBuffer}
     * @param winWidth         fallback width  used when content region query returns zero
     * @param winHeight        fallback height
     * @param contextMenuItems optional {@code Runnable} invoked inside a right-click popup
     *                         on the viewport image; pass {@code null} for no menu
     */
    public static void renderViewport(int textureID, int winWidth, int winHeight,
                                      Runnable contextMenuItems) {
        if (!initialized) return;

        viewportLeftClicked = false;

        // NoScrollbar prevents scroll bars from appearing when the image fills the panel
        int flags = ImGuiWindowFlags.NoScrollbar | ImGuiWindowFlags.NoScrollWithMouse;
        ImGui.begin("Viewport", showViewport, flags);

        // Use the usable area of the panel (respects title bar, window padding, etc.)
        ImGui.getContentRegionAvail(CONTENT_SIZE);
        float avW = CONTENT_SIZE.x > 0 ? CONTENT_SIZE.x : winWidth;
        float avH = CONTENT_SIZE.y > 0 ? CONTENT_SIZE.y : winHeight;

        // Capture the top-left screen coordinate of where the image will be drawn.
        // These are used externally for unprojection-based entity picking.
        ImGui.getWindowPos(VP_WINDOW_POS);
        ImGui.getCursorPos(VP_CURSOR_POS);   // cursor pos is relative to the window
        viewportImageX = VP_WINDOW_POS.x + VP_CURSOR_POS.x;
        viewportImageY = VP_WINDOW_POS.y + VP_CURSOR_POS.y;
        viewportImageW = avW;
        viewportImageH = avH;

        // Flip UV Y: OpenGL Y=0 is at the bottom; ImGui images expect Y=0 at the top
        ImGui.image(textureID, avW, avH, 0, 1, 1, 0);

        // Entity picking: detect left-click on the viewport image
        if (ImGui.isItemClicked(0)) {
            ImGui.getMousePos(VP_MOUSE_POS);
            float relX = VP_MOUSE_POS.x - viewportImageX;
            float relY = VP_MOUSE_POS.y - viewportImageY;
            viewportClickNdcX =  (relX / avW) * 2.0f - 1.0f;
            viewportClickNdcY = -(relY / avH) * 2.0f + 1.0f;  // Y is flipped in OpenGL
            viewportLeftClicked = true;
        }

        // Right-click context menu on the viewport image
        if (contextMenuItems != null && ImGui.beginPopupContextItem("##vp_ctx")) {
            contextMenuItems.run();
            ImGui.endPopup();
        }

        ImGui.end();
    }

    /** Backward-compatible overload with no context menu. */
    public static void renderViewport(int textureID, int winWidth, int winHeight) {
        renderViewport(textureID, winWidth, winHeight, null);
    }

    /**
     * Finalises the ImGui frame and flushes draw data to the OpenGL driver.
     * Must be called after all widget submissions for the current frame.
     */
    public static void endFrame() {
        if (!initialized) return;
        ImGui.render();
        implGl3.renderDrawData(ImGui.getDrawData());
    }

    /**
     * Releases all ImGui-allocated native resources.
     * Must be called <em>before</em> the GLFW window is destroyed.
     */
    public static void cleanup() {
        if (!initialized) return;
        implGl3.dispose();
        implGlfw.dispose();
        ImGui.destroyContext();
        initialized = false;
        Logger.info(Logger.System.CORE, "ImGui debug overlay shutdown complete.");
    }

    // =========================================================================
    // Panel toggle accessors
    // Subclasses pass these as the p_open parameter to ImGui.begin() so the
    // user can close individual panels via the × button.
    // =========================================================================

    /** Returns the open-state handle for the Viewport panel. */
    public static ImBoolean showViewport()     { return showViewport;     }

    /** Returns the open-state handle for the Physics Debug panel. */
    public static ImBoolean showPhysicsPanel() { return showPhysicsPanel; }

    /** Returns the open-state handle for the Engine Stats panel. */
    public static ImBoolean showEnginePanel()  { return showEnginePanel;  }

    // =========================================================================
    // Viewport picking accessors
    // Written during renderViewport(); consumed by Main.onDebugRender() for
    // entity selection via screen-to-world unprojection.
    // =========================================================================

    /** True for exactly one frame when the user left-clicks inside the rendered viewport image. */
    public static boolean wasViewportClicked()  { return viewportLeftClicked; }

    /** NDC X of the last viewport left-click (range −1 … +1). */
    public static float getViewportClickNdcX()  { return viewportClickNdcX; }

    /** NDC Y of the last viewport left-click (range −1 … +1). */
    public static float getViewportClickNdcY()  { return viewportClickNdcY; }

    /** Screen-space width  of the rendered viewport image in pixels. */
    public static float getViewportImageW() { return viewportImageW; }

    /** Screen-space height of the rendered viewport image in pixels. */
    public static float getViewportImageH() { return viewportImageH; }
}
