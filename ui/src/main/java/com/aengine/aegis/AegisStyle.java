package com.aengine.aegis;

/**
 * Every colour and measurement the widgets draw with, in one place.
 *
 * <p>This is where the theme plugs in. Each field is bound to one entry of
 * {@link AegisThemeCatalogue}, and {@link AegisTheme} writes every one of them when it builds
 * a style, so what a widget draws with is always what the theme resolved. The values written
 * below are the starting point of a fresh object only; they mirror the catalogue's factory
 * defaults. No widget names a colour in its drawing code: it reads the field here.</p>
 *
 * <p>Colours are {@code {r, g, b, a}} in 0–1; sizes are pixels. Fields are public and mutable
 * because the theme writes them; nothing reads them by name in the frame loop. Each field's
 * theme name is the catalogue entry bound to it, e.g. {@code buttonHover} is
 * {@code button.fill.hover}.</p>
 */
public final class AegisStyle {

    /** A style holding the built-in defaults, before any theme has written to it. */
    public AegisStyle() {}

    // ── Focus, shared by every focusable widget ─────────────────────────────

    /** The ring drawn around the widget that has keyboard focus. */
    public final float[] focusRing      = { 0.36f, 0.62f, 0.94f, 1.0f };
    /** Ring thickness. */
    public float         focusRingWidth = 2.0f;
    /** Space between a widget's edge and its ring, so the ring reads as a ring, not a border. */
    public float         focusRingGap   = 2.0f;

    // ── Button ──────────────────────────────────────────────────────────────

    /** The button's background at rest. */
    public final float[] buttonFill     = { 0.20f, 0.23f, 0.29f, 1.0f };
    /** The background while the pointer is over it. */
    public final float[] buttonHover    = { 0.27f, 0.31f, 0.39f, 1.0f };
    /** The background while it is held down with the pointer over it. */
    public final float[] buttonPressed  = { 0.14f, 0.16f, 0.21f, 1.0f };
    /** The outline. */
    public final float[] buttonBorder   = { 0.38f, 0.42f, 0.52f, 1.0f };
    /** The label. */
    public final float[] buttonText     = { 0.90f, 0.92f, 0.95f, 1.0f };
    /** Outline thickness, drawn inside the button's edge; 0 for none. */
    public float         buttonBorderWidth = 1.0f;
    /** Corner radius. */
    public float         buttonRadius   = 6.0f;
    /** Space between the label and the button's left and right edges. */
    public float         buttonPaddingX = 14.0f;
    /** Space between the label's line box and the button's top and bottom edges. */
    public float         buttonPaddingY = 6.0f;

    // ── Checkbox ────────────────────────────────────────────────────────────

    /** The square's background, unticked. */
    public final float[] checkboxFill    = { 0.14f, 0.16f, 0.21f, 1.0f };
    /** The square's background while the pointer is over the checkbox. */
    public final float[] checkboxHover   = { 0.20f, 0.23f, 0.29f, 1.0f };
    /** The square's outline. */
    public final float[] checkboxBorder  = { 0.38f, 0.42f, 0.52f, 1.0f };
    /** The mark inside the square when ticked. */
    public final float[] checkboxMark    = { 0.36f, 0.62f, 0.94f, 1.0f };
    /** The label beside the square. */
    public final float[] checkboxText    = { 0.90f, 0.92f, 0.95f, 1.0f };
    /** Side of the square. */
    public float         checkboxSize    = 16.0f;
    /** Corner radius of the square. */
    public float         checkboxRadius  = 3.0f;
    /** How far the ticked mark sits inside the square's edge. */
    public float         checkboxMarkInset = 4.0f;
    /** Space between the square and its label. */
    public float         checkboxGap     = 8.0f;
    /** Space above and below the label's line box. */
    public float         checkboxPaddingY = 4.0f;

    // ── Slider ──────────────────────────────────────────────────────────────

