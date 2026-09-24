package com.aengine.physics;

import com.aengine.utils.Logger;

import java.util.Arrays;

/**
 * BROAD PHASE: Deterministic Spatial Hash Grid
 *
 * Converts 3D AABB world-space bounds into discrete grid cells using prime-number hashing,
 * replacing the previous O(N²) brute-force collision loop with an O(K) candidate pipeline
 * where K is the average cell occupancy.
 *
 * Insertion formula (prime mixing — cast to long BEFORE multiplication to prevent overflow):
 *   key = ((long)cx * 73856093L) ^ ((long)cy * 19349663L) ^ ((long)cz * 83492791L)
 *
 * Internal layout (zero boxing, zero GC):
 *   - Cell storage uses a raw open-addressing hash table: long[] cellKeys + IntList[] cellValues.
 *     No java.util.HashMap → no Long boxing on every insert.
 *   - The occupied-slot tracker (int[] occupiedSlots) lets clear() touch only the K used slots
 *     instead of scanning the full CELL_CAP array each frame.
 *   - Pair deduplication uses a second open-addressing long[] set — each unique pair appears
 *     exactly once in the output buffer regardless of how many cells the entities share.
 *
 * Usage per physics step:
 *   1. grid.clear()
 *   2. grid.insert(entity, minX, minY, minZ, maxX, maxY, maxZ) for every collidable entity
 *   3. int[] pairs = grid.buildPairs()  /  int count = grid.getPairCount()
 *   4. Pass candidate pairs to NarrowPhase for precise shape tests
 */
public final class SpatialHashGrid {

    // -------------------------------------------------------------------------
    // Cell table — open-addressing, no boxing
    // -------------------------------------------------------------------------

    /**
     * Capacity of the cell hash table. Power-of-2 required for fast modulo via mask.
     * At 75% load the table holds 3072 unique cells. Each cell covers {@code cellSize³}
     * world units, so 3072 cells ≈ a 14×14×14 occupied area at cellSize=2.
     * Increase if scenes span many disjoint regions.
     */
    private static final int  CELL_CAP  = 4096;
    private static final int  CELL_MASK = CELL_CAP - 1;
    private static final long CELL_NIL  = Long.MIN_VALUE; // Sentinel for an empty slot

    // Raw long array for cell keys — no boxing, L1-cache-friendly sequential access
    private final long[]     cellKeys     = new long[CELL_CAP];
    // Pre-allocated IntList per slot; objects are reused across frames (size reset in clear())
    private final IntList[]  cellValues   = new IntList[CELL_CAP];
    // Tracks which table slots are occupied so clear() only resets those K slots, not all CELL_CAP
    private final int[]      occupiedSlots = new int[CELL_CAP];
    private int              occupiedCount = 0;

    // -------------------------------------------------------------------------
    // Pair dedup set — open-addressing, no boxing
    // -------------------------------------------------------------------------

    /**
     * Capacity of the pair dedup hash set.
     * At ~70% load it holds ~11 469 unique candidate pairs per frame.
     * If a scene produces more than this, insertPairDedup degrades gracefully (see method).
     */
    private static final int  PAIR_SET_CAP  = 16_384;
    private static final int  PAIR_SET_MASK = PAIR_SET_CAP - 1;
    private static final long PAIR_NIL      = Long.MIN_VALUE; // Safe: entity IDs are non-negative,
                                                               // so encoded key MSB is always 0.
    private final long[] pairSet = new long[PAIR_SET_CAP];
    private int          pairSetCount = 0; // Track occupancy for near-full detection

    // -------------------------------------------------------------------------
    // Output
    // -------------------------------------------------------------------------

    /** Flat output buffer: [entA₀, entB₀, entA₁, entB₁, ...] */
    private int[] pairBuf   = new int[1024];
    private int   pairCount = 0;

    // -------------------------------------------------------------------------
    // Configuration
    // -------------------------------------------------------------------------

    private final float cellSize;

    /** Guards the near-full warning so it is emitted only once per session. */
    private boolean pairSetWarnEmitted  = false;
    private boolean cellTableWarnEmitted = false;

    // -------------------------------------------------------------------------
    // Constructor
    // -------------------------------------------------------------------------

    /**
     * @param cellSize World-space size of each hash cell.
     *                 Best set to roughly 2× the radius of an average collider so that
     *                 most entities occupy a single cell per axis (1 cell × 1 cell × 1 cell),
     *                 minimising both insertion cost and candidate-pair generation.
     *                 Oversized entities spanning many cells still work but increase K.
     */
    public SpatialHashGrid(float cellSize) {
        this.cellSize = cellSize;

        // Fill table keys with CELL_NIL (empty sentinel)
        Arrays.fill(cellKeys, CELL_NIL);

        // Pre-allocate ALL IntList objects once. They are reused across every frame.
        // This eliminates new IntList() allocations in the hot insert path.
        for (int i = 0; i < CELL_CAP; i++) {
            cellValues[i] = new IntList();
        }

        Arrays.fill(pairSet, PAIR_NIL);
    }

