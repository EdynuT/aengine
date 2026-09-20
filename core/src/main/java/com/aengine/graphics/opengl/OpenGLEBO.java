package com.aengine.graphics.opengl;

import com.aengine.graphics.BufferAPI;
import org.lwjgl.BufferUtils;

import java.nio.IntBuffer;

import static org.lwjgl.opengl.GL15.*;

public class OpenGLEBO implements BufferAPI {

    private final int id;
    private int indexCount;

    public OpenGLEBO() {
        id = glGenBuffers();
    }

    @Override public void bind()   { glBindBuffer(GL_ELEMENT_ARRAY_BUFFER, id); }
    @Override public void unbind() { glBindBuffer(GL_ELEMENT_ARRAY_BUFFER, 0); }

    public void uploadData(int[] indices, int usage) {
        indexCount = indices.length;
        bind();
        IntBuffer buf = BufferUtils.createIntBuffer(indices.length);
        buf.put(indices).flip();
        glBufferData(GL_ELEMENT_ARRAY_BUFFER, buf, usage);
    }

    public void uploadData(int[] indices) { uploadData(indices, GL_STATIC_DRAW); }

    @Override public void cleanup()      { glDeleteBuffers(id); }
    public    int  getIndexCount()       { return indexCount; }
    public    int  getId()               { return id; }
}
