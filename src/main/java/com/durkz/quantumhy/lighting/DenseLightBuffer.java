package com.durkz.quantumhy.lighting;

import com.hypixel.hytale.math.util.ChunkUtil;
import com.hypixel.hytale.server.core.universe.world.chunk.section.ChunkLightDataBuilder;

import java.util.BitSet;

public final class DenseLightBuffer extends ChunkLightDataBuilder {
    private final short[] values = new short[ChunkUtil.SIZE_BLOCKS];
    private final BitSet loaded = new BitSet(ChunkUtil.SIZE_BLOCKS);
    private final BitSet changed = new BitSet(ChunkUtil.SIZE_BLOCKS);
    private ChunkLightDataBuilder source;

    public DenseLightBuffer() {
        super((short) 0);
    }

    public void load(ChunkLightDataBuilder source) {
        this.source = source;
        loaded.clear();
        changed.clear();
    }

    @Override
    public short getLightRaw(int index) {
        if (!loaded.get(index)) {
            values[index] = source.getLightRaw(index);
            loaded.set(index);
        }
        return values[index];
    }

    @Override
    public short getLightRaw(int x, int y, int z) {
        return getLightRaw(ChunkUtil.indexBlock(x, y, z));
    }

    @Override
    public void setLightRaw(int index, short value) {
        if (getLightRaw(index) != value) {
            values[index] = value;
            changed.set(index);
        }
    }

    public void writeBack(ChunkLightDataBuilder target) {
        for (int index = changed.nextSetBit(0); index >= 0; index = changed.nextSetBit(index + 1)) {
            target.setLightRaw(index, values[index]);
        }
    }

    int changedCount() {
        return changed.cardinality();
    }
}
