package dev.ellipog.tenet.editor;

import com.google.gson.JsonPrimitive;
import dev.ellipog.tenet.quest.MinecraftTestBootstrap;
import dev.ellipog.tenet.quest.QuestLoader;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * The server's open chapters: opened on demand, kept, and dropped by a reload.
 *
 * <h2>The property this class exists for</h2>
 *
 * <p>A history that survives between operations. An editor opened per request would undo nothing — every op
 * would arrive at a fresh model with an empty stack — and the fault would look like Ctrl+Z being broken
 * rather than like the server having forgotten anything, which is why it is asserted here rather than assumed:
 * two edits, then two undos, and the value is back to what the author started with.
 *
 * <p>The second property is the opposite one: a reload <i>may</i> have changed the files underneath, so the
 * cached chapters go and the next op reads them again. Both are about the same question — whether what the
 * server is holding is still what is on the disk — and they are the two answers it can have.
 */
@DisplayName("The server's open chapters")
class ServerEditorsTest {

    private static final String MANIFEST = """
            {
              "$schema": "../../_schema/chapter.schema.json",
              "id": "first_steps",
              "title": "First Steps",
              "quests": [ "one.json" ]
            }
            """;

    private static final String ONE = """
            {
              "id": "one",
              "title": "One",
              "x": 0,
              "y": 0,
              "icon": { "item": "minecraft:oak_log" }
            }
            """;

    private Path root;
    private ServerEditors editors;

    @BeforeAll
    static void bootstrapMinecraft() {
        MinecraftTestBootstrap.boot();
    }

    @BeforeEach
    void chapter(@TempDir Path dir) throws IOException {
        root = dir.resolve("quests");
        Path folder = root.resolve("getting_started").resolve("first_steps");
        Files.createDirectories(folder);
        Files.writeString(folder.resolve("chapter.json"), MANIFEST, StandardCharsets.UTF_8);
        Files.writeString(folder.resolve("one.json"), ONE, StandardCharsets.UTF_8);
        Files.writeString(root.resolve("getting_started").resolve("group.json"), """
                { "id": "getting_started", "title": "Getting Started", "chapters": [ "first_steps" ] }
                """, StandardCharsets.UTF_8);

        editors = new ServerEditors(() -> root);
    }

    private static EditorOp setTitle(String title) {
        return new EditorOp.SetField("one", "title", new JsonPrimitive(title));
    }

    /**
     * Applies with blind ops: these fixtures carry no registry-backed components, so plain JSON
     * judges what the server's registries would. See the same helper in {@code EditorOpsTest}.
     */
    private static EditorOps.Applied apply(ServerEditors editors, String chapter, EditorOp op) {
        return editors.apply(chapter, op, com.mojang.serialization.JsonOps.INSTANCE);
    }

    private String titleOnDisk() throws IOException {
        return Files.readString(root.resolve("getting_started").resolve("first_steps").resolve("one.json"),
                StandardCharsets.UTF_8);
    }

    @Test
    @DisplayName("a chapter opens on the first op about it, and the history carries on across ops")
    void theHistorySurvives() throws IOException {
        assertFalse(editors.isOpen("first_steps"), "nothing is open until something is edited");

        assertTrue(apply(editors,"first_steps", setTitle("Second")).ok());
        assertTrue(editors.isOpen("first_steps"));
        assertTrue(apply(editors,"first_steps", new EditorOp.Move("one", 128, 64)).ok());

        // The two undos are the assertion: the first takes back the move, the second the rename, which can
        // only happen if both ops were applied to the *same* instance.
        assertTrue(apply(editors,"first_steps", new EditorOp.Undo()).ok());
        assertTrue(apply(editors,"first_steps", new EditorOp.Undo()).ok());

        assertTrue(titleOnDisk().contains("\"One\""), "the file is back to what the author started with");
        assertTrue(titleOnDisk().contains("\"x\": 0"), "and so is the position");
    }

