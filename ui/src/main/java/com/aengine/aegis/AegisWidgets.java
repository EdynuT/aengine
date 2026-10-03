package com.aengine.aegis;

import com.aengine.core.Keys;
import com.aengine.core.Window;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
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
     * it was made — {@code viewport, stopButton}. Kept from creation rather than read from the
     * tree, so a layout that moves the widget to another panel does not change how it is dressed.
     */
    private final List<String>[] themePath;

    /** How many lines a text box shows — kept so its height can be worked out again when a theme changes the padding. */
    private final int[] visibleLines;

    /** Indexed by layout handle. */
    private final byte[]   kind;
    private final String[] label;   // set once at build time; never built per frame. A text field's placeholder.
    private final boolean[] checked; // checkboxes: ticked or not

    // Sliders: the value, its range, and how far one small step moves it.
    private final float[] sliderValue;
    private final float[] sliderMin;
    private final float[] sliderMax;
    private final float[] sliderStep;

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
     * name: {@code viewport.stopButton} — wherever a layout file has since moved it. A theme
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
        theme.report();
    }

    /** Walks a subtree, giving each widget the style its path calls for, and sizing it again. */
    private void dressSubtree(AegisTheme theme, int node) {
        if (kind[node] != NOT_A_WIDGET) {
            styleOf[node] = theme.styleFor(themePath[node], true, themeKind(node));
            fitToContent(node);
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
            case TEXT_FIELD, TEXT_BOX -> "textfield";
            case LABEL                -> "label";
            case SEPARATOR            -> "separator";
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
     * @param name   its name, which a theme or a layout file writes — {@code "stopButton"};
     *               see {@link AegisLayout#setNodeName}
     * @param text   the label; kept, so pass a string that does not change
     * @return its handle — a layout node, usable with the layout and the tree like any other
     * @throws IllegalArgumentException for a name {@link AegisLayout#isValidNodeName} refuses
     */
    public int button(int parent, String name, String text) {
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
     * @param name   its name, which a theme or a layout file writes — {@code "snapCheckbox"};
     *               see {@link AegisLayout#setNodeName}
     * @param text   the label; kept, so pass a string that does not change
     * @return its handle
     * @throws IllegalArgumentException for a name {@link AegisLayout#isValidNodeName} refuses
     */
    public int checkbox(int parent, String name, String text) {
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
     * @param name   its name, which a theme or a layout file writes — {@code "nameLabel"};
     *               see {@link AegisLayout#setNodeName}
     * @param text   the text; kept, so pass a string that does not change, and use
     *               {@link #setLabel} to show another
     * @return its handle
     * @throws IllegalArgumentException for a name {@link AegisLayout#isValidNodeName} refuses
     */
    public int label(int parent, String name, String text) {
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
     * {@code separator.thickness} plus {@code separator.margin} on each side along the way its
     * parent stacks, and nothing across. Neither clickable nor focusable.</p>
     *
     * @param parent the row or column to add it to
     * @param name   its name, which a theme or a layout file writes — {@code "settingsSeparator"};
     *               see {@link AegisLayout#setNodeName}
     * @return its handle
     * @throws IllegalArgumentException for a name {@link AegisLayout#isValidNodeName} refuses
     */
    public int separator(int parent, String name) {
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
     * @param name   its name, which a theme or a layout file writes — {@code "opacitySlider"};
     *               see {@link AegisLayout#setNodeName}
     * @param min    the value at the left end
     * @param max    the value at the right end
     * @param value  the starting value, clamped to the range
     * @return its handle
     * @throws IllegalArgumentException for a name {@link AegisLayout#isValidNodeName} refuses
     */
    public int slider(int parent, String name, float min, float max, float value) {
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
     * A single-line text field with no limit on its length, added to {@code parent}. Starts
     * empty.
     *
     * <p>Its buffer starts with room for {@value #UNLIMITED_START_CAPACITY} characters and
     * grows when the text outgrows it — an allocation now and then while typing past that, never
     * one per frame. Use {@link #textField(int, String, int)} where a limit makes sense, and
     * editing then allocates nothing at all.</p>
     *
     * @param parent the row or column to add it to
     * @param name   its name, which a theme or a layout file writes — {@code "notesField"};
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
     * @param name      its name, which a theme or a layout file writes — {@code "nameField"};
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
     * @param name         its name, which a theme or a layout file writes — {@code "descriptionBox"};
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
        themePath[node] = List.copyOf(path);
        return node;
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
     * box, a label or a separator.
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
        Arrays.fill(kind, NOT_A_WIDGET);
        Arrays.fill(label, null);
        Arrays.fill(checked, false);
        Arrays.fill(fieldText, null);
        Arrays.fill(themePath, null);
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
     *   <li>Enter and Space act on the focused widget: a button fires, a checkbox flips. On the
     *       press only, so holding them does not fire again and again.</li>
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
        int focused = tree.focused();
        if (isText(focused) && editKey(focused, key, mods, repeat)) {
            return;
        }

        boolean shift = (mods & Keys.MOD_SHIFT) != 0;

        if (key == Keys.TAB) {
            pendingFocusMove = shift ? -1 : +1;
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
        int focused = tree.focused();
        if (isText(focused)) {
            insert(focused, codepoint);
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

        if (box && key == Keys.ENTER && !ctrl) {
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
            if (!repeat) tree.focus(AegisLayout.NONE);
            return true;
        } else {
            return false;
        }

        caretShownAt = System.nanoTime();   // the caret shows at once wherever it went
        return true;
    }

    /**
     * Types one character into a text field at its caret, replacing the selection if there is
     * one. Refused if it is not {@linkplain #isAllowed allowed}, or the field is full.
     */
    private void insert(int node, int codepoint) {
        if (!isAllowed(codepoint)) return;
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

    /** Whether a node is a text field or a text box — whether it takes typing. */
    private boolean isText(int node) {
        return node != AegisLayout.NONE && (kind[node] == TEXT_FIELD || kind[node] == TEXT_BOX);
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
        caretShownAt = System.nanoTime();
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
            if (isAllowed(c)) clipboardScratch.append(c);
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
        hLastEdit = System.nanoTime();
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
        caretShownAt = System.nanoTime();
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
        caretShownAt = System.nanoTime();
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
        activated = AegisLayout.NONE;
        changed   = AegisLayout.NONE;
        activatedByKeyboard = false;

        // Text was edited as the keys arrived; this frame reports it.
        changed = edited;
        edited  = AegisLayout.NONE;

        if (pendingFocusMove > 0) tree.focusNext(root);
        if (pendingFocusMove < 0) tree.focusPrevious(root);

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
        if (isText(pressed)) {
            boolean freshPress = pressed != lastPressed;
            int at = caretAt(pressed, tree.pointerX(), tree.pointerY());
            if (freshPress || at != caret[pressed]) {
                hOpen = false;   // the caret moved: the block being typed is over
                moveCaret(pressed, at, !freshPress);
            }
        }
        lastPressed = pressed;

        // A field that has just gained focus shows its caret at once rather than mid-blink.
        if (focused != lastFocused) {
            caretShownAt = System.nanoTime();
            lastFocused  = focused;
        }

        // The undo history belongs to the field with focus: once focus is elsewhere it is gone.
        // A second without an edit ends the block being typed, so the next word is its own undo.
        if (hField != AegisLayout.NONE && hField != focused) resetHistory();
        if (hOpen && System.nanoTime() - hLastEdit > HISTORY_PAUSE_NANOS) hOpen = false;

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

    /** How wide the part of a text field that shows text is. */
    private float fieldTextWidth(int node) {
        return Math.max(0.0f, layout.width(node) - styleOf(node).textFieldPaddingX * 2.0f);
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
     * Scrolls the text box under the pointer by the mouse wheel — {@code dy} as the wheel
     * reports it, positive away from the user, which scrolls up. Three lines a notch. Handed in
     * by whoever owns input, like {@link #key}; a wheel over anything else does nothing here.
     *
     * @param dy the wheel's vertical offset, as from {@link com.aengine.core.Input#eventScrollY}
     */
    public void scroll(float dy) {
        int node = tree.hovered();
        if (node == AegisLayout.NONE || kind[node] != TEXT_BOX) return;
        fieldScroll[node] -= dy * aegis.lineHeight() * 3.0f;   // kept in range when drawn
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
     * widgets sit on top of them.</p>
     *
     * @param root the top of the tree to draw
     */
    public void draw(int root) {
        if (kind[root] == BUTTON)   drawButton(root);
        if (kind[root] == CHECKBOX) drawCheckbox(root);
        if (kind[root] == SLIDER)   drawSlider(root);
        if (kind[root] == TEXT_FIELD) drawTextField(root);
        if (kind[root] == TEXT_BOX)   drawTextBox(root);
        if (kind[root] == LABEL)      drawLabel(root);
        if (kind[root] == SEPARATOR)  drawSeparator(root);
        for (int child = layout.firstChild(root); child != AegisLayout.NONE;
             child = layout.nextSibling(child)) {
            draw(child);
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
            float[] ink = style.textFieldText;
            aegis.addTextTop(textX, textY, text, ink[0], ink[1], ink[2], ink[3]);
        }

        // Shown for one blink interval, hidden for the next, counted from the last time it
        // moved or the field gained focus.
        boolean caretOn = ((System.nanoTime() - caretShownAt) / CARET_BLINK_NANOS) % 2 == 0;
        if (focused && caretOn) {
            float[] c = style.textFieldCaret;
            aegis.addRoundedRect(Math.round(textX + caretOffset(node, caret[node])), textY,
                style.textFieldCaretWidth, aegis.lineHeight(), 0.0f,
                c[0], c[1], c[2], c[3]);
        }
        aegis.popClipRect();
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

        boolean caretOn = ((System.nanoTime() - caretShownAt) / CARET_BLINK_NANOS) % 2 == 0;
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
