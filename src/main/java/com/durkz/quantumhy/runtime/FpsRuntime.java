package com.durkz.quantumhy.runtime;

import com.durkz.quantumhy.QuantumHyPlugin;
import com.durkz.quantumhy.config.QuantumHyConfig;
import com.durkz.quantumhy.pressure.PressureGovernor;
import com.durkz.quantumhy.integration.LeanCoreBridge;
import com.durkz.quantumhy.spawn.SpawnStreamPauseSystem;
import com.durkz.quantumhy.view.ClientViewRadiusController;
import com.durkz.quantumhy.view.EntityCullSystem;
import com.hypixel.hytale.server.core.modules.entity.tracker.EntityTrackerSystems;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.Universe;
import com.hypixel.hytale.server.core.universe.world.World;

import javax.annotation.Nonnull;
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
    private final PressureGovernor pressure;

    private ScheduledExecutorService scheduler;
    private ScheduledFuture<?> tickFuture;
    private volatile boolean running;

    private boolean leanCoreHandled;
    private int leanCoreAttempts;

    public FpsRuntime(QuantumHyPlugin plugin, QuantumHyConfig config) {
        this.plugin = plugin;
        this.config = config;
        this.controller = new ClientViewRadiusController(config);
        this.pressure = new PressureGovernor(plugin);
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

    /** LeanCore coexistence: view-radius takeover and chunk-rate ownership. Retried a few passes. */
    private void ensureLeanCoreCoexistence() {
        if (leanCoreHandled) {
            return;
        }
        if (config.yieldToLeanCoreViewRadius || !config.leanCoreTakeover) {
            if (LeanCoreBridge.isPresent()) {
                if (!config.yieldToLeanCoreViewRadius) {
                    plugin.getLogger().atWarning().log(
                            "LeanCore detected but leanCoreTakeover=false: both mods may fight over the client view radius.");
                }
                logLeanCoreChunkRateCoexistence();
            }
            leanCoreHandled = true;
            return;
        }
        if (LeanCoreBridge.isPresent()) {
            boolean off = LeanCoreBridge.disableViewRadiusGovernance();
            plugin.getLogger().atInfo().log(
                    "LeanCore detected: took over the client view radius (governance %s). LeanCore keeps simulation and memory.",
                    off ? "turned off" : "could not be reached, check LeanCore version");
            logLeanCoreChunkRateCoexistence();
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

    private void logLeanCoreChunkRateCoexistence() {
        plugin.getLogger().atInfo().log("%s", LeanCoreBridge.coexistenceLine(config));
        if (LeanCoreBridge.leanCoreOwnsChunkRate()) {
            if (config.smoothChunkStreaming || config.pressureGovernorEnabled) {
                plugin.getLogger().atInfo().log(
                        "LeanCore chunkThroughputGovernanceEnabled=true: QuantumHy yields chunk send-rate "
                                + "(no maxChunks/s or maxChunks/tick writes). MSPT pressure still trims density, LOD, and effects.");
            }
        } else if (config.smoothChunkStreaming) {
            plugin.getLogger().atInfo().log(
                    "LeanCore chunk throughput governance off: QuantumHy owns chunk send-rate (smoothStreaming=true).");
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
        PressureGovernor.Snapshot pressureSnap = pressure.update(world, config, config.tickIntervalSeconds);
        pressure.applyEntityLod(config, pressureSnap);
        PressureGovernor.ViewPassContext pass = pressure.viewContext(config, pressureSnap);

        int changed = 0;
        StringBuilder details = config.verboseLog ? new StringBuilder() : null;
        for (PlayerRef ref : batch) {
            try {
                ClientViewRadiusController.Decision decision = controller.applyOne(ref, world, pass);
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
        logActionDeltas(world, pressureSnap);

        if (details != null) {
            plugin.getLogger().atInfo().log("pass [world=%s] players=%d changed=%d: %s",
                    shortId(worldUuid), batch.size(), changed,
                    details.length() == 0 ? "(none readable)" : details.toString());
        } else if (changed > 0) {
            plugin.getLogger().atInfo().log("pass [world=%s] changed %d view radius", shortId(worldUuid), changed);
        }
    }

    /** Server log summary for spawn hold, entity cull, and pressure since the last pass on this world. */
    private void logActionDeltas(@Nonnull World world, @Nonnull PressureGovernor.Snapshot pressureSnap) {
        String worldName = world.getName();
        long poolCooldowns = SpawnStreamPauseSystem.drainCooldownsSinceReport(worldName);
        long poolReleases = SpawnStreamPauseSystem.drainReleasesSinceReport(worldName);
        int poolCooled = SpawnStreamPauseSystem.poolCooledCount(worldName);
        boolean streamPause = SpawnStreamPauseSystem.isStreamPauseActive(worldName);
        long vertical = EntityCullSystem.drainVerticalSinceReport(worldName);
        long cap = EntityCullSystem.drainCapSinceReport(worldName);
        if (!streamPause && poolCooldowns == 0L && poolReleases == 0L && poolCooled == 0
                && vertical == 0L && cap == 0L && !pressureSnap.pressured()) {
            return;
        }
        plugin.getLogger().atInfo().log(
                "actions [world=%s] pressure=%s streamPause=%s poolCooled=%d poolTick=%d poolRelease=%d "
                        + "entityVertical=%d entityCap=%d (session pool=%d)",
                worldName, PressureGovernor.formatStatus(pressureSnap),
                streamPause ? "on" : "off", poolCooled, poolCooldowns, poolReleases,
                vertical, cap, SpawnStreamPauseSystem.POOL_COOLDOWNS.sum());
    }

    public PressureGovernor pressureGovernor() {
        return pressure;
    }

    private static String shortId(UUID uuid) {
        String text = uuid.toString();
        return text.length() >= 8 ? text.substring(0, 8) : text;
    }

    public void shutdown() {
        running = false;
        pressure.shutdown(config);
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
