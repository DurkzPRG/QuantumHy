package com.durkz.quantumhy.view;

import com.durkz.quantumhy.config.QuantumHyConfig;
import com.durkz.quantumhy.integration.LeanCoreBridge;
import com.durkz.quantumhy.runtime.RuntimeMetrics;
import com.hypixel.hytale.math.util.ChunkUtil;
import com.hypixel.hytale.math.vector.Transform;
import com.hypixel.hytale.server.core.modules.entity.player.ChunkTracker;
import com.hypixel.hytale.server.core.universe.PlayerRef;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Writes per-player section send caps on a short tick, separate from the density/radius pass.
 */
public final class StreamRateController {

    public record Applied(
            @Nonnull String tier,
            int perSecond,
            int perTick,
            int loading,
            int loaded
    ) {
        public static final Applied IDLE = new Applied("off", 0, 0, 0, 0);
    }

    private final QuantumHyConfig config;
    private final Map<UUID, PlayerStreamState> players = new ConcurrentHashMap<>();

    public StreamRateController(@Nonnull QuantumHyConfig config) {
        this.config = config;
    }

    @Nonnull
    public Applied lastApplied(@Nullable UUID playerId) {
        if (playerId == null) {
            return Applied.IDLE;
        }
        PlayerStreamState state = players.get(playerId);
        return state == null
                ? Applied.IDLE
                : new Applied(state.appliedTier, state.appliedPerSecond, state.appliedPerTick,
                state.lastLoading, state.lastLoaded);
    }

    public void retain(@Nonnull Set<UUID> online) {
        players.entrySet().removeIf(entry -> !online.contains(entry.getKey()));
    }

    /**
     * Apply cruise or catch-up caps for one player. Must run on that player's world thread.
     * {@code cruisePerSecond}/{@code cruisePerTick} already include MSPT pressure multipliers.
     */
    public void applyOne(@Nullable PlayerRef playerRef, boolean pressured, int cruisePerSecond,
            int cruisePerTick, long nowMs) {
        if (playerRef == null || !playerRef.isValid()) {
            return;
        }
        UUID playerId = playerRef.getUuid();
        ChunkTracker tracker = playerRef.getChunkTracker();
        PlayerStreamState state = playerId == null ? null : players.computeIfAbsent(playerId, ignored -> new PlayerStreamState());

        if (!LeanCoreBridge.shouldQuantumHyWriteChunkRate(config)) {
            restore(tracker, state);
            return;
        }
        if (tracker == null || state == null) {
            return;
        }

        if (!state.hasBaseline) {
            state.baselinePerSecond = tracker.getMaxSectionsPerSecond();
            state.baselinePerTick = tracker.getMaxSectionsPerTick();
            state.hasBaseline = true;
        }

        int chunkX = 0;
        int chunkZ = 0;
        boolean haveChunk = false;
        Transform transform = playerRef.getTransform();
        if (transform != null && transform.getPosition() != null) {
            chunkX = ChunkUtil.chunkCoordinate(transform.getPosition().x);
            chunkZ = ChunkUtil.chunkCoordinate(transform.getPosition().z);
            haveChunk = true;
        }

        int chebyshev = StreamCatchUpPolicy.chebyshev(
                state.hasChunkPos, state.lastChunkX, state.lastChunkZ, chunkX, chunkZ);
        int loading = tracker.getLoadingSectionsCount();
        int loaded = tracker.getLoadedSectionsCount();

        StreamCatchUpPolicy.Outcome outcome = StreamCatchUpPolicy.next(
                config.streamCatchUpEnabled,
                pressured,
                chebyshev,
                loading,
                state.hasLoading,
                state.lastLoading,
                state.consecutiveGrowing,
                state.moveScore,
                state.tier,
                nowMs,
                state.holdUntilMs,
                config.streamingBacklogThreshold,
                config.streamCatchUpHoldMs,
                cruisePerSecond,
                cruisePerTick,
                config.streamCatchUpPerSecond,
                config.streamCatchUpPerTick,
                state.baselinePerSecond,
                state.baselinePerTick);

        boolean changed = false;
        if (outcome.perSecond() > 0 && tracker.getMaxSectionsPerSecond() != outcome.perSecond()) {
            tracker.setMaxSectionsPerSecond(outcome.perSecond());
            changed = true;
        }
        if (outcome.perTick() > 0 && tracker.getMaxSectionsPerTick() != outcome.perTick()) {
            tracker.setMaxSectionsPerTick(outcome.perTick());
            changed = true;
        }
        boolean tierChanged = state.tier != outcome.tier();
        if (changed || tierChanged) {
            long holdLeft = Math.max(0L, outcome.holdUntilMs() - nowMs);
            RuntimeMetrics.streaming(
                    outcome.tier().name(),
                    outcome.perSecond(),
                    outcome.perTick(),
                    loading,
                    loaded,
                    holdLeft,
                    changed);
        }

        state.tier = outcome.tier();
        state.holdUntilMs = outcome.holdUntilMs();
        state.consecutiveGrowing = outcome.consecutiveGrowing();
        state.moveScore = outcome.moveScore();
        state.lastLoading = loading;
        state.hasLoading = true;
        if (haveChunk) {
            state.hasChunkPos = true;
            state.lastChunkX = chunkX;
            state.lastChunkZ = chunkZ;
        }
        state.appliedTier = outcome.tier().name();
        state.appliedPerSecond = outcome.perSecond();
        state.appliedPerTick = outcome.perTick();
        state.lastLoaded = loaded;
    }

    public void restoreAll(@Nullable Iterable<PlayerRef> onlinePlayers) {
        if (onlinePlayers == null) {
            return;
        }
        for (PlayerRef ref : onlinePlayers) {
            if (ref == null) {
                continue;
            }
            UUID playerId = ref.getUuid();
            restore(ref.getChunkTracker(), playerId == null ? null : players.get(playerId));
        }
    }

    private static void restore(@Nullable ChunkTracker tracker, @Nullable PlayerStreamState state) {
        if (tracker == null || state == null || !state.hasBaseline) {
            return;
        }
        if (tracker.getMaxSectionsPerSecond() != state.baselinePerSecond) {
            tracker.setMaxSectionsPerSecond(state.baselinePerSecond);
        }
        if (tracker.getMaxSectionsPerTick() != state.baselinePerTick) {
            tracker.setMaxSectionsPerTick(state.baselinePerTick);
        }
        state.hasBaseline = false;
        state.tier = StreamCatchUpPolicy.Tier.CRUISE;
        state.holdUntilMs = 0L;
        state.appliedTier = "off";
        state.appliedPerSecond = state.baselinePerSecond;
        state.appliedPerTick = state.baselinePerTick;
    }

    private static final class PlayerStreamState {
        StreamCatchUpPolicy.Tier tier = StreamCatchUpPolicy.Tier.CRUISE;
        long holdUntilMs;
        int consecutiveGrowing;
        int moveScore;
        boolean hasLoading;
        int lastLoading;
        boolean hasChunkPos;
        int lastChunkX;
        int lastChunkZ;
        int baselinePerSecond;
        int baselinePerTick;
        boolean hasBaseline;
        String appliedTier = "off";
        int appliedPerSecond;
        int appliedPerTick;
        int lastLoaded;
    }
}
