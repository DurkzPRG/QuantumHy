package com.durkz.quantumhy.view;

import com.durkz.quantumhy.config.QuantumHyConfig;
import com.durkz.quantumhy.integration.LeanCoreBridge;
import com.durkz.quantumhy.pressure.PressureGovernor.ViewPassContext;
import com.durkz.quantumhy.runtime.RuntimeMetrics;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.math.util.ChunkUtil;
import com.hypixel.hytale.math.vector.Transform;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.modules.entity.player.ChunkTracker;
import com.hypixel.hytale.server.core.modules.entity.tracker.EntityTrackerSystems;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.chunk.WorldChunk;
import com.hypixel.hytale.server.core.universe.world.chunk.section.EntitySection;
import com.hypixel.hytale.server.core.universe.world.storage.ChunkStore;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;

import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import javax.annotation.Nullable;

/**
 * Adapts each player's render load to the entity density around them. One smoothed signal drives two
 * levers, both capped at what the player started with so we only ever shrink: the chunk view radius
 * ({@link Player#setClientViewRadius(int)}) and the entity stream radius in blocks
 * ({@link EntityTrackerSystems.EntityViewer#viewRadiusBlocks}).
 *
 * Both writes also lower what the live getters report, so the player's real ceiling can't be read
 * back once we shrink. We remember the first (highest) value seen per player and ramp toward that.
 */
public final class ClientViewRadiusController {

    private static final int ENTITY_BLOCKS_APPLY_STEP = 4;
    static final long DENSITY_CACHE_MAX_AGE_NANOS = 15_000_000_000L;

    private final QuantumHyConfig config;
    private final DensityScanPlan densityScanPlan;
    private final Map<UUID, PlayerState> players = new ConcurrentHashMap<>();
    private final Map<UUID, Decision> lastDecisions = new ConcurrentHashMap<>();

    public ClientViewRadiusController(QuantumHyConfig config) {
        this.config = config;
        this.densityScanPlan = DensityScanPlan.of(config.densityScanChunkRadius,
                config.densityRingWeighting, config.densityRingEdgeWeight);
    }

    /** What QuantumHy decided for one player on a pass, for both the actuation and the log. */
    public record Decision(
            String name,
            int entities,
            int chunks,
            double smoothed,
            int chunkCurrent,
            int chunkTarget,
            boolean chunkApplied,
            boolean chunkHeld,
            int entCurrent,
            int entTarget,
            boolean entApplied,
            int lodExcluded,
            String reason,
            int visualCandidates,
            int visualVisible,
            double visualPressure,
            boolean visualEmergency
    ) {
        public boolean applied() {
            return chunkApplied || entApplied;
        }

        public String line() {
            if ("yield".equals(reason)) {
                return name + " [yield to LeanCore]";
            }
            if ("opt-out".equals(reason)) {
                return name + " [optimization disabled]";
            }
            String raw = chunks <= 0 ? "?" : String.format(Locale.ROOT, "%.1f", (double) entities / chunks);
            String chunk = "cl " + chunkCurrent
                    + (chunkApplied ? "->" + chunkTarget : (chunkHeld ? "!" + chunkTarget : "=" + chunkTarget));
            String ent = entCurrent < 0
                    ? "ent off"
                    : "ent " + entCurrent + (entApplied ? "->" + entTarget : "=" + entTarget)
                            + (lodExcluded > 0 ? " lod-" + lodExcluded : "");
            return name + " " + entities + "/" + chunks + "ch " + raw + "/ch~"
                    + String.format(Locale.ROOT, "%.1f", smoothed) + " " + chunk + " " + ent
                    + " vis " + visualVisible + "/" + visualCandidates
                    + String.format(Locale.ROOT, " p=%.2f", visualPressure)
                    + (visualEmergency ? " emergency" : "") + " [" + reason + "]";
        }
    }

    public Decision applyOne(PlayerRef playerRef, World world) {
        return applyOne(playerRef, world, ViewPassContext.fromConfig(config), Long.MAX_VALUE);
    }

    /**
     * Decides and applies the targets for one player. Must run on that player's world thread.
     * Returns the decision (for logging), or {@code null} if the player couldn't be read.
     */
    public Decision applyOne(PlayerRef playerRef, World world, ViewPassContext pass) {
        return applyOne(playerRef, world, pass, Long.MAX_VALUE);
    }

