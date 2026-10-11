package dev.ellipog.tenet.quest;

import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import com.mojang.serialization.DataResult;
import com.mojang.serialization.JsonOps;

import dev.ellipog.tenet.quest.reward.CommandReward;
import dev.ellipog.tenet.quest.reward.ItemReward;
import dev.ellipog.tenet.quest.reward.RewardAutoClaim;
import dev.ellipog.tenet.quest.reward.RewardTypes;
import dev.ellipog.tenet.quest.task.AdvancementTask;
import dev.ellipog.tenet.quest.task.ComponentMatch;
import dev.ellipog.tenet.quest.task.ItemTask;
import dev.ellipog.tenet.quest.task.KillTask;
import dev.ellipog.tenet.quest.task.LocationTask;
import dev.ellipog.tenet.quest.task.ObservationTask;
import dev.ellipog.tenet.quest.task.StatTask;
import dev.ellipog.tenet.quest.task.TaskTypes;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The legacy rows that need no Tenet change, pinned as tests.
 *
 * <p>Batch 0 of the migration work verified that every row here already has a Tenet home, so the
 * migration tool may emit the middle column today: the legacy snake_case becomes Tenet's camelCase,
 * and an absent legacy field keeps Tenet's default. Each test decodes the JSON the tool would write
 * through the real codec, which is what makes this page fail first if a field quietly becomes
 * required or changes meaning. The human-readable contract is {@code docs/authoring/ftb-mapping.md};
 * this class is the same contract in a form the build checks.
 *
 * <p>What is deliberately <b>not</b> here: every row that still needs Tenet work (quest
 * {@code optional}, flexible progress, titles and icons, images, links, click actions, text
 * tokens, energy and currency rewards, file settings, presets). Those belong
 * to later batches, and adding them here early would pin JSON nothing reads.
 */
@DisplayName("the legacy mappings that need no Tenet change")
class LegacyMappingTest {

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

    private static Quest quest(String text) {
        return decoded(Quest.CODEC.parse(JsonOps.INSTANCE, json(text)), text);
    }

    private static Chapter chapter(String text) {
        return decoded(Chapter.CODEC.parse(JsonOps.INSTANCE, json(text)), text);
    }

    private static QuestSettings settings(String text) {
        return decoded(QuestSettings.CODEC.parse(JsonOps.INSTANCE, json(text)), text);
    }

    // ------------------------------------------------------------------
    // Tasks
    // ------------------------------------------------------------------

    @Test
    @DisplayName("advancement with a criterion decodes to that criterion")
    void advancementWithCriterion() {
        var parsed = assertInstanceOf(AdvancementTask.class,
                task("{\"type\": \"tenet:advancement\", \"advancement\": \"minecraft:story/mine_stone\","
                        + " \"criterion\": \"get_stone\"}"));
        assertEquals("minecraft:story/mine_stone", parsed.advancement().toString());
        assertEquals(Optional.of("get_stone"), parsed.criterion());
    }

    @Test
    @DisplayName("advancement without a criterion watches the whole advancement")
    void advancementWithoutCriterion() {
        // The tool turns the legacy empty-string criterion into an absent field rather than an empty
        // one: absent is "the whole advancement" and an empty string would name a criterion that
        // does not exist.
        var parsed = assertInstanceOf(AdvancementTask.class,
                task("{\"type\": \"tenet:advancement\", \"advancement\": \"minecraft:story/mine_stone\"}"));
        assertEquals(Optional.empty(), parsed.criterion());
    }

    @Test
    @DisplayName("observation carries its target, timer and observe type")
    void observationCarriesTargetTimerAndType() {
        var parsed = assertInstanceOf(ObservationTask.class,
                task("{\"type\": \"tenet:observation\", \"observeType\": \"block\","
                        + " \"toObserve\": \"minecraft:beacon\", \"timer\": 40}"));
        assertEquals(ObservationTask.ObserveType.BLOCK, parsed.observeType());
        assertEquals("minecraft:beacon", parsed.toObserve());
        assertEquals(40, parsed.timer());
    }

