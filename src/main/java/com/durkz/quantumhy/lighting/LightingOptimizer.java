package com.durkz.quantumhy.lighting;

import com.hypixel.hytale.logger.HytaleLogger;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.lighting.FloodLightCalculation;
import com.hypixel.hytale.server.core.universe.world.lighting.LightCalculation;

import java.util.concurrent.ConcurrentHashMap;

public final class LightingOptimizer implements AutoCloseable {
    private record Installation(LightCalculation original, DenseGlobalLighting replacement) { }
    private final ConcurrentHashMap<World, Installation> worlds = new ConcurrentHashMap<>();
    private final HytaleLogger logger;
    private final boolean supported;

    public static void configureAgent(boolean enabled, HytaleLogger logger) {
        if (!Boolean.getBoolean("durkz.quantumhy.agent.requested")) return;
        boolean allowed = enabled && Boolean.parseBoolean(System.getProperty("durkz.quantumhy.agent.enabled", "true"));
        try {
            Class.forName("com.durkz.quantumhy.agent.AgentLightBridge", true, ClassLoader.getSystemClassLoader())
                    .getMethod("setEnabled", boolean.class).invoke(null, allowed);
        } catch (ReflectiveOperationException | LinkageError failure) {
            logger.atWarning().withCause(failure).log("Could not configure the optional QuantumHy lighting agent.");
        }
    }

    public LightingOptimizer(HytaleLogger logger) {
        this.logger = logger;
        boolean kernelSupported = NativeLightKernel.supported();
        if (Boolean.getBoolean("durkz.quantumhy.agent.active")) {
            supported = false;
            logger.atInfo().log("QuantumHy lighting agent active; native call sites handle local and global propagation.");
            return;
        }
        supported = kernelSupported;
        if (!supported) logger.atWarning().log("QuantumHy dense lighting unavailable for this server binary; using native lighting.");
    }

    public void install(World world) {
        if (!supported || world.isStarted() || worlds.containsKey(world)) return;
        var manager = world.getChunkLighting();
        LightCalculation original = manager.getLightCalculation();
        if (original == null || original.getClass() != FloodLightCalculation.class) return;
        DenseGlobalLighting replacement = new DenseGlobalLighting(manager);
        if (worlds.putIfAbsent(world, new Installation(original, replacement)) != null) return;
        manager.setLightCalculation(replacement);
        logger.atInfo().log("QuantumHy dense global lighting enabled for world %s.", world.getName());
    }

    public void remove(World world) {
        Installation installation = worlds.remove(world);
        if (installation != null && world.getChunkLighting().getLightCalculation() == installation.replacement()) {
            world.getChunkLighting().setLightCalculation(installation.original());
        }
    }

    public static long bufferedSections() {
        return DenseGlobalLighting.BUFFERED_SECTIONS.sum();
    }

    @Override
    public void close() {
        for (World world : worlds.keySet()) remove(world);
    }
}