    @Test
    @DisplayName("a batch applied through the server is one step: one undo takes the whole gesture back")
    void aBatchThroughTheServer() throws IOException {
        // The path a bulk gesture actually travels: one payload, one op, one chapter's editor. What this
        // adds to `EditorOpsTest` is the server's half -- the cached editor is found by the chapter the
        // client named, and its history is the one the batch joins.
        EditorOps.Applied applied = apply(editors,"first_steps", EditorOps.batch(List.of(
                new EditorOp.SetField("one", "title", new JsonPrimitive("First")),
                new EditorOp.Move("one", 128, 64))));

        assertTrue(applied.ok(), () -> "refused: " + applied.messages());
        assertTrue(titleOnDisk().contains("\"First\""), "both edits landed, and both are on the disk");

        assertTrue(apply(editors,"first_steps", new EditorOp.Undo()).ok(), "and one undo is enough");
        assertTrue(titleOnDisk().contains("\"One\""));
        assertTrue(titleOnDisk().contains("\"x\": 0"), "the whole gesture is back, both edits of it");
    }

    @Test
    @DisplayName("a reload drops the open chapters, so the next op reads the files again")
    void forgetStartsAgain() {
        assertTrue(apply(editors,"first_steps", setTitle("Second")).ok());

        editors.forget();

        assertFalse(editors.isOpen("first_steps"));
        // Nothing to undo, because the model that remembered the edit is gone: an undo across a reload would
        // be the server contradicting files it has not read.
        EditorOps.Applied undone = apply(editors,"first_steps", new EditorOp.Undo());
        assertFalse(undone.ok());
        assertFalse(undone.messages().isEmpty(), "and it says why rather than doing nothing quietly");
    }

    @Test
    @DisplayName("deleting the last chapter keeps a way back, with no chapter left to name")
    void theLastChapterIsStillUndoable() throws IOException {
        // **The hole this closes.** `follow` moves the acting history onto the first surviving chapter, and
        // when there is none it dropped it -- so deleting the only chapter in a book left a Ctrl+Z that did
        // nothing, at the exact moment an author wants it, while the folder sat beside the tree as
        // `first_steps.deleted`. The client's effective chapter is empty now, so the op names none, which is
        // why the history has to be reachable without a session.
        Path folder = root.resolve("getting_started").resolve("first_steps");
        assertTrue(apply(editors,"first_steps", new EditorOp.DeleteChapter("first_steps")).ok());
        assertFalse(Files.exists(folder), "the chapter is out of the tree");
        assertTrue(Files.isRegularFile(root.resolve("getting_started/first_steps.deleted/chapter.json")),
                "and set aside, which is what makes an undo possible at all");

        EditorOps.Applied undone = apply(editors,"", new EditorOp.Undo());

        assertTrue(undone.ok(), () -> "the last chapter's delete is undoable: " + undone.messages());
        assertTrue(Files.isRegularFile(folder.resolve("chapter.json")), "the chapter is back");
        assertTrue(Files.isRegularFile(folder.resolve("one.json")), "with its quests");
        assertFalse(Files.exists(root.resolve("getting_started/first_steps.deleted")), "and no copy left");
        assertTrue(editors.isOpen("first_steps"), "and it is open again, under its own id");

        // **And the history came across rather than being thrown away**: the delete is now ahead of it, so
        // Ctrl+Y puts the chapter away again.
        assertTrue(apply(editors,"first_steps", new EditorOp.Redo()).ok(),
                "the rest of that history is not stranded either");
        assertFalse(Files.exists(folder), "so the redo takes the chapter out again");
    }

