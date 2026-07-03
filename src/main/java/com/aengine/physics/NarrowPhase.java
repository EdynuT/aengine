package com.aengine.physics;

import org.joml.Matrix3f;
import org.joml.Vector3f;
import com.aengine.ecs.components.ColliderComponent;
import com.aengine.ecs.components.ColliderType;
import com.aengine.ecs.components.TransformComponent;

/**
 * Narrow Phase — stateless collision shape tests.
 *
 * All methods write their result into a pre-leased {@link CollisionManifold} and return
 * {@code true} on a hit.  The manifold's {@code normal} consistently points
 * FROM entity A TOWARD entity B in world space.
 *
 * Supported shape pairs:
 *   AABB   vs AABB   — 3-axis SAT (axis-aligned separating axes)
 *   SPHERE vs SPHERE — distance vs radius sum
 *   AABB   vs SPHERE — closest-point-on-AABB test
 *   SPHERE vs AABB   — (symmetric)
 *   OBB    vs OBB    — full 15-axis SAT (3 face normals each + 9 edge-edge cross products)
 *   OBB    vs AABB   — SAT treating AABB as zero-rotation OBB (identity axes)
 *   AABB   vs OBB    — (symmetric)
 *   OBB    vs SPHERE — closest-point-on-oriented-box to sphere
 *   SPHERE vs OBB    — (symmetric)
 */
public final class NarrowPhase {

    private NarrowPhase() {}

    // =========================================================================
    // OBB pre-allocated temporaries
    // Static is safe: NarrowPhase is only called from the physics thread,
    // which serialises all test() calls via PhysicsThread.syncLock.
    // =========================================================================

    private static final Matrix3f   OBB_ROT_A  = new Matrix3f();
    private static final Matrix3f   OBB_ROT_B  = new Matrix3f();
    /** World-space local axes of OBB A, rebuilt each call to testOBB_*. */
    private static final Vector3f[] OBB_AXES_A = { new Vector3f(), new Vector3f(), new Vector3f() };
    /** World-space local axes of OBB B (or identity for AABB). */
    private static final Vector3f[] OBB_AXES_B = { new Vector3f(), new Vector3f(), new Vector3f() };
    /** Centre-to-centre displacement vector (A → B), reused in performSAT. */
    private static final Vector3f   OBB_T      = new Vector3f();

    // -------------------------------------------------------------------------
    // Public dispatch
    // -------------------------------------------------------------------------

    /**
     * Run the appropriate shape-pair test and populate {@code out} on a hit.
     *
     * @param entityA / entityB ECS entity IDs
     * @param tA / tB           Transform components
     * @param cA / cB           Collider components
     * @param out               Pre-leased manifold to write contact data into
     * @return {@code true} if the shapes are intersecting
     */
    public static boolean test(
            int entityA, TransformComponent tA, ColliderComponent cA,
            int entityB, TransformComponent tB, ColliderComponent cB,
            CollisionManifold out) {

        boolean hit;

        ColliderType typeA = cA.type;
        ColliderType typeB = cB.type;

        if (typeA == ColliderType.AABB && typeB == ColliderType.AABB) {
            hit = testAABB_AABB(tA, cA, tB, cB, out);

        } else if (typeA == ColliderType.SPHERE && typeB == ColliderType.SPHERE) {
            hit = testSphere_Sphere(tA, cA, tB, cB, out);

        } else if (typeA == ColliderType.AABB && typeB == ColliderType.SPHERE) {
            // testAABB_Sphere(box=A, sphere=B): raw normal = (sphere_center - closest_box_point),
            // pointing FROM A (box surface) TOWARD B (sphere center) = A→B. No flip needed.
            hit = testAABB_Sphere(tA, cA, tB, cB, out);

        } else if (typeA == ColliderType.SPHERE && typeB == ColliderType.AABB) {
            // testAABB_Sphere(box=B, sphere=A): raw normal points FROM B-surface TOWARD A-center = B→A.
            // Negate to obtain the required A→B convention.
            hit = testAABB_Sphere(tB, cB, tA, cA, out);
            if (hit) out.normal.negate();

        } else if (typeA == ColliderType.OBB && typeB == ColliderType.OBB) {
            // Both OBBs use their Transform rotation to build world-space axes.
            hit = testOBB_OBB(tA, cA, tB, cB, out);

        } else if (typeA == ColliderType.OBB && typeB == ColliderType.AABB) {
            // AABB treated as zero-rotation OBB (identity axes, ignores Transform.rotation).
            // Normal: FROM OBB-surface TOWARD AABB-centre = A→B ✓
            hit = testOBB_AABB(tA, cA, tB, cB, out);

        } else if (typeA == ColliderType.AABB && typeB == ColliderType.OBB) {
            // Swap: OBB is now B. Raw normal = B→A; negate to get A→B.
            hit = testOBB_AABB(tB, cB, tA, cA, out);
            if (hit) out.normal.negate();

        } else if (typeA == ColliderType.OBB && typeB == ColliderType.SPHERE) {
            // Closest-point-on-OBB test. Normal: OBB-surface → sphere-centre = A→B ✓
            hit = testOBB_Sphere(tA, cA, tB, cB, out);

        } else if (typeA == ColliderType.SPHERE && typeB == ColliderType.OBB) {
            // Swap: OBB is now B. Raw normal = B→A; negate to get A→B.
            hit = testOBB_Sphere(tB, cB, tA, cA, out);
            if (hit) out.normal.negate();

        } else {
            // Unhandled pair — no collision
            return false;
        }

        if (hit) {
            out.entityA  = entityA;
            out.entityB  = entityB;
            out.isTrigger = cA.isTrigger || cB.isTrigger;
        }
        return hit;
    }

