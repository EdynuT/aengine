package com.aengine.ecs.systems;

import com.aengine.ecs.Registry;
import com.aengine.ecs.components.ScriptComponent;
import com.aengine.ecs.components.TransformComponent;
import com.aengine.utils.FileSystem;
import com.aengine.utils.Logger;
import org.luaj.vm2.Globals;
import org.luaj.vm2.LuaValue;
import org.luaj.vm2.lib.jse.CoerceJavaToLua;
import org.luaj.vm2.lib.jse.JsePlatform;

import java.io.File;
import java.util.List;

public class ScriptSystem {

    public void update(Registry registry, float deltaTime) {
        var scriptedEntities = registry.getEntitiesWith(TransformComponent.class, ScriptComponent.class);

        for (int i = 0; i < scriptedEntities.size(); i++) {
            int entity = scriptedEntities.get(i);
            ScriptComponent script = registry.getComponent(entity, ScriptComponent.class);
            TransformComponent transform = registry.getComponent(entity, TransformComponent.class);

            // Defensive null guard: with the physics syncLock in place this should never
            // fire, but protects against future multi-threaded ECS changes.
            if (script == null || transform == null) continue;

            // 1. Lazy Initialization:
            // Compiles the script only the first time the entity runs in the loop.
            if (script.luaGlobals == null && script.scriptPath != null) {
                initializeScript(script, transform);
            }

            // 2. Execution: Calls the Lua onUpdate function passing the deltaTime
            if (script.updateFunction != null && !script.updateFunction.isnil()) {
                try {
                    script.updateFunction.call(LuaValue.valueOf(deltaTime));
                } catch (Exception e) {
                    Logger.error(Logger.System.CORE, "Lua Runtime Error in script [%s]: %s", script.scriptPath, e.getMessage());
                }
            }
        }
    }

    private void initializeScript(ScriptComponent script, TransformComponent transform) {
        try {
            File file = FileSystem.resolve(script.scriptPath);
            if (!file.exists()) return;

            // Instantiates the standard Lua environment
            Globals globals = JsePlatform.standardGlobals();
            
            // MEMORY BINDING: Injects the Java TransformComponent pointer directly into Lua
            // Any changes to 'transform' within Lua will affect the memory in Java RAM.
            globals.set("transform", CoerceJavaToLua.coerce(transform));

            // Reads and compiles the file from disk
            globals.get("dofile").call(LuaValue.valueOf(file.getAbsolutePath()));

            // Stores the global reference and extracts the onUpdate function for caching
            script.luaGlobals = globals;
            script.updateFunction = globals.get("onUpdate");

        } catch (Exception e) {
            Logger.error(Logger.System.CORE, "Failed to compile Lua script [%s]: %s", script.scriptPath, e.getMessage());
        }
    }
}
