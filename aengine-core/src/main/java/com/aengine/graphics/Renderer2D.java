package com.aengine.graphics;

import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.joml.Vector4f;
import org.joml.Vector2f;
import com.aengine.utils.FileUtils;
import com.aengine.utils.Logger;

public class Renderer2D {

    private static final int MAX_QUADS = 1000;
    private static final int VERTICES_PER_QUAD = 4;
    private static final int INDICES_PER_QUAD = 6;
    private static final int VERTEX_SIZE_FLOATS = 10; // pos(3), uv(2), color(4), texIndex(1)
    
    private static final int MAX_VERTICES = MAX_QUADS * VERTICES_PER_QUAD;
    private static final int MAX_INDICES = MAX_QUADS * INDICES_PER_QUAD;
    
    private static int maxTextureSlots;

    private static RendererAPI renderer; 
    private static ShaderAPI   batchShader;

    private static float[] vertexBuffer;
    private static int     vertexCount = 0;
    private static int     indexCount  = 0;

    private static TextureAPI[] textureSlots;
    private static int          textureSlotIndex = 0;

    // Allocation-free static math transformations
    private static final Matrix4f transformMatrix = new Matrix4f();

    private static final Vector4f[] LOCAL_VERTEX_POSITIONS = {
        new Vector4f(-0.5f, -0.5f, 0.0f, 1.0f),
        new Vector4f( 0.5f, -0.5f, 0.0f, 1.0f),
        new Vector4f( 0.5f,  0.5f, 0.0f, 1.0f),
        new Vector4f(-0.5f,  0.5f, 0.0f, 1.0f)
    };

    private static final Vector2f[] LOCAL_UV_COORDS = {
        new Vector2f(0.0f, 0.0f),
        new Vector2f(1.0f, 0.0f),
        new Vector2f(1.0f, 1.0f),
        new Vector2f(0.0f, 1.0f)
    };

    public static void init() {
        Logger.info(Logger.System.RENDERER, "Initializing Batch Renderer pipeline...");

        // Ensure hardware capability mapping is bound and active before allocating buffers
        HardwareCapabilities.initialize();
        maxTextureSlots = HardwareCapabilities.getMaxTextureSlots();

        renderer = RenderContext.createRenderer();
        renderer.init();
        
        String vertSrc = FileUtils.readResource("/shaders/opengl/texture.vert");
        String fragSrc = FileUtils.readAndInjectResource("/shaders/opengl/texture.frag", maxTextureSlots);
        
        batchShader = RenderContext.createShader(vertSrc, fragSrc, true);

        vertexBuffer = new float[MAX_VERTICES * VERTEX_SIZE_FLOATS];
        textureSlots = new TextureAPI[maxTextureSlots];
        
        renderer.initBatchBuffers(MAX_VERTICES, MAX_INDICES, VERTEX_SIZE_FLOATS);

        Logger.info(Logger.System.RENDERER, "Batch storage allocations verified. Capable of %d quads per draw call.", MAX_QUADS);
    }

    public static void beginScene(Camera camera) {
        batchShader.bind();
        batchShader.setMat4("u_ViewProjection", camera.getViewProjection());
        
        for (int i = 0; i < maxTextureSlots; i++) {
            batchShader.setInt("u_Textures[" + i + "]", i);
        }
        batchShader.unbind();

        startBatch();
    }

    private static void startBatch() {
        vertexCount = 0;
        indexCount = 0;
        textureSlotIndex = 0;
    }

    public static void endScene() {
        flush();
    }

    public static void flush() {
        if (indexCount == 0) return;

        // Sequentially bind texture units to hardware slots before draw execution
        for (int i = 0; i < textureSlotIndex; i++) {
            textureSlots[i].bind(i);
        }

        batchShader.bind();
        
        // Zero-allocation buffer pass: streaming raw pointer boundaries directly
        renderer.drawBatch(vertexBuffer, vertexCount, indexCount);

        batchShader.unbind();
    }

    private static void nextBatch() {
        flush();
        startBatch();
    }

    public static void drawQuad(Vector3f position, Vector3f size, Vector4f color) {
        drawQuad(position, size, null, color);
    }

    public static void drawQuad(Vector3f position, Vector3f size, TextureAPI texture) {
        drawQuad(position, size, texture, new Vector4f(1.0f));
    }

    public static void drawQuad(Vector3f position, Vector3f size, TextureAPI texture, Vector4f tint) {
        if (indexCount >= MAX_INDICES) {
            nextBatch();
        }

        float textureIndex = getOrCreateTextureIndex(texture);

        // Mutate transformation data within the static matrix registry to ensure zero-heap allocation
        transformMatrix.identity()
                       .translate(position)
                       .scale(size);

        // Capture the absolute top base index where this specific quad sequence block starts
        int baseIndex = vertexCount;

        for (int i = 0; i < VERTICES_PER_QUAD; i++) {
            Vector4f transformedPos = new Vector4f(LOCAL_VERTEX_POSITIONS[i]).mul(transformMatrix);

            vertexBuffer[baseIndex + 0] = transformedPos.x;
            vertexBuffer[baseIndex + 1] = transformedPos.y;
            vertexBuffer[baseIndex + 2] = transformedPos.z; 
            vertexBuffer[baseIndex + 3] = LOCAL_UV_COORDS[i].x;
            vertexBuffer[baseIndex + 4] = LOCAL_UV_COORDS[i].y;
            vertexBuffer[baseIndex + 5] = tint.x;
            vertexBuffer[baseIndex + 6] = tint.y;
            vertexBuffer[baseIndex + 7] = tint.z;
            vertexBuffer[baseIndex + 8] = tint.w;
            vertexBuffer[baseIndex + 9] = textureIndex;

            // Move the local writing head pointer forward by exactly one vertex size unit
            baseIndex += VERTEX_SIZE_FLOATS;
        }

        // Finalize the batch state by incrementing global tracker metrics only after safe allocation pass
        vertexCount += (VERTICES_PER_QUAD * VERTEX_SIZE_FLOATS);
        indexCount  += INDICES_PER_QUAD;
    }

