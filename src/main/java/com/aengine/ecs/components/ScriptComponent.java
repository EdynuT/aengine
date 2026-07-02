package com.aengine.ecs.components;

import org.luaj.vm2.LuaValue;

/**
 * HARDWARE CONTEXT: ECS SCRIPT MEMORY BLOCK
 * Stores the virtual path to the script and caches the JIT-compiled Lua function 
 * to prevent disk I/O and recompilation during the hot loop.
 */
public class ScriptComponent {
    public String scriptPath;
    
    // Cached references to the Lua runtime objects
    public LuaValue luaGlobals;
    public LuaValue updateFunction;

    public ScriptComponent() {}
}
