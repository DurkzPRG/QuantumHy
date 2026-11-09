package com.durkz.quantumhy.client;

import com.hypixel.hytale.logger.HytaleLogger;
import com.hypixel.hytale.protocol.ToClientPacket;
import com.hypixel.hytale.protocol.ParticleSystem;
import com.hypixel.hytale.protocol.UpdateType;
import com.hypixel.hytale.protocol.packets.assets.UpdateParticleSystems;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import java.util.Map;
import java.util.Collection;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import java.util.function.Supplier;

/** Optional cosmetic density tradeoff. Native assets and shared packet caches are never modified. */
public final class CosmeticSmokeProfile {
    private final boolean enabled;
    private final Supplier<CosmeticSmokeDescriptors> source;
    private final Consumer<String> log;
    private final Supplier<ParticleSystem> restoration;
    private final ConcurrentHashMap<UUID, Session> sessions = new ConcurrentHashMap<>();
    private boolean initialized;
    private CosmeticSmokeDescriptors descriptors;

    public CosmeticSmokeProfile(boolean enabled, HytaleLogger logger) {
        this(enabled, CosmeticSmokeDescriptors::load, CosmeticSmokeDescriptors::currentOriginal,
                message -> logger.atInfo().log("%s", message));
    }

    CosmeticSmokeProfile(boolean enabled, Supplier<CosmeticSmokeDescriptors> source,
                         Supplier<ParticleSystem> restoration, Consumer<String> log) {
        this.enabled = enabled;
        this.source = source;
        this.log = log;
        this.restoration = restoration;
    }

    public void update(PlayerRef player, boolean optedIn) {
        if (!enabled || player == null || !player.isValid() || player.getUuid() == null) return;
        var handler = player.getPacketHandler();
        if (handler != null && !(handler instanceof com.hypixel.hytale.server.core.io.handlers.SetupPacketHandler))
            update(player.getUuid(), handler.getChannel(), handler::writeNoCache, optedIn);
    }

    void update(UUID player, Object connection, Consumer<? super ToClientPacket> send, boolean optedIn) {
        if (!enabled || player == null || connection == null) return;
        CosmeticSmokeDescriptors data = descriptors();
        if (data == null) return;
        sessions.compute(player, (id, previous) -> {
            Session session = previous != null && previous.connection == connection ? previous : new Session(connection);
            try {
                if (!optedIn || session.failed) {
                    if (session.applied) {
                        send.accept(new UpdateParticleSystems(UpdateType.AddOrUpdate,
                                Map.of(CosmeticSmokeDescriptors.SOURCE, restoration.get()), null));
                        session.applied = false;
                    }
                } else if (!session.applied) {
                    if (source.get() == null) {
                        session.failed = true;
                        log.accept("Cosmetic smoke profile skipped: native assets changed. Native visuals retained.");
                        return session;
                    }
                    session.applied = true;
                    send.accept(new UpdateParticleSystems(UpdateType.AddOrUpdate,
                            Map.of(CosmeticSmokeDescriptors.SOURCE, data.replacement()), null));
                }
            } catch (RuntimeException failure) {
                if (!session.failed) log.accept("Cosmetic smoke profile delivery failed; restoring the native system for this session.");
                session.failed = true;
                if (session.applied) {
                    try {
                        send.accept(new UpdateParticleSystems(UpdateType.AddOrUpdate,
                                Map.of(CosmeticSmokeDescriptors.SOURCE, restoration.get()), null));
                        session.applied = false;
                    } catch (RuntimeException retryLater) {
                        // Keep restoration pending for the next pass or shutdown.
                    }
                }
            }
            return session;
        });
    }

    private synchronized CosmeticSmokeDescriptors descriptors() {
        if (!initialized) {
            initialized = true;
            try { descriptors = source.get(); }
            catch (RuntimeException | LinkageError unsupported) { descriptors = null; }
            log.accept(descriptors == null
                    ? "Cosmetic smoke profile unavailable: native or preloaded descriptors differ. Native visuals retained."
                    : "Cosmetic smoke profile available: selected smoke population 6 to 3, preloaded with native assets.");
        }
        return descriptors;
    }

    public void forget(UUID player) { sessions.remove(player); }
    public void retainPlayers(Collection<PlayerRef> online) {
        if (!enabled || sessions.isEmpty()) return;
        Set<UUID> present = new HashSet<>();
        for (PlayerRef player : online) {
            if (player != null && player.getUuid() != null) present.add(player.getUuid());
        }
        retain(present);
    }
    void retain(Set<UUID> online) { sessions.keySet().retainAll(online); }
    public void clear() { sessions.clear(); }

    private static final class Session {
        final Object connection;
        boolean applied;
        boolean failed;
        Session(Object connection) { this.connection = connection; }
    }
}
