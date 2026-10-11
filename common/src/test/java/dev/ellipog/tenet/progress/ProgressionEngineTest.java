package dev.ellipog.tenet.progress;

import dev.ellipog.tenet.quest.Fixtures;
import dev.ellipog.tenet.quest.Quest;
import dev.ellipog.tenet.quest.QuestIndex;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;

import static dev.ellipog.tenet.quest.Fixtures.q;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The progression rules.
 *
 * <h2>Why these are worth testing rather than playing</h2>
 *
 * <p>A quest that refuses to unlock looks the same whether the cause is a prerequisite mode, a
 * mistyped id, a cycle, or a repeatable quest still on cooldown. Playing finds the symptom; only a
 * test finds which rule produced it. And these are pure functions — quests in, state out — so
 * there is no reason to need a game to check them.
 *
 * <p>Everything here goes through the public API: build a quest index, hand the engine some
 * progress and a clock, read the states back. No reflection, no reaching inside.
 *
 * <h2>A naming rule for this file</h2>
 *
 * <p>The static helpers below are named <b>indexOf</b>, <b>completedQuests</b> and
 * <b>startedQuests</b> rather than the obvious {@code index}, {@code completed} and
 * {@code started}. That is not decoration: a method in an enclosing class is shadowed by any
 * method of the same name in a {@code @Nested} class, even one with a completely different
 * signature. A test called {@code started()} therefore silently redirected every
 * {@code started(index, "a")} call in that class to itself, and the compiler reported it as
 * "required: no arguments, found: QuestIndex,String" — which reads like a mistake at the call
 * site and is not. Naming the helpers unambiguously means no future test can reintroduce it.
 */
@DisplayName("ProgressionEngine")
class ProgressionEngineTest {

    /** An arbitrary game time. Cooldown tests add to it. */
    private static final long NOW = 10_000L;

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    private static QuestIndex indexOf(String... quests) {
        return Fixtures.indexOf(Fixtures.file(quests));
    }

    private static QuestIndex indexWithChapter(String chapterExtras, String... quests) {
        return Fixtures.indexOf(Fixtures.fileWithChapter(chapterExtras, quests));
    }

    private static QuestState stateOf(QuestIndex index, TeamProgress progress, String id) {
        return ProgressionEngine.resolve(index, progress, NOW).stateOf(Fixtures.quest(index, id));
    }

    /** An index of chapters built by {@code Fixtures.chapterWith}, rather than of one chapter. */
    private static QuestIndex indexWithChapters(String... chapters) {
        return Fixtures.indexOf(Fixtures.fileWithChapters(chapters));
    }

    private static QuestState chapterStateOf(QuestIndex index, TeamProgress progress, String chapterId) {
        return ProgressionEngine.resolve(index, progress, NOW).chapterStateOf(chapterId);
    }

    /** Progress where the named quests have been finished. */
    private static TeamProgress completedQuests(QuestIndex index, String... ids) {
        TeamProgress progress = TeamProgress.empty();
        for (String id : ids) {
            progress = progress.put(Fixtures.quest(index, id),
                    QuestProgress.NONE.completedAt(NOW).withRewardsClaimed(true));
        }
        return progress;
    }

    /**
     * Progress where the named quests have been started but not finished.
     *
     * <p>Recorded as task progress rather than a state, because that is how the engine decides
     * between UNLOCKED and STARTED — the stored state field is not consulted for that.
     */
    private static TeamProgress startedQuests(QuestIndex index, String... ids) {
        TeamProgress progress = TeamProgress.empty();
        for (String id : ids) {
            progress = progress.put(Fixtures.quest(index, id), QuestProgress.NONE.recordTask(0, 1));
        }
        return progress;
    }

    // ------------------------------------------------------------------
    // Dependencies
    // ------------------------------------------------------------------

    @Nested
    @DisplayName("with no dependencies")
    class NoDependencies {

        @Test
        @DisplayName("a quest is unlocked straight away")
        void unlocked() {
            QuestIndex index = indexOf(q("a").build());
            assertEquals(QuestState.UNLOCKED, stateOf(index, TeamProgress.empty(), "a"));
        }

        @Test
        @DisplayName("and STARTED, not merely unlocked, once a task has progress against it")
        void startedOnceATaskHasProgress() {
            QuestIndex index = indexOf(q("a").build());
            assertEquals(QuestState.STARTED, stateOf(index, startedQuests(index, "a"), "a"));
        }
    }

    @Nested
    @DisplayName("in the default ALL_COMPLETED mode")
    class AllCompleted {

        @Test
        @DisplayName("an unmet dependency keeps the quest locked")
        void lockedBeforeDependency() {
            QuestIndex index = indexOf(q("a").build(), q("b").dependsOn("a").build());
            assertEquals(QuestState.LOCKED, stateOf(index, TeamProgress.empty(), "b"));
        }

        @Test
        @DisplayName("completing it unlocks the dependent")
        void unlockedAfterDependency() {
            QuestIndex index = indexOf(q("a").build(), q("b").dependsOn("a").build());
            assertEquals(QuestState.UNLOCKED, stateOf(index, completedQuests(index, "a"), "b"));
        }

        @Test
        @DisplayName("with two dependencies, one of them is not enough")
        void needsEveryDependency() {
            QuestIndex index = indexOf(q("a").build(), q("b").build(), q("c").dependsOn("a", "b").build());

            assertEquals(QuestState.LOCKED, stateOf(index, completedQuests(index, "a"), "c"));
            assertEquals(QuestState.LOCKED, stateOf(index, completedQuests(index, "b"), "c"));
            assertEquals(QuestState.UNLOCKED, stateOf(index, completedQuests(index, "a", "b"), "c"));
        }

        @Test
        @DisplayName("a dependency that does not exist leaves it locked rather than crashing")
        void missingDependency() {
            // The loader reports this as an error at load time. Locking is the safe reading: a
            // quest that cannot be unlocked is visibly broken, whereas one that unlocks for the
            // wrong reason is not.
            QuestIndex index = indexOf(q("a").dependsOn("nowhere").build());
            assertEquals(QuestState.LOCKED, stateOf(index, TeamProgress.empty(), "a"));
        }
    }

    @Nested
    @DisplayName("in ONE_COMPLETED mode")
    class OneCompleted {

        private QuestIndex paths() {
            return indexOf(q("a").build(), q("b").build(),
                    q("c").dependsOn("a", "b").prerequisiteMode("one_completed").build());
        }

        @Test
        @DisplayName("either dependency alone unlocks it")
        void oneIsEnough() {
            QuestIndex index = paths();
            assertEquals(QuestState.UNLOCKED, stateOf(index, completedQuests(index, "a"), "c"));
            assertEquals(QuestState.UNLOCKED, stateOf(index, completedQuests(index, "b"), "c"));
        }

        @Test
        @DisplayName("neither done leaves it locked")
        void noneIsNotEnough() {
            QuestIndex index = paths();
            assertEquals(QuestState.LOCKED, stateOf(index, TeamProgress.empty(), "c"));
        }
    }

    @Nested
    @DisplayName("in ALL_STARTED mode")
    class AllStarted {

