package com.aengine.graphics;

import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.joml.Vector4f;
import com.aengine.utils.FileUtils;
import com.aengine.utils.Logger;

import static org.lwjgl.opengl.GL30.*;

public class Renderer3D {

    private static ShaderAPI gridShader;
    private static ShaderAPI basicShader; // NOVO: Shader exclusivo para objetos 3D
    private static Camera activeCameraContext;

    private static int gridVAO, gridVBO, gridEBO;
    private static int cubeVAO, cubeVBO, cubeEBO;

    public static void init() {
        Logger.info(Logger.System.RENDERER, "Initializing core 3D Projection Subsystem...");
        
        // --- SHADER DO CHÃO (Procedural) ---
        String vertSrc = FileUtils.readResource("/shaders/opengl/grid.vert");
        String fragSrc = FileUtils.readResource("/shaders/opengl/grid.frag");
        gridShader = RenderContext.createShader(vertSrc, fragSrc, true);

        // --- NOVO: SHADER BÁSICO (Para as Entidades 3D) ---
        String basicVert = 
            "#version 330 core\n" +
            "layout (location = 0) in vec3 a_Position;\n" +
            "uniform mat4 u_ViewProjection;\n" +
            "uniform mat4 u_Transform;\n" +
            "void main() {\n" +
            "    // Agora sim a matemática 3D de posição e rotação será respeitada!\n" +
            "    gl_Position = u_ViewProjection * u_Transform * vec4(a_Position, 1.0);\n" +
            "}";

        String basicFrag = 
            "#version 330 core\n" +
            "out vec4 FragColor;\n" +
            "uniform vec4 u_Color;\n" +
            "void main() {\n" +
            "    FragColor = u_Color;\n" +
            "}";

        // Compilando o shader básico em tempo de execução
        basicShader = RenderContext.createShader(basicVert, basicFrag, true);

        // Upload das geometrias para a VRAM
        setupGridHardware();
        setupCubeHardware();
    }

    private static void setupGridHardware() {
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

    private static void setupCubeHardware() {
        // 8 Vértices do Cubo
        float[] vertices = {
            -0.5f, -0.5f, -0.5f,  0.5f, -0.5f, -0.5f,  0.5f,  0.5f, -0.5f, -0.5f,  0.5f, -0.5f,
            -0.5f, -0.5f,  0.5f,  0.5f, -0.5f,  0.5f,  0.5f,  0.5f,  0.5f, -0.5f,  0.5f,  0.5f
        };
        // 36 Índices (12 Triângulos)
        int[] indices = {
            4, 5, 6, 6, 7, 4, 1, 0, 3, 3, 2, 1, 0, 4, 7, 7, 3, 0,
            5, 1, 2, 2, 6, 5, 3, 7, 6, 6, 2, 3, 4, 0, 1, 1, 5, 4
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

    public static void beginScene(Camera camera) {
        activeCameraContext = camera;
        Renderer2D.beginScene(camera);
    }

    public static void drawPlane(Vector3f position, Vector3f rotation, Vector3f scale, Vector4f color) {
        Renderer2D.flush();

        gridShader.bind();
        gridShader.setMat4("u_ViewProjection", activeCameraContext.getViewProjection());
        gridShader.setVec4("u_GridColor", color);

        Matrix4f transform = new Matrix4f()
            .translate(position)
            .rotateX((float) Math.toRadians(rotation.x))
            .rotateY((float) Math.toRadians(rotation.y))
            .rotateZ((float) Math.toRadians(rotation.z))
            .scale(scale);

        gridShader.setMat4("u_Transform", transform);

        glBindVertexArray(gridVAO);
        glDrawElements(GL_TRIANGLES, 6, GL_UNSIGNED_INT, 0);
        glBindVertexArray(0);

        gridShader.unbind();
    }

    public static void drawCube(Vector3f position, Vector3f rotation, Vector3f scale, Vector4f color) {
        Renderer2D.flush();

        // USANDO O NOVO SHADER BÁSICO (E não mais o shader da grade!)
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
        if (gridShader != null) gridShader.cleanup();
        if (basicShader != null) basicShader.cleanup(); // Limpa o novo shader da memória
        
        glDeleteVertexArrays(gridVAO);
        glDeleteBuffers(gridVBO);
        glDeleteBuffers(gridEBO);
        
        glDeleteVertexArrays(cubeVAO);
        glDeleteBuffers(cubeVBO);
        glDeleteBuffers(cubeEBO);
    }
}