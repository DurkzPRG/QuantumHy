package com.durkz.quantumhy.runtime;

/** Optional profiling sink supplied only by dedicated developer builds. */
public interface RuntimeProfiler {

    void pass(long durationNanos, int players, int changed, boolean pressured);

    void cull(long durationNanos, int visibleBefore, int verticalCulled, int capCulled);

    void density(long durationNanos, int chunks, int entities, boolean cached);

    void pressure(long durationNanos, double averageMspt, double lastMspt, boolean pressured);

    void streaming(String tier, int perSecond, int perTick, int loading, int loaded,
            long holdMs, boolean changed);
}
