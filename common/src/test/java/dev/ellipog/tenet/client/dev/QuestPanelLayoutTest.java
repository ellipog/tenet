package dev.ellipog.tenet.client.dev;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import dev.ellipog.armature.client.ui.inspect.InspectField;
import dev.ellipog.armature.client.ui.inspect.InspectRow;

import dev.ellipog.tenet.quest.condition.ConditionTypes;
import dev.ellipog.tenet.quest.reward.RewardTypes;
import dev.ellipog.tenet.quest.task.TaskTypes;

import net.minecraft.resources.Identifier;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;
import java.util.TreeSet;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The quest panel's rows, from a quest's own tree.
 *
 * <h2>What is asked here, and what cannot be</h2>
 *
 * <p>Everything the panel promises an author: every section in order, a field row per field the format
 * has, a task's own fields from the registry the validator reads, an unknown type shown as what it is
 * rather than as nothing, and a folded section gone but for its heading. All of it is answerable with a
 * parsed JSON object and no client -- which is why the rows are built by a game-free class and the
 * drawing is not.
 */
@DisplayName("the quest panel's rows")
class QuestPanelLayoutTest {


    /** A quest with one of everything the panel edits. */
    private static JsonObject quest() {
        return JsonParser.parseString("""
                {
                  "id": "smelt_iron",
                  "title": "Iron, Twice",
                  "subtitle": "The first one is a lesson",
                  "icon": { "item": "minecraft:iron_ingot" },
                  "x": 12, "y": -4,
                  "showTitle": true,
                  "iconScale": 1.5,
                  "aliases": ["iron_lesson"],
                  "dependsOn": ["make_a_furnace", "mine_iron"],
                  "tasks": [
                    { "type": "tenet:item", "item": "minecraft:iron_ingot", "count": 3 },
                    { "type": "addon:mystery", "custom": true, "nested": { "deep": 1 } }
                  ],
                  "rewards": [
                    { "type": "tenet:item", "item": "minecraft:iron_block" }
                  ]
                }
                """).getAsJsonObject();
    }

    private static List<InspectRow> rows(JsonObject quest) {
        return QuestPanelLayout.rows(quest, "smelt_iron", Set.of());
    }

    private static InspectRow row(List<InspectRow> rows, String key) {
        return rows.stream().filter(candidate -> candidate.key().equals(key)).findFirst()
                .orElseThrow(() -> new AssertionError("no row keyed " + key + " in " + rows.size() + " rows"));
    }

    @Test
    @DisplayName("the sections are there, in order, and a field row's key is the path it commits to")
    void sectionsInOrder() {
        List<InspectRow> rows = rows(quest());

        List<String> headings = rows.stream().filter(row -> row.isHeading() || row.isEntry())
                .map(InspectRow::key).toList();
        assertEquals(List.of(QuestPanelLayout.IDENTITY, QuestPanelLayout.PLACEMENT,
                QuestPanelLayout.RULES, QuestPanelLayout.DEPENDENCIES,
                "h:tasks.0", "h:tasks.1", "h:rewards.0"), headings,
                "one section per part of the quest, in the order an author reads them");

        InspectRow title = row(rows, "title");
        assertEquals(InspectRow.Kind.FIELD, title.kind());
        assertEquals("Iron, Twice", title.value(), "the field starts showing what the tree says");

        assertEquals("12", row(rows, "x").value());
    }

    @Test
    @DisplayName("a task of a known type gets its own fields, sorted, read from its own object")
    void knownTypesGetTheirFields() {
        List<InspectRow> rows = rows(quest());

        InspectRow heading = row(rows, "h:tasks.0");
        assertFalse(heading.isWarning(), "tenet:item is known, so its section is a heading");
        assertTrue(heading.label().contains("tenet:item"), "the section names the type");

        // The registry's own field names plus the common ones, sorted: autoSubmitTicks, consumeItems,
        // count, disableToast, icon, item, manualOnly, match, onlyFromCrafting, optional, tags, title.
        // `conditions` is in
        // the registered set too and is filtered out on the way to a row, because a list of objects is
        // not a text cell.
        assertEquals(List.of("tasks.0.autoSubmitTicks", "tasks.0.consumeItems", "tasks.0.count",
                        "tasks.0.disableToast", "tasks.0.icon", "tasks.0.item", "tasks.0.manualOnly",
                        "tasks.0.match", "tasks.0.onlyFromCrafting", "tasks.0.optional", "tasks.0.tags",
                        "tasks.0.title"),
                rows.stream().map(InspectRow::key)
                        .filter(key -> key.startsWith("tasks.0.") && !key.equals("tasks.0.type"))
                        .toList(),
                "the type's own fields, from the registry, not a second list");

        assertEquals("3", row(rows, "tasks.0.count").value());
        assertEquals("minecraft:iron_ingot", row(rows, "tasks.0.item").value());
    }

