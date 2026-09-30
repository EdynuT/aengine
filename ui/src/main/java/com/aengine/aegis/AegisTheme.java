package com.aengine.aegis;

import com.aengine.utils.Logger;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;
import com.google.gson.stream.JsonReader;

import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * A theme, read and resolved — step 3f part 2.
 *
 * <p>Built once, from a file with {@link #read} or with nothing at all by {@link #factory}, and
 * handed to {@link AegisWidgets#applyTheme}, which asks it for the style every widget shares
 * and for each widget's own. All the work of names — parsing, following references, matching
 * paths — happens here and then; drawing only ever reads the {@link AegisStyle} fields it
 * produced (§7, <em>Resolution pipeline</em>).</p>
 *
 * <h2>What a theme file holds</h2>
 *
 * <p>The format is set out in §7 of the design document. In short, four kinds of entry:</p>
 * <ul>
 *   <li><b>metadata</b> — {@code name}, {@code description}, {@code format};</li>
 *   <li><b>{@code "global"}</b> — the palette and each kind of widget's look, for everyone;</li>
 *   <li><b>local variables</b> — keys without a dot, which paint nothing until referenced;</li>
 *   <li><b>scoped rules</b> — keys with a dot: {@code panel.kind.property} for every widget of
 *       a kind inside a panel, {@code panel.widget.property} for one widget.</li>
 * </ul>
 *
 * <p>A value is a colour ({@code "#RRGGBB"}, {@code "#RRGGBBAA"}), a size (a number of pixels)
 * or a reference: {@code "@global.accent"} to the global block, {@code "@danger"} to a local
 * variable. Precedence, strongest first: one widget's rule, then panel and kind — the deeper
 * panel winning — then global, then the catalogue's default.</p>
 *
 * <h2>What goes wrong, and what happens</h2>
 *
 * <p>A file that is not JSON, or not an object, cannot be used at all: {@link #read} throws
 * {@link Unusable}, and the caller moves to the next theme in line. Everything else is one
 * property wrong — a colour that does not parse, a reference to nothing, a cycle, a name the
 * catalogue does not know, a rule that reaches no widget — and falls back, that property
 * alone, with a warning in the log. Warnings are said once each, however many widgets share
 * the mistake. Step 3f part 3 makes them precise, with line numbers.</p>
 */
public final class AegisTheme {

    /** A theme file nothing usable can be built from: not JSON, or not a JSON object. */
    public static final class Unusable extends Exception {
        Unusable(String message, Throwable cause) { super(message, cause); }
    }

    /** A scoped rule: the key as written, the value as written, and whether it reached a widget. */
    private static final class Rule {
        final String key;
        final String value;
        boolean matched;
        Rule(String key, String value) { this.key = key; this.value = value; }
    }

    private static final String GLOBAL_PREFIX = "global.";

    private final String source;        // where it came from, for warnings: a path, or "factory"
    private final String name;
    private final String description;
    private final int    format;

    private final Map<String, String> global = new LinkedHashMap<>();   // as written
    private final Map<String, String> locals = new LinkedHashMap<>();   // as written
    private final List<Rule>          rules  = new ArrayList<>();

    /** Every catalogue entry's value once global and defaults are resolved: a {@code float[4]} or a {@code Float}. */
    private final Map<String, Object> resolvedGlobal = new HashMap<>();

    /** Warnings already said, so a mistake many widgets share is reported once. */
    private final Set<String> warned = new HashSet<>();

    private AegisStyle globalStyle;

    private AegisTheme(String source, String name, String description, int format) {
        this.source      = source;
        this.name        = name;
        this.description = description;
        this.format      = format;
    }

    // -----------------------------------------------------------------------------------
    // Making one
    // -----------------------------------------------------------------------------------

    /**
     * The factory theme: nothing set, every property at the catalogue's default. What the
     * editor wears when no theme file is found, and the last resort behind any value that
     * fails to resolve.
     *
     * @return a new factory theme
     */
    public static AegisTheme factory() {
        AegisTheme theme = new AegisTheme("factory", "Factory", "The defaults built into the engine.",
                                          AegisThemeCatalogue.FORMAT);
        theme.resolveGlobal();
        return theme;
    }

    /**
     * Reads a theme file. Problems with single properties are logged, not thrown; see the
     * class comment.
     *
     * @param file the theme file, UTF-8 JSON
     * @return the theme, resolved and ready to apply
     * @throws IOException if the file cannot be read — missing, or not readable
     * @throws Unusable    if it is not JSON, or not a JSON object: nothing can be built from it
     */
    public static AegisTheme read(Path file) throws IOException, Unusable {
        try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            return parse(reader, file.toString());
        }
    }

    /**
     * Reads a theme from any reader; {@code source} names it in warnings. JSON comments are
     * tolerated. The reader is not closed.
     *
     * @param reader the JSON text
     * @param source what to call it in warnings, e.g. a file path
     * @return the theme, resolved and ready to apply
     * @throws Unusable if it is not JSON, or not a JSON object
     */
    public static AegisTheme parse(Reader reader, String source) throws Unusable {
        JsonElement root;
        try {
            JsonReader json = new JsonReader(reader);
            json.setLenient(true);   // tolerate a comment someone added; the shipped themes have none
            root = JsonParser.parseReader(json);
        } catch (JsonParseException e) {
            throw new Unusable(source + " is not valid JSON: " + e.getMessage(), e);
        }
        if (!root.isJsonObject()) {
            throw new Unusable(source + " must hold one JSON object, { ... }, at the top.", null);
        }
        JsonObject top = root.getAsJsonObject();

        AegisTheme theme = new AegisTheme(source,
            text(top, "name", source), text(top, "description", ""),
            top.has("format") && top.get("format").isJsonPrimitive() ? top.get("format").getAsInt() : 1);

        for (Map.Entry<String, JsonElement> e : top.entrySet()) {
            String key = e.getKey();
            if (key.equals("name") || key.equals("description") || key.equals("format")) continue;

            if (key.equals("global")) {
                theme.readGlobal(e.getValue());
                continue;
            }
            String value = theme.valueText(key, e.getValue());
            if (value == null) continue;

            if (key.indexOf('.') < 0) theme.locals.put(key, value);
            else                      theme.rules.add(new Rule(key, value));
        }

        theme.resolveGlobal();
        return theme;
    }

    private void readGlobal(JsonElement element) {
        if (!element.isJsonObject()) {
            warn("\"global\" must be an object, { ... }; it is ignored");
            return;
        }
        for (Map.Entry<String, JsonElement> e : element.getAsJsonObject().entrySet()) {
            String key = e.getKey();
            if (AegisThemeCatalogue.find(key) == null) {
                warn("global." + key + " is not something a theme can set; it is ignored");
                continue;
            }
            String value = valueText("global." + key, e.getValue());
            if (value != null) global.put(key, value);
        }
    }

    /** A value as written, as text — a string's contents, a number's digits — or null, warned, for anything else. */
    private String valueText(String key, JsonElement element) {
        if (element.isJsonPrimitive()) {
            JsonPrimitive p = element.getAsJsonPrimitive();
            if (p.isString() || p.isNumber()) return p.getAsString();
        }
        warn(key + " must be a colour, a number or a reference; it is ignored");
        return null;
    }

    private static String text(JsonObject top, String key, String otherwise) {
        JsonElement e = top.get(key);
        return e != null && e.isJsonPrimitive() ? e.getAsString() : otherwise;
    }

    // -----------------------------------------------------------------------------------
    // What it says
    // -----------------------------------------------------------------------------------

    /**
     * The name a theme list shows.
     *
     * @return the {@code name} the file declares, or its source if it declares none
     */
    public String name() { return name; }

    /**
     * What the theme's author wrote about it.
     *
     * @return the {@code description}, or empty
     */
    public String description() { return description; }

    /**
     * The catalogue format the theme was written against.
     *
     * @return the declared {@code format}, or 1 if the file declares none
     */
    public int format() { return format; }

    /**
     * Where it came from: a file path, or {@code "factory"}.
     *
     * @return the source, as used in warnings
     */
    public String source() { return source; }

    /** The style every widget draws with unless a scoped rule gives it its own. Made once. */
    AegisStyle globalStyle() {
        if (globalStyle == null) {
            globalStyle = new AegisStyle();
            for (AegisThemeCatalogue.Entry entry : AegisThemeCatalogue.entries()) {
                if (!entry.isPalette()) write(globalStyle, entry, resolvedGlobal.get(entry.name()));
            }
        }
        return globalStyle;
    }

    /**
     * A widget's own style, or {@code null} when no scoped rule reaches it and it should share
     * the global one.
     *
     * @param path  the ids from the top of the tree down to the widget
     * @param ownId whether the last id in {@code path} is the widget's own, rather than its
     *              nearest named container's
     * @param kind  the kind the catalogue dresses it as — {@code button}, {@code textfield}...
     */
    AegisStyle styleFor(List<String> path, boolean ownId, String kind) {
        if (rules.isEmpty() || kind.isEmpty()) return null;

        // Which rule wins for each property, and how strongly: one widget's own rule is 1000,
        // panel and kind the depth of the panel, so a nearer panel beats a farther one.
        Map<AegisThemeCatalogue.Entry, Rule>    chosen   = null;
        Map<AegisThemeCatalogue.Entry, Integer> strength = null;

        String full = String.join(".", path);
        int panels = ownId ? path.size() - 1 : path.size();

        for (Rule rule : rules) {
            AegisThemeCatalogue.Entry entry = null;
            int power = -1;

            if (ownId && rule.key.startsWith(full + ".")) {
                rule.matched = true;
                entry = propertyOf(kind, rule.key.substring(full.length() + 1), rule.key);
                power = 1000;
            } else {
                for (int depth = panels; depth >= 1 && entry == null; depth--) {
                    String panel = String.join(".", path.subList(0, depth));
                    String rest  = restAfter(rule.key, panel + "." + kind + ".");
                    if (rest == null) {
                        String focus = restAfter(rule.key, panel + ".focus.");
                        if (focus != null) rest = "focus." + focus;
                    } else {
                        rest = kind + "." + rest;
                    }
                    if (rest == null) continue;

                    rule.matched = true;
                    entry = AegisThemeCatalogue.find(rest);
                    if (entry == null) warn(rule.key + " — " + kind + " has no property "
                                            + rest.substring(rest.indexOf('.') + 1) + "; it is ignored");
                    power = depth;
                }
            }
            if (entry == null || entry.isPalette()) continue;

            if (chosen == null) {
                chosen   = new HashMap<>();
                strength = new HashMap<>();
            }
            Integer held = strength.get(entry);
            if (held == null || power > held) {
                chosen.put(entry, rule);
                strength.put(entry, power);
            }
        }
        if (chosen == null) return null;

        AegisStyle style = new AegisStyle();
        for (AegisThemeCatalogue.Entry entry : AegisThemeCatalogue.entries()) {
            if (entry.isPalette()) continue;
            Rule rule = chosen.get(entry);
            Object value = rule != null
                ? resolveEntry(entry, rule.value, rule.key)
                : resolvedGlobal.get(entry.name());
            write(style, entry, value);
        }
        return style;
    }

    /** The catalogue entry a widget rule's property names — {@code fill.hover} on a button — or null, warned. */
    private AegisThemeCatalogue.Entry propertyOf(String kind, String property, String key) {
        String name = property.startsWith("focus.") ? property : kind + "." + property;
        AegisThemeCatalogue.Entry entry = AegisThemeCatalogue.find(name);
        if (entry == null) warn(key + " — " + kind + " has no property " + property + "; it is ignored");
        return entry;
    }

    private static String restAfter(String key, String prefix) {
        return key.startsWith(prefix) && key.length() > prefix.length() ? key.substring(prefix.length()) : null;
    }

    /**
     * Warns about every scoped rule that reached no widget — most often an id renamed in an
     * engine update, or a typo in a path. Called once the theme has been applied to a tree.
     */
    void reportUnmatchedRules() {
        for (Rule rule : rules) {
            if (!rule.matched) warn(rule.key + " reaches no widget; it is ignored");
        }
    }

    // -----------------------------------------------------------------------------------
    // Resolving
    // -----------------------------------------------------------------------------------

    /** Resolves every catalogue entry against the global block, falling back to its default. */
    private void resolveGlobal() {
        for (AegisThemeCatalogue.Entry entry : AegisThemeCatalogue.entries()) {
            String written = global.get(entry.name());
            resolvedGlobal.put(entry.name(), written != null
                ? resolveEntry(entry, written, "global." + entry.name())
                : resolveEntry(entry, entry.defaultValue(), "default of " + entry.name()));
        }
    }

    /**
     * An entry's value from what was written for it. If that does not resolve, the entry's
     * default resolved against this theme; if not even that — the default leans on a palette
     * colour this theme broke — the factory's value, which always resolves.
     */
    private Object resolveEntry(AegisThemeCatalogue.Entry entry, String written, String where) {
        Object value = resolve(written, entry.type(), where, new HashSet<>());
        if (value != null) return value;

        if (!written.equals(entry.defaultValue())) {
            value = resolve(entry.defaultValue(), entry.type(), "default of " + entry.name(), new HashSet<>());
            if (value != null) return value;
        }
        return FACTORY_VALUES.get(entry.name());
    }

    /**
     * Follows references until a value, and parses it as {@code type}: a {@code float[4]} for
     * a colour, a {@code Float} for a size. Null, warned, if it cannot.
     */
    private Object resolve(String written, AegisThemeCatalogue.Type type, String where, Set<String> visiting) {
        if (written.startsWith("@")) {
            String ref = written.substring(1);
            String target;
            if (ref.startsWith(GLOBAL_PREFIX)) {
                String name = ref.substring(GLOBAL_PREFIX.length());
                target = global.get(name);
                if (target == null) {
                    AegisThemeCatalogue.Entry entry = AegisThemeCatalogue.find(name);
                    target = entry != null ? entry.defaultValue() : null;
                }
            } else {
                target = locals.get(ref);
            }
            if (target == null) {
                warn(where + " refers to " + written + ", which does not exist; using the default");
                return null;
            }
            if (!visiting.add(ref)) {
                warn(where + " is part of a reference cycle through " + written + "; using the default");
                return null;
            }
            return resolve(target, type, where, visiting);
        }

        Object value = type == AegisThemeCatalogue.Type.COLOUR ? parseColour(written) : parseSize(written);
        if (value == null) {
            warn(where + " — expected " + (type == AegisThemeCatalogue.Type.COLOUR
                ? "a colour like \"#5C9EF0\"" : "a number of pixels, 0 or more")
                + ", got \"" + written + "\"; using the default");
        }
        return value;
    }

    /** {@code #RRGGBB} or {@code #RRGGBBAA} to {r, g, b, a} in 0–1, or null. */
    private static float[] parseColour(String text) {
        if (!text.startsWith("#") || (text.length() != 7 && text.length() != 9)) return null;
        float[] rgba = { 0.0f, 0.0f, 0.0f, 1.0f };
        for (int i = 0; i < (text.length() - 1) / 2; i++) {
            int hi = Character.digit(text.charAt(1 + i * 2), 16);
            int lo = Character.digit(text.charAt(2 + i * 2), 16);
            if (hi < 0 || lo < 0) return null;
            rgba[i] = (hi * 16 + lo) / 255.0f;
        }
        return rgba;
    }

    /** A number of pixels, 0 or more, or null. */
    private static Float parseSize(String text) {
        try {
            float value = Float.parseFloat(text);
            return value >= 0.0f && Float.isFinite(value) ? value : null;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** Writes a resolved value into the style field its entry is bound to. */
    private static void write(AegisStyle style, AegisThemeCatalogue.Entry entry, Object value) {
        if (entry.type() == AegisThemeCatalogue.Type.COLOUR) {
            System.arraycopy((float[]) value, 0, entry.colourField().of(style), 0, 4);
        } else {
            entry.sizeField().set(style, (Float) value);
        }
    }

    private void warn(String message) {
        if (warned.add(message)) Logger.warn(Logger.System.UI, "%s: %s", source, message);
    }

    /**
     * Every entry's factory value, resolved once from the catalogue alone. The catalogue's
     * defaults always resolve — a default that did not would be a bug in the engine, caught the
     * first time this class loads.
     */
    private static final Map<String, Object> FACTORY_VALUES = new HashMap<>();

    static {
        AegisTheme bare = new AegisTheme("catalogue", "", "", AegisThemeCatalogue.FORMAT);
        for (AegisThemeCatalogue.Entry entry : AegisThemeCatalogue.entries()) {
            Object value = bare.resolve(entry.defaultValue(), entry.type(), entry.name(), new HashSet<>());
            if (value == null) {
                throw new IllegalStateException("The theme catalogue's default for " + entry.name()
                    + " does not resolve: " + entry.defaultValue());
            }
            FACTORY_VALUES.put(entry.name(), value);
        }
    }
}
