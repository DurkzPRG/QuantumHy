package com.durkz.quantumhy.lighting;

import com.hypixel.hytale.logger.HytaleLogger;
import com.hypixel.hytale.server.core.universe.world.chunk.section.BlockSection;
import com.hypixel.hytale.server.core.universe.world.chunk.section.ChunkLightDataBuilder;
import com.hypixel.hytale.server.core.universe.world.lighting.FloodLightCalculation;

import java.util.BitSet;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.LongAdder;

final class DenseLightPropagation {
    static final LongAdder BUFFERED_SECTIONS = new LongAdder();
    private final DenseLightBuffer buffer = new DenseLightBuffer();
    private final BitSet work = new BitSet();
    private final AtomicBoolean busy = new AtomicBoolean();
    private final HytaleLogger logger;
    private volatile boolean usable = true;

    DenseLightPropagation(HytaleLogger logger) {
        this.logger = logger;
    }

    boolean propagate(FloodLightCalculation calculation, BitSet queue, BlockSection section,
                      ChunkLightDataBuilder light) {
        if (!usable || queue.cardinality() < 64 || !busy.compareAndSet(false, true)) return false;
        try {
            work.clear();
            work.or(queue);
            try {
                buffer.load(light);
                NativeLightKernel.propagate(calculation, work, section, buffer);
            } catch (VirtualMachineError | ThreadDeath fatal) {
                throw fatal;
            } catch (Throwable failure) {
                usable = false;
                logger.atWarning().withCause(failure).log("QuantumHy dense lighting disabled for this calculation; native propagation retained.");
                return false;
            }
            buffer.writeBack(light);
            queue.clear();
            BUFFERED_SECTIONS.increment();
            return true;
        } finally {
            busy.set(false);
        }
    }
}
