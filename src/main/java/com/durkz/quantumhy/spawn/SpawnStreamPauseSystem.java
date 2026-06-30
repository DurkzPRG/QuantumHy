package com.durkz.quantumhy.spawn;

import com.durkz.quantumhy.config.QuantumHyConfig;
import com.hypixel.hytale.component.ComponentType;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.component.dependency.Dependency;
import com.hypixel.hytale.component.dependency.Order;
import com.hypixel.hytale.component.dependency.SystemDependency;
import com.hypixel.hytale.component.system.tick.TickingSystem;
import com.hypixel.hytale.logger.HytaleLogger;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.chunk.WorldChunk;
import com.hypixel.hytale.server.core.universe.world.storage.ChunkStore;
import com.hypixel.hytale.server.spawning.world.component.ChunkSpawnData;
import com.hypixel.hytale.server.spawning.world.component.WorldSpawnData;
import com.hypixel.hytale.server.spawning.world.system.WorldSpawningSystem;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;

import javax.annotation.Nonnull;
import java.util.Collections;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.LongAdder;

/**
 * Pauses environmental spawning while any player is streaming chunks to the client.
 *
 * <p>Engine flow (see {@link WorldSpawningSystem}, {@code ChunkSpawningSystems},
 * {@code ChunkTracker}): {@code pickRandomChunk} only considers chunks already in
 * {@link com.hypixel.hytale.server.spawning.world.WorldEnvironmentSpawnData#getChunkRefList()}.
 * Chunks in the client's {@code !isLoaded} view ring usually have no server spawn row yet, so
 * per-chunk gating on that ring cannot work. While {@code loadingColumns} is non-empty, the
 * server still runs the spawn loop across every pool chunk the client already has, which competes
 * with chunk IO and shows up as MSPT spikes. Setting {@link ChunkSpawnData#setLastSpawn(long)}
 * on the whole pool makes {@code isOnSpawnCooldown()} skip every picker candidate until streaming
 * finishes.
 */
public final class SpawnStreamPauseSystem extends TickingSystem<ChunkStore> {

    public static final LongAdder POOL_COOLDOWNS = new LongAdder();
    public static final LongAdder POOL_RELEASES = new LongAdder();

    private static final ConcurrentHashMap<String, AtomicLong> COOLDOWNS_SINCE_REPORT = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<String, AtomicLong> RELEASES_SINCE_REPORT = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<String, AtomicBoolean> STREAM_PAUSE_ACTIVE = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<String, LongOpenHashSet> COOLED_BY_WORLD = new ConcurrentHashMap<>();

    private final QuantumHyConfig config;
    private final HytaleLogger logger;
    private final ComponentType<ChunkStore, ChunkSpawnData> chunkSpawnDataType;
    private final ComponentType<ChunkStore, WorldChunk> worldChunkType;
    private final Set<Dependency<ChunkStore>> dependencies;

    public SpawnStreamPauseSystem(@Nonnull QuantumHyConfig config, @Nonnull HytaleLogger logger) {
        this.config = config;
        this.logger = logger;
        this.chunkSpawnDataType = ChunkSpawnData.getComponentType();
        this.worldChunkType = WorldChunk.getComponentType();
        this.dependencies = Collections.singleton(
                new SystemDependency<>(Order.BEFORE, WorldSpawningSystem.class));
    }

    @Nonnull
    @Override
    public Set<Dependency<ChunkStore>> getDependencies() {
        return dependencies;
    }

