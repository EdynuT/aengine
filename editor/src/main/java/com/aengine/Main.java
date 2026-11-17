package com.aengine;

import java.io.File;

import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.joml.Vector4f;

import com.aengine.audio.AudioDevice;
import com.aengine.core.Engine;
import com.aengine.core.Input;
import com.aengine.core.Keys;
import com.aengine.core.RenderMode;
import com.aengine.ecs.components.CameraComponent;
import com.aengine.ecs.components.SpriteComponent;
import com.aengine.ecs.components.TransformComponent;
import com.aengine.ecs.serialization.SceneLoader;
import com.aengine.ecs.systems.AudioSystem;
import com.aengine.ecs.systems.CameraSystem;
import com.aengine.ecs.systems.PhysicsSystem;
import com.aengine.ecs.systems.ScriptSystem;
import com.aengine.editor.EditorState;
import com.aengine.editor.MoveEntityEdit;
import com.aengine.editor.UndoHistory;
import com.aengine.editor.SceneSerializer;
import com.aengine.graphics.Camera;
import com.aengine.graphics.Renderer2D;
import com.aengine.graphics.Renderer3D;
import com.aengine.physics.PhysicsThread;
import com.aengine.utils.AssetBaker;
import com.aengine.utils.AssetWatcher;
import com.aengine.utils.FPSTracker;
import com.aengine.utils.FileSystem;
import com.aengine.utils.Logger;
import com.aengine.utils.ProjectWizard;



/**
 * The editor application: starts the engine with the editor's interface, mounts a game
 * project, and runs the edit and play loop.
 *
 * <p>Command line: an optional project path, plus {@code --2d} or {@code --3d} for the render
 * mode (3D by default). Without a path it uses {@code ~/AeternumSandbox}, creating it if
 * needed. F5 switches between editing and playing.</p>
 */
public class Main extends Engine {

    /** Whether the scene is being edited or simulated. Separate from {@link Engine.EngineState}. */
    public enum EngineState {
        /** Editing: physics paused, the inspector usable. */
        EDITOR,
        /** Playing: physics running; the scene is restored from a snapshot on return to EDITOR. */
        PLAY
    }
    private static EngineState currentState = EngineState.EDITOR;
    private com.google.gson.JsonObject sceneMemoryBackup = null;

    // --- Editor Camera State ---
    private float editorFov = 45.0f;
    private CameraSystem cameraSystem;
    private int cameraEntity;
    
    // The editor's interface, drawn by Aegis (Phase 4): panels, menus, the viewport.
    private com.aengine.editor.gui.EditorShell shell;

    // Dedicated physics thread — 120 Hz fixed-timestep loop, fully decoupled from the render rate.
    // All ECS Transform writes from the physics side are guarded by physicsThread.getSyncLock().
    private PhysicsThread physicsThread;
    private ScriptSystem scriptSystem;

    // Audio System — 60 Hz update loop, fully decoupled from the render rate.
    private AudioSystem audioSystem;


    // --- Editor Drag & Drop State ---
    private boolean isDraggingEntity = false;
    /** Where the entity being dragged was when the drag began, to record the move for undo. */
    private final Vector3f dragFrom = new Vector3f();
    private UndoHistory history;
    private float dragLastX, dragLastY;   // where the pointer was last frame, while dragging
    private int lastWindowWidth = -1;
    private int lastWindowHeight = -1;


    // Allocation-free temporary structural containers for 3D physical environment alignment
    private static final Vector3f GROUND_POSITION = new Vector3f(0.0f, -1.5f, 0.0f); 
    private static final Vector3f GROUND_SIZE = new Vector3f(1024.0f, 1.0f, 1024.0f);
    private static final Vector4f GROUND_COLOR = new Vector4f(0.50f, 0.50f, 0.50f, 1.0f); // Light Gray Floor

    // Shared execution state capturing target path sent from external process host
    private static String activeProjectPath;

    /** Creates the editor. The window, and the interface with it, open in {@link #run()}. */
    public Main() {
        super("AEngine - ECS Fly-Camera Runtime");
    }

