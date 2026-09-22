package com.aengine.graphics;

import com.aengine.graphics.opengl.OpenGLRenderer;
import com.aengine.graphics.opengl.OpenGLShader;
import com.aengine.graphics.opengl.OpenGLTexture;
import com.aengine.utils.Logger;

public class RenderContext {

    private static GraphicsAPI activeAPI = GraphicsAPI.OPENGL;

    public static void setAPI(GraphicsAPI api) {
        Logger.info(Logger.System.RENDERER, "Switching graphics context factory line to: %s", api);
        activeAPI = api;
    }

    public static GraphicsAPI getAPI() {
        return activeAPI;
    }

    /**
     * Factory execution pipeline for the base hardware command driver interface.
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
     * @param layout         the attributes of one vertex, in location order
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