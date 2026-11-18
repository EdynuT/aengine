package com.aengine.aegis;

import org.junit.jupiter.api.Test;

import java.io.StringReader;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** Layout files, and screens built from them: the strict reading, moving, hiding, falling back. */
class AegisScreensTest {

    private static final String FACTORY = """
        {
          "name": "Factory", "format": 1,
          "screens": { "editor": { "root": { "type": "column", "children": [
            { "type": "panel", "panel": "toolbar", "height": 80 },
            { "type": "panel", "panel": "viewport", "grow": 1 }
          ]}}}
        }
        """;

    private final TestShell    shell   = new TestShell();
    private final AegisLayout  layout  = shell.layout;
    private final AegisWidgets ae      = shell.ae;
    private final AegisScreens screens = shell.aegis.screens();

    private final int toolbar  = screens.rowPanel("toolbar");
    private final int viewport = screens.columnPanel("viewport");
    private final int save     = ae.button(toolbar, "buttonSave", "Save");
    private final int play     = ae.button(viewport, "buttonPlay", "Play");
    private final int stop     = ae.button(viewport, "buttonStop", "Stop");

    @Test
    void aScreenIsBuiltFromTheFileWithThePanelsCodeMade() throws Exception {
        int root = screens.build("editor", file(FACTORY));

        assertEquals(toolbar, layout.firstChild(root));
        assertEquals(viewport, layout.nextSibling(toolbar));
        assertEquals(80.0f, layout.requestedHeight(toolbar));
        assertEquals(play, layout.firstChild(viewport), "a panel the file does not arrange keeps code's order");
    }

    @Test
    void rebuildingTakesBackWhatTheOldFileDidInsideThePanels() throws Exception {
        // A layout chosen after one that moved, hid and grouped widgets: the panels it leaves
        // alone are as code made them, not as the old file left them.
        screens.build("editor", file(FACTORY));
        int nodes = layout.nodesInUse();
        float stopWidth = layout.requestedWidth(stop);

        screens.rebuild("editor", file("""
            { "name": "Moved", "format": 1,
              "screens": { "editor": { "root": { "type": "column", "children": [
                { "type": "panel", "panel": "toolbar", "children": [
                  { "type": "row", "children": [ { "type": "widget", "widget": "buttonStop", "width": 140 } ] },
                  { "type": "widget", "widget": "buttonSave" }
                ]},
                { "type": "panel", "panel": "viewport", "children": [
                  { "type": "widget", "widget": "buttonPlay", "hidden": true }
                ]}
              ]}}}
            }
            """));
        assertEquals(toolbar, layout.parent(layout.parent(stop)), "moved into a row of the toolbar");

        int root = screens.rebuild("editor", file(FACTORY));
        assertEquals(play, layout.firstChild(viewport), "the hidden widget is back");
        assertEquals(stop, layout.nextSibling(play), "the moved one is back in its panel, in code order");
        assertEquals(save, layout.firstChild(toolbar));
        assertEquals(AegisLayout.NONE, layout.nextSibling(save), "the row the old file made is gone");
        assertEquals(stopWidth, layout.requestedWidth(stop), "the width the old file gave is taken back");
        assertEquals(nodes, layout.nodesInUse(), "nothing the old file made is left behind");
        assertEquals(toolbar, layout.firstChild(root));
    }

    @Test
    void aWidgetMovesToAnotherPanelAndTheUnplacedGoToTheEnd() throws Exception {
        int root = screens.build("editor", file("""
            { "name": "Moved", "format": 1,
              "screens": { "editor": { "root": { "type": "column", "children": [
                { "type": "panel", "panel": "toolbar", "children": [
                  { "type": "widget", "widget": "buttonStop", "width": 140 },
                  { "type": "widget", "widget": "buttonSave" }
                ]},
                { "type": "panel", "panel": "viewport", "children": [ ] }
              ]}}}
            }
            """));

        assertEquals(toolbar, layout.firstChild(root));
        assertEquals(stop, layout.firstChild(toolbar));
        assertEquals(save, layout.nextSibling(stop));
        assertEquals(140.0f, layout.requestedWidth(stop));
        assertEquals(play, layout.firstChild(viewport), "left out by the file, back at the end of its panel");
        assertEquals(play, layout.lastChild(viewport));
    }

    @Test
    void aHiddenWidgetIsOutOfTheTree() throws Exception {
        screens.build("editor", file("""
            { "name": "Hidden", "format": 1,
              "screens": { "editor": { "root": { "type": "column", "children": [
                { "type": "panel", "panel": "toolbar" },
                { "type": "panel", "panel": "viewport", "children": [
                  { "type": "widget", "widget": "buttonPlay", "hidden": true },
                  { "type": "widget", "widget": "buttonStop" }
                ]}
              ]}}}
            }
            """));

        assertEquals(AegisLayout.NONE, layout.parent(play));
        assertEquals(stop, layout.firstChild(viewport));
        assertEquals(stop, layout.lastChild(viewport));
    }