    @Test
    @DisplayName("observation defaults to watching a block for a second")
    void observationDefaults() {
        var parsed = assertInstanceOf(ObservationTask.class,
                task("{\"type\": \"tenet:observation\", \"toObserve\": \"minecraft:beacon\"}"));
        assertEquals(ObservationTask.ObserveType.BLOCK, parsed.observeType());
        assertEquals(20, parsed.timer());
    }

    @Test
    @DisplayName("stat carries its id and value")
    void statCarriesIdAndValue() {
        var parsed = assertInstanceOf(StatTask.class,
                task("{\"type\": \"tenet:stat\", \"stat\": \"minecraft:walk_one_cm\", \"value\": 1000}"));
        assertEquals("minecraft:walk_one_cm", parsed.stat().toString());
        assertEquals(1000, parsed.value());
    }

    @Test
    @DisplayName("kill carries tag, count, name and NBT filter")
    void killCarriesAllFilters() {
        var parsed = assertInstanceOf(KillTask.class,
                task("{\"type\": \"tenet:kill\", \"entity\": \"minecraft:zombie\","
                        + " \"entityTypeTag\": \"minecraft:undead\", \"value\": 5,"
                        + " \"customName\": \"Bob\", \"nbtFilter\": \"{Color: 4b}\"}"));
        assertEquals(Optional.of("minecraft:zombie"), parsed.entity().map(Object::toString));
        assertEquals(Optional.of("minecraft:undead"), parsed.entityTypeTag().map(Object::toString));
        assertEquals(5, parsed.value());
        assertEquals(Optional.of("Bob"), parsed.customName());
        assertEquals(Optional.of("{Color: 4b}"), parsed.nbtFilter());
    }

    @Test
    @DisplayName("item match speaks the legacy three words")
    void itemMatchSpeaksThreeWords() {
        // the legacy `match_components` vocabulary, identical on both sides; strict stays the default.
        for (var row : List.of(
                new String[]{"none", "NONE"}, new String[]{"fuzzy", "FUZZY"}, new String[]{"strict", "STRICT"})) {
            var parsed = assertInstanceOf(ItemTask.class,
                    task("{\"type\": \"tenet:item\", \"item\": \"minecraft:oak_log\", \"match\": \""
                            + row[0] + "\"}"));
            assertEquals(ComponentMatch.valueOf(row[1]), parsed.match(), "match " + row[0]);
        }
        assertEquals(ComponentMatch.STRICT,
                assertInstanceOf(ItemTask.class,
                        task("{\"type\": \"tenet:item\", \"item\": \"minecraft:oak_log\"}")).match());
    }

    @Test
    @DisplayName("item onlyFromCrafting and consumeItems decode")
    void itemCraftingAndConsumeFlags() {
        var parsed = assertInstanceOf(ItemTask.class,
                task("{\"type\": \"tenet:item\", \"item\": \"minecraft:oak_log\","
                        + " \"onlyFromCrafting\": true, \"consumeItems\": true}"));
        assertTrue(parsed.onlyFromCrafting());
        assertEquals(Optional.of(true), parsed.consumeItems());
        // Absent defers to the chapter default rather than pinning false: the Optional must stay
        // empty so the chapter's answer shows through.
        assertEquals(Optional.empty(),
                assertInstanceOf(ItemTask.class,
                        task("{\"type\": \"tenet:item\", \"item\": \"minecraft:oak_log\"}"))
                        .consumeItems());
    }

