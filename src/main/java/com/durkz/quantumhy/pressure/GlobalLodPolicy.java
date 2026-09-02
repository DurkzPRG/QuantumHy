package com.durkz.quantumhy.pressure;

public final class GlobalLodPolicy {

    private GlobalLodPolicy() {
    }

    public static double aggressiveness(double configured, double pressureMultiplier, boolean anyWorldPressured) {
        double base = configured > 0.0D ? configured : 1.0D;
        if (!anyWorldPressured || base <= 1.0D) {
            return base;
        }
        return base * Math.max(1.0D, pressureMultiplier);
    }
}
