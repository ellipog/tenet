package dev.ellipog.tenet.quest;

import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.tags.TagKey;
import net.minecraft.world.item.Item;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * The tag id a player reads instead of parsing: "Any Iron Ores", never "#Ores/iron".
 *
 * <p>Game-free and registry-free by construction — the utility takes ids and tags, not items — so
 * every shape the convention tags actually use is pinned here, including the ones that do not fit
 * the shape: a malformed path must come back as something readable, not as a throw.
 */
class TagLabelsTest {

    private static TagKey<Item> tag(String id) {
        return TagKey.create(Registries.ITEM, Identifier.parse(id));
    }

    @Test
    @DisplayName("a nested tag reads member first: 'Any Iron Ores'")
    void nestedTagsReadMemberFirst() {
        assertEquals("Any Iron Ores", TagLabels.humanize(tag("c:ores/iron")));
        assertEquals("Any Gold Raw Materials", TagLabels.humanize(tag("c:raw_materials/gold")));
    }

    @Test
    @DisplayName("a flat tag is just its name, title-cased")
    void flatTagsAreTheirName() {
        assertEquals("Any Planks", TagLabels.humanize(tag("minecraft:planks")));
        assertEquals("Any Oak Planks", TagLabels.humanize(tag("minecraft:oak_planks")));
    }

    @Test
    @DisplayName("the id overload takes the same path as the tag overload")
    void theIdOverloadAgrees() {
        assertEquals("Any Iron Ores", TagLabels.humanize(Identifier.parse("c:ores/iron")));
    }

    @Test
    @DisplayName("no tag, no id and an empty path are answered, not thrown")
    void edgeCasesAreAnswered() {
        assertEquals("", TagLabels.humanize((TagKey<Item>) null));
        assertEquals("", TagLabels.humanize((Identifier) null));
        assertEquals("#c:", TagLabels.humanize(Identifier.fromNamespaceAndPath("c", "")),
                "an id with no words falls back to the raw id rather than to 'Any '");
        assertEquals("Any Ores", TagLabels.humanize(Identifier.parse("c:ores/")),
                "a trailing slash reads as flat, because the word it has is better than nothing");
        assertEquals("Any C A B", TagLabels.humanize(Identifier.parse("c:a/b/c")),
                "a deeper path keeps every word in the category");
    }
}
