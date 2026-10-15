package dev.ellipog.tenet.quest.task;

import com.google.gson.JsonParser;
import com.mojang.serialization.JsonOps;

import dev.ellipog.tenet.quest.ItemStacks;
import dev.ellipog.tenet.quest.MinecraftTestBootstrap;
import dev.ellipog.tenet.quest.QuestTask;
import dev.ellipog.tenet.quest.TaskCommon;

import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The filter task: "hand in whatever this expression matches".
 *
 * <p>Counting, taking and the chapter's consume-items default work exactly as for
 * {@code tenet:item_tag}; what is new is the filter itself, whose parsing {@code FilterParserTest}
 * already pins. So this answers the rest: the behaviour, the leaves against real stacks, the row's
 * sentence, and the codec. Tag positives need a datapack to bind the tag (see
 * {@code BreakMatchTest}'s note), so only the negative tag case is asked here; the inventory walk
 * and the take need a player, so the playthrough covers those.
 */
@DisplayName("the filter task")
class FilterTaskTest {

    @BeforeAll
    static void bootVanilla() {
        MinecraftTestBootstrap.boot();
    }

    private static FilterTask task(String filter, int count, Optional<Boolean> consume) {
        return new FilterTask(new TaskCommon(false, 20),
                FilterParser.parse(filter), count, consume, false);
    }

    private static QuestTask decoded(String text) {
        return TaskTypes.dispatchCodec().parse(JsonOps.INSTANCE, JsonParser.parseString(text))
                .result().orElseThrow(() -> new AssertionError(text + " did not decode"));
    }

    @Test
    @DisplayName("the requirement is the count the file names")
    void requiredIsTheCount() {
        assertEquals(4, FilterTask.BEHAVIOUR.required(task("item(minecraft:coal)", 4,
                Optional.empty())));
    }

    @Test
    @DisplayName("the button and the wait follow the consume answer, chapter default included")
    void behaviour() {
        FilterTask inheriting = task("item(minecraft:coal)", 4, Optional.empty());
        assertFalse(FilterTask.BEHAVIOUR.canSubmitByHand(inheriting, false));
        assertFalse(FilterTask.BEHAVIOUR.takesResources(inheriting, false));
        assertFalse(FilterTask.BEHAVIOUR.waitsForSubmit(inheriting, false));
        assertTrue(FilterTask.BEHAVIOUR.canSubmitByHand(inheriting, true));
        assertTrue(FilterTask.BEHAVIOUR.takesResources(inheriting, true));
        assertTrue(FilterTask.BEHAVIOUR.waitsForSubmit(inheriting, true));

        FilterTask consuming = task("item(minecraft:coal)", 4, Optional.of(true));
        assertTrue(FilterTask.BEHAVIOUR.canSubmitByHand(consuming, false));
        assertTrue(FilterTask.BEHAVIOUR.waitsForSubmit(consuming, false));

        FilterTask manual = new FilterTask(new TaskCommon(false, 20),
                FilterParser.parse("item(minecraft:coal)"), 4, Optional.empty(), true);
        assertTrue(FilterTask.BEHAVIOUR.manualOnly(manual));
        assertTrue(FilterTask.BEHAVIOUR.canSubmitByHand(manual, false));
        assertTrue(FilterTask.BEHAVIOUR.waitsForSubmit(manual, false));

        // current walks a player's inventory and take shrinks its stacks: both need a live
        // server, so the playthrough covers them.
    }

    @Test
    @DisplayName("the leaves match what they name against real stacks")
    void leavesMatch() {
        ItemStack coal = ItemStacks.stackOf(Items.COAL);
        ItemStack stone = ItemStacks.stackOf(Items.STONE);
        ItemStack stick = ItemStacks.stackOf(Items.STICK);

        assertTrue(FilterParser.parse("item(minecraft:coal)").test(coal));
        assertFalse(FilterParser.parse("item(minecraft:coal)").test(stone));
        assertTrue(FilterParser.parse("mod(minecraft)").test(coal));
        assertFalse(FilterParser.parse("mod(othermod)").test(coal));
        assertTrue(FilterParser.parse("block()").test(stone), "stone is a placeable block");
        assertFalse(FilterParser.parse("block()").test(stick), "and a stick is not");
        assertFalse(FilterParser.parse("item(minecraft:coal)").test(ItemStack.EMPTY));
        assertFalse(FilterParser.parse("item_tag(minecraft:tag_no_datapack_declares)")
                        .test(ItemStacks.stackOf(Items.OAK_LOG)),
                "a tag nothing declares matches nothing, whatever the datapack situation");
        assertTrue(FilterParser.parse("not(item(minecraft:coal))").test(stone));
        assertFalse(FilterParser.parse("not(item(minecraft:coal))").test(coal));
    }

    @Test
    @DisplayName("the row says the filter in words")
    void display() {
        var display = TaskTypes.displayOf(task("item(minecraft:coal)", 4, Optional.empty()));

        assertEquals("tenet.task.filter", display.label());
        assertEquals("Coal", display.labelArg());
        assertEquals("Hand in Coal", display.labelFallback());
        assertEquals(4, display.count());
    }

    @Test
    @DisplayName("a filter task decodes to its expression and its count, defaulting to one")
    void codec() {
        var parsed = assertInstanceOf(FilterTask.class, decoded(
                "{\"type\": \"tenet:filter\","
                        + " \"filter\": \"or(item(minecraft:coal)item(minecraft:stone))\","
                        + " \"count\": 4, \"consumeItems\": true, \"manualOnly\": true}"));
        assertEquals("or(item(minecraft:coal)item(minecraft:stone))", parsed.filter().toString());
        assertEquals(4, parsed.count());
        assertEquals(Optional.of(true), parsed.consumeItems());
        assertTrue(parsed.manualOnly());

        var plain = assertInstanceOf(FilterTask.class, decoded(
                "{\"type\": \"tenet:filter\", \"filter\": \"item(minecraft:coal)\"}"));
        assertEquals("item(minecraft:coal)", plain.filter().toString(),
                "the expression survives the trip in its own spelling");
        assertEquals(1, plain.count(), "an absent count means one item");
        assertEquals(Optional.empty(), plain.consumeItems());
        assertFalse(plain.manualOnly());
    }
}
