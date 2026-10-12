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
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The experience task: hand over points, or whole levels.
 *
 * <p>Manual only, as the legacy task is: experience is not something the engine should take the
 * moment a player happens to be carrying enough. The submit button is the consent, and the press
 * takes it. A quest that asks for experience and lets you keep it is not a trade, so taking is
 * unconditional — the chapter's consume-items default is about items. The purse itself belongs
 * to a live player, so the playthrough covers the counting and the taking.
 */
@DisplayName("the experience task")
class XpTaskTest {

    @BeforeAll
    static void bootVanilla() {
        MinecraftTestBootstrap.boot();
    }

    private static XpTask points(int value) {
        return new XpTask(new TaskCommon(false, 20), value, true);
    }

    private static QuestTask decoded(String text) {
        return TaskTypes.dispatchCodec().parse(JsonOps.INSTANCE, JsonParser.parseString(text))
                .result().orElseThrow(() -> new AssertionError(text + " did not decode"));
    }

    @Test
    @DisplayName("the requirement is the value, and the press is the consent under any chapter")
    void behaviour() {
        XpTask task = points(30);

        assertEquals(30, XpTask.BEHAVIOUR.required(task));
        assertTrue(XpTask.BEHAVIOUR.canSubmitByHand(task, false),
                "the press is the consent to be drained");
        assertTrue(XpTask.BEHAVIOUR.takesResources(task, false),
                "handing experience over is the task, chapter default or not");
        assertTrue(XpTask.BEHAVIOUR.takesResources(task, true));
        assertTrue(XpTask.BEHAVIOUR.waitsForSubmit(task, false),
                "so the tick records nothing and the press does");

        // current reads the player's purse and take empties it: both need a live server, so
        // the playthrough covers them.
    }

    @Test
    @DisplayName("the row says points or levels in the task's own unit")
    void display() {
        var points = TaskTypes.displayOf(points(30));
        assertEquals("tenet.task.xp", points.label());
        assertEquals("30 XP", points.labelArg());
        assertEquals("Hand in 30 XP", points.labelFallback());
        assertEquals(30, points.count());

        var levels = TaskTypes.displayOf(new XpTask(new TaskCommon(false, 20), 3, false));
        assertEquals("3 levels", levels.labelArg());
        assertEquals("Hand in 3 levels", levels.labelFallback());
        assertEquals(3, levels.count());
    }

    @Test
    @DisplayName("an experience task decodes to its value, counting points by default")
    void codec() {
        var levels = assertInstanceOf(XpTask.class, decoded(
                "{\"type\": \"tenet:xp\", \"value\": 3, \"points\": false}"));
        assertEquals(3, levels.value());
        assertEquals(false, levels.points());

        var plain = assertInstanceOf(XpTask.class,
                decoded("{\"type\": \"tenet:xp\", \"value\": 30}"));
        assertEquals(30, plain.value());
        assertEquals(true, plain.points(), "an absent unit means points");
    }
}
