package com.aengine.graphics;

import com.aengine.graphics.opengl.OpenGLRenderer;
import com.aengine.graphics.opengl.OpenGLShader;
import com.aengine.graphics.opengl.OpenGLTexture;
import com.aengine.utils.Logger;

/**
 * Factory for backend objects: the only place that knows which graphics API is in use.
 *
 * <p>Code above the backend asks this class for renderers, shaders, textures and meshes
 * and receives the {@code ...API} interfaces, never an OpenGL class, so a different backend
 * can be slotted in here without touching the callers. Only {@link GraphicsAPI#OPENGL}
 * is implemented; every factory throws {@link UnsupportedOperationException} for the
 * other values.</p>
 *
 * <p>Everything created here talks to the GPU, so the factories must run on the GL
 * thread, after the window and its context exist.</p>
 */
public class RenderContext {

    private static GraphicsAPI activeAPI = GraphicsAPI.OPENGL;

    private RenderContext() {}

    /**
     * Chooses the backend for objects created from now on. Objects created earlier keep
     * their backend. Choose before the renderers are initialised.
     *
     * @param api the backend to use
     */
    public static void setAPI(GraphicsAPI api) {
        Logger.info(Logger.System.RENDERER, "Switching graphics context factory line to: %s", api);
        activeAPI = api;
    }

    /**
     * Returns the backend new objects will be created for.
     *
     * @return the active backend; {@link GraphicsAPI#OPENGL} by default
     */
    public static GraphicsAPI getAPI() {
        return activeAPI;
    }

    /**
     * Factory execution pipeline for the base hardware command driver interface.
     * The renderer is returned uninitialised; call {@link RendererAPI#init()} on it.
     *
     * @return a new renderer for the active backend
     */
    public static RendererAPI createRenderer() {
        switch (activeAPI) {
            case OPENGL: return new OpenGLRenderer();
            case VULKAN:
                Logger.error(Logger.System.RENDERER, "Vulkan driver hardware rendering context layer is not implemented yet.");
                throw new UnsupportedOperationException("Vulkan Renderer implementation not implemented yet.");
            case DIRECTX12:
                Logger.error(Logger.System.RENDERER, "DirectX 12 driver hardware rendering context layer is not implemented yet.");
                throw new UnsupportedOperationException("DirectX 12 Renderer implementation not implemented yet.");
            default: throw new IllegalStateException("Unknown Graphics API target.");
        }
    }

    /**
     * Factory for a frame-updated geometry buffer — see {@link DynamicMeshAPI}.
     *
     * @param maxVertexWords capacity of the vertex buffer, in 4-byte words
     * @param maxIndices     capacity of the index buffer, in indices
     * @param layout         the attributes of one vertex, in location order
     * @return a new, empty mesh with its storage reserved
     */
    public static DynamicMeshAPI createDynamicMesh(int maxVertexWords, int maxIndices, VertexAttribute[] layout) {
        switch (activeAPI) {
            case OPENGL: return new com.aengine.graphics.opengl.OpenGLDynamicMesh(
                                        maxVertexWords, maxIndices, layout);
            case VULKAN:
                throw new UnsupportedOperationException("Vulkan DynamicMesh implementation not implemented yet.");
            case DIRECTX12:
                throw new UnsupportedOperationException("DirectX 12 DynamicMesh implementation not implemented yet.");
            default: throw new IllegalStateException("Unknown Graphics API target.");
        }
    }

    /**
     * Compiles and links a shader from two classpath resources.
     *
     * @param vertexPath   classpath path of the vertex shader, e.g. {@code "/shaders/opengl/texture.vert"}
     * @param fragmentPath classpath path of the fragment shader
     * @return the linked program
     * @throws RuntimeException if a stage fails to compile or the program fails to link;
     *                          the driver's log is in the message
     */
    public static ShaderAPI createShader(String vertexPath, String fragmentPath) {
        switch (activeAPI) {
            case OPENGL: return new OpenGLShader(vertexPath, fragmentPath);
            case VULKAN: 
                throw new UnsupportedOperationException("Vulkan Shader implementation not implemented yet.");
            case DIRECTX12:
                throw new UnsupportedOperationException("DirectX 12 Shader implementation not implemented yet.");
            default: throw new IllegalStateException("Unknown Graphics API target.");
        }
    }

