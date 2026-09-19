package com.aengine.utils;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.FileInputStream;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Path;
import java.nio.file.Paths;
import org.lwjgl.system.MemoryUtil;

public final class FileSystem {

    private static Path projectRootPath = null;

    private FileSystem() {}

    /**
     * Mounts the absolute game project directory context into the Virtual File System.
     * @param absolutePath The physical root directory of the game project.
     */
    public static void mountProject(String absolutePath) {
        File root = new File(absolutePath);
        if (!root.exists() || !root.isDirectory()) {
            Logger.error(Logger.System.CORE, "VFS Mount Fault: Path does not exist or is not a directory -> %s", absolutePath);
            throw new IllegalArgumentException("Invalid project root path.");
        }
        
        projectRootPath = Paths.get(root.getAbsolutePath()).normalize();
        Logger.info(Logger.System.CORE, "Virtual File System securely mounted at: %s", projectRootPath);
    }

    /**
     * Resolves an engine virtual path (e.g., "assets://textures/wall.png") into a normalized,
     * sandboxed java.io.File handle.
     */
    public static File resolve(String virtualPath) {
        if (projectRootPath == null) {
            Logger.error(Logger.System.CORE, "VFS Violation: Attempted to resolve path before mounting project context.");
            throw new IllegalStateException("Virtual File System is not mounted.");
        }

        String sanitized = virtualPath.trim();
        Path resolvedPath;

        if (sanitized.startsWith("assets://")) {
            String relative = sanitized.substring("assets://".length());
            resolvedPath = projectRootPath.resolve("assets").resolve(relative).normalize();
        } else {
            resolvedPath = projectRootPath.resolve(sanitized).normalize();
        }

        // Security Sandbox Check: Protect core filesystem against Directory Traversal attacks (../)
        if (!resolvedPath.startsWith(projectRootPath)) {
            Logger.error(Logger.System.CORE, "VFS Security Block: Path traversal attempt blocked -> %s", sanitized);
            throw new SecurityException("Access denied: Target path lies outside the project boundary sandboxing.");
        }

        return resolvedPath.toFile();
    }

    /**
     * Reads a virtual file straight into an unmanaged, raw native ByteBuffer.
     * Highly optimized for direct I/O transfers to hardware drivers (LWJGL/OpenGL/Vulkan).
     * * NOTE: Memory allocated here must be manually freed via MemoryUtil.memFree() once done.
     */
    public static ByteBuffer ioResourceToBuffer(String virtualPath, int bufferSize) throws IOException {
        File file = resolve(virtualPath);
        if (!file.exists()) {
            Logger.error(Logger.System.CORE, "Hardware I/O Read Error: Asset node not found -> %s", virtualPath);
            throw new java.io.FileNotFoundException("Target resource mapping missing: " + file.getAbsolutePath());
        }

        ByteBuffer buffer;
        
        try (FileInputStream fis = new FileInputStream(file); FileChannel fc = fis.getChannel()) {
            // Allocate unmanaged native heap memory directly to bypass JVM Garbage Collector pauses
            buffer = MemoryUtil.memAlloc((int) fc.size() + 1);
            while (fc.read(buffer) != -1) {
                // Sucking byte frames sequentially from disk channel stream
            }
        }

        buffer.flip();
        return buffer;
    }

    /**
     * Returns a standard stream handle for basic sequential text processing (e.g., Shader source parsing).
     */
    public static InputStream openStream(String virtualPath) throws IOException {
        File file = resolve(virtualPath);
        return new FileInputStream(file);
    }
}