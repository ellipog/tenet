package dev.ellipog.tenet.quest.reward;

import com.google.gson.JsonParser;
import com.mojang.serialization.JsonOps;

import dev.ellipog.tenet.quest.QuestReward;

import net.minecraft.resources.ResourceLocation;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The stage reward: set a stage, or take one away.
 *
 * <p>The write half of the flags the stage task asks about. A grant and a revocation are
 * different acts, so each wears its own sentence. The grant writes through the stage store on
 * the server, so it needs a live one and the playthrough covers it; this answers the sentence
 * and the round trip.
 */
@DisplayName("the stage reward")
class StageRewardTest {

    private static StageReward reward(String stage, boolean remove, boolean teamStage) {
        return new StageReward(RewardCommon.DEFAULT, ResourceLocation.parse(stage), remove,
                teamStage);
    }

    private static QuestReward decoded(String text) {
        return RewardTypes.dispatchCodec().parse(JsonOps.INSTANCE, JsonParser.parseString(text))
                .getOrThrow(error -> new AssertionError(text + " did not decode: " + error));
    }

    @Test
    @DisplayName("its type is tenet:stage")
    void typeIsTenetStage() {
        assertEquals("tenet:stage", reward("example:left_village", false, false).type().toString());
    }

    @Test
    @DisplayName("a grant wears the grant sentence")
    void displayGrant() {
        RewardDisplay display =
                RewardTypes.displayOf(reward("example:left_village", false, false));

        assertEquals("tenet.reward.stage.grant", display.label());
        assertEquals("Grant the stage example:left_village", display.labelFallback());
        assertEquals("example:left_village", display.labelArg());
    }

    @Test
    @DisplayName("a removal wears the removal sentence, not the grant one")
    void displayRemove() {
        RewardDisplay display =
                RewardTypes.displayOf(reward("example:left_village", true, false));

        assertEquals("tenet.reward.stage.remove", display.label());
        assertEquals("Remove the stage example:left_village", display.labelFallback());
    }

    @Test
    @DisplayName("a stage reward decodes its stage and its two flags")
    void codec() {
        var parsed = assertInstanceOf(StageReward.class, decoded(
                "{\"type\": \"tenet:stage\", \"stage\": \"example:left_village\","
                        + " \"remove\": true, \"teamStage\": true}"));

        assertEquals("example:left_village", parsed.stage().toString());
        assertTrue(parsed.remove());
        assertTrue(parsed.teamStage());
    }

    @Test
    @DisplayName("a bare stage grants to the claiming player")
    void codecDefaults() {
        var parsed = assertInstanceOf(StageReward.class, decoded(
                "{\"type\": \"tenet:stage\", \"stage\": \"example:left_village\"}"));

        assertEquals("example:left_village", parsed.stage().toString());
        assertFalse(parsed.remove(), "absent means grant, not revocation");
        assertFalse(parsed.teamStage(), "absent means the claiming player, not the team");
    }

    @Test
    @DisplayName("a stage reward round-trips through the dispatch codec")
    void codecRoundTrip() {
        var encoded = RewardTypes.dispatchCodec()
                .encodeStart(JsonOps.INSTANCE, reward("example:left_village", true, true))
                .getOrThrow(error -> new AssertionError("did not encode: " + error));
        var parsed = assertInstanceOf(StageReward.class, RewardTypes.dispatchCodec()
                .parse(JsonOps.INSTANCE, encoded)
                .getOrThrow(error -> new AssertionError("did not decode: " + error)));

        assertEquals("example:left_village", parsed.stage().toString());
        assertTrue(parsed.remove());
        assertTrue(parsed.teamStage());
    }

    @Test
    @DisplayName("the type is registered, and the write waits for a live server")
    void behaviourIsRegistered() {
        // The grant writes the stage store — player or team, grant or removal — so there is no
        // grant to run in a bare JVM. The playthrough covers it.
        assertTrue(RewardTypes.behaviourOf(reward("example:left_village", false, false))
                .isPresent());
    }
}
