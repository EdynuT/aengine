package com.aengine.ecs;

import com.aengine.utils.Logger;
import java.lang.reflect.Array;
import java.util.Arrays;

/**
 * Storage for every component of one type, laid out as a sparse set.
 *
 * <p>In standard Object-Oriented Programming (OOP), entities store lists of components.
 * This causes CPU cache misses because the hardware has to follow pointers scattered
 * randomly across the Java heap, stalling the CPU while it waits for RAM fetches.
 * Data-Oriented Design (DOD) solves this using a sparse set made of three arrays:</p>
 * <ol>
 *   <li>{@code denseComponents}: a strictly packed, contiguous array of component data.
 *       When the CPU requests index [0], the hardware L1 cache pre-fetches [1], [2] and [3]
 *       in the same 64-byte cache line, so linear iteration is very fast.</li>
 *   <li>{@code entityToDense} (sparse array): maps entity ID to dense array index in O(1).</li>
 *   <li>{@code denseToEntity} (dense array): tells a system which entity owns the
 *       component at a given dense index during linear iteration.</li>
 * </ol>
 *
 * <p>Put, remove, get and has are all O(1). Removal swaps the last element into the hole,
 * so the dense order is <em>not</em> stable: never keep a dense index across a removal.</p>
 *
 * <p>Pools are normally created and owned by the {@link Registry}; systems reach one
 * through {@link Registry#getPool(Class)} when they want to scan the raw arrays directly.
 * Not thread-safe.</p>
 *
 * @param <T> the component type stored in this pool
 */
public final class ComponentPool<T> {

    private final Class<?> componentType;
    private T[] denseComponents;
    private int[] denseToEntity;
    private int[] entityToDense;
    private int size = 0;

    /**
     * Creates an empty pool with room for 128 components and entity IDs up to 1023.
     * Both limits grow on demand.
     *
     * @param type the component class, used to allocate the typed dense array and in log lines
     */
    @SuppressWarnings("unchecked")
    public ComponentPool(Class<?> type) {
        this.componentType = type;
        this.denseComponents = (T[]) Array.newInstance(type, 128); // Smaller cold initial footprint
        this.denseToEntity = new int[128];
        this.entityToDense = new int[1024]; // Sparse lookup array starts reasonably sized
        Arrays.fill(entityToDense, -1);
    }

    /**
     * Maps an entity ID to a packed dense array index slot in O(1).
     * If the entity already has a component in this pool, it is replaced in place
     * and keeps its dense slot.
     *
     * @param entity    the owning entity ID; must be zero or positive
     * @param component the component instance to store
     */
    public void put(int entity, T component) {
        ensureSparseCapacity(entity);

        int existingIndex = entityToDense[entity];
        if (existingIndex != -1) {
            denseComponents[existingIndex] = component;
            return;
        }

        ensureDenseCapacity();

        int index = size;
        denseComponents[index] = component;
        denseToEntity[index] = entity;
        entityToDense[entity] = index;
        size++;
        
        Logger.trace(Logger.System.CORE, "Packed component type [%s] at dense storage slot: %d for Entity: %d", componentType.getSimpleName(), index, entity);
    }

    /**
     * Unmaps an entity and swaps the last element of the dense array to the deleted slot
     * to preserve absolute contiguous sequence lines (Unordered Fast-Delete Pattern).
     * Does nothing if the entity has no component in this pool.
     *
     * @param entity the entity whose component should be removed
     */
    public void remove(int entity) {
        if (entity >= entityToDense.length || entityToDense[entity] == -1) {
            return;
        }

        int indexToRemove = entityToDense[entity];
        int lastIndex = size - 1;

        T lastComponent = denseComponents[lastIndex];
        int lastEntity = denseToEntity[lastIndex];

        // Swap execution sequence
        denseComponents[indexToRemove] = lastComponent;
        denseToEntity[indexToRemove] = lastEntity;
        entityToDense[lastEntity] = indexToRemove;

        // Clean stale reference hooks for GC leverage
        denseComponents[lastIndex] = null;
        entityToDense[entity] = -1;
        size--;
        
        Logger.trace(Logger.System.CORE, "Swapped trailing dense index %d to index %d to evict component for Entity: %d", lastIndex, indexToRemove, entity);
    }

    /**
     * Returns the component owned by an entity.
     *
     * @param entity the entity ID to look up
     * @return the component, or {@code null} if the entity has none in this pool
     */
    public T get(int entity) {
        if (entity >= entityToDense.length || entityToDense[entity] == -1) {
            return null;
        }
        return denseComponents[entityToDense[entity]];
    }

    /**
     * Tells whether an entity has a component in this pool.
     *
     * @param entity the entity ID to look up
     * @return {@code true} if a component is stored for the entity
     */
    public boolean has(int entity) {
        if (entity >= entityToDense.length) return false;
        return entityToDense[entity] != -1;
    }

    /**
     * Resizes the sparse lookup mapping array based strictly on maximum Entity ID bounds.
     */
    private void ensureSparseCapacity(int entity) {
        if (entity >= entityToDense.length) {
            int oldCapacity = entityToDense.length;
            int newLength = Math.max(entity + 1, oldCapacity * 2);
            
            entityToDense = Arrays.copyOf(entityToDense, newLength);
            Arrays.fill(entityToDense, oldCapacity, newLength, -1);
            
            Logger.debug(Logger.System.CORE, "Resized sparse layout tracking matrix. Capacity: %d -> %d", oldCapacity, newLength);
        }
    }

    /**
     * Resizes the dense arrays independently when memory block thresholds are exhausted.
     */
    private void ensureDenseCapacity() {
        if (size >= denseComponents.length) {
            int newLength = denseComponents.length * 2;
            denseComponents = Arrays.copyOf(denseComponents, newLength);
            denseToEntity = Arrays.copyOf(denseToEntity, newLength);
            
            Logger.debug(Logger.System.CORE, "Resized contiguous dense infrastructure for type [%s]: %d allocation blocks.", componentType.getSimpleName(), newLength);
        }
    }

    /**
     * Returns the number of components stored, which is also the number of valid
     * entries at the start of the raw arrays.
     *
     * @return the count of live components
     */
    public int size() { return size; }

    /**
     * Returns the packed dense array itself, for allocation-free iteration.
     * Only indices {@code 0} to {@code size() - 1} are valid; the rest is spare capacity.
     * The array is replaced when the pool grows, so fetch it again after adding components.
     *
     * @return the live dense component array (not a copy)
     */
    public T[] getRawComponents() { return denseComponents; }

    /**
     * Returns the dense-index-to-entity array that runs parallel to
     * {@link #getRawComponents()}: entry {@code i} is the entity that owns component {@code i}.
     * Same validity rules as {@link #getRawComponents()}.
     *
     * @return the live dense entity array (not a copy)
     */
    public int[] getRawDenseToEntity() { return denseToEntity; }
}
