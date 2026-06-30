package com.durkz.quantumhy.config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.hypixel.hytale.logger.HytaleLogger;

import java.io.File;
import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.logging.Level;

/**
 * Config for QuantumHy, saved as QuantumHy.json. QuantumHy only ever shrinks under the player's own
 * view radius, never inflates past what the player asked for.
 */
public class QuantumHyConfig {

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    private transient File configFile;

    /** Master switch. When false, QuantumHy never touches any view radius. */
    public boolean enabled = true;

    /** Detailed server log: one line per world per pass with each player's density and view decision. */
    public boolean verboseLog = true;

    /** Seconds between adaptive passes. */
    public int tickIntervalSeconds = 5;

    /** Grace before the first pass after start, so logins and initial streaming can settle. */
    public int initialDelaySeconds = 20;

    /**
     * Hard FPS cap for the client view radius, in chunks. {@code 0} means no hard cap: the player's
     * own server view radius is the ceiling and QuantumHy only trims under local density. Set this
     * above 0 to trade view distance for FPS even in open areas.
     */
    public int targetClientViewRadius = 0;

    /** Lower bound QuantumHy will never shrink below. */
    public int minClientViewRadius = 6;

    /** Upper bound for QuantumHy's own hard cap (still clamped by the player's server view radius). */
    public int maxClientViewRadius = 32;

    /** Chunk radius scanned around each player to estimate local render cost. */
    public int densityScanChunkRadius = 4;

    /**
     * Local density is entities per loaded chunk in the scan area. At or below this, the radius is
     * allowed back up to the base. A normal world idles around 1 to 1.5 entities per chunk.
     */
    public double densityLowPerChunk = 2.0D;

    /** At or above this entities-per-chunk, the radius is pulled all the way down to the minimum. */
    public double densityHighPerChunk = 8.0D;

    /**
     * Smoothing for the density signal: weight of the newest sample in an exponential moving average,
     * in (0, 1]. Lower is smoother, so a moving player's view radius stops flip-flopping on momentary
     * spikes. {@code 1.0} means no smoothing (react to each raw reading).
     */
    public double densitySmoothing = 0.4D;

    /**
     * Also adapt the per-player entity radius, not just the chunk view radius. This is the big lever
     * in mob-heavy spots: the chunk radius only trims terrain, while this trims how far the server
     * streams entities to the client. Driven by the same density signal as the chunk radius.
     */
    public boolean adaptEntityRadius = true;

    /** Lower bound for the adaptive entity radius, in blocks (16 blocks = 1 chunk). */
    public int minEntityViewBlocks = 48;

    /**
     * Global entity LOD culling. {@code 1.0} is the engine default; higher culls small and distant
     * entities sooner (e.g. {@code 1.5} drops them at ~80% of the default distance). Server-wide, not
     * per player, applied once at startup and restored on shutdown.
     */
    public double entityLodAggressiveness = 1.5D;

    /**
     * Stop streaming entities that are too far above or below the player, in blocks. The engine sends
     * every entity inside the entity radius as a plain sphere with no line of sight, so mobs deep in
     * caves under you (or far overhead) still get sent and drawn through the terrain. This drops them.
     * {@code 0} turns the vertical cut off. 40 keeps normal surface play untouched while cutting the
     * cave and ceiling crowds that the client would otherwise render for nothing.
     */
    public int maxEntityVerticalDistance = 40;

    /**
     * Hard ceiling on how many entities the server streams to one client at once. In a crowd past
     * this, only the nearest entities are sent and the rest are held back until they thin out. Other
     * players are never trimmed. {@code 0} turns the cap off (the adaptive entity radius and LOD still
     * apply). Set it to protect FPS in dense mob pile-ups on lower-end machines.
     */
    public int maxVisibleEntitiesPerPlayer = 0;

    /** Minimum change (chunks) before an update is sent, to avoid churn. */
    public int minViewRadiusDelta = 2;

    /** Hold radius cuts while a player is actively streaming at least this many chunks. */
    public boolean respectStreamingGrace = true;
    public int streamingBacklogThreshold = 8;

    /**
     * Smooth how fast chunks stream to each managed client. The hitching you feel moving into fresh
     * terrain is the client meshing a burst of chunks at once; capping the send rate spreads that out
     * for fewer stutters, at the cost of slightly slower fill. Set false to leave the engine alone.
     */
    public boolean smoothChunkStreaming = true;

    /** Max chunks per second streamed to a managed client. {@code 0} keeps the engine per-connection default (local 256, LAN 128, internet 36). */
    public int maxChunksPerSecond = 128;

