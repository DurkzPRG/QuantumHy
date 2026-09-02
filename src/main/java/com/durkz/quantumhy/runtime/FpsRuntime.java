package com.durkz.quantumhy.runtime;

import com.durkz.quantumhy.QuantumHyPlugin;
import com.durkz.quantumhy.config.QuantumHyConfig;
import com.durkz.quantumhy.config.PlayerPreferences;
import com.durkz.quantumhy.pressure.GlobalLodPolicy;
import com.durkz.quantumhy.pressure.PressureGovernor;
import com.durkz.quantumhy.integration.LeanCoreBridge;
import com.durkz.quantumhy.spawn.SpawnStreamPauseSystem;
import com.durkz.quantumhy.view.ClientViewRadiusController;
import com.durkz.quantumhy.view.EntityCullSystem;
import com.durkz.quantumhy.view.StreamCatchUpPolicy;
import com.durkz.quantumhy.view.StreamRateController;
import com.durkz.quantumhy.view.VisualLoadRegistry;
import com.hypixel.hytale.server.core.modules.entity.player.ChunkTracker;
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
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Runs the adaptive pass on a daemon timer. Each pass groups players by world and hands the
 * per-player work to that world's thread, since reading chunks and writing view radius must happen
 * there.
 */
public final class FpsRuntime {

    private final QuantumHyPlugin plugin;
    private final QuantumHyConfig config;
    private final PlayerPreferences preferences;
    private final ClientViewRadiusController controller;
    private final StreamRateController stream;
    private final PressureGovernor pressure;
    private final double originalEntityLodRatio;

    private ScheduledExecutorService scheduler;
    private ScheduledFuture<?> tickFuture;
    private ScheduledFuture<?> streamFuture;
    private volatile boolean running;

    private boolean leanCoreHandled;
    private int leanCoreAttempts;

    private final Map<UUID, List<PlayerRef>> worldScratch = new HashMap<>();
    private final Set<UUID> onlineScratch = new HashSet<>();
    private final ConcurrentHashMap<UUID, String> worldNames = new ConcurrentHashMap<>();
    private volatile Set<UUID> activeWorldIds = Set.of();

    /** Reused by the 250ms path. One queued batch per world prevents world-thread backlog. */
    private final ConcurrentHashMap<UUID, StreamWorldBatch> streamWorldBatches = new ConcurrentHashMap<>();
    private final ArrayList<StreamWorldBatch> streamTouchedScratch = new ArrayList<>(4);
    private long streamGeneration;

    private final ConcurrentHashMap<UUID, RuntimeSnapshot.PlayerRow> playerSnapshotScratch = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, RuntimeSnapshot.WorldRow> worldSnapshotScratch = new ConcurrentHashMap<>();
    private volatile RuntimeSnapshot snapshot = RuntimeSnapshot.EMPTY;
    private volatile int lastOnlineCount;

    public FpsRuntime(QuantumHyPlugin plugin, QuantumHyConfig config, PlayerPreferences preferences) {
        this.plugin = plugin;
        this.config = config;
        this.preferences = preferences;
        this.controller = new ClientViewRadiusController(config);
        this.stream = new StreamRateController(config);
        this.pressure = new PressureGovernor(plugin);
        this.originalEntityLodRatio = EntityTrackerSystems.LODCull.ENTITY_LOD_RATIO;
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
        if (config.entityLodAggressiveness != 1.0D) {
            plugin.getLogger().atInfo().log(
                    "Entity LOD culling set to %.2fx default. Pressure multiplier is enabled for this admin override.",
                    config.entityLodAggressiveness);
        }
        long interval = Math.max(1, config.tickIntervalSeconds);
        long delay = Math.max(0, config.initialDelaySeconds);
        tickFuture = scheduler.scheduleAtFixedRate(this::tick, delay, interval, TimeUnit.SECONDS);
        if (config.smoothChunkStreaming) {
            long streamMs = Math.max(50, config.streamCatchUpIntervalMs);
            streamFuture = scheduler.scheduleAtFixedRate(this::streamTick, delay * 1000L, streamMs, TimeUnit.MILLISECONDS);
        }
        plugin.getLogger().atInfo().log(
                "QuantumHy runtime started (interval=%ds, terrainAdaptive=%s, terrainEmergency=%s, hardCap=%d, min=%d, max=%d, scan=%d, entityRadius=%s).",
                interval, config.adaptiveTerrainViewEnabled, config.emergencyTerrainTrimEnabled,
                config.targetClientViewRadius, config.minClientViewRadius,
                config.maxClientViewRadius, config.densityScanChunkRadius, config.adaptEntityRadius);
        ensureLeanCoreCoexistence();
    }

