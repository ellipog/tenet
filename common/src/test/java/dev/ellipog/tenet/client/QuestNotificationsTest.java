package dev.ellipog.tenet.client;

import dev.ellipog.tenet.client.QuestNotifications.ChapterSnapshot;
import dev.ellipog.tenet.client.QuestNotifications.Kind;
import dev.ellipog.tenet.client.QuestNotifications.Notice;
import dev.ellipog.tenet.client.QuestNotifications.Snapshot;
import dev.ellipog.tenet.progress.QuestState;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The completion, task, claim and chapter diff, without a client.
 *
 * <p>The interesting cases are the silences: a join must not announce the finished half of the book — nor
 * every task of it — a cleared cache must not read as a hundred removals, a silent auto-claim mode must not
 * speak, and a task that goes <i>backwards</i> is a repeatable quest cycling rather than news. Each is a rule
 * the class documents, and each is asserted here rather than trusted.
 */
@DisplayName("the notice diff: what just happened, and the four ways it says nothing")
class QuestNotificationsTest {

    private final QuestNotifications diff = new QuestNotifications();

    private static Snapshot quest(String id, QuestState state) {
        return new Snapshot(id, state, false, false);
    }

    private static Snapshot claimable(String id) {
        return new Snapshot(id, QuestState.COMPLETED, true, false);
    }

    /**
     * A quest with a task picture: which of its tasks are finished, in the tree's order.
     *
     * <p>The four-argument constructor's empty list is the other case, and the two are deliberately
     * different answers: no picture means every task notice for this quest is suppressed, because a field a
     * server did not send must not read as "everything just finished".
     */
    private static Snapshot tasks(String id, QuestState state, Boolean... done) {
        return new Snapshot(id, state, false, false, List.of(done));
    }

    private static ChapterSnapshot chapter(String id, QuestState state) {
        return new ChapterSnapshot(id, state);
    }

    @Nested
    @DisplayName("completions")
    class Completions {

        @Test
        @DisplayName("the first sample seeds and says nothing, however finished the book is")
        void aJoinIsSilent() {
            assertEquals(List.of(), diff.sample(List.of(
                    quest("a", QuestState.COMPLETED),
                    quest("b", QuestState.STARTED))));
        }

        @Test
        @DisplayName("a quest that becomes complete announces once, and only once")
        void aCompletionAnnouncesOnce() {
            diff.sample(List.of(quest("a", QuestState.STARTED)));

            List<Notice> notices = diff.sample(List.of(quest("a", QuestState.COMPLETED)));
            assertEquals(List.of(new Notice(Kind.COMPLETED, "a")), notices);

            // And the same state again is not a second event: a sync repeats, a completion does not.
            assertEquals(List.of(), diff.sample(List.of(quest("a", QuestState.COMPLETED))));
        }

        @Test
        @DisplayName("a repeatable quest completing again announces again")
        void aRepeatAnnouncesAgain() {
            diff.sample(List.of(quest("a", QuestState.STARTED)));
            diff.sample(List.of(quest("a", QuestState.COMPLETED)));

            // The server puts a repeatable quest back in progress for the next round.
            diff.sample(List.of(quest("a", QuestState.UNLOCKED)));
            assertEquals(List.of(new Notice(Kind.COMPLETED, "a")),
                    diff.sample(List.of(quest("a", QuestState.COMPLETED))));
        }

        @Test
        @DisplayName("a silent auto-claim mode suppresses the completion")
        void silentModesDoNotSpeak() {
            diff.sample(List.of(new Snapshot("a", QuestState.STARTED, false, false)));

            assertEquals(List.of(), diff.sample(List.of(
                    new Snapshot("a", QuestState.COMPLETED, false, true))));
        }

        @Test
        @DisplayName("a quest that drops out of the tree is forgotten, not kept")
        void aRemovedQuestIsForgotten() {
            diff.sample(List.of(quest("a", QuestState.STARTED)));
            diff.sample(List.of());   // the tree moved and 'a' is gone
            diff.sample(List.of(quest("a", QuestState.STARTED)));   // and comes back

            // It reappears in progress and then completes: the notice is about the completion, not
            // about a remembered state from before the removal.
            assertEquals(List.of(new Notice(Kind.COMPLETED, "a")),
                    diff.sample(List.of(quest("a", QuestState.COMPLETED))));
        }
    }

