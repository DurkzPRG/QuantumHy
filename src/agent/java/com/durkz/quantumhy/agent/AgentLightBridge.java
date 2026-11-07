package com.durkz.quantumhy.agent;

import com.durkz.quantumhy.lighting.DenseLightBuffer;
import com.durkz.quantumhy.lighting.NativeLightKernel;
import com.hypixel.hytale.server.core.universe.world.chunk.section.BlockSection;
import com.hypixel.hytale.server.core.universe.world.chunk.section.ChunkLightDataBuilder;
import com.hypixel.hytale.server.core.universe.world.lighting.FloodLightCalculation;

import java.util.BitSet;
import java.util.concurrent.atomic.LongAdder;

public final class AgentLightBridge {
    private static final class Workspace {
        final DenseLightBuffer buffer = new DenseLightBuffer();
        final BitSet queue = new BitSet();
        boolean usable = true;
    }

    private static final ThreadLocal<Workspace> WORK = ThreadLocal.withInitial(Workspace::new);
    private static final boolean VERIFY = Boolean.getBoolean("durkz.quantumhy.agent.verify");
    private static volatile boolean enabled = Boolean.parseBoolean(System.getProperty("durkz.quantumhy.agent.enabled", "true"));
    private static final LongAdder OPTIMIZED = new LongAdder();
    private static final LongAdder VERIFIED = new LongAdder();
    private static final LongAdder FAILURES = new LongAdder();

    private AgentLightBridge() { }

    public static boolean tryPropagate(FloodLightCalculation calculation, BitSet queue, BlockSection section,
                                       ChunkLightDataBuilder light) {
        if (!enabled || queue.cardinality() < 64 || !NativeLightKernel.supported()) return false;
        Workspace work = WORK.get();
        if (!work.usable) return false;
        work.queue.clear();
        work.queue.or(queue);
        try {
            work.buffer.load(light);
            NativeLightKernel.propagate(calculation, work.queue, section, work.buffer);
            if (VERIFY) {
                var reference = new ChunkLightDataBuilder(light.build(), light.getChangeId());
                NativeLightKernel.propagate(calculation, (BitSet) queue.clone(), section, reference);
                for (int index = 0; index < 32768; index++) {
                    if (reference.getLightRaw(index) != work.buffer.getLightRaw(index)) {
                        throw new IllegalStateException("Light mismatch at cell " + index);
                    }
                }
                VERIFIED.increment();
            }
        } catch (VirtualMachineError | ThreadDeath fatal) {
            throw fatal;
        } catch (Throwable failure) {
            work.usable = false;
            FAILURES.increment();
            System.err.println("QuantumHy agent: buffer disabled on this thread; native propagation retained: " + failure);
            return false;
        }
        work.buffer.writeBack(light);
        queue.clear();
        OPTIMIZED.increment();
        return true;
    }

    public static long optimizedSections() { return OPTIMIZED.sum(); }
    public static long verifiedSections() { return VERIFIED.sum(); }
    public static long failures() { return FAILURES.sum(); }
    public static void setEnabled(boolean value) { enabled = value; }
}
