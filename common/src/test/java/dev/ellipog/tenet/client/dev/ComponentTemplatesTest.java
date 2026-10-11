package dev.ellipog.tenet.client.dev;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import org.junit.jupiter.api.AfterEach;
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

/**
 * The template library: shipped rows always present, saved rows round-tripped, sorted three
 * ways, and a corrupt file that costs the shelf nothing.
 */
@DisplayName("The component template library")
class ComponentTemplatesTest {

    @AfterEach
    void forget() {
        // Statics with a static path: a test that leaves either set changes the next one.
        ComponentTemplates.reset();
    }

    private static JsonObject tree(String json) {
        return JsonParser.parseString(json).getAsJsonObject();
    }

    @Test
    @DisplayName("the shipped presets are there before anything is saved")
    void presetsAreListedFirst() {
        assertTrue(ComponentTemplates.saved().isEmpty(), "nothing saved yet");
        assertFalse(ComponentTemplates.builtIns().isEmpty(), "but the presets are");
        assertTrue(ComponentTemplates.all().size() >= ComponentTemplates.builtIns().size());
        assertTrue(ComponentTemplates.byId("built_in/gather_oak_logs").isPresent(),
                "under the reserved prefix");
    }

    @Test
    @DisplayName("saving, writing and reading back")
    void roundTrip(@TempDir Path dir) throws IOException {
        Path file = dir.resolve(ComponentTemplates.FILE_NAME);
        ComponentTemplates.load(file);
        assertEquals(file, ComponentTemplates.file(), "the file it read is the file it will write");

        ComponentTemplate.Template saved = ComponentTemplates.saveNew(ComponentTemplate.Kind.TASK,
                "tenet:item", tree("{\"type\": \"tenet:item\", \"item\": \"minecraft:oak_log\","
                        + " \"count\": 8}"),
                "Gather oak logs");
        assertEquals("gather_oak_logs", saved.id(), "slugged from the name");
        assertTrue(Files.isRegularFile(file), "saving writes the file");

        ComponentTemplate.Template again = ComponentTemplates.saveNew(ComponentTemplate.Kind.TASK,
                "tenet:item", tree("{\"type\": \"tenet:item\", \"item\": \"minecraft:oak_log\"}"),
                "Gather oak logs");
        assertEquals("gather_oak_logs_2", again.id(), "the ladder, like the elements'");

        ComponentTemplates.load(file);
        assertEquals(2, ComponentTemplates.saved().size(), "both survive the round trip");
        assertTrue(ComponentTemplates.byId("gather_oak_logs").isPresent());
    }

    @Test
    @DisplayName("a saved template without a type in its tree still carries one")
    void savedTreesNameTheirType(@TempDir Path dir) {
        ComponentTemplates.load(dir.resolve(ComponentTemplates.FILE_NAME));
        ComponentTemplate.Template saved = ComponentTemplates.saveNew(ComponentTemplate.Kind.REWARD,
                "tenet:xp", tree("{\"amount\": 10}"), "Some XP");
        assertEquals("tenet:xp", saved.payload().get("type").getAsString(),
                "so Apply never has to guess what it is inserting");
    }

    @Test
    @DisplayName("only saved rows delete, and deletion is what the file says afterwards")
    void deleteRules(@TempDir Path dir) {
        Path file = dir.resolve(ComponentTemplates.FILE_NAME);
        ComponentTemplates.load(file);
        ComponentTemplates.saveNew(ComponentTemplate.Kind.TASK, "tenet:kill",
                tree("{\"entity\": \"minecraft:zombie\"}"), "Zombies");

        assertFalse(ComponentTemplates.delete("built_in/gather_oak_logs"),
                "shipped rows are not deletable");
        assertFalse(ComponentTemplates.delete("no_such_template"), "nor are unknown ids");
        assertTrue(ComponentTemplates.delete("zombies"));
        assertTrue(ComponentTemplates.saved().isEmpty());

        ComponentTemplates.load(file);
        assertTrue(ComponentTemplates.saved().isEmpty(), "the deletion survived the file");
    }

    @Test
    @DisplayName("the same setup twice is one row, whatever it is called")
    void duplicatesAreOneRow(@TempDir Path dir) {
        ComponentTemplates.load(dir.resolve(ComponentTemplates.FILE_NAME));
        ComponentTemplates.saveNew(ComponentTemplate.Kind.TASK, "tenet:kill",
                tree("{\"type\": \"tenet:kill\", \"entity\": \"minecraft:zombie\", \"value\": 3}"),
                "Three zombies");

        assertEquals("three_zombies", ComponentTemplates
                .duplicateOf(ComponentTemplate.Kind.TASK, "tenet:kill",
                        tree("{\"value\": 3, \"entity\": \"minecraft:zombie\", \"type\": \"tenet:kill\"}"))
                .orElseThrow().id(),
                "key order and nothing else differs");
        assertTrue(ComponentTemplates.duplicateOf(ComponentTemplate.Kind.TASK, "tenet:item",
                tree("{\"type\": \"tenet:item\", \"item\": \"minecraft:oak_log\", \"count\": 9}")).isEmpty(),
                "a different count is a different setup");
        assertTrue(ComponentTemplates.duplicateOf(ComponentTemplate.Kind.REWARD, "tenet:item",
                tree("{\"type\": \"tenet:item\", \"item\": \"minecraft:oak_log\", \"count\": 8}")).isEmpty(),
                "and so is the same tree on the other list");
        assertTrue(ComponentTemplates
                .duplicateOf(ComponentTemplate.Kind.TASK, "tenet:kill",
                        tree("{\"entity\": \"minecraft:zombie\"}"))
                .isEmpty(), "no such setup saved");
    }

