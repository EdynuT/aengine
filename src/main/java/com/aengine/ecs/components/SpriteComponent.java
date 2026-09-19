package com.aengine.ecs.components;

import com.aengine.graphics.TextureAPI;
import org.joml.Vector4f;

public final class SpriteComponent {

    public TextureAPI texture = null;

    /**
     * Virtual path used to load {@link #texture}, e.g. {@code "assets://baked/textures/box.atex"}.
     * Set by {@link com.aengine.ecs.serialization.PrefabLoader} and the editor texture picker
     * so that the editor's scene serializer can round-trip the texture reference.
     * (Named rather than linked: the engine core must not reference the editor module.)
     * {@code null} when the entity has no texture (colour-only sprite).
     */
    public String texturePath = null;

    public final Vector4f color = new Vector4f(1.0f, 1.0f, 1.0f, 1.0f);

    public SpriteComponent() {}

    public SpriteComponent(Vector4f color) {
        this.color.set(color);
    }

    public SpriteComponent(TextureAPI texture) {
        this.texture = texture;
    }
}
