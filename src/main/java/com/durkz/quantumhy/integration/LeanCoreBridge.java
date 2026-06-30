package com.durkz.quantumhy.integration;

import com.hypixel.hytale.common.plugin.PluginIdentifier;
import com.hypixel.hytale.server.core.plugin.PluginManager;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

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
        Object config = invokeNoArg(plugin, "config");
        if (config == null) {
            return false;
        }
        boolean a = clearFlag(config, "viewRadiusGovernanceEnabled");
        boolean b = clearFlag(config, "liteViewRadiusEnabled");
        boolean c = clearFlag(config, "motionViewRadiusBoostEnabled");
        return a && b && c;
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

    private static Object invokeNoArg(Object target, String method) {
        try {
            Method m = target.getClass().getMethod(method);
            return m.invoke(target);
        } catch (ReflectiveOperationException | RuntimeException ignored) {
            return null;
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
