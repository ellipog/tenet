package dev.ellipog.tenet.quest.loot;

import dev.ellipog.tenet.quest.QuestReward;
import dev.ellipog.tenet.quest.reward.ItemReward;
import dev.ellipog.tenet.quest.reward.TableReward;

import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Container;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;

/**
 * A table's items, as stacks, and where they go.
 *
 * <h2>What an export is for</h2>
 *
 * <p>Batch editing: an author wants the table's items in front of them, in a chest, to rearrange and
 * compare. So an entry's <b>configured count</b> is what travels — {@code torches x16} is a stack of
 * sixteen, {@code iron x192} is three stacks of sixty-four — because a chest that said "one iron" for an
 * entry that grants a hundred and ninety-two would be a working set that lies about the table.
 *
 * <h2>Nothing is destroyed, and the report says where everything went</h2>
 *
 * <p>The container gets <b>empty slots only</b>: a chest with sixteen iron in it already is not part of
 * the working set, and merging into it would mix an author's pre-existing items into the export. Then
 * the player's own inventory, which is an ordinary insertion and where merging is what inventories do.
 * Then the ground, at the player's feet. Each non-zero count is reported, in that order, so an overflow
 * is something the author is told about rather than something they find later.
 *
 * <h2>Nesting is a view, not a round trip</h2>
 *
 * <p>By default only the table's own item entries are exported, and the rest are counted and named as
 * skipped. Resolving nested tables is available because "everything this table can produce" is a real
 * question — but the flattened chest cannot be imported back into the same table without turning its
 * modular sub-tables into a flat list of entries, so the command that asks for it says so.
 */
public final class TableExport {

    private TableExport() {
    }

    /**
     * What an export found: the stacks, and the two things that make the chest an incomplete picture.
     *
     * <h2>Why one walk returns all three</h2>
     *
     * <p>Because they are three answers about one traversal. This used to be {@code stacks(...)} and a
     * separate {@code skipped(...)}, which walked the table a second time and counted only the top
     * level — so with {@code nested} the stacks descended into child tables while the count did not,
     * and a report could say "2 skipped" about a table that had skipped nine. The separate method was
     * also never called by anything, so the sentence it existed for (see {@link Result#skipped}) never
     * printed at all: the count was hardcoded to zero at the one place that read it.
     *
     * @param stacks     the items, as stacks a chest can hold
     * @param skipped    entries that are not items this walk could put in a chest — a non-item reward,
     *                   a table reference it was not asked to open, a choice, a missing table, a loop,
     *                   or one cut off at {@link RewardTable#MAX_NESTING}
     * @param randomised entries exported at their <b>minimum</b>: they carry a {@code randomBonus}, so
     *                   the roll that grants them may hand over more than the chest shows
     */
    public record Export(List<ItemStack> stacks, int skipped, int randomised) {

        public Export {
            stacks = List.copyOf(stacks);
        }
    }

    /** What an export did, in the three places things can end up, and what it could not carry. */
    public record Result(int given, int toContainer, int toInventory, int dropped, int skipped,
                         int randomised) {
    }

    /**
     * The table's items, as stacks a chest can hold, with what could not become one.
     *
     * @param nested whether to resolve nested tables as well, or only the table's own item entries
     * @param tables where a named reference resolves
     */
    public static Export stacks(RewardTable table, boolean nested,
                                Function<String, RewardTable> tables) {
        Found found = new Found();
        collect(table, nested, tables, found, new LinkedHashSet<>(), 0);
        return new Export(found.stacks, found.skipped, found.randomised);
    }

    /** The running totals, so one traversal answers every question the caller asks about it. */
    private static final class Found {

        private final List<ItemStack> stacks = new ArrayList<>();
        private int skipped;
        private int randomised;
    }

