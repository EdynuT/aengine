package com.aengine.graphics.opengl;

import com.aengine.graphics.DynamicMeshAPI;
import com.aengine.utils.Logger;
import org.lwjgl.system.MemoryUtil;

import java.nio.FloatBuffer;
import java.nio.IntBuffer;

import static org.lwjgl.opengl.GL11.GL_TRIANGLES;
import static org.lwjgl.opengl.GL11.GL_UNSIGNED_INT;
import static org.lwjgl.opengl.GL11.glDrawElements;
import static org.lwjgl.opengl.GL15.*;

/**
 * OpenGL implementation of {@link DynamicMeshAPI}: a VAO over a stream-updated VBO and EBO.
 *
 * <p>Both staging buffers live off-heap and are written in place, so a frame of interface
 * geometry reaches the driver without allocating.</p>
 */
public final class OpenGLDynamicMesh implements DynamicMeshAPI {

    private final OpenGLVAO vao;
    private final OpenGLVBO vbo;
    private final OpenGLEBO ebo;

    private final FloatBuffer vertexStaging;
    private final IntBuffer   indexStaging;

    /**
     * @param maxVertexFloats total float capacity of the vertex buffer
     * @param maxIndices      total index capacity
     * @param attributeSizes  component count of each vertex attribute, in layout order —
     *                        {@code {2, 4}} declares a vec2 at location 0 and a vec4 at 1
     */
    public OpenGLDynamicMesh(int maxVertexFloats, int maxIndices, int[] attributeSizes) {
        int stride = 0;
        for (int size : attributeSizes) stride += size;

        vao = new OpenGLVAO();
        vbo = new OpenGLVBO();
        ebo = new OpenGLEBO();

        vao.bind();

        vbo.bind();
        glBufferData(GL_ARRAY_BUFFER, (long) maxVertexFloats * Float.BYTES, GL_DYNAMIC_DRAW);

        ebo.bind();
        glBufferData(GL_ELEMENT_ARRAY_BUFFER, (long) maxIndices * Integer.BYTES, GL_DYNAMIC_DRAW);

        int offset = 0;
        for (int location = 0; location < attributeSizes.length; location++) {
            vao.setVertexAttrib(location, attributeSizes[location], stride, offset);
            offset += attributeSizes[location];
        }

        vao.unbind();

        vertexStaging = MemoryUtil.memAllocFloat(maxVertexFloats);
        indexStaging  = MemoryUtil.memAllocInt(maxIndices);

        Logger.debug(Logger.System.RENDERER,
            "Dynamic mesh reserved: %d vertex floats, %d indices, stride %d floats.",
            maxVertexFloats, maxIndices, stride);
    }

    @Override
    public void upload(float[] vertices, int vertexFloatCount, int[] indices, int indexCount) {
        if (indexCount == 0) return;

        vertexStaging.clear();
        vertexStaging.put(vertices, 0, vertexFloatCount);
        vertexStaging.flip();

        indexStaging.clear();
        indexStaging.put(indices, 0, indexCount);
        indexStaging.flip();

        vbo.bind();
        glBufferSubData(GL_ARRAY_BUFFER, 0, vertexStaging);

        ebo.bind();
        glBufferSubData(GL_ELEMENT_ARRAY_BUFFER, 0, indexStaging);
    }

    @Override
    public void draw(int indexOffset, int indexCount) {
        if (indexCount == 0) return;

        vao.bind();
        glDrawElements(GL_TRIANGLES, indexCount, GL_UNSIGNED_INT,
                       (long) indexOffset * Integer.BYTES);
        vao.unbind();
    }

    @Override
    public void cleanup() {
        vao.cleanup();
        vbo.cleanup();
        ebo.cleanup();

        MemoryUtil.memFree(vertexStaging);
        MemoryUtil.memFree(indexStaging);
    }
}
