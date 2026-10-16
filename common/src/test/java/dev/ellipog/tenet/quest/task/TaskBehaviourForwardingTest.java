package dev.ellipog.tenet.quest.task;

import dev.ellipog.tenet.quest.ItemRef;
import dev.ellipog.tenet.quest.MinecraftTestBootstrap;
import dev.ellipog.tenet.quest.QuestTask;
import dev.ellipog.tenet.quest.TaskCommon;

import net.minecraft.resources.Identifier;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The registry wrapper around every task behaviour forwards <b>all</b> of {@link TaskBehaviour}.
 *
 * <h2>What this is guarding against, and it happened</h2>
 *
 * <p>{@code TaskTypes.widenBehaviour} existed to erase a type parameter; its first version forwarded
 * {@code required}, {@code current} and {@code canSubmitByHand} and left the interface's newer methods
 * to their defaults. So an item task saying {@code consumeItems: true} answered
 * {@code takesResources == false} at every engine call site, and consuming tasks silently took
 * nothing. No test failed, because the worked examples never consume.
 *
 * <p>A wrapper that forwards "most" methods is the shape of that bug, so this pins the ones a type
 * can override and the engine actually calls — through {@code behaviourOf}, which is the only door
 * the engine uses.
 */
@DisplayName("the task registry's behaviour wrapper")
class TaskBehaviourForwardingTest {

    @BeforeAll
    static void bootVanilla() {
        // The break case below reads real block states; everything else here never leaves records.
        MinecraftTestBootstrap.boot();
    }

    private static ItemTask itemTask(Optional<Boolean> consume) {
        return new ItemTask(TaskCommon.DEFAULT,
                new ItemRef(Identifier.withDefaultNamespace("oak_log"), 4),
                consume, ComponentMatch.STRICT, false, false);
    }

    @Test
    @DisplayName("a consuming item task still consumes through the registry")
    void consumingSurvivesTheWrapper() {
        ItemTask task = itemTask(Optional.of(true));
        TaskBehaviour<QuestTask> through = TaskTypes.behaviourOf(task).orElseThrow();

        assertTrue(through.takesResources(task, false),
                "the wrapper dropped takesResources, which is what silently stopped item consumption");
        assertEquals(4, through.required(task));
        assertTrue(through.acceptsClientSubmit(task, false),
                "a consuming task is submit-by-hand, and the client submit gate reads this");
        assertTrue(through.waitsForSubmit(task, false),
                "and it waits for the press, which is the engine's half of the same answer");
    }

    @Test
    @DisplayName("a presence-only item task still takes nothing")
    void presenceStillTakesNothing() {
        ItemTask task = itemTask(Optional.of(false));
        TaskBehaviour<QuestTask> through = TaskTypes.behaviourOf(task).orElseThrow();

        assertFalse(through.takesResources(task, true));
        assertFalse(through.acceptsClientSubmit(task, true),
                "a presence-only task has no button, so its client submit is refused too");
        assertFalse(through.waitsForSubmit(task, true),
                "and the tick registers it: declining the chapter's default is the whole point of the field");
    }

    @Test
    @DisplayName("manual-only survives the wrapper, for every type that declares it")
    void manualOnlySurvivesTheWrapper() {
        // The engine's tick-skip reads this through behaviourOf — the same door the take, the
        // submit gate and the inventory floor go through — so a wrapper that dropped it would
        // measure a manual task on the tick while the submit path refused to record it.
        ItemTask manualItem = new ItemTask(TaskCommon.DEFAULT,
                new ItemRef(Identifier.withDefaultNamespace("oak_log"), 4),
                Optional.empty(), ComponentMatch.STRICT, false, true);
        assertTrue(TaskTypes.behaviourOf(manualItem).orElseThrow().manualOnly(manualItem));
        assertFalse(TaskTypes.behaviourOf(itemTask(Optional.empty())).orElseThrow()
                .manualOnly(itemTask(Optional.empty())));

        ItemTagTask manualTag = new ItemTagTask(TaskCommon.DEFAULT,
                Identifier.withDefaultNamespace("logs"), 1, Optional.empty(), false, true);
        assertTrue(TaskTypes.behaviourOf(manualTag).orElseThrow().manualOnly(manualTag));

        FluidTask manualFluid = new FluidTask(TaskCommon.DEFAULT,
                Identifier.withDefaultNamespace("water"), 1000, true);
        assertTrue(TaskTypes.behaviourOf(manualFluid).orElseThrow().manualOnly(manualFluid));

        EnergyTask manualEnergy = new EnergyTask(TaskCommon.DEFAULT, 1000, 0, true);
        assertTrue(TaskTypes.behaviourOf(manualEnergy).orElseThrow().manualOnly(manualEnergy));
    }

    @Test
    @DisplayName("a break is heard through the registry, like a death")
    void blockBrokenSurvivesTheWrapper() {
        // The engine's break path reads this through behaviourOf — the same door the take, the
        // submit gate and the inventory floor go through — so a wrapper that dropped it would
        // record nothing while the event fired. The item-consumption incident is why this is
        // pinned rather than trusted: the wrapper forwarded "most" methods once already.
        BreakTask task = new BreakTask(TaskCommon.DEFAULT,
                Optional.of(Identifier.withDefaultNamespace("stone")),
                Optional.empty(), Optional.empty(), 10, false);
        TaskBehaviour<QuestTask> through = TaskTypes.behaviourOf(task).orElseThrow();

        assertEquals(1, through.onBlockBroken(task, null,
                        net.minecraft.world.level.block.Blocks.STONE.defaultBlockState()),
                "the wrapper dropped onBlockBroken, and breaks would silently count nothing");
        assertEquals(0, through.onBlockBroken(task, null,
                net.minecraft.world.level.block.Blocks.DIRT.defaultBlockState()));
        assertEquals(10, through.required(task));
    }

    @Test
    @DisplayName("only the inventory-backed types read the inventory, through the registry")
    void readsInventorySurvivesTheWrapper() {
        // The pack's detection_delay floors exactly these types' re-evaluation. A type missing
        // here keeps its own cadence whatever the pack says; a type wrongly present here is
        // throttled for no reason. Pinned through behaviourOf, the engine's only door.
        assertTrue(TaskTypes.behaviourOf(itemTask(Optional.empty())).orElseThrow().readsInventory(),
                "item tasks count carried items");
        assertTrue(TaskTypes.behaviourOf(new ItemTagTask(TaskCommon.DEFAULT,
                        Identifier.withDefaultNamespace("logs"), 1, Optional.empty(), false, false))
                        .orElseThrow().readsInventory(),
                "item-tag tasks count carried items");
        assertTrue(TaskTypes.behaviourOf(new FluidTask(TaskCommon.DEFAULT,
                        Identifier.withDefaultNamespace("water"), 1000, false))
                        .orElseThrow().readsInventory(),
                "fluid tasks count carried buckets");
        assertTrue(TaskTypes.behaviourOf(new EnergyTask(TaskCommon.DEFAULT, 1000, 0, false))
                        .orElseThrow().readsInventory(),
                "energy tasks count carried charge");        assertFalse(TaskTypes.behaviourOf(new StatTask(TaskCommon.DEFAULT,
                        Identifier.withDefaultNamespace("walk_one_cm"), 100))
                        .orElseThrow().readsInventory(),
                "a stat lookup is not an inventory walk");
        assertFalse(TaskTypes.behaviourOf(new XpTask(TaskCommon.DEFAULT, 30, false))
                        .orElseThrow().readsInventory(),
                "nor is an XP level read");
    }
}
