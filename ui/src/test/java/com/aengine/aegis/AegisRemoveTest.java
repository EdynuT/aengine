package com.aengine.aegis;

import com.aengine.core.Keys;
import org.junit.jupiter.api.Test;

import java.io.StringReader;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Step 4e: widgets removed for good, and their nodes taken by the next ones made. */
class AegisRemoveTest {

    private final TestShell    shell  = new TestShell();
    private final AegisLayout  layout = shell.layout;
    private final AegisTree    tree   = shell.tree;
    private final AegisWidgets ae     = shell.ae;

    private final int list = layout.column(shell.root);

    @Test
    void aRemovedWidgetsNodesAreTakenByTheNextOnesMade() {
        int before = layout.nodesInUse();
        int ok = ae.button(list, "buttonOk", "OK");
        int handed = layout.nodeCount();

        ae.remove(ok);
        assertEquals(before, layout.nodesInUse());
        assertFalse(layout.isInUse(ok));
        assertEquals(AegisLayout.NONE, layout.firstChild(list), "out of its parent");

        int again = ae.label(list, "labelName", "Name");
        assertEquals(ok, again, "the handle given back is the one taken");
        assertEquals(handed, layout.nodeCount(), "no new handle handed out");
    }

    @Test
    void aPanelFilledAgainAndAgainDoesNotRunTheLayoutOutOfRoom() {
        for (int round = 0; round < 2000; round++) {   // 2000 * 5 labels is more than the 4096 nodes
            ae.removeChildren(list);
            for (int i = 0; i < 5; i++) ae.label(list, "labelEntity" + i, "Entity " + i);
            shell.frame();
        }
        assertEquals(5, countChildren(list));
    }

    @Test
    void aReusedHandleStartsClean() {
        int stop = ae.button(list, "buttonStop", "Stop");
        ae.setTooltip(stop, "Stops the scene.");
        ae.setEnabled(stop, false);
        ae.remove(stop);

        int play = ae.button(list, "buttonPlay", "Play");
        assertEquals(stop, play);
        assertNull(ae.shownTooltip(play), "the old tooltip is not inherited");
        assertTrue(ae.isEnabled(play));
        assertEquals("Play", ae.shownText(play));
    }

    @Test
    void focusInsideMovesOnToWhatTabWouldReachNext() {
        int a = ae.button(list, "buttonA", "A");
        int row = layout.row(list);
        int b = ae.button(row, "buttonB", "B");
        int c = ae.button(list, "buttonC", "C");
        tree.focus(b);

        ae.remove(row);
        assertEquals(c, tree.focused(), "past the row taken");

        ae.remove(c);
        assertEquals(a, tree.focused(), "from the last, round to the first");

        ae.remove(a);
        assertEquals(AegisLayout.NONE, tree.focused(), "nothing left to focus");
    }

    @Test
    void aComboBoxTakesItsOpenListWithIt() {
        int before = layout.nodesInUse();
        int combo = ae.comboBox(list, "comboShading");
        ae.addComboItem(combo, "lit", "Lit");
        ae.addComboItem(combo, "unlit", "Unlit");
        shell.frame();
        tree.focus(combo);
        shell.key(Keys.DOWN, Keys.MOD_ALT);
        assertTrue(ae.isAnyOverlayOpen());

        ae.remove(combo);
        shell.frame();
        assertFalse(ae.isAnyOverlayOpen(), "the list is closed");
        assertEquals(before, layout.nodesInUse(), "and given back, with the box");
    }

    @Test
    void aMenuGoesWithItsTitleItsItemsAndItsSubmenus() {
        int bar = ae.menuBar(list, "menuBarMain");
        int before = layout.nodesInUse();
        int file = ae.menu(bar, "menuFile", "File");
        ae.menuItem(file, "menuItemSave", "Save");
        int recent = ae.subMenu(file, "menuRecent", "Recent");
        ae.menuItem(recent, "menuItemFirst", "First");
        shell.click(ae.menuTitle(file));
        assertTrue(ae.isMenuOpen(file));

        ae.remove(file);
        shell.frame();
        assertFalse(ae.isAnyOverlayOpen());
        assertEquals(before, layout.nodesInUse());
        assertEquals(AegisLayout.NONE, layout.firstChild(bar), "the title is off the bar");
    }

