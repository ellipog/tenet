package dev.ellipog.tenet.client;

import dev.ellipog.tenet.quest.ItemRef;
import dev.ellipog.tenet.quest.MinecraftTestBootstrap;
import dev.ellipog.tenet.quest.reward.RewardDisplay;

import net.minecraft.resources.Identifier;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * What a reward is called on a row, which was wrong in three places at once.
 *
 * <p>The bug this pins: {@code label} is a translation key and {@code labelArg} is the subject it is
 * formatted with, so drawing either of them straight onto a row prints {@code tenet:reward.xp.points},
 * and passing the count as the subject prints {@code Roll the 1 table} for a table called {@code dice}.
 * Each case below is one of the three panels that got it wrong.
 */
@DisplayName("a reward's row text")
class RewardTextTest {

    @BeforeAll
    static void bootVanilla() {
        MinecraftTestBootstrap.boot();
    }

    @Test
    @DisplayName("an item reward is named by the item, not by a label it does not have")
    void itemRewardsUseTheItemsName() {
        RewardDisplay display = RewardDisplay.ofItem(
                new ItemRef(Identifier.withDefaultNamespace("iron_ingot"), 4));

        assertEquals("Iron Ingot", RewardText.of(display));
        assertEquals(4, RewardText.countOf(display));
        assertEquals("x4 Iron Ingot", RewardText.withCount(RewardText.of(display), 4));
    }

    @Test
    @DisplayName("an item this build does not have shows the id rather than nothing")
    void missingItemsShowTheirId() {
        RewardDisplay display = RewardDisplay.ofItem(
                new ItemRef(Identifier.fromNamespaceAndPath("gone", "relic"), 1));

        assertEquals("gone:relic", RewardText.of(display));
    }

    @Test
    @DisplayName("a key that counts something is formatted with the count")
    void countedKeysUseTheCount() {
        // XpReward.DISPLAY's shape: no subject, the count is the argument.
        RewardDisplay display = RewardDisplay.ofTranslatableText("tenet.reward.xp.points", "5 XP", 5);

        assertEquals("5 XP", RewardText.of(display));
    }

    @Test
    @DisplayName("a key that names something is formatted with its subject, never with the count")
    void namedKeysUseTheirSubject() {
        // TableReward.DISPLAY's shape: the subject is the table's id and the count is 1. Reading the
        // count into the key is what produced "Roll the 1 table".
        RewardDisplay display = RewardDisplay.ofTranslatableText("tenet.reward.table",
                "Roll the dice table", "dice", 1);

        assertEquals("Roll the dice table", RewardText.of(display));
    }

    @Test
    @DisplayName("a display with neither an item nor a key is a question mark, not a blank row")
    void nothingToNameIsAQuestionMark() {
        assertEquals("?", RewardText.of(RewardDisplay.NONE));
        assertEquals("?", RewardText.of("", "", "", 1, ""));
    }

    @Test
    @DisplayName("the payload's shape reads the same as the record's")
    void theWireShapeAgrees() {
        // The choice card's entries arrive as fields rather than as a RewardDisplay; both doors lead to
        // the same sentence, which is the whole point of this class.
        assertEquals(RewardText.of(RewardDisplay.ofTranslatableText("tenet.reward.table",
                        "Roll the dice table", "dice", 1)),
                RewardText.of("tenet.reward.table", "Roll the dice table", "dice", 1, ""));
        assertEquals("Iron Ingot", RewardText.of("", "", "", 4, "minecraft:iron_ingot"));
    }
}
