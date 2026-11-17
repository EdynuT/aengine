package com.aengine.core;

import com.aengine.graphics.HardwareCapabilities;
import com.aengine.utils.Logger;
import org.lwjgl.glfw.GLFWErrorCallback;
import org.lwjgl.glfw.GLFWVidMode;
import org.lwjgl.opengl.GL;

import static org.lwjgl.glfw.GLFW.*;
import static org.lwjgl.opengl.GL11.*;
import static org.lwjgl.system.MemoryUtil.NULL;

/**
 * The engine's single native window and its OpenGL context, through GLFW.
 *
 * <p>{@link #init()} opens the window maximised on the largest monitor, creates an OpenGL
 * 3.3 core context, makes it current on the calling thread, which from then on is the GL
 * thread, and turns on v-sync. The window is resizable; its size is tracked in
 * framebuffer pixels.</p>
 */
public class Window {

    private final String title;
    private int    width;
    private int    height;
    private long   handle;

    /**
     * Creates the window object. Nothing native is opened until {@link #init()}.
     *
     * @param title text for the window's title bar
     */
    public Window(String title) {
        this.title  = title;
    }

    /**
     * Initialises GLFW, opens the window, creates the OpenGL context and records the GPU's
     * capabilities. GLFW errors are sent to the log from here on.
     *
     * @throws IllegalStateException if GLFW cannot be initialised
     * @throws RuntimeException      if the window or its context cannot be created
     */
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
        // V-SYNC: 0 = no fps limit, 1 = locked to the monitor's refresh rate (current)
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

    /** Shows the frame just drawn. With v-sync on, waits for the monitor's next refresh. */
    public void swapBuffers() { 
        glfwSwapBuffers(handle); 
    }
    
    /**
     * Tells whether the user asked to close the window, e.g. with its close button.
     *
     * @return {@code true} once a close was requested
     */
    public boolean shouldClose() {
        return glfwWindowShouldClose(handle);
    }

    /**
     * The text on the system clipboard, or an empty string when it holds no text.
     *
     * <p>Allocates the {@code String} GLFW hands back, so it belongs on a paste, not in every
     * frame. Whatever is returned came from outside the engine: it is untrusted text, to be
     * filtered by whoever takes it in.</p>
     *
     * @return the clipboard text, never {@code null}
     */
    public String clipboard() {
        String text = glfwGetClipboardString(handle);
        return text != null ? text : "";
    }

    /**
     * Puts text on the system clipboard — what copy and cut do.
     *
     * @param text the text to copy
     */
    public void setClipboard(CharSequence text) {
        glfwSetClipboardString(handle, text);
    }

    /**
     * Destroys the window and shuts GLFW down. Call last, after everything that uses the
     * OpenGL context has been released.
     */
    public void cleanup() {
        Logger.info(Logger.System.WINDOW, "Destroying graphics context and releasing native display allocations...");
        if (handle != NULL) {
            glfwDestroyWindow(handle);
        }
        glfwTerminate();
        glfwSetErrorCallback(null);
        Logger.info(Logger.System.WINDOW, "GLFW lifecycle terminated successfully.");
    }

    /**
     * Returns the native GLFW handle, for APIs that take one.
     *
     * @return the window handle; 0 before {@link #init()}
     */
    public long   getHandle() { return handle; }

    /**
     * Returns the width of the drawable area.
     *
     * @return width in framebuffer pixels, updated on every resize
     */
    public int    getWidth()  { return width; }

    /**
     * Returns the height of the drawable area.
     *
     * @return height in framebuffer pixels, updated on every resize
     */
    public int    getHeight() { return height; }

    /**
     * Returns the title the window was created with.
     *
     * @return the title
     */
    public String getTitle()  { return title; }
}
