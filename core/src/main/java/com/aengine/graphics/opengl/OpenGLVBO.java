package com.aengine.graphics.opengl;

import com.aengine.graphics.BufferAPI;
import org.lwjgl.BufferUtils;

import java.nio.FloatBuffer;

import static org.lwjgl.opengl.GL15.*;

/** An OpenGL vertex buffer ({@code GL_ARRAY_BUFFER}). Must be used on the GL thread. */
public class OpenGLVBO implements BufferAPI {

    private final int id;

    /** Generates the buffer name; no storage is allocated until {@link #uploadData}. */
    public OpenGLVBO() {
        id = glGenBuffers();
    }

    @Override public void bind()   { glBindBuffer(GL_ARRAY_BUFFER, id); }
    @Override public void unbind() { glBindBuffer(GL_ARRAY_BUFFER, 0); }

    /**
     * Binds the buffer and replaces its storage with a copy of {@code data}. Allocates a
     * temporary direct buffer, so do not call it every frame.
     *
     * @param data  the floats to upload
     * @param usage an OpenGL usage hint such as {@code GL_STATIC_DRAW} or {@code GL_DYNAMIC_DRAW}
     */
    public void uploadData(float[] data, int usage) {
        bind();
        FloatBuffer buf = BufferUtils.createFloatBuffer(data.length);
        buf.put(data).flip();
        glBufferData(GL_ARRAY_BUFFER, buf, usage);
    }

    /**
     * Uploads data that will not change, as {@link #uploadData(float[], int)} with
     * {@code GL_STATIC_DRAW}.
     *
     * @param data the floats to upload
     */
    public void uploadData(float[] data) { uploadData(data, GL_STATIC_DRAW); }

    @Override public void cleanup() { glDeleteBuffers(id); }

    /**
     * Returns the OpenGL buffer name.
     *
     * @return the name generated at construction
     */
    public    int  getId()          { return id; }
}
