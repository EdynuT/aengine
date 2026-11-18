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
     * Draws the accumulated geometry, at 100 %: a unit is a pixel.
     *
     * @param drawList       geometry for this frame
     * @param viewportWidth  target width in pixels
     * @param viewportHeight target height in pixels
     */
    public void render(AegisDrawList drawList, int viewportWidth, int viewportHeight) {
        render(drawList, viewportWidth, viewportHeight, viewportWidth, viewportHeight, 1.0f);
    }

    /**
     * Draws the accumulated geometry, laid out in units, into a framebuffer in pixels. The
     * shader maps units to the whole framebuffer, so each unit covers {@code scale} pixels;
     * the clip rectangles, which the GPU takes in pixels, are scaled here — outwards, so a
     * clip never loses the pixel its edge falls in.
     *
     * @param drawList          geometry for this frame, in units
     * @param unitsWidth        the frame's width in units
     * @param unitsHeight       the frame's height in units
     * @param framebufferWidth  the target's width in pixels
     * @param framebufferHeight the target's height in pixels
     * @param scale             pixels per unit
     */
    public void render(AegisDrawList drawList, float unitsWidth, float unitsHeight,
                       int framebufferWidth, int framebufferHeight, float scale) {
        int indexCount = drawList.indexCount();
        if (indexCount == 0) return;

        renderer.setRenderTargetSize(framebufferWidth, framebufferHeight);
        renderer.setDepthTest(false);
        renderer.setBlend(true);

        shader.bind();
        viewportSize.set(unitsWidth, unitsHeight);
        shader.setVec2("u_ViewportSize", viewportSize);
        shader.setInt("u_Texture", 0);

        mesh.upload(drawList.vertices(), drawList.vertexWords(), drawList.indices(), indexCount);

        // One draw per command. The clip rectangle is pipeline state rather than vertex
        // data, so a frame cannot be issued as a single call once anything is clipped.
        int commands = drawList.commandCount();
        for (int i = 0; i < commands; i++) {
            float left   = drawList.commandClipX(i) * scale;
            float top    = drawList.commandClipY(i) * scale;
            float right  = left + drawList.commandClipW(i) * scale;
            float bottom = top  + drawList.commandClipH(i) * scale;
            int x = (int) Math.floor(left), y = (int) Math.floor(top);
            renderer.setScissor(x, y, (int) Math.ceil(right) - x, (int) Math.ceil(bottom) - y);

            renderer.bindTexture(0, drawList.commandTexture(i));

            mesh.draw(drawList.commandIndexOffset(i), drawList.commandIndexCount(i));
        }

        shader.unbind();
        end();
    }

    /**
     * Leaves the pipeline as the engine starts a frame: no scissor, no blending, depth testing
     * on. A scene pass that wants something else — 2D draws with depth testing off — sets it
     * at its own start, every frame, rather than counting on what it set once.
     */
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
