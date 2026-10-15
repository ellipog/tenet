package dev.ellipog.tenet.quest.condition;

import com.google.gson.JsonParser;
import com.mojang.serialization.JsonOps;

import dev.ellipog.tenet.quest.MinecraftTestBootstrap;

import net.minecraft.resources.Identifier;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The advancement condition's file shape and sentence.
 *
 * <p>The evaluation needs a live server twice over — the advancement has to exist in the world's
 * registry, and the player's progress on it lives on the player — so there is no behaviour
 * invocation here. The playthrough covers the gate; what is pinned here is that the type is
 * registered with a behaviour at all, and that one criterion versus the whole advancement survives
 * the file.
 */
@DisplayName("the advancement condition")
class AdvancementConditionTest {

    @BeforeAll
    static void bootVanilla() {
        MinecraftTestBootstrap.boot();
    }

    @Test
    @DisplayName("the type is registered with a behaviour, so the gate can open")
    void behaviourRegistered() {
        var condition = new AdvancementCondition(Identifier.parse("minecraft:story/root"),
                Optional.empty());

        assertTrue(ConditionTypes.behaviourOf(condition).isPresent(),
                "a registered type with no behaviour is a gate that never opens");
    }

    @Test
    @DisplayName("the row names the advancement, or the one watched criterion of it")
    void display() {
        var whole = new AdvancementCondition(Identifier.parse("minecraft:story/mine_diamond"),
                Optional.empty());
        var one = new AdvancementCondition(Identifier.parse("minecraft:story/mine_diamond"),
                Optional.of("diamond"));

        assertEquals("tenet.condition.advancement", AdvancementCondition.DISPLAY.apply(whole).label());
        assertEquals("Have earned minecraft:story/mine_diamond",
                AdvancementCondition.DISPLAY.apply(whole).labelFallback());
        assertEquals("Have earned diamond of minecraft:story/mine_diamond",
                AdvancementCondition.DISPLAY.apply(one).labelFallback(),
                "a condition watching one criterion is about that criterion, not the whole advancement");
    }

    @Test
    @DisplayName("an advancement condition decodes with or without its criterion")
    void codec() {
        var whole = assertInstanceOf(AdvancementCondition.class, ConditionTypes.dispatchCodec()
                .parse(JsonOps.INSTANCE, JsonParser.parseString(
                        "{\"type\": \"tenet:advancement\", \"advancement\": \"minecraft:story/mine_diamond\"}"))
                .result().orElseThrow(() -> new AssertionError("did not decode")));
        assertEquals(Identifier.parse("minecraft:story/mine_diamond"), whole.advancement());
        assertEquals(Optional.empty(), whole.criterion(), "an absent criterion means the whole advancement");
        assertEquals(Identifier.parse("tenet:advancement"), whole.type());

        var one = assertInstanceOf(AdvancementCondition.class, ConditionTypes.dispatchCodec()
                .parse(JsonOps.INSTANCE, JsonParser.parseString("{\"type\": \"tenet:advancement\","
                        + " \"advancement\": \"minecraft:story/mine_diamond\", \"criterion\": \"diamond\"}"))
                .result().orElseThrow(() -> new AssertionError("did not decode")));
        assertEquals(Optional.of("diamond"), one.criterion());
    }
}
