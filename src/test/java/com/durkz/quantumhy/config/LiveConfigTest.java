package com.durkz.quantumhy.config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.Executors;

import static org.junit.jupiter.api.Assertions.*;

class LiveConfigTest {
    @Test
    void publishesDetachedSnapshotWithoutMutatingOriginalOrFile(@TempDir Path dir) throws Exception {
        QuantumHyConfig original = new QuantumHyConfig();
        LiveConfig live = new LiveConfig(original);
        QuantumHyConfig before = live.get();
        Path file = dir.resolve("QuantumHy.json");
        String json = "{\"maxVisibleEntitiesPerPlayer\": 50, \"densityLowPerChunk\": 0.5}";
        Files.writeString(file, json);
        var result = live.reload(file);
        assertEquals(List.of("maxVisibleEntitiesPerPlayer", "densityLowPerChunk"), result.applied());
        assertNotSame(before, live.get());
        assertEquals(80, before.maxVisibleEntitiesPerPlayer);
        assertEquals(80, original.maxVisibleEntitiesPerPlayer);
        assertEquals(50, live.get().maxVisibleEntitiesPerPlayer);
        assertEquals(json, Files.readString(file));
        assertEquals(0, live.get().configVersion);
        try (var files = Files.list(dir)) {
            assertEquals(1, files.count());
        }
    }

    @Test
    void separatesStructuralSettingsAndKeepsOwnershipAndScheduling() throws Exception {
        LiveConfig live = new LiveConfig(new QuantumHyConfig());
        var result = live.reloadJson("""
                {"enabled":false,"leanCoreTakeover":false,"tickIntervalSeconds":2,
                 "holdSpawnOnLoadingChunks":true,"maxVisibleEntitiesPerPlayer":0,"verboseLog":true}
                """);
        assertEquals(List.of("enabled", "leanCoreTakeover", "tickIntervalSeconds", "holdSpawnOnLoadingChunks"),
                result.restartRequired());
        assertTrue(live.get().enabled);
        assertTrue(live.get().leanCoreTakeover);
        assertEquals(5, live.get().tickIntervalSeconds);
        assertFalse(live.get().holdSpawnOnLoadingChunks);
        assertEquals(0, live.get().maxVisibleEntitiesPerPlayer);
        assertTrue(live.get().verboseLog);
    }

    @Test
    void noOpAndRestartOnlyDoNotInvalidateCaches() throws Exception {
        LiveConfig live = new LiveConfig(new QuantumHyConfig());
        QuantumHyConfig before = live.get();
        assertTrue(live.reloadJson("{}").applied().isEmpty());
        assertTrue(live.reloadJson("{\"enabled\": false}").applied().isEmpty());
        assertSame(before, live.get());
    }

    @Test
    void rejectsMalformedTypesNonFiniteValuesAndInvalidRangesAtomically() {
        List<String> invalid = List.of(
                "null", "[]", "{", "{} {}", "{\"verboseLog\":true,}",
                "{\"verboseLog\": true, \"verboseLog\": false}",
                "{\"densitySmoothing\":NaN}", "{\"densitySmoothing\":1e999}",
                "{\"densitySmoothing\":\"0.5\"}", "{\"densitySmoothing\":0}",
                "{\"verboseLog\":1}", "{\"verboseLog\":null}",
                "{\"maxVisibleEntitiesPerPlayer\":-1}", "{\"maxVisibleEntitiesPerPlayer\":1.5}",
                "{\"maxVisibleEntitiesPerPlayer\":2147483648}", "{\"unknownLimit\":4}",
                "{\"minClientViewRadius\":20,\"maxClientViewRadius\":10}",
                "{\"densityLowPerChunk\":5,\"densityHighPerChunk\":4}",
                "{\"chunkLoadLowChunks\":1000,\"chunkLoadHighChunks\":900}",
                "{\"verboseLog\":true,\"tickIntervalSeconds\":0}");
        for (String json : invalid) {
            LiveConfig live = new LiveConfig(new QuantumHyConfig());
            QuantumHyConfig before = live.get();
            assertThrows(Exception.class, () -> live.reloadJson(json), json);
            assertSame(before, live.get(), json);
        }
    }

    @Test
    void acceptsAllPlannedLiveAdjustmentsWithoutRestart() throws Exception {
        LiveConfig live = new LiveConfig(new QuantumHyConfig());
        var result = live.reloadJson("""
                {"verboseLog":true,"targetClientViewRadius":8,"minClientViewRadius":4,
                 "maxClientViewRadius":24,"minEntityViewBlocks":32,"densityLowPerChunk":0.5,
                 "densityHighPerChunk":3,"densityRingEdgeWeight":0.6,"densitySmoothing":0.5,
                 "baselineShrinkFraction":0,"chunkLoadLowChunks":600,"chunkLoadHighChunks":1400,
                 "streamingBacklogThreshold":60,"maxEntityVerticalDistance":24,
                 "maxVisibleEntitiesPerPlayer":50,"minViewRadiusDelta":1,"maxExpandChunksPerPass":2,
                 "maxShrinkChunksPerPass":3,"maxExpandEntityBlocksPerPass":8,
                 "expandHysteresisPasses":3,"worldPassBudgetMs":6,"maxChunksPerSecond":100,
                 "maxChunksPerTick":6,"streamCatchUpPerSecond":200,"streamCatchUpPerTick":10,
                 "streamCatchUpHoldMs":2000}
                """);
        assertEquals(26, result.applied().size());
        assertTrue(result.restartRequired().isEmpty());
    }

    @Test
    void concurrentReloadsAndReadersSeeCompleteSnapshots() throws Exception {
        LiveConfig live = new LiveConfig(new QuantumHyConfig());
        live.reloadJson("{\"minClientViewRadius\":4,\"maxClientViewRadius\":5}");
        try (var executor = Executors.newFixedThreadPool(3)) {
            var first = executor.submit(() -> {
                for (int i = 0; i < 100; i++) {
                    live.reloadJson("{\"minClientViewRadius\":6,\"maxClientViewRadius\":7}");
                }
                return null;
            });
            var second = executor.submit(() -> {
                for (int i = 0; i < 100; i++) {
                    live.reloadJson("{\"minClientViewRadius\":8,\"maxClientViewRadius\":9}");
                }
                return null;
            });
            var reader = executor.submit(() -> {
                for (int i = 0; i < 10000; i++) {
                    QuantumHyConfig snapshot = live.get();
                    assertEquals(snapshot.minClientViewRadius + 1, snapshot.maxClientViewRadius);
                }
            });
            first.get();
            second.get();
            reader.get();
        }
    }

    @Test
    void missingFileLeavesCurrentSettingsUntouched(@TempDir Path dir) {
        LiveConfig live = new LiveConfig(new QuantumHyConfig());
        QuantumHyConfig before = live.get();
        assertThrows(java.io.IOException.class, () -> live.reload(dir.resolve("missing.json")));
        assertSame(before, live.get());
    }
}
