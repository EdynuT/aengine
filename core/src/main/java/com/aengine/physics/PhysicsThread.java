package com.aengine.physics;

import com.aengine.ecs.Registry;
import com.aengine.ecs.systems.PhysicsSystem;
import com.aengine.utils.Logger;

import java.util.concurrent.locks.LockSupport;

/**
 * Dedicated Physics Thread
 *
 * <p>Runs {@link PhysicsSystem#update} on a background thread at a fixed internal
 * timestep (120 Hz), fully decoupled from the render frame rate.</p>
 *
 * <h2>Synchronization model</h2>
 * <p>The ECS {@link Registry} is shared between this thread and the main render thread.
 * A coarse-grained {@code syncLock} object protects all Transform writes during
 * a physics step.  The render/main thread must acquire the same lock whenever it
 * needs a consistent view of Transform positions (e.g., for interpolated rendering),
 * and whenever it adds or removes entities or components.</p>
 *
 * <p>Example integration in the render loop:</p>
 * <pre>{@code
 * synchronized (physicsThread.getSyncLock()) {
 * renderer.drawScene(registry);
 * }
 * }</pre>
 *
 * <p>Fixed-timestep accumulator and the spiral-of-death protection are handled
 * internally — the caller only needs to call {@link #init()} and
 * {@link #cleanup()}.</p>
 *
 * <p>The thread starts <b>paused</b>, matching the editor, where the scene sits still
 * until Play. Call {@link #setPaused(boolean) setPaused(false)} to let time run.</p>
 *
 * <p>The object owns its Java thread rather than being one, so it can be stopped with
 * {@link #cleanup()} and started again with {@link #init()}: each start runs a new thread.</p>
 */
public final class PhysicsThread {

    // -------------------------------------------------------------------------
    // Configuration
    // -------------------------------------------------------------------------

    /** Physics simulation frequency in Hz. */
    public static final int   PHYSICS_HZ   = 120;
    /** Length of one physics step in seconds ({@code 1 / PHYSICS_HZ}); every step uses exactly this delta. */
    public static final float TIME_STEP    = 1.0f / PHYSICS_HZ;
    private static final long STEP_NS      = (long)(TIME_STEP * 1_000_000_000L);

    /**
     * Maximum simulated time per loop iteration — prevents the "spiral of death".
     * After a stall longer than this, the simulation skips ahead instead of catching up.
     */
    private static final float MAX_FRAME_TIME = 0.25f;

    // -------------------------------------------------------------------------
    // State
    // -------------------------------------------------------------------------

    private final Registry     registry;
    private final PhysicsSystem physics;
    /**
     * The thread running the loop, or {@code null} when stopped. A loop keeps going only
     * while it is still the current worker, so a thread that outlives a timed-out
     * {@link #cleanup()} stops at its next check even after a new {@link #init()}.
     */
    private volatile Thread    worker   = null;
    private volatile boolean   isPaused = true; // Starts paused in Editor Mode

    /**
     * Shared mutex between this thread and any external reader (e.g. the render thread).
     * Physics writes are always enclosed in {@code synchronized (syncLock) { ... }}.
     */
    private final Object syncLock = new Object();

    // -------------------------------------------------------------------------
    // Construction
    // -------------------------------------------------------------------------

    /**
     * Creates the physics loop without starting it. Its threads are daemon threads, so they
     * never keep the JVM alive, and run at slightly above normal priority.
     *
     * @param registry Shared ECS registry.
     * @param physics  Physics system instance to step (must NOT be run concurrently on the main thread).
     */
    public PhysicsThread(Registry registry, PhysicsSystem physics) {
        this.registry = registry;
        this.physics  = physics;
    }

    // -------------------------------------------------------------------------
    // Lifecycle
    // -------------------------------------------------------------------------

    /**
     * Start the physics simulation loop. The simulation stays paused until
     * {@link #setPaused(boolean) setPaused(false)}.
     *
     * <p>Further calls are ignored while the loop is running. After {@link #cleanup()} it
     * starts a new thread, with the pause state the loop had.</p>
     */
    public void init() {
        if (worker != null) return;
        Thread thread = new Thread(this::run, "AEngine-Physics");
        thread.setDaemon(true);                        // Die automatically when the JVM exits
        thread.setPriority(Thread.NORM_PRIORITY + 1);  // Slightly above render to minimise input latency
        worker = thread;
        thread.start();
        Logger.info(Logger.System.CORE,
            "PhysicsThread started. Stepping at %d Hz (%.4f s/step).", PHYSICS_HZ, TIME_STEP);
    }

    /**
     * Pauses or resumes the simulation without stopping the thread. While paused no steps
     * run and time does not pile up, so resuming continues from the same state instead of
     * running the missed steps at once. Safe to call from any thread.
     *
     * @param paused {@code true} to freeze physics, {@code false} to let it run
     */
    public void setPaused(boolean paused) {
        this.isPaused = paused;
    }

    /**
     * Signal the physics loop to stop and block until the thread terminates.
     * Safe to call from any thread except the physics thread itself, and harmless when the
     * loop is not running.
     *
     * <p>Waits at most 2 seconds. If the thread is still alive after that, a warning is
     * logged and the method returns anyway; being a daemon, the thread will not keep
     * the process alive.</p>
     */
    public void cleanup() {
        Thread thread = worker;
        if (thread == null) return;
        worker = null;
        thread.interrupt();
        boolean cleanStop = false;
        try {
            thread.join(2000L); // Block up to 2 s for the physics loop to notice it was stopped
            cleanStop = !thread.isAlive();
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
     *
     * <p>Hold it briefly: while another thread holds it, the physics step waits.</p>
     *
     * @return the lock object; always the same instance
     */
    public Object getSyncLock() {
        return syncLock;
    }

    // -------------------------------------------------------------------------
    // Thread body
    // -------------------------------------------------------------------------

    /**
     * Whether the loop is running: true from {@link #init()} until {@link #cleanup()}.
     *
     * @return {@code true} while a physics thread is started and not asked to stop
     */
    public boolean isRunning() {
        return worker != null;
    }

    /** The fixed-timestep loop, run by the worker thread {@link #init()} starts. */
    private void run() {
        Logger.info(Logger.System.CORE, "Physics thread online.");
        Thread self = Thread.currentThread();

        long lastTime   = System.nanoTime();
        float accumulator = 0.0f;

        while (worker == self && !self.isInterrupted()) {
            long now = System.nanoTime();
            float dt = (now - lastTime) / 1_000_000_000.0f;
            lastTime = now;

            if (!isPaused) {
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
            } else {
                // Bleed out the accumulator to prevent mass simulation steps upon resume
                accumulator = 0.0f;
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
