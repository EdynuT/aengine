package com.aengine.editor;

import com.aengine.ecs.Registry;
import com.aengine.ecs.components.ColliderComponent;
import com.aengine.ecs.components.ScriptComponent;
import com.aengine.ecs.components.RigidbodyComponent;
import com.aengine.ecs.components.SpriteComponent;
import com.aengine.ecs.components.TransformComponent;
import com.aengine.utils.FileSystem;
import com.aengine.utils.Logger;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;

/**
 * Serialises the live ECS state back to the scene JSON format understood by
 * {@link com.aengine.ecs.serialization.SceneLoader}.
 *
 * <p>Only entities that possess both a {@link TransformComponent} and a
 * {@link SpriteComponent} are written out — camera entities and other
 * infrastructure entities are intentionally excluded.</p>
 *
 * <p>Entities are written as inline component blocks (not prefab references)
 * to preserve any runtime mutations (colour changes, texture assignments, etc.)
 * that differ from the original prefab definition.</p>
 */
public final class SceneSerializer {

    private static final com.google.gson.Gson GSON =
        new GsonBuilder().setPrettyPrinting().create();

    private SceneSerializer() {}

    // =========================================================================
    // Public API
    // =========================================================================

    /**
     * Serializes the ECS state directly into a memory JSON Object. Used for RAM backups.
     */
    public static JsonObject serializeScene(Registry registry, String sceneName) {
        JsonObject root = new JsonObject();
        root.addProperty("name", sceneName);

        JsonArray entities = new JsonArray();

        var view = registry.getEntitiesWith(TransformComponent.class, SpriteComponent.class);
        for (int i = 0; i < view.size(); i++) {
            int entityId = view.get(i);
            JsonObject entityObj = new JsonObject();
            JsonObject components = new JsonObject();

            // --- TransformComponent ---
            TransformComponent t = registry.getComponent(entityId, TransformComponent.class);
            if (t != null) {
                JsonObject tObj = new JsonObject();
                tObj.add("position", toArray(t.position.x, t.position.y, t.position.z));
                tObj.add("rotation", toArray(t.rotation.x, t.rotation.y, t.rotation.z));
                tObj.add("scale",    toArray(t.scale.x,    t.scale.y,    t.scale.z));
                components.add("TransformComponent", tObj);
            }

            // --- SpriteComponent ---
            SpriteComponent s = registry.getComponent(entityId, SpriteComponent.class);
            if (s != null) {
                JsonObject sObj = new JsonObject();
                if (s.texturePath != null && !s.texturePath.isEmpty()) {
                    sObj.addProperty("texture", s.texturePath);
                }
                sObj.add("color", toArray(s.color.x, s.color.y, s.color.z, s.color.w));
                components.add("SpriteComponent", sObj);
            }

            // --- ColliderComponent ---
            ColliderComponent c = registry.getComponent(entityId, ColliderComponent.class);
            if (c != null) {
                JsonObject cObj = new JsonObject();
                cObj.addProperty("type", c.type.name());
                cObj.add("size", toArray(c.size.x, c.size.y, c.size.z));
                cObj.add("offset", toArray(c.offset.x, c.offset.y, c.offset.z));
                cObj.addProperty("isTrigger", c.isTrigger);
                components.add("ColliderComponent", cObj);
            }

            // --- RigidbodyComponent ---
            RigidbodyComponent rb = registry.getComponent(entityId, RigidbodyComponent.class);

            ScriptComponent scriptCmp = registry.getComponent(entityId, ScriptComponent.class);
            if (scriptCmp != null && scriptCmp.scriptPath != null && !scriptCmp.scriptPath.isEmpty()) {
                JsonObject scriptObj = new JsonObject();
                scriptObj.addProperty("scriptPath", scriptCmp.scriptPath);
                components.add("ScriptComponent", scriptObj);
            }

            if (rb != null) {
                JsonObject rbObj = new JsonObject();
                rbObj.addProperty("mass", rb.mass);
                rbObj.addProperty("friction", rb.friction);
                rbObj.addProperty("restitution", rb.restitution);
                rbObj.addProperty("isKinematic", rb.isKinematic);
                components.add("RigidbodyComponent", rbObj);
            }

            entityObj.add("components", components);
            entities.add(entityObj);
        }

        root.add("entities", entities);
        return root;
    }

    /**
     * Writes the current state of all renderable ECS entities to {@code virtualPath}.
     *
     * @param registry    the active ECS registry
     * @param virtualPath VFS path such as {@code "assets://data/scenes/level_01.scene"}
     * @param sceneName   human-readable scene name stored in the JSON root
     */
    public static void save(Registry registry, String virtualPath, String sceneName) {
        JsonObject root = serializeScene(registry, sceneName);
        File file = FileSystem.resolve(virtualPath);
        try {
            if (file.getParentFile() != null) file.getParentFile().mkdirs();
            try (FileWriter writer = new FileWriter(file)) {
                writer.write(GSON.toJson(root));
            }
            Logger.info(Logger.System.CORE,
                "Scene serialized: %d entities → %s", root.getAsJsonArray("entities").size(), file.getAbsolutePath());
        } catch (IOException e) {
            Logger.error(Logger.System.CORE,
                "Failed to write scene file [%s]: %s", virtualPath, e.getMessage());
        }
    }

    // =========================================================================
    // Helpers
    // =========================================================================

    private static JsonArray toArray(float... values) {
        JsonArray a = new JsonArray(values.length);
        for (float v : values) a.add(v);
        return a;
    }
}
