package dev.ellipog.tenet.client;

import dev.ellipog.tenet.quest.task.FilterParser;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * What a {@code tenet:filter} expression names, enumerated against this client's registry.
 *
 * <p>The server counts with the same predicate ({@code Expr.test}), so preview and progress
 * cannot disagree: both ask the expression, one about the player's pockets and one about the
 * registry. Evaluated client-side rather than synced because the answer is large (every
 * matching id) and derivable — the wire carries the expression, not its expansion.
 *
 * <p>Evaluated once per distinct expression and kept: registries do not change while a client
 * is connected, so a resolution can be made once like every other one in
 * {@code ClientQuestCache}. {@link #forget()} drops them when a new tree arrives.
 */
public final class FilterMatches {

    /** How many matches a preview lists. A {@code mod(*)} filter names thousands; nobody scrolls that. */
    public static final int MAX_LISTED = 200;

    /** What an expression names: the first {@link #MAX_LISTED} matches, and how many there are. */
    public record Matches(List<ItemStack> shown, int total, boolean truncated) {
        public boolean isEmpty() {
            return total == 0;
        }
    }

    private static final Matches NONE = new Matches(List.of(), 0, false);

    private static final Map<String, Matches> CACHE = new HashMap<>();

    private FilterMatches() {
    }

    /** Drops every cached enumeration, on tree sync. */
    public static void forget() {
        CACHE.clear();
    }

    /**
     * The matches for an expression, cached. An unparseable expression matches nothing — the
     * server refused the file long before the client ever saw it, so reaching here with one is
     * a version skew rather than author error, and an empty preview says so honestly.
     */
    public static Matches of(String expression) {
        if (expression == null || expression.isEmpty()) {
            return NONE;
        }
        Matches cached = CACHE.get(expression);
        if (cached != null) {
            return cached;
        }
        FilterParser.Expr parsed;
        try {
            parsed = FilterParser.parse(expression);
        }
        catch (FilterParser.FilterException e) {
            return NONE;
        }
        List<ItemStack> shown = new ArrayList<>();
        int total = 0;
        // The registry iterates its values directly now; holders come from wrapping, and the
        // stack helper covers the unbound case a test JVM always is.
        for (Item item : BuiltInRegistries.ITEM) {
            if (item == Items.AIR) {
                continue;
            }
            ItemStack stack = dev.ellipog.tenet.quest.ItemStacks.stackOf(item, 1);
            if (!parsed.test(stack)) {
                continue;
            }
            total++;
            if (shown.size() < MAX_LISTED) {
                shown.add(stack);
            }
        }
        Matches matches = new Matches(List.copyOf(shown), total, total > shown.size());
        CACHE.put(expression, matches);
        return matches;
    }
}
