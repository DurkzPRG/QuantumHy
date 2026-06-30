package com.durkz.quantumhy.runtime;

import com.durkz.quantumhy.QuantumHyPlugin;
import com.durkz.quantumhy.config.QuantumHyConfig;
import com.durkz.quantumhy.integration.LeanCoreBridge;
import com.durkz.quantumhy.view.ClientViewRadiusController;
import com.hypixel.hytale.server.core.modules.entity.tracker.EntityTrackerSystems;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.Universe;
import com.hypixel.hytale.server.core.universe.world.World;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/**
 * Runs the adaptive pass on a daemon timer. Each pass groups players by world and hands the
 * per-player work to that world's thread, since reading chunks and writing view radius must happen
 * there.
 */
public final class FpsRuntime {

    private final QuantumHyPlugin plugin;
    private final QuantumHyConfig config;
    private final ClientViewRadiusController controller;

    private ScheduledExecutorService scheduler;
    private ScheduledFuture<?> tickFuture;
    private volatile boolean running;

    private boolean leanCoreHandled;
    private int leanCoreAttempts;

    public FpsRuntime(QuantumHyPlugin plugin, QuantumHyConfig config) {
        this.plugin = plugin;
        this.config = config;
        this.controller = new ClientViewRadiusController(config);
    }

    public void start() {
        if (running || !config.enabled) {
            return;
        }
        running = true;
        scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread thread = new Thread(r, "QuantumHy-fps");
            thread.setDaemon(true);
            return thread;
        });
        applyEntityLod();
        long interval = Math.max(1, config.tickIntervalSeconds);
        long delay = Math.max(0, config.initialDelaySeconds);
        tickFuture = scheduler.scheduleAtFixedRate(this::tick, delay, interval, TimeUnit.SECONDS);
        plugin.getLogger().atInfo().log(
                "QuantumHy runtime started (interval=%ds, hardCap=%d, min=%d, max=%d, scan=%d, entityRadius=%s).",
                interval, config.targetClientViewRadius, config.minClientViewRadius,
                config.maxClientViewRadius, config.densityScanChunkRadius, config.adaptEntityRadius);
        ensureLeanCoreCoexistence();
    }

    /** Set the global entity LOD ratio from config (server-wide). Restored on shutdown. */
    private void applyEntityLod() {
        double ratio = EntityTrackerSystems.LODCull.ENTITY_LOD_RATIO_DEFAULT * config.entityLodAggressiveness;
        EntityTrackerSystems.LODCull.ENTITY_LOD_RATIO = ratio;
        if (config.entityLodAggressiveness != 1.0D) {
            plugin.getLogger().atInfo().log(
                    "Entity LOD culling set to %.2fx default (ratio=%.6f): small/distant entities drop sooner.",
                    config.entityLodAggressiveness, ratio);
        }
    }

    /** Turns off LeanCore's view-radius governance once it's loaded. Retried a few passes, since LeanCore may load after us. */
    private void ensureLeanCoreCoexistence() {
        if (leanCoreHandled) {
            return;
        }
        if (config.yieldToLeanCoreViewRadius || !config.leanCoreTakeover) {
            if (!config.yieldToLeanCoreViewRadius && LeanCoreBridge.isPresent()) {
                plugin.getLogger().atWarning().log(
                        "LeanCore detected but leanCoreTakeover=false: both mods may fight over the client view radius.");
            }
            leanCoreHandled = true;
            return;
        }
        if (LeanCoreBridge.isPresent()) {
            boolean off = LeanCoreBridge.disableViewRadiusGovernance();
            plugin.getLogger().atInfo().log(
                    "LeanCore detected: took over the client view radius (governance %s). LeanCore keeps simulation and memory.",
                    off ? "turned off" : "could not be reached, check LeanCore version");
            leanCoreHandled = true;
            return;
        }
        if (++leanCoreAttempts >= 3) {
            plugin.getLogger().atInfo().log(
                    "LeanCore not detected after %d checks: QuantumHy owns the client view radius standalone.",
                    leanCoreAttempts);
            leanCoreHandled = true;
        }
    }

    private void tick() {
        if (!running) {
            return;
        }
        try {
            ensureLeanCoreCoexistence();
            Collection<PlayerRef> online = Universe.get().getPlayers();
            if (online == null || online.isEmpty()) {
                return;
            }

            Map<UUID, List<PlayerRef>> byWorld = new HashMap<>();
            Set<UUID> onlineIds = new HashSet<>();
            for (PlayerRef ref : online) {
                if (ref == null || !ref.isValid()) {
                    continue;
                }
                UUID worldUuid = ref.getWorldUuid();
                if (worldUuid == null) {
                    continue;
                }
                UUID playerId = ref.getUuid();
                if (playerId != null) {
                    onlineIds.add(playerId);
                }
                byWorld.computeIfAbsent(worldUuid, ignored -> new ArrayList<>()).add(ref);
            }
            controller.retain(onlineIds);

            for (Map.Entry<UUID, List<PlayerRef>> entry : byWorld.entrySet()) {
                World world = Universe.get().getWorld(entry.getKey());
                if (world == null || !world.isAlive()) {
                    continue;
                }
                UUID worldUuid = entry.getKey();
                List<PlayerRef> batch = List.copyOf(entry.getValue());
                world.execute(() -> runWorldPass(world, worldUuid, batch));
            }
        } catch (RuntimeException ex) {
            plugin.getLogger().atWarning().withCause(ex)
                    .log("QuantumHy tick failed: %s", ex.getClass().getSimpleName());
        }
    }

    private void runWorldPass(World world, UUID worldUuid, List<PlayerRef> batch) {
        if (!running) {
            return;
        }
        int changed = 0;
        StringBuilder details = config.verboseLog ? new StringBuilder() : null;
        for (PlayerRef ref : batch) {
            try {
                ClientViewRadiusController.Decision decision = controller.applyOne(ref, world);
                if (decision == null) {
                    continue;
                }
                if (decision.applied()) {
                    changed++;
                }
                if (details != null) {
                    if (details.length() > 0) {
                        details.append(" | ");
                    }
                    details.append(decision.line());
                }
            } catch (RuntimeException ex) {
                plugin.getLogger().atWarning().withCause(ex).log("view radius apply failed for a player");
            }
        }
        if (details != null) {
            plugin.getLogger().atInfo().log("pass [world=%s] players=%d changed=%d: %s",
                    shortId(worldUuid), batch.size(), changed,
                    details.length() == 0 ? "(none readable)" : details.toString());
        } else if (changed > 0) {
            plugin.getLogger().atInfo().log("pass [world=%s] changed %d view radius", shortId(worldUuid), changed);
        }
    }

    private static String shortId(UUID uuid) {
        String text = uuid.toString();
        return text.length() >= 8 ? text.substring(0, 8) : text;
    }

    public void shutdown() {
        running = false;
        EntityTrackerSystems.LODCull.ENTITY_LOD_RATIO = EntityTrackerSystems.LODCull.ENTITY_LOD_RATIO_DEFAULT;
        if (tickFuture != null) {
            tickFuture.cancel(false);
            tickFuture = null;
        }
        if (scheduler != null) {
            scheduler.shutdownNow();
            scheduler = null;
        }
    }
}
