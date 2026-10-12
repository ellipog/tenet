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
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The loot reward: a roll that can come up empty.
 *
 * <p>One of the four ids sharing {@link TableReward}. Beside {@code random} it is the only
 * mode whose dice include the empty band — a {@code random} reward promises something, a
 * {@code loot} reward is allowed to promise nothing. The grant needs the loaded questline and
 * a live player's dice, so the playthrough covers it; this answers the mode binding.
 */
@DisplayName("the loot reward")
class LootRewardTest {

    private static TableReward reward(Optional<String> table, Optional<RewardTable> inline) {
        return new TableReward(RewardCommon.DEFAULT, TableReward.Mode.LOOT, table, inline);
    }

    private static QuestReward decoded(String text) {
        return RewardTypes.dispatchCodec().parse(JsonOps.INSTANCE, JsonParser.parseString(text))
                .getOrThrow(error -> new AssertionError(text + " did not decode: " + error));
    }

    @Test
    @DisplayName("its type is tenet:loot, and its mode is loot")
    void typeAndModeAreLoot() {
        var parsed = assertInstanceOf(TableReward.class,
                decoded("{\"type\": \"tenet:loot\", \"table\": \"loot\"}"));

        assertEquals("tenet:loot", parsed.type().toString());
        assertEquals(TableReward.Mode.LOOT, parsed.mode());
        assertEquals(TableReward.TYPE_LOOT, parsed.type());
    }

    @Test
    @DisplayName("a loot reward can be granted without asking the player anything")
    void autoGrantable() {
        assertTrue(reward(Optional.of("loot"), Optional.empty()).autoGrantable(),
                "it rolls and pays immediately, empty or not");
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
    @DisplayName("a loot reward reads as loot")
    void description() {
        assertEquals("loot", reward(Optional.of("loot"), Optional.empty()).description());
    }

    @Test
    @DisplayName("the row names the table it rolls")
    void display() {
        RewardDisplay display = RewardTypes.displayOf(reward(Optional.of("dungeon"), Optional.empty()));

        assertEquals("tenet.reward.table", display.label());
        assertEquals("Roll the dungeon table", display.labelFallback());
        assertEquals("dungeon", display.labelArg());
    }

    @Test
    @DisplayName("a loot reward decodes its table")
    void codec() {
        var parsed = assertInstanceOf(TableReward.class,
                decoded("{\"type\": \"tenet:loot\", \"table\": \"dungeon\"}"));

        assertEquals(TableReward.Mode.LOOT, parsed.mode());
        assertEquals(Optional.of("dungeon"), parsed.table());
        assertEquals(Optional.empty(), parsed.inline());
    }

    @Test
    @DisplayName("a loot reward with neither table reads as neither")
    void codecDefaults() {
        var parsed = assertInstanceOf(TableReward.class,
                decoded("{\"type\": \"tenet:loot\"}"));

        assertEquals(TableReward.Mode.LOOT, parsed.mode());
        assertEquals(Optional.empty(), parsed.table());
        assertEquals(Optional.empty(), parsed.inline());
    }

    @Test
    @DisplayName("a loot reward decodes an inline table, with its empty weight")
    void inlineCodec() {
        var parsed = assertInstanceOf(TableReward.class, decoded(
                "{\"type\": \"tenet:loot\","
                        + " \"inline\": {\"emptyWeight\": 10, \"entries\": [{\"weight\": 1,"
                        + " \"reward\": {\"type\": \"tenet:xp\", \"amount\": 5}}]}}"));

        assertEquals(10.0, parsed.inline().orElseThrow().emptyWeight());
        assertEquals(1, parsed.inline().orElseThrow().entryCount());
    }

    @Test
    @DisplayName("the empty band never swallows a guaranteed entry")
    void resolveKeepsGuarantees() {
        XpReward guaranteed = new XpReward(RewardCommon.DEFAULT, 5, false);
        RewardTable inline = new RewardTable(1000, 1,
                List.of(new RewardTable.Entry(0, guaranteed)));
        TableReward nested = reward(Optional.empty(), Optional.of(inline));

        // A weight-zero entry is always granted, whatever the empty weight says.
        assertTrue(TableReward.resolve(List.of(nested), RandomSource.create(1), 0)
                .contains(guaranteed));
    }

    @Test
    @DisplayName("the type is registered, and the grant waits for a loaded table and a player")
    void behaviourIsRegistered() {
        // The grant resolves the named table from the loaded questline and rolls the player's
        // dice with the empty band in play — the playthrough covers it.
        assertTrue(RewardTypes.behaviourOf(reward(Optional.of("loot"), Optional.empty())).isPresent());
    }
}
