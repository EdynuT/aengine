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

    private int field, menuFile, menuView, recentMenu;
    private int menuItemNew, menuItemSave, menuRecent, menuItemSceneOne, menuItemQuit, menuItemGrid;

    /**
     * File: New, Save (Ctrl+S), Recent ▸ [Scene one, Scene two], —, Quit
     * View: Show grid (ticks)
     * and a text field below the bar, with focus.
     */
    private void build() {
        int bar = ae.menuBar(shell.root, "menuBarMain");
        menuFile   = ae.menu(bar, "menuFile", "File");
        menuItemNew    = ae.menuItem(menuFile, "menuItemNew", "New");
        menuItemSave   = ae.menuItem(menuFile, "menuItemSave", "Save", "Ctrl+S");
        recentMenu = ae.subMenu(menuFile, "menuRecent", "Recent");
        menuRecent = layout.lastChild(menuFile);
        menuItemSceneOne   = ae.menuItem(recentMenu, "menuItemSceneOne", "Scene one");
        ae.menuItem(recentMenu, "menuItemSceneTwo", "Scene two");
        ae.separator(menuFile, "separatorFile");
        menuItemQuit   = ae.menuItem(menuFile, "menuItemQuit", "Quit");
        menuView   = ae.menu(bar, "menuView", "View");
        menuItemGrid   = ae.menuCheckItem(menuView, "menuItemGrid", "Show grid");
        field = ae.textField(shell.root, "fieldName");
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
        int file = ae.menuTitle(menuFile);
        shell.pointerOn(file);
        pressAndRelease();
        assertTrue(ae.isMenuOpen(menuFile));
        assertEquals(layout.y(file) + layout.height(file), layout.y(menuFile));
        assertEquals(field, shell.tree.focused(), "the menus leave focus where it was");

        shell.pointerOn(menuItemSave);
        shell.frame();
        assertEquals(menuItemSave, ae.menuPointedAt(menuFile));
        pressAndRelease();
        assertFalse(ae.isMenuOpen(menuFile));
        assertEquals(field, shell.tree.focused());
    }

    @Test
    void pressingATitleAndLettingGoOverAnItemActivatesIt() {
        build();
        shell.pointerOn(ae.menuTitle(menuFile));
        shell.frame();
        shell.press();
        shell.frame();
        shell.pointerOn(menuItemNew);
        shell.frame();
        shell.release();
        shell.frame();
        assertTrue(ae.wasActivated(menuItemNew));
        assertFalse(ae.isMenuOpen(menuFile));
    }

    @Test
    void aSecondPressClosesAndMovingToAnotherTitleSwitches() {
        build();
        int file = ae.menuTitle(menuFile);
        shell.pointerOn(file);
        pressAndRelease();
        assertTrue(ae.isMenuOpen(menuFile));

        shell.pointerOn(ae.menuTitle(menuView));
        shell.frame();
        assertTrue(ae.isMenuOpen(menuView));
        assertFalse(ae.isMenuOpen(menuFile));

        pressAndRelease();
        assertFalse(ae.isMenuOpen(menuView), "a press on the open menu's title closes it");
    }

    @Test
    void pointingAtASubmenuItemOpensItBeside() {
        build();
        shell.pointerOn(ae.menuTitle(menuFile));
        pressAndRelease();
        shell.pointerOn(menuRecent);
        shell.frame();
        assertTrue(ae.isMenuOpen(recentMenu));
        assertTrue(layout.x(recentMenu) >= layout.x(menuFile) + layout.width(menuFile) - 2.0f);

        shell.pointerOn(menuItemSceneOne);
        pressAndRelease();
        assertFalse(ae.isMenuOpen(menuFile));
        assertFalse(ae.isMenuOpen(recentMenu));

        shell.pointerOn(ae.menuTitle(menuFile));
        pressAndRelease();
        shell.pointerOn(menuRecent);
        shell.frame();
        shell.pointerOn(menuItemNew);
        shell.frame();
        assertFalse(ae.isMenuOpen(recentMenu), "pointing at another item closes the submenu");
    }

    @Test
    void f10AndTheArrowsWorkTheMenusFromTheKeyboard() {
        build();
        shell.key(Keys.F10);
        assertTrue(ae.isMenuOpen(menuFile));
        assertEquals(menuItemNew, ae.menuPointedAt(menuFile), "opened by the keyboard, it points at the first item");

        shell.key(Keys.DOWN);
        shell.key(Keys.DOWN);
        assertEquals(menuRecent, ae.menuPointedAt(menuFile));
        shell.key(Keys.RIGHT);
        assertTrue(ae.isMenuOpen(recentMenu));
        assertEquals(menuItemSceneOne, ae.menuPointedAt(recentMenu));
        shell.key(Keys.LEFT);
        assertFalse(ae.isMenuOpen(recentMenu), "Left closes the submenu");
        assertTrue(ae.isMenuOpen(menuFile));

        shell.key(Keys.DOWN);
        assertEquals(menuItemQuit, ae.menuPointedAt(menuFile), "the separator is passed by");
        shell.key(Keys.DOWN);
        assertEquals(menuItemNew, ae.menuPointedAt(menuFile), "and past the last, around");

        shell.key(Keys.RIGHT);
        assertTrue(ae.isMenuOpen(menuView), "Right on a plain item goes to the next menu");
        shell.key(Keys.ENTER);
        assertTrue(ae.wasActivated(menuItemGrid));
        assertTrue(ae.isChecked(menuItemGrid), "a ticking item ticks");
        assertFalse(ae.isMenuOpen(menuView));
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
        assertTrue(ae.isMenuOpen(menuFile));
        shell.key(Keys.ESCAPE);
        assertFalse(ae.isMenuOpen(menuFile));
        assertEquals("", ae.text(field).toString());
        assertEquals(field, shell.tree.focused());

        shell.key(Keys.F10);
        shell.key(Keys.F10);
        assertFalse(ae.isMenuOpen(menuFile), "F10 again closes them all");
        assertDoesNotThrow(shell::draw);
    }

    @Test
    void aPressOutsideClosesTheMenus() {
        build();
        shell.pointerOn(ae.menuTitle(menuFile));
        pressAndRelease();
        shell.pointerOn(menuRecent);
        shell.frame();
        assertDoesNotThrow(shell::draw);
        shell.pointerAt(700.0f, 550.0f);
        pressAndRelease();
        assertFalse(ae.isMenuOpen(menuFile));
        assertFalse(ae.isMenuOpen(recentMenu));
    }
}
