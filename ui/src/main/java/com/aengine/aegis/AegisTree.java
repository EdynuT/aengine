package com.aengine.aegis;

import java.util.Arrays;

/**
 * What the pointer is doing to the layout — layer L4 of the design document, first part.
 *
 * <p>The layout (L3) knows where every node is. This knows which of them react: which one the
 * mouse is over, which one a button went down on, and which one was clicked. It adds state
 * to the layout's nodes rather than building a tree of its own — a node here is the same
 * {@code int} handle as in {@link AegisLayout}, so there is one tree, not two to keep in step.</p>
 *
 * <pre>{@code
 * AegisTree tree = aegis.tree();
 *
 * // once, while building the layout
 * tree.setInteractive(saveButton, true);
 *
 * // every frame, after the layout's solve
 * tree.update(root, mouseX, mouseY, leftButtonDown);
 * if (tree.wasClicked(saveButton)) save();
 * boolean lit = tree.isHovered(saveButton);
 * }</pre>
 *
 * <h2>Why it is shaped like this</h2>
 *
 * <p><strong>Input is handed in, not read.</strong> {@link #update} takes the mouse position
 * and button as arguments instead of asking the engine's input directly. Whoever calls it
 * decides what the interface is allowed to see: the editor, for one, must hide the mouse from
 * it while the pointer is over a Dear ImGui window, and only the caller knows that.</p>
 *
 * <p><strong>Only interactive nodes react.</strong> The layout's hit-test finds the innermost
 * node under the pointer, which may be a label inside a button or a spacer. This walks up from
 * there to the nearest node marked {@linkplain #setInteractive interactive}, so pressing the
 * label presses the button.</p>
 *
 * <p><strong>A click is a press and a release on the same node.</strong> The node a button
 * goes down on <em>captures</em> the pointer until it is released: while it holds it, no other
 * node is hovered, and it reads as hovered only while the pointer is actually over it.
 * Releasing over it is a click; releasing elsewhere is not, which is how a user backs out of a
 * press they did not mean. Press and release are found by comparing this frame's button with
 * the last frame's, which is enough for a mouse button. Typed characters and key repeat are not
 * — those come from the event queue in the engine's input (§10 of the design document), which
 * the widgets read directly.</p>
 *
 * <p><strong>Focus follows the tree.</strong> Nodes marked {@linkplain #setFocusable focusable}
 * take the keyboard's attention one at a time. {@link #focusNext} and {@link #focusPrevious}
 * — what Tab and Shift+Tab call — step through them in tree order: a node, then its children
 * in the order they were added, then its next sibling. That is reading order for any
 * interface built top to bottom and left to right, with no order to maintain by hand. A press
 * focuses the nearest focusable node under the pointer, and a press on anything else clears
 * focus, the way clicking away from a text field does. Focusable and interactive are separate
 * markings: a text field takes focus without being a button, and a button can be clicked
 * without taking focus.</p>
 *
 * <p>The pointer's state and the focus are four integers, not a flag per node: at most one
 * node is hovered, one pressed, one clicked and one focused at a time. Only the markings are
 * per node. It allocates nothing. Not thread-safe, and not meant to be.</p>
 */
public final class AegisTree {

    private final AegisLayout layout;

    /** Which nodes react to the pointer. Indexed by layout handle. */
    private final boolean[] interactive;

    /** Which nodes can take focus. Indexed by layout handle. */
    private final boolean[] focusable;

    private int hovered = AegisLayout.NONE;
    private int pressed = AegisLayout.NONE;   // holds the capture while the button is down
    private int clicked = AegisLayout.NONE;   // this frame only
    private int focused = AegisLayout.NONE;

    private boolean buttonWasDown;

    // The pointer as last handed to update(), for widgets that follow it — a slider's drag.
    private float pointerX;
    private float pointerY;

    /**
     * Creates the state for a layout, with nothing marked and nothing focused.
     *
     * @param layout   the layout whose nodes this adds state to
     * @param capacity the layout's capacity, so every handle it can give out has a slot here
     */
    public AegisTree(AegisLayout layout, int capacity) {
        this.layout      = layout;
        this.interactive = new boolean[capacity];
        this.focusable   = new boolean[capacity];
    }

    // -----------------------------------------------------------------------------------
    // Building
    // -----------------------------------------------------------------------------------

