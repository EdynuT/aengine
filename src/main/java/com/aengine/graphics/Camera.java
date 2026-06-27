package com.aengine.graphics;

import org.joml.Matrix4f;
import org.joml.Vector3f;

public class Camera {

    public enum Type { ORTHOGRAPHIC, PERSPECTIVE }

    private final Type     type;
    private final Matrix4f projection     = new Matrix4f();
    private final Matrix4f view           = new Matrix4f();
    private final Matrix4f viewProjection = new Matrix4f();
    private final Vector3f position       = new Vector3f(0, 0, 0);
    private float yaw   = -90.0f;
    private float pitch = 0.0f;

    private Camera(Type type) {
        this.type = type;
    }

    /** Create a 2D orthographic camera */
    public static Camera orthographic(float left, float right, float bottom, float top) {
        Camera cam = new Camera(Type.ORTHOGRAPHIC);
        cam.projection.ortho(left, right, bottom, top, -1.0f, 1.0f);
        cam.recalculate();
        return cam;
    }

    /** Create a 3D perspective camera */
    public static Camera perspective(float fovDegrees, float aspectRatio, float near, float far) {
        Camera cam = new Camera(Type.PERSPECTIVE);
        cam.projection.perspective((float) Math.toRadians(fovDegrees), aspectRatio, near, far);
        cam.recalculate();
        return cam;
    }

    public void recalculate() {
        if (type == Type.ORTHOGRAPHIC) {
            view.identity().translate(-position.x, -position.y, 0);
        } else {
            Vector3f front = new Vector3f(
                (float)(Math.cos(Math.toRadians(yaw)) * Math.cos(Math.toRadians(pitch))),
                (float)(Math.sin(Math.toRadians(pitch))),
                (float)(Math.sin(Math.toRadians(yaw)) * Math.cos(Math.toRadians(pitch)))
            ).normalize();
            view.identity().lookAt(position,
                new Vector3f(position).add(front),
                new Vector3f(0, 1, 0));
        }
        viewProjection.set(projection).mul(view);
    }

    public void setPosition(float x, float y, float z)  { position.set(x, y, z); recalculate(); }
    public void move(Vector3f delta)                    { position.add(delta);   recalculate(); }
    public void setYaw(float yaw)                       { this.yaw   = yaw;      recalculate(); }
    public void setPitch(float pitch)                   { this.pitch = pitch;    recalculate(); }

    public Matrix4f getProjection()     { return projection; }
    public Matrix4f getView()           { return view; }
    public Matrix4f getViewProjection() { return viewProjection; }
    public Vector3f getPosition()       { return position; }
    public float    getYaw()            { return yaw; }
    public float    getPitch()          { return pitch; }
}
