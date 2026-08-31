package com.durkz.quantumhy.runtime;

import com.durkz.quantumhy.QuantumHyPlugin;
import com.durkz.quantumhy.config.QuantumHyConfig;
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
    private final StreamRateController stream;
    private final PressureGovernor pressure;
    private final double originalEntityLodRatio;

    private ScheduledExecutorService scheduler;
    private ScheduledFuture<?> tickFuture;
    private ScheduledFuture<?> streamFuture;
    private volatile boolean running;

    private boolean leanCoreHandled;
    private int leanCoreAttempts;

    /** Reused each tick to avoid allocating maps/lists on the 5s cold path. */
    private final Map<UUID, List<PlayerRef>> worldScratch = new HashMap<>();
    private final Set<UUID> onlineScratch = new HashSet<>();
    private final ArrayList<List<PlayerRef>> listPool = new ArrayList<>();

    /** Reused by the 250ms path. One queued batch per world prevents world-thread backlog. */
    private final ConcurrentHashMap<UUID, StreamWorldBatch> streamWorldBatches = new ConcurrentHashMap<>();
    private final ArrayList<StreamWorldBatch> streamTouchedScratch = new ArrayList<>(4);
    private long streamGeneration;

    private final ConcurrentHashMap<UUID, RuntimeSnapshot.PlayerRow> playerSnapshotScratch = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, RuntimeSnapshot.WorldRow> worldSnapshotScratch = new ConcurrentHashMap<>();
    private volatile RuntimeSnapshot snapshot = RuntimeSnapshot.EMPTY;
    private volatile int lastOnlineCount;

    public FpsRuntime(QuantumHyPlugin plugin, QuantumHyConfig config) {
        this.plugin = plugin;
        this.config = config;
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
                snapshot = RuntimeSnapshot.EMPTY;
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
                worldScratch.computeIfAbsent(worldUuid, ignored -> borrowList()).add(ref);
            }
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
                world.execute(() -> {
                    try {
                        runWorldPass(world, worldUuid, batch);
                    } finally {
                        returnList(batch);
                    }
                });
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
                StreamRateController.Transition transition = stream.applyOne(
                        ref, health, pressureSnap.pressured(), nowMs);
                if (transition != null) {
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
        if (!running) {
            return;
        }
        String worldName = world.getName();
        long pressureStartNs = System.nanoTime();
        PressureGovernor.Snapshot pressureSnap = pressure.update(world, config, config.tickIntervalSeconds);
        RuntimeMetrics.pressure(System.nanoTime() - pressureStartNs, pressureSnap.msptAvg10s(),
                pressureSnap.msptLast(), pressureSnap.pressured());
        pressure.applyEntityLod(config, pressureSnap);
        PressureGovernor.ViewPassContext pass = pressure.viewContext(config, pressureSnap);

        long startNs = System.nanoTime();
        long deadlineNs = config.worldPassBudgetMs <= 0
                ? Long.MAX_VALUE
                : startNs + (long) config.worldPassBudgetMs * 1_000_000L;

        int changed = 0;
        StringBuilder details = config.verboseLog ? new StringBuilder() : null;
        for (PlayerRef ref : batch) {
            try {
                ClientViewRadiusController.Decision decision = controller.applyOne(ref, world, pass, deadlineNs);
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
        } else if (changed > 0) {
            plugin.getLogger().atInfo().log("pass [world=%s] changed %d view radius", shortId(worldUuid), changed);
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
                decision.name(), worldName, loaded, loading, rate, tickRate, applied.tier(),
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

    private List<PlayerRef> borrowList() {
        if (listPool.isEmpty()) {
            return new ArrayList<>(8);
        }
        return listPool.remove(listPool.size() - 1);
    }

    private void returnList(@Nonnull List<PlayerRef> batch) {
        batch.clear();
        if (listPool.size() < 16) {
            listPool.add(batch);
        }
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

    public void shutdown() {
        running = false;
        Collection<PlayerRef> online = Universe.get().getPlayers();
        controller.restoreAll(online);
        stream.restoreAll(online);
        pressure.shutdown(config);
        EntityTrackerSystems.LODCull.ENTITY_LOD_RATIO = originalEntityLodRatio;
        LeanCoreBridge.restoreOwnership();
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
        playerSnapshotScratch.clear();
        worldSnapshotScratch.clear();
        streamWorldBatches.clear();
        streamTouchedScratch.clear();
        VisualLoadRegistry.clear();
        snapshot = RuntimeSnapshot.EMPTY;
    }
}