    @Test
    void tabsInAFileBecomeATabbedPaneWithATabPerPanel() throws Exception {
        screens.setPanelTitle("viewport", "Scene view");
        int root = screens.build("editor", file("""
            { "name": "Tabbed", "format": 1,
              "screens": { "editor": { "root": { "type": "row", "alignY": "stretch", "children": [
                { "type": "tabs", "width": 420, "panels": ["toolbar", "viewport"] }
              ]}}}
            }
            """));

        int pane = layout.firstChild(root);
        assertEquals(2, ae.tabCount(pane));
        assertEquals(420.0f, layout.requestedWidth(pane));
        assertEquals(toolbar, layout.lastChild(pane), "the first panel listed is the one shown");
        assertEquals(AegisLayout.NONE, layout.parent(viewport));

        ae.selectTab(pane, 1);
        assertEquals(viewport, layout.lastChild(pane));
        assertEquals(play, layout.firstChild(viewport), "with its widgets as code made them");
    }

    @Test
    void aPanelInTabsCannotBeShownAgain() throws Exception {
        AegisLayoutFile twice = file("""
            { "name": "Twice", "format": 1,
              "screens": {
                "editor": { "root": { "type": "tabs", "panels": ["toolbar", "viewport"] } },
                "other":  { "root": { "type": "panel", "panel": "viewport" } }
              }
            }
            """);
        screens.build("editor", twice);
        assertThrows(IllegalStateException.class, () -> screens.build("other", twice),
            "viewport is in the editor's tabs, though not the one shown");
    }

    @Test
    void aScreenWithAnErrorIsSetAsideForTheNextFileInLine() throws Exception {
        AegisLayoutFile broken = file("""
            { "name": "Broken", "format": 1,
              "screens": { "editor": { "root": { "type": "column", "children": [
                { "type": "panel", "panel": "toolbar", "height": "tall" },
                { "type": "panel", "panel": "viewport" }
              ]}}}
            }
            """);
        assertNull(broken.screen("editor"), "a wrong value sets the whole screen aside");
        assertNotNull(broken.setAsideScreen("editor"));

        AegisLayoutFile unknownPanel = file("""
            { "name": "Outdated", "format": 1,
              "screens": { "editor": { "root": { "type": "column", "children": [
                { "type": "panel", "panel": "console" }
              ]}}}
            }
            """);

        int root = screens.build("editor", broken, unknownPanel, file(FACTORY));

        assertEquals(toolbar, layout.firstChild(root), "the factory layout, last in line, was built");
        assertEquals(80.0f, layout.requestedHeight(toolbar));
    }

    @Test
    void stretchAlongTheWayARowOrColumnStacksIsAnErrorNotACrash() throws Exception {
        AegisLayoutFile row = file("""
            { "format": 1, "screens": { "editor": { "root": { "type": "row", "alignX": "stretch", "children": [
              { "type": "panel", "panel": "viewport" } ] } } } }
            """);
        assertNull(row.screen("editor"), "a row stretched along X is set aside as it is read");

        AegisLayoutFile column = file("""
            { "format": 1, "screens": { "editor": { "root": { "type": "column", "alignY": "stretch", "children": [
              { "type": "panel", "panel": "viewport" } ] } } } }
            """);
        assertNull(column.screen("editor"));

        // A panel is a row or a column by its code: told only when the screen is built.
        AegisLayoutFile rowPanel = file("""
            { "format": 1, "screens": { "editor": { "root": { "type": "column", "children": [
              { "type": "panel", "panel": "toolbar", "alignX": "stretch" },
              { "type": "panel", "panel": "viewport", "alignX": "stretch" } ] } } } }
            """);
        int root = screens.build("editor", row, column, rowPanel, file(FACTORY));
        assertEquals(80.0f, layout.requestedHeight(toolbar), "the factory layout, after all three");
        assertEquals(toolbar, layout.firstChild(root));
    }

    @Test
    void withNoUsableFileInLineBuildingFails() throws Exception {
        AegisLayoutFile unknownPanel = file("""
            { "name": "Outdated", "format": 1,
              "screens": { "editor": { "root": { "type": "panel", "panel": "console" } } }
            }
            """);
        assertThrows(IllegalStateException.class, () -> screens.build("editor", unknownPanel));
    }

    @Test
    void whatIsNotJsonIsUnusable() {
        assertThrows(AegisLayoutFile.Unusable.class, () -> file("{ \"name\": "));
    }

    @Test
    void panelNamesAreUnique() {
        assertThrows(IllegalArgumentException.class, () -> screens.rowPanel("toolbar"));
    }

    private static AegisLayoutFile file(String text) throws AegisLayoutFile.Unusable {
        return AegisLayoutFile.parse(new StringReader(text), "test");
    }
}
