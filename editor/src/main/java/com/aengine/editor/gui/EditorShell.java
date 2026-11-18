package com.aengine.editor.gui;

import com.aengine.aegis.Aegis;
import com.aengine.aegis.AegisLayout;
import com.aengine.aegis.AegisLayoutFile;
import com.aengine.aegis.AegisLocale;
import com.aengine.aegis.AegisScreens;
import com.aengine.aegis.AegisTheme;
import com.aengine.aegis.AegisTree;
import com.aengine.aegis.AegisWidgets;
import com.aengine.core.Input;
import com.aengine.core.Keys;
import com.aengine.core.Window;
import com.aengine.editor.UndoHistory;
import com.aengine.utils.Logger;

import java.nio.file.Path;
import java.util.List;

/**
 * The editor's interface, drawn by Aegis — Phase 4.
 *
 * <p>Registers the editor's panels, builds the {@code editor} screen from the layouts in line
 * — the user's, the installation's, then {@link FactoryLayout} — dresses it in a theme and
 * shows it in the user's language. Each frame, {@link #frame} hands Aegis the input, solves,
 * updates and draws, and keeps what the editor needs to know: what the menus chose, where
 * the viewport is, whether it has focus, where it was pressed.</p>
 *
 * <p>Panels are registered empty, with their title, and filled as each is designed; the
 * viewport is the first, a surface showing the scene. The arrangement is the factory
 * layout's: hierarchy over assets on the left, viewport over console and output in the
 * middle, inspector on the right, with the menu bar and toolbar above and the status bar
 * below.</p>
 *
 * <p>View &gt; Theme and View &gt; Layout list the user's and the installation's files and the
 * factory's; the one chosen applies at once, without restarting, and is kept in
 * {@code editor.json} for the next start. The docks the user resizes or closes are kept as
 * their arrangement of the layout in use, never over the layout's file.</p>
 */
public final class EditorShell {

    /** One quad per glyph and per shape: a full editor with text in every panel. */
    private static final int MAX_QUADS = 8192;

    private final Aegis aegis;
    private final Window window;

    /** This frame's pixels per interface unit, and the pointer in units. */
    private float pixelScale = 1.0f;
    private float pointerX, pointerY;
    private final AegisLayout layout;
    private final AegisTree tree;
    private final AegisWidgets ae;

    private int root;
    private final AegisScreens screens;
    private AegisLocale locale;

    /** The docks the View menu closes and opens, by panel name, with their menu items (4c). */
    private static final String[] DOCKS = { "hierarchy", "assets", "inspector", "console", "output" };
    private final int[] menuItemDocks = new int[DOCKS.length];
    private final int menuItemRestoreLayout, menuItemFactoryLook;

    /** View &gt; Theme and View &gt; Layout: the lists shown, their items, and the View menu's state last frame. */
    private final int menuView, menuTheme, menuLayout;
    private List<ShellFiles.Listed> themeList = List.of(), layoutList = List.of();
    private int[] themeItems = new int[0], layoutItems = new int[0];
    private boolean viewWasOpen;

    /** Edit (4g): the scene's history, its two items, what they were last shown for, and whether the scene plays. */
    private final UndoHistory history;
    private final int menuItemUndo, menuItemRedo;
    private int historyShown = -1;
    private boolean playing;

    /** The docks' tabs' context menu (4f), and the dock it was last opened for. */
    private final int menuDock, menuItemCloseDock;
    private String dockMenuFor;

    /** The layout chosen, the files the editor screen was built from for it, and a layout asked for this frame. */
    private ShellFiles.Choice layoutChoice;
    private AegisLayoutFile[] layoutsInLine;
    private ShellFiles.Choice layoutAsked;
    private String layoutAskedBy;
    private After layoutAskedAfter;

    /** What follows a theme or layout being applied: ask whether to keep it, keep it, or nothing — a revert. */
    private enum After { ASK, KEEP, REVERT }

    /**
     * The confirmation after a theme or layout is chosen: kept with Keep; with Revert, Esc or
     * ten seconds of nothing the previous ones come back — so one that leaves the interface
     * unusable undoes itself. editor.json is written only once kept.
     */
    private final int dialogConfirmLook, buttonKeepLook, buttonRevertLook, labelConfirmSeconds;
    private ShellFiles.Choice confirmTheme, confirmLayout;   // what to go back to; null while not asking
    private long confirmDeadline;
    private int confirmShown;
    private static final long CONFIRM_TIME = 10_000_000_000L;

    /** Ctrl+Shift+F12 or View &gt; Factory theme and layout, asked for this frame: the way out of an unusable interface. */
    private boolean factoryLookAsked;
    private static final String FACTORY_LOOK = "Ctrl+Shift+F12";

    /** When to write the arrangement down: a second after the user last changed it; 0 for not due. */
    private long layoutSaveDue;
    private static final long LAYOUT_SAVE_DELAY = 1_000_000_000L;

    // Step 3f-5: the theme on screen, the one chosen, and whether a reload has been asked
    // for, run at the start of the next frame; the watcher, while the development option is on.
    private AegisTheme theme;
    private ShellFiles.Choice themeChoice;
    private boolean themeReloadAsked;
    private ThemeWatcher themeWatcher;

    // Widgets the editor acts on.
    private final int menuItemSaveScene;
    private final int menuItemQuit;
    private final int surfaceScene;
    private final int toggleSelect, toggleMove, toggleRotate, toggleScale;
    private final int togglePlay, togglePause, buttonStop;
    private final int labelEditing, labelPlaying, labelPaused;
    private final int labelFpsValue, labelEntitiesValue, labelProject;

