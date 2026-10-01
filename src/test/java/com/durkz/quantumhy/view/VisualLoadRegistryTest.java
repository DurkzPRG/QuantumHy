package com.durkz.quantumhy.view;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class VisualLoadRegistryTest {
    @AfterEach
    void clear() {
        VisualLoadRegistry.clear();
    }

    @Test
    void tracksSwapsEvenWhenCandidateCountDoesNotChangeWithoutChangingPressureSignal() {
        UUID id = UUID.randomUUID();
        VisualLoadRegistry.record(id, 100, 80, 64, 0, 0);
        VisualLoadRegistry.record(id, 100, 80, 64, 3, 3);
        VisualLoadRegistry.record(id, 100, 80, 64, 2, 2);
        var state = VisualLoadRegistry.state(id);
        assertNotNull(state);
        assertEquals(0, state.drainAverageChurn());
        assertEquals(new VisualLoadRegistry.SelectionChanges(5, 5), state.drainSelectionChanges());
        assertEquals(VisualLoadRegistry.SelectionChanges.NONE, state.drainSelectionChanges());
    }

    @Test
    void removesOfflineAndOptedOutPlayers() {
        UUID first = UUID.randomUUID();
        UUID second = UUID.randomUUID();
        VisualLoadRegistry.record(first, 10, 10, 64, 0, 0);
        VisualLoadRegistry.record(second, 10, 10, 64, 0, 0);
        VisualLoadRegistry.retain(Set.of(first));
        assertNull(VisualLoadRegistry.state(second));
        VisualLoadRegistry.remove(first);
        assertNull(VisualLoadRegistry.state(first));
    }
}
