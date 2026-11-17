package com.aengine.editor;

import com.aengine.ecs.Registry;
import com.aengine.ecs.components.SpriteComponent;
import com.aengine.ecs.components.TransformComponent;
import com.aengine.ecs.serialization.SceneLoader;
import com.google.gson.JsonObject;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/** A scene written by the editor and read back, as entering and leaving Play mode does. */
class SceneRoundTripTest {

    @Test
    void anEntityComesBackWithItsSpriteAfterPlay() {
        Registry registry = new Registry();
        int entity = registry.createEntity();
        TransformComponent t = new TransformComponent();
        t.position.set(1.0f, 2.0f, 0.0f);
        registry.addComponent(entity, t);
        SpriteComponent sprite = new SpriteComponent();
        sprite.color.set(0.25f, 0.5f, 0.75f, 1.0f);
        registry.addComponent(entity, sprite);

        JsonObject snapshot = SceneSerializer.serializeScene(registry, "RAM_BACKUP");
        registry.clearScene();
        SceneLoader.load(registry, snapshot);

        var view = registry.getEntitiesWith(TransformComponent.class, SpriteComponent.class);
        assertEquals(1, view.size(), "the entity keeps its sprite (KNOWN_ISSUES #1)");
        SpriteComponent back = registry.getComponent(view.get(0), SpriteComponent.class);
        assertNotNull(back);
        assertEquals(0.5f, back.color.y);
        assertEquals(2.0f, registry.getComponent(view.get(0), TransformComponent.class).position.y);

        // A second Play and Stop keeps it too: the entity is still written out.
        JsonObject again = SceneSerializer.serializeScene(registry, "RAM_BACKUP");
        registry.clearScene();
        SceneLoader.load(registry, again);
        assertEquals(1, registry.getEntitiesWith(TransformComponent.class, SpriteComponent.class).size());
    }
}
