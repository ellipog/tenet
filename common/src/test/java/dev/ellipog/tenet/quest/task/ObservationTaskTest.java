package dev.ellipog.tenet.quest.task;

import com.google.gson.JsonParser;
import com.mojang.serialization.JsonOps;

import dev.ellipog.tenet.quest.MinecraftTestBootstrap;
import dev.ellipog.tenet.quest.QuestTask;
import dev.ellipog.tenet.quest.TaskCommon;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The observation task's behaviour: the half {@code ObservationMatchTest} does not cover.
 *
 * <p>Matching — "does this block answer that filter" — is pure and lives there. This is the
 * rest: the one task the <b>client</b> is trusted to decide. The server never sees the
 * crosshair, so the client ray-traces each tick, counts how long the target has been held, and
 * submits when the timer is done. Current is therefore zero and there is no button — but the
 * client may submit on the player's behalf, which is the one acceptance no other buttonless
 * type grants.
 */
@DisplayName("the observation task's behaviour")
class ObservationTaskTest {

    @BeforeAll
    static void bootVanilla() {
        MinecraftTestBootstrap.boot();
    }

    private static ObservationTask task(int timer) {
        return new ObservationTask(new TaskCommon(false, 20),
                ObservationTask.ObserveType.BLOCK, "minecraft:beacon", timer);
    }

    private static QuestTask decoded(String text) {
        return TaskTypes.dispatchCodec().parse(JsonOps.INSTANCE, JsonParser.parseString(text))
                .result().orElseThrow(() -> new AssertionError(text + " did not decode"));
    }

    @Test
    @DisplayName("one watching, never observed by the server, and no button for it")
    void behaviour() {
        ObservationTask task = task(40);

        assertEquals(1, ObservationTask.BEHAVIOUR.required(task));
        assertEquals(0, ObservationTask.BEHAVIOUR.current(task, null),
                "the client counts the ticks and submits; the server records the submission");
        assertFalse(ObservationTask.BEHAVIOUR.canSubmitByHand(task, false),
                "no button: a press would be a lie about the work");
        assertTrue(ObservationTask.BEHAVIOUR.acceptsClientSubmit(task, false),
                "but the client that did the looking may submit on the player's behalf");
    }

    @Test
    @DisplayName("the timer reads in seconds, one decimal")
    void seconds() {
        assertEquals("2.0", task(40).seconds());
        assertEquals("0.5", task(10).seconds());
    }

    @Test
    @DisplayName("the row names the target and how long to hold it")
    void display() {
        var display = TaskTypes.displayOf(task(40));

        assertEquals("tenet.task.observation", display.label());
        assertEquals("minecraft:beacon for 2.0s", display.labelArg());
        assertEquals("Observe minecraft:beacon for 2.0s", display.labelFallback());
        assertEquals(1, display.count());
    }

    @Test
    @DisplayName("an observation task decodes to its target, watching a block briefly by default")
    void codec() {
        var parsed = assertInstanceOf(ObservationTask.class, decoded(
                "{\"type\": \"tenet:observation\", \"observeType\": \"entity_type\","
                        + " \"toObserve\": \"minecraft:zombie\", \"timer\": 100}"));
        assertEquals(ObservationTask.ObserveType.ENTITY_TYPE, parsed.observeType());
        assertEquals("minecraft:zombie", parsed.toObserve());
        assertEquals(100, parsed.timer());

        var plain = assertInstanceOf(ObservationTask.class, decoded(
                "{\"type\": \"tenet:observation\", \"toObserve\": \"minecraft:beacon\"}"));
        assertEquals(ObservationTask.ObserveType.BLOCK, plain.observeType());
        assertEquals(20, plain.timer(), "an absent timer means a second of looking");
    }
}
