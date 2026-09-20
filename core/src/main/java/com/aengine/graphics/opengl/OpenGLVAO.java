package com.aengine.graphics.opengl;

import com.aengine.graphics.BufferAPI;

import static org.lwjgl.opengl.GL30.*;

public class OpenGLVAO implements BufferAPI {

    private final int id;

    public OpenGLVAO() {
        id = glGenVertexArrays();
    }

    @Override public void bind()   { glBindVertexArray(id); }
    @Override public void unbind() { glBindVertexArray(0); }

    public void setVertexAttrib(int index, int size, int stride, int offset) {
        glVertexAttribPointer(index, size, GL_FLOAT, false,
            stride * Float.BYTES, (long) offset * Float.BYTES);
        glEnableVertexAttribArray(index);
    }

    @Override public void cleanup() { glDeleteVertexArrays(id); }
    public    int  getId()          { return id; }
}
