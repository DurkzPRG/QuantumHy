package com.durkz.quantumhy.disk;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashSet;
import java.util.Set;
import org.junit.jupiter.api.Test;

class DiskGeometryTest {
    @Test
    void sphereMaskMatchesNativeFormula() {
        for (int r = 0; r <= 14; r++) {
            for (int py = -4; py <= 14; py++) {
                for (int d = 0; d <= 220; d++) {
                    int mask = DiskGeometry.sphereMask(d, py, r);
                    for (int y = 0; y < DiskGeometry.SECTIONS; y++) {
                        boolean inside = d + (y - py) * (y - py) <= r * r;
                        assertEquals(inside, (mask & (1 << y)) != 0, "r=" + r + " py=" + py + " d=" + d + " y=" + y);
                    }
                }
            }
        }
    }

    @Test
    void bandNeverOverlapsNativeAndCoversOuterExactly() {
        for (int nativeRadius = 1; nativeRadius <= 12; nativeRadius++) {
            for (int outer = nativeRadius; outer <= 16; outer++) {
                for (int py = -2; py <= 12; py++) {
                    for (int d = 0; d <= 260; d++) {
                        int band = DiskGeometry.bandMask(d, py, nativeRadius, outer);
                        int inner = DiskGeometry.sphereMask(d, py, nativeRadius);
                        assertEquals(0, band & inner);
                        assertEquals(DiskGeometry.sphereMask(d, py, outer), band | inner);
                    }
                }
            }
        }
    }

    @Test
    void verticalSectionsNearThePlayerBelongToTheBand() {
        int band = DiskGeometry.bandMask(25, 3, 6, 12);
        assertEquals(0b1110000000, band);
    }

    @Test
    void ringIsNearestFirstAndRespectsBounds() {
        long[] ring = DiskGeometry.ringColumns(100, -50, 3, 9);
        Set<Long> seen = new HashSet<>();
        int last = -1;
        for (long index : ring) {
            int d = DiskGeometry.columnDistanceSq(100, -50, DiskGeometry.x(index), DiskGeometry.z(index));
            assertTrue(d > 9 && d <= 81);
            assertTrue(d >= last);
            assertTrue(seen.add(index));
            last = d;
        }
        int expected = 0;
        for (int dx = -9; dx <= 9; dx++) for (int dz = -9; dz <= 9; dz++) {
            int d = dx * dx + dz * dz;
            if (d > 9 && d <= 81) expected++;
        }
        assertEquals(expected, ring.length);
        assertEquals(113, DiskGeometry.ringColumns(0, 0, -1, 6).length);
    }

    @Test
    void nativeRadiusKeepsSimulationMarginAndNeverExceedsVisible() {
        assertEquals(6, DiskGeometry.nativeRadius(6, 4, 12));
        assertEquals(9, DiskGeometry.nativeRadius(6, 8, 12));
        assertEquals(5, DiskGeometry.nativeRadius(6, 4, 5));
        assertEquals(1, DiskGeometry.nativeRadius(6, 4, 0));
    }

    @Test
    void indexRoundTripsNegativeCoordinates() {
        for (int x : new int[] {-100000, -1, 0, 1, 31, 99999}) {
            for (int z : new int[] {-77777, -1, 0, 1, 32, 123456}) {
                long index = DiskGeometry.index(x, z);
                assertEquals(x, DiskGeometry.x(index));
                assertEquals(z, DiskGeometry.z(index));
                assertEquals(((long) x << 32) | (z & 0xFFFFFFFFL), index);
            }
        }
    }
}