    // -------------------------------------------------------------------------
    // AABB vs AABB  (3-axis SAT — Separating Axis Theorem, axis-aligned case)
    // -------------------------------------------------------------------------

    /**
     * Test two axis-aligned bounding boxes in 3D.
     * The minimum-penetration axis becomes the contact normal.
     *
     * Half-extents are stored in {@link ColliderComponent#size}.
     * World-space centres are {@code transform.position + collider.offset}.
     */
    private static boolean testAABB_AABB(
            TransformComponent tA, ColliderComponent cA,
            TransformComponent tB, ColliderComponent cB,
            CollisionManifold out) {

        // World-space AABB centres
        float axc = tA.position.x + cA.offset.x;
        float ayc = tA.position.y + cA.offset.y;
        float azc = tA.position.z + cA.offset.z;

        float bxc = tB.position.x + cB.offset.x;
        float byc = tB.position.y + cB.offset.y;
        float bzc = tB.position.z + cB.offset.z;

        float dx = bxc - axc;
        float dy = byc - ayc;
        float dz = bzc - azc;

        // Overlap per axis: (half-size_A + half-size_B) - |distance|
        float penX = (cA.size.x + cB.size.x) - Math.abs(dx);
        float penY = (cA.size.y + cB.size.y) - Math.abs(dy);
        float penZ = (cA.size.z + cB.size.z) - Math.abs(dz);

        // A separating axis exists if any overlap is ≤ 0 → no collision
        if (penX <= 0.0f || penY <= 0.0f || penZ <= 0.0f) return false;

        // Select the axis of minimum penetration as the contact normal
        if (penX <= penY && penX <= penZ) {
            out.depth = penX;
            out.normal.set(Math.signum(dx), 0.0f, 0.0f);
        } else if (penY <= penX && penY <= penZ) {
            out.depth = penY;
            out.normal.set(0.0f, Math.signum(dy), 0.0f);
        } else {
            out.depth = penZ;
            out.normal.set(0.0f, 0.0f, Math.signum(dz));
        }

        return true;
    }

    // -------------------------------------------------------------------------
    // Sphere vs Sphere
    // -------------------------------------------------------------------------

    /**
     * Test two spheres.
     * Radius is stored in {@link ColliderComponent#size}'s X component via {@code getRadius()}.
     */
    private static boolean testSphere_Sphere(
            TransformComponent tA, ColliderComponent cA,
            TransformComponent tB, ColliderComponent cB,
            CollisionManifold out) {

        float dx = (tB.position.x + cB.offset.x) - (tA.position.x + cA.offset.x);
        float dy = (tB.position.y + cB.offset.y) - (tA.position.y + cA.offset.y);
        float dz = (tB.position.z + cB.offset.z) - (tA.position.z + cA.offset.z);

        float distSq  = dx * dx + dy * dy + dz * dz;
        float radSum  = cA.getRadius() + cB.getRadius();

        if (distSq >= radSum * radSum) return false;

        float dist = (float) Math.sqrt(distSq);

        if (dist < 1e-6f) {
            // Degenerate: perfectly co-located spheres — push along +Y
            out.normal.set(0.0f, 1.0f, 0.0f);
            out.depth = radSum;
        } else {
            float inv = 1.0f / dist;
            out.normal.set(dx * inv, dy * inv, dz * inv);
            out.depth = radSum - dist;
        }
        return true;
    }

