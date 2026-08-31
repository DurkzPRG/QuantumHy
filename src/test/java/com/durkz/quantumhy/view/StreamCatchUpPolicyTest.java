package com.durkz.quantumhy.view;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StreamCatchUpPolicyTest {

    private static final int BACKLOG = 80;
    private static final int HOLD_MS = 1500;
    private static final int CRUISE_S = 128;
    private static final int CRUISE_T = 8;
    private static final int CATCH_S = 256;
    private static final int CATCH_T = 12;
    private static final int BASELINE_S = 2560;
    private static final int BASELINE_T = 40;
    private static final double ENTER = 45.0D;
    private static final double EXIT = 35.0D;

    @Test
    void motionEntersCatchUpButBacklogAloneDoesNot() {
        StreamCatchUpPolicy.Outcome motion = next(false, true, 20.0D, 20.0D,
                2, 10, 0, 0, StreamCatchUpPolicy.Tier.CRUISE, 0L, 0L);
        assertEquals(StreamCatchUpPolicy.Tier.CATCH_UP, motion.tier());

        StreamCatchUpPolicy.Outcome backlog = next(false, true, 20.0D, 20.0D,
                0, 200, 0, 0, StreamCatchUpPolicy.Tier.CRUISE, 0L, 0L);
        assertEquals(StreamCatchUpPolicy.Tier.CRUISE, backlog.tier());
    }

    @Test
    void repeatedChunkMotionEntersCatchUp() {
        StreamCatchUpPolicy.Outcome first = next(false, true, 20.0D, 20.0D,
                1, 10, 0, 0, StreamCatchUpPolicy.Tier.CRUISE, 0L, 0L);
        StreamCatchUpPolicy.Outcome second = next(false, true, 20.0D, 20.0D,
                1, 10, first.moveScore(), 0, first.tier(), 750L, 0L);
        assertEquals(StreamCatchUpPolicy.Tier.CATCH_UP, second.tier());
        assertTrue(second.moveScore() >= StreamCatchUpPolicy.MOVE_SCORE_ENTER);
    }

    @Test
    void lastTickAverageAndGovernorEnterProtectImmediately() {
        assertProtect(next(false, true, 20.0D, 46.0D,
                2, 10, 0, 0, StreamCatchUpPolicy.Tier.CATCH_UP, 0L, 1000L),
                StreamCatchUpPolicy.ProtectionCause.LAST_TICK);
        assertProtect(next(false, true, 46.0D, 20.0D,
                2, 10, 0, 0, StreamCatchUpPolicy.Tier.CATCH_UP, 0L, 1000L),
                StreamCatchUpPolicy.ProtectionCause.AVERAGE);
        assertProtect(next(true, true, 20.0D, 20.0D,
                2, 10, 0, 0, StreamCatchUpPolicy.Tier.CATCH_UP, 0L, 1000L),
                StreamCatchUpPolicy.ProtectionCause.GOVERNOR);
    }

    @Test
    void protectOverridesCatchUpHold() {
        StreamCatchUpPolicy.Outcome out = next(false, true, 20.0D, 60.0D,
                0, 10, 0, 0, StreamCatchUpPolicy.Tier.CATCH_UP, 1000L, 2500L);
        assertEquals(StreamCatchUpPolicy.Tier.PROTECT, out.tier());
        assertEquals(0L, out.holdUntilMs());
        assertEquals(96, out.perSecond());
        assertEquals(6, out.perTick());
    }

    @Test
    void protectNeedsFourCalmSamplesAndReturnsToCruiseFirst() {
        StreamCatchUpPolicy.Outcome out = next(false, true, 20.0D, 20.0D,
                3, 0, 0, 0, StreamCatchUpPolicy.Tier.PROTECT, 0L, 0L);
        for (int sample = 1; sample < StreamCatchUpPolicy.PROTECT_RECOVERY_SAMPLES; sample++) {
            assertEquals(StreamCatchUpPolicy.Tier.PROTECT, out.tier());
            assertEquals(sample, out.protectCalmSamples());
            out = next(false, true, 20.0D, 20.0D,
                    3, 0, out.moveScore(), out.protectCalmSamples(), out.tier(), sample * 250L, 0L);
        }
        assertEquals(StreamCatchUpPolicy.Tier.CRUISE, out.tier());
        assertEquals(0, out.protectCalmSamples());
        assertEquals(CRUISE_S, out.perSecond());
    }

    @Test
    void unavailableMetricStaysCruiseAndDoesNotEnterCatchUp() {
        StreamCatchUpPolicy.Outcome out = next(false, false, 0.0D, 0.0D,
                3, 100, 0, 0, StreamCatchUpPolicy.Tier.CRUISE, 0L, 0L);
        assertEquals(StreamCatchUpPolicy.Tier.CRUISE, out.tier());
        assertEquals(StreamCatchUpPolicy.ProtectionCause.METRIC_UNAVAILABLE, out.protectionCause());
    }

    @Test
    void disabledGovernorAllowsMotionCatchUpWithoutMetric() {
        StreamCatchUpPolicy.Outcome out = StreamCatchUpPolicy.next(
                true, false, true, false, 0.0D, 0.0D, ENTER, EXIT, 0.75D,
                3, 100, 0, 0, StreamCatchUpPolicy.Tier.CRUISE, 0L, 0L,
                BACKLOG, HOLD_MS, CRUISE_S, CRUISE_T, CATCH_S, CATCH_T,
                BASELINE_S, BASELINE_T);
        assertEquals(StreamCatchUpPolicy.Tier.CATCH_UP, out.tier());
    }

    @Test
    void zeroRatesRestoreConnectionBaselineAndRemoteBaselineIsNeverExceeded() {
        StreamCatchUpPolicy.Outcome defaults = StreamCatchUpPolicy.next(
                false, true, false, true, 20.0D, 20.0D, ENTER, EXIT, 0.75D,
                0, 0, 0, 0, StreamCatchUpPolicy.Tier.CRUISE, 0L, 0L,
                BACKLOG, HOLD_MS, 0, 0, CATCH_S, CATCH_T, BASELINE_S, BASELINE_T);
        assertEquals(BASELINE_S, defaults.perSecond());
        assertEquals(BASELINE_T, defaults.perTick());

        StreamCatchUpPolicy.Outcome remote = StreamCatchUpPolicy.next(
                true, false, false, false, 0.0D, 0.0D, ENTER, EXIT, 0.75D,
                2, 0, 0, 0, StreamCatchUpPolicy.Tier.CRUISE, 0L, 0L,
                BACKLOG, HOLD_MS, CRUISE_S, CRUISE_T, CATCH_S, CATCH_T, 200, 10);
        assertEquals(200, remote.perSecond());
        assertEquals(10, remote.perTick());
    }

    @Test
    void catchUpHoldStillDrainsBacklogBeforeCruise() {
        StreamCatchUpPolicy.Outcome busy = next(false, true, 20.0D, 20.0D,
                0, BACKLOG, 0, 0, StreamCatchUpPolicy.Tier.CATCH_UP, 1000L, 0L);
        assertEquals(StreamCatchUpPolicy.Tier.CATCH_UP, busy.tier());
        assertEquals(0L, busy.holdUntilMs());

        StreamCatchUpPolicy.Outcome calm = next(false, true, 20.0D, 20.0D,
                0, 10, 0, 0, StreamCatchUpPolicy.Tier.CATCH_UP, 1250L, 0L);
        assertEquals(2750L, calm.holdUntilMs());
    }

    private static void assertProtect(StreamCatchUpPolicy.Outcome outcome,
            StreamCatchUpPolicy.ProtectionCause cause) {
        assertEquals(StreamCatchUpPolicy.Tier.PROTECT, outcome.tier());
        assertEquals(cause, outcome.protectionCause());
    }

    private static StreamCatchUpPolicy.Outcome next(
            boolean governorPressured,
            boolean metricAvailable,
            double average,
            double last,
            int chebyshev,
            int loading,
            int moveScore,
            int calmSamples,
            StreamCatchUpPolicy.Tier tier,
            long nowMs,
            long holdUntilMs
    ) {
        return StreamCatchUpPolicy.next(
                true, true, governorPressured, metricAvailable, average, last,
                ENTER, EXIT, 0.75D, chebyshev, loading, moveScore, calmSamples,
                tier, nowMs, holdUntilMs, BACKLOG, HOLD_MS,
                CRUISE_S, CRUISE_T, CATCH_S, CATCH_T, BASELINE_S, BASELINE_T);
    }
}
