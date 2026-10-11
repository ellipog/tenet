package dev.ellipog.tenet;

import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.CreativeModeTab;

import java.util.Set;

/**
 * Which creative tabs hold the quest book.
 *
 * <p>One set rather than one per loader, because the answer is Tenet's and the mechanism is
 * each loader's: Fabric's {@code ItemGroupEvents} and NeoForge's
 * {@code BuildCreativeModeTabContentsEvent} both read this. A future tab move edits one set,
 * not two entry points that have to agree.
 *
 * <p>Two tabs: tools-and-utilities first, because the book is a single-stack right-click
 * tool, and ingredients beside it, because that is where pack authors browse. The search tab
 * aggregates every tab's entries on its own, so it needs no entry here; operator-only tabs
 * need none either.
 *
 * <p>The keys are built by path rather than read off {@code CreativeModeTabs}: that class
 * only exposes its keys' locations, and spelling the two paths out here keeps this helper
 * independent of its field visibility. Equality is by registry and location, so these match
 * the vanilla tabs at runtime.
 */
public final class QuestBookTabs {

    private QuestBookTabs() {
    }

    /** {@code minecraft:tools_and_utilities} — the book is a right-click tool. */
    public static final ResourceKey<CreativeModeTab> TOOLS_AND_UTILITIES = key("tools_and_utilities");

    /** {@code minecraft:ingredients} — where pack authors browse. */
    public static final ResourceKey<CreativeModeTab> INGREDIENTS = key("ingredients");

    /** The tabs the quest book appears in. Read by both loaders' entry points. */
    public static final Set<ResourceKey<CreativeModeTab>> TABS = Set.of(TOOLS_AND_UTILITIES, INGREDIENTS);

    /**
     * Whether the book belongs in {@code tab}.
     *
     * <p>Null-safe: a caller holding no tab — or a tab key from a mod that moved — is simply
     * told no rather than throwing during tab population.
     */
    public static boolean shouldInclude(ResourceKey<CreativeModeTab> tab) {
        return tab != null && TABS.contains(tab);
    }

    private static ResourceKey<CreativeModeTab> key(String path) {
        return ResourceKey.create(Registries.CREATIVE_MODE_TAB, ResourceLocation.withDefaultNamespace(path));
    }
}
