package com.aengine.aegis;

import com.aengine.utils.Logger;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Panels registered by code, and screens built from a layout file — step 3f part 4c.
 *
 * <p>Code makes every panel and every widget in it, each wired to what it does, and registers
 * the panel here under its name. A layout file then says where each goes: which panels a
 * screen shows, in which rows and columns, at what size, and where each widget goes — in which
 * panel, in what order, grouped how. {@link #build} puts the two together and hands back the
 * screen's root, which is solved, dressed and drawn like any other.</p>
 *
 * <pre>{@code
 * AegisScreens screens = aegis.screens();
 * int inspector = screens.columnPanel("inspector");    // made by code, placed by the file
 * nameField = ae.textField(inspector, "nameField", 64);
 * ...
 * int root = screens.build("editor", layoutFiles);
 * for (AegisLayoutFile f : layoutFiles) f.report();
 * }</pre>
 *
 * <h2>What the file decides, and what code does</h2>
 *
 * <p>What the file writes on a panel — {@code width}, {@code gap}, {@code alignX}... — replaces
 * what code set; what it does not write stays as code left it. A panel the file gives
 * {@code "children"} has its widgets reordered and grouped into the file's rows and columns.
 * A widget may be placed in <em>any</em> panel's {@code "children"}, not only the one code made
 * it in: it is found by name, moved, and keeps doing what it did — the handle code holds does
 * not change. A widget the file places nowhere stays in the panel code made it in, at the end,
 * with a warning, so a control an update adds is never hidden by an older file. A panel's
 * widgets are its direct children as code made them, and their names are unique across every
 * panel, since the file names a widget without its panel.</p>
 *
 * <p>Before anything is built, the screen is checked against what code registered: a panel
 * name no code registers is an error, and the screen is then built from the next file in line
 * instead, leaving nothing of the failed attempt behind. A widget name no panel has is a
 * warning, and is skipped. A registered panel the file does not place is not shown.</p>
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
     * Builds a screen from the first file in line that describes it without errors.
     *
     * <p>Files are tried in the order given — the user's, the installation's, the factory's.
     * A file whose description of {@code screen} has an error, found when it was read or here
     * — a panel code does not register — is passed over for the next, so one screen may come
     * from the user's file and another from the installation's. What is found is added to the
     * report of the file it came from; call each file's {@link AegisLayoutFile#report} once
     * every screen is built, to say it all in one block.</p>
     *
     * @param screen the screen's name — {@code "editor"}
     * @param inLine the files to take it from, first choice first; the last should always
     *               describe it, which the factory layout does
     * @return the screen's root: solve, dress and draw from it
     * @throws IllegalStateException if no file describes the screen without errors, which only
     *                               a broken factory layout can cause, or if two widgets of
     *                               different panels share a name — both bugs in code
     */
    public int build(String screen, AegisLayoutFile... inLine) {
        Map<String, Integer> widgets = widgetsByName();
        for (int i = 0; i < inLine.length; i++) {
            AegisLayoutFile file = inLine[i];
            AegisLayoutFile.Node root = file.screen(screen);
            if (root == null) {
                // Set aside while reading: still checked against code, so its report holds
                // everything to fix at once, then passed over.
                AegisLayoutFile.Node aside = file.setAsideScreen(screen);
                if (aside != null) {
                    check(file, aside, widgets);
                    sayNext(screen, file, inLine, i);
                }
                continue;
            }
            if (!check(file, root, widgets)) {
                file.setAsideWhileBuilding(screen);
                sayNext(screen, file, inLine, i);
                continue;
            }
            Logger.info(Logger.System.UI, "Screen \"%s\" from %s.", screen, file.source());
            return new Build(file, widgets).node(root, AegisLayout.NONE);
        }
        throw new IllegalStateException("No layout describes the screen \"" + screen + "\" without errors.");
    }

    /**
     * Says, as a screen is passed over for its errors, which layout comes next: the next file
     * in line that describes the screen — "trying to apply" it, since it may have errors of its
     * own — or, when that is the last in line, the factory layout, "applying" it.
     */
    private static void sayNext(String screen, AegisLayoutFile failed, AegisLayoutFile[] inLine, int at) {
        for (int next = at + 1; next < inLine.length; next++) {
            if (inLine[next].screen(screen) == null && inLine[next].setAsideScreen(screen) == null) continue;
            boolean last = next == inLine.length - 1;
            Logger.warn(Logger.System.UI, "Screen \"%s\" in %s has errors, listed in its report; %s %s.",
                screen, failed.source(), last ? "applying" : "trying to apply", inLine[next].source());
            return;
        }
        Logger.warn(Logger.System.UI, "Screen \"%s\" in %s has errors, listed in its report; no other layout"
            + " describes it.", screen, failed.source());
    }

    /**
     * Every named widget of every registered panel — its direct children — by name. Names are
     * unique across panels, since a layout file names a widget without its panel.
     */
    private Map<String, Integer> widgetsByName() {
        Map<String, Integer> widgets = new HashMap<>();
        Map<String, String>  panelOf = new HashMap<>();
        for (Map.Entry<String, Integer> panel : panels.entrySet()) {
            for (int child = layout.firstChild(panel.getValue()); child != AegisLayout.NONE;
                 child = layout.nextSibling(child)) {
                String name = layout.nodeName(child);
                if (name == null) continue;
                String other = panelOf.put(name, panel.getKey());
                if (other != null) {
                    throw new IllegalStateException("Panels \"" + other + "\" and \"" + panel.getKey()
                        + "\" both have a widget called \"" + name + "\"; widget names are unique across"
                        + " panels, since a layout file names a widget without its panel.");
                }
                widgets.put(name, child);
            }
        }
        return widgets;
    }

    /**
     * Checks a screen against what code registered, before anything is built: every panel it
     * names must be registered and not shown on another screen already. A widget no panel has
     * is only a warning. Returns whether the screen can be built.
     */
    private boolean check(AegisLayoutFile file, AegisLayoutFile.Node node, Map<String, Integer> widgets) {
        boolean usable = true;
        switch (node.type()) {
            case PANEL -> usable = checkPanel(file, node, node.name());
            case TABS  -> {
                if (node.tabs().isEmpty()) {
                    file.error(node, "tabs lists no panel; it needs at least one to show");
                    usable = false;
                }
                for (String tab : node.tabs()) usable &= checkPanel(file, node, tab);
            }
            case WIDGET -> {
                if (!widgets.containsKey(node.name())) {
                    file.problem(node, "widget \"" + node.name() + "\" is not a widget of any panel the code"
                        + " registers; it is ignored");
                }
            }
            case ROW, COLUMN -> { }
        }
        for (AegisLayoutFile.Node child : node.children()) usable &= check(file, child, widgets);
        return usable;
    }

    private boolean checkPanel(AegisLayoutFile file, AegisLayoutFile.Node node, String name) {
        Integer panel = panels.get(name);
        if (panel == null) {
            file.error(node, "panel \"" + name + "\" is not one the code registers");
            return false;
        }
        if (layout.parent(panel) != AegisLayout.NONE) {
            file.error(node, "panel \"" + name + "\" is already shown on another screen");
            return false;
        }
        return true;
    }

    /** One screen being built: the file it comes from, and what has been placed so far. */
    private final class Build {
        private final AegisLayoutFile file;
        private final Map<String, Integer> widgets;

        /** Widgets the file placed, so the ones it did not can go back where code made them. */
        private final Set<Integer> placed = new HashSet<>();

        /** Each rearranged panel's children as code made them, in code order. */
        private final Map<Integer, List<Integer>> codeOrder = new LinkedHashMap<>();
        private final Map<Integer, AegisLayoutFile.Node> panelNodes = new HashMap<>();

        Build(AegisLayoutFile file, Map<String, Integer> widgets) {
            this.file    = file;
            this.widgets = widgets;
        }

        /** One node of the file, made or found, under {@code parent}; its handle. */
        int node(AegisLayoutFile.Node node, int parent) {
            int root = place(node, parent);
            if (parent == AegisLayout.NONE) putBackUnplaced();
            return root;
        }

        private int place(AegisLayoutFile.Node node, int parent) {
            return switch (node.type()) {
                case ROW, COLUMN -> {
                    int made = node.type() == AegisLayoutFile.Type.ROW ? layout.row(parent) : layout.column(parent);
                    applyProperties(node, made);
                    for (AegisLayoutFile.Node child : node.children()) place(child, made);
                    yield made;
                }
                // Until the tabbed pane exists (3g), a tabs node shows its first panel; the
                // reader has already noted it.
                case PANEL, TABS -> {
                    int panel = panels.get(node.type() == AegisLayoutFile.Type.PANEL ? node.name() : node.tabs().get(0));
                    layout.attach(panel, parent);
                    applyProperties(node, panel);
                    if (node.type() == AegisLayoutFile.Type.PANEL && node.hasChildren()) arrange(node, panel);
                    yield panel;
                }
                case WIDGET -> {
                    Integer widget = widgets.get(node.name());
                    if (widget == null) yield AegisLayout.NONE;   // warned about in check()
                    layout.attach(widget, parent);
                    applySizes(node, widget);
                    placed.add(widget);
                    yield widget;
                }
            };
        }

        /**
         * A panel the file gives {@code "children"}: the widgets still in it come off, and the
         * file's rows, columns and widgets — its own or moved in from another panel — go in.
         * The ones the file did not place anywhere go back in {@link #putBackUnplaced}.
         */
        private void arrange(AegisLayoutFile.Node panelNode, int panel) {
            List<Integer> own = new ArrayList<>();
            for (int child = layout.firstChild(panel); child != AegisLayout.NONE; child = layout.nextSibling(child)) {
                own.add(child);
            }
            for (int child : own) layout.detach(child);
            codeOrder.put(panel, own);
            panelNodes.put(panel, panelNode);

            for (AegisLayoutFile.Node child : panelNode.children()) place(child, panel);
        }

        /**
         * What a rearranged panel held and the file placed nowhere goes back to the end of that
         * panel, in code order — a widget an update added, say — with a warning for each named
         * one, since the file could have placed it.
         */
        private void putBackUnplaced() {
            for (Map.Entry<Integer, List<Integer>> entry : codeOrder.entrySet()) {
                int panel = entry.getKey();
                for (int child : entry.getValue()) {
                    if (placed.contains(child)) continue;
                    layout.attach(child, panel);
                    String name = layout.nodeName(child);
                    if (name != null) {
                        file.problem(panelNodes.get(panel), "widget \"" + name + "\" of panel \""
                            + layout.nodeName(panel) + "\" is not placed by the file; it is added at the end of"
                            + " the panel");
                    }
                }
            }
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