    /**
     * @param writeDeadlineNanos after this nanoTime, sample and log but do not write radii
     */
    public Decision applyOne(PlayerRef playerRef, World world, ViewPassContext pass, long writeDeadlineNanos) {
        if (playerRef == null || !playerRef.isValid()) {
            return null;
        }
        String name = nameOf(playerRef);

        if (!LeanCoreBridge.shouldQuantumHyWriteViewRadius(config)) {
            return new Decision(name, -1, 0, 0, -1, -1, false, false, -1, -1, false, 0,
                    "yield", 0, 0, 0.0D, false);
        }

        Ref<EntityStore> ref = playerRef.getReference();
        if (ref == null) {
            return null;
        }
        Store<EntityStore> store = ref.getStore();
        Player player = store.getComponent(ref, Player.getComponentType());
        if (player == null) {
            return null;
        }

        PlayerState state = stateFor(playerRef.getUuid());
        ChunkTracker tracker = playerRef.getChunkTracker();
        int chunkCurrent = player.getClientViewRadius();
        int chunkCeiling = ceiling(state, State.CHUNK, Math.max(1, player.getViewRadius()));
        int radiusCeiling = chunkBase(chunkCeiling);
        int radiusMinimum = Math.min(config.minClientViewRadius, radiusCeiling);
        EntityTrackerSystems.EntityViewer viewer = config.adaptEntityRadius
                ? store.getComponent(ref, EntityTrackerSystems.EntityViewer.getComponentType())
                : null;
        int observedEntityRadius = viewer == null ? 0 : Math.max(0, viewer.viewRadiusBlocks);
        int entityCeiling = observedEntityRadius > 0
                ? ceiling(state, State.ENTITY, observedEntityRadius)
                : observedEntityRadius;

        int centerX = 0;
        int centerZ = 0;
        boolean haveChunkPos = false;
        Transform transform = playerRef.getTransform();
        if (transform != null && transform.getPosition() != null) {
            centerX = ChunkUtil.chunkCoordinate(transform.getPosition().x);
            centerZ = ChunkUtil.chunkCoordinate(transform.getPosition().z);
            haveChunkPos = true;
        }
        boolean movingFast = haveChunkPos && state != null
                && ViewAdaptPolicy.movingFast(state.hasChunkPos, state.lastChunkX, state.lastChunkZ, centerX, centerZ);

        int loadSignal = tracker == null
                ? 0
                : tracker.getLoadedSectionsCount() + tracker.getLoadingSectionsCount();
        boolean calm = !movingFast && ViewAdaptPolicy.loadIsCalm(
                loadSignal, config.chunkLoadLowChunks, config.chunkLoadShrinkEnabled);
        if (state != null) {
            state.calmPasses = ViewAdaptPolicy.nextCalmPasses(state.calmPasses, calm);
        }
        boolean streaming = isStreaming(tracker);
        long nowNanos = System.nanoTime();
        boolean cacheHit = state != null && state.hasDensityCache && haveChunkPos
                && state.cacheChunkX == centerX && state.cacheChunkZ == centerZ
                && densityCacheFresh(nowNanos, state.densitySampleNanos, DENSITY_CACHE_MAX_AGE_NANOS);
        boolean firstPass = state != null && !state.hasPassedOnce;
        boolean atMin = chunkCurrent <= radiusMinimum;
        boolean trackerExpandOk = state != null
                && ViewAdaptPolicy.canExpand(state.calmPasses, config.expandHysteresisPasses,
                pass.pressured(), streaming, true, movingFast);
        boolean skipScan = ViewAdaptPolicy.shouldSkipDensityScan(
                firstPass, movingFast, streaming, cacheHit, atMin, trackerExpandOk);

        long densityStartNs = System.nanoTime();
        boolean densityCached = false;
        Density density;
        if (skipScan && cacheHit && state != null) {
            density = state.cachedDensity;
            densityCached = true;
        } else if (skipScan) {
            density = Density.DEFERRED;
        } else {
            density = sampleDensity(playerRef, world, state);
        }
        RuntimeMetrics.density(System.nanoTime() - densityStartNs, density.chunks(), density.entities(), densityCached);
        boolean allowWrites = System.nanoTime() < writeDeadlineNanos;
        boolean sampleCovered = density.valid()
                && ViewAdaptPolicy.densityCoveredColumns(density.chunks(), densityScanPlan.columns());

        if (!density.valid() && density != Density.DEFERRED) {
            if (haveChunkPos && state != null) {
                state.hasPassedOnce = true;
                state.hasChunkPos = true;
                state.lastChunkX = centerX;
                state.lastChunkZ = centerZ;
            }
            Decision skipped = new Decision(name, density.entities(), density.chunks(), -1,
                    chunkCurrent, chunkCurrent, false, false,
                    -1, -1, false, 0, "no-sample", 0, 0, 0.0D, false);
            UUID playerId = playerRef.getUuid();
            if (playerId != null) {
                lastDecisions.put(playerId, skipped);
            }
            return skipped;
        }

        double smoothed;
        if (density.valid() && density != Density.DEFERRED) {
            smoothed = smooth(state, density.perChunk());
        } else if (state != null && state.hasSmoothed) {
            smoothed = state.smoothed;
        } else {
            smoothed = 0.0D;
        }
        double densityFrac = sampleCovered ? pass.shrinkFraction(smoothed) : 0.0D;
        double chunkLoadFrac = chunkLoadShrinkFraction(tracker, config);
        VisualLoadRegistry.State visual = VisualLoadRegistry.state(playerRef.getUuid());
        int visualCandidates = visual == null ? 0 : visual.candidates();
        int visualVisible = visual == null ? 0 : visual.visible();
        int sampledEntityRadius = visual == null ? observedEntityRadius : visual.entityRadiusBlocks();
        double projectedCandidates = VisualPressurePolicy.projectedCandidates(
                visualCandidates, sampledEntityRadius, Math.max(sampledEntityRadius, entityCeiling));
        double entityRatio = VisualPressurePolicy.entityRatio(
                projectedCandidates, config.maxVisibleEntitiesPerPlayer);
        double averageChurn = visual == null ? 0.0D : visual.drainAverageChurn();
        double churnRatio = config.maxVisibleEntitiesPerPlayer <= 0
                ? 0.0D
                : averageChurn * 4.0D / config.maxVisibleEntitiesPerPlayer;
        double effectiveEntityRatio = Math.max(entityRatio, churnRatio);
        int loadingSections = tracker == null ? 0 : tracker.getLoadingSectionsCount();
        double backlogRatio = VisualPressurePolicy.backlogRatio(
                loadingSections, config.streamingBacklogThreshold);
        double visualPressure = updateVisualPressure(state, effectiveEntityRatio, backlogRatio);
        boolean visualEmergency = state != null && state.visualEmergency;
        double entityRawFrac = Math.max(
                ViewAdaptPolicy.combinedShrinkFraction(
                        densityFrac, chunkLoadFrac, config.baselineShrinkFraction),
                VisualPressurePolicy.entityShrinkFraction(effectiveEntityRatio));
        double rawFrac;
        if (config.adaptiveTerrainViewEnabled) {
            rawFrac = ViewAdaptPolicy.terrainShrinkFraction(
                    densityFrac, chunkLoadFrac, config.baselineShrinkFraction);
        } else if (config.emergencyTerrainTrimEnabled && visualEmergency) {
            rawFrac = VisualPressurePolicy.emergencyTerrainFraction(visualPressure);
        } else {
            rawFrac = 0.0D;
        }

        boolean canExpand = state != null
                && ViewAdaptPolicy.canExpand(state.calmPasses, config.expandHysteresisPasses,
                pass.pressured(), streaming, sampleCovered, movingFast);
        double frac = ViewAdaptPolicy.ratchetFrac(
                rawFrac,
                state == null ? 0.0D : state.lastAppliedFrac,
                state != null && state.hasAppliedFrac,
                canExpand);
        String reason = config.adaptiveTerrainViewEnabled
                ? shrinkReason(true, frac, densityFrac, chunkLoadFrac, config.baselineShrinkFraction)
                : (visualEmergency && config.emergencyTerrainTrimEnabled
                ? "visual-emergency" : "terrain-preserved");
        if (state != null && state.hasAppliedFrac && frac > rawFrac + 1e-6 && !canExpand) {
            reason = "hold";
        }
        if ((config.adaptiveTerrainViewEnabled || config.emergencyTerrainTrimEnabled)
                && pass.pressured() && chunkCurrent < radiusCeiling && !canExpand) {
            reason = "pressure";
        }

        int chunkIdeal = scale(radiusCeiling, radiusMinimum, frac);
        int shrinkCap = ViewAdaptPolicy.chunkShrinkCap(config.maxShrinkChunksPerPass, pass.pressured());
        int chunkTarget = ViewAdaptPolicy.rampToward(
                chunkCurrent, chunkIdeal, config.maxExpandChunksPerPass, shrinkCap);
        boolean chunkApplied = false;
        boolean chunkHeld = false;
        boolean terrainControlActive = config.adaptiveTerrainViewEnabled
                || (config.emergencyTerrainTrimEnabled && visualEmergency)
                || (state != null && state.terrainWasControlled && chunkCurrent < radiusCeiling);
        if (!terrainControlActive) {
            chunkTarget = chunkCurrent;
        } else if (!config.adaptiveTerrainViewEnabled && !visualEmergency) {
            chunkTarget = ViewAdaptPolicy.rampToward(
                    chunkCurrent, radiusCeiling, config.maxExpandChunksPerPass, shrinkCap);
            reason = "terrain-restore";
        }
        if (chunkTarget < chunkCurrent
                && !shouldApplyChunkTarget(chunkCurrent, chunkIdeal, chunkTarget, config.minViewRadiusDelta)) {
            chunkTarget = chunkCurrent;
        }
        boolean chunkWantsWrite = terrainControlActive && chunkTarget != chunkCurrent;
        if (chunkWantsWrite && (streaming || !allowWrites)) {
            chunkHeld = true;
        } else if (chunkWantsWrite) {
            player.setClientViewRadius(chunkTarget);
            chunkApplied = true;
        }

        int entCurrent = -1;
        int entTarget = -1;
        boolean entApplied = false;
        int lodExcluded = 0;
        if (viewer != null && viewer.viewRadiusBlocks > 0) {
            entCurrent = viewer.viewRadiusBlocks;
            lodExcluded = viewer.lodExcludedCount;
            int entBase = Math.max(entCurrent, entityCeiling);
            int entMin = Math.min(config.minEntityViewBlocks, entBase);
            int entIdeal = entityRawFrac >= 1.0D ? entMin : scale(entBase, entMin, entityRawFrac);
            int entShrink = entityRawFrac >= 1.0D
                    ? Math.max(entCurrent - entMin, 1)
                    : ViewAdaptPolicy.entityShrinkCap(
                    config.maxExpandEntityBlocksPerPass,
                    config.maxExpandChunksPerPass,
                    config.maxShrinkChunksPerPass,
                    pass.pressured());
            entTarget = ViewAdaptPolicy.rampToward(
                    entCurrent, entIdeal, config.maxExpandEntityBlocksPerPass, entShrink);
            if (entTarget > entCurrent && !canExpand) {
                entTarget = entCurrent;
            }
            if (Math.abs(entTarget - entCurrent) >= ENTITY_BLOCKS_APPLY_STEP) {
                if (allowWrites && !streaming) {
                    viewer.viewRadiusBlocks = entTarget;
                    entApplied = true;
                } else {
                    chunkHeld = true;
                }
            }
        }

        if (state != null) {
            state.hasPassedOnce = true;
            int liveRadius = chunkApplied ? chunkTarget : chunkCurrent;
            if (terrainControlActive) {
                state.lastAppliedFrac = ViewAdaptPolicy.fracFromRadius(
                        radiusCeiling, radiusMinimum, liveRadius);
                state.hasAppliedFrac = true;
                state.terrainWasControlled = chunkTarget < radiusCeiling;
            } else {
                state.lastAppliedFrac = 0.0D;
                state.hasAppliedFrac = false;
            }
            if (haveChunkPos) {
                state.hasChunkPos = true;
                state.lastChunkX = centerX;
                state.lastChunkZ = centerZ;
            }
        }

        Decision decision = new Decision(name, density.entities(), density.chunks(), smoothed,
                chunkCurrent, chunkTarget, chunkApplied, chunkHeld, entCurrent, entTarget, entApplied,
                lodExcluded, reason, visualCandidates, visualVisible, visualPressure, visualEmergency);
        UUID playerId = playerRef.getUuid();
        if (playerId != null) {
            lastDecisions.put(playerId, decision);
        }
        return decision;
    }

