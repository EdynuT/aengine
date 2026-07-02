package com.aengine.ecs.systems;

import com.aengine.core.Input;
import com.aengine.core.Keys;
import com.aengine.Main;
import com.aengine.ecs.ComponentPool;
import com.aengine.ecs.Registry;
import com.aengine.ecs.System;
import com.aengine.ecs.components.CameraComponent;
import com.aengine.ecs.components.TransformComponent;
import com.aengine.graphics.Camera;
import org.joml.Vector3f;
import static org.lwjgl.glfw.GLFW.*;

public final class CameraSystem extends System {

    private final Vector3f movementDelta = new Vector3f();
    private final Vector3f forwardDirection = new Vector3f();
    private final Vector3f rightDirection = new Vector3f();
    private final Vector3f verticalUp = new Vector3f(0.0f, 1.0f, 0.0f);

    private float currentYaw = -90.0f;
    private float currentPitch = 0.0f;
    private final float movementSpeed = 6.0f;
    private final float mouseSensitivity = 0.15f;
    
    private float lastMouseX = 0.0f;
    private float lastMouseY = 0.0f;
    private boolean isFirstFrame = true;
    private boolean isCursorHidden = false;

    private static final int BUTTON_RIGHT = 1; 

    @Override
    public void update(Registry registry, float deltaTime) {
        ComponentPool<CameraComponent> cameraPool = registry.getPool(CameraComponent.class);
        ComponentPool<TransformComponent> transformPool = registry.getPool(TransformComponent.class);

        if (cameraPool == null || transformPool == null || cameraPool.size() == 0) {
            return;
        }

        CameraComponent[] cameras = cameraPool.getRawComponents();
        int[] denseToEntity = cameraPool.getRawDenseToEntity();
        int totalElements = cameraPool.size();

        // Verify if the active render mode is 2D or 3D
        boolean is2DMode = (Main.getActiveRenderMode() == Main.RenderMode.MODE_2D);

        for (int i = 0; i < totalElements; i++) {
            CameraComponent camComp = cameras[i];
            if (!camComp.primary) continue;

            int entityID = denseToEntity[i];
            TransformComponent transform = transformPool.get(entityID);
            if (transform == null) continue;

            Camera nativeCamera = camComp.camera;

            // 1. Hardware mouse coordinates fetch
            float currentMouseX = (float) Input.getMouseX();
            float currentMouseY = (float) Input.getMouseY();

            if (isFirstFrame) {
                lastMouseX = currentMouseX;
                lastMouseY = currentMouseY;
                isFirstFrame = false;
                continue;
            }

            float deltaX = currentMouseX - lastMouseX;
            float deltaY = currentMouseY - lastMouseY;
            
            lastMouseX = currentMouseX;
            lastMouseY = currentMouseY;

            // 2. Evaluate current direction vectors based on rotation look tracking
            double radYaw = Math.toRadians(currentYaw);
            double radPitch = Math.toRadians(currentPitch);

            forwardDirection.x = (float) (Math.cos(radYaw) * Math.cos(radPitch));
            forwardDirection.y = (float) Math.sin(radPitch);
            forwardDirection.z = (float) (Math.sin(radYaw) * Math.cos(radPitch));
            forwardDirection.normalize();

            forwardDirection.cross(verticalUp, rightDirection).normalize();

            movementDelta.set(0.0f, 0.0f, 0.0f);
            float speedModifier = movementSpeed * deltaTime;

            // 3. State Machine: Is Right Click Engaged?
            if (Input.isMouseButtonPressed(BUTTON_RIGHT)) {
                
                if (!isCursorHidden && (Math.abs(deltaX) > 0.0f || Math.abs(deltaY) > 0.0f)) {
                    long windowHandle = glfwGetCurrentContext();
                    glfwSetInputMode(windowHandle, GLFW_CURSOR, GLFW_CURSOR_DISABLED);
                    isCursorHidden = true;
                }

                if (is2DMode) {
                    // --- 2D BEHAVIOR ---
                    // Right Button only drags the camera (Pan) along the X and Y axes
                    if (Math.abs(deltaX) > 0.0f) {
                        // Invert the delta to give the sensation of "dragging the paper"
                        movementDelta.x -= deltaX * mouseSensitivity * 0.05f;
                    }
                    if (Math.abs(deltaY) > 0.0f) {
                        // In GLFW, moving the mouse down increases Y. To make the camera go up, Y must be positive.
                        movementDelta.y += deltaY * mouseSensitivity * 0.05f;
                    }
                } else {
                    // --- 3D BEHAVIOR ---
                    // Modifier state check: SHIFT + RIGHT CLICK (Strafe/Pan mode)
                    if (Input.isKeyPressed(Keys.SHIFT_L) || Input.isKeyPressed(Keys.SHIFT_R)) {
                        if (Math.abs(deltaX) > 0.0f) {
                            movementDelta.add(new Vector3f(rightDirection).mul(deltaX * mouseSensitivity * 0.01f));
                        }
                        if (Math.abs(deltaY) > 0.0f) {
                            movementDelta.sub(new Vector3f(forwardDirection).mul(deltaY * mouseSensitivity * 0.01f));
                        }
                    } else {
                        // Standard State: RIGHT CLICK ONLY (Orbit/Look mode)
                        if (Math.abs(deltaX) > 0.0f || Math.abs(deltaY) > 0.0f) {
                            currentYaw += deltaX * mouseSensitivity;
                            currentPitch -= deltaY * mouseSensitivity;

                            // Lock the Y axis to prevent the camera from flipping
                            if (currentPitch > 89.9f)  currentPitch = 89.9f;
                            if (currentPitch < -89.9f) currentPitch = -89.9f;
                        }
                    }
                }
            } else {
                // Restore the cursor when releasing the right button
                if (isCursorHidden) {
                    long windowHandle = glfwGetCurrentContext();
                    glfwSetInputMode(windowHandle, GLFW_CURSOR, GLFW_CURSOR_NORMAL);
                    isCursorHidden = false;
                }
            }

            // 4. Vertical Space Elevation Processing (Space to Ascend / Left Ctrl to Descend)
            // Works identically in 2D and 3D as requested
            if (Input.isKeyPressed(Keys.SPACE)) {
                movementDelta.add(new Vector3f(verticalUp).mul(speedModifier));
            }
            if (Input.isKeyPressed(Keys.CTRL_L) || Input.isKeyPressed(Keys.CTRL_R)) {
                movementDelta.sub(new Vector3f(verticalUp).mul(speedModifier));
            }

            // Apply calculated structural translations straight to component storage
            if (movementDelta.lengthSquared() > 0.0f) {
                transform.position.add(movementDelta);
            }

            // Enforce unified matrix synchronization block inside the camera subsystem to prevent frame stalls
            nativeCamera.setPosition(transform.position.x, transform.position.y, transform.position.z);
            nativeCamera.setYaw(currentYaw);
            nativeCamera.setPitch(currentPitch);
            
            // Invoke the baseline matrix getter to evaluate or flag dirty states internally
            nativeCamera.getViewProjection(); 
        }
    }
}
