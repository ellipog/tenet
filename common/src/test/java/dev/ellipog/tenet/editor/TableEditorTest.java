package dev.ellipog.tenet.editor;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import dev.ellipog.tenet.client.dev.QuestPanelLayout;
import dev.ellipog.tenet.quest.MinecraftTestBootstrap;
import dev.ellipog.tenet.quest.QuestIndex;
import dev.ellipog.tenet.quest.loot.RewardTable;
import dev.ellipog.tenet.quest.reward.RewardTypes;

import net.minecraft.resources.Identifier;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tables open for editing: a file's own table, and one inside a quest.
 *
 * <p>The two halves are the same draft, so they are tested against the same edits — and the differences
 * are pinned as differences: a named table's save validates its own document and the folder's graph,
 * while an inline table's save is the chapter's, and its undo is the chapter's history.
 */
@DisplayName("A table, open for editing")
class TableEditorTest {

    private static final String TABLE = """
            {
              "title": "Tier 1 ores",
              "lootSize": 1,
              "entries": [
                { "weight": 3, "reward": { "type": "tenet:item", "item": "minecraft:iron_ingot" } },
                { "weight": 1, "reward": { "type": "tenet:item", "item": "minecraft:diamond" } }
              ]
            }
            """;

    private static final String QUEST = """
            {
              "id": "one",
              "title": "One",
              "x": 0,
              "y": 0,
              "icon": { "item": "minecraft:oak_log" },
              "rewards": [ { "type": "tenet:random",
                  "inline": { "uid": "handle", "entries": [
                    { "weight": 1, "reward": { "type": "tenet:item", "item": "minecraft:coal" } } ] } } ]
            }
            """;

    private Path root;

    @BeforeAll
    static void bootstrapMinecraft() {
        MinecraftTestBootstrap.boot();
    }

    @BeforeEach
    void files(@TempDir Path dir) throws IOException {
        root = dir.resolve("quests");
        Files.createDirectories(root.resolve("reward_tables"));
        Files.writeString(root.resolve("reward_tables/ores.json"), TABLE, StandardCharsets.UTF_8);

        Path chapter = root.resolve("getting_started").resolve("first_steps");
        Files.createDirectories(chapter);
        Files.writeString(chapter.resolve("chapter.json"), """
                { "id": "first_steps", "title": "First Steps", "quests": [ "one.json" ] }
                """, StandardCharsets.UTF_8);
        Files.writeString(chapter.resolve("one.json"), QUEST, StandardCharsets.UTF_8);
        // A group manifest, because the walk expects one: a chapter is only found under a group.
        Files.writeString(root.resolve("getting_started/group.json"), """
                { "id": "getting_started", "title": "Getting Started", "chapters": [ "first_steps" ] }
                """, StandardCharsets.UTF_8);
    }

    private TableEditor named() {
        return TableEditor.named(root.resolve("reward_tables/ores.json"), "ores").orElseThrow();
    }

    private Map<String, RewardTable> loaded() {
        Map<String, RewardTable> tables = new LinkedHashMap<>();
        tables.put("ores", RewardTable.CODEC.parse(com.mojang.serialization.JsonOps.INSTANCE,
                JsonParser.parseString(TABLE)).getOrThrow());
        return tables;
    }

    /** A draft over a chapter's quest file at a prefix: how a reward's own table is written through. */
    private TableEditor inQuest() {
        QuestEditor chapter = QuestEditor.open(root, "first_steps").orElseThrow();
        assertNotNull(chapter.quest("one"), "the fixture quest must be open");
        return TableEditor.inQuest(chapter, "one", "rewards.0.inline");
    }

    // ------------------------------------------------------------------
    // A file's own table
    // ------------------------------------------------------------------

