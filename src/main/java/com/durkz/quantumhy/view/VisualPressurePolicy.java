package com.durkz.quantumhy.view;

/** Pure math for server-visible render pressure. */
public final class VisualPressurePolicy {

    private static final double MAX_VOLUME_SCALE = 8.0D;

    private VisualPressurePolicy() {
    }

    /** Project the observed entity count back to the original spherical entity radius. */
    public static double projectedCandidates(int observed, int currentRadius, int originalRadius) {
        if (observed <= 0 || currentRadius <= 0 || originalRadius <= currentRadius) {
            return Math.max(0, observed);
        }
        double ratio = originalRadius / (double) currentRadius;
        double volumeScale = Math.min(MAX_VOLUME_SCALE, ratio * ratio * ratio);
        return observed * volumeScale;
    }

    public static double entityRatio(double projectedCandidates, int visibleBudget) {
        return visibleBudget <= 0 ? 0.0D : projectedCandidates / visibleBudget;
    }

    public static double backlogRatio(int loadingSections, int backlogBudget) {
        return backlogBudget <= 0 ? 0.0D : Math.max(0, loadingSections) / (double) backlogBudget;
    }

    /** 1.0 means the sustained emergency entry boundary. */
    public static double emergencyScore(double entityRatio, double backlogRatio) {
        return Math.max(entityRatio / 1.5D, backlogRatio / 2.0D);
    }

    /** Candidate load starts tightening entities at budget and reaches full strength at 2x budget. */
    public static double entityShrinkFraction(double entityRatio) {
        return smoothstep(entityRatio, 1.0D, 2.0D);
    }

    /** Emergency terrain starts at half strength at entry and reaches full strength at 2x entry. */
    public static double emergencyTerrainFraction(double emergencyScore) {
        if (emergencyScore <= 0.0D) {
            return 0.0D;
        }
        return Math.min(1.0D, 0.5D * emergencyScore);
    }

    public static double ema(double previous, double sample, double alpha, boolean hasPrevious) {
        if (!hasPrevious) {
            return sample;
        }
        double boundedAlpha = Math.max(0.0D, Math.min(1.0D, alpha));
        return boundedAlpha * sample + (1.0D - boundedAlpha) * previous;
    }

    private static double smoothstep(double value, double low, double high) {
        if (value <= low) {
            return 0.0D;
        }
        if (value >= high) {
            return 1.0D;
        }
        double t = (value - low) / (high - low);
        return t * t * (3.0D - 2.0D * t);
    }
}
