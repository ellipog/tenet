package dev.ellipog.tenet.quest.reward;

import com.google.gson.JsonParser;
import com.mojang.serialization.JsonOps;

import dev.ellipog.tenet.quest.QuestReward;

import net.minecraft.resources.ResourceLocation;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The advancement reward: an advancement, or one criterion of one.
 *
 * <p>An empty criterion means every criterion, which is the legacy reading. The grant awards
 * through the player's advancement tracker, so it needs a live server and player and the
 * playthrough covers it; this answers the sentence and the round trip.
 */
@DisplayName("the advancement reward")
class AdvancementRewardTest {

    private static AdvancementReward reward(String advancement, Optional<String> criterion) {
        return new AdvancementReward(RewardCommon.DEFAULT, ResourceLocation.parse(advancement),
                criterion);
    }

    private static QuestReward decoded(String text) {
        return RewardTypes.dispatchCodec().parse(JsonOps.INSTANCE, JsonParser.parseString(text))
                .getOrThrow(error -> new AssertionError(text + " did not decode: " + error));
    }

    @Test
    @DisplayName("its type is tenet:advancement")
    void typeIsTenetAdvancement() {
        assertEquals("tenet:advancement",
                reward("minecraft:story/mine_stone", Optional.empty()).type().toString());
    }

    @Test
    @DisplayName("the row names the advancement it awards")
    void displayWhole() {
        RewardDisplay display =
                RewardTypes.displayOf(reward("minecraft:story/mine_stone", Optional.empty()));

        assertEquals("tenet.reward.advancement", display.label());
        assertEquals("Award minecraft:story/mine_stone", display.labelFallback());
        assertEquals("minecraft:story/mine_stone", display.labelArg());
    }

    @Test
    @DisplayName("with a criterion, the row names the criterion of the advancement")
    void displayCriterion() {
        RewardDisplay display =
                RewardTypes.displayOf(reward("minecraft:story/mine_stone", Optional.of("get_stone")));

        assertEquals("tenet.reward.advancement", display.label());
        assertEquals("get_stone of minecraft:story/mine_stone", display.labelArg());
    }

    @Test
    @DisplayName("an advancement reward decodes its advancement and its criterion")
    void codec() {
        var parsed = assertInstanceOf(AdvancementReward.class, decoded(
                "{\"type\": \"tenet:advancement\","
                        + " \"advancement\": \"minecraft:story/mine_stone\","
                        + " \"criterion\": \"get_stone\"}"));

        assertEquals("minecraft:story/mine_stone", parsed.advancement().toString());
        assertEquals(Optional.of("get_stone"), parsed.criterion());
    }

    @Test
    @DisplayName("an advancement reward without a criterion means the whole advancement")
    void codecDefaults() {
        var parsed = assertInstanceOf(AdvancementReward.class, decoded(
                "{\"type\": \"tenet:advancement\","
                        + " \"advancement\": \"minecraft:story/mine_stone\"}"));

        assertEquals("minecraft:story/mine_stone", parsed.advancement().toString());
        assertEquals(Optional.empty(), parsed.criterion(),
                "absent means every criterion, not the first of them");
    }

    @Test
    @DisplayName("an advancement reward round-trips through the dispatch codec")
    void codecRoundTrip() {
        var encoded = RewardTypes.dispatchCodec()
                .encodeStart(JsonOps.INSTANCE,
                        reward("minecraft:story/mine_stone", Optional.of("get_stone")))
                .getOrThrow(error -> new AssertionError("did not encode: " + error));
        var parsed = assertInstanceOf(AdvancementReward.class, RewardTypes.dispatchCodec()
                .parse(JsonOps.INSTANCE, encoded)
                .getOrThrow(error -> new AssertionError("did not decode: " + error)));

        assertEquals("minecraft:story/mine_stone", parsed.advancement().toString());
        assertEquals(Optional.of("get_stone"), parsed.criterion());
    }

    @Test
    @DisplayName("the type is registered, and the award waits for a live server")
    void behaviourIsRegistered() {
        // The grant looks the advancement up on the server and awards through the player's
        // tracker — an unknown id warns and grants nothing — so there is no grant to run in a
        // bare JVM. The playthrough covers it.
        assertTrue(RewardTypes
                .behaviourOf(reward("minecraft:story/mine_stone", Optional.empty())).isPresent());
    }
}
