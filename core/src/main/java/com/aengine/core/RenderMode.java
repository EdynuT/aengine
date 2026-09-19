package com.aengine.core;

/**
 * Which render pipeline the engine is running: flat 2D batching or 3D mesh.
 *
 * <p>Selected once at startup (the application host parses {@code --2d} / {@code --3d})
 * and read by engine systems to branch their per-frame behaviour — {@code RenderSystem}
 * picks a batcher, {@code CameraSystem} picks a projection.</p>
 *
 * <p>This lives in the core rather than in the application host because it is engine
 * state, not editor state: the ECS systems that read it must not depend on whichever
 * host happens to have started the engine.</p>
 */
public enum RenderMode {

    MODE_2D,
    MODE_3D;

    /** Volatile: written once on the main thread at startup, read by the physics thread. */
    private static volatile RenderMode active = MODE_3D;

    /** The pipeline currently in effect. Defaults to {@link #MODE_3D}. */
    public static RenderMode active() { return active; }

    /**
     * Selects the pipeline. Call before the engine loop starts — systems read this every
     * frame and do not expect it to change underneath them.
     */
    public static void setActive(RenderMode mode) {
        if (mode != null) active = mode;
    }

    /** Convenience for the common branch. */
    public static boolean is2D() { return active == MODE_2D; }
}
