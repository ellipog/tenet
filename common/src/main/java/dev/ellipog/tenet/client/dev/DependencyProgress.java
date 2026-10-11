package dev.ellipog.tenet.client.dev;

import dev.ellipog.tenet.progress.QuestState;
import dev.ellipog.tenet.quest.PrerequisiteMode;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.Function;

/**
 * How far a quest is from its prerequisites: what the rule asks for, and what has been met.
 *
 * <h2>Why the client needs this at all</h2>
 *
 * <p>Because the canvas and the card both have to answer "is this prerequisite satisfied", and the
 * answer is <b>not</b> "is it completed". Two of the four modes count a dependency that is merely
 * started, so a client that asked about completion drew a satisfied prerequisite as unmet — a line that
 * stayed dark and a card row that showed a cross, for a quest a player could already unlock. The rule
 * fields cross the wire for exactly this, and this class is the one place they are turned into an
 * answer.
 *
 * <p>It is game-free on purpose: the screen cannot be instantiated by a test, so the arithmetic — the
 * bar a dependency must reach, how many are enough, which ones are still missing — lives here where a
 * test can hold it, and the screen asks.
 *
 * <h2>Every dependency counts</h2>
 *
 * <p>Optional quests gate exactly like any other — the legacy `optional` only excuses a quest
 * from chapter completion, never from a gate — so the counts here name every edge, exactly
 * as the engine does. A second spelling of the denominator is how the card's "2 of 3 met"
 * would come to disagree with the unlock.
 */
public record DependencyProgress(PrerequisiteMode mode, int minRequired, List<String> dependencies) {

    public DependencyProgress {
        Objects.requireNonNull(mode, "mode");
        Objects.requireNonNull(dependencies, "dependencies");
        dependencies = List.copyOf(dependencies);
    }

    /** The state a dependency must reach for this rule to count it. */
    public QuestState bar() {
        return QuestState.bar(mode);
    }

    /** How many dependencies must meet the bar. */
    public int required() {
        return PrerequisiteMode.requiredCount(mode, minRequired, counted());
    }

    /** How many of them do. */
    public int satisfied(Function<String, QuestState> states) {
        int met = 0;
        for (String dependency : dependencies) {
            if (satisfies(dependency, states)) {
                met++;
            }
        }
        return met;
    }

    /** Whether the quest's prerequisites are met, so it can be unlocked. */
    public boolean met(Function<String, QuestState> states) {
        return satisfied(states) >= required();
    }

    /** How many dependencies the rule counts: every edge. */
    public int counted() {
        return dependencies.size();
    }

    /**
     * Whether one dependency meets the bar.
     *
     * <p>The per-edge answer the canvas colours a line with. A dependency this client cannot resolve —
     * a quest from a newer server, or one filtered out of the chapter — answers false, which draws it
     * as unmet: a line claiming something is satisfied when the client cannot see it would be worse than
     * one that admits it does not know.
     */
    public boolean satisfies(String dependencyId, Function<String, QuestState> states) {
        QuestState state = states.apply(dependencyId);
        return state != null && state.isAtLeast(bar());
    }

    /** The dependencies still short of the bar, in the order they were declared. */
    public List<String> waitingFor(Function<String, QuestState> states) {
        List<String> waiting = new ArrayList<>();
        for (String dependency : dependencies) {
            if (!satisfies(dependency, states)) {
                waiting.add(dependency);
            }
        }
        return List.copyOf(waiting);
    }
}
