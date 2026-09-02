package com.durkz.quantumhy.config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PlayerPreferencesTest {

    @Test
    void persistsOptOutByUuid(@TempDir Path dir) {
        UUID playerId = UUID.randomUUID();
        PlayerPreferences preferences = PlayerPreferences.load(dir);

        assertTrue(preferences.isOptimizationEnabled(playerId));
        assertTrue(preferences.setOptimizationEnabled(playerId, false));

        PlayerPreferences reloaded = PlayerPreferences.load(dir);
        assertFalse(reloaded.isOptimizationEnabled(playerId));
        assertTrue(reloaded.setOptimizationEnabled(playerId, true));
        assertTrue(PlayerPreferences.load(dir).isOptimizationEnabled(playerId));
    }

    @Test
    void quarantinesCorruptFileAndRecreatesDefaults(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("PlayerPreferences.json");
        Files.writeString(file, "{broken", StandardCharsets.UTF_8);

        PlayerPreferences preferences = PlayerPreferences.load(dir);

        assertTrue(preferences.isOptimizationEnabled(UUID.randomUUID()));
        assertTrue(Files.readString(file).contains("\"version\": 1"));
        try (var files = Files.list(dir)) {
            assertTrue(files.anyMatch(path -> path.getFileName().toString()
                    .startsWith("PlayerPreferences.json.corrupt.")));
        }
        assertFalse(Files.exists(dir.resolve("PlayerPreferences.json.tmp")));
    }
}
