package dev.ellipog.tenet.progress;

import dev.ellipog.tenet.Constants;
import dev.ellipog.tenet.api.TenetEvents;
import dev.ellipog.tenet.party.PartyMode;
import dev.ellipog.tenet.party.PartyStore;
import dev.ellipog.tenet.quest.Chapter;
import dev.ellipog.tenet.quest.Quest;
import dev.ellipog.tenet.quest.QuestIndex;
import dev.ellipog.tenet.quest.QuestReward;
import dev.ellipog.tenet.quest.QuestSettings;
import dev.ellipog.tenet.quest.QuestTask;
import dev.ellipog.tenet.quest.TaskContext;
import dev.ellipog.tenet.quest.TenetQuests;
import dev.ellipog.tenet.quest.condition.ConditionContext;
import dev.ellipog.tenet.quest.condition.Conditions;
import dev.ellipog.tenet.quest.condition.QuestCondition;
import dev.ellipog.tenet.inventory.InventoryAccesses;
import dev.ellipog.tenet.net.ClaimChoiceResultPayload;
import dev.ellipog.tenet.net.ClaimSummaryPayload;
import dev.ellipog.tenet.net.RewardOverflowPayload;
import dev.ellipog.tenet.quest.reward.ItemReward;
import dev.ellipog.tenet.quest.reward.RewardAutoClaim;
import dev.ellipog.tenet.quest.reward.RewardContext;
import dev.ellipog.tenet.quest.reward.RewardFeedback;
import dev.ellipog.tenet.quest.reward.RewardTypes;
import dev.ellipog.tenet.quest.reward.TableReward;
import dev.ellipog.tenet.quest.task.BreakTask;
import dev.ellipog.tenet.quest.task.KillTask;
import dev.ellipog.tenet.quest.task.TaskTypes;

import dev.ellipog.armature.api.net.ArmatureNetwork;
import dev.ellipog.armature.api.teams.Team;
import dev.ellipog.armature.api.teams.Teams;

