package com.aengine.network;

import com.aengine.utils.Logger;
import com.aengine.ecs.Registry;
import com.aengine.utils.FPSTracker;
import org.lwjgl.system.MemoryUtil;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.AsynchronousServerSocketChannel;
import java.nio.channels.AsynchronousSocketChannel;
import java.nio.channels.CompletionHandler;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * HARDWARE CONTEXT: ZERO-ALLOCATION LOCAL IPC DAEMON
 * Establishes a Non-Blocking TCP loopback interface to stream engine telemetry to the Tauri WebKit frontend.
 * Execution is strictly driven by the Engine's Main Thread to prevent ECS Data Races.
 */
public final class TelemetryServer {

    private static final int IPC_PORT = 8080;
    private static final String LOOPBACK_ADDRESS = "127.0.0.1"; // Crucial: Prevents Windows Defender Firewall prompts
    
    private static AsynchronousServerSocketChannel serverChannel;
    private static volatile AsynchronousSocketChannel activeClient;
    private static final AtomicBoolean isRunning = new AtomicBoolean(false);

    // Lock-free queue for thread-safe Logger interception
    private static final ConcurrentLinkedQueue<String> logQueue = new ConcurrentLinkedQueue<>();

    // Pre-allocated 8KB native buffer to serialize JSON payloads without heap allocation
    private static final ByteBuffer PAYLOAD_BUFFER = MemoryUtil.memAlloc(8192);
    private static final StringBuilder JSON_BUILDER = new StringBuilder(8192);

    private TelemetryServer() {}

    public static void start() {
        Logger.info(Logger.System.CORE, "Starting Telemetry IPC Daemon...");
        if (isRunning.get()) return;

        try {
            serverChannel = AsynchronousServerSocketChannel.open();
            // Bind strictly to the local loopback interface
            serverChannel.bind(new InetSocketAddress(LOOPBACK_ADDRESS, IPC_PORT));
            isRunning.set(true);

            Logger.info(Logger.System.CORE, "Telemetry IPC Daemon engaged on %s:%d", LOOPBACK_ADDRESS, IPC_PORT);

            // Delegate connection listening to OS Kernel (Non-blocking)
            serverChannel.accept(null, new CompletionHandler<AsynchronousSocketChannel, Void>() {
                @Override
                public void completed(AsynchronousSocketChannel clientChannel, Void attachment) {
                    // Accept the next connection immediately to keep the pipeline open
                    serverChannel.accept(null, this);
                    
                    if (activeClient != null && activeClient.isOpen()) {
                        try { activeClient.close(); } catch (IOException ignored) {}
                    }
                    
                    activeClient = clientChannel;
                    Logger.info(Logger.System.CORE, "Frontend WebKit debugger attached via IPC.");
                }

                @Override
                public void failed(Throwable exc, Void attachment) {
                    if (isRunning.get()) {
                        Logger.error(Logger.System.CORE, "IPC Accept pipeline failed: %s", exc.getMessage());
                    }
                }
            });

            // NOTICE: The autonomous Daemon Thread (telemetryLoop) was completely removed.
            // Dispatching is now safely dictated by Main.java at a fixed UI threshold.

        } catch (IOException e) {
            Logger.error(Logger.System.CORE, "Failed to bind Telemetry IPC Daemon: %s", e.getMessage());
        }
    }

    /**
     * Extracts current engine state and dispatches the JSON payload over TCP.
     * MUST be called from the Main Thread to guarantee thread-safe ECS Registry reads.
     */
    public static void dispatch(Registry registry) {
        if (!isRunning.get()) return;

        if (activeClient == null || !activeClient.isOpen()) {
            // If no frontend is attached, silently drain the log queue to prevent memory leaks
            if (!logQueue.isEmpty()) logQueue.clear();
            return;
        }

        // Reset StringBuilder cursor without reallocating memory
        JSON_BUILDER.setLength(0);

        // Extract live Engine metrics safely
        int currentFps = FPSTracker.getCurrentFPS();
        int activeEntities = registry.getEntityCount();

        // Construct JSON Payload manually to avoid heavy Reflection-based parsers like GSON
        JSON_BUILDER.append("{\"type\":\"TELEMETRY_TICK\",\"data\":{");
        JSON_BUILDER.append("\"performance\":{\"fps\":").append(currentFps).append("},");
        JSON_BUILDER.append("\"ecs\":{\"activeEntities\":").append(activeEntities).append("},");
        
        // Drain concurrent log queue
        JSON_BUILDER.append("\"logs\":[");
        boolean firstLog = true;
        while (!logQueue.isEmpty()) {
            String log = logQueue.poll();
            if (log == null) break;
            
            if (!firstLog) JSON_BUILDER.append(",");
            // Basic sanitization to prevent JSON malformation
            JSON_BUILDER.append("\"").append(log.replace("\"", "\\\"").replace("\n", " ")).append("\"");
            firstLog = false;
        }
        JSON_BUILDER.append("]}}");

        // Blit text data into native NIO buffer
        byte[] rawBytes = JSON_BUILDER.toString().getBytes(StandardCharsets.UTF_8);
        
        PAYLOAD_BUFFER.clear();
        if (rawBytes.length <= PAYLOAD_BUFFER.capacity()) {
            PAYLOAD_BUFFER.put(rawBytes);
            PAYLOAD_BUFFER.flip(); // Set pointer to 0 for OS reading

            // Non-blocking write dispatch to TCP stack
            activeClient.write(PAYLOAD_BUFFER, null, new CompletionHandler<Integer, Void>() {
                @Override public void completed(Integer result, Void attachment) {}
                @Override public void failed(Throwable exc, Void attachment) {
                    try { activeClient.close(); } catch (IOException ignored) {}
                }
            });
        } else {
            Logger.warn(Logger.System.CORE, "Telemetry payload exceeded native buffer capacity. Frame dropped.");
        }
    }

    /**
     * Thread-safe injection hook for the internal Logger.
     */
    public static void enqueueLog(String message) {
        // Hard cap at 500 logs to prevent OOM errors if frontend detaches unexpectedly
        if (logQueue.size() < 500) {
            logQueue.add(message);
        }
    }

    public static void stop() {
        isRunning.set(false);
        try {
            if (activeClient != null && activeClient.isOpen()) activeClient.close();
            if (serverChannel != null && serverChannel.isOpen()) serverChannel.close();
            MemoryUtil.memFree(PAYLOAD_BUFFER);
            Logger.info(Logger.System.CORE, "Telemetry IPC Daemon shutdown complete.");
        } catch (IOException e) {
            Logger.error(Logger.System.CORE, "Exception during IPC Daemon shutdown: %s", e.getMessage());
        }
    }
}
