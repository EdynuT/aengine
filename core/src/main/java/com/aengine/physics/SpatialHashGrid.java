package com.aengine.physics;

import com.aengine.utils.Logger;

import java.util.Arrays;

/**
 * BROAD PHASE: hierarchical spatial hash grid.
 *
 * <p>Converts 3D AABB world-space bounds into discrete grid cells using prime-number hashing,
 * replacing an O(N²) brute-force collision loop with a candidate pipeline whose cost follows
 * the cells' occupancy.</p>
 *
 * <p>The result is a list of <em>candidate</em> pairs: two entities whose boxes share a
 * cell. They may still not touch; {@link NarrowPhase} decides. The grid only sees the
 * boxes it is given, so a box smaller than the real shape can make it miss a pair.</p>
 *
 * <p>Levels: level 0 has cells of {@code cellSize}; every level above has cells four times
 * wider (2, 8, 32, 128, 512... units for a cell size of 2). Each box goes to the lowest
 * level whose cell is at least as wide as the box's largest side, so it covers at most two
 * cells per axis, eight in all, whatever its size: a 200 × 200 floor takes a handful of
 * slots instead of ten thousand. Boxes on the same level pair up inside their shared cells;
 * a box also looks up the cells its bounds touch on every coarser level in use and pairs
 * with what it finds there.</p>
 *
 * <p>Coordinates are clamped to ±{@value #WORLD_LIMIT} world units, so the top level always
 * covers the clamped world in two cells per axis; a NaN coordinate falls into cell 0.</p>
 *
 * <p>Insertion formula (prime mixing — cast to long BEFORE multiplication to prevent overflow):
 * {@code key = ((long)cx * 73856093L) ^ ((long)cy * 19349663L) ^ ((long)cz * 83492791L)},
 * then mixed with the level. Two cells sharing a key only add candidates; none are lost.</p>
 *
 * <p>Internal layout (zero boxing, no allocation once warmed up):</p>
 * <ul>
 *   <li>Cell storage uses a raw open-addressing hash table: {@code long[] cellKeys} +
 *       {@code IntList[] cellValues}. No {@code java.util.HashMap} → no Long boxing on every insert.</li>
 *   <li>The table doubles and rehashes when it passes {@value #CELL_MAX_LOAD} occupancy, so a
 *       probe always ends on a free slot. It grows only while a scene reaches a size it has not
 *       had before; the next steps reuse the larger table.</li>
 *   <li>The occupied-slot tracker ({@code int[] occupiedSlots}) lets {@code clear()} touch only
 *       the K used slots instead of scanning the whole table each step.</li>
 *   <li>Pair deduplication uses a second open-addressing {@code long[]} set — each unique pair
 *       appears exactly once in the output buffer regardless of how many cells the entities share.</li>
 * </ul>
 *
 * <p>Usage per physics step:</p>
 * <ol>
 *   <li>{@code grid.clear()}</li>
 *   <li>{@code grid.insert(entity, minX, minY, minZ, maxX, maxY, maxZ)} for every collidable entity</li>
 *   <li>{@code int[] pairs = grid.buildPairs(); int count = grid.getPairCount();}</li>
 *   <li>Pass candidate pairs to NarrowPhase for precise shape tests</li>
 * </ol>
 *
 * <p>Not thread-safe.</p>
 */
public final class SpatialHashGrid {

    // -------------------------------------------------------------------------
    // Levels
    // -------------------------------------------------------------------------

    /** Coordinates beyond this distance from the origin are clamped to it. */
    static final float WORLD_LIMIT = 1.0e9f;

    /** Each level's cells are this many times wider than the level below. */
    private static final int LEVEL_RATIO = 4;

    /** Upper bound on the level count; the mask of levels in use is a {@code long}. */
    private static final int MAX_LEVELS = 63;

    /** Cell width per level, and its inverse for the coordinate conversion. */
    private final double[] levelSize;
    private final double[] levelInv;

    /** Bit {@code l} set when level {@code l} holds a box this step. */
    private long levelsInUse = 0;

    // -------------------------------------------------------------------------
    // Cell table — open-addressing, no boxing, grows when needed
    // -------------------------------------------------------------------------

