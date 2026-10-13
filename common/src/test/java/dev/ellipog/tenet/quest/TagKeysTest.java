package dev.ellipog.tenet.quest;

import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.TagKey;
import net.minecraft.world.item.Item;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tag keys by registry and id: the same pair answers the same object.
 *
 * <p>The call sites are the tick's inventory scans and the death and break events, so a key built
 * per call is one allocation per slot per member per task. What is pinned here is that the memo
 * answers identically — same pair, same object — and distinctly: two ids are two keys, which is
 * what keeps a tag match from answering for its neighbour.
 */
@DisplayName("remembered tag keys")
class TagKeysTest {

    @Test
    @DisplayName("one pair builds once, however often it is asked")
    void samePairSameObject() {
        TagKey<Item> first = TagKeys.of(Registries.ITEM, ResourceLocation.parse("minecraft:logs"));
        assertSame(first, TagKeys.of(Registries.ITEM, ResourceLocation.parse("minecraft:logs")));
        assertEquals(first, TagKey.create(Registries.ITEM, ResourceLocation.parse("minecraft:logs")),
                "the remembered key is the created key, not a lookalike");
    }

    @Test
    @DisplayName("two ids are two keys, and the registry is half the key")
    void distinctPairsAreDistinct() {
        TagKey<Item> logs = TagKeys.of(Registries.ITEM, ResourceLocation.parse("minecraft:logs"));
        TagKey<Item> planks = TagKeys.of(Registries.ITEM, ResourceLocation.parse("minecraft:planks"));
        assertTrue(!logs.equals(planks), "a neighbour's key must not answer");

        TagKey<?> biome = TagKeys.of(Registries.BIOME, ResourceLocation.parse("minecraft:is_forest"));
        TagKey<?> block = TagKeys.of(Registries.BLOCK, ResourceLocation.parse("minecraft:is_forest"));
        assertTrue(!biome.equals(block),
                "the same path in two registries is two keys, which is what keeps a biome tag "
                        + "out of a block match");
    }
}
