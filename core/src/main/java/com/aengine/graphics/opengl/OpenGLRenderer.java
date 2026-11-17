package com.aengine.graphics.opengl;

import com.aengine.graphics.RendererAPI;
import com.aengine.utils.Logger;
import org.joml.Vector4f;
import org.lwjgl.system.MemoryUtil;

import java.nio.FloatBuffer;

import static org.lwjgl.opengl.GL11.*;
import static org.lwjgl.opengl.GL13.GL_TEXTURE0;
import static org.lwjgl.opengl.GL13.glActiveTexture;
import static org.lwjgl.opengl.GL15.*;

/**
 * OpenGL implementation of {@link RendererAPI}. Obtain it through
 * {@link com.aengine.graphics.RenderContext#createRenderer()} rather than directly.
 */
public class OpenGLRenderer implements RendererAPI {

    /** Creates the renderer; nothing touches the GPU until {@link #init()}. */
    public OpenGLRenderer() {}

    private OpenGLVAO quadVAO;
    private OpenGLVBO quadVBO;
    private OpenGLEBO quadEBO;

    /**
     * Persistent off-heap staging buffer for vertex uploads.
     *
     * <p>Allocated once in {@link #initBatchBuffers} and rewritten in place every draw.
     * Building a fresh {@code float[]} per draw call would allocate on the hottest path in
     * the engine — at a full batch that is 40,000 floats, 160 KB, every flush of every
     * frame.</p>
     */
    private FloatBuffer vertexStaging;

    /** Height of the current render target, needed to flip scissor into GL's coordinates. */
    private int targetHeight = 0;

    @Override
    public void init() {
        // Enable hardware-level depth testing for structural 3D rendering
        glEnable(GL_DEPTH_TEST);
        glDepthFunc(GL_LESS);
    }

    @Override
    public void initBatchBuffers(int maxVertices, int maxIndices, int vertexSizeFloats) {
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

        // Reallocating on a second call would leak the previous block.
        if (vertexStaging != null) MemoryUtil.memFree(vertexStaging);
        vertexStaging = MemoryUtil.memAllocFloat(maxVertices * vertexSizeFloats);

        Logger.debug(Logger.System.RENDERER,
            "Vertex staging buffer reserved off-heap: %d floats (%.1f KB).",
            vertexStaging.capacity(), (vertexStaging.capacity() * Float.BYTES) / 1024.0f);
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

        // Copy the active slice straight into the off-heap buffer and hand the driver that
        // buffer. No intermediate array, so no allocation on the draw path.
        vertexStaging.clear();
        vertexStaging.put(vertices, 0, vertexCount);
        vertexStaging.flip();
        glBufferSubData(GL_ARRAY_BUFFER, 0, vertexStaging);

        quadVAO.bind();
        glDrawElements(GL_TRIANGLES, indexCount, GL_UNSIGNED_INT, 0);
        quadVAO.unbind();
    }

    // -------------------------------------------------------------------------------------
    // Render state
    // -------------------------------------------------------------------------------------

    @Override
    public void setDepthTest(boolean enabled) {
        if (enabled) glEnable(GL_DEPTH_TEST);
        else         glDisable(GL_DEPTH_TEST);
    }

    @Override
    public void setBlend(boolean enabled) {
        if (enabled) {
            glEnable(GL_BLEND);
            glBlendFunc(GL_SRC_ALPHA, GL_ONE_MINUS_SRC_ALPHA);
        } else {
            glDisable(GL_BLEND);
        }
    }

    @Override
    public void setRenderTargetSize(int width, int height) {
        this.targetHeight = height;
    }

    @Override
    public void setScissor(int x, int y, int width, int height) {
        // GL measures from the bottom-left; the interface hands us top-left coordinates.
        int glY = targetHeight - (y + height);

        glEnable(GL_SCISSOR_TEST);
        glScissor(x, glY, Math.max(0, width), Math.max(0, height));
    }

    @Override
    public void disableScissor() {
        glDisable(GL_SCISSOR_TEST);
    }

    @Override
    public void bindTexture(int slot, int textureHandle) {
        glActiveTexture(GL_TEXTURE0 + slot);
        glBindTexture(GL_TEXTURE_2D, textureHandle);
    }

    @Override
    public void cleanup() {
        if (quadVAO != null) quadVAO.cleanup();
        if (quadVBO != null) quadVBO.cleanup();
        if (quadEBO != null) quadEBO.cleanup();

        if (vertexStaging != null) {
            MemoryUtil.memFree(vertexStaging);
            vertexStaging = null;
        }
    }
}
