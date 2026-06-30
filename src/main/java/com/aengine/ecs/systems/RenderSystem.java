package com.aengine.ecs.systems;

import com.aengine.Main;
import com.aengine.ecs.ComponentPool;
import com.aengine.ecs.Registry;
import com.aengine.ecs.System;
import com.aengine.ecs.components.SpriteComponent;
import com.aengine.ecs.components.TransformComponent;
import com.aengine.graphics.Renderer2D;
import com.aengine.utils.Logger;

public final class RenderSystem extends System {

    @Override
    public void update(Registry registry, float deltaTime) {
        ComponentPool<SpriteComponent> spritePool = registry.getPool(SpriteComponent.class);
        ComponentPool<TransformComponent> transformPool = registry.getPool(TransformComponent.class);

        if (spritePool == null || transformPool == null || spritePool.size() == 0) {
            return;
        }

        SpriteComponent[] sprites = spritePool.getRawComponents();
        int[] denseToEntity = spritePool.getRawDenseToEntity();
        int totalElements = spritePool.size();

        boolean is2DMode = Main.getActiveRenderMode() == Main.RenderMode.MODE_2D;

        for (int i = 0; i < totalElements; i++) {
            int entityID = denseToEntity[i];
            SpriteComponent sprite = sprites[i];
            TransformComponent transform = transformPool.get(entityID);
            
            if (transform == null) {
                Logger.warn(Logger.System.RENDERER, "Entity ID: %d possesses a SpriteComponent but lacks a TransformComponent. Skipping draw.", entityID);
                continue;
            }

            // Enforce strict 2D constraints by hard-resetting depth and pitch/yaw rotations directly
            if (is2DMode) {
                transform.position.z = 0.0f;
                transform.rotation.x = 0.0f;
                transform.rotation.y = 0.0f;
            }

            // Stream raw 3D vectors directly to the Renderer2D pipeline to eliminate local memory downsampling overhead
            if (sprite.texture != null) {
                Renderer2D.drawQuad(transform.position, transform.scale, sprite.texture);
                Logger.trace(Logger.System.RENDERER, "RenderSystem submitted textured quad for Entity ID: %d", entityID);
            } else {
                Renderer2D.drawQuad(transform.position, transform.scale, sprite.color);
                Logger.trace(Logger.System.RENDERER, "RenderSystem submitted colored quad for Entity ID: %d", entityID);
            }
        }
    }
}
