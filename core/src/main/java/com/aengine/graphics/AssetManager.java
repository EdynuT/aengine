package com.aengine.graphics;

import com.aengine.graphics.opengl.OpenGLTexture;
import com.aengine.utils.Logger;
import java.util.concurrent.ConcurrentHashMap;

/**
 * HARDWARE CONTEXT: VRAM CACHE &amp; HOT-RELOAD ROUTER
 * Prevents redundant GPU allocations and routes OS-level mutation events to active hardware pointers.
 *
 * <p>Textures are cached by virtual path: every caller asking for the same path gets the
 * same texture. Audio buffers are not cached; each call loads a new one.</p>
 */
public class AssetManager {

    private AssetManager() {}

    // Thread-safe map to handle async loading and main-thread reading simultaneously
    private static final ConcurrentHashMap<String, OpenGLTexture> textureCache = new ConcurrentHashMap<>();

    /**
     * Returns the shared texture for a path, creating it and starting its background load
     * on the first request. Must be called on the GL thread.
     *
     * @param virtualPath virtual path of the image, e.g. {@code "assets://baked/textures/box.atex"}
     * @return the texture, possibly still loading (see {@link RenderContext#createTexture(String)});
     *         a missing file gives a texture that never loads and binds nothing
     */
    public static TextureAPI getTexture(String virtualPath) {
        // If it exists, return the pointer. If not, allocate and return.
        return textureCache.computeIfAbsent(virtualPath, OpenGLTexture::new);
    }

    /**
     * Reloads a cached texture from disk after its file changed. The texture object and
     * its ID stay the same, so everything holding it picks up the new pixels. Paths that
     * are not cached are skipped with a warning. Ignored while a load of that texture is
     * still running.
     *
     * @param virtualPath virtual path of the changed image
     */
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
     *
     * <p>A {@code .aaud} file is a 20-byte header (magic {@code "AAUD"}, format, channel
     * count, sample rate, payload size; five native-order ints) followed by 16-bit PCM
     * samples. Only mono and stereo are accepted, and only mono sounds are positioned in
     * 3D. Requires {@link com.aengine.audio.AudioDevice#init()} to have run.</p>
     *
     * @param vfsPath virtual path of the {@code .aaud} file
     * @return the new OpenAL buffer ID, or {@code -1} if the file is missing or invalid
     *         (the reason is logged). The caller owns the buffer.
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

            mbb.getInt(); // type: 0 = Raw PCM
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

    /**
     * Deletes every cached texture and empties the cache. Textures handed out earlier
     * become invalid, so clear only when nothing will draw them again, e.g. at shutdown.
     */
    public static void clear() {
        for (OpenGLTexture tex : textureCache.values()) {
            tex.cleanup();
        }
        textureCache.clear();
    }
}
