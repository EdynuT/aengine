package com.aengine.ecs.systems;

import com.aengine.ecs.Registry;
import com.aengine.ecs.components.ColliderComponent;
import com.aengine.ecs.components.RigidbodyComponent;
import com.aengine.ecs.components.TransformComponent;
import com.aengine.physics.CollisionManifold;
import com.aengine.physics.NarrowPhase;
import com.aengine.physics.SpatialHashGrid;
import com.aengine.utils.Logger;
import org.joml.Vector3f;

/**
 * Physics System — fixed-timestep simulation pipeline.
 *
 * Execution order per step:
 *   1. Force Integration  — Semi-implicit Euler: v += a·dt, x += v·dt, with gravity + damping
 *   2. Broad Phase        — SpatialHashGrid insertion; O(1) per entity → O(K) candidate pairs
 *   3. Narrow Phase       — Precise AABB/Sphere SAT tests on broad-phase candidates only
 *   4. Positional Correction — Baumgarte stabilisation bleeds out penetration depth
 *   5. Impulse Resolution — Velocity projection using restitution coefficient e:
 *                           j = -(1+e)·vRel / (1/mA + 1/mB)
 *
 * Manifold pool usage:
 *   {@link CollisionManifold#releaseFrame()} is called at the TOP of each narrow-phase
 *   loop iteration, not once before the loop. This reuses POOL[0] on every iteration so
 *   pool consumption stays at exactly 1 regardless of pair count. A manifold is only
 *   written into when NarrowPhase confirms a hit — no wasted slots from broadphase misses.
 *
 * Thread safety:
 *   This class is NOT thread-safe on its own. When used with
 *   {@link com.aengine.physics.PhysicsThread}, all calls to {@code update()} are wrapped
 *   in a {@code synchronized(syncLock)} block, serialising access to the ECS registry.
 */
public class PhysicsSystem {

    // -------------------------------------------------------------------------
    // Simulation constants
    // -------------------------------------------------------------------------

    /** World gravity vector (m/s²). */
    private static final Vector3f GRAVITY = new Vector3f(0.0f, -9.81f, 0.0f);

    /**
     * Baumgarte penetration slop — minimum overlap that triggers positional correction.
     * A small tolerance prevents jitter from micro-penetrations on resting contact.
     */
    private static final float PENETRATION_SLOP       = 0.01f;

    /**
     * Baumgarte correction percentage — fraction of penetration resolved per step.
     * Values in [0.2, 0.8] are stable; 1.0 causes overshooting/jitter.
     */
    private static final float BAUMGARTE_CORRECTION   = 0.6f;

    // -------------------------------------------------------------------------
    // Pre-allocated temporaries (zero GC in the hot path)
    // -------------------------------------------------------------------------

    /**
     * Reused across every integration step to avoid new Vector3f allocations.
     * These are ONLY valid within a single update() call — never cache references to them.
     */
    private final Vector3f tempAcc  = new Vector3f();
    private final Vector3f tempMove = new Vector3f();

    // -------------------------------------------------------------------------
    // Broad Phase
    // -------------------------------------------------------------------------

    /**
     * Cell size = 2.0 world units covers entities whose half-extents are ≤ 1.0 in a single cell.
     * Entities larger than the cell size span multiple cells and are inserted into each —
     * still O(cells_spanned), not O(N). Increase cellSize if most colliders are larger.
     */
    private final SpatialHashGrid grid = new SpatialHashGrid(2.0f);

    // -------------------------------------------------------------------------
    // Diagnostics
    // -------------------------------------------------------------------------

    /**
     * Monotonically increasing counter of physics steps executed.
     * Used to throttle periodic TRACE-level diagnostics so they don't spam logs at 120 Hz.
     */
    private long stepCount = 0;

    /**
     * Emit TRACE diagnostics every N steps. At 120 Hz this is approximately every 2.5 seconds.
     * TRACE is filtered by the default Logger level (DEBUG), so there is zero cost in production.
     */
    private static final int DIAGNOSTIC_INTERVAL = 300;

    // -------------------------------------------------------------------------
    // Update
    // -------------------------------------------------------------------------

