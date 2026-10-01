package com.aengine.aegis;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;
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

/**
 * A layout file, read and checked — step 3f part 4.
 *
 * <p>Describes where things go and nothing else: which panels each screen shows, in which rows,
 * columns and tabs, at what size, and how a panel's widgets are ordered and grouped. Everything
 * it places is made by code — a panel and its widgets, each wired to what it does — and
 * registered under a name; this file only arranges those names (§7, <em>Format</em>). Reading it
 * builds nothing: it produces a description per screen, {@link Node}s, which step 4c turns into
 * layout nodes.</p>
 *
 * <h2>What goes wrong, and what happens</h2>
 *
 * <p>The rule is the theme's. A file that is not JSON, not an object, or has no
 * {@code "screens"} object holds nothing to build: {@link #read} throws {@link Unusable}, and
 * the caller moves to the next file in line. A screen that cannot form a tree — a node that is
 * not an object, a {@code "type"} that is not one of the five, {@code "children"} that is not a
 * list — is set aside, that screen alone, for the next file in line to supply. Anything else
 * is one property or one node wrong, and is dropped or falls back by itself. Types are strict:
 * a size is a JSON number, a name a string. Every finding names its line and is said in one
 * block by {@link #report}.</p>
 */
public final class AegisLayoutFile {

    /** The layout format this engine reads. A file declares the one it was written against. */
    public static final int FORMAT = 1;

    /** A layout file nothing usable can be built from: not JSON, not an object, no screens. */
    public static final class Unusable extends Exception {
        Unusable(String message, Throwable cause) { super(message, cause); }
    }

    /** The five kinds of node. */
    public enum Type {
        /** Children side by side, left to right. */
        ROW,
        /** Children stacked, top to bottom. */
        COLUMN,
        /** One panel's slot, by the name code registered it under. */
        PANEL,
        /** Inside a panel only: one of that panel's widgets, by its name. */
        WIDGET,
        /** Several panels sharing a slot, one shown at a time. */
        TABS
    }

    /**
     * One node of a screen, as the file describes it. A size or spacing the file does not set
     * is {@code NaN}; an alignment it does not set is {@code null} — either way, whatever code
     * gave the node stays.
     */
    public static final class Node {
        private final Type   type;
        private final String name;        // the panel's or the widget's; null for the others
        private final int    line;
        private final List<Node>   children = new ArrayList<>();
        private final List<String> tabs     = new ArrayList<>();
        private boolean hasChildren;      // a panel with "children" rearranges its widgets

        float width = Float.NaN, height = Float.NaN, grow = Float.NaN, gap = Float.NaN, padding = Float.NaN;
        AegisLayout.Align alignX, alignY;

        Node(Type type, String name, int line) {
            this.type = type;
            this.name = name;
            this.line = line;
        }

        /** @return what kind of node it is */
        public Type type() { return type; }

        /** @return the panel's name for a panel, the widget's for a widget, otherwise {@code null} */
        public String name() { return name; }

        /** @return the line the node starts on, or 0 when not known */
        public int line() { return line; }

        /** @return the children, in order; for a panel, its widgets as the file arranges them */
        public List<Node> children() { return Collections.unmodifiableList(children); }

        /** @return whether a panel's {@code "children"} rearranges its widgets; false keeps code's order */
        public boolean hasChildren() { return hasChildren; }

        /** @return a tabs node's panels, in tab order; empty for the others */
        public List<String> tabs() { return Collections.unmodifiableList(tabs); }

        /** @return the width asked for, or {@code NaN} if the file does not set it */
        public float width() { return width; }

        /** @return the height asked for, or {@code NaN} */
        public float height() { return height; }

        /** @return the share of spare room taken, or {@code NaN} */
        public float grow() { return grow; }

        /** @return the space between children, or {@code NaN} */
        public float gap() { return gap; }

        /** @return the space inside the edges, or {@code NaN} */
        public float padding() { return padding; }

        /** @return the alignment across X, or {@code null} */
        public AegisLayout.Align alignX() { return alignX; }

        /** @return the alignment across Y, or {@code null} */
        public AegisLayout.Align alignY() { return alignY; }

        /** {@code panel "inspector"}, {@code widget "nameField"}, {@code row}: how messages name it. */
        String label() {
            return name != null ? type.name().toLowerCase() + " \"" + name + "\"" : type.name().toLowerCase();
        }
    }