    @Nested
    @DisplayName("claims")
    class Claims {

        @Test
        @DisplayName("waiting rewards that stop waiting are a claim, announced once")
        void aClaimAnnounces() {
            diff.sample(List.of(claimable("a")));

            assertEquals(List.of(new Notice(Kind.CLAIMED, "a")),
                    diff.sample(List.of(quest("a", QuestState.COMPLETED))));
            assertEquals(List.of(), diff.sample(List.of(quest("a", QuestState.COMPLETED))));
        }

        @Test
        @DisplayName("a completion is not a claim, even when it pays out")
        void aCompletionIsNotAClaim() {
            // A quest that completes with its rewards auto-granted was never claimable at either
            // sample: the completion is the event, and the claim sound does not fire for it.
            diff.sample(List.of(quest("a", QuestState.STARTED)));
            assertEquals(List.of(new Notice(Kind.COMPLETED, "a")),
                    diff.sample(List.of(quest("a", QuestState.COMPLETED))));
        }

        @Test
        @DisplayName("a reset is neither a claim nor a completion")
        void aResetIsSilent() {
            diff.sample(List.of(claimable("a")));
            assertEquals(List.of(), diff.sample(List.of(quest("a", QuestState.LOCKED))));
        }
    }

    @Nested
    @DisplayName("the reset rule")
    class Resets {

        @Test
        @DisplayName("an empty sample forgets everything, so the next one seeds silently")
        void anEmptySampleResets() {
            diff.sample(List.of(quest("a", QuestState.STARTED)));
            assertEquals(List.of(), diff.sample(List.of()));

            // A world change: the same id arrives complete on the next server, and that is a join,
            // not a completion that happened in front of this player.
            assertEquals(List.of(), diff.sample(List.of(quest("a", QuestState.COMPLETED))));
        }

        @Test
        @DisplayName("reset() does the same, for the disconnect hook")
        void resetForgets() {
            diff.sample(List.of(quest("a", QuestState.STARTED)));
            diff.reset();
            assertEquals(List.of(), diff.sample(List.of(quest("a", QuestState.COMPLETED))));
        }

        @Test
        @DisplayName("a quest entering the tree part-way through is seeded, not announced")
        void aNewQuestIsSeeded() {
            diff.sample(List.of(quest("a", QuestState.STARTED)));
            // 'b' appears already complete -- a reload added it, or the tree gained a chapter.
            List<Notice> notices = diff.sample(List.of(
                    quest("a", QuestState.STARTED), quest("b", QuestState.COMPLETED)));
            assertTrue(notices.isEmpty(), "a quest first seen complete is not a completion");
        }
    }

    /**
     * The partial sample, which is what a delta is read with.
     *
     * <h2>Why these are separate cases rather than a shorter list</h2>
     *
     * <p>A delta names the quests that moved. Reading it with the whole-cache method would <b>forget
     * every quest it did not name</b> — and this diff only announces a transition it has a previous
     * sample for, so a forgotten quest can never be announced again. That is the failure these cases
     * exist to catch, and it is silent: no exception, no log line, just a completion that never makes
     * a sound.
     */
    @Nested
    @DisplayName("the partial sample a delta is read with")
    class Partial {

        @Test
        @DisplayName("a quest the delta named announces its completion")
        void aNamedQuestAnnounces() {
            diff.sample(List.of(quest("a", QuestState.STARTED), quest("b", QuestState.STARTED)));

            assertEquals(List.of(new Notice(Kind.COMPLETED, "a")),
                    diff.sampleSome(Set.of("a"), List.of(quest("a", QuestState.COMPLETED))));
        }

        @Test
        @DisplayName("a quest the delta did not name keeps the sample it had")
        void anUnnamedQuestKeepsItsBaseline() {
            diff.sample(List.of(quest("a", QuestState.STARTED), quest("b", QuestState.STARTED)));

            // A delta about 'a' that changes nothing about it. The whole-cache method would forget 'b'
            // here, and the cost of that is the next assertion rather than this one.
            assertEquals(List.of(), diff.sampleSome(Set.of("a"), List.of(quest("a", QuestState.STARTED))));

            assertEquals(List.of(new Notice(Kind.COMPLETED, "b")),
                    diff.sampleSome(Set.of("b"), List.of(quest("b", QuestState.COMPLETED))),
                    "'b' was not named by the earlier delta, which is not the same as it being gone");
        }

