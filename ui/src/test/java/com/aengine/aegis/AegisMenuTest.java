package com.aengine.aegis;

import com.aengine.core.Keys;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Step 3g-10: a menu bar, its menus, a submenu, an item that ticks — by pointer and by keys. */
class AegisMenuTest {

    private final TestShell    shell  = new TestShell();
    private final AegisLayout  layout = shell.layout;
    private final AegisWidgets ae     = shell.ae;

    private int field, fileMenu, viewMenu, recentMenu;
    private int newItem, saveItem, recentItem, sceneOne, quitItem, gridItem;

    /**
     * File: New, Save (Ctrl+S), Recent ▸ [Scene one, Scene two], —, Quit
     * View: Show grid (ticks)
     * and a text field below the bar, with focus.
     */
    private void build() {
        int bar = ae.menuBar(shell.root, "mainMenu");
        fileMenu   = ae.menu(bar, "fileMenu", "File");
        newItem    = ae.menuItem(fileMenu, "newItem", "New");
        saveItem   = ae.menuItem(fileMenu, "saveItem", "Save", "Ctrl+S");
        recentMenu = ae.subMenu(fileMenu, "recentItem", "Recent");
        recentItem = layout.lastChild(fileMenu);
        sceneOne   = ae.menuItem(recentMenu, "sceneOne", "Scene one");
        ae.menuItem(recentMenu, "sceneTwo", "Scene two");
        ae.separator(fileMenu, "fileSeparator");
        quitItem   = ae.menuItem(fileMenu, "quitItem", "Quit");
        viewMenu   = ae.menu(bar, "viewMenu", "View");
        gridItem   = ae.menuCheckItem(viewMenu, "gridItem", "Show grid");
        field = ae.textField(shell.root, "nameField");
        shell.frame();
        shell.tab();
        assertEquals(field, shell.tree.focused());
    }

    private void pressAndRelease() {
        shell.frame();
        shell.press();
        shell.frame();
        shell.release();
        shell.frame();
    }

    @Test
    void aPressOnATitleOpensItsMenuBelowAndAClickActivatesAnItem() {
        build();
        int file = ae.menuTitle(fileMenu);
        shell.pointerOn(file);
        pressAndRelease();
        assertTrue(ae.isMenuOpen(fileMenu));
        assertEquals(layout.y(file) + layout.height(file), layout.y(fileMenu));
        assertEquals(field, shell.tree.focused(), "the menus leave focus where it was");

        shell.pointerOn(saveItem);
        shell.frame();
        assertEquals(saveItem, ae.menuPointedAt(fileMenu));
        pressAndRelease();
        assertFalse(ae.isMenuOpen(fileMenu));
        assertEquals(field, shell.tree.focused());
    }

    @Test
    void pressingATitleAndLettingGoOverAnItemActivatesIt() {
        build();
        shell.pointerOn(ae.menuTitle(fileMenu));
        shell.frame();
        shell.press();
        shell.frame();
        shell.pointerOn(newItem);
        shell.frame();
        shell.release();
        shell.frame();
        assertTrue(ae.wasActivated(newItem));
        assertFalse(ae.isMenuOpen(fileMenu));
    }

    @Test
    void aSecondPressClosesAndMovingToAnotherTitleSwitches() {
        build();
        int file = ae.menuTitle(fileMenu);
        shell.pointerOn(file);
        pressAndRelease();
        assertTrue(ae.isMenuOpen(fileMenu));

        shell.pointerOn(ae.menuTitle(viewMenu));
        shell.frame();
        assertTrue(ae.isMenuOpen(viewMenu));
        assertFalse(ae.isMenuOpen(fileMenu));

        pressAndRelease();
        assertFalse(ae.isMenuOpen(viewMenu), "a press on the open menu's title closes it");
    }

    @Test
    void pointingAtASubmenuItemOpensItBeside() {
        build();
        shell.pointerOn(ae.menuTitle(fileMenu));
        pressAndRelease();
        shell.pointerOn(recentItem);
        shell.frame();
        assertTrue(ae.isMenuOpen(recentMenu));
        assertTrue(layout.x(recentMenu) >= layout.x(fileMenu) + layout.width(fileMenu) - 2.0f);

        shell.pointerOn(sceneOne);
        pressAndRelease();
        assertFalse(ae.isMenuOpen(fileMenu));
        assertFalse(ae.isMenuOpen(recentMenu));

        shell.pointerOn(ae.menuTitle(fileMenu));
        pressAndRelease();
        shell.pointerOn(recentItem);
        shell.frame();
        shell.pointerOn(newItem);
        shell.frame();
        assertFalse(ae.isMenuOpen(recentMenu), "pointing at another item closes the submenu");
    }

    @Test
    void f10AndTheArrowsWorkTheMenusFromTheKeyboard() {
        build();
        shell.key(Keys.F10);
        assertTrue(ae.isMenuOpen(fileMenu));
        assertEquals(newItem, ae.menuPointedAt(fileMenu), "opened by the keyboard, it points at the first item");

        shell.key(Keys.DOWN);
        shell.key(Keys.DOWN);
        assertEquals(recentItem, ae.menuPointedAt(fileMenu));
        shell.key(Keys.RIGHT);
        assertTrue(ae.isMenuOpen(recentMenu));
        assertEquals(sceneOne, ae.menuPointedAt(recentMenu));
        shell.key(Keys.LEFT);
        assertFalse(ae.isMenuOpen(recentMenu), "Left closes the submenu");
        assertTrue(ae.isMenuOpen(fileMenu));

        shell.key(Keys.DOWN);
        assertEquals(quitItem, ae.menuPointedAt(fileMenu), "the separator is passed by");
        shell.key(Keys.DOWN);
        assertEquals(newItem, ae.menuPointedAt(fileMenu), "and past the last, around");

        shell.key(Keys.RIGHT);
        assertTrue(ae.isMenuOpen(viewMenu), "Right on a plain item goes to the next menu");
        shell.key(Keys.ENTER);
        assertTrue(ae.wasActivated(gridItem));
        assertTrue(ae.isChecked(gridItem), "a ticking item ticks");
        assertFalse(ae.isMenuOpen(viewMenu));
        assertEquals("", ae.text(field).toString(), "Enter did not reach the field");
    }

    @Test
    void escClosesOneLevelAndTheKeysNeverReachTheField() {
        build();
        shell.key(Keys.F10);
        shell.key(Keys.DOWN);
        shell.key(Keys.DOWN);
        shell.key(Keys.RIGHT);
        assertTrue(ae.isMenuOpen(recentMenu));
        shell.type("abc");
        shell.key(Keys.ESCAPE);
        assertFalse(ae.isMenuOpen(recentMenu));
        assertTrue(ae.isMenuOpen(fileMenu));
        shell.key(Keys.ESCAPE);
        assertFalse(ae.isMenuOpen(fileMenu));
        assertEquals("", ae.text(field).toString());
        assertEquals(field, shell.tree.focused());

        shell.key(Keys.F10);
        shell.key(Keys.F10);
        assertFalse(ae.isMenuOpen(fileMenu), "F10 again closes them all");
        assertDoesNotThrow(shell::draw);
    }

    @Test
    void aPressOutsideClosesTheMenus() {
        build();
        shell.pointerOn(ae.menuTitle(fileMenu));
        pressAndRelease();
        shell.pointerOn(recentItem);
        shell.frame();
        assertDoesNotThrow(shell::draw);
        shell.pointerAt(700.0f, 550.0f);
        pressAndRelease();
        assertFalse(ae.isMenuOpen(fileMenu));
        assertFalse(ae.isMenuOpen(recentMenu));
    }
}
