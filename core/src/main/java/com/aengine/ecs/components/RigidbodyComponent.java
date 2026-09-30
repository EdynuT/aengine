package com.aengine.ecs.components;

import org.joml.Vector3f;

/**
 * HARDWARE CONTEXT: ECS RIGIDBODY MEMORY BLOCK
 * Governs the kinetic and dynamic state of an entity.
 * Decoupled from the Collider to allow weightless triggers or mass-driven physics objects.
 */
public class RigidbodyComponent {

    /** Current velocity in world units per second. The physics system moves the transform by it every step. */
    public final Vector3f velocity = new Vector3f(0.0f, 0.0f, 0.0f);
    /**
     * Forces accumulated since the last physics step, gravity included. Add to it through
     * {@link #applyForce(Vector3f)}; the physics system turns it into acceleration and then
     * clears it.
     */
    public final Vector3f netForce = new Vector3f(0.0f, 0.0f, 0.0f);

    /** Mass; a value of zero or below behaves like an immovable body (see {@link #getInverseMass()}). */
    public float mass = 1.0f;

    /** Bounciness factor (0.0 = lead block, 1.0 = super bouncy rubber ball). */
    public float restitution = 0.0f;

    /**
     * Surface drag. Applied as linear damping: each step the velocity is multiplied by
     * {@code 1 - friction * dt}, whether or not the body is touching anything.
     */
    public float friction = 0.2f;

    /** If true, the object ignores gravity and forces (acts as an immovable wall/floor). */
    public boolean isKinematic = false;

    /** Creates a dynamic body at rest with a mass of 1 and default drag. */
    public RigidbodyComponent() {}

    /**
     * Cache-friendly helper for physics formulas.
     * Kinematic objects are treated as having infinite mass (inverse mass = 0).
     *
     * @return {@code 1 / mass}, or {@code 0} if the body is kinematic or its mass is not positive
     */
    public float getInverseMass() {
        if (isKinematic || mass <= 0.0f) {
            return 0.0f;
        }
        return 1.0f / mass;
    }

    /**
     * Applies a continuous force vector to the entity.
     * The force lasts for one physics step only; call again every step to keep pushing.
     * Ignored on kinematic bodies.
     *
     * @param force the force to add; the vector is read, not kept
     */
    public void applyForce(Vector3f force) {
        if (!isKinematic) {
            this.netForce.add(force);
        }
    }
}
