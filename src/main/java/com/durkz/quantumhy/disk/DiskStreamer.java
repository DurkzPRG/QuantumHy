package com.durkz.quantumhy.disk;

import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.logger.HytaleLogger;
import com.hypixel.hytale.math.util.MathUtil;
import com.hypixel.hytale.protocol.NetworkChannel;
import com.hypixel.hytale.protocol.ToClientPacket;
import com.hypixel.hytale.protocol.packets.world.UnloadChunks;
import com.hypixel.hytale.server.core.HytaleServer;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.modules.entity.player.ChunkTracker;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.ChunkStore;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.hypixel.hytale.server.core.universe.world.storage.GetChunkFlags;
import com.hypixel.hytale.server.core.universe.world.storage.IChunkLoader;
import it.unimi.dsi.fastutil.ints.IntArrayList;
import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.objects.ObjectIterator;
import java.util.ArrayDeque;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.WeakHashMap;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import javax.annotation.Nullable;

/**
 * Streams saved terrain beyond the native load radius. The native tracker keeps every column inside its radius;
 * this class owns only sections outside the native sphere and inside the player's visible sphere.
 *
 * Sessions are touched on their world thread only. Workers produce detached packet futures; admission,
 * freshness checks and client writes run on the world thread. A saved column is read only while it is not
 * resident and delivered only if it is still not resident within {@link #MAX_DISK_AGE_NANOS}, which is shorter
 * than the native keep-alive before an edited column can be saved and unloaded again. Resident columns are
 * served from their live components and resent when the engine's cached packets change.
 */
public final class DiskStreamer {
    private static final HytaleLogger LOGGER = HytaleLogger.forEnclosingClass();

    static final long MAX_DISK_AGE_NANOS = TimeUnit.MILLISECONDS.toNanos(2000);
    static final int MAX_DISK_JOBS_PER_WORLD = 8;
    static final int MAX_JOBS_PER_PLAYER = 6;
    static final int MAX_RECHECK_QUEUE = 16;
    static final int MAX_FALLBACKS_PER_WORLD = 2;
    static final int MAX_SECTIONS_PER_TICK = 64;
    static final int MAX_BYTES_PER_TICK = 1 << 20;
    static final int MAX_SCANS_PER_TICK = 128;
    static final int WATCH_INTERVAL_TICKS = 20;
    static final int RETRY_TICKS = 40;
    static final int FAILURE_RETRY_TICKS = 300;
    static final int MAX_FAILURES_PER_WORLD = 64;

    private final ConcurrentHashMap<UUID, Session> sessions = new ConcurrentHashMap<>();
    private final Map<World, WorldBudget> budgets = new WeakHashMap<>();
    private volatile boolean closed;

    final AtomicLong diskColumns = new AtomicLong();
    final AtomicLong residentColumns = new AtomicLong();
    final AtomicLong sectionsSent = new AtomicLong();
    final AtomicLong bytesSent = new AtomicLong();
    final AtomicLong staleDiscards = new AtomicLong();
    final AtomicLong changedResends = new AtomicLong();
    final AtomicLong fallbackLoads = new AtomicLong();
    final AtomicLong failures = new AtomicLong();
    final AtomicLong columnResets = new AtomicLong();
    final AtomicLong unlitFallbacks = new AtomicLong();

    /** True when DISK owns this player's native radius right now. */
    public boolean owns(@Nullable UUID playerId) {
        return playerId != null && sessions.containsKey(playerId);
    }

    /** Radius the player should see. Equals the live radius when DISK does not own the player. */
    public int visibleRadius(@Nullable UUID playerId, Player player) {
        Session session = playerId == null ? null : sessions.get(playerId);
        return session == null ? player.getClientViewRadius() : session.visible;
    }

    /** World thread. Sets what the player should see; the native tracker stays capped below it. */
    public void setVisibleRadius(@Nullable UUID playerId, Player player, int visible) {
        Session session = playerId == null ? null : sessions.get(playerId);
        if (session == null) {
            player.setClientViewRadius(visible);
            return;
        }
        session.visible = Math.max(0, visible);
        session.enforce(player);
    }

