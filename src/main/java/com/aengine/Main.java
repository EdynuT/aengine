package com.aengine;

import java.io.File;

import org.joml.Vector3f;
import org.joml.Vector4f;

import com.aengine.core.Engine;
import com.aengine.core.Input;
import com.aengine.core.Keys;
import com.aengine.ecs.components.CameraComponent;
import com.aengine.ecs.components.SpriteComponent;
import com.aengine.ecs.components.TransformComponent;
import com.aengine.ecs.systems.CameraSystem;
import com.aengine.graphics.Camera;
import com.aengine.graphics.Renderer2D;
import com.aengine.graphics.Renderer3D;
import com.aengine.utils.FileSystem;
import com.aengine.utils.Logger;
import com.aengine.utils.ProjectWizard;

public class Main extends Engine {

    private CameraSystem cameraSystem;
    private int cameraEntity;
    
    // Physics Time-Step variables
    private float accumulator = 0.0f;
    private static final float TIME_STEP = 1.0f / 60.0f; // 60Hz

    // Telemetry Throttling variables
    private float telemetryAccumulator = 0.0f;
    private static final float TELEMETRY_INTERVAL = 0.1f; // 10Hz UI Refresh Rate

    private com.aengine.ecs.systems.PhysicsSystem physicsSystem;
    private com.aengine.ecs.systems.ScriptSystem scriptSystem;

    public enum RenderMode { MODE_2D, MODE_3D }
    private static RenderMode activeRenderMode = RenderMode.MODE_3D;

    // Allocation-free temporary structural containers for 3D physical environment alignment
    private static final Vector3f GROUND_POSITION = new Vector3f(0.0f, -1.5f, 0.0f); 
    private static final Vector3f GROUND_SIZE = new Vector3f(1024.0f, 1.0f, 1024.0f);
    private static final Vector4f GROUND_COLOR = new Vector4f(0.50f, 0.50f, 0.50f, 1.0f); // Light Gray Floor

    // Shared execution state capturing target path sent from external process host
    private static String activeProjectPath;

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
            com.aengine.utils.AssetBaker.bakeDirectory(rawAssetsDir, vfsAssetsDir);
            com.aengine.utils.AssetWatcher.start(activeProjectPath); 
            com.aengine.network.TelemetryServer.start();
        } catch (Exception e) {
            Logger.error(Logger.System.CORE, "VFS Handshake critical failure. Halting engine initialization pipeline.");
            throw new RuntimeException("Critical core infrastructure failure during VFS mount", e);
        }

        Renderer2D.init();
        Renderer3D.init();
        
        // Atmospheric sky blue background clear color registration (0.45f, 0.65f, 0.85f, 1.0f)
        // Gray background for neutral visual (0.30f, 0.30f, 0.30f, 1.0f)
        Renderer2D.setClearColor(0.30f, 0.30f, 0.30f, 1.0f);

        cameraSystem = new CameraSystem();
        cameraEntity = registry.createEntity();
        physicsSystem = new com.aengine.ecs.systems.PhysicsSystem();
        scriptSystem = new com.aengine.ecs.systems.ScriptSystem();
        
        // If is 3D, the camera recedes 5 meters. If is 2D, it stays at Z=0 along with the sprites.
        float cameraZ = (activeRenderMode == RenderMode.MODE_3D) ? 5.0f : 0.0f;
        registry.addComponent(cameraEntity, new TransformComponent(new Vector3f(0.0f, 0.0f, cameraZ)));

        if (activeRenderMode == RenderMode.MODE_3D) {
            Logger.info(Logger.System.RENDERER, "Enforcing Core 3D Perspective execution pipeline.");
            org.lwjgl.opengl.GL11.glEnable(org.lwjgl.opengl.GL11.GL_DEPTH_TEST);
            registry.addComponent(cameraEntity, new CameraComponent(45.0f, getWindow().getWidth(), getWindow().getHeight(), 0.1f, 1000.0f, true));
        } else {
            Logger.info(Logger.System.RENDERER, "Enforcing Core 2D Orthographic execution pipeline. Z-Axis dropped.");
            org.lwjgl.opengl.GL11.glDisable(org.lwjgl.opengl.GL11.GL_DEPTH_TEST);
            
            // False enforces 2D Orthographic projection matrix calculation, dropping spatial depth distortions
            registry.addComponent(cameraEntity, new CameraComponent(0.0f, getWindow().getWidth(), getWindow().getHeight(), -1.0f, 100.0f, false));
            
            // HARDWARE CONTEXT: Data-Driven Instantiation
            com.aengine.ecs.serialization.SceneLoader.load(registry, "assets://data/scenes/level_01.scene");
        }
    }

    @Override
    protected void onUpdate(float deltaTime) {
        com.aengine.utils.FPSTracker.update(deltaTime);
        if (Input.isKeyPressed(Keys.ESCAPE)) {
            stop();
        }

        // Executes the logic programmed in the Lua files
        scriptSystem.update(registry, deltaTime);

        // "Spiral of Death" protection: Prevents a massive OS lag from locking the game in an infinite loop
        if (deltaTime > 0.25f) {
            deltaTime = 0.25f;
        }

        // The accumulator absorbs the variable time from the renderer
        accumulator += deltaTime;

        // Physics consumes the absorbed time in perfectly equal slices
        while (accumulator >= TIME_STEP) {
            physicsSystem.update(registry, TIME_STEP);
            accumulator -= TIME_STEP;
        }

        // Visual systems operate freely, without Hz restriction
        cameraSystem.update(registry, deltaTime);
        Input.update();

        // =========================================================
        // ENGINE RUNTIME TELEMETRY DISPATCH (Thread-Safe)
        // =========================================================
        telemetryAccumulator += deltaTime;
        if (telemetryAccumulator >= TELEMETRY_INTERVAL) {
            // Dispatch state strictly from the Main Thread to avoid ECS data races.
            // The network server must handle the JSON serialization and socket push.
            com.aengine.network.TelemetryServer.dispatch(registry);
            telemetryAccumulator = 0.0f;
        }
    }

    @Override
    protected void onRender() {
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
        if (activeRenderMode == RenderMode.MODE_3D) {
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
            var sprite = registry.getComponent(entityID, SpriteComponent.class);
            Renderer2D.drawEntityQuad(transform, sprite);
        }

        Renderer3D.endScene();
    }

    @Override
    protected void onCleanup() {
        Logger.info(Logger.System.CORE, "Terminating active workspace runtime contexts. Executing hardware cleanup...");
        Renderer3D.cleanup();
        Renderer2D.cleanup();
        com.aengine.network.TelemetryServer.stop();
    }

    public static RenderMode getActiveRenderMode() { return activeRenderMode; }

    public static void main(String[] args) {
        for (String arg : args) {
            if (arg.equalsIgnoreCase("--2d")) {
                activeRenderMode = RenderMode.MODE_2D;
            } else if (arg.equalsIgnoreCase("--3d")) {
                activeRenderMode = RenderMode.MODE_3D;
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
