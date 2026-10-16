package dev.ellipog.tenet.quest.task;

import com.google.gson.JsonParser;
import com.mojang.serialization.JsonOps;

import dev.ellipog.tenet.quest.ItemRef;
import dev.ellipog.tenet.quest.ItemStacks;
import dev.ellipog.tenet.quest.MinecraftTestBootstrap;
import dev.ellipog.tenet.quest.QuestTask;
import dev.ellipog.tenet.quest.TaskCommon;

import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
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
 * The item task: what it asks for, how closely it looks, and what the row wears.
 *
 * <p>The count lives on the {@link ItemRef}, so the requirement is the file's own number.
 * How closely a carried stack must match is {@link ComponentMatch}, the legacy three modes by
 * their own names, and it is pure — answered here against real stacks. The inventory walk itself
 * ({@code current}) and the take need a player holding things, so a live server answers those;
 * the playthrough covers them.
 */
@DisplayName("the item task")
class ItemTaskTest {

    @BeforeAll
    static void bootVanilla() {
        MinecraftTestBootstrap.boot();
    }

    private static ItemTask task(int count, Optional<Boolean> consume) {
        return new ItemTask(new TaskCommon(false, 20),
                new ItemRef(Identifier.parse("minecraft:oak_log"), count),
                consume, ComponentMatch.STRICT, false, false);
    }

    private static ItemTask manual(int count) {
        return new ItemTask(new TaskCommon(false, 20),
                new ItemRef(Identifier.parse("minecraft:oak_log"), count),
                Optional.empty(), ComponentMatch.STRICT, false, true);
    }

    private static QuestTask decoded(String text) {
        return TaskTypes.dispatchCodec().parse(JsonOps.INSTANCE, JsonParser.parseString(text))
                .result().orElseThrow(() -> new AssertionError(text + " did not decode"));
    }

    @Test
    @DisplayName("the requirement is the count the file names")
    void requiredIsTheCount() {
        assertEquals(8, ItemTask.BEHAVIOUR.required(task(8, Optional.empty())));
        assertEquals(1, ItemTask.BEHAVIOUR.required(task(1, Optional.of(true))));
    }

    @Test
    @DisplayName("none looks at the item alone, fuzzy at the filter's data, strict at the whole stack")
    void matchModes() {
        ItemStack plain = ItemStacks.stackOf(Items.OAK_LOG);
        ItemStack named = ItemStacks.stackOf(Items.OAK_LOG);
        named.set(DataComponents.CUSTOM_NAME, Component.literal("Oakie"));
        ItemStack stone = ItemStacks.stackOf(Items.STONE);

        assertTrue(ComponentMatch.NONE.matches(plain, named), "the item alone is the same item");
        assertTrue(ComponentMatch.FUZZY.matches(plain, named),
                "a filter naming nothing extra is satisfied by anything of the item");
        assertFalse(ComponentMatch.STRICT.matches(plain, named),
                "the whole stack is not the same when one of them is named");
        assertFalse(ComponentMatch.FUZZY.matches(named, plain),
                "a filter naming a name is not satisfied by a stack without it");
        assertTrue(ComponentMatch.NONE.matches(named, plain),
                "but the item alone still is");

        assertFalse(ComponentMatch.NONE.matches(plain, stone), "another item is not this one");
        assertFalse(ComponentMatch.STRICT.matches(plain, stone));
        assertFalse(ComponentMatch.NONE.matches(plain, ItemStack.EMPTY),
                "nothing is never a match");
    }

    @Test
    @DisplayName("the button and the wait follow the consume answer, chapter default included")
    void behaviour() {
        ItemTask inheriting = task(8, Optional.empty());
        assertFalse(ItemTask.BEHAVIOUR.canSubmitByHand(inheriting, false));
        assertFalse(ItemTask.BEHAVIOUR.takesResources(inheriting, false));
        assertFalse(ItemTask.BEHAVIOUR.waitsForSubmit(inheriting, false));
        assertTrue(ItemTask.BEHAVIOUR.canSubmitByHand(inheriting, true),
                "saying nothing inherits the chapter, button and all");
        assertTrue(ItemTask.BEHAVIOUR.takesResources(inheriting, true));
        assertTrue(ItemTask.BEHAVIOUR.waitsForSubmit(inheriting, true));

        ItemTask consuming = task(8, Optional.of(true));
        assertTrue(ItemTask.BEHAVIOUR.canSubmitByHand(consuming, false));
        assertTrue(ItemTask.BEHAVIOUR.takesResources(consuming, false));
        assertTrue(ItemTask.BEHAVIOUR.waitsForSubmit(consuming, false));

        ItemTask keeping = task(8, Optional.of(false));
        assertFalse(ItemTask.BEHAVIOUR.canSubmitByHand(keeping, true));
        assertFalse(ItemTask.BEHAVIOUR.takesResources(keeping, true));

        ItemTask manual = manual(8);
        assertTrue(ItemTask.BEHAVIOUR.manualOnly(manual));
        assertTrue(ItemTask.BEHAVIOUR.canSubmitByHand(manual, false),
                "the press is the only path that measures a manual task");
        assertTrue(ItemTask.BEHAVIOUR.waitsForSubmit(manual, false));
        assertFalse(ItemTask.BEHAVIOUR.manualOnly(inheriting));

        // current walks a player's inventory and take shrinks its stacks: both need a live
        // server holding things, so the playthrough covers them and this pins the answers only.
    }

    @Test
    @DisplayName("the row wears the item itself, and the client names it")
    void display() {
        var display = TaskTypes.displayOf(task(8, Optional.empty()));

        assertEquals("minecraft:oak_log", display.item().orElseThrow().item().toString());
        assertEquals(8, display.count());
        assertEquals("", display.label(), "no sentence: the item's own name is the row");
    }

    @Test
    @DisplayName("an item task decodes flat, defaulting to one strict presence check")
    void codec() {
        var parsed = assertInstanceOf(ItemTask.class, decoded(
                "{\"type\": \"tenet:item\", \"item\": \"minecraft:oak_log\", \"count\": 8,"
                        + " \"consumeItems\": true, \"match\": \"fuzzy\","
                        + " \"onlyFromCrafting\": true, \"manualOnly\": true}"));
        assertEquals("minecraft:oak_log", parsed.item().item().toString());
        assertEquals(8, parsed.item().count());
        assertEquals(Optional.of(true), parsed.consumeItems());
        assertEquals(ComponentMatch.FUZZY, parsed.match());
        assertTrue(parsed.onlyFromCrafting());
        assertTrue(parsed.manualOnly());

        var plain = assertInstanceOf(ItemTask.class,
                decoded("{\"type\": \"tenet:item\", \"item\": \"minecraft:oak_log\"}"));
        assertEquals(1, plain.item().count(), "an absent count means one item");
        assertEquals(Optional.empty(), plain.consumeItems());
        assertEquals(ComponentMatch.STRICT, plain.match(),
                "an absent mode keeps meaning what it meant before the field existed");
        assertFalse(plain.onlyFromCrafting());
        assertFalse(plain.manualOnly());
    }
}
