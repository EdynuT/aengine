package com.aengine.ecs.components;

import org.joml.Matrix4f;
import org.joml.Vector3f;

/**
 * Position, rotation and scale of an entity in the world.
 *
 * <p>MEMORY ADDRESSING NOTE:
 * In C/C++, this struct would exist perfectly inline within a contiguous byte buffer.
 * In Java, objects are accessed via references. To simulate DOD and avoid Heap fragmentation:</p>
 * <ol>
 *   <li>We declare fields as {@code final} primitives/objects, allocated exactly once upon creation.</li>
 *   <li>We NEVER use {@code new} inside system update loops (e.g., movement or matrix scaling).
 *       We mutate the internal states of these instances directly.</li>
 *   <li>{@code transformMatrix} acts as a pre-allocated memory chunk to stream float matrix calculations
 *       straight to the GPU, preventing the Garbage Collector from triggering during rendering.</li>
 * </ol>
 */
public final class TransformComponent {

    // Raw, primitive aligned vector structures for direct CPU L1 cache streaming

    /** Position of the entity's centre in world units. */
    public final Vector3f position = new Vector3f(0.0f, 0.0f, 0.0f);
    /** Rotation around the X, Y and Z axes, in degrees (the renderers convert to radians). */
    public final Vector3f rotation = new Vector3f(0.0f, 0.0f, 0.0f);
    /** Size multiplier on each axis; {@code 1} is unscaled. Does not scale colliders. */
    public final Vector3f scale    = new Vector3f(1.0f, 1.0f, 1.0f);

    /** Cached transformation matrix to avoid allocating new instances during runtime loops. */
    public final Matrix4f transformMatrix = new Matrix4f();

    /** Creates a transform at the origin with no rotation and a scale of 1. */
    public TransformComponent() {}

    /**
     * Creates a transform at a position with no rotation and a scale of 1.
     *
     * @param position world position; copied, not kept
     */
    public TransformComponent(Vector3f position) {
        this.position.set(position);
    }

    /**
     * Creates a transform with every part given.
     *
     * @param position world position; copied, not kept
     * @param rotation rotation in degrees around X, Y and Z; copied, not kept
     * @param scale    scale on each axis; copied, not kept
     */
    public TransformComponent(Vector3f position, Vector3f rotation, Vector3f scale) {
        this.position.set(position);
        this.rotation.set(rotation);
        this.scale.set(scale);
    }
}
