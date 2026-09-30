package com.aengine.ecs.components;

import org.joml.Vector3f;

/**
 * HARDWARE CONTEXT: ECS COLLIDER MEMORY BLOCK
 * Contiguous data structure tracking collision shape types, spatial offsets, and hit states.
 * Aligned with JOML vectors for cache-friendly transformation pipelines.
 */
public class ColliderComponent {

    /** The collision shape. */
    public ColliderType type;

    /**
     * Extents/Size: For AABB/OBB represents half the size (half-extents). For SPHERE, x is the radius.
     * The default of 0.5 on every axis is a 1x1x1 box. Not multiplied by the transform's scale.
     */
    public final Vector3f size = new Vector3f(0.5f, 0.5f, 0.5f);

    /** Local Offset: Allows shifting the hitbox relative to the entity's Transform center. */
    public final Vector3f offset = new Vector3f(0.0f, 0.0f, 0.0f);

    /** Trigger configuration (If true, detects intersection but does not apply physical response). */
    public boolean isTrigger = false;

    /**
     * Runtime state tracking: {@code true} if this collider touched another one during the
     * last physics step. Written by the physics system every step; pairs where neither
     * side can move are not tested, so a static collider touching only static colliders
     * stays {@code false}.
     */
    public boolean isColliding = false;

    /**
     * Creates a collider of the given shape with the default size (half-extents of 0.5).
     *
     * @param type the collision shape
     */
    public ColliderComponent(ColliderType type) {
        this.type = type;
    }

    /**
     * Creates a collider of the given shape and size.
     *
     * @param type the collision shape
     * @param sx   half-extent on X, or the radius for {@link ColliderType#SPHERE}
     * @param sy   half-extent on Y (ignored by spheres)
     * @param sz   half-extent on Z (ignored by spheres)
     */
    public ColliderComponent(ColliderType type, float sx, float sy, float sz) {
        this.type = type;
        this.size.set(sx, sy, sz);
    }

    /**
     * Returns the sphere radius, which is stored in {@code size.x}.
     *
     * @return {@code size.x}
     */
    public float getRadius() {
        return size.x; // Convenience for Bounding Spheres
    }
}
