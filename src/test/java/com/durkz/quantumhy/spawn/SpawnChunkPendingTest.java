package com.durkz.quantumhy.spawn;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SpawnChunkPendingTest {

    @Test
    void backlogGateIgnoresLightStreaming() {
        assertFalse(SpawnChunkPending.isBacklogged(0, 80));
        assertFalse(SpawnChunkPending.isBacklogged(1, 80));
        assertFalse(SpawnChunkPending.isBacklogged(79, 80));
    }

    @Test
    void backlogGateTripsAtThreshold() {
        assertTrue(SpawnChunkPending.isBacklogged(80, 80));
        assertTrue(SpawnChunkPending.isBacklogged(120, 80));
    }

    @Test
    void zeroThresholdMeansAnyLoading() {
        assertFalse(SpawnChunkPending.isBacklogged(0, 0));
        assertTrue(SpawnChunkPending.isBacklogged(1, 0));
    }
}
