package com.durkz.quantumhy.lighting;

import com.hypixel.hytale.server.core.universe.world.chunk.section.BlockSection;
import com.hypixel.hytale.server.core.universe.world.chunk.section.ChunkLightData;
import com.hypixel.hytale.server.core.universe.world.chunk.section.ChunkLightDataBuilder;
import com.hypixel.hytale.server.core.universe.world.lighting.FloodLightCalculation;

import java.io.IOException;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.BitSet;
import java.util.HexFormat;

public final class NativeLightKernel {
    private static final MethodHandle PROPAGATE = resolve();

    private NativeLightKernel() { }

    public static boolean supported() {
        return PROPAGATE != null;
    }

    public static void propagate(FloodLightCalculation calculation, BitSet queue, BlockSection section,
                          ChunkLightDataBuilder light) throws Throwable {
        PROPAGATE.invokeExact(calculation, queue, section, light);
    }

    private static MethodHandle resolve() {
        try {
            // The private call sequence and buffer access must match the verified engine binary.
            if (!matches(FloodLightCalculation.class, "87cd68d50a14bb1fd4e9eee00fd9a271e6eface8fc52b94e577c07aabd2c9c65")
                    || !matches(ChunkLightDataBuilder.class, "2bab827d9036405b77b0811b3cdb0d7863a80c1f684595a54898f9db8acb240b")
                    || !matches(ChunkLightData.class, "67cb67a433dce89e14acf5d39767bb60dada09ebad501e20d758298dff0cbd17")) {
                return null;
            }
            return MethodHandles.privateLookupIn(FloodLightCalculation.class, MethodHandles.lookup()).findVirtual(
                    FloodLightCalculation.class, "propagateLight",
                    MethodType.methodType(void.class, BitSet.class, BlockSection.class, ChunkLightDataBuilder.class));
        } catch (IOException | NoSuchAlgorithmException | ReflectiveOperationException | SecurityException failure) {
            return null;
        }
    }

    private static boolean matches(Class<?> type, String expected) throws IOException, NoSuchAlgorithmException {
        try (var input = type.getResourceAsStream(type.getSimpleName() + ".class")) {
            if (input == null) return false;
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(input.readAllBytes());
            return HexFormat.of().formatHex(digest).equals(expected);
        }
    }
}
