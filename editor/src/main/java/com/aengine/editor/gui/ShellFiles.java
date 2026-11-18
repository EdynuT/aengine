package com.aengine.editor.gui;

import com.aengine.aegis.AegisLayoutFile;
import com.aengine.aegis.AegisLocale;
import com.aengine.aegis.AegisTheme;
import com.aengine.utils.Logger;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;
import com.google.gson.stream.JsonReader;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Where the shell's files live, and which of them the editor uses — the one place that knows
 * (§7, <em>Where the files live</em>).
 *
 * <p>Two directories. The <b>installation's</b>, {@code <install>/gui/}, holds the defaults the
 * engine ships — made by the shell's designer, read and never written. The <b>user's</b>, in
 * the system's per-user data directory, holds what the user made or changed: their themes and
 * layouts, their arrangement of each layout, and {@code editor.json} with the theme and the
 * layout chosen in View &gt; Theme and View &gt; Layout. When the file chosen is missing or cannot
 * be used, the factory defaults compiled into the engine are, so the editor always opens.</p>
 *
 * <p>Both directories can be moved with a system property, which is how development runs
 * work: {@code aengine.home} for the installation — {@code ./gradlew :editor:run} points it at
 * {@code src/dist}, which is what becomes the installation — and {@code aengine.userdata} for
 * the user's, which {@code -PtestShell} points at the test themes in {@code test-shell} so that
 * trying a theme never touches the real one.</p>
 */
public final class ShellFiles {

    private ShellFiles() { }

    /** Whose a theme or a layout is: in the order the status bar's labels for it are made. */
    public enum Origin {
        /** The user's directory: made or changed by the user, or written by the editor. */
        USER("yours"),
        /** The installation's: what the engine ships. */
        INSTALLATION("installed"),
        /** Compiled into the engine: no file was found, or none could be used. */
        FACTORY("factory");

        private final String word;
        Origin(String word) { this.word = word; }

        /** @return how the log and {@code editor.json} say it */
        public String word() { return word; }

        /** @return the origin {@code word} names, or {@code null} for none */
        static Origin ofWord(String word) {
            for (Origin o : values()) if (o.word.equals(word)) return o;
            return null;
        }
    }

    /**
     * A theme or a layout the user can choose: whose it is and its file's name, without
     * {@code .json}. The factory's has no file.
     *
     * @param origin whose it is
     * @param file   the file's name without {@code .json}; {@code null} for the factory's
     */
    public record Choice(Origin origin, String file) {
        /** The factory's theme or layout, compiled into the engine. */
        public static final Choice FACTORY = new Choice(Origin.FACTORY, null);

        /** @return a choice of the user's file */
        public static Choice yours(String file) { return new Choice(Origin.USER, file); }

        /** @return a choice of the installation's file */
        public static Choice installed(String file) { return new Choice(Origin.INSTALLATION, file); }
    }

    /**
     * One entry of the theme list or the layout list: what choosing it means, and the name
     * its file gives itself — the file's name when it gives none.
     *
     * @param choice the theme or layout
     * @param name   what the list shows
     */
    public record Listed(Choice choice, String name) { }

    /**
     * Whose a theme or layout is, from where it was read: its {@code source()}.
     *
     * @param source a file path, {@code "factory"} for the factory theme, or
     *               {@link FactoryLayout#SOURCE}
     * @return the origin
     */
    public static Origin originOf(String source) {
        if (source.equals("factory") || source.equals(FactoryLayout.SOURCE)) return Origin.FACTORY;
        return Path.of(source).toAbsolutePath().startsWith(userDir()) ? Origin.USER : Origin.INSTALLATION;
    }

    /**
     * {@code <install>/gui/}: the shipped defaults. {@code aengine.home} if set, else the working directory.
     *
     * @return the absolute path; it may not exist
     */
    public static Path installDir() {
        String home = System.getProperty("aengine.home");
        return (home != null ? Path.of(home) : Path.of("")).toAbsolutePath().resolve("gui");
    }

    /**
     * {@code <install>/lang/}: the locale files, which ship with the engine and change with it
     * (§8). {@code aengine.home} if set, else the working directory.
     *
     * @return the absolute path; it may not exist
     */
    public static Path langDir() {
        String home = System.getProperty("aengine.home");
        return (home != null ? Path.of(home) : Path.of("")).toAbsolutePath().resolve("lang");
    }

