package com.durkz.quantumhy.view;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BuriedSectionPolicyTest {

    @Test
    void buriedFarSectionBelowPlayerIsReplaced() {
        // Surface at 110, player flying at 124, section 2 spans 64..95.
        assertTrue(BuriedSectionPolicy.replace(2, 110, 124.0, 6, 4));
    }

    @Test
    void nearRingGetsRealSectionsDownToNearDepth() {
        // Player at 124: section 2 (top 95) is within 48 blocks, section 1 (top 63) is not.
        assertFalse(BuriedSectionPolicy.replace(2, 110, 124.0, 4, 4));
        assertTrue(BuriedSectionPolicy.restore(2, 124.0, 3, 4));
        assertTrue(BuriedSectionPolicy.replace(1, 110, 124.0, 0, 4));
        assertFalse(BuriedSectionPolicy.restore(1, 124.0, 0, 4));
        // Walking down to 111 brings section 1 within reach (63 >= 111 - 48).
        assertTrue(BuriedSectionPolicy.restore(1, 111.0, 2, 4));
    }

    @Test
    void sectionReachingTheSurfaceIsKept() {
        // Valley floor at 90 inside the neighbourhood: section 2 (top 95) can be seen.
        assertFalse(BuriedSectionPolicy.replace(2, 90, 124.0, 8, 4));
        // Margin: top 95 needs a floor above 97.
        assertFalse(BuriedSectionPolicy.replace(2, 97, 124.0, 8, 4));
        assertTrue(BuriedSectionPolicy.replace(2, 98, 124.0, 8, 4));
    }

    @Test
    void playerUndergroundKeepsSectionsAroundThem() {
        // Player in a cave at y=70 next to section 2.
        assertFalse(BuriedSectionPolicy.replace(2, 110, 70.0, 8, 4));
        assertTrue(BuriedSectionPolicy.restore(2, 70.0, 8, 4));
        assertTrue(BuriedSectionPolicy.replace(1, 110, 90.0, 8, 4));
    }

    @Test
    void unknownSurfaceIsNeverReplaced() {
        assertFalse(BuriedSectionPolicy.replace(0, Integer.MIN_VALUE, 124.0, 10, 4));
    }

    @Test
    void helpers() {
        assertEquals(95, BuriedSectionPolicy.topY(2));
        assertEquals(60, BuriedSectionPolicy.lowest(new int[]{110, 60, 300}));
        assertEquals(Integer.MIN_VALUE, BuriedSectionPolicy.lowest(new int[0]));
        assertEquals(5, BuriedSectionPolicy.ring(10, -3, 5, 0));
    }
}
