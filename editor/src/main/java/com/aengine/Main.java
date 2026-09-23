package com.aengine;

import java.io.File;

import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.joml.Vector4f;

import imgui.ImGui;

import com.aengine.audio.AudioDevice;

import com.aengine.core.Engine;
import com.aengine.core.RenderMode;
import com.aengine.core.Input;
import com.aengine.core.Keys;

import com.aengine.ecs.components.CameraComponent;
import com.aengine.ecs.components.SpriteComponent;
import com.aengine.ecs.components.TransformComponent;
import com.aengine.ecs.serialization.SceneLoader;
import com.aengine.ecs.systems.AudioSystem;
import com.aengine.ecs.systems.CameraSystem;
import com.aengine.ecs.systems.PhysicsSystem;
import com.aengine.ecs.systems.ScriptSystem;

import com.aengine.debug.DebugOverlay;

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
import com.aengine.utils.FileSystem;
import com.aengine.utils.FPSTracker;
import com.aengine.utils.Logger;
import com.aengine.utils.ProjectWizard;


public class Main extends Engine {

    public enum EngineState { EDITOR, PLAY }
    private static EngineState currentState = EngineState.EDITOR;
    private com.google.gson.JsonObject sceneMemoryBackup = null;

    // --- Editor Camera State ---
    private float editorFov = 45.0f;
    private CameraSystem cameraSystem;
    private int cameraEntity;
    
    private final com.aengine.editor.ImGuiUILayer imguiLayer;

    // -------------------------------------------------------------------------------------
    // SCAFFOLDING — first light for Aegis, the engine's own UI framework.
    //
    // Exercises shapes, clipping, textures and text through Aegis to prove the pipeline end
    // to end. Deleted once real panels exist; see docs/UI_FRAMEWORK_ARCHITECTURE.md.
    // -------------------------------------------------------------------------------------
    private com.aengine.aegis.Aegis aegis;

    /** Scene FBO colour attachment, captured so the scaffold can present it as a thumbnail. */
    private int sceneTextureID = 0;

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
    private int pendingDeleteEntity = -1;
    private int lastWindowWidth = -1;
    private int lastWindowHeight = -1;


    // Allocation-free temporary structural containers for 3D physical environment alignment
    private static final Vector3f GROUND_POSITION = new Vector3f(0.0f, -1.5f, 0.0f); 
    private static final Vector3f GROUND_SIZE = new Vector3f(1024.0f, 1.0f, 1024.0f);
    private static final Vector4f GROUND_COLOR = new Vector4f(0.50f, 0.50f, 0.50f, 1.0f); // Light Gray Floor

    // Shared execution state capturing target path sent from external process host
    private static String activeProjectPath;

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

        // SCAFFOLDING — see the field declaration.
        // One quad per visible character, so the scaffolding's six lines of text dominate
        // the budget; 256 truncated them silently.
        aegis = new com.aengine.aegis.Aegis(512);
        // 18px Latin-1 is 224 glyphs against ASCII's 95: it needs 86 rows, so the previous
        // 64 no longer fits and baking would refuse. 128 leaves room for a larger size later.
        aegis.loadFont("/fonts/DejaVuSans/DejaVuSans.ttf", 18.0f, 512, 128);
        imguiLayer.setAfterImGui(this::drawUiFirstLight);
        
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