    /**
     * The user's {@code AEngine/gui/}: {@code aengine.userdata} if set; on Windows
     * {@code AEngine/gui} under {@code %LOCALAPPDATA%}; elsewhere {@code $XDG_DATA_HOME/AEngine/gui}, which
     * defaults to {@code ~/.local/share/AEngine/gui}.
     *
     * @return the absolute path; it may not exist
     */
    public static Path userDir() {
        String override = System.getProperty("aengine.userdata");
        if (override != null) return Path.of(override).toAbsolutePath();

        String localAppData = System.getenv("LOCALAPPDATA");
        if (localAppData != null && System.getProperty("os.name", "").startsWith("Windows")) {
            return Path.of(localAppData, "AEngine", "gui");
        }
        String xdg = System.getenv("XDG_DATA_HOME");
        Path data = xdg != null && !xdg.isBlank()
            ? Path.of(xdg)
            : Path.of(System.getProperty("user.home"), ".local", "share");
        return data.resolve("AEngine").resolve("gui");
    }

    // -----------------------------------------------------------------------------------
    // The selector: the lists, the choice kept in editor.json, and loading what was chosen
    // -----------------------------------------------------------------------------------

    /**
     * The themes to choose from: the user's, then the installation's, each by file name, then
     * the factory theme. A theme of the same name in both is listed twice, so the installed
     * one is never hidden by the user's copy.
     *
     * @return the list, never empty: the factory theme is always last
     */
    public static List<Listed> listThemes() {
        List<Listed> out = listFiles("themes");
        out.add(new Listed(Choice.FACTORY, AegisTheme.factory().name()));
        return out;
    }

    /**
     * The layouts to choose from, as {@link #listThemes} lists themes. The user's own
     * arrangements are not listed: each belongs to the layout it was made from.
     *
     * @return the list, never empty: the factory layout is always last
     */
    public static List<Listed> listLayouts() {
        List<Listed> out = listFiles("layouts");
        out.add(new Listed(Choice.FACTORY, FactoryLayout.read().name()));
        return out;
    }

    private static List<Listed> listFiles(String kind) {
        List<Listed> out = new ArrayList<>();
        for (Origin origin : new Origin[] { Origin.USER, Origin.INSTALLATION }) {
            Path dir = dirOf(origin).resolve(kind);
            if (!Files.isDirectory(dir)) continue;
            List<Path> files = new ArrayList<>();
            try (var listing = Files.list(dir)) {
                listing.filter(f -> f.getFileName().toString().endsWith(".json") && Files.isRegularFile(f)).forEach(files::add);
            } catch (IOException e) {
                Logger.warn(Logger.System.UI, "%s cannot be listed (%s).", dir, e.getMessage());
                continue;
            }
            files.sort(null);
            for (Path f : files) {
                String file = f.getFileName().toString();
                file = file.substring(0, file.length() - ".json".length());
                out.add(new Listed(new Choice(origin, file), nameIn(f, file)));
            }
        }
        return out;
    }

    /** The "name" a theme or layout file gives itself, or {@code fallback} when it gives none or cannot be read. */
    private static String nameIn(Path file, String fallback) {
        try (java.io.Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            JsonReader json = new JsonReader(reader);
            json.setLenient(true);
            JsonElement read = JsonParser.parseReader(json);
            if (read.isJsonObject() && read.getAsJsonObject().get("name") instanceof JsonPrimitive p
                    && p.isString() && !p.getAsString().isBlank()) {
                return p.getAsString();
            }
        } catch (IOException | RuntimeException e) {
            // listed by its file's name; choosing it says what is wrong
        }
        return fallback;
    }

    private static Path dirOf(Origin origin) {
        return origin == Origin.USER ? userDir() : installDir();
    }

    /**
     * The theme chosen: its file, read; the factory theme when the choice is the factory's, or
     * when the file is missing or cannot be used, whose reason the log gives.
     *
     * @param choice the theme chosen
     * @return the theme; never {@code null}
     */
    public static AegisTheme loadTheme(Choice choice) {
        if (choice.origin() == Origin.FACTORY) {
            AegisTheme factory = AegisTheme.factory();
            Logger.info(Logger.System.UI, "Theme \"%s\": the engine's defaults.", factory.name());
            return factory;
        }
        Path file = dirOf(choice.origin()).resolve("themes").resolve(choice.file() + ".json");
        try {
            AegisTheme theme = AegisTheme.read(file);
            Logger.info(Logger.System.UI, "Theme \"%s\" from %s.", theme.name(), file);
            return theme;
        } catch (IOException e) {
            Logger.warn(Logger.System.UI, "%s cannot be read (%s).", file, e.getMessage());
        } catch (AegisTheme.Unusable e) {
            Logger.warn(Logger.System.UI, "%s", e.getMessage());
        }
        return AegisTheme.factory();   // the editor says so, with the theme it falls back from
    }

