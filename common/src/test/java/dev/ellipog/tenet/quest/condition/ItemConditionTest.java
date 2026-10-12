package dev.ellipog.tenet.quest.condition;

import com.google.gson.JsonParser;
import com.mojang.serialization.JsonOps;

import dev.ellipog.tenet.quest.ItemRef;
import dev.ellipog.tenet.quest.MinecraftTestBootstrap;
import dev.ellipog.tenet.quest.task.ComponentMatch;
import dev.ellipog.tenet.quest.task.ItemCounting;

import net.minecraft.core.component.DataComponents;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.player.Inventory;
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
 * The item condition's gate, against a hand-built inventory.
 *
 * <p>The behaviour answers "have at least this many" through {@link ItemCounting}, the same helper
 * the item task counts with, so a condition can never disagree with the task it guards. That helper
 * takes an inventory, not a player, which is what makes this test possible headless: the behaviour
 * itself only adds the {@code context.player().getInventory()} unwrap, and a bare context has no
 * player to unwrap — passing nulls would NPE on the record access, not answer. So the gate below is
 * the behaviour's body with the unwrap already done, and the playthrough covers the unwrap.
 */
@DisplayName("the item condition")
class ItemConditionTest {

    @BeforeAll
    static void bootVanilla() {
        MinecraftTestBootstrap.boot();
    }

    private static ItemCondition requiring(String item, int count) {
        return new ItemCondition(new ItemRef(ResourceLocation.parse(item), count), ComponentMatch.STRICT);
    }

    private static Inventory stocked(ItemStack... stacks) {
        Inventory inventory = new Inventory(null);
        for (int slot = 0; slot < stacks.length; slot++) {
            inventory.setItem(slot, stacks[slot]);
        }
        return inventory;
    }

    /** The behaviour's answer, with the player unwrap already done. */
    private static boolean meets(Inventory inventory, ItemCondition condition) {
        return ItemCounting.countIn(inventory, condition.item().toStack(), condition.match(),
                condition.item().count()) >= condition.item().count();
    }

    @Test
    @DisplayName("enough of the named item counts, across slots, and one short does not")
    void threshold() {
        Inventory inventory = stocked(new ItemStack(Items.IRON_INGOT, 5), new ItemStack(Items.IRON_INGOT, 3));

        assertTrue(meets(inventory, requiring("minecraft:iron_ingot", 8)), "five and three is eight");
        assertFalse(meets(inventory, requiring("minecraft:iron_ingot", 9)), "and not nine");
    }

    @Test
    @DisplayName("only the named item counts toward the total")
    void onlyTheNamedItem() {
        Inventory inventory = stocked(new ItemStack(Items.DIRT, 64));

        assertFalse(meets(inventory, requiring("minecraft:iron_ingot", 1)),
                "a chest of dirt is not one ingot");
    }

    @Test
    @DisplayName("the match mode decides whether a damaged sword is the plain one")
    void matchMode() {
        ItemStack damaged = new ItemStack(Items.DIAMOND_SWORD);
        damaged.set(DataComponents.DAMAGE, 3);
        Inventory inventory = stocked(damaged);
        ItemRef plain = new ItemRef(ResourceLocation.parse("minecraft:diamond_sword"), 1);

        assertTrue(meets(inventory, new ItemCondition(plain, ComponentMatch.NONE)),
                "none compares the item alone");
        assertFalse(meets(inventory, new ItemCondition(plain, ComponentMatch.STRICT)),
                "strict is the whole stack, so damage disqualifies");
    }

    @Test
    @DisplayName("the row is drawn with the item itself, count and all")
    void display() {
        ItemRef ref = new ItemRef(ResourceLocation.parse("minecraft:iron_ingot"), 8);

        assertEquals(Optional.of(ref), ItemCondition.DISPLAY.apply(requiring("minecraft:iron_ingot", 8)).item());
    }

    @Test
    @DisplayName("an item condition decodes flat, defaulting to one strict item")
    void codec() {
        var parsed = assertInstanceOf(ItemCondition.class, ConditionTypes.dispatchCodec()
                .parse(JsonOps.INSTANCE, JsonParser.parseString(
                        "{\"type\": \"tenet:item\", \"item\": \"minecraft:iron_ingot\", \"count\": 8}"))
                .result().orElseThrow(() -> new AssertionError("did not decode")));
        assertEquals(new ItemRef(ResourceLocation.parse("minecraft:iron_ingot"), 8), parsed.item());
        assertEquals(ComponentMatch.STRICT, parsed.match());

        var plain = assertInstanceOf(ItemCondition.class, ConditionTypes.dispatchCodec()
                .parse(JsonOps.INSTANCE,
                        JsonParser.parseString("{\"type\": \"tenet:item\", \"item\": \"minecraft:stone\"}"))
                .result().orElseThrow(() -> new AssertionError("did not decode")));
        assertEquals(1, plain.item().count(), "an absent count means one");
        assertEquals(ComponentMatch.STRICT, plain.match(), "an absent match is strict, like the task");
        assertEquals(ResourceLocation.parse("tenet:item"), plain.type());
    }
}