    /** Starting capacity of the cell table. Power of two, for the slot mask. */
    private static final int   CELL_START_CAP = 4096;
    /** Occupancy past which the cell table doubles. */
    private static final float CELL_MAX_LOAD  = 0.7f;
    private static final long  CELL_NIL       = Long.MIN_VALUE; // Sentinel for an empty slot

    private int       cellCap;
    private int       cellMask;
    private int       cellShift;   // 64 - log2(cellCap): Fibonacci hashing keeps the top bits
    private int       cellGrowAt;  // occupiedCount at which the table doubles

    // Raw long array for cell keys — no boxing, L1-cache-friendly sequential access
    private long[]    cellKeys;
    // Pre-allocated IntList per slot; objects are reused across steps (size reset in clear())
    private IntList[] cellValues;
    // Tracks which table slots are occupied so clear() only resets those K slots
    private int[]     occupiedSlots;
    private int       occupiedCount = 0;

    // -------------------------------------------------------------------------
    // The boxes inserted this step, for the queries across levels
    // -------------------------------------------------------------------------

    private int[]   boxEntity = new int[256];
    private int[]   boxLevel  = new int[256];
    private float[] boxBounds = new float[256 * 6]; // minX, minY, minZ, maxX, maxY, maxZ
    private int     boxCount  = 0;

    // -------------------------------------------------------------------------
    // Pair dedup set — open-addressing, no boxing
    // -------------------------------------------------------------------------

    /**
     * Capacity of the pair dedup hash set.
     * At ~70% load it holds ~11 469 unique candidate pairs per step.
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
    // Warnings
    // -------------------------------------------------------------------------

    /** Guards the near-full warning so it is emitted only once per session. */
    private boolean pairSetWarnEmitted = false;

    // -------------------------------------------------------------------------
    // Constructor
    // -------------------------------------------------------------------------

    /**
     * Creates an empty grid with the starting cell table.
     *
     * @param cellSize World-space size of a level-0 cell; must be positive and finite.
     *                 Best set to roughly 2× the radius of an average collider so that
     *                 most entities sit on level 0 in a single cell per axis.
     *                 Larger entities go to coarser levels automatically.
     */
    public SpatialHashGrid(float cellSize) {
        if (!(cellSize > 0f) || Float.isInfinite(cellSize)) {
            throw new IllegalArgumentException("cellSize must be positive and finite: " + cellSize);
        }
        // Enough levels that the top cell spans the whole clamped world.
        double[] sizes = new double[MAX_LEVELS];
        int levels = 0;
        double size = cellSize;
        while (true) {
            sizes[levels++] = size;
            if (size >= 2.0 * WORLD_LIMIT || levels == MAX_LEVELS) break;
            size *= LEVEL_RATIO;
        }
        if (size < 2.0 * WORLD_LIMIT) {
            throw new IllegalArgumentException("cellSize too small for the level count: " + cellSize);
        }
        levelSize = Arrays.copyOf(sizes, levels);
        levelInv  = new double[levels];
        for (int l = 0; l < levels; l++) levelInv[l] = 1.0 / levelSize[l];

        allocateCells(CELL_START_CAP);
        for (int i = 0; i < cellCap; i++) cellValues[i] = new IntList();
        Arrays.fill(pairSet, PAIR_NIL);
    }

    // -------------------------------------------------------------------------
    // Public API
    // -------------------------------------------------------------------------

    /**
     * Reset all cell occupancy lists and the pair dedup set for the next physics step.
     *
     * Complexity: O(occupiedCount) for cell resets + O(PAIR_SET_CAP) for pair set.
     * The cell reset is O(K) — only the slots used in the previous step are touched,
     * not the whole table.
     */
    public void clear() {
        // O(K) — reset only the slots written during the previous step
        for (int i = 0; i < occupiedCount; i++) {
            int slot = occupiedSlots[i];
            cellKeys[slot]   = CELL_NIL;   // Mark slot empty
            cellValues[slot].size = 0;     // Reuse IntList object; array data kept for next step
        }
        occupiedCount = 0;
        boxCount      = 0;
        levelsInUse   = 0;

        // O(PAIR_SET_CAP) — must fully clear the pair dedup set each step
        // (there is no compact tracking equivalent for the pair set)
        Arrays.fill(pairSet, PAIR_NIL);
        pairSetCount = 0;
        pairCount    = 0;
    }

