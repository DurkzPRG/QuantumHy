package com.durkz.quantumhy.view;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class DiskRadiusPolicyTest {
    @Test
    void anIdleWorldHasNoPermanentResidencyCut() {
        double fraction = DiskRadiusPolicy.serverTerrainFraction(0, 0);
        assertEquals(0.0, fraction);
        assertEquals(6, DiskRadiusPolicy.nativeTarget(6, 6, 4, fraction, true, 1, 2));
        assertEquals(1.0, DiskRadiusPolicy.serverTerrainFraction(1, 0));
        assertEquals(1.0, DiskRadiusPolicy.serverTerrainFraction(0, 1));
    }

    @Test
    void maskedClientPressureDoesNotCountAsRecovery() {
        assertFalse(DiskRadiusPolicy.visibleRecoveryAllowed(true, .8, true, 0, false));
        assertFalse(DiskRadiusPolicy.visibleRecoveryAllowed(true, 0, false, .5, false));
        assertFalse(DiskRadiusPolicy.visibleRecoveryAllowed(true, 0, false, 0, true));
        assertTrue(DiskRadiusPolicy.visibleRecoveryAllowed(true, 0, false, 0, false));
        assertTrue(DiskRadiusPolicy.visibleRecoveryAllowed(false, 1, true, 1, true));
    }

    @Test
    void serverAndMemoryPressureOnlyTightenResidency() {
        assertEquals(0.0, DiskRadiusPolicy.visibleFraction(true, 0, true, .5, true));
        assertEquals(.5, DiskRadiusPolicy.nativeFraction(.1, true, true, true, .25));
        assertEquals(.75, DiskRadiusPolicy.nativeFraction(.75, true, true, false, 0));
        assertEquals(0.0, DiskRadiusPolicy.nativeFraction(1, false, false, true, 1));
    }

    @Test
    void clientLoopDelayCanLimitVisibilityOnlyWithoutServerOrStreamingConfounders() {
        assertEquals(.4, DiskRadiusPolicy.visibleFraction(true, .8, false, 0, false));
        assertEquals(0.0, DiskRadiusPolicy.visibleFraction(true, .8, true, 0, false));
        assertEquals(0.0, DiskRadiusPolicy.visibleFraction(true, .8, false, .25, false));
        assertEquals(0.0, DiskRadiusPolicy.visibleFraction(true, .8, false, 0, true));
        assertEquals(0.0, DiskRadiusPolicy.visibleFraction(false, 1, false, 0, false));
    }

    @Test
    void residencyNeverCrossesSimulationMarginAndRecoveryNeedsCalm() {
        assertEquals(5, DiskRadiusPolicy.nativeTarget(6, 6, 4, 1, false, 1, 2));
        assertEquals(5, DiskRadiusPolicy.nativeTarget(6, 6, 4, .5, false, 1, 2));
        assertEquals(5, DiskRadiusPolicy.nativeTarget(6, 6, 4, .1, false, 1, 2));
        assertEquals(5, DiskRadiusPolicy.nativeTarget(5, 6, 4, 0, false, 1, 2));
        assertEquals(6, DiskRadiusPolicy.nativeTarget(5, 6, 4, 0, true, 1, 2));
        assertEquals(9, DiskRadiusPolicy.nativeTarget(6, 9, 8, 1, false, 1, 2));
        assertEquals(3, DiskRadiusPolicy.nativeTarget(3, 3, 4, 1, false, 1, 2));
    }

    @Test
    void nativeCutLeavesRequestedVisibilityOutOfTheCalculation() {
        for (int ceiling = 6; ceiling <= 32; ceiling++) {
            int target = DiskRadiusPolicy.nativeTarget(ceiling, ceiling, 4, 1, false, 1, 32);
            assertEquals(5, target);
            assertEquals(0.0, DiskRadiusPolicy.visibleFraction(true, 0, false, 0, false));
        }
    }
}
