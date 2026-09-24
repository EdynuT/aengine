package com.aengine.ecs.systems;

import com.aengine.ecs.Registry;
import com.aengine.ecs.components.ScriptComponent;
import com.aengine.ecs.components.TransformComponent;
import com.aengine.utils.FileSystem;
import com.aengine.utils.Logger;

import java.io.File;
import java.util.HashMap;
import java.util.Map;

public class ScriptSystem {

    // Public contract that plugins must follow
    public interface ScriptRuntime {
        void initialize(ScriptComponent script, TransformComponent transform, File scriptFile);
        void update(ScriptComponent script, float deltaTime);
    }

    private final Map<String, ScriptRuntime> runtimes = new HashMap<>();

    public ScriptSystem() {
        // Tries to register runtimes for different scripting languages. If the dependency is not present in the user's Gradle, the JVM throws an exception
        registerRuntime(".lua", "com.aengine.ecs.systems.plugins.LuaRuntime");
        registerRuntime(".js",  "com.aengine.ecs.systems.plugins.JavascriptRuntime");
        registerRuntime(".py",  "com.aengine.ecs.systems.plugins.PythonRuntime");
    }

    private void registerRuntime(String extension, String fullyQualifiedClassName) {
        try {
            Class<?> clazz = Class.forName(fullyQualifiedClassName);
            ScriptRuntime runtime = (ScriptRuntime) clazz.getDeclaredConstructor().newInstance();
            runtimes.put(extension, runtime);
            Logger.info(Logger.System.CORE, "Scripting runtime bound for extension: " + extension);
        } catch (Throwable e) {
            // Throwable is intentionally caught to capture NoClassDefFoundError.
            // The plugin is not installed.
        }
    }

    public void update(Registry registry, float deltaTime) {
        var scriptedEntities = registry.getEntitiesWith(TransformComponent.class, ScriptComponent.class);

        for (int i = 0; i < scriptedEntities.size(); i++) {
            int entity = scriptedEntities.get(i);
            ScriptComponent script = registry.getComponent(entity, ScriptComponent.class);
            TransformComponent transform = registry.getComponent(entity, TransformComponent.class);

            if (script == null || transform == null || script.scriptPath == null) continue;

            String extension = getExtension(script.scriptPath);
            ScriptRuntime runtime = runtimes.get(extension);

            if (runtime == null) {
                Logger.error(Logger.System.CORE, "No runtime installed for script type [%s]: %s. Did you enable the plugin in build.gradle?", extension, script.scriptPath);
                script.scriptPath = null; 
                continue;
            }

            if (script.internalRuntimeState == null) {
                File file = FileSystem.resolve(script.scriptPath);
                if (file.exists()) {
                    runtime.initialize(script, transform, file);
                }
            }

            if (script.internalRuntimeState != null) {
                runtime.update(script, deltaTime);
            }
        }
    }

    private String getExtension(String path) {
        int lastDot = path.lastIndexOf('.');
        return (lastDot == -1) ? "" : path.substring(lastDot).toLowerCase();
    }
}
