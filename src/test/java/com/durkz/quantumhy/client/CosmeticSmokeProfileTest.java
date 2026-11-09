package com.durkz.quantumhy.client;

import com.hypixel.hytale.protocol.Packet;
import com.hypixel.hytale.protocol.ParticleSpawner;
import com.hypixel.hytale.protocol.ParticleSpawnerGroup;
import com.hypixel.hytale.protocol.ParticleSystem;
import com.hypixel.hytale.protocol.packets.assets.UpdateParticleSpawners;
import com.hypixel.hytale.protocol.packets.assets.UpdateParticleSystems;
import org.junit.jupiter.api.Test;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

class CosmeticSmokeProfileTest {
    private static ParticleSpawner spawner() {
        var value = new ParticleSpawner();
        value.id = CosmeticSmokeDescriptors.SOURCE;
        value.maxConcurrentParticles = 6;
        return value;
    }

    private static ParticleSystem system() {
        var value = new ParticleSystem();
        value.id = CosmeticSmokeDescriptors.SOURCE;
        var group = new ParticleSpawnerGroup();
        group.spawnerId = CosmeticSmokeDescriptors.SOURCE;
        value.spawners = new ParticleSpawnerGroup[] {group};
        return value;
    }

    private static CosmeticSmokeDescriptors descriptors() {
        var a = spawner();
        var b = system();
        return CosmeticSmokeDescriptors.verified(a, b,
                CosmeticSmokeDescriptors.fingerprint(a), CosmeticSmokeDescriptors.fingerprint(b));
    }

    private static CosmeticSmokeProfile profile(CosmeticSmokeDescriptors data) {
        return new CosmeticSmokeProfile(true, () -> data, data::original, message -> { });
    }

    @Test
    void copiesEveryDescriptorAndNeverChangesNativeSources() {
        var a = spawner();
        var b = system();
        var beforeA = a.clone();
        var beforeB = b.clone();
        var data = CosmeticSmokeDescriptors.verified(a, b,
                CosmeticSmokeDescriptors.fingerprint(a), CosmeticSmokeDescriptors.fingerprint(b));
        assertNotNull(data);
        var reduced = data.reduced();
        assertEquals(3, reduced.maxConcurrentParticles);
        reduced.id = a.id;
        reduced.maxConcurrentParticles = 6;
        assertEquals(beforeA, reduced);
        var replacement = data.replacement();
        assertEquals(CosmeticSmokeDescriptors.ALIAS, replacement.spawners[0].spawnerId);
        replacement.spawners[0].spawnerId = b.spawners[0].spawnerId;
        assertEquals(beforeB, replacement);
        assertEquals(beforeA, a);
        assertEquals(beforeB, b);
        data.reduced().maxConcurrentParticles = 100;
        data.replacement().spawners[0].spawnerId = "Foreign";
        data.original().spawners[0].spawnerId = "Foreign";
        assertEquals(3, data.reduced().maxConcurrentParticles);
        assertEquals(CosmeticSmokeDescriptors.ALIAS, data.replacement().spawners[0].spawnerId);
        assertEquals(beforeB, data.original());
    }

    @Test
    void fingerprintUsesTheDetachedCloneFormWhenNativeMapOrderDiffers() {
        var a = spawner();
        a.particle = new com.hypixel.hytale.protocol.Particle();
        a.particle.animationFrames = new java.util.LinkedHashMap<>();
        a.particle.animationFrames.put(100, new com.hypixel.hytale.protocol.ParticleAnimationFrame());
        a.particle.animationFrames.put(0, new com.hypixel.hytale.protocol.ParticleAnimationFrame());
        var b = system();
        String rawHash = CosmeticSmokeDescriptors.fingerprint(a);
        String detachedHash = CosmeticSmokeDescriptors.fingerprint(a.clone());
        assertNotEquals(rawHash, detachedHash);
        assertEquals(a, a.clone());
        assertNotNull(CosmeticSmokeDescriptors.verified(a, b, detachedHash, CosmeticSmokeDescriptors.fingerprint(b.clone())));
        assertNull(CosmeticSmokeDescriptors.verified(a, b, rawHash, CosmeticSmokeDescriptors.fingerprint(b.clone())));
    }

    @Test
    void rejectsChangedDescriptorsAndUnexpectedReferences() {
        var a = spawner();
        var b = system();
        String ah = CosmeticSmokeDescriptors.fingerprint(a), bh = CosmeticSmokeDescriptors.fingerprint(b);
        a.isLowRes = true;
        assertNull(CosmeticSmokeDescriptors.verified(a, b, ah, bh));
        a = spawner();
        b.spawners[0].spawnerId = "CombatEffect";
        assertNull(CosmeticSmokeDescriptors.verified(a, b, ah, CosmeticSmokeDescriptors.fingerprint(b)));
        assertNull(CosmeticSmokeDescriptors.verified(null, b, ah, bh));
    }

    @Test
    void appliesAndRestoresOnlySystemReferencesWithoutRebuildingSpawners() {
        var data = descriptors();
        var profile = profile(data);
        UUID id = UUID.randomUUID();
        Object connection = new Object();
        List<Packet> sent = new ArrayList<>();
        profile.update(id, connection, sent::add, true);
        profile.update(id, connection, sent::add, true);
        assertEquals(1, sent.size());
        var apply = (UpdateParticleSystems) sent.get(0);
        assertEquals(CosmeticSmokeDescriptors.ALIAS, apply.particleSystems.get(CosmeticSmokeDescriptors.SOURCE).spawners[0].spawnerId);
        profile.update(id, connection, sent::add, false);
        profile.update(id, connection, sent::add, false);
        assertEquals(2, sent.size());
        assertEquals(data.original(), ((UpdateParticleSystems) sent.get(1)).particleSystems.get(CosmeticSmokeDescriptors.SOURCE));
        profile.update(id, connection, sent::add, true);
        assertEquals(3, sent.size());
        assertTrue(sent.stream().allMatch(UpdateParticleSystems.class::isInstance));
    }

