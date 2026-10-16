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
 * The stage condition's file shape and sentence.
 *
 * <p>The evaluation reads the progress store off the server, so there is no behaviour invocation
 * here — a bare context has no server to read. The playthrough covers the gate; what is pinned here
 * is that the type is registered with a behaviour at all, and that the stage id survives the file.
 * There is deliberately no "unknown stage fails" case: a stage exists by being granted, so every id
 * is valid and there is nothing to validate against.
 */
@DisplayName("the stage condition")
class StageConditionTest {

    @BeforeAll
    static void bootVanilla() {
        MinecraftTestBootstrap.boot();
    }

    @Test
    @DisplayName("the type is registered with a behaviour, so the gate can open")
    void behaviourRegistered() {
        var condition = new StageCondition(Identifier.parse("my_pack:inducted"));

        assertTrue(ConditionTypes.behaviourOf(condition).isPresent(),
                "a registered type with no behaviour is a gate that never opens");
    }

    @Test
    @DisplayName("the row names the stage the player has to have")
    void display() {
        var condition = new StageCondition(Identifier.parse("my_pack:inducted"));

        assertEquals("tenet.condition.stage", StageCondition.DISPLAY.apply(condition).label());
        assertEquals("Have the stage my_pack:inducted",
                StageCondition.DISPLAY.apply(condition).labelFallback());
        assertEquals("my_pack:inducted", StageCondition.DISPLAY.apply(condition).labelArg());
    }

    @Test
    @DisplayName("a stage condition decodes with its stage")
    void codec() {
        var parsed = assertInstanceOf(StageCondition.class, ConditionTypes.dispatchCodec()
                .parse(JsonOps.INSTANCE, JsonParser.parseString(
                        "{\"type\": \"tenet:stage\", \"stage\": \"my_pack:inducted\"}"))
                .result().orElseThrow(() -> new AssertionError("did not decode")));
        assertEquals(Identifier.parse("my_pack:inducted"), parsed.stage());
        assertEquals(Identifier.parse("tenet:stage"), parsed.type());
    }
}
