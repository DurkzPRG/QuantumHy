package com.durkz.quantumhy.disk;

import com.hypixel.hytale.component.Holder;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.protocol.CachedPacket;
import com.hypixel.hytale.protocol.ToClientPacket;
import com.hypixel.hytale.server.core.universe.world.chunk.BlockChunk;
import com.hypixel.hytale.server.core.universe.world.chunk.ChunkColumn;
import com.hypixel.hytale.server.core.universe.world.chunk.WorldChunk;
import com.hypixel.hytale.server.core.universe.world.chunk.environment.EnvironmentChunk;
import com.hypixel.hytale.server.core.universe.world.chunk.heightmap.HeightmapColumn;
import com.hypixel.hytale.server.core.universe.world.chunk.heightmap.HeightmapSystems;
import com.hypixel.hytale.server.core.universe.world.chunk.section.BlockSection;
import com.hypixel.hytale.server.core.universe.world.chunk.section.ChunkSection;
import com.hypixel.hytale.server.core.universe.world.chunk.section.EnvironmentSection;
import com.hypixel.hytale.server.core.universe.world.chunk.section.FluidSection;
import com.hypixel.hytale.server.core.universe.world.storage.BufferChunkLoader;
import com.hypixel.hytale.server.core.universe.world.storage.ChunkStore;
import com.hypixel.hytale.server.core.universe.world.storage.IChunkLoader;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.foreign.MemorySegment;
import java.lang.invoke.MethodType;
import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.zip.CRC32C;

/**
 * Terrain packets built by the engine's own serializers. Saved columns are read through the world's loader into a
 * detached holder that is prepared like the native pre-load; resident columns use their live components.
 */
final class DiskSnapshots {
    private static final MethodHandle COLUMN_PACKET = columnPacketHandle();

    private DiskSnapshots() {
    }

    static boolean available() {
        return COLUMN_PACKET != null;
    }

    /** Only the non-cubic buffer loader is supported; anything else stays on native streaming. */
    static boolean supports(ChunkStore chunkStore) {
        if (!available() || chunkStore == null || chunkStore.supportsCubicSections()) return false;
        IChunkLoader loader = chunkStore.getLoader();
        return loader instanceof BufferChunkLoader && !(loader instanceof IChunkLoader.Cubic);
    }

    /** Reads a saved column. Completes with null when the column was never saved. */
    static CompletableFuture<Snapshot> persisted(IChunkLoader loader, int x, int z, int mask) {
        return loader.loadHolder(x, z).thenCompose(holder -> {
            if (holder == null) return CompletableFuture.completedFuture(null);
            Snapshot snapshot = fromDetached(loader, holder, x, z, mask);
            return snapshot.ready().thenApply(ignored -> snapshot);
        });
    }

    /** World thread only. Null when the column or one of the requested sections is not resident. */
    static Snapshot resident(ChunkStore chunkStore, long index, int mask) {
        Ref<ChunkStore> columnRef = chunkStore.getChunkReference(index);
        if (columnRef == null || !columnRef.isValid()) return null;
        Store<ChunkStore> store = chunkStore.getStore();
        BlockChunk blockChunk = store.getComponent(columnRef, BlockChunk.getComponentType());
        if (blockChunk == null) return null;
        int x = DiskGeometry.x(index);
        int z = DiskGeometry.z(index);
        Snapshot snapshot = new Snapshot(x, z, mask, columnPacket(blockChunk));
        for (int y = 0; y < DiskGeometry.SECTIONS; y++) {
            if ((mask & (1 << y)) == 0) continue;
            Ref<ChunkStore> sectionRef = chunkStore.getChunkSectionReference(x, y, z);
            if (sectionRef == null || !sectionRef.isValid()) return null;
            BlockSection blocks = store.getComponent(sectionRef, BlockSection.getComponentType());
            if (blocks == null) return null;
            snapshot.section(y, blocks, store.getComponent(sectionRef, FluidSection.getComponentType()),
                    store.getComponent(sectionRef, EnvironmentSection.getComponentType()));
        }
        return snapshot;
    }

    /** World thread only. True when the resident column packet is still the engine's current one. */
    static boolean columnUnchanged(ChunkStore chunkStore, Snapshot snapshot) {
        Ref<ChunkStore> columnRef = chunkStore.getChunkReference(DiskGeometry.index(snapshot.x, snapshot.z));
        if (columnRef == null || !columnRef.isValid()) return false;
        BlockChunk blockChunk = chunkStore.getStore().getComponent(columnRef, BlockChunk.getComponentType());
        return blockChunk != null && columnPacket(blockChunk) == snapshot.column;
    }