    /**
     * Which theme and which layout are in use, and whose — the user's, the installation's or
     * the factory's — shown on the status bar, so a copy of the user's hiding the installed
     * file is never a surprise. The names are code's text; the origins, three translatable
     * labels each, of which one is shown, as the scene's state is.
     */
    private final int labelThemeName, labelLayoutName;
    private final int[] labelThemeOrigin = new int[3], labelLayoutOrigin = new int[3];

    /** Reused every frame to write the status bar's numbers into: §9, nothing allocated. */
    private final StringBuilder statusText = new StringBuilder(16);

    // This frame's outcome.
    private boolean saveAsked;
    private boolean quitAsked;
    private boolean playAsked, stopAsked;
    private boolean escapeInViewport;
    private boolean viewportPressed;
    private float pressNdcX, pressNdcY;
    private float viewportWheel;
    private boolean rightWasDown;

    /**
     * Builds the interface. Call once, after the GL context exists.
     *
     * @param window  the editor's window, for the clipboard
     * @param history the scene's undo history, which Edit &gt; Undo and Redo walk
     */
    public EditorShell(Window window, UndoHistory history) {
        this.history = history;
        this.window  = window;
        aegis = new Aegis(MAX_QUADS);
        // 18px Latin-1 is 224 glyphs: 86 rows of the atlas. 128 leaves room for a larger size.
        aegis.loadFont("/fonts/DejaVuSans/DejaVuSans.ttf", 18.0f, 512, 128);
        // The icons, by the engine's names — a provisional set until the designer's (4b).
        aegis.loadIcons("/icons/icons.json", 18.0f);
        layout = aegis.layout();
        tree   = aegis.tree();
        ae     = aegis.widgets();
        screens = aegis.screens();
        ae.setClipboard(window);

        // ── Panels. Each is registered under its name, which the layout file and the theme
        //    write; what is set here is its inside — padding, gap, alignment. How big it is,
        //    and where, is the layout's.

        // Menu bar. Ctrl+S is bound in frame(), with the other editor keys.
        int menu = screens.rowPanel("menu");
        layout.setPadding(menu, 2.0f);
        layout.setAlignY(menu, AegisLayout.Align.STRETCH);
        int menuBarMain = ae.menuBar(menu, "menuBarMain");
        int menuFile = ae.menu(menuBarMain, "menuFile", "File");
        menuItemSaveScene = ae.menuItem(menuFile, "menuItemSaveScene", "Save scene", "Ctrl+S");
        ae.separator(menuFile, "separatorFile");
        menuItemQuit = ae.menuItem(menuFile, "menuItemQuit", "Quit");

        // View (4c): a check item per dock, ticked while it is open — Unity's Window menu —
        // and Undo my changes to this layout, which forgets the user's arrangement.
        // Edit (4g): Undo and Redo walk the scene's history. In a text field Ctrl+Z and Ctrl+Y
        // are the field's own, for what is typed; the menu's items are the scene's always.
        int menuEdit = ae.menu(menuBarMain, "menuEdit", "Edit");
        menuItemUndo = ae.menuItem(menuEdit, "menuItemUndo", "Undo", "Ctrl+Z");
        menuItemRedo = ae.menuItem(menuEdit, "menuItemRedo", "Redo", "Ctrl+Y");

        menuView = ae.menu(menuBarMain, "menuView", "View");
        menuItemDocks[0] = ae.menuCheckItem(menuView, "menuItemHierarchy", "Hierarchy");
        menuItemDocks[1] = ae.menuCheckItem(menuView, "menuItemAssets",    "Assets");
        menuItemDocks[2] = ae.menuCheckItem(menuView, "menuItemInspector", "Inspector");
        menuItemDocks[3] = ae.menuCheckItem(menuView, "menuItemConsole",   "Console");
        menuItemDocks[4] = ae.menuCheckItem(menuView, "menuItemOutput",    "Output");
        ae.separator(menuView, "separatorView");
        // The selector: the files are listed again each time View opens, so one just copied
        // into a themes or layouts directory is there without restarting.
        menuTheme  = ae.subMenu(menuView, "menuTheme", "Theme");
        menuLayout = ae.subMenu(menuView, "menuLayout", "Layout");
        // Forgets the user's resizing of the layout in use: the layout as its file has it.
        menuItemRestoreLayout = ae.menuItem(menuView, "menuItemRestoreLayout", "Undo my changes to this layout");
        // The way out of a theme or layout that leaves the interface unusable — every panel
        // hidden, or pushed off the window — as its key, Ctrl+Shift+F12, which needs no menu.
        menuItemFactoryLook = ae.menuItem(menuView, "menuItemFactoryLook", "Factory theme and layout", "Ctrl+Shift+F12");

        // The docks' tabs (4f): a secondary click on one, or Shift+F10 on its bar, offers to
        // close it, as the tabs of Unity's and Godot's docks do. Given to the bars once the
        // screen is built, since the layout makes them.
        menuDock = ae.contextMenu("menuDock");
        menuItemCloseDock = ae.menuItem(menuDock, "menuItemClose", "Close");

        // Toolbar (4b): the tools on the left, Play, Pause and Stop in the middle, as Unity's
        // and Godot's. Each is an icon alone, so each has a tooltip saying what it does.
        int toolbar = screens.rowPanel("toolbar");
        layout.setPadding(toolbar, 4.0f);
        layout.setGap(toolbar, 2.0f);
        layout.setAlignY(toolbar, AegisLayout.Align.CENTER);

        // The tools: toggle buttons in a group, one pressed at a time — Swing's ButtonGroup.
        toggleSelect = ae.toggleButton(toolbar, "toggleSelect", "");
        ae.setIcon(toggleSelect, "toolSelect");
        ae.setTooltip(toggleSelect, "Select");
        toggleMove = ae.toggleButton(toolbar, "toggleMove", "");
        ae.setIcon(toggleMove, "toolMove");
        ae.setTooltip(toggleMove, "Move");
        toggleRotate = ae.toggleButton(toolbar, "toggleRotate", "");
        ae.setIcon(toggleRotate, "toolRotate");
        ae.setTooltip(toggleRotate, "Rotate");
        toggleScale = ae.toggleButton(toolbar, "toggleScale", "");
        ae.setIcon(toggleScale, "toolScale");
        ae.setTooltip(toggleScale, "Scale");
        ae.groupToggles(toggleSelect, toggleMove, toggleRotate, toggleScale);
        ae.setChecked(toggleSelect, true);

        // Play and Pause stay pressed while the scene plays or is paused; Pause and Stop can
        // be used only while it plays. A box that grows on each side keeps them in the middle.
        // The spacers are named, as boxes are (§7, Conventions), so a layout file can place them.
        int spaceBefore = layout.box(toolbar);
        layout.setNodeName(spaceBefore, "boxSpaceBefore");
        layout.setGrow(spaceBefore, 1.0f);
        togglePlay = ae.toggleButton(toolbar, "togglePlay", "");
        ae.setIcon(togglePlay, "play");
        ae.setTooltip(togglePlay, "Play the scene (F5)");
        togglePause = ae.toggleButton(toolbar, "togglePause", "");
        ae.setIcon(togglePause, "pause");
        ae.setTooltip(togglePause, "Pause");
        buttonStop = ae.button(toolbar, "buttonStop", "");
        ae.setIcon(buttonStop, "stop");
        ae.setTooltip(buttonStop, "Stop and go back to the scene as it was (F5)");
        int spaceAfter = layout.box(toolbar);
        layout.setNodeName(spaceAfter, "boxSpaceAfter");
        layout.setGrow(spaceAfter, 1.0f);

        // The docks. Empty for now, each showing its title on its tab.
        emptyDock(screens, "hierarchy", "Hierarchy");
        emptyDock(screens, "assets",    "Assets");
        emptyDock(screens, "console",   "Console");
        emptyDock(screens, "output",    "Output");
        emptyDock(screens, "inspector", "Inspector");

        // Viewport: a surface showing the scene, filling the panel. A press on it focuses it,
        // and while it has focus the camera takes the keys.
        int viewport = screens.columnPanel("viewport");
        layout.setPadding(viewport, 1.0f);   // the panel's edge stays visible around the scene
        layout.setAlignX(viewport, AegisLayout.Align.STRETCH);
        surfaceScene = ae.surface(viewport, "surfaceScene");
        layout.setGrow(surfaceScene, 1.0f);
        screens.setPanelTitle("viewport", "Viewport");

        // Status bar (4d): what the scene is doing, the frame rate, how many entities, and the
        // project. The words are labels of their own, so a locale translates them; the numbers
        // are set every frame with setLabel, which copies into the label and allocates nothing.
        // Of the three state words one is shown at a time: the others are hidden.
        int status = screens.rowPanel("status");
        layout.setPadding(status, 2.0f);
        layout.setGap(status, 6.0f);
        layout.setAlignY(status, AegisLayout.Align.CENTER);
        labelEditing = ae.label(status, "labelEditing", "Editing");
        labelPlaying = ae.label(status, "labelPlaying", "Playing");
        labelPaused  = ae.label(status, "labelPaused",  "Paused");
        ae.separator(status, "separatorState");
        ae.label(status, "labelFps", "FPS");
        labelFpsValue = ae.label(status, "labelFpsValue", "0");
        ae.separator(status, "separatorFps");
        ae.label(status, "labelEntities", "Entities");
        labelEntitiesValue = ae.label(status, "labelEntitiesValue", "0");
        ae.setLabel(labelFpsValue, "0");        // numbers are code's text from the start:
        ae.setLabel(labelEntitiesValue, "0");   // nothing for a locale to translate
        int statusSpace = layout.box(status);
        layout.setNodeName(statusSpace, "boxStatusSpace");
        layout.setGrow(statusSpace, 1.0f);
        ae.label(status, "labelTheme", "Theme");
        labelThemeName = ae.label(status, "labelThemeName", "");
        labelThemeOrigin[0] = ae.label(status, "labelThemeYours",     "(yours)");
        labelThemeOrigin[1] = ae.label(status, "labelThemeInstalled", "(installed)");
        labelThemeOrigin[2] = ae.label(status, "labelThemeFactory",   "(factory)");
        ae.separator(status, "separatorTheme");
        ae.label(status, "labelLayout", "Layout");
        labelLayoutName = ae.label(status, "labelLayoutName", "");
        labelLayoutOrigin[0] = ae.label(status, "labelLayoutYours",     "(yours)");
        labelLayoutOrigin[1] = ae.label(status, "labelLayoutInstalled", "(installed)");
        labelLayoutOrigin[2] = ae.label(status, "labelLayoutFactory",   "(factory)");
        ae.separator(status, "separatorLayout");
        labelProject = ae.label(status, "labelProject", "");
        showPlayState(false, false);

        // Titles for the bars too, shown only when a layout puts them in tabs.
        screens.setPanelTitle("menu", "Menu");
        screens.setPanelTitle("toolbar", "Toolbar");
        screens.setPanelTitle("status", "Status bar");

        // The confirmation after a theme or layout is chosen. Revert comes first, so focus
        // lands on it: Enter pressed on an interface one cannot read goes back, not forward.
        dialogConfirmLook = ae.dialog("dialogConfirmLook", "Keep this theme and layout?");
        int confirmLine = layout.row(dialogConfirmLook);
        layout.setGap(confirmLine, 4.0f);
        layout.setAlignY(confirmLine, AegisLayout.Align.CENTER);
        ae.label(confirmLine, "labelConfirmLook", "If nothing is chosen, the previous ones come back in");
        labelConfirmSeconds = ae.label(confirmLine, "labelConfirmSeconds", "10");
        ae.setLabel(labelConfirmSeconds, "10");   // a number: code's text
        ae.label(confirmLine, "labelConfirmUnit", "s");
        int confirmButtons = layout.row(dialogConfirmLook);
        layout.setGap(confirmButtons, 8.0f);
        buttonRevertLook = ae.button(confirmButtons, "buttonRevertLook", "Revert");
        buttonKeepLook   = ae.button(confirmButtons, "buttonKeepLook", "Keep");

        // ── What was chosen last, from editor.json. A development run's -PlayoutFile and
        //    -PthemeFile name a file instead, for that run.
        ShellFiles.Choice[] chosen = ShellFiles.readChoices();
        String layoutProperty = System.getProperty("aengine.layout");
        layoutChoice = layoutProperty != null ? ShellFiles.defaultLayout(layoutProperty)
                     : chosen[1] != null      ? chosen[1]
                     : ShellFiles.defaultLayout("default");
        String themeProperty = System.getProperty("aengine.theme");
        themeChoice = themeProperty != null ? ShellFiles.themeNamed(themeProperty)
                    : chosen[0] != null     ? chosen[0]
                    : ShellFiles.Choice.FACTORY;

        // ── The screen, from the first layout in line that describes it usably: the user's
        //    arrangement of the layout chosen, the layout, the factory's.
        layoutsInLine = ShellFiles.loadLayouts(layoutChoice);
        root = screens.build("editor", layoutsInLine);
        for (AegisLayoutFile file : layoutsInLine) file.report();

        // ── The theme, applied once the tree is built, since a theme finds widgets by their paths.
        theme = ShellFiles.loadTheme(themeChoice);
        ae.applyTheme(theme, root);
        watchTheme();

        // ── The lists in View > Theme and View > Layout, made before the language is applied,
        //    so it finds their headings.
        listChoices();

        // ── The language: the system's, unless -Plocale=<tag> says otherwise.
        String tag = System.getProperty("aengine.locale", java.util.Locale.getDefault().toLanguageTag());
        locale = ShellFiles.loadLocale(tag);
        ae.applyLocale(locale);
        String check = System.getProperty("aengine.localeCheck");
        ShellFiles.reportLocale(locale, tag, check != null ? Path.of(check) : null);
        showDocksInMenu();
        giveDocksTheirMenu();
        showSources(true);
        reportThemeFallback();   // what editor.json chose may have gone since
        reportLayoutFallback();
    }

