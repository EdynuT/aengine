package com.aengine.ecs;

import com.aengine.utils.Logger;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * The ECS world: hands out entity IDs and keeps one {@link ComponentPool} per component type.
 *
 * <p>An entity is only an {@code int}. It has components when some pool holds an instance
 * for that ID, and the pool for a type is created the first time a component of that type
 * is added. Destroyed IDs are recycled by later {@link #createEntity()} calls.</p>
 *
 * <p>Components are keyed by their exact runtime class ({@code component.getClass()}):
 * an instance of a subclass lands in the subclass's pool, not the parent's.</p>
 *
 * <p>Not thread-safe. The physics thread and the render thread share one registry and
 * serialise access through {@link com.aengine.physics.PhysicsThread#getSyncLock()}.</p>
 */
public final class Registry {

    private int entityCounter = 0;
    private final List<Integer> freeEntities = new ArrayList<>();
    private final Map<Class<?>, ComponentPool<?>> componentPools = new HashMap<>();
    private final List<Integer> activeEntities = new ArrayList<>(); 

    /**
     * A growable list of entity IDs backed by a raw {@code int[]}.
     *
     * <p>FastEntityView completely eliminates Java heap allocations ({@code new ArrayList<Integer>})
     * during the game loop. It uses a raw primitive {@code int[]} array to prevent Integer boxing,
     * maintaining a contiguous memory block for systems to iterate over.</p>
     *
     * <p>The view returned by {@link Registry#getEntitiesWith(Class...)} is one shared
     * instance that is cleared and refilled by every query; see that method for the rules.</p>
     */
    public static final class FastEntityView {
        /** Backing array; only the first {@link #size} entries are valid. */
        public int[] data = new int[128];
        /** Number of valid entries in {@link #data}. */
        public int size = 0;

        /** Creates an empty view with room for 128 entities before it grows. */
        public FastEntityView() {}

        /**
         * Appends an entity ID, doubling the backing array when it is full.
         *
         * @param entity the entity ID to append
         */
        public void add(int entity) {
            if (size == data.length) {
                // Resize linearly to preserve memory locality
                data = java.util.Arrays.copyOf(data, data.length * 2);
            }
            data[size++] = entity;
        }
        /**
         * Returns the entity ID at a position in the view.
         *
         * @param index position from {@code 0} to {@code size() - 1}; not bounds-checked
         *              against {@code size}, so a larger index returns stale data
         * @return the entity ID stored there
         */
        public int get(int index) { return data[index]; }

        /**
         * Returns how many entity IDs the view holds.
         *
         * @return the number of valid entries
         */
        public int size() { return size; }

        /** Empties the view without releasing its backing array. */
        public void clear() { size = 0; }
    }

    // Pre-allocated reusable buffer for component queries. Prevents GC spikes.
    private final FastEntityView viewBuffer = new FastEntityView();

    /** Creates an empty world with no entities and no component pools. */
    public Registry() {}

    /**
     * Creates a new entity with no components. The most recently destroyed ID is reused
     * first; otherwise the next never-used ID is handed out.
     *
     * @return the new entity ID
     */
    public int createEntity() {
        int id;
        if (!freeEntities.isEmpty()) {
            id = freeEntities.remove(freeEntities.size() - 1);
            Logger.debug(Logger.System.CORE, "Recycled Entity ID allocation token: %d", id);
        } else {
            id = entityCounter++;
            Logger.debug(Logger.System.CORE, "Allocated absolute Entity ID sequence index: %d", id);
        }
        activeEntities.add(id);
        return id;
    }

    /**
     * Destroys an entity: removes its components from every pool and queues its ID for reuse.
     *
     * <p>The call is not guarded against repeats. Destroying the same ID twice queues it
     * twice, and two later {@link #createEntity()} calls would then return the same ID.</p>
     *
     * @param entity the entity to destroy
     */
    public void destroyEntity(int entity) {
        Logger.debug(Logger.System.CORE, "Initiating global teardown sequence for Entity ID: %d", entity);
        freeEntities.add(entity);
        activeEntities.remove(Integer.valueOf(entity));
        
        for (Map.Entry<Class<?>, ComponentPool<?>> entry : componentPools.entrySet()) {
            entry.getValue().remove(entity);
        }
    }

    /**
     * L1/L2 CACHE LOCALITY OPTIMIZED VIEW MATCHER.
     * Iterates strictly over the smallest contiguous dense array in memory,
     * dropping O(N) global entity iteration in favor of O(K) subset linear iteration.
     *
     * <p>The returned view is a single buffer owned by the registry and reused by every call.
     * Read it completely before querying again: the next {@code getEntitiesWith} call clears
     * and refills it, even when the two calls ask for different types. Copy the IDs out if
     * they have to outlive the next query. Adding or removing components while iterating
     * does not update the view.</p>
     *
     * @param componentTypes the component classes an entity must have, all of them;
     *                       with no arguments the result is empty
     * @return the shared view of matching entity IDs, in no guaranteed order
     */
    public FastEntityView getEntitiesWith(Class<?>... componentTypes) {
        viewBuffer.clear();
        
        if (componentTypes.length == 0) return viewBuffer;

        // 1. Find the smallest pool to drive the iteration (Hardware Prefetching Anchor)
        ComponentPool<?> smallestPool = null;
        int minSize = Integer.MAX_VALUE;

        for (Class<?> type : componentTypes) {
            ComponentPool<?> pool = componentPools.get(type);
            // If any requested component doesn't exist or is empty, the intersection is absolutely zero.
            if (pool == null || pool.size() == 0) return viewBuffer; 
            
            if (pool.size() < minSize) {
                minSize = pool.size();
                smallestPool = pool;
            }
        }

        // 2. Linear iteration over contiguous dense memory array.
        // The CPU L1 Cache will aggressively prefetch 'denseEntities' because it is accessed sequentially.
        int[] denseEntities = smallestPool.getRawDenseToEntity();
        
        for (int i = 0; i < minSize; i++) {
            int entity = denseEntities[i];
            boolean match = true;
            
            for (Class<?> type : componentTypes) {
                ComponentPool<?> poolToVerify = componentPools.get(type);
                if (poolToVerify != smallestPool) {
                    // O(1) jump into the Sparse Array to verify intersection
                    if (!poolToVerify.has(entity)) {
                        match = false;
                        break;
                    }
                }
            }
            
            if (match) {
                viewBuffer.add(entity);
            }
        }
        
        return viewBuffer;
    }

    /**
     * Attaches a component to an entity, creating the pool for its class if needed.
     * If the entity already has a component of the same class, it is replaced.
     *
     * @param <T>       the component type
     * @param entity    the entity to attach to
     * @param component the component instance; its exact runtime class chooses the pool
     */
    @SuppressWarnings("unchecked")
    public <T> void addComponent(int entity, T component) {
        Class<?> type = component.getClass();
        ComponentPool<T> pool = (ComponentPool<T>) componentPools.computeIfAbsent(type, k -> {
            Logger.info(Logger.System.CORE, "Allocating cold infrastructure ComponentPool for type: %s", type.getSimpleName());
            return new ComponentPool<>(type);
        });
        pool.put(entity, component);
    }

    /**
     * Detaches a component from an entity. Does nothing if the entity has none of that type.
     *
     * @param <T>           the component type
     * @param entity        the entity to detach from
     * @param componentType the class of the component to remove
     */
    @SuppressWarnings("unchecked")
    public <T> void removeComponent(int entity, Class<T> componentType) {
        ComponentPool<T> pool = (ComponentPool<T>) componentPools.get(componentType);
        if (pool != null) {
            pool.remove(entity);
        }
    }

    /**
     * Returns an entity's component of a given type. The instance is live: changing its
     * fields changes the entity.
     *
     * @param <T>           the component type
     * @param entity        the entity to look up
     * @param componentType the class of the wanted component
     * @return the component, or {@code null} if the entity does not have one
     */
    @SuppressWarnings("unchecked")
    public <T> T getComponent(int entity, Class<T> componentType) {
        ComponentPool<T> pool = (ComponentPool<T>) componentPools.get(componentType);
        if (pool == null) return null;
        return pool.get(entity);
    }

    /**
     * Returns the pool that stores a component type, for systems that scan the raw
     * dense arrays instead of going through {@link #getEntitiesWith(Class...)}.
     *
     * @param <T>           the component type
     * @param componentType the component class
     * @return the pool, or {@code null} if no component of that type was ever added
     */
    @SuppressWarnings("unchecked")
    public <T> ComponentPool<T> getPool(Class<T> componentType) {
        return (ComponentPool<T>) componentPools.get(componentType);
    }
    
    /**
     * Checks if a component exists without allocating extra memory or throwing NPEs.
     *
     * @param entity        the entity to look up
     * @param componentType the component class
     * @return {@code true} if the entity has a component of that type
     */
    public boolean hasComponent(int entity, Class<?> componentType) {
        ComponentPool<?> pool = componentPools.get(componentType);
        return pool != null && pool.has(entity);
    }

    /**
     * Purges all dynamic entities from the world, preserving infrastructure (like the Editor Camera).
     *
     * <p>The rule is by component, not by name: every entity that has a
     * {@link com.aengine.ecs.components.CameraComponent} survives, including cameras
     * that came from the scene itself.</p>
     */
    public void clearScene() {
        Logger.info(Logger.System.CORE, "Teardown of dynamic scene entities...");
        List<Integer> toDestroy = new ArrayList<>();
        
        for (int i = 0; i < activeEntities.size(); i++) {
            int entity = activeEntities.get(i);
            // Preserve the editor camera
            if (!hasComponent(entity, com.aengine.ecs.components.CameraComponent.class)) {
                toDestroy.add(entity);
            }
        }
        
        for (int entity : toDestroy) {
            destroyEntity(entity);
        }
    }

    /**
     * Returns the total amount of instantiated entities currently active in the ECS.
     *
     * @return the number of live entities
     */
    public int getEntityCount() {
        return activeEntities.size();
    }
}