    /** World thread only. True when the section packets in {@code mask} are still the engine's current ones. */
    static boolean sectionsUnchanged(ChunkStore chunkStore, Snapshot snapshot, int mask) {
        Store<ChunkStore> store = chunkStore.getStore();
        for (int y = 0; y < DiskGeometry.SECTIONS; y++) {
            if ((mask & snapshot.mask & (1 << y)) == 0) continue;
            Ref<ChunkStore> sectionRef = chunkStore.getChunkSectionReference(snapshot.x, y, snapshot.z);
            if (sectionRef == null || !sectionRef.isValid()) return false;
            if (!snapshot.sameAs(y, store.getComponent(sectionRef, BlockSection.getComponentType()),
                    store.getComponent(sectionRef, FluidSection.getComponentType()),
                    store.getComponent(sectionRef, EnvironmentSection.getComponentType()))) return false;
        }
        return true;
    }

    /**
     * World thread only. True when every watched packet in {@code mask} (and the column packet when asked) is still
     * the engine's current one. A cleared reference counts as changed; the caller then compares hashes.
     */
    static boolean watchUnchanged(ChunkStore chunkStore, Watch watch, long index, int mask, boolean column) {
        int x = DiskGeometry.x(index);
        int z = DiskGeometry.z(index);
        Store<ChunkStore> store = chunkStore.getStore();
        if (column) {
            Ref<ChunkStore> columnRef = chunkStore.getChunkReference(index);
            if (columnRef == null || !columnRef.isValid()) return false;
            BlockChunk blockChunk = store.getComponent(columnRef, BlockChunk.getComponentType());
            if (blockChunk == null || !Watch.same(watch.column, columnPacket(blockChunk))) return false;
        }
        for (int y = 0; y < DiskGeometry.SECTIONS; y++) {
            if ((mask & (1 << y)) == 0) continue;
            Ref<ChunkStore> sectionRef = chunkStore.getChunkSectionReference(x, y, z);
            if (sectionRef == null || !sectionRef.isValid()) return false;
            BlockSection blocks = store.getComponent(sectionRef, BlockSection.getComponentType());
            FluidSection fluid = store.getComponent(sectionRef, FluidSection.getComponentType());
            EnvironmentSection environment = store.getComponent(sectionRef, EnvironmentSection.getComponentType());
            if (blocks == null || !Watch.same(watch.blocks[y], blocks.getCachedChunkPacket(x, y, z))
                    || !Watch.same(watch.fluids[y], fluid == null ? null : fluid.getCachedPacket())
                    || !Watch.same(watch.environments[y], environment == null ? null : environment.getCachedPacket(x, y, z))) {
                return false;
            }
        }
        return true;
    }

