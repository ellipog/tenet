package dev.ellipog.tenet.progress;

import dev.ellipog.tenet.quest.Chapter;
import dev.ellipog.tenet.quest.PrerequisiteMode;
import dev.ellipog.tenet.quest.ProgressionMode;
import dev.ellipog.tenet.quest.Quest;
import dev.ellipog.tenet.quest.QuestIndex;
import dev.ellipog.tenet.quest.QuestRef;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Works out which quests a team can do, and which it has done.
 *
 * <h2>It reads; it never writes</h2>
 *
 * <p>Everything here is a pure function of the loaded quests, a {@link TeamProgress} and the clock.
 * The state of every quest is <b>recomputed</b> rather than stored, which is deliberate: a stored
 * state can drift out of step with the files, and then a player is permanently locked out of a quest
 * whose dependency they have actually finished. Recomputing costs a walk over the graph, and this
 * graph is a few hundred nodes — a few microseconds, on a tick where anything changed at all.
 *
 * <p>The only thing persisted is what a player <i>did</i>: which tasks are satisfied, how many times a
 * quest was completed, and when. Those are facts that cannot be derived and must be stored. "Is this
 * unlocked" is not a fact — it is a conclusion, and conclusions are cheaper to draw again than to
 * keep in sync.
 *
 * <h2>Cycle safety</h2>
 *
 * <p>Dependencies can form a cycle, because a file can be edited into one. The loader reports it as
 * an error, and this resolves around it rather than hanging or overflowing: a quest already being
 * evaluated higher up the stack comes back LOCKED, so a cycle simply never unlocks. That is the
 * honest answer — no ordering of a cycle satisfies it — and it means a mistake in a quest file
 * produces a quest that cannot be completed plus a clear message at load time, rather than a server
 * that never finishes starting.
 */
public final class ProgressionEngine {

    /** State of every quest, keyed by the quest's own id. */
    public record Resolution(Map<String, QuestState> states,
                             Map<String, QuestState> chapterStates,
                             Map<String, Long> cooldownRemaining,
                             Set<String> excluded) {

        public QuestState stateOf(Quest quest) {
            return states.getOrDefault(quest.id(), QuestState.LOCKED);
        }

        /**
         * How far one chapter has got, by its own id.
         *
         * <p>{@link QuestState#UNLOCKED} for a chapter this resolution has never heard of, which is the
         * reading that hides least: an unknown id is a caller asking about a chapter that is not in the
         * tree, and answering LOCKED there would shut a screen for a fact nobody stated.
         */
        public QuestState chapterStateOf(String chapterId) {
            return chapterStates.getOrDefault(chapterId, QuestState.UNLOCKED);
        }

        /** Ticks until a repeatable quest can be done again, or zero. */
        public long cooldownOf(Quest quest) {
            return cooldownRemaining.getOrDefault(quest.id(), 0L);
        }

        public int unlockedCount() {
            return (int) states.values().stream().filter(state -> state != QuestState.LOCKED).count();
        }

        public int completedCount() {
            return (int) states.values().stream().filter(state -> state == QuestState.COMPLETED).count();
        }
    }

    private ProgressionEngine() {
    }

    /**
     * Whether a quest's tasks may be worked on before its dependencies are satisfied.
     *
     * <p>the legacy {@code flexible} progression mode: the quest's own flag, or its chapter's
     * default — either true makes it flexible, and there is no opt-out. The chapter default keeps
     * hand-authoring pleasant; a migration tool inlines the resolved value onto each quest and
     * leaves the default off, so the two can never disagree about one quest.
     */
    public static boolean isFlexible(Quest quest, Chapter chapter) {
        return quest.flexibleProgress() || chapter.rules().defaultFlexibleProgress();
    }

