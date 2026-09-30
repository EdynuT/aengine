package com.aengine.core;

import com.aengine.graphics.HardwareCapabilities;
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

    public Window(String title) {
        this.title  = title;
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

        // --- ADAPTIVE HARDWARE MULTI-MONITOR RESOLUTION INTERCEPTION ---
        long targetMonitor = glfwGetPrimaryMonitor();
        org.lwjgl.PointerBuffer monitors = glfwGetMonitors();
        
        if (monitors != null && monitors.hasRemaining()) {
            if (monitors.remaining() > 1) {
                Logger.debug(Logger.System.WINDOW, "Multi-monitor environment detected. Resolving primary canvas dynamically...");
                
                long highestResMonitor = targetMonitor;
                int maxCalculatedArea = 0;

                while (monitors.hasRemaining()) {
                    long monitorPtr = monitors.get();
                    GLFWVidMode mode = glfwGetVideoMode(monitorPtr);
                    
                    if (mode != null) {
                        int currentArea = mode.width() * mode.height();
                        if (currentArea > maxCalculatedArea) {
                            maxCalculatedArea = currentArea;
                            highestResMonitor = monitorPtr;
                        }
                    }
                }
                targetMonitor = highestResMonitor;
            }
        }

        if (targetMonitor != NULL) {
            GLFWVidMode vidMode = glfwGetVideoMode(targetMonitor);
            if (vidMode != null) {
                this.width = vidMode.width();
                this.height = vidMode.height();
                Logger.info(Logger.System.WINDOW, "Hardware display metrics finalized: %dx%d", this.width, this.height);
            }
        }

        glfwWindowHint(GLFW_MAXIMIZED, GLFW_TRUE);

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

        if (glfwGetPlatform() != GLFW_PLATFORM_WAYLAND) {
            GLFWVidMode vidMode = glfwGetVideoMode(targetMonitor);
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

        // Initialize and delegate telemetry resolution directly to the hardware ledger
        HardwareCapabilities.initialize();

        glClearColor(0.1f, 0.1f, 0.1f, 1.0f);
        glEnable(GL_DEPTH_TEST);

        Logger.debug(Logger.System.WINDOW, "Mapping window frame buffer to physical display.");
        glfwShowWindow(handle);
        org.lwjgl.glfw.GLFW.glfwMakeContextCurrent(handle);
        // DISABLE V-SYNC FOR UNLIMITED FPS RENDERING
        // 0 = No fps limit
        // 1 = Locked to monitor's refresh rate
        org.lwjgl.glfw.GLFW.glfwSwapInterval(1);
        org.lwjgl.glfw.GLFW.glfwShowWindow(handle);

        // The size asked for above is the monitor's, but a maximised window is smaller: the
        // taskbar and the title bar take their share. On Windows the window is born at that
        // smaller size, so the resize callback never fires and width/height would keep the
        // monitor's numbers - everything drawn would be squeezed to fit, while the mouse
        // reports real pixels. Asking for the framebuffer once it is shown settles it.
        int[] fbWidth = new int[1], fbHeight = new int[1];
        glfwGetFramebufferSize(handle, fbWidth, fbHeight);
        if (fbWidth[0] > 0 && fbHeight[0] > 0) {
            width  = fbWidth[0];
            height = fbHeight[0];
            glViewport(0, 0, width, height);
        }
        Logger.info(Logger.System.WINDOW, "Framebuffer: %dx%d", width, height);
    }

    public void swapBuffers() { 
        glfwSwapBuffers(handle); 
    }
    
    public boolean shouldClose() {
        return glfwWindowShouldClose(handle);
    }

    /**
     * The text on the system clipboard, or an empty string when it holds no text.
     *
     * <p>Allocates the {@code String} GLFW hands back, so it belongs on a paste, not in every
     * frame. Whatever is returned came from outside the engine: it is untrusted text, to be
     * filtered by whoever takes it in.</p>
     */
    public String clipboard() {
        String text = glfwGetClipboardString(handle);
        return text != null ? text : "";
    }

    /** Puts text on the system clipboard — what copy and cut do. */
    public void setClipboard(CharSequence text) {
        glfwSetClipboardString(handle, text);
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