        @Test
        @DisplayName("a partial sample of nothing is not a reset")
        void anEmptyPartialIsNotAReset() {
            diff.sample(List.of(quest("a", QuestState.STARTED)));

            // The full method reads an empty sample as "the cache is empty, forget everything". A delta
            // that named nothing is a message about nothing, and the baseline has to survive it.
            assertEquals(List.of(), diff.sampleSome(Set.of(), List.of()));

            assertEquals(List.of(new Notice(Kind.COMPLETED, "a")),
                    diff.sampleSome(Set.of("a"), List.of(quest("a", QuestState.COMPLETED))));
        }

        @Test
        @DisplayName("an id the message named and the tree no longer holds is forgotten")
        void aNamedButAbsentIdIsForgotten() {
            diff.sample(List.of(quest("a", QuestState.STARTED)));

            // The delta named 'a' and the tree has no picture of it: a removal, said the only way a
            // delta can say one.
            assertEquals(List.of(), diff.sampleSome(Set.of("a"), List.of()));

            assertEquals(List.of(), diff.sampleSome(Set.of("a"), List.of(quest("a", QuestState.STARTED))),
                    "it comes back and is seeded again, rather than compared with what it was before");
            assertEquals(List.of(new Notice(Kind.COMPLETED, "a")),
                    diff.sampleSome(Set.of("a"), List.of(quest("a", QuestState.COMPLETED))));
        }

        @Test
        @DisplayName("a claim is announced through a delta as well as through a full sync")
        void aClaimAnnouncesThroughADelta() {
            diff.sample(List.of(claimable("a")));

            assertEquals(List.of(new Notice(Kind.CLAIMED, "a")),
                    diff.sampleSome(Set.of("a"), List.of(quest("a", QuestState.COMPLETED))));
        }
    }

    /**
     * The task transitions, which are the same comparison one level down.
     *
     * <h2>Why the direction matters enough to be half of these cases</h2>
     *
     * <p>A task finishing is news. A task <i>un</i>-finishing is not: it is a repeatable quest cycling or a
     * counter that went down, and a row about either would be a notice about nothing — which is the fault the
     * claim rule already avoids by requiring the same state at both ends. So the baseline is replaced either
     * way and only the arrival is announced, and both directions are asserted because only one of them is
     * visible when it is wrong.
     */
    @Nested
    @DisplayName("tasks")
    class Tasks {

        @Test
        @DisplayName("a task that was not finished and now is is announced, once")
        void aTaskArrivingAnnounces() {
            diff.sample(List.of(tasks("a", QuestState.STARTED, false)));

            assertEquals(List.of(new Notice(Kind.TASK_COMPLETED, "a", 0)),
                    diff.sample(List.of(tasks("a", QuestState.STARTED, true))));
            assertEquals(List.of(), diff.sample(List.of(tasks("a", QuestState.STARTED, true))),
                    "and the same state again is not a second event");
        }

        @Test
        @DisplayName("a task that stopped being finished says nothing, and is re-armed for next time")
        void aTaskGoingBackwardsIsSilent() {
            diff.sample(List.of(tasks("a", QuestState.STARTED, true)));

            assertEquals(List.of(), diff.sample(List.of(tasks("a", QuestState.STARTED, false))),
                    "a repeatable quest cycling is not a notice");

            // Re-armed rather than left finished: the second arrival is a real second arrival.
            assertEquals(List.of(new Notice(Kind.TASK_COMPLETED, "a", 0)),
                    diff.sample(List.of(tasks("a", QuestState.STARTED, true))));
        }

        @Test
        @DisplayName("a quest completing swallows the tasks that completed with it")
        void aCompletionSwallowsItsTasks() {
            diff.sample(List.of(tasks("a", QuestState.STARTED, false, false)));

            // A quest becomes COMPLETED when its last task does, so without this rule one sentence would be
            // followed by the whole task list arriving at once.
            assertEquals(List.of(new Notice(Kind.COMPLETED, "a")),
                    diff.sample(List.of(tasks("a", QuestState.COMPLETED, true, true))));
        }

