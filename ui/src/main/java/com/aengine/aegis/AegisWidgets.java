package com.aengine.aegis;

import com.aengine.core.Keys;
import com.aengine.core.Window;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.function.LongSupplier;
/**
 * The widgets — layer L4's controls, built on the layout and the tree.
 *
 * <p>A widget is a layout node that knows what it is. Creating one makes the node, sizes it,
 * marks it interactive and focusable in the {@link AegisTree}, and records its kind and
 * whatever state that kind keeps. The handle it returns is the same {@code int} as in the
 * layout and the tree: still one tree, now with some of its nodes being buttons.</p>
 *
 * <pre>{@code
 * AegisWidgets ae = aegis.widgets();
 *
 * // once, while building
 * int play = ae.button(toolbar, "Play");
 *
 * // every frame, after the layout's solve and the tree's update
 * for (each key event)       ae.key(code, mods, isRepeat);
 * for (each character event) ae.character(codepoint);
 * for (each wheel event)     ae.scroll(dy);
 * ae.update(root);
 * if (ae.wasActivated(play)) start();
 *
 * // ...draw panes...
 * ae.draw(root);
 * }</pre>
 *
 * <h2>Why it is shaped like this</h2>
 *
 * <p><strong>Handed out, not forwarded.</strong> Widgets are reached through
 * {@code aegis.widgets()} rather than as methods on {@link Aegis}, for the reason the Aegis
 * class comment gives: one front door, handing out objects, not a class with a method per
 * widget.</p>
 *
 * <p><strong>Input is handed in.</strong> Like the tree's pointer, the keyboard reaches
 * widgets through {@link #key}, called by whoever owns input — so the editor can keep keys from
 * Aegis while Dear ImGui has the keyboard. Tab and Shift+Tab are handled here too: moving focus
 * is part of what the keyboard does to widgets.</p>
 *
 * <p><strong>Activation, not clicks.</strong> A button is activated by a click or, while
 * focused, by Enter or Space. {@link #wasActivated} answers for all three, so code using a
 * button never has to care which way it was pressed.</p>
 *
 * <p><strong>Sized to its label, at build time.</strong> A button measures its label once when
 * it is created and asks the layout for that size plus padding. The layout itself stays free
 * of text: sizing to content is the widget's job, done when the content is known, not on
 * every solve.</p>
 *
 * <p><strong>Colours come from {@link AegisStyle} only</strong>, which the theme fills — see
 * {@link #applyTheme}. Nothing in this class names a colour.</p>
 *
 * <p>Allocates nothing per frame. Not thread-safe, and not meant to be.</p>
 */
public final class AegisWidgets {

    // What a node is, as far as widgets are concerned.
    private static final byte NOT_A_WIDGET = 0;
    private static final byte BUTTON       = 1;
    private static final byte CHECKBOX     = 2;
    private static final byte SLIDER       = 3;
    private static final byte TEXT_FIELD   = 4;
    private static final byte TEXT_BOX     = 5;   // a text field of several lines: shares all of its state
    private static final byte LABEL        = 6;   // step 3g-1: text, and nothing to act on
    private static final byte SEPARATOR    = 7;   // step 3g-1: a line across its parent
    private static final byte SPINNER      = 8;   // step 3g-3: a number, typed or stepped; edits as a text field does

    private static final byte TABBED_PANE  = 9;   // step 3g-4: the column holding a tab bar and the shown panel
    private static final byte TAB_BAR      = 10;  //            its bar, the part that is drawn and takes input
    private static final byte SPLIT_PANE   = 11;  // step 3g-5: a row or column of two sides and a divider
    private static final byte DIVIDER      = 12;  //            the divider, dragged or moved by keys

    private static final byte TREE_VIEW    = 13;  // step 3g-6: items in levels, opened and closed

    private static final byte COMBO_BOX    = 14;  // step 3g-9: a choice of items, from a list that drops down
    private static final byte COMBO_LIST   = 15;  //            the list, a root shown as a popup

    private static final byte MENU_BAR     = 16;  // step 3g-10: a row of titles
    private static final byte MENU_TITLE   = 17;  //             a title on it, which opens its menu
    private static final byte MENU         = 18;  //             a menu: a column shown as a popup
    private static final byte MENU_ITEM    = 19;  //             an item in a menu

    private static final byte DIALOG       = 20;  // step 3g-11: a modal column on the overlay layer
    private static final byte DIALOG_TITLE = 21;  //             its title bar, dragged to move it

    /** "No item" — the parent of a tree's top level, and what is selected in a tree with none. */
    public static final int NO_ITEM = -1;

    /** Two presses on the same tree item within this long are a double click. */
    private static final long DOUBLE_CLICK_NANOS = 500_000_000L;

    /** How long the pointer rests on a node before its tooltip shows, as Swing's initial delay. */
    private static final long TOOLTIP_DELAY_NANOS  = 750_000_000L;
    /** A tooltip hidden this recently by the pointer leaving lets the next one show at once. */
    private static final long TOOLTIP_RESHOW_NANOS = 500_000_000L;

    /** Neither side of a split pane is made smaller than this by its divider. */
    private static final float SPLIT_MINIMUM = 24.0f;
    /** How far an arrow key moves a divider; Shift makes it ten times as far. */
    private static final float DIVIDER_KEY_STEP = 8.0f;

    /** A held spinner arrow steps again after this long, then every {@link #SPIN_REPEAT_NANOS}. */
    private static final long SPIN_DELAY_NANOS  = 400_000_000L;
    private static final long SPIN_REPEAT_NANOS =  60_000_000L;

    /** Shift + arrow moves a slider this many steps at once. */
    private static final int LARGE_STEP = 10;

    /** How long the caret stays shown, then hidden, while it blinks. */
    private static final long CARET_BLINK_NANOS = 530_000_000L;

    private final Aegis       aegis;
    private final AegisLayout layout;
    private final AegisTree   tree;
    /**
     * The style every widget draws with unless the theme gives it one of its own: the theme's
     * global values, or the factory's until a theme is applied. Replaced, not changed in place,
     * when a theme is applied — see {@link #applyTheme}.
     */
    private AegisStyle globalStyle = new AegisStyle();

    /**
     * A widget's own style, when a scoped rule of the theme reaches it; {@code null} for the
     * rest, which draw with {@link #globalStyle}. Most widgets have none, so most share one.
     * Indexed by layout handle.
     */
    private final AegisStyle[] styleOf;

    /**
     * The size each widget was last fitted to from its content. A size the layout holds that
     * differs from it was set by someone else — code with {@link AegisLayout#setSize}, or a
     * layout file — and is kept when the widget is fitted again, on that axis.
     */
    private final float[] fittedWidth;
    private final float[] fittedHeight;

    /**
     * The path a theme reaches each widget by, ending in its own name: the names above it when
     * it was made — {@code viewport, buttonStop}. Kept from creation rather than read from the
     * tree, so a layout that moves the widget to another panel does not change how it is dressed.
     */
    private final List<String>[] themePath;

    /** How many lines a text box shows — kept so its height can be worked out again when a theme changes the padding. */
    private final int[] visibleLines;

    /** Indexed by layout handle. */
    private final byte[]   kind;
    private final String[] label;   // set once at build time; never built per frame. A text field's placeholder.
    private final boolean[] checked; // checkboxes: ticked or not

    // Sliders and spinners: the value, its range, and how far one small step moves it.
    private final float[] sliderValue;
    private final float[] sliderMin;
    private final float[] sliderMax;
    private final float[] sliderStep;

    /**
     * A tabbed pane's tabs: their titles, the panel each shows, and which one is shown. Held by
     * both of the pane's nodes — the column and its bar — so either finds it. Made when the
     * pane is, grown when a tab is added; nothing here is made in the frame loop.
     */
    private static final class TabSet {
        String[] titles   = new String[4];
        int[]    contents = new int[4];
        int      count;
        int      selected;
    }

    private final TabSet[] tabsOf;

    /**
     * A tree's items: a forest threaded through arrays, as the layout keeps its nodes — each
     * item knows its parent, first and last child and next sibling. Grown, doubling, when an
     * item is added; nothing here is made in the frame loop.
     */
    private static final class TreeItems {
        String[]  text        = new String[16];
        int[]     parent      = new int[16];
        int[]     firstChild  = new int[16];
        int[]     lastChild   = new int[16];
        int[]     nextSibling = new int[16];
        boolean[] expanded    = new boolean[16];
        int firstTop = NO_ITEM, lastTop = NO_ITEM;
        int count;
        int selected = NO_ITEM;
    }

    private final TreeItems[] itemsOf;

    // Combo boxes: the items, the one chosen, the list a box drops down — and, on the list,
    // the box it belongs to and the item it points at while open.
    private final String[][] comboItems;
    private final int[]      comboChosen;
    private final int[]      comboList;
    private final int[]      listOwner;
    private final int[]      listHighlight;

    // Menus: what a title opens, what opened a menu (a title, or an item for a submenu), the
    // submenu an item opens, whether an item ticks, what it says at its right, and the item a
    // menu points at.
    private final int[]     titleMenu;
    private final int[]     menuOwner;
    private final int[]     itemSubMenu;
    private final boolean[] itemChecks;
    private final String[]  itemShortcut;
    private final int[]     menuPointed;

    /** The first menu bar made: the one F10 opens. */
    private int firstMenuBar = AegisLayout.NONE;
    /** The item or title the pointer was over at the last update, so hovering acts on a change only. */
    private int lastMenuHover = AegisLayout.NONE;

    // Keys taken while a menu is open: they belong to the menus, not to the focused widget.
    private int     menuLines;      // Up, Down
    private int     menuSteps;      // Left, Right
    private boolean menuEnter;      // Enter, Space
    private boolean pendingF10;

    /** Each dialog's default button — what Enter activates from a field in it — or NONE. */
    private final int[] defaultButton;
    /** The dialog the user cancelled this frame — Esc, or its close mark — or NONE. */
    private int cancelled = AegisLayout.NONE;
    /** Where on a title bar the pointer took hold of it, from the dialog's corner. */
    private float dialogGrabX, dialogGrabY;

    /** Alt+Down or F4 this frame: open the focused combo box's list. */
    private boolean pendingOpenList;

    // The last press on a tree item, to tell a double click.
    private int  treePressItem = NO_ITEM;
    private int  treePressTree = AegisLayout.NONE;
    private long treePressAt;

    /** Whether the last name of a widget's {@link #themePath} is its own, or its nearest named container's. */
    private final boolean[] themeNamed;

    /** Where on a divider the pointer took hold of it, from its start, so it does not jump. */
    private float dividerGrab;

    /**
     * What every timing here reads — the caret's blink, the undo pause, a held arrow — in
     * nanoseconds. The system clock; a test sets its own, to step time instead of waiting.
     */
    LongSupplier clock = System::nanoTime;

    private long now() { return clock.getAsLong(); }

    // The overlays open, bottom first, as the tree's layers are: where each was asked to be,
    // whether it is modal, and the widget that opened it, if one did — the combo box whose
    // list it is. A press outside a popup closes it; the widget that opened it is told, so a
    // press on that widget does not open it again at once.
    private static final int MAX_OVERLAYS = 16;
    private final int[]     overlayNode  = new int[MAX_OVERLAYS];
    private final float[]   overlayX     = new float[MAX_OVERLAYS];
    private final float[]   overlayY     = new float[MAX_OVERLAYS];
    private final boolean[] overlayModal = new boolean[MAX_OVERLAYS];
    private final int[]     overlayOwner = new int[MAX_OVERLAYS];
    private final int[]     overlayFocus = new int[MAX_OVERLAYS];   // what had focus before a modal one
    private int overlayCount;

    /** The owner of a popup a press closed this frame, or NONE: that press is not to open it again. */
    private int dismissedOwner = AegisLayout.NONE;

    /** Esc went down, outside a text field: it closes the topmost overlay. */
    private boolean pendingEscape;

    /** The root update() was last given — what overlays are kept inside before the first frame is drawn. */
    private int lastRoot = AegisLayout.NONE;

    // Tooltips — step 3g-8: each node's text, if it has one; the node under the resting
    // pointer and since when it rested; the one shown, and whether the keyboard showed it.
    private final String[] tooltip;
    private int     tipCandidate = AegisLayout.NONE;
    private long    tipRestingSince;
    private float   tipPointerX = Float.NaN, tipPointerY = Float.NaN;
    private int     tipShown = AegisLayout.NONE;
    private boolean tipByKeyboard;
    private float   tipX, tipY;
    private long    tipHiddenAt = Long.MIN_VALUE / 2;
    private boolean pendingTipToggle;   // Ctrl+F1
    private boolean pendingAnyKey;      // any other key: hides a tooltip

    /** Ctrl+Tab and Ctrl+PageDown this frame, or their opposites: + or -. */
    private int pendingTabSwitch;

    /** Spinners: how many digits after the decimal point the number is shown and kept with. */
    private final int[] spinnerDecimals;

    // The spinner arrow being held: which way it steps, +1 or -1, and when it steps next.
    private int  spinHeld;
    private long spinNextAt;

    /** One arrow or mark, written here to be measured and drawn without making a String. */
    private final StringBuilder arrowText = new StringBuilder(2);

    /** Where a slider's value is written as text each frame, reused rather than rebuilt. */
    private final StringBuilder valueText = new StringBuilder(16);

    // Text fields and text boxes: the text, in a builder sized to the limit when the widget is
    // made, so typing never grows it (one with no limit starts smaller and grows); the limit;
    // where the caret is, as a character index (0 is before the first character, length is
    // after the last); the anchor, the other end of the selection — the selection is the text
    // between anchor and caret, and there is none when they are equal; and how far the text is
    // scrolled, in pixels — left in a field, up in a box.
    private final StringBuilder[] fieldText;
    private final int[]           fieldMaxLength;
    private final int[]           caret;
    private final int[]           anchor;
    private final float[]         fieldScroll;

    /** A field with no limit starts with room for this many characters, and grows past it. */
    private static final int UNLIMITED_START_CAPACITY = 64;

    /** Where copy gathers the selection and paste filters the clipboard, reused. Grows once to the largest seen. */
    private final StringBuilder clipboardScratch = new StringBuilder(64);

    /** The window whose clipboard copy, cut and paste use. {@code null} until set: they then do nothing. */
    private Window clipboard;

    /** The node pressed at the last update(), to tell a fresh press from a held one. */
    private int lastPressed = AegisLayout.NONE;

    // A text box's lines as wrapped to its width — line i is [lineStarts[i], lineEnds[i]) of
    // its text. Worked out when a box is drawn or a key needs them, never kept between: one
    // pair of arrays serves every box. They grow, doubling, only for a text with more lines
    // than they hold, which is the rare allocation a box with no limit accepts.
    private int[] lineStarts = new int[256];
    private int[] lineEnds   = new int[256];
    private int   lineCount;

    /**
     * Where Up and Down aim, across the line, while they are pressed one after another: the
     * caret's x when the first of them was pressed. Passing through a short line and back into
     * a long one returns the caret to where it was. Below zero when no vertical run is going on.
     */
    private float caretGoalX = -1.0f;

    /**
     * Whether the caret moved since the focused widget was last drawn, so it should be
     * scrolled into view. Only then: a text box scrolled with the wheel must stay where it was
     * put, even though its caret is out of sight.
     */
    private boolean revealCaret;

    // Undo history for the text field being edited — one history, not one per field: it
    // belongs to the field with focus and is dropped when focus leaves, after which undoing is
    // the engine's business, not the field's. Each block is one replacement: at hPos, the text
    // hRemoved long was replaced by text hInserted long. Both are kept in hChars, removed then
    // inserted, from hStart; blocks lie one after another there in order. Undo puts the removed
    // text back; redo puts the inserted text back. Everything is made here, once.
    private static final int  HISTORY_BLOCKS      = 100;
    private static final int  HISTORY_CHARS       = 16 * 1024;
    /** A block still being typed into is closed after this long without an edit. */
    private static final long HISTORY_PAUSE_NANOS = 1_000_000_000L;

    // What a block is, so the next edit knows whether it continues it.
    private static final byte BLOCK_TYPING    = 1;   // characters typed one after another
    private static final byte BLOCK_BACKSPACE = 2;   // Backspaces one after another
    private static final byte BLOCK_DELETE    = 3;   // Deletes one after another
    private static final byte BLOCK_SINGLE    = 4;   // a paste, a cut, a selection deleted: never continued
    private static final byte BLOCK_SPACE     = 5;   // spaces typed one after another
    private static final byte BLOCK_NEWLINE   = 6;   // line breaks typed one after another, in a text box

    private final byte[] hKind         = new byte[HISTORY_BLOCKS];
    private final int[]  hPos          = new int[HISTORY_BLOCKS];
    private final int[]  hStart        = new int[HISTORY_BLOCKS];
    private final int[]  hRemoved      = new int[HISTORY_BLOCKS];
    private final int[]  hInserted     = new int[HISTORY_BLOCKS];
    private final int[]  hCaretBefore  = new int[HISTORY_BLOCKS];
    private final int[]  hAnchorBefore = new int[HISTORY_BLOCKS];
    private final int[]  hCaretAfter   = new int[HISTORY_BLOCKS];
    private final int[]  hAnchorAfter  = new int[HISTORY_BLOCKS];
    private final char[] hChars        = new char[HISTORY_CHARS];

    private int     hField   = AegisLayout.NONE;   // whose history this is
    private int     hCount;                        // blocks recorded, undone ones included
    private int     hApplied;                      // blocks in effect; those past it can be redone
    private boolean hOpen;                         // whether the last block may still grow
    private long    hLastEdit;                     // when it last grew, for the pause

    /** The spinner stepped by this frame's key() calls, reported by update(). */
    private int  stepped = AegisLayout.NONE;
    /** The text field edited by this frame's key() and character() calls, reported by update(). */
    private int  edited = AegisLayout.NONE;
    /** When the caret was last shown afresh: it blinks from here, so it is visible while typing. */
    private long caretShownAt = System.nanoTime();
    /** The focus at the last update(), to restart the blink when a field gains focus. */
    private int  lastFocused = AegisLayout.NONE;

    // Keyboard intent gathered by key() and character(), applied by update(), which knows the root.
    private int     pendingFocusMove;   // +1 Tab, -1 Shift+Tab, 0 none
    private boolean pendingEnter;       // Enter went down
    private boolean pendingSpace;       // Space went down
    private int     pendingSteps;       // slider steps asked for this frame, + or -
    private int     pendingJump;        // -1 Home, +1 End, 0 none
    private float   pendingWheel;       // the wheel's turn this frame, as scroll() was handed it
    private int     pendingPages;       // PageUp and PageDown this frame, - or +
    private int     pendingLines;       // Up and Down this frame, - or +, for a focused scrolling pane

    // A scroll bar being dragged: whose it is, and where on the bar the pointer took hold,
    // from the bar's start, so the bar does not jump under the pointer.
    private int   barDragged = AegisLayout.NONE;
    private float barGrab;

    // This frame's outcome.
    private int     activated = AegisLayout.NONE;   // a button, activated
    private int     changed   = AegisLayout.NONE;   // a checkbox, toggled
    private boolean activatedByKeyboard;

    AegisWidgets(Aegis aegis, AegisLayout layout, AegisTree tree, int capacity) {
        this.aegis  = aegis;
        this.layout = layout;
        this.tree   = tree;
        this.kind    = new byte[capacity];
        this.label   = new String[capacity];
        this.checked = new boolean[capacity];

        this.sliderValue = new float[capacity];
        this.sliderMin   = new float[capacity];
        this.sliderMax   = new float[capacity];
        this.sliderStep  = new float[capacity];
        this.spinnerDecimals = new int[capacity];
        this.tabsOf          = new TabSet[capacity];
        this.itemsOf         = new TreeItems[capacity];
        this.tooltip         = new String[capacity];
        this.comboItems      = new String[capacity][];
        this.defaultButton   = new int[capacity];
        this.titleMenu       = new int[capacity];
        this.menuOwner       = new int[capacity];
        this.itemSubMenu     = new int[capacity];
        this.itemChecks      = new boolean[capacity];
        this.itemShortcut    = new String[capacity];
        this.menuPointed     = new int[capacity];
        this.comboChosen     = new int[capacity];
        this.comboList       = new int[capacity];
        this.listOwner       = new int[capacity];
        this.listHighlight   = new int[capacity];
        this.themeNamed      = new boolean[capacity];

        this.fieldText      = new StringBuilder[capacity];
        this.fieldMaxLength = new int[capacity];
        this.caret          = new int[capacity];
        this.anchor         = new int[capacity];
        this.fieldScroll    = new float[capacity];

        this.styleOf      = new AegisStyle[capacity];
        this.fittedWidth  = new float[capacity];
        this.fittedHeight = new float[capacity];
        this.visibleLines = new int[capacity];
        @SuppressWarnings("unchecked")
        List<String>[] paths = new List[capacity];
        this.themePath = paths;
    }

    /**
     * The colours and sizes widgets draw with when the theme gives them none of their own. See
     * {@link AegisStyle}. A new object each time a theme is applied, so hold on to it no longer
     * than the current theme.
     *
     * @return the shared style
     */
    public AegisStyle style() { return globalStyle; }

    /** The style a widget draws with: its own if a scoped theme rule reached it, the global one otherwise. */
    AegisStyle styleOf(int node) {
        AegisStyle own = styleOf[node];
        return own != null ? own : globalStyle;
    }

    /**
     * Dresses every widget under {@code root} in a theme: the theme's global values become the
     * style all widgets share, and each widget a scoped rule reaches gets a style of its own.
     * Widgets are then sized again, since a theme may change their padding.
     *
     * <p>Call it after the tree is built, and again after building more under {@code root}.
     * Never in the frame loop: rules are matched here, by name, so that drawing only ever reads a
     * field.</p>
     *
     * <p>A widget is reached by the path it was made with — the panel code made it in, then its
     * name: {@code viewport.buttonStop} — wherever a layout file has since moved it. A theme
     * and a layout are thereby independent: moving the Stop button to the toolbar keeps it red,
     * and the toolbar's {@code toolbar.button} rules do not reach it.</p>
     *
     * <p>A widget's size is worked out again from its content and the theme — a theme with
     * more padding makes a button larger — except on an axis where something else set it: code
     * with {@link AegisLayout#setSize}, or a layout file's {@code "width"}. That size is
     * kept.</p>
     *
     * <p>Rules that reach no widget are reported in the log once this is done.</p>
     *
     * @param theme the theme to apply
     * @param root  the top of the tree to dress; names are read from here down
     */
    public void applyTheme(AegisTheme theme, int root) {
        globalStyle = theme.globalStyle();
        Arrays.fill(styleOf, null);

        dressSubtree(theme, root);
        // What hangs nowhere is dressed too: dialogs and popups not open, panels a layout does
        // not show, tabs not shown — so whatever is shown later is already in the theme.
        for (int node = 0; node < layout.nodeCount(); node++) {
            if (node != root && layout.parent(node) == AegisLayout.NONE) dressSubtree(theme, node);
        }
        theme.report();
    }

    /** Walks a subtree, giving each widget the style its path calls for, and sizing it again. */
    private void dressSubtree(AegisTheme theme, int node) {
        if (kind[node] != NOT_A_WIDGET) {
            styleOf[node] = theme.styleFor(themePath[node], themeNamed[node], themeKind(node));
            fitToContent(node);
        }
        // A tabbed pane's panels that are not shown hang nowhere; they are dressed all the same,
        // so switching to one shows it in the theme.
        // A bar's menus, and an item's submenu, hang nowhere until opened: dressed all the same.
        if (kind[node] == MENU_TITLE) dressSubtree(theme, titleMenu[node]);
        if (kind[node] == MENU_ITEM && itemSubMenu[node] != AegisLayout.NONE) dressSubtree(theme, itemSubMenu[node]);
        if (kind[node] == TABBED_PANE) {
            TabSet tabs = tabsOf[node];
            for (int i = 0; i < tabs.count; i++) {
                if (i != tabs.selected) dressSubtree(theme, tabs.contents[i]);
            }
        }
        for (int child = layout.firstChild(node); child != AegisLayout.NONE;
             child = layout.nextSibling(child)) {
            dressSubtree(theme, child);
        }
    }

    /** The kind a theme knows a widget by: the first part of the catalogue names that dress it. */
    private String themeKind(int node) {
        return switch (kind[node]) {
            case BUTTON               -> "button";
            case CHECKBOX             -> "checkbox";
            case SLIDER               -> "slider";
            case TEXT_FIELD, TEXT_BOX -> "field";
            case LABEL                -> "label";
            case SEPARATOR            -> "separator";
            case SPINNER              -> "spinner";
            case TAB_BAR              -> "tabs";
            case DIVIDER              -> "split";
            case TREE_VIEW            -> "tree";
            case COMBO_BOX            -> "combo";
            case MENU_TITLE, MENU_ITEM -> "menu";
            case DIALOG_TITLE         -> "dialog";
            default                   -> "";
        };
    }

