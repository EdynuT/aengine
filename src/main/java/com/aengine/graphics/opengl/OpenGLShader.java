package com.aengine.graphics.opengl;

import com.aengine.graphics.ShaderAPI;
import com.aengine.utils.FileUtils;
import com.aengine.utils.Logger;
import org.joml.Matrix4f;
import org.joml.Vector2f;
import org.joml.Vector3f;
import org.joml.Vector4f;
import org.lwjgl.BufferUtils;

import java.nio.FloatBuffer;
import java.util.HashMap;
import java.util.Map;

import static org.lwjgl.opengl.GL11.GL_FALSE;
import static org.lwjgl.opengl.GL20.*;

public class OpenGLShader implements ShaderAPI {

    private int programId;
    private final Map<String, Integer> uniformCache = new HashMap<>();

    // Standard path-based initialization wrapper.
    public OpenGLShader(String vertexPath, String fragmentPath) {
        Logger.debug(Logger.System.SHADER, "Loading pipeline shaders from paths: [Vert: %s | Frag: %s]", vertexPath, fragmentPath);
        initFromSource(FileUtils.readResource(vertexPath), FileUtils.readResource(fragmentPath));
    }

    // Overloaded constructor accepting raw or dynamically generated GLSL source code directly.
    public OpenGLShader(String vertexSource, String fragmentSource, boolean isRawSource) {
        Logger.debug(Logger.System.SHADER, "Compiling pipeline shaders from dynamic runtime raw source strings.");
        initFromSource(vertexSource, fragmentSource);
    }

    private void initFromSource(String vertexSource, String fragmentSource) {
        int vert = compile(GL_VERTEX_SHADER,   vertexSource);
        int frag = compile(GL_FRAGMENT_SHADER, fragmentSource);

        programId = glCreateProgram();
        glAttachShader(programId, vert);
        glAttachShader(programId, frag);
        glLinkProgram(programId);

        if (glGetProgrami(programId, GL_LINK_STATUS) == GL_FALSE) {
            String log = glGetProgramInfoLog(programId);
            Logger.error(Logger.System.SHADER, "Pipeline link failure:\n%s", log);
            throw new RuntimeException("Shader link error:\n" + log);
        }

        Logger.info(Logger.System.SHADER, "Shader pipeline attached and linked successfully. Program ID: %d", programId);

        glDeleteShader(vert);
        glDeleteShader(frag);
    }

    private int compile(int type, String src) {
        int id = glCreateShader(type);
        glShaderSource(id, src);
        glCompileShader(id);
        
        if (glGetShaderi(id, GL_COMPILE_STATUS) == GL_FALSE) {
            String label = (type == GL_VERTEX_SHADER) ? "Vertex" : "Fragment";
            String log = glGetShaderInfoLog(id);
            Logger.error(Logger.System.SHADER, "%s GLSL compilation failed:\n%s", label, log);
            throw new RuntimeException(label + " shader compile error:\n" + log);
        }
        return id;
    }

    @Override 
    public void bind() { 
        glUseProgram(programId); 
    }
    
    @Override 
    public void unbind() { 
        glUseProgram(0); 
    }

    private int location(String name) {
        return uniformCache.computeIfAbsent(name, n -> {
            int loc = glGetUniformLocation(programId, n);
            if (loc == -1) {
                Logger.warn(Logger.System.SHADER, "Active uniform target '%s' not found or optimized out in program %d.", n, programId);
            }
            return loc;
        });
    }

    @Override public void setInt(String name, int value)     { glUniform1i(location(name), value); }
    @Override public void setFloat(String name, float value) { glUniform1f(location(name), value); }
    @Override public void setVec2(String name, Vector2f v)   { glUniform2f(location(name), v.x, v.y); }
    @Override public void setVec3(String name, Vector3f v)   { glUniform3f(location(name), v.x, v.y, v.z); }
    @Override public void setVec4(String name, Vector4f v)   { glUniform4f(location(name), v.x, v.y, v.z, v.w); }

    // Allocation-free static continuous flat array memory hook for matrix updates
    private final float[] matrixBuffer = new float[16];

    @Override
    public void setMat4(String name, Matrix4f mat) {
        int loc = location(name);
        if (loc != -1) {
            // 1. Dump direct column-major float values straight into the reusable flat array
            mat.get(matrixBuffer);
            
            // 2. Stream the flat array natively through the JNI bridge down to the AMD driver
            glUniformMatrix4fv(loc, false, matrixBuffer);
        }
    }

    @Override 
    public void cleanup() { 
        Logger.info(Logger.System.SHADER, "Deleting shader pipeline program ID: %d", programId);
        glDeleteProgram(programId); 
    }
}
