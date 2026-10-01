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
     * The editor screen: the toolbar across the top, then hierarchy, viewport and inspector
     * side by side, the viewport taking the width the other two leave. Sizes and spacing
     * between panels are here; spacing inside a panel is its code's.
     */
    private static final String TEXT = """
        {
          "name":        "Factory",
          "description": "Toolbar on top; hierarchy, viewport and inspector below.",
          "format":      1,

          "screens": {
            "editor": {
              "root": { "type": "column", "padding": 8, "gap": 8, "alignX": "stretch", "children": [
                { "type": "panel", "panel": "toolbar", "height": 84 },
                { "type": "row", "grow": 1, "gap": 8, "alignY": "stretch", "children": [
                  { "type": "panel", "panel": "hierarchy", "width": 240 },
                  { "type": "panel", "panel": "viewport",  "grow": 1 },
                  { "type": "panel", "panel": "inspector", "width": 300 }
                ]}
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
