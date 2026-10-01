package com.durkz.quantumhy.view;

import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ClientRenderCapTest {

    private static final long SECOND = 1_000_000_000L;

    @Test
    void requestAtOrBelowRecentCapIsAnEcho() {
        long sentAt = 10 * SECOND;
        assertTrue(ClientRenderCap.isEcho(256, sentAt, 256, sentAt + SECOND / 2));
        assertTrue(ClientRenderCap.isEcho(256, sentAt, 192, sentAt + SECOND));
    }

    @Test
    void requestAboveCapOrAfterWindowIsReal() {
        long sentAt = 10 * SECOND;
        assertFalse(ClientRenderCap.isEcho(256, sentAt, 288, sentAt + SECOND / 2));
        assertFalse(ClientRenderCap.isEcho(256, sentAt, 192, sentAt + ClientRenderCap.ECHO_WINDOW_NANOS));
    }

    @Test
    void noCapSentMeansNoEcho() {
        assertFalse(ClientRenderCap.isEcho(0, 0L, 192, SECOND));
    }

    @Test
    void chunksRoundUpLikeTheEngine() {
        assertEquals(8, ClientRenderCap.toChunks(256));
        assertEquals(9, ClientRenderCap.toChunks(257));
        assertEquals(1, ClientRenderCap.toChunks(1));
    }

    @Test
    void realRequestIsRecordedAndOpensTheDriftWindow() {
        ClientRenderCap cap = new ClientRenderCap(true);
        UUID player = UUID.randomUUID();
        assertEquals(-1, cap.requestedChunks(player));
        assertFalse(cap.drifting(player));
        cap.onClientRequest(player, 12 * 32);
        assertEquals(12, cap.requestedChunks(player));
        assertTrue(cap.drifting(player));
        cap.forget(player);
        assertEquals(-1, cap.requestedChunks(player));
    }
}
