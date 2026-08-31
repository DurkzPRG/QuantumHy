package com.durkz.quantumhy.view;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StreamCatchUpPolicyTest {

    private static final int BACKLOG = 80;
    private static final int HOLD_MS = 1500;
    private static final int CRUISE_S = 128;
    private static final int CRUISE_T = 8;
    private static final int CATCH_S = 256;
    private static final int CATCH_T = 12;
    private static final int LOCAL_S = 2560;
    private static final int LOCAL_T = 40;

    @Test
    void flightChebyshevTwoEntersCatchUp() {
        assertEquals(2, StreamCatchUpPolicy.chebyshev(true, 0, 0, 2, 0));
        StreamCatchUpPolicy.Outcome out = next(
                false, 2, 10, false, 0, 0, 0,
                StreamCatchUpPolicy.Tier.CRUISE, 0L, 0L, LOCAL_S, LOCAL_T);
        assertEquals(StreamCatchUpPolicy.Tier.CATCH_UP, out.tier());
        assertEquals(256, out.perSecond());
        assertEquals(12, out.perTick());
    }

    @Test
    void singleChunkStepDoesNotEnter() {
        assertEquals(1, StreamCatchUpPolicy.chebyshev(true, 4, 4, 5, 4));
        assertFalse(StreamCatchUpPolicy.wantsCatchUp(true, false, 1, 10, 0, BACKLOG));
        StreamCatchUpPolicy.Outcome out = next(
                false, 1, 10, true, 10, 0, 0,
                StreamCatchUpPolicy.Tier.CRUISE, 0L, 0L, LOCAL_S, LOCAL_T);
        assertEquals(StreamCatchUpPolicy.Tier.CRUISE, out.tier());
        assertEquals(4, out.moveScore());
        assertEquals(128, out.perSecond());
        assertEquals(8, out.perTick());
    }

    @Test
    void flySprintPaceEntersOnSecondChunkCrossing() {
        StreamCatchUpPolicy.Outcome t0 = next(
                false, 1, 10, true, 10, 0, 0,
                StreamCatchUpPolicy.Tier.CRUISE, 0L, 0L, LOCAL_S, LOCAL_T);
        assertEquals(StreamCatchUpPolicy.Tier.CRUISE, t0.tier());
        assertEquals(4, t0.moveScore());

        StreamCatchUpPolicy.Outcome t1 = next(
                false, 0, 10, true, 10, 0, t0.moveScore(),
                StreamCatchUpPolicy.Tier.CRUISE, 250L, 0L, LOCAL_S, LOCAL_T);
        StreamCatchUpPolicy.Outcome t2 = next(
                false, 0, 10, true, 10, 0, t1.moveScore(),
                StreamCatchUpPolicy.Tier.CRUISE, 500L, 0L, LOCAL_S, LOCAL_T);
        assertEquals(StreamCatchUpPolicy.Tier.CRUISE, t2.tier());

        StreamCatchUpPolicy.Outcome t3 = next(
                false, 1, 10, true, 10, 0, t2.moveScore(),
                StreamCatchUpPolicy.Tier.CRUISE, 750L, 0L, LOCAL_S, LOCAL_T);
        assertEquals(StreamCatchUpPolicy.Tier.CATCH_UP, t3.tier());
        assertTrue(t3.moveScore() >= StreamCatchUpPolicy.MOVE_SCORE_ENTER);
        assertEquals(256, t3.perSecond());
    }

    @Test
    void twoConsecutiveBacklogIncreasesEnterCatchUp() {
        int growing = StreamCatchUpPolicy.nextGrowingCount(true, 10, 20, 0);
        assertEquals(1, growing);
        assertFalse(StreamCatchUpPolicy.wantsCatchUp(true, false, 0, 20, growing, BACKLOG));

        growing = StreamCatchUpPolicy.nextGrowingCount(true, 20, 30, 1);
        assertEquals(2, growing);
        assertTrue(StreamCatchUpPolicy.wantsCatchUp(true, false, 0, 30, growing, BACKLOG));

        StreamCatchUpPolicy.Outcome out = next(
                false, 0, 30, true, 20, 1, 0,
                StreamCatchUpPolicy.Tier.CRUISE, 0L, 0L, LOCAL_S, LOCAL_T);
        assertEquals(StreamCatchUpPolicy.Tier.CATCH_UP, out.tier());
        assertEquals(2, out.consecutiveGrowing());
    }

    @Test
    void highBacklogEntersCatchUp() {
        assertTrue(StreamCatchUpPolicy.wantsCatchUp(true, false, 0, 80, 0, BACKLOG));
        StreamCatchUpPolicy.Outcome out = next(
                false, 0, 80, true, 80, 0, 0,
                StreamCatchUpPolicy.Tier.CRUISE, 0L, 0L, LOCAL_S, LOCAL_T);
        assertEquals(StreamCatchUpPolicy.Tier.CATCH_UP, out.tier());
    }

    @Test
    void exitHoldsThenDropsOneStep() {
        StreamCatchUpPolicy.Outcome startHold = next(
                false, 0, 10, true, 10, 0, 0,
                StreamCatchUpPolicy.Tier.CATCH_UP, 1000L, 0L, LOCAL_S, LOCAL_T);
        assertEquals(StreamCatchUpPolicy.Tier.CATCH_UP, startHold.tier());
        assertEquals(2500L, startHold.holdUntilMs());

        StreamCatchUpPolicy.Outcome stillHeld = next(
                false, 0, 10, true, 10, 0, 0,
                StreamCatchUpPolicy.Tier.CATCH_UP, 2000L, 2500L, LOCAL_S, LOCAL_T);
        assertEquals(StreamCatchUpPolicy.Tier.CATCH_UP, stillHeld.tier());

        StreamCatchUpPolicy.Outcome dropped = next(
                false, 0, 10, true, 10, 0, 0,
                StreamCatchUpPolicy.Tier.CATCH_UP, 2500L, 2500L, LOCAL_S, LOCAL_T);
        assertEquals(StreamCatchUpPolicy.Tier.CRUISE, dropped.tier());
        assertEquals(0L, dropped.holdUntilMs());
        assertEquals(128, dropped.perSecond());
    }

    @Test
    void flightDuringHoldCancelsHold() {
        StreamCatchUpPolicy.Outcome out = next(
                false, 2, 10, true, 10, 0, 0,
                StreamCatchUpPolicy.Tier.CATCH_UP, 2000L, 2500L, LOCAL_S, LOCAL_T);
        assertEquals(StreamCatchUpPolicy.Tier.CATCH_UP, out.tier());
        assertEquals(0L, out.holdUntilMs());
    }

    @Test
    void clampDoesNotExceedRemoteBaseline() {
        assertEquals(200, StreamCatchUpPolicy.clampRate(256, 200));
        assertEquals(256, StreamCatchUpPolicy.clampRate(256, 360));
        assertEquals(360, StreamCatchUpPolicy.clampRate(0, 360));
        StreamCatchUpPolicy.Outcome out = next(
                false, 2, 10, false, 0, 0, 0,
                StreamCatchUpPolicy.Tier.CRUISE, 0L, 0L, 200, 10);
        assertEquals(200, out.perSecond());
        assertEquals(10, out.perTick());
    }

    @Test
    void pressureBlocksCatchUpAndDropsImmediately() {
        assertFalse(StreamCatchUpPolicy.wantsCatchUp(true, true, 3, 90, 2, BACKLOG));
        StreamCatchUpPolicy.Outcome blocked = next(
                true, 3, 90, true, 80, 2, 0,
                StreamCatchUpPolicy.Tier.CRUISE, 0L, 0L, LOCAL_S, LOCAL_T);
        assertEquals(StreamCatchUpPolicy.Tier.CRUISE, blocked.tier());
        assertEquals(128, blocked.perSecond());

        StreamCatchUpPolicy.Outcome dropped = next(
                true, 3, 90, true, 80, 2, 0,
                StreamCatchUpPolicy.Tier.CATCH_UP, 1000L, 2500L, LOCAL_S, LOCAL_T);
        assertEquals(StreamCatchUpPolicy.Tier.CRUISE, dropped.tier());
        assertEquals(0L, dropped.holdUntilMs());
    }

    @Test
    void disabledCatchUpStaysOnCruise() {
        StreamCatchUpPolicy.Outcome out = StreamCatchUpPolicy.next(
                false, false, 3, 90, true, 80, 2, 0,
                StreamCatchUpPolicy.Tier.CATCH_UP, 0L, 0L, BACKLOG, HOLD_MS,
                CRUISE_S, CRUISE_T, CATCH_S, CATCH_T, LOCAL_S, LOCAL_T);
        assertEquals(StreamCatchUpPolicy.Tier.CRUISE, out.tier());
        assertEquals(128, out.perSecond());
    }

    private static StreamCatchUpPolicy.Outcome next(
            boolean pressured,
            int chebyshev,
            int loading,
            boolean hasLastLoading,
            int lastLoading,
            int prevGrowing,
            int prevMoveScore,
            StreamCatchUpPolicy.Tier current,
            long nowMs,
            long holdUntilMs,
            int baselineS,
            int baselineT
    ) {
        return StreamCatchUpPolicy.next(
                true, pressured, chebyshev, loading, hasLastLoading, lastLoading, prevGrowing, prevMoveScore,
                current, nowMs, holdUntilMs, BACKLOG, HOLD_MS,
                CRUISE_S, CRUISE_T, CATCH_S, CATCH_T, baselineS, baselineT);
    }
}
