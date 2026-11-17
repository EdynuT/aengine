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

    private int treeScene, world, camera, lights, sun, fill, player;

    /**
     * World            (open)
     *   Main Camera
     *   Lights         (closed)
     *     Sun
     *     Fill light
     * Player
     */
    private void build() {
        treeScene = ae.treeView(shell.root, "treeScene");
        world  = ae.addTreeItem(treeScene, AegisWidgets.NO_ITEM, "World");
        camera = ae.addTreeItem(treeScene, world, "Main Camera");
        lights = ae.addTreeItem(treeScene, world, "Lights");
        sun    = ae.addTreeItem(treeScene, lights, "Sun");
        fill   = ae.addTreeItem(treeScene, lights, "Fill light");
        player = ae.addTreeItem(treeScene, AegisWidgets.NO_ITEM, "Player");
        ae.setTreeItemExpanded(treeScene, world, true);
        shell.frame();
    }

    private float rowHeight() {
        AegisStyle style = ae.style();
        return shell.aegis.lineHeight() + style.treePaddingY * 2.0f;
    }

    /** The pointer on a row, at an x from the tree's left. */
    private void pressRow(int row, float x) {
        shell.pointerAt(layout.x(treeScene) + x, layout.y(treeScene) + row * rowHeight() + 3.0f);
        shell.frame();
        shell.press();
        shell.frame();
        shell.release();
        shell.frame();
    }

    @Test
    void itAsksForTheRowsShownAndGrowsAsItemsOpen() {
        build();
        assertEquals(4 * rowHeight(), layout.height(treeScene), "World, Main Camera, Lights, Player");
        ae.setTreeItemExpanded(treeScene, lights, true);
        shell.frame();
        assertEquals(6 * rowHeight(), layout.height(treeScene));
    }

    @Test
    void aPressSelectsAndOnTheArrowOpens() {
        build();
        pressRow(2, 120.0f);                                    // Lights' text
        assertEquals(lights, ae.selectedTreeItem(treeScene));
        assertFalse(ae.isTreeItemExpanded(treeScene, lights));

        float arrow = ae.style().treePaddingX + ae.style().treeIndent + 4.0f;   // level 1's arrow
        pressRow(2, arrow);
        assertTrue(ae.isTreeItemExpanded(treeScene, lights));
        shell.frame();
        assertEquals(6 * rowHeight(), layout.height(treeScene));
    }

    @Test
    void aDoubleClickOpensAndActivates() {
        build();
        shell.pointerAt(layout.x(treeScene) + 120.0f, layout.y(treeScene) + 2 * rowHeight() + 3.0f);
        shell.frame();
        shell.press();
        shell.frame();
        assertTrue(ae.wasChanged(treeScene));
        shell.release();
        shell.frame();
        shell.waitMillis(200);
        shell.press();
        shell.frame();
        assertTrue(ae.isTreeItemExpanded(treeScene, lights));
        assertTrue(ae.wasActivated(treeScene));
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
        assertTrue(ae.isTreeItemExpanded(treeScene, lights));
        assertFalse(ae.wasActivated(treeScene));
    }

    @Test
    void theArrowKeysWalkOpenAndClose() {
        build();
        shell.tab();
        assertEquals(treeScene, shell.tree.focused());

        shell.key(Keys.DOWN);
        assertEquals(world, ae.selectedTreeItem(treeScene), "nothing selected: the first key starts at the top");
        shell.key(Keys.DOWN);
        assertEquals(camera, ae.selectedTreeItem(treeScene));
        shell.key(Keys.DOWN);
        assertEquals(lights, ae.selectedTreeItem(treeScene));

        shell.key(Keys.RIGHT);
        assertTrue(ae.isTreeItemExpanded(treeScene, lights), "Right opens a closed item");
        assertEquals(lights, ae.selectedTreeItem(treeScene));
        shell.key(Keys.RIGHT);
        assertEquals(sun, ae.selectedTreeItem(treeScene), "and then goes to its first child");

        shell.key(Keys.LEFT);
        assertEquals(lights, ae.selectedTreeItem(treeScene), "Left goes to the parent");
        shell.key(Keys.LEFT);
        assertFalse(ae.isTreeItemExpanded(treeScene, lights), "then closes it");

        shell.key(Keys.END);
        assertEquals(player, ae.selectedTreeItem(treeScene));
        shell.key(Keys.HOME);
        assertEquals(world, ae.selectedTreeItem(treeScene));
        shell.key(Keys.UP);
        assertEquals(world, ae.selectedTreeItem(treeScene), "stops at the top");

        shell.key(Keys.ENTER);
        assertTrue(ae.wasActivated(treeScene));
    }

    @Test
    void closingAnItemMovesTheSelectionOutOfIt() {
        build();
        ae.selectTreeItem(treeScene, fill);
        assertTrue(ae.isTreeItemExpanded(treeScene, lights), "selecting from code shows the item");
        ae.setTreeItemExpanded(treeScene, world, false);
        assertEquals(world, ae.selectedTreeItem(treeScene));
        shell.frame();
        assertFalse(ae.wasChanged(treeScene));
    }

    @Test
    void typingALetterGoesToTheNextItemStartingWithIt() {
        build();
        ae.setTreeItemExpanded(treeScene, lights, true);
        shell.tab();
        shell.type("p");
        assertEquals(player, ae.selectedTreeItem(treeScene));
        shell.type("L");
        assertEquals(lights, ae.selectedTreeItem(treeScene), "around from the top, either case");
    }

    @Test
    void inAScrollingColumnTheSelectionIsKeptInView() {
        int pane = layout.column(shell.root);
        layout.setSize(pane, 300.0f, 80.0f);
        layout.setScrollable(pane, true);
        treeScene = ae.treeView(pane, "treeScene");
        int last = AegisWidgets.NO_ITEM;
        for (int i = 0; i < 30; i++) last = ae.addTreeItem(treeScene, AegisWidgets.NO_ITEM, "Entity " + i);
        shell.frame();
        shell.tab();
        shell.key(Keys.END);
        assertEquals(last, ae.selectedTreeItem(treeScene));
        float rowTop = layout.y(treeScene) + 29 * rowHeight();
        assertTrue(rowTop + rowHeight() <= layout.y(pane) + layout.height(pane) + 0.5f, "the last row is in view");
        assertDoesNotThrow(shell::draw);
    }
}
