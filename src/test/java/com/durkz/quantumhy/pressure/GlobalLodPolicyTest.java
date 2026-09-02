package com.durkz.quantumhy.pressure;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class GlobalLodPolicyTest {

    @Test
    void pressureDoesNotCreateGlobalOverrideFromDefault() {
        assertEquals(1.0D, GlobalLodPolicy.aggressiveness(1.0D, 1.15D, true), 1e-9);
    }

    @Test
    void pressureUsesMostConservativeExplicitAdminOverride() {
        assertEquals(1.725D, GlobalLodPolicy.aggressiveness(1.5D, 1.15D, true), 1e-9);
        assertEquals(1.5D, GlobalLodPolicy.aggressiveness(1.5D, 1.15D, false), 1e-9);
    }
}