    @Test
    @DisplayName("manual-only and tag crafting-only decode (T21/G13)")
    void manualAndTagCraftingFlags() {
        var item = assertInstanceOf(ItemTask.class,
                task("{\"type\": \"tenet:item\", \"item\": \"minecraft:oak_log\","
                        + " \"manualOnly\": true}"));
        assertTrue(item.manualOnly());
        assertFalse(assertInstanceOf(ItemTask.class,
                task("{\"type\": \"tenet:item\", \"item\": \"minecraft:oak_log\"}")).manualOnly());

        var tag = assertInstanceOf(dev.ellipog.tenet.quest.task.ItemTagTask.class,
                task("{\"type\": \"tenet:item_tag\", \"tag\": \"minecraft:logs\","
                        + " \"onlyFromCrafting\": true, \"manualOnly\": true}"));
        assertTrue(tag.onlyFromCrafting());
        assertTrue(tag.manualOnly());

        var fluid = assertInstanceOf(dev.ellipog.tenet.quest.task.FluidTask.class,
                task("{\"type\": \"tenet:fluid\", \"fluid\": \"minecraft:water\","
                        + " \"amount\": 1000, \"manualOnly\": true}"));
        assertTrue(fluid.manualOnly());
    }

    @Test
    @DisplayName("location carries its ignore-dimension flag")
    void locationIgnoreDimension() {
        var parsed = assertInstanceOf(LocationTask.class,
                task("{\"type\": \"tenet:location\", \"position\": [0, 64, 0], \"ignoreDimension\": true}"));
        assertTrue(parsed.ignoreDimension());
    }

    @Test
    @DisplayName("filter expressions decode verbatim (T23)")
    void filterExpressionDecodes() {
        var parsed = assertInstanceOf(dev.ellipog.tenet.quest.task.FilterTask.class,
                task("{\"type\": \"tenet:filter\","
                        + " \"filter\": \"or(item(minecraft:coal)item_tag(minecraft:coals))\","
                        + " \"count\": 4}"));
        assertEquals(4, parsed.count());
        assertEquals("or(item(minecraft:coal)item_tag(minecraft:coals))",
                parsed.filter().toString());
    }

    // ------------------------------------------------------------------
    // Rewards
    // ------------------------------------------------------------------

    @Test
    @DisplayName("reward auto speaks the legacy five words")
    void rewardAutoSpeaksFiveWords() {
        for (var row : List.of(
                new String[]{"default", "DEFAULT"}, new String[]{"disabled", "DISABLED"},
                new String[]{"enabled", "ENABLED"}, new String[]{"no_toast", "NO_TOAST"},
                new String[]{"invisible", "INVISIBLE"})) {
            var parsed = assertInstanceOf(ItemReward.class,
                    reward("{\"type\": \"tenet:item\", \"item\": \"minecraft:stone\", \"auto\": \""
                            + row[0] + "\"}"));
            assertEquals(RewardAutoClaim.valueOf(row[1]), parsed.common().auto(), "auto " + row[0]);
        }
    }

    @Test
    @DisplayName("reward team is a tristate that defers when absent")
    void rewardTeamTristate() {
        assertEquals(Optional.of(true),
                assertInstanceOf(ItemReward.class, reward(
                        "{\"type\": \"tenet:item\", \"item\": \"minecraft:stone\", \"team\": true}"))
                        .common().team());
        assertEquals(Optional.of(false),
                assertInstanceOf(ItemReward.class, reward(
                        "{\"type\": \"tenet:item\", \"item\": \"minecraft:stone\", \"team\": false}"))
                        .common().team());
        assertEquals(Optional.empty(),
                assertInstanceOf(ItemReward.class,
                        reward("{\"type\": \"tenet:item\", \"item\": \"minecraft:stone\"}"))
                        .common().team());
    }

    @Test
    @DisplayName("reward claim-all and reward-blocking flags decode")
    void rewardClaimAndBlockingFlags() {
        var parsed = assertInstanceOf(ItemReward.class,
                reward("{\"type\": \"tenet:item\", \"item\": \"minecraft:stone\","
                        + " \"excludeFromClaimAll\": true, \"ignoreRewardBlocking\": true}"));
        assertTrue(parsed.common().excludeFromClaimAll());
        assertTrue(parsed.common().ignoreRewardBlocking());
        var absent = assertInstanceOf(ItemReward.class,
                reward("{\"type\": \"tenet:item\", \"item\": \"minecraft:stone\"}"));
        assertFalse(absent.common().excludeFromClaimAll());
        assertFalse(absent.common().ignoreRewardBlocking());
    }