        @Test
        @DisplayName("a dependency that is merely unlocked does not count")
        void unlockedIsNotStarted() {
            QuestIndex index = indexOf(q("a").build(),
                    q("b").dependsOn("a").prerequisiteMode("all_started").build());
            assertEquals(QuestState.LOCKED, stateOf(index, TeamProgress.empty(), "b"));
        }

        @Test
        @DisplayName("one with task progress counts, without being complete")
        void taskProgressCounts() {
            QuestIndex index = indexOf(q("a").build(),
                    q("b").dependsOn("a").prerequisiteMode("all_started").build());
            assertEquals(QuestState.UNLOCKED, stateOf(index, startedQuests(index, "a"), "b"));
        }

        @Test
        @DisplayName("and a completed dependency counts too")
        void completedCounts() {
            QuestIndex index = indexOf(q("a").build(),
                    q("b").dependsOn("a").prerequisiteMode("all_started").build());
            assertEquals(QuestState.UNLOCKED, stateOf(index, completedQuests(index, "a"), "b"));
        }
    }

    @Nested
    @DisplayName("with minRequired")
    class MinRequired {

        @Test
        @DisplayName("any two of three will do, in any combination")
        void countsInAnyOrder() {
            QuestIndex index = indexOf(q("a").build(), q("b").build(), q("c").build(),
                    q("d").dependsOn("a", "b", "c").minRequired(2).build());

            assertEquals(QuestState.LOCKED, stateOf(index, TeamProgress.empty(), "d"));
            assertEquals(QuestState.LOCKED, stateOf(index, completedQuests(index, "a"), "d"));
            assertEquals(QuestState.UNLOCKED, stateOf(index, completedQuests(index, "a", "c"), "d"));
            assertEquals(QuestState.UNLOCKED, stateOf(index, completedQuests(index, "b", "c"), "d"));
            assertEquals(QuestState.UNLOCKED, stateOf(index, completedQuests(index, "a", "b", "c"), "d"));
        }

        @Test
        @DisplayName("it overrides the mode rather than combining with it")
        void overridesMode() {
            // ALL_COMPLETED would demand all three; minRequired says two. Two should win, since
            // that is the whole reason the field exists.
            QuestIndex index = indexOf(q("a").build(), q("b").build(), q("c").build(),
                    q("d").dependsOn("a", "b", "c").minRequired(2)
                            .prerequisiteMode("all_completed").build());
            assertEquals(QuestState.UNLOCKED, stateOf(index, completedQuests(index, "a", "b"), "d"));
        }

        @Test
        @DisplayName("a count larger than the dependency list still unlocks when all are done")
        void clampedToDependencyCount() {
            // The validator flags this as an error, because such a quest can never unlock. The
            // engine clamps rather than refusing to resolve, so a bad file still loads and every
            // other quest in it still works.
            QuestIndex index = indexOf(q("a").build(), q("b").build(),
                    q("c").dependsOn("a").minRequired(5).build());
            assertEquals(QuestState.UNLOCKED, stateOf(index, completedQuests(index, "a"), "c"));
        }
    }

    @Nested
    @DisplayName("with optional dependencies")
    class OptionalQuests {

        @Test
        @DisplayName("an unmet optional dependency locks, under ALL_COMPLETED")
        void unmetOptionalLocks() {
            // legacy parity: optional only excuses a quest from chapter completion, never from a
            // gate. A pack gating on a side quest means it, so the edge gates like any other.
            QuestIndex index = indexOf(q("side").optional(true).build(),
                    q("d").dependsOn("side").build());

            assertEquals(QuestState.LOCKED, stateOf(index, TeamProgress.empty(), "d"));
            assertEquals(QuestState.UNLOCKED, stateOf(index, completedQuests(index, "side"), "d"));
        }

        @Test
        @DisplayName("a completed optional dependency satisfies, under ONE_COMPLETED")
        void completedOptionalSatisfies() {
            QuestIndex index = indexOf(q("side").optional(true).build(), q("main").build(),
                    q("d").dependsOn("side", "main").prerequisiteMode("one_completed").build());

            assertEquals(QuestState.LOCKED, stateOf(index, TeamProgress.empty(), "d"));
            assertEquals(QuestState.UNLOCKED, stateOf(index, completedQuests(index, "side"), "d"));
            assertEquals(QuestState.UNLOCKED, stateOf(index, completedQuests(index, "main"), "d"));
            assertEquals(QuestState.UNLOCKED,
                    stateOf(index, completedQuests(index, "main", "side"), "d"));
        }

        @Test
        @DisplayName("every gate still gates, under ALL_COMPLETED with a mix")
        void everyGateStillGates() {
            QuestIndex index = indexOf(q("side").optional(true).build(), q("main").build(),
                    q("d").dependsOn("side", "main").build());

            assertEquals(QuestState.LOCKED, stateOf(index, TeamProgress.empty(), "d"));
            assertEquals(QuestState.LOCKED, stateOf(index, completedQuests(index, "side"), "d"));
            assertEquals(QuestState.LOCKED, stateOf(index, completedQuests(index, "main"), "d"));
            assertEquals(QuestState.UNLOCKED,
                    stateOf(index, completedQuests(index, "side", "main"), "d"));
        }

        @Test
        @DisplayName("the started-based modes count optionals the same way")
        void startedModesCountOptionals() {
            QuestIndex index = indexOf(q("side").optional(true).build(), q("main").build(),
                    q("d").dependsOn("side", "main").prerequisiteMode("all_started").build());

            assertEquals(QuestState.LOCKED, stateOf(index, TeamProgress.empty(), "d"));
            assertEquals(QuestState.LOCKED, stateOf(index, startedQuests(index, "side"), "d"));
            assertEquals(QuestState.UNLOCKED, stateOf(index, startedQuests(index, "side", "main"), "d"));

            QuestIndex one = indexOf(q("side").optional(true).build(), q("main").build(),
                    q("d").dependsOn("side", "main").prerequisiteMode("one_started").build());
            assertEquals(QuestState.LOCKED, stateOf(one, TeamProgress.empty(), "d"));
            assertEquals(QuestState.UNLOCKED, stateOf(one, startedQuests(one, "side"), "d"));
            assertEquals(QuestState.UNLOCKED, stateOf(one, startedQuests(one, "main"), "d"));
        }

        @Test
        @DisplayName("minRequired counts every dependency")
        void minRequiredCountsEveryEdge() {
            QuestIndex index = indexOf(q("a").build(), q("b").build(), q("side").optional(true).build(),
                    q("d").dependsOn("a", "b", "side").minRequired(2).build());

            assertEquals(QuestState.LOCKED, stateOf(index, completedQuests(index, "a"), "d"));
            assertEquals(QuestState.UNLOCKED, stateOf(index, completedQuests(index, "a", "b"), "d"));
            // Two met with the optional one among them is two met.
            assertEquals(QuestState.UNLOCKED, stateOf(index, completedQuests(index, "a", "side"), "d"));
        }

