package com.durkz.quantumhy.pressure;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ItemMergeBoostTest {

    @Test
    void widensOnlyItemsThatMergeCloserThanTheBoost() {
        assertEquals(4.0F, ItemMergeBoostSystem.nextRadius(true, 2.0F, 4.0F));
        assertEquals(6.0F, ItemMergeBoostSystem.nextRadius(true, 6.0F, 4.0F));
    }

    @Test
    void releaseClearsOnlyOurOwnOverride() {
        assertEquals(-1.0F, ItemMergeBoostSystem.nextRadius(false, 4.0F, 4.0F));
        assertEquals(2.0F, ItemMergeBoostSystem.nextRadius(false, 2.0F, 4.0F));
        assertEquals(3.0F, ItemMergeBoostSystem.nextRadius(false, 3.0F, 4.0F));
    }
}
