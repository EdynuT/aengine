package com.aengine.editor;

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

    private final int root;
    private final AegisScreens screens;
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
     * @param window the editor's window, for the clipboard
     */
    public EditorShell(Window window) {
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
        showPlayState(false, false);

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

        int status = screens.rowPanel("status");
        layout.setPadding(status, 4.0f);

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
        AegisLocale locale = ShellFiles.loadLocale(tag);
        ae.applyLocale(locale);
        String check = System.getProperty("aengine.localeCheck");
        ShellFiles.reportLocale(locale, tag, check != null ? Path.of(check) : null);
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
                    if (key == Keys.ESCAPE && viewportHadFocus) escapeInViewport = true;
                    ae.key(key, mods, false);
                }
                case KEY_REPEAT -> ae.key(Input.eventCode(i), Input.eventMods(i), true);
                default -> { }   // releases and mouse buttons: the tree already has them
            }
        }

        ae.update(root);

        // The arrangement the user changed — a dock resized — is written down a second after
        // the last change, so a drag is one write, not one per frame.
        if (ae.wasLayoutChanged()) layoutSaveDue = System.nanoTime() + LAYOUT_SAVE_DELAY;
        if (layoutSaveDue != 0 && System.nanoTime() >= layoutSaveDue) saveLayout();

        if (ae.wasActivated(menuItemSaveScene)) saveAsked = true;
        if (ae.wasActivated(menuItemQuit))      quitAsked = true;

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
        ae.setChecked(togglePlay, playing);
        ae.setChecked(togglePause, playing && paused);
        ae.setEnabled(togglePause, playing);
        ae.setEnabled(buttonStop, playing);
    }

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