        @Test
        @DisplayName("several optionals gate until each is done")
        void severalOptionals() {
            QuestIndex index = indexOf(q("s1").optional(true).build(), q("s2").optional(true).build(),
                    q("d").dependsOn("s1", "s2").build());

            assertEquals(QuestState.LOCKED, stateOf(index, TeamProgress.empty(), "d"));
            assertEquals(QuestState.LOCKED, stateOf(index, completedQuests(index, "s1"), "d"));
            assertEquals(QuestState.UNLOCKED,
                    stateOf(index, completedQuests(index, "s1", "s2"), "d"));
        }

        @Test
        @DisplayName("the required count the command reports counts every edge")
        void reportedCountCountsEveryEdge() {
            QuestIndex index = indexOf(q("side").optional(true).build(), q("main").build(),
                    q("d").dependsOn("side", "main").build());

            assertEquals(2,
                    ProgressionEngine.requiredCount(index, Fixtures.quest(index, "d"),
                            dev.ellipog.tenet.quest.PrerequisiteMode.ALL_COMPLETED));
        }
    }

    @Nested
    @DisplayName("with flexible progress")
    class Flexible {

        @Test
        @DisplayName("a flexible quest is measurable while its gate is shut, not locked")
        void flexibleIsMeasurableBehindItsGate() {
            QuestIndex index = indexOf(q("gate").build(),
                    q("early").dependsOn("gate").flexibleProgress(true).build());

            assertEquals(QuestState.UNLOCKED, stateOf(index, TeamProgress.empty(), "early"),
                    "a flexible quest waits on nothing to start working");
            assertEquals(QuestState.STARTED, stateOf(index, startedQuests(index, "early"), "early"),
                    "and progress on it reads as started");
        }

        @Test
        @DisplayName("without the flag the same quest is locked")
        void inflexibleIsLocked() {
            QuestIndex index = indexOf(q("gate").build(), q("late").dependsOn("gate").build());

            assertEquals(QuestState.LOCKED, stateOf(index, TeamProgress.empty(), "late"));
            assertEquals(QuestState.LOCKED, stateOf(index, startedQuests(index, "late"), "late"));
        }

        @Test
        @DisplayName("the chapter default makes every quiet quest flexible")
        void chapterDefaultIsFlexible() {
            QuestIndex index = indexWithChapter("\"defaultFlexibleProgress\": true,",
                    q("gate").build(), q("early").dependsOn("gate").build());

            assertEquals(QuestState.UNLOCKED, stateOf(index, TeamProgress.empty(), "early"));
        }

        @Test
        @DisplayName("either the quest or the chapter makes it flexible")
        void eitherSideMakesItFlexible() {
            QuestIndex flagged = indexWithChapter("", q("gate").build(),
                    q("early").dependsOn("gate").flexibleProgress(true).build());
            assertEquals(QuestState.UNLOCKED, stateOf(flagged, TeamProgress.empty(), "early"));

            QuestIndex plain = indexWithChapter("", q("gate").build(),
                    q("late").dependsOn("gate").build());
            assertEquals(QuestState.LOCKED, stateOf(plain, TeamProgress.empty(), "late"));
        }

        @Test
        @DisplayName("a flexible quest still unlocks normally once its gate opens")
        void gateOpeningUnlocks() {
            QuestIndex index = indexOf(q("gate").build(),
                    q("early").dependsOn("gate").flexibleProgress(true).build());

            assertEquals(QuestState.UNLOCKED,
                    stateOf(index, completedQuests(index, "gate"), "early"));
        }

        @Test
        @DisplayName("linear order still applies to a flexible quest")
        void linearOrderStillApplies() {
            // Flexible is about dependency edges, not about list order: a second quest in a linear
            // chapter still waits on the first, because that axis is a different question.
            QuestIndex index = indexWithChapter("\"progressionMode\": \"linear\",",
                    q("first").build(), q("second").flexibleProgress(true).build());

            assertEquals(QuestState.LOCKED, stateOf(index, TeamProgress.empty(), "second"));
            assertEquals(QuestState.UNLOCKED,
                    stateOf(index, completedQuests(index, "first"), "second"));
        }

        @Test
        @DisplayName("the gate helper judges already-resolved states")
        void gateHelper() {
            QuestIndex index = indexOf(q("gate").build(), q("side").optional(true).build(),
                    q("early").dependsOn("gate", "side").flexibleProgress(true).build());
            var entry = Fixtures.quest(index, "early");
            var questEntry = index.quest("early").orElseThrow();

            var shut = ProgressionEngine.resolve(index, TeamProgress.empty(), NOW).states();
            assertFalse(ProgressionEngine.dependenciesSatisfied(index, questEntry, shut));

            var open = ProgressionEngine
                    .resolve(index, completedQuests(index, "gate"), NOW).states();
            assertFalse(ProgressionEngine.dependenciesSatisfied(index, questEntry, open),
                    "the optional edge gates like any other, so the gate alone does not open it");

            var both = ProgressionEngine
                    .resolve(index, completedQuests(index, "gate", "side"), NOW).states();
            assertTrue(ProgressionEngine.dependenciesSatisfied(index, questEntry, both));

            assertTrue(ProgressionEngine.isFlexible(entry, index.chapters().get(0).chapter()));
            assertFalse(ProgressionEngine.isFlexible(Fixtures.quest(index, "gate"),
                    index.chapters().get(0).chapter()));
        }
    }

    // ------------------------------------------------------------------
    // A cap on how many dependents may complete
    // ------------------------------------------------------------------

    @Nested
    @DisplayName("with maxCompletableDependents")
    class Capped {

        /** One quest three branches depend on, and the cap is the test's variable. */
        private QuestIndex branches(int cap) {
            return indexOf(q("root").noTasks().maxCompletableDependents(cap).build(),
                    q("left").dependsOn("root").build(),
                    q("right").dependsOn("root").build(),
                    q("middle").dependsOn("root").build());
        }

        @Test
        @DisplayName("a cap of one lets one branch through and locks the others")
        void oneOfThree() {
            QuestIndex index = branches(1);
            TeamProgress progress = completedQuests(index, "left");

            assertEquals(QuestState.COMPLETED, stateOf(index, progress, "left"));
            assertEquals(QuestState.LOCKED, stateOf(index, progress, "right"),
                    "a second branch completed past the cap");
            assertEquals(QuestState.LOCKED, stateOf(index, progress, "middle"));
        }

        @Test
        @DisplayName("with nothing taken, every branch is still available")
        void allAvailableBeforeTheCapIsReached() {
            QuestIndex index = branches(2);
            // The root completed, so the branches are unlocked by their dependency -- which is what
            // this test is about: before the cap is reached, a cap changes nothing.
            TeamProgress progress = completedQuests(index, "root");

            assertEquals(QuestState.UNLOCKED, stateOf(index, progress, "left"));
            assertEquals(QuestState.UNLOCKED, stateOf(index, progress, "right"));
            assertEquals(QuestState.UNLOCKED, stateOf(index, progress, "middle"));
        }

        @Test
        @DisplayName("a cap of two leaves exactly one branch locked")
        void twoOfThree() {
            QuestIndex index = branches(2);
            TeamProgress progress = completedQuests(index, "left", "right");

            assertEquals(QuestState.COMPLETED, stateOf(index, progress, "left"));
            assertEquals(QuestState.COMPLETED, stateOf(index, progress, "right"));
            assertEquals(QuestState.LOCKED, stateOf(index, progress, "middle"));
        }

