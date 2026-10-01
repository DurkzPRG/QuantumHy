package com.durkz.quantumhy.effects;

import com.durkz.quantumhy.client.ClientStrainMonitor;
import com.durkz.quantumhy.config.PlayerPreferences;
import com.durkz.quantumhy.config.QuantumHyConfig;
import com.hypixel.hytale.math.vector.Transform;
import com.hypixel.hytale.protocol.Packet;
import com.hypixel.hytale.protocol.Position;
import com.hypixel.hytale.protocol.packets.world.PlaySoundEvent3D;
import com.hypixel.hytale.protocol.packets.world.SpawnBlockParticleSystem;
import com.hypixel.hytale.protocol.packets.world.SpawnParticleSystem;
import com.hypixel.hytale.server.core.io.adapter.PlayerPacketFilter;
import com.hypixel.hytale.server.core.universe.PlayerRef;

import javax.annotation.Nullable;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.LongAdder;
import java.util.function.Supplier;

/**
 * Outbound filter for positional particles and 3D sounds, budgeted per player by
 * {@link EffectBudgetPolicy}.
 *
 * Runs on whatever thread writes the packet (world thread, parallel ECS workers, async tasks), so
 * per-player state is a small lock-guarded bucket and nothing is allocated per packet after the
 * first one. Only packets the engine sends through the single-packet {@code write}/{@code writeNoCache}
 * path are filtered: dropping one from the array {@code write} path would leave a null in the batch.
 * As of 0.7 every sender of these three packets uses the single-packet path.
 */
public final class EffectBudgetFilter implements PlayerPacketFilter {

    public static final LongAdder DROPPED = new LongAdder();

    private final Supplier<QuantumHyConfig> settings;
    private final PlayerPreferences preferences;
    private final ClientStrainMonitor strain;
    private final ConcurrentHashMap<UUID, Bucket> buckets = new ConcurrentHashMap<>();

    public EffectBudgetFilter(Supplier<QuantumHyConfig> settings, PlayerPreferences preferences,
                              ClientStrainMonitor strain) {
        this.settings = settings;
        this.preferences = preferences;
        this.strain = strain;
    }

    @Override
    public boolean test(PlayerRef playerRef, Packet packet) {
        Position position = positionOf(packet);
        if (position == null || playerRef == null) {
            return false;
        }
        UUID playerId = playerRef.getUuid();
        if (playerId == null || !preferences.isOptimizationEnabled(playerId)) {
            return false;
        }
        Transform transform = playerRef.getTransform();
        if (transform == null || transform.getPosition() == null) {
            return false;
        }
        QuantumHyConfig config = settings.get();
        double dx = position.x - transform.getPosition().x;
        double dy = position.y - transform.getPosition().y;
        double dz = position.z - transform.getPosition().z;
        double distanceSq = dx * dx + dy * dy + dz * dz;
        double protect = config.effectProtectRadius;
        double soft = config.effectSoftRadius;
        double max = config.effectMaxDistance;
        double rate = EffectBudgetPolicy.rate(config.effectBudgetPerSecond, strain.strain(playerId));

        Bucket bucket = buckets.computeIfAbsent(playerId, ignored -> new Bucket(rate));
        boolean drop = bucket.admit(distanceSq, rate, protect * protect, soft * soft, max > 0 ? max * max : 0.0D);
        if (drop) {
            DROPPED.increment();
        }
        return drop;
    }

    @Nullable
    private static Position positionOf(Packet packet) {
        if (packet instanceof SpawnParticleSystem particle) {
            return particle.position;
        }
        if (packet instanceof PlaySoundEvent3D sound) {
            return sound.position;
        }
        if (packet instanceof SpawnBlockParticleSystem blockParticle) {
            return blockParticle.position;
        }
        return null;
    }

    /** Effects dropped for this player since the last call, for /q status. */
    public long drainDropped(@Nullable UUID playerId) {
        Bucket bucket = playerId == null ? null : buckets.get(playerId);
        return bucket == null ? 0L : bucket.drainDropped();
    }

    public void forget(@Nullable UUID playerId) {
        if (playerId != null) {
            buckets.remove(playerId);
        }
    }

    public void retain(Set<UUID> online) {
        buckets.keySet().retainAll(online);
    }

    public void clear() {
        buckets.clear();
    }

    private static final class Bucket {
        private double tokens;
        private long refilledAtNanos;
        private long droppedSinceRead;

        Bucket(double rate) {
            tokens = rate;
            refilledAtNanos = System.nanoTime();
        }

        synchronized boolean admit(double distanceSq, double rate, double protectSq, double softSq, double maxSq) {
            long now = System.nanoTime();
            tokens = EffectBudgetPolicy.refill(tokens, now - refilledAtNanos, rate);
            refilledAtNanos = now;
            EffectBudgetPolicy.Verdict verdict = EffectBudgetPolicy.decide(
                    distanceSq, tokens, rate, protectSq, softSq, maxSq);
            switch (verdict) {
                case ALLOW -> {
                    tokens -= 1.0D;
                    return false;
                }
                case ALLOW_PROTECTED -> {
                    return false;
                }
                default -> {
                    droppedSinceRead++;
                    return true;
                }
            }
        }

        synchronized long drainDropped() {
            long value = droppedSinceRead;
            droppedSinceRead = 0L;
            return value;
        }
    }
}
