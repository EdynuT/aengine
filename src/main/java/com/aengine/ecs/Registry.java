package com.aengine.ecs;

import com.aengine.utils.Logger;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public final class Registry {

    private int entityCounter = 0;
    private final List<Integer> freeEntities = new ArrayList<>();
    private final Map<Class<?>, ComponentPool<?>> componentPools = new HashMap<>();
    private final List<Integer> activeEntities = new ArrayList<>(); 

    /*
     * FastEntityView completely eliminates Java Heap allocations (new ArrayList<Integer>) 
     * during the game loop. It uses a raw primitive int[] array to prevent Integer boxing,
     * maintaining a perfectly contiguous memory block for systems to iterate over.
     */
    public static final class FastEntityView {
        public int[] data = new int[128];
        public int size = 0;

        public void add(int entity) {
            if (size == data.length) {
                // Resize linearly to preserve memory locality
                data = java.util.Arrays.copyOf(data, data.length * 2);
            }
            data[size++] = entity;
        }
        public int get(int index) { return data[index]; }
        public int size() { return size; }
        public void clear() { size = 0; }
    }

    // Pre-allocated reusable buffer for component queries. Prevents GC spikes.
    private final FastEntityView viewBuffer = new FastEntityView();

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

    @SuppressWarnings("unchecked")
    public <T> void addComponent(int entity, T component) {
        Class<?> type = component.getClass();
        ComponentPool<T> pool = (ComponentPool<T>) componentPools.computeIfAbsent(type, k -> {
            Logger.info(Logger.System.CORE, "Allocating cold infrastructure ComponentPool for type: %s", type.getSimpleName());
            return new ComponentPool<>(type);
        });
        pool.put(entity, component);
    }

    @SuppressWarnings("unchecked")
    public <T> void removeComponent(int entity, Class<T> componentType) {
        ComponentPool<T> pool = (ComponentPool<T>) componentPools.get(componentType);
        if (pool != null) {
            pool.remove(entity);
        }
    }

    @SuppressWarnings("unchecked")
    public <T> T getComponent(int entity, Class<T> componentType) {
        ComponentPool<T> pool = (ComponentPool<T>) componentPools.get(componentType);
        if (pool == null) return null;
        return pool.get(entity);
    }

    @SuppressWarnings("unchecked")
    public <T> ComponentPool<T> getPool(Class<T> componentType) {
        return (ComponentPool<T>) componentPools.get(componentType);
    }
}
