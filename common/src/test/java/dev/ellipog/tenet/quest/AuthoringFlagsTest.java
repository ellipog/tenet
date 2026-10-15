package dev.ellipog.tenet.quest;

import com.google.gson.JsonParser;
import com.mojang.serialization.DataResult;
import com.mojang.serialization.JsonOps;

import dev.ellipog.armature.api.data.Problems;
import dev.ellipog.tenet.quest.loot.RewardTable;
import dev.ellipog.tenet.quest.reward.RewardCommon;
import dev.ellipog.tenet.quest.reward.RewardTypes;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The authoring flags: how a quest presents itself, which chapter it centres on, which notices are
 * quiet, and how a reward row draws its table.
 *
 * <p>Batch 3 of the migration work. The legacy format ships {@code min_width}, {@code autofocus_id},
 * {@code hide_dependent_lines}, {@code disable_toast} on every quest object, and {@code use_title}
 * / {@code hide_tooltip} on reward tables; this mod had none of them, so a converted pack's uses
 * had nowhere to land. Each test decodes the JSON the tool would write through the real codec, or
 * runs the real validator and index over it, which is what makes this page fail first if a field
 * quietly becomes required or changes meaning.
 */
@DisplayName("authoring flags: presentation, autofocus, quiet notices, table display")
class AuthoringFlagsTest {

    private static <T> T decoded(DataResult<T> result, String what) {
        return result.result().orElseThrow(() -> new AssertionError(what + " did not decode: "
                + result.error().map(DataResult.Error::message).orElse("no message")));
    }

    private static Quest quest(String text) {
        return decoded(Quest.CODEC.parse(JsonOps.INSTANCE, JsonParser.parseString(text)), text);
    }

    private static Chapter chapter(String text) {
        return decoded(Chapter.CODEC.parse(JsonOps.INSTANCE, JsonParser.parseString(text)), text);
    }

    private static QuestTask task(String text) {
        return decoded(dev.ellipog.tenet.quest.task.TaskTypes.dispatchCodec()
                .parse(JsonOps.INSTANCE, JsonParser.parseString(text)), text);
    }

    private static QuestReward reward(String text) {
        return decoded(RewardTypes.dispatchCodec()
                .parse(JsonOps.INSTANCE, JsonParser.parseString(text)), text);
    }

    // ------------------------------------------------------------------
    // The quest's own presentation: flat on the quest, defaulting to the old behaviour
    // ------------------------------------------------------------------

    @Nested
    @DisplayName("quest presentation")
    class Presentation {

        @Test
        @DisplayName("a quest that says nothing presents as before: unset width, drawn edges, announced, held, personal gate, listed, marked")
        void absentIsTheOldBehaviour() {
            Quest parsed = quest("""
                    {"id": "one", "title": "One"}""");

            assertEquals(0, parsed.minWidth());
            assertFalse(parsed.hideDependentLines());
            assertFalse(parsed.disableToast());
            assertFalse(parsed.ignoreRewardBlocking());
            assertFalse(parsed.requiresStageTeam());
            assertEquals(Optional.empty(), parsed.disableRecipeMod());
            assertFalse(parsed.hideLockIcon());
        }

        @Test
        @DisplayName("minWidth, hideDependentLines, disableToast, ignoreRewardBlocking, requiresStageTeam, disableRecipeMod and hideLockIcon round-trip flat on the quest")
        void presentationRoundTrips() {
            Quest parsed = quest("""
                    {"id": "one", "title": "One", "minWidth": 250,
                     "hideDependentLines": true, "disableToast": true,
                     "ignoreRewardBlocking": true, "requiresStageTeam": true,
                     "disableRecipeMod": true, "hideLockIcon": true}""");

            assertEquals(250, parsed.minWidth());
            assertTrue(parsed.hideDependentLines());
            assertTrue(parsed.disableToast());
            assertTrue(parsed.ignoreRewardBlocking());
            assertTrue(parsed.requiresStageTeam());
            assertEquals(Optional.of(true), parsed.disableRecipeMod());
            assertTrue(parsed.hideLockIcon());

            Quest decoded = decoded(Quest.CODEC.parse(JsonOps.INSTANCE,
                    Quest.CODEC.encodeStart(JsonOps.INSTANCE, parsed).getOrThrow()), "re-decode");
            assertEquals(250, decoded.minWidth());
            assertTrue(decoded.hideDependentLines());
            assertTrue(decoded.disableToast());
            assertTrue(decoded.ignoreRewardBlocking());
            assertTrue(decoded.requiresStageTeam());
            assertEquals(Optional.of(true), decoded.disableRecipeMod());
            assertTrue(decoded.hideLockIcon());
        }

