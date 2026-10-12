package dev.ellipog.tenet.quest.task;

import com.google.gson.JsonParser;
import com.mojang.serialization.JsonOps;

import dev.ellipog.tenet.quest.MinecraftTestBootstrap;
import dev.ellipog.tenet.quest.QuestTask;
import dev.ellipog.tenet.quest.QuestText;
import dev.ellipog.tenet.quest.TaskCommon;

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
 * The checkmark task: a button, and a promise.
 *
 * <p>Required is one and current is always zero, which is what makes it manual-only without a
 * special case in the engine: no amount of observing the world will ever satisfy it, so only a
 * submit advances it. The words live in the common title, so the display is the author's own
 * sentence — or nothing, when the author wrote nothing.
 */
@DisplayName("the checkmark task")
class CheckmarkTaskTest {

    @BeforeAll
    static void bootVanilla() {
        MinecraftTestBootstrap.boot();
    }

    private static CheckmarkTask titled(String title) {
        return new CheckmarkTask(new TaskCommon(false, 20, List.of(), false,
                Optional.of(QuestText.literal(title)), Optional.empty(), List.of()));
    }

    private static QuestTask decoded(String text) {
        return TaskTypes.dispatchCodec().parse(JsonOps.INSTANCE, JsonParser.parseString(text))
                .result().orElseThrow(() -> new AssertionError(text + " did not decode"));
    }

    @Test
    @DisplayName("one is asked, none is ever observed, and the press is the only path")
    void behaviour() {
        CheckmarkTask task = titled("Did you read the sign?");

        assertEquals(1, CheckmarkTask.BEHAVIOUR.required(task));
        assertEquals(0, CheckmarkTask.BEHAVIOUR.current(task, null),
                "nothing observable: the tick can never satisfy this");
        assertTrue(CheckmarkTask.BEHAVIOUR.canSubmitByHand(task, false),
                "a checkmark is only ever a press");
        assertFalse(CheckmarkTask.BEHAVIOUR.takesResources(task, false),
                "and it takes nothing, so the press waits for no consent");
        assertFalse(CheckmarkTask.BEHAVIOUR.waitsForSubmit(task, false),
                "the press moves it because the count is zero, not because the tick is held back");
    }

    @Test
    @DisplayName("the row wears the author's own words, or nothing when there are none")
    void display() {
        var titled = TaskTypes.displayOf(titled("Did you read the sign?"));
        assertEquals("Did you read the sign?", titled.label());
        assertEquals(1, titled.count());

        var bare = TaskTypes.displayOf(new CheckmarkTask(new TaskCommon(false, 20)));
        assertEquals("", bare.label(),
                "a checkmark that says nothing draws a bare button, not an invented sentence");
        assertEquals(1, bare.count());
    }

    @Test
    @DisplayName("a checkmark decodes to nothing but its common settings")
    void codec() {
        var bare = assertInstanceOf(CheckmarkTask.class,
                decoded("{\"type\": \"tenet:checkmark\"}"));
        assertEquals(Optional.empty(), bare.common().title());

        var titled = assertInstanceOf(CheckmarkTask.class, decoded(
                "{\"type\": \"tenet:checkmark\", \"title\": \"Did you read the sign?\"}"));
        assertEquals("Did you read the sign?",
                titled.common().title().orElseThrow().value());
    }
}