    @Nullable
    public Decision lastDecision(@Nullable UUID playerId) {
        return playerId == null ? null : lastDecisions.get(playerId);
    }

    @Nullable
    public Decision observeDisabled(@Nullable PlayerRef playerRef) {
        if (playerRef == null || !playerRef.isValid()) {
            return null;
        }
        Ref<EntityStore> ref = playerRef.getReference();
        if (ref == null) {
            return null;
        }
        Store<EntityStore> store = ref.getStore();
        Player player = store.getComponent(ref, Player.getComponentType());
        if (player == null) {
            return null;
        }
        EntityTrackerSystems.EntityViewer viewer = store.getComponent(
                ref, EntityTrackerSystems.EntityViewer.getComponentType());
        int entityRadius = viewer == null ? -1 : viewer.viewRadiusBlocks;
        return new Decision(nameOf(playerRef), -1, 0, 0.0D,
                player.getClientViewRadius(), player.getClientViewRadius(), false, false,
                entityRadius, entityRadius, false, viewer == null ? 0 : viewer.lodExcludedCount,
                "opt-out", 0, 0, 0.0D, false);
    }

    /** Drop cached state for players no longer online, so the map can't grow without bound. */
    public void retain(Set<UUID> online) {
        players.entrySet().removeIf(entry -> {
            if (online.contains(entry.getKey())) {
                return false;
            }
            return true;
        });
        lastDecisions.keySet().retainAll(online);
        VisualLoadRegistry.retain(online);
    }

