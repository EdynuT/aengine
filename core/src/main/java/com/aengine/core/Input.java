package com.aengine.core;

import static org.lwjgl.glfw.GLFW.*;

/**
 * Keyboard and mouse input, in two forms side by side.
 *
 * <p><strong>Polling</strong> — {@link #isKeyPressed}, {@link #getMouseX} and friends — answers
 * "what is held right now". It is what gameplay and the fly camera want, and it is unchanged.</p>
 *
 * <p><strong>The event queue</strong> answers "what happened since last frame, in order": a
 * character typed, a key going down, repeating or coming up, a mouse button pressed or
 * released, a wheel turned. An interface needs this, because polling cannot see a key pressed
 * and released between two frames, cannot tell a repeat from a hold, and knows nothing of
 * characters — 'ç' typed as a dead key and a letter is one character but two key presses.</p>
 *
 * <p>The queue is filled by GLFW's callbacks while {@link #poll()} runs, and emptied at the start
 * of the next {@link #poll()}, so during a frame it holds exactly that frame's events. Read it
 * with an index loop from 0 to {@link #eventCount()}. It is a set of parallel arrays sized once,
 * so filling and reading it allocates nothing; if a frame ever brings more than
 * {@value #EVENT_CAPACITY} events, the extra ones are dropped and counted in
 * {@link #droppedEvents()} rather than growing anything.</p>
 */
public class Input {

    /** What an entry in the event queue records. */
    public enum EventType {
        /** A key went down. {@link #eventCode} is the key, as in {@link Keys}. */
        KEY_PRESS,
        /** A key held down repeated, at the rate the operating system sets. */
        KEY_REPEAT,
        /** A key came up. */
        KEY_RELEASE,
        /** A character was typed. {@link #eventCode} is its Unicode code point. */
        CHAR,
        /** A mouse button went down. {@link #eventCode} is the button, as in {@link Keys}. */
        MOUSE_PRESS,
        /** A mouse button came up. */
        MOUSE_RELEASE,
        /** The wheel turned. {@link #eventScrollX} and {@link #eventScrollY} say how far. */
        SCROLL
    }

    /** Events one frame can hold. A frame at human typing speed brings a handful. */
    public static final int EVENT_CAPACITY = 256;

    private static final EventType[] eventType    = new EventType[EVENT_CAPACITY];
    private static final int[]       eventCode    = new int[EVENT_CAPACITY];
    private static final int[]       eventMods    = new int[EVENT_CAPACITY];
    private static final float[]     eventScrollX = new float[EVENT_CAPACITY];
    private static final float[]     eventScrollY = new float[EVENT_CAPACITY];
    private static int eventCount;
    private static int droppedEvents;

    // ── Key repeat of our own, on Wayland ────────────────────────────────────
    //
    // On X11 and Windows the operating system repeats a held key, and GLFW passes the repeats
    // on as they come. Wayland only tells a client the delay and rate and leaves the repeating
    // to it, so GLFW runs a timer — and in the GLFW bundled with LWJGL 3.3.4 that timer is not
    // serviced every poll: repeats pile up and arrive in bursts of twenty or more, seconds late.
    // So on Wayland GLFW's repeats are ignored and this class repeats the held key itself: after
    // REPEAT_DELAY_NS, one KEY_REPEAT every REPEAT_INTERVAL_NS, each with the character the key
    // typed when it went down. Elsewhere nothing changes.

    private static final long REPEAT_DELAY_NS    = 500_000_000L;   // before the first repeat
    private static final long REPEAT_INTERVAL_NS =  50_000_000L;   // 20 repeats a second

    private static boolean ownKeyRepeat;          // true on Wayland, decided once in init()
    private static int     repeatKey  = -1;       // the key being held, or -1
    private static int     repeatMods;
    private static int     repeatChar = -1;       // what it typed, or -1 if it typed nothing
    private static long    repeatNextNs;
    private static boolean awaitingChar;          // a press just happened; its character may follow
    private static boolean glfwRepeating;         // the key event just received was GLFW's repeat

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

        ownKeyRepeat = glfwGetPlatform() == GLFW_PLATFORM_WAYLAND;

        // Installed before Dear ImGui's, which chains onto them — see UILayer.

        glfwSetKeyCallback(windowHandle, (window, key, scancode, action, mods) -> {
            if (key >= 0 && key <= GLFW_KEY_LAST)
                keys[key] = (action != GLFW_RELEASE);

            // GLFW calls the key callback and then, if the key typed something, the character
            // callback — so this flag tells the character callback whose character it is.
            glfwRepeating = action == GLFW_REPEAT;

            if (ownKeyRepeat) {
                if (action == GLFW_REPEAT) return;   // late and bunched; poll() repeats instead
                if (action == GLFW_PRESS && !isModifier(key)) {
                    repeatKey    = key;
                    repeatMods   = mods;
                    repeatChar   = -1;
                    repeatNextNs = System.nanoTime() + REPEAT_DELAY_NS;
                    awaitingChar = true;
                } else if (action == GLFW_RELEASE && key == repeatKey) {
                    repeatKey = -1;
                }
            }

            EventType type = action == GLFW_PRESS  ? EventType.KEY_PRESS
                           : action == GLFW_REPEAT ? EventType.KEY_REPEAT
                           :                         EventType.KEY_RELEASE;
            pushEvent(type, key, mods, 0.0f, 0.0f);
        });

