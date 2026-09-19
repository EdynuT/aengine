package com.aengine.ecs;

public abstract class System {
    
    /**
     * Executes the internal logic of the system across the targeted component pools.
     * @param registry The central ECS registry containing memory pools.
     * @param deltaTime The high-resolution time delta slice for the current frame.
     */
    public abstract void update(Registry registry, float deltaTime);
}