    // -------------------------------------------------------------------------
    // AABB vs Sphere
    //
    // Normal convention here: points FROM the sphere centre TOWARD the AABB surface
    // (i.e., from B toward A when the AABB is A).
    // The dispatch above calls negate() so the final manifold always reads A→B.
    // -------------------------------------------------------------------------

    /**
     * Test an AABB (param order: box first, sphere second).
     *
     * Algorithm:
     *   1. Find the closest point on the box surface to the sphere centre.
     *   2. If the squared distance to that point is less than radius², we have a hit.
     *   3. Special case: sphere centre is fully inside the box — push out via
     *      the face of minimum signed distance to avoid a zero normal.
     */
    private static boolean testAABB_Sphere(
            TransformComponent tBox, ColliderComponent cBox,     // AABB
            TransformComponent tSph, ColliderComponent cSph,     // Sphere
            CollisionManifold out) {

        // World-space sphere centre
        float sx = tSph.position.x + cSph.offset.x;
        float sy = tSph.position.y + cSph.offset.y;
        float sz = tSph.position.z + cSph.offset.z;

        // World-space box centre
        float bx = tBox.position.x + cBox.offset.x;
        float by = tBox.position.y + cBox.offset.y;
        float bz = tBox.position.z + cBox.offset.z;

        float hx = cBox.size.x;
        float hy = cBox.size.y;
        float hz = cBox.size.z;

        // Closest point on box surface (or interior) to sphere centre
        float cpx = Math.max(bx - hx, Math.min(sx, bx + hx));
        float cpy = Math.max(by - hy, Math.min(sy, by + hy));
        float cpz = Math.max(bz - hz, Math.min(sz, bz + hz));

        float dx = sx - cpx;
        float dy = sy - cpy;
        float dz = sz - cpz;

        float distSq = dx * dx + dy * dy + dz * dz;
        float radius = cSph.getRadius();

        if (distSq >= radius * radius) return false;

        // distSq == 0 when the closest point on the box collapsed to the sphere centre,
        // which only happens when the sphere centre is fully inside the AABB.
        // Use a small epsilon to absorb float rounding at exact-surface contacts.
        if (distSq < 1e-12f) {
            // Sphere is inside the box — push it out through the face of minimum penetration depth.
            float dxFace = hx - Math.abs(sx - bx);
            float dyFace = hy - Math.abs(sy - by);
            float dzFace = hz - Math.abs(sz - bz);

            if (dxFace <= dyFace && dxFace <= dzFace) {
                out.normal.set(Math.signum(sx - bx), 0.0f, 0.0f);
                out.depth = radius + dxFace;
            } else if (dyFace <= dxFace && dyFace <= dzFace) {
                out.normal.set(0.0f, Math.signum(sy - by), 0.0f);
                out.depth = radius + dyFace;
            } else {
                out.normal.set(0.0f, 0.0f, Math.signum(sz - bz));
                out.depth = radius + dzFace;
            }
        } else {
            float dist = (float) Math.sqrt(distSq);
            float inv  = 1.0f / dist;
            out.normal.set(dx * inv, dy * inv, dz * inv);
            out.depth = radius - dist;
        }
        return true;
    }

    // =========================================================================
    // OBB helpers
    // =========================================================================

    /**
     * Populate {@code axes[3]} with the world-space local axes of an OBB by applying
     * its Transform Euler rotation (XYZ order, radians) to the standard basis vectors.
     * Uses a pre-allocated {@code Matrix3f} to avoid heap allocation.
     */
    private static void buildOBBAxes(TransformComponent t, Vector3f[] axes, Matrix3f mat) {
        mat.rotationXYZ(t.rotation.x, t.rotation.y, t.rotation.z);
        // Transform each basis vector in-place: axis[i] = mat * basisVector[i]
        axes[0].set(1, 0, 0);  mat.transform(axes[0]); // Right
        axes[1].set(0, 1, 0);  mat.transform(axes[1]); // Up
        axes[2].set(0, 0, 1);  mat.transform(axes[2]); // Forward
    }

