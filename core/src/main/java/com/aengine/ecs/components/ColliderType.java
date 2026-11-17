package com.aengine.ecs.components;

/** The collision shapes a {@link ColliderComponent} can have. */
public enum ColliderType {
    /** Axis-aligned box: always lined up with the world axes, ignoring the transform's rotation. */
    AABB,
    /** Oriented box: a box that turns with the transform's rotation. */
    OBB,
    /** Sphere whose radius is {@code size.x}. */
    SPHERE
}