    @Test
    @DisplayName("a task of an unknown type is a warning heading over the value itself")
    void unknownTypesGetTheFallback() {
        List<InspectRow> rows = rows(quest());

        InspectRow heading = row(rows, "h:tasks.1");
        assertTrue(heading.isWarning(), "the fallback opens in the voice that says something is wrong");
        assertTrue(heading.label().contains("addon:mystery"), "and names the type it cannot take apart");

        InspectRow raw = row(rows, QuestPanelLayout.RAW_PREFIX + "tasks.1");
        assertEquals(InspectRow.Kind.RAW, raw.kind());
        assertTrue(raw.value().contains("\"custom\":true"), "the value as stored, not a summary");
        assertTrue(raw.value().contains("\"nested\":{\"deep\":1}"),
                "and everything in it -- a fallback that dropped fields would be a save that does");
    }

    @Test
    @DisplayName("dependencies are a row each with a strip, and one row to add by typed id")
    void dependenciesAreRowsWithStrips() {
        List<InspectRow> rows = rows(quest());

        InspectRow first = row(rows, QuestPanelLayout.DEPENDENCY_PREFIX + "make_a_furnace");
        assertEquals(InspectRow.Kind.TOGGLE, first.kind(),
                "a strip row, which is where the screen puts the Remove button");
        assertEquals("make_a_furnace", first.label(), "the row is named for the dependency it is");

        InspectRow add = row(rows, QuestPanelLayout.DEPENDENCY_ADD);
        assertEquals(InspectRow.Kind.FIELD, add.kind(), "the Add row is a field: the id is typed into it");
    }

    @Test
    @DisplayName("a folded section is its heading and nothing else")
    void foldingLeavesTheHeading() {
        List<InspectRow> rows = QuestPanelLayout.rows(quest(), "smelt_iron",
                Set.of(QuestPanelLayout.PLACEMENT, "h:tasks.0"));

        assertTrue(rows.stream().anyMatch(candidate -> candidate.key().equals(QuestPanelLayout.PLACEMENT)),
                "the heading stays");
        assertTrue(rows.stream().noneMatch(candidate -> candidate.key().equals("x")),
                "and nothing under it: a folded section is folded");

        assertTrue(rows.stream().anyMatch(candidate -> candidate.key().equals("h:tasks.0")),
                "a folded task keeps its heading too");
        assertTrue(rows.stream().noneMatch(candidate -> candidate.key().equals("tasks.0.item")),
                "and loses its fields with it");
    }

    @Test
    @DisplayName("no selection is a line that says so, not a blank panel")
    void nothingSelectedSaysSo() {
        List<InspectRow> rows = QuestPanelLayout.rows(null, null, Set.of());
        assertEquals(1, rows.size());
        assertTrue(rows.get(0).value().contains("Select a quest"),
                "the panel's first answer tells the author what to do");

        List<InspectRow> missing = QuestPanelLayout.rows(null, "gone_quest", Set.of());
        assertTrue(missing.get(0).value().contains("gone_quest"),
                "and a quest that is not in the chapter is named, not vague");
    }

    @Test
    @DisplayName("the type picker lists every registered type exactly once, under the table's headings")
    void typePickerCoversTheRegistry() {
        assertCovered("tasks", TaskTypes.ids());
        assertCovered("rewards", RewardTypes.ids());
    }

