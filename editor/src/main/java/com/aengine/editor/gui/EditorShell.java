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
 * <p>While Dear ImGui still draws some windows, it draws on top of this: over one of its
 * windows the pointer is ImGui's, and while one of its fields is typed into the keys are.</p>
 */
public final class EditorShell {

    /** One quad per glyph and per shape: a full editor with text in every panel. */
    private static final int MAX_QUADS = 8192;

    private final Aegis aegis;
    private final AegisLayout layout;
    private final AegisTree tree;
    private final AegisWidgets ae;

    private int root;
    private final AegisScreens screens;
    private AegisLocale locale;

    /** The docks the View menu closes and opens, by panel name, with their menu items (4c). */
    private static final String[] DOCKS = { "hierarchy", "assets", "inspector", "console", "output" };
    private final int[] menuItemDocks = new int[DOCKS.length];
    private final int menuItemRestoreLayout;

    /** Edit (4g): the scene's history, its two items, what they were last shown for, and whether the scene plays. */
    private final UndoHistory history;
    private final int menuItemUndo, menuItemRedo;
    private int historyShown = -1;
    private boolean playing;

    /** The docks' tabs' context menu (4f), and the dock it was last opened for. */
    private final int menuDock, menuItemCloseDock;
    private String dockMenuFor;
    private final String layoutName;

    /** When to write the arrangement down: a second after the user last changed it; 0 for not due. */
    private long layoutSaveDue;
    private static final long LAYOUT_SAVE_DELAY = 1_000_000_000L;

    // Step 3f-5: the theme on screen, the name it was asked for by — null for the factory
    // theme — and whether a reload has been asked for, run at the start of the next frame.
    private AegisTheme theme;
    private final String themeName;
    private boolean themeReloadAsked;
    private final ThemeWatcher themeWatcher;

    // Widgets the editor acts on.
    private final int menuItemSaveScene;
    private final int menuItemQuit;
    private final int surfaceScene;
    private final int toggleSelect, toggleMove, toggleRotate, toggleScale;
    private final int togglePlay, togglePause, buttonStop;
    private final int labelEditing, labelPlaying, labelPaused;
    private final int labelFpsValue, labelEntitiesValue, labelProject;

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
        // and Restore default layout, which forgets the user's arrangement.
        // Edit (4g): Undo and Redo walk the scene's history. In a text field Ctrl+Z and Ctrl+Y
        // are the field's own, for what is typed; the menu's items are the scene's always.
        int menuEdit = ae.menu(menuBarMain, "menuEdit", "Edit");
        menuItemUndo = ae.menuItem(menuEdit, "menuItemUndo", "Undo", "Ctrl+Z");
        menuItemRedo = ae.menuItem(menuEdit, "menuItemRedo", "Redo", "Ctrl+Y");

        int menuView = ae.menu(menuBarMain, "menuView", "View");
        menuItemDocks[0] = ae.menuCheckItem(menuView, "menuItemHierarchy", "Hierarchy");
        menuItemDocks[1] = ae.menuCheckItem(menuView, "menuItemAssets",    "Assets");
        menuItemDocks[2] = ae.menuCheckItem(menuView, "menuItemInspector", "Inspector");
        menuItemDocks[3] = ae.menuCheckItem(menuView, "menuItemConsole",   "Console");
        menuItemDocks[4] = ae.menuCheckItem(menuView, "menuItemOutput",    "Output");
        ae.separator(menuView, "separatorView");
        menuItemRestoreLayout = ae.menuItem(menuView, "menuItemRestoreLayout", "Restore default layout");

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
        int spaceBefore = layout.box(toolbar);
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
        layout.setGrow(statusSpace, 1.0f);
        labelProject = ae.label(status, "labelProject", "");
        showPlayState(false, false);

        // ── The screen, from the first layout in line that describes it usably.
        layoutName = System.getProperty("aengine.layout", "default");
        AegisLayoutFile[] layouts = ShellFiles.loadLayouts(layoutName);
        root = screens.build("editor", layouts);
        for (AegisLayoutFile file : layouts) file.report();

        // ── The theme: the factory's — the catalogue's defaults, compiled in — unless
        //    -PthemeFile=<name> asks for a file. Applied once the tree is built, since a theme
        //    finds widgets by their paths.
        themeName = System.getProperty("aengine.theme");
        if (themeName != null) {
            theme = ShellFiles.loadTheme(themeName);
        } else {
            theme = AegisTheme.factory();
            Logger.info(Logger.System.UI, "Theme \"%s\": the engine's defaults.", theme.name());
        }
        ae.applyTheme(theme, root);
        // The theme author's development option: saving the file applies it. Off by default.
        themeWatcher = themeName != null && Boolean.getBoolean("aengine.themeWatch")
            ? ThemeWatcher.start(themeName) : null;

