package com.durkz.quantumhy.client;

import com.hypixel.hytale.protocol.ParticleSpawner;
import com.hypixel.hytale.protocol.ParticleSystem;
import java.lang.foreign.Arena;
import java.lang.foreign.ValueLayout;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

final class CosmeticSmokeDescriptors {
    static final String SOURCE = "Dungeon_Goblin_Smoke_Pipe_Large";
    static final String ALIAS = "QuantumHy_Cosmetic_Smoke_Budget_V1";
    // Fingerprint detached clones: the protocol copy normalizes native animation map implementations.
    private static final String SPAWNER_SHA256 = "9bf76fb39773951733631b04b4418a74457077183f848e606c478e79a4326446";
    private static final String SYSTEM_SHA256 = "6d1c1c1d88e3e46f57e49ccddb35b4e47bd669eb6645cd6d852bf87c57ea9ab8";
    private final ParticleSpawner reduced;
    private final ParticleSystem original;
    private final ParticleSystem replacement;

    private CosmeticSmokeDescriptors(ParticleSpawner spawner, ParticleSystem system) {
        original = system.clone();
        reduced = spawner.clone();
        reduced.id = ALIAS;
        reduced.maxConcurrentParticles = 3;
        replacement = system.clone();
        replacement.spawners[0].spawnerId = ALIAS;
    }

    static CosmeticSmokeDescriptors load() {
        var spawners = com.hypixel.hytale.server.core.asset.type.particle.config.ParticleSpawner.getAssetMap();
        var systems = com.hypixel.hytale.server.core.asset.type.particle.config.ParticleSystem.getAssetMap();
        var spawner = spawners.getAsset(SOURCE);
        var system = systems.getAsset(SOURCE);
        var preloaded = spawners.getAsset(ALIAS);
        if (spawner == null || system == null || preloaded == null) return null;
        var data = verified(spawner.toPacket(), system.toPacket(), SPAWNER_SHA256, SYSTEM_SHA256);
        return data != null && data.matchesPreloaded(preloaded.toPacket()) ? data : null;
    }

    static CosmeticSmokeDescriptors verified(ParticleSpawner spawner, ParticleSystem system,
                                             String spawnerHash, String systemHash) {
        if (spawner == null || system == null) return null;
        var sourceSpawner = spawner.clone();
        var sourceSystem = system.clone();
        if (!SOURCE.equals(sourceSpawner.id) || !SOURCE.equals(sourceSystem.id)
                || sourceSpawner.maxConcurrentParticles != 6 || sourceSystem.spawners == null
                || sourceSystem.spawners.length != 1 || sourceSystem.spawners[0] == null
                || !SOURCE.equals(sourceSystem.spawners[0].spawnerId)
                || !spawnerHash.equals(fingerprint(sourceSpawner)) || !systemHash.equals(fingerprint(sourceSystem))) return null;
        return new CosmeticSmokeDescriptors(sourceSpawner, sourceSystem);
    }

    ParticleSpawner reduced() { return reduced.clone(); }
    ParticleSystem replacement() { return replacement.clone(); }
    ParticleSystem original() { return original.clone(); }
    boolean matchesPreloaded(ParticleSpawner spawner) { return reduced.equals(spawner); }

    static ParticleSystem currentOriginal() {
        var asset = com.hypixel.hytale.server.core.asset.type.particle.config.ParticleSystem.getAssetMap().getAsset(SOURCE);
        if (asset == null) throw new IllegalStateException("Native smoke system unavailable for restoration");
        return asset.toPacket().clone();
    }

    static String fingerprint(ParticleSpawner value) {
        try (var arena = Arena.ofConfined()) {
            var data = arena.allocate(value.computeSize());
            if (value.serialize(data, 0) != data.byteSize()) throw new IllegalStateException("Particle descriptor size changed");
            return digest(data.toArray(ValueLayout.JAVA_BYTE));
        }
    }

    static String fingerprint(ParticleSystem value) {
        try (var arena = Arena.ofConfined()) {
            var data = arena.allocate(value.computeSize());
            if (value.serialize(data, 0) != data.byteSize()) throw new IllegalStateException("Particle descriptor size changed");
            return digest(data.toArray(ValueLayout.JAVA_BYTE));
        }
    }

    private static String digest(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }
}
