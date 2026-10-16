package dev.ellipog.tenet.quest.loot;

import dev.ellipog.tenet.quest.ItemRef;
import dev.ellipog.tenet.quest.MinecraftTestBootstrap;
import dev.ellipog.tenet.quest.QuestReward;
import dev.ellipog.tenet.quest.reward.ItemReward;
import dev.ellipog.tenet.quest.reward.RewardCommon;
import dev.ellipog.tenet.quest.reward.TableReward;
import dev.ellipog.tenet.quest.reward.XpReward;

import net.minecraft.resources.Identifier;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Reading a table into a chest, and what the chest is not telling you.
 *
 * <h2>Two questions, one walk</h2>
 *
 * <p>This used to be two methods — {@code stacks(...)} and {@code skipped(...)} — which walked the table
 * separately, counted different things, and disagreed the moment nesting was involved: the stacks
 * descended into child tables while the count described the top level only, so a report could say "2
 * skipped" about a table that had skipped nine. The second method was also never called by anything, so
 * the sentence it existed for never printed at all: the count was hardcoded to zero at the one place that
 * read it. Every assertion here is about the counts and the stacks coming from the same traversal.
 */
@DisplayName("exporting a table's items")
class TableExportTest {

    @BeforeAll
    static void bootVanilla() {
        MinecraftTestBootstrap.boot();
    }

    private static ItemReward item(String id, int count, int bonus) {
        return new ItemReward(RewardCommon.DEFAULT,
                new ItemRef(Identifier.withDefaultNamespace(id), count), bonus, false);
    }

    private static RewardTable table(RewardTable.Entry... entries) {
        return new RewardTable(0, 1, List.of(entries));
    }

    private static RewardTable.Entry entry(QuestReward reward) {
        return new RewardTable.Entry(1, reward);
    }

    private static TableReward reference(String id, TableReward.Mode mode) {
        return new TableReward(RewardCommon.DEFAULT, mode, Optional.of(id), Optional.empty());
    }

    /** No named tables, so every reference in these fixtures dangles unless a map says otherwise. */
    private static final java.util.function.Function<String, RewardTable> NO_TABLES = id -> null;

    @Test
    @DisplayName("the stacks and the two counts come from one traversal")
    void oneWalkAnswersEveryQuestion() {
        RewardTable table = table(
                entry(item("iron_ingot", 3, 0)),
                entry(item("diamond", 1, 63)),
                entry(new XpReward(RewardCommon.DEFAULT, 30, false)));

        TableExport.Export export = TableExport.stacks(table, false, NO_TABLES);

        assertEquals(2, export.stacks().size(), "three iron is one stack, one diamond is another");
        assertEquals(3, export.stacks().get(0).getCount(), "and the count is what the entry asks for");
        assertEquals(1, export.skipped(), "and the experience entry is the one that cannot go in");
        assertEquals(1, export.randomised(),
                "the diamond rolls up to sixty-four, and the chest holds the minimum");
    }

    @Test
    @DisplayName("a stack is split into what the game can hold, and the count is the configured one")
    void countsAreSplitIntoLegalStacks() {
        TableExport.Export export = TableExport.stacks(table(entry(item("iron_ingot", 130, 0))), false,
                NO_TABLES);

        assertEquals(3, export.stacks().size(), "130 iron is three stacks");
        assertEquals(64, export.stacks().get(0).getCount());
        assertEquals(2, export.stacks().get(2).getCount(), "and the remainder is its own stack");
        assertEquals(130, export.stacks().stream().mapToInt(stack -> stack.getCount()).sum());
    }

    @Test
    @DisplayName("a table reference is skipped when the walk was not asked to open it")
    void aReferenceIsSkippedWhenNestingIsOff() {
        // The count that was wrong before: without this, a table of three sub-tables exported an empty
        // chest and reported nothing skipped at all.
        RewardTable parent = table(
                entry(item("gold_ingot", 1, 0)),
                entry(reference("child", TableReward.Mode.RANDOM)));
        Map<String, RewardTable> tables = Map.of("child", table(entry(item("diamond", 1, 0))));

        TableExport.Export flat = TableExport.stacks(parent, false, tables::get);

        assertEquals(1, flat.stacks().size(), "only the parent's own item");
        assertEquals(1, flat.skipped(), "and the reference it did not open is counted, not silently dropped");

        TableExport.Export nested = TableExport.stacks(parent, true, tables::get);

        assertEquals(2, nested.stacks().size(), "with nesting on, the child's item comes too");
        assertEquals(0, nested.skipped());
    }

