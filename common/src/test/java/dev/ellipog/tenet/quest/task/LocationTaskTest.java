package dev.ellipog.tenet.quest.task;

import com.google.gson.JsonParser;
import com.mojang.serialization.JsonOps;

import dev.ellipog.tenet.quest.MinecraftTestBootstrap;
import dev.ellipog.tenet.quest.QuestTask;
import dev.ellipog.tenet.quest.TaskCommon;

import net.minecraft.resources.Identifier;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The location task: stand in a box, in a dimension or in any.
 *
 * <p>The box is inclusive at its minimum corner and exclusive at its maximum, as the legacy is,
 * so a size of one is exactly one block and two adjacent boxes cannot both claim the same
 * block. That rule is pure — answered here — while whose feet are in it needs a player in a
 * level, so the playthrough covers the reading.
 */
@DisplayName("the location task")
class LocationTaskTest {

    @BeforeAll
    static void bootVanilla() {
        MinecraftTestBootstrap.boot();
    }

    private static LocationTask task() {
        return new LocationTask(new TaskCommon(false, 3),
                Optional.of(Identifier.parse("minecraft:overworld")), false,
                List.of(100, 64, -20), List.of(8, 8, 8));
    }

    private static QuestTask decoded(String text) {
        return TaskTypes.dispatchCodec().parse(JsonOps.INSTANCE, JsonParser.parseString(text))
                .result().orElseThrow(() -> new AssertionError(text + " did not decode"));
    }

    @Test
    @DisplayName("one box to stand in, and no button to press about it")
    void behaviour() {
        LocationTask task = task();

        assertEquals(1, LocationTask.BEHAVIOUR.required(task));
        assertFalse(LocationTask.BEHAVIOUR.canSubmitByHand(task, false),
                "standing there is the whole task, and it completes on its own");

        // current asks where a player's feet are and in which dimension, so it needs a live
        // server; the playthrough covers it.
    }

    @Test
    @DisplayName("the minimum corner is in and the maximum is out, on every axis")
    void contains() {
        LocationTask task = task();

        assertTrue(task.contains(100, 64, -20), "the minimum corner is inside");
        assertTrue(task.contains(107, 71, -13), "and so is the last block before the far faces");
        assertFalse(task.contains(108, 64, -20), "the maximum x is already outside");
        assertFalse(task.contains(100, 72, -20), "and so is the top face");
        assertFalse(task.contains(100, 64, -12), "and the far z face");
        assertFalse(task.contains(99, 64, -20), "as is one short of the corner");
        assertFalse(task.contains(0, 64, 0), "and an unrelated spot");
    }

    @Test
    @DisplayName("the row says the box in words")
    void display() {
        var display = TaskTypes.displayOf(task());

        assertEquals("tenet.task.location", display.label());
        assertEquals("the box at 100, 64, -20", display.labelArg(),
                "a location is not an id, so there is nothing for the client to prettify");
        assertEquals("Stand in the box at 100, 64, -20", display.labelFallback());
        assertEquals(1, display.count());
    }

    @Test
    @DisplayName("a location task decodes to its box, defaulting to one block anywhere named")
    void codec() {
        var parsed = assertInstanceOf(LocationTask.class, decoded(
                "{\"type\": \"tenet:location\", \"dimension\": \"minecraft:overworld\","
                        + " \"ignoreDimension\": true,"
                        + " \"position\": [100, 64, -20], \"size\": [8, 8, 8]}"));
        assertEquals(Optional.of("minecraft:overworld"),
                parsed.dimension().map(Object::toString));
        assertTrue(parsed.ignoreDimension());
        assertEquals(List.of(100, 64, -20), parsed.position());
        assertEquals(List.of(8, 8, 8), parsed.size());

        var plain = assertInstanceOf(LocationTask.class, decoded(
                "{\"type\": \"tenet:location\", \"position\": [100, 64, -20]}"));
        assertEquals(List.of(1, 1, 1), plain.size(), "an absent size means exactly one block");
        assertFalse(plain.ignoreDimension());
        assertEquals(Optional.empty(), plain.dimension());
    }
}
