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
 * Gerencia o ciclo de vida do dispositivo de áudio nativo e o contexto espacial.
 */
public final class AudioDevice {

    private static long device;
    private static long context;

    private AudioDevice() {}

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