    /**
     * Insert an entity into the cells its AABB overlaps, on the level that fits its size.
     *
     * The box covers at most two cells per axis on its level, so the cost of an insert
     * does not grow with the box. Inserting the same entity twice in one step is harmless:
     * pairs are deduplicated and an entity never pairs with itself.
     *
     * @param entity ECS entity ID; must be zero or positive
     * @param minX   World-space AABB minimum corner, X
     * @param minY   World-space AABB minimum corner, Y
     * @param minZ   World-space AABB minimum corner, Z
     * @param maxX   World-space AABB maximum corner, X
     * @param maxY   World-space AABB maximum corner, Y
     * @param maxZ   World-space AABB maximum corner, Z
     */
    public void insert(int entity,
                       float minX, float minY, float minZ,
                       float maxX, float maxY, float maxZ) {
        minX = clamp(minX); minY = clamp(minY); minZ = clamp(minZ);
        maxX = clamp(maxX); maxY = clamp(maxY); maxZ = clamp(maxZ);

        int level = levelFor(Math.max(maxX - minX, Math.max(maxY - minY, maxZ - minZ)));
        rememberBox(entity, level, minX, minY, minZ, maxX, maxY, maxZ);
        levelsInUse |= 1L << level;

        double inv = levelInv[level];
        int x0 = gridCoord(minX, inv), x1 = Math.max(x0, gridCoord(maxX, inv));
        int y0 = gridCoord(minY, inv), y1 = Math.max(y0, gridCoord(maxY, inv));
        int z0 = gridCoord(minZ, inv), z1 = Math.max(z0, gridCoord(maxZ, inv));

        // The loops stop on equality rather than "<=", so a range ending at
        // Integer.MAX_VALUE cannot wrap around.
        for (int cx = x0; ; cx++) {
            for (int cy = y0; ; cy++) {
                for (int cz = z0; ; cz++) {
                    getOrCreateCell(cellKey(cx, cy, cz, level)).add(entity);
                    if (cz == z1) break;
                }
                if (cy == y1) break;
            }
            if (cx == x1) break;
        }
    }

    /**
     * Collect all unique candidate collision pairs: entities sharing a cell on the same
     * level, and each box against the coarser-level cells its bounds touch.
     *
     * Each pair (A, B) with {@code A < B} is emitted exactly once even if the two entities share
     * multiple cells — the open-addressing pair set deduplicates across all cells.
     *
     * <p>Call it once per step, after all inserts; a second call before {@link #clear()}
     * appends nothing new but returns the same pairs.</p>
     *
     * @return Internal flat buffer [a₀,b₀, a₁,b₁, ...]. Valid until the next {@link #clear()}.
     *         The array is usually longer than the data: read only the first
     *         {@code 2 * getPairCount()} entries.
     */
    public int[] buildPairs() {
        // Same level: every pair inside each occupied cell.
        for (int s = 0; s < occupiedCount; s++) {
            IntList list = cellValues[occupiedSlots[s]];
            if (list.size < 2) continue;

            for (int i = 0; i < list.size; i++) {
                int a = list.data[i];
                for (int j = i + 1; j < list.size; j++) {
                    emitPair(a, list.data[j]);
                }
            }
        }

        // Across levels: each box against the coarser levels in use.
        for (int b = 0; b < boxCount; b++) {
            int  level   = boxLevel[b];
            long coarser = levelsInUse & ~((2L << level) - 1);
            if (coarser == 0) continue;

            int   entity = boxEntity[b];
            int   o      = b * 6;
            float minX = boxBounds[o],     minY = boxBounds[o + 1], minZ = boxBounds[o + 2];
            float maxX = boxBounds[o + 3], maxY = boxBounds[o + 4], maxZ = boxBounds[o + 5];

            while (coarser != 0) {
                int m = Long.numberOfTrailingZeros(coarser);
                coarser &= coarser - 1;

                double inv = levelInv[m];
                int x0 = gridCoord(minX, inv), x1 = Math.max(x0, gridCoord(maxX, inv));
                int y0 = gridCoord(minY, inv), y1 = Math.max(y0, gridCoord(maxY, inv));
                int z0 = gridCoord(minZ, inv), z1 = Math.max(z0, gridCoord(maxZ, inv));

                for (int cx = x0; ; cx++) {
                    for (int cy = y0; ; cy++) {
                        for (int cz = z0; ; cz++) {
                            IntList list = findCell(cellKey(cx, cy, cz, m));
                            if (list != null) {
                                for (int i = 0; i < list.size; i++) emitPair(entity, list.data[i]);
                            }
                            if (cz == z1) break;
                        }
                        if (cy == y1) break;
                    }
                    if (cx == x1) break;
                }
            }
        }
        return pairBuf;
    }

