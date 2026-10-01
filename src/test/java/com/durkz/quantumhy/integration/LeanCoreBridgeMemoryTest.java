package com.durkz.quantumhy.integration;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class LeanCoreBridgeMemoryTest {

    @Test
    void onlyHeapPressureTiersAddAFloor() {
        assertEquals(0.5D, LeanCoreBridge.memoryShrinkFloor("CRITICAL"), 1e-9);
        assertEquals(0.25D, LeanCoreBridge.memoryShrinkFloor("TIGHT"), 1e-9);
        assertEquals(0.0D, LeanCoreBridge.memoryShrinkFloor("WATCH"), 1e-9);
        assertEquals(0.0D, LeanCoreBridge.memoryShrinkFloor("COMFORT"), 1e-9);
        assertEquals(0.0D, LeanCoreBridge.memoryShrinkFloor(""), 1e-9);
        assertEquals(0.0D, LeanCoreBridge.memoryShrinkFloor((String) null), 1e-9);
    }
}
