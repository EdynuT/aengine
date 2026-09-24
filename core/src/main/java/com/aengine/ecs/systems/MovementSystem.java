package com.aengine.ecs.systems;

import com.aengine.ecs.ComponentPool;
import com.aengine.ecs.Registry;
import com.aengine.ecs.System;
import com.aengine.ecs.components.TransformComponent;
import com.aengine.utils.Logger;

public final class MovementSystem extends System {

    @Override
    public void update(Registry registry, float deltaTime) {
        ComponentPool<TransformComponent> pool = registry.getPool(TransformComponent.class);
        
        // Fast-abort condition if the architecture has no allocated entities for this pool
        if (pool == null || pool.size() == 0) {
            return;
        }

        TransformComponent[] transforms = pool.getRawComponents();
        int[] denseToEntity = pool.getRawDenseToEntity();
        int totalElements = pool.size();

        // High-performance contiguous memory scan line loop (L1/L2 cache prefetch safe)
        for (int i = 0; i < totalElements; i++) {
            TransformComponent transform = transforms[i];
            int entityID = denseToEntity[i];

            // Simulation step: linear translation along X and Y axes (agnostic 2D/3D execution)
            transform.position.x += 1.5f * deltaTime;
            transform.position.y += 0.5f * deltaTime;

            Logger.trace(Logger.System.CORE, "System [Movement] updated entity ID: %d | New Position: (%f, %f, %f)", 
                    entityID, transform.position.x, transform.position.y, transform.position.z);
        }
    }
}
