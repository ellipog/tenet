package dev.ellipog.tenet.quest.loot;

import dev.ellipog.tenet.quest.ItemRef;
import dev.ellipog.tenet.quest.MinecraftTestBootstrap;
import dev.ellipog.tenet.quest.QuestReward;
import dev.ellipog.tenet.quest.reward.ItemReward;
import dev.ellipog.tenet.quest.reward.RewardCommon;
import dev.ellipog.tenet.quest.reward.TableReward;

import net.minecraft.resources.Identifier;
import net.minecraft.util.RandomSource;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The roll report: what the editor's test roll says, and whether it says the truth.
 *
 * <p>The properties worth pinning are the ones a wrong report would get away with: a nested table's
 * tallies, a nested loot table's empty hits, and an all-table child that lists rather than rolls. A
 * report is only useful if it describes the grant, so every case here is a case the runtime has.
 */
@DisplayName("the test roll's report")
class TableRollerTest {

    @BeforeAll
    static void bootVanilla() {
        MinecraftTestBootstrap.boot();
    }

    private static ItemReward item(String id) {
        return new ItemReward(RewardCommon.DEFAULT,
                new ItemRef(Identifier.withDefaultNamespace(id), 1), 0, false);
    }

    private static RewardTable table(RewardTable.Entry... entries) {
        return new RewardTable(0, 1, List.of(entries));
    }

    private static RewardTable.Entry weight(double weight, QuestReward reward) {
        return new RewardTable.Entry(weight, reward);
    }

    private static TableReward named(String id, TableReward.Mode mode) {
        return new TableReward(RewardCommon.DEFAULT, mode, Optional.of(id), Optional.empty());
    }

    /** One roll of a table with one guaranteed entry: every tally is exact, so assertions can be. */
    @Test
    @DisplayName("a guaranteed entry is counted once per roll, and nothing else comes up")
    void guaranteedEntriesAreCountedPerRoll() {
        RewardTable table = table(weight(0, item("bread")), weight(1, item("diamond")));

        TableRoller.Report report = TableRoller.roll(table, TableReward.Mode.RANDOM, 50,
                RandomSource.create(4), id -> null);

        assertEquals(50, report.rolls());
        assertEquals(50, report.entries().get(0).hits(), "the guarantee lands in every roll");
        assertEquals(50, report.entries().get(1).hits() + report.emptyHits(),
                "and the weighted entry takes every throw, since a random reward has no empty band");
        assertEquals(0, report.emptyHits());
    }

    @Test
    @DisplayName("the empty band's hits are reported, not lost")
    void emptyHitsAreCounted() {
        RewardTable table = new RewardTable(3, 2, List.of(weight(1, item("bread"))));

        TableRoller.Report report = TableRoller.roll(table, TableReward.Mode.LOOT, 40,
                RandomSource.create(11), id -> null);

        assertEquals(80, report.entries().get(0).hits() + report.emptyHits(),
                "forty grants of two throws each, every one of them landing somewhere");
        assertTrue(report.emptyHits() > 0, "a three-to-one empty band is not nothing");
    }

    @Test
    @DisplayName("an all-table lists every entry once per grant, with no dice and no empty band")
    void allTableListsEverything() {
        RewardTable table = table(weight(1, item("bread")), weight(99, item("diamond")));

        TableRoller.Report report = TableRoller.roll(table, TableReward.Mode.ALL_TABLE, 7,
                RandomSource.create(1), id -> null);

        assertEquals(7, report.entries().get(0).hits());
        assertEquals(7, report.entries().get(1).hits(), "weights do not apply to a listing");
        assertEquals(0, report.emptyHits());
    }

    @Test
    @DisplayName("a nested table is rolled once per landing, and its tallies come back with it")
    void nestedTablesAreTallied() {
        Map<String, RewardTable> tables = Map.of(
                "child", table(weight(1, item("iron_ingot")), weight(1, item("gold_ingot"))));
        RewardTable parent = table(weight(0, item("bread")), weight(1, named("child", TableReward.Mode.RANDOM)));

        TableRoller.Report report = TableRoller.roll(parent, TableReward.Mode.RANDOM, 60,
                RandomSource.create(9), tables::get);

        TableRoller.Tally nested = report.entries().get(1);
        assertEquals(60, nested.hits(), "the guarantee plus one throw means the child rolls every time");
        assertEquals(2, nested.nested().size());
        assertEquals(60, nested.nested().get(0).hits() + nested.nested().get(1).hits(),
                "and the child's own throws all landed in it");
        assertEquals(0, nested.emptyHits(), "a random child has no empty band to report");
    }

    @Test
    @DisplayName("a nested loot table that paid nothing says so")
    void nestedEmptyHitsAreReported() {
        Map<String, RewardTable> tables = Map.of(
                "child", new RewardTable(9, 1, List.of(weight(1, item("iron_ingot")))));
        RewardTable parent = table(weight(1, named("child", TableReward.Mode.LOOT)));

        TableRoller.Report report = TableRoller.roll(parent, TableReward.Mode.RANDOM, 40,
                RandomSource.create(5), tables::get);

        TableRoller.Tally nested = report.entries().get(0);
        assertTrue(nested.emptyHits() > 0, "a nine-to-one empty band pays nothing most of the time");
        assertEquals(nested.hits(), nested.nested().get(0).hits() + nested.emptyHits(),
                "every child roll either landed or came up empty -- none may vanish from the report");
    }

