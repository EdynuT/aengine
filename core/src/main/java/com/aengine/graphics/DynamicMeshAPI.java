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
     * <p>Vertices are 4-byte words laid out as described by the {@link VertexAttribute}
     * layout the mesh was created with. They are {@code int} rather than {@code float} so a
     * packed colour can travel unchanged: {@link Float#intBitsToFloat} does not guarantee to
     * preserve a NaN's bit pattern, and an opaque colour's bytes can form one. Floats go in
     * through {@link Float#floatToRawIntBits}, which always preserves bits.</p>
     *
     * <p>Implementations must write into storage reserved at creation rather than
     * reallocating — this runs every frame, and the UI framework's budget forbids
     * allocating on that path. The counts must therefore fit the capacities given to
     * {@link RenderContext#createDynamicMesh}; exceeding them is an error, not a resize.
     * With {@code indexCount == 0} the call does nothing and the previous contents stay.</p>
     *
     * @param vertexWords     source vertex data
     * @param vertexWordCount how many words of {@code vertexWords} are live
     * @param indices         source index data
     * @param indexCount      how many entries of {@code indices} are live
     */
    void upload(int[] vertexWords, int vertexWordCount, int[] indices, int indexCount);

    /**
     * Draws a range of the uploaded indices as triangles.
     *
     * <p>Ranges rather than the whole buffer, because one frame of interface geometry is
     * split into commands: each carries its own clip rectangle and texture, so it must be
     * issued as a separate draw over its own slice of the index stream.</p>
     *
     * @param indexOffset first index to draw, counted in indices, not bytes
     * @param indexCount  how many indices to draw
     */
    void draw(int indexOffset, int indexCount);

    /** Releases the underlying GPU and off-heap allocations. */
    void cleanup();
}
