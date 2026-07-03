package com.aengine.graphics;

import com.aengine.graphics.opengl.OpenGLTexture;
import com.aengine.utils.Logger;
import java.util.concurrent.ConcurrentHashMap;

import org.lwjgl.openal.AL10;

/**
 * HARDWARE CONTEXT: VRAM CACHE & HOT-RELOAD ROUTER
 * Prevents redundant GPU allocations and routes OS-level mutation events to active hardware pointers.
 */
public class AssetManager {
    // Thread-safe map to handle async loading and main-thread reading simultaneously
    private static final ConcurrentHashMap<String, OpenGLTexture> textureCache = new ConcurrentHashMap<>();

    public static TextureAPI getTexture(String virtualPath) {
        // If it exists, return the pointer. If not, allocate and return.
        return textureCache.computeIfAbsent(virtualPath, OpenGLTexture::new);
    }

    public static void hotReloadTexture(String virtualPath) {
        OpenGLTexture texture = textureCache.get(virtualPath);
        if (texture != null) {
            texture.reload(virtualPath);
        } else {
            Logger.warn(Logger.System.ASSET, "Hot-Reload skipped. Texture not currently active in ECS: %s", virtualPath);
        }
    }

    /**
     * Maps a .aaud file directly to OpenAL via Zero-Copy (Memory Mapped File).
     * Returns the OpenAL buffer ID or -1 on failure.
     */
    public static int loadAudioBuffer(String vfsPath) {
        java.io.File file = com.aengine.utils.FileSystem.resolve(vfsPath);
        if (file == null || !file.exists()) {
            Logger.error(Logger.System.ASSET, "Audio asset not found: %s", vfsPath);
            return -1;
        }

        try (java.io.RandomAccessFile raf = new java.io.RandomAccessFile(file, "r");
             java.nio.channels.FileChannel channel = raf.getChannel()) {

            // Sands Memory-Map right into OpenAL without intermediate copies
            java.nio.MappedByteBuffer mbb = channel.map(java.nio.channels.FileChannel.MapMode.READ_ONLY, 0, channel.size());
            mbb.order(java.nio.ByteOrder.nativeOrder());

            int magic = mbb.getInt();
            if (magic != 0x41415544) { // "AAUD"
                Logger.error(Logger.System.ASSET, "Invalid .aaud magic number: %s", vfsPath);
                return -1;
            }

            int type = mbb.getInt(); // 0 = Raw PCM
            int channels = mbb.getInt();
            int sampleRate = mbb.getInt();
            int payloadSize = mbb.getInt();

            // Determine the OpenAL format (Note: Only mono sounds have 3D spatialization)
            int format = -1;
            if (channels == 1) format = org.lwjgl.openal.AL10.AL_FORMAT_MONO16;
            else if (channels == 2) format = org.lwjgl.openal.AL10.AL_FORMAT_STEREO16;

            if (format == -1) {
                Logger.error(Logger.System.ASSET, "Unsupported channel count (%d) in %s", channels, vfsPath);
                return -1;
            }

            // Slice the buffer to ignore the 20-byte header
            java.nio.ByteBuffer pcmData = mbb.slice();
            pcmData.limit(payloadSize);

            // Generate hardware buffer
            int bufferId = org.lwjgl.openal.AL10.alGenBuffers();
            org.lwjgl.openal.AL10.alBufferData(bufferId, format, pcmData, sampleRate);

            return bufferId;

        } catch (Exception e) {
            Logger.error(Logger.System.ASSET, "I/O Fault loading .aaud: %s", e.getMessage());
            return -1;
        }
    }

    public static void clear() {
        for (OpenGLTexture tex : textureCache.values()) {
            tex.cleanup();
        }
        textureCache.clear();
    }
}
