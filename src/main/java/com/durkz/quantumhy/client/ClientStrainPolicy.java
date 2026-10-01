package com.durkz.quantumhy.client;

/**
 * Pure client-strain math. No Hytale types, so the scoring and hysteresis can be unit-tested.
 *
 * The client answers each ping three ways: {@code Raw} from the network thread, {@code Direct}
 * once the packet is handled, and {@code Tick} from its game loop. Both pongs answer the same ping,
 * so {@code Tick - Direct} is how long the client's main loop takes to get to it: a frame-time proxy
 * that network latency cancels out of.
 */
public final class ClientStrainPolicy {

    /** EMA weight for the 1s samples. */
    public static final double EMA_ALPHA = 0.3D;
    /** Strained once the EMA holds at or above this... */
    public static final double ENTER = 0.5D;
    /** ...for this many consecutive samples. */
    public static final int ENTER_SAMPLES = 3;
    /** Released once the EMA holds at or below this... */
    public static final double EXIT = 0.25D;
    /** ...for this many consecutive samples. */
    public static final int EXIT_SAMPLES = 10;
    /** Share of the strain applied to terrain; entities take the full value. */
    public static final double TERRAIN_SHARE = 0.5D;

    private ClientStrainPolicy() {
    }

    /** Main-loop lag in milliseconds from the two pong round trips (microseconds). */
    public static double frameLagMs(long tickRttMicros, long directRttMicros) {
        if (tickRttMicros <= 0L || directRttMicros <= 0L) {
            return 0.0D;
        }
        return Math.max(0L, tickRttMicros - directRttMicros) / 1000.0D;
    }

    /** One sample in {@code [0, 1]}: the worst of frame lag, client queue, and channel backpressure. */
    public static double score(double frameLagMs, int lagLowMs, int lagHighMs,
                               int queue, int queueLow, int queueHigh, boolean backpressure) {
        double lag = smoothstep(frameLagMs, lagLowMs, lagHighMs);
        double queued = queue < 0 ? 0.0D : smoothstep(queue, queueLow, queueHigh);
        double channel = backpressure ? 1.0D : 0.0D;
        return Math.max(lag, Math.max(queued, channel));
    }

    public static double ema(double previous, double sample, boolean hasPrevious) {
        return hasPrevious ? EMA_ALPHA * sample + (1.0D - EMA_ALPHA) * previous : sample;
    }

    /**
     * Next hysteresis counter. Positive counts samples toward entering, negative toward leaving;
     * the caller flips {@code strained} when {@link #flips} says so.
     */
    public static int nextCounter(boolean strained, double ema, int counter) {
        if (!strained) {
            return ema >= ENTER ? Math.max(0, counter) + 1 : 0;
        }
        return ema <= EXIT ? Math.min(0, counter) - 1 : 0;
    }

    public static boolean flips(boolean strained, int counter) {
        return strained ? -counter >= EXIT_SAMPLES : counter >= ENTER_SAMPLES;
    }

    /** Shrink fraction for the entity stream while strained. */
    public static double entityFraction(double strain) {
        return clamp01(strain);
    }

    /** Shrink fraction for terrain while strained; terrain stays the gentler lever. */
    public static double terrainFraction(double strain) {
        return clamp01(strain) * TERRAIN_SHARE;
    }

    static double smoothstep(double value, double low, double high) {
        if (value <= low) {
            return 0.0D;
        }
        if (value >= high) {
            return 1.0D;
        }
        double t = (value - low) / (high - low);
        return t * t * (3.0D - 2.0D * t);
    }

    private static double clamp01(double value) {
        return value < 0.0D ? 0.0D : Math.min(1.0D, value);
    }
}
