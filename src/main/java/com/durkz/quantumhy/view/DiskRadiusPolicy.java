package com.durkz.quantumhy.view;

import com.durkz.quantumhy.client.ClientStrainPolicy;

/** Separate server residency pressure from the last-resort client terrain limit. */
public final class DiskRadiusPolicy {
    private DiskRadiusPolicy() { }

    public static double serverTerrainFraction(double densityFraction, double loadFraction) {
        return ViewAdaptPolicy.terrainShrinkFraction(densityFraction, loadFraction, 0.0D);
    }

    public static double visibleFraction(boolean emergencyEnabled, double loopStrain,
                                          boolean serverPressured, double memoryFloor, boolean streaming) {
        return emergencyEnabled && !serverPressured && memoryFloor <= 0.0D && !streaming
                ? ClientStrainPolicy.terrainFraction(loopStrain) : 0.0D;
    }

    public static double nativeFraction(double serverFraction, boolean adaptiveEnabled,
                                         boolean emergencyEnabled, boolean pressured, double memoryFloor) {
        if (!adaptiveEnabled && !emergencyEnabled) return 0.0D;
        double fraction = Math.max(serverFraction, memoryFloor);
        if (pressured) fraction = Math.max(fraction, 0.5D);
        return Math.clamp(fraction, 0.0D, 1.0D);
    }

    public static boolean visibleRecoveryAllowed(boolean emergencyEnabled, double loopStrain,
                                                   boolean serverPressured, double memoryFloor, boolean streaming) {
        return !emergencyEnabled || (loopStrain <= 0.0D && !serverPressured && memoryFloor <= 0.0D && !streaming);
    }

    public static int nativeTarget(int current, int ceiling, int simulation, double fraction,
                                    boolean canExpand, int maxExpand, int maxShrink) {
        int floor = Math.min(ceiling, Math.max(3, simulation + 1));
        int ideal = (int) Math.floor(ceiling - Math.clamp(fraction, 0.0D, 1.0D) * (ceiling - floor));
        int bounded = Math.clamp(current, floor, ceiling);
        if (ideal > bounded && !canExpand) return bounded;
        return Math.clamp(ViewAdaptPolicy.rampToward(bounded, ideal, maxExpand, maxShrink), floor, ceiling);
    }
}
