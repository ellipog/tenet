package dev.ellipog.tenet.quest.condition;

import com.google.gson.JsonParser;
import com.mojang.serialization.JsonOps;

import dev.ellipog.tenet.quest.MinecraftTestBootstrap;

import net.minecraft.resources.ResourceLocation;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The party-size condition's file shape and sentence.
 *
 * <p>The evaluation resolves the player to their team and counts who of it is online, so it needs a
 * live server twice over and there is no behaviour invocation here. The playthrough covers the
 * gate — including the "a solo player is a party of one" reading, which only a player list can
 * answer. What is pinned here is that the type is registered with a behaviour at all, and that the
 * minimum survives the file.
 */
@DisplayName("the party-size condition")
class PartySizeConditionTest {

    @BeforeAll
    static void bootVanilla() {
        MinecraftTestBootstrap.boot();
    }

    @Test
    @DisplayName("the type is registered with a behaviour, so the gate can open")
    void behaviourRegistered() {
        var condition = new PartySizeCondition(2);

        assertTrue(ConditionTypes.behaviourOf(condition).isPresent(),
                "a registered type with no behaviour is a gate that never opens");
    }

    @Test
    @DisplayName("the row names how many of the party have to be online")
    void display() {
        var condition = new PartySizeCondition(2);

        assertEquals("tenet.condition.party_size", PartySizeCondition.DISPLAY.apply(condition).label());
        assertEquals("Be in a party of 2 or more",
                PartySizeCondition.DISPLAY.apply(condition).labelFallback());
        assertEquals("2", PartySizeCondition.DISPLAY.apply(condition).labelArg());
    }

    @Test
    @DisplayName("a party-size condition decodes with its minimum, which has no default to fall back on")
    void codec() {
        var parsed = assertInstanceOf(PartySizeCondition.class, ConditionTypes.dispatchCodec()
                .parse(JsonOps.INSTANCE,
                        JsonParser.parseString("{\"type\": \"tenet:party_size\", \"min\": 2}"))
                .result().orElseThrow(() -> new AssertionError("did not decode")));
        assertEquals(2, parsed.min());
        assertEquals(ResourceLocation.parse("tenet:party_size"), parsed.type());

        assertTrue(ConditionTypes.dispatchCodec().parse(JsonOps.INSTANCE,
                JsonParser.parseString("{\"type\": \"tenet:party_size\"}"))
                .result().isEmpty(), "without a minimum there is nothing to reach");
        assertTrue(ConditionTypes.dispatchCodec().parse(JsonOps.INSTANCE,
                JsonParser.parseString("{\"type\": \"tenet:party_size\", \"min\": 0}"))
                .result().isEmpty(), "a party of nobody is not a party");
    }

    @Test
    @DisplayName("a held count answers without a server: the tick's own shortcut, pinned headless")
    void heldCountNeedsNoServer() {
        // The tick walks members it already holds and tells each evaluation the count, so the
        // behaviour must never touch the server when one is held. Null server and player would
        // throw on the resolve path, which is exactly what makes this a test rather than a wish.
        var condition = new PartySizeCondition(2);
        ConditionBehaviour<PartySizeCondition> behaviour = PartySizeCondition.BEHAVIOUR;

        assertTrue(behaviour.test(condition, new ConditionContext(null, null, null, 3)),
                "three present opens a gate of two");
        assertTrue(behaviour.test(condition, new ConditionContext(null, null, null, 2)),
                "exactly enough is enough");
        assertTrue(!behaviour.test(condition, new ConditionContext(null, null, null, 1)),
                "one present does not open a gate of two");
    }
}
