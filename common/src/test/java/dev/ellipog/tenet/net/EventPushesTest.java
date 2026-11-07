package dev.ellipog.tenet.net;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

@DisplayName("Event pushes wait for the tick")
class EventPushesTest {

    private static final UUID ONE = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID TWO = UUID.fromString("00000000-0000-0000-0000-000000000002");

    @AfterEach
    void clear() {
        EventPushes.clear();
    }

    @Test
    @DisplayName("a burst of events for one team is one owner for the tick")
    void aBurstIsOneOwner() {
        for (int i = 0; i < 64; i++) {
            EventPushes.add(Set.of(ONE));
        }
        assertEquals(Set.of(ONE), EventPushes.drainWith(Set.of()));
    }

    @Test
    @DisplayName("the tick's own changes and the events' owners are told together, once each")
    void mergedWithTheTick() {
        EventPushes.add(Set.of(ONE));
        EventPushes.add(Set.of(TWO));
        assertEquals(Set.of(ONE, TWO), EventPushes.drainWith(Set.of(ONE)));
    }

    @Test
    @DisplayName("a drain empties it, so the next tick tells nobody again")
    void drainEmpties() {
        EventPushes.add(Set.of(ONE));
        EventPushes.drainWith(Set.of());
        assertEquals(Set.of(), EventPushes.drainWith(Set.of()));
    }

    @Test
    @DisplayName("an event that moved nothing adds nobody")
    void nothingMovedAddsNobody() {
        EventPushes.add(Set.of());
        Set<UUID> changed = Set.of(TWO);
        assertSame(changed, EventPushes.drainWith(changed), "with nothing pending, the tick's set passes through");
    }

    @Test
    @DisplayName("a stopped server forgets what was pending")
    void clearForgets() {
        EventPushes.add(Set.of(ONE));
        EventPushes.clear();
        assertEquals(Set.of(), EventPushes.drainWith(Set.of()));
    }
}
