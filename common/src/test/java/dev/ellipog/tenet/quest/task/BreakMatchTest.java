package dev.ellipog.tenet.quest.task;

import com.google.gson.JsonParser;
import com.mojang.serialization.JsonOps;

import dev.ellipog.tenet.quest.MinecraftTestBootstrap;
import dev.ellipog.tenet.quest.QuestTask;
import dev.ellipog.tenet.quest.TaskCommon;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.CropBlock;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Break-task matching, behaviour and codec, against real blocks.
 *
 * <p>Matching is pure — "does this broken state answer that filter" — so it is answered here
 * against the real registry. Tag positives need a datapack to bind the tag (see
 * {@code ObservationMatchTest}'s note on JVM ordering), so the positive tag case is exercised
 * by the playthrough, where a server loads one; here a tag no datapack declares is the
 * deterministic negative.
 */
@DisplayName("break matching")
class BreakMatchTest {

    @BeforeAll
    static void bootVanilla() {
        MinecraftTestBootstrap.boot();
    }

    private static BreakTask breaking(Optional<ResourceLocation> block, Optional<ResourceLocation> tag,
                                      Optional<String> state, int value) {
        return new BreakTask(new TaskCommon(false, 20), block, tag, state, value);
    }

    private static BreakTask breaking(String block, int value) {
        return breaking(Optional.of(ResourceLocation.parse(block)), Optional.empty(), Optional.empty(),
                value);
    }

    private static QuestTask decoded(String text) {
        return TaskTypes.dispatchCodec().parse(JsonOps.INSTANCE, JsonParser.parseString(text))
                .result().orElseThrow(() -> new AssertionError(text + " did not decode"));
    }

    @Test
    @DisplayName("a block is matched by its own id, and only its own")
    void blockById() {
        var task = breaking("minecraft:stone", 10);

        assertTrue(task.matches(Blocks.STONE.defaultBlockState()));
        assertFalse(task.matches(Blocks.DIRT.defaultBlockState()), "another block is not this one");
    }

    @Test
    @DisplayName("with nothing named, any broken block counts")
    void emptyMatchesAnything() {
        var task = breaking(Optional.empty(), Optional.empty(), Optional.empty(), 1);

        assertTrue(task.matches(Blocks.STONE.defaultBlockState()));
        assertTrue(task.matches(Blocks.OAK_LOG.defaultBlockState()));
    }

    @Test
    @DisplayName("a tag nothing declares matches nothing, and the tag wins over a matching id")
    void tagPrecedence() {
        var stone = Blocks.STONE.defaultBlockState();

        assertFalse(breaking(Optional.empty(), Optional.of(ResourceLocation.parse("minecraft:logs")),
                        Optional.empty(), 1).matches(stone),
                "stone is not a log");
        assertFalse(breaking(Optional.empty(),
                        Optional.of(ResourceLocation.parse("minecraft:tag_no_datapack_declares")),
                        Optional.empty(), 1).matches(Blocks.OAK_LOG.defaultBlockState()),
                "a tag nothing declares matches nothing, whatever the datapack situation");
        // The precedence half: the id alone would match, but the tag is asked first and answers
        // no — so the task counts nothing. That is the kill rule ("the tag wins when both are
        // named"), and it holds in a bare JVM precisely because the tag answers false there.
        assertFalse(breaking(Optional.of(ResourceLocation.parse("minecraft:stone")),
                        Optional.of(ResourceLocation.parse("minecraft:tag_no_datapack_declares")),
                        Optional.empty(), 1).matches(stone),
                "a tag takes precedence over the id, even when the id would match");
    }

    @Test
    @DisplayName("a state predicate narrows to the properties it names")
    void stateNarrows() {
        var ripe = Blocks.WHEAT.defaultBlockState().setValue(CropBlock.AGE, 7);
        var green = Blocks.WHEAT.defaultBlockState().setValue(CropBlock.AGE, 0);

        var wheat = breaking("minecraft:wheat", 16);
        assertTrue(wheat.matches(ripe), "ripe wheat is wheat");
        assertTrue(wheat.matches(green), "and so is green wheat, without a predicate");

        var ripeOnly = breaking(Optional.of(ResourceLocation.parse("minecraft:wheat")),
                Optional.empty(), Optional.of("minecraft:wheat[age=7]"), 16);
        assertTrue(ripeOnly.matches(ripe));
        assertFalse(ripeOnly.matches(green), "the property is part of the filter");
        assertFalse(ripeOnly.matches(Blocks.OAK_LOG.defaultBlockState()),
                "the id still has to match beside the predicate");

        // A lone predicate implies its own block: there is nothing else to match against.
        var lone = breaking(Optional.empty(), Optional.empty(),
                Optional.of("minecraft:wheat[age=7]"), 16);
        assertTrue(lone.matches(ripe));
        assertFalse(lone.matches(green));
        assertFalse(lone.matches(Blocks.STONE.defaultBlockState()));
    }

    @Test
    @DisplayName("an unparsable state predicate matches nothing")
    void unparsableStateMatchesNothing() {
        var task = breaking(Optional.of(ResourceLocation.parse("minecraft:wheat")),
                Optional.empty(), Optional.of("minecraft:wheat[age=sideways]"), 16);

        // Fail-closed like the kill task's NBT filter; the validator refuses this at the
        // file's line, and this is the honest runtime backstop.
        assertFalse(task.matches(Blocks.WHEAT.defaultBlockState().setValue(CropBlock.AGE, 7)));
    }

    @Test
    @DisplayName("the behaviour counts one matching break, and nothing else")
    void behaviour() {
        var task = breaking("minecraft:stone", 10);

        assertEquals(10, BreakTask.BEHAVIOUR.required(task));
        assertEquals(0, BreakTask.BEHAVIOUR.current(task, null), "event-sourced: the count is recorded");
        assertFalse(BreakTask.BEHAVIOUR.canSubmitByHand(task, false), "no button: breaking is the work");
        assertEquals(1, BreakTask.BEHAVIOUR.onBlockBroken(task, null, Blocks.STONE.defaultBlockState()));
        assertEquals(0, BreakTask.BEHAVIOUR.onBlockBroken(task, null, Blocks.DIRT.defaultBlockState()));
    }

    @Test
    @DisplayName("the row names its target the way the kill row names its mob")
    void target() {
        assertEquals("minecraft:stone", breaking("minecraft:stone", 10).target());
        assertEquals("10 x minecraft:stone", breaking("minecraft:stone", 10).description());
        assertEquals("#minecraft:logs", breaking(Optional.empty(),
                Optional.of(ResourceLocation.parse("minecraft:logs")), Optional.empty(), 32).target());
        assertEquals("anything",
                breaking(Optional.empty(), Optional.empty(), Optional.empty(), 1).target());
    }

    @Test
    @DisplayName("a break task decodes with its filters and its count, defaulting to one")
    void codec() {
        var parsed = assertInstanceOf(BreakTask.class, decoded(
                "{\"type\": \"tenet:break\", \"block\": \"minecraft:stone\","
                        + " \"blockTag\": \"minecraft:logs\", \"blockState\": \"minecraft:wheat[age=7]\","
                        + " \"value\": 32}"));
        assertEquals(Optional.of("minecraft:stone"), parsed.block().map(Object::toString));
        assertEquals(Optional.of("minecraft:logs"), parsed.blockTag().map(Object::toString));
        assertEquals(Optional.of("minecraft:wheat[age=7]"), parsed.blockState());
        assertEquals(32, parsed.value());

        var plain = assertInstanceOf(BreakTask.class,
                decoded("{\"type\": \"tenet:break\", \"block\": \"minecraft:stone\"}"));
        assertEquals(1, plain.value(), "an absent count means one break");
        assertEquals(Optional.empty(), plain.blockTag());
        assertEquals(Optional.empty(), plain.blockState());
    }
}
