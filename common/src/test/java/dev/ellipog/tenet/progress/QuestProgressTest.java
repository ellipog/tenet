package dev.ellipog.tenet.progress;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A repeatable round's two halves: what completion records, and what the final claim ends.
 *
 * <p>the legacy rule is that the repeat timer starts when the last unclaimed reward is claimed:
 * the quest's progress resets at that moment and the completion count moves then. So completion
 * of a round with a payout still waiting records the state and stops — no cleared tasks, no moved
 * count, no started clock — and the claim that collects the last of it ends the round. A round
 * with nothing to wait for ends at completion instead, because no claim will ever arrive.
 */
@DisplayName("a repeatable round's two halves")
class QuestProgressTest {

    private static QuestProgress completedWithPayoutWaiting() {
        // A repeatable completion whose reward nobody has collected: tasks still maxed, claims
        // empty, clock and count untouched.
        return QuestProgress.NONE.recordTask(0, 8).completed();
    }

    @Test
    @DisplayName("completion with a payout waiting records the state and nothing else")
    void completionKeepsTasksCountAndClock() {
        QuestProgress recorded = completedWithPayoutWaiting();

        assertEquals(QuestState.COMPLETED, recorded.state());
        assertEquals(8, recorded.progressOf(0), "the maxed tasks stay maxed");
        assertEquals(0, recorded.timesCompleted(), "the count moves at the final claim, not here");
        assertEquals(0L, recorded.lastCompletedAt(), "and the cooldown has not started");
        assertFalse(recorded.rewardsClaimed(), "the round is still waiting on its payout");
    }

    @Test
    @DisplayName("the final claim resets tasks, moves the count and starts the clock")
    void finalClaimEndsTheRound() {
        QuestProgress ended = completedWithPayoutWaiting().repeatRoundOver(6_000L);

        assertEquals(QuestState.COMPLETED, ended.state());
        assertEquals(0, ended.progressOf(0), "the next round starts clean");
        assertEquals(1, ended.timesCompleted());
        assertEquals(6_000L, ended.lastCompletedAt());
        assertTrue(ended.rewardsClaimed(), "the round is over");

        assertTrue(ended.cooldownElapsed(6_000L + 100, 100), "100 ticks later the cooldown has run");
        assertFalse(ended.cooldownElapsed(6_000L + 50, 100), "but not after 50");
        assertEquals(50L, ended.cooldownRemaining(6_000L + 50, 100));
    }

    @Test
    @DisplayName("the ended round keeps its collected claims, so nothing is paid twice")
    void endedRoundKeepsClaims() {
        java.util.UUID player = java.util.UUID.randomUUID();
        QuestProgress collected = completedWithPayoutWaiting()
                .withClaims(QuestClaims.NONE.withPlayerClaim(player, 0));
        QuestProgress ended = collected.repeatRoundOver(6_000L);

        assertTrue(ended.claimed(player, 0, false),
                "the collection record survives the reset, so the payout cannot be claimed again");
    }

    @Test
    @DisplayName("task progress only ever records upwards")
    void progressIsMonotonic() {
        QuestProgress progress = QuestProgress.NONE.recordTask(0, 3).recordTask(0, 8);
        assertEquals(8, progress.progressOf(0));
        assertEquals(Map.of(0, 8), progress.recordTask(0, 2).taskProgress(),
                "a lower re-record must not move the count backwards");
    }
}
