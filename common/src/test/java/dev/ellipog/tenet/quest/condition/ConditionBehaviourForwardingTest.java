package dev.ellipog.tenet.quest.condition;

import net.minecraft.resources.Identifier;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The registry wrapper around every condition behaviour forwards the behaviour.
 *
 * <p>The condition half of {@code TaskBehaviourForwardingTest}, kept for the same reason. The condition
 * behaviour has one method today, so a wrapper that dropped it would look like a condition that is
 * simply never met — not a crash, not a compile error, but every gated task and reward silently
 * refusing. That is the exact shape of the item task's lost {@code takesResources}.
 *
 * <p>The wrapper is called directly, which is why it is package-private: registering a test-only
 * condition type into the shared registry would leave it registered for every other test in the JVM,
 * and the sweeps that walk {@code ids()} nondeterministically start failing in classes that never
 * met it. The same trade, and the same note, as {@code QuestPanelLayout.typeRows(String, Set)}.
 */
@DisplayName("the condition registry's behaviour wrapper")
class ConditionBehaviourForwardingTest {

    private static StageCondition stage() {
        return new StageCondition(Identifier.fromNamespaceAndPath("my_pack", "marked"));
    }

    @Test
    @DisplayName("a behaviour's answer reaches the caller through the wrapper")
    void behaviourSurvivesTheWrapper() {
        ConditionBehaviour<StageCondition> yes = (condition, context) -> true;
        ConditionBehaviour<StageCondition> no = (condition, context) -> false;
        StageCondition condition = stage();

        // A null context on purpose: the test behaviours ignore it, and passing one proves the wrapper
        // does not reach for anything of its own on the way through.
        assertTrue(ConditionTypes.widenBehaviour(yes).test(condition, null),
                "the wrapper dropped the behaviour's answer, which is a gate that never opens");
        assertFalse(ConditionTypes.widenBehaviour(no).test(condition, null),
                "the wrapper answered a constant rather than the behaviour");
    }

    @Test
    @DisplayName("an unregistered condition type evaluates as not met, the safe direction")
    void unknownTypeIsNotMet() {
        // A condition whose type this build cannot find: hand-built, because the dispatch would never
        // decode one. The safe answer for a gate is "not met" -- the other direction is a lock that
        // silently opens. Only unknown types are used here: a registered one would run its behaviour,
        // and a real behaviour reads the server, which a null context does not have.
        QuestCondition unknown = () -> Identifier.fromNamespaceAndPath("some_mod", "missing");
        QuestCondition other = () -> Identifier.fromNamespaceAndPath("some_mod", "missing_too");

        assertFalse(Conditions.passes(List.of(unknown), null));
        assertFalse(Conditions.passes(List.of(other, unknown), null),
                "one unmet condition fails the list, whatever the others are");
    }

    @Test
    @DisplayName("an empty condition list passes, which is what makes no conditions free")
    void emptyListPasses() {
        assertTrue(Conditions.passes(List.of(), null));
        assertTrue(Conditions.unmet(List.of(), null).isEmpty());
    }

    @Test
    @DisplayName("the unmet mask names failing conditions by index, ascending")
    void unmetNamesTheFailingIndices() {
        QuestCondition unknownA = () -> Identifier.fromNamespaceAndPath("some_mod", "missing_a");
        QuestCondition unknownB = () -> Identifier.fromNamespaceAndPath("some_mod", "missing_b");

        assertEquals(List.of(0, 1), Conditions.unmet(List.of(unknownA, unknownB), null));
    }
}
