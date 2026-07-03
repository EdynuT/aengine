package com.aengine.utils;

import org.lwjgl.stb.STBImage;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.stb.STBVorbis;
import org.lwjgl.system.MemoryUtil;

import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioInputStream;
import javax.sound.sampled.AudioSystem;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.ByteOrder;
import java.nio.ByteBuffer;
import java.nio.ShortBuffer;
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
    public static final int MAGIC_NUMBER_AUDIO = 0x41415544; 
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
                        if (compileTexture(file, rootSourceDir, rootTargetDir)) successTracker.incrementAndGet();
                        else failTracker.incrementAndGet();
                    });
                } else if (name.endsWith(".ogg") || name.endsWith(".wav")) {
                    pool.submit(() -> {
                        if (compileAudio(file, rootSourceDir, rootTargetDir)) successTracker.incrementAndGet();
                        else failTracker.incrementAndGet();
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

    public static boolean compileAudio(File sourceFile, File rootSourceDir, File rootTargetDir) {
        String relativePath = sourceFile.getAbsolutePath().substring(rootSourceDir.getAbsolutePath().length());
        String targetExtensionPath = relativePath.substring(0, relativePath.lastIndexOf('.')) + ".aaud";
        File outputFile = new File(rootTargetDir, targetExtensionPath);
        outputFile.getParentFile().mkdirs();

        String name = sourceFile.getName().toLowerCase();

        if (name.endsWith(".ogg")) {
            // OGG → STBVorbis decode into unmanaged native memory
            try (MemoryStack stack = MemoryStack.stackPush()) {
                IntBuffer channels   = stack.mallocInt(1);
                IntBuffer sampleRate = stack.mallocInt(1);

                ShortBuffer pcm = STBVorbis.stb_vorbis_decode_filename(
                    sourceFile.getAbsolutePath(), channels, sampleRate);

                if (pcm == null) {
                    Logger.error(Logger.System.ASSET, "Vorbis decode failure on %s", sourceFile.getName());
                    return false;
                }

                int numChannels = channels.get(0);
                int sRate       = sampleRate.get(0);
                int payloadSize = pcm.capacity() * 2; // 2 bytes per 16-bit short sample

                try (FileOutputStream fos = new FileOutputStream(outputFile);
                     FileChannel channel = fos.getChannel()) {
                    channel.write(buildAudHeader(numChannels, sRate, payloadSize));
                    // Zero-Copy Blit: cast native ShortBuffer pointer directly to ByteBuffer
                    channel.write(MemoryUtil.memByteBuffer(MemoryUtil.memAddress(pcm), payloadSize));
                }

                org.lwjgl.system.libc.LibCStdlib.free(pcm);
                return true;

            } catch (Exception e) {
                Logger.error(Logger.System.ASSET, "I/O Fault writing .aaud from OGG: %s", e.getMessage());
                return false;
            }

        } else if (name.endsWith(".wav")) {
            // WAV → Java AudioSystem decode with Auto-Conversion
            try (AudioInputStream baseAis = AudioSystem.getAudioInputStream(sourceFile)) {
                AudioFormat baseFormat = baseAis.getFormat();
                AudioInputStream decodedAis = baseAis;

                // Auto-convert to 16-bit PCM Signed (Little-Endian) if the source is 8-bit, 24-bit, or 32-bit float
                if (baseFormat.getEncoding() != AudioFormat.Encoding.PCM_SIGNED || baseFormat.getSampleSizeInBits() != 16) {
                    AudioFormat targetFormat = new AudioFormat(
                        AudioFormat.Encoding.PCM_SIGNED,
                        baseFormat.getSampleRate(),
                        16, // Force 16-bit
                        baseFormat.getChannels(),
                        baseFormat.getChannels() * 2, // Frame size (channels * 2 bytes)
                        baseFormat.getSampleRate(),
                        false // Little-endian (standard for our native buffer)
                    );

                    if (AudioSystem.isConversionSupported(targetFormat, baseFormat)) {
                        decodedAis = AudioSystem.getAudioInputStream(targetFormat, baseAis);
                    } else {
                        Logger.warn(Logger.System.ASSET, "Bake skipped: WAV format unsupported and cannot be auto-converted -> %s", sourceFile.getName());
                        return false;
                    }
                }

                int    numChannels = decodedAis.getFormat().getChannels();
                int    sRate       = (int) decodedAis.getFormat().getSampleRate();
                byte[] audioBytes  = decodedAis.readAllBytes();
                int    payloadSize = audioBytes.length;

                try (FileOutputStream fos = new FileOutputStream(outputFile);
                     FileChannel channel = fos.getChannel()) {
                    channel.write(buildAudHeader(numChannels, sRate, payloadSize));
                    ByteBuffer payload = ByteBuffer.allocateDirect(payloadSize);
                    payload.put(audioBytes).flip();
                    channel.write(payload);
                }

                // Close the converted stream if a new one was created
                if (decodedAis != baseAis) {
                    decodedAis.close();
                }
                return true;

            } catch (Exception e) {
                Logger.error(Logger.System.ASSET, "I/O Fault writing .aaud from WAV: %s", e.getMessage());
                return false;
            }
        }

        Logger.warn(Logger.System.ASSET, "Unsupported audio format: %s", sourceFile.getName());
        return false;
    }

    /** Builds the 20-byte AAUD header common to both OGG and WAV baked files. */
    private static ByteBuffer buildAudHeader(int numChannels, int sampleRate, int payloadSize) {
        // Header layout (20 bytes): Magic(4) + Type(4) + Channels(4) + SampleRate(4) + PayloadLength(4)
        ByteBuffer header = ByteBuffer.allocateDirect(20).order(ByteOrder.nativeOrder());
        header.putInt(MAGIC_NUMBER_AUDIO);
        header.putInt(0); // Type 0 = Raw PCM
        header.putInt(numChannels);
        header.putInt(sampleRate);
        header.putInt(payloadSize);
        return header.flip();
    }
}