        @Test
        @DisplayName("a cap of zero is no cap at all")
        void zeroIsNoCap() {
            QuestIndex index = branches(0);
            TeamProgress progress = completedQuests(index, "root", "left", "right");

            assertEquals(QuestState.UNLOCKED, stateOf(index, progress, "middle"),
                    "a cap of zero should not lock anything");
        }

        @Test
        @DisplayName("a dependent that completed before the cap was reached keeps its completion")
        void completedDependentsKeepTheirCompletion() {
            // The cap decides which branches are still available, not which ones a player has already
            // taken: a completed quest that lost its completion would lose its rewards with it.
            QuestIndex index = branches(1);
            TeamProgress progress = completedQuests(index, "left", "right");

            assertEquals(QuestState.COMPLETED, stateOf(index, progress, "left"));
            assertEquals(QuestState.COMPLETED, stateOf(index, progress, "right"),
                    "a cap turned a completed branch back into a locked one");
            assertEquals(QuestState.LOCKED, stateOf(index, progress, "middle"));
        }

        @Test
        @DisplayName("the cap counts a dependent named by alias, and one in another chapter")
        void theCapCountsEveryDependent() {
            // A cap is a statement about the graph, and the graph crosses files: a dependent that named
            // the root by alias, or that lives in another chapter, is still a dependent.
            QuestIndex index = Fixtures.indexOf(Fixtures.fileWithChapters(
                    Fixtures.chapter("one", q("root").noTasks().alias("the_root")
                                    .maxCompletableDependents(1).build(),
                            q("left").dependsOn("the_root").build()),
                    Fixtures.chapter("two", q("right").dependsOn("root").build())));
            TeamProgress progress = completedQuests(index, "left");

            assertEquals(QuestState.LOCKED, stateOf(index, progress, "right"),
                    "a dependent in another chapter was not counted against the cap");
        }
    }

    // ------------------------------------------------------------------
    // Exclusive branches
    // ------------------------------------------------------------------

    @Nested
    @DisplayName("with an exclusive group")
    class Exclusive {

        private QuestIndex paths() {
            return indexOf(q("sword").exclusiveGroup("spec").build(),
                    q("pick").exclusiveGroup("spec").build(),
                    q("neither").exclusiveGroup("spec").build());
        }

        @Test
        @DisplayName("taking one path locks the others, permanently")
        void onePathLocksTheRest() {
            QuestIndex index = paths();
            TeamProgress progress = completedQuests(index, "sword");

            assertEquals(QuestState.COMPLETED, stateOf(index, progress, "sword"));
            assertEquals(QuestState.LOCKED, stateOf(index, progress, "pick"));
            assertEquals(QuestState.LOCKED, stateOf(index, progress, "neither"));
        }

        @Test
        @DisplayName("with nothing taken, every path is available")
        void allAvailableUntilOneIsTaken() {
            QuestIndex index = paths();
            TeamProgress empty = TeamProgress.empty();
            assertEquals(QuestState.UNLOCKED, stateOf(index, empty, "sword"));
            assertEquals(QuestState.UNLOCKED, stateOf(index, empty, "pick"));
            assertEquals(QuestState.UNLOCKED, stateOf(index, empty, "neither"));
        }

        @Test
        @DisplayName("a repeatable quest in the group is not locked by its own completion")
        void aRepeatableQuestDoesNotLockItself() {
            // The group's key is recorded for every quest that is satisfied for its dependents, which
            // includes the one that just completed. A repeatable quest therefore reached its own key the
            // moment its first round ended and was locked out of its second, permanently -- the group is
            // meant to lock the siblings, and a quest is not its own sibling.
            QuestIndex index = indexOf(q("daily").repeatable(true).repeatCooldownTicks(600)
                            .exclusiveGroup("spec").build(),
                    q("once").exclusiveGroup("spec").build());
            Quest daily = Fixtures.quest(index, "daily");
            Quest once = Fixtures.quest(index, "once");

            ProgressionEngine.Resolution resolution =
                    ProgressionEngine.resolve(index, completedQuests(index, "daily"), NOW + 600);

            assertEquals(QuestState.STARTED, resolution.stateOf(daily),
                    "the cooldown elapsed, so the quest is playable again rather than locked by its own "
                            + "group");
            assertEquals(QuestState.LOCKED, resolution.stateOf(once),
                    "and its sibling is still locked, which is what the group is for");
        }

        @Test
        @DisplayName("groups in different chapters do not collide, even with the same name")
        void scopedToChapter() {
            // Two chapters, each with a "spec" group. Completing one chapter's quest must not lock
            // the other chapter's -- the group name is scoped by the chapter that declares it, and
            // this is the test that says so.
            String json = """
                    {
                      "version": 1,
                      "chapterGroups": [
                        {
                          "id": "group",
                          "title": "Group",
                          "chapters": [
                            { "id": "first", "title": "First", "quests": [ %s ] },
                            { "id": "second", "title": "Second", "quests": [ %s ] }
                          ]
                        }
                      ]
                    }
                    """.formatted(
                    q("one").exclusiveGroup("spec").build(),
                    q("two").exclusiveGroup("spec").build());

            QuestIndex index = Fixtures.indexOf(json);
            TeamProgress progress = completedQuests(index, "one");

            assertEquals(QuestState.COMPLETED, stateOf(index, progress, "one"));
            assertEquals(QuestState.UNLOCKED, stateOf(index, progress, "two"));
        }
    }

    @Nested
    @DisplayName("excluded ids (T26)")
    class Excluded {

        @Test
        @DisplayName("names the siblings a taken group shut out, and not the quest that took it")
        void takenGroupNamesSiblings() {
            QuestIndex index = indexOf(q("sword").exclusiveGroup("spec").build(),
                    q("pick").exclusiveGroup("spec").build(),
                    q("neither").exclusiveGroup("spec").build());
            TeamProgress progress = completedQuests(index, "sword");

            assertEquals(java.util.Set.of("pick", "neither"),
                    ProgressionEngine.excludedIds(index, progress),
                    "the two shut out, and not the one that took the group");
        }

        @Test
        @DisplayName("names nothing while every path is still available")
        void nothingExcludedBeforeAnythingIsTaken() {
            QuestIndex index = indexOf(q("sword").exclusiveGroup("spec").build(),
                    q("pick").exclusiveGroup("spec").build());
            assertTrue(ProgressionEngine.excludedIds(index, TeamProgress.empty()).isEmpty(),
                    "no choice made, so nothing is shut out");
        }

        @Test
        @DisplayName("names the branches a reached cap cut off, and not the ones that finished")
        void reachedCapNamesCutOff() {
            QuestIndex index = indexOf(q("root").noTasks().maxCompletableDependents(1).build(),
                    q("left").dependsOn("root").build(),
                    q("right").dependsOn("root").build());
            TeamProgress progress = completedQuests(index, "left");

            assertEquals(java.util.Set.of("right"),
                    ProgressionEngine.excludedIds(index, progress),
                    "the branch the cap left, and not the one that finished first");
        }

