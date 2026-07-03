package com.aengine.physics;

import org.joml.Vector3f;

/**
 * Contact Manifold — output record produced by {@link NarrowPhase}.
 *
 * Encodes all data required by the Impulse Resolution solver:
 *   - Which two entities are touching
 *   - Collision normal (points FROM entity A TOWARD entity B)
 *   - Penetration depth
 *   - Whether either collider is a trigger (detect-only, no physical response)
 *
 * Pre-allocated frame pool:
 *   Call {@link #lease()} to obtain a manifold from the internal pool.
 *   Call {@link #releaseFrame()} at the end of each physics step to return all
 *   manifolds at once — zero individual free/alloc overhead.
 */
public final class CollisionManifold {

    // -------------------------------------------------------------------------
    // Contact data
    // -------------------------------------------------------------------------

    /** ECS entity IDs involved in the contact. */
    public int entityA;
    public int entityB;

    /**
     * Collision normal in world space.
     * Convention: points FROM the centre of A TOWARD the centre of B.
     * - Push A in the  -normal direction to separate.
     * - Push B in the  +normal direction to separate.
     */
    public final Vector3f normal = new Vector3f();

    /** Signed penetration depth along the normal axis (always ≥ 0 on a valid hit). */
    public float depth;

    /**
     * True when either collider is a trigger.
     * The solver flags {@code isColliding} but skips impulse and positional correction.
     */
    public boolean isTrigger;

    // -------------------------------------------------------------------------
    // Frame pool
    // -------------------------------------------------------------------------

    private static final int MAX_PER_FRAME = 4096;
    private static final CollisionManifold[] POOL = new CollisionManifold[MAX_PER_FRAME];
    private static int poolCursor = 0;

    static {
        for (int i = 0; i < MAX_PER_FRAME; i++) {
            POOL[i] = new CollisionManifold();
        }
    }

    private CollisionManifold() {}

    /**
     * Lease a zeroed manifold from the pre-allocated pool.
     *
     * Thread safety: the pool cursor is NOT synchronized. This class assumes all calls
     * to {@code lease()} and {@code releaseFrame()} originate from the same thread
     * (the physics thread). If multiple threads call physics methods simultaneously,
     * external locking (e.g. via {@link com.aengine.physics.PhysicsThread#getSyncLock()})
     * must already be held.
     *
     * If the pool is exhausted (> {@value MAX_PER_FRAME} contacts per step), a temporary
     * heap object is returned as a graceful fallback — this will cause a single GC allocation
     * and should be treated as a diagnostic signal to increase MAX_PER_FRAME.
     */
    public static CollisionManifold lease() {
        if (poolCursor >= MAX_PER_FRAME) {
            // Pool exhausted — this should never happen in normal operation.
            // The caller (PhysicsSystem) calls releaseFrame() at the top of each
            // iteration and only leases one manifold per iteration, so pool usage
            // is always exactly 1. This fallback guards against future misuse.
            return new CollisionManifold();
        }
        CollisionManifold m = POOL[poolCursor++];
        m.entityA   = -1;
        m.entityB   = -1;
        m.normal.zero();
        m.depth     = 0.0f;
        m.isTrigger = false;
        return m;
    }

    /**
     * Reset the pool cursor to 0, making all slots available for re-lease.
     *
     * Calling this at the <em>top</em> of a per-pair loop body (rather than once before
     * the loop) lets a single manifold object be reused on every iteration — pool usage
     * stays at exactly 1 instead of growing with the number of pairs tested.
     */
    public static void releaseFrame() {
        poolCursor = 0;
    }
}
