package dev.ellipog.tenet.client;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The first-run intro: when it starts, what moves it, and how it ends.
 *
 * <p>All through the tick's parameters rather than the screen — the tree counts, the permission
 * and the preview are numbers and booleans here, so "an author facing an empty book" is a call
 * rather than a running game. {@code DevMode} is the one static this touches, and the file is
 * never set in these tests, so every write is a memory-only one the teardown forgets.
 */
@DisplayName("The first-quest intro")
class IntroGuideTest {

    @AfterEach
    void forget() {
        // Both halves are static: a test that leaves either set changes the next one.
        IntroGuide.clearSession();
        DevMode.reset();
    }

    @Test
    @DisplayName("it starts on its own only for an author facing an empty book")
    void autoStartsOnEmptyBookInEditMode() {
        IntroGuide.tick(true, true, false, true, 0, 0);
        assertTrue(IntroGuide.isActive(), "edit permission, edit mode, tree arrived, no chapters");
        assertEquals(IntroGuide.Step.CHAPTER, IntroGuide.step());
    }

    @Test
    @DisplayName("it stays quiet for everyone the tour is not for")
    void autoStartConditions() {
        IntroGuide.tick(false, true, false, true, 0, 0);
        assertFalse(IntroGuide.isActive(), "no edit permission, no tour");

        IntroGuide.tick(true, false, false, true, 0, 0);
        assertFalse(IntroGuide.isActive(), "edit mode off, no tour");

        IntroGuide.tick(true, true, true, true, 0, 0);
        assertFalse(IntroGuide.isActive(), "the player's view is showing, not the editor");

        IntroGuide.tick(true, true, false, false, 0, 0);
        assertFalse(IntroGuide.isActive(), "the tree has not arrived, so emptiness means nothing yet");

        IntroGuide.tick(true, true, false, true, 2, 5);
        assertFalse(IntroGuide.isActive(), "a book with chapters needs no first chapter");

        DevMode.setGuideSeen(true);
        IntroGuide.tick(true, true, false, true, 0, 0);
        assertFalse(IntroGuide.isActive(), "seen means seen, even facing an empty book");
    }

    @Test
    @DisplayName("making the chapter and the quest walks the first two rungs")
    void treeCountsAdvanceChapterAndQuest() {
        IntroGuide.tick(true, true, false, true, 0, 0);
        IntroGuide.tick(true, true, false, true, 1, 0);
        assertEquals(IntroGuide.Step.QUEST, IntroGuide.step(), "the tree gained a chapter");

        IntroGuide.tick(true, true, false, true, 1, 1);
        assertEquals(IntroGuide.Step.TASK, IntroGuide.step(), "the tree gained a quest");
    }

    @Test
    @DisplayName("accepted edits walk the task and reward rungs, refusals do not")
    void repliesAdvanceTaskAndReward() {
        IntroGuide.tick(true, true, false, true, 0, 0);
        IntroGuide.noteReply(false, "");
        assertEquals(IntroGuide.Step.CHAPTER, IntroGuide.step(), "a refusal changes nothing");

        IntroGuide.tick(true, true, false, true, 1, 0);
        assertEquals(IntroGuide.Step.QUEST, IntroGuide.step(), "the tree gained a chapter");
        IntroGuide.tick(true, true, false, true, 1, 1);
        assertEquals(IntroGuide.Step.TASK, IntroGuide.step(), "the tree gained a quest");
        IntroGuide.noteReply(false, "");
        assertEquals(IntroGuide.Step.TASK, IntroGuide.step(), "a refused task is not a task");

        IntroGuide.noteReply(true, "");
        assertEquals(IntroGuide.Step.REWARD, IntroGuide.step(), "an accepted edit is the task");

        IntroGuide.noteReply(true, "");
        assertEquals(IntroGuide.Step.DONE, IntroGuide.step(), "and the next one is the reward");
        assertTrue(IntroGuide.completedByDoing(), "made, not skipped to");
        assertTrue(DevMode.hasSeenGuide(), "a finished tour does not return");
    }

