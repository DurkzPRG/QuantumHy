package com.durkz.quantumhy.command;

import com.durkz.quantumhy.config.QuantumHyConfig;
import com.durkz.quantumhy.integration.LeanCoreBridge;
import com.durkz.quantumhy.spawn.SpawnStreamPauseSystem;
import com.durkz.quantumhy.view.EntityCullSystem;
import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.command.system.CommandContext;
import com.hypixel.hytale.server.core.command.system.arguments.system.RequiredArg;
import com.hypixel.hytale.server.core.command.system.arguments.types.ArgTypes;
import com.hypixel.hytale.server.core.command.system.basecommands.AbstractCommandCollection;
import com.hypixel.hytale.server.core.command.system.basecommands.CommandBase;
import com.hypixel.hytale.server.core.modules.entity.player.ChunkTracker;
import com.hypixel.hytale.server.core.modules.entity.tracker.EntityTrackerSystems;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.Universe;
import com.hypixel.hytale.server.core.universe.world.World;

import java.util.Collection;
import java.util.Locale;

/** {@code /q status} (alias /quantumhy). Read-only status and diagnostics for what QuantumHy is doing right now. */
public class QuantumCommand extends AbstractCommandCollection {

    public QuantumCommand(QuantumHyConfig config) {
        super("quantumhy", "QuantumHy status and diagnostics");
        addAliases("q", "qhy");
        addSubCommand(new StatusSubCommand(config));
        addSubCommand(new HelpSubCommand());
        addSubCommand(new PerfSubCommand());
    }

    @Override
    protected boolean canGeneratePermission() {
        return false;
    }

    private static final class StatusSubCommand extends CommandBase {

        private final QuantumHyConfig config;

        StatusSubCommand(QuantumHyConfig config) {
            super("status", "Show QuantumHy status");
            this.config = config;
        }

        @Override
        protected boolean canGeneratePermission() {
            return false;
        }

        @Override
        protected void executeSync(CommandContext ctx) {
            status(ctx, config);
        }
    }

    private static final class HelpSubCommand extends CommandBase {

        HelpSubCommand() {
            super("help", "List QuantumHy commands");
        }

        @Override
        protected boolean canGeneratePermission() {
            return false;
        }

        @Override
        protected void executeSync(CommandContext ctx) {
            help(ctx);
        }
    }

    private static final class PerfSubCommand extends CommandBase {

        private final RequiredArg<String> actionArg;

        PerfSubCommand() {
            super("perf", "Toggle local dev performance meter");
            this.actionArg = withRequiredArg("action", "on | off", ArgTypes.STRING);
        }

        @Override
        protected boolean canGeneratePermission() {
            return false;
        }

        @Override
        protected void executeSync(CommandContext ctx) {
            perf(ctx, ctx.get(actionArg));
        }
    }

    private static void perf(CommandContext ctx, String arg) {
        boolean on = "on".equalsIgnoreCase(arg);
        boolean off = "off".equalsIgnoreCase(arg);
        if (!on && !off) {
            send(ctx, "Usage: /q perf on | off", "#FFAA00");
            return;
        }
        try {
            Class<?> meter = Class.forName("com.durkz.quantumhy.devperf.PerfMeter");
            meter.getMethod(on ? "start" : "stop").invoke(null);
            send(ctx, "perf meter " + (on ? "on" : "off") + " (check the server log)", "#55FF55");
        } catch (ClassNotFoundException notInBuild) {
            send(ctx, "perf meter is a local-only dev tool and is not included in this build", "#FFAA00");
        } catch (ReflectiveOperationException failed) {
            send(ctx, "perf meter failed: " + failed.getClass().getSimpleName(), "#FF5555");
        }
    }

