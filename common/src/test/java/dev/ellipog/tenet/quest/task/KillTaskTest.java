package dev.ellipog.tenet.quest.task;

import com.google.gson.JsonParser;
import com.mojang.serialization.JsonOps;

import dev.ellipog.tenet.quest.MinecraftTestBootstrap;
import dev.ellipog.tenet.quest.QuestTask;
import dev.ellipog.tenet.quest.TaskCommon;

import net.minecraft.resources.ResourceLocation;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The kill task: the break task's sibling, told rather than asked.
 *
 * <p>A lifetime stat can say how many zombies a player has ever killed, but not whether the one
 * that just died was called "Bob" — so this type hangs off the death event and the engine
 * records what the event reports. Current is therefore always zero: the count lives in recorded
 * progress. A death needs an entity, and an entity needs a level to hold it (see
 * {@code ObservationMatchTest}'s note), so the playthrough covers matching and the event; this
 * pins the behaviour's answers, the target's two spellings, the row, and the codec.
 */
@DisplayName("the kill task")
class KillTaskTest {

    @BeforeAll
    static void bootVanilla() {
        MinecraftTestBootstrap.boot();
    }

    private static KillTask byId(String entity, int value) {
        return new KillTask(new TaskCommon(false, 20),
                Optional.of(ResourceLocation.parse(entity)), Optional.empty(), value,
                Optional.empty(), Optional.empty());
    }

    private static QuestTask decoded(String text) {
        return TaskTypes.dispatchCodec().parse(JsonOps.INSTANCE, JsonParser.parseString(text))
                .result().orElseThrow(() -> new AssertionError(text + " did not decode"));
    }

    @Test
    @DisplayName("the requirement is the value, and the count lives in recorded progress")
    void behaviour() {
        KillTask task = byId("minecraft:zombie", 10);

        assertEquals(10, KillTask.BEHAVIOUR.required(task));
        assertEquals(0, KillTask.BEHAVIOUR.current(task, null),
                "event-sourced: the count is recorded");
        assertFalse(KillTask.BEHAVIOUR.canSubmitByHand(task, false),
                "no button: killing is the work");

        // matches and onEntityDeath answer about a death, which needs an entity in a level, so
        // they need a live server; the playthrough covers them.
    }

    @Test
    @DisplayName("the target is the entity, its tag, or the word for any")
    void target() {
        assertEquals("minecraft:zombie", byId("minecraft:zombie", 10).target());
        assertEquals("10 x minecraft:zombie", byId("minecraft:zombie", 10).description());

        var tagged = new KillTask(new TaskCommon(false, 20), Optional.empty(),
                Optional.of(ResourceLocation.parse("minecraft:skeletons")), 5,
                Optional.empty(), Optional.empty());
        assertEquals("#minecraft:skeletons", tagged.target());

        var anything = new KillTask(new TaskCommon(false, 20), Optional.empty(),
                Optional.empty(), 3, Optional.empty(), Optional.empty());
        assertEquals("anything", anything.target());
    }

    @Test
    @DisplayName("the row names its mob, wearing its egg when one mob is named")
    void display() {
        var id = TaskTypes.displayOf(byId("minecraft:zombie", 10));
        assertEquals("tenet.task.kill", id.label());
        assertEquals("minecraft:zombie", id.labelArg());
        assertEquals("Kill minecraft:zombie", id.labelFallback());
        assertEquals(10, id.count());
        assertEquals("minecraft:zombie_spawn_egg",
                id.picture().orElseThrow().item().toString());

        var tagged = TaskTypes.displayOf(new KillTask(new TaskCommon(false, 20),
                Optional.empty(), Optional.of(ResourceLocation.parse("minecraft:skeletons")), 5,
                Optional.empty(), Optional.empty()));
        assertTrue(tagged.picture().isEmpty(),
                "a tag takes precedence in matching, so no egg may name the wrong mob");
    }

    @Test
    @DisplayName("a kill task decodes with its filters and its count, defaulting to one")
    void codec() {
        var parsed = assertInstanceOf(KillTask.class, decoded(
                "{\"type\": \"tenet:kill\", \"entity\": \"minecraft:zombie\","
                        + " \"entityTypeTag\": \"minecraft:skeletons\","
                        + " \"value\": 10, \"customName\": \"Bob\","
                        + " \"nbtFilter\": \"{Health:20.0f}\"}"));
        assertEquals(Optional.of("minecraft:zombie"), parsed.entity().map(Object::toString));
        assertEquals(Optional.of("minecraft:skeletons"),
                parsed.entityTypeTag().map(Object::toString));
        assertEquals(10, parsed.value());
        assertEquals(Optional.of("Bob"), parsed.customName());
        assertEquals(Optional.of("{Health:20.0f}"), parsed.nbtFilter());

        var plain = assertInstanceOf(KillTask.class, decoded(
                "{\"type\": \"tenet:kill\", \"entity\": \"minecraft:zombie\"}"));
        assertEquals(1, plain.value(), "an absent count means one kill");
        assertEquals(Optional.empty(), plain.entityTypeTag());
        assertEquals(Optional.empty(), plain.customName());
        assertEquals(Optional.empty(), plain.nbtFilter());
    }
}
