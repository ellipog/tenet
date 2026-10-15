package dev.ellipog.tenet.client.viewer;

import net.minecraft.resources.Identifier;
import net.minecraft.world.item.ItemStack;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The item-to-quest lookup every viewer reads: roles kept apart, order kept stable, one quest once.
 *
 * <p>No Minecraft is booted: an index entry needs a {@link Identifier} and a {@link QuestRef},
 * and a reference's icon is allowed to be {@link ItemStack#EMPTY} -- which is also the shape a pack
 * with a missing item produces, so the empty-stack case is exercised rather than avoided.
 */
class ItemQuestIndexTest {

    @BeforeAll
    static void bootstrap() {
        // An empty ItemStack still reads a registry key while its class initialises.
        MinecraftTestBootstrap.boot();
    }

    private static final Identifier STONE = Identifier.parse("minecraft:stone");
    private static final Identifier DIAMOND = Identifier.parse("minecraft:diamond");

    private static QuestRef quest(String id) {
        return new QuestRef(id, "Quest " + id, "Chapter", ItemStack.EMPTY, "");
    }

    @Test
    @DisplayName("questsUsing returns only the quests that require the item, in insertion order")
    void usesAreOnlyRequiredEntries() {
        ItemQuestIndex index = ItemQuestIndex.builder()
                .add(STONE, quest("a"), true, false)
                .add(DIAMOND, quest("b"), false, true)
                .add(STONE, quest("c"), true, false)
                .build();

        assertEquals(List.of("a", "c"), index.questsUsing(STONE).stream().map(QuestRef::id).toList());
        assertTrue(index.questsUsing(DIAMOND).isEmpty(),
                "b awards the diamond and does not use it");
    }

    @Test
    @DisplayName("questsAwarding returns only the quests that award the item")
    void awardsAreOnlyAwardedEntries() {
        ItemQuestIndex index = ItemQuestIndex.builder()
                .add(DIAMOND, quest("a"), true, false)
                .add(DIAMOND, quest("b"), false, true)
                .build();

        assertEquals(List.of("b"), index.questsAwarding(DIAMOND).stream().map(QuestRef::id).toList());
    }

    /**
     * The case that was broken, and the way it was found.
     *
     * <p>The awarding list used to be built by a pass that kept entries whose {@code required} was
     * <b>false</b>, which reads "awards and does not require" — so a quest that hands in eight logs and
     * gives back a stack of them was missing from the output side of every viewer, which is the one
     * place a player looks for what an item leads to. Nothing else caught it because the combined
     * {@code questsFor} happened to stay right: its required pass had already added the quest.
     *
     * <p>It surfaced from a test asserting the boolean pair agreed with the lists — a test written for a
     * different reason entirely, which is the argument for asserting agreement rather than each answer
     * on its own.
     */
    @Test
    @DisplayName("a quest that both requires and awards an item is on both sides, and once in the combined list")
    void aQuestWithBothRolesIsOnBothSides() {
        ItemQuestIndex index = ItemQuestIndex.builder()
                .add(STONE, quest("both"), true, true)
                .build();

        assertEquals(List.of("both"), index.questsUsing(STONE).stream().map(QuestRef::id).toList());
        assertEquals(List.of("both"), index.questsAwarding(STONE).stream().map(QuestRef::id).toList(),
                "the awarding pass kept 'awards and does not require', so this quest had no output side");
        assertEquals(List.of("both"), index.questsFor(STONE).stream().map(QuestRef::id).toList(),
                "and the combined list still names it once");
    }

    @Test
    @DisplayName("questsFor puts the required quests first and never lists one twice")
    void forIsRequiredFirstAndDeduped() {
        ItemQuestIndex index = ItemQuestIndex.builder()
                .add(STONE, quest("both"), true, true)
                .add(STONE, quest("uses"), true, false)
                .add(STONE, quest("awards"), false, true)
                .build();

        assertEquals(List.of("both", "uses", "awards"),
                index.questsFor(STONE).stream().map(QuestRef::id).toList(),
                "the quest that both uses and awards the item appears once, in the required pass");
    }

    @Test
    @DisplayName("an item nobody references answers empty, and items() holds exactly the keys")
    void unknownItemsAndTheKeySet() {
        ItemQuestIndex index = ItemQuestIndex.builder()
                .add(STONE, quest("a"), true, false)
                .build();

        assertTrue(index.questsFor(DIAMOND).isEmpty());
        assertTrue(index.questsUsing(DIAMOND).isEmpty());
        assertTrue(index.questsAwarding(DIAMOND).isEmpty());
        assertEquals(java.util.Set.of(STONE), index.items());
        assertFalse(index.isEmpty());
        assertTrue(ItemQuestIndex.empty().isEmpty(),
                "the empty index is the one an adapter reads before the tree arrives");
        assertTrue(ItemQuestIndex.empty().questsFor(STONE).isEmpty());
    }

    @Test
    @DisplayName("an entry with neither role is a caller bug and is refused")
    void anEntryWithNeitherRoleIsRefused() {
        assertThrows(IllegalArgumentException.class,
                () -> new ItemQuestIndex.Entry(STONE, quest("a"), false, false),
                "an entry that neither requires nor awards would only ever answer 'no quests'");
    }

    /**
     * The boolean pair a viewer asks in bulk.
     *
     * <h2>What these have to agree with, and why that is the whole test</h2>
     *
     * <p>{@code uses} and {@code awards} answer the same question the collected lists do, asked without
     * building anything — a viewer asks once per ingredient it is deciding whether to show a tab for.
     * The two answers are derived from one walk of one map, so the risk is not that they disagree with
     * each other but that they drift from the lists: a set that said yes where the list said no would
     * offer a player a tab that opens onto nothing.
     */
    @Test
    @DisplayName("the boolean pair agrees with the lists, for every case the lists have")
    void membershipAgreesWithTheLists() {
        ItemQuestIndex index = ItemQuestIndex.builder()
                .add(STONE, quest("both"), true, true)
                .add(STONE, quest("uses"), true, false)
                .add(DIAMOND, quest("awards"), false, true)
                .build();

        assertTrue(index.uses(STONE), "STONE is required by two quests");
        assertTrue(index.awards(STONE), "and awarded by the one that does both -- the case a viewer's "
                + "two tabs both have to light up for");
        assertFalse(index.uses(DIAMOND), "the diamond is only ever awarded");
        assertTrue(index.awards(DIAMOND));

        assertEquals(!index.questsUsing(STONE).isEmpty(), index.uses(STONE));
        assertEquals(!index.questsAwarding(STONE).isEmpty(), index.awards(STONE));
        assertEquals(!index.questsUsing(DIAMOND).isEmpty(), index.uses(DIAMOND));
        assertEquals(!index.questsAwarding(DIAMOND).isEmpty(), index.awards(DIAMOND));
    }

    @Test
    @DisplayName("an item nobody references answers no to both, and the empty index answers no to everything")
    void membershipOfNothing() {
        ItemQuestIndex index = ItemQuestIndex.builder()
                .add(STONE, quest("a"), true, false)
                .build();
        Identifier unknown = Identifier.parse("minecraft:iron_ingot");

        assertFalse(index.uses(unknown), "the common case, and the one worth never allocating for");
        assertFalse(index.awards(unknown));
        assertFalse(ItemQuestIndex.empty().uses(STONE), "an adapter reads this before the tree arrives");
        assertFalse(ItemQuestIndex.empty().awards(STONE));
    }
}
