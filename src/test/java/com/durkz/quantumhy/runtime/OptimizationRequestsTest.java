package com.durkz.quantumhy.runtime;

import org.junit.jupiter.api.Test;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class OptimizationRequestsTest {
    @Test
    void queuedOffCannotRestoreOrEraseStateAfterNewerOn() {
        var requests = new OptimizationRequests();
        var queue = new ArrayDeque<Runnable>();
        var actions = new ArrayList<Boolean>();
        UUID player = UUID.randomUUID();
        var off = requests.submit(player, false, queue::add, mode -> {
            actions.add(mode);
            return "off restored";
        });
        var on = requests.submit(player, true, queue::add, mode -> {
            actions.add(mode);
            return "on checked";
        });
        assertTrue(actions.isEmpty());
        assertTrue(off.join().contains("superseded"));
        while (!queue.isEmpty()) queue.remove().run();
        assertEquals(List.of(true), actions);
        assertEquals("on checked", on.join());
    }

    @Test
    void repeatedAlternationAppliesOnlyLastPendingRequest() {
        var requests = new OptimizationRequests();
        var queue = new ArrayDeque<Runnable>();
        var actions = new ArrayList<Boolean>();
        UUID player = UUID.randomUUID();
        var replies = new ArrayList<java.util.concurrent.CompletableFuture<String>>();
        for (int i = 0; i < 100; i++) {
            replies.add(requests.submit(player, i % 2 == 0, queue::add, mode -> {
                actions.add(mode);
                return "applied";
            }));
        }
        while (!queue.isEmpty()) queue.remove().run();
        assertEquals(List.of(false), actions);
        assertTrue(replies.stream().allMatch(java.util.concurrent.CompletableFuture::isDone));
        assertEquals("applied", replies.getLast().join());
    }

    @Test
    void repeatedOnAfterCompletionStillRechecksWithoutSuppressingAction() {
        var requests = new OptimizationRequests();
        UUID player = UUID.randomUUID();
        AtomicInteger checks = new AtomicInteger();
        for (int i = 0; i < 20; i++) {
            assertEquals("checked", requests.submit(player, true, Runnable::run, mode -> {
                checks.incrementAndGet();
                return "checked";
            }).join());
        }
        assertEquals(20, checks.get());
    }

    @Test
    void playersHaveIndependentPendingTransitions() {
        var requests = new OptimizationRequests();
        var queue = new ArrayDeque<Runnable>();
        var actions = new ArrayList<Boolean>();
        var first = requests.submit(UUID.randomUUID(), true, queue::add, mode -> {
            actions.add(mode);
            return "first";
        });
        var second = requests.submit(UUID.randomUUID(), false, queue::add, mode -> {
            actions.add(mode);
            return "second";
        });
        while (!queue.isEmpty()) queue.remove().run();
        assertEquals(List.of(true, false), actions);
        assertEquals("first", first.join());
        assertEquals("second", second.join());
    }

    @Test
    void disconnectAndShutdownCompleteRepliesWithoutApplyingStaleWork() {
        var requests = new OptimizationRequests();
        var queue = new ArrayDeque<Runnable>();
        UUID player = UUID.randomUUID();
        var cancelled = requests.submit(player, true, queue::add, mode -> fail("Disconnected request applied"));
        requests.forget(player);
        assertTrue(cancelled.join().contains("cancelled"));
        var stopped = requests.submit(player, false, queue::add, mode -> fail("Stopped request applied"));
        requests.close();
        while (!queue.isEmpty()) queue.remove().run();
        assertTrue(stopped.join().contains("stopped"));
        assertTrue(requests.submit(player, true, queue::add, mode -> fail("Closed request applied"))
                .join().contains("stopping"));
        assertTrue(queue.isEmpty());
    }

    @Test
    void applicationAndQueueFailuresAreReportedAndAllowRetry() {
        var requests = new OptimizationRequests();
        UUID player = UUID.randomUUID();
        var failed = requests.submit(player, false, Runnable::run, mode -> {
            throw new IllegalStateException("restore failed");
        });
        assertTrue(failed.isCompletedExceptionally());
        var rejected = requests.submit(player, true, task -> {
            throw new IllegalStateException("world stopped");
        }, mode -> fail("Rejected queue ran"));
        assertTrue(rejected.isCompletedExceptionally());
        assertEquals("retried", requests.submit(player, false, Runnable::run, mode -> "retried").join());
    }
}