    // -------------------------------------------------------------------------
    // Public API
    // -------------------------------------------------------------------------

    /**
     * Reset all cell occupancy lists and the pair dedup set for the next physics step.
     *
     * Complexity: O(occupiedCount) for cell resets + O(PAIR_SET_CAP) for pair set.
     * The cell reset is O(K) — only the slots used in the previous frame are touched,
     * not the full CELL_CAP table.
     */
    public void clear() {
        // O(K) — reset only the slots written during the previous step
        for (int i = 0; i < occupiedCount; i++) {
            int slot = occupiedSlots[i];
            cellKeys[slot]   = CELL_NIL;   // Mark slot empty
            cellValues[slot].size = 0;     // Reuse IntList object; array data kept for next frame
        }
        occupiedCount = 0;

        // O(PAIR_SET_CAP) — must fully clear the pair dedup set each frame
        // (there is no compact tracking equivalent for the pair set)
        Arrays.fill(pairSet, PAIR_NIL);
        pairSetCount = 0;
        pairCount    = 0;
    }

    /**
     * Insert an entity into every grid cell that its AABB overlaps.
     *
     * An entity whose AABB spans S cells per axis will be inserted into S³ cells.
     * Keep {@code cellSize} larger than the typical entity extent to keep S = 1.
     *
     * @param entity         ECS entity ID
     * @param minX minY minZ World-space AABB minimum corner
     * @param maxX maxY maxZ World-space AABB maximum corner
     */
    public void insert(int entity,
                       float minX, float minY, float minZ,
                       float maxX, float maxY, float maxZ) {
        int x0 = gridCoord(minX), x1 = gridCoord(maxX);
        int y0 = gridCoord(minY), y1 = gridCoord(maxY);
        int z0 = gridCoord(minZ), z1 = gridCoord(maxZ);

        for (int cx = x0; cx <= x1; cx++) {
            for (int cy = y0; cy <= y1; cy++) {
                for (int cz = z0; cz <= z1; cz++) {
                    getOrCreateCell(cellKey(cx, cy, cz)).add(entity);
                }
            }
        }
    }

    /**
     * Collect all unique candidate collision pairs from entities sharing the same cell.
     *
     * Each pair (A, B) with A < B is emitted exactly once even if the two entities share
     * multiple cells — the open-addressing pair set deduplicates across all cells.
     *
     * @return Internal flat buffer [a₀,b₀, a₁,b₁, ...]. Valid until the next {@link #clear()}.
     */
    public int[] buildPairs() {
        // pairCount was reset in clear() — safe to accumulate from 0
        for (int s = 0; s < occupiedCount; s++) {
            IntList list = cellValues[occupiedSlots[s]];
            if (list.size < 2) continue;

            for (int i = 0; i < list.size; i++) {
                int a = list.data[i];
                for (int j = i + 1; j < list.size; j++) {
                    int b = list.data[j];

                    // Canonical form: smaller ID in the high 32 bits of the key
                    if (a > b) { int tmp = a; a = b; b = tmp; }

                    if (insertPairDedup(a, b)) {
                        // Grow output buffer if needed
                        if (pairCount * 2 + 2 > pairBuf.length) {
                            pairBuf = java.util.Arrays.copyOf(pairBuf, pairBuf.length * 2);
                        }
                        pairBuf[pairCount * 2]     = a;
                        pairBuf[pairCount * 2 + 1] = b;
                        pairCount++;
                    }
                }
            }
        }
        return pairBuf;
    }

    /** Number of unique candidate pairs produced by the last {@link #buildPairs()} call. */
    public int getPairCount() {
        return pairCount;
    }

    /**
     * Current pair-set occupancy as a fraction of capacity [0, 1].
     * Values above 0.70 indicate the dedup set is under pressure; near 1.0 means pairs
     * may not be fully deduplicated and duplicates can enter the narrow phase.
     */
    public float pairSetLoad() {
        return (float) pairSetCount / PAIR_SET_CAP;
    }

    /**
     * Current cell-table occupancy as a fraction of capacity [0, 1].
     * Values above 0.75 increase average probe depth.
     */
    public float cellTableLoad() {
        return (float) occupiedCount / CELL_CAP;
    }

    // -------------------------------------------------------------------------
    // Private helpers
    // -------------------------------------------------------------------------

    /**
     * Convert a world coordinate to its integer grid cell index.
     * {@code Math.floor} is required (not a simple cast) to handle negative coordinates:
     * e.g., floor(-0.3 / 2.0) = floor(-0.15) = -1 (correct cell);
     *       (int)(-0.3 / 2.0) = (int)(-0.15) =  0 (wrong cell, truncation toward zero).
     */
    private int gridCoord(float coord) {
        return (int) Math.floor(coord / cellSize);
    }

