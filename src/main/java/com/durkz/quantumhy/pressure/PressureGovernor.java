package com.durkz.quantumhy.pressure;

import com.durkz.quantumhy.QuantumHyPlugin;
import com.durkz.quantumhy.config.QuantumHyConfig;
import com.hypixel.hytale.logger.HytaleLogger;
import com.hypixel.hytale.metrics.metric.HistoricMetric;
import com.hypixel.hytale.server.core.modules.entity.tracker.EntityTrackerSystems;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.ClientEffectWorldSettings;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.WorldConfig;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

/**
 * Watches real per-world MSPT from {@link World#getBufferedTickLengthMetricSet()} and, after
 * sustain/cooldown, tightens existing QuantumHy render levers. Optional world levers and client
 * effect trims are restored on release and shutdown.
 */
public final class PressureGovernor {

    public enum Tier {
        NORMAL,
        PRESSURED
    }

    public record Snapshot(
            Tier tier,
            double msptAvg10s,
            double msptLast,
            boolean worldLeversActive,
            boolean effectsTrimActive
    ) {
        public boolean pressured() {
            return tier == Tier.PRESSURED;
        }

        public static Snapshot idle() {
            return new Snapshot(Tier.NORMAL, 0, 0, false, false);
        }
    }

    private static final double NANOS_TO_MS = 1.0D / 1_000_000.0D;

    /** Per-world vertical cull distance chosen on the last pass (read by {@link com.durkz.quantumhy.view.EntityCullSystem}). */
    static final ConcurrentHashMap<String, Integer> VERTICAL_BLOCKS = new ConcurrentHashMap<>();

    private final HytaleLogger logger;
    private final Map<UUID, WorldState> worlds = new ConcurrentHashMap<>();

    private static volatile PressureGovernor active;

    public PressureGovernor(@Nonnull QuantumHyPlugin plugin) {
        this.logger = plugin.getLogger();
        active = this;
    }

    @Nonnull
    public static Snapshot statusSnapshot(@Nullable UUID worldId) {
        PressureGovernor governor = active;
        return governor == null ? Snapshot.idle() : governor.snapshotFor(worldId);
    }

    /** Effective density and streaming caps for one adaptive pass (may reflect MSPT pressure). */
    public record ViewPassContext(
            double densityLowPerChunk,
            double densityHighPerChunk,
            int maxChunksPerSecond,
            int maxChunksPerTick
    ) {
        @Nonnull
        public static ViewPassContext fromConfig(@Nonnull QuantumHyConfig config) {
            return new ViewPassContext(
                    config.densityLowPerChunk,
                    config.densityHighPerChunk,
                    config.maxChunksPerSecond,
                    config.maxChunksPerTick);
        }
    }

    public ViewPassContext viewContext(@Nonnull QuantumHyConfig config, @Nonnull Snapshot snap) {
        boolean writeChunkRate = com.durkz.quantumhy.integration.LeanCoreBridge.shouldQuantumHyWriteChunkRate(config);
        return new ViewPassContext(
                densityLowPerChunk(config, snap),
                densityHighPerChunk(config, snap),
                writeChunkRate ? maxChunksPerSecond(config, snap) : 0,
                writeChunkRate ? maxChunksPerTick(config, snap) : 0);
    }

