package com.aengine.ecs.components;

import org.joml.Matrix4f;
import org.joml.Vector3f;

/*
 * MEMORY ADDRESSING NOTE:
 * In C/C++, this struct would exist perfectly inline within a contiguous byte buffer.
 * In Java, objects are accessed via references. To simulate DOD and avoid Heap fragmentation:
 * 1. We declare fields as `final` primitives/objects, allocated exactly once upon creation.
 * 2. We NEVER use 'new' inside system update loops (e.g., movement or matrix scaling). 
 * We mutate the internal states of these instances directly.
 * 3. 'transformMatrix' acts as a pre-allocated memory chunk to stream float matrix calculations 
 * straight to the GPU, preventing the Garbage Collector from triggering during rendering.
 */
public final class TransformComponent {

    // Raw, primitive aligned vector structures for direct CPU L1 cache streaming
    public final Vector3f position = new Vector3f(0.0f, 0.0f, 0.0f);
    public final Vector3f rotation = new Vector3f(0.0f, 0.0f, 0.0f);
    public final Vector3f scale    = new Vector3f(1.0f, 1.0f, 1.0f);
    
    // Cached transformation matrix to avoid allocating new instances during runtime loops
    public final Matrix4f transformMatrix = new Matrix4f();

    public TransformComponent() {}

    public TransformComponent(Vector3f position) {
        this.position.set(position);
    }

    public TransformComponent(Vector3f position, Vector3f rotation, Vector3f scale) {
        this.position.set(position);
        this.rotation.set(rotation);
        this.scale.set(scale);
    }
}
