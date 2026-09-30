package com.aengine.graphics;

import com.aengine.utils.Logger;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL20;
import java.io.BufferedReader;
import java.io.InputStreamReader;

/**
 * Reads the GPU's limits and the CPU's name once, logs them, and keeps them for the
 * renderers to query.
 *
 * <p>Call {@link #initialize()} on the GL thread after the context exists; the 2D renderer
 * does this itself. Before that the getters return 0, 1 or {@code "Unknown"}.</p>
 */
public final class HardwareCapabilities {

    private HardwareCapabilities() {}

    // GPU Telemetry Parameters
    private static int maxTextureSlots;
    private static int maxTextureSize;
    private static int maxRenderBufferSize;
    private static String gpuVendor = "Unknown";
    private static String gpuHardware = "Unknown";
    private static String glVersion = "Unknown";

    // CPU Telemetry Parameters
    private static String cpuModel = "Unknown CPU";
    private static int cpuLogicalCores = 1;

    private static boolean initialized = false;

    // Sourced from GL30 core specification to prevent compilation breakdown in GL20 scopes
    private static final int GL_MAX_RENDERBUFFER_SIZE = 0x84E8;

    /**
     * Queries the GPU through OpenGL and the CPU through the operating system, then logs a
     * summary. Runs only once; later calls return immediately.
     *
     * <p>The CPU name comes from {@code /proc/cpuinfo} on Linux and from {@code wmic} on
     * Windows. If that fails it stays {@code "Unknown CPU"}, with a warning in the log.</p>
     */
    public static void initialize() {
        if (initialized) return;

        // 1. Resolve Graphics Driver Telemetry Context
        gpuVendor = GL11.glGetString(GL11.GL_VENDOR);
        gpuHardware = GL11.glGetString(GL11.GL_RENDERER);
        glVersion = GL11.glGetString(GL11.GL_VERSION);

        int[] queryBuffer = new int[1];
        GL11.glGetIntegerv(GL20.GL_MAX_TEXTURE_IMAGE_UNITS, queryBuffer);
        maxTextureSlots = queryBuffer[0];

        GL11.glGetIntegerv(GL11.GL_MAX_TEXTURE_SIZE, queryBuffer);
        maxTextureSize = queryBuffer[0];

        GL11.glGetIntegerv(GL_MAX_RENDERBUFFER_SIZE, queryBuffer);
        maxRenderBufferSize = queryBuffer[0];

        // 2. Resolve Processor Execution Context Topology
        cpuLogicalCores = Runtime.getRuntime().availableProcessors();
        resolveCpuSpecifications();

        initialized = true;

        /* Python to java
        box = [gpuVendor, gpuHardware, glVersion, maxTextureSlots, maxTextureSize, maxRenderBufferSize, cpuModel, cpuLogicalCores]
        max_len = []
        for iten in range(len(box)):
            max_len.append(len(box[iten]))
        
        max_len = max(max_len)

        bar = "=" * (max_len)
        */

        // now translated to java
        String[] box = {gpuVendor, gpuHardware, glVersion, String.valueOf(maxTextureSlots), String.valueOf(maxTextureSize), String.valueOf(maxRenderBufferSize), cpuModel, String.valueOf(cpuLogicalCores)};
        
        int max_len = 0;
        for (String item : box) {
            if (item.length() > max_len) {
                max_len = item.length();
            }
        }

        String bar = "=".repeat(max_len + 28);

        // 3. Flush Complete Hardware Stack Context to Logs
        Logger.info(Logger.System.RENDERER, bar);
        Logger.info(Logger.System.RENDERER, "HARDWARE TELEMETRY LOGGED:");
        Logger.info(Logger.System.RENDERER, " -> CPU Host Identifier   : %s", cpuModel);
        Logger.info(Logger.System.RENDERER, " -> CPU Logical Cores     : %d active threads", cpuLogicalCores);
        Logger.info(Logger.System.RENDERER, " -> Graphics Accelerator  : %s", gpuHardware);
        Logger.info(Logger.System.RENDERER, " -> OpenGL Driver Scope   : %s", glVersion);
        Logger.info(Logger.System.RENDERER, " -> Hardware Texture Units: %d active slots", maxTextureSlots);
        Logger.info(Logger.System.RENDERER, " -> Texture Resolution Max: %dx%d px", maxTextureSize, maxTextureSize);
        Logger.info(Logger.System.RENDERER, " -> FrameBuffer Render Cap: %d px", maxRenderBufferSize);
        Logger.info(Logger.System.RENDERER, bar);
    }