    /** Must run on the world's thread. */
    @Nonnull
    public Snapshot update(@Nonnull World world, @Nonnull QuantumHyConfig config, int passIntervalSeconds) {
        UUID worldId = world.getWorldConfig().getUuid();
        WorldState state = worlds.computeIfAbsent(worldId, ignored -> new WorldState());
        int interval = Math.max(1, passIntervalSeconds);

        if (!config.pressureGovernorEnabled) {
            if (state.tier == Tier.PRESSURED) {
                release(world, config, state, "disabled");
            }
            Snapshot snap = readMspt(world, state, Tier.NORMAL);
            state.lastSnapshot = snap;
            VERTICAL_BLOCKS.put(world.getName(), config.maxEntityVerticalDistance);
            return snap;
        }

        HistoricMetric metric = world.getBufferedTickLengthMetricSet();
        double msptAvg = metric == null ? 0 : metric.getAverage(0) * NANOS_TO_MS;
        double msptLast = metric == null ? 0 : metric.getLastValue() * NANOS_TO_MS;
        state.msptAvg10s = msptAvg;
        state.msptLast = msptLast;

        if (state.tier == Tier.NORMAL) {
            if (msptAvg >= config.pressureMsptEnter) {
                state.aboveEnterPasses++;
                if (state.aboveEnterPasses * interval >= config.pressureSustainSeconds) {
                    enter(world, config, state);
                }
            } else {
                state.aboveEnterPasses = 0;
            }
        } else if (msptAvg <= config.pressureMsptExit) {
            state.belowExitPasses++;
            if (state.belowExitPasses * interval >= config.pressureCooldownSeconds) {
                release(world, config, state, "cooldown");
            }
        } else {
            state.belowExitPasses = 0;
        }

        Snapshot snap = new Snapshot(state.tier, msptAvg, msptLast,
                state.worldLeversApplied, state.effectsTrimmed);
        state.lastSnapshot = snap;
        VERTICAL_BLOCKS.put(world.getName(), maxEntityVerticalDistance(config, snap));
        return snap;
    }

    /** Effective vertical cull for a world after the last governor pass. */
    public static int verticalDistance(@Nonnull String worldName, int configDefault) {
        if (configDefault <= 0) {
            return 0;
        }
        Integer effective = VERTICAL_BLOCKS.get(worldName);
        return effective == null ? configDefault : effective;
    }

    @Nonnull
    public Snapshot snapshotFor(@Nullable UUID worldId) {
        if (worldId == null) {
            return Snapshot.idle();
        }
        WorldState state = worlds.get(worldId);
        return state == null || state.lastSnapshot == null ? Snapshot.idle() : state.lastSnapshot;
    }

    public double densityHighPerChunk(@Nonnull QuantumHyConfig config, @Nonnull Snapshot snap) {
        if (!snap.pressured()) {
            return config.densityHighPerChunk;
        }
        return config.densityHighPerChunk / Math.max(1.0D, config.pressureDensityMultiplier);
    }

    public double densityLowPerChunk(@Nonnull QuantumHyConfig config, @Nonnull Snapshot snap) {
        if (!snap.pressured()) {
            return config.densityLowPerChunk;
        }
        double root = Math.sqrt(Math.max(1.0D, config.pressureDensityMultiplier));
        return config.densityLowPerChunk / root;
    }

    public int maxChunksPerSecond(@Nonnull QuantumHyConfig config, @Nonnull Snapshot snap) {
        int base = config.maxChunksPerSecond;
        if (!snap.pressured() || base <= 0) {
            return base;
        }
        return Math.max(1, (int) Math.round(base * config.pressureChunkRateMultiplier));
    }

    public int maxChunksPerTick(@Nonnull QuantumHyConfig config, @Nonnull Snapshot snap) {
        int base = config.maxChunksPerTick;
        if (!snap.pressured() || base <= 0) {
            return base;
        }
        return Math.max(1, (int) Math.round(base * config.pressureChunkRateMultiplier));
    }

    public int maxEntityVerticalDistance(@Nonnull QuantumHyConfig config, @Nonnull Snapshot snap) {
        int base = config.maxEntityVerticalDistance;
        if (!snap.pressured() || base <= 0) {
            return base;
        }
        return Math.max(0, base - config.pressureVerticalTrimBlocks);
    }

    public void applyEntityLod(@Nonnull QuantumHyConfig config, @Nonnull Snapshot snap) {
        double ratio = EntityTrackerSystems.LODCull.ENTITY_LOD_RATIO_DEFAULT * config.entityLodAggressiveness;
        if (snap.pressured()) {
            ratio *= config.pressureLodMultiplier;
        }
        EntityTrackerSystems.LODCull.ENTITY_LOD_RATIO = ratio;
    }

