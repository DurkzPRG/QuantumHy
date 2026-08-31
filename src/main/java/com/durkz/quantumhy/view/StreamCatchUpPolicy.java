package com.durkz.quantumhy.view;

/** Pure chunk send-rate policy, isolated from Hytale types for deterministic tests. */
public final class StreamCatchUpPolicy {

    public enum Tier {
        CRUISE,
        CATCH_UP,
        PROTECT
    }

    public enum ProtectionCause {
        NONE("none"),
        GOVERNOR("governor"),
        LAST_TICK("last-tick"),
        AVERAGE("average"),
        METRIC_UNAVAILABLE("metric-unavailable");

        private final String label;

        ProtectionCause(String label) {
            this.label = label;
        }

        public String label() {
            return label;
        }
    }

    public record Outcome(
            Tier tier,
            int perSecond,
            int perTick,
            long holdUntilMs,
            int moveScore,
            int protectCalmSamples,
            ProtectionCause protectionCause
    ) {
    }

    static final int MOVE_STEP_SCORE = 4;
    static final int MOVE_IDLE_DECAY = 1;
    static final int MOVE_SCORE_CAP = 16;
    static final int MOVE_SCORE_ENTER = 6;
    static final int PROTECT_RECOVERY_SAMPLES = 4;

    private StreamCatchUpPolicy() {
    }

    public static int chebyshev(boolean hasLastChunk, int lastX, int lastZ, int chunkX, int chunkZ) {
        if (!hasLastChunk) {
            return 0;
        }
        return Math.max(Math.abs(chunkX - lastX), Math.abs(chunkZ - lastZ));
    }

    public static int nextMoveScore(int chebyshev, int previous) {
        int score = chebyshev >= 1
                ? previous + MOVE_STEP_SCORE * chebyshev
                : previous - MOVE_IDLE_DECAY;
        if (score < 0) {
            return 0;
        }
        return Math.min(MOVE_SCORE_CAP, score);
    }

    public static boolean wantsCatchUp(boolean enabled, int chebyshev, int moveScore) {
        return enabled && (chebyshev >= 2 || moveScore >= MOVE_SCORE_ENTER);
    }

    public static boolean exitReady(int loading, int backlogThreshold, int chebyshev) {
        boolean loadingCalm = backlogThreshold <= 0 ? loading <= 0 : loading < backlogThreshold;
        return loadingCalm && chebyshev <= 1;
    }

    public static int clampRate(int desired, int baseline) {
        if (desired <= 0) {
            return Math.max(0, baseline);
        }
        if (baseline <= 0) {
            return desired;
        }
        return Math.min(desired, baseline);
    }

    static int protectedRate(int desired, double multiplier) {
        if (desired <= 0) {
            return desired;
        }
        double safeMultiplier = Math.max(0.05D, Math.min(1.0D, multiplier));
        return Math.max(1, (int) Math.round(desired * safeMultiplier));
    }

    public static Outcome next(
            boolean catchUpEnabled,
            boolean pressureGovernorEnabled,
            boolean governorPressured,
            boolean metricAvailable,
            double msptAverage,
            double msptLast,
            double pressureEnter,
            double pressureExit,
            double pressureRateMultiplier,
            int chebyshev,
            int loading,
            int previousMoveScore,
            int previousProtectCalmSamples,
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
        int moveScore = nextMoveScore(chebyshev, previousMoveScore);
        ProtectionCause overload = overloadCause(
                pressureGovernorEnabled, governorPressured, metricAvailable,
                msptAverage, msptLast, pressureEnter);

        Tier nextTier;
        long nextHold = holdUntilMs;
        int calmSamples = 0;
        ProtectionCause reportedCause = overload;

        if (overload != ProtectionCause.NONE) {
            nextTier = Tier.PROTECT;
            nextHold = 0L;
        } else if (current == Tier.PROTECT) {
            boolean recovered = !pressureGovernorEnabled
                    || (metricAvailable && msptAverage <= pressureExit && msptLast <= pressureExit);
            calmSamples = recovered ? previousProtectCalmSamples + 1 : 0;
            if (calmSamples < PROTECT_RECOVERY_SAMPLES) {
                nextTier = Tier.PROTECT;
                reportedCause = metricAvailable
                        ? ProtectionCause.NONE
                        : ProtectionCause.METRIC_UNAVAILABLE;
                nextHold = 0L;
            } else {
                nextTier = Tier.CRUISE;
                calmSamples = 0;
                nextHold = 0L;
            }
        } else if (pressureGovernorEnabled && !metricAvailable) {
            nextTier = Tier.CRUISE;
            nextHold = 0L;
            reportedCause = ProtectionCause.METRIC_UNAVAILABLE;
        } else {
            boolean healthy = !pressureGovernorEnabled
                    || (msptAverage <= pressureExit && msptLast <= pressureExit);
            boolean wantsCatchUp = healthy && wantsCatchUp(catchUpEnabled, chebyshev, moveScore);
            if (!catchUpEnabled || !healthy) {
                nextTier = Tier.CRUISE;
                nextHold = 0L;
            } else if (wantsCatchUp) {
                nextTier = Tier.CATCH_UP;
                nextHold = 0L;
            } else if (current == Tier.CATCH_UP) {
                if (exitReady(loading, backlogThreshold, chebyshev)) {
                    if (holdUntilMs <= 0L) {
                        nextTier = Tier.CATCH_UP;
                        nextHold = nowMs + Math.max(0, holdMs);
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
        }

        int desiredS;
        int desiredT;
        if (nextTier == Tier.CATCH_UP) {
            desiredS = catchUpPerSecond;
            desiredT = catchUpPerTick;
        } else if (nextTier == Tier.PROTECT) {
            desiredS = protectedRate(cruisePerSecond, pressureRateMultiplier);
            desiredT = protectedRate(cruisePerTick, pressureRateMultiplier);
        } else {
            desiredS = cruisePerSecond;
            desiredT = cruisePerTick;
        }
        return new Outcome(
                nextTier,
                clampRate(desiredS, baselinePerSecond),
                clampRate(desiredT, baselinePerTick),
                nextHold,
                moveScore,
                calmSamples,
                reportedCause);
    }

    private static ProtectionCause overloadCause(boolean pressureGovernorEnabled,
            boolean governorPressured, boolean metricAvailable, double msptAverage,
            double msptLast, double pressureEnter) {
        if (pressureGovernorEnabled && governorPressured) {
            return ProtectionCause.GOVERNOR;
        }
        if (!pressureGovernorEnabled || !metricAvailable) {
            return ProtectionCause.NONE;
        }
        if (msptLast >= pressureEnter) {
            return ProtectionCause.LAST_TICK;
        }
        if (msptAverage >= pressureEnter) {
            return ProtectionCause.AVERAGE;
        }
        return ProtectionCause.NONE;
    }
}