    public void forget(@Nullable UUID playerId) {
        if (playerId == null) {
            return;
        }
        players.remove(playerId);
        lastDecisions.remove(playerId);
        VisualLoadRegistry.remove(playerId);
    }

    public void clear() {
        players.clear();
        lastDecisions.clear();
        VisualLoadRegistry.clear();
    }

    /** Restore radii that QuantumHy changed before the runtime is stopped. */
    public void restoreAll(@Nullable Iterable<PlayerRef> onlinePlayers) {
        if (onlinePlayers == null) {
            return;
        }
        for (PlayerRef playerRef : onlinePlayers) {
            if (playerRef == null || !playerRef.isValid()) {
                continue;
            }
            restoreOne(playerRef);
        }
    }

    public void restoreOne(@Nullable PlayerRef playerRef) {
        if (playerRef == null || !playerRef.isValid()) {
            return;
        }
        UUID playerId = playerRef.getUuid();
        PlayerState state = playerId == null ? null : players.get(playerId);
        Ref<EntityStore> ref = playerRef.getReference();
        if (state == null || ref == null) {
            return;
        }
        Store<EntityStore> store = ref.getStore();
        Player player = store.getComponent(ref, Player.getComponentType());
        if (player != null && state.chunkCeiling > 0
                && player.getClientViewRadius() < state.chunkCeiling) {
            player.setClientViewRadius(state.chunkCeiling);
        }
        EntityTrackerSystems.EntityViewer viewer = store.getComponent(
                ref, EntityTrackerSystems.EntityViewer.getComponentType());
        if (viewer != null && state.entityCeiling > 0
                && viewer.viewRadiusBlocks < state.entityCeiling) {
            viewer.viewRadiusBlocks = state.entityCeiling;
        }
    }

