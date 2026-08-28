package com.durkz.quantumhy.view;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class DensityScanPlanTest {

    @Test
    void preservesTheDiskSizeAndRingWeightFormula() {
        DensityScanPlan plan = DensityScanPlan.of(4, true, 0.55D);

        assertEquals(49, plan.columns());
        for (int i = 0; i < plan.columns(); i++) {
            int dx = plan.dx(i);
            int dz = plan.dz(i);
            double distance = Math.sqrt((double) dx * dx + (double) dz * dz);
            double expected = 1.0D - Math.min(1.0D, distance / 4.0D) * (1.0D - 0.55D);
            assertEquals(expected, plan.weight(i), 0.0D);
        }
    }

    @Test
    void flatWeightingKeepsEveryColumnAtOne() {
        DensityScanPlan plan = DensityScanPlan.of(3, false, 0.55D);

        assertEquals(ViewAdaptPolicy.expectedScanChunks(3), plan.columns());
        for (int i = 0; i < plan.columns(); i++) {
            assertEquals(1.0D, plan.weight(i), 0.0D);
        }
    }
}