    @Test
    @DisplayName("quest ignore_reward_blocking decodes (G7)")
    void questIgnoreRewardBlocking() {
        assertTrue(quest("{\"id\": \"a\", \"title\": \"A\", \"ignoreRewardBlocking\": true}")
                .ignoreRewardBlocking());
        assertFalse(quest("{\"id\": \"a\", \"title\": \"A\"}").ignoreRewardBlocking());
    }

    @Test
    @DisplayName("disable_toast decodes on every quest object")
    void disableToastOnEveryObject() {
        assertTrue(quest("{\"id\": \"a\", \"title\": \"A\", \"disableToast\": true}").disableToast());
        assertFalse(quest("{\"id\": \"a\", \"title\": \"A\"}").disableToast());
        assertTrue(task("{\"type\": \"tenet:checkmark\", \"title\": \"x\", \"disableToast\": true}")
                .common().disableToast());
        assertFalse(task("{\"type\": \"tenet:checkmark\", \"title\": \"x\"}")
                .common().disableToast());
        var reward = assertInstanceOf(ItemReward.class,
                reward("{\"type\": \"tenet:item\", \"item\": \"minecraft:stone\","
                        + " \"disableToast\": true}"));
        assertTrue(reward.common().disableToast());
    }

    @Test
    @DisplayName("min_width, autofocus, hide_dependent_lines and table display decode")
    void presentationAndTableDisplayDecode() {
        assertEquals(250, quest("{\"id\": \"a\", \"title\": \"A\", \"minWidth\": 250}").minWidth());
        assertEquals(0, quest("{\"id\": \"a\", \"title\": \"A\"}").minWidth());
        assertTrue(quest("{\"id\": \"a\", \"title\": \"A\", \"hideDependentLines\": true}")
                .hideDependentLines());
        var chapter = chapter("{\"id\": \"c\", \"title\": \"C\","
                + " \"defaultMinWidth\": 350, \"autofocus\": \"a\"}");
        assertEquals(350, chapter.rules().defaultMinWidth());
        assertEquals("a", chapter.rules().autofocus().orElseThrow().id());
    }

    @Test
    @DisplayName("texture and entity icons decode on quests, chapters, groups and the book")
    void iconArmsDecode() {
        assertInstanceOf(Icon.Texture.class, quest("{\"id\": \"a\", \"title\": \"A\","
                + " \"icon\": {\"texture\": \"my_pack:textures/gui/emblem.png\"}}").icon());
        assertInstanceOf(Icon.Entity.class, quest("{\"id\": \"a\", \"title\": \"A\","
                + " \"icon\": {\"entity\": \"minecraft:creeper\"}}").icon());
        assertInstanceOf(Icon.Item.class, quest(
                "{\"id\": \"a\", \"title\": \"A\", \"icon\": {\"item\": \"minecraft:stone\"}}").icon());
        var textured = chapter("{\"id\": \"c\", \"title\": \"C\","
                + " \"icon\": {\"texture\": \"my_pack:textures/gui/emblem.png\"}}");
        assertInstanceOf(Icon.Texture.class, textured.icon());
    }

    @Test
    @DisplayName("command silent decodes")
    void commandSilent() {
        var parsed = assertInstanceOf(CommandReward.class,
                reward("{\"type\": \"tenet:command\", \"command\": \"say hello\", \"silent\": true}"));
        assertTrue(parsed.silent());
        assertEquals(2, parsed.permissionLevel());
    }

    @Test
    @DisplayName("currency amount decodes (T28)")
    void currencyAmountDecodes() {
        var parsed = assertInstanceOf(dev.ellipog.tenet.quest.reward.CurrencyReward.class,
                reward("{\"type\": \"tenet:currency\", \"amount\": 50}"));
        assertEquals(50, parsed.amount());
    }

