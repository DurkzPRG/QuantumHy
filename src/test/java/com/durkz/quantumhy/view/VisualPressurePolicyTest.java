package com.durkz.quantumhy.view;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class VisualPressurePolicyTest {

    @Test
    void preserves030PressureAtReducedRadius() {
        assertEquals(80.0D, VisualPressurePolicy.projectedCandidates(80, 64, 64), 1e-9);
        assertEquals(640.0D, VisualPressurePolicy.projectedCandidates(80, 32, 64), 1e-9);
        assertEquals(640.0D, VisualPressurePolicy.projectedCandidates(80, 8, 64), 1e-9);
        assertEquals(156.25D, VisualPressurePolicy.projectedCandidates(80, 64, 80), 1e-9);
        double projected = VisualPressurePolicy.projectedCandidates(20, 64, 128);
        assertEquals(160, projected);
        assertEquals(1, VisualPressurePolicy.entityShrinkFraction(
                VisualPressurePolicy.entityRatio(projected, 80)));
    }

    @Test
    void emptyOrInvalidRadiiNeverAmplifyObservedLoad() {
        assertEquals(0, VisualPressurePolicy.projectedCandidates(0, 8, 64));
        assertEquals(0, VisualPressurePolicy.projectedCandidates(-1, 8, 64));
        assertEquals(80, VisualPressurePolicy.projectedCandidates(80, 0, 64));
        assertEquals(80, VisualPressurePolicy.projectedCandidates(80, -1, 64));
        assertEquals(80, VisualPressurePolicy.projectedCandidates(80, 64, 0));
        assertEquals(80, VisualPressurePolicy.projectedCandidates(80, 64, 32));
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