import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Everything that changes quest progress.
 *
 * <h2>The one place that writes</h2>
 *
 * <p>Task behaviours can only <i>observe</i>; rewards can only <i>give</i>. Recording, unlocking and
 * completing all happen here, so there is one implementation of "what it means to finish a quest"
 * and one place to look when progress goes wrong. A task that could write its own progress would
 * make that impossible to reason about.
 *
 * <h2>Who progress belongs to</h2>
 *
 * <p>Never a player — always a <b>team</b>. A solo player is in a team of one, whose id is their own
 * UUID (see {@code Team.solo}). So there is no branch anywhere here for "playing alone", and the same
 * code path serves both cases. Two players in a party share one {@link TeamProgress}, because both
 * look theirs up by the team's id and the key is the whole of it.
 *
 * <h2>Sharing the store was only half of it</h2>
 *
 * <p>This class used to say the plan's clause — <i>"two players on a dedicated server share progress
 * correctly"</i> — "falls out rather than needing to be built". That was true of the storage and
 * <b>false of the counting</b>, which is the half nobody looks at. {@link #tick} chose one member per
 * team and {@link #evaluateTeam} read only that member's inventory, so a party's quest completed for
 * whichever of them the server happened to list first and never for the other. See
 * {@code evaluateTeam} for the full note. {@code QuestPlaythroughTest} now gathers in each direction
 * in turn, precisely so the answer cannot depend on which member is chosen.
 *
 * <p><b>A party now chooses how it counts, and the maximum is one of three modes rather than the
 * rule.</b> {@link PartyMode} is the choice — the largest single member, the members added together,
 * or the owner alone — and {@code evaluateTeam} applies it. That paragraph above used to end by
 * saying the party chose nothing and that counting as a maximum was "the least that makes sharing
 * true", which was the right answer while a counting bug was being fixed and the wrong one
 * afterwards: a rule about who pays is not something to invent while fixing arithmetic, but neither
 * is it something to leave as an accident of which member the loop happened to ask first.
 *
 * <p>A mode changes the <i>count</i> and, for {@link PartyMode#POOLED}, the paying too — since a
 * count spread across four inventories cannot be settled by reaching into one of them. See
 * {@link PartyMode} on why it lives in Tenet rather than in Armature, and {@code consumeAcross}
 * below on the half of it that a count alone cannot express.
 *
 * <h2>Finishing a quest and collecting its reward are two calls</h2>
 *
 * <p>{@link #complete} records the completion and nothing else; {@link #claim} is the only thing in
 * the mod that hands a reward over. So a quest can sit finished-but-uncollected for as long as the
 * player likes, across a relog, and the reward survives it — which is what "the book shows a Claim
 * button" is built on, and why the state is two fields rather than one.
 *
 * <h2>Evaluation cost</h2>
 *
 * <p>Resolving every quest's state is a walk over a few hundred nodes with a memoised map — cheap, and
 * it happens once per team per tick that anything needs it. Task evaluation is the expensive half,
 * because an item task walks an inventory: so each task is only evaluated when its own
 * {@code autoSubmitTicks} has elapsed, tracked per team in memory. Nothing about evaluation timing is
 * persisted, because losing it costs one extra evaluation after a restart and nothing else.
 */
public final class ProgressService {

    /**
     * When each task was last evaluated, keyed by team and then "questId#taskIndex".
     *
     * <p>In memory, and that is a choice rather than an oversight: it is a scheduling detail, not a
     * fact about the world. Persisting it would add a write per task per interval for a value whose
     * worst case on loss is one duplicate evaluation.
     */
    private static final Map<UUID, Map<String, Long>> LAST_EVALUATED = new LinkedHashMap<>();

    /**
     * Each member's own count toward a task, as of the last time that task was evaluated.
     *
     * <h2>Why the engine keeps this at all</h2>
     *
     * <p>Because it was already computing it and throwing it away. The loop below asks every member what
     * they are holding toward a task, so that the party's mode can add the answers up — and the list it
     * adds up was the only record of <i>who</i> was carrying what, discarded the moment the total was
     * known. So "Ellio has four of the eight logs" was known to the server every second and told to
     * nobody, which is why a quest book could say how much a party had collected and never who
     * collected it.
     *
     * <p>Keyed the way {@code LAST_EVALUATED} is, and for the same reasons: by team, then by the
     * task's {@code quest#index} key. Live numbers rather than stored progress — they go <b>down</b> as
     * well as up, because they are inventories — so they are not saved and they are not part of
     * {@code TeamProgress}.
     *
     * <p>A member's entry is present while they hold something, and a task's picture <b>outlives the
     * task</b>: once a task is satisfied it is never evaluated again, so the last picture stands as the
     * record of who did the work — which for a consuming task is the only record there could be, since
     * the items are taken and every member's count falls to zero on the next tick.
     */
    private static final Map<UUID, Map<String, Map<UUID, Integer>>> CONTRIBUTIONS =
            new LinkedHashMap<>();

    /**
     * The live count of the tasks that wait for a press, per team.
     *
     * <p>A task that takes what it asks for is <b>not recorded by the tick</b> — see
     * {@code TaskBehaviour#waitsForSubmit} — so the row's "5 of 8" has to come from somewhere, and
     * this is it: the number the engine just combined under the party's own mode, which is the
     * number it would take.
     *
     * <p>Only a waiting task has an entry, so a reader can ask for a task's live count without
     * asking what kind of task it is looking at; zero is the honest answer for everything else.
     * Live, like {@link #CONTRIBUTIONS} and for the same reason: it is inventories, so it goes down
     * as well as up and is not part of {@code TeamProgress}.
     */
    private static final Map<UUID, Map<String, Integer>> LIVE = new LinkedHashMap<>();

    /**
     * The server and tick the last full evaluation ran on.
     *
     * <p>Needed because the hook is a <i>player</i> tick: with four players online it fires four times
     * per tick, evaluating every team four times over. The work is idempotent, so that would be
     * wasteful rather than wrong — but four inventory scans a second per quest, per player, is exactly
     * the kind of thing that gets a mod blamed for lag it did not cause.
     */
    private static MinecraftServer lastEvaluatedServer;
    private static long lastEvaluatedTick = Long.MIN_VALUE;

    private ProgressService() {
    }

    // ------------------------------------------------------------------
    // Reading
    // ------------------------------------------------------------------

    /** The team a player's progress belongs to. */
    /** A task's key in the evaluator's maps: the quest, and the task's position in it. */
    private static String keyOf(dev.ellipog.tenet.quest.Quest quest, int taskIndex) {
        return keyOf(quest.id(), taskIndex);
    }

    /**
     * The same key, from a quest's id, for a caller that has no {@code Quest} to hand.
     *
     * <p>Remembered rather than rebuilt: the key is asked per task per team per tick whether the
     * task is due or not, and a quest id plus an index spell exactly one string. Bounded by the
     * pack rather than by a cap — one entry per quest task the files name.
     */
    private static final Map<String, Map<Integer, String>> TASK_KEYS = new ConcurrentHashMap<>();

    private static String keyOf(String questId, int taskIndex) {
        Map<Integer, String> perQuest = TASK_KEYS.get(questId);
        String key = perQuest == null ? null : perQuest.get(taskIndex);
        if (key != null) {
            return key;
        }
        return buildKey(questId, taskIndex);
    }

    /** The miss half of {@link #keyOf}, synchronised so two teams cannot build it twice. */
    private static synchronized String buildKey(String questId, int taskIndex) {
        return TASK_KEYS.computeIfAbsent(questId, id -> new ConcurrentHashMap<>())
                .computeIfAbsent(taskIndex, i -> questId + "#" + i);
    }

    /**
     * Who is holding what toward a task, as the progress sync asks it.
     *
     * <h2>Why a function rather than the map itself</h2>
     *
     * <p>Because the key a picture is filed under — {@code quest#index} — is this class's business, and a
     * caller that built the same string for itself would be the second place that knows the format. So
     * the sync asks a question in the terms it has ("who is contributing to task 3 of this quest") and
     * the keying stays here.
     */
    @FunctionalInterface
    public interface Contributors {
        Map<UUID, Integer> of(String questId, int taskIndex);
    }

    /** The contributors of one team's tasks, captured when it is asked for. */
    public static Contributors contributors(UUID owner) {
        if (owner == null) {
            return (questId, taskIndex) -> Map.of();
        }
        Map<String, Map<UUID, Integer>> mine = CONTRIBUTIONS.getOrDefault(owner, Map.of());
        return (questId, taskIndex) -> mine.getOrDefault(keyOf(questId, taskIndex), Map.of());
    }

    /**
     * The live count of one team's waiting tasks, as the progress sync asks it.
     *
     * <p>Keyed the way {@link #contributors} is, and for the same reason: the {@code quest#index}
     * key is this class's business. Zero for a task that is not waiting, which is the honest answer
     * — its count is recorded progress and needs no second reading.
     */
    @FunctionalInterface
    public interface Live {
        int of(String questId, int taskIndex);
    }

    /** The live counts of one team's waiting tasks, captured when it is asked for. */
    public static Live live(UUID owner) {
        if (owner == null) {
            return (questId, taskIndex) -> 0;
        }
        Map<String, Integer> mine = LIVE.getOrDefault(owner, Map.of());
        return (questId, taskIndex) -> mine.getOrDefault(keyOf(questId, taskIndex), 0);
    }

    public static UUID progressOwner(MinecraftServer server, ServerPlayer player) {
        return Teams.teamOf(server, player.getUUID()).id();
    }

    /**
     * Keeps, in a player's own record, everything the party they are leaving had done.
     *
     * <h2>Why leaving does this, when joining deliberately does not</h2>
     *
     * <p>The two are asymmetric on purpose, and both directions are the promised behaviour rather than
     * an oversight. <b>Joining merges nothing</b>: importing a player's solo record would hand a fresh
     * party a finished questline, which is why {@code ProgressStore} has always refused it. <b>Leaving
     * merges the party's progress into the leaver's own record</b>, so quest nodes earned together are
     * not lost -- the alternative soft-locks a progression-gated pack the moment somebody goes solo.
     *
     * <p>It is a copy, not a move: the party's record stays exactly where it was, under the team id,
     * for the members still in it. See {@link ProgressMerge} for what travels and what does not.
     *
     * <p>Called for every reason a membership ends -- left by choice, removed, or the party dissolved
     * -- because the question this answers is "what did this player earn while they were here", and
     * the answer does not change with how it ended. A kick is a statement about behaviour; confiscating
     * quest nodes is not a moderation tool, and the command that removes somebody already says so.
     *
     * @param teamId the party being left. A solo record keyed by the player's own id is a no-op
     */
    public static void retainFor(MinecraftServer server, UUID player, UUID teamId) {
        if (teamId == null || player == null || teamId.equals(player)) {
            // Their own record already, so there is nothing a party holds that they do not.
            return;
        }
        ProgressStore store = ProgressStore.of(server);
        TeamProgress party = store.progressOf(teamId);
        if (party.size() == 0) {
            // An empty party record -- a party whose members never made progress -- merges to the
            // player's own record unchanged, and skipping the write keeps the store clean.
            return;
        }
        store.put(player, ProgressMerge.merge(store.progressOf(player), party, player));
    }

    /** Every quest's state, for whoever's progress this is. */
    public static ProgressionEngine.Resolution resolutionFor(MinecraftServer server, UUID owner) {
        QuestIndex index = TenetQuests.index();
        TeamProgress progress = ProgressStore.of(server).progressOf(owner);
        return ProgressionEngine.resolve(index, progress, server.overworld().getGameTime());
    }

    public static TeamProgress progressFor(MinecraftServer server, UUID owner) {
        return ProgressStore.of(server).progressOf(owner);
    }

    // ------------------------------------------------------------------
    // Evaluation
    // ------------------------------------------------------------------

    /**
     * Evaluates every online team's tasks and completes any quest that is now finished.
     *
     * <p>Called once per player tick. Almost every call does nothing, because each task has its own
     * interval and most will not be due — an item task defaulting to every twenty ticks means one
     * inventory scan per second per relevant quest, which is nothing. Raising a task's
     * {@code autoSubmitTicks} is the knob for an expensive one.
     *
     * <h2>Why this returns anything</h2>
     *
     * <p>Because the automatic half of the engine had no way to reach a client. This used to return
     * {@code void}, and the only code anywhere that pushed progress to a player was the handler for
     * <i>pressing Submit</i> — so submitting a task updated the screen and nothing else did. Gathering
     * eight oak logs completed the quest on the server, granted its reward and printed the completion
     * message, while the book went on showing {@code 0 / 8} for the rest of the session. A player who
     * then threw the logs on the ground and picked them up again was doing the sensible thing and
     * could not have made it work, because it was the display that was frozen.
     *
     * @return the ids of the teams whose progress actually <b>changed</b>. Empty on almost every tick,
     *     and that is what makes "tell the players when it moves" cost nothing rather than a packet
     *     per player per tick.
     */
    public static Set<UUID> tick(MinecraftServer server) {
        QuestIndex index = TenetQuests.index();
        if (index.isEmpty()) {
            return Set.of();
        }

        long now = server.overworld().getGameTime();

        // The hook is a player tick, so this fires once per player. Everything below is per team, and
        // most players are alone or in one party -- so without this guard a party of four would have
        // its shared progress evaluated four times every tick.
        //
        // The second and later callers in a tick get "nothing changed", which is correct rather than
        // merely harmless: the first call evaluated every team and returned every team that moved, so
        // by the time a second player's tick arrives there is genuinely nothing left to report. Saying
        // so is what stops the same sync being sent four times a tick to a party of four.
        if (server == lastEvaluatedServer && now == lastEvaluatedTick) {
            return Set.of();
        }

        // A different world, so every recorded evaluation time is now in that world's future.
        //
        // `now` is per-world game time and starts again near zero. LAST_EVALUATED lives for the life
        // of the *process* and is keyed by team id -- and a solo team's id is the player's own UUID,
        // which does not change between worlds. So loading a second world in one session leaves the
        // first world's entries behind, and `now - lastAt` is a large *negative* number, which the
        // due check below reads as "evaluated a moment ago".
        //
        // The consequence is that every task in the new world is skipped until its clock catches up
        // with the old one's -- and for exactly as long as the previous world was played for. Minutes
        // of gathering items with nothing happening, and which world behaves that way depends only on
        // the order they were opened in.
        //
        // Which is the reported symptom verbatim: "it worked in one world and not the other, and it
        // seems inconsistent at the very least". It is not inconsistent -- it is the second world.
        if (server != lastEvaluatedServer) {
            LAST_EVALUATED.clear();
            // And the pictures, which are per-world in exactly the same way: a team id is stable across
            // worlds, so a stale picture would be attributed to the new world's party.
            CONTRIBUTIONS.clear();
            // And the live counts of the waiting tasks, which are inventories too.
            LIVE.clear();
        }

        lastEvaluatedServer = server;
        lastEvaluatedTick = now;

        // One pass per team, not per player: two members of a party share progress, so evaluating it
        // twice per tick would do the same work again and could race on the same stored value.
        //
        // Keyed by owner id only, and the team's members are looked up inside evaluateTeam rather
        // than one of them being chosen here. This used to be a map from owner to a *representative
        // member*, filled with putIfAbsent -- which reads as a harmless optimisation and was the
        // whole of the party bug. See evaluateTeam.
        Set<UUID> owners = new LinkedHashSet<>();
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            owners.add(progressOwner(server, player));
        }

        Set<UUID> changed = new LinkedHashSet<>();
        for (UUID owner : owners) {
            if (evaluateTeam(server, index, owner, now)) {
                changed.add(owner);
            }
        }
        return java.util.Collections.unmodifiableSet(changed);
    }

    // ------------------------------------------------------------------
    // Event-driven progress
    // ------------------------------------------------------------------

    /** One kill-task task, with where it lives: the work list for the death event. */
    private record KillSite(QuestIndex.QuestEntry entry, int taskIndex, KillTask task) {
    }

    private static QuestIndex killSiteIndex;
    private static List<KillSite> killSites = List.of();

    /**
     * The loaded tree's kill tasks, rebuilt when the index is replaced.
     *
     * <p>Rebuilt lazily against the index's own identity: a reload produces a new index, and nothing
     * else does. Without this list a death would walk every quest and every task in the book, which a
     * mob farm turns into real work; with it the walk is only the tasks that could care.
     */
    private static List<KillSite> killSites() {
        QuestIndex index = TenetQuests.index();
        if (index != killSiteIndex) {
            List<KillSite> found = new ArrayList<>();
            for (QuestIndex.QuestEntry entry : index.quests()) {
                List<QuestTask> tasks = entry.quest().tasks();
                for (int i = 0; i < tasks.size(); i++) {
                    if (tasks.get(i) instanceof KillTask kill) {
                        found.add(new KillSite(entry, i, kill));
                    }
                }
            }
            killSites = List.copyOf(found);
            killSiteIndex = index;
        }
        return killSites;
    }

    /**
     * A living thing died. Records kill-task progress for the player who killed it.
     *
     * <p>The event half of the engine, and the only one: everything else is polled. Progress lands in
     * the ordinary recorded ints -- so it persists, pools across a party and resets with a repeatable
     * round like everything else -- and the caller pushes the change to the team, because the tick
     * that normally does that would find recorded progress already satisfied and report nothing.
     *
     * @return the owners whose progress moved, for the caller to push
     */
    public static Set<UUID> onEntityDeath(MinecraftServer server, LivingEntity entity, DamageSource source) {
        List<KillSite> sites = killSites();
        if (sites.isEmpty() || !(source.getEntity() instanceof ServerPlayer killer) || killer.isSpectator()) {
            return Set.of();
        }

        UUID owner = progressOwner(server, killer);
        ProgressStore store = ProgressStore.of(server);
        TeamProgress working = store.progressOf(owner);
        long now = server.overworld().getGameTime();
        ProgressionEngine.Resolution resolution = ProgressionEngine.resolve(TenetQuests.index(), working, now);

        Map<QuestIndex.QuestEntry, QuestProgress> touched = new LinkedHashMap<>();
        boolean changed = false;
        // One context for the whole event rather than one per site: the killer, the server and the
        // owner cannot move between sites of the same death, so per-site contexts were one
        // allocation per task for an identical answer.
        ConditionContext death = new ConditionContext(killer, server, owner);
        for (KillSite site : sites) {
            Quest quest = site.entry().quest();
            if (!resolution.stateOf(quest).isPlayable()) {
                continue;
            }
            QuestProgress before = touched.getOrDefault(site.entry(), working.progressOf(quest));
            if (ProgressionEngine.isTaskSatisfied(quest, site.taskIndex(), before)) {
                continue;
            }
            // At event time, which is the only moment the kill is real: a condition that was unmet
            // when the mob died cannot be satisfied afterwards, so this is a gate on the act rather
            // than on a later tally.
            if (!Conditions.passes(site.task().common().conditions(), death)) {
                continue;
            }
            int delta = KillTask.BEHAVIOUR.onEntityDeath(site.task(), killer, entity);
            if (delta <= 0) {
                continue;
            }
            QuestProgress after = before.addTask(site.taskIndex(), delta);
            touched.put(site.entry(), after);
            changed = true;

            if (ProgressionEngine.isTaskSatisfied(quest, site.taskIndex(), after)) {
                TenetEvents.TASK_COMPLETED.invoker().onTaskCompleted(killer, quest, site.taskIndex());
            }
            if (!before.anyTaskProgress() && after.anyTaskProgress()) {
                TenetEvents.QUEST_STARTED.invoker().onQuestStarted(killer, quest);
            }
        }
        if (!changed) {
            return Set.of();
        }

        for (Map.Entry<QuestIndex.QuestEntry, QuestProgress> each : touched.entrySet()) {
            Quest quest = each.getKey().quest();
            if (ProgressionEngine.tasksSatisfied(quest, each.getValue()) && canComplete(quest, working)
                    && gateOpenForFlexible(TenetQuests.index(), each.getKey(), quest,
                            resolution.states())) {
                working = complete(server, owner, killer, each.getKey(), working.put(quest, each.getValue()));
            }
            else {
                working = working.put(quest, each.getValue());
            }
        }
        store.put(owner, working);
        return Set.of(owner);
    }

    /** One break-task task, with where it lives: the work list for the break event. */
    private record BreakSite(QuestIndex.QuestEntry entry, int taskIndex, BreakTask task) {
    }

    private static QuestIndex breakSiteIndex;
    private static List<BreakSite> breakSites = List.of();

    /**
     * The loaded tree's break tasks, rebuilt when the index is replaced.
     *
     * <p>Rebuilt lazily against the index's own identity: a reload produces a new index, and nothing
     * else does. Without this list a break would walk every quest and every task in the book, which a
     * quarry turns into real work; with it the walk is only the tasks that could care.
     */
    private static List<BreakSite> breakSites() {
        QuestIndex index = TenetQuests.index();
        if (index != breakSiteIndex) {
            List<BreakSite> found = new ArrayList<>();
            for (QuestIndex.QuestEntry entry : index.quests()) {
                List<QuestTask> tasks = entry.quest().tasks();
                for (int i = 0; i < tasks.size(); i++) {
                    if (tasks.get(i) instanceof BreakTask broken) {
                        found.add(new BreakSite(entry, i, broken));
                    }
                }
            }
            breakSites = List.copyOf(found);
            breakSiteIndex = index;
        }
        return breakSites;
    }

    /**
     * A block broke at a player's hand. Records break-task progress for that player.
     *
     * <p>The break half of the event engine, beside {@link #onEntityDeath}: everything else is
     * polled. Progress lands in the ordinary recorded ints — so it persists, pools across a party
     * and resets with a repeatable round like everything else — and the caller pushes the change
     * to the team, because the tick that normally does that would find recorded progress already
     * satisfied and report nothing.
     *
     * @return the owners whose progress moved, for the caller to push
     */
    public static Set<UUID> onBlockBroken(MinecraftServer server, ServerPlayer player,
                                          net.minecraft.world.level.block.state.BlockState state) {
        List<BreakSite> sites = breakSites();
        if (sites.isEmpty() || player.isSpectator()) {
            return Set.of();
        }

        UUID owner = progressOwner(server, player);
        ProgressStore store = ProgressStore.of(server);
        TeamProgress working = store.progressOf(owner);
        long now = server.overworld().getGameTime();
        ProgressionEngine.Resolution resolution = ProgressionEngine.resolve(TenetQuests.index(), working, now);

        Map<QuestIndex.QuestEntry, QuestProgress> touched = new LinkedHashMap<>();
        boolean changed = false;
        // One context for the whole event, like the death path beside it: the player, the server
        // and the owner are fixed for the break.
        ConditionContext broke = new ConditionContext(player, server, owner);
        for (BreakSite site : sites) {
            Quest quest = site.entry().quest();
            if (!resolution.stateOf(quest).isPlayable()) {
                continue;
            }
            QuestProgress before = touched.getOrDefault(site.entry(), working.progressOf(quest));
            if (ProgressionEngine.isTaskSatisfied(quest, site.taskIndex(), before)) {
                continue;
            }
            // At event time, which is the only moment the break is real: a condition that was unmet
            // when the block broke cannot be satisfied afterwards, so this is a gate on the act rather
            // than on a later tally.
            if (!Conditions.passes(site.task().common().conditions(), broke)) {
                continue;
            }
            int delta = BreakTask.BEHAVIOUR.onBlockBroken(site.task(), player, state);
            if (delta <= 0) {
                continue;
            }
            QuestProgress after = before.addTask(site.taskIndex(), delta);
            touched.put(site.entry(), after);
            changed = true;

            if (ProgressionEngine.isTaskSatisfied(quest, site.taskIndex(), after)) {
                TenetEvents.TASK_COMPLETED.invoker().onTaskCompleted(player, quest, site.taskIndex());
            }
            if (!before.anyTaskProgress() && after.anyTaskProgress()) {
                TenetEvents.QUEST_STARTED.invoker().onQuestStarted(player, quest);
            }
        }
        if (!changed) {
            return Set.of();
        }

        for (Map.Entry<QuestIndex.QuestEntry, QuestProgress> each : touched.entrySet()) {
            Quest quest = each.getKey().quest();
            if (ProgressionEngine.tasksSatisfied(quest, each.getValue()) && canComplete(quest, working)
                    && gateOpenForFlexible(TenetQuests.index(), each.getKey(), quest,
                            resolution.states())) {
                working = complete(server, owner, player, each.getKey(), working.put(quest, each.getValue()));
            }
            else {
                working = working.put(quest, each.getValue());
            }
        }
        store.put(owner, working);
        return Set.of(owner);
    }

    /**
     * Evaluates one team's tasks, and completes what is finished.
     *
     * <h2>Why {@code working} exists rather than reusing {@code progress}</h2>
     *
     * <p>{@link #complete} writes to the store, and the local variable does not see that write. So the
     * loop carries its own copy forward. Without that, completing one quest and then recording progress
     * on the next would write back a {@link TeamProgress} holding the <i>pre-completion</i> value for
     * the first quest — silently undoing it. The completion would appear to work, then vanish the next
     * time the player did anything.
     *
     * @return whether anything here actually moved — a task's recorded count went up, or a quest
     *     completed. False on the overwhelming majority of ticks, and that is what lets the caller skip
     *     the network entirely rather than sending a packet per player per tick to say nothing.
     */
    private static boolean evaluateTeam(MinecraftServer server,
                                        QuestIndex index,
                                        UUID owner,
                                        long now) {
        // Every online member, not one chosen member.
        //
        // This method used to take an `anyMember` picked by insertion order, and count item tasks in
        // that player's inventory alone. It is a fair reading of the name -- for a task that asks
        // whether *somebody* in the party has eight logs, who you ask looks like it should not
        // matter. It matters completely, and it is wrong in exactly the case parties exist for: one
        // of you gathers the wood, and the wood is in *their* inventory. Ask the other one and the
        // party sees nothing.
        //
        // The representative was also picked by whichever member the server listed first, which is
        // spawn order and therefore who logged in first. So the same party, doing the same thing,
        // would pass or fail depending on that -- and the failure looks like "the quest never
        // completes", which sends you to the quest file rather than to the loop.
        Team team = teamFor(server, owner);
        List<ServerPlayer> members = onlineMembersOf(server, team);
        if (members.isEmpty()) {
            return false;
        }

        // How this party wants those members' counts combined, and which of them the owner is.
        //
        // Read once for the whole pass rather than once per task. It is a map lookup either way, but
        // the property that matters is that one evaluation sees one rule: read inside the loop, a
        // command that changed the mode midway through would have some of a quest's tasks counted by
        // one mode and the rest by another, and a quest could then be completed by a rule that was
        // never in force for all of it.
        PartyMode mode = PartyStore.of(server).modeOf(owner);
        int ownerIndex = indexOfMember(members, team.owner());

        // One question per member for the whole pass, reused across tasks: the subjects, the
        // index and the tick cannot move between tasks of one evaluation, so per-task contexts
        // were two allocations per member per task for identical answers. See asksFor.
        MemberAsks asks = asksFor(server, owner, members, index, now);

        // Whether anything moved, for the caller's return value and for the write below.
        //
        // The write is the second reason this matters, and it was a separate quiet fault: the store
        // was written at the end of this method unconditionally, on every tick, for every online team.
        // `ProgressStore.put` calls `setDirty()`, so that marked the world's progress file for saving
        // twenty times a second, forever, whether or not a single quest had moved.
        boolean changed = false;

        ProgressStore store = ProgressStore.of(server);
        TeamProgress working = store.progressOf(owner);
        ProgressionEngine.Resolution resolution = ProgressionEngine.resolve(index, working, now);

        Map<String, Long> lastEvaluated = LAST_EVALUATED.computeIfAbsent(owner, key -> new LinkedHashMap<>());
        Map<String, Map<UUID, Integer>> contributions =
                CONTRIBUTIONS.computeIfAbsent(owner, key -> new LinkedHashMap<>());

        // the legacy `detection_delay`, read once per pass: the minimum ticks between inventory
        // checks. A pack that polls inventory every sixty ticks must not pay for a task that asked
        // every twenty — and a task that never reads an inventory keeps its own cadence whatever
        // the pack says, because a stat lookup is not an inventory walk. The default is twenty,
        // which is the interval it floors, so a pack that says nothing behaves exactly as before.
        int inventoryFloor = TenetQuests.settings().detectionDelay();

        for (QuestIndex.QuestEntry entry : index.quests()) {
            Quest quest = entry.quest();
            if (!resolution.stateOf(quest).isPlayable()) {
                continue;
            }

            QuestProgress questProgress = working.progressOf(quest);
            var chapter = chapterOf(index, entry);
            boolean chapterConsumes = chapter.map(Chapter::defaultConsumeItems).orElse(false)
                    || TenetQuests.settings().defaultConsumeItems();
            boolean chapterSequential = chapter.map(c -> c.rules().defaultSequentialTasks()).orElse(false);

            // Who the reward goes to if this quest finishes in this pass. Reassigned below to the
            // member whose inventory moved the quest forward, so a party pays the player who
            // gathered rather than a player chosen at random -- see the completion at the bottom.
            ServerPlayer earner = members.get(0);

            for (int taskIndex = 0; taskIndex < quest.tasks().size(); taskIndex++) {
                QuestTask task = quest.tasks().get(taskIndex);

                if (ProgressionEngine.isTaskSatisfied(quest, taskIndex, questProgress)) {
                    // Skipped, and that is all: the picture this task was last given <b>stays</b>.
                    //
                    // It is the only record anywhere of who did the work -- the counts themselves are
                    // inventories and go to zero the moment a consuming task takes the items -- and the
                    // evaluation that satisfied the task is the last one that saw them. So the map holds
                    // "who did what" for a finished task and "who is doing what" for a live one, from
                    // the same entry, with nothing to distinguish them because nothing needs to.
                    continue;
                }

                // A final local, because the lambda below cannot capture `questProgress`: it is
                // reassigned further down this loop, so it is not effectively final. Java refuses
                // that outright, and it is right to -- a deferred lambda reading a variable that
                // moves is a bug waiting to be written.
                //
                // A snapshot is also the correct reading rather than a workaround. isTaskUnlocked
                // runs the predicate synchronously, so the state it should judge against is exactly
                // the progress as it stands at this moment.
                final QuestProgress beforeThisTask = questProgress;
                if (!quest.isTaskUnlocked(taskIndex,
                        earlier -> ProgressionEngine.isTaskSatisfied(quest, earlier, beforeThisTask),
                        chapterSequential)) {
                    continue;
                }

                String key = keyOf(quest, taskIndex);
                int interval = task.common().autoSubmitTicks();

                Optional<dev.ellipog.tenet.quest.task.TaskBehaviour<QuestTask>> behaviour =
                        TaskTypes.behaviourOf(task);
                if (behaviour.isEmpty()) {
                    continue;
                }
                // Inventory-backed tasks wait at least the pack's detection delay between reads.
                if (behaviour.get().readsInventory()) {
                    interval = Math.max(interval, inventoryFloor);
                }
                // The scheduling decision, which belongs to `isDue` rather than to this loop. It is a
                // method because it went wrong twice, and both times the cause was arithmetic no test
                // could reach from here: an overflow, and a clock that goes backwards when a player
                // loads a second world in one session. Both are written up on `isDue`.
                if (!isDue(now, lastEvaluated.get(key), interval)) {
                    continue;
                }
                lastEvaluated.put(key, now);

                int required = behaviour.get().required(task);

                // Every member is asked, and the party's mode decides what the answers add up to.
                // A manual-only task is the exception: it is never measured here — zeros stand in for
                // the asking — so the tick publishes zero and records nothing, and the submit press is
                // the only path that reads the inventory. Without this the flag would be display-only
                // on consuming tasks (which already wait for the press) and meaningless on
                // presence-only ones the tick would register by itself.
                //
                // The asks used to be folded here, as a running maximum with a comment explaining why
                // max and not sum. That reasoning is now PartyMode.ONE_MEMBER's, and it is worth
                // keeping in view because it is still the default: max means "somebody in this party
                // has eight logs", which is what makes one player gathering work for both, where sum
                // means "the party's pooled logs come to eight" — a more generous rule where eight
                // members carrying one log each would finish a gather-eight quest.
                //
                // Both are now answers a party can choose, so the fold moved to where the rule can be
                // read on its own and asserted without a server. What stayed here is the asking, which
                // is the part that needs a world -- and which now lives in countMembers, because the
                // press that pays for a waiting task has to ask the same question. See `submit`.
                PartyCounts counts;
                if (behaviour.get().manualOnly(task)) {
                    // Zeros stand in for the asking, so the tick publishes zero and records nothing.
                    // The view is already a list, so it is passed as is rather than copied through a
                    // second one: nothing here writes to it, and the copy was one allocation per task
                    // per team per tick for the same zeros.
                    counts = new PartyCounts(List.of(), java.util.Collections.nCopies(members.size(), 0));
                }
                else {
                    counts = countMembers(index, server, owner, members,
                            asks, task, behaviour.get(), now);
                }
                List<Integer> perMember = counts.perMember();

                // Kept, not just added up: this list is what the panel's rows name, and it used to be
                // the tally's private business. See CONTRIBUTIONS.
                Map<UUID, Integer> held = new LinkedHashMap<>();
                for (int m = 0; m < members.size(); m++) {
                    int heldByMember = perMember.get(m);
                    if (heldByMember > 0) {
                        held.put(members.get(m).getUUID(), heldByMember);
                    }
                }
                // Handed over as it is rather than copied through an unordered map: the client's delta
                // is built by comparing JSON *text*, so a picture whose members came out in a different
                // order for the same numbers would read as a change on every tick and re-send the quest
                // forever. It is freshly built here and never touched again, so there is nothing to
                // defend against by copying.
                Map<UUID, Integer> shown = held.isEmpty() ? null : held;
                Map<UUID, Integer> was = shown == null
                        ? contributions.remove(key)
                        : contributions.put(key, shown);
                // News even when the party's total did not move: somebody going from two logs to three,
                // with eight already counted, changes nothing about the quest and everything about the
                // row that names them.
                if (!java.util.Objects.equals(was, shown)) {
                    changed = true;
                }
                PartyMode.Tally tally = mode.combine(perMember, ownerIndex);
                int current = tally.counted();
                ServerPlayer holder = tally.hasPayer() ? members.get(tally.payer()) : null;

                // Progress only ever goes up. A consuming task zeroes its own count the moment the
                // items are taken, so without this the task would un-complete itself.
                int recorded = questProgress.progressOf(taskIndex);
                int best = Math.max(recorded, current);

                // A task that takes what it asks for waits for the press: the tick publishes the
                // count for the row and the button, and records nothing. See waitsForSubmit, and
                // `submit` for the press that takes it.
                //
                // <b>Not recorded</b>, rather than merely not taken: completion is read off the
                // recorded progress (ProgressionEngine.tasksSatisfied), so a tick that took nothing
                // but still recorded the count would finish the quest and leave the items in the
                // player's pocket -- the opposite of what the press is for.
                boolean waits = behaviour.get().waitsForSubmit(task, chapterConsumes);
                if (waits) {
                    LIVE.computeIfAbsent(owner, absent -> new LinkedHashMap<>())
                            .put(keyOf(quest, taskIndex), current);
                }

                if (best <= recorded) {
                    continue;
                }
                if (!waits) {
                    questProgress = questProgress.recordTask(taskIndex, best);
                    changed = true;

                    // The lifecycle events, fired where the engine records the change rather than where
                    // it is later reported, so a listener runs before the caller's next statement. See
                    // TenetEvents.
                    ServerPlayer mover = holder != null ? holder : earner;
                    if (best >= required) {
                        TenetEvents.TASK_COMPLETED.invoker().onTaskCompleted(mover, quest, taskIndex);
                    }
                    if (!beforeThisTask.anyTaskProgress() && questProgress.anyTaskProgress()) {
                        TenetEvents.QUEST_STARTED.invoker().onQuestStarted(mover, quest);
                    }
                }

                // Whose carrying moved this quest forward, so the reward below goes to them.
                //
                // Only when somebody actually holds something: a task satisfied from *stored*
                // progress -- the items are gone, the completion is not -- leaves holder null and
                // keeps whatever the previous task decided, falling back to the first online member
                // if nothing has. That path is a player who logged off after gathering, and the
                // fallback is what it was before this was per-member at all.
                if (holder != null) {
                    earner = holder;
                }

                if (!waits && best >= required && behaviour.get().takesResources(task, chapterConsumes)) {
                    // Where the resources come from, and the mode decides it -- see takesFromEveryone.
                    //
                    // For the two modes that count one member, the payer is that member and is
                    // guaranteed to be holding at least `required`, because the count *is* their
                    // inventory. So the take is what it says it is, rather than finding less and
                    // leaving the task recorded as satisfied with the items still in somebody's
                    // pocket.
                    //
                    // POOLED is the mode where that guarantee does not hold and cannot: the count is
                    // the party's while the payer is only its largest holder, so eight logs across
                    // four members is a satisfied task and an inventory holding two. Taking from one
                    // would take two and call it four. So this is a real difference in behaviour
                    // rather than a tidier way to spell the same take.
                    if (mode.takesFromEveryone()) {
                        // From the contributors, not from everyone: an ungated member's items were never
                        // counted toward the requirement, so they are not the requirement's to take.
                        consumeAcross(counts.contributors(), task, required, behaviour.get());
                    }
                    else {
                        behaviour.get().take(task, holder != null ? holder : earner, required);
                    }
                }
            }

            // Completion is checked after the task pass, so a quest whose last task was satisfied
            // above completes on the same evaluation rather than one interval later.
            //
            // `canComplete` as well as `tasksSatisfied`, and it is not belt-and-braces. A repeatable
            // quest that is finished with a payout nobody has collected is still *playable* -- its
            // tasks are satisfied and its cooldown has not started counting -- so every tick would
            // reach this line and call `complete` again. `complete` refuses, correctly; the problem is
            // that `changed` was set regardless, so the refusal was reported as a change and became a
            // progress packet per tick, per member, for a quest that is waiting on a player rather
            // than on the engine. Asking first is what keeps the two answers consistent.
            //
            // The `else if` is what still carries genuinely new task progress to disk on the tick the
            // refusal happens, rather than dropping it on the floor.
            if (ProgressionEngine.tasksSatisfied(quest, questProgress) && canComplete(quest, working)
                    && gateOpenForFlexible(index, entry, quest, resolution.states())) {
                working = complete(server, owner, earner, entry, working.put(quest, questProgress),
                        resolution.states());
                changed = true;
            }
            else if (!questProgress.equals(working.progressOf(quest))) {
                working = working.put(quest, questProgress);
                changed = true;
            }
        }

        // Only when it moved. See the note on `changed` above: writing unconditionally marked the
        // store dirty every tick, which is a save-file write every tick for a value that did not
        // change. `complete` has already written by this point when it ran, so nothing is lost by
        // skipping this -- what it does is carry the *rest* of the pass's progress to disk.
        if (changed) {
            store.put(owner, working);
        }
        return changed;
    }

    // ------------------------------------------------------------------
    // Completing
    // ------------------------------------------------------------------

    /**
     * Marks a quest complete. Its rewards are recorded as <b>waiting</b>, not handed over.
     *
     * <h2>Completing and paying are two different acts</h2>
     *
     * <p>This used to grant the rewards itself, in the same call, which is what made a finished quest
     * and an empty inventory the same event. They are not the same event. A quest is finished when its
     * tasks are done; it is <i>paid</i> when the player decides to collect, which may be a minute
     * later, may be after they have sorted their inventory out, and may be after a relog. Legacy packs
     * separate them for the same reason, and so does every quest book a player has used.
     *
     * <p>So completion records {@code rewardsClaimed = false} and stops there. {@link #claim} is what
     * hands anything over, and it is the only thing that grants.
     *
     * <p>A quest with <b>no</b> rewards is recorded as already claimed. That is not a shortcut: there
     * is nothing to collect, so "settled" is the truthful state, and it keeps "is anything waiting"
     * a single boolean rather than a comparison against the reward list at every call site.
     *
     * <p>Repeatable quests keep their {@code timesCompleted} and clear their task progress, so the
     * next round starts clean while dependents stay satisfied — a chain following a repeatable quest
     * does not lock again every time the player redos it.
     *
     * @return the progress with this completion recorded. <b>Callers must use it</b>: the store has
     *         been written, but the value passed in does not know that.
     */
    public static TeamProgress complete(MinecraftServer server,
                                        UUID owner,
                                        ServerPlayer player,
                                        QuestIndex.QuestEntry entry,
                                        TeamProgress progress) {
        return complete(server, owner, player, entry, progress, null);
    }

    /**
     * The same, judging the flexible gate against states the caller already holds.
     *
     * <p>For the tick, which resolved the pass a moment ago: re-resolving inside would be a second
     * full evaluation for an answer that cannot differ — the gate reads other quests' states, and
     * states only ever move toward satisfied within a pass, so a gate the pass left open is open
     * here too. Commands, submits and events pass nothing and judge a fresh resolution, exactly as
     * before.
     *
     * @param passStates the pass's resolved states, or null to resolve fresh
     */
    private static TeamProgress complete(MinecraftServer server,
                                         UUID owner,
                                         ServerPlayer player,
                                         QuestIndex.QuestEntry entry,
                                         TeamProgress progress,
                                         Map<String, QuestState> passStates) {
        Quest quest = entry.quest();
        QuestProgress current = progress.progressOf(quest);

        if (!stageGateOpen(server, quest, player != null ? player.getUUID() : null)) {
            // A quest this player has not unlocked does not finish for them. It stays satisfied and
            // unfinished -- the tasks are done, the completion is not -- so granting the stage afterwards
            // pays out without anyone repeating the work, which is what a gate should mean.
            return progress;
        }

        if (!canComplete(quest, progress)) {
            return progress;
        }

        // The gate half of flexible progress. This runs outside any evaluation pass — commands,
        // submits and the death event all land here — so it judges a fresh resolution rather than
        // a pass's states. The tick's own check above already answered for the pass it holds; this
        // is what stops a submit or a kill from finishing a flexible quest whose gate is still shut.
        long now = server.overworld().getGameTime();
        Map<String, QuestState> states = passStates != null ? passStates
                : ProgressionEngine.resolve(TenetQuests.index(), progress, now).states();
        if (ProgressionEngine.isFlexible(quest, entry.chapter())
                && !ProgressionEngine.dependenciesSatisfied(TenetQuests.index(), entry, states)) {
            return progress;
        }

        QuestSettings settings = TenetQuests.settings();

        // `resetTasks` clears the round's claims as a side effect of building a fresh round, so
        // anything marked below is marked after it rather than before. Setting first and resetting
        // second is the ordering that looks natural and is silently wrong.
        //
        // A repeatable round with a payout still to collect keeps its tasks: the payout is the
        // round, and clearing it now would erase what "completed" means to look at, while starting
        // the cooldown now would time what nobody is waiting for. Its claims are wiped — the next
        // round's payout must read as uncollected — but its clock and its count wait for the final
        // claim (see the claim path). A round with nothing to wait for, rewards or not, resets here.
        QuestProgress recorded;
        if (quest.repeatable() && !quest.rewards().isEmpty()) {
            recorded = current.withClaims(QuestClaims.NONE).completed();
        }
        else {
            recorded = (quest.repeatable() ? current.resetTasks() : current).completedAt(now);
        }

        // The rewards that hand themselves over, which is what the auto-claim modes are for. Resolved
        // and recorded now -- before anything is granted -- so a crash between the two cannot
        // duplicate a payout; see the class note on the direction of that write. A team-mode reward
        // is one claim, granted to the completer; a player-mode one is granted to every member online
        // now, and the rest collect theirs when they next join (see autoClaimFor).
        List<Grant> automatic = automaticGrants(server, owner, quest, recorded, progress, settings, player,
                onlineMembersOf(server, teamFor(server, owner)),
                // The ladder's middle rungs, resolved once here: reward's own `auto` overrides this,
                // and this is the quest's mode or the chapter's (itself over the pack setting).
                quest.autoClaim(entry.chapter().autoClaim().resolved(settings.defaultAutoClaim())));
        QuestClaims marked = recorded.claims();
        for (Grant grant : automatic) {
            marked = grant.teamClaim() ? marked.withTeamClaim(grant.index())
                    : marked.withPlayerClaim(grant.target().getUUID(), grant.index());
        }
        recorded = recorded.withClaims(marked);

        // The round is over for the completer when they have nothing left to collect; see QuestProgress.
        boolean outstanding = outstandingFor(quest, recorded, player.getUUID(), settings);
        recorded = recorded.withRewardsClaimed(!outstanding);

        // A repeatable round nobody has anything left to collect from is over the moment it is
        // paid: with no payout waiting, no claim will ever arrive to end it, so the count moves
        // and the cooldown starts now rather than at a claim that never comes. The claims stay
        // marked — wiping them here would re-offer what was just paid.
        if (quest.repeatable() && !quest.rewards().isEmpty() && !outstanding) {
            recorded = recorded.repeatRoundOver(now);
        }

        TeamProgress saved = progress.put(quest, recorded);
        ProgressStore.of(server).put(owner, saved);

        // One tally per recipient, because the drops are per inventory: a party member whose own
        // backpack was full is told about their own floor, not the completer's. The completer is the
        // usual case and gets one sentence covering every automatic reward they were handed.
        Map<ServerPlayer, RewardFeedback> feedbackByTarget = new LinkedHashMap<>();
        for (Grant grant : automatic) {
            RewardFeedback feedback = feedbackByTarget.computeIfAbsent(grant.target(),
                    target -> new RewardFeedback());
            grantRewards(server, owner, grant.target(), entry, List.of(grant.index()), settings, feedback);
        }
        feedbackByTarget.forEach(ProgressService::announceOverflow);

        TenetEvents.QUEST_COMPLETED.invoker().onQuestCompleted(player, quest);

        Constants.LOG.info("{} completed '{}' for team {}{}",
                player.getScoreboardName(), quest.id(), owner, outstanding ? " -- rewards waiting" : "");
        // No chat line: the completion notice is the client's job now -- the book's own stack when it is
        // open, a real toast when it is not, and one sound either way. A line here was the third copy of
        // the same sentence, and the one a player could not read while the book was up.

        return saved;
    }

    /** One reward being handed to one player, as completion's auto-claim decides it. */
    private record Grant(int index, ServerPlayer target, boolean teamClaim) {
    }

    /**
     * Whether <b>this player</b> still has a reward to collect on this quest.
     *
     * <p>Per player, because that is what claiming is: in a party of four where one has collected
     * their diamond, three players still have something outstanding — and one global flag could only
     * answer that question wrong for somebody.
     */
    private static boolean outstandingFor(Quest quest, QuestProgress progress, UUID player,
                                          QuestSettings settings) {
        for (int index = 0; index < quest.rewards().size(); index++) {
            if (!progress.claimed(player, index, teamReward(quest, index, settings))) {
                return true;
            }
        }
        return false;
    }

    /** Whether a reward is the one-claim-for-the-team kind, resolved against the file default. */
    private static boolean teamReward(Quest quest, int index, QuestSettings settings) {
        return quest.rewards().get(index).common().teamReward(settings.defaultTeamReward());
    }

    /**
     * The automatic rewards, as the players they are owed to.
     *
     * <p>Blocking applies here exactly as it does to a claim: a team whose rewards are held keeps even
     * its automatic ones, so unblocking is one moment rather than two behaviours. Suppression is the
     * file-wide switch, and it outranks every per-reward mode.
     *
     * <p>{@code fileDefault} is the ladder's middle: the quest's {@code autoClaim} over the chapter's
     * over the pack setting, already resolved by the caller. A reward whose own {@code auto} says
     * something other than {@code default} still wins over it.
     *
     * <p>A reward that cannot be granted without a decision — a choice table — is skipped rather than
     * selected: it stays outstanding and the claim flow offers it, which is the only path that can pay
     * it. Before {@link QuestReward#autoGrantable} existed it was marked collected and granted nothing.
     */
    private static List<Grant> automaticGrants(MinecraftServer server, UUID owner, Quest quest,
                                               QuestProgress current, TeamProgress team,
                                               QuestSettings settings, ServerPlayer completer,
                                               List<ServerPlayer> members, RewardAutoClaim fileDefault) {
        if (settings.suppressAllAutoclaiming()) {
            return List.of();
        }
        List<Grant> grants = new ArrayList<>();
        // One context per member for the whole grant pass rather than one per reward per member:
        // the subject, the server and the owner cannot move between rewards of one completion.
        Map<ServerPlayer, ConditionContext> contexts = new LinkedHashMap<>();
        for (ServerPlayer member : members) {
            contexts.put(member, new ConditionContext(member, server, owner));
        }
        for (int index = 0; index < quest.rewards().size(); index++) {
            QuestReward reward = quest.rewards().get(index);
            if (isBlocked(team, quest, reward) || !reward.autoGrantable()) {
                continue;
            }
            if (!reward.common().autoClaim(fileDefault).automatic()) {
                continue;
            }
            // Per recipient, because the conditions and the stage gate are: a reward that pays each
            // member is gated for each member. One whose conditions are unmet, or whose stage this
            // member has never held, is not lost -- it stays unclaimed, and the claim paths (including
            // autoClaimFor at the next join) pick it up once they hold.
            //
            // The gate belongs here for the same reason it is on the completion path at 805 and the
            // claim path: the team's record can be complete while a member who just joined it never
            // held the stage, and paying them would be the one payout the gate does not cover.
            if (reward.common().teamReward(settings.defaultTeamReward())) {
                if (!current.claims().team().contains(index)
                        && stageGateOpen(server, quest, completer.getUUID())
                        && Conditions.passes(reward.common().conditions(),
                                contexts.computeIfAbsent(completer,
                                        late -> new ConditionContext(late, server, owner)))) {
                    grants.add(new Grant(index, completer, true));
                }
            }
            else {
                for (ServerPlayer member : members) {
                    if (!current.claims().claimed(member.getUUID(), index, false)
                            && stageGateOpen(server, quest, member.getUUID())
                            && Conditions.passes(reward.common().conditions(),
                                    contexts.get(member))) {
                        grants.add(new Grant(index, member, false));
                    }
                }
            }
        }
        return grants;
    }

    /** Whether this team's rewards are blocked, for this reward. See {@link TeamProgress#rewardsBlocked()}. */
    public static boolean isBlocked(TeamProgress team, Quest quest, QuestReward reward) {
        return team.rewardsBlocked() && !reward.common().ignoreRewardBlocking()
                && !quest.ignoreRewardBlocking();
    }

    /**
     * Hands over a finished quest's rewards, once.
     *
     * <h2>The order here is the whole point, and it moved with the granting</h2>
     *
     * <p>Rewards are marked claimed and <b>saved</b> before any of them is handed over — the same
     * direction {@code complete} used to take, and for the same reason. A crash between the write and
     * the last item leaves a quest marked claimed with some things given, which loses the rest rather
     * than duplicating them. Duplicating a diamond is an exploit; losing one to a crash is an
     * annoyance. That is the direction the plan chose, and it is the only defensible one.
     *
     * @return whether anything was collected. False when the quest is not finished, has no rewards, or
     *     has already been collected — so a second click, a double-sent packet, or a stale client
     *     asking after a reload all change nothing at all.
     */
    public static boolean claim(MinecraftServer server, ServerPlayer player, QuestIndex.QuestEntry entry) {
        RewardFeedback feedback = new RewardFeedback();
        // Strict: this press can take more than one reward, so nothing is dropped -- a reward that does
        // not fit is left outstanding and the sweep stops. The row's own Claim is the lenient path.
        Claimed result = claim(server, player, entry, false, -1, feedback, ClaimFilter.ALL, true);
        announceOverflow(player, feedback);
        if (result.halted()) {
            announceSummary(player, result.taken(), result.total(), true);
        }
        return result.taken() > 0 || result.offered();
    }

    /**
     * Hands over one of a finished quest's rewards, by index.
     *
     * <p>The rewards panel's per-row press. It exists rather than a client-side loop over
     * {@link #claim} because a player with a full inventory wants the diamonds now and the planks
     * later: each row is one reward, and collecting it must leave its siblings outstanding. Every
     * check the whole-quest claim makes is the same code here — the private claim is one method with
     * one index filter — so the two presses cannot disagree about what may be taken, and a forged
     * index buys a modified client nothing.
     *
     * @return whether anything was collected. False for an index that is out of range, already
     *     collected, gated by an unmet condition, or held by the team's block — each with the same
     *     message the whole-quest claim would give.
     */
    public static boolean claimReward(MinecraftServer server, ServerPlayer player,
                                      QuestIndex.QuestEntry entry, int rewardIndex) {
        RewardFeedback feedback = new RewardFeedback();
        // Lenient: one reward, one deliberate press, so what does not fit is dropped at the player's
        // feet rather than refused -- the lockout escape hatch the overflow notice exists for.
        Claimed result = claim(server, player, entry, false, rewardIndex, feedback, ClaimFilter.ALL, false);
        announceOverflow(player, feedback);
        return result.taken() > 0;
    }

    /** Whether this player passes a quest's stage gate. True when the quest has none. */
    public static boolean stageGateOpen(MinecraftServer server, Quest quest, UUID player) {
        java.util.Optional<net.minecraft.resources.ResourceLocation> required = quest.requiresStage();
        if (required.isEmpty()) {
            return true;
        }
        if (server == null || player == null) {
            // A harness, or a server on its way down. A gate nobody can ask about is not a gate that blocks:
            // the alternative is refusing a completion over a fact that cannot be read, and the playback
            // harness -- whose players have a null server -- is the case that made this explicit rather than
            // accidental.
            return true;
        }
        if (quest.requiresStageTeam()) {
            return StageService.hasTeam(server, Teams.teamOf(server, player).id(), required.get());
        }
        return StageService.has(server, player, required.get());
    }

    /**
     * The quests whose stage gate this player does not pass, by id.
     *
     * <p>Asked once per player by the sync, so the client can draw them locked: the state a team's progress
     * holds is the same for every member, and whether <i>this</i> player may see and claim a quest is not.
     * Empty whenever no quest in the index declares a gate, which is the usual case and the fast one.
     */
    public static java.util.Set<String> stageLockedQuests(MinecraftServer server, ServerPlayer player,
                                                          QuestIndex index) {
        if (server == null) {
            return java.util.Set.of();
        }
        // Asked per quest rather than copied per send: the held sets were built whole and then
        // probed once per gated quest, so a pack with a handful of gates paid for every stage ever
        // granted. A miss reads as locked, exactly as the set-membership form answered — a stage
        // neither the player nor (for a team gate) the team holds is not held.
        java.util.UUID self = player.getUUID();
        java.util.UUID team = progressOwner(server, player);
        java.util.Set<String> locked = new java.util.LinkedHashSet<>();
        for (QuestIndex.QuestEntry entry : index.quests()) {
            Quest quest = entry.quest();
            if (quest.requiresStage().isEmpty()) {
                continue;
            }
            net.minecraft.resources.ResourceLocation stage = quest.requiresStage().get();
            boolean held = quest.requiresStageTeam()
                    ? StageService.hasTeam(server, team, stage)
                    : StageService.has(server, self, stage);
            if (!held) {
                locked.add(quest.id());
            }
        }
        return locked;
    }

    /**
     * The rows a player is condition-locked out of, by quest: row index -> the conditions that failed.
     *
     * <p>A task or a reward with no entry is not locked, and its conditions — if it has any — all held.
     * The lists are ascending and the maps are sorted, because this value's text is compared to decide
     * whether a client needs an update; a map iterated in a varying order would resend forever, the
     * lesson the contributors' JSON already carries.
     */
    public record LockView(Map<Integer, List<Integer>> tasks, Map<Integer, List<Integer>> rewards) {

        /** No locks at all, which is every player on a pack that uses no conditions. */
        public static final LockView NONE = new LockView(Map.of(), Map.of());

        public boolean isEmpty() {
            return tasks.isEmpty() && rewards.isEmpty();
        }
    }

    /**
     * Every condition lock this player has, for the sync to draw and to compare against the last one.
     *
     * <p>Only rows that declare conditions are evaluated, so a pack that uses none pays one walk of
     * the index per call and nothing else — and the callers skip even that when the index declares
     * none at all; see {@link #hasConditions}.
     *
     * <p>Asked per player because every condition is: the sync's overlay is the one per-player fact a
     * team's stored progress cannot express, the same reason {@link #stageLockedQuests} exists.
     */
    public static Map<String, LockView> lockView(MinecraftServer server, QuestIndex index,
                                                 ServerPlayer player, UUID owner) {
        Map<String, LockView> out = new TreeMap<>();
        ConditionContext context = new ConditionContext(player, server, owner);
        for (QuestIndex.QuestEntry entry : index.quests()) {
            Quest quest = entry.quest();
            Map<Integer, List<Integer>> tasks = lockedRows(
                    quest.tasks().stream().map(task -> task.common().conditions()).toList(), context);
            Map<Integer, List<Integer>> rewards = lockedRows(
                    quest.rewards().stream().map(reward -> reward.common().conditions()).toList(), context);
            if (!tasks.isEmpty() || !rewards.isEmpty()) {
                out.put(quest.id(), new LockView(tasks, rewards));
            }
        }
        return out;
    }

    private static Map<Integer, List<Integer>> lockedRows(List<List<QuestCondition>> conditions,
                                                          ConditionContext context) {
        Map<Integer, List<Integer>> out = new TreeMap<>();
        for (int i = 0; i < conditions.size(); i++) {
            if (conditions.get(i).isEmpty()) {
                continue;
            }
            List<Integer> unmet = Conditions.unmet(conditions.get(i), context);
            if (!unmet.isEmpty()) {
                out.put(i, unmet);
            }
        }
        return out;
    }

    /**
     * Whether any quest declares a condition at all.
     *
     * <p>The lock refresh's fast path: a pack that uses no conditions — which is every pack before
     * this feature, and most after it — must pay nothing recurring for a display it never shows.
     */
    public static boolean hasConditions(QuestIndex index) {
        for (QuestIndex.QuestEntry entry : index.quests()) {
            for (QuestTask task : entry.quest().tasks()) {
                if (!task.common().conditions().isEmpty()) {
                    return true;
                }
            }
            for (QuestReward reward : entry.quest().rewards()) {
                if (!reward.common().conditions().isEmpty()) {
                    return true;
                }
            }
        }
        return false;
    }

    /** What one claim press did: how much it took, out of how much it could, and why it stopped. */
    private record Claimed(int taken, int total, boolean halted, boolean offered) {
    }

    /** One resolved reward: the leaf to hand over, and — for an item — the exact stacks it will insert. */
    private record Leaf(QuestReward reward, List<ItemStack> stacks) {
    }

    /** The rewards a press may take, and the two reasons it may not. */
    private record Payable(List<Integer> indexes, List<Integer> offers, boolean sawBlocked,
                           boolean sawLocked) {
    }

    /**
     * Everything outstanding on one quest, or everything a press is allowed to take.
     *
     * <p>{@code claimAllMode} separates the two presses: a reward marked {@code excludeFromClaimAll}
     * waits for its own button, exactly as in legacy packs. Blocked rewards are skipped in both modes and
     * named when nothing at all could be taken, because a press that silently does nothing is the thing
     * a blocked team would report as broken.
     *
     * <p>{@code onlyIndex} narrows the press to one reward — the rewards panel's per-row Claim — and
     * {@code -1} means the whole list. {@code filter} is the panel's active chip: it only ever narrows,
     * and a reward it excludes is skipped before every other check, so it cannot be reported as locked
     * or blocked by a press that was never about it.
     *
     * <p>One loop, used by the claim and by the sweep's own count, so what a press takes and what it
     * says it could have taken cannot disagree.
     */
    private static Payable payable(MinecraftServer server, ServerPlayer player, UUID owner, TeamProgress team,
                                   Quest quest, QuestProgress current, QuestSettings settings,
                                   boolean claimAllMode, int onlyIndex, ClaimFilter filter) {
        List<Integer> payable = new ArrayList<>();
        List<Integer> offers = new ArrayList<>();
        boolean sawBlocked = false;
        boolean sawLocked = false;
        // One context for the press rather than one per reward: the player, the server and the
        // owner cannot move between rewards of one plan.
        ConditionContext context = new ConditionContext(player, server, owner);
        for (int index = 0; index < quest.rewards().size(); index++) {
            if (onlyIndex >= 0 && index != onlyIndex) {
                // Somebody else's row. Skipped before every check, so a reward this press is not about
                // cannot be reported as locked or blocked by it.
                continue;
            }
            QuestReward reward = quest.rewards().get(index);
            if (!matches(filter, reward)) {
                continue;
            }
            boolean teamMode = reward.common().teamReward(settings.defaultTeamReward());
            // This player's own claim, or the team's for a team-mode reward. What a teammate has
            // collected is not this player's business -- each claim is their own copy.
            if (current.legacySettled() || current.claimed(player.getUUID(), index, teamMode)) {
                continue;
            }
            // The reward's own gate, checked here so that a claim and a claim-all cannot disagree:
            // both build the same plan and both refuse a row whose conditions are unmet, and the refusal
            // is a message rather than a silent no-op. Nothing is marked, so the reward stays outstanding
            // and is paid the moment the conditions hold.
            if (!Conditions.passes(reward.common().conditions(), context)) {
                sawLocked = true;
                continue;
            }
            if (isBlocked(team, quest, reward)) {
                sawBlocked = true;
                continue;
            }
            if (isChoice(reward)) {
                // A choice is not paid on the press: the player picks first. It stays outstanding --
                // unmarked -- until the answer arrives, so nothing is lost between the two messages
                // and a re-press simply offers again.
                offers.add(index);
                continue;
            }
            if (claimAllMode && reward.common().excludeFromClaimAll()) {
                continue;
            }
            payable.add(index);
        }
        return new Payable(List.copyOf(payable), List.copyOf(offers), sawBlocked, sawLocked);
    }

    /** Whether a reward is what a filter chip is about. */
    private static boolean matches(ClaimFilter filter, QuestReward reward) {
        return switch (filter) {
            case ALL -> true;
            // A table can roll items, so it counts as one; a choice is the picker's business.
            case ITEMS -> reward instanceof ItemReward
                    || (reward instanceof TableReward table && table.mode() != TableReward.Mode.CHOICE);
            case CHOICES -> isChoice(reward);
        };
    }

    /**
     * Whether a reward is a choice table — offered to the client rather than granted on the press.
     *
     * <p>Delegates rather than repeating the test: the grant, the roll report and the export all have
     * to answer this same question, and four copies of "is it a table reward whose mode is choice" is
     * four chances for one of them to be a mode behind. See {@link TableReward#isChoice}.
     */
    private static boolean isChoice(QuestReward reward) {
        return TableReward.isChoice(reward);
    }

    /**
     * Hands over what this press may take.
     *
     * <h2>Strict and lenient</h2>
     *
     * <p>{@code strict} is the difference between a sweep and a single press. A sweep (Claim all, Claim
     * Quest, a pick) asks whether each reward fits <b>before</b> handing it over, and stops at the first
     * that does not — leaving it and everything after it outstanding. A single row's own Claim is
     * lenient: it inserts what fits and drops the rest at the player's feet, which is the escape hatch
     * from the inventory-full lockout that the drop notice exists for.
     *
     * <h2>Marked after granting, written once</h2>
     *
     * <p>Each reward is marked as it is actually handed over, and the store is written <b>once</b> after
     * the loop — including when it stops early. That is deliberately not the usual mark-then-grant:
     * {@code put} is an in-memory map write plus a dirty flag, so a crash loses the marks and the items
     * together and the ordering buys nothing there. What it does change is a runtime exception
     * mid-grant: this order leaves that reward unmarked and outstanding, where marking first would lose
     * it for good.
     */
    private static Claimed claim(MinecraftServer server, ServerPlayer player, QuestIndex.QuestEntry entry,
                                 boolean claimAllMode, int onlyIndex, RewardFeedback feedback,
                                 ClaimFilter filter, boolean strict) {
        Quest quest = entry.quest();
        if (!stageGateOpen(server, quest, player.getUUID())) {
            // Gated: the claim is refused where every claim arrives -- the button, Claim all and the
            // command all reach this method -- and the sync the caller sends anyway corrects the button
            // the client should not be showing.
            return new Claimed(0, 0, false, false);
        }
        UUID owner = progressOwner(server, player);
        ProgressStore store = ProgressStore.of(server);
        TeamProgress team = store.progressOf(owner);
        QuestProgress current = team.progressOf(quest);
        // Nothing is owed by a quest that is not finished, and this is the guard that says so.
        //
        // `canClaimFor` has always required a stored COMPLETED, so Claim all never reached an unfinished
        // quest; the single claim and the choice answer did, which meant a client whose book was stale
        // after `/tenet reset` -- or a forged claim payload -- was paid the whole reward list. The
        // stored state and not the resolved one, deliberately: a repeatable quest past its cooldown
        // resolves STARTED/UNLOCKED while its stored state stays COMPLETED, and that is exactly when an
        // uncollected reward of the last round is legitimately claimable.
        if (current.state() != QuestState.COMPLETED) {
            return new Claimed(0, 0, false, false);
        }
        QuestSettings settings = TenetQuests.settings();
        Payable plan = payable(server, player, owner, team, quest, current, settings, claimAllMode,
                onlyIndex, filter);
        int total = plan.indexes().size();

        if (plan.indexes().isEmpty() && plan.offers().isEmpty()) {
            if (plan.sawBlocked()) {
                player.displayClientMessage(
                        Component.translatable("tenet.quest.rewards_blocked"), true);
            }
            else if (plan.sawLocked()) {
                // Named separately from blocking: one is the team's switch, the other is something the
                // player can go and do. The sync the caller sends anyway corrects the rows.
                player.displayClientMessage(
                        Component.translatable("tenet.quest.conditions_unmet"), true);
            }
            return new Claimed(0, total, false, false);
        }

        int taken = 0;
        boolean halted = false;
        if (!plan.indexes().isEmpty()) {
            QuestClaims marked = current.claims();
            for (int index : plan.indexes()) {
                QuestReward reward = quest.rewards().get(index);
                List<Leaf> leaves = resolve(reward, player);
                if (strict && !InventoryAccesses.current().fitsAll(player, itemStacks(leaves))) {
                    // No room: nothing is granted, this reward and everything after it stay outstanding,
                    // and the caller says how far the sweep got.
                    halted = true;
                    break;
                }
                RewardContext context = new RewardContext(player, server, owner, quest.id(), entry.chapterId(),
                        onlineMembersOf(server, teamFor(server, owner)), feedback);
                boolean whole = grantLeaves(leaves, context);
                TenetEvents.REWARD_CLAIMED.invoker().onRewardClaimed(player, quest, reward);
                marked = teamReward(quest, index, settings)
                        ? marked.withTeamClaim(index)
                        : marked.withPlayerClaim(player.getUUID(), index);
                taken++;
                if (!whole) {
                    // A remainder on an insert the check said would fit: something ran between the two
                    // (a command reward, a listener). The remainder is on the ground and reported; stop.
                    halted = true;
                    break;
                }
            }
            QuestProgress updated = current.withClaims(marked);
            updated = updated.withRewardsClaimed(
                    !outstandingFor(quest, updated, player.getUUID(), settings));
            updated = endRepeatableRoundIfCollected(server, quest, current, updated);
            store.put(owner, team.put(quest, updated));

            Constants.LOG.info("{} collected {} of {} reward(s) for '{}' (team {}){}",
                    player.getScoreboardName(), taken, total, quest.id(), owner,
                    halted ? " -- stopped, inventory full" : "");
            // The claim is confirmed by the UI it changes and by the pickup sound the client plays when
            // the sync shows what was owed is not any more -- not by a chat line, which the player
            // cannot see behind the book they are claiming from.
        }

        for (int index : plan.offers()) {
            sendChoiceOffer(player, quest, index, (TableReward) quest.rewards().get(index));
            // And no "choose" line either: the choice card opens itself from the offer.
        }
        return new Claimed(taken, total, halted, !plan.offers().isEmpty());
    }

    /**
     * One reward as the leaves it will hand over.
     *
     * <p>Resolved once, before anything is checked or granted: a table's roll has to exist before it
     * can be asked about, and checking one roll while handing over another would be a check that means
     * nothing. The item stacks are pinned the same way — the same list is what the fit check measures
     * and what the insert places, so a {@code randomBonus} is rolled once.
     */
    private static List<Leaf> resolve(QuestReward reward, ServerPlayer player) {
        List<QuestReward> leaves = reward instanceof TableReward table
                ? TableReward.resolve(List.of(table), player.getRandom(), 0)
                : List.of(reward);
        List<Leaf> out = new ArrayList<>(leaves.size());
        for (QuestReward leaf : leaves) {
            out.add(new Leaf(leaf, leaf instanceof ItemReward item
                    ? ItemReward.stacksToGive(item, player)
                    : List.of()));
        }
        return out;
    }

    /** Every stack these leaves will insert, for the fit check. */
    private static List<ItemStack> itemStacks(List<Leaf> leaves) {
        List<ItemStack> stacks = new ArrayList<>();
        for (Leaf leaf : leaves) {
            stacks.addAll(leaf.stacks());
        }
        return stacks;
    }

    /**
     * Grants one reward's leaves: the items first, then everything else.
     *
     * <p>That order is not cosmetic. A command or an advancement in the same reward can put items in
     * the player's inventory or hand them to a script, so running one before the items would invalidate
     * the fit check the items were just measured against.
     *
     * @return whether every insert took the whole stack. False means a remainder hit the ground: it is
     *     dropped and reported rather than swallowed, and the caller stops the sweep.
     */
    private static boolean grantLeaves(List<Leaf> leaves, RewardContext context) {
        boolean whole = true;
        for (Leaf leaf : leaves) {
            if (!(leaf.reward() instanceof ItemReward)) {
                continue;
            }
            for (ItemStack stack : leaf.stacks()) {
                ItemStack remainder = InventoryAccesses.current().insert(context.player(), stack);
                if (!remainder.isEmpty()) {
                    context.player().drop(remainder, false);
                    context.feedback().dropped(remainder);
                    whole = false;
                }
            }
        }
        for (Leaf leaf : leaves) {
            if (leaf.reward() instanceof ItemReward) {
                continue;
            }
            grantLeaf(leaf.reward(), context);
        }
        return whole;
    }

    /** One non-item leaf through its registered behaviour, isolated the way the engine's loop isolates. */
    private static void grantLeaf(QuestReward reward, RewardContext context) {
        Optional<dev.ellipog.tenet.quest.reward.RewardBehaviour<QuestReward>> behaviour =
                RewardTypes.behaviourOf(reward);
        if (behaviour.isEmpty()) {
            Constants.LOG.warn("no behaviour registered for reward type {}; skipped", reward.type());
            return;
        }
        try {
            behaviour.get().grant(reward, context);
        }
        catch (RuntimeException e) {
            // One bad reward must not abort the rest, and must not leave the quest unclaimed --
            // which would re-grant everything on the next evaluation.
            Constants.LOG.error("granting a {} reward failed; the rest were still given",
                    reward.type(), e);
        }
    }

    /**
     * Offers a choice reward's entries to the player who claimed it.
     *
     * <p>Only the display travels; the rewards stay on the server, and the answer is re-validated
     * when it comes back. See {@link #claimChoice}.
     */
    private static void sendChoiceOffer(ServerPlayer player, Quest quest, int index,
                                        dev.ellipog.tenet.quest.reward.TableReward reward) {
        Optional<dev.ellipog.tenet.quest.loot.RewardTable> resolved = reward.resolvedTable();
        if (resolved.isEmpty()) {
            return; // the loader reported the missing table at its own line
        }
        List<dev.ellipog.tenet.net.ChoiceRewardPayload.Entry> entries = new ArrayList<>();
        for (dev.ellipog.tenet.quest.loot.RewardTable.Entry tableEntry : resolved.get().entries()) {
            dev.ellipog.tenet.quest.reward.RewardDisplay display =
                    RewardTypes.displayOf(tableEntry.reward());
            entries.add(new dev.ellipog.tenet.net.ChoiceRewardPayload.Entry(
                    display.item().map(ref -> ref.item().toString()).orElse(""),
                    display.count(), display.label(), display.labelFallback(), display.labelArg()));
        }
        dev.ellipog.armature.api.net.ArmatureNetwork.sendToPlayer(player,
                new dev.ellipog.tenet.net.ChoiceRewardPayload(quest.id(), index, entries));
    }

    /**
     * The player's answer to a choice offer: grant the chosen entry and record the claim.
     *
     * <p>Everything is re-resolved here — the quest, the reward, the entry index — so the payload
     * cannot grant anything a table does not hold.
     *
     * <h2>A pick is strict, and the player is told why when it is refused</h2>
     *
     * <p>The player has just made a deliberate decision, so a full inventory must not discard it: the
     * fit is checked before anything is marked, {@link ClaimChoiceResultPayload.Result#NO_SPACE} comes
     * back, and the card stays open with its rows live — the player can make room, pick another entry,
     * or leave. Only a granted pick is marked, and the store is written once, after the grant: the same
     * order the sweep uses, for the same exception-safety reason.
     *
     * @return what became of the pick, which the caller echoes to the client
     */
    public static ClaimChoiceResultPayload.Result claimChoice(MinecraftServer server, ServerPlayer player,
                                                              QuestIndex.QuestEntry entry, int rewardIndex,
                                                              int entryIndex) {
        Quest quest = entry.quest();
        if (rewardIndex < 0 || rewardIndex >= quest.rewards().size()) {
            return ClaimChoiceResultPayload.Result.REFUSED;
        }
        QuestReward reward = quest.rewards().get(rewardIndex);
        if (!isChoice(reward)) {
            return ClaimChoiceResultPayload.Result.REFUSED;
        }
        Optional<dev.ellipog.tenet.quest.loot.RewardTable> resolved =
                ((TableReward) reward).resolvedTable();
        Optional<QuestReward> chosen = resolved.flatMap(value -> value.choice(entryIndex));
        if (chosen.isEmpty()) {
            return ClaimChoiceResultPayload.Result.REFUSED;
        }

        UUID owner = progressOwner(server, player);
        ProgressStore store = ProgressStore.of(server);
        TeamProgress team = store.progressOf(owner);
        QuestProgress current = team.progressOf(quest);
        // Nothing is owed by a quest that is not finished, and this is the guard that says so.
        //
        // `canClaimFor` has always required a stored COMPLETED, so Claim all never reached an unfinished
        // quest; the single claim and the choice answer did, which meant a client whose book was stale
        // after `/tenet reset` -- or a forged claim payload -- was paid the whole reward list. The
        // stored state and not the resolved one, deliberately: a repeatable quest past its cooldown
        // resolves STARTED/UNLOCKED while its stored state stays COMPLETED, and that is exactly when an
        // uncollected reward of the last round is legitimately claimable.
        if (current.state() != QuestState.COMPLETED) {
            return ClaimChoiceResultPayload.Result.REFUSED;
        }
        QuestSettings settings = TenetQuests.settings();
        boolean teamMode = reward.common().teamReward(settings.defaultTeamReward());
        if (current.legacySettled() || current.claimed(player.getUUID(), rewardIndex, teamMode)) {
            return ClaimChoiceResultPayload.Result.REFUSED;
        }
        // Re-checked at the answer: the offer travelled, the conditions did not necessarily hold when
        // it arrived, and a choice is a payout path of its own.
        if (!Conditions.passes(reward.common().conditions(), new ConditionContext(player, server, owner))) {
            player.displayClientMessage(Component.translatable("tenet.quest.conditions_unmet"), true);
            return ClaimChoiceResultPayload.Result.REFUSED;
        }

        List<Leaf> leaves = resolve(chosen.get(), player);
        if (!InventoryAccesses.current().fitsAll(player, itemStacks(leaves))) {
            // Refused rather than dropped: the player chose this, so it waits for them.
            return ClaimChoiceResultPayload.Result.NO_SPACE;
        }
        RewardFeedback feedback = new RewardFeedback();
        RewardContext context = new RewardContext(player, server, owner, quest.id(), entry.chapterId(),
                onlineMembersOf(server, teamFor(server, owner)), feedback);
        try {
            // The return value is not consulted: a remainder means the inventory changed under the
            // check, and the remainder is on the ground and reported -- the pick itself still counts,
            // so the player is not asked to choose again for a half-grant.
            grantLeaves(leaves, context);
        }
        catch (RuntimeException e) {
            Constants.LOG.error("granting the chosen reward of '{}' failed; nothing was claimed",
                    quest.id(), e);
            announceOverflow(player, feedback);
            return ClaimChoiceResultPayload.Result.REFUSED;
        }
        announceOverflow(player, feedback);

        QuestClaims marked = teamMode
                ? current.claims().withTeamClaim(rewardIndex)
                : current.claims().withPlayerClaim(player.getUUID(), rewardIndex);
        QuestProgress updated = current.withClaims(marked);
        updated = updated.withRewardsClaimed(
                !outstandingFor(quest, updated, player.getUUID(), settings));
        updated = endRepeatableRoundIfCollected(server, quest, current, updated);
        store.put(owner, team.put(quest, updated));
        TenetEvents.REWARD_CLAIMED.invoker().onRewardClaimed(player, quest, reward);
        // No chat line; see the claim path above.
        return ClaimChoiceResultPayload.Result.OK;
    }

    /**
     * Claims everything outstanding across the whole book, for the footer control.
     *
     * <p>The quests are found here rather than named by the caller: a client sending a list of ids
     * would be a client deciding what it is owed. This asks the same {@link #canClaimFor} the single
     * claim does, so the two cannot disagree about what a button may take. {@code filter} is the claim
     * menu's active view and only ever narrows — a view of choices must not reach past what is on
     * screen.
     *
     * <p>One line, because {@link #sweep} is where the work is: this is the whole book and
     * {@link #claimChapter} is one chapter of it, so the order, the strictness and the summary are one
     * implementation rather than two that agree until somebody edits one.
     *
     * @return how many rewards were handed over
     */
    public static int claimAll(MinecraftServer server, ServerPlayer player, ClaimFilter filter) {
        return sweep(server, player, filter, null);
    }

    /**
     * Claims everything outstanding in one chapter, for the claim menu's banner.
     *
     * <p>The same sweep as {@link #claimAll} with the index narrowed to one chapter, and narrowed
     * <b>here</b> rather than by the caller: the payload carries a chapter id and no quests, so which of
     * that chapter's quests are owed stays the server's to decide, exactly as it does for the whole
     * book. A chapter id matching nothing — a typo, a renamed chapter, a forged packet — walks the index
     * and claims nothing, which is the honest outcome rather than an error: there is nothing to refuse.
     *
     * <p>Sharing the sweep rather than restating it is what keeps the two presses from disagreeing: the
     * order, the strictness, the overflow tally and the summary sentence are one implementation, so
     * "Claim Chapter" cannot spill items that "Claim all" would have stopped for.
     *
     * <p>{@code filter} is the view the banner was pressed in and only ever narrows — a banner drawn in
     * a view of choices must not reach past what is on screen, the same promise the footer makes.
     *
     * @param chapterId the chapter to collect from, by id
     * @param filter    what the sweep is allowed to touch
     * @return how many rewards were handed over
     */
    public static int claimChapter(MinecraftServer server, ServerPlayer player, String chapterId,
                                   ClaimFilter filter) {
        return sweep(server, player, filter, chapterId == null ? "" : chapterId);
    }

    /**
     * The sweep both bulk presses run: one chapter when {@code chapterId} is non-null, the whole book
     * when it is null.
     *
     * <p>The quests are found here rather than named by the caller: a client sending a list of ids
     * would be a client deciding what it is owed. This asks the same {@link #canClaimFor} the single
     * claim does, so the buttons cannot disagree about what may be taken. {@code filter} is the panel's
     * active view and only ever narrows — a view of choices must not reach past what is on screen.
     *
     * <p>Strict, and it stops at the first reward that does not fit: a sweep never spills items on the
     * floor, and the player is told how far it got. The count it reports against is taken before
     * anything is claimed, from the same classification the claim itself uses.
     *
     * @param chapterId the chapter to keep, or null for every chapter
     * @return how many rewards were handed over
     */
    private static int sweep(MinecraftServer server, ServerPlayer player, ClaimFilter filter,
                             String chapterId) {
        UUID playerId = player.getUUID();
        UUID owner = progressOwner(server, player);
        QuestSettings settings = TenetQuests.settings();

        // What the sweep could take with infinite room, counted before it starts: this is the "of M"
        // the summary needs, and it comes from the same loop that decides what to claim.
        int total = 0;
        for (QuestIndex.QuestEntry entry : TenetQuests.index().quests()) {
            if (!inScope(entry, chapterId)) {
                continue;
            }
            TeamProgress team = ProgressStore.of(server).progressOf(owner);
            if (!canClaimFor(server, team, entry.quest(), playerId)) {
                continue;
            }
            total += payable(server, player, owner, team, entry.quest(), team.progressOf(entry.quest()),
                    settings, true, -1, filter).indexes().size();
        }

        int claimed = 0;
        boolean halted = false;
        // One tally for the whole sweep: twenty quests' overflow is one fact about one press, and a
        // sentence per quest would be a wall the player cannot read behind the book.
        RewardFeedback feedback = new RewardFeedback();
        for (QuestIndex.QuestEntry entry : TenetQuests.index().quests()) {
            if (!inScope(entry, chapterId)) {
                continue;
            }
            TeamProgress team = ProgressStore.of(server).progressOf(owner);
            if (!canClaimFor(server, team, entry.quest(), playerId)) {
                continue;
            }
            Claimed result = claim(server, player, entry, true, -1, feedback, filter, true);
            claimed += result.taken();
            if (result.halted()) {
                halted = true;
                break;
            }
        }
        announceOverflow(player, feedback);
        if (claimed > 0 || halted) {
            announceSummary(player, claimed, total, halted);
        }
        return claimed;
    }

    /**
     * Whether a quest is inside the sweep's scope.
     *
     * <p>Read off the index entry's own chapter rather than looked up again — the entry carries it,
     * which is the same fact {@link #autoClaimFor} reads a few lines down for the same reason.
     */
    private static boolean inScope(QuestIndex.QuestEntry entry, String chapterId) {
        return chapterId == null || entry.chapter().id().equals(chapterId);
    }

    /**
     * Grants a player any automatic rewards still owed to them.
     *
     * <p>The offline half of auto-claiming: a quest that completes while a member is away marks the
     * team-mode rewards and the online members' own, and this is where the absent member collects
     * theirs — on the next join, as legacy packs do on login.
     *
     * @return whether anything was granted
     */
    public static boolean autoClaimFor(MinecraftServer server, ServerPlayer player) {
        QuestSettings settings = TenetQuests.settings();
        if (settings.suppressAllAutoclaiming()) {
            return false;
        }
        UUID owner = progressOwner(server, player);
        ProgressStore store = ProgressStore.of(server);
        TeamProgress team = store.progressOf(owner);
        // One tally for the whole login sweep, announced once at the end: a player who was away for
        // three quests' worth of rewards is owed one sentence, not one per quest.
        RewardFeedback feedback = new RewardFeedback();

        boolean grantedAny = false;
        for (QuestIndex.QuestEntry entry : TenetQuests.index().quests()) {
            Quest quest = entry.quest();
            // The same ladder the completion path resolves, so a quest auto-claims on join exactly as
            // it would have at completion. The entry carries its chapter, so no second lookup.
            RewardAutoClaim fileDefault =
                    quest.autoClaim(entry.chapter().autoClaim().resolved(settings.defaultAutoClaim()));
            QuestProgress current = team.progressOf(quest);
            // The join-time half of the stage gate: a member who was away and never held the stage is
            // not paid on login either. Nothing is marked, so the reward is still owed, and this sweep
            // pays it the first time they log in holding the stage.
            if (current.state() != QuestState.COMPLETED || current.legacySettled()
                    || !stageGateOpen(server, quest, player.getUUID())) {
                continue;
            }
            List<Integer> owed = new ArrayList<>();
            QuestClaims marked = current.claims();
            for (int index = 0; index < quest.rewards().size(); index++) {
                QuestReward reward = quest.rewards().get(index);
                boolean teamMode = reward.common().teamReward(settings.defaultTeamReward());
                if (!reward.autoGrantable()
                        || !reward.common().autoClaim(fileDefault).automatic()
                        || isBlocked(team, quest, reward)
                        || current.claimed(player.getUUID(), index, teamMode)) {
                    continue;
                }
                // The join-time half of the same gate: an offline member collects on their next login,
                // and if the conditions do not hold then either, the reward stays owed until they do.
                if (!Conditions.passes(reward.common().conditions(),
                        new ConditionContext(player, server, owner))) {
                    continue;
                }
                owed.add(index);
                marked = teamMode ? marked.withTeamClaim(index)
                        : marked.withPlayerClaim(player.getUUID(), index);
            }
            if (owed.isEmpty()) {
                continue;
            }
            QuestProgress updated = current.withClaims(marked);
            updated = updated.withRewardsClaimed(
                    !outstandingFor(quest, updated, player.getUUID(), settings));
            team = team.put(quest, updated);
            store.put(owner, team);
            grantRewards(server, owner, player, entry, owed, settings, feedback);
            grantedAny = true;
        }
        announceOverflow(player, feedback);
        return grantedAny;
    }

    /**
     * Whether completing this quest now would actually change anything.
     *
     * <h2>Why this is a question that needs asking at all</h2>
     *
     * <p>Because a finished quest is not always a settled one, now that collecting is a separate act.
     * Three states look alike from a distance and are not:
     *
     * <ul>
     *   <li><b>Not finished</b> — completing it changes everything.</li>
     *   <li><b>Finished, nothing owed</b> — a quest with no rewards, or one whose payout has been
     *       collected. Non-repeatable ones are done for good.</li>
     *   <li><b>Finished, payout waiting</b> — the state manual claiming creates. The quest is
     *       <i>playable</i> in the engine's eyes (its tasks are satisfied, and its cooldown decides
     *       the rest), so nothing stops the tick loop reaching it again. Completing it a second time
     *       would overwrite the unclaimed payout with another unclaimed payout and destroy the only
     *       record that anything was owed. The player would be told they had something to collect,
     *       ask for it, and be told there was nothing.</li>
     * </ul>
     *
     * <p>So this is the one definition of "would complete do anything", and it has three callers that
     * each need it for a different reason: {@link #complete} to refuse, {@link #evaluateTeam} to avoid
     * reporting a refusal as a change, and {@code /tenet complete} to avoid claiming success for a
     * no-op. Three copies of that condition would be three chances for the guard and the reporting to
     * disagree.
     */
    public static boolean canComplete(Quest quest, TeamProgress progress) {
        QuestProgress current = progress.progressOf(quest);
        if (current.state() != QuestState.COMPLETED) {
            return true;
        }
        // Finished. Only a repeatable quest can be finished again, and only once whatever it was
        // holding has been collected -- or when it never held anything.
        return quest.repeatable() && (quest.rewards().isEmpty() || current.rewardsClaimed());
    }

    /**
     * Whether a quest's dependency gate is open, for the flexible quests that measure early.
     *
     * <p>Always true for a quest that is not flexible: those only ever measure while playable, and
     * playable means the gate already opened. A flexible quest accumulates task progress while its
     * dependencies are unmet, so its completion needs this second half — tasks satisfied <b>and</b>
     * gate open — judged on already-resolved states rather than a fresh resolution per quest.
     *
     * <p>Called with the pass's own resolution, which is stale by up to one pass for chains that
     * complete mid-pass: a flexible quest whose gate opened earlier in this same pass completes on
     * the next tick rather than this one. Fifty milliseconds, invisible, and bounded — and the
     * alternative (re-resolving per quest) would turn every tick into a quadratic walk.
     */
    private static boolean gateOpenForFlexible(QuestIndex index, QuestIndex.QuestEntry entry, Quest quest,
                                               Map<String, QuestState> states) {
        return !ProgressionEngine.isFlexible(quest, entry.chapter())
                || ProgressionEngine.dependenciesSatisfied(index, entry, states);
    }

    /**
     * Ends a repeatable round whose payout this claim just finished collecting.
     *
     * <p>One helper for both payout paths that mark claims — the claim sweep and the choice answer
     * — because the round ends the same way whichever press collected it, and two copies of "the
     * last unclaimed reward was just claimed" would be two chances to disagree about when a round
     * is over. The timer starts here, the tasks reset here, and the count moves here; a round with
     * nothing to wait for never reaches this, because its completion already ended it.
     */
    private static QuestProgress endRepeatableRoundIfCollected(MinecraftServer server, Quest quest,
                                                               QuestProgress before,
                                                               QuestProgress updated) {
        if (quest.repeatable() && !quest.rewards().isEmpty()
                && updated.rewardsClaimed() && !before.rewardsClaimed()) {
            return updated.repeatRoundOver(server.overworld().getGameTime());
        }
        return updated;
    }

    /**
     * Whether <b>this player</b> is finished and has something still to collect.
     *
     * <p>The one place that question is answered, because it is asked from four: the command, the
     * progress wire format's hint, the claim-all walk and (through the wire) the screen's Claim
     * button. Three copies of the condition would be three chances for the button to appear on a
     * quest the server would refuse.
     *
     * <p>The stage gate is part of the question, which is why the server is a parameter: a stage is a
     * per-player fact and the record's state is the team's, so a quest can be finished for the team
     * and refuse this player. Without it, `/tenet progress` offered a claim the claim path answered
     * with "Nothing to collect", and claim-all counted one it would not take.
     */
    public static boolean canClaimFor(MinecraftServer server, TeamProgress progress, Quest quest, UUID player) {
        QuestProgress current = progress.progressOf(quest);
        if (current.state() != QuestState.COMPLETED || current.legacySettled()
                || quest.rewards().isEmpty()
                || !stageGateOpen(server, quest, player)) {
            return false;
        }
        QuestSettings settings = TenetQuests.settings();
        return outstandingFor(quest, current, player, settings);
    }

    /**
     * Whether <i>anyone</i> could still have something to collect on this quest.
     *
     * <p>A hint for the wire, not a decision: player-mode rewards are outstanding for every member
     * who has not claimed, which a team-scoped payload cannot enumerate per recipient. The client
     * answers the per-player question itself from the claims it is sent; see {@code ClientQuestCache}.
     */
    public static boolean anyoneCouldClaim(TeamProgress progress, Quest quest) {
        QuestProgress current = progress.progressOf(quest);
        if (current.state() != QuestState.COMPLETED || current.legacySettled()
                || quest.rewards().isEmpty()) {
            return false;
        }
        QuestSettings settings = TenetQuests.settings();
        for (int index = 0; index < quest.rewards().size(); index++) {
            if (!teamReward(quest, index, settings) || !current.claims().team().contains(index)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Hands the chosen rewards to one player.
     *
     * <p>Any reward — team-mode or not — is granted to the player who claimed it, which is what the
     * legacy reward code does: {@code team: true} changes whose claim settles the reward (the
     * team's), not who receives the effect. What each member gets for themselves is the default, and
     * what an auto-claim hands out per member; this method is the single payout step both use.
     */
    private static void grantRewards(MinecraftServer server, UUID owner, ServerPlayer player,
                                     QuestIndex.QuestEntry entry, List<Integer> indexes,
                                     QuestSettings settings, RewardFeedback feedback) {
        Quest quest = entry.quest();
        if (indexes.isEmpty()) {
            return;
        }
        // The context carries what a reward may legitimately need to name -- the team, the ids for a
        // command's placeholders, the online members for a count. None of it is progress; see
        // RewardContext for where that line is drawn. The feedback tally is the caller's, so every
        // reward this operation grants -- including items rolled from a nested table -- reports into
        // the one sentence the operation will say.
        RewardContext context = new RewardContext(player, server, owner, quest.id(), entry.chapterId(),
                onlineMembersOf(server, teamFor(server, owner)), feedback);
        for (int index : indexes) {
            QuestReward reward = quest.rewards().get(index);
            Optional<dev.ellipog.tenet.quest.reward.RewardBehaviour<QuestReward>> behaviour =
                    RewardTypes.behaviourOf(reward);
            if (behaviour.isEmpty()) {
                Constants.LOG.warn("no behaviour registered for reward type {}; skipped", reward.type());
                continue;
            }
            try {
                behaviour.get().grant(reward, context);
            }
            catch (RuntimeException e) {
                // One bad reward must not abort the rest, and must not leave the quest unclaimed --
                // which would re-grant everything on the next evaluation.
                Constants.LOG.error("granting a {} reward failed; the rest were still given",
                        reward.type(), e);
            }
            TenetEvents.REWARD_CLAIMED.invoker().onRewardClaimed(player, quest, reward);
        }
    }

    /**
     * Says the one sentence an overflowing operation owes the player: how much hit the ground, in the
     * action bar, with a click that is not the claim chime.
     *
     * <p>The client payload is not decoration. The action bar belongs to the HUD, and the HUD is not
     * drawn behind an open screen — so a player claiming from the rewards panel, which is the common
     * case, would hear the sound and read nothing. The book draws the same sentence on its own toast
     * stack; see {@code QuestNotifier} for the same routing decision made for completions.
     *
     * <p>The sound is deliberately <b>not</b> the {@code ITEM_PICKUP} the client plays when a claim
     * succeeds: "something did not fit" has to sound different from "collected", or the one moment the
     * player needs to look down sounds like everything is fine.
     */
    private static void announceOverflow(ServerPlayer player, RewardFeedback feedback) {
        if (!feedback.anythingDropped()) {
            return;
        }
        player.displayClientMessage(
                Component.translatable("tenet.reward.inventory_full_count", feedback.droppedStacks()), true);
        player.playNotifySound(SoundEvents.DISPENSER_FAIL, SoundSource.PLAYERS, 0.7F, 1.4F);
        ArmatureNetwork.sendToPlayer(player,
                new RewardOverflowPayload(feedback.droppedStacks()));
    }

    /**
     * Says how a sweep ended, in one sentence: how many rewards it took, out of how many it could.
     *
     * <p>A sweep that stops early and one that finishes look identical from the inbox — rows disappear
     * either way — so the sentence is what tells the player whether to make room and come back, or that
     * they are done. The action bar carries it, and the payload carries it into the book, where the HUD
     * is not drawn; the same routing the overflow notice uses. See {@link ClaimSummaryPayload}.
     *
     * <p>A halt plays the "did not fit" click an overflow plays — it is the same fact — while a
     * successful sweep stays silent: the claim notices already play the pickup chime as the rows go.
     */
    private static void announceSummary(ServerPlayer player, int claimed, int total, boolean halted) {
        player.displayClientMessage(halted
                ? Component.translatable("tenet.reward.claim_halted", claimed, total)
                : Component.translatable("tenet.reward.claim_done", claimed, total), true);
        if (halted) {
            player.playNotifySound(SoundEvents.DISPENSER_FAIL, SoundSource.PLAYERS, 0.7F, 1.4F);
        }
        ArmatureNetwork.sendToPlayer(player, new ClaimSummaryPayload(claimed, total, halted));
    }

    // ------------------------------------------------------------------
    // Submitting by hand
    // ------------------------------------------------------------------

    /**
     * A player submits one task by hand — a checkmark, or an item task that consumes.
     *
     * <p>Refused when the task cannot be satisfied right now, so a submit button that is showing
     * cannot be used to skip the requirement. For an item task the check is "they actually have the
     * items"; for a checkmark there is nothing to check, which is the point of a checkmark.
     *
     * @return whether anything changed
     */
    public static boolean submit(MinecraftServer server, ServerPlayer player, QuestIndex.QuestEntry entry, int taskIndex) {
        Quest quest = entry.quest();
        if (taskIndex < 0 || taskIndex >= quest.tasks().size()) {
            return false;
        }

        UUID owner = progressOwner(server, player);
        ProgressStore store = ProgressStore.of(server);
        TeamProgress progress = store.progressOf(owner);
        long now = server.overworld().getGameTime();

        // Keep it honest: submitting has to be allowed for the quest.
        QuestState state = ProgressionEngine.resolve(TenetQuests.index(), progress, now).stateOf(quest);
        if (!state.isPlayable()) {
            return false;
        }

        QuestTask task = quest.tasks().get(taskIndex);
        QuestProgress questProgress = progress.progressOf(quest);

        // A final snapshot for the lambda below, because `questProgress` is reassigned further down
        // and Java will not let a lambda capture a local that moves. That refusal is correct: a
        // deferred lambda reading a variable that changes underneath it is a bug waiting to be
        // written. And a snapshot is the right reading anyway -- isTaskUnlocked runs its predicate
        // synchronously, so it should judge against the progress as it stands at this moment, before
        // this submit changes anything.
        final QuestProgress beforeSubmit = questProgress;

        boolean submitChapterSequential = chapterOf(TenetQuests.index(), entry)
                .map(c -> c.rules().defaultSequentialTasks()).orElse(false);
        if (!quest.isTaskUnlocked(taskIndex,
                earlier -> ProgressionEngine.isTaskSatisfied(quest, earlier, beforeSubmit),
                submitChapterSequential)) {
            return false;
        }

        // The gate, checked at the press rather than left to the tick: a submit button showing for a
        // task whose conditions are unmet is the skip this guards against, the same way the count check
        // below guards the items.
        if (!Conditions.passes(task.common().conditions(), new ConditionContext(player, server, owner))) {
            player.displayClientMessage(Component.translatable("tenet.quest.conditions_unmet"), true);
            return false;
        }

        Optional<dev.ellipog.tenet.quest.task.TaskBehaviour<QuestTask>> behaviour = TaskTypes.behaviourOf(task);
        boolean chapterConsumes = chapterOf(TenetQuests.index(), entry)
                .map(Chapter::defaultConsumeItems).orElse(false)
                || TenetQuests.settings().defaultConsumeItems();
        // `acceptsClientSubmit`, not `canSubmitByHand`: a task may have no button and still be
        // submitted by the client that did the work -- an observation's watching is exactly that. See
        // the two methods on TaskBehaviour. Both are asked with the chapter's default, because an item
        // task that does not say whether it consumes inherits it, and the button and the take have to
        // be the same answer.
        if (behaviour.isEmpty() || !behaviour.get().acceptsClientSubmit(task, chapterConsumes)) {
            return false;
        }

        if (behaviour.get().takesResources(task, chapterConsumes)
                || behaviour.get().manualOnly(task)) {
            int required = behaviour.get().required(task);
            // The count the press has to meet is the one the row showed, which is the party's under
            // the party's own mode -- the same reading the tick takes. Judging it by the presser's own
            // inventory alone would refuse a pooled party a task the engine would have satisfied for
            // them, and would make the button's own count a lie.
            //
            // Asked before the take, never after: a press the party cannot pay is the skip this
            // guards against.
            Team team = teamFor(server, owner);
            List<ServerPlayer> members = onlineMembersOf(server, team);
            if (members.isEmpty()) {
                return false;
            }
            PartyMode mode = PartyStore.of(server).modeOf(owner);
            int ownerIndex = indexOfMember(members, team.owner());
            PartyCounts counts = countMembers(TenetQuests.index(), server, owner, members,
                    asksFor(server, owner, members, TenetQuests.index(), now), task,
                    behaviour.get(), now);
            PartyMode.Tally tally = mode.combine(counts.perMember(), ownerIndex);
            if (tally.counted() < required) {
                player.displayClientMessage(Component.translatable("tenet.quest.not_enough"), true);
                return false;
            }
            // The take itself only happens for a task that takes what it asks for: a manual-only
            // presence task is measured above but keeps everything, which is what makes it a press
            // rather than a tick with a button.
            if (behaviour.get().takesResources(task, chapterConsumes) && mode.takesFromEveryone()) {
                // From the contributors, exactly as the tick would have: an ungated member's items
                // were never counted toward the requirement, so they are not the requirement's to take.
                consumeAcross(counts.contributors(), task, required, behaviour.get());
            }
            else if (behaviour.get().takesResources(task, chapterConsumes)) {
                // The payer the tally names -- the largest holder under ONE_MEMBER, the owner under
                // OWNER_ONLY. No payer means the party holds nothing, which the count above refused.
                ServerPlayer payer = tally.hasPayer() ? members.get(tally.payer()) : player;
                behaviour.get().take(task, payer, required);
            }
        }

        questProgress = questProgress.recordTask(taskIndex, behaviour.get().required(task));
        progress = progress.put(quest, questProgress);
        store.put(owner, progress);

        // A submit always satisfies its task -- that is what the press means -- so both events fire
        // here rather than being inferred later.
        TenetEvents.TASK_COMPLETED.invoker().onTaskCompleted(player, quest, taskIndex);
        if (!beforeSubmit.anyTaskProgress()) {
            TenetEvents.QUEST_STARTED.invoker().onQuestStarted(player, quest);
        }

        // Submitting can finish the quest, and for a checkmark that is the only way it ever will.
        if (ProgressionEngine.tasksSatisfied(quest, questProgress)) {
            complete(server, owner, player, entry, progress);
        }
        return true;
    }

    // ------------------------------------------------------------------
    // Resetting
    // ------------------------------------------------------------------

    /**
     * Clears progress for one quest, or everything.
     *
     * <p>Removes the entry rather than setting it to a locked state, so the quest recomputes from the
     * dependency graph — which is what "reset" has to mean for a quest whose dependencies are also
     * reset.
     *
     * @return how many quests were cleared
     */
    public static int reset(MinecraftServer server, UUID owner, Optional<String> questIdOrAlias) {
        ProgressStore store = ProgressStore.of(server);
        TeamProgress progress = store.progressOf(owner);

        if (questIdOrAlias.isEmpty()) {
            int count = progress.size();
            store.clear(owner);
            LAST_EVALUATED.remove(owner);
            return count;
        }

        Optional<QuestIndex.QuestEntry> entry = TenetQuests.find(questIdOrAlias.get());
        if (entry.isEmpty()) {
            return 0;
        }
        store.put(owner, progress.remove(entry.get().quest()));
        LAST_EVALUATED.remove(owner);
        return 1;
    }

    /**
     * Whether the team holds any stored progress for a quest — finished, partial, or merely seen.
     *
     * <p>The question the reset press asks before it answers: clearing a quest nobody has touched
     * is a no-op, and a button that goes quiet on a no-op reads as broken. Asked here rather than
     * in the handler, because what "holds progress" means is this file's to say.
     */
    public static boolean hasProgress(MinecraftServer server, UUID owner, Quest quest) {
        return ProgressStore.of(server).progressOf(owner).progressOf(quest) != QuestProgress.NONE;
    }

    /**
     * Clears progress for every quest in one chapter.
     *
     * <p>The chapter is a scope, not a list: the members are read off the server's own index under
     * the chapter's canonical id, so an alias, a rename and a forged packet all resolve the same way — and each member is cleared through {@link #reset(MinecraftServer, UUID, Optional)},
     * which is what keeps this from disagreeing with the single-quest press about what "reset"
     * means. A chapter id matching nothing clears nothing.
     *
     * <p>Counted honestly, unlike the single reset: only quests that actually held progress are
     * counted, so the caller can tell "cleared three" from "there was nothing to clear" — which is
     * the sentence the menu's press owes its author. See the reset handlers' toast.
     *
     * @return how many quests held progress and were cleared
     */
    public static int resetChapter(MinecraftServer server, UUID owner, String chapterId) {
        Optional<QuestIndex.ChapterEntry> chapter = TenetQuests.index().chapter(chapterId);
        if (chapter.isEmpty()) {
            return 0;
        }
        ProgressStore store = ProgressStore.of(server);
        int cleared = 0;
        for (QuestIndex.QuestEntry each : TenetQuests.index().questsIn(chapter.get().chapter().id())) {
            if (store.progressOf(owner).progressOf(each.quest()) == QuestProgress.NONE) {
                continue;
            }
            cleared += reset(server, owner, Optional.of(each.quest().id()));
        }
        return cleared;
    }

    // ------------------------------------------------------------------
    // Small helpers
    // ------------------------------------------------------------------

    /**
     * The team a progress owner's id resolves to — a real team, or a solo one.
     *
     * <p>A solo player is a team of one whose id <b>is</b> their own UUID — see {@code Team.solo} —
     * so the two cases differ only in where the ids come from, and neither needs a branch at the call
     * site. That is the whole reason progress for a lone player is addressable without storing
     * anything.
     *
     * <p>Split out from {@link #onlineMembersOf} because the team itself is now needed twice: for its
     * members, and for <b>who its owner is</b>, which {@link PartyMode#OWNER_ONLY} cannot be applied
     * without. Reading the team once and passing it is what keeps those two answers from being
     * fetched separately and disagreeing — the shape of fault this file has already paid for once,
     * when "near the bottom right" was written twice.
     */
    private static Team teamFor(MinecraftServer server, UUID owner) {
        return Teams.of(server).byId(owner).orElseGet(() -> Team.solo(owner));
    }

    /**
     * The members of {@code team} who are online, in a stable order.
     *
     * <p>Sorted by id, and the sort is doing real work. {@code Team.memberIds()} comes from an
     * immutable map, whose iteration order is deliberately unspecified; without this, "the first
     * online member" would be a different player from one run to the next, and the reward fallback
     * above would be arbitrary in a way that looks like flakiness.
     *
     * <p>The sort is load-bearing a second time now that a mode can name a member by <i>index</i>:
     * {@code PartyMode.ONE_MEMBER} pays the earliest member holding the largest count, and a party
     * whose two members both hold eight logs would otherwise hand the reward to whichever of them the
     * map felt like iterating first. An arbitrary choice that is stable is inspectable; one that is
     * not is a bug report nobody can reproduce.
     *
     * <p>Offline members are skipped rather than found, and that is the honest reading: an inventory
     * that is not loaded cannot be counted. It also means a party does not lose what it recorded when
     * somebody logs off — the count only ever went up, and it stays where it got to.
     */
    private static List<ServerPlayer> onlineMembersOf(MinecraftServer server, Team team) {
        List<UUID> ids = new ArrayList<>(team.memberIds());
        // Natural UUID order rather than lexicographic-string order: the sort is load-bearing for
        // stability (who counts as "first" must not wander), and the spelling is not — so the
        // thirty-six characters per comparison go away while the stability stays. Indexes built
        // from this order are per-pass and never persisted, so no stored data depends on it.
        ids.sort(UUID::compareTo);

        List<ServerPlayer> online = new ArrayList<>(ids.size());
        for (UUID member : ids) {
            ServerPlayer found = server.getPlayerList().getPlayer(member);
            if (found != null) {
                online.add(found);
            }
        }
        return online;
    }

    /**
     * Where {@code player} sits in the online member list, or −1 if they are not in it.
     *
     * <p>Against {@link #onlineMembersOf}'s sorted order, which is the same list the counts were
     * gathered from — so an index handed to a mode refers to the same member the count at that index
     * came from. Computing the order twice, in two places, is exactly how those two would come to
     * disagree.
     *
     * <p>Returns −1 rather than throwing for a player who is not online. That is a real state rather
     * than a caller's mistake: {@code OWNER_ONLY} asked of a party whose owner has logged off has no
     * owner to point at, and the mode's own answer to that — nothing counts — is the right one. See
     * {@code PartyMode.OWNER_ONLY}.
     */
    private static int indexOfMember(List<ServerPlayer> members, UUID player) {
        for (int i = 0; i < members.size(); i++) {
            if (members.get(i).getUUID().equals(player)) {
                return i;
            }
        }
        return -1;
    }

    /**
     * Whether a task is due to be evaluated again.
     *
     * <h2>Two ways to be due, and the second one is a bug that shipped</h2>
     *
     * <p><b>Never evaluated.</b> {@code null}, kept out of the arithmetic rather than represented by a
     * sentinel. This used to be {@code Long.MIN_VALUE}, which is wrong for every input:
     * {@code now - Long.MIN_VALUE} overflows to a <i>negative</i> number, so the test read "not yet
     * due" on the very first look — and because the caller only records {@code now} once the test
     * passes, the key was never written and the test stayed true forever. Every item task was skipped
     * for the life of the server: gather eight logs, watch nothing happen.
     *
     * <p>It survived because of where it lives. The engine's <i>rules</i> are pure functions and
     * {@code ProgressionEngineTest} checks those thoroughly; this is scheduling, and a scheduler has
     * no output to assert on until something is running. A playthrough with a real player showed that
     * a full inventory completed nothing.
     *
     * <p><b>The clock went backwards.</b> {@code now < lastAt}, which is neither a clock error nor
     * hypothetical. Game time is per-world and starts at zero, while this schedule lives for the life
     * of the <i>process</i>. A player who plays one world and then loads another has the same team id —
     * a solo team's id is the player's own UUID, so the key matches — and a game time smaller than the
     * one recorded in the world they just left. Computed as {@code now - lastAt < interval}, that is a
     * large negative number and reads as "just evaluated": every task is skipped until the new world's
     * clock climbs past the old world's. Minutes of gathering items and seeing nothing happen, and it
     * depends on which world was played first, which is exactly what gets reported as "it worked in my
     * other world and not this one".
     *
     * <p>Treating a backwards clock as <b>due</b> is the honest reading, and it self-corrects on the
     * first tick of the new world because the caller records {@code now} as it goes.
     *
     * <p>{@code tick} does not rely on that branch, though: it clears the schedule outright when the
     * server changes, so in play this branch is a backstop rather than the mechanism. It is kept, and
     * tested, because it is the half of the rule that can be tested without a server -- and because
     * the rule should be true on its own terms. Somebody deleting it as redundant would be deleting
     * the only part of this that a test can reach.
     *
     * @param now      the current game time, for the world being evaluated
     * @param lastAt   when this task was last evaluated, or null if it never has been in this process
     * @param interval the task type's own auto-submit interval, in ticks
     */
    static boolean isDue(long now, Long lastAt, int interval) {
        return lastAt == null || now < lastAt || now - lastAt >= interval;
    }

    /**
     * Takes {@code count} matching items from the party, across as many members as it needs.
     *
     * <h2>Why pooling needs this and the other two modes do not</h2>
     *
     * <p>Because the two halves of a mode have to agree. {@code POOLED} says the party's task is
     * satisfied when its members' counts <b>add up</b> to the requirement — so the natural way to pay
     * for it is the same way it was counted, and taking the whole amount from one member would take
     * what happened to be in that pocket and record the rest as handed over. Four members holding two
     * logs each would finish a gather-eight quest and surrender two logs, and the difference would
     * never be visible: the task is recorded as satisfied either way.
     *
     * <p>So the mode carries both answers — see {@link PartyMode#takesFromEveryone} — and this is the
     * second one. The two cannot drift, because they are read off the same mode that did the counting.
     *
     * <h2>The order is the member order, which is stable</h2>
     *
     * <p>Members are paid from in the same sorted order the counts were read in, so which pockets get
     * lighter is reproducible rather than dependent on map iteration. That matters for a player
     * noticing that their stack went down instead of their friend's, and it matters more for a
     * failure that has to be re-creatable.
     *
     * <p>Stopping early when {@code remaining} reaches zero is the common case rather than an
     * optimisation: the first member usually holds most of it, and the party usually has one member.
     */
    private static void consumeAcross(List<ServerPlayer> members, QuestTask task, int count,
                                      dev.ellipog.tenet.quest.task.TaskBehaviour<QuestTask> behaviour) {
        int remaining = count;
        for (ServerPlayer member : members) {
            if (remaining <= 0) {
                return;
            }
            remaining -= behaviour.take(task, member, remaining);
        }
        // Falling out of the loop with `remaining` above zero is possible in principle and not worth
        // a warning: the count was taken from live inventories a moment ago, and the only way to get
        // here is for an inventory to have changed between the count and the take. What it means is
        // that the party gave what it had, which is all a consuming task can ask of anybody.
    }

    /**
     * One task's counts across a party, as the asking half of an evaluation.
     *
     * <p>Who may contribute at all is a different question from how much they have: a member who
     * fails the task's conditions contributes nothing and — see {@link #consumeAcross} — pays
     * nothing. An empty condition list passes, so an unconditioned task fills this with every member
     * exactly as it always did.
     *
     * <p>Here rather than twice, because the tick and the press have to agree: a press judged by the
     * presser's own inventory alone would refuse a pooled party the tick would have satisfied, and
     * the row that shows the party's count would be lying about what the button does.
     *
     * @param index the quest index the passing task belongs to, for the {@link TaskContext} a
     *              behaviour reads. The tick passes the one it was handed, so one pass sees one
     *              snapshot of the pack.
     */
    private static PartyCounts countMembers(QuestIndex index, MinecraftServer server, UUID owner,
                                            List<ServerPlayer> members, MemberAsks asks,
                                            QuestTask task,
                                            dev.ellipog.tenet.quest.task.TaskBehaviour<QuestTask> behaviour,
                                            long now) {
        List<Integer> perMember = new ArrayList<>(members.size());
        List<ServerPlayer> contributors = new ArrayList<>(members.size());
        for (int m = 0; m < members.size(); m++) {
            ServerPlayer member = members.get(m);
            if (!Conditions.passes(task.common().conditions(), asks.conditions().get(m))) {
                perMember.add(0);
                continue;
            }
            contributors.add(member);
            perMember.add(behaviour.current(task, asks.tasks().get(m)));
        }
        return new PartyCounts(contributors, perMember);
    }

    /** One pass's per-member questions, in member order. See {@link #asksFor}. */
    private record MemberAsks(List<ConditionContext> conditions,
                              List<TaskContext> tasks) {
    }

    /**
     * One condition context and one task context per member, built once per pass and reused
     * across tasks.
     *
     * <p>The subject, the server, the owner, the index and the tick are the same for every task a
     * pass asks about, so per-task contexts were two allocations per member per task for identical
     * answers — and the held member count travels with the condition half, which is what makes
     * the party-size gate free on the same pass. See the count call for why the held count is
     * exact.
     */
    private static MemberAsks asksFor(MinecraftServer server, UUID owner, List<ServerPlayer> members,
                                      QuestIndex index, long now) {
        List<ConditionContext> conditions = new ArrayList<>(members.size());
        List<TaskContext> tasks = new ArrayList<>(members.size());
        for (ServerPlayer member : members) {
            conditions.add(new ConditionContext(member, server, owner, members.size()));
            tasks.add(new TaskContext(member, index, now));
        }
        return new MemberAsks(List.copyOf(conditions), List.copyOf(tasks));
    }

    /** The two lists {@link #countMembers} produces: who is counted, and what each one holds. */
    private record PartyCounts(List<ServerPlayer> contributors, List<Integer> perMember) {
    }

    private static Optional<Chapter> chapterOf(QuestIndex index, QuestIndex.QuestEntry entry) {
        return index.chapter(entry.chapterId()).map(QuestIndex.ChapterEntry::chapter);
    }

    /** Everything a player can act on right now, for a listing. Invisible quests are left out. */
    public static List<QuestIndex.QuestEntry> availableTo(MinecraftServer server, ServerPlayer player) {
        ProgressionEngine.Resolution resolution = resolutionFor(server, progressOwner(server, player));
        List<QuestIndex.QuestEntry> out = new ArrayList<>();
        for (QuestIndex.QuestEntry entry : TenetQuests.index().quests()) {
            if (resolution.stateOf(entry.quest()).isPlayable() && !entry.quest().invisible()) {
                out.add(entry);
            }
        }
        return out;
    }
}
