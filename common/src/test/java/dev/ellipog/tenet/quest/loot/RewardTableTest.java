package dev.ellipog.tenet.quest.loot;

import com.mojang.serialization.JsonOps;

import dev.ellipog.tenet.quest.ItemRef;
import dev.ellipog.tenet.quest.MinecraftTestBootstrap;
import dev.ellipog.tenet.quest.QuestReward;
import dev.ellipog.tenet.quest.reward.ItemReward;
import dev.ellipog.tenet.quest.reward.RewardCommon;
import dev.ellipog.tenet.quest.reward.TableReward;
import dev.ellipog.tenet.quest.reward.XpReward;

import net.minecraft.resources.Identifier;
import net.minecraft.util.RandomSource;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The reward table's roll, with a seeded source rather than a playtest.
 *
 * <p>The distribution properties are arithmetic — guaranteed entries, the number of throws, the
 * empty band — and a seeded {@link RandomSource} makes each one reproducible. What a playtest can
 * check is that the numbers feel right; what these check is that the rules are the ones written.
 */
@DisplayName("the reward table's roll")
class RewardTableTest {

    @BeforeAll
    static void bootVanilla() {
        MinecraftTestBootstrap.boot();
    }

    private static ItemReward item(String id) {
        return new ItemReward(RewardCommon.DEFAULT,
                new ItemRef(Identifier.withDefaultNamespace(id), 1), 0, false);
    }

    private static RewardTable table(double emptyWeight, int lootSize, RewardTable.Entry... entries) {
        return new RewardTable(emptyWeight, lootSize, List.of(entries));
    }

    private static RewardTable.Entry entry(double weight, QuestReward reward) {
        return new RewardTable.Entry(weight, reward);
    }

    private static long countOf(List<QuestReward> rewards, ItemReward wanted) {
        return rewards.stream().filter(reward -> reward == wanted).count();
    }

    @Test
    @DisplayName("a weight-zero entry is always granted, exactly once, whatever the dice say")
    void weightZeroIsAlwaysGranted() {
        ItemReward guaranteed = item("stone");
        ItemReward weighted = item("diamond");
        RewardTable table = table(0, 1, entry(0, guaranteed), entry(1, weighted));

        for (int seed = 0; seed < 20; seed++) {
            List<QuestReward> granted = table.roll(RandomSource.create(seed), false);
            assertEquals(1, countOf(granted, guaranteed), "seed " + seed);
        }
    }

    @Test
    @DisplayName("lootSize is how many times the dice are thrown")
    void lootSizeRollsTheStatedNumberOfTimes() {
        ItemReward one = item("stone");
        ItemReward two = item("diamond");
        RewardTable table = table(0, 3, entry(1, one), entry(1, two));

        List<QuestReward> granted = table.roll(RandomSource.create(7), false);

        assertEquals(3, granted.size(), "one throw per loot size, and no empty band to swallow any");
    }

    @Test
    @DisplayName("the empty band can yield nothing, and only when the mode includes it")
    void theEmptyBandOnlyBitesWhenIncluded() {
        RewardTable table = table(1.0, 20, entry(1, item("stone")));

        List<QuestReward> withEmpty = table.roll(RandomSource.create(3), true);
        assertTrue(withEmpty.size() < 20,
                "twenty throws with a fifty-fifty empty chance granting all twenty is not a roll");
        assertFalse(withEmpty.isEmpty(), "and the weighted entry is not impossible either");

        List<QuestReward> withoutEmpty = table.roll(RandomSource.create(3), false);
        assertEquals(20, withoutEmpty.size(),
                "the empty weight is out of play when the mode excludes it");
    }

    @Test
    @DisplayName("the all-table grant takes every entry once, weights ignored")
    void allIgnoresWeights() {
        RewardTable table = table(5.0, 4, entry(0, item("stone")), entry(9, item("diamond")));

        assertEquals(2, table.all().size(), "every entry once, the empty weight and the weights set aside");
    }

    @Test
    @DisplayName("choice finds an entry by position and refuses one that is not there")
    void choiceIndexesEntries() {
        ItemReward first = item("stone");
        RewardTable table = table(0, 1, entry(1, first), entry(1, item("diamond")));

        assertTrue(table.choice(0).isPresent());
        assertTrue(table.choice(0).get() == first, "position zero is the first entry");
        assertTrue(table.choice(1).isPresent());
        assertFalse(table.choice(-1).isPresent());
        assertFalse(table.choice(2).isPresent(), "there is no third entry");
    }

    @Test
    @DisplayName("a table round-trips through its codec")
    void codecRoundTrip() {
        RewardTable table = table(0.5, 2, entry(0, item("stone")), entry(3, item("diamond")));

        var encoded = RewardTable.CODEC.encodeStart(JsonOps.INSTANCE, table).getOrThrow();
        RewardTable decoded = RewardTable.CODEC.parse(JsonOps.INSTANCE, encoded).getOrThrow();

        assertEquals(0.5, decoded.emptyWeight());
        assertEquals(2, decoded.lootSize());
        assertEquals(2, decoded.entryCount());
        assertEquals(0.0, decoded.entries().get(0).weight());
        assertEquals(3.0, decoded.entries().get(1).weight());
        assertEquals("tenet:item", decoded.entries().get(1).reward().type().toString());
    }