    /**
     * World thread, before the native tracker update. Opens, keeps or releases the session and re-applies the
     * native cap when something else wrote the live radius (the engine copies client ViewRadius packets into it).
     */
    void preTick(World world, PlayerRef playerRef, Player player, ChunkTracker tracker, boolean wanted,
                 int nativeConfigured, int simulation) {
        UUID playerId = playerRef.getUuid();
        if (playerId == null) return;
        Session session = sessions.get(playerId);
        if (session != null && (session.world != world || session.tracker != tracker || session.playerRef != playerRef)) {
            sessions.remove(playerId, session);
            session.discarded = true;
            session = null;
        }
        boolean enabled = wanted && !closed && budget(world).enabled() && DiskSnapshots.supports(world.getChunkStore());
        if (!enabled) {
            if (session != null) release(session, player);
            return;
        }
        if (session == null) {
            session = new Session(world, playerRef, tracker, player.getClientViewRadius());
            sessions.put(playerId, session);
        }
        int live = player.getClientViewRadius();
        if (live != session.appliedNative) session.visible = live;
        session.nativeConfigured = nativeConfigured;
        session.simulation = simulation;
        session.enforce(player);
    }

    /** World thread, after the native tracker update. Reconciles ownership, delivers and plans work. */
    void postTick(World world, PlayerRef playerRef, ChunkTracker tracker, double x, double y, double z) {
        UUID playerId = playerRef.getUuid();
        Session session = playerId == null ? null : sessions.get(playerId);
        if (session == null || session.discarded || session.world != world || session.tracker != tracker || closed) return;
        session.ticks++;
        session.move(MathUtil.floor(x) >> 5, MathUtil.floor(y) >> 5, MathUtil.floor(z) >> 5);
        ChunkStore chunkStore = world.getChunkStore();
        Unloads unloads = new Unloads();
        session.reconcile(chunkStore, unloads);
        unloads.flush(playerRef);
        var channel = playerRef.getPacketHandler().getChannel(NetworkChannel.Chunks);
        if (!tracker.isReadyForChunks() || channel == null || !channel.isWritable()) return;
        WorldBudget budget = budget(world);
        session.tickSections = 0;
        session.tickBytes = 0;
        session.deliver(chunkStore, budget);
        session.plan(chunkStore, budget);
        session.deliver(chunkStore, budget);
    }

    /** World thread. The optimization was turned off for this player: native radius comes back. */
    public void release(UUID playerId, @Nullable Player player) {
        Session session = playerId == null ? null : sessions.get(playerId);
        if (session != null) release(session, player);
    }

    private void release(Session session, @Nullable Player player) {
        sessions.remove(session.playerRef.getUuid(), session);
        session.discarded = true;
        if (player != null && player.getClientViewRadius() == session.appliedNative) {
            player.setClientViewRadius(session.visible);
        }
    }

    /** Any thread. Drops a session without touching the client (disconnect, world change). */
    public void forget(@Nullable UUID playerId) {
        if (playerId == null) return;
        Session session = sessions.remove(playerId);
        if (session != null) session.discarded = true;
    }

    /** World thread. Restores every session of this world. */
    public void releaseWorld(World world, Iterable<PlayerRef> players) {
        for (PlayerRef playerRef : players) {
            if (playerRef == null || playerRef.getUuid() == null) continue;
            Session session = sessions.get(playerRef.getUuid());
            if (session == null || session.world != world) continue;
            Ref<EntityStore> ref = playerRef.getReference();
            Player player = ref == null || !ref.isValid() ? null : ref.getStore().getComponent(ref, Player.getComponentType());
            release(session, player);
        }
    }

    /** After every world restored its players. Late jobs finish into discarded sessions and are dropped. */
    public void close() {
        closed = true;
        for (Session session : sessions.values()) session.discarded = true;
        sessions.clear();
    }

    public String summary() {
        return String.format(java.util.Locale.ROOT,
                "disk sessions=%d columns disk=%d resident=%d sections=%d bytes=%d stale=%d changed=%d fallback=%d unlit=%d failures=%d resets=%d",
                sessions.size(), diskColumns.get(), residentColumns.get(), sectionsSent.get(), bytesSent.get(),
                staleDiscards.get(), changedResends.get(), fallbackLoads.get(), unlitFallbacks.get(), failures.get(),
                columnResets.get());
    }

    private WorldBudget budget(World world) {
        synchronized (budgets) {
            return budgets.computeIfAbsent(world, ignored -> new WorldBudget());
        }
    }

    private static int serverMaxViewRadius() {
        try {
            return HytaleServer.get().getConfig().getMaxViewRadius();
        } catch (RuntimeException e) {
            return Integer.MAX_VALUE;
        }
    }