    @SuppressWarnings("removal")
    private static Snapshot fromDetached(IChunkLoader loader, Holder<ChunkStore> holder, int x, int z, int mask) {
        WorldChunk worldChunk = holder.getComponent(WorldChunk.getComponentType());
        BlockChunk blockChunk = holder.getComponent(BlockChunk.getComponentType());
        ChunkColumn column = holder.getComponent(ChunkColumn.getComponentType());
        if (worldChunk == null || blockChunk == null || column == null || worldChunk.getIndex() != DiskGeometry.index(x, z)) {
            throw new IllegalStateException("Saved column is incomplete");
        }
        Holder<ChunkStore>[] sections = column.getSectionHolders();
        if (sections == null) {
            @SuppressWarnings("unchecked")
            Holder<ChunkStore>[] created = new Holder[column.getSections().length];
            sections = created;
        }
        if (sections.length < DiskGeometry.SECTIONS) throw new IllegalStateException("Saved column has too few sections");
        for (int i = 0; i < sections.length; i++) {
            if (sections[i] == null) sections[i] = ChunkStore.REGISTRY.newHolder();
            if (!sections[i].getArchetype().contains(ChunkSection.getComponentType())) {
                sections[i].putComponent(ChunkSection.getComponentType(), new ChunkSection(null, x, i, z, false));
            }
        }
        column.putSectionHolders(sections);
        EnvironmentChunk legacyEnvironment = holder.getComponent(EnvironmentChunk.getComponentType());
        if (legacyEnvironment != null) {
            // Same as ChunkSystems.MigrateEnvironmentChunk on a native load: older saves keep environments per column.
            for (int i = 0; i < sections.length; i++) {
                EnvironmentSection existing = sections[i].getComponent(EnvironmentSection.getComponentType());
                if (existing == null || !existing.wasDeserialized()) {
                    sections[i].putComponent(EnvironmentSection.getComponentType(), legacyEnvironment.sliceToSection(i));
                }
            }
        }
        blockChunk.loadFromHolder(holder);
        HeightmapSystems.MigrateHeightmap.migrate(holder, BlockChunk.getComponentType(),
                HeightmapColumn.getComponentType(), loader);
        HeightmapColumn heightmap = holder.getComponent(HeightmapColumn.getComponentType());
        if (heightmap == null) throw new IllegalStateException("Saved column has no heightmap");
        heightmap.load(x, z);
        blockChunk.setHeightmapColumn(heightmap);

        Snapshot snapshot = new Snapshot(x, z, mask, columnPacket(blockChunk));
        for (int y = 0; y < DiskGeometry.SECTIONS; y++) {
            if ((mask & (1 << y)) == 0) continue;
            Holder<ChunkStore> section = sections[y];
            BlockSection blocks = section.getComponent(BlockSection.getComponentType());
            FluidSection fluid = section.getComponent(FluidSection.getComponentType());
            if (fluid != null) fluid.preload(x, y, z);
            snapshot.section(y, blocks, fluid, section.getComponent(EnvironmentSection.getComponentType()));
            if (blocks != null && BlockChunk.SEND_LOCAL_LIGHTING_DATA && !blocks.isSolidAir()
                    && (!blocks.hasLocalLight() || blocks.getLocalChangeCounter() != blocks.getLocalLight().getChangeId())) {
                snapshot.unlit |= 1 << y;
            }
        }
        return snapshot;
    }

    @SuppressWarnings("unchecked")
    private static CompletableFuture<CachedPacket<?>> columnPacket(BlockChunk blockChunk) {
        try {
            return (CompletableFuture<CachedPacket<?>>) COLUMN_PACKET.invoke(blockChunk);
        } catch (RuntimeException | Error e) {
            throw e;
        } catch (Throwable t) {
            throw new IllegalStateException(t);
        }
    }

    private static MethodHandle columnPacketHandle() {
        try {
            var method = BlockChunk.class.getDeclaredMethod("getCachedColumnPacket");
            if (method.getReturnType() != CompletableFuture.class) return null;
            method.setAccessible(true);
            return MethodHandles.lookup().unreflect(method).asType(MethodType.methodType(CompletableFuture.class, BlockChunk.class));
        } catch (ReflectiveOperationException | RuntimeException e) {
            return null;
        }
    }

    /** Packet futures for one column; section futures are null where the engine sends nothing. */
    static final class Snapshot {
        final int x;
        final int z;
        final int mask;
        final CompletableFuture<CachedPacket<?>> column;
        private final CompletableFuture<?>[] blocks = new CompletableFuture<?>[DiskGeometry.SECTIONS];
        private final CompletableFuture<?>[] fluids = new CompletableFuture<?>[DiskGeometry.SECTIONS];
        private final CompletableFuture<?>[] environments = new CompletableFuture<?>[DiskGeometry.SECTIONS];
        final long[] sectionHash = new long[DiskGeometry.SECTIONS];
        /** Saved sections whose light is out of date; the engine would send them without light. */
        int unlit;
        long columnHash;

        Snapshot(int x, int z, int mask, CompletableFuture<CachedPacket<?>> column) {
            this.x = x;
            this.z = z;
            this.mask = mask;
            this.column = column;
        }

        void section(int y, BlockSection blocks, FluidSection fluid, EnvironmentSection environment) {
            this.blocks[y] = blocks == null ? null : blocks.getCachedChunkPacket(x, y, z);
            this.fluids[y] = fluid == null ? null : fluid.getCachedPacket();
            this.environments[y] = environment == null ? null : environment.getCachedPacket(x, y, z);
        }

        boolean sameAs(int y, BlockSection blocks, FluidSection fluid, EnvironmentSection environment) {
            return blocks != null && blocks.getCachedChunkPacket(x, y, z) == this.blocks[y]
                    && (fluid == null ? this.fluids[y] == null : fluid.getCachedPacket() == this.fluids[y])
                    && (environment == null ? this.environments[y] == null
                    : environment.getCachedPacket(x, y, z) == this.environments[y]);
        }

