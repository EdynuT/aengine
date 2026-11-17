package com.aengine.editor;

import com.aengine.ecs.Registry;
import com.aengine.ecs.components.TransformComponent;
import org.joml.Vector3f;

/** An entity moved — dragged in the viewport: put back where it was, or where it was taken. */
public final class MoveEntityEdit implements UndoableEdit {

    private final Registry registry;
    private final int entity;
    private final Vector3f before;
    private final Vector3f after;

    /**
     * @param registry the scene
     * @param entity   the entity moved
     * @param before   where it was; copied
     * @param after    where it is now; copied
     */
    public MoveEntityEdit(Registry registry, int entity, Vector3f before, Vector3f after) {
        this.registry = registry;
        this.entity = entity;
        this.before = new Vector3f(before);
        this.after = new Vector3f(after);
    }

    @Override public String name() { return "Move entity"; }

    @Override public void undo() { moveTo(before); }

    @Override public void redo() { moveTo(after); }

    private void moveTo(Vector3f where) {
        TransformComponent t = registry.getComponent(entity, TransformComponent.class);
        if (t != null) t.position.set(where);
    }
}