        @Test
        @DisplayName("the nine presentation names are validator-visible at quest level")
        void presentationFieldsAreKnown() {
            assertTrue(QuestPresentation.FIELDS.contains("minWidth"));
            assertTrue(QuestPresentation.FIELDS.contains("hideDependentLines"));
            assertTrue(QuestPresentation.FIELDS.contains("disableToast"));
            assertTrue(QuestPresentation.FIELDS.contains("ignoreRewardBlocking"));
            assertTrue(QuestPresentation.FIELDS.contains("requiresStageTeam"));
            assertTrue(QuestPresentation.FIELDS.contains("disableRecipeMod"));
            assertTrue(QuestPresentation.FIELDS.contains("hideLockIcon"));
            assertTrue(QuestPresentation.FIELDS.contains("tags"));
            assertTrue(QuestPresentation.FIELDS.contains("guidePage"));
        }
    }

    // ------------------------------------------------------------------
    // The chapter's defaults and its focus quest
    // ------------------------------------------------------------------

    @Nested
    @DisplayName("chapter defaults and autofocus")
    class ChapterFlags {

        @Test
        @DisplayName("a chapter that says nothing centres on its box with kind-decided widths")
        void absentIsTheOldBehaviour() {
            Chapter parsed = chapter("""
                    {"id": "chapter", "title": "Chapter"}""");

            assertEquals(0, parsed.rules().defaultMinWidth());
            assertTrue(parsed.rules().autofocus().isEmpty());
        }

        @Test
        @DisplayName("defaultMinWidth and autofocus round-trip flat on the chapter")
        void chapterFlagsRoundTrip() {
            Chapter parsed = chapter("""
                    {"id": "chapter", "title": "Chapter",
                     "defaultMinWidth": 350, "autofocus": "first_steps"}""");

            assertEquals(350, parsed.rules().defaultMinWidth());
            assertEquals("first_steps", parsed.rules().autofocus().orElseThrow().id());
        }

        @Test
        @DisplayName("an autofocus on nothing is an error naming the chapter's field")
        void autofocusOnNothingIsAnError() {
            String file = Fixtures.fileWithChapter("\"autofocus\": \"missing\",",
                    Fixtures.q("one").build());
            Problems problems = new Problems();
            QuestIndex.build(List.of(new LoadedQuestFile(java.nio.file.Path.of("test.json"), "test",
                    Fixtures.document("test.json", file), Fixtures.decode("test",
                            Fixtures.document("test.json", file)))),
                    problems);

            assertTrue(problems.hasErrors(), "an autofocus that resolves to nothing must be reported");
        }

        @Test
        @DisplayName("an autofocus on another chapter's quest is an error")
        void autofocusOnAnotherChapterIsAnError() {
            String first = Fixtures.chapter("first", Fixtures.q("one").build());
            String second = Fixtures.chapterWith("second", "\"autofocus\": \"one\",",
                    Fixtures.q("two").build());
            String file = Fixtures.fileWithChapters(first, second);
            Problems problems = new Problems();
            QuestIndex.build(List.of(new LoadedQuestFile(java.nio.file.Path.of("test.json"), "test",
                    Fixtures.document("test.json", file),
                    Fixtures.decode("test", Fixtures.document("test.json", file)))),
                    problems);

            assertTrue(problems.hasErrors(),
                    "an autofocus on a quest drawn on another canvas must be reported");
        }

        @Test
        @DisplayName("an autofocus on the chapter's own quest loads clean")
        void autofocusOnOwnQuestLoadsClean() {
            Problems problems = new Problems();
            QuestIndex index = Fixtures.indexOf(Fixtures.fileWithChapter("\"autofocus\": \"one\",",
                    Fixtures.q("one").build()));
            // Indexed without dropping: the quest is in the tree however the chapter centres.
            assertTrue(index.quest("one").isPresent());
            assertFalse(problems.hasErrors());
        }
    }

    // ------------------------------------------------------------------
    // Quiet tasks and rewards
    // ------------------------------------------------------------------

    @Nested
    @DisplayName("quiet tasks and rewards")
    class Quiet {

        @Test
        @DisplayName("a task that says nothing is announced")
        void taskAbsentIsAnnounced() {
            QuestTask parsed = task("""
                    {"type": "tenet:checkmark", "title": "Did it"}""");

            assertFalse(parsed.common().disableToast());
        }

        @Test
        @DisplayName("a task's disableToast round-trips flat on the task")
        void taskQuietRoundTrips() {
            QuestTask parsed = task("""
                    {"type": "tenet:checkmark", "title": "Did it", "disableToast": true}""");

            assertTrue(parsed.common().disableToast());
        }

