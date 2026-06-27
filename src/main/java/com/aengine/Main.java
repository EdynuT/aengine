package com.aengine;

import com.aengine.graphics.Camera;
import com.aengine.graphics.Renderer2D;
import com.aengine.graphics.Renderer3D;
import org.joml.Vector3f;
import org.joml.Vector4f;

public class Main extends Engine {

    private Camera camera;
    
    // Movement velocity scalars
    private final float moveSpeed = 4.0f;
    private final float mouseSensitivity = 0.1f;

    private static final int[][] GRID_MAP = {
        {1, 1, 1, 1, 1, 1, 1},
        {1, 0, 0, 0, 0, 0, 1},
        {1, 0, 1, 0, 1, 0, 1},
        {1, 0, 1, 0, 1, 0, 1},
        {1, 0, 0, 0, 0, 0, 1},
        {1, 1, 1, 1, 1, 1, 1}
    };

    public Main() {
        super("AEngine", 1280, 720);
    }

    @Override
    protected void onInit() {
        Renderer2D.init();
        Renderer3D.init();

        camera = Camera.perspective(70.0f, 1280.0f / 720.0f, 0.1f, 100.0f);
        camera.setPosition(2.5f, 0.0f, 4.0f);

        // Lock mouse pointer to center to act as hardware First Person controller
        Input.setCursorMode(true);

        Renderer2D.setClearColor(0.02f, 0.02f, 0.04f, 1.0f);
    }

    @Override
    protected void onUpdate(float deltaTime) {
        if (Input.isKeyPressed(Keys.ESCAPE)) {
            stop();
        }

        // 1. MUST EXECUTE: Accumulate structural hardware delta calculations
        Input.update();

        // 2. Mouse Orientation Update Loop
        float deltaYaw   = (float) Input.getMouseDeltaX() * mouseSensitivity;
        float deltaPitch = (float) Input.getMouseDeltaY() * mouseSensitivity;

        camera.setYaw(camera.getYaw() + deltaYaw);
        
        // Clamp pitching rotation axis angle to prevent neck breaks
        float currentPitch = camera.getPitch() - deltaPitch;
        if (currentPitch > 89.0f)  currentPitch = 89.0f;
        if (currentPitch < -89.0f) currentPitch = -89.0f;
        camera.setPitch(currentPitch);

        // 3. Keyboard Directional Movement Translation Vectors calculation
        float yawRad = (float) Math.toRadians(camera.getYaw());
        Vector3f forwardDir = new Vector3f((float) Math.cos(yawRad), 0.0f, (float) Math.sin(yawRad)).normalize();
        
        // Fixed right-handed orthogonal projection calculation (A/D inversion fixed)
        Vector3f rightDir = new Vector3f(-forwardDir.z, 0.0f, forwardDir.x).normalize(); 
        
        Vector3f velocity = new Vector3f();

        if (Input.isKeyPressed(Keys.W)) velocity.add(forwardDir);
        if (Input.isKeyPressed(Keys.S)) velocity.sub(forwardDir);
        if (Input.isKeyPressed(Keys.D)) velocity.add(rightDir);
        if (Input.isKeyPressed(Keys.A)) velocity.sub(rightDir);

        if (velocity.lengthSquared() > 0) {
            velocity.normalize().mul(moveSpeed * deltaTime);
            camera.move(velocity);
        }
    }

    @Override
    protected void onRender() {
        Renderer3D.beginScene(camera);

        Vector4f wallColor = new Vector4f(0.25f, 0.3f, 0.4f, 1.0f);
        Vector4f floorColor = new Vector4f(0.12f, 0.12f, 0.15f, 1.0f);

        for (int z = 0; z < GRID_MAP.length; z++) {
            for (int x = 0; x < GRID_MAP[z].length; x++) {
                
                // Floor Tile
                Renderer3D.drawCubeFace(new Vector3f(x, -0.5f, z), new Vector3f(90.0f, 0.0f, 0.0f), new Vector3f(1.0f, 1.0f, 1.0f), null, floorColor);
                // Ceiling Tile
                Renderer3D.drawCubeFace(new Vector3f(x, 0.5f, z), new Vector3f(90.0f, 0.0f, 0.0f), new Vector3f(1.0f, 1.0f, 1.0f), null, floorColor);

                if (GRID_MAP[z][x] == 1) {
                    // Box mapping geometries
                    Renderer3D.drawCubeFace(new Vector3f(x, 0.0f, z + 0.5f), new Vector3f(0.0f, 0.0f, 0.0f), new Vector3f(1.0f, 1.0f, 1.0f), null, wallColor);
                    // Back
                    Renderer3D.drawCubeFace(new Vector3f(x, 0.0f, z - 0.5f), new Vector3f(0.0f, 180.0f, 0.0f), new Vector3f(1.0f, 1.0f, 1.0f), null, wallColor);
                    // Left
                    Renderer3D.drawCubeFace(new Vector3f(x - 0.5f, 0.0f, z), new Vector3f(0.0f, 90.0f, 0.0f), new Vector3f(1.0f, 1.0f, 1.0f), null, wallColor);
                    // Right
                    Renderer3D.drawCubeFace(new Vector3f(x + 0.5f, 0.0f, z), new Vector3f(0.0f, -90.0f, 0.0f), new Vector3f(1.0f, 1.0f, 1.0f), null, wallColor);
                }
            }
        }

        // Submits the complete batch array execution to the RenderAPI driver
        Renderer3D.endScene();
    }

    @Override
    protected void onCleanup() {
        Renderer3D.cleanup();
        Renderer2D.cleanup();
    }

    public static void main(String[] args) {
        new Main().run();
    }
}
