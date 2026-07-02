package com.aengine.utils;

import com.aengine.graphics.AssetManager;
import java.io.File;
import java.nio.file.*;

import static java.nio.file.StandardWatchEventKinds.*;

/**
 * HARDWARE CONTEXT: KERNEL-LEVEL DIRECTORY MONITOR
 * Hooks into the OS (inotify/ReadDirectoryChanges) to trigger zero-downtime VRAM injections.
 */
public class AssetWatcher {

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
                        String fileName = modifiedFile.toString().toLowerCase();

                        // Intercept only modifications in PNG or JPG
                        if (fileName.endsWith(".png") || fileName.endsWith(".jpg") || fileName.endsWith(".jpeg")) {
                            File srcFile = texturesSrcDir.resolve(modifiedFile).toFile();
                            
                            if (srcFile.exists()) {
                                // 1. Instruct the AssetBaker to compile ONLY this file
                                AssetBaker.compileTexture(srcFile, rootSource, rootTarget);
                                
                                // 2. Translate the name to the virtual path (.atex) and notify the ECS
                                String bakedName = fileName.substring(0, fileName.lastIndexOf('.')) + ".atex";
                                String virtualPath = "assets://baked/textures/" + bakedName;
                                
                                AssetManager.hotReloadTexture(virtualPath);
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
}
