package com.aengine.aegis;

import com.aengine.core.Keys;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Step 4f: context menus, opened by a secondary click or a key. */
class AegisContextMenuTest {

    private final TestShell    shell  = new TestShell();
    private final AegisLayout  layout = shell.layout;
    private final AegisTree    tree   = shell.tree;
    private final AegisWidgets ae     = shell.ae;

    private final int panel  = layout.column(shell.root);
    private final int button = ae.button(panel, "buttonOk", "OK");
    private final int menu   = ae.contextMenu("menuPanel");
    private final int copy   = ae.menuItem(menu, "menuItemCopy", "Copy");
    private final int paste  = ae.menuItem(menu, "menuItemPaste", "Paste");

    { ae.setContextMenu(panel, menu); }

    @Test
    void aSecondaryClickOpensTheMenuWhereThePointerIs() {
        shell.secondaryClick(button);
        assertTrue(ae.isMenuOpen(menu));
        assertTrue(ae.wasContextMenuOpened(menu), "on the frame it opened");
        assertEquals(button, ae.contextTarget(), "what was clicked");
        assertEquals(layout.x(button) + layout.width(button) * 0.5f, layout.x(menu), 0.5f);
        assertEquals(button, tree.focused(), "what was clicked takes focus");

        shell.frame();
        assertFalse(ae.wasContextMenuOpened(menu), "and only then");
        assertTrue(ae.isMenuOpen(menu));
    }

    @Test
    void anItemClickedIsActivatedAndTheMenuCloses() {
        shell.secondaryClick(button);
        shell.click(paste);
        assertFalse(ae.isMenuOpen(menu));
        // The click's frames have passed; what it activated was reported on one of them.
        shell.secondaryClick(button);
        shell.frame();
        shell.pointerOn(copy);
        shell.frame();
        shell.press();
        shell.frame();
        shell.release();
        shell.frame();
        assertTrue(ae.wasActivated(copy));
    }

    @Test
    void aDragWithTheSecondaryButtonOpensNothing() {
        shell.pointerOn(button);
        shell.frame();
        shell.pressSecondary();
        shell.frame();
        shell.pointerAt(layout.x(button) + 30.0f, layout.y(button) + 2.0f);
        shell.frame();
        shell.pointerOn(button);
        shell.frame();
        shell.releaseSecondary();
        shell.frame();
        assertFalse(ae.isMenuOpen(menu), "looking around a scene is not a click");
    }

    @Test
    void aKeyPressedWhileTheButtonIsHeldOpensNothing() {
        shell.pointerOn(button);
        shell.frame();
        shell.pressSecondary();
        shell.frame();
        shell.key(Keys.W);
        shell.releaseSecondary();
        shell.frame();
        assertFalse(ae.isMenuOpen(menu), "flying the camera is not a click");
    }

    @Test
    void theNearestMenuAboveTheClickIsTheOneOpened() {
        int row = layout.row(panel);
        int label = ae.label(row, "labelName", "Name");
        int inner = ae.contextMenu("menuRow");
        ae.menuItem(inner, "menuItemRename", "Rename");
        ae.setContextMenu(row, inner);

        shell.secondaryClick(label);
        assertTrue(ae.isMenuOpen(inner));
        assertFalse(ae.isMenuOpen(menu));
        assertEquals(inner, ae.contextMenuFor(label));
        assertEquals(menu, ae.contextMenuFor(button));
    }

    @Test
    void whereNoMenuIsGivenNothingOpens() {
        ae.setContextMenu(panel, AegisLayout.NONE);
        shell.secondaryClick(button);
        assertFalse(ae.isAnyOverlayOpen());
    }

    @Test
    void shiftF10AndTheMenuKeyOpenTheFocusedWidgetsMenuAtItsFirstItem() {
        tree.focus(button);
        shell.key(Keys.F10, Keys.MOD_SHIFT);
        assertTrue(ae.isMenuOpen(menu));
        assertEquals(copy, ae.menuPointedAt(menu), "the keyboard points at the first item");
        assertEquals(button, ae.contextTarget());
        shell.key(Keys.DOWN);
        shell.key(Keys.ENTER);
        assertTrue(ae.wasActivated(paste));
        assertFalse(ae.isMenuOpen(menu));
        assertEquals(button, tree.focused(), "focus stays where it was");

        shell.key(Keys.MENU);
        assertTrue(ae.isMenuOpen(menu));
        shell.key(Keys.ESCAPE);
        assertFalse(ae.isMenuOpen(menu));
        assertEquals(button, tree.focused());
    }

