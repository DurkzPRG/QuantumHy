package com.durkz.quantumhy.runtime;

import com.durkz.quantumhy.pressure.PressureGovernor;

import javax.annotation.Nonnull;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/** Immutable /q status view built from the last adaptive pass (no live server re-walk). */
public record RuntimeSnapshot(
        long capturedAtMs,
        int onlineCount,
        @Nonnull List<PlayerRow> players,
        @Nonnull Map<String, WorldRow> worlds
) {
    public static final RuntimeSnapshot EMPTY = new RuntimeSnapshot(
            0, 0, List.of(), Map.of());

    public record PlayerRow(
            @Nonnull String name,
            @Nonnull String worldName,
            int chunksLoaded,
            int chunksLoading,
            int maxChunksPerSecond,
            @Nonnull String decisionLine
    ) {
    }

    public record WorldRow(
            @Nonnull PressureGovernor.Snapshot pressure,
            boolean streamPause,
            int poolCooled
    ) {
    }

    @Nonnull
    public WorldRow worldOrDefault(@Nonnull String worldName) {
        WorldRow row = worlds.get(worldName);
        return row == null
                ? new WorldRow(PressureGovernor.Snapshot.idle(), false, 0)
                : row;
    }

    @Nonnull
    public List<PlayerRow> playersOrEmpty() {
        return players.isEmpty() ? Collections.emptyList() : players;
    }
}
