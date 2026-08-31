package com.durkz.quantumhy.runtime;

import jdk.jfr.Event;
import jdk.jfr.Label;
import jdk.jfr.Name;

/** Optional diagnostics for private profiling. Disabled unless the JVM property is set. */
public final class QuantumHyJfr {

    private static final boolean REQUESTED = Boolean.getBoolean("durkz.quantumhy.jfr");

    private QuantumHyJfr() {
    }

    public static void pass(long durationNanos, int players, int changed, boolean pressured) {
        if (!REQUESTED) {
            return;
        }
        PassEvent event = new PassEvent();
        if (!event.isEnabled()) {
            return;
        }
        event.durationNanos = durationNanos;
        event.players = players;
        event.changed = changed;
        event.pressured = pressured;
        event.commit();
    }

    public static void cull(long durationNanos, int visibleBefore, int verticalCulled, int capCulled) {
        if (!REQUESTED) {
            return;
        }
        CullEvent event = new CullEvent();
        if (!event.isEnabled()) {
            return;
        }
        event.durationNanos = durationNanos;
        event.visibleBefore = visibleBefore;
        event.verticalCulled = verticalCulled;
        event.capCulled = capCulled;
        event.commit();
    }

    public static void density(long durationNanos, int chunks, int entities, boolean cached) {
        if (!REQUESTED) {
            return;
        }
        DensityEvent event = new DensityEvent();
        if (!event.isEnabled()) {
            return;
        }
        event.durationNanos = durationNanos;
        event.chunks = chunks;
        event.entities = entities;
        event.cached = cached;
        event.commit();
    }

    public static void pressure(long durationNanos, double averageMspt, double lastMspt, boolean pressured) {
        if (!REQUESTED) {
            return;
        }
        PressureEvent event = new PressureEvent();
        if (!event.isEnabled()) {
            return;
        }
        event.durationNanos = durationNanos;
        event.averageMspt = averageMspt;
        event.lastMspt = lastMspt;
        event.pressured = pressured;
        event.commit();
    }

    public static void streaming(String tier, int perSecond, int perTick, int loading, int loaded,
            long holdMs, boolean changed) {
        if (!REQUESTED) {
            return;
        }
        StreamingEvent event = new StreamingEvent();
        if (!event.isEnabled()) {
            return;
        }
        event.tier = tier == null ? "" : tier;
        event.perSecond = perSecond;
        event.perTick = perTick;
        event.loading = loading;
        event.loaded = loaded;
        event.holdMs = holdMs;
        event.changed = changed;
        event.commit();
    }

    @Name("durkz.QuantumHy.Pass")
    @Label("QuantumHy adaptive pass")
    public static final class PassEvent extends Event {
        @Label("Duration (ns)") public long durationNanos;
        @Label("Players") public int players;
        @Label("Changed radii") public int changed;
        @Label("MSPT pressure") public boolean pressured;
    }

    @Name("durkz.QuantumHy.EntityCull")
    @Label("QuantumHy entity cull")
    public static final class CullEvent extends Event {
        @Label("Duration (ns)") public long durationNanos;
        @Label("Visible before") public int visibleBefore;
        @Label("Vertical culled") public int verticalCulled;
        @Label("Cap culled") public int capCulled;
    }

    @Name("durkz.QuantumHy.DensityScan")
    @Label("QuantumHy density scan")
    public static final class DensityEvent extends Event {
        @Label("Duration (ns)") public long durationNanos;
        @Label("Loaded chunks") public int chunks;
        @Label("Entities") public int entities;
        @Label("Used cached sample") public boolean cached;
    }

    @Name("durkz.QuantumHy.Pressure")
    @Label("QuantumHy MSPT pressure governor")
    public static final class PressureEvent extends Event {
        @Label("Duration (ns)") public long durationNanos;
        @Label("Average MSPT") public double averageMspt;
        @Label("Last MSPT") public double lastMspt;
        @Label("Pressured") public boolean pressured;
    }

    @Name("durkz.QuantumHy.Streaming")
    @Label("QuantumHy chunk streaming")
    public static final class StreamingEvent extends Event {
        @Label("Tier") public String tier;
        @Label("Sections per second") public int perSecond;
        @Label("Sections per tick") public int perTick;
        @Label("Loading sections") public int loading;
        @Label("Loaded sections") public int loaded;
        @Label("Hold remaining (ms)") public long holdMs;
        @Label("Changed tracker") public boolean changed;
    }
}