    @Test
    void disabledUnsupportedAndOptedOutSessionsReceiveNoProfile() {
        AtomicInteger reads = new AtomicInteger();
        var profile = new CosmeticSmokeProfile(false, () -> { reads.incrementAndGet(); return descriptors(); },
                CosmeticSmokeProfileTest::system, message -> { });
        List<Packet> sent = new ArrayList<>();
        UUID id = UUID.randomUUID();
        Object connection = new Object();
        profile.update(id, connection, sent::add, true);
        assertEquals(0, reads.get());
        new CosmeticSmokeProfile(true, () -> null, CosmeticSmokeProfileTest::system, message -> { })
                .update(id, connection, sent::add, true);
        profile(descriptors()).update(id, connection, sent::add, false);
        assertTrue(sent.isEmpty());
    }

    @Test
    void newConnectionGetsItsOwnSelectionAndPlayersRemainIndependent() {
        var profile = profile(descriptors());
        UUID a = UUID.randomUUID(), b = UUID.randomUUID();
        List<Packet> first = new ArrayList<>(), second = new ArrayList<>();
        Object connection = new Object();
        profile.update(a, connection, first::add, true);
        profile.update(b, new Object(), second::add, false);
        assertTrue(second.isEmpty());
        profile.update(a, new Object(), first::add, true);
        assertEquals(2, first.size());
        profile.forget(a);
        profile.update(a, connection, first::add, true);
        assertEquals(3, first.size());
    }

    @Test
    void retainedConnectionKeepsItsAliasAcrossWorldWorkBatches() {
        var profile = profile(descriptors());
        UUID id = UUID.randomUUID();
        Object connection = new Object();
        List<Packet> sent = new ArrayList<>();
        profile.update(id, connection, sent::add, true);
        var playerBetweenWorlds = new com.hypixel.hytale.server.core.universe.PlayerRef(null, id, "Fixture", "en-US", null, null);
        assertNull(playerBetweenWorlds.getWorldUuid());
        profile.retainPlayers(List.of(playerBetweenWorlds));
        profile.update(id, connection, sent::add, true);
        assertEquals(1, sent.size());
        profile.update(id, connection, sent::add, false);
        profile.retainPlayers(List.of(playerBetweenWorlds));
        profile.update(id, connection, sent::add, true);
        assertEquals(3, sent.size());
        assertEquals(0, sent.stream().filter(UpdateParticleSpawners.class::isInstance).count());
        profile.retainPlayers(List.of());
        profile.update(id, connection, sent::add, true);
        assertEquals(4, sent.size());
    }

    @Test
    void partialDeliveryRestoresAndNeverReappliesInFailedSession() {
        var data = descriptors();
        var profile = profile(data);
        UUID id = UUID.randomUUID();
        Object connection = new Object();
        List<Packet> sent = new ArrayList<>();
        AtomicBoolean fail = new AtomicBoolean(true);
        java.util.function.Consumer<Packet> send = packet -> {
            sent.add(packet);
            if (packet instanceof UpdateParticleSystems && fail.getAndSet(false)) throw new IllegalStateException("Partial enqueue");
        };
        profile.update(id, connection, send, true);
        assertEquals(2, sent.size());
        assertEquals(data.original(), ((UpdateParticleSystems) sent.get(1)).particleSystems.get(CosmeticSmokeDescriptors.SOURCE));
        profile.update(id, connection, send, true);
        assertEquals(2, sent.size());
    }

    @Test
    void failedRestorationRetriesAndUsesCurrentNativeDescriptor() {
        var data = descriptors();
        var current = system();
        current.cullDistance = 123;
        var profile = new CosmeticSmokeProfile(true, () -> data, () -> current.clone(), message -> { });
        UUID id = UUID.randomUUID();
        Object connection = new Object();
        List<Packet> sent = new ArrayList<>();
        profile.update(id, connection, sent::add, true);
        profile.update(id, connection, packet -> { throw new IllegalStateException("Disconnected"); }, false);
        profile.update(id, connection, sent::add, true);
        assertEquals(2, sent.size());
        assertEquals(current, ((UpdateParticleSystems) sent.get(1)).particleSystems.get(CosmeticSmokeDescriptors.SOURCE));
    }

    @Test
    void changedNativeAssetsPreventNewApplications() {
        var data = descriptors();
        AtomicBoolean supported = new AtomicBoolean(true);
        var profile = new CosmeticSmokeProfile(true, () -> supported.get() ? data : null, data::original, message -> { });
        UUID id = UUID.randomUUID();
        Object connection = new Object();
        List<Packet> sent = new ArrayList<>();
        profile.update(id, connection, sent::add, true);
        profile.update(id, connection, sent::add, false);
        supported.set(false);
        profile.update(id, connection, sent::add, true);
        assertEquals(2, sent.size());
    }

    @Test
    void rejectsUnexpectedPreloadedSpawnerAndDoesNotShareItsMutableData() {
        var data = descriptors();
        var candidate = data.reduced();
        assertTrue(data.matchesPreloaded(candidate));
        candidate.maxConcurrentParticles = 4;
        assertFalse(data.matchesPreloaded(candidate));
        assertFalse(data.matchesPreloaded(spawner()));
        assertFalse(data.matchesPreloaded(null));
        assertEquals(3, data.reduced().maxConcurrentParticles);
    }

}
