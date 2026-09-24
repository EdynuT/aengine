package com.aengine.utils;

import org.joml.Vector3f;

/**
 * HARDWARE CONTEXT: LOW-LEVEL VECTOR INTERSECTION CORE
 * Contains dimensional-isolated primitives for narrow-phase resolution.
 * Strictly avoids Math.sqrt() on hot-paths using squared distance operations.
 */
public final class IntersectionMath {

    private IntersectionMath() {}

    // =========================================================================
    // 2D PIPELINE INTERSECTIONS (Z-Axis Culled / Ignored)
    // =========================================================================

    public static boolean testAABB2D(Vector3f posA, Vector3f sizeA, Vector3f posB, Vector3f sizeB) {
        return (posA.x - sizeA.x <= posB.x + sizeB.x && posA.x + sizeA.x >= posB.x - sizeB.x) &&
               (posA.y - sizeA.y <= posB.y + sizeB.y && posA.y + sizeA.y >= posB.y - sizeB.y);
    }

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

    public static boolean testAABB3D(Vector3f posA, Vector3f sizeA, Vector3f posB, Vector3f sizeB) {
        return (posA.x - sizeA.x <= posB.x + sizeB.x && posA.x + sizeA.x >= posB.x - sizeB.x) &&
               (posA.y - sizeA.y <= posB.y + sizeB.y && posA.y + sizeA.y >= posB.y - sizeB.y) &&
               (posA.z - sizeA.z <= posB.z + sizeB.z && posA.z + sizeA.z >= posB.z - sizeB.z);
    }

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