    /**
     * Asks the layout for the size a widget's content and style call for: a label's width plus
     * padding, a slider's width, so many lines of text. Done when a widget is made and again
     * when a theme is applied.
     */
    private void fitToContent(int node) {
        AegisStyle style = styleOf(node);
        float line = aegis.lineHeight();
        switch (kind[node]) {
            case BUTTON -> fit(node,
                (float) Math.ceil(aegis.measure(label[node])) + style.buttonPaddingX * 2.0f,
                line + style.buttonPaddingY * 2.0f);
            case CHECKBOX -> fit(node,
                style.checkboxSize + style.checkboxGap + (float) Math.ceil(aegis.measure(label[node])),
                Math.max(style.checkboxSize, line) + style.checkboxPaddingY * 2.0f);
            case SLIDER -> fit(node, style.sliderWidth,
                Math.max(style.sliderThumbSize, line) + style.sliderPaddingY * 2.0f);
            case TEXT_FIELD, TEXT_BOX -> fit(node, style.textFieldWidth,
                line * Math.max(1, visibleLines[node]) + style.textFieldPaddingY * 2.0f);
            case LABEL -> fit(node,
                (float) Math.ceil(aegis.measure(label[node])), line + style.labelPaddingY * 2.0f);
            case SPINNER -> fit(node, style.spinnerWidth, line + style.textFieldPaddingY * 2.0f);
            case TAB_BAR -> fit(node, 0.0f, line + style.tabsPaddingY * 2.0f + style.tabsAccentHeight);
            case TREE_VIEW -> fitTree(node, style);
            case DIALOG_TITLE -> fit(node, (float) Math.ceil(aegis.measure(label[node])) + line * 3.0f,
                line + style.dialogTitlePaddingY * 2.0f);
            case MENU_TITLE -> fit(node, (float) Math.ceil(aegis.measure(label[node])) + style.menuPaddingX * 2.0f,
                line + style.menuPaddingY * 2.0f);
            case MENU_ITEM -> {
                float w = style.menuCheckWidth + (float) Math.ceil(aegis.measure(label[node])) + style.menuPaddingX * 2.0f;
                if (itemShortcut[node] != null) w += style.menuGap + (float) Math.ceil(aegis.measure(itemShortcut[node]));
                if (itemSubMenu[node] != AegisLayout.NONE) w += style.menuGap;
                fit(node, w, line + style.menuPaddingY * 2.0f);
            }
            case COMBO_BOX -> {
                float widest = 0.0f;
                for (String item : comboItems[node]) widest = Math.max(widest, (float) Math.ceil(aegis.measure(item)));
                fit(node, widest + style.comboPaddingX * 2.0f + style.comboArrowWidth,
                    line + style.comboPaddingY * 2.0f);
            }
            case DIVIDER -> {
                // Its room along the way the split pane lays its sides out; across, it is stretched.
                if (layout.isRow(layout.parent(node))) fit(node, style.splitSize, 0.0f);
                else                                   fit(node, 0.0f, style.splitSize);
            }
            case SEPARATOR -> {
                // Room for the line and its margins along the way its parent stacks; nothing
                // across, since the line is drawn across the whole parent (drawSeparator).
                float room = style.separatorThickness + style.separatorMargin * 2.0f;
                if (layout.isRow(layout.parent(node))) fit(node, room, 0.0f);
                else                                   fit(node, 0.0f, room);
            }
            default -> { }
        }
    }

    /**
     * Gives a widget the size its content needs, on each axis where nothing else has set one —
     * step 3f part 4d. A size that differs from the one last fitted was set by code or a layout
     * file, and stays.
     */
    private void fit(int node, float width, float height) {
        float keepWidth  = layout.requestedWidth(node)  != fittedWidth[node]  ? layout.requestedWidth(node)  : width;
        float keepHeight = layout.requestedHeight(node) != fittedHeight[node] ? layout.requestedHeight(node) : height;
        fittedWidth[node]  = width;
        fittedHeight[node] = height;
        layout.setSize(node, keepWidth, keepHeight);
    }

    /**
     * The window whose system clipboard text fields copy to and paste from. Until it is set,
     * Ctrl+C, Ctrl+X and Ctrl+V do nothing. Handed in rather than looked up, like the pointer
     * and the keys: whoever owns the window decides the interface may use its clipboard.
     *
     * @param window the window, or {@code null} to turn clipboard keys off again
     */
    public void setClipboard(Window window) { this.clipboard = window; }

    // -----------------------------------------------------------------------------------
    // Building
    // -----------------------------------------------------------------------------------

    /**
     * A button showing {@code text}, added to {@code parent}.
     *
     * <p>Sized to its label: the label's measured width plus {@code buttonPaddingX} each side,
     * the line height plus {@code buttonPaddingY} above and below. Interactive and focusable.
     * A font must already be loaded, since the label is measured here.</p>
     *
     * @param parent the row or column to add it to
     * @param name   its name, which a theme or a layout file writes — {@code "buttonStop"};
     *               see {@link AegisLayout#setNodeName}
     * @param text   the label; kept, so pass a string that does not change
     * @return its handle — a layout node, usable with the layout and the tree like any other
     * @throws IllegalArgumentException for a name {@link AegisLayout#isValidNodeName} refuses
     */
    public int button(int parent, String name, String text) {
        checkWidgetName(name, "button", "a button");
        int node = namedBox(parent, name);
        tree.setInteractive(node, true);
        tree.setFocusable(node, true);

        kind[node]    = BUTTON;
        label[node]   = text;
        styleOf[node] = null;
        fitToContent(node);
        return node;
    }

    /**
     * A checkbox: a square that ticks and unticks, with {@code text} beside it, added to
     * {@code parent}. Starts unticked.
     *
     * <p>The whole widget — square and label — is the target, so clicking the label ticks it,
     * as in every desktop toolkit. Sized to the label: the square, a gap, the label's width;
     * the line height plus {@code checkboxPaddingY} above and below. Interactive and focusable.</p>
     *
     * @param parent the row or column to add it to
     * @param name   its name, which a theme or a layout file writes — {@code "checkboxSnap"};
     *               see {@link AegisLayout#setNodeName}
     * @param text   the label; kept, so pass a string that does not change
     * @return its handle
     * @throws IllegalArgumentException for a name {@link AegisLayout#isValidNodeName} refuses
     */
    public int checkbox(int parent, String name, String text) {
        checkWidgetName(name, "checkbox", "a checkbox");
        int node = namedBox(parent, name);
        tree.setInteractive(node, true);
        tree.setFocusable(node, true);

        kind[node]    = CHECKBOX;
        label[node]   = text;
        checked[node] = false;
        styleOf[node] = null;
        fitToContent(node);
        return node;
    }

    /**
     * Whether a checkbox is ticked.
     *
     * @param node the checkbox
     * @return {@code true} when ticked
     */
    public boolean isChecked(int node) { return checked[node]; }

    /**
     * Ticks or unticks a checkbox from code — to show a setting's current value, say. Does not
     * count as a change: {@link #wasChanged} reports only what the user did.
     *
     * @param node the checkbox
     * @param on   {@code true} to tick it
     */
    public void setChecked(int node, boolean on) { checked[node] = on; }

    /**
     * A label: text on its own, added to {@code parent} — step 3g-1.
     *
     * <p>Says what something is — "Name", "Opacity" — and has nothing to act on, like Swing's
     * {@code JLabel}: neither clickable nor focusable, so Tab passes it by. Sized to its text,
     * with {@code label.padding.y} above and below, which matches a checkbox's so a label and a
     * checkbox line up in a row.</p>
     *
     * @param parent the row or column to add it to
     * @param name   its name, which a theme or a layout file writes — {@code "labelName"};
     *               see {@link AegisLayout#setNodeName}
     * @param text   the text; kept, so pass a string that does not change, and use
     *               {@link #setLabel} to show another
     * @return its handle
     * @throws IllegalArgumentException for a name {@link AegisLayout#isValidNodeName} refuses
     */
    public int label(int parent, String name, String text) {
        checkWidgetName(name, "label", "a label");
        int node = namedBox(parent, name);
        kind[node]    = LABEL;
        label[node]   = text;
        styleOf[node] = null;
        fitToContent(node);
        return node;
    }

    /**
     * Shows other text in a label, and sizes it again to fit. For text that changes now and
     * then — a count, a name — not every frame: it measures the text.
     *
     * @param node the label
     * @param text the text; kept, as at creation
     */
    public void setLabel(int node, String text) {
        label[node] = text;
        fitToContent(node);
    }

    /**
     * A separator: a thin line that sets groups apart, added to {@code parent} — step 3g-1.
     *
     * <p>It follows its parent: a horizontal line in a column, a vertical one in a row, drawn
     * across the whole parent from one inner edge to the other. It takes
     * {@code separator.line.width} plus {@code separator.margin} on each side along the way its
     * parent stacks, and nothing across. Neither clickable nor focusable.</p>
     *
     * @param parent the row or column to add it to
     * @param name   its name, which a theme or a layout file writes — {@code "separatorSettings"};
     *               see {@link AegisLayout#setNodeName}
     * @return its handle
     * @throws IllegalArgumentException for a name {@link AegisLayout#isValidNodeName} refuses
     */
    public int separator(int parent, String name) {
        checkWidgetName(name, "separator", "a separator");
        int node = namedBox(parent, name);
        kind[node]    = SEPARATOR;
        styleOf[node] = null;
        fitToContent(node);
        return node;
    }

    /**
     * A slider over {@code min}..{@code max}, starting at {@code value}, added to
     * {@code parent}: a track, a round thumb, and the value written at the right.
     *
     * <p>The value is a {@code float} — dragging moves it continuously — but what the user
     * sees beside the track is rounded to a whole number, so pick a range where whole numbers
     * mean something (0 to 100 rather than 0 to 1).</p>
     *
     * <p>Asks for {@code sliderWidth}; a stretching parent may make it wider, and the track
     * takes whatever width the value does not. One small step — the arrow keys, {@code +} and
     * {@code -} — is a hundredth of the range until {@link #setSliderStep} says otherwise;
     * Shift + arrow moves {@value #LARGE_STEP} steps, Home and End go to the ends.</p>
     *
     * @param parent the row or column to add it to
     * @param name   its name, which a theme or a layout file writes — {@code "sliderOpacity"};
     *               see {@link AegisLayout#setNodeName}
     * @param min    the value at the left end
     * @param max    the value at the right end
     * @param value  the starting value, clamped to the range
     * @return its handle
     * @throws IllegalArgumentException for a name {@link AegisLayout#isValidNodeName} refuses
     */
    public int slider(int parent, String name, float min, float max, float value) {
        checkWidgetName(name, "slider", "a slider");
        int node = namedBox(parent, name);
        tree.setInteractive(node, true);
        tree.setFocusable(node, true);

        kind[node]        = SLIDER;
        sliderMin[node]   = min;
        sliderMax[node]   = max;
        sliderStep[node]  = (max - min) / 100.0f;
        sliderValue[node] = clamp(value, min, max);
        styleOf[node]     = null;
        fitToContent(node);
        return node;
    }

    /**
     * A slider's current value, between its minimum and maximum, unrounded.
     *
     * @param node the slider
     * @return the value
     */
    public float sliderValue(int node) { return sliderValue[node]; }

    /**
     * Sets a slider's value from code, clamped to its range. Not reported by {@link #wasChanged}.
     *
     * @param node  the slider
     * @param value the new value
     */
    public void setSliderValue(int node, float value) {
        sliderValue[node] = clamp(value, sliderMin[node], sliderMax[node]);
    }

    /**
     * How far one small step — an arrow key, {@code +} or {@code -} — moves a slider.
     *
     * @param node the slider
     * @param step the step, in the slider's own units; a hundredth of the range by default
     */
    public void setSliderStep(int node, float step) { sliderStep[node] = step; }

    /**
     * A spinner: a number in a field, with two small arrows that step it, added to
     * {@code parent} — step 3g-3. What an inspector uses for a position, a count, a size.
     *
     * <p>It works as Swing's {@code JSpinner} does. The arrows, clicked or held, and the Up and
     * Down keys step the value; Shift with a key steps ten at once. The number can also be
     * typed: only what can be in a number is accepted — digits, a minus sign, and a point or
     * a comma for the decimals — and what was typed becomes the value on Enter, on a step, or
     * when focus leaves. Text that is not a number puts the last value back; a number outside
     * the range is brought to its nearest end. Esc puts the last value back and gives up
     * focus. Arriving by Tab selects the number, so typing replaces it.</p>
     *
     * <p>The value is shown and kept with as many decimals as {@code step} has, up to three —
     * a step of 1 shows 12, a step of 0.25 shows 12.25 — or as {@link #setSpinnerDecimals}
     * says. Asks for {@code spinner.width} and a text field's height.</p>
     *
     * @param parent the row or column to add it to
     * @param name   its name, which a theme or a layout file writes — {@code "spinnerCount"};
     *               see {@link AegisLayout#setNodeName}
     * @param min    the least it can be
     * @param max    the most it can be
     * @param value  the starting value, clamped to the range
     * @param step   how far an arrow or an arrow key moves it
     * @return its handle
     * @throws IllegalArgumentException for a name {@link AegisLayout#isValidNodeName} refuses
     */
    public int spinner(int parent, String name, float min, float max, float value, float step) {
        int node = textWidget(parent, name, SPINNER, 1, 24, 24);
        sliderMin[node]  = min;
        sliderMax[node]  = max;
        sliderStep[node] = step;

        int decimals = 0;
        for (float scaled = Math.abs(step); decimals < 3 && Math.abs(scaled - Math.round(scaled)) > 1e-4f; scaled *= 10.0f) {
            decimals++;
        }
        spinnerDecimals[node] = decimals;
        sliderValue[node] = rounded(node, clamp(value, min, max));
        writeSpinnerText(node);
        return node;
    }

    /**
     * A spinner's value: the last one stepped to or confirmed. A number still being typed is
     * not it yet.
     *
     * @param node the spinner
     * @return the value, within its range
     */
    public float spinnerValue(int node) { return sliderValue[node]; }

    /**
     * Sets a spinner's value from code, clamped to its range, and shows it. Not reported by
     * {@link #wasChanged}.
     *
     * @param node  the spinner
     * @param value the new value
     */
    public void setSpinnerValue(int node, float value) {
        sliderValue[node] = rounded(node, clamp(value, sliderMin[node], sliderMax[node]));
        writeSpinnerText(node);
    }

    /**
     * How many digits a spinner shows and keeps after the decimal point, from 0 to 6.
     *
     * @param node     the spinner
     * @param decimals the digits; 0 for whole numbers
     */
    public void setSpinnerDecimals(int node, int decimals) {
        spinnerDecimals[node] = Math.max(0, Math.min(6, decimals));
        sliderValue[node] = rounded(node, sliderValue[node]);
        writeSpinnerText(node);
    }

    /** A value brought to the decimals a spinner keeps, so stepping by 0.1 ten times is exactly 1. */
    private float rounded(int node, float value) {
        double scale = Math.pow(10.0, spinnerDecimals[node]);
        return (float) (Math.round(value * scale) / scale);
    }

    /** Shows a spinner's value in its field, replacing whatever was being typed. */
    private void writeSpinnerText(int node) {
        StringBuilder text = fieldText[node];
        text.setLength(0);
        int decimals = spinnerDecimals[node];
        long scale = 1;
        for (int i = 0; i < decimals; i++) scale *= 10;
        long scaled = Math.round((double) sliderValue[node] * scale);
        if (scaled < 0) text.append('-');
        scaled = Math.abs(scaled);
        text.append(scaled / scale);
        if (decimals > 0) {
            text.append('.');
            long fraction = scaled % scale;
            for (long digit = scale / 10; digit > 0; digit /= 10) text.append((char) ('0' + (fraction / digit) % 10));
        }
        caret[node]       = text.length();
        anchor[node]      = caret[node];
        fieldScroll[node] = 0.0f;
        if (hField == node) resetHistory();
    }

    /**
     * Makes what is typed in a spinner its value: a number is clamped to the range and brought
     * to the spinner's decimals; anything else is dropped. Either way the field then shows the
     * value. Returns whether the value changed.
     */
    private boolean commitSpinner(int node) {
        float before = sliderValue[node];
        try {
            float typed = Float.parseFloat(fieldText[node].toString().replace(',', '.'));
            if (!Float.isNaN(typed)) {
                sliderValue[node] = rounded(node, clamp(typed, sliderMin[node], sliderMax[node]));
            }
        } catch (NumberFormatException notANumber) {
            // The last value stays.
        }
        writeSpinnerText(node);
        if (sliderValue[node] == before) return false;
        changed = node;   // for a commit made inside update()
        stepped = node;   // for one made by a key, before it: update() reports it
        return true;
    }

    /** Steps a spinner, after taking what was typed: by the arrows, or by Up and Down. */
    private void stepSpinner(int node, int steps) {
        commitSpinner(node);
        float before = sliderValue[node];
        sliderValue[node] = rounded(node,
            clamp(before + steps * sliderStep[node], sliderMin[node], sliderMax[node]));
        writeSpinnerText(node);
        anchor[node] = 0;                   // the number selected, so typing replaces it
        if (sliderValue[node] != before) stepped = node;
    }

    /** Whether a character can be part of a number: what a spinner lets in. */
    private static boolean isNumberChar(int c) {
        return (c >= '0' && c <= '9') || c == '-' || c == '.' || c == ',';
    }

    /**
     * A single-line text field with no limit on its length, added to {@code parent}. Starts
     * empty.
     *
     * <p>Its buffer starts with room for {@value #UNLIMITED_START_CAPACITY} characters and
     * grows when the text outgrows it — an allocation now and then while typing past that, never
     * one per frame. Use {@link #textField(int, String, int)} where a limit makes sense, and
     * editing then allocates nothing at all.</p>
     *
     * @param parent the row or column to add it to
     * @param name   its name, which a theme or a layout file writes — {@code "fieldNotes"};
     *               see {@link AegisLayout#setNodeName}
     * @return its handle
     * @throws IllegalArgumentException for a name {@link AegisLayout#isValidNodeName} refuses
     */
    public int textField(int parent, String name) {
        return textWidget(parent, name, TEXT_FIELD, 1, Integer.MAX_VALUE, UNLIMITED_START_CAPACITY);
    }

    /**
     * A single-line text field holding at most {@code maxLength} characters, added to
     * {@code parent}. Starts empty.
     *
     * <p>Its text lives in a buffer of exactly that size, made here: typing and deleting only
     * move characters inside it, so editing allocates nothing. Characters past the limit are
     * not inserted.</p>
     *
     * <p>Asks for {@code textFieldWidth} and one line's height plus padding; a stretching
     * parent may make it wider. Text wider than the field scrolls inside it, keeping the caret
     * in view. Interactive and focusable.</p>
     *
     * @param parent    the row or column to add it to
     * @param name      its name, which a theme or a layout file writes — {@code "fieldName"};
     *                  see {@link AegisLayout#setNodeName}
     * @param maxLength the most characters it will hold
     * @return its handle
     * @throws IllegalArgumentException for a name {@link AegisLayout#isValidNodeName} refuses
     */
    public int textField(int parent, String name, int maxLength) {
        return textWidget(parent, name, TEXT_FIELD, 1, maxLength, maxLength);
    }

    /**
     * A text box — text of several lines — showing {@code visibleLines} lines, with no limit on
     * its length, added to {@code parent}. Starts empty.
     *
     * <p>Everything a {@linkplain #textField(int, String) text field} does, with these differences:
     * lines wrap at the box's width instead of scrolling sideways, and text taller than the box
     * scrolls up and down, by the caret or by the mouse wheel ({@link #scroll}). Enter breaks
     * the line, so <strong>Ctrl+Enter</strong> is what confirms — {@link #wasActivated}. Up and
     * Down move between lines keeping the caret's place across them; Home and End go to the
     * ends of the line, Ctrl+Home and Ctrl+End to the ends of the text. Pasted line breaks are
     * kept. Tab still moves focus, so the interface stays usable from the keyboard.</p>
     *
     * <p>Asks for {@code textFieldWidth} and that many lines' height plus padding.</p>
     *
     * @param parent       the row or column to add it to
     * @param name         its name, which a theme or a layout file writes — {@code "fieldDescription"};
     *                     see {@link AegisLayout#setNodeName}
     * @param visibleLines how many lines tall it is
     * @return its handle
     * @throws IllegalArgumentException for a name {@link AegisLayout#isValidNodeName} refuses
     */
    public int textBox(int parent, String name, int visibleLines) {
        return textWidget(parent, name, TEXT_BOX, visibleLines, Integer.MAX_VALUE, UNLIMITED_START_CAPACITY);
    }

    /**
     * A text box showing {@code visibleLines} lines and holding at most {@code maxLength}
     * characters, line breaks included. See {@link #textBox(int, String, int)}.
     *
     * @param parent       the row or column to add it to
     * @param name         its name; see {@link AegisLayout#setNodeName}
     * @param visibleLines how many lines tall it is
     * @param maxLength    the most characters it will hold
     * @return its handle
     * @throws IllegalArgumentException for a name {@link AegisLayout#isValidNodeName} refuses
     */
    public int textBox(int parent, String name, int visibleLines, int maxLength) {
        return textWidget(parent, name, TEXT_BOX, visibleLines, maxLength, maxLength);
    }

    /** What every text field and text box form does: its kind, its height in lines, a limit, and the room the buffer starts with. */
    private int textWidget(int parent, String name, byte textKind, int visibleLines, int maxLength, int startCapacity) {
        if (textKind == SPINNER) checkWidgetName(name, "spinner", "a spinner");
        else                     checkWidgetName(name, "field", "a text field");
        int node = namedBox(parent, name);
        tree.setInteractive(node, true);
        tree.setFocusable(node, true);

        kind[node]              = textKind;
        fieldText[node]         = new StringBuilder(startCapacity);
        fieldMaxLength[node]    = maxLength;
        caret[node]             = 0;
        anchor[node]            = 0;
        fieldScroll[node]       = 0.0f;
        this.visibleLines[node] = visibleLines;
        styleOf[node]           = null;
        fitToContent(node);
        return node;
    }

    /**
     * Refuses a widget's name that does not start with its kind — {@code buttonStop}, never
     * {@code stopButton} or {@code button} alone; see "Conventions" in §7 of
     * {@code docs/UI_FRAMEWORK_ARCHITECTURE.md}. The prefix is the word a theme dresses the
     * kind by, so the name says which entries dress it. Checked before anything is made.
     */
    private static void checkWidgetName(String name, String prefix, String what) {
        AegisLayout.checkNodeName(name);
        boolean fits = startsWithPrefix(name, prefix);
        // A menu's name may not read as one of the two kinds that share its prefix.
        if (fits && prefix.equals("menu")) fits = !startsWithPrefix(name, "menuItem") && !startsWithPrefix(name, "menuBar");
        if (!fits) {
            throw new IllegalArgumentException("\"" + name + "\" cannot name " + what + ": its name starts with \""
                + prefix + "\", then says what it is for with a capital — \"" + prefix + "Save\".");
        }
    }

    /** Whether {@code name} is {@code prefix} followed by more, starting with a capital or a digit. */
    private static boolean startsWithPrefix(String name, String prefix) {
        int n = prefix.length();
        return name.length() > n && name.startsWith(prefix)
            && ((name.charAt(n) >= 'A' && name.charAt(n) <= 'Z') || (name.charAt(n) >= '0' && name.charAt(n) <= '9'));
    }

    /**
     * The layout node every widget is made on, with its name. The name is checked before the
     * node is made, so a refused one leaves nothing half-built behind.
     */
    private int namedBox(int parent, String name) {
        AegisLayout.checkNodeName(name);
        int node = layout.box(parent);
        layout.setNodeName(node, name);

        // The theme path: the names above it now, top down, then its own.
        ArrayList<String> path = new ArrayList<>();
        path.add(name);
        for (int up = parent; up != AegisLayout.NONE; up = layout.parent(up)) {
            String above = layout.nodeName(up);
            if (above != null) path.add(0, above);
        }
        themePath[node]  = List.copyOf(path);
        themeNamed[node] = true;
        return node;
    }

    // -----------------------------------------------------------------------------------
    // Tabbed pane — step 3g-4
    // -----------------------------------------------------------------------------------

    /**
     * A tabbed pane: several panels sharing one place, one shown at a time, picked from a bar
     * of tabs above them — added to {@code parent}. Starts with no tabs; see {@link #addTab}.
     *
     * <p>It works as Swing's {@code JTabbedPane} does. Pressing a tab shows its panel. The bar
     * takes focus as one stop of Tab, and while it has it Left and Right show the tab before
     * and after, going around at the ends, and Home and End the first and last. From anywhere
     * inside the pane, Ctrl+Tab and Ctrl+PageDown show the next tab, Ctrl+Shift+Tab and
     * Ctrl+PageUp the one before.</p>
     *
     * <p>The handle is a column: the bar, then the shown panel, which takes all the room the
     * bar leaves. Size it, or make it grow, as any node. The panels not shown hang nowhere —
     * not drawn, not solved, reached by neither the pointer nor Tab — exactly as a node a
     * layout hides.</p>
     *
     * @param parent the row or column to add it to, or {@link AegisLayout#NONE}
     * @param name   its name, which a theme writes — {@code "tabsScene"}, dressed by
     *               {@code hierarchy.tabsScene.fill.selected} and the other {@code tabs}
     *               entries; see {@link AegisLayout#setNodeName}
     * @return its handle
     * @throws IllegalArgumentException for a name {@link AegisLayout#isValidNodeName} refuses
     */
    public int tabbedPane(int parent, String name) {
        checkWidgetName(name, "tabs", "a tabbed pane");
        int pane = tabbedPane(parent);
        layout.setNodeName(pane, name);
        int bar = layout.firstChild(pane);
        ArrayList<String> path = new ArrayList<>(themePath[bar]);
        path.add(name);
        themePath[bar]  = List.copyOf(path);
        themeNamed[bar] = true;
        return pane;
    }