    @Test
    @DisplayName("a missing table is a tally with no children, not a crash")
    void aMissingReferenceIsReportedAsItself() {
        RewardTable parent = table(weight(1, named("nowhere", TableReward.Mode.RANDOM)));

        TableRoller.Report report = TableRoller.roll(parent, TableReward.Mode.RANDOM, 5,
                RandomSource.create(1), id -> null);

        assertEquals(5, report.entries().get(0).hits());
        assertTrue(report.entries().get(0).nested().isEmpty());
    }

    @Test
    @DisplayName("the depth cap cuts a cascade off and says that it did")
    void theDepthCapIsReported() {
        // A chain of ten tables, each pointing at the next: the walk may follow eight.
        Map<String, RewardTable> tables = new java.util.HashMap<>();
        for (int i = 0; i < 10; i++) {
            tables.put("t" + i, table(weight(1, named("t" + (i + 1), TableReward.Mode.RANDOM))));
        }
        tables.put("t10", table(weight(1, item("bread"))));

        TableRoller.Report report = TableRoller.roll(tables.get("t0"), TableReward.Mode.RANDOM, 1,
                RandomSource.create(1), tables::get);

        assertTrue(report.truncated(), "the report must not imply it followed the whole chain");
    }

    @Test
    @DisplayName("a forged roll count is clamped rather than obeyed")
    void rollCountsAreClamped() {
        RewardTable table = table(weight(1, item("bread")));

        assertEquals(TableRoller.MAX_ROLLS, TableRoller.roll(table, TableReward.Mode.RANDOM,
                10_000_000, RandomSource.create(1), id -> null).rolls());
        assertEquals(1, TableRoller.roll(table, TableReward.Mode.RANDOM, 0,
                RandomSource.create(1), id -> null).rolls(), "and a nonsensical zero is one");
        assertFalse(TableRoller.roll(table, TableReward.Mode.RANDOM, -5,
                RandomSource.create(1), id -> null).rolls() < 1);
    }

    @Test
    @DisplayName("an entry carries the display a reward row would draw for it")
    void talliesCarryDisplays() {
        RewardTable table = table(weight(1, item("iron_ingot")));

        TableRoller.Tally tally = TableRoller.roll(table, TableReward.Mode.RANDOM, 1,
                RandomSource.create(1), id -> null).entries().get(0);

        assertEquals(0, tally.index());
        assertEquals("minecraft:iron_ingot", tally.display().item().orElseThrow().item().toString());
    }

    @Test
    @DisplayName("a choice reading throws no dice at all, and says so rather than inventing a band")
    void aChoiceReportsNoDice() {
        // The fault this pins: the request collapsed four readings into two, so asking for a choice
        // rolled the dice and reported percentages under a chip that says the player picks. Now the
        // reading travels and the report follows it -- and the entries are still listed, because "what
        // would this offer" is the useful thing to show.
        RewardTable table = new RewardTable(5, 3, List.of(
                weight(1, item("bread")), weight(1, item("diamond"))));

        TableRoller.Report report = TableRoller.roll(table, TableReward.Mode.CHOICE, 20,
                RandomSource.create(3), id -> null);

        assertEquals(TableReward.Mode.CHOICE, report.mode(), "the report names the reading it was asked for");
        assertEquals(20, report.rolls());
        assertEquals(2, report.entries().size(), "every entry is listed, so the pane can show the offer");
        for (TableRoller.Tally tally : report.entries()) {
            assertEquals(0, tally.hits(), "a pick is not a roll: nothing was granted");
        }
        assertEquals(0, report.emptyHits(),
                "and no throw landed in the empty band, because there were no throws -- a nonzero count "
                        + "here would be lootSize times rolls of nothing");
    }

    @Test
    @DisplayName("an entry no roll can pay out is named, not dropped from the report")
    void aNestedChoiceIsNamedInTheReport() {
        // The bug this pins: a choice entry inside a table was skipped by the walk with no trace in the
        // report at all, so the pane showed one row fewer than the file has and the author went looking
        // for an entry that was never missing.
        Map<String, RewardTable> tables = Map.of(
                "child", table(weight(1, item("bread"))));
        RewardTable parent = table(
                weight(1, item("iron_ingot")),
                weight(1, named("child", TableReward.Mode.CHOICE)));

        TableRoller.Report report = TableRoller.roll(parent, TableReward.Mode.RANDOM, 10,
                RandomSource.create(1), tables::get);

        assertEquals(1, report.notRolled().size(),
                "the entry that cannot be rolled is named once, however often it is landed on");
        String note = report.notRolled().get(0);
        assertTrue(note.contains("entry 2"), "it says which entry it is: " + note);
        assertTrue(note.contains("child"), "and which table it points at: " + note);
        assertTrue(note.contains("offers"), "and why a roll cannot pay it: " + note);
    }

    @Test
    @DisplayName("a report with nothing skipped says nothing, and the two incompletenesses are separate")
    void aCompleteReportHasNoNotes() {
        RewardTable table = table(weight(1, item("bread")), weight(0, item("iron_ingot")));

        TableRoller.Report report = TableRoller.roll(table, TableReward.Mode.RANDOM, 5,
                RandomSource.create(1), id -> null);

        assertTrue(report.notRolled().isEmpty(), "nothing was skipped, so there is nothing to name");
        assertFalse(report.truncated(), "and nothing was cut off");
    }
}