    /**
     * Whether a quest's dependencies are satisfied, judged from already-resolved states.
     *
     * <p>The completion half of flexible progress: a flexible quest's tasks may accumulate while
     * its gate is shut, but it completes only once the gate opens. Every dependency counts,
     * optional ones included — legacy packs gate on an optional quest exactly like any other,
     * so skipping one here would open gates the pack keeps shut.
     *
     * @param states the resolution to judge against: the pass's own map from the resolver, or a
     *               fresh {@link #resolve} for a call site that holds none
     */
    public static boolean dependenciesSatisfied(QuestIndex index, QuestIndex.QuestEntry entry,
                                                Map<String, QuestState> states) {
        Quest quest = entry.quest();
        PrerequisiteMode effective = quest.prerequisiteMode(entry.chapter().defaultPrerequisiteMode());
        QuestState bar = QuestState.bar(effective);
        int satisfied = 0;
        int counted = 0;
        for (QuestRef dependency : quest.dependencies()) {
            Optional<QuestIndex.QuestEntry> target = index.quest(dependency.id());
            counted++;
            QuestState state = target
                    .map(found -> states.getOrDefault(found.quest().id(), QuestState.LOCKED))
                    .orElse(QuestState.LOCKED);
            if (state.isAtLeast(bar)) {
                satisfied++;
            }
        }
        return satisfied >= PrerequisiteMode.requiredCount(effective, quest.minRequired(), counted);
    }

    /** Resolves every quest in the index. */
    public static Resolution resolve(QuestIndex index, TeamProgress progress, long now) {
        Map<String, QuestState> states = new LinkedHashMap<>();
        Map<String, Long> cooldowns = new LinkedHashMap<>();

        // The chapters first, and in one direction: a chapter's state is a function of stored progress
        // and the chapter graph, and never of what a quest resolved to. That is what stops the two
        // passes from being mutually recursive -- see ChapterStates for why that matters.
        Map<String, QuestState> chapterStates = ChapterStates.resolve(index, progress);

        // Which quest has completed in each exclusive group, keyed by chapter and group so that a
        // group name is scoped to the chapter that declared it. Without the chapter in the key,
        // "smithing" in two chapters would silently exclude across both.
        Set<String> takenExclusiveGroups = new HashSet<>();

        // First pass: find the groups that are already decided, so a quest in one is locked even if
        // its dependencies are met. Done before resolving anything, because the answer must not
        // depend on the order the graph happens to be walked in.
        for (QuestIndex.QuestEntry entry : index.quests()) {
            Quest quest = entry.quest();
            Optional<String> group = quest.exclusiveGroup();
            if (group.isEmpty()) {
                continue;
            }
            if (satisfiedForDependents(quest, progress)) {
                takenExclusiveGroups.add(exclusiveKey(entry.chapterId(), group.get()));
            }
        }

        // Which quests a prerequisite's cap has cut off, decided in the same first pass for the same
        // reason: a cap is a statement about a quest's dependents as a group, so the answer must not
        // depend on which of them the walk happens to reach first.
        Set<String> cappedOut = cappedDependents(index, progress);

        // Quest position within its chapter, needed by linear progression.
        //
        // Taken from each entry rather than recounted from a chapter walk, and that is a fix rather
        // than a tidy-up. The position is what a LINEAR chapter gates on: a version counted from a
        // *different* list than the one the chapter's quests were assembled in can be a prefix that is
        // wrong in the permissive direction, and the way that fails is the way nothing catches -- the
        // chapter unlocks several quests at once, or every quest at once, with no error and no log
        // line, because nothing about the computation looks wrong. One position, written where the
        // chapter's own list is built.
        Map<String, Integer> positionInChapter = new LinkedHashMap<>();
        for (QuestIndex.QuestEntry entry : index.quests()) {
            positionInChapter.put(entry.quest().id(), entry.orderInChapter());
        }

        for (QuestIndex.QuestEntry entry : index.quests()) {
            resolveOne(index, entry, progress, now, states, cooldowns, takenExclusiveGroups,
                    cappedOut, positionInChapter, chapterStates, new ArrayDeque<>());
        }

        return new Resolution(states, chapterStates, cooldowns,
                excludedIds(index, progress, takenExclusiveGroups, cappedOut));
    }

