package com.aengine.core;

import com.aengine.graphics.FrameBuffer;
import com.aengine.utils.Logger;
import com.aengine.ecs.Registry;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import static org.lwjgl.opengl.GL11.GL_COLOR_BUFFER_BIT;
import static org.lwjgl.opengl.GL11.GL_DEPTH_BUFFER_BIT;
import static org.lwjgl.opengl.GL11.glClear;

/**
 * The engine's main loop, for a host application to extend.
 *
 * <p>A host subclasses this, fills in the {@code on...} callbacks and calls {@link #run()},
 * which owns the thread until the window closes. Each frame, in order:</p>
 * <ol>
 *   <li>input is collected ({@link Input#poll()}, {@link Input#update()});</li>
 *   <li>the scene FrameBuffer is resized to the UI's viewport, if the UI reports one;</li>
 *   <li>{@link #onUpdate(float)} runs;</li>
 *   <li>{@link #onRender()} draws the scene into the FrameBuffer;</li>
 *   <li>the window is cleared and the UI frame is built around
 *       {@link #onDebugRender(int)}, which presents the scene texture;</li>
 *   <li>the frame is shown.</li>
 * </ol>
 *
 * <p>Steps 3 to 5 run only in {@link EngineState#EDITOR}, the default. Everything runs on
 * the thread that called {@code run()}, which is the GL thread.</p>
 */
public abstract class Engine {

    private final Window window;
    private volatile boolean running;
    private FrameBuffer frameBuffer;

    /** Interface implementation driving the editor surface; never null. */
    private UILayer ui = UILayer.NONE;

    /**
     * Hoisted out of the frame loop: {@code this::onViewportContextMenu} allocates a new
     * capturing lambda on every evaluation, and this one is evaluated once per frame.
     */
    private final Runnable viewportContextMenu = this::onViewportContextMenu;

    /** The ECS world, created with the engine; also available through {@link #getRegistry()}. */
    protected final Registry registry; 

    private Path targetClassPath;
    private String gameClassName;
    private long lastKnownModificationTime = 0;
    private long lastReloadCheckTime = 0;
    private static final long CHECK_INTERVAL_MS = 1000;

    /** What the main loop does each frame. */
    public enum EngineState {
        /** Only clears the window: no update, no scene, no UI. Not used by the editor today. */
        LAUNCHER,
        /** Runs the full frame: update, scene render and UI. The default. */
        EDITOR
    }
    private EngineState currentState = EngineState.EDITOR; // Instantiating as EDITOR for standalone fallback execution

    /**
     * Creates the engine with an empty ECS registry. The window opens later, in {@link #run()}.
     *
     * @param title the window title
     */
    public Engine(String title) {
        this.window = new Window(title);
        this.registry = new Registry(); 
    }

    /**
     * Installs the interface implementation. Must be called before {@link #run()}, since
     * the layer is initialised during engine startup. Passing {@code null} restores
     * {@link UILayer#NONE}.
     *
     * @param layer the interface to drive, or {@code null} for none
     */
    public final void setUILayer(UILayer layer) {
        this.ui = (layer != null) ? layer : UILayer.NONE;
    }

    /**
     * Points game-code hot reload at a compiled class. The engine then checks the class
     * file's modification time once a second.
     *
     * <p>The reload itself is not implemented yet: a change is detected and logged, and
     * nothing is reloaded. The editor does not call this.</p>
     *
     * @param buildDirectory          directory holding the compiled classes
     * @param fullyQualifiedClassName the game class to watch, e.g. {@code "game.MyGame"}
     */
    public final void configureHotReload(String buildDirectory, String fullyQualifiedClassName) {
        this.gameClassName = fullyQualifiedClassName;
        this.targetClassPath = Paths.get(buildDirectory).resolve(fullyQualifiedClassName.replace('.', '/') + ".class");
        
        if (Files.exists(targetClassPath)) {
            try {
                this.lastKnownModificationTime = Files.getLastModifiedTime(targetClassPath).toMillis();
                Logger.info(Logger.System.CORE, "Hot Reload system pointing to: %s", targetClassPath.toAbsolutePath());
            } catch (Exception e) {
                Logger.error(Logger.System.CORE, "Failed to resolve initial file attributes for hot reload target.");
            }
        } else {
            Logger.warn(Logger.System.CORE, "Hot reload target bytecode file not found yet at: %s.", targetClassPath.toAbsolutePath());
        }
    }

    /**
     * Opens the window, runs {@link #onInit()}, then loops until the window is closed or
     * {@link #stop()} is called, and finally releases everything, even when an exception
     * ends the loop. Blocks the calling thread for the lifetime of the engine.
     */
    public final void run() {
        try {
            init();
            loop();
        } finally {
            cleanup();
        }
    }

    private void init() {
        Logger.info(Logger.System.CORE, "Initializing core engine components...");
        window.init();
        Input.init(window.getHandle());

        // Initialise the interface AFTER Input so its GLFW callback installation chains
        // onto Input's callbacks rather than replacing them silently.
        ui.init(window.getHandle());

        frameBuffer = new FrameBuffer(window.getWidth(), window.getHeight());

        if (gameClassName != null) {
            reloadGameCode();
        }

        Logger.info(Logger.System.CORE, "Invoking native engine host onInit callback...");
        onInit();
    }

