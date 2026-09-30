package com.aengine.graphics;

import org.joml.Matrix4f;
import org.joml.Vector3f;

/**
 * A view and projection, combined into the {@link #getViewProjection() view-projection}
 * matrix shaders take.
 *
 * <p>Two kinds, fixed at creation:</p>
 * <ul>
 *   <li><b>Orthographic</b> (2D): looks straight down -Z. Only the X and Y of the position
 *       move the view; Z, yaw and pitch are ignored. Anything whose world Z falls
 *       outside -1 to 1 is clipped.</li>
 *   <li><b>Perspective</b> (3D): looks from the position in the direction given by yaw and
 *       pitch, in degrees. Yaw -90 and pitch 0 (the defaults) look down -Z.</li>
 * </ul>
 *
 * <p>Every setter recalculates the matrices at once, so the getters are always current.
 * The getters return the camera's own objects: read them, do not modify them.</p>
 */
public class Camera {

    /** The kind of projection a camera uses. */
    public enum Type {
        /** Parallel projection with no perspective, for 2D. */
        ORTHOGRAPHIC,
        /** Perspective projection, for 3D. */
        PERSPECTIVE
    }

    private final Type     type;
    private final Matrix4f projection     = new Matrix4f();
    private final Matrix4f view           = new Matrix4f();
    private final Matrix4f viewProjection = new Matrix4f();
    private final Vector3f position       = new Vector3f(0, 0, 0);
    private float yaw   = -90.0f;
    private float pitch = 0.0f;

    // Allocation-free static mathematical hooks to secure L1/L2 cache lines
    private final Vector3f targetLookBuffer = new Vector3f();
    private final Vector3f upVectorBuffer   = new Vector3f(0.0f, 1.0f, 0.0f);
    private final Vector3f frontDirection   = new Vector3f();

    private Camera(Type type) {
        this.type = type;
    }

    /**
     * Create a 2D orthographic camera. The four edges are in world units, relative to the
     * camera's position, which starts at the origin.
     *
     * @param left   world X at the left edge of the view
     * @param right  world X at the right edge of the view
     * @param bottom world Y at the bottom edge of the view
     * @param top    world Y at the top edge of the view
     * @return the new camera
     */
    public static Camera orthographic(float left, float right, float bottom, float top) {
        Camera cam = new Camera(Type.ORTHOGRAPHIC);
        cam.projection.setOrtho(left, right, bottom, top, -1.0f, 1.0f);
        cam.recalculate();
        return cam;
    }

    /**
     * Create a 3D perspective camera at the origin, looking down -Z.
     *
     * @param fovDegrees  vertical field of view in degrees
     * @param aspectRatio viewport width divided by height
     * @param near        distance to the near clipping plane; must be greater than 0
     * @param far         distance to the far clipping plane; must be greater than {@code near}
     * @return the new camera
     */
    public static Camera perspective(float fovDegrees, float aspectRatio, float near, float far) {
        Camera cam = new Camera(Type.PERSPECTIVE);
        cam.projection.setPerspective((float) Math.toRadians(fovDegrees), aspectRatio, near, far);
        cam.recalculate();
        return cam;
    }

    /**
     * Rebuilds the view and view-projection matrices from the position, yaw and pitch.
     * The setters already call it; call it yourself only after changing the vector
     * returned by {@link #getPosition()} directly.
     */
    public void recalculate() {
        if (type == Type.ORTHOGRAPHIC) {
            view.identity().translate(-position.x, -position.y, 0.0f);
        } else {
            double radYaw = Math.toRadians(yaw);
            double radPitch = Math.toRadians(pitch);

            frontDirection.x = (float) (Math.cos(radYaw) * Math.cos(radPitch));
            frontDirection.y = (float) Math.sin(radPitch);
            frontDirection.z = (float) (Math.sin(radYaw) * Math.cos(radPitch));
            frontDirection.normalize();

            // Perform calculation inside localized vector references to isolate memory allocations
            position.add(frontDirection, targetLookBuffer);
            
            // Reconstruct the View Matrix pointing smoothly towards the directional target
            view.identity().lookAt(position, targetLookBuffer, upVectorBuffer);
        }
        
        // Enforce the standard hardware matrix layout order: ViewProjection = Projection * View
        projection.mul(view, viewProjection);
    }

    /**
     * Moves the camera to a world position.
     *
     * @param x world X
     * @param y world Y
     * @param z world Z (ignored by orthographic cameras)
     */
    public void setPosition(float x, float y, float z)  { position.set(x, y, z); recalculate(); }

    /**
     * Moves the camera by an offset in world units.
     *
     * @param delta the offset; read, not kept
     */
    public void move(Vector3f delta)                    { position.add(delta);   recalculate(); }

    /**
     * Sets the horizontal look angle. Ignored by orthographic cameras.
     *
     * @param yaw angle in degrees around the Y axis; -90 looks down -Z
     */
    public void setYaw(float yaw)                       { this.yaw   = yaw;      recalculate(); }

    /**
     * Sets the vertical look angle. Ignored by orthographic cameras. Keep it inside
     * -90 to 90 exclusive: at exactly ±90 the view matrix is undefined.
     *
     * @param pitch angle in degrees; positive looks up
     */
    public void setPitch(float pitch)                   { this.pitch = pitch;    recalculate(); }

    /**
     * Returns the projection matrix alone.
     *
     * @return the camera's own matrix; do not modify
     */
    public Matrix4f getProjection()     { return projection; }

    /**
     * Returns the view matrix alone (world to camera space).
     *
     * @return the camera's own matrix; do not modify
     */
    public Matrix4f getView()           { return view; }

    /**
     * Returns projection × view, the matrix to hand to shaders.
     *
     * @return the camera's own matrix; do not modify
     */
    public Matrix4f getViewProjection() { return viewProjection; }

    /**
     * Returns the camera's position. Changing it directly does not update the matrices;
     * use {@link #setPosition} or call {@link #recalculate()} afterwards.
     *
     * @return the camera's own vector
     */
    public Vector3f getPosition()       { return position; }

    /**
     * Returns the horizontal look angle.
     *
     * @return yaw in degrees
     */
    public float    getYaw()            { return yaw; }

    /**
     * Returns the vertical look angle.
     *
     * @return pitch in degrees
     */
    public float    getPitch()          { return pitch; }
}
