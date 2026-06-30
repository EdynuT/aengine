package com.aengine;

import com.aengine.graphics.Renderer2D;
import com.aengine.graphics.Renderer3D;
import com.aengine.graphics.Camera;
import com.aengine.utils.ProjectWizard;
import com.aengine.utils.FileSystem;
import com.aengine.utils.Logger;
import com.aengine.ecs.components.TransformComponent;
import com.aengine.ecs.components.CameraComponent;
import com.aengine.ecs.components.SpriteComponent;
import com.aengine.ecs.systems.CameraSystem;

import java.io.File;

import org.joml.Vector3f;
import org.joml.Vector4f;

public class Main extends Engine {

    private CameraSystem cameraSystem;
    private int cameraEntity;

    public enum RenderMode { MODE_2D, MODE_3D }
    private static RenderMode activeRenderMode = RenderMode.MODE_3D;

    // Allocation-free temporary structural containers for 3D physical environment alignment
    private static final Vector3f GROUND_POSITION = new Vector3f(0.0f, -1.5f, 0.0f); 
    private static final Vector3f GROUND_SIZE = new Vector3f(1000.0f, 1.0f, 1000.0f);   
    private static final Vector4f GROUND_COLOR    = new Vector4f(0.50f, 0.50f, 0.50f, 1.0f); // Light Gray Floor

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
        } catch (Exception e) {
            Logger.error(Logger.System.CORE, "VFS Handshake critical failure. Halting engine initialization pipeline.");
            throw new RuntimeException("Critical core infrastructure failure during VFS mount", e);
        }

        Renderer2D.init();
        Renderer3D.init();
        
        // Atmospheric sky blue background clear color registration
        Renderer2D.setClearColor(0.45f, 0.65f, 0.85f, 1.0f);

        cameraSystem = new CameraSystem();
        cameraEntity = registry.createEntity();
        registry.addComponent(cameraEntity, new TransformComponent(new Vector3f(0.0f, 0.0f, 5.0f)));

        if (activeRenderMode == RenderMode.MODE_3D) {
            Logger.info(Logger.System.RENDERER, "Enforcing Core 3D Perspective execution pipeline.");
            org.lwjgl.opengl.GL11.glEnable(org.lwjgl.opengl.GL11.GL_DEPTH_TEST);
            
            // True activates 3D Perspective projection matrix calculation inside CameraComponent
            registry.addComponent(cameraEntity, new CameraComponent(45.0f, getWindow().getWidth(), getWindow().getHeight(), 0.1f, 1000.0f, true));
            
            // Spawn static world assets scattered across X and Z planes for depth testing
            for (int i = 0; i < 3; i++) {
                int entity = registry.createEntity();
                registry.addComponent(entity, new TransformComponent(new Vector3f(i * 2.5f - 2.5f, 0.0f, -i * 2.0f)));
                registry.addComponent(entity, new SpriteComponent(new Vector4f(0.2f, 0.5f, i * 0.3f + 0.3f, 1.0f)));
            }
        } else {
            Logger.info(Logger.System.RENDERER, "Enforcing Core 2D Orthographic execution pipeline. Z-Axis dropped.");
            org.lwjgl.opengl.GL11.glDisable(org.lwjgl.opengl.GL11.GL_DEPTH_TEST);
            
            // False enforces 2D Orthographic projection matrix calculation, dropping spatial depth distortions
            registry.addComponent(cameraEntity, new CameraComponent(0.0f, getWindow().getWidth(), getWindow().getHeight(), -1.0f, 1.0f, false));
            
            // Spawn static world assets locked at Z = 0.0f to prevent pipeline artifacts
            for (int i = 0; i < 3; i++) {
                int entity = registry.createEntity();
                registry.addComponent(entity, new TransformComponent(new Vector3f(i * 2.5f - 2.5f, 0.0f, 0.0f)));
                registry.addComponent(entity, new SpriteComponent(new Vector4f(0.2f, i * 0.3f + 0.3f, 0.5f, 1.0f)));
            }
        }
    }

    @Override
    protected void onUpdate(float deltaTime) {
        if (Input.isKeyPressed(Keys.ESCAPE)) {
            stop();
        }

        // Tick hardware camera updates before dispatching geometry rendering frames
        cameraSystem.update(registry, deltaTime);
        Input.update();
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
