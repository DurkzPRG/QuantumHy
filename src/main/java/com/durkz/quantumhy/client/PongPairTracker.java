package com.durkz.quantumhy.client;

import javax.annotation.Nullable;

final class PongPairTracker {
    static final long MAX_SAMPLE_AGE_NANOS = 5_000_000_000L;
    private static final int SLOTS = 16;
    private final int[] ids = new int[SLOTS];
    private final byte[] received = new byte[SLOTS];
    private final long[] directTimes = new long[SLOTS];
    private final long[] tickTimes = new long[SLOTS];
    private final int[] queues = new int[SLOTS];
    private final long[] orders = new long[SLOTS];
    private int nextSlot;
    private long nextOrder;
    private long latestOrder;
    private long latestTickOrder;
    private long sequence;
    private long tickSequence;
    private Sample latest;
    private TickSample latestTick;

    record Sample(long sequence, int pingId, long capturedNanos, double loopLagMs, int queueSize) {
    }

    record TickSample(long sequence, long capturedNanos, int queueSize) {
    }

    synchronized void record(int id, boolean tick, int queueSize, long nowNanos) {
        int slot = -1;
        for (int i = 0; i < SLOTS; i++) {
            if (received[i] != 0 && ids[i] == id) {
                slot = i;
                break;
            }
        }
        if (slot < 0) {
            slot = nextSlot;
            nextSlot = (nextSlot + 1) % SLOTS;
            ids[slot] = id;
            received[slot] = 0;
            // Ping IDs are random; track the order in which each pair is first observed.
            orders[slot] = ++nextOrder;
        }
        int flag = tick ? 2 : 1;
        if ((received[slot] & flag) != 0) return;
        if (tick) {
            tickTimes[slot] = nowNanos;
            queues[slot] = queueSize;
            if (latestTick == null || orders[slot] > latestTickOrder) {
                latestTick = new TickSample(++tickSequence, nowNanos, queueSize);
                latestTickOrder = orders[slot];
            }
        } else {
            directTimes[slot] = nowNanos;
        }
        received[slot] |= (byte) flag;
        if (received[slot] != 3) return;
        received[slot] |= 4;
        if (latest == null || orders[slot] > latestOrder) {
            double lagMs = Math.max(0L, tickTimes[slot] - directTimes[slot]) / 1_000_000.0D;
            latest = new Sample(++sequence, id, tickTimes[slot], lagMs, queues[slot]);
            latestOrder = orders[slot];
        }
    }

    @Nullable
    synchronized Sample latest(long nowNanos) {
        if (latest == null) return null;
        long age = nowNanos - latest.capturedNanos();
        return age >= 0 && age < MAX_SAMPLE_AGE_NANOS ? latest : null;
    }

    @Nullable
    synchronized TickSample latestTick(long nowNanos) {
        if (latestTick == null) return null;
        long age = nowNanos - latestTick.capturedNanos();
        return age >= 0 && age < MAX_SAMPLE_AGE_NANOS ? latestTick : null;
    }
}
