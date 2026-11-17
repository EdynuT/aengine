package com.aengine.utils;

import com.aengine.graphics.AssetManager;
import java.io.File;
import java.nio.file.*;

import static java.nio.file.StandardWatchEventKinds.*;

/**
 * HARDWARE CONTEXT: KERNEL-LEVEL DIRECTORY MONITOR
 * Hooks into the OS (inotify/ReadDirectoryChanges) to trigger zero-downtime VRAM injections.
 *
 * <p>Watches the project's {@code assets/src/textures} folder. When a PNG or JPG there is
 * created or saved, it is re-baked to {@code .atex} and the cached texture is reloaded, so
 * the change shows in the running editor without a restart. Sub-folders are not watched,
 * and audio is not watched.</p>
 */
public class AssetWatcher {

    private AssetWatcher() {}

    /**
     * Starts watching on a background daemon thread and returns at once. If the textures
     * folder does not exist, the thread ends quietly. Call once per project; each call
     * starts another thread.
     *
     * @param projectRoot absolute path of the game project
     */
    public static void start(String projectRoot) {
        Thread watcherThread = new Thread(() -> {
            try (WatchService watchService = FileSystems.getDefault().newWatchService()) {
                
                // Observe only the textures source directory for changes
                Path texturesSrcDir = Paths.get(projectRoot, "assets", "src", "textures");
                if (!Files.exists(texturesSrcDir)) return;
                
                texturesSrcDir.register(watchService, ENTRY_CREATE, ENTRY_MODIFY);
                Logger.info(Logger.System.ASSET, "Kernel WatchService active on: %s", texturesSrcDir.toString());

                File rootSource = new File(projectRoot, "assets/src");
                File rootTarget = new File(projectRoot, "assets/baked");

                while (true) {
                    WatchKey key = watchService.take();
                    
                    // Small debounce (50ms) because image editors often generate multiple rapid save events
                    Thread.sleep(50); 

                    for (WatchEvent<?> event : key.pollEvents()) {
                        if (event.kind() == OVERFLOW) continue;

                        Path modifiedFile = (Path) event.context();
                        String fileName = modifiedFile.toString();
                        String lowerName = fileName.toLowerCase();

                        // Intercept only modifications in PNG or JPG (any case of extension)
                        if (lowerName.endsWith(".png") || lowerName.endsWith(".jpg") || lowerName.endsWith(".jpeg")) {
                            File srcFile = texturesSrcDir.resolve(modifiedFile).toFile();
                            
                            if (srcFile.exists()) {
                                // 1. Instruct the AssetBaker to compile ONLY this file
                                AssetBaker.compileTexture(srcFile, rootSource, rootTarget);
                                
                                // 2. Translate the name to the virtual path (.atex) and notify the ECS
                                AssetManager.hotReloadTexture(bakedTexturePath(fileName));
                            }
                        }
                    }
                    
                    if (!key.reset()) break; // Cancel if the directory is deleted by the OS
                }
            } catch (Exception e) {
                Logger.error(Logger.System.ASSET, "WatchService terminated: %s", e.getMessage());
            }
        });

        watcherThread.setDaemon(true);
        watcherThread.setName("AEngine-AssetWatcher");
        watcherThread.start();
    }

    /**
     * The virtual path the baker writes a source image to: its name as it is, with the
     * extension replaced by {@code .atex}. The case is kept, as {@link AssetBaker} keeps it
     * and the texture cache compares paths exactly.
     *
     * @param sourceFileName the image's file name, e.g. {@code "Box.png"}
     * @return e.g. {@code "assets://baked/textures/Box.atex"}
     */
    static String bakedTexturePath(String sourceFileName) {
        return "assets://baked/textures/" + sourceFileName.substring(0, sourceFileName.lastIndexOf('.')) + ".atex";
    }
}