    /**
     * Number of unique candidate pairs produced by the last {@link #buildPairs()} call.
     *
     * @return the pair count; the buffer holds twice as many entity IDs
     */
    public int getPairCount() {
        return pairCount;
    }

    /**
     * Current pair-set occupancy as a fraction of capacity [0, 1].
     * Values above 0.70 indicate the dedup set is under pressure; near 1.0 means pairs
     * may not be fully deduplicated and duplicates can enter the narrow phase.
     *
     * @return the fraction of pair-set slots in use this step
     */
    public float pairSetLoad() {
        return (float) pairSetCount / PAIR_SET_CAP;
    }

    /**
     * Current cell-table occupancy as a fraction of its present capacity [0, 1].
     * It stays at or below {@value #CELL_MAX_LOAD}: past that the table grows.
     *
     * @return the fraction of cell-table slots in use this step
     */
    public float cellTableLoad() {
        return (float) occupiedCount / cellCap;
    }

    /**
     * Number of slots in the cell table; it starts at 4096 and doubles as scenes need.
     *
     * @return the current capacity
     */
    public int cellTableCapacity() {
        return cellCap;
    }

    // -------------------------------------------------------------------------
    // Private helpers
    // -------------------------------------------------------------------------

    private static float clamp(float v) {
        // NaN passes through both tests unchanged and lands in cell 0.
        return v > WORLD_LIMIT ? WORLD_LIMIT : (v < -WORLD_LIMIT ? -WORLD_LIMIT : v);
    }

    /** The lowest level whose cell is at least {@code extent} wide; NaN goes to the top. */
    private int levelFor(float extent) {
        int top = levelSize.length - 1;
        for (int l = 0; l < top; l++) {
            if (extent <= levelSize[l]) return l;
        }
        return top;
    }

    /**
     * Convert a world coordinate to its integer cell index on a level.
     * {@code Math.floor} is required (not a simple cast) to handle negative coordinates:
     * e.g., floor(-0.3 / 2.0) = floor(-0.15) = -1 (correct cell);
     *       (int)(-0.3 / 2.0) = (int)(-0.15) =  0 (wrong cell, truncation toward zero).
     * The cast saturates instead of overflowing; NaN becomes 0.
     */
    private static int gridCoord(float coord, double inv) {
        return (int) Math.floor(coord * inv);
    }

    /**
     * Compute a 64-bit hash key from three signed grid-cell coordinates and the level.
     *
     * IMPORTANT: casts to long happen BEFORE the multiplication to prevent 32-bit overflow.
     * The naive form {@code (long)(cx * 73856093)} performs 32-bit multiplication first,
     * silently overflowing for |cx| > ~29. This form is correct for any int value of cx/cy/cz.
     *
     * Prime constants (from Teschner et al. 2003 spatial hashing paper):
     *   73 856 093, 19 349 663, 83 492 791
     */
    private static long cellKey(int cx, int cy, int cz, int level) {
        return ((long) cx * 73856093L)
             ^ ((long) cy * 19349663L)
             ^ ((long) cz * 83492791L)
             ^ ((long) level * 0x632BE59BD9B4E019L);
    }

