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
 * The stage task: have a flag.
 *
 * <p>The read half of the flags a pack grants, whose write half is the stage reward, a command
 * or a script. Any resource location is a valid one to ask about — a stage exists by being
 * granted, so a typo shows up as a task that never completes rather than as a file that will
 * not load. Measured, never handed in: whether the flag is set is not something the player can
 * attest to. The flags live on a server, so the playthrough covers the reading.
 */
@DisplayName("the stage task")
class StageTaskTest {

    @BeforeAll
    static void bootVanilla() {
        MinecraftTestBootstrap.boot();
    }

    private static StageTask task(boolean teamStage) {
        return new StageTask(new TaskCommon(false, 20),
                Identifier.parse("example:left_the_village"), teamStage);
    }

    private static QuestTask decoded(String text) {
        return TaskTypes.dispatchCodec().parse(JsonOps.INSTANCE, JsonParser.parseString(text))
                .result().orElseThrow(() -> new AssertionError(text + " did not decode"));
    }

    @Test
    @DisplayName("one flag: you have it or you do not, and no button grants it")
    void behaviour() {
        StageTask task = task(false);

        assertEquals(1, StageTask.BEHAVIOUR.required(task));
        assertFalse(StageTask.BEHAVIOUR.canSubmitByHand(task, false),
                "a button for this would either do nothing or let a player grant their own stages");

        // current asks the server's stage service about a player or their team, so it needs a
        // live server; the playthrough covers it.
    }

    @Test
    @DisplayName("the row names the stage it asks about")
    void display() {
        var display = TaskTypes.displayOf(task(false));

        assertEquals("tenet.task.stage", display.label());
        assertEquals("example:left_the_village", display.labelArg());
        assertEquals("Have the stage example:left_the_village", display.labelFallback());
        assertEquals(1, display.count());
    }

    @Test
    @DisplayName("a stage task decodes to its stage, asking the player by default")
    void codec() {
        var team = assertInstanceOf(StageTask.class, decoded(
                "{\"type\": \"tenet:stage\", \"stage\": \"example:left_the_village\","
                        + " \"teamStage\": true}"));
        assertEquals("example:left_the_village", team.stage().toString());
        assertEquals(true, team.teamStage());

        var plain = assertInstanceOf(StageTask.class,
                decoded("{\"type\": \"tenet:stage\", \"stage\": \"example:left_the_village\"}"));
        assertEquals(false, plain.teamStage(),
                "an absent flag asks about the player's own stages");
    }
}
