package com.aengine.core;

import static org.lwjgl.glfw.GLFW.*;

public class Input {

    private static final boolean[] keys         = new boolean[GLFW_KEY_LAST + 1];
    private static final boolean[] mouseButtons = new boolean[GLFW_MOUSE_BUTTON_LAST + 1];
    
    private static double mouseX, mouseY;
    private static double lastMouseX, lastMouseY;
    private static double mouseDeltaX, mouseDeltaY;
    private static boolean firstMouseInput = true;
    private static long activeWindowHandle;

    private Input() {}

    public static void init(long windowHandle) {
        activeWindowHandle = windowHandle;

        glfwSetKeyCallback(windowHandle, (window, key, scancode, action, mods) -> {
            if (key >= 0 && key <= GLFW_KEY_LAST)
                keys[key] = (action != GLFW_RELEASE);
        });

        glfwSetMouseButtonCallback(windowHandle, (window, button, action, mods) -> {
            if (button >= 0 && button <= GLFW_MOUSE_BUTTON_LAST)
                mouseButtons[button] = (action != GLFW_RELEASE);
        });

        glfwSetCursorPosCallback(windowHandle, (window, xpos, ypos) -> {
            mouseX = xpos;
            mouseY = ypos;
        });
    }

    /**
     * Updates frame-by-frame delta accumulation for smooth mouse look logic.
     * Must be called exactly once at the beginning of the engine frame updates sequence.
     */
    public static void update() {
        if (firstMouseInput) {
            lastMouseX = mouseX;
            lastMouseY = mouseY;
            firstMouseInput = false;
        }

        // Calculate offset difference between current frame and historical slice
        mouseDeltaX = mouseX - lastMouseX;
        mouseDeltaY = mouseY - lastMouseY;

        // Sync historical tracking pointers
        lastMouseX = mouseX;
        lastMouseY = mouseY;
    }

    /**
     * Grabs window event signals from the OS window manager pipeline.
     */
    public static void poll() { 
        glfwPollEvents(); 
    }

    /**
     * Caps mouse rendering context and binds cursor focus to the native window center.
     * Essential tool to avoid window edge collisions when managing 3D First-Person scenes.
     */
    public static void setCursorMode(boolean grabbed) {
        if (grabbed) {
            glfwSetInputMode(activeWindowHandle, GLFW_CURSOR, GLFW_CURSOR_DISABLED);
            firstMouseInput = true; // Prevents sudden rotation snaps on context lock
        } else {
            glfwSetInputMode(activeWindowHandle, GLFW_CURSOR, GLFW_CURSOR_NORMAL);
        }
    }

    public static double  getMouseX()      { return mouseX; }
    public static double  getMouseY()      { return mouseY; }
    public static double  getMouseDeltaX() { return mouseDeltaX; }
    public static double  getMouseDeltaY() { return mouseDeltaY; }

    public static boolean isKeyPressed(int keyCode) {
        return keyCode >= 0 && keyCode <= GLFW_KEY_LAST && keys[keyCode];
    }

    public static boolean isMouseButtonPressed(int button) {
        return button >= 0 && button <= GLFW_MOUSE_BUTTON_LAST && mouseButtons[button];
    }
}