    /**
     * Compiles and links a shader from source text, for shaders that are generated or
     * patched in code.
     *
     * <p>{@code isRawSource} only tells this overload apart from the path-based one; its
     * value is ignored, and the strings are always treated as source.</p>
     *
     * @param vertexSource   GLSL source of the vertex shader
     * @param fragmentSource GLSL source of the fragment shader
     * @param isRawSource    ignored; pass {@code true}
     * @return the linked program
     * @throws RuntimeException if a stage fails to compile or the program fails to link;
     *                          the driver's log is in the message
     */
    public static ShaderAPI createShader(String vertexSource, String fragmentSource, boolean isRawSource) {
        switch (activeAPI) {
            case OPENGL: return new OpenGLShader(vertexSource, fragmentSource, isRawSource);
            case VULKAN:
                throw new UnsupportedOperationException("Vulkan Shader implementation not implemented yet.");
            case DIRECTX12:
                throw new UnsupportedOperationException("DirectX 12 Shader implementation not implemented yet.");
            default: throw new IllegalStateException("Unknown Graphics API target.");
        }
    }

    /**
     * Factory for a texture uploaded from pixels already in memory, rather than loaded from
     * a project asset. For textures the engine generates itself, such as a glyph atlas.
     *
     * <p>The pixels are copied to the GPU before this returns; the caller keeps ownership of
     * the buffer. Must be called on the GL thread.</p>
     *
     * <p>Filtering is linear and edges clamp, which suits generated images such as atlases.</p>
     *
     * @param width  width in pixels
     * @param height height in pixels
     * @param format how the bytes in {@code pixels} are laid out
     * @param pixels {@code width * height * format.bytesPerPixel} bytes, rows top to bottom
     *               with no padding between them
     * @return the uploaded texture, ready to bind
     */
    public static TextureAPI createTexture(int width, int height, TextureFormat format,
                                           java.nio.ByteBuffer pixels) {
        switch (activeAPI) {
            case OPENGL: return new com.aengine.graphics.opengl.OpenGLMemoryTexture(
                                        width, height, format, pixels);
            case VULKAN:
                throw new UnsupportedOperationException("Vulkan Texture implementation not implemented yet.");
            case DIRECTX12:
                throw new UnsupportedOperationException("DirectX 12 Texture implementation not implemented yet.");
            default: throw new IllegalStateException("Unknown Graphics API target.");
        }
    }

    /**
     * Creates a texture from a project image and loads it in the background.
     *
     * <p>Returns at once: the file is read and decoded on a worker thread, and the pixels
     * reach the GPU on the first {@link TextureAPI#bind(int)} after decoding finishes.
     * Until then the texture binds nothing and reports a size of 1x1. Accepts baked
     * {@code .atex} files and anything stb_image reads (PNG, JPG, ...). Prefer
     * {@link AssetManager#getTexture(String)}, which shares one texture per path.</p>
     *
     * @param resourcePath virtual path of the image, resolved through
     *                     {@link com.aengine.utils.FileSystem#resolve(String)}
     * @return the texture, possibly still loading
     */
    public static TextureAPI createTexture(String resourcePath) {
        switch (activeAPI) {
            case OPENGL: return new OpenGLTexture(resourcePath);
            case VULKAN:
                throw new UnsupportedOperationException("Vulkan Texture implementation not implemented yet.");
            case DIRECTX12:
                throw new UnsupportedOperationException("DirectX 12 Texture implementation not implemented yet.");
            default: throw new IllegalStateException("Unknown Graphics API target.");
        }
    }
}