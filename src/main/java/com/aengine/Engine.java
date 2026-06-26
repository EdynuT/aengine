package com.aengine;

import com.aengine.utils.DynamicClassLoader;
import com.aengine.utils.Logger;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import static org.lwjgl.opengl.GL11.GL_COLOR_BUFFER_BIT;
import static org.lwjgl.opengl.GL11.GL_DEPTH_BUFFER_BIT;
import static org.lwjgl.opengl.GL11.glClear;

public abstract class Engine {

    private final Window window;
    private volatile boolean running;

    // Hot reload tracking state
    private GameBehavior activeGameInstance;
    private Path targetClassPath;
    private String gameClassName;
    private long lastKnownModificationTime = 0;
    private long lastReloadCheckTime = 0;
    private static final long CHECK_INTERVAL_MS = 1000; // Check disk changes every 1s

    public Engine(String title, int width, int height) {
        this.window = new Window(title, width, height);
    }

    /**
     * Configures the hot reload sub-system targets.
     * @param buildDirectory Absolute or relative path to the compiled game output classes directory (e.g. "game/build/classes/java/main")
     * @param fullyQualifiedClassName The full package + name target class (e.g. "com.mygame.AeternumClient")
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
            Logger.warn(Logger.System.CORE, "Hot reload target bytecode file not found yet at: %s. System will wait for compilation.", targetClassPath.toAbsolutePath());
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
        
        // Load initial live instance if hot reload target configuration exists
        if (gameClassName != null) {
            reloadGameCode();
        }

        Logger.info(Logger.System.CORE, "Invoking native engine host onInit callback...");
        onInit();
    }

    private void loop() {
        running = true;
        long lastTime = System.nanoTime();
        
        Logger.info(Logger.System.CORE, "Engine main loop engaged.");

        while (running && !window.shouldClose()) {
            long now = System.nanoTime();
            float deltaTime = (now - lastTime) / 1_000_000_000.0f;
            lastTime = now;

            // Non-blocking file polling to avoid frame stuttering
            long currentMillis = now / 1_000_000;
            if (currentMillis - lastReloadCheckTime > CHECK_INTERVAL_MS) {
                checkAndHandleHotReload();
                lastReloadCheckTime = currentMillis;
            }

            Input.poll();
            
            // Execute client logic loop updates
            if (activeGameInstance != null) {
                activeGameInstance.update(deltaTime);
            }
            onUpdate(deltaTime);

            glClear(GL_COLOR_BUFFER_BIT | GL_DEPTH_BUFFER_BIT);
            
            // Execute client render buffer dispatches
            if (activeGameInstance != null) {
                activeGameInstance.render();
            }
            onRender();

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
        try {
            Logger.debug(Logger.System.CORE, "Instantiating clean isolated DynamicClassLoader branch...");
            Path rootClassDir = targetClassPath.getFileSystem().getPath(targetClassPath.toString().substring(0, targetClassPath.toString().indexOf(gameClassName.replace('.', '/'))));
            DynamicClassLoader loader = new DynamicClassLoader(rootClassDir);
            
            Class<?> gameClass = loader.findClass(gameClassName);
            if (!GameBehavior.class.isAssignableFrom(gameClass)) {
                Logger.error(Logger.System.CORE, "Hot reload rejected: Target class '%s' does not implement GameBehavior.", gameClassName);
                return;
            }

            GameBehavior newInstance = (GameBehavior) gameClass.getDeclaredConstructor().newInstance();

            // Safely swap state machines
            if (activeGameInstance != null) {
                Logger.debug(Logger.System.CORE, "Executing teardown on obsolete logic instance...");
                activeGameInstance.cleanup();
            }

            activeGameInstance = newInstance;
            Logger.debug(Logger.System.CORE, "Bootstrapping new loaded runtime instance context...");
            activeGameInstance.init();
            
            Logger.info(Logger.System.CORE, "Hot reload pipeline successfully processed target class '%s'.", gameClassName);
            
            // Hint JVM to garbage collect old ClassLoader and obsolete definitions immediately
            System.gc();
        } catch (Exception e) {
            Logger.error(Logger.System.CORE, "Hot reload routine execution crashed. Preserving old logic instance to avoid engine collapse.");
            e.printStackTrace();
        }
    }

    private void cleanup() {
        Logger.info(Logger.System.CORE, "Executing engine teardown sequence...");
        if (activeGameInstance != null) {
            activeGameInstance.cleanup();
        }
        onCleanup();
        window.cleanup();
        Logger.info(Logger.System.CORE, "Engine lifecycle shutdown complete.");
    }

    public final void stop() { 
        running = false; 
    }
    
    public Window getWindow() { 
        return window; 
    }

    protected abstract void onInit();
    protected abstract void onUpdate(float deltaTime);
    protected abstract void onRender();
    protected abstract void onCleanup();
}