    /**
     * A tabbed pane with no name, as a layout file's {@code "tabs"} makes: a theme dresses it
     * by the panel it is in and its kind — {@code inspector.tabs.fill} — or by the global
     * {@code tabs} values.
     */
    int tabbedPane(int parent) {
        int pane = layout.column(parent);
        layout.setAlignX(pane, AegisLayout.Align.STRETCH);
        int bar = layout.box(pane);
        tree.setInteractive(bar, true);
        tree.setFocusable(bar, true);

        ArrayList<String> path = new ArrayList<>();
        for (int up = parent; up != AegisLayout.NONE; up = layout.parent(up)) {
            String above = layout.nodeName(up);
            if (above != null) path.add(0, above);
        }
        TabSet tabs = new TabSet();
        kind[pane]   = TABBED_PANE;
        kind[bar]    = TAB_BAR;
        tabsOf[pane] = tabs;
        tabsOf[bar]  = tabs;
        themePath[bar]  = List.copyOf(path);
        themeNamed[bar] = false;
        styleOf[bar]    = null;
        fitToContent(bar);
        return pane;
    }

    /**
     * Adds a tab to a tabbed pane, after the ones it has. The first tab added is the one shown.
     *
     * <p>{@code content} is taken from wherever it hangs and belongs to the pane from now on:
     * shown, it is the pane's second child, growing to fill it; not shown, it hangs nowhere.
     * Build what goes in it before or after — it is an ordinary row or column either way.</p>
     *
     * @param pane    the tabbed pane
     * @param title   what the tab says; kept, so pass a string that does not change
     * @param content the row or column the tab shows
     * @return the tab's index, counting from 0
     */
    public int addTab(int pane, String title, int content) {
        TabSet tabs = tabsOf[pane];
        if (tabs.count == tabs.titles.length) {
            tabs.titles   = Arrays.copyOf(tabs.titles, tabs.count * 2);
            tabs.contents = Arrays.copyOf(tabs.contents, tabs.count * 2);
        }
        int index = tabs.count++;
        tabs.titles[index]   = title;
        tabs.contents[index] = content;
        layout.setGrow(content, 1.0f);
        if (index == 0) {
            tabs.selected = 0;
            layout.attach(content, pane);
        } else {
            layout.detach(content);
        }
        return index;
    }

    /**
     * How many tabs a tabbed pane has.
     *
     * @param pane the tabbed pane
     * @return the count
     */
    public int tabCount(int pane) { return tabsOf[pane].count; }

    /**
     * Which tab a tabbed pane shows.
     *
     * @param pane the tabbed pane
     * @return the tab's index, counting from 0
     */
    public int selectedTab(int pane) { return tabsOf[pane].selected; }

    /**
     * Shows another tab from code. Not reported by {@link #wasChanged}. If focus was inside
     * the panel that is put away, it goes to the bar.
     *
     * @param pane  the tabbed pane
     * @param index the tab to show, counting from 0
     * @throws IndexOutOfBoundsException for an index the pane has no tab at
     */
    public void selectTab(int pane, int index) {
        TabSet tabs = tabsOf[pane];
        if (index < 0 || index >= tabs.count) {
            throw new IndexOutOfBoundsException("Tab " + index + " of a pane with " + tabs.count + " tabs.");
        }
        if (index == tabs.selected) return;

        int column = kind[pane] == TAB_BAR ? layout.parent(pane) : pane;
        int old = tabs.contents[tabs.selected];
        for (int up = tree.focused(); up != AegisLayout.NONE; up = layout.parent(up)) {
            if (up == old) {
                tree.focus(layout.firstChild(column));
                break;
            }
        }
        layout.detach(old);
        layout.attach(tabs.contents[index], column);
        tabs.selected = index;
    }

    /** Shows another tab because the user asked, and reports it. */
    private void userSelectsTab(int bar, int index) {
        TabSet tabs = tabsOf[bar];
        if (index < 0 || index >= tabs.count || index == tabs.selected) return;
        selectTab(bar, index);
        changed = layout.parent(bar);
    }

    /** The tab under an x in a tab bar, or -1: tabs follow each other from the bar's left edge. */
    private int tabAt(int bar, float px) {
        AegisStyle style = styleOf(bar);
        TabSet tabs = tabsOf[bar];
        float x = layout.x(bar);
        for (int i = 0; i < tabs.count; i++) {
            float w = tabWidth(style, tabs.titles[i]);
            if (px >= x && px < x + w) return i;
            x += w + style.tabsGap;
        }
        return -1;
    }

    private float tabWidth(AegisStyle style, String title) {
        return (float) Math.ceil(aegis.measure(title)) + style.tabsPaddingX * 2.0f;
    }

    // -----------------------------------------------------------------------------------
    // Split pane — step 3g-5
    // -----------------------------------------------------------------------------------

    /**
     * A split pane: two sides with a divider between them, which is dragged to give one side
     * more room and the other less — added to {@code parent}. Side by side, the divider stands
     * upright; one above the other, it lies flat.
     *
     * <p>It works as Swing's {@code JSplitPane} does. The divider is dragged with the pointer;
     * from the keyboard it is a stop of Tab, and with focus the arrows along the way it moves
     * shift it {@value #DIVIDER_KEY_STEP} pixels — Shift, ten times that — and Home and End
     * take it as far as it goes either way. Pressing it leaves focus where it was, so a text
     * field being typed into keeps the keyboard while the divider is dragged. Neither side is
     * made smaller than {@value #SPLIT_MINIMUM} pixels.</p>
     *
     * <p>The handle is a row (side by side) or a column: {@code first}, the divider,
     * {@code second}. {@code first} keeps the size the divider gives it, and {@code second}
     * grows into the rest, so a larger window goes to the second side — as {@code JSplitPane}
     * gives it with its default resize weight. Size the pane, or make it grow, as any node.
     * Where the divider is is kept in memory only; a layout file writing it back is Phase 4's.</p>
     *
     * @param parent     the row or column to add it to, or {@link AegisLayout#NONE}
     * @param name       its name, which a theme writes — {@code "splitView"}, dressed by
     *                   {@code viewport.splitView.line} and the other {@code split} entries;
     *                   see {@link AegisLayout#setNodeName}
     * @param sideBySide {@code true} for left and right, {@code false} for top and bottom
     * @param first      the left or top side: any row, column or box; taken from where it hangs
     * @param second     the right or bottom side; taken likewise
     * @param location   how wide (or tall) {@code first} starts, in pixels
     * @return its handle
     * @throws IllegalArgumentException for a name {@link AegisLayout#isValidNodeName} refuses
     */
    public int splitPane(int parent, String name, boolean sideBySide, int first, int second, float location) {
        checkWidgetName(name, "split", "a split pane");
        int split = sideBySide ? layout.row(parent) : layout.column(parent);
        layout.setNodeName(split, name);
        if (sideBySide) layout.setAlignY(split, AegisLayout.Align.STRETCH);
        else            layout.setAlignX(split, AegisLayout.Align.STRETCH);

        layout.attach(first, split);
        int divider = layout.box(split);
        layout.attach(second, split);
        layout.setGrow(first, 0.0f);
        layout.setGrow(second, 1.0f);

        tree.setInteractive(divider, true);
        tree.setFocusable(divider, true);
        tree.setKeepsFocus(divider, true);
        kind[split]   = SPLIT_PANE;
        kind[divider] = DIVIDER;
        ArrayList<String> path = new ArrayList<>(pathAbove(parent));
        path.add(name);
        themePath[divider]  = List.copyOf(path);
        themeNamed[divider] = true;
        styleOf[divider]    = null;
        fitToContent(divider);
        setDividerLocation(split, location);
        return split;
    }

    /** The names above a node, top down, as a theme path starts. */
    private List<String> pathAbove(int parent) {
        ArrayList<String> path = new ArrayList<>();
        for (int up = parent; up != AegisLayout.NONE; up = layout.parent(up)) {
            String above = layout.nodeName(up);
            if (above != null) path.add(0, above);
        }
        return path;
    }

    /**
     * Where a split pane's divider is: how wide its first side is, or how tall when the sides
     * are one above the other.
     *
     * @param split the split pane
     * @return the first side's size, in pixels
     */
    public float dividerLocation(int split) {
        int first = layout.firstChild(split);
        return layout.isRow(split) ? layout.requestedWidth(first) : layout.requestedHeight(first);
    }

    /**
     * Moves a split pane's divider from code. Not reported by {@link #wasChanged}. Kept to the
     * pane's room once it has been solved.
     *
     * @param split    the split pane
     * @param location how wide (or tall) the first side is to be, in pixels
     */
    public void setDividerLocation(int split, float location) {
        int first = layout.firstChild(split);
        float to = clampDivider(split, location);
        if (layout.isRow(split)) layout.setWidth(first, to);
        else                     layout.setHeight(first, to);
    }

    /** A divider location kept between the two minimums, once the pane has a size to keep it in. */
    private float clampDivider(int split, float location) {
        float room = (layout.isRow(split) ? layout.width(split) : layout.height(split))
                   - layout.padding(split) * 2.0f;
        if (room <= 0.0f) return Math.max(0.0f, location);   // not solved yet
        int divider = layout.nextSibling(layout.firstChild(split));
        float most = room - (layout.isRow(split) ? layout.requestedWidth(divider) : layout.requestedHeight(divider))
                   - SPLIT_MINIMUM;
        return Math.max(Math.min(SPLIT_MINIMUM, most), Math.min(location, most));
    }

    /** Moves a divider because the user did, and reports it on the split pane. */
    private void userMovesDivider(int split, float location) {
        float before = dividerLocation(split);
        setDividerLocation(split, Math.round(location));
        if (dividerLocation(split) != before) changed = split;
    }

    // -----------------------------------------------------------------------------------
    // Tree — step 3g-6
    // -----------------------------------------------------------------------------------

    /**
     * A tree: items in levels, each opened to show the items under it and closed to hide them
     * — the scene's hierarchy, a folder of assets — added to {@code parent}. Starts empty; see
     * {@link #addTreeItem}.
     *
     * <p>It works as Swing's {@code JTree} does. A press on an item selects it; on its arrow,
     * or a double click, opens or closes it. It is one stop of Tab, and with focus Up and Down
     * move the selection through the items shown, Right opens the selected item or, open,
     * goes to its first child, Left closes it or, closed, goes to its parent, Home and End go
     * to the first and last, and typing a letter goes to the next item starting with it. Enter
     * and a double click activate the selected item: {@link #wasActivated} on the tree.</p>
     *
     * <p>It asks for the room its items take while shown, so it grows as they are opened: put
     * it in a scrolling column ({@link AegisLayout#setScrollable}) and the selection is kept in
     * view as it moves.</p>
     *
     * @param parent the row or column to add it to
     * @param name   its name, which a theme or a layout file writes — {@code "treeScene"}
     * @return its handle
     * @throws IllegalArgumentException for a name {@link AegisLayout#isValidNodeName} refuses
     */
    public int treeView(int parent, String name) {
        checkWidgetName(name, "tree", "a tree");
        int node = namedBox(parent, name);
        tree.setInteractive(node, true);
        tree.setFocusable(node, true);
        kind[node]    = TREE_VIEW;
        itemsOf[node] = new TreeItems();
        styleOf[node] = null;
        fitToContent(node);
        return node;
    }

    /**
     * Adds an item to a tree, after the items its parent already has. Items start closed.
     *
     * @param treeView   the tree
     * @param parentItem the item to add it under, or {@link #NO_ITEM} for the top level
     * @param text       what it says; kept, so pass a string that does not change
     * @return the item, a number counting from 0 that stays the item's for the tree's life
     */
    public int addTreeItem(int treeView, int parentItem, String text) {
        TreeItems t = itemsOf[treeView];
        if (t.count == t.text.length) {
            int n = t.count * 2;
            t.text        = Arrays.copyOf(t.text, n);
            t.parent      = Arrays.copyOf(t.parent, n);
            t.firstChild  = Arrays.copyOf(t.firstChild, n);
            t.lastChild   = Arrays.copyOf(t.lastChild, n);
            t.nextSibling = Arrays.copyOf(t.nextSibling, n);
            t.expanded    = Arrays.copyOf(t.expanded, n);
        }
        int item = t.count++;
        t.text[item]        = text;
        t.parent[item]      = parentItem;
        t.firstChild[item]  = NO_ITEM;
        t.lastChild[item]   = NO_ITEM;
        t.nextSibling[item] = NO_ITEM;
        t.expanded[item]    = false;
        if (parentItem == NO_ITEM) {
            if (t.firstTop == NO_ITEM) t.firstTop = item; else t.nextSibling[t.lastTop] = item;
            t.lastTop = item;
        } else {
            if (t.firstChild[parentItem] == NO_ITEM) t.firstChild[parentItem] = item;
            else                                     t.nextSibling[t.lastChild[parentItem]] = item;
            t.lastChild[parentItem] = item;
        }
        if (isShown(t, item)) fitToContent(treeView);
        return item;
    }

    /**
     * Opens or closes a tree item from code, showing or hiding the items under it.
     *
     * @param treeView the tree
     * @param item     the item
     * @param open     {@code true} to open it
     */
    public void setTreeItemExpanded(int treeView, int item, boolean open) {
        TreeItems t = itemsOf[treeView];
        if (t.expanded[item] == open) return;
        t.expanded[item] = open;
        // Closing an item that holds the selection moves it to the item, as JTree does.
        if (!open && t.selected != NO_ITEM && isUnderItem(t, t.selected, item)) t.selected = item;
        fitToContent(treeView);
    }

    /**
     * Whether a tree item is open.
     *
     * @param treeView the tree
     * @param item     the item
     * @return {@code true} when the items under it are shown
     */
    public boolean isTreeItemExpanded(int treeView, int item) { return itemsOf[treeView].expanded[item]; }

    /**
     * The selected item of a tree.
     *
     * @param treeView the tree
     * @return the item, or {@link #NO_ITEM}
     */
    public int selectedTreeItem(int treeView) { return itemsOf[treeView].selected; }

    /**
     * Selects a tree item from code, opening the items above it so it is shown. Not reported
     * by {@link #wasChanged}.
     *
     * @param treeView the tree
     * @param item     the item, or {@link #NO_ITEM} to select none
     */
    public void selectTreeItem(int treeView, int item) {
        TreeItems t = itemsOf[treeView];
        t.selected = item;
        boolean opened = false;
        for (int up = item == NO_ITEM ? NO_ITEM : t.parent[item]; up != NO_ITEM; up = t.parent[up]) {
            if (!t.expanded[up]) {
                t.expanded[up] = true;
                opened = true;
            }
        }
        if (opened) fitToContent(treeView);
    }

    /**
     * What a tree item says.
     *
     * @param treeView the tree
     * @param item     the item
     * @return its text
     */
    public String treeItemText(int treeView, int item) { return itemsOf[treeView].text[item]; }

    /**
     * The item a tree item was added under.
     *
     * @param treeView the tree
     * @param item     the item
     * @return its parent, or {@link #NO_ITEM} for the top level
     */
    public int treeItemParent(int treeView, int item) { return itemsOf[treeView].parent[item]; }

    /** Whether an item is shown: every item above it is open. */
    private static boolean isShown(TreeItems t, int item) {
        for (int up = t.parent[item]; up != NO_ITEM; up = t.parent[up]) {
            if (!t.expanded[up]) return false;
        }
        return true;
    }

    /** Whether {@code item} is under {@code above}, at any depth, and not {@code above} itself. */
    private static boolean isUnderItem(TreeItems t, int item, int above) {
        for (int up = t.parent[item]; up != NO_ITEM; up = t.parent[up]) {
            if (up == above) return true;
        }
        return false;
    }

    /** The item shown after this one, in the order rows are drawn, or NO_ITEM after the last. */
    private static int nextShown(TreeItems t, int item) {
        if (item == NO_ITEM) return t.firstTop;
        if (t.expanded[item] && t.firstChild[item] != NO_ITEM) return t.firstChild[item];
        while (item != NO_ITEM && t.nextSibling[item] == NO_ITEM) item = t.parent[item];
        return item == NO_ITEM ? NO_ITEM : t.nextSibling[item];
    }

    private static int depthOf(TreeItems t, int item) {
        int depth = 0;
        for (int up = t.parent[item]; up != NO_ITEM; up = t.parent[up]) depth++;
        return depth;
    }

    /** Which row an item is shown on, counting from 0, or -1 when it is not shown. */
    private static int rowOf(TreeItems t, int item) {
        int row = 0;
        for (int i = t.firstTop; i != NO_ITEM; i = nextShown(t, i), row++) {
            if (i == item) return row;
        }
        return -1;
    }

    /** The item shown on a row, or NO_ITEM past the last. */
    private static int itemOnRow(TreeItems t, int row) {
        if (row < 0) return NO_ITEM;
        int i = t.firstTop;
        for (int r = 0; r < row && i != NO_ITEM; r++) i = nextShown(t, i);
        return i;
    }

    private float treeRowHeight(AegisStyle style) { return aegis.lineHeight() + style.treePaddingY * 2.0f; }

    /** Asks for the room the shown items take: as many rows as are shown, as wide as the widest. */
    private void fitTree(int node, AegisStyle style) {
        TreeItems t = itemsOf[node];
        float widest = 0.0f;
        int rows = 0;
        for (int i = t.firstTop; i != NO_ITEM; i = nextShown(t, i), rows++) {
            float w = (depthOf(t, i) + 1) * style.treeIndent + (float) Math.ceil(aegis.measure(t.text[i]));
            widest = Math.max(widest, w);
        }
        fit(node, widest + style.treePaddingX * 2.0f, Math.max(1, rows) * treeRowHeight(style));
    }

    /** Selects an item because the user did: reports it and keeps it in view. */
    private void userSelectsItem(int node, int item) {
        TreeItems t = itemsOf[node];
        if (item == NO_ITEM || item == t.selected) return;
        t.selected = item;
        changed = node;
        scrollItemIntoView(node, item);
    }

    /** Opens or closes an item because the user did, if it has anything under it. */
    private void userTogglesItem(int node, int item) {
        TreeItems t = itemsOf[node];
        if (item == NO_ITEM || t.firstChild[item] == NO_ITEM) return;
        setTreeItemExpanded(node, item, !t.expanded[item]);
    }

    private void scrollItemIntoView(int node, int item) {
        int row = rowOf(itemsOf[node], item);
        if (row < 0) return;
        float h = treeRowHeight(styleOf(node));
        float top = layout.y(node) + row * h;
        scrollSpanIntoView(node, layout.x(node), top, layout.x(node) + layout.width(node), top + h);
    }

    /** A tree pressed: the item under the pointer selected, its arrow or a double click opening it. */
    private void pressTree(int node) {
        AegisStyle style = styleOf(node);
        TreeItems t = itemsOf[node];
        int item = itemOnRow(t, (int) Math.floor((tree.pointerY() - layout.y(node)) / treeRowHeight(style)));
        if (item == NO_ITEM) return;

        float arrowX = layout.x(node) + style.treePaddingX + depthOf(t, item) * style.treeIndent;
        boolean onArrow = tree.pointerX() >= arrowX && tree.pointerX() < arrowX + style.treeIndent;
        boolean twice = treePressTree == node && treePressItem == item && now() - treePressAt < DOUBLE_CLICK_NANOS;

        userSelectsItem(node, item);
        if (onArrow) {
            userTogglesItem(node, item);
        } else if (twice) {
            userTogglesItem(node, item);
            activated = node;
            activatedByKeyboard = false;
        }
        treePressTree = node;
        treePressItem = twice ? NO_ITEM : item;   // a third press starts again
        treePressAt   = now();
    }

    /** A focused tree's keys: the arrows, Home and End; see {@link #treeView}. */
    private void treeKeys(int node) {
        TreeItems t = itemsOf[node];
        if (t.firstTop == NO_ITEM) return;
        int sel = t.selected;
        if (sel == NO_ITEM && (pendingLines != 0 || pendingSteps != 0)) {
            userSelectsItem(node, t.firstTop);   // nothing selected yet: an arrow starts at the top
            return;
        }
        if (pendingLines != 0 && sel != NO_ITEM) {
            int rows = rowOf(t, sel) + pendingLines;
            int last = 0;
            for (int i = t.firstTop; nextShown(t, i) != NO_ITEM; i = nextShown(t, i)) last++;
            userSelectsItem(node, itemOnRow(t, Math.max(0, Math.min(last, rows))));
        }
        if (pendingJump < 0) userSelectsItem(node, t.firstTop);
        if (pendingJump > 0) {
            int last = t.firstTop;
            for (int i = t.firstTop; i != NO_ITEM; i = nextShown(t, i)) last = i;
            userSelectsItem(node, last);
        }
        sel = t.selected;
        if (sel == NO_ITEM) return;
        if (pendingSteps > 0) {
            if (t.firstChild[sel] != NO_ITEM && !t.expanded[sel]) setTreeItemExpanded(node, sel, true);
            else if (t.firstChild[sel] != NO_ITEM)                userSelectsItem(node, t.firstChild[sel]);
        } else if (pendingSteps < 0) {
            if (t.expanded[sel])                setTreeItemExpanded(node, sel, false);
            else if (t.parent[sel] != NO_ITEM)  userSelectsItem(node, t.parent[sel]);
        }
    }

    /** A letter typed in a focused tree: the next item shown after the selection that starts with it. */
    private void treeTypeAhead(int node, int codepoint) {
        TreeItems t = itemsOf[node];
        if (t.firstTop == NO_ITEM || !isAllowed(codepoint) || codepoint == ' ') return;
        char c = Character.toLowerCase((char) codepoint);
        int start = t.selected == NO_ITEM ? NO_ITEM : nextShown(t, t.selected);
        if (start == NO_ITEM) start = t.firstTop;   // past the last: around from the top
        int i = start;
        do {
            String text = t.text[i];
            if (!text.isEmpty() && Character.toLowerCase(text.charAt(0)) == c) {
                userSelectsItem(node, i);
                return;
            }
            i = nextShown(t, i);
            if (i == NO_ITEM) i = t.firstTop;
        } while (i != start);
    }

    /** Typed letters waiting for update(), for a focused tree; reused, never grown past its size. */
    private final int[] pendingTyped = new int[16];
    private int pendingTypedCount;

    // -----------------------------------------------------------------------------------
    // Overlays — step 3g-7
    // -----------------------------------------------------------------------------------

    /**
     * Shows a root above everything else, at {@code (x, y)}, as a popup: what Swing puts on
     * its popup layer. It is drawn after the tree, on its own background, at the size its
     * content asks for — or the size set on it — and moved in if it would leave the window. It
     * is given the pointer first, so nothing under it is hovered or pressed.
     *
     * <p>A press anywhere outside it closes it, as does Esc; that press still reaches what it
     * landed on. Opening a popup does not move focus. Build what it shows under a root — a
     * column made with {@link AegisLayout#NONE} as its parent — once, and show it as often as
     * needed. The widgets a combo box, a menu and a tooltip use are popups too.</p>
     *
     * @param node a root: a row, column or box hanging nowhere
     * @param x    where its left edge goes, in the pixels the layout is solved in
     * @param y    where its top edge goes
     * @throws IllegalArgumentException if {@code node} hangs under another node
     */
    public void showPopup(int node, float x, float y) {
        openOverlay(node, x, y, false, AegisLayout.NONE);
    }

    /**
     * Closes an overlay, and every overlay opened on top of it. One not open is left alone.
     *
     * @param node the overlay's root
     */
    public void closeOverlay(int node) {
        for (int i = 0; i < overlayCount; i++) {
            if (overlayNode[i] == node) {
                closeOverlaysFrom(i);
                return;
            }
        }
    }

    /**
     * Whether an overlay is open.
     *
     * @param node the overlay's root
     * @return {@code true} while it is shown
     */
    public boolean isOverlayOpen(int node) { return overlayIndex(node) >= 0; }

    private int overlayIndex(int node) {
        for (int i = 0; i < overlayCount; i++) if (overlayNode[i] == node) return i;
        return -1;
    }

    /** Opens an overlay, on top of the ones open; reopening one moves it to where it is asked. */
    private void openOverlay(int node, float x, float y, boolean modal, int owner) {
        if (layout.parent(node) != AegisLayout.NONE) {
            throw new IllegalArgumentException("An overlay is a root: build it with NONE as its parent.");
        }
        int at = overlayIndex(node);
        if (at >= 0) {
            overlayX[at] = x;
            overlayY[at] = y;
            return;
        }
        if (overlayCount == MAX_OVERLAYS) {
            throw new IllegalStateException("More than " + MAX_OVERLAYS + " overlays open at once.");
        }
        // Its background takes the pointer, so a press on it is the overlay's own and leaves
        // focus where it was, rather than reaching what is below or clearing focus.
        tree.setInteractive(node, true);
        tree.setKeepsFocus(node, true);
        overlayNode[overlayCount]  = node;
        overlayX[overlayCount]     = x;
        overlayY[overlayCount]     = y;
        overlayModal[overlayCount] = modal;
        overlayOwner[overlayCount] = owner;
        overlayFocus[overlayCount] = tree.focused();
        overlayCount++;
        tree.pushLayer(node, modal);
        solveOverlay(overlayCount - 1);
    }

    /** Closes the overlay at an index and all above it, topmost first; a modal one gives focus back. */
    private void closeOverlaysFrom(int index) {
        while (overlayCount > index) {
            int i = --overlayCount;
            int node = overlayNode[i];
            tree.removeLayer(node);
            if (overlayModal[i] || isUnder(tree.focused(), node)) tree.focus(overlayFocus[i]);
            overlayClosed(node, overlayOwner[i]);
        }
    }

