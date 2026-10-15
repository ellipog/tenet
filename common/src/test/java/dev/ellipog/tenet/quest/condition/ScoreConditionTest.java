package dev.ellipog.tenet.quest.condition;

import com.google.gson.JsonParser;
import com.mojang.serialization.JsonOps;

import dev.ellipog.tenet.quest.MinecraftTestBootstrap;

import net.minecraft.resources.Identifier;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The score condition's file shape and sentence.
 *
 * <p>The evaluation itself needs a live server — it reads the world's scoreboard for an objective
 * only a running game has — so there is no behaviour invocation here. A bare context would NPE on
 * the server lookup rather than answer, which proves nothing. The playthrough covers the gate; what
 * is pinned here is that the type is registered with a behaviour at all (a type without one reads
 * as never met, silently), and that the file means what the behaviour will read.
 */
@DisplayName("the score condition")
class ScoreConditionTest {

    @BeforeAll
    static void bootVanilla() {
        MinecraftTestBootstrap.boot();
    }

    @Test
    @DisplayName("the type is registered with a behaviour, so the gate can open")
    void behaviourRegistered() {
        var condition = new ScoreCondition("standing", 10);

        assertTrue(ConditionTypes.behaviourOf(condition).isPresent(),
                "a registered type with no behaviour is a gate that never opens");
    }

    @Test
    @DisplayName("the row names the minimum and the objective it is read from")
    void display() {
        var condition = new ScoreCondition("standing", 10);

        assertEquals("tenet.condition.score", ScoreCondition.DISPLAY.apply(condition).label());
        assertEquals("Have 10 in standing", ScoreCondition.DISPLAY.apply(condition).labelFallback());
        assertEquals("10 in standing", ScoreCondition.DISPLAY.apply(condition).labelArg());
    }

    @Test
    @DisplayName("a score condition decodes with its minimum, which has no default to fall back on")
    void codec() {
        var parsed = assertInstanceOf(ScoreCondition.class, ConditionTypes.dispatchCodec()
                .parse(JsonOps.INSTANCE, JsonParser.parseString(
                        "{\"type\": \"tenet:score\", \"objective\": \"standing\", \"min\": 10}"))
                .result().orElseThrow(() -> new AssertionError("did not decode")));
        assertEquals("standing", parsed.objective());
        assertEquals(10, parsed.min());
        assertEquals(Identifier.parse("tenet:score"), parsed.type());

        assertTrue(ConditionTypes.dispatchCodec().parse(JsonOps.INSTANCE,
                JsonParser.parseString("{\"type\": \"tenet:score\", \"objective\": \"standing\"}"))
                .result().isEmpty(), "without a minimum there is nothing to reach");
        assertTrue(ConditionTypes.dispatchCodec().parse(JsonOps.INSTANCE, JsonParser.parseString(
                "{\"type\": \"tenet:score\", \"objective\": \"standing\", \"min\": 0}"))
                .result().isEmpty(), "a minimum of zero is a gate nothing can fail, so the file refuses it");
    }
}
