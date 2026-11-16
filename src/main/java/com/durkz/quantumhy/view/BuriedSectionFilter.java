package com.durkz.quantumhy.view;

import com.durkz.quantumhy.config.PlayerPreferences;
import com.durkz.quantumhy.config.QuantumHyConfig;
import com.hypixel.hytale.component.ArchetypeChunk;
import com.hypixel.hytale.component.CommandBuffer;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.component.query.Query;
import com.hypixel.hytale.component.system.tick.EntityTickingSystem;
import com.hypixel.hytale.logger.HytaleLogger;
import com.hypixel.hytale.math.vector.Transform;
import com.hypixel.hytale.protocol.CachedPacket;
import com.hypixel.hytale.protocol.Packet;
import com.hypixel.hytale.protocol.packets.world.SetChunk;
import com.hypixel.hytale.protocol.packets.world.SetColumn;
import com.hypixel.hytale.protocol.packets.world.SetFluids;
import com.hypixel.hytale.server.core.asset.type.blocktype.config.BlockType;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.io.adapter.PlayerPacketFilter;
import com.hypixel.hytale.server.core.modules.entity.player.ChunkTracker;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.chunk.section.BlockSection;
import com.hypixel.hytale.server.core.universe.world.chunk.section.FluidSection;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import io.netty.buffer.ByteBuf;
import it.unimi.dsi.fastutil.ints.IntArrayList;
import it.unimi.dsi.fastutil.longs.Long2IntMap;
import it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap;
import it.unimi.dsi.fastutil.objects.ObjectIterator;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.lang.foreign.MemorySegment;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.LongAdder;
import java.util.function.Supplier;

/**
 * Sends a uniform stone section in place of terrain the player cannot see: sections buried under
 * the surface and far enough below the player (see {@link BuriedSectionPolicy}).
 *
 * The client only draws a column once every section has arrived, so sections are swapped, never
 * dropped. The stand-in costs a few bytes and no meshing, while a real deep section carries caves,
 * ores, lava and aquifers the client would decode, mesh and keep in memory.
 *
 * The engine still thinks the real section was sent. {@link Restorer} asks the chunk tracker to
 * resend it ({@code removeForReload}) once the player comes within the near ring above it or drops
 * close to it, and this filter then lets the real packets through. Section packets are written one
 * by one through the single-packet path, so dropping one never leaves a hole in a batch.
 */
public final class BuriedSectionFilter implements PlayerPacketFilter {

    public static final LongAdder REPLACED = new LongAdder();
    public static final LongAdder RESTORED = new LongAdder();

    private static final HytaleLogger LOGGER = HytaleLogger.forEnclosingClass();
    private static final VarHandle CACHED_BYTES = cachedBytesHandle();
    private static final ThreadLocal<Boolean> WRITING = ThreadLocal.withInitial(() -> Boolean.FALSE);
    private static final int PART_BLOCKS = 0;
    private static final int PART_FLUIDS = 1;

    private final Supplier<QuantumHyConfig> settings;
    private final PlayerPreferences preferences;
    private final ConcurrentHashMap<UUID, PlayerState> players = new ConcurrentHashMap<>();
    private volatile byte[] stoneSection;
    private volatile byte[] emptyFluids;
    private volatile boolean broken = CACHED_BYTES == null;

    public BuriedSectionFilter(Supplier<QuantumHyConfig> settings, PlayerPreferences preferences) {
        this.settings = settings;
        this.preferences = preferences;
        if (broken) {
            LOGGER.atWarning().log("QuantumHy buried sections disabled: CachedPacket layout changed.");
        }
    }

    @Override
    public boolean test(PlayerRef playerRef, Packet packet) {
        if (broken || WRITING.get() || playerRef == null) {
            return false;
        }
        Class<?> type = packet instanceof CachedPacket<?> cached ? cached.getPacketType() : packet.getClass();
        if (type == SetColumn.class) {
            rememberColumn(playerRef, packet);
            return false;
        }
        int part = type == SetChunk.class ? PART_BLOCKS : type == SetFluids.class ? PART_FLUIDS : -1;
        if (part < 0) {
            return false;
        }
        UUID id = playerRef.getUuid();
        PlayerState state = id == null ? null : players.get(id);
        Transform transform = playerRef.getTransform();
        if (state == null || transform == null || transform.getPosition() == null
                || !preferences.isOptimizationEnabled(id)) {
            return false;
        }
        int x;
        int y;
        int z;
        if (packet instanceof SetChunk p) {
            x = p.x; y = p.y; z = p.z;
        } else if (packet instanceof SetFluids p) {
            x = p.x; y = p.y; z = p.z;
        } else {
            byte[] head = head(packet, 13);
            if (head == null) {
                return false;
            }
            MemorySegment memory = MemorySegment.ofArray(head);
            x = part == PART_BLOCKS ? SetChunk.getX(memory) : SetFluids.getX(memory);
            y = part == PART_BLOCKS ? SetChunk.getY(memory) : SetFluids.getY(memory);
            z = part == PART_BLOCKS ? SetChunk.getZ(memory) : SetFluids.getZ(memory);
        }
        if (y < 0 || y > 30) {
            return false;
        }
        double px = transform.getPosition().x;
        double py = transform.getPosition().y;
        double pz = transform.getPosition().z;
        int ring = BuriedSectionPolicy.ring(x, z, (int) Math.floor(px) >> 5, (int) Math.floor(pz) >> 5);
        int near = settings.get().buriedSectionNearChunks;
        if (!BuriedSectionPolicy.replace(y, state.surfaceFloor(x, z), py, ring, near) || !ensureStandIns()) {
            return false;
        }
        state.markReplaced(x, y, z);
        WRITING.set(Boolean.TRUE);
        try {
            playerRef.getPacketHandler().write(part == PART_BLOCKS
                    ? new SetChunk(x, y, z, null, null, stoneSection)
                    : new SetFluids(x, y, z, emptyFluids));
        } finally {
            WRITING.set(Boolean.FALSE);
        }
        if (part == PART_BLOCKS) {
            REPLACED.increment();
        }
        return true;
    }