    /** The track, the part the thumb has not covered. */
    public final float[] sliderTrack        = { 0.14f, 0.16f, 0.21f, 1.0f };
    /** The track from the minimum up to the thumb. */
    public final float[] sliderFill         = { 0.36f, 0.62f, 0.94f, 1.0f };
    /** The round thumb at rest. */
    public final float[] sliderThumb        = { 0.90f, 0.92f, 0.95f, 1.0f };
    /** The thumb while the pointer is over the slider. */
    public final float[] sliderThumbHover   = { 1.00f, 1.00f, 1.00f, 1.0f };
    /** The thumb while it is being dragged. */
    public final float[] sliderThumbPressed = { 0.70f, 0.74f, 0.80f, 1.0f };
    /** The value written beside the track. */
    public final float[] sliderText         = { 0.90f, 0.92f, 0.95f, 1.0f };
    /** Width a slider asks for; a stretching parent may give it more. */
    public float         sliderWidth        = 200.0f;
    /** Thickness of the track. */
    public float         sliderTrackHeight  = 4.0f;
    /** Diameter of the round thumb. */
    public float         sliderThumbSize    = 14.0f;
    /** Room kept at the right for the value, written as a whole number — enough for "-100". */
    public float         sliderValueWidth   = 44.0f;
    /** Space between the track and the value. */
    public float         sliderGap          = 8.0f;
    /** Space above and below the value's line box. */
    public float         sliderPaddingY     = 4.0f;

    // ── Text field ──────────────────────────────────────────────────────────

    /** The field's background. Text fields and text boxes share every {@code textField} value. */
    public final float[] textFieldFill          = { 0.14f, 0.16f, 0.21f, 1.0f };
    /** The background while the pointer is over the field. */
    public final float[] textFieldHover         = { 0.17f, 0.19f, 0.25f, 1.0f };
    /** The outline while the field does not have focus. */
    public final float[] textFieldBorder        = { 0.38f, 0.42f, 0.52f, 1.0f };
    /** The border while the field has focus — it stands in for the focus ring. */
    public final float[] textFieldBorderFocused = { 0.36f, 0.62f, 0.94f, 1.0f };
    /** The text typed into the field. */
    public final float[] textFieldText          = { 0.90f, 0.92f, 0.95f, 1.0f };
    /** The hint shown while the field is empty. */
    public final float[] textFieldPlaceholder   = { 0.50f, 0.54f, 0.62f, 1.0f };
    /** The blinking insertion bar. */
    public final float[] textFieldCaret         = { 0.90f, 0.92f, 0.95f, 1.0f };
    /** Behind selected text; translucent, so the text reads through it. */
    public final float[] textFieldSelection     = { 0.36f, 0.62f, 0.94f, 0.40f };
    /** Width a text field asks for; a stretching parent may give it more. */
    public float         textFieldWidth         = 200.0f;
    /** Corner radius. */
    public float         textFieldRadius        = 4.0f;
    /** Outline thickness, drawn inside the field's edge. */
    public float         textFieldBorderWidth   = 1.0f;
    /** Space between the text and the field's left and right edges. */
    public float         textFieldPaddingX      = 8.0f;
    /** Space between the text's line box and the field's top and bottom edges. */
    public float         textFieldPaddingY      = 6.0f;
    /** Width of the caret. */
    public float         textFieldCaretWidth    = 2.0f;

    // ── Label ────────────────────────────────────────────────────────────

    /** The label's text. */
    public final float[] labelText          = { 0.90f, 0.92f, 0.95f, 1.0f };
    /** Space above and below the text; a checkbox's, by default, so the two line up in a row. */
    public float         labelPaddingY      = 4.0f;

    // ── Separator ────────────────────────────────────────────────────────

    /** The line. */
    public final float[] separatorLine      = { 0.38f, 0.42f, 0.52f, 1.0f };
    /** How thick the line is. */
    public float         separatorThickness = 1.0f;
    /** Clear space on each side of the line, along the direction its parent stacks in. */
    public float         separatorMargin    = 4.0f;
}
