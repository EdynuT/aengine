package com.aengine.physics;

import com.aengine.ecs.Registry;
import com.aengine.ecs.systems.PhysicsSystem;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Starting and stopping the physics loop. */
class PhysicsThreadTest {

    @Test
    void theLoopCanBeStoppedAndStartedAgain() {
        // KNOWN_ISSUES #9: a Java thread starts once; the loop now owns a new one per start.
        PhysicsThread physics = new PhysicsThread(new Registry(), new PhysicsSystem());
        physics.setPaused(false);
        physics.init();
        assertTrue(physics.isRunning());
        physics.cleanup();
        assertFalse(physics.isRunning());

        assertDoesNotThrow(physics::init);
        assertTrue(physics.isRunning());
        physics.cleanup();
        assertFalse(physics.isRunning());
    }

    @Test
    void stoppingALoopThatIsNotRunningIsHarmless() {
        PhysicsThread physics = new PhysicsThread(new Registry(), new PhysicsSystem());
        assertDoesNotThrow(physics::cleanup);
        physics.init();
        physics.init();
        physics.cleanup();
        assertDoesNotThrow(physics::cleanup);
    }
}
