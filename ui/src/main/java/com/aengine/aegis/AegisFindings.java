package com.aengine.aegis;

import com.aengine.utils.Logger;
import com.google.gson.JsonElement;
import com.google.gson.JsonParseException;
import com.google.gson.stream.MalformedJsonException;

import java.io.IOException;
import java.io.Reader;
import java.io.StringWriter;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * What a shell file's reader found wrong or untidy, said in one block — step 3f part 3,
 * shared by the theme and the layout readers since 3f part 4.
 *
 * <p>A reader records findings as it goes: a <em>problem</em> is something it had to fall back
 * on, a <em>note</em> something that changes nothing but is worth fixing. Each is said once,
 * however many times it is met. {@link #log} then says them all: problems first, as warnings,
 * then notes, as information, each group in the order of the file's lines. A file with no
 * problem says so in one line of information.</p>
 *
 * <p>Also here, since both readers need them alike: reading the whole text, turning Gson's
 * syntax complaint into one with the file and line first, and reading a whole number strictly.</p>
 */
final class AegisFindings {

    private record Finding(int line, boolean problem, String message) {}

    private final String source;
    private final Set<String> said = new HashSet<>();
    private final List<Finding> findings = new ArrayList<>();
    private boolean logged;

    /**
     * @param source what to call the file in the log, e.g. its path
     */
    AegisFindings(String source) {
        this.source = source;
    }

    /**
     * Records a problem, unless the same one was recorded before.
     *
     * @param line    the line it is written on, or 0 when not known
     * @param mistake what makes two reports the same mistake — one wrong value that many
     *                properties lead to is one thing to fix
     * @param message what is wrong, and what is done instead
     */
    void problem(int line, String mistake, String message) {
        if (said.add(mistake)) findings.add(new Finding(line, true, message));
    }

    /** {@link #problem(int, String, String)}, with the message as what identifies it. */
    void problem(int line, String message) {
        problem(line, message, message);
    }

    /**
     * Records a note: something that changes nothing but is worth fixing.
     *
     * @param line    the line it is written on, or 0 when not known
     * @param message what it is
     */
    void note(int line, String message) {
        if (said.add(message)) findings.add(new Finding(line, false, message));
    }

    /**
     * Says everything recorded, in one block. Only the first call says anything.
     */
    void log() {
        if (logged) return;
        logged = true;

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

    private static int lineOrLast(Finding f) { return f.line() > 0 ? f.line() : Integer.MAX_VALUE; }

    private static String lineText(Finding f) {
        return (f.line() > 0 ? "line " + f.line() + ": " : "") + f.message();
    }

    private static String count(int n, String what) { return n + " " + what + (n == 1 ? "" : "s"); }

    // -----------------------------------------------------------------------------------
    // Shared by the readers
    // -----------------------------------------------------------------------------------

    /** The whole text a reader holds. A shell file is a few kilobytes. */
    static String readAll(Reader reader) throws IOException {
        StringWriter whole = new StringWriter();
        reader.transferTo(whole);
        return whole.toString();
    }

    /**
     * Gson's complaint, said the way the other warnings are: the file and the line first.
     * Gson writes "Expected ':' at line 5 column 12 path $.global.accent".
     */
    static String syntaxError(String source, JsonParseException e) {
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

    /** A JSON number with no fraction, or null. A string is not a number, even "1". */
    static Integer wholeNumber(JsonElement e) {
        if (!e.isJsonPrimitive() || !e.getAsJsonPrimitive().isNumber()) return null;
        try {
            return new java.math.BigDecimal(e.getAsString().trim()).intValueExact();
        } catch (NumberFormatException | ArithmeticException ex) {
            return null;
        }
    }

    /** "7", "7 and 22", "7, 15 and 22". */
    static String listOf(List<Integer> numbers) {
        StringBuilder s = new StringBuilder();
        for (int i = 0; i < numbers.size(); i++) {
            if (i > 0) s.append(i == numbers.size() - 1 ? " and " : ", ");
            s.append(numbers.get(i));
        }
        return s.toString();
    }
}
