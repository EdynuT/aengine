package com.aengine.aegis;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Panels registered by code, and screens built from a layout file — step 3f part 4c.
 *
 * <p>Code makes every panel and every widget in it, each wired to what it does, and registers
 * the panel here under its name. A layout file then says where each goes: which panels a
 * screen shows, in which rows and columns, at what size, and how a panel's widgets are ordered
 * and grouped. {@link #build} puts the two together and hands back the screen's root, which
 * is solved, dressed and drawn like any other.</p>
 *
 * <pre>{@code
 * AegisScreens screens = aegis.screens();
 * int inspector = screens.columnPanel("inspector");    // made by code, placed by the file
 * nameField = ae.textField(inspector, "nameField", 64);
 * ...
 * int root = screens.build("editor", layoutFile);
 * layoutFile.report();
 * }</pre>
 *
 * <h2>What the file decides, and what code does</h2>
 *
 * <p>What the file writes on a panel — {@code width}, {@code gap}, {@code alignX}... — replaces
 * what code set; what it does not write stays as code left it. A panel the file gives
 * {@code "children"} has its widgets reordered and grouped into the file's rows and columns;
 * a widget the file does not mention goes to the end of its panel, in code order, with a note,
 * so a control an update adds is never hidden by an older file. A panel's widgets are its
 * direct children, as code made them.</p>
 *
 * <p>Nothing here stops the editor from opening. A panel name code never registered leaves an
 * empty slot of the size the file asks, a widget name that is not one of the panel's is
 * skipped, each with a warning in the file's report. A registered panel the file does not
 * place is not shown.</p>
 *
 * <p>Built once, at start. Rows and columns the file adds are new layout nodes, and the layout
 * does not free nodes, so building the same screen again — a reload — is the reload step's to
 * solve, not this one's.</p>
 */
public final class AegisScreens {

    private final AegisLayout layout;

    /** Every registered panel, by name. */
    private final Map<String, Integer> panels = new HashMap<>();

    AegisScreens(AegisLayout layout) {
        this.layout = layout;
    }

    // -----------------------------------------------------------------------------------
    // Registering
    // -----------------------------------------------------------------------------------

    /**
     * A panel whose widgets sit top to bottom, registered under {@code name}. It hangs nowhere
     * until a screen is built: fill it with widgets, then {@link #build} puts it where the file
     * says.
     *
     * @param name the panel's name, which a layout file and a theme write — {@code "inspector"};
     *             unique among panels, since a theme path is {@code panel.widget} with no screen
     * @return its handle — a column of the layout, to add widgets to
     * @throws IllegalArgumentException for a name the layout refuses, or one already registered
     */
    public int columnPanel(String name) {
        return register(name, false);
    }

    /**
     * A panel whose widgets sit left to right, registered under {@code name}. See
     * {@link #columnPanel}.
     *
     * @param name the panel's name — {@code "toolbar"}
     * @return its handle — a row of the layout
     * @throws IllegalArgumentException for a name the layout refuses, or one already registered
     */
    public int rowPanel(String name) {
        return register(name, true);
    }

    private int register(String name, boolean row) {
        AegisLayout.checkNodeName(name);
        if (panels.containsKey(name)) {
            throw new IllegalArgumentException("A panel called \"" + name + "\" is already registered;"
                + " panel names are unique, since a theme reaches a panel by its name alone.");
        }
        int panel = row ? layout.row(AegisLayout.NONE) : layout.column(AegisLayout.NONE);
        layout.setNodeName(panel, name);
        panels.put(name, panel);
        return panel;
    }

    /**
     * A registered panel, by name.
     *
     * @param name the name it was registered under
     * @return its handle, or {@link AegisLayout#NONE} if none is registered under it
     */
    public int panel(String name) {
        Integer panel = panels.get(name);
        return panel != null ? panel : AegisLayout.NONE;
    }

    // -----------------------------------------------------------------------------------
    // Building
    // -----------------------------------------------------------------------------------

    /**
     * Builds a screen from the first file in line that describes it usably.
     *
     * <p>Files are tried in the order given — the user's, the installation's, the factory's —
     * and the first one with {@code screen} wins, so one screen may come from the user's file
     * and another from the installation's. What goes wrong while building is added to the
     * report of the file it came from; call each file's {@link AegisLayoutFile#report} once
     * every screen is built, to say it all in one block.</p>
     *
     * @param screen the screen's name — {@code "editor"}
     * @param inLine the files to take it from, first choice first; the last should always
     *               describe it, which the factory layout does
     * @return the screen's root: solve, dress and draw from it
     * @throws IllegalStateException if no file describes the screen, which only a factory
     *                               layout missing it can cause
     */
    public int build(String screen, AegisLayoutFile... inLine) {
        for (AegisLayoutFile file : inLine) {
            AegisLayoutFile.Node root = file.screen(screen);
            if (root != null) return buildNode(file, root, AegisLayout.NONE);
        }
        throw new IllegalStateException("No layout describes the screen \"" + screen + "\".");
    }

    /** One node of the file, made or found, under {@code parent}; its handle. */
    private int buildNode(AegisLayoutFile file, AegisLayoutFile.Node node, int parent) {
        return switch (node.type()) {
            case ROW, COLUMN -> {
                int made = node.type() == AegisLayoutFile.Type.ROW ? layout.row(parent) : layout.column(parent);
                applyProperties(node, made);
                for (AegisLayoutFile.Node child : node.children()) buildNode(file, child, made);
                yield made;
            }
            case PANEL -> placePanel(file, node, node.name(), parent);
            case TABS -> {
                // Until the tabbed pane exists (3g), a tabs node shows its first panel; the
                // reader has already noted it.
                List<String> tabs = node.tabs();
                yield placePanel(file, node, tabs.isEmpty() ? null : tabs.get(0), parent);
            }
            // The reader keeps widgets inside panels; arrange() is what reads them.
            case WIDGET -> throw new IllegalStateException("A widget outside a panel reached the builder.");
        };
    }

    /** A registered panel, attached to its slot; an empty slot when there is none to place. */
    private int placePanel(AegisLayoutFile file, AegisLayoutFile.Node node, String name, int parent) {
        Integer panel = name != null ? panels.get(name) : null;
        if (panel == null || layout.parent(panel) != AegisLayout.NONE) {
            if (name != null) {
                file.problem(node, panel == null
                    ? "panel \"" + name + "\" is not one the code registers; its slot is left empty"
                    : "panel \"" + name + "\" is already shown on another screen; this slot is left empty");
            }
            // A slot at the top of a screen is its root, which must be able to hold children.
            int empty = parent == AegisLayout.NONE ? layout.column(AegisLayout.NONE) : layout.box(parent);
            applySizes(node, empty);
            return empty;
        }
        layout.attach(panel, parent);
        applyProperties(node, panel);
        if (node.type() == AegisLayoutFile.Type.PANEL && node.hasChildren()) arrange(file, node, panel);
        return panel;
    }

    /**
     * Reorders and regroups a panel's widgets as the file says: its widgets come off the panel,
     * the file's rows and columns are made, each widget it names is attached where it stands,
     * and the ones it does not name go back at the end, in code order.
     */
    private void arrange(AegisLayoutFile file, AegisLayoutFile.Node panelNode, int panel) {
        List<Integer> codeOrder = new ArrayList<>();
        Map<String, Integer> byName = new HashMap<>();
        for (int child = layout.firstChild(panel); child != AegisLayout.NONE; child = layout.nextSibling(child)) {
            codeOrder.add(child);
            String name = layout.nodeName(child);
            if (name != null) byName.put(name, child);
        }
        for (int child : codeOrder) layout.detach(child);

        Set<Integer> placed = new HashSet<>();
        for (AegisLayoutFile.Node child : panelNode.children()) {
            arrangeNode(file, panelNode, child, panel, byName, placed);
        }

        for (int child : codeOrder) {
            if (placed.contains(child)) continue;
            layout.attach(child, panel);
            String name = layout.nodeName(child);
            if (name != null) {
                file.note(panelNode, "widget \"" + name + "\" of panel \"" + panelNode.name()
                    + "\" is not in the file's \"children\"; it is added at the end of the panel");
            }
        }
    }

    private void arrangeNode(AegisLayoutFile file, AegisLayoutFile.Node panelNode, AegisLayoutFile.Node node,
                             int parent, Map<String, Integer> byName, Set<Integer> placed) {
        switch (node.type()) {
            case ROW, COLUMN -> {
                int made = node.type() == AegisLayoutFile.Type.ROW ? layout.row(parent) : layout.column(parent);
                applyProperties(node, made);
                for (AegisLayoutFile.Node child : node.children()) {
                    arrangeNode(file, panelNode, child, made, byName, placed);
                }
            }
            case WIDGET -> {
                Integer widget = byName.get(node.name());
                if (widget == null) {
                    file.problem(node, "widget \"" + node.name() + "\" is not one of panel \""
                        + panelNode.name() + "\"'s widgets; it is ignored");
                    return;
                }
                layout.attach(widget, parent);
                applySizes(node, widget);
                placed.add(widget);
            }
            // The reader keeps panels and tabs out of a panel's children.
            case PANEL, TABS -> throw new IllegalStateException("A panel inside a panel reached the builder.");
        }
    }

    /** What the file writes on a row, column or panel; what it leaves out stays. */
    private void applyProperties(AegisLayoutFile.Node node, int target) {
        applySizes(node, target);
        if (!layout.isContainer(target)) return;
        if (!Float.isNaN(node.gap()))     layout.setGap(target, node.gap());
        if (!Float.isNaN(node.padding())) layout.setPadding(target, node.padding());
        if (node.alignX() != null)        layout.setAlignX(target, node.alignX());
        if (node.alignY() != null)        layout.setAlignY(target, node.alignY());
    }

    /** {@code width}, {@code height} and {@code grow}, which any node may hold. */
    private void applySizes(AegisLayoutFile.Node node, int target) {
        if (!Float.isNaN(node.width()))  layout.setWidth(target, node.width());
        if (!Float.isNaN(node.height())) layout.setHeight(target, node.height());
        if (!Float.isNaN(node.grow()))   layout.setGrow(target, node.grow());
    }
}
