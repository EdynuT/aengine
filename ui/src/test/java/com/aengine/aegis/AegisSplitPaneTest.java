package com.aengine.aegis;

import com.aengine.core.Keys;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Step 3g-5: the split pane, its divider dragged and moved by keys. */
class AegisSplitPaneTest {

    private final TestShell    shell  = new TestShell();
    private final AegisLayout  layout = shell.layout;
    private final AegisWidgets ae     = shell.ae;

    private int left, right, divider, split;

    /** A side-by-side pane 400 wide: the left side starts 120 wide, the right takes the rest. */
    private void sideBySide() {
        left  = layout.column(AegisLayout.NONE);
        right = layout.column(AegisLayout.NONE);
        split = ae.splitPane(shell.root, "splitView", true, left, right, 120.0f);
        layout.setSize(split, 400.0f, 200.0f);
        divider = layout.nextSibling(left);
        shell.frame();
    }

    @Test
    void theFirstSideKeepsItsSizeAndTheSecondTakesTheRest() {
        sideBySide();
        assertEquals(120.0f, layout.width(left));
        assertEquals(new AegisStyle().splitSize, layout.width(divider));
        assertEquals(400.0f - 120.0f - layout.width(divider), layout.width(right));
        assertEquals(200.0f, layout.height(left), "each side fills the pane across");
        assertEquals(divider, layout.nextSibling(left));
        assertEquals(right, layout.lastChild(split));
    }

    @Test
    void theDividerFollowsThePointerWithoutJumping() {
        sideBySide();
        float grabX = layout.x(divider) + 2.0f;
        shell.pointerAt(grabX, 100.0f);
        shell.frame();
        shell.press();
        shell.frame();
        assertEquals(120.0f, ae.dividerLocation(split), "taking hold moves nothing");

        shell.pointerAt(grabX + 50.0f, 100.0f);
        shell.frame();
        assertEquals(170.0f, ae.dividerLocation(split));
        assertTrue(ae.wasChanged(split));
        assertEquals(170.0f, layout.width(left));

        shell.pointerAt(-500.0f, 100.0f);
        shell.frame();
        assertEquals(24.0f, ae.dividerLocation(split), "the left side keeps its minimum");

        shell.pointerAt(5000.0f, 100.0f);
        shell.frame();
        assertEquals(400.0f - layout.width(divider) - 24.0f, ae.dividerLocation(split), "and so does the right");

        shell.release();
        shell.frame();
        shell.pointerAt(grabX, 100.0f);
        shell.frame();
        assertFalse(ae.wasChanged(split), "let go, it stays");
    }

    @Test
    void pressingTheDividerLeavesFocusInTheTextField() {
        int field = ae.textField(shell.root, "fieldName");
        sideBySide();
        shell.tab();
        assertEquals(field, shell.tree.focused());

        shell.pointerOn(divider);
        shell.frame();
        shell.press();
        shell.frame();
        assertEquals(field, shell.tree.focused());
        shell.release();
        shell.frame();
    }

    @Test
    void theFocusedDividerMovesByArrowsAndGoesToItsEnds() {
        sideBySide();
        shell.tab();
        assertEquals(divider, shell.tree.focused(), "a stop of Tab");

        shell.key(Keys.RIGHT);
        assertEquals(128.0f, ae.dividerLocation(split));
        assertTrue(ae.wasChanged(split));
        shell.key(Keys.LEFT, Keys.MOD_SHIFT);
        assertEquals(48.0f, ae.dividerLocation(split));
        shell.key(Keys.UP);
        assertEquals(48.0f, ae.dividerLocation(split), "Up and Down are for a divider lying flat");
        shell.key(Keys.HOME);
        assertEquals(24.0f, ae.dividerLocation(split));
        shell.key(Keys.END);
        assertEquals(400.0f - layout.width(divider) - 24.0f, ae.dividerLocation(split));
    }

    @Test
    void oneAboveTheOtherTheDividerLiesFlatAndTakesUpAndDown() {
        int top = layout.column(AegisLayout.NONE);
        int bottom = layout.column(AegisLayout.NONE);
        int stacked = ae.splitPane(shell.root, "splitLog", false, top, bottom, 80.0f);
        layout.setSize(stacked, 300.0f, 300.0f);
        shell.frame();
        assertEquals(80.0f, layout.height(top));
        assertEquals(300.0f, layout.width(top));

        shell.tab();
        shell.key(Keys.DOWN);
        assertEquals(88.0f, ae.dividerLocation(stacked));
        shell.key(Keys.RIGHT);
        assertEquals(88.0f, ae.dividerLocation(stacked));
        ae.setDividerLocation(stacked, 150.0f);
        shell.frame();
        assertFalse(ae.wasChanged(stacked), "moved from code, not reported");
        assertEquals(150.0f, layout.height(top));
    }
}
