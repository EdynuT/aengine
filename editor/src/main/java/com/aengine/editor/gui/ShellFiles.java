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
 * the system's per-user data directory, holds what the user made or changed. A file is looked
 * for in the user's first, then the installation's, and when neither has a usable one the
 * factory defaults compiled into the engine are used, so the editor always opens.</p>
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

        /** @return how the log says it */
        public String word() { return word; }
    }

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

    /**
     * The theme called {@code name}: the user's {@code themes/<name>.json}, else the
     * installation's, else the factory theme. A file that is missing is passed over quietly —
     * the user normally has none — and one that cannot be read or used is passed over with a
     * warning saying why.
     *
     * @param name the theme's file name without {@code .json}, e.g. {@code "dark"}
     * @return the first usable theme found, or the factory theme; never {@code null}
     */
    public static AegisTheme loadTheme(String name) {
        String file = name + ".json";
        for (Path candidate : new Path[] {
                userDir().resolve("themes").resolve(file),
                installDir().resolve("themes").resolve(file) }) {
            if (!Files.exists(candidate)) continue;
            try {
                AegisTheme theme = AegisTheme.read(candidate);
                Logger.info(Logger.System.UI, "Theme \"%s\" from %s.", theme.name(), candidate);
                return theme;
            } catch (IOException e) {
                Logger.warn(Logger.System.UI, "%s cannot be read (%s); trying the next.", candidate, e.getMessage());
            } catch (AegisTheme.Unusable e) {
                Logger.warn(Logger.System.UI, "%s; trying the next.", e.getMessage());
            }
        }
        Logger.info(Logger.System.UI, "No theme \"%s\" found; using the factory theme.", name);
        return AegisTheme.factory();
    }

    /**
     * The theme called {@code name}, read again for a running editor — step 3f part 5.
     *
     * <p>The file is found as {@link #loadTheme} finds it: the user's, else the installation's.
     * What differs is a file that cannot be used — not JSON, not an object: at startup the next
     * in line is taken, but here <b>the theme already on screen is kept</b>, and the log says
     * why. Falling to another theme would repaint the editor under the hands of whoever is
     * editing the file, because of a typo they are about to fix. A file that can be used but
     * has wrong values is used, each wrong value at its default, exactly as at startup.</p>
     *
     * <p>When neither directory has the file any more, the factory theme is used, as at
     * startup.</p>
     *
     * @param name    the theme's file name without {@code .json}, e.g. {@code "dark"}
     * @param current the theme on screen now, kept if the file cannot be used
     * @return the theme read again; {@code current} itself when the file cannot be used
     */
    public static AegisTheme reloadTheme(String name, AegisTheme current) {
        String file = name + ".json";
        for (Path candidate : new Path[] {
                userDir().resolve("themes").resolve(file),
                installDir().resolve("themes").resolve(file) }) {
            if (!Files.exists(candidate)) continue;
            try {
                AegisTheme theme = AegisTheme.read(candidate);
                Logger.info(Logger.System.UI, "Theme \"%s\" reloaded from %s.", theme.name(), candidate);
                return theme;
            } catch (IOException e) {
                Logger.warn(Logger.System.UI, "%s cannot be read (%s); keeping the theme on screen, \"%s\".",
                    candidate, e.getMessage(), current.name());
            } catch (AegisTheme.Unusable e) {
                Logger.warn(Logger.System.UI, "%s; keeping the theme on screen, \"%s\".", e.getMessage(), current.name());
            }
            return current;
        }
        Logger.info(Logger.System.UI, "No theme \"%s\" found any more; using the factory theme.", name);
        return AegisTheme.factory();
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

    /**
     * The layouts to build screens from, in the order they are tried: the user's
     * {@code layouts/<name>.json}, the installation's, and the factory layout, which is always last
     * and always there. A screen is taken from the first of them that describes it usably
     * ({@link com.aengine.aegis.AegisScreens#build}), so the user's file may lay out one
     * screen and leave the others to the next in line.
     *
     * <p>A file that is missing is passed over quietly — the user normally has none — and one
     * that cannot be read or used is passed over with a warning saying why. Each file returned
     * reports its findings when the caller calls {@code report()}, once its screens are built.</p>
     *
     * @param name the file's name without {@code .json}: {@code "default"}, unless a development
     *             run names a test layout
     * @return the usable files, first choice first, ending with the factory layout; never empty
     */
    public static AegisLayoutFile[] loadLayouts(String name) {
        String file = name + ".json";
        List<AegisLayoutFile> inLine = new ArrayList<>(3);
        for (Path candidate : new Path[] {
                userDir().resolve("layouts").resolve(file),
                installDir().resolve("layouts").resolve(file) }) {
            if (!Files.exists(candidate)) continue;
            try {
                AegisLayoutFile layout = AegisLayoutFile.read(candidate);
                Logger.info(Logger.System.UI, "Layout \"%s\" from %s.", layout.name(), candidate);
                inLine.add(layout);
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
     * Writes a screen the user rearranged into their own {@code layouts/<name>.json} — step 4c.
     *
     * <p>Only that screen is written, under {@code "screens"}: the file's other screens are kept
     * as they are, and screens it does not hold go on coming from the installation's layout,
     * so an update that improves them still reaches the user (§7). A file that does not exist
     * yet, or cannot be read, is started anew. Written to a temporary file first and then moved
     * over the old one, so a crash halfway never leaves half a layout.</p>
     *
     * <p>Not written when {@code aengine.layoutReadOnly} is set, as {@code -PtestShell} sets it,
     * so trying a test layout never changes it.</p>
     *
     * @param name   the file's name without {@code .json} — {@code "default"}
     * @param screen the screen's name — {@code "editor"}
     * @param object what {@link com.aengine.aegis.AegisScreens#describe} gives for it
     */
    public static void saveLayout(String name, String screen, JsonObject object) {
        if (Boolean.getBoolean("aengine.layoutReadOnly")) {
            Logger.info(Logger.System.UI, "Arrangement not written: the test shell's layouts are read only.");
            return;
        }
        Path file = userDir().resolve("layouts").resolve(name + ".json");
        JsonObject top = null;
        if (Files.exists(file)) {
            try (java.io.Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
                JsonReader json = new JsonReader(reader);
                json.setLenient(true);
                JsonElement read = JsonParser.parseReader(json);
                if (read.isJsonObject() && read.getAsJsonObject().get("screens") instanceof JsonObject) top = read.getAsJsonObject();
            } catch (IOException | RuntimeException e) {
                Logger.warn(Logger.System.UI, "%s cannot be read (%s); it is written anew.", file, e.getMessage());
            }
        }
        if (top == null) {
            top = new JsonObject();
            top.addProperty("name", "Your arrangement");
            top.addProperty("description", "Written by the editor when you resize or close panels. Only the screens"
                + " you changed are here; the others come from the engine's layout. View > Restore default layout,"
                + " or deleting this file, goes back to the engine's.");
            top.addProperty("format", com.aengine.aegis.AegisLayoutFile.FORMAT);
            top.add("screens", new JsonObject());
        }
        top.getAsJsonObject("screens").add(screen, object);
        writeJson(file, top);
    }

    /**
     * Takes a screen out of the user's own {@code layouts/<name>.json}, so it comes from the
     * installation's layout again — View &gt; Restore default layout. With no screen left, the
     * file is deleted.
     *
     * @param name   the file's name without {@code .json}
     * @param screen the screen's name
     */
    public static void forgetLayout(String name, String screen) {
        if (Boolean.getBoolean("aengine.layoutReadOnly")) return;
        Path file = userDir().resolve("layouts").resolve(name + ".json");
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
                    writeJson(file, read.getAsJsonObject());
                }
                return;
            }
        } catch (IOException | RuntimeException e) {
            Logger.warn(Logger.System.UI, "%s cannot be read (%s); it is left as it is.", file, e.getMessage());
            return;
        }
        Logger.warn(Logger.System.UI, "%s holds no screens; it is left as it is.", file);
    }

    private static void writeJson(Path file, JsonObject top) {
        String text = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create().toJson(top) + "\n";
        try {
            Files.createDirectories(file.getParent());
            Path temporary = file.resolveSibling(file.getFileName() + ".tmp");
            Files.writeString(temporary, text, StandardCharsets.UTF_8);
            Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            Logger.info(Logger.System.UI, "Arrangement written to %s.", file);
        } catch (IOException e) {
            Logger.warn(Logger.System.UI, "%s cannot be written (%s); the arrangement is kept until the editor closes.",
                file, e.getMessage());
        }
    }
}