    @Test
    @DisplayName("a child's skipped entries are counted at the depth they are met")
    void aChildsSkippedEntriesAreCounted() {
        // The disagreement this pins: `stacks` descended and `skipped` did not, so the two numbers
        // described different tables.
        RewardTable child = table(
                entry(item("diamond", 1, 0)),
                entry(new XpReward(RewardCommon.DEFAULT, 10, false)),
                entry(new XpReward(RewardCommon.DEFAULT, 20, false)));
        RewardTable parent = table(
                entry(new XpReward(RewardCommon.DEFAULT, 5, false)),
                entry(reference("child", TableReward.Mode.ALL_TABLE)));
        Map<String, RewardTable> tables = Map.of("child", child);

        TableExport.Export export = TableExport.stacks(parent, true, tables::get);

        assertEquals(1, export.stacks().size(), "one item, from inside the child");
        assertEquals(3, export.skipped(), "the parent's experience entry and the child's two");
    }

    @Test
    @DisplayName("a choice, a missing table and a loop are each skipped rather than walked")
    void theThreeDeadEndsAreCounted() {
        RewardTable dangling = table(entry(reference("nowhere", TableReward.Mode.RANDOM)));
        assertEquals(1, TableExport.stacks(dangling, true, NO_TABLES).skipped(),
                "a reference that resolves to nothing cannot become a stack");

        RewardTable choosing = table(entry(reference("child", TableReward.Mode.CHOICE)));
        assertEquals(1, TableExport.stacks(choosing, true,
                Map.of("child", table(entry(item("diamond", 1, 0))))::get).skipped(),
                "a choice offers rather than grants, so an export has nothing to add");

        // A table that reaches itself: the loader reports the loop, and a walk that followed it would
        // hang -- an export that hangs is worse than one that is incomplete.
        RewardTable loop = table(entry(reference("self", TableReward.Mode.RANDOM)));
        TableExport.Export export = TableExport.stacks(loop, true, Map.of("self", loop)::get);
        assertEquals(1, export.skipped());
        assertTrue(export.stacks().isEmpty());
    }

    @Test
    @DisplayName("an entry whose item does not resolve is not called a non-item entry")
    void anUnknownItemIsNotCountedAsANonItemEntry() {
        // It is an item entry; it just resolved to nothing. The validator reports the id, and calling it
        // "not an item" here would send an author looking for the wrong mistake.
        RewardTable table = table(entry(item("not_a_real_item", 1, 0)));

        TableExport.Export export = TableExport.stacks(table, false, NO_TABLES);

        assertTrue(export.stacks().isEmpty());
        assertEquals(0, export.skipped());
    }

    @Test
    @DisplayName("nesting stops at the shared limit rather than at one of its own")
    void nestingStopsAtTheSharedLimit() {
        // One constant for four walks: the grant, the report, the loader's reference check and this. The
        // chain is built from a map so it is data rather than a stack of calls -- t0 points at t1, and so
        // on -- and each table carries an item of its own, so the walk's reach can be counted exactly
        // rather than described.
        int limit = RewardTable.MAX_NESTING;

        Map<String, RewardTable> within = new java.util.HashMap<>();
        for (int i = 0; i <= limit; i++) {
            within.put("t" + i, i == limit
                    ? table(entry(item("iron_ingot", 1, 0)), entry(item("gold_ingot", 1, 0)))
                    : table(entry(item("iron_ingot", 1, 0)),
                            entry(reference("t" + (i + 1), TableReward.Mode.RANDOM))));
        }
        TableExport.Export walked = TableExport.stacks(within.get("t0"), true, within::get);

        assertEquals(limit + 2, walked.stacks().size(),
                "one item per table, plus the last table's second, so the whole chain was walked");
        assertEquals(0, walked.skipped(), "and nothing inside the limit was cut off");

        // Deeper than the limit, in the shape that shows where the walk stops: one item per table and a
        // reference onward, so the stack count *is* the count of tables walked. `>` is what "nesting at
        // this depth is allowed" means, so the table at the limit is walked and the reference written in
        // it is opened -- c(limit+1) is reached. One link more, and it is the reference written in *that*
        // table, a level further down, that is counted as skipped: the gold past it is not reached.
        Map<String, RewardTable> chain = new java.util.HashMap<>();
        for (int i = 0; i <= limit + 1; i++) {
            chain.put("c" + i, i == limit + 1
                    ? table(entry(item("gold_ingot", 1, 0)))
                    : table(entry(item("iron_ingot", 1, 0)),
                            entry(reference("c" + (i + 1), TableReward.Mode.RANDOM))));
        }

        TableExport.Export reached = TableExport.stacks(chain.get("c0"), true, chain::get);

        assertEquals(limit + 2, reached.stacks().size(),
                "every table up to and including the one past the limit, and the gold it holds");
        assertEquals(0, reached.skipped(), "and every reference on the way down was opened");

        chain.put("c" + (limit + 1), table(entry(item("iron_ingot", 1, 0)),
                entry(reference("c" + (limit + 2), TableReward.Mode.RANDOM))));
        chain.put("c" + (limit + 2), table(entry(item("gold_ingot", 1, 0))));

        TableExport.Export cut = TableExport.stacks(chain.get("c0"), true, chain::get);

        assertEquals(limit + 2, cut.stacks().size(),
                "one link longer, and one stack per table walked: the gold past the limit is not in it");
        assertEquals(1, cut.skipped(), "the reference written past the limit is the skipped entry");
    }
}
