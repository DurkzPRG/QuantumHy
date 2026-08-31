package com.durkz.quantumhy.view;

/**
 * Pure chunk send-rate policy. No world/player types, so enter/exit/hold/clamp can be unit-tested
 * without a Hytale server.
 */
public final class StreamCatchUpPolicy {

    public enum Tier {
        CRUISE,
        CATCH_UP
    }

    public record Outcome(
            Tier tier,
            int perSecond,
            int perTick,
            long holdUntilMs,
            int consecutiveGrowing,
            int moveScore
    ) {
    }

    /** One chunk step in a 250ms tick. Creative fly sprint is ~42 blocks/s: one chunk per ~3 ticks. */
    static final int MOVE_STEP_SCORE = 4;
    static final int MOVE_IDLE_DECAY = 1;
    static final int MOVE_SCORE_CAP = 16;
    /** Two chunk crossings about 750ms apart. A single walk step stays at 4 and never enters. */
    static final int MOVE_SCORE_ENTER = 6;

    private StreamCatchUpPolicy() {
    }

    /** Chebyshev distance in chunks since the last stream tick. */
    public static int chebyshev(boolean hasLastChunk, int lastX, int lastZ, int chunkX, int chunkZ) {
        if (!hasLastChunk) {
            return 0;
        }
        return Math.max(Math.abs(chunkX - lastX), Math.abs(chunkZ - lastZ));
    }

    /**
     * Count consecutive ticks where {@code loading} increased. First tick after a drop or with no
     * history is 0.
     */
    public static int nextGrowingCount(boolean hasLastLoading, int lastLoading, int loading, int previous) {
        if (!hasLastLoading) {
            return 0;
        }
        return loading > lastLoading ? previous + 1 : 0;
    }

    /**
     * Leaky score of recent chunk motion. A lone step is not enough; a second crossing ~750ms later
     * (Hytale fly sprint) crosses {@link #MOVE_SCORE_ENTER}.
     */
    public static int nextMoveScore(int chebyshev, int previous) {
        int score = chebyshev >= 1
                ? previous + MOVE_STEP_SCORE * chebyshev
                : previous - MOVE_IDLE_DECAY;
        if (score < 0) {
            return 0;
        }
        return Math.min(MOVE_SCORE_CAP, score);
    }

    /**
     * Catch-up is opt-in and never under MSPT pressure. A single chunk step does not count.
     */
    public static boolean wantsCatchUp(boolean enabled, boolean pressured, int chebyshev, int loading,
            int growingCount, int backlogThreshold) {
        return wantsCatchUp(enabled, pressured, chebyshev, loading, growingCount, 0, backlogThreshold);
    }

    public static boolean wantsCatchUp(boolean enabled, boolean pressured, int chebyshev, int loading,
            int growingCount, int moveScore, int backlogThreshold) {
        if (!enabled || pressured) {
            return false;
        }
        if (chebyshev >= 2) {
            return true;
        }
        if (moveScore >= MOVE_SCORE_ENTER) {
            return true;
        }
        if (growingCount >= 2) {
            return true;
        }
        return backlogThreshold > 0 && loading >= backlogThreshold;
    }

    public static boolean exitReady(int loading, int backlogThreshold, int chebyshev) {
        boolean loadingCalm = backlogThreshold <= 0 ? loading <= 0 : loading < backlogThreshold;
        return loadingCalm && chebyshev <= 1;
    }

    /**
     * Never write above the connection baseline captured before QuantumHy first touched the tracker.
     * {@code desired <= 0} means restore that baseline (engine/connection default).
     */
    public static int clampRate(int desired, int baseline) {
        if (desired <= 0) {
            return Math.max(0, baseline);
        }
        if (baseline <= 0) {
            return desired;
        }
        return Math.min(desired, baseline);
    }

    public static Outcome next(
            boolean enabled,
            boolean pressured,
            int chebyshev,
            int loading,
            boolean hasLastLoading,
            int lastLoading,
            int prevGrowing,
            int prevMoveScore,
            Tier current,
            long nowMs,
            long holdUntilMs,
            int backlogThreshold,
            int holdMs,
            int cruisePerSecond,
            int cruisePerTick,
            int catchUpPerSecond,
            int catchUpPerTick,
            int baselinePerSecond,
            int baselinePerTick
    ) {
        int growing = nextGrowingCount(hasLastLoading, lastLoading, loading, prevGrowing);
        int moveScore = nextMoveScore(chebyshev, prevMoveScore);
        boolean want = wantsCatchUp(enabled, pressured, chebyshev, loading, growing, moveScore, backlogThreshold);

        Tier nextTier = current;
        long nextHold = holdUntilMs;

        if (pressured || !enabled) {
            nextTier = Tier.CRUISE;
            nextHold = 0L;
        } else if (want) {
            nextTier = Tier.CATCH_UP;
            nextHold = 0L;
        } else if (current == Tier.CATCH_UP) {
            if (exitReady(loading, backlogThreshold, chebyshev)) {
                if (holdUntilMs <= 0L) {
                    nextHold = nowMs + Math.max(0, holdMs);
                    nextTier = Tier.CATCH_UP;
                } else if (nowMs < holdUntilMs) {
                    nextTier = Tier.CATCH_UP;
                } else {
                    nextTier = Tier.CRUISE;
                    nextHold = 0L;
                }
            } else {
                nextTier = Tier.CATCH_UP;
                nextHold = 0L;
            }
        } else {
            nextTier = Tier.CRUISE;
            nextHold = 0L;
        }

        int desiredS = nextTier == Tier.CATCH_UP ? catchUpPerSecond : cruisePerSecond;
        int desiredT = nextTier == Tier.CATCH_UP ? catchUpPerTick : cruisePerTick;
        return new Outcome(
                nextTier,
                clampRate(desiredS, baselinePerSecond),
                clampRate(desiredT, baselinePerTick),
                nextHold,
                growing,
                moveScore);
    }
}