        // ── The language: the system's, unless -Plocale=<tag> says otherwise.
        String tag = System.getProperty("aengine.locale", java.util.Locale.getDefault().toLanguageTag());
        locale = ShellFiles.loadLocale(tag);
        ae.applyLocale(locale);
        String check = System.getProperty("aengine.localeCheck");
        ShellFiles.reportLocale(locale, tag, check != null ? Path.of(check) : null);
        showDocksInMenu();
        giveDocksTheirMenu();
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
     * View &gt; Restore default layout: the user's arrangement of the editor screen is
     * forgotten — taken out of their file — and the screen is built again from the next in
     * line, the installation's or the factory's, then dressed and translated again.
     */
    private void restoreLayout() {
        layoutSaveDue = 0;
        ShellFiles.forgetLayout(layoutName, "editor");
        AegisLayoutFile[] layouts = ShellFiles.loadLayouts(layoutName);
        root = screens.rebuild("editor", layouts);
        for (AegisLayoutFile file : layouts) file.report();
        ae.applyTheme(theme, root);
        ae.applyLocale(locale);
        showDocksInMenu();
        giveDocksTheirMenu();
        Logger.info(Logger.System.UI, "Layout restored.");
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
     * @param width            the window's width in pixels
     * @param height           the window's height in pixels
     * @param sceneTexture     the scene framebuffer's colour texture, for the viewport
     * @param imguiHasMouse    whether a Dear ImGui window is under the pointer
     * @param imguiHasKeyboard whether a Dear ImGui field is being typed into
     */
    public void frame(int width, int height, int sceneTexture, boolean imguiHasMouse, boolean imguiHasKeyboard) {
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
        if ((themeReloadAsked || themeSaved) && themeName != null) {
            AegisTheme reloaded = ShellFiles.reloadTheme(themeName, theme);
            if (reloaded != theme) {
                theme = reloaded;
                ae.applyTheme(theme, root);
            }
        }
        themeReloadAsked = false;

        aegis.begin(width, height);
        layout.solve(root, 0.0f, 0.0f, width, height);

        // The pointer. Over a Dear ImGui window it is ImGui's, so the tree is given a point
        // outside the screen.
        float mouseX = imguiHasMouse ? -1.0f : (float) Input.getMouseX();
        float mouseY = imguiHasMouse ? -1.0f : (float) Input.getMouseY();
        tree.update(root, mouseX, mouseY, Input.isMouseButtonPressed(0));

        // The right button looks around with the camera, so a right press on the viewport
        // focuses it as a left press does.
        boolean rightDown = Input.isMouseButtonPressed(1);
        if (rightDown && !rightWasDown && tree.hovered() == surfaceScene) tree.focus(surfaceScene);
        rightWasDown = rightDown;
        ae.secondaryButton(rightDown && !imguiHasMouse);   // a click with it opens context menus

        boolean viewportHadFocus = tree.focused() == surfaceScene && !ae.isAnyOverlayOpen();
        for (int i = 0; i < Input.eventCount(); i++) {
            Input.EventType type = Input.eventType(i);
            if (type == Input.EventType.SCROLL) {
                if (imguiHasMouse) continue;
                // Over the viewport the wheel is the camera's; anywhere else it scrolls.
                if (tree.hovered() == surfaceScene) viewportWheel += Input.eventScrollY(i);
                else ae.scroll(Input.eventScrollY(i));
                continue;
            }
            if (imguiHasKeyboard) continue;
            switch (type) {
                case CHAR -> ae.character(Input.eventCode(i));
                case KEY_PRESS -> {
                    int key = Input.eventCode(i), mods = Input.eventMods(i);
                    boolean ctrl = (mods & Keys.MOD_CONTROL) != 0;
                    // Editor keys, not handed to the widgets. Ctrl+R reads the theme again:
                    // a development key, until the settings page exists.
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
            layout.solve(root, 0.0f, 0.0f, width, height);
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

    /** @return the viewport's width in pixels, as last solved: what the scene renders at */
    public float viewportWidth() { return layout.width(surfaceScene); }

    /** @return the viewport's height in pixels, as last solved */
    public float viewportHeight() { return layout.height(surfaceScene); }

    /** Writes the arrangement into the user's layout file. */
    private void saveLayout() {
        layoutSaveDue = 0;
        ShellFiles.saveLayout(layoutName, "editor", screens.describe("editor"));
    }

    /** Writes down an arrangement change not yet written, releases what Aegis holds, stops the theme watcher. */
    public void cleanup() {
        if (layoutSaveDue != 0) saveLayout();
        if (themeWatcher != null) themeWatcher.close();
        aegis.cleanup();
    }
}
