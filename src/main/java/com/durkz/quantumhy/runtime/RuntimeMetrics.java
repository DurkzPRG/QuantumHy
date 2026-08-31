package com.durkz.quantumhy.runtime;

import java.util.ServiceLoader;

/** Near-zero-cost profiling bridge. Published builds contain no provider. */
public final class RuntimeMetrics {

    private static final RuntimeProfiler PROFILER = loadProfiler();

    private RuntimeMetrics() {
    }

    private static RuntimeProfiler loadProfiler() {
        if (!Boolean.getBoolean("durkz.quantumhy.profile")) {
            return null;
        }
        return ServiceLoader.load(RuntimeProfiler.class, RuntimeMetrics.class.getClassLoader())
                .findFirst().orElse(null);
    }

    public static void pass(long durationNanos, int players, int changed, boolean pressured) {
        RuntimeProfiler profiler = PROFILER;
        if (profiler != null) profiler.pass(durationNanos, players, changed, pressured);
    }

    public static void cull(long durationNanos, int visibleBefore, int verticalCulled, int capCulled) {
        RuntimeProfiler profiler = PROFILER;
        if (profiler != null) profiler.cull(durationNanos, visibleBefore, verticalCulled, capCulled);
    }

    public static void density(long durationNanos, int chunks, int entities, boolean cached) {
        RuntimeProfiler profiler = PROFILER;
        if (profiler != null) profiler.density(durationNanos, chunks, entities, cached);
    }

    public static void pressure(long durationNanos, double averageMspt, double lastMspt, boolean pressured) {
        RuntimeProfiler profiler = PROFILER;
        if (profiler != null) profiler.pressure(durationNanos, averageMspt, lastMspt, pressured);
    }

    public static void streaming(String tier, int perSecond, int perTick, int loading, int loaded,
            long holdMs, boolean changed) {
        RuntimeProfiler profiler = PROFILER;
        if (profiler != null) {
            profiler.streaming(tier, perSecond, perTick, loading, loaded, holdMs, changed);
        }
    }
}