    /** Every registered id is a row, once -- from the registry, not from the table. */
    private static void assertCovered(String member, Set<Identifier> registered) {
        List<String> listed = QuestPanelLayout.typeRows(member).stream()
                .filter(candidate -> candidate.kind() == InspectRow.Kind.ACTION)
                .map(candidate -> candidate.key().substring(QuestPanelLayout.TYPE_PREFIX.length()))
                .toList();
        Set<String> expected = new TreeSet<>();
        registered.forEach(id -> expected.add(id.toString()));

        assertEquals(expected, new TreeSet<>(listed), "every registered type is offered");
        assertEquals(listed.size(), new TreeSet<>(listed).size(), "and offered once");
    }

    @Test
    @DisplayName("the picker's rows are the table's groups in order, and a row is named the table's way")
    void typePickerGroupsAndNames() {
        List<InspectRow> rows = QuestPanelLayout.typeRows("tasks");

        assertEquals("Add a task", rows.get(0).label(), "the page says what it is for");
        assertEquals(List.of("Add a task", "Hand in", "Go", "Progress", "Manual", "Other"),
                rows.stream().filter(InspectRow::isHeading).map(InspectRow::label).toList(),
                "the table's groups, in the table's order");

        InspectRow item = row(rows, QuestPanelLayout.TYPE_PREFIX + "tenet:item");
        assertEquals(InspectRow.Kind.ACTION, item.kind());
        assertEquals("Item", item.label(), "the row shows the name, not the id a file spells");

        assertEquals(List.of("Item", "Item tag", "Experience", "Fluid"),
                rows.stream().filter(candidate -> candidate.kind() == InspectRow.Kind.ACTION).limit(4)
                        .map(InspectRow::label).toList(),
                "the first group's types, in the order the table lists them");
    }

    @Test
    @DisplayName("a type the table does not name is still offered, under More and by its own id")
    void typePickerFallback() {
        List<InspectRow> rows = QuestPanelLayout.typeRows("tasks",
                new TreeSet<>(Set.of("tenet:item", "tenet:checkmark", "addon:mystery")));

        // The heading is resolved where the row is built, so the expectation is the word rather than the
        // key: `Labels.of` is the same call the layout makes, with the language installed.
        assertEquals(List.of("Add a task", "Hand in", "Manual", Labels.of(QuestPanelLayout.MORE)),
                rows.stream().filter(InspectRow::isHeading).map(InspectRow::label).toList(),
                "an empty group is left out, and the unknown type gets one of its own");
        assertEquals("addon:mystery", row(rows, QuestPanelLayout.TYPE_PREFIX + "addon:mystery").label(),
                "named by the id -- the spelling an author meets in the file and in every error");
        assertEquals(List.of("addon:mystery"), QuestPanelLayout.typeTooltip("tasks", "addon:mystery"),
                "and it has no hint to show");
    }

    @Test
    @DisplayName("a hint is the table's line for its own member, and nothing for an id it does not name")
    void typeHints() {
        assertEquals("Hand in a count of one item.",
                QuestPanelLayout.typeTooltip("tasks", "tenet:item").get(0));
        assertEquals("Give one item.", QuestPanelLayout.typeTooltip("rewards", "tenet:item").get(0),
                "the same id is a different type on each member, with its own line");
        assertEquals(List.of("tenet:nope"), QuestPanelLayout.typeTooltip("tasks", "tenet:nope"),
                "an id the table does not name has no hint: the id alone is the description");
    }

    @Test
    @DisplayName("the picker's tooltip is the author's, and two lines: what it is for, and the fields it asks")
    void pickerTooltips() {
        List<String> lines = QuestPanelLayout.typeTooltip("tasks", "tenet:biome");
        assertEquals(2, lines.size(),
                "a hint and the fields it will ask for, and no third line repeating the id: " + lines);
        assertEquals("Be in a biome, or any biome of a tag.", lines.get(0));
        assertEquals("Fields: autoSubmitTicks, biome, conditions, disableToast, icon, optional, tags,"
                        + " title",
                lines.get(1),
                "the fields come from the registry -- the type's own plus the seven every task has -- "
                        + "not from a second list: " + lines);

        assertEquals(List.of("addon:mystery"), QuestPanelLayout.typeTooltip("tasks", "addon:mystery"),
                "no hint and no registered fields: the id is the whole description");
    }

