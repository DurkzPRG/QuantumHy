package com.durkz.quantumhy;

import com.durkz.quantumhy.config.QuantumHyConfig;
import com.durkz.quantumhy.runtime.FpsRuntime;
import com.hypixel.hytale.server.core.event.events.ShutdownEvent;
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

        if (!config.enabled) {
            getLogger().atInfo().log("QuantumHy %s disabled via config (enabled=false).",
                    getManifest().getVersion());
            return;
        }

        runtime = new FpsRuntime(this, config);

        getEventRegistry().registerGlobal(ShutdownEvent.class, e -> {
            if (runtime != null) {
                runtime.shutdown();
            }
        });

        String configDump = String.format(java.util.Locale.ROOT,
                "QuantumHy %s setup. config: verboseLog=%s tickInterval=%ds initialDelay=%ds hardCap=%d min=%d "
                        + "max=%d scan=%d densityLow=%.1f/ch densityHigh=%.1f/ch smoothing=%.2f adaptEntity=%s "
                        + "minEntityBlocks=%d entityLod=%.2fx minDelta=%d streamGrace=%s backlog>=%d "
                        + "leanCoreTakeover=%s yield=%s",
                getManifest().getVersion(), config.verboseLog, config.tickIntervalSeconds,
                config.initialDelaySeconds, config.targetClientViewRadius, config.minClientViewRadius,
                config.maxClientViewRadius, config.densityScanChunkRadius, config.densityLowPerChunk,
                config.densityHighPerChunk, config.densitySmoothing, config.adaptEntityRadius,
                config.minEntityViewBlocks, config.entityLodAggressiveness, config.minViewRadiusDelta,
                config.respectStreamingGrace, config.streamingBacklogThreshold, config.leanCoreTakeover,
                config.yieldToLeanCoreViewRadius);
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
        if (runtime != null) {
            runtime.shutdown();
            runtime = null;
        }
        super.shutdown();
    }
}