    /** What closing an overlay means for the widget that opened it, if any. */
    private void overlayClosed(int node, int owner) { }

    /** Whether a node is {@code above} or inside it. */
    private boolean isUnder(int node, int above) {
        for (int up = node; up != AegisLayout.NONE; up = layout.parent(up)) if (up == above) return true;
        return false;
    }

    /** The rectangle overlays are kept inside: the window, or before the first frame the root. */
    private float boundsW() {
        int w = aegis.viewportWidth();
        return w > 0 ? w : lastRoot != AegisLayout.NONE ? layout.x(lastRoot) + layout.width(lastRoot) : 0.0f;
    }

    private float boundsH() {
        int h = aegis.viewportHeight();
        return h > 0 ? h : lastRoot != AegisLayout.NONE ? layout.y(lastRoot) + layout.height(lastRoot) : 0.0f;
    }

    /** Places an overlay at its natural size where it was asked to be, moved in to stay in the window. */
    private void solveOverlay(int i) {
        int node = overlayNode[i];
        float w = layout.naturalWidth(node), h = layout.naturalHeight(node);
        float x = overlayX[i], y = overlayY[i];
        float bw = boundsW(), bh = boundsH();
        if (bw > 0.0f) x = Math.max(0.0f, Math.min(x, bw - w));
        if (bh > 0.0f) y = Math.max(0.0f, Math.min(y, bh - h));
        layout.solve(node, x, y, w, h);
    }

    private void solveOverlays() {
        for (int i = 0; i < overlayCount; i++) solveOverlay(i);
    }

    /**
     * A press outside the popups on top closes them — each popup above the topmost overlay
     * that holds the point, down to a modal one. Remembers whose popup it closed, so that
     * widget's own press does not reopen it.
     */
    private void dismissPopups() {
        dismissedOwner = AegisLayout.NONE;
        if (!tree.pointerWentDown() || overlayCount == 0) return;
        float px = tree.pointerX(), py = tree.pointerY();
        int keep = overlayCount;
        while (keep > 0) {
            int i = keep - 1;
            if (overlayModal[i] || layout.nodeAt(overlayNode[i], px, py) != AegisLayout.NONE) break;
            keep--;
        }
        if (keep < overlayCount) {
            dismissedOwner = overlayOwner[keep];
            closeOverlaysFrom(keep);
        }
    }

    /**
     * Draws the overlays, bottom first, above everything: under a modal one, a scrim over the
     * whole window; each on its background, with a shadow; then its widgets.
     */
    private void drawOverlays() {
        solveOverlays();
        AegisStyle style = globalStyle;
        for (int i = 0; i < overlayCount; i++) {
            int node = overlayNode[i];
            if (overlayModal[i]) {
                float[] scrim = style.overlayScrim;
                aegis.addRoundedRect(0.0f, 0.0f, boundsW(), boundsH(), 0.0f, scrim[0], scrim[1], scrim[2], scrim[3]);
            }
            drawOverlayBackground(style, node);
            drawNode(node);
        }
    }

    /** What any overlay stands on: a shadow, a fill and an outline. */
    private void drawOverlayBackground(AegisStyle style, int node) {
        float x = layout.x(node), y = layout.y(node), w = layout.width(node), h = layout.height(node);
        float off = style.overlayShadowOffset, r = style.overlayRadius;
        float[] sh = style.overlayShadow, fill = style.overlayFill, border = style.overlayBorder;
        if (off > 0.0f) aegis.addRoundedRect(x + off, y + off, w, h, r, sh[0], sh[1], sh[2], sh[3]);
        aegis.addRoundedRect(x, y, w, h, r, fill[0], fill[1], fill[2], fill[3],
            border[0], border[1], border[2], border[3], 1.0f);
    }

    // -----------------------------------------------------------------------------------
    // Combo box — step 3g-9
    // -----------------------------------------------------------------------------------

    /**
     * A combo box: one item chosen from several, shown in a box whose list drops down when it
     * is pressed — added to {@code parent}. The first item starts chosen.
     *
     * <p>It works as Swing's {@code JComboBox} does, not editable. A press opens the list and
     * another closes it; pressing an item in it, or pressing the box and letting go over an
     * item, chooses it. A press anywhere else closes the list. It is a stop of Tab: with focus,
     * Up and Down choose the item before and after, Home and End the first and last, and a
     * letter the next item starting with it. Enter, Space, Alt+Down or F4 open the list; while
     * it is open the same keys move through it, Enter or Space choose, and Esc or Tab close it
     * leaving the choice as it was.</p>
     *
     * <p>Sized to its longest item, plus room for its arrow.</p>
     *
     * @param parent the row or column to add it to
     * @param name   its name, which a theme or a layout file writes — {@code "comboShading"}
     * @param items  what can be chosen, in order; kept, so pass strings that do not change
     * @return its handle
     * @throws IllegalArgumentException for a name {@link AegisLayout#isValidNodeName} refuses,
     *                                  or no items
     */
    public int comboBox(int parent, String name, String... items) {
        checkWidgetName(name, "combo", "a combo box");
        if (items.length == 0) throw new IllegalArgumentException("A combo box needs at least one item.");
        int node = namedBox(parent, name);
        tree.setInteractive(node, true);
        tree.setFocusable(node, true);
        kind[node]        = COMBO_BOX;
        comboItems[node]  = items.clone();
        comboChosen[node] = 0;

        int list = layout.box(AegisLayout.NONE);
        tree.setInteractive(list, true);
        tree.setKeepsFocus(list, true);
        kind[list]      = COMBO_LIST;
        listOwner[list] = node;
        comboList[node] = list;

        styleOf[node] = null;
        fitToContent(node);
        return node;
    }

    /**
     * Which item of a combo box is chosen.
     *
     * @param combo the combo box
     * @return the item's index, counting from 0
     */
    public int comboChosen(int combo) { return comboChosen[combo]; }

    /**
     * The text of a combo box's item.
     *
     * @param combo the combo box
     * @param index the item, counting from 0
     * @return what it says
     */
    public String comboItem(int combo, int index) { return comboItems[combo][index]; }

    /**
     * How many items a combo box has.
     *
     * @param combo the combo box
     * @return the count
     */
    public int comboItemCount(int combo) { return comboItems[combo].length; }

    /**
     * Chooses an item from code. Not reported by {@link #wasChanged}.
     *
     * @param combo the combo box
     * @param index the item, counting from 0
     * @throws IndexOutOfBoundsException for an index the box has no item at
     */
    public void setComboChosen(int combo, int index) {
        if (index < 0 || index >= comboItems[combo].length) {
            throw new IndexOutOfBoundsException("Item " + index + " of a combo box with " + comboItems[combo].length + ".");
        }
        comboChosen[combo] = index;
    }

    /**
     * Whether a combo box's list is open.
     *
     * @param combo the combo box
     * @return {@code true} while it is shown
     */
    public boolean isComboOpen(int combo) { return isOverlayOpen(comboList[combo]); }

    private float comboRowHeight(AegisStyle style) { return aegis.lineHeight() + style.comboPaddingY * 2.0f; }

    /** Drops a combo box's list down below it — or up above it, when there is no room below. */
    private void openCombo(int combo) {
        AegisStyle style = styleOf(combo);
        int list = comboList[combo];
        float h = comboItems[combo].length * comboRowHeight(style) + 4.0f;
        layout.setSize(list, layout.width(combo), h);
        float y = layout.y(combo) + layout.height(combo) + 2.0f;
        if (boundsH() > 0.0f && y + h > boundsH()) y = layout.y(combo) - h - 2.0f;
        listHighlight[list] = comboChosen[combo];
        openOverlay(list, layout.x(combo), y, false, combo);
    }

    /** Chooses an item because the user did: reports it if it is another. */
    private void userChoosesItem(int combo, int index) {
        if (index < 0 || index >= comboItems[combo].length || index == comboChosen[combo]) return;
        comboChosen[combo] = index;
        changed = combo;
    }

    /** The item of an open list under a point, or -1. */
    private int listItemAt(int list, float px, float py) {
        if (px < layout.x(list) || px >= layout.x(list) + layout.width(list)) return -1;
        int owner = listOwner[list];
        int row = (int) Math.floor((py - layout.y(list) - 2.0f) / comboRowHeight(styleOf(owner)));
        return row >= 0 && row < comboItems[owner].length ? row : -1;
    }

    /** The next item after {@code from}, going around, whose text starts with a letter; or -1. */
    private int itemStartingWith(String[] items, int from, int codepoint) {
        char c = Character.toLowerCase((char) codepoint);
        for (int k = 1; k <= items.length; k++) {
            int i = Math.floorMod(from + k, items.length);
            if (!items[i].isEmpty() && Character.toLowerCase(items[i].charAt(0)) == c) return i;
        }
        return -1;
    }

    /** A combo box's pointer and keys, and its list's, for this frame. */
    private void updateCombo(int pressed, int focused) {
        // A press on a box opens its list, or closes it — unless that same press just closed it
        // from outside the list, which counts as the closing.
        if (pressed != AegisLayout.NONE && kind[pressed] == COMBO_BOX && pressed != lastPressed) {
            if (isComboOpen(pressed))           closeOverlay(comboList[pressed]);
            else if (dismissedOwner != pressed) openCombo(pressed);
        }
        // Let go over an item, having pressed the box: chosen, as in Swing.
        if (lastPressed != AegisLayout.NONE && kind[lastPressed] == COMBO_BOX && pressed == AegisLayout.NONE
                && isComboOpen(lastPressed)) {
            int list = comboList[lastPressed];
            int item = listItemAt(list, tree.pointerX(), tree.pointerY());
            if (item >= 0 && layout.nodeAt(list, tree.pointerX(), tree.pointerY()) != AegisLayout.NONE) {
                userChoosesItem(lastPressed, item);
                closeOverlay(list);
            }
        }
        // An item clicked in an open list.
        int clicked = tree.clicked();
        if (clicked != AegisLayout.NONE && kind[clicked] == COMBO_LIST) {
            int item = listItemAt(clicked, tree.pointerX(), tree.pointerY());
            if (item >= 0) {
                userChoosesItem(listOwner[clicked], item);
                closeOverlay(clicked);
            }
        }
        // The pointer over an open list points at the item under it.
        int hovered = tree.hovered();
        if (hovered != AegisLayout.NONE && kind[hovered] == COMBO_LIST) {
            int item = listItemAt(hovered, tree.pointerX(), tree.pointerY());
            if (item >= 0) listHighlight[hovered] = item;
        }

        // A list stays open only while its box has focus or is being pressed.
        for (int i = overlayCount - 1; i >= 0; i--) {
            int list = overlayNode[i];
            if (kind[list] == COMBO_LIST && focused != listOwner[list] && pressed != listOwner[list]) {
                closeOverlay(list);
            }
        }

        if (focused == AegisLayout.NONE || kind[focused] != COMBO_BOX) return;
        int combo = focused;
        int list = comboList[combo];
        int count = comboItems[combo].length;
        if (isComboOpen(combo)) {
            int to = listHighlight[list] + pendingLines;
            if (pendingJump < 0) to = 0;
            if (pendingJump > 0) to = count - 1;
            for (int i = 0; i < pendingTypedCount; i++) {
                int found = itemStartingWith(comboItems[combo], to, pendingTyped[i]);
                if (found >= 0) to = found;
            }
            listHighlight[list] = Math.max(0, Math.min(count - 1, to));
        } else {
            if (pendingOpenList) {
                openCombo(combo);
                return;
            }
            int to = comboChosen[combo] + pendingLines;
            if (pendingJump < 0) to = 0;
            if (pendingJump > 0) to = count - 1;
            for (int i = 0; i < pendingTypedCount; i++) {
                int found = itemStartingWith(comboItems[combo], to, pendingTyped[i]);
                if (found >= 0) to = found;
            }
            userChoosesItem(combo, Math.max(0, Math.min(count - 1, to)));
        }
    }

    // -----------------------------------------------------------------------------------
    // Menus — step 3g-10
    // -----------------------------------------------------------------------------------

    /**
     * A menu bar: a row of titles, each opening a menu below it — added to {@code parent}.
     * Starts empty; see {@link #menu}.
     *
     * <p>It works as Swing's {@code JMenuBar} does. A press on a title opens its menu, and
     * another press closes it; while one is open, moving the pointer over another title opens
     * that one instead. In a menu, the pointer points at items, opening a submenu as it reaches
     * one, and a click — or letting go over an item after pressing a title — activates it and
     * closes the menus. From the keyboard, <strong>F10</strong> opens the first menu of the
     * first bar made; then Up and Down move through the items, Right opens a submenu or goes to
     * the next menu, Left closes a submenu or goes to the menu before, Enter or Space
     * activates, Esc closes one level, F10 closes them all. The titles are not stops of Tab:
     * the menus are reached by F10, as in Swing, and focus stays where it was.</p>
     *
     * @param parent the row or column to add it to
     * @param name   its name, which a theme or a layout file writes — {@code "menuBarMain"}
     * @return its handle, a row
     * @throws IllegalArgumentException for a name {@link AegisLayout#isValidNodeName} refuses
     */
    public int menuBar(int parent, String name) {
        checkWidgetName(name, "menuBar", "a menu bar");
        int bar = layout.row(parent);
        layout.setNodeName(bar, name);
        layout.setAlignY(bar, AegisLayout.Align.STRETCH);
        kind[bar] = MENU_BAR;
        if (firstMenuBar == AegisLayout.NONE) firstMenuBar = bar;
        return bar;
    }

    /**
     * A menu: a title on a menu bar, and the menu it opens. Add items to the handle returned.
     *
     * @param bar   the menu bar
     * @param name  the title's name, which a theme writes — {@code "menuFile"}; the menu's items
     *              are reached as {@code menuFile.menuItemSave}
     * @param title what the title says — {@code "File"}; kept
     * @return the menu, a column hanging nowhere until it is opened: give it to
     *         {@link #menuItem}, {@link #menuCheckItem}, {@link #subMenu} or {@link #separator}
     */
    public int menu(int bar, String name, String title) {
        checkWidgetName(name, "menu", "a menu");
        int node = namedBox(bar, name);
        tree.setInteractive(node, true);
        tree.setKeepsFocus(node, true);
        kind[node]  = MENU_TITLE;
        label[node] = title;
        styleOf[node] = null;
        fitToContent(node);

        int menu = newMenu(name, node);
        titleMenu[node] = menu;
        return menu;
    }

    /** A menu's column, hanging nowhere: what a title or a submenu item opens. */
    private int newMenu(String name, int owner) {
        int menu = layout.column(AegisLayout.NONE);
        layout.setNodeName(menu, name);
        layout.setPadding(menu, 4.0f);
        layout.setAlignX(menu, AegisLayout.Align.STRETCH);
        kind[menu]        = MENU;
        menuOwner[menu]   = owner;
        menuPointed[menu] = AegisLayout.NONE;
        return menu;
    }

    /**
     * An item in a menu. {@link #wasActivated} reports it on the frame it is chosen.
     *
     * @param menu the menu
     * @param name its name — {@code "menuItemSave"}
     * @param text what it says — {@code "Save"}; kept
     * @return its handle
     */
    public int menuItem(int menu, String name, String text) {
        return menuItem(menu, name, text, null);
    }

    /**
     * An item in a menu, with a shortcut written at its right — {@code "Ctrl+S"}. The text is
     * only shown: the key itself is the caller's to handle, as with Swing's accelerator text.
     *
     * @param menu     the menu
     * @param name     its name
     * @param text     what it says; kept
     * @param shortcut what it says at its right, or {@code null}; kept
     * @return its handle
     */
    public int menuItem(int menu, String name, String text, String shortcut) {
        checkWidgetName(name, "menuItem", "a menu item");
        return item(menu, name, text, shortcut);
    }

    /** An item of a menu, whatever its name says it is: an item, a check item, a submenu's. */
    private int item(int menu, String name, String text, String shortcut) {
        int node = namedBox(menu, name);
        tree.setInteractive(node, true);
        tree.setKeepsFocus(node, true);
        kind[node]         = MENU_ITEM;
        label[node]        = text;
        itemShortcut[node] = shortcut;
        itemSubMenu[node]  = AegisLayout.NONE;
        itemChecks[node]   = false;
        styleOf[node] = null;
        fitToContent(node);
        return node;
    }

    /**
     * An item that ticks and unticks each time it is chosen, as {@code JCheckBoxMenuItem}. Read
     * and set it with {@link #isChecked} and {@link #setChecked}; it starts unticked.
     *
     * @param menu the menu
     * @param name its name
     * @param text what it says; kept
     * @return its handle
     */
    public int menuCheckItem(int menu, String name, String text) {
        checkWidgetName(name, "menuItem", "a menu item");
        int node = item(menu, name, text, null);
        itemChecks[node] = true;
        checked[node]    = false;
        return node;
    }

    /**
     * An item that opens a menu of its own beside it.
     *
     * @param menu the menu
     * @param name the item's name
     * @param text what it says; kept
     * @return the submenu, to add items to
     */
    public int subMenu(int menu, String name, String text) {
        checkWidgetName(name, "menu", "a submenu");
        int item = item(menu, name, text, null);
        int sub = newMenu(name, item);
        itemSubMenu[item] = sub;
        fitToContent(item);
        return sub;
    }

    /** The title a top-level menu hangs from, for tests. */
    int menuTitle(int menu) { return menuOwner[menu]; }

    /** Whether a menu is open. */
    public boolean isMenuOpen(int menu) { return isOverlayOpen(menu); }

    /** The item a menu points at, or NONE; for tests. */
    int menuPointedAt(int menu) { return menuPointed[menu]; }

    /** Whether any menu is open: the keys then belong to the menus. */
    private boolean anyMenuOpen() {
        for (int i = 0; i < overlayCount; i++) if (kind[overlayNode[i]] == MENU) return true;
        return false;
    }

    private void closeAllMenus() {
        for (int i = 0; i < overlayCount; i++) {
            if (kind[overlayNode[i]] == MENU) {
                closeOverlaysFrom(i);
                return;
            }
        }
    }

    /** The topmost open menu, or NONE. */
    private int topMenu() {
        for (int i = overlayCount - 1; i >= 0; i--) if (kind[overlayNode[i]] == MENU) return overlayNode[i];
        return AegisLayout.NONE;
    }

    /** The first item of a menu from {@code from} on, going {@code step} at a time and around; separators passed by. */
    private int nextMenuItem(int menu, int from, int step) {
        int count = 0;
        for (int c = layout.firstChild(menu); c != AegisLayout.NONE; c = layout.nextSibling(c)) count++;
        if (count == 0) return AegisLayout.NONE;
        int index = -1;
        int i = 0;
        for (int c = layout.firstChild(menu); c != AegisLayout.NONE; c = layout.nextSibling(c), i++) if (c == from) index = i;
        for (int k = 1; k <= count; k++) {
            int at = from == AegisLayout.NONE ? (step > 0 ? k - 1 : count - k) : Math.floorMod(index + k * step, count);
            int c = layout.firstChild(menu);
            for (int j = 0; j < at; j++) c = layout.nextSibling(c);
            if (kind[c] == MENU_ITEM) return c;
        }
        return AegisLayout.NONE;
    }

    /** Opens a bar's menu below its title, closing any other; pointing at its first item when the keyboard opened it. */
    private void openTopMenu(int title, boolean byKeyboard) {
        closeAllMenus();
        int menu = titleMenu[title];
        openOverlay(menu, layout.x(title), layout.y(title) + layout.height(title), false, title);
        menuPointed[menu] = byKeyboard ? nextMenuItem(menu, AegisLayout.NONE, 1) : AegisLayout.NONE;
    }

    /** Opens an item's submenu beside it — to the right, or to the left with no room — closing deeper ones. */
    private void openSubMenu(int item, boolean byKeyboard) {
        int sub = itemSubMenu[item];
        int menu = layout.parent(item);
        int at = overlayIndex(menu);
        if (at >= 0 && at + 1 < overlayCount && overlayNode[at + 1] != sub) closeOverlaysFrom(at + 1);
        if (isOverlayOpen(sub)) return;
        float w = layout.naturalWidth(sub);
        float x = layout.x(menu) + layout.width(menu) - 2.0f;
        if (boundsW() > 0.0f && x + w > boundsW()) x = layout.x(menu) - w + 2.0f;
        openOverlay(sub, x, layout.y(item) - layout.padding(sub), false, item);
        menuPointed[sub] = byKeyboard ? nextMenuItem(sub, AegisLayout.NONE, 1) : AegisLayout.NONE;
    }

    /** Chooses an item: a submenu opens; anything else is activated, ticked if it ticks, and the menus close. */
    private void chooseMenuItem(int item, boolean byKeyboard) {
        if (itemSubMenu[item] != AegisLayout.NONE) {
            openSubMenu(item, byKeyboard);
            return;
        }
        if (itemChecks[item]) checked[item] = !checked[item];
        activated = item;
        activatedByKeyboard = byKeyboard;
        closeAllMenus();
    }

    /** The menu bar a menu belongs to, through the titles and items that opened it. */
    private int barOf(int menu) {
        int owner = menuOwner[menu];
        return kind[owner] == MENU_TITLE ? layout.parent(owner) : barOf(layout.parent(owner));
    }

    /** The title next to this one on its bar, going around. */
    private int titleBeside(int title, int step) {
        int bar = layout.parent(title);
        int count = 0, index = 0;
        for (int c = layout.firstChild(bar); c != AegisLayout.NONE; c = layout.nextSibling(c)) {
            if (c == title) index = count;
            count++;
        }
        int at = Math.floorMod(index + step, count);
        int c = layout.firstChild(bar);
        for (int j = 0; j < at; j++) c = layout.nextSibling(c);
        return c;
    }

    /** The menus' pointer and keys for this frame. */
    private void updateMenus(int pressed) {
        // F10 opens the first bar's first menu, or closes the menus.
        if (pendingF10) {
            if (anyMenuOpen()) closeAllMenus();
            else if (firstMenuBar != AegisLayout.NONE && layout.firstChild(firstMenuBar) != AegisLayout.NONE) {
                openTopMenu(layout.firstChild(firstMenuBar), true);
            }
        }

        // A press on a title opens its menu, or closes it — unless that same press just closed it.
        if (pressed != AegisLayout.NONE && kind[pressed] == MENU_TITLE && pressed != lastPressed) {
            if (isOverlayOpen(titleMenu[pressed]))  closeAllMenus();
            else if (dismissedOwner != pressed)     openTopMenu(pressed, false);
        }

        // Let go over an item — pressed on it, or on a title and dragged here — chooses it.
        int hovered = tree.hovered();
        if (lastPressed != AegisLayout.NONE && pressed == AegisLayout.NONE
                && (kind[lastPressed] == MENU_TITLE || kind[lastPressed] == MENU_ITEM)
                && hovered != AegisLayout.NONE && kind[hovered] == MENU_ITEM) {
            chooseMenuItem(hovered, false);
        }

        // The pointer, moving: over an item it points at it, opening its submenu or closing a
        // deeper one; over another title of a bar whose menu is open, that menu opens instead.
        if (hovered != lastMenuHover && hovered != AegisLayout.NONE) {
            if (kind[hovered] == MENU_ITEM && isOverlayOpen(layout.parent(hovered))) {
                int menu = layout.parent(hovered);
                menuPointed[menu] = hovered;
                if (itemSubMenu[hovered] != AegisLayout.NONE) {
                    openSubMenu(hovered, false);
                } else {
                    int at = overlayIndex(menu);
                    if (at + 1 < overlayCount && kind[overlayNode[at + 1]] == MENU) closeOverlaysFrom(at + 1);
                }
            } else if (kind[hovered] == MENU_TITLE && anyMenuOpen() && !isOverlayOpen(titleMenu[hovered])
                    && barOf(topMenu()) == layout.parent(hovered)) {
                openTopMenu(hovered, false);
            }
        }
        lastMenuHover = hovered;

        // The keys, while a menu is open.
        int menu = topMenu();
        if (menu != AegisLayout.NONE) {
            int pointed = menuPointed[menu];
            if (menuLines != 0) {
                int item = pointed;
                for (int k = 0; k < Math.abs(menuLines); k++) item = nextMenuItem(menu, item, Integer.signum(menuLines));
                menuPointed[menu] = item;
            }
            if (menuSteps > 0) {
                if (pointed != AegisLayout.NONE && itemSubMenu[pointed] != AegisLayout.NONE) {
                    openSubMenu(pointed, true);
                } else {
                    openTopMenu(titleBeside(menuOwner[firstOpenMenu()], +1), true);
                }
            } else if (menuSteps < 0) {
                if (kind[menuOwner[menu]] == MENU_ITEM) closeOverlay(menu);
                else openTopMenu(titleBeside(menuOwner[menu], -1), true);
            }
            if (menuEnter && pointed != AegisLayout.NONE) chooseMenuItem(pointed, true);
        }
        menuLines  = 0;
        menuSteps  = 0;
        menuEnter  = false;
        pendingF10 = false;
    }

    /** The bottom open menu: the one a bar's title opened. */
    private int firstOpenMenu() {
        for (int i = 0; i < overlayCount; i++) if (kind[overlayNode[i]] == MENU) return overlayNode[i];
        return AegisLayout.NONE;
    }

    // -----------------------------------------------------------------------------------
    // Dialog — step 3g-11
    // -----------------------------------------------------------------------------------