    @Test
    @DisplayName("edits change the file, and one edit is one undo")
    void namedEditsAndUndo() throws IOException {
        TableEditor table = named();

        assertTrue(table.set("entries.0.weight", new com.google.gson.JsonPrimitive(9)));
        assertEquals(9, table.root().getAsJsonArray("entries").get(0).getAsJsonObject()
                .get("weight").getAsInt());
        assertTrue(table.canUndo());
        assertTrue(table.undo());
        assertEquals(3, table.root().getAsJsonArray("entries").get(0).getAsJsonObject()
                .get("weight").getAsInt(), "undo puts the old weight back");
        assertTrue(table.canRedo());
        assertTrue(table.redo());
        assertEquals(9, table.root().getAsJsonArray("entries").get(0).getAsJsonObject()
                .get("weight").getAsInt());

        // And the edit is written by a save that validates first.
        assertTrue(table.save(loaded(), com.mojang.serialization.JsonOps.INSTANCE).ok());
        assertEquals(9, RewardTable.CODEC.parse(com.mojang.serialization.JsonOps.INSTANCE,
                        JsonParser.parseString(Files.readString(root.resolve("reward_tables/ores.json"),
                                StandardCharsets.UTF_8)))
                .getOrThrow().entries().get(0).weight());
    }

    @Test
    @DisplayName("a blank title is stored as absent, not as an empty name")
    void blankTitlesAreRemoved() {
        TableEditor table = named();

        assertTrue(table.set("title", new com.google.gson.JsonPrimitive("  ")));
        assertFalse(table.root().has("title"),
                "a cleared title box must fall back to the id rather than draw nothing");
    }

    @Test
    @DisplayName("writing a value the file already holds is not an edit, and touches nothing")
    void anIdenticalValueIsNotAnEdit() throws IOException {
        TableEditor table = named();
        Path file = root.resolve("reward_tables/ores.json");

        assertFalse(table.set("entries.0.weight", new com.google.gson.JsonPrimitive(3)),
                "the weight is already 3, so there is nothing to change");
        assertFalse(table.set("uid", null), "and there is no uid to clear");
        assertTrue(table.set("title", new com.google.gson.JsonPrimitive("  ")),
                "clearing a title that exists is a change");
        assertTrue(table.save(loaded(), com.mojang.serialization.JsonOps.INSTANCE).ok());
        String cleared = Files.readString(file, StandardCharsets.UTF_8);

        // A blank title is absent, so writing blank over the absent title is the same value again.
        assertFalse(table.set("title", new com.google.gson.JsonPrimitive("")),
                "blank over absent is not a change");
        assertFalse(table.set("entries.0.weight", new com.google.gson.JsonPrimitive(3)),
                "and the weight is still 3");
        assertTrue(table.save(loaded(), com.mojang.serialization.JsonOps.INSTANCE).ok());
        assertEquals(cleared, Files.readString(file, StandardCharsets.UTF_8),
                "a refused no-op writes nothing at all");

        // And it takes no history step: the next undo must reach the last real edit, not a phantom one.
        assertTrue(table.set("entries.0.weight", new com.google.gson.JsonPrimitive(4)));
        assertTrue(table.save(loaded(), com.mojang.serialization.JsonOps.INSTANCE).ok());
        assertNotEquals(cleared, Files.readString(file, StandardCharsets.UTF_8),
                "a real edit does write");
        assertTrue(table.undo());
        assertEquals(3, table.root().getAsJsonArray("entries").get(0).getAsJsonObject()
                .get("weight").getAsInt(), "one undo returns to the value before the real edit");
    }

    @Test
    @DisplayName("entries insert, remove and move, and a batch is one edit")
    void entriesAreEditedAsOneThing() {
        TableEditor table = named();
        JsonObject entry = JsonParser.parseString(
                """
                { "weight": 1, "reward": { "type": "tenet:item", "item": "minecraft:gold_ingot" } }""")
                .getAsJsonObject();

        assertTrue(table.insert(1, entry));
        assertEquals(3, table.entries().size());
        assertTrue(table.move(1, 2));
        assertEquals("minecraft:gold_ingot",
                table.entries().get(2).getAsJsonObject("reward").get("item").getAsString());
        assertTrue(table.remove(2));
        assertEquals(2, table.entries().size());

        // A batch -- what an import is -- is one history step, so one Ctrl+Z takes it all back.
        JsonObject other = entry.deepCopy();
        assertEquals(2, table.insertBatch(0, List.of(entry.deepCopy(), other)));
        assertEquals(4, table.entries().size());
        assertTrue(table.undo());
        assertEquals(2, table.entries().size(), "one undo, the whole import");
    }

