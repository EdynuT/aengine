package com.aengine.audio;

import com.aengine.utils.Logger;
import org.lwjgl.openal.AL;
import org.lwjgl.openal.ALC;
import org.lwjgl.openal.ALCCapabilities;
import org.lwjgl.openal.ALCapabilities;

import java.nio.ByteBuffer;
import java.nio.IntBuffer;

import static org.lwjgl.openal.ALC10.*;
import static org.lwjgl.system.MemoryUtil.NULL;

/**
 * HARDWARE CONTEXT: OpenAL Subsystem
 * Manages the lifecycle of the native audio device and the spatial audio context.
 *
 * <p>One device and one context exist for the whole process. Call {@link #init()} once at
 * startup, before any {@link com.aengine.ecs.systems.AudioSystem} update, and
 * {@link #cleanup()} once at shutdown. The context is made current on the thread that
 * calls {@code init()}, so the audio system must run on that same thread.</p>
 *
 * <p>Distances fall off with OpenAL's "inverse distance clamped" model: full volume up to a
 * source's reference distance, then quieter with distance until its maximum distance.</p>
 */
public final class AudioDevice {

    private static long device;
    private static long context;

    private AudioDevice() {}

    /**
     * Opens the operating system's default audio output, creates the OpenAL context and
     * makes it current on the calling thread.
     *
     * <p>If the device reports no OpenAL 1.0 support, the problem is logged and
     * initialisation carries on. Calling this twice opens a second device without closing
     * the first.</p>
     *
     * @throws IllegalStateException if there is no audio device or the context cannot be created
     */
    public static void init() {
        Logger.info(Logger.System.CORE, "Initializing OpenAL Hardware Context...");

        // Solicita o dispositivo de áudio padrão do Sistema Operativo
        device = alcOpenDevice((ByteBuffer) null);
        if (device == NULL) {
            Logger.error(Logger.System.CORE, "Failed to open the default OpenAL device.");
            throw new IllegalStateException("OpenAL Device Failure");
        }

        // Cria as capacidades do dispositivo
        ALCCapabilities deviceCaps = ALC.createCapabilities(device);

        // Cria e ativa o contexto de áudio
        context = alcCreateContext(device, (IntBuffer) null);
        if (context == NULL) {
            Logger.error(Logger.System.CORE, "Failed to create OpenAL context.");
            throw new IllegalStateException("OpenAL Context Failure");
        }

        alcMakeContextCurrent(context);
        ALCapabilities alCaps = AL.createCapabilities(deviceCaps);

        if (!alCaps.OpenAL10) {
            Logger.error(Logger.System.CORE, "Hardware does not support OpenAL 1.0");
        }

        // Configura o modelo de atenuação de distância padrão (Inverse Distance Clamped)
        org.lwjgl.openal.AL10.alDistanceModel(org.lwjgl.openal.AL11.AL_INVERSE_DISTANCE_CLAMPED);
        
        Logger.info(Logger.System.CORE, "OpenAL Context bound successfully.");
    }

    /**
     * Releases the OpenAL context and closes the audio device. Sources and buffers created
     * by the audio system are not deleted one by one; they go with the context.
     *
     * <p>Call it once. The handles are not reset afterwards, so a second call would try to
     * destroy the same context and device again.</p>
     */
    public static void cleanup() {
        if (context != NULL) {
            alcMakeContextCurrent(NULL);
            alcDestroyContext(context);
        }
        if (device != NULL) {
            alcCloseDevice(device);
        }
    }
}