    @Test
    @DisplayName("the condition picker lists every registered condition type once, under its own headings")
    void conditionPickerCoversTheRegistry() {
        List<InspectRow> rows = QuestPanelLayout.conditionTypeRows();
        List<String> listed = rows.stream()
                .filter(candidate -> candidate.kind() == InspectRow.Kind.ACTION)
                .map(candidate -> candidate.key().substring(QuestPanelLayout.TYPE_PREFIX.length()))
                .toList();
        Set<String> expected = new TreeSet<>();
        ConditionTypes.ids().forEach(id -> expected.add(id.toString()));

        assertEquals(expected, new TreeSet<>(listed), "every registered condition is offered");
        assertEquals(listed.size(), new TreeSet<>(listed).size(), "and offered once");
        assertEquals("Add a condition", rows.get(0).label(), "the page says what it is for");
        assertEquals("Stage", row(rows, QuestPanelLayout.TYPE_PREFIX + "tenet:stage").label(),
                "a row shows the table's name for the type, not the id a file spells");
    }

    @Test
    @DisplayName("a condition's form is the condition registry's, and an unknown type has none")
    void conditionFormsAndNames() {
        JsonObject stage = JsonParser.parseString(
                "{ \"type\": \"tenet:stage\", \"stage\": \"my_pack:marked\" }").getAsJsonObject();
        assertEquals(List.of("stage"), QuestPanelLayout.conditionEditorFor(stage).stream()
                        .map(dev.ellipog.tenet.quest.EditorField::path).toList(),
                "the declared form, read from the condition registry");
        assertEquals("Stage", QuestPanelLayout.conditionName("tenet:stage"));
        assertEquals("Custom gate", QuestPanelLayout.conditionName("addon:custom_gate"),
                "an unnamed type reads as its prettified path");

        JsonObject unknown = JsonParser.parseString(
                "{ \"type\": \"addon:custom_gate\" }").getAsJsonObject();
        assertTrue(QuestPanelLayout.conditionEditorFor(unknown).isEmpty(),
                "an unknown condition type has no form -- the raw-JSON fallback's trigger");

        List<String> tip = QuestPanelLayout.conditionTypeTooltip("tenet:item");
        assertEquals(2, tip.size(), "the condition picker's hover is capped like the task picker's: " + tip);
        assertEquals("Have a count of one item.", tip.get(0), "the table's hint comes first");
        assertTrue(tip.get(1).startsWith("Fields: "),
                "and the fields come from the registry, as a comma-separated list rather than a set's "
                        + "own toString: " + tip);
    }

    @Test
    @DisplayName("a task's conditions are the card's section, not a dock row that would write a string")
    void conditionsAreNotDockRows() {
        JsonObject quest = JsonParser.parseString("""
                {
                  "id": "gated",
                  "title": "Gated",
                  "tasks": [
                    { "type": "tenet:checkmark", "title": "done",
                      "conditions": [ { "type": "tenet:stage", "stage": "my_pack:marked" } ] }
                  ]
                }
                """).getAsJsonObject();

        List<InspectRow> rows = rows(quest);
        assertFalse(rows.stream().anyMatch(candidate -> candidate.key().equals("tasks.0.conditions")),
                "a text row for a list would offer to write a string where the format holds objects: "
                        + rows.stream().map(InspectRow::key).toList());
    }

    @Test
    @DisplayName("every registered type's player tooltip is player language: no fields, no ids")
    void playerTooltipsArePlayerLanguage() {
        for (Identifier id : TaskTypes.ids()) {
            for (boolean byHand : new boolean[] {false, true}) {
                for (boolean takes : new boolean[] {false, true}) {
                    assertPlayerFacing("tasks", id.toString(), byHand, takes);
                }
            }
        }
        for (Identifier id : RewardTypes.ids()) {
            assertPlayerFacing("rewards", id.toString(), false, false);
        }
    }

    /** The guard for the whole class of leak: the reader's hover must never be the author's. */
    private static void assertPlayerFacing(String member, String typeId, boolean byHand, boolean takes) {
        List<String> lines = QuestPanelLayout.playerTooltip(member, typeId, byHand, takes);
        assertFalse(lines.isEmpty(), typeId + " has no tooltip at all");
        for (String line : lines) {
            assertFalse(line.isBlank(), typeId + " has a blank line");
            assertFalse(line.contains("Fields:"), typeId + " leaks the field list: " + line);
            assertFalse(line.contains("tenet:"), typeId + " leaks a type id: " + line);
            assertFalse(line.equals(typeId), typeId + " shows its own id: " + line);
        }
    }

