package com.aengine;

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

            org.lwjgl.glfw.GLFW.glfwPollEvents(); 
            Input.update();

            // Clear hardware buffers once at the beginning of the frame execution step
            glClear(GL_COLOR_BUFFER_BIT | GL_DEPTH_BUFFER_BIT);

            if (currentState == EngineState.EDITOR) {
                onUpdate(deltaTime);
                
                // frameBuffer.bind();
                // glClear(GL_COLOR_BUFFER_BIT | GL_DEPTH_BUFFER_BIT);
                onRender();
                // frameBuffer.unbind();
                
                // Here you can render the final FrameBuffer texture pass straight to the maximized screen quad
            } else if (currentState == EngineState.LAUNCHER) {
                // Future fallback logic for host UI processing if required
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
        
        if (frameBuffer != null) frameBuffer.cleanup();
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
    protected abstract void onCleanup();
}