    /**
     * A dialog: a window inside the window, with a title, that keeps everything else from the
     * user while it is open — a modal {@code JDialog}. Made closed; add what it holds to the
     * handle, then {@link #openDialog} it as often as needed.
     *
     * <p>Open, it is drawn centred above everything, over a dimmed window, and nothing outside
     * it can be pointed at; Tab and Shift+Tab go around the widgets inside it only. Focus goes
     * to its first widget, and back to where it was when it closes. Its title bar is dragged
     * to move it; the × at its right, or Esc, closes it as cancelled
     * ({@link #wasCancelled}). With a {@linkplain #setDefaultButton default button}, Enter from
     * a field, a spinner or a slider in it activates that button.</p>
     *
     * <p>The handle is a column — the title bar first, then whatever is added — with its
     * widgets stretched across, so put buttons side by side in a row of their own.</p>
     *
     * @param name  its name, which a theme writes — {@code "dialogAbout"}, dressed by
     *              {@code dialogAbout.dialog.title.text} and {@code dialogAbout.button.fill}
     * @param title what its title bar says; kept
     * @return its handle, a column hanging nowhere
     * @throws IllegalArgumentException for a name {@link AegisLayout#isValidNodeName} refuses
     */
    public int dialog(String name, String title) {
        checkWidgetName(name, "dialog", "a dialog");
        int dialog = layout.column(AegisLayout.NONE);
        layout.setNodeName(dialog, name);
        layout.setPadding(dialog, 12.0f);
        layout.setGap(dialog, 10.0f);
        layout.setAlignX(dialog, AegisLayout.Align.STRETCH);
        kind[dialog] = DIALOG;
        defaultButton[dialog] = AegisLayout.NONE;

        int bar = layout.box(dialog);
        tree.setInteractive(bar, true);
        tree.setKeepsFocus(bar, true);
        kind[bar]       = DIALOG_TITLE;
        label[bar]      = title;
        themePath[bar]  = List.of(name);
        themeNamed[bar] = false;
        styleOf[bar]    = null;
        fitToContent(bar);
        return dialog;
    }

    /**
     * Opens a dialog, centred in the window, and gives focus to the first widget in it. One
     * already open is left where it is.
     *
     * @param dialog the dialog
     */
    public void openDialog(int dialog) {
        if (isOverlayOpen(dialog)) return;
        float w = layout.naturalWidth(dialog), h = layout.naturalHeight(dialog);
        float x = Math.max(0.0f, (boundsW() - w) * 0.5f);
        float y = Math.max(0.0f, (boundsH() - h) / 3.0f);   // a little above the middle, as windows sit
        openOverlay(dialog, Math.round(x), Math.round(y), true, AegisLayout.NONE);
        tree.focus(AegisLayout.NONE);
        tree.focusNext(dialog);
    }

    /**
     * Closes a dialog from code — its OK and Cancel buttons do this — giving focus back to
     * where it was before it opened. Not reported by {@link #wasCancelled}.
     *
     * @param dialog the dialog
     */
    public void closeDialog(int dialog) { closeOverlay(dialog); }

    /**
     * Whether a dialog is open.
     *
     * @param dialog the dialog
     * @return {@code true} while it is shown
     */
    public boolean isDialogOpen(int dialog) { return isOverlayOpen(dialog); }

    /**
     * Whether the user closed a dialog this frame without choosing — by Esc or the × of its
     * title bar.
     *
     * @param dialog the dialog
     * @return {@code true} in the frame it was cancelled
     */
    public boolean wasCancelled(int dialog) { return cancelled == dialog; }

    /**
     * The button Enter activates from a text field, a spinner or a slider in a dialog — its
     * OK, as Swing's {@code JRootPane.setDefaultButton}. Drawn with an outline of its own.
     *
     * @param dialog the dialog
     * @param button a button in it, or {@link AegisLayout#NONE} for none
     */
    public void setDefaultButton(int dialog, int button) { defaultButton[dialog] = button; }

    /** The dialog a node is in, or NONE. */
    private int dialogOf(int node) {
        for (int up = node; up != AegisLayout.NONE; up = layout.parent(up)) if (kind[up] == DIALOG) return up;
        return AegisLayout.NONE;
    }

    /** Whether a button is the default button of the dialog it is in. */
    private boolean isDefaultButton(int node) {
        int dialog = dialogOf(node);
        return dialog != AegisLayout.NONE && defaultButton[dialog] == node;
    }

    /** Where the close mark of a title bar starts: a square at its right end. */
    private float closeMarkX(int bar) { return layout.x(bar) + layout.width(bar) - layout.height(bar); }

    /** A dialog's title bar pressed: on its close mark it cancels; elsewhere it is taken hold of, and dragged. */
    private void updateDialog(int pressed) {
        cancelled = AegisLayout.NONE;
        if (pressed == AegisLayout.NONE || kind[pressed] != DIALOG_TITLE) return;
        int dialog = layout.parent(pressed);
        int at = overlayIndex(dialog);
        if (at < 0) return;
        if (pressed != lastPressed) {
            if (tree.pointerX() >= closeMarkX(pressed)) {
                closeOverlay(dialog);
                cancelled = dialog;
                return;
            }
            dialogGrabX = tree.pointerX() - layout.x(dialog);
            dialogGrabY = tree.pointerY() - layout.y(dialog);
        } else {
            overlayX[at] = Math.round(tree.pointerX() - dialogGrabX);
            overlayY[at] = Math.round(tree.pointerY() - dialogGrabY);
            solveOverlay(at);
            overlayX[at] = layout.x(dialog);   // kept where the window let it go
            overlayY[at] = layout.y(dialog);
        }
    }

    // -----------------------------------------------------------------------------------
    // Tooltips — step 3g-8
    // -----------------------------------------------------------------------------------

    /**
     * Gives a node a tooltip: a short note shown beside the pointer once it rests on the node,
     * as Swing's {@code setToolTipText}. Any node may have one — a widget, a panel, a plain
     * box; what is under the pointer and has none shows the nearest one above it.
     *
     * <p>It shows after the pointer has rested on the node for three quarters of a second, and
     * at once when moving on from another tooltip; it goes when the pointer leaves the node, a
     * button is pressed or a key is. From the keyboard, Ctrl+F1 shows the focused widget's
     * tooltip under it, and hides it again. Text wider than {@code tooltip.width} wraps.</p>
     *
     * @param node any layout node
     * @param text the note; kept, so pass a string that does not change; {@code null} for none
     */
    public void setTooltip(int node, String text) {
        tooltip[node] = text;
        if (text == null && tipShown == node) tipShown = AegisLayout.NONE;
    }

    /** The node whose tooltip is shown, or NONE; for tests. */
    int tooltipShown() { return tipShown; }

    /** The nearest node from this one up that has a tooltip, or NONE. */
    private int tipOwner(int node) {
        for (int up = node; up != AegisLayout.NONE; up = layout.parent(up)) if (tooltip[up] != null) return up;
        return AegisLayout.NONE;
    }

    /** Shows, moves on or hides the tooltip for this frame. */
    private void updateTooltip(int root) {
        long t = now();
        float px = tree.pointerX(), py = tree.pointerY();

        // The keyboard's: Ctrl+F1 on the focused widget, below it, until a key or a press.
        if (pendingTipToggle) {
            int owner = tipOwner(tree.focused());
            if (tipShown != AegisLayout.NONE && tipByKeyboard) {
                tipShown = AegisLayout.NONE;
            } else if (owner != AegisLayout.NONE) {
                int f = tree.focused();
                tipShown = owner;
                tipByKeyboard = true;
                tipX = layout.x(f);
                tipY = layout.y(f) + layout.height(f) + 4.0f;
            }
            return;
        }
        if (pendingAnyKey || tree.pointerWentDown() || tree.pointerDown()) {
            if (tipShown != AegisLayout.NONE) tipHiddenAt = t;
            tipShown = AegisLayout.NONE;
            tipCandidate = AegisLayout.NONE;
            tipPointerX = px;
            tipPointerY = py;
            return;
        }
        if (tipByKeyboard && tipShown != AegisLayout.NONE) {
            if (px == tipPointerX && py == tipPointerY) return;   // the pointer has not moved
            tipByKeyboard = false;
            tipShown = AegisLayout.NONE;
        }

        int owner = tipOwner(tree.nodeUnder(root, px, py));
        boolean moved = px != tipPointerX || py != tipPointerY;
        tipPointerX = px;
        tipPointerY = py;

        if (tipShown != AegisLayout.NONE) {
            if (owner == tipShown) return;                       // still on it: stays, where it is
            tipShown = AegisLayout.NONE;
            tipHiddenAt = t;
        }
        if (owner != tipCandidate) {
            tipCandidate = owner;
            tipRestingSince = t;
        } else if (moved) {
            tipRestingSince = t;                                 // it must rest, as in Swing
        }
        if (owner == AegisLayout.NONE) return;
        boolean warm = t - tipHiddenAt < TOOLTIP_RESHOW_NANOS;
        if (warm || t - tipRestingSince >= TOOLTIP_DELAY_NANOS) {
            tipShown = owner;
            tipByKeyboard = false;
            tipX = px;
            tipY = py + aegis.lineHeight() + 4.0f;               // below the pointer, clear of it
        }
    }

    /** The tooltip, above everything, moved in to stay in the window. */
    private void drawTooltip() {
        if (tipShown == AegisLayout.NONE) return;
        AegisStyle style = globalStyle;
        String text = tooltip[tipShown];
        float padX = style.tooltipPaddingX, padY = style.tooltipPaddingY;
        float textW = Math.min((float) Math.ceil(aegis.measure(text)), style.tooltipWidth - padX * 2.0f);
        float w = textW + padX * 2.0f;
        float h = aegis.wrappedHeight(text, textW + 1.0f) + padY * 2.0f;
        float x = tipX, y = tipY;
        float bw = boundsW(), bh = boundsH();
        if (bw > 0.0f) x = Math.max(0.0f, Math.min(x, bw - w));
        if (bh > 0.0f && y + h > bh) y = Math.max(0.0f, tipY - h - aegis.lineHeight() - 8.0f);   // above, if no room below
        x = Math.round(x);
        y = Math.round(y);
        float[] fill = style.tooltipFill, border = style.tooltipBorder, ink = style.tooltipText;
        aegis.addRoundedRect(x, y, w, h, style.tooltipRadius, fill[0], fill[1], fill[2], fill[3],
            border[0], border[1], border[2], border[3], 1.0f);
        aegis.addTextWrapped(x + padX, y + padY, textW + 1.0f, text, ink[0], ink[1], ink[2], ink[3]);
    }

    /** The bar of the nearest tabbed pane around a node — the node itself, if it is a bar — or NONE. */
    private int tabBarAround(int node) {
        for (int up = node; up != AegisLayout.NONE; up = layout.parent(up)) {
            if (kind[up] == TAB_BAR)     return up;
            if (kind[up] == TABBED_PANE) return layout.firstChild(up);
        }
        return AegisLayout.NONE;
    }

    /**
     * A text field's text. The field's own buffer, handed out for reading — measure it, draw
     * it, compare it, copy it with {@code toString()} when a {@code String} is really needed.
     * Change it only through {@link #setText}.
     *
     * @param node a text field or text box
     * @return the live text, not a copy
     */
    public CharSequence text(int node) { return fieldText[node]; }

    /**
     * Replaces a text field's text from code — to show an entity's current name, say — cut to
     * the field's limit, with the caret at the end. Not reported by {@link #wasChanged}.
     * Clears the field's undo history if it is the one being edited.
     *
     * @param node a text field or text box
     * @param text the new text; copied, not kept
     */
    public void setText(int node, CharSequence text) {
        StringBuilder buffer = fieldText[node];
        buffer.setLength(0);
        buffer.append(text, 0, Math.min(text.length(), fieldMaxLength[node]));
        caret[node]       = buffer.length();
        anchor[node]      = caret[node];
        fieldScroll[node] = 0.0f;
        if (hField == node) resetHistory();   // what code put there is not the user's to undo
    }

    /**
     * The hint a text field shows, dimmed, while it is empty. {@code null} for none.
     *
     * @param node a text field or text box
     * @param text the hint; kept, so pass a string that does not change
     */
    public void setPlaceholder(int node, String text) { label[node] = text; }

    /**
     * Where a text field's caret is: 0 before the first character, the length after the last.
     *
     * @param node a text field or text box
     * @return the caret index
     */
    public int caret(int node) { return caret[node]; }

    /**
     * Where a text field's selection begins — equal to {@link #selectionEnd} when nothing is selected.
     *
     * @param node a text field or text box
     * @return the index of the first selected character
     */
    public int selectionStart(int node) { return Math.min(anchor[node], caret[node]); }

    /**
     * Where a text field's selection ends, exclusive.
     *
     * @param node a text field or text box
     * @return the index after the last selected character
     */
    public int selectionEnd(int node) { return Math.max(anchor[node], caret[node]); }

    /**
     * Whether a node was made by this class — a button, a checkbox, a slider, a text field or
     * box, a label, a separator, a spinner, a tabbed pane or its bar, a split pane or its
     * divider, a tree, a combo box or its list, a menu bar, title, menu or item, or a dialog or
     * its title bar.
     *
     * @param node any layout handle
     * @return {@code true} for a widget, {@code false} for a plain layout node
     */
    public boolean isWidget(int node) { return kind[node] != NOT_A_WIDGET; }

    /**
     * Forgets every widget. Call it together with {@link AegisLayout#clear()} and
     * {@link AegisTree#clear()}, since handles are reused after a clear.
     */
    public void clear() {
        overlayCount = 0;
        Arrays.fill(kind, NOT_A_WIDGET);
        Arrays.fill(label, null);
        Arrays.fill(checked, false);
        Arrays.fill(fieldText, null);
        Arrays.fill(themePath, null);
        Arrays.fill(tabsOf, null);
        Arrays.fill(itemsOf, null);
        Arrays.fill(tooltip, null);
        Arrays.fill(comboItems, null);
        tipShown = AegisLayout.NONE;
        Arrays.fill(fittedWidth, 0.0f);
        Arrays.fill(fittedHeight, 0.0f);
        activated = AegisLayout.NONE;
        changed   = AegisLayout.NONE;
        edited    = AegisLayout.NONE;
        lastPressed = AegisLayout.NONE;
        resetHistory();
    }

    // -----------------------------------------------------------------------------------
    // Every frame
    // -----------------------------------------------------------------------------------

    /**
     * One key event, handed in by whoever owns input — once per key event of the frame, before
     * {@link #update}.
     *
     * <ul>
     *   <li>Tab and Shift+Tab move focus, and repeat when held.</li>
     *   <li>Ctrl+Tab and Ctrl+PageDown show the next tab of the tabbed pane the focused widget
     *       is in, Ctrl+Shift+Tab and Ctrl+PageUp the one before; see {@link #tabbedPane}.</li>
     *   <li>Enter and Space act on the focused widget: a button fires, a checkbox flips. On the
     *       press only, so holding them does not fire again and again.</li>
     *   <li>PageUp and PageDown scroll the scrolling row or column the focused widget is in, a
     *       view at a time. A scrolling pane that itself has focus also takes the arrows, a
     *       line at a time, and Home and End. See {@link AegisLayout#setScrollable}.</li>
     *   <li>While a text field has focus, the editing keys are its own and are applied at
     *       once, in the order they arrive — see {@link #editKey}. Tab and Enter still do what
     *       they do everywhere.</li>
     * </ul>
     *
     * @param key    the key, as in {@link Keys}
     * @param mods   modifier bits, as in {@link Keys#MOD_SHIFT}
     * @param repeat whether this is a repeat of a held key rather than a fresh press
     */
    public void key(int key, int mods, boolean repeat) {
        if (key == Keys.F1 && (mods & Keys.MOD_CONTROL) != 0) {
            if (!repeat) pendingTipToggle = true;
            return;
        }
        pendingAnyKey = true;

        // While a menu is open the keys are the menus', whatever has focus.
        if (anyMenuOpen()) {
            if (key == Keys.DOWN)                                menuLines++;
            else if (key == Keys.UP)                             menuLines--;
            else if (key == Keys.RIGHT)                          menuSteps++;
            else if (key == Keys.LEFT)                           menuSteps--;
            else if (!repeat && (key == Keys.ENTER || key == Keys.SPACE)) menuEnter = true;
            else if (!repeat && key == Keys.ESCAPE)              pendingEscape = true;
            else if (!repeat && key == Keys.F10)                 pendingF10 = true;
            return;
        }
        if (!repeat && key == Keys.F10 && mods == 0 && tree.topModal() == AegisLayout.NONE) {
            pendingF10 = true;
            return;
        }
        int focused = tree.focused();
        if (isText(focused) && editKey(focused, key, mods, repeat)) {
            return;
        }

        boolean shift = (mods & Keys.MOD_SHIFT) != 0;
        boolean ctrl  = (mods & Keys.MOD_CONTROL) != 0;

        boolean alt = (mods & Keys.MOD_ALT) != 0;
        if ((alt && key == Keys.DOWN) || (!repeat && key == Keys.F4 && !alt)) {
            pendingOpenList = true;
        } else if (ctrl && key == Keys.TAB) {
            pendingTabSwitch += shift ? -1 : +1;
        } else if (ctrl && (key == Keys.PAGE_DOWN || key == Keys.PAGE_UP)) {
            pendingTabSwitch += key == Keys.PAGE_DOWN ? +1 : -1;
        } else if (key == Keys.TAB) {
            pendingFocusMove = shift ? -1 : +1;
        } else if (!repeat && key == Keys.ESCAPE) {
            pendingEscape = true;
        } else if (!repeat && key == Keys.ENTER) {
            pendingEnter = true;
        } else if (!repeat && key == Keys.SPACE) {
            pendingSpace = true;
        } else if (key == Keys.RIGHT) {
            pendingSteps += shift ? LARGE_STEP : 1;    // repeats count: holding keeps moving
        } else if (key == Keys.LEFT) {
            pendingSteps -= shift ? LARGE_STEP : 1;
        } else if (!repeat && key == Keys.HOME) {
            pendingJump = -1;
        } else if (!repeat && key == Keys.END) {
            pendingJump = +1;
        } else if (key == Keys.PAGE_DOWN) {
            pendingPages++;                            // repeats count, like the arrows
        } else if (key == Keys.PAGE_UP) {
            pendingPages--;
        } else if (key == Keys.DOWN) {
            pendingLines += shift ? LARGE_STEP : 1;
        } else if (key == Keys.UP) {
            pendingLines -= shift ? LARGE_STEP : 1;
        }
    }

    /**
     * One typed character, handed in like {@link #key} — once per character event of the
     * frame, before {@link #update}.
     *
     * <p>A slider takes {@code +} and {@code -} as a step up or down. They are read as typed
     * characters rather than as keys on purpose: which physical key types {@code +} depends on
     * the keyboard layout, and the numeric keypad has its own; the character is the same on all
     * of them.</p>
     *
     * <p>A focused text field takes every character instead — {@code +}, {@code -} and space
     * included — inserting it at the caret at once, so that typing, deleting and typing again
     * in one frame come out in the order they were done.</p>
     *
     * @param codepoint the Unicode code point typed, as from {@link com.aengine.core.Input#eventCode}
     */
    public void character(int codepoint) {
        if (anyMenuOpen()) return;   // the menus take keys, not typing
        int focused = tree.focused();
        if (isText(focused)) {
            insert(focused, codepoint);
            return;
        }

        if (focused != AegisLayout.NONE && (kind[focused] == TREE_VIEW || kind[focused] == COMBO_BOX)) {
            if (codepoint != ' ' && isAllowed(codepoint) && pendingTypedCount < pendingTyped.length) {
                pendingTyped[pendingTypedCount++] = codepoint;
            }
            return;
        }
        if (codepoint == '+')      pendingSteps++;
        else if (codepoint == '-') pendingSteps--;
    }

    /**
     * One key, applied to the focused text field. Returns whether the field used it; a key it
     * does not use — Tab, Enter, anything else — goes on to be handled like any other.
     *
     * <ul>
     *   <li>Backspace and Delete remove the selection, or with none the character before and
     *       after the caret.</li>
     *   <li>Left and Right move the caret one character, Ctrl+Left and Ctrl+Right one word;
     *       Home and End to the ends. With Shift they extend the selection instead. Without
     *       Shift, Left and Right on a selection drop it and leave the caret at that side.</li>
     *   <li>Ctrl+A selects everything; Ctrl+C copies the selection, Ctrl+X cuts it, Ctrl+V
     *       pastes over it.</li>
     *   <li>Ctrl+Z undoes the last block of editing; Ctrl+Y and Ctrl+Shift+Z redo it. See
     *       {@link #beginBlock} for what a block is.</li>
     *   <li>Space is used and does nothing here: it types a space through {@link #character},
     *       and must not also act on the field the way it acts on a button.</li>
     *   <li>Esc takes focus away from the field.</li>
     * </ul>
     *
     * A text box adds: Enter breaks the line (Ctrl+Enter is left to confirm, like Enter in a
     * field); Up and Down move a line, keeping the caret's place across; Home and End go to the
     * ends of the line, and Ctrl+Home and Ctrl+End to the ends of the text.
     *
     * All but Esc repeat while held.
     */
    private boolean editKey(int node, int key, int mods, boolean repeat) {
        StringBuilder text = fieldText[node];
        int at = caret[node];
        boolean shift     = (mods & Keys.MOD_SHIFT) != 0;
        boolean ctrl      = (mods & Keys.MOD_CONTROL) != 0;
        boolean selection = anchor[node] != at;
        boolean box       = kind[node] == TEXT_BOX;

        if (kind[node] == SPINNER && (key == Keys.UP || key == Keys.DOWN)) {
            stepSpinner(node, (key == Keys.UP ? 1 : -1) * (shift ? LARGE_STEP : 1));
        } else if (box && key == Keys.ENTER && !ctrl) {
            insertChar(node, '\n');
        } else if (box && (key == Keys.UP || key == Keys.DOWN)) {
            hOpen = false;
            moveVertically(node, key == Keys.UP ? -1 : +1, shift);
        } else if (box && !ctrl && (key == Keys.HOME || key == Keys.END)) {
            hOpen = false;
            layoutLines(node);
            int line = lineOf(at);
            moveCaret(node, key == Keys.HOME ? lineStarts[line] : lineEnds[line], shift);
        } else if (key == Keys.BACKSPACE) {
            if (selection) {
                deleteSelection(node);
            } else if (at > 0) {
                // Backspaces one after another grow one block backwards: its removed text gains
                // each character at the front, and its position steps back with the caret.
                if (!continues(node, BLOCK_BACKSPACE)) beginBlock(node, BLOCK_BACKSPACE, at, at);
                prependRemoved(text.charAt(at - 1));
                text.deleteCharAt(at - 1);
                moveCaret(node, at - 1, false);
                finishEdit(node);
            }
        } else if (key == Keys.DELETE) {
            if (selection) {
                deleteSelection(node);
            } else if (at < text.length()) {
                if (!continues(node, BLOCK_DELETE)) beginBlock(node, BLOCK_DELETE, at, at);
                appendChar(text.charAt(at), true);
                text.deleteCharAt(at);
                finishEdit(node);
            }
        } else if (ctrl && key == Keys.Z) {
            if (shift) redo(node); else undo(node);
        } else if (ctrl && key == Keys.Y) {
            redo(node);
        } else if (key == Keys.LEFT) {
            int to = ctrl                  ? wordLeft(text, at)
                   : selection && !shift   ? selectionStart(node)
                   :                         Math.max(0, at - 1);
            hOpen = false;   // moving the caret ends the block being typed
            moveCaret(node, to, shift);
        } else if (key == Keys.RIGHT) {
            int to = ctrl                  ? wordRight(text, at)
                   : selection && !shift   ? selectionEnd(node)
                   :                         Math.min(text.length(), at + 1);
            hOpen = false;
            moveCaret(node, to, shift);
        } else if (key == Keys.HOME) {
            hOpen = false;
            moveCaret(node, 0, shift);
        } else if (key == Keys.END) {
            hOpen = false;
            moveCaret(node, text.length(), shift);
        } else if (ctrl && key == Keys.A) {
            hOpen = false;
            anchor[node] = 0;
            caret[node]  = text.length();
        } else if (ctrl && key == Keys.C) {
            copySelection(node);
        } else if (ctrl && key == Keys.X) {
            if (copySelection(node)) deleteSelection(node);
        } else if (ctrl && key == Keys.V) {
            paste(node);
        } else if (key == Keys.SPACE) {
            return true;
        } else if (key == Keys.ESCAPE) {
            if (kind[node] == SPINNER) writeSpinnerText(node);   // what was typed is given up
            if (!repeat) tree.focus(AegisLayout.NONE);
            return true;
        } else {
            return false;
        }

        caretShownAt = now();   // the caret shows at once wherever it went
        return true;
    }

    /**
     * Types one character into a text field at its caret, replacing the selection if there is
     * one. Refused if it is not {@linkplain #isAllowed allowed}, or the field is full.
     */
    private void insert(int node, int codepoint) {
        if (!isAllowed(codepoint)) return;
        if (kind[node] == SPINNER && !isNumberChar(codepoint)) return;
        insertChar(node, (char) codepoint);
    }