    /**
     * The theme author's development option, {@code -PthemeWatch}: saving the theme chosen
     * applies it. Off by default; started again for each theme chosen.
     */
    private void watchTheme() {
        if (themeWatcher != null) { themeWatcher.close(); themeWatcher = null; }
        if (themeChoice.file() != null && Boolean.getBoolean("aengine.themeWatch")) {
            themeWatcher = ThemeWatcher.start(themeChoice.file());
        }
    }

    /**
     * Lists the themes and the layouts in View &gt; Theme and View &gt; Layout, ticking the ones in
     * use: the user's under a heading, the installation's under another, the factory's last.
     * Made again only when the files changed.
     */
    private void listChoices() {
        List<ShellFiles.Listed> themes = ShellFiles.listThemes(), layouts = ShellFiles.listLayouts();
        boolean changed = false;
        if (!themes.equals(themeList)) {
            themeList = themes;
            themeItems = fillList(menuTheme, "Theme", themes);
            changed = true;
        }
        if (!layouts.equals(layoutList)) {
            layoutList = layouts;
            layoutItems = fillList(menuLayout, "Layout", layouts);
            changed = true;
        }
        if (changed) {   // the new items take the theme and the language the others have
            ae.applyTheme(theme, root);
            if (locale != null) ae.applyLocale(locale);
        }
        tickChoices();
    }

