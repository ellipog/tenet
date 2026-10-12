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
 * The random reward: one guaranteed roll from a table.
 *
 * <p>One of the four ids sharing {@link TableReward}: the mode is fixed by the type id, never
 * read from the file. The grant resolves the table and pays immediately, which needs the loaded
 * questline and a live player's dice — the playthrough covers that. What is answered here is
 * the mode binding and the pure half beside it.
 */
@DisplayName("the random reward")
class RandomRewardTest {

    private static TableReward reward(Optional<String> table, Optional<RewardTable> inline) {
        return new TableReward(RewardCommon.DEFAULT, TableReward.Mode.RANDOM, table, inline);
    }

    private static QuestReward decoded(String text) {
        return RewardTypes.dispatchCodec().parse(JsonOps.INSTANCE, JsonParser.parseString(text))
                .getOrThrow(error -> new AssertionError(text + " did not decode: " + error));
    }

    private static RewardTable inlineTable(QuestReward... rewards) {
        List<RewardTable.Entry> entries = new java.util.ArrayList<>();
        for (QuestReward entry : rewards) {
            entries.add(new RewardTable.Entry(1, entry));
        }
        return new RewardTable(0, 1, entries);
    }

    @Test
    @DisplayName("its type is tenet:random, and its mode is random")
    void typeAndModeAreRandom() {
        var parsed = assertInstanceOf(TableReward.class,
                decoded("{\"type\": \"tenet:random\", \"table\": \"loot\"}"));

        assertEquals("tenet:random", parsed.type().toString());
        assertEquals(TableReward.Mode.RANDOM, parsed.mode());
        assertEquals(TableReward.TYPE_RANDOM, parsed.type());
    }

    @Test
    @DisplayName("a random reward can be granted without asking the player anything")
    void autoGrantable() {
        assertTrue(reward(Optional.of("loot"), Optional.empty()).autoGrantable(),
                "it rolls and pays immediately, so the automatic paths may take it");
    }

    @Test
    @DisplayName("a named table is the loader's cross-file check; an inline table leaves none")
    void tableId() {
        assertEquals(Optional.of("loot"), reward(Optional.of("loot"), Optional.empty()).tableId());
        assertEquals(Optional.empty(),
                reward(Optional.of("loot"), Optional.of(inlineTable())).tableId(),
                "an inline table has no reference to dangle, even beside a name");
    }

    @Test
    @DisplayName("a random reward reads as random")
    void description() {
        assertEquals("random", reward(Optional.of("loot"), Optional.empty()).description());
    }

    @Test
    @DisplayName("the row names the table it rolls")
    void display() {
        RewardDisplay named = RewardTypes.displayOf(reward(Optional.of("loot"), Optional.empty()));

        assertEquals("tenet.reward.table", named.label());
        assertEquals("Roll the loot table", named.labelFallback());
        assertEquals("loot", named.labelArg());

        RewardDisplay inline =
                RewardTypes.displayOf(reward(Optional.empty(), Optional.of(inlineTable())));

        assertEquals("inline", inline.labelArg(), "an inline table has no id and says so");
    }

    @Test
    @DisplayName("a random reward decodes its table")
    void codec() {
        var parsed = assertInstanceOf(TableReward.class,
                decoded("{\"type\": \"tenet:random\", \"table\": \"dungeon\"}"));

        assertEquals(TableReward.Mode.RANDOM, parsed.mode());
        assertEquals(Optional.of("dungeon"), parsed.table());
        assertEquals(Optional.empty(), parsed.inline());
    }

    @Test
    @DisplayName("a random reward with neither table reads as neither")
    void codecDefaults() {
        var parsed = assertInstanceOf(TableReward.class,
                decoded("{\"type\": \"tenet:random\"}"));

        assertEquals(TableReward.Mode.RANDOM, parsed.mode());
        assertEquals(Optional.empty(), parsed.table());
        assertEquals(Optional.empty(), parsed.inline());
    }

    @Test
    @DisplayName("a random reward decodes an inline table, with its entries")
    void inlineCodec() {
        var parsed = assertInstanceOf(TableReward.class, decoded(
                "{\"type\": \"tenet:random\","
                        + " \"inline\": {\"entries\": [{\"weight\": 1,"
                        + " \"reward\": {\"type\": \"tenet:xp\", \"amount\": 5}}]}}"));

        assertEquals(1, parsed.inline().orElseThrow().entryCount());
        assertEquals("tenet:xp",
                parsed.inline().orElseThrow().entries().get(0).reward().type().toString());
    }

    @Test
    @DisplayName("resolving a random entry rolls its inline table now")
    void resolveRollsNow() {
        XpReward leaf = new XpReward(RewardCommon.DEFAULT, 5, false);
        TableReward nested =
                reward(Optional.empty(), Optional.of(inlineTable(leaf)));

        // Inline, so no loaded questline is read; a one-entry table rolls to its entry.
        assertEquals(List.of(leaf), TableReward.resolve(List.of(nested), RandomSource.create(1), 0));
    }

    @Test
    @DisplayName("the type is registered, and the grant waits for a loaded table and a player")
    void behaviourIsRegistered() {
        // The grant resolves the named table from the loaded questline and rolls the player's
        // dice, so there is no grant to run in a bare JVM — the playthrough covers it.
        assertTrue(RewardTypes.behaviourOf(reward(Optional.of("loot"), Optional.empty())).isPresent());
    }
}