    /**
     * Compute a 64-bit hash key from three signed grid-cell coordinates.
     *
     * IMPORTANT: casts to long happen BEFORE the multiplication to prevent 32-bit overflow.
     * The naive form {@code (long)(cx * 73856093)} performs 32-bit multiplication first,
     * silently overflowing for |cx| > ~29. This form is correct for any int value of cx/cy/cz.
     *
     * Prime constants (from Teschner et al. 2003 spatial hashing paper):
     *   73 856 093, 19 349 663, 83 492 791
     */
    private static long cellKey(int cx, int cy, int cz) {
        return ((long) cx * 73856093L)
             ^ ((long) cy * 19349663L)
             ^ ((long) cz * 83492791L);
    }

    /**
     * Find or create the IntList for a given cell key using open-addressing linear probing.
     * No boxing — operates entirely on primitive long arrays.
     *
     * When a new slot is created, its index is recorded in {@code occupiedSlots} so that
     * {@link #clear()} can reset only those K slots in O(K) instead of O(CELL_CAP).
     */
    private IntList getOrCreateCell(long key) {
        // Fibonacci hashing: multiply by 2^64 / φ ≈ 0x9e3779b97f4a7c15 for well-spread slots
        long h = key * 0x9e3779b97f4a7c15L;
        int slot = (int)(h >>> (64 - 12)) & CELL_MASK; // top 12 bits → index 0..4095

        // Warn once if the table is getting full (>75% occupancy degrades probe chains)
        if (!cellTableWarnEmitted && occupiedCount > (int)(CELL_CAP * 0.75f)) {
            Logger.warn(Logger.System.CORE,
                "[SpatialHashGrid] Cell table at %.0f%% capacity (%d/%d). " +
                "Consider increasing CELL_CAP or cellSize.",
                cellTableLoad() * 100f, occupiedCount, CELL_CAP);
            cellTableWarnEmitted = true;
        }

        while (true) {
            long cur = cellKeys[slot];
            if (cur == CELL_NIL) {
                // Empty slot — claim it and register for O(K) clear
                cellKeys[slot] = key;
                occupiedSlots[occupiedCount++] = slot;
                return cellValues[slot]; // Pre-allocated IntList, size already 0 from last clear()
            }
            if (cur == key) {
                return cellValues[slot]; // Existing slot
            }
            // Collision — linear probe to next slot
            slot = (slot + 1) & CELL_MASK;
        }
    }

    /**
     * Insert a canonical pair key (a < b) into the dedup set.
     *
     * Encoding: high 32 bits = entity a, low 32 bits = entity b.
     * Because entity IDs are non-negative, the MSB is always 0, so the key can never
     * equal {@code PAIR_NIL = Long.MIN_VALUE}.
     *
     * @return {@code true} if the pair is new; {@code false} if already present.
     */
    private boolean insertPairDedup(int a, int b) {
        long key = ((long) a << 32) | Integer.toUnsignedLong(b);

        // MurmurHash64-style finaliser for uniform slot distribution
        long h = key ^ (key >>> 33);
        h *= 0xff51afd7ed558ccdL;
        h ^= h >>> 33;
        int slot = (int)(h & PAIR_SET_MASK);

        // Warn once if the pair set is becoming saturated
        if (!pairSetWarnEmitted && pairSetCount > (int)(PAIR_SET_CAP * 0.70f)) {
            Logger.warn(Logger.System.CORE,
                "[SpatialHashGrid] Pair dedup set at %.0f%% capacity (%d/%d). " +
                "Duplicate candidates may enter the narrow phase.",
                pairSetLoad() * 100f, pairSetCount, PAIR_SET_CAP);
            pairSetWarnEmitted = true;
        }

        for (int probe = 0; probe < PAIR_SET_CAP; probe++) {
            long cur = pairSet[slot];
            if (cur == PAIR_NIL) {
                // New pair — insert and count
                pairSet[slot] = key;
                pairSetCount++;
                return true;
            }
            if (cur == key) {
                return false; // Already present — skip duplicate
            }
            slot = (slot + 1) & PAIR_SET_MASK;
        }
        // Pair set completely full: accept without dedup to avoid dropping a real collision.
        // This state should trigger the near-full warning well before it is reached.
        return true;
    }

    // -------------------------------------------------------------------------
    // Internal: compact non-allocating integer list
    // -------------------------------------------------------------------------

    static final class IntList {
        int[] data = new int[4];
        int   size = 0;

        void add(int v) {
            if (size == data.length) {
                data = Arrays.copyOf(data, data.length * 2);
            }
            data[size++] = v;
        }
    }
}