    /**
     * Fills a list's submenu: a heading over the user's, one over the installation's, the
     * factory's last, set apart by separators. The headings are always made, hidden over an
     * empty group, so a locale always finds the texts it translates. The items are named by
     * their place, and show the files' own names.
     */
    private int[] fillList(int menu, String what, List<ShellFiles.Listed> list) {
        ae.removeChildren(menu);
        int[] items = new int[list.size()];
        int i = 0;
        for (ShellFiles.Origin origin : ShellFiles.Origin.values()) {
            int first = i;
            while (i < list.size() && list.get(i).choice().origin() == origin) i++;
            boolean empty = i == first;
            if (origin != ShellFiles.Origin.FACTORY) {
                boolean yours = origin == ShellFiles.Origin.USER;
                int heading = ae.menuItem(menu, "menuItem" + what + "s" + (yours ? "Yours" : "Installed"),
                                          yours ? "Yours" : "Installed");
                ae.setEnabled(heading, false);
                layout.setHidden(heading, empty);
            }
            for (int k = first; k < i; k++) {
                items[k] = ae.menuCheckItem(menu, "menuItem" + what + (k + 1), "");
                ae.setLabel(items[k], list.get(k).name());   // a file's own name: no language changes it
            }
            if (origin != ShellFiles.Origin.FACTORY) {
                int separator = ae.separator(menu, "separator" + what + "s" + (origin == ShellFiles.Origin.USER ? "Yours" : "Installed"));
                layout.setHidden(separator, empty);
            }
        }
        return items;
    }

    /** What a list shows for a choice; its file's name when the list does not hold it. */
    private static String listedName(List<ShellFiles.Listed> list, ShellFiles.Choice choice) {
        for (ShellFiles.Listed listed : list) if (listed.choice().equals(choice)) return listed.name();
        return choice.file() != null ? choice.file() : "Factory";
    }

    /** Says in the log when the theme chosen could not be used and the factory theme took its place. */
    private void reportThemeFallback() {
        if (themeChoice.origin() != ShellFiles.Origin.FACTORY && ShellFiles.originOf(theme.source()) == ShellFiles.Origin.FACTORY) {
            Logger.warn(Logger.System.UI, "Theme \"%s\" (%s) could not be used, for the reason above; the factory theme is used instead.",
                listedName(themeList, themeChoice), themeChoice.origin().word());
        }
    }

