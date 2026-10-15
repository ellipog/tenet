package dev.ellipog.tenet.client;

import dev.ellipog.armature.api.net.ArmatureNetwork;
import dev.ellipog.tenet.client.dev.QuestWalks;
import dev.ellipog.tenet.net.SubmitTaskPayload;
import dev.ellipog.tenet.progress.QuestState;
import dev.ellipog.tenet.quest.task.ObservationTask;

import net.minecraft.client.Minecraft;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Watches the crosshair for observation tasks and submits when one has been looked at long enough.
 *
 * <h2>Why this is the client's job, and the only one</h2>
 *
 * <p>A server never sees where a player is looking, so an observation task can only be judged where
 * the eyes are. Legacy packs do the same thing, and the trust is deliberate and bounded: the worst a
 * modified client can do is finish an observation without watching, which grants nothing by itself.
 * Everything a payout depends on is still decided server-side.
 *
 * <p>Called once per client tick from each loader's tick hook — the only two seams that exist, since
 * Armature has no client-tick event. The counters are per session and in memory: what the server keeps
 * is the completed task, recorded in the ordinary progress record.
 *
 * <h2>What the tick walks, and what it used to</h2>
 *
 * <p>It used to walk <b>every quest and every task in the book</b>, every tick, to find the handful of
 * tasks that can be observed at all — and it did that with no screen open, which is to say it was paid
 * by a player who was mining. The cost scaled with the size of the pack and bought nothing: a task's
 * <i>kind</i> cannot change unless the tree does, so most of the walk asked a question whose answer had
 * not moved since the last reload.
 *
 * <p>So the tasks that can be observed are gathered once per tree revision and the tick walks that
 * list. Two things about the split are load-bearing rather than tidiness:
 *
 * <ul>
 *   <li><b>The list holds only what the tree can move</b> — where the task is, what to watch, and for
 *       how long. Everything about <i>this player right now</i> — finished, already counted, or shut by
 *       a condition — stays in the tick, where it is read as it always was.</li>
 *   <li><b>It is keyed on the tree revision and not on progress</b>, and that follows from the point
 *       above: a list holding no per-player fact has no reason to be rebuilt when a player's progress
 *       moves. Keying it on both would rebuild it on every progress message — which is a whole-book
 *       walk per message, most of the cost this exists to remove, arriving by a different road.</li>
 * </ul>
 *
 * <p>The invariant that makes that safe: <b>this list is never more stale than the state the tick reads
 * beside it.</b> The per-player reads ({@code taskLockOf} and its neighbours) come from the same cache
 * the list's own contents do, and the tick asks them fresh every time — so a task whose stage or
 * condition changed is judged by the current answer, exactly as before, whatever the list holds.
 */
public final class ObservationWatcher {

    private ObservationWatcher() {
    }

    /** How many consecutive ticks each observation task has been watched for, keyed quest#task. */
    private static final Map<String, Integer> HELD = new HashMap<>();

    /**
     * One task that can be observed at all, as the tree describes it.
     *
     * <p>Package-private with {@link #watchables()} so a test can read the list without a client: the
     * tick itself needs a {@code Minecraft} and a crosshair, which is the half of this class that no
     * test can reach — and the list is the half that decides the tick's cost.
     */
    record Watchable(String questId, int index, ObservationTask.ObserveType kind, String target,
                     int ticks) {
    }

    /** The list, and the tree revision it was read from. See this class's note on the key. */
    private record Watchables(long tree, List<Watchable> tasks) {
    }

    private static Watchables watchables;

