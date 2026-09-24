package com.aengine.ecs.components;

/**
 * HARDWARE CONTEXT: ECS SCRIPT MEMORY BLOCK
 * Stores the virtual path to the script and caches the JIT-compiled function 
 * (Lua, JS, etc.) to prevent disk I/O and recompilation during the hot loop.
 */
public class ScriptComponent {
    public String scriptPath;
    
    // Agnostic state. The Java JVM ignores the type until the ScriptSystem casts it.
    public Object internalRuntimeState = null;

    public ScriptComponent() {}
}
