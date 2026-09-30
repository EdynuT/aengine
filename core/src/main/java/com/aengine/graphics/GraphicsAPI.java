package com.aengine.graphics;

/**
 * The graphics backends {@link RenderContext} can create objects for. Only
 * {@link #OPENGL} is implemented; the factories throw for the others.
 */
public enum GraphicsAPI {
    /** OpenGL 3.3 core, through LWJGL. The only working backend. */
    OPENGL,
    /** Reserved; not implemented. */
    VULKAN,
    /** Reserved; not implemented. */
    DIRECTX12
}