    @Test
    @DisplayName("a save refuses an edit that would close a loop, and writes nothing")
    void aClosedCycleIsRefused() throws IOException {
        // The file itself is fine: only the folder knows that ores -> other -> ores is a loop.
        TableEditor table = named();
        JsonObject other = JsonParser.parseString("""
                { "title": "Other", "entries": [ { "weight": 1,
                    "reward": { "type": "tenet:random", "table": "ores" } } ] }
                """).getAsJsonObject();
        Map<String, RewardTable> loaded = loaded();
        loaded.put("other", RewardTable.CODEC.parse(com.mojang.serialization.JsonOps.INSTANCE, other)
                .getOrThrow());

        assertTrue(table.insert(0, JsonParser.parseString("""
                { "weight": 1, "reward": { "type": "tenet:random", "table": "other" } }""")
                .getAsJsonObject()));
        QuestEditor.SaveResult saved = table.save(loaded, com.mojang.serialization.JsonOps.INSTANCE);

        assertFalse(saved.ok(), "the edit would make ores -> other -> ores");
        assertTrue(String.join("\n", saved.messages()).contains("circular table reference"),
                "and the chain is named: " + saved.messages());
        assertEquals(TABLE.trim(), Files.readString(root.resolve("reward_tables/ores.json"),
                StandardCharsets.UTF_8).trim(), "nothing was written");
    }

    // ------------------------------------------------------------------
    // What a table's list holds: an entry, not a reward
    // ------------------------------------------------------------------

    @Test
    @DisplayName("a reward wrapped as a table entry saves; the reward on its own is refused")
    void theClientBuildsAnEntryAndNotAReward() throws IOException {
        // The bug this pins, and it was every press of the table editor's `+ Reward`: the insert was
        // handed a *reward* tree where the file holds `{weight, reward}`. The validator reported
        // `unknown field "type"` and `unknown field "table"` at the new entry and refused the save, so
        // nothing was written and the row never appeared -- and because that button is the only way to
        // put a non-item entry into a table, the whole fold-per-type feature had no entry to edit.
        //
        // The wrapped tree is what the client builds (`QuestPanelLayout.tableEntry`), so this is a test
        // of the client's element rather than of the format: the wrapper saves and the bare reward is
        // refused, which means a future caller that skips the wrapper fails here rather than in a
        // screenshot six hours later.
        JsonObject xp = RewardTypes.defaultTree(Identifier.fromNamespaceAndPath("tenet", "xp"))
                .orElseThrow(() -> new AssertionError("tenet:xp has no default tree, so nothing to add"));

        // The refusal first, because it writes nothing: both halves then start from the same fixture.
        TableEditor bare = named();
        assertTrue(bare.insert(1, xp),
                "the draft takes the raw reward, which is exactly why the refusal has to be explicit");
        QuestEditor.SaveResult refused = bare.save(loaded(), com.mojang.serialization.JsonOps.INSTANCE);
        assertFalse(refused.ok(), "a reward is not an entry, and the save must say so");
        String refusal = String.join("\n", refused.messages());
        assertTrue(refusal.contains("unknown field"), "the refusal names the offending field: " + refusal);
        assertTrue(refusal.contains("entries[1]"), "and the entry it was asked to write: " + refusal);

        TableEditor wrapped = named();
        assertTrue(wrapped.insert(1, QuestPanelLayout.tableEntry(xp)));
        QuestEditor.SaveResult saved = wrapped.save(loaded(), com.mojang.serialization.JsonOps.INSTANCE);
        assertTrue(saved.ok(), () -> "a wrapped reward is an entry and must save: " + saved.messages());

        // Written, not merely accepted: the symptom was that the press changed nothing on disk, so the
        // assertion is about the file rather than about the return value alone.
        String written = Files.readString(root.resolve("reward_tables/ores.json"), StandardCharsets.UTF_8);
        assertTrue(written.contains("\"tenet:xp\""), () -> "the entry reached the file: " + written);
    }