    @Test
    @DisplayName("a title, an icon and a handle round-trip, and a blank title is stored as none")
    void theEditorsThreeFieldsRoundTrip() {
        RewardTable table = new RewardTable(0, 2, List.of(entry(1, item("stone"))),
                java.util.Optional.of("Tier 1 minerals"),
                java.util.Optional.of(new ItemRef(Identifier.withDefaultNamespace("iron_ingot"), 1)),
                java.util.Optional.of("a-handle"), false, false);

        RewardTable decoded = RewardTable.CODEC.parse(JsonOps.INSTANCE,
                RewardTable.CODEC.encodeStart(JsonOps.INSTANCE, table).getOrThrow()).getOrThrow();

        assertEquals("Tier 1 minerals", decoded.displayTitle("whatever"));
        assertEquals("minecraft:iron_ingot", decoded.displayIcon().item().toString());
        assertEquals("a-handle", decoded.uid().orElseThrow());

        RewardTable blank = new RewardTable(0, 1, List.of(), java.util.Optional.of("  "),
                java.util.Optional.empty(), java.util.Optional.of(""), false, false);
        assertTrue(blank.title().isEmpty(), "a blank title is no title");
        assertTrue(blank.uid().isEmpty(), "and a blank handle is no handle");
    }

    // ------------------------------------------------------------------
    // The table's chances, which the editor draws
    // ------------------------------------------------------------------

    private static RewardTable named(String title, RewardTable.Entry... entries) {
        return new RewardTable(0, 1, List.of(entries), java.util.Optional.of(title),
                java.util.Optional.empty(), java.util.Optional.empty(), false, false);
    }

    @Test
    @DisplayName("a table of only guaranteed entries rolls without touching the dice")
    void aGuaranteedTableNeedsNoProbabilitySpace() {
        // The state a freshly made table is in, and the one a naive total would divide by zero on.
        RewardTable table = table(0, 3, entry(0, item("stone")), entry(-1, item("diamond")));

        assertEquals(List.of(0, 1), table.rollIndices(RandomSource.create(1), false));
        assertEquals(2, table.roll(RandomSource.create(1), true).size(),
                "the empty band is not in play either: there is nothing for it to compete with");

        RewardTable.Odds odds = table.odds(true);
        assertTrue(odds.each().get(0).always(), "a guarantee is always granted, not a percentage");
        assertTrue(odds.each().get(1).always());
        assertEquals(0.0, odds.empty(), "an all-guaranteed table has no empty band");
        assertEquals(1.0, odds.each().get(0).atLeastOnce(3));
    }

    @Test
    @DisplayName("an empty table grants nothing and divides by nothing")
    void anEmptyTableIsSafe() {
        RewardTable table = table(0.5, 2);

        assertTrue(table.roll(RandomSource.create(1), true).isEmpty());
        assertTrue(table.odds(true).each().isEmpty());
        assertEquals(1.0, table.odds(true).empty(), 1e-9,
                "there is nothing to grant, so every throw lands in the empty band");

        RewardTable noEmptyBand = table(0, 2);
        assertEquals(0.0, noEmptyBand.odds(true).empty(),
                "and with no band either, the space is empty rather than divided by");
    }

    @Test
    @DisplayName("the odds are the same rule the roll uses")
    void oddsMatchTheRoll() {
        // Empty band 2, two entries at 1 each: a quarter for each entry, a half for nothing.
        RewardTable table = table(2.0, 1, entry(1, item("stone")), entry(1, item("diamond")));

        RewardTable.Odds loot = table.odds(true);
        assertEquals(0.5, loot.empty(), 1e-9);
        assertEquals(0.25, loot.each().get(0).perRoll(), 1e-9);
        assertEquals(0.25, loot.each().get(1).perRoll(), 1e-9);

        RewardTable.Odds random = table.odds(false);
        assertEquals(0.0, random.empty(), "a random reward promises something, so the band is out of play");
        assertEquals(0.5, random.each().get(0).perRoll(), 1e-9,
                "and the shares are of the space that is left");
    }

    @Test
    @DisplayName("more than one throw is not the same chance, and the odds can say so")
    void atLeastOnceCountsEveryThrow() {
        RewardTable.Chance oneInFive = new RewardTable.Chance(false, 0.2);

        assertEquals(0.2, oneInFive.atLeastOnce(1), 1e-9);
        assertEquals(1.0 - Math.pow(0.8, 3), oneInFive.atLeastOnce(3), 1e-9);
        assertEquals(0.0, oneInFive.atLeastOnce(0), "a table that is never rolled grants nothing");
    }

    // ------------------------------------------------------------------
    // What a browser row draws
    // ------------------------------------------------------------------

    @Test
    @DisplayName("a table with no title is named by its id")
    void theTitleFallsBackToTheId() {
        assertEquals("Tier 1 ores", table(0, 1).displayTitle("tier_1_ores"));
        assertEquals("Dungeon loot", named("Dungeon loot", entry(1, item("stone")))
                .displayTitle("tier_1_ores"));
    }

