package com.aengine.graphics.opengl;

import com.aengine.graphics.RendererAPI;
import org.joml.Vector4f;
import static org.lwjgl.opengl.GL11.*;
import static org.lwjgl.opengl.GL15.*;

public class OpenGLRenderer implements RendererAPI {

    private OpenGLVAO quadVAO;
    private OpenGLVBO quadVBO;
    private OpenGLEBO quadEBO;
    private int vertexSizeFloats;

    @Override
    public void init() {
        // Enable hardware-level depth testing for structural 3D rendering
        glEnable(GL_DEPTH_TEST);
        glDepthFunc(GL_LESS);
    }

    @Override
    public void initBatchBuffers(int maxVertices, int maxIndices, int vertexSizeFloats) {
        this.vertexSizeFloats = vertexSizeFloats;

        quadVAO = new OpenGLVAO();
        quadVBO = new OpenGLVBO();
        quadEBO = new OpenGLEBO();

        quadVAO.bind();
        quadVBO.bind();
        quadVBO.uploadData(new float[maxVertices * vertexSizeFloats], GL_DYNAMIC_DRAW);

        int[] indices = new int[maxIndices];
        int offset = 0;
        for (int i = 0; i < maxIndices; i += 6) { // 6 indices per quad layout container
            indices[i + 0] = offset + 0;
            indices[i + 1] = offset + 1;
            indices[i + 2] = offset + 2;
            indices[i + 3] = offset + 2;
            indices[i + 4] = offset + 3;
            indices[i + 5] = offset + 0;
            offset += 4; // 4 discrete vertices per layout quad
        }
        quadEBO.uploadData(indices, GL_STATIC_DRAW);

        // Map low-level vertex layout offsets directly into the hardware pipeline
        quadVAO.setVertexAttrib(0, 3, vertexSizeFloats, 0); // a_Position (x, y, z)
        quadVAO.setVertexAttrib(1, 2, vertexSizeFloats, 3); // a_TexCoord (u, v)
        quadVAO.setVertexAttrib(2, 4, vertexSizeFloats, 5); // a_Color (r, g, b, a)
        quadVAO.setVertexAttrib(3, 1, vertexSizeFloats, 9); // a_TexIndex
        
        quadVAO.unbind();
    }

    @Override
    public void setClearColor(Vector4f color) {
        glClearColor(color.x, color.y, color.z, color.w);
    }

    @Override
    public void clear() {
        // Clear both channels simultaneously to avoid depth buffer trails artifacts
        glClear(GL_COLOR_BUFFER_BIT | GL_DEPTH_BUFFER_BIT);
    }

    @Override
    public void drawBatch(float[] vertices, int vertexCount, int indexCount) {
        if (indexCount == 0) return;

        quadVBO.bind();
        
        // Push ONLY the active initialized float slice into the VBO storage memory.
        // This isolates the data boundary and prevents driver-level memory drops.
        glBufferSubData(GL_ARRAY_BUFFER, 0, java.util.Arrays.copyOfRange(vertices, 0, vertexCount));

        quadVAO.bind();
        glDrawElements(GL_TRIANGLES, indexCount, GL_UNSIGNED_INT, 0);
        quadVAO.unbind();
    }

    @Override
    public void cleanup() {
        if (quadVAO != null) quadVAO.cleanup();
        if (quadVBO != null) quadVBO.cleanup();
        if (quadEBO != null) quadEBO.cleanup();
    }
}
