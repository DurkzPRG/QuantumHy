package com.durkz.quantumhy.client;

import com.hypixel.hytale.logger.HytaleLogger;
import com.hypixel.hytale.protocol.Packet;
import com.hypixel.hytale.protocol.ToServerPacket;
import com.hypixel.hytale.protocol.packets.connection.Pong;
import com.hypixel.hytale.protocol.packets.setup.ViewRadius;
import com.hypixel.hytale.server.core.io.handlers.GenericPacketHandler;
import com.hypixel.hytale.server.core.io.handlers.game.GamePacketHandler;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.function.IntFunction;

public final class ClientInboundBridge implements AutoCloseable {
    private final BiConsumer<PlayerRef, Packet> observer;
    private final HytaleLogger logger;
    private final Map<UUID, Lease> leases = new HashMap<>();
    private boolean closed;

    public ClientInboundBridge(BiConsumer<PlayerRef, Packet> observer, HytaleLogger logger) {
        this.observer = observer;
        this.logger = logger;
    }

    public static boolean usesQuiche(PlayerRef player) {
        return player != null && player.getPacketHandler() != null
                && player.getPacketHandler().getChannel() != null
                && player.getPacketHandler().getChannel().getClass().getName()
                .startsWith("com.hypixel.hytale.lib.quiche.");
    }

    public synchronized void attach(PlayerRef player) {
        if (closed || player == null || player.getUuid() == null || !usesQuiche(player)
                || !(player.getPacketHandler() instanceof GamePacketHandler handler)) return;
        UUID id = player.getUuid();
        Lease previous = leases.get(id);
        if (previous != null && previous.player == player && previous.handler == handler) return;
        Lease next = new Lease(player, handler);
        leases.put(id, next);
        if (previous != null) previous.restoreLater();
        try {
            handler.getChannel().execute(() -> {
                synchronized (ClientInboundBridge.this) {
                    if (closed || leases.get(id) != next) return;
                    try {
                        next.install();
                    } catch (ReflectiveOperationException | RuntimeException failure) {
                        next.restore();
                        leases.remove(id, next);
                        logger.atWarning().withCause(failure).log(
                                "Client feedback could not be observed on this connection; native handlers retained.");
                    }
                }
            });
        } catch (RuntimeException failure) {
            leases.remove(id, next);
            logger.atWarning().withCause(failure).log("Client feedback connection became unavailable.");
        }
    }

    public synchronized void forget(UUID player) {
        Lease lease = leases.remove(player);
        if (lease != null) lease.restoreLater();
    }

    @Override
    public synchronized void close() {
        if (closed) return;
        closed = true;
        for (Lease lease : leases.values()) lease.restoreLater();
        leases.clear();
    }

    private final class Lease {
        final PlayerRef player;
        final GamePacketHandler handler;
        final List<Slot<ToServerPacket>> slots = new ArrayList<>(2);
        final AtomicBoolean observerFailed = new AtomicBoolean();
        Field table;

        Lease(PlayerRef player, GamePacketHandler handler) {
            this.player = player;
            this.handler = handler;
        }

        @SuppressWarnings("unchecked")
        Consumer<ToServerPacket> read(int id) {
            try {
                return ((Consumer<ToServerPacket>[]) table.get(handler))[id];
            } catch (IllegalAccessException failure) {
                throw new IllegalStateException(failure);
            }
        }

        void install() throws ReflectiveOperationException {
            table = GenericPacketHandler.class.getDeclaredField("handlers");
            table.setAccessible(true);
            for (int id : new int[]{ViewRadius.PACKET_ID, Pong.PACKET_ID}) {
                Slot<ToServerPacket> slot = new Slot<>(id, this::read, handler::registerHandler,
                        packet -> observer.accept(player, packet), failure -> {
                            if (observerFailed.compareAndSet(false, true)) logger.atWarning().withCause(failure).log(
                                    "Client feedback observer failed; native packet processing continues.");
                        });
                slots.add(slot);
                slot.install();
            }
        }

        void restore() {
            for (Slot<ToServerPacket> slot : slots) {
                try {
                    slot.restore();
                } catch (RuntimeException failure) {
                    logger.atWarning().withCause(failure).log("Client feedback handler could not be restored.");
                }
            }
            slots.clear();
        }

        void restoreLater() {
            try {
                if (handler.getChannel().isActive()) handler.getChannel().execute(this::restore);
            } catch (RuntimeException failure) {
                logger.atWarning().withCause(failure).log("Client feedback connection closed before cleanup.");
            }
        }
    }

    static final class Slot<T> {
        private final int id;
        private final IntFunction<Consumer<T>> read;
        private final BiConsumer<Integer, Consumer<T>> write;
        private final Consumer<T> observer;
        private final Consumer<RuntimeException> onFailure;
        private Consumer<T> previous;
        private Consumer<T> wrapper;

        Slot(int id, IntFunction<Consumer<T>> read, BiConsumer<Integer, Consumer<T>> write,
             Consumer<T> observer, Consumer<RuntimeException> onFailure) {
            this.id = id;
            this.read = read;
            this.write = write;
            this.observer = observer;
            this.onFailure = onFailure;
        }

        void install() {
            if (wrapper != null) throw new IllegalStateException("Observer already installed");
            previous = read.apply(id);
            if (previous == null) throw new IllegalStateException("Native packet handler missing");
            wrapper = packet -> {
                try {
                    observer.accept(packet);
                } catch (RuntimeException failure) {
                    onFailure.accept(failure);
                } finally {
                    previous.accept(packet);
                }
            };
            write.accept(id, wrapper);
        }

        void restore() {
            if (wrapper != null && read.apply(id) == wrapper) write.accept(id, previous);
        }
    }
}
