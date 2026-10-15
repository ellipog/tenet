package dev.ellipog.tenet;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.CreativeModeTab;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Which creative tabs hold the quest book.
 *
 * <p>Pure tab keys — no player, no registry contents, no loader — so this runs headless. The
 * loader event subscription itself cannot run here (it needs Fabric/NeoForge runtimes) and is
 * covered by in-game acceptance instead: the book in Tools &amp; Utilities and Ingredients
 * on both loaders, searchable, no duplicates.
 */
@DisplayName("the quest book creative tabs")
class QuestBookTabsTest {

    @Test
    @DisplayName("the book is in tools and ingredients")
    void bookIsInToolsAndIngredients() {
        assertTrue(QuestBookTabs.shouldInclude(QuestBookTabs.TOOLS_AND_UTILITIES),
                "a right-click tool belongs with the tools");
        assertTrue(QuestBookTabs.shouldInclude(QuestBookTabs.INGREDIENTS),
                "pack authors browse ingredients for gating");
    }

    @Test
    @DisplayName("the keys name the vanilla tabs")
    void keysNameTheVanillaTabs() {
        assertEquals(vanillaKey("tools_and_utilities"), QuestBookTabs.TOOLS_AND_UTILITIES,
                "tools key matches the vanilla tab by location");
        assertEquals(vanillaKey("ingredients"), QuestBookTabs.INGREDIENTS,
                "ingredients key matches the vanilla tab by location");
    }

    @Test
    @DisplayName("the book is nowhere else")
    void bookIsNowhereElse() {
        assertFalse(QuestBookTabs.shouldInclude(vanillaKey("combat")),
                "not a weapon");
        assertFalse(QuestBookTabs.shouldInclude(vanillaKey("building_blocks")),
                "not a block");
        assertFalse(QuestBookTabs.shouldInclude(vanillaKey("search")),
                "search aggregates on its own");
        assertFalse(QuestBookTabs.shouldInclude(vanillaKey("spawn_eggs")),
                "not an egg");
        assertFalse(QuestBookTabs.shouldInclude(vanillaKey("food_and_drinks")),
                "not food");
    }

    @Test
    @DisplayName("a missing tab is simply told no")
    void missingTabIsNo() {
        assertFalse(QuestBookTabs.shouldInclude(null),
                "tab population must never throw on a tab it does not know");
    }

    @Test
    @DisplayName("the set and the predicate agree")
    void setAndPredicateAgree() {
        for (ResourceKey<CreativeModeTab> tab : QuestBookTabs.TABS) {
            assertTrue(QuestBookTabs.shouldInclude(tab),
                    "every listed tab is included: " + tab.identifier());
        }
    }

    private static ResourceKey<CreativeModeTab> vanillaKey(String path) {
        return ResourceKey.create(Registries.CREATIVE_MODE_TAB, Identifier.withDefaultNamespace(path));
    }
}
