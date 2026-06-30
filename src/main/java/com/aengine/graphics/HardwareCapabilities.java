package com.aengine.graphics;

import com.aengine.utils.Logger;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL20;
import java.io.BufferedReader;
import java.io.InputStreamReader;

public final class HardwareCapabilities {

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
                Process process = Runtime.getRuntime().exec("wmic cpu get name");
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
                Process process = Runtime.getRuntime().exec("cat /proc/cpuinfo");
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

    public static int getMaxTextureSlots() { return maxTextureSlots; }
    public static int getMaxTextureSize() { return maxTextureSize; }
    public static int getMaxRenderBufferSize() { return maxRenderBufferSize; }
    public static String getGpuVendor() { return gpuVendor; }
    public static String getGpuHardware() { return gpuHardware; }
    public static String getGlVersion() { return glVersion; }
    public static String getCpuModel() { return cpuModel; }
    public static int getCpuLogicalCores() { return cpuLogicalCores; }
}