    /**
     * The theme chosen, read again for a running editor: Ctrl+R, or a save the theme watcher
     * noticed. A file that cannot be used — not JSON, not an object — keeps <b>the theme
     * already on screen</b>, and the log says why: falling to another would repaint the editor
     * under the hands of whoever is editing the file, because of a typo they are about to fix.
     * A file with wrong values is used, each wrong value at its default, as at startup. A file
     * gone altogether gives the factory theme.
     *
     * @param choice  the theme chosen
     * @param current the theme on screen now, kept if the file cannot be used
     * @return the theme read again; {@code current} itself when the file cannot be used
     */
    public static AegisTheme reloadTheme(Choice choice, AegisTheme current) {
        if (choice.origin() == Origin.FACTORY) return current;
        Path file = dirOf(choice.origin()).resolve("themes").resolve(choice.file() + ".json");
        if (!Files.exists(file)) {
            Logger.info(Logger.System.UI, "%s is gone; using the factory theme.", file);
            return AegisTheme.factory();
        }
        try {
            AegisTheme theme = AegisTheme.read(file);
            Logger.info(Logger.System.UI, "Theme \"%s\" reloaded from %s.", theme.name(), file);
            return theme;
        } catch (IOException e) {
            Logger.warn(Logger.System.UI, "%s cannot be read (%s); keeping the theme on screen, \"%s\".",
                file, e.getMessage(), current.name());
        } catch (AegisTheme.Unusable e) {
            Logger.warn(Logger.System.UI, "%s; keeping the theme on screen, \"%s\".", e.getMessage(), current.name());
        }
        return current;
    }

    /**
     * The theme a development run names with {@code -PthemeFile=<name>}: the user's
     * {@code themes/<name>.json} if it exists, else the installation's, else the factory theme.
     *
     * @param name the file's name without {@code .json}
     * @return the choice it amounts to
     */
    public static Choice themeNamed(String name) {
        return firstFound("themes", name);
    }

    /**
     * The layout the editor starts with when nothing was chosen: the first of the user's and
     * the installation's {@code layouts/<name>.json} that exists, else the factory layout.
     *
     * @param name the file's name without {@code .json}: {@code "default"}, unless a development
     *             run names a test layout
     * @return the choice it amounts to
     */
    public static Choice defaultLayout(String name) {
        return firstFound("layouts", name);
    }

    private static Choice firstFound(String kind, String name) {
        if (Files.isRegularFile(userDir().resolve(kind).resolve(name + ".json")))    return Choice.yours(name);
        if (Files.isRegularFile(installDir().resolve(kind).resolve(name + ".json"))) return Choice.installed(name);
        return Choice.FACTORY;
    }

    /**
     * The layouts to build screens from for the layout chosen, in the order they are tried:
     * the user's arrangement of it, if they made one; its file; the factory layout, always last.
     * A screen is taken from the first that describes it usably, so an arrangement holding only
     * the editor screen leaves the others to the layout. A file that cannot be used is passed
     * over with a warning; each reports its findings when the caller calls {@code report()}.
     *
     * @param choice the layout chosen
     * @return the usable files, first choice first, ending with the factory layout; never empty
     */
    public static AegisLayoutFile[] loadLayouts(Choice choice) {
        List<AegisLayoutFile> inLine = new ArrayList<>(3);
        Path arrangement = arrangementFile(choice);
        List<Path> candidates = new ArrayList<>(2);
        candidates.add(arrangement);
        if (choice.origin() != Origin.FACTORY) candidates.add(dirOf(choice.origin()).resolve("layouts").resolve(choice.file() + ".json"));
        for (Path candidate : candidates) {
            if (!Files.exists(candidate)) {
                if (candidate != arrangement) Logger.warn(Logger.System.UI, "%s is missing; using the factory layout.", candidate);
                continue;
            }
            try {
                AegisLayoutFile file = AegisLayoutFile.read(candidate);
                Logger.info(Logger.System.UI, candidate == arrangement ? "Your arrangement of the layout, from %s." : "Layout \"%2$s\" from %1$s.",
                    candidate, file.name());
                inLine.add(file);
            } catch (IOException e) {
                Logger.warn(Logger.System.UI, "%s cannot be read (%s); trying the next.", candidate, e.getMessage());
            } catch (AegisLayoutFile.Unusable e) {
                Logger.warn(Logger.System.UI, "%s; trying the next.", e.getMessage());
            }
        }
        inLine.add(FactoryLayout.read());
        return inLine.toArray(new AegisLayoutFile[0]);
    }

