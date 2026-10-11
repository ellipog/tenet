package dev.ellipog.tenet.client.dev;

import dev.ellipog.tenet.progress.QuestState;
import dev.ellipog.tenet.quest.PrerequisiteMode;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * What the client says about a quest's prerequisites.
 *
 * <h2>The bug this exists for</h2>
 *
 * <p>The canvas coloured a dependency line by "is the prerequisite completed" and the card ticked its
 * rows the same way — which is the right question for two of the four modes and the wrong one for the
 * other two. A quest whose rule is {@code all_started} unlocks as soon as its prerequisites have any
 * task progress, and the client drew that as unmet: a dark line and a cross beside a prerequisite that
 * had already done its job.
 */
@DisplayName("The dependency read-out")
class DependencyProgressTest {

    private static final Function<String, QuestState> NOTHING = id -> null;

    private static Function<String, QuestState> states(Map<String, QuestState> given) {
        return given::get;
    }

    @Test
    @DisplayName("the completed modes count only completed dependencies")
    void theCompletedModesNeedCompletion() {
        DependencyProgress progress = new DependencyProgress(PrerequisiteMode.ALL_COMPLETED, 0,
                List.of("a", "b"));
        assertEquals(QuestState.COMPLETED, progress.bar());

        Function<String, QuestState> started = states(Map.of("a", QuestState.STARTED,
                "b", QuestState.STARTED));
        assertFalse(progress.satisfies("a", started), "a started prerequisite counted as completed");
        assertEquals(0, progress.satisfied(started));
        assertFalse(progress.met(started));
    }

    @Test
    @DisplayName("the started modes count a dependency that is merely started")
    void theStartedModesCountStarted() {
        // The whole reason this class exists: under these two modes a player with any task progress in
        // the prerequisite has satisfied it, and the client used to draw that as unmet.
        DependencyProgress progress = new DependencyProgress(PrerequisiteMode.ALL_STARTED, 0,
                List.of("a", "b"));
        assertEquals(QuestState.STARTED, progress.bar());

        Function<String, QuestState> started = states(Map.of("a", QuestState.STARTED,
                "b", QuestState.COMPLETED));
        assertTrue(progress.satisfies("a", started), "a started prerequisite was not counted");
        assertEquals(2, progress.satisfied(started));
        assertTrue(progress.met(started));

        assertFalse(progress.satisfies("a", states(Map.of("a", QuestState.UNLOCKED))),
                "an unlocked prerequisite counted as started");
    }

    @Test
    @DisplayName("one-of means one, however many there are")
    void oneOfMeansOne() {
        DependencyProgress progress = new DependencyProgress(PrerequisiteMode.ONE_COMPLETED, 0,
                List.of("a", "b", "c"));
        assertEquals(1, progress.required());

        Function<String, QuestState> one = states(Map.of("b", QuestState.COMPLETED));
        assertEquals(1, progress.satisfied(one));
        assertTrue(progress.met(one));
        assertEquals(List.of("a", "c"), progress.waitingFor(one));
    }

    @Test
    @DisplayName("all-of needs every one of them")
    void allOfNeedsEveryOne() {
        DependencyProgress progress = new DependencyProgress(PrerequisiteMode.ALL_COMPLETED, 0,
                List.of("a", "b", "c"));
        assertEquals(3, progress.required());

        Function<String, QuestState> two = states(Map.of("a", QuestState.COMPLETED,
                "b", QuestState.COMPLETED));
        assertEquals(2, progress.satisfied(two));
        assertFalse(progress.met(two));
        assertEquals(List.of("c"), progress.waitingFor(two));
    }

    @Test
    @DisplayName("minRequired is the count, and the mode still decides the bar")
    void minRequiredSetsTheCount() {
        // The server's rule, and the one the docs used to describe wrongly: `minRequired` replaces how
        // *many*, and the mode still says whether "met" means completed or started. So two of three
        // under `one_started` is "any two started", which no other field can express.
        DependencyProgress twoOfThree = new DependencyProgress(PrerequisiteMode.ONE_STARTED, 2,
                List.of("a", "b", "c"));
        assertEquals(2, twoOfThree.required());
        assertEquals(QuestState.STARTED, twoOfThree.bar());

        Function<String, QuestState> twoStarted = states(Map.of("a", QuestState.STARTED,
                "b", QuestState.STARTED));
        assertTrue(twoOfThree.met(twoStarted));
        assertEquals(List.of("c"), twoOfThree.waitingFor(twoStarted));
    }

