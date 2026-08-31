package com.durkz.quantumhy.view;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class VisualPressurePolicyTest {

    @Test
    void projectsCandidatesBySphericalVolume() {
        assertEquals(80.0D, VisualPressurePolicy.projectedCandidates(80, 64, 64), 1e-9);
        assertEquals(640.0D, VisualPressurePolicy.projectedCandidates(80, 32, 64), 1e-9);
        assertEquals(640.0D, VisualPressurePolicy.projectedCandidates(80, 8, 64), 1e-9);
    }

    @Test
    void emergencyUsesSeparateEntityAndBacklogBoundaries() {
        assertEquals(1.0D, VisualPressurePolicy.emergencyScore(1.5D, 0.0D), 1e-9);
        assertEquals(1.0D, VisualPressurePolicy.emergencyScore(0.0D, 2.0D), 1e-9);
        assertEquals(0.5D, VisualPressurePolicy.emergencyTerrainFraction(1.0D), 1e-9);
        assertEquals(1.0D, VisualPressurePolicy.emergencyTerrainFraction(2.0D), 1e-9);
    }

    @Test
    void entityPressureStartsAtBudgetAndIsFullAtDoubleBudget() {
        assertEquals(0.0D, VisualPressurePolicy.entityShrinkFraction(1.0D), 1e-9);
        assertEquals(0.5D, VisualPressurePolicy.entityShrinkFraction(1.5D), 1e-9);
        assertEquals(1.0D, VisualPressurePolicy.entityShrinkFraction(2.0D), 1e-9);
    }
}