        @Test
        @DisplayName("a silent quest says nothing about its tasks either")
        void aSilentQuestSilencesItsTasks() {
            diff.sample(List.of(new Snapshot("a", QuestState.STARTED, false, false, List.of(false))));

            assertEquals(List.of(), diff.sample(List.of(
                    new Snapshot("a", QuestState.STARTED, false, true, List.of(true)))),
                    "fifty starter quests that must not be announced would otherwise be hundreds of rows");
        }

        @Test
        @DisplayName("a quest quieted by its author announces no completion")
        void aQuietQuestAnnouncesNoCompletion() {
            // The quest-level disableToast: the same silence as a silent auto-claim mode, asked by name
            // rather than by ladder. the legacy disable_toast on the quest.
            diff.sample(List.of(new Snapshot("a", QuestState.STARTED, false, false)));

            assertEquals(List.of(), diff.sample(List.of(
                    new Snapshot("a", QuestState.COMPLETED, false, true))),
                    "a quest that asked for no toast gets none");
        }

        @Test
        @DisplayName("a quest quieted by its author says nothing about its tasks either")
        void aQuietQuestSilencesItsTasks() {
            diff.sample(List.of(new Snapshot("a", QuestState.STARTED, false, false,
                    List.of(false, false))));

            assertEquals(List.of(), diff.sample(List.of(
                    new Snapshot("a", QuestState.STARTED, false, true,
                            List.of(true, true)))),
                    "quieting the quest quiets its rows too, like the silent modes do");
        }

        @Test
        @DisplayName("a task quieted by its author is skipped while its siblings speak")
        void aQuietTaskIsSkipped() {
            // The per-task disableToast: one muted arrival among two, and only the unmuted one is told.
            diff.sample(List.of(new Snapshot("a", QuestState.STARTED, false, false,
                    List.of(false, false), List.of(false, true))));

            assertEquals(List.of(new Notice(Kind.TASK_COMPLETED, "a", 0)),
                    diff.sample(List.of(new Snapshot("a", QuestState.STARTED, false, false,
                            List.of(true, true), List.of(false, true)))));
        }

        @Test
        @DisplayName("a muted task still moves the baseline, so un-quieting does not announce old news")
        void aMutedTaskMovesTheBaseline() {
            diff.sample(List.of(new Snapshot("a", QuestState.STARTED, false, false,
                    List.of(false), List.of(true))));
            diff.sample(List.of(new Snapshot("a", QuestState.STARTED, false, false,
                    List.of(true), List.of(true))));

            // The arrival happened while muted; lifting the mute later is not a second arrival.
            assertEquals(List.of(), diff.sample(List.of(new Snapshot("a", QuestState.STARTED, false,
                    false, List.of(true), List.of(false)))));
        }

        @Test
        @DisplayName("a join seeds the task picture and announces none of it")
        void aJoinSeedsTheTasks() {
            assertEquals(List.of(), diff.sample(List.of(tasks("a", QuestState.COMPLETED, true, true))));
            assertEquals(List.of(), diff.sample(List.of(tasks("a", QuestState.COMPLETED, true, true))));
        }

        @Test
        @DisplayName("a task the last sample did not reach has no baseline, so it is not an arrival")
        void anUnpicturedTaskHasNoBaseline() {
            diff.sample(List.of(tasks("a", QuestState.STARTED, false)));

            // The tree gained a task: index 1 was not in the previous picture, and a transition against
            // nothing is not a transition. Index 0 is compared as usual.
            assertEquals(List.of(new Notice(Kind.TASK_COMPLETED, "a", 0)),
                    diff.sample(List.of(tasks("a", QuestState.STARTED, true, true))));
        }

        @Test
        @DisplayName("a quest with no task picture at all announces no tasks")
        void noPictureIsNoAnnouncement() {
            diff.sample(List.of(quest("a", QuestState.STARTED)));

            // The safe direction: a server too old to send the field, or a caller that never asked for it,
            // reads as "nothing to compare" rather than as "everything just finished".
            assertEquals(List.of(), diff.sample(List.of(tasks("a", QuestState.STARTED, true, true))));
        }

