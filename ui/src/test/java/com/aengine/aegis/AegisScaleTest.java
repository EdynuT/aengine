package com.aengine.aegis;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** A screen scaled above 100 %: the interface in units, each unit several pixels. */
class AegisScaleTest {

    private final TestShell shell = new TestShell();

    @Test
    void aFrameIsTheFramebufferOverTheScaleInUnits() {
        shell.aegis.begin(1500, 900, 1.5f);
        assertEquals(1000.0f, shell.aegis.width(), 0.001f);
        assertEquals(600.0f, shell.aegis.height(), 0.001f);
        assertEquals(1.5f, shell.aegis.scale());
        shell.aegis.end();
    }

    @Test
    void aScaleThatMakesNoSenseIsTakenAsOne() {
        for (float wrong : new float[] { 0.0f, -2.0f, Float.NaN, Float.POSITIVE_INFINITY }) {
            shell.aegis.begin(800, 600, wrong);
            assertEquals(800.0f, shell.aegis.width(), 0.001f);
            shell.aegis.end();
        }
    }

    @Test
    void anOverlayStaysInsideTheFrameInUnits() {
        // A dialog wider than half the frame, opened at 150 %: kept within 1000 units, not 1500.
        int dialog = shell.ae.dialog("dialogWide", "Wide");
        shell.layout.setWidth(dialog, 700.0f);
        shell.aegis.begin(1500, 900, 1.5f);
        shell.ae.openDialog(dialog);
        shell.aegis.end();
        shell.frame();
        float right = shell.layout.x(dialog) + shell.layout.width(dialog);
        assertTrue(right <= 1000.0f + 0.5f, "the dialog ends at " + right);
    }
}
