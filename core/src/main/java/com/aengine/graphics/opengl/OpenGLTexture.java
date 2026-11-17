package com.aengine.graphics.opengl;

import com.aengine.graphics.TextureAPI;
import com.aengine.utils.FileSystem;
import com.aengine.utils.Logger;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL13;
import org.lwjgl.opengl.GL30;
import org.lwjgl.stb.STBImage;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;
import java.io.File;
import java.nio.ByteBuffer;
import java.nio.IntBuffer;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * OpenGL implementation of {@link TextureAPI} for project image files, loaded in the
 * background. Obtain one through {@link com.aengine.graphics.AssetManager#getTexture(String)}.
 *
 * <p>Loading happens in three stages:</p>
 * <ol>
 *   <li>The constructor, on the GL thread, reserves the texture name and queues the file.</li>
 *   <li>A worker thread reads the file and decodes it to RGBA pixels in native memory:
 *       {@code .atex} files are used as stored; other formats go through stb_image,
 *       flipped so the first row is the bottom one.</li>
 *   <li>The first {@link #bind(int)} after decoding uploads the pixels, builds mipmaps and
 *       frees the native memory.</li>
 * </ol>
 *
 * <p>Until the first upload, {@code bind} binds a shared 1x1 white placeholder, so a sprite
 * shows its own colour rather than whatever texture was last on the slot, and the size reads
 * as 1x1. A file that is missing or cannot be decoded is logged and the texture stays in that
 * state. While a hot reload is decoding, the previous image stays bound. Sampling is
 * nearest-neighbour (crisp pixels) and the texture repeats outside 0..1.</p>
 *
 * <p>Pending pixels are handed between the decode worker, the GL thread and the asset
 * watcher's thread under the texture's own lock, so a decode that finishes after
 * {@link #cleanup()}, or a reload over pixels not yet uploaded, frees them instead of
 * leaking them.</p>
 */
public class OpenGLTexture implements TextureAPI {

    /*
     * MULTI-THREADED ASSET STREAMING POOL (DAEMONIZED)
     * A dedicated pool of CPU threads used strictly for background disk I/O and pixel decoding.
     * The ThreadFactory enforces Daemon status so the JVM can terminate gracefully without hanging.
     */
    private static final ExecutorService ASSET_STREAMING_POOL = Executors.newFixedThreadPool(4, runnable -> {
        Thread worker = new Thread(runnable);
        worker.setDaemon(true); // Fixes terminal lockup: JVM kills this thread when Main finishes
        worker.setName("AEngine-AssetWorker-" + worker.threadId());
        return worker;
    });

    // State Machine Flags for JIT (Just-In-Time) VRAM Upload
    private static final int STATE_LOADING = 0;
    private static final int STATE_DECODED = 1;
    private static final int STATE_READY   = 2;
    private static final int STATE_FAILED  = 3;

    private final int id;
    private final AtomicInteger uploadState = new AtomicInteger(STATE_LOADING);
    
    // Marked volatile to ensure cache visibility across Core Threads
    private volatile int width = 1;
    private volatile int height = 1;
    
    // Pending pixels: written and freed only under "synchronized (this)".
    private ByteBuffer decodedPixels = null;

    // Memory Tracking: Differentiates between MemoryUtil (ATEX) and STBImage (PNG) allocations
    private boolean isNativeAllocation = false;
    private ByteBuffer originalNativeBuffer = null;

    /** Set by {@link #cleanup()}; a decode finishing afterwards frees its pixels. Under the lock. */
    private boolean disposed = false;

    /** Whether an image has reached the GPU at least once; GL thread only. */
    private boolean hasImage = false;

    /** The shared 1x1 white texture bound while nothing has been uploaded; 0 until first needed. */
    private static int placeholderId = 0;

    /**
     * Reserves the texture on the GPU and queues the file for background loading.
     * Must be called on the GL thread.
     *
     * @param virtualPath virtual path of the image, resolved through
     *                    {@link com.aengine.utils.FileSystem#resolve(String)}
     */
    public OpenGLTexture(String virtualPath) {
        Logger.debug(Logger.System.ASSET, "Queuing async texture stream: %s", virtualPath);

        // 1. Allocate the hardware pointer immediately on the Main GL Thread
        this.id = GL11.glGenTextures();

        // 2. Offload Disk IO and Memory Decoding to background CPU cores
        ASSET_STREAMING_POOL.submit(() -> loadAndDecode(virtualPath));
    }

    /**
     * Executes entirely on a background thread. Safe from GL Context locks.
     */
    private void loadAndDecode(String virtualPath) {
        File file = FileSystem.resolve(virtualPath);
        if (!file.exists()) {
            Logger.error(Logger.System.ASSET, "Resource lookup failed for path target: %s", file.getAbsolutePath());
            uploadState.set(STATE_FAILED);
            return;
        }

        boolean isAtex = virtualPath.toLowerCase().endsWith(".atex");
        ByteBuffer rawData;
        
        try {
            // Allocate unmanaged memory and read the entire file block from disk
            rawData = FileSystem.ioResourceToBuffer(virtualPath, 8 * 1024);
        } catch (Exception e) {
            Logger.error(Logger.System.ASSET, "Disk IO breakdown processing byte stream: %s", virtualPath);
            uploadState.set(STATE_FAILED);
            return;
        }

        if (isAtex) {
            /*
             * HARDWARE CONTEXT: PROPRIETARY ZERO-DECODE PIPELINE (.atex)
             * Bypasses heavy CPU decompression algorithms (Huffman/Deflate).
             * Reads the 20-byte strict header and maps the remaining bytes directly to the GPU payload.
             */
            int magic = rawData.getInt();
            if (magic != 0x41544558) { // "ATEX" in Hex
                Logger.error(Logger.System.ASSET, "Corrupted .atex file (Magic Number mismatch): %s", virtualPath);
                MemoryUtil.memFree(rawData);
                uploadState.set(STATE_FAILED);
                return;
            }

            rawData.getInt(); // version (unused)
            int w = rawData.getInt();
            int h = rawData.getInt();
            rawData.getInt(); // channels (always 4, RGBA)

            // Slice the buffer: a view starting at position 20 (payload start); the base
            // buffer is kept to free the memory from its start later.
            publish(rawData.slice(), rawData, w, h);
        } else {
            /*
             * HARDWARE CONTEXT: LEGACY COMPRESSED PIPELINE (.png, .jpg)
             * Uses STBImage to decompress the asset into a raw pixel matrix.
             */
            try (MemoryStack stack = MemoryStack.stackPush()) {
                IntBuffer w        = stack.mallocInt(1);
                IntBuffer h        = stack.mallocInt(1);
                IntBuffer channels = stack.mallocInt(1);

                STBImage.stbi_set_flip_vertically_on_load(true);
                ByteBuffer pixels = STBImage.stbi_load_from_memory(rawData, w, h, channels, 4);

                // Free the compressed I/O buffer immediately
                MemoryUtil.memFree(rawData);

                if (pixels == null) {
                    Logger.error(Logger.System.ASSET, "Failed to decode legacy image data: %s. Reason: %s", virtualPath, STBImage.stbi_failure_reason());
                    uploadState.set(STATE_FAILED);
                    return;
                }

                publish(pixels, null, w.get(0), h.get(0));
            }
        }
    }

    /**
     * Hands decoded pixels to the GL thread, or frees them if the texture was cleaned up
     * meanwhile. Runs on the decode worker.
     *
     * @param pixels the RGBA payload
     * @param base   the buffer to free for an {@code .atex} payload, or {@code null} when
     *               {@code pixels} came from stb_image
     */
    private synchronized void publish(ByteBuffer pixels, ByteBuffer base, int w, int h) {
        if (disposed) {
            if (base != null) MemoryUtil.memFree(base.position(0));
            else              STBImage.stbi_image_free(pixels);
            return;
        }
        freeNativeMemory(); // pixels from an earlier decode that never reached the GPU
        this.width  = w;
        this.height = h;
        this.decodedPixels        = pixels;
        this.isNativeAllocation   = base != null;
        this.originalNativeBuffer = base;
        uploadState.set(STATE_DECODED);
    }

    @Override 
    public void bind(int slot) {
        if (uploadState.get() == STATE_DECODED) upload();

        GL13.glActiveTexture(GL13.GL_TEXTURE0 + slot);
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, hasImage ? id : placeholder());
    }

    /**
     * JIT (JUST-IN-TIME) VRAM UPLOAD
     * Executed on the GL thread the first time the renderer binds the texture after a decode.
     */
    private synchronized void upload() {
        if (uploadState.get() != STATE_DECODED || decodedPixels == null) return;
        
        // Injection of warning outside the Hot Path (Executes only once during JIT Upload)
        if (width > 4096 || height > 4096) {
            Logger.warn(Logger.System.ASSET, "Performance Warning: Texture [ID: %d] payload is heavy (%dx%d px). Consider utilizing the AEngine optimization pipeline to compress this asset into a native block format.", id, width, height);
        }
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, id);
        
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_S, GL11.GL_REPEAT);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_T, GL11.GL_REPEAT);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_NEAREST_MIPMAP_NEAREST);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_NEAREST);
        
        // Blit raw pixels directly to VRAM
        GL11.glTexImage2D(GL11.GL_TEXTURE_2D, 0, GL11.GL_RGBA8, width, height, 0, GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, decodedPixels);
        GL30.glGenerateMipmap(GL11.GL_TEXTURE_2D);

        // Hardware handover complete. Purge system RAM safely based on allocation origin.
        freeNativeMemory();
        
        uploadState.set(STATE_READY);
        hasImage = true;
        Logger.info(Logger.System.ASSET, "Texture JIT VRAM Upload completed. ID: %d [%dx%d]", id, width, height);
    }

    /** The shared white placeholder, created on first use. GL thread only. */
    private static int placeholder() {
        if (placeholderId == 0) {
            placeholderId = GL11.glGenTextures();
            GL11.glBindTexture(GL11.GL_TEXTURE_2D, placeholderId);
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_NEAREST);
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_NEAREST);
            ByteBuffer white = MemoryUtil.memAlloc(4);
            white.put((byte) 0xFF).put((byte) 0xFF).put((byte) 0xFF).put((byte) 0xFF).flip();
            GL11.glTexImage2D(GL11.GL_TEXTURE_2D, 0, GL11.GL_RGBA8, 1, 1, 0, GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, white);
            MemoryUtil.memFree(white);
        }
        return placeholderId;
    }
    
    /**
     * Dynamically routes the memory deallocation to the correct C-API bridge.
     * Called under the texture's lock.
     */
    private void freeNativeMemory() {
        if (decodedPixels != null) {
            if (isNativeAllocation && originalNativeBuffer != null) {
                // Free raw FileSystem buffer (ATEX)
                MemoryUtil.memFree(originalNativeBuffer.position(0));
                originalNativeBuffer = null;
            } else {
                // Free STB decoded buffer (PNG/JPG)
                STBImage.stbi_image_free(decodedPixels);
            }
            decodedPixels = null;
        }
    }

    /**
     * Re-submits the pipeline payload to the Thread Pool.
     * The Main Thread will automatically catch the STATE_DECODED flag and overwrite the VRAM block.
     *
     * <p>Ignored while a load is already running. Until the new pixels are uploaded, the
     * previous image stays bound. Pixels decoded but not yet uploaded are freed first.</p>
     *
     * @param virtualPath virtual path to load from; normally the one the texture was created with
     */
    public void reload(String virtualPath) {
        synchronized (this) {
            if (disposed || uploadState.get() == STATE_LOADING) return; // duplicate saves in the image editor
            freeNativeMemory();
            uploadState.set(STATE_LOADING);
        }
        Logger.info(Logger.System.ASSET, "Hot-Reload Signal caught. Re-streaming asset: %s", virtualPath);
        
        // Offloads decoding to the background cores.
        ASSET_STREAMING_POOL.submit(() -> loadAndDecode(virtualPath));
    }

    @Override 
    public void unbind() { 
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, 0); 
    }
    
    @Override public int  getWidth()  { return width; }
    @Override public int  getHeight() { return height; }
    @Override public int  getID()     { return id; }
    
    @Override 
    public void cleanup() {
        Logger.info(Logger.System.ASSET, "Releasing hardware storage resource for texture ID: %d", id);
        GL11.glDeleteTextures(id); 
        
        // A decode still running sees "disposed" when it finishes and frees its own pixels.
        synchronized (this) {
            disposed = true;
            freeNativeMemory();
        }
    }
}
