package com.aengine.graphics.opengl;

import com.aengine.graphics.BufferAPI;
import org.lwjgl.BufferUtils;

import java.nio.IntBuffer;

import static org.lwjgl.opengl.GL15.*;

/**
 * An OpenGL index buffer ({@code GL_ELEMENT_ARRAY_BUFFER}).
 *
 * <p>The index-buffer binding is part of the vertex array state: binding or uploading this
 * buffer attaches it to whichever VAO is bound at that moment. Bind the intended VAO
 * first. Must be used on the GL thread.</p>
 */
public class OpenGLEBO implements BufferAPI {

    private final int id;
    private int indexCount;

    /** Generates the buffer name; no storage is allocated until {@link #uploadData}. */
    public OpenGLEBO() {
        id = glGenBuffers();
    }

    @Override public void bind()   { glBindBuffer(GL_ELEMENT_ARRAY_BUFFER, id); }
    @Override public void unbind() { glBindBuffer(GL_ELEMENT_ARRAY_BUFFER, 0); }

    /**
     * Binds the buffer and replaces its storage with a copy of {@code indices}. Allocates a
     * temporary direct buffer, so do not call it every frame.
     *
     * @param indices the indices to upload
     * @param usage   an OpenGL usage hint such as {@code GL_STATIC_DRAW}
     */
    public void uploadData(int[] indices, int usage) {
        indexCount = indices.length;
        bind();
        IntBuffer buf = BufferUtils.createIntBuffer(indices.length);
        buf.put(indices).flip();
        glBufferData(GL_ELEMENT_ARRAY_BUFFER, buf, usage);
    }

    /**
     * Uploads indices that will not change, as {@link #uploadData(int[], int)} with
     * {@code GL_STATIC_DRAW}.
     *
     * @param indices the indices to upload
     */
    public void uploadData(int[] indices) { uploadData(indices, GL_STATIC_DRAW); }

    @Override public void cleanup()      { glDeleteBuffers(id); }

    /**
     * Returns how many indices the last {@link #uploadData} stored.
     *
     * @return the index count; 0 before any upload
     */
    public    int  getIndexCount()       { return indexCount; }

    /**
     * Returns the OpenGL buffer name.
     *
     * @return the name generated at construction
     */
    public    int  getId()               { return id; }
}
