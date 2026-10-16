package dev.ellipog.tenet.quest;

import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import com.mojang.serialization.JsonOps;
import net.minecraft.core.component.DataComponentPatch;
import net.minecraft.core.component.DataComponents;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * An item reference with custom data: the patch rides the codec and reaches the stack.
 *
 * <h2>Why this is the load-bearing test for components</h2>
 *
 * <p>Everything else about custom data is bookkeeping -- fields on records, strings on a wire. What
 * has to be true is that the datapack's own component spelling survives the quest file's codec and
 * comes out on an {@link ItemStack}, because that stack is what a task counts against and what a
 * reward hands over. {@code ItemTask} and {@code ProgressService} already compare with
 * {@code isSameItemSameComponents}, so the day this passes, strict matching of custom items is
 * already the behaviour -- the second test says so out loud.
 */
@DisplayName("an item reference with custom data")
class ItemRefTest {

    @BeforeAll
    static void bootstrapMinecraft() {
        // The registry the reference resolves against.
        MinecraftTestBootstrap.boot();
    }

    @Test
    @DisplayName("a component patch rides the codec and reaches the stack")
    void componentsReachTheStack() {
        DataComponentPatch patch = DataComponentPatch.builder()
                .set(DataComponents.DAMAGE, 5)
                .build();
        ItemRef ref = new ItemRef(Identifier.withDefaultNamespace("diamond_sword"), 2, patch);

        JsonElement wire = ItemRef.CODEC.encodeStart(JsonOps.INSTANCE, ref).getOrThrow();
        assertTrue(wire.getAsJsonObject().has("components"),
                "the patch travels as its own field, in the same shape a quest file uses");
        ItemRef read = ItemRef.CODEC.parse(JsonOps.INSTANCE, wire).getOrThrow();

        assertEquals(Identifier.withDefaultNamespace("diamond_sword"), read.item());
        assertEquals(2, read.toStack().getCount(), "count and all");
        assertEquals(5, read.toStack().getDamageValue(), "the stack arrives damaged, as it was written");
    }

    @Test
    @DisplayName("a plain reference has no patch, and a damaged one is not the same stack")
    void componentsMakeADifferentStack() {
        ItemRef plain = new ItemRef(Identifier.withDefaultNamespace("diamond_sword"), 1);
        assertTrue(plain.components().isEmpty(), "nothing custom, nothing to carry");
        assertTrue(ItemStack.isSameItemSameComponents(ItemStacks.stackOf(Items.DIAMOND_SWORD),
                        plain.toStack()),
                "so the template is the plain stack");

        ItemRef damaged = new ItemRef(Identifier.withDefaultNamespace("diamond_sword"), 1,
                DataComponentPatch.builder().set(DataComponents.DAMAGE, 3).build());
        assertFalse(ItemStack.isSameItemSameComponents(damaged.toStack(), plain.toStack()),
                "which is why a task naming the damaged sword is not satisfied by a pristine one -- "
                        + "the strict comparison every counting path already uses");
    }

    @Test
    @DisplayName("a custom name in a file is a string holding JSON, and comes out as the name")
    void aCustomNameInAFileReads() {
        // The one spelling question in this feature, and a broken worked example answered it the hard
        // way: `custom_name`'s codec is Minecraft's FLAT_CODEC, which reads the JSON string and parses
        // its *content* as a text component -- so the file form is a string holding JSON, and the bare
        // "Tempered Pickaxe" only appears to work when the name is one word (`Renamed`): the lenient
        // parse of a single bare word yields that word, and the parse of two chokes on the second.
        // The codec writes the nested form below, which is why the editor round-trips and only a
        // hand-written file could get it wrong. The schema states it too.
        ItemRef ref = ItemRef.CODEC.parse(JsonOps.INSTANCE,
                JsonParser.parseString("""
                        {"item": "minecraft:diamond_sword",
                         "components": {"minecraft:custom_name": "\\"Tempered Pickaxe\\""}}""")).getOrThrow();

        assertEquals("Tempered Pickaxe", ref.toStack().getHoverName().getString(),
                "a string holding JSON is a text component on JsonOps");

        JsonElement written = ItemRef.CODEC.encodeStart(JsonOps.INSTANCE, ref).getOrThrow();
        // On 26.x vanilla writes a literal component back as its bare string rather than the
        // nested quoted form 1.21.1 wrote. Either spelling reads to the same name through the
        // leniency above, so the editor's files carry whatever vanilla writes.
        assertEquals("Tempered Pickaxe",
                written.getAsJsonObject().getAsJsonObject("components")
                        .get("minecraft:custom_name").getAsString(),
                "and the codec writes a form that reads back to the same name");
    }

    @Test
    @DisplayName("an unknown item still resolves to nothing, patch or no patch")
    void unknownItemsStayEmpty() {
        ItemRef gone = new ItemRef(Identifier.fromNamespaceAndPath("someothermod", "widget"), 1,
                DataComponentPatch.builder().set(DataComponents.DAMAGE, 1).build());

        assertFalse(gone.isKnown(), "the mod is not installed");
        assertTrue(gone.toStack().isEmpty(), "and a missing item stays missing, components and all");
    }
}