    @Test
    @DisplayName("energy value and maxInput decode (T20)")
    void energyValueDecodes() {
        var parsed = assertInstanceOf(dev.ellipog.tenet.quest.task.EnergyTask.class,
                task("{\"type\": \"tenet:energy\", \"value\": 1000000, \"maxInput\": 1000}"));
        assertEquals(1000000, parsed.value());
        assertEquals(1000, parsed.maxInput());
        var plain = assertInstanceOf(dev.ellipog.tenet.quest.task.EnergyTask.class,
                task("{\"type\": \"tenet:energy\", \"value\": 1000000}"));
        assertEquals(0, plain.maxInput(), "absent means unlimited, which the migrated quest relies on");
    }

    @Test
    @DisplayName("command feedbackMessage and toast description decode (T18)")
    void toastAndFeedbackDecode() {        var command = assertInstanceOf(CommandReward.class,
                reward("{\"type\": \"tenet:command\", \"command\": \"say hello\","
                        + " \"feedbackMessage\": \"Well done.\"}"));
        assertEquals("Well done.", command.feedbackMessage().orElseThrow().value());

        var toast = assertInstanceOf(dev.ellipog.tenet.quest.reward.ToastReward.class,
                reward("{\"type\": \"tenet:toast\", \"description\": \"The vault is open.\"}"));
        assertEquals("The vault is open.", toast.description().value());
    }

    @Test
    @DisplayName("item onlyOne and randomBonus decode")
    void itemOnlyOneAndBonus() {
        var parsed = assertInstanceOf(ItemReward.class,
                reward("{\"type\": \"tenet:item\", \"item\": \"minecraft:stone\","
                        + " \"onlyOne\": true, \"randomBonus\": 3}"));
        assertTrue(parsed.onlyOne());
        assertEquals(3, parsed.randomBonus());
    }

    @Test
    @DisplayName("stage team flags decode (T22)")
    void stageTeamFlagsDecode() {
        var teamTask = assertInstanceOf(dev.ellipog.tenet.quest.task.StageTask.class,
                task("{\"type\": \"tenet:stage\", \"stage\": \"my_pack:inducted\","
                        + " \"teamStage\": true}"));
        assertTrue(teamTask.teamStage());
        var plainTask = assertInstanceOf(dev.ellipog.tenet.quest.task.StageTask.class,
                task("{\"type\": \"tenet:stage\", \"stage\": \"my_pack:inducted\"}"));
        assertFalse(plainTask.teamStage(), "absent reads the player's own stages");

        var teamReward = assertInstanceOf(dev.ellipog.tenet.quest.reward.StageReward.class,
                reward("{\"type\": \"tenet:stage\", \"stage\": \"my_pack:inducted\","
                        + " \"teamStage\": true}"));
        assertTrue(teamReward.teamStage());
        assertFalse(teamReward.remove());
        var plainReward = assertInstanceOf(dev.ellipog.tenet.quest.reward.StageReward.class,
                reward("{\"type\": \"tenet:stage\", \"stage\": \"my_pack:inducted\"}"));
        assertFalse(plainReward.teamStage(), "absent grants the claiming player");

        assertTrue(quest("{\"id\": \"a\", \"title\": \"A\", \"requiresStage\": \"my_pack:inducted\","
                + " \"requiresStageTeam\": true}").requiresStageTeam());
        assertFalse(quest("{\"id\": \"a\", \"title\": \"A\","
                + " \"requiresStage\": \"my_pack:inducted\"}").requiresStageTeam(),
                "absent gates on the player's own stages");
    }

    // ------------------------------------------------------------------
    // Quest, chapter and file rows
    // ------------------------------------------------------------------

    @Test
    @DisplayName("quest sequentialTasks decodes")
    void questSequentialTasks() {
        assertTrue(quest("{\"id\": \"a\", \"title\": \"A\", \"sequentialTasks\": true}").sequentialTasks());
        assertFalse(quest("{\"id\": \"a\", \"title\": \"A\"}").sequentialTasks());
    }