    /**
     * Where the user's arrangement of a layout is kept — the docks they resized or closed:
     * {@code arrangements/<origin>/<file>.json} in the user's directory, one per layout, so
     * the layout's own file is never written over and going back to a layout finds it as the
     * user left it.
     *
     * @param choice the layout arranged
     * @return the file's path; it may not exist
     */
    public static Path arrangementFile(Choice choice) {
        return userDir().resolve("arrangements").resolve(choice.origin().word())
            .resolve((choice.file() != null ? choice.file() : "factory") + ".json");
    }

    /**
     * Whether a layout file is one of the user's arrangements rather than a layout.
     *
     * @param source the file's {@code source()}
     * @return {@code true} for a file under {@code arrangements/}
     */
    public static boolean isArrangement(String source) {
        return !source.equals(FactoryLayout.SOURCE)
            && Path.of(source).toAbsolutePath().startsWith(userDir().resolve("arrangements"));
    }

    /**
     * Writes a screen the user rearranged into their arrangement of the layout chosen. Only
     * that screen is written, under {@code "screens"}; the file's other screens are kept. A
     * file that does not exist yet, or cannot be read, is started anew. Written to a temporary
     * file and moved over the old one, so a crash halfway never leaves half a file. Not written
     * when {@code aengine.layoutReadOnly} is set, as {@code -PtestShell} sets it.
     *
     * @param choice the layout arranged
     * @param name   the layout's own name, which the arrangement repeats
     * @param screen the screen's name — {@code "editor"}
     * @param object what {@link com.aengine.aegis.AegisScreens#describe} gives for it
     */
    public static void saveArrangement(Choice choice, String name, String screen, JsonObject object) {
        if (Boolean.getBoolean("aengine.layoutReadOnly")) {
            Logger.info(Logger.System.UI, "Arrangement not written: the test shell's layouts are read only.");
            return;
        }
        Path file = arrangementFile(choice);
        JsonObject top = readScreensFile(file);
        if (top == null) {
            top = new JsonObject();
            top.addProperty("name", name);
            top.addProperty("description", "Written by the editor when you resize or close panels: your arrangement"
                + " of this layout. Only the screens you changed are here; the others come from the layout itself."
                + " View > Undo my changes to this layout, or deleting this file, goes back to the layout as it is.");
            top.addProperty("format", AegisLayoutFile.FORMAT);
            top.add("screens", new JsonObject());
        }
        top.getAsJsonObject("screens").add(screen, object);
        writeJson(file, top, "Arrangement written to %s.");
    }

    /**
     * Takes a screen out of the user's arrangement of a layout, so it comes from the layout
     * again — View &gt; Undo my changes to this layout. With no screen left, the file is deleted.
     *
     * @param choice the layout arranged
     * @param screen the screen's name
     */
    public static void forgetArrangement(Choice choice, String screen) {
        if (Boolean.getBoolean("aengine.layoutReadOnly")) return;
        forgetScreen(arrangementFile(choice), screen);
    }

    /** The file read as an object holding {@code "screens"}, or {@code null} when it is missing or is not one. */
    private static JsonObject readScreensFile(Path file) {
        if (!Files.exists(file)) return null;
        try (java.io.Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            JsonReader json = new JsonReader(reader);
            json.setLenient(true);
            JsonElement read = JsonParser.parseReader(json);
            if (read.isJsonObject() && read.getAsJsonObject().get("screens") instanceof JsonObject) return read.getAsJsonObject();
        } catch (IOException | RuntimeException e) {
            Logger.warn(Logger.System.UI, "%s cannot be read (%s); it is written anew.", file, e.getMessage());
        }
        return null;
    }