    /**
     * Resolves one quest, resolving its dependencies first.
     *
     * <p>{@code visiting} is the cycle guard: a quest already on the stack returns LOCKED instead of
     * recursing again. The chain is carried rather than a plain set so that a stuck resolver could
     * report the cycle, which is what the loader does with its own copy of this walk.
     */
    private static QuestState resolveOne(QuestIndex index,
                                         QuestIndex.QuestEntry entry,
                                         TeamProgress progress,
                                         long now,
                                         Map<String, QuestState> states,
                                         Map<String, Long> cooldowns,
                                         Set<String> takenExclusiveGroups,
                                         Set<String> cappedOut,
                                         Map<String, Integer> positionInChapter,
                                         Map<String, QuestState> chapterStates,
                                         Deque<String> visiting) {

        Quest quest = entry.quest();

        QuestState known = states.get(quest.id());
        if (known != null) {
            return known;
        }
        if (visiting.contains(quest.id())) {
            // A cycle. Whichever quest is reached twice stays locked, which is the only answer that
            // terminates -- and the loader has already reported the cycle with a proper message.
            return QuestState.LOCKED;
        }
        visiting.push(quest.id());
        try {
            QuestProgress stored = progress.progressOf(quest);

            // Already done, and either not repeatable or still cooling down.
            if (stored.state() == QuestState.COMPLETED) {
                long remaining = stored.cooldownRemaining(now, quest.repeatCooldownTicks());
                cooldowns.put(quest.id(), remaining);
                if (!quest.repeatable() || remaining > 0) {
                    states.put(quest.id(), QuestState.COMPLETED);
                    return QuestState.COMPLETED;
                }
                // Repeatable with the cooldown elapsed: falls through, and resolves as playable again.
            }

            // The chapter's own gate. A quest inside a chapter that is not open yet cannot be reached
            // whatever its own edges say, so this comes before them -- and after the completed
            // short-circuit above, which is what lets a quest finished before its chapter was gated keep
            // its completion. That is the same rule the dependents cap follows: a gate decides what is
            // still available, not what a player has already done.
            if (ChapterStates.gatesQuests(
                    chapterStates.getOrDefault(entry.chapterId(), QuestState.UNLOCKED))) {
                states.put(quest.id(), QuestState.LOCKED);
                return QuestState.LOCKED;
            }

            // Mutually exclusive with something already taken -- but not with *itself*. A group's key is
            // recorded for every quest that is satisfied for its dependents, this one included, so a
            // repeatable quest reached its own key the moment its first round completed and locked
            // itself out for good as soon as the cooldown elapsed (the fall-through at 158-165 above).
            // The group locks siblings; the quest that took it is not its own sibling.
            Optional<String> group = quest.exclusiveGroup();
            if (group.isPresent()
                    && takenExclusiveGroups.contains(exclusiveKey(entry.chapterId(), group.get()))
                    && !(quest.repeatable() && satisfiedForDependents(quest, progress))) {
                states.put(quest.id(), QuestState.LOCKED);
                return QuestState.LOCKED;
            }

            // Cut off by a prerequisite's cap on how many of its dependents may complete. Checked
            // after the completed short-circuit above, so a dependent that finished before the cap was
            // reached keeps its completion -- a cap decides which branches are still available, not
            // which ones a player has already taken.
            if (cappedOut.contains(quest.id())) {
                states.put(quest.id(), QuestState.LOCKED);
                return QuestState.LOCKED;
            }

            // Dependencies. Resolve each first, so this is a depth-first walk of the graph.
            // Every dependency gates, optional ones included: the legacy `optional` only excuses
            // a quest from chapter completion, never from a gate — a pack gating on a side
            // quest means it, and skipping the edge would unlock what the pack keeps locked.
            // Dependency lines are still drawn for every edge.
            PrerequisiteMode effective = quest.prerequisiteMode(entry.chapter().defaultPrerequisiteMode());

            int satisfied = 0;
            int counted = 0;
            for (var dependency : quest.dependencies()) {
                Optional<QuestIndex.QuestEntry> target = index.quest(dependency.id());
                counted++;
                // Unresolved by the loader's reading: index.quest missed, so this resolves to
                // LOCKED, which is what resolveById answers for a name nothing holds. The loader
                // reports the dangling reference as an error; the engine reads it as unmet.
                QuestState dependencyState = target.map(found -> resolveOne(index, found, progress,
                                now, states, cooldowns, takenExclusiveGroups, cappedOut, positionInChapter,
                                chapterStates, visiting))
                        .orElse(QuestState.LOCKED);
                if (dependencyState.isAtLeast(QuestState.bar(effective))) {
                    satisfied++;
                }
            }

            int required = PrerequisiteMode.requiredCount(effective, quest.minRequired(), counted);
            if (satisfied < required) {
                // A flexible quest stays measurable while its gate is shut: its tasks accumulate
                // before its dependencies are done, and completion waits for them — the gate half
                // lives in ProgressService's completion paths, which judge already-resolved states
                // rather than re-resolving here. Everything else about the quest is unchanged: the
                // chapter gate, exclusivity, caps and linear order above still lock it.
                if (!isFlexible(quest, entry.chapter())) {
                    states.put(quest.id(), QuestState.LOCKED);
                    return QuestState.LOCKED;
                }
            }

            // Linear progression: every quest earlier in the chapter must be complete as well.
            int position = positionInChapter.getOrDefault(quest.id(), -1);
            if (position > 0) {
                // The chapter off the entry, rather than looked up: `entry.chapter()` is the very
                // object whose `quests()` list `orderInChapter` was read from, so the position and the
                // list it indexes cannot be two different chapters. See QuestEntry.
                Chapter chapter = entry.chapter();
                if (chapter.progressionMode() == ProgressionMode.LINEAR) {
                    for (Quest earlier : chapter.questsBefore(position)) {
                        QuestState earlierState = resolveById(index, earlier.id(), entry, progress, now, states,
                                cooldowns, takenExclusiveGroups, cappedOut, positionInChapter, chapterStates,
                                visiting);
                        if (earlierState != QuestState.COMPLETED) {
                            states.put(quest.id(), QuestState.LOCKED);
                            return QuestState.LOCKED;
                        }
                    }
                }
            }

            // Unlocked. Whether it is merely unlocked or already started depends on stored progress,
            // which is the one fact that cannot be recomputed.
            QuestState result = stored.state() == QuestState.COMPLETED || stored.anyTaskProgress()
                    ? QuestState.STARTED
                    : QuestState.UNLOCKED;
            states.put(quest.id(), result);
            return result;
        }
        finally {
            visiting.pop();
        }
    }