        @Test
        @DisplayName("a reward that says nothing is announced")
        void rewardAbsentIsAnnounced() {
            QuestReward parsed = reward("""
                    {"type": "tenet:xp", "amount": 5}""");

            assertFalse(parsed.common().disableToast());
        }

        @Test
        @DisplayName("a reward's disableToast round-trips flat on the reward")
        void rewardQuietRoundTrips() {
            QuestReward parsed = reward("""
                    {"type": "tenet:xp", "amount": 5, "disableToast": true}""");

            assertTrue(parsed.common().disableToast());
            assertTrue(RewardTypes.fieldsOf(
                    net.minecraft.resources.Identifier.fromNamespaceAndPath("tenet", "xp"))
                    .contains("disableToast"));
            assertTrue(RewardCommon.FIELDS.contains("disableToast"));
        }
    }

    // ------------------------------------------------------------------
    // Table display: whose title the row wears, and whether it hovers
    // ------------------------------------------------------------------

    @Nested
    @DisplayName("table display")
    class TableDisplay {

        @Test
        @DisplayName("a table that says nothing draws the generic row with its tooltip")
        void absentIsTheOldBehaviour() {
            RewardTable table = new RewardTable(0, 1, List.of());

            assertFalse(table.useTitle());
            assertFalse(table.hideTooltip());
        }

        @Test
        @DisplayName("useTitle and hideTooltip round-trip on the table")
        void tableDisplayRoundTrips() {
            RewardTable table = decoded(RewardTable.CODEC.parse(JsonOps.INSTANCE, JsonParser.parseString("""
                    {"entries": [], "useTitle": true, "hideTooltip": true}""")), "table");

            assertTrue(table.useTitle());
            assertTrue(table.hideTooltip());

            RewardTable decoded = decoded(RewardTable.CODEC.parse(JsonOps.INSTANCE,
                    RewardTable.CODEC.encodeStart(JsonOps.INSTANCE, table).getOrThrow()), "re-decode");
            assertTrue(decoded.useTitle());
            assertTrue(decoded.hideTooltip());
        }
    }

    // ------------------------------------------------------------------
    // The author's own words and pictures on tasks and rewards
    // ------------------------------------------------------------------

    @Nested
    @DisplayName("task and reward titles and icons")
    class TitlesAndPictures {

        @Test
        @DisplayName("a checkmark's old title key lands in the shared field")
        void checkmarkTitleIsTheSharedField() {
            QuestTask parsed = task("""
                    {"type": "tenet:checkmark", "title": "Did you read the sign?"}""");

            assertEquals("Did you read the sign?",
                    parsed.common().title().orElseThrow().value());
        }

        @Test
        @DisplayName("an author title replaces the type's own sentence")
        void authorTitleWins() {
            QuestTask item = task("""
                    {"type": "tenet:item", "item": "minecraft:oak_log", "count": 8,
                     "title": "Bring logs"}""");

            assertEquals("Bring logs",
                    dev.ellipog.tenet.quest.task.TaskTypes.displayOf(item).label());
        }

        @Test
        @DisplayName("an author item travels beside the requirement, not in it")
        void authorItemWins() {
            QuestTask check = task("""
                    {"type": "tenet:checkmark", "title": "x",
                     "icon": {"item": "minecraft:torch"}}""");

            var display = dev.ellipog.tenet.quest.task.TaskTypes.displayOf(check);
            assertTrue(display.item().isEmpty(), "a checkmark needs nothing");
            assertEquals("minecraft:torch", display.picture().orElseThrow().item().toString());
            assertTrue(display.textureIcon().isEmpty());
        }

        @Test
        @DisplayName("an author item on an item task keeps the requirement")
        void authorItemKeepsTheRequirement() {
            QuestTask item = task("""
                    {"type": "tenet:item", "item": "minecraft:oak_log", "count": 8,
                     "icon": {"item": "minecraft:torch"}}""");

            var display = dev.ellipog.tenet.quest.task.TaskTypes.displayOf(item);
            assertEquals("minecraft:oak_log", display.item().orElseThrow().item().toString(),
                    "the task still needs oak logs");
            assertEquals("minecraft:torch", display.picture().orElseThrow().item().toString(),
                    "and draws the torch");
        }

        @Test
        @DisplayName("an author texture keeps the requirement and names the path")
        void authorTextureWins() {
            QuestTask check = task("""
                    {"type": "tenet:checkmark", "title": "x",
                     "icon": {"texture": "my_pack:textures/gui/emblem.png"}}""");

            var display = dev.ellipog.tenet.quest.task.TaskTypes.displayOf(check);
            assertTrue(display.item().isEmpty());
            assertTrue(display.picture().isEmpty());
            assertEquals("my_pack:textures/gui/emblem.png", display.textureIcon());
        }

