package com.durkz.quantumhy.spawn;

import com.hypixel.hytale.server.core.modules.entity.player.ChunkTracker;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.World;

import javax.annotation.Nonnull;
import java.util.Collection;

/**
 * ChunkTracker streaming state for environmental spawn pause.
 *
 * On 0.6 the engine streams sections at up to 2560/s, so {@code getLoadingSectionsCount() > 0}
 * is nearly always true while walking. Pause only when any viewer is at or above a backlog
 * threshold (same unit as {@code streamingBacklogThreshold}).
 */
final class SpawnChunkPending {

    private SpawnChunkPending() {
    }

    /**
     * True when any online player in {@code world} has at least {@code backlogThreshold}
     * sections still loading to the client. Threshold {@code <= 0} means any loading section.
     */
    static boolean anyViewerBacklogged(@Nonnull World world, int backlogThreshold) {
        Collection<PlayerRef> players = world.getPlayerRefs();
        if (players == null || players.isEmpty()) {
            return false;
        }
        int threshold = Math.max(0, backlogThreshold);
        for (PlayerRef playerRef : players) {
            if (playerRef == null || !playerRef.isValid()) {
                continue;
            }
            ChunkTracker tracker = playerRef.getChunkTracker();
            if (tracker != null && isBacklogged(tracker.getLoadingSectionsCount(), threshold)) {
                return true;
            }
        }
        return false;
    }

    /** Pure gate used by unit tests and {@link #anyViewerBacklogged}. */
    static boolean isBacklogged(int loadingSections, int backlogThreshold) {
        int threshold = Math.max(0, backlogThreshold);
        if (threshold == 0) {
            return loadingSections > 0;
        }
        return loadingSections >= threshold;
    }
}
