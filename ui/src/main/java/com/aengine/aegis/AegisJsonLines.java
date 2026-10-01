package com.aengine.aegis;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Where each key of a JSON file is written — step 3f part 3.
 *
 * <p>Gson hands back what a file says but not where it says it, and a warning that names a
 * key without its line leaves the reader searching. This walks the text once, before Gson
 * reads it, and notes the line of every object key. It does not check the file: a file Gson
 * rejects is reported from Gson's own message, and anything this walk cannot make sense of
 * simply has no line.</p>
 *
 * <p>Gson keeps the last of two keys with the same name and says nothing. This keeps every
 * line a key was written on, so a repeated key can be pointed out.</p>
 *
 * <p>A key is found by its path: {@code lines("global", "accent")} for {@code accent} inside
 * {@code "global"}, {@code lines("viewport.stopButton.fill")} for a key at the top. Dots
 * inside a key are part of the key, not a path. An element of a list is a step of its own,
 * written with its index in brackets: {@code lines("children", "[1]", "width")} for the
 * {@code width} of the second child, and {@code lines("panels", "[0]")} for the line the
 * first element of a list starts on.</p>
 */
final class AegisJsonLines {

    /** Joins a path's keys. Not a dot: keys contain dots. */
    private static final char SEPARATOR = '\u0000';

    /** Path to the lines it is written on, in file order. */
    private final Map<String, List<Integer>> lines = new HashMap<>();

    /** Paths that are written more than once, in the order the second one was met. */
    private final List<String> repeated = new ArrayList<>();

    private AegisJsonLines() {}

    /**
     * Walks {@code text} and notes the line of every object key.
     *
     * @param text the whole file
     * @return what was found; never null, possibly empty
     */
    static AegisJsonLines scan(String text) {
        AegisJsonLines found = new AegisJsonLines();

        // One entry per open object or array: its path; for an object, whether the next string
        // is a key; for an array, the index of the element being read and whether that
        // element has started yet, since its line is the line it starts on.
        List<String>  paths     = new ArrayList<>();
        List<Boolean> expectKey = new ArrayList<>();   // always false for an array
        List<Boolean> isObject  = new ArrayList<>();
        List<Integer> index     = new ArrayList<>();   // unused for an object
        List<Boolean> started   = new ArrayList<>();   // unused for an object
        String lastKey = null;
        int line = 1;

        int i = 0, n = text.length();
        while (i < n) {
            char c = text.charAt(i);

            if (c == '\n') { line++; i++; continue; }

            // Comments, which lenient reading accepts: //, # to the end of the line, /* */.
            if (c == '#' || (c == '/' && i + 1 < n && text.charAt(i + 1) == '/')) {
                while (i < n && text.charAt(i) != '\n') i++;
                continue;
            }
            if (c == '/' && i + 1 < n && text.charAt(i + 1) == '*') {
                i += 2;
                while (i < n && !(text.charAt(i) == '*' && i + 1 < n && text.charAt(i + 1) == '/')) {
                    if (text.charAt(i) == '\n') line++;
                    i++;
                }
                i += 2;
                continue;
            }

            if (Character.isWhitespace(c)) { i++; continue; }

            // The first character of a list element, whatever it is: note the element's line.
            int open = paths.size() - 1;
            if (open >= 0 && !isObject.get(open) && !started.get(open) && c != ']' && c != ',') {
                found.add(element(paths.get(open), index.get(open)), line);
                started.set(open, true);
            }

            if (c == '"' || c == '\'') {
                int startLine = line;
                StringBuilder s = new StringBuilder();
                i++;
                while (i < n && text.charAt(i) != c) {
                    char ch = text.charAt(i);
                    if (ch == '\\' && i + 1 < n) {
                        // The escape's meaning does not matter for finding keys, only that the
                        // quote after a backslash does not end the string.
                        s.append(text.charAt(i + 1));
                        i += 2;
                        continue;
                    }
                    if (ch == '\n') line++;
                    s.append(ch);
                    i++;
                }
                i++;   // the closing quote

                int top = paths.size() - 1;
                if (top >= 0 && isObject.get(top) && expectKey.get(top)) {
                    String parent = paths.get(top);
                    lastKey = parent.isEmpty() ? s.toString() : parent + SEPARATOR + s;
                    found.add(lastKey, startLine);
                    expectKey.set(top, false);
                }
                continue;
            }

            int top = paths.size() - 1;
            switch (c) {
                case '{', '[' -> {
                    // The new container's path: the key it is the value of, or, inside a
                    // list, the list's path and its index. At the top there is none.
                    String path = "";
                    if (top >= 0 && isObject.get(top) && lastKey != null) path = lastKey;
                    else if (top >= 0 && !isObject.get(top))            path = element(paths.get(top), index.get(top));
                    paths.add(path);
                    isObject.add(c == '{');
                    expectKey.add(c == '{');
                    index.add(0);
                    started.add(false);
                    lastKey = null;
                }
                case '}', ']' -> {
                    if (top >= 0) {
                        paths.remove(top);
                        isObject.remove(top);
                        expectKey.remove(top);
                        index.remove(top);
                        started.remove(top);
                    }
                    lastKey = null;
                }
                case ',' -> {
                    if (top >= 0 && isObject.get(top)) {
                        expectKey.set(top, true);
                    } else if (top >= 0) {
                        index.set(top, index.get(top) + 1);
                        started.set(top, false);
                    }
                    lastKey = null;
                }
                default -> { }
            }
            i++;
        }
        return found;
    }

    /** The path of a list's element: the list's path, then {@code [i]} as a step of its own. */
    private static String element(String listPath, int i) {
        String step = "[" + i + "]";
        return listPath.isEmpty() ? step : listPath + SEPARATOR + step;
    }

    private void add(String path, int line) {
        List<Integer> at = lines.computeIfAbsent(path, k -> new ArrayList<>(1));
        at.add(line);
        if (at.size() == 2) repeated.add(path);
    }

    /**
     * The lines a key is written on, in file order.
     *
     * @param path the keys from the top down, e.g. {@code "global", "accent"}
     * @return the lines, empty if the key was not found
     */
    List<Integer> lines(String... path) {
        List<Integer> at = lines.get(String.join(String.valueOf(SEPARATOR), path));
        return at != null ? at : List.of();
    }

    /**
     * The line a key is written on — the last, if it is written more than once, since that is
     * the one Gson keeps.
     *
     * @param path the keys from the top down
     * @return the line, 1 or more, or 0 if the key was not found
     */
    int line(String... path) {
        List<Integer> at = lines(path);
        return at.isEmpty() ? 0 : at.get(at.size() - 1);
    }

    /**
     * Every key written more than once, as its path — the outermost only, when a whole object
     * is repeated.
     *
     * @return each repeated key's path, from the top down
     */
    List<String[]> repeated() {
        List<String[]> out = new ArrayList<>(repeated.size());
        for (String path : repeated) {
            // An object written twice repeats every key inside it too; the outer one is the
            // mistake, so keys under a repeated key are not listed again.
            boolean underRepeated = false;
            for (String other : repeated) {
                if (path.startsWith(other + SEPARATOR)) { underRepeated = true; break; }
            }
            if (!underRepeated) out.add(path.split(String.valueOf(SEPARATOR), -1));
        }
        return out;
    }
}
