package com.aengine.ecs.components;

import com.aengine.graphics.TextureAPI;
import org.joml.Vector4f;

/**
 * Makes an entity visible as a flat, textured or coloured quad. Drawn with the entity's
 * {@link TransformComponent}.
 */
public final class SpriteComponent {

    /** Texture drawn on the quad; {@code null} draws the {@link #color} alone. */
    public TextureAPI texture = null;

    /**
     * Virtual path used to load {@link #texture}, e.g. {@code "assets://baked/textures/box.atex"}.
     * Set by {@link com.aengine.ecs.serialization.PrefabLoader} and the editor texture picker
     * so that the editor's scene serializer can round-trip the texture reference.
     * (Named rather than linked: the engine core must not reference the editor module.)
     * {@code null} when the entity has no texture (colour-only sprite).
     */
    public String texturePath = null;

    /** RGBA tint from 0 to 1, multiplied with the texture. Opaque white by default, which leaves the texture unchanged. */
    public final Vector4f color = new Vector4f(1.0f, 1.0f, 1.0f, 1.0f);

    /** Creates an opaque white sprite with no texture. */
    public SpriteComponent() {}

    /**
     * Creates an untextured sprite of a single colour.
     *
     * @param color RGBA colour from 0 to 1; copied, not kept
     */
    public SpriteComponent(Vector4f color) {
        this.color.set(color);
    }

    /**
     * Creates a sprite showing a texture with no tint.
     * {@link #texturePath} stays {@code null}, so set it too if the scene will be saved.
     *
     * @param texture the texture to draw
     */
    public SpriteComponent(TextureAPI texture) {
        this.texture = texture;
    }
}
