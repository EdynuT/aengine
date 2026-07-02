package com.aengine.ecs.serialization;

import com.aengine.ecs.Registry;
import com.aengine.ecs.components.TransformComponent;
import com.aengine.utils.FileSystem;
import com.aengine.utils.Logger;
import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.io.File;
import java.io.FileReader;

/**
 * HARDWARE CONTEXT: DATA-DRIVEN SCENE ORCHESTRATOR
 * Parses world layouts and invokes Prefab instantiations. Applies spatial overrides
 * (Transformations) to prevent all prefabs from spawning at the exact same origin coordinate.
 */
public class SceneLoader {
    private static final Gson gson = new Gson();

    public static void load(Registry activeRegistry, String virtualPath) {
        File file = FileSystem.resolve(virtualPath);
        
        if (!file.exists()) {
            Logger.error(Logger.System.CORE, "Scene load failed. Physical map not found: %s", file.getAbsolutePath());
            return;
        }

        try (FileReader reader = new FileReader(file)) {
            JsonObject root = gson.fromJson(reader, JsonObject.class);
            String sceneName = root.has("name") ? root.get("name").getAsString() : "Unnamed_Scene";
            Logger.info(Logger.System.CORE, "Mounting Scene Workspace: [%s]", sceneName);

            if (root.has("entities")) {
                JsonArray entities = root.getAsJsonArray("entities");
                
                for (JsonElement element : entities) {
                    JsonObject entityData = element.getAsJsonObject();
                    
                    if (entityData.has("prefab")) {
                        String prefabPath = entityData.get("prefab").getAsString();
                        
                        // 1. Allocate the base memory block from the entity template
                        int entityId = PrefabLoader.instantiate(activeRegistry, prefabPath);
                        if (entityId == -1) continue;

                        // 2. Apply world-space positional overrides dictacted by the scene
                        if (entityData.has("transform")) {
                            JsonObject transformOverride = entityData.getAsJsonObject("transform");
                            TransformComponent transform = activeRegistry.getComponent(entityId, TransformComponent.class);
                            
                            if (transform != null) {
                                if (transformOverride.has("position")) {
                                    JsonArray pos = transformOverride.getAsJsonArray("position");
                                    transform.position.set(pos.get(0).getAsFloat(), pos.get(1).getAsFloat(), pos.get(2).getAsFloat());
                                }
                                if (transformOverride.has("rotation")) {
                                    JsonArray rot = transformOverride.getAsJsonArray("rotation");
                                    transform.rotation.set(rot.get(0).getAsFloat(), rot.get(1).getAsFloat(), rot.get(2).getAsFloat());
                                }
                                if (transformOverride.has("scale")) {
                                    JsonArray scale = transformOverride.getAsJsonArray("scale");
                                    transform.scale.set(scale.get(0).getAsFloat(), scale.get(1).getAsFloat(), scale.get(2).getAsFloat());
                                }
                            }
                        }
                    }
                }
            }
            Logger.info(Logger.System.CORE, "Scene [%s] loaded successfully into active ECS.", sceneName);
            
        } catch (Exception e) {
            Logger.error(Logger.System.CORE, "Failed to parse .scene format: %s. Reason: %s", virtualPath, e.getMessage());
        }
    }
}
