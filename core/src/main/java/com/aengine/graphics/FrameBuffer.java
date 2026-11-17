package com.aengine.graphics;

import static org.lwjgl.opengl.GL11.*;
import static org.lwjgl.opengl.GL30.*;

import com.aengine.utils.Logger;

/**
 * An off-screen render target: a colour texture plus a depth-and-stencil buffer.
 *
 * <p>The editor renders the scene into one and shows its colour texture inside the
 * viewport panel. Draw into it between {@link #bind()} and {@link #unbind()}; read the
 * result through {@link #getTextureID()}.</p>
 *
 * <p>This class calls OpenGL directly rather than going through {@link RenderContext},
 * so it is tied to the OpenGL backend. Every method must run on the GL thread.</p>
 */
public final class FrameBuffer {

    private int fboID = 0;
    private int textureID = 0;
    private int rboID = 0;

    private int width;
    private int height;

    /**
     * Creates the target and allocates its GPU storage.
     *
     * @param width  width in pixels; must be greater than 0
     * @param height height in pixels; must be greater than 0
     */
    public FrameBuffer(int width, int height) {
        this.width = width;
        this.height = height;
        invalidate();
    }

    /**
     * Returns the width.
     *
     * @return width in pixels
     */
    public int getWidth() { return width; }

    /**
     * Returns the height.
     *
     * @return height in pixels
     */
    public int getHeight() { return height; }

    /**
     * Changes the size, recreating the GPU storage. The old contents are lost and the
     * colour texture gets a new ID, so fetch {@link #getTextureID()} again afterwards.
     * Does nothing if the size is unchanged.
     *
     * @param newWidth  width in pixels; must be greater than 0
     * @param newHeight height in pixels; must be greater than 0
     */
    public void resize(int newWidth, int newHeight) {
        // Ignore if the resolution is the same to save CPU
        if (this.width == newWidth && this.height == newHeight) return;
        
        this.width = newWidth;
        this.height = newHeight;
        invalidate(); // Recreate textures in hardware
    }

    /**
     * Deletes the current GPU objects, if any, and creates new ones at the current size.
     * An incomplete framebuffer is logged as an error, not thrown. Leaves the default
     * framebuffer bound.
     */
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

    /** Directs drawing into this target and sets the viewport to its full size. */
    public void bind() {
        glBindFramebuffer(GL_FRAMEBUFFER, fboID);
        glViewport(0, 0, width, height);
    }

    /**
     * Directs drawing back to the window. The viewport is not restored; set it to the
     * window size before drawing there.
     */
    public void unbind() {
        glBindFramebuffer(GL_FRAMEBUFFER, 0);
    }

    /** Deletes the framebuffer, its colour texture and its depth buffer. */
    public void cleanup() {
        glDeleteFramebuffers(fboID);
        glDeleteTextures(textureID);
        glDeleteRenderbuffers(rboID);
    }

    /**
     * Returns the colour texture, for showing the rendered image, e.g. through
     * {@link RendererAPI#bindTexture(int, int)}.
     *
     * @return the OpenGL texture name; changes on every {@link #resize}
     */
    public int getTextureID() { return textureID; }
}
