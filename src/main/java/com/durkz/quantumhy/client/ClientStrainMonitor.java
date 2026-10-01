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

/**
 * Per-player client strain, sampled once a second off the world thread. Everything it reads is
 * safe there: ping metrics, the channel writable flag, and the queue size recorded from the
 * client's {@code Tick} pong by the inbound watcher. Consumers on other threads only read the
 * published volatile values.
 */
public final class ClientStrainMonitor {

    private final ConcurrentHashMap<UUID, State> players = new ConcurrentHashMap<>();
    private final boolean enabled;

    public ClientStrainMonitor(boolean enabled) {
        this.enabled = enabled;
    }

    public boolean enabled() {
        return enabled;
    }

    /** Inbound {@link Pong}. Runs on a network thread; keeps the queue size the game-loop pong reports. */
    public void onPong(@Nullable UUID playerId, Pong pong) {
        if (!enabled || playerId == null || pong.type != PongType.Tick) {
            return;
        }
        players.computeIfAbsent(playerId, ignored -> new State()).queue = pong.packetQueueSize;
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
        State state = players.computeIfAbsent(playerRef.getUuid(), ignored -> new State());
        double lagMs = ClientStrainPolicy.frameLagMs(
                handler.getPingInfo(PongType.Tick).getPingMetricSet().getLastValue(),
                handler.getPingInfo(PongType.Direct).getPingMetricSet().getLastValue());
        boolean backpressure = !handler.getChannel(NetworkChannel.Chunks).isWritable();
        double score = ClientStrainPolicy.score(lagMs,
                config.clientStrainFrameLagLowMs, config.clientStrainFrameLagHighMs,
                state.queue, config.clientStrainQueueLow, config.clientStrainQueueHigh, backpressure);
        state.ema = ClientStrainPolicy.ema(state.ema, score, state.hasEma);
        state.hasEma = true;
        state.counter = ClientStrainPolicy.nextCounter(state.strained, state.ema, state.counter);
        if (ClientStrainPolicy.flips(state.strained, state.counter)) {
            state.strained = !state.strained;
            state.counter = 0;
        }
        state.frameLagMs = lagMs;
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
        volatile int queue = -1;
        volatile double published;
        volatile double frameLagMs;
        double ema;
        boolean hasEma;
        boolean strained;
        int counter;
    }
}
