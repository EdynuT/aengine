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

public class OpenGLTexture implements TextureAPI {

    /*
     * MULTI-THREADED ASSET STREAMING POOL (DAEMONIZED)
     * A dedicated pool of CPU threads used strictly for background disk I/O and pixel decoding.
     * The ThreadFactory enforces Daemon status so the JVM can terminate gracefully without hanging.
     */
    private static final ExecutorService ASSET_STREAMING_POOL = Executors.newFixedThreadPool(4, runnable -> {
        Thread worker = new Thread(runnable);
        worker.setDaemon(true); // Fixes terminal lockup: JVM kills this thread when Main finishes
        worker.setName("AEngine-AssetWorker-" + worker.getId());
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
    
    private volatile ByteBuffer decodedPixels = null;
    
    // Memory Tracking: Differentiates between MemoryUtil (ATEX) and STBImage (PNG) allocations
    private volatile boolean isNativeAllocation = false;
    private volatile ByteBuffer originalNativeBuffer = null;

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

            int version = rawData.getInt();
            this.width = rawData.getInt();
            this.height = rawData.getInt();
            int channels = rawData.getInt(); // Always 4 (RGBA)

            // Slice the buffer: creates a new ByteBuffer view starting at position 20 (payload start)
            this.decodedPixels = rawData.slice(); 
            this.isNativeAllocation = true;
            this.originalNativeBuffer = rawData; // Kept to correctly free the memory base pointer later

            uploadState.set(STATE_DECODED);
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

                this.width  = w.get(0);
                this.height = h.get(0);
                this.decodedPixels = pixels;
                this.isNativeAllocation = false; // STB allocation flag

                uploadState.set(STATE_DECODED);
            }
        }
    }

    @Override 
    public void bind(int slot) {
        int currentState = uploadState.get();

        if (currentState == STATE_FAILED || currentState == STATE_LOADING) {
            return; 
        }

        /*
         * JIT (JUST-IN-TIME) VRAM UPLOAD
         * Executed on the Main Thread the very first time the Renderer requests this texture.
         */
        if (currentState == STATE_DECODED && decodedPixels != null) {
            
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
            Logger.info(Logger.System.ASSET, "Texture JIT VRAM Upload completed. ID: %d [%dx%d]", id, width, height);
        }

        if (uploadState.get() == STATE_READY) {
            GL13.glActiveTexture(GL13.GL_TEXTURE0 + slot);
            GL11.glBindTexture(GL11.GL_TEXTURE_2D, id);
        }
    }
    
    /**
     * Dynamically routes the memory deallocation to the correct C-API bridge.
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
     */
    public void reload(String virtualPath) {
        if (uploadState.get() == STATE_LOADING) return; // Prevent race conditions from duplicate saves in the image editor
        
        uploadState.set(STATE_LOADING);
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
        
        // Prevent memory leaks if engine destroys texture before background thread finishes
        freeNativeMemory();
    }
}