    /**
     * Whether a node reacts to the pointer. Off by default: panes, spacers and labels let the
     * pointer through to whatever interactive node contains them.
     *
     * @param node the layout handle
     * @param on   {@code true} to make it react
     */
    public void setInteractive(int node, boolean on) { interactive[node] = on; }

    /**
     * Whether a node can take focus — be reached with Tab, and be focused by a press. Off by
     * default. Independent of {@link #setInteractive}.
     *
     * @param node the layout handle
     * @param on   {@code true} to let it take focus
     */
    public void setFocusable(int node, boolean on) { focusable[node] = on; }

    /**
     * Forgets every node's settings, the pointer state and the focus.
     *
     * <p>Call it together with {@link AegisLayout#clear()}. Handles are reused after a layout is
     * cleared, and a reused handle must not inherit its previous owner's markings.</p>
     */
    public void clear() {
        Arrays.fill(interactive, false);
        Arrays.fill(focusable, false);
        hovered = AegisLayout.NONE;
        pressed = AegisLayout.NONE;
        clicked = AegisLayout.NONE;
        focused = AegisLayout.NONE;
    }

    // -----------------------------------------------------------------------------------
    // Every frame
    // -----------------------------------------------------------------------------------

    /**
     * Works out what the pointer is doing this frame.
     *
     * <p>Call it once a frame, after the layout's solve, so it tests against what is on screen.
     * To keep the interface from reacting — the pointer is over something else drawn on top —
     * pass a position outside the root, such as {@code -1, -1}.</p>
     *
     * @param root       the layout root to test against
     * @param mouseX     pointer position, in the same pixels the layout was solved in
     * @param mouseY     pointer position
     * @param buttonDown whether the primary button is held this frame
     */
    public void update(int root, float mouseX, float mouseY, boolean buttonDown) {
        pointerX = mouseX;
        pointerY = mouseY;
        int target = interactiveAt(root, mouseX, mouseY);

        boolean justPressed  =  buttonDown && !buttonWasDown;
        boolean justReleased = !buttonDown &&  buttonWasDown;
        buttonWasDown = buttonDown;

        clicked = AegisLayout.NONE;

        // The press lands on whatever is under the pointer — possibly nothing, in which case no
        // node holds the capture and nothing can be clicked until the button comes up again.
        // It also moves focus: to the focusable node pressed on, or away from everything.
        if (justPressed) {
            pressed = target;
            focused = markedAt(focusable, root, mouseX, mouseY);
        }

        if (justReleased) {
            if (pressed != AegisLayout.NONE && target == pressed) clicked = pressed;
            pressed = AegisLayout.NONE;
        }

        // While a node holds the capture, it is the only one that can be hovered, and only when
        // the pointer is over it; otherwise hover follows the pointer.
        if (pressed != AegisLayout.NONE) {
            hovered = (target == pressed) ? pressed : AegisLayout.NONE;
        } else {
            hovered = target;
        }
    }

    /**
     * Whether the pointer is over this node, and nothing else holds the capture.
     *
     * @param node the layout handle
     * @return {@code true} if it is the hovered node
     */
    public boolean isHovered(int node) { return hovered == node; }

    /**
     * Whether a press began on this node and the button is still down — true even while the
     * pointer has wandered off it. Draw it pressed when it is also {@linkplain #isHovered
     * hovered}, so the user can see that releasing here now would not click.
     *
     * @param node the layout handle
     * @return {@code true} if it holds the capture
     */
    public boolean isPressed(int node) { return pressed == node; }

    /**
     * Whether this node was clicked this frame. True for exactly one frame per click.
     *
     * @param node the layout handle
     * @return {@code true} in the frame the click completed
     */
    public boolean wasClicked(int node) { return clicked == node; }

    /**
     * The hovered node, or {@link AegisLayout#NONE}.
     *
     * @return the handle
     */
    public int hovered() { return hovered; }

    /**
     * The node clicked this frame, or {@link AegisLayout#NONE}.
     *
     * @return the handle
     */
    public int clicked() { return clicked; }

    /**
     * The node holding the pointer's capture — pressed and not yet released — or {@link AegisLayout#NONE}.
     *
     * @return the handle
     */
    public int pressed() { return pressed; }

    /**
     * The pointer's x as last handed to {@link #update} — what a drag follows.
     *
     * @return the x, in layout pixels
     */
    public float pointerX() { return pointerX; }

    /**
     * The pointer's y as last handed to {@link #update}.
     *
     * @return the y, in layout pixels
     */
    public float pointerY() { return pointerY; }