    private double updateVisualPressure(PlayerState state, double entityRatio, double backlogRatio) {
        double sample = VisualPressurePolicy.emergencyScore(entityRatio, backlogRatio);
        if (state == null) {
            return sample;
        }
        state.visualPressure = VisualPressurePolicy.ema(
                state.visualPressure, sample, 0.25D, state.hasVisualPressure);
        state.hasVisualPressure = true;
        int elapsed = Math.max(1, config.tickIntervalSeconds);
        if (!state.visualEmergency) {
            state.visualExitSeconds = 0;
            state.visualEnterSeconds = state.visualPressure >= 1.0D
                    ? state.visualEnterSeconds + elapsed : 0;
            if (state.visualEnterSeconds >= 5) {
                state.visualEmergency = true;
                state.visualEnterSeconds = 0;
            }
        } else {
            state.visualEnterSeconds = 0;
            boolean calm = entityRatio <= 1.0D && backlogRatio <= 1.0D;
            state.visualExitSeconds = calm ? state.visualExitSeconds + elapsed : 0;
            if (state.visualExitSeconds >= 15) {
                state.visualEmergency = false;
                state.visualExitSeconds = 0;
            }
        }
        return state.visualPressure;
    }

    /** Counts entities in the chunks around a player as a stand-in for client render cost. */
    private Density sampleDensity(PlayerRef ref, World world, PlayerState state) {
        Transform transform = ref.getTransform();
        if (transform == null || transform.getPosition() == null || world == null || !world.isAlive()) {
            return Density.NONE;
        }
        ChunkStore chunkStore = world.getChunkStore();
        if (chunkStore == null) {
            return Density.NONE;
        }
        double playerY = transform.getPosition().y;
        int centerX = ChunkUtil.chunkCoordinate(transform.getPosition().x);
        int centerZ = ChunkUtil.chunkCoordinate(transform.getPosition().z);
        int maxVert = Math.max(0, config.maxEntityVerticalDistance);

        int rawEntities = 0;
        double weightedEntities = 0;
        int chunks = 0;
        for (int column = 0; column < densityScanPlan.columns(); column++) {
                int chunkX = centerX + densityScanPlan.dx(column);
                int chunkZ = centerZ + densityScanPlan.dz(column);
                long index = ChunkUtil.indexChunk(chunkX, chunkZ);
                WorldChunk worldChunk = chunkStore.getChunkComponent(index, WorldChunk.getComponentType());
                if (worldChunk == null) {
                    continue;
                }
                chunks++;
                int count = 0;
                for (int sectionY = ChunkUtil.MIN_SECTION; sectionY < ChunkUtil.HEIGHT_SECTIONS; sectionY++) {
                    if (maxVert > 0 && !sectionOverlapsVerticalWindow(sectionY, playerY, maxVert)) {
                        continue;
                    }
                    Ref<ChunkStore> sectionRef = chunkStore.getChunkSectionReference(chunkX, sectionY, chunkZ);
                    if (sectionRef == null) {
                        continue;
                    }
                    EntitySection section =
                            sectionRef.getStore().getComponent(sectionRef, EntitySection.getComponentType());
                    if (section == null) {
                        continue;
                    }
                    count += section.getEntityReferences().size() + section.getEntityHolders().size();
                }
                if (count == 0) {
                    continue;
                }
                rawEntities += count;
                weightedEntities += count * densityScanPlan.weight(column);
        }
        Density sampled = new Density(rawEntities, weightedEntities, chunks);
        if (state != null) {
            if (sampled.valid()) {
                state.hasDensityCache = true;
                state.cacheChunkX = centerX;
                state.cacheChunkZ = centerZ;
                state.cachedDensity = sampled;
                state.densitySampleNanos = System.nanoTime();
            } else {
                state.hasDensityCache = false;
            }
        }
        return sampled;
    }

