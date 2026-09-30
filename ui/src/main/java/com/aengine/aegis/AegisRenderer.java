package com.aengine.aegis;

import com.aengine.graphics.DynamicMeshAPI;
import com.aengine.graphics.RenderContext;
import com.aengine.graphics.RendererAPI;
import com.aengine.graphics.ShaderAPI;
import com.aengine.utils.FileUtils;
import com.aengine.utils.Logger;
import org.joml.Vector2f;

/**
 * Presents a {@link AegisDrawList} on screen.
 *
 * <p>The only part of the framework that talks to the graphics layer, and it does so
 * exclusively through the API interfaces — never a backend class — so the whole of
 * {@code :ui} stays portable across renderer implementations.</p>
 *
 * <p>The pipeline configuration interface rendering needs is the opposite of the scene's:
 * depth testing off, because panels are drawn in submission order and depth would fight
 * that; alpha blending on, because every antialiased edge is partial coverage. Both are
 * restored in {@link #end()} so the engine's own passes are unaffected.</p>
 */
public final class AegisRenderer {

    private final RendererAPI    renderer;
    private final ShaderAPI      shader;
    private final DynamicMeshAPI mesh;

    /** Reused across frames: submitting a uniform must not allocate. */
    private final Vector2f viewportSize = new Vector2f();

    /**
     * Compiles the interface shader and reserves the GPU mesh. Must be called on the GL thread.
     *
     * @param maxQuads shapes per frame; must match the {@link AegisDrawList} this renderer
     *                 will draw, since the mesh is sized from it
     */
    public AegisRenderer(int maxQuads) {
        this.renderer = RenderContext.createRenderer();

        String vertexSource   = FileUtils.readResource("/shaders/aegis/aegis.vert");
        String fragmentSource = FileUtils.readResource("/shaders/aegis/aegis.frag");
        this.shader = RenderContext.createShader(vertexSource, fragmentSource, true);

        this.mesh = RenderContext.createDynamicMesh(
            maxQuads * 4 * AegisDrawList.WORDS_PER_VERTEX,
            maxQuads * 6,
            AegisDrawList.VERTEX_LAYOUT);

        Logger.info(Logger.System.RENDERER,
            "Aegis online. SDF pipeline ready for %d shapes per frame.", maxQuads);
    }

    /**
     * Draws the accumulated geometry.
     *
     * @param drawList       geometry for this frame
     * @param viewportWidth  target width in pixels
     * @param viewportHeight target height in pixels
     */
    public void render(AegisDrawList drawList, int viewportWidth, int viewportHeight) {
        int indexCount = drawList.indexCount();
        if (indexCount == 0) return;

        renderer.setRenderTargetSize(viewportWidth, viewportHeight);
        renderer.setDepthTest(false);
        renderer.setBlend(true);

        shader.bind();
        viewportSize.set(viewportWidth, viewportHeight);
        shader.setVec2("u_ViewportSize", viewportSize);
        shader.setInt("u_Texture", 0);

        mesh.upload(drawList.vertices(), drawList.vertexWords(), drawList.indices(), indexCount);

        // One draw per command. The clip rectangle is pipeline state rather than vertex
        // data, so a frame cannot be issued as a single call once anything is clipped.
        int commands = drawList.commandCount();
        for (int i = 0; i < commands; i++) {
            renderer.setScissor(
                (int) drawList.commandClipX(i),
                (int) drawList.commandClipY(i),
                (int) drawList.commandClipW(i),
                (int) drawList.commandClipH(i));

            renderer.bindTexture(0, drawList.commandTexture(i));

            mesh.draw(drawList.commandIndexOffset(i), drawList.commandIndexCount(i));
        }

        shader.unbind();
        end();
    }

    /** Restores the pipeline state the engine's scene passes expect. */
    private void end() {
        renderer.disableScissor();
        renderer.setBlend(false);
        renderer.setDepthTest(true);
    }

    /** Releases the mesh and the shader. The renderer must not be used afterwards. */
    public void cleanup() {
        mesh.cleanup();
        shader.cleanup();
        Logger.info(Logger.System.RENDERER, "Aegis pipeline released.");
    }
}
