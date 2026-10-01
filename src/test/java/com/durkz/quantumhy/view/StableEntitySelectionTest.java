package com.durkz.quantumhy.view;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.*;

class StableEntitySelectionTest {
    private final Object world = new Object();
    private final Object settings = new Object();

    @Test
    void nearBoundaryOscillationKeepsExistingEntityButCloserArrivalReplacesIt() {
        var selection = new StableEntitySelection<Object>();
        Object a = new Object();
        Object b = new Object();
        selection.begin(world, settings);
        selection.finish(List.of(a));
        for (int tick = 0; tick < 100; tick++) {
            selection.begin(world, settings);
            selection.prepareHeap(2, 1);
            selection.offer(a, tick % 2 == 0 ? 100 : 99);
            selection.offer(b, tick % 2 == 0 ? 99 : 100);
            selection.selectHeap();
            assertFalse(selection.dropForCap(a));
            assertTrue(selection.dropForCap(b));
            selection.finish(List.of(a));
            assertEquals(0, selection.entered() + selection.exited());
        }
        selection.begin(world, settings);
        selection.prepareHeap(2, 1);
        selection.offer(a, 100);
        selection.offer(b, 80);
        selection.selectHeap();
        assertTrue(selection.dropForCap(a));
        assertFalse(selection.dropForCap(b));
        selection.finish(List.of(b));
        assertEquals(1, selection.entered());
        assertEquals(1, selection.exited());
    }

    @Test
    void bothHeapBranchesMatchReferenceRankingAndRespectBudget() {
        Random random = new Random(71);
        for (int count : new int[]{1, 2, 10, 80, 500}) {
            for (int cap = 0; cap < count; cap++) {
                var selection = new StableEntitySelection<Object>();
                List<Object> refs = new ArrayList<>();
                for (int i = 0; i < count; i++) {
                    refs.add(new Object());
                }
                selection.begin(world, settings);
                selection.finish(refs.subList(0, count / 2));
                selection.begin(world, settings);
                selection.prepareHeap(count, cap);
                var distances = new java.util.IdentityHashMap<Object, Double>();
                for (Object ref : refs) {
                    double distance = random.nextDouble() * 10000;
                    distances.put(ref, selection.score(ref, distance));
                    selection.offer(ref, distance);
                }
                selection.selectHeap();
                List<Object> expected = refs.stream().sorted(Comparator.comparingDouble(distances::get))
                        .limit(cap).toList();
                for (Object ref : refs) {
                    assertEquals(!expected.contains(ref), selection.dropForCap(ref));
                }
            }
        }
    }

    @Test
    void verticalReentryRequiresTwoBlockMarginAndDropsOldReferences() {
        var selection = new StableEntitySelection<Object>();
        Object ref = new Object();
        selection.begin(world, settings);
        assertTrue(selection.excludeVertically(ref, 33, 32));
        selection.finish(List.of());
        selection.begin(world, settings);
        assertTrue(selection.excludeVertically(ref, -31, 32));
        selection.finish(List.of());
        selection.begin(world, settings);
        assertFalse(selection.excludeVertically(ref, 30, 32));
        selection.finish(List.of(ref));
        selection.begin(world, settings);
        assertFalse(selection.excludeVertically(ref, 32, 32));
        selection.finish(List.of(ref));
        selection.begin(world, settings);
        assertTrue(selection.excludeVertically(ref, 33, 32));
        selection.finish(List.of());
        selection.begin(world, settings);
        selection.finish(List.of());
        selection.begin(world, settings);
        assertFalse(selection.excludeVertically(ref, 31, 32));
    }

    @Test
    void narrowVerticalLimitAndDisabledCullingHaveDefinedReentry() {
        var selection = new StableEntitySelection<Object>();
        Object ref = new Object();
        selection.begin(world, settings);
        assertTrue(selection.excludeVertically(ref, 2, 1));
        selection.finish(List.of());
        selection.begin(world, settings);
        assertTrue(selection.excludeVertically(ref, 0.1, 1));
        assertFalse(selection.excludeVertically(ref, 0, 1));
        assertFalse(selection.excludeVertically(ref, 1000, 0));
    }

