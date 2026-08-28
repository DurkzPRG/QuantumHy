package com.durkz.quantumhy.update;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ModUpdateCheckerTest {

    @Test
    void newerSemanticVersionIsDetected() {
        assertTrue(ModUpdateChecker.isNewer("0.2.3", "0.2.2"));
        assertTrue(ModUpdateChecker.isNewer("1.0.0", "0.9.9"));
        assertTrue(ModUpdateChecker.isNewer("v0.2.3", "0.2.2"));
    }

    @Test
    void currentOrOlderVersionIsNotReported() {
        assertFalse(ModUpdateChecker.isNewer("0.2.2", "0.2.2"));
        assertFalse(ModUpdateChecker.isNewer("0.2.1", "0.2.2"));
        assertFalse(ModUpdateChecker.isNewer(null, "0.2.2"));
    }
}