    @Test
    void aMenusItemsAreRemovedAndTheMenuFilledAgain() {
        int bar = ae.menuBar(list, "menuBarMain");
        int file = ae.menu(bar, "menuFile", "File");
        int recent = ae.subMenu(file, "menuRecent", "Recent");
        ae.menuItem(recent, "menuItemFirst", "First");
        ae.menuItem(recent, "menuItemSecond", "Second");

        ae.removeChildren(recent);
        assertEquals(0, countChildren(recent));
        ae.menuItem(recent, "menuItemThird", "Third");
        assertEquals(1, countChildren(recent));
    }

    @Test
    void anOpenDialogIsClosedAndFocusGoesBackOutOfIt() {
        int ok = ae.button(list, "buttonOk", "OK");
        tree.focus(ok);
        int before = layout.nodesInUse();
        int dialog = ae.dialog("dialogAbout", "About");
        int close = ae.button(dialog, "buttonClose", "Close");
        ae.openDialog(dialog);
        shell.frame();
        tree.focus(close);

        ae.remove(dialog);
        shell.frame();
        assertFalse(ae.isAnyOverlayOpen());
        assertEquals(ok, tree.focused(), "back where it was before the dialog");
        assertEquals(before, layout.nodesInUse());
    }

    @Test
    void aTabbedPaneTakesItsTabsShownOrNot() {
        int before = layout.nodesInUse();
        int tabs = ae.tabbedPane(list, "tabsScene");
        int first = layout.column(AegisLayout.NONE);
        ae.label(first, "labelOne", "One");
        int second = layout.column(AegisLayout.NONE);
        ae.label(second, "labelTwo", "Two");
        ae.addTab(tabs, "tabFirst", "First", first);
        ae.addTab(tabs, "tabSecond", "Second", second);

        ae.remove(tabs);
        assertEquals(before, layout.nodesInUse());
    }

    @Test
    void partsOfWidgetsTabContentsAndPanelsAreRefused() {
        int tabs = ae.tabbedPane(list, "tabsScene");
        int content = layout.column(AegisLayout.NONE);
        ae.addTab(tabs, "tabFirst", "First", content);
        int combo = ae.comboBox(list, "comboShading");
        ae.addComboItem(combo, "lit", "Lit");

        assertThrows(IllegalArgumentException.class, () -> ae.remove(layout.firstChild(tabs)), "the tab bar");
        assertThrows(IllegalArgumentException.class, () -> ae.remove(content), "a tab's content");

        AegisScreens screens = shell.aegis.screens();
        int panel = screens.columnPanel("viewport");
        ae.button(panel, "buttonStop", "Stop");
        assertThrows(IllegalArgumentException.class, () -> ae.remove(panel));

        ae.remove(combo);
        assertThrows(IllegalArgumentException.class, () -> ae.remove(combo), "given back already");
    }

    @Test
    void restoringALayoutGivesTheOldArrangementBack() throws Exception {
        AegisScreens screens = shell.aegis.screens();
        int left = screens.columnPanel("left");
        int right = screens.columnPanel("right");
        ae.button(left, "buttonOk", "OK");
        String tabbed = """
            { "format": 1, "screens": { "editor": { "root": { "type": "row", "children": [
              { "type": "tabs", "panels": ["left", "right"] }
            ] } } } }
            """;
        screens.build("editor", AegisLayoutFile.parse(new StringReader(tabbed), "test"));
        int inUse = layout.nodesInUse();

        for (int i = 0; i < 50; i++) screens.rebuild("editor", AegisLayoutFile.parse(new StringReader(tabbed), "test"));
        assertEquals(inUse, layout.nodesInUse(), "each restore takes the nodes the last one gave back");
        assertTrue(layout.isInUse(left) && layout.isInUse(right), "the panels stay");
    }

    private int countChildren(int node) {
        int n = 0;
        for (int c = layout.firstChild(node); c != AegisLayout.NONE; c = layout.nextSibling(c)) n++;
        return n;
    }
}
