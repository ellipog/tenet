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
 * The biome task: be somewhere green, or somewhere of a tag.
 *
 * <p>The id and the tag share one field in the legacy {@code #} spelling, which is why
 * {@link RegistryRef} exists rather than a second field. Matching a holder needs a level to
 * stand in, so the playthrough covers it; what is pinned here is the other half — the field's
 * two spellings — beside the behaviour, the row, and the codec.
 */
@DisplayName("the biome task")
class BiomeTaskTest {

    @BeforeAll
    static void bootVanilla() {
        MinecraftTestBootstrap.boot();
    }

    private static BiomeTask task(String biome) {
        return new BiomeTask(new TaskCommon(false, 20), RegistryRef.parse(biome));
    }

    private static QuestTask decoded(String text) {
        return TaskTypes.dispatchCodec().parse(JsonOps.INSTANCE, JsonParser.parseString(text))
                .result().orElseThrow(() -> new AssertionError(text + " did not decode"));
    }

    @Test
    @DisplayName("one place to be, and no button to press about it")
    void behaviour() {
        BiomeTask task = task("minecraft:plains");

        assertEquals(1, BiomeTask.BEHAVIOUR.required(task));
        assertFalse(BiomeTask.BEHAVIOUR.canSubmitByHand(task, false),
                "being there is the whole task, and it completes on its own");

        // current looks up the biome at a player's feet, and matches answers about a holder from
        // a registry: both need a live server, so the playthrough covers them.
    }

    @Test
    @DisplayName("the field holds an id or a tag, and the spelling survives the trip")
    void referenceShape() {
        RegistryRef id = RegistryRef.parse("minecraft:plains");
        assertFalse(id.tag());
        assertEquals("minecraft:plains", id.id().toString());
        assertEquals("minecraft:plains", id.wire());

        RegistryRef tag = RegistryRef.parse("#minecraft:is_forest");
        assertTrue(tag.tag());
        assertEquals("minecraft:is_forest", tag.id().toString());
        assertEquals("#minecraft:is_forest", tag.wire(),
                "the # is the file's own spelling, and the value writes back as it was read");
    }

    @Test
    @DisplayName("the row names the biome, tag mark included")
    void display() {
        var id = TaskTypes.displayOf(task("minecraft:plains"));
        assertEquals("tenet.task.biome", id.label());
        assertEquals("minecraft:plains", id.labelArg());
        assertEquals("Find minecraft:plains", id.labelFallback());
        assertEquals(1, id.count());

        var tag = TaskTypes.displayOf(task("#minecraft:is_forest"));
        assertEquals("#minecraft:is_forest", tag.labelArg(),
                "a tag is the subject, mark and all");
    }

    @Test
    @DisplayName("a biome task decodes to its reference, id or tag")
    void codec() {
        var id = assertInstanceOf(BiomeTask.class,
                decoded("{\"type\": \"tenet:biome\", \"biome\": \"minecraft:plains\"}"));
        assertEquals("minecraft:plains", id.biome().wire());

        var tag = assertInstanceOf(BiomeTask.class,
                decoded("{\"type\": \"tenet:biome\", \"biome\": \"#minecraft:is_forest\"}"));
        assertEquals("#minecraft:is_forest", tag.biome().wire());
        assertTrue(tag.biome().tag());
    }
}
