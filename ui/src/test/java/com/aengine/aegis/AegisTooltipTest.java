package com.aengine.aegis;

import com.aengine.core.Keys;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;

/** Step 3g-8: tooltips — after the pointer rests, gone when it leaves, and from the keyboard. */
class AegisTooltipTest {

    private final TestShell    shell  = new TestShell();
    private final AegisLayout  layout = shell.layout;
    private final AegisWidgets ae     = shell.ae;

    private int play, stop, plain;

    private void build() {
        play  = ae.button(shell.root, "buttonPlay", "Play");
        stop  = ae.button(shell.root, "buttonStop", "Stop");
        plain = ae.button(shell.root, "buttonPlain", "No tooltip");
        ae.setTooltip(play, "Starts the scene");
        ae.setTooltip(stop, "Stops it, and puts every entity back where it was");
        shell.frame();
    }

    /** The pointer on a node, resting for some time, then a frame. */
    private void rest(int node, long millis) {
        shell.pointerOn(node);
        shell.frame();
        shell.waitMillis(millis);
        shell.frame();
    }

    @Test
    void itShowsOnceThePointerHasRested() {
        build();
        rest(play, 500);
        assertEquals(AegisLayout.NONE, ae.tooltipShown(), "not yet");
        shell.waitMillis(300);
        shell.frame();
        assertEquals(play, ae.tooltipShown());
        assertDoesNotThrow(shell::draw);
    }

    @Test
    void movingRestartsTheWaitAsInSwing() {
        build();
        rest(play, 600);
        shell.pointerAt(layout.x(play) + 3.0f, layout.y(play) + 3.0f);   // still on it, but moved
        shell.frame();
        shell.waitMillis(300);
        shell.frame();
        assertEquals(AegisLayout.NONE, ae.tooltipShown());
    }

    @Test
    void leavingHidesItAndTheNextShowsAtOnce() {
        build();
        rest(play, 800);
        assertEquals(play, ae.tooltipShown());

        shell.pointerOn(stop);
        shell.frame();
        assertEquals(stop, ae.tooltipShown(), "moving on from a tooltip, the next one is warm");

        rest(plain, 1000);
        assertEquals(AegisLayout.NONE, ae.tooltipShown(), "no tooltip of its own, none above");

        shell.waitMillis(2000);
        rest(play, 100);
        assertEquals(AegisLayout.NONE, ae.tooltipShown(), "cold again after a while");
    }

    @Test
    void aPressOrAKeyHidesIt() {
        build();
        rest(play, 800);
        shell.press();
        shell.frame();
        assertEquals(AegisLayout.NONE, ae.tooltipShown());
        shell.release();
        shell.frame();

        rest(stop, 800);
        assertEquals(stop, ae.tooltipShown());
        shell.key(Keys.A);
        assertEquals(AegisLayout.NONE, ae.tooltipShown());
    }

    @Test
    void aNodeWithoutOneShowsTheNearestAbove() {
        int pane = layout.column(shell.root);
        ae.setTooltip(pane, "The inspector");
        int inner = ae.label(pane, "labelInner", "A label");
        shell.frame();
        rest(inner, 800);
        assertEquals(pane, ae.tooltipShown());
    }

    @Test
    void ctrlF1ShowsTheFocusedWidgetsAndHidesItAgain() {
        build();
        shell.tab();
        assertEquals(play, shell.tree.focused());
        shell.key(Keys.F1, Keys.MOD_CONTROL);
        assertEquals(play, ae.tooltipShown());
        shell.frame();
        assertEquals(play, ae.tooltipShown(), "stays while nothing happens");
        shell.key(Keys.F1, Keys.MOD_CONTROL);
        assertEquals(AegisLayout.NONE, ae.tooltipShown());

        shell.key(Keys.F1, Keys.MOD_CONTROL);
        shell.tab();
        assertEquals(AegisLayout.NONE, ae.tooltipShown(), "any other key hides it");
    }
}