    private static void collect(RewardTable table, boolean nested, Function<String, RewardTable> tables,
                                Found found, Set<String> seen, int depth) {
        for (RewardTable.Entry entry : table.entries()) {
            QuestReward reward = entry.reward();
            if (reward instanceof ItemReward item) {
                addStacks(found, item);
                continue;
            }
            if (!(nested && reward instanceof TableReward reference)) {
                // Not an item, and not a table this walk was asked to open. Nothing here can go in the
                // chest, and counting it is the whole point: an author exporting a table of experience
                // and commands is told the chest is emptier than the table rather than left to notice.
                found.skipped++;
                continue;
            }
            if (TableReward.isChoice(reference) || depth > RewardTable.MAX_NESTING) {
                // A choice offers rather than grants, and past the depth limit every walk gives up --
                // the loader reports both where they are written, and an export that hung or invented
                // entries would be worse than one that counts them as skipped.
                found.skipped++;
                continue;
            }
            Optional<RewardTable> child = reference.resolvedTable(tables);
            if (child.isEmpty()) {
                found.skipped++;
                continue;
            }
            String key = reference.tableId().orElse("");
            if (!key.isEmpty() && !seen.add(key)) {
                // A table that reaches itself: the loader reports the loop, and this must not walk
                // it -- an export that hangs is worse than one that is incomplete.
                found.skipped++;
                continue;
            }
            collect(child.get(), true, tables, found, seen, depth + 1);
        }
    }

    /**
     * One entry's count, split into stacks the game can hold.
     *
     * <p>The <b>configured</b> count, and a {@code randomBonus} is counted rather than added: the
     * chest is a working set an author rearranges and reads back with an import, and a chest holding
     * the maximum would import as a table that grants more than the one it came from. The entry is
     * reported as randomised instead, which is the honest half of the same fact.
     */
    private static void addStacks(Found found, ItemReward reward) {
        ItemStack whole = reward.item().toStack();
        if (whole.isEmpty()) {
            // An item this build cannot resolve. The validator reports it at load time; there is
            // nothing to put in a chest, and calling it "not an item" would be a different lie.
            return;
        }
        if (reward.randomBonus() > 0) {
            found.randomised++;
        }
        int remaining = whole.getCount();
        int most = Math.max(1, whole.getMaxStackSize());
        while (remaining > 0) {
            ItemStack one = whole.copyWithCount(Math.min(remaining, most));
            found.stacks.add(one);
            remaining -= one.getCount();
        }
    }

    /**
     * Puts the stacks in the container's empty slots, then the player's inventory, then the ground.
     *
     * <p>Takes the whole {@link Export} rather than its list, so the counts that describe what the
     * chest does <b>not</b> hold travel with the stacks they describe — the alternative is a caller
     * that has the list and not the numbers, which is how the skipped count came to be hardcoded to
     * zero here while the sentence that printed it waited for a non-zero value.
     *
     * @param container the container to fill, or null for {@code here}
     */
    public static Result give(Export export, Container container, ServerPlayer player) {
        int toContainer = 0;
        int toInventory = 0;
        int dropped = 0;
        for (ItemStack stack : export.stacks()) {
            if (container != null && !stack.isEmpty()) {
                int slot = emptySlot(container);
                if (slot >= 0) {
                    container.setItem(slot, stack.copy());
                    toContainer++;
                    continue;
                }
            }
            if (player.getInventory().add(stack.copy())) {
                toInventory++;
                continue;
            }
            player.drop(stack.copy(), false, net.minecraft.util.Prediction.SERVER_ONLY);
            dropped++;
        }
        if (container != null) {
            container.setChanged();
        }
        return new Result(export.stacks().size(), toContainer, toInventory, dropped, export.skipped(),
                export.randomised());
    }

    /** The first empty slot, or -1 when there is none. Never overwrites what is there. */
    private static int emptySlot(Container container) {
        for (int slot = 0; slot < container.getContainerSize(); slot++) {
            if (container.getItem(slot).isEmpty()) {
                return slot;
            }
        }
        return -1;
    }
}