    // -----------------------------------------------------------------------------------
    // Focus
    // -----------------------------------------------------------------------------------

    /**
     * Whether this node has focus.
     *
     * @param node the layout handle
     * @return {@code true} if it is the focused node
     */
    public boolean isFocused(int node) { return focused == node; }

    /**
     * The focused node, or {@link AegisLayout#NONE}.
     *
     * @return the handle
     */
    public int focused() { return focused; }

    /**
     * Gives focus to a node, or with {@link AegisLayout#NONE} takes it from everything — what
     * Esc does in a text field. The node is not checked for being focusable: the caller is
     * code, not the user, and is trusted to name one.
     *
     * @param node the layout handle, or {@link AegisLayout#NONE}
     */
    public void focus(int node) { focused = node; }

    /**
     * Moves focus to the next focusable node under {@code root}, in tree order — what Tab
     * does. Past the last it wraps to the first; with nothing focused it starts at the first.
     * If no node under {@code root} is focusable, focus is left as it is.
     *
     * @param root the tree to step through; focus held outside it counts as none
     */
    public void focusNext(int root) {
        int start = isUnder(root, focused) ? focused : root;
        int node  = start;
        do {
            node = nextInOrder(root, node);
            if (focusable[node]) {
                focused = node;
                return;
            }
        } while (node != start);
    }

    /**
     * Moves focus to the previous focusable node under {@code root}, in tree order — what
     * Shift+Tab does. Before the first it wraps to the last; with nothing focused it starts at
     * the last. If no node under {@code root} is focusable, focus is left as it is.
     *
     * @param root the tree to step through; focus held outside it counts as none
     */
    public void focusPrevious(int root) {
        int start = isUnder(root, focused) ? focused : root;
        int node  = start;
        do {
            node = previousInOrder(root, node);
            if (focusable[node]) {
                focused = node;
                return;
            }
        } while (node != start);
    }

    // -----------------------------------------------------------------------------------
    // Internals
    // -----------------------------------------------------------------------------------

    /** The innermost node under the point, walked up to the nearest interactive one. */
    private int interactiveAt(int root, float x, float y) {
        return markedAt(interactive, root, x, y);
    }

    /** The innermost node under the point, walked up to the nearest one set in {@code marks}. */
    private int markedAt(boolean[] marks, int root, float x, float y) {
        int node = layout.nodeAt(root, x, y);
        while (node != AegisLayout.NONE && !marks[node]) {
            node = layout.parent(node);
        }
        return node;
    }

    /**
     * The node after this one in tree order, wrapping from the last back to {@code root}.
     *
     * <p>Tree order is: a node, then its children, then its next sibling. So the next node is
     * the first child if there is one; otherwise the next sibling of the nearest ancestor —
     * this node included — that has one. Walking the layout's own links, with no stack and no
     * allocation.</p>
     */
    private int nextInOrder(int root, int node) {
        int child = layout.firstChild(node);
        if (child != AegisLayout.NONE) return child;

        while (node != root) {
            int sibling = layout.nextSibling(node);
            if (sibling != AegisLayout.NONE) return sibling;
            node = layout.parent(node);
        }
        return root;   // past the last node: wrap
    }

    /**
     * The node before this one in tree order, wrapping from {@code root} to the last node.
     *
     * <p>The mirror of {@link #nextInOrder}: the previous node is the deepest last descendant
     * of the previous sibling, or the parent if there is no previous sibling.</p>
     */
    private int previousInOrder(int root, int node) {
        if (node == root) return deepestLast(root);   // before the first: wrap

        int parent = layout.parent(node);
        int before = AegisLayout.NONE;
        for (int c = layout.firstChild(parent); c != node; c = layout.nextSibling(c)) {
            before = c;
        }
        return before == AegisLayout.NONE ? parent : deepestLast(before);
    }

    /**
     * Whether a node is {@code root} or inside it. Stepping through tree order climbs parents
     * until it meets the root, so starting from a node in some other tree would climb past the
     * top instead; focus in another tree is treated as no focus here.
     */
    private boolean isUnder(int root, int node) {
        while (node != AegisLayout.NONE) {
            if (node == root) return true;
            node = layout.parent(node);
        }
        return false;
    }

    /** Follows last children down as far as they go: the final node of a subtree in tree order. */
    private int deepestLast(int node) {
        int last;
        while ((last = layout.lastChild(node)) != AegisLayout.NONE) node = last;
        return node;
    }
}
