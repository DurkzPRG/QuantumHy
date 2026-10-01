package com.durkz.quantumhy.runtime;

import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import java.util.function.Function;

/** Coalesces pending requests; transitions execute only through the supplied world queue. */
final class OptimizationRequests {
    private final ConcurrentHashMap<UUID, CompletableFuture<String>> pending = new ConcurrentHashMap<>();
    private boolean closed;

    synchronized CompletableFuture<String> submit(UUID playerId, boolean enabled,
            Consumer<Runnable> enqueue, Function<Boolean, String> apply) {
        if (closed) {
            return CompletableFuture.completedFuture("QuantumHy is stopping; optimization was not changed.");
        }
        CompletableFuture<String> reply = new CompletableFuture<>();
        CompletableFuture<String> old = pending.put(playerId, reply);
        if (old != null) {
            old.complete("Optimization request superseded by your newer command.");
        }
        try {
            enqueue.accept(() -> {
                if (!pending.remove(playerId, reply)) {
                    return;
                }
                try {
                    reply.complete(apply.apply(enabled));
                } catch (RuntimeException failed) {
                    reply.completeExceptionally(failed);
                }
            });
        } catch (RuntimeException failed) {
            pending.remove(playerId, reply);
            reply.completeExceptionally(failed);
        }
        return reply;
    }

    void forget(UUID playerId) {
        CompletableFuture<String> reply = pending.remove(playerId);
        if (reply != null) {
            reply.complete("Optimization request cancelled because the player session ended.");
        }
    }

    synchronized void close() {
        closed = true;
        pending.forEach((id, reply) -> reply.complete("QuantumHy stopped before applying your request."));
        pending.clear();
    }
}
