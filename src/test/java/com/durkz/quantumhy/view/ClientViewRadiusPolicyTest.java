package com.durkz.quantumhy.view;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ClientViewRadiusPolicyTest {

    @Test
    void neverRaisesBaseAbovePlayerCeiling() {
        assertEquals(4, ClientViewRadiusController.effectiveChunkBase(4, 0, 6, 32));
        assertEquals(4, ClientViewRadiusController.effectiveChunkBase(4, 12, 6, 32));
        assertEquals(8, ClientViewRadiusController.effectiveChunkBase(12, 8, 6, 32));
    }

    @Test
    void densityCacheExpiresForStationaryPlayer() {
        long sampled = 1_000_000_000L;
        assertTrue(ClientViewRadiusController.densityCacheFresh(
                sampled + 14_999_999_999L, sampled, 15_000_000_000L));
        assertFalse(ClientViewRadiusController.densityCacheFresh(
                sampled + 15_000_000_000L, sampled, 15_000_000_000L));
    }

    @Test
    void minimumDeltaSuppressesOnlySmallShrinks() {
        assertFalse(ClientViewRadiusController.shouldApplyChunkTarget(8, 7, 7, 2));
        assertTrue(ClientViewRadiusController.shouldApplyChunkTarget(8, 6, 7, 2));
        assertTrue(ClientViewRadiusController.shouldApplyChunkTarget(7, 8, 8, 2));
    }
}