    /** Set the global entity LOD ratio from config (server-wide). Restored on shutdown. */
    private void applyEntityLod() {
        double aggressiveness = GlobalLodPolicy.aggressiveness(
                config.entityLodAggressiveness, config.pressureLodMultiplier,
                pressure.anyPressured(activeWorldIds));
        double ratio = EntityTrackerSystems.LODCull.ENTITY_LOD_RATIO_DEFAULT * aggressiveness;
        EntityTrackerSystems.LODCull.ENTITY_LOD_RATIO = ratio;
    }

    /** LeanCore coexistence: view-radius takeover and chunk-rate ownership. Retried a few passes. */
    private void ensureLeanCoreCoexistence() {
        if (leanCoreHandled) {
            return;
        }
        if (LeanCoreBridge.isPresent()) {
            LeanCoreBridge.Ownership state = LeanCoreBridge.establishOwnership(config);
            if (state == LeanCoreBridge.Ownership.TAKEOVER_CONFIRMED) {
                plugin.getLogger().atInfo().log(
                        "LeanCore detected: QuantumHy takeover confirmed. LeanCore keeps simulation and memory.");
            } else if (state == LeanCoreBridge.Ownership.INCOMPATIBLE) {
                plugin.getLogger().atWarning().log(
                        "LeanCore bridge could not confirm ownership: QuantumHy safely yields view radius and chunk streaming.");
            }
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
                clearOnlineState();
                return;
            }

            onlineScratch.clear();
            worldScratch.clear();

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
                    onlineScratch.add(playerId);
                }
                worldScratch.computeIfAbsent(worldUuid, ignored -> new ArrayList<>(4)).add(ref);
            }
            Set<UUID> active = Set.copyOf(worldScratch.keySet());
            activeWorldIds = active;
            worldNames.keySet().retainAll(active);
            pressure.releaseInactiveWorlds(active, config);
            Set<String> activeNames = Set.copyOf(worldNames.values());
            worldSnapshotScratch.keySet().retainAll(activeNames);
            applyEntityLod();
            controller.retain(onlineScratch);
            stream.retain(onlineScratch);
            playerSnapshotScratch.keySet().retainAll(onlineScratch);
            lastOnlineCount = onlineScratch.size();

            for (Map.Entry<UUID, List<PlayerRef>> entry : worldScratch.entrySet()) {
                World world = Universe.get().getWorld(entry.getKey());
                if (world == null || !world.isAlive()) {
                    continue;
                }
                UUID worldUuid = entry.getKey();
                List<PlayerRef> batch = entry.getValue();
                world.execute(() -> runWorldPass(world, worldUuid, batch));
            }
            worldScratch.clear();
        } catch (RuntimeException ex) {
            plugin.getLogger().atWarning().withCause(ex)
                    .log("QuantumHy tick failed: %s", ex.getClass().getSimpleName());
        }
    }

    private void streamTick() {
        if (!running || !config.smoothChunkStreaming) {
            return;
        }
        try {
            Collection<PlayerRef> online = Universe.get().getPlayers();
            if (online == null || online.isEmpty()) {
                return;
            }
            long generation = ++streamGeneration;
            long nowMs = System.currentTimeMillis();
            streamTouchedScratch.clear();
            for (PlayerRef ref : online) {
                if (ref == null || !ref.isValid()) {
                    continue;
                }
                UUID worldUuid = ref.getWorldUuid();
                if (worldUuid == null) {
                    continue;
                }
                StreamWorldBatch batch = streamWorldBatches.computeIfAbsent(
                        worldUuid, StreamWorldBatch::new);
                if (batch.collect(generation, nowMs, ref)) {
                    streamTouchedScratch.add(batch);
                }
            }
            for (int i = 0; i < streamTouchedScratch.size(); i++) {
                StreamWorldBatch batch = streamTouchedScratch.get(i);
                if (!batch.tryQueue(generation)) {
                    continue;
                }
                UUID worldUuid = batch.worldUuid();
                World world = Universe.get().getWorld(worldUuid);
                if (world == null || !world.isAlive()) {
                    batch.complete();
                    continue;
                }
                try {
                    world.execute(() -> {
                        try {
                            runStreamPass(world, worldUuid, batch.players(), batch.sampleTimeMs());
                        } finally {
                            batch.complete();
                        }
                    });
                } catch (RuntimeException ex) {
                    batch.complete();
                    throw ex;
                }
            }
        } catch (RuntimeException ex) {
            plugin.getLogger().atWarning().withCause(ex)
                    .log("QuantumHy stream tick failed: %s", ex.getClass().getSimpleName());
        } finally {
            streamTouchedScratch.clear();
        }
    }

    private void runStreamPass(World world, UUID worldUuid, List<PlayerRef> batch, long nowMs) {
        if (!running) {
            return;
        }
        PressureGovernor.Snapshot pressureSnap = pressure.snapshotFor(worldUuid);
        PressureGovernor.StreamHealth health = pressure.readStreamHealth(world);
        for (PlayerRef ref : batch) {
            try {
                if (!preferences.isOptimizationEnabled(ref.getUuid())) {
                    stream.restoreOne(ref);
                    continue;
                }
                StreamRateController.Transition transition = stream.applyOne(
                        ref, health, pressureSnap.pressured(), nowMs);
                if (transition != null && config.verboseLog) {
                    StreamRateController.Applied applied = transition.applied();
                    plugin.getLogger().atInfo().log(
                            "stream protect %s [world=%s player=%s] mspt=%.1f/%.1f "
                                    + "loading=%d delta=%+d rate=%d/%d cause=%s",
                            transition.current() == StreamCatchUpPolicy.Tier.PROTECT ? "entered" : "exited",
                            world.getName(), ref.getUsername(), applied.msptLast(), applied.msptAverage(),
                            applied.loading(), applied.loadingDelta(), applied.perSecond(), applied.perTick(),
                            applied.protectionCause());
                }
            } catch (RuntimeException ex) {
                plugin.getLogger().atWarning().withCause(ex).log("stream rate apply failed for a player");
            }
        }
    }

    private void runWorldPass(World world, UUID worldUuid, List<PlayerRef> batch) {
        if (!running || !activeWorldIds.contains(worldUuid)) {
            return;
        }
        String worldName = world.getName();
        long pressureStartNs = System.nanoTime();
        PressureGovernor.Snapshot pressureSnap = pressure.update(world, config, config.tickIntervalSeconds);
        RuntimeMetrics.pressure(System.nanoTime() - pressureStartNs, pressureSnap.msptAvg10s(),
                pressureSnap.msptLast(), pressureSnap.pressured());
        worldNames.put(worldUuid, worldName);
        applyEntityLod();
        PressureGovernor.ViewPassContext pass = pressure.viewContext(config, pressureSnap);

        long startNs = System.nanoTime();
        long deadlineNs = config.worldPassBudgetMs <= 0
                ? Long.MAX_VALUE
                : startNs + (long) config.worldPassBudgetMs * 1_000_000L;

        int changed = 0;
        StringBuilder details = config.verboseLog ? new StringBuilder() : null;
        for (PlayerRef ref : batch) {
            try {
                ClientViewRadiusController.Decision decision;
                if (preferences.isOptimizationEnabled(ref.getUuid())) {
                    decision = controller.applyOne(ref, world, pass, deadlineNs);
                } else {
                    controller.restoreOne(ref);
                    stream.restoreOne(ref);
                    controller.forget(ref.getUuid());
                    stream.forget(ref.getUuid());
                    decision = controller.observeDisabled(ref);
                }
                if (decision == null) {
                    continue;
                }
                if (decision.applied()) {
                    changed++;
                }
                publishPlayerRow(ref, worldName, decision);
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
        worldSnapshotScratch.put(worldName, new RuntimeSnapshot.WorldRow(
                pressureSnap,
                SpawnStreamPauseSystem.isStreamPauseActive(worldName),
                SpawnStreamPauseSystem.poolCooledCount(worldName)));
        publishSnapshot();
        logActionDeltas(world, pressureSnap);

        if (details != null) {
            plugin.getLogger().atInfo().log("pass [world=%s] players=%d changed=%d: %s",
                    shortId(worldUuid), batch.size(), changed,
                    details.length() == 0 ? "(none readable)" : details.toString());
        }
        RuntimeMetrics.pass(System.nanoTime() - startNs, batch.size(), changed, pressureSnap.pressured());
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
        if (!config.verboseLog) {
            return;
        }
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

    @Nonnull
    public RuntimeSnapshot snapshot() {
        return snapshot;
    }

    private void publishPlayerRow(@Nonnull PlayerRef ref, @Nonnull String worldName,
                                  @Nonnull ClientViewRadiusController.Decision decision) {
        UUID playerId = ref.getUuid();
        if (playerId == null) {
            return;
        }
        ChunkTracker tracker = ref.getChunkTracker();
        int loaded = tracker == null ? 0 : tracker.getLoadedSectionsCount();
        int loading = tracker == null ? 0 : tracker.getLoadingSectionsCount();
        int rate = tracker == null ? 0 : tracker.getMaxSectionsPerSecond();
        int tickRate = tracker == null ? 0 : tracker.getMaxSectionsPerTick();
        StreamRateController.Applied applied = stream.lastApplied(playerId);
        playerSnapshotScratch.put(playerId, new RuntimeSnapshot.PlayerRow(
                playerId, decision.name(), worldName, loaded, loading, rate, tickRate, applied.tier(),
                applied.loadingDelta(), applied.msptAverage(), applied.msptLast(), applied.protectionCause(),
                decision.chunkCurrent(), decision.chunkTarget(), decision.entCurrent(), decision.entTarget(),
                decision.visualCandidates(), decision.visualVisible(), decision.visualPressure(),
                decision.visualEmergency(), decision.line()));
    }

    private void publishSnapshot() {
        if (playerSnapshotScratch.isEmpty() && worldSnapshotScratch.isEmpty()) {
            return;
        }
        snapshot = new RuntimeSnapshot(
                System.currentTimeMillis(),
                lastOnlineCount,
                List.copyOf(playerSnapshotScratch.values()),
                Map.copyOf(worldSnapshotScratch));
    }

    private static String shortId(UUID uuid) {
        String text = uuid.toString();
        return text.length() >= 8 ? text.substring(0, 8) : text;
    }

    /** Scheduler-owned collection buffer, world-thread-owned while queued. */
    private static final class StreamWorldBatch {
        private final UUID worldUuid;
        private final ArrayList<PlayerRef> players = new ArrayList<>(8);
        private long generation;
        private long sampleTimeMs;
        private boolean queued;

        StreamWorldBatch(UUID worldUuid) {
            this.worldUuid = worldUuid;
        }

        synchronized boolean collect(long currentGeneration, long nowMs, PlayerRef ref) {
            if (queued) {
                return false;
            }
            boolean firstForTick = generation != currentGeneration;
            if (firstForTick) {
                generation = currentGeneration;
                sampleTimeMs = nowMs;
                players.clear();
            }
            players.add(ref);
            return firstForTick;
        }

        synchronized boolean tryQueue(long currentGeneration) {
            if (queued || generation != currentGeneration || players.isEmpty()) {
                return false;
            }
            queued = true;
            return true;
        }

        UUID worldUuid() {
            return worldUuid;
        }

        synchronized List<PlayerRef> players() {
            return players;
        }

        synchronized long sampleTimeMs() {
            return sampleTimeMs;
        }

        synchronized void complete() {
            players.clear();
            queued = false;
        }
    }

    public void forgetPlayer(UUID playerId) {
        controller.forget(playerId);
        stream.forget(playerId);
        playerSnapshotScratch.remove(playerId);
        VisualLoadRegistry.remove(playerId);
        publishSnapshot();
    }

    public void optimizationChanged(@Nonnull PlayerRef playerRef, boolean enabled) {
        UUID playerId = playerRef.getUuid();
        if (playerId == null) {
            return;
        }
        UUID worldId = playerRef.getWorldUuid();
        World world = worldId == null ? null : Universe.get().getWorld(worldId);
        if (!enabled && world != null && world.isAlive()) {
            world.execute(() -> {
                controller.restoreOne(playerRef);
                stream.restoreOne(playerRef);
                forgetPlayer(playerId);
            });
            return;
        }
        forgetPlayer(playerId);
    }

    private void clearOnlineState() {
        onlineScratch.clear();
        worldScratch.clear();
        activeWorldIds = Set.of();
        worldNames.clear();
        pressure.releaseInactiveWorlds(Set.of(), config);
        controller.clear();
        stream.clear();
        playerSnapshotScratch.clear();
        worldSnapshotScratch.clear();
        streamWorldBatches.clear();
        lastOnlineCount = 0;
        snapshot = RuntimeSnapshot.EMPTY;
        applyEntityLod();
    }

    public synchronized void shutdown() {
        if (!running && scheduler == null) {
            return;
        }
        running = false;
        if (tickFuture != null) {
            tickFuture.cancel(false);
            tickFuture = null;
        }
        if (streamFuture != null) {
            streamFuture.cancel(false);
            streamFuture = null;
        }
        if (scheduler != null) {
            scheduler.shutdownNow();
            scheduler = null;
        }

        Map<UUID, List<PlayerRef>> playersByWorld = new HashMap<>();
        Collection<PlayerRef> online = Universe.get().getPlayers();
        if (online != null) {
            for (PlayerRef ref : online) {
                if (ref == null || !ref.isValid() || ref.getWorldUuid() == null) {
                    continue;
                }
                playersByWorld.computeIfAbsent(ref.getWorldUuid(), ignored -> new ArrayList<>(4)).add(ref);
            }
        }
        Set<UUID> restoreWorlds = new HashSet<>(playersByWorld.keySet());
        restoreWorlds.addAll(pressure.worldIds());
        List<CompletableFuture<Void>> pending = new ArrayList<>();
        for (UUID worldId : restoreWorlds) {
            World world = Universe.get().getWorld(worldId);
            if (world == null || !world.isAlive()) {
                continue;
            }
            List<PlayerRef> batch = playersByWorld.getOrDefault(worldId, List.of());
            CompletableFuture<Void> done = new CompletableFuture<>();
            pending.add(done);
            world.execute(() -> {
                try {
                    controller.restoreAll(batch);
                    stream.restoreAll(batch);
                    pressure.restoreWorld(world, config);
                    done.complete(null);
                } catch (RuntimeException ex) {
                    done.completeExceptionally(ex);
                }
            });
        }
        CompletableFuture<Void> restoreAll = CompletableFuture.allOf(
                pending.toArray(new CompletableFuture[0]));
        boolean restored = pending.isEmpty();
        if (!restored) {
            try {
                restoreAll.get(2, TimeUnit.SECONDS);
                restored = true;
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                plugin.getLogger().atWarning().withCause(interrupted)
                        .log("QuantumHy restore was interrupted");
            } catch (TimeoutException timedOut) {
                plugin.getLogger().atWarning().withCause(timedOut)
                        .log("QuantumHy restore timed out for one or more worlds");
            } catch (ExecutionException failed) {
                plugin.getLogger().atWarning().withCause(failed)
                        .log("QuantumHy restore failed in one or more worlds");
            }
        }

        EntityTrackerSystems.LODCull.ENTITY_LOD_RATIO = originalEntityLodRatio;
        LeanCoreBridge.restoreOwnership();
        pressure.deactivate();
        if (restored) {
            clearAdaptiveState();
        } else {
            restoreAll.whenComplete((ignored, failure) -> clearAdaptiveState());
        }
        playerSnapshotScratch.clear();
        worldSnapshotScratch.clear();
        streamWorldBatches.clear();
        streamTouchedScratch.clear();
        VisualLoadRegistry.clear();
        EntityCullSystem.clearSession();
        SpawnStreamPauseSystem.clearSession();
        worldNames.clear();
        activeWorldIds = Set.of();
        snapshot = RuntimeSnapshot.EMPTY;
    }

    private void clearAdaptiveState() {
        pressure.clearSession();
        controller.clear();
        stream.clear();
    }
}
