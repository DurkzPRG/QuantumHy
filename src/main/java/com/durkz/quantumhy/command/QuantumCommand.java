package com.durkz.quantumhy.command;

import com.durkz.quantumhy.QuantumHyPlugin;
import com.durkz.quantumhy.config.QuantumHyConfig;
import com.durkz.quantumhy.integration.LeanCoreBridge;
import com.durkz.quantumhy.permissions.QuantumHyPermissions;
import com.durkz.quantumhy.pressure.PressureGovernor;
import com.durkz.quantumhy.runtime.RuntimeSnapshot;
import com.durkz.quantumhy.spawn.SpawnStreamPauseSystem;
import com.durkz.quantumhy.view.EntityCullSystem;
import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.command.system.CommandContext;
import com.hypixel.hytale.server.core.command.system.arguments.system.RequiredArg;
import com.hypixel.hytale.server.core.command.system.arguments.types.ArgTypes;
import com.hypixel.hytale.server.core.command.system.basecommands.AbstractCommandCollection;
import com.hypixel.hytale.server.core.command.system.basecommands.CommandBase;
import com.hypixel.hytale.server.core.modules.entity.tracker.EntityTrackerSystems;
import com.hypixel.hytale.server.core.universe.PlayerRef;

import java.util.Locale;
import java.util.UUID;

public class QuantumCommand extends AbstractCommandCollection {

    public QuantumCommand(QuantumHyPlugin plugin) {
        super("quantumhy", "QuantumHy controls and diagnostics");
        requireNoPermission();
        addAliases("q", "qhy");
        addSubCommand(new StatusSubCommand(plugin));
        addSubCommand(new OptimizeSubCommand(plugin));
        addSubCommand(new ReloadSubCommand(plugin));
        addSubCommand(new HelpSubCommand());
    }

    private static final class StatusSubCommand extends CommandBase {

        private final QuantumHyPlugin plugin;

        StatusSubCommand(QuantumHyPlugin plugin) {
            super("status", "Show QuantumHy status");
            requireNoPermission();
            this.plugin = plugin;
        }

        @Override
        protected void executeSync(CommandContext ctx) {
            if (ctx.sender() instanceof PlayerRef playerRef
                    && !QuantumHyPermissions.isAdmin(playerRef)) {
                personalStatus(ctx, plugin, playerRef);
                return;
            }
            adminStatus(ctx, plugin.activeConfig(), plugin);
        }
    }

    private static final class ReloadSubCommand extends CommandBase {
        private final QuantumHyPlugin plugin;

        ReloadSubCommand(QuantumHyPlugin plugin) {
            super("reload", "Reload performance settings; structural changes require a restart");
            requirePermission(QuantumHyPermissions.ADMIN);
            this.plugin = plugin;
        }

        @Override
        protected void executeSync(CommandContext ctx) {
            if (ctx.sender() instanceof PlayerRef playerRef && !QuantumHyPermissions.isAdmin(playerRef)) {
                send(ctx, "You do not have permission to reload QuantumHy.", "#FF5555");
                return;
            }
            try {
                var result = plugin.reloadConfig();
                send(ctx, result.applied().isEmpty() ? "No live settings changed."
                        : "QuantumHy settings applied: " + String.join(", ", result.applied()), "#55FF55");
                if (!result.restartRequired().isEmpty()) {
                    send(ctx, "Restart required (current values retained): "
                            + String.join(", ", result.restartRequired()), "#FFAA00");
                }
            } catch (java.io.IOException | RuntimeException failed) {
                send(ctx, "Reload rejected; active settings unchanged. " + failed.getMessage(), "#FF5555");
            }
        }
    }

    private static final class OptimizeSubCommand extends CommandBase {

        private final QuantumHyPlugin plugin;
        private final RequiredArg<String> modeArg;

        OptimizeSubCommand(QuantumHyPlugin plugin) {
            super("optimize", "Control QuantumHy for yourself");
            requireNoPermission();
            this.plugin = plugin;
            this.modeArg = withRequiredArg("mode", "on or off", ArgTypes.STRING);
        }

        @Override
        protected void executeSync(CommandContext ctx) {
            if (!(ctx.sender() instanceof PlayerRef playerRef)) {
                send(ctx, "This command can only be used by a player.", "#FF5555");
                return;
            }
            String mode = ctx.get(modeArg).trim().toLowerCase(Locale.ROOT);
            if (!"on".equals(mode) && !"off".equals(mode)) {
                send(ctx, "Usage: /q optimize <on|off>", "#FFAA00");
                return;
            }
            boolean enabled = "on".equals(mode);
            send(ctx, "Checking optimization state on your world...", "#AAAAAA");
            plugin.setOptimizationEnabled(playerRef, enabled).whenComplete((reply, failure) -> {
                if (failure != null) {
                    plugin.getLogger().atWarning().withCause(failure).log("QuantumHy optimization request failed");
                    send(ctx, "Could not finish applying optimization. Use /q status and retry the command.", "#FF5555");
                } else {
                    send(ctx, reply, "#AAAAAA");
                }
            });
        }
    }

    private static final class HelpSubCommand extends CommandBase {

        HelpSubCommand() {
            super("help", "List QuantumHy commands");
            requireNoPermission();
        }

