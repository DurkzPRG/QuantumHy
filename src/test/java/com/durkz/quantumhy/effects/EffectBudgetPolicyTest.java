package com.durkz.quantumhy.effects;

import org.junit.jupiter.api.Test;

import static com.durkz.quantumhy.effects.EffectBudgetPolicy.Verdict.ALLOW;
import static com.durkz.quantumhy.effects.EffectBudgetPolicy.Verdict.ALLOW_PROTECTED;
import static com.durkz.quantumhy.effects.EffectBudgetPolicy.Verdict.DROP_BUDGET;
import static com.durkz.quantumhy.effects.EffectBudgetPolicy.Verdict.DROP_DISTANCE;
import static org.junit.jupiter.api.Assertions.assertEquals;

class EffectBudgetPolicyTest {

    private static final double PROTECT = 16 * 16;
    private static final double SOFT = 48 * 48;

    @Test
    void closeEffectsAlwaysPassEvenWithAnEmptyBucket() {
        assertEquals(ALLOW_PROTECTED, EffectBudgetPolicy.decide(10 * 10, 0.0D, 120, PROTECT, SOFT, 0));
    }

    @Test
    void farEffectsGoFirstOnceHalfTheBudgetIsSpent() {
        assertEquals(ALLOW, EffectBudgetPolicy.decide(60 * 60, 100, 120, PROTECT, SOFT, 0));
        assertEquals(DROP_BUDGET, EffectBudgetPolicy.decide(60 * 60, 50, 120, PROTECT, SOFT, 0));
        assertEquals(ALLOW, EffectBudgetPolicy.decide(30 * 30, 50, 120, PROTECT, SOFT, 0));
        assertEquals(DROP_BUDGET, EffectBudgetPolicy.decide(30 * 30, 0.5D, 120, PROTECT, SOFT, 0));
    }

    @Test
    void hardCutOffOnlyWhenSet() {
        assertEquals(DROP_DISTANCE, EffectBudgetPolicy.decide(90 * 90, 120, 120, PROTECT, SOFT, 80 * 80));
        assertEquals(ALLOW, EffectBudgetPolicy.decide(90 * 90, 120, 120, PROTECT, SOFT, 0));
    }

    @Test
    void strainShrinksTheRateToTheFloor() {
        assertEquals(120.0D, EffectBudgetPolicy.rate(120, 0.0D), 1e-9);
        assertEquals(48.0D, EffectBudgetPolicy.rate(120, 1.0D), 1e-9);
        assertEquals(1.0D, EffectBudgetPolicy.rate(1, 1.0D), 1e-9);
    }

    @Test
    void refillIsProportionalAndCappedAtOneSecond() {
        assertEquals(60.0D, EffectBudgetPolicy.refill(0.0D, 500_000_000L, 120), 1e-9);
        assertEquals(120.0D, EffectBudgetPolicy.refill(100.0D, 5_000_000_000L, 120), 1e-9);
    }
}
