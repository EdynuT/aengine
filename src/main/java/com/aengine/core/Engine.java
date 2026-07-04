package com.aengine.core;

import com.aengine.debug.DebugOverlay;
import com.aengine.graphics.FrameBuffer;
import com.aengine.utils.Logger;
import com.aengine.ecs.Registry;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import static org.lwjgl.opengl.GL11.GL_COLOR_BUFFER_BIT;
import static org.lwjgl.opengl.GL11.GL_DEPTH_BUFFER_BIT;
import static org.lwjgl.opengl.GL11.glClear;

public abstract class Engine {

    private final Window window;
    private volatile boolean running;
    private FrameBuffer frameBuffer;

    protected final Registry registry; 

    private Path targetClassPath;
    private String gameClassName;
    private long lastKnownModificationTime = 0;
    private long lastReloadCheckTime = 0;
    private static final long CHECK_INTERVAL_MS = 1000;

    public enum EngineState { LAUNCHER, EDITOR }
    private EngineState currentState = EngineState.EDITOR; // Instantiating as EDITOR for standalone fallback execution

    public Engine(String title) {
        this.window = new Window(title);
        this.registry = new Registry(); 
    }

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

        // Initialise ImGui AFTER Input so ImGui's GLFW callback installation chains
        // onto Input's callbacks rather than replacing them silently.
        DebugOverlay.init(window.getHandle());

        frameBuffer = new FrameBuffer(window.getWidth(), window.getHeight());
        
        com.aengine.network.SharedMemory.init(window.getWidth(), window.getHeight());

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

            org.lwjgl.glfw.GLFW.glfwPollEvents(); 
            Input.update();

            int vpW = (int) com.aengine.debug.DebugOverlay.getViewportImageW();
            int vpH = (int) com.aengine.debug.DebugOverlay.getViewportImageH();
            
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

                // Extract image 100% clean, before ImGui pollutes the state machine.
                frameBuffer.dispatchToSharedMemory();
                
                // 2. PHYSICAL DISPLAY RENDER PASS (Physical Monitor)
                org.lwjgl.opengl.GL11.glViewport(0, 0, window.getWidth(), window.getHeight());
                glClear(GL_COLOR_BUFFER_BIT | GL_DEPTH_BUFFER_BIT); // Clear the monitor

                DebugOverlay.beginFrame();
                onDebugRender(frameBuffer.getTextureID());
                DebugOverlay.endFrame();

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

        // Overlay must be shut down before the GLFW window is destroyed
        DebugOverlay.cleanup();

        if (frameBuffer != null) frameBuffer.cleanup();

        com.aengine.network.SharedMemory.cleanup();
        
        window.cleanup();

        Logger.info(Logger.System.CORE, "Engine lifecycle shutdown complete.");
    }

    public final void stop() { running = false; }
    public Window getWindow() { return window; }
    public Registry getRegistry() { return registry; }
    public void setEngineState(EngineState state) { this.currentState = state; }

    protected abstract void onInit();
    protected abstract void onUpdate(float deltaTime);
    protected abstract void onRender();

    /**
     * Called each frame after the scene FBO is complete but before buffer swap.
     * Submit all ImGui windows here. The default implementation renders the scene
     * FBO as a dockable "Viewport" ImGui panel.
     *
     * <p>Subclasses should call {@code super.onDebugRender(viewportTextureID)} first
     * to preserve the Viewport panel, then append additional debug windows.</p>
     *
     * @param viewportTextureID OpenGL texture ID of the rendered scene FrameBuffer
     */
    protected void onDebugRender(int viewportTextureID) {
        DebugOverlay.renderViewport(viewportTextureID,
            window.getWidth(), window.getHeight(), this::onViewportContextMenu);
    }

    /**
     * Override to inject ImGui menu items into the right-click context menu that
     * appears when the user right-clicks inside the Viewport image panel.
     *
     * <p>This method is called from within an active ImGui popup context, so only
     * {@code ImGui.menuItem}, {@code ImGui.beginMenu}/{@code endMenu}, separators,
     * and similar popup-safe widgets should be submitted here.</p>
     *
     * <p>Default implementation is empty (no context menu items).</p>
     */
    protected void onViewportContextMenu() {}

    protected abstract void onCleanup();
}
