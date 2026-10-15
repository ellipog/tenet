package dev.ellipog.tenet.server;

import dev.ellipog.armature.api.ArmatureApi;
import dev.ellipog.armature.api.config.ArmatureConfig;
import dev.ellipog.armature.api.config.TeamSettings;
import dev.ellipog.armature.api.data.DataProblem;
import dev.ellipog.armature.api.data.Problems;
import dev.ellipog.armature.api.platform.ArmaturePlatform;
import dev.ellipog.armature.api.platform.PlatformKind;
import dev.ellipog.armature.api.registry.Registrar;
import dev.ellipog.armature.api.teams.Team;
import dev.ellipog.armature.api.teams.TeamPolicy;
import dev.ellipog.armature.api.teams.TeamRole;
import dev.ellipog.armature.api.teams.Teams;
import dev.ellipog.tenet.TenetCommand;
import dev.ellipog.tenet.api.TenetScripts;
import dev.ellipog.tenet.editor.EditPhases;
import dev.ellipog.tenet.net.ClaimChoiceResultPayload;
import dev.ellipog.tenet.progress.ClaimFilter;
import dev.ellipog.tenet.progress.ProgressService;
import dev.ellipog.tenet.progress.QuestState;
import dev.ellipog.tenet.progress.StageService;
import dev.ellipog.tenet.quest.ItemStacks;
import dev.ellipog.tenet.quest.QuestIndex;
import dev.ellipog.tenet.quest.QuestLoader;
import dev.ellipog.tenet.quest.QuestFiles;
import dev.ellipog.tenet.quest.TenetQuests;
import dev.ellipog.tenet.net.PartySnapshot;
import dev.ellipog.tenet.net.ProgressSyncPayload;
import dev.ellipog.tenet.net.QuestSync;
import dev.ellipog.tenet.net.TenetNetworking;
import dev.ellipog.tenet.party.PartyMode;
import dev.ellipog.tenet.party.PartyStore;

