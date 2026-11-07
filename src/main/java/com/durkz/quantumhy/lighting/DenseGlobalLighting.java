package com.durkz.quantumhy.lighting;

import com.hypixel.hytale.server.core.universe.world.chunk.section.BlockSection;
import com.hypixel.hytale.server.core.universe.world.chunk.section.ChunkLightDataBuilder;
import com.hypixel.hytale.server.core.universe.world.lighting.ChunkLightingManager;
import com.hypixel.hytale.server.core.universe.world.lighting.FloodLightCalculation;

import java.util.BitSet;
import java.util.concurrent.atomic.LongAdder;

final class DenseGlobalLighting extends FloodLightCalculation {
    static final LongAdder BUFFERED_SECTIONS = new LongAdder();
    private final DenseLightBuffer buffer = new DenseLightBuffer();
    private final BitSet work = new BitSet();
    private boolean usable = true;

    DenseGlobalLighting(ChunkLightingManager manager) {
        super(manager);
    }

    @Override
    public void propagateCorners(BlockSection section, ChunkLightDataBuilder light, BitSet queue) {
        super.propagateCorners(section, light, queue);
        if (!usable || queue.cardinality() < 64) return;
        // Preserve the caller's queue and light data so a failed attempt can use the native path.
        work.clear();
        work.or(queue);
        try {
            buffer.load(light);
            NativeLightKernel.propagate(this, work, section, buffer);
        } catch (VirtualMachineError | ThreadDeath fatal) {
            throw fatal;
        } catch (Throwable failure) {
            usable = false;
            chunkLightingManager.getWorld().getLogger().atWarning().withCause(failure)
                    .log("QuantumHy dense lighting disabled; continuing with native propagation.");
            return;
        }
        buffer.writeBack(light);
        queue.clear();
        BUFFERED_SECTIONS.increment();
    }
}
