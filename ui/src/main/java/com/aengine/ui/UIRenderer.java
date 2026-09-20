package com.aengine.ui;

import com.aengine.graphics.DynamicMeshAPI;
import com.aengine.graphics.RenderContext;
import com.aengine.graphics.RendererAPI;
import com.aengine.graphics.ShaderAPI;
import com.aengine.utils.FileUtils;
import com.aengine.utils.Logger;
import org.joml.Vector2f;

/**
 * Presents a {@link UIDrawList} on screen.
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
public final class UIRenderer {

    private final RendererAPI    renderer;
    private final ShaderAPI      shader;
    private final DynamicMeshAPI mesh;

    /** Reused across frames: submitting a uniform must not allocate. */
    private final Vector2f viewportSize = new Vector2f();

    public UIRenderer(int maxQuads) {
        this.renderer = RenderContext.createRenderer();

        String vertexSource   = FileUtils.readResource("/shaders/ui/ui.vert");
        String fragmentSource = FileUtils.readResource("/shaders/ui/ui.frag");
        this.shader = RenderContext.createShader(vertexSource, fragmentSource, true);

        this.mesh = RenderContext.createDynamicMesh(
            maxQuads * 4 * UIDrawList.FLOATS_PER_VERTEX,
            maxQuads * 6,
            UIDrawList.VERTEX_LAYOUT);

        Logger.info(Logger.System.RENDERER,
            "UI renderer online. SDF pipeline ready for %d shapes per frame.", maxQuads);
    }

    /**
     * Draws the accumulated geometry.
     *
     * @param drawList       geometry for this frame
     * @param viewportWidth  target width in pixels
     * @param viewportHeight target height in pixels
     */
    public void render(UIDrawList drawList, int viewportWidth, int viewportHeight) {
        int indexCount = drawList.indexCount();
        if (indexCount == 0) return;

        renderer.setRenderTargetSize(viewportWidth, viewportHeight);
        renderer.setDepthTest(false);
        renderer.setBlend(true);

        shader.bind();
        viewportSize.set(viewportWidth, viewportHeight);
        shader.setVec2("u_ViewportSize", viewportSize);

        mesh.upload(drawList.vertices(), drawList.vertexFloats(), drawList.indices(), indexCount);
        mesh.draw(indexCount);

        shader.unbind();
        end();
    }

    /** Restores the pipeline state the engine's scene passes expect. */
    private void end() {
        renderer.setBlend(false);
        renderer.setDepthTest(true);
    }

    public void cleanup() {
        mesh.cleanup();
        shader.cleanup();
        Logger.info(Logger.System.RENDERER, "UI renderer pipeline released.");
    }
}
