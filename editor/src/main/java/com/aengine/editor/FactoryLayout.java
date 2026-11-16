package com.aengine.editor;

import com.aengine.aegis.AegisLayoutFile;

import java.io.StringReader;

/**
 * The editor's layout as the engine ships it, compiled in: what a screen falls back to when
 * neither the user's {@code layout.json} nor the installation's describes it (§7, <em>Two
 * files</em>).
 *
 * <p>Written in the layout file's own format and read by the same reader, so there is one way
 * a screen is described and one way it is built, and the shell's designer can copy it into a
 * file as a starting point. It names the panels the editor registers; a mistake in it is a bug
 * in the engine, and is reported like any file's.</p>
 */
public final class FactoryLayout {

    private FactoryLayout() { }

    /** Where it says it comes from, in the log. */
    public static final String SOURCE = "factory layout";

    /**
     * The editor screen, arranged as Godot's and Unity's are — step 4a. The menu bar and the
     * toolbar across the top, the status bar across the bottom; between them the hierarchy
     * over the assets on the left, the viewport over the console and output in the middle,
     * taking the room the sides leave, and the inspector the whole height on the right. Every
     * dock is a {@code "tabs"}, so each shows its title and more panels can share it. Sizes
     * and spacing between panels are here; spacing inside a panel is its code's.
     */
    private static final String TEXT = """
        {
          "name": "Factory",
          "description": "Menu bar and toolbar on top, status bar below; hierarchy over assets on the left, viewport over console and output in the middle, inspector on the right.",
          "format": 1,

          "screens": {
            "editor": {
              "root": { "type": "column", "padding": 6, "gap": 6, "alignX": "stretch", "children": [
                { "type": "panel", "panel": "menu",    "height": 28 },
                { "type": "panel", "panel": "toolbar", "height": 40 },
                { "type": "row", "grow": 1, "gap": 6, "alignY": "stretch", "children": [
                  { "type": "column", "width": 260, "gap": 6, "alignX": "stretch", "children": [
                    { "type": "tabs", "grow": 1, "panels": ["hierarchy"] },
                    { "type": "tabs", "grow": 1, "panels": ["assets"] }
                  ]},
                  { "type": "column", "grow": 1, "gap": 6, "alignX": "stretch", "children": [
                    { "type": "tabs", "grow": 1, "panels": ["viewport"] },
                    { "type": "tabs", "height": 200, "panels": ["console", "output"] }
                  ]},
                  { "type": "tabs", "width": 320, "panels": ["inspector"] }
                ]},
                { "type": "panel", "panel": "status", "height": 24 }
              ]}
            }
          }
        }
        """;

    /**
     * Reads it. A new description each call, with its own report.
     *
     * @return the factory layout
     * @throws IllegalStateException if it cannot be read at all — a bug, never the user's file
     */
    public static AegisLayoutFile read() {
        try {
            return AegisLayoutFile.parse(new StringReader(TEXT), SOURCE);
        } catch (AegisLayoutFile.Unusable e) {
            throw new IllegalStateException("The factory layout is broken: " + e.getMessage(), e);
        }
    }
}
