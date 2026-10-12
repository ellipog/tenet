package dev.ellipog.tenet.quest.task;

import com.google.gson.JsonParser;
import com.mojang.serialization.JsonOps;

import dev.ellipog.tenet.quest.MinecraftTestBootstrap;
import dev.ellipog.tenet.quest.QuestTask;
import dev.ellipog.tenet.quest.RegistryRef;
import dev.ellipog.tenet.quest.TaskCommon;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The structure task: be inside somewhere built.
 *
 * <p>Asked every twenty ticks like the biome task, and answered by walking the structures that
 * contain the player's position — which keeps one code path for the id and the tag form rather
 * than two lookups that could disagree. The walk needs a server level, so the playthrough
 * covers it; this pins the behaviour's answers, the row, and the codec.
 */
@DisplayName("the structure task")
class StructureTaskTest {

    @BeforeAll
    static void bootVanilla() {
        MinecraftTestBootstrap.boot();
    }

    private static StructureTask task(String structure) {
        return new StructureTask(new TaskCommon(false, 20), RegistryRef.parse(structure));
    }

    private static QuestTask decoded(String text) {
        return TaskTypes.dispatchCodec().parse(JsonOps.INSTANCE, JsonParser.parseString(text))
                .result().orElseThrow(() -> new AssertionError(text + " did not decode"));
    }

    @Test
    @DisplayName("one place to be, and no button to press about it")
    void behaviour() {
        StructureTask task = task("minecraft:village_plains");

        assertEquals(1, StructureTask.BEHAVIOUR.required(task));
        assertFalse(StructureTask.BEHAVIOUR.canSubmitByHand(task, false),
                "being inside is the whole task, and it completes on its own");

        // current walks the structures containing a player's position in a server level, so it
        // needs a live server; the playthrough covers it.
    }

    @Test
    @DisplayName("the row names the structure, tag mark included")
    void display() {
        var id = TaskTypes.displayOf(task("minecraft:village_plains"));
        assertEquals("tenet.task.structure", id.label());
        assertEquals("minecraft:village_plains", id.labelArg());
        assertEquals("Find minecraft:village_plains", id.labelFallback());
        assertEquals(1, id.count());

        var tag = TaskTypes.displayOf(task("#minecraft:village"));
        assertEquals("#minecraft:village", tag.labelArg());
    }

    @Test
    @DisplayName("a structure task decodes to its reference, id or tag")
    void codec() {
        var id = assertInstanceOf(StructureTask.class, decoded(
                "{\"type\": \"tenet:structure\", \"structure\": \"minecraft:village_plains\"}"));
        assertEquals("minecraft:village_plains", id.structure().wire());

        var tag = assertInstanceOf(StructureTask.class, decoded(
                "{\"type\": \"tenet:structure\", \"structure\": \"#minecraft:village\"}"));
        assertEquals("#minecraft:village", tag.structure().wire());
        assertTrue(tag.structure().tag());
    }
}
