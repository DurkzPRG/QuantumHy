package com.durkz.quantumhy.lighting;

import com.hypixel.hytale.logger.HytaleLogger;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.lighting.FloodLightCalculation;
import com.hypixel.hytale.server.core.universe.world.lighting.LightCalculation;

import java.util.concurrent.ConcurrentHashMap;

public final class LightingOptimizer implements AutoCloseable {
    private record Installation(LightCalculation original, FloodLightCalculation replacement) { }
    private final ConcurrentHashMap<World, Installation> worlds = new ConcurrentHashMap<>();
    private final HytaleLogger logger;
    private final boolean supported;

    public LightingOptimizer(HytaleLogger logger) {
        this.logger = logger;
        supported = NativeLightKernel.supported();
        if (!supported) logger.atWarning().log("QuantumHy dense lighting unavailable for this server binary; using native lighting.");
    }

    public void install(World world) {
        if (!supported || world.isStarted() || worlds.containsKey(world)) return;
        var manager = world.getChunkLighting();
        LightCalculation original = manager.getLightCalculation();
        if (original == null || original.getClass() != FloodLightCalculation.class) return;
        FloodLightCalculation replacement;
        try {
            replacement = StandaloneLightingFactory.create(manager, logger);
        } catch (VirtualMachineError | ThreadDeath fatal) {
            throw fatal;
        } catch (Throwable failure) {
            logger.atWarning().withCause(failure).log("QuantumHy standalone local lighting unavailable; retaining the global lighting wrapper.");
            replacement = null;
        }
        if (replacement == null) replacement = new DenseGlobalLighting(manager);
        if (worlds.putIfAbsent(world, new Installation(original, replacement)) != null) return;
        manager.setLightCalculation(replacement);
        logger.atInfo().log("QuantumHy dense %s lighting enabled for world %s.",
                replacement.getClass().isHidden() ? "local and global" : "global", world.getName());
    }

    public void remove(World world) {
        Installation installation = worlds.remove(world);
        if (installation != null && world.getChunkLighting().getLightCalculation() == installation.replacement()) {
            world.getChunkLighting().setLightCalculation(installation.original());
        }
    }

    public static long bufferedSections() {
        return DenseGlobalLighting.BUFFERED_SECTIONS.sum() + DenseLightPropagation.BUFFERED_SECTIONS.sum();
    }

    @Override
    public void close() {
        for (World world : worlds.keySet()) remove(world);
    }
}