    // -------------------------------------------------------------------------
    // OBB vs OBB
    // -------------------------------------------------------------------------

    /** Test two Oriented Bounding Boxes using the full 15-axis SAT. */
    private static boolean testOBB_OBB(
            TransformComponent tA, ColliderComponent cA,
            TransformComponent tB, ColliderComponent cB,
            CollisionManifold out) {

        buildOBBAxes(tA, OBB_AXES_A, OBB_ROT_A);
        buildOBBAxes(tB, OBB_AXES_B, OBB_ROT_B);
        OBB_T.set(
            (tB.position.x + cB.offset.x) - (tA.position.x + cA.offset.x),
            (tB.position.y + cB.offset.y) - (tA.position.y + cA.offset.y),
            (tB.position.z + cB.offset.z) - (tA.position.z + cA.offset.z));

        return performSAT(
            OBB_AXES_A, cA.size.x, cA.size.y, cA.size.z,
            OBB_AXES_B, cB.size.x, cB.size.y, cB.size.z,
            OBB_T, out);
    }

    // -------------------------------------------------------------------------
    // OBB vs AABB  (OBB = first param, AABB = second)
    // -------------------------------------------------------------------------

    /**
     * Test an OBB against an AABB by treating the AABB as an OBB with identity rotation.
     * AABB always uses axis-aligned faces regardless of its Transform.rotation value.
     * Normal convention: FROM OBB surface TOWARD AABB centre (A→B when OBB=A).
     */
    private static boolean testOBB_AABB(
            TransformComponent tOBB,  ColliderComponent cOBB,
            TransformComponent tAABB, ColliderComponent cAABB,
            CollisionManifold out) {

        buildOBBAxes(tOBB, OBB_AXES_A, OBB_ROT_A);

        // AABB axes = identity — axis-aligned by definition
        OBB_AXES_B[0].set(1, 0, 0);
        OBB_AXES_B[1].set(0, 1, 0);
        OBB_AXES_B[2].set(0, 0, 1);

        OBB_T.set(
            (tAABB.position.x + cAABB.offset.x) - (tOBB.position.x + cOBB.offset.x),
            (tAABB.position.y + cAABB.offset.y) - (tOBB.position.y + cOBB.offset.y),
            (tAABB.position.z + cAABB.offset.z) - (tOBB.position.z + cOBB.offset.z));

        return performSAT(
            OBB_AXES_A, cOBB.size.x,  cOBB.size.y,  cOBB.size.z,
            OBB_AXES_B, cAABB.size.x, cAABB.size.y, cAABB.size.z,
            OBB_T, out);
    }

    // -------------------------------------------------------------------------
    // OBB vs Sphere  (OBB = first param, Sphere = second)
    // -------------------------------------------------------------------------

