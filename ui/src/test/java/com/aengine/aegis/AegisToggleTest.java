package com.aengine.aegis;

import com.aengine.core.Keys;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Step 4b: icons, toggle buttons and their groups, and widgets that cannot be used. */
class AegisToggleTest {

    private final TestShell    shell  = new TestShell();
    private final AegisLayout  layout = shell.layout;
    private final AegisTree    tree   = shell.tree;
    private final AegisWidgets ae     = shell.ae;

    // ── Icons ────────────────────────────────────────────────────────────────────────

    @Test
    void theIconSetIsReadByTheEnginesNames() {
        AegisIcons icons = shell.aegis.loadIcons("/icons/icons.json", 18.0f);
        assertNotEquals(AegisIcons.NONE, icons.find("play"));
        assertNotEquals(AegisIcons.NONE, icons.find("toolMove"));
        assertEquals(AegisIcons.NONE, icons.find("rocket"), "a name the map does not have");
        assertEquals(18.0f, icons.size());
    }

    @Test
    void anIconNeedsASet() {
        int button = ae.button(shell.root, "buttonPlay", "Play");
        assertThrows(IllegalStateException.class, () -> ae.setIcon(button, "play"));
    }

    @Test
    void anIconTheSetLacksShowsNothingAndStopsNothing() {
        shell.aegis.loadIcons("/icons/icons.json", 18.0f);
        int button = ae.button(shell.root, "buttonPlay", "Play");
        float textOnly = layout.requestedWidth(button);
        ae.setIcon(button, "rocket");
        assertEquals(textOnly, layout.requestedWidth(button), "no icon, so no room for one");
    }

    @Test
    void anIconGoesBeforeTheTextAndAloneMakesASquare() {
        shell.aegis.loadIcons("/icons/icons.json", 18.0f);
        int withText = ae.button(shell.root, "buttonPlay", "Play");
        float textOnly = layout.requestedWidth(withText);
        ae.setIcon(withText, "play");
        assertTrue(layout.requestedWidth(withText) > textOnly, "the icon and the gap are added");

        int alone = ae.button(shell.root, "buttonStop", "");
        ae.setIcon(alone, "stop");
        assertEquals(layout.requestedHeight(alone), layout.requestedWidth(alone), "an icon alone is a square");

        shell.frame();
        assertDoesNotThrow(shell::draw);
    }

    @Test
    void onlyButtonsTogglesAndLabelsShowIcons() {
        shell.aegis.loadIcons("/icons/icons.json", 18.0f);
        int box = ae.checkbox(shell.root, "checkboxSnap", "Snap");
        assertThrows(IllegalArgumentException.class, () -> ae.setIcon(box, "play"));
        int label = ae.label(shell.root, "labelSave", "Save");
        assertDoesNotThrow(() -> ae.setIcon(label, "save"));
    }

    @Test
    void anIconAloneAsksNoTranslation() throws Exception {
        shell.aegis.loadIcons("/icons/icons.json", 18.0f);
        int stop = ae.button(shell.root, "buttonStop", "");
        ae.setIcon(stop, "stop");
        AegisLocale locale = AegisLocale.parse(new java.io.StringReader("{ \"format\": 1 }"), "test");
        ae.applyLocale(locale);
        assertTrue(locale.missing().isEmpty(), "nothing to translate: " + locale.missing());
    }

    // ── Toggle buttons ───────────────────────────────────────────────────────────────

    @Test
    void aToggleIsNamedByItsKind() {
        assertThrows(IllegalArgumentException.class, () -> ae.toggleButton(shell.root, "moveToggle", "Move"));
    }

    @Test
    void aToggleAloneFlipsByClickEnterAndSpace() {
        int grid = ae.toggleButton(shell.root, "toggleGrid", "Grid");
        shell.click(grid);
        assertTrue(ae.isChecked(grid));

        shell.key(Keys.ENTER);
        assertFalse(ae.isChecked(grid));
        assertTrue(ae.wasChanged(grid));

        shell.key(Keys.SPACE);
        assertTrue(ae.isChecked(grid));
    }

    @Test
    void inAGroupOneIsPressedAndPressingItAgainKeepsIt() {
        int select = ae.toggleButton(shell.root, "toggleSelect", "Select");
        int move   = ae.toggleButton(shell.root, "toggleMove", "Move");
        int rotate = ae.toggleButton(shell.root, "toggleRotate", "Rotate");
        ae.groupToggles(select, move, rotate);
        ae.setChecked(select, true);

        shell.click(move);
        assertTrue(ae.isChecked(move));
        assertFalse(ae.isChecked(select), "the choice moved");

        shell.click(move);
        assertTrue(ae.isChecked(move), "pressing the pressed one leaves it pressed, as a radio button");
        assertFalse(ae.wasChanged(move));

        ae.setChecked(rotate, true);
        assertTrue(ae.isChecked(rotate));
        assertFalse(ae.isChecked(move), "code moves the choice too");
    }

    @Test
    void groupingKeepsOnlyTheFirstPressed() {
        int a = ae.toggleButton(shell.root, "toggleA", "A");
        int b = ae.toggleButton(shell.root, "toggleB", "B");
        ae.setChecked(a, true);
        ae.setChecked(b, true);
        ae.groupToggles(a, b);
        assertTrue(ae.isChecked(a));
        assertFalse(ae.isChecked(b));
        assertThrows(IllegalArgumentException.class, () -> ae.groupToggles(a, ae.button(shell.root, "buttonC", "C")));
    }

    // ── Disabled widgets ─────────────────────────────────────────────────────────────

    @Test
    void aDisabledWidgetIsReachedByNothing() {
        int play = ae.button(shell.root, "buttonPlay", "Play");
        int stop = ae.button(shell.root, "buttonStop", "Stop");
        ae.setEnabled(stop, false);
        assertFalse(ae.isEnabled(stop));

        shell.click(stop);
        assertFalse(ae.wasActivated(stop), "a click does nothing");
        assertNotEquals(stop, tree.focused());

        tree.focus(AegisLayout.NONE);
        shell.tab();
        assertEquals(play, tree.focused());
        shell.tab();
        assertEquals(play, tree.focused(), "Tab passes it by");

        ae.setEnabled(stop, true);
        shell.tab();
        assertEquals(stop, tree.focused(), "enabled again, it is reached again");
    }

    @Test
    void disablingTheFocusedWidgetMovesFocusOff() {
        int stop = ae.button(shell.root, "buttonStop", "Stop");
        shell.frame();
        shell.tab();
        assertEquals(stop, tree.focused());
        ae.setEnabled(stop, false);
        assertNotEquals(stop, tree.focused());
    }

    @Test
    void whatADisabledContainerHoldsIsOutOfReachToo() {
        int row = layout.row(shell.root);
        int inside = ae.button(row, "buttonInside", "Inside");
        int pane = ae.surface(shell.root, "surfaceScene");
        layout.setSize(pane, 100.0f, 100.0f);
        ae.setEnabled(pane, false);
        shell.tree.setDisabled(row, true);

        shell.click(inside);
        assertFalse(ae.wasActivated(inside));
        shell.frame();
        assertDoesNotThrow(shell::draw, "drawn faded");
    }
}
