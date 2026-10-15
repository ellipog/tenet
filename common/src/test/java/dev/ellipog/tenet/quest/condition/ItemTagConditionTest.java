package dev.ellipog.tenet.quest.condition;

import com.google.gson.JsonParser;
import com.mojang.serialization.JsonOps;

import dev.ellipog.tenet.quest.ItemStacks;
import dev.ellipog.tenet.quest.MinecraftTestBootstrap;
import dev.ellipog.tenet.quest.task.ItemCounting;

import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.tags.TagKey;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

/**
 * The item-tag condition's gate, against a hand-built inventory.
 *
 * <p>Tags are the one thing a bare JVM cannot bind: it has the registries but not the datapack, so
 * every tag query answers false there — and whether the playthrough's server has bound them by the
 * time this class runs is JVM ordering, which a test may not depend on (see
 * {@code ObservationMatchTest}'s note). So the negative case is asked about a tag NO datapack
 * declares, which answers false either way, and the positive case — a bound tag counting its
 * members — is exercised by the playthrough, where datapacks load.
 */
@DisplayName("the item-tag condition")
class ItemTagConditionTest {

    @BeforeAll
    static void bootVanilla() {
        MinecraftTestBootstrap.boot();
    }

    /** The behaviour's answer, with the player unwrap already done. */
    private static boolean meets(Inventory inventory, ItemTagCondition condition) {
        TagKey<Item> tag = TagKey.create(Registries.ITEM, condition.tag());
        return ItemCounting.countIn(inventory, tag, condition.count()) >= condition.count();
    }

    @Test
    @DisplayName("a tag nothing declares matches nothing, whatever is carried")
    void unboundTagMatchesNothing() {
        Inventory inventory = new Inventory(null, new net.minecraft.world.entity.EntityEquipment());
        inventory.setItem(0, ItemStacks.stackOf(Items.OAK_LOG, 64));
        var condition = new ItemTagCondition(
                Identifier.parse("tenet:tag_no_datapack_declares"), 1);

        assertFalse(meets(inventory, condition),
                "sixty-four logs are not one of a tag nothing declares");
    }

    @Test
    @DisplayName("the row names the tag and carries the count, which a condition has no chip for")
    void display() {
        var sixteen = new ItemTagCondition(Identifier.parse("minecraft:logs"), 16);
        var one = new ItemTagCondition(Identifier.parse("minecraft:logs"), 1);

        assertEquals("tenet.condition.item_tag", ItemTagCondition.DISPLAY.apply(sixteen).label());
        assertEquals("Have #minecraft:logs \u00d716",
                ItemTagCondition.DISPLAY.apply(sixteen).labelFallback());
        assertEquals("#minecraft:logs \u00d716", ItemTagCondition.DISPLAY.apply(sixteen).labelArg());
        assertEquals("Have #minecraft:logs", ItemTagCondition.DISPLAY.apply(one).labelFallback(),
                "a single item carries no suffix");
    }

    @Test
    @DisplayName("a tag condition decodes with its count, defaulting to one")
    void codec() {
        var parsed = assertInstanceOf(ItemTagCondition.class, ConditionTypes.dispatchCodec()
                .parse(JsonOps.INSTANCE, JsonParser.parseString(
                        "{\"type\": \"tenet:item_tag\", \"tag\": \"minecraft:logs\", \"count\": 16}"))
                .result().orElseThrow(() -> new AssertionError("did not decode")));
        assertEquals(Identifier.parse("minecraft:logs"), parsed.tag());
        assertEquals(16, parsed.count());

        var plain = assertInstanceOf(ItemTagCondition.class, ConditionTypes.dispatchCodec()
                .parse(JsonOps.INSTANCE,
                        JsonParser.parseString("{\"type\": \"tenet:item_tag\", \"tag\": \"minecraft:logs\"}"))
                .result().orElseThrow(() -> new AssertionError("did not decode")));
        assertEquals(1, plain.count(), "an absent count means one");
        assertEquals(Identifier.parse("tenet:item_tag"), plain.type());
    }
}
