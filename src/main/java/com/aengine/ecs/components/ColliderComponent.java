package com.aengine.ecs.components;

import org.joml.Vector3f;

/**
 * HARDWARE CONTEXT: ECS COLLIDER MEMORY BLOCK
 * Contiguous data structure tracking collision shape types, spatial offsets, and hit states.
 * Aligned with JOML vectors for cache-friendly transformation pipelines.
 */
public class ColliderComponent {
    
    public ColliderType type;
    
    // Extents/Size: For AABB/OBB represents half the size (half-extents). For SPHERE, x is the radius.
    public final Vector3f size = new Vector3f(0.5f, 0.5f, 0.5f);
    
    // Local Offset: Allows shifting the hitbox relative to the entity's Transform center
    public final Vector3f offset = new Vector3f(0.0f, 0.0f, 0.0f);
    
    // Trigger configuration (If true, detects intersection but does not apply physical response)
    public boolean isTrigger = false;
    
    // Runtime state tracking
    public boolean isColliding = false;

    public ColliderComponent(ColliderType type) {
        this.type = type;
    }

    public ColliderComponent(ColliderType type, float sx, float sy, float sz) {
        this.type = type;
        this.size.set(sx, sy, sz);
    }
    
    public float getRadius() {
        return size.x; // Convenience for Bounding Spheres
    }
}
