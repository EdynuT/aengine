package com.aengine.editor;

import com.aengine.ecs.Registry;
import com.aengine.ecs.components.SpriteComponent;
import com.aengine.ecs.components.TransformComponent;
import org.joml.Vector3f;
import org.joml.Vector4f;

/**
 * Convenience factory for runtime entity creation inside the editor.
 *
 * <p>Every entity gets a {@link TransformComponent} (1×1×1 default scale) and a
 * {@link SpriteComponent} with a distinguishable solid colour so it is immediately
 * visible without a texture. The caller is responsible for holding the physics
 * sync-lock before invoking these methods if the physics thread is running.</p>
 */
public final class EntityFactory {

    /** Default colour for Quad entities — warm red. */
    private static final Vector4f COLOR_QUAD = new Vector4f(0.85f, 0.30f, 0.25f, 1.0f);

    /** Default colour for Cube entities — sky blue. */
    private static final Vector4f COLOR_CUBE = new Vector4f(0.25f, 0.50f, 0.90f, 1.0f);

    private EntityFactory() {}

    // =========================================================================
    // 2D — flat quad at (x, y)
    // =========================================================================

    /**
     * Creates a coloured 1×1 quad at {@code (x, y, 0)} — intended for 2D mode.
     *
     * @return the new entity ID
     */
    public static int createQuad(Registry registry, float x, float y) {
        int id = registry.createEntity();

        TransformComponent t = new TransformComponent(new Vector3f(x, y, 0.0f));
        t.scale.set(1.0f, 1.0f, 1.0f);
        registry.addComponent(id, t);

        SpriteComponent s = new SpriteComponent(new Vector4f(COLOR_QUAD));
        registry.addComponent(id, s);

        EditorState.setEntityName(id, "Quad #" + id);
        return id;
    }

    // =========================================================================
    // 3D — billboard sprite at (x, y, z)
    // =========================================================================

    /**
     * Creates a coloured 1×1×1 sprite-box at {@code (x, y, z)} — intended for 3D mode.
     *
     * <p>Note: the engine currently renders all sprites as camera-facing quads via
     * {@code Renderer2D}. In a future mesh pipeline this factory will emit a proper
     * cube mesh instead.</p>
     *
     * @return the new entity ID
     */
    public static int createCube(Registry registry, float x, float y, float z) {
        int id = registry.createEntity();

        TransformComponent t = new TransformComponent(new Vector3f(x, y, z));
        t.scale.set(1.0f, 1.0f, 1.0f);
        registry.addComponent(id, t);

        SpriteComponent s = new SpriteComponent(new Vector4f(COLOR_CUBE));
        registry.addComponent(id, s);

        EditorState.setEntityName(id, "Cube #" + id);
        return id;
    }
}
