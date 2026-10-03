package com.aengine.editor;

import com.aengine.aegis.AegisLayoutFile;
import com.aengine.aegis.AegisTheme;
import com.aengine.utils.Logger;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

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
}
