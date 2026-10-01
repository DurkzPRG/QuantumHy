package com.durkz.quantumhy.view;

import com.durkz.quantumhy.config.QuantumHyConfig;
import com.durkz.quantumhy.config.PlayerPreferences;
import com.durkz.quantumhy.pressure.PressureGovernor;
import com.durkz.quantumhy.runtime.RuntimeMetrics;
import com.hypixel.hytale.builtin.mounts.MountedComponent;
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
import com.hypixel.hytale.protocol.InteractionState;
import com.hypixel.hytale.server.core.entity.InteractionManager;
import com.hypixel.hytale.server.core.modules.interaction.InteractionModule;
import com.hypixel.hytale.server.core.modules.entity.component.TransformComponent;
import com.hypixel.hytale.server.core.modules.entity.tracker.EntityTrackerSystems;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.LongAdder;
import java.util.function.Supplier;

/** Trims engine candidates with stable distance selection and narrowly scoped gameplay protection. */
public final class EntityCullSystem extends EntityTickingSystem<EntityStore> {
    public static final LongAdder VERTICAL_CULLED = new LongAdder();
    public static final LongAdder CAP_CULLED = new LongAdder();

    private static final ConcurrentHashMap<String, AtomicLong> VERTICAL_SINCE_REPORT = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<String, AtomicLong> CAP_SINCE_REPORT = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<UUID, StableEntitySelection<Ref<EntityStore>>> SELECTIONS =
            new ConcurrentHashMap<>();

    private final Supplier<QuantumHyConfig> settings;
    private final PlayerPreferences preferences;
    private final ComponentType<EntityStore, EntityTrackerSystems.EntityViewer> entityViewerComponentType;
    private final ComponentType<EntityStore, PlayerRef> playerRefComponentType;
    private final ComponentType<EntityStore, TransformComponent> transformComponentType;
    private final ComponentType<EntityStore, MountedComponent> mountedComponentType;
    private final ComponentType<EntityStore, InteractionManager> interactionManagerComponentType;
    private final Query<EntityStore> query;
    private final Set<Dependency<EntityStore>> dependencies;

    public EntityCullSystem(@Nonnull ComponentType<EntityStore, EntityTrackerSystems.EntityViewer> viewerType,
                            @Nonnull Supplier<QuantumHyConfig> settings, @Nonnull PlayerPreferences preferences) {
        this.settings = settings;
        this.preferences = preferences;
        this.entityViewerComponentType = viewerType;
        this.playerRefComponentType = PlayerRef.getComponentType();
        this.transformComponentType = TransformComponent.getComponentType();
        this.mountedComponentType = MountedComponent.getComponentType();
        this.interactionManagerComponentType = InteractionModule.get().getInteractionManagerComponent();
        this.query = Query.and(viewerType, transformComponentType, playerRefComponentType);
        Set<Dependency<EntityStore>> ordered = new HashSet<>(3);
        ordered.add(new SystemDependency<>(Order.AFTER, EntityTrackerSystems.CollectVisible.class));
        ordered.add(new SystemDependency<>(Order.AFTER, EntityTrackerSystems.LODCull.class));
        ordered.add(new SystemDependency<>(Order.AFTER, EntityTrackerSystems.HideFromPlayer.class));
        this.dependencies = Collections.unmodifiableSet(ordered);
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
        final QuantumHyConfig config = settings.get();
        final var viewer = archetypeChunk.getComponent(index, entityViewerComponentType);
        final PlayerRef playerRef = archetypeChunk.getComponent(index, playerRefComponentType);
        if (viewer == null || playerRef == null || playerRef.getUuid() == null) {
            return;
        }
        final UUID playerId = playerRef.getUuid();
        if (!config.enabled || !preferences.isOptimizationEnabled(playerId)) {
            forget(playerId);
            VisualLoadRegistry.remove(playerId);
            return;
        }
        final World world = store.getExternalData().getWorld();
        if (world == null || !world.getWorldConfig().getUuid().equals(playerRef.getWorldUuid())) {
            return;
        }
        final String worldName = world.getName();
        final var transform = archetypeChunk.getComponent(index, transformComponentType);
        if (transform == null) {
            return;
        }
        final var position = transform.getPosition();
        final int visibleBefore = viewer.visible.size();
        StableEntitySelection<Ref<EntityStore>> selection =
                SELECTIONS.computeIfAbsent(playerId, ignored -> new StableEntitySelection<>());
        selection.begin(playerRef.getWorldUuid(), config);

        MountedComponent mounted = archetypeChunk.getComponent(index, mountedComponentType);
        Ref<EntityStore> mount = mounted == null ? null : mounted.getMountedToEntity();
        if (validCandidate(mount, viewer, store)) {
            selection.protectMount(mount);
        }
        InteractionManager manager = archetypeChunk.getComponent(index, interactionManagerComponentType);
        if (manager != null) {
            for (var chain : manager.getChains().values()) {
                if (chain.getServerState() != InteractionState.NotFinished || chain.isPredicted()) {
                    continue;
                }
                var context = chain.getContext();
                Ref<EntityStore> target = context == null ? null : context.getTargetEntity();
                if (!validCandidate(target, viewer, store)) {
                    continue;
                }
                var targetTransform = commandBuffer.getComponent(target, transformComponentType);
                if (targetTransform != null) {
                    selection.considerInteraction(target, targetTransform.getPosition().distanceSquared(position));
                }
            }
        }

        int verticalCulled = 0;
        boolean capEnabled = config.maxVisibleEntitiesPerPlayer > 0;
        int maxVertical = PressureGovernor.verticalDistance(playerRef.getWorldUuid(), config);
        for (var iterator = viewer.visible.iterator(); iterator.hasNext();) {
            Ref<EntityStore> ref = iterator.next();
            if (!ref.isValid() || ref.getStore() != store) {
                iterator.remove();
                continue;
            }
            if (commandBuffer.getArchetype(ref).contains(playerRefComponentType)) {
                continue;
            }
            var targetTransform = commandBuffer.getComponent(ref, transformComponentType);
            if (targetTransform != null
                    && selection.excludeVertically(ref, targetTransform.getPosition().y - position.y, maxVertical)) {
                iterator.remove();
                verticalCulled++;
                continue;
            }
            if (capEnabled) {
                selection.stashCandidate(ref, targetTransform == null ? Double.POSITIVE_INFINITY
                        : targetTransform.getPosition().distanceSquared(position));
            }
        }
        recordCull(worldName, verticalCulled, VERTICAL_CULLED, VERTICAL_SINCE_REPORT);
        int capCulled = capEnabled ? capToNearest(viewer, config.maxVisibleEntitiesPerPlayer, selection) : 0;
        recordCull(worldName, capCulled, CAP_CULLED, CAP_SINCE_REPORT);
        selection.finish(viewer.visible);
        int candidates = visibleBefore + Math.max(0, viewer.lodExcludedCount);
        VisualLoadRegistry.record(playerId, candidates, viewer.visible.size(), viewer.viewRadiusBlocks,
                selection.entered(), selection.exited());
        RuntimeMetrics.cull(System.nanoTime() - startNs, visibleBefore, verticalCulled, capCulled);
    }