    /** Max chunks per server tick streamed to a managed client. This is the real anti-hitch lever (engine default is 4). {@code 0} keeps the default. */
    public int maxChunksPerTick = 2;

    /**
     * If LeanCore is installed, QuantumHy detects it on startup and turns off LeanCore's client
     * view-radius governance so the two don't both write it. QuantumHy then owns the view radius and
     * LeanCore keeps simulation radius, chunk throughput, and memory. Set false to leave LeanCore
     * alone (they may then fight over the view radius).
     */
    public boolean leanCoreTakeover = true;

    /**
     * The opposite of {@code leanCoreTakeover}: QuantumHy stays out of the client view radius
     * entirely and lets LeanCore keep it. When true, QuantumHy touches neither the view radius nor
     * anything in LeanCore.
     */
    public boolean yieldToLeanCoreViewRadius = false;

    public static QuantumHyConfig load(Path dataDirectory) {
        File directory = dataDirectory.toFile();
        if (!directory.exists()) {
            directory.mkdirs();
        }

        File file = new File(directory, "QuantumHy.json");
        QuantumHyConfig config = new QuantumHyConfig();
        config.configFile = file;

        if (!file.exists()) {
            config.save();
            return config;
        }

        try (Reader reader = Files.newBufferedReader(file.toPath(), StandardCharsets.UTF_8)) {
            QuantumHyConfig loaded = GSON.fromJson(reader, QuantumHyConfig.class);
            if (loaded != null) {
                loaded.configFile = file;
                loaded.applyDefaults();
                return loaded;
            }
        } catch (Exception ignored) {
            quarantineCorrupt(file);
        }

        config.applyDefaults();
        return config;
    }

    private static void quarantineCorrupt(File file) {
        if (file == null || !file.isFile()) {
            return;
        }
        try {
            Path corrupt = file.toPath().resolveSibling(
                    file.getName() + ".corrupt." + System.currentTimeMillis());
            Files.move(file.toPath(), corrupt, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException ignored) {
        }
    }

    private void applyDefaults() {
        if (tickIntervalSeconds <= 0) {
            tickIntervalSeconds = 5;
        }
        if (initialDelaySeconds < 0) {
            initialDelaySeconds = 20;
        }
        if (minClientViewRadius < 1) {
            minClientViewRadius = 1;
        }
        if (maxClientViewRadius < minClientViewRadius) {
            maxClientViewRadius = minClientViewRadius;
        }
        if (targetClientViewRadius < 0) {
            targetClientViewRadius = 0;
        }
        if (densityScanChunkRadius < 0) {
            densityScanChunkRadius = 0;
        }
        if (densityLowPerChunk < 0) {
            densityLowPerChunk = 0;
        }
        if (densityHighPerChunk <= densityLowPerChunk) {
            densityHighPerChunk = densityLowPerChunk + 1;
        }
        if (densitySmoothing <= 0 || densitySmoothing > 1) {
            densitySmoothing = 1.0D;
        }
        if (minEntityViewBlocks < 0) {
            minEntityViewBlocks = 0;
        }
        if (entityLodAggressiveness <= 0) {
            entityLodAggressiveness = 1.0D;
        }
        if (maxEntityVerticalDistance < 0) {
            maxEntityVerticalDistance = 0;
        }
        if (maxVisibleEntitiesPerPlayer < 0) {
            maxVisibleEntitiesPerPlayer = 0;
        }
        if (minViewRadiusDelta < 1) {
            minViewRadiusDelta = 1;
        }
        if (streamingBacklogThreshold < 0) {
            streamingBacklogThreshold = 0;
        }
        if (maxChunksPerSecond < 0) {
            maxChunksPerSecond = 0;
        }
        if (maxChunksPerTick < 0) {
            maxChunksPerTick = 0;
        }
    }

    public void save() {
        if (configFile == null) {
            return;
        }
        try {
            Path target = configFile.toPath();
            Path temp = target.resolveSibling(configFile.getName() + ".tmp");
            try (Writer writer = Files.newBufferedWriter(temp, StandardCharsets.UTF_8)) {
                GSON.toJson(this, writer);
            }
            try {
                Files.move(temp, target,
                        StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (IOException atomicUnsupported) {
                Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException failed) {
            HytaleLogger.getLogger().at(Level.WARNING).withCause(failed)
                    .log("QuantumHy failed to save config to %s", configFile.getAbsolutePath());
        }
    }
}