    /**
     * Puts one character at the caret, over the selection if there is one — a typed one that
     * passed the filter, or a text box's line break, which the filter would refuse as a control
     * character and Enter puts here on purpose.
     */
    private void insertChar(int node, char c) {
        StringBuilder text = fieldText[node];
        int start = selectionStart(node), end = selectionEnd(node);
        if (start == end && text.length() >= fieldMaxLength[node]) return;

        // Typing continues the block being typed; over a selection it starts a new one, which
        // holds the selection as its removed text, so one undo brings the selection back. A
        // space is its own kind of block, so a run of them groups together but typing a word
        // before or after one does not: the kind changing is what ends the block. Line breaks
        // are a kind of their own the same way.
        byte blockKind = c == ' ' ? BLOCK_SPACE : c == '\n' ? BLOCK_NEWLINE : BLOCK_TYPING;
        if (start != end || !continues(node, blockKind)) beginBlock(node, blockKind, start, end);
        text.delete(start, end);
        text.insert(start, c);
        appendChar(c, false);
        moveCaret(node, start + 1, false);
        finishEdit(node);
    }

    /** Whether a node is a text field, a text box or a spinner — whether it takes typing. */
    private boolean isText(int node) {
        return node != AegisLayout.NONE
            && (kind[node] == TEXT_FIELD || kind[node] == TEXT_BOX || kind[node] == SPINNER);
    }

    /**
     * Whether a character may go into a text field — typed or pasted, the same test.
     *
     * <ul>
     *   <li>Control characters are refused: they are not text, and among them are ESC
     *       ({@code 0x1B}) and CSI ({@code 0x9B}), which begin the escape sequences a terminal
     *       obeys. Text that reaches the log must not be able to clear the screen, hide lines
     *       or retitle the window.</li>
     *   <li>The bidirectional controls are refused — the embeddings and overrides
     *       {@code U+202A}–{@code U+202E}, the isolates {@code U+2066}–{@code U+2069} and the
     *       marks {@code U+200E}, {@code U+200F}. They are invisible and reorder what is shown,
     *       which is how {@code file\u202Etxt.exe} is made to read as {@code fileexe.txt}.</li>
     *   <li>Anything beyond 16 bits is refused, and so is half of a surrogate pair: such a
     *       character takes two {@code char}s and a caret that steps over pairs, and the atlas
     *       can draw none today.</li>
     * </ul>
     *
     * This keeps the field's own text clean; it does not make text safe to use. What the text
     * is used for — a file name, a command's argument — is checked where it is used.
     */
    private static boolean isAllowed(int codepoint) {
        if (codepoint < 0x20 || (codepoint >= 0x7F && codepoint < 0xA0)) return false;
        if (codepoint == 0x200E || codepoint == 0x200F)                  return false;
        if (codepoint >= 0x202A && codepoint <= 0x202E)                  return false;
        if (codepoint >= 0x2066 && codepoint <= 0x2069)                  return false;
        if (codepoint >= 0xD800 && codepoint <= 0xDFFF)                  return false;
        return codepoint <= 0xFFFF;
    }

    /**
     * Moves a text field's caret. With {@code extend}, the anchor stays where it is and the
     * selection grows or shrinks to the new caret; without, the anchor follows and nothing is
     * selected. Either way the caret shows at once rather than mid-blink.
     */
    private void moveCaret(int node, int to, boolean extend) {
        caret[node] = to;
        if (!extend) anchor[node] = to;
        caretShownAt = now();
        caretGoalX   = -1.0f;   // any move but Up or Down ends a vertical run; those set it again after
        revealCaret  = true;
    }

    /** Removes a text field's selected text, leaving the caret where it began. A block of its own. */
    private void deleteSelection(int node) {
        int start = selectionStart(node), end = selectionEnd(node);
        beginBlock(node, BLOCK_SINGLE, start, end);
        fieldText[node].delete(start, end);
        moveCaret(node, start, false);
        finishEdit(node);
    }

    /**
     * Puts a text field's selected text on the clipboard. Returns whether there was anything
     * to copy — cut deletes only then. Gathered in a reused builder rather than with
     * {@code substring}, so copying allocates nothing.
     */
    private boolean copySelection(int node) {
        int start = selectionStart(node), end = selectionEnd(node);
        if (start == end || clipboard == null) return false;
        clipboardScratch.setLength(0);
        clipboardScratch.append(fieldText[node], start, end);
        clipboard.setClipboard(clipboardScratch);
        return true;
    }

    /**
     * Inserts the clipboard's text at the caret, over the selection if there is one.
     *
     * <p>The clipboard is outside text and is filtered the way typing is, character by
     * character: a field is one line, so line breaks and tabs become spaces — a Windows
     * {@code \r\n} one space, not two — and whatever {@link #isAllowed} refuses is dropped. A
     * text box keeps the line breaks instead, each of them one {@code \n}. What is left is cut
     * to the room the field has.</p>
     */
    private void paste(int node) {
        if (clipboard == null) return;
        String pasted = clipboard.clipboard();   // the one allocation: GLFW hands back a String

        // A text box keeps line breaks, all made '\n'; a field, being one line, turns them into
        // spaces. Tabs become spaces in both: Tab moves focus, so no tab can be typed either.
        boolean box = kind[node] == TEXT_BOX;
        clipboardScratch.setLength(0);
        for (int i = 0; i < pasted.length(); i++) {
            char c = pasted.charAt(i);
            if (c == '\r' && i + 1 < pasted.length() && pasted.charAt(i + 1) == '\n') continue;
            if (c == '\r') c = '\n';
            if (c == '\n' && box) {
                clipboardScratch.append('\n');
                continue;
            }
            if (c == '\n' || c == '\t') c = ' ';
            if (isAllowed(c) && (kind[node] != SPINNER || isNumberChar(c))) clipboardScratch.append(c);
        }
        if (clipboardScratch.length() == 0) return;

        StringBuilder text = fieldText[node];
        int start = selectionStart(node), end = selectionEnd(node);
        int room  = fieldMaxLength[node] - (text.length() - (end - start));
        int count = Math.min(room, clipboardScratch.length());
        if (count <= 0) return;

        // One block for the whole paste, selection replaced included: one undo takes it all back.
        beginBlock(node, BLOCK_SINGLE, start, end);
        text.delete(start, end);
        text.insert(start, clipboardScratch, 0, count);
        for (int i = 0; i < count; i++) appendChar(clipboardScratch.charAt(i), false);
        moveCaret(node, start + count, false);
        finishEdit(node);
    }

    // -----------------------------------------------------------------------------------
    // Undo history
    // -----------------------------------------------------------------------------------

    /**
     * Whether the next edit of this kind continues the last block rather than starting one:
     * the block is still open, of the same kind, in this field, and not undone.
     */
    private boolean continues(int node, byte blockKind) {
        return hOpen && hField == node && hApplied == hCount && hApplied > 0
            && hKind[hApplied - 1] == blockKind;
    }

    /**
     * Starts a block of editing in a text field's history, recording the text between
     * {@code start} and {@code end} that the edit is about to remove — nothing, for typing
     * with no selection. Called before the text changes.
     *
     * <p>A block is what one Ctrl+Z undoes. Typing one character after another is one block,
     * and a run of spaces is a block of its own — one ends the other, as does any other kind
     * of edit, moving the caret, or a pause of a second; Backspaces one after another are one
     * block, and so are Deletes. A paste, a cut and a selection deleted are a block each, never
     * continued.</p>
     *
     * <p>Starting a block drops whatever could have been redone, as in any editor. At
     * {@value #HISTORY_BLOCKS} blocks, or when the characters no longer fit, the oldest are
     * dropped to make room. An edit too large for the whole buffer is not recorded, and the
     * history is emptied rather than left inconsistent: that edit cannot be undone.</p>
     */
    private void beginBlock(int node, byte blockKind, int start, int end) {
        if (hField != node) {
            resetHistory();
            hField = node;
        }
        hCount = hApplied;             // a new edit drops what could have been redone
        hOpen  = false;

        int removed = end - start;
        if (!makeRoom(removed)) {
            resetHistory();
            return;
        }
        if (hCount == HISTORY_BLOCKS) dropOldestBlock();

        int b = hCount;
        hKind[b]         = blockKind;
        hPos[b]          = start;
        hStart[b]        = historyEnd();
        hRemoved[b]      = removed;
        hInserted[b]     = 0;
        hCaretBefore[b]  = caret[node];
        hAnchorBefore[b] = anchor[node];
        fieldText[node].getChars(start, end, hChars, hStart[b]);

        hCount   = b + 1;
        hApplied = hCount;
        hOpen    = blockKind != BLOCK_SINGLE;
    }

    /**
     * Adds a character to the last block — to its inserted text, or with {@code removed} to
     * its removed text, for a Delete. Either is the last run of characters in the buffer (a
     * block that grows has nothing inserted after what it removes), so this is an append.
     */
    private void appendChar(char c, boolean removed) {
        if (hApplied == 0 || hField == AegisLayout.NONE) return;   // not being recorded
        if (!makeRoom(1)) {
            resetHistory();
            return;
        }
        int b = hApplied - 1;
        hChars[historyEnd()] = c;
        if (removed) hRemoved[b]++; else hInserted[b]++;
    }

    /**
     * Adds a character to the front of the last block's removed text, for a Backspace: what
     * it removes lies before what it removed already. The block's characters shift up by one,
     * and its position steps back.
     */
    private void prependRemoved(char c) {
        if (hApplied == 0 || hField == AegisLayout.NONE) return;
        if (!makeRoom(1)) {
            resetHistory();
            return;
        }
        int b = hApplied - 1;
        System.arraycopy(hChars, hStart[b], hChars, hStart[b] + 1, hRemoved[b]);
        hChars[hStart[b]] = c;
        hRemoved[b]++;
        hPos[b]--;
    }

    /** After an edit: records where it left caret and selection — what redo restores — and reports it. */
    private void finishEdit(int node) {
        if (hField == node && hApplied > 0) {
            hCaretAfter[hApplied - 1]  = caret[node];
            hAnchorAfter[hApplied - 1] = anchor[node];
        }
        hLastEdit = now();
        edited = node;
    }

    /** Ctrl+Z: puts back what the last block removed, and the caret and selection from before it. */
    private void undo(int node) {
        hOpen = false;
        if (hField != node || hApplied == 0) return;
        int b = --hApplied;
        StringBuilder text = fieldText[node];
        text.delete(hPos[b], hPos[b] + hInserted[b]);
        text.insert(hPos[b], hChars, hStart[b], hRemoved[b]);
        caret[node]  = hCaretBefore[b];
        anchor[node] = hAnchorBefore[b];
        revealCaret  = true;
        caretShownAt = now();
        edited = node;
    }

    /** Ctrl+Y or Ctrl+Shift+Z: does the last undone block again. */
    private void redo(int node) {
        hOpen = false;
        if (hField != node || hApplied == hCount) return;
        int b = hApplied++;
        StringBuilder text = fieldText[node];
        text.delete(hPos[b], hPos[b] + hRemoved[b]);
        text.insert(hPos[b], hChars, hStart[b] + hRemoved[b], hInserted[b]);
        caret[node]  = hCaretAfter[b];
        anchor[node] = hAnchorAfter[b];
        revealCaret  = true;
        caretShownAt = now();
        edited = node;
    }

    /** Where the next character goes in the buffer: just past the last recorded block's. */
    private int historyEnd() {
        if (hCount == 0) return 0;
        int last = hCount - 1;
        return hStart[last] + hRemoved[last] + hInserted[last];
    }

    /**
     * Drops the oldest blocks until {@code chars} more fit, keeping at least the last block
     * (the one that may be growing). Returns whether they fit.
     */
    private boolean makeRoom(int chars) {
        while (historyEnd() + chars > HISTORY_CHARS && hCount > 1) dropOldestBlock();
        return historyEnd() + chars <= HISTORY_CHARS;
    }

    /** Forgets the oldest block, sliding the others and their characters down. Allocates nothing. */
    private void dropOldestBlock() {
        int shift = hRemoved[0] + hInserted[0];
        int end   = historyEnd();
        System.arraycopy(hChars, shift, hChars, 0, end - shift);

        int n = hCount - 1;
        System.arraycopy(hKind,         1, hKind,         0, n);
        System.arraycopy(hPos,          1, hPos,          0, n);
        System.arraycopy(hStart,        1, hStart,        0, n);
        System.arraycopy(hRemoved,      1, hRemoved,      0, n);
        System.arraycopy(hInserted,     1, hInserted,     0, n);
        System.arraycopy(hCaretBefore,  1, hCaretBefore,  0, n);
        System.arraycopy(hAnchorBefore, 1, hAnchorBefore, 0, n);
        System.arraycopy(hCaretAfter,   1, hCaretAfter,   0, n);
        System.arraycopy(hAnchorAfter,  1, hAnchorAfter,  0, n);
        for (int i = 0; i < n; i++) hStart[i] -= shift;

        hCount = n;
        hApplied = Math.max(0, hApplied - 1);
    }

    /** Empties the history: focus left the field, code replaced its text, or it could not keep up. */
    private void resetHistory() {
        hField   = AegisLayout.NONE;
        hCount   = 0;
        hApplied = 0;
        hOpen    = false;
    }

    /**
     * Where Ctrl+Left goes: back over any spaces, then to the start of the word before them.
     * A word is a run of anything but whitespace.
     */
    private static int wordLeft(CharSequence text, int at) {
        while (at > 0 && Character.isWhitespace(text.charAt(at - 1)))  at--;
        while (at > 0 && !Character.isWhitespace(text.charAt(at - 1))) at--;
        return at;
    }

    /** Where Ctrl+Right goes: past the rest of this word, then past the spaces after it. */
    private static int wordRight(CharSequence text, int at) {
        int end = text.length();
        while (at < end && !Character.isWhitespace(text.charAt(at))) at++;
        while (at < end && Character.isWhitespace(text.charAt(at)))  at++;
        return at;
    }

    /**
     * Applies this frame's pointer and keys to the widgets under {@code root}. Call it once a
     * frame, after {@link AegisTree#update} and after the frame's {@link #key} calls.
     *
     * <p>This is where this frame's results are settled, so read {@link #wasActivated} and
     * {@link #wasChanged} after it. Only one widget is reported as changed per frame.</p>
     *
     * @param root the tree whose widgets receive this frame's input; Tab moves focus within it
     */
    public void update(int root) {
        lastRoot = root;
        dismissPopups();
        activated = AegisLayout.NONE;
        changed   = AegisLayout.NONE;
        activatedByKeyboard = false;

        // Text was edited as the keys arrived; this frame reports it. Typing in a spinner is
        // not a change yet — its value is, once confirmed — but a step by a key is.
        changed = edited != AegisLayout.NONE && kind[edited] == SPINNER ? AegisLayout.NONE : edited;
        edited  = AegisLayout.NONE;
        if (stepped != AegisLayout.NONE) changed = stepped;
        stepped = AegisLayout.NONE;

        // A press on a scroll bar is the bar's, not a click on what lies under it, and it
        // does not take focus away from where it was.
        dragScrollBar(root);

        // Tab stays inside a modal overlay while one is open.
        int focusRoot = tree.topModal() != AegisLayout.NONE ? tree.topModal() : root;
        if (pendingFocusMove > 0) tree.focusNext(focusRoot);
        if (pendingFocusMove < 0) tree.focusPrevious(focusRoot);

        // The pointer: a click acts on whatever widget it landed on.
        int clicked = tree.clicked();
        if (clicked != AegisLayout.NONE) act(clicked, false);

        // The keyboard: Enter or Space acts on the focused widget, whatever its kind — the
        // interface has to be usable from the keyboard alone, and Enter is the key people reach
        // for to confirm. A widget that is not a button or checkbox ignores it in act().
        int focused = tree.focused();
        if (focused != AegisLayout.NONE && (pendingEnter || pendingSpace)) {
            act(focused, true);
        }
        // Enter from a field, a spinner or a slider in a dialog activates its default button.
        if (pendingEnter) {
            int dialog = tree.topModal() != AegisLayout.NONE && kind[tree.topModal()] == DIALOG ? tree.topModal() : AegisLayout.NONE;
            boolean passesEnter = focused == AegisLayout.NONE
                || kind[focused] == TEXT_FIELD || kind[focused] == SPINNER || kind[focused] == SLIDER;
            if (dialog != AegisLayout.NONE && defaultButton[dialog] != AegisLayout.NONE && passesEnter
                    && (focused == AegisLayout.NONE || dialogOf(focused) == dialog)) {
                activated = defaultButton[dialog];
                activatedByKeyboard = true;
            }
        }

        // A slider being dragged follows the pointer every frame the press lasts, even once
        // the pointer has left it — the tree's capture keeps it pressed. The first frame of the
        // press jumps the thumb to where the track was pressed.
        int pressed = tree.pressed();
        if (pressed != AegisLayout.NONE && kind[pressed] == SLIDER) {
            setFromPointer(pressed, tree.pointerX());
        }

        // A text field being pressed puts its caret between the two characters nearest the
        // pointer. The first frame of the press drops the anchor there too; every later frame
        // moves only the caret, so dragging selects from where the press began. Dragging past
        // the field's edge keeps selecting, and the text scrolls to follow the caret. A text box
        // picks the line under the pointer first, and dragging selects across lines.
        updateCombo(pressed, focused);
        updateMenus(pressed);
        updateDialog(pressed);

        // A tree pressed selects, opens and closes, on the press.
        if (pressed != AegisLayout.NONE && kind[pressed] == TREE_VIEW && pressed != lastPressed) {
            pressTree(pressed);
        }
        if (focused != AegisLayout.NONE && kind[focused] == TREE_VIEW) {
            treeKeys(focused);
            for (int i = 0; i < pendingTypedCount; i++) treeTypeAhead(focused, pendingTyped[i]);
        }
        pendingTypedCount = 0;
        pendingOpenList   = false;

        // A divider pressed follows the pointer, every frame the press lasts.
        if (pressed != AegisLayout.NONE && kind[pressed] == DIVIDER) {
            int split = layout.parent(pressed);
            boolean across = layout.isRow(split);
            float along = across ? tree.pointerX() : tree.pointerY();
            if (pressed != lastPressed) {
                dividerGrab = along - (across ? layout.x(pressed) : layout.y(pressed));
            } else {
                float start = (across ? layout.x(split) : layout.y(split)) + layout.padding(split);
                userMovesDivider(split, along - dividerGrab - start);
            }
        }

        // A focused divider takes the arrows along the way it moves, and Home and End.
        if (focused != AegisLayout.NONE && kind[focused] == DIVIDER) {
            int split = layout.parent(focused);
            int steps = layout.isRow(split) ? pendingSteps : pendingLines;
            float to = dividerLocation(split) + steps * DIVIDER_KEY_STEP;
            if (pendingJump < 0) to = 0.0f;
            if (pendingJump > 0) to = Float.MAX_VALUE;
            if (steps != 0 || pendingJump != 0) userMovesDivider(split, to);
        }

        // A tab pressed is shown at once, on the press rather than the release, as in Swing.
        if (pressed != AegisLayout.NONE && kind[pressed] == TAB_BAR && pressed != lastPressed) {
            userSelectsTab(pressed, tabAt(pressed, tree.pointerX()));
        }

        // Ctrl+Tab from anywhere inside a tabbed pane; the arrows, Home and End on its bar.
        int bar = tabBarAround(focused);
        if (bar != AegisLayout.NONE) {
            TabSet tabs = tabsOf[bar];
            int to = tabs.selected;
            if (tabs.count > 0) to = Math.floorMod(to + pendingTabSwitch, tabs.count);
            if (focused == bar && tabs.count > 0) {
                to = Math.floorMod(to + pendingSteps, tabs.count);
                if (pendingJump < 0) to = 0;
                if (pendingJump > 0) to = tabs.count - 1;
            }
            userSelectsTab(bar, to);
            focused = tree.focused();   // it went to the bar, if it was in the panel put away
        }

        // A spinner pressed on its arrows steps instead: once at the press, and again and
        // again while held.
        boolean onArrows = false;
        if (pressed != AegisLayout.NONE && kind[pressed] == SPINNER) {
            if (pressed != lastPressed) {
                spinHeld = spinnerArrowAt(pressed, tree.pointerX(), tree.pointerY());
                if (spinHeld != 0) {
                    stepSpinner(pressed, spinHeld);
                    spinNextAt = now() + SPIN_DELAY_NANOS;
                }
            } else if (spinHeld != 0 && now() >= spinNextAt) {
                stepSpinner(pressed, spinHeld);
                spinNextAt = now() + SPIN_REPEAT_NANOS;
            }
            onArrows = spinHeld != 0;
            if (stepped != AegisLayout.NONE) changed = stepped;
            stepped = AegisLayout.NONE;
        } else {
            spinHeld = 0;
        }

        if (isText(pressed) && !onArrows) {
            boolean freshPress = pressed != lastPressed;
            int at = caretAt(pressed, tree.pointerX(), tree.pointerY());
            if (freshPress || at != caret[pressed]) {
                hOpen = false;   // the caret moved: the block being typed is over
                moveCaret(pressed, at, !freshPress);
            }
        }
        lastPressed = pressed;

        // A field that has just gained focus shows its caret at once rather than mid-blink —
        // and whatever gained focus is scrolled into view, so Tab never lands somewhere the
        // user cannot see.
        if (focused != lastFocused) {
            // A spinner that loses focus takes what was typed in it; one reached by Tab has its
            // number selected, so typing replaces it.
            if (lastFocused != AegisLayout.NONE && kind[lastFocused] == SPINNER) commitSpinner(lastFocused);
            if (focused != AegisLayout.NONE && kind[focused] == SPINNER && pendingFocusMove != 0) {
                anchor[focused] = 0;
                caret[focused]  = fieldText[focused].length();
            }
            caretShownAt = now();
            lastFocused  = focused;
            if (focused != AegisLayout.NONE) scrollIntoView(focused);
            if (focused != AegisLayout.NONE && kind[focused] == TREE_VIEW && itemsOf[focused].selected != NO_ITEM) {
                scrollItemIntoView(focused, itemsOf[focused].selected);   // the selection, rather than the top
            }
        }

        // The wheel: a text box under the pointer scrolls its lines; otherwise the nearest
        // scrolling row or column under it that has somewhere to go.
        if (pendingWheel != 0.0f) wheel(root, pendingWheel);
        scrollByKeys(focused);

        // The undo history belongs to the field with focus: once focus is elsewhere it is gone.
        // A second without an edit ends the block being typed, so the next word is its own undo.
        if (hField != AegisLayout.NONE && hField != focused) resetHistory();
        if (hOpen && now() - hLastEdit > HISTORY_PAUSE_NANOS) hOpen = false;

        // A focused slider takes the arrows, + and -, Home and End.
        if (focused != AegisLayout.NONE && kind[focused] == SLIDER) {
            float before = sliderValue[focused];
            float after  = before + pendingSteps * sliderStep[focused];
            if (pendingJump < 0) after = sliderMin[focused];
            if (pendingJump > 0) after = sliderMax[focused];
            after = clamp(after, sliderMin[focused], sliderMax[focused]);
            if (after != before) {
                sliderValue[focused] = after;
                changed = focused;
            }
        }

        pendingFocusMove = 0;
        pendingEnter     = false;
        pendingSpace     = false;
        pendingSteps     = 0;
        pendingJump      = 0;
        pendingWheel     = 0.0f;
        pendingPages     = 0;
        pendingLines     = 0;
        pendingTabSwitch = 0;
        stepped          = AegisLayout.NONE;

        // Esc, outside a text field, closes the topmost overlay — a dialog as cancelled.
        if (pendingEscape && overlayCount > 0) {
            int top = overlayNode[overlayCount - 1];
            closeOverlaysFrom(overlayCount - 1);
            if (kind[top] == DIALOG) cancelled = top;
        }
        pendingEscape = false;

        updateTooltip(root);
        pendingTipToggle = false;
        pendingAnyKey    = false;
        solveOverlays();
    }

    /** Moves a slider to the value under the pointer's x, and reports a change if there was one. */
    private void setFromPointer(int node, float pointerX) {
        float left  = sliderTrackLeft(node);
        float right = sliderTrackRight(node);
        float t = right > left ? (pointerX - left) / (right - left) : 0.0f;
        float value = sliderMin[node] + clamp(t, 0.0f, 1.0f) * (sliderMax[node] - sliderMin[node]);
        if (value != sliderValue[node]) {
            sliderValue[node] = value;
            changed = node;
        }
    }

    /**
     * Where the thumb's centre sits at the minimum. The thumb never overhangs the widget, so
     * its centre travels between half a thumb in from the left and half a thumb in from where
     * the value text begins.
     */
    private float sliderTrackLeft(int node) {
        return layout.x(node) + styleOf(node).sliderThumbSize * 0.5f;
    }

    /** Where the thumb's centre sits at the maximum. */
    private float sliderTrackRight(int node) {
        return layout.x(node) + layout.width(node)
             - styleOf(node).sliderValueWidth - styleOf(node).sliderGap - styleOf(node).sliderThumbSize * 0.5f;
    }

    /** Where a text field's text starts, before scrolling: inside its left padding. */
    private float fieldTextLeft(int node) {
        return layout.x(node) + styleOf(node).textFieldPaddingX;
    }

    /** How wide the part of a text field that shows text is. A spinner's arrows take their part of it. */
    private float fieldTextWidth(int node) {
        float arrows = kind[node] == SPINNER ? styleOf(node).spinnerButtonWidth : 0.0f;
        return Math.max(0.0f, layout.width(node) - styleOf(node).textFieldPaddingX * 2.0f - arrows);
    }

    /** Which of a spinner's arrows a point is on: +1 the upper, -1 the lower, 0 neither. */
    private int spinnerArrowAt(int node, float px, float py) {
        float x = layout.x(node), y = layout.y(node), w = layout.width(node), h = layout.height(node);
        if (px < x + w - styleOf(node).spinnerButtonWidth || px >= x + w || py < y || py >= y + h) return 0;
        return py < y + h * 0.5f ? +1 : -1;
    }

