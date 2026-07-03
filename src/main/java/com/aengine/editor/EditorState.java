package com.aengine.editor;

import java.util.HashMap;
import java.util.Map;

/**
 * Singleton editor state: tracks which entity is currently selected and provides
 * optional display names for entities that lack a NameComponent.
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

    public static int  getSelectedEntity() { return selectedEntity; }
    public static boolean hasSelection()   { return selectedEntity != -1; }

    public static void select(int entityId) { selectedEntity = entityId; }
    public static void deselect()           { selectedEntity = -1; }

    // =========================================================================
    // Name registry
    // =========================================================================

    public static String getEntityName(int entityId) {
        return entityNames.getOrDefault(entityId, "Entity #" + entityId);
    }

    public static void setEntityName(int entityId, String name) {
        entityNames.put(entityId, name);
    }

    public static void removeName(int entityId) {
        entityNames.remove(entityId);
    }
}