    /** Says in the log when the layout chosen could not build the editor screen and the factory layout did. */
    private void reportLayoutFallback() {
        if (layoutChoice.origin() != ShellFiles.Origin.FACTORY
                && ShellFiles.originOf(screens.builtFrom("editor").source()) == ShellFiles.Origin.FACTORY) {
            Logger.warn(Logger.System.UI, "Layout \"%s\" (%s) could not build the editor screen, for the reason above;"
                + " the factory layout is used instead.", listedName(layoutList, layoutChoice), layoutChoice.origin().word());
        }
    }

    /** Ticks the theme and the layout in use, and only them. */
    private void tickChoices() {
        for (int i = 0; i < themeItems.length; i++)  ae.setChecked(themeItems[i],  themeList.get(i).choice().equals(themeChoice));
        for (int i = 0; i < layoutItems.length; i++) ae.setChecked(layoutItems[i], layoutList.get(i).choice().equals(layoutChoice));
    }

    /**
     * Applies a theme at once: View &gt; Theme asks whether to keep it, the way out keeps it, a
     * revert just puts it back.
     */
    private void chooseTheme(ShellFiles.Choice choice, String by, After after) {
        Logger.info(Logger.System.UI, "%s: theme \"%s\" changed to \"%s\" (%s).",
            by, theme.name(), listedName(themeList, choice), choice.origin().word());
        ShellFiles.Choice before = themeChoice;
        themeChoice = choice;
        theme = ShellFiles.loadTheme(choice);
        reportThemeFallback();
        ae.applyTheme(theme, root);
        watchTheme();
        settle(after, before, layoutChoice, choice.equals(before));
        tickChoices();
        showSources(true);
    }

    /**
     * Shows on the status bar the theme and the layout in use, and whose each is; with
     * {@code log}, says it in the log too, with where each file is.
     */
    private void showSources(boolean log) {
        ShellFiles.Origin themeOrigin = ShellFiles.originOf(theme.source());
        AegisLayoutFile layoutFile = screens.builtFrom("editor");
        // The user's arrangement of a layout is shown as the layout it arranges.
        if (ShellFiles.isArrangement(layoutFile.source()) && layoutsInLine.length > 1) layoutFile = layoutsInLine[1];
        ShellFiles.Origin layoutOrigin = ShellFiles.originOf(layoutFile.source());
        ae.setLabel(labelThemeName, theme.name());
        ae.setLabel(labelLayoutName, layoutFile.name());
        for (int i = 0; i < 3; i++) {
            layout.setHidden(labelThemeOrigin[i],  i != themeOrigin.ordinal());
            layout.setHidden(labelLayoutOrigin[i], i != layoutOrigin.ordinal());
        }
        if (log) {
            Logger.info(Logger.System.UI, "Interface: theme \"%s\" (%s, %s); layout \"%s\" (%s, %s).",
                theme.name(), themeOrigin.word(), theme.source(),
                layoutFile.name(), layoutOrigin.word(), layoutFile.source());
        }
    }

    /** Gives the bar of every tabbed pane holding a dock the docks' context menu. */
    private void giveDocksTheirMenu() {
        for (String dock : DOCKS) {
            int pane = screens.paneOf(dock);
            if (pane != AegisLayout.NONE) ae.setContextMenu(layout.firstChild(pane), menuDock);
        }
    }

    private static boolean isDock(String panel) {
        for (String dock : DOCKS) if (dock.equals(panel)) return true;
        return false;
    }

    /** Enables Undo and Redo while there is something to undo or redo, and the scene is not playing. */
    private void showHistory() {
        if (historyShown == history.changes()) return;
        historyShown = history.changes();
        ae.setEnabled(menuItemUndo, !playing && history.canUndo());
        ae.setEnabled(menuItemRedo, !playing && history.canRedo());
    }

    /** Ticks each dock's View item while it is open; one the layout does not put in tabs cannot be closed. */
    private void showDocksInMenu() {
        for (int i = 0; i < DOCKS.length; i++) {
            ae.setChecked(menuItemDocks[i], screens.isPanelOpen(DOCKS[i]));
            ae.setEnabled(menuItemDocks[i], screens.isPanelClosable(DOCKS[i]));
        }
    }

    /**
     * View &gt; Undo my changes to this layout: the user's arrangement of the layout in use is
     * forgotten, and the editor screen is built again from the layout as it is.
     */
    private void restoreLayout() {
        layoutSaveDue = 0;
        ShellFiles.forgetArrangement(layoutChoice, "editor");
        rebuildScreen();
        Logger.info(Logger.System.UI, "Layout restored.");
    }

    /**
     * Builds the editor screen from a layout at once: an arrangement not yet written is written
     * for the layout it belongs to first, and the layout chosen comes with the user's
     * arrangement of it, if they made one. View &gt; Layout asks whether to keep it, the way out
     * keeps it, a revert just puts it back.
     */
    private void chooseLayout(ShellFiles.Choice choice, String by, After after) {
        if (layoutSaveDue != 0) saveLayout();
        Logger.info(Logger.System.UI, "%s: layout \"%s\" changed to \"%s\" (%s).",
            by, listedName(layoutList, layoutChoice), listedName(layoutList, choice), choice.origin().word());
        ShellFiles.Choice before = layoutChoice;
        layoutChoice = choice;
        rebuildScreen();
        reportLayoutFallback();
        settle(after, themeChoice, before, choice.equals(before));
        tickChoices();
    }

