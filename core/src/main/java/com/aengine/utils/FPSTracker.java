package com.aengine.utils;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;

/**
 * Counts frames per second. Call {@link #update(float)} once per frame; once a second the
 * count is stored for {@link #getCurrentFPS()} and appended to a session file in the system's
 * temporary folder, {@code <tmp>/AEngine/fps.log}, not to the engine log, where a line a
 * second would bury everything else in a long session.
 *
 * <p>The file is replaced when a session writes its first line, so it holds the current or
 * last session only. If it cannot be written, one warning goes to the log and the count
 * carries on without it.</p>
 */
public final class FPSTracker {

    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm:ss");

    private static float timeAccumulator = 0.0f;
    private static int frameCount = 0;

    // Cached FPS value for external asynchronous polling (e.g., Telemetry)
    private static int currentFPS = 0;

    private static BufferedWriter file;
    private static boolean fileFailed = false;

    // Private constructor to prevent instantiation of this utility class
    private FPSTracker() {}

    /**
     * Accumulates delta time and records the count every second.
     *
     * @param deltaTime seconds since the previous frame
     */
    public static void update(float deltaTime) {
        timeAccumulator += deltaTime;
        frameCount++;

        if (timeAccumulator >= 1.0f) {
            // Store the final count before wiping it for the next second
            currentFPS = frameCount;
            record(frameCount);

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

    /**
     * Where the counts go: {@code AEngine/fps.log} in the system's temporary folder.
     *
     * @return the file's path
     */
    public static Path file() {
        return Path.of(java.lang.System.getProperty("java.io.tmpdir"), "AEngine", "fps.log");
    }

    private static void record(int frames) {
        if (fileFailed) return;
        try {
            if (file == null) {
                Path path = file();
                Files.createDirectories(path.getParent());
                file = Files.newBufferedWriter(path, StandardCharsets.UTF_8);  // replaces the last session's
                file.write("# AEngine frame rate, session started " + LocalDateTime.now().withNano(0));
                file.newLine();
                Logger.info(Logger.System.CORE, "Frame rate is recorded in %s", path);
            }
            file.write(String.format("%s  FPS %d  frame %.3f ms", LocalTime.now().format(TIME), frames, 1000.0f / frames));
            file.newLine();
            file.flush();
        } catch (IOException e) {
            fileFailed = true;
            Logger.warn(Logger.System.CORE, "Frame rate file unavailable (%s); the count stays on screen only.", e.getMessage());
        }
    }
}
