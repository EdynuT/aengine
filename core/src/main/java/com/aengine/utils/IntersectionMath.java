package com.aengine.utils;

import org.joml.Vector3f;

/**
 * HARDWARE CONTEXT: LOW-LEVEL VECTOR INTERSECTION CORE
 * Contains dimensional-isolated primitives for narrow-phase resolution.
 * Strictly avoids Math.sqrt() on hot-paths using squared distance operations.
 *
 * <p>Yes/no overlap tests with no contact data. Sizes are half-extents (the distance from
 * the centre to a face). Touching exactly counts as overlapping. The physics system uses
 * {@link com.aengine.physics.NarrowPhase} instead; nothing in the engine calls this class
 * today.</p>
 */
public final class IntersectionMath {

    private IntersectionMath() {}

    // =========================================================================
    // 2D PIPELINE INTERSECTIONS (Z-Axis Culled / Ignored)
    // =========================================================================

    /**
     * Tests two axis-aligned rectangles on the XY plane. Z is ignored.
     *
     * @param posA  centre of A
     * @param sizeA half-extents of A
     * @param posB  centre of B
     * @param sizeB half-extents of B
     * @return {@code true} if they overlap or touch
     */
    public static boolean testAABB2D(Vector3f posA, Vector3f sizeA, Vector3f posB, Vector3f sizeB) {
        return (posA.x - sizeA.x <= posB.x + sizeB.x && posA.x + sizeA.x >= posB.x - sizeB.x) &&
               (posA.y - sizeA.y <= posB.y + sizeB.y && posA.y + sizeA.y >= posB.y - sizeB.y);
    }

    /**
     * Tests two circles on the XY plane. Z is ignored.
     *
     * @param posA    centre of A
     * @param radiusA radius of A
     * @param posB    centre of B
     * @param radiusB radius of B
     * @return {@code true} if they overlap or touch
     */
    public static boolean testSphere2D(Vector3f posA, float radiusA, Vector3f posB, float radiusB) {
        float dx = posB.x - posA.x;
        float dy = posB.y - posA.y;
        float distanceSquared = (dx * dx) + (dy * dy);
        float radiusSum = radiusA + radiusB;
        return distanceSquared <= (radiusSum * radiusSum);
    }

    /**
     * 2D OBB Collision using Separating Axis Theorem (SAT)
     * Projects entities along 4 potential separating axes (2 local axes per OBB).
     * Z is ignored.
     *
     * @param posA  centre of A
     * @param sizeA half-extents of A along its own axes
     * @param rotA  rotation of A around Z, in <b>radians</b>
     * @param posB  centre of B
     * @param sizeB half-extents of B along its own axes
     * @param rotB  rotation of B around Z, in radians
     * @return {@code true} if they overlap or touch
     */
    public static boolean testOBB2D(Vector3f posA, Vector3f sizeA, float rotA, Vector3f posB, Vector3f sizeB, float rotB) {
        // Matrizes de orientação locais 2D
        float cosA = (float) Math.cos(rotA), sinA = (float) Math.sin(rotA);
        float cosB = (float) Math.cos(rotB), sinB = (float) Math.sin(rotB);

        // Eixos locais de A e B
        float[][] axes = {
            { cosA, sinA }, { -sinA, cosA }, // Axes A
            { cosB, sinB }, { -sinB, cosB }  // Axes B
        };

        float dx = posB.x - posA.x;
        float dy = posB.y - posA.y;

        for (float[] axis : axes) {
            // Projects the center-to-center vector onto the current axis
            float distance = Math.abs(dx * axis[0] + dy * axis[1]);

            // Projects the half-extents of the boxes onto the current axis
            float projectionA = Math.abs(sizeA.x * (cosA * axis[0] + sinA * axis[1])) +
                                Math.abs(sizeA.y * (-sinA * axis[0] + cosA * axis[1]));
            
            float projectionB = Math.abs(sizeB.x * (cosB * axis[0] + sinB * axis[1])) +
                                Math.abs(sizeB.y * (-sinB * axis[0] + cosB * axis[1]));

            if (distance > projectionA + projectionB) {
                return false; // Found a separating axis, no collision.
            }
        }
        return true;
    }

    // =========================================================================
    // 3D PIPELINE INTERSECTIONS
    // =========================================================================

    /**
     * Tests two axis-aligned boxes.
     *
     * @param posA  centre of A
     * @param sizeA half-extents of A
     * @param posB  centre of B
     * @param sizeB half-extents of B
     * @return {@code true} if they overlap or touch
     */
    public static boolean testAABB3D(Vector3f posA, Vector3f sizeA, Vector3f posB, Vector3f sizeB) {
        return (posA.x - sizeA.x <= posB.x + sizeB.x && posA.x + sizeA.x >= posB.x - sizeB.x) &&
               (posA.y - sizeA.y <= posB.y + sizeB.y && posA.y + sizeA.y >= posB.y - sizeB.y) &&
               (posA.z - sizeA.z <= posB.z + sizeB.z && posA.z + sizeA.z >= posB.z - sizeB.z);
    }

    /**
     * Tests two spheres.
     *
     * @param posA    centre of A
     * @param radiusA radius of A
     * @param posB    centre of B
     * @param radiusB radius of B
     * @return {@code true} if they overlap or touch
     */
    public static boolean testSphere3D(Vector3f posA, float radiusA, Vector3f posB, float radiusB) {
        float dx = posB.x - posA.x;
        float dy = posB.y - posA.y;
        float dz = posB.z - posA.z;
        float distanceSquared = (dx * dx) + (dy * dy) + (dz * dz);
        float radiusSum = radiusA + radiusB;
        return distanceSquared <= (radiusSum * radiusSum);
    }
    
    // Note: 3D OBB calculation requires checking 15 axes via SAT (3 local axes of A, 3 local axes of B, and 9 cross products between them). 
    // We can plug this expansion once you validate these base modes on the i5 CPU.
}