    private static final class WorldBudget {
        final AtomicInteger diskJobs = new AtomicInteger();
        final AtomicInteger fallbacks = new AtomicInteger();
        final AtomicInteger failures = new AtomicInteger();

        boolean enabled() {
            return failures.get() < MAX_FAILURES_PER_WORLD;
        }

        boolean tryAcquire(AtomicInteger counter, int limit) {
            while (true) {
                int current = counter.get();
                if (current >= limit) return false;
                if (counter.compareAndSet(current, current + 1)) return true;
            }
        }

        void fail(World world, Throwable cause) {
            if (failures.incrementAndGet() == MAX_FAILURES_PER_WORLD) {
                LOGGER.atWarning().withCause(cause).log(
                        "QuantumHy disk streaming disabled for world %s after repeated failures; native streaming restored.",
                        world.getName());
            }
        }
    }

    private static final class Column {
        int sent;
        int inFlight;
        /** Sent sections that came from the saved copy and were never compared with a resident one. */
        int diskSections;
        boolean metadata;
        boolean nativeColumn;
        long fallbackAt = Long.MIN_VALUE;
        boolean recheck;
        long retryAt;
        long columnHash;
        final long[] hash = new long[DiskGeometry.SECTIONS];
        final DiskSnapshots.Watch watch = new DiskSnapshots.Watch();
    }

    private static final class Job {
        final long index;
        final int mask;
        final boolean fromDisk;
        final boolean recheck;
        final long startNanos = System.nanoTime();
        volatile DiskSnapshots.Snapshot snapshot;
        volatile boolean missing;
        volatile Throwable failure;
        volatile boolean done;

        Job(long index, int mask, boolean fromDisk, boolean recheck) {
            this.index = index;
            this.mask = mask;
            this.fromDisk = fromDisk;
            this.recheck = recheck;
        }
    }

    private static final class Unloads {
        static final int SECTION_INTS = 3 * 1024;
        static final int COLUMN_INTS = 2 * 1024;
        final IntArrayList sections = new IntArrayList();
        final IntArrayList columns = new IntArrayList();

        void flush(PlayerRef playerRef) {
            for (int start = 0; start < sections.size(); start += SECTION_INTS) {
                int end = Math.min(sections.size(), start + SECTION_INTS);
                playerRef.getPacketHandler().writeNoCache(new UnloadChunks(sections.subList(start, end).toIntArray(), null));
            }
            for (int start = 0; start < columns.size(); start += COLUMN_INTS) {
                int end = Math.min(columns.size(), start + COLUMN_INTS);
                playerRef.getPacketHandler().writeNoCache(new UnloadChunks(null, columns.subList(start, end).toIntArray()));
            }
        }
    }

    private final class Session {
        final World world;
        final PlayerRef playerRef;
        final ChunkTracker tracker;
        final Long2ObjectOpenHashMap<Column> columns = new Long2ObjectOpenHashMap<>();
        final ArrayDeque<Job> jobs = new ArrayDeque<>();
        int planned;
        volatile boolean discarded;
        int visible;
        int appliedNative = -1;
        int nativeConfigured;
        int simulation;
        long ticks;
        int px = Integer.MIN_VALUE;
        int py;
        int pz;
        int planNative = -1;
        int planOuter = -1;
        long[] ring = new long[0];
        int cursor;
        long rescanAt;
        int tickSections;
        int tickBytes;

        Session(World world, PlayerRef playerRef, ChunkTracker tracker, int visible) {
            this.world = world;
            this.playerRef = playerRef;
            this.tracker = tracker;
            this.visible = visible;
        }

        void enforce(Player player) {
            int target = DiskGeometry.nativeRadius(nativeConfigured, simulation, visible);
            if (player.getClientViewRadius() != target) player.setClientViewRadius(target);
            appliedNative = target;
        }

        void move(int x, int y, int z) {
            int max = serverMaxViewRadius();
            int nativeRadius = Math.min(appliedNative, max);
            int outer = Math.min(visible, max);
            if (x != px || y != py || z != pz || nativeRadius != planNative || outer != planOuter) {
                px = x;
                py = y;
                pz = z;
                planNative = nativeRadius;
                planOuter = outer;
                ring = outer > nativeRadius ? DiskGeometry.ringColumns(px, pz, -1, outer) : new long[0];
                cursor = 0;
                rescanAt = 0;
            }
        }

        int distanceSq(long index) {
            return DiskGeometry.columnDistanceSq(px, pz, DiskGeometry.x(index), DiskGeometry.z(index));
        }

