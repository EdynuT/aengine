package com.aengine.graphics;

import org.joml.Matrix4f;
import org.joml.Vector2f;
import org.joml.Vector3f;
import org.joml.Vector4f;

/**
 * A compiled and linked shader program. Create one through
 * {@link RenderContext#createShader(String, String)}.
 *
 * <p>The {@code set...} methods write a uniform of the program that is <em>currently
 * bound</em>, so call {@link #bind()} first. A uniform name that does not exist, or that
 * the compiler removed because it is unused, is logged once as a warning and then
 * silently ignored. Every method must run on the GL thread.</p>
 */
public interface ShaderAPI {
    /** Makes this program the one used by the next draws. */
    void bind();
    /** Unbinds any program. */
    void unbind();

    /**
     * Sets an {@code int} or sampler uniform.
     *
     * @param name  uniform name as written in the shader; array elements as {@code "u_Name[3]"}
     * @param value the value
     */
    void setInt(String name, int value);

    /**
     * Sets a {@code float} uniform.
     *
     * @param name  uniform name as written in the shader
     * @param value the value
     */
    void setFloat(String name, float value);

    /**
     * Sets a {@code vec2} uniform.
     *
     * @param name  uniform name as written in the shader
     * @param value the value; read, not kept
     */
    void setVec2(String name, Vector2f value);

    /**
     * Sets a {@code vec3} uniform.
     *
     * @param name  uniform name as written in the shader
     * @param value the value; read, not kept
     */
    void setVec3(String name, Vector3f value);

    /**
     * Sets a {@code vec4} uniform.
     *
     * @param name  uniform name as written in the shader
     * @param value the value; read, not kept
     */
    void setVec4(String name, Vector4f value);

    /**
     * Sets a {@code mat4} uniform, uploaded in column-major order without transposing.
     *
     * @param name  uniform name as written in the shader
     * @param value the matrix; read, not kept
     */
    void setMat4(String name, Matrix4f value);

    /** Deletes the program. The shader must not be used afterwards. */
    void cleanup();
}
