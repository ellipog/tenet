package dev.ellipog.tenet.quest.task;

import dev.ellipog.tenet.quest.ItemRef;
import dev.ellipog.tenet.quest.QuestTask;
import dev.ellipog.tenet.quest.QuestText;
import dev.ellipog.tenet.quest.TaskCommon;

import net.minecraft.resources.Identifier;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Which tasks the tick registers by itself, and which wait for the player to press Submit.
 *
 * <h2>The rule, and the two faults it is made of</h2>
 *
 * <p>A task that <b>takes</b> what it asks for is handed over by hand: {@code tasks.md} promises that
 * "a task that takes something waits for the player to press Submit, because the press is the consent
 * to take it", and {@code XpTask}'s own note says the same about experience. Everything else is
 * registered the moment the count is met, which is what makes a presence-only item task complete by
 * itself.
 *
 * <p>Both halves were broken, in opposite directions. The tick took for every satisfied task that
 * answered {@code takesResources}, so experience and fluid vanished the moment a player happened to be
 * carrying enough — no press, no consent. And an item task that said nothing about consuming answered
 * {@code consumeItems().orElse(false)} to the button question while answering the chapter's default to
 * the take, so a chapter with {@code defaultConsumeItems: true} took the items while the row insisted
 * nothing was taken.
 *
 * <p>So this asserts the rule through {@code TaskTypes.behaviourOf}, which is the only door the engine
 * uses: a test that built the behaviour directly would prove the class right and the wrapper silent.
 */
@DisplayName("the consent to take")
class ConsentToTakeTest {

    private static TaskBehaviour<QuestTask> behaviour(QuestTask task) {
        return TaskTypes.behaviourOf(task).orElseThrow();
    }

    private static ItemTask item(Optional<Boolean> consume) {
        return new ItemTask(TaskCommon.DEFAULT,
                new ItemRef(Identifier.withDefaultNamespace("oak_log"), 8),
                consume, ComponentMatch.STRICT, false, false);
    }

    @Test
    @DisplayName("an item task that says nothing takes the chapter's answer, button and all")
    void sayingNothingInheritsTheChapter() {
        ItemTask task = item(Optional.empty());
        TaskBehaviour<QuestTask> through = behaviour(task);

        assertTrue(through.takesResources(task, true),
                "the chapter's consume-items default is what this task inherits");
        assertTrue(through.canSubmitByHand(task, true),
                "and it is the same answer the button is built from -- asking the task's own field alone "
                        + "is what let the row say nothing was taken while the tick took it");
        assertTrue(through.waitsForSubmit(task, true),
                "so it waits for the press rather than being registered and stripped by the tick");

        assertFalse(through.takesResources(task, false));
        assertFalse(through.canSubmitByHand(task, false));
        assertFalse(through.waitsForSubmit(task, false),
                "with the chapter's default off it is a presence-only task, and the tick registers it");
    }

    @Test
    @DisplayName("a task that states its own answer overrides the chapter, both ways")
    void anExplicitFieldBeatsTheDefault() {
        ItemTask consuming = item(Optional.of(true));
        TaskBehaviour<QuestTask> through = behaviour(consuming);
        assertTrue(through.waitsForSubmit(consuming, false),
                "consumeItems: true is a press even in a chapter that says not to take");

        ItemTask keeping = item(Optional.of(false));
        assertFalse(through.waitsForSubmit(keeping, true),
                "and consumeItems: false is the exception a default exists for: nothing is taken, so "
                        + "there is nothing to consent to and the tick registers it");
    }

    @Test
    @DisplayName("an item tag task reads the same rule as an item task")
    void tagTasksReadTheSameRule() {
        ItemTagTask task = new ItemTagTask(TaskCommon.DEFAULT,
                Identifier.withDefaultNamespace("logs"), 8, Optional.empty(), false, false);
        TaskBehaviour<QuestTask> through = behaviour(task);

        assertTrue(through.canSubmitByHand(task, true));
        assertTrue(through.waitsForSubmit(task, true));
        assertFalse(through.waitsForSubmit(task, false));
    }

    @Test
    @DisplayName("experience and fluid always wait for the press, chapter default or not")
    void experienceAndFluidAreAlwaysHandedOver() {
        XpTask xp = new XpTask(TaskCommon.DEFAULT, 30, true);
        assertTrue(behaviour(xp).waitsForSubmit(xp, false),
                "the chapter's consume-items default is about items; a quest that asks for experience "
                        + "and lets you keep it is not a trade, and taking it unasked is the fault this "
                        + "rule exists to stop");

        FluidTask fluid = new FluidTask(TaskCommon.DEFAULT,
                Identifier.withDefaultNamespace("water"), 1000, false);
        assertTrue(behaviour(fluid).waitsForSubmit(fluid, false));
    }

    @Test
    @DisplayName("waiting is about what is taken, not about whether a button exists")
    void aButtonWithNothingToTakeIsStillRegisteredByTheTick() {
        CheckmarkTask check = new CheckmarkTask(new TaskCommon(false, 20, java.util.List.of(), false,
                java.util.Optional.of(QuestText.literal("I read the sign")),
                java.util.Optional.empty(), java.util.List.of()));
        TaskBehaviour<QuestTask> through = behaviour(check);

        assertTrue(through.canSubmitByHand(check, false), "a checkmark is only ever a press");
        assertFalse(through.takesResources(check, false));
        assertFalse(through.waitsForSubmit(check, false),
                "and it takes nothing, so there is no consent to wait for -- the press is what moves a "
                        + "checkmark because its count is zero, not because the tick is held back");

        DimensionTask elsewhere = new DimensionTask(TaskCommon.DEFAULT,
                Identifier.withDefaultNamespace("the_nether"));
        assertFalse(behaviour(elsewhere).waitsForSubmit(elsewhere, true),
                "and a type with no button at all is registered too, rather than being stranded");
    }

    @Test
    @DisplayName("a manual-only presence task waits for the press and keeps its button")
    void manualOnlyPresenceWaits() {
        ItemTask task = new ItemTask(TaskCommon.DEFAULT,
                new ItemRef(Identifier.withDefaultNamespace("oak_log"), 8),
                Optional.of(false), ComponentMatch.STRICT, false, true);
        TaskBehaviour<QuestTask> through = behaviour(task);

        assertTrue(through.canSubmitByHand(task, false),
                "the press is the only path that measures a manual task, so the button is always there");
        assertFalse(through.takesResources(task, false));
        assertTrue(through.waitsForSubmit(task, false),
                "and the tick records nothing: a presence task that waited for nothing would complete by itself");

        ItemTagTask tag = new ItemTagTask(TaskCommon.DEFAULT,
                Identifier.withDefaultNamespace("logs"), 8, Optional.empty(), false, true);
        assertTrue(behaviour(tag).canSubmitByHand(tag, false));
        assertTrue(behaviour(tag).waitsForSubmit(tag, false));
    }
}