    /** Resolves a dependency by id or alias. An unresolved dependency locks the dependent. */
    private static QuestState resolveById(QuestIndex index,
                                          String idOrAlias,
                                          QuestIndex.QuestEntry dependent,
                                          TeamProgress progress,
                                          long now,
                                          Map<String, QuestState> states,
                                          Map<String, Long> cooldowns,
                                          Set<String> takenExclusiveGroups,
                                          Set<String> cappedOut,
                                          Map<String, Integer> positionInChapter,
                                          Map<String, QuestState> chapterStates,
                                          Deque<String> visiting) {
        Optional<QuestIndex.QuestEntry> found = index.quest(idOrAlias);
        if (found.isEmpty()) {
            // The loader reports this as an error at load time. Locking the dependent is the safe
            // reading: a quest that cannot be unlocked is visible as broken, whereas one that
            // unlocks for the wrong reason is not.
            return QuestState.LOCKED;
        }
        return resolveOne(index, found.get(), progress, now, states, cooldowns, takenExclusiveGroups,
                cappedOut, positionInChapter, chapterStates, visiting);
    }

    /**
     * How many of a quest's dependencies must be satisfied, counting every edge.
     *
     * <p>The display half of the engine's own counting: {@code /tenet progress} names the same
     * number the resolver enforces, and a second spelling of the denominator is how the two would
     * come to disagree. Unresolved names count as required — the loader reports them, and until
     * they are fixed the quest behaves as gated rather than as open.
     */
    public static int requiredCount(QuestIndex index, Quest quest, PrerequisiteMode effective) {
        int counted = quest.dependencies().size();
        return PrerequisiteMode.requiredCount(effective, quest.minRequired(), counted);
    }

    // ------------------------------------------------------------------
    // Completion
    // ------------------------------------------------------------------

    /**
     * Whether every task that has to be done, has been.
     *
     * <p>The rule the plan names: every task not marked optional, or — if they are <i>all</i> optional
     * — any single one of them. A quest where nothing at all is required would otherwise complete
     * itself the instant it unlocked.
     */
    public static boolean tasksSatisfied(Quest quest, QuestProgress progress) {
        if (quest.tasks().isEmpty()) {
            return true;
        }
        int satisfied = 0;
        for (int index = 0; index < quest.tasks().size(); index++) {
            if (isTaskSatisfied(quest, index, progress)) {
                satisfied++;
            }
        }
        return satisfied >= quest.requiredTaskCount();
    }