    @Override
    public void tick(float dt, int index, @Nonnull Store<ChunkStore> store) {
        if (!config.enabled || !config.holdSpawnOnLoadingChunks) {
            return;
        }

        World world = store.getExternalData().getWorld();
        if (world.getPlayerCount() == 0) {
            return;
        }

        String worldName = world.getName();
        LongOpenHashSet wasCooled = COOLED_BY_WORLD.computeIfAbsent(worldName, ignored -> new LongOpenHashSet());
        boolean streaming = SpawnChunkPending.anyViewerLoading(world);

        if (!streaming) {
            STREAM_PAUSE_ACTIVE.put(worldName, new AtomicBoolean(false));
            if (wasCooled.isEmpty()) {
                return;
            }
            for (long chunkIndex : wasCooled) {
                if (releaseCooldown(store, chunkIndex)) {
                    POOL_RELEASES.increment();
                    RELEASES_SINCE_REPORT.computeIfAbsent(worldName, ignored -> new AtomicLong()).incrementAndGet();
                }
            }
            wasCooled.clear();
            return;
        }

        STREAM_PAUSE_ACTIVE.put(worldName, new AtomicBoolean(true));
        LongOpenHashSet poolChunks = collectSpawnPoolChunkIndexes(world, store);
        boolean wasAlreadyPaused = !wasCooled.isEmpty();

        for (long chunkIndex : wasCooled) {
            if (!poolChunks.contains(chunkIndex)) {
                if (releaseCooldown(store, chunkIndex)) {
                    POOL_RELEASES.increment();
                    RELEASES_SINCE_REPORT.computeIfAbsent(worldName, ignored -> new AtomicLong()).incrementAndGet();
                }
            }
        }

        LongOpenHashSet cooledNow = new LongOpenHashSet();
        for (long chunkIndex : poolChunks) {
            if (applyCooldown(store, chunkIndex)) {
                cooledNow.add(chunkIndex);
                POOL_COOLDOWNS.increment();
                COOLDOWNS_SINCE_REPORT.computeIfAbsent(worldName, ignored -> new AtomicLong()).incrementAndGet();
            }
        }

        if (config.verboseLog && !wasAlreadyPaused) {
            logger.atInfo().log(String.format(Locale.ROOT,
                    "spawn pause: environmental spawn held while client streams (world=%s pool=%d)",
                    worldName, cooledNow.size()));
        }

        wasCooled.clear();
        wasCooled.addAll(cooledNow);
    }

    @Nonnull
    private LongOpenHashSet collectSpawnPoolChunkIndexes(@Nonnull World world, @Nonnull Store<ChunkStore> store) {
        LongOpenHashSet indexes = new LongOpenHashSet();
        WorldSpawnData worldSpawnData = world.getEntityStore().getStore().getResource(WorldSpawnData.getResourceType());
        if (worldSpawnData == null) {
            return indexes;
        }
        worldSpawnData.forEachEnvironmentSpawnData(envData -> {
            for (Ref<ChunkStore> chunkRef : envData.getChunkRefList()) {
                if (chunkRef == null || !chunkRef.isValid()) {
                    continue;
                }
                WorldChunk worldChunk = store.getComponent(chunkRef, worldChunkType);
                if (worldChunk != null) {
                    indexes.add(worldChunk.getIndex());
                }
            }
        });
        return indexes;
    }

    private boolean applyCooldown(@Nonnull Store<ChunkStore> store, long chunkIndex) {
        ChunkSpawnData spawnData = chunkSpawnData(store, chunkIndex);
        if (spawnData == null) {
            return false;
        }
        spawnData.setLastSpawn(System.nanoTime());
        return true;
    }

    private boolean releaseCooldown(@Nonnull Store<ChunkStore> store, long chunkIndex) {
        ChunkSpawnData spawnData = chunkSpawnData(store, chunkIndex);
        if (spawnData == null || spawnData.getLastSpawn() == 0L) {
            return false;
        }
        spawnData.setLastSpawn(0L);
        return true;
    }

    private ChunkSpawnData chunkSpawnData(@Nonnull Store<ChunkStore> store, long chunkIndex) {
        Ref<ChunkStore> ref = store.getExternalData().getWorld().getChunkStore().getChunkReference(chunkIndex);
        if (ref == null || !ref.isValid()) {
            return null;
        }
        return store.getComponent(ref, chunkSpawnDataType);
    }

    public static boolean isStreamPauseActive(@Nonnull String worldName) {
        AtomicBoolean flag = STREAM_PAUSE_ACTIVE.get(worldName);
        return flag != null && flag.get();
    }

    public static int poolCooledCount(@Nonnull String worldName) {
        LongOpenHashSet cooled = COOLED_BY_WORLD.get(worldName);
        return cooled == null ? 0 : cooled.size();
    }

    public static long drainCooldownsSinceReport(@Nonnull String worldName) {
        AtomicLong counter = COOLDOWNS_SINCE_REPORT.get(worldName);
        return counter == null ? 0L : counter.getAndSet(0L);
    }

    public static long drainReleasesSinceReport(@Nonnull String worldName) {
        AtomicLong counter = RELEASES_SINCE_REPORT.get(worldName);
        return counter == null ? 0L : counter.getAndSet(0L);
    }
}
