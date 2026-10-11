package dev.ellipog.tenet.quest.task;

import dev.ellipog.armature.api.energy.EnergyAccess;
import dev.ellipog.armature.api.energy.EnergyAccesses;
import dev.ellipog.tenet.quest.QuestTask;
import dev.ellipog.tenet.quest.TaskCommon;
import dev.ellipog.tenet.quest.TaskContext;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The energy task: what it asks for, and what it forwards to the loader.
 *
 * <p>T20 of the migration work: the legacy {@code forge_energy} counted from a task screen the
 * pack pipes energy into; Tenet counts carried items' storage through {@link EnergyAccess}. The
 * per-item cap ({@code maxInput}) is applied by the loader, so what this pins is that the task
 * forwards it — and its requirement — faithfully, and that the row names its subject.
 */
@DisplayName("the energy task")
class EnergyTaskTest {

    /** A seam that answers fixed stores and records what it was asked. */
    private static final class Fake implements EnergyAccess {
        final int stored;
        int seenMax;
        int seenAmount;

        Fake(int stored) {
            this.stored = stored;
        }

        @Override
        public int storedOf(net.minecraft.server.level.ServerPlayer player, int maxPerItem) {
            seenMax = maxPerItem;
            return stored;
        }

        @Override
        public int drainFrom(net.minecraft.server.level.ServerPlayer player, int amount, int maxPerItem) {
            seenMax = maxPerItem;
            seenAmount = amount;
            return Math.min(amount, stored);
        }
    }

    private static EnergyTask task(int value, int maxInput) {
        return new EnergyTask(TaskCommon.DEFAULT, value, maxInput, false);
    }

    private static TaskBehaviour<QuestTask> behaviourOf(EnergyTask task) {
        return TaskTypes.behaviourOf(task)
                .orElseThrow(() -> new AssertionError("tenet:energy is not a registered type"));
    }

    private static TaskContext context() {
        return new TaskContext(null, null, 0L);
    }

    @Test
    @DisplayName("the count is capped at the requirement, and the cap travels with the read")
    void measurementCapsAndForwards() {
        Fake access = new Fake(5000);
        EnergyAccesses.install(access);
        try {
            EnergyTask task = task(2000, 500);
            assertEquals(2000, behaviourOf(task).current(task, context()),
                    "five thousand stored against two thousand asked reads as done, not as five");
            assertEquals(500, access.seenMax, "the per-item cap reaches the loader");

            EnergyTask unlimited = task(2000, 0);
            assertEquals(2000, behaviourOf(unlimited).current(unlimited, context()));
            assertEquals(0, access.seenMax, "zero travels as zero: unlimited is the loader's reading");
        }
        finally {
            EnergyAccesses.reset();
        }
    }

    @Test
    @DisplayName("energy always waits for the press and always takes")
    void energyWaitsAndTakes() {
        EnergyTask task = task(2000, 0);
        TaskBehaviour<QuestTask> through = behaviourOf(task);
        assertTrue(through.canSubmitByHand(task, false), "the press is the consent to drain");
        assertTrue(through.takesResources(task, false), "handing energy over is the task");
        assertTrue(through.waitsForSubmit(task, false), "so the tick records nothing");
    }

    @Test
    @DisplayName("the take asks for the requirement under the same cap")
    void takeAsksUnderTheCap() {
        Fake access = new Fake(5000);
        EnergyAccesses.install(access);
        try {
            EnergyTask task = task(2000, 500);
            assertEquals(2000, behaviourOf(task).take(task, null, 2000));
            assertEquals(2000, access.seenAmount);
            assertEquals(500, access.seenMax);
        }
        finally {
            EnergyAccesses.reset();
        }
    }

    @Test
    @DisplayName("an energy row names its value in Forge Energy units")
    void aRowNamesItsValue() {
        TaskDisplay display = TaskTypes.displayOf(task(1000000, 0));
        assertEquals("tenet.task.energy", display.label());
        assertEquals("1000000 FE", display.labelArg());
    }
}
