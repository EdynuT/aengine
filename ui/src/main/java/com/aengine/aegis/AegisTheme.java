package com.aengine.aegis;

import com.aengine.utils.Logger;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;
import com.google.gson.stream.JsonReader;

import com.google.gson.stream.MalformedJsonException;

import java.io.IOException;
import java.io.Reader;
import java.io.StringReader;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

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
 * alone, with a warning in the log. Every warning names the line the mistake is written on,
 * and is said once, however many widgets share the mistake. A key written twice is pointed
 * out too, as information: JSON keeps the last one without a word.</p>
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
        boolean changesSomething;   // for at least one widget, it gives a value the widget would not otherwise have
        boolean broken;             // already reported as a problem, so never also called redundant
        Rule(String key, String value) { this.key = key; this.value = value; }
    }

    /** Something to say about the file: a problem, or a note that changes nothing. */
    private record Finding(int line, boolean problem, String message) {
    }

    private static final String GLOBAL_PREFIX = "global.";

    private final String source;        // where it came from, for warnings: a path, or "factory"
    private final String name;
    private final String description;
    private final int    format;
    private final AegisJsonLines lines; // where each key is written; empty for the factory theme

    private final Map<String, String> global = new LinkedHashMap<>();   // as written
    private final Map<String, String> locals = new LinkedHashMap<>();   // as written
    private final List<Rule>          rules  = new ArrayList<>();

    /** Keys whose value the file writes as a JSON number rather than a string — sizes, rightly. */
    private final Set<String> writtenAsNumber = new HashSet<>();

    /** Every catalogue entry's value once global and defaults are resolved: a {@code float[4]} or a {@code Float}. */
    private final Map<String, Object> resolvedGlobal = new HashMap<>();

    /** Mistakes already recorded, so one that many widgets share is reported once. */
    private final Set<String> warned = new HashSet<>();

    /** What {@link #report} will say, gathered while reading and while dressing widgets. */
    private final List<Finding> findings = new ArrayList<>();
    private boolean reported;

    private AegisStyle globalStyle;

    private AegisTheme(String source, String name, String description, int format, AegisJsonLines lines) {
        this.source      = source;
        this.name        = name;
        this.description = description;
        this.format      = format;
        this.lines       = lines;
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
                                          AegisThemeCatalogue.FORMAT, AegisJsonLines.scan(""));
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
        // Read whole, once: walked for where each key is written, then handed to Gson. A theme
        // is a few kilobytes.
        String text;
        try {
            StringWriter whole = new StringWriter();
            reader.transferTo(whole);
            text = whole.toString();
        } catch (IOException e) {
            throw new Unusable(source + " could not be read: " + e.getMessage(), e);
        }

        JsonElement root;
        try {
            JsonReader json = new JsonReader(new StringReader(text));
            json.setLenient(true);   // tolerate a comment someone added; the shipped themes have none
            root = JsonParser.parseReader(json);
        } catch (JsonParseException e) {
            throw new Unusable(syntaxError(source, e), e);
        }
        if (!root.isJsonObject()) {
            throw new Unusable(source + " must hold one JSON object, { ... }, at the top.", null);
        }
        JsonObject top = root.getAsJsonObject();

        JsonElement declared = top.get("format");
        Integer declaredFormat = declared != null ? wholeNumber(declared) : null;

        AegisTheme theme = new AegisTheme(source,
            text(top, "name", source), text(top, "description", ""),
            declaredFormat != null && declaredFormat >= 1 ? declaredFormat : 1,
            AegisJsonLines.scan(text));

        for (String[] path : theme.lines.repeated()) {
            List<Integer> at = theme.lines.lines(path);
            theme.note(String.join(".", path), String.join(".", path) + " is written on lines "
                + listOf(at) + "; only line " + at.get(at.size() - 1) + " counts");
        }

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

        theme.checkFormat(declared, declaredFormat);   // after "global" is read: it asks what the theme sets
        theme.resolveGlobal();
        return theme;
    }

    /**
     * What a theme's {@code format} says about its age, measured against the engine's. Older is
     * normal — the theme simply predates some properties, which keep their defaults — and is a
     * note listing them. Newer is a problem: it may use names this engine does not know yet.
     * Missing or not a whole number, the theme is read as format 1.
     */
    private void checkFormat(JsonElement declared, Integer declaredFormat) {
        int engine = AegisThemeCatalogue.FORMAT;

        if (declared == null) {
            note("format", "no \"format\"; read as format 1. Add \"format\": " + engine
                + " so a later engine can tell which properties this theme predates");
            return;
        }
        if (declaredFormat == null || declaredFormat < 1) {
            warn("format", "\"format\" must be a whole number, 1 or more, written without quotes; got "
                + declared + "; read as format 1");
            return;
        }
        if (declaredFormat > engine) {
            warn("format", "written for format " + declaredFormat + ", newer than this engine's "
                + engine + "; names it does not know may come from a later version and are ignored");
            return;
        }
        if (declaredFormat < engine) {
            List<String> later = new ArrayList<>();
            for (AegisThemeCatalogue.Entry entry : AegisThemeCatalogue.entries()) {
                if (entry.since() > declaredFormat && !global.containsKey(entry.name())) later.add(entry.name());
            }
            if (!later.isEmpty()) {
                note("format", "written for format " + declaredFormat + "; this engine is at format " + engine
                    + ". Added since, and at their defaults unless a rule sets them: " + String.join(", ", later));
            }
        }
    }

    /** A JSON number with no fraction, or null. A string is not a number, even "1". */
    private static Integer wholeNumber(JsonElement e) {
        if (!e.isJsonPrimitive() || !e.getAsJsonPrimitive().isNumber()) return null;
        try {
            return new java.math.BigDecimal(e.getAsString().trim()).intValueExact();
        } catch (NumberFormatException | ArithmeticException ex) {
            return null;
        }
    }

    /**
     * Gson's complaint, said the way the other warnings are: the file and the line first.
     * Gson writes "Expected ':' at line 5 column 12 path $.global.accent".
     */
    private static String syntaxError(String source, JsonParseException e) {
        Throwable cause = e.getCause() instanceof MalformedJsonException ? e.getCause() : e;
        String message = String.valueOf(cause.getMessage());
        Matcher m = GSON_LOCATION.matcher(message);
        if (!m.find()) return source + " is not valid JSON: " + message;
        String what = message.substring(0, m.start()).trim();
        // Gson reports where it noticed, which is often past the mistake: a missing comma at
        // the end of one line is noticed on the next.
        return source + ":" + m.group(1) + ": not valid JSON, column " + m.group(2) + ": " + what
            + " (if this line looks right, check the end of the one before: a missing comma or quote)";
    }

    private static final Pattern GSON_LOCATION = Pattern.compile(" at line (\\d+) column (\\d+)");

    /** "7", "7 and 22", "7, 15 and 22". */
    private static String listOf(List<Integer> numbers) {
        StringBuilder s = new StringBuilder();
        for (int i = 0; i < numbers.size(); i++) {
            if (i > 0) s.append(i == numbers.size() - 1 ? " and " : ", ");
            s.append(numbers.get(i));
        }
        return s.toString();
    }

    private void readGlobal(JsonElement element) {
        if (!element.isJsonObject()) {
            warn("global", "\"global\" must be an object, { ... }; it is ignored");
            return;
        }
        for (Map.Entry<String, JsonElement> e : element.getAsJsonObject().entrySet()) {
            String key = e.getKey();
            if (AegisThemeCatalogue.find(key) == null) {
                warn("global." + key, "global." + key + " is not something a theme can set; it is ignored");
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
            if (p.isNumber()) writtenAsNumber.add(key);
            if (p.isString() || p.isNumber()) return p.getAsString();
        }
        warn(key, key + " must be a colour, a number or a reference; it is ignored");
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
     * @return the declared {@code format}, or 1 if the file declares none or not a whole number
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
        Map<AegisThemeCatalogue.Entry, Rule>    runnerUp = null;   // what would win if the chosen rule were gone
        Map<AegisThemeCatalogue.Entry, Integer> runnerUpStrength = null;

        String full = String.join(".", path);
        int panels = ownId ? path.size() - 1 : path.size();

        for (Rule rule : rules) {
            AegisThemeCatalogue.Entry entry = null;
            int power = -1;

            if (ownId && rule.key.startsWith(full + ".")) {
                rule.matched = true;
                entry = propertyOf(kind, rule.key.substring(full.length() + 1), rule.key);
                if (entry == null) rule.broken = true;
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
                    if (entry == null) rule.broken = true;
                    if (entry == null) warn(rule.key, rule.key + " — " + kind + " has no property "
                                            + rest.substring(rest.indexOf('.') + 1) + "; it is ignored");
                    power = depth;
                }
            }
            if (entry == null || entry.isPalette()) continue;

            if (chosen == null) {
                chosen   = new HashMap<>();
                strength = new HashMap<>();
                runnerUp = new HashMap<>();
                runnerUpStrength = new HashMap<>();
            }
            Integer held = strength.get(entry);
            if (held == null || power > held) {
                Rule beaten = chosen.put(entry, rule);
                if (beaten != null) {
                    runnerUp.put(entry, beaten);
                    runnerUpStrength.put(entry, held);
                }
                strength.put(entry, power);
            } else {
                Integer second = runnerUpStrength.get(entry);
                if (second == null || power > second) {
                    runnerUp.put(entry, rule);
                    runnerUpStrength.put(entry, power);
                }
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
            if (rule != null && !rule.broken
                    && resolve(rule.value, entry.type(), rule.key, rule.key, new HashSet<>()) == null) {
                rule.broken = true;   // its value is wrong, and already warned about
            }
            if (rule != null && !rule.changesSomething && !rule.broken) {
                // Does it matter here? Only if, without it, this widget would look different.
                Rule second = runnerUp.get(entry);
                Object without = second != null
                    ? resolveEntry(entry, second.value, second.key)
                    : resolvedGlobal.get(entry.name());
                if (!sameValue(value, without)) rule.changesSomething = true;
            }
            write(style, entry, value);
        }
        return style;
    }

    private static boolean sameValue(Object a, Object b) {
        if (a instanceof float[] fa && b instanceof float[] fb) return Arrays.equals(fa, fb);
        return a.equals(b);
    }

    /** The catalogue entry a widget rule's property names — {@code fill.hover} on a button — or null, warned. */
    private AegisThemeCatalogue.Entry propertyOf(String kind, String property, String key) {
        String name = property.startsWith("focus.") ? property : kind + "." + property;
        AegisThemeCatalogue.Entry entry = AegisThemeCatalogue.find(name);
        if (entry == null) warn(key, key + " — " + kind + " has no property " + property + "; it is ignored");
        return entry;
    }

    private static String restAfter(String key, String prefix) {
        return key.startsWith(prefix) && key.length() > prefix.length() ? key.substring(prefix.length()) : null;
    }

    /**
     * Says, in one block in the log, everything worth saying about the file — called once the
     * theme has been applied to a tree, since only then is it known which rules reached a
     * widget. Problems are listed first, then notes, each in the order of the file's lines.
     *
     * <p>Problems are warnings, notes are information. A theme with a problem opens its block
     * with a warning; one without, with a line of information saying so. The factory
     * theme has no file and says nothing. Only the first call says anything.</p>
     */
    void report() {
        if (reported || source.equals("factory")) return;
        reported = true;

        for (Rule rule : rules) {
            if (!rule.matched) {
                warn(rule.key, rule.key + " reaches no widget; it is ignored");
            } else if (!rule.changesSomething && !rule.broken) {
                // A broken rule is reported as a problem; calling it redundant as well would be
                // the same mistake said twice, and the wrong way round.
                note(rule.key, rule.key + " gives every widget it reaches the value it would have anyway; "
                    + "it can be removed");
            }
        }
        for (String local : locals.keySet()) {
            if (!isReferenced(local)) {
                note(local, local + " is a variable nothing refers to (@" + local + "); it paints nothing");
            }
        }
        // The palette is left out: writing every palette colour out, even at its default, is
        // how a theme says what its colours are, and is worth doing.
        for (Map.Entry<String, String> e : global.entrySet()) {
            AegisThemeCatalogue.Entry entry = AegisThemeCatalogue.find(e.getKey());
            Object asDefault = resolve(entry.defaultValue(), entry.type(), "", "", new HashSet<>());
            String key = "global." + e.getKey();
            boolean broken = resolve(e.getValue(), entry.type(), key, key, new HashSet<>()) == null;   // already warned
            if (!entry.isPalette() && !broken && asDefault != null
                    && sameValue(resolvedGlobal.get(entry.name()), asDefault)) {
                note("global." + e.getKey(), "global." + e.getKey()
                    + " sets the value it has by default; it can be removed");
            }
        }

        findings.sort((a, b) -> a.problem() != b.problem() ? (a.problem() ? -1 : 1)
                                : Integer.compare(lineOrLast(a), lineOrLast(b)));
        int problems = 0;
        for (Finding f : findings) if (f.problem()) problems++;
        int notes = findings.size() - problems;

        if (problems > 0) {
            Logger.warn(Logger.System.UI, "%s: %s", source, count(problems, "problem")
                + (notes > 0 ? ", " + count(notes, "note") : "") + ":");
            for (Finding f : findings) {
                if (f.problem()) Logger.warn(Logger.System.UI, "  %s", lineText(f));
                else             Logger.info(Logger.System.UI, "  %s", lineText(f));
            }
        } else if (notes > 0) {
            Logger.info(Logger.System.UI, "%s: no problems, %s:", source, count(notes, "note"));
            for (Finding f : findings) Logger.info(Logger.System.UI, "  %s", lineText(f));
        } else {
            Logger.info(Logger.System.UI, "%s: no problems.", source);
        }
    }

    /** Whether any value in the file refers to the variable {@code name}. */
    private boolean isReferenced(String name) {
        String ref = "@" + name;
        for (String v : global.values()) if (v.equals(ref)) return true;
        for (String v : locals.values()) if (v.equals(ref)) return true;
        for (Rule r : rules)             if (r.value.equals(ref)) return true;
        return false;
    }

    private static int lineOrLast(Finding f) { return f.line() > 0 ? f.line() : Integer.MAX_VALUE; }

    private static String lineText(Finding f) {
        return (f.line() > 0 ? "line " + f.line() + ": " : "") + f.message();
    }

    private static String count(int n, String what) { return n + " " + what + (n == 1 ? "" : "s"); }

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
        Object value = resolve(written, entry.type(), where, where, new HashSet<>());
        if (value != null) return value;

        if (!written.equals(entry.defaultValue())) {
            String defaultOf = "default of " + entry.name();
            value = resolve(entry.defaultValue(), entry.type(), defaultOf, defaultOf, new HashSet<>());
            if (value != null) return value;
        }
        return FACTORY_VALUES.get(entry.name());
    }

    /**
     * Follows references until a value, and parses it as {@code type}: a {@code float[4]} for
     * a colour, a {@code Float} for a size. Null, warned, if it cannot.
     *
     * @param where what is being resolved — the key whose value falls back if this fails
     * @param at    the key {@code written} is the value of: {@code where} at first, then each
     *              variable a reference leads to. A warning points at its line, since that is
     *              where the wrong text is.
     */
    private Object resolve(String written, AegisThemeCatalogue.Type type, String where, String at,
                           Set<String> visiting) {
        // Through a variable, say who led there; through a default, every widget kind would,
        // and the mistake is the same one.
        String reached = at.equals(where) || where.startsWith("default of ") ? "" : "; reached from " + where;

        if (written.startsWith("@")) {
            String ref = written.substring(1);
            String target;
            String next = ref;
            if (ref.startsWith(GLOBAL_PREFIX)) {
                String name = ref.substring(GLOBAL_PREFIX.length());
                target = global.get(name);
                if (target == null) {
                    AegisThemeCatalogue.Entry entry = AegisThemeCatalogue.find(name);
                    target = entry != null ? entry.defaultValue() : null;
                    next = "default of " + name;
                }
            } else {
                target = locals.get(ref);
            }
            if (target == null) {
                warn(at, at + "|" + written, at + " refers to " + written + ", which does not exist" + reached + "; using the default");
                return null;
            }
            if (!visiting.add(ref)) {
                warn(at, at + "|" + written, at + " is part of a reference cycle through " + written + reached + "; using the default");
                return null;
            }
            return resolve(target, type, where, next, visiting);
        }

        // Typed strictly: a size is a JSON number, a colour is a string. "4" is text that happens
        // to hold digits, and is refused rather than converted. Only what the file wrote is
        // checked; the catalogue's defaults are text by construction.
        if (lineOf(at) > 0) {
            boolean isNumber = writtenAsNumber.contains(at);
            if (type == AegisThemeCatalogue.Type.SIZE && !isNumber) {
                warn(at, at + "|" + written, at + " — a size is a number, written without quotes, like 4; got the text \""
                    + written + "\"" + reached + "; using the default");
                return null;
            }
            if (type == AegisThemeCatalogue.Type.COLOUR && isNumber) {
                warn(at, at + "|" + written, at + " — expected a colour like \"#5C9EF0\", got the number "
                    + written + reached + "; using the default");
                return null;
            }
        }

        Object value = type == AegisThemeCatalogue.Type.COLOUR ? parseColour(written) : parseSize(written);
        if (value == null) {
            warn(at, at + "|" + written, at + " — expected " + (type == AegisThemeCatalogue.Type.COLOUR
                ? "a colour like \"#5C9EF0\"" : "a number of pixels, 0 or more")
                + ", got \"" + written + "\"" + reached + "; using the default");
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

    /**
     * Records a problem for {@link #report}, once, with the line {@code key} is written on.
     *
     * @param key     the key the mistake is in — {@code global.accent}, a rule, a variable —
     *                or anything else, which simply has no line
     * @param message what is wrong, and what is done instead
     */
    private void warn(String key, String message) {
        warn(key, message, message);
    }

    /**
     * Like {@link #warn(String, String)}, said once per {@code mistake} rather than per message:
     * one wrong value that many properties lead to is one thing to fix.
     */
    private void warn(String key, String mistake, String message) {
        if (warned.add(mistake)) findings.add(new Finding(lineOf(key), true, message));
    }

    /** Like {@link #warn}, for something worth knowing that changes nothing. */
    private void note(String key, String message) {
        if (warned.add(message)) findings.add(new Finding(lineOf(key), false, message));
    }

    /** The line {@code key} is written on, or 0 when it is not known. */
    private int lineOf(String key) {
        return key.startsWith(GLOBAL_PREFIX)
            ? lines.line("global", key.substring(GLOBAL_PREFIX.length()))
            : lines.line(key);
    }

    /**
     * Every entry's factory value, resolved once from the catalogue alone. The catalogue's
     * defaults always resolve — a default that did not would be a bug in the engine, caught the
     * first time this class loads.
     */
    private static final Map<String, Object> FACTORY_VALUES = new HashMap<>();

    static {
        AegisTheme bare = new AegisTheme("catalogue", "", "", AegisThemeCatalogue.FORMAT, AegisJsonLines.scan(""));
        for (AegisThemeCatalogue.Entry entry : AegisThemeCatalogue.entries()) {
            Object value = bare.resolve(entry.defaultValue(), entry.type(), entry.name(), entry.name(), new HashSet<>());
            if (value == null) {
                throw new IllegalStateException("The theme catalogue's default for " + entry.name()
                    + " does not resolve: " + entry.defaultValue());
            }
            FACTORY_VALUES.put(entry.name(), value);
        }
    }
}
