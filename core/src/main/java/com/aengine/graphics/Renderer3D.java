package com.aengine.graphics;

import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.joml.Vector4f;
import com.aengine.utils.FileUtils;
import com.aengine.utils.Logger;

import static org.lwjgl.opengl.GL30.*;

public class Renderer3D {

    private static ShaderAPI gridShader;

    /** Solid-colour shader for 3D entity meshes — position in, flat colour out. */
    private static ShaderAPI basicShader;

    private static Camera activeCameraContext;

    // Direct hardware pointers for isolated static geometry
    private static int gridVAO, gridVBO, gridEBO;
    private static int cubeVAO, cubeVBO, cubeEBO;

    public static void init() {
        Logger.info(Logger.System.RENDERER, "Initializing core 3D Projection Subsystem...");
        
        String vertSrc = FileUtils.readResource("/shaders/opengl/grid.vert");
        String fragSrc = FileUtils.readResource("/shaders/opengl/grid.frag");
        gridShader = RenderContext.createShader(vertSrc, fragSrc, true);

        // Entity mesh shader. Kept inline rather than in a resource file because it is the
        // minimum a solid mesh needs: transform the vertex, emit a uniform colour.
        String basicVert =
            "#version 330 core\n" +
            "layout (location = 0) in vec3 a_Position;\n" +
            "uniform mat4 u_ViewProjection;\n" +
            "uniform mat4 u_Transform;\n" +
            "void main() {\n" +
            "    gl_Position = u_ViewProjection * u_Transform * vec4(a_Position, 1.0);\n" +
            "}";

        String basicFrag =
            "#version 330 core\n" +
            "out vec4 FragColor;\n" +
            "uniform vec4 u_Color;\n" +
            "void main() {\n" +
            "    FragColor = u_Color;\n" +
            "}";

        basicShader = RenderContext.createShader(basicVert, basicFrag, true);

        // Upload the structural floor quad directly to VRAM once
        setupGridHardware();
        setupCubeHardware();
    }

    private static void setupCubeHardware() {
        // 8 corners of a unit cube centred on the origin.
        float[] vertices = {
            -0.5f, -0.5f, -0.5f,  0.5f, -0.5f, -0.5f,  0.5f,  0.5f, -0.5f, -0.5f,  0.5f, -0.5f,
            -0.5f, -0.5f,  0.5f,  0.5f, -0.5f,  0.5f,  0.5f,  0.5f,  0.5f, -0.5f,  0.5f,  0.5f
        };

        // 36 indices — 12 triangles, two per face.
        int[] indices = {
            4, 5, 6, 6, 7, 4,  1, 0, 3, 3, 2, 1,  0, 4, 7, 7, 3, 0,
            5, 1, 2, 2, 6, 5,  3, 7, 6, 6, 2, 3,  4, 0, 1, 1, 5, 4
        };

        cubeVAO = glGenVertexArrays();
        cubeVBO = glGenBuffers();
        cubeEBO = glGenBuffers();

        glBindVertexArray(cubeVAO);

        glBindBuffer(GL_ARRAY_BUFFER, cubeVBO);
        glBufferData(GL_ARRAY_BUFFER, vertices, GL_STATIC_DRAW);

        glBindBuffer(GL_ELEMENT_ARRAY_BUFFER, cubeEBO);
        glBufferData(GL_ELEMENT_ARRAY_BUFFER, indices, GL_STATIC_DRAW);

        glVertexAttribPointer(0, 3, GL_FLOAT, false, 3 * Float.BYTES, 0);
        glEnableVertexAttribArray(0);

        glBindVertexArray(0);
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

    /**
     * Draws a solid cube with the entity's real position, rotation and scale.
     *
     * <p>Unlike {@code Renderer2D.drawEntityQuad}, which billboards a flat quad toward the
     * camera, this puts actual volumetric geometry through the depth buffer. Colour only —
     * texturing a cube needs UV coordinates on the mesh, which it does not carry yet.</p>
     */
    public static void drawCube(Vector3f position, Vector3f rotation, Vector3f scale, Vector4f color) {
        // Force the 2D renderer to clear its queue to preserve depth testing order
        Renderer2D.flush();

        basicShader.bind();
        basicShader.setMat4("u_ViewProjection", activeCameraContext.getViewProjection());
        basicShader.setVec4("u_Color", color);

        Matrix4f transform = new Matrix4f()
            .translate(position)
            .rotateX((float) Math.toRadians(rotation.x))
            .rotateY((float) Math.toRadians(rotation.y))
            .rotateZ((float) Math.toRadians(rotation.z))
            .scale(scale);

        basicShader.setMat4("u_Transform", transform);

        glBindVertexArray(cubeVAO);
        glDrawElements(GL_TRIANGLES, 36, GL_UNSIGNED_INT, 0);
        glBindVertexArray(0);

        basicShader.unbind();
    }

    public static void endScene() {
        Renderer2D.endScene();
    }

    public static void cleanup() {
        Logger.info(Logger.System.RENDERER, "3D Context terminated.");
        if (gridShader  != null) gridShader.cleanup();
        if (basicShader != null) basicShader.cleanup();

        glDeleteVertexArrays(gridVAO);
        glDeleteBuffers(gridVBO);
        glDeleteBuffers(gridEBO);

        glDeleteVertexArrays(cubeVAO);
        glDeleteBuffers(cubeVBO);
        glDeleteBuffers(cubeEBO);
    }
}