    /** Whether one task is done, given how much has been recorded for it. */
    public static boolean isTaskSatisfied(Quest quest, int index, QuestProgress progress) {
        var task = quest.tasks().get(index);
        int required = dev.ellipog.tenet.quest.task.TaskTypes.behaviourOf(task)
                .map(behaviour -> behaviour.required(task))
                .orElse(1);
        return progress.progressOf(index) >= required;
    }

    /**
     * Whether a quest counts as done for anything that depends on it.
     *
     * <p>A repeatable quest is satisfied for its dependents after its <b>first</b> completion, and
     * stays satisfied however many times it is repeated. Otherwise a chain following a repeatable
     * quest would lock again every time the player redid it, which is not what anyone means by
     * repeatable.
     */
    public static boolean satisfiedForDependents(Quest quest, TeamProgress progress) {
        QuestProgress stored = progress.progressOf(quest);
        return stored.state() == QuestState.COMPLETED || stored.timesCompleted() > 0;
    }

    private static String exclusiveKey(String chapterId, String group) {
        return chapterId + ":" + group;
    }

    /**
     * The quests a prerequisite's {@code maxCompletableDependents} has cut off.
     *
     * <h2>What the cap means, and how it differs from an exclusive group</h2>
     *
     * <p>A quest with a cap of N lets at most N of the quests that depend on it be completed; once that
     * many are done, the rest are locked for good. It is the other end of the same idea as
     * {@code exclusiveGroup}: a named group says "these quests exclude each other" wherever they sit,
     * and a cap says "at most N of the things I unlock". A branch with a shared parent and no natural
     * group name wants the cap.
     *
     * <p>Dependents are found by resolving every quest's own {@code dependsOn} back to a quest id, so a
     * dependency written against an alias counts for the quest it names, and a dependent in another
     * chapter counts too -- a cap is a statement about the graph, and the graph crosses files.
     *
     * <p>A dependent that has already completed keeps its completion: it holds a slot, and the quests
     * still available are the ones the cap leaves. That is what "at most N can be completed" means --
     * it is not a rule about which branches a player may start.
     */
    private static Set<String> cappedDependents(QuestIndex index, TeamProgress progress) {
        Map<String, List<String>> dependents = new LinkedHashMap<>();
        for (QuestIndex.QuestEntry entry : index.quests()) {
            for (QuestRef dependency : entry.quest().dependencies()) {
                index.quest(dependency.id()).ifPresent(target -> dependents
                        .computeIfAbsent(target.quest().id(), key -> new ArrayList<>())
                        .add(entry.quest().id()));
            }
        }

        Set<String> capped = new HashSet<>();
        for (Map.Entry<String, List<String>> entry : dependents.entrySet()) {
            int cap = index.quest(entry.getKey())
                    .map(quest -> quest.quest().rules().maxCompletableDependents())
                    .orElse(0);
            if (cap <= 0) {
                continue;
            }
            List<String> unfinished = new ArrayList<>();
            int completed = 0;
            for (String id : entry.getValue()) {
                if (index.quest(id).map(quest -> satisfiedForDependents(quest.quest(), progress))
                        .orElse(false)) {
                    completed++;
                }
                else {
                    unfinished.add(id);
                }
            }
            if (completed >= cap) {
                capped.addAll(unfinished);
            }
        }
        return capped;
    }

    /**
     * The quests an exclusive choice has shut out for good: the legacy "excluded by another
     * questline", as ids.
     *
     * <p>The same first pass {@link #resolve} reads — a taken exclusive group, or a reached
     * dependent cap — restated as a set of ids rather than as states, because the reader that
     * hides excluded quests needs the <i>reason</i> and the state cannot carry it: LOCKED is also
     * what "not yet unlocked" looks like. A quest that is satisfied for its dependents is not its
     * own sibling: the one that took the group, and every dependent that finished before a cap was
     * reached, keep their completion and are never named here.
     *
     * <p>Read by the sync, once per progress message, so the mark and the state travel together
     * and cannot disagree. The engine itself is unchanged: this answers a question about stored
     * progress, and resolution stays the only thing that grants or refuses.
     */
    public static Set<String> excludedIds(QuestIndex index, TeamProgress progress) {
        Set<String> taken = new HashSet<>();
        for (QuestIndex.QuestEntry entry : index.quests()) {
            Quest quest = entry.quest();
            Optional<String> group = quest.exclusiveGroup();
            if (group.isEmpty()) {
                continue;
            }
            if (satisfiedForDependents(quest, progress)) {
                taken.add(exclusiveKey(entry.chapterId(), group.get()));
            }
        }
        return excludedIds(index, progress, taken, cappedDependents(index, progress));
    }