    /**
     * Test an OBB against a Sphere using the closest-point-on-oriented-box algorithm.
     *
     * Algorithm:
     *   1. Project the sphere centre onto each OBB local axis to get OBB-local coordinates.
     *   2. Clamp those coordinates to the OBB half-extents.
     *   3. Reconstruct the closest world-space point from the clamped local coordinates.
     *   4. Compare squared distance from sphere centre to that point against radius².
     *   5. Handle the degenerate case (sphere inside box) by finding the nearest exit face.
     *
     * Normal: FROM OBB surface TOWARD sphere centre (A→B when OBB=A).
     */
    private static boolean testOBB_Sphere(
            TransformComponent tOBB, ColliderComponent cOBB,
            TransformComponent tSph, ColliderComponent cSph,
            CollisionManifold out) {

        buildOBBAxes(tOBB, OBB_AXES_A, OBB_ROT_A);

        float obbCx = tOBB.position.x + cOBB.offset.x;
        float obbCy = tOBB.position.y + cOBB.offset.y;
        float obbCz = tOBB.position.z + cOBB.offset.z;

        // World-space vector from OBB centre to sphere centre
        float spx = tSph.position.x + cSph.offset.x;
        float spy = tSph.position.y + cSph.offset.y;
        float spz = tSph.position.z + cSph.offset.z;
        float dx = spx - obbCx;
        float dy = spy - obbCy;
        float dz = spz - obbCz;

        // Project onto each local OBB axis to get OBB-local sphere coordinates
        float lx = OBB_AXES_A[0].x * dx + OBB_AXES_A[0].y * dy + OBB_AXES_A[0].z * dz;
        float ly = OBB_AXES_A[1].x * dx + OBB_AXES_A[1].y * dy + OBB_AXES_A[1].z * dz;
        float lz = OBB_AXES_A[2].x * dx + OBB_AXES_A[2].y * dy + OBB_AXES_A[2].z * dz;

        // Clamp local coordinates to OBB half-extents
        float clx = Math.max(-cOBB.size.x, Math.min(lx, cOBB.size.x));
        float cly = Math.max(-cOBB.size.y, Math.min(ly, cOBB.size.y));
        float clz = Math.max(-cOBB.size.z, Math.min(lz, cOBB.size.z));

        // Reconstruct the closest point on the OBB surface in world space:
        //   closest = obbCentre + clx*axisX + cly*axisY + clz*axisZ
        float cpx = obbCx + clx * OBB_AXES_A[0].x + cly * OBB_AXES_A[1].x + clz * OBB_AXES_A[2].x;
        float cpy = obbCy + clx * OBB_AXES_A[0].y + cly * OBB_AXES_A[1].y + clz * OBB_AXES_A[2].y;
        float cpz = obbCz + clx * OBB_AXES_A[0].z + cly * OBB_AXES_A[1].z + clz * OBB_AXES_A[2].z;

        float ddx = spx - cpx;
        float ddy = spy - cpy;
        float ddz = spz - cpz;
        float distSq = ddx * ddx + ddy * ddy + ddz * ddz;
        float radius = cSph.getRadius();

        if (distSq >= radius * radius) return false;

        // distSq ≈ 0: sphere centre is inside the OBB.
        // Find the nearest face in local space and push out through it.
        if (distSq < 1e-12f) {
            float dxFace = cOBB.size.x - Math.abs(lx);
            float dyFace = cOBB.size.y - Math.abs(ly);
            float dzFace = cOBB.size.z - Math.abs(lz);

            if (dxFace <= dyFace && dxFace <= dzFace) {
                out.depth = radius + dxFace;
                float s = lx >= 0 ? 1f : -1f;
                out.normal.set(OBB_AXES_A[0].x * s, OBB_AXES_A[0].y * s, OBB_AXES_A[0].z * s);
            } else if (dyFace <= dxFace && dyFace <= dzFace) {
                out.depth = radius + dyFace;
                float s = ly >= 0 ? 1f : -1f;
                out.normal.set(OBB_AXES_A[1].x * s, OBB_AXES_A[1].y * s, OBB_AXES_A[1].z * s);
            } else {
                out.depth = radius + dzFace;
                float s = lz >= 0 ? 1f : -1f;
                out.normal.set(OBB_AXES_A[2].x * s, OBB_AXES_A[2].y * s, OBB_AXES_A[2].z * s);
            }
        } else {
            float dist = (float) Math.sqrt(distSq);
            out.depth = radius - dist;
            float inv  = 1.0f / dist;
            out.normal.set(ddx * inv, ddy * inv, ddz * inv);
        }
        return true;
    }

    // =========================================================================
    // Core 15-axis SAT engine
    // =========================================================================

