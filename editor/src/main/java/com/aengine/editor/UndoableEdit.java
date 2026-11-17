package com.aengine.editor;

/**
 * One change the user made, which can be taken back and made again — Swing's
 * {@code UndoableEdit}. Made once the change is done, holding what it was before and after,
 * and handed to {@link UndoHistory#record}.
 *
 * <p>An edit sets things back to a value it holds, never by doing the change in reverse: an
 * entity moved is put back where it was, not moved back by the same amount, so undoing twice
 * in a row from different places cannot drift.</p>
 */
public interface UndoableEdit {

    /** What it is, in a word or two, for the log — "Move entity". */
    String name();

    /** Puts things back as they were before the change. */
    void undo();

    /** Makes the change again, after {@link #undo}. */
    void redo();

    /**
     * Takes the next edit into this one, when the two are one change to the user — a value
     * dragged or stepped again and again, as Swing's {@code addEdit}. Taken in, the next one
     * leaves this one's "before" as it is and gives it its "after".
     *
     * @param next the edit recorded right after this one
     * @return {@code true} if taken in; {@code false} to record it on its own, the default
     */
    default boolean absorb(UndoableEdit next) { return false; }
}
