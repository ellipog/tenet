package dev.ellipog.tenet.client.dev;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import dev.ellipog.tenet.quest.condition.ConditionTypes;
import dev.ellipog.tenet.quest.reward.RewardTypes;
import dev.ellipog.tenet.quest.task.TaskTypes;

import net.minecraft.resources.Identifier;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * What a player reads, and what only an author does.
 *
 * <h2>The rule, stated once</h2>
 *
 * <p>A player's tooltip is written for somebody who has never heard of a task type: plain words, no
 * registry ids, no field names, no commands, and no more than a line or two. An <b>editor's</b> tooltip
 * may name the fields and the id, because those are what the author edits — and that is the whole of
 * what it may do: a picker row's hover is two lines, not a paragraph.
 *
 * <p>The rule had been broken quietly in both directions. Player hovers printed a raw item id on a
 * reward, a quest id on a rewards-inbox header, a tag id on a tagged task, a slash command on every
 * party button, and "Missing item: minecraft:oak_log" on a sidebar row — each with a comment
 * explaining that the id was for a bug report, which is an author's reason and not a player's. And the
 * editor's own hover had grown to three lines, the third of which repeated the type's id after the hint
 * and the fields had already named it.
 *
 * <h2>Why these four guards rather than a list of banned words</h2>
 *
 * <p>A list would be a second description of the format, and it would go stale the first time a field
 * was added. These are shapes instead: an id is {@code namespace:path} wherever it appears, and a
 * machine name is camelCase in an English sentence, because nothing else in a player-facing line ever
 * is. So a field added to the format tomorrow is caught by the shape rather than by somebody
 * remembering to extend a list — which is the same argument {@code LangSweepTest} makes about keys.
 */
@DisplayName("what a player reads, and what only an author does")
class PlayerTooltipLanguageTest {

    /** One level in from the common module, which is the test working directory. */
    private static final Path LANG =
            Path.of("src", "main", "resources", "assets", "tenet", "lang", "en_us.json");

    /** A registry id as a file spells it, which is what a player must never be shown. */
    private static final Pattern ID = Pattern.compile("[a-z0-9_.\\-]+:[a-z0-9_./\\-]+");

    /** A machine name. CamelCase in an English sentence is a field or a key, never a word. */
    private static final Pattern FIELD = Pattern.compile("[a-z]+[A-Z][a-zA-Z]*");

    /** The file's own vocabulary, which reads as a debug dump beside a sentence. */
    private static final List<String> JARGON = List.of("Fields:", "NBT", "JSON", "criterion");

    @Test
    @DisplayName("every player tooltip is plain words: no ids, no field names, no file vocabulary")
    void playerTooltipsArePlainWords() {
        for (Identifier id : TaskTypes.ids()) {
            for (boolean byHand : new boolean[] {false, true}) {
                for (boolean takes : new boolean[] {false, true}) {
                    assertPlain("the task tooltip for " + id,
                            QuestPanelLayout.playerTooltip("tasks", id.toString(), byHand, takes));
                }
            }
        }
        for (Identifier id : RewardTypes.ids()) {
            assertPlain("the reward tooltip for " + id,
                    QuestPanelLayout.playerTooltip("rewards", id.toString(), false, false));
        }
    }

    @Test
    @DisplayName("no player-facing string in the language file names an id or a field")
    void screenStringsArePlainWords() throws IOException {
        // The half a call-site test cannot see: a player string that is only reached on a path no test
        // walks -- a row with a missing item, a party button in a phase nobody opened.
        int checked = 0;
        for (Map.Entry<String, String> entry : lang().entrySet()) {
            if (!entry.getKey().startsWith("tenet.screen.")) {
                continue;
            }
            assertPlain(entry.getKey(), List.of(entry.getValue()));
            checked++;
        }
        assertTrue(checked > 100, "the sweep found only " + checked + " screen strings, which means it "
                + "is reading the wrong file rather than that the file is clean");
    }

    @Test
    @DisplayName("an editor tooltip is two lines at most, and names the id only when nothing else does")
    void editorTooltipsStayShort() {
        for (Identifier id : TaskTypes.ids()) {
            assertShort("the task picker's hover for " + id, QuestPanelLayout.typeTooltip("tasks", id.toString()));
        }
        for (Identifier id : RewardTypes.ids()) {
            assertShort("the reward picker's hover for " + id,
                    QuestPanelLayout.typeTooltip("rewards", id.toString()));
        }
        for (Identifier id : ConditionTypes.ids()) {
            assertShort("the condition picker's hover for " + id,
                    QuestPanelLayout.conditionTypeTooltip(id.toString()));
        }

        // And the other direction, so "two lines" cannot be met by throwing the useful half away: the
        // editor is the one surface where the field names belong, and it has to carry them, because
        // naming the fields a type will ask for is the question a picker row is being asked.
        List<String> known = QuestPanelLayout.typeTooltip("tasks", "tenet:item");
        assertEquals(2, known.size(), "a known type says its hint and its fields, and stops: " + known);
        assertTrue(known.get(1).startsWith("Fields:"), "the second line is the field list: " + known);

        // An addon's type is the exception in both directions: nothing in this build names it, so its
        // id is its name -- the one place an id is what the author needs rather than noise.
        assertEquals(List.of("addon:mystery"), QuestPanelLayout.typeTooltip("tasks", "addon:mystery"),
                "an unregistered type is named by its id and nothing else");
    }

    /** A player line: no id, no field name, no file vocabulary, and not blank. */
    private static void assertPlain(String what, List<String> lines) {
        for (String line : lines) {
            assertFalse(line.isBlank(), what + " has a blank line");
            assertFalse(ID.matcher(line).find(), what + " shows an id: \"" + line + "\"");
            assertFalse(FIELD.matcher(line).find(), what + " shows a field name: \"" + line + "\"");
            for (String jargon : JARGON) {
                assertFalse(line.contains(jargon), what + " reads like the file: \"" + line + "\"");
            }
        }
    }

    /** An editor line: two at most, and at least one. */
    private static void assertShort(String what, List<String> lines) {
        assertFalse(lines.isEmpty(), what + " has no tooltip at all");
        assertTrue(lines.size() <= 2, what + " runs to " + lines.size() + " lines: " + lines);
    }

    private static Map<String, String> lang() throws IOException {
        JsonObject root = JsonParser.parseString(Files.readString(LANG, StandardCharsets.UTF_8))
                .getAsJsonObject();
        Map<String, String> entries = new LinkedHashMap<>();
        for (Map.Entry<String, JsonElement> entry : root.entrySet()) {
            entries.put(entry.getKey(), entry.getValue().getAsString());
        }
        return entries;
    }
}
