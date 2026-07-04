package com.aengine.graphics;

import static org.lwjgl.opengl.GL11.*;
import static org.lwjgl.opengl.GL15.*;
import static org.lwjgl.opengl.GL21.GL_PIXEL_PACK_BUFFER;
import static org.lwjgl.opengl.GL30.*;
import java.nio.ByteBuffer;

import com.aengine.utils.Logger;
import com.aengine.network.SharedMemory;

public final class FrameBuffer {

    private int fboID = 0;
    private int textureID = 0;
    private int rboID = 0;

    private int width;
    private int height;

    // PBO (Pixel Buffer Object) Double-Buffering
    private int[] pbo = new int[2];
    private int pboIndex = 0;
    private ByteBuffer cachedBufferWrapper = null;

    public FrameBuffer(int width, int height) {
        this.width = width;
        this.height = height;
        invalidate();
    }

    public int getWidth() { return width; }
    public int getHeight() { return height; }

    public void resize(int newWidth, int newHeight) {
        // Ignore if the resolution is the same to save CPU
        if (this.width == newWidth && this.height == newHeight) return;
        
        this.width = newWidth;
        this.height = newHeight;
        invalidate(); // Recreate textures and PBOs in hardware
    }

    public void invalidate() {
        if (fboID != 0) {
            cleanup();
        }

        int requiredSize = width * height * 4;

        // --- FBO SETUP ---
        fboID = glGenFramebuffers();
        glBindFramebuffer(GL_FRAMEBUFFER, fboID);

        textureID = glGenTextures();
        glBindTexture(GL_TEXTURE_2D, textureID);
        glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA, width, height, 0, GL_RGBA, GL_UNSIGNED_BYTE, 0);

        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_LINEAR);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_LINEAR);

        glFramebufferTexture2D(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, GL_TEXTURE_2D, textureID, 0);

        rboID = glGenRenderbuffers();
        glBindRenderbuffer(GL_RENDERBUFFER, rboID);
        glRenderbufferStorage(GL_RENDERBUFFER, GL_DEPTH24_STENCIL8, width, height);
        glFramebufferRenderbuffer(GL_FRAMEBUFFER, GL_DEPTH_STENCIL_ATTACHMENT, GL_RENDERBUFFER, rboID);

        if (glCheckFramebufferStatus(GL_FRAMEBUFFER) != GL_FRAMEBUFFER_COMPLETE) {
            Logger.error(Logger.System.RENDERER, "Hardware Framebuffer pipeline creation failed.");
        }
        glBindFramebuffer(GL_FRAMEBUFFER, 0);

        // --- PBO SETUP (Asynchronous DMA Transfers) ---
        pbo[0] = glGenBuffers();
        glBindBuffer(GL_PIXEL_PACK_BUFFER, pbo[0]);
        glBufferData(GL_PIXEL_PACK_BUFFER, requiredSize, GL_STREAM_READ);

        pbo[1] = glGenBuffers();
        glBindBuffer(GL_PIXEL_PACK_BUFFER, pbo[1]);
        glBufferData(GL_PIXEL_PACK_BUFFER, requiredSize, GL_STREAM_READ);

        glBindBuffer(GL_PIXEL_PACK_BUFFER, 0);
    }

    public void bind() {
        glBindFramebuffer(GL_FRAMEBUFFER, fboID);
        glViewport(0, 0, width, height);
    }

    public void unbind() {
        glBindFramebuffer(GL_FRAMEBUFFER, 0);
    }

    public void dispatchToSharedMemory() {
        bind();

        // 1. Initiates reading from VRAM to the current PBO.
        // The offset is 0L. 'Cause the PBO is already bound, so OpenGL will write directly into it asynchronously.
        glBindBuffer(GL_PIXEL_PACK_BUFFER, pbo[pboIndex]);
        glReadPixels(0, 0, width, height, GL_RGBA, GL_UNSIGNED_BYTE, 0L);

        // 2. Processes the PBO from the previous frame (which the GPU has already transferred asynchronously in the background)
        int nextIndex = (pboIndex + 1) % 2;
        glBindBuffer(GL_PIXEL_PACK_BUFFER, pbo[nextIndex]);

        // Maps the PBO to RAM. We reuse the "cachedBufferWrapper" to avoid Garbage Collection (Zero-GC).
        cachedBufferWrapper = glMapBuffer(GL_PIXEL_PACK_BUFFER, GL_READ_ONLY, cachedBufferWrapper);

        if (cachedBufferWrapper != null) {
            SharedMemory.writePixels(cachedBufferWrapper, width, height);
            glUnmapBuffer(GL_PIXEL_PACK_BUFFER);
        }

        // Resets the state to avoid affecting the rest of the engine
        glBindBuffer(GL_PIXEL_PACK_BUFFER, 0);
        unbind();

        pboIndex = nextIndex;
    }

    public void cleanup() {
        glDeleteFramebuffers(fboID);
        glDeleteTextures(textureID);
        glDeleteRenderbuffers(rboID);
        glDeleteBuffers(pbo[0]);
        glDeleteBuffers(pbo[1]);
    }

    public int getTextureID() { return textureID; }
}
