package com.aengine.ecs.components;

import com.aengine.graphics.Camera;

/**
 * Gives an entity a {@link Camera}. The entity also needs a {@link TransformComponent},
 * which the {@link com.aengine.ecs.systems.CameraSystem} copies into the camera each frame.
 *
 * <p>Entities with this component survive {@link com.aengine.ecs.Registry#clearScene()}.</p>
 */
public final class CameraComponent {

    /** The camera and its matrices. Created by the constructor and never replaced. */
    public final Camera camera;
    /**
     * Whether this is the camera the engine uses. The camera and audio systems act on
     * primary cameras only; mark exactly one camera as primary.
     */
    public boolean primary = true;

    /**
     * Creates a perspective or orthographic camera sized for a viewport.
     *
     * <p>An orthographic camera is always 10 world units tall, centred on the origin, with
     * the width following the aspect ratio. It ignores {@code fov}, {@code near} and
     * {@code far}.</p>
     *
     * @param fov           vertical field of view in degrees (perspective only)
     * @param width         viewport width, used only for the aspect ratio
     * @param height        viewport height, used only for the aspect ratio; must not be zero
     * @param near          near clipping plane distance (perspective only)
     * @param far           far clipping plane distance (perspective only)
     * @param isPerspective {@code true} for a 3D perspective camera, {@code false} for a 2D orthographic one
     */
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
