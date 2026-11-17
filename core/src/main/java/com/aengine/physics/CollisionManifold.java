package com.aengine.physics;

import org.joml.Vector3f;

/**
 * Contact Manifold — output record produced by {@link NarrowPhase}.
 *
 * <p>Encodes all data required by the Impulse Resolution solver:</p>
 * <ul>
 *   <li>Which two entities are touching</li>
 *   <li>Collision normal (points FROM entity A TOWARD entity B)</li>
 *   <li>Penetration depth</li>
 *   <li>Whether either collider is a trigger (detect-only, no physical response)</li>
 * </ul>
 *
 * <p>Pre-allocated frame pool: call {@link #lease()} to obtain a manifold from the
 * internal pool, and {@link #releaseFrame()} to return all manifolds at once — zero
 * individual free/alloc overhead. A released manifold is handed out again by the next
 * {@code lease()}, so never keep one after releasing.</p>
 *
 * <p>{@link com.aengine.ecs.systems.PhysicsSystem} releases at the top of every pair test
 * rather than once per step, so it only ever uses the first pooled manifold.</p>
 *
 * <p>The pool is static: it is shared by every physics system in the process and is not
 * thread-safe (see {@link #lease()}).</p>
 */
public final class CollisionManifold {

    // -------------------------------------------------------------------------
    // Contact data
    // -------------------------------------------------------------------------

    /** ECS entity ID of the first body in the contact; {@code -1} on a fresh lease. */
    public int entityA;
    /** ECS entity ID of the second body in the contact; {@code -1} on a fresh lease. */
    public int entityB;

    /**
     * Collision normal in world space, of unit length on a hit.
     * Convention: points FROM the centre of A TOWARD the centre of B.
     * <ul>
     *   <li>Push A in the -normal direction to separate.</li>
     *   <li>Push B in the +normal direction to separate.</li>
     * </ul>
     */
    public final Vector3f normal = new Vector3f();

    /** Penetration depth along the normal axis, in world units (always greater than 0 on a valid hit). */
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
     * <p>Thread safety: the pool cursor is NOT synchronized. This class assumes all calls
     * to {@code lease()} and {@code releaseFrame()} originate from the same thread
     * (the physics thread). If multiple threads call physics methods simultaneously,
     * external locking (e.g. via {@link com.aengine.physics.PhysicsThread#getSyncLock()})
     * must already be held.</p>
     *
     * <p>If the pool is exhausted (more than {@value MAX_PER_FRAME} leases without a
     * release), a temporary heap object is returned as a graceful fallback — this will cause
     * a single GC allocation and should be treated as a diagnostic signal to increase
     * MAX_PER_FRAME.</p>
     *
     * @return a manifold with both entities set to {@code -1}, a zero normal, zero depth and
     *         {@code isTrigger} false
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
     * Every manifold leased so far becomes invalid.
     *
     * <p>Calling this at the <em>top</em> of a per-pair loop body (rather than once before
     * the loop) lets a single manifold object be reused on every iteration — pool usage
     * stays at exactly 1 instead of growing with the number of pairs tested.</p>
     */
    public static void releaseFrame() {
        poolCursor = 0;
    }
}
