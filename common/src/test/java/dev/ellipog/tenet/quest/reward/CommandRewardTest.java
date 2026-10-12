package dev.ellipog.tenet.quest.reward;

import com.google.gson.JsonParser;
import com.mojang.serialization.JsonOps;

import dev.ellipog.tenet.quest.QuestReward;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The command reward: run a command as the player.
 *
 * <p>The escape hatch every pack reaches for, and the one reward whose reach is the whole
 * server — it runs through the server's own dispatcher, so the grant needs a live server and
 * player and the playthrough covers it. The feedback line's own decoding lives with the toast
 * reward's tests; this answers the sentence, the permission, and the round trip.
 */
@DisplayName("the command reward")
class CommandRewardTest {

    private static CommandReward reward(String command) {
        return new CommandReward(RewardCommon.DEFAULT, command, 2, false, Optional.empty());
    }

    private static QuestReward decoded(String text) {
        return RewardTypes.dispatchCodec().parse(JsonOps.INSTANCE, JsonParser.parseString(text))
                .getOrThrow(error -> new AssertionError(text + " did not decode: " + error));
    }

    @Test
    @DisplayName("its type is tenet:command")
    void typeIsTenetCommand() {
        assertEquals("tenet:command", reward("say hello").type().toString());
    }

    @Test
    @DisplayName("the row names the command it runs")
    void display() {
        RewardDisplay display = RewardTypes.displayOf(reward("say hello"));

        assertEquals("tenet.reward.command", display.label());
        assertEquals("Run say hello", display.labelFallback());
        assertEquals("say hello", display.labelArg());
        assertEquals(1, display.count());
    }

    @Test
    @DisplayName("a command reward decodes its command, permission, silence and feedback")
    void codec() {
        var parsed = assertInstanceOf(CommandReward.class, decoded(
                "{\"type\": \"tenet:command\", \"command\": \"give {p} minecraft:apple\","
                        + " \"permissionLevel\": 4, \"silent\": true,"
                        + " \"feedbackMessage\": \"Apples away.\"}"));

        assertEquals("give {p} minecraft:apple", parsed.command());
        assertEquals(4, parsed.permissionLevel());
        assertTrue(parsed.silent());
        assertEquals("Apples away.", parsed.feedbackMessage().orElseThrow().value());
    }

    @Test
    @DisplayName("a bare command runs at a command block's level, aloud, with no feedback")
    void codecDefaults() {
        var parsed = assertInstanceOf(CommandReward.class,
                decoded("{\"type\": \"tenet:command\", \"command\": \"say hello\"}"));

        assertEquals("say hello", parsed.command());
        assertEquals(2, parsed.permissionLevel(), "enough for say, give and summon, without /op");
        assertFalse(parsed.silent());
        assertEquals(Optional.empty(), parsed.feedbackMessage(),
                "absent means nothing extra is shown");
    }

    @Test
    @DisplayName("a feedback line accepts the translation-key shape")
    void feedbackTranslates() {
        var parsed = assertInstanceOf(CommandReward.class, decoded(
                "{\"type\": \"tenet:command\", \"command\": \"say hello\","
                        + " \"feedbackMessage\": {\"translate\": \"pack.command.done\","
                        + " \"fallback\": \"Well done.\"}}"));

        assertTrue(parsed.feedbackMessage().orElseThrow().translatable());
        assertEquals("Well done.", parsed.feedbackMessage().orElseThrow().fallback().orElseThrow());
    }

    @Test
    @DisplayName("a command reward round-trips through the dispatch codec")
    void codecRoundTrip() {
        var encoded = RewardTypes.dispatchCodec()
                .encodeStart(JsonOps.INSTANCE, reward("say hello"))
                .getOrThrow(error -> new AssertionError("did not encode: " + error));
        var parsed = assertInstanceOf(CommandReward.class, RewardTypes.dispatchCodec()
                .parse(JsonOps.INSTANCE, encoded)
                .getOrThrow(error -> new AssertionError("did not decode: " + error)));

        assertEquals("say hello", parsed.command());
        assertEquals(2, parsed.permissionLevel());
        assertFalse(parsed.silent());
    }

    @Test
    @DisplayName("the type is registered, and the run waits for a live server")
    void behaviourIsRegistered() {
        // The grant runs the substituted command through the server dispatcher as the player,
        // so there is no grant to run in a bare JVM — the playthrough covers it, including the
        // {p}/{x}/{quest} placeholders, which need the player's position and quest identity.
        assertTrue(RewardTypes.behaviourOf(reward("say hello")).isPresent());
    }
}
