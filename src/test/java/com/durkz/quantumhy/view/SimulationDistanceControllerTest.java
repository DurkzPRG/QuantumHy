package com.durkz.quantumhy.view;

import com.hypixel.hytale.math.iterator.SphereOffsets;
import com.hypixel.hytale.server.core.modules.entity.player.ChunkTracker;
import org.junit.jupiter.api.Test;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.assertEquals;

class SimulationDistanceControllerTest {
    private final SimulationDistanceController controller = new SimulationDistanceController();
    private final UUID player = UUID.randomUUID();

    private static ChunkTracker tracker(int hot) {
        var tracker = new ChunkTracker(() -> SphereOffsets.build(1));
        tracker.setMaxHotLoadedRadius(hot);
        return tracker;
    }

    @Test
    void optOutRestoresSimulationWithoutChangingTerrainOrStreamingBudgets() {
        var tracker = tracker(8);
        tracker.setMinLoadedRadius(12);
        tracker.setMaxSectionsPerSecond(1200);
        tracker.setMaxSectionsPerTick(32);
        controller.apply(player, tracker, 4, true, true);
        assertEquals(4, tracker.getMaxHotLoadedRadius());
        assertEquals(12, tracker.getMinLoadedRadius());
        assertEquals(1200, tracker.getMaxSectionsPerSecond());
        assertEquals(32, tracker.getMaxSectionsPerTick());
        controller.apply(player, tracker, 4, false, true);
        assertEquals(8, tracker.getMaxHotLoadedRadius());
        assertEquals(12, tracker.getMinLoadedRadius());
    }

    @Test
    void nativeCloneWaitsForEngineInitializationBeforeTakingANewLease() {
        var source = tracker(8);
        controller.apply(player, source, 4, true, true);
        var destination = (ChunkTracker) source.clone();
        assertEquals(0, destination.getMaxHotLoadedRadius());
        controller.onWorldJoin(player, destination);
        controller.apply(player, destination, 4, true, true);
        assertEquals(0, destination.getMaxHotLoadedRadius());
        destination.setMaxHotLoadedRadius(8);
        controller.apply(player, destination, 4, true, true);
        controller.restore(player, destination);
        assertEquals(8, destination.getMaxHotLoadedRadius());
    }

    @Test
    void aTransferThatKeepsTheTrackerRestoresItsNativeBaseline() {
        var tracker = tracker(8);
        controller.apply(player, tracker, 4, true, true);
        controller.onWorldJoin(player, tracker);
        assertEquals(8, tracker.getMaxHotLoadedRadius());
        controller.apply(player, tracker, 4, true, true);
        controller.restore(player, tracker);
        assertEquals(8, tracker.getMaxHotLoadedRadius());
    }

    @Test
    void anExternalWriterRetainsControlThroughLaterPassesAndShutdown() {
        var tracker = tracker(8);
        controller.apply(player, tracker, 4, true, true);
        tracker.setMaxHotLoadedRadius(3);
        controller.apply(player, tracker, 4, true, true);
        controller.apply(player, tracker, 2, true, true);
        controller.restore(player, tracker);
        assertEquals(3, tracker.getMaxHotLoadedRadius());
    }

    @Test
    void aSmallerNativeRadiusIsNeverExpandedToTheConfiguredLimit() {
        var tracker = tracker(3);
        controller.apply(player, tracker, 4, true, true);
        assertEquals(3, tracker.getMaxHotLoadedRadius());
        controller.apply(player, tracker, 2, true, true);
        assertEquals(2, tracker.getMaxHotLoadedRadius());
        controller.restore(player, tracker);
        assertEquals(3, tracker.getMaxHotLoadedRadius());
    }

    @Test
    void losingOwnershipRestoresTheLeaseAndStopsApplyingLimits() {
        var tracker = tracker(8);
        controller.apply(player, tracker, 4, true, true);
        controller.apply(player, tracker, 4, true, false);
        controller.apply(player, tracker, 4, true, false);
        assertEquals(8, tracker.getMaxHotLoadedRadius());
    }

    @Test
    void playersKeepSeparateNativeBaselines() {
        var other = UUID.randomUUID();
        var first = tracker(8);
        var second = tracker(6);
        controller.apply(player, first, 4, true, true);
        controller.apply(other, second, 4, true, true);
        controller.restore(player, first);
        assertEquals(8, first.getMaxHotLoadedRadius());
        assertEquals(4, second.getMaxHotLoadedRadius());
        controller.restore(other, second);
        assertEquals(6, second.getMaxHotLoadedRadius());
    }

    @Test
    void changedLimitsStayWithinTheOriginalNativeRadius() {
        var tracker = tracker(8);
        controller.apply(player, tracker, 4, true, true);
        controller.apply(player, tracker, 6, true, true);
        assertEquals(6, tracker.getMaxHotLoadedRadius());
        controller.apply(player, tracker, 32, true, true);
        assertEquals(8, tracker.getMaxHotLoadedRadius());
    }

    @Test
    void anUninitializedTrackerDoesNotCaptureAZeroBaseline() {
        var tracker = tracker(0);
        controller.apply(player, tracker, 4, true, true);
        assertEquals(0, tracker.getMaxHotLoadedRadius());
        tracker.setMaxHotLoadedRadius(8);
        controller.apply(player, tracker, 4, true, true);
        controller.restore(player, tracker);
        assertEquals(8, tracker.getMaxHotLoadedRadius());
    }

    @Test
    void destinationSpecificNativeSettingsArePreserved() {
        var source = tracker(8);
        controller.apply(player, source, 4, true, true);
        var destination = tracker(6);
        controller.onWorldJoin(player, destination);
        assertEquals(6, destination.getMaxHotLoadedRadius());
        controller.apply(player, destination, 4, true, true);
        controller.restore(player, destination);
        assertEquals(6, destination.getMaxHotLoadedRadius());
    }

    @Test
    void aDestinationWithTheSameRadiusAsOurCapKeepsItsOwnBaseline() {
        var source = tracker(8);
        controller.apply(player, source, 4, true, true);
        var destination = tracker(4);
        controller.onWorldJoin(player, destination);
        assertEquals(4, destination.getMaxHotLoadedRadius());
        controller.apply(player, destination, 2, true, true);
        controller.restore(player, destination);
        assertEquals(4, destination.getMaxHotLoadedRadius());
    }
}