    @Test
    @DisplayName("a shipped setup cannot be saved again either")
    void presetsCountAsDuplicates(@TempDir Path dir) {
        ComponentTemplates.load(dir.resolve(ComponentTemplates.FILE_NAME));
        assertTrue(ComponentTemplates.duplicateOf(ComponentTemplate.Kind.TASK, "tenet:item",
                tree("{\"type\": \"tenet:item\", \"item\": \"minecraft:oak_log\", \"count\": 8}"))
                .map(template -> template.id().equals("built_in/gather_oak_logs")).orElse(false),
                "the star on an identical setup points at the preset instead");
    }

    @Test
    @DisplayName("renaming changes the words, never the key, and refuses quietly otherwise")
    void renameRules(@TempDir Path dir) throws IOException {
        Path file = dir.resolve(ComponentTemplates.FILE_NAME);
        ComponentTemplates.load(file);
        ComponentTemplates.saveNew(ComponentTemplate.Kind.TASK, "tenet:kill",
                tree("{\"entity\": \"minecraft:zombie\"}"), "Zombies");

        assertTrue(ComponentTemplates.rename("zombies", "  Undead horde  "));
        assertEquals("Undead horde", ComponentTemplates.byId("zombies").orElseThrow().name(),
                "trimmed, and the id is untouched");
        assertEquals("tenet:kill", ComponentTemplates.byId("zombies").orElseThrow().typeId(),
                "and the setup with it");

        assertFalse(ComponentTemplates.rename("zombies", "   "), "a blank name keeps the old one");
        assertEquals("Undead horde", ComponentTemplates.byId("zombies").orElseThrow().name());
        assertFalse(ComponentTemplates.rename("no_such_template", "Ghost"));
        assertFalse(ComponentTemplates.rename("built_in/gather_oak_logs", "Mine now"),
                "shipped rows are not renamable");

        ComponentTemplates.load(file);
        assertEquals("Undead horde", ComponentTemplates.byId("zombies").orElseThrow().name(),
                "the rename survived the file");
    }

    @Test
    @DisplayName("recent sorts applied before never, name sorts by words, type groups by kind")
    void threeSorts(@TempDir Path dir) {
        ComponentTemplates.load(dir.resolve(ComponentTemplates.FILE_NAME));
        ComponentTemplates.saveNew(ComponentTemplate.Kind.REWARD, "tenet:xp", tree("{}"), "Zebra reward");
        ComponentTemplates.saveNew(ComponentTemplate.Kind.TASK, "tenet:kill", tree("{}"), "Apple task");
        ComponentTemplates.markUsed("apple_task");

        List<String> recent = ComponentTemplates.sorted(ComponentTemplates.Sort.RECENT).stream()
                .filter(template -> !template.builtIn()).map(ComponentTemplate.Template::id).toList();
        assertEquals(List.of("apple_task", "zebra_reward"), recent,
                "applied first, never-applied after");

        List<String> names = ComponentTemplates.sorted(ComponentTemplates.Sort.NAME).stream()
                .filter(template -> !template.builtIn()).map(ComponentTemplate.Template::name).toList();
        assertEquals(List.of("Apple task", "Zebra reward"), names);

        List<ComponentTemplate.Kind> kinds = ComponentTemplates.sorted(ComponentTemplates.Sort.TYPE)
                .stream().map(ComponentTemplate.Template::kind).toList();
        assertTrue(kinds.indexOf(ComponentTemplate.Kind.TASK) < kinds.indexOf(ComponentTemplate.Kind.REWARD),
                "tasks group before rewards: " + kinds);
    }

    @Test
    @DisplayName("a bad entry is skipped and a bad file is an empty shelf, never a throw")
    void tolerantParse(@TempDir Path dir) throws IOException {
        Path file = dir.resolve(ComponentTemplates.FILE_NAME);
        Files.writeString(file, "{\"templates\": ["
                + "{\"id\": \"good\", \"name\": \"Good\", \"kind\": \"TASK\", \"type\": \"tenet:item\","
                + " \"payload\": {\"type\": \"tenet:item\"}},"
                + "{\"id\": \"built_in/claimed\", \"name\": \"Claimed\", \"kind\": \"TASK\","
                + " \"type\": \"tenet:item\", \"payload\": {}},"
                + "{\"id\": \"nokind\", \"name\": \"No kind\"},"
                + "\"not an object\"]}",
                StandardCharsets.UTF_8);
        ComponentTemplates.load(file);

        assertEquals(List.of("good"),
                ComponentTemplates.saved().stream().map(ComponentTemplate.Template::id).toList(),
                "the good entry survives; the claimed prefix, the kind-less and the string do not");

        Files.writeString(file, "this is not json", StandardCharsets.UTF_8);
        ComponentTemplates.load(file);
        assertTrue(ComponentTemplates.saved().isEmpty(), "a corrupt file is an empty shelf");
        assertFalse(ComponentTemplates.all().isEmpty(), "and the shipped rows never leave");
    }

    @Test
    @DisplayName("the remembered entry is session-only context for a condition Apply")
    void entryContext() {
        assertTrue(ComponentTemplates.entryContext().isEmpty(), "nothing touched yet");
        ComponentTemplates.noteEntryContext("some_quest", "tasks", 2);
        assertEquals(new ComponentTemplates.EntryContext("some_quest", "tasks", 2),
                ComponentTemplates.entryContext().orElseThrow());
        ComponentTemplates.noteEntryContext(null, "tasks", 0);
        assertTrue(ComponentTemplates.entryContext().isPresent(), "a null note changes nothing");
        ComponentTemplates.reset();
        assertTrue(ComponentTemplates.entryContext().isEmpty(), "leaving the world forgets it");
    }
}
