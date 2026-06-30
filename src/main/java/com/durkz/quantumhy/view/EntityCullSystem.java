package com.durkz.quantumhy.view;

import com.durkz.quantumhy.config.QuantumHyConfig;
import com.hypixel.hytale.component.ArchetypeChunk;
import com.hypixel.hytale.component.CommandBuffer;
import com.hypixel.hytale.component.ComponentType;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.component.SystemGroup;
import com.hypixel.hytale.component.dependency.Dependency;
import com.hypixel.hytale.component.dependency.Order;
import com.hypixel.hytale.component.dependency.SystemDependency;
import com.hypixel.hytale.component.query.Query;
import com.hypixel.hytale.component.system.tick.EntityTickingSystem;
import com.hypixel.hytale.server.core.modules.entity.component.TransformComponent;
import com.hypixel.hytale.server.core.modules.entity.tracker.EntityTrackerSystems;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.LongAdder;

/**
 * Trims each player's set of streamed entities after the engine has collected it, the same way the
 * built-in LOD cull does. The engine sends every entity inside the entity radius as a plain sphere
 * with no line of sight, so mobs deep in caves below you (or far overhead) still get sent and drawn
 * through the terrain. This drops anything too far above or below the viewer, and optionally caps the
 * total count so a player in a crowd only gets the nearest entities. Other players are never trimmed.
 */
public final class EntityCullSystem extends EntityTickingSystem<EntityStore> {

    public static final LongAdder VERTICAL_CULLED = new LongAdder();
    public static final LongAdder CAP_CULLED = new LongAdder();

    private static final ConcurrentHashMap<String, AtomicLong> VERTICAL_SINCE_REPORT = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<String, AtomicLong> CAP_SINCE_REPORT = new ConcurrentHashMap<>();

    private final QuantumHyConfig config;
    private final ComponentType<EntityStore, EntityTrackerSystems.EntityViewer> entityViewerComponentType;
    private final ComponentType<EntityStore, PlayerRef> playerRefComponentType;
    private final Query<EntityStore> query;
    private final Set<Dependency<EntityStore>> dependencies;

    public EntityCullSystem(@Nonnull ComponentType<EntityStore, EntityTrackerSystems.EntityViewer> entityViewerComponentType,
                            @Nonnull QuantumHyConfig config) {
        this.config = config;
        this.entityViewerComponentType = entityViewerComponentType;
        this.playerRefComponentType = PlayerRef.getComponentType();
        this.query = Query.and(entityViewerComponentType, TransformComponent.getComponentType());
        this.dependencies = Collections.singleton(
                new SystemDependency<>(Order.AFTER, EntityTrackerSystems.CollectVisible.class));
    }

    @Nullable
    @Override
    public SystemGroup<EntityStore> getGroup() {
        return EntityTrackerSystems.FIND_VISIBLE_ENTITIES_GROUP;
    }

    @Nonnull
    @Override
    public Set<Dependency<EntityStore>> getDependencies() {
        return dependencies;
    }

    @Override
    public Query<EntityStore> getQuery() {
        return query;
    }

    @Override
    public boolean isParallel(int archetypeChunkSize, int taskCount) {
        return EntityTickingSystem.maybeUseParallel(archetypeChunkSize, taskCount);
    }

    @Override
    public void tick(float dt, int index, @Nonnull ArchetypeChunk<EntityStore> archetypeChunk,
                     @Nonnull Store<EntityStore> store, @Nonnull CommandBuffer<EntityStore> commandBuffer) {
        final var viewer = archetypeChunk.getComponent(index, entityViewerComponentType);
        assert viewer != null;

        final World world = store.getExternalData().getWorld();
        final String worldName = world == null ? "?" : world.getName();

        final var transformComponent = archetypeChunk.getComponent(index, TransformComponent.getComponentType());
        assert transformComponent != null;
        final var position = transformComponent.getPosition();

        final int maxVertical = config.maxEntityVerticalDistance;
        if (maxVertical > 0) {
            for (final var iterator = viewer.visible.iterator(); iterator.hasNext(); ) {
                final Ref<EntityStore> targetRef = iterator.next();
                if (!targetRef.isValid()) continue;
                if (commandBuffer.getArchetype(targetRef).contains(playerRefComponentType)) continue;

                final var targetTransform = commandBuffer.getComponent(targetRef, TransformComponent.getComponentType());
                if (targetTransform == null) continue;

                if (Math.abs(targetTransform.getPosition().y - position.y) > maxVertical) {
                    iterator.remove();
                    recordVerticalCull(worldName);
                }
            }
        }

        final int cap = config.maxVisibleEntitiesPerPlayer;
        if (cap > 0 && viewer.visible.size() > cap) {
            capToNearest(viewer, position, cap, commandBuffer, worldName);
        }
    }

    private static void recordVerticalCull(@Nonnull String worldName) {
        VERTICAL_CULLED.increment();
        VERTICAL_SINCE_REPORT.computeIfAbsent(worldName, ignored -> new AtomicLong()).incrementAndGet();
    }

    private static void recordCapCull(@Nonnull String worldName) {
        CAP_CULLED.increment();
        CAP_SINCE_REPORT.computeIfAbsent(worldName, ignored -> new AtomicLong()).incrementAndGet();
    }

    public static long drainVerticalSinceReport(@Nonnull String worldName) {
        AtomicLong counter = VERTICAL_SINCE_REPORT.get(worldName);
        return counter == null ? 0L : counter.getAndSet(0L);
    }

    public static long drainCapSinceReport(@Nonnull String worldName) {
        AtomicLong counter = CAP_SINCE_REPORT.get(worldName);
        return counter == null ? 0L : counter.getAndSet(0L);
    }

    /** Keeps the {@code cap} nearest non-player entities, dropping the farthest ones over the cap. */
    private void capToNearest(@Nonnull EntityTrackerSystems.EntityViewer viewer, @Nonnull org.joml.Vector3d position,
                              int cap, @Nonnull CommandBuffer<EntityStore> commandBuffer, @Nonnull String worldName) {
        final List<Candidate> candidates = new ArrayList<>(viewer.visible.size());
        for (final Ref<EntityStore> ref : viewer.visible) {
            if (!ref.isValid() || commandBuffer.getArchetype(ref).contains(playerRefComponentType)) continue;
            final var targetTransform = commandBuffer.getComponent(ref, TransformComponent.getComponentType());
            if (targetTransform == null) continue;
            candidates.add(new Candidate(ref, targetTransform.getPosition().distanceSquared(position)));
        }

        int over = viewer.visible.size() - cap;
        if (over <= 0 || candidates.isEmpty()) {
            return;
        }
        candidates.sort((a, b) -> Double.compare(b.distanceSq, a.distanceSq));
        for (int i = 0; i < candidates.size() && over > 0; i++) {
            if (viewer.visible.remove(candidates.get(i).ref)) {
                over--;
                recordCapCull(worldName);
            }
        }
    }

    private record Candidate(Ref<EntityStore> ref, double distanceSq) {
    }
}
