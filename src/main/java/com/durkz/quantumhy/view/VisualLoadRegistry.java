package com.durkz.quantumhy.view;

import javax.annotation.Nullable;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Allocation-free bridge from the per-tick entity viewer pass to the slower adaptive pass.
 * Each player is written by its world thread and read by that same thread during radius updates.
 */
public final class VisualLoadRegistry {

    private static final ConcurrentHashMap<UUID, State> PLAYERS = new ConcurrentHashMap<>();

    private VisualLoadRegistry() {
    }

    static void record(UUID playerId, int candidates, int visible, int entityRadiusBlocks) {
        if (playerId == null) {
            return;
        }
        State state = PLAYERS.computeIfAbsent(playerId, ignored -> new State());
        int previous = state.candidates;
        state.candidates = Math.max(0, candidates);
        state.visible = Math.max(0, visible);
        state.entityRadiusBlocks = Math.max(0, entityRadiusBlocks);
        if (state.hasSample) {
            state.churnSinceSample += Math.abs(state.candidates - previous);
        } else {
            state.hasSample = true;
        }
        state.samplesSinceRead++;
    }

    @Nullable
    public static State state(UUID playerId) {
        return playerId == null ? null : PLAYERS.get(playerId);
    }

    public static void retain(Set<UUID> online) {
        PLAYERS.keySet().retainAll(online);
    }

    public static void remove(UUID playerId) {
        if (playerId != null) {
            PLAYERS.remove(playerId);
        }
    }

    public static void clear() {
        PLAYERS.clear();
    }

    public static final class State {
        private volatile int candidates;
        private volatile int visible;
        private volatile int entityRadiusBlocks;
        private int churnSinceSample;
        private int samplesSinceRead;
        private boolean hasSample;

        private State() {
        }

        public int candidates() {
            return candidates;
        }

        public int visible() {
            return visible;
        }

        public int entityRadiusBlocks() {
            return entityRadiusBlocks;
        }

        public double drainAverageChurn() {
            int value = churnSinceSample;
            int samples = samplesSinceRead;
            churnSinceSample = 0;
            samplesSinceRead = 0;
            return samples <= 0 ? 0.0D : value / (double) samples;
        }
    }
}
