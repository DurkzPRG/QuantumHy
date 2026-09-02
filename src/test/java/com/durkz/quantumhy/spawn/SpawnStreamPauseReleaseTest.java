package com.durkz.quantumhy.spawn;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SpawnStreamPauseReleaseTest {

    @Test
    void releaseRecognizesOnlyAnActiveEngineCooldown() {
        assertFalse(SpawnStreamPauseSystem.shouldReleaseCooldown(0L));
        assertTrue(SpawnStreamPauseSystem.shouldReleaseCooldown(123L));
    }
}
