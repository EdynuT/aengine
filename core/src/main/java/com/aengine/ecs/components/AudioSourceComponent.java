package com.aengine.ecs.components;

/**
 * ECS Component: Audio Source
 * Define the acoustic properties of an entity and stores the OpenAL hardware emitter pointer.
 *
 * <p>The entity also needs a {@link TransformComponent}: the
 * {@link com.aengine.ecs.systems.AudioSystem} only processes entities that have both, and
 * moves the emitter to the transform's position every frame.</p>
 *
 * <p>The acoustic properties ({@link #gain}, {@link #pitch}, {@link #loop},
 * {@link #referenceDistance}, {@link #maxDistance}) are sent to OpenAL once, when the
 * source is created on the first frame. Changing them afterwards has no effect on the
 * sound that is already set up.</p>
 */
public class AudioSourceComponent {
    /**
     * Virtual path of the baked audio asset to play, e.g. {@code "assets://baked/audio/x.aaud"}.
     * {@code null} means no sound; the source is then never created.
     */
    public String audioPath = null;

    // Hardware IDs

    /** OpenAL buffer holding the decoded sound; {@code -1} until loaded. Set by the audio system. */
    public int bufferId = -1;
    /** OpenAL source (emitter) playing the buffer; {@code -1} until created. Set by the audio system. */
    public int sourceId = -1;

    // Acoustic Properties

    /** Volume multiplier; {@code 1.0} is the sound's own level, {@code 0.0} is silent. */
    public float gain = 1.0f;
    /** Playback speed and pitch multiplier; {@code 1.0} is unchanged, {@code 2.0} is an octave up. */
    public float pitch = 1.0f;
    /** Whether the sound restarts from the beginning when it ends. */
    public boolean loop = false;
    /** Whether the sound starts playing as soon as its source is created. */
    public boolean playOnAwake = true;

    // 3D Distance (From how many meters the sound starts to decay)

    /** Distance, in world units, within which the sound plays at full {@link #gain}; beyond it the volume starts to fall off. */
    public float referenceDistance = 2.0f;
    /** Distance, in world units, beyond which the sound stops getting quieter. */
    public float maxDistance = 50.0f;

    // Internal State

    /**
     * Set to {@code true} by the audio system when {@link #playOnAwake} starts the sound.
     * It is not cleared when a non-looping sound ends.
     */
    public boolean isPlaying = false;
    /**
     * {@code true} until the audio system has tried to create the OpenAL source.
     * Set it back to {@code true} to make the system load {@link #audioPath} again.
     */
    public boolean requiresHardwareInit = true;

    /** Creates a silent source with default acoustic properties and no audio path. */
    public AudioSourceComponent() {}
}
