package com.durkz.quantumhy.view;

import com.durkz.quantumhy.config.QuantumHyConfig;
import com.durkz.quantumhy.integration.LeanCoreBridge;
import com.durkz.quantumhy.pressure.PressureGovernor.ViewPassContext;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.math.util.ChunkUtil;
import com.hypixel.hytale.math.vector.Transform;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.modules.entity.player.ChunkTracker;
import com.hypixel.hytale.server.core.modules.entity.tracker.EntityTrackerSystems;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.chunk.EntityChunk;
import com.hypixel.hytale.server.core.universe.world.chunk.WorldChunk;
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

    private final QuantumHyConfig config;
    private final Map<UUID, PlayerState> players = new ConcurrentHashMap<>();
    private final Map<UUID, Decision> lastDecisions = new ConcurrentHashMap<>();

    public ClientViewRadiusController(QuantumHyConfig config) {
        this.config = config;
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
            String reason
    ) {
        public boolean applied() {
            return chunkApplied || entApplied;
        }

        public String line() {
            if ("yield".equals(reason)) {
                return name + " [yield to LeanCore]";
            }
            String raw = chunks <= 0 ? "?" : String.format(Locale.ROOT, "%.1f", (double) entities / chunks);
            String chunk = "cl " + chunkCurrent
                    + (chunkApplied ? "->" + chunkTarget : (chunkHeld ? "!" + chunkTarget : "=" + chunkTarget));
            String ent = entCurrent < 0
                    ? "ent off"
                    : "ent " + entCurrent + (entApplied ? "->" + entTarget : "=" + entTarget)
                            + (lodExcluded > 0 ? " lod-" + lodExcluded : "");
            return name + " " + entities + "/" + chunks + "ch " + raw + "/ch~"
                    + String.format(Locale.ROOT, "%.1f", smoothed) + " " + chunk + " " + ent + " [" + reason + "]";
        }
    }

    public Decision applyOne(PlayerRef playerRef, World world) {
        return applyOne(playerRef, world, ViewPassContext.fromConfig(config));
    }

    /**
     * Decides and applies the targets for one player. Must run on that player's world thread.
     * Returns the decision (for logging), or {@code null} if the player couldn't be read.
     */
    public Decision applyOne(PlayerRef playerRef, World world, ViewPassContext pass) {
        if (playerRef == null || !playerRef.isValid()) {
            return null;
        }
        String name = nameOf(playerRef);

        if (config.yieldToLeanCoreViewRadius) {
            return new Decision(name, -1, 0, 0, -1, -1, false, false, -1, -1, false, 0, "yield");
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
        Density density = sampleDensity(playerRef, world, config);
        double smoothed = density.valid() ? smooth(state, density.perChunk()) : -1;
        ChunkTracker tracker = playerRef.getChunkTracker();
        double densityFrac = density.valid() ? pass.shrinkFraction(smoothed) : 0.0D;
        double chunkLoadFrac = chunkLoadShrinkFraction(tracker, config);
        double frac = combinedShrinkFraction(densityFrac, chunkLoadFrac, config.baselineShrinkFraction);
        String reason = shrinkReason(density.valid(), frac, densityFrac, chunkLoadFrac,
                config.baselineShrinkFraction);

        // Chunk view radius.
        int chunkCurrent = player.getClientViewRadius();
        int chunkBase = chunkBase(ceiling(state, State.CHUNK, Math.max(1, player.getViewRadius())));
        int chunkTarget = scale(chunkBase, config.minClientViewRadius, frac);
        boolean chunkApplied = false;
        boolean chunkHeld = false;
        if (chunkTarget < chunkCurrent && isStreaming(tracker)) {
            chunkHeld = true;
        } else if (Math.abs(chunkTarget - chunkCurrent) >= config.minViewRadiusDelta) {
            player.setClientViewRadius(chunkTarget);
            chunkApplied = true;
        }

        // Entity stream radius in blocks, independent of chunks.
        int entCurrent = -1;
        int entTarget = -1;
        boolean entApplied = false;
        int lodExcluded = 0;
        EntityTrackerSystems.EntityViewer viewer = config.adaptEntityRadius
                ? store.getComponent(ref, EntityTrackerSystems.EntityViewer.getComponentType())
                : null;
        if (viewer != null && viewer.viewRadiusBlocks > 0) {
            entCurrent = viewer.viewRadiusBlocks;
            lodExcluded = viewer.lodExcludedCount;
            int entBase = ceiling(state, State.ENTITY, entCurrent);
            entTarget = scale(entBase, Math.min(config.minEntityViewBlocks, entBase), frac);
            if (Math.abs(entTarget - entCurrent) >= ENTITY_BLOCKS_APPLY_STEP) {
                viewer.viewRadiusBlocks = entTarget;
                entApplied = true;
            }
        }

        applyChunkStreamingSmoothing(tracker, pass);

        Decision decision = new Decision(name, density.entities(), density.chunks(), smoothed,
                chunkCurrent, chunkTarget, chunkApplied, chunkHeld, entCurrent, entTarget, entApplied,
                lodExcluded, reason);
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

    /**
     * Caps how fast chunks stream to this client, so a freshly opened radius arrives spread out
     * instead of as one burst the client has to mesh at once. Idempotent: only writes on change.
     */
    private void applyChunkStreamingSmoothing(@Nullable ChunkTracker tracker, ViewPassContext pass) {
        if (!LeanCoreBridge.shouldQuantumHyWriteChunkRate(config) || tracker == null) {
            return;
        }
        if (pass.maxChunksPerSecond() > 0 && tracker.getMaxChunksPerSecond() != pass.maxChunksPerSecond()) {
            tracker.setMaxChunksPerSecond(pass.maxChunksPerSecond());
        }
        if (pass.maxChunksPerTick() > 0 && tracker.getMaxChunksPerTick() != pass.maxChunksPerTick()) {
            tracker.setMaxChunksPerTick(pass.maxChunksPerTick());
        }
    }

    /** Drop cached state for players no longer online, so the map can't grow without bound. */
    public void retain(Set<UUID> online) {
        players.keySet().retainAll(online);
        lastDecisions.keySet().retainAll(online);
    }

    /** Counts entities in the chunks around a player as a stand-in for client render cost. */
    private Density sampleDensity(PlayerRef ref, World world, QuantumHyConfig cfg) {
        Transform transform = ref.getTransform();
        if (transform == null || transform.getPosition() == null || world == null || !world.isAlive()) {
            return Density.NONE;
        }
        ChunkStore chunkStore = world.getChunkStore();
        if (chunkStore == null) {
            return Density.NONE;
        }
        int centerX = ChunkUtil.chunkCoordinate(transform.getPosition().x);
        int centerZ = ChunkUtil.chunkCoordinate(transform.getPosition().z);
        int radius = Math.max(0, cfg.densityScanChunkRadius);
        int radiusSq = radius * radius;

        int rawEntities = 0;
        double weightedEntities = 0;
        int chunks = 0;
        for (int dz = -radius; dz <= radius; dz++) {
            int dzSq = dz * dz;
            for (int dx = -radius; dx <= radius; dx++) {
                if (dx * dx + dzSq > radiusSq) {
                    continue;
                }
                long index = ChunkUtil.indexChunk(centerX + dx, centerZ + dz);
                WorldChunk worldChunk = chunkStore.getChunkComponent(index, WorldChunk.getComponentType());
                if (worldChunk == null) {
                    continue;
                }
                chunks++;
                EntityChunk entityChunk = worldChunk.getEntityChunk();
                if (entityChunk == null) {
                    continue;
                }
                var refs = entityChunk.getEntityReferences();
                if (refs == null || refs.isEmpty()) {
                    continue;
                }
                int count = refs.size();
                rawEntities += count;
                double ringWeight = 1.0D;
                if (cfg.densityRingWeighting && radius > 0) {
                    double dist = Math.sqrt((double) dx * dx + (double) dz * dz);
                    double t = Math.min(1.0D, dist / radius);
                    ringWeight = 1.0D - t * (1.0D - cfg.densityRingEdgeWeight);
                }
                weightedEntities += count * ringWeight;
            }
        }
        return new Density(rawEntities, weightedEntities, chunks);
    }

    private static double chunkLoadShrinkFraction(@Nullable ChunkTracker tracker, QuantumHyConfig cfg) {
        if (!cfg.chunkLoadShrinkEnabled || tracker == null) {
            return 0.0D;
        }
        int signal = tracker.getLoadedChunksCount() + tracker.getLoadingChunksCount();
        return smoothstepShrink(signal, cfg.chunkLoadLowChunks, cfg.chunkLoadHighChunks);
    }

    private static double combinedShrinkFraction(double densityFrac, double chunkLoadFrac, double baseline) {
        double frac = Math.max(densityFrac, chunkLoadFrac);
        if (baseline > 0) {
            frac = Math.max(frac, baseline);
        }
        return Math.min(1.0D, frac);
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
        if (config.targetClientViewRadius > 0) {
            int hardMax = Math.min(config.maxClientViewRadius, ceiling);
            return clamp(config.targetClientViewRadius, config.minClientViewRadius,
                    Math.max(config.minClientViewRadius, hardMax));
        }
        return Math.max(config.minClientViewRadius, ceiling);
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
                && tracker.getLoadingChunksCount() >= config.streamingBacklogThreshold;
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
    }

    private record Density(int rawEntities, double weightedEntities, int chunks) {
        static final Density NONE = new Density(-1, 0, 0);

        boolean valid() {
            return rawEntities >= 0;
        }

        int entities() {
            return rawEntities;
        }

        double perChunk() {
            return chunks <= 0 ? 0.0D : weightedEntities / chunks;
        }
    }
}
