package com.aengine.utils;

import org.lwjgl.stb.STBImage;
import org.lwjgl.system.MemoryStack;

import java.io.DataOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.nio.ByteBuffer;
import java.nio.IntBuffer;

/*
 * HARDWARE CONTEXT: OFFLINE ASSET PIPELINE
 * This utility parses heavy compressed web formats (PNG/JPG) and bakes them 
 * into uncompressed, hardware-ready proprietary binary formats (.atex).
 * This eliminates CPU decompression overhead during runtime loading.
 */
public final class AssetBaker {

    // "ATEX" em representação hexadecimal ASCII (0x41 0x54 0x45 0x58)
    public static final int MAGIC_NUMBER = 0x41544558; 
    public static final int VERSION = 1;

    private AssetBaker() {}

    /**
     * Compiles a raw PNG/JPG into an optimized .atex binary blob.
     * @param inputPath Absolute path to the raw image (e.g., "C:/mygame/raw/hero.png")
     * @param outputPath Absolute path to the baked output (e.g., "C:/mygame/assets/textures/hero.atex")
     */
    public static void bakeTexture(String inputPath, String outputPath) {
        Logger.info(Logger.System.ASSET, "Baking raw texture: %s -> %s", inputPath, outputPath);

        try (MemoryStack stack = MemoryStack.stackPush()) {
            IntBuffer w = stack.mallocInt(1);
            IntBuffer h = stack.mallocInt(1);
            IntBuffer channels = stack.mallocInt(1);

            // Mantém a imagem invertida verticalmente (Padrão OpenGL)
            STBImage.stbi_set_flip_vertically_on_load(true);
            
            // Decodifica a imagem original do disco
            ByteBuffer pixels = STBImage.stbi_load(inputPath, w, h, channels, 4);

            if (pixels == null) {
                Logger.error(Logger.System.ASSET, "Bake Failed! Could not parse input image: %s", STBImage.stbi_failure_reason());
                return;
            }

            int width = w.get(0);
            int height = h.get(0);
            int pixelByteCount = width * height * 4; // 4 channels (RGBA)

            File outFile = new File(outputPath);
            // Garante que a pasta de destino exista
            outFile.getParentFile().mkdirs(); 

            // Escreve os bytes diretamente no disco
            try (DataOutputStream dos = new DataOutputStream(new FileOutputStream(outFile))) {
                
                // --- WRITE HEADER (20 Bytes) ---
                dos.writeInt(MAGIC_NUMBER);
                dos.writeInt(VERSION);
                dos.writeInt(width);
                dos.writeInt(height);
                dos.writeInt(4); // Fixed RGBA

                // --- WRITE PAYLOAD ---
                // Transfere o buffer nativo (Fora da JVM) para um array Java (Heap) para gravação no disco
                byte[] pixelArray = new byte[pixelByteCount];
                pixels.get(pixelArray);
                dos.write(pixelArray);
                
                Logger.info(Logger.System.ASSET, "Successfully baked .atex file! [%dx%d, Payload: %d bytes]", width, height, pixelByteCount);
            }

            // Libera a memória nativa alocada pelo decodificador
            STBImage.stbi_image_free(pixels);

        } catch (Exception e) {
            Logger.error(Logger.System.ASSET, "Catastrophic I/O failure during texture baking: %s", e.getMessage());
            e.printStackTrace();
        }
    }

    // Ponto de entrada de testes
    public static void main(String[] args) {
        // Exemplo: Substitua pelos caminhos reais no seu PC para testar a conversão
        // bakeTexture("C:/Users/Usuario/Desktop/teste.png", "C:/Users/Usuario/AeternumSandbox/assets/teste.atex");
    }
}