    @Test
    @DisplayName("a carried item that is not handed in says nothing is taken; a hand-in says what is")
    void itemTooltips() {
        assertEquals(List.of("Have this many in your inventory.",
                        "Nothing is taken - the quest only checks that you have them."),
                QuestPanelLayout.playerTooltip("tasks", "tenet:item", false, false),
                "a presence-only item task completes by itself, so nothing may be taken");

        assertEquals(List.of("Have this many in your inventory.",
                        "Hand it in with the Submit button - what you hand over is taken."),
                QuestPanelLayout.playerTooltip("tasks", "tenet:item", true, true),
                "a consuming item task is handed over, and the player is told so");

        // And the state that has no button at all yet -- locked, or a count not yet met. The items are
        // still taken when it is finally handed in, so "nothing is taken" is the one sentence this may
        // not print, and the row's own second fact is what says so.
        List<String> waiting = QuestPanelLayout.playerTooltip("tasks", "tenet:item", false, true);
        assertEquals("Handing it in takes what it asks for.", waiting.get(1));
        assertFalse(waiting.stream().anyMatch(line -> line.contains("Nothing is taken")),
                "a consuming row says what it takes whether or not its button is drawn: " + waiting);
    }

    @Test
    @DisplayName("a checkmark asks for the button and never claims anything is taken")
    void checkmarkTooltip() {
        List<String> lines = QuestPanelLayout.playerTooltip("tasks", "tenet:checkmark", true, false);
        assertEquals("You decide when this one is done.", lines.get(0));
        assertEquals("Press the Submit button when you have done it.", lines.get(1));
        assertFalse(lines.stream().anyMatch(line -> line.contains("taken")),
                "a checkmark takes nothing: " + lines);
    }

    @Test
    @DisplayName("a reward's tooltip says when you get it, and never that anything is taken")
    void rewardTooltips() {
        List<String> lines = QuestPanelLayout.playerTooltip("rewards", "tenet:item", false, false);
        assertEquals(List.of("You get this item when you claim the quest."), lines);
        assertFalse(lines.stream().anyMatch(line -> line.contains("taken")),
                "a reward is given, not handed in: " + lines);
    }

    @Test
    @DisplayName("an id-shaped argument is prettified for the kinds that name one, and left alone otherwise")
    void prettiedArguments() {
        assertTrue(QuestPanelLayout.prettifiesIds("tasks", "tenet:biome"));
        assertTrue(QuestPanelLayout.prettifiesIds("rewards", "tenet:advancement"));
        assertFalse(QuestPanelLayout.prettifiesIds("tasks", "tenet:stage"),
                "a stage's id is its name, and prettifying it would invent one");
        assertFalse(QuestPanelLayout.prettifiesIds("tasks", "addon:mystery"),
                "an addon's sentence is its own shape, which this build cannot prettify");

        assertEquals("Plains", QuestPanelLayout.prettiedArgument("tasks", "tenet:biome", "minecraft:plains"));
        assertEquals("#Logs",
                QuestPanelLayout.prettiedArgument("tasks", "tenet:item_tag", "#minecraft:logs"));
        assertEquals("my_pack:inducted",
                QuestPanelLayout.prettiedArgument("tasks", "tenet:stage", "my_pack:inducted"),
                "a type the table says not to prettify keeps its argument exactly");
    }

    @Test
    @DisplayName("the words around an id in a composite argument survive the prettifier")
    void prettiedArgumentsKeepTheirWords() {
        // The two traps this rule exists against. Since 1.21 a bare word parses as `minecraft:<word>`,
        // so a walk that prettified every parseable token would read "1000 mB of Water" as "1000 mB Of
        // Water" and a kill task's "anything" as "Anything" -- the words are not ids, and the sentences
        // write the namespace on the ids they do name.
        assertEquals("1000 mB of Water",
                QuestPanelLayout.prettiedArgument("tasks", "tenet:fluid", "1000 mB of minecraft:water"));
        assertEquals("Beacon for 2.0s",
                QuestPanelLayout.prettiedArgument("tasks", "tenet:observation",
                        "minecraft:beacon for 2.0s"));
        assertEquals("anything",
                QuestPanelLayout.prettiedArgument("tasks", "tenet:kill", "anything"),
                "a kill task with no target names no id to prettify");
    }