    /**
     * What follows a change: asked about — unless nothing changed — with the theme and layout
     * to go back to; kept, written to editor.json; or, for a revert, nothing.
     */
    private void settle(After after, ShellFiles.Choice backTheme, ShellFiles.Choice backLayout, boolean same) {
        if (after == After.KEEP || (after == After.ASK && same)) {
            ShellFiles.saveChoices(themeChoice, layoutChoice);
        } else if (after == After.ASK) {
            confirmTheme  = backTheme;
            confirmLayout = backLayout;
            confirmDeadline = System.nanoTime() + CONFIRM_TIME;
            confirmShown = -1;
            ae.openDialog(dialogConfirmLook);
        }
    }

    /**
     * The confirmation, each frame while it is open: Keep writes the choice down; Revert, Esc
     * or the time running out bring the previous theme and layout back, the layout once this
     * frame's input is read. Otherwise the seconds left are shown.
     */
    private void runConfirmation() {
        if (confirmTheme == null) return;
        long left = confirmDeadline - System.nanoTime();
        if (ae.wasActivated(buttonKeepLook)) {
            Logger.info(Logger.System.UI, "Theme \"%s\" and layout \"%s\" kept.",
                listedName(themeList, themeChoice), listedName(layoutList, layoutChoice));
            ae.closeDialog(dialogConfirmLook);
            confirmTheme = confirmLayout = null;
            ShellFiles.saveChoices(themeChoice, layoutChoice);
            return;
        }
        boolean asked = ae.wasActivated(buttonRevertLook) || ae.wasCancelled(dialogConfirmLook);
        if (asked || left <= 0) {
            Logger.info(Logger.System.UI, asked ? "Reverting, as asked." : "Reverting: nothing was chosen in %d seconds.",
                CONFIRM_TIME / 1_000_000_000L);
            ae.closeDialog(dialogConfirmLook);
            ShellFiles.Choice backTheme = confirmTheme, backLayout = confirmLayout;
            confirmTheme = confirmLayout = null;
            if (!backTheme.equals(themeChoice)) chooseTheme(backTheme, "Revert", After.REVERT);
            if (!backLayout.equals(layoutChoice)) {
                layoutAsked = backLayout;
                layoutAskedBy = "Revert";
                layoutAskedAfter = After.REVERT;
            }
            return;
        }
        int seconds = (int) ((left + 999_999_999L) / 1_000_000_000L);
        if (seconds != confirmShown) {
            confirmShown = seconds;
            statusText.setLength(0);
            statusText.append(seconds);
            ae.setLabel(labelConfirmSeconds, statusText);
        }
    }

    /** Builds the editor screen again for the layout chosen, then dresses and translates it again. */
    private void rebuildScreen() {
        layoutsInLine = ShellFiles.loadLayouts(layoutChoice);
        root = screens.rebuild("editor", layoutsInLine);
        for (AegisLayoutFile file : layoutsInLine) file.report();
        ae.applyTheme(theme, root);
        ae.applyLocale(locale);
        showDocksInMenu();
        giveDocksTheirMenu();
        showSources(true);
    }

    /** A dock's panel with nothing in it yet but its title. */
    private void emptyDock(AegisScreens screens, String name, String title) {
        int panel = screens.columnPanel(name);
        layout.setPadding(panel, 8.0f);
        layout.setGap(panel, 6.0f);
        layout.setAlignX(panel, AegisLayout.Align.STRETCH);
        screens.setPanelTitle(name, title);
    }

