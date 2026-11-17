package com.aengine.editor;

/**
 * The editor's history of changes, for Edit &gt; Undo and Redo — Swing's {@code UndoManager}.
 * Any panel that changes the scene records an {@link UndoableEdit} here once the change is
 * done; undo walks back through them, and redo forward again until something new is recorded,
 * which drops what was undone.
 *
 * <p>It keeps the last {@link #LIMIT} changes, the oldest dropped first. Each undo and redo is
 * applied holding the lock it was made with — the one the physics thread takes before it
 * writes to the scene.</p>
 *
 * <p>A text field keeps its own history of what is typed in it (Aegis, §3g-4); this one is
 * for the scene.</p>
 */
public final class UndoHistory {

    /** How many changes are kept. */
    public static final int LIMIT = 200;

    private final Object lock;
    private final UndoableEdit[] edits = new UndoableEdit[LIMIT];
    private int count;     // recorded, the undone included
    private int applied;   // in effect; those after it can be redone
    private int changes;   // bumped by everything that changes what undo or redo would do

    /**
     * @param lock what every undo and redo is applied holding: the scene's lock
     */
    public UndoHistory(Object lock) {
        this.lock = lock;
    }

    /**
     * Records a change already made. What was undone can no longer be redone. The change is
     * taken into the last one recorded if that one {@linkplain UndoableEdit#absorb absorbs} it.
     *
     * @param edit the change
     */
    public void record(UndoableEdit edit) {
        count = applied;   // what was undone goes
        if (applied > 0 && edits[applied - 1].absorb(edit)) {
            changes++;
            return;
        }
        if (count == LIMIT) {   // full: the oldest goes
            System.arraycopy(edits, 1, edits, 0, LIMIT - 1);
            count--;
        }
        edits[count++] = edit;
        applied = count;
        for (int i = count; i < LIMIT && edits[i] != null; i++) edits[i] = null;
        changes++;
    }

    /** Takes back the last change in effect, if there is one. */
    public void undo() {
        if (!canUndo()) return;
        synchronized (lock) {
            edits[applied - 1].undo();
        }
        applied--;
        changes++;
    }

    /** Makes the last change undone again, if nothing has been recorded since. */
    public void redo() {
        if (!canRedo()) return;
        synchronized (lock) {
            edits[applied].redo();
        }
        applied++;
        changes++;
    }

    /** @return whether there is a change to take back */
    public boolean canUndo() { return applied > 0; }

    /** @return whether there is a change undone to make again */
    public boolean canRedo() { return applied < count; }

    /** @return the change {@link #undo} would take back, or {@code null} */
    public UndoableEdit nextUndo() { return canUndo() ? edits[applied - 1] : null; }

    /** @return the change {@link #redo} would make again, or {@code null} */
    public UndoableEdit nextRedo() { return canRedo() ? edits[applied] : null; }

    /**
     * Forgets every change — when the scene is replaced and what the edits name may no longer
     * be what they were made on.
     */
    public void clear() {
        java.util.Arrays.fill(edits, null);
        count = 0;
        applied = 0;
        changes++;
    }

    /**
     * A number that changes whenever what undo or redo would do changes, so the interface
     * looks again only then.
     *
     * @return the count of such changes
     */
    public int changes() { return changes; }
}
