package com.aengine.network;

import com.aengine.utils.Logger;
import java.io.File;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.nio.MappedByteBuffer;
import java.nio.channels.FileChannel;

/**
 * HARDWARE CONTEXT: ZERO-COPY SHARED MEMORY BRIDGE
 * Maps a raw memory file (RAW RGBA) directly into the Operating System's RAM
 * (/dev/shm on Linux or Temp on Windows). Allows the frontend (Tauri/Rust) to read
 * the game's pixels at 60+ FPS with absolute zero latency and no TCP socket overhead.
 */
public final class SharedMemory {

    private static FileChannel channel;
    private static MappedByteBuffer mappedBuffer;
    private static int currentBufferSize = 0;
    private static File shmFile;

    private SharedMemory() {}

    /**
     * Initializes the shared memory bridge by creating a temporary file in RAM and mapping it to a ByteBuffer.
     * @param width The width of the viewport in pixels.
     * @param height The height of the viewport in pixels.
     */
    public static void init(int width, int height) {
        try {
            String os = System.getProperty("os.name").toLowerCase();
            // On Linux, use pure RAM. On Windows, use the user's temporary folder.
            String basePath = os.contains("linux") ? "/dev/shm" : System.getProperty("java.io.tmpdir");
            
            shmFile = new File(basePath, "aengine_viewport.raw");

            // 4 bytes per pixel (Red, Green, Blue, Alpha)
            currentBufferSize = width * height * 4;

            try (RandomAccessFile raf = new RandomAccessFile(shmFile, "rw")) {
                raf.setLength(currentBufferSize); // Pre-allocate the file in RAM
                channel = raf.getChannel();
            }

            // Maps the file directly to Java's native memory (outside the Heap)
            mappedBuffer = channel.map(FileChannel.MapMode.READ_WRITE, 0, currentBufferSize);

            Logger.info(Logger.System.CORE, "Shared Memory Bridge established at: %s (%.2f MB)", 
                        shmFile.getAbsolutePath(), (currentBufferSize / 1024.0f / 1024.0f));
        } catch (Exception e) {
            Logger.error(Logger.System.CORE, "Failed to initialize Shared Memory: %s", e.getMessage());
        }
    }

    /**
     * Dumps the pixels extracted from OpenGL directly into the shared RAM.
     * @param pixels The ByteBuffer containing the pixel data.
     * @param width The width of the viewport in pixels.
     * @param height The height of the viewport in pixels.
     */
    public static void writePixels(ByteBuffer pixels, int width, int height) {
        int requiredSize = width * height * 4;
        
        // If the viewport has been resized, the shared memory bridge needs to be rebuilt
        if (requiredSize != currentBufferSize) {
            Logger.info(Logger.System.CORE, "Viewport resized (%dx%d). Rebuilding Shared Memory bridge...", width, height);
            cleanup();
            init(width, height);
        }

        if (mappedBuffer != null) {
            mappedBuffer.clear(); // Reset the destination cursor
            pixels.clear();       // Absolute guarantee of resetting the source cursor
            mappedBuffer.put(pixels); // Copy memory at DDR5 speeds
        }
    }

    /**
     * Cleans up the memory pointer and deletes the ghost file from RAM.
     */
    public static void cleanup() {
        try {
            if (channel != null) channel.close();
            if (shmFile != null && shmFile.exists()) shmFile.delete();
        } catch (Exception e) {
            Logger.error(Logger.System.CORE, "Error cleaning up Shared Memory: %s", e.getMessage());
        }
    }
}
