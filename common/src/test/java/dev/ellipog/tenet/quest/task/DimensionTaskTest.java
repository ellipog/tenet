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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

/**
 * The dimension task: be somewhere else.
 *
 * <p>One flag — in that dimension or not — asked on the type's own slow cadence, because a
 * player does not change dimension between two ticks. There is nothing to confirm, so there is
 * no button; being there is the whole task and it completes on its own. The reading itself needs
 * a player standing in a level, so the playthrough covers it.
 */
@DisplayName("the dimension task")
class DimensionTaskTest {

    @BeforeAll
    static void bootVanilla() {
        MinecraftTestBootstrap.boot();
    }

    private static DimensionTask task(String dimension) {
        return new DimensionTask(new TaskCommon(false, 100),
                Identifier.parse(dimension));
    }

    private static QuestTask decoded(String text) {
        return TaskTypes.dispatchCodec().parse(JsonOps.INSTANCE, JsonParser.parseString(text))
                .result().orElseThrow(() -> new AssertionError(text + " did not decode"));
    }

    @Test
    @DisplayName("one place to be, and no button to press about it")
    void behaviour() {
        DimensionTask task = task("minecraft:the_nether");

        assertEquals(1, DimensionTask.BEHAVIOUR.required(task));
        assertFalse(DimensionTask.BEHAVIOUR.canSubmitByHand(task, false),
                "there is nothing to confirm: being there is the whole task");

        // current reads which dimension a player is standing in, so it needs a live server;
        // the playthrough covers it.
    }

    @Test
    @DisplayName("the row names the dimension it wants")
    void display() {
        var display = TaskTypes.displayOf(task("minecraft:the_nether"));

        assertEquals("tenet.task.dimension", display.label());
        assertEquals("minecraft:the_nether", display.labelArg());
        assertEquals("Visit minecraft:the_nether", display.labelFallback());
        assertEquals(1, display.count());
    }

    @Test
    @DisplayName("a dimension task decodes to its dimension, on the slow cadence by default")
    void codec() {
        var parsed = assertInstanceOf(DimensionTask.class, decoded(
                "{\"type\": \"tenet:dimension\", \"dimension\": \"minecraft:the_nether\"}"));
        assertEquals("minecraft:the_nether", parsed.dimension().toString());
        assertEquals(100, parsed.common().autoSubmitTicks(),
                "an absent interval means the type's own: a dimension changes slowly");
    }
}
