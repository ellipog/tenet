package dev.ellipog.tenet.client.viewer;

import dev.ellipog.tenet.quest.ItemStacks;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.tags.TagKey;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The decision a row press goes through, and the delivery when it passes.
 *
 * <p>The chain itself — which viewer owns this client — is {@code ViewersTest}'s; this is the second
 * half of the seam: whether a target can be opened at all, and that the installed lookup receives
 * exactly what the row named. A tag is the case worth pinning: a viewer that cannot open one must
 * refuse it rather than open an arbitrary member of it.
 */
class RecipeLookupsTest {

    @BeforeAll
    static void bootstrap() {
        MinecraftTestBootstrap.boot();
    }

    private static TagKey<Item> logs() {
        return TagKey.create(Registries.ITEM, Identifier.fromNamespaceAndPath("minecraft", "logs"));
    }

    /** A lookup that records what it was asked for, and answers the tag question however it is told. */
    private static final class Fake implements RecipeLookups.Lookup {

        private final boolean tags;
        private final List<RecipeLookups.Target> opened = new ArrayList<>();

        Fake(boolean tags) {
            this.tags = tags;
        }

        @Override
        public void recipesFor(RecipeLookups.Target target) {
            opened.add(target);
        }

        @Override
        public boolean supportsTags() {
            return tags;
        }
    }

    @Test
    @DisplayName("an item target needs a lookup and an item, and a tag also needs a viewer that has tags")
    void theDecisionIsTheFeature() {
        Fake noTags = new Fake(false);
        Fake withTags = new Fake(true);
        RecipeLookups.Target item = RecipeLookups.Target.of(ItemStacks.stackOf(Items.STONE));
        RecipeLookups.Target tag = RecipeLookups.Target.ofTag(logs());

        assertFalse(RecipeLookups.accepts(null, item), "no viewer installed means nothing to open");
        assertFalse(RecipeLookups.accepts(noTags, null), "no target means nothing to open");
        assertFalse(RecipeLookups.accepts(noTags, RecipeLookups.Target.of(ItemStack.EMPTY)),
                "an unresolved item is not a target");
        assertTrue(RecipeLookups.accepts(noTags, item));

        assertFalse(RecipeLookups.accepts(noTags, tag),
                "a viewer with no tag path must refuse a tag rather than pick a member");
        assertTrue(RecipeLookups.accepts(withTags, tag));
    }

    @Test
    @DisplayName("the installed lookup receives exactly the row's target")
    void openDeliversTheTarget() {
        Fake lookup = new Fake(true);
        RecipeLookups.install(lookup);
        ItemStack stack = ItemStacks.stackOf(Items.STONE);

        assertTrue(RecipeLookups.open(RecipeLookups.Target.of(stack)));
        assertTrue(RecipeLookups.open(RecipeLookups.Target.ofTag(logs())));

        assertEquals(2, lookup.opened.size());
        assertFalse(lookup.opened.get(0).isTag());
        assertSame(stack, lookup.opened.get(0).item());
        assertTrue(lookup.opened.get(1).isTag());
        assertEquals("minecraft:logs", lookup.opened.get(1).tag().location().toString());
    }

    @Test
    @DisplayName("a press that cannot be answered is refused, and nothing reaches the lookup")
    void openRefusesWhatItCannotOpen() {
        Fake lookup = new Fake(false);
        RecipeLookups.install(lookup);

        assertFalse(RecipeLookups.open(RecipeLookups.Target.ofTag(logs())),
                "a viewer without tags does not get the call");
        assertFalse(RecipeLookups.open(RecipeLookups.Target.of(ItemStack.EMPTY)));
        assertTrue(lookup.opened.isEmpty());

        assertFalse(RecipeLookups.canOpen(RecipeLookups.Target.ofTag(logs())),
                "and the hint is gated on the same answer");
        assertTrue(RecipeLookups.canOpen(RecipeLookups.Target.of(ItemStacks.stackOf(Items.STONE))));
    }

    @Test
    @DisplayName("a target carries exactly one of an item and a tag")
    void aTargetIsOneOfTwo() {
        ItemStack stack = ItemStacks.stackOf(Items.STONE);

        RecipeLookups.Target item = RecipeLookups.Target.of(stack);
        assertFalse(item.isTag());
        assertSame(stack, item.item());
        assertFalse(item.isEmpty());

        RecipeLookups.Target tag = RecipeLookups.Target.ofTag(logs());
        assertTrue(tag.isTag());
        assertSame(logs(), tag.tag());
        assertFalse(tag.isEmpty());

        assertTrue(RecipeLookups.Target.of(ItemStack.EMPTY).isEmpty());
    }
}
