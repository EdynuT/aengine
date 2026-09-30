package com.aengine.core;

import static org.lwjgl.glfw.GLFW.*;

/**
 * Keyboard constants — wrap GLFW so game projects don't need to import LWJGL directly.
 * Use them with {@link Input#isKeyPressed(int)} and {@link Input#eventCode(int)}.
 *
 * <p>Keys are named after their position on a US keyboard, not after the character they
 * print: on a Brazilian ABNT2 keyboard, for example, {@link #SEMICOLON} is the key labelled
 * Ç. For text the user types, read {@link Input.EventType#CHAR} events instead.</p>
 *
 * <p>The mouse-button constants at the end are for {@link Input#isMouseButtonPressed(int)}
 * and mouse events, not for keyboard calls.</p>
 */
public final class Keys {

    private Keys() {}

    // ── Letters ──────────────────────────────────────────────────────────────
    /** The A key. */
    public static final int A = GLFW_KEY_A;
    /** The B key. */
    public static final int B = GLFW_KEY_B;
    /** The C key. */
    public static final int C = GLFW_KEY_C;
    /** The D key. */
    public static final int D = GLFW_KEY_D;
    /** The E key. */
    public static final int E = GLFW_KEY_E;
    /** The F key. */
    public static final int F = GLFW_KEY_F;
    /** The G key. */
    public static final int G = GLFW_KEY_G;
    /** The H key. */
    public static final int H = GLFW_KEY_H;
    /** The I key. */
    public static final int I = GLFW_KEY_I;
    /** The J key. */
    public static final int J = GLFW_KEY_J;
    /** The K key. */
    public static final int K = GLFW_KEY_K;
    /** The L key. */
    public static final int L = GLFW_KEY_L;
    /** The M key. */
    public static final int M = GLFW_KEY_M;
    /** The N key. */
    public static final int N = GLFW_KEY_N;
    /** The O key. */
    public static final int O = GLFW_KEY_O;
    /** The P key. */
    public static final int P = GLFW_KEY_P;
    /** The Q key. */
    public static final int Q = GLFW_KEY_Q;
    /** The R key. */
    public static final int R = GLFW_KEY_R;
    /** The S key. */
    public static final int S = GLFW_KEY_S;
    /** The T key. */
    public static final int T = GLFW_KEY_T;
    /** The U key. */
    public static final int U = GLFW_KEY_U;
    /** The V key. */
    public static final int V = GLFW_KEY_V;
    /** The W key. */
    public static final int W = GLFW_KEY_W;
    /** The X key. */
    public static final int X = GLFW_KEY_X;
    /** The Y key. */
    public static final int Y = GLFW_KEY_Y;
    /** The Z key. */
    public static final int Z = GLFW_KEY_Z;
    /** The / key. */
    public static final int SLASH = GLFW_KEY_SLASH;
    /** The backslash key. */
    public static final int BACKSLASH = GLFW_KEY_BACKSLASH;
    /** The ; key. */
    public static final int SEMICOLON = GLFW_KEY_SEMICOLON;
    /** The ' key. */
    public static final int APOSTROPHE = GLFW_KEY_APOSTROPHE;
    /** The , key. */
    public static final int COMMA = GLFW_KEY_COMMA;
    /** The . key. */
    public static final int PERIOD = GLFW_KEY_PERIOD;
    /** The - key. */
    public static final int MINUS = GLFW_KEY_MINUS;
    /** The = key. */
    public static final int EQUAL = GLFW_KEY_EQUAL;
    /** The [ key. */
    public static final int LEFT_BRACKET = GLFW_KEY_LEFT_BRACKET;
    /** The ] key. */
    public static final int RIGHT_BRACKET = GLFW_KEY_RIGHT_BRACKET;

    // ── Numbers ───────────────────────────────────────────────────────────────
    /** The 0 key on the main row (not the keypad). */
    public static final int NUM_0 = GLFW_KEY_0;
    /** The 1 key on the main row (not the keypad). */
    public static final int NUM_1 = GLFW_KEY_1;
    /** The 2 key on the main row (not the keypad). */
    public static final int NUM_2 = GLFW_KEY_2;
    /** The 3 key on the main row (not the keypad). */
    public static final int NUM_3 = GLFW_KEY_3;
    /** The 4 key on the main row (not the keypad). */
    public static final int NUM_4 = GLFW_KEY_4;
    /** The 5 key on the main row (not the keypad). */
    public static final int NUM_5 = GLFW_KEY_5;
    /** The 6 key on the main row (not the keypad). */
    public static final int NUM_6 = GLFW_KEY_6;
    /** The 7 key on the main row (not the keypad). */
    public static final int NUM_7 = GLFW_KEY_7;
    /** The 8 key on the main row (not the keypad). */
    public static final int NUM_8 = GLFW_KEY_8;
    /** The 9 key on the main row (not the keypad). */
    public static final int NUM_9 = GLFW_KEY_9;