    /**
     * The same answer from already-decided sets, for the resolver to carry on its resolution.
     *
     * <p>The sync reads the excluded mark once per progress message, and re-deriving the taken
     * groups and the dependent caps per message is two walks the resolver has already walked for
     * the same states. Same rule, same sets — the mark and the LOCKED state cannot disagree
     * because they come from one pass.
     */
    private static Set<String> excludedIds(QuestIndex index, TeamProgress progress,
                                           Set<String> taken, Set<String> capped) {
        Set<String> excluded = new HashSet<>(capped);
        for (QuestIndex.QuestEntry entry : index.quests()) {
            Quest quest = entry.quest();
            Optional<String> group = quest.exclusiveGroup();
            // The resolver's own rule, restated: in a taken group, and not the one that took it.
            // A repeatable quest that is satisfied for its dependents is still playable after its
            // cooldown, so it is not shut out — the same exception the resolver makes.
            if (group.isPresent() && taken.contains(exclusiveKey(entry.chapterId(), group.get()))
                    && !(quest.repeatable() && satisfiedForDependents(quest, progress))
                    && !satisfiedForDependents(quest, progress)) {
                excluded.add(quest.id());
            }
        }
        return Set.copyOf(excluded);
    }

    // ------------------------------------------------------------------
    // Cycle detection, for the loader
    // ------------------------------------------------------------------

    /**
     * Finds every dependency cycle, returning each one as the chain of ids that closes it.
     *
     * <p>Called by {@link QuestIndex} at load time so an author is told, rather than discovering it
     * because a quest never unlocks. A cycle is not recoverable — no amount of play satisfies
     * "A needs B and B needs A" — so it is an error with the chain printed, and the engine's own
     * guard keeps it from hanging in the meantime.
     *
     * <p>Returns each cycle once. A graph with a cycle in it would otherwise report the same loop
     * from every node that can reach it, which for a questline is dozens of identical messages.
     */
    public static java.util.List<java.util.List<String>> findCycles(QuestIndex index) {
        Map<String, java.util.List<String>> edges = new LinkedHashMap<>();
        for (QuestIndex.QuestEntry entry : index.quests()) {
            java.util.List<String> targets = new java.util.ArrayList<>();
            for (var dependency : entry.quest().dependencies()) {
                index.quest(dependency.id()).ifPresent(target -> targets.add(target.quest().id()));
            }
            edges.put(entry.quest().id(), targets);
        }

        return cyclesIn(edges);
    }

    /**
     * Finds every cycle in the <b>chapter</b> graph, returning each one as the chain of chapter ids.
     *
     * <h2>Two kinds of edge, and the second is the one that is easy to miss</h2>
     *
     * <p>A chapter waits on the chapters in its {@code dependsOn}, which is one edge. The other comes
     * from {@code completesWhen}: a chapter that says "I am finished when these quests are" cannot be
     * finished unless the chapters holding those quests can be played, so it <i>also</i> waits on them.
     * A file that writes the two against each other — A waits on B, and B is finished by a quest inside
     * A — is a loop that no amount of play can break, and it looks like nothing at all in either file
     * read on its own.
     *
     * <p>Reported like a quest cycle, and for the same reason: it is an author's mistake that otherwise
     * shows up only as a chapter that never opens, with no log line to explain it.
     */
    public static java.util.List<java.util.List<String>> findChapterCycles(QuestIndex index) {
        Map<String, java.util.List<String>> edges = new LinkedHashMap<>();
        for (QuestIndex.ChapterEntry entry : index.chapters()) {
            Chapter chapter = entry.chapter();
            java.util.List<String> targets = new java.util.ArrayList<>();
            for (var dependency : chapter.rules().dependsOn()) {
                index.chapter(dependency.id())
                        .ifPresent(target -> addOnce(targets, target.chapter().id()));
            }
            // The completion edges. A milestone in this chapter is not an edge -- a chapter is allowed
            // to be finished by its own quests, which is the ordinary case.
            for (var milestone : chapter.rules().completesWhen()) {
                index.quest(milestone.id()).ifPresent(quest -> {
                    if (!quest.chapterId().equals(chapter.id())) {
                        addOnce(targets, quest.chapterId());
                    }
                });
            }
            edges.put(chapter.id(), targets);
        }

        return cyclesIn(edges);
    }

