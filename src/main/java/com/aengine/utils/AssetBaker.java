package com.aengine.utils;

import org.lwjgl.stb.STBImage;
import org.lwjgl.system.MemoryStack;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.ByteOrder;
import java.nio.ByteBuffer;
import java.nio.IntBuffer;
import java.nio.channels.FileChannel;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * HARDWARE CONTEXT: MULTI-THREADED OFFLINE ASSET PIPELINE
 * Compiles compressed source images into contiguous, zero-decode binary payloads (.atex).
 * Utilizes NIO FileChannels for direct native-to-disk blitting, bypassing Java Heap allocations.
 */
public final class AssetBaker {

    // "ATEX" encoded in ASCII Hex
    public static final int MAGIC_NUMBER = 0x41544558; 
    public static final int VERSION = 1;

    private AssetBaker() {}

    /**
     * Recursively scans a source directory and bakes all valid formats into the target VFS directory.
     */
    public static void bakeDirectory(String sourceDirPath, String targetDirPath) {
        File sourceDir = new File(sourceDirPath);
        if (!sourceDir.exists() || !sourceDir.isDirectory()) {
            Logger.error(Logger.System.ASSET, "Bake Pipeline aborted: Source directory invalid -> %s", sourceDirPath);
            return;
        }

        // Saturates hardware cores for parallel decoding (Optimal for 8C/16T processors)
        int optimalThreads = Runtime.getRuntime().availableProcessors();
        ExecutorService compilerPool = Executors.newFixedThreadPool(optimalThreads);
        AtomicInteger successCount = new AtomicInteger(0);
        AtomicInteger failCount = new AtomicInteger(0);

        long startTime = System.nanoTime();
        Logger.info(Logger.System.ASSET, "Engaging Multi-Threaded Asset Baker. Allocated threads: %d", optimalThreads);

        scanAndSubmit(sourceDir, sourceDir, new File(targetDirPath), compilerPool, successCount, failCount);

        compilerPool.shutdown();
        try {
            compilerPool.awaitTermination(60, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Logger.error(Logger.System.ASSET, "Bake Pipeline interrupted forcefully.");
        }

        long elapsedMs = (System.nanoTime() - startTime) / 1_000_000;
        Logger.info(Logger.System.ASSET, "Bake Pipeline Complete. Handled %d assets (%d failed) in %d ms.", 
            successCount.get(), failCount.get(), elapsedMs);
    }

    private static void scanAndSubmit(File currentDir, File rootSourceDir, File rootTargetDir, 
                                      ExecutorService pool, AtomicInteger successTracker, AtomicInteger failTracker) {
        File[] files = currentDir.listFiles();
        if (files == null) return;

        for (File file : files) {
            if (file.isDirectory()) {
                scanAndSubmit(file, rootSourceDir, rootTargetDir, pool, successTracker, failTracker);
            } else {
                String name = file.getName().toLowerCase();
                if (name.endsWith(".png") || name.endsWith(".jpg") || name.endsWith(".jpeg")) {
                    pool.submit(() -> {
                        if (compileTexture(file, rootSourceDir, rootTargetDir)) {
                            successTracker.incrementAndGet();
                        } else {
                            failTracker.incrementAndGet();
                        }
                    });
                }
            }
        }
    }

    public static boolean compileTexture(File sourceFile, File rootSourceDir, File rootTargetDir) {
        // Replicate internal folder hierarchy in the target VFS
        String relativePath = sourceFile.getAbsolutePath().substring(rootSourceDir.getAbsolutePath().length());
        String targetExtensionPath = relativePath.substring(0, relativePath.lastIndexOf('.')) + ".atex";
        File outputFile = new File(rootTargetDir, targetExtensionPath);
        
        outputFile.getParentFile().mkdirs();

        try (MemoryStack stack = MemoryStack.stackPush()) {
            IntBuffer w = stack.mallocInt(1);
            IntBuffer h = stack.mallocInt(1);
            IntBuffer channels = stack.mallocInt(1);

            // Mandatory OpenGL alignment
            STBImage.stbi_set_flip_vertically_on_load(true);
            
            // Decodes into unmanaged native memory
            ByteBuffer pixels = STBImage.stbi_load(sourceFile.getAbsolutePath(), w, h, channels, 4);

            if (pixels == null) {
                Logger.error(Logger.System.ASSET, "Decode failure on %s: %s", sourceFile.getName(), STBImage.stbi_failure_reason());
                return false;
            }

            int width = w.get(0);
            int height = h.get(0);
            int payloadSize = width * height * 4;

            // NIO direct disk blitting. Bypasses Java Heap overhead.
            try (FileOutputStream fos = new FileOutputStream(outputFile);
                 FileChannel channel = fos.getChannel()) {
                
                // Enforce native CPU endianness (Little-Endian on x86_64) to match the unmanaged C-API read execution in OpenGLTexture.
                ByteBuffer header = ByteBuffer.allocateDirect(20).order(ByteOrder.nativeOrder());
                header.putInt(MAGIC_NUMBER);
                header.putInt(VERSION);
                header.putInt(width);
                header.putInt(height);
                header.putInt(4); // RGBA format
                header.flip();

                // Sequential write: Header -> Payload
                channel.write(header);
                channel.write(pixels);
            }

            STBImage.stbi_image_free(pixels.position(0));
            return true;

        } catch (Exception e) {
            Logger.error(Logger.System.ASSET, "I/O Fault writing .atex binary: %s", e.getMessage());
            return false;
        }
    }
}
