package com.durkz.quantumhy.view;

import com.durkz.quantumhy.config.QuantumHyConfig;
import com.durkz.quantumhy.integration.LeanCoreBridge;
import com.durkz.quantumhy.pressure.PressureGovernor;
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
            int loaded,
            int loadingDelta,
            double msptAverage,
            double msptLast,
            @Nonnull String protectionCause
    ) {
        public static final Applied IDLE = new Applied("off", 0, 0, 0, 0,
                0, 0.0D, 0.0D, "none");
    }

    public record Transition(
            @Nonnull StreamCatchUpPolicy.Tier previous,
            @Nonnull StreamCatchUpPolicy.Tier current,
            @Nonnull Applied applied
    ) {
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
                state.lastLoading, state.lastLoaded, state.lastLoadingDelta,
                state.lastMsptAverage, state.lastMsptLast, state.protectionCause);
    }

    public void retain(@Nonnull Set<UUID> online) {
        players.entrySet().removeIf(entry -> !online.contains(entry.getKey()));
    }

    /**
     * Apply cruise or catch-up caps for one player. Must run on that player's world thread.
     * Returns a transition only when entering or leaving the protection tier.
     */
    @Nullable
    public Transition applyOne(@Nullable PlayerRef playerRef,
            @Nonnull PressureGovernor.StreamHealth health, boolean governorPressured, long nowMs) {
        if (playerRef == null || !playerRef.isValid()) {
            return null;
        }
        UUID playerId = playerRef.getUuid();
        ChunkTracker tracker = playerRef.getChunkTracker();
        PlayerStreamState state = playerId == null ? null : players.computeIfAbsent(playerId, ignored -> new PlayerStreamState());

        if (!LeanCoreBridge.shouldQuantumHyWriteChunkRate(config)) {
            restore(tracker, state);
            return null;
        }
        if (tracker == null || state == null) {
            return null;
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
        int loadingDelta = state.hasLoading ? loading - state.lastLoading : 0;
        StreamCatchUpPolicy.Tier previousTier = state.tier;

        StreamCatchUpPolicy.Outcome outcome = StreamCatchUpPolicy.next(
                config.streamCatchUpEnabled,
                config.pressureGovernorEnabled,
                governorPressured,
                health.available(),
                health.msptAvg10s(),
                health.msptLast(),
                config.pressureMsptEnter,
                config.pressureMsptExit,
                config.pressureChunkRateMultiplier,
                chebyshev,
                loading,
                state.moveScore,
                state.protectCalmSamples,
                state.tier,
                nowMs,
                state.holdUntilMs,
                config.streamingBacklogThreshold,
                config.streamCatchUpHoldMs,
                config.maxChunksPerSecond,
                config.maxChunksPerTick,
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
                    loadingDelta,
                    health.msptAvg10s(),
                    health.msptLast(),
                    outcome.protectionCause().label(),
                    holdLeft,
                    changed);
        }

        state.tier = outcome.tier();
        state.holdUntilMs = outcome.holdUntilMs();
        state.moveScore = outcome.moveScore();
        state.protectCalmSamples = outcome.protectCalmSamples();
        state.lastLoading = loading;
        state.lastLoadingDelta = loadingDelta;
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
        state.lastMsptAverage = health.msptAvg10s();
        state.lastMsptLast = health.msptLast();
        state.protectionCause = outcome.protectionCause().label();

        boolean protectChanged = (previousTier == StreamCatchUpPolicy.Tier.PROTECT)
                != (outcome.tier() == StreamCatchUpPolicy.Tier.PROTECT);
        if (!protectChanged) {
            return null;
        }
        return new Transition(previousTier, outcome.tier(), lastApplied(playerId));
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
        state.protectCalmSamples = 0;
        state.appliedTier = "off";
        state.appliedPerSecond = state.baselinePerSecond;
        state.appliedPerTick = state.baselinePerTick;
        state.protectionCause = "none";
    }

    private static final class PlayerStreamState {
        StreamCatchUpPolicy.Tier tier = StreamCatchUpPolicy.Tier.CRUISE;
        long holdUntilMs;
        int moveScore;
        int protectCalmSamples;
        boolean hasLoading;
        int lastLoading;
        int lastLoadingDelta;
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
        double lastMsptAverage;
        double lastMsptLast;
        String protectionCause = "none";
    }
}