    public void update(Registry registry, float fixedDeltaTime) {

        // =======================================================================
        // PHASE 1 — Force Integration (Semi-implicit Euler)
        // =======================================================================
        var dynamics = registry.getEntitiesWith(TransformComponent.class, RigidbodyComponent.class);

        for (int i = 0; i < dynamics.size(); i++) {
            int entity = dynamics.get(i);
            TransformComponent t  = registry.getComponent(entity, TransformComponent.class);
            RigidbodyComponent rb = registry.getComponent(entity, RigidbodyComponent.class);

            if (rb.isKinematic) continue;

            // Gravity applied as a continuous force each step
            rb.applyForce(GRAVITY);

            // a = F * (1/m)
            tempAcc.set(rb.netForce).mul(rb.getInverseMass());

            // v += a * dt
            rb.velocity.add(tempAcc.mul(fixedDeltaTime));

            // Linear damping (models air resistance / surface drag)
            rb.velocity.mul(1.0f - rb.friction * fixedDeltaTime);

            // x += v * dt
            t.position.add(tempMove.set(rb.velocity).mul(fixedDeltaTime));

            // Clear accumulated forces for next step
            rb.netForce.zero();
        }

        // =======================================================================
        // PHASE 2 — Broad Phase: Spatial Hash Grid
        //
        // Each collidable entity is inserted into all cells its AABB overlaps.
        // buildPairs() then returns only entity pairs that share at least one cell,
        // replacing the previous O(N²) exhaustive double loop.
        // =======================================================================
        var collidables = registry.getEntitiesWith(TransformComponent.class, ColliderComponent.class);
        int N = collidables.size();

        // ---- Merged loop: reset collision flags + populate spatial hash grid ----
        // Combining these two operations into a single pass halves the number of
        // getComponent() calls for ColliderComponent from 2N to N.
        grid.clear();

        for (int i = 0; i < N; i++) {
            int entity = collidables.get(i);
            TransformComponent t = registry.getComponent(entity, TransformComponent.class);
            ColliderComponent  c = registry.getComponent(entity, ColliderComponent.class);

            // Clear last frame's contact flag
            c.isColliding = false;

            // World-space AABB centre = transform position + collider local offset
            float cx = t.position.x + c.offset.x;
            float cy = t.position.y + c.offset.y;
            float cz = t.position.z + c.offset.z;

            // Insert the full AABB (centre ± half-extents) into the hash grid.
            // If scale affects collider size, multiply c.size by t.scale here.
            grid.insert(entity,
                cx - c.size.x, cy - c.size.y, cz - c.size.z,
                cx + c.size.x, cy + c.size.y, cz + c.size.z);
        }

        int[]  pairs      = grid.buildPairs();
        int    pairCount  = grid.getPairCount();

        // =======================================================================
        // PHASE 3 — Narrow Phase + Impulse Resolution
        //
        // For each candidate pair supplied by the broad phase:
        //   a) Lease a manifold (reusing POOL[0] every iteration via releaseFrame() at top)
        //   b) Run the precise shape test (AABB/Sphere SAT via NarrowPhase)
        //   c) Apply Baumgarte positional correction to bleed out penetration
        //   d) Apply impulse-based velocity projection using the restitution coefficient
        //
        // Manifold reuse strategy:
        //   releaseFrame() is called at the START of each iteration, not once before
        //   the loop. This keeps pool cursor pinned at 0 — a single manifold object is
        //   reused on every iteration, so pool exhaustion is impossible regardless of
        //   how many broadphase candidates there are.
        // =======================================================================

        for (int p = 0; p < pairCount; p++) {
            // Reset pool cursor to reuse POOL[0] this iteration.
            // The manifold is fully consumed before the next iteration begins.
            CollisionManifold.releaseFrame();
            int entA = pairs[p * 2];
            int entB = pairs[p * 2 + 1];

            TransformComponent  tA  = registry.getComponent(entA, TransformComponent.class);
            ColliderComponent   cA  = registry.getComponent(entA, ColliderComponent.class);
            RigidbodyComponent  rbA = registry.getComponent(entA, RigidbodyComponent.class);

            TransformComponent  tB  = registry.getComponent(entB, TransformComponent.class);
            ColliderComponent   cB  = registry.getComponent(entB, ColliderComponent.class);
            RigidbodyComponent  rbB = registry.getComponent(entB, RigidbodyComponent.class);

            float invMassA = (rbA != null) ? rbA.getInverseMass() : 0.0f;
            float invMassB = (rbB != null) ? rbB.getInverseMass() : 0.0f;

            // Both kinematic (invMass = 0) → immovable walls/floors on both sides → skip entirely
            if (invMassA == 0.0f && invMassB == 0.0f) continue;

            // --- Narrow Phase: precise AABB / Sphere shape test ---
            // lease() always returns POOL[0] because releaseFrame() was called above.
            CollisionManifold m = CollisionManifold.lease();
            if (!NarrowPhase.test(entA, tA, cA, entB, tB, cB, m)) continue;

            cA.isColliding = true;
            cB.isColliding = true;

            // Triggers: register contact but apply no physical response
            if (m.isTrigger) continue;

            float totalInvMass = invMassA + invMassB;

            // ---------------------------------------------------------------
            // Baumgarte Positional Correction
            // Gradually bleeds out penetration depth without introducing energy.
            // Only applied when penetration exceeds the slop threshold.
            // ---------------------------------------------------------------
            float correction = Math.max(m.depth - PENETRATION_SLOP, 0.0f)
                               / totalInvMass
                               * BAUMGARTE_CORRECTION;

            if (invMassA > 0.0f) {
                // Push A in the -normal direction (away from B)
                tA.position.x -= m.normal.x * correction * invMassA;
                tA.position.y -= m.normal.y * correction * invMassA;
                tA.position.z -= m.normal.z * correction * invMassA;
            }
            if (invMassB > 0.0f) {
                // Push B in the +normal direction (away from A)
                tB.position.x += m.normal.x * correction * invMassB;
                tB.position.y += m.normal.y * correction * invMassB;
                tB.position.z += m.normal.z * correction * invMassB;
            }

            // ---------------------------------------------------------------
            // Impulse-Based Velocity Projection
            //
            // Relative velocity of B w.r.t. A projected onto the contact normal.
            // Convention: normal points FROM A TOWARD B.
            //   vRel = dot(vB - vA, normal)
            //   vRel < 0 → objects approaching → apply impulse
            //   vRel > 0 → already separating → skip
            //
            // Impulse magnitude:
            //   j = -(1 + e) * vRel / (1/mA + 1/mB)
            // where e = min(restitution_A, restitution_B)
            // ---------------------------------------------------------------
            float vBx = (rbB != null) ? rbB.velocity.x : 0.0f;
            float vBy = (rbB != null) ? rbB.velocity.y : 0.0f;
            float vBz = (rbB != null) ? rbB.velocity.z : 0.0f;

            float vAx = (rbA != null) ? rbA.velocity.x : 0.0f;
            float vAy = (rbA != null) ? rbA.velocity.y : 0.0f;
            float vAz = (rbA != null) ? rbA.velocity.z : 0.0f;

            float vRelN = (vBx - vAx) * m.normal.x
                        + (vBy - vAy) * m.normal.y
                        + (vBz - vAz) * m.normal.z;

            // Already separating — no impulse needed
            if (vRelN > 0.0f) continue;

            // Coefficient of restitution — use the lesser of the two materials
            float e = 0.0f;
            if (rbA != null && rbB != null) {
                e = Math.min(rbA.restitution, rbB.restitution);
            } else if (rbA != null) {
                e = rbA.restitution;
            } else if (rbB != null) {
                e = rbB.restitution;
            }

            float j = -(1.0f + e) * vRelN / totalInvMass;

            float jx = m.normal.x * j;
            float jy = m.normal.y * j;
            float jz = m.normal.z * j;

            if (rbA != null && !rbA.isKinematic) {
                rbA.velocity.x -= jx * invMassA;
                rbA.velocity.y -= jy * invMassA;
                rbA.velocity.z -= jz * invMassA;
            }
            if (rbB != null && !rbB.isKinematic) {
                rbB.velocity.x += jx * invMassB;
                rbB.velocity.y += jy * invMassB;
                rbB.velocity.z += jz * invMassB;
            }
        }

        // =======================================================================
        // PHASE 4 — Periodic Diagnostics (TRACE level — filtered in production)
        //
        // Emits one log line every DIAGNOSTIC_INTERVAL steps showing entity count,
        // candidate pair count, and grid load factors. Helps catch hash table saturation
        // before it silently degrades collision quality.
        // Stack-trace capture inside Logger is triggered only at TRACE priority;
        // at the default DEBUG level this block is a single integer comparison.
        // =======================================================================
        if (++stepCount % DIAGNOSTIC_INTERVAL == 0) {
            Logger.trace(Logger.System.CORE,
                "[Physics] step=%d collidables=%d broadPairs=%d cellLoad=%.2f pairLoad=%.2f",
                stepCount, N, pairCount,
                grid.cellTableLoad(), grid.pairSetLoad());
        }
    }
}
