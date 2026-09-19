package com.aengine.ecs.components;

import com.aengine.graphics.Camera;

public final class CameraComponent {

    public final Camera camera;
    public boolean primary = true;

    public CameraComponent(float fov, float width, float height, float near, float far, boolean isPerspective) {
        // Explicitly enforce floating-point conversion to prevent integer truncation bugs
        float aspectRatio = (float) width / (float) height;

        if (isPerspective) {
            this.camera = Camera.perspective(fov, aspectRatio, near, far);
        } else {
            // Normalize 2D Orthographic view box to a standard size (e.g., 10 units high)
            // This prevents objects from shrinking to 1-pixel artifacts on 1440p displays
            float orthoSize = 10.0f;
            float orthoWidth = orthoSize * aspectRatio;
            
            this.camera = Camera.orthographic(
                -orthoWidth / 2.0f,  orthoWidth / 2.0f, 
                -orthoSize  / 2.0f,  orthoSize  / 2.0f
            );
        }
    }
}