    private int slotOf(long key) {
        // Fibonacci hashing: multiply by 2^64 / φ ≈ 0x9e3779b97f4a7c15 for well-spread slots
        return (int) ((key * 0x9e3779b97f4a7c15L) >>> cellShift);
    }

    /**
     * Find or create the IntList for a given cell key using open-addressing linear probing.
     * No boxing — operates entirely on primitive long arrays.
     *
     * The table grows before it passes {@value #CELL_MAX_LOAD} occupancy, so the probe
     * always meets a free slot. A new slot's index is recorded in {@code occupiedSlots} so
     * that {@link #clear()} can reset only those K slots.
     */
    private IntList getOrCreateCell(long key) {
        if (occupiedCount >= cellGrowAt) grow();

        int slot = slotOf(key);
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
            slot = (slot + 1) & cellMask;
        }
    }

    /** The IntList of an occupied cell, or null; never creates one. */
    private IntList findCell(long key) {
        int slot = slotOf(key);
        while (true) {
            long cur = cellKeys[slot];
            if (cur == CELL_NIL) return null;
            if (cur == key) return cellValues[slot];
            slot = (slot + 1) & cellMask;
        }
    }

    private void allocateCells(int cap) {
        cellCap       = cap;
        cellMask      = cap - 1;
        cellShift     = 64 - Integer.numberOfTrailingZeros(cap);
        cellGrowAt    = (int) (cap * CELL_MAX_LOAD);
        cellKeys      = new long[cap];
        cellValues    = new IntList[cap];
        occupiedSlots = new int[cap];
        Arrays.fill(cellKeys, CELL_NIL);
    }

    /** Doubles the cell table and moves the occupied cells, lists and all, into it. */
    private void grow() {
        long[]    oldKeys   = cellKeys;
        IntList[] oldValues = cellValues;
        int[]     oldSlots  = occupiedSlots;
        int       count     = occupiedCount;

        allocateCells(cellCap * 2);
        occupiedCount = 0;
        for (int i = 0; i < count; i++) {
            int  from = oldSlots[i];
            long key  = oldKeys[from];
            int  slot = slotOf(key);
            while (cellKeys[slot] != CELL_NIL) slot = (slot + 1) & cellMask;
            cellKeys[slot]   = key;
            cellValues[slot] = oldValues[from];
            occupiedSlots[occupiedCount++] = slot;
        }
        for (int i = 0; i < cellCap; i++) {
            if (cellValues[i] == null) cellValues[i] = new IntList();
        }
        Logger.info(Logger.System.CORE,
            "[SpatialHashGrid] Cell table grown to %d slots for %d occupied cells.", cellCap, count);
    }

    private void rememberBox(int entity, int level,
                             float minX, float minY, float minZ,
                             float maxX, float maxY, float maxZ) {
        if (boxCount == boxEntity.length) {
            int cap = boxEntity.length * 2;
            boxEntity = Arrays.copyOf(boxEntity, cap);
            boxLevel  = Arrays.copyOf(boxLevel, cap);
            boxBounds = Arrays.copyOf(boxBounds, cap * 6);
        }
        boxEntity[boxCount] = entity;
        boxLevel[boxCount]  = level;
        int o = boxCount * 6;
        boxBounds[o]     = minX; boxBounds[o + 1] = minY; boxBounds[o + 2] = minZ;
        boxBounds[o + 3] = maxX; boxBounds[o + 4] = maxY; boxBounds[o + 5] = maxZ;
        boxCount++;
    }

    /** Adds the pair to the output unless it is an entity with itself or already there. */
    private void emitPair(int a, int b) {
        if (a == b) return;
        // Canonical form: smaller ID in the high 32 bits of the key
        if (a > b) { int tmp = a; a = b; b = tmp; }
        if (!insertPairDedup(a, b)) return;

        if (pairCount * 2 + 2 > pairBuf.length) {
            pairBuf = Arrays.copyOf(pairBuf, pairBuf.length * 2);
        }
        pairBuf[pairCount * 2]     = a;
        pairBuf[pairCount * 2 + 1] = b;
        pairCount++;
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