    @Override
    protected void onInit() {
        Logger.info(Logger.System.CORE, "Initializing core pipeline execution context...");

        // Resolve active directory or deploy development workspace bootstrap
        if (activeProjectPath == null || activeProjectPath.trim().isEmpty()) {
            activeProjectPath = System.getProperty("user.home") + File.separator + "AeternumSandbox";
        }

        try {
            java.io.File projectDir = new java.io.File(activeProjectPath);
            if (!projectDir.exists() || !projectDir.isDirectory()) {
                Logger.warn(Logger.System.CORE, "Target workspace not found. Deploying Project Wizard Bootstrap at: " + activeProjectPath);
                ProjectWizard.createProject(System.getProperty("user.home"), "AeternumSandbox");
            }
            
            FileSystem.mountProject(activeProjectPath);
            String rawAssetsDir = activeProjectPath + File.separator + "assets" + File.separator + "src";
            String vfsAssetsDir = activeProjectPath + File.separator + "assets" + File.separator + "baked";
            AssetBaker.bakeDirectory(rawAssetsDir, vfsAssetsDir);
            AssetWatcher.start(activeProjectPath); 
        } catch (Exception e) {
            Logger.error(Logger.System.CORE, "VFS Handshake critical failure. Halting engine initialization pipeline.");
            throw new RuntimeException("Critical core infrastructure failure during VFS mount", e);
        }

        AudioDevice.init();
        Renderer2D.init();
        Renderer3D.init();

        // Atmospheric sky blue background clear color registration (0.45f, 0.65f, 0.85f, 1.0f) 
        // Gray background for neutral visual (0.30f, 0.30f, 0.30f, 1.0f)
        Renderer2D.setClearColor(0.30f, 0.30f, 0.30f, 1.0f);
        
        audioSystem = new AudioSystem();
        cameraSystem = new CameraSystem();
        cameraEntity = registry.createEntity();
        
        // Construct PhysicsSystem, wrap it in a dedicated background thread, and start it.
        PhysicsSystem physicsSystem = new PhysicsSystem();
        physicsThread = new PhysicsThread(registry, physicsSystem);
        physicsThread.init();
        physicsThread.setPaused(true); // <--- Editor starts frozen

        // The scene's undo history, applied under the physics thread's lock; then the
        // interface: panels registered, the screen built from the layouts in line, the theme
        // and the language applied. See EditorShell.
        history = new UndoHistory(physicsThread.getSyncLock());
        shell = new com.aengine.editor.gui.EditorShell(getWindow(), history);
        shell.showProject(activeProjectPath);

        scriptSystem = new ScriptSystem();
        
        // If is 3D, the camera recedes 5 meters. If is 2D, it stays at Z=0 along with the sprites.
        float cameraZ = (RenderMode.active() == RenderMode.MODE_3D) ? 5.0f : 0.0f;
        registry.addComponent(cameraEntity, new TransformComponent(new Vector3f(0.0f, 0.0f, cameraZ)));

        if (RenderMode.active() == RenderMode.MODE_3D) {
            Logger.info(Logger.System.RENDERER, "Enforcing Core 3D Perspective execution pipeline.");
            // Use the editorFov variable instead of the hardcoded value
            registry.addComponent(cameraEntity, new CameraComponent(editorFov, getWindow().getWidth(), getWindow().getHeight(), 0.1f, 1000.0f, true));
        } else {
            Logger.info(Logger.System.RENDERER, "Enforcing Core 2D Orthographic execution pipeline. Z-Axis dropped.");
            
            // False enforces 2D Orthographic projection matrix calculation, dropping spatial depth distortions
            registry.addComponent(cameraEntity, new CameraComponent(0.0f, getWindow().getWidth(), getWindow().getHeight(), -1.0f, 100.0f, false));
            
            // HARDWARE CONTEXT: Data-Driven Instantiation
            SceneLoader.load(registry, "assets://data/scenes/level_01.scene");
        }
    }

    private boolean wasF5Pressed = false;

    /** Whether the scene, while playing, is paused: physics, scripts and audio frozen. */
    private boolean playPaused = false;

    /** Starts the scene: a snapshot of it is kept in memory, to be restored by {@link #enterEditor}. */
    private void enterPlay() {
        Logger.info(Logger.System.CORE, "Entering PLAY Mode. Snapshotting ECS to RAM...");
        currentState = EngineState.PLAY;
        playPaused = false;
        EditorState.deselect(); // Clear inspector to prevent editing during simulation
        // Serialize straight to RAM, bypassing disk IO
        sceneMemoryBackup = SceneSerializer.serializeScene(registry, "RAM_BACKUP");
        physicsThread.setPaused(false);
        shell.showPlayState(true, false);
    }

    /** Stops the scene and puts it back as it was when it started. */
    private void enterEditor() {
        Logger.info(Logger.System.CORE, "Entering EDITOR Mode. Restoring ECS from RAM...");
        currentState = EngineState.EDITOR;
        playPaused = false;
        physicsThread.setPaused(true);
        // Wipe dynamic entities cleanly
        synchronized (physicsThread.getSyncLock()) {
            registry.clearScene();
            SceneLoader.load(registry, sceneMemoryBackup);
        }
        // The scene was rebuilt from the snapshot, and an entity may not have the number it
        // had: the edits recorded before name entities that may now be others.
        history.clear();
        shell.showPlayState(false, false);
    }

