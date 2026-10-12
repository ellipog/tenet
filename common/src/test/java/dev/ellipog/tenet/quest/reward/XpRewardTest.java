package dev.ellipog.tenet.quest.reward;

import com.google.gson.JsonParser;
import com.mojang.serialization.JsonOps;

import dev.ellipog.tenet.quest.QuestReward;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The experience reward: points or levels, and the sentence each wears.
 *
 * <p>Points and levels are genuinely different rewards — thirty points is a nudge, thirty
 * levels is most of a playthrough — so each wears its own sentence. The grant calls straight
 * into the player, which needs a live one; the playthrough covers it.
 */
@DisplayName("the experience reward")
class XpRewardTest {

    private static XpReward reward(int amount, boolean levels) {
        return new XpReward(RewardCommon.DEFAULT, amount, levels);
    }

    private static QuestReward decoded(String text) {
        return RewardTypes.dispatchCodec().parse(JsonOps.INSTANCE, JsonParser.parseString(text))
                .getOrThrow(error -> new AssertionError(text + " did not decode: " + error));
    }

    @Test
    @DisplayName("its type is tenet:xp")
    void typeIsTenetXp() {
        assertEquals("tenet:xp", reward(30, false).type().toString());
    }

    @Test
    @DisplayName("points wear the points sentence, with the amount as the count")
    void pointsDisplay() {
        RewardDisplay display = RewardTypes.displayOf(reward(30, false));

        assertEquals("tenet.reward.xp.points", display.label());
        assertEquals("30 XP", display.labelFallback());
        assertEquals(30, display.count());
        assertTrue(display.item().isEmpty(), "experience has no item to look up");
    }

    @Test
    @DisplayName("levels wear the level sentence, singular for one")
    void levelsDisplay() {
        RewardDisplay one = RewardTypes.displayOf(reward(1, true));

        assertEquals("tenet.reward.xp.level", one.label());
        assertEquals("1 level", one.labelFallback());

        RewardDisplay many = RewardTypes.displayOf(reward(3, true));

        assertEquals("tenet.reward.xp.levels", many.label());
        assertEquals("3 levels", many.labelFallback());
        assertEquals(3, many.count());
    }

    @Test
    @DisplayName("an experience reward decodes its amount and its levels flag")
    void codec() {
        var parsed = assertInstanceOf(XpReward.class, decoded(
                "{\"type\": \"tenet:xp\", \"amount\": 30, \"levels\": true}"));

        assertEquals(30, parsed.amount());
        assertTrue(parsed.levels());
    }

    @Test
    @DisplayName("an experience reward without the flag means points")
    void codecDefaults() {
        var parsed = assertInstanceOf(XpReward.class,
                decoded("{\"type\": \"tenet:xp\", \"amount\": 5}"));

        assertEquals(5, parsed.amount());
        assertFalse(parsed.levels(), "absent means points, not levels");
    }

    @Test
    @DisplayName("an experience reward round-trips through the dispatch codec")
    void codecRoundTrip() {
        var encoded = RewardTypes.dispatchCodec()
                .encodeStart(JsonOps.INSTANCE, reward(30, true))
                .getOrThrow(error -> new AssertionError("did not encode: " + error));
        var parsed = assertInstanceOf(XpReward.class, RewardTypes.dispatchCodec()
                .parse(JsonOps.INSTANCE, encoded)
                .getOrThrow(error -> new AssertionError("did not decode: " + error)));

        assertEquals(30, parsed.amount());
        assertTrue(parsed.levels());
    }

    @Test
    @DisplayName("the type is registered, and the grant waits for a live player")
    void behaviourIsRegistered() {
        // The grant calls giveExperiencePoints or giveExperienceLevels on the player, so there
        // is no grant to run in a bare JVM — the playthrough covers it.
        assertTrue(RewardTypes.behaviourOf(reward(30, false)).isPresent());
    }
}