        @Override
        protected void executeSync(CommandContext ctx) {
            send(ctx, "QuantumHy commands", "#55FFFF");
            send(ctx, "/q optimize <on|off> - control optimization for yourself", "#AAAAAA");
            send(ctx, "/q status - show your current QuantumHy status", "#AAAAAA");
            send(ctx, "/q help - this list", "#AAAAAA");
            if (!(ctx.sender() instanceof PlayerRef playerRef) || QuantumHyPermissions.isAdmin(playerRef)) {
                send(ctx, "/q reload - reload performance settings", "#AAAAAA");
                send(ctx, "Admins and console receive full server diagnostics from /q status.", "#999999");
            }
        }
    }

    private static void sendOptimizationState(CommandContext ctx, QuantumHyPlugin plugin, PlayerRef playerRef) {
        boolean enabled = plugin.isOptimizationEnabled(playerRef);
        send(ctx, "QuantumHy optimization is " + (enabled ? "enabled" : "disabled") + " for you.",
                enabled ? "#55FF55" : "#FFAA00");
    }

    private static void personalStatus(CommandContext ctx, QuantumHyPlugin plugin, PlayerRef playerRef) {
        send(ctx, "QuantumHy status", "#55FFFF");
        sendOptimizationState(ctx, plugin, playerRef);
        RuntimeSnapshot.PlayerRow row = findPlayer(plugin.runtimeSnapshot(), playerRef.getUuid());
        if (row == null) {
            send(ctx, "Runtime data: awaiting first pass", "#AAAAAA");
            return;
        }
        send(ctx, String.format(Locale.ROOT,
                "terrain=%d->%d entity=%d->%d visible=%d/%d stream=%s",
                row.terrainCurrent(), row.terrainTarget(), row.entityCurrent(), row.entityTarget(),
                row.visualVisible(), row.visualCandidates(), row.streamTier()), "#CCCCCC");
        send(ctx, "decision: " + row.decisionLine(), "#999999");
    }

    private static void adminStatus(CommandContext ctx, QuantumHyConfig config, QuantumHyPlugin plugin) {
        send(ctx, "QuantumHy server status", "#55FFFF");
        send(ctx, config.enabled ? "enabled" : "DISABLED via config", config.enabled ? "#55FF55" : "#FF5555");

        double lodRatio = EntityTrackerSystems.LODCull.ENTITY_LOD_RATIO;
        double lodX = lodRatio / EntityTrackerSystems.LODCull.ENTITY_LOD_RATIO_DEFAULT;
        send(ctx, String.format(Locale.ROOT,
                "levers: terrain=%s emergency=%s chunkMin=%d hardCap=%s entityRadius=%s entityMin=%db lodCull=%.2fx",
                config.adaptiveTerrainViewEnabled ? "adaptive" : "preserved",
                config.emergencyTerrainTrimEnabled ? "armed" : "off",
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

        RuntimeSnapshot snap = plugin.runtimeSnapshot();
        PressureGovernor.Snapshot pressure = snap.worlds().values().stream()
                .map(RuntimeSnapshot.WorldRow::pressure)
                .filter(PressureGovernor.Snapshot::pressured)
                .findFirst()
                .orElseGet(() -> snap.worlds().values().stream()
                        .map(RuntimeSnapshot.WorldRow::pressure)
                        .findFirst().orElse(PressureGovernor.Snapshot.idle()));
        send(ctx, String.format(Locale.ROOT,
                "pressure: %s enter=%.0fms exit=%.0fms effects=%s worldLevers=%s",
                config.pressureGovernorEnabled ? PressureGovernor.formatStatus(pressure) : "disabled",
                config.pressureMsptEnter, config.pressureMsptExit,
                config.pressureTrimClientEffects ? "on" : "off",
                config.pressureWorldLevers ? "on" : "off"), "#AAAAAA");
        send(ctx, String.format(Locale.ROOT,
                "spawn pause: %s poolCooldowns=%d streaming=%s catchUp=%s owner=%s",
                config.holdSpawnOnLoadingChunks ? "on" : "off",
                SpawnStreamPauseSystem.POOL_COOLDOWNS.sum(), config.smoothChunkStreaming,
                config.streamCatchUpEnabled, LeanCoreBridge.chunkRateOwnerLabel(config)), "#AAAAAA");
        send(ctx, "LeanCore: state=" + LeanCoreBridge.ownership()
                + " view=" + LeanCoreBridge.viewRadiusOwnerLabel(config), "#AAAAAA");
        send(ctx, "online players: " + snap.onlineCount(), "#AAAAAA");
        if (snap.players().isEmpty()) {
            send(ctx, "runtime data: awaiting first pass", "#AAAAAA");
            return;
        }
        for (RuntimeSnapshot.PlayerRow row : snap.players()) {
            send(ctx, String.format(Locale.ROOT,
                    "- %s world=%s chunks=%d/%d rate=%d/%d tier=%s | %s",
                    row.name(), row.worldName(), row.chunksLoaded(), row.chunksLoading(),
                    row.maxChunksPerSecond(), row.maxChunksPerTick(), row.streamTier(),
                    row.decisionLine()), "#CCCCCC");
        }
    }

    private static RuntimeSnapshot.PlayerRow findPlayer(RuntimeSnapshot snapshot, UUID playerId) {
        if (playerId == null) {
            return null;
        }
        for (RuntimeSnapshot.PlayerRow row : snapshot.players()) {
            if (playerId.equals(row.playerId())) {
                return row;
            }
        }
        return null;
    }

    private static void send(CommandContext ctx, String text, String colorHex) {
        ctx.sendMessage(Message.raw(text).color(colorHex));
    }
}