    @Test
    @DisplayName("the type a field edits as comes from the format, then the tree, then text")
    void fieldKinds() {
        JsonObject quest = quest();

        assertTrue(refuses(QuestPanelLayout.fieldFor(quest, "x"), "1.5"),
                "a coordinate is a whole number, and refuses a fraction");
        assertTrue(accepts(QuestPanelLayout.fieldFor(quest, "x"), "12"));
        assertTrue(accepts(QuestPanelLayout.fieldFor(quest, "iconScale"), "1.5"),
                "a scale takes a fraction, which the whole-number field would refuse");
        assertTrue(accepts(QuestPanelLayout.fieldFor(quest, "showTitle"), "true"),
                "a flag edits as a flag");
        assertTrue(accepts(QuestPanelLayout.fieldFor(quest, "tasks.0.count"), "3"),
                "a task's count is reached through its array step and edits whole");
        assertTrue(accepts(QuestPanelLayout.fieldFor(quest, "tasks.1.count"), "4"),
                "an absent count still edits whole, from what the name declares");
        assertTrue(accepts(QuestPanelLayout.fieldFor(quest, "title"), "anything at all"),
                "a title is text");
    }

    private static boolean accepts(InspectField<?> field, String text) {
        return field.parse(text).ok();
    }

    private static boolean refuses(InspectField<?> field, String text) {
        return !field.parse(text).ok();
    }

    @Test
    @DisplayName("labels are said like fields, and the index is not one of them")
    void labels() {
        assertEquals("Icon Item", QuestPanelLayout.labelFor("icon.item"));
        assertEquals("Icon Texture", QuestPanelLayout.labelFor("icon.texture"));
        assertEquals("Icon Sprite", QuestPanelLayout.labelFor("tasks.0.icon.sprite"));
        assertEquals("Icon Entity", QuestPanelLayout.labelFor("icon.entity"));
        assertEquals("Count", QuestPanelLayout.labelFor("tasks.0.count"));
        assertEquals("Repeat Cooldown Ticks", QuestPanelLayout.labelFor("repeatCooldownTicks"));
        assertEquals("Item", QuestPanelLayout.labelFor("rewards.0.item"));
    }

    @Test
    @DisplayName("reading the tree takes array steps, the same shape the model writes")
    void readsThroughArraySteps() {
        JsonObject quest = quest();

        assertEquals(3, QuestPanelLayout.get(quest, "tasks.0.count").getAsInt(),
                "the commit's path and the read's path are one shape");
        assertNull(QuestPanelLayout.get(quest, "tasks.9.count"), "past the end is nothing");
        assertNull(QuestPanelLayout.get(quest, "tasks.notAnIndex"), "and so is a word where an index belongs");
        assertEquals("minecraft:iron_block", QuestPanelLayout.get(quest, "rewards.0.item").getAsString());
    }

    @Test
    @DisplayName("a page of types names the act on its first line, for a host that cannot")
    void theTypePageNamesItsAct() {
        // The quest card's host: its chrome is the quest's identity, so the page has to say what it is for
        // itself -- and this is the only host that does, which is why the method is separate from the one
        // the table editor calls.
        List<InspectRow> rows = QuestPanelLayout.typeRows("rewards");

        assertEquals(InspectRow.Kind.HEADING, rows.get(0).kind());
        assertEquals("h:type", rows.get(0).key(), "the page's own line, keyed as the page's");
        assertTrue(rows.get(0).label().toLowerCase(java.util.Locale.ROOT).contains("reward"),
                "and it says what the page adds: " + rows.get(0).label());
        assertTrue(QuestPanelLayout.typeRows("tasks").get(0).label()
                        .toLowerCase(java.util.Locale.ROOT).contains("task"),
                "the task member says task, from its own key");
    }

