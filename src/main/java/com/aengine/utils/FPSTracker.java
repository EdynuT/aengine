package com.aengine.utils;

public final class FPSTracker {

    private static float timeAccumulator = 0.0f;
    private static int frameCount = 0;
    
    // Cached FPS value for external asynchronous polling (e.g., Telemetry)
    private static int currentFPS = 0; 

    // Private constructor to prevent instantiation of this utility class
    private FPSTracker() {}

    /**
     * Accumulates delta time and logs telemetry every 1 second.
     */
    public static void update(float deltaTime) {
        timeAccumulator += deltaTime;
        frameCount++;

        if (timeAccumulator >= 1.0f) {
            // Store the final count before wiping it for the next second
            currentFPS = frameCount;
            
            // Uses the engine's native Logger to maintain terminal consistency
            Logger.info(Logger.System.CORE, "Telemetry - FPS: %d | Frame Time: %.3f ms", frameCount, (1000.0f / frameCount));
            
            frameCount = 0;
            timeAccumulator -= 1.0f; // Subtracts 1.0 instead of resetting to avoid losing fractional precision between frames
        }
    }

    /**
     * Retrieves the last calculated stable frames-per-second metric.
     */
    public static int getCurrentFPS() {
        return currentFPS;
    }
}
