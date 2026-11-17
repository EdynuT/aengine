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

/**
 * The engine's virtual file system: turns project-relative "virtual paths" into files,
 * and refuses anything outside the mounted project.
 *
 * <p>A virtual path is either {@code "assets://..."}, resolved under the project's
 * {@code assets} folder, or a plain relative path, resolved under the project root. Both
 * are normalised, and a path that would escape the project (through {@code ..} or by being
 * absolute) throws {@link SecurityException}.</p>
 *
 * <p>{@link #mountProject(String)} must run before anything is resolved.</p>
 */
public final class FileSystem {

    private static Path projectRootPath = null;

    private FileSystem() {}

    /**
     * Mounts the absolute game project directory context into the Virtual File System.
     * Mounting again replaces the previous project.
     *
     * @param absolutePath The physical root directory of the game project.
     * @throws IllegalArgumentException if the path is not an existing directory
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
     * sandboxed java.io.File handle. The file is not required to exist.
     *
     * @param virtualPath the path to resolve; surrounding spaces are ignored
     * @return the file inside the project
     * @throws IllegalStateException if no project is mounted
     * @throws SecurityException     if the path points outside the project
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
     *
     * <p>NOTE: Memory allocated here must be manually freed via {@code MemoryUtil.memFree()}
     * once done.</p>
     *
     * @param virtualPath the file to read
     * @param bufferSize  ignored; the buffer is always sized to the whole file
     * @return a native buffer holding the whole file, positioned at 0, in native byte order
     * @throws IOException if the file is missing or cannot be read
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
     * The caller must close it.
     *
     * @param virtualPath the file to open
     * @return an open stream at the start of the file
     * @throws IOException if the file is missing or cannot be opened
     */
    public static InputStream openStream(String virtualPath) throws IOException {
        File file = resolve(virtualPath);
        return new FileInputStream(file);
    }
}