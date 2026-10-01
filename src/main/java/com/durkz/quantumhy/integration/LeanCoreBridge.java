package com.durkz.quantumhy.integration;

import com.durkz.quantumhy.config.QuantumHyConfig;
import com.hypixel.hytale.common.plugin.PluginIdentifier;
import com.hypixel.hytale.server.core.plugin.PluginManager;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.Locale;
import javax.annotation.Nullable;

/** Optional reflective coexistence contract with LeanCore. */
public final class LeanCoreBridge {

    public enum Ownership {
        NOT_PRESENT, TAKEOVER_CONFIRMED, YIELD_CONFIGURED, INCOMPATIBLE, EXTERNAL
    }

    private static final String GROUP = "durkz";
    private static final String NAME = "LeanCore";
    private static final String[] VIEW_FLAGS = {
            "viewRadiusGovernanceEnabled", "liteViewRadiusEnabled", "motionViewRadiusBoostEnabled"
    };

    private static volatile Ownership ownership = Ownership.NOT_PRESENT;
    private static volatile SavedViewFlags savedViewFlags;

    private static final long CHUNK_RATE_CACHE_NANOS = 1_000_000_000L;
    private static final long MEMORY_TIER_CACHE_NANOS = 5_000_000_000L;
    private static volatile String memoryTier = "";
    private static volatile long memoryTierReadAtNanos;
    private static volatile boolean chunkRateOwned;
    private static volatile long chunkRateReadAtNanos;

    private LeanCoreBridge() {
    }

    public static boolean isPresent() {
        return getPlugin() != null;
    }

    /** Attempts takeover once LeanCore has initialized. Failure is safe: QuantumHy yields shared levers. */
    public static Ownership establishOwnership(QuantumHyConfig config) {
        Object plugin = getPlugin();
        if (plugin == null) {
            ownership = Ownership.NOT_PRESENT;
            return ownership;
        }
        if (config == null || config.yieldToLeanCoreViewRadius) {
            ownership = Ownership.YIELD_CONFIGURED;
            return ownership;
        }
        if (!config.leanCoreTakeover) {
            ownership = Ownership.EXTERNAL;
            return ownership;
        }
        Object leanConfig = leanCoreConfig(plugin);
        if (leanConfig == null) {
            ownership = Ownership.INCOMPATIBLE;
            return ownership;
        }
        SavedViewFlags saved = readViewFlags(leanConfig);
        if (saved == null || !writeViewFlags(leanConfig, false)) {
            ownership = Ownership.INCOMPATIBLE;
            return ownership;
        }
        savedViewFlags = saved;
        ownership = Ownership.TAKEOVER_CONFIRMED;
        return ownership;
    }

    public static Ownership ownership() {
        return ownership;
    }

    public static boolean shouldQuantumHyWriteViewRadius(QuantumHyConfig config) {
        if (config == null || config.yieldToLeanCoreViewRadius) {
            return false;
        }
        return ownership != Ownership.INCOMPATIBLE && ownership != Ownership.YIELD_CONFIGURED;
    }

    /**
     * Read on the 250ms stream path for every player, so the reflective lookup is cached for
     * {@link #CHUNK_RATE_CACHE_NANOS}. LeanCore's flag only changes on its own config reload.
     */
    public static boolean leanCoreOwnsChunkRate() {
        long now = System.nanoTime();
        long readAt = chunkRateReadAtNanos;
        if (readAt != 0L && now - readAt < CHUNK_RATE_CACHE_NANOS) {
            return chunkRateOwned;
        }
        Object config = leanCoreConfig(getPlugin());
        boolean owned = config != null && readFlag(config, "chunkThroughputGovernanceEnabled");
        chunkRateOwned = owned;
        chunkRateReadAtNanos = now == 0L ? 1L : now;
        return owned;
    }

    /**
     * LeanCore's current heap tier ({@code COMFORT}, {@code WATCH}, {@code TIGHT}, {@code CRITICAL}),
     * or an empty string when LeanCore is absent or its API changed. Read-only, cached for
     * {@link #MEMORY_TIER_CACHE_NANOS}: {@code plugin.runtime().lastSample().tier()}.
     */
    public static String memoryTier() {
        long now = System.nanoTime();
        long readAt = memoryTierReadAtNanos;
        if (readAt != 0L && now - readAt < MEMORY_TIER_CACHE_NANOS) {
            return memoryTier;
        }
        Object runtime = invokeNoArg(getPlugin(), "runtime");
        Object sample = runtime == null ? null : invokeNoArg(runtime, "lastSample");
        Object tier = sample == null ? null : invokeNoArg(sample, "tier");
        String value = tier instanceof Enum<?> e ? e.name() : "";
        memoryTier = value;
        memoryTierReadAtNanos = now == 0L ? 1L : now;
        return value;
    }

