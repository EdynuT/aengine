package com.aengine.ecs.components;

/**
 * ECS Component: Audio Source
 * Define the acoustic properties of an entity and stores the OpenAL hardware emitter pointer.
 */
public class AudioSourceComponent {
    public String audioPath = null;
    
    // Hardware IDs
    public int bufferId = -1;
    public int sourceId = -1;

    // Acoustic Properties
    public float gain = 1.0f;
    public float pitch = 1.0f;
    public boolean loop = false;
    public boolean playOnAwake = true;

    // 3D Distance (From how many meters the sound starts to decay)
    public float referenceDistance = 2.0f;
    public float maxDistance = 50.0f;

    // Internal State
    public boolean isPlaying = false;
    public boolean requiresHardwareInit = true;
}