    /** Restore every pressured world in parallel (same 2s budget total, not per world). */
    public void shutdown(@Nonnull QuantumHyConfig config) {
        var universe = com.hypixel.hytale.server.core.universe.Universe.get();
        List<CompletableFuture<Void>> pending = new ArrayList<>();
        for (Map.Entry<UUID, WorldState> entry : worlds.entrySet()) {
            World world = universe.getWorld(entry.getKey());
            if (world == null || !world.isAlive() || entry.getValue().tier != Tier.PRESSURED) {
                continue;
            }
            WorldState state = entry.getValue();
            CompletableFuture<Void> done = new CompletableFuture<>();
            world.execute(() -> {
                release(world, config, state, "shutdown");
                done.complete(null);
            });
            pending.add(done);
        }
        if (!pending.isEmpty()) {
            try {
                CompletableFuture.allOf(pending.toArray(new CompletableFuture[0]))
                        .get(2, TimeUnit.SECONDS);
            } catch (Exception ex) {
                logger.atWarning().withCause(ex).log(
                        "pressure restore timed out for one or more worlds");
            }
        }
        worlds.clear();
        VERTICAL_BLOCKS.clear();
    }

    /** Called on world thread during an explicit release while the world is still alive. */
    public void restoreWorld(@Nonnull World world, @Nonnull QuantumHyConfig config) {
        WorldState state = worlds.get(world.getWorldConfig().getUuid());
        if (state == null || state.tier != Tier.PRESSURED) {
            return;
        }
        release(world, config, state, "shutdown");
    }

    private void enter(@Nonnull World world, @Nonnull QuantumHyConfig config, @Nonnull WorldState state) {
        state.tier = Tier.PRESSURED;
        state.aboveEnterPasses = 0;
        state.belowExitPasses = 0;

        WorldConfig worldConfig = world.getWorldConfig();
        if (config.pressureWorldLevers && !state.worldLeversApplied) {
            SavedLevers saved = state.ensureSaved();
            saved.spawningNpc = worldConfig.isSpawningNPC();
            saved.blockTicking = worldConfig.isBlockTicking();
            saved.canUnloadChunks = worldConfig.canUnloadChunks();
            worldConfig.setSpawningNPC(false);
            worldConfig.setBlockTicking(false);
            worldConfig.setCanUnloadChunks(true);
            state.worldLeversApplied = true;
        }

        if (config.pressureTrimClientEffects && !state.effectsTrimmed) {
            trimClientEffects(world, config, state);
        }

        if (config.verboseLog) {
            logger.atInfo().log("pressure [world=%s] enter mspt=%.1f avg10s=%.1f worldLevers=%s effects=%s",
                    world.getName(), state.msptLast, state.msptAvg10s,
                    state.worldLeversApplied, state.effectsTrimmed);
        }
    }

    private void release(@Nonnull World world, @Nonnull QuantumHyConfig config, @Nonnull WorldState state,
                         @Nonnull String reason) {
        Tier was = state.tier;
        state.tier = Tier.NORMAL;
        state.aboveEnterPasses = 0;
        state.belowExitPasses = 0;

        WorldConfig worldConfig = world.getWorldConfig();
        SavedLevers saved = state.saved;
        if (state.worldLeversApplied && saved != null) {
            worldConfig.setSpawningNPC(saved.spawningNpc);
            worldConfig.setBlockTicking(saved.blockTicking);
            worldConfig.setCanUnloadChunks(saved.canUnloadChunks);
            state.worldLeversApplied = false;
        }

        if (state.effectsTrimmed && saved != null) {
            ClientEffectWorldSettings fx = worldConfig.getClientEffects();
            if (fx != null) {
                fx.setBloomIntensity(saved.bloomIntensity);
                fx.setBloomPower(saved.bloomPower);
                fx.setSunshaftIntensity(saved.sunshaftIntensity);
                fx.setSunshaftScaleFactor(saved.sunshaftScaleFactor);
                fx.setSunIntensity(saved.sunIntensity);
                broadcastClientEffects(world, fx);
            }
            state.effectsTrimmed = false;
        }

        if (was == Tier.PRESSURED && config.verboseLog) {
            logger.atInfo().log("pressure [world=%s] release (%s) mspt=%.1f avg10s=%.1f",
                    world.getName(), reason, state.msptLast, state.msptAvg10s);
        }
    }

