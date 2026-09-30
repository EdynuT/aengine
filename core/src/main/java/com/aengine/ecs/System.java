package com.aengine.ecs;

/**
 * Base class for the per-frame ECS systems.
 *
 * <p>A system holds behaviour and no entity data: each frame it asks the {@link Registry}
 * for the components it cares about and changes them in place. Any state a subclass
 * keeps (cached vectors, mouse deltas, and so on) belongs to the system itself, not to
 * an entity.</p>
 *
 * <p>Not every system extends this class. {@link com.aengine.ecs.systems.PhysicsSystem}
 * runs on its own fixed timestep and {@link com.aengine.ecs.systems.ScriptSystem} runs
 * scripting plugins, so both expose their own {@code update} with the same parameters.</p>
 */
public abstract class System {

    /** Constructor for subclasses; a system has no state of its own at this level. */
    protected System() {}

    /**
     * Executes the internal logic of the system across the targeted component pools.
     * @param registry The central ECS registry containing memory pools.
     * @param deltaTime The high-resolution time delta slice for the current frame.
     */
    public abstract void update(Registry registry, float deltaTime);
}
