package dev.ellipog.tenet.client;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * The task row's tag slot: who wins it, and what a cooling row says in it.
 *
 * <p>One slot and four claimants, so the order is the whole rule: a lock explains itself rather
 * than the hand-in it hides, and a countdown shows only when nothing else has anything to say.
 * Asserted here rather than in game because the rule is pure text over four inputs — and because
 * a row that drew the wrong tag would still pass every suite that only counts rows.
 */
@DisplayName("the task row's tag slot")
class RowTagTest {

    @Test
    @DisplayName("locked wins the slot, whatever else is true")
    void lockedWinsTheSlot() {
        assertEquals("locked", QuestBookScreen.tagText(true, false, false, 0));
        assertEquals("locked", QuestBookScreen.tagText(true, true, true, 1650),
                "a cooling hand-in behind a lock still says locked");
    }

    @Test
    @DisplayName("the hand-in beats the optional flag")
    void handInBeatsOptional() {
        assertEquals("hand in", QuestBookScreen.tagText(false, true, true, 0));
        assertEquals("optional", QuestBookScreen.tagText(false, false, true, 0));
    }

    @Test
    @DisplayName("a cooling row with an empty slot counts down in whole seconds")
    void coolingCountsDownInWholeSeconds() {
        assertEquals("ready in 83s", QuestBookScreen.tagText(false, false, false, 1650));
        assertEquals("ready in 1s", QuestBookScreen.tagText(false, false, false, 1),
                "a single tick left is still a second to wait");
        assertEquals("ready in 1s", QuestBookScreen.tagText(false, false, false, 20));
        assertEquals("ready in 2s", QuestBookScreen.tagText(false, false, false, 21),
                "ceiling, so the text reaches zero exactly when the hand-in comes back");
    }

    @Test
    @DisplayName("anything else leaves the slot empty")
    void anythingElseLeavesTheSlotEmpty() {
        assertNull(QuestBookScreen.tagText(false, false, false, 0));
        assertNull(QuestBookScreen.tagText(false, false, false, -5),
                "a lapsed countdown is nothing to say, not a zero-second one");
    }
}
