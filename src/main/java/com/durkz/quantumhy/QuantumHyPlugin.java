package com.durkz.quantumhy;

import com.durkz.quantumhy.command.QuantumCommand;
import com.durkz.quantumhy.config.QuantumHyConfig;
import com.durkz.quantumhy.runtime.FpsRuntime;
import com.durkz.quantumhy.spawn.SpawnStreamPauseSystem;
import com.durkz.quantumhy.view.EntityCullSystem;
import com.hypixel.hytale.server.core.event.events.ShutdownEvent;
import com.hypixel.hytale.server.core.modules.entity.tracker.EntityTrackerSystems;
import com.hypixel.hytale.server.core.plugin.JavaPlugin;
import com.hypixel.hytale.server.core.plugin.JavaPluginInit;
import org.checkerframework.checker.nullness.compatqual.NonNullDecl;

/** Server-side FPS mod. Trims per-client render load by adapting view radius to nearby density. */
public class QuantumHyPlugin extends JavaPlugin {

    private QuantumHyConfig config;
    private FpsRuntime runtime;

    public QuantumHyPlugin(@NonNullDecl JavaPluginInit init) {
        super(init);
    }

    @Override
    protected void setup() {
        super.setup();

        config = QuantumHyConfig.load(getDataDirectory());

        getCommandRegistry().registerCommand(new QuantumCommand(config));

        if (!config.enabled) {
            getLogger().atInfo().log("QuantumHy %s disabled via config (enabled=false).",
                    getManifest().getVersion());
            return;
        }

        runtime = new FpsRuntime(this, config);

        if (config.maxEntityVerticalDistance > 0 || config.maxVisibleEntitiesPerPlayer > 0) {
            getEntityStoreRegistry().registerSystem(
                    new EntityCullSystem(EntityTrackerSystems.EntityViewer.getComponentType(), config));
        }

        if (config.holdSpawnOnLoadingChunks) {
            getChunkStoreRegistry().registerSystem(new SpawnStreamPauseSystem(config, getLogger()));
        }

        getEventRegistry().registerGlobal(ShutdownEvent.class, e -> {
            if (runtime != null) {
                runtime.shutdown();
            }
        });

        String configDump = String.format(java.util.Locale.ROOT,
                "QuantumHy %s setup. config: verboseLog=%s tickInterval=%ds initialDelay=%ds hardCap=%d min=%d "
                        + "max=%d scan=%d densityLow=%.1f/ch densityHigh=%.1f/ch smoothing=%.2f adaptEntity=%s "
                        + "minEntityBlocks=%d entityLod=%.2fx vCull=%s entityCap=%s spawnHold=%s minDelta=%d streamGrace=%s "
                        + "backlog>=%d smoothStreaming=%s maxChunks/s=%d maxChunks/tick=%d leanCoreTakeover=%s yield=%s "
                        + "pressureGov=%s msptEnter=%.0f effects=%s worldLevers=%s",
                getManifest().getVersion(), config.verboseLog, config.tickIntervalSeconds,
                config.initialDelaySeconds, config.targetClientViewRadius, config.minClientViewRadius,
                config.maxClientViewRadius, config.densityScanChunkRadius, config.densityLowPerChunk,
                config.densityHighPerChunk, config.densitySmoothing, config.adaptEntityRadius,
                config.minEntityViewBlocks, config.entityLodAggressiveness,
                config.maxEntityVerticalDistance > 0 ? config.maxEntityVerticalDistance + "b" : "off",
                config.maxVisibleEntitiesPerPlayer > 0 ? String.valueOf(config.maxVisibleEntitiesPerPlayer) : "off",
                config.holdSpawnOnLoadingChunks,
                config.minViewRadiusDelta,
                config.respectStreamingGrace, config.streamingBacklogThreshold, config.smoothChunkStreaming,
                config.maxChunksPerSecond, config.maxChunksPerTick, config.leanCoreTakeover,
                config.yieldToLeanCoreViewRadius,
                config.pressureGovernorEnabled, config.pressureMsptEnter,
                config.pressureTrimClientEffects, config.pressureWorldLevers);
        getLogger().atInfo().log("%s", configDump);
    }

    @Override
    protected void start() {
        super.start();
        if (runtime != null) {
            runtime.start();
        }
    }

    @Override
    protected void shutdown() {
        stopDevPerfMeterIfPresent();
        if (runtime != null) {
            runtime.shutdown();
            runtime = null;
        }
        super.shutdown();
    }

    /** Stops the local-only dev performance meter so it logs its session average, if it is in this build. */
    private void stopDevPerfMeterIfPresent() {
        try {
            Class.forName("com.durkz.quantumhy.devperf.PerfMeter").getMethod("stop").invoke(null);
        } catch (ClassNotFoundException notInBuild) {
            // Expected in published builds: the dev meter is gitignored and not shipped.
        } catch (ReflectiveOperationException ignored) {
            // Dev tool only; never block shutdown on it.
        }
    }
}