        glfwSetCharCallback(windowHandle, (window, codepoint) -> {
            if (ownKeyRepeat) {
                if (glfwRepeating) return;           // the character of a repeat we ignored
                if (awaitingChar) {
                    repeatChar   = codepoint;        // what holding this key will keep typing
                    awaitingChar = false;
                }
            }
            pushEvent(EventType.CHAR, codepoint, 0, 0.0f, 0.0f);
        });

        glfwSetMouseButtonCallback(windowHandle, (window, button, action, mods) -> {
            if (button >= 0 && button <= GLFW_MOUSE_BUTTON_LAST)
                mouseButtons[button] = (action != GLFW_RELEASE);

            pushEvent(action == GLFW_PRESS ? EventType.MOUSE_PRESS : EventType.MOUSE_RELEASE,
                      button, mods, 0.0f, 0.0f);
        });

        glfwSetScrollCallback(windowHandle, (window, xoffset, yoffset) ->
            pushEvent(EventType.SCROLL, 0, 0, (float) xoffset, (float) yoffset));

        glfwSetCursorPosCallback(windowHandle, (window, xpos, ypos) -> {
            mouseX = xpos;
            mouseY = ypos;
        });
    }

    /** Appends to this frame's queue, or counts the event as dropped if the queue is full. */
    private static void pushEvent(EventType type, int code, int mods, float scrollX, float scrollY) {
        if (eventCount == EVENT_CAPACITY) {
            droppedEvents++;
            return;
        }
        eventType[eventCount]    = type;
        eventCode[eventCount]    = code;
        eventMods[eventCount]    = mods;
        eventScrollX[eventCount] = scrollX;
        eventScrollY[eventCount] = scrollY;
        eventCount++;
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
     * Starts a frame's input: empties the event queue, then collects everything the operating
     * system has delivered since the last call. Call it once per frame, before anything reads
     * input. GLFW's callbacks run inside this call, on this thread, which is why the queue needs
     * no locking.
     */
    public static void poll() {
        eventCount = 0;
        glfwPollEvents();
        if (ownKeyRepeat) repeatHeldKey();
    }

    /**
     * Wayland only: repeats the held key when its time comes, as KEY_REPEAT plus the character
     * it typed. The next time is advanced by the interval rather than set from "now", so the
     * rate stays even whatever the frame rate; a long stall is capped rather than replayed as a
     * burst, which is the very thing this exists to prevent.
     */
    private static void repeatHeldKey() {
        awaitingChar = false;   // a character arriving after this frame is not the press's
        if (repeatKey == -1) return;

        long now = System.nanoTime();
        int emitted = 0;
        while (now >= repeatNextNs && emitted < 2) {
            pushEvent(EventType.KEY_REPEAT, repeatKey, repeatMods, 0.0f, 0.0f);
            if (repeatChar != -1) pushEvent(EventType.CHAR, repeatChar, 0, 0.0f, 0.0f);
            repeatNextNs += REPEAT_INTERVAL_NS;
            emitted++;
        }
        if (now >= repeatNextNs) repeatNextNs = now + REPEAT_INTERVAL_NS;   // stalled: resync
    }

    /** Shift, Control, Alt and Super do not repeat: holding one is a modifier, not typing. */
    private static boolean isModifier(int key) {
        return key == GLFW_KEY_LEFT_SHIFT   || key == GLFW_KEY_RIGHT_SHIFT
            || key == GLFW_KEY_LEFT_CONTROL || key == GLFW_KEY_RIGHT_CONTROL
            || key == GLFW_KEY_LEFT_ALT     || key == GLFW_KEY_RIGHT_ALT
            || key == GLFW_KEY_LEFT_SUPER   || key == GLFW_KEY_RIGHT_SUPER;
    }

    // ── Event queue ──────────────────────────────────────────────────────────

    /** How many events arrived this frame. Read them with indices 0 to this, exclusive. */
    public static int eventCount() { return eventCount; }

    /** What the event at {@code i} records. */
    public static EventType eventType(int i) { return eventType[i]; }

    /**
     * The event's key or mouse button (compare with {@link Keys}), or for {@link EventType#CHAR}
     * the Unicode code point of the character typed.
     */
    public static int eventCode(int i) { return eventCode[i]; }

    /**
     * Modifiers held when a key or mouse event happened, as bits: test with
     * {@code (eventMods(i) & Keys.MOD_SHIFT) != 0}. Always 0 for characters and scrolling.
     */
    public static int eventMods(int i) { return eventMods[i]; }

    /** How far the wheel turned sideways, for {@link EventType#SCROLL}. */
    public static float eventScrollX(int i) { return eventScrollX[i]; }

    /** How far the wheel turned, for {@link EventType#SCROLL}. Positive is away from the user. */
    public static float eventScrollY(int i) { return eventScrollY[i]; }

    /**
     * Events lost because a frame brought more than {@value #EVENT_CAPACITY}. Zero in normal use;
     * a number that climbs means the capacity is too small, not that input is broken.
     */
    public static int droppedEvents() { return droppedEvents; }

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
