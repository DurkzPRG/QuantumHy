package com.durkz.quantumhy.client;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ClientStrainPolicyTest {

    @Test
    void frameLagIsTickMinusDirectAndNeverNegative() {
        assertEquals(30.0D, ClientStrainPolicy.frameLagMs(80_000L, 50_000L), 1e-9);
        assertEquals(0.0D, ClientStrainPolicy.frameLagMs(40_000L, 50_000L), 1e-9);
        assertEquals(0.0D, ClientStrainPolicy.frameLagMs(0L, 50_000L), 1e-9);
    }

    @Test
    void scoreTakesTheWorstSignal() {
        assertEquals(0.0D, ClientStrainPolicy.score(10, 25, 60, 10, 64, 512, false), 1e-9);
        assertEquals(1.0D, ClientStrainPolicy.score(70, 25, 60, 10, 64, 512, false), 1e-9);
        assertEquals(1.0D, ClientStrainPolicy.score(10, 25, 60, 600, 64, 512, false), 1e-9);
        assertEquals(1.0D, ClientStrainPolicy.score(10, 25, 60, 10, 64, 512, true), 1e-9);
        // Unknown queue (no Tick pong yet) does not count.
        assertEquals(0.0D, ClientStrainPolicy.score(10, 25, 60, -1, 64, 512, false), 1e-9);
    }

    @Test
    void hysteresisNeedsSustainedStrainToEnterAndLongerCalmToLeave() {
        boolean strained = false;
        int counter = 0;
        for (int i = 0; i < ClientStrainPolicy.ENTER_SAMPLES - 1; i++) {
            counter = ClientStrainPolicy.nextCounter(strained, 0.8D, counter);
            assertFalse(ClientStrainPolicy.flips(strained, counter));
        }
        counter = ClientStrainPolicy.nextCounter(strained, 0.8D, counter);
        assertTrue(ClientStrainPolicy.flips(strained, counter));
        strained = true;
        counter = 0;

        // A spike back above EXIT resets the calm count.
        for (int i = 0; i < ClientStrainPolicy.EXIT_SAMPLES - 1; i++) {
            counter = ClientStrainPolicy.nextCounter(strained, 0.1D, counter);
        }
        counter = ClientStrainPolicy.nextCounter(strained, 0.4D, counter);
        assertFalse(ClientStrainPolicy.flips(strained, counter));
        for (int i = 0; i < ClientStrainPolicy.EXIT_SAMPLES; i++) {
            counter = ClientStrainPolicy.nextCounter(strained, 0.1D, counter);
        }
        assertTrue(ClientStrainPolicy.flips(strained, counter));
    }

    @Test
    void terrainTakesHalfTheStrainEntitiesTakeAll() {
        assertEquals(0.8D, ClientStrainPolicy.entityFraction(0.8D), 1e-9);
        assertEquals(0.4D, ClientStrainPolicy.terrainFraction(0.8D), 1e-9);
        assertEquals(0.5D, ClientStrainPolicy.terrainFraction(3.0D), 1e-9);
    }
}
