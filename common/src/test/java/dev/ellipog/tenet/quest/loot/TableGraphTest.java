package dev.ellipog.tenet.quest.loot;

import dev.ellipog.tenet.quest.ItemRef;
import dev.ellipog.tenet.quest.MinecraftTestBootstrap;
import dev.ellipog.tenet.quest.QuestReward;
import dev.ellipog.tenet.quest.reward.ItemReward;
import dev.ellipog.tenet.quest.reward.RewardCommon;
import dev.ellipog.tenet.quest.reward.TableReward;

import net.minecraft.resources.Identifier;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * What a table can reach: the references, the cycles, and the handles.
 *
 * <p>Three walks that have to agree about the same word, so they are tested against the same shapes: a
 * reward that names a table, an entry that carries one inline, and a table inside a table's entry.
 */
@DisplayName("the table graph")
class TableGraphTest {

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

    private static RewardTable.Entry entry(QuestReward reward) {
        return new RewardTable.Entry(1, reward);
    }

    private static TableReward named(String id, TableReward.Mode mode) {
        return new TableReward(RewardCommon.DEFAULT, mode, Optional.of(id), Optional.empty());
    }

    private static TableReward inline(RewardTable table, TableReward.Mode mode) {
        return new TableReward(RewardCommon.DEFAULT, mode, Optional.empty(), Optional.of(table));
    }

    // ------------------------------------------------------------------
    // References
    // ------------------------------------------------------------------

    @Test
    @DisplayName("a named reference is collected, and an inline table's own references with it")
    void referencesAreWalkedThroughInlineTables() {
        RewardTable deepest = table(entry(named("deep", TableReward.Mode.RANDOM)));
        TableReward nested = inline(deepest, TableReward.Mode.LOOT);
        TableReward outer = inline(table(entry(item("stone")), entry(nested)), TableReward.Mode.RANDOM);

        assertEquals(Set.of("deep"), RewardTableRefs.idsOf(outer),
                "the id is two inline tables down, and still the outer table's reference");
        assertEquals(Set.of("deep"), RewardTableRefs.idsOf(table(entry(outer))));
        assertEquals(Set.of("deep", "other"),
                RewardTableRefs.idsOf(List.of(outer, named("other", TableReward.Mode.LOOT))));
    }

    @Test
    @DisplayName("a dangling reference is still reported as a reference")
    void danglingReferencesAreCollected() {
        // The loader's own check is what refuses it; this walk must not quietly drop it, or the cycle
        // check would treat a broken file as a leaf and the delete guard as unreferenced.
        assertEquals(Set.of("nowhere"), RewardTableRefs.idsOf(named("nowhere", TableReward.Mode.RANDOM)));
    }

    @Test
    @DisplayName("a reference is reported with the path of the reward that makes it")
    void referencesCarryTheirPaths() {
        RewardTable table = table(entry(item("stone")),
                entry(named("dungeon", TableReward.Mode.LOOT)));

        List<RewardTableRefs.Ref> refs = RewardTableRefs.refsOf(table, "$");

        assertEquals(1, refs.size());
        assertEquals("dungeon", refs.get(0).id());
        assertEquals("$.entries[1].reward", refs.get(0).path(),
                "the reward's path, so a caller's `+ \".table\"` names the line the author edits");

        // And a nested one runs through the inline table that holds it.
        RewardTable nested = table(entry(inline(
                table(entry(named("deep", TableReward.Mode.RANDOM))), TableReward.Mode.LOOT)));
        assertEquals("$.entries[0].reward.inline.entries[0].reward",
                RewardTableRefs.refsOf(nested, "$").get(0).path());
    }

    // ------------------------------------------------------------------
    // Cycles
    // ------------------------------------------------------------------