        @Test
        @DisplayName("a merely locked quest is not excluded")
        void lockedIsNotExcluded() {
            QuestIndex index = indexOf(q("gate").build(),
                    q("late").dependsOn("gate").build());
            assertTrue(ProgressionEngine.excludedIds(index, TeamProgress.empty()).isEmpty(),
                    "not yet unlocked is not shut out: the mark is the reason, not the state");
        }
    }

    // ------------------------------------------------------------------
    // Linear vs flexible
    // ------------------------------------------------------------------

    @Nested
    @DisplayName("in a linear chapter")
    class Linear {

        private static final String LINEAR = "\"progressionMode\": \"linear\",";

        @Test
        @DisplayName("the order of the quest list is the progression, with no dependsOn anywhere")
        void orderIsTheChain() {
            QuestIndex index = indexWithChapter(LINEAR,
                    q("one").build(), q("two").build(), q("three").build());

            assertEquals(QuestState.UNLOCKED, stateOf(index, TeamProgress.empty(), "one"));
            assertEquals(QuestState.LOCKED, stateOf(index, TeamProgress.empty(), "two"));

            TeamProgress afterOne = completedQuests(index, "one");
            assertEquals(QuestState.UNLOCKED, stateOf(index, afterOne, "two"));
            assertEquals(QuestState.LOCKED, stateOf(index, afterOne, "three"));

            assertEquals(QuestState.UNLOCKED,
                    stateOf(index, completedQuests(index, "one", "two"), "three"));
        }

        @Test
        @DisplayName("an explicit dependency still applies on top of the order")
        void explicitDependencyStillCounts() {
            // Linear adds a constraint; it does not replace what a quest already declared. Here
            // "extra" sits between "one" and "two", so "two" waits for both.
            QuestIndex index = indexWithChapter(LINEAR,
                    q("one").build(),
                    q("extra").build(),
                    q("two").dependsOn("extra").build());

            assertEquals(QuestState.LOCKED, stateOf(index, TeamProgress.empty(), "two"));
            assertEquals(QuestState.LOCKED, stateOf(index, completedQuests(index, "one"), "two"),
                    "one is not enough: extra comes first");
            assertEquals(QuestState.UNLOCKED,
                    stateOf(index, completedQuests(index, "one", "extra"), "two"));
        }

        @Test
        @DisplayName("in the default FLEXIBLE mode, order means nothing")
        void flexibleIgnoresOrder() {
            QuestIndex index = indexWithChapter("",
                    q("one").build(), q("two").build(), q("three").build());
            TeamProgress empty = TeamProgress.empty();

            assertEquals(QuestState.UNLOCKED, stateOf(index, empty, "one"));
            assertEquals(QuestState.UNLOCKED, stateOf(index, empty, "two"));
            assertEquals(QuestState.UNLOCKED, stateOf(index, empty, "three"));
        }
    }

    // ------------------------------------------------------------------
    // Repeatable quests
    // ------------------------------------------------------------------

    @Nested
    @DisplayName("a repeatable quest")
    class Repeatable {

        @Test
        @DisplayName("stays completed while its cooldown runs, and reports how long is left")
        void cooldownHolds() {
            QuestIndex index = indexOf(q("daily").repeatable(true).repeatCooldownTicks(600).build());
            Quest daily = Fixtures.quest(index, "daily");

            ProgressionEngine.Resolution resolution =
                    ProgressionEngine.resolve(index, completedQuests(index, "daily"), NOW + 100);

            assertEquals(QuestState.COMPLETED, resolution.stateOf(daily));
            assertEquals(500, resolution.cooldownOf(daily), "600 ticks of cooldown, 100 elapsed");
        }

        @Test
        @DisplayName("becomes playable again once the cooldown has elapsed")
        void cooldownElapsed() {
            QuestIndex index = indexOf(q("daily").repeatable(true).repeatCooldownTicks(600).build());
            Quest daily = Fixtures.quest(index, "daily");

            ProgressionEngine.Resolution resolution =
                    ProgressionEngine.resolve(index, completedQuests(index, "daily"), NOW + 600);

            assertEquals(QuestState.STARTED, resolution.stateOf(daily));
            assertEquals(0, resolution.cooldownOf(daily));
        }

        @Test
        @DisplayName("with no cooldown, it is playable again immediately")
        void noCooldown() {
            QuestIndex index = indexOf(q("daily").repeatable(true).build());
            Quest daily = Fixtures.quest(index, "daily");

            assertEquals(QuestState.STARTED,
                    ProgressionEngine.resolve(index, completedQuests(index, "daily"), NOW).stateOf(daily));
        }

        @Test
        @DisplayName("a non-repeatable quest stays completed however long passes")
        void notRepeatableStaysDone() {
            QuestIndex index = indexOf(q("once").build());
            Quest once = Fixtures.quest(index, "once");

            assertEquals(QuestState.COMPLETED,
                    ProgressionEngine.resolve(index, completedQuests(index, "once"), NOW + 100_000_000)
                            .stateOf(once));
        }

        @Test
        @DisplayName("a repeatable quest satisfies its dependents from the first completion")
        void dependentsStaySatisfied() {
            // Otherwise a chain following a repeatable quest would lock again every time the
            // player redid it, which is not what anyone means by repeatable.
            QuestIndex index = indexOf(q("daily").repeatable(true).repeatCooldownTicks(600).build(),
                    q("after").dependsOn("daily").build());
            Quest daily = Fixtures.quest(index, "daily");

            TeamProgress done = completedQuests(index, "daily");
            assertTrue(ProgressionEngine.satisfiedForDependents(daily, done));
            assertEquals(QuestState.UNLOCKED, stateOf(index, done, "after"));
        }
    }

    // ------------------------------------------------------------------
    // Task completion rules
    // ------------------------------------------------------------------

    @Nested
    @DisplayName("task completion rules")
    class Tasks {

        @Test
        @DisplayName("every task not marked optional has to be done")
        void allMandatory() {
            QuestIndex index = indexOf(q("a").tasks(3).build());
            Quest a = Fixtures.quest(index, "a");

            assertFalse(ProgressionEngine.tasksSatisfied(a, QuestProgress.NONE));
            assertFalse(ProgressionEngine.tasksSatisfied(a, QuestProgress.NONE.recordTask(0, 1)));
            assertFalse(ProgressionEngine.tasksSatisfied(a,
                    QuestProgress.NONE.recordTask(0, 1).recordTask(1, 1)));
            assertTrue(ProgressionEngine.tasksSatisfied(a,
                    QuestProgress.NONE.recordTask(0, 1).recordTask(1, 1).recordTask(2, 1)));
        }

        @Test
        @DisplayName("when every task is optional, any one of them finishes the quest")
        void allOptional() {
            // A quest where nothing is required would otherwise complete itself the instant it
            // unlocked, which is why this has to be a rule rather than falling out of the count.
            QuestIndex index = indexOf(q("a").tasks(3).optional(0).optional(1).optional(2).build());
            Quest a = Fixtures.quest(index, "a");

            assertFalse(ProgressionEngine.tasksSatisfied(a, QuestProgress.NONE));
            assertTrue(ProgressionEngine.tasksSatisfied(a, QuestProgress.NONE.recordTask(1, 1)));
        }

