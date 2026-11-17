package com.aengine.aegis;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** The draw list's clip stack. */
class AegisDrawListTest {

    @Test
    void clipsPushedPastTheLimitKeepThePopsPaired() {
        // KNOWN_ISSUES #19
        AegisDrawList list = new AegisDrawList(64);
        list.begin(800, 600);
        list.pushClipRect(0, 0, 100, 100);                 // the panel
        for (int i = 0; i < 40; i++) list.pushClipRect(10, 10, 50, 50);
        for (int i = 0; i < 40; i++) list.popClipRect();
        list.addRoundedRect(5, 5, 200, 200, 0, 1, 1, 1, 1);  // still inside the panel
        list.end();

        int last = list.commandCount() - 1;
        assertEquals(100, list.commandClipW(last), "clipped to the panel, not the window");
        assertEquals(100, list.commandClipH(last));
    }
}
