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
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The item-tag task: the item task's sibling for "any of these".
 *
 * <p>A separate type rather than a field because the legacy format has no tag field of its own,
 * so there is no file shape to match and the most-used task keeps its {@code item} required.
 * The consumption rules are the same answer — presence by default, take when told — and so is
 * the shape of this test. Matching itself is a tag query against carried stacks, which needs a
 * player holding things; tag positives need a datapack to bind the tag anyway (see
 * {@code BreakMatchTest}'s note), so the playthrough covers both.
 */
@DisplayName("the item-tag task")
class ItemTagTaskTest {

    @BeforeAll
    static void bootVanilla() {
        MinecraftTestBootstrap.boot();
    }

    private static ItemTagTask task(int count, Optional<Boolean> consume) {
        return new ItemTagTask(new TaskCommon(false, 20),
                ResourceLocation.parse("minecraft:logs"), count, consume, false, false);
    }

    private static QuestTask decoded(String text) {
        return TaskTypes.dispatchCodec().parse(JsonOps.INSTANCE, JsonParser.parseString(text))
                .result().orElseThrow(() -> new AssertionError(text + " did not decode"));
    }

    @Test
    @DisplayName("the requirement is the count the file names")
    void requiredIsTheCount() {
        assertEquals(16, ItemTagTask.BEHAVIOUR.required(task(16, Optional.empty())));
    }

    @Test
    @DisplayName("the button and the wait follow the consume answer, chapter default included")
    void behaviour() {
        ItemTagTask inheriting = task(8, Optional.empty());
        assertFalse(ItemTagTask.BEHAVIOUR.canSubmitByHand(inheriting, false));
        assertFalse(ItemTagTask.BEHAVIOUR.takesResources(inheriting, false));
        assertFalse(ItemTagTask.BEHAVIOUR.waitsForSubmit(inheriting, false));
        assertTrue(ItemTagTask.BEHAVIOUR.canSubmitByHand(inheriting, true));
        assertTrue(ItemTagTask.BEHAVIOUR.takesResources(inheriting, true));
        assertTrue(ItemTagTask.BEHAVIOUR.waitsForSubmit(inheriting, true));

        ItemTagTask consuming = task(8, Optional.of(true));
        assertTrue(ItemTagTask.BEHAVIOUR.canSubmitByHand(consuming, false));
        assertTrue(ItemTagTask.BEHAVIOUR.waitsForSubmit(consuming, false));

        ItemTagTask manual = new ItemTagTask(new TaskCommon(false, 20),
                ResourceLocation.parse("minecraft:logs"), 8, Optional.empty(), false, true);
        assertTrue(ItemTagTask.BEHAVIOUR.manualOnly(manual));
        assertTrue(ItemTagTask.BEHAVIOUR.canSubmitByHand(manual, false));
        assertTrue(ItemTagTask.BEHAVIOUR.waitsForSubmit(manual, false));
        assertFalse(ItemTagTask.BEHAVIOUR.manualOnly(inheriting));

        // current counts carried stacks in the tag and take shrinks them: both need a live
        // server, so the playthrough covers them.
    }

    @Test
    @DisplayName("the row names the tag in words, not in ids")
    void display() {
        var display = TaskTypes.displayOf(task(16, Optional.empty()));

        assertEquals("tenet.task.item_tag", display.label());
        assertEquals("Any Logs", display.labelArg(),
                "the humanized tag, not the author's spelling with its #");
        assertEquals("Hand in Any Logs", display.labelFallback());
        assertEquals(16, display.count());
    }

    @Test
    @DisplayName("a tag task decodes to its tag and its count, defaulting to one")
    void codec() {
        var parsed = assertInstanceOf(ItemTagTask.class, decoded(
                "{\"type\": \"tenet:item_tag\", \"tag\": \"minecraft:logs\", \"count\": 16,"
                        + " \"consumeItems\": true, \"onlyFromCrafting\": true,"
                        + " \"manualOnly\": true}"));
        assertEquals("minecraft:logs", parsed.tag().toString());
        assertEquals(16, parsed.count());
        assertEquals(Optional.of(true), parsed.consumeItems());
        assertTrue(parsed.onlyFromCrafting());
        assertTrue(parsed.manualOnly());

        var plain = assertInstanceOf(ItemTagTask.class,
                decoded("{\"type\": \"tenet:item_tag\", \"tag\": \"minecraft:logs\"}"));
        assertEquals(1, plain.count(), "an absent count means one item");
        assertEquals(Optional.empty(), plain.consumeItems());
        assertFalse(plain.onlyFromCrafting());
        assertFalse(plain.manualOnly());
    }
}
