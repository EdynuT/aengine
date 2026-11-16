package com.aengine.aegis;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;
import com.google.gson.stream.JsonReader;

import java.io.IOException;
import java.io.Reader;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The words the interface shows, in one language — step 3h.
 *
 * <p>English is written in code, where each widget is made: {@code ae.button(viewport,
 * "buttonStop", "Stop")}. A locale file only says what changes in another language, keyed by
 * the widget's address — the path a theme reaches it by:</p>
 *
 * <pre>{@code
 * {
 *   "name":        "Português (Brasil)",
 *   "description": "The editor in Brazilian Portuguese.",
 *   "format":      1,
 *
 *   "viewport.buttonStop":          "Parar",
 *   "viewport.buttonStop.tooltip":  "Para a cena e volta ao estado salvo.",
 *   "inspector.fieldName.placeholder": "Nome da entidade",
 *   "inspector.title":              "Inspetor"
 * }
 * }</pre>
 *
 * <p>So adding or renaming a widget never asks for a file to be edited: until a translation
 * says otherwise, the widget shows its English. {@link #missing} lists what a language has yet
 * to translate, with the English beside it, ready to be copied into the file.</p>
 *
 * <p>A text is looked for in this locale, then in its {@linkplain #withFallback fallback} —
 * {@code pt} behind {@code pt-BR} — then taken from code. {@link #english} is the locale with
 * no texts at all, shown when no file is chosen.</p>
 *
 * <h2>What goes wrong, and what happens</h2>
 *
 * <p>As with a theme: a file that is not JSON, or not an object, cannot be used —
 * {@link #read} throws {@link Unusable} and the editor speaks English. Anything else is one
 * text wrong — not a string, a key no widget answers to, a placeholder such as {@code {count}}
 * dropped — and that text alone is ignored or used as written, with a warning naming its
 * line, said when the locale is applied.</p>
 */
public final class AegisLocale {

    /**
     * The version of what locale files hold. Stays 1 until the engine is released; after
     * that, a change to what a file may hold raises it.
     */
    public static final int FORMAT = 1;

    /** The suffixes a key may end in after a widget's address, for its texts beside the main one. */
    static final String TOOLTIP = "tooltip", PLACEHOLDER = "placeholder", TITLE = "title";

    /** A file nothing can be read from. */
    public static final class Unusable extends Exception {
        Unusable(String message, Throwable cause) { super(message, cause); }
    }

    private static final Pattern PLACEHOLDER_NAME = Pattern.compile("\\{([A-Za-z][A-Za-z0-9]*)\\}");

    private final String source;
    private String name;
    private String description;
    private final AegisJsonLines lines;
    private final AegisFindings findings;

    /** What the file says, by key, in the order written. */
    private final Map<String, String> texts = new LinkedHashMap<>();

    private AegisLocale fallback;

    /** Every key asked for since the last {@link #beginApplying}, with the English that went with it. */
    private final Map<String, String> asked = new HashMap<>();

    /** Keys no file in line translates, with the English shown instead; sorted, for a stable listing. */
    private final Map<String, String> missing = new TreeMap<>();

    private AegisLocale(String source, String name, String description, AegisJsonLines lines) {
        this.source = source;
        this.name = name;
        this.description = description;
        this.lines = lines;
        this.findings = new AegisFindings(source);
    }

    // -----------------------------------------------------------------------------------
    // Making one
    // -----------------------------------------------------------------------------------

    /**
     * The locale with no texts: every widget shows the English it was made with.
     *
     * @return a new English locale
     */
    public static AegisLocale english() {
        return new AegisLocale("english", "English", "The English written in code.", AegisJsonLines.scan(""));
    }

    /**
     * Reads a locale file. Problems with single texts are said when the locale is applied, not
     * thrown; see the class comment.
     *
     * @param file the locale file, UTF-8 JSON
     * @return the locale
     * @throws IOException if the file cannot be read
     * @throws Unusable    if it is not JSON, or not a JSON object
     */
    public static AegisLocale read(Path file) throws IOException, Unusable {
        try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            return parse(reader, file.toString());
        }
    }

    /**
     * Reads a locale from any reader; {@code source} names it in warnings. The reader is not
     * closed.
     *
     * @param reader JSON text
     * @param source what to call it in warnings, e.g. its file path
     * @return the locale
     * @throws Unusable if it is not JSON, or not a JSON object
     */
    public static AegisLocale parse(Reader reader, String source) throws Unusable {
        String text;
        try {
            text = AegisFindings.readAll(reader);
        } catch (IOException e) {
            throw new Unusable(source + " could not be read: " + e.getMessage(), e);
        }
        JsonElement root;
        try {
            JsonReader json = new JsonReader(new StringReader(text));
            json.setLenient(true);   // tolerate a comment someone added, as a theme does
            root = JsonParser.parseReader(json);
        } catch (JsonParseException e) {
            throw new Unusable(AegisFindings.syntaxError(source, e), e);
        }
        if (!root.isJsonObject()) {
            throw new Unusable(source + " must hold one JSON object, { ... }, at the top.", null);
        }
        JsonObject top = root.getAsJsonObject();
        AegisLocale locale = new AegisLocale(source, source, "", AegisJsonLines.scan(text));
        locale.name = locale.metadata(top, "name", source);
        locale.description = locale.metadata(top, "description", "");

        for (String[] path : locale.lines.repeated()) {
            List<Integer> at = locale.lines.lines(path);
            locale.findings.note(at.get(0), String.join(".", path) + " written on lines "
                + AegisFindings.listOf(at) + "; only line " + at.get(at.size() - 1) + " counts");
        }
        locale.checkFormat(top.get("format"));

        for (Map.Entry<String, JsonElement> e : top.entrySet()) {
            String key = e.getKey();
            if (key.equals("name") || key.equals("description") || key.equals("format")) continue;
            if (!isValidKey(key)) {
                locale.problem(key, "\"" + key + "\" is not a widget's address — names of letters and"
                    + " digits joined by dots, as a theme writes them; it is ignored");
                continue;
            }
            JsonElement value = e.getValue();
            if (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isString()) {
                locale.problem(key, key + " is text, written in quotes; got " + value + "; it is ignored");
                continue;
            }
            locale.texts.put(key, value.getAsString());
        }
        return locale;
    }

    private String metadata(JsonObject top, String key, String otherwise) {
        JsonElement e = top.get(key);
        if (e == null) return otherwise;
        if (e.isJsonPrimitive() && e.getAsJsonPrimitive().isString()) return e.getAsString();
        problem(key, "\"" + key + "\" is text, written in quotes; got " + e);
        return otherwise;
    }

    private void checkFormat(JsonElement declared) {
        if (declared == null) {
            findings.note(0, "no \"format\"; read as format " + FORMAT + ". Add \"format\": " + FORMAT);
            return;
        }
        Integer format = AegisFindings.wholeNumber(declared);
        if (format == null || format < 1) {
            problem("format", "\"format\" must be a whole number, 1 or more, written without quotes; got "
                + declared + "; read as format 1");
        } else if (format > FORMAT) {
            problem("format", "written for format " + format + ", newer than this engine's " + FORMAT
                + "; texts it does not know may come from a later version");
        }
    }

    /** A widget's address, maybe with a suffix: names joined by dots, each a valid node name. */
    private static boolean isValidKey(String key) {
        if (key.isEmpty() || key.startsWith(".") || key.endsWith(".") || key.contains("..")) return false;
        for (String part : key.split("\\.")) {
            if (!AegisLayout.isValidNodeName(part)) return false;
        }
        return true;
    }

    /**
     * Puts another locale behind this one: a text this one lacks is looked for there before
     * English is shown — {@code pt} behind {@code pt-BR}.
     *
     * @param behind the locale to fall back on, or {@code null} for none
     * @return this locale
     */
    public AegisLocale withFallback(AegisLocale behind) {
        this.fallback = behind;
        return this;
    }

    // -----------------------------------------------------------------------------------
    // Looking texts up
    // -----------------------------------------------------------------------------------

    /**
     * The text for {@code key} in this language: this locale's, its fallback's, or
     * {@code english} when neither has one. Looked up when the locale is applied, never per
     * frame.
     *
     * @param key     the widget's address, maybe with a suffix — {@code viewport.buttonStop}
     * @param english what code wrote, shown when no translation exists
     * @return the text to show
     */
    public String text(String key, String english) {
        asked.put(key, english);
        String found = lookUp(key);
        if (found == null) {
            if (isFile()) missing.put(key, english);
            return english;
        }
        checkPlaceholders(key, english, found);
        return found;
    }

    private String lookUp(String key) {
        if (fallback != null) fallback.asked.put(key, "");   // asked of it too, so its own text for it is not unused
        String found = texts.get(key);
        if (found == null && fallback != null) found = fallback.texts.get(key);
        return found;
    }

    private boolean isFile() { return !source.equals("english"); }

    /** A placeholder English has — {@code {count}} — that the translation drops is reported. */
    private void checkPlaceholders(String key, String english, String translated) {
        Matcher m = PLACEHOLDER_NAME.matcher(english);
        while (m.find()) {
            if (!translated.contains(m.group())) {
                AegisLocale owner = texts.containsKey(key) ? this : fallback;
                owner.problem(key, key + " drops the placeholder " + m.group() + " the English has;"
                    + " the translation is shown as written");
            }
        }
    }

    /** Forgets what was asked before, so a new application finds what is missing anew. */
    void beginApplying() {
        asked.clear();
        missing.clear();
        if (fallback != null) fallback.asked.clear();
    }

    /**
     * Says what applying found, in one block per file: texts no widget asked for, a dropped
     * placeholder, a value that is not text. Each file's findings are said once.
     */
    void report() {
        reportUnasked();
        findings.log();
        if (fallback != null) {
            fallback.reportUnasked();
            fallback.findings.log();
        }
    }

    private void reportUnasked() {
        for (String key : texts.keySet()) {
            if (!asked.containsKey(key)) {
                problem(key, key + " is not a text the editor shows — a widget renamed or removed?;"
                    + " it is ignored");
            }
        }
    }

    // -----------------------------------------------------------------------------------
    // What it says
    // -----------------------------------------------------------------------------------

    /**
     * Texts the widgets asked for that no file in line translates, by key, with the English
     * shown in their place — what a translator has left to do, in the form a locale file
     * writes it. Complete once the locale is applied.
     *
     * @return key to English, sorted by key; empty for {@link #english}
     */
    public Map<String, String> missing() { return Collections.unmodifiableMap(new TreeMap<>(missing)); }

    /**
     * Keys the file holds that no widget asked for when the locale was last applied: texts
     * for widgets renamed or removed.
     *
     * @return those keys, sorted
     */
    public Set<String> unused() {
        Set<String> unused = new TreeSet<>();
        for (String key : texts.keySet()) if (!asked.containsKey(key)) unused.add(key);
        return unused;
    }

    /**
     * How many texts the file holds that were read without a problem.
     *
     * @return the count
     */
    public int size() { return texts.size(); }

    /** @return the {@code name} the file declares, or its source if it declares none */
    public String name() { return name; }

    /** @return what the file's author wrote about it */
    public String description() { return description; }

    /** @return where it came from: a file path, or {@code "english"} */
    public String source() { return source; }

    private void problem(String key, String message) {
        findings.problem(lines.line(key), message);
    }

    /** The texts, for a test to compare against. */
    List<String> keys() { return new ArrayList<>(texts.keySet()); }
}
