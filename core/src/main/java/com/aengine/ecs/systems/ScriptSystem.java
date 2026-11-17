package com.aengine.ecs.systems;

import com.aengine.ecs.Registry;
import com.aengine.ecs.components.ScriptComponent;
import com.aengine.ecs.components.TransformComponent;
import com.aengine.utils.FileSystem;
import com.aengine.utils.Logger;

import java.io.File;
import java.util.HashMap;
import java.util.Map;

/**
 * Runs entity scripts through optional language plugins.
 *
 * <p>Each supported language lives in its own plugin class (Lua, JavaScript, Python). At
 * construction the system tries to load each one by name; a plugin that is not on the
 * classpath is skipped quietly. Every frame, each entity with a {@link TransformComponent}
 * and a {@link ScriptComponent} is handed to the runtime that matches its script's file
 * extension.</p>
 *
 * <p>A script whose extension has no runtime is reported once as an error and then
 * switched off by setting its {@code scriptPath} to {@code null}.</p>
 */
public class ScriptSystem {

    /**
     * Public contract that plugins must follow.
     *
     * <p>An implementation needs a public no-argument constructor and must live at one of
     * the class names the system looks for, e.g.
     * {@code com.aengine.ecs.systems.plugins.LuaRuntime}.</p>
     */
    public interface ScriptRuntime {
        /**
         * Compiles a script and binds it to its entity. Called when the component has no
         * compiled state yet and the script file exists. The implementation must store its
         * compiled state in {@link ScriptComponent#internalRuntimeState}; while that stays
         * {@code null}, this method is called again on the next frame.
         *
         * @param script     the entity's script component
         * @param transform  the entity's transform, for the script to read and move
         * @param scriptFile the script file on disk, already resolved from its virtual path
         */
        void initialize(ScriptComponent script, TransformComponent transform, File scriptFile);

        /**
         * Runs one frame of an already initialised script.
         *
         * @param script    the entity's script component, with its compiled state
         * @param deltaTime seconds since the previous frame
         */
        void update(ScriptComponent script, float deltaTime);
    }

    private final Map<String, ScriptRuntime> runtimes = new HashMap<>();

    /** Creates the system and registers every scripting plugin found on the classpath. */
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

    /**
     * Initialises new scripts and runs one frame of every active script.
     *
     * @param registry  the ECS world
     * @param deltaTime seconds since the previous frame
     */
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