    // ------------------------------------------------------------------
    // A table inside a quest
    // ------------------------------------------------------------------

    @Test
    @DisplayName("a table inside a quest is edited through the chapter, and its undo is the chapter's")
    void questEditsRideTheChapterHistory() throws IOException {
        // The fixture's reward carries a table written inline in the quest file. The panels do not make
        // one of those any more, but a `Select` still writes a reward's reference through this draft,
        // and a file in the wild has one -- so the draft over a quest's file is a live path.
        TableEditor table = inQuest();

        assertTrue(table.set("entries.0.weight", new com.google.gson.JsonPrimitive(7)));
        assertEquals(7, table.root().getAsJsonArray("entries").get(0).getAsJsonObject()
                .get("weight").getAsInt());
        assertTrue(table.save(loaded(), com.mojang.serialization.JsonOps.INSTANCE).ok());

        // The chapter's own save is what wrote it: the quest file on disk has the new weight.
        String written = Files.readString(
                root.resolve("getting_started/first_steps/one.json"), StandardCharsets.UTF_8);
        assertTrue(written.contains("\"weight\": 7") || written.contains("\"weight\":7"), written);

        // And one Ctrl+Z takes it back -- the same key a quest title edit uses.
        assertTrue(table.undo());
        assertFalse(table.root().getAsJsonArray("entries").get(0).getAsJsonObject()
                .get("weight").getAsInt() == 7, "the weight is back to what it was");
    }

    @Test
    @DisplayName("a quest with nothing at the prefix is an empty draft, not a crash")
    void aMissingQuestTableIsEmpty() {
        QuestEditor chapter = QuestEditor.open(root, "first_steps").orElseThrow();
        TableEditor missing = TableEditor.inQuest(chapter, "one", "rewards.9.inline");

        assertFalse(missing.exists());
        assertEquals(List.of(), missing.entries());
        assertFalse(missing.set("title", new com.google.gson.JsonPrimitive("x")),
                "a write to a table that is not there is refused");
    }

    // ------------------------------------------------------------------
    // The wire
    // ------------------------------------------------------------------

    @Test
    @DisplayName("every op survives the wire, address and all")
    void opsRoundTrip() {
        TableAddress file = TableAddress.of("ores");
        TableAddress inQuest = new TableAddress(
                new TableAddress.Owner.InQuest("first_steps", "one"));

        List<TableOp> ops = List.of(
                new TableOp.Set(file, "entries.0.weight", new com.google.gson.JsonPrimitive(2)),
                new TableOp.Set(inQuest, "title", null),
                new TableOp.SetFields(file, JsonParser.parseString(
                        "{ \"entries.0.reward.item\": \"minecraft:iron_ingot\" }").getAsJsonObject()),
                new TableOp.Insert(file, 1, new JsonObject()),
                new TableOp.Remove(file, 0),
                new TableOp.Move(file, 0, 1),
                new TableOp.Undo(file),
                new TableOp.Redo(inQuest),
                new TableOp.Create("new_table", JsonParser.parseString("{ \"entries\": [] }").getAsJsonObject()),
                new TableOp.Duplicate("ores", "ores_copy"),
                new TableOp.Delete("ores"),
                new TableOp.Select(new TableAddress.Owner.Named("ores"), "entries.0.reward", "other"),
                new TableOp.Select(new TableAddress.Owner.InQuest("first_steps", "one"), "rewards.0", ""));

        for (TableOp op : ops) {
            TableOp read = TableOps.read(TableOps.write(op));
            assertEquals(op, read, op.getClass().getSimpleName() + " did not survive the wire");
        }
    }

