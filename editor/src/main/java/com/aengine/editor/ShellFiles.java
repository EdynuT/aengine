package com.aengine.editor;

import com.aengine.aegis.AegisTheme;
import com.aengine.utils.Logger;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Where the shell's files live, and which of them the editor uses — the one place that knows
 * (§7, <em>Where the files live</em>).
 *
 * <p>Two directories. The <b>installation's</b>, {@code <install>/ui/}, holds the defaults the
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

    /**
     * {@code <install>/ui/}: the shipped defaults. {@code aengine.home} if set, else the working directory.
     *
     * @return the absolute path; it may not exist
     */
    public static Path installDir() {
        String home = System.getProperty("aengine.home");
        return (home != null ? Path.of(home) : Path.of("")).toAbsolutePath().resolve("ui");
    }

    /**
     * The user's {@code AEngine/ui/}: {@code aengine.userdata} if set; on Windows
     * {@code AEngine/ui} under {@code %LOCALAPPDATA%}; elsewhere {@code $XDG_DATA_HOME/AEngine/ui}, which
     * defaults to {@code ~/.local/share/AEngine/ui}.
     *
     * @return the absolute path; it may not exist
     */
    public static Path userDir() {
        String override = System.getProperty("aengine.userdata");
        if (override != null) return Path.of(override).toAbsolutePath();

        String localAppData = System.getenv("LOCALAPPDATA");
        if (localAppData != null && System.getProperty("os.name", "").startsWith("Windows")) {
            return Path.of(localAppData, "AEngine", "ui");
        }
        String xdg = System.getenv("XDG_DATA_HOME");
        Path data = xdg != null && !xdg.isBlank()
            ? Path.of(xdg)
            : Path.of(System.getProperty("user.home"), ".local", "share");
        return data.resolve("AEngine").resolve("ui");
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
}
