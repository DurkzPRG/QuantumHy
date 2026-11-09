package com.durkz.quantumhy.view;

import com.hypixel.hytale.server.core.modules.entity.player.ChunkTracker;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import java.util.Collection;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public final class SimulationDistanceController {
    private final ConcurrentHashMap<UUID, State> players = new ConcurrentHashMap<>();

    public void apply(UUID playerId, ChunkTracker tracker, int configuredRadius, boolean enabled, boolean ownsRadius) {
        if (playerId == null || tracker == null) return;
        players.compute(playerId, (ignored, state) -> {
            if (state != null && state.tracker != tracker) {
                restoreIncoming(state, tracker);
                state = null;
            }
            if (!enabled || !ownsRadius) {
                restoreIncoming(state, tracker);
                return null;
            }
            int current = tracker.getMaxHotLoadedRadius();
            if (current < 1) return state;
            if (state == null) state = new State(tracker, current);
            if (state.yielded) return state;
            if (current != state.applied) {
                state.yielded = true;
                return state;
            }
            int target = Math.min(state.original, Math.clamp(configuredRadius, 2, 32));
            if (current != target) tracker.setMaxHotLoadedRadius(target);
            state.applied = target;
            return state;
        });
    }

    public void restore(UUID playerId, ChunkTracker tracker) {
        if (playerId == null || tracker == null) return;
        players.computeIfPresent(playerId, (ignored, state) -> {
            restoreIncoming(state, tracker);
            return null;
        });
    }

    public void onWorldJoin(UUID playerId, ChunkTracker incoming) {
        restore(playerId, incoming);
    }

    public void restoreAll(Collection<PlayerRef> batch) {
        for (PlayerRef player : batch) {
            if (player != null && player.isValid()) restore(player.getUuid(), player.getChunkTracker());
        }
    }

    public void forget(UUID playerId) {
        if (playerId != null) players.remove(playerId);
    }

    public void clear() {
        players.clear();
    }

    private static void restoreIncoming(State state, ChunkTracker incoming) {
        if (state != null && state.tracker == incoming && !state.yielded && incoming.getMaxHotLoadedRadius() == state.applied
                && state.original != state.applied) incoming.setMaxHotLoadedRadius(state.original);
    }

    private static final class State {
        private final ChunkTracker tracker;
        private final int original;
        private int applied;
        private boolean yielded;

        private State(ChunkTracker tracker, int original) {
            this.tracker = tracker;
            this.original = original;
            this.applied = original;
        }
    }
}
