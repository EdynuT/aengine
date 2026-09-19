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
                Logger.error(Logger.System.RENDERER, "Vulkan driver hardware rendering context layer is missing.");
                throw new UnsupportedOperationException("Vulkan Renderer implementation missing.");
            default: throw new IllegalStateException("Unknown Graphics API target.");
        }
    }

    public static ShaderAPI createShader(String vertexPath, String fragmentPath) {
        switch (activeAPI) {
            case OPENGL: return new OpenGLShader(vertexPath, fragmentPath);
            case VULKAN: 
                throw new UnsupportedOperationException("Vulkan Shader implementation missing.");
            default: throw new IllegalStateException("Unknown Graphics API target.");
        }
    }

    public static ShaderAPI createShader(String vertexSource, String fragmentSource, boolean isRawSource) {
        switch (activeAPI) {
            case OPENGL: return new OpenGLShader(vertexSource, fragmentSource, isRawSource);
            case VULKAN:
                throw new UnsupportedOperationException("Vulkan Shader implementation missing.");
            default: throw new IllegalStateException("Unknown Graphics API target.");
        }
    }

    public static TextureAPI createTexture(String resourcePath) {
        switch (activeAPI) {
            case OPENGL: return new OpenGLTexture(resourcePath);
            case VULKAN:
                throw new UnsupportedOperationException("Vulkan Texture implementation missing.");
            default: throw new IllegalStateException("Unknown Graphics API target.");
        }
    }
}