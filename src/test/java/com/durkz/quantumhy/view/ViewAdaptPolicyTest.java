package com.durkz.quantumhy.view;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ViewAdaptPolicyTest {

    @Test
    void ratchetDoesNotExpandOnThePassAfterChunkLoadDrops() {
        double shrunk = ViewAdaptPolicy.combinedShrinkFraction(0.0D, 1.0D, 0.10D);
        assertEquals(1.0D, shrunk, 1e-9);

        double afterDrop = ViewAdaptPolicy.combinedShrinkFraction(0.0D, 0.0D, 0.10D);
        assertEquals(0.10D, afterDrop, 1e-9);

        double held = ViewAdaptPolicy.ratchetFrac(afterDrop, shrunk, true, false);
        assertEquals(1.0D, held, 1e-9);
    }

    @Test
    void ratchetAllowsExpandAfterHysteresis() {
        int calm = 0;
        calm = ViewAdaptPolicy.nextCalmPasses(calm, true);
        calm = ViewAdaptPolicy.nextCalmPasses(calm, true);
        assertTrue(ViewAdaptPolicy.canExpand(calm, 2));

        double held = ViewAdaptPolicy.ratchetFrac(0.10D, 1.0D, true, true);
        assertEquals(0.10D, held, 1e-9);
    }

    @Test
    void loadSpikeResetsCalmPasses() {
        int calm = ViewAdaptPolicy.nextCalmPasses(2, false);
        assertEquals(0, calm);
        assertFalse(ViewAdaptPolicy.canExpand(calm, 2));
    }

    @Test
    void rampDoesNotJumpFromMinToBaselineInOnePass() {
        assertEquals(7, ViewAdaptPolicy.rampToward(6, 13, 1, 2));
        assertEquals(11, ViewAdaptPolicy.rampToward(13, 6, 1, 2));
    }

    @Test
    void pressuredShrinkUsesFourChunks() {
        assertEquals(4, ViewAdaptPolicy.chunkShrinkCap(2, true));
        assertEquals(2, ViewAdaptPolicy.chunkShrinkCap(2, false));
        assertEquals(9, ViewAdaptPolicy.rampToward(13, 6, 1, ViewAdaptPolicy.chunkShrinkCap(2, true)));
    }

    @Test
    void densityZeroChunksIsInvalid() {
        assertFalse(ViewAdaptPolicy.densityValid(0, 0));
        assertFalse(ViewAdaptPolicy.densityValid(12, 0));
        assertTrue(ViewAdaptPolicy.densityValid(0, 49));
        assertFalse(ViewAdaptPolicy.densityValid(-1, 0));
    }

    @Test
    void pressureExitIgnoresLowAverageDuringLastTickSpike() {
        assertFalse(ViewAdaptPolicy.pressureBelowExit(30.0D, 266.0D, 43.0D, true));
        assertTrue(ViewAdaptPolicy.pressureBelowExit(30.0D, 266.0D, 43.0D, false));
        assertTrue(ViewAdaptPolicy.pressureBelowExit(30.0D, 20.0D, 43.0D, true));
        assertFalse(ViewAdaptPolicy.pressureBelowExit(50.0D, 20.0D, 43.0D, true));
    }

    @Test
    void fracFromRadiusTracksLiveValue() {
        assertEquals(1.0D, ViewAdaptPolicy.fracFromRadius(14, 6, 6), 1e-9);
        assertEquals(0.0D, ViewAdaptPolicy.fracFromRadius(14, 6, 14), 1e-9);
        assertEquals(0.125D, ViewAdaptPolicy.fracFromRadius(14, 6, 13), 1e-9);
    }
}