        @Test
        @DisplayName("a mix requires the mandatory ones and ignores the optional one")
        void mixed() {
            QuestIndex index = indexOf(q("a").tasks(3).optional(2).build());
            Quest a = Fixtures.quest(index, "a");

            assertTrue(ProgressionEngine.tasksSatisfied(a,
                            QuestProgress.NONE.recordTask(0, 1).recordTask(1, 1)),
                    "tasks 0 and 1 are mandatory; 2 is optional and not needed");
            assertFalse(ProgressionEngine.tasksSatisfied(a, QuestProgress.NONE.recordTask(0, 1)),
                    "only one of the two mandatory tasks is done");
        }

        @Test
        @DisplayName("a quest with no tasks is completable immediately")
        void noTasks() {
            QuestIndex index = indexOf(q("a").noTasks().build());
            assertTrue(ProgressionEngine.tasksSatisfied(Fixtures.quest(index, "a"), QuestProgress.NONE));
        }

        @Test
        @DisplayName("sequential tasks lock everything after the first unfinished one")
        void sequential() {
            QuestIndex index = indexOf(q("a").tasks(3).sequentialTasks(true).build());
            Quest a = Fixtures.quest(index, "a");

            QuestProgress none = QuestProgress.NONE;
            assertTrue(a.isTaskUnlocked(0, i -> ProgressionEngine.isTaskSatisfied(a, i, none)));
            assertFalse(a.isTaskUnlocked(1, i -> ProgressionEngine.isTaskSatisfied(a, i, none)));

            QuestProgress firstDone = none.recordTask(0, 1);
            assertTrue(a.isTaskUnlocked(1, i -> ProgressionEngine.isTaskSatisfied(a, i, firstDone)));
            assertFalse(a.isTaskUnlocked(2, i -> ProgressionEngine.isTaskSatisfied(a, i, firstDone)));
        }

        @Test
        @DisplayName("without sequentialTasks, every task is available at once")
        void notSequential() {
            QuestIndex index = indexOf(q("a").tasks(3).build());
            Quest a = Fixtures.quest(index, "a");

            // The predicate is deliberately always-false: it should not even be consulted.
            assertTrue(a.isTaskUnlocked(2, i -> false));
        }

        @Test
        @DisplayName("a chapter default makes its quests sequential (XS)")
        void chapterDefaultSequential() {
            QuestIndex index = indexOf(q("a").tasks(3).build());
            Quest a = Fixtures.quest(index, "a");

            assertTrue(a.isTaskUnlocked(2, i -> false, false),
                    "a quest that says nothing follows its chapter, and this chapter says nothing");
            assertFalse(a.isTaskUnlocked(1, i -> false, true),
                    "either true makes the quest sequential, with no opt-out");
            assertTrue(a.isTaskUnlocked(0, i -> false, true),
                    "the first task is always unlocked");
        }

        @Test
        @DisplayName("recorded progress never goes backwards")
        void progressIsMonotonic() {
            // A consuming task reports zero the moment the items are taken, so without this the
            // task would un-complete itself on the next evaluation.
            QuestProgress progress = QuestProgress.NONE.recordTask(0, 5);
            assertEquals(5, progress.recordTask(0, 2).progressOf(0), "a lower reading must not lower it");
            assertEquals(8, progress.recordTask(0, 8).progressOf(0));
            assertEquals(5, progress.recordTask(1, 3).progressOf(0), "a different task must not disturb it");
        }
    }

    // ------------------------------------------------------------------
    // Dependency cycles
    // ------------------------------------------------------------------

    @Nested
    @DisplayName("a dependency cycle")
    class Cycles {

        private static QuestIndex ring() {
            return indexOf(q("a").dependsOn("c").build(),
                    q("b").dependsOn("a").build(),
                    q("c").dependsOn("b").build());
        }

        @Test
        @DisplayName("leaves every quest in it locked, rather than hanging or overflowing")
        void resolvesSafely() {
            QuestIndex index = ring();
            ProgressionEngine.Resolution resolution =
                    ProgressionEngine.resolve(index, TeamProgress.empty(), NOW);

            assertEquals(QuestState.LOCKED, resolution.stateOf(Fixtures.quest(index, "a")));
            assertEquals(QuestState.LOCKED, resolution.stateOf(Fixtures.quest(index, "b")));
            assertEquals(QuestState.LOCKED, resolution.stateOf(Fixtures.quest(index, "c")));
        }

        @Test
        @DisplayName("is reported as a chain that closes the loop")
        void detected() {
            List<List<String>> cycles = ProgressionEngine.findCycles(ring());

            assertEquals(1, cycles.size(), "one loop, reported once");
            List<String> cycle = cycles.get(0);
            assertEquals(cycle.get(0), cycle.get(cycle.size() - 1),
                    "the chain should end where it started: " + cycle);
            assertTrue(cycle.containsAll(List.of("a", "b", "c")),
                    "it should name every quest in the loop: " + cycle);
        }

        @Test
        @DisplayName("is reported once, not once per quest that can reach it")
        void reportedOnce() {
            // A three-quest ring can be entered from any of the three. Without the dedupe this
            // would be three identical messages, and a real questline has more.
            assertEquals(1, ProgressionEngine.findCycles(ring()).size());
        }

        @Test
        @DisplayName("is found even when it runs across two files")
        void acrossFiles() {
            // Two files, so two groups and two chapters -- and they have to be *named* apart. `Fixtures.file`
            // calls every group `group` and every chapter `chapter`, so two of them in one index are a
            // duplicate at both levels: reported, and the second file's whole tree dropped with it. That was
            // invisible while the index listed everything it could not resolve, and it is the fixture's
            // shortcut rather than the loader's fault.
            QuestIndex index = Fixtures.indexOf(
                    Fixtures.fileAs("first", "first_chapter", q("p").dependsOn("q").build()),
                    Fixtures.fileAs("second", "second_chapter", q("q").dependsOn("p").build()));

            List<List<String>> cycles = ProgressionEngine.findCycles(index);
            assertEquals(1, cycles.size());
            assertTrue(cycles.get(0).containsAll(List.of("p", "q")), "got " + cycles.get(0));
        }

        @Test
        @DisplayName("a graph with no loop reports none")
        void noneWhenAcyclic() {
            QuestIndex index = indexOf(q("a").build(), q("b").dependsOn("a").build(),
                    q("c").dependsOn("a").build(), q("d").dependsOn("b", "c").build());
            assertTrue(ProgressionEngine.findCycles(index).isEmpty());
        }

        @Test
        @DisplayName("a self-dependency is a cycle of one")
        void selfDependency() {
            QuestIndex index = indexOf(q("a").dependsOn("a").build());
            assertEquals(QuestState.LOCKED, stateOf(index, TeamProgress.empty(), "a"));
        }
    }

    // ------------------------------------------------------------------
    // Aliases
    // ------------------------------------------------------------------

    @Nested
    @DisplayName("aliases")
    class Aliases {

