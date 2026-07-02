package com.aengine.ecs.components;

import org.joml.Vector3f;

/**
 * HARDWARE CONTEXT: ECS RIGIDBODY MEMORY BLOCK
 * Governs the kinetic and dynamic state of an entity.
 * Decoupled from the Collider to allow weightless triggers or mass-driven physics objects.
 */
public class RigidbodyComponent {
    
    public final Vector3f velocity = new Vector3f(0.0f, 0.0f, 0.0f);
    public final Vector3f netForce = new Vector3f(0.0f, 0.0f, 0.0f);
    
    public float mass = 1.0f;
    
    // Bounciness factor (0.0 = lead block, 1.0 = super bouncy rubber ball)
    public float restitution = 0.0f; 
    
    // Surface drag
    public float friction = 0.2f; 
    
    // If true, the object ignores gravity and forces (acts as an immovable wall/floor)
    public boolean isKinematic = false; 

    /**
     * Cache-friendly helper for physics formulas.
     * Kinematic objects are treated as having infinite mass (inverse mass = 0).
     */
    public float getInverseMass() {
        if (isKinematic || mass <= 0.0f) {
            return 0.0f;
        }
        return 1.0f / mass;
    }

    /**
     * Applies a continuous force vector to the entity.
     */
    public void applyForce(Vector3f force) {
        if (!isKinematic) {
            this.netForce.add(force);
        }
    }
}
