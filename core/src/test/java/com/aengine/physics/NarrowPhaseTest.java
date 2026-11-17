package com.aengine.physics;

import com.aengine.ecs.components.ColliderComponent;
import com.aengine.ecs.components.ColliderType;
import com.aengine.ecs.components.TransformComponent;
import org.joml.Vector3f;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The shape tests and the boxes the broad phase gets for each shape. */
class NarrowPhaseTest {

    private static TransformComponent at(float x, float y, float z) {
        return new TransformComponent(new Vector3f(x, y, z));
    }

    private static CollisionManifold manifold() {
        CollisionManifold.releaseFrame();
        return CollisionManifold.lease();
    }

    private static boolean touch(TransformComponent tA, ColliderComponent cA,
                                 TransformComponent tB, ColliderComponent cB,
                                 CollisionManifold out) {
        return NarrowPhase.test(1, tA, cA, 2, tB, cB, out);
    }

    // ---- #3: OBB rotation is in degrees ------------------------------------

    @Test
    void anObbRotatedNinetyDegreesCollidesWhereItIsDrawn() {
        // A plank 4 long on X, thin on Z; turned 90° about Y it lies along Z.
        TransformComponent plank = at(0, 0, 0);
        plank.rotation.set(0, 90, 0);
        ColliderComponent plankShape = new ColliderComponent(ColliderType.OBB, 2.0f, 0.5f, 0.1f);
        ColliderComponent cube = new ColliderComponent(ColliderType.AABB, 0.1f, 0.1f, 0.1f);
        CollisionManifold out = manifold();

        assertTrue(touch(plank, plankShape, at(0, 0, 1.8f), cube, out), "the plank now reaches along Z");
        assertFalse(touch(plank, plankShape, at(1.8f, 0, 0), cube, out), "and no longer along X");
    }

    // ---- #5: centres at the same spot still separate ----------------------

    @Test
    void twoBoxesAtTheSameSpotGetANormalToSeparateAlong() {
        ColliderComponent box = new ColliderComponent(ColliderType.AABB);
        CollisionManifold out = manifold();
        assertTrue(touch(at(3, 1, 2), box, at(3, 1, 2), box, out));
        assertEquals(1.0f, out.normal.length(), 1e-6f);
        assertTrue(out.depth > 0);
    }

    @Test
    void aSphereCentredInABoxGetsANormalToLeaveBy() {
        ColliderComponent box = new ColliderComponent(ColliderType.AABB);
        ColliderComponent ball = new ColliderComponent(ColliderType.SPHERE, 0.25f, 0.25f, 0.25f);
        CollisionManifold out = manifold();

        assertTrue(touch(at(0, 0, 0), box, at(0, 0, 0), ball, out));
        assertEquals(1.0f, out.normal.length(), 1e-6f);

        assertTrue(touch(at(0, 0, 0), ball, at(0, 0, 0), box, out), "and with the sphere first");
        assertEquals(1.0f, out.normal.length(), 1e-6f);
    }

    // ---- #4: the broad phase sees the whole shape --------------------------

    @Test
    void aSphereIsBoundedByItsRadiusOnEveryAxis() {
        ColliderComponent ball = new ColliderComponent(ColliderType.SPHERE, 2.0f, 0.5f, 0.5f);
        Vector3f half = new Vector3f();
        NarrowPhase.boundsHalfExtents(at(0, 0, 0), ball, half);
        assertEquals(new Vector3f(2, 2, 2), half);
    }

    @Test
    void aRotatedObbIsBoundedByItsCorners() {
        TransformComponent t = at(0, 0, 0);
        t.rotation.set(0, 45, 0);
        ColliderComponent shape = new ColliderComponent(ColliderType.OBB, 1.0f, 0.5f, 1.0f);
        Vector3f half = new Vector3f();
        NarrowPhase.boundsHalfExtents(t, shape, half);
        float reach = (float) Math.sqrt(2.0);  // a unit square's corner, turned 45°
        assertEquals(reach, half.x, 1e-5f);
        assertEquals(0.5f, half.y, 1e-5f);
        assertEquals(reach, half.z, 1e-5f);
    }

    @Test
    void anAabbIsBoundedByItsSize() {
        ColliderComponent box = new ColliderComponent(ColliderType.AABB, 1.0f, 2.0f, 3.0f);
        TransformComponent t = at(0, 0, 0);
        t.rotation.set(30, 40, 50);  // ignored: an AABB does not turn
        Vector3f half = new Vector3f();
        NarrowPhase.boundsHalfExtents(t, box, half);
        assertEquals(new Vector3f(1, 2, 3), half);
    }
}
