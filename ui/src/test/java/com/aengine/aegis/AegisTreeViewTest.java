package com.aengine.aegis;

import com.aengine.core.Keys;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Step 3g-6: the tree, worked by pointer and keyboard. */
class AegisTreeViewTest {

    private final TestShell    shell  = new TestShell();
    private final AegisLayout  layout = shell.layout;
    private final AegisWidgets ae     = shell.ae;

    private int sceneTree, world, camera, lights, sun, fill, player;

    /**
     * World            (open)
     *   Main Camera
     *   Lights         (closed)
     *     Sun
     *     Fill light
     * Player
     */
    private void build() {
        sceneTree = ae.treeView(shell.root, "sceneTree");
        world  = ae.addTreeItem(sceneTree, AegisWidgets.NO_ITEM, "World");
        camera = ae.addTreeItem(sceneTree, world, "Main Camera");
        lights = ae.addTreeItem(sceneTree, world, "Lights");
        sun    = ae.addTreeItem(sceneTree, lights, "Sun");
        fill   = ae.addTreeItem(sceneTree, lights, "Fill light");
        player = ae.addTreeItem(sceneTree, AegisWidgets.NO_ITEM, "Player");
        ae.setTreeItemExpanded(sceneTree, world, true);
        shell.frame();
    }

    private float rowHeight() {
        AegisStyle style = ae.style();
        return shell.aegis.lineHeight() + style.treePaddingY * 2.0f;
    }

    /** The pointer on a row, at an x from the tree's left. */
    private void pressRow(int row, float x) {
        shell.pointerAt(layout.x(sceneTree) + x, layout.y(sceneTree) + row * rowHeight() + 3.0f);
        shell.frame();
        shell.press();
        shell.frame();
        shell.release();
        shell.frame();
    }

    @Test
    void itAsksForTheRowsShownAndGrowsAsItemsOpen() {
        build();
        assertEquals(4 * rowHeight(), layout.height(sceneTree), "World, Main Camera, Lights, Player");
        ae.setTreeItemExpanded(sceneTree, lights, true);
        shell.frame();
        assertEquals(6 * rowHeight(), layout.height(sceneTree));
    }

    @Test
    void aPressSelectsAndOnTheArrowOpens() {
        build();
        pressRow(2, 120.0f);                                    // Lights' text
        assertEquals(lights, ae.selectedTreeItem(sceneTree));
        assertFalse(ae.isTreeItemExpanded(sceneTree, lights));

        float arrow = ae.style().treePaddingX + ae.style().treeIndent + 4.0f;   // level 1's arrow
        pressRow(2, arrow);
        assertTrue(ae.isTreeItemExpanded(sceneTree, lights));
        shell.frame();
        assertEquals(6 * rowHeight(), layout.height(sceneTree));
    }

    @Test
    void aDoubleClickOpensAndActivates() {
        build();
        shell.pointerAt(layout.x(sceneTree) + 120.0f, layout.y(sceneTree) + 2 * rowHeight() + 3.0f);
        shell.frame();
        shell.press();
        shell.frame();
        assertTrue(ae.wasChanged(sceneTree));
        shell.release();
        shell.frame();
        shell.waitMillis(200);
        shell.press();
        shell.frame();
        assertTrue(ae.isTreeItemExpanded(sceneTree, lights));
        assertTrue(ae.wasActivated(sceneTree));
        shell.release();
        shell.frame();

        shell.waitMillis(900);                                  // too slow: two single clicks
        shell.press();
        shell.frame();
        shell.release();
        shell.frame();
        shell.waitMillis(900);
        shell.press();
        shell.frame();
        assertTrue(ae.isTreeItemExpanded(sceneTree, lights));
        assertFalse(ae.wasActivated(sceneTree));
    }

    @Test
    void theArrowKeysWalkOpenAndClose() {
        build();
        shell.tab();
        assertEquals(sceneTree, shell.tree.focused());

        shell.key(Keys.DOWN);
        assertEquals(world, ae.selectedTreeItem(sceneTree), "nothing selected: the first key starts at the top");
        shell.key(Keys.DOWN);
        assertEquals(camera, ae.selectedTreeItem(sceneTree));
        shell.key(Keys.DOWN);
        assertEquals(lights, ae.selectedTreeItem(sceneTree));

        shell.key(Keys.RIGHT);
        assertTrue(ae.isTreeItemExpanded(sceneTree, lights), "Right opens a closed item");
        assertEquals(lights, ae.selectedTreeItem(sceneTree));
        shell.key(Keys.RIGHT);
        assertEquals(sun, ae.selectedTreeItem(sceneTree), "and then goes to its first child");

        shell.key(Keys.LEFT);
        assertEquals(lights, ae.selectedTreeItem(sceneTree), "Left goes to the parent");
        shell.key(Keys.LEFT);
        assertFalse(ae.isTreeItemExpanded(sceneTree, lights), "then closes it");

        shell.key(Keys.END);
        assertEquals(player, ae.selectedTreeItem(sceneTree));
        shell.key(Keys.HOME);
        assertEquals(world, ae.selectedTreeItem(sceneTree));
        shell.key(Keys.UP);
        assertEquals(world, ae.selectedTreeItem(sceneTree), "stops at the top");

        shell.key(Keys.ENTER);
        assertTrue(ae.wasActivated(sceneTree));
    }

    @Test
    void closingAnItemMovesTheSelectionOutOfIt() {
        build();
        ae.selectTreeItem(sceneTree, fill);
        assertTrue(ae.isTreeItemExpanded(sceneTree, lights), "selecting from code shows the item");
        ae.setTreeItemExpanded(sceneTree, world, false);
        assertEquals(world, ae.selectedTreeItem(sceneTree));
        shell.frame();
        assertFalse(ae.wasChanged(sceneTree));
    }

    @Test
    void typingALetterGoesToTheNextItemStartingWithIt() {
        build();
        ae.setTreeItemExpanded(sceneTree, lights, true);
        shell.tab();
        shell.type("p");
        assertEquals(player, ae.selectedTreeItem(sceneTree));
        shell.type("L");
        assertEquals(lights, ae.selectedTreeItem(sceneTree), "around from the top, either case");
    }

    @Test
    void inAScrollingColumnTheSelectionIsKeptInView() {
        int pane = layout.column(shell.root);
        layout.setSize(pane, 300.0f, 80.0f);
        layout.setScrollable(pane, true);
        sceneTree = ae.treeView(pane, "sceneTree");
        int last = AegisWidgets.NO_ITEM;
        for (int i = 0; i < 30; i++) last = ae.addTreeItem(sceneTree, AegisWidgets.NO_ITEM, "Entity " + i);
        shell.frame();
        shell.tab();
        shell.key(Keys.END);
        assertEquals(last, ae.selectedTreeItem(sceneTree));
        float rowTop = layout.y(sceneTree) + 29 * rowHeight();
        assertTrue(rowTop + rowHeight() <= layout.y(pane) + layout.height(pane) + 0.5f, "the last row is in view");
        assertDoesNotThrow(shell::draw);
    }
}
