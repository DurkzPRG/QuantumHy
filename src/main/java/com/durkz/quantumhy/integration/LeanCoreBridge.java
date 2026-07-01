package com.durkz.quantumhy.integration;

import com.durkz.quantumhy.config.QuantumHyConfig;
import com.hypixel.hytale.common.plugin.PluginIdentifier;
import com.hypixel.hytale.server.core.plugin.PluginManager;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Locale;

/**
 * Optional, reflective hook into LeanCore. LeanCore isn't a compile dependency, so every call
 * no-ops when it isn't loaded or its internals moved.
 */
public final class LeanCoreBridge {

    private static final String GROUP = "durkz";
    private static final String NAME = "LeanCore";

    private LeanCoreBridge() {
    }

    public static boolean isPresent() {
        return getPlugin() != null;
    }

    /** Clears LeanCore's three view-radius flags. True if config was reached and all three fields were written. */
    public static boolean disableViewRadiusGovernance() {
        Object plugin = getPlugin();
        if (plugin == null) {
            return false;
        }
        Object config = leanCoreConfig(plugin);
        if (config == null) {
            return false;
        }
        boolean a = clearFlag(config, "viewRadiusGovernanceEnabled");
        boolean b = clearFlag(config, "liteViewRadiusEnabled");
        boolean c = clearFlag(config, "motionViewRadiusBoostEnabled");
        return a && b && c;
    }

    /**
     * True when LeanCore is loaded and {@code chunkThroughputGovernanceEnabled} is on. In that case
     * QuantumHy must not write {@code ChunkTracker} send-rate caps (LeanCore owns them).
     */
    public static boolean leanCoreOwnsChunkRate() {
        Object config = leanCoreConfig(getPlugin());
        return config != null && readFlag(config, "chunkThroughputGovernanceEnabled");
    }

    /**
     * Whether QuantumHy may write {@code maxChunksPerSecond} / {@code maxChunksPerTick} this pass.
     * Yields to LeanCore when its throughput governance is on. Writes only when
     * {@link QuantumHyConfig#smoothChunkStreaming} is enabled (MSPT pressure may still trim caps).
     */
    public static boolean shouldQuantumHyWriteChunkRate(QuantumHyConfig config) {
        if (config == null || config.yieldToLeanCoreViewRadius || leanCoreOwnsChunkRate()) {
            return false;
        }
        return config.smoothChunkStreaming;
    }

    /** Short label for {@code /q status}. */
    public static String chunkRateOwnerLabel(QuantumHyConfig config) {
        if (leanCoreOwnsChunkRate()) {
            return "LeanCore";
        }
        if (!shouldQuantumHyWriteChunkRate(config)) {
            return "off";
        }
        if (config.pressureGovernorEnabled && config.pressureChunkRateMultiplier < 1.0D) {
            return "QuantumHy (smooth + MSPT pressure)";
        }
        return "QuantumHy (smooth streaming)";
    }

    /** View-radius owner for {@code /q status}. */
    public static String viewRadiusOwnerLabel(QuantumHyConfig config) {
        if (config.yieldToLeanCoreViewRadius) {
            return "LeanCore";
        }
        if (isPresent() && config.leanCoreTakeover) {
            return "QuantumHy (LeanCore view governance off)";
        }
        if (isPresent()) {
            return "conflict risk (both may write)";
        }
        return "QuantumHy";
    }

    /** One-line coexistence summary for logs. */
    public static String coexistenceLine(QuantumHyConfig config) {
        return String.format(Locale.ROOT,
                "LeanCore coexistence: view=%s chunkRate=%s hotRadius=LeanCore",
                viewRadiusOwnerLabel(config), chunkRateOwnerLabel(config));
    }

    private static Object getPlugin() {
        try {
            PluginManager pm = PluginManager.get();
            if (pm == null) {
                return null;
            }
            return pm.getPlugin(new PluginIdentifier(GROUP, NAME));
        } catch (RuntimeException | LinkageError ignored) {
            return null;
        }
    }

    private static Object leanCoreConfig(Object plugin) {
        return plugin == null ? null : invokeNoArg(plugin, "config");
    }

    private static Object invokeNoArg(Object target, String method) {
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

    private static boolean clearFlag(Object target, String field) {
        try {
            Field f = target.getClass().getField(field);
            f.setBoolean(target, false);
            return true;
        } catch (ReflectiveOperationException | RuntimeException ignored) {
            return false;
        }
    }
}
