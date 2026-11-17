package com.aengine.aegis;

import com.aengine.core.Keys;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Step 3g-9: the combo box and the list it drops down. */
class AegisComboBoxTest {

    private final TestShell    shell  = new TestShell();
    private final AegisLayout  layout = shell.layout;
    private final AegisWidgets ae     = shell.ae;

    private int combo;

    private void build() {
        combo = ae.comboBox(shell.root, "comboShading");
        ae.addComboItem(combo, "lit", "Lit");
        ae.addComboItem(combo, "unlit", "Unlit");
        ae.addComboItem(combo, "wireframe", "Wireframe");
        ae.addComboItem(combo, "normals", "Normals");
        shell.frame();
    }


    /** The list is the node made right after the box. */
    private int listNode() { return combo + 1; }

    private float rowHeight() {
        return shell.aegis.lineHeight() + ae.style().comboPaddingY * 2.0f;
    }

    /** The pointer on an item of the open list. */
    private void pointAtItem(int item) {
        int list = listNode();
        shell.pointerAt(layout.x(list) + 20.0f, layout.y(list) + 2.0f + item * rowHeight() + rowHeight() * 0.5f);
    }

    @Test
    void itIsSizedToItsLongestItemAndStartsOnTheFirst() {
        build();
        assertEquals(0, ae.comboChosen(combo));
        AegisStyle style = ae.style();
        assertEquals((float) Math.ceil(shell.aegis.measure("Wireframe")) + style.comboPaddingX * 2.0f + style.comboArrowWidth,
            layout.width(combo));
    }

    @Test
    void aPressOpensTheListAndPressingAnItemChoosesIt() {
        build();
        shell.click(combo);
        assertTrue(ae.isComboOpen(combo));
        assertEquals(combo, shell.tree.focused());
        assertEquals(layout.y(combo) + layout.height(combo) + 2.0f, layout.y(listNode()), "below the box");

        pointAtItem(2);
        shell.frame();
        shell.press();
        shell.frame();
        shell.release();
        shell.frame();
        assertEquals(2, ae.comboChosen(combo));
        assertTrue(ae.wasChanged(combo));
        assertFalse(ae.isComboOpen(combo));
        assertEquals(combo, shell.tree.focused(), "pressing the list leaves focus on the box");
    }

    @Test
    void pressingTheBoxAndLettingGoOverAnItemChoosesIt() {
        build();
        shell.pointerOn(combo);
        shell.frame();
        shell.press();
        shell.frame();
        assertTrue(ae.isComboOpen(combo));
        pointAtItem(3);
        shell.frame();
        shell.release();
        shell.frame();
        assertEquals(3, ae.comboChosen(combo));
        assertFalse(ae.isComboOpen(combo));
    }

    @Test
    void aSecondPressOnTheBoxClosesItAndAPressElsewhereToo() {
        build();
        shell.click(combo);
        assertTrue(ae.isComboOpen(combo));
        shell.click(combo);
        assertFalse(ae.isComboOpen(combo), "the press that closed it did not open it again");

        shell.click(combo);
        shell.pointerAt(700.0f, 500.0f);
        shell.frame();
        shell.press();
        shell.frame();
        shell.release();
        shell.frame();
        assertFalse(ae.isComboOpen(combo));
        assertEquals(0, ae.comboChosen(combo));
    }

    @Test
    void closedTheArrowsAndLettersChooseAtOnce() {
        build();
        shell.tab();
        shell.key(Keys.DOWN);
        assertEquals(1, ae.comboChosen(combo));
        assertTrue(ae.wasChanged(combo));
        shell.key(Keys.END);
        assertEquals(3, ae.comboChosen(combo));
        shell.key(Keys.DOWN);
        assertEquals(3, ae.comboChosen(combo), "stops at the last");
        shell.type("w");
        assertEquals(2, ae.comboChosen(combo));
        shell.key(Keys.HOME);
        assertEquals(0, ae.comboChosen(combo));
        assertFalse(ae.isComboOpen(combo));
    }

    @Test
    void openTheKeysMoveThroughTheListAndEnterChooses() {
        build();
        ae.button(shell.root, "buttonOther", "Other");
        shell.tab();
        shell.key(Keys.DOWN, Keys.MOD_ALT);
        assertTrue(ae.isComboOpen(combo));
        shell.key(Keys.DOWN);
        shell.key(Keys.DOWN);
        assertEquals(0, ae.comboChosen(combo), "moving through an open list chooses nothing yet");
        shell.key(Keys.ENTER);
        assertEquals(2, ae.comboChosen(combo));
        assertTrue(ae.wasChanged(combo));
        assertFalse(ae.isComboOpen(combo));

        shell.key(Keys.SPACE);
        assertTrue(ae.isComboOpen(combo), "Space opens it too");
        shell.key(Keys.UP);
        shell.key(Keys.ESCAPE);
        assertFalse(ae.isComboOpen(combo));
        assertEquals(2, ae.comboChosen(combo), "Esc leaves the choice as it was");
        assertEquals(combo, shell.tree.focused());

        shell.key(Keys.F4);
        assertTrue(ae.isComboOpen(combo));
        shell.tab();
        assertFalse(ae.isComboOpen(combo), "Tab away closes it");
        assertDoesNotThrow(shell::draw);
    }

    @Test
    void withNoRoomBelowTheListOpensAbove() {
        int spacer = layout.box(shell.root);
        layout.setSize(spacer, 10.0f, 560.0f);
        build();
        shell.click(combo);
        assertTrue(ae.isComboOpen(combo));
        assertTrue(layout.y(listNode()) + layout.height(listNode()) <= layout.y(combo));
        assertDoesNotThrow(shell::draw);
    }
}
