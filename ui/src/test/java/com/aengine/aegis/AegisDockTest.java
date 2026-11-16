package com.aengine.aegis;

import com.aengine.core.Keys;
import com.google.gson.JsonObject;
import org.junit.jupiter.api.Test;

import java.io.StringReader;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Step 4c: docks the user resizes by their edge, and the arrangement written down. */
class AegisDockTest {

    private final TestShell    shell   = new TestShell();
    private final AegisLayout  layout  = shell.layout;
    private final AegisTree    tree    = shell.tree;
    private final AegisWidgets ae      = shell.ae;
    private final AegisScreens screens = shell.aegis.screens();

    private final int left   = screens.columnPanel("left");
    private final int middle = screens.columnPanel("middle");
    private final int right  = screens.columnPanel("right");
    private final int button = ae.button(middle, "buttonOk", "OK");

    /** A row of three: left 200 and right 150, both resizable, around a middle that grows; gaps of 10. */
    private static final String THREE = """
        { "format": 1, "screens": { "editor": { "root": { "type": "row", "gap": 10, "alignY": "stretch", "children": [
          { "type": "panel", "panel": "left",   "width": 200, "resizable": true },
          { "type": "panel", "panel": "middle", "grow": 1 },
          { "type": "panel", "panel": "right",  "width": 150, "resizable": true }
        ] } } } }
        """;

    private int build(String json) throws Exception {
        AegisLayoutFile file = AegisLayoutFile.parse(new StringReader(json), "test");
        int root = screens.build("editor", file);
        layout.setAlignX(shell.root, AegisLayout.Align.STRETCH);
        layout.attach(root, shell.root);
        layout.setGrow(root, 1.0f);
        shell.frame();
        return root;
    }

    /** Drags from one point to another, a frame each: on, press, move, release. */
    private void drag(float fromX, float y, float toX) {
        shell.pointerAt(fromX, y);
        shell.frame();
        shell.press();
        shell.frame();
        shell.pointerAt(toX, y);
        shell.frame();
        shell.release();
        shell.frame();
    }

    @Test
    void theEdgeBesideTheGrowerIsDraggedAndTheDockFollows() throws Exception {
        build(THREE);
        assertEquals(200.0f, layout.width(left));

        drag(205.0f, 300.0f, 255.0f);   // the gap between left (0-200) and middle (210-)
        assertEquals(250.0f, layout.width(left), "the trailing edge, dragged right, widens it");
        assertTrue(layout.width(middle) < 800.0f - 250.0f - 150.0f, "the grower gives the room");
    }

    @Test
    void aDockAfterTheGrowerIsDraggedByItsLeadingEdge() throws Exception {
        build(THREE);
        float gapStart = layout.x(right) - 10.0f;
        drag(gapStart + 5.0f, 300.0f, gapStart - 45.0f);
        assertEquals(200.0f, layout.width(right), "dragged left, the dock on the right grows");
    }

    @Test
    void theArrangementChangeIsReportedWhenTheDragEnds() throws Exception {
        build(THREE);
        shell.pointerAt(205.0f, 300.0f);
        shell.frame();
        shell.press();
        shell.frame();
        shell.pointerAt(235.0f, 300.0f);
        shell.frame();
        assertFalse(ae.wasLayoutChanged(), "not while dragging");
        shell.release();
        shell.frame();
        assertTrue(ae.wasLayoutChanged(), "once, when let go");
        shell.frame();
        assertFalse(ae.wasLayoutChanged());
    }

    @Test
    void aDockKeepsItsLeastSizeAndLeavesTheGrowerItsLeast() throws Exception {
        build(THREE);
        drag(205.0f, 300.0f, 5.0f);
        assertEquals(80.0f, layout.width(left), "never below 80");
        drag(85.0f, 300.0f, 795.0f);
        assertTrue(layout.width(middle) >= 79.0f, "the grower keeps 80: " + layout.width(middle));
    }

    @Test
    void thePressOnAnEdgeClicksNothingAndKeepsFocus() throws Exception {
        build(THREE);
        tree.focus(button);
        drag(205.0f, 300.0f, 225.0f);
        assertEquals(button, tree.focused());
    }

    @Test
    void f8GivesTheKeyboardToTheEdgesInTurnAndEscGivesItBack() throws Exception {
        build(THREE);
        tree.focus(button);
        shell.key(Keys.F8);
        assertEquals(0, ae.focusedDock());
        shell.key(Keys.RIGHT);
        shell.key(Keys.RIGHT);
        shell.frame();
        assertEquals(220.0f, layout.width(left), "two steps of 10");

        shell.key(Keys.F8);
        assertEquals(1, ae.focusedDock(), "the next edge");
        shell.key(Keys.LEFT, Keys.MOD_SHIFT);
        shell.frame();
        assertEquals(200.0f, layout.width(right), "Shift: five steps, the leading edge moved left widens it");

        shell.key(Keys.ESCAPE);
        assertEquals(-1, ae.focusedDock());
        assertEquals(button, tree.focused(), "the keyboard goes back where it was");
    }

    @Test
    void aResizableNodeNeedsASizeAndAGrowerBesideIt() throws Exception {
        build("""
            { "format": 1, "screens": { "editor": { "root": { "type": "row", "gap": 10, "children": [
              { "type": "panel", "panel": "left", "resizable": true, "grow": 1 },
              { "type": "panel", "panel": "middle", "width": 300, "resizable": true },
              { "type": "panel", "panel": "right", "width": 100 }
            ] } } } }
            """);
        shell.key(Keys.F8);
        assertEquals(0, ae.focusedDock(), "only middle, beside the grower, became a dock");
        shell.key(Keys.F8);
        assertEquals(-1, ae.focusedDock(), "past the last edge, out");
    }

    @Test
    void describeWritesTheScreenWithTheSizesTheUserLeft() throws Exception {
        build(THREE);
        drag(205.0f, 300.0f, 245.0f);
        JsonObject screen = screens.describe("editor");
        JsonObject root = screen.getAsJsonObject("root");
        assertEquals("row", root.get("type").getAsString());
        assertEquals(10, root.get("gap").getAsInt());
        JsonObject first = root.getAsJsonArray("children").get(0).getAsJsonObject();
        assertEquals("left", first.get("panel").getAsString());
        assertEquals("240", first.get("width").toString(), "whole, written without a fraction");
        assertTrue(first.get("resizable").getAsBoolean());
        JsonObject second = root.getAsJsonArray("children").get(1).getAsJsonObject();
        assertFalse(second.has("width"), "what the file left out stays out");

        // What is written reads back as a layout, to the same arrangement.
        JsonObject top = new JsonObject();
        top.addProperty("format", 1);
        JsonObject all = new JsonObject();
        all.add("editor", screen);
        top.add("screens", all);
        AegisLayoutFile again = AegisLayoutFile.parse(new StringReader(top.toString()), "written");
        assertEquals(240.0f, again.screen("editor").children().get(0).width());
    }
}
