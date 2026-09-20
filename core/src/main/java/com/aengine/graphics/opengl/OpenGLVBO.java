package com.aengine.graphics.opengl;

import com.aengine.graphics.BufferAPI;
import org.lwjgl.BufferUtils;

import java.nio.FloatBuffer;

import static org.lwjgl.opengl.GL15.*;

public class OpenGLVBO implements BufferAPI {

    private final int id;

    public OpenGLVBO() {
        id = glGenBuffers();
    }

    @Override public void bind()   { glBindBuffer(GL_ARRAY_BUFFER, id); }
    @Override public void unbind() { glBindBuffer(GL_ARRAY_BUFFER, 0); }

    public void uploadData(float[] data, int usage) {
        bind();
        FloatBuffer buf = BufferUtils.createFloatBuffer(data.length);
        buf.put(data).flip();
        glBufferData(GL_ARRAY_BUFFER, buf, usage);
    }

    public void uploadData(float[] data) { uploadData(data, GL_STATIC_DRAW); }

    @Override public void cleanup() { glDeleteBuffers(id); }
    public    int  getId()          { return id; }
}
