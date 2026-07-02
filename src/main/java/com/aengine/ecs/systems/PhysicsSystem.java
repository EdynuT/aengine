package com.aengine.ecs.systems;

import com.aengine.Main;
import com.aengine.ecs.Registry;
import com.aengine.ecs.components.ColliderComponent;
import com.aengine.ecs.components.RigidbodyComponent;
import com.aengine.ecs.components.TransformComponent;
import org.joml.Vector3f;

import java.util.List;

public class PhysicsSystem {

    // Alocações estáticas para evitar spikes no Garbage Collector (O(1) Memory)
    private final Vector3f globalGravity = new Vector3f(0.0f, -9.81f, 0.0f);
    private final Vector3f tempAcc = new Vector3f();
    private final Vector3f tempMove = new Vector3f();
    private final Vector3f normal = new Vector3f();

    public void update(Registry registry, float fixedDeltaTime) {
        
        // 1. Euler integration (Movement and Gravity)
        var rigidbodies = registry.getEntitiesWith(TransformComponent.class, RigidbodyComponent.class);
        
        for (int i = 0; i < rigidbodies.size(); i++) {
            int entity = rigidbodies.get(i);
            TransformComponent t = registry.getComponent(entity, TransformComponent.class);
            RigidbodyComponent rb = registry.getComponent(entity, RigidbodyComponent.class);

            if (rb.isKinematic) continue;

            // Apply gravity as a continuous force
            rb.applyForce(globalGravity);

            // Acceleration = Force / Mass (Force * Inverse Mass)
            tempAcc.set(rb.netForce).mul(rb.getInverseMass());

            // Velocity += Acceleration * dt
            tempAcc.mul(fixedDeltaTime);
            rb.velocity.add(tempAcc);

            // Apply air drag/friction (Damping)
            rb.velocity.mul(1.0f - (rb.friction * fixedDeltaTime));

            // Position += Velocity * dt
            tempMove.set(rb.velocity).mul(fixedDeltaTime);
            t.position.add(tempMove);

            // Reset forces for the next frame
            rb.netForce.set(0, 0, 0);
        }

        // 2. Narrow Phase Detection & Penetration Resolution (AABB 2D)
        var colliders = registry.getEntitiesWith(TransformComponent.class, ColliderComponent.class);
        int size = colliders.size();

        for (int i = 0; i < size; i++) {
            registry.getComponent(colliders.get(i), ColliderComponent.class).isColliding = false;
        }

        for (int i = 0; i < size; i++) {
            int entA = colliders.get(i);
            TransformComponent tA = registry.getComponent(entA, TransformComponent.class);
            ColliderComponent cA = registry.getComponent(entA, ColliderComponent.class);
            RigidbodyComponent rbA = registry.getComponent(entA, RigidbodyComponent.class);

            float invMassA = (rbA != null) ? rbA.getInverseMass() : 0.0f;

            for (int j = i + 1; j < size; j++) {
                int entB = colliders.get(j);
                TransformComponent tB = registry.getComponent(entB, TransformComponent.class);
                ColliderComponent cB = registry.getComponent(entB, ColliderComponent.class);
                RigidbodyComponent rbB = registry.getComponent(entB, RigidbodyComponent.class);

                float invMassB = (rbB != null) ? rbB.getInverseMass() : 0.0f;

                // Early Exit: If both objects are kinematic (infinite mass), skip collision resolution
                if (invMassA == 0.0f && invMassB == 0.0f) continue;

                // Calculate real centers (Transform + Collider Offset)
                float cAx = tA.position.x + cA.offset.x;
                float cAy = tA.position.y + cA.offset.y;
                float cBx = tB.position.x + cB.offset.x;
                float cBy = tB.position.y + cB.offset.y;

                float dx = cBx - cAx;
                float dy = cBy - cAy;

                // Calculate overlap in X and Y (Sum of half sizes - absolute distance)
                float penX = (cA.size.x + cB.size.x) - Math.abs(dx);
                float penY = (cA.size.y + cB.size.y) - Math.abs(dy);

                // If there is penetration in BOTH axes, we have a real collision.
                if (penX > 0 && penY > 0) {
                    cA.isColliding = true;
                    cB.isColliding = true;

                    // The axis of least penetration dictates the face on which the collision occurred.
                    float depth;
                    if (penX < penY) {
                        normal.set(Math.signum(dx), 0.0f, 0.0f);
                        depth = penX;
                    } else {
                        normal.set(0.0f, Math.signum(dy), 0.0f);
                        depth = penY;
                    }

                    // Positional Correction: Push objects apart to resolve clipping (Linear Projection)
                    float massSum = invMassA + invMassB;
                    float correctionMagnitude = depth / massSum;

                    // Move Object A (Against the Normal)
                    if (invMassA > 0.0f) {
                        tA.position.x -= normal.x * correctionMagnitude * invMassA;
                        tA.position.y -= normal.y * correctionMagnitude * invMassA;
                    }

                    // Move Object B (Along the Normal)
                    if (invMassB > 0.0f) {
                        tB.position.x += normal.x * correctionMagnitude * invMassB;
                        tB.position.y += normal.y * correctionMagnitude * invMassB;
                    }

                    // Zero local velocity against the collision normal to prevent infinite sliding
                    // This cuts the falling movement when the box touches the ground.
                    if (rbA != null && !rbA.isKinematic) {
                        if (normal.x != 0) rbA.velocity.x = 0;
                        if (normal.y != 0) rbA.velocity.y = 0;
                    }
                    if (rbB != null && !rbB.isKinematic) {
                        if (normal.x != 0) rbB.velocity.x = 0;
                        if (normal.y != 0) rbB.velocity.y = 0;
                    }
                }
            }
        }
    }
}