    /**
     * One frame of the interface: input in, solve, update, draw.
     *
     * @param width            the framebuffer's width in pixels
     * @param height           the framebuffer's height in pixels
     * @param sceneTexture     the scene framebuffer's colour texture, for the viewport
     */
    public void frame(int width, int height, int sceneTexture) {
        saveAsked = false;
        quitAsked = false;
        playAsked = false;
        stopAsked = false;
        escapeInViewport = false;
        viewportPressed = false;
        viewportWheel = 0.0f;

        // A theme reload asked for last frame, by Ctrl+R or by saving the file, runs first, so
        // nothing is solved or drawn half in one theme and half in the other.
        boolean themeSaved = themeWatcher != null && themeWatcher.takeChange();
        if ((themeReloadAsked || themeSaved) && themeChoice.file() != null) {
            AegisTheme reloaded = ShellFiles.reloadTheme(themeChoice, theme);
            if (reloaded != theme) {
                theme = reloaded;
                showSources(false);
                ae.applyTheme(theme, root);
            }
        }
        themeReloadAsked = false;

        // The interface is laid out in units, the system's scale apart from the framebuffer's
        // pixels, so it is the size the system asks for on a screen at 150 % as at 100 %. The
        // pointer comes in window coordinates: framebuffer pixels per coordinate, over pixels
        // per unit, turns it into units on every platform.
        pixelScale = window.getContentScale();
        aegis.begin(width, height, pixelScale);
        float unitsW = aegis.width(), unitsH = aegis.height();
        layout.solve(root, 0.0f, 0.0f, unitsW, unitsH);

        float toUnits = window.getPixelRatio() / aegis.scale();
        float mouseX = (float) Input.getMouseX() * toUnits;
        float mouseY = (float) Input.getMouseY() * toUnits;
        pointerX = mouseX;
        pointerY = mouseY;
        tree.update(root, mouseX, mouseY, Input.isMouseButtonPressed(0));

        // The right button looks around with the camera, so a right press on the viewport
        // focuses it as a left press does.
        boolean rightDown = Input.isMouseButtonPressed(1);
        if (rightDown && !rightWasDown && tree.hovered() == surfaceScene) tree.focus(surfaceScene);
        rightWasDown = rightDown;
        ae.secondaryButton(rightDown);   // a click with it opens context menus

        boolean viewportHadFocus = tree.focused() == surfaceScene && !ae.isAnyOverlayOpen();
        for (int i = 0; i < Input.eventCount(); i++) {
            Input.EventType type = Input.eventType(i);
            if (type == Input.EventType.SCROLL) {
                // Over the viewport the wheel is the camera's; anywhere else it scrolls.
                if (tree.hovered() == surfaceScene) viewportWheel += Input.eventScrollY(i);
                else ae.scroll(Input.eventScrollY(i));
                continue;
            }
            switch (type) {
                case CHAR -> ae.character(Input.eventCode(i));
                case KEY_PRESS -> {
                    int key = Input.eventCode(i), mods = Input.eventMods(i);
                    boolean ctrl = Keys.isCtrlShortcut(mods);   // AltGr+R types ®, and must not reload the theme
                    // Editor keys, not handed to the widgets. Ctrl+R reads the theme again:
                    // a development key, until the settings page exists.
                    // The way out, read here before any widget, so it works whatever the theme
                    // and the layout have done to the interface.
                    if (ctrl && (mods & Keys.MOD_SHIFT) != 0 && key == Keys.F12) { factoryLookAsked = true; continue; }
                    if (ctrl && key == Keys.R) { themeReloadAsked = true; continue; }
                    if (ctrl && key == Keys.S) { saveAsked = true; continue; }
                    // Ctrl+Z, and Ctrl+Y or Ctrl+Shift+Z, undo and redo in the scene — unless
                    // something being typed into takes them for itself, or a menu is open.
                    boolean historyKey = ctrl && (key == Keys.Z || key == Keys.Y);
                    if (historyKey && !ae.isTyping() && !ae.isAnyOverlayOpen()) {
                        boolean redo = key == Keys.Y || (mods & Keys.MOD_SHIFT) != 0;
                        if (!playing) { if (redo) history.redo(); else history.undo(); }
                        continue;
                    }
                    if (key == Keys.ESCAPE && viewportHadFocus) escapeInViewport = true;
                    ae.key(key, mods, false);
                }
                case KEY_REPEAT -> ae.key(Input.eventCode(i), Input.eventMods(i), true);
                default -> { }   // releases and mouse buttons: the tree already has them
            }
        }

        ae.update(root);

        if (ae.wasActivated(menuItemSaveScene)) saveAsked = true;
        if (ae.wasActivated(menuItemQuit))      quitAsked = true;

        // View: a dock's item closes it or opens it again; the item has already flipped.
        for (int i = 0; i < DOCKS.length; i++) {
            if (ae.wasActivated(menuItemDocks[i])) screens.setPanelOpen(DOCKS[i], ae.isChecked(menuItemDocks[i]));
        }
        boolean restore = ae.wasActivated(menuItemRestoreLayout);

        // View: its lists are made again as it opens, so a file just added is there; a theme
        // chosen applies now, a layout once this frame's input is read, as Restore does.
        boolean viewOpen = ae.isMenuOpen(menuView);
        if (viewOpen && !viewWasOpen) listChoices();
        viewWasOpen = viewOpen;
        for (int i = 0; i < themeItems.length; i++) {
            if (ae.wasActivated(themeItems[i])) chooseTheme(themeList.get(i).choice(), "View > Theme", After.ASK);
        }
        for (int i = 0; i < layoutItems.length; i++) {
            if (ae.wasActivated(layoutItems[i])) {
                layoutAsked = layoutList.get(i).choice();
                layoutAskedBy = "View > Layout";
                layoutAskedAfter = After.ASK;
            }
        }
        runConfirmation();
        // The way out: the factory theme and layout, kept at once, with no question — the
        // interface it rescues may be one where no question can be read.
        if (ae.wasActivated(menuItemFactoryLook)) factoryLookAsked = true;
        if (factoryLookAsked) {
            factoryLookAsked = false;
            Logger.info(Logger.System.UI, "Back to the factory theme and layout, as asked.");
            if (confirmTheme != null) {
                ae.closeDialog(dialogConfirmLook);
                confirmTheme = confirmLayout = null;
            }
            // The theme only if it is not the factory's already; the layout always, which also
            // writes both down, once.
            if (!themeChoice.equals(ShellFiles.Choice.FACTORY)) chooseTheme(ShellFiles.Choice.FACTORY, FACTORY_LOOK, After.REVERT);
            layoutAsked = ShellFiles.Choice.FACTORY;
            layoutAskedBy = FACTORY_LOOK;
            layoutAskedAfter = After.KEEP;
        }
        if (ae.wasActivated(menuItemUndo)) history.undo();
        if (ae.wasActivated(menuItemRedo)) history.redo();
        showHistory();

        // A dock's tab: Close is offered for a dock the View menu can open again — not for the
        // viewport, which can share a pane with them.
        if (ae.wasContextMenuOpened(menuDock)) {
            dockMenuFor = screens.panelShownIn(ae.contextTarget());
            ae.setEnabled(menuItemCloseDock, dockMenuFor != null && isDock(dockMenuFor) && screens.isPanelClosable(dockMenuFor));
        }
        if (ae.wasActivated(menuItemCloseDock) && dockMenuFor != null) {
            screens.setPanelOpen(dockMenuFor, false);
            showDocksInMenu();
        }

        // The arrangement the user changed — a dock resized or closed — is written down a
        // second after the last change, so a drag is one write, not one per frame.
        if (ae.wasLayoutChanged()) layoutSaveDue = System.nanoTime() + LAYOUT_SAVE_DELAY;
        if (layoutSaveDue != 0 && System.nanoTime() >= layoutSaveDue) saveLayout();

        // Restored after this frame's input is read, before it is drawn: the new screen is
        // solved here so nothing is drawn from the old one.
        if (restore) {
            restoreLayout();
            layout.solve(root, 0.0f, 0.0f, unitsW, unitsH);
        } else if (layoutAsked != null) {
            ShellFiles.Choice asked = layoutAsked;
            layoutAsked = null;
            chooseLayout(asked, layoutAskedBy, layoutAskedAfter);
            layout.solve(root, 0.0f, 0.0f, unitsW, unitsH);
        }

        // Play pressed starts the scene; pressed again, it stops it, as Unity's does. Its
        // pressed look, and Pause's, are set back by showPlayState() once the editor acts.
        if (ae.wasChanged(togglePlay)) {
            if (ae.isChecked(togglePlay)) playAsked = true;
            else stopAsked = true;
        }
        if (ae.wasActivated(buttonStop)) stopAsked = true;

        // A press on the scene: where, in the viewport's normalised coordinates, Y up.
        if (tree.pointerWentDown() && tree.hovered() == surfaceScene) {
            viewportPressed = true;
            pressNdcX =  ((mouseX - layout.x(surfaceScene)) / layout.width(surfaceScene)) * 2.0f - 1.0f;
            pressNdcY = -((mouseY - layout.y(surfaceScene)) / layout.height(surfaceScene)) * 2.0f + 1.0f;
        }

        ae.setSurfaceTexture(surfaceScene, sceneTexture, true);   // a framebuffer: bottom row first
        ae.draw(root);
        aegis.end();
    }