import net.minecraft.core.Registry;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.CropBlock;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Stage 3's exit condition, played rather than argued.
 *
 * <h2>What this is for</h2>
 *
 * <p>The plan's bar for Stage 3 is: <i>"you can complete an entire questline through commands, with
 * no GUI, and two players on a dedicated server share progress correctly."</i> Until now the engine
 * was checked only as a set of pure functions — {@code ProgressionEngineTest} hands the resolver a
 * quest index and some progress and reads states back, which is the right way to test the <i>rules</i>
 * and says nothing about whether anything connects.
 *
 * <p>Everything between those rules and a player was unverified, and the gap is not theoretical:
 * {@code /tenet progress}, {@code submit} and {@code complete} all call
 * {@code getPlayerOrException()}, so none of them had ever actually run. They compile. Nothing proved
 * they execute.
 *
 * <h2>How it runs</h2>
 *
 * <p>A real server, a real world, a real {@code ServerPlayer} — see {@link HeadlessServer} for why the
 * GameTest framework could not be borrowed and its recipe was copied instead. Commands go through the
 * server's own dispatcher with the player attached as the source's entity, which is the same source
 * shape a command block gets when it acts on a player.
 *
 * <h2>The one seam, and it is worth naming</h2>
 *
 * <p>The engine's automatic half is driven by a <i>player tick</i> that fires from each loader's event,
 * and no loader is present in a test, so nothing fires it. {@link #tickUntil} calls
 * {@code ProgressService.tick} directly, which is what the loader would call. So the automatic
 * evaluation is exercised through its real entry point but not through the real <i>trigger</i>; the
 * trigger is three lines in {@code Tenet.listen} and is covered by the loader's own event tests.
 *
 * <h2>Why the transcript shows translation keys</h2>
 *
 * <p>A dedicated server has no language file loaded, so {@code Component.getString()} on a translatable
 * returns the key: the transcript reads {@code tenet.command.complete.done} rather than English. That
 * is why <b>nothing here asserts on output text</b>. The return value is the assertion — 0 for a
 * refusal, 1 for a change — which is also sturdier: rewording or translating a message cannot break
 * these tests, and a test that matched on English would pass while the message was wrong for everyone
 * not playing in it.
 *
 * <h2>Ordered, because a playthrough is a sequence</h2>
 *
 * <p>A quest unlocking is a consequence of the previous quest completing, so these run in order and
 * carry state between them deliberately. That is the opposite of the usual rule for tests and is the
 * point here: the thing being tested is a chain, and splitting it into independent cases would test
 * five invocations of the resolver instead of one playthrough.
 */
@DisplayName("Stage 3: a whole questline, played by command")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class QuestPlaythroughTest {

    /** The example chapter's onboarding five, in the order they become available. */
    private static final List<String> THE_QUESTLINE = List.of(
            "punch_a_tree", "make_a_table", "stone_tools", "read_the_sign", "the_underground");

    /** Where the world, the config and the transcript go. Inside the build directory, so not committed. */
    private static final Path ROOT = Path.of("build", "playthrough");

    private static final List<String> TRANSCRIPT = new ArrayList<>();

    private static HeadlessServer server;
    private static ServerPlayer player;
    private static QuestLoader.Result loaded;

    /** The second player, spawned when the party tests start. Null until then. */
    private static ServerPlayer friend;

    /** The party's own id, which is also the key its progress is stored under. */
    private static UUID partyId;

    /**
     * The party {@code /tenet party create} formed, which is a different party from {@link #partyId}.
     *
     * <p>Kept separate rather than reusing {@code partyId}, and the reason is that they are built by
     * different means: {@code partyId} comes from Armature's API directly, and this one from the
     * command. Reusing the field would leave the command's tests unable to tell "the command used the
     * party that already existed" from "the command formed one".
     */
    private static UUID commandPartyId;

    /** What the last tick of the engine reported as changed. Empty when nothing moved. */
    private static java.util.Set<UUID> lastTick = java.util.Set.of();

    /** Every owner any tick has reported as changed, for the assertion that the engine reports at all. */
    private static final java.util.Set<UUID> changedEver = new java.util.LinkedHashSet<>();

    /**
     * Where the worked examples live, relative to the Gradle project directory.
     *
     * <p>The same directory {@code QuestIndexTest} reads and {@code tenet/tools/seed_quests.py} copies
     * from. Three readers of one directory, and no list of file names anywhere — because a list is a
     * thing that stops matching the directory, which is exactly how the first version of the seeding
     * went wrong.
     */
    private static final Path EXAMPLES = Path.of("..", "tools", "quests");

    /** The example files this test seeded, in load order. Set once, in {@code @BeforeAll}. */
    private static List<String> examples = List.of();

    // ------------------------------------------------------------------
    // Before and after
    // ------------------------------------------------------------------

    @BeforeAll
    static void startEverything() throws Exception {
        // Deleted first, every run. A world left over from a previous run carries its saved progress,
        // and "the playthrough passed on the second run and failed on the first" is a difference
        // nobody would guess the cause of. The same goes for the quest directory, which is now
        // something this test puts there itself.
        deleteRecursively(ROOT);
        Path configDir = ROOT.resolve("config");
        Files.createDirectories(configDir);

        // Seed the config directory before anything reads it, and by copying the same files an author
        // would -- this is the step `tenet/tools/seed_quests.py` performs, done in Java so a test does
        // not depend on a Python interpreter being on PATH.
        //
        // The test does it rather than the loader, and that is the whole point of this round: the mod
        // reads a quest directory and does nothing else, so a test that wants quests has to put them
        // there. Relying on the loader to create its own input was the design that had to go, because
        // a mod that seeds content into a player's config directory has decided something that is not
        // its to decide.
        examples = seedExamples(configDir);
        note("seeded " + examples.size() + " example questline(s) into " + configDir.resolve("tenet/quests"));
        // The auto-claim chapter is seeded and counted like the examples are, so the load check below
        // keeps holding it to "everything seeded reached the index".
        List<String> seeded = new ArrayList<>(examples);
        seeded.addAll(seedAutoClaimChapter(configDir));
        seeded.addAll(seedEngineChapter(configDir));
        seeded.addAll(seedRewardInboxChapter(configDir));
        seeded.addAll(seedFlexChapter(configDir));
        seeded.addAll(seedManualChapter(configDir));
        seeded.addAll(seedFilterChapter(configDir));
        seeded.addAll(seedChargeChapter(configDir));
        seeded.addAll(seedLinkChapter(configDir));
        seeded.addAll(seedTeamStageChapter(configDir));
        seeded.addAll(seedJunctionChapter(configDir));
        seeded.addAll(seedBreakChapter(configDir));
        Collections.sort(seeded);
        examples = List.copyOf(seeded);

        server = HeadlessServer.start(ROOT.resolve("universe"));

        // Armature's platform layer, because TenetQuests.reload() asks it for the config directory
        // and there is no loader here to provide one. Asserted to be uninstalled first: if something
        // else got there before this test, the config directory would be someone else's and every
        // result below would be about the wrong questline.
        assertThrows(IllegalStateException.class, ArmatureApi::platform,
                "Armature's platform layer was already installed before this test -- its config "
                        + "directory is not the one these assertions are about");
        ArmatureApi.install(new PlaythroughPlatform(configDir), new NothingIsRegistered());

        // The real load path, the one /tenet reload calls.
        loaded = TenetQuests.reload();

        // Registered onto the server's own dispatcher, which is the one a typed command reaches.
        server.callOnServerThread(() -> {
            TenetCommand.register(server.dispatcher());
            return null;
        });

        player = server.spawnPlayer("tenet-tester");

        note("server up: " + TenetQuests.summary());
        note("player '" + player.getScoreboardName() + "' is on the player list: "
                + server.callOnServerThread(() -> server.server().getPlayerList().getPlayers().size()));
    }

    @AfterAll
    static void stopEverything() throws IOException {
        // The transcript is written even when a test failed, because the transcript is most of what a
        // failure needs: the commands that ran, in order, and what each of them said.
        Files.createDirectories(ROOT);
        Files.writeString(ROOT.resolve("transcript.txt"),
                String.join("\n", TRANSCRIPT) + "\n");
        System.out.println("    playthrough transcript: " + ROOT.resolve("transcript.txt").toAbsolutePath());

        if (server != null) {
            server.close();
        }
    }

    // ------------------------------------------------------------------
    // The playthrough
    // ------------------------------------------------------------------

    @Test
    @Order(1)
    @DisplayName("the examples this test seeded load with nothing wrong in them")
    void theSeededExamplesLoad() throws IOException {
        assertTrue(loaded.ok(), () -> "the seeded questlines did not load cleanly:\n" + render(loaded.problems()));

        // Counted from the seeded file names, and this is the fourth version of this check.
        //
        // The first asserted `5 quests in 1 chapter from 1 file`. That was right until the second and
        // third questlines were written, at which point it failed for the one reason a test should never
        // fail: the content grew. A hardcoded 5 was a claim about the content wearing the clothes of a
        // check on the loader.
        //
        // The second derived the expected numbers by *walking the index* -- it compared the index against
        // itself, so it could only fail if the index were internally inconsistent, which it never is. It
        // read as a check on the loader and was a tautology.
        //
        // The third decoded every seeded file and added up the groups, chapters and quests inside them.
        // That was a genuine independent count under version 1, where a file <i>is</i> a whole tree -- and
        // it is impossible under version 2, which is why this had to change rather than be repaired:
        // `group.json` decodes to a manifest holding a list of <b>names</b>, and the names are not in the
        // document at all. They are the directory listing. A test that decoded the files and counted what
        // was inside them would count three manifests and no chapters.
        //
        // So the count comes from the file names, which are the format's own fixed contract: a group is a
        // folder holding a {@code group.json}, a chapter holds a {@code chapter.json}, and every other
        // {@code .json} inside a chapter folder is one quest. Counting those is not a re-implementation of
        // the loader -- it is the loader's stated naming rules, and the loader is what this checks. What
        // it catches is the failure that matters: a file that was seeded, sits on disk, and never reached
        // the index.
        int seededGroups = 0;
        int seededChapters = 0;
        int seededQuests = 0;
        int seededFlat = 0;
        int seededTables = 0;
        int seededLocales = 0;
        for (String name : examples) {
            Path relative = Path.of(name);
            String fileName = relative.getFileName().toString();

            if (relative.getNameCount() > 1
                    && relative.getName(0).toString().equals(QuestFiles.REWARD_TABLES_DIRECTORY)) {
                // A reward table, not a quest. It sits in its reserved folder, the loader reads it in
                // its own pass and keeps it out of the index, so counting it as a quest here would
                // fail the count below by exactly the number of tables.
                seededTables++;
                continue;
            }

            if (relative.getNameCount() > 1
                    && relative.getName(0).toString().equals(QuestFiles.LANG_DIRECTORY)) {
                // A translation, not a quest, for the same reason and by the same rule: `lang/` is
                // reserved by name, and a locale is read by its own pass and kept out of the index.
                // Counting one as a quest would fail the count below by exactly the number of locales
                // -- and this is the shape the whole reserved-folder mechanism exists to produce, so
                // the test that counts by file name is where it has to be honoured too.
                seededLocales++;
                continue;
            }

            if (fileName.equals(QuestFiles.GROUP_MANIFEST)) {
                seededGroups++;
            }
            else if (fileName.equals(QuestFiles.CHAPTER_MANIFEST)) {
                seededChapters++;
            }
            else if (fileName.endsWith(".json")) {
                if (relative.getNameCount() > 1) {
                    seededQuests++;
                }
                else {
                    // A version-1 file at the quest root: one whole tree in one document. None of the
                    // examples is one any more, which is what the assertion below says -- and counting
                    // them separately matters, because a conversion that left one behind would otherwise
                    // inflate the quest count while every id in the pair was reported as a duplicate.
                    seededFlat++;
                }
            }
        }

        assertEquals(0, seededFlat,
                "the examples still contain a version-1 flat file at the quest root. Leaving one beside"
                        + " the folder it was converted into is worse than not converting at all: every id"
                        + " then exists twice, and the loader reports a duplicate for every group, chapter"
                        + " and quest in the pair -- which reads as a broken conversion rather than as a"
                        + " stale copy.");

        // Every seeded group, chapter and quest was found -- which is the property the three
        // file-counting variants were all circling, and the only one that stays meaningful as content is
        // added.
        assertEquals(seededGroups, TenetQuests.index().groupCount(),
                "every chapter group in the seeded files should be in the index");
        assertEquals(seededChapters, TenetQuests.index().chapterCount(),
                "every chapter in the seeded files should be in the index");
        assertEquals(seededQuests, TenetQuests.index().questCount(),
                "every quest in the seeded files should be in the index");

        // The files the loader examines are the quest tree's, and neither a reward table nor a locale
        // is one of them: each lives in a folder reserved by name, the discovery walk skips it, and its
        // own pass reads it. So the seeded count comes down by both before it meets filesFound.
        assertEquals(examples.size() - seededTables - seededLocales, loaded.filesFound(),
                "every seeded file the loader examines should have been found and read. Reward tables"
                        + " and locales are seeded but not examined -- they are read by their own passes"
                        + " -- so they come off the seeded count here.");

        // And the tables are loaded, keyed by file name: the same contract as a chapter's quest list,
        // and the one a `tenet:loot` reward like The Winnings depends on resolving.
        assertEquals(seededTables, loaded.rewardTables().size(),
                "every seeded reward table should be loaded, keyed by its file name without the suffix");
        assertTrue(seededTables > 0,
                "the examples no longer include a reward table, so nothing in the playthrough rolls one"
                        + " -- add one under tools/quests/reward_tables/ or stop counting on it");

        // And the locales, which are the same shape of claim one folder over: a file in a reserved
        // folder is seeded, is read by its own pass, and never reaches the index. Asserted here rather
        // than in a test of its own because this is the one place the whole tree is put on disk and
        // loaded through the real entry point -- so it is the only place that can catch a `lang/` folder
        // the loader stops reading, which is exactly what a reserved name going stale looks like.
        assertEquals(seededLocales, TenetQuests.languages().locales().size(),
                "every seeded locale should have been read, and none invented");
        assertTrue(TenetQuests.languages().refused().isEmpty(),
                () -> "the shipped example's translation was refused: "
                        + TenetQuests.languages().refused());

        // And not vacuously: every count above is zero if the walk found nothing, and zero equals zero.
        assertTrue(seededQuests > 0 && seededGroups > 0,
                "the counting walk found no content at all under " + EXAMPLES.toAbsolutePath()
                        + ", so every assertion above is comparing zero with zero");

        note("the seeded examples are " + seededQuests + " quest(s) in " + seededChapters
                + " chapter(s) in " + seededGroups + " group(s), " + seededTables
                + " reward table(s) and " + seededLocales + " locale(s), from " + examples.size()
                + " file(s)");
    }

    @Test
    @Order(2)
    @DisplayName("a quest behind the whole chain cannot be forced, and forcing it changes nothing")
    void aLockedQuestCannotBeForced() {
        HeadlessServer.Outcome attempt = asOperator("/tenet complete the_underground");

        assertRefused(attempt, "the_underground depends on stone_tools, which depends on make_a_table");
        assertEquals(QuestState.LOCKED, stateOf("the_underground"), "and it is still locked");
        assertEquals(0, storedQuestCount(),
                "a refused command must not have written progress. A non-zero count here means the "
                        + "refusal happened after something was already recorded.");

        // And the script binding, which is the documented parity: the same service call, and the same
        // guard in front of it now. Without the guard a script could finish the chain the command above
        // just refused, because the service itself never looks at a quest's dependencies.
        assertFalse(TenetScripts.complete(player, "the_underground"),
                "the script path refuses a locked quest, as the command does");
        assertEquals(QuestState.LOCKED, stateOf("the_underground"), "and it is still locked");
        assertEquals(0, storedQuestCount(), "and the script's refusal wrote nothing either");
    }

    @Test
    @Order(3)
    @DisplayName("an item task that does not consume has no button, so it cannot be handed in")
    void anItemTaskThatNeverConsumesCannotBeHandedIn() {
        HeadlessServer.Outcome attempt = asOperator("/tenet submit punch_a_tree 0");

        assertRefused(attempt, "the task completes by itself the moment the player has the logs; "
                + "there is deliberately nothing to press");
        assertEquals(QuestState.UNLOCKED, stateOf("punch_a_tree"),
                "and the quest is untouched -- unlocked, having done nothing");
        assertEquals(0, storedQuestCount(), "and nothing was written");
    }

    @Test
    @Order(4)
    @DisplayName("a checkmark cannot be handed in for a quest that is still locked")
    void aCheckmarkCannotBeHandedInWhileItsQuestIsLocked() {
        HeadlessServer.Outcome attempt = asOperator("/tenet submit read_the_sign 0");

        assertRefused(attempt, "read_the_sign depends on punch_a_tree, which is not done");
        assertEquals(QuestState.LOCKED, stateOf("read_the_sign"), "and it is still locked");
    }

    @Test
    @Order(5)
    @DisplayName("gathering the items completes the quest by itself, and pays out")
    void gatheringTheItemsCompletesTheFirstQuestAndGivesItsReward() {
        HeadlessServer.Outcome given = asOperator("/give @s minecraft:oak_log 8");
        assertEquals(1, given.result(), () -> "/give should have worked. It said:\n" + given.text());
        assertEquals(8, countInInventory(Items.OAK_LOG), "the player should be holding eight logs");

        boolean completed = tickUntil(() -> stateOf("punch_a_tree") == QuestState.COMPLETED,
                Duration.ofSeconds(20));

        assertTrue(completed, () -> "eight oak logs did not complete punch_a_tree within twenty seconds."
                + "\n  the quest is " + stateOf("punch_a_tree")
                + " and task 0 is recorded at " + recordedTask("punch_a_tree", 0) + " of 8."
                + "\n  A recorded value of 0 means the engine's auto-submit pass never ran at all,"
                + "\n  which is a different fault from running and counting wrong.");

        // Nothing is handed over yet, and this is the assertion that pins manual claiming: finishing a
        // quest and collecting its reward are two acts. The axe arrives when the player asks for it.
        assertEquals(0, countInInventory(Items.WOODEN_AXE),
                "punch_a_tree is finished, and its reward is deliberately NOT in the inventory yet -- "
                        + "a completed quest and a paid one are different states, and this is the one "
                        + "that says so");
        assertEquals(8, countInInventory(Items.OAK_LOG),
                "the task does not consume, so the logs are still there");

        HeadlessServer.Outcome claimed = asOperator("/tenet claim punch_a_tree");
        assertEquals(1, claimed.result(),
                () -> "/tenet claim should have handed the axe over. It said:\n" + claimed.text());
        assertEquals(1, countInInventory(Items.WOODEN_AXE),
                "punch_a_tree's reward is one wooden axe, and now it has been collected");

        // Clicking twice must be harmless. This is the whole reason claiming is safe to expose as a
        // button: the server re-checks rather than trusting the request.
        HeadlessServer.Outcome again = asOperator("/tenet claim punch_a_tree");
        assertRefused(again, "those rewards have already been collected once");
        assertEquals(1, countInInventory(Items.WOODEN_AXE),
                "and a second claim must not produce a second axe");

        // And the engine told somebody. This is the assertion that would have caught the bug a player
        // reported as "it doesn't register logs I have in my inventory": everything above passed while
        // the quest book showed 0/8, because the engine's automatic half had no way to reach a client.
        // The counting was right the whole time; the *reporting* was missing.
        //
        // Asserted here rather than in a test of its own because this is the moment a change happens,
        // and the property is "a change is reported when it happens". If `tick` ever goes back to
        // returning nothing, the compiler is the only thing that would object -- and only at the call
        // site in Tenet.listen, where a missing sync has no symptom at all.
        assertTrue(changedEver.contains(ownerOf(player)),
                () -> "the engine completed a quest and reported no team as changed, so nothing would "
                        + "have been sent to the client and the quest book would still say 0/8."
                        + "\n  the owner is " + ownerOf(player)
                        + " and ticks have reported " + changedEver);
    }

    @Test
    @Order(6)
    @DisplayName("a checkmark is handed in by hand, and that is how its quest completes")
    void aCheckmarkIsHandedInByHand() {
        assertEquals(QuestState.UNLOCKED, stateOf("read_the_sign"),
                "punch_a_tree completed, so its dependent should be unlocked");

        HeadlessServer.Outcome submitted = asOperator("/tenet submit read_the_sign 0");
        assertEquals(1, submitted.result(),
                () -> "the checkmark should have been accepted. It said:\n" + submitted.text());
        assertEquals(QuestState.COMPLETED, stateOf("read_the_sign"),
                "one task, submitted, is a complete quest -- nothing else observes a checkmark");
    }

    @Test
    @Order(7)
    @DisplayName("the rest of the chain completes by command, in order")
    void theRestOfTheChainCompletesByCommand() {
        for (String id : List.of("make_a_table", "stone_tools", "the_underground")) {
            QuestState before = stateOf(id);
            assertTrue(before.isPlayable(), () -> id + " should be playable by now, but it is " + before);

            HeadlessServer.Outcome done = asOperator("/tenet complete " + id);
            assertEquals(1, done.result(), () -> "/tenet complete " + id + " was refused:\n" + done.text());
            assertEquals(QuestState.COMPLETED, stateOf(id), id + " should now be completed");
        }
    }

    @Test
    @Order(8)
    @DisplayName("the whole questline reads as done")
    void theWholeQuestlineIsCompleted() {
        for (String id : THE_QUESTLINE) {
            assertEquals(QuestState.COMPLETED, stateOf(id), id + " should be completed");
        }

        // The listing is what a player without the GUI has, so it has to say something. Its wording is
        // not asserted -- see the class comment on translation keys.
        HeadlessServer.Outcome listing = asOperator("/tenet progress");
        assertTrue(listing.output().size() > 0,
                "the progress listing should print at least its header, and printed nothing at all");
    }

    @Test
    @Order(9)
    @DisplayName("resetting one quest clears exactly that quest")
    void resettingOneQuestClearsIt() {
        HeadlessServer.Outcome reset = asOperator("/tenet reset make_a_table");
        assertEquals(1, reset.result(),
                () -> "resetting one quest should clear exactly one. It said:\n" + reset.text());

        assertEquals(QuestState.UNLOCKED, stateOf("make_a_table"),
                "a cleared quest is recomputed from its dependencies, and punch_a_tree is still done, "
                        + "so it is unlocked rather than locked");
        assertEquals(QuestState.COMPLETED, stateOf("punch_a_tree"),
                "clearing one quest must not clear anything else");

        // Deliberately not asserted: whether a quest that had been completed *and* depends on the one
        // just cleared locks again. That is a design question -- honour the stored completion, or
        // recompute the whole graph -- and not a rule this test should invent an answer to. It is
        // recorded here instead, so the answer is visible rather than assumed.
        note("after resetting make_a_table: stone_tools=" + stateOf("stone_tools")
                + " the_underground=" + stateOf("the_underground")
                + " (recorded, not asserted -- see the comment)");
    }

    @Test
    @Order(10)
    @DisplayName("forcing a quest takes operator permission")
    void forcingAQuestTakesOperatorPermission() {
        // make_a_table was cleared above, so it is playable and a success here would be visible.
        HeadlessServer.Outcome asPlayer = server.run(player, 0, "/tenet complete make_a_table");
        note(asPlayer.report());

        assertTrue(asPlayer.refused(), "a player without op must not be able to force a quest");
        assertNotNull(asPlayer.thrown(),
                "it should be Brigadier refusing the command -- unknown or unpermitted -- so Tenet's "
                        + "code never runs. A refusal from inside Tenet would mean the permission gate "
                        + "is in the wrong place.");
        assertNotEquals(QuestState.COMPLETED, stateOf("make_a_table"),
                "and nothing may have changed");

        HeadlessServer.Outcome asOperator = asOperator("/tenet complete make_a_table");
        assertEquals(1, asOperator.result(), () -> "an operator should be able to. It said:\n" + asOperator.text());
        assertEquals(QuestState.COMPLETED, stateOf("make_a_table"), "and now it is done");
    }

    // ------------------------------------------------------------------
    // Two players, which is Stage 3's second half
    // ------------------------------------------------------------------

    /**
     * <h2>What was actually wrong here</h2>
     *
     * <p>The next three tests are the plan's second Stage 3 clause — <i>"two players on a dedicated
     * server share progress correctly"</i> — and writing them found a real defect rather than
     * confirming a design.
     *
     * <p>{@code ProgressService} keys progress by <b>team id</b>, and its class comment said sharing
     * therefore "falls out rather than needing to be built". That is true of the <i>storage</i> and it
     * was false of the <i>counting</i>, which is the half nobody looks at. {@code tick} chose one
     * member per team, by player-list order — whoever logged in first — and {@code evaluateTeam}
     * counted item tasks in that one member's inventory. The parameter was even named
     * {@code anyMember}, which is the tell: for a task that asks whether <i>somebody</i> in the party
     * has eight logs, who you ask looks like it should not matter.
     *
     * <p>It matters completely, and it is wrong in exactly the case parties exist for. One of you
     * gathers the wood, and the wood is in <i>their</i> inventory. Ask the other one and the party
     * sees nothing — and the failure presents as "the quest never completes", which sends you to the
     * quest file rather than to the loop.
     *
     * <h2>Why the tests alternate who holds the items</h2>
     *
     * <p>This is the design that makes them worth having, and it is deliberate rather than tidy.
     * Giving the items to the non-owner is a stronger test than giving them to the owner, because the
     * old bug chose by player-list order and the owner tends to be first. But a fixed "always give
     * them to the friend" would still pass if somebody later picked a representative by a
     * <i>different</i> fixed rule — say the last member, or the one with the highest UUID.
     *
     * <p>So the direction reverses: {@link #aPartySharesOneProgressRecord} has the friend gather, and
     * {@link #theOtherPlayerGatheringWorksToo} has the owner gather. <b>No single chosen member can
     * pass both.</b> That is the whole reason there are two of them.
     */

    @Test
    @Order(11)
    @DisplayName("a party shares one progress record, and one player gathering works for both")
    void aPartySharesOneProgressRecord() {
        friend = server.spawnPlayer("tenet-friend");
        note("spawned '" + friend.getScoreboardName() + "', a second real player on the server");

        // Emptied now that both players exist, and this matters more than it looks. The solo run
        // above left player holding eight oak logs, because punch_a_tree's task does not consume --
        // so without this, both members would be holding exactly the required eight and the test
        // could pass by accident through the *other* member, which is the bug it exists to catch.
        clearInventories();

        // Created and joined through Armature's own API, which is what /tenet party will wrap. The
        // invite is a separate step from the accept because they fire different events, and accepting
        // is what fires MEMBER_JOINED.
        partyId = server.callOnServerThread(() -> {
            var teams = Teams.of(server.server());
            var party = teams.create("the playthrough party", player.getUUID());
            boolean invited = teams.invite(party.id(), friend.getUUID());
            assertTrue(invited, "the friend should have been invited to the new party");
            return party.id();
        });

        Optional<UUID> joined = server.callOnServerThread(() ->
                Teams.of(server.server()).acceptInvite(friend.getUUID()).map(team -> team.id()));
        assertEquals(Optional.of(partyId), joined, "the friend should now be in the party");

        assertEquals(partyId, ownerOf(player), "the party's own id is the key progress is stored under");
        assertEquals(ownerOf(player), ownerOf(friend),
                "and both members resolve to it -- which is the whole of 'progress is shared'");
        assertNotEquals(player.getUUID(), ownerOf(player),
                "and it is neither player's solo id, so the party starts empty rather than inheriting "
                        + "what its owner did alone");

        // The friend holds them, and only the friend. Giving them to the member that a first-in-the-
        // player-list rule would *not* pick is what makes this fail loudly if that rule comes back.
        server.onServerThread(() -> {
            friend.getInventory().add(ItemStacks.stackOf(Items.OAK_LOG, 8));
            friend.getInventory().setChanged();
        });

        assertEquals(0, countInInventoryOf(player, Items.OAK_LOG), "the party's owner is holding none");
        assertEquals(8, countInInventoryOf(friend, Items.OAK_LOG), "and the friend is holding eight");

        boolean completed = tickUntil(() -> stateFor(friend, "punch_a_tree") == QuestState.COMPLETED,
                Duration.ofSeconds(20));

        assertTrue(completed, () -> "eight logs in the *friend's* inventory did not complete the party's "
                + "punch_a_tree within twenty seconds, though the owner is holding none."
                + "\n  This is the party bug: the engine counted in one member's inventory, chosen by "
                + "player-list order, instead of in every member's."
                + "\n  the friend's task 0 is recorded at " + recordedTaskFor(friend, "punch_a_tree", 0)
                + " and the owner's resolution says " + stateFor(player, "punch_a_tree"));

        assertEquals(QuestState.COMPLETED, stateFor(player, "punch_a_tree"),
                "read from the owner, the same quest is complete -- there is one record, so there is "
                        + "nothing for the two of them to disagree about");

        // Collected by the member who did NOT gather, and deliberately.
        //
        // The payout belongs to the party's *record*, not to the inventory that happened to satisfy it,
        // so either member can collect theirs -- and now that claiming is separate from completing,
        // "who gets it" has the only honest answer: whoever asks. This test asks from the side a "pay
        // the gatherer" shortcut would get wrong, which is what makes it worth asserting rather than
        // the other way round.
        assertEquals(0, countInInventoryOf(friend, Items.WOODEN_AXE),
                "nothing is handed over at completion, to either member");
        assertEquals(0, countInInventoryOf(player, Items.WOODEN_AXE), "to either of them");

        HeadlessServer.Outcome claimed = asOperator("/tenet claim punch_a_tree");
        assertEquals(1, claimed.result(),
                () -> "a party's finished quest should be collectable by either member. It said:\n"
                        + claimed.text());
        assertEquals(1, countInInventoryOf(player, Items.WOODEN_AXE),
                "the member who asked receives the reward");

        // And the other member collects their own copy: progress is the team's, a payout is each
        // player's. This is the legacy model -- one reward each, not one per team -- and the half
        // that a single team-wide "claimed" flag could never express.
        HeadlessServer.Outcome friendClaim = asOperator(friend, "/tenet claim punch_a_tree");
        assertEquals(1, friendClaim.result(),
                () -> "the member who gathered has their own copy to collect. It said:\n"
                        + friendClaim.text());
        assertEquals(1, countInInventoryOf(friend, Items.WOODEN_AXE), "the friend's own axe");
        assertEquals(1, countInInventoryOf(player, Items.WOODEN_AXE),
                "and the owner keeps theirs -- neither claim settled the other's");

        // Neither of them can claim again; each has their copy, once.
        assertRefused(asOperator("/tenet claim punch_a_tree"),
                "the owner has already collected their copy");
        assertRefused(asOperator(friend, "/tenet claim punch_a_tree"),
                "and so has the friend");
    }

    @Test
    @Order(12)
    @DisplayName("and with the other player gathering, which no single chosen member could pass twice")
    void theOtherPlayerGatheringWorksToo() {
        clearInventories();

        server.onServerThread(() -> {
            player.getInventory().add(ItemStacks.stackOf(Items.CRAFTING_TABLE, 1));
            player.getInventory().setChanged();
        });

        // make_a_table is the next quest with an item task, and it depends on punch_a_tree -- which
        // the party completed in the previous test, so this also checks that a party's completion
        // unlocks the next quest for the party rather than for one of its members.
        boolean completed = tickUntil(() -> stateFor(player, "make_a_table") == QuestState.COMPLETED,
                Duration.ofSeconds(20));

        assertTrue(completed, () -> "the owner holding a crafting table did not complete the party's "
                + "make_a_table. punch_a_tree is " + stateFor(player, "punch_a_tree")
                + " and make_a_table is " + stateFor(player, "make_a_table"));

        assertEquals(QuestState.COMPLETED, stateFor(friend, "make_a_table"),
                "and the other member sees it too");

        // And the other way round again: the friend collects this time, so between these two tests the
        // gatherer *and* the collector both reverse. Neither "pay whoever gathered" nor "pay one chosen
        // member" can pass both.
        HeadlessServer.Outcome claimed = asOperator(friend, "/tenet claim make_a_table");
        assertEquals(1, claimed.result(),
                () -> "the owner gathered the crafting table, so the friend should still be able to "
                        + "collect the payout. It said:\n" + claimed.text());
        assertEquals(8, countInInventoryOf(friend, Items.STICK),
                "the friend asked, so the friend received");
        assertEquals(0, countInInventoryOf(player, Items.STICK),
                "and the member who gathered does not");
    }

    @Test
    @Order(13)
    @DisplayName("leaving takes the party's progress with you, and the party keeps its own copy")
    void leavingTakesThePartysProgressWithYou() {
        // Through the command, which is a player's own route and the one this file can assert the
        // retained copy on: the team listener that also calls `retainFor` is registered from
        // SERVER_STARTED, which no test JVM fires -- see order 23's note on the same gap. The
        // command-side call is what the leave subcommand performs on a live server too, where both
        // run and the merge is idempotent.
        HeadlessServer.Outcome leave = asOperator(friend, "/tenet party leave");
        assertEquals(1, leave.result(),
                () -> "the friend was in a real party, so leaving should have worked:\n" + leave.text());

        assertEquals(friend.getUUID(), ownerOf(friend),
                "an unpartied player's progress is keyed by their own id again");
        assertEquals(partyId, ownerOf(player), "and the party is unaffected by somebody leaving it");

        assertEquals(QuestState.COMPLETED, stateFor(player, "punch_a_tree"),
                "the party keeps what it did together -- the record is not moved, it is copied");
        assertEquals(QuestState.COMPLETED, stateFor(friend, "punch_a_tree"),
                "**and the friend keeps it too**: the nodes earned while in the party are retained on "
                        + "departure, so a progression-gated pack cannot soft-lock somebody who did a "
                        + "chain with friends and then went solo. `ProgressStore.retainFor` merges the "
                        + "party's record into the leaver's own, and merging on *join* is still refused "
                        + "-- that direction would hand a fresh party a finished questline");
        assertEquals(QuestState.COMPLETED, stateFor(friend, "make_a_table"),
                "all the way down what the party finished, not only the first quest");

        // And the copy is scoped to the *party's* record, not to what any member had done. The owner
        // played through to the_underground before the party existed and the friend never did, so a
        // merge that took the owner's record -- or the union of everything every member had ever
        // done -- would hand it over here.
        assertNotEquals(QuestState.COMPLETED, stateFor(friend, "the_underground"),
                "retention copies what the party did, not what the owner did alone: merging anything "
                        + "wider would hand a departing member a questline they never touched");
        // While the owner is still in the party, their own view *is* the party's record, so nothing
        // about their solo record can be read from here -- order 14 checks it after they leave, which
        // is the only moment it becomes the record they read again.

        note("after " + friend.getScoreboardName() + " left: the party sees punch_a_tree="
                + stateFor(player, "punch_a_tree") + ", and their own retained record says "
                + stateFor(friend, "punch_a_tree") + " -- party-earned nodes kept, solo record unlowered");
    }

    @Test
    @Order(14)
    @DisplayName("a player who partied and then left still has everything they did alone")
    void soloProgressSurvivesAParty() {
        // The friend left in the previous test, so the party is now the owner on their own -- and
        // Armature removes a team once its last member leaves rather than leaving an empty one.
        boolean left = server.callOnServerThread(() -> Teams.of(server.server()).leave(player.getUUID()));
        assertTrue(left, "the owner was in a party, so leaving should have done something");

        assertEquals(player.getUUID(), ownerOf(player),
                "so this player is solo again, keyed by their own id");

        assertEquals(QuestState.COMPLETED, stateFor(player, "punch_a_tree"),
                "and everything from the solo playthrough in orders 5 to 10 is still there -- the "
                        + "party never touched it, in either direction");
        assertEquals(QuestState.COMPLETED, stateFor(player, "the_underground"),
                "all the way to the end of the questline");

        note("after the owner left: solo progress is intact, punch_a_tree="
                + stateFor(player, "punch_a_tree") + " the_underground=" + stateFor(player, "the_underground"));
    }

    @Test
    @Order(15)
    @DisplayName("and here is what that does not prove, said plainly rather than implied")
    void whatThePartyTestsDoNotProve() {
        // Not an assertion about Tenet; an assertion that a claim is not being overclaimed. The
        // harness's own server reports isDedicatedServer() false, because it subclasses
        // MinecraftServer rather than DedicatedServer -- see HeadlessServer for why. So "two players
        // on a dedicated server share progress" is verified for every part Tenet owns and not for
        // the loader's dedicated-server bootstrap.
        //
        // Checked rather than assumed: `isDedicatedServer` appears in exactly one file under
        // tenet/common/src/main, and it is HeadlessServer's own override. No Tenet code branches on
        // it, so there is no dedicated-server-only path here that this test is skipping.
        boolean dedicated = server.callOnServerThread(() -> server.server().isDedicatedServer());
        assertFalse(dedicated,
                "if this ever becomes true, the note above is stale and the harness has changed shape");

        note("NOT PROVEN by these tests: that the same progress-sharing holds when the server is a "
                + "real dedicated server with a real client attached. Every part Tenet owns is "
                + "covered, and nothing in Tenet branches on isDedicatedServer -- but the loader's "
                + "dedicated bootstrap and the client's packet decode are not exercised here. That "
                + "needs a real client, and it is the one item on the list that does.");
    }

    @Test
    @Order(16)
    @DisplayName("a tick that finds nothing new reports nothing, so the sync is not a packet per tick")
    void anIdleTickReportsNothing() {
        // The other half of the fix, and the half that makes it affordable. The engine runs on a player
        // tick -- twenty times a second, per player -- so "tell the clients when it moves" is only
        // acceptable because it almost never moves. If `tick` reported a change every time it was
        // called, this fix would be a progress packet per player per tick, forever, and nobody would
        // see it in a test that only checked the bits that do change.
        //
        // Everything above is settled by now: the questline is complete, the player has left the party,
        // and nothing in any inventory is going to move again. So a tick eventually reports nothing --
        // "eventually" rather than "immediately", because the state before this test is whatever the
        // tests above left, and one of them reset a quest that a tick may legitimately re-complete.
        long deadline = System.nanoTime() + Duration.ofSeconds(15).toNanos();
        boolean idle = false;
        while (System.nanoTime() < deadline) {
            tickOnce();
            if (lastTick.isEmpty()) {
                idle = true;
                break;
            }
            sleep(100);
        }

        assertTrue(idle, () -> "fifteen seconds of ticks and every one reported a change, so every one "
                + "would have sent a progress packet to every member of the team. The last reported "
                + lastTick + " -- a change that repeats forever is a value being written per tick "
                + "rather than a quest being completed.");

        note("an idle tick reports nothing: the engine's own output is empty, so the sync it drives "
                + "costs nothing on the ticks where nothing happened");
    }

    // ------------------------------------------------------------------
    // The two pushes the tick cannot make
    // ------------------------------------------------------------------

    /**
     * <h2>Why these are separate from the tick's push, which is already covered</h2>
     *
     * <p>Order 5 asserts that the engine's automatic push happens — a tick completes a quest and
     * reports the team as changed, which is what fixed "the quest book still says 0/8". That push goes
     * through {@code ProgressService.tick}, and the two tests below are about the changes a tick
     * <b>cannot</b> see at all. Both were half-built in the committed code and neither had a test:
     *
     * <ol>
     *   <li><b>A command.</b> {@code evaluateTeam} skips every quest that is not playable, so a quest
     *       that is already COMPLETED is never reported as changed again, however often it is ticked.
     *       {@code /tenet reset} and {@code /tenet claim} both move something a client is showing and
     *       are both invisible to the tick. {@code /tenet complete} likewise.</li>
     *   <li><b>A team change.</b> {@code REASON_TEAM_CHANGED} existed, {@code sendProgress} implemented
     *       the full-sync-on-team-change branch, and nothing ever sent one — the two listeners on
     *       Armature's team events logged and returned.</li>
     * </ol>
     *
     * <p>Both are asserted by counting messages, not by inspecting the wire, and the reason is stated
     * where the counter lives: a loader is absent here, so "did a message get produced" is the only
     * question a test can ask, and counting at the one place a message is produced means a route
     * nobody thought of is counted too.
     */

    @Test
    @Order(17)
    @DisplayName("a command that moves progress pushes it, which the tick could never have done for it")
    void aCommandPushesTheChangeTheTickCannotSee() {
        // the_underground was completed back in order 7, so before this it is COMPLETED -- which is
        // exactly the state `evaluateTeam` skips, so no amount of ticking would ever report it again.
        assertEquals(QuestState.COMPLETED, stateOf("the_underground"),
                "this test is about a change the tick cannot see, so it has to start from the state "
                        + "that makes it invisible: an already-completed quest");

        int before = messagesSent(ProgressSyncPayload.REASON_CHANGED);
        int beforeToPlayer = messagesSentTo(ProgressSyncPayload.REASON_CHANGED, player);

        HeadlessServer.Outcome reset = asOperator("/tenet reset the_underground");
        assertEquals(1, reset.result(),
                () -> "/tenet reset the_underground should have cleared one quest. It said:\n"
                        + reset.text());

        assertTrue(stateOf("the_underground").isPlayable(),
                "and the reset moved it out of COMPLETED, which is the whole point: the state it was "
                        + "in produces no tick, and the state it is in now produces one. So the client "
                        + "had to be told by the command, or it keeps drawing a finished quest");

        assertEquals(before + 1, messagesSent(ProgressSyncPayload.REASON_CHANGED),
                "resetting a quest should have produced exactly one progress message. Zero means the "
                        + "command changed something a client is drawing and told nobody, and the tick "
                        + "cannot make up the difference -- see this test's note on why");
        assertEquals(beforeToPlayer + 1,
                messagesSentTo(ProgressSyncPayload.REASON_CHANGED, player),
                "and it reaches the player who ran the command, which is the sender's own team");

        // The half that keeps this from becoming "one message per command, whether or not anything
        // happened". A refusal returns before the push, so a command that changed nothing is silent --
        // and this counts every reason rather than just REASON_CHANGED, because "silent" has to mean
        // no message at all. Counting one reason would pass while a push under another reason happened.
        int after = messagesSent();
        HeadlessServer.Outcome refused = asOperator("/tenet reset no_such_quest_at_all");
        assertRefused(refused, "there is no quest by that name, so nothing was cleared");
        assertEquals(after, messagesSent(),
                "a command that changed nothing must push nothing, for any reason. A message here means "
                        + "the push happens before the command knows whether it did anything, which turns "
                        + "every mistyped id into a progress packet to the whole party");
    }

    @Test
    @Order(18)
    @DisplayName("both sides of a party change are told, with the reason that forces a full sync")
    void aPartyChangeIsPushedToBothSidesOfIt() {
        // Driven by hand, and this is a real limitation stated rather than glossed. Tenet's listeners
        // on Armature's team events are registered from `Tenet.listenToTeams`, which is hooked to
        // SERVER_STARTED -- and that event is fired by loader code in each loader's subproject, so no
        // test JVM ever fires it. `Tenet`'s own note on that guard says as much: "nothing in Tenet's
        // tests can come to depend on a team event having been subscribed, because it never is there".
        //
        // So the team events fired by the Teams calls above reach no listener at all, and the push
        // those listeners perform is exercised by calling the method they call with the same arguments
        // they pass. What is left unasserted is that the *subscription* happened, which nothing in this
        // repo can reach -- the same shape of gap as the loader's transport.
        UUID secondParty = server.callOnServerThread(() -> {
            var teams = Teams.of(server.server());
            var party = teams.create("the second playthrough party", player.getUUID());
            assertTrue(teams.invite(party.id(), friend.getUUID()),
                    "the friend should have been invited to the second party");
            return party.id();
        });

        assertEquals(Optional.of(secondParty),
                server.callOnServerThread(() -> Teams.of(server.server())
                        .acceptInvite(friend.getUUID()).map(team -> team.id())),
                "and is now in it, so there are two members for a change to be news to");

        assertTrue(server.callOnServerThread(() -> Teams.of(server.server()).leave(friend.getUUID())),
                "leaving a party they were in should have done something");

        assertEquals(friend.getUUID(), ownerOf(friend),
                "and their progress is keyed by their own id again -- which is the whole reason the "
                        + "leaver has to be named separately. MEMBER_LEFT carries the team as it now is, "
                        + "and that team no longer contains them, so walking it would tell everybody "
                        + "except the one player whose progress actually moved");

        assertEquals(0, messagesSentTo(ProgressSyncPayload.REASON_TEAM_CHANGED, player),
                "nothing above pushed a team-change message: the events fired with no listener "
                        + "attached, so every message counted below is this test's own doing and the "
                        + "assertions are not passing on somebody else's traffic");
        assertEquals(0, messagesSentTo(ProgressSyncPayload.REASON_TEAM_CHANGED, friend),
                "and the same for the other member");

        int before = messagesSent(ProgressSyncPayload.REASON_TEAM_CHANGED);

        // The same call `Tenet.listenToTeams`'s MEMBER_LEFT listener makes: the team, and the player
        // who has left it.
        server.callOnServerThread(() -> {
            Teams.of(server.server()).byId(secondParty).ifPresent(team ->
                    TenetNetworking.sendTeamChange(server.server(), team, friend.getUUID()));
            return null;
        });

        assertEquals(before + 2, messagesSent(ProgressSyncPayload.REASON_TEAM_CHANGED),
                "one team change should produce one message per person who has to hear it, so two for "
                        + "a party of two. Four means both members were told twice -- which is what a "
                        + "disband does if the listener walks the team, since each member's own "
                        + "MEMBER_LEFT carries the whole team");
        assertEquals(1, messagesSentTo(ProgressSyncPayload.REASON_TEAM_CHANGED, player),
                "the member still in the party is told, because the team's progress is theirs and the "
                        + "leaver arriving with their own share is what can change it");
        assertEquals(1, messagesSentTo(ProgressSyncPayload.REASON_TEAM_CHANGED, friend),
                "and the player who left is told exactly once. **If this count is 0, the leaver is the "
                        + "one person a team-only push can never reach** -- which is the bug this "
                        + "parameter exists to prevent, and it is the worst possible shape for it: "
                        + "everybody still in the party gets corrected, and the player whose screen is "
                        + "now showing the wrong team's questline is the one who never hears");

        // Note what is deliberately not asserted: that the message was a *full* sync. The reason code
        // is on the wire and `QuestSync.sendProgress` branches on it, but whether the bytes were a full
        // set or a delta is only visible through `chunk.full()`, and this test has no client to decode
        // it. `QuestSyncTest` covers the branch itself; this covers that it is reached with the right
        // reason, which is the half that was broken.
        note("a team change pushed " + messagesSent(ProgressSyncPayload.REASON_TEAM_CHANGED)
                + " message(s) for a party of two, one to each member, tagged "
                + "REASON_TEAM_CHANGED so the client is sent the whole questline rather than a delta "
                + "against the team it has just stopped being in");
    }

    // ------------------------------------------------------------------
    // T4's other half: /tenet party, and the modes it sets
    // ------------------------------------------------------------------

    /**
     * <h2>Why the party is built by command here when orders 11 to 14 built one through the API</h2>
     *
     * <p>Because those two are not the same test, and the difference is the whole of what this round
     * added. Orders 11 to 14 drive {@code Teams.of(server)} directly, which proves the <i>engine</i>
     * shares progress across a team. Everything below goes through {@code /tenet party}, which is a
     * different set of code on top of the same API — and the parts of it that can be wrong are the
     * parts a command adds: a permission question answered the wrong way, an argument read under the
     * wrong name, a mode written to the wrong team's key.
     *
     * <h2>What is deliberately not asserted, and it is the same gap as the team listeners</h2>
     *
     * <p>Nothing here counts the messages a party command produces. It cannot: the stored source
     * answers {@code firesEvents()} true, so {@code pushMembershipChange} correctly declines to push
     * and leaves it to the event — and those listeners are registered from {@code SERVER_STARTED},
     * which no test JVM fires. See order 18. So a party built by command here tells nobody anything,
     * and that is the harness's shape rather than a fault in the command.
     *
     * <p>What <i>is</i> asserted is everything the command is responsible for that has a state to
     * read back: the party exists, the player's progress moved onto it, and the mode reached the store
     * and then the engine.
     */

    @Test
    @Order(19)
    @DisplayName("/tenet party creates a real party, and moves the creator's progress onto it")
    void thePartyCommandCreatesARealParty() {
        // Out of whatever party a previous test left this player in, and *through the command*, so that
        // `leave` is exercised by the same route a player uses rather than only through Armature's API.
        //
        // This line is load-bearing and was missing on the first run, which failed here with an owner
        // id that was neither this player's nor anything the test had made: order 18 creates a party to
        // prove the team-change push and never disbands it, so "starts from a solo player" was simply
        // false. The fix is not to assume the state but to establish it -- and establishing it through
        // `leave` covers a subcommand that nothing else does.
        HeadlessServer.Outcome leave = asOperator("/tenet party leave");
        assertEquals(1, leave.result(),
                () -> "this player should have been in a party to leave. It said:\n" + leave.text());

        assertEquals(player.getUUID(), ownerOf(player),
                "so this player is solo again, and their progress is keyed by their own id");

        HeadlessServer.Outcome created = asOperator("/tenet party create the-command-party");
        assertEquals(1, created.result(),
                () -> "/tenet party create should have formed a party. It said:\n" + created.text());

        // The assertion that matters, and it is the one a command can get wrong in a way nothing else
        // notices: the creator's *progress owner* has to have moved. A party that exists while its
        // owner's progress stays keyed to their solo id is a party whose questline is invisible to
        // everybody in it, and every line of output above would still look right.
        commandPartyId = ownerOf(player);
        assertNotEquals(player.getUUID(), commandPartyId,
                "the creator's progress must now be keyed by the party, not by themselves -- otherwise "
                        + "they are in a party whose progress nobody in it can see");

        assertTrue(server.callOnServerThread(() -> Teams.of(server.server())
                        .realTeamOf(player.getUUID()).isPresent()),
                "and a real team exists for them, rather than a synthesised solo one");

        // Fresh, so the party starts empty rather than inheriting the four quests this player has
        // already completed alone. That is ProgressStore's deliberate rule and worth re-checking here,
        // because a command that passed the wrong id would look exactly like a party that inherited.
        assertNotEquals(QuestState.COMPLETED, stateOf("punch_a_tree"),
                "a new party's progress is empty, whatever its creator did before it");

        HeadlessServer.Outcome again = asOperator("/tenet party create another-one");
        assertRefused(again, "the player is already in a party");
        assertEquals(commandPartyId, ownerOf(player), "and the refusal changed nothing");

        HeadlessServer.Outcome info = asOperator("/tenet party");
        assertEquals(1, info.result(), () -> "/tenet party should report. It said:\n" + info.text());

        // Read from the manager rather than from the command's output, and that is the rule this file
        // states at the top: a dedicated server has no language file, so `getString()` on a translatable
        // returns the *key* -- which is why nothing here asserts on output text. An earlier version of
        // this line checked that the listing contained the word "stored", which could never have passed
        // whatever the command did.
        assertEquals("stored", server.callOnServerThread(() -> Teams.of(server.server()).name()),
                "the listing's source line is about naming which mod provides these parties, and this "
                        + "is the answer it reports -- read here rather than from the rendered string");

        note("the party command formed a real party; progress is now keyed by " + commandPartyId
                + " rather than by " + player.getUUID());
    }

    @Test
    @Order(20)
    @DisplayName("a party's progress mode is set by command, and read back from the store")
    void theModeIsSetByCommand() {
        assertEquals(PartyMode.DEFAULT, modeOf(player),
                "a new party counts the default way, which is what the engine did before there was a "
                        + "choice -- so no file and no command has to exist for sharing to work");

        HeadlessServer.Outcome read = asOperator("/tenet party mode");
        assertEquals(1, read.result(), () -> "/tenet party mode should report. It said:\n" + read.text());

        HeadlessServer.Outcome bad = asOperator("/tenet party mode nonsense");
        assertRefused(bad, "there is no such mode");
        assertEquals(PartyMode.DEFAULT, modeOf(player),
                "and a refused mode writes nothing -- a typo must not leave a party counting by a rule "
                        + "nobody chose");

        // A window, so the counts below are this command's and not the fixture's traffic.
        server.callOnServerThread(() -> {
            TenetNetworking.forgetRosters();
            return null;
        });

        HeadlessServer.Outcome set = asOperator("/tenet party mode pooled");
        assertEquals(1, set.result(),
                () -> "/tenet party mode pooled should have been accepted. It said:\n" + set.text());
        assertEquals(PartyMode.POOLED, modeOf(player), "and the store has it");

        // The party is told, because the panel draws the rule as a line of text rather than as a button
        // a player presses and watches. Without the push the row would keep reading the old rule until
        // something else moved a member -- which is exactly how the cycling button read as doing
        // nothing, one round ago.
        assertEquals(1, server.callOnServerThread(() -> TenetNetworking.rostersSentTo(player.getUUID())),
                "setting the mode should have pushed the roster that carries it");

        // Setting it again is a no-op rather than a change, so it reports 0 -- the same convention
        // every other command here follows, where the return value is the assertion.
        HeadlessServer.Outcome same = asOperator("/tenet party mode pooled");
        assertRefused(same, "nothing changed, so nothing happened worth reporting");
        assertEquals(PartyMode.POOLED, modeOf(player), "and it is still set");
        assertEquals(1, server.callOnServerThread(() -> TenetNetworking.rostersSentTo(player.getUUID())),
                "and a refusal is not news, so nothing was sent for it");

        HeadlessServer.Outcome back = asOperator("/tenet party mode one_member");
        assertEquals(1, back.result(), "an explicit return to the default is a change and reports one");
        assertEquals(PartyMode.ONE_MEMBER, modeOf(player));
        assertEquals(2, server.callOnServerThread(() -> TenetNetworking.rostersSentTo(player.getUUID())),
                "and the second real change was pushed as well as the first");

        // Stored and greppable, which is the half a party's mode has to survive: a restart re-reads
        // this file, and a reader opening it should be able to see why a party counts as it does.
        assertTrue(server.callOnServerThread(() ->
                        PartyStore.of(server.server()).has(commandPartyId)),
                "a party that has chosen is recorded, so the choice outlives the session");

        note("the mode round-tripped through the command and the store; a typo was refused and wrote "
                + "nothing");
    }

    @Test
    @Order(21)
    @DisplayName("the mode reaches the engine: one_member cannot finish what pooled can")
    void theModeChangesWhatTheEngineCounts() {
        // The whole point of the round, in one test. Everything above proves the mode is stored and
        // readable; this proves the engine applies it, which is the only reason it exists.
        //
        // Two members of one party, four oak logs each, and punch_a_tree wants eight. Under the
        // default neither member has eight, so the party has not finished it; under pooled they have
        // eight between them, so it has. The two modes disagree about the *same two inventories*, which
        // is what makes this an assertion about the mode rather than about the items.
        assertTrue(server.callOnServerThread(() -> {
                    Teams.of(server.server()).invite(commandPartyId, friend.getUUID());
                    return Teams.of(server.server()).acceptInvite(friend.getUUID()).isPresent();
                }),
                "the friend should have joined the command-created party");

        assertEquals(commandPartyId, ownerOf(friend),
                "both members resolve to the party, so there is one progress record between them");

        clearInventories();
        server.onServerThread(() -> {
            player.getInventory().add(ItemStacks.stackOf(Items.OAK_LOG, 4));
            player.getInventory().setChanged();
            friend.getInventory().add(ItemStacks.stackOf(Items.OAK_LOG, 4));
            friend.getInventory().setChanged();
        });

        assertEquals(4, countInInventoryOf(player, Items.OAK_LOG));
        assertEquals(4, countInInventoryOf(friend, Items.OAK_LOG));

        // Tick for a while and check it does NOT complete. Ticking first and asserting the negative
        // afterwards is the order that makes this mean something: an assertion that the quest is
        // incomplete, with no ticks in between, would pass on a party the engine had never looked at.
        tickUntil(() -> false, Duration.ofSeconds(3));

        assertEquals(PartyMode.ONE_MEMBER, modeOf(player), "this half runs under the default");
        assertEquals(QuestState.LOCKED, stateOf("the_underground"),
                "and the party's questline starts where a locked quest starts");
        assertNotEquals(QuestState.COMPLETED, stateOf("punch_a_tree"),
                () -> "four logs each is four logs a member for the default mode, and this quest wants "
                        + "eight. It completed anyway, which means the mode is being ignored and the "
                        + "members' counts are being added regardless."
                        + "\n  the owner's task 0 is recorded at "
                        + recordedTaskFor(player, "punch_a_tree", 0));

        HeadlessServer.Outcome set = asOperator("/tenet party mode pooled");
        assertEquals(1, set.result(), () -> "setting the mode should have worked:\n" + set.text());

        boolean completed = tickUntil(() -> stateFor(player, "punch_a_tree") == QuestState.COMPLETED,
                Duration.ofSeconds(20));

        assertTrue(completed, () -> "with the same four logs each, pooled mode did not complete "
                + "punch_a_tree, which needs eight. The two counts add to exactly eight, so this is "
                + "the mode not reaching the engine rather than the arithmetic being wrong -- see "
                + "PartyModeTest, which pins the arithmetic on its own."
                + "\n  the owner's task 0 is recorded at "
                + recordedTaskFor(player, "punch_a_tree", 0)
                + " and the state is " + stateOf("punch_a_tree"));

        assertEquals(QuestState.COMPLETED, stateFor(friend, "punch_a_tree"),
                "and both members see it, because there is one record and the mode decided it");

        note("four oak logs each: refused under one_member, completed under pooled. The same two "
                + "inventories, two different answers, which is the mode being applied rather than "
                + "described");

        // ---- and now the third mode, on a different quest, so the completed one is not in the way.

        HeadlessServer.Outcome ownerOnly = asOperator("/tenet party mode owner_only");
        assertEquals(1, ownerOnly.result(),
                () -> "setting owner_only should have worked:\n" + ownerOnly.text());

        clearInventories();
        server.onServerThread(() -> {
            friend.getInventory().add(ItemStacks.stackOf(Items.CRAFTING_TABLE, 1));
            friend.getInventory().setChanged();
        });

        tickUntil(() -> false, Duration.ofSeconds(3));

        assertEquals(0, countInInventoryOf(player, Items.CRAFTING_TABLE),
                "the owner is holding nothing");
        assertEquals(1, countInInventoryOf(friend, Items.CRAFTING_TABLE), "and the friend is holding one");
        assertNotEquals(QuestState.COMPLETED, stateFor(player, "make_a_table"),
                () -> "owner_only counts the owner and nobody else, so the friend holding the crafting "
                        + "table must not complete make_a_table. It did, which means the mode is being "
                        + "ignored for the second quest after being honoured for the first."
                        + "\n  the owner's task 0 is recorded at "
                        + recordedTaskFor(player, "make_a_table", 0));

        server.onServerThread(() -> {
            player.getInventory().add(ItemStacks.stackOf(Items.CRAFTING_TABLE, 1));
            player.getInventory().setChanged();
        });

        boolean ownerCompleted = tickUntil(
                () -> stateFor(player, "make_a_table") == QuestState.COMPLETED, Duration.ofSeconds(20));

        assertTrue(ownerCompleted, () -> "the owner holding the crafting table should complete "
                + "make_a_table under owner_only. The state is " + stateOf("make_a_table"));

        note("and under owner_only the friend's crafting table counted for nothing until the owner "
                + "held one -- so all three modes reach the engine");
    }

    @Test
    @Order(22)
    @DisplayName("a party's roster can be read back off the server, which is what the client is sent")
    void aPartysRosterIsReadBackOffTheServer() {
        // **The regression test for the fault this round is about**, and it is the whole of it in one
        // assertion. `PartySnapshot.of` asked Armature for `teamOf(teamId)` — and `teamOf` takes a
        // *player's* id, so handing it a team id found no such player, found no team, and synthesised
        // `Team.solo(teamId)`, whose `persistent` is false. The guard below that turned every real
        // party into "nobody is in a party", so `sendPartyToTeam` returned at its first line and no
        // roster was ever put on the wire. The panel was correct and was never told anything.
        //
        // So this asserts the one thing that was false: a party that really exists, looked up by its
        // own id, reads back as a party with members in it. Nothing about the client, nothing about
        // the panel -- just the lookup, because that is where the fault was and a test that went
        // through the UI would be able to fail in more places than the one being described.
        //
        // The party is order 19's, created by `/tenet party create`, and it has the friend in it from
        // order 21 — so this is a party of two real players rather than a fixture.
        assertNotNull(commandPartyId, "order 19 should have created a party for this test to read");

        PartySnapshot snapshot = server.callOnServerThread(
                () -> PartySnapshot.of(server.server(), commandPartyId));

        assertTrue(snapshot.isPresent(),
                "the party " + commandPartyId + " exists on this server and has members, so reading "
                        + "it back by its own id must not report that nobody is in it. An empty or "
                        + "absent snapshot here is the reported fault: the roster never reaches a "
                        + "client, so the party panel draws its empty state for a party that exists");

        assertEquals(commandPartyId, snapshot.teamId(),
                "and it is *this* party that was read, not a synthesised team of one keyed by the same "
                        + "id -- which is precisely what asking for a team by a player's id produces");

        assertEquals(2, snapshot.members().size(),
                "two members: the player who created it in order 19, and the friend who joined in "
                        + "order 21. One means the lookup found something other than the real team");

        assertTrue(snapshot.members().stream().anyMatch(member -> member.id().equals(player.getUUID())),
                "the creator is in the roster the server would send");
        assertTrue(snapshot.members().stream().anyMatch(member -> member.id().equals(friend.getUUID())),
                "and so is the friend, so this is the party rather than a party of one");

        // Read through the format the client actually receives, so this covers the packing as well as
        // the lookup. A snapshot that read back correctly and packed into something the client could
        // not parse would pass every assertion above and leave the panel empty anyway.
        PartySnapshot roundTripped = PartySnapshot.unpack(snapshot.pack());
        assertEquals(2, roundTripped.members().size(),
                "the roster survives the wire format the payload carries it in");

        note("the party's roster read back off the server with " + roundTripped.members().size()
                + " member(s) and survived the packed form the client is sent -- the lookup that was "
                + "returning nobody's-team for every real party now returns the party");
    }

    @Test
    @Order(23)
    @DisplayName("a disband names every member, and the roster it sends is what an open panel redraws from")
    void aDisbandTellsEveryMemberTheyAreInNoParty() {
        // **Driven by hand, for the same reason order 18 is**, and the limitation is the one stated
        // there: Tenet's listeners on Armature's team events are registered from
        // `Tenet.listenToTeams`, which hangs off SERVER_STARTED, and no loader fires that in a test
        // JVM. So `disband` below fires one MEMBER_LEFT per member into no listener at all, and the
        // push the listener's DISBANDED branch performs is exercised by calling the method it calls,
        // once per member, with the arguments that branch passes.
        //
        // What that leaves unasserted is the subscription. What it pins is the thing that was wrong:
        // the disband branch sent every member their progress and returned, so no client was ever told
        // the party was gone. That was invisible while the party panel closed on every press, and it is
        // a panel describing a party that does not exist now that the panel stays open -- see
        // `TenetNetworking.rostersSentTo`.
        assertNotNull(commandPartyId, "orders 19-22 should have left a party of two to disband");

        List<UUID> members = server.callOnServerThread(() -> Teams.of(server.server())
                .byId(commandPartyId)
                .map(team -> List.copyOf(team.memberIds()))
                .orElse(List.of()));
        assertEquals(2, members.size(),
                "the party orders 19-21 built should have two members for a disband to be news to: "
                        + members);

        server.callOnServerThread(() -> {
            TenetNetworking.forgetRosters();
            return null;
        });

        HeadlessServer.Outcome disbanded = asOperator("/tenet party disband");
        assertEquals(1, disbanded.result(),
                () -> "the owner should have been able to disband their own party:\n"
                        + disbanded.text());
        assertTrue(server.callOnServerThread(
                        () -> Teams.of(server.server()).byId(commandPartyId).isEmpty()),
                "and the party is gone, so \"you are in no party\" is the true thing to tell them");

        for (UUID member : members) {
            server.callOnServerThread(() -> {
                TenetNetworking.sendNoPartyTo(server.server(), member);
                return null;
            });
        }

        // One apiece rather than one per member: a disband arrives as one MEMBER_LEFT per member, each
        // naming its own player, so each of them is the whole audience of their own event. A count of
        // two for either would mean something walked the team instead.
        for (UUID member : members) {
            assertEquals(1, server.callOnServerThread(() -> TenetNetworking.rostersSentTo(member)),
                    () -> member + " was not told their party is gone. Their panel is open, so this "
                            + "message is the only thing that can take it back to the empty state -- "
                            + "the disband fault was progress being sent without it");
        }

        note("a disband's members were each told they are in no party -- the roster message an open "
                + "panel redraws its empty state from");
    }

    @Test
    @Order(24)
    @DisplayName("a login is told about its party, and told when it has none")
    void aLoginIsToldAboutItsParty() {
        // **The regression test for a party a rejoin could not see.** The join push used to ask
        // `ProgressService.progressOwner` which team to send -- a second lookup, where every other push
        // in the mod passes a team id it already holds. A manager still reading its store answers that
        // lookup with a synthesised solo team keyed by the *player's* id, `PartySnapshot.of` finds no
        // such team, and `sendPartyToTeam` returns at its first line. The shape on screen was exact: a
        // party every command knew about, and a panel showing the empty state on every login.
        assertNotNull(commandPartyId, "order 19 should have created a party for this to recreate");

        HeadlessServer.Outcome created = asOperator("/tenet party create the-login-party");
        assertEquals(1, created.result(),
                () -> "a party to log in to should have been created:\n" + created.text());

        server.callOnServerThread(() -> {
            TenetNetworking.forgetRosters();
            return null;
        });

        // The exact call the PLAYER_JOIN hook makes.
        server.callOnServerThread(() -> {
            TenetNetworking.sendEverythingTo(player);
            return null;
        });

        assertEquals(1, server.callOnServerThread(() -> TenetNetworking.rostersSentTo(player.getUUID())),
                "logging in with a party has to tell the client which one. Nothing else ever will --"
                        + " the panel is built from that message, and until the next member joins or"
                        + " leaves, the empty state is what it draws");

        HeadlessServer.Outcome left = asOperator("/tenet party leave");
        assertEquals(1, left.result(), () -> "and the party should be leavable:\n" + left.text());

        server.callOnServerThread(() -> {
            TenetNetworking.forgetRosters();
            return null;
        });
        server.callOnServerThread(() -> {
            TenetNetworking.sendEverythingTo(player);
            return null;
        });

        assertEquals(1, server.callOnServerThread(() -> TenetNetworking.rostersSentTo(player.getUUID())),
                "and a login with no party is told that too. A client's cache holds whatever roster it"
                        + " was last sent, so silence here is a panel describing a party from the"
                        + " previous world -- which one client can see two of in a row");

        note("a login with a party was sent its roster, and a login without one was told so -- the two"
                + " halves of the join push, both silent while the panel was the only thing asking");
    }

    // ------------------------------------------------------------------
    // The stage gate
    // ------------------------------------------------------------------

    /** The flag the induction chapter grants: one player's, not the team's. */
    private static final Identifier THE_MARK =
            Identifier.fromNamespaceAndPath("the_induction", "marked");

    @Test
    @Order(25)
    @DisplayName("a stage-gated quest is refused for a player without the stage, and nothing is written")
    void aStageGateShutsTheQuestForAPlayerWithoutIt() {
        // The gate is an overlay, not a dependency: the_mark declares no dependsOn at all, so the
        // engine's own answer for the team is UNLOCKED. What shuts it is `requiresStage`, and that is
        // per player -- the difference this whole feature exists to make.
        assertFalse(hasStage(player, THE_MARK), "this test is about a player who does not have the mark");
        assertEquals(QuestState.UNLOCKED, stateOf("the_mark"),
                "the_mark has no prerequisites, so the stored answer is unlocked -- the gate is not an edge");

        assertTrue(stageLocked().contains("the_mark"),
                "the sync's per-player set should name the_mark as shut for this player: " + stageLocked());

        HeadlessServer.Outcome attempt = asOperator("/tenet complete the_mark");
        assertRefused(attempt, "the_mark requires a stage this player does not have");
        assertNotEquals(QuestState.COMPLETED, stateOf("the_mark"),
                "a refusal must leave the quest unfinished");
        assertEquals(0, recordedTask("the_mark", 0),
                "and must not have recorded the stage task -- a shut gate refuses before anything is written");
    }

    @Test
    @Order(26)
    @DisplayName("claiming the summons' stage reward marks the player, and the gate opens")
    void theStageRewardMarksThePlayerAndTheGateOpens() {
        clearInventories();
        HeadlessServer.Outcome given = asOperator("/give @s minecraft:writable_book");
        assertEquals(1, given.result(), () -> "/give should have worked:\n" + given.text());

        assertTrue(tickUntil(() -> stateOf("the_summons") == QuestState.COMPLETED, Duration.ofSeconds(20)),
                () -> "a writable book did not complete the_summons. It is " + stateOf("the_summons")
                        + " and task 0 is at " + recordedTask("the_summons", 0));

        // The reward is a stage, and it waits for a claim like every other example reward -- so the
        // grant happens at the claim, which is the path a stage reward really takes.
        assertFalse(hasStage(player, THE_MARK), "nothing has claimed the stage yet");

        HeadlessServer.Outcome claimed = asOperator("/tenet claim the_summons");
        assertEquals(1, claimed.result(), () -> "the stage should have been granted:\n" + claimed.text());
        assertTrue(hasStage(player, THE_MARK), "the claim is what grants a stage reward");

        assertFalse(stageLocked().contains("the_mark"),
                "the player holds the stage, so the_mark is no longer shut: " + stageLocked());

        // The other half of the same feature: a stage task is measured, never handed in, so the quest
        // completes by itself the moment the flag is held -- there is no button to press.
        assertTrue(tickUntil(() -> stateOf("the_mark") == QuestState.COMPLETED, Duration.ofSeconds(20)),
                () -> "the_mark's stage task did not satisfy once the stage was held. The quest is "
                        + stateOf("the_mark") + " and task 0 is recorded at " + recordedTask("the_mark", 0));

        note("the stage reward granted " + THE_MARK + " on claim, and the stage task completed the_mark"
                + " with no button pressed");
    }

    @Test
    @Order(27)
    @DisplayName("taking the stage away refuses the claim, and granting it again pays out")
    void aShutGateRefusesTheClaimAndRegrantingPays() {
        assertEquals(QuestState.COMPLETED, stateOf("the_mark"), "order 26 completed it");
        int apples = countInInventory(Items.GOLDEN_APPLE);

        HeadlessServer.Outcome removed = asOperator(
                "/tenet stage remove tenet-tester the_induction:marked");
        assertEquals(1, removed.result(), () -> "the stage should have been taken away:\n" + removed.text());
        assertTrue(stageLocked().contains("the_mark"),
                "with the stage gone the_mark is shut for this player again: " + stageLocked());

        HeadlessServer.Outcome refused = asOperator("/tenet claim the_mark");
        assertRefused(refused, "a shut gate refuses the payout, not only the completion");
        assertEquals(apples, countInInventory(Items.GOLDEN_APPLE), "and nothing was handed over");

        HeadlessServer.Outcome regranted = asOperator(
                "/tenet stage add tenet-tester the_induction:marked");
        assertEquals(1, regranted.result(), () -> "the stage should have been granted:\n" + regranted.text());

        HeadlessServer.Outcome claimed = asOperator("/tenet claim the_mark");
        assertEquals(1, claimed.result(), () -> "the claim should have gone through:\n" + claimed.text());
        assertEquals(apples + 1, countInInventory(Items.GOLDEN_APPLE),
                "granting the stage back is enough to collect -- the work was already done");

        note("a removed stage refused the claim, and granting it back paid out without repeating the task");
    }

    @Test
    @Order(28)
    @DisplayName("a stage-removing reward clears the flag, and the gate reads shut again")
    void aStageRemovingRewardClearsTheFlag() {
        assertTrue(hasStage(player, THE_MARK), "order 27 left the player marked");
        assertEquals(QuestState.UNLOCKED, stateOf("the_fall"),
                "the_fall depends on the completed the_mark");

        HeadlessServer.Outcome submitted = asOperator("/tenet submit the_fall 0");
        assertEquals(1, submitted.result(),
                () -> "the checkmark should have been accepted:\n" + submitted.text());

        HeadlessServer.Outcome claimed = asOperator("/tenet claim the_fall");
        assertEquals(1, claimed.result(), () -> "the fall should have been collected:\n" + claimed.text());
        assertFalse(hasStage(player, THE_MARK), "the_fall's reward takes the stage away");

        // And the flag's absence is the gate's input, so the_mark reads shut for this player again --
        // even though it is completed. The overlay is about what this player may do next, and the
        // stored state is untouched: the fall clears a flag, it does not un-complete the quest.
        assertTrue(stageLocked().contains("the_mark"),
                "cleared, so the gate is shut again: " + stageLocked());
        assertEquals(QuestState.COMPLETED, stateOf("the_mark"),
                "the stored state stays completed while the per-player overlay reads locked");

        note("the fall's remove-stage reward cleared " + THE_MARK + ", and the gate shut again while the"
                + " completed quest stayed completed");
    }

    // ------------------------------------------------------------------
    // Reward tables
    // ------------------------------------------------------------------

    @Test
    @Order(29)
    @DisplayName("a quest's loot reward rolls the seeded table, and the weight-zero entry always lands")
    void aLootRewardRollsTheSeededTable() {
        // The end of the table feature, played: reward_tables/loot.json is seeded beside the book,
        // The Winnings names it with a tenet:loot reward, and claiming the quest rolls it. The
        // weight-zero entry is what makes the assertion exact -- five experience points, granted
        // once per roll, and the only experience in the table, where the item entries are a
        // distribution.
        assertTrue(TenetQuests.rewardTables().containsKey("loot"),
                "the seeded examples should have loaded the loot table; loaded: "
                        + TenetQuests.rewardTables().keySet());

        HeadlessServer.Outcome submitted = asOperator("/tenet submit the_winnings 0");
        assertEquals(1, submitted.result(),
                () -> "the checkmark should have been accepted:\n" + submitted.text());

        int before = server.callOnServerThread(() -> player.totalExperience);
        HeadlessServer.Outcome claimed = asOperator("/tenet claim the_winnings");
        assertEquals(1, claimed.result(),
                () -> "the loot reward should have been collected:\n" + claimed.text());

        int gained = server.callOnServerThread(() -> player.totalExperience) - before;
        assertEquals(5, gained,
                "the weight-zero entry is five experience points, granted once per roll, and it is"
                        + " the only experience in the table -- so the delta is exact where the item"
                        + " entries are a distribution");

        note("a loot reward rolled the seeded table: 5 XP guaranteed, and the weighted entries rolled");
    }

    // ------------------------------------------------------------------
    // Conditions
    // ------------------------------------------------------------------

    /** The flag the gallery's shopping list hands out, and the two quests beside it ask for. */
    private static final Identifier THE_PASSWORD =
            Identifier.fromNamespaceAndPath("condition_gallery", "password");

    /**
     * Sets a stage on the test player, either way, without asserting the result.
     *
     * <p>Setup rather than a check: adding a stage a player already has, or removing one they do not,
     * both return 0, and both leave the world in the state the caller wanted.
     */
    private static void setStage(Identifier stage, boolean present) {
        asOperator((present ? "/tenet stage add tenet-tester " : "/tenet stage remove tenet-tester ")
                + stage);
    }

    @Test
    @Order(30)
    @DisplayName("a conditioned submit is refused without the stage and accepted the moment it is held")
    void aConditionedSubmitIsRefusedUntilTheStageIsHeld() {
        clearInventories();
        setStage(THE_PASSWORD, false);
        assertFalse(hasStage(player, THE_PASSWORD), "the setup must leave the flag unheld");

        HeadlessServer.Outcome refused = asOperator("/tenet submit the_password 0");
        assertRefused(refused, "the task's condition asks for a stage this player does not have");
        assertEquals(0, recordedTask("the_password", 0),
                "a refused submit must not record the checkmark -- the gate is checked at the press");

        setStage(THE_PASSWORD, true);
        HeadlessServer.Outcome accepted = asOperator("/tenet submit the_password 0");
        assertEquals(1, accepted.result(),
                () -> "the same press should be accepted now that the stage is held:\n" + accepted.text());
        assertEquals(QuestState.COMPLETED, stateOf("the_password"),
                "the checkmark was the quest's only task, so the quest is finished");

        note("a conditioned checkmark was refused without the stage and accepted with it -- the gate is "
                + "asked at the press, not only in the tick");
    }

    @Test
    @Order(31)
    @DisplayName("an item condition gates the press, is never consumed, and its reward grants the stage")
    void anItemConditionGatesThePressAndItsRewardGrantsTheStage() {
        clearInventories();
        setStage(THE_PASSWORD, false);

        HeadlessServer.Outcome refused = asOperator("/tenet submit the_shopping_list 0");
        assertRefused(refused, "the condition wants eight cobblestone the player is not holding");
        assertEquals(0, recordedTask("the_shopping_list", 0), "and nothing was recorded");

        HeadlessServer.Outcome given = asOperator("/give @s minecraft:cobblestone 8");
        assertEquals(1, given.result(), () -> "/give should have worked:\n" + given.text());

        HeadlessServer.Outcome accepted = asOperator("/tenet submit the_shopping_list 0");
        assertEquals(1, accepted.result(),
                () -> "the stone is there, so the press should be accepted:\n" + accepted.text());
        assertEquals(8, countInInventory(Items.COBBLESTONE),
                "a condition asks; it never takes. The eight are still in the inventory");

        HeadlessServer.Outcome claimed = asOperator("/tenet claim the_shopping_list");
        assertEquals(1, claimed.result(), () -> "the stage reward should pay:\n" + claimed.text());
        assertTrue(hasStage(player, THE_PASSWORD),
                "the reward is what grants the gallery's password, which the next quest asks for");

        note("an item condition was measured without consuming, and its quest's reward granted "
                + THE_PASSWORD);
    }

    @Test
    @Order(32)
    @DisplayName("a condition gates the tick's own counting, not only a press")
    void aConditionGatesTheTickToo() {
        clearInventories();
        setStage(THE_PASSWORD, false);

        HeadlessServer.Outcome given = asOperator("/give @s minecraft:cobblestone 8");
        assertEquals(1, given.result(), () -> "/give should have worked:\n" + given.text());

        // the_supply is an item task, so no press is involved: tick it with the stone held and the
        // stage missing, and the count must not be taken at all.
        for (int i = 0; i < 5; i++) {
            tickOnce();
            sleep(100);
        }
        assertNotEquals(QuestState.COMPLETED, stateOf("the_supply"),
                "eight cobblestone without the stage must not complete the supply");
        assertEquals(0, recordedTask("the_supply", 0),
                "and must not record a count -- the condition gates the reading, not only the button");

        setStage(THE_PASSWORD, true);
        assertTrue(tickUntil(() -> stateOf("the_supply") == QuestState.COMPLETED, Duration.ofSeconds(20)),
                () -> "the same stone should count once the stage is held. the_supply is "
                        + stateOf("the_supply") + " and its task is at " + recordedTask("the_supply", 0));

        note("the same eight cobblestone counted only once the stage was held -- a condition gates the "
                + "tick, where a checkmark's gate is only ever met at a press");
    }

    @Test
    @Order(33)
    @DisplayName("a scoreboard condition gates a reward, and the objective is born shut at zero")
    void aScoreConditionGatesTheClaim() {
        clearInventories();

        // The objective does not exist when the world starts, which is the state docs/authoring/conditions.md
        // calls out: a missing objective reads as zero, so the gate is shut rather than the quest
        // being broken. Four is deliberately one short of the reward's five.
        HeadlessServer.Outcome added = asOperator("/scoreboard objectives add condition_gallery_standing dummy");
        assertEquals(1, added.result(), () -> "the objective should have been created:\n" + added.text());
        asOperator("/scoreboard players set tenet-tester condition_gallery_standing 4");

        HeadlessServer.Outcome completed = asOperator("/tenet complete the_standing");
        assertEquals(1, completed.result(),
                () -> "an operator's complete is not gated by a task's condition:\n" + completed.text());

        assertRefused(asOperator("/tenet claim the_standing"),
                "the reward's condition wants five points and the player has four");
        assertEquals(0, countInInventory(Items.GOLD_INGOT), "and nothing was paid");

        asOperator("/scoreboard players set tenet-tester condition_gallery_standing 5");
        HeadlessServer.Outcome claimed = asOperator("/tenet claim the_standing");
        assertEquals(1, claimed.result(),
                () -> "five points is the reward's condition, so the claim should pay:\n" + claimed.text());
        assertEquals(1, countInInventory(Items.GOLD_INGOT), "the ingot is the proof it did");

        note("a score condition refused the claim at four and paid at five, read from the scoreboard");
    }

    @Test
    @Order(34)
    @DisplayName("a party-size condition counts the members online now, and a party of one cannot pass it")
    void aPartySizeConditionCountsWhoIsHere() {
        clearInventories();

        // Start from solo, whatever the party tests above left behind: leave() is a no-op for a solo
        // player, which is exactly the state this wants and the reason it can be called blind.
        server.callOnServerThread(() -> {
            Teams.of(server.server()).leave(player.getUUID());
            Teams.of(server.server()).leave(friend.getUUID());
            return null;
        });

        HeadlessServer.Outcome refused = asOperator("/tenet submit the_company 0");
        assertRefused(refused, "a party of one cannot pass a party-of-two condition");
        assertEquals(0, recordedTask("the_company", 0), "nothing recorded, either");

        server.callOnServerThread(() -> {
            var teams = Teams.of(server.server());
            var team = teams.create("the condition party", player.getUUID());
            assertTrue(teams.invite(team.id(), friend.getUUID()), "the friend should be invited");
            return team.id();
        });
        server.callOnServerThread(() -> Teams.of(server.server()).acceptInvite(friend.getUUID()));

        HeadlessServer.Outcome accepted = asOperator("/tenet submit the_company 0");
        assertEquals(1, accepted.result(),
                () -> "with two members online the condition holds:\n" + accepted.text());

        note("a party-size condition refused a solo press and accepted it once a second member was "
                + "online in the same party");
    }

    @Test
    @Order(35)
    @DisplayName("two conditions on a reward are an AND, and the last one is what opens it")
    void twoConditionsOnARewardAreAnAnd() {
        clearInventories();
        // The advancement is one of the two conditions and the harder one to undo, so the setup
        // revokes it first: 0 when it was never granted, which is not a failure here.
        asOperator("/advancement revoke @s only minecraft:story/root");

        HeadlessServer.Outcome completed = asOperator("/tenet complete the_receipt");
        assertEquals(1, completed.result(), () -> "the receipt should complete:\n" + completed.text());

        assertRefused(asOperator("/tenet claim the_receipt"),
                "neither condition holds: no advancement, no logs");
        assertEquals(0, countInInventory(Items.EMERALD), "nothing paid");

        HeadlessServer.Outcome given = asOperator("/give @s minecraft:oak_log 8");
        assertEquals(1, given.result(), () -> "/give should have worked:\n" + given.text());
        assertRefused(asOperator("/tenet claim the_receipt"),
                "eight logs satisfy one condition, and the advancement is still missing -- the list is an AND");

        HeadlessServer.Outcome granted = asOperator("/advancement grant @s only minecraft:story/root");
        assertEquals(1, granted.result(), () -> "the advancement should have been granted:\n" + granted.text());

        HeadlessServer.Outcome claimed = asOperator("/tenet claim the_receipt");
        assertEquals(1, claimed.result(),
                () -> "both conditions hold now, so the claim should pay:\n" + claimed.text());
        assertEquals(1, countInInventory(Items.EMERALD), "the emerald is the proof");

        note("a reward with two conditions refused while one was met and paid with both -- a conditions "
                + "list is an AND");
    }

    @Test
    @Order(36)
    @DisplayName("a claim on a quest nobody has finished is refused and pays nothing")
    void aClaimOnAnUnfinishedQuestPaysNothing() {
        // The hole this pins, found by review: `claim` checked the stage gate, the reward's conditions,
        // claims and blocking -- but never that the quest was finished, so `/tenet claim <any id>` and
        // a forged claim payload were paid the whole reward list of a quest nobody had done.
        // `canClaimFor` has always required COMPLETED, which is why Claim all never reached this; the
        // single claim and the choice answer did.
        clearInventories();
        assertEquals(QuestState.UNLOCKED, stateOf("ameth_start"),
                "the fixture is a gallery quest nothing in this run has touched");

        HeadlessServer.Outcome refused = asOperator("/tenet claim ameth_start");
        assertRefused(refused, "a quest that is not finished owes nothing");
        assertEquals(0, countInInventory(Items.AMETHYST_BLOCK),
                "and the refusal granted nothing");
        assertNotEquals(QuestState.COMPLETED, stateOf("ameth_start"),
                "the claim must not have completed it either");

        note("a claim on an unfinished quest was refused and paid nothing -- the guard `canClaimFor` had "
                + "always implied and the single claim path had not enforced");
    }

    @Test
    @Order(37)
    @DisplayName("a criterion nothing declares reads as not done rather than crashing the tick")
    void aCriterionNothingDeclaresReadsAsNotDone() {
        // The crash this pins: `AdvancementProgress.getCriterion` returns null for a name the
        // advancement does not declare -- which the validator cannot know, since the game's own files
        // decide what exists -- and both the condition and the task dereferenced it. The condition is
        // asked on every tick and every lock refresh, so a one-word typo took the server down.
        //
        // Order 35 granted minecraft:story/root, so the advancement is held and only the criterion is
        // missing: this refusal is that null branch and nothing else. Before the fix, the seeded quest
        // crashed the first tick that evaluated it -- long before this order ran.
        HeadlessServer.Outcome refused = asOperator("/tenet submit the_false_criterion 0");
        assertRefused(refused, "a criterion nothing declares must read as not done, not throw");
        assertEquals(0, recordedTask("the_false_criterion", 0), "and nothing is recorded");

        note("an advancement condition naming a criterion nothing declares was refused rather than "
                + "crashing the tick -- the null branch vanilla's own isCriterionDone guards");
    }

    // ------------------------------------------------------------------
    // The party's own management: rename, transfer, settings, invitations
    // ------------------------------------------------------------------

    /**
     * A party to manage across these orders, and the friend in it where a test needs two seats.
     *
     * <p>Orders 38 to 43 run last of the party work and clean up after themselves: each ends with the
     * player solo again, so nothing here changes what the later orders find. The friend is left solo
     * by each one too, for the same reason.
     */
    @Test
    @Order(38)
    @DisplayName("/tenet party rename changes the name for the owner and refuses everybody else")
    void renameIsOwnerOnly() {
        // From solo, whatever the earlier orders left behind: order 34 forms a party for the
        // party-size condition and never disbands it. `leave` is a no-op for a solo player, so both
        // calls are safe whichever state they find -- the same trick order 34 uses in the other
        // direction.
        server.callOnServerThread(() -> {
            Teams.of(server.server()).leave(player.getUUID());
            Teams.of(server.server()).leave(friend.getUUID());
            return null;
        });

        HeadlessServer.Outcome created = asOperator("/tenet party create rename-party");
        assertEquals(1, created.result(),
                () -> "a party to rename should have been created:\n" + created.text());
        UUID id = ownerOf(player);

        assertEquals(1, asOperator("/tenet party invite tenet-friend").result());
        assertTrue(server.callOnServerThread(() ->
                        Teams.of(server.server()).acceptInvite(friend.getUUID()).isPresent()),
                "the friend should have joined, so the non-owner branch below has somebody in it");

        HeadlessServer.Outcome byMember = asOperator(friend, "/tenet party rename not-yours");
        assertRefused(byMember, "only the owner may rename a party");
        assertEquals("rename-party", nameOf(id), "and the refusal changed nothing");

        String tooLong = "x".repeat(64);
        HeadlessServer.Outcome invalid = asOperator("/tenet party rename " + tooLong);
        assertRefused(invalid, "a name past the limit is refused");
        assertEquals("rename-party", nameOf(id), "and an invalid name writes nothing either");

        HeadlessServer.Outcome renamed = asOperator("/tenet party rename the renamed party");
        assertEquals(1, renamed.result(), () -> "/tenet party rename should have worked:\n" + renamed.text());
        assertEquals("the renamed party", nameOf(id), "read back from the store, which is what a "
                + "roster carries rather than the command's own output");

        note("the party was renamed by its owner; a member's rename and an over-long name were both "
                + "refused without writing");
    }

    @Test
    @Order(39)
    @DisplayName("/tenet party transfer hands ownership over, demotes the actor, and is owner-only")
    void transferHandsThePartyOver() {
        UUID id = ownerOf(player);
        assertNotNull(id, "order 38 should have left a party to hand over");

        HeadlessServer.Outcome transferred = asOperator("/tenet party transfer tenet-friend");
        assertEquals(1, transferred.result(),
                () -> "the owner should have been able to hand the party over:\n" + transferred.text());
        assertEquals(friend.getUUID(), ownerOfTeam(id), "the friend owns it now");
        assertEquals(TeamRole.MEMBER, roleOf(id, player.getUUID()),
                "and the previous owner is an ordinary member -- the demotion the manager documents");

        HeadlessServer.Outcome byMember = asOperator("/tenet party transfer tenet-tester");
        assertRefused(byMember, "the member who just gave it away cannot take it back by themselves");

        HeadlessServer.Outcome back = asOperator(friend, "/tenet party transfer tenet-tester");
        assertEquals(1, back.result(), () -> "the new owner can hand it back:\n" + back.text());
        assertEquals(player.getUUID(), ownerOfTeam(id), "and the run continues with the original owner");

        note("ownership moved to the friend and back, and a member's transfer attempt was refused");
    }

    @Test
    @Order(40)
    @DisplayName("the two settings are owner-only, and member invitations obey the switch")
    void settingsAreOwnerOnlyAndMemberInvitesObeyTheSwitch() {
        UUID id = ownerOf(player);

        HeadlessServer.Outcome off = asOperator("/tenet party member-invites off");
        assertEquals(1, off.result(), () -> "the owner should have been able to switch it off:\n" + off.text());
        assertEquals(new TeamPolicy(false, false), policyOf(id));

        // The friend is a MEMBER, and with the switch off the refusal is the policy's. The character
        // of this assertion is the switch: with it on, the same command from the same member is the
        // one order 43 relies on.
        HeadlessServer.Outcome refused = asOperator(friend, "/tenet party invite tenet-tester");
        assertRefused(refused, "with member invitations off, an ordinary member may not invite");

        HeadlessServer.Outcome on = asOperator("/tenet party member-invites on");
        assertEquals(1, on.result(), () -> "the switch should go back on:\n" + on.text());
        assertEquals(new TeamPolicy(false, true), policyOf(id));

        HeadlessServer.Outcome notOwner = asOperator(friend, "/tenet party open on");
        assertRefused(notOwner, "only the owner may open the party");
        HeadlessServer.Outcome opened = asOperator("/tenet party open on");
        assertEquals(1, opened.result(), () -> "the owner should have opened it:\n" + opened.text());
        assertEquals(new TeamPolicy(true, true), policyOf(id));

        HeadlessServer.Outcome read = asOperator("/tenet party open");
        assertEquals(1, read.result(), () -> "the read form should report:\n" + read.text());

        note("member invitations and the public switch were both owner-only, and the policy round-tripped "
                + "through the store");
    }

    @Test
    @Order(41)
    @DisplayName("a solo player joins an open party, and declines or is uninvited from a closed one")
    void joinDeclineAndCancel() {
        UUID id = ownerOf(player);

        // The friend leaves so they are solo again -- the state this whole order is about.
        assertTrue(server.callOnServerThread(() -> Teams.of(server.server()).leave(friend.getUUID())));

        // Open (order 40 left it open): joined by id, with no invitation anywhere.
        HeadlessServer.Outcome joined = asOperator(friend, "/tenet party join " + id);
        assertEquals(1, joined.result(),
                () -> "an open party should be joinable from the solo screen:\n" + joined.text());
        assertEquals(id, ownerOf(friend), "and the join moved their progress owner onto it");
        assertTrue(server.callOnServerThread(() -> Teams.of(server.server()).leave(friend.getUUID())));

        HeadlessServer.Outcome closed = asOperator("/tenet party open off");
        assertEquals(1, closed.result());
        HeadlessServer.Outcome refused = asOperator(friend, "/tenet party join " + id);
        assertRefused(refused, "a closed party cannot be joined without an invitation");

        // Decline: the invitee answers an invitation with a no. The first invitation here is the way
        // in for the next step, which is the withdrawal.
        assertEquals(1, asOperator("/tenet party invite tenet-friend").result());
        assertTrue(invitedTo(id, friend.getUUID()), "the invitation exists to be declined");
        HeadlessServer.Outcome declined = asOperator(friend, "/tenet party decline " + id);
        assertEquals(1, declined.result(), () -> "the invitee should be able to decline:\n" + declined.text());
        assertFalse(invitedTo(id, friend.getUUID()), "and the invitation is gone");
        assertRefused(asOperator(friend, "/tenet party decline " + id), "nothing is waiting to decline now");

        HeadlessServer.Outcome withdrawn = asOperator("/tenet party uninvite tenet-friend");
        assertRefused(withdrawn, "there is no second invitation to withdraw yet");
        assertEquals(1, asOperator("/tenet party invite tenet-friend").result());
        HeadlessServer.Outcome uninvited = asOperator("/tenet party uninvite tenet-friend");
        assertEquals(1, uninvited.result(), () -> "the party should be able to withdraw it:\n" + uninvited.text());
        assertFalse(invitedTo(id, friend.getUUID()), "and the invitation is gone from the party's side");

        note("a solo player joined an open party by id; a closed party refused them; an invitation was "
                + "declined by the invitee and, sent again, withdrawn by the party");
    }

    @Test
    @Order(42)
    @DisplayName("a party holds eight, and both the invitation and the answer are refused past it")
    void theMemberCapHolds() {
        // A fresh party, so the count is known and the fixture is not somebody else's members.
        assertTrue(server.callOnServerThread(() -> Teams.of(server.server()).leave(player.getUUID())));
        assertEquals(1, asOperator("/tenet party create the-eight-party").result());
        UUID id = ownerOf(player);

        List<UUID> fakes = new ArrayList<>();
        for (int i = 0; i < 8; i++) {
            fakes.add(UUID.randomUUID());
        }

        // Seven in, six of them answered: the party is at seven members with one invitation pending.
        // Fakes rather than real players on purpose -- the stored manager keys membership by uuid and
        // never needs a connection, and eight real players would be a fixture rather than a test.
        assertTrue(server.callOnServerThread(() -> {
            var teams = Teams.of(server.server());
            for (int i = 0; i < 6; i++) {
                if (!teams.invite(player.getUUID(), id, fakes.get(i))) {
                    return false;
                }
                if (teams.acceptInvite(fakes.get(i), id).isEmpty()) {
                    return false;
                }
            }
            return teams.invite(player.getUUID(), id, fakes.get(6));
        }), "six members and a seventh invitation should have fitted in a party of eight");

        assertEquals(7, sizeOf(id));

        // The eighth joins, and now the party is exactly full -- while the seventh invitation is still
        // pending. That is the case the answer re-checks capacity for: a party can fill between the
        // invitation and the answer.
        assertTrue(server.callOnServerThread(() ->
                Teams.of(server.server()).invite(player.getUUID(), id, fakes.get(7))));
        assertTrue(server.callOnServerThread(() ->
                Teams.of(server.server()).acceptInvite(fakes.get(7), id).isPresent()));
        assertEquals(8, sizeOf(id), "eight, which is the cap");

        assertTrue(server.callOnServerThread(() ->
                        Teams.of(server.server()).acceptInvite(fakes.get(6), id).isEmpty()),
                "the pending invitation cannot be answered into a full party -- capacity is rechecked "
                        + "at the answer, not only at the invitation");
        assertFalse(server.callOnServerThread(() ->
                        Teams.of(server.server()).invite(player.getUUID(), id, UUID.randomUUID())),
                "and a ninth invitation is refused at the invitation");

        // Clean up: the owner disbands, which is also the path where every member's retained copy is
        // taken -- orders 42's fakes included, harmlessly.
        HeadlessServer.Outcome disbanded = asOperator("/tenet party disband");
        assertEquals(1, disbanded.result(), () -> "the fixture party should disband:\n" + disbanded.text());

        note("a party filled to eight; a pending invitation was refused at the answer and a ninth at "
                + "the invitation");
    }

    @Test
    @Order(43)
    @DisplayName("a kicked member keeps the party's nodes, and a reward already collected cannot pay twice")
    void aKickedMemberKeepsThePartysProgressAndPaidRewardsStay() {
        // Two rules that share one fixture: retention on a kick, and the claim copy that stops a
        // reward collected in the party from being collected again after it.
        assertEquals(1, asOperator("/tenet party create the-kick-party").result());
        UUID id = ownerOf(player);
        assertEquals(1, asOperator("/tenet party invite tenet-friend").result());
        assertTrue(server.callOnServerThread(() ->
                        Teams.of(server.server()).acceptInvite(friend.getUUID()).isPresent()),
                "the friend should have joined the party to be kicked from it");

        clearInventories();
        server.onServerThread(() -> {
            friend.getInventory().add(ItemStacks.stackOf(Items.OAK_LOG, 8));
            friend.getInventory().setChanged();
        });
        assertTrue(tickUntil(() -> stateFor(player, "punch_a_tree") == QuestState.COMPLETED,
                        Duration.ofSeconds(20)),
                () -> "the friend gathering should complete the party's punch_a_tree, as in order 11");

        assertEquals(1, asOperator(friend, "/tenet claim punch_a_tree").result(),
                "the friend collects their copy while in the party");
        assertEquals(1, countInInventoryOf(friend, Items.WOODEN_AXE));

        HeadlessServer.Outcome kicked = asOperator("/tenet party kick tenet-friend");
        assertEquals(1, kicked.result(), () -> "the owner should have been able to remove the friend:\n"
                + kicked.text());
        assertEquals(friend.getUUID(), ownerOf(friend), "the friend is solo again");

        assertEquals(QuestState.COMPLETED, stateFor(friend, "punch_a_tree"),
                "and keeps the node the party completed -- a kick is a statement about behaviour, not "
                        + "a confiscation of progression");
        assertRefused(asOperator(friend, "/tenet claim punch_a_tree"),
                "and the axe they collected in the party cannot be collected again from their own "
                        + "record: their claim travelled with them, which is what stops the merge "
                        + "becoming a dupe");
        assertEquals(1, countInInventoryOf(friend, Items.WOODEN_AXE), "still exactly one axe");

        assertEquals(1, asOperator("/tenet party disband").result(), "and the fixture party is cleaned up");

        note("a kicked member kept the party's completed node and could not re-collect the copy they "
                + "had already claimed");
    }

    @Test
    @Order(44)
    @DisplayName("/tenet party handover gives the party away and leaves, in one press")
    void handoverTransfersAndLeaves() {
        // The successor picker's whole meaning: whoever takes the party owns it, and the player who
        // pressed is out of it -- not "still a member of the party they just gave away", which is what
        // the picker used to do by sending a transfer with no leave.
        assertEquals(1, asOperator("/tenet party create the-handover-party").result());
        UUID id = ownerOf(player);
        assertEquals(1, asOperator("/tenet party invite tenet-friend").result());
        assertTrue(server.callOnServerThread(() ->
                        Teams.of(server.server()).acceptInvite(friend.getUUID()).isPresent()),
                "the friend should have joined the party to be handed it");

        HeadlessServer.Outcome handed = asOperator("/tenet party handover tenet-friend");
        assertEquals(1, handed.result(), () -> "the handover should have worked:\n" + handed.text());

        assertEquals(friend.getUUID(), ownerOfTeam(id), "the friend owns it now");
        assertEquals(TeamRole.OWNER, roleOf(id, friend.getUUID()));
        // The party's id, not the friend's own: they are still a member, so the record they read is
        // the party's -- the same answer order 41 asserts for a join. Only the player who pressed is
        // solo, and their own id is what the next assertion checks.
        assertEquals(id, ownerOf(friend), "and reads the party's progress");
        assertEquals(player.getUUID(), ownerOf(player),
                "the player who pressed is solo -- the second half of the operation, which a bare "
                        + "transfer left undone");
        assertFalse(server.callOnServerThread(() ->
                        Teams.of(server.server()).byId(id)
                                .map(team -> team.isMember(player.getUUID())).orElse(false)),
                "and they are no longer a member of it");

        assertRefused(asOperator("/tenet party handover tenet-tester"),
                "a member cannot hand a party over");
        assertRefused(asOperator("/tenet party leave"),
                "and they are not in a party to leave");

        assertEquals(1, asOperator(friend, "/tenet party disband").result(),
                "the fixture party is cleaned up by its new owner");

        note("handover moved ownership to the friend and left the old owner solo, in one command");
    }

    // ------------------------------------------------------------------
    // Auto-claim: the chapter toggle, and the reward a mode can never take
    // ------------------------------------------------------------------

    @Test
    @Order(90)
    @DisplayName("a chapter that turns auto-claim on pays at completion, and a choice still waits")
    void autoClaimPaysAtCompletionAndNeverEatsAChoice() {
        // The two quests in the seeded `auto_claim` chapter differ only in their rewards: one is an
        // item that can be handed over, the other is a choice that cannot be handed over by anyone but
        // the player. The chapter says `autoClaim: enabled`, so both take the middle rung of the ladder.
        HeadlessServer.Outcome paid = asOperator("/tenet complete auto_paid");
        assertEquals(1, paid.result(), () -> "/tenet complete should have worked. It said:\n" + paid.text());

        assertEquals(1, countInInventory(Items.GOLDEN_APPLE),
                "the chapter's autoClaim paid the item at completion -- no claim command ran");
        assertTrue(rewardClaimed("auto_paid", 0),
                "and the claim was recorded before the grant, which is the crash-safe direction");

        HeadlessServer.Outcome choice = asOperator("/tenet complete auto_choice");
        assertEquals(1, choice.result(), () -> "/tenet complete should have worked. It said:\n"
                + choice.text());

        assertEquals(0, countInInventory(Items.DIAMOND) + countInInventory(Items.EMERALD),
                "a choice reward is never auto-granted: its payout is the player's pick");
        assertFalse(rewardClaimed("auto_choice", 0),
                "and it is still outstanding, so the claim flow will offer it -- the bug this pins "
                        + "marked it collected and granted nothing, which lost the reward silently");

        note("auto-claim paid the item at completion and left the choice outstanding");
    }

    // ------------------------------------------------------------------
    // The rewards inbox: one row's press takes one reward
    // ------------------------------------------------------------------

    @Test
    @Order(91)
    @DisplayName("claiming one reward of a quest leaves its siblings outstanding")
    void claimingOneRewardLeavesItsSiblingsOutstanding() {
        // The fixture's two rewards are both `auto: disabled`, so completing it hands over nothing --
        // this is the rewards panel's per-row press, and its whole point is that taking the emeralds
        // does not take the diamonds with them.
        HeadlessServer.Outcome completed = asOperator("/tenet complete two_gifts");
        assertEquals(1, completed.result(),
                () -> "/tenet complete should have worked. It said:\n" + completed.text());

        int diamondsBefore = countInInventory(Items.DIAMOND);
        int emeraldsBefore = countInInventory(Items.EMERALD);
        assertEquals(diamondsBefore, countInInventory(Items.DIAMOND),
                "a disabled auto-claim hands over nothing at completion");

        QuestIndex.QuestEntry entry = TenetQuests.index().quest("two_gifts").orElseThrow();

        assertTrue(server.callOnServerThread(() ->
                        ProgressService.claimReward(server.server(), player, entry, 1)),
                "the emeralds' row should have paid");
        assertEquals(emeraldsBefore + 5, countInInventory(Items.EMERALD),
                "five emeralds, one row's worth");
        assertEquals(diamondsBefore, countInInventory(Items.DIAMOND),
                "and the diamonds' row is untouched -- a per-row claim takes one reward, not the list");
        assertTrue(rewardClaimed("two_gifts", 1), "the claimed row is recorded");
        assertFalse(rewardClaimed("two_gifts", 0), "and its sibling is still owed");

        assertTrue(server.callOnServerThread(() ->
                        ProgressService.claimReward(server.server(), player, entry, 0)),
                "the diamonds' row pays next");
        assertEquals(diamondsBefore + 3, countInInventory(Items.DIAMOND),
                "three diamonds, the other row's worth");

        assertFalse(server.callOnServerThread(() ->
                        ProgressService.claimReward(server.server(), player, entry, 0)),
                "a second press on a paid row changes nothing, the same guard the quest claim has");
        assertEquals(diamondsBefore + 3, countInInventory(Items.DIAMOND),
                "which is the assertion that it gave no more");

        // An index no reward holds is refused rather than clamped: a forged packet buys nothing.
        assertFalse(server.callOnServerThread(() ->
                        ProgressService.claimReward(server.server(), player, entry, 7)),
                "an index no reward holds is refused");

        note("a per-row claim took one reward and left the other; a second press and a forged index "
                + "both changed nothing");
    }

    // ------------------------------------------------------------------
    // Strict sweeps: no room stops the claim rather than spilling it
    // ------------------------------------------------------------------

    @Test
    @Order(92)
    @DisplayName("a sweep with no room stops at the reward that does not fit, and leaves the rest owed")
    void aFullInventoryStopsASweep() {
        // The fixture quest has two item rewards, both `auto: disabled`. Reset and complete it again so
        // both are outstanding, then leave the player exactly one free slot: the first reward fits and
        // the second does not, which is the halt this exists to pin.
        assertEquals(1, asOperator("/tenet reset two_gifts").result(),
                "the reset should have cleared the quest");
        assertEquals(1, asOperator("/tenet complete two_gifts").result());

        server.onServerThread(() -> {
            var inventory = player.getInventory();
            inventory.clearContent();
            for (int slot = 0; slot < 35; slot++) {
                inventory.setItem(slot, ItemStacks.stackOf(Items.COBBLESTONE, 64));
            }
            inventory.setChanged();
        });
        // The whole-quest press rather than the book-wide sweep, and for a reason that matters to the
        // assertion: a sweep walks every outstanding quest in the pack, so a count of what it took is a
        // fact about the whole book. This is the same strict path -- one press, one quest's rewards,
        // halting where it must -- which is what the test is about.
        QuestIndex.QuestEntry entry = TenetQuests.index().quest("two_gifts").orElseThrow();
        int diamondsBefore = countInInventory(Items.DIAMOND);
        int emeraldsBefore = countInInventory(Items.EMERALD);

        boolean collected = server.callOnServerThread(() ->
                ProgressService.claim(server.server(), player, entry));

        assertTrue(collected, "the diamonds fit the one free slot, so the press paid something");
        assertEquals(diamondsBefore + 3, countInInventory(Items.DIAMOND),
                "the reward that fit was handed over");
        assertEquals(emeraldsBefore, countInInventory(Items.EMERALD),
                "and the one that did not fit was not handed over");
        assertTrue(rewardClaimed("two_gifts", 0), "the granted reward is marked");
        assertFalse(rewardClaimed("two_gifts", 1), "the stopped one stays outstanding");
        assertTrue(server.callOnServerThread(() -> server.server().overworld()
                        .getEntitiesOfClass(net.minecraft.world.entity.item.ItemEntity.class,
                                player.getBoundingBox().inflate(8)).isEmpty()),
                "a strict claim never spills: nothing may be lying on the floor");

        server.onServerThread(() -> {
            player.getInventory().clearContent();
            player.getInventory().setChanged();
        });
        note("a full inventory stopped the sweep after the first reward, with nothing on the floor");
    }

    @Test
    @Order(93)
    @DisplayName("a pick with no room is refused on the card's terms, and pays once there is room")
    void aRefusedPickStaysOutstanding() {
        // The auto-claim chapter's choice quest: reset and complete it so the choice is outstanding.
        assertEquals(1, asOperator("/tenet reset auto_choice").result());
        assertEquals(1, asOperator("/tenet complete auto_choice").result());
        QuestIndex.QuestEntry entry = TenetQuests.index().quest("auto_choice").orElseThrow();

        server.onServerThread(() -> {
            var inventory = player.getInventory();
            inventory.clearContent();
            for (int slot = 0; slot < 36; slot++) {
                inventory.setItem(slot, ItemStacks.stackOf(Items.COBBLESTONE, 64));
            }
            inventory.setChanged();
        });

        var refused = server.callOnServerThread(() ->
                ProgressService.claimChoice(server.server(), player, entry, 0, 0));
        assertEquals(ClaimChoiceResultPayload.Result.NO_SPACE, refused,
                "a full inventory refuses the pick rather than dropping it on the floor");
        assertFalse(rewardClaimed("auto_choice", 0),
                "nothing is marked, so the reward is still owed and the pick can be made again");

        server.onServerThread(() -> {
            player.getInventory().clearContent();
            player.getInventory().setChanged();
        });
        var granted = server.callOnServerThread(() ->
                ProgressService.claimChoice(server.server(), player, entry, 0, 0));
        assertEquals(ClaimChoiceResultPayload.Result.OK, granted, "with room, the same pick pays");
        assertTrue(rewardClaimed("auto_choice", 0));
        assertEquals(1, countInInventory(Items.DIAMOND), "entry 0 of the table is the diamond");
        note("a pick was refused with no room, and paid once there was some");
    }

    @Test
    @Order(94)
    @DisplayName("a task that takes waits for the press: the tick shows the count and records nothing")
    void aTakingTaskWaitsForThePress() {
        clearInventories();
        HeadlessServer.Outcome given = asOperator("/give @s minecraft:amethyst_shard 8");
        assertEquals(1, given.result(), () -> "/give should have worked:\n" + given.text());

        // An item task is re-counted on its own interval, so five ticks with a sleep between them is
        // comfortably past the first evaluation.
        for (int i = 0; i < 5; i++) {
            tickOnce();
            sleep(100);
        }

        assertNotEquals(QuestState.COMPLETED, stateOf("the_toll"),
                "the shards must not finish a task whose cost is a press -- taking them unasked is the "
                        + "one thing tasks.md promises does not happen");
        assertEquals(0, recordedTask("the_toll", 0),
                "and nothing may be recorded: completion is read off the recorded count, so recording it "
                        + "here would finish the quest and leave the shards in the player's pocket");

        HeadlessServer.Outcome submitted = asOperator("/tenet submit the_toll 0");
        assertEquals(1, submitted.result(),
                () -> "the press should have been accepted. It said:\n" + submitted.text());
        assertEquals(QuestState.COMPLETED, stateOf("the_toll"), "the press is what finishes it");
        assertEquals(8, recordedTask("the_toll", 0), "and the count it took is the count it recorded");
        assertEquals(0, countInInventory(Items.AMETHYST_SHARD),
                "the press takes what the task asked for -- that is the consent it exists for");

        note("a consuming item task stayed unfinished through the tick and was taken at the press");
    }

    @Test
    @Order(95)
    @DisplayName("an automatic payout honours the stage gate, member by member")
    void anAutomaticPayoutHonoursTheStageGate() {
        // The gate's fifth part. A stage is one player's, and the team's record is the team's -- so a
        // member who joined after the fact can read a completed quest they were never eligible for, and
        // the automatic payout paid them anyway. The javadoc promised the gate decides whether a quest
        // can "complete or pay out"; only completion and the manual claim checked it.
        clearInventories();
        setStage(THE_MARK, true);
        asOperator("/tenet stage remove tenet-friend " + THE_MARK);

        assertEquals(1, asOperator("/tenet party create the-gate-party").result());
        assertEquals(1, asOperator("/tenet party invite tenet-friend").result());
        assertTrue(server.callOnServerThread(
                        () -> Teams.of(server.server()).acceptInvite(friend.getUUID()).isPresent()),
                "the friend joins, so the party has two online members and the payout has two candidates");

        HeadlessServer.Outcome completed = asOperator("/tenet complete auto_gate");
        assertEquals(1, completed.result(),
                () -> "the member holding the stage may complete it:\n" + completed.text());

        assertEquals(1, countInInventory(Items.GOLDEN_APPLE),
                "the member holding the stage is paid by the automatic payout");
        assertEquals(0, countInInventoryOf(friend, Items.GOLDEN_APPLE),
                "and the member who never held it is not, although the team's record is complete -- the "
                        + "reward stays owed and is paid the first time they hold the stage");

        assertEquals(1, asOperator("/tenet party disband").result(), "and the party is cleaned up");
        note("a stage-gated automatic payout paid the stage holder and skipped the member without it");
    }

    @Test
    @Order(96)
    @DisplayName("a gated player is not offered a claim the server would refuse")
    void theClaimHintAsksTheGate() {
        // `/tenet progress` answers "is there something to collect" with the same question the claim
        // path asks. It did not ask the stage gate, so it stamped "rewards waiting" on a quest the
        // claim refused with "Nothing to collect", and claim-all counted one it would not take.
        clearInventories();
        assertEquals(1, asOperator("/tenet reset the_mark").result(), "start from an uncollected quest");
        setStage(THE_MARK, true);
        assertEquals(1, asOperator("/tenet complete the_mark").result());

        // The claim suffix names the quest's id, and other quests in this pack legitimately have rewards
        // waiting -- so the assertion is about *this* quest's line, not about the phrase appearing.
        assertTrue(asOperator("/tenet progress").text().contains("/tenet claim the_mark"),
                "with the stage held, the listing offers the claim:\n" + asOperator("/tenet progress").text());

        setStage(THE_MARK, false);
        assertFalse(asOperator("/tenet progress").text().contains("/tenet claim the_mark"),
                "and with it removed the same quest is no longer offered, because the claim would be "
                        + "refused:\n" + asOperator("/tenet progress").text());

        setStage(THE_MARK, true);
        note("the progress listing stopped offering a claim the stage gate would refuse");
    }

    @Test
    @Order(97)
    @DisplayName("/tenet reload re-reads Armature's settings file, not only the quest folder")
    void reloadReReadsArmatureSettings() throws IOException {
        // `/tenet config` names two files and says to edit them and run this command. The quest half was
        // always true; the Armature half was not, because `ArmatureConfig.install` had one caller --
        // startup -- while the command re-read only the quest directory. So this asserts the other half
        // through the command itself, at the one value an operator would change.
        Path file = ROOT.resolve("config").resolve("armature").resolve(ArmatureConfig.FILE_NAME);
        Files.createDirectories(file.getParent());
        Files.writeString(file, "{\"teams\":{\"maxMembers\":9}}");

        assertTrue(asOperator("/tenet reload").result() > 0, "the reload reports the files it decoded");
        assertEquals(9, ArmatureConfig.current().teams().maxMembers(),
                "the edited cap is in force without a restart");

        // Restored to what a first start leaves, so the party-cap orders that count to eight are not
        // reading a file this test wrote: deleting the file and reloading is what a fresh install does.
        Files.delete(file);
        assertTrue(asOperator("/tenet reload").result() > 0);
        assertEquals(TeamSettings.DEFAULT.maxMembers(), ArmatureConfig.current().teams().maxMembers(),
                "and the default is back in force");
    }

    @Test
    @Order(98)
    @DisplayName("a press on one chapter pays that chapter and leaves every other chapter owed")
    void aChapterClaimStopsAtItsOwnChapter() {
        // The claim menu's banner. Two chapters, one waiting reward each, both outstanding at the same
        // moment -- which is the only arrangement in which "it paid only its own" is a fact rather than
        // a coincidence. Different items, so which one was paid is read off the inventory.
        clearInventories();
        assertEquals(1, asOperator("/tenet complete one_ingot").result(), "the first chapter's quest");
        assertEquals(1, asOperator("/tenet complete one_brick").result(), "and the second's");
        assertEquals(0, countInInventory(Items.GOLD_INGOT),
                "both rewards are `auto: disabled`, so completing either hands over nothing");

        int ingotsBefore = countInInventory(Items.GOLD_INGOT);
        int bricksBefore = countInInventory(Items.BRICK);
        int claimed = server.callOnServerThread(() ->
                ProgressService.claimChapter(server.server(), player, "probe_one", ClaimFilter.ALL));

        assertEquals(1, claimed, "the banner's press paid exactly the one reward that chapter owed");
        assertEquals(ingotsBefore + 2, countInInventory(Items.GOLD_INGOT),
                "the named chapter's reward was handed over");
        assertEquals(bricksBefore, countInInventory(Items.BRICK),
                "and the other chapter's was not -- which is the whole of what the press promises");
        assertTrue(rewardClaimed("one_ingot", 0), "the paid chapter's reward is recorded");
        assertFalse(rewardClaimed("one_brick", 0), "and the untouched chapter's is still owed");

        // A chapter id no chapter has. A typo, a rename, or a forged packet: the sweep walks the index,
        // matches nothing, and pays nothing -- there is no error to raise because nothing was asked for.
        assertEquals(0, server.callOnServerThread(() -> ProgressService.claimChapter(server.server(),
                        player, "no_such_chapter", ClaimFilter.ALL)),
                "an unknown chapter claims nothing");
        assertEquals(0, server.callOnServerThread(() ->
                        ProgressService.claimChapter(server.server(), player, "", ClaimFilter.ALL)),
                "and a blank one claims nothing rather than sweeping the book");

        // A second press on a chapter already collected is the same as the first: it finds nothing
        // outstanding, so it pays nothing and says nothing.
        assertEquals(0, server.callOnServerThread(() ->
                        ProgressService.claimChapter(server.server(), player, "probe_one",
                                ClaimFilter.ALL)),
                "a chapter already collected pays nothing a second time");

        // And the other chapter is still there to be collected, so the first press did not merely take
        // everything and report one.
        assertEquals(1, server.callOnServerThread(() ->
                        ProgressService.claimChapter(server.server(), player, "probe_two",
                                ClaimFilter.ALL)),
                "the chapter the first press left alone is still collectable");
        assertEquals(bricksBefore + 2, countInInventory(Items.BRICK), "and it pays what it owed");

        // The filter narrows a chapter's press the way it narrows the footer's, which is the fault the
        // first version of this payload had: a banner drawn in the choices view reached the item rewards
        // the player could not see. Both probe rewards are items, so a choices-only press pays nothing.
        assertEquals(1, asOperator("/tenet reset one_ingot").result(), "start it over");
        assertEquals(1, asOperator("/tenet complete one_ingot").result());
        int before = countInInventory(Items.GOLD_INGOT);
        assertEquals(0, server.callOnServerThread(() ->
                        ProgressService.claimChapter(server.server(), player, "probe_one",
                                ClaimFilter.CHOICES)),
                "a choices-only press takes nothing from a chapter of items");
        assertEquals(before, countInInventory(Items.GOLD_INGOT),
                "and nothing was handed over, so the banner reached only what its view showed");
        assertEquals(1, server.callOnServerThread(() ->
                        ProgressService.claimChapter(server.server(), player, "probe_one",
                                ClaimFilter.ALL)),
                "while the unfiltered press still pays it");

        clearInventories();
        note("a chapter's press paid its own reward, left the other chapter's, and an unknown id paid "
                + "nothing");
    }

    @Test
    @Order(200)
    @DisplayName("flexible progress accumulates early but neither completes nor pays")
    void flexibleProgressWaitsForItsGate() {
        // the legacy flexible mode as played: the item task maxes out before the gate quest is done,
        // and nothing happens — no completion, no payout — until the gate opens, when the
        // already-maxed quest finishes and its automatic reward is handed over.
        //
        // Solo for this test, because the party saga above leaves whoever knows what behind:
        // under owner_only an item task counts the owner's pockets rather than the presser's, so a
        // leftover mode plus moved ownership would measure the wrong player and this test would
        // starve however long it ticked. Leaving is refused when already solo; either way the next
        // line pins the state this test counts under. Merging is harmless here: the gate quest has
        // no progress anywhere, so nothing merged can complete anything early.
        asOperator("/tenet party leave");
        assertEquals(player.getUUID(), ownerOf(player), "solo for this test: no party counting");
        clearInventories();
        HeadlessServer.Outcome given = asOperator("/give @s minecraft:oak_log 4");
        assertEquals(1, given.result(), "the give should have worked:\n" + given.text());
        assertEquals(4, countInInventory(Items.OAK_LOG), "the logs should be held");

        // At least, not exactly: recorded progress is the best count ever seen and the count can
        // overshoot the requirement (one stack of eight records eight for a task wanting four),
        // so == 4 would fail on a record an earlier order already pushed past it.
        assertTrue(tickUntil(() -> recordedTask("flex_early", 0) >= 4, Duration.ofSeconds(20)),
                "the flexible quest should measure while its gate is shut");
        assertEquals(QuestState.STARTED, stateOf("flex_early"),
                "measured, but not completable: the gate is still shut");
        assertEquals(0, countInInventory(Items.STICK),
                "and nothing was paid before the gate opened");
        assertRefused(asOperator("/tenet claim flex_early"),
                "there is nothing to collect on a quest that has not completed");

        HeadlessServer.Outcome opened = asOperator("/tenet submit flex_gate 0");
        assertEquals(1, opened.result(), "the gate press should have worked:\n" + opened.text());
        assertTrue(tickUntil(() -> stateOf("flex_early") == QuestState.COMPLETED,
                Duration.ofSeconds(20)),
                "the maxed quest should finish once its gate opens, with no further action");
        assertTrue(countInInventory(Items.STICK) >= 2,
                "and its automatic reward should have been handed over");

        note("early progress waited for its gate, then completed and paid on its own");
        clearInventories();
    }

    @Test
    @Order(201)
    @DisplayName("a chain of flexible quests completes over bounded ticks, one node at a time")
    void flexibleChainCompletesWithoutACascade() {
        // All three max their tasks before any gate opens; then the head is pressed and each tick
        // finishes at most the next one. A recursive sweep would finish the whole chain inside one
        // call — the harness calls tick directly, so one call completing three quests would show.
        assertEquals(1, asOperator("/tenet submit flex_c3 0").result(), "the tail should record");
        assertEquals(1, asOperator("/tenet submit flex_c2 0").result(), "and the middle");
        assertNotEquals(QuestState.COMPLETED, stateOf("flex_c1"), "nothing is done yet");
        assertNotEquals(QuestState.COMPLETED, stateOf("flex_c2"), "maxed but gated");
        assertNotEquals(QuestState.COMPLETED, stateOf("flex_c3"), "maxed but gated twice over");

        assertEquals(1, asOperator("/tenet submit flex_c1 0").result(), "the head should finish");
        assertEquals(QuestState.COMPLETED, stateOf("flex_c1"), "a quest with no gate finishes at once");

        tickOnce();
        assertEquals(QuestState.COMPLETED, stateOf("flex_c2"),
                "one tick finishes the quest whose gate just opened");
        assertNotEquals(QuestState.COMPLETED, stateOf("flex_c3"),
                "but not the one behind it: retro-completion is the tick's job, one node per pass, "
                        + "not a cascade down the chain");

        assertTrue(tickUntil(() -> stateOf("flex_c3") == QuestState.COMPLETED,
                Duration.ofSeconds(20)),
                "and the tail follows on its own tick");
        note("three pre-maxed flexible quests finished over three passes, never in one call");
    }

    @Test
    @Order(202)
    @DisplayName("a submit that opens the gate does not finish the dependant in the same call")
    void submitDoesNotCascadeToFlexibleDependants() {
        // The event-path rule: a kill or a submit completes the single quest it touched, and the
        // next tick picks up whatever that opened. Completing N quests inside one event would grant
        // N automatic rewards and fire N events outside the tick's batching.
        assertEquals(1, asOperator("/tenet submit flex_d2 0").result(), "the gated press records");
        assertEquals(1, recordedTask("flex_d2", 0), "its task is maxed");
        assertNotEquals(QuestState.COMPLETED, stateOf("flex_d2"), "but the gate is still shut");

        assertEquals(1, asOperator("/tenet submit flex_d1 0").result(), "the gate should finish");
        assertNotEquals(QuestState.COMPLETED, stateOf("flex_d2"),
                "the dependant must not finish inside the submit call that opened its gate");
        tickOnce();
        assertEquals(QuestState.COMPLETED, stateOf("flex_d2"),
                "the next tick finishes what the submit opened");
        note("a submit finished its own quest; the tick finished the dependant");
    }

    @Test
    @Order(203)
    @DisplayName("a repeatable round ends at its final claim, not at its completion")
    void repeatableRoundEndsAtFinalClaim() {
        // the legacy rule: the timer starts when the last unclaimed reward is claimed, the quest's
        // progress resets then, and the count moves then. So a completed-but-unclaimed repeatable
        // reads STARTED with its tasks still maxed and its count unmoved — past the point where a
        // completion-timed cooldown would already have run out — and the claim ends the round.
        //
        // The STARTED (rather than COMPLETED) is the resolution's own doing, before and after this
        // change: a repeatable finished with a payout nobody has collected is still playable, so it
        // resolves as started. What pins the new rule is structural — tasks kept, count and clock
        // untouched at completion; all three moving at the claim.
        clearInventories();
        assertEquals(1, asOperator("/tenet submit flex_repeat 0").result(), "the press records");
        assertEquals(QuestState.STARTED, stateOf("flex_repeat"),
                "finished but awaiting its payout, which still reads as playable");

        assertEquals(0, timesCompletedOf("flex_repeat"),
                "the count moves at the final claim, not at the completion");
        assertEquals(1, recordedTask("flex_repeat", 0),
                "and the maxed task stays maxed while the payout is waiting");
        assertEquals(0L, lastCompletedAtOf("flex_repeat"),
                "with no clock running: the cooldown has not started");

        // Past the 100-tick cooldown, had it started at completion: the game time is set, not
        // waited out, because this world's clock barely advances on its own and a sleep would be
        // a race against an unknown tick rate. It must still read waiting, because its payout is
        // uncollected and nothing about time changes that.
        long settledAt = gameTime();
        setGameTime(settledAt + 500);
        tickOnce();
        assertEquals(QuestState.STARTED, stateOf("flex_repeat"),
                "an unclaimed repeatable is not replayable, however far time moves");
        assertEquals(0, timesCompletedOf("flex_repeat"), "and still uncounted");

        HeadlessServer.Outcome claimed = asOperator("/tenet claim flex_repeat");
        assertEquals(1, claimed.result(), "the payout should have been handed over:\n"
                + claimed.text());
        assertEquals(1, timesCompletedOf("flex_repeat"), "the final claim moved the count");
        assertEquals(0, recordedTask("flex_repeat", 0), "and cleared the round's tasks");
        long claimedAt = lastCompletedAtOf("flex_repeat");
        assertTrue(claimedAt >= settledAt + 500,
                "with the cooldown running from the claim, not from 500 ticks before it");
        assertEquals(QuestState.COMPLETED, stateOf("flex_repeat"),
                "cooling down, which is the one state that reads as waiting on time");

        setGameTime(claimedAt + 50);
        tickOnce();
        assertEquals(QuestState.COMPLETED, stateOf("flex_repeat"), "still cooling at +50");
        setGameTime(claimedAt + 500);
        tickOnce();
        assertNotEquals(QuestState.COMPLETED, stateOf("flex_repeat"),
                "and playable again once the claim-timed cooldown runs out");

        // One more round, to prove the count moves per round and the second payout is real.
        assertEquals(1, asOperator("/tenet submit flex_repeat 0").result(), "the next round records");
        assertEquals(1, asOperator("/tenet claim flex_repeat").result(), "and pays");
        assertEquals(2, timesCompletedOf("flex_repeat"), "two rounds, two completions counted");
        assertTrue(countInInventory(Items.STICK) >= 2, "both rounds paid their stick");

        note("a repeatable round ended at its final claim: count, reset and cooldown together");
        clearInventories();
    }

    @Test
    @Order(204)
    @DisplayName("a repeatable with nothing to collect resets at completion")
    void rewardlessRepeatableResetsAtCompletion() {
        // The exception to the claim-timed rule: with no payout waiting, no claim will ever arrive
        // to end the round, so the count moves and the round clears at completion instead. The
        // quest reads STARTED throughout, for the reason the test above gives: a finished
        // repeatable still reads as playable.
        assertEquals(1, asOperator("/tenet submit flex_tick 0").result(), "the press records");
        assertEquals(1, timesCompletedOf("flex_tick"), "counted at completion, with nothing to wait for");
        assertEquals(0, recordedTask("flex_tick", 0), "and cleared at completion too");
        assertEquals(1, asOperator("/tenet submit flex_tick 0").result(), "immediately replayable");
        assertEquals(2, timesCompletedOf("flex_tick"), "a second round counts again");
        note("a rewardless repeatable counted and cleared at completion");
    }

    @Test
    @Order(205)
    @DisplayName("a chapter of markers plays exactly like the chapter without them")
    void linksNeitherGateNorSatisfyAnything() {
        // The whole of a link's server-side contract, played: two quests gated the ordinary way with
        // two markers pointing at them. The markers must change nothing — no gating, no satisfying,
        // no completing, no paying — and the questline must read exactly as it would with no links file
        // at all. The mirror itself is drawn on the client; what is pinned here is that there is
        // nothing *to* mirror but the quests' own states.
        assertNotEquals(QuestState.COMPLETED, stateOf("link_b"), "gated, whatever points at it");
        assertEquals(1, asOperator("/tenet submit link_a 0").result(), "the gate should finish");
        assertEquals(QuestState.COMPLETED, stateOf("link_a"), "a quest with no gate finishes at once");
        assertEquals(QuestState.UNLOCKED, stateOf("link_b"),
                "open because its dependency finished, not because a marker named it");

        assertEquals(1, asOperator("/tenet submit link_b 0").result(), "the dependent finishes");
        assertEquals(QuestState.COMPLETED, stateOf("link_b"), "with no further action");
        note("two quests gated the ordinary way, with two markers watching and changing nothing");
    }

    @Test
    @Order(206)
    @DisplayName("a canvas press runs its command and fires its event on the server")
    void clicksDispatchOnTheServer() {
        // The cheat boundary, pressed for real: the wire's two ids resolve through the loaded index
        // to the file's own words, and neither a forged press nor a command press fires the bus.
        // The listener is permanent for the session, like every bus listener a mod registers — it
        // records into a list this order owns. It ignores player-less fires: the bus unit test
        // fires one with a null player, and either order has to work.
        List<String> fired = new ArrayList<>();
        dev.ellipog.tenet.api.TenetEvents.CLICK_EVENT.register((firedPlayer, id, chapter, element) -> {
            if (firedPlayer != null) {
                fired.add(id + "@" + chapter + ":" + element + " by "
                        + firedPlayer.getScoreboardName());
            }
        });

        server.callOnServerThread(() -> {
            TenetNetworking.handleClick(
                    new dev.ellipog.tenet.net.ClickPayload("link_works", "sig"), player);
            return null;
        });
        assertEquals(List.of("tenet:link_probe@link_works:sig by " + player.getScoreboardName()), fired,
                "the press fired the file's own event, naming where it landed and who pressed it");

        // A command press reaches the dispatcher without error at the pack's level: `help` needs
        // level 0, which is what the test pack configures by saying nothing.
        server.callOnServerThread(() -> {
            TenetNetworking.handleClick(
                    new dev.ellipog.tenet.net.ClickPayload("link_works", "cmd"), player);
            return null;
        });
        assertEquals(1, fired.size(), "a command is not an event, however it is addressed");

        // And a forged press names nothing, runs nothing and fires nothing.
        server.callOnServerThread(() -> {
            TenetNetworking.handleClick(
                    new dev.ellipog.tenet.net.ClickPayload("link_works", "ghost"), player);
            return null;
        });
        assertEquals(1, fired.size(), "a press with no action behind it is a warning, not a run");
        note("a press ran its command and fired its event; a forged one did neither");
    }

    @Test
    @Order(207)
    @DisplayName("the tick measures an ordinary presence task and never a manual-only one")
    void theTickSkipsManualTasks() {
        // The control and the manual quest want the same four coal: one tick must finish the
        // first and leave the second at zero. Coal throughout, which no other order spends, and
        // cleared inventories first so nothing earlier in the playthrough can answer instead.
        clearInventories();
        HeadlessServer.Outcome given = asOperator("/give @s minecraft:coal 4");
        assertEquals(1, given.result(), () -> "/give should have worked. It said:\n" + given.text());

        boolean controlled = tickUntil(() -> stateOf("manual_control") == QuestState.COMPLETED,
                Duration.ofSeconds(20));
        assertTrue(controlled, () -> "four coal did not complete manual_control within twenty seconds."
                + "\n  the quest is " + stateOf("manual_control")
                + " and task 0 is recorded at " + recordedTask("manual_control", 0) + " of 4.");
        assertEquals(0, recordedTask("manual_press", 0),
                "the tick measured nothing for the manual task, so there was nothing to record");
        assertNotEquals(QuestState.COMPLETED, stateOf("manual_press"),
                "and an unmeasured task cannot have finished");
        note("four coal finished the ordinary presence quest on the tick; the manual twin stayed at zero");
    }

    @Test
    @Order(208)
    @DisplayName("a manual-only presence task completes by press, and refuses a short one")
    void manualTasksCompleteByPress() {
        // Four coal against sixty-four must refuse the press rather than record it: the submit
        // path measures a manual task the way the tick measures an ordinary one.
        HeadlessServer.Outcome refused = asOperator("/tenet submit manual_short 0");
        assertEquals(0, refused.result(),
                () -> "a short press should have been refused. It said:\n" + refused.text());
        assertNotEquals(QuestState.COMPLETED, stateOf("manual_short"),
                "a refused press records nothing");

        HeadlessServer.Outcome submitted = asOperator("/tenet submit manual_press 0");
        assertEquals(1, submitted.result(),
                () -> "the press should have been accepted. It said:\n" + submitted.text());
        assertEquals(QuestState.COMPLETED, stateOf("manual_press"),
                "four coal, pressed by hand, is a complete quest");
        assertEquals(4, countInInventory(Items.COAL),
                "a presence task keeps everything it counted");
        note("a short manual press was refused; the full one completed and kept the coal");
    }

    @Test
    @Order(209)
    @DisplayName("an or-filter of two items completes on the tick holding one of each")
    void filterOrCompletesOnTheTick() {
        // The Gallery's own coal, and nothing else: the manual gallery's four are cleared first
        // so this measures only what it is given here. One coal and one charcoal are two matches
        // for an or() of the two — the tick counts either side.
        clearInventories();
        HeadlessServer.Outcome given = asOperator("/give @s minecraft:coal 1");
        assertEquals(1, given.result(), () -> "/give should have worked. It said:\n" + given.text());
        HeadlessServer.Outcome givenChar = asOperator("/give @s minecraft:charcoal 1");
        assertEquals(1, givenChar.result(), () -> "/give should have worked. It said:\n" + givenChar.text());

        boolean done = tickUntil(() -> stateOf("filter_or") == QuestState.COMPLETED,
                Duration.ofSeconds(20));
        assertTrue(done, () -> "a coal and a charcoal did not complete filter_or within twenty seconds."
                + "\n  the quest is " + stateOf("filter_or")
                + " and task 0 is recorded at " + recordedTask("filter_or", 0) + " of 2.");
        note("a coal and a charcoal finished the or-filter on the tick");
    }

    @Test
    @Order(210)
    @DisplayName("an and-filter of tag and mod is taken by the press")
    void filterTakeConsumesByPress() {
        // Two coal: both in minecraft:coals and both from minecraft, so the and() matches twice.
        // Cleared first, so the press takes exactly what it is given here and the count proves it.
        clearInventories();
        HeadlessServer.Outcome given = asOperator("/give @s minecraft:coal 2");
        assertEquals(1, given.result(), () -> "/give should have worked. It said:\n" + given.text());

        HeadlessServer.Outcome submitted = asOperator("/tenet submit filter_take 0");
        assertEquals(1, submitted.result(),
                () -> "the press should have been accepted. It said:\n" + submitted.text());
        assertEquals(QuestState.COMPLETED, stateOf("filter_take"),
                "two matching coal, pressed by hand, is a complete quest");
        assertEquals(0, countInInventory(Items.COAL) + countInInventory(Items.CHARCOAL),
                "a consuming filter takes what it counted");
        note("the and-filter took its two coal on the press and completed");
    }

    /** Torches holding half a bucket of water each: the fake the charge orders measure through. */
    private static dev.ellipog.tenet.inventory.FluidAccess torchWater() {
        return new dev.ellipog.tenet.inventory.FluidAccess() {
            private static final net.minecraft.resources.Identifier WATER =
                    net.minecraft.resources.Identifier.withDefaultNamespace("water");

            @Override
            public int storedOf(ServerPlayer player, net.minecraft.resources.Identifier fluid,
                               net.minecraft.world.item.Item bucketToSkip) {
                if (!fluid.equals(WATER)) {
                    return 0;
                }
                int torches = 0;
                var inventory = player.getInventory();
                for (int slot = 0; slot < inventory.getContainerSize(); slot++) {
                    net.minecraft.world.item.ItemStack stack = inventory.getItem(slot);
                    // The contract's skip, mirrored: stacks of the bucket item are measured by
                    // Tenet's own bucket loop, so this counts them never. See FluidAccess.
                    if (stack.is(bucketToSkip)) {
                        continue;
                    }
                    if (stack.is(Items.TORCH)) {
                        torches += stack.getCount();
                    }
                }
                return torches * 500;
            }

            @Override
            public int drainFrom(ServerPlayer player, net.minecraft.resources.Identifier fluid,
                                int mb, net.minecraft.world.item.Item bucketToSkip) {
                if (!fluid.equals(WATER) || mb <= 0) {
                    return 0;
                }
                // Discrete vessels: a torch gives five hundred millibuckets or nothing, so a
                // remainder under half a bucket stays where it is.
                int remaining = mb;
                var inventory = player.getInventory();
                for (int slot = 0; slot < inventory.getContainerSize() && remaining >= 500; slot++) {
                    net.minecraft.world.item.ItemStack stack = inventory.getItem(slot);
                    if (stack.is(bucketToSkip)) {
                        continue;
                    }
                    while (remaining >= 500 && stack.is(Items.TORCH) && !stack.isEmpty()) {
                        stack.shrink(1);
                        remaining -= 500;
                        inventory.setItem(slot,
                                stack.isEmpty() ? net.minecraft.world.item.ItemStack.EMPTY : stack);
                    }
                }
                inventory.setChanged();
                return mb - remaining;
            }
        };
    }

    /** Coal holding a thousand Forge Energy units a piece, honouring the per-item cap. */
    private static dev.ellipog.armature.api.energy.EnergyAccess coalCharge() {
        return new dev.ellipog.armature.api.energy.EnergyAccess() {
            @Override
            public int storedOf(ServerPlayer player, int maxPerItem) {
                int perItem = maxPerItem > 0 ? Math.min(1000, maxPerItem) : 1000;
                int found = 0;
                var inventory = player.getInventory();
                for (int slot = 0; slot < inventory.getContainerSize(); slot++) {
                    net.minecraft.world.item.ItemStack stack = inventory.getItem(slot);
                    if (stack.is(Items.COAL)) {
                        found += stack.getCount() * perItem;
                    }
                }
                return found;
            }

            @Override
            public int drainFrom(ServerPlayer player, int amount, int maxPerItem) {
                if (amount <= 0) {
                    return 0;
                }
                int perItem = maxPerItem > 0 ? Math.min(1000, maxPerItem) : 1000;
                int remaining = amount;
                var inventory = player.getInventory();
                for (int slot = 0; slot < inventory.getContainerSize() && remaining > 0; slot++) {
                    net.minecraft.world.item.ItemStack stack = inventory.getItem(slot);
                    while (remaining > 0 && stack.is(Items.COAL) && !stack.isEmpty()) {
                        stack.shrink(1);
                        remaining -= Math.min(remaining, perItem);
                        inventory.setItem(slot,
                                stack.isEmpty() ? net.minecraft.world.item.ItemStack.EMPTY : stack);
                    }
                }
                inventory.setChanged();
                return amount - remaining;
            }
        };
    }

    @Test
    @Order(211)
    @DisplayName("a fluid press drains the bucket first and a container for the rest")
    void fluidTakeDrainsBucketsThenContainers() {
        // One bucket (a thousand) plus one torch (five hundred fake millibuckets) against fifteen
        // hundred asked: the take must empty the bucket, hand the empty back, and spend the torch.
        // The seam is installed for this order alone and reset after it, whatever happens.
        dev.ellipog.tenet.inventory.FluidAccesses.install(torchWater());
        try {
            clearInventories();
            assertEquals(1, asOperator("/give @s minecraft:water_bucket").result(),
                    () -> "the bucket should have arrived");
            assertEquals(1, asOperator("/give @s minecraft:torch").result(),
                    () -> "the torch should have arrived");

            tickOnce();
            assertEquals(0, recordedTask("fluid_jug", 0),
                    "fluid always waits for the press, containers or not");
            assertEquals(1, countInInventory(Items.WATER_BUCKET), "sanity: the bucket is being held");
            assertEquals(1, countInInventory(Items.TORCH), "sanity: the torch is being held");

            HeadlessServer.Outcome submitted = asOperator("/tenet submit fluid_jug 0");
            assertEquals(1, submitted.result(),
                    () -> "the press should have been accepted. It said:\n" + submitted.text());
            assertEquals(QuestState.COMPLETED, stateOf("fluid_jug"),
                    "a bucket and a torch, pressed by hand, is fifteen hundred millibuckets");
            assertEquals(0, countInInventory(Items.WATER_BUCKET), "the full bucket is spent");
            assertEquals(1, countInInventory(Items.BUCKET),
                    "the emptied bucket comes straight back");
            assertEquals(0, countInInventory(Items.TORCH), "and the container is spent");
            note("a fluid press drained its bucket first and its container for the rest");
        }
        finally {
            dev.ellipog.tenet.inventory.FluidAccesses.reset();
        }
    }

    @Test
    @Order(212)
    @DisplayName("stored energy is drained by the press")
    void energyDrainsByPress() {
        dev.ellipog.armature.api.energy.EnergyAccesses.install(coalCharge());
        try {
            clearInventories();
            assertEquals(1, asOperator("/give @s minecraft:coal 2").result(),
                    () -> "the coal should have arrived");

            HeadlessServer.Outcome submitted = asOperator("/tenet submit energy_cell 0");
            assertEquals(1, submitted.result(),
                    () -> "the press should have been accepted. It said:\n" + submitted.text());
            assertEquals(QuestState.COMPLETED, stateOf("energy_cell"),
                    "two charged coal, pressed by hand, is two thousand Forge Energy");
            assertEquals(0, countInInventory(Items.COAL), "and the charge is spent");
            note("an energy press drained its two coal and completed");
        }
        finally {
            dev.ellipog.armature.api.energy.EnergyAccesses.reset();
        }
    }

    @Test
    @Order(213)
    @DisplayName("a per-item cap refuses a short press and completes a full one")
    void energyCapRefusesShortPresses() {
        // Two coal at five hundred apiece are one thousand against two thousand asked: the press
        // measures under the cap and is refused. Two more coal make two thousand, and the same
        // press completes and spends all four.
        dev.ellipog.armature.api.energy.EnergyAccesses.install(coalCharge());
        try {
            clearInventories();
            assertEquals(1, asOperator("/give @s minecraft:coal 2").result(),
                    () -> "the coal should have arrived");

            HeadlessServer.Outcome refused = asOperator("/tenet submit energy_cap 0");
            assertEquals(0, refused.result(),
                    () -> "a capped press should have been refused. It said:\n" + refused.text());
            assertNotEquals(QuestState.COMPLETED, stateOf("energy_cap"),
                    "a refused press records nothing");

            assertEquals(1, asOperator("/give @s minecraft:coal 2").result(),
                    () -> "the coal should have arrived");
            HeadlessServer.Outcome submitted = asOperator("/tenet submit energy_cap 0");
            assertEquals(1, submitted.result(),
                    () -> "the press should have been accepted. It said:\n" + submitted.text());
            assertEquals(QuestState.COMPLETED, stateOf("energy_cap"),
                    "four capped coal, pressed by hand, is two thousand Forge Energy");
            assertEquals(0, countInInventory(Items.COAL), "and the charge is spent");
            note("a capped energy press refused two coal and completed on four");
        }
        finally {
            dev.ellipog.armature.api.energy.EnergyAccesses.reset();
        }
    }

    /** The flag the team gallery grants: the party's, not whoever claimed it. */
    private static final Identifier TEAM_OATH =
            Identifier.fromNamespaceAndPath("the_induction", "team_oath");

    /** Whether a team holds a stage, read on the server thread like every other read here. */
    private static boolean teamHasStage(UUID team, Identifier stage) {
        return server.callOnServerThread(
                () -> dev.ellipog.tenet.progress.StageService.hasTeam(server.server(), team, stage));
    }

    @Test
    @Order(214)
    @DisplayName("a team stage reward marks the whole party, and a team task completes for both")
    void aTeamStageRewardMarksTheWholeParty() {
        // A party of two, built the way order 95 builds one: by command, invited, accepted.
        assertEquals(1, asOperator("/tenet party create the-oath-party").result());
        assertEquals(1, asOperator("/tenet party invite tenet-friend").result());
        assertTrue(server.callOnServerThread(
                        () -> Teams.of(server.server()).acceptInvite(friend.getUUID()).isPresent()),
                "the friend joins, so the party has two online members");
        UUID team = ownerOf(player);
        assertEquals(team, ownerOf(friend), "both members resolve to the party");

        assertFalse(teamHasStage(team, TEAM_OATH), "the party starts holding nothing");
        assertFalse(hasStage(player, TEAM_OATH), "and neither does the player personally");
        assertFalse(hasStage(friend, TEAM_OATH), "nor the friend");

        HeadlessServer.Outcome submitted = asOperator("/tenet submit team_summons 0");
        assertEquals(1, submitted.result(),
                () -> "the checkmark should have been accepted:\n" + submitted.text());
        HeadlessServer.Outcome claimed = asOperator("/tenet claim team_summons");
        assertEquals(1, claimed.result(), () -> "the team stage should have been granted:\n"
                + claimed.text());

        assertTrue(teamHasStage(team, TEAM_OATH), "the claim granted the stage to the team");
        assertFalse(hasStage(player, TEAM_OATH),
                "while the claiming player holds nothing personally");
        assertFalse(hasStage(friend, TEAM_OATH), "and neither does the friend");

        // The team task is measured, never handed in: it completes by itself once the team holds
        // the flag, for a party whose members each hold nothing.
        assertTrue(tickUntil(() -> stateOf("team_mark") == QuestState.COMPLETED,
                        Duration.ofSeconds(20)),
                () -> "team_mark's team task did not satisfy once the team held the oath. It is "
                        + stateOf("team_mark"));
        assertEquals(QuestState.COMPLETED, stateFor(friend, "team_mark"),
                "and the friend reads the same stored answer, because progress is the party's");
        assertFalse(stageLocked().contains("team_mark"),
                "the team gate is open for a member holding nothing: " + stageLocked());

        note("a team stage reward marked the party rather than the claimer, and the team task"
                + " completed for both members");
    }

    @Test
    @Order(215)
    @DisplayName("a team gate serves the member holding nothing, and the team commands move it")
    void aTeamGateServesTheMemberHoldingNothing() {
        // The friend never held anything personally, and the quest is already finished for the
        // party -- so what the gate owes them is their own copy of the payout, collected like any
        // member's. A gate that read personal stages would refuse this claim.
        clearInventories();
        HeadlessServer.Outcome claimed = asOperator(friend, "/tenet claim team_mark");
        assertEquals(1, claimed.result(), () -> "the friend's claim should have paid:\n"
                + claimed.text());
        assertEquals(1, countInInventoryOf(friend, Items.GOLDEN_APPLE),
                "the member holding nothing personally is paid through the team gate");
        assertFalse(hasStage(friend, TEAM_OATH),
                "and still holds nothing personally -- the team holds it");

        // The team commands, which are the legacy format's /ftbteams teamstage under Tenet's name: take the
        // oath away and the gate shuts for both members; grant it back and it opens.
        UUID team = ownerOf(player);
        HeadlessServer.Outcome listed = asOperator("/tenet stage team-list tenet-tester");
        assertTrue(listed.text().contains("the_induction:team_oath"),
                "team-list names what the team holds:\n" + listed.text());

        HeadlessServer.Outcome removed = asOperator("/tenet stage remove-team tenet-tester "
                + TEAM_OATH);
        assertEquals(1, removed.result(), () -> "the team stage should have been taken away:\n"
                + removed.text());
        assertFalse(teamHasStage(team, TEAM_OATH));
        assertTrue(stageLocked().contains("team_mark"),
                "with the team's oath gone the gate is shut again: " + stageLocked());

        HeadlessServer.Outcome regranted = asOperator("/tenet stage add-team tenet-friend "
                + TEAM_OATH);
        assertEquals(1, regranted.result(), () -> "naming either member grants the team:\n"
                + regranted.text());
        assertTrue(teamHasStage(team, TEAM_OATH));
        assertFalse(stageLocked().contains("team_mark"), "and the gate is open again");

        note("the team gate paid the member holding nothing, and add-team/remove-team moved it");
    }

    @Test
    @Order(216)
    @DisplayName("a team-removing reward clears the team's flag, and leaving takes it away")
    void aTeamRemovingRewardClearsTheTeamsFlag() {
        UUID team = ownerOf(player);
        assertTrue(teamHasStage(team, TEAM_OATH), "order 215 left the team holding the oath");

        HeadlessServer.Outcome submitted = asOperator("/tenet submit team_fall 0");
        assertEquals(1, submitted.result(),
                () -> "the checkmark should have been accepted:\n" + submitted.text());
        HeadlessServer.Outcome claimed = asOperator("/tenet claim team_fall");
        assertEquals(1, claimed.result(), () -> "the fall should have been collected:\n"
                + claimed.text());
        assertFalse(teamHasStage(team, TEAM_OATH), "the fall's reward took the team's stage away");
        assertTrue(stageLocked().contains("team_mark"),
                "cleared, so the team gate is shut again: " + stageLocked());

        // The personal vocabulary is untouched by every team operation above: team stages live
        // beside player stages, and neither answers for the other.
        assertTrue(hasStage(player, THE_MARK),
                "the player's own mark survived the team's oath coming and going");

        assertEquals(1, asOperator("/tenet party disband").result(), "and the party is cleaned up");
        assertFalse(teamHasStage(team, TEAM_OATH),
                "disbanding changes no flag -- the gone party's oath simply has no party left");
        assertTrue(stageLocked().contains("team_mark"),
                "solo again with no team oath, the gate reads shut: " + stageLocked());

        note("the fall cleared the team's oath while the player's own mark stayed held");
    }

    @Test
    @Order(217)
    @DisplayName("complete with-dependencies finishes the chain from its start")
    void completeWithDependenciesFinishesTheChain() {
        // team_fall waits on team_mark, and both are cleared: one press must finish the dependency
        // first and the quest itself second, rather than refusing the locked chain.
        assertEquals(1, asOperator("/tenet reset team_mark").result());
        assertEquals(1, asOperator("/tenet reset team_fall").result());
        assertNotEquals(QuestState.COMPLETED, stateOf("team_mark"), "both start cleared");
        assertNotEquals(QuestState.COMPLETED, stateOf("team_fall"), "both start cleared");
        assertEquals(1, asOperator("/tenet stage add-team tenet-tester " + TEAM_OATH).result(),
                "the team gate must be open for the sweep to reach the fall");

        HeadlessServer.Outcome swept = asOperator("/tenet complete team_fall with-dependencies");
        assertTrue(swept.result() >= 2,
                () -> "the sweep should have finished the mark and the fall. It said:\n" + swept.text());
        assertEquals(QuestState.COMPLETED, stateOf("team_mark"), "the dependency finished first");
        assertEquals(QuestState.COMPLETED, stateOf("team_fall"), "and then the quest itself");

        note("complete with-dependencies finished team_mark and team_fall in one press");
    }

    @Test
    @Order(218)
    @DisplayName("reset-all and complete-all move a whole book, for a named player too")
    void resetAllAndCompleteAllMoveAWholeBook() {
        // The chain order 217 finished, so resetting with dependencies must clear both nodes back.
        HeadlessServer.Outcome reset = asOperator("/tenet reset team_fall with-dependencies");
        assertTrue(reset.result() >= 2,
                () -> "resetting with dependencies should have cleared two. It said:\n" + reset.text());
        assertNotEquals(QuestState.COMPLETED, stateOf("team_mark"));
        assertNotEquals(QuestState.COMPLETED, stateOf("team_fall"));

        // complete-all finishes what can be finished: the checkmark chain among it. The count is
        // what the sweep reports, and the chain's own states are what it proves.
        HeadlessServer.Outcome swept = asOperator("/tenet complete-all");
        assertTrue(swept.result() >= 2,
                () -> "complete-all should have finished at least the team chain. It said:\n"
                        + swept.text());
        assertEquals(QuestState.COMPLETED, stateOf("team_mark"));
        assertEquals(QuestState.COMPLETED, stateOf("team_fall"));

        // And the named-player spellings move somebody else's book: the friend is solo, so their
        // record is their own to clear and to finish.
        HeadlessServer.Outcome friendReset = asOperator("/tenet reset-all tenet-friend");
        assertTrue(friendReset.result() >= 1,
                () -> "reset-all for a named player should have cleared something. It said:\n"
                        + friendReset.text());
        assertEquals(1, asOperator("/tenet stage add-team tenet-friend " + TEAM_OATH).result(),
                "the friend's own team needs the oath for the team gate to open there");
        HeadlessServer.Outcome friendSweep = asOperator("/tenet complete-all tenet-friend");
        assertTrue(friendSweep.result() >= 1,
                () -> "complete-all for a named player should have finished something. It said:\n"
                        + friendSweep.text());
        assertEquals(QuestState.COMPLETED, stateFor(friend, "team_mark"),
                "the friend's own book finished the team chain");

        // Back to a clean chain for whatever comes next: reset-all clears the invoker's own book.
        assertTrue(asOperator("/tenet reset-all").result() >= 1, "reset-all clears the book");
        assertNotEquals(QuestState.COMPLETED, stateOf("team_mark"));

        note("reset-all and complete-all cleared and finished whole books, self and named");
    }

    @Test
    @Order(219)
    @DisplayName("open_book asks the client to open, and names a missing quest honestly")
    void openBookAsksTheClientToOpen() {
        // Headless, so there is no screen to see: what the command owns is asking (the payload to
        // the invoker) and refusing a quest the book does not hold. The opening itself is the
        // client's handler, which a server test cannot see.
        assertEquals(1, asOperator("/tenet open_book").result(),
                "opening the book plainly should be accepted");
        assertEquals(1, asOperator("/tenet open_book team_mark").result(),
                "opening it on a quest the book holds should be accepted");
        HeadlessServer.Outcome missing = asOperator("/tenet open_book no_such_quest");
        assertEquals(0, missing.result(), "and a quest nobody holds must be refused, not sent");

        note("open_book asked twice and refused the missing quest");
    }

    @Test
    @Order(220)
    @DisplayName("a taskless junction completes on the tick its last dependency does")
    void tasklessJunctionCompletesWithItsGate() {
        // the legacy junction nodes: a quest with no tasks auto-completes once its dependencies are
        // met, with no press on it, and its own dependant unlocks behind it. Solo, for the reason
        // order 200 gives: a leftover party would count the wrong player's progress.
        asOperator("/tenet party leave");
        assertEquals(player.getUUID(), ownerOf(player), "solo for this test: no party counting");

        // A replay from a clean slate whatever order 218's sweep left behind: clearing the
        // dependant clears its whole closure, so the gate and both of its own gates are fresh.
        asOperator("/tenet reset junction_after with-dependencies");
        tickOnce();

        assertEquals(QuestState.LOCKED, stateOf("junction_gate"), "gated twice over, so locked");
        assertEquals(QuestState.LOCKED, stateOf("junction_after"), "and so is what waits on it");

        assertEquals(1, asOperator("/tenet submit junction_a 0").result(), "the first press records");
        assertEquals(QuestState.COMPLETED, stateOf("junction_a"),
                "a quest with no gate finishes at once");
        assertNotEquals(QuestState.COMPLETED, stateOf("junction_gate"),
                "one of two gates is still shut");

        assertEquals(1, asOperator("/tenet submit junction_b 0").result(),
                "the second press records");
        assertTrue(tickUntil(() -> stateOf("junction_gate") == QuestState.COMPLETED,
                        Duration.ofSeconds(20)),
                "the taskless junction should finish on a tick once its last dependency does,"
                        + " with no press on it");
        assertNotEquals(QuestState.LOCKED, stateOf("junction_after"),
                "and its dependant unlocks behind it");

        note("a taskless junction finished on its gate's tick and opened its dependant");
    }

    @Test
    @Order(221)
    @DisplayName("break events record into ordinary progress and complete the quest")
    void breakEventsRecordAndComplete() {
        // Solo, for the reason order 200 gives: a leftover party would count the wrong player's
        // progress. Fresh slate: whatever earlier orders left behind is not this order's.
        asOperator("/tenet party leave");
        assertEquals(player.getUUID(), ownerOf(player), "solo for this test: no party counting");
        asOperator("/tenet reset break_stone");

        // The event's real entry point but not its real trigger: no loader fires events in a
        // test JVM, so this calls it directly — the same seam tickUntil uses for the tick.
        var stone = Blocks.STONE.defaultBlockState();
        var dirt = Blocks.DIRT.defaultBlockState();

        Set<UUID> untouched = server.callOnServerThread(
                () -> ProgressService.onBlockBroken(server.server(), player, dirt));
        assertTrue(untouched.isEmpty(), "a dirt break moves nothing on a stone quest");
        assertEquals(0, recordedTask("break_stone", 0));

        for (int i = 0; i < 4; i++) {
            server.callOnServerThread(
                    () -> ProgressService.onBlockBroken(server.server(), player, stone));
        }
        assertEquals(4, recordedTask("break_stone", 0), "four stone breaks are four recorded");
        assertEquals(QuestState.COMPLETED, stateOf("break_stone"),
                "and recorded progress completes like any other");

        note("four stone breaks recorded and completed; dirt counted nothing");
    }

    @Test
    @Order(222)
    @DisplayName("breaks count by tag and by state, on a server with a datapack")
    void breaksCountByTagAndState() {
        asOperator("/tenet reset break_logs");
        asOperator("/tenet reset break_wheat");

        var log = Blocks.OAK_LOG.defaultBlockState();
        var stone = Blocks.STONE.defaultBlockState();
        var ripe = Blocks.WHEAT.defaultBlockState().setValue(CropBlock.AGE, 7);
        var green = Blocks.WHEAT.defaultBlockState().setValue(CropBlock.AGE, 0);

        server.callOnServerThread(() -> ProgressService.onBlockBroken(server.server(), player, stone));
        assertEquals(0, recordedTask("break_logs", 0), "stone is not a log");
        for (int i = 0; i < 4; i++) {
            server.callOnServerThread(
                    () -> ProgressService.onBlockBroken(server.server(), player, log));
        }
        assertEquals(QuestState.COMPLETED, stateOf("break_logs"),
                "four logs against minecraft:logs is a complete quest — the bound-tag positive");

        server.callOnServerThread(() -> ProgressService.onBlockBroken(server.server(), player, green));
        assertEquals(0, recordedTask("break_wheat", 0), "green wheat is not ripe");
        for (int i = 0; i < 4; i++) {
            server.callOnServerThread(
                    () -> ProgressService.onBlockBroken(server.server(), player, ripe));
        }
        assertEquals(QuestState.COMPLETED, stateOf("break_wheat"),
                "four ripe breaks are a harvest");

        note("logs completed by tag; only ripe wheat counted");
    }

    @Test
    @Order(223)
    @DisplayName("a bucket worn in the offhand or armour counts like one in the pack")
    void fluidBucketsCountInEverySlot() {
        // The seam contract measures every slot the inventory reports, and the bucket half is
        // Tenet's own loop over the same range: a water bucket in the offhand and one in the
        // boots counts two thousand millibuckets with an empty main inventory.
        dev.ellipog.tenet.inventory.FluidAccesses.install(torchWater());
        try {
            clearInventories();
            server.onServerThread(() -> {
                // Container slot indices now: the raw armor/offhand lists are private.
                player.getInventory().setItem(net.minecraft.world.entity.player.Inventory.SLOT_OFFHAND,
                        ItemStacks.stackOf(Items.WATER_BUCKET));
                player.getInventory().setItem(net.minecraft.world.entity.player.Inventory.SLOT_BODY_ARMOR,
                        ItemStacks.stackOf(Items.WATER_BUCKET));
                player.getInventory().setChanged();
            });
            dev.ellipog.tenet.quest.task.FluidTask task =
                    new dev.ellipog.tenet.quest.task.FluidTask(
                            new dev.ellipog.tenet.quest.TaskCommon(false, 20),
                            net.minecraft.resources.Identifier.withDefaultNamespace("water"),
                            2000, false);
            int measured = server.callOnServerThread(() ->
                    dev.ellipog.tenet.quest.task.FluidTask.BEHAVIOUR.current(task,
                            new dev.ellipog.tenet.quest.TaskContext(player,
                                    dev.ellipog.tenet.quest.TenetQuests.index(), gameTime())));
            assertEquals(2000, measured,
                    "an offhand bucket plus an armour bucket is two thousand millibuckets");
            note("a bucket in the offhand and one in the armour counted like two in the pack");
        }
        finally {
            dev.ellipog.tenet.inventory.FluidAccesses.reset();
        }
    }

    @Test
    @Order(224)
    @DisplayName("the task hands its own bucket as the seam's skip")
    void fluidTaskPassesItsBucketAsTheSkip() {
        // The no-double-count half of the contract: Tenet measures buckets itself, so the seam
        // must skip stacks of exactly the bucket item. This pins the consumer half — the task
        // hands its fluid's bucket, not some other item — with a fake that records what it got.
        java.util.concurrent.atomic.AtomicReference<net.minecraft.world.item.Item> seen =
                new java.util.concurrent.atomic.AtomicReference<>();
        dev.ellipog.tenet.inventory.FluidAccess inner = torchWater();
        dev.ellipog.tenet.inventory.FluidAccess recording =
                new dev.ellipog.tenet.inventory.FluidAccess() {
                    @Override
                    public int storedOf(ServerPlayer player,
                                        net.minecraft.resources.Identifier fluid,
                                        net.minecraft.world.item.Item bucketToSkip) {
                        seen.set(bucketToSkip);
                        return inner.storedOf(player, fluid, bucketToSkip);
                    }

                    @Override
                    public int drainFrom(ServerPlayer player,
                                         net.minecraft.resources.Identifier fluid, int mb,
                                         net.minecraft.world.item.Item bucketToSkip) {
                        seen.set(bucketToSkip);
                        return inner.drainFrom(player, fluid, mb, bucketToSkip);
                    }
                };
        dev.ellipog.tenet.inventory.FluidAccesses.install(recording);
        try {
            clearInventories();
            assertEquals(1, asOperator("/give @s minecraft:water_bucket").result(),
                    () -> "the bucket should have arrived");
            assertEquals(1, asOperator("/give @s minecraft:torch").result(),
                    () -> "the torch should have arrived");

            HeadlessServer.Outcome submitted = asOperator("/tenet submit fluid_jug 0");
            assertEquals(1, submitted.result(),
                    () -> "the press should have been accepted. It said:\n" + submitted.text());
            assertEquals(QuestState.COMPLETED, stateOf("fluid_jug"),
                    "a bucket and a torch, pressed by hand, is fifteen hundred millibuckets");
            assertEquals(net.minecraft.world.item.Items.WATER_BUCKET, seen.get(),
                    "the seam was told to skip the task's own bucket, so buckets cannot count twice");
            note("the fluid press handed its bucket as the skip and completed");
        }
        finally {
            dev.ellipog.tenet.inventory.FluidAccesses.reset();
        }
    }

    @Test
    @Order(225)
    @DisplayName("the seam double skips the bucket and counts everything else")
    void fluidSeamFakeHonoursTheSkip() {
        // The loader half of the contract, held by the house fake every seam test runs against:
        // stacks of the skip item are not vessels, and everything else measures. The real
        // loaders honor the same promise in their own slot loops — see FluidAccess — which no
        // headless test can run, so this is the seam's executable copy of it.
        var access = torchWater();
        clearInventories();
        assertEquals(1, asOperator("/give @s minecraft:torch 2").result(),
                () -> "the torches should have arrived");
        assertEquals(0, server.callOnServerThread(() -> access.storedOf(player,
                        net.minecraft.resources.Identifier.withDefaultNamespace("water"),
                        net.minecraft.world.item.Items.TORCH)),
                "two torches read as the skip item are no vessels at all");
        assertEquals(1000, server.callOnServerThread(() -> access.storedOf(player,
                        net.minecraft.resources.Identifier.withDefaultNamespace("water"),
                        net.minecraft.world.item.Items.WATER_BUCKET)),
                "the same two torches measure a thousand millibuckets against any other skip");
        note("the skip excluded the skip item and nothing else");
    }

    @Test
    @Order(226)
    @DisplayName("charge worn in the offhand or armour counts like charge in the pack")
    void energyChargeCountsInEverySlot() {
        // The energy contract's slot promise: every slot the inventory reports is measured, main,
        // armour and offhand alike. A coal in the offhand and one in the boots counts two
        // thousand Forge Energy with an empty main inventory.
        dev.ellipog.armature.api.energy.EnergyAccesses.install(coalCharge());
        try {
            clearInventories();
            server.onServerThread(() -> {
                // Container slot indices now: the raw armor/offhand lists are private.
                player.getInventory().setItem(net.minecraft.world.entity.player.Inventory.SLOT_OFFHAND,
                        ItemStacks.stackOf(Items.COAL));
                player.getInventory().setItem(net.minecraft.world.entity.player.Inventory.SLOT_BODY_ARMOR,
                        ItemStacks.stackOf(Items.COAL));
                player.getInventory().setChanged();
            });
            dev.ellipog.tenet.quest.task.EnergyTask task =
                    new dev.ellipog.tenet.quest.task.EnergyTask(
                            new dev.ellipog.tenet.quest.TaskCommon(false, 20), 2000, 0, false);
            int measured = server.callOnServerThread(() ->
                    dev.ellipog.tenet.quest.task.EnergyTask.BEHAVIOUR.current(task,
                            new dev.ellipog.tenet.quest.TaskContext(player,
                                    dev.ellipog.tenet.quest.TenetQuests.index(), gameTime())));
            assertEquals(2000, measured,
                    "an offhand coal plus an armour coal is two thousand Forge Energy");
            note("charge in the offhand and the armour counted like charge in the pack");
        }
        finally {
            dev.ellipog.armature.api.energy.EnergyAccesses.reset();
        }
    }

    @Test
    @DisplayName("a locale is packed once per language, however many players read it")
    void aLocaleIsPackedOncePerLanguage() {
        // The claim the per-locale cache exists for, and it is measured rather than asserted in prose
        // because a cache that quietly stopped working would show up as a number nobody reads. The
        // reason it matters: `sendTreeTo` already pays one encode and one deflate **per recipient** and
        // is instrumented for exactly that (see `WireTiming`), so a second per-recipient encode would
        // be the wrong direction twice over.
        //
        // Run without `@Order`, so it goes last, after the playthrough has finished with the server.
        //
        // The language is `es_es` rather than whatever these players read, and that is the point of
        // the test: it is a locale nobody has asked for yet, so the first call is a genuine miss and
        // the second is a genuine hit. The shipped example translates `es_es`, so the bundle is real
        // rather than an empty object that would be cheap either way.
        boolean wasOn = EditPhases.on();
        EditPhases.set(true);
        try {
            QuestSync.drainWireTiming();
            server.callOnServerThread(() -> {
                QuestSync.sendLocaleFor(player, "es_es");
                return null;
            });
            QuestSync.WireTiming first = QuestSync.drainWireTiming();

            server.callOnServerThread(() -> {
                QuestSync.sendLocaleFor(player, "es_es");
                return null;
            });
            QuestSync.WireTiming second = QuestSync.drainWireTiming();

            assertTrue(first.encodeNanos() + first.deflateNanos() > 0,
                    "the first reader of a language pays to pack it");
            assertEquals(0, second.encodeNanos() + second.deflateNanos(),
                    "and the next reader of the same language reuses those bytes rather than rebuilding"
                            + " them -- which is what keeps a second encode off the per-recipient path");
        }
        finally {
            EditPhases.set(wasOn);
        }
    }

    /**
     * The auto-claim chapter, seeded beside the examples.
     *
     * <p>Written by the test rather than added to the example content on purpose: the examples are a
     * demonstration for authors — and {@code QuestIndexTest} reads them — so a test that needs an
     * auto-claim quest should not decide what the demonstration contains. The chapter states the mode,
     * which is the point: neither quest says anything about auto-claim, so this exercises the middle
     * rung rather than a per-quest setting.
     */
    private static List<String> seedAutoClaimChapter(Path configDir) throws IOException {
        Path quests = configDir.resolve("tenet/quests/auto_claim");
        Path chapter = quests.resolve("auto_claim");
        Files.createDirectories(chapter);
        Files.writeString(quests.resolve("group.json"), """
                { "id": "auto_claim", "title": "Auto Claim", "chapters": ["auto_claim"] }
                """);
        Files.writeString(chapter.resolve("chapter.json"), """
                { "$schema": "../../../_schema/chapter.schema.json",
                  "id": "auto_claim", "title": "Auto Claim", "autoClaim": "enabled",
                  "quests": ["paid.json", "choice.json", "gate.json"] }
                """);
        Files.writeString(chapter.resolve("paid.json"), """
                { "$schema": "../../../_schema/quest.schema.json", "id": "auto_paid", "title": "Auto Paid",
                  "tasks": [{ "type": "tenet:checkmark", "title": "Done" }],
                  "rewards": [{ "type": "tenet:item", "item": "minecraft:golden_apple", "count": 1 }] }
                """);
        Files.writeString(chapter.resolve("choice.json"), """
                { "$schema": "../../../_schema/quest.schema.json", "id": "auto_choice",
                  "title": "Auto Choice",
                  "tasks": [{ "type": "tenet:checkmark", "title": "Done" }],
                  "rewards": [{ "type": "tenet:choice", "inline": { "entries": [
                    { "weight": 1, "reward": { "type": "tenet:item", "item": "minecraft:diamond",
                        "count": 1 } },
                    { "weight": 1, "reward": { "type": "tenet:item", "item": "minecraft:emerald",
                        "count": 1 } } ] } }] }
                """);
        // The stage gate on an automatic payout: a member who never held the stage must not be paid
        // when the team finishes this, and the member who holds it must be. `the_induction:marked` is
        // the stage the gate orders above hand out and take away, so there is one vocabulary for it.
        Files.writeString(chapter.resolve("gate.json"), """
                { "$schema": "../../../_schema/quest.schema.json", "id": "auto_gate",
                  "title": "Auto Gate", "requiresStage": "the_induction:marked",
                  "tasks": [{ "type": "tenet:checkmark", "title": "Done" }],
                  "rewards": [{ "type": "tenet:item", "item": "minecraft:golden_apple", "count": 1 }] }
                """);
        // The relative names the load check counts, in the same shape `seedExamples` produces.
        return List.of("auto_claim/group.json", "auto_claim/auto_claim/chapter.json",
                "auto_claim/auto_claim/paid.json", "auto_claim/auto_claim/choice.json",
                "auto_claim/auto_claim/gate.json");
    }

    /**
     * The engine chapter: one of each mechanic the stage, condition and table orders below play,
     * seeded beside the examples.
     *
     * <p>Written by the test for the same reason {@link #seedAutoClaimChapter} is: the examples are
     * an exhibition for authors, and what it contains is the exhibition's call. These
     * orders, though, need quests shaped exactly for the assertion -- a gate with no dependency edge,
     * a reward behind a scoreboard that starts at zero, a table whose weight-zero entry makes the
     * experience delta exact -- and an exhibition that moved content around to hold the tests' hands
     * would be documentation written for the engine rather than for the author. So the fixture
     * travels with the test, the ids the orders name are the fixture's, and the two collections stay
     * free to change without breaking each other.
     */
    private static List<String> seedEngineChapter(Path configDir) throws IOException {
        Path quests = configDir.resolve("tenet/quests/engine_gallery");
        Path chapter = quests.resolve("the_galleries");
        Path tables = configDir.resolve("tenet/quests/reward_tables");
        Files.createDirectories(chapter);
        Files.createDirectories(tables);
        Files.writeString(quests.resolve("group.json"), """
                { "id": "engine_gallery", "title": "Engine Gallery", "chapters": ["the_galleries"] }
                """);
        Files.writeString(chapter.resolve("chapter.json"), """
                { "$schema": "../../../_schema/chapter.schema.json",
                  "id": "the_galleries", "title": "The Galleries",
                  "quests": ["the_summons.json", "the_mark.json", "the_fall.json",
                    "the_winnings.json", "the_password.json", "the_shopping_list.json",
                    "the_supply.json", "the_standing.json", "the_company.json",
                    "the_receipt.json", "the_false_criterion.json", "the_toll.json",
                    "ameth_start.json"] }
                """);
        Files.writeString(chapter.resolve("the_summons.json"), """
                { "$schema": "../../../_schema/quest.schema.json", "id": "the_summons",
                  "title": "The Summons",
                  "icon": { "item": "minecraft:paper" },
                  "tasks": [{ "type": "tenet:item", "item": "minecraft:writable_book" }],
                  "rewards": [{ "type": "tenet:stage", "stage": "the_induction:marked" }] }
                """);
        Files.writeString(chapter.resolve("the_mark.json"), """
                { "$schema": "../../../_schema/quest.schema.json", "id": "the_mark",
                  "title": "The Mark",
                  "icon": { "item": "minecraft:golden_apple" },
                  "requiresStage": "the_induction:marked",
                  "tasks": [{ "type": "tenet:stage", "stage": "the_induction:marked" }],
                  "rewards": [{ "type": "tenet:item", "item": "minecraft:golden_apple",
                    "auto": "disabled" }] }
                """);
        Files.writeString(chapter.resolve("the_fall.json"), """
                { "$schema": "../../../_schema/quest.schema.json", "id": "the_fall",
                  "title": "The Fall", "dependsOn": ["the_mark"],
                  "icon": { "item": "minecraft:wither_rose" },
                  "tasks": [{ "type": "tenet:checkmark", "title": "I will go" }],
                  "rewards": [{ "type": "tenet:stage", "stage": "the_induction:marked",
                    "remove": true }] }
                """);
        Files.writeString(chapter.resolve("the_winnings.json"), """
                { "$schema": "../../../_schema/quest.schema.json", "id": "the_winnings",
                  "title": "The Winnings",
                  "icon": { "item": "minecraft:gold_ingot" },
                  "tasks": [{ "type": "tenet:checkmark", "title": "I will take my chances" }],
                  "rewards": [{ "type": "tenet:loot", "table": "loot" }] }
                """);
        Files.writeString(chapter.resolve("the_password.json"), """
                { "$schema": "../../../_schema/quest.schema.json", "id": "the_password",
                  "title": "The Password",
                  "icon": { "item": "minecraft:name_tag" },
                  "tasks": [{ "type": "tenet:checkmark", "title": "Say the word",
                    "conditions": [{ "type": "tenet:stage",
                      "stage": "condition_gallery:password" }] }],
                  "rewards": [{ "type": "tenet:item", "item": "minecraft:book" }] }
                """);
        Files.writeString(chapter.resolve("the_shopping_list.json"), """
                { "$schema": "../../../_schema/quest.schema.json", "id": "the_shopping_list",
                  "title": "The Shopping List",
                  "icon": { "item": "minecraft:cobblestone" },
                  "tasks": [{ "type": "tenet:checkmark", "title": "Hand over the list",
                    "conditions": [{ "type": "tenet:item", "item": "minecraft:cobblestone",
                      "count": 8 }] }],
                  "rewards": [{ "type": "tenet:stage",
                    "stage": "condition_gallery:password" }] }
                """);
        Files.writeString(chapter.resolve("the_supply.json"), """
                { "$schema": "../../../_schema/quest.schema.json", "id": "the_supply",
                  "title": "The Supply",
                  "icon": { "item": "minecraft:chest" },
                  "tasks": [{ "type": "tenet:item", "item": "minecraft:cobblestone", "count": 8,
                    "conditions": [{ "type": "tenet:stage",
                      "stage": "condition_gallery:password" }] }] }
                """);
        Files.writeString(chapter.resolve("the_standing.json"), """
                { "$schema": "../../../_schema/quest.schema.json", "id": "the_standing",
                  "title": "The Standing",
                  "icon": { "item": "minecraft:gold_ingot" },
                  "tasks": [{ "type": "tenet:checkmark", "title": "Ask after your standing" }],
                  "rewards": [{ "type": "tenet:item", "item": "minecraft:gold_ingot",
                    "conditions": [{ "type": "tenet:score",
                      "objective": "condition_gallery_standing", "min": 5 }] }] }
                """);
        Files.writeString(chapter.resolve("the_company.json"), """
                { "$schema": "../../../_schema/quest.schema.json", "id": "the_company",
                  "title": "The Company",
                  "icon": { "item": "minecraft:golden_carrot" },
                  "tasks": [{ "type": "tenet:checkmark", "title": "Bring a friend",
                    "conditions": [{ "type": "tenet:party_size", "min": 2 }] }] }
                """);
        Files.writeString(chapter.resolve("the_receipt.json"), """
                { "$schema": "../../../_schema/quest.schema.json", "id": "the_receipt",
                  "title": "The Receipt",
                  "icon": { "item": "minecraft:emerald" },
                  "tasks": [{ "type": "tenet:checkmark", "title": "Present the receipt" }],
                  "rewards": [{ "type": "tenet:item", "item": "minecraft:emerald",
                    "conditions": [{ "type": "tenet:advancement",
                      "advancement": "minecraft:story/root" },
                      { "type": "tenet:item", "item": "minecraft:oak_log", "count": 8 }] }] }
                """);
        Files.writeString(chapter.resolve("the_false_criterion.json"), """
                { "$schema": "../../../_schema/quest.schema.json", "id": "the_false_criterion",
                  "title": "The False Criterion",
                  "icon": { "item": "minecraft:paper" },
                  "tasks": [{ "type": "tenet:checkmark", "title": "Say the false word",
                    "conditions": [{ "type": "tenet:advancement",
                      "advancement": "minecraft:story/root",
                      "criterion": "criterion_nothing_declares" }] }] }
                """);
        Files.writeString(chapter.resolve("ameth_start.json"), """
                { "$schema": "../../../_schema/quest.schema.json", "id": "ameth_start",
                  "title": "Amethyst Start",
                  "icon": { "item": "minecraft:amethyst_block" },
                  "tasks": [{ "type": "tenet:item", "item": "minecraft:amethyst_shard",
                    "count": 4 }],
                  "rewards": [{ "type": "tenet:item", "item": "minecraft:amethyst_block",
                    "count": 1 }] }
                """);
        // The one quest whose task costs something, and it says so on the task. The chapter states no
        // consume-items default, deliberately: this is about what an item task that *takes* does, not
        // about inheritance, which ConsentToTakeTest pins through the same registry door.
        Files.writeString(chapter.resolve("the_toll.json"), """
                { "$schema": "../../../_schema/quest.schema.json", "id": "the_toll",
                  "title": "The Toll",
                  "icon": { "item": "minecraft:amethyst_shard" },
                  "tasks": [{ "type": "tenet:item", "item": "minecraft:amethyst_shard",
                    "count": 8, "consumeItems": true }] }
                """);
        Files.writeString(tables.resolve("loot.json"), """
                { "emptyWeight": 3, "lootSize": 1,
                  "entries": [
                    { "weight": 0, "reward": { "type": "tenet:xp", "amount": 5 } },
                    { "weight": 5, "reward": { "type": "tenet:item", "item": "minecraft:iron_ingot",
                      "count": 2 } },
                    { "weight": 3, "reward": { "type": "tenet:item", "item": "minecraft:gold_ingot" } },
                    { "weight": 1, "reward": { "type": "tenet:item", "item": "minecraft:diamond" } }
                  ] }
                """);
        // The relative names the load check counts, in the same shape `seedExamples` produces.
        return List.of("engine_gallery/group.json", "engine_gallery/the_galleries/chapter.json",
                "engine_gallery/the_galleries/the_summons.json",
                "engine_gallery/the_galleries/the_mark.json",
                "engine_gallery/the_galleries/the_fall.json",
                "engine_gallery/the_galleries/the_winnings.json",
                "engine_gallery/the_galleries/the_password.json",
                "engine_gallery/the_galleries/the_shopping_list.json",
                "engine_gallery/the_galleries/the_supply.json",
                "engine_gallery/the_galleries/the_standing.json",
                "engine_gallery/the_galleries/the_company.json",
                "engine_gallery/the_galleries/the_receipt.json",
                "engine_gallery/the_galleries/the_false_criterion.json",
                "engine_gallery/the_galleries/the_toll.json",
                "engine_gallery/the_galleries/ameth_start.json",
                "reward_tables/loot.json");
    }

    /**
     * The rewards-inbox fixture: one quest with two rewards, both waiting, and two one-reward chapters
     * beside it for the chapter-scoped press.
     *
     * <p>Written by the test for the reason {@link #seedAutoClaimChapter} is: the order below asks a
     * per-row claim to take one reward and proves it did not take the other, and no example quest is
     * shaped for that question. Both rewards are {@code auto: disabled}, so completing the quest hands
     * over nothing and the only way either arrives is the operation under test.
     *
     * <p>The two probe chapters exist for the same reason one level up: "a press on one chapter pays
     * that chapter and no other" is only observable with two chapters outstanding at one moment, and
     * that needs a second chapter that is nothing like the first. They give different items, so which
     * one was paid is read off the inventory rather than inferred.
     */
    private static List<String> seedFlexChapter(Path configDir) throws IOException {
        // The flexible-progress gallery: early task progress that must not complete or pay before
        // its gate opens, a three-chain for bounded retro-completion, and a submit pair for the
        // no-cascade rule. Checkmarks everywhere except the one item task, so no inventory couples
        // the three questions: the gate quest is a press, the chain is three presses, and only the
        // early quest reads an inventory.
        Path quests = configDir.resolve("tenet/quests/flex_gallery");
        Path chapter = quests.resolve("flex_works");
        Files.createDirectories(chapter);
        Files.writeString(quests.resolve("group.json"), """
                { "id": "flex_gallery", "title": "Flex Gallery", "chapters": ["flex_works"] }
                """);
        Files.writeString(chapter.resolve("chapter.json"), """
                { "$schema": "../../../_schema/chapter.schema.json",
                  "id": "flex_works", "title": "Flex Works",
                  "quests": ["flex_gate.json", "flex_early.json", "flex_c1.json", "flex_c2.json",
                    "flex_c3.json", "flex_d1.json", "flex_d2.json", "flex_repeat.json",
                    "flex_tick.json"] }
                """);
        Files.writeString(chapter.resolve("flex_gate.json"), """
                { "$schema": "../../../_schema/quest.schema.json", "id": "flex_gate",
                  "title": "Flex Gate", "x": 0, "y": 0,
                  "icon": { "item": "minecraft:paper" },
                  "tasks": [{ "type": "tenet:checkmark", "title": "Open the gate" }] }
                """);
        Files.writeString(chapter.resolve("flex_early.json"), """
                { "$schema": "../../../_schema/quest.schema.json", "id": "flex_early",
                  "title": "Flex Early", "x": 64, "y": 0,
                  "icon": { "item": "minecraft:oak_log" },
                  "dependsOn": ["flex_gate"], "flexibleProgress": true,
                  "tasks": [{ "type": "tenet:item", "item": "minecraft:oak_log", "count": 4 }],
                  "rewards": [{ "type": "tenet:item", "item": "minecraft:stick", "count": 2,
                    "auto": "enabled" }] }
                """);
        Files.writeString(chapter.resolve("flex_c1.json"), """
                { "$schema": "../../../_schema/quest.schema.json", "id": "flex_c1",
                  "title": "Flex Chain One", "x": 0, "y": 64,
                  "icon": { "item": "minecraft:paper" }, "flexibleProgress": true,
                  "tasks": [{ "type": "tenet:checkmark", "title": "First" }] }
                """);
        Files.writeString(chapter.resolve("flex_c2.json"), """
                { "$schema": "../../../_schema/quest.schema.json", "id": "flex_c2",
                  "title": "Flex Chain Two", "x": 64, "y": 64,
                  "icon": { "item": "minecraft:paper" }, "flexibleProgress": true,
                  "dependsOn": ["flex_c1"],
                  "tasks": [{ "type": "tenet:checkmark", "title": "Second" }] }
                """);
        Files.writeString(chapter.resolve("flex_c3.json"), """
                { "$schema": "../../../_schema/quest.schema.json", "id": "flex_c3",
                  "title": "Flex Chain Three", "x": 128, "y": 64,
                  "icon": { "item": "minecraft:paper" }, "flexibleProgress": true,
                  "dependsOn": ["flex_c2"],
                  "tasks": [{ "type": "tenet:checkmark", "title": "Third" }] }
                """);
        Files.writeString(chapter.resolve("flex_d1.json"), """
                { "$schema": "../../../_schema/quest.schema.json", "id": "flex_d1",
                  "title": "Flex Direct One", "x": 0, "y": 128,
                  "icon": { "item": "minecraft:paper" },
                  "tasks": [{ "type": "tenet:checkmark", "title": "Press" }] }
                """);
        Files.writeString(chapter.resolve("flex_d2.json"), """
                { "$schema": "../../../_schema/quest.schema.json", "id": "flex_d2",
                  "title": "Flex Direct Two", "x": 64, "y": 128,
                  "icon": { "item": "minecraft:paper" }, "flexibleProgress": true,
                  "dependsOn": ["flex_d1"],
                  "tasks": [{ "type": "tenet:checkmark", "title": "Press after" }] }
                """);
        Files.writeString(chapter.resolve("flex_repeat.json"), """
                { "$schema": "../../../_schema/quest.schema.json", "id": "flex_repeat",
                  "title": "Flex Repeat", "x": 0, "y": 192,
                  "icon": { "item": "minecraft:stick" },
                  "repeatable": true, "repeatCooldownTicks": 100,
                  "tasks": [{ "type": "tenet:checkmark", "title": "Again" }],
                  "rewards": [{ "type": "tenet:item", "item": "minecraft:stick", "count": 1,
                    "auto": "disabled" }] }
                """);
        Files.writeString(chapter.resolve("flex_tick.json"), """
                { "$schema": "../../../_schema/quest.schema.json", "id": "flex_tick",
                  "title": "Flex Tick", "x": 64, "y": 192,
                  "icon": { "item": "minecraft:paper" },
                  "repeatable": true,
                  "tasks": [{ "type": "tenet:checkmark", "title": "Tick" }] }
                """);
        return List.of("flex_gallery/group.json", "flex_gallery/flex_works/chapter.json",
                "flex_gallery/flex_works/flex_gate.json", "flex_gallery/flex_works/flex_early.json",
                "flex_gallery/flex_works/flex_c1.json", "flex_gallery/flex_works/flex_c2.json",
                "flex_gallery/flex_works/flex_c3.json", "flex_gallery/flex_works/flex_d1.json",
                "flex_gallery/flex_works/flex_d2.json", "flex_gallery/flex_works/flex_repeat.json",
                "flex_gallery/flex_works/flex_tick.json");
    }

    /**
     * The manual-only gallery: two presence tasks for the same items, one of which the tick must
     * never measure, plus a third asking for more than the player holds to prove the press refuses.
     *
     * <p>Written by the test for the reason the flex gallery is: no example quest sets manualOnly,
     * and the orders below ask what the tick and the press each do with one. Coal throughout, which
     * no other order spends, so the gallery neither feeds on nor feeds the rest of the playthrough.
     */
    private static List<String> seedManualChapter(Path configDir) throws IOException {
        Path quests = configDir.resolve("tenet/quests/manual_gallery");
        Path chapter = quests.resolve("manual_works");
        Files.createDirectories(chapter);
        Files.writeString(quests.resolve("group.json"), """
                { "id": "manual_gallery", "title": "Manual Gallery", "chapters": ["manual_works"] }
                """);
        Files.writeString(chapter.resolve("chapter.json"), """
                { "$schema": "../../../_schema/chapter.schema.json",
                  "id": "manual_works", "title": "Manual Works",
                  "quests": ["manual_press.json", "manual_control.json", "manual_short.json"] }
                """);
        Files.writeString(chapter.resolve("manual_press.json"), """
                { "$schema": "../../../_schema/quest.schema.json", "id": "manual_press",
                  "title": "Manual Press", "x": 0, "y": 0,
                  "icon": { "item": "minecraft:coal" },
                  "tasks": [{ "type": "tenet:item", "item": "minecraft:coal", "count": 4,
                    "consumeItems": false, "manualOnly": true }] }
                """);
        Files.writeString(chapter.resolve("manual_control.json"), """
                { "$schema": "../../../_schema/quest.schema.json", "id": "manual_control",
                  "title": "Manual Control", "x": 64, "y": 0,
                  "icon": { "item": "minecraft:coal" },
                  "tasks": [{ "type": "tenet:item", "item": "minecraft:coal", "count": 4,
                    "consumeItems": false }] }
                """);
        Files.writeString(chapter.resolve("manual_short.json"), """
                { "$schema": "../../../_schema/quest.schema.json", "id": "manual_short",
                  "title": "Manual Short", "x": 0, "y": 64,
                  "icon": { "item": "minecraft:coal" },
                  "tasks": [{ "type": "tenet:item", "item": "minecraft:coal", "count": 64,
                    "consumeItems": false, "manualOnly": true }] }
                """);
        return List.of("manual_gallery/group.json", "manual_gallery/manual_works/chapter.json",
                "manual_gallery/manual_works/manual_press.json",
                "manual_gallery/manual_works/manual_control.json",
                "manual_gallery/manual_works/manual_short.json");
    }

    /**
     * The filter gallery: an {@code or} of two items finished by the tick, and an {@code and} of
     * a tag and a mod finished by a consuming press.
     *
     * <p>Written by the test for the reason the manual gallery is: no example quest carries a
     * filter expression. Coal and charcoal throughout, which no other order spends — the manual
     * gallery's coal is cleared first, so this gallery measures only what it is given here.
     */
    private static List<String> seedFilterChapter(Path configDir) throws IOException {
        Path quests = configDir.resolve("tenet/quests/filter_gallery");
        Path chapter = quests.resolve("filter_works");
        Files.createDirectories(chapter);
        Files.writeString(quests.resolve("group.json"), """
                { "id": "filter_gallery", "title": "Filter Gallery", "chapters": ["filter_works"] }
                """);
        Files.writeString(chapter.resolve("chapter.json"), """
                { "$schema": "../../../_schema/chapter.schema.json",
                  "id": "filter_works", "title": "Filter Works",
                  "quests": ["filter_or.json", "filter_take.json"] }
                """);
        Files.writeString(chapter.resolve("filter_or.json"), """
                { "$schema": "../../../_schema/quest.schema.json", "id": "filter_or",
                  "title": "Filter Or", "x": 0, "y": 0,
                  "icon": { "item": "minecraft:coal" },
                  "tasks": [{ "type": "tenet:filter",
                    "filter": "or(item(minecraft:coal)item(minecraft:charcoal))",
                    "count": 2, "consumeItems": false }] }
                """);
        Files.writeString(chapter.resolve("filter_take.json"), """
                { "$schema": "../../../_schema/quest.schema.json", "id": "filter_take",
                  "title": "Filter Take", "x": 64, "y": 0,
                  "icon": { "item": "minecraft:coal" },
                  "tasks": [{ "type": "tenet:filter",
                    "filter": "and(item_tag(minecraft:coals)mod(minecraft))",
                    "count": 2, "consumeItems": true }] }
                """);
        return List.of("filter_gallery/group.json", "filter_gallery/filter_works/chapter.json",
                "filter_gallery/filter_works/filter_or.json",
                "filter_gallery/filter_works/filter_take.json");
    }

    /**
     * The charge gallery: a fluid task answered half by a bucket and half by a container, and two
     * energy tasks — one unlimited, one capped per item.
     *
     * <p>Written by the test because no example quest carries either: the fluid half proves the
     * take drains buckets first and containers for the rest, and the energy pair proves the
     * per-item cap refuses a short press and completes a full one. The containers and the charge
     * come from fakes installed per order (vanilla has neither capability to read), so what these
     * orders pin is the common wiring — measure, order, drain — and not any loader's loop.
     */
    private static List<String> seedChargeChapter(Path configDir) throws IOException {
        Path quests = configDir.resolve("tenet/quests/charge_gallery");
        Path chapter = quests.resolve("charge_works");
        Files.createDirectories(chapter);
        Files.writeString(quests.resolve("group.json"), """
                { "id": "charge_gallery", "title": "Charge Gallery", "chapters": ["charge_works"] }
                """);
        Files.writeString(chapter.resolve("chapter.json"), """
                { "$schema": "../../../_schema/chapter.schema.json",
                  "id": "charge_works", "title": "Charge Works",
                  "quests": ["fluid_jug.json", "energy_cell.json", "energy_cap.json"] }
                """);
        Files.writeString(chapter.resolve("fluid_jug.json"), """
                { "$schema": "../../../_schema/quest.schema.json", "id": "fluid_jug",
                  "title": "Fluid Jug", "x": 0, "y": 0,
                  "icon": { "item": "minecraft:water_bucket" },
                  "tasks": [{ "type": "tenet:fluid", "fluid": "minecraft:water", "amount": 1500 }] }
                """);
        Files.writeString(chapter.resolve("energy_cell.json"), """
                { "$schema": "../../../_schema/quest.schema.json", "id": "energy_cell",
                  "title": "Energy Cell", "x": 64, "y": 0,
                  "icon": { "item": "minecraft:redstone" },
                  "tasks": [{ "type": "tenet:energy", "value": 2000 }] }
                """);
        Files.writeString(chapter.resolve("energy_cap.json"), """
                { "$schema": "../../../_schema/quest.schema.json", "id": "energy_cap",
                  "title": "Energy Cap", "x": 0, "y": 64,
                  "icon": { "item": "minecraft:redstone" },
                  "tasks": [{ "type": "tenet:energy", "value": 2000, "maxInput": 500 }] }
                """);
        return List.of("charge_gallery/group.json", "charge_gallery/charge_works/chapter.json",
                "charge_gallery/charge_works/fluid_jug.json",
                "charge_gallery/charge_works/energy_cell.json",
                "charge_gallery/charge_works/energy_cap.json");
    }

    /**
     * The link gallery: two quests gated the ordinary way, with two markers pointing at them.
     *
     * <p>Written by the test for the reason the flex gallery is: no example quest carries links, and
     * the order above asks whether markers change anything about a questline. One marker points at
     * each quest — the pointed-at quest and the pointed-from chapter are the same chapter here, and
     * cross-chapter pointing is the canvas's own navigation rather than a second server behavior.
     */
    private static List<String> seedLinkChapter(Path configDir) throws IOException {
        Path quests = configDir.resolve("tenet/quests/link_gallery");
        Path chapter = quests.resolve("link_works");
        Files.createDirectories(chapter);
        Files.writeString(quests.resolve("group.json"), """
                { "id": "link_gallery", "title": "Link Gallery", "chapters": ["link_works"] }
                """);
        Files.writeString(chapter.resolve("chapter.json"), """
                { "$schema": "../../../_schema/chapter.schema.json",
                  "id": "link_works", "title": "Link Works",
                  "quests": ["link_a.json", "link_b.json"],
                  "links": [ { "id": "to_a", "quest": "link_a", "x": 128, "y": 0 },
                             { "id": "to_b", "quest": "link_b", "x": 128, "y": 64 } ],
                  "elements": [
                    { "type": "image", "id": "cmd", "x": 192, "y": 0, "width": 32, "height": 32,
                      "image": { "sprite": "minecraft:block/stone" },
                      "click": { "type": "run_command", "data": "help" } },
                    { "type": "image", "id": "sig", "x": 192, "y": 64, "width": 32, "height": 32,
                      "image": { "sprite": "minecraft:block/stone" },
                      "click": { "type": "custom_event", "data": "tenet:link_probe" } } ] }
                """);
        Files.writeString(chapter.resolve("link_a.json"), """
                { "$schema": "../../../_schema/quest.schema.json", "id": "link_a",
                  "title": "Link Gate", "x": 0, "y": 0,
                  "icon": { "item": "minecraft:paper" },
                  "tasks": [{ "type": "tenet:checkmark", "title": "Press" }] }
                """);
        Files.writeString(chapter.resolve("link_b.json"), """
                { "$schema": "../../../_schema/quest.schema.json", "id": "link_b",
                  "title": "Link Dependent", "x": 0, "y": 64,
                  "icon": { "item": "minecraft:paper" },
                  "dependsOn": ["link_a"],
                  "tasks": [{ "type": "tenet:checkmark", "title": "Press after" }] }
                """);
        return List.of("link_gallery/group.json", "link_gallery/link_works/chapter.json",
                "link_gallery/link_works/link_a.json", "link_gallery/link_works/link_b.json");
    }

    /**
     * The team-stage gallery: one party's oath, held by the team rather than by whoever claimed it.
     *
     * <p>Written by the test for the reason the engine gallery is: no example quest grants to a team,
     * and the orders below ask whether one member's induction opens the chapter for everybody. The
     * stage is `the_induction:team_oath` so it shares the induction chapter's vocabulary without
     * touching the personal `marked` flag any other order hands out.
     */
    private static List<String> seedTeamStageChapter(Path configDir) throws IOException {
        Path quests = configDir.resolve("tenet/quests/team_gallery");
        Path chapter = quests.resolve("team_works");
        Files.createDirectories(chapter);
        Files.writeString(quests.resolve("group.json"), """
                { "id": "team_gallery", "title": "Team Gallery", "chapters": ["team_works"] }
                """);
        Files.writeString(chapter.resolve("chapter.json"), """
                { "$schema": "../../../_schema/chapter.schema.json",
                  "id": "team_works", "title": "Team Works",
                  "quests": ["team_summons.json", "team_mark.json", "team_fall.json"] }
                """);
        Files.writeString(chapter.resolve("team_summons.json"), """
                { "$schema": "../../../_schema/quest.schema.json", "id": "team_summons",
                  "title": "Team Summons", "x": 0, "y": 0,
                  "icon": { "item": "minecraft:paper" },
                  "tasks": [{ "type": "tenet:checkmark", "title": "Answer for the party" }],
                  "rewards": [{ "type": "tenet:stage", "stage": "the_induction:team_oath",
                    "teamStage": true }] }
                """);
        Files.writeString(chapter.resolve("team_mark.json"), """
                { "$schema": "../../../_schema/quest.schema.json", "id": "team_mark",
                  "title": "Team Mark", "x": 64, "y": 0,
                  "icon": { "item": "minecraft:golden_apple" },
                  "requiresStage": "the_induction:team_oath", "requiresStageTeam": true,
                  "tasks": [{ "type": "tenet:stage", "stage": "the_induction:team_oath",
                    "teamStage": true }],
                  "rewards": [{ "type": "tenet:item", "item": "minecraft:golden_apple",
                    "auto": "disabled" }] }
                """);
        Files.writeString(chapter.resolve("team_fall.json"), """
                { "$schema": "../../../_schema/quest.schema.json", "id": "team_fall",
                  "title": "Team Fall", "x": 128, "y": 0,
                  "dependsOn": ["team_mark"],
                  "icon": { "item": "minecraft:wither_rose" },
                  "tasks": [{ "type": "tenet:checkmark", "title": "Renounce for the party" }],
                  "rewards": [{ "type": "tenet:stage", "stage": "the_induction:team_oath",
                    "remove": true, "teamStage": true }] }
                """);
        return List.of("team_gallery/group.json", "team_gallery/team_works/chapter.json",
                "team_gallery/team_works/team_summons.json",
                "team_gallery/team_works/team_mark.json",
                "team_gallery/team_works/team_fall.json");
    }

    /**
     * The junction gallery: two checkmark gates, a taskless quest behind both, and a quest behind
     * that.
     *
     * <p>Written by the test for the reason the flex gallery is: no example quest is a taskless
     * junction with a dependant, and order 220 asks what the tick does with one. Checkmarks
     * throughout, so no inventory couples the questions: the two gates are presses, the junction
     * has nothing to press, and the dependant's own press is never touched. No titles are drawn,
     * so the position checks have nothing to say either.
     */
    private static List<String> seedJunctionChapter(Path configDir) throws IOException {
        Path quests = configDir.resolve("tenet/quests/junction_gallery");
        Path chapter = quests.resolve("junction_works");
        Files.createDirectories(chapter);
        Files.writeString(quests.resolve("group.json"), """
                { "id": "junction_gallery", "title": "Junction Gallery", "chapters": ["junction_works"] }
                """);
        Files.writeString(chapter.resolve("chapter.json"), """
                { "$schema": "../../../_schema/chapter.schema.json",
                  "id": "junction_works", "title": "Junction Works",
                  "quests": ["junction_a.json", "junction_b.json", "junction_gate.json",
                    "junction_after.json"] }
                """);
        Files.writeString(chapter.resolve("junction_a.json"), """
                { "$schema": "../../../_schema/quest.schema.json", "id": "junction_a",
                  "title": "Junction A", "x": 0, "y": 0,
                  "icon": { "item": "minecraft:paper" },
                  "tasks": [{ "type": "tenet:checkmark", "title": "First" }] }
                """);
        Files.writeString(chapter.resolve("junction_b.json"), """
                { "$schema": "../../../_schema/quest.schema.json", "id": "junction_b",
                  "title": "Junction B", "x": 64, "y": 0,
                  "icon": { "item": "minecraft:paper" },
                  "tasks": [{ "type": "tenet:checkmark", "title": "Second" }] }
                """);
        Files.writeString(chapter.resolve("junction_gate.json"), """
                { "$schema": "../../../_schema/quest.schema.json", "id": "junction_gate",
                  "title": "Junction Gate", "x": 32, "y": 64,
                  "icon": { "item": "minecraft:hopper" },
                  "dependsOn": ["junction_a", "junction_b"] }
                """);
        Files.writeString(chapter.resolve("junction_after.json"), """
                { "$schema": "../../../_schema/quest.schema.json", "id": "junction_after",
                  "title": "Junction After", "x": 32, "y": 128,
                  "icon": { "item": "minecraft:paper" },
                  "dependsOn": ["junction_gate"],
                  "tasks": [{ "type": "tenet:checkmark", "title": "After" }] }
                """);
        return List.of("junction_gallery/group.json", "junction_gallery/junction_works/chapter.json",
                "junction_gallery/junction_works/junction_a.json",
                "junction_gallery/junction_works/junction_b.json",
                "junction_gallery/junction_works/junction_gate.json",
                "junction_gallery/junction_works/junction_after.json");
    }

    /**
     * The break gallery: a break by id, by block tag and by block state.
     *
     * <p>Written by the test because the break event has no loader in a test JVM: the orders
     * below call {@code ProgressService.onBlockBroken} directly, which is the event's real entry
     * point but not its real trigger — the same seam {@code tickUntil} uses for the tick. Stone
     * and wheat throughout, which no other order spends.
     */
    private static List<String> seedBreakChapter(Path configDir) throws IOException {
        Path quests = configDir.resolve("tenet/quests/break_gallery");
        Path chapter = quests.resolve("break_works");
        Files.createDirectories(chapter);
        Files.writeString(quests.resolve("group.json"), """
                { "id": "break_gallery", "title": "Break Gallery", "chapters": ["break_works"] }
                """);
        Files.writeString(chapter.resolve("chapter.json"), """
                { "$schema": "../../../_schema/chapter.schema.json",
                  "id": "break_works", "title": "Break Works",
                  "quests": ["break_stone.json", "break_logs.json", "break_wheat.json"] }
                """);
        Files.writeString(chapter.resolve("break_stone.json"), """
                { "$schema": "../../../_schema/quest.schema.json", "id": "break_stone",
                  "title": "Break Stone", "x": 0, "y": 0,
                  "icon": { "item": "minecraft:stone" },
                  "tasks": [{ "type": "tenet:break", "block": "minecraft:stone", "value": 4 }] }
                """);
        Files.writeString(chapter.resolve("break_logs.json"), """
                { "$schema": "../../../_schema/quest.schema.json", "id": "break_logs",
                  "title": "Break Logs", "x": 64, "y": 0,
                  "icon": { "item": "minecraft:oak_log" },
                  "tasks": [{ "type": "tenet:break", "blockTag": "minecraft:logs", "value": 4 }] }
                """);
        Files.writeString(chapter.resolve("break_wheat.json"), """
                { "$schema": "../../../_schema/quest.schema.json", "id": "break_wheat",
                  "title": "Break Wheat", "x": 128, "y": 0,
                  "icon": { "item": "minecraft:wheat" },
                  "tasks": [{ "type": "tenet:break",
                    "blockState": "minecraft:wheat[age=7]", "value": 4 }] }
                """);
        return List.of("break_gallery/group.json", "break_gallery/break_works/chapter.json",
                "break_gallery/break_works/break_stone.json",
                "break_gallery/break_works/break_logs.json",
                "break_gallery/break_works/break_wheat.json");
    }

    private static List<String> seedRewardInboxChapter(Path configDir) throws IOException {
        Path quests = configDir.resolve("tenet/quests/reward_inbox");
        Path chapter = quests.resolve("reward_inbox");
        Files.createDirectories(chapter);
        Files.writeString(quests.resolve("group.json"), """
                { "id": "reward_inbox", "title": "Reward Inbox",
                  "chapters": ["reward_inbox", "probe_one", "probe_two"] }
                """);
        Files.writeString(chapter.resolve("chapter.json"), """
                { "$schema": "../../../_schema/chapter.schema.json",
                  "id": "reward_inbox", "title": "Reward Inbox", "quests": ["two_gifts.json"] }
                """);
        Files.writeString(chapter.resolve("two_gifts.json"), """
                { "$schema": "../../../_schema/quest.schema.json", "id": "two_gifts",
                  "title": "Two Gifts",
                  "icon": { "item": "minecraft:diamond" },
                  "tasks": [{ "type": "tenet:checkmark", "title": "Take them one at a time" }],
                  "rewards": [
                    { "type": "tenet:item", "item": "minecraft:diamond", "count": 3,
                      "auto": "disabled" },
                    { "type": "tenet:item", "item": "minecraft:emerald", "count": 5,
                      "auto": "disabled" }] }
                """);

        List<String> seeded = new ArrayList<>(List.of("reward_inbox/group.json",
                "reward_inbox/reward_inbox/chapter.json",
                "reward_inbox/reward_inbox/two_gifts.json"));
        seeded.addAll(seedProbeChapter(quests, "probe_one", "one_ingot", "minecraft:gold_ingot"));
        seeded.addAll(seedProbeChapter(quests, "probe_two", "one_brick", "minecraft:brick"));
        // The relative names the load check counts, in the same shape `seedExamples` produces.
        return List.copyOf(seeded);
    }

    /** One chapter of the chapter-scoped fixture: a single quest, one waiting item, no auto-claim. */
    private static List<String> seedProbeChapter(Path quests, String chapterId, String questId,
                                                 String item) throws IOException {
        Path folder = quests.resolve(chapterId);
        Files.createDirectories(folder);
        Files.writeString(folder.resolve("chapter.json"), """
                { "$schema": "../../../_schema/chapter.schema.json",
                  "id": "%s", "title": "%s", "quests": ["%s.json"] }
                """.formatted(chapterId, chapterId, questId));
        Files.writeString(folder.resolve(questId + ".json"), """
                { "$schema": "../../../_schema/quest.schema.json", "id": "%s",
                  "title": "%s",
                  "icon": { "item": "%s" },
                  "tasks": [{ "type": "tenet:checkmark", "title": "Ask for it" }],
                  "rewards": [{ "type": "tenet:item", "item": "%s", "count": 2,
                    "auto": "disabled" }] }
                """.formatted(questId, questId, item, item));
        return List.of("reward_inbox/" + chapterId + "/chapter.json",
                "reward_inbox/" + chapterId + "/" + questId + ".json");
    }

    /** Whether one of a quest's rewards is already claimed for the test player, from stored progress. */
    private static boolean rewardClaimed(String questId, int index) {
        return server.callOnServerThread(() -> {
            UUID owner = ProgressService.progressOwner(server.server(), player);
            var entry = TenetQuests.index().quest(questId).orElse(null);
            if (entry == null) {
                return false;
            }
            return ProgressService.progressFor(server.server(), owner)
                    .progressOf(entry.quest())
                    .claimed(player.getUUID(), index, false);
        });
    }

    // ------------------------------------------------------------------
    // Driving and reading
    // ------------------------------------------------------------------
    /**
     * How many progress messages have been produced for one reason.
     *
     * <p>Read through the server thread, like every other read here, and not because the counter is
     * lock-protected -- it is a plain {@code HashMap}, written from the server thread and read here, so
     * reading it directly would be reading a {@code HashMap} from another thread while it may be
     * written. That happens to work today and is not a thing to build on.
     */
    private static int messagesSent(int reason) {
        return server.callOnServerThread(() -> QuestSync.messagesSent(reason));
    }

    /**
     * How many progress messages have been produced in total, whatever the reason.
     *
     * <p>This is the form that answers "did <i>anything</i> get sent", which is a different question
     * from "was the right message sent for this reason" and the only one that can be asked about a
     * refusal. Order 17's last assertion is exactly that: a command that changed nothing must be silent,
     * and silence means no message of <b>any</b> reason -- so asking per-reason would let a push under a
     * different reason through while the assertion still passed.
     *
     * <p>It exists rather than being spelled as a sum at the call site because a caller summing reasons
     * has to know which reasons exist, and that list grows. A total is defined by the counter, not by the
     * test.
     */
    private static int messagesSent() {
        return server.callOnServerThread(QuestSync::messagesSent);
    }

    /**
     * The same, for one recipient.
     *
     * <p>The recipient is the half that matters for the team-change push, and a total cannot supply it:
     * two messages that both reached the same player look identical to two that reached one player
     * each, and the second is the entire claim being made.
     */
    private static int messagesSentTo(int reason, ServerPlayer who) {
        return server.callOnServerThread(() -> QuestSync.messagesSentTo(reason, who.getUUID()));
    }

    /** Runs a command as an operator -- the same permission a player in ops.json has. */
    private static HeadlessServer.Outcome asOperator(String command) {
        HeadlessServer.Outcome outcome = server.run(player, 4, command);
        note(outcome.report());
        return outcome;
    }

    /**
     * The same, from a specific player's own source.
     *
     * <p>Needed because collecting a reward is per-player while progress is per-team: "the party can
     * collect this" is only a check if somebody other than the owner does the collecting, and it has to
     * be a real player's source rather than the owner's for that to mean anything.
     */
    private static HeadlessServer.Outcome asOperator(ServerPlayer who, String command) {
        HeadlessServer.Outcome outcome = server.run(who, 4, command);
        note(outcome.report());
        return outcome;
    }

    /**
     * Ticks the engine until {@code condition} holds, or {@code timeout} runs out.
     *
     * <p>The engine is time-based: a task's next evaluation is due a fixed number of ticks after the
     * last, so "did this complete" has to be asked repeatedly. Each call to {@code tick} goes through
     * the server thread, because that is where the loader's hook would call it from.
     *
     * <p>Not a fixed sleep, because a passing test should not take as long as a failing one.
     */
    private static boolean tickUntil(BooleanSupplier condition, Duration timeout) {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (System.nanoTime() < deadline) {
            if (condition.getAsBoolean()) {
                return true;
            }
            tickOnce();
            sleep(100);
        }
        return condition.getAsBoolean();
    }

    /**
     * One tick of the engine, recording which teams it reported as changed.
     *
     * <p>Recording it is the point. {@code tick} returns the owners whose progress moved, and that
     * return value is the <b>only</b> thing that tells a caller there is something to send — it is how
     * the missing progress sync was fixed. So a test that calls {@code tick} and throws the answer away
     * is testing the engine's arithmetic and not the thing that was broken.
     *
     * <p>Before this round, {@code tick} returned {@code void} and the only code anywhere that pushed
     * progress to a player was the handler for pressing Submit. Gathering eight oak logs completed the
     * quest, granted the reward and printed the completion message while the quest book went on showing
     * {@code 0 / 8} for the rest of the session — and no test could have noticed, because there was
     * nothing to assert about a method with no output.
     */
    private static void tickOnce() {
        java.util.Set<UUID> changed = server.callOnServerThread(() -> ProgressService.tick(server.server()));
        lastTick = java.util.Set.copyOf(changed);
        changedEver.addAll(changed);
    }

    /** A quest's state, computed the way the engine computes it. */
    private static QuestState stateOf(String questId) {
        return stateFor(player, questId);
    }

    /**
     * A quest's state <b>as one particular player sees it</b>.
     *
     * <p>The two-argument version exists because of what the party tests are about. With one player,
     * "whose progress" is not a question; with two in a party it is the entire question, and asking it
     * twice with different subjects is how "they share one record" becomes a check rather than a
     * claim. It also means a bug where one member's view is correct and the other's is not cannot hide
     * behind a single lookup.
     */
    private static QuestState stateFor(ServerPlayer who, String questId) {
        return server.callOnServerThread(() -> {
            UUID owner = ProgressService.progressOwner(server.server(), who);
            return ProgressService.resolutionFor(server.server(), owner).stateOf(quest(questId).quest());
        });
    }

    /** The progress owner a player's quest state is read from. Their team's id, solo or not. */
    private static UUID ownerOf(ServerPlayer who) {
        return server.callOnServerThread(() -> ProgressService.progressOwner(server.server(), who));
    }

    /**
     * The management reads, all against the manager rather than a command's output.
     *
     * <p>The file's rule, restated where it is easiest to break: a dedicated server has no language
     * file, so a translatable renders as its key and no assertion here may read a sentence. A party's
     * name, owner, roles, policy and invitations are all facts the store holds, so each is asked for
     * directly -- and asking through {@code Teams.of} also exercises the source resolution the
     * commands use.
     */
    private static String nameOf(UUID teamId) {
        return server.callOnServerThread(() ->
                Teams.of(server.server()).byId(teamId).map(Team::name).orElse(null));
    }

    private static UUID ownerOfTeam(UUID teamId) {
        return server.callOnServerThread(() ->
                Teams.of(server.server()).byId(teamId).map(Team::owner).orElse(null));
    }

    private static TeamRole roleOf(UUID teamId, UUID playerId) {
        return server.callOnServerThread(() ->
                Teams.of(server.server()).byId(teamId).flatMap(team -> team.roleOf(playerId)).orElse(null));
    }

    private static TeamPolicy policyOf(UUID teamId) {
        return server.callOnServerThread(() ->
                Teams.of(server.server()).byId(teamId).map(Team::policy).orElse(null));
    }

    private static boolean invitedTo(UUID teamId, UUID playerId) {
        return server.callOnServerThread(() ->
                Teams.of(server.server()).byId(teamId).map(team -> team.isInvited(playerId)).orElse(false));
    }

    private static int sizeOf(UUID teamId) {
        return server.callOnServerThread(() ->
                Teams.of(server.server()).byId(teamId).map(Team::size).orElse(0));
    }

    /** Whether one player holds a stage, read on the server thread like every other read here. */
    private static boolean hasStage(ServerPlayer who, Identifier stage) {
        return server.callOnServerThread(() -> StageService.has(server.server(), who.getUUID(), stage));
    }

    /**
     * The quests the sync would draw locked for this player: the per-player overlay, not the stored
     * state.
     *
     * <p>The set the client is actually told, computed by the same call {@code sendProgress} makes --
     * which is the point. Asserting {@code stateOf} here would assert the team's answer and miss the
     * one thing a stage gate changes.
     */
    private static java.util.Set<String> stageLocked() {
        return server.callOnServerThread(() ->
                ProgressService.stageLockedQuests(server.server(), player, TenetQuests.index()));
    }

    /**
     * Empties both players' inventories, on the server thread.
     *
     * <p>Needed because the playthrough runs as one long sequence in one world: by the time the party
     * tests start, the first player is carrying eight oak logs from the solo run -- punch_a_tree's
     * task does not consume them. A test that means "only the friend has the logs" has to make that
     * true rather than hope it.
     *
     * <p>Straight to the inventory rather than through {@code /clear}, and the reason is that a
     * second thing that can fail is a second thing to debug when this test does fail. The command
     * would be more "real", and it is not what is under test.
     */
    private static void clearInventories() {
        // A plain null check for the second player, rather than List.of(player, friend) which is what
        // this was first and which *throws* on a null element. It threw on the line above the note
        // that would have said the friend had been spawned, so three party tests failed on an
        // NPE from a helper and the transcript named the friend as null -- which reads like a
        // server problem and was a helper problem. Being an outside-the-test-framework class,
        // nothing here fails loudly at the call site; it just returns the wrong thing.
        //
        // Nullable-on-purpose: the first test that uses this spawns the second player, and the ones
        // before it never touch this. A helper that only works when called in the right order is fine
        // as long as calling it in the wrong order is not silently a no-op.
        server.onServerThread(() -> {
            for (ServerPlayer who : new ServerPlayer[] { player, friend }) {
                if (who == null) {
                    continue;
                }
                who.getInventory().clearContent();
                who.getInventory().setChanged();
            }
        });
    }

    /** How far along one task is recorded to be. Zero for a task nothing has ever written. */
    private static int recordedTask(String questId, int taskIndex) {
        return recordedTaskFor(player, questId, taskIndex);
    }

    /** The world's game time, read on the server thread like every other read here. */
    private static long gameTime() {
        return server.callOnServerThread(() -> server.server().overworld().getGameTime());
    }

    /** Sets the world's game time. Tests own the clock here because the headless world barely ticks. */
    private static void setGameTime(long time) {
        server.onServerThread(() -> ((net.minecraft.world.level.storage.ServerLevelData) server.server()
                        .overworld()
                        .getLevelData())
                .setGameTime(time));
    }

    /** How many times a repeatable quest has finished. Zero for one that never has. */
    private static int timesCompletedOf(String questId) {
        return server.callOnServerThread(() -> {
            UUID owner = ProgressService.progressOwner(server.server(), player);
            return ProgressService.progressFor(server.server(), owner)
                    .progressOf(quest(questId).quest()).timesCompleted();
        });
    }

    /** The game time the last completion round was timed from. Zero when no clock is running. */
    private static long lastCompletedAtOf(String questId) {
        return server.callOnServerThread(() -> {
            UUID owner = ProgressService.progressOwner(server.server(), player);
            return ProgressService.progressFor(server.server(), owner)
                    .progressOf(quest(questId).quest()).lastCompletedAt();
        });
    }

    /**
     * How the party this player is in currently counts.
     *
     * <p>Read through the server thread like every other read here, and through {@code PartyStore}
     * rather than through the command's output — so the assertion is about the state a restart would
     * read back, not about the wording of a message.
     */
    private static PartyMode modeOf(ServerPlayer who) {
        return server.callOnServerThread(() ->
                PartyStore.of(server.server()).modeOf(ProgressService.progressOwner(server.server(), who)));
    }

    /** The same, for whichever player's progress is the interesting one. */
    private static int recordedTaskFor(ServerPlayer who, String questId, int taskIndex) {
        return server.callOnServerThread(() -> {
            UUID owner = ProgressService.progressOwner(server.server(), who);
            return ProgressService.progressFor(server.server(), owner)
                    .progressOf(quest(questId).quest())
                    .progressOf(taskIndex);
        });
    }

    /** How many quests have any stored progress at all. The check that a refusal wrote nothing. */
    private static int storedQuestCount() {
        return server.callOnServerThread(() -> {
            UUID owner = ProgressService.progressOwner(server.server(), player);
            return ProgressService.progressFor(server.server(), owner).size();
        });
    }

    /** How many of {@code item} the player is carrying, whole inventory. */
    private static int countInInventory(Item item) {
        return countInInventoryOf(player, item);
    }

    /** The same, for whichever player's inventory is the interesting one. */
    private static int countInInventoryOf(ServerPlayer who, Item item) {
        return server.callOnServerThread(() -> {
            var inventory = who.getInventory();
            int found = 0;
            for (int slot = 0; slot < inventory.getContainerSize(); slot++) {
                ItemStack stack = inventory.getItem(slot);
                if (!stack.isEmpty() && stack.getItem() == item) {
                    found += stack.getCount();
                }
            }
            return found;
        });
    }

    private static QuestIndex.QuestEntry quest(String id) {
        Optional<QuestIndex.QuestEntry> found = TenetQuests.find(id);
        return found.orElseThrow(() -> new AssertionError(
                "no quest '" + id + "' is loaded, so this test is asking about nothing. "
                        + "Loaded: " + TenetQuests.index().quests().size() + " quest(s)"));
    }

    private static void assertRefused(HeadlessServer.Outcome outcome, String why) {
        assertTrue(outcome.refused(), () -> outcome.command() + " should have been refused, because "
                + why + " -- but it returned " + outcome.result() + "\n" + outcome.text());
    }

    // ------------------------------------------------------------------
    // Small things
    // ------------------------------------------------------------------

    private static void note(String line) {
        TRANSCRIPT.add(line);
        System.out.println("    " + line);
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        }
        catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AssertionError("interrupted while waiting for the engine", e);
        }
    }

    private static String render(Problems problems) {
        return problems.all().stream()
                .map(DataProblem::render)
                .reduce((a, b) -> a + "\n" + b)
                .orElse("(no problems at all)");
    }

    /**
     * Copies the worked examples into a config directory, and returns their names.
     *
     * <p>What {@code tenet/tools/seed_quests.py} does, in Java so the test needs nothing on PATH. The
     * two must agree, and the thing that keeps them agreeing is that neither holds a list: both walk
     * {@code tenet/tools/quests} and both skip anything whose name begins with {@code _}.
     *
     * <h2>Recursive, and it had to become recursive for the same reason the script did</h2>
     *
     * <p>It was a single {@code Files.list} over the quest directory, copying each {@code .json} whose
     * name did not begin with an underscore. That is exactly right for the flat format, where a file
     * <i>is</i> a whole tree, and it silently copies nothing at all under the folder format — where the
     * quests are four levels down inside {@code first_light/first_steps/} and no file at the top
     * level is a quest.
     *
     * <p>The failure that produces is worth naming because of how it reads. The seeding step succeeds,
     * the load succeeds, and the quest book is empty — so the first place anyone looks is the loader,
     * which is working. Worse, this test's own {@code filesFound} assertion would have compared 0 with
     * the number of top-level files and passed, because both sides were counting the same nothing.
     *
     * <p>So it walks, and it applies the underscore rule to <b>every path segment</b> rather than to the
     * final name — the same rule {@code DeclaredPaths.isIgnored} states, and for the same reason: the
     * shipped {@code _schema} folder is a directory, and a rule that only tested the file's own name
     * would happily copy every one of its contents into a config directory.
     *
     * <p>Returns {@code /}-separated relative paths, not bare file names, because under the folder
     * format a name is not unique — {@code group.json} appears once per group and {@code chapter.json}
     * once per chapter.
     */
    private static List<String> seedExamples(Path configDir) throws IOException {
        Path target = configDir.resolve("tenet/quests");
        Files.createDirectories(target);

        List<String> copied = new ArrayList<>();
        try (Stream<Path> walk = Files.walk(EXAMPLES)) {
            for (Path source : walk.filter(Files::isRegularFile).toList()) {
                Path relative = EXAMPLES.relativize(source);
                if (isIgnored(relative)) {
                    continue;
                }
                Path destination = target.resolve(relative.toString());
                Files.createDirectories(destination.getParent());
                Files.copy(source, destination);
                copied.add(relative.toString().replace('\\', '/'));
            }
        }
        Collections.sort(copied);

        if (copied.isEmpty()) {
            throw new IllegalStateException("no example quests at " + EXAMPLES.toAbsolutePath()
                    + " -- the tests run from the Gradle project directory, so this is relative to"
                    + " tenet/common, and the examples belong in tenet/tools/quests");
        }
        return copied;
    }

    /**
     * Whether a path under the examples directory is to be skipped.
     *
     * <p>Every segment, not just the last. See {@link #seedExamples} — the {@code _schema} folder is a
     * directory, and a rule that only looked at the file's own name would copy its contents.
     */
    private static boolean isIgnored(Path relative) {
        for (Path segment : relative) {
            if (segment.toString().startsWith("_")) {
                return true;
            }
        }
        return false;
    }

    /**
     * One seeded example's text, by the relative path this test seeded it under.
     *
     * <p>Was used by {@link #theSeededExamplesLoad} to decode each seeded file and count what was in
     * it. That count is impossible under the folder format — a {@code group.json} holds a list of names
     * and the names are the directory listing — so the count reads the paths themselves now, and this
     * has no callers. It is kept rather than deleted for the one thing it is still good for: a test that
     * wants to assert something about the <i>bytes</i> of an example, which nothing does today.
     *
     * <p>Concretely: "the seeded file on disk is the file the loader read" is a claim a per-byte
     * comparison would make and a count cannot. Nothing needs it yet, and inventing a use for it would
     * be worse than leaving it here with a note saying so.
     */
    private static String readExample(String relative) throws IOException {
        return Files.readString(EXAMPLES.resolve(relative), java.nio.charset.StandardCharsets.UTF_8);
    }

    private static void deleteRecursively(Path root) throws IOException {
        if (!Files.exists(root)) {
            return;
        }
        try (Stream<Path> walk = Files.walk(root)) {
            for (Path path : walk.sorted(Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(path);
            }
        }
    }

    // ------------------------------------------------------------------
    // The two things Armature normally provides
    // ------------------------------------------------------------------

    /**
     * Just enough platform for {@code TenetQuests.reload()}, which asks for the config directory.
     *
     * <p>{@code FABRIC} is a stand-in and not a claim, and the honest version of this note is why it
     * is not something else: {@link PlatformKind} has no constant for a test double, and adding one
     * would mean republishing Armature for the benefit of a test. Nothing in Tenet branches on
     * {@code kind()} — checked rather than assumed — so no code path here can take a Fabric-only route
     * by accident. If something ever does start branching on it, this is the line that becomes a lie.
     */
    private record PlaythroughPlatform(Path configDirectory) implements ArmaturePlatform {

        @Override
        public PlatformKind kind() {
            return PlatformKind.FABRIC;
        }

        @Override
        public boolean isModLoaded(String modId) {
            return "tenet".equals(modId) || "armature".equals(modId);
        }

        @Override
        public Optional<String> modVersion(String modId) {
            return isModLoaded(modId) ? Optional.of("test") : Optional.empty();
        }

        @Override
        public boolean isClient() {
            return false;
        }

        @Override
        public boolean isDevelopmentEnvironment() {
            return true;
        }

        @Override
        public boolean isFakePlayer(net.minecraft.server.level.ServerPlayer player) {
            // Headless: every breaker here is a real test player, so nothing is fake.
            return false;
        }

        @Override
        public Path gameDir() {
            return configDirectory.getParent();
        }

        @Override
        public Path configDir() {
            return configDirectory;
        }
    }

    /**
     * Refuses to register anything, loudly.
     *
     * <p>{@code Tenet.init()} is deliberately not called: it registers the quest book item and the
     * network payloads, and this playthrough needs neither — it drives the engine through commands.
     * Doing it that way keeps the test free of both loaders, which is the whole reason it can run
     * headless at all.
     *
     * <p>So if anything <i>does</i> try to register, that assumption has quietly stopped being true,
     * and this throws with the reason rather than failing later with a null registry.
     */
    private static final class NothingIsRegistered implements Registrar {

        @Override
        public Registrar forMod(String modId) {
            return this;
        }

        @Override
        public <T> void register(Registry<T> registry, Identifier id, Supplier<? extends T> value) {
            throw new UnsupportedOperationException("the playthrough does not run Tenet.init(), so "
                    + "nothing here should be registering " + id + ". Something started depending on "
                    + "an item or a payload existing, and this test needs to know which.");
        }
    }
}
