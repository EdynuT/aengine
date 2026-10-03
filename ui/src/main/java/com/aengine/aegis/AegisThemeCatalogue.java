package com.aengine.aegis;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Everything a theme may set — the closed list, step 3f part 1.
 *
 * <p>Each entry is a name, a type and a default. The loader checks a theme's names against
 * this list, falls back to these defaults for what a theme leaves out, and writes what it
 * resolves into the {@link AegisStyle} field the entry is bound to. Nothing outside this list
 * is themeable: a name a theme uses that is not here is a mistake to report, not a property to
 * invent (§7, <em>Scope discipline</em>).</p>
 *
 * <h2>Names</h2>
 *
 * <p>{@code kind.part.state} — {@code button.fill.hover}. The first part is the kind of
 * widget the entry dresses, which is what a panel-and-kind rule such as
 * {@code inspector.button.fill} matches against; {@code textfield} dresses text fields and
 * text boxes alike, and {@code focus} every widget that draws a focus ring.</p>
 *
 * <p>The <strong>palette</strong> — {@code accent}, {@code text}, {@code surface} and the rest
 * — is bound to no field. Widgets never read it; the other entries point at it.</p>
 *
 * <h2>Defaults point at the palette</h2>
 *
 * <p>A widget entry's default is a reference, {@code @global.surface.raised}, not a colour.
 * That is what lets a theme written before a widget existed still dress it: the new widget's
 * defaults resolve against that theme's palette. Only values with no palette colour to stand
 * for — the white of a hovered slider thumb, the translucent selection — are written out.</p>
 *
 * <h2>Format</h2>
 *
 * <p>Every entry records the {@link #FORMAT} it arrived in. A theme declares the format it was
 * written against, and entries newer than that are what the loader is to report as unset — how
 * an engine update tells a theme author what is new (§7, <em>How a custom theme survives an
 * engine update</em>): {@link AegisTheme} notes, for a theme declaring an older format, the
 * entries added since that it does not set.</p>
 *
 * <p>The format stays 1 while the framework is in development: widgets and entries added
 * before release arrive in format 1, since nobody yet has a theme they could break. It is
 * raised only after release, and rarely — each raise flags every custom theme.</p>
 *
 * <p>Built once, when the class loads; read at theme load, never in the frame loop.</p>
 */
public final class AegisThemeCatalogue {

    /** The catalogue's current format. Raised when entries are added; each entry records its own. */
    public static final int FORMAT = 1;

    /** What kind of value an entry holds. */
    public enum Type {
        /** {@code "#RRGGBB"} or {@code "#RRGGBBAA"}. */
        COLOUR,
        /** A number of pixels. */
        SIZE
    }

    /** Where a resolved colour goes: the style's array for it, which the loader copies into. */
    @FunctionalInterface
    public interface ColourField {
        /**
         * Picks the colour field out of a style.
         *
         * @param style the style being written
         * @return the style's own {@code float[4]}, which the loader overwrites in place
         */
        float[] of(AegisStyle style);
    }

    /** Where a resolved size goes. */
    @FunctionalInterface
    public interface SizeField {
        /**
         * Writes the size into its field of a style.
         *
         * @param style the style being written
         * @param value the resolved size in pixels
         */
        void set(AegisStyle style, float value);
    }

    /**
     * One themeable property.
     *
     * @param name         its name, as a theme writes it
     * @param type         colour or size
     * @param defaultValue what it is when a theme does not set it — a value or an
     *                     {@code @reference}, written as a theme would write it
     * @param since        the {@link #FORMAT} it arrived in
     * @param colourField  where a colour goes; {@code null} for sizes and for the palette
     * @param sizeField    where a size goes; {@code null} for colours
     */
    public record Entry(String name, Type type, String defaultValue, int since,
                        ColourField colourField, SizeField sizeField) {

        /**
         * Whether this is a palette colour: one no widget reads, there for others to point at.
         *
         * @return {@code true} for a palette entry
         */
        public boolean isPalette() { return type == Type.COLOUR && colourField == null; }

        /**
         * The kind of widget it dresses — the part before the first dot. Empty for the palette.
         *
         * @return {@code button}, {@code checkbox}, {@code slider}, {@code textfield},
         *         {@code focus}, or empty
         */
        public String widgetKind() {
            if (isPalette()) return "";
            int dot = name.indexOf('.');
            return dot < 0 ? name : name.substring(0, dot);
        }
    }

    private static final List<Entry>        ENTRIES = new ArrayList<>();
    private static final Map<String, Entry> BY_NAME = new HashMap<>();

    static {
        // ── Palette ─────────────────────────────────────────────────────────────────────
        palette("accent",         "#5C9EF0");   // focus, a ticked checkbox, a slider's fill, selection
        palette("text",           "#E6EBF2");
        palette("text.dim",       "#808A9E");   // placeholders
        palette("surface",        "#242936");   // sunken: inside fields, empty slider track
        palette("surface.raised", "#333B4A");   // raised: a resting button
        palette("surface.hover",  "#454F63");   // raised, under the pointer
        palette("border",         "#616B85");

        // ── Focus, drawn by every focusable widget but the text field ────────────────────
        colour("focus.ring",       "@global.accent", s -> s.focusRing);
        size  ("focus.ring.width", "2",              (s, v) -> s.focusRingWidth = v);
        size  ("focus.ring.gap",   "2",              (s, v) -> s.focusRingGap = v);

        // ── Button ─────────────────────────────────────────────────────────────────────
        colour("button.fill",         "@global.surface.raised", s -> s.buttonFill);
        colour("button.fill.hover",   "@global.surface.hover",  s -> s.buttonHover);
        colour("button.fill.pressed", "@global.surface",        s -> s.buttonPressed);
        colour("button.border",       "@global.border",         s -> s.buttonBorder);
        colour("button.text",         "@global.text",           s -> s.buttonText);
        size  ("button.border.width", "1",  (s, v) -> s.buttonBorderWidth = v);
        size  ("button.radius",       "6",  (s, v) -> s.buttonRadius = v);
        size  ("button.padding.x",    "14", (s, v) -> s.buttonPaddingX = v);
        size  ("button.padding.y",    "6",  (s, v) -> s.buttonPaddingY = v);

        // ── Checkbox ───────────────────────────────────────────────────────────────────
        colour("checkbox.fill",       "@global.surface",        s -> s.checkboxFill);
        colour("checkbox.fill.hover", "@global.surface.raised", s -> s.checkboxHover);
        colour("checkbox.border",     "@global.border",         s -> s.checkboxBorder);
        colour("checkbox.mark",       "@global.accent",         s -> s.checkboxMark);
        colour("checkbox.text",       "@global.text",           s -> s.checkboxText);
        size  ("checkbox.size",       "16", (s, v) -> s.checkboxSize = v);
        size  ("checkbox.radius",     "3",  (s, v) -> s.checkboxRadius = v);
        size  ("checkbox.mark.inset", "4",  (s, v) -> s.checkboxMarkInset = v);
        size  ("checkbox.gap",        "8",  (s, v) -> s.checkboxGap = v);
        size  ("checkbox.padding.y",  "4",  (s, v) -> s.checkboxPaddingY = v);

        // ── Slider ─────────────────────────────────────────────────────────────────────
        colour("slider.track",         "@global.surface", s -> s.sliderTrack);
        colour("slider.fill",          "@global.accent",  s -> s.sliderFill);
        colour("slider.thumb",         "@global.text",    s -> s.sliderThumb);
        colour("slider.thumb.hover",   "#FFFFFF",         s -> s.sliderThumbHover);
        colour("slider.thumb.pressed", "#B3BDCC",         s -> s.sliderThumbPressed);
        colour("slider.text",          "@global.text",    s -> s.sliderText);
        size  ("slider.width",         "200", (s, v) -> s.sliderWidth = v);
        size  ("slider.track.height",  "4",   (s, v) -> s.sliderTrackHeight = v);
        size  ("slider.thumb.size",    "14",  (s, v) -> s.sliderThumbSize = v);
        size  ("slider.value.width",   "44",  (s, v) -> s.sliderValueWidth = v);
        size  ("slider.gap",           "8",   (s, v) -> s.sliderGap = v);
        size  ("slider.padding.y",     "4",   (s, v) -> s.sliderPaddingY = v);

        // ── Text field and text box ────────────────────────────────────────────────────
        colour("textfield.fill",           "@global.surface",  s -> s.textFieldFill);
        colour("textfield.fill.hover",     "#2B3040",          s -> s.textFieldHover);
        colour("textfield.border",         "@global.border",   s -> s.textFieldBorder);
        colour("textfield.border.focused", "@global.accent",   s -> s.textFieldBorderFocused);
        colour("textfield.text",           "@global.text",     s -> s.textFieldText);
        colour("textfield.placeholder",    "@global.text.dim", s -> s.textFieldPlaceholder);
        colour("textfield.caret",          "@global.text",     s -> s.textFieldCaret);
        colour("textfield.selection",      "#5C9EF066",        s -> s.textFieldSelection);
        size  ("textfield.width",          "200", (s, v) -> s.textFieldWidth = v);
        size  ("textfield.radius",         "4",   (s, v) -> s.textFieldRadius = v);
        size  ("textfield.border.width",   "1",   (s, v) -> s.textFieldBorderWidth = v);
        size  ("textfield.padding.x",      "8",   (s, v) -> s.textFieldPaddingX = v);
        size  ("textfield.padding.y",      "6",   (s, v) -> s.textFieldPaddingY = v);
        size  ("textfield.caret.width",    "2",   (s, v) -> s.textFieldCaretWidth = v);

        // ── Label ──────────────────────────────────────────────────────────────────────
        colour("label.text",      "@global.text", s -> s.labelText);
        size  ("label.padding.y", "4", (s, v) -> s.labelPaddingY = v);   // as a checkbox's, so the two line up in a row

        // ── Separator ──────────────────────────────────────────────────────────────────
        colour("separator.line",      "@global.border", s -> s.separatorLine);
        size  ("separator.thickness", "1", (s, v) -> s.separatorThickness = v);
        size  ("separator.margin",    "4", (s, v) -> s.separatorMargin = v);   // clear space on each side of the line
    }

    private AegisThemeCatalogue() { }

    /**
     * Every entry, in the order declared: the palette, then each widget's.
     *
     * @return a read-only view of the catalogue
     */
    public static List<Entry> entries() { return Collections.unmodifiableList(ENTRIES); }

    /**
     * The entry with this name, or {@code null} if nothing by that name is themeable.
     *
     * @param name the full name as a theme writes it, e.g. {@code "button.fill.hover"}
     * @return the entry, or {@code null}
     */
    public static Entry find(String name) { return BY_NAME.get(name); }

    // -----------------------------------------------------------------------------------
    // Declaring
    // -----------------------------------------------------------------------------------

    private static void palette(String name, String defaultValue) {
        add(new Entry(name, Type.COLOUR, defaultValue, 1, null, null));
    }

    private static void colour(String name, String defaultValue, ColourField field) {
        add(new Entry(name, Type.COLOUR, defaultValue, 1, field, null));
    }

    private static void size(String name, String defaultValue, SizeField field) {
        add(new Entry(name, Type.SIZE, defaultValue, 1, null, field));
    }

    private static void add(Entry entry) {
        if (BY_NAME.putIfAbsent(entry.name(), entry) != null) {
            throw new IllegalStateException("Theme catalogue declares \"" + entry.name() + "\" twice.");
        }
        ENTRIES.add(entry);
    }
}
