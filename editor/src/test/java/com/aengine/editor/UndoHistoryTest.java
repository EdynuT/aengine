package com.aengine.editor;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Step 4g: the scene's undo history. */
class UndoHistoryTest {

    /** A number the edits set: the scene, as far as these tests go. */
    private final int[] value = { 0 };
    private final UndoHistory history = new UndoHistory(new Object());

    /** Sets the value from one number to another, as the user did; absorbs a later Set when it chains on. */
    private final class Set implements UndoableEdit {
        final int before;
        int after;
        final boolean merges;

        Set(int to, boolean merges) {
            this.before = value[0];
            this.after = to;
            this.merges = merges;
            value[0] = to;
        }

        @Override public String name() { return "Set"; }
        @Override public void undo() { value[0] = before; }
        @Override public void redo() { value[0] = after; }

        @Override public boolean absorb(UndoableEdit next) {
            if (!merges || !(next instanceof Set s) || !s.merges || s.before != after) return false;
            after = s.after;
            return true;
        }
    }

    private void set(int to) { history.record(new Set(to, false)); }

    @Test
    void undoGoesBackAndRedoForwardAgain() {
        set(1);
        set(2);
        history.undo();
        assertEquals(1, value[0]);
        history.undo();
        assertEquals(0, value[0]);
        assertFalse(history.canUndo());
        history.undo();
        assertEquals(0, value[0], "nothing left: nothing happens");

        history.redo();
        history.redo();
        assertEquals(2, value[0]);
        assertFalse(history.canRedo());
    }

    @Test
    void somethingNewDropsWhatWasUndone() {
        set(1);
        set(2);
        history.undo();
        set(5);
        assertFalse(history.canRedo());
        history.undo();
        assertEquals(1, value[0]);
    }

    @Test
    void anEditThatChainsOnIsTakenIntoTheLast() {
        history.record(new Set(1, true));
        history.record(new Set(2, true));
        history.record(new Set(3, true));
        history.undo();
        assertEquals(0, value[0], "one change, back to before the first");
        assertFalse(history.canUndo());
        history.redo();
        assertEquals(3, value[0]);
    }

    @Test
    void theOldestGoesWhenFull() {
        for (int i = 1; i <= UndoHistory.LIMIT + 5; i++) set(i);
        int undone = 0;
        while (history.canUndo()) { history.undo(); undone++; }
        assertEquals(UndoHistory.LIMIT, undone);
        assertEquals(5, value[0], "the first five are kept no more");
    }

    @Test
    void clearForgetsEverything() {
        set(1);
        set(2);
        history.undo();
        history.clear();
        assertFalse(history.canUndo());
        assertFalse(history.canRedo());
        assertNull(history.nextUndo());
    }

    @Test
    void changesMovesWhenWhatUndoWouldDoChanges() {
        int before = history.changes();
        set(1);
        assertTrue(history.changes() != before);
        int after = history.changes();
        history.redo();
        assertEquals(after, history.changes(), "nothing to redo: no change");
        history.undo();
        assertTrue(history.changes() != after);
    }

    @Test
    void theNextToUndoAndRedoAreShown() {
        set(1);
        UndoableEdit second = new Set(2, false);
        history.record(second);
        assertSame(second, history.nextUndo());
        history.undo();
        assertSame(second, history.nextRedo());
    }

    @Test
    void undoIsAppliedHoldingTheLock() {
        Object lock = new Object();
        UndoHistory locked = new UndoHistory(lock);
        boolean[] held = { false };
        locked.record(new UndoableEdit() {
            @Override public String name() { return "Check"; }
            @Override public void undo() { held[0] = Thread.holdsLock(lock); }
            @Override public void redo() { }
        });
        locked.undo();
        assertTrue(held[0]);
    }
}