    @Override
    protected void onUpdate(float deltaTime) {
        FPSTracker.update(deltaTime);    // Optional: Enable FPS tracking for the editor stats panel. Comment it but DO NOT REMOVE
        // The viewport's size, as the interface last solved it: the scene renders at it.
        int currentW = (int) shell.viewportWidth();
        int currentH = (int) shell.viewportHeight();
        
        boolean boundsChanged = (currentW > 0 && currentH > 0 && (currentW != lastWindowWidth || currentH != lastWindowHeight));
        boolean fovChanged = false;

        // The wheel over the viewport changes the field of view.
        if (RenderMode.active() == RenderMode.MODE_3D) {
            float scroll = shell.viewportWheel();
            if (scroll != 0.0f) {
                // Roll up (positive) decreases FOV (Zoom In). Roll down (negative) increases FOV (Zoom Out).
                // The scroll sensitivity is 10% of the current FOV, ensuring a minimum step of 0.5 degrees
                // to prevent the zoom from getting mathematically stuck when approaching 1.0f.
                float zoomSensitivity = Math.max(editorFov * 0.1f, 0.5f);
                editorFov -= scroll * zoomSensitivity;
                
                // Strict mathematical clamping between 1.0f and 120.0f
                if (editorFov < 1.0f) editorFov = 1.0f;
                if (editorFov > 120.0f) editorFov = 120.0f;
                
                fovChanged = true;
            }
        }

        // If the window size changed OR the user used the scroll, rebuild the matrix
        if (boundsChanged || fovChanged) {
            if (boundsChanged) {
                lastWindowWidth = currentW;
                lastWindowHeight = currentH;
            }

            synchronized (physicsThread.getSyncLock()) {
                if (RenderMode.active() == RenderMode.MODE_3D) {
                    registry.addComponent(cameraEntity, new CameraComponent(editorFov, currentW, currentH, 0.1f, 1000.0f, true));
                } else {
                    registry.addComponent(cameraEntity, new CameraComponent(0.0f, currentW, currentH, -1.0f, 100.0f, false));
                }
            }
        }

        // --- STATE MACHINE TRANSITION: F5, or the toolbar's Play, Pause and Stop ---
        // The toolbar's choices are those of the last frame's interface, which runs after
        // this update; F5 is read here. Play starts the scene, or stops it if it is playing;
        // Stop stops it; Pause freezes it where it is while it plays.
        boolean isF5Pressed = Input.isKeyPressed(Keys.F5);
        boolean f5 = isF5Pressed && !wasF5Pressed;
        wasF5Pressed = isF5Pressed;
        if (currentState == EngineState.EDITOR && (f5 || shell.playAsked())) {
            enterPlay();
        } else if (currentState == EngineState.PLAY && (f5 || shell.stopAsked())) {
            enterEditor();
        }
        if (currentState == EngineState.PLAY && shell.pauseAsked() != playPaused) {
            playPaused = shell.pauseAsked();
            physicsThread.setPaused(playPaused);
            shell.showPlayState(true, playPaused);
            Logger.info(Logger.System.CORE, playPaused ? "PLAY Mode paused." : "PLAY Mode resumed.");
        }

        // Cap deltaTime before any system update to prevent spiral-of-death if the main
        // thread falls behind (e.g., during asset loading or OS scheduling spikes).
        if (deltaTime > 0.25f) deltaTime = 0.25f;

        // Guard all registry access with the physics syncLock.
        synchronized (physicsThread.getSyncLock()) {
            
            if (currentState == EngineState.PLAY && !playPaused) {
                audioSystem.update(registry, deltaTime);
                scriptSystem.update(registry, deltaTime);
            }
            // Camera operates in both modes, taking the keys only while the viewport has focus:
            // Space typed into a field must not also raise it.
            cameraSystem.setInputEnabled(shell.viewportFocused());
            cameraSystem.update(registry, deltaTime);
        }

        Input.update();
    }

