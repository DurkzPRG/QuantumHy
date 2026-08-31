package com.durkz.quantumhy.runtime;

import jdk.jfr.Event;
import jdk.jfr.Label;
import jdk.jfr.Name;

public final class JfrRuntimeProfiler implements RuntimeProfiler {

    @Override
    public void pass(long durationNanos, int players, int changed, boolean pressured) {
        PassEvent event = new PassEvent();
        if (!event.isEnabled()) return;
        event.durationNanos = durationNanos;
        event.players = players;
        event.changed = changed;
        event.pressured = pressured;
        event.commit();
    }

    @Override
    public void cull(long durationNanos, int visibleBefore, int verticalCulled, int capCulled) {
        CullEvent event = new CullEvent();
        if (!event.isEnabled()) return;
        event.durationNanos = durationNanos;
        event.visibleBefore = visibleBefore;
        event.verticalCulled = verticalCulled;
        event.capCulled = capCulled;
        event.commit();
    }

    @Override
    public void density(long durationNanos, int chunks, int entities, boolean cached) {
        DensityEvent event = new DensityEvent();
        if (!event.isEnabled()) return;
        event.durationNanos = durationNanos;
        event.chunks = chunks;
        event.entities = entities;
        event.cached = cached;
        event.commit();
    }

    @Override
    public void pressure(long durationNanos, double averageMspt, double lastMspt, boolean pressured) {
        PressureEvent event = new PressureEvent();
        if (!event.isEnabled()) return;
        event.durationNanos = durationNanos;
        event.averageMspt = averageMspt;
        event.lastMspt = lastMspt;
        event.pressured = pressured;
        event.commit();
    }

    @Override
    public void streaming(String tier, int perSecond, int perTick, int loading, int loaded,
            int loadingDelta, double averageMspt, double lastMspt, String protectionCause,
            long holdMs, boolean changed) {
        StreamingEvent event = new StreamingEvent();
        if (!event.isEnabled()) return;
        event.tier = tier == null ? "" : tier;
        event.perSecond = perSecond;
        event.perTick = perTick;
        event.loading = loading;
        event.loaded = loaded;
        event.loadingDelta = loadingDelta;
        event.averageMspt = averageMspt;
        event.lastMspt = lastMspt;
        event.protectionCause = protectionCause == null ? "" : protectionCause;
        event.holdMs = holdMs;
        event.changed = changed;
        event.commit();
    }

    @Name("durkz.QuantumHy.Pass") @Label("QuantumHy adaptive pass")
    public static final class PassEvent extends Event {
        public long durationNanos; public int players; public int changed; public boolean pressured;
    }

    @Name("durkz.QuantumHy.EntityCull") @Label("QuantumHy entity cull")
    public static final class CullEvent extends Event {
        public long durationNanos; public int visibleBefore; public int verticalCulled; public int capCulled;
    }

    @Name("durkz.QuantumHy.DensityScan") @Label("QuantumHy density scan")
    public static final class DensityEvent extends Event {
        public long durationNanos; public int chunks; public int entities; public boolean cached;
    }

    @Name("durkz.QuantumHy.Pressure") @Label("QuantumHy MSPT pressure governor")
    public static final class PressureEvent extends Event {
        public long durationNanos; public double averageMspt; public double lastMspt; public boolean pressured;
    }

    @Name("durkz.QuantumHy.Streaming") @Label("QuantumHy chunk streaming")
    public static final class StreamingEvent extends Event {
        public String tier; public int perSecond; public int perTick; public int loading; public int loaded;
        public int loadingDelta; public double averageMspt; public double lastMspt;
        public String protectionCause; public long holdMs; public boolean changed;
    }
}