    @Test
    void onlyMountAndOneActiveTargetCanExceedBudgetAndProtectionExpires() {
        var selection = new StableEntitySelection<Object>();
        Object mount = new Object();
        Object target = new Object();
        Object other = new Object();
        selection.begin(world, settings);
        selection.protectMount(mount);
        selection.considerInteraction(other, 20);
        selection.considerInteraction(target, 10);
        selection.prepareHeap(1, 0);
        selection.offer(other, 1);
        selection.selectHeap();
        assertFalse(selection.dropForCap(mount));
        assertFalse(selection.dropForCap(target));
        assertTrue(selection.dropForCap(other));
        assertFalse(selection.excludeVertically(mount, 1000, 32));
        assertFalse(selection.excludeVertically(target, 1000, 32));
        selection.finish(List.of(mount, target));
        selection.begin(world, settings);
        assertFalse(selection.protectedEntity(mount));
        assertFalse(selection.protectedEntity(target));
        assertTrue(selection.excludeVertically(target, 1000, 32));
    }

    @Test
    void previousInteractionWinsEqualDistanceOnlyWhileStillActive() {
        var selection = new StableEntitySelection<Object>();
        Object a = new Object();
        Object b = new Object();
        selection.begin(world, settings);
        selection.considerInteraction(a, 10);
        selection.finish(List.of(a));
        selection.begin(world, settings);
        selection.considerInteraction(b, 10);
        selection.considerInteraction(a, 10);
        assertTrue(selection.protectedEntity(a));
        assertFalse(selection.protectedEntity(b));
        selection.finish(List.of(a));
        selection.begin(world, settings);
        selection.considerInteraction(b, 10);
        assertFalse(selection.protectedEntity(a));
        assertTrue(selection.protectedEntity(b));
    }

    @Test
    void worldAndConfigChangesResetSelectionButSameSnapshotPreservesIt() {
        var selection = new StableEntitySelection<Object>();
        Object ref = new Object();
        selection.begin(world, settings);
        selection.finish(List.of(ref));
        selection.begin(world, settings);
        assertEquals(81, selection.score(ref, 100));
        selection.finish(List.of(ref));
        selection.begin(new Object(), settings);
        assertEquals(100, selection.score(ref, 100));
        selection.finish(List.of(ref));
        selection.begin(world, new Object());
        assertEquals(100, selection.score(ref, 100));
        selection.finish(List.of());
        assertEquals(0, selection.exited());
    }

    @Test
    void absentEntityLosesItsPreferenceAndCountsAsAnExit() {
        var selection = new StableEntitySelection<Object>();
        Object ref = new Object();
        selection.begin(world, settings);
        selection.finish(List.of(ref));
        selection.begin(world, settings);
        selection.finish(List.of());
        assertEquals(1, selection.exited());
        selection.begin(world, settings);
        assertEquals(100, selection.score(ref, 100));
    }

    @Test
    void capDropsKeepNearestAndCountProtectedAgainstTheCap() {
        var selection = new StableEntitySelection<Object>();
        Object mount = new Object();
        Object near = new Object();
        Object mid = new Object();
        Object far = new Object();
        selection.begin(world, settings);
        selection.protectMount(mount);
        selection.stashCandidate(mount, 10_000);
        selection.stashCandidate(far, 900);
        selection.stashCandidate(near, 4);
        selection.stashCandidate(mid, 100);
        // cap 3 with one protected leaves room for 2 of the 3 eligible.
        var drops = selection.selectCapDrops(3);
        assertEquals(1, drops.size());
        assertTrue(drops.contains(far));
        assertFalse(drops.contains(mount));
        selection.finish(List.of(mount, near, mid));
    }

    @Test
    void capDropsEmptyWhenCandidatesFitAndClearedEachTick() {
        var selection = new StableEntitySelection<Object>();
        Object a = new Object();
        Object b = new Object();
        selection.begin(world, settings);
        selection.stashCandidate(a, 1);
        selection.stashCandidate(b, 2);
        assertTrue(selection.selectCapDrops(2).isEmpty());
        selection.finish(List.of(a, b));
        selection.begin(world, settings);
        assertTrue(selection.selectCapDrops(0).isEmpty());
    }
}
