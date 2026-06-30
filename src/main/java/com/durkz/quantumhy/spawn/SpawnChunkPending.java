package com.durkz.quantumhy.spawn;

import com.hypixel.hytale.server.core.modules.entity.player.ChunkTracker;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.World;

import javax.annotation.Nonnull;
import java.util.Collection;

/** ChunkTracker streaming state for environmental spawn pause. */
final class SpawnChunkPending {

    private SpawnChunkPending() {
    }

    static boolean anyViewerLoading(@Nonnull World world) {
        Collection<PlayerRef> players = world.getPlayerRefs();
        if (players == null || players.isEmpty()) {
            return false;
        }
        for (PlayerRef playerRef : players) {
            if (playerRef == null || !playerRef.isValid()) {
                continue;
            }
            ChunkTracker tracker = playerRef.getChunkTracker();
            if (tracker != null && tracker.getLoadingChunksCount() > 0) {
                return true;
            }
        }
        return false;
    }
}