    private static void status(CommandContext ctx, QuantumHyConfig config) {
        send(ctx, "QuantumHy status", "#55FFFF");
        send(ctx, config.enabled ? "enabled" : "DISABLED via config", config.enabled ? "#55FF55" : "#FF5555");

        double lodRatio = EntityTrackerSystems.LODCull.ENTITY_LOD_RATIO;
        double lodX = lodRatio / EntityTrackerSystems.LODCull.ENTITY_LOD_RATIO_DEFAULT;
        send(ctx, String.format(Locale.ROOT,
                "levers: chunkMin=%d hardCap=%s entityRadius=%s entityMin=%db lodCull=%.2fx",
                config.minClientViewRadius,
                config.targetClientViewRadius > 0 ? String.valueOf(config.targetClientViewRadius) : "off",
                config.adaptEntityRadius, config.minEntityViewBlocks, lodX), "#AAAAAA");
        send(ctx, String.format(Locale.ROOT,
                "density: scan=%dch low=%.1f/ch high=%.1f/ch smoothing=%.2f",
                config.densityScanChunkRadius, config.densityLowPerChunk,
                config.densityHighPerChunk, config.densitySmoothing), "#AAAAAA");
        send(ctx, String.format(Locale.ROOT,
                "entity cull: vertical=%s cap=%s culled(v/cap)=%d/%d",
                config.maxEntityVerticalDistance > 0 ? config.maxEntityVerticalDistance + "b" : "off",
                config.maxVisibleEntitiesPerPlayer > 0 ? String.valueOf(config.maxVisibleEntitiesPerPlayer) : "off",
                EntityCullSystem.VERTICAL_CULLED.sum(), EntityCullSystem.CAP_CULLED.sum()), "#AAAAAA");

        Collection<PlayerRef> online = Universe.get().getPlayers();
        String worldName = "default";
        if (online != null) {
            for (PlayerRef ref : online) {
                if (ref != null && ref.isValid() && ref.getWorldUuid() != null) {
                    World world = Universe.get().getWorld(ref.getWorldUuid());
                    if (world != null) {
                        worldName = world.getName();
                        break;
                    }
                }
            }
        }
        send(ctx, String.format(Locale.ROOT,
                "spawn pause: %s pause=%s pool=%d cooldowns=%d",
                config.holdSpawnOnLoadingChunks ? "on" : "off",
                SpawnStreamPauseSystem.isStreamPauseActive(worldName) ? "on" : "off",
                SpawnStreamPauseSystem.poolCooledCount(worldName),
                SpawnStreamPauseSystem.POOL_COOLDOWNS.sum()), "#AAAAAA");
        send(ctx, String.format(Locale.ROOT,
                "streaming: smooth=%s maxChunks/s=%s maxChunks/tick=%s",
                config.smoothChunkStreaming,
                config.maxChunksPerSecond > 0 ? String.valueOf(config.maxChunksPerSecond) : "default",
                config.maxChunksPerTick > 0 ? String.valueOf(config.maxChunksPerTick) : "default"), "#AAAAAA");

        String lean = config.yieldToLeanCoreViewRadius ? "yielding to LeanCore"
                : LeanCoreBridge.isPresent()
                ? (config.leanCoreTakeover ? "present, took over view radius" : "present, takeover off (may conflict)")
                : "not present (standalone)";
        send(ctx, "LeanCore: " + lean, "#AAAAAA");

        int count = online == null ? 0 : online.size();
        send(ctx, "online players: " + count, "#AAAAAA");
        if (online == null) {
            return;
        }
        for (PlayerRef ref : online) {
            if (ref == null || !ref.isValid()) {
                continue;
            }
            ChunkTracker tracker = ref.getChunkTracker();
            String line = "- " + nameOf(ref);
            if (tracker != null) {
                line += String.format(Locale.ROOT, " chunks loaded=%d loading=%d rate=%d/s",
                        tracker.getLoadedChunksCount(), tracker.getLoadingChunksCount(),
                        tracker.getMaxChunksPerSecond());
            }
            send(ctx, line, "#CCCCCC");
        }
    }

    private static void help(CommandContext ctx) {
        send(ctx, "QuantumHy commands", "#55FFFF");
        send(ctx, "/q status - what QuantumHy is doing right now", "#AAAAAA");
        send(ctx, "/q perf on|off - local dev server performance log (if present in this build)", "#AAAAAA");
        send(ctx, "/q help - this list", "#AAAAAA");
    }

    private static String nameOf(PlayerRef ref) {
        String username = ref.getUsername();
        return username != null && !username.isBlank() ? username : String.valueOf(ref.getUuid());
    }

    private static void send(CommandContext ctx, String text, String colorHex) {
        ctx.sendMessage(Message.raw(text).color(colorHex));
    }
}