    @Test
    @DisplayName("a restore is the way back when the history is gone, which is what a restart leaves")
    void aRestoreWorksWithNoHistory() throws IOException {
        // **The case the whole pair exists for.** `/tenet reload` drops every open editor, and a server
        // restart does the same by ending the process -- so the delete's own undo is gone while the folder
        // it set aside is still on disk. Nothing else in the game can reach it: every walk skips a
        // tombstone, so the tree the editor draws does not know it is there.
        Path folder = root.resolve("getting_started").resolve("first_steps");
        assertTrue(apply(editors,"first_steps", new EditorOp.DeleteChapter("first_steps")).ok());
        editors.forget();
        assertFalse(editors.isOpen("first_steps"), "the model is gone, which is what a restart leaves");

        EditorOps.Applied restored = apply(editors,"",
                new EditorOp.RestoreRemoved("getting_started/first_steps.deleted"));

        assertTrue(restored.ok(), () -> "a restore needs no history: " + restored.messages());
        assertTrue(Files.isRegularFile(folder.resolve("chapter.json")), "the chapter is back");
        assertTrue(Files.isRegularFile(folder.resolve("one.json")), "with its quests");
        assertFalse(editors.isOpen("first_steps"),
                "a sessionless restore opens no session -- the same as the first chapter a book is given");
        assertTrue(apply(editors,"first_steps", setTitle("Second")).ok(),
                "and the chapter it put back is editable, which is what a restore is for");
    }

    @Test
    @DisplayName("a replica carries each quest's own tree, opened by the read itself")
    void theReplicaIsTheFiles() {
        var all = editors.replica("first_steps");

        assertFalse(all == null || all.isEmpty());
        assertEquals(1, all.size(), "one quest in this fixture");
        var one = all.getAsJsonObject("one");
        assertEquals("One", one.get("title").getAsString());
        assertEquals("minecraft:oak_log", one.getAsJsonObject("icon").get("item").getAsString());
        assertTrue(editors.isOpen("first_steps"), "a read opens the chapter it read");

        assertNull(editors.replica("nowhere"));
        assertNull(editors.replica(null));
    }

    @Test
    @DisplayName("a chapter that is not there, or no chapter at all, is a refusal with a sentence")
    void refusals() {
        EditorOps.Applied missing = apply(editors,"nowhere", setTitle("Second"));
        assertFalse(missing.ok());
        assertEquals(1, missing.messages().size());
        assertFalse(editors.isOpen("nowhere"), "a refusal does not open anything");

        assertFalse(apply(editors,null, setTitle("Second")).ok());
        assertFalse(apply(editors,"  ", setTitle("Second")).ok());
    }

    @Test
    @DisplayName("an edit aimed at a quest this chapter does not hold is refused, and names both")
    void anOpForAnotherChaptersQuestIsRefused() throws IOException {
        // The op names a quest and the payload names a chapter, and on the client the two come from
        // different places: the selection is the screen's and the chapter is the one it is looking at, so a
        // card left open across a switch is a pair that no longer agrees. Left to each mutation's own
        // `map.get(id) == null` the disagreement arrived as "that edit would change nothing" -- a sentence
        // that reads as a no-op when the truth is that the edit was aimed somewhere else, which is also the
        // shape a destructive op would silently take.
        String before = titleOnDisk();

        EditorOps.Applied field = apply(editors,"first_steps",
                new EditorOp.SetField("elsewhere", "title", new JsonPrimitive("Moved")));
        assertFalse(field.ok());
        assertEquals(1, field.messages().size());
        assertTrue(field.messages().get(0).contains("elsewhere"), field.messages().toString());
        assertTrue(field.messages().get(0).contains("first_steps"),
                "the refusal names the chapter it was aimed at: " + field.messages());

        assertFalse(apply(editors,"first_steps", new EditorOp.Delete("elsewhere")).ok(),
                "the destructive one is refused by the same rule");
        assertTrue(apply(editors,"first_steps", new EditorOp.Delete("elsewhere")).messages().get(0)
                        .contains("elsewhere"),
                "and it names the quest too, rather than reporting a no-op");

        assertEquals(before, titleOnDisk(), "and nothing was written by either");
    }