        CompletableFuture<Void> ready() {
            List<CompletableFuture<?>> all = new ArrayList<>(1 + 3 * DiskGeometry.SECTIONS);
            all.add(column);
            for (CompletableFuture<?>[] group : List.of(blocks, fluids, environments)) {
                for (CompletableFuture<?> future : group) if (future != null) all.add(future);
            }
            return CompletableFuture.allOf(all.toArray(CompletableFuture[]::new));
        }

        boolean isDone() {
            if (!column.isDone()) return false;
            for (CompletableFuture<?>[] group : List.of(blocks, fluids, environments)) {
                for (CompletableFuture<?> future : group) if (future != null && !future.isDone()) return false;
            }
            return true;
        }

        /** Any thread, once {@link #ready()} completed. Hashes the packet bytes so later copies can be compared. */
        void hash() {
            columnHash = hash(columnPacket());
            for (int y = 0; y < DiskGeometry.SECTIONS; y++) {
                if ((mask & (1 << y)) == 0) continue;
                List<ToClientPacket> packets = sectionPackets(y);
                long h = 0;
                if (packets != null) for (ToClientPacket packet : packets) h = h * 0x9E3779B97F4A7C15L + hash(packet);
                sectionHash[y] = h;
            }
        }

        private static long hash(ToClientPacket packet) {
            if (packet == null) return 0;
            byte[] bytes = new byte[packet.computeSize()];
            packet.serialize(MemorySegment.ofArray(bytes), 0);
            CRC32C crc = new CRC32C();
            crc.update(bytes);
            return (crc.getValue() << 32) ^ ((long) packet.getId() << 24) ^ bytes.length;
        }

        /** Records the engine packets behind sections in {@code mask} (and the column) without keeping them alive. */
        void watchInto(Watch watch, int mask, boolean column) {
            if (column) watch.column = new WeakReference<>(this.column);
            for (int y = 0; y < DiskGeometry.SECTIONS; y++) {
                if ((mask & (1 << y)) == 0) continue;
                watch.blocks[y] = Watch.ref(blocks[y]);
                watch.fluids[y] = Watch.ref(fluids[y]);
                watch.environments[y] = Watch.ref(environments[y]);
            }
        }

        /** Completed column packet, or null if it failed. */
        ToClientPacket columnPacket() {
            return joined(column);
        }

        /** Completed packets of section {@code y} in native order, or null if any failed. */
        List<ToClientPacket> sectionPackets(int y) {
            if (blocks[y] == null) return null;
            ToClientPacket block = joined(blocks[y]);
            if (block == null) return null;
            ArrayList<ToClientPacket> packets = new ArrayList<>(3);
            packets.add(block);
            if (fluids[y] != null) {
                ToClientPacket fluid = joined(fluids[y]);
                if (fluid == null) return null;
                packets.add(fluid);
            }
            if (environments[y] != null) {
                ToClientPacket environment = joined(environments[y]);
                if (environment == null) return null;
                packets.add(environment);
            }
            return packets;
        }

        private static ToClientPacket joined(CompletableFuture<?> future) {
            if (future == null || !future.isDone() || future.isCompletedExceptionally()) return null;
            Object value = future.getNow(null);
            return value instanceof ToClientPacket packet ? packet : null;
        }

        @Override
        public String toString() {
            return "Snapshot[" + x + "," + z + " mask=" + Integer.toBinaryString(mask) + " blocks=" + Arrays.stream(blocks).filter(f -> f != null).count() + "]";
        }
    }

    /** Weak references to the engine packets a client copy came from; used only to notice that they changed. */
    static final class Watch {
        private static final WeakReference<Object> NONE = new WeakReference<>(null);
        WeakReference<?> column;
        final WeakReference<?>[] blocks = new WeakReference<?>[DiskGeometry.SECTIONS];
        final WeakReference<?>[] fluids = new WeakReference<?>[DiskGeometry.SECTIONS];
        final WeakReference<?>[] environments = new WeakReference<?>[DiskGeometry.SECTIONS];

        static WeakReference<?> ref(Object value) {
            return value == null ? NONE : new WeakReference<>(value);
        }

        static boolean same(WeakReference<?> ref, Object current) {
            if (ref == null) return false;
            if (ref == NONE) return current == null;
            return current != null && ref.get() == current;
        }
    }
}