    /**
     * Separating Axis Theorem test for two OBBs with pre-computed world-space axes.
     *
     * Tests 15 candidate separating axes:
     *   Axes [0-2]  — 3 face normals of A    (each axis projects A to one half-extent)
     *   Axes [3-5]  — 3 face normals of B
     *   Axes [6-14] — 9 edge-edge cross products (one edge direction from each box)
     *
     * For each axis L the overlap is:
     *   pen = (projA + projB) − |dot(T, L)|        where projX = Σ_k |dot(axX[k], L)| * hXk
     * If pen ≤ 0 on any axis, a separating plane exists → no collision.
     * The axis with the minimum positive pen becomes the contact normal.
     *
     * Cross products of nearly-parallel edge pairs (|L|² < ε) are skipped to prevent
     * division-by-near-zero and numerically meaningless normal directions.
     *
     * @param axA      world-space unit axes of OBB A (length-3 array)
     * @param hAx hAy hAz  half-extents of A along its three local axes
     * @param axB      world-space unit axes of OBB B
     * @param hBx hBy hBz  half-extents of B
     * @param T        world-space vector FROM A centre TOWARD B centre
     * @param out      manifold to write depth and normal into on hit
     * @return         true if the OBBs overlap on all 15 axes
     */
    private static boolean performSAT(
            Vector3f[] axA, float hAx, float hAy, float hAz,
            Vector3f[] axB, float hBx, float hBy, float hBz,
            Vector3f T, CollisionManifold out) {

        float minPen = Float.MAX_VALUE;
        float bestNx = 0, bestNy = 1, bestNz = 0;

        // ── Face normals of A (3 axes) ────────────────────────────────────────
        //
        // For L = axA[i], projection of A onto its own axis = hAi (orthogonal decomposition).
        // Projection of B = Σ_k |dot(axB[k], L)| * hBk.

        // Axis 0: axA[0]
        {
            float Lx = axA[0].x, Ly = axA[0].y, Lz = axA[0].z;
            float rb  = Math.abs(Lx*axB[0].x + Ly*axB[0].y + Lz*axB[0].z) * hBx
                      + Math.abs(Lx*axB[1].x + Ly*axB[1].y + Lz*axB[1].z) * hBy
                      + Math.abs(Lx*axB[2].x + Ly*axB[2].y + Lz*axB[2].z) * hBz;
            float dist = Math.abs(T.x*Lx + T.y*Ly + T.z*Lz);
            float pen  = hAx + rb - dist;
            if (pen <= 0.0f) return false;
            if (pen < minPen) { minPen = pen; float s = (T.x*Lx+T.y*Ly+T.z*Lz)>=0?1f:-1f; bestNx=Lx*s; bestNy=Ly*s; bestNz=Lz*s; }
        }
        // Axis 1: axA[1]
        {
            float Lx = axA[1].x, Ly = axA[1].y, Lz = axA[1].z;
            float rb  = Math.abs(Lx*axB[0].x + Ly*axB[0].y + Lz*axB[0].z) * hBx
                      + Math.abs(Lx*axB[1].x + Ly*axB[1].y + Lz*axB[1].z) * hBy
                      + Math.abs(Lx*axB[2].x + Ly*axB[2].y + Lz*axB[2].z) * hBz;
            float dist = Math.abs(T.x*Lx + T.y*Ly + T.z*Lz);
            float pen  = hAy + rb - dist;
            if (pen <= 0.0f) return false;
            if (pen < minPen) { minPen = pen; float s = (T.x*Lx+T.y*Ly+T.z*Lz)>=0?1f:-1f; bestNx=Lx*s; bestNy=Ly*s; bestNz=Lz*s; }
        }
        // Axis 2: axA[2]
        {
            float Lx = axA[2].x, Ly = axA[2].y, Lz = axA[2].z;
            float rb  = Math.abs(Lx*axB[0].x + Ly*axB[0].y + Lz*axB[0].z) * hBx
                      + Math.abs(Lx*axB[1].x + Ly*axB[1].y + Lz*axB[1].z) * hBy
                      + Math.abs(Lx*axB[2].x + Ly*axB[2].y + Lz*axB[2].z) * hBz;
            float dist = Math.abs(T.x*Lx + T.y*Ly + T.z*Lz);
            float pen  = hAz + rb - dist;
            if (pen <= 0.0f) return false;
            if (pen < minPen) { minPen = pen; float s = (T.x*Lx+T.y*Ly+T.z*Lz)>=0?1f:-1f; bestNx=Lx*s; bestNy=Ly*s; bestNz=Lz*s; }
        }

        // ── Face normals of B (3 axes) ────────────────────────────────────────

        // Axis 3: axB[0]
        {
            float Lx = axB[0].x, Ly = axB[0].y, Lz = axB[0].z;
            float ra  = Math.abs(Lx*axA[0].x + Ly*axA[0].y + Lz*axA[0].z) * hAx
                      + Math.abs(Lx*axA[1].x + Ly*axA[1].y + Lz*axA[1].z) * hAy
                      + Math.abs(Lx*axA[2].x + Ly*axA[2].y + Lz*axA[2].z) * hAz;
            float dist = Math.abs(T.x*Lx + T.y*Ly + T.z*Lz);
            float pen  = ra + hBx - dist;
            if (pen <= 0.0f) return false;
            if (pen < minPen) { minPen = pen; float s = (T.x*Lx+T.y*Ly+T.z*Lz)>=0?1f:-1f; bestNx=Lx*s; bestNy=Ly*s; bestNz=Lz*s; }
        }
        // Axis 4: axB[1]
        {
            float Lx = axB[1].x, Ly = axB[1].y, Lz = axB[1].z;
            float ra  = Math.abs(Lx*axA[0].x + Ly*axA[0].y + Lz*axA[0].z) * hAx
                      + Math.abs(Lx*axA[1].x + Ly*axA[1].y + Lz*axA[1].z) * hAy
                      + Math.abs(Lx*axA[2].x + Ly*axA[2].y + Lz*axA[2].z) * hAz;
            float dist = Math.abs(T.x*Lx + T.y*Ly + T.z*Lz);
            float pen  = ra + hBy - dist;
            if (pen <= 0.0f) return false;
            if (pen < minPen) { minPen = pen; float s = (T.x*Lx+T.y*Ly+T.z*Lz)>=0?1f:-1f; bestNx=Lx*s; bestNy=Ly*s; bestNz=Lz*s; }
        }
        // Axis 5: axB[2]
        {
            float Lx = axB[2].x, Ly = axB[2].y, Lz = axB[2].z;
            float ra  = Math.abs(Lx*axA[0].x + Ly*axA[0].y + Lz*axA[0].z) * hAx
                      + Math.abs(Lx*axA[1].x + Ly*axA[1].y + Lz*axA[1].z) * hAy
                      + Math.abs(Lx*axA[2].x + Ly*axA[2].y + Lz*axA[2].z) * hAz;
            float dist = Math.abs(T.x*Lx + T.y*Ly + T.z*Lz);
            float pen  = ra + hBz - dist;
            if (pen <= 0.0f) return false;
            if (pen < minPen) { minPen = pen; float s = (T.x*Lx+T.y*Ly+T.z*Lz)>=0?1f:-1f; bestNx=Lx*s; bestNy=Ly*s; bestNz=Lz*s; }
        }

        // ── Edge-edge cross products (9 axes) ─────────────────────────────────
        //
        // L = axA[i] × axB[j].  Normalise before projecting.
        // Skip nearly-parallel edge pairs to avoid a near-zero denominator.

        for (int i = 0; i < 3; i++) {
            Vector3f ai = axA[i];
            float hAi = (i == 0) ? hAx : (i == 1) ? hAy : hAz;
            for (int j = 0; j < 3; j++) {
                Vector3f bj = axB[j];
                float hBj = (j == 0) ? hBx : (j == 1) ? hBy : hBz;

                float Lx = ai.y * bj.z - ai.z * bj.y;
                float Ly = ai.z * bj.x - ai.x * bj.z;
                float Lz = ai.x * bj.y - ai.y * bj.x;
                float lenSq = Lx * Lx + Ly * Ly + Lz * Lz;
                // Degenerate: parallel edges produce a zero (or near-zero) cross product.
                // No unique separating axis exists along this direction — skip it.
                if (lenSq < 1e-6f) continue;

                float invLen = 1.0f / (float) Math.sqrt(lenSq);
                Lx *= invLen;  Ly *= invLen;  Lz *= invLen;

                // Project A's and B's extents onto L (unrolled over 3 axes each)
                float ra = Math.abs(axA[0].x*Lx + axA[0].y*Ly + axA[0].z*Lz) * hAx
                         + Math.abs(axA[1].x*Lx + axA[1].y*Ly + axA[1].z*Lz) * hAy
                         + Math.abs(axA[2].x*Lx + axA[2].y*Ly + axA[2].z*Lz) * hAz;
                float rb = Math.abs(axB[0].x*Lx + axB[0].y*Ly + axB[0].z*Lz) * hBx
                         + Math.abs(axB[1].x*Lx + axB[1].y*Ly + axB[1].z*Lz) * hBy
                         + Math.abs(axB[2].x*Lx + axB[2].y*Ly + axB[2].z*Lz) * hBz;

                float dist = Math.abs(T.x*Lx + T.y*Ly + T.z*Lz);
                float pen  = ra + rb - dist;
                if (pen <= 0.0f) return false;
                if (pen < minPen) {
                    minPen = pen;
                    float s = (T.x*Lx + T.y*Ly + T.z*Lz) >= 0 ? 1f : -1f;
                    bestNx = Lx * s;  bestNy = Ly * s;  bestNz = Lz * s;
                }
            }
        }

        out.depth = minPen;
        out.normal.set(bestNx, bestNy, bestNz);
        return true;
    }
}
