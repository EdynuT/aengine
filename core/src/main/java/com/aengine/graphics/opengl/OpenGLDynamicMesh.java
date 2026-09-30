package com.aengine.graphics.opengl;

import com.aengine.graphics.DynamicMeshAPI;
import com.aengine.graphics.VertexAttribute;
import com.aengine.utils.Logger;
import org.lwjgl.system.MemoryUtil;

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

    private final IntBuffer vertexStaging;
    private final IntBuffer indexStaging;

    /**
     * Reserves GPU storage and off-heap staging for the given capacities, and declares the
     * attribute layout. Must be called on the GL thread.
     *
     * @param maxVertexWords total capacity of the vertex buffer, in 4-byte words
     * @param maxIndices     total index capacity
     * @param layout         the attributes of one vertex, in location order —
     *                       {@code {FLOAT2, RGBA8}} declares a vec2 at location 0 and a
     *                       packed colour at location 1
     */
    public OpenGLDynamicMesh(int maxVertexWords, int maxIndices, VertexAttribute[] layout) {
        int stride = 0;
        for (VertexAttribute attribute : layout) stride += attribute.slots;

        vao = new OpenGLVAO();
        vbo = new OpenGLVBO();
        ebo = new OpenGLEBO();

        vao.bind();

        vbo.bind();
        glBufferData(GL_ARRAY_BUFFER, (long) maxVertexWords * Integer.BYTES, GL_DYNAMIC_DRAW);

        ebo.bind();
        glBufferData(GL_ELEMENT_ARRAY_BUFFER, (long) maxIndices * Integer.BYTES, GL_DYNAMIC_DRAW);

        int offset = 0;
        for (int location = 0; location < layout.length; location++) {
            VertexAttribute attribute = layout[location];

            if (attribute == VertexAttribute.RGBA8) {
                vao.setVertexAttribNormalizedBytes(location, stride, offset);
            } else {
                vao.setVertexAttrib(location, attribute.slots, stride, offset);
            }
            offset += attribute.slots;
        }

        vao.unbind();

        vertexStaging = MemoryUtil.memAllocInt(maxVertexWords);
        indexStaging  = MemoryUtil.memAllocInt(maxIndices);

        Logger.debug(Logger.System.RENDERER,
            "Dynamic mesh reserved: %d vertex words, %d indices, stride %d words.",
            maxVertexWords, maxIndices, stride);
    }

    @Override
    public void upload(int[] vertexWords, int vertexWordCount, int[] indices, int indexCount) {
        if (indexCount == 0) return;

        vertexStaging.clear();
        vertexStaging.put(vertexWords, 0, vertexWordCount);
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