    /** How tall the part of a text box that shows text is. */
    private float fieldTextHeight(int node) {
        return Math.max(0.0f, layout.height(node) - styleOf(node).textFieldPaddingY * 2.0f);
    }

    /** A text field's caret offset, from the start of its one line. */
    private float caretOffset(int node, int index) {
        StringBuilder text = fieldText[node];
        return offsetInLine(text, 0, text.length(), index);
    }

    /**
     * How far from the start of the line {@code [from, to)} the caret sits when it is before
     * character {@code index} — exactly where that character is drawn.
     *
     * <p>A measured range kerns only inside itself, but the drawn character {@code index} is
     * also moved by its kerning against the one before it; that pair is added, so the caret sits
     * against the glyph and not a kerning's width away from it.</p>
     */
    private float offsetInLine(CharSequence text, int from, int to, int index) {
        AegisFont font = aegis.font();
        float offset = font.measure(text, from, index);
        if (index > from && index < to) {
            offset += font.kerning(text.charAt(index - 1), text.charAt(index));
        }
        return offset;
    }

    /** The caret position nearest a pointer: in a field by x alone, in a box by the line under y first. */
    private int caretAt(int node, float pointerX, float pointerY) {
        StringBuilder text = fieldText[node];
        float localX = pointerX - fieldTextLeft(node);
        if (kind[node] != TEXT_BOX) {
            return indexInLine(text, 0, text.length(), localX + fieldScroll[node]);
        }

        layoutLines(node);
        float localY = pointerY - (layout.y(node) + styleOf(node).textFieldPaddingY) + fieldScroll[node];
        int line = (int) Math.floor(localY / aegis.lineHeight());
        line = Math.max(0, Math.min(lineCount - 1, line));
        return indexInLine(text, lineStarts[line], lineEnds[line], localX);
    }

    /**
     * The caret position in the line {@code [from, to)} nearest an x measured from the line's
     * start: the boundary between the two characters it falls between, going to whichever side
     * of a character's middle it is on. Walks the line once, adding advances and kerning the
     * way drawing does.
     */
    private int indexInLine(CharSequence text, int from, int to, float x) {
        AegisFont font = aegis.font();
        float pen = 0.0f;
        for (int i = from; i < to; i++) {
            float start   = pen + (i > from ? font.kerning(text.charAt(i - 1), text.charAt(i)) : 0.0f);
            float advance = font.measure(text, i, i + 1);
            if (x < start + advance * 0.5f) return i;
            pen = start + advance;
        }
        return to;
    }

    /**
     * Scrolls a text field so its caret is in view — only when the caret has moved, so as not
     * to undo a scroll the user made — and always so that no empty space is left at the right
     * while text is hidden at the left: deleting from the end of a long text pulls it back in.
     */
    private void scrollField(int node, boolean reveal) {
        float visible = fieldTextWidth(node) - styleOf(node).textFieldCaretWidth;
        float textW   = aegis.measure(fieldText[node]);

        float scroll = fieldScroll[node];
        if (reveal) {
            float caretX = caretOffset(node, caret[node]);
            if (caretX - scroll > visible) scroll = caretX - visible;
            if (caretX - scroll < 0.0f)    scroll = caretX;
        }
        scroll = Math.min(scroll, Math.max(0.0f, textW - visible));
        fieldScroll[node] = Math.max(0.0f, scroll);
    }

    /**
     * The same for a text box, up and down: the caret's line brought into view when it has
     * moved, and the scroll kept between the top and the last line resting on the bottom.
     * Expects {@link #layoutLines} to have been called for this box.
     */
    private void scrollBox(int node, boolean reveal) {
        float lh      = aegis.lineHeight();
        float visible = fieldTextHeight(node);

        float scroll = fieldScroll[node];
        if (reveal) {
            float caretY = lineOf(caret[node]) * lh;
            if (caretY + lh - scroll > visible) scroll = caretY + lh - visible;
            if (caretY - scroll < 0.0f)         scroll = caretY;
        }
        scroll = Math.min(scroll, Math.max(0.0f, lineCount * lh - visible));
        fieldScroll[node] = Math.max(0.0f, scroll);
    }

    /**
     * Wraps a text box's text to its width into {@link #lineStarts} and {@link #lineEnds}.
     *
     * <p>Two things are added to what {@link AegisFont#wrap} gives. The arrays double and it
     * wraps again if they filled — the text may have more lines. And an empty last line is
     * added where the caret can stand but the wrap reports nothing: in an empty box, after a
     * line break at the very end, or after a space the last line broke at.</p>
     */
    private void layoutLines(int node) {
        StringBuilder text = fieldText[node];
        float width = fieldTextWidth(node) - styleOf(node).textFieldCaretWidth;
        AegisFont font = aegis.font();

        lineCount = font.wrap(text, width, lineStarts, lineEnds);
        while (lineCount >= lineStarts.length - 1) {
            lineStarts = new int[lineStarts.length * 2];
            lineEnds   = new int[lineEnds.length * 2];
            lineCount  = font.wrap(text, width, lineStarts, lineEnds);
        }

        int length = text.length();
        if (lineCount == 0 || lineEnds[lineCount - 1] < length) {
            lineStarts[lineCount] = length;
            lineEnds[lineCount]   = length;
            lineCount++;
        }
    }

    /**
     * Which of the lines last laid out holds a caret position: the last line starting at or
     * before it. A position where one line ends and the next begins — a word broken in the
     * middle — belongs to the next line, where the character after it is drawn.
     */
    private int lineOf(int index) {
        int line = 0;
        while (line + 1 < lineCount && lineStarts[line + 1] <= index) line++;
        return line;
    }

    /**
     * Up and Down in a text box: the caret goes to the line above or below, as near as it can
     * to the same place across. The first press of a run remembers that place; later ones aim
     * at it again, so a short line on the way does not pull the caret left for good. Up on the
     * first line goes to the start of the text, Down on the last to its end.
     */
    private void moveVertically(int node, int direction, boolean extend) {
        StringBuilder text = fieldText[node];
        layoutLines(node);
        int line = lineOf(caret[node]);
        float goal = caretGoalX >= 0.0f
            ? caretGoalX
            : offsetInLine(text, lineStarts[line], lineEnds[line], caret[node]);

        int target = line + direction;
        int to;
        if (target < 0)               to = 0;
        else if (target >= lineCount) to = text.length();
        else                          to = indexInLine(text, lineStarts[target], lineEnds[target], goal);

        moveCaret(node, to, extend);
        caretGoalX = goal;   // after moveCaret, which clears it
    }

    /**
     * The mouse wheel — {@code dy} as the wheel reports it, positive away from the user, which
     * scrolls up. Three lines a notch. Handed in by whoever owns input, like {@link #key}, and
     * applied by {@link #update}: a text box under the pointer scrolls its lines; otherwise the
     * nearest scrolling row or column under the pointer that can move scrolls
     * ({@link AegisLayout#setScrollable}).
     *
     * @param dy the wheel's vertical offset, as from {@link com.aengine.core.Input#eventScrollY}
     */
    public void scroll(float dy) {
        pendingWheel += dy;
    }

    /** What the wheel scrolls; see {@link #scroll}. */
    private void wheel(int root, float dy) {
        float by = dy * aegis.lineHeight() * 3.0f;
        int hovered = tree.hovered();
        if (hovered != AegisLayout.NONE && kind[hovered] == TEXT_BOX) {
            fieldScroll[hovered] -= by;   // kept in range when drawn
            return;
        }
        for (int up = tree.nodeUnder(root, tree.pointerX(), tree.pointerY()); up != AegisLayout.NONE;
             up = layout.parent(up)) {
            if (layout.isScrollable(up) && layout.scrollRange(up) > 0.0f) {
                layout.setScrollOffset(up, layout.scrollOffset(up) - by);   // kept in range by the solve
                return;
            }
        }
    }

    /**
     * Scrolls every scrolling row or column around a node just far enough to show it whole —
     * or, if it is longer than the room, its start. Read from this frame's rectangles; the
     * next solve moves it.
     */
    private void scrollIntoView(int node) {
        scrollSpanIntoView(node, layout.x(node), layout.y(node),
            layout.x(node) + layout.width(node), layout.y(node) + layout.height(node));
    }

    /**
     * Scrolls every scrolling row or column around a node just far enough to show a part of
     * it — the rectangle from (x0, y0) to (x1, y1) — whole, or its start if it is longer than
     * the room. A tree uses it for one row.
     */
    private void scrollSpanIntoView(int node, float x0, float y0, float x1, float y1) {
        for (int up = layout.parent(node); up != AegisLayout.NONE; up = layout.parent(up)) {
            if (!layout.isScrollable(up)) continue;
            boolean across = layout.isRow(up);
            float pad       = layout.padding(up);
            float viewStart = (across ? layout.x(up) : layout.y(up)) + pad;
            float viewEnd   = (across ? layout.x(up) + layout.width(up) : layout.y(up) + layout.height(up)) - pad;
            float start     = across ? x0 : y0;
            float end       = across ? x1 : y1;
            float offset    = layout.scrollOffset(up);
            if (start < viewStart)  offset -= viewStart - start;
            else if (end > viewEnd) offset += Math.min(end - viewEnd, start - viewStart);
            layout.setScrollOffset(up, offset);
        }
    }

    /**
     * PageUp and PageDown scroll the nearest scrolling row or column around the focused widget
     * that can move, by a view less one line, so the last line before stays in sight as the
     * first after. A scrolling pane that has focus itself — one made focusable because nothing
     * in it is — also moves a line by the arrows along its direction, and to its ends by Home
     * and End.
     */
    private void scrollByKeys(int focused) {
        if (focused == AegisLayout.NONE) return;
        float line = aegis.lineHeight();

        if (layout.isContainer(focused) && layout.isScrollable(focused)) {
            int lines = layout.isRow(focused) ? pendingSteps : pendingLines;
            float to = layout.scrollOffset(focused) + lines * line;
            if (pendingJump < 0) to = 0.0f;
            if (pendingJump > 0) to = layout.scrollRange(focused);
            layout.setScrollOffset(focused, to);
        }

        if (pendingPages == 0) return;
        for (int up = focused; up != AegisLayout.NONE; up = layout.parent(up)) {
            if (!layout.isContainer(up) || !layout.isScrollable(up) || layout.scrollRange(up) <= 0.0f) continue;
            float page = Math.max(line, scrollView(up) - line);
            layout.setScrollOffset(up, layout.scrollOffset(up) + pendingPages * page);   // kept in range by the solve
            return;
        }
    }

    // -----------------------------------------------------------------------------------
    // The scroll bar — step 3g-2b
    // -----------------------------------------------------------------------------------

    /** How long the part of a scrolling row or column that shows content is: its length less its padding. */
    private float scrollView(int node) {
        return (layout.isRow(node) ? layout.width(node) : layout.height(node)) - layout.padding(node) * 2.0f;
    }

    /** Where the view starts along the way a row or column scrolls: its edge plus its padding. */
    private float scrollViewStart(int node) {
        return (layout.isRow(node) ? layout.x(node) : layout.y(node)) + layout.padding(node);
    }

    /** How long a scroll bar is: the share of the content in view, and never too short to take hold of. */
    private float barLength(int node) {
        float view = scrollView(node);
        float len  = Math.max(globalStyle.scrollbarWidth * 4.0f, view * view / layout.scrollContent(node));
        return Math.min(len, view);
    }

    /** Where a scroll bar starts, along the way its pane scrolls. */
    private float barStart(int node) {
        float range = layout.scrollRange(node);
        float t = range > 0.0f ? layout.scrollOffset(node) / range : 0.0f;
        return scrollViewStart(node) + (scrollView(node) - barLength(node)) * t;
    }

    /**
     * Whether a point is in the strip a pane's scroll bar runs in: along its far edge — the
     * right of a column, the bottom of a row — as wide as the padding there, or as the bar
     * where the padding is narrower. The whole strip takes hold, not just the thin bar drawn
     * in it, so it is not a four-pixel target.
     */
    private boolean inBarStrip(int node, float px, float py) {
        if (layout.scrollRange(node) <= 0.0f) return false;
        float strip = Math.max(layout.padding(node), globalStyle.scrollbarWidth);
        float x = layout.x(node), y = layout.y(node), w = layout.width(node), h = layout.height(node);
        if (px < x || px >= x + w || py < y || py >= y + h) return false;
        return layout.isRow(node) ? py >= y + h - strip : px >= x + w - strip;
    }

    /** The scrolling row or column whose bar strip is under a point, innermost first, or NONE. */
    private int barAt(int root, float px, float py) {
        for (int up = tree.nodeUnder(root, px, py); up != AegisLayout.NONE; up = layout.parent(up)) {
            if (layout.isContainer(up) && layout.isScrollable(up) && inBarStrip(up, px, py)) return up;
        }
        return AegisLayout.NONE;
    }

    /**
     * The scroll bar under the pointer, pressed and dragged — as a {@code JScrollBar} is: a
     * press on the bar takes hold of it, and it then follows the pointer, wherever the pointer
     * goes, until the button comes up; a press in the strip beside the bar scrolls one view
     * towards the pointer.
     *
     * <p>The pane takes the pointer's capture for as long as the drag lasts, so nothing under
     * the pointer lights up or is clicked, and focus stays where it was before the press: a
     * scroll bar is not something focus goes to.</p>
     */
    private void dragScrollBar(int root) {
        if (tree.pointerWentDown()) {
            int pane = barAt(root, tree.pointerX(), tree.pointerY());
            if (pane != AegisLayout.NONE) {
                float along = layout.isRow(pane) ? tree.pointerX() : tree.pointerY();
                float start = barStart(pane), length = barLength(pane);
                tree.capture(pane);
                tree.focus(lastFocused);
                if (along >= start && along < start + length) {
                    barDragged = pane;
                    barGrab    = along - start;
                } else {
                    float page = Math.max(aegis.lineHeight(), scrollView(pane) - aegis.lineHeight());
                    layout.setScrollOffset(pane, layout.scrollOffset(pane) + (along < start ? -page : page));
                }
            }
        }
        if (barDragged == AegisLayout.NONE) return;
        if (!tree.pointerDown()) {
            barDragged = AegisLayout.NONE;
            return;
        }
        int pane = barDragged;
        float along  = layout.isRow(pane) ? tree.pointerX() : tree.pointerY();
        float travel = scrollView(pane) - barLength(pane);
        if (travel <= 0.0f) return;
        float t = (along - barGrab - scrollViewStart(pane)) / travel;
        layout.setScrollOffset(pane, clamp(t, 0.0f, 1.0f) * layout.scrollRange(pane));
    }

    private static float clamp(float value, float min, float max) {
        return Math.max(min, Math.min(max, value));
    }

    /**
     * What activating a widget means for its kind: a button fires, a checkbox flips, a text
     * field is confirmed — by Enter only, since a click on a field is for placing the caret —
     * and so is a text box, by Ctrl+Enter, the only Enter it does not take as a line break.
     */
    private void act(int node, boolean byKeyboard) {
        switch (kind[node]) {
            case BUTTON -> {
                activated = node;
                activatedByKeyboard = byKeyboard;
            }
            case CHECKBOX -> {
                checked[node] = !checked[node];
                changed = node;
            }
            case TEXT_FIELD, TEXT_BOX -> {
                if (byKeyboard) {
                    activated = node;
                    activatedByKeyboard = true;
                }
            }
            case MENU_ITEM -> { }   // chosen in updateMenus(), on the release
            case COMBO_BOX -> {
                if (!byKeyboard) break;
                int list = comboList[node];
                if (isOverlayOpen(list)) {
                    userChoosesItem(node, listHighlight[list]);
                    closeOverlay(list);
                } else {
                    openCombo(node);
                }
            }
            case TREE_VIEW -> {
                if (byKeyboard && itemsOf[node].selected != NO_ITEM) {
                    activated = node;
                    activatedByKeyboard = true;
                }
            }
            case SPINNER -> {
                if (byKeyboard) {
                    commitSpinner(node);
                    anchor[node] = 0;   // confirmed, and selected for the next number
                    activated = node;
                    activatedByKeyboard = true;
                }
            }
            default -> { }   // not a widget: a click on a plain node does nothing here
        }
    }

    /**
     * Whether this widget was activated this frame — a button by a click, Enter or Space; a
     * text field by Enter, the user confirming what they typed; a text box by Ctrl+Enter.
     *
     * @param node the widget
     * @return {@code true} in the frame it was activated
     */
    public boolean wasActivated(int node) { return activated == node; }

    /**
     * Whether the user changed this widget this frame — for a checkbox, ticked or unticked it
     * by a click, Enter or Space; for a slider, moved it by dragging or by a key; for a text
     * field, typed or deleted in it. Changes made from code, with {@link #setChecked},
     * {@link #setSliderValue} or {@link #setText}, do not count.
     *
     * @param node the widget
     * @return {@code true} in the frame the user changed it
     */
    public boolean wasChanged(int node) { return changed == node; }

    /**
     * Whether this frame's activation came from the keyboard rather than the pointer. For
     * showing it, as the scaffolding does; code acting on a button should not need to care.
     *
     * @return {@code true} if Enter or Space caused this frame's activation
     */
    public boolean activatedByKeyboard() { return activatedByKeyboard; }

    /**
     * Draws every widget under {@code root}, in tree order, each as its kind and state say.
     *
     * <p>Only widgets: panes and other plain nodes are the caller's to draw, first, so the
     * widgets sit on top of them. Then the open overlays, above everything — so call it last
     * of what the interface draws.</p>
     *
     * @param root the top of the tree to draw
     */
    public void draw(int root) {
        drawNode(root);
        drawOverlays();
        drawTooltip();
    }

    /** One node and everything under it. */
    private void drawNode(int root) {
        if (kind[root] == BUTTON)   drawButton(root);
        if (kind[root] == CHECKBOX) drawCheckbox(root);
        if (kind[root] == SLIDER)   drawSlider(root);
        if (kind[root] == TEXT_FIELD) drawTextField(root);
        if (kind[root] == TEXT_BOX)   drawTextBox(root);
        if (kind[root] == LABEL)      drawLabel(root);
        if (kind[root] == SEPARATOR)  drawSeparator(root);
        if (kind[root] == SPINNER)    drawSpinner(root);
        if (kind[root] == TAB_BAR)    drawTabBar(root);
        if (kind[root] == DIVIDER)    drawDivider(root);
        if (kind[root] == TREE_VIEW)  drawTree(root);
        if (kind[root] == COMBO_BOX)  drawCombo(root);
        if (kind[root] == COMBO_LIST) drawComboList(root);
        if (kind[root] == MENU_TITLE) drawMenuTitle(root);
        if (kind[root] == MENU_ITEM)  drawMenuItem(root);
        if (kind[root] == DIALOG_TITLE) drawDialogTitle(root);

        // A scrolling row or column shows its children only inside its padding, and where it
        // is scrolled to (step 3g-2).
        boolean scrolls = layout.isScrollable(root);
        if (scrolls) {
            float pad = layout.padding(root);
            aegis.pushClipRect(layout.x(root) + pad, layout.y(root) + pad,
                layout.width(root) - pad * 2.0f, layout.height(root) - pad * 2.0f);
        }
        for (int child = layout.firstChild(root); child != AegisLayout.NONE;
             child = layout.nextSibling(child)) {
            drawNode(child);
        }
        if (scrolls) {
            aegis.popClipRect();
            drawScrollBar(root);
        }
    }

    /**
     * A scrolling row or column's bar: thin, along its far edge — the right of a column, the
     * bottom of a row — as long as the share of the content in view, at the place the view is.
     * Lighter under the pointer and while dragged. Drawn only when there is something to
     * scroll to; centred in the padding when the padding has room for it.
     */
    private void drawScrollBar(int node) {
        AegisStyle style = globalStyle;   // containers are not dressed: global values only, for now

        // A pane that has focus itself — one with nothing focusable in it — shows it.
        if (tree.isFocused(node)) {
            drawFocusRing(style, layout.x(node), layout.y(node), layout.width(node), layout.height(node), 0.0f);
        }
        if (layout.scrollRange(node) <= 0.0f) return;
        boolean across = layout.isRow(node);
        float t    = style.scrollbarWidth;
        float len  = barLength(node);
        float at   = barStart(node);
        float edge = (Math.max(layout.padding(node), t) + t) * 0.5f;   // from the far edge to the bar's far side

        boolean lit = barDragged == node
            || (tree.pressed() == AegisLayout.NONE && inBarStrip(node, tree.pointerX(), tree.pointerY()));
        float[] ink = lit ? style.scrollbarThumbHover : style.scrollbarThumb;
        if (across) {
            aegis.addRoundedRect(at, layout.y(node) + layout.height(node) - edge, len, t, t * 0.5f,
                ink[0], ink[1], ink[2], ink[3]);
        } else {
            aegis.addRoundedRect(layout.x(node) + layout.width(node) - edge, at, t, len, t * 0.5f,
                ink[0], ink[1], ink[2], ink[3]);
        }
    }

    // -----------------------------------------------------------------------------------
    // Drawing each kind
    // -----------------------------------------------------------------------------------

    /**
     * A button: its fill for the state it is in, a border, the label centred, and the focus
     * ring when focused.
     *
     * <p>Drawn pressed only while also hovered — a press dragged off the button goes back to
     * resting, showing that letting go now would not activate it.</p>
     */
    private void drawButton(int node) {
        AegisStyle style = styleOf(node);
        float x = layout.x(node), y = layout.y(node);
        float w = layout.width(node), h = layout.height(node);

        float[] fill = style.buttonFill;
        if (tree.isHovered(node)) fill = tree.isPressed(node) ? style.buttonPressed : style.buttonHover;

        float[] border = style.buttonBorder;
        aegis.addRoundedRect(x, y, w, h, style.buttonRadius,
            fill[0], fill[1], fill[2], fill[3],
            border[0], border[1], border[2], border[3], style.buttonBorderWidth);

        // Centred by measurement and by line box, and clipped to the button so a label longer
        // than its button can never draw over a neighbour.
        String text = label[node];
        float textX = x + (w - aegis.measure(text)) * 0.5f;
        float textY = y + (h - aegis.lineHeight()) * 0.5f;
        float[] ink = style.buttonText;
        aegis.pushClipRect(x, y, w, h);
        aegis.addTextTop(textX, textY, text, ink[0], ink[1], ink[2], ink[3]);
        aegis.popClipRect();

        if (isDefaultButton(node)) {
            float[] d = style.dialogDefault;
            aegis.addRoundedRect(x, y, w, h, style.buttonRadius, 0.0f, 0.0f, 0.0f, 0.0f, d[0], d[1], d[2], d[3], 2.0f);
        }
        if (tree.isFocused(node)) drawFocusRing(style, x, y, w, h, style.buttonRadius);
    }

    /**
     * A checkbox: the square, vertically centred, lighter while hovered, with an inner mark
     * when ticked; the label to its right; the focus ring around the whole widget, since the
     * whole widget is what the keyboard acts on.
     *
     * <p>The mark is a filled inner square rather than a tick: the atlas has no ✓ glyph, and
     * drawing one from strokes would need a primitive the draw list does not have.</p>
     */
    private void drawCheckbox(int node) {
        AegisStyle style = styleOf(node);
        float x = layout.x(node), y = layout.y(node);
        float w = layout.width(node), h = layout.height(node);

        float size = style.checkboxSize;
        float boxY = y + Math.round((h - size) * 0.5f);

        float[] fill   = tree.isHovered(node) ? style.checkboxHover : style.checkboxFill;
        float[] border = style.checkboxBorder;
        aegis.addRoundedRect(x, boxY, size, size, style.checkboxRadius,
            fill[0], fill[1], fill[2], fill[3],
            border[0], border[1], border[2], border[3], 1.0f);

        if (checked[node]) {
            float inset = style.checkboxMarkInset;
            float[] mark = style.checkboxMark;
            aegis.addRoundedRect(x + inset, boxY + inset, size - inset * 2.0f, size - inset * 2.0f,
                Math.max(0.0f, style.checkboxRadius - 1.0f),
                mark[0], mark[1], mark[2], mark[3]);
        }

        float textX = x + size + style.checkboxGap;
        float textY = y + (h - aegis.lineHeight()) * 0.5f;
        float[] ink = style.checkboxText;
        aegis.pushClipRect(x, y, w, h);
        aegis.addTextTop(textX, textY, label[node], ink[0], ink[1], ink[2], ink[3]);
        aegis.popClipRect();

        if (tree.isFocused(node)) drawFocusRing(style, x, y, w, h, style.checkboxRadius);
    }