    @Test
    @DisplayName("a batch is checked too: a gesture aimed at another chapter is refused whole")
    void aBatchForAnotherChaptersQuestIsRefused() throws IOException {
        // A batch answers `quest()` with null -- it is the gesture rather than a quest -- so a check that
        // asked only the op itself would let every element of a bulk edit through the one gate that has to
        // hold for all of them. Checked before anything runs, so a refused gesture is refused entirely
        // rather than half-applied.
        EditorOps.Applied applied = apply(editors,"first_steps", EditorOps.batch(List.of(
                new EditorOp.SetField("one", "title", new JsonPrimitive("First")),
                new EditorOp.SetField("elsewhere", "title", new JsonPrimitive("Moved")))));

        assertFalse(applied.ok());
        assertTrue(applied.messages().get(0).contains("elsewhere"), applied.messages().toString());
        assertFalse(titleOnDisk().contains("First"),
                "refused before any element ran, so the half that was fine did not land either");
    }

    @Test
    @DisplayName("an edit the validator refuses leaves the chapter open and the disk untouched")
    void aRefusalKeepsTheChapter() throws IOException {
        String before = titleOnDisk();
        // An unknown field, not an unknown type: a missing item became a warning when the id was kept and
        // the row marked, and an unknown type became one when the dispatch learned to decode it to a
        // placeholder. This test needs a fault the loader truly refuses, and a field no type declares is
        // the one that remains an error.
        com.google.gson.JsonObject mystery = new com.google.gson.JsonObject();
        mystery.addProperty("type", "tenet:checkmark");
        mystery.addProperty("title", "Did it");
        mystery.addProperty("splines", 4);

        EditorOps.Applied refused = apply(editors,"first_steps",
                new EditorOp.Insert("one", "tasks", 0, mystery));

        assertFalse(refused.ok());
        assertTrue(editors.isOpen("first_steps"), "the chapter is still the one being edited");
        assertEquals(before, titleOnDisk(), "and nothing was written");
    }

    @Test
    @DisplayName("a renamed chapter's editor follows it, history and all")
    void aRenamedChapterKeepsItsHistory() throws IOException {
        // The cache is keyed by chapter id and an editor is bound to a folder, so a rename breaks both
        // halves at once: the key is stale and the path is gone. What must survive is the history -- a
        // re-opened editor with an empty stack would make Ctrl+Z after a rename a key that does nothing,
        // which is exactly the fault this class was written to prevent for ordinary edits.
        assertTrue(apply(editors,"first_steps", setTitle("Edited")).ok());

        EditorOps.Applied renamed = apply(editors,"first_steps",
                new EditorOp.RenameChapter("first_steps", "renamed_chapter", null));

        assertTrue(renamed.ok(), renamed.messages().toString());
        assertFalse(editors.isOpen("first_steps"), "the editor under the old id is gone");
        assertTrue(editors.isOpen("renamed_chapter"), "one is open at the new id");

        // Undo is last-in-first-out, so the rename itself comes back first: the folder returns to its
        // old name and the cache follows it there -- which is the half that a stale editor would get
        // wrong, writing the next edit into a folder nobody named any more.
        EditorOps.Applied undid = apply(editors,"renamed_chapter", new EditorOp.Undo());
        assertTrue(undid.ok(), undid.messages().toString());
        assertTrue(Files.isRegularFile(root.resolve("getting_started").resolve("first_steps")
                .resolve("chapter.json")), "the folder is back where it was");
        assertTrue(editors.isOpen("first_steps"),
                "and the cache is keyed where the folder actually is");

        // And the edit made before the rename is still behind it, at the id it now has.
        assertTrue(apply(editors,"first_steps", new EditorOp.Undo()).ok(),
                "the title edit made before the rename is still undoable");
        assertFalse(titleOnDisk().contains("Edited"), titleOnDisk());
    }

