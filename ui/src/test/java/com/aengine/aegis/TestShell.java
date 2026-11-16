package com.aengine.aegis;

import com.aengine.core.Keys;

/**
 * What a widget test stands on: an Aegis with no window, the font the editor uses, and the
 * frame loop's order of calls in one method — solve, pointer, widgets — so a test reads as
 * what the user did.
 */
final class TestShell {

    final Aegis        aegis  = Aegis.headless(4096);
    final AegisLayout  layout = aegis.layout();
    final AegisTree    tree   = aegis.tree();
    final AegisWidgets ae     = aegis.widgets();

    /** The root every test builds under: a column filling an 800 by 600 window. */
    final int root;

    private float   pointerX = -1.0f, pointerY = -1.0f;
    private boolean buttonDown;

    /** The widgets' clock: it stands still until a test moves it with {@link #waitMillis}. */
    private long nanos = 1_000_000_000L;

    TestShell() {
        aegis.loadFont("/fonts/DejaVuSans/DejaVuSans.ttf", 18.0f, 512, 128);
        root = layout.column(AegisLayout.NONE);
        ae.clock = () -> nanos;
    }

    /** Time passing, with no frame: what a delay is measured against. */
    void waitMillis(long millis) { nanos += millis * 1_000_000L; }

    /** One frame: solve, hand the pointer in, apply the keys handed in since the last. */
    void frame() {
        layout.solve(root, 0.0f, 0.0f, 800.0f, 600.0f);
        tree.update(root, pointerX, pointerY, buttonDown);
        ae.update(root);
        layout.solve(root, 0.0f, 0.0f, 800.0f, 600.0f);
    }

    /** Records a frame's drawing into the draw list, as the editor does; nothing is presented. */
    void draw() {
        aegis.begin(800, 600);
        ae.draw(root);
        aegis.end();
    }

    void pointerAt(float x, float y) { pointerX = x; pointerY = y; }

    /** Puts the pointer on the middle of a node, as last solved. */
    void pointerOn(int node) {
        pointerAt(layout.x(node) + layout.width(node) * 0.5f, layout.y(node) + layout.height(node) * 0.5f);
    }

    void press()   { buttonDown = true; }
    void release() { buttonDown = false; }

    /** A whole click on a node: move there, press, release, each its own frame. */
    void click(int node) {
        frame();
        pointerOn(node);
        frame();
        press();
        frame();
        release();
        frame();
    }

    /** A key pressed, then the frame that applies it. */
    void key(int key) { key(key, 0); }

    void key(int key, int mods) {
        ae.key(key, mods, false);
        frame();
    }

    void tab() { key(Keys.TAB); }

    void type(String text) {
        for (int i = 0; i < text.length(); i++) ae.character(text.charAt(i));
        frame();
    }
}
