package dev.ellipog.tenet.quest;

import dev.ellipog.armature.api.data.DataProblem;
import dev.ellipog.armature.api.data.JsonDocument;
import dev.ellipog.armature.api.data.Problems;
import dev.ellipog.tenet.quest.loot.RewardTable;
import dev.ellipog.tenet.quest.reward.TableReward;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * What the validator says, and where it says it.
 *
 * <h2>The feature under test is the error message</h2>
 *
 * <p>Minecraft's codecs silently ignore fields they do not recognise, so a misspelled key produces
 * a quest that quietly does nothing and no explanation. The whole reason this validator exists is
 * to say which field is wrong, on which line, and — where the name is close to a real one — what
 * was probably meant. So most of these tests assert on the message text and its position, not on
 * a pass/fail verdict.
 */
@DisplayName("QuestValidator")
class QuestValidatorTest {

    @BeforeAll
    static void bootstrapMinecraft() {
        // The validator checks that a named item exists, which reads BuiltInRegistries.ITEM — and
        // that is empty until vanilla has registered its items. Without this, every item in every
        // fixture would be reported as missing, including minecraft:oak_log, and half these tests
        // would pass for entirely the wrong reason.
        MinecraftTestBootstrap.boot();
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    private static Problems validate(String json) {
        JsonDocument document = Fixtures.document("test.json", json);
        Problems problems = new Problems();
        QuestValidator.validate(document, problems);
        return problems;
    }

    private static DataProblem containing(Problems problems, String text) {
        return problems.all().stream()
                .filter(problem -> problem.message().contains(text))
                .findFirst()
                .orElseThrow(() -> new AssertionError("no problem mentioning '" + text + "'. Got:\n"
                        + problems.all().stream()
                                .map(DataProblem::render)
                                .collect(Collectors.joining("\n"))));
    }

    /**
     * Asserts the problem points at a JSON <b>value</b> — an item name, a task type, an id.
     *
     * <p>Computed from the fixture rather than hardcoded, so the assertions stay honest if the
     * fixture is reformatted, and still fail if the position drifts. {@code token} must include its
     * quotes.
     */
    private static void assertPointsAtValue(DataProblem problem, String json, String token) {
        withToken(json, token, (line, column) -> {
            assertEquals(line, problem.line(), "line, for value " + token);
            assertEquals(column, problem.column(), "column, for value " + token);
        });
    }

    /**
     * Asserts the problem points at the value of a <b>key</b> — the text after the colon.
     *
     * <p>Separate from {@link #assertPointsAtValue} because the two are genuinely different
     * positions, and conflating them was a real bug here: this method was originally the only one,
     * and it computed every token as though it were a key. Three of the four call sites passed
     * values, so three assertions were off by the token's own length and failed. Keeping the two
     * named apart means a call site says which it expects.
     */
    private static void assertPointsAtKeyValue(DataProblem problem, String json, String key) {
        withToken(json, key, (line, column) -> {
            assertEquals(line, problem.line(), "line, for key " + key);
            // key, then ':', then ' ' — so the value begins three characters later, 1-based.
            assertEquals(column + key.length() + 2, problem.column(), "column, for key " + key);
        });
    }

    /** Finds {@code token} and hands its 1-based line and column to {@code check}. */
    private static void withToken(String json, String token, java.util.function.BiConsumer<Integer, Integer> check) {
        String[] lines = json.split("\n", -1);
        for (int i = 0; i < lines.length; i++) {
            int at = lines[i].indexOf(token);
            if (at >= 0) {
                check.accept(i + 1, at + 1);
                return;
            }
        }
        throw new AssertionError("'" + token + "' does not appear in the fixture");
    }

    // ------------------------------------------------------------------
    // The happy path
    // ------------------------------------------------------------------

    @Test
    @DisplayName("a valid file produces nothing at all")
    void cleanFile() {
        Problems problems = validate(Fixtures.file(
                Fixtures.q("a").build(),
                Fixtures.q("b").dependsOn("a").build()));

        assertTrue(problems.isEmpty(), "expected no problems, got:\n"
                + problems.all().stream().map(DataProblem::render).collect(Collectors.joining("\n")));
    }

    @Test
    @DisplayName("a custom task or reward nothing provides is a warning at its id, not an error")
    void unregisteredCustomHandler() {
        // The two reasons a custom id has no handler -- a typo, or a mod the player has not installed --
        // are the same sentence from inside the game, and both leave the task at zero progress forever
        // with nothing on screen saying why. So the file is valid, and this says which fact it found.
        String quest = """
                {"id": "a", "title": "A", "tasks": [
                  {"type": "tenet:custom", "id": "test:no_such_handler", "value": 1}
                ], "rewards": [
                  {"type": "tenet:custom", "id": "test:no_such_handler"}
                ]}""";

        Problems problems = validate(Fixtures.file(quest));

        assertFalse(problems.hasErrors(),
                "an uninstalled handler is not a broken file:\n"
                        + problems.all().stream().map(DataProblem::render).collect(Collectors.joining("\n")));
        DataProblem task = containing(problems, "nothing is registered for \"test:no_such_handler\"");
        assertPointsAtValue(task, Fixtures.file(quest), "\"test:no_such_handler\"");
        assertTrue(problems.warningCount() >= 2,
                "both halves of the quest say it: " + problems.warningCount() + " warning(s)");

        // And a handler that *is* registered is silent -- the check is against the registry, not a list of
        // known names.
        dev.ellipog.tenet.quest.task.CustomTask.CustomTasks.register("test:provided", (t, context) -> 1);
        Problems provided = validate(Fixtures.file("""
                {"id": "a", "title": "A", "tasks": [
                  {"type": "tenet:custom", "id": "test:provided", "value": 1}
                ]}"""));
        assertTrue(provided.isEmpty(), "a provided handler is nothing to report, got:\n"
                + provided.all().stream().map(DataProblem::render).collect(Collectors.joining("\n")));
    }

    @Test
    @DisplayName("a currency reward with no economy behind it warns, and goes quiet with one")
    void currencyWithoutAProviderWarns() {
        // The same tolerance as an uninstalled custom handler: a missing economy mod is often
        // temporary, so the file is valid and the quest still completes -- this says which fact
        // it found. The hook is one global, so the test puts back whatever it found there.
        java.util.Optional<dev.ellipog.tenet.quest.reward.CurrencyReward.CurrencyProvider> before =
                dev.ellipog.tenet.quest.reward.CurrencyReward.CurrencyProviders.provider();
        dev.ellipog.tenet.quest.reward.CurrencyReward.CurrencyProviders.clear();
        try {
            Problems problems = validate(Fixtures.file("""
                    {"id": "a", "title": "A", "rewards": [
                      {"type": "tenet:currency", "amount": 50}
                    ]}"""));
            assertFalse(problems.hasErrors(),
                    "a missing economy is not a broken file:\n"
                            + problems.all().stream().map(DataProblem::render)
                                    .collect(Collectors.joining("\n")));
            containing(problems, "no currency provider registered");
        }
        finally {
            before.ifPresent(
                    dev.ellipog.tenet.quest.reward.CurrencyReward.CurrencyProviders::setActive);
        }
        assertEquals(before.isPresent(),
                dev.ellipog.tenet.quest.reward.CurrencyReward.CurrencyProviders.provider().isPresent(),
                "the hook is as it was found");

        // And an economy that *is* installed is silent.
        dev.ellipog.tenet.quest.reward.CurrencyReward.CurrencyProviders.setActive(
                new dev.ellipog.tenet.quest.reward.CurrencyReward.CurrencyProvider() {
                    @Override
                    public String name() {
                        return "test-bank";
                    }

                    @Override
                    public void give(net.minecraft.server.level.ServerPlayer player, int amount) {
                    }
                });
        try {
            Problems provided = validate(Fixtures.file("""
                    {"id": "a", "title": "A", "rewards": [
                      {"type": "tenet:currency", "amount": 50}
                    ]}"""));
            assertTrue(provided.isEmpty(), "a paid economy is nothing to report, got:\n"
                    + provided.all().stream().map(DataProblem::render).collect(Collectors.joining("\n")));
        }
        finally {
            dev.ellipog.tenet.quest.reward.CurrencyReward.CurrencyProviders.clear();
            before.ifPresent(
                    dev.ellipog.tenet.quest.reward.CurrencyReward.CurrencyProviders::setActive);
        }
    }

    // ------------------------------------------------------------------
    // The check that saves the most time
    // ------------------------------------------------------------------

    @Test
    @DisplayName("an unknown field is reported on its own line, with the right name suggested")
    void unknownFieldSuggestsTheRightName() {
        String json = """
                {
                  "version": 1,
                  "chapterGroups": [
                    {
                      "id": "group",
                      "title": "Group",
                      "chapters": [
                        {
                          "id": "chapter",
                          "titl": "A typo",
                          "quests": []
                        }
                      ]
                    }
                  ]
                }
                """;

        Problems problems = validate(json);
        DataProblem problem = containing(problems, "unknown field \"titl\"");

        assertTrue(problem.message().contains("did you mean \"title\"?"),
                "should suggest the real field; got: " + problem.message());
        assertPointsAtKeyValue(problem, json, "\"titl\"");
    }

    @Test
    @DisplayName("and lists every field that would have been allowed")
    void unknownFieldListsTheAlternatives() {
        Problems problems = validate(Fixtures.file(Fixtures.q("a").build()));
        // A misspelling of "title" on the quest, to get the quest's own field list.
        Problems withTypo = validate(Fixtures.file(
                "{\"id\": \"a\", \"titl\": \"x\", \"tasks\": []}"));

        assertTrue(containing(withTypo, "unknown field \"titl\"").message().contains("valid fields here:"));
        assertTrue(withTypo.errorCount() > 0);
        assertFalse(problems.hasErrors(), "the unmodified fixture should be clean");
    }

    @Test
    @DisplayName("a comment attempt is named as one, rather than as an unknown field")
    void commentIsNamedAsAComment() {
        // JSON has no comments, so `//` is simply an unknown field -- and "unknown field //"
        // leaves the author wondering what it should have been called.
        String json = """
                {
                  "version": 1,
                  "//": "this is not a comment",
                  "chapterGroups": []
                }
                """;

        DataProblem problem = containing(validate(json), "looks like a comment");
        assertTrue(problem.message().contains("JSON has none"), "got: " + problem.message());
    }

    // ------------------------------------------------------------------
    // Types
    // ------------------------------------------------------------------

    @Test
    @DisplayName("an unknown task type lists the ones that exist")
    void unknownTaskType() {
        String json = Fixtures.file(
                "{\"id\": \"a\", \"title\": \"a\", \"tasks\": [ {\"type\": \"tenet:items\"} ]}");

        DataProblem problem = containing(validate(json), "unknown quest task type");

        assertTrue(problem.message().contains("tenet:item"), "should list tenet:item: " + problem.message());
        assertTrue(problem.message().contains("tenet:checkmark"),
                "should list the other type too: " + problem.message());
        assertPointsAtValue(problem, json, "\"tenet:items\"");
    }

    @Test
    @DisplayName("a field that no task type knows is reported, but one that another type knows is not")
    void taskFieldsAreCheckedAgainstEveryType() {
        // Deliberately the union of every type's fields rather than just this type's: a task may
        // be changed from one type to another, and flagging the leftover fields of the old type
        // on every file would be noise. What must still be caught is a field nothing understands.
        Problems typo = validate(Fixtures.file(
                "{\"id\": \"a\", \"title\": \"a\", \"tasks\": "
                        + "[ {\"type\": \"tenet:item\", \"item\": \"minecraft:oak_log\", \"counnt\": 4} ]}"));
        assertTrue(containing(typo, "unknown field \"counnt\"").message().contains("did you mean \"count\"?"));

        Problems crossType = validate(Fixtures.file(
                "{\"id\": \"a\", \"title\": \"a\", \"tasks\": "
                        + "[ {\"type\": \"tenet:checkmark\", \"title\": \"t\", \"count\": 4} ]}"));
        assertFalse(crossType.errorCount() > 0,
                "count belongs to tenet:item, so it is a known field overall. Got:\n"
                        + crossType.all().stream().map(DataProblem::render).collect(Collectors.joining("\n")));
    }

    // ------------------------------------------------------------------
    // Items
    // ------------------------------------------------------------------

    @Test
    @DisplayName("an item that does not exist warns, names it, and keeps the quest loadable")
    void unknownItem() {
        String json = Fixtures.file(
                "{\"id\": \"a\", \"title\": \"a\", \"tasks\": "
                        + "[ {\"type\": \"tenet:item\", \"item\": \"minecraft:not_a_real_item\"} ]}");

        Problems problems = validate(json);
        DataProblem problem = containing(problems, "there is no item");

        assertTrue(problem.message().contains("minecraft:not_a_real_item"), "should name it");
        assertTrue(problem.message().contains("check the spelling"),
                "a minecraft: item missing is a typo, and the message should say so: " + problem.message());
        assertPointsAtValue(problem, json, "\"minecraft:not_a_real_item\"");
        // Reversed deliberately, and this is where the reversal is pinned: a missing mod used to be an
        // error, and an error skips the file -- so a pack whose mod had been removed lost the whole
        // quest, node and all, with the id still sitting in the file. The id is kept, the quest loads,
        // and the row draws it as missing.
        assertTrue(problem.severity() == DataProblem.Severity.WARNING,
                "a missing item is kept, not fatal");
        assertFalse(problems.hasErrors(), "so the file still loads");
    }

    @Test
    @DisplayName("an item from a mod that is not installed says which mod")
    void unknownItemNamesTheMod() {
        String json = Fixtures.file(
                "{\"id\": \"a\", \"title\": \"a\", \"tasks\": "
                        + "[ {\"type\": \"tenet:item\", \"item\": \"someothermod:widget\"} ]}");

        DataProblem problem = containing(validate(json), "there is no item");

        assertTrue(problem.message().contains("someothermod"),
                "the fix is usually installing the mod, so name it: " + problem.message());
    }

    @Test
    @DisplayName("a real item is accepted, which is what makes the check worth having")
    void knownItemIsFine() {
        Problems problems = validate(Fixtures.file(
                "{\"id\": \"a\", \"title\": \"a\", \"tasks\": "
                        + "[ {\"type\": \"tenet:item\", \"item\": \"minecraft:oak_log\", \"count\": 8} ]}"));
        assertFalse(problems.hasErrors(), "minecraft:oak_log exists, so there is nothing to report");
    }

    @Test
    @DisplayName("a block that does not exist warns, names it, and keeps the quest loadable")
    void unknownBlock() {
        String json = Fixtures.file(
                "{\"id\": \"a\", \"title\": \"a\", \"tasks\": "
                        + "[ {\"type\": \"tenet:break\", \"block\": \"minecraft:not_a_real_block\","
                        + " \"value\": 4} ]}");

        Problems problems = validate(json);
        DataProblem problem = containing(problems, "there is no block");

        assertTrue(problem.message().contains("minecraft:not_a_real_block"), "should name it");
        assertTrue(problem.message().contains("check the spelling"),
                "a minecraft: block missing is a typo, and the message should say so: "
                        + problem.message());
        assertPointsAtValue(problem, json, "\"minecraft:not_a_real_block\"");
        assertTrue(problem.severity() == DataProblem.Severity.WARNING,
                "a missing block is kept, not fatal — the same reversal as a missing item");
        assertFalse(problems.hasErrors(), "so the file still loads");
    }

    @Test
    @DisplayName("a real block and a real state predicate are accepted")
    void knownBlockAndStateAreFine() {
        Problems problems = validate(Fixtures.file(
                "{\"id\": \"a\", \"title\": \"a\", \"tasks\": "
                        + "[ {\"type\": \"tenet:break\", \"block\": \"minecraft:stone\", \"value\": 4},"
                        + " {\"type\": \"tenet:break\","
                        + " \"blockState\": \"minecraft:wheat[age=7]\", \"value\": 4} ]}"));
        assertFalse(problems.hasErrors(), "stone exists and the predicate parses");
    }

    @Test
    @DisplayName("a block state predicate that does not parse is refused at its line")
    void unparsableBlockState() {
        String json = Fixtures.file(
                "{\"id\": \"a\", \"title\": \"a\", \"tasks\": "
                        + "[ {\"type\": \"tenet:break\", \"block\": \"minecraft:wheat\","
                        + " \"blockState\": \"minecraft:wheat[age=sideways]\", \"value\": 4} ]}");

        Problems problems = validate(json);
        DataProblem problem = containing(problems, "is not a block state predicate");

        // An error rather than a warning: the engine fail-closes on an unparsable predicate,
        // so the task would sit at zero forever with nothing on screen saying why.
        assertTrue(problem.severity() == DataProblem.Severity.ERROR, messages(problems));
        assertPointsAtValue(problem, json, "\"minecraft:wheat[age=sideways]\"");
    }

    @Test
    @DisplayName("a break task naming both a block and a tag warns that the tag wins")
    void blockAndTagBothSetWarns() {
        String json = Fixtures.file(
                "{\"id\": \"a\", \"title\": \"a\", \"tasks\": "
                        + "[ {\"type\": \"tenet:break\", \"block\": \"minecraft:stone\","
                        + " \"blockTag\": \"minecraft:logs\", \"value\": 4} ]}");

        Problems problems = validate(json);
        DataProblem problem = containing(problems, "takes precedence");

        assertTrue(problem.severity() == DataProblem.Severity.WARNING, messages(problems));
        assertPointsAtValue(problem, json, "\"minecraft:stone\"");
        assertFalse(problems.hasErrors(), "so the file still loads");
    }

    @Test
    @DisplayName("a break task with no selector warns that it counts every break")
    void breakWithNoSelectorWarns() {
        String json = Fixtures.file(
                "{\"id\": \"a\", \"title\": \"a\", \"tasks\": "
                        + "[ {\"type\": \"tenet:break\", \"value\": 4} ]}");

        Problems problems = validate(json);
        containing(problems, "counts every break");

        assertFalse(problems.hasErrors(), "so the file still loads");
    }

    // ------------------------------------------------------------------
    // Ids and enums
    // ------------------------------------------------------------------

    @Test
    @DisplayName("an id with an uppercase letter is rejected, and says which character")
    void uppercaseId() {
        String json = Fixtures.file("{\"id\": \"NOT_AN_ID\", \"title\": \"a\", \"tasks\": []}");

        DataProblem problem = containing(validate(json), "may only contain lowercase");
        assertTrue(problem.message().contains("'N'"), "should point at the offending character: "
                + problem.message());
        assertPointsAtValue(problem, json, "\"NOT_AN_ID\"");
    }

    @Test
    @DisplayName("an alias may use uppercase, because converted packs arrive with it")
    void uppercaseAliasIsAccepted() {
        // Ids stay lowercase — the lookup normalises, but the files do not, so a mixed-case id is
        // still an authoring error. Aliases accept uppercase because legacy ids are uppercase
        // hexadecimal, and refusing them would refuse the converted pack.
        String json = Fixtures.file(
                "{\"id\": \"a\", \"title\": \"a\", \"aliases\": [\"OldName\"], \"tasks\": []}");

        Problems problems = validate(json);
        assertTrue(problems.all().stream().noneMatch(problem -> problem.message().contains("alias")),
                "an uppercase alias should not be reported, got:\n"
                        + problems.all().stream().map(DataProblem::render)
                                .collect(Collectors.joining("\n")));
    }

    @Test
    @DisplayName("a dependency in uppercase is still an authoring error, even though it resolves")
    void uppercaseDependencyIsRejected() {
        // The split the plan asks for: the validator keeps files uniform (lowercase), the lookup
        // resolves anyway. A reference somebody wrote correctly in another case is not a failure
        // the legacy format has ever had — but this file is still asked to spell it the uniform way.
        String json = Fixtures.file(
                "{\"id\": \"a\", \"title\": \"a\", \"tasks\": []},"
                        + "{\"id\": \"b\", \"title\": \"b\", \"dependsOn\": [\"A\"], \"tasks\": []}");

        containing(validate(json), "is not a valid quest id");
    }

    @Test
    @DisplayName("an enum value that is close to a real one lists the options")
    void badEnumValue() {
        Problems problems = validate(Fixtures.file(
                "{\"id\": \"a\", \"title\": \"a\", \"prerequisiteMode\": \"all_complete\", \"tasks\": []}"));

        DataProblem problem = containing(problems, "is not one of");
        assertTrue(problem.message().contains("all_completed"), "should list the real value: "
                + problem.message());
    }

    // ------------------------------------------------------------------
    // Warnings
    // ------------------------------------------------------------------

    @Test
    @DisplayName("a cooldown on a quest that is not repeatable warns rather than failing")
    void cooldownWithoutRepeatWarns() {        // Not an error: it does nothing, but it is not wrong. A validator that fails a working
        // file is a validator people learn to ignore.
        Problems problems = validate(Fixtures.file(
                Fixtures.q("a").repeatCooldownTicks(600).build()));

        assertFalse(problems.hasErrors(), "a no-op cooldown is not fatal");
        assertTrue(containing(problems, "only means something on a repeatable quest").severity()
                        == DataProblem.Severity.WARNING,
                "it should be a warning");
    }

    @Test
    @DisplayName("a quest flagged optional is clean")
    void optionalQuestIsClean() {
        // The engine reads it, so a typo here would silently gate — the drift the closed-set
        // checks prevent. Accepted as a boolean like its neighbouring flags.
        Problems problems = validate(Fixtures.file(Fixtures.q("a").optional(true).build()));

        assertTrue(problems.all().stream().noneMatch(problem -> problem.message().contains("optional")),
                "an optional flag should not be reported, got:\n"
                        + problems.all().stream().map(DataProblem::render)
                                .collect(Collectors.joining("\n")));
    }

    @Test
    @DisplayName("a quest and chapter flagged for flexible progress are clean")
    void flexibleProgressIsClean() {
        Problems problems = validate(Fixtures.fileWithChapter(
                "\"defaultFlexibleProgress\": true,",
                Fixtures.q("a").flexibleProgress(true).build()));

        assertTrue(problems.all().stream()
                        .noneMatch(problem -> problem.message().contains("lexible")),
                "flexible flags should not be reported, got:\n"
                        + problems.all().stream().map(DataProblem::render)
                                .collect(Collectors.joining("\n")));
    }

    @Test
    @DisplayName("a quest that opts out of recipe viewers is clean, in either state")
    void disableRecipeModIsClean() {
        // Accepted as a boolean like its neighbouring presentation flags; absent is the third
        // state and needs no check at all.
        for (String json : List.of(
                "{\"id\": \"a\", \"title\": \"a\", \"disableRecipeMod\": true}",
                "{\"id\": \"a\", \"title\": \"a\", \"disableRecipeMod\": false}")) {
            Problems problems = validate(Fixtures.file(json));

            assertTrue(problems.all().stream()
                            .noneMatch(problem -> problem.message().contains("isableRecipeMod")),
                    "a disableRecipeMod flag should not be reported, got:\n"
                            + problems.all().stream().map(DataProblem::render)
                                    .collect(Collectors.joining("\n")));
        }
    }

    @Test
    @DisplayName("a quest that hides its lock mark is clean, in either state (T26)")
    void hideLockIconIsClean() {
        // Accepted as a boolean like its neighbouring presentation flags.
        for (String json : List.of(
                "{\"id\": \"a\", \"title\": \"a\", \"hideLockIcon\": true}",
                "{\"id\": \"a\", \"title\": \"a\", \"hideLockIcon\": false}")) {
            Problems problems = validate(Fixtures.file(json));

            assertTrue(problems.all().stream()
                            .noneMatch(problem -> problem.message().contains("ideLockIcon")),
                    "a hideLockIcon flag should not be reported, got:\n"
                            + problems.all().stream().map(DataProblem::render)
                                    .collect(Collectors.joining("\n")));
        }
    }

    @Test
    @DisplayName("well-formed tags are clean on every quest object (T30)")
    void tagsAreClean() {
        Problems problems = validate(Fixtures.fileWithChapter(
                "\"tags\": [\"village\"],",
                "{\"id\": \"a\", \"title\": \"a\", \"tags\": [\"early\", \"village\"],"
                        + " \"guidePage\": \"my_pack:early_game\","
                        + " \"tasks\": [{\"type\": \"tenet:checkmark\", \"tags\": [\"t1\"]}],"
                        + " \"rewards\": [{\"type\": \"tenet:xp\", \"levels\": true,"
                        + " \"amount\": 1, \"tags\": [\"r1\"]}]}"));

        assertTrue(problems.all().stream()
                        .noneMatch(problem -> problem.message().contains("tag '")
                                || problem.message().contains("a tag must be")
                                || problem.message().contains("uide page")),
                "well-formed tags and a guide page should not be reported, got:\n"
                        + problems.all().stream().map(DataProblem::render)
                                .collect(Collectors.joining("\n")));
    }

    @Test
    @DisplayName("a tag outside the id charset is refused (T30)")
    void badTagsAreRefused() {
        // Uppercase and a dash: the rule is an id's rule, so both fail it.
        for (String tag : List.of("Village", "early-game")) {
            Problems problems = validate(Fixtures.file(
                    "{\"id\": \"a\", \"title\": \"a\", \"tags\": [\"" + tag + "\"]}"));

            DataProblem problem = containing(problems, "'" + tag + "'");
            assertTrue(problem.message().contains("tag") && problem.message().contains("lowercase"),
                    "the refusal names the rule: " + problem.message());
        }
        // And an empty string is refused by the list read itself, before the pattern is asked.
        containing(validate(Fixtures.file("{\"id\": \"a\", \"title\": \"a\", \"tags\": [\"\"]}")),
                "a tag may not be empty");
    }

    @Test
    @DisplayName("a blank guide page is refused (T30)")
    void blankGuidePageIsRefused() {
        Problems problems = validate(Fixtures.file(
                "{\"id\": \"a\", \"title\": \"a\", \"guidePage\": \"  \"}"));

        containing(problems, "a guide page may not be empty");
    }

    @Test
    @DisplayName("an always-invisible chapter is clean, in either state (T30)")
    void alwaysInvisibleIsClean() {
        for (String json : List.of(
                Fixtures.fileWithChapter("\"alwaysInvisible\": true,", Fixtures.q("a").build()),
                Fixtures.fileWithChapter("", Fixtures.q("a").build()))) {
            Problems problems = validate(json);

            assertTrue(problems.all().stream()
                            .noneMatch(problem -> problem.message().contains("lwaysInvisible")),
                    "an alwaysInvisible flag should not be reported, got:\n"
                            + problems.all().stream().map(DataProblem::render)
                                    .collect(Collectors.joining("\n")));
        }
    }

    @Test
    @DisplayName("a chapter LOD answer is clean, in every state it can be written")
    void disableCanvasLodIsClean() {
        for (String json : List.of(
                Fixtures.fileWithChapter("\"disableCanvasLod\": true,", Fixtures.q("a").build()),
                Fixtures.fileWithChapter("\"disableCanvasLod\": false,", Fixtures.q("a").build()),
                Fixtures.fileWithChapter("", Fixtures.q("a").build()))) {
            Problems problems = validate(json);

            assertTrue(problems.all().stream()
                            .noneMatch(problem -> problem.message().contains("isableCanvasLod")),
                    "a disableCanvasLod flag should not be reported, got:\n"
                            + problems.all().stream().map(DataProblem::render)
                                    .collect(Collectors.joining("\n")));
        }
    }

    @Test
    @DisplayName("a positive report is not an error, so it does not stop the file loading")
    void warningsDoNotBlockALoad() {
        Problems problems = validate(Fixtures.file(Fixtures.q("a").build()));
        assertFalse(problems.hasErrors());
        assertFalse(problems.hasErrorsIn("test.json"), "no errors means nothing to skip");
    }

    // ------------------------------------------------------------------
    // Blank lines, which are paragraphs and not mistakes
    // ------------------------------------------------------------------

    @Test
    @DisplayName("a blank line in a description is a paragraph break, not a mistake")
    void blankParagraphsAreDeliberate() {
        // Found by the shipped questlines, which warn on every deliberate blank line in them. Each
        // entry of a description is drawn as its own paragraph, so an empty entry is the only way an
        // author can write a line break -- and warning about it is the worst shape a check can take:
        // not wrong about the fact, wrong about whether the fact is a problem. A warning like that
        // teaches an author to skim output, which costs the warnings that matter.
        Problems problems = validate(file(
                "{\"id\": \"a\", \"title\": \"a\", \"description\": [\"one\", \"\", \"two\"]}"));

        assertFalse(messages(problems).contains("this text is empty"),
                "a blank paragraph should not warn, got:" + messages(problems));
    }

    @Test
    @DisplayName("a description that is all blank paragraphs still warns, because nothing shows")
    void anAllBlankDescriptionWarns() {
        // The question moves from each paragraph to the whole list. A description of nothing but
        // blank lines draws nothing at all, which is worth saying once.
        Problems problems = validate(file(
                "{\"id\": \"a\", \"title\": \"a\", \"description\": [\"\", \"  \"]}"));

        assertTrue(messages(problems).contains("every paragraph here is empty"),
                "expected a warning about the whole list, got:" + messages(problems));
    }

    @Test
    @DisplayName("an empty title still warns, since an empty title is a quest with no name")
    void anEmptyTitleStillWarns() {
        // The distinction the parameter exists for. Blank is meaningful inside a list of paragraphs
        // and meaningless in a title.
        Problems problems = validate(
                "{\"version\": 1, \"chapterGroups\": [{\"id\": \"g\", \"title\": \"G\", "
                        + "\"chapters\": [{\"id\": \"c\", \"title\": \"\", \"quests\": []}]}]}");

        assertTrue(messages(problems).contains("this text is empty"),
                "an empty chapter title should still warn, got:" + messages(problems));
    }

    // ------------------------------------------------------------------
    // A group's description, where a list of paragraphs is the natural thing to write
    // ------------------------------------------------------------------

    @Test
    @DisplayName("a chapter group description accepts a list of paragraphs, because a chapter's does")
    void groupDescriptionAcceptsAList() {
        // The inconsistency that a second shipped questline found by being written the obvious way.
        // `ChapterGroup.description` was a single Optional<QuestText> while a chapter's and a quest's
        // were both lists -- so an array of lines failed to decode, and the failure arrived as a codec
        // message at line 1 column 1 saying the format had rejected the file.
        //
        // That is the exact message this whole validator exists to prevent: it names the wrong place,
        // about a field the validator had never been taught to look at, so it stayed silent and let the
        // codec speak from the root of the file.
        Problems problems = validate("""
                {"version": 1, "chapterGroups": [{
                   "id": "g", "title": "G",
                   "description": ["a line", "", "another line"],
                   "chapters": [{"id": "c", "title": "C", "quests": [
                     {"id": "q", "title": "q", "tasks": [{"type": "tenet:checkmark", "title": "t"}]}
                   ]}]
                }]}""");

        assertTrue(problems.isEmpty(), "a group description as a list should be clean, got:"
                + messages(problems));
    }

    @Test
    @DisplayName("and a bare string still works, so an existing file is not broken by the change")
    void groupDescriptionAcceptsAString() {
        // The union is deliberate rather than lazy. A one-line group description written as a string is
        // not a mistake worth failing a file over, so both spellings work and keep working.
        Problems problems = validate("""
                {"version": 1, "chapterGroups": [{
                   "id": "g", "title": "G",
                   "description": "one line",
                   "chapters": [{"id": "c", "title": "C", "quests": [
                     {"id": "q", "title": "q", "tasks": [{"type": "tenet:checkmark", "title": "t"}]}
                   ]}]
                }]}""");

        assertTrue(problems.isEmpty(), "a one-line group description should still load, got:"
                + messages(problems));
    }

    // ------------------------------------------------------------------
    // A type's own required fields, which the field-name checks cannot see
    // ------------------------------------------------------------------

    /** A quest document on its own, the way the loader reads one. */
    private static Problems validateQuest(String json) {
        JsonDocument document = Fixtures.document("quest.json", json);
        Problems problems = new Problems();
        QuestValidator.validateQuestDocument(document, problems);
        return problems;
    }

    @Test
    @DisplayName("an item task with no \"item\" is an error -- this is the one the loader used to catch")
    void itemTaskNeedsItsItem() {
        // The report that made this a test: the picker's clear row deleted "item" from a task, the
        // field-name checks saw nothing wrong (an absent field is not an unknown one), the save was
        // allowed, and the next load dropped the quest from the tree -- the node simply vanished.
        // The codec always knew. Only the loader ever asked it, and only after the write.
        Problems problems = validateQuest("""
                {"id": "one", "title": "One",
                 "tasks": [{"type": "tenet:item", "count": 1}]}""");

        DataProblem problem = containing(problems, "No key item");
        assertTrue(problem.severity() == DataProblem.Severity.ERROR,
                "an unloadable file is an error, not a warning");
        assertTrue(problem.message().contains("tenet:item"),
                "and it says which type the fields failed to make, got: " + problem.message());
    }

    @Test
    @DisplayName("a reward's own codec gets the last word too")
    void itemRewardNeedsItsItem() {
        Problems problems = validateQuest("""
                {"id": "one", "title": "One",
                 "rewards": [{"type": "tenet:item", "count": 1}]}""");

        assertTrue(containing(problems, "No key item").severity() == DataProblem.Severity.ERROR,
                "the reward half has the same gap and the same fix");
    }

    @Test
    @DisplayName("an unknown condition type lists the condition types, and only those")
    void unknownConditionTypeIsReported() {
        // The third registry, asked the same question as the other two. The listing is the one place
        // the difference shows: a condition type name that silently fell back to the task registry
        // would suggest tenet:checkmark to an author who wrote a condition.
        Problems problems = validateQuest("""
                {"id": "one", "title": "One",
                 "tasks": [{"type": "tenet:checkmark", "title": "x",
                            "conditions": [{"type": "tenet:no_such"}]}]}""");

        DataProblem problem = containing(problems, "unknown quest condition type \"tenet:no_such\"");
        assertTrue(problem.message().contains("tenet:party_size"),
                "the known list must be the condition registry, got: " + problem.message());
        assertFalse(problem.message().contains("tenet:checkmark"),
                "and not the task registry, got: " + problem.message());
    }

    @Test
    @DisplayName("a condition field no condition type knows is reported, with a suggestion")
    void unknownConditionFieldIsReported() {
        Problems problems = validateQuest("""
                {"id": "one", "title": "One",
                 "tasks": [{"type": "tenet:checkmark", "title": "x",
                            "conditions": [{"type": "tenet:stage", "stag": "pack:x"}]}]}""");

        DataProblem problem = containing(problems, "unknown field \"stag\"");
        assertTrue(problem.message().contains("did you mean \"stage\"?"),
                "a one-letter slip is the case this catches, got: " + problem.message());
    }

    @Test
    @DisplayName("a condition missing its required field is an error naming the condition type")
    void conditionNeedsItsOwnFields() {
        // The nested half of the item-task gap: the field names are only names, and whether they make a
        // value is the condition's own codec's question.
        Problems problems = validateQuest("""
                {"id": "one", "title": "One",
                 "tasks": [{"type": "tenet:checkmark", "title": "x",
                            "conditions": [{"type": "tenet:stage"}]}]}""");

        DataProblem problem = containing(problems, "No key stage");
        assertTrue(problem.severity() == DataProblem.Severity.ERROR,
                "a condition the codec cannot read would drop the quest from the tree at the next load");
        assertTrue(problem.message().contains("tenet:stage"),
                "and it says which condition type the fields failed to make, got: " + problem.message());
    }

    @Test
    @DisplayName("one broken condition is one message, not the task's codec repeating it")
    void aBrokenConditionIsReportedOnce() {
        // The enclosing task's codec decodes the nested list too, so without the skip in checkTask the
        // same root cause arrives twice -- once precisely, once wrapped in the task's name. The class
        // comment's rule is one mistake, one message; this is what holds it for a nested list.
        Problems problems = validateQuest("""
                {"id": "one", "title": "One",
                 "tasks": [{"type": "tenet:checkmark", "title": "x",
                            "conditions": [{"type": "tenet:stage"}]}]}""");

        long aboutTheCondition = problems.all().stream()
                .filter(problem -> problem.message().contains("No key stage"))
                .count();
        assertEquals(1, aboutTheCondition,
                "the condition's own codec reports it; the task's backstop must not repeat it. Got:\n"
                        + messages(problems));
    }

    @Test
    @DisplayName("an item condition's item is checked where the condition stands")
    void itemConditionChecksItsItem() {
        // The "item" convention check, one level down: an item condition with an id this build does not
        // have is a warning at the condition's own line, and the quest still loads.
        Problems problems = validateQuest("""
                {"id": "one", "title": "One",
                 "tasks": [{"type": "tenet:checkmark", "title": "x",
                            "conditions": [{"type": "tenet:item", "item": "someothermod:widget"}]}]}""");

        assertFalse(problems.hasErrors(),
                "a missing item is the warning it is everywhere else, got: " + messages(problems));
        containing(problems, "there is no item someothermod:widget");
    }

    @Test
    @DisplayName("a conditions list that is not a list is refused on its own line")
    void conditionsMustBeAList() {
        Problems problems = validateQuest("""
                {"id": "one", "title": "One",
                 "tasks": [{"type": "tenet:checkmark", "title": "x", "conditions": 5}]}""");

        assertTrue(containing(problems, "expected a list").severity() == DataProblem.Severity.ERROR,
                "a number where a list belongs must not be skipped, got: " + messages(problems));
    }

    @Test
    @DisplayName("a reward inside a reward table cannot carry conditions, and the error says where they go")
    void tableEntryConditionsAreRefused() {
        // The drift this closes: an entry's reward decodes as an ordinary reward, so it can carry
        // `conditions` -- but `TableReward.grantAll` pays entries directly, with no player to ask, so
        // the field was validated and then ignored. Refused rather than honoured: a gated entry inside
        // a roll would be silently lost, the opposite of the promise an unmet reward's conditions carry.
        String table = """
                {"entries": [
                  { "weight": 1, "reward": { "type": "tenet:item", "item": "minecraft:gold_ingot",
                      "conditions": [ { "type": "tenet:stage", "stage": "my_pack:marked" } ] } }
                ]}""";
        JsonDocument document = Fixtures.document("loot.json", table);
        Problems problems = new Problems();
        QuestValidator.validateRewardTableDocument(document, problems);

        DataProblem problem = containing(problems, "cannot carry \"conditions\"");
        assertTrue(problem.severity() == DataProblem.Severity.ERROR,
                "a field the engine would ignore must not load quietly");
        assertTrue(problem.message().contains("table reward itself"),
                "and the way out is named: " + problem.message());
        assertPointsAtKeyValue(problem, table, "\"conditions\"");
    }

    @Test
    @DisplayName("an inline table's entries are walked: unknown fields reported, conditions refused")
    void inlineTableEntriesAreChecked() {
        // The walk itself is new. Nothing descended into `inline` before this, so an inline entry's
        // fields were the codec's business alone -- and a codec ignores unknown fields, which is the
        // gap the field-name checks exist to close.
        Problems unknownField = validateQuest("""
                {"id": "one", "title": "One",
                 "rewards": [ { "type": "tenet:loot", "inline": { "entries": [
                   { "weight": 1, "wobble": true,
                     "reward": { "type": "tenet:item", "item": "minecraft:gold_ingot" } } ] } } ]}""");
        assertTrue(containing(unknownField, "unknown field \"wobble\"").severity()
                        == DataProblem.Severity.ERROR,
                "an inline entry's fields are checked now, got: " + messages(unknownField));

        Problems conditioned = validateQuest("""
                {"id": "one", "title": "One",
                 "rewards": [ { "type": "tenet:loot", "inline": { "entries": [
                   { "weight": 1, "reward": { "type": "tenet:item", "item": "minecraft:gold_ingot",
                       "conditions": [ { "type": "tenet:stage", "stage": "my_pack:marked" } ] } } ] } } ]}""");
        assertTrue(containing(conditioned, "cannot carry \"conditions\"").severity()
                        == DataProblem.Severity.ERROR,
                "the inline path refuses it too, got: " + messages(conditioned));

        // And a clean inline table is still clean, so the new walk is not a wall of new errors.
        Problems clean = validateQuest("""
                {"id": "one", "title": "One",
                 "rewards": [ { "type": "tenet:loot", "inline": { "entries": [
                   { "weight": 1,
                     "reward": { "type": "tenet:item", "item": "minecraft:gold_ingot" } } ] } } ]}""");
        assertTrue(clean.isEmpty(), "a well-formed inline table must be clean, got: " + messages(clean));
    }

    @Test
    @DisplayName("a choice entry is refused wherever it sits, and a table with no entries warns")
    void choiceEntriesAndEmptyTables() {
        // A roll hands entries out; a choice waits for a player to pick one. So a choice entry is an
        // entry the engine would skip with a log line -- an error here instead, at its own line.
        String table = """
                {"entries": [
                  { "weight": 1, "reward": { "type": "tenet:choice",
                      "inline": { "entries": [ { "weight": 1,
                        "reward": { "type": "tenet:item", "item": "minecraft:gold_ingot" } } ] } } }
                ]}""";
        Problems problems = new Problems();
        QuestValidator.validateRewardTableDocument(Fixtures.document("loot.json", table), problems);

        DataProblem problem = containing(problems, "cannot be an entry in a table");
        assertTrue(problem.severity() == DataProblem.Severity.ERROR,
                "an entry the roll skips must not load quietly");
        assertPointsAtValue(problem, table, "\"tenet:choice\"");

        // And the same inside an inline table, which is the same walk one level down.
        Problems inline = validateQuest("""
                {"id": "one", "title": "One",
                 "rewards": [ { "type": "tenet:loot", "inline": { "entries": [
                   { "weight": 1, "reward": { "type": "tenet:choice",
                       "inline": { "entries": [ { "weight": 1,
                         "reward": { "type": "tenet:item", "item": "minecraft:gold_ingot" } } ] } } } ] } } ]}""");
        assertTrue(containing(inline, "cannot be an entry in a table").severity()
                        == DataProblem.Severity.ERROR,
                "the inline path refuses it too, got: " + messages(inline));

        // An empty table is a placeholder, not a mistake -- a warning, the way a file with no quests
        // is. Said out loud because a roll of it grants nothing and the file looks finished.
        Problems empty = new Problems();
        QuestValidator.validateRewardTableDocument(
                Fixtures.document("empty.json", "{ \"entries\": [] }"), empty);
        assertTrue(containing(empty, "has no entries").severity() == DataProblem.Severity.WARNING,
                "an empty table warns rather than refusing to load, got: " + messages(empty));
    }

    @Test
    @DisplayName("a weight that is not a number is refused, and a negative one only warns")
    void emptyWeightHasTwoSeverities() {
        // The split is on whether the arithmetic still means something, not on tidiness. Infinity breaks
        // it: every weighted throw compares false, so the table silently grants only its guaranteed
        // entries -- a table that looks weighted and pays like an unweighted one.
        //
        // Reached through a literal that overflows rather than through `NaN`: this project's own parser
        // refuses `NaN` and `Infinity` as JSON, which is the right place for that refusal, so the value
        // the codec can still be handed is the one that starts life as a finite-looking number.
        Problems notANumber = validateTable("{ \"emptyWeight\": 1e400, \"entries\": [] }");
        DataProblem broken = containing(notANumber, "not a number a roll can use");
        assertTrue(broken.severity() == DataProblem.Severity.ERROR,
                "a table whose dice cannot land must not load quietly, got: " + messages(notANumber));
        assertTrue(broken.message().contains("guaranteed entries"),
                "and it says what would happen: " + broken.message());

        // A finite negative is a defined reading -- the roll clamps it to zero -- so refusing the file
        // would turn a pack that loads today into one that does not, over a number the loader can read.
        Problems negative = validateTable("{ \"emptyWeight\": -3, \"entries\": [] }");
        assertTrue(containing(negative, "read as 0").severity() == DataProblem.Severity.WARNING,
                "a negative weight is a warning, got: " + messages(negative));

        // And the bounds either side of it: zero is fine, and a positive weight is fine. Asserted about
        // the field rather than about the document, because an empty table warns about being empty --
        // which is a different fact and would otherwise hide the one under test.
        assertTrue(noProblemOf(validateTable("{ \"emptyWeight\": 0, \"entries\": [] }"), "emptyWeight"),
                "no empty band is not a problem");
        assertTrue(noProblemOf(validateTable("{ \"emptyWeight\": 2.5, \"entries\": [] }"),
                        "emptyWeight"),
                "and a positive one is what the field is for");
    }

    @Test
    @DisplayName("lootSize is held to the codec's own bounds, and the message repeats them")
    void lootSizeIsBounded() {
        Problems tooMany = validateTable("{ \"lootSize\": 1001, \"entries\": [] }");
        DataProblem over = containing(tooMany, "lootSize must be between");
        assertTrue(over.severity() == DataProblem.Severity.ERROR, "one past the end is refused");
        assertTrue(over.message().contains(String.valueOf(RewardTable.LOOT_SIZE_MAX)),
                "and the bound it names is the codec's own: " + over.message());

        assertTrue(validateTable("{ \"lootSize\": 0, \"entries\": [] }").all().stream()
                        .anyMatch(problem -> problem.severity() == DataProblem.Severity.ERROR),
                "zero throws is not a table that rolls");

        // The value the stepper stops at is the value the loader accepts, which is the point of one
        // constant: a panel that could reach past it would send a value that comes back refused.
        assertTrue(noProblemOf(validateTable("{ \"lootSize\": " + RewardTable.LOOT_SIZE_MAX
                        + ", \"entries\": [] }"), "lootSize"),
                "the top of the range is inside it");
    }

    @Test
    @DisplayName("the choice-entry refusal is the model's own sentence, not a second one")
    void theChoiceRefusalComesFromTheModel() {
        // The editor's type picker draws this type blocked with the same words, so the two cannot
        // describe the rule differently -- and the picker is the one an author believes, because it is
        // the one that looks like it knows.
        String table = """
                {"entries": [
                  { "weight": 1, "reward": { "type": "tenet:choice",
                      "inline": { "entries": [ { "weight": 1,
                        "reward": { "type": "tenet:item", "item": "minecraft:gold_ingot" } } ] } } }
                ]}""";
        Problems problems = validateTable(table);

        DataProblem problem = containing(problems, "cannot be an entry in a table");
        assertEquals(TableReward.entryRefusal(TableReward.TYPE_CHOICE), problem.message(),
                "the validator and the picker must refuse with one sentence");
    }

    /** A named-table document through the validator. */
    private static Problems validateTable(String table) {
        Problems problems = new Problems();
        QuestValidator.validateRewardTableDocument(Fixtures.document("loot.json", table), problems);
        return problems;
    }

    /** Whether no problem at all was reported about one field -- a warning counts as a problem here. */
    private static boolean noProblemOf(Problems problems, String field) {
        return problems.all().stream().noneMatch(problem -> problem.path().contains(field));
    }

    // ------------------------------------------------------------------
    // Retired fields: warned about, never refused
    // ------------------------------------------------------------------

    @Nested
    @DisplayName("retired fields")
    class RetiredFields {

        @Test
        @DisplayName("a version in a per-kind quest warns instead of refusing the file")
        void versionInAPerKindQuestWarns() {
            // A file split out of a version-1 tree may have kept its "version", which means
            // nothing where the folder layout already says the format. Refusing the file over
            // it would cost the quest over one ignored number.
            Problems problems = validateQuest("""
                    {"id": "one", "title": "One", "version": 1}""");

            DataProblem problem = containing(problems, "\"version\" means nothing here");
            assertEquals(DataProblem.Severity.WARNING, problem.severity(),
                    "a leftover must never fail a load");
            assertFalse(problems.hasErrors(), "the quest still loads:" + messages(problems));
        }

        @Test
        @DisplayName("a version in per-kind group and chapter files warns too")
        void versionInGroupAndChapterWarns() {
            Problems group = new Problems();
            QuestValidator.validateGroupDocument(
                    Fixtures.document("group.json", """
                            {"id": "g", "title": "G", "version": 1, "chapters": []}"""), group);
            Problems chapter = new Problems();
            QuestValidator.validateChapterDocument(
                    Fixtures.document("chapter.json", """
                            {"id": "c", "title": "C", "version": 1, "quests": []}"""), chapter);

            assertEquals(DataProblem.Severity.WARNING,
                    containing(group, "\"version\" means nothing here").severity());
            assertEquals(DataProblem.Severity.WARNING,
                    containing(chapter, "\"version\" means nothing here").severity());
            assertFalse(group.hasErrors(), "the group still loads:" + messages(group));
            assertFalse(chapter.hasErrors(), "the chapter still loads:" + messages(chapter));
        }

        @Test
        @DisplayName("a version at the version-1 root keeps its own rule")
        void versionAtTheVersion1RootIsUntouched() {
            // The root is the one place version means something, so the retired rule must not
            // fire there: a clean version-1 file stays clean, with no new warning.
            Problems problems = validate(Fixtures.file(Fixtures.q("a").build()));

            assertTrue(problems.isEmpty(), "a version-1 file with version 1 stays clean, got:"
                    + messages(problems));
        }

        @Test
        @DisplayName("a table id warns and names the file, not another field")
        void tableIdWarnsAndNamesTheFile() {
            // A legacy reward table carries its hex id at the root; Tenet tables are named by their
            // file. The id is not read, but the table still loads — and the warning must not point
            // at "uid": a table file's root uid is ignored too, so that rename would trade one
            // dead field for another.
            Problems problems = validateTable("""
                    {"id": "3a3bdbA4e9ad13c4",
                     "entries": [{ "weight": 1,
                       "reward": { "type": "tenet:item", "item": "minecraft:stone" } }]}""");

            DataProblem problem = containing(problems, "\"id\" is not read");
            assertEquals(DataProblem.Severity.WARNING, problem.severity());
            assertTrue(problem.message().contains("reward_tables/<name>.json"),
                    "the warning names what actually names the table: " + problem.message());
            assertFalse(problem.message().contains("uid"),
                    "and offers no rename that does nothing there: " + problem.message());
            assertFalse(problems.hasErrors(), "the table still loads:" + messages(problems));
        }

        @Test
        @DisplayName("an inline table id points at uid, which is the handle that works there")
        void inlineTableIdPointsAtUid() {
            // The one place "uid" means something: an inline table is addressed by its handle, so
            // a converted inline id has a rename rather than only a removal.
            Problems problems = validateQuest("""
                    {"id": "one", "title": "One",
                     "rewards": [{"type": "tenet:random",
                       "inline": {"id": "old",
                         "entries": [{ "weight": 1,
                           "reward": { "type": "tenet:item", "item": "minecraft:stone" } }]}}]}""");

            DataProblem problem = containing(problems, "\"id\" is not read");
            assertEquals(DataProblem.Severity.WARNING, problem.severity());
            assertTrue(problem.message().contains("rename this to \"uid\""),
                    "the inline warning offers the rename: " + problem.message());
            assertFalse(problems.hasErrors(), "the reward still loads:" + messages(problems));
        }

        @Test
        @DisplayName("a loot_crate warns but the table's entries still load")
        void lootCrateWarnsButTheTableLoads() {
            // The physical crate has no Tenet home, so the sub-object is not read — while the
            // table around it is. Refusing the file would cost every entry over one mechanic.
            Problems problems = validateTable("""
                    {"loot_crate": {"string_id": "crate", "color": 1},
                     "entries": [{ "weight": 1,
                       "reward": { "type": "tenet:item", "item": "minecraft:stone" } }]}""");

            DataProblem problem = containing(problems, "\"loot_crate\" has no Tenet home");
            assertEquals(DataProblem.Severity.WARNING, problem.severity());
            assertFalse(problems.hasErrors(), "the entries still load:" + messages(problems));
        }

        @Test
        @DisplayName("anything else unknown is still an error: the retired list is closed")
        void otherUnknownTableFieldsAreStillErrors() {
            // The tolerance is three names, not a direction. A legacy snake_case leftover like
            // "loot_size" is a file that was never converted, and loading it as if it were
            // would pay the default loot size while wearing the author's number.
            Problems problems = validateTable("""
                    {"loot_size": 2,
                     "entries": [{ "weight": 1,
                       "reward": { "type": "tenet:item", "item": "minecraft:stone" } }]}""");

            assertEquals(DataProblem.Severity.ERROR,
                    containing(problems, "unknown field \"loot_size\"").severity());
        }
    }

    @Test
    @DisplayName("an icon whose components do not decode is refused -- the loader would skip the quest")    void iconWithABrokenPatchIsRefused() {
        // The icon's half of the per-type codec check: a component patch the codec cannot read would
        // otherwise pass every field-name rule and reach the loader, which skips the whole file.
        Problems problems = validateQuest("""
                {"id": "one", "title": "One",
                 "icon": {"item": "minecraft:paper", "components": 5}}""");

        DataProblem problem = containing(problems, "not a usable item reference");
        assertTrue(problem.severity() == DataProblem.Severity.ERROR, "a file the loader drops is an error");
    }

    @Test
    @DisplayName("an icon object with no arm was already refused, and stays refused")
    void iconWithoutAnItemIsRefused() {
        // Pinned because the picker's clear row is about to rely on it: clearing an icon has to remove
        // the whole object, since removing only the leaf leaves this -- which does not load either.
        Problems problems = validateQuest("""
                {"id": "one", "title": "One", "icon": {}}""");

        assertTrue(containing(problems, "not a usable icon").severity()
                        == DataProblem.Severity.ERROR,
                "the icon's required arm is the existing check the clear path is built around");
    }

    // ------------------------------------------------------------------
    // Split control points: a per-line pair, refused where it cannot mean anything
    // ------------------------------------------------------------------

    /** A file whose line (a waits on b) is split into two control points. */
    private static final String SPLIT_FILE = """
            {"version": 1, "chapterGroups": [{"id": "g", "title": "G",
              "chapters": [{"id": "c", "title": "C", "quests": [
                {"id": "a", "title": "a", "dependsOn": ["b"],
                 "dependencyLines": {"b": {"form": "curved",
                    "fromHandle": [0.33, 0.2], "toHandle": [0.66, -0.2]}}},
                {"id": "b", "title": "b"}
              ]}]}]}""";

    @Test
    @DisplayName("a split pair on a line is clean")
    void aSplitLineIsClean() {
        Problems problems = validate(SPLIT_FILE);

        assertTrue(problems.isEmpty(), "a well-formed split must be clean, got:" + messages(problems));
    }

    @Test
    @DisplayName("a handle that is not exactly two numbers is refused instead of throwing at load")
    void aMalformedHandleIsRefused() {
        // `[0.33, "x"]` is the pair that matters: a check that answered "two entries, the last one a
        // number" would pass it and then throw on getAsDouble -- a validator crash instead of a report.
        for (String pair : new String[] { "[0.33]", "[0.33, 0.2, 0.1]", "[0.33, \"x\"]", "\"nope\"" }) {
            Problems problems = validate(SPLIT_FILE.replace("[0.33, 0.2]", pair));

            assertTrue(containing(problems, "expected two numbers").severity() == DataProblem.Severity.ERROR,
                    "pair " + pair + " must be refused, got:" + messages(problems));
        }
    }

    @Test
    @DisplayName("a handle dragged out of its range is refused, with the range in the message")
    void aHandleOutOfRangeIsRefused() {
        Problems problems = validate(SPLIT_FILE.replace("[0.66, -0.2]", "[0.66, -2.0]"));

        DataProblem problem = containing(problems, "stay near its line");
        assertTrue(problem.message().contains("-0.9 to 0.9"),
                "the message carries the range it is checking: " + problem.message());
    }

    @Test
    @DisplayName("a split pair in a chapter's default is refused on its own line")
    void aChapterDefaultRefusesSplitHandles() {
        // Same rule as anchors, and for the same reason: where a control point pulls a line is a fact
        // about that line's two ends, so a chapter-wide one would be wrong on almost every edge.
        String extras = "\"dependencyStyle\": {\n                \"fromHandle\": [0.33, 0.2]},";
        String json = Fixtures.fileWithChapter(extras, Fixtures.q("a").build());
        Problems problems = validate(json);
        DataProblem problem = containing(problems, "per line, not per chapter");

        int line = 0;
        String[] lines = json.split("\n");
        for (int i = 0; i < lines.length; i++) {
            if (lines[i].contains("fromHandle")) {
                line = i + 1;
                break;
            }
        }
        assertEquals(line, problem.line(), "the refusal must land where the author wrote it");
    }

    // ------------------------------------------------------------------
    // The line-style vocabulary: the current axes, and the legacy one still read
    // ------------------------------------------------------------------

    /** A file whose line (a waits on b) carries the current arrow vocabulary. */
    private static final String STYLED_FILE = """
            {"version": 1, "chapterGroups": [{"id": "g", "title": "G",
              "chapters": [{"id": "c", "title": "C", "quests": [
                {"id": "a", "title": "a", "dependsOn": ["b"],
                 "dependencyLines": {"b": {"form": "chamfered", "arrowHead": "triangle",
                    "arrowPlace": "stream", "arrowDensity": "high", "dash": "dash_dot",
                    "weight": "conduit"}}},
                {"id": "b", "title": "b"}
              ]}]}]}""";

    @Test
    @DisplayName("the current line vocabulary is clean, new axes and all")
    void theNewVocabularyIsClean() {
        Problems problems = validate(STYLED_FILE);

        assertTrue(problems.isEmpty(), "the new values must validate, got:" + messages(problems));
    }

    @Test
    @DisplayName("an unknown value on a new axis is refused with the names it does know")
    void anUnknownArrowValueIsRefused() {
        Problems problems = validate(STYLED_FILE.replace("\"triangle\"", "\"harpoon\""));

        DataProblem problem = containing(problems, "\"harpoon\" is not a arrowHead this build knows");
        assertTrue(problem.message().contains("chevron"),
                "the message lists the real names: " + problem.message());
    }

    @Test
    @DisplayName("the legacy arrows axis is still accepted, so old files keep validating")
    void theLegacyArrowsAxisIsStillAccepted() {
        Problems problems = validate("""
                {"version": 1, "chapterGroups": [{"id": "g", "title": "G",
                  "chapters": [{"id": "c", "title": "C", "quests": [
                    {"id": "a", "title": "a", "dependsOn": ["b"],
                     "dependencyLines": {"b": {"arrows": "many", "form": "curved"}}},
                    {"id": "b", "title": "b"}
                  ]}]}]}""");

        assertTrue(problems.isEmpty(), "an old file must still validate, got:" + messages(problems));
    }

    @Test
    @DisplayName("a chapter default may carry the new axes too")
    void aChapterDefaultCarriesTheNewAxes() {
        // Only anchors and split handles are per-line facts; a chapter-wide head, place and density are
        // exactly what a chapter default is for.
        String extras = "\"dependencyStyle\": {\"arrowHead\": \"dot\", \"arrowPlace\": \"mid\"},";
        Problems problems = validate(Fixtures.fileWithChapter(extras, Fixtures.q("a").build()));

        assertTrue(problems.isEmpty(), "a chapter default of the new vocabulary must be clean: "
                + messages(problems));
    }

    // ------------------------------------------------------------------
    // The chapter's theme patch: the toolkit's own reader is the check
    // ------------------------------------------------------------------

    @Test
    @DisplayName("a well-formed theme patch on a chapter is clean")
    void aChapterThemePatchIsClean() {
        String extras = "\"themePatch\": {\"colours\": {\"raised\": \"#FF24242E\"}, \"cornerRadius\": 4},";
        Problems problems = validate(Fixtures.fileWithChapter(extras, Fixtures.q("a").build()));

        assertTrue(problems.isEmpty(), "a chapter may dress itself, got:" + messages(problems));
    }

    @Test
    @DisplayName("an unknown token and an unreadable colour are both reported, at the patch's line")
    void aBadThemePatchIsReported() {
        // The messages are the toolkit's, which is the point of reusing its reader: they name the token
        // or the value, and they cannot drift from what the client will actually do with the file.
        String token = "\"themePatch\": {\"colours\": {\"no_such_token\": \"#FF24242E\"}},";
        assertTrue(containing(validate(Fixtures.fileWithChapter(token, Fixtures.q("a").build())),
                        "is not a colour a theme can set").severity() == DataProblem.Severity.ERROR,
                "an unknown token must be refused");

        String colour = "\"themePatch\": {\"colours\": {\"raised\": \"not a colour\"}},";
        assertTrue(containing(validate(Fixtures.fileWithChapter(colour, Fixtures.q("a").build())),
                        "which is not a hex").severity() == DataProblem.Severity.ERROR,
                "an unreadable colour must be refused");
    }

    @Test
    @DisplayName("a theme patch that is not an object is refused with the fields it takes")
    void aNonObjectThemePatchIsRefused() {
        String extras = "\"themePatch\": 4,";
        DataProblem problem = containing(
                validate(Fixtures.fileWithChapter(extras, Fixtures.q("a").build())),
                "expected an object of theme overrides");

        assertTrue(problem.message().contains("cornerRadius"),
                "the message names what a patch may hold: " + problem.message());
        // And every key, not four of six: the vocabulary is ThemePatch's own, so a key added there
        // cannot leave this sentence behind. `name` and `canvasBackground` were the two that had gone
        // missing from it, which is exactly the drift a hand-written list produces.
        for (String key : dev.ellipog.armature.client.ui.ThemePatch.KEYS) {
            assertTrue(problem.message().contains("\"" + key + "\""),
                    "the message should name every key a patch may hold, and misses \"" + key
                            + "\": " + problem.message());
        }
    }

    // ------------------------------------------------------------------
    // The auto-claim ladder's two author fields
    // ------------------------------------------------------------------

    @Test
    @DisplayName("a quest and a chapter may each set an auto-claim mode")
    void autoClaimModesAreAccepted() {
        Problems quest = validate(file(
                "{\"id\": \"a\", \"title\": \"a\", \"autoClaim\": \"no_toast\"}"));
        assertTrue(quest.isEmpty(), "a quest may set its own mode, got:" + messages(quest));

        Problems chapter = validate(Fixtures.fileWithChapter("\"autoClaim\": \"enabled\",",
                Fixtures.q("a").build()));
        assertTrue(chapter.isEmpty(), "and a chapter may set the default for its quests, got:"
                + messages(chapter));
    }

    @Test
    @DisplayName("a mode this build does not know is refused, with the names it does")
    void anUnknownAutoClaimModeIsRefused() {
        Problems problems = validate(file(
                "{\"id\": \"a\", \"title\": \"a\", \"autoClaim\": \"sometimes\"}"));

        DataProblem problem = containing(problems, "'sometimes' is not one of");
        assertTrue(problem.message().contains("no_toast"),
                "the message lists the real names: " + problem.message());
    }

    @Test
    @DisplayName("a chapter's description is checked against the codec that will read it")
    void chapterDescriptionsAreCheckedPerLayout() {
        // The folder format's chapter takes a list or one bare string, like a group's, so a bare string
        // there is clean.
        Problems folder = new Problems();
        QuestValidator.validateChapterDocument(Fixtures.document(
                "getting_started/first_steps/chapter.json", """
                        { "id": "first_steps", "title": "First Steps", "quests": ["a.json"],
                          "description": "One bare line, which the folder format takes." }
                        """), folder);
        assertTrue(folder.isEmpty(), "the folder format's chapter takes the union, got:" + messages(folder));

        // The version-1 chapter's codec is list-only, so the same shape there is an error -- and it used
        // to be the codec's, reported at line 1 column 1 with no field named at all, because nothing
        // checked a chapter's description.
        Problems flat = validate(Fixtures.fileWithChapter("\"description\": \"One bare line.\",",
                Fixtures.q("a").build()));
        DataProblem refused = containing(flat, "expected a list");
        assertTrue(refused.severity() == DataProblem.Severity.ERROR,
                "a version-1 chapter's description has to be a list");
        assertTrue(refused.path().contains("description"),
                "and the problem names the field rather than pointing at the file: " + refused.path());
    }

    @Test
    @DisplayName("a chapter's own gate is checked field by field, at the path the author wrote")
    void chapterGateFieldsAreChecked() {
        // The per-file half of the chapter gate: the shape of the five fields, checked where a person can
        // act on it. What a single file cannot know -- whether a referenced chapter exists, whether a
        // completion is declared by the chapter a completed edge points at -- is QuestIndex's, and is
        // asserted in ChapterDependencyTest.
        Problems clean = new Problems();
        QuestValidator.validateChapterDocument(Fixtures.document("first_steps/chapter.json", """
                { "id": "first_steps", "title": "First Steps", "quests": [],
                  "dependsOn": ["prologue"], "prerequisiteMode": "one_started", "minRequired": 1,
                  "completesWhen": ["the_festival"], "hideUntilDependenciesComplete": true }
                """), clean);
        assertTrue(clean.errorCount() == 0,
                "a well-formed gate has nothing wrong with it, got:" + messages(clean));

        // A mode name no codec knows. The codec would fail with a message at the file root; the validator
        // names the field and the values, which is the whole reason it checks a closed set itself.
        Problems badMode = new Problems();
        QuestValidator.validateChapterDocument(Fixtures.document("first_steps/chapter.json", """
                { "id": "first_steps", "title": "First Steps", "quests": [],
                  "prerequisiteMode": "all_finished" }
                """), badMode);
        assertTrue(containing(badMode, "all_finished").severity() == DataProblem.Severity.ERROR);
        assertTrue(containing(badMode, "all_finished").path().contains("prerequisiteMode"),
                "named at its own path: " + containing(badMode, "all_finished").path());
        assertTrue(containing(badMode, "all_completed").message().contains("all_completed"),
                "and the message lists the names that would work: "
                        + containing(badMode, "all_completed").message());

        // A count outside the model's own bounds. The bounds come from the record, so the two cannot
        // disagree -- the same arrangement the quest-level counted flags already have.
        Problems badCount = new Problems();
        QuestValidator.validateChapterDocument(Fixtures.document("first_steps/chapter.json", """
                { "id": "first_steps", "title": "First Steps", "quests": [],
                  "dependsOn": ["prologue"], "minRequired": 99 }
                """), badCount);
        assertTrue(containing(badCount, "minRequired must be between").path().contains("minRequired"));

        // An empty name in either list, and a name that is not an id at all: both are the author's typo,
        // and neither can be a reference -- one resolves to nothing by construction, and the other cannot
        // be a folder's name either.
        Problems badNames = new Problems();
        QuestValidator.validateChapterDocument(Fixtures.document("first_steps/chapter.json", """
                { "id": "first_steps", "title": "First Steps", "quests": [],
                  "dependsOn": [""], "completesWhen": ["Not An Id"] }
                """), badNames);
        assertTrue(containing(badNames, "a chapter id may not be empty").path().contains("dependsOn"),
                messages(badNames));
        assertTrue(containing(badNames, "is not a valid quest id").path().contains("completesWhen"),
                "the completion list is checked as quest names, which is what it holds: "
                        + messages(badNames));

        // And the flag is read as a boolean rather than trusted.
        Problems badFlag = new Problems();
        QuestValidator.validateChapterDocument(Fixtures.document("first_steps/chapter.json", """
                { "id": "first_steps", "title": "First Steps", "quests": [],
                  "hideUntilDependenciesComplete": "yes" }
                """), badFlag);
        DataProblem wrongType = containing(badFlag, "expected true or false");
        assertTrue(wrongType.severity() == DataProblem.Severity.ERROR);
        assertTrue(wrongType.path().contains("hideUntilDependenciesComplete"),
                "the boolean check points at its own field: " + wrongType.path());
    }

    @Test
    @DisplayName("an automatic mode on a choice reward warns, because it cannot be honoured")
    void anAutomaticChoiceRewardWarns() {
        // The setting is not an error -- the file loads and the engine leaves the choice outstanding
        // for the claim flow -- but it reads as supported and does nothing, which is exactly what this
        // validator exists to say out loud.
        String quest = """
                {"id": "a", "title": "a",
                 "rewards": [{"type": "tenet:choice", "auto": "enabled", "inline": {"entries": [
                    {"weight": 1, "reward": {"type": "tenet:item", "item": "minecraft:stone"}},
                    {"weight": 1, "reward": {"type": "tenet:xp", "amount": 5}}]}}]}""";
        Problems problems = validate(file(quest));

        assertTrue(containing(problems, "waits for the player's pick").severity()
                        == DataProblem.Severity.WARNING,
                "a warning, not an error: got " + messages(problems));
        assertEquals(0, problems.errorCount(), "and the file is still loadable: " + messages(problems));
    }

    // ------------------------------------------------------------------
    // Canvas elements
    // ------------------------------------------------------------------

    /**
     * The chapter-level list, checked as one file can check it.
     *
     * <p>What a single chapter cannot answer — whether the quest an element waits on exists — is
     * {@code QuestIndex}'s, and is asserted in {@code QuestIndexTest}. Everything here is a fact about the
     * file: the shape of the list, the arm each element claims to be, the fields that arm allows, and the
     * values the codec is too lenient to refuse.
     */
    @Nested
    @DisplayName("canvas elements")
    class CanvasElements {

        private static Problems chapter(String elements) {
            Problems problems = new Problems();
            QuestValidator.validateChapterDocument(Fixtures.document("first_steps/chapter.json",
                    "{ \"id\": \"first_steps\", \"title\": \"First Steps\", \"quests\": [],"
                            + " \"elements\": " + elements + " }"), problems);
            return problems;
        }

        @Test
        @DisplayName("one of each kind, spelled out in full, has nothing wrong with it")
        void oneOfEachIsClean() {
            // The list is also the worked example the schema's descriptions are written from, so it is worth
            // asserting clean: a validator that reported a legitimate element would make the format
            // unusable, and the four arms are easy to get subtly wrong one at a time.
            Problems clean = chapter("""
                    [ { "type": "rect", "id": "box", "x": -8, "y": -8, "width": 64, "height": 64,
                        "fillColor": "#40101018", "borderColor": "#66204060", "borderWidth": 1 },
                      { "type": "text", "id": "label", "x": 0, "y": -40, "text": "Chapter 1",
                        "scale": 1.5, "color": "#A0A0A0", "shadow": true },
                      { "type": "line", "id": "divider", "x1": 0, "y1": 0, "x2": 64, "y2": 0,
                        "width": 2, "color": "#66A0A0A0", "arrowhead": "both" },
                      { "type": "image", "id": "logo", "order": 2, "x": 0, "y": 0, "width": 32,
                        "height": 32, "rotation": 8, "corner": true,
                        "image": { "sprite": "minecraft:block/sculk" },
                        "tint": "#FFFFFFFF", "alpha": 200, "dev": true, "requires": "the_core",
                        "title": { "translate": "element.logo.title", "fallback": "Logo" },
                        "label": { "onImage": true, "shadow": true, "inset": 4, "hAlign": "end",
                                   "vAlign": "start" },
                        "click": { "type": "open_quest", "data": "the_core" } } ]
                    """);
            assertEquals(0, clean.errorCount(), "a well-formed element list: " + messages(clean));
        }

        @Test
        @DisplayName("the list and its entries are objects and elements, not whatever was written")
        void theShapeOfTheListIsChecked() {
            assertTrue(containing(chapter("{}"), "expected a list of elements").severity()
                    == DataProblem.Severity.ERROR);
            assertTrue(containing(chapter("[ 42 ]"), "expected an element object").message()
                    .contains("expected an element object"), messages(chapter("[ 42 ]")));
        }

        @Test
        @DisplayName("every element needs a type and an id")
        void typeAndIdAreRequired() {
            assertTrue(containing(chapter("[ { \"id\": \"box\" } ]"), "needs a \"type\" string")
                    .message().contains("image"), "the message lists the four kinds: "
                            + messages(chapter("[ { \"id\": \"box\" } ]")));
            assertTrue(containing(chapter("[ { \"type\": \"rect\" } ]"), "missing required field id")
                    .path().contains("id"), "the id check is the shared one, and names its own path");
        }

        @Test
        @DisplayName("an unknown type is one warning, and its other fields are left alone")
        void anUnknownTypeIsAWarningAndTheRestIsNotChecked() {
            // The same treatment an unknown task type gets, and the reason is the report rather than the
            // element: this build cannot know what a badge's fields are, so calling each of them unknown
            // would be a page of noise about a value the author has already been told about once.
            Problems problems = chapter("[ { \"type\": \"tenet:badge\", \"id\": \"b\", \"glow\": 12 } ]");

            DataProblem warning = containing(problems, "unknown element type");
            assertEquals(DataProblem.Severity.WARNING, warning.severity(), messages(problems));
            assertTrue(warning.message().contains("tenet:badge"), "and names the type it cannot draw");
            assertEquals(0, problems.errorCount(), "the chapter still loads: " + messages(problems));
            assertFalse(messages(problems).contains("glow"),
                    "and the payload is not reported field by field: " + messages(problems));
        }

        @Test
        @DisplayName("an unknown field is reported against the arm it was written on")
        void unknownFieldsAreCheckedPerArm() {
            // Per arm rather than against the union of every arm, and the difference is the quality of the
            // message: `x1` on a box is a real mistake worth naming, and a union would have accepted it
            // because a line legitimises the name.
            Problems problems = chapter("[ { \"type\": \"rect\", \"id\": \"box\", \"x1\": 4 } ]");
            assertTrue(containing(problems, "unknown field \"x1\"").path().contains("elements[0].x1"),
                    "named at its own path: " + messages(problems));
            assertTrue(containing(problems, "did you mean \"x\"").message().contains("valid fields here"),
                    "with the arm's own field list: " + messages(problems));

            // And a field from another arm is unknown here: a picture has no `fillColor`.
            assertTrue(messages(chapter("[ { \"type\": \"image\", \"id\": \"i\", \"fillColor\": \"#FFFFFF\","
                            + " \"image\": { \"sprite\": \"minecraft:air\" } } ]"))
                            .contains("unknown field \"fillColor\""));
        }

        @Test
        @DisplayName("two elements may not share an id, because the second could then never be addressed")
        void idsAreUniqueWithinAChapter() {
            Problems problems = chapter("""
                    [ { "type": "rect", "id": "box" },
                      { "type": "text", "id": "box", "text": "twice" } ]
                    """);
            assertTrue(containing(problems, "share the id \"box\"").path().contains("elements[1].id"),
                    "the second one is the one reported: " + messages(problems));

            // The id's own shape is the same check a quest's id gets, so a capital is reported the same way.
            assertTrue(messages(chapter("[ { \"type\": \"rect\", \"id\": \"Box\" } ]"))
                    .contains("lowercase letters"));
        }

        @Test
        @DisplayName("a colour, a size and a scale are reported rather than silently clamped")
        void theNumbersAndColoursAreReported() {
            // The two halves of one arrangement: the codec clamps so a document always loads, and this
            // reports so an author is told their number was not the one that was read. `minRequired` already
            // works this way, and the message is the same shape.
            assertTrue(containing(chapter("[ { \"type\": \"rect\", \"id\": \"b\", "
                            + "\"fillColor\": \"#A0A0A\" } ]"), "#A0A0A").message().contains("#RRGGBB"),
                    "a short colour names the spellings: "
                            + messages(chapter("[ { \"type\": \"rect\", \"id\": \"b\", "
                                    + "\"fillColor\": \"#A0A0A\" } ]")));
            assertTrue(messages(chapter("[ { \"type\": \"rect\", \"id\": \"b\", \"borderWidth\": 99 } ]"))
                    .contains("must be between 0 and 16"));
            assertTrue(messages(chapter("[ { \"type\": \"text\", \"id\": \"t\", \"text\": \"x\", "
                            + "\"scale\": 9 } ]"))
                    .contains("must be between 0.25 and 4.0"));
            assertTrue(messages(chapter("[ { \"type\": \"image\", \"id\": \"i\", \"alpha\": 900, "
                            + "\"image\": { \"sprite\": \"minecraft:air\" } } ]"))
                    .contains("must be between 0 and 255"));
        }

        @Test
        @DisplayName("a picture's pin must be a real boolean, and saying nothing is unlocked")
        void lockedMustBeABoolean() {
            // The same shape as the label's flags: absent is off, a word is a mistake worth naming.
            Problems clean = chapter("[ { \"type\": \"image\", \"id\": \"i\", \"locked\": true, "
                    + "\"image\": { \"sprite\": \"minecraft:air\" } } ]");
            assertEquals(0, clean.errorCount(), "a pinned picture: " + messages(clean));

            Problems wordy = chapter("[ { \"type\": \"image\", \"id\": \"i\", \"locked\": \"yes\", "
                    + "\"image\": { \"sprite\": \"minecraft:air\" } } ]");
            assertTrue(containing(wordy, "expected true or false").path().contains("elements[0].locked"),
                    "at the pin's own field: " + messages(wordy));
        }

        @Test
        @DisplayName("a picture names exactly one source, and the codec cannot say so")
        void aPictureNeedsOneSource() {            // The one thing the codec genuinely cannot refuse: it reads the file arm first, so an object
            // carrying both would quietly lose its sprite, and an object carrying neither has nothing to
            // draw. See ImageSource for why the leniency is there and this is where it is reported.
            assertTrue(messages(chapter("[ { \"type\": \"image\", \"id\": \"i\", \"image\": "
                            + "{ \"texture\": \"pack:textures/x.png\", \"sprite\": \"minecraft:air\" } } ]"))
                    .contains("both a \"texture\" and a \"sprite\""));
            assertTrue(messages(chapter("[ { \"type\": \"image\", \"id\": \"i\", \"image\": {} } ]"))
                    .contains("needs a source"));
            assertTrue(messages(chapter("[ { \"type\": \"image\", \"id\": \"i\" } ]"))
                    .contains("needs a source"), "and an image with no source field at all says the same");
            assertTrue(messages(chapter("[ { \"type\": \"image\", \"id\": \"i\", "
                            + "\"image\": { \"texture\": \"Not An Id\" } } ]"))
                    .contains("is not an id this game can resolve"));
        }

        @Test
        @DisplayName("every action this build runs loads clean with well-formed data")
        void allSupportedActionsAreClean() {
            // The inverse of the old refusal test: with nothing left to refuse, what this pins is that
            // every name the codec reads has a validator arm that accepts its well-formed data. A type
            // added to the enum without an arm would fail the switch's exhaustiveness at compile time;
            // a type with an arm that refuses its own valid data fails here.
            assertEquals(0, chapter(click("none", "")).errorCount(), "none carries nothing");
            assertEquals(0, chapter(click("open_quest", "some_quest")).errorCount(),
                    "a dangling target is the index's to report, not this validator's");
            assertEquals(0, chapter(click("open_uri", "https://example.invalid")).errorCount());
            assertEquals(0, chapter(click("run_command", "say hi")).errorCount());
            assertEquals(0, chapter(click("custom_event", "my_pack:sounded")).errorCount());
            assertEquals(0, chapter(click("show_recipe", "minecraft:blast_furnace")).errorCount());
            assertEquals(0, chapter(click("show_docs", "mod,book")).errorCount());
        }

        @Test
        @DisplayName("a recipe press names an item, and a docs press names a shelf and a book")
        void recipeAndDocsDataAreChecked() {            // The shape the openers need: a viewer opens an item, and a guide press names the mod and
            // the book it would have opened. Checked here rather than at the press, so an author hears
            // about it at load with the file and the line.
            assertTrue(messages(chapter(click("show_recipe", ""))).contains("needs an item id"));
            assertTrue(messages(chapter(click("show_recipe", "Not An Id"))).contains("not an id"));
            assertEquals(0, chapter(click("show_recipe", "minecraft:blast_furnace")).errorCount(),
                    "an item id is clean: " + messages(chapter(click("show_recipe", "minecraft:blast_furnace"))));

            assertTrue(messages(chapter(click("show_docs", "justamod"))).contains("<mod>,<book>"));
            assertTrue(messages(chapter(click("show_docs", "mod,"))).contains("<mod>,<book>"),
                    "a book with no name is no address");
            assertEquals(0, chapter(click("show_docs", "mod,book")).errorCount(), "mod and book are enough");
            assertEquals(0, chapter(click("show_docs", "mod,book,page,anchor")).errorCount(),
                    "and the page and the anchor ride along");
        }

        @Test
        @DisplayName("a command press names a command, and an event press names an event id")
        void commandAndEventDataAreChecked() {
            // The shape the server needs: a command it can run, and an id it can fire. Checked here
            // rather than at the press, so an author hears about it at load with the file and the line
            // -- and whether anything listens is deliberately not checked, because listeners are runtime.
            assertTrue(messages(chapter(click("run_command", ""))).contains("needs a command to run"));
            assertEquals(0, chapter(click("run_command", "say {p} pressed it")).errorCount(),
                    "any non-blank command is well-formed: the dispatcher is what refuses a bad one");

            assertTrue(messages(chapter(click("custom_event", ""))).contains("needs an event id"));
            assertTrue(messages(chapter(click("custom_event", "Not An Id")))
                    .contains("not an event id"));
            assertEquals(0, chapter(click("custom_event", "my_pack:sounded")).errorCount(),
                    "namespace:path is clean");
        }

        @Test
        @DisplayName("a click's data is checked by the same rule the opener uses")
        void clickDataIsChecked() {
            // Mirrored from the client's own opener rather than approximated, so a URL that passes here
            // cannot be refused when a player presses it -- which is what "only http and https links open"
            // arriving after a successful load would mean.
            assertTrue(messages(chapter(click("open_uri", "file:///etc/passwd"))).contains("http and https"));
            assertTrue(messages(chapter(click("open_uri", "not a url"))).contains("not a valid address"));
            assertTrue(messages(chapter(click("open_quest", ""))).contains("needs a quest id or alias"));
            assertTrue(messages(chapter(click("teleport", "over there"))).contains("open_quest"),
                    "a name no version has is refused with the names that exist");

            Problems clean = chapter(click("open_uri", "https://example.invalid/a?b=c"));
            assertEquals(0, clean.errorCount(), "and a legitimate address is clean: " + messages(clean));
        }

        private static String click(String type, String data) {
            return "[ { \"type\": \"image\", \"id\": \"i\", "
                    + "\"image\": { \"sprite\": \"minecraft:air\" }, "
                    + "\"click\": { \"type\": \"" + type + "\", \"data\": \"" + data + "\" } } ]";
        }

        @Test
        @DisplayName("a label's axes are its own three-value vocabulary, not the line axes")
        void labelAlignmentIsChecked() {
            assertTrue(messages(chapter("[ { \"type\": \"image\", \"id\": \"i\", "
                            + "\"image\": { \"sprite\": \"minecraft:air\" }, "
                            + "\"label\": { \"hAlign\": \"centre\" } } ]"))
                    .contains("start, middle, end"));
            assertTrue(messages(chapter("[ { \"type\": \"image\", \"id\": \"i\", "
                            + "\"image\": { \"sprite\": \"minecraft:air\" }, "
                            + "\"label\": { \"sideways\": true } } ]"))
                    .contains("unknown field \"sideways\""), "and a label's own fields are a closed set");
            assertTrue(messages(chapter("[ { \"type\": \"line\", \"id\": \"l\", "
                            + "\"arrowhead\": \"around\" } ]")).contains("none, start, end, both"));
        }

        @Test
        @DisplayName("an empty gate is this file's problem, and a gate that resolves to nothing is the index's")
        void theGatesShapeIsCheckedHere() {
            // The split the chapter gate already has: the shape of the field is checked where the author
            // wrote it, and whether the name exists is the pass that can see every quest.
            assertTrue(messages(chapter("[ { \"type\": \"rect\", \"id\": \"b\", \"requires\": \"\" } ]"))
                    .contains("is empty"));
            assertEquals(0, chapter("[ { \"type\": \"rect\", \"id\": \"b\", "
                            + "\"requires\": \"some_quest\" } ]").errorCount(),
                    "a name that resolves to nothing is not this validator's to refuse");
        }
    }

    /**
     * A chapter's markers.
     *
     * <p>What a single chapter can answer — the shape of the list, the fields a link allows, and the
     * values the codec is too lenient to refuse. Whether the quest a link points at exists is
     * {@code QuestIndex}'s, and is asserted in {@code QuestIndexTest}. Everything here is a fact
     * about the file.
     */
    @Nested
    @DisplayName("quest links")
    class QuestLinks {

        private static Problems chapterWithLinks(String links) {
            Problems problems = new Problems();
            QuestValidator.validateChapterDocument(Fixtures.document("first_steps/chapter.json",
                    "{ \"id\": \"first_steps\", \"title\": \"First Steps\", \"quests\": [],"
                            + " \"links\": " + links + " }"), problems);
            return problems;
        }

        @Test
        @DisplayName("a full link, spelled out, has nothing wrong with it")
        void oneLinkIsClean() {
            Problems clean = chapterWithLinks("""
                    [ { "id": "gate_hint", "quest": "the_deep", "x": 336, "y": -64,
                        "shape": "hexagon", "size": 64 } ]
                    """);
            assertEquals(0, clean.errorCount(), "a well-formed link: " + messages(clean));
        }

        @Test
        @DisplayName("a link needs an id and a target, and nothing it does not know")
        void idAndTargetAreRequired() {
            assertTrue(messages(chapterWithLinks("[ { \"quest\": \"a\" } ]"))
                    .contains("missing required field id"));
            assertTrue(messages(chapterWithLinks("[ { \"id\": \"l\" } ]")).contains("missing required"),
                    "a marker without a target is nothing: " + messages(chapterWithLinks(
                            "[ { \"id\": \"l\" } ]")));
            assertTrue(messages(chapterWithLinks(
                            "[ { \"id\": \"l\", \"quest\": \"a\", \"linked_quest\": \"b\" } ]"))
                            .contains("unknown field \"linked_quest\""),
                    "the legacy spelling is the tool's to map, not the file's to carry");
        }

        @Test
        @DisplayName("two links may not share an id, because the second could then never be addressed")
        void idsAreUniqueWithinAChapter() {
            Problems problems = chapterWithLinks("""
                    [ { "id": "twice", "quest": "a" },
                      { "id": "twice", "quest": "b" } ]
                    """);
            assertTrue(containing(problems, "share the id \"twice\"").path().contains("links[1].id"),
                    "the second one is the one reported: " + messages(problems));
        }

        @Test
        @DisplayName("an empty target is this file's problem, and a missing one is the index's")
        void theTargetsShapeIsCheckedHere() {
            assertTrue(messages(chapterWithLinks("[ { \"id\": \"l\", \"quest\": \"\" } ]"))
                    .contains("is empty"));
            assertEquals(0, chapterWithLinks("[ { \"id\": \"l\", \"quest\": \"some_quest\" } ]")
                    .errorCount(), "a name that resolves to nothing is not this validator's to refuse");
        }

        @Test
        @DisplayName("a shape is the node's own vocabulary, and a size outside it is read clamped")
        void shapeAndSizeAreChecked() {
            assertTrue(messages(chapterWithLinks("[ { \"id\": \"l\", \"quest\": \"a\", "
                            + "\"shape\": \"round\" } ]")).contains("rounded"),
                    "a near miss names the words that exist");
            Problems clamped = chapterWithLinks("[ { \"id\": \"l\", \"quest\": \"a\", "
                    + "\"size\": 4000 } ]");
            assertTrue(containing(clamped, "must be between 16 and 512").severity()
                    == DataProblem.Severity.WARNING, "clamped, not refused: " + messages(clamped));
            assertEquals(0, clamped.errorCount(), "and the file still loads: " + messages(clamped));
        }
    }

    private static String file(String quest) {
        return "{\"version\": 1, \"chapterGroups\": [{\"id\": \"g\", \"title\": \"G\", "
                + "\"chapters\": [{\"id\": \"c\", \"title\": \"C\", \"quests\": [" + quest + "]}]}]}";
    }

    private static String messages(Problems problems) {
        return problems.all().stream().map(DataProblem::render).reduce("", (a, b) -> a + "\n" + b);
    }
}