    @Override
    protected void onRender() {
        // The depth test the render mode needs, set every frame rather than once at startup:
        // the interface's pass, drawn after the scene, leaves depth testing on, which in 2D
        // would make overlapping quads at the same Z keep the first one drawn instead of the
        // last (KNOWN_ISSUES #10).
        if (RenderMode.active() == RenderMode.MODE_3D) org.lwjgl.opengl.GL11.glEnable(org.lwjgl.opengl.GL11.GL_DEPTH_TEST);
        else                                           org.lwjgl.opengl.GL11.glDisable(org.lwjgl.opengl.GL11.GL_DEPTH_TEST);

        // Acquire the physics sync lock for the duration of this render pass.
        synchronized (physicsThread.getSyncLock()) {

            var cameraPool = registry.getPool(CameraComponent.class);
            Camera activeCamera = null;

            if (cameraPool != null) {
                CameraComponent[] cameras = cameraPool.getRawComponents();
                int totalCameras = cameraPool.size();
                for (int i = 0; i < totalCameras; i++) {
                    if (cameras[i] != null && cameras[i].primary) {
                        activeCamera = cameras[i].camera;
                        break;
                    }
                }
            }

            if (activeCamera == null) return;

            Renderer3D.beginScene(activeCamera);

            // --- RENDER PHYSICAL ENVIRONMENT (THE GROUND) ---
            if (RenderMode.active() == RenderMode.MODE_3D) {
                // Rotates the structural quad -90 degrees on the X-axis to lay it flat perpendicular to Y
                Renderer3D.drawPlane(GROUND_POSITION, new Vector3f(0.0f, 0.0f, 0.0f), GROUND_SIZE, GROUND_COLOR);
            }

            // --- RENDER ECS DYNAMIC ENTITIES SET ---
            var entities = registry.getEntitiesWith(
                TransformComponent.class,
                SpriteComponent.class
            );

            for (int i = 0; i < entities.size(); i++) {
                int entityID = entities.get(i);
                var transform = registry.getComponent(entityID, TransformComponent.class);
                var sprite    = registry.getComponent(entityID, SpriteComponent.class);

                if (RenderMode.active() == RenderMode.MODE_3D) {
                    // Volumetric mesh path — respects the entity's real position/rotation/scale.
                    Renderer3D.drawCube(transform.position, transform.rotation, transform.scale, sprite.color);
                } else {
                    Renderer2D.drawEntityQuad(transform, sprite);
                }
            }

            Renderer3D.endScene();

        } // release physicsThread.getSyncLock()
    }

    @Override
    protected void onCleanup() {
        Logger.info(Logger.System.CORE, "Terminating active workspace runtime contexts. Executing hardware cleanup...");
        // Stop the physics thread first to prevent it from accessing the registry
        // after the renderers and VFS have been torn down.
        if (physicsThread != null) physicsThread.cleanup();
        Renderer3D.cleanup();
        Renderer2D.cleanup();
        AudioDevice.cleanup();

        if (shell != null) shell.cleanup();
    }

    @Override
    protected void onDrawInterface(int sceneTexture) {
        // The interface, drawn by Aegis, with the scene in its viewport; the scene renders at
        // the viewport's size from the next frame.
        shell.showStats(FPSTracker.getCurrentFPS(), registry.getEntityCount());
        shell.frame(getWindow().getWidth(), getWindow().getHeight(), sceneTexture);
        setSceneSize((int) shell.viewportWidth(), (int) shell.viewportHeight());

        if (shell.saveAsked()) saveCurrentScene();
        // Esc leaves the editor while the scene has focus, as it did before; anywhere else it
        // closes what is open, or leaves the field being typed into.
        if (shell.quitAsked() || shell.escapeInViewport()) stop();

        // ── Entity Picking ────────────────────────────────────────────────────
        // A press on the scene → unproject to world space and hit-test every entity's AABB.
        // Only meaningful in 2D orthographic mode.
        float mouseX = (float) Input.getMouseX(), mouseY = (float) Input.getMouseY();
        if (shell.viewportPressed()) {
            handleViewportPick(shell.pressNdcX(), shell.pressNdcY());
            dragLastX = mouseX;
            dragLastY = mouseY;
        }

        if (isDraggingEntity && EditorState.hasSelection()) {
            if (Input.isMouseButtonPressed(0)) {
                Camera camera = getActiveCamera();
                float dxPixels = mouseX - dragLastX;
                float dyPixels = mouseY - dragLastY;
                dragLastX = mouseX;
                dragLastY = mouseY;
                if (camera != null && (dxPixels != 0.0f || dyPixels != 0.0f)) {
                    // Convert the pixel delta to an NDC delta by the viewport's own size
                    float ndcDx =  2.0f * (dxPixels / shell.viewportWidth());
                    float ndcDy = -2.0f * (dyPixels / shell.viewportHeight()); // Y inverted

                    Matrix4f invVP = new Matrix4f(camera.getViewProjection()).invert();
                    float worldDx = invVP.m00() * ndcDx + invVP.m10() * ndcDy;
                    float worldDy = invVP.m01() * ndcDx + invVP.m11() * ndcDy;

                    TransformComponent t = registry.getComponent(EditorState.getSelectedEntity(), TransformComponent.class);
                    if (t != null) {
                        synchronized (physicsThread.getSyncLock()) {
                            t.position.x += worldDx;
                            t.position.y += worldDy;
                        }
                    }
                }
            } else {
                isDraggingEntity = false; // Mouse released, end the drag
                // The move, start to end, is one change to undo — if it moved at all.
                int moved = EditorState.getSelectedEntity();
                TransformComponent t = registry.getComponent(moved, TransformComponent.class);
                if (t != null && !t.position.equals(dragFrom)) {
                    history.record(new MoveEntityEdit(registry, moved, dragFrom, t.position));
                }
            }
        }
    }