    /** Clears everything known about a player; their next column packets start fresh. */
    public void forget(@Nullable UUID playerId) {
        if (playerId != null) {
            players.remove(playerId);
        }
    }

    /** World-thread system that swaps stand-ins back for real sections as the player nears them. */
    public Restorer restorer() {
        return new Restorer();
    }

    private void rememberColumn(PlayerRef playerRef, Packet packet) {
        UUID id = playerRef.getUuid();
        if (id == null) {
            return;
        }
        int[] heightmap;
        int x;
        int z;
        if (packet instanceof SetColumn column) {
            heightmap = column.heightmap;
            x = column.x;
            z = column.z;
        } else {
            byte[] bytes = head(packet, -1);
            if (bytes == null) {
                return;
            }
            MemorySegment memory = MemorySegment.ofArray(bytes);
            heightmap = SetColumn.getHeightmap(memory);
            x = SetColumn.getX(memory);
            z = SetColumn.getZ(memory);
        }
        int lowest = BuriedSectionPolicy.lowest(heightmap);
        if (lowest != Integer.MIN_VALUE) {
            players.computeIfAbsent(id, ignored -> new PlayerState()).rememberFloor(x, z, lowest);
        }
    }

    /** First {@code length} serialized bytes of a packet, or all of them for {@code -1}. */
    @Nullable
    private byte[] head(Packet packet, int length) {
        try {
            if (packet instanceof CachedPacket<?> cached) {
                ByteBuf buf = (ByteBuf) CACHED_BYTES.get(cached);
                int size = length < 0 ? buf.readableBytes() : Math.min(length, buf.readableBytes());
                byte[] out = new byte[size];
                buf.getBytes(buf.readerIndex(), out);
                return out;
            }
            return null;
        } catch (RuntimeException e) {
            broken = true;
            LOGGER.atWarning().withCause(e).log("QuantumHy buried sections disabled: packet read failed.");
            return null;
        }
    }

    private boolean ensureStandIns() {
        if (stoneSection != null) {
            return true;
        }
        synchronized (this) {
            if (stoneSection != null) {
                return true;
            }
            try {
                int stone = BlockType.getAssetMap().getIndex("Rock_Stone");
                if (stone <= 0) {
                    throw new IllegalStateException("Rock_Stone missing");
                }
                BlockSection section = new BlockSection();
                for (int bx = 0; bx < 32; bx++) {
                    for (int by = 0; by < 32; by++) {
                        for (int bz = 0; bz < 32; bz++) {
                            section.set(bx, by, bz, stone, 0, 0);
                        }
                    }
                }
                var serialize = FluidSection.class.getDeclaredMethod("serializeForPacket");
                serialize.setAccessible(true);
                emptyFluids = (byte[]) serialize.invoke(new FluidSection());
                stoneSection = section.serializeForPacket();
                return true;
            } catch (ReflectiveOperationException | RuntimeException e) {
                broken = true;
                LOGGER.atWarning().withCause(e).log("QuantumHy buried sections disabled: stand-in build failed.");
                return false;
            }
        }
    }

    @Nullable
    private static VarHandle cachedBytesHandle() {
        try {
            return MethodHandles.privateLookupIn(CachedPacket.class, MethodHandles.lookup())
                    .findVarHandle(CachedPacket.class, "cachedBytes", ByteBuf.class);
        } catch (ReflectiveOperationException | RuntimeException e) {
            return null;
        }
    }

    /** Per player: lowest surface per column and replaced sections per column (bit = section y). */
    private static final class PlayerState {
        private final Long2IntOpenHashMap floors = new Long2IntOpenHashMap();
        private final Long2IntOpenHashMap replaced = new Long2IntOpenHashMap();
        private int ticks;