    @Test
    @DisplayName("a minted quest id walks the quest rung the way the screen selects on it")
    void mintedQuestIdAdvancesQuestRung() {
        IntroGuide.tick(true, true, false, true, 0, 0);
        IntroGuide.tick(true, true, false, true, 1, 0);
        IntroGuide.noteReply(true, "");
        assertEquals(IntroGuide.Step.QUEST, IntroGuide.step(),
                "an id-less reply on the quest rung is not a made quest");
        IntroGuide.noteReply(true, "first_quest");
        assertEquals(IntroGuide.Step.TASK, IntroGuide.step(),
                "the server naming the id is the first moment the made quest exists");
    }

    @Test
    @DisplayName("/tenet intro starts at the first rung that is still undone and unsees")
    void freshRequestStartsWhereTheBookIs() {
        DevMode.setGuideSeen(true);
        IntroGuide.requestFresh();
        IntroGuide.tick(true, true, false, true, 3, 7);
        assertTrue(IntroGuide.isActive(), "an ask is an ask, content or not");
        assertFalse(DevMode.hasSeenGuide(), "asking again unsees");
        assertEquals(IntroGuide.Step.TASK, IntroGuide.step(),
                "a book with chapters and quests still needs its task");

        IntroGuide.clearSession();
        IntroGuide.requestFresh();
        IntroGuide.tick(true, true, false, true, 0, 0);
        assertEquals(IntroGuide.Step.CHAPTER, IntroGuide.step(), "an empty book starts at the start");
    }

    @Test
    @DisplayName("Next walks without doing, Skip leaves for the session, Done leaves for good")
    void exits() {
        IntroGuide.tick(true, true, false, true, 0, 0);
        IntroGuide.skipStep();
        assertEquals(IntroGuide.Step.QUEST, IntroGuide.step());
        IntroGuide.skipStep();
        IntroGuide.skipStep();
        IntroGuide.skipStep();
        assertEquals(IntroGuide.Step.DONE, IntroGuide.step());
        assertFalse(IntroGuide.completedByDoing(), "skipped to, not made");
        IntroGuide.skipStep();
        assertEquals(IntroGuide.Step.DONE, IntroGuide.step(), "past the end is still the end");

        IntroGuide.dismiss();
        assertFalse(IntroGuide.isActive());
        assertTrue(DevMode.hasSeenGuide(), "leaving the done card stays left");

        IntroGuide.clearSession();
        DevMode.setGuideSeen(false);
        IntroGuide.tick(true, true, false, true, 0, 0);
        assertTrue(IntroGuide.isActive(), "a new tour starts once unseen again");
        IntroGuide.skip();
        assertFalse(IntroGuide.isActive());
        assertFalse(DevMode.hasSeenGuide(), "skipping mid-tour marks nothing");
        IntroGuide.tick(true, true, false, true, 0, 0);
        assertFalse(IntroGuide.isActive(), "and stays away for the session");

        IntroGuide.clearSession();
        IntroGuide.tick(true, true, false, true, 0, 0);
        assertTrue(IntroGuide.isActive(), "but a waved-away tour on a still-empty book returns");
    }

    @Test
    @DisplayName("losing permission or entering preview drops the tour without marking it")
    void interruptionResumes() {
        IntroGuide.tick(true, true, false, true, 0, 0);
        IntroGuide.tick(false, true, false, true, 0, 0);
        assertFalse(IntroGuide.isActive(), "de-opped mid-tour");
        assertFalse(DevMode.hasSeenGuide(), "and nothing is marked");

        IntroGuide.tick(true, true, false, true, 0, 0);
        assertTrue(IntroGuide.isActive(), "re-opped on the still-empty book resumes at the start");
        assertEquals(IntroGuide.Step.CHAPTER, IntroGuide.step());
    }

    @Test
    @DisplayName("the checkbox is the durable flag and nothing else")
    void dontShowAgainIsTheFlag() {
        IntroGuide.setDontShowAgain(true);
        assertTrue(DevMode.hasSeenGuide());
        assertTrue(IntroGuide.dontShowAgain());
        IntroGuide.setDontShowAgain(false);
        assertFalse(IntroGuide.dontShowAgain());
    }
}
