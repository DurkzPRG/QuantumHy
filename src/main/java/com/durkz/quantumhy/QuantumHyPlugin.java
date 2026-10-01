package com.durkz.quantumhy;

import com.durkz.quantumhy.command.QuantumCommand;
import com.durkz.quantumhy.config.QuantumHyConfig;
import com.durkz.quantumhy.config.LiveConfig;
import com.durkz.quantumhy.config.PlayerPreferences;
import com.durkz.quantumhy.permissions.QuantumHyPermissions;
import com.durkz.quantumhy.runtime.FpsRuntime;
import com.durkz.quantumhy.runtime.RuntimeSnapshot;
import com.durkz.quantumhy.spawn.SpawnStreamPauseSystem;
import com.durkz.quantumhy.update.ModUpdateChecker;
import com.durkz.quantumhy.view.EntityCullSystem;
import com.hypixel.hytale.protocol.packets.setup.ViewRadius;
import com.hypixel.hytale.server.core.io.adapter.PacketAdapters;
import com.hypixel.hytale.server.core.io.adapter.PacketFilter;
import com.hypixel.hytale.server.core.io.adapter.PlayerPacketWatcher;
import com.hypixel.hytale.server.core.event.events.ShutdownEvent;
import com.hypixel.hytale.server.core.event.events.player.AddPlayerToWorldEvent;
import com.hypixel.hytale.server.core.event.events.player.PlayerDisconnectEvent;
import com.hypixel.hytale.server.core.modules.entity.tracker.EntityTrackerSystems;
import com.hypixel.hytale.server.core.plugin.JavaPlugin;
import com.hypixel.hytale.server.core.plugin.JavaPluginInit;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import org.checkerframework.checker.nullness.compatqual.NonNullDecl;

/** Server-side FPS mod. Trims per-client render load by adapting view radius to nearby density. */
public class QuantumHyPlugin extends JavaPlugin {

    private QuantumHyConfig config;
    private LiveConfig liveConfig;
    private PlayerPreferences playerPreferences;
    private FpsRuntime runtime;
    private PacketFilter viewRadiusWatcher;

    public QuantumHyPlugin(@NonNullDecl JavaPluginInit init) {
        super(init);
    }

    @Override
    protected void setup() {
        super.setup();

        config = QuantumHyConfig.load(getDataDirectory());
        liveConfig = new LiveConfig(config);
        playerPreferences = PlayerPreferences.load(getDataDirectory());

        QuantumHyPermissions.register();
        getCommandRegistry().registerCommand(new QuantumCommand(this));

        if (!config.enabled) {
            getLogger().atInfo().log("QuantumHy %s disabled via config (enabled=false).",
                    getManifest().getVersion());
            return;
        }

        runtime = new FpsRuntime(this, config, playerPreferences);

        getEntityStoreRegistry().registerSystem(
                new EntityCullSystem(EntityTrackerSystems.EntityViewer.getComponentType(), liveConfig,
                        playerPreferences));

        if (config.clientRenderCapEnabled) {
            FpsRuntime capRuntime = runtime;
            viewRadiusWatcher = PacketAdapters.registerInbound((PlayerPacketWatcher) (playerRef, packet) -> {
                if (packet instanceof ViewRadius viewRadius && playerRef != null) {
                    capRuntime.renderCap().onClientRequest(playerRef.getUuid(), viewRadius.value);
                }
            });
        }

        if (config.holdSpawnOnLoadingChunks) {
            getChunkStoreRegistry().registerSystem(new SpawnStreamPauseSystem(liveConfig, getLogger()));
        }

        getEventRegistry().registerGlobal(ShutdownEvent.class, e -> {
            if (runtime != null) {
                runtime.shutdown();
            }
        });
        getEventRegistry().registerGlobal(AddPlayerToWorldEvent.class, event -> {
            PlayerRef playerRef = event.getHolder().getComponent(PlayerRef.getComponentType());
            if (playerRef != null) {
                EntityCullSystem.forget(playerRef.getUuid());
                com.durkz.quantumhy.view.VisualLoadRegistry.remove(playerRef.getUuid());
                if (runtime != null) {
                    runtime.onWorldJoin(playerRef.getUuid());
                }
            }
            ModUpdateChecker.getInstance().notifyPlayer(playerRef);
        });
        getEventRegistry().registerGlobal(PlayerDisconnectEvent.class, event -> {
            PlayerRef playerRef = event.getPlayerRef();
            ModUpdateChecker.getInstance().forgetPlayer(playerRef.getUuid());
            if (runtime != null) {
                runtime.forgetPlayer(playerRef.getUuid());
            }
        });

        String configDump = String.format(java.util.Locale.ROOT,
                "QuantumHy %s setup. config: verboseLog=%s tickInterval=%ds initialDelay=%ds hardCap=%d min=%d "
                        + "max=%d scan=%d densityLow=%.1f/ch densityHigh=%.1f/ch ringWeight=%s baseline=%.0f%% "
                        + "terrainAdaptive=%s terrainEmergency=%s chunkLoadShrink=%s smoothing=%.2f adaptEntity=%s "
                        + "minEntityBlocks=%d entityLod=%.2fx vCull=%s entityCap=%s spawnHold=%s minDelta=%d streamGrace=%s "
                        + "backlog>=%d smoothStreaming=%s catchUp=%s catchUpRate=%d/%d hold=%dms maxChunks/s=%d maxChunks/tick=%d leanCoreTakeover=%s yield=%s "
                        + "pressureGov=%s msptEnter=%.0f exitLast=%s effects=%s worldLevers=%s "
                        + "expand=%d shrink=%d entExpand=%d hyst=%d budget=%dms",
                getManifest().getVersion(), config.verboseLog, config.tickIntervalSeconds,
                config.initialDelaySeconds, config.targetClientViewRadius, config.minClientViewRadius,
                config.maxClientViewRadius, config.densityScanChunkRadius, config.densityLowPerChunk,
                config.densityHighPerChunk,
                config.densityRingWeighting ? String.format(java.util.Locale.ROOT, "edge=%.2f",
                        config.densityRingEdgeWeight) : "off",
                config.baselineShrinkFraction * 100.0D,
                config.adaptiveTerrainViewEnabled, config.emergencyTerrainTrimEnabled,
                config.chunkLoadShrinkEnabled ? config.chunkLoadLowChunks + "-" + config.chunkLoadHighChunks : "off",
                config.densitySmoothing, config.adaptEntityRadius,
                config.minEntityViewBlocks, config.entityLodAggressiveness,
                config.maxEntityVerticalDistance > 0 ? config.maxEntityVerticalDistance + "b" : "off",
                config.maxVisibleEntitiesPerPlayer > 0 ? String.valueOf(config.maxVisibleEntitiesPerPlayer) : "off",
                config.holdSpawnOnLoadingChunks,
                config.minViewRadiusDelta,
                config.respectStreamingGrace, config.streamingBacklogThreshold, config.smoothChunkStreaming,
                config.streamCatchUpEnabled ? "on" : "off",
                config.streamCatchUpPerSecond, config.streamCatchUpPerTick, config.streamCatchUpHoldMs,
                config.maxChunksPerSecond, config.maxChunksPerTick, config.leanCoreTakeover,
                config.yieldToLeanCoreViewRadius,
                config.pressureGovernorEnabled, config.pressureMsptEnter,
                config.pressureExitRequiresLastTick,
                config.pressureTrimClientEffects, config.pressureWorldLevers,
                config.maxExpandChunksPerPass, config.maxShrinkChunksPerPass,
                config.maxExpandEntityBlocksPerPass, config.expandHysteresisPasses,
                config.worldPassBudgetMs);
        getLogger().atInfo().log("%s", configDump);
    }