    /**
     * The theme and the layout chosen last, from {@code editor.json} in the user's directory.
     * Each is read on its own: one that is missing or written wrong is {@code null}, with a
     * warning for the wrong one, and the other still counts.
     *
     * @return {@code [theme, layout]}, either {@code null} when not chosen
     */
    public static Choice[] readChoices() {
        Choice[] out = new Choice[2];
        Path file = settingsFile();
        if (!Files.exists(file)) return out;
        try (java.io.Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            JsonElement read = JsonParser.parseReader(reader);
            if (!read.isJsonObject()) {
                Logger.warn(Logger.System.UI, "%s is not a JSON object; the choices in it are passed over.", file);
                return out;
            }
            out[0] = choiceIn(read.getAsJsonObject(), "theme", file);
            out[1] = choiceIn(read.getAsJsonObject(), "layout", file);
        } catch (IOException | RuntimeException e) {
            Logger.warn(Logger.System.UI, "%s cannot be read (%s); the choices in it are passed over.", file, e.getMessage());
        }
        return out;
    }

    /** {@code "theme": {"origin": "installed", "file": "dark"}} as a choice; {@code null} with a warning when wrong. */
    private static Choice choiceIn(JsonObject top, String key, Path file) {
        JsonElement e = top.get(key);
        if (e == null) return null;
        if (e instanceof JsonObject o && o.get("origin") instanceof JsonPrimitive p && p.isString()) {
            Origin origin = Origin.ofWord(p.getAsString());
            if (origin == Origin.FACTORY) return Choice.FACTORY;
            if (origin != null && o.get("file") instanceof JsonPrimitive f && f.isString() && !f.getAsString().isBlank()) {
                return new Choice(origin, f.getAsString());
            }
        }
        Logger.warn(Logger.System.UI, "%s: \"%s\" should be {\"origin\": \"yours\", \"installed\" or \"factory\","
            + " \"file\": a name}; it is passed over.", file, key);
        return null;
    }

    /**
     * Keeps the theme and the layout chosen in {@code editor.json}, for the next start. Other
     * settings in the file are kept as they are.
     *
     * @param theme  the theme chosen
     * @param layout the layout chosen
     */
    public static void saveChoices(Choice theme, Choice layout) {
        Path file = settingsFile();
        JsonObject top = null;
        if (Files.exists(file)) {
            try (java.io.Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
                JsonElement read = JsonParser.parseReader(reader);
                if (read.isJsonObject()) top = read.getAsJsonObject();
            } catch (IOException | RuntimeException e) {
                Logger.warn(Logger.System.UI, "%s cannot be read (%s); it is written anew.", file, e.getMessage());
            }
        }
        if (top == null) top = new JsonObject();
        top.add("theme", choiceObject(theme));
        top.add("layout", choiceObject(layout));
        writeJson(file, top, "Choices written to %s.");
    }

    private static JsonObject choiceObject(Choice choice) {
        JsonObject o = new JsonObject();
        o.addProperty("origin", choice.origin().word());
        if (choice.file() != null) o.addProperty("file", choice.file());
        return o;
    }

    /** {@code editor.json} in the user's directory: the editor's settings. */
    public static Path settingsFile() {
        return userDir().resolve("editor.json");
    }

    /**
     * The texts for the language {@code tag} names — step 3h: {@code lang/<tag>.json}, with
     * the language's own file behind it — {@code pt.json} behind {@code pt-BR.json} — and the
     * English in code behind both. English itself needs no file. A file that is missing is
     * passed over; one that cannot be used is passed over with a warning; with neither, the
     * editor speaks English and says so.
     *
     * @param tag a BCP 47 tag — {@code "pt-BR"}; the system's when no setting chooses one
     * @return the locale to apply; {@link AegisLocale#english} when there is no file for it
     */
    public static AegisLocale loadLocale(String tag) {
        Locale wanted = Locale.forLanguageTag(tag);
        String language = wanted.getLanguage();
        if (language.isEmpty() || language.equals("en")) {
            Logger.info(Logger.System.UI, "Texts in English, as written in code.");
            return AegisLocale.english();
        }
        AegisLocale exact = readLocale(langDir().resolve(wanted.toLanguageTag() + ".json"));
        AegisLocale whole = wanted.getCountry().isEmpty() ? null : readLocale(langDir().resolve(language + ".json"));
        if (exact == null && whole == null) {
            Logger.info(Logger.System.UI, "No texts for %s in %s; the editor speaks English.", wanted.toLanguageTag(), langDir());
            return AegisLocale.english();
        }
        return exact != null ? exact.withFallback(whole) : whole;
    }

