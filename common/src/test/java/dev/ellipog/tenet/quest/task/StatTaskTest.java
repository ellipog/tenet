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
 * The stat task: reach a number of a vanilla statistic.
 *
 * <p>Progress is the stat's own number, clamped to the requirement the way the legacy format
 * clamps it, so a stat in the millions does not overflow a row. Reading it is a map lookup off
 * the player's statistics; a stat this build does not have counts nothing, and the validator
 * already said so at load — that half needs no player and is pinned here, while the lookup
 * itself needs a live server and is covered by the playthrough.
 */
@DisplayName("the stat task")
class StatTaskTest {

    @BeforeAll
    static void bootVanilla() {
        MinecraftTestBootstrap.boot();
    }

    private static StatTask task(String stat, int value) {
        return new StatTask(new TaskCommon(false, 3), Identifier.parse(stat), value);
    }

    private static QuestTask decoded(String text) {
        return TaskTypes.dispatchCodec().parse(JsonOps.INSTANCE, JsonParser.parseString(text))
                .result().orElseThrow(() -> new AssertionError(text + " did not decode"));
    }

    @Test
    @DisplayName("the requirement is the value the file names, and it needs no button")
    void behaviour() {
        StatTask task = task("minecraft:walk_one_cm", 1000);

        assertEquals(1000, StatTask.BEHAVIOUR.required(task));
        assertFalse(StatTask.BEHAVIOUR.canSubmitByHand(task, false),
                "walking is the work, and it completes on its own");

        // current reads a live player's statistics, so it needs a live server; the playthrough
        // covers it.
    }

    @Test
    @DisplayName("a stat this build does not have counts nothing, without touching the player")
    void unknownStatCountsNothing() {
        StatTask missing = task("tenet:no_such_stat", 100);

        assertEquals(0, StatTask.BEHAVIOUR.current(missing, null),
                "the validator reports the typo at the file's line; the task sits visibly"
                        + " incomplete rather than failing the tick");
    }

    @Test
    @DisplayName("the row names the statistic and its number")
    void display() {
        var display = TaskTypes.displayOf(task("minecraft:walk_one_cm", 1000));

        assertEquals("tenet.task.stat", display.label());
        assertEquals("minecraft:walk_one_cm", display.labelArg());
        assertEquals("Reach minecraft:walk_one_cm", display.labelFallback());
        assertEquals(1000, display.count());
    }

    @Test
    @DisplayName("a stat task decodes to its statistic and its value")
    void codec() {
        var parsed = assertInstanceOf(StatTask.class, decoded(
                "{\"type\": \"tenet:stat\", \"stat\": \"minecraft:walk_one_cm\","
                        + " \"value\": 1000}"));
        assertEquals("minecraft:walk_one_cm", parsed.stat().toString());
        assertEquals(1000, parsed.value());
        assertEquals(3, parsed.common().autoSubmitTicks(),
                "an absent interval means the type's own: a stat lookup is cheap");
    }
}
