package com.aengine.aegis;

import com.aengine.core.Keys;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Step 3g-7: the overlay layer — drawn above, given the pointer first, closed by a press outside. */
class AegisOverlayTest {

    private final TestShell    shell  = new TestShell();
    private final AegisLayout  layout = shell.layout;
    private final AegisWidgets ae     = shell.ae;

    private int below, popup, inPopup;

    /** A button at the top left of the window, and a popup with a button of its own. */
    private void build() {
        below = ae.button(shell.root, "belowButton", "Below the popup");
        popup = layout.column(AegisLayout.NONE);
        layout.setPadding(popup, 6.0f);
        inPopup = ae.button(popup, "popupButton", "In the popup");
        shell.frame();
    }

    @Test
    void aPopupIsPlacedAtItsNaturalSizeAndKeptInTheWindow() {
        build();
        ae.showPopup(popup, 100.0f, 50.0f);
        shell.frame();
        assertTrue(ae.isOverlayOpen(popup));
        assertEquals(100.0f, layout.x(popup));
        assertEquals(layout.width(inPopup) + 12.0f, layout.width(popup));

        ae.showPopup(popup, 790.0f, 590.0f);                     // open already: moved
        shell.frame();
        assertEquals(800.0f, layout.x(popup) + layout.width(popup), "moved in from the right edge");
        assertEquals(600.0f, layout.y(popup) + layout.height(popup));
        assertThrows(IllegalArgumentException.class, () -> ae.showPopup(below, 0.0f, 0.0f));
    }

    @Test
    void thePopupTakesThePointerBeforeWhatIsBelow() {
        build();
        ae.showPopup(popup, 0.0f, 0.0f);                          // right over the button below
        shell.frame();
        shell.click(inPopup);
        assertTrue(ae.wasActivated(inPopup));
        assertTrue(ae.isOverlayOpen(popup), "a press inside keeps it open");

        shell.pointerOn(inPopup);
        shell.frame();
        assertFalse(shell.tree.isHovered(below));
    }

    @Test
    void aPressOutsideClosesItAndStillReachesWhatItLandedOn() {
        build();
        int far = ae.button(shell.root, "farButton", "Far");
        layout.setSize(far, 100.0f, 30.0f);
        shell.frame();
        ae.showPopup(popup, 400.0f, 300.0f);
        shell.frame();

        shell.click(far);
        assertFalse(ae.isOverlayOpen(popup));
        assertTrue(ae.wasActivated(far));
    }

    @Test
    void escClosesTheTopmostAndOpeningMovesNoFocus() {
        build();
        shell.tab();
        assertEquals(below, shell.tree.focused());
        int second = layout.column(AegisLayout.NONE);
        ae.label(second, "secondLabel", "On top");
        ae.showPopup(popup, 100.0f, 100.0f);
        ae.showPopup(second, 150.0f, 150.0f);
        shell.frame();
        assertEquals(below, shell.tree.focused());

        shell.key(Keys.ESCAPE);
        assertFalse(ae.isOverlayOpen(second));
        assertTrue(ae.isOverlayOpen(popup));
        shell.key(Keys.ESCAPE);
        assertFalse(ae.isOverlayOpen(popup));
    }

    @Test
    void closingOneClosesTheOnesOnTopOfIt() {
        build();
        int second = layout.column(AegisLayout.NONE);
        ae.label(second, "secondLabel", "On top");
        ae.showPopup(popup, 100.0f, 100.0f);
        ae.showPopup(second, 150.0f, 150.0f);
        ae.closeOverlay(popup);
        assertFalse(ae.isOverlayOpen(second));
        assertDoesNotThrow(shell::draw);
    }

    @Test
    void theWheelOverAPopupDoesNotScrollWhatIsBelow() {
        int pane = layout.column(shell.root);
        layout.setSize(pane, 300.0f, 100.0f);
        layout.setScrollable(pane, true);
        for (int i = 0; i < 10; i++) ae.button(pane, "b" + i, "Button " + i);
        popup = layout.column(AegisLayout.NONE);
        layout.setSize(popup, 200.0f, 80.0f);
        shell.frame();
        ae.showPopup(popup, 10.0f, 10.0f);
        shell.frame();
        shell.pointerAt(50.0f, 50.0f);
        shell.frame();
        ae.scroll(-1.0f);
        shell.frame();
        assertEquals(0.0f, layout.scrollOffset(pane));
        assertDoesNotThrow(shell::draw);
    }
}
