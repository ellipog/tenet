package dev.ellipog.tenet.quest.task;

import com.google.gson.JsonParser;
import com.mojang.serialization.JsonOps;

import dev.ellipog.tenet.quest.MinecraftTestBootstrap;
import dev.ellipog.tenet.quest.QuestTask;
import dev.ellipog.tenet.quest.TaskCommon;

import net.minecraft.resources.ResourceLocation;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

/**
 * The advancement task: earn something, or one criterion of it.
 *
 * <p>Polled every five ticks — an advancement lookup is cheap, and the player expects the book
 * to notice the moment the toast appears. The lookup needs the server's advancement tree and the
 * player's progress in it, so the playthrough covers it; this pins the behaviour's answers, the
 * row's two sentences, and the codec.
 */
@DisplayName("the advancement task")
class AdvancementTaskTest {

    @BeforeAll
    static void bootVanilla() {
        MinecraftTestBootstrap.boot();
    }

    private static AdvancementTask task(Optional<String> criterion) {
        return new AdvancementTask(new TaskCommon(false, 5),
                ResourceLocation.parse("minecraft:story/mine_stone"), criterion);
    }

    private static QuestTask decoded(String text) {
        return TaskTypes.dispatchCodec().parse(JsonOps.INSTANCE, JsonParser.parseString(text))
                .result().orElseThrow(() -> new AssertionError(text + " did not decode"));
    }

    @Test
    @DisplayName("one toast to earn, and no button to press about it")
    void behaviour() {
        AdvancementTask task = task(Optional.empty());

        assertEquals(1, AdvancementTask.BEHAVIOUR.required(task));
        assertFalse(AdvancementTask.BEHAVIOUR.canSubmitByHand(task, false),
                "earning it is the whole task, and it completes on its own");

        // current reads the server's advancement tree and the player's progress in it, so it
        // needs a live server; the playthrough covers it.
    }

    @Test
    @DisplayName("the row names the advancement, or the one criterion it watches")
    void display() {
        var whole = TaskTypes.displayOf(task(Optional.empty()));
        assertEquals("tenet.task.advancement", whole.label());
        assertEquals("minecraft:story/mine_stone", whole.labelArg());
        assertEquals("Earn minecraft:story/mine_stone", whole.labelFallback());
        assertEquals(1, whole.count());

        var part = TaskTypes.displayOf(task(Optional.of("get_stone")));
        assertEquals("get_stone of minecraft:story/mine_stone", part.labelArg(),
                "a task watching one criterion is about that criterion");
    }

    @Test
    @DisplayName("an advancement task decodes to its advancement, criterion optional")
    void codec() {
        var withCriterion = assertInstanceOf(AdvancementTask.class, decoded(
                "{\"type\": \"tenet:advancement\","
                        + " \"advancement\": \"minecraft:story/mine_stone\","
                        + " \"criterion\": \"get_stone\"}"));
        assertEquals("minecraft:story/mine_stone", withCriterion.advancement().toString());
        assertEquals(Optional.of("get_stone"), withCriterion.criterion());
        assertEquals(5, withCriterion.common().autoSubmitTicks(),
                "an absent interval means the type's own: the toast should be noticed at once");

        var whole = assertInstanceOf(AdvancementTask.class, decoded(
                "{\"type\": \"tenet:advancement\","
                        + " \"advancement\": \"minecraft:story/mine_stone\"}"));
        assertEquals(Optional.empty(), whole.criterion(),
                "an absent criterion means the whole advancement");
    }
}
