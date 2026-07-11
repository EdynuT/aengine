package com.aengine.ecs.systems;

import com.aengine.Main;
import com.aengine.ecs.ComponentPool;
import com.aengine.ecs.Registry;
import com.aengine.ecs.System;
import com.aengine.ecs.components.SpriteComponent;
import com.aengine.ecs.components.TransformComponent;
import com.aengine.graphics.Renderer2D;
import com.aengine.graphics.Renderer3D;
import com.aengine.utils.Logger;
import org.joml.Vector4f;

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

            if (is2DMode) {
                // Modo 2D: Trava a profundidade e as rotações (Pitch/Yaw) para manter tudo plano
                transform.position.z = 0.0f;
                transform.rotation.x = 0.0f;
                transform.rotation.y = 0.0f;

                if (sprite.texture != null) {
                    Renderer2D.drawQuad(transform.position, transform.scale, sprite.texture);
                } else {
                    Renderer2D.drawQuad(transform.position, transform.scale, sprite.color);
                }
            } else {
                // =================================================================
                // HACK DE DEBUG: MODO 3D
                // Ignoramos a escala/rotação que o painel do Editor possa ter zerado
                // e forçamos o cubo a ter volume (1x1x1) e ficar rotacionado na diagonal
                // para podermos enxergar as quinas e faces perfeitamente.
                // =================================================================
                transform.scale.set(1.0f, 1.0f, 1.0f);
                transform.rotation.set(45.0f, 45.0f, 0.0f);

                // Usa a cor do SpriteComponent ou um azul padrão caso seja nulo
                Vector4f colorToUse = (sprite.color != null) ? sprite.color : new Vector4f(0.2f, 0.6f, 1.0f, 1.0f);
                
                // Despacha a geometria 3D nativa para a placa de vídeo
                Renderer3D.drawCube(transform.position, transform.rotation, transform.scale, colorToUse);
            }
        }
    }
}