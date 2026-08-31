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
    void terrainWaitsForHighDensityWhileEntityLoadCanReactEarlier() {
        assertEquals(0.50D, ViewAdaptPolicy.combinedShrinkFraction(0.50D, 0.0D, 0.0D), 1e-9);
        assertEquals(0.0D, ViewAdaptPolicy.terrainShrinkFraction(0.50D, 0.0D, 0.0D), 1e-9);
        assertEquals(1.0D, ViewAdaptPolicy.terrainShrinkFraction(1.0D, 0.0D, 0.0D), 1e-9);
        assertEquals(0.75D, ViewAdaptPolicy.terrainShrinkFraction(0.0D, 0.75D, 0.0D), 1e-9);
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

    @Test
    void expandFrozenWhilePressuredStreamingOrFlying() {
        assertFalse(ViewAdaptPolicy.canExpand(8, 2, true, false, true, false));
        assertFalse(ViewAdaptPolicy.canExpand(8, 2, false, true, true, false));
        assertFalse(ViewAdaptPolicy.canExpand(8, 2, false, false, true, true));
        assertTrue(ViewAdaptPolicy.canExpand(8, 2, false, false, true, false));
    }

    @Test
    void expandFrozenOnPartialDensityDisk() {
        assertEquals(49, ViewAdaptPolicy.expectedScanChunks(4));
        assertFalse(ViewAdaptPolicy.densityCovered(20, 4));
        assertFalse(ViewAdaptPolicy.densityCovered(12, 4));
        assertTrue(ViewAdaptPolicy.densityCovered(25, 4));
        assertFalse(ViewAdaptPolicy.canExpand(8, 2, false, false, false, false));
    }

    @Test
    void movingFastIsChebyshevTwoChunks() {
        assertFalse(ViewAdaptPolicy.movingFast(false, 0, 0, 10, 10));
        assertFalse(ViewAdaptPolicy.movingFast(true, 0, 0, 1, 1));
        assertTrue(ViewAdaptPolicy.movingFast(true, 0, 0, 2, 0));
        assertTrue(ViewAdaptPolicy.movingFast(true, 5, 5, 5, 8));
    }

    @Test
    void skipScanOnJoinFlightStreamingAndMinHold() {
        assertTrue(ViewAdaptPolicy.shouldSkipDensityScan(true, false, false, false, false, true));
        assertTrue(ViewAdaptPolicy.shouldSkipDensityScan(false, true, false, false, false, true));
        assertTrue(ViewAdaptPolicy.shouldSkipDensityScan(false, false, true, false, false, true));
        assertTrue(ViewAdaptPolicy.shouldSkipDensityScan(false, false, false, true, false, true));
        assertTrue(ViewAdaptPolicy.shouldSkipDensityScan(false, false, false, false, true, false));
        assertFalse(ViewAdaptPolicy.shouldSkipDensityScan(false, false, false, false, true, true));
        assertFalse(ViewAdaptPolicy.shouldSkipDensityScan(false, false, false, false, false, true));
    }

    @Test
    void effectTrimWaitsForHealthyLastTick() {
        assertFalse(ViewAdaptPolicy.canTrimClientEffects(2972.0D, 50.0D));
        assertFalse(ViewAdaptPolicy.canTrimClientEffects(302.0D, 50.0D));
        assertTrue(ViewAdaptPolicy.canTrimClientEffects(5.3D, 50.0D));
    }
}
