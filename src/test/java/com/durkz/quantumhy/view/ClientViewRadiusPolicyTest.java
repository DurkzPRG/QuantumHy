package com.durkz.quantumhy.view;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ClientViewRadiusPolicyTest {

    @Test
    void reloadInvalidatesDensityButPreservesRestorationAndRampState() {
        var state = new ClientViewRadiusController.PlayerState();
        var first = new com.durkz.quantumhy.config.QuantumHyConfig();
        state.settingsChanged(first);
        state.chunkCeiling = 16;
        state.entityCeiling = 128;
        state.lastAppliedFrac = 0.5;
        state.hasAppliedFrac = true;
        state.hasDensityCache = true;
        state.hasSmoothed = true;
        state.calmPasses = 5;
        state.settingsChanged(first);
        assertTrue(state.hasDensityCache);
        var second = new com.durkz.quantumhy.config.QuantumHyConfig();
        second.densityRingEdgeWeight = 0.7;
        state.settingsChanged(second);
        assertFalse(state.hasDensityCache);
        assertFalse(state.hasSmoothed);
        assertEquals(0, state.calmPasses);
        assertEquals(16, state.chunkCeiling);
        assertEquals(128, state.entityCeiling);
        assertTrue(state.hasAppliedFrac);
        assertEquals(0.5, state.lastAppliedFrac);
    }

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
