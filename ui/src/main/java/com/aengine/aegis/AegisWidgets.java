package com.aengine.aegis;

import com.aengine.core.Keys;

import java.util.Arrays;

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
 * for (each input event) ae.key(code, mods, isRepeat);
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
 * <p><strong>Colours come from {@link AegisStyle} only</strong>, the one place the theme will
 * replace. Nothing in this class names a colour.</p>
 *
 * <p>Allocates nothing per frame. Not thread-safe, and not meant to be.</p>
 */
public final class AegisWidgets {

    // What a node is, as far as widgets are concerned.
    private static final byte NOT_A_WIDGET = 0;
    private static final byte BUTTON       = 1;
    private static final byte CHECKBOX     = 2;
    private static final byte SLIDER       = 3;

    /** Shift + arrow moves a slider this many steps at once. */
    private static final int LARGE_STEP = 10;

    private final Aegis       aegis;
    private final AegisLayout layout;
    private final AegisTree   tree;
    private final AegisStyle  style = new AegisStyle();

    /** Indexed by layout handle. */
    private final byte[]   kind;
    private final String[] label;   // set once at build time; never built per frame
    private final boolean[] checked; // checkboxes: ticked or not

    // Sliders: the value, its range, and how far one small step moves it.
    private final float[] sliderValue;
    private final float[] sliderMin;
    private final float[] sliderMax;
    private final float[] sliderStep;

