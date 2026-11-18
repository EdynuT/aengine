package com.aengine.core;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/** The window size a development run asks for. */
class WindowTest {

    @AfterEach
    void clear() { System.clearProperty("aengine.windowSize"); }

    @Test
    void aSizeIsAWidthAndAHeight() {
        System.setProperty("aengine.windowSize", "1280x720");
        assertArrayEquals(new int[] { 1280, 720 }, Window.askedSize());
        System.setProperty("aengine.windowSize", " 800 X 600 ");
        assertArrayEquals(new int[] { 800, 600 }, Window.askedSize());
    }

    @Test
    void anythingElseOpensItMaximized() {
        assertNull(Window.askedSize(), "not set");
        for (String wrong : new String[] { "1280", "1280x", "x720", "0x720", "-5x10", "big", "1280x720x2" }) {
            System.setProperty("aengine.windowSize", wrong);
            assertNull(Window.askedSize(), wrong);
        }
    }
}
