package com.durkz.quantumhy.disk;

/**
 * Section geometry shared with the native tracker: a section is inside a radius when dx² + dy² + dz² <= r²
 * (section units), a column is retained when dx² + dz² <= r². Columns hold sections 0..9 on non-cubic storage.
 */
final class DiskGeometry {
    static final int SECTIONS = 10;
    static final int ALL_SECTIONS = (1 << SECTIONS) - 1;

    private DiskGeometry() {
    }

    static int columnDistanceSq(int px, int pz, int x, int z) {
        long dx = (long) x - px;
        long dz = (long) z - pz;
        return (int) Math.min(Integer.MAX_VALUE, dx * dx + dz * dz);
    }

    /** Bitmask of sections inside the sphere of {@code radius} around the player's section. */
    static int sphereMask(int columnDistanceSq, int py, int radius) {
        long rsq = (long) radius * radius;
        if (columnDistanceSq > rsq) return 0;
        int mask = 0;
        for (int y = 0; y < SECTIONS; y++) {
            long dy = (long) y - py;
            if (columnDistanceSq + dy * dy <= rsq) mask |= 1 << y;
        }
        return mask;
    }

    /** Sections the disk band owns: inside the outer sphere, outside the native sphere. */
    static int bandMask(int columnDistanceSq, int py, int nativeRadius, int outerRadius) {
        return sphereMask(columnDistanceSq, py, outerRadius) & ~sphereMask(columnDistanceSq, py, nativeRadius);
    }

    /** Effective native radius: never below simulation + 1, never above what the player can see. */
    static int nativeRadius(int configured, int simulation, int visible) {
        int floor = Math.max(configured, simulation + 1);
        return Math.max(1, Math.min(floor, visible));
    }

    /** Column indices inside {@code outer} and outside {@code inner} (2D), nearest first. */
    static long[] ringColumns(int px, int pz, int inner, int outer) {
        if (outer <= 0) return new long[0];
        long innerSq = (long) Math.max(inner, -1) * Math.max(inner, -1);
        long outerSq = (long) outer * outer;
        int count = 0;
        long[] keys = new long[(2 * outer + 1) * (2 * outer + 1)];
        for (int dx = -outer; dx <= outer; dx++) {
            for (int dz = -outer; dz <= outer; dz++) {
                long d = (long) dx * dx + (long) dz * dz;
                if (d > outerSq || (inner >= 0 && d <= innerSq)) continue;
                keys[count++] = (d << 32) | ((long) (dx + outer) << 16) | (dz + outer);
            }
        }
        java.util.Arrays.sort(keys, 0, count);
        long[] columns = new long[count];
        for (int i = 0; i < count; i++) {
            int dx = (int) ((keys[i] >>> 16) & 0xFFFF) - outer;
            int dz = (int) (keys[i] & 0xFFFF) - outer;
            columns[i] = index(px + dx, pz + dz);
        }
        return columns;
    }

    static long index(int x, int z) {
        return ((long) x << 32) | (z & 0xFFFFFFFFL);
    }

    static int x(long index) {
        return (int) (index >> 32);
    }

    static int z(long index) {
        return (int) index;
    }
}
