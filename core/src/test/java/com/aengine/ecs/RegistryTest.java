package com.aengine.ecs;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

/** Entity IDs: created, destroyed and handed out again. */
class RegistryTest {

    @Test
    void destroyingAnEntityTwiceDoesNotHandItsIdOutTwice() {
        // KNOWN_ISSUES #6
        Registry registry = new Registry();
        int a = registry.createEntity();
        registry.destroyEntity(a);
        registry.destroyEntity(a);
        int first = registry.createEntity();
        int second = registry.createEntity();
        assertNotEquals(first, second);
        assertEquals(2, registry.getEntityCount());
    }

    @Test
    void destroyingAnIdThatWasNeverCreatedIsIgnored() {
        Registry registry = new Registry();
        registry.destroyEntity(42);
        assertEquals(0, registry.createEntity(), "42 was not queued for reuse");
        assertEquals(1, registry.getEntityCount());
    }

    @Test
    void aDestroyedIdIsReused() {
        Registry registry = new Registry();
        int a = registry.createEntity();
        registry.createEntity();
        registry.destroyEntity(a);
        assertEquals(a, registry.createEntity());
    }
}
