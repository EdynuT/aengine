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
import com.aengine.debug.DebugOverlay;
import com.aengine.ecs.components.CameraComponent;
import com.aengine.ecs.components.SpriteComponent;
import com.aengine.ecs.components.TransformComponent;
import com.aengine.ecs.serialization.SceneLoader;
import com.aengine.ecs.systems.AudioSystem;
import com.aengine.ecs.systems.CameraSystem;
import com.aengine.ecs.systems.PhysicsSystem;
import com.aengine.ecs.systems.ScriptSystem;
import com.aengine.editor.EditorState;
import com.aengine.editor.EntityFactory;
import com.aengine.editor.SceneSerializer;
import com.aengine.graphics.AssetManager;
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

import imgui.ImGui;


/**
 * The editor application: starts the engine with the Dear ImGui interface, mounts a game
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
    
    private final com.aengine.editor.ImGuiUILayer imguiLayer;

    // The editor's interface, drawn by Aegis (Phase 4): panels, menus, the viewport.
    private com.aengine.editor.EditorShell shell;

    // Dedicated physics thread — 120 Hz fixed-timestep loop, fully decoupled from the render rate.
    // All ECS Transform writes from the physics side are guarded by physicsThread.getSyncLock().
    private PhysicsThread physicsThread;
    private ScriptSystem scriptSystem;

    // Audio System — 60 Hz update loop, fully decoupled from the render rate.
    private AudioSystem audioSystem;


    // =========================================================================
    // Editor state — pre-allocated scratch buffers to avoid per-frame GC churn.
    // dragFloat3 / colorEdit4 require float[] arrays as in/out parameters.
    // =========================================================================

    private static final float[]  EDITOR_POS   = new float[3];
    private static final float[]  EDITOR_ROT   = new float[3];
    private static final float[]  EDITOR_SCALE = new float[3];
    private static final float[]  EDITOR_COLOR = new float[4];

    // --- Editor Drag & Drop State ---
    private boolean isDraggingEntity = false;
    private float dragLastX, dragLastY;   // where the pointer was last frame, while dragging
    private int pendingDeleteEntity = -1;
    private int lastWindowWidth = -1;
    private int lastWindowHeight = -1;


    // Allocation-free temporary structural containers for 3D physical environment alignment
    private static final Vector3f GROUND_POSITION = new Vector3f(0.0f, -1.5f, 0.0f); 
    private static final Vector3f GROUND_SIZE = new Vector3f(1024.0f, 1.0f, 1024.0f);
    private static final Vector4f GROUND_COLOR = new Vector4f(0.50f, 0.50f, 0.50f, 1.0f); // Light Gray Floor

    // Shared execution state capturing target path sent from external process host
    private static String activeProjectPath;

    /** Creates the editor and installs its Dear ImGui interface. The window opens in {@link #run()}. */
    public Main() {
        super("AEngine - ECS Fly-Camera Runtime");
        this.imguiLayer = new com.aengine.editor.ImGuiUILayer();
        setUILayer(this.imguiLayer);
    }

    @Override
    protected void onInit() {
        Logger.info(Logger.System.CORE, "Initializing core pipeline execution context...");
        
        ImGui.getIO().setConfigWindowsMoveFromTitleBarOnly(true);

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

        // The interface: panels registered, the screen built from the layouts in line, the
        // theme and the language applied. See EditorShell.
        shell = new com.aengine.editor.EditorShell(getWindow());
        
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

        scriptSystem = new ScriptSystem();
        
        // If is 3D, the camera recedes 5 meters. If is 2D, it stays at Z=0 along with the sprites.
        float cameraZ = (RenderMode.active() == RenderMode.MODE_3D) ? 5.0f : 0.0f;
        registry.addComponent(cameraEntity, new TransformComponent(new Vector3f(0.0f, 0.0f, cameraZ)));

        if (RenderMode.active() == RenderMode.MODE_3D) {
            Logger.info(Logger.System.RENDERER, "Enforcing Core 3D Perspective execution pipeline.");
            org.lwjgl.opengl.GL11.glEnable(org.lwjgl.opengl.GL11.GL_DEPTH_TEST);
            // Use the editorFov variable instead of the hardcoded value
            registry.addComponent(cameraEntity, new CameraComponent(editorFov, getWindow().getWidth(), getWindow().getHeight(), 0.1f, 1000.0f, true));
        } else {
            Logger.info(Logger.System.RENDERER, "Enforcing Core 2D Orthographic execution pipeline. Z-Axis dropped.");
            org.lwjgl.opengl.GL11.glDisable(org.lwjgl.opengl.GL11.GL_DEPTH_TEST);
            
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
    protected void onDebugRender(int viewportTextureID) {
        // The interface first: it is drawn now, and Dear ImGui's remaining windows render on
        // top of it when the frame ends. Over one of them the pointer is ImGui's, and while one
        // of its fields is typed into the keys are.
        shell.frame(getWindow().getWidth(), getWindow().getHeight(), viewportTextureID,
            DebugOverlay.ENABLED && ImGui.getIO().getWantCaptureMouse(),
            DebugOverlay.ENABLED && ImGui.getIO().getWantCaptureKeyboard());
        imguiLayer.setViewportSize(shell.viewportWidth(), shell.viewportHeight());

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
            }
        }

        if (!DebugOverlay.ENABLED) return;

        // ── Engine Stats ──────────────────────────────────────────────────────
        ImGui.begin("Engine Stats", DebugOverlay.showEnginePanel());
        ImGui.text(String.format("State    : %s", currentState.name()));
        ImGui.text(String.format("FPS      : %d", FPSTracker.getCurrentFPS()));
        ImGui.text(String.format("Mode     : %s", RenderMode.active()));
        ImGui.text(String.format("Entities : %d", registry.getEntityCount()));
        ImGui.separator();
        ImGui.textDisabled("Project: " + (activeProjectPath != null ? activeProjectPath : "—"));
        ImGui.end();

        // ── Physics Debug ─────────────────────────────────────────────────────
        ImGui.begin("Physics Debug", DebugOverlay.showPhysicsPanel());
        boolean alive = physicsThread != null && physicsThread.isAlive();
        ImGui.text("Thread   : " + (alive ? "Running @ 120 Hz" : "Stopped"));
        ImGui.separator();
        ImGui.textDisabled("Tip: use Logger.TRACE to see per-step grid stats");
        ImGui.end();

        // ── Scene Hierarchy ───────────────────────────────────────────────────
        renderHierarchyPanel();

        // ── Properties ───────────────────────────────────────────────────────
        renderPropertiesPanel();
    }

    // =========================================================================
    // Viewport context menu — right-click on the scene image
    // =========================================================================

    @Override
    protected void onViewportContextMenu() {
        if (ImGui.beginMenu("Add Entity")) {
            if (ImGui.menuItem("Quad (2D)")) {
                synchronized (physicsThread.getSyncLock()) {
                    int id = EntityFactory.createQuad(registry, 0.0f, 0.0f);
                    EditorState.select(id);
                }
            }
            if (ImGui.menuItem("Cube (3D)")) {
                synchronized (physicsThread.getSyncLock()) {
                    int id = EntityFactory.createCube(registry, 0.0f, 0.0f, 0.0f);
                    EditorState.select(id);
                }
            }
            ImGui.endMenu();
        }

        ImGui.separator();

        if (ImGui.menuItem("Save Scene")) {
            saveCurrentScene();
        }
    }

    // =========================================================================
    // Scene Hierarchy panel
    // =========================================================================

    private void renderHierarchyPanel() {
        ImGui.begin("Scene Hierarchy");

        if (ImGui.button("+ Add Entity")) {
            synchronized (physicsThread.getSyncLock()) {
                int id = (RenderMode.active() == RenderMode.MODE_2D)
                    ? EntityFactory.createQuad(registry, 0.0f, 0.0f)
                    : EntityFactory.createCube(registry, 0.0f, 0.0f, 0.0f);
                EditorState.select(id);
            }
        }
        ImGui.sameLine();
        if (ImGui.button("Save Scene")) {
            saveCurrentScene();
        }

        ImGui.separator();

        // List all renderable entities (have both Transform + Sprite)
        var entities = registry.getEntitiesWith(TransformComponent.class, SpriteComponent.class);
        for (int i = 0; i < entities.size(); i++) {
            int entityId = entities.get(i);
            String label = EditorState.getEntityName(entityId);

            boolean isSelected = EditorState.getSelectedEntity() == entityId;
            if (ImGui.selectable(label + "##" + entityId, isSelected)) {
                EditorState.select(entityId);
            }

            // Right-click on a hierarchy item → context menu
            if (ImGui.beginPopupContextItem("##ectx_" + entityId)) {
                if (ImGui.menuItem("Select")) {
                    EditorState.select(entityId);
                }
                ImGui.separator();
                if (ImGui.menuItem("Delete")) {
                    pendingDeleteEntity = entityId;
                }
                ImGui.endPopup();
            }
        }

        ImGui.end();

        // Deferred entity deletion — applied after the hierarchy loop to avoid modifying the registry mid-frame
        if (pendingDeleteEntity != -1) {
            synchronized (physicsThread.getSyncLock()) {
                if (EditorState.getSelectedEntity() == pendingDeleteEntity) {
                    EditorState.deselect();
                }
                registry.destroyEntity(pendingDeleteEntity);
            }
            pendingDeleteEntity = -1;
        }
    }

    // =========================================================================
    // Properties panel — edit selected entity's components
    // =========================================================================

    private void renderPropertiesPanel() {
        ImGui.begin("Properties");

        if (!EditorState.hasSelection()) {
            ImGui.textDisabled("No entity selected.");
            ImGui.end();
            return;
        }

        int sel = EditorState.getSelectedEntity();
        ImGui.text(EditorState.getEntityName(sel) + "  (ID: " + sel + ")");
        ImGui.separator();

        // --- Transform ---
        TransformComponent t = registry.getComponent(sel, TransformComponent.class);
        if (t != null && ImGui.collapsingHeader("Transform")) {

            EDITOR_POS[0] = t.position.x; EDITOR_POS[1] = t.position.y; EDITOR_POS[2] = t.position.z;
            if (ImGui.dragFloat3("Position", EDITOR_POS, 0.1f)) {
                synchronized (physicsThread.getSyncLock()) {
                    t.position.set(EDITOR_POS[0], EDITOR_POS[1], EDITOR_POS[2]);
                }
            }

            EDITOR_ROT[0] = t.rotation.x; EDITOR_ROT[1] = t.rotation.y; EDITOR_ROT[2] = t.rotation.z;
            if (ImGui.dragFloat3("Rotation", EDITOR_ROT, 0.5f)) {
                t.rotation.set(EDITOR_ROT[0], EDITOR_ROT[1], EDITOR_ROT[2]);
            }

            EDITOR_SCALE[0] = t.scale.x; EDITOR_SCALE[1] = t.scale.y; EDITOR_SCALE[2] = t.scale.z;
            if (ImGui.dragFloat3("Scale", EDITOR_SCALE, 0.05f, 0.01f, 100.0f)) {
                t.scale.set(EDITOR_SCALE[0], EDITOR_SCALE[1], EDITOR_SCALE[2]);
            }
        }

        // --- Sprite ---
        SpriteComponent s = registry.getComponent(sel, SpriteComponent.class);
        if (s != null && ImGui.collapsingHeader("Sprite")) {

            EDITOR_COLOR[0] = s.color.x; EDITOR_COLOR[1] = s.color.y;
            EDITOR_COLOR[2] = s.color.z; EDITOR_COLOR[3] = s.color.w;
            if (ImGui.colorEdit4("Color", EDITOR_COLOR)) {
                s.color.set(EDITOR_COLOR[0], EDITOR_COLOR[1], EDITOR_COLOR[2], EDITOR_COLOR[3]);
            }

            ImGui.spacing();
            String currentTex = s.texturePath != null ? s.texturePath : "None";
            ImGui.text("Texture: " + currentTex);

            // Expandable texture browser — lists all .atex files in the baked textures dir
            if (ImGui.treeNode("Assign Texture##" + sel)) {
                File bakedTexDir = FileSystem.resolve("assets://baked/textures");
                if (bakedTexDir != null && bakedTexDir.isDirectory()) {
                    File[] texFiles = bakedTexDir.listFiles(
                        f -> f.isFile() && f.getName().endsWith(".atex"));
                    if (texFiles != null && texFiles.length > 0) {
                        for (File texFile : texFiles) {
                            String vPath = "assets://baked/textures/" + texFile.getName();
                            boolean isCurrent = vPath.equals(s.texturePath);
                            if (ImGui.selectable(texFile.getName() + "##tex_" + texFile.getName(),
                                                 isCurrent)) {
                                s.texturePath = vPath;
                                s.texture = AssetManager.getTexture(vPath);
                            }
                        }
                    } else {
                        ImGui.textDisabled("No .atex files found.");
                        ImGui.textDisabled("Drop a texture into assets/src/textures/");
                        ImGui.textDisabled("and rebuild to bake it.");
                    }
                } else {
                    ImGui.textDisabled("Baked textures directory not found.");
                }
                ImGui.treePop();
            }

            // Clear texture (revert to solid colour)
            if (s.texturePath != null) {
                ImGui.spacing();
                if (ImGui.button("Remove Texture##" + sel)) {
                    s.texture = null;
                    s.texturePath = null;
                }
            }
        }

        ImGui.end();
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