    @Test
    @DisplayName("a quest with no dependencies is met, whatever the mode")
    void nothingToWaitFor() {
        for (PrerequisiteMode mode : PrerequisiteMode.values()) {
            DependencyProgress progress = new DependencyProgress(mode, 0, List.of());
            assertEquals(0, progress.required(), mode + " asked for a dependency it does not have");
            assertTrue(progress.met(NOTHING), mode + " was not met with nothing to wait for");
        }
    }

    @Test
    @DisplayName("a dependency this client cannot resolve is unmet, not quietly satisfied")
    void anUnknownDependencyIsUnmet() {
        // A quest from a newer server, or one filtered out of the chapter. Drawing it as satisfied
        // would be a line claiming something the client cannot see; admitting it does not know is the
        // honest answer, and it is the same one the server gives (a dependent of an unresolvable
        // dependency stays locked).
        DependencyProgress progress = new DependencyProgress(PrerequisiteMode.ALL_COMPLETED, 0,
                List.of("gone"));
        assertFalse(progress.satisfies("gone", NOTHING));
        assertFalse(progress.met(NOTHING));
        assertEquals(List.of("gone"), progress.waitingFor(NOTHING));
    }

    @Test
    @DisplayName("minRequired beyond the list is clamped, so the read-out cannot ask for the impossible")
    void minRequiredIsClamped() {
        // The loader reports the file as an error; until it is fixed the quest behaves as "all of them",
        // which is what the engine does and what the client must show.
        DependencyProgress progress = new DependencyProgress(PrerequisiteMode.ALL_COMPLETED, 5,
                List.of("a"));
        assertEquals(1, progress.required());
        assertTrue(progress.met(states(Map.of("a", QuestState.COMPLETED))));
    }

    @Test
    @DisplayName("an optional dependency counts like any other")
    void optionalDependenciesCount() {
        // The client's half of the engine's rule: legacy parity means an optional edge gates
        // exactly like a mandatory one, so the card's "2 of 3 met" uses the same denominator
        // the unlock does. The line itself is still drawn, and satisfies still answers per
        // edge.
        DependencyProgress progress = new DependencyProgress(PrerequisiteMode.ALL_COMPLETED, 0,
                List.of("main", "side"));
        assertEquals(2, progress.required());
        assertEquals(2, progress.counted());

        Function<String, QuestState> mainDone = states(Map.of("main", QuestState.COMPLETED));
        assertEquals(1, progress.satisfied(mainDone));
        assertFalse(progress.met(mainDone));
        assertEquals(List.of("side"), progress.waitingFor(mainDone));

        Function<String, QuestState> sideDone = states(Map.of("side", QuestState.COMPLETED));
        assertEquals(1, progress.satisfied(sideDone), "a completed optional satisfies like any edge");
        assertFalse(progress.met(sideDone));
        assertEquals(List.of("main"), progress.waitingFor(sideDone));

        Function<String, QuestState> bothDone = states(
                Map.of("main", QuestState.COMPLETED, "side", QuestState.COMPLETED));
        assertTrue(progress.met(bothDone));
        assertEquals(List.of(), progress.waitingFor(bothDone));

        // ...and every edge still answers, because the line is still drawn.
        assertTrue(progress.satisfies("side", sideDone));
    }

    @Test
    @DisplayName("the three-argument form counts every edge")
    void threeArgumentsCountEverything() {
        DependencyProgress progress = new DependencyProgress(PrerequisiteMode.ALL_COMPLETED, 0,
                List.of("a", "b"));
        assertEquals(2, progress.required());
        assertEquals(2, progress.counted());
    }
}
