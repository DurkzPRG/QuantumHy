package com.durkz.quantumhy.view;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DistantUpdatePolicyTest {

    @Test
    void tiersByDistance() {
        assertEquals(1, DistantUpdatePolicy.interval(20 * 20, 32, 64, false));
        assertEquals(1, DistantUpdatePolicy.interval(32 * 32, 32, 64, false));
        assertEquals(2, DistantUpdatePolicy.interval(50 * 50, 32, 64, false));
        assertEquals(4, DistantUpdatePolicy.interval(100 * 100, 32, 64, false));
    }

    @Test
    void strainDoublesButNearStaysEveryTick() {
        assertEquals(1, DistantUpdatePolicy.interval(10 * 10, 32, 64, true));
        assertEquals(4, DistantUpdatePolicy.interval(50 * 50, 32, 64, true));
        assertEquals(DistantUpdatePolicy.MAX_INTERVAL, DistantUpdatePolicy.interval(200 * 200, 32, 64, true));
    }

    @Test
    void everyEntitySendsExactlyOncePerInterval() {
        for (int phase = 0; phase < 8; phase++) {
            int sends = 0;
            for (long tick = 1; tick <= 4; tick++) {
                if (DistantUpdatePolicy.allowed(tick, phase, 4)) {
                    sends++;
                }
            }
            assertEquals(1, sends);
        }
        assertTrue(DistantUpdatePolicy.allowed(7, 3, 1));
        assertFalse(DistantUpdatePolicy.allowed(1, 0, 2));
    }
}
