package com.aengine.graphics;

import org.joml.Matrix4f;
import org.joml.Vector2f;
import org.joml.Vector3f;
import org.joml.Vector4f;

public interface ShaderAPI {
    void bind();
    void unbind();
    void setInt(String name, int value);
    void setFloat(String name, float value);
    void setVec2(String name, Vector2f value);
    void setVec3(String name, Vector3f value);
    void setVec4(String name, Vector4f value);
    void setMat4(String name, Matrix4f value);
    void cleanup();
}
