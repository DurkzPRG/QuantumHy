package com.durkz.quantumhy.view;

import com.durkz.quantumhy.client.ClientStrainMonitor;
import com.durkz.quantumhy.config.PlayerPreferences;
import com.durkz.quantumhy.config.QuantumHyConfig;
import com.hypixel.hytale.component.ArchetypeChunk;
import com.hypixel.hytale.component.CommandBuffer;
import com.hypixel.hytale.component.ComponentType;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.component.dependency.Dependency;
import com.hypixel.hytale.component.dependency.Order;
import com.hypixel.hytale.component.dependency.SystemGroupDependency;
import com.hypixel.hytale.component.query.Query;
import com.hypixel.hytale.component.system.tick.EntityTickingSystem;
import com.hypixel.hytale.logger.HytaleLogger;
import com.hypixel.hytale.protocol.ComponentUpdate;
import com.hypixel.hytale.protocol.TransformUpdate;
import com.hypixel.hytale.server.core.modules.entity.component.TransformComponent;
import com.hypixel.hytale.server.core.modules.entity.tracker.EntityTrackerSystems;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.lang.reflect.Field;
import java.util.Collection;
import java.util.IdentityHashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

/**
 * Experimental: sends position-only updates of distant entities less often.
 *
 * Runs after every system in {@code QUEUE_UPDATE_GROUP} and before {@code SEND_PACKET_GROUP}, so it
 * sees each viewer's finished update map right before {@code SendPackets} turns it into a packet.
 *
 * Safety rules, from the 0.7 source:
 * <ul>
 *   <li>Only entries holding exactly one {@link TransformUpdate} and no removals are held back, and
 *   only for entities this viewer was already sent. Spawns, models, skins and removals always go.</li>
 *   <li>The engine queues a transform only when it changes, so a held update is re-queued on the
 *   entity's next allowed tick; an entity that stops moving still lands on its final position.
 *   {@link TransformUpdate} points at the entity's live sent-transform, so the re-queued object
 *   always carries the latest position.</li>
 *   <li>{@code EntityUpdate} keeps its contents private; they are read through cached reflection. If
 *   that fails on a future build the system turns itself off.</li>
 * </ul>
 * One viewer is handled by one worker at a time, so per-viewer state needs no locking.
 */
public final class DistantUpdateThrottleSystem extends EntityTickingSystem<EntityStore> {

    private static final HytaleLogger LOGGER = HytaleLogger.forEnclosingClass();
    private static final ConcurrentHashMap<UUID, ViewerState> VIEWERS = new ConcurrentHashMap<>();

    private static final Field UPDATES_FIELD = field("updates");
    private static final Field REMOVED_FIELD = field("removed");
    private static volatile boolean broken = UPDATES_FIELD == null || REMOVED_FIELD == null;

    private final Supplier<QuantumHyConfig> settings;
    private final PlayerPreferences preferences;
    private final ClientStrainMonitor strain;
    private final ComponentType<EntityStore, EntityTrackerSystems.EntityViewer> viewerType;
    private final ComponentType<EntityStore, TransformComponent> transformType;
    private final ComponentType<EntityStore, PlayerRef> playerRefType;
    private final Query<EntityStore> query;
    private final Set<Dependency<EntityStore>> dependencies;

