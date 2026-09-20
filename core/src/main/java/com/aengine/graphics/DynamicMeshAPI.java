package com.aengine.graphics;

/**
 * A geometry buffer whose contents are replaced every frame.
 *
 * <p>The batch buffers on {@link RendererAPI} assume the engine's quad layout, with an index
 * pattern generated once at startup. Interface rendering cannot use them: it produces a
 * fresh vertex <em>and</em> index stream each frame, with its own attribute layout.</p>
 *
 * <p>This interface exists so {@code :ui} can own geometry without reaching into a backend
 * package — the boundary in docs/UI_FRAMEWORK_ARCHITECTURE.md §3 forbids that, and the
 * whole point of the graphics API layer is that a Vulkan backend can replace the OpenGL one
 * underneath without the caller noticing.</p>
 *
 * <p>Instances are created through {@link RenderContext#createDynamicMesh}.</p>
 */
public interface DynamicMeshAPI {

    /**
     * Replaces the buffer contents.
     *
     * <p>Implementations must write into storage reserved at creation rather than
     * reallocating — this runs every frame, and the UI framework's budget forbids
     * allocating on that path.</p>
     *
     * @param vertices         source vertex data
     * @param vertexFloatCount how many floats of {@code vertices} are live
     * @param indices          source index data
     * @param indexCount       how many entries of {@code indices} are live
     */
    void upload(float[] vertices, int vertexFloatCount, int[] indices, int indexCount);

    /** Draws {@code indexCount} indices as triangles from the last {@link #upload}. */
    void draw(int indexCount);

    /** Releases the underlying GPU and off-heap allocations. */
    void cleanup();
}
