package com.aengine.physics;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The broad phase: which entities are handed to the narrow phase as possibly touching. */
class SpatialHashGridTest {

    private static Set<Long> pairs(SpatialHashGrid grid) {
        int[] buf = grid.buildPairs();
        Set<Long> out = new HashSet<>();
        for (int i = 0; i < grid.getPairCount(); i++) out.add(((long) buf[i * 2] << 32) | buf[i * 2 + 1]);
        return out;
    }

    private static long pair(int a, int b) { return ((long) Math.min(a, b) << 32) | Math.max(a, b); }

    @Test
    void twoBoxesInTheSameCellAreACandidatePairAndFarOnesAreNot() {
        SpatialHashGrid grid = new SpatialHashGrid(2.0f);
        grid.insert(1, 0, 0, 0, 0.5f, 0.5f, 0.5f);
        grid.insert(2, 0.2f, 0.2f, 0.2f, 0.7f, 0.7f, 0.7f);
        grid.insert(3, 50, 50, 50, 51, 51, 51);
        Set<Long> found = pairs(grid);
        assertEquals(Set.of(pair(1, 2)), found);
    }

    @Test
    void aFloorFarLargerThanTheTableDoesNotFreezeTheStep() {
        // KNOWN_ISSUES #2: a 200 x 200 floor covers about 10 000 cells of 2 units, more than
        // the 4096 the table holds; the probe for a free slot used to loop for ever.
        assertTimeoutPreemptively(Duration.ofSeconds(5), () -> {
            SpatialHashGrid grid = new SpatialHashGrid(2.0f);
            grid.insert(1, -100, -0.5f, -100, 100, 0.5f, 100);   // the floor
            grid.insert(2, 10, 0.5f, 10, 11, 1.5f, 11);           // a box resting on it
            grid.insert(3, -30, 0.5f, 40, -29, 1.5f, 41);         // another, far from the first
            Set<Long> found = pairs(grid);
            assertTrue(found.contains(pair(1, 2)), "the box on the floor is still tested against it");
            assertTrue(found.contains(pair(1, 3)));
            assertTrue(!found.contains(pair(2, 3)), "the two boxes are far apart");
        });
    }

    @Test
    void aTableFilledByManySmallEntitiesStillFindsTheirPairs() {
        assertTimeoutPreemptively(Duration.ofSeconds(5), () -> {
            SpatialHashGrid grid = new SpatialHashGrid(2.0f);
            // 5000 boxes, each in a cell of its own: more distinct cells than the table holds.
            for (int i = 0; i < 5000; i++) {
                float x = (i % 100) * 4.0f, z = (i / 100) * 4.0f;
                grid.insert(i, x, 0, z, x + 1, 1, z + 1);
            }
            // One more box overlapping the last one.
            grid.insert(9999, 99 * 4.0f + 0.5f, 0, 49 * 4.0f + 0.5f, 99 * 4.0f + 1.5f, 1, 49 * 4.0f + 1.5f);
            assertTrue(pairs(grid).contains(pair(4999, 9999)));
        });
    }

    @Test
    void theNextStepStartsClean() {
        SpatialHashGrid grid = new SpatialHashGrid(2.0f);
        grid.insert(1, -100, -0.5f, -100, 100, 0.5f, 100);
        grid.insert(2, 0, 0, 0, 1, 1, 1);
        pairs(grid);
        grid.clear();
        grid.insert(3, 0, 0, 0, 1, 1, 1);
        grid.buildPairs();
        assertEquals(0, grid.getPairCount(), "nothing left from the last step");
    }

    @Test
    void aFloorTakesAFewCellsNotThousands() {
        SpatialHashGrid grid = new SpatialHashGrid(2.0f);
        grid.insert(1, -100, -0.5f, -100, 100, 0.5f, 100);
        assertTrue(grid.cellTableLoad() * grid.cellTableCapacity() <= 8, "at most two cells per axis on its level");
    }

    @Test
    void boxesOfDifferentSizesThatOverlapAreAPair() {
        SpatialHashGrid grid = new SpatialHashGrid(2.0f);
        grid.insert(1, 0, 0, 0, 1, 1, 1);           // level 0
        grid.insert(2, 0.5f, 0.5f, 0.5f, 6, 6, 6);  // level 1 (cells of 8)
        grid.insert(3, -40, 0, -40, 40, 3, 40);     // level 3 (cells of 128)
        assertEquals(Set.of(pair(1, 2), pair(1, 3), pair(2, 3)), pairs(grid));
    }

    @Test
    void aLargeWallFarAwayIsNotACandidate() {
        SpatialHashGrid grid = new SpatialHashGrid(2.0f);
        grid.insert(1, 1000, 0, 1000, 1100, 10, 1001);  // a wall 100 units long, far off
        grid.insert(2, 0, 0, 0, 1, 1, 1);
        grid.insert(3, 0.5f, 0, 0.5f, 1.5f, 1, 1.5f);
        assertEquals(Set.of(pair(2, 3)), pairs(grid));
    }

    @Test
    void anEntityInsertedTwiceNeverPairsWithItself() {
        SpatialHashGrid grid = new SpatialHashGrid(2.0f);
        grid.insert(1, 0, 0, 0, 1, 1, 1);
        grid.insert(1, 0, 0, 0, 1, 1, 1);
        grid.buildPairs();
        assertEquals(0, grid.getPairCount());
    }

    @Test
    void theTableGrowsAndKeepsItsCellsWhenASceneOutgrowsIt() {
        SpatialHashGrid grid = new SpatialHashGrid(2.0f);
        int start = grid.cellTableCapacity();
        for (int i = 0; i < 5000; i++) grid.insert(i, i * 4.0f, 0, 0, i * 4.0f + 1, 1, 1);
        assertTrue(grid.cellTableCapacity() > start);
        assertTrue(grid.cellTableLoad() <= 0.7f);
        grid.insert(9999, 0.5f, 0, 0, 1.5f, 1, 1);  // overlaps entity 0, inserted before the growth
        assertTrue(pairs(grid).contains(pair(0, 9999)));
    }

    @Test
    void boxesWithInfiniteOrNaNBoundsDoNotHangTheStep() {
        assertTimeoutPreemptively(Duration.ofSeconds(5), () -> {
            SpatialHashGrid grid = new SpatialHashGrid(2.0f);
            float inf = Float.POSITIVE_INFINITY;
            grid.insert(1, -inf, -inf, -inf, inf, inf, inf);
            grid.insert(2, Float.NaN, Float.NaN, Float.NaN, Float.NaN, Float.NaN, Float.NaN);
            grid.insert(3, 0, 0, 0, 1, 1, 1);
            assertTrue(pairs(grid).contains(pair(1, 3)), "an infinite box covers everything");
        });
    }
}
