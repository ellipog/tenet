package dev.ellipog.tenet.quest.reward;

import com.google.gson.JsonParser;
import com.mojang.serialization.JsonOps;

import dev.ellipog.tenet.quest.ItemRef;
import dev.ellipog.tenet.quest.MinecraftTestBootstrap;
import dev.ellipog.tenet.quest.QuestReward;

import net.minecraft.resources.Identifier;
import net.minecraft.world.item.Items;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The item reward: what it hands over, and what its row carries.
 *
 * <p>The grant itself inserts through the loader's transfer and drops the remainder at the
 * player's feet, which needs a live player with an inventory — the playthrough covers that.
 * What is answered here is the pure half beside it: the exact stacks one call hands over.
 */
@DisplayName("the item reward")
class ItemRewardTest {

    @BeforeAll
    static void bootVanilla() {
        MinecraftTestBootstrap.boot();
    }

    private static ItemReward reward(String item, int count) {
        return new ItemReward(RewardCommon.DEFAULT,
                new ItemRef(Identifier.parse(item), count), 0, false);
    }

    private static QuestReward decoded(String text) {
        return RewardTypes.dispatchCodec().parse(JsonOps.INSTANCE, JsonParser.parseString(text))
                .getOrThrow(error -> new AssertionError(text + " did not decode: " + error));
    }

    @Test
    @DisplayName("its type is tenet:item")
    void typeIsTenetItem() {
        assertEquals("tenet:item", reward("minecraft:diamond", 4).type().toString());
    }

    @Test
    @DisplayName("the stacks hand over the count, of the item named")
    void stacksHandOverTheCount() {
        var stacks = ItemReward.stacksToGive(reward("minecraft:diamond", 4), null);

        assertEquals(1, stacks.size());
        assertTrue(stacks.get(0).getItem() == Items.DIAMOND);
        assertEquals(4, stacks.get(0).getCount());
    }

    @Test
    @DisplayName("an item this build cannot resolve gives nothing")
    void anUnknownItemGivesNothing() {
        ItemReward reward = reward("tenet:no_such_item_xyz", 4);

        assertFalse(reward.item().isKnown());
        assertTrue(ItemReward.stacksToGive(reward, null).isEmpty(),
                "reaching here means a reload changed the registries under a loaded quest");
    }

    @Test
    @DisplayName("a count past one stack splits into legal stacks")
    void aLargeCountSplitsIntoLegalStacks() {
        var stacks = ItemReward.stacksToGive(reward("minecraft:diamond", 70), null);

        assertEquals(70, stacks.stream().mapToInt(stack -> stack.getCount()).sum());
        assertTrue(stacks.size() > 1, "seventy diamonds are not one stack");
        assertTrue(stacks.stream().allMatch(stack -> stack.getCount() <= stack.getMaxStackSize()));
    }

    @Test
    @DisplayName("the row carries the item and its count, with no sentence of its own")
    void theRowCarriesTheItem() {
        RewardDisplay display = RewardTypes.displayOf(reward("minecraft:diamond", 4));

        assertEquals("minecraft:diamond", display.item().orElseThrow().item().toString());
        assertEquals(4, display.count());
    }

    @Test
    @DisplayName("an item reward decodes its item, count, bonus and only-one")
    void codec() {
        var parsed = assertInstanceOf(ItemReward.class, decoded(
                "{\"type\": \"tenet:item\", \"item\": \"minecraft:diamond\","
                        + " \"count\": 4, \"randomBonus\": 2, \"onlyOne\": true}"));

        assertEquals("minecraft:diamond", parsed.item().item().toString());
        assertEquals(4, parsed.item().count());
        assertEquals(2, parsed.randomBonus());
        assertTrue(parsed.onlyOne());
    }

    @Test
    @DisplayName("an item reward with nothing but its item means one, with no bonus")
    void codecDefaults() {
        var parsed = assertInstanceOf(ItemReward.class,
                decoded("{\"type\": \"tenet:item\", \"item\": \"minecraft:stone\"}"));

        assertEquals(1, parsed.item().count(), "an absent count means one");
        assertEquals(0, parsed.randomBonus());
        assertFalse(parsed.onlyOne());
    }

    @Test
    @DisplayName("an item reward round-trips through the dispatch codec")
    void codecRoundTrip() {
        var encoded = RewardTypes.dispatchCodec()
                .encodeStart(JsonOps.INSTANCE, reward("minecraft:diamond", 4))
                .getOrThrow(error -> new AssertionError("did not encode: " + error));
        var parsed = assertInstanceOf(ItemReward.class, RewardTypes.dispatchCodec()
                .parse(JsonOps.INSTANCE, encoded)
                .getOrThrow(error -> new AssertionError("did not decode: " + error)));

        assertEquals("minecraft:diamond", parsed.item().item().toString());
        assertEquals(4, parsed.item().count());
    }

    @Test
    @DisplayName("the type is registered, and the grant waits for a live player")
    void behaviourIsRegistered() {
        // The grant inserts through InventoryAccesses and drops the remainder at the player's
        // feet, so there is no grant to run in a bare JVM — the playthrough covers it.
        assertTrue(RewardTypes.behaviourOf(reward("minecraft:diamond", 4)).isPresent());
    }
}