    @Test
    @DisplayName("a chapter's op is not read as a table's, which is what lets one payload carry both")
    void theFamiliesDoNotCollide() {
        JsonObject chapterOp = new JsonObject();
        chapterOp.addProperty("kind", "field");
        chapterOp.addProperty("quest", "one");
        chapterOp.addProperty("path", "title");
        chapterOp.addProperty("value", "Two");

        assertEquals(null, TableOps.read(chapterOp));
        assertNotNull(EditorOps.read(chapterOp));
        assertEquals(null, TableOps.read(new JsonObject()), "and nonsense is not a table op either");
    }

    @Test
    @DisplayName("a table the loader has not got is refused, and the sentence says so")
    void missingTablesAreRefused() {
        ServerEditors editors = new ServerEditors(() -> root);
        ServerTables tables = new ServerTables(editors, () -> root, this::loaded,
                () -> QuestIndex.build(List.of(), new dev.ellipog.armature.api.data.Problems()));

        EditorOps.Applied applied = apply(tables,new TableOp.Set(TableAddress.of("nowhere"), "title",
                new com.google.gson.JsonPrimitive("x")));

        assertFalse(applied.ok());
        assertTrue(String.join("\n", applied.messages()).contains("nowhere"),
                "the refusal names the table: " + applied.messages());
    }

    private ServerTables tables() {
        return new ServerTables(new ServerEditors(() -> root), () -> root, this::loaded,
                () -> QuestIndex.build(List.of(), new dev.ellipog.armature.api.data.Problems()));
    }

    /**
     * Applies with blind ops: these fixtures carry no registry-backed components, so plain JSON
     * judges what the server's registries would. See the same helper in {@code EditorOpsTest}.
     */
    private static EditorOps.Applied apply(ServerTables tables, TableOp op) {
        return tables.apply(op, com.mojang.serialization.JsonOps.INSTANCE);
    }

    @Test
    @DisplayName("deleting a table twice keeps both copies: the second is numbered, not written over")
    void aTableDeleteNeverReplacesAnEarlierCopy() throws IOException {
        // **The one place in this mod that destroyed a tombstone.** The move carried `REPLACE_EXISTING`,
        // so a table deleted, re-made and deleted again lost the first copy for good -- and a tombstone is
        // the author's own file, which is the one thing a delete is not allowed to throw away. Nothing
        // asserted it either, which is why it survived: the round trip below tests the op's shape, not what
        // it does to a folder.
        ServerTables tables = tables();
        Path file = root.resolve("reward_tables/ores.json");
        Path first = root.resolve("reward_tables/ores.json.deleted");
        String original = Files.readString(file, StandardCharsets.UTF_8);

        assertTrue(apply(tables,new TableOp.Delete("ores")).ok());
        assertTrue(Files.isRegularFile(first), "the file is set aside rather than erased");
        assertEquals(original, Files.readString(first, StandardCharsets.UTF_8), "byte for byte");

        // A second table of the same name, deleted the same way.
        Files.writeString(file, TABLE, StandardCharsets.UTF_8);
        assertTrue(apply(tables,new TableOp.Delete("ores")).ok());

        assertTrue(Files.isRegularFile(first), "the first copy is still there");
        assertEquals(original, Files.readString(first, StandardCharsets.UTF_8), "and untouched");
        assertTrue(Files.isRegularFile(root.resolve("reward_tables/ores.json.deleted.2")),
                "and the second is numbered beside it, which is the rule `isDeletedName` answers for");
    }