        @Test
        @DisplayName("tasks are announced through a delta as well as through a full sync")
        void aTaskArrivesThroughADelta() {
            diff.sample(List.of(tasks("a", QuestState.STARTED, false, false),
                    tasks("b", QuestState.STARTED, false)));

            assertEquals(List.of(new Notice(Kind.TASK_COMPLETED, "a", 1)),
                    diff.sampleSome(Set.of("a"), List.of(tasks("a", QuestState.STARTED, false, true))));
        }

        @Test
        @DisplayName("a task that starts a quest, rather than finishing it, is still just a task")
        void aStartedQuestStillSpeaksForItsTask() {
            diff.sample(List.of(tasks("a", QuestState.UNLOCKED, false)));

            assertEquals(List.of(new Notice(Kind.TASK_COMPLETED, "a", 0)),
                    diff.sample(List.of(tasks("a", QuestState.STARTED, true))));
        }
    }

    /**
     * The chapter diff, which is a second key space and a second map.
     *
     * <h2>Why a chapter is read on every progress change</h2>
     *
     * <p>{@code QuestSync} writes the chapter map whole into a delta as well as into a full sync, so unlike a
     * quest there is nothing partial about it — which is why the notifier calls this on both paths, and why
     * the empty-list rule below is the only silence it needs.
     */
    @Nested
    @DisplayName("chapters")
    class Chapters {

        @Test
        @DisplayName("the first chapter sample seeds and says nothing")
        void aJoinIsSilent() {
            assertEquals(List.of(), diff.chapters(List.of(chapter("one", QuestState.COMPLETED),
                    chapter("two", QuestState.STARTED))));
        }

        @Test
        @DisplayName("a chapter that completes is announced once, and only once")
        void aChapterCompletingAnnouncesOnce() {
            diff.chapters(List.of(chapter("one", QuestState.STARTED)));

            assertEquals(List.of(new Notice(Kind.CHAPTER_COMPLETED, "one")),
                    diff.chapters(List.of(chapter("one", QuestState.COMPLETED))));
            assertEquals(List.of(), diff.chapters(List.of(chapter("one", QuestState.COMPLETED))));
        }

        @Test
        @DisplayName("an empty chapter list is no news rather than every chapter reopening")
        void anEmptyListIsNoNews() {
            diff.chapters(List.of(chapter("one", QuestState.COMPLETED)));

            // A server older than chapter gates sends no map at all. Reading that as "nothing is complete"
            // would make the next full sync announce a completion that happened before this player joined.
            assertEquals(List.of(), diff.chapters(List.of()));
            assertEquals(List.of(), diff.chapters(List.of(chapter("one", QuestState.COMPLETED))));
        }

        @Test
        @DisplayName("a chapter that stops being complete says nothing, and is re-armed")
        void aChapterGoingBackwardsIsSilent() {
            diff.chapters(List.of(chapter("one", QuestState.COMPLETED)));

            assertEquals(List.of(), diff.chapters(List.of(chapter("one", QuestState.UNLOCKED))),
                    "a pack edit or a reset is not a completion");
            assertEquals(List.of(new Notice(Kind.CHAPTER_COMPLETED, "one")),
                    diff.chapters(List.of(chapter("one", QuestState.COMPLETED))));
        }

        @Test
        @DisplayName("a chapter the pack no longer carries is forgotten, like a quest")
        void aRemovedChapterIsForgotten() {
            diff.chapters(List.of(chapter("one", QuestState.STARTED)));
            diff.chapters(List.of());
            diff.chapters(List.of(chapter("one", QuestState.STARTED)));

            assertEquals(List.of(new Notice(Kind.CHAPTER_COMPLETED, "one")),
                    diff.chapters(List.of(chapter("one", QuestState.COMPLETED))));
        }

        @Test
        @DisplayName("reset() forgets the chapters with everything else")
        void resetForgetsChapters() {
            diff.chapters(List.of(chapter("one", QuestState.STARTED)));
            diff.reset();

            assertEquals(List.of(), diff.chapters(List.of(chapter("one", QuestState.COMPLETED))),
                    "a world change must not greet the next server with a chapter it finished");
        }

        @Test
        @DisplayName("an added chapter is seeded, not announced")
        void anAddedChapterIsSeeded() {
            diff.chapters(List.of(chapter("one", QuestState.STARTED)));

            assertEquals(List.of(), diff.chapters(List.of(chapter("one", QuestState.STARTED),
                    chapter("two", QuestState.COMPLETED))));
        }
    }
}
