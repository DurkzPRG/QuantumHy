package com.durkz.quantumhy.view;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DensityVerticalWindowTest {

    @Test
    void keepsSectionsOverlappingVerticalWindow() {
        // Player at Y=100, window ±32 → [68, 132]. Section 3 = [96, 128).
        assertTrue(ClientViewRadiusController.sectionOverlapsVerticalWindow(3, 100.0D, 32));
    }

    @Test
    void skipsSectionsOutsideVerticalWindow() {
        // Section 0 = [0, 32) is entirely below [68, 132].
        assertFalse(ClientViewRadiusController.sectionOverlapsVerticalWindow(0, 100.0D, 32));
        // Section 7 = [224, 256) is entirely above.
        assertFalse(ClientViewRadiusController.sectionOverlapsVerticalWindow(7, 100.0D, 32));
    }
}