    @Test
    @DisplayName("the icon is the declared one, else an entry's own item, else the entry type's")
    void theIconIsDecidedInOneOrder() {
        ItemReward ore = item("iron_ingot");
        RewardTable declared = new RewardTable(0, 1, List.of(entry(1, ore)),
                java.util.Optional.empty(),
                java.util.Optional.of(new ItemRef(Identifier.withDefaultNamespace("chest"), 1)),
                java.util.Optional.empty(), false, false);
        assertEquals("minecraft:chest", declared.displayIcon().item().toString(),
                "an author's icon wins outright");

        assertEquals("minecraft:iron_ingot", table(0, 1, entry(1, ore)).displayIcon().item().toString(),
                "an item entry shows the item itself, not the generic item-reward icon");

        RewardTable xpOnly = table(0, 1, entry(1, new XpReward(RewardCommon.DEFAULT, 30, false)));
        assertEquals("minecraft:experience_bottle", xpOnly.displayIcon().item().toString(),
                "an entry with no item of its own falls back to its type's icon");

        assertEquals("minecraft:paper", table(0, 1).displayIcon().item().toString(),
                "and a table with nothing in it is paper, not a crash");
    }

    @Test
    @DisplayName("an entry that has an item is found past one that does not")
    void theIconLooksPastEntriesWithoutOne() {
        RewardTable table = table(0, 1,
                entry(1, new XpReward(RewardCommon.DEFAULT, 30, false)),
                entry(1, item("gold_ingot")));

        assertEquals("minecraft:gold_ingot", table.displayIcon().item().toString(),
                "a table of experience and gold is a gold table");
    }

    // ------------------------------------------------------------------
    // The four modes, as one ring and one spelling
    // ------------------------------------------------------------------

    @Test
    @DisplayName("every mode round-trips through its wire spelling, and no other string names one")
    void modesRoundTripThroughTheirWireSpelling() {
        // The spelling travels: a request carries it and the report echoes it, so a mode that could not
        // be read back would come home as a different reading of the same file -- which is exactly what
        // the collapsed request did before this round.
        assertEquals(4, TableReward.Mode.all().size(), "four modes, and the ring lists all of them");
        assertEquals(TableReward.Mode.all().size(), TableReward.Mode.values().length,
                "the ring and the enum cannot disagree about how many modes there are");

        for (TableReward.Mode mode : TableReward.Mode.values()) {
            assertTrue(TableReward.Mode.all().contains(mode), mode + " is missing from the ring");
            assertEquals(mode, TableReward.Mode.ofWire(mode.wire()).orElseThrow(),
                    mode + " does not survive its own wire spelling");
        }
        // The file's spelling, not the enum's: a report or a command that said "ALL_TABLE" would be a
        // constant name leaking into something a person reads and types.
        assertEquals("all_table", TableReward.Mode.ALL_TABLE.wire());

        assertTrue(TableReward.Mode.ofWire("wobble").isEmpty(),
                "an unknown spelling names no mode, rather than defaulting to one");
        assertTrue(TableReward.Mode.ofWire("").isEmpty());
        assertTrue(TableReward.Mode.ofWire("RANDOM").isEmpty(), "the spelling is lowercase");
    }

    @Test
    @DisplayName("a choice cannot be an entry in a table, and the sentence says why")
    void theEntryRefusalNamesTheOneTypeThatCannotBeRolled() {
        // One sentence, three readers: the validator refuses with it, the editor's picker draws the row
        // blocked with it, and the grant's own skip names the same fact. Asserted here, at the model,
        // rather than three times at the readers -- which is what stops the picker and the loader from
        // describing the rule differently.
        String refusal = TableReward.entryRefusal(TableReward.TYPE_CHOICE);

        assertFalse(refusal.isEmpty(), "a choice entry is the one thing a table may not hold");
        assertTrue(refusal.contains("cannot be an entry in a table"), refusal);
        assertTrue(refusal.contains("skipped"),
                "and it says what would happen if it were written anyway: " + refusal);

        assertTrue(TableReward.entryRefusal(TableReward.TYPE_LOOT).isEmpty());
        assertTrue(TableReward.entryRefusal(ItemReward.TYPE).isEmpty());
    }

    @Test
    @DisplayName("only a choice is a choice, wherever the question is asked from")
    void onePredicateAnswersWhatAChoiceIs() {
        // The grant, the report and the export each used to write their own instanceof plus a mode test,
        // with its own amount of noise when the answer was yes.
        TableReward choice = new TableReward(RewardCommon.DEFAULT, TableReward.Mode.CHOICE,
                java.util.Optional.of("dice"), java.util.Optional.empty());
        TableReward loot = new TableReward(RewardCommon.DEFAULT, TableReward.Mode.LOOT,
                java.util.Optional.of("dice"), java.util.Optional.empty());

        assertTrue(TableReward.isChoice(choice));
        assertFalse(TableReward.isChoice(loot));
        assertFalse(TableReward.isChoice(item("stone")),
                "an ordinary reward is not a choice, and a walk must not mistake it for one");
    }
}