        @Test
        @DisplayName("let a dependency name an old id, so renaming a quest breaks nothing")
        void dependencyByAlias() {
            QuestIndex index = indexOf(q("punch_a_tree").alias("punch").build(),
                    q("make_a_table").dependsOn("punch").build());

            assertEquals(QuestState.LOCKED, stateOf(index, TeamProgress.empty(), "make_a_table"));
            assertEquals(QuestState.UNLOCKED,
                    stateOf(index, completedQuests(index, "punch_a_tree"), "make_a_table"));
        }

        @Test
        @DisplayName("resolve through the index, by id or by alias")
        void lookupByEither() {
            QuestIndex index = indexOf(q("punch_a_tree").alias("punch", "tree").build());

            assertTrue(index.quest("punch_a_tree").isPresent());
            assertTrue(index.quest("punch").isPresent());
            assertTrue(index.quest("tree").isPresent());
            assertEquals(Fixtures.quest(index, "punch_a_tree"), Fixtures.quest(index, "punch"));
        }

        @Test
        @DisplayName("do not create extra quests in the listing")
        void aliasesDoNotDuplicate() {
            // The lookup table holds one entry per name, so counting it would count a quest once
            // per alias. questCount() walks the files instead, and this is the test that says so.
            QuestIndex index = indexOf(q("a").alias("x", "y", "z").build());
            assertEquals(1, index.questCount());
        }
    }

    // ------------------------------------------------------------------
    // Counting
    // ------------------------------------------------------------------

    @Test
    @DisplayName("the resolution counts what is unlocked and what is complete")
    void counts() {
        QuestIndex index = indexOf(q("a").build(), q("b").dependsOn("a").build(),
                q("c").dependsOn("b").build());

        ProgressionEngine.Resolution empty = ProgressionEngine.resolve(index, TeamProgress.empty(), NOW);
        assertEquals(1, empty.unlockedCount(), "only 'a' to begin with");
        assertEquals(0, empty.completedCount());

        ProgressionEngine.Resolution afterA =
                ProgressionEngine.resolve(index, completedQuests(index, "a"), NOW);
        assertEquals(2, afterA.unlockedCount(), "'a' is done and 'b' has opened");
        assertEquals(1, afterA.completedCount());
    }

    // ------------------------------------------------------------------
    // A chapter's own gate
    // ------------------------------------------------------------------

    /**
     * Two chapters, the second waiting on the first, each with one quest.
     *
     * <p>The shape every test in the nested class below starts from, and the reason it is built through
     * {@code Fixtures.chapterWith} rather than as a v1 file: a chapter gate is a field of the chapter, so
     * a fixture that splices it in beside the title is the same thing the author writes in
     * {@code chapter.json}.
     */
    private static QuestIndex twoGatedChapters(String firstExtras, String secondExtras) {
        return indexWithChapters(
                Fixtures.chapterWith("first", firstExtras, q("a").build()),
                Fixtures.chapterWith("second", secondExtras, q("b").build()));
    }

    @Nested
    @DisplayName("a chapter gate")
    class ChapterGates {

        @Test
        @DisplayName("locks every quest inside a chapter whose dependencies are unmet")
        void locksTheQuestsInside() {
            // The half that makes a chapter dependency mean something. Hiding the row is what an author
            // sees; this is what a player is refused, and it is enforced here rather than in the screen
            // so that /tenet, a script and a click all get the same answer.
            QuestIndex index = twoGatedChapters("\"completesWhen\": [\"a\"],",
                    "\"dependsOn\": [\"first\"],");

            TeamProgress empty = TeamProgress.empty();
            assertEquals(QuestState.LOCKED, chapterStateOf(index, empty, "second"));
            assertEquals(QuestState.LOCKED, stateOf(index, empty, "b"));
            assertEquals(QuestState.UNLOCKED, stateOf(index, empty, "a"),
                    "the first chapter is not gated, so its quest is open");
        }

        @Test
        @DisplayName("opens the whole chapter on the tick its dependency is completed")
        void opensWhenSatisfied() {
            QuestIndex index = twoGatedChapters("\"completesWhen\": [\"a\"],",
                    "\"dependsOn\": [\"first\"],");

            TeamProgress done = completedQuests(index, "a");
            assertEquals(QuestState.COMPLETED, chapterStateOf(index, done, "first"));
            assertEquals(QuestState.UNLOCKED, chapterStateOf(index, done, "second"));
            assertEquals(QuestState.UNLOCKED, stateOf(index, done, "b"));
        }

        @Test
        @DisplayName("reads a started-based mode as one quest touched, not the whole chapter done")
        void startedModeCountsATouch() {
            QuestIndex index = twoGatedChapters("\"completesWhen\": [\"a\"],",
                    "\"dependsOn\": [\"first\"], \"prerequisiteMode\": \"one_started\",");

            TeamProgress touched = startedQuests(index, "a");
            assertEquals(QuestState.STARTED, chapterStateOf(index, touched, "first"));
            assertEquals(QuestState.UNLOCKED, chapterStateOf(index, touched, "second"),
                    "one_started asks for a touch, and it has one");
            assertEquals(QuestState.LOCKED, stateOf(index, TeamProgress.empty(), "b"),
                    "and with nothing touched, it is still shut");
        }

        @Test
        @DisplayName("honours minRequired over the chapters it waits on")
        void minRequiredCounts() {
            QuestIndex index = indexWithChapters(
                    Fixtures.chapterWith("first", "\"completesWhen\": [\"a\"],", q("a").build()),
                    Fixtures.chapterWith("second", "\"completesWhen\": [\"b\"],", q("b").build()),
                    Fixtures.chapterWith("third",
                            "\"dependsOn\": [\"first\", \"second\"], \"minRequired\": 2,",
                            q("c").build()));

            assertEquals(QuestState.LOCKED,
                    chapterStateOf(index, completedQuests(index, "a"), "third"),
                    "one of two is not two");
            assertEquals(QuestState.UNLOCKED,
                    chapterStateOf(index, completedQuests(index, "a", "b"), "third"));
        }

        @Test
        @DisplayName("is COMPLETED only once every quest it names as its completion is done")
        void completesWhenNeedsAllOfThem() {
            QuestIndex index = indexWithChapters(
                    Fixtures.chapterWith("first", "\"completesWhen\": [\"a\", \"a2\"],",
                            q("a").build(), q("a2").build()));

            assertEquals(QuestState.STARTED,
                    chapterStateOf(index, completedQuests(index, "a"), "first"),
                    "one milestone done is progress, not completion");
            assertEquals(QuestState.COMPLETED,
                    chapterStateOf(index, completedQuests(index, "a", "a2"), "first"));
        }

        @Test
        @DisplayName("never reports completed without a completesWhen")
        void noCompletionWithoutDeclaration() {
            // A real state rather than an oversight: a chapter that is only ever waited on as "started"
            // has nothing to declare. The load-time check is what stops a completed bar being written
            // against one -- see ChapterDependencyTest.
            QuestIndex index = indexWithChapters(
                    Fixtures.chapterWith("only", "", q("a").build()));

            assertEquals(QuestState.STARTED,
                    chapterStateOf(index, completedQuests(index, "a"), "only"),
                    "finished quests are progress; the chapter itself never reports completed");
        }