        synchronized void rememberFloor(int x, int z, int lowest) {
            floors.put(key(x, z), lowest);
        }

        /** Lowest surface over the column and its known neighbours; unknown column means unknown. */
        synchronized int surfaceFloor(int x, int z) {
            if (!floors.containsKey(key(x, z))) {
                return Integer.MIN_VALUE;
            }
            int floor = Integer.MAX_VALUE;
            for (int dx = -1; dx <= 1; dx++) {
                for (int dz = -1; dz <= 1; dz++) {
                    long k = key(x + dx, z + dz);
                    if (floors.containsKey(k)) {
                        floor = Math.min(floor, floors.get(k));
                    }
                }
            }
            return floor;
        }

        synchronized void markReplaced(int x, int y, int z) {
            long k = key(x, z);
            replaced.put(k, replaced.get(k) | (1 << y));
        }
    }

    private static long key(int x, int z) {
        return ((long) x << 32) | (z & 0xFFFFFFFFL);
    }

    /**
     * Swaps stand-ins back on the world thread, a few times per second. Entries for columns far
     * outside the view radius are forgotten: the tracker has unloaded them, and a later resend goes
     * through the filter again.
     */
    public final class Restorer extends EntityTickingSystem<EntityStore> {
        private static final int EVERY_TICKS = 6;
        private final Query<EntityStore> query = Query.and(PlayerRef.getComponentType(),
                Player.getComponentType(), ChunkTracker.getComponentType());
        private final IntArrayList reloads = new IntArrayList();

        @Override
        public Query<EntityStore> getQuery() {
            return query;
        }

        @Override
        public boolean isParallel(int archetypeChunkSize, int taskCount) {
            return false;
        }

        @Override
        public void tick(float dt, int index, @Nonnull ArchetypeChunk<EntityStore> chunk,
                         @Nonnull Store<EntityStore> store, @Nonnull CommandBuffer<EntityStore> commands) {
            PlayerRef playerRef = chunk.getComponent(index, PlayerRef.getComponentType());
            Player player = chunk.getComponent(index, Player.getComponentType());
            ChunkTracker tracker = chunk.getComponent(index, ChunkTracker.getComponentType());
            if (playerRef == null || player == null || tracker == null || playerRef.getUuid() == null) {
                return;
            }
            PlayerState state = players.get(playerRef.getUuid());
            Transform transform = playerRef.getTransform();
            if (state == null || transform == null || transform.getPosition() == null
                    || ++state.ticks % EVERY_TICKS != 0) {
                return;
            }
            double py = transform.getPosition().y;
            int pcx = (int) Math.floor(transform.getPosition().x) >> 5;
            int pcz = (int) Math.floor(transform.getPosition().z) >> 5;
            int near = settings.get().buriedSectionNearChunks;
            boolean optedOut = !preferences.isOptimizationEnabled(playerRef.getUuid());
            int forgetBeyond = Math.max(player.getViewRadius(), 1) + 3;
            // The engine writes section packets (and so runs this filter) while holding the tracker's
            // lock, so removeForReload must never be called while holding the state lock.
            reloads.clear();
            synchronized (state) {
                ObjectIterator<Long2IntMap.Entry> it = state.replaced.long2IntEntrySet().fastIterator();
                while (it.hasNext()) {
                    Long2IntMap.Entry entry = it.next();
                    long column = entry.getLongKey();
                    int x = (int) (column >> 32);
                    int z = (int) column;
                    int ring = BuriedSectionPolicy.ring(x, z, pcx, pcz);
                    if (ring > forgetBeyond) {
                        it.remove();
                        state.floors.remove(column);
                        continue;
                    }
                    int bits = entry.getIntValue();
                    int keep = bits;
                    for (int y = 0; bits != 0; y++, bits >>>= 1) {
                        if ((bits & 1) != 0 && (optedOut || BuriedSectionPolicy.restore(y, py, ring, near))) {
                            reloads.add(x);
                            reloads.add(y);
                            reloads.add(z);
                            keep &= ~(1 << y);
                        }
                    }
                    if (keep == 0) {
                        it.remove();
                    } else if (keep != entry.getIntValue()) {
                        entry.setValue(keep);
                    }
                }
                if (state.floors.size() > 4 * (forgetBeyond * 2 + 1) * (forgetBeyond * 2 + 1)) {
                    state.floors.long2IntEntrySet().removeIf(e -> BuriedSectionPolicy.ring(
                            (int) (e.getLongKey() >> 32), (int) e.getLongKey(), pcx, pcz) > forgetBeyond);
                }
            }
            for (int i = 0; i < reloads.size(); i += 3) {
                tracker.removeForReload(reloads.getInt(i), reloads.getInt(i + 1), reloads.getInt(i + 2));
            }
            RESTORED.add(reloads.size() / 3);
        }
    }
}