    @Test
    @DisplayName("quest hide flags keep their tristate")
    void questHideTristate() {
        // Absent is "no opinion" and the chapter default decides; false forces visible. Collapsing
        // absent into false would pin the chapter default the moment a quest is written.
        assertEquals(Optional.empty(),
                quest("{\"id\": \"a\", \"title\": \"A\"}").rules().hideUntilDependenciesComplete());
        assertEquals(Optional.of(true),
                quest("{\"id\": \"a\", \"title\": \"A\","
                        + " \"hideUntilDependenciesComplete\": true}").rules()
                        .hideUntilDependenciesComplete());
        assertEquals(Optional.of(false),
                quest("{\"id\": \"a\", \"title\": \"A\","
                        + " \"hideUntilDependenciesComplete\": false}").rules()
                        .hideUntilDependenciesComplete());
        assertFalse(quest("{\"id\": \"a\", \"title\": \"A\"}").rules().hideDependencyLines());
        assertTrue(quest("{\"id\": \"a\", \"title\": \"A\","
                + " \"hideDependencyLines\": true, \"hideTextUntilComplete\": true,"
                + " \"hideDetailsUntilStartable\": true, \"invisible\": true,"
                + " \"invisibleUntilTasks\": 2}").rules().hideTextUntilComplete());
    }

    @Test
    @DisplayName("chapter carries consume and hide defaults")
    void chapterDefaults() {
        var parsed = chapter("{\"id\": \"c\", \"title\": \"C\", \"defaultConsumeItems\": true,"
                + " \"defaultHideUntilDependenciesComplete\": true,"
                + " \"defaultHideUntilDependenciesVisible\": true}");
        assertTrue(parsed.defaultConsumeItems());
        assertTrue(parsed.rules().defaultHideUntilDependenciesComplete());
        assertTrue(parsed.rules().defaultHideUntilDependenciesVisible());
    }

    @Test
    @DisplayName("chapter defaultSequentialTasks and file defaultConsumeItems decode (XS)")
    void sequentialAndFileConsumeDefaults() {
        var chapter = chapter("{\"id\": \"c\", \"title\": \"C\","
                + " \"defaultSequentialTasks\": true}");
        assertTrue(chapter.rules().defaultSequentialTasks());
        assertFalse(chapter("{\"id\": \"c\", \"title\": \"C\"}").rules().defaultSequentialTasks());

        var settings = settings("{\"defaultConsumeItems\": true}");
        assertTrue(settings.defaultConsumeItems());
        assertFalse(settings("{\"defaultAutoClaim\": \"disabled\"}").defaultConsumeItems());
    }

    @Test
    @DisplayName("file settings carry auto-claim and team defaults")
    void fileSettings() {
        var parsed = settings("{\"defaultAutoClaim\": \"no_toast\", \"defaultTeamReward\": true,"
                + " \"detectionDelay\": 60}");
        assertEquals(RewardAutoClaim.NO_TOAST, parsed.defaultAutoClaim());
        assertTrue(parsed.defaultTeamReward());
        assertEquals(60, parsed.detectionDelay());
    }

    @Test
    @DisplayName("chapter subtitle and the book title decode (G16)")
    void chapterSubtitleAndBookTitleDecode() {
        // the legacy `chapter_subtitle` (a single-element lang array in ATM10) becomes the chapter's
        // `subtitle`: the one line under the chapter's name, drawn as the second line of its
        // sidebar row's hover. Absent stays absent, which is every chapter that never had one.
        var subtitled = chapter("{\"id\": \"c\", \"title\": \"C\","
                + " \"subtitle\": \"Five quests, no tricks\"}");
        assertEquals("Five quests, no tricks", subtitled.subtitle().orElseThrow().value());
        assertTrue(chapter("{\"id\": \"c\", \"title\": \"C\"}").subtitle().isEmpty(),
                "a chapter with no subtitle sends nothing on the wire");
        // And the legacy file `title` (lang `file.<id>.title`, "All The Mods 10" in ATM10) becomes the
        // index settings' `bookTitle`: what the header draws. Already a plain string, so the
        // tool's value decodes as written and translates through `book.title` instead.
        assertEquals("All The Mods 10",
                settings("{\"bookTitle\": \"All The Mods 10\"}").bookTitle());
        assertEquals("", settings("{}").bookTitle(),
                "absent means the client's own title");
    }

