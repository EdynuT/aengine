package com.aengine.graphics;

import com.aengine.graphics.opengl.OpenGLTexture;
import com.aengine.utils.Logger;
import java.util.concurrent.ConcurrentHashMap;

/**
 * HARDWARE CONTEXT: VRAM CACHE & HOT-RELOAD ROUTER
 * Prevents redundant GPU allocations and routes OS-level mutation events to active hardware pointers.
 */
public class AssetManager {
    // Thread-safe map to handle async loading and main-thread reading simultaneously
    private static final ConcurrentHashMap<String, OpenGLTexture> textureCache = new ConcurrentHashMap<>();

    public static TextureAPI getTexture(String virtualPath) {
        // If it exists, return the pointer. If not, allocate and return.
        return textureCache.computeIfAbsent(virtualPath, OpenGLTexture::new);
    }

    public static void hotReloadTexture(String virtualPath) {
        OpenGLTexture texture = textureCache.get(virtualPath);
        if (texture != null) {
            texture.reload(virtualPath);
        } else {
            Logger.warn(Logger.System.ASSET, "Hot-Reload skipped. Texture not currently active in ECS: %s", virtualPath);
        }
    }

    public static void clear() {
        for (OpenGLTexture tex : textureCache.values()) {
            tex.cleanup();
        }
        textureCache.clear();
    }
}
