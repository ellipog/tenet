package dev.ellipog.tenet.progress;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * One team's progress through one quest.
 *
 * <h2>Task progress is keyed by position, and that is a real limitation</h2>
 *
 * <p>{@code taskProgress} maps a task's index to how far along it is. Reordering the tasks in a quest
 * file therefore moves progress to a different task — insert a task at the top and every existing
 * progress value shifts down one.
 *
 * <p>Keyed this way anyway, because the alternative is worse. Giving every task an author-chosen id
 * means every task in every file needs an id, forever, and the files get noisier for a case that
 * almost never comes up. Keying by a hash of the task's content is worse still: editing a count
 * would silently orphan the progress.
 *
 * <p>What actually happens in practice is that reordering a quest's tasks resets partial progress in
 * that one quest, and the next time a player does anything the progress recomputes from their
 * inventory. Nothing is lost that was not derivable. The failure mode is mild enough to be worth the
 * simpler file — but it is a real trade, so it is written down rather than discovered.
 *
 * <h2>Progress is the team's; a payout is the player's</h2>
 *
 * <p>{@code taskProgress} is team-wide, and {@link #claims} is where the per-player side lives: who
 * has collected which reward. See {@link QuestClaims} for the rule that decides whose claim a given
 * reward reads.
 *
 * <p>{@code rewardsClaimed} is not "everyone has collected everything" — that is not a fact a
 * per-player model can settle globally, and it would fight the repeatable quest that needs an answer.
 * It means <b>the completion round is over</b>: set when a repeatable quest's payout was collected
 * by the player who claimed it, and what {@code canComplete} reads before starting another round.
 * {@code legacySettled} is the migration-only cousin: a quest a pre-per-player build recorded as
 * collected reads as collected for everyone, so no old save can be paid twice.
 *
 * @param state           how far it has got
 * @param taskProgress    task index to how far along that task is
 * @param claims          per-player and per-team reward provenance. See {@link QuestClaims}
 * @param rewardsClaimed  whether this round's payout is over (see the class note)
 * @param legacySettled   a pre-per-player save's "collected": collected for everyone, for good
 * @param timesCompleted  how many times a repeatable quest has been finished
 * @param lastCompletedAt game time in ticks of the last completion, for a cooldown
 */
public record QuestProgress(QuestState state,
                            Map<Integer, Integer> taskProgress,
                            QuestClaims claims,
                            boolean rewardsClaimed,
                            boolean legacySettled,
                            int timesCompleted,
                            long lastCompletedAt) {

    /** Nothing done. What a quest with no stored progress looks like. */
    public static final QuestProgress NONE =
            new QuestProgress(QuestState.LOCKED, Map.of(), QuestClaims.NONE, false, false, 0, 0L);

    public QuestProgress {
        taskProgress = Map.copyOf(taskProgress);
    }

    /** How far along task {@code index} is. Zero if nothing recorded. */
    public int progressOf(int index) {
        return taskProgress.getOrDefault(index, 0);
    }

    /** Whether reward {@code index} is settled, by the rule the reward's own team flag asks for. */
    public boolean claimed(UUID player, int index, boolean teamReward) {
        return claims.claimed(player, index, teamReward);
    }

    public QuestProgress withState(QuestState state) {
        return new QuestProgress(state, taskProgress, claims, rewardsClaimed, legacySettled,
                timesCompleted, lastCompletedAt);
    }

    /** Records progress only if it improved. Progress never goes backwards — see {@link #recordTask}. */
    public QuestProgress recordTask(int index, int amount) {
        int current = progressOf(index);
        if (amount <= current) {
            return this;
        }
        Map<Integer, Integer> next = new LinkedHashMap<>(taskProgress);
        next.put(index, amount);
        return new QuestProgress(state, next, claims, rewardsClaimed, legacySettled, timesCompleted,
                lastCompletedAt);
    }

    /**
     * Adds to recorded progress, for the event-driven types.
     *
     * <p>{@link #recordTask} sets an absolute amount, which is what a type that can be asked "how
     * much do you have now" wants. A kill is not that: the death happened once, and the only thing
     * that knows how much progress it is worth is the event that saw it. So this adds — always
     * upwards, because the monotonic rule is what stops a consuming task un-completing itself.
     */
    public QuestProgress addTask(int index, int delta) {
        return delta <= 0 ? this : recordTask(index, progressOf(index) + delta);
    }

    public QuestProgress withClaims(QuestClaims next) {
        return new QuestProgress(state, taskProgress, next, rewardsClaimed, legacySettled,
                timesCompleted, lastCompletedAt);
    }

    public QuestProgress withRewardsClaimed(boolean claimed) {
        return new QuestProgress(state, taskProgress, claims, claimed, legacySettled, timesCompleted,
                lastCompletedAt);
    }

    /**
     * Clears task progress and the round's claims, counting the round, for a repeatable quest with
     * nothing to wait for — no rewards at all. A round with a payout still to collect must not take
     * this path: clearing its tasks at completion would erase what "completed" means to look at,
     * and wiping its claims would re-offer nothing. See {@link #completed} and
     * {@link #repeatRoundOver} for that round's two halves.
     */
    public QuestProgress resetTasks() {
        return new QuestProgress(state, Map.of(), QuestClaims.NONE, false, false, timesCompleted + 1,
                lastCompletedAt);
    }

    /**
     * Marks completed without touching the clock or the count.
     *
     * <p>For a repeatable round whose payout is still waiting: the timer starts when the last
     * unclaimed reward is claimed, not when the quest completes, and the count moves then too. So
     * completion records the state and stops — see {@link #repeatRoundOver} for the other half.
     */
    public QuestProgress completed() {
        return new QuestProgress(QuestState.COMPLETED, taskProgress, claims, rewardsClaimed,
                legacySettled, timesCompleted, lastCompletedAt);
    }

    /**
     * Ends a repeatable round whose payout is now fully collected.
     *
     * <p>the legacy rule: the repeat timer starts when the last unclaimed reward is claimed, the
     * quest's progress resets at that moment, and the completion count moves then. Task progress
     * is cleared and the clock restarts — but the claims stay marked, because "collected" is still
     * true and wiping it would offer the same payout again. The next completion starts its round
     * with fresh claims; see the completion path.
     */
    public QuestProgress repeatRoundOver(long gameTime) {
        return new QuestProgress(state, Map.of(), claims, true, legacySettled, timesCompleted + 1,
                gameTime);
    }

    public QuestProgress completedAt(long gameTime) {
        return new QuestProgress(QuestState.COMPLETED, taskProgress, claims, rewardsClaimed,
                legacySettled, timesCompleted, gameTime);
    }

    /**
     * Whether a repeatable quest's cooldown has elapsed.
     *
     * @param now        game time in ticks
     * @param cooldownTicks how long the cooldown is; zero means there is none
     */
    public boolean cooldownElapsed(long now, int cooldownTicks) {
        if (cooldownTicks <= 0) {
            return true;
        }
        return now - lastCompletedAt >= cooldownTicks;
    }

    /** Ticks still to wait, or zero. For a message that says how long rather than just "no". */
    public long cooldownRemaining(long now, int cooldownTicks) {
        if (cooldownTicks <= 0) {
            return 0L;
        }
        return Math.max(0L, cooldownTicks - (now - lastCompletedAt));
    }

    /** Whether any task has been touched. Used to decide between UNLOCKED and STARTED. */
    public boolean anyTaskProgress() {
        return taskProgress.values().stream().anyMatch(value -> value > 0);
    }
}
