package com.durkz.quantumhy.client;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ClientInboundBridgeTest {
    private final Map<Integer, Consumer<String>> handlers = new HashMap<>();
    private final List<String> events = new ArrayList<>();

    private ClientInboundBridge.Slot<String> slot(Consumer<String> observer) {
        return new ClientInboundBridge.Slot<>(32, handlers::get, handlers::put, observer,
                failure -> events.add("observer-failure"));
    }

    @Test
    void nativeHandlerReceivesTheSamePacketOnceAfterObservation() {
        String packet = new String("request");
        handlers.put(32, value -> { assertSame(packet, value); events.add("native"); });
        var slot = slot(value -> { assertSame(packet, value); events.add("observer"); });
        slot.install();
        handlers.get(32).accept(packet);
        assertEquals(List.of("observer", "native"), events);
    }

    @Test
    void observationFailureNeverSwallowsTheNativePacket() {
        handlers.put(32, value -> events.add("native"));
        var slot = slot(value -> { throw new IllegalStateException("observer"); });
        slot.install();
        handlers.get(32).accept("request");
        assertEquals(List.of("observer-failure", "native"), events);
    }

    @Test
    void nativeFailureKeepsItsOriginalIdentityAndIsNotRetried() {
        var failure = new IllegalArgumentException("native");
        handlers.put(32, value -> { events.add("native"); throw failure; });
        var slot = slot(value -> events.add("observer"));
        slot.install();
        assertSame(failure, assertThrows(IllegalArgumentException.class, () -> handlers.get(32).accept("request")));
        assertEquals(List.of("observer", "native"), events);
    }

    @Test
    void shutdownRestoresOnlyTheHandlerItOwns() {
        Consumer<String> nativeHandler = value -> events.add("native");
        handlers.put(32, nativeHandler);
        var slot = slot(value -> events.add("observer"));
        slot.install();
        slot.restore();
        slot.restore();
        assertSame(nativeHandler, handlers.get(32));
        handlers.get(32).accept("request");
        assertEquals(List.of("native"), events);
    }

    @Test
    void cleanupPreservesAnotherOwnersReplacement() {
        handlers.put(32, value -> events.add("native"));
        var slot = slot(value -> events.add("observer"));
        slot.install();
        Consumer<String> other = value -> events.add("other");
        handlers.put(32, other);
        slot.restore();
        assertSame(other, handlers.get(32));
    }

    @Test
    void unsupportedHandlerIsLeftUntouched() {
        var slot = slot(value -> fail("Must not install without a native handler"));
        assertThrows(IllegalStateException.class, slot::install);
        assertFalse(handlers.containsKey(32));
    }
}