    @Test
    @DisplayName("a blank session can create the first chapter of an empty tree")
    void aBlankSessionCreatesTheFirstChapter(@TempDir Path empty) throws IOException {
        // The empty-pack case: no chapter means no editor to open and no history to record on, so the op
        // arrives with an empty session. Creating a chapter is still something to do there, and it is
        // the only way in from the book.
        Path bare = empty.resolve(QuestLoader.DIRECTORY);
        Files.createDirectories(bare);
        ServerEditors bareEditors = new ServerEditors(() -> bare);

        EditorOps.Applied applied = apply(bareEditors,"",
                new EditorOp.CreateChapter("", 0, "first", "First"));

        assertTrue(applied.ok(), applied.messages().toString());
        assertTrue(Files.isRegularFile(bare.resolve("first/chapter.json")), "the chapter exists");
        assertTrue(Files.isRegularFile(bare.resolve("index.json")),
                "and the root order names it, so the tree loads");
        QuestLoader.Result loaded = QuestLoader.load(empty);
        assertTrue(loaded.ok(), () -> "the created tree has to load cleanly:\n"
                + loaded.problems().all().stream().map(problem -> problem.render())
                        .reduce("", (a, b) -> a + "\n" + b));
        assertTrue(loaded.index().chapter("first").isPresent());
    }

    @Test
    @DisplayName("and a blank session still refuses an edit that needs a chapter")
    void aBlankSessionRefusesFieldEdits(@TempDir Path empty) throws IOException {
        Path bare = empty.resolve(QuestLoader.DIRECTORY);
        Files.createDirectories(bare);
        ServerEditors bareEditors = new ServerEditors(() -> bare);

        EditorOps.Applied applied = apply(bareEditors,"", setTitle("x"));

        assertFalse(applied.ok());
        assertTrue(applied.messages().toString().contains("chapter"),
                "the refusal names what is missing: " + applied.messages());
    }

    @Test
    @DisplayName("a replica of a pack whose files are named differently from their ids carries them anyway")
    void theReplicaSpeaksBothVocabularies(@TempDir Path dir) throws IOException {
        // **The converted pack.** A pack from the legacy format keeps the legacy hex ids in the files while the converter
        // names the files after the quests' titles, so the manifest's vocabulary and the tree's are different
        // strings for the same quest. Building the replica by walking the manifest and looking each name up in
        // an editor keyed by declared ids returned **nothing**, and the panel -- which asks by the tree's id --
        // read an empty copy as "the copy has not arrived". Both keys, one file, and the size of the map is
        // the assertion that would have caught it.
        Path root = dir.resolve("quests");
        Path folder = root.resolve("pack").resolve("first_steps");
        Files.createDirectories(folder);
        Files.writeString(root.resolve("pack").resolve("group.json"),
                "{ \"id\": \"pack\", \"title\": \"Pack\", \"chapters\": [ \"first_steps\" ] }",
                StandardCharsets.UTF_8);
        Files.writeString(folder.resolve("chapter.json"),
                "{ \"id\": \"first_steps\", \"title\": \"First Steps\", \"quests\": [ \"first_tree.json\" ] }",
                StandardCharsets.UTF_8);
        Files.writeString(folder.resolve("first_tree.json"),
                "{ \"id\": \"58b556d40904e3b3\", \"title\": \"First Tree\", \"x\": 0, \"y\": 0 }",
                StandardCharsets.UTF_8);

        ServerEditors converted = new ServerEditors(() -> root);
        var all = converted.replica("first_steps");

        assertFalse(all == null || all.isEmpty(),
                "an empty replica is what the panel reports as a copy that never arrived");
        var byId = all.getAsJsonObject("58b556d40904e3b3");
        assertNotNull(byId, "the id the tree, the card and every op use");
        assertEquals("First Tree", byId.get("title").getAsString());
        var byName = all.getAsJsonObject("first_tree");
        assertNotNull(byName, "and the file name the chapter's own manifest lists");
        assertEquals("First Tree", byName.get("title").getAsString(),
                "the same quest under both keys, not two quests");
    }
}