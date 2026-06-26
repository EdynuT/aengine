package com.aengine;

import com.aengine.utils.Logger;
import org.lwjgl.glfw.GLFWErrorCallback;
import org.lwjgl.glfw.GLFWVidMode;
import org.lwjgl.opengl.GL;

import static org.lwjgl.glfw.GLFW.*;
import static org.lwjgl.opengl.GL11.*;
import static org.lwjgl.system.MemoryUtil.NULL;

public class Window {

    private final String title;
    private int    width;
    private int    height;
    private long   handle;

    public Window(String title, int width, int height) {
        this.title  = title;
        this.width  = width;
        this.height = height;
    }

    public void init() {
        Logger.info(Logger.System.WINDOW, "Redirecting GLFW error pipeline to internal logging engine...");
        glfwSetErrorCallback((errorCode, description) -> 
            Logger.error(Logger.System.WINDOW, "GLFW Error [0x%X]: %s", errorCode, GLFWErrorCallback.getDescription(description))
        );

        Logger.info(Logger.System.WINDOW, "Initializing GLFW subsystem...");
        if (!glfwInit()) {
            Logger.error(Logger.System.WINDOW, "Critical: GLFW initialization failed.");
            throw new IllegalStateException("Failed to initialize GLFW");
        }

        glfwDefaultWindowHints();
        glfwWindowHint(GLFW_CONTEXT_VERSION_MAJOR, 3);
        glfwWindowHint(GLFW_CONTEXT_VERSION_MINOR, 3);
        glfwWindowHint(GLFW_OPENGL_PROFILE,        GLFW_OPENGL_CORE_PROFILE);
        glfwWindowHint(GLFW_OPENGL_FORWARD_COMPAT, GLFW_TRUE);
        glfwWindowHint(GLFW_VISIBLE,   GLFW_FALSE);
        glfwWindowHint(GLFW_RESIZABLE, GLFW_TRUE);

        Logger.debug(Logger.System.WINDOW, "Instantiating native window '%s' (%dx%d)...", title, width, height);
        handle = glfwCreateWindow(width, height, title, NULL, NULL);
        if (handle == NULL) {
            Logger.error(Logger.System.WINDOW, "Critical: Window creation rejected by display server.");
            throw new RuntimeException("Failed to create GLFW window");
        }

        glfwSetFramebufferSizeCallback(handle, (win, w, h) -> {
            width  = w;
            height = h;
            glViewport(0, 0, w, h);
            Logger.trace(Logger.System.WINDOW, "Viewport hardware sync updated to: %dx%d", w, h);
        });

        // Safe positioning block — evaluated only after valid window context instantiation
        if (glfwGetPlatform() != GLFW_PLATFORM_WAYLAND) {
            GLFWVidMode vidMode = glfwGetVideoMode(glfwGetPrimaryMonitor());
            if (vidMode != null) {
                glfwSetWindowPos(handle,
                    (vidMode.width()  - width)  / 2,
                    (vidMode.height() - height) / 2);
            }
        } else {
            Logger.debug(Logger.System.WINDOW, "Wayland detected. Window positioning delegated to compositor.");
        }

        glfwMakeContextCurrent(handle);
        glfwSwapInterval(1);
        
        Logger.info(Logger.System.RENDERER, "Binding LWJGL OpenGL capabilities to current hardware thread...");
        GL.createCapabilities();

        // Query driver telemetry metadata to intercept Mesa/AMD specific bugs
        String vendor   = glGetString(GL_VENDOR);
        String renderer = glGetString(GL_RENDERER);
        String version  = glGetString(GL_VERSION);
        Logger.info(Logger.System.RENDERER, "GPU Vendor : %s", vendor);
        Logger.info(Logger.System.RENDERER, "Hardware   : %s", renderer);
        Logger.info(Logger.System.RENDERER, "GL Version : %s", version);

        glClearColor(0.1f, 0.1f, 0.1f, 1.0f);
        glEnable(GL_DEPTH_TEST);

        Logger.debug(Logger.System.WINDOW, "Mapping window frame buffer to physical display.");
        glfwShowWindow(handle);
    }

    public void swapBuffers() { 
        glfwSwapBuffers(handle); 
    }
    
    public boolean shouldClose() { 
        return glfwWindowShouldClose(handle); 
    }

    public void cleanup() {
        Logger.info(Logger.System.WINDOW, "Destroying graphics context and releasing native display allocations...");
        if (handle != NULL) {
            glfwDestroyWindow(handle);
        }
        glfwTerminate();
        
        glfwSetErrorCallback(null);
        Logger.info(Logger.System.WINDOW, "GLFW lifecycle terminated successfully.");
    }

    public long   getHandle() { return handle; }
    public int    getWidth()  { return width; }
    public int    getHeight() { return height; }
    public String getTitle()  { return title; }
}