    @Test
    @DisplayName("quest disableRecipeMod keeps its tristate, and the file default decodes (T27)")
    void recipeViewerFlags() {
        // Absent is "no opinion" and the file's defaultDisableRecipeMod decides; false forces the
        // quest listed. Collapsing absent into false would pin the file default the moment a quest
        // is written — the same trap the hide flags' tristate closes.
        assertEquals(Optional.empty(),
                quest("{\"id\": \"a\", \"title\": \"A\"}").disableRecipeMod());
        assertEquals(Optional.of(true),
                quest("{\"id\": \"a\", \"title\": \"A\", \"disableRecipeMod\": true}")
                        .disableRecipeMod());
        assertEquals(Optional.of(false),
                quest("{\"id\": \"a\", \"title\": \"A\", \"disableRecipeMod\": false}")
                        .disableRecipeMod());

        // The resolution: the quest's own flag wins when set, otherwise the file decides, and the
        // viewers read the negation.
        assertTrue(quest("{\"id\": \"a\", \"title\": \"A\"}").showInRecipeMod(false),
                "a quest that says nothing follows a permissive file");
        assertFalse(quest("{\"id\": \"a\", \"title\": \"A\"}").showInRecipeMod(true),
                "a quest that says nothing follows a hiding file");
        assertFalse(quest("{\"id\": \"a\", \"title\": \"A\", \"disableRecipeMod\": true}")
                .showInRecipeMod(false), "an explicit hide wins over a permissive file");
        assertTrue(quest("{\"id\": \"a\", \"title\": \"A\", \"disableRecipeMod\": false}")
                .showInRecipeMod(true), "an explicit show opts out of a hiding file");

        assertTrue(settings("{\"defaultDisableRecipeMod\": true}").defaultDisableRecipeMod());
        assertFalse(settings("{\"defaultAutoClaim\": \"disabled\"}").defaultDisableRecipeMod());
    }

    @Test
    @DisplayName("file lock, pause, gui, book, grid and message flags decode (T26)")
    void fileBehaviourFlags() {
        // Absent is the default on every one, and the defaults are quiet: marks hidden unless
        // asked for, nothing hidden, the world unpaused, the book opening, no custom locked word.
        // (the legacy format draws an absent mark, so the tool writes an explicit true for packs that never
        // heard of the field — see the mapping page.)
        var absent = settings("{}");
        assertFalse(absent.showLockIcons(), "absent hides");
        assertFalse(absent.hideExcludedQuests());
        assertFalse(absent.pauseGame());
        assertFalse(absent.disableGui());
        assertFalse(absent.dropBookOnDeath());
        assertEquals(0.5, absent.gridScale());
        assertEquals("", absent.lockMessage());
        assertEquals(300, absent.emergencyItemsCooldown());
        assertTrue(absent.emergencyItems().isEmpty());

        var parsed = settings("{\"showLockIcons\": true, \"hideExcludedQuests\": true,"
                + " \"pauseGame\": true, \"disableGui\": true, \"dropBookOnDeath\": true,"
                + " \"gridScale\": 1.0, \"lockMessage\": \"Sealed\","
                + " \"emergencyItemsCooldown\": 60,"
                + " \"emergencyItems\": [{\"item\": \"minecraft:torch\", \"count\": 8}]}");
        assertTrue(parsed.showLockIcons(), "an explicit true draws every mark");
        assertTrue(parsed.hideExcludedQuests());
        assertTrue(parsed.pauseGame());
        assertTrue(parsed.disableGui());
        assertTrue(parsed.dropBookOnDeath());
        assertEquals(1.0, parsed.gridScale());
        assertEquals("Sealed", parsed.lockMessage());
        assertEquals(60, parsed.emergencyItemsCooldown());
        assertEquals(1, parsed.emergencyItems().size());
        assertEquals("minecraft:torch", parsed.emergencyItems().get(0).item().toString());
        assertEquals(8, parsed.emergencyItems().get(0).count());
    }

