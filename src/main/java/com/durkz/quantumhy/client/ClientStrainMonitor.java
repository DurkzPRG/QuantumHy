package com.durkz.quantumhy.client;

import com.durkz.quantumhy.config.QuantumHyConfig;
import com.hypixel.hytale.protocol.NetworkChannel;
import com.hypixel.hytale.protocol.packets.connection.Pong;
import com.hypixel.hytale.protocol.packets.connection.PongType;
import com.hypixel.hytale.server.core.io.PacketHandler;
import com.hypixel.hytale.server.core.universe.PlayerRef;

import javax.annotation.Nullable;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.LongSupplier;

/**
 * Per-player client strain, sampled once a second off the world thread. Everything it reads is
 * safe there: matched pong arrivals from the inbound watcher and the channel writable flag.
 * Consumers on other threads only read the published volatile values. Response spacing is a
 * congestion signal, not a measurement of client FPS.
 */
public final class ClientStrainMonitor {

    private final ConcurrentHashMap<UUID, State> players = new ConcurrentHashMap<>();
    private final boolean enabled;
    private final LongSupplier clock;

    public ClientStrainMonitor(boolean enabled) {
        this(enabled, System::nanoTime);
    }

    ClientStrainMonitor(boolean enabled, LongSupplier clock) {
        this.enabled = enabled;
        this.clock = clock;
    }

    public boolean enabled() {
        return enabled;
    }

    /** Matches network-thread arrivals by ping ID; the Tick reply supplies the queue size. */
    public void onPong(@Nullable UUID playerId, Pong pong) {
        if (!enabled || playerId == null || (pong.type != PongType.Tick && pong.type != PongType.Direct)) {
            return;
        }
        players.computeIfAbsent(playerId, ignored -> new State()).pongs.record(
                pong.id, pong.type == PongType.Tick, pong.packetQueueSize, clock.getAsLong());
    }

    /** One 1s sample. Single sampler thread. */
    public void sample(@Nullable PlayerRef playerRef, QuantumHyConfig config) {
        if (!enabled || playerRef == null || !playerRef.isValid() || playerRef.getUuid() == null) {
            return;
        }
        PacketHandler handler = playerRef.getPacketHandler();
        if (handler == null) {
            return;
        }
        var channel = handler.getChannel(NetworkChannel.Chunks);
        sample(playerRef.getUuid(), channel != null && !channel.isWritable(), config);
    }

    void sample(UUID playerId, boolean backpressure, QuantumHyConfig config) {
        if (!enabled || playerId == null) return;
        State state = players.computeIfAbsent(playerId, ignored -> new State());
        long nowNanos = clock.getAsLong();
        var sample = state.pongs.latest(nowNanos);
        var tick = state.pongs.latestTick(nowNanos);
        boolean fresh = sample != null && sample.sequence() != state.lastSequence;
        boolean freshTick = tick != null && tick.sequence() != state.lastTickSequence;
        state.frameLagMs = sample == null ? 0.0D : sample.loopLagMs();
        if (!freshTick && !backpressure) {
            if (sample == null && tick == null) state.reset();
            return;
        }
        if (fresh) state.lastSequence = sample.sequence();
        if (freshTick) state.lastTickSequence = tick.sequence();
        double lagMs = fresh ? sample.loopLagMs() : 0.0D;
        int queue = freshTick ? tick.queueSize() : -1;
        double score = ClientStrainPolicy.score(lagMs,
                config.clientStrainFrameLagLowMs, config.clientStrainFrameLagHighMs,
                queue, config.clientStrainQueueLow, config.clientStrainQueueHigh, backpressure);
        state.ema = ClientStrainPolicy.ema(state.ema, score, state.hasEma);
        state.hasEma = true;
        state.counter = ClientStrainPolicy.nextCounter(state.strained, state.ema, state.counter);
        if (ClientStrainPolicy.flips(state.strained, state.counter)) {
            state.strained = !state.strained;
            state.counter = 0;
        }
        state.published = state.strained ? state.ema : 0.0D;
    }

    /** Strain in {@code [0, 1]} while the player is strained, else {@code 0}. */
    public double strain(@Nullable UUID playerId) {
        State state = playerId == null ? null : players.get(playerId);
        return state == null ? 0.0D : state.published;
    }

    public boolean strained(@Nullable UUID playerId) {
        return strain(playerId) > 0.0D;
    }

    /** Last main-loop lag sample, for /q status. */
    public double frameLagMs(@Nullable UUID playerId) {
        State state = playerId == null ? null : players.get(playerId);
        return state == null ? 0.0D : state.frameLagMs;
    }

    public void forget(@Nullable UUID playerId) {
        if (playerId != null) {
            players.remove(playerId);
        }
    }

    public void retain(Set<UUID> online) {
        players.keySet().retainAll(online);
    }

    public void clear() {
        players.clear();
    }

    private static final class State {
        final PongPairTracker pongs = new PongPairTracker();
        volatile double published;
        volatile double frameLagMs;
        long lastSequence;
        long lastTickSequence;
        double ema;
        boolean hasEma;
        boolean strained;
        int counter;

        void reset() {
            published = 0.0D;
            frameLagMs = 0.0D;
            ema = 0.0D;
            hasEma = false;
            strained = false;
            counter = 0;
        }
    }
}
