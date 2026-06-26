package com.aengine.utils;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

public class DynamicClassLoader extends ClassLoader {
    private final Path classDir;

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
