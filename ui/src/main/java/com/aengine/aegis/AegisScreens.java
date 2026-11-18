package com.aengine.aegis;

import com.aengine.utils.Logger;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

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
 * fieldName = ae.textField(inspector, "fieldName", 64);
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
 * <p>A node the file marks {@code "hidden": true} is checked like any other but not built: it
 * is not drawn, takes no room, and neither the pointer nor Tab reaches it. A widget hidden
 * counts as placed, so it does not go back to the end of its panel.</p>
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

    /** What each panel's tab says, when a layout puts it in tabs; its name, until code gives it one. */
    private final Map<String, String> titles = new HashMap<>();

    /** Every panel's title, by panel name, so a locale is asked for each whether or not it is in a tab now. */
    Map<String, String> panelTitles() { return titles; }

    /** Panels a layout put in tabs. The ones not shown hang nowhere, yet are on a screen all the same. */
    private final Set<Integer> inTabs = new HashSet<>();

    private final AegisWidgets widgets;

    /** What each built screen was built from — the file's root — and each built node of it: what describe() writes. */
    private final Map<String, AegisLayoutFile.Node> builtFrom = new HashMap<>();
    private final Map<String, Map<AegisLayoutFile.Node, Integer>> builtNodes = new HashMap<>();
    private final Map<String, Integer> builtRoots = new HashMap<>();
    private final Map<String, AegisLayoutFile> builtFile = new HashMap<>();
    /** What building each screen changed inside the panels, so rebuilding it can take it back. */
    private final Map<String, Build> builtBy = new HashMap<>();

    /**
     * Each panel as code made it, before any layout file set its size, spacing or alignment —
     * kept at the first build, so building again starts every panel from code's word, not from
     * the arrangement it leaves. {padding, gap, alignX ordinal, alignY ordinal}.
     */
    private final Map<Integer, float[]> asCodeMadeIt = new HashMap<>();

    /** Each panel shown as a tab — step 4c: the tabbed pane holding it, and its tab there. */
    private final Map<String, int[]> tabOf = new HashMap<>();

    AegisScreens(AegisLayout layout, AegisWidgets widgets) {
        this.layout  = layout;
        this.widgets = widgets;
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
        widgets.markPanel(panel);   // drawn as its background, under its widgets
        panels.put(name, panel);
        return panel;
    }

    /**
     * What a panel's tab says when a layout file puts the panel in {@code "tabs"} — "Inspector"
     * for the panel {@code inspector}. Until it is given one, the tab shows the panel's name.
     *
     * @param name  a registered panel's name
     * @param title the text of its tab; kept
     * @throws IllegalArgumentException if no panel of that name is registered
     */
    public void setPanelTitle(String name, String title) {
        if (!panels.containsKey(name)) {
            throw new IllegalArgumentException("No panel called \"" + name + "\" is registered.");
        }
        titles.put(name, title);
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
            for (int panel : panels.values()) {
                asCodeMadeIt.computeIfAbsent(panel, p -> new float[] { layout.padding(p), layout.gap(p),
                    layout.alignX(p).ordinal(), layout.alignY(p).ordinal() });
            }
            Build build = new Build(file, widgets);
            int built = build.node(root, AegisLayout.NONE);
            this.widgets.markScreen(built);   // drawn as the window's background
            builtFrom.put(screen, root);
            builtFile.put(screen, file);
            builtNodes.put(screen, build.handles);
            builtRoots.put(screen, built);
            builtBy.put(screen, build);
            build.registerDocks(root, null);
            // The panels the user closed, once the whole screen stands: closing the last tab of
            // a dock hides the rows and columns left with nothing shown, which needs them all.
            build.closeClosed(root);
            return built;
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
            case PANEL -> {
                usable = checkPanel(file, node, node.name());
                // Whether a panel is a row or a column is code's, known only now.
                Integer panel = panels.get(node.name());
                if (panel != null) {
                    boolean row = layout.isRow(panel);
                    if (row ? node.alignX() == AegisLayout.Align.STRETCH : node.alignY() == AegisLayout.Align.STRETCH) {
                        file.error(node, AegisLayoutFile.stretchAlongFlow(node.label(), row));
                        usable = false;
                    }
                }
            }
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
        if (layout.parent(panel) != AegisLayout.NONE || inTabs.contains(panel)) {
            file.error(node, "panel \"" + name + "\" is already shown on another screen");
            return false;
        }
        return true;
    }

    // -----------------------------------------------------------------------------------
    // Writing down — step 4c
    // -----------------------------------------------------------------------------------

    /**
     * A built screen as a layout file would write it, with the arrangement the user has made
     * since: the sizes of the docks they resized. What {@code layout.json} holds for the
     * screen, under {@code "screens"} — {@code { "root": ... }}.
     *
     * @param screen a screen {@link #build} built
     * @return the screen's object, ready to write
     * @throws IllegalArgumentException if no screen of that name was built
     */
    public JsonObject describe(String screen) {
        AegisLayoutFile.Node root = builtFrom.get(screen);
        if (root == null) throw new IllegalArgumentException("No screen \"" + screen + "\" has been built.");
        JsonObject out = new JsonObject();
        out.add("root", describe(root, null, builtNodes.get(screen)));
        return out;
    }

    private JsonObject describe(AegisLayoutFile.Node node, AegisLayoutFile.Node parent, Map<AegisLayoutFile.Node, Integer> handles) {
        JsonObject o = new JsonObject();
        o.addProperty("type", node.type().name().toLowerCase());
        if (node.type() == AegisLayoutFile.Type.PANEL)  o.addProperty("panel", node.name());
        if (node.type() == AegisLayoutFile.Type.WIDGET) o.addProperty("widget", node.name());
        if (node.type() == AegisLayoutFile.Type.TABS) {
            JsonArray panels = new JsonArray();
            for (String tab : node.tabs()) panels.add(tab);
            o.add("panels", panels);
            JsonArray closed = new JsonArray();
            for (String tab : node.tabs()) if (!isPanelOpen(tab)) closed.add(tab);
            if (!closed.isEmpty()) o.add("closed", closed);
        }
        float width = node.width(), height = node.height();
        Integer handle = handles.get(node);
        if (node.resizable() && handle != null && parent != null) {
            // The size the user left it at, along its parent.
            if (parent.type() == AegisLayoutFile.Type.ROW && !Float.isNaN(width))     width  = layout.requestedWidth(handle);
            if (parent.type() == AegisLayoutFile.Type.COLUMN && !Float.isNaN(height)) height = layout.requestedHeight(handle);
        }
        number(o, "width", width);
        number(o, "height", height);
        number(o, "grow", node.grow());
        number(o, "padding", node.padding());
        number(o, "gap", node.gap());
        if (node.alignX() != null) o.addProperty("alignX", node.alignX().name().toLowerCase());
        if (node.alignY() != null) o.addProperty("alignY", node.alignY().name().toLowerCase());
        if (node.resizable()) o.addProperty("resizable", true);
        if (node.hidden())    o.addProperty("hidden", true);
        if (!node.children().isEmpty() || node.hasChildren()) {
            JsonArray children = new JsonArray();
            for (AegisLayoutFile.Node child : node.children()) children.add(describe(child, node, handles));
            o.add("children", children);
        }
        return o;
    }

    // -----------------------------------------------------------------------------------
    // Closing panels, and building again — step 4c
    // -----------------------------------------------------------------------------------

    /**
     * Whether the user can close a panel: it is shown as a tab, in a layout's {@code "tabs"}.
     * The others — the viewport, the menu bar — stand where the layout puts them for good.
     *
     * @param name a registered panel's name
     * @return {@code true} for a panel shown as a tab
     */
    public boolean isPanelClosable(String name) { return tabOf.containsKey(name); }

    /**
     * The tabbed pane a layout put a panel in — step 4f, to give its bar a context menu.
     *
     * @param name a registered panel's name
     * @return the pane, or {@link AegisLayout#NONE} for a panel not shown as a tab
     */
    public int paneOf(String name) {
        int[] at = tabOf.get(name);
        return at == null ? AegisLayout.NONE : at[0];
    }

    /**
     * The panel whose tab a layout's tabbed pane is showing.
     *
     * @param pane a pane {@link #paneOf} gave, or its bar
     * @return the panel's name, or {@code null} for a pane no layout built
     */
    public String panelShownIn(int pane) {
        for (Map.Entry<String, int[]> e : tabOf.entrySet()) {
            int[] at = e.getValue();
            boolean thisPane = at[0] == pane || layout.firstChild(at[0]) == pane;   // the pane, or its bar
            if (thisPane && widgets.selectedTab(at[0]) == at[1]) return e.getKey();
        }
        return null;
    }

    /**
     * Whether a panel is open — on its screen, or closed by the user.
     *
     * @param name a registered panel's name
     * @return {@code false} once {@link #setPanelOpen} closed it; {@code true} for a panel that
     *         cannot be closed
     */
    public boolean isPanelOpen(String name) {
        int[] at = tabOf.get(name);
        return at == null || widgets.isTabOpen(at[0], at[1]);
    }

    /**
     * Closes a panel shown as a tab, or opens it again — a View menu's item. Closing the last
     * open tab of a dock hides the dock, and the docks beside it take the room; opening a
     * panel shows it, bringing its dock back. The arrangement then counts as changed, to be
     * written down.
     *
     * @param name a registered panel's name; one that is not {@link #isPanelClosable} is left alone
     * @param open {@code false} to close it
     */
    public void setPanelOpen(String name, boolean open) {
        int[] at = tabOf.get(name);
        if (at == null || widgets.isTabOpen(at[0], at[1]) == open) return;
        widgets.setTabOpen(at[0], at[1], open);
        widgets.layoutChanged();
    }

    /**
     * Builds a screen again from the files in line, in place of the one built before — View
     * &gt; Undo my changes to this layout, or another layout chosen. The panels come off the old
     * arrangement and go into the new; the old rows, columns and tabbed panes are dropped.
     * What the old file did inside the panels is taken back first — widgets it moved to another
     * panel or hid, sizes it gave them, rows it grouped them in — so a file that leaves a panel
     * as code made it finds it so. Apply the theme and the language again afterwards: the new
     * tabbed panes have neither yet.
     *
     * @param screen a screen built before
     * @param inLine the files to take it from, as for {@link #build}
     * @return the screen's new root
     * @throws IllegalArgumentException if the screen was never built
     */
    public int rebuild(String screen, AegisLayoutFile... inLine) {
        Integer old = builtRoots.get(screen);
        if (old == null) throw new IllegalArgumentException("No screen \"" + screen + "\" has been built.");
        for (int panel : panels.values()) {
            if (isUnder(panel, old) || inTabs.contains(panel)) {
                layout.detach(panel);
                layout.setHidden(panel, false);
                inTabs.remove(panel);
                // Back to what code gave it: no size, no grow — a tab made it grow — and its own spacing.
                layout.setSize(panel, 0.0f, 0.0f);
                layout.setGrow(panel, 0.0f);
                float[] code = asCodeMadeIt.get(panel);
                if (code != null) {
                    layout.setPadding(panel, code[0]);
                    layout.setGap(panel, code[1]);
                    layout.setAlignX(panel, AegisLayout.Align.values()[(int) code[2]]);
                    layout.setAlignY(panel, AegisLayout.Align.values()[(int) code[3]]);
                }
            }
        }
        tabOf.clear();
        Build last = builtBy.remove(screen);
        if (last != null) last.takeBack();
        widgets.discard(old);
        if (last != null) last.dropMade();
        widgets.clearDocks();
        builtFrom.remove(screen);
        builtNodes.remove(screen);
        builtRoots.remove(screen);
        builtFile.remove(screen);
        return build(screen, inLine);
    }

    /**
     * The file a screen was built from — the first in line that described it without errors —
     * so the editor can say which layout is in use, and whose.
     *
     * @param screen a screen {@link #build} built
     * @return the file, or {@code null} for a screen not built
     */
    public AegisLayoutFile builtFrom(String screen) { return builtFile.get(screen); }

    private boolean isUnder(int node, int above) {
        for (int up = node; up != AegisLayout.NONE; up = layout.parent(up)) if (up == above) return true;
        return false;
    }

    /** A size, written whole when it is whole — 260, not 260.0; left out when the file had none. */
    private static void number(JsonObject o, String key, float value) {
        if (Float.isNaN(value)) return;
        if (value == Math.rint(value)) o.addProperty(key, (int) value);
        else o.addProperty(key, value);
    }

    /** One screen being built: the file it comes from, and what has been placed so far. */
    private final class Build {
        private final AegisLayoutFile file;
        private final Map<String, Integer> widgets;

        /** Every node of the file made or found, with its handle. */
        final Map<AegisLayoutFile.Node, Integer> handles = new HashMap<>();

        /**
         * Hands the widgets every node the file makes resizable, with the side its handle is on
         * — towards the nearest growing sibling, after it if there is one. A resizable node must
         * hold a size along its parent and stand beside a sibling that grows; one that does not
         * is a warning, and stays fixed.
         */
        void registerDocks(AegisLayoutFile.Node node, AegisLayoutFile.Node parent) {
            Integer handle = handles.get(node);
            if (node.resizable() && handle != null && !node.hidden()) {
                if (parent == null || (parent.type() != AegisLayoutFile.Type.ROW && parent.type() != AegisLayoutFile.Type.COLUMN)) {
                    file.problem(node, node.label() + " is resizable, but only a child of a row or a column has an edge to drag; it stays as it is");
                } else {
                    boolean across = parent.type() == AegisLayoutFile.Type.ROW;
                    float size = across ? node.width() : node.height();
                    int at = parent.children().indexOf(node);
                    int after = -1, before = -1;
                    for (int k = at + 1; k < parent.children().size() && after < 0; k++) if (grows(parent.children().get(k))) after = k;
                    for (int k = at - 1; k >= 0 && before < 0; k--) if (grows(parent.children().get(k))) before = k;
                    if (Float.isNaN(size)) {
                        file.problem(node, node.label() + " is resizable, but has no " + (across ? "width" : "height")
                            + " for the user to change; it stays as it is");
                    } else if (after < 0 && before < 0) {
                        file.problem(node, node.label() + " is resizable, but nothing beside it grows to give it room; it stays as it is");
                    } else {
                        AegisScreens.this.widgets.addDock(handle, after >= 0 && (before < 0 || after - at <= at - before));
                    }
                }
            }
            for (AegisLayoutFile.Node child : node.children()) registerDocks(child, node);
        }

        /** Closes the tabs the file says the user closed. */
        void closeClosed(AegisLayoutFile.Node node) {
            for (String name : node.closed()) {
                int[] at = tabOf.get(name);
                if (at != null) AegisScreens.this.widgets.setTabOpen(at[0], at[1], false);
            }
            for (AegisLayoutFile.Node child : node.children()) closeClosed(child);
        }

        private boolean grows(AegisLayoutFile.Node node) {
            return !node.hidden() && !Float.isNaN(node.grow()) && node.grow() > 0.0f;
        }

        /** Widgets the file placed, so the ones it did not can go back where code made them. */
        private final Set<Integer> placed = new HashSet<>();

        /**
         * For {@link #takeBack}: each container whose children the file changed, with its
         * children before; each widget it sized, with its size before (width, height, grow, and
         * whether each axis was fitted to content); the rows and columns it made.
         */
        private final Map<Integer, List<Integer>> childrenBefore = new LinkedHashMap<>();
        private final Map<Integer, float[]> sizeBefore = new HashMap<>();
        private final List<Integer> made = new ArrayList<>();

        /** Notes a container's children before the file first changes them. */
        private void keep(int container) {
            if (container == AegisLayout.NONE || childrenBefore.containsKey(container)) return;
            List<Integer> children = new ArrayList<>();
            for (int c = layout.firstChild(container); c != AegisLayout.NONE; c = layout.nextSibling(c)) children.add(c);
            childrenBefore.put(container, children);
        }

        /**
         * Takes back what the file did inside the panels: every container it changed gets its
         * children back, in their order, and every widget it sized its size — on an axis that
         * was fitted to content, the size it fits now, since its text may have changed.
         */
        void takeBack() {
            for (Map.Entry<Integer, List<Integer>> e : childrenBefore.entrySet()) {
                int container = e.getKey();
                if (!layout.isInUse(container)) continue;
                for (int c = layout.firstChild(container); c != AegisLayout.NONE; ) {
                    int next = layout.nextSibling(c);
                    layout.detach(c);
                    c = next;
                }
            }
            for (Map.Entry<Integer, List<Integer>> e : childrenBefore.entrySet()) {
                if (!layout.isInUse(e.getKey())) continue;
                for (int child : e.getValue()) if (layout.isInUse(child)) layout.attach(child, e.getKey());
            }
            for (Map.Entry<Integer, float[]> e : sizeBefore.entrySet()) {
                int widget = e.getKey();
                if (!layout.isInUse(widget)) continue;
                float[] was = e.getValue();
                layout.setSize(widget, was[3] != 0 ? AegisScreens.this.widgets.fittedWidth(widget)  : was[0],
                                       was[4] != 0 ? AegisScreens.this.widgets.fittedHeight(widget) : was[1]);
                layout.setGrow(widget, was[2]);
            }
        }

        /** Drops the rows and columns the file made that nothing holds any more. */
        void dropMade() {
            for (int node : made) {
                if (layout.isInUse(node) && layout.parent(node) == AegisLayout.NONE) AegisScreens.this.widgets.discard(node);
            }
        }

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
            if (node.hidden()) {
                hide(node);
                return AegisLayout.NONE;
            }
            int made = placeShown(node, parent);
            if (made != AegisLayout.NONE) handles.put(node, made);
            return made;
        }

        private int placeShown(AegisLayoutFile.Node node, int parent) {
            return switch (node.type()) {
                case ROW, COLUMN -> {
                    int made = node.type() == AegisLayoutFile.Type.ROW ? layout.row(parent) : layout.column(parent);
                    this.made.add(made);
                    applyProperties(node, made);
                    for (AegisLayoutFile.Node child : node.children()) place(child, made);
                    yield made;
                }
                case PANEL -> {
                    int panel = panels.get(node.name());
                    layout.attach(panel, parent);
                    applyProperties(node, panel);
                    if (node.hasChildren()) arrange(node, panel);
                    yield panel;
                }
                // A tabbed pane with a tab per panel, in the order listed, the first one shown.
                // The sizes the file writes are the pane's; each panel fills it in its turn.
                case TABS -> {
                    int pane = AegisScreens.this.widgets.tabbedPane(parent);
                    for (String name : node.tabs()) {
                        int panel = panels.get(name);
                        int index = AegisScreens.this.widgets.addPanelTab(pane, name, titles.getOrDefault(name, name), panel);
                        inTabs.add(panel);
                        tabOf.put(name, new int[] { pane, index });
                    }
                    applySizes(node, pane);
                    yield pane;
                }
                case WIDGET -> {
                    Integer widget = widgets.get(node.name());
                    if (widget == null) yield AegisLayout.NONE;   // warned about in check()
                    keep(layout.parent(widget));
                    sizeBefore.computeIfAbsent(widget, w -> new float[] {
                        layout.requestedWidth(w), layout.requestedHeight(w), layout.grow(w),
                        layout.requestedWidth(w)  == AegisScreens.this.widgets.fittedWidth(w)  ? 1 : 0,
                        layout.requestedHeight(w) == AegisScreens.this.widgets.fittedHeight(w) ? 1 : 0 });
                    layout.attach(widget, parent);
                    applySizes(node, widget);
                    placed.add(widget);
                    yield widget;
                }
            };
        }

        /**
         * A node the file hides: nothing of it is built. A hidden panel stays where code made
         * it, hanging nowhere; every widget named under a hidden node comes out of the tree
         * and counts as placed, so it does not go back to the end of its panel. Out of the
         * tree, it is not drawn, takes no room, and neither the pointer nor Tab reaches it;
         * code holding its handle notices nothing, except that it is never activated.
         */
        private void hide(AegisLayoutFile.Node node) {
            if (node.type() == AegisLayoutFile.Type.WIDGET) {
                Integer widget = widgets.get(node.name());
                if (widget == null) return;   // warned about in check()
                keep(layout.parent(widget));
                layout.detach(widget);
                placed.add(widget);
                return;
            }
            for (AegisLayoutFile.Node child : node.children()) hide(child);
        }

        /**
         * A panel the file gives {@code "children"}: the widgets still in it come off, and the
         * file's rows, columns and widgets — its own or moved in from another panel — go in.
         * The ones the file did not place anywhere go back in {@link #putBackUnplaced}.
         */
        private void arrange(AegisLayoutFile.Node panelNode, int panel) {
            keep(panel);
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
