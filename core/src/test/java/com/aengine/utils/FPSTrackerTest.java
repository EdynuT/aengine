package com.aengine.utils;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The frame rate goes to its own file, not to the log. */
class FPSTrackerTest {

    @Test
    void eachSecondIsAppendedToTheSessionFile() throws Exception {
        for (int i = 0; i < 4; i++) FPSTracker.update(0.25f);   // one second of 4 frames
        assertEquals(4, FPSTracker.getCurrentFPS());

        List<String> lines = Files.readAllLines(FPSTracker.file(), StandardCharsets.UTF_8);
        assertTrue(lines.get(0).startsWith("# AEngine frame rate"));
        assertTrue(lines.get(lines.size() - 1).contains("FPS 4"));
    }
}