    // ── Special ───────────────────────────────────────────────────────────────
    /** Escape. */
    public static final int ESCAPE       = GLFW_KEY_ESCAPE;
    /** Enter (the main one, not the keypad). */
    public static final int ENTER        = GLFW_KEY_ENTER;
    /** The space bar. */
    public static final int SPACE        = GLFW_KEY_SPACE;
    /** Backspace. */
    public static final int BACKSPACE    = GLFW_KEY_BACKSPACE;
    /** Tab. */
    public static final int TAB          = GLFW_KEY_TAB;
    /** Left Shift. */
    public static final int SHIFT_L      = GLFW_KEY_LEFT_SHIFT;
    /** Right Shift. */
    public static final int SHIFT_R      = GLFW_KEY_RIGHT_SHIFT;
    /** Left Control. */
    public static final int CTRL_L       = GLFW_KEY_LEFT_CONTROL;
    /** Right Control. */
    public static final int CTRL_R       = GLFW_KEY_RIGHT_CONTROL;
    /** Left Alt. */
    public static final int ALT_L        = GLFW_KEY_LEFT_ALT;
    /** Right Alt (AltGr on many layouts). */
    public static final int ALT_R        = GLFW_KEY_RIGHT_ALT;
    /** Caps Lock. */
    public static final int CAPS_LOCK    = GLFW_KEY_CAPS_LOCK;
    /** Delete. */
    public static final int DELETE       = GLFW_KEY_DELETE;
    /** Insert. */
    public static final int INSERT       = GLFW_KEY_INSERT;
    /** Print Screen. */
    public static final int PRINT_SCREEN = GLFW_KEY_PRINT_SCREEN;
    /** Scroll Lock. */
    public static final int SCROLL_LOCK  = GLFW_KEY_SCROLL_LOCK;
    /** Pause. */
    public static final int PAUSE        = GLFW_KEY_PAUSE;
    /** Num Lock. */
    public static final int NUM_LOCK     = GLFW_KEY_NUM_LOCK;
    /** Home. */
    public static final int HOME         = GLFW_KEY_HOME;
    /** End. */
    public static final int END          = GLFW_KEY_END;
    /** Page Up. */
    public static final int PAGE_UP      = GLFW_KEY_PAGE_UP;
    /** Page Down. */
    public static final int PAGE_DOWN    = GLFW_KEY_PAGE_DOWN;

    // ── Arrows ────────────────────────────────────────────────────────────────
    /** Up arrow. */
    public static final int UP    = GLFW_KEY_UP;
    /** Down arrow. */
    public static final int DOWN  = GLFW_KEY_DOWN;
    /** Left arrow. */
    public static final int LEFT  = GLFW_KEY_LEFT;
    /** Right arrow. */
    public static final int RIGHT = GLFW_KEY_RIGHT;

    // ── Function ──────────────────────────────────────────────────────────────
    /** Function key F1. */
    public static final int F1  = GLFW_KEY_F1;
    /** Function key F2. */
    public static final int F2  = GLFW_KEY_F2;
    /** Function key F3. */
    public static final int F3  = GLFW_KEY_F3;
    /** Function key F4. */
    public static final int F4  = GLFW_KEY_F4;
    /** Function key F5. */
    public static final int F5  = GLFW_KEY_F5;
    /** Function key F6. */
    public static final int F6  = GLFW_KEY_F6;
    /** Function key F7. */
    public static final int F7  = GLFW_KEY_F7;
    /** Function key F8. */
    public static final int F8  = GLFW_KEY_F8;
    /** Function key F9. */
    public static final int F9  = GLFW_KEY_F9;
    /** Function key F10. */
    public static final int F10 = GLFW_KEY_F10;
    /** Function key F11. */
    public static final int F11 = GLFW_KEY_F11;
    /** Function key F12. */
    public static final int F12 = GLFW_KEY_F12;

    // ── Modifier bits (used with Input.eventMods — test with &, e.g. (mods & MOD_SHIFT) != 0)
    /** Bit set in {@link Input#eventMods(int)} while either Shift is held. */
    public static final int MOD_SHIFT   = GLFW_MOD_SHIFT;
    /** Bit set in {@link Input#eventMods(int)} while either Control is held. */
    public static final int MOD_CONTROL = GLFW_MOD_CONTROL;
    /** Bit set in {@link Input#eventMods(int)} while either Alt is held. */
    public static final int MOD_ALT     = GLFW_MOD_ALT;

    // ── Mouse buttons (used with Input.isMouseButtonPressed) ─────────────────
    /** The left mouse button. */
    public static final int MOUSE_LEFT   = GLFW_MOUSE_BUTTON_LEFT;
    /** The right mouse button. */
    public static final int MOUSE_RIGHT  = GLFW_MOUSE_BUTTON_RIGHT;
    /** The middle mouse button (pressing the wheel). */
    public static final int MOUSE_MIDDLE = GLFW_MOUSE_BUTTON_MIDDLE;
}