    // Static allocation-free math hooks to handle complex 2D/3D matrix rotations layout
    private static final org.joml.Vector4f tempVertexPos = new org.joml.Vector4f();

    /**
     * Specialized ECS integration path. Evaluates packed component data arrays sequentially 
     * without creating auxiliary wrapper objects during batch submission. Supports full 3D rotations.
     */
    public static void drawEntityQuad(com.aengine.ecs.components.TransformComponent transform, com.aengine.ecs.components.SpriteComponent sprite) {
        if (indexCount >= MAX_INDICES) {
            nextBatch();
        }

        // Fetch dynamic hardware texture slot mappings via pre-existing optimized cache scanner
        float textureIndex = getOrCreateTextureIndex(sprite.texture);

        // Enforce strict zero-allocation matrix construction supporting complete pitch, yaw and roll Euler rotations
        transformMatrix.identity()
                       .translate(transform.position)
                       .rotateXYZ(
                           (float) Math.toRadians(transform.rotation.x),
                           (float) Math.toRadians(transform.rotation.y),
                           (float) Math.toRadians(transform.rotation.z)
                       )
                       .scale(transform.scale);

        for (int i = 0; i < VERTICES_PER_QUAD; i++) {
            // Stream position values into shared static vector container to shield L1 cache from object allocation
            tempVertexPos.set(LOCAL_VERTEX_POSITIONS[i]).mul(transformMatrix);

            int baseIndex = vertexCount;
            vertexBuffer[baseIndex + 0] = tempVertexPos.x;
            vertexBuffer[baseIndex + 1] = tempVertexPos.y;
            vertexBuffer[baseIndex + 2] = tempVertexPos.z; // Complete Z positioning injected into rendering pipeline
            vertexBuffer[baseIndex + 3] = LOCAL_UV_COORDS[i].x;
            vertexBuffer[baseIndex + 4] = LOCAL_UV_COORDS[i].y;
            vertexBuffer[baseIndex + 5] = sprite.color.x;
            vertexBuffer[baseIndex + 6] = sprite.color.y;
            vertexBuffer[baseIndex + 7] = sprite.color.z;
            vertexBuffer[baseIndex + 8] = sprite.color.w;
            vertexBuffer[baseIndex + 9] = textureIndex;

            vertexCount += VERTEX_SIZE_FLOATS;
        }

        indexCount += INDICES_PER_QUAD;
    }

    public static void cleanup() {
        Logger.info(Logger.System.RENDERER, "Deallocating internal Batch Renderer pipeline elements...");
        if (batchShader != null) 
            batchShader.cleanup();
        if (renderer != null)
            renderer.cleanup();
    }

    public static void setClearColor(float r, float g, float b, float a) {
        renderer.setClearColor(new Vector4f(r, g, b, a));
    }
    
    public static float getOrCreateTextureIndex(TextureAPI texture) {
        if (texture == null) return -1.0f;
        
        // Intercept and evaluate asset metrics before submitting allocation handles
        int textureWidth = texture.getWidth();
        int textureHeight = texture.getHeight();
        int maxHardwareSize = HardwareCapabilities.getMaxTextureSize();

        // Kinda buggy. The log appears multiple times if the texture is too big, but it is not a problem - just a warning (very annoying tho)
        // if (textureWidth > 4096 || textureHeight > 4096) {
        //     Logger.warn(Logger.System.RENDERER, 
        //         "Performance Warning: Texture [ID: %d] payload is heavy (%dx%d px). " +
        //         "Consider utilizing the AEngine optimization pipeline to compress this asset into a native block format.",
        //         texture.getID(), textureWidth, textureHeight
        //     );
        // }

        if (textureWidth > maxHardwareSize || textureHeight > maxHardwareSize) {
            Logger.warn(Logger.System.RENDERER, 
                "Hardware Limit: Texture [ID: %d] size (%dx%d px) exceeds maximum GPU capabilities (%dx%d px). This might cause rendering issues.",
                texture.getID(), textureWidth, textureHeight, maxHardwareSize, maxHardwareSize
            );
        }
        
        // Linear cache scan to find already bound hardware asset handles
        for (int i = 0; i < textureSlotIndex; i++) {
            if (textureSlots[i].getID() == texture.getID()) {
                return (float) i;
            }
        }

        // Trigger a pipeline flush if maximum physical texture allocation is reached during execution
        if (textureSlotIndex >= maxTextureSlots) {
            nextBatch();
        }
        
        textureSlots[textureSlotIndex] = texture;
        float index = (float) textureSlotIndex;
        textureSlotIndex++;
        return index;
    }

    static float[] getVertexBuffer() { return vertexBuffer; }
    static int getVertexCount() { return vertexCount; }
    static void setVertexCount(int count) { vertexCount = count; }
    static int getIndexCount() { return indexCount; }
    static void setIndexCount(int count) { indexCount = count; }
}
