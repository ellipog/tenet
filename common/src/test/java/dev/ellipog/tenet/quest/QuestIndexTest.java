package dev.ellipog.tenet.quest;

import dev.ellipog.armature.api.data.DataProblem;
import dev.ellipog.armature.api.data.JsonDocument;
import dev.ellipog.armature.api.data.Problems;
import dev.ellipog.tenet.quest.condition.AdvancementCondition;
import dev.ellipog.tenet.quest.condition.ItemCondition;
import dev.ellipog.tenet.quest.condition.ItemTagCondition;
import dev.ellipog.tenet.quest.condition.PartySizeCondition;
import dev.ellipog.tenet.quest.condition.ScoreCondition;
import dev.ellipog.tenet.quest.condition.StageCondition;
import dev.ellipog.tenet.quest.reward.AdvancementReward;
import dev.ellipog.tenet.quest.reward.CommandReward;
import dev.ellipog.tenet.quest.reward.CustomReward;
import dev.ellipog.tenet.quest.reward.ItemReward;
import dev.ellipog.tenet.quest.reward.RewardAutoClaim;
import dev.ellipog.tenet.quest.reward.RewardCommon;
import dev.ellipog.tenet.quest.reward.StageReward;
import dev.ellipog.tenet.quest.reward.TableReward;
import dev.ellipog.tenet.quest.reward.XpReward;
import dev.ellipog.tenet.quest.task.AdvancementTask;
import dev.ellipog.tenet.quest.task.BiomeTask;
import dev.ellipog.tenet.quest.task.BreakTask;
import dev.ellipog.tenet.quest.task.CheckmarkTask;
import dev.ellipog.tenet.quest.task.ComponentMatch;
import dev.ellipog.tenet.quest.task.CustomTask;
import dev.ellipog.tenet.quest.task.DimensionTask;
import dev.ellipog.tenet.quest.task.FluidTask;
import dev.ellipog.tenet.quest.task.ItemTagTask;
import dev.ellipog.tenet.quest.task.ItemTask;
import dev.ellipog.tenet.quest.task.KillTask;
import dev.ellipog.tenet.quest.task.LocationTask;
import dev.ellipog.tenet.quest.task.ObservationTask;
import dev.ellipog.tenet.quest.task.StageTask;
import dev.ellipog.tenet.quest.task.StatTask;
import dev.ellipog.tenet.quest.task.StructureTask;
import dev.ellipog.tenet.quest.task.XpTask;

