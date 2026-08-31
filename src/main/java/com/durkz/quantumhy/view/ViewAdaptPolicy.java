package com.durkz.quantumhy.view;

/**
 * Pure adaptive-radius policy. No world/player types, so the ratchet and ramp can be unit-tested
 * without a Hytale server.
 */
public final class ViewAdaptPolicy {

    private ViewAdaptPolicy() {
    }

    /**
     * Combined shrink in {@code [0, 1]}: density and chunk-load take the max, then a baseline floor.
     */
    public static double combinedShrinkFraction(double densityFrac, double chunkLoadFrac, double baseline) {
        double frac = Math.max(densityFrac, chunkLoadFrac);
        if (baseline > 0) {
            frac = Math.max(frac, baseline);
        }
        return Math.min(1.0D, frac);
    }

    /**
     * Terrain is the last render lever to tighten. Entity streaming can react to the complete
     * density signal, while terrain only follows its upper range. Chunk-load pressure still
     * applies immediately because it represents active client meshing work.
     */
    public static double terrainShrinkFraction(double densityFrac, double chunkLoadFrac, double baseline) {
        double terrainDensity = densityFrac <= 0.60D ? 0.0D : (densityFrac - 0.60D) / 0.40D;
        return combinedShrinkFraction(terrainDensity, chunkLoadFrac, baseline);
    }

    /**
     * Chunk-load (and any other falling signal) may only <em>increase</em> shrink. A drop in
     * {@code rawFrac} is ignored until {@code canExpand} (load has been calm for the hysteresis
     * window). Shrink-up is always allowed.
     */
    public static double ratchetFrac(double rawFrac, double lastAppliedFrac, boolean hasLast, boolean canExpand) {
        double raw = clamp01(rawFrac);
        if (!hasLast) {
            return raw;
        }
        if (raw >= lastAppliedFrac - 1e-9) {
            return raw;
        }
        return canExpand ? raw : clamp01(lastAppliedFrac);
    }

    /** Count consecutive passes where loaded+loading stayed at or below the low threshold. */
    public static int nextCalmPasses(int currentCalmPasses, boolean loadIsCalm) {
        return loadIsCalm ? currentCalmPasses + 1 : 0;
    }

    public static boolean loadIsCalm(int loadedPlusLoading, int chunkLoadLow, boolean chunkLoadShrinkEnabled) {
        if (!chunkLoadShrinkEnabled) {
            return true;
        }
        return loadedPlusLoading <= chunkLoadLow;
    }

    public static boolean canExpand(int calmPasses, int hysteresisPasses) {
        return canExpand(calmPasses, hysteresisPasses, false, false, true, false);
    }

    /**
     * Expand is opt-in and conservative: never while MSPT-pressured, streaming, flying, or when
     * the density disk is only half-loaded (looks like open sky because chunks are not there yet).
     */
    public static boolean canExpand(int calmPasses, int hysteresisPasses, boolean pressured,
            boolean streaming, boolean sampleCovered, boolean movingFast) {
        if (pressured || streaming || movingFast || !sampleCovered) {
            return false;
        }
        return calmPasses >= Math.max(1, hysteresisPasses);
    }

    /**
     * Skip the 49-column density walk when it cannot change the expand decision (join, flight,
     * streaming, cached same chunk, or already at min with expand frozen).
     */
    public static boolean shouldSkipDensityScan(boolean firstPass, boolean movingFast, boolean streaming,
            boolean cacheHit, boolean atMin, boolean expandGatesExceptCoverage) {
        return firstPass || movingFast || streaming || cacheHit || (atMin && !expandGatesExceptCoverage);
    }

    /** Chebyshev chunk move of 2+ in one pass is travel/flight, not standing around. */
    public static boolean movingFast(boolean hasLastChunk, int lastX, int lastZ, int chunkX, int chunkZ) {
        if (!hasLastChunk) {
            return false;
        }
        int dx = Math.abs(chunkX - lastX);
        int dz = Math.abs(chunkZ - lastZ);
        return Math.max(dx, dz) > 1;
    }

    /** Bloom/sunshaft packets during a hitch make the hitch worse; wait for a healthy last tick. */
    public static boolean canTrimClientEffects(double msptLast, double maxLastTickMs) {
        return msptLast <= maxLastTickMs;
    }

    /** Columns in the density disk of {@code radius} (dx²+dz² ≤ r²). */
    public static int expectedScanChunks(int radius) {
        int r = Math.max(0, radius);
        int r2 = r * r;
        int n = 0;
        for (int dz = -r; dz <= r; dz++) {
            for (int dx = -r; dx <= r; dx++) {
                if (dx * dx + dz * dz <= r2) {
                    n++;
                }
            }
        }
        return Math.max(1, n);
    }

    /** At least half the scan disk must be loaded, or "open sky" is just missing terrain. */
    public static boolean densityCovered(int chunks, int radius) {
        return densityCoveredColumns(chunks, expectedScanChunks(radius));
    }

    /** Same coverage check with a precomputed number of scan columns. */
    public static boolean densityCoveredColumns(int chunks, int expectedChunks) {
        return chunks * 2 >= expectedChunks;
    }

    /**
     * Walk {@code current} toward {@code ideal} by at most {@code maxExpand} up or {@code maxShrink}
     * down per pass. Prevents 6→13 / 13→6 sawtooth.
     */
    public static int rampToward(int current, int ideal, int maxExpand, int maxShrink) {
        if (ideal > current) {
            int step = Math.max(0, maxExpand);
            return Math.min(ideal, current + step);
        }
        if (ideal < current) {
            int step = Math.max(0, maxShrink);
            return Math.max(ideal, current - step);
        }
        return current;
    }

    /** Under MSPT pressure, shrink faster (default 2 → 4 chunks/pass). */
    public static int chunkShrinkCap(int maxShrinkChunksPerPass, boolean pressured) {
        int base = Math.max(1, maxShrinkChunksPerPass);
        return pressured ? Math.max(4, base * 2) : base;
    }

    /** Entity-block shrink cap, scaled from the chunk expand/shrink ratio. */
    public static int entityShrinkCap(int maxExpandEntityBlocks, int maxExpandChunks, int maxShrinkChunks,
            boolean pressured) {
        int expand = Math.max(1, maxExpandEntityBlocks);
        int chunkExpand = Math.max(1, maxExpandChunks);
        int chunkShrink = chunkShrinkCap(maxShrinkChunks, pressured);
        return Math.max(expand, expand * chunkShrink / chunkExpand);
    }

    /**
     * Shrink fraction implied by a radius already written, so the ratchet tracks the live value
     * instead of the unclamped ideal.
     */
    public static double fracFromRadius(int base, int min, int current) {
        if (base <= min) {
            return 0.0D;
        }
        return clamp01((base - current) / (double) (base - min));
    }

    /** Empty or failed scans are not "open sky". */
    public static boolean densityValid(int rawEntities, int chunks) {
        return rawEntities >= 0 && chunks > 0;
    }

    /**
     * Exit pressure only when the 10s average is low, and optionally when the last tick is also
     * below the exit threshold (stops release during a 266ms spike).
     */
    public static boolean pressureBelowExit(double msptAvg, double msptLast, double exitThreshold,
            boolean requireLastTick) {
        if (msptAvg > exitThreshold) {
            return false;
        }
        return !requireLastTick || msptLast <= exitThreshold;
    }

    private static double clamp01(double value) {
        if (value < 0.0D) {
            return 0.0D;
        }
        if (value > 1.0D) {
            return 1.0D;
        }
        return value;
    }
}
