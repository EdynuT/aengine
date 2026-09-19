package com.aengine.graphics;

import static org.lwjgl.opengl.GL11.*;
import static org.lwjgl.opengl.GL30.*;

import com.aengine.utils.Logger;

public final class FrameBuffer {

    private int fboID = 0;
    private int textureID = 0;
    private int rboID = 0;

    private int width;
    private int height;

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
        invalidate(); // Recreate textures in hardware
    }

    public void invalidate() {
        if (fboID != 0) {
            cleanup();
        }

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
    }

    public void bind() {
        glBindFramebuffer(GL_FRAMEBUFFER, fboID);
        glViewport(0, 0, width, height);
    }

    public void unbind() {
        glBindFramebuffer(GL_FRAMEBUFFER, 0);
    }

    public void cleanup() {
        glDeleteFramebuffers(fboID);
        glDeleteTextures(textureID);
        glDeleteRenderbuffers(rboID);
    }

    public int getTextureID() { return textureID; }
}
