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

        // ── Spinner. Its caret, selection, radius, border width and padding are the text
        //    field's, so a spinner and a field line up in a panel.
        colour("spinner.fill",           "@global.surface",  s -> s.spinnerFill);
        colour("spinner.fill.hover",     "#2B3040",          s -> s.spinnerHover);
        colour("spinner.border",         "@global.border",   s -> s.spinnerBorder);
        colour("spinner.border.focused", "@global.accent",   s -> s.spinnerBorderFocused);
        colour("spinner.text",           "@global.text",     s -> s.spinnerText);
        colour("spinner.arrow",          "@global.text.dim", s -> s.spinnerArrow);
        colour("spinner.arrow.hover",    "@global.text",     s -> s.spinnerArrowHover);
        size  ("spinner.width",          "120", (s, v) -> s.spinnerWidth = v);
        size  ("spinner.button.width",   "20",  (s, v) -> s.spinnerButtonWidth = v);

        // ── Tabs: the bar of a tabbed pane ─────────────────────────────────────────────
        colour("tabs.fill",          "@global.surface",        s -> s.tabsFill);
        colour("tabs.fill.hover",    "@global.surface.hover",  s -> s.tabsHover);
        colour("tabs.fill.selected", "@global.surface.raised", s -> s.tabsSelected);
        colour("tabs.text",          "@global.text.dim",       s -> s.tabsText);
        colour("tabs.text.selected", "@global.text",           s -> s.tabsTextSelected);
        colour("tabs.accent",        "@global.accent",         s -> s.tabsAccent);   // the line marking the shown tab
        colour("tabs.border",        "@global.border",         s -> s.tabsBorder);   // the line under the bar
        size  ("tabs.padding.x",     "14", (s, v) -> s.tabsPaddingX = v);
        size  ("tabs.padding.y",     "6",  (s, v) -> s.tabsPaddingY = v);
        size  ("tabs.gap",           "2",  (s, v) -> s.tabsGap = v);
        size  ("tabs.radius",        "4",  (s, v) -> s.tabsRadius = v);
        size  ("tabs.accent.height", "2",  (s, v) -> s.tabsAccentHeight = v);

        // ── Split pane: its divider ────────────────────────────────────────────────────
        colour("split.line",       "@global.border", s -> s.splitLine);
        colour("split.line.hover", "@global.accent", s -> s.splitLineHover);   // under the pointer, or dragged
        size  ("split.size",       "8", (s, v) -> s.splitSize = v);            // the room the divider takes
        size  ("split.line.width", "2", (s, v) -> s.splitLineWidth = v);

        // ── Tree ───────────────────────────────────────────────────────────────────────
        colour("tree.text",          "@global.text",          s -> s.treeText);
        colour("tree.text.selected", "#FFFFFF",               s -> s.treeTextSelected);
        colour("tree.fill.selected", "#5C9EF066",             s -> s.treeSelected);
        colour("tree.fill.hover",    "#454F6399",             s -> s.treeHover);
        colour("tree.arrow",         "@global.text.dim",      s -> s.treeArrow);
        size  ("tree.indent",        "16", (s, v) -> s.treeIndent = v);   // each level; also the arrow's room
        size  ("tree.padding.x",     "4",  (s, v) -> s.treePaddingX = v);
        size  ("tree.padding.y",     "3",  (s, v) -> s.treePaddingY = v);

        // ── Overlay: popups, lists, menus and dialogs stand on it. Global values only. ────
        colour("overlay.fill",          "#2B3040",        s -> s.overlayFill);
        colour("overlay.border",        "@global.border", s -> s.overlayBorder);
        colour("overlay.shadow",        "#00000059",      s -> s.overlayShadow);
        colour("overlay.scrim",         "#00000073",      s -> s.overlayScrim);   // over what is below a dialog
        size  ("overlay.radius",        "6", (s, v) -> s.overlayRadius = v);
        size  ("overlay.shadow.offset", "4", (s, v) -> s.overlayShadowOffset = v);

        // ── Combo box, and its list ───────────────────────────────────────────────────
        colour("combo.fill",           "@global.surface.raised", s -> s.comboFill);
        colour("combo.fill.hover",     "@global.surface.hover",  s -> s.comboHover);
        colour("combo.border",         "@global.border",         s -> s.comboBorder);
        colour("combo.text",           "@global.text",           s -> s.comboText);
        colour("combo.arrow",          "@global.text.dim",       s -> s.comboArrow);
        colour("combo.highlight",      "@global.accent",         s -> s.comboHighlight);       // the item the open list points at
        colour("combo.highlight.text", "#FFFFFF",                s -> s.comboHighlightText);
        size  ("combo.padding.x",      "10", (s, v) -> s.comboPaddingX = v);
        size  ("combo.padding.y",      "6",  (s, v) -> s.comboPaddingY = v);
        size  ("combo.radius",         "4",  (s, v) -> s.comboRadius = v);
        size  ("combo.arrow.width",    "20", (s, v) -> s.comboArrowWidth = v);

        // ── Tooltip. Global values only: it belongs to no panel. ─────────────────────────
        colour("tooltip.fill",    "#1A1C26",        s -> s.tooltipFill);
        colour("tooltip.border",  "@global.border", s -> s.tooltipBorder);
        colour("tooltip.text",    "@global.text",   s -> s.tooltipText);
        size  ("tooltip.padding", "6",   (s, v) -> s.tooltipPadding = v);
        size  ("tooltip.radius",  "4",   (s, v) -> s.tooltipRadius = v);
        size  ("tooltip.width",   "320", (s, v) -> s.tooltipWidth = v);   // the widest; longer text wraps

        // ── Scroll bar, on any scrolling panel. Global values only for now: panels are
        //    not dressed by rules, so inspector.scrollbar.thumb reaches nothing yet.
        colour("scrollbar.thumb",       "@global.border",   s -> s.scrollbarThumb);
        colour("scrollbar.thumb.hover", "@global.text.dim", s -> s.scrollbarThumbHover);   // under the pointer, or dragged
        size  ("scrollbar.width", "4", (s, v) -> s.scrollbarWidth = v);
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
