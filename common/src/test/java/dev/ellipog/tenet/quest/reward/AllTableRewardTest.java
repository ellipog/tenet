package dev.ellipog.tenet.quest.reward;

import com.google.gson.JsonParser;
import com.mojang.serialization.JsonOps;

import dev.ellipog.tenet.quest.ItemRef;
import dev.ellipog.tenet.quest.QuestReward;
import dev.ellipog.tenet.quest.loot.RewardTable;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.RandomSource;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The all-table reward: every entry once, weights ignored.
 *
 * <p>One of the four ids sharing {@link TableReward}. Where its siblings throw the dice,
 * this one lists — the empty weight and the weights are set aside. The grant needs the loaded
 * questline, so the playthrough covers it; this answers the mode binding.
 */
@DisplayName("the all-table reward")
class AllTableRewardTest {

    private static TableReward reward(Optional<String> table, Optional<RewardTable> inline) {
        return new TableReward(RewardCommon.DEFAULT, TableReward.Mode.ALL_TABLE, table, inline);
    }

    private static QuestReward decoded(String text) {
        return RewardTypes.dispatchCodec().parse(JsonOps.INSTANCE, JsonParser.parseString(text))
                .getOrThrow(error -> new AssertionError(text + " did not decode: " + error));
    }

    private static ItemReward item(String id) {
        return new ItemReward(RewardCommon.DEFAULT,
                new ItemRef(ResourceLocation.parse(id), 1), 0, false);
    }

    @Test
    @DisplayName("its type is tenet:all_table, and its mode is all_table")
    void typeAndModeAreAllTable() {
        var parsed = assertInstanceOf(TableReward.class,
                decoded("{\"type\": \"tenet:all_table\", \"table\": \"loot\"}"));

        assertEquals("tenet:all_table", parsed.type().toString());
        assertEquals(TableReward.Mode.ALL_TABLE, parsed.mode());
        assertEquals(TableReward.TYPE_ALL_TABLE, parsed.type());
    }

    @Test
    @DisplayName("an all-table reward can be granted without asking the player anything")
    void autoGrantable() {
        assertTrue(reward(Optional.of("loot"), Optional.empty()).autoGrantable(),
                "it lists and pays immediately, so the automatic paths may take it");
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
    @DisplayName("an all-table reward reads as all table")
    void description() {
        assertEquals("all table", reward(Optional.of("loot"), Optional.empty()).description());
    }

    @Test
    @DisplayName("the row names the table it takes everything from")
    void display() {
        RewardDisplay display =
                RewardTypes.displayOf(reward(Optional.of("starter_kit"), Optional.empty()));

        assertEquals("tenet.reward.table", display.label());
        assertEquals("Roll the starter_kit table", display.labelFallback());
        assertEquals("starter_kit", display.labelArg());
    }

    @Test
    @DisplayName("an all-table reward decodes its table")
    void codec() {
        var parsed = assertInstanceOf(TableReward.class,
                decoded("{\"type\": \"tenet:all_table\", \"table\": \"starter_kit\"}"));

        assertEquals(TableReward.Mode.ALL_TABLE, parsed.mode());
        assertEquals(Optional.of("starter_kit"), parsed.table());
        assertEquals(Optional.empty(), parsed.inline());
    }

    @Test
    @DisplayName("an all-table reward with neither table reads as neither")
    void codecDefaults() {
        var parsed = assertInstanceOf(TableReward.class,
                decoded("{\"type\": \"tenet:all_table\"}"));

        assertEquals(TableReward.Mode.ALL_TABLE, parsed.mode());
        assertEquals(Optional.empty(), parsed.table());
        assertEquals(Optional.empty(), parsed.inline());
    }

    @Test
    @DisplayName("resolving an all-table entry takes every entry once, weights ignored")
    void resolveTakesEverythingOnce() {
        ItemReward stone = item("minecraft:stone");
        ItemReward diamond = item("minecraft:diamond");
        RewardTable inline = new RewardTable(5, 4, List.of(
                new RewardTable.Entry(0, stone), new RewardTable.Entry(9, diamond)));
        TableReward nested = reward(Optional.empty(), Optional.of(inline));

        // Inline, so no loaded questline is read; the weights and the empty weight are set aside.
        assertEquals(List.of(stone, diamond),
                TableReward.resolve(List.of(nested), RandomSource.create(1), 0));
    }

    @Test
    @DisplayName("the type is registered, and the grant waits for a loaded table and a player")
    void behaviourIsRegistered() {
        // The grant lists the named table from the loaded questline — the playthrough covers it.
        assertTrue(RewardTypes.behaviourOf(reward(Optional.of("loot"), Optional.empty())).isPresent());
    }
}