    @Test
    @DisplayName("a table that reaches itself is a cycle, and the chain names the loop")
    void aCycleIsFound() {
        // A LinkedHashMap, because that is what the loader builds (name order) and what makes the
        // reported chain's starting point a fact rather than whichever order a map felt like.
        Map<String, RewardTable> tables = new java.util.LinkedHashMap<>();
        tables.put("a", table(entry(named("b", TableReward.Mode.RANDOM))));
        tables.put("b", table(entry(named("c", TableReward.Mode.RANDOM))));
        tables.put("c", table(entry(named("a", TableReward.Mode.RANDOM))));

        Optional<List<String>> cycle = TableCycles.find(tables);

        assertTrue(cycle.isPresent());
        assertEquals("a", cycle.get().get(0), "reported from where the loop was entered");
        assertEquals(cycle.get().get(0), cycle.get().get(cycle.get().size() - 1),
                "the chain closes: the first id is named again at the end");
        assertEquals(Set.of("a", "b", "c"), Set.copyOf(cycle.get()));
    }

    @Test
    @DisplayName("a self-reference is a cycle of one")
    void aSelfReferenceIsACycle() {
        Map<String, RewardTable> tables = Map.of(
                "self", table(entry(named("self", TableReward.Mode.LOOT))));

        assertEquals(List.of("self", "self"), TableCycles.find(tables).orElseThrow());
    }

    @Test
    @DisplayName("a reference inside an inline table closes the loop too")
    void cyclesRunThroughInlineTables() {
        // A -> (its inline) -> B -> A. The inline table is not a node; its reference is A's.
        RewardTable a = table(entry(inline(table(entry(named("b", TableReward.Mode.RANDOM))),
                TableReward.Mode.RANDOM)));
        Map<String, RewardTable> tables = Map.of(
                "a", a,
                "b", table(entry(named("a", TableReward.Mode.LOOT))));

        assertTrue(TableCycles.find(tables).isPresent(),
                "a walk that only looked at top-level entries would call this a diamond");
    }

    @Test
    @DisplayName("a diamond is not a cycle")
    void sharedChildrenAreNotCycles() {
        Map<String, RewardTable> tables = Map.of(
                "a", table(entry(named("b", TableReward.Mode.RANDOM)),
                        entry(named("c", TableReward.Mode.RANDOM))),
                "b", table(entry(named("d", TableReward.Mode.RANDOM))),
                "c", table(entry(named("d", TableReward.Mode.RANDOM))),
                "d", table(entry(item("stone"))));

        assertTrue(TableCycles.find(tables).isEmpty());
    }

    @Test
    @DisplayName("a dangling reference does not stop the walk, and is not mistaken for a cycle")
    void danglingReferencesAreNotCycles() {
        Map<String, RewardTable> tables = Map.of(
                "a", table(entry(named("b", TableReward.Mode.RANDOM)),
                        entry(named("nowhere", TableReward.Mode.LOOT))),
                "b", table(entry(named("nowhere", TableReward.Mode.RANDOM))));

        assertTrue(TableCycles.find(tables).isEmpty(),
                "a reference nothing answers to is the loader's error, not a loop");
    }

    // ------------------------------------------------------------------
    // Handles
    // ------------------------------------------------------------------

    private static com.google.gson.JsonObject quest(String... rewardJson) {
        com.google.gson.JsonObject root = new com.google.gson.JsonObject();
        com.google.gson.JsonArray rewards = new com.google.gson.JsonArray();
        for (String json : rewardJson) {
            rewards.add(com.google.gson.JsonParser.parseString(json));
        }
        root.add("rewards", rewards);
        return root;
    }

    @Test
    @DisplayName("inline tables are found where they are, nested ones included")
    void handlesAreFoundAtAnyDepth() {
        com.google.gson.JsonObject root = quest(
                """
                { "type": "tenet:item", "item": "minecraft:stone" }""",
                """
                { "type": "tenet:random", "inline": { "uid": "outer",
                    "entries": [ { "reward": { "type": "tenet:loot", "inline": { "uid": "inner",
                        "entries": [] } } } ] } }""");

        List<InlineTables.Found> found = InlineTables.inQuest(root);

        assertEquals(2, found.size());
        assertEquals("rewards.1.inline", found.get(0).path());
        assertEquals("outer", found.get(0).uid());
        assertEquals("rewards.1.inline.entries.0.reward.inline", found.get(1).path(),
                "a nested table's path runs through the one that holds it");
        assertEquals("inner", found.get(1).uid());

        assertEquals(1, InlineTables.withUid(found, "inner").size());
        assertTrue(InlineTables.withUid(found, "absent").isEmpty());
        assertTrue(InlineTables.withUid(found, " ").isEmpty(), "a blank handle names nothing");
    }

