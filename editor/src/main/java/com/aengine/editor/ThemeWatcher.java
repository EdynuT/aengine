package com.aengine.editor;

import com.aengine.utils.Logger;

import java.io.IOException;
import java.nio.file.ClosedWatchServiceException;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardWatchEventKinds;
import java.nio.file.WatchEvent;
import java.nio.file.WatchKey;
import java.nio.file.WatchService;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Notices when the theme in use is saved, so the editor can apply it at once — step 3f part
 * 5b, the theme author's development option (§7, <em>Changing the theme</em>).
 *
 * <p>Off unless asked for: an end user never has their interface change because a file was
 * touched. It is the editor's own, and never {@code AssetWatcher}, which serves the projects
 * built with the engine; the editor's interface is not one of them.</p>
 *
 * <ul>
 *   <li><b>It watches directories, not a file</b> — {@code WatchService} works that way — the
 *       {@code themes/} directories of the user and of the installation, and keeps only the
 *       events for the theme in use.</li>
 *   <li><b>It waits for a save to finish.</b> An editor often saves by writing a temporary file
 *       and renaming it over the old one, which raises several events for one save; the change
 *       is announced once no event has come for {@value #SETTLE_MILLIS} ms.</li>
 *   <li><b>It never touches the interface.</b> Its thread only raises a flag; the editor reads
 *       it with {@link #takeChange} at the start of a frame and reloads there, on the thread
 *       that owns the interface.</li>
 * </ul>
 *
 * <p>Layouts are not watched: the editor writes the user's layout itself (Phase 4), and must
 * never react to its own writes.</p>
 */
public final class ThemeWatcher implements AutoCloseable {

    /** How long a save must be quiet before it counts as finished. */
    private static final long SETTLE_MILLIS = 200;

    private final WatchService service;
    private final String file;
    private final AtomicBoolean changed = new AtomicBoolean();
    private final Thread thread;

    private ThemeWatcher(WatchService service, String file) {
        this.service = service;
        this.file    = file;
        this.thread  = new Thread(this::run, "theme-watcher");
        this.thread.setDaemon(true);   // never keeps the editor from exiting
    }

    /**
     * Starts watching the theme called {@code name} in the user's and the installation's
     * {@code themes/} directories — those that exist.
     *
     * @param name the theme's file name without {@code .json}, e.g. {@code "dark"}
     * @return the running watcher, or {@code null} if there is nothing to watch or the system
     *         refuses, which is logged; the editor then simply does not reload on save
     */
    public static ThemeWatcher start(String name) {
        List<Path> directories = new ArrayList<>(2);
        for (Path dir : new Path[] { ShellFiles.userDir().resolve("themes"), ShellFiles.installDir().resolve("themes") }) {
            if (Files.isDirectory(dir)) directories.add(dir);
        }
        if (directories.isEmpty()) {
            Logger.warn(Logger.System.UI, "Theme watching is on, but there is no themes directory to watch.");
            return null;
        }
        try {
            WatchService service = FileSystems.getDefault().newWatchService();
            for (Path dir : directories) {
                dir.register(service, StandardWatchEventKinds.ENTRY_CREATE, StandardWatchEventKinds.ENTRY_MODIFY);
            }
            ThemeWatcher watcher = new ThemeWatcher(service, name + ".json");
            watcher.thread.start();
            Logger.info(Logger.System.UI, "Theme watching is on (a development option): saving %s in %s"
                + " applies it at once.", watcher.file, directories);
            return watcher;
        } catch (IOException e) {
            Logger.warn(Logger.System.UI, "Theme watching could not start (%s); themes apply on Ctrl+R only.", e.getMessage());
            return null;
        }
    }

    /**
     * Whether the theme was saved since the last call — and forgets it. Called by the editor
     * at the start of a frame.
     *
     * @return {@code true} once per finished save
     */
    public boolean takeChange() {
        return changed.getAndSet(false);
    }

    /** Stops watching; the thread ends on its own. */
    @Override
    public void close() {
        try {
            service.close();
        } catch (IOException e) {
            Logger.warn(Logger.System.UI, "Theme watching did not stop cleanly: %s", e.getMessage());
        }
    }

    /**
     * The watcher's thread: waits for events, keeps the ones for the theme in use, and once a
     * save has been quiet for {@link #SETTLE_MILLIS}, raises the flag.
     */
    private void run() {
        long lastEvent = 0;   // when the last event for the theme came; 0 when none is waiting
        try {
            while (true) {
                WatchKey key = lastEvent == 0
                    ? service.take()
                    : service.poll(Math.max(1, lastEvent + SETTLE_MILLIS - System.currentTimeMillis()), TimeUnit.MILLISECONDS);

                if (key != null) {
                    for (WatchEvent<?> event : key.pollEvents()) {
                        if (event.context() instanceof Path changedFile && changedFile.toString().equals(file)) {
                            lastEvent = System.currentTimeMillis();
                        }
                    }
                    key.reset();
                }
                if (lastEvent != 0 && System.currentTimeMillis() - lastEvent >= SETTLE_MILLIS) {
                    lastEvent = 0;
                    changed.set(true);
                }
            }
        } catch (ClosedWatchServiceException | InterruptedException e) {
            // closed by the editor: the thread ends
        }
    }
}