        /** Sections this session may own in a column, re-checked against the tracker's own sphere. */
        int band(long index) {
            int mask = DiskGeometry.bandMask(distanceSq(index), py, planNative, planOuter);
            if (mask == 0) return 0;
            int x = DiskGeometry.x(index);
            int z = DiskGeometry.z(index);
            for (int y = 0; y < DiskGeometry.SECTIONS; y++) {
                if ((mask & (1 << y)) != 0 && tracker.getSectionVisibility(x, y, z) != ChunkTracker.ChunkVisibility.NONE) {
                    mask &= ~(1 << y);
                }
            }
            return mask;
        }

        /** Native will load this column: some of its world sections lie in the tracker's sphere. */
        boolean nativeClaims(long index) {
            return DiskGeometry.sphereMask(distanceSq(index), py, planNative) != 0;
        }

        void reconcile(ChunkStore chunkStore, Unloads unloads) {
            boolean watch = ticks % WATCH_INTERVAL_TICKS == 0;
            ObjectIterator<Long2ObjectMap.Entry<Column>> it = columns.long2ObjectEntrySet().fastIterator();
            while (it.hasNext()) {
                Long2ObjectMap.Entry<Column> entry = it.next();
                long index = entry.getLongKey();
                Column column = entry.getValue();
                boolean nativeNow = tracker.isLoaded(index);
                if (column.nativeColumn && !nativeNow) {
                    if (column.sent != 0) columnResets.incrementAndGet();
                    column.sent = 0;
                    column.diskSections = 0;
                    column.metadata = false;
                    column.retryAt = 0;
                    rescanAt = 0;
                }
                column.nativeColumn = nativeNow;
                if (nativeNow) column.metadata = false;
                int band = band(index);
                int outside = column.sent & ~band;
                column.sent &= band;
                column.diskSections &= column.sent;
                boolean dropColumn = band == 0 && column.metadata && !nativeNow && !nativeClaims(index);
                int x = DiskGeometry.x(index);
                int z = DiskGeometry.z(index);
                if (dropColumn) {
                    unloads.columns.add(x);
                    unloads.columns.add(z);
                    column.metadata = false;
                } else {
                    for (int y = 0; y < DiskGeometry.SECTIONS; y++) {
                        if ((outside & (1 << y)) == 0 || tracker.getSectionVisibility(x, y, z) != ChunkTracker.ChunkVisibility.NONE) {
                            continue;
                        }
                        unloads.sections.add(x);
                        unloads.sections.add(y);
                        unloads.sections.add(z);
                    }
                }
                if (band == 0 && column.inFlight == 0 && column.sent == 0 && !column.metadata) {
                    it.remove();
                    continue;
                }
                if (watch && column.sent != 0) watchResident(chunkStore, index, column);
            }
        }

        /**
         * A copy on the client may be out of date once the column is resident: the engine rebuilt a packet, or the
         * sections came from the saved copy. Hashes decide; only sections whose bytes changed are sent again.
         */
        void watchResident(ChunkStore chunkStore, long index, Column column) {
            if (column.recheck || column.inFlight != 0 || jobs.size() >= MAX_RECHECK_QUEUE) return;
            if (chunkStore.getChunkReference(index) == null) return;
            boolean ownColumn = column.metadata && !column.nativeColumn;
            if (column.diskSections == 0
                    && DiskSnapshots.watchUnchanged(chunkStore, column.watch, index, column.sent, ownColumn)) return;
            DiskSnapshots.Snapshot snapshot;
            try {
                snapshot = DiskSnapshots.resident(chunkStore, index, column.sent);
            } catch (RuntimeException e) {
                failures.incrementAndGet();
                return;
            }
            if (snapshot == null) return;
            Job job = new Job(index, column.sent, false, true);
            job.snapshot = snapshot;
            column.recheck = true;
            jobs.add(job);
            completeAfterHash(job, snapshot);
        }

        private void completeAfterHash(Job job, DiskSnapshots.Snapshot snapshot) {
            snapshot.ready().whenComplete((ignored, failure) -> {
                Throwable error = failure;
                if (error == null) {
                    try {
                        snapshot.hash();
                    } catch (RuntimeException e) {
                        error = e;
                    }
                }
                job.failure = error;
                job.done = true;
            });
        }

