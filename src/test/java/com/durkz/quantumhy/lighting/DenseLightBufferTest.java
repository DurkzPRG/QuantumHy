package com.durkz.quantumhy.lighting;

import com.hypixel.hytale.math.util.ChunkUtil;
import com.hypixel.hytale.server.core.universe.world.chunk.section.ChunkLightDataBuilder;
import org.junit.jupiter.api.Test;

import java.util.SplittableRandom;

import static org.junit.jupiter.api.Assertions.assertEquals;

class DenseLightBufferTest {
    @Test
    void buffersWritesWithoutChangingSourceAndRetainsUntouchedLight() {
        var source = new ChunkLightDataBuilder((short) 4);
        int first = ChunkUtil.indexBlock(2, 7, 19);
        int second = ChunkUtil.indexBlock(31, 0, 5);
        source.setLightRaw(first, (short) 0xf831);
        source.setLightRaw(second, (short) 0xabcd);
        var buffer = new DenseLightBuffer();
        buffer.load(source);
        assertEquals((short) 0xf831, buffer.getLightRaw(2, 7, 19));
        buffer.setLightRaw(first, (short) 0xffff);
        assertEquals((short) 0xf831, source.getLightRaw(first));
        buffer.writeBack(source);
        assertEquals((short) 0xffff, source.getLightRaw(first));
        assertEquals((short) 0xabcd, source.getLightRaw(second));
        buffer.load(source);
        assertEquals((short) 0xabcd, buffer.getLightRaw(31, 0, 5));
        assertEquals(0, buffer.changedCount());
    }

    @Test
    void preservesEveryPackedLightValueAndNativeBuild() {
        DenseLightBuffer buffer = new DenseLightBuffer();
        for (int offset : new int[]{0, 32768}) {
            ChunkLightDataBuilder nativeLight = new ChunkLightDataBuilder((short) 7);
            buffer.load(nativeLight);
            ChunkLightDataBuilder dispatched = buffer;
            for (int index = 0; index < ChunkUtil.SIZE_BLOCKS; index++) {
                dispatched.setLightRaw(index, (short) (index + offset));
            }
            buffer.writeBack(nativeLight);
            var built = nativeLight.build();
            assertEquals(7, built.getChangeId());
            for (int index = 0; index < ChunkUtil.SIZE_BLOCKS; index++) {
                assertEquals((short) (index + offset), built.getLightRaw(index), "cell " + index);
            }
        }
    }

    @Test
    void repeatedWritesMatchNativeStorageAndReloadDiscardsPriorChanges() {
        SplittableRandom random = new SplittableRandom(92341);
        ChunkLightDataBuilder reference = new ChunkLightDataBuilder((short) 3);
        ChunkLightDataBuilder target = new ChunkLightDataBuilder((short) 3);
        DenseLightBuffer buffer = new DenseLightBuffer();
        buffer.load(target);
        for (int i = 0; i < 120_000; i++) {
            int index = random.nextInt(ChunkUtil.SIZE_BLOCKS);
            short value = (short) random.nextInt(65536);
            reference.setLightRaw(index, value);
            buffer.setLightRaw(index, value);
        }
        buffer.writeBack(target);
        for (int index = 0; index < ChunkUtil.SIZE_BLOCKS; index++) {
            assertEquals(reference.getLightRaw(index), target.getLightRaw(index), "cell " + index);
        }
        ChunkLightDataBuilder empty = new ChunkLightDataBuilder((short) 9);
        buffer.load(empty);
        assertEquals(0, buffer.changedCount());
        buffer.writeBack(empty);
        for (int index = 0; index < ChunkUtil.SIZE_BLOCKS; index++) assertEquals(0, buffer.getLightRaw(index));
    }
}