    public DistantUpdateThrottleSystem(@Nonnull Supplier<QuantumHyConfig> settings,
                                       @Nonnull PlayerPreferences preferences,
                                       @Nonnull ClientStrainMonitor strain) {
        this.settings = settings;
        this.preferences = preferences;
        this.strain = strain;
        this.viewerType = EntityTrackerSystems.EntityViewer.getComponentType();
        this.transformType = TransformComponent.getComponentType();
        this.playerRefType = PlayerRef.getComponentType();
        this.query = Query.and(viewerType, transformType, playerRefType);
        this.dependencies = Set.of(
                new SystemGroupDependency<>(Order.AFTER, EntityTrackerSystems.QUEUE_UPDATE_GROUP),
                new SystemGroupDependency<>(Order.BEFORE, EntityStore.SEND_PACKET_GROUP));
        if (broken) {
            LOGGER.atWarning().log("QuantumHy distant update throttle disabled: EntityUpdate layout changed.");
        }
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
        if (broken) {
            return;
        }
        EntityTrackerSystems.EntityViewer viewer = archetypeChunk.getComponent(index, viewerType);
        PlayerRef playerRef = archetypeChunk.getComponent(index, playerRefType);
        TransformComponent self = archetypeChunk.getComponent(index, transformType);
        if (viewer == null || playerRef == null || self == null || playerRef.getUuid() == null) {
            return;
        }
        UUID playerId = playerRef.getUuid();
        QuantumHyConfig config = settings.get();
        ViewerState state = VIEWERS.get(playerId);
        if (!config.enabled || !preferences.isOptimizationEnabled(playerId)) {
            if (state != null) {
                flushAll(viewer, state);
                VIEWERS.remove(playerId);
            }
            return;
        }
        if (state == null) {
            state = VIEWERS.computeIfAbsent(playerId, ignored -> new ViewerState());
        }
        long tick = ++state.tick;
        boolean strained = strain.strained(playerId);
        var origin = self.getPosition();

        try {
            for (Iterator<Map.Entry<Ref<EntityStore>, EntityTrackerSystems.EntityUpdate>> it =
                         viewer.updates.entrySet().iterator(); it.hasNext(); ) {
                Map.Entry<Ref<EntityStore>, EntityTrackerSystems.EntityUpdate> entry = it.next();
                Ref<EntityStore> ref = entry.getKey();
                TransformUpdate only = transformOnly(entry.getValue());
                if (only == null || !viewer.sent.containsKey(ref)) {
                    if (only != null || carriesTransform(entry.getValue())) {
                        state.held.remove(ref);
                    }
                    continue;
                }
                TransformComponent other = commandBuffer.getComponent(ref, transformType);
                if (other == null) {
                    state.held.remove(ref);
                    continue;
                }
                int interval = DistantUpdatePolicy.interval(other.getPosition().distanceSquared(origin),
                        config.distantUpdateNearBlocks, config.distantUpdateFarBlocks, strained);
                if (DistantUpdatePolicy.allowed(tick, phase(ref), interval)) {
                    state.held.remove(ref);
                } else {
                    Held held = state.held.get(ref);
                    if (held == null) {
                        state.held.put(ref, new Held(only, interval));
                    } else {
                        held.update = only;
                        held.interval = interval;
                    }
                    it.remove();
                }
            }

            if (!state.held.isEmpty()) {
                for (Iterator<Map.Entry<Ref<EntityStore>, Held>> it = state.held.entrySet().iterator(); it.hasNext(); ) {
                    Map.Entry<Ref<EntityStore>, Held> entry = it.next();
                    Ref<EntityStore> ref = entry.getKey();
                    if (!ref.isValid() || !viewer.visible.contains(ref)) {
                        it.remove();
                        continue;
                    }
                    Held held = entry.getValue();
                    if (viewer.updates.containsKey(ref)) {
                        continue;
                    }
                    if (DistantUpdatePolicy.allowed(tick, phase(ref), held.interval)) {
                        viewer.queueUpdate(ref, held.update);
                        it.remove();
                    }
                }
            }
        } catch (ReflectiveOperationException | RuntimeException ex) {
            broken = true;
            flushAll(viewer, state);
            VIEWERS.remove(playerId);
            LOGGER.atWarning().withCause(ex).log("QuantumHy distant update throttle disabled after an error.");
        }
    }

    /** Re-queues everything held for this viewer, e.g. on opt-out, so no final position is lost. */
    private static void flushAll(EntityTrackerSystems.EntityViewer viewer, ViewerState state) {
        for (Map.Entry<Ref<EntityStore>, Held> entry : state.held.entrySet()) {
            Ref<EntityStore> ref = entry.getKey();
            if (ref.isValid() && viewer.visible.contains(ref) && !viewer.updates.containsKey(ref)) {
                viewer.queueUpdate(ref, entry.getValue().update);
            }
        }
        state.held.clear();
    }

    /** The single {@link TransformUpdate} in this entry, or {@code null} if it holds anything else. */
    @Nullable
    private static TransformUpdate transformOnly(EntityTrackerSystems.EntityUpdate update)
            throws ReflectiveOperationException {
        Collection<?> removed = (Collection<?>) REMOVED_FIELD.get(update);
        if (!removed.isEmpty()) {
            return null;
        }
        List<?> updates = (List<?>) UPDATES_FIELD.get(update);
        if (updates.size() != 1) {
            return null;
        }
        Object only = updates.get(0);
        return only instanceof TransformUpdate transform ? transform : null;
    }

    private static boolean carriesTransform(EntityTrackerSystems.EntityUpdate update)
            throws ReflectiveOperationException {
        for (Object item : (List<?>) UPDATES_FIELD.get(update)) {
            if (item instanceof TransformUpdate) {
                return true;
            }
        }
        return false;
    }

    private static int phase(Ref<EntityStore> ref) {
        return System.identityHashCode(ref) & 0x7;
    }

    @Nullable
    private static Field field(String name) {
        try {
            Field field = EntityTrackerSystems.EntityUpdate.class.getDeclaredField(name);
            field.setAccessible(true);
            return field;
        } catch (ReflectiveOperationException | RuntimeException ex) {
            return null;
        }
    }

    public static void forget(@Nullable UUID playerId) {
        if (playerId != null) {
            VIEWERS.remove(playerId);
        }
    }

    public static void retain(Set<UUID> online) {
        VIEWERS.keySet().retainAll(online);
    }

    public static void clearSession() {
        VIEWERS.clear();
    }

    private static final class ViewerState {
        long tick;
        final Map<Ref<EntityStore>, Held> held = new IdentityHashMap<>();
    }

    private static final class Held {
        ComponentUpdate update;
        int interval;

        Held(ComponentUpdate update, int interval) {
            this.update = update;
            this.interval = interval;
        }
    }
}
