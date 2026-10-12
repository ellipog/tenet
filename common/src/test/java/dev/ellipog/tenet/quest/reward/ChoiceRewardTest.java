package dev.ellipog.tenet.quest.reward;

import com.google.gson.JsonParser;
import com.mojang.serialization.JsonOps;

import dev.ellipog.tenet.quest.QuestReward;
import dev.ellipog.tenet.quest.loot.RewardTable;

import net.minecraft.util.RandomSource;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The choice reward: the player picks one entry.
 *
 * <p>One of the four ids sharing {@link TableReward}, and the one that cannot pay without a
 * decision — its payout <i>is</i> the player's pick. The claim marks nothing at grant time;
 * it offers the entries to the client, and the answer comes back as its own operation. That
 * round trip needs a live player, so the playthrough covers it; this answers the mode binding.
 */
@DisplayName("the choice reward")
class ChoiceRewardTest {

    private static TableReward reward(Optional<String> table, Optional<RewardTable> inline) {
        return new TableReward(RewardCommon.DEFAULT, TableReward.Mode.CHOICE, table, inline);
    }

    private static QuestReward decoded(String text) {
        return RewardTypes.dispatchCodec().parse(JsonOps.INSTANCE, JsonParser.parseString(text))
                .getOrThrow(error -> new AssertionError(text + " did not decode: " + error));
    }

    @Test
    @DisplayName("its type is tenet:choice, and its mode is choice")
    void typeAndModeAreChoice() {
        var parsed = assertInstanceOf(TableReward.class,
                decoded("{\"type\": \"tenet:choice\", \"table\": \"loot\"}"));

        assertEquals("tenet:choice", parsed.type().toString());
        assertEquals(TableReward.Mode.CHOICE, parsed.mode());
        assertEquals(TableReward.TYPE_CHOICE, parsed.type());
    }

    @Test
    @DisplayName("a choice cannot be auto-granted: its payout is the player's pick")
    void notAutoGrantable() {
        assertFalse(reward(Optional.of("loot"), Optional.empty()).autoGrantable(),
                "the automatic paths leave it outstanding for the claim flow instead of"
                        + " marking it collected and granting nothing");
        assertTrue(TableReward.isChoice(reward(Optional.of("loot"), Optional.empty())));
    }

    @Test
    @DisplayName("a named table is the loader's cross-file check; an inline table leaves none")
    void tableId() {
        assertEquals(Optional.of("loot"), reward(Optional.of("loot"), Optional.empty()).tableId());
        assertEquals(Optional.empty(),
                reward(Optional.empty(), Optional.of(new RewardTable(0, 1, List.of()))).tableId(),
                "an inline table has no reference to dangle");
    }

    @Test
    @DisplayName("a choice reward reads as choice")
    void description() {
        assertEquals("choice", reward(Optional.of("loot"), Optional.empty()).description());
    }

    @Test
    @DisplayName("the row names the table the player picks from")
    void display() {
        RewardDisplay display = RewardTypes.displayOf(reward(Optional.of("draft"), Optional.empty()));

        assertEquals("tenet.reward.table", display.label());
        assertEquals("Roll the draft table", display.labelFallback());
        assertEquals("draft", display.labelArg());
    }

    @Test
    @DisplayName("a choice reward decodes its table")
    void codec() {
        var parsed = assertInstanceOf(TableReward.class,
                decoded("{\"type\": \"tenet:choice\", \"table\": \"draft\"}"));

        assertEquals(TableReward.Mode.CHOICE, parsed.mode());
        assertEquals(Optional.of("draft"), parsed.table());
        assertEquals(Optional.empty(), parsed.inline());
    }

    @Test
    @DisplayName("a choice reward with neither table reads as neither")
    void codecDefaults() {
        var parsed = assertInstanceOf(TableReward.class,
                decoded("{\"type\": \"tenet:choice\"}"));

        assertEquals(TableReward.Mode.CHOICE, parsed.mode());
        assertEquals(Optional.empty(), parsed.table());
        assertEquals(Optional.empty(), parsed.inline());
    }

    @Test
    @DisplayName("a roll hands entries out rather than offering them, so a choice inside is skipped")
    void resolveSkipsAChoiceInside() {
        XpReward leaf = new XpReward(RewardCommon.DEFAULT, 5, false);
        TableReward nested = reward(Optional.empty(),
                Optional.of(new RewardTable(0, 1, List.of(new RewardTable.Entry(1, leaf)))));

        assertTrue(TableReward.resolve(List.of(nested), RandomSource.create(1), 0).isEmpty(),
                "the grant cannot offer a pick, so it grants nothing for the entry");
    }

    @Test
    @DisplayName("the type is registered, and the offer waits for a live player")
    void behaviourIsRegistered() {
        // The grant is a no-op by design: the claim flow offers the entries and the pick comes
        // back as its own operation — the playthrough covers that round trip.
        assertTrue(RewardTypes.behaviourOf(reward(Optional.of("loot"), Optional.empty())).isPresent());
    }
}
