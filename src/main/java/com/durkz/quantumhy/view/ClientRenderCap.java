package com.durkz.quantumhy.view;

import com.hypixel.hytale.math.util.ChunkUtil;
import com.hypixel.hytale.protocol.packets.setup.ViewRadius;
import com.hypixel.hytale.server.core.HytaleServer;
import com.hypixel.hytale.server.core.universe.PlayerRef;

import javax.annotation.Nullable;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Caps the client's own render distance to the terrain radius QuantumHy chose.
 *
 * The server sends {@link ViewRadius} on join with the server max, and the client treats it as the
 * ceiling for its draw distance ({@code /maxviewradius} re-sends it the same way). Trimming only
 * {@code Player.setClientViewRadius} stops the stream but leaves the client drawing fog and terrain
 * out to its own setting. Sending the trimmed radius as the cap closes that gap.
 *
 * The client also sends {@link ViewRadius} when its setting changes, and the engine copies that
 * straight into the player's radius. {@link #onClientRequest} records those so the controller can
 * use the real request as the ceiling and re-assert a trim the engine just overwrote. A request
 * that arrives right after our own cap and is not above it is treated as the client echoing the cap.
 */
public final class ClientRenderCap {

    static final long ECHO_WINDOW_NANOS = 2_000_000_000L;
    static final long DRIFT_WINDOW_NANOS = 1_000_000_000L;

    private final ConcurrentHashMap<UUID, CapState> players = new ConcurrentHashMap<>();
    private final boolean enabled;

    public ClientRenderCap(boolean enabled) {
        this.enabled = enabled;
    }

    public boolean enabled() {
        return enabled;
    }

    /** Inbound {@link ViewRadius} from the client. Runs on a network thread. */
    public void onClientRequest(@Nullable UUID playerId, int valueBlocks) {
        if (playerId == null || valueBlocks <= 0) {
            return;
        }
        CapState state = players.computeIfAbsent(playerId, ignored -> new CapState());
        long now = System.nanoTime();
        if (isEcho(state.lastSentBlocks, state.lastSentNanos, valueBlocks, now)) {
            return;
        }
        state.requestedBlocks = valueBlocks;
        state.driftUntilNanos = now + DRIFT_WINDOW_NANOS;
    }

    /**
     * True when an inbound value is most likely the client answering our own cap: it arrived inside
     * the echo window and does not ask for more than we capped to.
     */
    static boolean isEcho(int lastSentBlocks, long lastSentNanos, int valueBlocks, long nowNanos) {
        return lastSentBlocks > 0
                && nowNanos - lastSentNanos < ECHO_WINDOW_NANOS
                && valueBlocks <= lastSentBlocks;
    }

    /** The client's last real view radius request in chunks, or {@code -1} if none was seen. */
    public int requestedChunks(@Nullable UUID playerId) {
        CapState state = playerId == null ? null : players.get(playerId);
        if (state == null || state.requestedBlocks <= 0) {
            return -1;
        }
        return toChunks(state.requestedBlocks);
    }

    /** True for a short window after the client changed its setting, while the engine may have overwritten a trim. */
    public boolean drifting(@Nullable UUID playerId) {
        CapState state = playerId == null ? null : players.get(playerId);
        return state != null && state.driftUntilNanos != 0L && System.nanoTime() < state.driftUntilNanos;
    }

    /**
     * Caps the client to {@code radiusChunks}, or releases the cap when {@code trimmed} is false.
     * Sends only on change. Must run on the player's world thread.
     */
    public void apply(@Nullable PlayerRef playerRef, int radiusChunks, boolean trimmed) {
        if (!enabled || playerRef == null || playerRef.getUuid() == null) {
            return;
        }
        if (!trimmed) {
            release(playerRef);
            return;
        }
        int blocks = Math.max(1, radiusChunks) * ChunkUtil.SIZE;
        CapState state = players.computeIfAbsent(playerRef.getUuid(), ignored -> new CapState());
        if (state.lastSentBlocks == blocks) {
            return;
        }
        send(playerRef, state, blocks);
    }

    /** Hands the client back the server max, as on join. No-op when we never capped this player. */
    public void release(@Nullable PlayerRef playerRef) {
        if (!enabled || playerRef == null || playerRef.getUuid() == null) {
            return;
        }
        CapState state = players.get(playerRef.getUuid());
        if (state == null || state.lastSentBlocks <= 0) {
            return;
        }
        send(playerRef, state, serverMaxBlocks());
        state.lastSentBlocks = 0;
    }

    /** The engine re-sends the server max when a player enters a world; our last cap is gone. */
    public void onWorldJoin(@Nullable UUID playerId) {
        CapState state = playerId == null ? null : players.get(playerId);
        if (state != null) {
            state.lastSentBlocks = 0;
        }
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

    private static void send(PlayerRef playerRef, CapState state, int blocks) {
        playerRef.getPacketHandler().writeNoCache(new ViewRadius(blocks));
        state.lastSentBlocks = blocks;
        state.lastSentNanos = System.nanoTime();
    }

    private static int serverMaxBlocks() {
        return HytaleServer.get().getConfig().getMaxViewRadius() * ChunkUtil.SIZE;
    }

    /** Same rounding as the engine's {@code handleViewRadius}. */
    static int toChunks(int blocks) {
        return (blocks + ChunkUtil.SIZE - 1) / ChunkUtil.SIZE;
    }

    private static final class CapState {
        volatile int requestedBlocks;
        volatile int lastSentBlocks;
        volatile long lastSentNanos;
        volatile long driftUntilNanos;
    }
}