    @Override
    protected void onUpdate(float deltaTime) {
        FPSTracker.update(deltaTime);    // Optional: Enable FPS tracking for the editor stats panel. Comment it but DO NOT REMOVE
        if (Input.isKeyPressed(Keys.ESCAPE)) {
            stop();
        }
        
        int currentW = (int) com.aengine.debug.DebugOverlay.getViewportImageW();
        int currentH = (int) com.aengine.debug.DebugOverlay.getViewportImageH();
        
        boolean boundsChanged = (currentW > 0 && currentH > 0 && (currentW != lastWindowWidth || currentH != lastWindowHeight));
        boolean fovChanged = false;

        // Captura o Scroll do rato para alterar o FOV
        if (RenderMode.active() == RenderMode.MODE_3D) {
            float scroll = imgui.ImGui.getIO().getMouseWheel();
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

        // --- STATE MACHINE TRANSITION (F5) ---
        boolean isF5Pressed = Input.isKeyPressed(Keys.F5);
        if (isF5Pressed && !wasF5Pressed) {
            if (currentState == EngineState.EDITOR) {
                Logger.info(Logger.System.CORE, "Entering PLAY Mode. Snapshotting ECS to RAM...");
                currentState = EngineState.PLAY;
                EditorState.deselect(); // Clear inspector to prevent editing during simulation
                // Serialize straight to RAM, bypassing disk IO
                sceneMemoryBackup = SceneSerializer.serializeScene(registry, "RAM_BACKUP");
                physicsThread.setPaused(false);
            } else {
                Logger.info(Logger.System.CORE, "Entering EDITOR Mode. Restoring ECS from RAM...");
                currentState = EngineState.EDITOR;
                physicsThread.setPaused(true);
                // Wipe dynamic entities cleanly
                synchronized (physicsThread.getSyncLock()) {
                    registry.clearScene();
                    SceneLoader.load(registry, sceneMemoryBackup);
                }
            }
        }
        wasF5Pressed = isF5Pressed;

        // Cap deltaTime before any system update to prevent spiral-of-death if the main
        // thread falls behind (e.g., during asset loading or OS scheduling spikes).
        if (deltaTime > 0.25f) deltaTime = 0.25f;

        // Guard all registry access with the physics syncLock.
        synchronized (physicsThread.getSyncLock()) {
            
            if (currentState == EngineState.PLAY) {
                audioSystem.update(registry, deltaTime);
                scriptSystem.update(registry, deltaTime);
            }
            // Camera operates freely in both modes
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
        physicsThread.cleanup();
        Renderer3D.cleanup();
        Renderer2D.cleanup();
        AudioDevice.cleanup();

        if (aegis != null) aegis.cleanup();
    }

    /**
     * SCAFFOLDING — first light for the in-house UI framework.
     *
     * <p>Submits one rounded rectangle through {@code :ui} and draws it over the ImGui
     * surface. Its only job is to prove the SDF pipeline works end to end: draw list to
     * dynamic mesh to shader to screen. Deleted once the framework draws real panels.</p>
     */
    private void drawUiFirstLight() {
        int w = getWindow().getWidth();
        int h = getWindow().getHeight();

        aegis.begin(w, h);

        // Panel-like slab with a generous radius, plus a small square to show the radius is
        // a parameter and not a baked mesh.
        aegis.addRoundedRect(40.0f, 80.0f, 320.0f, 180.0f, 18.0f,
            0.12f, 0.14f, 0.18f, 0.92f,          // fill
            0.38f, 0.42f, 0.52f, 1.0f, 1.0f);    // 1px border, the panel-edge case

        // Everything between push and pop is cut to this rectangle, which stops halfway
        // down the two shapes below. Their bottom halves are submitted and discarded by the
        // hardware — the same mechanism a scrolling list relies on.
        aegis.pushClipRect(40.0f, 80.0f, 320.0f, 90.0f);
        aegis.addRoundedRect(72.0f, 112.0f, 96.0f, 96.0f,  48.0f, 0.36f, 0.62f, 0.94f, 1.0f);
        aegis.addRoundedRect(200.0f, 112.0f, 120.0f, 40.0f, 8.0f, 0.94f, 0.55f, 0.28f, 1.0f);
        aegis.popClipRect();

        // Outside the clip again: a marker that must stay whole.
        aegis.addRoundedRect(40.0f, 280.0f, 60.0f, 24.0f, 12.0f, 0.45f, 0.85f, 0.50f, 1.0f);

        // Thick border on a fully rounded shape — the focus-ring case. The border follows
        // the curve because it is the distance field, not a smaller shape behind it.
        aegis.addRoundedRect(120.0f, 274.0f, 80.0f, 36.0f, 18.0f,
            0.10f, 0.12f, 0.16f, 1.0f,
            0.95f, 0.75f, 0.20f, 1.0f, 4.0f);

        // Border with no fill: alpha 0 on the fill leaves the outline alone.
        aegis.addRoundedRect(220.0f, 274.0f, 80.0f, 36.0f, 8.0f,
            0.0f, 0.0f, 0.0f, 0.0f,
            0.90f, 0.35f, 0.45f, 1.0f, 2.0f);

        // Textured quad: the scene's own framebuffer, presented as a thumbnail through our
        // pipeline. v is flipped because a GL colour attachment has its origin bottom-left.
        if (sceneTextureID != 0) {
            aegis.addTexturedQuad(
                40.0f, 330.0f, 240.0f, 135.0f,
                0.0f, 1.0f, 1.0f, 0.0f,
                sceneTextureID,
                1.0f, 1.0f, 1.0f, 1.0f);

            // Same texture, tinted and clipped — proves the tint multiplies the sample and
            // that clipping applies to textured commands too.
            aegis.pushClipRect(300.0f, 330.0f, 120.0f, 135.0f);
            aegis.addTexturedQuad(
                300.0f, 330.0f, 240.0f, 135.0f,
                0.0f, 1.0f, 1.0f, 0.0f,
                sceneTextureID,
                0.45f, 0.75f, 1.0f, 1.0f);
            aegis.popClipRect();
        }

        // The text block is anchored to the window rather than placed at fixed coordinates,
        // so it stays on screen at any window shape — 4:3, 1:1 and ultrawide alike. Pixels
        // are pixels on both axes (the shader normalises each by its own viewport extent),
        // so only the window's size matters here, never its proportions. Clamped so a narrow
        // window pushes the block left instead of off the right edge.
        final float textX = Math.max(380.0f, w - 580.0f);
        float       textY = 92.0f;

        // Text is positioned by the top of its line, not by the baseline: line height and
        // ascent come from the font file, so stacking is the same addition for every font.
        final int line = aegis.lineHeight();

        aegis.addTextTop(textX, textY,
            "AEngine - first text through the Aegis UI",
            1.0f, 1.0f, 1.0f, 1.0f);
        textY += line;

        // Same atlas, another colour: the atlas holds coverage, the vertex holds colour.
        aegis.addTextTop(textX, textY,
            "The quick brown fox jumps over the lazy dog 0123456789",
            0.45f, 0.70f, 1.0f, 1.0f);
        textY += line;

        // Step 3b-2, the visible check: two lines exactly one line height apart. Each line
        // gets a slab the height of its own line box, in two shades so the seam between them
        // is visible. The slabs touch without overlapping and the ink stays inside its own
        // slab, which is what proves the spacing comes from the font rather than from a
        // guess. 'Ç' reaches the top of the box and 'gjpq' the bottom, so ascent and descent
        // are both exercised instead of only the x-height.
        //
        // Written out twice rather than looped: the pair of calls per line is the pattern
        // every widget will use, and a loop hides it.
        final float slabX     = textX - 6.0f;
        final float slabWidth = 420.0f;

        final float firstLineTop = textY;
        aegis.addRoundedRect(slabX, firstLineTop, slabWidth, line, 0.0f,
            0.16f, 0.18f, 0.23f, 1.0f);
        aegis.addTextTop(textX, firstLineTop,
            "Ça va? Hanging gjpq, rising ÀÉÎÕÜ - line 1",
            0.90f, 0.92f, 0.95f, 1.0f);

        // One line height below the first, and nothing else: no padding, no fudge factor.
        final float secondLineTop = firstLineTop + line;
        aegis.addRoundedRect(slabX, secondLineTop, slabWidth, line, 0.0f,
            0.10f, 0.12f, 0.16f, 1.0f);
        aegis.addTextTop(textX, secondLineTop,
            "Ça va? Hanging gjpq, rising ÀÉÎÕÜ - line 2",
            0.90f, 0.92f, 0.95f, 1.0f);

        textY = secondLineTop + line + 12.0f;

        // Text composed over a shape, the way every widget will draw. The label is centred in
        // the button by its line box rather than by an eyeballed baseline offset.
        final float buttonHeight = 36.0f;
        aegis.addRoundedRect(textX, textY, 220.0f, buttonHeight, 8.0f,
            0.16f, 0.18f, 0.23f, 1.0f,
            0.38f, 0.42f, 0.52f, 1.0f, 1.0f);
        aegis.addTextTop(textX + 14.0f, textY + (buttonHeight - line) * 0.5f,
            "Button label", 0.90f, 0.92f, 0.95f, 1.0f);
        textY += buttonHeight + 12.0f;

        // Step 3b-1: the Latin-1 supplement is baked, so these read as written instead of
        // as '?'. The last line is outside Latin-1 and still falls back, which is the
        // boundary being checked rather than a defect.
        aegis.addTextTop(textX, textY, "Olá, ação! Português, español, français",
            0.95f, 0.60f, 0.30f, 1.0f);
        textY += line;
        aegis.addTextTop(textX, textY, "Grüße, Ångström, ¿cómo?, ½ £ © ÷ ×",
            0.95f, 0.60f, 0.30f, 1.0f);
        textY += line;
        aegis.addTextTop(textX, textY, "Beyond Latin-1: Привет 日本語 -> ?",
            0.60f, 0.62f, 0.68f, 1.0f);

        aegis.end();   // closes the draw list and presents it
    }

    @Override
    protected void onDebugRender(int viewportTextureID) {
        sceneTextureID = viewportTextureID; // SCAFFOLDING — see drawUiFirstLight

        // Render scene FBO as the main Viewport panel (includes right-click context menu)
        super.onDebugRender(viewportTextureID);

        if (!DebugOverlay.ENABLED) return;

        // ── Entity Picking ────────────────────────────────────────────────────
        // Left-click inside the Viewport image → unproject to world space and
        // hit-test every entity's AABB. Only meaningful in 2D orthographic mode.
        if (DebugOverlay.wasViewportClicked()) {
            handleViewportPick(DebugOverlay.getViewportClickNdcX(),
                               DebugOverlay.getViewportClickNdcY());
        }

        if (isDraggingEntity && EditorState.hasSelection()) {
            if (ImGui.isMouseDown(0)) { // 0 = Left Mouse Button pressed
                Camera camera = getActiveCamera();
                if (camera != null) {
                    float dxPixels = ImGui.getIO().getMouseDeltaX();
                    float dyPixels = ImGui.getIO().getMouseDeltaY();

                    if (dxPixels != 0.0f || dyPixels != 0.0f) {
                        
                        // Extract the exact resolution of the active render panel
                        float vpW = com.aengine.debug.DebugOverlay.getViewportImageW();
                        float vpH = com.aengine.debug.DebugOverlay.getViewportImageH();

                        // Convert pixel delta to NDC delta using the correct proportions
                        float ndcDx =  2.0f * (dxPixels / vpW);
                        float ndcDy = -2.0f * (dyPixels / vpH); // Y inverted

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
                }
            } else {
                isDraggingEntity = false; // Mouse released, end the drag
            }
        }

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

    public static RenderMode getActiveRenderMode() { return RenderMode.active(); }

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
