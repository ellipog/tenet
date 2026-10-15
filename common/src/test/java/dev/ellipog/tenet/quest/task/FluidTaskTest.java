package dev.ellipog.tenet.quest.task;

import com.google.gson.JsonParser;
import com.mojang.serialization.JsonOps;

import dev.ellipog.tenet.quest.MinecraftTestBootstrap;
import dev.ellipog.tenet.quest.QuestTask;
import dev.ellipog.tenet.quest.TaskCommon;

import net.minecraft.resources.Identifier;
import net.minecraft.world.item.Items;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The fluid task: hand over millibuckets, carried in buckets and containers.
 *
 * <p>Buckets count a thousand each, and whatever the buckets do not cover counts through the
 * loader's fluid access — which is also what a submit drains. The press this answers measured
 * both in the same order, so what it counted is what it drains. The vessels belong to a live
 * player, so the playthrough covers the counting and the taking; this pins the answers, the
 * bucket each fluid rides in, the row, and the codec.
 */
@DisplayName("the fluid task")
class FluidTaskTest {

    @BeforeAll
    static void bootVanilla() {
        MinecraftTestBootstrap.boot();
    }

    private static FluidTask task(boolean manualOnly) {
        return new FluidTask(new TaskCommon(false, 20),
                Identifier.parse("minecraft:water"), 1000, manualOnly);
    }

    private static QuestTask decoded(String text) {
        return TaskTypes.dispatchCodec().parse(JsonOps.INSTANCE, JsonParser.parseString(text))
                .result().orElseThrow(() -> new AssertionError(text + " did not decode"));
    }

    @Test
    @DisplayName("the requirement is the amount, and the press always takes it")
    void behaviour() {
        FluidTask task = task(false);

        assertEquals(1000, FluidTask.BEHAVIOUR.required(task));
        assertTrue(FluidTask.BEHAVIOUR.canSubmitByHand(task, false),
                "the press is the consent to be drained");
        assertTrue(FluidTask.BEHAVIOUR.takesResources(task, false),
                "the fluid is what is being asked for, so handing it over is the task");
        assertFalse(FluidTask.BEHAVIOUR.manualOnly(task));
        assertTrue(FluidTask.BEHAVIOUR.manualOnly(task(true)));

        // current counts carried buckets and containers and take drains them: both need a live
        // player, so the playthrough covers them.
    }

    @Test
    @DisplayName("every fluid rides in its own bucket")
    void bucketFor() {
        assertEquals(Items.WATER_BUCKET,
                FluidTask.bucketFor(Identifier.parse("minecraft:water")));
        assertEquals(Items.LAVA_BUCKET,
                FluidTask.bucketFor(Identifier.parse("minecraft:lava")));
    }

    @Test
    @DisplayName("the row says the amount and the fluid in one phrase")
    void display() {
        var display = TaskTypes.displayOf(task(false));

        assertEquals("tenet.task.fluid", display.label());
        assertEquals("1000 mB of minecraft:water", display.labelArg());
        assertEquals("Hand in 1000 mB of minecraft:water", display.labelFallback());
        assertEquals(1000, display.count(),
                "millibuckets, like the progress the row is drawn against");
    }

    @Test
    @DisplayName("a fluid task decodes to its fluid and its amount")
    void codec() {
        var parsed = assertInstanceOf(FluidTask.class, decoded(
                "{\"type\": \"tenet:fluid\", \"fluid\": \"minecraft:water\","
                        + " \"amount\": 1000, \"manualOnly\": true}"));
        assertEquals("minecraft:water", parsed.fluid().toString());
        assertEquals(1000, parsed.amount());
        assertTrue(parsed.manualOnly());

        var plain = assertInstanceOf(FluidTask.class, decoded(
                "{\"type\": \"tenet:fluid\", \"fluid\": \"minecraft:lava\","
                        + " \"amount\": 2000}"));
        assertEquals("minecraft:lava", plain.fluid().toString());
        assertFalse(plain.manualOnly());
    }
}