    private static void resolveCpuSpecifications() {
        String os = System.getProperty("os.name").toLowerCase();
        try {
            if (os.contains("win")) {
                // Execute hardware pipeline query against Windows Management Instrumentation (WMI)
                Process process = Runtime.getRuntime().exec(new String[]{"wmic", "cpu", "get", "name"});
                try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
                    reader.readLine(); // Skip header layout entry line
                    String line;
                    while ((line = reader.readLine()) != null) {
                        if (!line.trim().isEmpty()) {
                            cpuModel = line.trim();
                            break;
                        }
                    }
                }
            } else {
                // Execute direct VFS stream interrogation against Unix hardware descriptors layout
                Process process = Runtime.getRuntime().exec(new String[]{"cat", "/proc/cpuinfo"});
                try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
                    String line;
                    while ((line = reader.readLine()) != null) {
                        if (line.startsWith("model name")) {
                            String[] parts = line.split(":");
                            if (parts.length > 1) {
                                cpuModel = parts[1].trim();
                                break;
                            }
                        }
                    }
                }
            }
        } catch (Exception e) {
            Logger.warn(Logger.System.CORE, "Failed to resolve physical CPU identifiers from host registers.");
        }
    }

    /**
     * Returns how many textures a fragment shader can sample at once, which is also how
     * many textures one 2D batch can hold.
     *
     * @return {@code GL_MAX_TEXTURE_IMAGE_UNITS}; 0 before {@link #initialize()}
     */
    public static int getMaxTextureSlots() { return maxTextureSlots; }

    /**
     * Returns the largest texture width or height the GPU accepts.
     *
     * @return {@code GL_MAX_TEXTURE_SIZE} in pixels; 0 before {@link #initialize()}
     */
    public static int getMaxTextureSize() { return maxTextureSize; }

    /**
     * Returns the largest render buffer width or height, which bounds a {@link FrameBuffer}.
     *
     * @return {@code GL_MAX_RENDERBUFFER_SIZE} in pixels; 0 before {@link #initialize()}
     */
    public static int getMaxRenderBufferSize() { return maxRenderBufferSize; }

    /**
     * Returns the GPU vendor as reported by the driver.
     *
     * @return {@code GL_VENDOR}, e.g. "AMD"; "Unknown" before {@link #initialize()}
     */
    public static String getGpuVendor() { return gpuVendor; }

    /**
     * Returns the GPU model as reported by the driver.
     *
     * @return {@code GL_RENDERER}; "Unknown" before {@link #initialize()}
     */
    public static String getGpuHardware() { return gpuHardware; }

    /**
     * Returns the OpenGL version string, which usually includes the driver version.
     *
     * @return {@code GL_VERSION}; "Unknown" before {@link #initialize()}
     */
    public static String getGlVersion() { return glVersion; }

    /**
     * Returns the CPU's marketing name.
     *
     * @return the name, or "Unknown CPU" if it could not be read
     */
    public static String getCpuModel() { return cpuModel; }

    /**
     * Returns how many hardware threads the JVM can use.
     *
     * @return the logical core count; 1 before {@link #initialize()}
     */
    public static int getCpuLogicalCores() { return cpuLogicalCores; }
}
