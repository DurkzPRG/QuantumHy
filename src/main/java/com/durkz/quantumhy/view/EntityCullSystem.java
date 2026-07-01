package com.durkz.quantumhy.view;

import com.durkz.quantumhy.config.QuantumHyConfig;
import com.durkz.quantumhy.pressure.PressureGovernor;
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
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.PriorityQueue;
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
    private final ComponentType<EntityStore, TransformComponent> transformComponentType;
    private final Query<EntityStore> query;
    private final Set<Dependency<EntityStore>> dependencies;

    public EntityCullSystem(@Nonnull ComponentType<EntityStore, EntityTrackerSystems.EntityViewer> entityViewerComponentType,
                            @Nonnull QuantumHyConfig config) {
        this.config = config;
        this.entityViewerComponentType = entityViewerComponentType;
        this.playerRefComponentType = PlayerRef.getComponentType();
        this.transformComponentType = TransformComponent.getComponentType();
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
        final double py = position.y;

        final int maxVertical = PressureGovernor.verticalDistance(worldName, config.maxEntityVerticalDistance);
        if (maxVertical > 0) {
            final int maxVerticalSq = maxVertical * maxVertical;
            for (final var iterator = viewer.visible.iterator(); iterator.hasNext(); ) {
                final Ref<EntityStore> targetRef = iterator.next();
                if (!targetRef.isValid()) {
                    continue;
                }
                if (commandBuffer.getArchetype(targetRef).contains(playerRefComponentType)) {
                    continue;
                }

                final var targetTransform = commandBuffer.getComponent(targetRef, transformComponentType);
                if (targetTransform == null) {
                    continue;
                }

                final double dy = targetTransform.getPosition().y - py;
                if (dy * dy > maxVerticalSq) {
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
        int over = viewer.visible.size() - cap;
        if (over <= 0) {
            return;
        }

        // Max-heap of the cap nearest: head is the farthest among the kept set.
        PriorityQueue<Candidate> nearest = new PriorityQueue<>(cap,
                (a, b) -> Double.compare(b.distanceSq, a.distanceSq));
        for (final Ref<EntityStore> ref : viewer.visible) {
            if (!ref.isValid() || commandBuffer.getArchetype(ref).contains(playerRefComponentType)) {
                continue;
            }
            final var targetTransform = commandBuffer.getComponent(ref, transformComponentType);
            if (targetTransform == null) {
                continue;
            }
            double distSq = targetTransform.getPosition().distanceSquared(position);
            if (nearest.size() < cap) {
                nearest.add(new Candidate(ref, distSq));
            } else if (distSq < nearest.peek().distanceSq) {
                nearest.poll();
                nearest.add(new Candidate(ref, distSq));
            }
        }
        if (nearest.isEmpty()) {
            return;
        }

        Set<Ref<EntityStore>> keep = Collections.newSetFromMap(new IdentityHashMap<>(nearest.size()));
        for (Candidate candidate : nearest) {
            keep.add(candidate.ref);
        }

        int culled = 0;
        for (final var iterator = viewer.visible.iterator(); iterator.hasNext(); ) {
            final Ref<EntityStore> ref = iterator.next();
            if (!ref.isValid() || commandBuffer.getArchetype(ref).contains(playerRefComponentType)) {
                continue;
            }
            if (!keep.contains(ref)) {
                iterator.remove();
                culled++;
            }
        }
        for (int i = 0; i < culled; i++) {
            recordCapCull(worldName);
        }
    }

    private record Candidate(Ref<EntityStore> ref, double distanceSq) {
    }
}