    /**
     * The tasks that can be observed, built from the tree once per revision and kept until it moves.
     *
     * <p>Package-private for the reason {@link Watchable} is.
     */
    static List<Watchable> watchables() {
        long tree = ClientQuestCache.treeRevision();
        Watchables current = watchables;
        if (current != null && current.tree() == tree) {
            return current.tasks();
        }

        List<Watchable> built = new ArrayList<>();
        int entries = 0;
        for (ClientQuestCache.Entry entry : ClientQuestCache.entries()) {
            entries++;
            String questId = entry.id();
            for (int index = 0; index < entry.tasks().size(); index++) {
                ClientQuestCache.TaskEntry task = entry.tasks().get(index);
                ObservationTask.ObserveType kind = task.observation();
                if (kind == null) {
                    continue;
                }
                built.add(new Watchable(questId, index, kind, task.observeTarget(), task.observeTicks()));
            }
        }
        List<Watchable> tasks = List.copyOf(built);
        watchables = new Watchables(tree, tasks);
        // Two numbers, because a rebuild and a tick are different costs and only one of them is per
        // tick: this one happens once per reload, and the tick's is the one that must not scale.
        QuestWalks.walked("observation index", entries);
        return tasks;
    }

    /** One client tick: what is under the crosshair, and for how long it has been there. */
    public static void tick(Minecraft minecraft) {
        if (minecraft == null || minecraft.player == null || minecraft.level == null) {
            HELD.clear();
            return;
        }
        // The open screen moved to `Gui.screen()`: `Minecraft.screen` is gone.
        if (minecraft.gui.screen() != null || minecraft.hitResult == null) {
            // A menu open means the crosshair is stale; looking away is not watching, and the count
            // restarts rather than being kept warm.
            HELD.clear();
            return;
        }

        // Counted rather than assumed: see QuestWalks, whose number is what says whether this tick
        // still scales with the pack.
        int walked = 0;
        for (Watchable task : watchables()) {
            walked++;
            String questId = task.questId();
            int index = task.index();
            if (!eligible(questId, index)) {
                // A locked task is not submitted, for the same reason it has no Submit button: the
                // server refuses it, so sending anyway would tell the player their conditions are
                // unmet once per watching cycle for as long as they keep looking.
                continue;
            }
            String key = questId + "#" + index;
            if (!ObservationTask.matches(minecraft.hitResult, minecraft.level, task.kind(),
                    task.target())) {
                HELD.remove(key);
                continue;
            }
            int held = HELD.merge(key, 1, Integer::sum);
            if (held >= Math.max(1, task.ticks())) {
                HELD.remove(key);
                // The server accepts this because the type says it may; see TaskBehaviour and
                // ProgressService.submit. No button is involved.
                ArmatureNetwork.sendToServer(new SubmitTaskPayload(questId, index));
            }
        }
        QuestWalks.walked("observation tick", walked);
    }

    /**
     * Whether this task is one the tick has any business watching the crosshair for.
     *
     * <h2>Three questions, and the fourth that is deliberately not asked</h2>
     *
     * <p>Finished, already counted, or shut by one of this player's conditions. Those are the three
     * the tick has always asked, moved here unchanged so that a test can hold them rather than a reader
     * having to believe a transcription.
     *
     * <p>It does <b>not</b> ask whether the quest's own prerequisites are met, and that is not an
     * omission: the check has never been here. A quest that is still locked by a prerequisite is
     * watched for exactly as it was before this class gained a list, and the server is what refuses a
     * submission it does not want. Adding that question here would be a change in behaviour wearing the
     * clothes of a tidy-up, which is the kind of change this codebase has a rule about.
     *
     * <p>Answered from the live cache rather than from the list, because every one of the three moves
     * with progress and the list is rebuilt only when the tree does.
     */
    static boolean eligible(String questId, int index) {
        return ClientQuestCache.stateOf(questId) != QuestState.COMPLETED
                && ClientQuestCache.taskProgressOf(questId, index) < 1
                && ClientQuestCache.taskLockOf(questId, index).isEmpty();
    }

    /**
     * Forgets every counter, and the list with them.
     *
     * <p>For a disconnect: another server's tasks are not this one's. The list would invalidate itself
     * through the tree revision, which {@code clear()} moves — but a list left holding the previous
     * server's trees until the next one arrives is still a list holding somebody else's quests, and
     * forgetting it here costs one assignment.
     */
    public static void reset() {
        HELD.clear();
        watchables = null;
    }
}