    /**
     * A slider: the track, filled in the accent colour from the minimum up to the thumb; the
     * round thumb, lighter while hovered and darker while dragged; the value at the right,
     * rounded to a whole number; and the focus ring around the whole widget.
     */
    private void drawSlider(int node) {
        AegisStyle style = styleOf(node);
        float x = layout.x(node), y = layout.y(node);
        float w = layout.width(node), h = layout.height(node);

        float left  = sliderTrackLeft(node);
        float right = sliderTrackRight(node);
        float range = sliderMax[node] - sliderMin[node];
        float t     = range > 0.0f ? (sliderValue[node] - sliderMin[node]) / range : 0.0f;
        float thumbX = Math.round(left + t * (right - left));
        float midY   = y + h * 0.5f;

        // The track runs the thumb's full travel, as a thin rounded bar centred vertically.
        float trackH = style.sliderTrackHeight;
        float trackY = Math.round(midY - trackH * 0.5f);
        float[] track = style.sliderTrack;
        aegis.addRoundedRect(left, trackY, right - left, trackH, trackH * 0.5f,
            track[0], track[1], track[2], track[3]);
        float[] fill = style.sliderFill;
        aegis.addRoundedRect(left, trackY, thumbX - left, trackH, trackH * 0.5f,
            fill[0], fill[1], fill[2], fill[3]);

        // The thumb: a rounded rectangle whose radius is half its size is a circle.
        float size = style.sliderThumbSize;
        float[] thumb = style.sliderThumb;
        if (tree.isPressed(node))      thumb = style.sliderThumbPressed;
        else if (tree.isHovered(node)) thumb = style.sliderThumbHover;
        aegis.addRoundedRect(thumbX - size * 0.5f, Math.round(midY - size * 0.5f), size, size, size * 0.5f,
            thumb[0], thumb[1], thumb[2], thumb[3]);

        // The value, rounded to a whole number and right-aligned in the room kept for it.
        // StringBuilder.append(int) writes digits into the reused builder without allocating;
        // String.format would allocate every frame, which §9 forbids in the frame loop.
        valueText.setLength(0);
        valueText.append(Math.round(sliderValue[node]));
        float textW = aegis.measure(valueText);
        float[] ink = style.sliderText;
        aegis.addTextTop(x + w - textW, y + (h - aegis.lineHeight()) * 0.5f, valueText,
            ink[0], ink[1], ink[2], ink[3]);

        if (tree.isFocused(node)) drawFocusRing(style, x, y, w, h, size * 0.5f);
    }

    /**
     * A text field: its fill, lighter while hovered; a border that turns the accent colour
     * while focused, standing in for the focus ring; the text, scrolled so the caret is in
     * view and clipped to the space inside the padding — or the placeholder, dimmed, while it
     * is empty; and, while focused, the blinking caret.
     */
    private void drawTextField(int node) {
        AegisStyle style = styleOf(node);
        float x = layout.x(node), y = layout.y(node);
        float w = layout.width(node), h = layout.height(node);
        boolean focused = tree.isFocused(node);

        float[] fill   = tree.isHovered(node) ? style.textFieldHover : style.textFieldFill;
        float[] border = focused ? style.textFieldBorderFocused : style.textFieldBorder;
        aegis.addRoundedRect(x, y, w, h, style.textFieldRadius,
            fill[0], fill[1], fill[2], fill[3],
            border[0], border[1], border[2], border[3], style.textFieldBorderWidth);

        drawFieldLine(node, style, style.textFieldText);
    }

    /**
     * The one line of a text field or a spinner, inside its frame: the selection, the text or
     * the placeholder, and the caret, scrolled so the caret is in view and clipped to the part
     * that shows text.
     */
    private void drawFieldLine(int node, AegisStyle style, float[] ink) {
        float y = layout.y(node), h = layout.height(node);
        boolean focused = tree.isFocused(node);

        scrollField(node, focused && revealCaret);
        if (focused) revealCaret = false;

        float left  = fieldTextLeft(node);
        float textX = Math.round(left - fieldScroll[node]);   // whole pixels keep glyphs crisp
        float textY = y + (h - aegis.lineHeight()) * 0.5f;
        StringBuilder text = fieldText[node];

        aegis.pushClipRect(left, y, fieldTextWidth(node), h);

        // The selection, behind the text, only while the field has focus: an unfocused field
        // keeps its selection for when focus returns, but does not show it.
        int selStart = selectionStart(node), selEnd = selectionEnd(node);
        if (focused && selStart != selEnd) {
            float from = Math.round(textX + caretOffset(node, selStart));
            float to   = Math.round(textX + caretOffset(node, selEnd));
            float[] sel = style.textFieldSelection;
            aegis.addRoundedRect(from, textY, to - from, aegis.lineHeight(), 0.0f,
                sel[0], sel[1], sel[2], sel[3]);
        }

        if (text.length() == 0 && label[node] != null) {
            float[] hint = style.textFieldPlaceholder;
            aegis.addTextTop(left, textY, label[node], hint[0], hint[1], hint[2], hint[3]);
        } else {
            aegis.addTextTop(textX, textY, text, ink[0], ink[1], ink[2], ink[3]);
        }

        // Shown for one blink interval, hidden for the next, counted from the last time it
        // moved or the field gained focus.
        boolean caretOn = ((now() - caretShownAt) / CARET_BLINK_NANOS) % 2 == 0;
        if (focused && caretOn) {
            float[] c = style.textFieldCaret;
            aegis.addRoundedRect(Math.round(textX + caretOffset(node, caret[node])), textY,
                style.textFieldCaretWidth, aegis.lineHeight(), 0.0f,
                c[0], c[1], c[2], c[3]);
        }
        aegis.popClipRect();
    }

    /**
     * A tabbed pane's bar: a line under it from edge to edge; each tab a shape with rounded
     * top corners, its title centred — the shown one raised and marked with the accent line,
     * the others dim, lighter under the pointer; and, while the bar has focus, the focus ring
     * around the shown tab, since that is the one the arrows move from. Tabs the bar has no
     * room for are cut off at its end.
     */
    private void drawTabBar(int bar) {
        AegisStyle style = styleOf(bar);
        TabSet tabs = tabsOf[bar];
        float x = layout.x(bar), y = layout.y(bar);
        float w = layout.width(bar), h = layout.height(bar);
        float accent = style.tabsAccentHeight;

        int over = tree.isHovered(bar) && !tree.isPressed(bar) ? tabAt(bar, tree.pointerX()) : -1;
        float textY = y + style.tabsPaddingY;
        float tabX = x;
        float ringX = 0.0f, ringW = 0.0f;

        aegis.pushClipRect(x, y, w, h);
        for (int i = 0; i < tabs.count; i++) {
            float tabW = tabWidth(style, tabs.titles[i]);
            boolean shown = i == tabs.selected;
            float[] fill = shown ? style.tabsSelected : i == over ? style.tabsHover : style.tabsFill;
            float[] ink  = shown ? style.tabsTextSelected : style.tabsText;

            // Round at the top only: the shape runs a radius past the bar's bottom, where the
            // clip cuts its lower corners off.
            aegis.addRoundedRect(tabX, y, tabW, h + style.tabsRadius, style.tabsRadius,
                fill[0], fill[1], fill[2], fill[3]);
            aegis.addTextTop(tabX + style.tabsPaddingX, textY, tabs.titles[i], ink[0], ink[1], ink[2], ink[3]);
            if (shown) {
                ringX = tabX;
                ringW = tabW;
            }
            tabX += tabW + style.tabsGap;
        }
        // Over the tabs' lower edges: the line under the whole bar, then the shown tab's mark.
        float[] line = style.tabsBorder;
        aegis.addRoundedRect(x, y + h - 1.0f, w, 1.0f, 0.0f, line[0], line[1], line[2], line[3]);
        if (ringW > 0.0f) {
            float[] mark = style.tabsAccent;
            aegis.addRoundedRect(ringX, y + h - accent, ringW, accent, 0.0f, mark[0], mark[1], mark[2], mark[3]);
        }
        aegis.popClipRect();

        if (tree.isFocused(bar) && ringW > 0.0f) {
            float inset = style.focusRingGap + style.focusRingWidth;   // inside the tab: the bar's neighbours are close
            drawFocusRing(style, ringX + inset, y + inset, ringW - inset * 2.0f, h - inset * 2.0f,
                Math.max(0.0f, style.tabsRadius - inset));
        }
    }

    /**
     * A combo box: like a button, the chosen item at the left and the arrow at the right; the
     * focus ring while it has focus.
     */
    private void drawCombo(int node) {
        AegisStyle style = styleOf(node);
        float x = layout.x(node), y = layout.y(node), w = layout.width(node), h = layout.height(node);
        float[] fill = tree.isHovered(node) || isComboOpen(node) ? style.comboHover : style.comboFill;
        float[] border = style.comboBorder;
        aegis.addRoundedRect(x, y, w, h, style.comboRadius, fill[0], fill[1], fill[2], fill[3],
            border[0], border[1], border[2], border[3], 1.0f);

        float textY = y + (h - aegis.lineHeight()) * 0.5f;
        float[] ink = style.comboText;
        aegis.pushClipRect(x, y, w - style.comboArrowWidth, h);
        aegis.addTextTop(x + style.comboPaddingX, textY, comboItems[node][comboChosen[node]], ink[0], ink[1], ink[2], ink[3]);
        aegis.popClipRect();

        arrowText.setLength(0);
        arrowText.append(AegisFont.ARROW_DOWN);
        float[] a = style.comboArrow;
        aegis.addTextTop(Math.round(x + w - style.comboArrowWidth + (style.comboArrowWidth - aegis.measure(arrowText)) * 0.5f),
            textY, arrowText, a[0], a[1], a[2], a[3]);

        if (tree.isFocused(node)) drawFocusRing(style, x, y, w, h, style.comboRadius);
    }

    /** A dialog's title bar: the title, a line under it, and the × that cancels, red under the pointer. */
    private void drawDialogTitle(int node) {
        AegisStyle style = styleOf(node);
        float x = layout.x(node), y = layout.y(node), w = layout.width(node), h = layout.height(node);
        float[] ink = style.dialogTitle, line = style.dialogLine;
        aegis.addTextTop(x, y + (h - aegis.lineHeight()) * 0.5f, label[node], ink[0], ink[1], ink[2], ink[3]);
        aegis.addRoundedRect(x, y + h + 4.0f, w, 1.0f, 0.0f, line[0], line[1], line[2], line[3]);

        float markX = closeMarkX(node);
        if (tree.isHovered(node) && tree.pointerX() >= markX) {
            float[] hot = style.dialogCloseHover;
            aegis.addRoundedRect(markX, y, h, h, 4.0f, hot[0], hot[1], hot[2], hot[3]);
        }
        arrowText.setLength(0);
        arrowText.append('\u00D7');   // ×, in Latin-1
        aegis.addTextTop(Math.round(markX + (h - aegis.measure(arrowText)) * 0.5f), y + (h - aegis.lineHeight()) * 0.5f,
            arrowText, ink[0], ink[1], ink[2], ink[3]);
    }

    /** A title on a menu bar: highlighted while its menu is open, lighter under the pointer. */
    private void drawMenuTitle(int node) {
        AegisStyle style = styleOf(node);
        float x = layout.x(node), y = layout.y(node), w = layout.width(node), h = layout.height(node);
        boolean open = isOverlayOpen(titleMenu[node]);
        if (open || tree.isHovered(node)) {
            float[] fill = open ? style.menuHighlight : style.menuTitleHover;
            aegis.addRoundedRect(x, y, w, h, 3.0f, fill[0], fill[1], fill[2], fill[3]);
        }
        float[] ink = open ? style.menuHighlightText : style.menuText;
        aegis.addTextTop(x + style.menuPaddingX, y + (h - aegis.lineHeight()) * 0.5f, label[node], ink[0], ink[1], ink[2], ink[3]);
    }

    /**
     * An item in a menu: filled when pointed at; a tick at its left when it ticks and is
     * ticked; its text; its shortcut at the right, dimmed; an arrow when it opens a submenu.
     */
    private void drawMenuItem(int node) {
        AegisStyle style = styleOf(node);
        float x = layout.x(node), y = layout.y(node), w = layout.width(node), h = layout.height(node);
        int menu = layout.parent(node);
        boolean lit = menu != AegisLayout.NONE && menuPointed[menu] == node;
        if (lit) {
            float[] hl = style.menuHighlight;
            aegis.addRoundedRect(x, y, w, h, 3.0f, hl[0], hl[1], hl[2], hl[3]);
        }
        float[] ink = lit ? style.menuHighlightText : style.menuText;
        float[] dim = lit ? style.menuHighlightText : style.menuTextDim;
        float textY = y + (h - aegis.lineHeight()) * 0.5f;
        if (itemChecks[node] && checked[node]) {
            arrowText.setLength(0);
            arrowText.append(AegisFont.CHECK_MARK);
            aegis.addTextTop(x + style.menuPaddingX * 0.5f, textY, arrowText, ink[0], ink[1], ink[2], ink[3]);
        }
        aegis.addTextTop(x + style.menuPaddingX + style.menuCheckWidth, textY, label[node], ink[0], ink[1], ink[2], ink[3]);
        if (itemShortcut[node] != null) {
            float sw = aegis.measure(itemShortcut[node]);
            aegis.addTextTop(x + w - style.menuPaddingX - sw, textY, itemShortcut[node], dim[0], dim[1], dim[2], dim[3]);
        }
        if (itemSubMenu[node] != AegisLayout.NONE) {
            arrowText.setLength(0);
            arrowText.append(AegisFont.ARROW_RIGHT);
            aegis.addTextTop(x + w - style.menuPaddingX - aegis.measure(arrowText), textY, arrowText, dim[0], dim[1], dim[2], dim[3]);
        }
    }

    /** A combo box's open list, on its overlay background: a row per item, the one pointed at filled. */
    private void drawComboList(int list) {
        int owner = listOwner[list];
        AegisStyle style = styleOf(owner);
        float x = layout.x(list), y = layout.y(list) + 2.0f, w = layout.width(list);
        float rowH = comboRowHeight(style);
        String[] items = comboItems[owner];
        aegis.pushClipRect(layout.x(list), layout.y(list), w, layout.height(list));
        for (int i = 0; i < items.length; i++) {
            float rowY = y + i * rowH;
            boolean lit = i == listHighlight[list];
            if (lit) {
                float[] hl = style.comboHighlight;
                aegis.addRoundedRect(x + 2.0f, rowY, w - 4.0f, rowH, 3.0f, hl[0], hl[1], hl[2], hl[3]);
            }
            float[] ink = lit ? style.comboHighlightText : style.comboText;
            aegis.addTextTop(x + style.comboPaddingX, rowY + style.comboPaddingY, items[i], ink[0], ink[1], ink[2], ink[3]);
        }
        aegis.popClipRect();
    }

    /**
     * A tree: a row per item shown, set in by its level; the selected row filled, the row under
     * the pointer lighter; an arrow before each item that has items under it, pointing down
     * when open; and, while it has focus, the focus ring inside the selected row. Rows outside
     * the scrolling pane around it are not drawn.
     */
    private void drawTree(int node) {
        AegisStyle style = styleOf(node);
        TreeItems t = itemsOf[node];
        float x = layout.x(node), y = layout.y(node), w = layout.width(node), h = layout.height(node);
        float rowH = treeRowHeight(style);
        float lh = aegis.lineHeight();

        // Only rows that can be seen: inside the tree, and inside every scrolling pane around it.
        float seenTop = y, seenBottom = y + h;
        for (int up = layout.parent(node); up != AegisLayout.NONE; up = layout.parent(up)) {
            if (!layout.isScrollable(up)) continue;
            seenTop    = Math.max(seenTop, layout.y(up));
            seenBottom = Math.min(seenBottom, layout.y(up) + layout.height(up));
        }
        int over = NO_ITEM;
        if (tree.isHovered(node) && tree.pressed() == AegisLayout.NONE) {
            over = itemOnRow(t, (int) Math.floor((tree.pointerY() - y) / rowH));
        }

        aegis.pushClipRect(x, y, w, h);
        float rowY = y;
        for (int i = t.firstTop; i != NO_ITEM && rowY < seenBottom; i = nextShown(t, i), rowY += rowH) {
            if (rowY + rowH < seenTop) continue;
            boolean selected = i == t.selected;
            if (selected || i == over) {
                float[] fill = selected ? style.treeSelected : style.treeHover;
                aegis.addRoundedRect(x, rowY, w, rowH, 3.0f, fill[0], fill[1], fill[2], fill[3]);
            }
            float levelX = x + style.treePaddingX + depthOf(t, i) * style.treeIndent;
            float textY  = rowY + (rowH - lh) * 0.5f;
            if (t.firstChild[i] != NO_ITEM) {
                arrowText.setLength(0);
                arrowText.append(t.expanded[i] ? AegisFont.ARROW_DOWN : AegisFont.ARROW_RIGHT);
                float[] a = style.treeArrow;
                aegis.addTextTop(Math.round(levelX + (style.treeIndent - aegis.measure(arrowText)) * 0.5f), textY,
                    arrowText, a[0], a[1], a[2], a[3]);
            }
            float[] ink = selected ? style.treeTextSelected : style.treeText;
            aegis.addTextTop(levelX + style.treeIndent, textY, t.text[i], ink[0], ink[1], ink[2], ink[3]);
        }
        aegis.popClipRect();

        if (tree.isFocused(node)) {
            int row = t.selected == NO_ITEM ? -1 : rowOf(t, t.selected);
            float inset = style.focusRingGap + style.focusRingWidth;
            if (row >= 0) drawFocusRing(style, x + inset, y + row * rowH + inset, w - inset * 2.0f, rowH - inset * 2.0f, 0.0f);
            else          drawFocusRing(style, x, y, w, h, 0.0f);
        }
    }

    /**
     * A split pane's divider: a line along its middle, the length of the pane, lighter under
     * the pointer and while dragged; the focus ring around it while it has focus.
     */
    private void drawDivider(int node) {
        AegisStyle style = styleOf(node);
        float x = layout.x(node), y = layout.y(node), w = layout.width(node), h = layout.height(node);
        float t = style.splitLineWidth;
        boolean lit = tree.isPressed(node) || (tree.isHovered(node) && tree.pressed() == AegisLayout.NONE);
        float[] ink = lit ? style.splitLineHover : style.splitLine;
        if (layout.isRow(layout.parent(node))) {
            aegis.addRoundedRect(Math.round(x + (w - t) * 0.5f), y, t, h, t * 0.5f, ink[0], ink[1], ink[2], ink[3]);
        } else {
            aegis.addRoundedRect(x, Math.round(y + (h - t) * 0.5f), w, t, t * 0.5f, ink[0], ink[1], ink[2], ink[3]);
        }
        if (tree.isFocused(node)) drawFocusRing(style, x, y, w, h, 0.0f);
    }

    /**
     * A spinner: a text field's frame and line, and at the right its two arrows, one above the
     * other, each lighter under the pointer.
     */
    private void drawSpinner(int node) {
        AegisStyle style = styleOf(node);
        float x = layout.x(node), y = layout.y(node);
        float w = layout.width(node), h = layout.height(node);
        boolean focused = tree.isFocused(node);

        float[] fill   = tree.isHovered(node) ? style.spinnerHover : style.spinnerFill;
        float[] border = focused ? style.spinnerBorderFocused : style.spinnerBorder;
        aegis.addRoundedRect(x, y, w, h, style.textFieldRadius,
            fill[0], fill[1], fill[2], fill[3],
            border[0], border[1], border[2], border[3], style.textFieldBorderWidth);

        drawFieldLine(node, style, style.spinnerText);

        // The arrows: each centred in its half of the part kept for them. Lit when the pointer
        // is on it and nothing else holds the pointer, or while it is the one held.
        float buttonW = style.spinnerButtonWidth;
        float buttonX = x + w - buttonW;
        int over = tree.isHovered(node) ? spinnerArrowAt(node, tree.pointerX(), tree.pointerY()) : 0;
        if (tree.isPressed(node)) over = spinHeld;
        AegisFont font = aegis.font();
        float lh = aegis.lineHeight();
        for (int way = +1; way >= -1; way -= 2) {
            arrowText.setLength(0);
            arrowText.append(way > 0 ? AegisFont.ARROW_UP : AegisFont.ARROW_DOWN);
            float[] ink = over == way ? style.spinnerArrowHover : style.spinnerArrow;
            float halfTop = way > 0 ? y : y + h * 0.5f;
            aegis.pushClipRect(buttonX, halfTop, buttonW, h * 0.5f);
            aegis.addTextTop(Math.round(buttonX + (buttonW - font.measure(arrowText)) * 0.5f),
                Math.round(halfTop + (h * 0.5f - lh) * 0.5f), arrowText, ink[0], ink[1], ink[2], ink[3]);
            aegis.popClipRect();
        }
    }

    /**
     * A text box: drawn as a text field is, line by line. The text is wrapped to the box's
     * width, scrolled up and down, and clipped to the space inside the padding; the selection
     * is a band on each line it covers, reaching a little past a line's end when it takes the
     * line break or the space the line broke at, so selecting an empty line shows.
     */
    private void drawTextBox(int node) {
        AegisStyle style = styleOf(node);
        float x = layout.x(node), y = layout.y(node);
        float w = layout.width(node), h = layout.height(node);
        boolean focused = tree.isFocused(node);

        float[] fill   = tree.isHovered(node) ? style.textFieldHover : style.textFieldFill;
        float[] border = focused ? style.textFieldBorderFocused : style.textFieldBorder;
        aegis.addRoundedRect(x, y, w, h, style.textFieldRadius,
            fill[0], fill[1], fill[2], fill[3],
            border[0], border[1], border[2], border[3], style.textFieldBorderWidth);

        layoutLines(node);
        scrollBox(node, focused && revealCaret);
        if (focused) revealCaret = false;

        float left = fieldTextLeft(node);
        float top  = y + style.textFieldPaddingY;
        float lh   = aegis.lineHeight();
        float firstY = Math.round(top - fieldScroll[node]);   // whole pixels keep glyphs crisp
        StringBuilder text = fieldText[node];
        AegisFont font = aegis.font();

        aegis.pushClipRect(left, top, fieldTextWidth(node), fieldTextHeight(node));

        if (text.length() == 0 && label[node] != null) {
            float[] hint = style.textFieldPlaceholder;
            aegis.addTextTop(left, top, label[node], hint[0], hint[1], hint[2], hint[3]);
        }

        int selStart = selectionStart(node), selEnd = selectionEnd(node);
        boolean showSelection = focused && selStart != selEnd;
        float[] sel = style.textFieldSelection;
        float[] ink = style.textFieldText;
        float   pastEnd = style.textFieldCaretWidth * 3.0f;

        int caretLine = lineOf(caret[node]);
        for (int line = 0; line < lineCount; line++) {
            float lineY = firstY + line * lh;
            if (lineY + lh < top || lineY > top + fieldTextHeight(node)) continue;   // out of view
            int from = lineStarts[line], to = lineEnds[line];

            if (showSelection && selStart <= to && selEnd > from) {
                int a = Math.max(selStart, from), b = Math.min(selEnd, to);
                float sx = Math.round(left + offsetInLine(text, from, to, a));
                float ex = Math.round(left + offsetInLine(text, from, to, b));
                if (selEnd > to && line + 1 < lineCount) ex += pastEnd;   // takes the break
                if (ex > sx) {
                    aegis.addRoundedRect(sx, lineY, ex - sx, lh, 0.0f, sel[0], sel[1], sel[2], sel[3]);
                }
            }

            if (to > from) {
                aegis.drawList().addText(font, left, font.baselineForTop(lineY), text, from, to,
                    ink[0], ink[1], ink[2], ink[3]);
            }
        }

        boolean caretOn = ((now() - caretShownAt) / CARET_BLINK_NANOS) % 2 == 0;
        if (focused && caretOn) {
            float[] c = style.textFieldCaret;
            int from = lineStarts[caretLine], to = lineEnds[caretLine];
            aegis.addRoundedRect(Math.round(left + offsetInLine(text, from, to, caret[node])),
                firstY + caretLine * lh, style.textFieldCaretWidth, lh, 0.0f,
                c[0], c[1], c[2], c[3]);
        }
        aegis.popClipRect();
    }

    /** The focus ring, standing {@code focusRingGap} off a widget's edge. Shared by every kind. */
    /** A label: its text, at the left, centred in its height, clipped to its rectangle. */
    private void drawLabel(int node) {
        AegisStyle style = styleOf(node);
        float x = layout.x(node), y = layout.y(node);
        float w = layout.width(node), h = layout.height(node);
        float[] ink = style.labelText;
        aegis.pushClipRect(x, y, w, h);
        aegis.addTextTop(x, y + (h - aegis.lineHeight()) * 0.5f, label[node], ink[0], ink[1], ink[2], ink[3]);
        aegis.popClipRect();
    }

    /**
     * A separator: a line across its parent — horizontal in a column, vertical in a row —
     * from one inner edge to the other, centred in the room the separator takes. Drawn across
     * the parent rather than the separator's own rectangle, so it spans the panel whether or
     * not the panel stretches its children.
     */
    private void drawSeparator(int node) {
        AegisStyle style = styleOf(node);
        int parent = layout.parent(node);
        if (parent == AegisLayout.NONE) return;
        float pad = layout.padding(parent);
        float t = style.separatorThickness;
        float[] line = style.separatorLine;
        if (layout.isRow(parent)) {
            float x = Math.round(layout.x(node) + (layout.width(node) - t) * 0.5f);
            aegis.addRoundedRect(x, layout.y(parent) + pad, t, layout.height(parent) - pad * 2.0f, 0.0f,
                line[0], line[1], line[2], line[3]);
        } else {
            float y = Math.round(layout.y(node) + (layout.height(node) - t) * 0.5f);
            aegis.addRoundedRect(layout.x(parent) + pad, y, layout.width(parent) - pad * 2.0f, t, 0.0f,
                line[0], line[1], line[2], line[3]);
        }
    }

    private void drawFocusRing(AegisStyle style, float x, float y, float w, float h, float radius) {
        float out = style.focusRingGap + style.focusRingWidth;
        float[] ring = style.focusRing;
        aegis.addRoundedRect(x - out, y - out, w + out * 2.0f, h + out * 2.0f, radius + out,
            0.0f, 0.0f, 0.0f, 0.0f,
            ring[0], ring[1], ring[2], ring[3], style.focusRingWidth);
    }
}