    /**
     * True when section {@code sectionY} overlaps the same vertical window EntityCull uses
     * ({@code |dy| <= maxEntityVerticalDistance}). Skips cave/ceiling bands the client already drops.
     */
    static boolean sectionOverlapsVerticalWindow(int sectionY, double playerY, int maxVertBlocks) {
        int sectionMin = sectionY * ChunkUtil.SIZE;
        int sectionMax = sectionMin + ChunkUtil.SIZE;
        double windowMin = playerY - maxVertBlocks;
        double windowMax = playerY + maxVertBlocks;
        return sectionMax > windowMin && sectionMin < windowMax;
    }

    private static double chunkLoadShrinkFraction(@Nullable ChunkTracker tracker, QuantumHyConfig cfg) {
        if (!cfg.chunkLoadShrinkEnabled || tracker == null) {
            return 0.0D;
        }
        int signal = tracker.getLoadedSectionsCount() + tracker.getLoadingSectionsCount();
        return smoothstepShrink(signal, cfg.chunkLoadLowChunks, cfg.chunkLoadHighChunks);
    }

    private static double smoothstepShrink(double value, double low, double high) {
        if (value <= low) {
            return 0.0D;
        }
        if (value >= high) {
            return 1.0D;
        }
        double t = (value - low) / (high - low);
        return t * t * (3.0D - 2.0D * t);
    }

    private static String shrinkReason(boolean valid, double frac, double densityFrac,
            double chunkLoadFrac, double baseline) {
        if (!valid) {
            return "no-sample";
        }
        if (frac <= 0) {
            return "open";
        }
        if (frac >= 1) {
            return "min";
        }
        boolean d = densityFrac >= frac - 1e-6;
        boolean c = chunkLoadFrac >= frac - 1e-6;
        boolean b = baseline >= frac - 1e-6;
        if (d && c) {
            return "density+chunk-load";
        }
        if (d && b) {
            return "density+baseline";
        }
        if (c && b) {
            return "chunk-load+baseline";
        }
        if (d) {
            return "density";
        }
        if (c) {
            return "chunk-load";
        }
        if (b) {
            return "baseline";
        }
        return "blend";
    }