    private void loop() {
        running = true;
        long lastTime = System.nanoTime();
        
        Logger.info(Logger.System.CORE, "Engine main loop engaged. Systems initialized successfully.");

        while (running && !window.shouldClose()) {
            long now = System.nanoTime();
            float deltaTime = (now - lastTime) / 1_000_000_000.0f;
            lastTime = now;

            long currentMillis = now / 1_000_000;
            if (currentMillis - lastReloadCheckTime > CHECK_INTERVAL_MS) {
                checkAndHandleHotReload();
                lastReloadCheckTime = currentMillis;
            }

            Input.poll();     // empties last frame's event queue, then collects this frame's
            Input.update();

            int vpW = (int) ui.viewportWidth();
            int vpH = (int) ui.viewportHeight();
            
            if (vpW > 0 && vpH > 0) {
                if (frameBuffer.getWidth() != vpW || frameBuffer.getHeight() != vpH) {
                    frameBuffer.resize(vpW, vpH);
                }
            }

            if (currentState == EngineState.EDITOR) {
                onUpdate(deltaTime);
                
                // 1. ENGINE RENDER PASS (Virtual Texture in VRAM)
                frameBuffer.bind();
                glClear(GL_COLOR_BUFFER_BIT | GL_DEPTH_BUFFER_BIT); // Limpa apenas o FBO
                
                onRender(); 
                
                frameBuffer.unbind();

                // 2. PHYSICAL DISPLAY RENDER PASS (Physical Monitor)
                org.lwjgl.opengl.GL11.glViewport(0, 0, window.getWidth(), window.getHeight());
                glClear(GL_COLOR_BUFFER_BIT | GL_DEPTH_BUFFER_BIT); // Clear the monitor

                ui.beginFrame();
                onDebugRender(frameBuffer.getTextureID());
                ui.endFrame();

            } else if (currentState == EngineState.LAUNCHER) {
                // Just clear the physical monitor for the launcher state. No FBO rendering needed.
                glClear(GL_COLOR_BUFFER_BIT | GL_DEPTH_BUFFER_BIT);
            }

            window.swapBuffers();
        }
        Logger.info(Logger.System.CORE, "Break condition detected. Terminating main loop...");
    }

    private void checkAndHandleHotReload() {
        if (targetClassPath == null || !Files.exists(targetClassPath)) return;

        try {
            long currentModificationTime = Files.getLastModifiedTime(targetClassPath).toMillis();
            if (currentModificationTime > lastKnownModificationTime) {
                lastKnownModificationTime = currentModificationTime;
                Logger.info(Logger.System.CORE, "Detected bytecode file mutation on disk. Triggering hot reload...");
                reloadGameCode();
            }
        } catch (Exception e) {
            Logger.error(Logger.System.CORE, "Hot reload file attribute lookup failed: %s", e.getMessage());
        }
    }

    private void reloadGameCode() {
        Logger.debug(Logger.System.CORE, "Hot reload intercepted. Pipeline pending target conversion to pure ECS Data-Driven systems.");
    }

    private void cleanup() {
        Logger.info(Logger.System.CORE, "Executing engine teardown sequence...");
        onCleanup();

        // Interface must be shut down before the GLFW window is destroyed
        ui.cleanup();

        if (frameBuffer != null) frameBuffer.cleanup();

        window.cleanup();

        Logger.info(Logger.System.CORE, "Engine lifecycle shutdown complete.\n");
    }

    /**
     * Asks the loop to end after the current frame. Safe to call from any thread.
     */
    public final void stop() { running = false; }

    /**
     * Returns the engine's window.
     *
     * @return the window; opened only once {@link #run()} has started
     */
    public Window getWindow() { return window; }

    /**
     * Returns the ECS world the engine was created with.
     *
     * @return the registry; the same instance for the engine's lifetime
     */
    public Registry getRegistry() { return registry; }

    /**
     * Switches what the loop does from the next frame on.
     *
     * @param state the new state
     */
    public void setEngineState(EngineState state) { this.currentState = state; }

    /**
     * Called once, after the window, input, UI layer and scene FrameBuffer exist and before
     * the first frame. Load the scene and start subsystems here.
     */
    protected abstract void onInit();

    /**
     * Called once per frame, before rendering, in {@link EngineState#EDITOR}.
     *
     * @param deltaTime seconds since the previous frame
     */
    protected abstract void onUpdate(float deltaTime);

    /**
     * Called once per frame to draw the scene. The scene FrameBuffer is bound and cleared,
     * and the viewport covers it.
     */
    protected abstract void onRender();

    /**
     * Called each frame after the scene FBO is complete but before buffer swap.
     * Submit all interface panels here. The default implementation presents the scene
     * FBO as the viewport surface.
     *
     * <p>Subclasses should call {@code super.onDebugRender(viewportTextureID)} first
     * to preserve the viewport, then append their own panels.</p>
     *
     * @param viewportTextureID OpenGL texture ID of the rendered scene FrameBuffer
     */
    protected void onDebugRender(int viewportTextureID) {
        ui.renderViewport(viewportTextureID,
            window.getWidth(), window.getHeight(), viewportContextMenu);
    }

    /**
     * Override to inject menu items into the context menu that appears when the user
     * right-clicks inside the viewport.
     *
     * <p>This method is called from within an active popup context, so only popup-safe
     * widgets should be submitted here.</p>
     *
     * <p>Default implementation is empty (no context menu items).</p>
     */
    protected void onViewportContextMenu() {}

    /**
     * Called once when the loop ends, before the UI layer, the FrameBuffer and the window
     * are released. Free what {@link #onInit()} created.
     */
    protected abstract void onCleanup();
}
