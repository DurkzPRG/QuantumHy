package com.durkz.quantumhy.effects;

/**
 * Pure per-player effect budget. A token bucket refilled at the budget rate (shrunk while the
 * client is strained) with a one-second burst. Close effects bypass it; once half the bucket is
 * spent, far effects go first so the ones the player is looking at survive.
 */
public final class EffectBudgetPolicy {

    /** Share of the budget kept while fully strained. */
    public static final double STRAINED_FLOOR = 0.4D;

    private EffectBudgetPolicy() {
    }

    public enum Verdict { ALLOW_PROTECTED, ALLOW, DROP_DISTANCE, DROP_BUDGET }

    /** Budget per second after strain: full when calm, {@link #STRAINED_FLOOR} of it when fully strained. */
    public static double rate(int budgetPerSecond, double strain) {
        double s = strain < 0.0D ? 0.0D : Math.min(1.0D, strain);
        return Math.max(1.0D, budgetPerSecond * (1.0D - (1.0D - STRAINED_FLOOR) * s));
    }

    /** Tokens after refilling for {@code elapsedNanos} at {@code rate} per second, capped at one second. */
    public static double refill(double tokens, long elapsedNanos, double rate) {
        if (elapsedNanos <= 0L) {
            return Math.min(tokens, rate);
        }
        return Math.min(rate, tokens + rate * (elapsedNanos / 1_000_000_000.0D));
    }

    /**
     * Decides one effect. Distances are squared blocks; {@code maxSq <= 0} disables the hard cut-off.
     * The caller consumes one token on {@link Verdict#ALLOW}.
     */
    public static Verdict decide(double distanceSq, double tokens, double capacity,
                                 double protectSq, double softSq, double maxSq) {
        if (distanceSq <= protectSq) {
            return Verdict.ALLOW_PROTECTED;
        }
        if (maxSq > 0.0D && distanceSq > maxSq) {
            return Verdict.DROP_DISTANCE;
        }
        if (tokens < 1.0D) {
            return Verdict.DROP_BUDGET;
        }
        if (tokens < capacity * 0.5D && distanceSq > softSq) {
            return Verdict.DROP_BUDGET;
        }
        return Verdict.ALLOW;
    }
}