    // =========================================================================
    // Entity picking — screen NDC → world space → AABB test
    // =========================================================================

    private void handleViewportPick(float ndcX, float ndcY) {
        Camera camera = getActiveCamera();
        if (camera == null) return;

        // Unproject NDC to world space using the inverse view-projection matrix
        Matrix4f invVP = new Matrix4f(camera.getViewProjection()).invert();
        float clipW = 1.0f;
        float worldX = invVP.m00() * ndcX + invVP.m10() * ndcY + invVP.m30() * clipW;
        float worldY = invVP.m01() * ndcX + invVP.m11() * ndcY + invVP.m31() * clipW;

        int hit = -1;
        var entities = registry.getEntitiesWith(TransformComponent.class, SpriteComponent.class);
        
        // Inverse iteration (Top-Most First) ensures to select the object that is visually on top
        for (int i = entities.size() - 1; i >= 0; i--) {
            int eid = entities.get(i);
            TransformComponent t = registry.getComponent(eid, TransformComponent.class);
            if (t == null) continue;
            float halfW = t.scale.x * 0.5f;
            float halfH = t.scale.y * 0.5f;
            if (worldX >= t.position.x - halfW && worldX <= t.position.x + halfW &&
                worldY >= t.position.y - halfH && worldY <= t.position.y + halfH) {
                hit = eid;
                break;
            }
        }

        EditorState.select(hit); // hit == -1 deselects
        isDraggingEntity = (hit != -1); // Locks the state machine in Dragging mode if something is hit
        if (hit != -1) dragFrom.set(registry.getComponent(hit, TransformComponent.class).position);
    }

    // =========================================================================
    // Helpers
    // =========================================================================

    private Camera getActiveCamera() {
        var cameraPool = registry.getPool(CameraComponent.class);
        if (cameraPool == null) return null;
        CameraComponent[] cameras = cameraPool.getRawComponents();
        int total = cameraPool.size();
        for (int i = 0; i < total; i++) {
            if (cameras[i] != null && cameras[i].primary) return cameras[i].camera;
        }
        return null;
    }

    private void saveCurrentScene() {
        // In 2D mode, save back to the loaded scene file.
        // In 3D mode, no scene file exists by default — save to a default path.
        String scenePath = (RenderMode.active() == RenderMode.MODE_2D)
            ? "assets://data/scenes/level_01.scene"
            : "assets://data/scenes/scene_3d.scene";
        SceneSerializer.save(registry, scenePath, "Edited Scene");
    }

    /**
     * Returns the render mode chosen at startup.
     *
     * @return the same as {@link RenderMode#active()}
     */
    public static RenderMode getActiveRenderMode() { return RenderMode.active(); }

    /**
     * Starts the editor.
     *
     * @param args an optional project path, and {@code --2d} or {@code --3d}
     */
    public static void main(String[] args) {
        for (String arg : args) {
            if (arg.equalsIgnoreCase("--2d")) {
                RenderMode.setActive(RenderMode.MODE_2D);
            } else if (arg.equalsIgnoreCase("--3d")) {
                RenderMode.setActive(RenderMode.MODE_3D);
            } else if (arg != null && !arg.trim().isEmpty() && !arg.startsWith("-")) {
                activeProjectPath = arg;
            }
        }

        if (activeProjectPath == null) {
            String os = System.getProperty("os.name").toLowerCase();
            if (os.contains("win")) {
                activeProjectPath = System.getProperty("user.home") + "\\AeternumSandbox";
            } else {
                activeProjectPath = System.getProperty("user.home") + "/AeternumSandbox";
            }
            Logger.warn(Logger.System.CORE, "No host initialization parameters detected. Binding fallback workspace: " + activeProjectPath);
        }

        new Main().run();
    }
}
