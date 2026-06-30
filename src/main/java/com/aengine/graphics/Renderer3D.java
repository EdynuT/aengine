package com.aengine.graphics;

import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.joml.Vector2f;
import org.joml.Vector4f;
import com.aengine.utils.FileUtils;
import com.aengine.utils.Logger;

import static org.lwjgl.opengl.GL30.*;

public class Renderer3D {

    private static ShaderAPI gridShader;
    private static Camera activeCameraContext;

    // Direct hardware pointers for isolated static geometry
    private static int gridVAO, gridVBO, gridEBO;

    public static void init() {
        Logger.info(Logger.System.RENDERER, "Initializing core 3D Projection Subsystem...");
        
        String vertSrc = FileUtils.readResource("/shaders/opengl/grid.vert");
        String fragSrc = FileUtils.readResource("/shaders/opengl/grid.frag");
        gridShader = RenderContext.createShader(vertSrc, fragSrc, true);

        // Upload the structural floor quad directly to VRAM once
        setupGridHardware();
    }

    private static void setupGridHardware() {
        // Flat 1x1 quad centered at origin, facing Y-up natively
        float[] vertices = {
            -0.5f, 0.0f, -0.5f,
             0.5f, 0.0f, -0.5f,
             0.5f, 0.0f,  0.5f,
            -0.5f, 0.0f,  0.5f
        };

        int[] indices = { 0, 1, 2, 2, 3, 0 };

        gridVAO = glGenVertexArrays();
        gridVBO = glGenBuffers();
        gridEBO = glGenBuffers();

        glBindVertexArray(gridVAO);

        glBindBuffer(GL_ARRAY_BUFFER, gridVBO);
        glBufferData(GL_ARRAY_BUFFER, vertices, GL_STATIC_DRAW);

        glBindBuffer(GL_ELEMENT_ARRAY_BUFFER, gridEBO);
        glBufferData(GL_ELEMENT_ARRAY_BUFFER, indices, GL_STATIC_DRAW);

        glVertexAttribPointer(0, 3, GL_FLOAT, false, 3 * Float.BYTES, 0);
        glEnableVertexAttribArray(0);

        glBindVertexArray(0);
    }

    public static void beginScene(Camera camera) {
        activeCameraContext = camera;
        Renderer2D.beginScene(camera);
    }

    public static void drawPlane(Vector3f position, Vector3f rotation, Vector3f scale, Vector4f color) {
        // Force the 2D renderer to clear its queue to preserve depth testing order
        Renderer2D.flush();

        gridShader.bind();
        gridShader.setMat4("u_ViewProjection", activeCameraContext.getViewProjection());
        gridShader.setVec4("u_GridColor", color);

        // Hardware-side spatial transformation
        Matrix4f transform = new Matrix4f()
            .translate(position)
            .rotateX((float) Math.toRadians(rotation.x))
            .rotateY((float) Math.toRadians(rotation.y))
            .rotateZ((float) Math.toRadians(rotation.z))
            .scale(scale);

        gridShader.setMat4("u_Transform", transform);

        // Bypass Renderer2D entirely. Dispatch draw command natively.
        glBindVertexArray(gridVAO);
        glDrawElements(GL_TRIANGLES, 6, GL_UNSIGNED_INT, 0);
        glBindVertexArray(0);

        gridShader.unbind();
    }

    public static void endScene() {
        Renderer2D.endScene();
    }

    public static void cleanup() {
        Logger.info(Logger.System.RENDERER, "3D Context terminated.");
        if (gridShader != null) gridShader.cleanup();
        glDeleteVertexArrays(gridVAO);
        glDeleteBuffers(gridVBO);
        glDeleteBuffers(gridEBO);
    }
}