    private static void addOnce(java.util.List<String> targets, String id) {
        if (!targets.contains(id)) {
            targets.add(id);
        }
    }

    /**
     * Every cycle in a directed graph, each reported once, as a chain that closes.
     *
     * <h2>Two memories, and why the second one had to change</h2>
     *
     * <p>One holds the set of nodes in each cycle already reported, so a loop reached from several entry
     * points is reported once. The other holds the nodes themselves, so the walk can stop when it
     * reaches a loop it has already described.
     *
     * <p>That second memory used to be the same collection as the first, tested with {@code contains} —
     * and since the first held the <b>rendered</b> cycle strings, that was a substring test, which is
     * not the same question as a set containing an element. A quest called {@code stone} counted as
     * "already reported" by a cycle containing {@code stone_tools}, so its own cycle went unmentioned: a
     * load-time silence about a questline that can never be finished, which is the one thing this pass
     * exists to prevent. A set of ids is exact, and it is what the old test meant to say.
     */
    private static java.util.List<java.util.List<String>> cyclesIn(Map<String, java.util.List<String>> edges) {
        java.util.List<java.util.List<String>> cycles = new java.util.ArrayList<>();
        Set<String> reported = new HashSet<>();
        Set<String> reportedNodes = new HashSet<>();

        for (String start : edges.keySet()) {
            Deque<String> path = new ArrayDeque<>();
            Set<String> onPath = new LinkedHashSet<>();
            walkForCycles(start, edges, path, onPath, cycles, reported, reportedNodes);
        }
        return cycles;
    }

    private static void walkForCycles(String node,
                                      Map<String, java.util.List<String>> edges,
                                      Deque<String> path,
                                      Set<String> onPath,
                                      java.util.List<java.util.List<String>> cycles,
                                      Set<String> reported,
                                      Set<String> reportedNodes) {
        if (onPath.contains(node)) {
            // Found one. Trim the path to start where the cycle starts, so the message reads as a
            // loop rather than as the route that happened to reach it.
            java.util.List<String> chain = new java.util.ArrayList<>(path);
            java.util.Collections.reverse(chain);
            int start = chain.indexOf(node);
            if (start >= 0) {
                java.util.List<String> cycle = new java.util.ArrayList<>(chain.subList(start, chain.size()));
                cycle.add(node);
                // Two memories, and they answer two different questions. `reported` holds the set of
                // nodes in each cycle reported so far, so the same loop reached from a different entry
                // point is only reported once -- a graph with a cycle would otherwise produce one
                // message per node that can reach it, which for a questline is dozens. `reportedNodes`
                // holds the nodes themselves, for the walk's own prune below.
                if (reported.add(new java.util.TreeSet<>(cycle).toString())) {
                    cycles.add(java.util.List.copyOf(cycle));
                }
                reportedNodes.addAll(cycle);
            }
            return;
        }
        if (reportedNodes.contains(node) && !path.isEmpty()) {
            // Already inside a cycle that has been reported; no need to walk it again. A set of ids and
            // not a substring test on the rendered cycles -- see `cyclesIn`.
            return;
        }

        path.push(node);
        onPath.add(node);
        try {
            for (String next : edges.getOrDefault(node, java.util.List.of())) {
                walkForCycles(next, edges, path, onPath, cycles, reported, reportedNodes);
            }
        }
        finally {
            path.pop();
            onPath.remove(node);
        }
    }

    /** Depth of the dependency graph from a quest, for diagnostics. Zero if it has none. */
    public static int depth(QuestIndex index, QuestIndex.QuestEntry entry) {
        return depth(index, entry.quest().id(), new HashSet<>());
    }

    private static int depth(QuestIndex index, String id, Set<String> seen) {
        if (!seen.add(id)) {
            return 0;
        }
        Optional<QuestIndex.QuestEntry> found = index.quest(id);
        if (found.isEmpty()) {
            return 0;
        }
        int best = 0;
        for (var dependency : found.get().quest().dependencies()) {
            best = Math.max(best, 1 + depth(index, dependency.id(), seen));
        }
        return best;
    }
}