    private void trimClientEffects(@Nonnull World world, @Nonnull QuantumHyConfig config, @Nonnull WorldState state) {
        ClientEffectWorldSettings fx = world.getWorldConfig().getClientEffects();
        if (fx == null) {
            return;
        }
        SavedLevers saved = state.ensureSaved();
        saved.bloomIntensity = fx.getBloomIntensity();
        saved.bloomPower = fx.getBloomPower();
        saved.sunshaftIntensity = fx.getSunshaftIntensity();
        saved.sunshaftScaleFactor = fx.getSunshaftScaleFactor();
        saved.sunIntensity = fx.getSunIntensity();

        float scale = (float) clamp(config.pressureEffectScale, 0.05D, 1.0D);
        fx.setBloomIntensity(saved.bloomIntensity * scale);
        fx.setBloomPower(saved.bloomPower * scale);
        fx.setSunshaftIntensity(saved.sunshaftIntensity * scale);
        fx.setSunshaftScaleFactor(saved.sunshaftScaleFactor * scale);
        fx.setSunIntensity(saved.sunIntensity * scale);
        broadcastClientEffects(world, fx);
        state.effectsTrimmed = true;
    }

    private static void broadcastClientEffects(@Nonnull World world, @Nonnull ClientEffectWorldSettings fx) {
        for (PlayerRef ref : world.getPlayerRefs()) {
            if (ref == null || !ref.isValid()) {
                continue;
            }
            var handler = ref.getPacketHandler();
            if (handler == null) {
                continue;
            }
            handler.write(fx.createSunSettingsPacket(), fx.createPostFxSettingsPacket());
        }
    }

    @Nonnull
    private static Snapshot readMspt(@Nonnull World world, @Nonnull WorldState state, @Nonnull Tier tier) {
        HistoricMetric metric = world.getBufferedTickLengthMetricSet();
        double msptAvg = metric == null ? 0 : metric.getAverage(0) * NANOS_TO_MS;
        double msptLast = metric == null ? 0 : metric.getLastValue() * NANOS_TO_MS;
        state.msptAvg10s = msptAvg;
        state.msptLast = msptLast;
        return new Snapshot(tier, msptAvg, msptLast, false, false);
    }

    private static double clamp(double value, double min, double max) {
        return Math.max(min, Math.min(max, value));
    }

    /** Status line for /q. */
    @Nonnull
    public static String formatStatus(@Nonnull Snapshot snap) {
        if (!snap.pressured() && snap.msptAvg10s() <= 0) {
            return "off";
        }
        return String.format(Locale.ROOT, "%s mspt=%.1f/%.1f levers=%s",
                snap.tier().name().toLowerCase(Locale.ROOT),
                snap.msptLast(), snap.msptAvg10s(),
                leversLabel(snap));
    }

    private static String leversLabel(@Nonnull Snapshot snap) {
        if (!snap.pressured()) {
            return "none";
        }
        StringBuilder sb = new StringBuilder("render");
        if (snap.worldLeversActive()) {
            sb.append("+world");
        }
        if (snap.effectsTrimActive()) {
            sb.append("+effects");
        }
        return sb.toString();
    }

    private static final class WorldState {
        Tier tier = Tier.NORMAL;
        int aboveEnterPasses;
        int belowExitPasses;
        double msptAvg10s;
        double msptLast;
        boolean worldLeversApplied;
        boolean effectsTrimmed;
        SavedLevers saved;
        Snapshot lastSnapshot;

        @Nonnull
        SavedLevers ensureSaved() {
            if (saved == null) {
                saved = new SavedLevers();
            }
            return saved;
        }
    }

    private static final class SavedLevers {
        boolean spawningNpc;
        boolean blockTicking;
        boolean canUnloadChunks;
        float bloomIntensity;
        float bloomPower;
        float sunshaftIntensity;
        float sunshaftScaleFactor;
        float sunIntensity;
    }
}