        @Test
        @DisplayName("lets a quest finished before the gate closed keep its completion")
        void completedQuestsStayCompleted() {
            // The same rule the dependents cap follows: a gate decides what is still available, not what
            // a player has already done. A quest that vanishes back to LOCKED would take a player's
            // completion with it the moment an author added a dependency to the chapter.
            QuestIndex index = twoGatedChapters("",
                    "\"dependsOn\": [\"first\"],");
            // `first` declares no completion, so the second chapter's ALL_COMPLETED gate can never open
            // -- which is the state this test needs. Quest `b` was finished while the chapter was open (a
            // file edit can always produce that), and the stored completion is what decides.

            TeamProgress done = completedQuests(index, "b");
            assertEquals(QuestState.COMPLETED, stateOf(index, done, "b"),
                    "a stored completion outranks a chapter that is shut");
            assertEquals(QuestState.LOCKED, chapterStateOf(index, done, "second"),
                    "and the chapter itself is still shut");
        }

        @Test
        @DisplayName("leaves every quest in a chapter cycle locked, rather than hanging")
        void chapterCycleResolvesSafely() {
            QuestIndex index = indexWithChapters(
                    Fixtures.chapterWith("one", "\"dependsOn\": [\"two\"],", q("a").build()),
                    Fixtures.chapterWith("two", "\"dependsOn\": [\"one\"],", q("b").build()));

            TeamProgress empty = TeamProgress.empty();
            assertEquals(QuestState.LOCKED, chapterStateOf(index, empty, "one"));
            assertEquals(QuestState.LOCKED, chapterStateOf(index, empty, "two"));
            assertEquals(QuestState.LOCKED, stateOf(index, empty, "a"));
            assertEquals(QuestState.LOCKED, stateOf(index, empty, "b"));
        }

        @Test
        @DisplayName("stays UNLOCKED when it holds no quests at all")
        void emptyChapterIsOpen() {
            // The state between creating a chapter and writing its first quest, which is normal rather
            // than a fault: it is open, it has nothing in it, and it can never be started or completed
            // because there is nothing to touch. A dependent waiting on it in a started-based mode
            // therefore stays shut, which is the honest reading of "no quest has been started here".
            QuestIndex index = indexWithChapters(
                    Fixtures.chapterWith("empty", ""),
                    Fixtures.chapterWith("waits",
                            "\"dependsOn\": [\"empty\"], \"prerequisiteMode\": \"all_started\",",
                            q("a").build()));

            assertEquals(QuestState.UNLOCKED,
                    chapterStateOf(index, TeamProgress.empty(), "empty"));
            assertEquals(QuestState.LOCKED, chapterStateOf(index, TeamProgress.empty(), "waits"),
                    "nothing in the empty chapter can ever be started");
        }
    }

    // ------------------------------------------------------------------
    // A chapter cycle
    // ------------------------------------------------------------------

    @Nested
    @DisplayName("a circular chapter dependency")
    class ChapterCycles {

        @Test
        @DisplayName("is reported as a chain that closes the loop")
        void detected() {
            QuestIndex index = indexWithChapters(
                    Fixtures.chapterWith("one", "\"dependsOn\": [\"two\"],", q("a").build()),
                    Fixtures.chapterWith("two", "\"dependsOn\": [\"one\"],", q("b").build()));

            List<List<String>> cycles = ProgressionEngine.findChapterCycles(index);

            assertEquals(1, cycles.size(), "one loop, reported once: " + cycles);
            List<String> cycle = cycles.get(0);
            assertEquals(cycle.get(0), cycle.get(cycle.size() - 1),
                    "the chain should end where it started: " + cycle);
            assertTrue(cycle.containsAll(List.of("one", "two")), "got " + cycle);
        }

        @Test
        @DisplayName("is found when it runs through a completion rather than a dependency")
        void throughACompletion() {
            // The edge a hand-written check misses. `one` waits on `two`, and `two` is finished by a
            // quest that lives inside `one` -- so `two` cannot be finished until `one` is open, and `one`
            // cannot open until `two` is finished. Neither file says anything wrong on its own, and the
            // symptom is a chapter that never opens.
            QuestIndex index = indexWithChapters(
                    Fixtures.chapterWith("one", "\"dependsOn\": [\"two\"],", q("a").build()),
                    Fixtures.chapterWith("two", "\"completesWhen\": [\"a\"],", q("b").build()));

            List<List<String>> cycles = ProgressionEngine.findChapterCycles(index);

            assertEquals(1, cycles.size(), "got " + cycles);
            assertTrue(cycles.get(0).containsAll(List.of("one", "two")), "got " + cycles.get(0));
        }

        @Test
        @DisplayName("is not reported for a completion inside the chapter's own quests")
        void ownMilestonesAreNotEdges() {
            // The ordinary case, and the one that would make this check useless: a chapter finished by
            // its own quests is a chapter waiting on itself only if a self-edge counts.
            QuestIndex index = indexWithChapters(
                    Fixtures.chapterWith("one", "\"completesWhen\": [\"a\", \"b\"],",
                            q("a").build(), q("b").dependsOn("a").build()));

            assertTrue(ProgressionEngine.findChapterCycles(index).isEmpty());
        }

        @Test
        @DisplayName("a quest cycle and a chapter cycle are separate questions")
        void theTwoGraphsAreIndependent() {
            // A chapter graph with no loop can hold a quest graph that has one, and the two are reported
            // by their own pass -- which is what keeps the chapter message from claiming a quest is at
            // fault, and the other way round.
            QuestIndex index = indexWithChapters(
                    Fixtures.chapterWith("one", "\"completesWhen\": [\"a\"],",
                            q("a").dependsOn("b").build(), q("b").dependsOn("a").build()));

            assertTrue(ProgressionEngine.findChapterCycles(index).isEmpty(),
                    "the chapters themselves are a chain, not a loop");
            assertEquals(1, ProgressionEngine.findCycles(index).size());
        }
    }

    // ------------------------------------------------------------------
    // The cycle walk's own memory
    // ------------------------------------------------------------------

    @Nested
    @DisplayName("cycle detection")
    class CycleDetection {

        @Test
        @DisplayName("does not treat one id as a prefix of another")
        void idsAreNotSubstrings() {
            // The walk used to prune on a substring test against the *rendered* cycles, so a quest named
            // `stone` counted as already reported by a cycle containing `stone_tools` -- and its own
            // cycle went unmentioned. A load-time silence about a questline that can never be finished,
            // which is the one thing the pass exists to prevent.
            QuestIndex index = indexOf(
                    q("stone_tools").dependsOn("torch").build(),
                    q("torch").dependsOn("stone_tools").build(),
                    q("stone").dependsOn("flint").build(),
                    q("flint").dependsOn("stone").build());

            List<List<String>> cycles = ProgressionEngine.findCycles(index);

            assertEquals(2, cycles.size(), "both loops should be reported, got: " + cycles);
            assertTrue(cycles.stream().anyMatch(cycle -> cycle.contains("stone")
                            && cycle.contains("flint")),
                    "the second loop is missing: " + cycles);
        }
    }
}
