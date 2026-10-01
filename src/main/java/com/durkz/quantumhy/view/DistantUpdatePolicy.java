package com.durkz.quantumhy.view;

/**
 * Pure send-rate tiers for transform-only updates of distant entities. No Hytale types.
 */
public final class DistantUpdatePolicy {

    /** Upper bound on the interval, in ticks, even for a strained client. */
    public static final int MAX_INTERVAL = 8;

    private DistantUpdatePolicy() {
    }

    /**
     * Ticks between transform updates for an entity at {@code distanceSq} (squared blocks): every
     * tick inside {@code nearBlocks}, every 2nd up to {@code farBlocks}, every 4th beyond. A strained
     * client doubles the interval, capped at {@link #MAX_INTERVAL}.
     */
    public static int interval(double distanceSq, int nearBlocks, int farBlocks, boolean strained) {
        double near = Math.max(0, nearBlocks);
        double far = Math.max(near, farBlocks);
        int base;
        if (distanceSq <= near * near) {
            return 1;
        } else if (distanceSq <= far * far) {
            base = 2;
        } else {
            base = 4;
        }
        return strained ? Math.min(MAX_INTERVAL, base * 2) : base;
    }

    /**
     * True on the ticks an entity may send. {@code phase} spreads entities across the interval so
     * they don't all send on the same tick.
     */
    public static boolean allowed(long tick, int phase, int interval) {
        if (interval <= 1) {
            return true;
        }
        return Math.floorMod(tick + phase, interval) == 0;
    }
}
