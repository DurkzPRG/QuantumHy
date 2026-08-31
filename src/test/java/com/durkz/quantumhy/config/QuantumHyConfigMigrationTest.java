package com.durkz.quantumhy.config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class QuantumHyConfigMigrationTest {

    private static final String LEGACY_020 = """
            {
              "enabled": true,
              "densityLowPerChunk": 2.0,
              "densityHighPerChunk": 8.0,
              "entityLodAggressiveness": 1.5,
              "maxEntityVerticalDistance": 40,
              "maxVisibleEntitiesPerPlayer": 0,
              "pressureMsptEnter": 52.0,
              "pressureMsptExit": 47.0,
              "pressureSustainSeconds": 6,
              "pressureDensityMultiplier": 1.35
            }
            """;

    private static final String BROKEN_V1 = """
            {
              "densityLowPerChunk": 2.0,
              "densityHighPerChunk": 8.0,
              "entityLodAggressiveness": 1.5,
              "maxEntityVerticalDistance": 40,
              "maxVisibleEntitiesPerPlayer": 0,
              "pressureMsptEnter": 52.0,
              "pressureMsptExit": 47.0,
              "pressureSustainSeconds": 6,
              "pressureDensityMultiplier": 1.35,
              "configVersion": 1
            }
            """;

    private static final String V3_SECTION_DEFAULTS = """
            {
              "chunkLoadLowChunks": 480,
              "chunkLoadHighChunks": 1120,
              "maxChunksPerTick": 2,
              "streamingBacklogThreshold": 80,
              "configVersion": 3
            }
            """;

    @Test
    void migratesLegacy020WithoutConfigVersion(@TempDir Path dir) throws Exception {
        Files.writeString(dir.resolve("QuantumHy.json"), LEGACY_020, StandardCharsets.UTF_8);

        QuantumHyConfig config = QuantumHyConfig.load(dir);

        assertEquals(1.0D, config.densityLowPerChunk, 1e-9);
        assertEquals(4.0D, config.densityHighPerChunk, 1e-9);
        assertEquals(2.0D, config.entityLodAggressiveness, 1e-9);
        assertEquals(32, config.maxEntityVerticalDistance);
        assertEquals(80, config.maxVisibleEntitiesPerPlayer);
        assertEquals(48.0D, config.pressureMsptEnter, 1e-9);
        assertTrue(config.densityRingWeighting);
        assertEquals(0.10D, config.baselineShrinkFraction, 1e-9);
        assertEquals(700, config.chunkLoadLowChunks);
        assertEquals(1550, config.chunkLoadHighChunks);
        assertEquals(80, config.streamingBacklogThreshold);
        assertEquals(8, config.maxChunksPerTick);
        assertEquals(1, config.maxExpandChunksPerPass);
        assertEquals(2, config.maxShrinkChunksPerPass);
        assertEquals(16, config.maxExpandEntityBlocksPerPass);
        assertEquals(2, config.expandHysteresisPasses);
        assertEquals(8, config.worldPassBudgetMs);
        assertTrue(config.pressureExitRequiresLastTick);
        assertEquals(6, config.configVersion);
        assertTrue(Files.readString(dir.resolve("QuantumHy.json")).contains("\"configVersion\": 6"));
    }

    @Test
    void repairsBrokenV1Migration(@TempDir Path dir) throws Exception {
        Files.writeString(dir.resolve("QuantumHy.json"), BROKEN_V1, StandardCharsets.UTF_8);

        QuantumHyConfig config = QuantumHyConfig.load(dir);

        assertEquals(1.0D, config.densityLowPerChunk, 1e-9);
        assertEquals(4.0D, config.densityHighPerChunk, 1e-9);
        assertEquals(700, config.chunkLoadLowChunks);
        assertEquals(1550, config.chunkLoadHighChunks);
        assertEquals(80, config.streamingBacklogThreshold);
        assertEquals(8, config.maxChunksPerTick);
        assertEquals(6, config.configVersion);
    }

    @Test
    void migratesV3SectionDefaultsToView8Budget(@TempDir Path dir) throws Exception {
        Files.writeString(dir.resolve("QuantumHy.json"), V3_SECTION_DEFAULTS, StandardCharsets.UTF_8);

        QuantumHyConfig config = QuantumHyConfig.load(dir);

        assertEquals(700, config.chunkLoadLowChunks);
        assertEquals(1550, config.chunkLoadHighChunks);
        assertEquals(8, config.maxChunksPerTick);
        assertEquals(6, config.configVersion);
    }

    @Test
    void v4FileKeepsVerboseLogAndGainsV5Keys(@TempDir Path dir) throws Exception {
        Files.writeString(dir.resolve("QuantumHy.json"), """
                {
                  "verboseLog": true,
                  "baselineShrinkFraction": 0.10,
                  "configVersion": 4
                }
                """, StandardCharsets.UTF_8);

        QuantumHyConfig config = QuantumHyConfig.load(dir);

        assertTrue(config.verboseLog);
        assertEquals(0.10D, config.baselineShrinkFraction, 1e-9);
        assertEquals(1, config.maxExpandChunksPerPass);
        assertEquals(2, config.expandHysteresisPasses);
        assertTrue(config.pressureExitRequiresLastTick);
        assertEquals(6, config.configVersion);
    }

    @Test
    void newInstallDefaultsVerboseLogOff(@TempDir Path dir) {
        QuantumHyConfig config = QuantumHyConfig.load(dir);

        assertFalse(config.verboseLog);
        assertEquals(6, config.configVersion);
        assertEquals(8, config.worldPassBudgetMs);
        assertEquals(1.0D, config.entityLodAggressiveness, 1e-9);
        assertFalse(config.holdSpawnOnLoadingChunks);
        assertFalse(config.pressureTrimClientEffects);
    }
}
