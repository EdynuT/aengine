package com.aengine.ecs.components;

/**
 * HARDWARE CONTEXT: ECS SCRIPT MEMORY BLOCK
 * Stores the virtual path to the script and caches the JIT-compiled function 
 * (Lua, JS, etc.) to prevent disk I/O and recompilation during the hot loop.
 */
public class ScriptComponent {
    /**
     * Virtual path of the script. Its extension ({@code .lua}, {@code .js}, {@code .py}) picks
     * the runtime. The script system sets it to {@code null} when no runtime is installed for
     * that extension, which switches the script off.
     */
    public String scriptPath;

    /**
     * Agnostic state. The Java JVM ignores the type until the ScriptSystem casts it.
     * {@code null} means the script has not been compiled yet; the runtime fills it on the
     * first update.
     */
    public Object internalRuntimeState = null;

    /** Creates a component with no script attached. */
    public ScriptComponent() {}
}
