package com.durkz.quantumhy.runtime;

public interface RuntimeProfiler {

    void pass(long durationNanos, int players, int changed, boolean pressured);

    void cull(long durationNanos, int visibleBefore, int verticalCulled, int capCulled);

    void density(long durationNanos, int chunks, int entities, boolean cached);

    void pressure(long durationNanos, double averageMspt, double lastMspt, boolean pressured);

    void streaming(String tier, int perSecond, int perTick, int loading, int loaded,
            int loadingDelta, double averageMspt, double lastMspt, String protectionCause,
            long holdMs, boolean changed);
}
