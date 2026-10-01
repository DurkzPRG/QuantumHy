package com.durkz.quantumhy.view;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
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

    @Test
    void sectionOfFloorsIncludingNegativeHeights() {
        assertEquals(3, ClientViewRadiusController.sectionOf(100.0D));
        assertEquals(0, ClientViewRadiusController.sectionOf(0.0D));
        assertEquals(-1, ClientViewRadiusController.sectionOf(-0.5D));
        assertEquals(-2, ClientViewRadiusController.sectionOf(-33.0D));
        // 0.7 cubic worlds can go above the old 10-section cap.
        assertEquals(12, ClientViewRadiusController.sectionOf(400.0D));
    }
}