    @Test
    @DisplayName("a set-aside table is put back by name, byte for byte")
    void restoresASetAsideTable() throws IOException {
        ServerTables tables = tables();
        Path file = root.resolve("reward_tables/ores.json");
        String original = Files.readString(file, StandardCharsets.UTF_8);
        assertTrue(apply(tables,new TableOp.Delete("ores")).ok());

        EditorOps.Applied restored = apply(tables,new TableOp.Restore("reward_tables/ores.json.deleted"));

        assertTrue(restored.ok(), restored.messages().toString());
        assertEquals(original, Files.readString(file, StandardCharsets.UTF_8), "byte for byte");
        assertFalse(Files.exists(root.resolve("reward_tables/ores.json.deleted")), "and no copy left");

        // And the numbering is honoured: whichever copy is named is the one that comes back, under the
        // name the file had -- so a second delete is not a reason to lose the first copy.
        assertTrue(apply(tables,new TableOp.Delete("ores")).ok());
        Files.writeString(file, TABLE, StandardCharsets.UTF_8);
        assertTrue(apply(tables,new TableOp.Delete("ores")).ok());
        assertTrue(apply(tables,new TableOp.Restore("reward_tables/ores.json.deleted.2")).ok());
        assertTrue(Files.isRegularFile(file), "the numbered copy comes back under its own name");
        assertTrue(Files.isRegularFile(root.resolve("reward_tables/ores.json.deleted")),
                "and the first copy is untouched");
    }

    @Test
    @DisplayName("a table restore refuses a path that is not a set-aside table under the root")
    void aTableRestoreRefusesWhatItCannotDo() throws IOException {
        // The same containment rule the delete's id rule enforces from the other side: the name arrives
        // from a command, and a restore moves a file.
        ServerTables tables = tables();

        assertFalse(apply(tables,new TableOp.Restore("../beside.json.deleted")).ok(),
                "outside the root");
        assertFalse(apply(tables,new TableOp.Restore("getting_started/first_steps/one.json")).ok(),
                "a live file is not a tombstone");
        assertFalse(apply(tables,new TableOp.Restore("reward_tables/nowhere.json.deleted")).ok(),
                "and a name that is not there is not a table");
        assertTrue(Files.isRegularFile(root.resolve("getting_started/first_steps/one.json")),
                "and nothing was moved");
    }

    @Test
    @DisplayName("a table id that is really a path is refused, so a delete cannot rename somebody's quest")
    void aTableIdThatIsAPathIsRefused() throws IOException {
        // The id is joined to `reward_tables/` to make both the file and its `.deleted` aside, and it
        // arrives from the wire: `../getting_started/first_steps/one` passed every check there was -- the
        // existence test found the quest, and the delete moved it, because the aside is a sibling of
        // whatever the id happened to name.
        Path quest = root.resolve("getting_started/first_steps/one.json");

        EditorOps.Applied applied = tables().apply(new TableOp.Delete("../getting_started/first_steps/one"),
                com.mojang.serialization.JsonOps.INSTANCE);

        assertFalse(applied.ok(), "a name is not a path");
        assertTrue(String.join("\n", applied.messages()).contains("no folders"),
                "and the sentence says which rule it broke: " + applied.messages());
        assertTrue(Files.isRegularFile(quest), "and the file it named is exactly where it was");
        assertFalse(Files.exists(quest.resolveSibling("one.json.deleted")), "with no copy beside it");
    }

    @Test
    @DisplayName("a name the loader skips is refused for a new table, and allowed for one already there")
    void anIgnoredNameIsRefusedOnlyWhenMakingOne() throws IOException {
        // A table called `_draft` is a file no walk reads and no reward can name, so making one is the
        // fault `ChapterNaming` refuses one level up. An *existing* file with an odd name is a different
        // question: it is somebody's table, and refusing to edit it would take their pack away from the
        // editor -- which is why the containment rule judges the path and not the spelling.
        ServerTables tables = tables();

        EditorOps.Applied made = apply(tables,new TableOp.Create("_draft",
                JsonParser.parseString("{ \"entries\": [] }").getAsJsonObject()));
        assertFalse(made.ok(), "a name the loader skips is not a table");
        assertFalse(Files.exists(root.resolve("reward_tables/_draft.json")), "and nothing was written");

        // The odd name that exists is editable, because the id is a name in the tables folder.
        Files.writeString(root.resolve("reward_tables/My Table.json"), TABLE, StandardCharsets.UTF_8);
        assertTrue(apply(tables,new TableOp.Set(TableAddress.of("My Table"), "entries.0.weight",
                new com.google.gson.JsonPrimitive(7))).ok(),
                "an existing name is somebody's table, whatever it is spelled like");
    }
}
