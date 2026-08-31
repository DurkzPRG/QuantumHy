package com.durkz.quantumhy.view;

import com.durkz.quantumhy.config.QuantumHyConfig;
import com.durkz.quantumhy.pressure.PressureGovernor;
import com.durkz.quantumhy.runtime.QuantumHyJfr;
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
    private final ThreadLocal<NearestScratch> nearestScratch = ThreadLocal.withInitial(NearestScratch::new);

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
        final long startNs = System.nanoTime();
        final var viewer = archetypeChunk.getComponent(index, entityViewerComponentType);
        assert viewer != null;
        final int visibleBefore = viewer.visible.size();

        final World world = store.getExternalData().getWorld();
        final String worldName = world == null ? "?" : world.getName();

        final var transformComponent = archetypeChunk.getComponent(index, TransformComponent.getComponentType());
        assert transformComponent != null;
        final var position = transformComponent.getPosition();
        final double py = position.y;

        final int maxVertical = PressureGovernor.verticalDistance(worldName, config.maxEntityVerticalDistance);
        int verticalCulled = 0;
        if (maxVertical > 0 && !viewer.visible.isEmpty()) {
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
                    verticalCulled++;
                }
            }
            recordVerticalCull(worldName, verticalCulled);
        }

        final int cap = config.maxVisibleEntitiesPerPlayer;
        int capCulled = 0;
        if (cap > 0 && viewer.visible.size() > cap) {
            capCulled = capToNearest(viewer, position, cap, commandBuffer, worldName);
        }
        QuantumHyJfr.cull(System.nanoTime() - startNs, visibleBefore, verticalCulled, capCulled);
    }

    private static void recordVerticalCull(@Nonnull String worldName, int count) {
        if (count <= 0) {
            return;
        }
        VERTICAL_CULLED.add(count);
        VERTICAL_SINCE_REPORT.computeIfAbsent(worldName, ignored -> new AtomicLong()).addAndGet(count);
    }

    private static void recordCapCull(@Nonnull String worldName, int count) {
        if (count <= 0) {
            return;
        }
        CAP_CULLED.add(count);
        CAP_SINCE_REPORT.computeIfAbsent(worldName, ignored -> new AtomicLong()).addAndGet(count);
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
    private int capToNearest(@Nonnull EntityTrackerSystems.EntityViewer viewer, @Nonnull org.joml.Vector3d position,
                              int cap, @Nonnull CommandBuffer<EntityStore> commandBuffer, @Nonnull String worldName) {
        int over = viewer.visible.size() - cap;
        if (over <= 0) {
            return 0;
        }

        // Max-heap of the cap nearest. Scratch is thread-local because this system may tick in parallel.
        NearestScratch nearest = nearestScratch.get();
        nearest.reset(cap);
        for (final Ref<EntityStore> ref : viewer.visible) {
            if (!ref.isValid() || commandBuffer.getArchetype(ref).contains(playerRefComponentType)) {
                continue;
            }
            final var targetTransform = commandBuffer.getComponent(ref, transformComponentType);
            if (targetTransform == null) {
                continue;
            }
            double distSq = targetTransform.getPosition().distanceSquared(position);
            nearest.offer(ref, distSq);
        }
        if (nearest.isEmpty()) {
            return 0;
        }

        nearest.buildKeepSet();

        int culled = 0;
        for (final var iterator = viewer.visible.iterator(); iterator.hasNext(); ) {
            final Ref<EntityStore> ref = iterator.next();
            if (!ref.isValid() || commandBuffer.getArchetype(ref).contains(playerRefComponentType)) {
                continue;
            }
            if (!nearest.keeps(ref)) {
                iterator.remove();
                culled++;
            }
        }
        recordCapCull(worldName, culled);
        nearest.clear();
        return culled;
    }

    /** Allocation-free max heap and identity keep set, reused once per worker thread. */
    private static final class NearestScratch {
        private Object[] refs = new Object[0];
        private double[] distances = new double[0];
        private final IdentityHashMap<Object, Boolean> keep = new IdentityHashMap<>();
        private int size;
        private int capacity;

        void reset(int requestedCapacity) {
            if (refs.length < requestedCapacity) {
                int newCapacity = Math.max(requestedCapacity, refs.length * 2 + 8);
                refs = new Object[newCapacity];
                distances = new double[newCapacity];
            }
            size = 0;
            capacity = requestedCapacity;
            keep.clear();
        }

        void offer(Ref<EntityStore> ref, double distanceSq) {
            if (size < capacity) {
                int index = size++;
                refs[index] = ref;
                distances[index] = distanceSq;
                siftUp(index);
            } else if (distanceSq < distances[0]) {
                refs[0] = ref;
                distances[0] = distanceSq;
                siftDown(0);
            }
        }

        boolean isEmpty() {
            return size == 0;
        }

        void buildKeepSet() {
            for (int i = 0; i < size; i++) {
                keep.put(refs[i], Boolean.TRUE);
            }
        }

        boolean keeps(Ref<EntityStore> ref) {
            return keep.containsKey(ref);
        }

        void clear() {
            for (int i = 0; i < size; i++) {
                refs[i] = null;
            }
            size = 0;
            keep.clear();
        }

        private void siftUp(int index) {
            while (index > 0) {
                int parent = (index - 1) >>> 1;
                if (distances[parent] >= distances[index]) {
                    return;
                }
                swap(parent, index);
                index = parent;
            }
        }

        private void siftDown(int index) {
            int half = size >>> 1;
            while (index < half) {
                int child = (index << 1) + 1;
                int right = child + 1;
                if (right < size && distances[right] > distances[child]) {
                    child = right;
                }
                if (distances[index] >= distances[child]) {
                    return;
                }
                swap(index, child);
                index = child;
            }
        }

        private void swap(int a, int b) {
            Object ref = refs[a];
            refs[a] = refs[b];
            refs[b] = ref;
            double distance = distances[a];
            distances[a] = distances[b];
            distances[b] = distance;
        }
    }
}