import net.minecraft.core.component.DataComponentPatch;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static dev.ellipog.tenet.quest.Fixtures.q;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The cross-file checks: identifiers, dependencies, and where quests sit.
 *
 * <h2>Why these are separate from the validator's tests</h2>
 *
 * <p>Because they answer a different question. {@code QuestValidator} decides whether one file is
 * well-formed; this decides whether a <i>set</i> of files makes sense together — whether an id is
 * taken, whether a {@code dependsOn} resolves, whether two quests are drawn on top of each other. A
 * single file cannot answer any of those, which is why an author finds out about them at load rather
 * than at save.
 *
 * <h2>The test that matters most here</h2>
 *
 * <p>{@link Crowding#closeTogetherInARowWarn}. That check was added because a screenshot showed three
 * quest titles interleaved into what looked like a corrupt string, and the cause was a questline
 * authored 64 pixels apart with titles about 90 pixels wide. The client now copes with that — it
 * truncates to the measured room and drops a label that would sit over a node — and converted packs
 * are laid out on a 64-pixel grid, so the check warns only below 64 pixels: a layout that hides
 * what the author wrote is worth reporting at load, and a check with no test is a check that
 * quietly stops working.
 */
class QuestIndexTest {

    /**
     * Starts enough of vanilla for the loader to check item ids.
     *
     * <p>Needed by the four tests that load the shipped examples, and it was not needed before them.
     * They used to decode files directly — which exercises the codecs and nothing else — and the
     * validator is the step that reads {@code BuiltInRegistries.ITEM}. Running it without this throws
     * from inside a vanilla class initialiser, naming a registry that has nothing to do with what is
     * under test, which is the failure mode {@link MinecraftTestBootstrap} documents at length.
     *
     * <p>About a second, once per JVM, shared with every other class that asks.
     */
    @org.junit.jupiter.api.BeforeAll
    static void bootVanilla() {
        MinecraftTestBootstrap.boot();
    }

    /**
     * A directory of this test's own, for the quest tree {@link #loadExamples} builds.
     *
     * <p>An instance field rather than a static one, so each test method gets a fresh directory: these
     * tests copy the whole example tree into it, and a shared one would mean a failed load leaving a
     * half-built tree — and a half-built tree is exactly the state that produces a misleading
     * "duplicate id" report on the next test rather than a clear failure.
     */
    @org.junit.jupiter.api.io.TempDir
    Path temp;

    // ------------------------------------------------------------------
    // Fixtures
    // ------------------------------------------------------------------

    /**
     * Indexes, returning the problems rather than discarding them.
     *
     * <p>{@link Fixtures#indexOf} throws the {@link Problems} away, which is right for the progression
     * tests — they want the index, not the complaints — and useless here. So this walks the same three
     * steps the loader walks, using only public API, and keeps the problems.
     */
    private static Problems problemsOf(String... jsons) {
        Problems problems = new Problems();
        indexOf(problems, jsons);
        return problems;
    }

    /** The same walk, keeping the index as well — for the tests that assert what the tree holds. */
    private static QuestIndex indexOf(Problems problems, String... jsons) {
        List<LoadedQuestFile> loaded = new ArrayList<>();
        for (int i = 0; i < jsons.length; i++) {
            String name = "test" + i + ".json";
            JsonDocument document = Fixtures.document(name, jsons[i]);
            loaded.add(new LoadedQuestFile(Path.of(name), name, document, Fixtures.decode(name, document)));
        }
        return QuestIndex.build(loaded, problems);
    }

    /** Every problem message, joined, for a single containment assertion. */
    private static String messages(Problems problems) {
        return problems.all().stream().map(DataProblem::render).reduce("", (a, b) -> a + "\n" + b);
    }

    private static void assertMentions(Problems problems, String text) {
        assertTrue(messages(problems).contains(text),
                "expected a problem mentioning '" + text + "', got:" + messages(problems));
    }

    private static void assertDoesNotMention(Problems problems, String text) {
        assertTrue(!messages(problems).contains(text),
                "did not expect a problem mentioning '" + text + "', got:" + messages(problems));
    }

    /** A title long enough to matter at the crowding threshold. 20 characters, about 120px. */
    private static final String LONG_ID = "punch_a_tree_forever";

    /** A second one, a different length so the check has to pick the wider of the two. */
    private static final String WIDER_ID = "collect_some_cobblestone";

    // ------------------------------------------------------------------
    // Identifiers
    // ------------------------------------------------------------------

    @Nested
    @DisplayName("identifiers")
    class Identifiers {

        @Test
        @DisplayName("two quests with the same id are an error, even in one file")
        void duplicateIdIsAnError() {
            // Reported only here and not by the validator: one file has no idea what another file
            // declared, and the index is the one place that can see the whole set. Within a single
            // file the validator could see it, but a second rule about the same thing would be a
            // second rule that can disagree with the first.
            Problems problems = problemsOf(Fixtures.file(
                    q("twice").build(),
                    q("twice").build()));

            assertTrue(problems.hasErrors(), "a duplicate id is fatal:" + messages(problems));
            assertMentions(problems, "duplicate quest id \"twice\"");
        }

        @Test
        @DisplayName("and only the first is in the tree, because a row no lookup can reach is worse than none")
        void theLoserIsNotInTheTree() {
            // **Reported and dropped, which are two different halves.** The index used to add the entry to
            // its list *before* claiming the id, so a duplicate produced two nodes on the canvas and one
            // entry in the map: the second drew, could be clicked, and the click opened the first quest.
            // `ClientQuestCache.byId` keeps the first for the same reason and says so in its own comment --
            // and a quest that is present but unreachable is worse than an absent one, because nothing
            // about it looks wrong. The positions tell the two apart, which the titles cannot: the builder
            // writes the id as the title.
            Problems problems = new Problems();
            QuestIndex index = indexOf(problems, Fixtures.file(
                    q("twice").at(0, 0).build(),
                    q("twice").at(64, 0).build()));

            assertTrue(problems.hasErrors(), "the duplicate is still fatal:" + messages(problems));
            assertEquals(1, index.questCount(), "one id, one quest");
            assertEquals(0, index.quest("twice").orElseThrow().quest().layout().x(),
                    "and the one the map resolves is the one the tree holds: the first to claim the id");
        }

        @Test
        @DisplayName("a dropped subtree's aliases are checked too, though they are never claimed")
        void aliasesInsideADroppedSubtree() {
            // The residual the drop left, closed. An alias clash inside a subtree whose parent lost an id
            // used to be reported one load later -- after the author repaired the parent -- which is two
            // rounds of fixing one mistake. Checked and not claimed, for the reason the id is: an alias
            // resolving to a row that is not in the tree is the fault the drop exists to remove.
            //
            // The second file's group collides, so its chapter and its quest are both orphaned -- and the
            // quest's alias is the only thing in that file that could be reported at all.
            String first = "{ \"version\": 1, \"chapterGroups\": ["
                    + " { \"id\": \"group\", \"title\": \"Group\", \"chapters\": ["
                    + Fixtures.chapter("first_steps", q("alpha").alias("shared").build()) + "] }"
                    + " ] }";
            String second = "{ \"version\": 1, \"chapterGroups\": ["
                    + " { \"id\": \"group\", \"title\": \"Group\", \"chapters\": ["
                    + Fixtures.chapter("first_steps", q("beta").alias("shared").build()) + "] }"
                    + " ] }";
            Problems problems = new Problems();
            QuestIndex index = indexOf(problems, first, second);

            assertMentions(problems, "duplicate chapter group id \"group\"");
            assertMentions(problems, "the alias \"shared\" is already used by another quest");
            assertEquals(1, index.questCount(), "and the dropped subtree is still out of the tree");
            assertNotNull(index.quest("alpha"), "with the quest that claimed the alias");
        }

        @Test
        @DisplayName("a duplicate group takes its subtree out of the tree, and still reports every clash in it")
        void aDuplicateGroupTakesItsSubtree() {
            // Two whole trees in two files, so every level collides at once -- which is what a pack
            // half-converted from another mod looks like, and `QuestFormatMigrationTest` pins the case.
            //
            // **The two halves pull in opposite directions and both have to hold.** The subtree is not
            // loaded, because a chapter nobody can open would otherwise leave its quests invisible and
            // still live: resolving by id, accruing progress, blocking dependencies, on a canvas no row
            // reaches. And every clash inside it is still reported, because they are separate faults the
            // author has to fix and hearing about them one load later is two rounds of fixing one mistake.
            Problems problems = new Problems();
            QuestIndex index = indexOf(problems,
                    Fixtures.file(q("only").build()),
                    Fixtures.file(q("other").build()));

            assertTrue(problems.hasErrors(), "the duplicates are fatal:" + messages(problems));
            assertMentions(problems, "duplicate chapter group id \"group\"");
            // Reported even though its group was dropped: it is a separate fault the author has to fix,
            // and hearing about it one load later would be two rounds of fixing one mistake.
            assertMentions(problems, "duplicate chapter id \"chapter\"");

            assertEquals(1, index.groupCount(), "one group, and the first to claim the id is the one kept");
            assertEquals(1, index.chapterCount(), "and one chapter");
            assertEquals(1, index.questCount(), "and one quest: the dropped chapter takes its own with it");
            assertNotNull(index.quest("only"), "the quest of the tree that was kept");
            assertTrue(index.quest("other").isEmpty(),
                    "and not the one whose chapter is not in the tree");
        }

        @Test
        @DisplayName("two chapters in different groups sharing a name: the second is dropped, not shadowed")
        void twoChaptersWithOneName() {
            // **The one that is not exotic.** A chapter's id has to equal its folder name, so two chapters
            // called `first_steps` means two folders with that name under two different groups -- which is
            // an ordinary authoring mistake rather than a contrived one. Left in the tree, the second row
            // draws and cannot be opened: `index.chapter` and `QuestEditor.open` both resolve the id to the
            // first folder, so the second chapter's quests are unreachable while still counting.
            String twoGroups = "{ \"version\": 1, \"chapterGroups\": ["
                    + " { \"id\": \"one\", \"title\": \"One\", \"chapters\": ["
                    + Fixtures.chapter("first_steps", q("first").build()) + "] },"
                    + " { \"id\": \"two\", \"title\": \"Two\", \"chapters\": ["
                    + Fixtures.chapter("first_steps", q("second").build()) + "] }"
                    + " ] }";
            Problems problems = new Problems();
            QuestIndex index = indexOf(problems, twoGroups);

            assertTrue(problems.hasErrors(), "the clash is fatal:" + messages(problems));
            assertMentions(problems, "duplicate chapter id \"first_steps\"");
            assertEquals(2, index.groupCount(), "the groups are not duplicates, so both stay");
            assertEquals(1, index.chapterCount(), "and only one chapter claims the name");
            assertTrue(index.quest("second").isEmpty(),
                    "so the shadowed chapter's quest is not in the tree either");
        }

        @Test
        @DisplayName("an alias that another quest already uses as an id is an error")
        void aliasClashingWithAnIdIsAnError() {
            // The reason this is fatal rather than a warning: a lookup of "alpha" would be ambiguous,
            // and a lookup is what progress is keyed on. Ambiguity here is somebody's progress landing
            // on the wrong quest.
            Problems problems = problemsOf(Fixtures.file(
                    q("alpha").build(),
                    q("beta").alias("alpha").build()));

            assertTrue(problems.hasErrors(), "an ambiguous alias is fatal:" + messages(problems));
            assertMentions(problems, "is already used by another quest");
        }

        @Test
        @DisplayName("an alias declared twice on one quest is an error")
        void duplicateAliasOnOneQuestIsAnError() {
            Problems problems = problemsOf(Fixtures.file(
                    q("alpha").alias("old_name", "old_name").build()));

            assertTrue(problems.hasErrors(), "a repeated alias is fatal:" + messages(problems));
            assertMentions(problems, "declared twice on the same quest");
        }

        @Test
        @DisplayName("a chapter and a quest may share an id")
        void kindsDoNotCollide() {
            // Deliberately allowed, and worth a test: the three tables are separate for exactly this
            // reason, and collapsing them into one map would break it silently.
            Problems problems = problemsOf(Fixtures.fileWithChapter(
                    "\"id\": \"first_steps\",", q("first_steps").build()));

            assertTrue(!problems.hasErrors(), "an id may be reused across kinds:" + messages(problems));
        }
    }

    @Nested
    @DisplayName("case-insensitive lookup")
    class CaseInsensitiveLookup {

        @Test
        @DisplayName("an id, an alias and every case mix of either resolve the same quest")
        void everyCaseMixResolvesTheSameQuest() {
            // legacy ids are uppercase hexadecimal and legacy packs never cared about case, so neither
            // does the lookup: the table is keyed by the lowercased name, and each entry keeps its
            // own id for display, storage and progress.
            Problems problems = new Problems();
            QuestIndex index = indexOf(problems, Fixtures.file(
                    q("myquest").alias("OldName").build()));

            assertTrue(!problems.hasErrors(), "nothing here is a clash:" + messages(problems));
            for (String spelling : new String[] {"myquest", "MYQUEST", "MyQuest", "mYqUeSt",
                    "oldname", "OLDNAME", "OldName", "oLdNaMe"}) {
                assertEquals("myquest", index.quest(spelling).orElseThrow().quest().id(),
                        "spelling " + spelling + " should resolve");
            }
        }

        @Test
        @DisplayName("a dependency written in another case resolves")
        void dependencyInAnotherCaseResolves() {
            // The validator still requires lowercase dependency strings, so this file would hear
            // about that there; the index's job is only to not break the reference.
            Problems problems = problemsOf(Fixtures.file(
                    q("myquest").build(),
                    q("b").dependsOn("MYQUEST").build()));

            assertDoesNotMention(problems, "no quest with id or alias");
        }

        @Test
        @DisplayName("two names differing only in case on two quests are a clash naming the other")
        void caseOnlyClashIsAnError() {
            // Normalisation must not loosen duplicate detection: a lookup of "abc" would be
            // ambiguous, and it never picks a winner.
            Problems problems = problemsOf(Fixtures.file(
                    q("abc").build(),
                    q("def").alias("ABC").build()));

            assertTrue(problems.hasErrors(), "a case-only clash is fatal:" + messages(problems));
            assertMentions(problems, "is already used by another quest");
        }

        @Test
        @DisplayName("an alias differing from its own id only in case says so")
        void aliasMatchingOwnIdInAnotherCaseIsAnError() {
            // It names nothing the id does not already name, so it is an error rather than dead
            // weight: a file carrying it is a file somebody will "fix" by pointing at it.
            Problems problems = problemsOf(Fixtures.file(
                    q("abc").alias("ABC").build()));

            assertTrue(problems.hasErrors(), "a no-op alias is fatal:" + messages(problems));
            assertMentions(problems, "differs from this quest's own id only in case");
        }

        @Test
        @DisplayName("a self-dependency in another case is still a self-dependency")
        void selfDependencyInAnotherCaseIsAnError() {
            Problems problems = problemsOf(Fixtures.file(
                    q("loop").dependsOn("LOOP").build()));

            assertTrue(problems.hasErrors(), "a self-dependency never unlocks:");
            assertMentions(problems, "depends on itself");
        }

        @Test
        @DisplayName("chapters resolve in any case too")
        void chaptersResolveInAnyCase() {
            Problems problems = new Problems();
            QuestIndex index = indexOf(problems,
                    Fixtures.fileWithChapter("\"id\": \"my_chapter\",", q("a").build()));

            assertTrue(!problems.hasErrors(), "nothing here is a clash:" + messages(problems));
            assertTrue(index.chapter("MY_CHAPTER").isPresent(), "the chapter should resolve");
            assertTrue(index.chapter("My_Chapter").isPresent(), "in any case mix");
        }
    }

    @Nested
    @DisplayName("tags")
    class Tags {

        @Test
        @DisplayName("a #tag lookup resolves to the first quest carrying it")
        void hashTagResolvesToFirstQuest() {
            Problems problems = new Problems();
            QuestIndex index = indexOf(problems, Fixtures.file(
                    "{\"id\": \"first\", \"title\": \"First\", \"tags\": [\"village\"]}",
                    "{\"id\": \"second\", \"title\": \"Second\", \"tags\": [\"village\"]}"));

            assertFalse(problems.hasErrors(), "tags are clean:" + messages(problems));
            assertEquals("first", index.quest("#village").orElseThrow().quest().id());
            assertEquals("first", index.questWithTag("village").orElseThrow().quest().id(),
                    "and the direct lookup agrees");
            assertTrue(index.quest("#nowhere").isEmpty(), "a tag nobody carries resolves to nothing");
            assertTrue(index.quest("#").isEmpty(), "a bare hash is not a tag");
        }

        @Test
        @DisplayName("a tag shared by a quest and a chapter answers whichever was asked for")
        void hashTagResolvesPerKind() {
            Problems problems = new Problems();
            QuestIndex index = indexOf(problems, Fixtures.fileWithChapters(
                    Fixtures.chapterWith("one", "\"tags\": [\"shared\"],",
                            "{\"id\": \"q\", \"title\": \"Q\", \"tags\": [\"shared\"]}")));

            assertFalse(problems.hasErrors(), "tags are clean:" + messages(problems));
            assertEquals("one", index.chapter("#shared").orElseThrow().chapter().id());
            assertEquals("q", index.quest("#shared").orElseThrow().quest().id(),
                    "a quest lookup never answers a chapter");
        }

        @Test
        @DisplayName("a #tag on a group resolves to the first group carrying it")
        void hashTagOnGroup() {
            Problems problems = new Problems();
            QuestIndex index = indexOf(problems, """
                    {"version": 1, "chapterGroups": [
                      {"id": "g1", "title": "G1", "tags": ["shared"],
                       "chapters": [{"id": "c1", "title": "C1", "quests": []}]},
                      {"id": "g2", "title": "G2", "tags": ["shared"],
                       "chapters": [{"id": "c2", "title": "C2", "quests": []}]}
                    ]}""");

            assertFalse(problems.hasErrors(), "tags are clean:" + messages(problems));
            assertEquals("g1", index.group("#shared").orElseThrow().group().id());
            assertEquals("g1", index.groupWithTag("shared").orElseThrow().group().id(),
                    "and the direct lookup agrees");
        }
    }

    // ------------------------------------------------------------------
    // Dependencies
    // ------------------------------------------------------------------

    @Nested
    @DisplayName("dependencies")
    class Dependencies {

        @Test
        @DisplayName("a dependency on a quest that does not exist is an error")
        void unresolvedDependencyIsAnError() {
            Problems problems = problemsOf(Fixtures.file(
                    q("b").dependsOn("nowhere").build()));

            assertTrue(problems.hasErrors(), "an unresolved dependency strands the quest:");
            assertMentions(problems, "no quest with id or alias \"nowhere\" exists");
            // The consequence, spelled out. "No such quest" is accurate and does not tell an author
            // why they should care.
            assertMentions(problems, "can never be unlocked");
        }

        @Test
        @DisplayName("a near miss suggests the id that was probably meant")
        void unresolvedDependencySuggestsANearMiss() {
            Problems problems = problemsOf(Fixtures.file(
                    q("punch_a_tree").build(),
                    q("b").dependsOn("punch_a_tre").build()));

            assertMentions(problems, "did you mean \"punch_a_tree\"?");
        }

        @Test
        @DisplayName("a quest that depends on itself is an error")
        void selfDependencyIsAnError() {
            Problems problems = problemsOf(Fixtures.file(
                    q("loop").dependsOn("loop").build()));

            assertTrue(problems.hasErrors(), "a self-dependency never unlocks:");
            assertMentions(problems, "depends on itself");
        }

        @Test
        @DisplayName("a dependency on another quest's alias resolves")
        void dependencyOnAnAliasResolves() {
            // The whole point of aliases: renaming a quest must not break the references to it, and a
            // reference by the old name has to keep working.
            Problems problems = problemsOf(Fixtures.file(
                    q("renamed").alias("old_name").build(),
                    q("b").dependsOn("old_name").build()));

            assertTrue(!problems.hasErrors(), "an alias should resolve:" + messages(problems));
            assertDoesNotMention(problems, "no quest with id or alias");
        }

        @Test
        @DisplayName("minRequired higher than the dependency count is an error")
        void minRequiredBeyondDependenciesIsAnError() {
            Problems problems = problemsOf(Fixtures.file(
                    q("a").build(),
                    q("b").dependsOn("a").minRequired(3).build()));

            assertTrue(problems.hasErrors(), "it can never be unlocked:");
            assertMentions(problems, "but there are only 1");
        }
    }

    // ------------------------------------------------------------------
    // An element's gate
    // ------------------------------------------------------------------

    @Nested
    @DisplayName("an element's gate")
    class ElementGates {

        /** A chapter carrying an element list, with one quest so the file is not otherwise odd. */
        private static String chapterWithElements(String elements) {
            return Fixtures.fileWithChapter("\"elements\": " + elements + ",", q("a").build());
        }

        @Test
        @DisplayName("a requires that names no quest is an error, with the consequence spelled out")
        void unresolvedRequiresIsAnError() {
            // The cross-file half, and the one an author cannot see from the chapter they are editing: the
            // element may be waiting on a quest in a chapter they have never opened.
            Problems problems = problemsOf(chapterWithElements(
                    "[ { \"type\": \"rect\", \"id\": \"box\", \"requires\": \"nowhere\" } ]"));

            assertTrue(problems.hasErrors(), "an element gated on nothing is never drawn:");
            assertMentions(problems, "no quest with id or alias \"nowhere\" exists");
            assertMentions(problems, "never drawn");
            // Reported at the element's own gate rather than at the chapter, because that is where the
            // author has to go: the chapter-level path would send them looking through the whole object.
            assertTrue(problems.all().stream()
                            .anyMatch(problem -> problem.path().contains("elements[0].requires")),
                    "named at its own path: " + messages(problems));
        }

        @Test
        @DisplayName("a near miss suggests the id that was probably meant")
        void unresolvedRequiresSuggestsANearMiss() {
            Problems problems = problemsOf(Fixtures.fileWithChapter(
                    "\"elements\": [ { \"type\": \"rect\", \"id\": \"box\","
                            + " \"requires\": \"punch_a_tre\" } ],",
                    q("punch_a_tree").build()));

            assertMentions(problems, "did you mean \"punch_a_tree\"?");
        }

        @Test
        @DisplayName("a gate on a quest resolves, by id or by alias")
        void resolvedRequiresIsClean() {
            Problems problems = problemsOf(Fixtures.fileWithChapter(
                    "\"elements\": [ { \"type\": \"rect\", \"id\": \"box\","
                            + " \"requires\": \"old_name\" } ],",
                    q("renamed").alias("old_name").build()));

            assertFalse(problems.hasErrors(), "an alias should resolve:" + messages(problems));
            assertDoesNotMention(problems, "no quest with id or alias");
        }

        @Test
        @DisplayName("an element's id is not a quest, so a gate can never find one")
        void anElementIdIsNotAQuest() {
            // The trap this check exists for. A converted chapter's element ids are sixteen hex digits,
            // which is exactly what a converted *quest* id looks like -- so a `requires` that could find an
            // element would silently point at a decoration, and the element would never be drawn with
            // nothing anywhere saying why. Only quests resolve.
            Problems problems = problemsOf(chapterWithElements(
                    "[ { \"type\": \"rect\", \"id\": \"aaaaaaaaaaaaaaaa\" },"
                            + " { \"type\": \"text\", \"id\": \"label\", \"text\": \"x\","
                            + " \"requires\": \"aaaaaaaaaaaaaaaa\" } ]"));

            assertTrue(problems.hasErrors(), "an element is not a quest:");
            assertMentions(problems, "no quest with id or alias \"aaaaaaaaaaaaaaaa\" exists");
        }

        @Test
        @DisplayName("an open_quest whose target does not exist is an error, because the press would do nothing")
        void anUnresolvableClickTargetIsAnError() {
            // The same cross-file question as a gate, and the same reason it is answered here: one chapter
            // cannot see another's quests. Reported at load rather than left to the press, because a press
            // that does nothing reads as a broken control rather than as a gap in the mod.
            Problems problems = problemsOf(chapterWithElements(
                    "[ { \"type\": \"image\", \"id\": \"logo\","
                            + " \"image\": { \"sprite\": \"minecraft:block/sculk\" },"
                            + " \"click\": { \"type\": \"open_quest\", \"data\": \"nowhere\" } } ]"));

            assertTrue(problems.hasErrors(), "a press with no target:");
            assertMentions(problems, "no quest with id or alias \"nowhere\" exists");
            assertMentions(problems, "pressing this element would do nothing");

            // And a target that does resolve is clean, by id or by alias.
            Problems clean = problemsOf(Fixtures.fileWithChapter(
                    "\"elements\": [ { \"type\": \"image\", \"id\": \"logo\","
                            + " \"image\": { \"sprite\": \"minecraft:block/sculk\" },"
                            + " \"click\": { \"type\": \"open_quest\", \"data\": \"old_name\" } } ],",
                    q("renamed").alias("old_name").build()));
            assertFalse(clean.hasErrors(), messages(clean));
        }

        @Test
        @DisplayName("an element is never counted for a chapter's completion, however it is gated")
        void elementsAreNotProgress() {
            // The property that lets the editor classify every element edit as cosmetic, and the one a
            // fifth element type would have to keep: a gate reads a quest's state and changes nothing.
            Problems problems = problemsOf(chapterWithElements(
                    "[ { \"type\": \"rect\", \"id\": \"box\", \"requires\": \"a\" } ]"));

            assertFalse(problems.hasErrors(), messages(problems));
            assertDoesNotMention(problems, "dependsOn");
            assertDoesNotMention(problems, "completed");
        }
    }

    // ------------------------------------------------------------------
    // Quest links
    // ------------------------------------------------------------------

    @Nested
    @DisplayName("a link's target")
    class LinkTargets {

        /** A chapter carrying a link list, with one quest so the file is not otherwise odd. */
        private static String chapterWithLinks(String links) {
            return Fixtures.fileWithChapter("\"links\": " + links + ",", q("a").build());
        }

        @Test
        @DisplayName("a target that names no quest is an error, with the consequence spelled out")
        void unresolvedTargetIsAnError() {
            // The cross-file half, and the one an author cannot see from the chapter they are editing:
            // the target may live in a chapter they have never opened.
            Problems problems = problemsOf(chapterWithLinks(
                    "[ { \"id\": \"gate_hint\", \"quest\": \"nowhere\" } ]"));

            assertTrue(problems.hasErrors(), "a link with no target:");
            assertMentions(problems, "no quest with id or alias \"nowhere\" exists");
            assertMentions(problems, "pressing it would do nothing");
            assertTrue(problems.all().stream()
                            .anyMatch(problem -> problem.path().contains("links[0].quest")),
                    "named at its own path: " + messages(problems));
        }

        @Test
        @DisplayName("a near miss suggests the id that was probably meant")
        void unresolvedTargetSuggestsANearMiss() {
            Problems problems = problemsOf(Fixtures.fileWithChapter(
                    "\"links\": [ { \"id\": \"gate_hint\", \"quest\": \"punch_a_tre\" } ],",
                    q("punch_a_tree").build()));

            assertMentions(problems, "did you mean \"punch_a_tree\"?");
        }

        @Test
        @DisplayName("a target resolves, by id or by alias")
        void resolvedTargetIsClean() {
            Problems problems = problemsOf(Fixtures.fileWithChapter(
                    "\"links\": [ { \"id\": \"gate_hint\", \"quest\": \"old_name\" } ],",
                    q("renamed").alias("old_name").build()));

            assertFalse(problems.hasErrors(), "an alias should resolve:" + messages(problems));
            assertDoesNotMention(problems, "no quest with id or alias");
        }

        @Test
        @DisplayName("a link's id must not shadow a quest, or a press opens the wrong node")
        void linkIdMayNotShadowAQuest() {
            // The shape every converted pack takes: sixteen hex digits either side. Without this a
            // canvas addresses two nodes by one name, and the press opens whichever the lookup
            // finds first — a fault with no visible cause, since both spellings are plausible.
            Problems problems = problemsOf(chapterWithLinks(
                    "[ { \"id\": \"a\", \"quest\": \"a\" } ]"));

            assertTrue(problems.hasErrors(), "a link sharing its target's name:");
            assertMentions(problems, "must not equal any quest, chapter or group id or alias");
            assertMentions(problems, "\"a\" names the quest \"a\"");
        }

        @Test
        @DisplayName("a link id is not a quest, so a target can never find one")
        void aLinkIdIsNotAQuest() {
            // The trap the resolution check exists for, from the other side: a target naming a link
            // would silently point at a marker, and the link would mirror nothing. Only quests
            // resolve.
            Problems problems = problemsOf(chapterWithLinks(
                    "[ { \"id\": \"marker\", \"quest\": \"a\" },"
                            + " { \"id\": \"echo\", \"quest\": \"marker\" } ]"));

            assertTrue(problems.hasErrors(), "a target naming a link:");
            assertMentions(problems, "no quest with id or alias \"marker\" exists");
        }

        @Test
        @DisplayName("a link is never counted for a chapter's completion, however it points")
        void linksAreNotProgress() {
            // The property that lets the editor classify every link edit as cosmetic, and the one a
            // link with ambitions would have to keep: a marker reads a quest's state and changes
            // nothing. Pinned beside the elements' own case, which says the same for decorations.
            Problems problems = problemsOf(chapterWithLinks(
                    "[ { \"id\": \"gate_hint\", \"quest\": \"a\" } ]"));

            assertFalse(problems.hasErrors(), messages(problems));
            assertDoesNotMention(problems, "dependsOn");
            assertDoesNotMention(problems, "completed");
        }
    }

    // ------------------------------------------------------------------
    // Content smells
    // ------------------------------------------------------------------

    @Nested
    @DisplayName("content smells")
    class ContentSmells {

        @Test
        @DisplayName("a quest with no tasks and no rewards warns")
        void emptyQuestWarns() {
            // A warning, not an error: it works, there is just nothing in it. It is almost always a
            // quest someone started writing and saved.
            Problems problems = problemsOf(Fixtures.file(q("stub").noTasks().build()));

            assertTrue(!problems.hasErrors(), "an empty quest still loads:");
            assertMentions(problems, "no tasks and no rewards");
            assertMentions(problems, "nothing depends on it");
            assertEquals(1, problems.warningCount(), "exactly one warning:" + messages(problems));
        }

        @Test
        @DisplayName("a taskless quest with a dependant is a junction, not a ghost")
        void junctionWithADependantDoesNotWarn() {
            // legacy authors use empty quests as visual junctions, milestones and chapter gates, and a
            // branch behind one has to open. Warning about those would be noise on every converted
            // pack, so only a taskless quest with nothing behind it warns. Apart on the canvas, so
            // the position checks have nothing to say: this test is about the ghost rule.
            Problems problems = problemsOf(Fixtures.file(
                    q("junction").noTasks().at(0, 0).build(),
                    q("after").dependsOn("junction").at(132, 0).build()));

            assertTrue(!problems.hasErrors(), "a junction still loads:");
            assertDoesNotMention(problems, "no tasks and no rewards");
            assertEquals(0, problems.warningCount(), "no warnings at all:" + messages(problems));
        }

        @Test
        @DisplayName("a dependant through an alias spares the junction too")
        void aliasDependantCounts() {
            // The dependant set resolves by id or by normalised alias, so a gate written through an
            // alias still counts: the lookup's job is to not break a reference somebody wrote in
            // another spelling, and the ghost check reads through the same table. Apart on the canvas,
            // for the reason the junction test gives.
            Problems problems = problemsOf(Fixtures.file(
                    q("junction").noTasks().alias("gate").at(0, 0).build(),
                    q("after").dependsOn("gate").at(132, 0).build()));

            assertTrue(!problems.hasErrors(), "an aliased gate still loads:");
            assertDoesNotMention(problems, "no tasks and no rewards");
            assertEquals(0, problems.warningCount(), "no warnings at all:" + messages(problems));
        }

        @Test
        @DisplayName("a taskless quest with rewards is a payout waiting for a task, not a ghost")
        void tasklessQuestWithRewardsDoesNotWarn() {
            // Nothing to do in it yet, but something to give from it: the fixture builder never
            // emits rewards, so this one is raw JSON.
            Problems problems = problemsOf(Fixtures.file(
                    """
                            {"id": "prize", "title": "Prize",
                             "rewards": [{"type": "tenet:item", "item": "minecraft:stone"}]}"""));

            assertTrue(!problems.hasErrors(), "a reward-only quest still loads:");
            assertDoesNotMention(problems, "no tasks and no rewards");
        }

        @Test
        @DisplayName("a quest with only inbound links still warns: links gate nothing")
        void linkAloneDoesNotSpareTheGhost() {
            // A link mirrors without gating, so a quest nothing depends on is still a ghost however
            // many markers point at it. The link id must not shadow the quest; the target resolves.
            Problems problems = problemsOf(Fixtures.fileWithChapter(
                    "\"links\": [{\"id\": \"marker\", \"quest\": \"lonely\", \"x\": 0, \"y\": 0}],",
                    q("lonely").noTasks().build()));

            assertTrue(!problems.hasErrors(), "a linked-to ghost still loads:");
            assertMentions(problems, "no tasks and no rewards");
            assertEquals(1, problems.warningCount(), "the ghost, and only the ghost:"
                    + messages(problems));
        }
    }

    // ------------------------------------------------------------------
    // Crowding
    // ------------------------------------------------------------------

    /**
     * The rule that came out of a screenshot.
     *
     * <p>Two quests in the same row, close enough together that their titles cannot both be drawn.
     * The client survives it — it measures the room and truncates — so this is a warning rather than
     * an error, and the point is that an author hears about it at load instead of at play.
     */
    @Nested
    @DisplayName("crowding")
    class Crowding {

        @Test
        @DisplayName("two long titles 32px apart in one row warn, if both are drawn")
        void closeTogetherInARowWarn() {
            // 32 is below the 64-pixel threshold converted packs are laid out on: two labels about
            // 120px wide in 32 pixels of room cannot both be drawn, and the client truncating them
            // is what this warning is about.
            //
            // `showTitle(true)` on both is not incidental detail -- it is what makes this the case the
            // check is about. Titles are off by default, so without it there would be nothing drawn in
            // that 32 pixels and nothing to collide.
            Problems problems = problemsOf(Fixtures.file(
                    q(LONG_ID).at(0, 0).showTitle(true).build(),
                    q(WIDER_ID).at(32, 0).showTitle(true).build()));

            assertTrue(!problems.hasErrors(), "crowding is a warning, not a failure:");
            assertMentions(problems, "pixels apart in the same row");
            assertMentions(problems, "will overlap and run together");
        }

        @Test
        @DisplayName("the same two titles on the 64-pixel conversion grid do not warn")
        void conversionGridSpacingDoesNotWarn() {
            // 64 is the spacing the migration tool writes and the threshold itself: a gap at the
            // threshold is room enough, so a converted row with two long titles loads quietly. The
            // client still truncates what does not fit -- this check is about cramped authoring,
            // not about exact overlap.
            Problems problems = problemsOf(Fixtures.file(
                    q(LONG_ID).at(0, 0).showTitle(true).build(),
                    q(WIDER_ID).at(64, 0).showTitle(true).build()));

            assertDoesNotMention(problems, "pixels apart in the same row");
        }

        @Test
        @DisplayName("the same two quests 64px apart with neither name drawn do not warn")
        void unnamedQuestsCannotCrowd() {
            // The case the labels' new default created, and the reason the check had to change with it.
            // Two 20-character titles 64 pixels apart look exactly like the reported bug -- and nothing
            // at all is drawn between them, so there is nothing to report.
            //
            // A check that ignored this would fire on every tightly-packed chapter of unnamed nodes,
            // which is a layout the default now actively encourages.
            Problems problems = problemsOf(Fixtures.file(
                    q(LONG_ID).at(0, 0).build(),
                    q(WIDER_ID).at(64, 0).build()));

            assertDoesNotMention(problems, "pixels apart in the same row");
        }

        @Test
        @DisplayName("and one named beside one unnamed does not warn either")
        void oneNamedNeighbourIsNotCrowding() {
            // Half the pair is drawn, so the drawn one has the whole 64 pixels to itself. That is a
            // real difference from "both are drawn" rather than a technicality: the screen measures the
            // room from the names it is going to draw, so an unnamed neighbour contributes no
            // competition for the space.
            Problems problems = problemsOf(Fixtures.file(
                    q(LONG_ID).at(0, 0).showTitle(true).build(),
                    q(WIDER_ID).at(64, 0).build()));

            assertDoesNotMention(problems, "pixels apart in the same row");
        }

        @Test
        @DisplayName("the same two names at 132px apart do not warn")
        void generousSpacingDoesNotWarn() {
            // 132 is what the shipped example questline now uses. If this ever starts warning, the
            // threshold has drifted away from the label cap the client actually applies.
            Problems problems = problemsOf(Fixtures.file(
                    q(LONG_ID).at(0, 0).showTitle(true).build(),
                    q(WIDER_ID).at(132, 0).showTitle(true).build()));

            assertDoesNotMention(problems, "pixels apart in the same row");
        }

        @Test
        @DisplayName("short titles 64px apart do not warn, because they fit")
        void shortTitlesDoNotWarn() {
            // The check is about the titles, not about the spacing on its own. Three characters is
            // about 18px, so 64px of room is ample -- and warning here would be noise, which is how a
            // check gets ignored.
            Problems problems = problemsOf(Fixtures.file(
                    q("aaa").at(0, 0).showTitle(true).build(),
                    q("bbb").at(64, 0).showTitle(true).build()));

            assertDoesNotMention(problems, "pixels apart in the same row");
        }

        @Test
        @DisplayName("quests 64px apart in different rows do not warn")
        void differentRowsDoNotWarn() {
            // A label is drawn *below* its node, so two labels on different rows never meet however
            // close the nodes are horizontally. Comparing every pair rather than only row-mates would
            // report a branch as a fault.
            Problems problems = problemsOf(Fixtures.file(
                    q(LONG_ID).at(0, 0).showTitle(true).build(),
                    q(WIDER_ID).at(64, 64).showTitle(true).build()));

            assertDoesNotMention(problems, "pixels apart in the same row");
        }

        @Test
        @DisplayName("quests in different chapters do not warn")
        void differentChaptersDoNotWarn() {
            // Two chapters are two separate canvases, so the same coordinates in each are independent.
            // Keying rows by chapter as well as row is what makes that true.
            //
            // Two files, passed as two arguments. Concatenating them into one string would produce a
            // document that is not JSON at all, and the test would then be asserting about a parse
            // failure rather than about chapters -- which is how it was written the first time.
            Problems problems = problemsOf(
                    Fixtures.fileWithChapter("\"id\": \"one\",", q(LONG_ID).at(0, 0).showTitle(true).build()),
                    Fixtures.fileWithChapter("\"id\": \"two\",", q(WIDER_ID).at(64, 0).showTitle(true).build()));

            assertDoesNotMention(problems, "pixels apart in the same row");
        }

        @Test
        @DisplayName("quests at the same coordinates in different chapters are not duplicates")
        void samePositionInDifferentChaptersIsNotADuplicate() {
            // Every chapter starts at 0,0, which is what makes a new chapter easy to write -- and this
            // is the check that had to be keyed by chapter when a second shipped file started doing it.
            // Reporting the first quest of every chapter as stacked would make the natural layout
            // impossible, and the message would be about coordinates rather than about anything wrong.
            Problems problems = problemsOf(
                    Fixtures.fileWithChapter("\"id\": \"one\",", q(LONG_ID).at(0, 0).build()),
                    Fixtures.fileWithChapter("\"id\": \"two\",", q(WIDER_ID).at(0, 0).build()));

            assertDoesNotMention(problems, "is at the same position");
        }

        @Test
        @DisplayName("two quests at exactly the same spot are reported once, as a duplicate")
        void stackedQuestsAreNotAlsoReportedAsCrowded() {
            // A gap of 0 is a duplicate position, which checkDuplicatePositions already reports with a
            // better message. Reporting it twice -- once as stacked, once as crowded -- would train an
            // author to skim the output, and "these two titles will overlap" is not the interesting
            // part of two nodes being on top of each other.
            Problems problems = problemsOf(Fixtures.file(
                    q(LONG_ID).at(0, 0).showTitle(true).build(),
                    q(WIDER_ID).at(0, 0).showTitle(true).build()));

            assertMentions(problems, "is at the same position");
            assertDoesNotMention(problems, "pixels apart in the same row");
            assertEquals(1, problems.warningCount(), "exactly one warning:" + messages(problems));
        }

        @Test
        @DisplayName("a row of three reports each crowded neighbour pair")
        void everyCrowdedPairInARowIsReported() {
            // Each adjacent pair, not just the first. An author who fixes only the pair they were told
            // about would otherwise have to reload once per collision.
            Problems problems = problemsOf(Fixtures.file(
                    q(LONG_ID).at(0, 0).showTitle(true).build(),
                    q(WIDER_ID).at(32, 0).showTitle(true).build(),
                    q(LONG_ID + "_b").at(64, 0).showTitle(true).build()));

            long crowded = problems.all().stream()
                    .filter(problem -> problem.message().contains("pixels apart in the same row"))
                    .count();
            assertEquals(2, crowded, "two adjacent pairs:" + messages(problems));
        }

        @Test
        @DisplayName("a rounding nudge apart is not a duplicate: the tool's +1,+1 lands here")
        void nudgedApartIsNotADuplicate() {
            // The migration tool resolves a rounding collision by nudging (+1,+1) and reporting it,
            // so the position this produces must load quietly: one pixel apart is not stacked, and
            // with neither name drawn there is nothing to crowd either.
            Problems problems = problemsOf(Fixtures.file(
                    q(LONG_ID).at(0, 0).build(),
                    q(WIDER_ID).at(1, 0).build()));

            assertDoesNotMention(problems, "is at the same position");
            assertEquals(0, problems.warningCount(), "no warnings at all:" + messages(problems));
        }
    }

    // ------------------------------------------------------------------
    // The mod ships nothing, and the examples are examples
    // ------------------------------------------------------------------

    /**
     * Where the worked examples live.
     *
     * <p><b>Not</b> in {@code src/main/resources}. They used to be: {@code /tenet/default_quests/} in
     * the jar, copied into {@code config/tenet/quests} by the loader. They moved out for two reasons,
     * and the first is the important one — a mod that installs example chapters into every
     * player's config directory has decided something that is not its to decide, and the first thing a
     * pack author would have to do is delete somebody else's content.
     *
     * <p>The second is smaller and still real: a quest file in the jar is a file that has to keep
     * working forever against a format that is still moving, and every format change is a migration
     * for content nobody asked for.
     *
     * <p>So they are authoring documentation in the repository, and
     * {@code tenet/tools/seed_quests.py} is what copies them somewhere. An author reads them; a
     * player never sees them unless they ask, which is the whole difference.
     *
     * <p>Relative to the Gradle project directory, which is {@code tenet/common} — the same place the
     * old path was relative to, so the tests run from the same working directory either way.
     */
    private static final java.nio.file.Path EXAMPLES = java.nio.file.Path.of("..", "tools", "quests");

    /**
     * Loads the worked examples the way the mod does: copy them into a config directory, then run the
     * real loader over it.
     *
     * <h2>Why this goes through the loader rather than decoding the files itself</h2>
     *
     * <p>Because the examples are now a <b>folder tree</b>, and the only thing that knows how to turn a
     * folder tree into a questline is the loader. This used to build a {@link LoadedQuestFile} per file
     * by hand, which worked because version 1 puts one whole tree in one document — so "decode the
     * file" and "load the questline" were the same act, and a test was free to do the first directly.
     * Under version 2 they are not the same act at all: {@code group.json} decodes to a manifest holding
     * a list of <i>names</i> and nothing else, so a test that stopped at the codec would be asserting
     * about a tree with no chapters in it.
     *
     * <p>That is the same trap the seeding step had, one level up, and it fails silently in the same
     * direction: a manifest decodes perfectly and contributes nothing, so a test looping over the
     * examples would find every file, decode every one, and then assert about an empty questline.
     *
     * <h2>So it is the real path, including validation</h2>
     *
     * <p>{@link QuestLoader#load} parses, validates, decodes and indexes — every step the game runs.
     * These tests therefore now check something they could not before: that the shipped examples pass
     * the <b>validator</b>, not merely the codecs. That is strictly stronger, and it is why
     * {@link #bootVanilla()} sits at the top of this class: the validator resolves item ids against
     * {@code BuiltInRegistries.ITEM}, and an unbootstrapped registry does not report everything missing
     * — it throws from inside a vanilla class initialiser.
     *
     * <h2>The copy is not incidental</h2>
     *
     * <p>{@code QuestLoader} reads a directory and writes nothing, so a test that wants it to load
     * something has to put it there. That is exactly what {@code tenet/tools/seed_quests.py} does for a
     * player and what this does for a test. Copying rather than reading in place also keeps the
     * examples directory itself untouched, so no test can leave the repository dirty.
     *
     * <h2>Paths are preserved, and the underscore rule applies to every segment</h2>
     *
     * <p>The relative path is kept rather than flattened, because the layout <i>is</i> the format: a
     * quest called {@code punch_a_tree.json} at the quest root is not a quest at all, it is a version-1
     * file the loader will try to read as a whole tree.
     *
     * <p>And {@code _}-prefixed names are skipped at every segment, not just the last. The shipped
     * {@code _schema} folder is a <b>directory</b>, so a rule that tested only a file's own name would
     * copy all three schema files into the config directory — where the loader's own walk would then
     * skip them, so the mistake would be invisible from the test and visible only as three stray files
     * in somebody's install.
     *
     * @param configDir where to build the copy. Each caller passes a directory of its own, so two tests
     *     cannot collide over one config directory — and so a failed load cannot leave state behind for
     *     the next one.
     */
    private static QuestLoader.Result loadExamples(java.nio.file.Path configDir) throws java.io.IOException {
        java.nio.file.Path target = configDir.resolve(QuestLoader.DIRECTORY);
        try (var walk = java.nio.file.Files.walk(EXAMPLES)) {
            for (java.nio.file.Path source : walk.filter(java.nio.file.Files::isRegularFile).toList()) {
                java.nio.file.Path relative = EXAMPLES.relativize(source);
                if (isIgnored(relative)) {
                    continue;
                }
                java.nio.file.Path destination = target.resolve(relative.toString());
                java.nio.file.Files.createDirectories(destination.getParent());
                java.nio.file.Files.copy(source, destination);
            }
        }
        return QuestLoader.load(configDir);
    }

    /**
     * Whether a path under the examples directory is skipped.
     *
     * <p>Every segment, not just the last — the same rule {@code DeclaredPaths.isIgnored} states, and
     * for the same reason, which is the paragraph above. See {@link #loadExamples}.
     */
    private static boolean isIgnored(java.nio.file.Path relative) {
        for (java.nio.file.Path segment : relative) {
            if (segment.toString().startsWith("_")) {
                return true;
            }
        }
        return false;
    }

    @Test
    @DisplayName("the mod ships no quests of its own, in the source tree or on the classpath")
    void theModShipsNoQuests() {
        // The rule this round was about, pinned in the two places it could break.
        //
        // Both halves are needed. The source-tree check catches somebody putting the files back; the
        // classpath check catches a file that reached the built output some other way — a stale copy
        // in `build/resources`, or a resource added under a different path. Only the second one is a
        // check of what actually ends up in the jar.
        java.nio.file.Path inSource = java.nio.file.Path.of(
                "src", "main", "resources", "tenet", "default_quests");
        assertFalse(java.nio.file.Files.exists(inSource),
                "the mod is shipping quest files again, at " + inSource.toAbsolutePath()
                        + ". They belong in tenet/tools/quests, where they are authoring documentation"
                        + " rather than content installed into every player's config directory.");

        assertNull(QuestIndexTest.class.getResource("/tenet/default_quests/01_stone_age.json"),
                "a quest file is on the classpath, so it is in the jar. Delete it from"
                        + " src/main/resources -- the examples live in tenet/tools/quests now, and"
                        + " tenet/tools/seed_quests.py is what puts them in a config directory.");
    }

    @Test
    @DisplayName("every example questline loads with nothing wrong in it")
    void everyExampleQuestlineIsClean() throws java.io.IOException {
        // Read from the repository rather than a fixture, so the file an author is pointed at is the
        // one under test. `01_stone_age`'s spacing was 64 until the labelling bug, and this is what
        // stops it drifting back: a change that reintroduces crowding fails here rather than on
        // someone's screen.
        //
        // A loop over the directory rather than one file by name, because it used to read
        // `01_stone_age.json` alone — so a second example could have shipped with a broken dependency
        // in it and this test would have stayed green, wrong about the thing it was named for.
        assertTrue(java.nio.file.Files.isDirectory(EXAMPLES),
                "expected the examples at " + EXAMPLES.toAbsolutePath()
                        + " -- the tests run from the Gradle project directory, so this is relative to"
                        + " tenet/common");

        QuestLoader.Result loaded = loadExamples(temp.resolve("clean"));

        // Errors only, and this assertion is stricter than the one it replaces in the way that matters
        // while being looser in the way that does not.
        //
        // Looser: it used to demand that the problem list be *empty*. But it ran no validation, so there
        // was very little that could have appeared in that list — the check was close to tautological.
        // Now the validator runs, and "empty" would mean asserting something about the examples that is
        // not this test's business: a warning that two example quests stack their nodes is a legitimate
        // thing for example content to do, and failing on it would set the suite against whoever writes
        // the next example.
        //
        // Stricter: what must be true is that nothing in the examples is an *error* — a dangling
        // dependency, an id claimed twice, a field the codecs reject, a misspelled enum. That is what
        // `ok()` means, and unlike the old assertion it is a claim the loader is in a position to
        // contradict.
        assertTrue(loaded.ok(), "the examples should have nothing fatal in them, but reported:"
                + messages(loaded.problems()));

        // And not vacuous. Every assertion above passes on an empty index, which is precisely what a
        // broken copy step produces — see `loadExamples` on why the copy step is worth distrusting.
        assertTrue(loaded.filesFound() > 0,
                "no example files were found at all under " + EXAMPLES.toAbsolutePath());
        assertTrue(loaded.index().questCount() > 0,
                "the examples were read and contributed no quests, so either the copy step or the "
                        + "loader found nothing. Files found: " + loaded.filesFound());
    }

    @Test
    @DisplayName("the example chapter is one canvas, and it exercises everything")
    void theExampleQuestlinesExerciseDifferentThings() throws java.io.IOException {
        // A test that says what the example content is *for*, so a future tidy-up cannot quietly turn
        // an exhibition into a chapter that has lost half its mechanisms. The descriptions in the
        // files already claim all of this; this is the version a compiler reads.
        //
        // It grew with the exhibition, and the pattern is worth naming: every time a mechanism had no
        // example, the mechanism was the thing nobody could see how to write. So the assertions below
        // are not a checklist of the engine -- they are the list of things a reader is entitled to
        // find a worked example of. They survived the collapse from twelve questlines to one chapter;
        // the three that could not are noted where they used to sit.
        QuestLoader.Result loaded = loadExamples(temp.resolve("varied"));

        assertTrue(loaded.ok(), "fixture sanity -- the examples should be clean first:"
                + messages(loaded.problems()));

        QuestIndex index = loaded.index();
        List<Quest> all = index.quests().stream().map(QuestIndex.QuestEntry::quest).toList();

        // The mechanics, each of which exists in exactly one place and is named in the file that has it.
        assertTrue(all.stream().anyMatch(Quest::repeatable), "a repeatable quest");
        assertTrue(all.stream().anyMatch(quest -> quest.repeatCooldownTicks() > 0), "with a cooldown");
        assertTrue(all.stream().anyMatch(Quest::sequentialTasks), "a quest with sequential tasks");
        assertTrue(all.stream().anyMatch(Quest::invisible), "a hidden quest");
        assertTrue(all.stream().anyMatch(Quest::showTitle), "a quest whose name is drawn");
        assertTrue(all.stream().anyMatch(quest -> quest.exclusiveGroup().isPresent()), "an exclusive pair");
        assertTrue(all.stream().anyMatch(quest -> quest.minRequired() > 0), "an OR-gate");
        assertTrue(all.stream().anyMatch(quest -> quest.tasks().stream().anyMatch(QuestTask::optional)),
                "an optional task");

        // A task that takes the items, said on the task. The assertion this replaces checked
        // `consumes(false)` under the message "a task that takes the items", which is the check
        // inverted -- it was satisfied by every item task in the collection and proved nothing. What
        // must exist is a task whose own flag *takes* what it asks for.
        assertTrue(all.stream().anyMatch(quest -> quest.tasks().stream().anyMatch(task ->
                        task instanceof ItemTask item && item.consumeItems().orElse(false))),
                "no example task asks for something and keeps it: a consumeItems of true on the task");

        // And the chapter-level version of the same field, which is the inheritance the field exists
        // for -- plus the task that declines it, because a default nothing ever overrides reads as a
        // fact rather than as a default.
        assertTrue(index.chapters().stream().map(QuestIndex.ChapterEntry::chapter)
                        .anyMatch(Chapter::defaultConsumeItems),
                "no example chapter sets defaultConsumeItems, so the inheritance is never demonstrated");
        assertTrue(all.stream().anyMatch(quest -> quest.tasks().stream().anyMatch(task ->
                        task instanceof ItemTask item && item.consumeItems().orElse(true) == false)),
                "no example task declines its chapter's consume default, so the exception -- the whole"
                        + " point of a default -- is missing");

        // The prerequisite modes, all four of them plus the count that none of them can express:
        // a quest that overrides the mode on itself, and a chapter that changes the default for
        // everything in it.
        assertTrue(all.stream().anyMatch(quest -> quest.prerequisiteMode().isPresent()),
                "no example quest states a prerequisiteMode of its own");
        assertTrue(index.chapters().stream().map(QuestIndex.ChapterEntry::chapter).anyMatch(
                        chapter -> chapter.defaultPrerequisiteMode() != PrerequisiteMode.ALL_COMPLETED),
                "every example chapter keeps the default prerequisite mode, so a chapter's own rule is"
                        + " never seen");
        for (PrerequisiteMode mode : PrerequisiteMode.values()) {
            // The *effective* mode, which is what a player experiences: a quest may state one, or may
            // inherit the chapter's. Both are demonstrations, and ONE_STARTED is deliberately the
            // inherited kind -- the chapter rule is the file whose whole point is that it says nothing.
            assertTrue(index.quests().stream().anyMatch(entry -> !entry.quest().dependencies().isEmpty()
                            && entry.quest().prerequisiteMode(entry.chapter().defaultPrerequisiteMode())
                            == mode),
                    "no example quest waits on anything under the " + mode + " rule");
        }

        // Aliases, at all three levels, and the one thing that makes them more than decoration: a
        // dependency written against an alias rather than against an id.
        Set<String> ids = all.stream().map(Quest::id).collect(Collectors.toSet());
        Set<String> aliases = all.stream().flatMap(quest -> quest.aliases().stream())
                .collect(Collectors.toSet());
        assertTrue(!aliases.isEmpty(), "no example quest declares an alias");
        assertTrue(all.stream().anyMatch(quest -> quest.dependencies().stream()
                        .anyMatch(dep -> !ids.contains(dep.id()) && aliases.contains(dep.id()))),
                "no example dependency is written against an alias, so nothing shows that a second"
                        + " name resolves everywhere a first one does");
        assertTrue(index.chapters().stream().anyMatch(entry -> !entry.chapter().aliases().isEmpty()),
                "no example chapter declares an alias");
        assertTrue(index.groups().stream().anyMatch(entry -> !entry.group().aliases().isEmpty()),
                "no example chapter group declares an alias");

        // The two sides of the folder-tree format that are otherwise invisible: a group collapsed by
        // default, and a chapter dressed in a theme of its own. With one chapter the theme is the
        // default palette plus a themePatch -- the chapter still names its palette rather than
        // leaving it to the player's, which is the field being read.
        assertTrue(index.groups().stream().anyMatch(entry -> entry.group().collapsedByDefault()),
                "no example group is collapsed by default, so the field that decides what a sidebar"
                        + " looks like on first sight is only ever read by its own test");
        assertTrue(index.chapters().stream().anyMatch(entry -> entry.chapter().theme().isPresent()),
                "no example chapter names a theme, so the field is only ever read by its own test");

        // Text written as a translation key with a fallback -- the other spelling a text field takes,
        // and the one a pack that wants its questline translated needs.
        assertTrue(all.stream().anyMatch(quest -> quest.title().translatable()
                        && quest.title().fallback().isPresent()),
                "no example text is written as a translation key with an English fallback");

        // And the two numeric fields that are dull until they are extreme: a task checked on a long
        // timer, and a count big enough that the client draws a progress bar rather than a number.
        assertTrue(all.stream().flatMap(quest -> quest.tasks().stream())
                        .anyMatch(task -> task.common().autoSubmitTicks() != 20),
                "no example task changes autoSubmitTicks, so the field that keeps an expensive check"
                        + " from running twenty times a second has no worked example");
        assertTrue(all.stream().flatMap(quest -> quest.tasks().stream()).anyMatch(task ->
                        task instanceof ItemTask item && item.item().count() >= 256),
                "no example asks for a count in the hundreds, which is the size at which the progress"
                        + " bar is the point");

        // A turned node, because the rotation is a field with no worked example otherwise -- and a
        // mechanism with no example is the thing nobody can see how to write.
        assertTrue(all.stream().anyMatch(quest -> quest.layout().rotation() != 0),
                "no example turns a node, so the rotation field has nothing to read it from");

        // Every shape, because the shapes are the thing a still cannot show the difference between
        // unless the content actually varies -- which was the defect this project already had once.
        for (QuestShape shape : QuestShape.values()) {
            assertTrue(all.stream().anyMatch(quest -> quest.layout().shape() == shape),
                    "no example quest uses the " + shape + " shape, so nothing exercises it");
        }

        // The size and icon-scale ranges at the ends the exhibition actually shows: a 16-pixel speck,
        // and a node of 200 pixels or more. The published range runs to 512 pixels and an icon of 0.25,
        // and neither is exhibited on purpose -- a node that size is a wall rather than an example. What
        // is asserted is what is drawn, so the claim and the content cannot disagree.
        assertTrue(all.stream().anyMatch(quest -> quest.layout().size() <= 16),
                "no example node is drawn at the smallest size there is");
        assertTrue(all.stream().anyMatch(quest -> quest.layout().size() >= 200),
                "no example node is a landmark, so a large size is never seen");
        assertTrue(all.stream().anyMatch(quest -> quest.layout().iconScale() >= 1.0),
                "no example icon fills its node corner to corner");
        assertTrue(all.stream().anyMatch(quest -> quest.layout().iconScale() <= 0.5),
                "no example icon sits small inside a large node");

        // The visibility family, flag by flag. Each of these changes what a player can see, and a flag
        // with no example is a flag nobody can look up how to write -- which for this family is
        // especially expensive, because the behaviour it changes is invisible by definition. The veils
        // arm of the orrery is the part of the chapter written to hold one of each.
        assertTrue(all.stream().anyMatch(quest -> quest.rules().maxCompletableDependents() > 0),
                "no example caps its dependents, so the branch point that closes roads has no file");
        assertTrue(all.stream().anyMatch(quest -> quest.rules().invisible()
                        && quest.rules().invisibleUntilTasks() > 0),
                "no example is an easter egg: `invisible` with `invisibleUntilTasks` is the one way a "
                        + "quest can appear on the canvas with nothing pointing at it");
        assertTrue(all.stream().anyMatch(quest -> quest.rules().hideUntilDependenciesComplete()
                        .orElse(false)),
                "no example is hidden until its prerequisite rule is met");
        assertTrue(all.stream().anyMatch(quest -> quest.rules().hideUntilDependenciesVisible()
                        .orElse(false)),
                "no example is hidden until a prerequisite is visible, so the recursive reveal -- the "
                        + "one rule in the family that cannot be read off a single file -- has no example");
        assertTrue(all.stream().anyMatch(quest -> quest.rules().hideDependencyLines()),
                "no example hides its dependency lines");
        assertTrue(all.stream().anyMatch(quest -> quest.rules().hideTextUntilComplete()),
                "no example withholds its description until it is completed");
        assertTrue(all.stream().anyMatch(quest -> quest.rules().hideDetailsUntilStartable()),
                "no example withholds its details until it can be started");

        // Three assertions used to live here, and they are gone because one chapter cannot make them
        // true -- which is the price of the collapse, stated rather than hidden:
        //
        //   - a large LINEAR chapter, whose list order is the progression. A chapter is linear or
        //     flexible and not both, and linear would deadlock the exclusive pairs and OR-gates this
        //     same test insists on, so the worked examples are flexible. LINEAR remains a documented
        //     mode with its own engine tests; it is no longer part of the exhibition.
        //   - a dependency that leaves its own chapter group. That needs a second group, and the
        //     whole point of this round was one.
        //   - examples in two sizes, one small and one large. One chapter is one size.
        //
        // The line that replaces them is the one claim that is still checkable: the single chapter is
        // large enough to scroll.
        assertTrue(all.size() >= 40,
                "the example chapter is no longer the large end of what a chapter can be: "
                        + all.size() + " quest(s)");

        // And the two defaults, by their absence: most quests draw no name and take no items, which is
        // what makes the exceptions in the files mean something.
        long named = all.stream().filter(Quest::showTitle).count();
        assertTrue(named < all.size() / 2,
                "most example quests should NOT draw their name -- that is the default, and a file where"
                        + " every quest opts in is not demonstrating anything");

        // ------------------------------------------------------------------
        // The second exhibition: the mechanisms the rewrite was for.
        //
        // The battery above grew one assertion at a time, whenever a mechanism turned out to have no
        // worked example. This block is the same idea applied in one pass when the examples were
        // rewritten: every task, reward and condition type, every value of every line-art axis, and
        // the fields the first round of examples never touched -- matching, kill filters, the
        // auto-claim ladder, the payout flags, theme patches. A mechanism with no example is the
        // mechanism nobody can see how to write; this is the version a compiler reads.
        // ------------------------------------------------------------------

        // Every task type with a worked example. Energy and filter have registered types but no
        // example yet (see tools/README.md's stated gap); everything else here must appear in
        // the orrery, or nothing exercises it.
        Class<?>[] taskTypes = {
                ItemTask.class, ItemTagTask.class, CheckmarkTask.class, CustomTask.class,
                DimensionTask.class, FluidTask.class, KillTask.class, BreakTask.class, LocationTask.class,
                ObservationTask.class, StageTask.class, StatTask.class, StructureTask.class,
                AdvancementTask.class, BiomeTask.class, XpTask.class };
        for (Class<?> type : taskTypes) {
            assertTrue(all.stream().anyMatch(quest -> quest.tasks().stream().anyMatch(type::isInstance)),
                    "no example uses the " + type.getSimpleName() + " task, so nothing exercises it");
        }

        // Every reward type: the simple ones by class, and the table's four modes.
        Class<?>[] rewardTypes = {
                ItemReward.class, XpReward.class, TableReward.class, CommandReward.class,
                AdvancementReward.class, StageReward.class, CustomReward.class };
        for (Class<?> type : rewardTypes) {
            assertTrue(all.stream().anyMatch(quest -> quest.rewards().stream().anyMatch(type::isInstance)),
                    "no example uses the " + type.getSimpleName() + " reward, so nothing exercises it");
        }
        for (TableReward.Mode mode : TableReward.Mode.values()) {
            assertTrue(all.stream().flatMap(quest -> quest.rewards().stream())
                            .anyMatch(reward -> reward instanceof TableReward table && table.mode() == mode),
                    "no example uses the " + mode + " table reward, so nothing exercises it");
        }
        // And both ways a table can arrive: by name and inline.
        assertTrue(all.stream().flatMap(quest -> quest.rewards().stream())
                        .anyMatch(reward -> reward instanceof TableReward table && table.table().isPresent()),
                "no example rolls a named reward table, so reward_tables/ has no reader");
        assertTrue(all.stream().flatMap(quest -> quest.rewards().stream())
                        .anyMatch(reward -> reward instanceof TableReward table && table.inline().isPresent()),
                "no example carries an inline table, so the other spelling is never seen");

        // Every condition type, all six, across tasks and rewards together.
        List<dev.ellipog.tenet.quest.condition.QuestCondition> conditions = new ArrayList<>();
        all.stream().flatMap(quest -> quest.tasks().stream())
                .forEach(task -> conditions.addAll(task.common().conditions()));
        all.stream().flatMap(quest -> quest.rewards().stream())
                .forEach(reward -> conditions.addAll(reward.common().conditions()));
        Class<?>[] conditionTypes = {
                ItemCondition.class, ItemTagCondition.class, ScoreCondition.class,
                AdvancementCondition.class, StageCondition.class, PartySizeCondition.class };
        for (Class<?> type : conditionTypes) {
            assertTrue(conditions.stream().anyMatch(type::isInstance),
                    "no example uses the " + type.getSimpleName() + " condition, so nothing exercises it");
        }

        // The item-matching family: fuzzy, strict, a components filter, and crafted-only.
        assertTrue(all.stream().flatMap(quest -> quest.tasks().stream())
                        .anyMatch(task -> task instanceof ItemTask item && item.match() == ComponentMatch.FUZZY),
                "no example matches an item fuzzily, so the reading that forgives the rest of the stack"
                        + " is never seen");
        assertTrue(all.stream().flatMap(quest -> quest.tasks().stream())
                        .anyMatch(task -> task instanceof ItemTask item && item.match() == ComponentMatch.STRICT),
                "no example matches an item strictly, so the whole-stack reading is never seen");
        assertTrue(all.stream().flatMap(quest -> quest.tasks().stream())
                        .anyMatch(task -> task instanceof ItemTask item
                                && item.item().components() != null
                                && item.item().components() != DataComponentPatch.EMPTY),
                "no example filters on data components, so the renamed-item spelling is never seen");
        assertTrue(all.stream().flatMap(quest -> quest.tasks().stream())
                        .anyMatch(task -> task instanceof ItemTask item && item.onlyFromCrafting()),
                "no example counts only crafted items, so onlyFromCrafting is never seen");

        // The kill filters, one each: a name, an SNBT filter, and a tag.
        assertTrue(all.stream().flatMap(quest -> quest.tasks().stream())
                        .anyMatch(task -> task instanceof KillTask kill && kill.customName().isPresent()),
                "no example kills by custom name, so the field that reaches what an id cannot is never seen");
        assertTrue(all.stream().flatMap(quest -> quest.tasks().stream())
                        .anyMatch(task -> task instanceof KillTask kill && kill.nbtFilter().isPresent()),
                "no example kills by SNBT filter");
        assertTrue(all.stream().flatMap(quest -> quest.tasks().stream())
                        .anyMatch(task -> task instanceof KillTask kill && kill.entityTypeTag().isPresent()),
                "no example kills by entity tag");

        // The break filters, one each: an id, a tag, and a state.
        assertTrue(all.stream().flatMap(quest -> quest.tasks().stream())
                        .anyMatch(task -> task instanceof BreakTask broken && broken.block().isPresent()),
                "no example breaks by block id");
        assertTrue(all.stream().flatMap(quest -> quest.tasks().stream())
                        .anyMatch(task -> task instanceof BreakTask broken && broken.blockTag().isPresent()),
                "no example breaks by block tag");
        assertTrue(all.stream().flatMap(quest -> quest.tasks().stream())
                        .anyMatch(task -> task instanceof BreakTask broken && broken.blockState().isPresent()),
                "no example breaks by block state");

        // A location box that ignores its dimension, and an observation with intent.
        assertTrue(all.stream().flatMap(quest -> quest.tasks().stream())
                        .anyMatch(task -> task instanceof LocationTask box && box.ignoreDimension()),
                "no example writes a location box with ignoreDimension, so the field's one honest use in"
                        + " an example is missing");
        assertTrue(all.stream().flatMap(quest -> quest.tasks().stream())
                        .anyMatch(task -> task instanceof ObservationTask look
                                && look.observeType() == ObservationTask.ObserveType.BLOCK_ENTITY),
                "no example observes a block entity");
        assertTrue(all.stream().flatMap(quest -> quest.tasks().stream())
                        .anyMatch(task -> task instanceof ObservationTask look && look.timer() != 20),
                "no example changes an observation's timer, so the field that says how long a look takes"
                        + " is never seen");

        // The auto-claim ladder: a chapter that turns it on, a quest that opts back out, and rewards
        // that pay more quietly than either asks.
        assertTrue(index.chapters().stream().map(QuestIndex.ChapterEntry::chapter)
                        .anyMatch(chapter -> chapter.autoClaim() == RewardAutoClaim.ENABLED),
                "no example chapter turns auto-claim on, so the fifty-claim-click problem has no worked"
                        + " answer");
        assertTrue(all.stream().anyMatch(quest -> quest.rules().autoClaim().isPresent()
                        && quest.rules().autoClaim().get() == RewardAutoClaim.DISABLED),
                "no example quest opts back out of its chapter's auto-claim, so the middle rung of the"
                        + " ladder is never demonstrated");
        List<RewardCommon> rewardCommons = all.stream()
                .flatMap(quest -> quest.rewards().stream()).map(QuestReward::common).toList();
        assertTrue(rewardCommons.stream().anyMatch(common -> common.auto() == RewardAutoClaim.NO_TOAST),
                "no example reward pays without a toast");
        assertTrue(rewardCommons.stream().anyMatch(common -> common.auto() == RewardAutoClaim.INVISIBLE),
                "no example reward pays invisibly");

        // The payout flags, one each: once per team, held back from Claim all, past a payout block,
        // a random bonus, skipped while carried, a silent command, and a stage taken back.
        assertTrue(rewardCommons.stream().anyMatch(common -> common.team().orElse(false)),
                "no example reward is claimed once for the team, so team: true is never seen");
        assertTrue(rewardCommons.stream().anyMatch(RewardCommon::excludeFromClaimAll),
                "no example reward is held back from Claim all");
        assertTrue(rewardCommons.stream().anyMatch(RewardCommon::ignoreRewardBlocking),
                "no example reward ignores a payout block, so the hold's one exception is never seen");
        assertTrue(all.stream().flatMap(quest -> quest.rewards().stream())
                        .anyMatch(reward -> reward instanceof ItemReward item && item.randomBonus() > 0),
                "no example reward rolls a random bonus on top of its count");
        assertTrue(all.stream().flatMap(quest -> quest.rewards().stream())
                        .anyMatch(reward -> reward instanceof ItemReward item && item.onlyOne()),
                "no example reward is skipped while the item is carried");
        assertTrue(all.stream().flatMap(quest -> quest.rewards().stream())
                        .anyMatch(reward -> reward instanceof CommandReward command && command.silent()),
                "no example command reward runs silently");
        assertTrue(all.stream().flatMap(quest -> quest.rewards().stream())
                        .anyMatch(reward -> reward instanceof StageReward stage && stage.remove()),
                "no example stage reward removes the stage it names, so the flag's write-back is never"
                        + " seen");

        // The line-art matrix: a chapter default, per-line overrides, and every value of every axis.
        assertTrue(index.chapters().stream().map(QuestIndex.ChapterEntry::chapter)
                        .anyMatch(chapter -> chapter.dependencyStyle() != DependencyStyle.UNSET),
                "no example chapter sets a dependencyStyle of its own, so the chapter default is never"
                        + " demonstrated");
        assertTrue(all.stream().anyMatch(quest -> !quest.dependencyLines().isEmpty()),
                "no example overrides a single dependency line, so per-line keys are never seen");
        List<DependencyStyle> lines = new ArrayList<>();
        index.chapters().stream().map(QuestIndex.ChapterEntry::chapter)
                .forEach(chapter -> lines.add(chapter.dependencyStyle()));
        all.stream().forEach(quest -> lines.addAll(quest.dependencyLines().values()));
        for (DependencyStyle.Form form : DependencyStyle.Form.values()) {
            assertTrue(lines.stream().anyMatch(line -> line.form().orElse(null) == form),
                    "no example line uses the " + form + " form, so nothing exercises it");
        }
        for (DependencyStyle.Dash dash : DependencyStyle.Dash.values()) {
            assertTrue(lines.stream().anyMatch(line -> line.dash().orElse(null) == dash),
                    "no example line uses the " + dash + " dash, so nothing exercises it");
        }
        for (DependencyStyle.Weight weight : DependencyStyle.Weight.values()) {
            assertTrue(lines.stream().anyMatch(line -> line.weight().orElse(null) == weight),
                    "no example line uses the " + weight + " weight, so nothing exercises it");
        }
        for (DependencyStyle.ArrowHead head : DependencyStyle.ArrowHead.values()) {
            assertTrue(lines.stream().anyMatch(line -> line.arrowHead().orElse(null) == head),
                    "no example line uses the " + head + " arrowhead, so nothing exercises it");
        }
        for (DependencyStyle.ArrowPlace place : DependencyStyle.ArrowPlace.values()) {
            assertTrue(lines.stream().anyMatch(line -> line.arrowPlace().orElse(null) == place),
                    "no example line places its heads with " + place + ", so nothing exercises it");
        }
        for (DependencyStyle.ArrowDensity density : DependencyStyle.ArrowDensity.values()) {
            assertTrue(lines.stream().anyMatch(line -> line.arrowDensity().orElse(null) == density),
                    "no example line runs a stream at " + density + " density, so nothing exercises it");
        }
        assertTrue(lines.stream().anyMatch(line -> line.bend().orElse(0.0) <= -0.8)
                        && lines.stream().anyMatch(line -> line.bend().orElse(0.0) >= 0.8),
                "no example line bends to either extreme, so the range of the axis is never seen");
        assertTrue(lines.stream().anyMatch(line -> line.fromAnchor().isPresent())
                        && lines.stream().anyMatch(line -> line.toAnchor().isPresent()),
                "no example line anchors its ends, so the per-line-only axis is never seen");
        assertTrue(lines.stream().anyMatch(line -> line.fromHandle().isPresent())
                        && lines.stream().anyMatch(line -> line.toHandle().isPresent()),
                "no example line carries split handles, so the control points are never seen");

        // A chapter palette with opinions of its own.
        assertTrue(index.chapters().stream().map(QuestIndex.ChapterEntry::chapter)
                        .anyMatch(chapter -> chapter.themePatch().isPresent()),
                "no example chapter carries a themePatch, so the token-level override is never seen");
    }

    @Test
    @DisplayName("every score gate in the examples has its objective created and set by the examples")
    void scoreGatesHaveTheirInput() throws java.io.IOException {
        // The defect this exists for: the festival's reward is gated on a scoreboard objective, and the
        // example that claimed to open the gate only ever created the objective. `objectives add` sets
        // nobody's score, and a missing objective reads as zero exactly like an objective nobody has
        // set -- so the gate was born shut and stayed shut, and no test could see it because the
        // playthrough exercises a different objective that the test itself sets.
        //
        // Generalised rather than pinned to that one quest: any score condition the exhibition grows
        // needs both halves somewhere in the same tree, because neither the loader nor the engine can
        // invent a score.
        QuestLoader.Result loaded = loadExamples(temp.resolve("score_gates"));
        assertTrue(loaded.ok(), "the examples should have nothing fatal in them, but reported:"
                + messages(loaded.problems()));
        QuestIndex index = loaded.index();
        List<Quest> all = index.quests().stream().map(QuestIndex.QuestEntry::quest).toList();

        List<String> commands = all.stream()
                .flatMap(quest -> quest.rewards().stream())
                .filter(CommandReward.class::isInstance)
                .map(CommandReward.class::cast)
                .map(CommandReward::command)
                .toList();

        List<String> gated = new ArrayList<>();
        for (Quest quest : all) {
            for (QuestReward reward : quest.rewards()) {
                for (var condition : reward.common().conditions()) {
                    if (condition instanceof ScoreCondition score) {
                        gated.add(quest.id() + " -> " + score.objective());
                        assertTrue(commands.stream().anyMatch(command ->
                                        command.startsWith("scoreboard objectives add " + score.objective())),
                                "nothing in the examples creates the \"" + score.objective()
                                        + "\" objective that " + quest.id() + " is gated on, so the gate "
                                        + "can never open");
                        assertTrue(commands.stream().anyMatch(command ->
                                        command.contains("scoreboard players set ")
                                                && command.endsWith(" " + score.objective() + " 1")),
                                "nothing in the examples sets anybody's score on \"" + score.objective()
                                        + "\", which " + quest.id() + " is gated on: an objective that "
                                        + "nobody has set reads as zero, exactly like one that does not "
                                        + "exist, so the gate is born shut and stays shut");
                    }
                }
            }
        }
        assertFalse(gated.isEmpty(),
                "no example carries a score condition, so this rule is not being tested by anything");
    }

    @Test
    @DisplayName("every theme a shipped example names is one this build actually has")
    void exampleThemesExist() throws java.io.IOException {
        // The failure this catches is the quietest one in the whole theme feature, and it is worth
        // being explicit about why it needs a test at all.
        //
        // A chapter naming a theme that does not exist is *handled*: the client logs a line naming the
        // chapter and the name, and carries on with the player's own theme. Handled well, in fact --
        // refusing to open the chapter would be far worse. But handled silently from the author's
        // side, and the shipped examples are the one place where a typo would reach the player before
        // it reached anyone who could fix it.
        //
        // So every chapter's `theme` field is checked against the built-ins here rather than discovered
        // in a log. It is the same reasoning as the shape field that was parsed, validated and printed
        // by a command while nothing drew it: a value that only ever produces a warning at runtime is
        // a value that is wrong for a while before anyone notices.
        //
        // Read from the tools directory rather than from a resource, because that is where the
        // examples live -- they are documentation, and the mod deliberately ships no quests.
        Set<String> builtIn = dev.ellipog.armature.client.ui.Themes.ALL.stream()
                .map(dev.ellipog.armature.client.ui.Theme::name)
                .collect(Collectors.toCollection(LinkedHashSet::new));

        QuestIndex index = loadExamples(temp.resolve("themes")).index();

        // Every chapter in every example, rather than a hand-written list of file names. That list was
        // a thing that stops matching the content — and it did, the moment the examples became
        // folders: there was no `04_theme_gallery.json` to name any more. Reading the index means a
        // new example is covered the day it is added.
        //
        // `entry.file()` rather than the old flat name, so the message names the file that actually
        // holds the mistake — a `chapter.json` inside its chapter folder rather than a flat file that
        // contains fifteen chapters and no longer exists.
        for (QuestIndex.ChapterEntry entry : index.chapters()) {
            Chapter chapter = entry.chapter();
            if (chapter.theme().isEmpty()) {
                continue;
            }
            assertTrue(builtIn.contains(chapter.theme().get()),
                    entry.file() + "'s chapter '" + chapter.id() + "' asks for a theme called '"
                            + chapter.theme().get() + "', which this build does not have. It has: "
                            + builtIn);
        }
    }
}