        private void recheck(Job job, DiskSnapshots.Snapshot snapshot, Column column) {
            boolean ownColumn = column.metadata && !tracker.isLoaded(job.index);
            if (ownColumn && snapshot.columnHash != column.columnHash) {
                // Heightmap or tint changed: the column goes again from the start.
                changedResends.incrementAndGet();
                column.metadata = false;
                column.sent = 0;
                column.diskSections = 0;
                column.retryAt = 0;
                rescanAt = 0;
                return;
            }
            int have = job.mask & column.sent;
            var handler = playerRef.getPacketHandler();
            int bytes = 0;
            int resent = 0;
            for (int y = 0; y < DiskGeometry.SECTIONS; y++) {
                if ((have & (1 << y)) == 0 || snapshot.sectionHash[y] == column.hash[y]) continue;
                List<ToClientPacket> packets = snapshot.sectionPackets(y);
                if (packets == null) continue;
                for (ToClientPacket packet : packets) {
                    handler.write(packet);
                    bytes += packet.computeSize();
                }
                column.hash[y] = snapshot.sectionHash[y];
                resent++;
            }
            snapshot.watchInto(column.watch, have, ownColumn);
            column.diskSections &= ~have;
            if (resent > 0) changedResends.incrementAndGet();
            tickSections += resent;
            tickBytes += bytes;
            sectionsSent.addAndGet(resent);
            bytesSent.addAndGet(bytes);
        }

        void deliver(ChunkStore chunkStore, WorldBudget budget) {
            int limit = Math.max(16, Math.min(MAX_SECTIONS_PER_TICK, tracker.getMaxSectionsPerTick()));
            Iterator<Job> it = jobs.iterator();
            while (it.hasNext() && tickSections < limit && tickBytes < MAX_BYTES_PER_TICK) {
                Job job = it.next();
                if (!job.done) continue;
                it.remove();
                if (!job.recheck) planned--;
                Column column = columns.get(job.index);
                if (column == null) continue;
                if (job.recheck) column.recheck = false;
                else column.inFlight &= ~job.mask;
                if (job.failure != null) {
                    failures.incrementAndGet();
                    budget.fail(world, job.failure);
                    column.retryAt = ticks + FAILURE_RETRY_TICKS;
                    continue;
                }
                if (job.missing && !job.recheck) {
                    fallback(chunkStore, budget, job.index, column);
                    continue;
                }
                DiskSnapshots.Snapshot snapshot = job.snapshot;
                if (snapshot == null) continue;
                boolean fresh = job.fromDisk
                        ? chunkStore.getChunkReference(job.index) == null && System.nanoTime() - job.startNanos <= MAX_DISK_AGE_NANOS
                        : DiskSnapshots.columnUnchanged(chunkStore, snapshot)
                        && DiskSnapshots.sectionsUnchanged(chunkStore, snapshot, snapshot.mask);
                if (!fresh) {
                    staleDiscards.incrementAndGet();
                    column.retryAt = 0;
                    rescanAt = 0;
                    continue;
                }
                if (job.recheck) recheck(job, snapshot, column);
                else if (!write(job, snapshot, column, limit)) column.retryAt = ticks + RETRY_TICKS;
            }
        }

        private boolean write(Job job, DiskSnapshots.Snapshot snapshot, Column column, int limit) {
            long index = job.index;
            boolean nativeNow = tracker.isLoaded(index);
            if (!nativeNow && nativeClaims(index)) return false;
            int wanted = job.mask & band(index) & ~column.sent;
            if (job.fromDisk && (wanted & snapshot.unlit) != 0) {
                // The saved light is stale; a native load relights the column and the resident path serves it.
                unlitFallbacks.incrementAndGet();
                fallback(world.getChunkStore(), budget(world), index, column);
                wanted &= ~snapshot.unlit;
            }
            if (wanted == 0) return true;
            var handler = playerRef.getPacketHandler();
            int bytes = 0;
            if (!nativeNow && !column.metadata) {
                ToClientPacket packet = snapshot.columnPacket();
                if (packet == null) return false;
                handler.write(packet);
                bytes += packet.computeSize();
                column.metadata = true;
                column.columnHash = snapshot.columnHash;
            }
            int sections = 0;
            int written = 0;
            for (int y = 0; y < DiskGeometry.SECTIONS; y++) {
                if ((wanted & (1 << y)) == 0) continue;
                if (tickSections + sections >= limit || tickBytes + bytes >= MAX_BYTES_PER_TICK) break;
                List<ToClientPacket> packets = snapshot.sectionPackets(y);
                if (packets == null) continue;
                for (ToClientPacket packet : packets) {
                    handler.write(packet);
                    bytes += packet.computeSize();
                }
                column.sent |= 1 << y;
                column.hash[y] = snapshot.sectionHash[y];
                written |= 1 << y;
                sections++;
            }
            if (job.fromDisk) column.diskSections |= written;
            else snapshot.watchInto(column.watch, written, column.metadata && !nativeNow);
            if (sections > 0 || bytes > 0) (job.fromDisk ? diskColumns : residentColumns).incrementAndGet();
            tickSections += sections;
            tickBytes += bytes;
            sectionsSent.addAndGet(sections);
            bytesSent.addAndGet(bytes);
            if ((wanted & ~column.sent) != 0) {
                column.retryAt = 0;
                rescanAt = 0;
            }
            return true;
        }

