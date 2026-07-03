package com.aengine.physics;

import com.aengine.ecs.Registry;
import com.aengine.ecs.systems.PhysicsSystem;
import com.aengine.utils.Logger;

import java.util.concurrent.locks.LockSupport;

/**
 * Dedicated Physics Thread
 *
 * Runs {@link PhysicsSystem#update} on a background thread at a fixed internal
 * timestep (default 120 Hz), fully decoupled from the render frame rate.
 *
 * Synchronization model
 * ─────────────────────
 * The ECS {@link Registry} is shared between this thread and the main render thread.
 * A coarse-grained {@code syncLock} object protects all Transform writes during
 * a physics step.  The render/main thread must acquire the same lock whenever it
 * needs a consistent view of Transform positions (e.g., for interpolated rendering).
 *
 * Example integration in the render loop:
 * <pre>{@code
 *   synchronized (physicsThread.getSyncLock()) {
 *       renderer.drawScene(registry);
 *   }
 * }</pre>
 *
 * Fixed-timestep accumulator and the spiral-of-death protection are handled
 * internally — the caller only needs to call {@link #startPhysics()} and
 * {@link #stopPhysics()}.
 */
public final class PhysicsThread extends Thread {

    // -------------------------------------------------------------------------
    // Configuration
    // -------------------------------------------------------------------------

    /** Physics simulation frequency in Hz. */
    public static final int   PHYSICS_HZ   = 120;
    public static final float TIME_STEP    = 1.0f / PHYSICS_HZ;
    private static final long STEP_NS      = (long)(TIME_STEP * 1_000_000_000L);

    /** Maximum simulated time per render frame — prevents the "spiral of death". */
    private static final float MAX_FRAME_TIME = 0.25f;

    // -------------------------------------------------------------------------
    // State
    // -------------------------------------------------------------------------

    private final Registry     registry;
    private final PhysicsSystem physics;
    private volatile boolean   running = false;

    /**
     * Shared mutex between this thread and any external reader (e.g. the render thread).
     * Physics writes are always enclosed in {@code synchronized (syncLock) { ... }}.
     */
    private final Object syncLock = new Object();

    // -------------------------------------------------------------------------
    // Construction
    // -------------------------------------------------------------------------

    /**
     * @param registry Shared ECS registry.
     * @param physics  Physics system instance to step (must NOT be run concurrently on the main thread).
     */
    public PhysicsThread(Registry registry, PhysicsSystem physics) {
        super("AEngine-Physics");
        setDaemon(true);                            // Die automatically when the JVM exits
        setPriority(Thread.NORM_PRIORITY + 1);      // Slightly above render to minimise input latency
        this.registry = registry;
        this.physics  = physics;
    }

    // -------------------------------------------------------------------------
    // Lifecycle
    // -------------------------------------------------------------------------

    /**
     * Start the physics simulation loop.
     * Safe to call only once — subsequent calls are ignored if already running.
     */
    public void init() {
        if (running) return;
        running = true;
        start();
        Logger.info(Logger.System.CORE,
            "PhysicsThread started. Stepping at %d Hz (%.4f s/step).", PHYSICS_HZ, TIME_STEP);
    }

    /**
     * Signal the physics loop to stop and block until the thread terminates.
     * Safe to call from any thread.
     */
    public void cleanup() {
        running = false;
        interrupt();
        boolean cleanStop = false;
        try {
            join(2000L); // Block up to 2 s for the physics loop to notice running=false
            cleanStop = !isAlive();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        if (cleanStop) {
            Logger.info(Logger.System.CORE, "PhysicsThread stopped cleanly.");
        } else {
            // Thread is still alive after the 2-second window — log a warning.
            // This can happen if PhysicsSystem.update() is stuck in a long step.
            Logger.warn(Logger.System.CORE,
                "PhysicsThread did not stop within 2 s — it may still be running.");
        }
    }

    /**
     * Returns the synchronisation lock shared with external readers.
     * The render thread should acquire this lock via {@code synchronized (getSyncLock())}
     * before reading Transform positions to guarantee a consistent snapshot.
     */
    public Object getSyncLock() {
        return syncLock;
    }

    // -------------------------------------------------------------------------
    // Thread body
    // -------------------------------------------------------------------------

    @Override
    public void run() {
        Logger.info(Logger.System.CORE, "Physics thread online.");

        long lastTime   = System.nanoTime();
        float accumulator = 0.0f;

        while (running && !isInterrupted()) {
            long now = System.nanoTime();
            float dt = (now - lastTime) / 1_000_000_000.0f;
            lastTime = now;

            // Spiral-of-death protection: cap the simulated frame time
            if (dt > MAX_FRAME_TIME) dt = MAX_FRAME_TIME;
            accumulator += dt;

            // Consume accumulated time in fixed-size slices
            while (accumulator >= TIME_STEP) {
                synchronized (syncLock) {
                    physics.update(registry, TIME_STEP);
                }
                accumulator -= TIME_STEP;
            }

            // Park the thread for the remainder of the step budget
            long elapsed  = System.nanoTime() - now;
            long sleepNs  = STEP_NS - elapsed;
            if (sleepNs > 0L) {
                LockSupport.parkNanos(sleepNs);
            }
        }

        Logger.info(Logger.System.CORE, "Physics thread shutdown complete.");
    }
}