    @Test
    @DisplayName("a write is deduplicated against the handles outside the subtree it replaces")
    void deduplicationIsScopedToTheWrite() {
        List<InlineTables.Found> found = InlineTables.inQuest(quest(
                """
                { "type": "tenet:random", "inline": { "uid": "first", "entries": [] } }""",
                """
                { "type": "tenet:random", "inline": { "uid": "second", "entries": [] } }"""));

        // Writing rewards.1 back: its own handle is inside the replaced subtree, so it is not a clash.
        Set<String> outside = InlineTables.handlesOutside(found, "rewards.1");
        assertFalse(outside.contains("second"), "the subtree being replaced is not a competitor");
        assertTrue(outside.contains("first"), "everything else is");

        // A copy of the first reward, written at the end: its handle does clash, and is renamed.
        com.google.gson.JsonElement copy = com.google.gson.JsonParser.parseString(
                """
                { "type": "tenet:random", "inline": { "uid": "first", "entries": [] } }""");
        assertEquals(1, InlineTables.deduplicate(copy, outside), "the copy's handle is renamed");

        // And writing the second reward back unchanged is not a rename: its handle is its own.
        com.google.gson.JsonElement itself = com.google.gson.JsonParser.parseString(
                """
                { "type": "tenet:random", "inline": { "uid": "second", "entries": [] } }""");
        assertEquals(0, InlineTables.deduplicate(itself, outside),
                "a table written back to where it already lives keeps the handle its editor is using");
    }

    @Test
    @DisplayName("an unhandled inline table is given one, and a copy of a file is re-minted whole")
    void unhandledAndCopiedTablesGetFreshHandles() {
        com.google.gson.JsonElement unhandled = com.google.gson.JsonParser.parseString(
                """
                { "type": "tenet:random", "inline": { "entries": [] } }""");
        assertEquals(1, InlineTables.deduplicate(unhandled, Set.of()));
        String given = unhandled.getAsJsonObject().getAsJsonObject("inline").get("uid").getAsString();
        assertFalse(given.isBlank(), "a table with no handle gets one the first time it is written");

        com.google.gson.JsonElement copy = com.google.gson.JsonParser.parseString(
                """
                { "inline": { "uid": "same", "entries": [
                    { "reward": { "type": "tenet:loot", "inline": { "uid": "same", "entries": [] } } } ] } }""");
        assertEquals(2, InlineTables.remintAll(copy), "a copied file re-mints every handle, clashes or not");
    }

    @Test
    @DisplayName("a copied file is re-minted inside, and not given a handle it never had")
    void aCopiedFileKeepsItsOwnNameAndRemintsWhatIsInside() {
        // A table file's root *is* a table -- it holds an `entries` array, which is what makes it one --
        // and a table in its own file is addressed by its file name. Re-minting from the root stamped a
        // fresh `uid` into the top level of a file both the schema and rewards.md say carries none: a
        // field the author never wrote, written by a copy. The walk starts at the entries instead,
        // because a table nested inside the copy is a second instance of itself and does need a handle.
        com.google.gson.JsonObject file = com.google.gson.JsonParser.parseString(
                """
                { "entries": [
                    { "reward": { "type": "tenet:random",
                                  "inline": { "uid": "inside", "entries": [] } } } ] }""")
                .getAsJsonObject();

        assertEquals(1, InlineTables.remintEntries(file), "the table inside the copy is re-minted");
        assertFalse(file.has("uid"), "the file is addressed by its name, so a root handle is a field nobody wrote");

        String inside = file.getAsJsonArray("entries").get(0).getAsJsonObject()
                .getAsJsonObject("reward").getAsJsonObject("inline").get("uid").getAsString();
        assertNotEquals("inside", inside, "and the copy's own handle is not the original's");
    }
}
