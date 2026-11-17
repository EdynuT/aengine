package com.aengine.utils;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Loads game classes from a directory of compiled {@code .class} files, so a new loader can
 * pick up recompiled code without restarting the engine.
 *
 * <p>Classes in the {@code com.aengine} packages, and anything not found in the directory,
 * come from the application class loader as usual. Not used by the engine today; intended
 * for game-code hot reload.</p>
 */
public class DynamicClassLoader extends ClassLoader {
    private final Path classDir;

    /**
     * Creates a loader reading from a class directory.
     *
     * @param classDir root of the compiled class tree (the folder that holds the top-level
     *                 package directories)
     */
    public DynamicClassLoader(Path classDir) {
        super(ClassLoader.getSystemClassLoader());
        this.classDir = classDir;
    }

    @Override
    public Class<?> findClass(String name) throws ClassNotFoundException {
        // Only intercept local game classes, delegate java/lwjgl packages to parent
        if (!name.startsWith("com.aengine")) {
            try {
                Path classFile = classDir.resolve(name.replace('.', '/') + ".class");
                if (Files.exists(classFile)) {
                    byte[] bytes = Files.readAllBytes(classFile);
                    return defineClass(name, bytes, 0, bytes.length);
                }
            } catch (IOException e) {
                Logger.error(Logger.System.CORE, "Failed to read binary stream for class: %s", name);
            }
        }
        return super.findClass(name);
    }
}