    private static AegisLocale readLocale(Path file) {
        if (!Files.exists(file)) return null;
        try {
            AegisLocale locale = AegisLocale.read(file);
            Logger.info(Logger.System.UI, "Texts \"%s\" from %s.", locale.name(), file);
            return locale;
        } catch (IOException e) {
            Logger.warn(Logger.System.UI, "%s cannot be read (%s); it is passed over.", file, e.getMessage());
        } catch (AegisLocale.Unusable e) {
            Logger.warn(Logger.System.UI, "%s; it is passed over.", e.getMessage());
        }
        return null;
    }

    /**
     * Says how far the locale applied has come, in one line — and, for a development run with
     * {@code -PlocaleCheck}, writes what is left to translate to {@code <checkDir>/<tag>.json},
     * each text as the locale file would hold it with its English, ready to be copied in.
     *
     * @param locale   the locale, once applied
     * @param tag      the language it is for
     * @param checkDir where to write what is left, or {@code null} for no listing
     */
    public static void reportLocale(AegisLocale locale, String tag, Path checkDir) {
        Map<String, String> missing = locale.missing();
        if (locale.source().equals("english")) {
            if (checkDir != null) Logger.info(Logger.System.UI, "Locale check: the editor speaks English; nothing to translate.");
            return;
        }
        int asked = locale.askedCount();
        Logger.info(Logger.System.UI, "Texts in %s: %d of %d translated, %d in English%s.", tag,
            asked - missing.size(), asked, missing.size(),
            missing.isEmpty() || checkDir != null ? "" : " (-PlocaleCheck lists them)");
        if (checkDir == null) return;

        StringBuilder out = new StringBuilder("{\n");
        int i = 0;
        for (Map.Entry<String, String> e : missing.entrySet()) {
            out.append("  ").append(new JsonPrimitive(e.getKey())).append(": ")
               .append(new JsonPrimitive(e.getValue())).append(++i < missing.size() ? ",\n" : "\n");
        }
        out.append("}\n");
        Path file = checkDir.resolve(tag + ".json");
        try {
            Files.createDirectories(checkDir);
            Files.writeString(file, out, StandardCharsets.UTF_8);
            Logger.info(Logger.System.UI, "Locale check: %d texts left to translate, written to %s.", missing.size(), file);
        } catch (IOException e) {
            Logger.warn(Logger.System.UI, "Locale check: %s cannot be written (%s).", file, e.getMessage());
        }
    }

    /** Takes a screen out of a file holding {@code "screens"}; deletes the file when none is left. */
    private static void forgetScreen(Path file, String screen) {
        if (!Files.exists(file)) return;
        try (java.io.Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            JsonReader json = new JsonReader(reader);
            json.setLenient(true);
            JsonElement read = JsonParser.parseReader(json);
            if (read.isJsonObject() && read.getAsJsonObject().get("screens") instanceof JsonObject screens) {
                screens.remove(screen);
                reader.close();
                if (screens.size() == 0) {
                    Files.delete(file);
                    Logger.info(Logger.System.UI, "Layout %s deleted: it held no other screen.", file);
                } else {
                    writeJson(file, read.getAsJsonObject(), "Arrangement written to %s.");
                }
                return;
            }
        } catch (IOException | RuntimeException e) {
            Logger.warn(Logger.System.UI, "%s cannot be read (%s); it is left as it is.", file, e.getMessage());
            return;
        }
        Logger.warn(Logger.System.UI, "%s holds no screens; it is left as it is.", file);
    }

    private static void writeJson(Path file, JsonObject top, String written) {
        String text = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create().toJson(top) + "\n";
        try {
            Files.createDirectories(file.getParent());
            Path temporary = file.resolveSibling(file.getFileName() + ".tmp");
            Files.writeString(temporary, text, StandardCharsets.UTF_8);
            Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            Logger.info(Logger.System.UI, written, file);
        } catch (IOException e) {
            Logger.warn(Logger.System.UI, "%s cannot be written (%s); what it would hold is kept until the editor closes.",
                file, e.getMessage());
        }
    }
}