    @Test
    void aDisabledItemIsPassedByTheKeysAndNeverChosen() {
        ae.setEnabled(copy, false);
        tree.focus(button);
        shell.key(Keys.MENU);
        assertEquals(paste, ae.menuPointedAt(menu), "the first item that can be used");
        shell.key(Keys.DOWN);
        assertEquals(paste, ae.menuPointedAt(menu), "round past the disabled one, back to itself");

        ae.setEnabled(paste, false);
        shell.key(Keys.ESCAPE);
        shell.key(Keys.MENU);
        shell.key(Keys.ENTER);
        assertFalse(ae.wasActivated(copy) || ae.wasActivated(paste));
    }

    @Test
    void leftAndRightOnlyGoInAndOutOfSubmenus() {
        int more = ae.subMenu(menu, "menuMore", "More");
        ae.menuItem(more, "menuItemDeeper", "Deeper");
        tree.focus(button);
        shell.key(Keys.MENU);
        shell.key(Keys.RIGHT);
        assertTrue(ae.isMenuOpen(menu), "no titles beside it to go to");
        shell.key(Keys.UP);              // round to the last item: the submenu's
        shell.key(Keys.RIGHT);
        assertTrue(ae.isMenuOpen(more));
        shell.key(Keys.LEFT);
        assertFalse(ae.isMenuOpen(more));
        shell.key(Keys.LEFT);
        assertTrue(ae.isMenuOpen(menu), "at the top Left does nothing");
    }

    @Test
    void aPressOutsideClosesItAndASecondaryClickElsewhereMovesIt() {
        shell.secondaryClick(button);
        shell.pointerAt(700.0f, 500.0f);
        shell.frame();
        shell.press();
        shell.frame();
        shell.release();
        shell.frame();
        assertFalse(ae.isMenuOpen(menu));

        shell.secondaryClick(button);
        float first = layout.x(menu);
        shell.pointerAt(layout.x(button) + 5.0f, layout.y(button) + 5.0f);
        shell.frame();
        shell.pressSecondary();
        shell.frame();
        shell.releaseSecondary();
        shell.frame();
        assertTrue(ae.isMenuOpen(menu));
        assertTrue(layout.x(menu) < first, "opened again where the second click was");
    }

    @Test
    void aSecondaryClickOnATabShowsItFirst() {
        layout.setAlignX(shell.root, AegisLayout.Align.STRETCH);
        layout.setAlignX(panel, AegisLayout.Align.STRETCH);
        int tabs = ae.tabbedPane(panel, "tabsScene");
        ae.addTab(tabs, "tabFirst", "First", layout.column(AegisLayout.NONE));
        ae.addTab(tabs, "tabSecond", "Second", layout.column(AegisLayout.NONE));
        shell.frame();
        int bar = layout.firstChild(tabs);
        float x = layout.x(bar);
        while (ae.tabAt(bar, x) != 1) x += 1.0f;

        shell.pointerAt(x + 2.0f, layout.y(bar) + layout.height(bar) * 0.5f);
        shell.frame();
        shell.pressSecondary();
        shell.frame();
        shell.releaseSecondary();
        shell.frame();
        assertEquals(1, ae.selectedTab(tabs));
        assertEquals(bar, ae.contextTarget());
        assertTrue(ae.isMenuOpen(menu));
    }

    @Test
    void aContextMenuRemovedIsTakenOffTheWidgetsItWasGiven() {
        ae.remove(menu);
        assertEquals(AegisLayout.NONE, ae.contextMenuFor(button));
        shell.secondaryClick(button);
        assertFalse(ae.isAnyOverlayOpen());
    }

    @Test
    void onlyAMenuContextMenuMadeIsGiven() {
        int bar = ae.menuBar(panel, "menuBarMain");
        int file = ae.menu(bar, "menuFile", "File");
        assertThrows(IllegalArgumentException.class, () -> ae.setContextMenu(panel, file));
        assertThrows(IllegalArgumentException.class, () -> ae.contextMenu("panelMenu"));
    }

    @Test
    void itsItemsAreTranslatedByTheMenusName() throws Exception {
        ae.applyLocale(AegisLocale.parse(new java.io.StringReader("""
            { "format": 1, "menuPanel.menuItemCopy": "Copiar" }
            """), "test"));
        assertEquals("Copiar", ae.shownText(copy));
    }
}
