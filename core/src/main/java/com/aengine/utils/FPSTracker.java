package com.aengine.utils;

/**
 * Counts frames per second. Call {@link #update(float)} once per frame; once a second the
 * count is stored for {@link #getCurrentFPS()} and written to the log at INFO level.
 */
public final class FPSTracker {

    private static float timeAccumulator = 0.0f;
    private static int frameCount = 0;
    
    // Cached FPS value for external asynchronous polling (e.g., Telemetry)
    private static int currentFPS = 0; 

    // Private constructor to prevent instantiation of this utility class
    private FPSTracker() {}

    /**
     * Accumulates delta time and logs telemetry every 1 second.
     *
     * @param deltaTime seconds since the previous frame
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
     *
     * @return frames counted in the last full second; 0 during the first second
     */
    public static int getCurrentFPS() {
        return currentFPS;
    }
}
