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

    // Colours as one named value each, rather than four floats spelled out at the call site.
    // A grouped colour is what the theme file will replace; a loose quartet is what has to be
    // hunted down first. New scaffolding colours go here from now on.
    private static final float[] KERN_LOOSE = { 0.70f, 0.72f, 0.78f, 1.0f };
    private static final float[] KERN_TIGHT = { 0.95f, 0.85f, 0.45f, 1.0f };
    private static final float[] LAYOUT_ROW_FILL   = { 0.12f, 0.14f, 0.18f, 0.92f };
    private static final float[] LAYOUT_ROW_BORDER = { 0.38f, 0.42f, 0.52f, 1.0f };
    private static final float[] LAYOUT_BOX        = { 0.36f, 0.62f, 0.94f, 1.0f };
    private static final float[] LAYOUT_CAPTION    = { 0.60f, 0.62f, 0.68f, 1.0f };
    private static final float[] LAYOUT_BOX_TEXT   = { 0.06f, 0.08f, 0.12f, 1.0f };
    private static final float[] LAYOUT_FRAME_FILL = { 0.07f, 0.08f, 0.11f, 0.94f };
    private static final float[] HIT_OUTLINE       = { 0.98f, 0.80f, 0.25f, 1.0f };
    private static final float[] BOX_HOVER         = { 0.55f, 0.76f, 1.00f, 1.0f };
    private static final float[] BOX_PRESSED       = { 0.22f, 0.44f, 0.76f, 1.0f };
    private static final float[] FOCUS_RING        = { 0.95f, 0.97f, 1.00f, 1.0f };

    // SCAFFOLDING — steps 3c-1 to 3c-3: an outline of the editor's frame. A column holding a
    // toolbar row (four boxes, two fixed and two growing) above a body row of three panes.
    // Handles into aegis.layout(), built once in buildLayoutScaffolding() and solved every
    // frame in drawUiFirstLight().
    private int layoutFrame;
    private int layoutRow;          // the toolbar
    private int layoutBoxA;
    private int layoutBoxB;
    private int layoutBoxC;
    private int layoutBoxD;
    private int layoutBody;
    private int layoutHierarchy;
    private int layoutTreeItemA;
    private int layoutTreeItemB;
    private int layoutViewport;
    private int layoutCentred;
    private int layoutInspector;
    private int layoutFieldA;
    private int layoutFieldB;

    // SCAFFOLDING — step 3d-2: clicks each interactive box has received, drawn inside it.
    private int clicksA;
    private int clicksB;
    private int clicksC;
    private int clicksD;
    private int clicksCentred;

    // SCAFFOLDING — step 3d-3: whether Tab was held last frame, so a press is seen once.
    private boolean tabWasDown;

    // SCAFFOLDING — step 3e-1: the input event queue made visible. What has been typed (the
    // last TYPED_KEEP characters), and a count of each kind of key event and of the wheel.
    private static final int           TYPED_KEEP = 48;
    private static final StringBuilder TYPED      = new StringBuilder(TYPED_KEEP + 4);
    private int   keyPresses;
    private int   keyRepeats;
    private int   keyReleases;
    private float wheelTotal;

    // SCAFFOLDING — somewhere to build a line of text with a number in it, reused every
    // frame. §9 forbids String.format and concatenation in the frame loop, and addText takes
    // a CharSequence precisely so a StringBuilder can be handed straight to it.
    private static final StringBuilder UI_TEXT = new StringBuilder(96);

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
        // One quad per visible character, so text dominates the budget and a paragraph
        // dominates the text: the wrapped paragraph alone is ~320 quads against ~330 for
        // every other line put together. Both 256 and 512 truncated it in silence.
        aegis = new com.aengine.aegis.Aegis(2048);
        // 18px Latin-1 is 224 glyphs against ASCII's 95: it needs 86 rows, so the previous
        // 64 no longer fits and baking would refuse. 128 leaves room for a larger size later.
        aegis.loadFont("/fonts/DejaVuSans/DejaVuSans.ttf", 18.0f, 512, 128);
        buildLayoutScaffolding();
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

        // Step 3b-3, the visible check: the same string twice, kerning off then on. The pairs
        // AV, To, Ta, Wa, Yo, LT, P. and r. are the ones DejaVu ships corrections for, so
        // they close up on the second line while the rest stays put.
        //
        // addTextTop returns the pen position after the last character, so a thin rule drawn
        // at each line's end turns the total tightening into a measurable gap instead of
        // something to squint at.
        final String kernSample = "AV To Ta Wa Yo LT P. r. AW VA";

        aegis.font().setKerningEnabled(false);
        float looseEnd = aegis.addTextTop(textX, textY, kernSample + "   kerning OFF",
            KERN_LOOSE[0], KERN_LOOSE[1], KERN_LOOSE[2], KERN_LOOSE[3]);
        drawKernMark(textX,    textY, line, KERN_LOOSE);
        drawKernMark(looseEnd, textY, line, KERN_LOOSE);
        textY += line;

        aegis.font().setKerningEnabled(true);
        float kernedEnd = aegis.addTextTop(textX, textY, kernSample + "   kerning ON",
            KERN_TIGHT[0], KERN_TIGHT[1], KERN_TIGHT[2], KERN_TIGHT[3]);
        drawKernMark(textX,     textY, line, KERN_TIGHT);
        drawKernMark(kernedEnd, textY, line, KERN_TIGHT);
        textY += line + 12.0f;

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

        // Step 3e-2: the punctuation pasted text brings, packed as a second atlas range. Every
        // one of these drew as '?' before — dashes, curly quotes, ellipsis, bullet, euro, ™.
        aegis.addTextTop(textX, textY, "Pasted: “quoted” ‘single’ it’s – en — em … • 9,99 € ™",
            0.95f, 0.60f, 0.30f, 1.0f);
        textY += line;

        aegis.addTextTop(textX, textY, "Beyond Latin-1: Привет 日本語 -> ?",
            0.60f, 0.62f, 0.68f, 1.0f);
        textY += line + 16.0f;

        // Step 3b-3 again, from the other side: measure() has to agree with what addText
        // draws, or a layout would centre things by a width that is not the width. Drawing a
        // rule at the measured end of a line proves it lands on the last glyph.
        final String measured = "measure() agrees with addText()";
        float measuredWidth = aegis.measure(measured);
        aegis.addTextTop(textX, textY, measured, KERN_TIGHT[0], KERN_TIGHT[1], KERN_TIGHT[2], KERN_TIGHT[3]);
        drawKernMark(textX + measuredWidth, textY, line, KERN_TIGHT);
        textY += line + 16.0f;

        // Step 3b-4, the visible check: a paragraph wrapping inside a panel. The panel's
        // width follows the window, so resizing re-wraps the text live — the point being that
        // wrapping is computed from a width rather than baked into the string.
        //
        // The paragraph carries the two cases worth seeing fail: an explicit newline, which
        // must break where it says, and a word far longer than the panel, which has nowhere
        // to break and so breaks mid-word rather than overflowing the panel or hanging.
        final float paraPadding = 10.0f;
        final float paraWidth   = Math.max(220.0f, Math.min(440.0f, w * 0.18f));
        final String paragraph =
            "Wrapping breaks a paragraph at spaces to fit a width, and honours a newline\n"
            + "where one is written. A word with no space in it and no room to fit, such as "
            + "esternocleidomastoideopneumoultramicroscopicossilicovulcanoconiotico, has "
            + "nowhere to break and is cut mid-word, because overflowing the panel and "
            + "looping forever are the only alternatives.";

        // The panel is sized from the wrap, not guessed. Asking the height goes through the
        // layout cache, so sizing the box and then drawing the text into it costs one wrap
        // between them rather than two — which is the whole point of step 3b-5.
        float paraHeight = aegis.wrappedHeight(paragraph, paraWidth) + paraPadding * 2.0f;

        aegis.addRoundedRect(textX, textY, paraWidth + paraPadding * 2.0f, paraHeight, 8.0f,
            0.12f, 0.14f, 0.18f, 0.94f,
            0.38f, 0.42f, 0.52f, 1.0f, 1.0f);
        aegis.addTextWrapped(textX + paraPadding, textY + paraPadding, paraWidth, paragraph,
            0.85f, 0.88f, 0.92f, 1.0f);
        textY += paraHeight + 8.0f;

        // Step 3b-5, the only visible evidence there is: a working layout cache changes
        // nothing on screen, so the thing to watch is a number that STOPS moving. Recomputes
        // climb for the first frame and then hold still while the text and width do; resize
        // the window and they tick up once more, because the width is part of the key.
        int recomputes = aegis.layoutRecomputes();
        UI_TEXT.setLength(0);
        UI_TEXT.append("layout cache - recomputed: ").append(recomputes)
               .append("   from cache: ").append(aegis.layoutHits());
        aegis.addTextTop(textX, textY, UI_TEXT, 0.55f, 0.75f, 0.55f, 1.0f);

        // Steps 3c-1 to 3c-3, the visible check: an outline of the editor's frame along the
        // bottom of the window — a toolbar above three panes. The only size written here is the
        // frame's own, handed to solve(); every rectangle inside it is worked out from the tree
        // built in buildLayoutScaffolding(), and drawing only reads them back. Resize the window
        // and the toolbar's B and C, the viewport pane and the inspector's fields all follow.
        com.aengine.aegis.AegisLayout layout = aegis.layout();

        final float frameHeight = 300.0f;
        final float frameWidth  = w - 80.0f;   // the window's width, less 40 each side
        final float frameTop    = h - frameHeight - 24.0f;

        aegis.addTextTop(40.0f, frameTop - line - 6.0f,
            "3c-3: frame = column [ toolbar row, body row [ hierarchy X START, viewport X+Y CENTER, inspector X STRETCH ] ]",
            LAYOUT_CAPTION[0], LAYOUT_CAPTION[1], LAYOUT_CAPTION[2], LAYOUT_CAPTION[3]);

        // One solve for the whole tree: the frame, and everything nested in it.
        layout.solve(layoutFrame, 40.0f, frameTop, frameWidth, frameHeight);

        // Step 3d-2: the pointer, handed to the tree right after the solve so it tests against
        // what this frame draws, and before drawing so each box can show its state. The tree
        // does not read input itself — the editor decides what it sees. Over a Dear ImGui
        // window the pointer belongs to ImGui, so the tree is given a point outside the frame.
        com.aengine.aegis.AegisTree tree = aegis.tree();

        final boolean imguiHasMouse = ImGui.getIO().getWantCaptureMouse();
        final float   mouseX        = imguiHasMouse ? -1.0f : (float) Input.getMouseX();
        final float   mouseY        = imguiHasMouse ? -1.0f : (float) Input.getMouseY();
        final boolean leftDown      = Input.isMouseButtonPressed(0);   // 0 = left button

        tree.update(layoutFrame, mouseX, mouseY, leftDown);

        // A click is true for exactly one frame, so counting it here counts each click once.
        if (tree.wasClicked(layoutBoxA))    clicksA++;
        if (tree.wasClicked(layoutBoxB))    clicksB++;
        if (tree.wasClicked(layoutBoxC))    clicksC++;
        if (tree.wasClicked(layoutBoxD))    clicksD++;
        if (tree.wasClicked(layoutCentred)) clicksCentred++;

        // Step 3d-3: Tab and Shift+Tab move focus. The key is polled, so "pressed" is found by
        // comparing with last frame — enough for Tab, though holding it does not repeat; key
        // repeat needs the input event queue that comes before the text field in 3e. While
        // ImGui is taking keyboard input (one of its fields is active), Tab is left to it.
        final boolean imguiHasKeyboard = ImGui.getIO().getWantCaptureKeyboard();
        final boolean tabDown   = !imguiHasKeyboard && Input.isKeyPressed(Keys.TAB);
        final boolean shiftDown = Input.isKeyPressed(Keys.SHIFT_L) || Input.isKeyPressed(Keys.SHIFT_R);

        if (tabDown && !tabWasDown) {
            if (shiftDown) tree.focusPrevious(layoutFrame);
            else           tree.focusNext(layoutFrame);
        }
        tabWasDown = tabDown;

        // Step 3e-1: this frame's input events, in the order they happened. Characters are
        // appended as typed — including 'ç' or 'ã' composed from a dead key, which arrive as
        // one character though they took two key presses. Backspace removes the last one, on
        // its press and on every repeat, so holding it shows the operating system's repeat at
        // work. Left to ImGui while it has the keyboard, like Tab.
        if (!imguiHasKeyboard) {
            for (int i = 0; i < Input.eventCount(); i++) {
                switch (Input.eventType(i)) {
                    case CHAR -> {
                        TYPED.appendCodePoint(Input.eventCode(i));
                        if (TYPED.length() > TYPED_KEEP) TYPED.delete(0, TYPED.length() - TYPED_KEEP);
                    }
                    case KEY_PRESS -> {
                        keyPresses++;
                        if (Input.eventCode(i) == Keys.BACKSPACE && TYPED.length() > 0) TYPED.setLength(TYPED.length() - 1);
                    }
                    case KEY_REPEAT -> {
                        keyRepeats++;
                        if (Input.eventCode(i) == Keys.BACKSPACE && TYPED.length() > 0) TYPED.setLength(TYPED.length() - 1);
                    }
                    case KEY_RELEASE -> keyReleases++;
                    case SCROLL      -> wheelTotal += Input.eventScrollY(i);
                    default          -> { }   // mouse buttons: the tree already handles them
                }
            }
        }

        // Step 3c-4, the visible check, beside the text cache's counter and read the same way:
        // "solved" is 1 after the first frame and then STOPS moving, because neither the tree
        // nor the frame's rectangle changes; "skipped" climbs once a frame instead. Resizing the
        // window changes the rectangle, so "solved" ticks up while it happens and stops again.
        UI_TEXT.setLength(0);
        UI_TEXT.append("layout solve - solved: ").append(layout.solves())
               .append("   skipped: ").append(layout.solveSkips());
        aegis.addTextTop(textX, textY + line, UI_TEXT, 0.55f, 0.75f, 0.55f, 1.0f);

        // The frame behind everything else.
        aegis.addRoundedRect(layout.x(layoutFrame), layout.y(layoutFrame),
            layout.width(layoutFrame), layout.height(layoutFrame), 10.0f,
            LAYOUT_FRAME_FILL[0], LAYOUT_FRAME_FILL[1], LAYOUT_FRAME_FILL[2], LAYOUT_FRAME_FILL[3]);

        // The toolbar, drawn from its solved rectangle like everything else.
        aegis.addRoundedRect(layout.x(layoutRow), layout.y(layoutRow),
            layout.width(layoutRow), layout.height(layoutRow), 8.0f,
            LAYOUT_ROW_FILL[0], LAYOUT_ROW_FILL[1], LAYOUT_ROW_FILL[2], LAYOUT_ROW_FILL[3],
            LAYOUT_ROW_BORDER[0], LAYOUT_ROW_BORDER[1], LAYOUT_ROW_BORDER[2], LAYOUT_ROW_BORDER[3],
            1.0f);

        // One call per box, written out: read the rectangle the solve produced, draw there, in
        // the colour its state calls for — see boxColour(). Each box reports its clicks.
        float[] colourA = boxColour(tree, layoutBoxA);
        aegis.addRoundedRect(layout.x(layoutBoxA), layout.y(layoutBoxA),
            layout.width(layoutBoxA), layout.height(layoutBoxA), 6.0f,
            colourA[0], colourA[1], colourA[2], colourA[3]);

        float[] colourB = boxColour(tree, layoutBoxB);
        aegis.addRoundedRect(layout.x(layoutBoxB), layout.y(layoutBoxB),
            layout.width(layoutBoxB), layout.height(layoutBoxB), 6.0f,
            colourB[0], colourB[1], colourB[2], colourB[3]);

        float[] colourC = boxColour(tree, layoutBoxC);
        aegis.addRoundedRect(layout.x(layoutBoxC), layout.y(layoutBoxC),
            layout.width(layoutBoxC), layout.height(layoutBoxC), 6.0f,
            colourC[0], colourC[1], colourC[2], colourC[3]);

        float[] colourD = boxColour(tree, layoutBoxD);
        aegis.addRoundedRect(layout.x(layoutBoxD), layout.y(layoutBoxD),
            layout.width(layoutBoxD), layout.height(layoutBoxD), 6.0f,
            colourD[0], colourD[1], colourD[2], colourD[3]);

        drawClickLabel(layout, layoutBoxA, "A", clicksA);
        drawClickLabel(layout, layoutBoxB, "B", clicksB);
        drawClickLabel(layout, layoutBoxC, "C", clicksC);
        drawClickLabel(layout, layoutBoxD, "D", clicksD);

        // The three panes of the body. Each is the same call: its rectangle, as solved.
        aegis.addRoundedRect(layout.x(layoutHierarchy), layout.y(layoutHierarchy),
            layout.width(layoutHierarchy), layout.height(layoutHierarchy), 8.0f,
            LAYOUT_ROW_FILL[0], LAYOUT_ROW_FILL[1], LAYOUT_ROW_FILL[2], LAYOUT_ROW_FILL[3],
            LAYOUT_ROW_BORDER[0], LAYOUT_ROW_BORDER[1], LAYOUT_ROW_BORDER[2], LAYOUT_ROW_BORDER[3],
            1.0f);

        aegis.addRoundedRect(layout.x(layoutViewport), layout.y(layoutViewport),
            layout.width(layoutViewport), layout.height(layoutViewport), 8.0f,
            LAYOUT_ROW_FILL[0], LAYOUT_ROW_FILL[1], LAYOUT_ROW_FILL[2], LAYOUT_ROW_FILL[3],
            LAYOUT_ROW_BORDER[0], LAYOUT_ROW_BORDER[1], LAYOUT_ROW_BORDER[2], LAYOUT_ROW_BORDER[3],
            1.0f);

        aegis.addRoundedRect(layout.x(layoutInspector), layout.y(layoutInspector),
            layout.width(layoutInspector), layout.height(layoutInspector), 8.0f,
            LAYOUT_ROW_FILL[0], LAYOUT_ROW_FILL[1], LAYOUT_ROW_FILL[2], LAYOUT_ROW_FILL[3],
            LAYOUT_ROW_BORDER[0], LAYOUT_ROW_BORDER[1], LAYOUT_ROW_BORDER[2], LAYOUT_ROW_BORDER[3],
            1.0f);

        // Hierarchy items: START, so each keeps the width it asked for, against the left edge.
        aegis.addRoundedRect(layout.x(layoutTreeItemA), layout.y(layoutTreeItemA),
            layout.width(layoutTreeItemA), layout.height(layoutTreeItemA), 4.0f,
            LAYOUT_BOX[0], LAYOUT_BOX[1], LAYOUT_BOX[2], LAYOUT_BOX[3]);
        aegis.addTextTop(layout.x(layoutTreeItemA) + 8.0f, layout.y(layoutTreeItemA) + 4.0f,
            "START 150", LAYOUT_BOX_TEXT[0], LAYOUT_BOX_TEXT[1], LAYOUT_BOX_TEXT[2], LAYOUT_BOX_TEXT[3]);

        aegis.addRoundedRect(layout.x(layoutTreeItemB), layout.y(layoutTreeItemB),
            layout.width(layoutTreeItemB), layout.height(layoutTreeItemB), 4.0f,
            LAYOUT_BOX[0], LAYOUT_BOX[1], LAYOUT_BOX[2], LAYOUT_BOX[3]);
        aegis.addTextTop(layout.x(layoutTreeItemB) + 8.0f, layout.y(layoutTreeItemB) + 4.0f,
            "START 110", LAYOUT_BOX_TEXT[0], LAYOUT_BOX_TEXT[1], LAYOUT_BOX_TEXT[2], LAYOUT_BOX_TEXT[3]);

        // Viewport's box: centred on X and on Y by the pane's two alignments, and interactive
        // like the toolbar's.
        float[] colourCentred = boxColour(tree, layoutCentred);
        aegis.addRoundedRect(layout.x(layoutCentred), layout.y(layoutCentred),
            layout.width(layoutCentred), layout.height(layoutCentred), 6.0f,
            colourCentred[0], colourCentred[1], colourCentred[2], colourCentred[3]);
        drawClickLabel(layout, layoutCentred, "CENTER", clicksCentred);

        // Inspector fields: STRETCH, so each is as wide as the pane minus its padding.
        aegis.addRoundedRect(layout.x(layoutFieldA), layout.y(layoutFieldA),
            layout.width(layoutFieldA), layout.height(layoutFieldA), 4.0f,
            LAYOUT_BOX[0], LAYOUT_BOX[1], LAYOUT_BOX[2], LAYOUT_BOX[3]);
        drawWidthLabel(layout, layoutFieldA, "STRETCH");

        aegis.addRoundedRect(layout.x(layoutFieldB), layout.y(layoutFieldB),
            layout.width(layoutFieldB), layout.height(layoutFieldB), 4.0f,
            LAYOUT_BOX[0], LAYOUT_BOX[1], LAYOUT_BOX[2], LAYOUT_BOX[3]);
        drawWidthLabel(layout, layoutFieldB, "STRETCH");

        // Step 3d-3, the visible check: a ring around the focused node, standing 3 pixels off
        // it so it reads as a ring rather than a border. One call, whichever node has focus.
        int focusedNode = tree.focused();
        if (focusedNode != com.aengine.aegis.AegisLayout.NONE) {
            aegis.addRoundedRect(layout.x(focusedNode) - 3.0f, layout.y(focusedNode) - 3.0f,
                layout.width(focusedNode) + 6.0f, layout.height(focusedNode) + 6.0f, 8.0f,
                0.0f, 0.0f, 0.0f, 0.0f,                               // no fill: ring only
                FOCUS_RING[0], FOCUS_RING[1], FOCUS_RING[2], FOCUS_RING[3],
                2.0f);
        }

        // Step 3d-1, the visible check: whichever node is under the mouse gets an outline.
        // nodeAt() answers with the innermost one, so moving from the frame's edge into a pane
        // and then onto a box inside it hands the outline inward, one level at a time. Asked
        // after this frame's solve, so it answers for exactly what is on screen. Drawn last, so
        // it sits on top of everything it outlines. It marks every node, interactive or not,
        // which is the difference between the raw hit-test and the tree's hover above.
        int underMouse = layout.nodeAt(layoutFrame, mouseX, mouseY);

        if (underMouse != com.aengine.aegis.AegisLayout.NONE) {
            aegis.addRoundedRect(layout.x(underMouse), layout.y(underMouse),
                layout.width(underMouse), layout.height(underMouse), 6.0f,
                0.0f, 0.0f, 0.0f, 0.0f,                               // no fill: outline only
                HIT_OUTLINE[0], HIT_OUTLINE[1], HIT_OUTLINE[2], HIT_OUTLINE[3],
                2.0f);
        }

        // The handle the hit-test returned, beside the other counters: NONE (-1) outside the
        // frame, and a different number for every node the mouse crosses.
        UI_TEXT.setLength(0);
        UI_TEXT.append("hit-test - node under mouse: ").append(underMouse);
        aegis.addTextTop(textX, textY + line * 2, UI_TEXT, 0.55f, 0.75f, 0.55f, 1.0f);

        // Step 3d-2: the tree's answer, which only ever names an interactive node — the five
        // boxes (2, 3, 4, 5 and 11) — or -1. Over a pane, or over ImGui, it stays -1 while the
        // hit-test line above still names what is there.
        UI_TEXT.setLength(0);
        UI_TEXT.append("tree - hovered: ").append(tree.hovered());
        aegis.addTextTop(textX, textY + line * 3, UI_TEXT, 0.55f, 0.75f, 0.55f, 1.0f);

        // Step 3d-3: the focused handle — Tab walks 2 3 4 5 8 9 11 13 14 and wraps, Shift+Tab
        // walks it backwards, and a press on a pane clears it to -1.
        UI_TEXT.setLength(0);
        UI_TEXT.append("tree - focused: ").append(tree.focused());
        aegis.addTextTop(textX, textY + line * 4, UI_TEXT, 0.55f, 0.75f, 0.55f, 1.0f);

        // Step 3e-1, the visible check: what has been typed, with a bar where the next
        // character goes, and the event counts. "repeated" only climbs while a key is held.
        UI_TEXT.setLength(0);
        UI_TEXT.append("typed: ").append(TYPED).append('|');
        aegis.addTextTop(textX, textY + line * 5, UI_TEXT, 0.90f, 0.92f, 0.95f, 1.0f);

        UI_TEXT.setLength(0);
        UI_TEXT.append("keys - pressed ").append(keyPresses)
               .append("  repeated ").append(keyRepeats)
               .append("  released ").append(keyReleases)
               .append("   wheel ").append((int) wheelTotal)
               .append("   dropped ").append(Input.droppedEvents());
        aegis.addTextTop(textX, textY + line * 6, UI_TEXT, 0.55f, 0.75f, 0.55f, 1.0f);

        aegis.end();   // closes the draw list and presents it
    }

    /**
     * SCAFFOLDING — a thin vertical rule marking where a line of text starts and ends, so the
     * kerning comparison shows a measurable gap rather than something to squint at. Local to
     * that test; nothing in the framework needs it.
     */
    private void drawKernMark(float x, float top, int height, float[] rgba) {
        aegis.addRoundedRect(x, top, 2.0f, height, 0.0f, rgba[0], rgba[1], rgba[2], rgba[3]);
    }

    /**
     * SCAFFOLDING — steps 3c-1 to 3c-3: builds the layout tree the frame will solve.
     *
     * <p>Runs once, at init. Building says what contains what and how big each thing asks to
     * be; it does not say where anything goes — that is the solve's job, every frame.</p>
     *
     * <p>The tree, from the outside in:</p>
     * <pre>
     * frame      column, X STRETCH
     * ├─ toolbar row, 84 tall        A fixed · B grow 1 · C grow 2 · D fixed
     * └─ body    row, grow 1, Y STRETCH
     *    ├─ hierarchy  column, 240 wide, X START               two items of their own width
     *    ├─ viewport   column, grow 1, X CENTER, Y CENTER      one box, in the middle
     *    └─ inspector  column, 300 wide, X STRETCH             two fields as wide as the pane
     * </pre>
     *
     * <p>Nobody in this tree is told the window's size. The frame receives it from solve(),
     * and every pane's width and height follows from grow and stretch — resize the window
     * and all of it moves.</p>
     */
    private void buildLayoutScaffolding() {
        com.aengine.aegis.AegisLayout layout = aegis.layout();

        // The root: a column with no parent. It takes whatever rectangle solve() hands it, and
        // STRETCH on X makes both of its rows as wide as it is, so neither asks for a width.
        layoutFrame = layout.column(com.aengine.aegis.AegisLayout.NONE);
        layout.setPadding(layoutFrame, 8.0f);
        layout.setGap(layoutFrame, 8.0f);
        layout.setAlignX(layoutFrame, com.aengine.aegis.AegisLayout.Align.STRETCH);

        // The toolbar: the 3c-2 row, now a child. It asks for a height and no width — the
        // frame's STRETCH supplies the width.
        layoutRow = layout.row(layoutFrame);
        layout.setSize(layoutRow, 0.0f, 84.0f);
        layout.setPadding(layoutRow, 12.0f);   // clear space inside the row's edges
        layout.setGap(layoutRow, 8.0f);        // space between one box and the next

        // Fixed: asks for 80 wide and grows by nothing.
        layoutBoxA = layout.box(layoutRow);
        layout.setSize(layoutBoxA, 80.0f, 60.0f);

        // Grows: asks for 0 wide, so its width is its share of the spare space and nothing else.
        layoutBoxB = layout.box(layoutRow);
        layout.setSize(layoutBoxB, 0.0f, 60.0f);
        layout.setGrow(layoutBoxB, 1.0f);

        // Grows twice as much as B.
        layoutBoxC = layout.box(layoutRow);
        layout.setSize(layoutBoxC, 0.0f, 60.0f);
        layout.setGrow(layoutBoxC, 2.0f);

        // Fixed again, so the growers have an edge to stop at.
        layoutBoxD = layout.box(layoutRow);
        layout.setSize(layoutBoxD, 60.0f, 60.0f);

        // The body: takes all the height the toolbar leaves (grow, in a column, is height),
        // and STRETCH on Y makes its three panes that tall.
        layoutBody = layout.row(layoutFrame);
        layout.setGrow(layoutBody, 1.0f);
        layout.setGap(layoutBody, 8.0f);
        layout.setAlignY(layoutBody, com.aengine.aegis.AegisLayout.Align.STRETCH);

        // Hierarchy: a fixed-width pane. START on X keeps each item at the width it asked for,
        // against the left edge — the way tree rows of different lengths sit. START is the
        // default; it is written out so the three panes read side by side.
        layoutHierarchy = layout.column(layoutBody);
        layout.setSize(layoutHierarchy, 240.0f, 0.0f);
        layout.setPadding(layoutHierarchy, 8.0f);
        layout.setGap(layoutHierarchy, 6.0f);
        layout.setAlignX(layoutHierarchy, com.aengine.aegis.AegisLayout.Align.START);

        layoutTreeItemA = layout.box(layoutHierarchy);
        layout.setSize(layoutTreeItemA, 150.0f, 28.0f);

        layoutTreeItemB = layout.box(layoutHierarchy);
        layout.setSize(layoutTreeItemB, 110.0f, 28.0f);

        // Viewport: takes the width the side panes leave, and centres what it holds on both
        // axes — one call per axis.
        layoutViewport = layout.column(layoutBody);
        layout.setGrow(layoutViewport, 1.0f);
        layout.setAlignX(layoutViewport, com.aengine.aegis.AegisLayout.Align.CENTER);
        layout.setAlignY(layoutViewport, com.aengine.aegis.AegisLayout.Align.CENTER);

        layoutCentred = layout.box(layoutViewport);
        layout.setSize(layoutCentred, 180.0f, 40.0f);

        // Inspector: a fixed-width pane. STRETCH on X makes every field as wide as the pane, so
        // the fields ask only for a height.
        layoutInspector = layout.column(layoutBody);
        layout.setSize(layoutInspector, 300.0f, 0.0f);
        layout.setPadding(layoutInspector, 8.0f);
        layout.setGap(layoutInspector, 6.0f);
        layout.setAlignX(layoutInspector, com.aengine.aegis.AegisLayout.Align.STRETCH);

        layoutFieldA = layout.box(layoutInspector);
        layout.setSize(layoutFieldA, 0.0f, 28.0f);

        layoutFieldB = layout.box(layoutInspector);
        layout.setSize(layoutFieldB, 0.0f, 28.0f);

        // Step 3d-2: which nodes react to the pointer. Everything else — panes, the toolbar
        // itself, the fields — lets it pass through to whatever interactive node holds it.
        com.aengine.aegis.AegisTree tree = aegis.tree();
        tree.setInteractive(layoutBoxA, true);
        tree.setInteractive(layoutBoxB, true);
        tree.setInteractive(layoutBoxC, true);
        tree.setInteractive(layoutBoxD, true);
        tree.setInteractive(layoutCentred, true);

        // Step 3d-3: which nodes Tab stops at. Nothing here says in what order — that comes
        // from the tree: the toolbar's A B C D, then the hierarchy's two items, then CENTER,
        // then the inspector's two fields. The fields and the hierarchy items take focus
        // without being clickable, which is why focusable is a marking of its own.
        tree.setFocusable(layoutBoxA, true);
        tree.setFocusable(layoutBoxB, true);
        tree.setFocusable(layoutBoxC, true);
        tree.setFocusable(layoutBoxD, true);
        tree.setFocusable(layoutTreeItemA, true);
        tree.setFocusable(layoutTreeItemB, true);
        tree.setFocusable(layoutCentred, true);
        tree.setFocusable(layoutFieldA, true);
        tree.setFocusable(layoutFieldB, true);
    }

    /**
     * SCAFFOLDING — the colour a box is drawn in, from its state in the tree.
     *
     * <p>Pressed only while the pointer is still over it: a box whose press has wandered off
     * goes back to its resting colour, telling the user that letting go now will not click.
     * This is the choice every widget in 3e will make, so it is written once here.</p>
     */
    private float[] boxColour(com.aengine.aegis.AegisTree tree, int box) {
        if (tree.isPressed(box) && tree.isHovered(box)) return BOX_PRESSED;
        if (tree.isHovered(box))                        return BOX_HOVER;
        return LAYOUT_BOX;
    }

    /**
     * SCAFFOLDING — a box's name and how many clicks it has had, centred vertically in it.
     * Built in the reused {@code UI_TEXT}, like the width labels.
     *
     * <p>Clipped to the box: a label longer than the box is cut at its edge rather than drawn
     * across its neighbour — which is what "A  clicks 11" did in an 80-pixel box. Every widget
     * label will need the same guard; this is the clip stack from Phase 2 doing that job.</p>
     */
    private void drawClickLabel(com.aengine.aegis.AegisLayout layout, int box, String name, int clicks) {
        UI_TEXT.setLength(0);
        UI_TEXT.append(name).append(' ').append(clicks);
        float top = layout.y(box) + (layout.height(box) - aegis.lineHeight()) * 0.5f;

        aegis.pushClipRect(layout.x(box), layout.y(box), layout.width(box), layout.height(box));
        aegis.addTextTop(layout.x(box) + 8.0f, top, UI_TEXT,
            LAYOUT_BOX_TEXT[0], LAYOUT_BOX_TEXT[1], LAYOUT_BOX_TEXT[2], LAYOUT_BOX_TEXT[3]);
        aegis.popClipRect();
    }

    /**
     * SCAFFOLDING — writes a box's solved width inside it, so a grow ratio can be read off the
     * screen as numbers rather than judged by eye. Built in the reused {@code UI_TEXT}, never
     * with concatenation, for the reason given at its declaration. Centred vertically in the
     * box by its line height, so it fits a 28-pixel field as well as a 60-pixel box.
     */
    private void drawWidthLabel(com.aengine.aegis.AegisLayout layout, int box, String name) {
        UI_TEXT.setLength(0);
        UI_TEXT.append(name).append(' ').append((int) layout.width(box));
        float top = layout.y(box) + (layout.height(box) - aegis.lineHeight()) * 0.5f;
        aegis.addTextTop(layout.x(box) + 8.0f, top, UI_TEXT,
            LAYOUT_BOX_TEXT[0], LAYOUT_BOX_TEXT[1], LAYOUT_BOX_TEXT[2], LAYOUT_BOX_TEXT[3]);
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
