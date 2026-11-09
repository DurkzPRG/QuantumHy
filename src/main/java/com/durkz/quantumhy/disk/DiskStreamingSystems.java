package com.durkz.quantumhy.disk;

import com.hypixel.hytale.component.ArchetypeChunk;
import com.hypixel.hytale.component.CommandBuffer;
import com.hypixel.hytale.component.ComponentType;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.component.dependency.Dependency;
import com.hypixel.hytale.component.dependency.Order;
import com.hypixel.hytale.component.dependency.SystemDependency;
import com.hypixel.hytale.component.query.Query;
import com.hypixel.hytale.component.system.tick.EntityTickingSystem;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.modules.entity.component.TransformComponent;
import com.hypixel.hytale.server.core.modules.entity.player.ChunkTracker;
import com.hypixel.hytale.server.core.modules.entity.player.PlayerChunkTrackerSystems;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import java.util.Set;
import java.util.function.Predicate;
import java.util.function.ToIntFunction;
import javax.annotation.Nonnull;
import org.joml.Vector3d;

/** The two world-thread hooks around the native chunk tracker update. */
public final class DiskStreamingSystems {
    private DiskStreamingSystems() {
    }

    private static final ComponentType<EntityStore, Player> PLAYER = Player.getComponentType();
    private static final ComponentType<EntityStore, PlayerRef> PLAYER_REF = PlayerRef.getComponentType();
    private static final ComponentType<EntityStore, ChunkTracker> TRACKER = ChunkTracker.getComponentType();
    private static final ComponentType<EntityStore, TransformComponent> TRANSFORM = TransformComponent.getComponentType();
    private static final Query<EntityStore> QUERY = Query.and(PLAYER, PLAYER_REF, TRACKER, TRANSFORM);

    /** Caps the native radius before the tracker runs, so the tracker never loads past it. */
    public static final class Before extends EntityTickingSystem<EntityStore> {
        private final DiskStreamer streamer;
        private final Predicate<PlayerRef> wanted;
        private final ToIntFunction<PlayerRef> nativeRadius;

        public Before(DiskStreamer streamer, Predicate<PlayerRef> wanted, ToIntFunction<PlayerRef> nativeRadius) {
            this.streamer = streamer;
            this.wanted = wanted;
            this.nativeRadius = nativeRadius;
        }

        @Override
        public Query<EntityStore> getQuery() {
            return QUERY;
        }

        @Nonnull
        @Override
        public Set<Dependency<EntityStore>> getDependencies() {
            return Set.of(new SystemDependency<>(Order.BEFORE, PlayerChunkTrackerSystems.UpdateSystem.class));
        }

        @Override
        public boolean isParallel(int archetypeChunkSize, int taskCount) {
            return false;
        }

        @Override
        public void tick(float dt, int index, @Nonnull ArchetypeChunk<EntityStore> chunk, @Nonnull Store<EntityStore> store,
                         @Nonnull CommandBuffer<EntityStore> commandBuffer) {
            Player player = chunk.getComponent(index, PLAYER);
            PlayerRef ref = chunk.getComponent(index, PLAYER_REF);
            ChunkTracker tracker = chunk.getComponent(index, TRACKER);
            if (player == null || ref == null || tracker == null) return;
            streamer.preTick(store.getExternalData().getWorld(), ref, player, tracker, wanted.test(ref),
                    nativeRadius.applyAsInt(ref), tracker.getMaxHotLoadedRadius());
        }
    }

    /** Sees the tracker's loads and unloads of this tick, then streams the band beyond it. */
    public static final class After extends EntityTickingSystem<EntityStore> {
        private final DiskStreamer streamer;

        public After(DiskStreamer streamer) {
            this.streamer = streamer;
        }

        @Override
        public Query<EntityStore> getQuery() {
            return QUERY;
        }

        @Nonnull
        @Override
        public Set<Dependency<EntityStore>> getDependencies() {
            return Set.of(new SystemDependency<>(Order.AFTER, PlayerChunkTrackerSystems.UpdateSystem.class));
        }

        @Override
        public boolean isParallel(int archetypeChunkSize, int taskCount) {
            return false;
        }

        @Override
        public void tick(float dt, int index, @Nonnull ArchetypeChunk<EntityStore> chunk, @Nonnull Store<EntityStore> store,
                         @Nonnull CommandBuffer<EntityStore> commandBuffer) {
            PlayerRef ref = chunk.getComponent(index, PLAYER_REF);
            ChunkTracker tracker = chunk.getComponent(index, TRACKER);
            TransformComponent transform = chunk.getComponent(index, TRANSFORM);
            if (ref == null || tracker == null || transform == null) return;
            Vector3d position = transform.getPosition();
            streamer.postTick(store.getExternalData().getWorld(), ref, tracker, position.x(), position.y(), position.z());
        }
    }
}
