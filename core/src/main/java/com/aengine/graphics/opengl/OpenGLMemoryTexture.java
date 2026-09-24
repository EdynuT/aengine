package com.aengine.graphics.opengl;

import com.aengine.graphics.TextureAPI;
import com.aengine.graphics.TextureFormat;
import com.aengine.utils.Logger;

import java.nio.ByteBuffer;

import static org.lwjgl.opengl.GL11.*;
import static org.lwjgl.opengl.GL12.GL_CLAMP_TO_EDGE;
import static org.lwjgl.opengl.GL13.GL_TEXTURE0;
import static org.lwjgl.opengl.GL13.glActiveTexture;
import static org.lwjgl.opengl.GL30.GL_R8;

/**
 * A texture uploaded synchronously from pixels already in memory.
 *
 * <p>Separate from {@link OpenGLTexture} on purpose. That class streams project assets from
 * disk on a worker pool and uploads them on first use; this one exists for textures the
 * engine generates itself, such as a glyph atlas, where the pixels are already in hand and
 * there is nothing to load. Keeping them apart leaves the asset pipeline untouched.</p>
 *
 * <p>Must be created on the GL thread. The pixels are copied to the GPU in the constructor,
 * so the caller keeps ownership of the buffer and may free it immediately after.</p>
 */
public final class OpenGLMemoryTexture implements TextureAPI {

    private final int id;
    private final int width;
    private final int height;

    public OpenGLMemoryTexture(int width, int height, TextureFormat format, ByteBuffer pixels) {
        this.width  = width;
        this.height = height;
        this.id     = glGenTextures();

        glBindTexture(GL_TEXTURE_2D, id);

        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_LINEAR);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_LINEAR);

        int internalFormat = (format == TextureFormat.R8) ? GL_R8  : GL_RGBA8;
        int pixelFormat    = (format == TextureFormat.R8) ? GL_RED : GL_RGBA;

        // GL assumes each pixel row starts on a 4-byte boundary. A one-byte-per-pixel image
        // whose width is not a multiple of 4 breaks that, and every row after the first
        // would be read skewed. Relax the alignment for the upload, then restore the default.
        glPixelStorei(GL_UNPACK_ALIGNMENT, 1);
        glTexImage2D(GL_TEXTURE_2D, 0, internalFormat, width, height, 0,
                     pixelFormat, GL_UNSIGNED_BYTE, pixels);
        glPixelStorei(GL_UNPACK_ALIGNMENT, 4);

        glBindTexture(GL_TEXTURE_2D, 0);

        Logger.debug(Logger.System.RENDERER,
            "Memory texture uploaded. ID: %d [%dx%d %s]", id, width, height, format);
    }

    @Override
    public void bind(int slot) {
        glActiveTexture(GL_TEXTURE0 + slot);
        glBindTexture(GL_TEXTURE_2D, id);
    }

    @Override public void unbind()    { glBindTexture(GL_TEXTURE_2D, 0); }
    @Override public int  getWidth()  { return width; }
    @Override public int  getHeight() { return height; }
    @Override public int  getID()     { return id; }

    @Override
    public void cleanup() {
        glDeleteTextures(id);
    }
}
