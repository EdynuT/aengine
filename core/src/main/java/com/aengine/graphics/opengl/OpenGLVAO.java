package com.aengine.graphics.opengl;

import com.aengine.graphics.BufferAPI;

import static org.lwjgl.opengl.GL30.*;

/**
 * An OpenGL vertex array object: records how vertex buffers are read (attribute formats and
 * offsets) and which index buffer is used. Bind it, bind the buffers and declare the
 * attributes once; afterwards binding the VAO alone is enough to draw. Must be used on
 * the GL thread.
 */
public class OpenGLVAO implements BufferAPI {

    private final int id;

    /** Generates the vertex array name. */
    public OpenGLVAO() {
        id = glGenVertexArrays();
    }

    @Override public void bind()   { glBindVertexArray(id); }
    @Override public void unbind() { glBindVertexArray(0); }

    /**
     * Declares a float attribute read from the currently bound vertex buffer, and enables it.
     * This VAO must be bound. Stride and offset are in 4-byte slots, not bytes.
     *
     * @param index  attribute location in the shader
     * @param size   how many floats the attribute has, 1 to 4
     * @param stride slots from one vertex to the next
     * @param offset slots from the start of a vertex to this attribute
     */
    public void setVertexAttrib(int index, int size, int stride, int offset) {
        glVertexAttribPointer(index, size, GL_FLOAT, false,
            stride * Float.BYTES, (long) offset * Float.BYTES);
        glEnableVertexAttribArray(index);
    }

    /**
     * Declares four unsigned bytes read by the shader as a normalised vec4 in 0..1 — a packed
     * RGBA8 colour in one 4-byte slot. Stride and offset are in 4-byte slots, as for
     * {@link #setVertexAttrib}.
     *
     * @param index  attribute location in the shader
     * @param stride slots from one vertex to the next
     * @param offset slots from the start of a vertex to this attribute
     */
    public void setVertexAttribNormalizedBytes(int index, int stride, int offset) {
        glVertexAttribPointer(index, 4, GL_UNSIGNED_BYTE, true,
            stride * Float.BYTES, (long) offset * Float.BYTES);
        glEnableVertexAttribArray(index);
    }

    @Override public void cleanup() { glDeleteVertexArrays(id); }

    /**
     * Returns the OpenGL vertex array name.
     *
     * @return the name generated at construction
     */
    public    int  getId()          { return id; }
}