    /** Where a slider's value is written as text each frame, reused rather than rebuilt. */
    private final StringBuilder valueText = new StringBuilder(16);

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
    }

    /** The colours and sizes every widget draws with. See {@link AegisStyle}. */
    public AegisStyle style() { return style; }

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
     * @return its handle — a layout node, usable with the layout and the tree like any other
     */
    public int button(int parent, String text) {
        int node = layout.box(parent);
        layout.setSize(node,
            (float) Math.ceil(aegis.measure(text)) + style.buttonPaddingX * 2.0f,
            aegis.lineHeight() + style.buttonPaddingY * 2.0f);

        tree.setInteractive(node, true);
        tree.setFocusable(node, true);

        kind[node]  = BUTTON;
        label[node] = text;
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
     * @return its handle
     */
    public int checkbox(int parent, String text) {
        int node = layout.box(parent);
        layout.setSize(node,
            style.checkboxSize + style.checkboxGap + (float) Math.ceil(aegis.measure(text)),
            Math.max(style.checkboxSize, aegis.lineHeight()) + style.checkboxPaddingY * 2.0f);

        tree.setInteractive(node, true);
        tree.setFocusable(node, true);

        kind[node]    = CHECKBOX;
        label[node]   = text;
        checked[node] = false;
        return node;
    }

    /** Whether a checkbox is ticked. */
    public boolean isChecked(int node) { return checked[node]; }

    /**
     * Ticks or unticks a checkbox from code — to show a setting's current value, say. Does not
     * count as a change: {@link #wasChanged} reports only what the user did.
     */
    public void setChecked(int node, boolean on) { checked[node] = on; }

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
     * @return its handle
     */
    public int slider(int parent, float min, float max, float value) {
        int node = layout.box(parent);
        layout.setSize(node, style.sliderWidth,
            Math.max(style.sliderThumbSize, aegis.lineHeight()) + style.sliderPaddingY * 2.0f);

        tree.setInteractive(node, true);
        tree.setFocusable(node, true);

        kind[node]        = SLIDER;
        sliderMin[node]   = min;
        sliderMax[node]   = max;
        sliderStep[node]  = (max - min) / 100.0f;
        sliderValue[node] = clamp(value, min, max);
        return node;
    }

    /** A slider's current value, between its minimum and maximum, unrounded. */
    public float sliderValue(int node) { return sliderValue[node]; }

    /** Sets a slider's value from code, clamped to its range. Not reported by {@link #wasChanged}. */
    public void setSliderValue(int node, float value) {
        sliderValue[node] = clamp(value, sliderMin[node], sliderMax[node]);
    }

    /** How far one small step — an arrow key, {@code +} or {@code -} — moves a slider. */
    public void setSliderStep(int node, float step) { sliderStep[node] = step; }

    /** Whether a node was made by this class — a button, a checkbox, a slider, and later the others. */
    public boolean isWidget(int node) { return kind[node] != NOT_A_WIDGET; }

    /**
     * Forgets every widget. Call it together with {@link AegisLayout#clear()} and
     * {@link AegisTree#clear()}, since handles are reused after a clear.
     */
    public void clear() {
        Arrays.fill(kind, NOT_A_WIDGET);
        Arrays.fill(label, null);
        Arrays.fill(checked, false);
        activated = AegisLayout.NONE;
        changed   = AegisLayout.NONE;
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
     * </ul>
     *
     * @param key    the key, as in {@link Keys}
     * @param mods   modifier bits, as in {@link Keys#MOD_SHIFT}
     * @param repeat whether this is a repeat of a held key rather than a fresh press
     */
    public void key(int key, int mods, boolean repeat) {
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
     * of them. The text field will take every character here, in part 6.</p>
     */
    public void character(int codepoint) {
        if (codepoint == '+')      pendingSteps++;
        else if (codepoint == '-') pendingSteps--;
    }

    /**
     * Applies this frame's pointer and keys to the widgets under {@code root}. Call it once a
     * frame, after {@link AegisTree#update} and after the frame's {@link #key} calls.
     */
    public void update(int root) {
        activated = AegisLayout.NONE;
        changed   = AegisLayout.NONE;
        activatedByKeyboard = false;

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
        return layout.x(node) + style.sliderThumbSize * 0.5f;
    }

    /** Where the thumb's centre sits at the maximum. */
    private float sliderTrackRight(int node) {
        return layout.x(node) + layout.width(node)
             - style.sliderValueWidth - style.sliderGap - style.sliderThumbSize * 0.5f;
    }

    private static float clamp(float value, float min, float max) {
        return Math.max(min, Math.min(max, value));
    }

    /** What activating a widget means for its kind: a button fires, a checkbox flips. */
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
            default -> { }   // not a widget: a click on a plain node does nothing here
        }
    }

    /** Whether this button was activated this frame — by a click, Enter or Space. */
    public boolean wasActivated(int node) { return activated == node; }

    /**
     * Whether the user changed this widget this frame — for a checkbox, ticked or unticked it
     * by a click, Enter or Space; for a slider, moved it by dragging or by a key. Changes made
     * from code, with {@link #setChecked} or {@link #setSliderValue}, do not count.
     */
    public boolean wasChanged(int node) { return changed == node; }

    /**
     * Whether this frame's activation came from the keyboard rather than the pointer. For
     * showing it, as the scaffolding does; code acting on a button should not need to care.
     */
    public boolean activatedByKeyboard() { return activatedByKeyboard; }

    /**
     * Draws every widget under {@code root}, in tree order, each as its kind and state say.
     *
     * <p>Only widgets: panes and other plain nodes are the caller's to draw, first, so the
     * widgets sit on top of them.</p>
     */
    public void draw(int root) {
        if (kind[root] == BUTTON)   drawButton(root);
        if (kind[root] == CHECKBOX) drawCheckbox(root);
        if (kind[root] == SLIDER)   drawSlider(root);
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

        if (tree.isFocused(node)) drawFocusRing(x, y, w, h, style.buttonRadius);
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

        if (tree.isFocused(node)) drawFocusRing(x, y, w, h, style.checkboxRadius);
    }

    /**
     * A slider: the track, filled in the accent colour from the minimum up to the thumb; the
     * round thumb, lighter while hovered and darker while dragged; the value at the right,
     * rounded to a whole number; and the focus ring around the whole widget.
     */
    private void drawSlider(int node) {
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

        if (tree.isFocused(node)) drawFocusRing(x, y, w, h, size * 0.5f);
    }


    /** The focus ring, standing {@code focusRingGap} off a widget's edge. Shared by every kind. */
    private void drawFocusRing(float x, float y, float w, float h, float radius) {
        float out = style.focusRingGap + style.focusRingWidth;
        float[] ring = style.focusRing;
        aegis.addRoundedRect(x - out, y - out, w + out * 2.0f, h + out * 2.0f, radius + out,
            0.0f, 0.0f, 0.0f, 0.0f,
            ring[0], ring[1], ring[2], ring[3], style.focusRingWidth);
    }
}
