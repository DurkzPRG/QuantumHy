package com.durkz.quantumhy.view;

/**
 * Pure rules for sending a solid stand-in instead of a terrain section the player cannot see.
 * No Hytale types.
 *
 * A section qualifies when every block column around it has terrain above the section's top
 * (the lowest surface of its own column and the known neighbours, minus a margin) and it is below
 * the player: by {@link #BELOW_PLAYER} outside the near ring, by {@link #NEAR_DEPTH} inside it.
 * Caves and ores inside it are hidden by the terrain above, so a uniform stone section looks the
 * same from the surface. Open shafts and ravines lower the heightmap and so stay real; what is left
 * deep under the near ring can only be seen through roofed caves.
 */
public final class BuriedSectionPolicy {

    /** Blocks of terrain required above the section's top. */
    public static final int SURFACE_MARGIN = 2;

    /** Outside the near ring, the section's top must be this many blocks below the player's feet. */
    public static final int BELOW_PLAYER = 16;

    /** Inside the near ring, the section's top must be this many blocks below the player's feet. */
    public static final int NEAR_DEPTH = 48;

    /** Section height in blocks. */
    public static final int SECTION_BLOCKS = 32;

    private BuriedSectionPolicy() {
    }

    /** Top block y of section {@code sectionY}. */
    public static int topY(int sectionY) {
        return sectionY * SECTION_BLOCKS + SECTION_BLOCKS - 1;
    }

    /**
     * True when the section at {@code sectionY} may be replaced. {@code surfaceFloor} is the lowest
     * surface height around the column, or {@link Integer#MIN_VALUE} when unknown (never replaced).
     * {@code ringDistance} is the Chebyshev distance in chunks from the player's column.
     */
    public static boolean replace(int sectionY, int surfaceFloor, double playerY, int ringDistance, int nearChunks) {
        if (surfaceFloor == Integer.MIN_VALUE) {
            return false;
        }
        int top = topY(sectionY);
        return top < surfaceFloor - SURFACE_MARGIN && !restore(sectionY, playerY, ringDistance, nearChunks);
    }

    /** False once the player is close enough above, or below, to see into the section. */
    public static boolean keepHidden(int sectionY, double playerY) {
        return topY(sectionY) < playerY - BELOW_PLAYER;
    }

    /** True while a replaced section must be swapped back for the real one. */
    public static boolean restore(int sectionY, double playerY, int ringDistance, int nearChunks) {
        return !keepHidden(sectionY, playerY)
                || ringDistance <= nearChunks && topY(sectionY) >= playerY - NEAR_DEPTH;
    }

    /** Lowest value of a column heightmap, or {@link Integer#MIN_VALUE} when empty. */
    public static int lowest(int[] heightmap) {
        if (heightmap == null || heightmap.length == 0) {
            return Integer.MIN_VALUE;
        }
        int min = Integer.MAX_VALUE;
        for (int h : heightmap) {
            if (h < min) {
                min = h;
            }
        }
        return min;
    }

    /** Chebyshev distance between two chunk columns. */
    public static int ring(int x, int z, int px, int pz) {
        return Math.max(Math.abs(x - px), Math.abs(z - pz));
    }
}