    @Test
    @DisplayName("file gridScale is bounded, like the editor it came from (T26)")
    void fileGridScaleIsBounded() {
        assertTrue(QuestSettings.CODEC.parse(JsonOps.INSTANCE,
                json("{\"gridScale\": 16}")).error().isPresent(),
                "twice the editor's own maximum is not a grid");
        assertTrue(QuestSettings.CODEC.parse(JsonOps.INSTANCE,
                json("{\"gridScale\": 0}")).error().isPresent(),
                "and neither is no grid at all");
        assertEquals(QuestSettingsExtra.GRID_SCALE_MIN,
                settings("{\"gridScale\": 0.03125}").gridScale(),
                "while the editor's own minimum reads as written");
    }

    @Test
    @DisplayName("quest hideLockIcon decodes, defaulting to deferring (T26)")
    void questHideLockIcon() {
        assertFalse(quest("{\"id\": \"a\", \"title\": \"A\"}").hideLockIcon(),
                "absent defers to the file, which is every file written before the field");
        assertTrue(quest("{\"id\": \"a\", \"title\": \"A\", \"hideLockIcon\": true}")
                .hideLockIcon());
    }

    @Test
    @DisplayName("chapter alwaysInvisible decodes, defaulting to shown (T30)")
    void chapterAlwaysInvisible() {
        assertFalse(chapter("{\"id\": \"c\", \"title\": \"C\"}").rules().alwaysInvisible(),
                "absent is shown, which is every file written before the field");
        assertTrue(chapter("{\"id\": \"c\", \"title\": \"C\", \"alwaysInvisible\": true}")
                .rules().alwaysInvisible());
    }

    @Test
    @DisplayName("chapter disableCanvasLod keeps its tristate, deferring to the book when absent")
    void chapterDisableCanvasLod() {
        // Absent is "no opinion" and the book's own flag decides; false forces the tiers
        // back on under a tierless book. Collapsing absent into false would pin the book
        // default the moment a chapter is written — the same trap the hide flags'
        // tristate closes.
        assertEquals(Optional.empty(),
                chapter("{\"id\": \"c\", \"title\": \"C\"}").rules().disableCanvasLod(),
                "absent defers, which is every file written before the field");
        assertEquals(Optional.of(true),
                chapter("{\"id\": \"c\", \"title\": \"C\", \"disableCanvasLod\": true}")
                        .rules().disableCanvasLod());
        assertEquals(Optional.of(false),
                chapter("{\"id\": \"c\", \"title\": \"C\", \"disableCanvasLod\": false}")
                        .rules().disableCanvasLod(),
                "an explicit false tiers under a tierless book");
    }

    @Test
    @DisplayName("tags decode on every quest object, defaulting to none (T30)")
    void tagsOnEveryObject() {
        assertTrue(quest("{\"id\": \"a\", \"title\": \"A\"}").tags().isEmpty());
        assertEquals(List.of("village", "early"),
                quest("{\"id\": \"a\", \"title\": \"A\", \"tags\": [\"village\", \"early\"]}").tags());
        assertEquals(List.of("village"),
                chapter("{\"id\": \"c\", \"title\": \"C\", \"tags\": [\"village\"]}").rules().tags());
        assertTrue(task("{\"type\": \"tenet:checkmark\", \"title\": \"x\"}").common().tags().isEmpty());
        assertEquals(List.of("village"),
                task("{\"type\": \"tenet:checkmark\", \"title\": \"x\", \"tags\": [\"village\"]}")
                        .common().tags());
        var reward = assertInstanceOf(ItemReward.class,
                reward("{\"type\": \"tenet:item\", \"item\": \"minecraft:stone\","
                        + " \"tags\": [\"village\"]}"));
        assertEquals(List.of("village"), reward.common().tags());
    }

    @Test
    @DisplayName("quest guidePage decodes, defaulting to none (T30)")
    void questGuidePage() {
        assertEquals("", quest("{\"id\": \"a\", \"title\": \"A\"}").guidePage(),
                "absent names no page, which is every file written before the field");
        assertEquals("my_pack:early_game",
                quest("{\"id\": \"a\", \"title\": \"A\", \"guidePage\": \"my_pack:early_game\"}")
                        .guidePage());
    }
}