        private void fallback(ChunkStore chunkStore, WorldBudget budget, long index, Column column) {
            if (ticks - column.fallbackAt < FAILURE_RETRY_TICKS) {
                column.retryAt = column.fallbackAt + FAILURE_RETRY_TICKS;
                return;
            }
            if (!budget.tryAcquire(budget.fallbacks, MAX_FALLBACKS_PER_WORLD)) {
                column.retryAt = ticks + RETRY_TICKS;
                return;
            }
            column.fallbackAt = ticks;
            column.retryAt = ticks + RETRY_TICKS;
            fallbackLoads.incrementAndGet();
            chunkStore.getChunkReferenceAsync(index, GetChunkFlags.NO_SET_TICKING_SYNC)
                    .whenComplete((ref, failure) -> {
                        budget.fallbacks.decrementAndGet();
                        if (failure != null) budget.fail(world, failure);
                    });
        }

        void plan(ChunkStore chunkStore, WorldBudget budget) {
            if (ring.length == 0 || ticks < rescanAt || planned >= MAX_JOBS_PER_PLAYER) return;
            IChunkLoader loader = chunkStore.getLoader();
            for (int scans = 0; scans < MAX_SCANS_PER_TICK && planned < MAX_JOBS_PER_PLAYER; scans++) {
                if (cursor >= ring.length) {
                    cursor = 0;
                    rescanAt = ticks + RETRY_TICKS;
                    return;
                }
                long index = ring[cursor++];
                int band = band(index);
                if (band == 0) continue;
                Column column = columns.get(index);
                if (column != null && ticks < column.retryAt) continue;
                boolean nativeNow = tracker.isLoaded(index);
                if (!nativeNow && nativeClaims(index)) continue;
                int needed = band & ~(column == null ? 0 : column.sent | column.inFlight);
                if (needed == 0) continue;
                if (column == null) {
                    column = new Column();
                    column.nativeColumn = nativeNow;
                    columns.put(index, column);
                }
                if (chunkStore.getChunkReference(index) != null) {
                    DiskSnapshots.Snapshot snapshot;
                    try {
                        snapshot = DiskSnapshots.resident(chunkStore, index, needed);
                    } catch (RuntimeException e) {
                        failures.incrementAndGet();
                        budget.fail(world, e);
                        column.retryAt = ticks + FAILURE_RETRY_TICKS;
                        continue;
                    }
                    if (snapshot == null) {
                        column.retryAt = ticks + RETRY_TICKS;
                        continue;
                    }
                    Job job = new Job(index, needed, false, false);
                    job.snapshot = snapshot;
                    column.inFlight |= needed;
                    jobs.add(job);
                    planned++;
                    completeAfterHash(job, snapshot);
                    continue;
                }
                if (!budget.tryAcquire(budget.diskJobs, MAX_DISK_JOBS_PER_WORLD)) {
                    cursor--;
                    return;
                }
                Job job = new Job(index, needed, true, false);
                column.inFlight |= needed;
                jobs.add(job);
                planned++;
                CompletableFuture<DiskSnapshots.Snapshot> future;
                try {
                    future = DiskSnapshots.persisted(loader, DiskGeometry.x(index), DiskGeometry.z(index), needed);
                } catch (RuntimeException e) {
                    future = CompletableFuture.failedFuture(e);
                }
                future.whenComplete((snapshot, failure) -> {
                    budget.diskJobs.decrementAndGet();
                    if (failure != null) job.failure = failure;
                    else if (snapshot == null) job.missing = true;
                    else {
                        try {
                            snapshot.hash();
                            job.snapshot = snapshot;
                        } catch (RuntimeException e) {
                            job.failure = e;
                        }
                    }
                    job.done = true;
                });
            }
        }
    }
}
