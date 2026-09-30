package com.aengine.utils;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

/**
 * Reads text bundled inside the engine's jar (classpath resources), such as the built-in
 * shaders. For files in a game project, use {@link FileSystem}.
 */
public class FileUtils {

    private FileUtils() {}

    /**
     * Reads a classpath resource as UTF-8 text.
     *
     * @param path absolute classpath path, e.g. {@code "/shaders/opengl/texture.vert"}
     * @return the whole resource as a string
     * @throws RuntimeException if the resource is missing or cannot be read
     */
    public static String readResource(String path) {
        try (InputStream is = FileUtils.class.getResourceAsStream(path)) {
            if (is == null) throw new RuntimeException("Resource not found: " + path);
            return new String(is.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new RuntimeException("Failed to read resource: " + path, e);
        }
    }

    /**
     * Reads a shader resource and injects the dynamic max texture slots count into the source text.
     *
     * <p>Two markers are replaced: {@code #MAX_TEXTURE_SLOTS#} with the slot count, and
     * {@code #DYNAMIC_SWITCH_BODY#} with one {@code case} per slot that samples
     * {@code u_Textures[i]} into {@code texColor}. The switch is needed because some GLSL
     * compilers reject indexing a sampler array with a value that varies per vertex.</p>
     *
     * @param path     absolute classpath path of the shader
     * @param maxSlots how many texture slots to generate
     * @return the shader source with both markers replaced
     * @throws RuntimeException if the resource is missing or cannot be read
     */
    public static String readAndInjectResource(String path, int maxSlots) {
        String source = readResource(path);
        
        // First, inject the array dimensions constraint
        source = source.replace("#MAX_TEXTURE_SLOTS#", String.valueOf(maxSlots));
        
        // Procedurally assemble the GLSL switch-case block to satisfy strict compilers like Mesa/ACO
        StringBuilder switchBuilder = new StringBuilder();
        for (int i = 0; i < maxSlots; i++) {
            switchBuilder.append("        case ")
                        .append(i)
                        .append(": texColor = texture(u_Textures[")
                        .append(i)
                        .append("], v_TexCoord); break;\n");
        }
        
        // Inject the final string tree back into the shader source code layout
        return source.replace("#DYNAMIC_SWITCH_BODY#", switchBuilder.toString());
    }
}
