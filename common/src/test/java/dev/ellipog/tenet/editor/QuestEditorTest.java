package dev.ellipog.tenet.editor;

import dev.ellipog.tenet.quest.MinecraftTestBootstrap;

import com.google.gson.JsonObject;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A chapter open for editing: the files it is made of, the edits, undo, and a save that validates.
 *
 * <h2>Why these are the tests that matter for T9</h2>
 *
 * <p>Everything here touches files a person wrote. The failures that would hurt are all in this class's
 * neighbourhood: a create that adds a name to the manifest without a file, a delete that loses the file,
 * an undo that restores a chapter the disk does not agree with, a save that writes something the loader
 * will refuse on the next reload. None of them need a client to catch, and a client could not catch them
 * cheaply â€” which is why the model is game-free and the screen is not where this lives.
 *
 * <p>The fixture is a real chapter on disk, built by {@link #chapter()} the way the examples are laid
 * out â€” folder per chapter, manifest listing file names, one file per quest â€” because the editor walks
 * the tree with the loader's own discovery and a fixture that is not the real shape would test nothing.
 */
@DisplayName("A chapter, open for editing")
class QuestEditorTest {

    private static final String MANIFEST = """
            {
              "$schema": "../../_schema/chapter.schema.json",
              "id": "first_steps",
              "title": "First Steps",
              "quests": [ "one.json", "two.json" ]
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

    private static final String TWO = """
            {
              "id": "two",
              "title": "Two",
              "x": 64,
              "y": 0,
              "dependsOn": [ "one" ],
              "icon": { "item": "minecraft:stick" }
            }
            """;

    private Path root;

    @BeforeAll
    static void bootstrapMinecraft() {
        // `save()` runs the loader's own validator, and the validator checks that a named item exists â€”
        // which reads `BuiltInRegistries.ITEM`, empty until vanilla has registered its items. Without
        // this the save tests would fail on a registry rather than on the editor, and worse, the failed
        // class initialiser poisons every other test in the same JVM. See the helper's own note, which
        // describes this exact message.
        MinecraftTestBootstrap.boot();
    }

    @BeforeEach
    void chapter(@TempDir Path dir) throws IOException {
        root = dir.resolve("quests");
        Path folder = root.resolve("getting_started").resolve("first_steps");
        Files.createDirectories(folder);
        Files.writeString(folder.resolve("chapter.json"), MANIFEST, StandardCharsets.UTF_8);
        Files.writeString(folder.resolve("one.json"), ONE, StandardCharsets.UTF_8);
        Files.writeString(folder.resolve("two.json"), TWO, StandardCharsets.UTF_8);

        // A group manifest, because the walk expects one: a chapter is only found under a group.
        Path group = root.resolve("getting_started");
        Files.writeString(group.resolve("group.json"), """
                { "id": "getting_started", "title": "Getting Started", "chapters": [ "first_steps" ] }
                """, StandardCharsets.UTF_8);
    }

    private QuestEditor open() {
        return QuestEditor.open(root, "first_steps").orElseThrow();
    }

    /**
     * A second chapter, in its own group, holding a quest whose id is one this editor would mint.
     *
     * <p>Written rather than built into the fixture because it exists for one question â€” whether an id is
     * free <i>in the pack</i> â€” and every other test in this class is about a chapter on its own.
     */
    private void anotherChapterWithQuestId(String id) throws IOException {
        Path group = root.resolve("later");
        Path folder = group.resolve("second_steps");
        Files.createDirectories(folder);
        Files.writeString(group.resolve("group.json"), """
                { "id": "later", "title": "Later", "chapters": [ "second_steps" ] }
                """, StandardCharsets.UTF_8);
        Files.writeString(folder.resolve("chapter.json"), """
                {
                  "$schema": "../../_schema/chapter.schema.json",
                  "id": "second_steps",
                  "title": "Second Steps",
                  "quests": [ "elsewhere.json" ]
                }
                """, StandardCharsets.UTF_8);
        Files.writeString(folder.resolve("elsewhere.json"), """
                {
                  "id": "%s",
                  "title": "Elsewhere",
                  "x": 0,
                  "y": 0,
                  "icon": { "item": "minecraft:stick" }
                }
                """.formatted(id), StandardCharsets.UTF_8);
    }

    /**
     * A chapter whose quest file is not named after the id it declares — the converted-pack shape.
     *
     * <p>Every fixture in this repository but these names its file after its id, which is why the two
     * vocabularies went unnoticed for so long: the manifest's entry and the tree's key were the same
     * string, so code that used the wrong one still found the right file. A pack converted from the legacy format
     * keeps the legacy hex ids in the files while the converter names the files after the quests' titles, and
     * there the two part company for every quest in the pack.
     *
     * @param dir    the config directory the fixture is built under
     * @param quests the manifest's quest list, in order
     * @return the chapter's folder
     */
    private static Path convertedChapter(Path dir, String... quests) throws IOException {
        Path root = dir.resolve("quests");
        Path folder = root.resolve("pack").resolve("first_steps");
        Files.createDirectories(folder);
        Files.writeString(root.resolve("pack").resolve("group.json"),
                "{ \"id\": \"pack\", \"title\": \"Pack\", \"chapters\": [ \"first_steps\" ] }",
                StandardCharsets.UTF_8);
        Files.writeString(folder.resolve("chapter.json"),
                "{ \"id\": \"first_steps\", \"title\": \"First Steps\", \"quests\": [ "
                        + java.util.Arrays.stream(quests).map(name -> "\"" + name + "\"")
                                .collect(java.util.stream.Collectors.joining(", "))
                        + " ] }",
                StandardCharsets.UTF_8);
        Files.writeString(folder.resolve("first_tree.json"),
                "{ \"id\": \"58b556d40904e3b3\", \"title\": \"First Tree\", \"x\": 0, \"y\": 0 }",
                StandardCharsets.UTF_8);
        return folder;
    }

    @Test
    @DisplayName("a created id is free across the whole pack, not only this chapter")
    void aCreatedIdIsFreeAcrossThePack() throws IOException {
        // **The collision this prevents.** `freeId` checked this chapter's manifest and this chapter's
        // folder, and an id is the key a player's progress is stored against â€” resolved pack-wide by the
        // loader. So a create here that landed on another chapter's id produced one progress record shared
        // by two quests: the first kept, the second drawn and clickable and never able to advance on its
        // own. Nothing refused it, because the id was free where the editor looked.
        anotherChapterWithQuestId("quest");
        QuestEditor editor = open();

        String created = editor.create(0, 0);

        assertNotEquals("quest", created,
                "another chapter already has a quest called `quest`, and two quests cannot share one id");
        assertEquals("quest_2", created,
                "and the next free name is the numbered one, so the id is still recognisable");
    }

    @Test
    @DisplayName("a created id is still free when the collision is in this chapter")
    void aCreatedIdAvoidsThisChapterToo() {
        // The chapter-local half, which already worked: the fix must not lose it while gaining the pack.
        QuestEditor editor = open();
        String first = editor.create(0, 0);
        String second = editor.create(0, 0);

        assertEquals("quest", first);
        assertNotEquals(first, second, "two creates in one chapter cannot share an id either");
        assertEquals("quest_2", second);
    }

    @Test
    @DisplayName("a duplicated id is free across the whole pack too")
    void aDuplicatedIdIsFreeAcrossThePack() throws IOException {
        // The same question asked by duplicate and paste, which mint through the same helper: a copy of
        // `one` must not land on another chapter's `one_copy`.
        anotherChapterWithQuestId("one_copy");
        QuestEditor editor = open();

        String copy = editor.duplicate("one");

        assertNotEquals("one_copy", copy,
                "another chapter has `one_copy`, so the copy needs a different name");
        assertEquals("one_copy_2", copy);
    }

    // ------------------------------------------------------------------
    // Opening
    // ------------------------------------------------------------------

    @Test
    @DisplayName("it opens the chapter's quests, in the manifest's order")
    void opensTheChapter() {
        QuestEditor editor = open();

        assertEquals(java.util.List.of("one", "two"), editor.questIds());
        assertNotNull(editor.quest("one"));
        assertEquals("One", editor.quest("one").text("title", ""));
        assertEquals(64, editor.quest("two").number("x", -1), 0.0001);
        assertFalse(editor.dirty(), "an editor that has changed nothing has nothing to save");
        assertFalse(editor.canUndo());
    }

    @Test
    @DisplayName("a created quest points at the schema beside its chapter's, not one directory deeper")
    void createdQuestSchemaPath() throws IOException {
        // The path is derived from the chapter's own, so a new file resolves at whatever depth the
        // chapter sits. The first version appended "_schema/quest.schema.json" to a prefix that already
        // ended in "_schema/", so every quest created in game carried
        // "../../_schema/_schema/quest.schema.json" -- a path that resolves to nothing. It was found in
        // a player's profile before it was found here, which is the wrong order for a two-line bug.
        Path folder = root.resolve("getting_started").resolve("first_steps");
        Files.writeString(folder.resolve("chapter.json"), """
                {
                  "$schema": "../../_schema/chapter.schema.json",
                  "id": "first_steps",
                  "title": "First Steps",
                  "quests": [ "one.json", "two.json" ]
                }
                """, StandardCharsets.UTF_8);

        QuestEditor editor = open();
        String id = editor.create(16, 16);

        String text = Files.readString(folder.resolve(id + ".json"), StandardCharsets.UTF_8);
        com.google.gson.JsonObject created = com.google.gson.JsonParser.parseString(text).getAsJsonObject();
        assertEquals("../../_schema/quest.schema.json", created.get("$schema").getAsString(),
                "the chapter's directory, and the quest schema's own name -- got: " + text);
    }

    @Test
    @DisplayName("a chapter that is not there, or a root that is not there, is unavailable rather than new")
    void refusesWhatItCannotOpen(@TempDir Path empty) {
        assertTrue(QuestEditor.open(root, "no_such_chapter").isEmpty());
        assertTrue(QuestEditor.open(empty, "first_steps").isEmpty(), "no files, no editor");
        assertTrue(QuestEditor.open(null, "first_steps").isEmpty());
        assertTrue(QuestEditor.open(root, null).isEmpty());
    }

    // ------------------------------------------------------------------
    // Editing
    // ------------------------------------------------------------------

    @Test
    @DisplayName("a field edit lands in the file's own tree and marks the chapter unsaved")
    void editsFields() {
        QuestEditor editor = open();

        assertTrue(editor.set("one", "title", "One, Renamed"));
        assertTrue(editor.set("one", "icon.item", "minecraft:birch_log"));
        assertTrue(editor.set("one", "size", 40));
        assertTrue(editor.set("one", "showTitle", true));
        assertTrue(editor.set("one", "subtitle", null), "null removes the field");

        assertEquals("One, Renamed", editor.quest("one").text("title", ""));
        assertEquals("minecraft:birch_log", editor.quest("one").text("icon.item", ""));
        assertEquals(40, editor.quest("one").number("size", 0), 0.0001);
        assertTrue(editor.quest("one").flag("showTitle", false));
        assertFalse(editor.quest("one").has("subtitle"));
        assertTrue(editor.dirty());
        assertTrue(editor.canUndo());
    }

    @Test
    @DisplayName("an edit to an id the chapter does not hold does nothing, and is not undoable")
    void editsOnlyWhatIsOpen() {
        QuestEditor editor = open();

        assertFalse(editor.set("three", "title", "Three"));
        assertFalse(editor.move("three", 10, 10));
        assertFalse(editor.canUndo(), "a refused edit must not leave an undo entry behind");
        assertFalse(editor.dirty());
    }

    @Test
    @DisplayName("writing one icon arm drops the others, so no file holds two pictures")
    void iconArmWriteClearsSiblings() {
        QuestEditor editor = open();

        assertTrue(editor.set("one", "icon.texture", "my_pack:textures/gui/emblem.png"));
        assertTrue(editor.set("one", "icon.sprite", "occultism:block/chalk_glyph/0"));
        assertEquals("occultism:block/chalk_glyph/0",
                editor.quest("one").text("icon.sprite", ""),
                "the sprite landed");
        assertFalse(editor.quest("one").has("icon.texture"),
                "the texture it replaced is gone, not lingering beside it");

        assertTrue(editor.set("one", "icon.item", "minecraft:torch"));
        assertFalse(editor.quest("one").has("icon.sprite"),
                "an item pick drops the sprite the same way");
        assertEquals("minecraft:torch", editor.quest("one").text("icon.item", ""));
    }

    @Test
    @DisplayName("a move sets both coordinates, and one that changes nothing is not an edit")
    void moves() {
        QuestEditor editor = open();

        assertTrue(editor.move("one", 128, -64));
        assertEquals(128, editor.quest("one").number("x", 0), 0.0001);
        assertEquals(-64, editor.quest("one").number("y", 0), 0.0001);

        int before = editor.canUndo() ? 1 : 0;
        assertFalse(editor.move("one", 128, -64), "the same place is not a change");
        assertTrue(before == 1, "so no undo entry was pushed");
    }

    // ------------------------------------------------------------------
    // Creating, duplicating, deleting
    // ------------------------------------------------------------------

    @Test
    @DisplayName("a created quest is a file and a name in the manifest, and it saves clean")
    void creates() {
        QuestEditor editor = open();

        String id = editor.create(96, 32);
        assertEquals("quest", id, "the first free name");
        assertTrue(editor.questIds().contains(id));
        assertTrue(Files.isRegularFile(editor.pathOf(id)), "the file exists before the manifest names it");
        assertEquals(96, editor.quest(id).number("x", 0), 0.0001);
        assertNotNull(editor.quest(id).text("title", null), "a new quest has a title to replace");
        assertTrue(editor.quest(id).has("tasks"), "and an empty task list, which the format requires");

        QuestEditor.SaveResult saved = editor.save(com.mojang.serialization.JsonOps.INSTANCE);
        assertTrue(saved.ok(), () -> "and what it wrote is loadable: " + saved.messages());

        String second = editor.create(144, 32);
        assertNotEquals(id, second, "a second new quest takes a free name too");
    }

    private static void assertNotEquals(Object a, Object b, String message) {
        org.junit.jupiter.api.Assertions.assertNotEquals(a, b, message);
    }

    @Test
    @DisplayName("a duplicate is a copy beside the original, with its own id and file")
    void duplicates() {
        QuestEditor editor = open();

        String copy = editor.duplicate("two");
        assertNotNull(copy);
        assertEquals(java.util.List.of("one", "two", copy), editor.questIds(),
                "a copy goes at the end of the chapter's list");
        assertEquals(copy, editor.quest(copy).text("id", ""),
                "the copy's id is its own file name, not the one it was copied from");
        assertEquals(112, editor.quest(copy).number("x", 0), 0.0001, "a step to the right of the original");
        assertEquals(java.util.List.of("one"), editor.quest(copy).strings("dependsOn"),
                "a copy depends on what the original did -- the editor does not invent an intent");
        assertTrue(Files.isRegularFile(editor.pathOf(copy)));

        assertNull(editor.duplicate("nothing"), "duplicating what is not open is refused");
    }

    @Test
    @DisplayName("a delete renames the file out of the way and forgets the name")
    void deletes() {
        QuestEditor editor = open();
        Path file = editor.pathOf("two");

        assertTrue(editor.delete("two").ok());
        assertEquals(java.util.List.of("one"), editor.questIds());
        assertNull(editor.quest("two"));
        assertFalse(Files.exists(file), "the file is gone from the folder");
        assertTrue(Files.exists(file.resolveSibling("two.json.deleted")),
                "and is recoverable: a program that deletes an author's file outright is one mis-click"
                        + " from losing an evening's work");
        QuestEditor.Deletion again = editor.delete("two");
        assertFalse(again.ok(), "deleting what is not open is refused");
        assertTrue(again.refusal().contains("two"),
                "and the sentence names what was asked for: " + again.refusal());
    }

    @Test
    @DisplayName("a delete names the aside and the manifest entry after the file, not after the id")
    void deletesAQuestWhoseFileIsNotNamedAfterItsId(@TempDir Path dir) throws IOException {
        // **The divergence, at the one place it reaches the disk.** The manifest lists *file names* while
        // every op, the canvas and the tree speak declared ids, and a converted pack is where the two part
        // company. Naming either the aside or the manifest entry from the id renamed the file and left
        // `chapter.json` still asking for one that was no longer there -- a chapter that loads as an error,
        // over a quest the author believed they had removed.
        Path folder = convertedChapter(dir, "first_tree.json");
        Path root = dir.resolve("quests");

        QuestEditor editor = QuestEditor.open(root, "first_steps").orElseThrow();
        String before = Files.readString(folder.resolve("first_tree.json"), StandardCharsets.UTF_8);

        assertTrue(editor.delete("58b556d40904e3b3").ok());

        assertFalse(Files.exists(folder.resolve("first_tree.json")), "the file is out of the way");
        assertTrue(Files.exists(folder.resolve("first_tree.json.deleted")),
                "named after the file, which is the only name an undo can look for");
        assertFalse(Files.exists(folder.resolve("58b556d40904e3b3.json.deleted")),
                "the declared id names no file here, and must not name the aside either");

        assertTrue(editor.save(com.mojang.serialization.JsonOps.INSTANCE).ok());
        String manifestOnDisk = Files.readString(folder.resolve("chapter.json"), StandardCharsets.UTF_8);
        assertFalse(manifestOnDisk.contains("first_tree.json"),
                () -> "the entry that named the file is the one that goes: " + manifestOnDisk);
        assertTrue(dev.ellipog.tenet.quest.QuestFiles.discover(root).ok(),
                "and the folder still describes itself, which is the whole of the fault: a manifest naming"
                        + " a file that is not there");

        assertTrue(editor.undo(), "and undo is still the way back");
        assertEquals(before, Files.readString(folder.resolve("first_tree.json"), StandardCharsets.UTF_8),
                "byte for byte");
        assertFalse(Files.exists(folder.resolve("first_tree.json.deleted")),
                "with nothing left behind under the aside name");
    }

    @Test
    @DisplayName("a created id avoids a quest file nothing lists, which the loader cannot see")
    void aCreatedIdAvoidsIdsTheLoaderCannotSee(@TempDir Path dir) throws IOException {
        // **The ghost id.** `freeId` minted against the loader's discovery, which answers "what will the
        // loader read" -- so a pack the loader is unhappy with hides ids that are written down anyway, and
        // an unlisted quest file is exactly what a half-converted pack is full of. A create then landed on
        // an id an existing file already declared: two quests under one id are one progress record, the
        // first kept and the second drawn, clickable and never able to advance on its own.
        Path root = dir.resolve("quests");
        Path folder = root.resolve("getting_started").resolve("first_steps");
        Files.createDirectories(folder);
        Files.writeString(root.resolve("getting_started").resolve("group.json"),
                "{ \"id\": \"getting_started\", \"title\": \"Getting Started\", \"chapters\": [ \"first_steps\" ] }",
                StandardCharsets.UTF_8);
        Files.writeString(folder.resolve("chapter.json"),
                "{ \"id\": \"first_steps\", \"title\": \"First Steps\", \"quests\": [ \"one.json\" ] }",
                StandardCharsets.UTF_8);
        Files.writeString(folder.resolve("one.json"), ONE, StandardCharsets.UTF_8);
        // Declares the very id a create would otherwise mint, and nothing will ever read it: the manifest
        // does not list it, so discovery never parses it and the loader never loads it.
        Files.writeString(folder.resolve("ghost.json"),
                "{ \"id\": \"quest\", \"title\": \"Ghost\", \"x\": 0, \"y\": 0 }", StandardCharsets.UTF_8);

        QuestEditor editor = QuestEditor.open(root, "first_steps").orElseThrow();
        String made = editor.create(0, 0);

        assertEquals("quest_2", made,
                "the id is taken on disk even though the loader cannot read the file that declares it");
        assertTrue(Files.isRegularFile(folder.resolve("quest_2.json")), "and the new quest has its own file");
        assertTrue(Files.isRegularFile(folder.resolve("ghost.json")),
                "the file that was in the way is untouched");
    }

    @Test
    @DisplayName("an element is inserted under an id the server chooses, and its tree arrives whole")
    void insertingAnElementKeepsItsTree() {
        QuestEditor editor = open();

        assertTrue(editor.insertElement(0, elementTree("logo")));

        JsonObject placed = firstElement(editor);
        assertEquals("logo", placed.get("id").getAsString());
        assertEquals(64, placed.get("width").getAsInt());
        // The field this build does not read, and the whole reason the tree travels rather than the fields:
        // an element is fifteen fields across four arms, so a reconstruction would drop this one silently.
        assertEquals("#FF00FF", placed.get("badgeColour").getAsString(),
                "an element's tree crosses whole, like a pasted quest's");
    }

    @Test
    @DisplayName("an id already used in the chapter is suffixed, and the asked-for one is preferred when free")
    void aTakenElementIdIsSuffixed() {
        QuestEditor editor = open();
        assertTrue(editor.insertElement(0, elementTree("box")));
        assertTrue(editor.insertElement(1, elementTree("box")));

        com.google.gson.JsonArray elements = elementsOf(editor);
        assertEquals("box", elements.get(0).getAsJsonObject().get("id").getAsString());
        assertEquals("box_2", elements.get(1).getAsJsonObject().get("id").getAsString(),
                "a duplicate keeps what it says under a name nobody else has");
    }

    @Test
    @DisplayName("an element's field is written through its id, and an id that is not there refuses")
    void settingAnElementFieldNeedsTheId() {
        // The refusal is the point of the test. An element is addressed by its id rather than by its
        // position, so a stale id -- one an author deleted while the panel was open -- must write nothing
        // rather than land on whichever element happens to sit at that index now.
        QuestEditor editor = open();
        assertTrue(editor.insertElement(0, elementTree("box")));

        assertTrue(editor.setElement("box", "width", 96));
        assertEquals(96, firstElement(editor).get("width").getAsInt());
        assertEquals("#FF00FF", firstElement(editor).get("badgeColour").getAsString(),
                "and the fields this build does not read are still on disk after a write to one it does");

        assertFalse(editor.setElement("nowhere", "width", 1), "no such element, so nothing is written");
        assertFalse(editor.removeElement("nowhere"), "and nothing is removed either");
        assertEquals(1, elementsOf(editor).size(), "the chapter still has its one element");
        assertEquals(96, firstElement(editor).get("width").getAsInt(), "at the width it was given");
    }

    @Test
    @DisplayName("removing an element takes it out of the chapter's own file, and undo puts it back")
    void removingAnElement() {
        QuestEditor editor = open();
        assertTrue(editor.insertElement(0, elementTree("box")));
        assertTrue(editor.removeElement("box"));
        assertEquals(0, elementsOf(editor).size());

        // The recovery is the snapshot, which is the honest answer for something that owns no file -- see
        // RemoveElement for why there is no tombstone here.
        assertTrue(editor.canUndo(), "the removal is a step in the chapter's history");
    }

    /** This chapter's element list, from the file the editor holds. */
    private static com.google.gson.JsonArray elementsOf(QuestEditor editor) {
        JsonObject chapter = com.google.gson.JsonParser.parseString(editor.chapterJson()).getAsJsonObject();
        return chapter.has("elements")
                ? chapter.getAsJsonArray("elements") : new com.google.gson.JsonArray();
    }

    private static JsonObject firstElement(QuestEditor editor) {
        return elementsOf(editor).get(0).getAsJsonObject();
    }

    /** A picture element with a field this build does not read, for the whole-tree assertions. */
    private static JsonObject elementTree(String id) {
        JsonObject tree = new JsonObject();
        tree.addProperty("type", "image");
        tree.addProperty("id", id);
        tree.addProperty("x", 10);
        tree.addProperty("y", 20);
        tree.addProperty("width", 64);
        tree.addProperty("height", 32);
        JsonObject source = new JsonObject();
        source.addProperty("sprite", "minecraft:block/sculk");
        tree.add("image", source);
        tree.addProperty("badgeColour", "#FF00FF");
        return tree;
    }

    @Test
    @DisplayName("a link is inserted under an id the server chooses, and its tree arrives whole")
    void insertingALinkKeepsItsTree() {
        QuestEditor editor = open();

        assertTrue(editor.insertLink(0, linkTree("gate_hint")));

        JsonObject placed = firstLink(editor);
        assertEquals("gate_hint", placed.get("id").getAsString());
        assertEquals("one", placed.get("quest").getAsString());
        // The field this build does not read, and the whole reason the tree travels rather than the fields:
        // a link is six fields, so a reconstruction would drop this one silently.
        assertEquals("#FF00FF", placed.get("badgeColour").getAsString(),
                "a link's tree crosses whole, like a pasted quest's");
    }

    @Test
    @DisplayName("an id already used by a link is suffixed, and the asked-for one is preferred when free")
    void aTakenLinkIdIsSuffixed() {
        QuestEditor editor = open();
        assertTrue(editor.insertLink(0, linkTree("gate_hint")));
        assertTrue(editor.insertLink(1, linkTree("gate_hint")));

        com.google.gson.JsonArray links = linksOf(editor);
        assertEquals("gate_hint", links.get(0).getAsJsonObject().get("id").getAsString());
        assertEquals("gate_hint_2", links.get(1).getAsJsonObject().get("id").getAsString(),
                "a duplicate keeps what it says under a name nobody else has");
    }

    @Test
    @DisplayName("a link's field is written through its id, and an id that is not there refuses")
    void settingALinkFieldNeedsTheId() {
        // The refusal is the point of the test. A link is addressed by its id rather than by its
        // position, so a stale id -- one an author deleted while the panel was open -- must write nothing
        // rather than land on whichever link happens to sit at that index now.
        QuestEditor editor = open();
        assertTrue(editor.insertLink(0, linkTree("gate_hint")));

        assertTrue(editor.setLink("gate_hint", "x", 96));
        assertEquals(96, firstLink(editor).get("x").getAsInt());
        assertEquals("#FF00FF", firstLink(editor).get("badgeColour").getAsString(),
                "and the fields this build does not read are still on disk after a write to one it does");

        assertFalse(editor.setLink("nowhere", "x", 1), "no such link, so nothing is written");
        assertFalse(editor.removeLink("nowhere"), "and nothing is removed either");
        assertEquals(1, linksOf(editor).size(), "the chapter still has its one link");
        assertEquals(96, firstLink(editor).get("x").getAsInt(), "at the position it was given");
    }

    @Test
    @DisplayName("removing a link takes it out of the chapter's own file, and undo puts it back")
    void removingALink() {
        QuestEditor editor = open();
        assertTrue(editor.insertLink(0, linkTree("gate_hint")));
        assertTrue(editor.removeLink("gate_hint"));
        assertEquals(0, linksOf(editor).size());

        // The recovery is the snapshot, which is the honest answer for something that owns no file -- see
        // RemoveLink for why there is no tombstone here.
        assertTrue(editor.canUndo(), "the removal is a step in the chapter's history");
    }

    /** This chapter's link list, from the file the editor holds. */
    private static com.google.gson.JsonArray linksOf(QuestEditor editor) {
        JsonObject chapter = com.google.gson.JsonParser.parseString(editor.chapterJson()).getAsJsonObject();
        return chapter.has("links")
                ? chapter.getAsJsonArray("links") : new com.google.gson.JsonArray();
    }

    private static JsonObject firstLink(QuestEditor editor) {
        return linksOf(editor).get(0).getAsJsonObject();
    }

    /** A quest link with a field this build does not read, for the whole-tree assertions. */
    private static JsonObject linkTree(String id) {
        JsonObject tree = new JsonObject();
        tree.addProperty("id", id);
        tree.addProperty("quest", "one");
        tree.addProperty("x", 64);
        tree.addProperty("y", -32);
        tree.addProperty("badgeColour", "#FF00FF");
        return tree;
    }

    @Test
    @DisplayName("a delete refuses rather than destroy a copy that is already set aside")
    void aDeleteWillNotDestroyAnEarlierAside() throws IOException {
        // The aside name is fixed so that `restore` can find it -- a snapshot of this chapter's files is
        // taken *before* the delete runs, so it cannot record a variable name the way a structural edit's
        // own steps can. The price of a fixed name is this case, and the price of overwriting it would be
        // an author's file: so the delete refuses, and says which file is in the way.
        QuestEditor editor = open();
        Path file = editor.pathOf("two");
        Path aside = file.resolveSibling("two.json.deleted");
        Files.writeString(aside, "an earlier copy nobody has dealt with", StandardCharsets.UTF_8);

        QuestEditor.Deletion refusal = editor.delete("two");
        assertFalse(refusal.ok(), "deleting would have destroyed that copy");
        assertTrue(refusal.refusal().contains("two.json.deleted"),
                "and the sentence names the copy that is in the way: " + refusal.refusal());

        assertTrue(Files.isRegularFile(file), "so nothing moved");
        assertEquals("an earlier copy nobody has dealt with",
                Files.readString(aside, StandardCharsets.UTF_8), "and the earlier copy is untouched");
        assertEquals(java.util.List.of("one", "two"), editor.questIds(), "the chapter still holds the quest");
        assertFalse(editor.canUndo(),
                "a refusal is not an edit that failed, it is one that never started: no snapshot, no step");
    }

    @Test
    @DisplayName("a delete the manifest cannot account for puts the file back")
    void aDeleteThatCannotBeListedIsRolledBack() throws IOException {
        // The manifest is edited out from under the delete, which is the one way to reach the branch that
        // used to leave a file renamed and a chapter no longer describing its own folder. A refusal has to
        // mean "nothing changed", or a caller cannot tell it from a half-delete -- and the sentence has to
        // name the manifest that could not account for it, which is the thing an author can go and fix.
        QuestEditor editor = open();
        assertTrue(editor.setChapter("quests", java.util.List.of("one.json")));
        Path file = editor.pathOf("two");

        QuestEditor.Deletion refusal = editor.delete("two");
        assertFalse(refusal.ok(), "it could not be accounted for, so it is not a delete");
        assertTrue(refusal.refusal().contains("chapter.json"),
                "and the sentence names the list that does not mention the file: " + refusal.refusal());

        assertTrue(Files.isRegularFile(file), "the file is back where it was");
        assertFalse(Files.exists(file.resolveSibling("two.json.deleted")),
                "and nothing is left under the aside name");
        assertNotNull(editor.quest("two"), "the chapter still holds it");
    }

    @Test
    @DisplayName("two files declaring one id: the first is the one open here, as it is for the loader")
    void aDuplicateIdIsTheLoadersQuestAndNotThisLoops(@TempDir Path dir) throws IOException {
        // **The delete that removed something else.** `reloadQuests` used a plain `put`, so it kept the
        // *last* file declaring an id, while `QuestIndex.claimIdentifier` and `ClientQuestCache.byId` keep
        // the first. The node the author clicks resolves to one file and the delete removed the other --
        // no chapter switch, no stale selection, just two layers disagreeing about one id.
        Path folder = convertedChapter(dir, "first_tree.json", "other.json");
        Files.writeString(folder.resolve("other.json"),
                "{ \"id\": \"58b556d40904e3b3\", \"title\": \"Also First Tree\", \"x\": 0, \"y\": 0 }",
                StandardCharsets.UTF_8);

        QuestEditor editor = QuestEditor.open(dir.resolve("quests"), "first_steps").orElseThrow();

        assertEquals(folder.resolve("first_tree.json"), editor.quest("58b556d40904e3b3").file(),
                "the first file declaring the id, which is the one the loader and the canvas resolve it to");
        assertEquals(java.util.List.of("58b556d40904e3b3"), editor.declaredIds(),
                "one id, one quest: the second file is not a second entry");

        assertTrue(editor.delete("58b556d40904e3b3").ok());
        assertFalse(Files.exists(folder.resolve("first_tree.json")),
                "the quest the author was looking at is the one removed");
        assertTrue(Files.isRegularFile(folder.resolve("other.json")),
                "and the file whose panel they never saw is untouched");
    }

    // ------------------------------------------------------------------
    // Undo
    // ------------------------------------------------------------------

    @Test
    @DisplayName("undo puts an edit back, and the file with it")
    void undoesAnEdit() {
        QuestEditor editor = open();

        editor.move("one", 200, 300);
        assertTrue(editor.undo());
        assertEquals(0, editor.quest("one").number("x", 0), 0.0001);
        assertFalse(editor.canUndo());

        assertTrue(editor.redo());
        assertEquals(200, editor.quest("one").number("x", 0), 0.0001);
    }

    @Test
    @DisplayName("undo puts a deleted quest back, file and all")
    void undoesADelete() throws IOException {
        QuestEditor editor = open();
        String before = Files.readString(editor.pathOf("two"), StandardCharsets.UTF_8);

        assertTrue(editor.delete("two").ok());
        assertFalse(Files.exists(editor.pathOf("two")));

        assertTrue(editor.undo());
        assertEquals(java.util.List.of("one", "two"), editor.questIds());
        assertTrue(Files.isRegularFile(editor.pathOf("two")), "the file is back");
        assertEquals(before, Files.readString(editor.pathOf("two"), StandardCharsets.UTF_8),
                "byte for byte, which is what makes an undo an undo");
    }

    @Test
    @DisplayName("an undo whose file cannot be put back is a refusal, and the retry converges")
    void anUndoThatCannotRestoreSaysSo() throws IOException {
        // **The other half of the silent reversal.** `restore` returned at the first failure and the
        // caller could not tell, so an undo that stopped half way was reported as accepted. It no longer
        // stops at the first one either: every file is attempted, because one locked file must not leave
        // the rest of a chapter unrecovered.
        QuestEditor editor = open();
        assertTrue(editor.delete("two").ok());
        Path back = editor.pathOf("two");
        assertTrue(Files.isRegularFile(back.resolveSibling("two.json.deleted")),
                "the copy an undo moves back");

        // The name the file has to come back to is taken by something that is not empty, so the move
        // cannot replace it.
        Files.createDirectories(back);
        Files.writeString(back.resolve("in-the-way.txt"), "not a quest", StandardCharsets.UTF_8);

        assertFalse(editor.undo(), "the file could not come back, so the undo did not happen");
        String failure = editor.takeLastFailure();
        assertNotNull(failure, "and there is a sentence rather than a bare false");
        assertTrue(failure.contains("could not all be put back"), failure);

        // The history is left as it was found, so the retry converges once the name is free again.
        Files.delete(back.resolve("in-the-way.txt"));
        Files.delete(back);
        assertTrue(editor.undo(), "the retry puts the file back");
        assertTrue(Files.isRegularFile(back), "under its own name");
        assertEquals(java.util.List.of("one", "two"), editor.questIds());
        assertFalse(Files.exists(back.resolveSibling("two.json.deleted")), "and no copy is left behind");
    }

    @Test
    @DisplayName("a set-aside quest file is put back by name, manifest entry and all")
    void restoresASetAsideQuest() throws IOException {
        // **The way back after the history is gone.** The undo stack is the server's memory, so a restart,
        // a `/tenet reload` or sixty further edits take Ctrl+Z away -- while the copy the delete made is
        // still on disk. This is the same operation asked for by name, which is what `/tenet restore` does
        // with the name `/tenet removed` printed.
        QuestEditor editor = open();
        String before = Files.readString(editor.pathOf("two"), StandardCharsets.UTF_8);
        assertTrue(editor.delete("two").ok());
        Path aside = editor.folder().resolve("two.json.deleted");
        assertTrue(Files.isRegularFile(aside), "the copy the restore moves back");

        assertTrue(editor.restoreAside(aside, com.mojang.serialization.JsonOps.INSTANCE).ok());

        assertTrue(Files.isRegularFile(editor.pathOf("two")), "the file is back");
        assertEquals(before, Files.readString(editor.pathOf("two"), StandardCharsets.UTF_8),
                "byte for byte, because a restore that re-serialised is not a restore");
        assertEquals(java.util.List.of("one", "two"), editor.questIds(), "and the manifest lists it again");
        assertFalse(Files.exists(aside), "with no copy left behind");

        // One history step, like every other edit: a restore that could not be taken back would be its own
        // small trap.
        assertTrue(editor.canUndo());
        assertTrue(editor.undo());
        assertFalse(Files.exists(editor.pathOf("two")), "Ctrl+Z sets it aside again");
        assertTrue(Files.isRegularFile(aside));
    }

    @Test
    @DisplayName("a restore refuses rather than overwrite, and says which rule it broke")
    void aRestoreRefusesWhatItCannotDo() throws IOException {
        QuestEditor editor = open();
        assertTrue(editor.delete("two").ok());
        Path aside = editor.folder().resolve("two.json.deleted");

        // The name it would come back to is taken: the move would have to replace a file, and a file is
        // somebody's work -- so the copy stays where it is and the author is told which name is in the way.
        Files.writeString(editor.pathOf("two"), "{\"id\":\"two\"}", StandardCharsets.UTF_8);
        QuestEditor.Deletion taken =
                editor.restoreAside(aside, com.mojang.serialization.JsonOps.INSTANCE);
        assertFalse(taken.ok(), "the file would have been overwritten");
        assertTrue(taken.refusal().contains("already"), taken.refusal());
        Files.delete(editor.pathOf("two"));

        QuestEditor.Deletion notATombstone = editor
                .restoreAside(editor.folder().resolve("one.json"), com.mojang.serialization.JsonOps.INSTANCE);
        assertFalse(notATombstone.ok(), "a live file is not a set-aside one");
        assertTrue(notATombstone.refusal().contains("not a set-aside"), notATombstone.refusal());

        QuestEditor.Deletion elsewhere = editor.restoreAside(
                editor.folder().getParent().resolve("two.json.deleted"),
                com.mojang.serialization.JsonOps.INSTANCE);
        assertFalse(elsewhere.ok(), "and another chapter's copy is not this editor's to move");
        assertTrue(elsewhere.refusal().contains("not in this chapter"), elsewhere.refusal());
    }

    @Test
    @DisplayName("undo takes a created quest away again, file and all")
    void undoesACreate() {
        QuestEditor editor = open();
        String id = editor.create(10, 10);
        Path file = editor.pathOf(id);

        assertTrue(editor.undo());
        assertFalse(editor.questIds().contains(id));
        assertFalse(Files.exists(file), "and the file it wrote is out of the way");
    }

    @Test
    @DisplayName("an undo sets a created quest aside beside a copy already there, and never over it")
    void undoingACreateNeverDestroysAnEarlierAside() throws IOException {
        // **The one that lost an author's file.** The undo renamed a file the snapshot does not hold to the
        // fixed `<name>.json.deleted`, with REPLACE_EXISTING -- while the delete refuses exactly that
        // collision. So a quest deleted and then created again under the same id had its copy destroyed by
        // the next Ctrl+Z, and the Ctrl+Z after that restored the wrong bytes and called the delete undone.
        QuestEditor editor = open();
        String id = editor.create(10, 10);
        Path file = editor.pathOf(id);
        Path earlier = file.resolveSibling(file.getFileName() + ".deleted");
        Files.writeString(earlier, "an earlier copy, which nothing may overwrite", StandardCharsets.UTF_8);

        assertTrue(editor.undo(), "the create is undone");

        assertFalse(Files.exists(file), "the created file is out of the way");
        assertEquals("an earlier copy, which nothing may overwrite",
                Files.readString(earlier, StandardCharsets.UTF_8),
                "and the copy that was already there is untouched");
        assertTrue(Files.isRegularFile(file.resolveSibling(file.getFileName() + ".deleted.2")),
                "so the file the undo set aside went to a numbered name, which is what asidePath is for");
    }

    @Test
    @DisplayName("two undos and two redos around a delete leave the removed copy where it is")
    void theRedoOfACreateDoesNotConsumeTheDeletesAside() throws IOException {
        // **Why numbering the copy is not the whole fix.** The first loop brought a missing file back by
        // moving `<name>.json.deleted` -- *any* copy with that name -- so the redo of a create could move the
        // delete's own copy into place and then write the snapshot's text over it. The author's removed copy
        // was consumed by a keypress that was not about it. So the entry that reverses a delete records the
        // copy it made, and an entry without one writes the text it holds and leaves every copy alone.
        QuestEditor editor = open();
        String before = Files.readString(editor.pathOf("two"), StandardCharsets.UTF_8);

        assertTrue(editor.delete("two").ok());
        Path aside = editor.pathOf("two").resolveSibling("two.json.deleted");
        String removed = Files.readString(aside, StandardCharsets.UTF_8);
        String made = editor.create(10, 10);

        assertTrue(editor.undo(), "the create is undone");
        assertTrue(editor.undo(), "and the delete after it");
        assertEquals(before, Files.readString(editor.pathOf("two"), StandardCharsets.UTF_8),
                "the deleted file is back, byte for byte, from the copy the delete made");

        assertTrue(editor.redo(), "redo the delete");
        assertTrue(editor.redo(), "and redo the create");
        assertEquals(removed, Files.readString(aside, StandardCharsets.UTF_8),
                "the removed copy is still the bytes the delete set aside, which is the whole point");
        assertEquals(java.util.List.of("one", made), editor.questIds(),
                "the chapter is back where the two redos put it, and nothing was resurrected twice");
    }

    @Test
    @DisplayName("a removed quest's name is not minted again, so the new quest can be deleted in turn")
    void aRemovedQuestsNameIsNotTaken() throws IOException {
        // **The in-game four presses.** A tombstone is skipped by every walk, so the id it declared looked
        // free: the next `+` landed on it again -- inheriting the removed quest's stored progress, because
        // progress is keyed by id and an id no loaded quest claims is kept -- and then could not be deleted,
        // because the delete refuses a name a copy already holds. Two faults, one mint.
        QuestEditor editor = open();
        String first = editor.create(10, 10);
        assertTrue(editor.delete(first).ok());
        Path aside = editor.folder().resolve(first + ".json.deleted");
        String removed = Files.readString(aside, StandardCharsets.UTF_8);

        String second = editor.create(20, 20);

        assertNotEquals(first, second, "a removed quest's name is not free");
        assertEquals(removed, Files.readString(aside, StandardCharsets.UTF_8), "its copy is untouched");
        assertTrue(editor.delete(second).ok(), "and the quest just made can be deleted in turn");
    }

    @Test
    @DisplayName("a pasted tree does not take the id a removed copy still declares")
    void aRemovedQuestsDeclaredIdIsNotTaken(@TempDir Path dir) throws IOException {
        // The converted-pack shape, where the id a file declares and the name it is written under differ.
        // The tombstone's declared id is a name a mint must not take, and so is the file it was written as:
        // taking either one gave the new quest the removed quest's progress, and taking the file name gave
        // the chapter a manifest entry naming a file that was never written.
        Path folder = convertedChapter(dir, "first_tree.json");
        Path root = dir.resolve("quests");
        QuestEditor editor = QuestEditor.open(root, "first_steps").orElseThrow();
        JsonObject tree = editor.quest("58b556d40904e3b3").root().deepCopy();

        assertTrue(editor.delete("58b556d40904e3b3").ok());
        Path aside = folder.resolve("first_tree.json.deleted");
        String removed = Files.readString(aside, StandardCharsets.UTF_8);

        String made = editor.paste(tree, 10, 10);

        assertNotEquals("58b556d40904e3b3", made, "the id the removed copy declares is taken");
        assertTrue(Files.isRegularFile(folder.resolve(made + ".json")),
                "the pasted copy lands under its own file name");
        assertEquals(removed, Files.readString(aside, StandardCharsets.UTF_8),
                "and the removed copy is untouched");
        assertTrue(editor.save(com.mojang.serialization.JsonOps.INSTANCE).ok(), "the manifest is written on a save, like every other edit");
        assertTrue(dev.ellipog.tenet.quest.QuestFiles.discover(root).ok(),
                "so the chapter still describes itself");
    }

    @Test
    @DisplayName("a create does not take an id another quest holds as an alias")
    void aMintAvoidsAQuestAlias() throws IOException {
        // Ids and aliases are one namespace to the loader: `claimIdentifier` finds the alias and the later
        // entry is not loaded at all. The panel's `aliases` row is editable, so one typed alias and one press
        // used to make a quest that never appears.
        Files.writeString(root.resolve("getting_started").resolve("first_steps").resolve("one.json"), """
                {
                  "id": "one",
                  "title": "One",
                  "x": 0,
                  "y": 0,
                  "aliases": [ "quest" ],
                  "icon": { "item": "minecraft:oak_log" }
                }
                """, StandardCharsets.UTF_8);

        QuestEditor editor = open();
        String made = editor.create(10, 10);

        assertNotEquals("quest", made, "an alias is a name somebody already answers to");
        assertEquals("quest_2", made, "so the mint steps past it");
    }

    @Test
    @DisplayName("a create never writes over a file whose name it would mint, but whose id is another")
    void aCreateNeverWritesOverAFileNamedLikeTheId(@TempDir Path dir) throws IOException {
        // The mint asked only about ids, so a file *called* `quest.json` that declares an id of its own -- a
        // pack a tool named, which is the shape this editor was taught to write back to -- was invisible to
        // it: the create wrote its own quest over the author's file.
        Path root = dir.resolve("quests");
        Path folder = root.resolve("getting_started").resolve("first_steps");
        Files.createDirectories(folder);
        Files.writeString(root.resolve("getting_started").resolve("group.json"),
                "{ \"id\": \"getting_started\", \"title\": \"Getting Started\","
                        + " \"chapters\": [ \"first_steps\" ] }", StandardCharsets.UTF_8);
        Files.writeString(folder.resolve("chapter.json"),
                "{ \"id\": \"first_steps\", \"title\": \"First Steps\","
                        + " \"quests\": [ \"quest.json\" ] }", StandardCharsets.UTF_8);
        Files.writeString(folder.resolve("quest.json"),
                "{ \"id\": \"something_else\", \"title\": \"Named by a tool\", \"x\": 0, \"y\": 0 }",
                StandardCharsets.UTF_8);

        QuestEditor editor = QuestEditor.open(root, "first_steps").orElseThrow();
        String made = editor.create(10, 10);

        assertNotEquals("quest", made, "the file name is a name too");
        assertTrue(Files.readString(folder.resolve("quest.json"), StandardCharsets.UTF_8)
                .contains("something_else"), "and the author's file is untouched");
    }

    @Test
    @DisplayName("a create steps past a quest file that does not parse")
    void aCreateStepsPastAnUnreadableFile(@TempDir Path dir) throws IOException {
        // The one file the scan cannot name: it reads it, fails, and used to contribute nothing. Its own name
        // can be known even when its contents cannot, and that name is what the create would write over.
        Path root = dir.resolve("quests");
        Path folder = root.resolve("getting_started").resolve("first_steps");
        Files.createDirectories(folder);
        Files.writeString(root.resolve("getting_started").resolve("group.json"),
                "{ \"id\": \"getting_started\", \"title\": \"Getting Started\","
                        + " \"chapters\": [ \"first_steps\" ] }", StandardCharsets.UTF_8);
        Files.writeString(folder.resolve("chapter.json"),
                "{ \"id\": \"first_steps\", \"title\": \"First Steps\","
                        + " \"quests\": [ \"quest.json\" ] }", StandardCharsets.UTF_8);
        Files.writeString(folder.resolve("quest.json"), "{ this is not json", StandardCharsets.UTF_8);

        QuestEditor editor = QuestEditor.open(root, "first_steps").orElseThrow();
        String made = editor.create(10, 10);

        assertNotEquals("quest", made, "the unreadable file's name is taken");
        assertEquals("{ this is not json",
                Files.readString(folder.resolve("quest.json"), StandardCharsets.UTF_8),
                "and it is left exactly as it was");
    }

    @Test
    @DisplayName("a delete forgets the file name it removed, so a later paste writes its own")
    void deleteForgetsTheFileNameItRemoved(@TempDir Path dir) throws IOException {
        // The declared id and the file name are two strings, and the delete has to drop both names from the
        // model. Keeping the file name made a later paste of that id write `first_tree.json` while the
        // manifest listed `58b556d40904e3b3.json`: a chapter naming a file it does not hold, and holding a
        // file it does not name. Reachable once the copy is gone -- moved away by hand, which is what the
        // refusal the delete gives tells the author to do.
        Path folder = convertedChapter(dir, "first_tree.json");
        Path root = dir.resolve("quests");
        QuestEditor editor = QuestEditor.open(root, "first_steps").orElseThrow();
        JsonObject tree = editor.quest("58b556d40904e3b3").root().deepCopy();

        assertTrue(editor.delete("58b556d40904e3b3").ok());
        Files.delete(folder.resolve("first_tree.json.deleted"));

        String made = editor.paste(tree, 10, 10);

        assertEquals("58b556d40904e3b3", made, "with the copy gone, the id is free again");
        assertTrue(Files.isRegularFile(folder.resolve("58b556d40904e3b3.json")),
                "and it is written under the id, not under the removed file's name");
        assertTrue(editor.save(com.mojang.serialization.JsonOps.INSTANCE).ok());
        assertTrue(dev.ellipog.tenet.quest.QuestFiles.discover(root).ok(),
                "so the chapter names the file it holds");
    }

    @Test
    @DisplayName("a pasted tree whose id is a path is refused before anything is written")
    void pasteRefusesANameThatIsAPath(@TempDir Path dir) throws IOException {
        // The id becomes a file name and the tree arrives from a client: `JsonWrite` creates the folders in
        // that path and replaces what it finds, so `../../escaped` wrote outside the chapter folder and
        // destroyed whatever `.json` was there -- before the save ever validated it. The table side refuses
        // the same thing in the same place.
        Path root = dir.resolve("quests");
        convertedChapter(dir, "first_tree.json");
        QuestEditor editor = QuestEditor.open(root, "first_steps").orElseThrow();
        JsonObject tree = editor.quest("58b556d40904e3b3").root().deepCopy();
        tree.addProperty("id", "../../escaped");

        assertNull(editor.paste(tree, 10, 10), "a name that is a path is not a name");
        assertFalse(Files.exists(root.resolve("escaped.json")), "nothing was written outside the folder");
        assertFalse(Files.exists(root.resolve("escaped.json.deleted")), "nor parked there afterwards");
        assertFalse(editor.dirty(), "and no history step was taken for it");
    }

    @Test
    @DisplayName("a new edit forgets the redo trail")
    void redoTrailIsCleared() {
        QuestEditor editor = open();

        editor.move("one", 10, 10);
        editor.undo();
        assertTrue(editor.canRedo());

        editor.move("two", 20, 20);
        assertFalse(editor.canRedo(), "there is no future to redo into any more");
        assertTrue(editor.canUndo());
    }

    // ------------------------------------------------------------------
    // Saving
    // ------------------------------------------------------------------

    @Test
    @DisplayName("a save writes every changed file and leaves nothing dirty")
    void saves() throws IOException {
        QuestEditor editor = open();

        editor.move("one", 32, 32);
        editor.set("two", "title", "Two, Renamed");
        QuestEditor.SaveResult result = editor.save(com.mojang.serialization.JsonOps.INSTANCE);

        assertTrue(result.ok(), () -> "refused: " + result.messages());
        assertEquals(2, result.written());
        assertFalse(editor.dirty());

        assertTrue(Files.readString(editor.pathOf("one"), StandardCharsets.UTF_8).contains("32"));
        assertTrue(Files.readString(editor.pathOf("two"), StandardCharsets.UTF_8)
                .contains("Two, Renamed"));
    }

    @Test
    @DisplayName("a save that would write something unloadable writes nothing at all")
    void aBadEditIsRefusedWhole() throws IOException {
        QuestEditor editor = open();
        String oneBefore = Files.readString(editor.pathOf("one"), StandardCharsets.UTF_8);

        // An icon that is not an item id: the validator knows the format, and this is the smallest edit
        // that breaks it without breaking JSON.
        editor.set("one", "icon.item", "not an item id");
        editor.set("two", "title", "this one is fine");

        QuestEditor.SaveResult result = editor.save(com.mojang.serialization.JsonOps.INSTANCE);

        assertFalse(result.ok(), "the save is refused");
        assertEquals(0, result.written());
        assertTrue(result.messages().stream().anyMatch(message -> message.contains("icon")),
                () -> "the message names the field: " + result.messages());
        assertEquals(oneBefore, Files.readString(editor.pathOf("one"), StandardCharsets.UTF_8),
                "and not one byte of the chapter was written");
        assertTrue(editor.dirty(), "the edits are still there to fix");
    }

    @Test
    @DisplayName("blind ops refuse registry-backed components, readably")
    void blindOpsRefuseComponents(@TempDir Path dir) throws IOException {
        // The boundary the server-ops threading exists for: a save judging with blind ops
        // refuses component types no registry can see — in game those come from the server's
        // own registries (see TenetNetworking.serverOps), which is why the same chapter
        // loads clean and refuses to save. The refusal itself must read as sentences, not
        // record dumps: those lines are toasted at the author.
        Path root = dir.resolve("quests");
        Path folder = root.resolve("pack").resolve("gear");
        Files.createDirectories(folder);
        Files.writeString(folder.resolve("chapter.json"), """
                {
                  "$schema": "../../_schema/chapter.schema.json",
                  "id": "gear",
                  "title": "Gear",
                  "quests": [ "sword.json" ]
                }
                """, StandardCharsets.UTF_8);
        Files.writeString(folder.resolve("sword.json"), """
                {
                  "id": "sword",
                  "title": "Sword",
                  "x": 0, "y": 0,
                  "icon": { "item": "minecraft:iron_sword" },
                  "tasks": [{ "type": "tenet:item", "item": "minecraft:iron_sword",
                              "components": { "no_such_mod:some_component": 1 } }]
                }
                """, StandardCharsets.UTF_8);
        Files.writeString(root.resolve("pack").resolve("group.json"), """
                { "id": "pack", "title": "Pack", "chapters": [ "gear" ] }
                """, StandardCharsets.UTF_8);

        QuestEditor editor = QuestEditor.open(root, "gear").orElseThrow();
        assertTrue(editor.move("sword", 10, 10));

        QuestEditor.SaveResult refused = editor.save(com.mojang.serialization.JsonOps.INSTANCE);
        assertFalse(refused.ok(), "blind ops cannot see component types");
        assertTrue(refused.messages().stream().noneMatch(message -> message.contains("DataProblem[")),
                "and refusals read as sentences, not record dumps: " + refused.messages());
        assertTrue(refused.messages().stream().anyMatch(message -> message.contains("sword.json")),
                "naming the file: " + refused.messages());
    }

    // ------------------------------------------------------------------
    // The group's own file
    // ------------------------------------------------------------------

    @Test
    @DisplayName("the group beside the chapter is edited as a group: its own file, its own validation")
    void editsTheGroup() throws IOException {
        Path groupPath = root.resolve("getting_started").resolve("group.json");
        QuestEditor editor = open();

        assertNotNull(editor.groupJson(), "the manifest beside the chapter is open for editing");
        assertTrue(editor.setGroup("title", "Getting Started, Properly"), "the group's title is set");
        assertTrue(editor.setGroup("icon", icon("minecraft:anvil")), "and its own icon");
        assertTrue(editor.save(com.mojang.serialization.JsonOps.INSTANCE).ok(), "the save validates it as a group document and writes it");

        com.google.gson.JsonObject saved = com.google.gson.JsonParser
                .parseString(Files.readString(groupPath, StandardCharsets.UTF_8)).getAsJsonObject();
        assertEquals("Getting Started, Properly", saved.get("title").getAsString());
        assertEquals("minecraft:anvil", saved.getAsJsonObject("icon").get("item").getAsString(),
                "the write landed in group.json, not in the chapter's file");
        assertEquals("first_steps", saved.getAsJsonArray("chapters").get(0).getAsString(),
                "and the rest of the group is untouched");

        // And an undo puts the group back too -- the snapshot holds it, which is the half of "the group
        // is edited here" that a test of writes alone would miss.
        editor.undo();
        editor.undo();
        assertFalse(editor.groupJson().contains("anvil"), "an undo takes the icon back off");
        assertTrue(editor.groupJson().contains("Getting Started"), "and the title with it");
    }

    @Test
    @DisplayName("a group edit that would not load is refused whole, like every other file")
    void aBadGroupEditIsRefusedWhole() throws IOException {
        Path groupPath = root.resolve("getting_started").resolve("group.json");
        QuestEditor editor = open();
        String before = Files.readString(groupPath, StandardCharsets.UTF_8);

        // A malformed item id, the same shape the chapter's own refusal test uses. An item that is
        // merely *missing* is a warning by design -- the id is kept so a removed mod can come back --
        // so it takes a reference the codec cannot read at all to make the save refuse.
        assertTrue(editor.setGroup("icon", icon("not an item id")),
                "the edit itself is written into the open tree");

        QuestEditor.SaveResult result = editor.save(com.mojang.serialization.JsonOps.INSTANCE);

        assertFalse(result.ok(), "the group's icon is validated as an item reference, like a chapter's");
        assertEquals(0, result.written(), "and nothing at all is written");
        assertTrue(result.messages().stream().anyMatch(message -> message.contains("icon")),
                () -> "the message names the field: " + result.messages());
        assertEquals(before, Files.readString(groupPath, StandardCharsets.UTF_8),
                "group.json is left exactly as it was");
    }

    private static com.google.gson.JsonObject icon(String item) {
        com.google.gson.JsonObject icon = new com.google.gson.JsonObject();
        icon.addProperty("item", item);
        return icon;
    }

    // ------------------------------------------------------------------
    // The group of edits: one step for a gesture
    // ------------------------------------------------------------------

    @Test
    @DisplayName("a group of edits is one history step, and redo puts the whole of it back")
    void aGroupIsOneStep() {
        QuestEditor editor = open();

        editor.group(() -> {
            assertTrue(editor.set("one", "title", "First"));
            assertTrue(editor.set("two", "title", "Second"));
            assertTrue(editor.set("one", "x", 32.0));
        });

        assertEquals("First", editor.quest("one").text("title", ""));
        assertEquals(32.0, editor.quest("one").number("x", -1), 0.0001, "every edit in the group landed");

        assertTrue(editor.undo(), "the group is on the history");
        assertEquals("One", editor.quest("one").text("title", ""), "and one undo takes the whole of it");
        assertEquals("Two", editor.quest("two").text("title", ""));
        assertEquals(0.0, editor.quest("one").number("x", -1), 0.0001);
        assertFalse(editor.canUndo(), "one step, not three");

        assertTrue(editor.redo(), "and the group is one redo as well");
        assertEquals("First", editor.quest("one").text("title", ""));
        assertEquals("Second", editor.quest("two").text("title", ""));
        assertEquals(32.0, editor.quest("one").number("x", -1), 0.0001);
        assertFalse(editor.canRedo(), "one step forward, not three");
    }

    @Test
    @DisplayName("a group cannot nest, and a structural edit cannot join one")
    void aGroupIsOneLevel() {
        QuestEditor editor = open();

        // A nested snapshot would be a step inside a step, and the inner one's undo would leave the
        // author halfway through a gesture they cannot see the edges of.
        assertThrows(IllegalStateException.class, () -> editor.group(() -> editor.group(() -> {
        })));

        // And a structural edit is refused rather than silently left out of the snapshot: its undo is
        // the tree's own record, not this chapter's files.
        assertThrows(IllegalStateException.class,
                () -> editor.group(() -> editor.record(null)));

        // The flag is cleared by the `finally`, so the edits after a refused group are ordinary edits
        // again -- a group abandoned by an exception must not leave every later edit without a step.
        assertTrue(editor.set("one", "title", "After"));
        assertTrue(editor.undo());
        assertEquals("One", editor.quest("one").text("title", ""));
    }

    @Test
    @DisplayName("a quest that declares its own id is keyed by it, and still written to its own file")
    void aQuestThatNamesItselfIsEditable(@TempDir Path dir) throws IOException {
        // **The shape no fixture in this repository had.** Every example here names its file after its id, so
        // the editor's key and the tree's key were the same string and the difference could not show. A pack
        // whose files are named by a tool assigns its own ids -- `first_tree.json` declaring
        // `58b556d40904e3b3` -- and the quest card then asked the replica for an id it did not hold: "the
        // chapter's copy has not arrived yet", with the copy in hand. See `QuestEditor.reloadQuests`,
        // `QuestEditor.pathOf` and `ServerEditors.replica`.
        Path root = dir.resolve("quests");
        Path folder = convertedChapter(dir, "first_tree.json");

        QuestEditor editor = QuestEditor.open(root, "first_steps").orElseThrow();

        // The declared id is the key, which is the whole fix: the loader, the canvas, every reference and the        // client's own `editTarget()` name this quest by the id in its file.
        assertNotNull(editor.quest("58b556d40904e3b3"),
                "the declared id is how every other part of the program names this quest");
        assertEquals("First Tree", editor.quest("58b556d40904e3b3").text("title", ""));

        // And a write still lands on the file that already exists rather than creating a second one beside
        // it -- the id is not the file name, and `pathOf` is the one place the two are reconciled. Its quiet
        // failure is worse than the loud one: two files for one quest.
        assertEquals(folder.resolve("first_tree.json"), editor.pathOf("58b556d40904e3b3"));
        assertEquals("first_tree", editor.stemOf("58b556d40904e3b3"));
        assertEquals(folder.resolve("first_tree.json"), editor.pathOf("first_tree"),
                "and a file name still resolves to its own file");

        // **And the file name is still a way to reach it**, which is the half that was missing: the chapter's
        // manifest lists names while the tree, the card and every op use declared ids, and the replica is
        // built by walking one and read by the other. One file, both vocabularies -- the absence of this
        // assertion is what let a converted pack's replica go out carrying no quests at all.
        JsonFile byName = editor.quest("first_tree");
        assertNotNull(byName, "the manifest's vocabulary must reach the same quest");
        assertSame(editor.quest("58b556d40904e3b3"), byName, "and it is one file, not two");
    }

    @Test
    @DisplayName("and a save writes that file, rather than reporting an edit it never made")
    void aQuestThatNamesItselfIsWritten(@TempDir Path dir) throws IOException {
        // **The half the test above cannot see.** That one asserts how a quest is *reached*; this one
        // asserts what a save *writes*, and the two disagreed: `save()` collected `Path`s and turned each
        // back into a quest with `quests.get(idOf(path))` -- the manifest's vocabulary against a map keyed
        // in the tree's. For a file whose name is not its id the lookup missed, the loop skipped it, and
        // `SaveResult(0, no refusals)` read as a success, so the reply said "applied" over a file that was
        // never touched. A player sees a press that does nothing and a log that says it worked.
        Path root = dir.resolve("quests");
        Path folder = convertedChapter(dir, "first_tree.json");

        QuestEditor editor = QuestEditor.open(root, "first_steps").orElseThrow();
        assertTrue(editor.set("58b556d40904e3b3", "title", "Renamed"));
        assertTrue(editor.set("58b556d40904e3b3", "dependsOn", java.util.List.of("one")),
                "a dependency is a list of ids, which is the edit the card's own rows make");

        QuestEditor.SaveResult result = editor.save(com.mojang.serialization.JsonOps.INSTANCE);

        assertTrue(result.ok(), () -> "refused: " + result.messages());
        assertEquals(1, result.written(), "the one changed quest is the one file written");
        assertFalse(editor.dirty(), "and nothing is left owing a write");

        String onDisk = Files.readString(folder.resolve("first_tree.json"), StandardCharsets.UTF_8);
        assertTrue(onDisk.contains("Renamed"), () -> "the title reached the file: " + onDisk);
        assertTrue(onDisk.contains("dependsOn"), () -> "and so did the dependency: " + onDisk);

        // One file for one quest. A write that took the declared id at face value would have created
        // `58b556d40904e3b3.json` beside it -- the quiet version of the same fault, and worse.
        try (var entries = Files.list(folder)) {
            assertEquals(java.util.List.of("chapter.json", "first_tree.json"),
                    entries.map(path -> path.getFileName().toString()).sorted().toList());
        }
    }
}