        @Test
        @DisplayName("an author texture on an item task keeps the requirement")
        void authorTextureKeepsTheRequirement() {
            QuestTask item = task("""
                    {"type": "tenet:item", "item": "minecraft:oak_log", "count": 8,
                     "icon": {"texture": "my_pack:textures/gui/emblem.png"}}""");

            var display = dev.ellipog.tenet.quest.task.TaskTypes.displayOf(item);
            assertEquals("minecraft:oak_log", display.item().orElseThrow().item().toString());
            assertEquals("my_pack:textures/gui/emblem.png", display.textureIcon());
        }

        @Test
        @DisplayName("a task that says nothing keeps its type's own words and picture")
        void absentIsTheType() {
            QuestTask check = task("""
                    {"type": "tenet:checkmark", "title": "Did it"}""");

            var display = dev.ellipog.tenet.quest.task.TaskTypes.displayOf(check);
            assertEquals("Did it", display.label());
            assertTrue(display.item().isEmpty() && display.textureIcon().isEmpty());
        }

        @Test
        @DisplayName("a reward's author title and texture resolve the same way")
        void rewardOverridesResolve() {
            QuestReward titled = reward("""
                    {"type": "tenet:xp", "amount": 5, "title": "Some experience"}""");
            assertEquals("Some experience",
                    dev.ellipog.tenet.quest.reward.RewardTypes.displayOf(titled).label());

            QuestReward pictured = reward("""
                    {"type": "tenet:xp", "amount": 5,
                     "icon": {"texture": "my_pack:textures/gui/emblem.png"}}""");
            var display = dev.ellipog.tenet.quest.reward.RewardTypes.displayOf(pictured);
            assertTrue(display.item().isEmpty());
            assertEquals("my_pack:textures/gui/emblem.png", display.textureIcon());
        }
    }

    // ------------------------------------------------------------------
    // The validator's sentences
    // ------------------------------------------------------------------

    @Nested
    @DisplayName("validator")
    class Validation {

        private static Problems validateQuest(String json) {
            Problems problems = new Problems();
            QuestValidator.validateQuestDocument(Fixtures.document("quest.json", json), problems);
            return problems;
        }

        private static Problems validateChapter(String json) {
            Problems problems = new Problems();
            QuestValidator.validateChapterDocument(Fixtures.document("chapter.json", json), problems);
            return problems;
        }

        @Test
        @DisplayName("a minWidth past the legacy 3000 is an error")
        void minWidthIsBounded() {
            Problems problems = validateQuest("""
                    {"id": "one", "title": "One", "minWidth": 4000}""");

            assertTrue(problems.hasErrors(), "a panel 4000 wide is a typo, got: " + problems);
        }

        @Test
        @DisplayName("a quest minWidth in range loads clean")
        void minWidthInRangeLoadsClean() {
            assertFalse(validateQuest("""
                    {"id": "one", "title": "One", "minWidth": 250}""").hasErrors());
        }

        @Test
        @DisplayName("a chapter defaultMinWidth past 3000 is an error")
        void defaultMinWidthIsBounded() {
            Problems problems = validateChapter("""
                    {"id": "c", "title": "C", "defaultMinWidth": 9999, "quests": []}""");

            assertTrue(problems.hasErrors());
        }

        @Test
        @DisplayName("a blank autofocus is an error, not a centre on nothing")
        void blankAutofocusIsAnError() {
            Problems problems = validateChapter("""
                    {"id": "c", "title": "C", "autofocus": "  ", "quests": []}""");

            assertTrue(problems.hasErrors());
        }

        @Test
        @DisplayName("a task's and a reward's disableToast are known fields")
        void quietFieldsAreKnown() {
            assertFalse(validateQuest("""
                    {"id": "one", "title": "One",
                     "tasks": [{"type": "tenet:checkmark", "title": "x", "disableToast": true}],
                     "rewards": [{"type": "tenet:xp", "amount": 1, "disableToast": true}]}""")
                    .hasErrors());
        }

        @Test
        @DisplayName("a table's useTitle and hideTooltip are known fields")
        void tableDisplayFieldsAreKnown() {
            Problems problems = new Problems();
            QuestValidator.validateRewardTableDocument(Fixtures.document("loot.json",
                    """
                            {"entries": [{"weight": 1, "reward": {"type": "tenet:xp", "amount": 1}}],
                             "useTitle": true, "hideTooltip": true}"""),
                    problems);

            assertFalse(problems.hasErrors(), "got: " + problems);
        }
    }
}