    /** A screen that cannot form a tree: thrown inside one screen's reading, caught around it. */
    private static final class ScreenBroken extends Exception {
        final int line;
        ScreenBroken(int line, String message) { super(message, null, false, false); this.line = line; }
    }

    private static final Set<String> SIZES      = Set.of("width", "height", "grow");
    private static final Set<String> CONTAINERS = Set.of("gap", "padding", "alignX", "alignY");

    private final String source;
    private final String name;
    private final String description;
    private final int    format;
    private final AegisJsonLines lines;
    private final AegisFindings  findings;

    private final Map<String, Node> screens = new LinkedHashMap<>();

    /** Where each panel is placed, across every screen: a panel belongs to one place. */
    private final Map<String, Node> placedPanels = new HashMap<>();

    private AegisLayoutFile(String source, String name, String description, int format, AegisJsonLines lines) {
        this.source      = source;
        this.name        = name;
        this.description = description;
        this.format      = format;
        this.lines       = lines;
        this.findings    = new AegisFindings(source);
    }

    // -----------------------------------------------------------------------------------
    // Reading
    // -----------------------------------------------------------------------------------

    /**
     * Reads a layout file. Problems below the whole file are recorded, not thrown; see the
     * class comment.
     *
     * @param file the layout file, UTF-8 JSON
     * @return what it describes, screen by screen
     * @throws IOException if the file cannot be read
     * @throws Unusable    if it is not JSON, not an object, or has no {@code "screens"} object
     */
    public static AegisLayoutFile read(Path file) throws IOException, Unusable {
        try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            return parse(reader, file.toString());
        }
    }

    /**
     * Reads a layout from any reader; {@code source} names it in the log. The reader is not
     * closed.
     *
     * @param reader the JSON text
     * @param source what to call it in the log, e.g. a file path
     * @return what it describes, screen by screen
     * @throws Unusable if it is not JSON, not an object, or has no {@code "screens"} object
     */
    public static AegisLayoutFile parse(Reader reader, String source) throws Unusable {
        String text;
        try {
            text = AegisFindings.readAll(reader);
        } catch (IOException e) {
            throw new Unusable(source + " could not be read: " + e.getMessage(), e);
        }

        JsonElement root;
        try {
            JsonReader json = new JsonReader(new StringReader(text));
            json.setLenient(true);   // a comment someone added is tolerated, as in a theme
            root = JsonParser.parseReader(json);
        } catch (JsonParseException e) {
            throw new Unusable(AegisFindings.syntaxError(source, e), e);
        }
        if (!root.isJsonObject()) {
            throw new Unusable(source + " must hold one JSON object, { ... }, at the top.", null);
        }
        JsonObject top = root.getAsJsonObject();
        AegisJsonLines lines = AegisJsonLines.scan(text);

        JsonElement screensElement = top.get("screens");
        if (screensElement == null || !screensElement.isJsonObject()) {
            int at = lines.line("screens");
            throw new Unusable(source + (at > 0 ? ":" + at : "") + ": \"screens\" must be an object,"
                + " { \"editor\": { \"root\": ... } }; there is nothing to build without it.", null);
        }

        JsonElement declared = top.get("format");
        Integer declaredFormat = declared != null ? AegisFindings.wholeNumber(declared) : null;

        AegisLayoutFile file = new AegisLayoutFile(source,
            stringOr(top.get("name"), source), stringOr(top.get("description"), ""),
            declaredFormat != null && declaredFormat >= 1 ? declaredFormat : 1, lines);

        for (String[] path : lines.repeated()) {
            List<Integer> at = lines.lines(path);
            file.findings.note(at.get(at.size() - 1), display(path) + " is written on lines "
                + AegisFindings.listOf(at) + "; only line " + at.get(at.size() - 1) + " counts");
        }
        file.checkMetadata(top, declared, declaredFormat);

        for (Map.Entry<String, JsonElement> e : screensElement.getAsJsonObject().entrySet()) {
            file.readScreen(e.getKey(), e.getValue());
        }
        return file;
    }

    private void checkMetadata(JsonObject top, JsonElement declared, Integer declaredFormat) {
        for (Map.Entry<String, JsonElement> e : top.entrySet()) {
            String key = e.getKey();
            switch (key) {
                case "screens", "format" -> { }
                case "name", "description" -> {
                    if (!isString(e.getValue())) {
                        findings.problem(lines.line(key), "\"" + key + "\" must be text, written in quotes; it is ignored");
                    }
                }
                default -> findings.problem(lines.line(key), "\"" + key + "\" is not something a layout file holds;"
                    + " it is ignored (screens go inside \"screens\")");
            }
        }

        int at = lines.line("format");
        if (declared == null) {
            findings.note(0, "no \"format\"; read as format 1. Add \"format\": " + FORMAT
                + " so a later engine can tell what this layout predates");
        } else if (declaredFormat == null || declaredFormat < 1) {
            findings.problem(at, "\"format\" must be a whole number, 1 or more, written without quotes; got "
                + declared + "; read as format 1");
        } else if (declaredFormat > FORMAT) {
            findings.problem(at, "written for format " + declaredFormat + ", newer than this engine's " + FORMAT
                + "; what it does not know may come from a later version and is ignored");
        }
    }

    private void readScreen(String screen, JsonElement element) {
        List<String> path = new ArrayList<>(List.of("screens", screen));
        reading = screen;
        try {
            if (!element.isJsonObject()) {
                throw new ScreenBroken(line(path), "must be an object holding \"root\"");
            }
            JsonObject object = element.getAsJsonObject();
            for (String key : object.keySet()) {
                if (!key.equals("root")) {
                    path.add(key);
                    findings.problem(line(path), "screen \"" + screen + "\": \"" + key
                        + "\" is not something a screen holds; it is ignored");
                    path.remove(path.size() - 1);
                }
            }
            JsonElement rootElement = object.get("root");
            if (rootElement == null) throw new ScreenBroken(line(path), "has no \"root\"");

            path.add("root");
            Node root = readNode(rootElement, path, null);
            if (root == null) throw new ScreenBroken(line(path), "its root was dropped, so nothing is left");
            if (root.type == Type.WIDGET) throw new ScreenBroken(root.line, "its root is a widget; a widget belongs inside a panel");
            screens.put(screen, root);
        } catch (ScreenBroken broken) {
            // Panels placed by the abandoned screen are free again for another to place.
            placedPanels.values().removeIf(n -> screen.equals(screenOf.get(n)));
            findings.problem(broken.line, "screen \"" + screen + "\" " + broken.getMessage()
                + "; it is set aside and taken from the next file in line");
        }
    }

    /** The screen each placed panel came from, so a screen set aside frees its panels. */
    private final Map<Node, String> screenOf = new HashMap<>();
    private String reading;   // the screen being read

    /**
     * Reads one node and everything under it.
     *
     * @param panel the panel whose {@code "children"} this is inside, or null outside any panel
     * @return the node, or null when it is dropped (already reported)
     */
    private Node readNode(JsonElement element, List<String> path, Node panel) throws ScreenBroken {
        int at = line(path);

        if (!element.isJsonObject()) {
            throw new ScreenBroken(at, "has a node that is not an object, { \"type\": ... }");
        }
        JsonObject object = element.getAsJsonObject();

        JsonElement typeElement = object.get("type");
        if (!isString(typeElement)) {
            throw new ScreenBroken(at, "has a node without a \"type\" in quotes:"
                + " row, column, panel, widget or tabs");
        }
        String typeName = typeElement.getAsString();
        Type type;
        try {
            type = Type.valueOf(typeName.toUpperCase());
            if (!type.name().toLowerCase().equals(typeName)) throw new IllegalArgumentException();
        } catch (IllegalArgumentException e) {
            throw new ScreenBroken(at, "has a node of type \"" + typeName
                + "\"; the types are row, column, panel, widget and tabs");
        }

        // What it refers to, for a panel or a widget, and where it may stand.
        String name = null;
        if (type == Type.PANEL || type == Type.WIDGET) {
            String key = type == Type.PANEL ? "panel" : "widget";
            JsonElement nameElement = object.get(key);
            if (!isString(nameElement)) {
                findings.problem(at, "a " + key + " node needs \"" + key + "\": the name code registered,"
                    + " in quotes; the node is left out");
                return null;
            }
            name = nameElement.getAsString();
        }
        Node node = new Node(type, name, at);

        if (panel != null && (type == Type.PANEL || type == Type.TABS)) {
            findings.problem(at, node.label() + " is inside " + panel.label()
                + "; a panel holds its own widgets, not other panels. It is left out");
            return null;
        }
        if (panel == null && type == Type.WIDGET) {
            findings.problem(at, node.label() + " is outside any panel; a widget is placed inside"
                + " its panel's \"children\". It is left out");
            return null;
        }
        if (type == Type.PANEL && !place(node, name)) return null;
        if (type == Type.WIDGET && !placeWidget(node, panel)) return null;

        // Properties: which a node may hold depends on its type.
        for (Map.Entry<String, JsonElement> e : object.entrySet()) {
            String key = e.getKey();
            path.add(key);
            int keyLine = line(path);
            JsonElement value = e.getValue();

            if (key.equals("type") || (key.equals("panel") && type == Type.PANEL)
                    || (key.equals("widget") && type == Type.WIDGET)) {
                // already read
            } else if (SIZES.contains(key) || (CONTAINERS.contains(key) && type != Type.WIDGET && type != Type.TABS)) {
                readProperty(node, key, value, keyLine);
            } else if (key.equals("children") && (type == Type.ROW || type == Type.COLUMN || type == Type.PANEL)) {
                if (!value.isJsonArray()) {
                    path.remove(path.size() - 1);
                    throw new ScreenBroken(keyLine, "has \"children\" that is not a list, [ ... ], in "
                        + node.label());
                }
                if (type == Type.PANEL) node.hasChildren = true;
                readChildren(node, value.getAsJsonArray(), path, type == Type.PANEL ? node : panel);
            } else if (key.equals("panels") && type == Type.TABS) {
                readTabs(node, value, path, keyLine);
            } else {
                findings.problem(keyLine, node.label() + ": \"" + key + "\" is not something a "
                    + type.name().toLowerCase() + " holds; it is ignored");
            }
            path.remove(path.size() - 1);
        }

        if (type == Type.TABS && object.get("panels") == null) {
            findings.problem(at, "tabs needs \"panels\": a list of panel names; the node is left out");
            return null;
        }
        if (type == Type.TABS && !node.tabs.isEmpty()) {
            findings.note(at, "tabs shows only its first panel, \"" + node.tabs.get(0)
                + "\", until the tabbed pane exists (step 3g)");
        }
        return node;
    }

    private void readChildren(Node node, JsonArray array, List<String> path, Node panel) throws ScreenBroken {
        for (int i = 0; i < array.size(); i++) {
            path.add("[" + i + "]");
            Node child = readNode(array.get(i), path, panel);
            if (child != null) node.children.add(child);
            path.remove(path.size() - 1);
        }
    }

    private void readTabs(Node node, JsonElement value, List<String> path, int keyLine) {
        if (!value.isJsonArray()) {
            findings.problem(keyLine, "tabs: \"panels\" must be a list of panel names, [\"a\", \"b\"]; it is ignored");
            return;
        }
        JsonArray array = value.getAsJsonArray();
        for (int i = 0; i < array.size(); i++) {
            path.add("[" + i + "]");
            int at = line(path);
            JsonElement e = array.get(i);
            if (!isString(e)) {
                findings.problem(at, "tabs: a panel name is text in quotes; got " + e + ". It is left out");
            } else {
                Node slot = new Node(Type.PANEL, e.getAsString(), at);
                if (place(slot, slot.name)) node.tabs.add(slot.name);
            }
            path.remove(path.size() - 1);
        }
    }

    /** Records where a panel goes; false, reported, if it already has a place. */
    private boolean place(Node node, String name) {
        Node earlier = placedPanels.get(name);
        if (earlier != null) {
            findings.problem(node.line, "panel \"" + name + "\" is already placed"
                + (earlier.line > 0 ? " on line " + earlier.line : "")
                + "; a panel has one place in the whole file. This one is left empty");
            return false;
        }
        placedPanels.put(name, node);
        screenOf.put(node, reading);
        return true;
    }

    /** Widgets already placed in each panel, so one placed twice is caught. */
    private final Map<Node, Map<String, Node>> placedWidgets = new HashMap<>();

    private boolean placeWidget(Node node, Node panel) {
        Map<String, Node> placed = placedWidgets.computeIfAbsent(panel, p -> new HashMap<>());
        Node earlier = placed.get(node.name);
        if (earlier != null) {
            findings.problem(node.line, node.label() + " is already placed in " + panel.label()
                + (earlier.line > 0 ? " on line " + earlier.line : "") + "; this one is left out");
            return false;
        }
        placed.put(node.name, node);
        return true;
    }

    private void readProperty(Node node, String key, JsonElement value, int at) {
        if (key.equals("alignX") || key.equals("alignY")) {
            AegisLayout.Align align = null;
            if (isString(value)) {
                switch (value.getAsString()) {
                    case "start"   -> align = AegisLayout.Align.START;
                    case "center"  -> align = AegisLayout.Align.CENTER;
                    case "end"     -> align = AegisLayout.Align.END;
                    case "stretch" -> align = AegisLayout.Align.STRETCH;
                    default        -> { }
                }
            }
            if (align == null) {
                findings.problem(at, node.label() + ": " + key + " is one of \"start\", \"center\", \"end\""
                    + " or \"stretch\", in quotes; got " + value + ". It is ignored");
            } else if (key.equals("alignX")) {
                node.alignX = align;
            } else {
                node.alignY = align;
            }
            return;
        }

        // A size or a spacing: a JSON number, 0 or more.
        JsonPrimitive p = value.isJsonPrimitive() ? value.getAsJsonPrimitive() : null;
        if (p == null || !p.isNumber()) {
            findings.problem(at, node.label() + ": " + key + " is a number, written without quotes, like 240;"
                + " got " + value + ". It is ignored");
            return;
        }
        float number = p.getAsFloat();
        if (!(number >= 0.0f) || !Float.isFinite(number)) {
            findings.problem(at, node.label() + ": " + key + " must be 0 or more; got " + value + ". It is ignored");
            return;
        }
        switch (key) {
            case "width"   -> node.width   = number;
            case "height"  -> node.height  = number;
            case "grow"    -> node.grow    = number;
            case "gap"     -> node.gap     = number;
            case "padding" -> node.padding = number;
            default        -> { }
        }
    }

    // -----------------------------------------------------------------------------------
    // What it says
    // -----------------------------------------------------------------------------------

    /**
     * A screen's root as the file describes it.
     *
     * @param screen the screen's name, e.g. {@code "editor"}
     * @return its root, or {@code null} when the file has no such screen or it was set aside
     */
    public Node screen(String screen) { return screens.get(screen); }

    /** @return the names of the screens the file describes usably, in file order */
    public Set<String> screens() { return Collections.unmodifiableSet(screens.keySet()); }

    /** @return the {@code name} the file declares, or its source */
    public String name() { return name; }

    /** @return the {@code description}, or empty */
    public String description() { return description; }

    /** @return the declared {@code format}, or 1 if none or not a whole number */
    public int format() { return format; }

    /** @return where it came from, as used in the log */
    public String source() { return source; }

    /**
     * Records a problem found while building from the file — a panel name code never registered,
     * a widget that is not that panel's — so it is said with the rest.
     */
    void problem(Node node, String message) { findings.problem(node.line, message); }

    /** Records a note found while building, like {@link #problem}. */
    void note(Node node, String message) { findings.note(node.line, message); }

    /** Says everything found, in one block; see {@link AegisFindings#log}. Only the first call speaks. */
    void report() { findings.log(); }

    // -----------------------------------------------------------------------------------
    // Internals
    // -----------------------------------------------------------------------------------

    private int line(List<String> path) { return lines.line(path.toArray(new String[0])); }

    private static boolean isString(JsonElement e) {
        return e != null && e.isJsonPrimitive() && e.getAsJsonPrimitive().isString();
    }

    private static String stringOr(JsonElement e, String otherwise) {
        return isString(e) ? e.getAsString() : otherwise;
    }

    /** {@code screens.editor.root.children[1].width}: a path as it reads in a message. */
    private static String display(String[] path) {
        StringBuilder s = new StringBuilder();
        for (String step : path) {
            if (s.length() > 0 && !step.startsWith("[")) s.append('.');
            s.append(step);
        }
        return s.toString();
    }
}
