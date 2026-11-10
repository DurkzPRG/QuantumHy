package com.durkz.quantumhy.client;

import com.durkz.quantumhy.config.QuantumHyConfig;
import com.hypixel.hytale.protocol.packets.connection.Pong;
import com.hypixel.hytale.protocol.packets.connection.PongType;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ClientTerrainStrainTest {
    private final AtomicLong clock = new AtomicLong(1_000_000_000L);
    private final ClientStrainMonitor monitor = new ClientStrainMonitor(true, clock::get);
    private final QuantumHyConfig config = new QuantumHyConfig();
    private final UUID player = UUID.randomUUID();

    private void sample(int id, int lagMs, int queue, boolean blocked) {
        var direct = new Pong(); direct.id = id; direct.type = PongType.Direct;
        monitor.onPong(player, direct);
        clock.addAndGet(lagMs * 1_000_000L);
        var tick = new Pong(); tick.id = id; tick.type = PongType.Tick; tick.packetQueueSize = (short) queue;
        monitor.onPong(player, tick);
        monitor.sample(player, blocked, config);
        clock.addAndGet(1_000_000_000L);
    }

    @Test
    void packetCongestionCanRelieveEntitiesWithoutCuttingTerrain() {
        for (int i = 0; i < 8; i++) sample(i, 100, 1000, true);
        assertTrue(monitor.strain(player) > 0);
        assertEquals(0, monitor.terrainStrain(player));
    }

    @Test
    void loopDelayNeedsSustainedEvidenceAndExpiresWithTheSession() {
        sample(0, 100, 0, false);
        assertEquals(0, monitor.terrainStrain(player));
        for (int i = 1; i < 8; i++) sample(i, 100, 0, false);
        assertTrue(monitor.terrainStrain(player) > 0);
        clock.addAndGet(6_000_000_000L);
        monitor.sample(player, false, config);
        assertEquals(0, monitor.terrainStrain(player));
        monitor.forget(player);
        assertEquals(0, monitor.terrainStrain(player));
    }

    @Test
    void loopDelayRecoveryDoesNotOscillateOnASingleGoodSample() {
        for (int i = 0; i < 8; i++) sample(i, 100, 0, false);
        sample(8, 0, 0, false);
        assertTrue(monitor.terrainStrain(player) > 0);
        for (int i = 9; i < 40; i++) sample(i, 0, 0, false);
        assertEquals(0, monitor.terrainStrain(player));
    }
}