    /**
     * Shrink floor QuantumHy applies while LeanCore reports heap pressure: the more conservative of
     * the FPS target and the memory target wins. {@code 0} for any other tier.
     */
    public static double memoryShrinkFloor(@Nullable String tier) {
        if ("CRITICAL".equals(tier)) {
            return 0.5D;
        }
        if ("TIGHT".equals(tier)) {
            return 0.25D;
        }
        return 0.0D;
    }

    public static double memoryShrinkFloor(QuantumHyConfig config) {
        if (config == null || !config.leanCoreMemoryAware) {
            return 0.0D;
        }
        return memoryShrinkFloor(memoryTier());
    }

    public static boolean shouldQuantumHyWriteChunkRate(QuantumHyConfig config) {
        if (config == null || !config.smoothChunkStreaming || !shouldQuantumHyWriteViewRadius(config)) {
            return false;
        }
        return !leanCoreOwnsChunkRate();
    }

    public static String chunkRateOwnerLabel(QuantumHyConfig config) {
        if (ownership == Ownership.INCOMPATIBLE || ownership == Ownership.YIELD_CONFIGURED) {
            return "LeanCore (safe yield)";
        }
        if (leanCoreOwnsChunkRate()) {
            return "LeanCore";
        }
        if (!shouldQuantumHyWriteChunkRate(config)) {
            return "off";
        }
        return config.pressureGovernorEnabled && config.pressureChunkRateMultiplier < 1.0D
                ? "QuantumHy (smooth + MSPT pressure)" : "QuantumHy (smooth streaming)";
    }

    public static String viewRadiusOwnerLabel(QuantumHyConfig config) {
        return switch (ownership) {
            case NOT_PRESENT -> "QuantumHy";
            case TAKEOVER_CONFIRMED -> "QuantumHy (LeanCore view governance off)";
            case YIELD_CONFIGURED -> "LeanCore";
            case INCOMPATIBLE -> "LeanCore (safe yield: bridge incompatible)";
            case EXTERNAL -> "external config (view may conflict)";
        };
    }

    public static String coexistenceLine(QuantumHyConfig config) {
        return String.format(Locale.ROOT,
                "LeanCore coexistence: state=%s view=%s chunkRate=%s hotRadius=LeanCore memoryAware=%s",
                ownership, viewRadiusOwnerLabel(config), chunkRateOwnerLabel(config),
                config != null && config.leanCoreMemoryAware);
    }

    /** Restores LeanCore's in-memory flags only when this runtime had a confirmed takeover. */
    public static void restoreOwnership() {
        SavedViewFlags saved = savedViewFlags;
        Object config = leanCoreConfig(getPlugin());
        if (ownership == Ownership.TAKEOVER_CONFIRMED && saved != null && config != null) {
            writeViewFlags(config, saved.values());
        }
        savedViewFlags = null;
        chunkRateReadAtNanos = 0L;
        memoryTierReadAtNanos = 0L;
        memoryTier = "";
        ownership = isPresent() ? Ownership.EXTERNAL : Ownership.NOT_PRESENT;
    }

    private static Object getPlugin() {
        try {
            PluginManager pm = PluginManager.get();
            return pm == null ? null : pm.getPlugin(new PluginIdentifier(GROUP, NAME));
        } catch (RuntimeException | LinkageError ignored) {
            return null;
        }
    }

    private static Object leanCoreConfig(Object plugin) {
        return plugin == null ? null : invokeNoArg(plugin, "config");
    }

    private static Object invokeNoArg(Object target, String method) {
        if (target == null) {
            return null;
        }
        try {
            Method m = target.getClass().getMethod(method);
            return m.invoke(target);
        } catch (ReflectiveOperationException | RuntimeException ignored) {
            return null;
        }
    }

    private static boolean readFlag(Object target, String field) {
        try {
            Field f = target.getClass().getField(field);
            return f.getBoolean(target);
        } catch (ReflectiveOperationException | RuntimeException ignored) {
            return false;
        }
    }

    private static SavedViewFlags readViewFlags(Object target) {
        boolean[] values = new boolean[VIEW_FLAGS.length];
        try {
            for (int i = 0; i < VIEW_FLAGS.length; i++) {
                values[i] = target.getClass().getField(VIEW_FLAGS[i]).getBoolean(target);
            }
            return new SavedViewFlags(values);
        } catch (ReflectiveOperationException | RuntimeException ignored) {
            return null;
        }
    }

    private static boolean writeViewFlags(Object target, boolean value) {
        boolean[] values = new boolean[VIEW_FLAGS.length];
        Arrays.fill(values, value);
        return writeViewFlags(target, values);
    }

    private static boolean writeViewFlags(Object target, boolean[] values) {
        try {
            for (int i = 0; i < VIEW_FLAGS.length; i++) {
                target.getClass().getField(VIEW_FLAGS[i]).setBoolean(target, values[i]);
            }
            return true;
        } catch (ReflectiveOperationException | RuntimeException ignored) {
            return false;
        }
    }

    private record SavedViewFlags(boolean[] values) {
    }
}
