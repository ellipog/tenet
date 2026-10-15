package dev.ellipog.tenet.quest;

import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import com.mojang.serialization.DataResult;
import com.mojang.serialization.JsonOps;

import dev.ellipog.armature.api.data.DataProblem;
import dev.ellipog.armature.api.data.Problems;
import dev.ellipog.tenet.client.ClientQuestCache;
import dev.ellipog.tenet.editor.JsonFile;
import dev.ellipog.tenet.net.QuestSync;
import dev.ellipog.tenet.quest.condition.ConditionTypes;
import dev.ellipog.tenet.quest.condition.Conditions;
import dev.ellipog.tenet.quest.condition.QuestCondition;
import dev.ellipog.tenet.quest.condition.UnknownCondition;
import dev.ellipog.tenet.quest.reward.RewardTypes;
import dev.ellipog.tenet.quest.reward.UnknownReward;
import dev.ellipog.tenet.quest.task.TaskTypes;
import dev.ellipog.tenet.quest.task.UnknownTask;

import net.minecraft.resources.Identifier;

import org.junit.jupiter.api.BeforeAll;
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
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The format's promise to a file written by an older build, and to one written by a newer one.
 *
 * <h2>What "additive compatibility" means here, in four claims</h2>
 *
 * <ol>
 *   <li><b>A file missing a field parses.</b> Every field added since the first release has a default,
 *       so a stripped payload decodes to a complete object. The other tests exercise this incidentally;
 *       {@link #everyRegisteredTaskTypeDecodesFromItsRequiredFieldsAlone()} and its two siblings do it
 *       exhaustively, one minimal payload per registered type.</li>
 *   <li><b>An unknown type costs one node, not one file.</b> This is the claim that was false, and the
 *       reason this class exists. A codec error refuses a whole document, so before the placeholder
 *       types a single task naming an uninstalled addon dropped every quest in the file. The
 *       {@code UnknownType} tests below pin the new answer from both directions: the codec keeps the
 *       node, and the validator reports it as a warning rather than refusing the file.</li>
 *   <li><b>Saving does not destroy what this build does not understand.</b> Asserted on the editor's
 *       own writer, because that is the only writer there is: it patches a retained tree rather than
 *       re-encoding a decoded record, so an unknown type's payload is still on disk afterwards.</li>
 *   <li><b>A save is byte-stable.</b> Two saves of one file produce identical bytes, and an edit
 *       produces a diff about the field that changed. This is what keeps a Git diff about the author's
 *       change rather than about the serialiser.</li>
 * </ol>
 *
 * <h2>What it deliberately does not claim</h2>
 *
 * <p>An unknown <b>field</b> on a known type is still an error, and {@link
 * #anUnknownFieldOnAKnownTypeIsStillAnError()} says so out loud rather than leaving it to be
 * discovered. That is a policy this file records rather than a gap it papers over: Minecraft's codecs
 * ignore a field they do not know, so a typo is otherwise silent — {@code "titl"} produces a quest with
 * a blank title, no error, and nothing in any log. There is no way to tell a misspelling from a field a
 * future build added, and the trade this format makes is to refuse loudly and let the author fix it.
 * Changing it would be a product decision, not a hardening one; see the note on that test.
 *
 * <p>The vanilla bootstrap is here because the validator resolves item ids against
 * {@code BuiltInRegistries.ITEM}, and the loader runs the validator.
 */
@DisplayName("legacy and forward compatibility")
class LegacyPayloadCompatibilityTest {

    @BeforeAll
    static void bootVanilla() {
        // Needed by the validator, which asks whether every item id a file names exists. See
        // MinecraftTestBootstrap: an unbootstrapped registry does not report everything missing, it
        // throws from inside a vanilla class initialiser.
        MinecraftTestBootstrap.boot();
    }

    @TempDir
    Path temp;

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    private static JsonElement json(String text) {
        return JsonParser.parseString(text);
    }

    private static <T> T decoded(DataResult<T> result, String what) {
        return result.result().orElseThrow(() -> new AssertionError(what + " did not decode: "
                + result.error().map(DataResult.Error::message).orElse("no message")));
    }

    private static QuestTask task(String text) {
        return decoded(TaskTypes.dispatchCodec().parse(JsonOps.INSTANCE, json(text)), text);
    }

    private static QuestReward reward(String text) {
        return decoded(RewardTypes.dispatchCodec().parse(JsonOps.INSTANCE, json(text)), text);
    }

    private static QuestCondition condition(String text) {
        return decoded(ConditionTypes.dispatchCodec().parse(JsonOps.INSTANCE, json(text)), text);
    }

    private static Quest quest(String text) {
        return decoded(Quest.CODEC.parse(JsonOps.INSTANCE, json(text)), text);
    }

    private static Problems validateQuest(String text) {
        Problems problems = new Problems();
        QuestValidator.validateQuestDocument(Fixtures.document("one.json", text), problems);
        return problems;
    }

    /** One problem whose message contains {@code text}, at any severity. */
    private static DataProblem problemMentioning(Problems problems, String text) {
        return problems.all().stream()
                .filter(problem -> problem.message().contains(text))
                .findFirst()
                .orElseThrow(() -> new AssertionError("nothing mentioned '" + text + "'. Got:\n"
                        + problems.all().stream().map(DataProblem::render).toList()));
    }

    // ------------------------------------------------------------------
    // 1. A stripped payload decodes, for every registered type
    // ------------------------------------------------------------------

    /**
     * One minimal task per registered type: the type id plus only the fields its own codec requires.
     *
     * <p>This is the list the audit produced, written down as a test. Each row is the smallest JSON a
     * file could carry for that type, so a field that quietly became required shows up here as a
     * failure naming the type rather than as a pack that stopped loading after an upgrade.
     *
     * <p>{@code tenet:kill} and {@code tenet:break} are present with no fields at all beyond
     * their type, which is the floor: a type whose every setting defaults is a type a legacy
     * file can name and nothing else.
     */
    private static final List<String> MINIMAL_TASKS = List.of(
            "{\"type\": \"tenet:item\", \"item\": \"minecraft:oak_log\"}",
            "{\"type\": \"tenet:item_tag\", \"tag\": \"minecraft:logs\"}",
            "{\"type\": \"tenet:checkmark\", \"title\": \"Did it\"}",
            "{\"type\": \"tenet:dimension\", \"dimension\": \"minecraft:overworld\"}",
            "{\"type\": \"tenet:biome\", \"biome\": \"minecraft:plains\"}",
            "{\"type\": \"tenet:structure\", \"structure\": \"minecraft:village_plains\"}",
            "{\"type\": \"tenet:advancement\", \"advancement\": \"minecraft:story/root\"}",
            "{\"type\": \"tenet:stat\", \"stat\": \"minecraft:walk_one_cm\", \"value\": 1000}",
            "{\"type\": \"tenet:location\", \"position\": [0, 0, 0]}",
            "{\"type\": \"tenet:xp\", \"value\": 100}",
            "{\"type\": \"tenet:fluid\", \"fluid\": \"minecraft:water\", \"amount\": 1000}",
            "{\"type\": \"tenet:observation\", \"toObserve\": \"minecraft:beacon\"}",
            "{\"type\": \"tenet:kill\"}",
            "{\"type\": \"tenet:break\"}",
            "{\"type\": \"tenet:custom\", \"id\": \"example:thing\", \"value\": 1}",
            "{\"type\": \"tenet:filter\", \"filter\": \"item(minecraft:stone)\"}",
            "{\"type\": \"tenet:energy\", \"value\": 1000}",
            "{\"type\": \"tenet:stage\", \"stage\": \"example:stage\"}");

    /** The same, one per registered reward type. */
    private static final List<String> MINIMAL_REWARDS = List.of(
            "{\"type\": \"tenet:item\", \"item\": \"minecraft:stone\"}",
            "{\"type\": \"tenet:xp\", \"amount\": 30}",
            "{\"type\": \"tenet:random\"}",
            "{\"type\": \"tenet:loot\"}",
            "{\"type\": \"tenet:all_table\"}",
            "{\"type\": \"tenet:choice\"}",
            "{\"type\": \"tenet:command\", \"command\": \"say hello\"}",
            "{\"type\": \"tenet:currency\", \"amount\": 50}",
            "{\"type\": \"tenet:toast\", \"description\": \"Hello\"}",
            "{\"type\": \"tenet:advancement\", \"advancement\": \"minecraft:story/root\"}",
            "{\"type\": \"tenet:custom\", \"id\": \"example:thing\"}",
            "{\"type\": \"tenet:stage\", \"stage\": \"example:stage\"}");

    /** And one per registered condition type. */
    private static final List<String> MINIMAL_CONDITIONS = List.of(
            "{\"type\": \"tenet:item\", \"item\": \"minecraft:stone\"}",
            "{\"type\": \"tenet:item_tag\", \"tag\": \"minecraft:logs\"}",
            "{\"type\": \"tenet:score\", \"objective\": \"points\", \"min\": 1}",
            "{\"type\": \"tenet:advancement\", \"advancement\": \"minecraft:story/root\"}",
            "{\"type\": \"tenet:stage\", \"stage\": \"example:stage\"}",
            "{\"type\": \"tenet:party_size\", \"min\": 2}");

    @Test
    @DisplayName("every registered task type decodes from its required fields alone")
    void everyRegisteredTaskTypeDecodesFromItsRequiredFieldsAlone() {
        for (String minimal : MINIMAL_TASKS) {
            QuestTask parsed = task(minimal);
            assertTrue(TaskTypes.ids().contains(parsed.type()),
                    minimal + " decoded to " + parsed.type() + ", which is not a registered type - so "
                            + "either the fixture names the wrong type or a type stopped registering");
            // The defaults every task carries, which is the other half of "stripped payload": absent
            // means the documented value rather than null or zero-by-accident.
            assertFalse(parsed.optional(), minimal + ": `optional` must default to false");
            // Not one number: `TaskCommon.mapCodec(defaultInterval)` gives each type its own cadence, so
            // a stat task is asked every 3 ticks and a dimension check every 100. What must hold is that
            // the fallback is a real interval in the codec's own range -- a zero here would be a task
            // the engine re-checks every tick, which is the expensive fault the field exists to prevent.
            assertTrue(parsed.common().autoSubmitTicks() >= 1,
                    minimal + ": `autoSubmitTicks` must fall back to the type's own cadence, not zero");
            assertTrue(parsed.common().autoSubmitTicks() <= 72000,
                    minimal + ": and it must stay inside the range the codec accepts");
            assertTrue(parsed.common().conditions().isEmpty(),
                    minimal + ": `conditions` must default to an empty list, not null");
        }
        assertEquals(TaskTypes.count(), MINIMAL_TASKS.size(),
                "this list is meant to cover every registered task type - a new type belongs here, "
                        + "with the smallest payload its codec accepts");
    }

    @Test
    @DisplayName("every registered reward type decodes from its required fields alone")
    void everyRegisteredRewardTypeDecodesFromItsRequiredFieldsAlone() {
        for (String minimal : MINIMAL_REWARDS) {
            QuestReward parsed = reward(minimal);
            assertTrue(RewardTypes.ids().contains(parsed.type()),
                    minimal + " decoded to " + parsed.type() + ", which is not a registered type");
            assertTrue(parsed.common().team().isEmpty(),
                    minimal + ": `team` must default to absent, which is 'defer to the tree'");
            assertTrue(parsed.common().conditions().isEmpty(),
                    minimal + ": `conditions` must default to an empty list, not null");
        }
        assertEquals(RewardTypes.count(), MINIMAL_REWARDS.size(),
                "this list is meant to cover every registered reward type");
    }

    @Test
    @DisplayName("every registered condition type decodes from its required fields alone")
    void everyRegisteredConditionTypeDecodesFromItsRequiredFieldsAlone() {
        for (String minimal : MINIMAL_CONDITIONS) {
            QuestCondition parsed = condition(minimal);
            assertTrue(ConditionTypes.ids().contains(parsed.type()),
                    minimal + " decoded to " + parsed.type() + ", which is not a registered type");
        }
        assertEquals(ConditionTypes.count(), MINIMAL_CONDITIONS.size(),
                "this list is meant to cover every registered condition type");
    }

    @Test
    @DisplayName("a quest with nothing but an id and a title is a complete quest")
    void aQuestWithOnlyAnIdAndATitleIsComplete() {
        // The floor for a quest, and the shape Fixtures emits when every optional field is at its
        // default. Nothing here may be null: the whole point of the defaults is that a caller can walk
        // a stripped quest without a null check.
        Quest stripped = quest("{\"id\": \"one\", \"title\": \"One\"}");

        assertEquals("one", stripped.id());
        assertEquals("One", stripped.title().value());
        assertTrue(stripped.subtitle().isEmpty(), "an absent subtitle is empty, not null");
        assertTrue(stripped.description().isEmpty(), "an absent description is an empty list");
        assertTrue(stripped.aliases().isEmpty());
        assertTrue(stripped.dependencies().isEmpty());
        assertTrue(stripped.dependencyLines().isEmpty());
        assertTrue(stripped.tasks().isEmpty(), "an absent tasks list is empty, not null");
        assertTrue(stripped.rewards().isEmpty(), "an absent rewards list is empty, not null");
        assertTrue(stripped.prerequisiteMode().isEmpty(), "absent means 'the chapter decides'");
        assertEquals(0, stripped.minRequired());
        assertEquals(Icon.DEFAULT_ICON, stripped.icon(), "an absent icon is paper, not null");
        assertEquals(QuestLayout.DEFAULT, stripped.layout(),
                "an absent layout is the whole documented default, not a zeroed record");
        assertEquals(QuestRules.DEFAULT, stripped.rules());
    }

    @Test
    @DisplayName("a chapter with nothing but an id and a title is a complete chapter")
    void aChapterWithOnlyAnIdAndATitleIsComplete() {
        Chapter stripped = decoded(Chapter.CODEC.parse(JsonOps.INSTANCE,
                json("{\"id\": \"c\", \"title\": \"C\"}")), "chapter");

        assertTrue(stripped.subtitle().isEmpty());
        assertTrue(stripped.description().isEmpty());
        assertTrue(stripped.aliases().isEmpty());
        assertTrue(stripped.quests().isEmpty(), "an absent quests list is empty, not null");
        assertEquals(Icon.DEFAULT_ICON, stripped.icon());
        assertEquals(PrerequisiteMode.ALL_COMPLETED, stripped.defaultPrerequisiteMode());
        assertEquals(ProgressionMode.FLEXIBLE, stripped.progressionMode(),
                "FLEXIBLE, so that declaring a dependency in a stripped chapter still means something");
        assertFalse(stripped.defaultConsumeItems());
        assertTrue(stripped.theme().isEmpty());
        assertTrue(stripped.themePatch().isEmpty());
        assertEquals(dev.ellipog.tenet.quest.reward.RewardAutoClaim.DEFAULT, stripped.autoClaim());
        assertTrue(stripped.links().isEmpty(), "an absent links list is empty, not null");
    }

    @Test
    @DisplayName("a version-1 file with no `version` key at all still decodes")
    void aVersionOneFileWithNoVersionKeyDecodes() {
        // The oldest shape there is: a flat file written before the field existed. The loader recognises
        // it by position rather than by the number, and the codec has to agree -- an absent version is
        // version 1, not a refusal.
        QuestFile parsed = Fixtures.decode("legacy.json", Fixtures.document("legacy.json", """
                { "chapterGroups": [
                    { "id": "g", "title": "G",
                      "chapters": [ { "id": "c", "title": "C", "quests": [] } ] } ] }"""));

        assertEquals(QuestFile.CURRENT_VERSION, parsed.version());
        assertEquals(1, parsed.chapterGroups().size());
        assertEquals("g", parsed.chapterGroups().get(0).id());
        assertEquals(1, parsed.chapterGroups().get(0).chapters().size());
        assertFalse(parsed.chapterGroups().get(0).collapsedByDefault(),
                "a group field added after this file was written must default to false");
    }

    @Test
    @DisplayName("a version-1 file with no `chapterGroups` at all is an empty file, not a failure")
    void aVersionOneFileWithNoChapterGroupsDecodes() {
        QuestFile parsed = Fixtures.decode("empty.json",
                Fixtures.document("empty.json", "{ \"version\": 1 }"));

        assertEquals(QuestFile.CURRENT_VERSION, parsed.version());
        assertTrue(parsed.chapterGroups().isEmpty(), "absent is empty, not null");
        assertTrue(parsed.allQuests().isEmpty());
    }

    @Test
    @DisplayName("a reward table with an empty entry list decodes")
    void aRewardTableWithNoEntriesDecodes() {
        dev.ellipog.tenet.quest.loot.RewardTable parsed = decoded(
                dev.ellipog.tenet.quest.loot.RewardTable.CODEC.parse(JsonOps.INSTANCE,
                        json("{\"entries\": []}")), "reward table");

        assertTrue(parsed.entries().isEmpty());
        assertEquals(1, parsed.lootSize(), "`lootSize` defaults to one throw");
        assertEquals(0.0, parsed.emptyWeight(), "`emptyWeight` defaults to no empty band");
        assertTrue(parsed.title().isEmpty());
        assertTrue(parsed.icon().isEmpty());
        assertTrue(parsed.uid().isEmpty());
    }

    @Test
    @DisplayName("a v2 chapter manifest with only an id and a title decodes to the v1 defaults")
    void aChapterManifestWithOnlyAnIdAndATitleDecodes() {
        ChapterManifest parsed = decoded(ChapterManifest.CODEC.parse(JsonOps.INSTANCE,
                json("{\"id\": \"c\", \"title\": \"C\"}")), "chapter manifest");

        assertTrue(parsed.quests().isEmpty(), "absent is empty, not null");
        assertEquals(ProgressionMode.FLEXIBLE, parsed.progressionMode());
        assertEquals(PrerequisiteMode.ALL_COMPLETED, parsed.defaultPrerequisiteMode());
        assertFalse(parsed.defaultConsumeItems());
        assertEquals(Icon.DEFAULT_ICON, parsed.icon());
        assertTrue(parsed.links().isEmpty(), "an absent links list is empty, not null");
    }

    @Test
    @DisplayName("a v2 group manifest with only an id and a title decodes")
    void aGroupManifestWithOnlyAnIdAndATitleDecodes() {
        GroupManifest parsed = decoded(GroupManifest.CODEC.parse(JsonOps.INSTANCE,
                json("{\"id\": \"g\", \"title\": \"G\"}")), "group manifest");

        assertTrue(parsed.chapters().isEmpty(), "absent is empty, not null");
        assertTrue(parsed.description().isEmpty());
        assertTrue(parsed.aliases().isEmpty());
        assertTrue(parsed.icon().isEmpty(), "an absent group icon stays absent, so the client can fall "
                + "back to the first chapter rather than to paper");
        assertFalse(parsed.collapsedByDefault());
    }

    // ------------------------------------------------------------------
    // 2. An unknown type costs one node, not one file
    // ------------------------------------------------------------------

    @Test
    @DisplayName("an unknown task type decodes to a placeholder that keeps the id")
    void anUnknownTaskTypeDecodesToAPlaceholder() {
        QuestTask parsed = task("{\"type\": \"someothermod:reticulate\", \"splines\": 4}");

        UnknownTask unknown = assertInstanceOf(UnknownTask.class, parsed,
                "an unregistered type must decode to UnknownTask rather than failing");
        assertEquals(Identifier.fromNamespaceAndPath("someothermod", "reticulate"), unknown.type(),
                "the id survives, which is what lets the author see which mod is missing");
        assertFalse(unknown.optional(), "the placeholder carries the common defaults, not null");
        assertEquals(20, unknown.common().autoSubmitTicks());
    }

    @Test
    @DisplayName("an unknown reward type decodes to a placeholder that keeps the id")
    void anUnknownRewardTypeDecodesToAPlaceholder() {
        QuestReward parsed = reward("{\"type\": \"someothermod:windfall\", \"amount\": 7}");

        UnknownReward unknown = assertInstanceOf(UnknownReward.class, parsed);
        assertEquals(Identifier.fromNamespaceAndPath("someothermod", "windfall"), unknown.type());
        assertFalse(unknown.autoGrantable(),
                "an unknown reward must NOT be auto-grantable: an automatic path marks a reward "
                        + "collected and then grants it, so this would lose it while granting nothing");
    }

    @Test
    @DisplayName("an unknown condition type decodes to a placeholder, and the gate stays shut")
    void anUnknownConditionTypeDecodesToAPlaceholder() {
        QuestCondition parsed = condition("{\"type\": \"someothermod:whenever\", \"phase\": \"moon\"}");

        UnknownCondition unknown = assertInstanceOf(UnknownCondition.class, parsed);
        assertEquals(Identifier.fromNamespaceAndPath("someothermod", "whenever"), unknown.type());
        // The safe direction for a gate, and the reason UnknownCondition registers no behaviour: a
        // condition that cannot be read must not read as satisfied, or a lock silently opens.
        assertFalse(Conditions.passes(List.of(unknown), null),
                "an unreadable condition must be unmet, not met");
    }

    @Test
    @DisplayName("an unknown task is never satisfied, and offers no button")
    void anUnknownTaskIsNeverSatisfied() {
        UnknownTask unknown = (UnknownTask) task("{\"type\": \"someothermod:reticulate\"}");

        var behaviour = TaskTypes.behaviourOf(unknown)
                .orElseThrow(() -> new AssertionError("an unknown task must answer with a behaviour "
                        + "rather than leaving the engine to its fallback"));
        assertEquals(1, behaviour.required(unknown), "one, so the row reads 'not done'");
        assertEquals(0, behaviour.current(unknown, null),
                "zero always: there is no handler to ask, so the quest sits visibly incomplete rather "
                        + "than silently completing itself");
        assertFalse(behaviour.canSubmitByHand(unknown, false),
                "a Submit button on a task nothing can evaluate is a control that does nothing");
    }

    @Test
    @DisplayName("an unknown type is drawn as a row naming it, rather than as a blank one")
    void anUnknownTypeIsDrawnNamingIt() {
        // A blank row in the middle of a quest is indistinguishable from a task that does nothing, and
        // the sentence is what tells the author which mod to install.
        assertEquals("Unknown task type: someothermod:reticulate",
                TaskTypes.displayOf(task("{\"type\": \"someothermod:reticulate\"}")).labelFallback());
        assertEquals("Unknown reward type: someothermod:windfall",
                RewardTypes.displayOf(reward("{\"type\": \"someothermod:windfall\"}")).labelFallback());
        assertEquals("Unknown condition type: someothermod:whenever",
                ConditionTypes.displayOf(condition("{\"type\": \"someothermod:whenever\"}")).labelFallback());
    }

    @Test
    @DisplayName("a quest holding an unknown task still decodes, and its other tasks survive")
    void aQuestHoldingAnUnknownTaskStillDecodes() {
        // The claim in one assertion: the failure is local to the node. Before the placeholder types this
        // decode returned an error and the whole document was refused.
        Quest parsed = quest("""
                { "id": "one", "title": "One",
                  "tasks": [ { "type": "someothermod:reticulate", "splines": 4 },
                             { "type": "tenet:checkmark", "title": "Did it" } ] }""");

        assertEquals(2, parsed.tasks().size(), "both tasks must survive the unknown one");
        assertInstanceOf(UnknownTask.class, parsed.tasks().get(0));
        assertEquals(Identifier.fromNamespaceAndPath("tenet", "checkmark"),
                parsed.tasks().get(1).type(),
                "the task after the unknown one is untouched");
    }

    @Test
    @DisplayName("an unknown reward type inside a reward table still decodes the table")
    void anUnknownRewardTypeInsideATableStillDecodes() {
        dev.ellipog.tenet.quest.loot.RewardTable parsed = decoded(
                dev.ellipog.tenet.quest.loot.RewardTable.CODEC.parse(JsonOps.INSTANCE, json("""
                        { "entries": [
                            { "weight": 0, "reward": { "type": "someothermod:windfall", "amount": 7 } },
                            { "weight": 5, "reward": { "type": "tenet:item", "item": "minecraft:stone" } } ] }""")),
                "reward table");

        assertEquals(2, parsed.entries().size());
        assertInstanceOf(UnknownReward.class, parsed.entries().get(0).reward());
        assertEquals(0.0, parsed.entries().get(0).weight(),
                "a weight-zero entry is the 'everyone also gets this' band, and it survives too");
    }

    @Test
    @DisplayName("the validator reports an unknown task type as a warning, not an error")
    void anUnknownTaskTypeIsAWarning() {
        Problems problems = validateQuest("""
                {"id": "one", "title": "One",
                 "tasks": [{"type": "someothermod:reticulate", "splines": 4}]}""");

        DataProblem problem = problemMentioning(problems, "unknown quest task type");
        assertEquals(DataProblem.Severity.WARNING, problem.severity(),
                "an error here means the file is not decoded at all, which is what made one node cost "
                        + "the whole file");
        assertFalse(problems.hasErrors(),
                "nothing about this file may be fatal, or the loader will skip it: " + problems.all());
        assertTrue(problem.message().contains("tenet:item"),
                "the message must still list the types this build does have: " + problem.message());
        assertTrue(problem.message().contains("someothermod:reticulate"),
                "and name the one it does not: " + problem.message());
    }

    @Test
    @DisplayName("the validator reports unknown reward and condition types as warnings too")
    void unknownRewardAndConditionTypesAreWarnings() {
        Problems problems = validateQuest("""
                {"id": "one", "title": "One",
                 "tasks": [{"type": "tenet:checkmark", "title": "Did it",
                            "conditions": [{"type": "someothermod:whenever", "phase": "moon"}]}],
                 "rewards": [{"type": "someothermod:windfall", "amount": 7}]}""");

        assertEquals(DataProblem.Severity.WARNING,
                problemMentioning(problems, "unknown quest condition type").severity());
        assertEquals(DataProblem.Severity.WARNING,
                problemMentioning(problems, "unknown quest reward type").severity());
        assertFalse(problems.hasErrors(),
                "an unknown type anywhere must leave the file loadable: " + problems.all());
    }

    @Test
    @DisplayName("an unknown type's own fields are not reported as unknown fields")
    void anUnknownTypesFieldsAreNotReported() {
        // The node is reported once, at its own line. Reporting each of its fields as well would be a
        // page of noise about a type this build has no field list for -- and it would be an *error*,
        // which would refuse the file the warning was careful not to.
        Problems problems = validateQuest("""
                {"id": "one", "title": "One",
                 "tasks": [{"type": "someothermod:reticulate", "splines": 4, "wobble": true}]}""");

        assertFalse(problems.hasErrors(), problems.all().toString());
        assertTrue(problems.all().stream().noneMatch(p -> p.message().contains("unknown field")),
                "no field of an unreadable type may be called an unknown field: " + problems.all());
    }

    @Test
    @DisplayName("a malformed biome id is a decode error, NOT a thrown exception")
    void aMalformedRegistryRefDoesNotThrow() {
        // This is the one that used to abort the *entire* questline load. RegistryRef's codec was a
        // plain `xmap` over a method that throws, and DFU does not catch what an xmap function throws --
        // so the exception left the codec as a raw throwable, past QuestLoader.decode (which handles
        // DataResult errors and has no try/catch), and was caught only at the top of the load. Every
        // chapter in every file went with it.
        DataResult<QuestTask> result = TaskTypes.dispatchCodec().parse(JsonOps.INSTANCE,
                json("{\"type\": \"tenet:biome\", \"biome\": \"not a biome!\"}"));

        assertTrue(result.error().isPresent(),
                "a malformed id must come back as a DataResult error, so the file is refused and the "
                        + "load continues");
    }

    @Test
    @DisplayName("the validator survives a malformed biome id rather than throwing out of the load")
    void theValidatorSurvivesAMalformedRegistryRef() {
        // The same fault reached through validation, which runs *before* the decode and inside
        // QuestLoader.load -- so this was the path that took the whole load down.
        Problems problems = validateQuest("""
                {"id": "one", "title": "One",
                 "tasks": [{"type": "tenet:biome", "biome": "not a biome!"}]}""");

        assertTrue(problems.hasErrors(),
                "it is reported against this file, which is the loud answer -- what it must not do is "
                        + "escape as an exception");
    }

    @Test
    @DisplayName("a tag reference still round-trips its own spelling")
    void aTagReferenceRoundTrips() {
        // The fix above changed how RegistryRef decodes, so the spelling it writes back is worth
        // pinning: a file that says "#minecraft:is_forest" must keep saying it.
        QuestTask parsed = task("{\"type\": \"tenet:biome\", \"biome\": \"#minecraft:is_forest\"}");
        assertInstanceOf(dev.ellipog.tenet.quest.task.BiomeTask.class, parsed);

        JsonElement encoded = decoded(TaskTypes.dispatchCodec().encodeStart(JsonOps.INSTANCE, parsed),
                "biome task");
        assertEquals("#minecraft:is_forest", encoded.getAsJsonObject().get("biome").getAsString(),
                "the tag's own spelling must survive a decode and an encode");
    }

    @Test
    @DisplayName("an unknown task reaches the client as a labelled, inert row")
    void anUnknownTaskReachesTheClientAsALabelledRow() {
        // The other half of node-level isolation, and the half no test covered: the placeholder exists
        // on the *server* (above), and this asks whether it survives the trip to a client that has never
        // heard of the type either. `QuestSync` writes each task as hand-built JSON -- nothing in the
        // compiler connects that writer to `ClientQuestCache`'s reader -- so a field that stopped
        // travelling would parse cleanly and show a blank row, which is the fault QuestSyncTest exists
        // for and the reason this is asserted on the wire rather than on the model.
        //
        // It cannot be covered by the panel tests: QuestPanelLayoutTest and EntryFormLayoutTest lay out
        // an unknown type from a hand-built JsonObject, so they prove the *drawing* is right and say
        // nothing about whether a decoded placeholder ever gets there.
        QuestIndex index = Fixtures.indexOf("""
                { "version": 1, "chapterGroups": [ { "id": "g", "title": "G", "chapters": [
                    { "id": "c", "title": "C", "quests": [
                        { "id": "one", "title": "One",
                          "tasks": [ { "type": "someothermod:reticulate", "splines": 4 } ] } ] } ] } ] }""");

        byte[] wire = QuestSync.treeAsJson(index);
        JsonElement task = JsonParser.parseString(new String(wire, StandardCharsets.UTF_8))
                .getAsJsonObject()
                .getAsJsonArray("quests").get(0).getAsJsonObject()
                .getAsJsonArray("tasks").get(0);

        assertEquals("someothermod:reticulate", task.getAsJsonObject().get("type").getAsString(),
                "the client is told which type it does not have, which is the sentence an author needs");
        assertEquals("Unknown task type: someothermod:reticulate",
                task.getAsJsonObject().get("labelFallback").getAsString(),
                "and the row says so in words, rather than drawing nothing");
        assertEquals("minecraft:paper", task.getAsJsonObject().get("icon").getAsString(),
                "with the fallback icon, since the type's own is the thing this build does not have");
        assertFalse(task.getAsJsonObject().get("manual").getAsBoolean(),
                "and no Submit button: a control on a task nothing can evaluate does nothing");
        assertEquals(1, task.getAsJsonObject().get("count").getAsInt(),
                "one, so the row reads as not done rather than as a count nobody can reach");

        // And the client takes it without complaint, and knows the quest -- which is the risk on this
        // side: a node whose shape the reader did not expect is a parse that dies or a quest that
        // vanishes from the book, and neither would be visible from the server.
        ClientQuestCache.acceptTree(index.questCount(), index.chapterCount(), wire);
        assertNotNull(ClientQuestCache.entry("one"),
                "the client parsed the tree: an unknown task must not break its reader");
        assertEquals(-1, ClientQuestCache.firstSubmitTask("one"),
                "and offers no Submit button on a task nothing can evaluate, since `manual` travelled "
                        + "as false -- a button there would be a press the server refuses");
    }

    // ------------------------------------------------------------------
    // 3. An unknown field is still an error, deliberately
    // ------------------------------------------------------------------

    @Test
    @DisplayName("an unknown field on a known type is still an error, and that is a decision")
    void anUnknownFieldOnAKnownTypeIsStillAnError() {
        // Pinned rather than glossed, because it is the one part of forward compatibility this format
        // deliberately does NOT give. Minecraft's codecs ignore a field they do not know, so a typo is
        // otherwise silent: "titl" gives a quest with a blank title, no error, and nothing in any log.
        // There is no way to tell a misspelling from a field a future build added, and the trade made
        // here is to refuse loudly and name the likely correction.
        //
        // Changing this is a product decision rather than a hardening one: it would let a file from a
        // newer build load, and it would also let "dependson" load as a quest with no dependencies.
        Problems problems = validateQuest("""
                {"id": "one", "title": "One", "titl": "Somewhere to Work"}""");

        DataProblem problem = problemMentioning(problems, "unknown field \"titl\"");
        assertEquals(DataProblem.Severity.ERROR, problem.severity());
        assertTrue(problem.message().contains("did you mean \"title\""),
                "the suggestion is what makes the refusal cheap to act on: " + problem.message());
        assertTrue(problems.hasErrors(), "and the loader will skip this file, loudly");
    }

    @Test
    @DisplayName("a save of an unknown type's node keeps its payload, and is byte-stable")
    void aSaveKeepsAnUnknownTypesPayload() throws IOException {
        // Requirement 4, asserted on the only writer there is. The editor patches a retained tree rather
        // than re-encoding a decoded record -- which is what makes this true, and is why the placeholder
        // type does not need to carry the payload itself.
        String text = """
                {
                  "id": "one",
                  "title": "One",
                  "authorNote": "counted by hand, do not touch",
                  "tasks": [
                    { "type": "someothermod:reticulate", "splines": 4 }
                  ]
                }
                """;
        Path file = temp.resolve("one.json");

        JsonFile parsed = JsonFile.parse(file, text);
        // An edit to a field this build *does* understand, which is the case that used to be at risk.
        parsed.setText("title", "One, Renamed");
        String written = parsed.json();

        assertTrue(written.contains("someothermod:reticulate"),
                "the unknown type must still be in the file:\n" + written);
        assertTrue(written.contains("splines"),
                "and so must the payload this build cannot read:\n" + written);
        assertTrue(written.contains("counted by hand, do not touch"),
                "and a field no codec knows about at all:\n" + written);
        assertTrue(written.contains("One, Renamed"), "the edit landed");

        // Byte-stable: a save of a save is the same bytes, so a diff shows the author's change and
        // nothing else. This is what keeps a Git history readable.
        String again = JsonFile.parse(file, written).json();
        assertEquals(written, again, "two saves of one file must produce identical bytes");
    }

    @Test
    @DisplayName("a save leaves one file behind, not a temporary one as well")
    void aSaveLeavesNoTemporaryFile() throws IOException {
        // The editor writes through JsonWrite, which puts the text in a `_<name>.tmp` sibling and
        // renames it over the target. That is what makes a crash mid-save leave the old file rather
        // than a truncated one -- and it is also a new way to leave litter, in a directory the loader
        // walks. So the directory is asserted whole rather than by looking for a name: a file the
        // author never asked for is exactly the fault QuestLoaderTest's directory snapshot exists for,
        // and it would be just as unwelcome here.
        Path file = temp.resolve("one.json");

        JsonFile parsed = JsonFile.parse(file, "{\"id\": \"one\", \"title\": \"One\"}");
        parsed.setText("title", "Renamed");
        parsed.write();

        try (var stream = Files.list(temp)) {
            assertEquals(List.of(file), stream.toList(),
                    "the directory holds the file and nothing else - a leftover _one.json.tmp would be "
                            + "a name the loader has to be taught to skip and git shows as a change");
        }
    }

    @Test
    @DisplayName("an unedited file is not rewritten at all")
    void anUneditedFileIsNotRewritten() {
        // The strongest form of "non-destructive": nothing happens. `dirty()` compares the tree against a
        // deep copy of what was read, so a file the editor opened and did not change is not written --
        // which is why a visit to a chapter cannot reformat a file nobody edited.
        JsonFile parsed = JsonFile.parse(temp.resolve("one.json"),
                "{\n  \"id\": \"one\",\n  \"title\": \"One\"\n}\n");

        assertFalse(parsed.dirty(), "an opened file is not a changed file");
    }

    @Test
    @DisplayName("an edit's diff is about the field that changed, not the whole file")
    void anEditsDiffIsAboutTheFieldThatChanged() {
        // Key order is the file's, because a JsonObject is insertion-ordered and the editor patches in
        // place. A field the editor rewrote keeps its position, so a one-field edit is a one-line diff.
        String text = "{\"id\": \"one\", \"title\": \"One\", \"x\": 0, \"y\": 0}";
        JsonFile parsed = JsonFile.parse(temp.resolve("one.json"), text);
        parsed.setNumber("x", 32);
        String written = parsed.json();

        int idAt = written.indexOf("\"id\"");
        int titleAt = written.indexOf("\"title\"");
        int xAt = written.indexOf("\"x\"");
        int yAt = written.indexOf("\"y\"");
        assertTrue(idAt < titleAt && titleAt < xAt && xAt < yAt,
                "the file's own key order must survive an edit:\n" + written);
        assertEquals(1, written.lines().filter(line -> line.contains("\"x\"")).count(),
                "the changed field appears once:\n" + written);
        assertNotEquals(text, written, "the edit did land");
    }

    // ------------------------------------------------------------------
    // 4. Numbers: a value out of range is clamped, not fatal
    // ------------------------------------------------------------------

    @Test
    @DisplayName("an out-of-range size is clamped to the bounds rather than refused")
    void anOutOfRangeSizeIsClamped() {
        assertEquals(QuestLayout.MIN_SIZE, quest("""
                {"id": "one", "title": "One", "size": 1}""").layout().size(),
                "a node too small to draw is read as the smallest that can be");
        assertEquals(QuestLayout.MAX_SIZE, quest("""
                {"id": "one", "title": "One", "size": 4000}""").layout().size(),
                "and a typo for 40 is read as the largest that fits");
        assertEquals(64, quest("""
                {"id": "one", "title": "One", "size": 64}""").layout().size(),
                "an in-range value is untouched");
    }

    @Test
    @DisplayName("an out-of-range rotation and icon scale are clamped rather than refused")
    void anOutOfRangeRotationAndIconScaleAreClamped() {
        assertEquals(QuestLayout.MAX_ROTATION, quest("""
                {"id": "one", "title": "One", "rotation": 360}""").layout().rotation(),
                "a whole turn is written as 0, and a file that says 360 is read as the top of the range "
                        + "rather than refused");
        assertEquals(QuestLayout.MIN_ROTATION, quest("""
                {"id": "one", "title": "One", "rotation": -90}""").layout().rotation());
        assertEquals(QuestShape.MAX_ICON_SCALE, quest("""
                {"id": "one", "title": "One", "iconScale": 99.0}""").layout().iconScale(),
                1.0E-9, "the same clamp the wire already applies to this field");
        assertEquals(QuestShape.MIN_ICON_SCALE, quest("""
                {"id": "one", "title": "One", "iconScale": -5.0}""").layout().iconScale(), 1.0E-9);
        assertEquals(QuestLayout.DEFAULT_ICON_SCALE, quest("""
                {"id": "one", "title": "One"}""").layout().iconScale(), 1.0E-9,
                "absent still means the documented default, not the clamp");
    }

    @Test
    @DisplayName("the validator calls a clamped layout number a warning, so it and the codec agree")
    void aClampedLayoutNumberIsAWarning() {
        // A validator stricter than the format is the worst of both worlds: the file decodes, so the
        // format says it is fine, and is then refused, so the tool says it is not. Since the codec
        // clamps, these three checks have to warn -- and the sentence says what the value was read as,
        // which is the part the author needs.
        Problems problems = validateQuest("""
                {"id": "one", "title": "One", "size": 4000, "rotation": 360, "iconScale": 99.0}""");

        assertFalse(problems.hasErrors(),
                "a file the codec can read must not be refused by the validator: " + problems.all());
        assertEquals(DataProblem.Severity.WARNING, problemMentioning(problems, "size must be between").severity());
        assertEquals(DataProblem.Severity.WARNING, problemMentioning(problems, "rotation must be between").severity());
        assertEquals(DataProblem.Severity.WARNING, problemMentioning(problems, "iconScale must be between").severity());
        assertTrue(problemMentioning(problems, "size must be between").message().contains("read as 512"),
                "the message must say what it was read as");
    }

    // ------------------------------------------------------------------
    // 5. Versioning
    // ------------------------------------------------------------------

    @Test
    @DisplayName("a file claiming a newer version is a warning, and is still read")
    void aNewerVersionIsAWarning() {
        // The version number is not what decides whether a file can be read -- the decode is. Refusing
        // on the number alone refused files that read fine, and the case it cost is a newer build that
        // added a *task type*: that node is now a placeholder, so the rest of the file is readable and
        // the version should not be what stops it.
        Problems problems = new Problems();
        QuestValidator.validate(Fixtures.document("future.json", """
                { "version": 2, "chapterGroups": [ { "id": "g", "title": "G", "chapters": [] } ] }"""),
                problems);

        DataProblem problem = problemMentioning(problems, "this file is version 2");
        assertEquals(DataProblem.Severity.WARNING, problem.severity());
        assertFalse(problems.hasErrors(),
                "a newer version must not on its own make the file unloadable: " + problems.all());
    }

    @Test
    @DisplayName("a version below 1 is still an error, because it names no format at all")
    void aVersionBelowOneIsStillAnError() {
        Problems problems = new Problems();
        QuestValidator.validate(Fixtures.document("old.json", """
                { "version": 0, "chapterGroups": [] }"""), problems);

        assertEquals(DataProblem.Severity.ERROR,
                problemMentioning(problems, "version must be at least 1").severity());
    }

    // ------------------------------------------------------------------
    // 6. The whole pipeline: a directory with an unknown type in it
    // ------------------------------------------------------------------

    @Test
    @DisplayName("a chapter holding an unknown task type loads, and its other quests are indexed")
    void aChapterHoldingAnUnknownTaskTypeLoads() throws IOException {
        // The end-to-end form of the central claim, through the real loader: this is the shape a pack is
        // in the day an addon is removed, and before the placeholder types the file loaded with nothing
        // in it.
        //
        // Written as a version-1 flat file rather than as a chapter folder, deliberately: the folder
        // layout's own rules (which folder is a group, what index.json must list) are a different
        // subject with their own tests, and a fixture that trips one of them would report a folder
        // mistake while claiming to be about an unknown type.
        Path quests = temp.resolve(QuestLoader.DIRECTORY);
        Files.createDirectories(quests);
        Files.writeString(quests.resolve("legacy.json"), """
                { "version": 1,
                  "chapterGroups": [ { "id": "g", "title": "G", "chapters": [ {
                      "id": "c", "title": "C", "quests": [
                        { "id": "one", "title": "One",
                          "tasks": [ { "type": "someothermod:reticulate", "splines": 4 } ] },
                        { "id": "two", "title": "Two",
                          "tasks": [ { "type": "tenet:checkmark", "title": "Did it" } ] } ] } ] } ] }""",
                StandardCharsets.UTF_8);

        QuestLoader.Result result = QuestLoader.load(temp);

        assertTrue(result.ok(), "the load must be clean: " + result.problems().all());
        assertEquals(2, result.index().questCount(),
                "both quests must be indexed -- the unknown type costs one node, not the file");
        assertInstanceOf(UnknownTask.class,
                Fixtures.quest(result.index(), "one").tasks().get(0),
                "the unknown node is present as a placeholder rather than missing");
        assertEquals(Identifier.fromNamespaceAndPath("tenet", "checkmark"),
                Fixtures.quest(result.index(), "two").tasks().get(0).type(),
                "and the readable quest is untouched");
        assertEquals(DataProblem.Severity.WARNING,
                problemMentioning(result.problems(), "unknown quest task type").severity(),
                "the author is still told, at the file's own line");
    }

    @Test
    @DisplayName("one broken file does not cost the others, and an unknown type is not broken")
    void oneBrokenFileDoesNotCostTheOthers() throws IOException {
        // The two failures side by side, which is the distinction worth holding on to: a file this build
        // cannot parse is skipped and reported, and a file holding a type it does not know is loaded.
        Path quests = temp.resolve(QuestLoader.DIRECTORY);
        Files.createDirectories(quests);
        Files.writeString(quests.resolve("legacy.json"), """
                { "version": 1,
                  "chapterGroups": [ { "id": "g", "title": "G", "chapters": [ {
                      "id": "c", "title": "C", "quests": [
                        { "id": "one", "title": "One",
                          "tasks": [ { "type": "someothermod:reticulate" } ] },
                        { "id": "two", "title": "Two" } ] } ] } ] }""", StandardCharsets.UTF_8);
        Files.writeString(quests.resolve("broken.json"), "{ this is not JSON", StandardCharsets.UTF_8);

        QuestLoader.Result result = QuestLoader.load(temp);

        assertFalse(result.ok(), "the unparseable file is a genuine error");
        // Matched on the tail of the name rather than the whole of it, because a problem's file is
        // reported as a path relative to the quest root and the separator is the platform's. What
        // matters is that the error is attributed to the file that caused it and to no other.
        assertTrue(hasErrorAgainst(result.problems(), "broken.json"),
                "the unparseable file is reported against its own name: " + result.problems().all());
        assertFalse(hasErrorAgainst(result.problems(), "legacy.json"),
                "an unknown type is not an error: " + result.problems().all());
        assertEquals(2, result.index().questCount(),
                "the two readable quests load: one holding an unknown type, one stripped to its id");
    }

    /** Whether any <b>error</b> was reported against a file whose name ends with {@code suffix}. */
    private static boolean hasErrorAgainst(Problems problems, String suffix) {
        return problems.all().stream()
                .anyMatch(problem -> problem.severity() == DataProblem.Severity.ERROR
                        && problem.file().endsWith(suffix));
    }
}
