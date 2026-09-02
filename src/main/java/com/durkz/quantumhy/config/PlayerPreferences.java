package com.durkz.quantumhy.config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonParseException;
import com.hypixel.hytale.logger.HytaleLogger;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.io.IOException;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;

public final class PlayerPreferences {

    private static final int CURRENT_VERSION = 1;
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    private final Path file;
    private final Set<UUID> optedOut = ConcurrentHashMap.newKeySet();
    private final Object saveLock = new Object();

    private PlayerPreferences(@Nonnull Path file) {
        this.file = file;
    }

    @Nonnull
    public static PlayerPreferences load(@Nonnull Path dataDirectory) {
        try {
            Files.createDirectories(dataDirectory);
        } catch (IOException createFailed) {
            logWarning(createFailed, "QuantumHy failed to create player preferences directory %s", dataDirectory);
        }
        Path file = dataDirectory.resolve("PlayerPreferences.json");
        PlayerPreferences preferences = new PlayerPreferences(file);
        if (!Files.exists(file)) {
            preferences.save();
            return preferences;
        }

        boolean rewrite = false;
        try {
            Data data = GSON.fromJson(Files.readString(file, StandardCharsets.UTF_8), Data.class);
            if (data == null || data.version != CURRENT_VERSION || data.optedOutPlayerUuids == null) {
                rewrite = true;
            } else {
                for (String value : data.optedOutPlayerUuids) {
                    try {
                        preferences.optedOut.add(UUID.fromString(value));
                    } catch (IllegalArgumentException invalidUuid) {
                        rewrite = true;
                    }
                }
            }
        } catch (IOException | JsonParseException corrupt) {
            quarantine(file);
            logWarning(corrupt, "QuantumHy found invalid player preferences at %s", file);
            rewrite = true;
        }
        if (rewrite) {
            preferences.save();
        }
        return preferences;
    }

    public boolean isOptimizationEnabled(@Nullable UUID playerId) {
        return playerId == null || !optedOut.contains(playerId);
    }

    public boolean setOptimizationEnabled(@Nonnull UUID playerId, boolean enabled) {
        boolean changed = enabled ? optedOut.remove(playerId) : optedOut.add(playerId);
        if (changed) {
            save();
        }
        return changed;
    }

    public int optedOutCount() {
        return optedOut.size();
    }

    void save() {
        synchronized (saveLock) {
            Path temp = file.resolveSibling(file.getFileName() + ".tmp");
            try {
                List<String> values = optedOut.stream()
                        .map(UUID::toString)
                        .sorted(Comparator.naturalOrder())
                        .toList();
                try (Writer writer = Files.newBufferedWriter(temp, StandardCharsets.UTF_8)) {
                    GSON.toJson(new Data(CURRENT_VERSION, values), writer);
                }
                try {
                    Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
                } catch (IOException atomicUnsupported) {
                    Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING);
                }
            } catch (IOException failed) {
                logWarning(failed, "QuantumHy failed to save player preferences to %s", file);
            } finally {
                try {
                    Files.deleteIfExists(temp);
                } catch (IOException cleanupFailed) {
                    logWarning(cleanupFailed, "QuantumHy failed to remove temporary player preferences file %s", temp);
                }
            }
        }
    }

    private static void quarantine(@Nonnull Path file) {
        try {
            Files.move(file, file.resolveSibling(file.getFileName() + ".corrupt." + System.currentTimeMillis()),
                    StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException quarantineFailed) {
            logWarning(quarantineFailed, "QuantumHy failed to quarantine invalid player preferences at %s", file);
        }
    }

    private static void logWarning(@Nonnull Throwable cause, @Nonnull String message, @Nonnull Path path) {
        try {
            HytaleLogger.getLogger().at(Level.WARNING).withCause(cause).log(message, path);
        } catch (IllegalStateException | LinkageError loggerUnavailable) {
            System.err.println(message.formatted(path) + ": " + cause.getMessage());
        }
    }

    private static final class Data {
        int version;
        List<String> optedOutPlayerUuids = new ArrayList<>();

        Data(int version, List<String> optedOutPlayerUuids) {
            this.version = version;
            this.optedOutPlayerUuids = optedOutPlayerUuids;
        }
    }
}
