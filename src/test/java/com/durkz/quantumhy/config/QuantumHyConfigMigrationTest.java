package com.durkz.quantumhy.config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
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
        assertEquals(2, config.configVersion);
        assertTrue(Files.readString(dir.resolve("QuantumHy.json")).contains("\"configVersion\": 2"));
    }

    @Test
    void repairsBrokenV1Migration(@TempDir Path dir) throws Exception {
        Files.writeString(dir.resolve("QuantumHy.json"), BROKEN_V1, StandardCharsets.UTF_8);

        QuantumHyConfig config = QuantumHyConfig.load(dir);

        assertEquals(1.0D, config.densityLowPerChunk, 1e-9);
        assertEquals(4.0D, config.densityHighPerChunk, 1e-9);
        assertEquals(2, config.configVersion);
    }
}