    // -----------------------------------------------------------------------------------
    // What the frame found
    // -----------------------------------------------------------------------------------

    /** @return whether Save scene was asked for this frame, from the menu or Ctrl+S */
    public boolean saveAsked() { return saveAsked; }

    /** @return whether Play was pressed this frame, to start the scene */
    public boolean playAsked() { return playAsked; }

    /** @return whether Stop was asked for this frame — the Stop button, or Play pressed while playing */
    public boolean stopAsked() { return stopAsked; }

    /** @return whether Pause is pressed: the scene, if it plays, is to stay frozen */
    public boolean pauseAsked() { return ae.isChecked(togglePause); }

    /**
     * Shows the scene's state on the toolbar: Play pressed while it plays, Pause pressed
     * while it is paused, and Pause and Stop usable only while it plays.
     *
     * @param playing whether the scene plays
     * @param paused  whether it is paused
     */
    public void showPlayState(boolean playing, boolean paused) {
        this.playing = playing;
        historyShown = -1;   // Undo and Redo wait while the scene plays
        layout.setHidden(labelEditing, playing);
        layout.setHidden(labelPlaying, !playing || paused);
        layout.setHidden(labelPaused,  !playing || !paused);
        ae.setChecked(togglePlay, playing);
        ae.setChecked(togglePause, playing && paused);
        ae.setEnabled(togglePause, playing);
        ae.setEnabled(buttonStop, playing);
    }

    /**
     * Shows the frame rate and the entity count on the status bar. Call every frame: nothing
     * is allocated, and a number that did not change costs a comparison.
     *
     * @param fps      frames per second
     * @param entities how many entities the scene holds
     */
    public void showStats(int fps, int entities) {
        statusText.setLength(0);
        statusText.append(fps);
        ae.setLabel(labelFpsValue, statusText);
        statusText.setLength(0);
        statusText.append(entities);
        ae.setLabel(labelEntitiesValue, statusText);
    }

    /**
     * Shows the project being edited on the status bar.
     *
     * @param path the project's directory
     */
    public void showProject(String path) { ae.setLabel(labelProject, path); }

    /**
     * The tool chosen on the toolbar.
     *
     * @return {@code "select"}, {@code "move"}, {@code "rotate"} or {@code "scale"}
     */
    public String tool() {
        if (ae.isChecked(toggleMove))   return "move";
        if (ae.isChecked(toggleRotate)) return "rotate";
        if (ae.isChecked(toggleScale))  return "scale";
        return "select";
    }

    /** @return whether Quit was chosen this frame */
    public boolean quitAsked() { return quitAsked; }

    /** @return whether Esc was pressed this frame while the viewport had focus and nothing was open over it */
    public boolean escapeInViewport() { return escapeInViewport; }

    /** @return whether the viewport has focus: while it does, the camera takes the keys */
    public boolean viewportFocused() { return tree.focused() == surfaceScene; }

    /** @return whether the left button went down on the scene this frame */
    public boolean viewportPressed() { return viewportPressed; }

    /** @return where that press was, across the viewport, from -1 at its left to 1 at its right */
    public float pressNdcX() { return pressNdcX; }

    /** @return where that press was, up the viewport, from -1 at its bottom to 1 at its top */
    public float pressNdcY() { return pressNdcY; }

    /** @return how far the wheel turned over the viewport this frame */
    public float viewportWheel() { return viewportWheel; }

    /** @return the viewport's width in interface units, as last solved; times {@link #pixelScale} for pixels */
    public float viewportWidth() { return layout.width(surfaceScene); }

    /** @return the viewport's height in interface units, as last solved */
    public float viewportHeight() { return layout.height(surfaceScene); }

    /** @return framebuffer pixels per interface unit this frame: the system's display scale */
    public float pixelScale() { return pixelScale; }

    /** @return the pointer across the window, in interface units, as this frame read it */
    public float pointerX() { return pointerX; }

    /** @return the pointer down the window, in interface units */
    public float pointerY() { return pointerY; }

    /** Writes the arrangement down as the user's arrangement of the layout in use. */
    private void saveLayout() {
        layoutSaveDue = 0;
        AegisLayoutFile base = layoutsInLine[ShellFiles.isArrangement(layoutsInLine[0].source()) && layoutsInLine.length > 1 ? 1 : 0];
        ShellFiles.saveArrangement(layoutChoice, base.name(), "editor", screens.describe("editor"));
    }

    /** Writes down an arrangement change not yet written, releases what Aegis holds, stops the theme watcher. */
    public void cleanup() {
        if (layoutSaveDue != 0) saveLayout();
        if (themeWatcher != null) themeWatcher.close();
        aegis.cleanup();
    }
}
