package com.aengine.editor;

import java.util.HashMap;
import java.util.Map;

/**
 * Singleton editor state: tracks which entity is currently selected and provides
 * optional display names for entities (the ECS has no name component of its own).
 *
 * <p>All methods are main-thread only — never call from the physics thread.</p>
 */
public final class EditorState {

    /** -1 means "nothing selected". */
    private static int selectedEntity = -1;

    /** Optional display names registered by {@link EntityFactory} or the hierarchy panel. */
    private static final Map<Integer, String> entityNames = new HashMap<>();

    private EditorState() {}

    // =========================================================================
    // Selection
    // =========================================================================

    /**
     * Returns the entity the inspector is showing.
     *
     * @return its ID, or -1 when nothing is selected
     */
    public static int  getSelectedEntity() { return selectedEntity; }

    /**
     * Tells whether any entity is selected.
     *
     * @return {@code true} when an entity is selected
     */
    public static boolean hasSelection()   { return selectedEntity != -1; }

    /**
     * Selects an entity, replacing any previous selection. The ID is not checked.
     *
     * @param entityId the entity to select
     */
    public static void select(int entityId) { selectedEntity = entityId; }

    /** Clears the selection. */
    public static void deselect()           { selectedEntity = -1; }

    // =========================================================================
    // Name registry
    // =========================================================================

    /**
     * Returns the name shown for an entity in the editor.
     *
     * @param entityId the entity
     * @return the registered name, or {@code "Entity #<id>"} if none was registered
     */
    public static String getEntityName(int entityId) {
        return entityNames.getOrDefault(entityId, "Entity #" + entityId);
    }

    /**
     * Registers the name shown for an entity, replacing any earlier one.
     *
     * @param entityId the entity
     * @param name     the display name
     */
    public static void setEntityName(int entityId, String name) {
        entityNames.put(entityId, name);
    }

    /**
     * Forgets an entity's name. Not called by the editor today, so the name of a destroyed
     * entity stays registered until its recycled ID is named again.
     *
     * @param entityId the entity
     */
    public static void removeName(int entityId) {
        entityNames.remove(entityId);
    }
}