    /** Chunk base to ramp toward in the open: the hard cap if set, else the player's own ceiling. */
    private int chunkBase(int ceiling) {
        return effectiveChunkBase(ceiling, config.targetClientViewRadius,
                config.minClientViewRadius, config.maxClientViewRadius);
    }

    static int effectiveChunkBase(int ceiling, int target, int minimum, int maximum) {
        int safeCeiling = Math.max(1, ceiling);
        int effectiveMinimum = Math.min(Math.max(1, minimum), safeCeiling);
        int effectiveMaximum = Math.max(effectiveMinimum, Math.min(maximum, safeCeiling));
        return target > 0 ? clamp(target, effectiveMinimum, effectiveMaximum) : safeCeiling;
    }

    static boolean densityCacheFresh(long nowNanos, long sampledAtNanos, long maxAgeNanos) {
        return sampledAtNanos > 0L && nowNanos >= sampledAtNanos
                && nowNanos - sampledAtNanos < maxAgeNanos;
    }

    static boolean shouldApplyChunkTarget(int current, int ideal, int target, int minimumDelta) {
        if (target == current) {
            return false;
        }
        return target > current || current - ideal >= Math.max(1, minimumDelta);
    }

    private PlayerState stateFor(UUID uuid) {
        return uuid == null ? null : players.computeIfAbsent(uuid, k -> new PlayerState());
    }

    /** Highest value seen for a lever; our own writes drag the live value down, so we only raise it. */
    private static int ceiling(PlayerState state, State lever, int observed) {
        if (state == null) {
            return observed;
        }
        return lever == State.CHUNK
                ? (state.chunkCeiling = Math.max(state.chunkCeiling, observed))
                : (state.entityCeiling = Math.max(state.entityCeiling, observed));
    }

    /** Exponential moving average of per-chunk density, so the levers don't chase momentary spikes. */
    private double smooth(PlayerState state, double sample) {
        double alpha = config.densitySmoothing;
        if (state == null || alpha >= 1.0D) {
            return sample;
        }
        double next = state.hasSmoothed ? alpha * sample + (1 - alpha) * state.smoothed : sample;
        state.smoothed = next;
        state.hasSmoothed = true;
        return next;
    }

    private boolean isStreaming(@Nullable ChunkTracker tracker) {
        return config.respectStreamingGrace
                && tracker != null
                && tracker.getLoadingSectionsCount() >= config.streamingBacklogThreshold;
    }

    private static int scale(int base, int min, double frac) {
        if (frac <= 0) {
            return base;
        }
        if (frac >= 1) {
            return min;
        }
        return (int) Math.round(base - frac * (base - min));
    }

    private static String nameOf(PlayerRef playerRef) {
        String username = playerRef.getUsername();
        return username != null && !username.isBlank() ? username : String.valueOf(playerRef.getUuid());
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    private enum State {CHUNK, ENTITY}

    private static final class PlayerState {
        int chunkCeiling;
        int entityCeiling;
        double smoothed;
        boolean hasSmoothed;
        double lastAppliedFrac;
        boolean hasAppliedFrac;
        boolean hasPassedOnce;
        int calmPasses;
        boolean hasDensityCache;
        int cacheChunkX;
        int cacheChunkZ;
        long densitySampleNanos;
        boolean hasChunkPos;
        int lastChunkX;
        int lastChunkZ;
        double visualPressure;
        boolean hasVisualPressure;
        boolean visualEmergency;
        boolean terrainWasControlled;
        int visualEnterSeconds;
        int visualExitSeconds;
        Density cachedDensity = Density.NONE;
    }

    private record Density(int rawEntities, double weightedEntities, int chunks) {
        static final Density NONE = new Density(-1, 0, 0);
        /** Scan skipped this pass; not a real empty disk. */
        static final Density DEFERRED = new Density(0, 0, 0);

        boolean valid() {
            return ViewAdaptPolicy.densityValid(rawEntities, chunks);
        }

        int entities() {
            return rawEntities;
        }

        double perChunk() {
            return chunks <= 0 ? 0.0D : weightedEntities / chunks;
        }
    }
}