    private static boolean validCandidate(Ref<EntityStore> ref, EntityTrackerSystems.EntityViewer viewer,
                                          Store<EntityStore> store) {
        return ref != null && ref.isValid() && ref.getStore() == store && viewer.visible.contains(ref);
    }

    /**
     * Keeps the nearest {@code cap} non-player entities. Candidates and their distances were stashed
     * by the vertical pass, so this is one heap pass plus one identity-set removal.
     */
    private static int capToNearest(EntityTrackerSystems.EntityViewer viewer, int cap,
                                    StableEntitySelection<Ref<EntityStore>> selection) {
        if (viewer.visible.size() <= cap) {
            return 0;
        }
        Set<Ref<EntityStore>> drops = selection.selectCapDrops(cap);
        if (drops.isEmpty()) {
            return 0;
        }
        int before = viewer.visible.size();
        viewer.visible.removeIf(drops::contains);
        return before - viewer.visible.size();
    }

    private static void recordCull(String worldName, int count, LongAdder total,
                                   ConcurrentHashMap<String, AtomicLong> reports) {
        if (count > 0) {
            total.add(count);
            reports.computeIfAbsent(worldName, ignored -> new AtomicLong()).addAndGet(count);
        }
    }

    public static long drainVerticalSinceReport(@Nonnull String worldName) {
        AtomicLong counter = VERTICAL_SINCE_REPORT.get(worldName);
        return counter == null ? 0L : counter.getAndSet(0L);
    }

    public static long drainCapSinceReport(@Nonnull String worldName) {
        AtomicLong counter = CAP_SINCE_REPORT.get(worldName);
        return counter == null ? 0L : counter.getAndSet(0L);
    }

    public static void forget(UUID playerId) {
        if (playerId != null) {
            SELECTIONS.remove(playerId);
        }
    }

    public static void retain(Set<UUID> online) {
        SELECTIONS.keySet().retainAll(online);
    }

    public static void clearSession() {
        SELECTIONS.clear();
        VERTICAL_SINCE_REPORT.clear();
        CAP_SINCE_REPORT.clear();
        VERTICAL_CULLED.reset();
        CAP_CULLED.reset();
    }
}
