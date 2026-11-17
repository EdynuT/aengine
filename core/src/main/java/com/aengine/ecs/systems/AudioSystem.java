package com.aengine.ecs.systems;

import com.aengine.ecs.Registry;
import com.aengine.ecs.System;
import com.aengine.ecs.components.AudioSourceComponent;
import com.aengine.ecs.components.CameraComponent;
import com.aengine.ecs.components.TransformComponent;
import com.aengine.graphics.AssetManager;

import static org.lwjgl.openal.AL10.*;

/**
 * Drives OpenAL from the ECS once per frame.
 *
 * <ol>
 *   <li>Moves the listener (the "ears") to the position of the first primary camera that
 *       has a {@link TransformComponent}. Orientation is not sent yet, so left and right
 *       do not follow the camera's rotation.</li>
 *   <li>For every entity with a {@link TransformComponent} and an
 *       {@link AudioSourceComponent}: on the first frame, loads the audio buffer, creates
 *       the source, applies the acoustic properties and starts it if
 *       {@code playOnAwake}; on every frame, moves the source to the transform's position.</li>
 * </ol>
 *
 * <p>Requires {@link com.aengine.audio.AudioDevice#init()} to have run, and must be
 * updated on the thread that owns the OpenAL context.</p>
 */
public final class AudioSystem extends System {

    /** Creates the system; it keeps no state between frames. */
    public AudioSystem() {}

    @Override
    public void update(Registry registry, float deltaTime) {
        
        // 1. Update Listener Position (Primary Camera)
        var cameras = registry.getEntitiesWith(CameraComponent.class, TransformComponent.class);
        for (int i = 0; i < cameras.size(); i++) {
            int camEntity = cameras.get(i);
            CameraComponent cam = registry.getComponent(camEntity, CameraComponent.class);
            if (cam.primary) {
                TransformComponent t = registry.getComponent(camEntity, TransformComponent.class);
                alListener3f(AL_POSITION, t.position.x, t.position.y, t.position.z);
                // (For absolute 3D audio realism, we would also inject AL_ORIENTATION here)
                break;
            }
        }

        // 2. Process Sources
        var audioEntities = registry.getEntitiesWith(TransformComponent.class, AudioSourceComponent.class);
        for (int i = 0; i < audioEntities.size(); i++) {
            int entityID = audioEntities.get(i);
            TransformComponent t = registry.getComponent(entityID, TransformComponent.class);
            AudioSourceComponent audio = registry.getComponent(entityID, AudioSourceComponent.class);

            // JIT Hardware Initialization Delay
            if (audio.requiresHardwareInit && audio.audioPath != null) {
                audio.bufferId = AssetManager.loadAudioBuffer(audio.audioPath);
                if (audio.bufferId != -1) {
                    audio.sourceId = alGenSources();
                    alSourcei(audio.sourceId, AL_BUFFER, audio.bufferId);
                    alSourcei(audio.sourceId, AL_LOOPING, audio.loop ? AL_TRUE : AL_FALSE);
                    alSourcef(audio.sourceId, AL_PITCH, audio.pitch);
                    alSourcef(audio.sourceId, AL_GAIN, audio.gain);
                    alSourcef(audio.sourceId, AL_REFERENCE_DISTANCE, audio.referenceDistance);
                    alSourcef(audio.sourceId, AL_MAX_DISTANCE, audio.maxDistance);
                    
                    if (audio.playOnAwake) {
                        alSourcePlay(audio.sourceId);
                        audio.isPlaying = true;
                    }
                }
                audio.requiresHardwareInit = false;
            }

            // Dynamic Positional Synchronization at 60Hz
            if (audio.sourceId != -1) {
                alSource3f(audio.sourceId, AL_POSITION, t.position.x, t.position.y, t.position.z);
            }
        }
    }
}