    /** Last adaptive-pass snapshot for /q (no live server re-walk). */
    public RuntimeSnapshot runtimeSnapshot() {
        FpsRuntime active = runtime;
        return active == null ? RuntimeSnapshot.EMPTY : active.snapshot();
    }

    public QuantumHyConfig activeConfig() {
        return liveConfig.get();
    }

    public LiveConfig.ReloadResult reloadConfig() throws java.io.IOException {
        return liveConfig.reload(getDataDirectory().resolve("QuantumHy.json"));
    }

    public boolean isOptimizationEnabled(PlayerRef playerRef) {
        return playerPreferences == null || playerPreferences.isOptimizationEnabled(
                playerRef == null ? null : playerRef.getUuid());
    }

    public java.util.concurrent.CompletableFuture<String> setOptimizationEnabled(PlayerRef playerRef, boolean enabled) {
        if (playerPreferences == null || playerRef == null || playerRef.getUuid() == null) {
            return java.util.concurrent.CompletableFuture.completedFuture("Player session unavailable; try again after joining a world.");
        }
        if (runtime == null) {
            return java.util.concurrent.CompletableFuture.completedFuture(
                    "QuantumHy is disabled in the server configuration; a restart is required to enable it.");
        }
        return runtime.optimizationChanged(playerRef, enabled);
    }

    @Override
    protected void start() {
        super.start();
        ModUpdateChecker.getInstance().start(this, config);
        if (runtime != null) {
            runtime.start();
        }
    }

    @Override
    protected void shutdown() {
        stopDevPerfMeterIfPresent();
        ModUpdateChecker.getInstance().shutdown();
        if (viewRadiusWatcher != null) {
            try {
                PacketAdapters.deregisterInbound(viewRadiusWatcher);
            } catch (IllegalArgumentException alreadyGone) {
            }
            viewRadiusWatcher = null;
        }
        if (runtime != null) {
            runtime.shutdown();
            runtime = null;
        }
        super.shutdown();
    }

    private void stopDevPerfMeterIfPresent() {
        try {
            Class.forName("com.durkz.quantumhy.devperf.PerfMeter").getMethod("stop").invoke(null);
        } catch (ClassNotFoundException notInBuild) {
        } catch (ReflectiveOperationException ignored) {
        }
    }
}
