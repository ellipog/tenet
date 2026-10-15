package dev.ellipog.tenet.client;

import dev.ellipog.tenet.client.viewer.MinecraftTestBootstrap;
import dev.ellipog.tenet.client.viewer.RecipeLookups;

import dev.ellipog.tenet.quest.ItemStacks;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Which rows can send the player to a viewer, and which cannot.
 *
 * <p>The rule is the scope of the feature: item tasks and item rewards have an item, a tag task has
 * a tag, and every other row — a checkmark, a stage, an item the client could not resolve, a reward
 * with no item — has nothing to open. Pinned here because the screen that applies it cannot be
 * instantiated by a test, the same reason {@code SidebarLayout} and {@code ItemPicker} carry their
 * rules in game-free classes.
 */
class BookRowTargetsTest {

    @BeforeAll
    static void bootstrap() {
        MinecraftTestBootstrap.boot();
    }

    private static ClientQuestCache.TaskEntry task(ItemStack item, String itemId, String tagId) {
        return new ClientQuestCache.TaskEntry(ItemStack.EMPTY, item, 1, false, false, false, false,
                "tenet:item", "", "", "", itemId, "", "", 0, tagId, List.of(), false, "", "", ItemStack.EMPTY,
                "", "", "");
    }

    private static ClientQuestCache.TaskEntry picturedTask(ItemStack picture) {
        return new ClientQuestCache.TaskEntry(ItemStack.EMPTY, ItemStack.EMPTY, 1, false, false, false,
                false, "tenet:checkmark", "", "", "", "", "", "", 0, "", List.of(), false, "", "", picture,
                "", "", "");
    }

    private static ClientQuestCache.TaskEntry picturedTagTask(String tagId, ItemStack picture) {
        return new ClientQuestCache.TaskEntry(ItemStack.EMPTY, ItemStack.EMPTY, 1, false, false, false,
                false, "tenet:item_tag", "", "", "", "", "", "", 0, tagId, List.of(), false, "", "", picture,
                "", "", "");
    }

    private static ClientQuestCache.TaskEntry breakTask(String block, String blockTag, ItemStack picture) {
        return new ClientQuestCache.TaskEntry(ItemStack.EMPTY, ItemStack.EMPTY, 1, false, false, false,
                false, "tenet:break", "", "", "", "", "", "", 0, "", List.of(), false, "", "", picture,
                "", block, blockTag);
    }

    private static ClientQuestCache.RewardEntry reward(ItemStack item, String itemId) {
        return new ClientQuestCache.RewardEntry(ItemStack.EMPTY, item, 1, "tenet:item", "", "", "",
                itemId, "", false, false, List.of(), false, "", "", ItemStack.EMPTY);
    }

    @Test
    @DisplayName("an item task's target is its item")
    void itemTaskIsAnItemTarget() {
        ItemStack stack = ItemStacks.stackOf(Items.STONE);

        RecipeLookups.Target target = BookRowTargets.ofTask(task(stack, "minecraft:stone", ""));

        assertFalse(target.isTag());
        assertSame(stack, target.item());
    }

    @Test
    @DisplayName("a tag task's target is its tag, parsed from the wire id")
    void tagTaskIsATagTarget() {
        RecipeLookups.Target target = BookRowTargets.ofTask(task(ItemStack.EMPTY, "", "minecraft:logs"));

        assertTrue(target.isTag());
        assertEquals("minecraft:logs", target.tag().location().toString());
    }

    @Test
    @DisplayName("a tag task wearing a picture still opens the tag, not the picture")
    void tagTaskWithPictureIsATagTarget() {
        ItemStack picture = ItemStacks.stackOf(Items.OAK_LOG);

        RecipeLookups.Target target =
                BookRowTargets.ofTask(picturedTagTask("minecraft:logs", picture));

        assertTrue(target.isTag());
        assertEquals("minecraft:logs", target.tag().location().toString());
    }

    @Test
    @DisplayName("a tag task from an old server opens the tag even when the picture rode in the item")
    void oldTagTaskWithPictureInItemIsATagTarget() {
        ItemStack picture = ItemStacks.stackOf(Items.OAK_LOG);

        RecipeLookups.Target target = BookRowTargets.ofTask(task(picture, "minecraft:oak_log", "minecraft:logs"));

        assertTrue(target.isTag());
        assertEquals("minecraft:logs", target.tag().location().toString());
    }

    @Test
    @DisplayName("a picture on a task with no requirement opens nothing")
    void pictureAloneHasNoTarget() {
        assertNull(BookRowTargets.ofTask(picturedTask(ItemStacks.stackOf(Items.TORCH))));
    }

    @Test
    @DisplayName("a tag id that does not parse is no target rather than a throw")
    void malformedTagIdHasNoTarget() {
        assertNull(BookRowTargets.ofTask(task(ItemStack.EMPTY, "", "Not A Tag")));
    }

    @Test
    @DisplayName("a row with neither an item nor a tag has no target")
    void textRowsHaveNoTarget() {
        assertNull(BookRowTargets.ofTask(task(ItemStack.EMPTY, "", "")));
        assertNull(BookRowTargets.ofReward(reward(ItemStack.EMPTY, "")));
    }

    @Test
    @DisplayName("a break task's target is its block's item, not its picture")
    void breakTaskIsABlockTarget() {
        RecipeLookups.Target target = BookRowTargets.ofTask(
                breakTask("minecraft:stone", "", ItemStacks.stackOf(Items.DIAMOND)));

        assertFalse(target.isTag());
        assertEquals(Items.STONE, target.item().getItem(),
                "the requirement wins over the picture, the way a tag task's tag does");
    }

    @Test
    @DisplayName("a break by block tag has no target: a block tag is not an item tag")
    void breakTagHasNoTarget() {
        assertNull(BookRowTargets.ofTask(
                breakTask("", "minecraft:logs", ItemStacks.stackOf(Items.OAK_LOG))),
                "opening one member's recipes would claim the quest is about that member");
    }

    @Test
    @DisplayName("a break of a block with no item form has no target")
    void breakWithoutItemFormHasNoTarget() {
        assertNull(BookRowTargets.ofTask(breakTask("minecraft:water", "", ItemStack.EMPTY)));
    }

    @Test
    @DisplayName("a break id that does not parse is no target rather than a throw")
    void malformedBreakBlockHasNoTarget() {
        assertNull(BookRowTargets.ofTask(breakTask("Not A Block", "", ItemStack.EMPTY)));
    }

    @Test
    @DisplayName("an item reward's target is its item; rewards carry no tags")
    void itemRewardIsAnItemTarget() {
        ItemStack stack = ItemStacks.stackOf(Items.DIAMOND);

        RecipeLookups.Target target = BookRowTargets.ofReward(reward(stack, "minecraft:diamond"));

        assertFalse(target.isTag());
        assertSame(stack, target.item());
    }
}