    @Test
    @DisplayName("an item entry carries the stack it came from")
    void anItemEntryCarriesItsCount() {
        // The two ways an item gets into a table disagreed about this: a recipe-viewer drop carried the
        // stack's count, while an item picked in the picker wrote none — and the picker was *showing* the
        // count ("x128 Iron Ore"). One factory, one rule, so the number on screen is the number in the file.
        JsonObject one = QuestPanelLayout.itemEntry("minecraft:stone", 1, null);

        assertEquals(1.0, one.get("weight").getAsDouble(), "a new entry starts at the neutral weight");
        JsonObject single = one.getAsJsonObject("reward");
        assertEquals("tenet:item", single.get("type").getAsString());
        assertEquals("minecraft:stone", single.get("item").getAsString());
        assertFalse(single.has("count"), "a count of one is the format's default, and is left out");

        JsonObject stack = QuestPanelLayout.itemEntry("minecraft:iron_ore", 128, null)
                .getAsJsonObject("reward");
        assertEquals(128, stack.get("count").getAsInt(), "a stack of 128 becomes 128");

        // The picked data travels with the id, and an absent patch is absent rather than a null member.
        JsonObject patched = QuestPanelLayout.itemEntry("minecraft:stone", 2,
                JsonParser.parseString("{\"minecraft:custom_name\":\"Rock\"}")).getAsJsonObject("reward");
        assertEquals(2, patched.get("count").getAsInt());
        assertTrue(patched.has("components"), "the renamed stack is that stack");
        assertFalse(QuestPanelLayout
                .itemEntry("minecraft:stone", 1, com.google.gson.JsonNull.INSTANCE)
                .getAsJsonObject("reward").has("components"));
    }

    @Test
    @DisplayName("a page whose chrome names it has no heading row at all")
    void aPageNamedInItsChromeHasNoFirstLine() {
        // The table editor's strip says "Add a reward" while the page is open, so a heading row could only
        // repeat that sentence or name the table -- and the table's name there was the first thing the eye
        // landed on and the last thing it needed. The list begins at its first group heading instead.
        List<InspectRow> rows = QuestPanelLayout.typeRowsNamedInChrome("rewards");

        assertFalse(rows.isEmpty(), "the page must still list the registered types");
        assertEquals(InspectRow.Kind.HEADING, rows.get(0).kind(),
                "the first line is a group's heading, not the page's");
        assertTrue(rows.stream().noneMatch(row -> "h:type".equals(row.key())),
                "and there is no page heading row: " + rows.get(0).key());

        // Everything else is the list the named page shows, minus that one row.
        List<InspectRow> named = QuestPanelLayout.typeRows("rewards");
        assertEquals(named.size() - 1, rows.size(), "one row fewer, and it is the heading");
        for (int i = 0; i < rows.size(); i++) {
            assertEquals(named.get(i + 1).key(), rows.get(i).key(), "row " + i + " is the same row");
            assertEquals(named.get(i + 1).label(), rows.get(i).label());
            assertEquals(named.get(i + 1).value(), rows.get(i).value(), "and carries the same id");
        }
    }

    @Test
    @DisplayName("a type row carries the id a file spells, unless its name is that id")
    void typeRowsCarryTheirIds() {
        // The right-aligned detail the item picker puts on a row — an item's id beside its name — so the
        // spelling a file uses is on screen rather than only in a hover.
        List<InspectRow> actions = QuestPanelLayout.typeRows("rewards").stream()
                .filter(row -> row.kind() == InspectRow.Kind.ACTION).toList();

        assertFalse(actions.isEmpty(), "the picker must list the registered types");
        for (InspectRow row : actions) {
            assertTrue(row.key().startsWith(QuestPanelLayout.TYPE_PREFIX), row.key());
            String id = row.key().substring(QuestPanelLayout.TYPE_PREFIX.length());
            if (row.label().equals(id)) {
                // A type no table names is already labelled by its id, and printing it twice on one line
                // is the duplication the header's title and id had.
                assertTrue(row.value().isEmpty(), row.key() + " would print its id twice");
            }
            else {
                assertEquals(id, row.value(), row.key() + " must carry the id it writes");
            }
        }
    }
}
