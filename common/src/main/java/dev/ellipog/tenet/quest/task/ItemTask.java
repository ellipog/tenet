package dev.ellipog.tenet.quest.task;

import com.mojang.serialization.Codec;
import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import dev.ellipog.tenet.Tenet;
import dev.ellipog.tenet.quest.ItemRef;
import dev.ellipog.tenet.quest.QuestTask;
import dev.ellipog.tenet.quest.TaskCommon;
import dev.ellipog.tenet.quest.TaskContext;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.stats.Stats;
import net.minecraft.world.item.ItemStack;

import java.util.Optional;
import java.util.Set;

/**
 * Have enough of an item.
 *
 * <pre>{@code { "type": "tenet:item", "item": "minecraft:oak_log", "count": 8 } }</pre>
 *
 * <h2>{@code consumeItems}, and why it is optional</h2>
 *
 * <p>An item task can either take the items or merely require them. Both are wanted: a tutorial
 * asking you to gather eight logs should probably let you keep them, while a quest that asks for a
 * diamond and gives you a sword in return is a trade, and a trade takes the diamond.
 *
 * <p>It is an {@link Optional} so the chapter's {@code defaultConsumeItems} can fill it in — the
 * inheritance the plan borrowed from the legacy format. An author of a resource-hungry pack sets it once on
 * the chapter; an author who wants the friendlier behaviour sets nothing.
 */
public record ItemTask(TaskCommon common, ItemRef item, Optional<Boolean> consumeItems,
                       ComponentMatch match, boolean onlyFromCrafting, boolean manualOnly) implements QuestTask {

    public static final Identifier TYPE = Identifier.fromNamespaceAndPath(Tenet.MOD_ID, "item");

    /** This type's own fields, for the validator. {@code "type"} and the common fields are added by it. */
    public static final Set<String> FIELDS =
            Set.of("item", "count", "components", "consumeItems", "match", "onlyFromCrafting",
                    "manualOnly");

    public static final MapCodec<ItemTask> MAP_CODEC = RecordCodecBuilder.mapCodec(instance -> instance.group(
            // The common settings first, so every task in a file reads in the same order.
            TaskCommon.MAP_CODEC.forGetter(ItemTask::common),
            // A MapCodec, so "item" and "count" sit flat here rather than nested under "item".
            ItemRef.MAP_CODEC.forGetter(ItemTask::item),
            Codec.BOOL.optionalFieldOf("consumeItems").forGetter(ItemTask::consumeItems),
            // Strict is the default because it is what this type has always matched: a file that says
            // nothing must keep meaning what it meant before the field existed.
            ComponentMatch.CODEC.optionalFieldOf("match", ComponentMatch.STRICT).forGetter(ItemTask::match),
            Codec.BOOL.optionalFieldOf("onlyFromCrafting", false).forGetter(ItemTask::onlyFromCrafting),
            Codec.BOOL.optionalFieldOf("manualOnly", false).forGetter(ItemTask::manualOnly)
    ).apply(instance, ItemTask::new));

    @Override
    public Identifier type() {
        return TYPE;
    }

    /** Whether this task takes the items, given what the chapter says when the task does not. */
    public boolean consumes(boolean chapterDefault) {
        return consumeItems.orElse(chapterDefault);
    }

    /**
     * The behaviour, in one place.
     *
     * <p>Kept in the task's own file rather than in the registry, so that everything about
     * {@code tenet:item} is readable in one screen.
     */
    public static final TaskBehaviour<ItemTask> BEHAVIOUR = new TaskBehaviour<>() {

        @Override
        public int required(ItemTask task) {
            return task.item().count();
        }

        @Override
        public boolean readsInventory() {
            // Counted from inventories — or from a crafting statistic, which is cheaper, when the
            // task asks for that instead. Either way the pack's inventory floor applies: slowing
            // the poll of a stat-backed task costs nothing and keeps one rule for the type.
            return true;
        }

        @Override
        public int current(ItemTask task, TaskContext context) {
            // The shared template: counting reads it and never keeps it, so rebuilding it per
            // evaluation is one stack per member per task for an answer that cannot move.
            ItemStack template = task.item().template();
            if (template.isEmpty()) {
                return 0;
            }
            if (task.onlyFromCrafting()) {
                // Counted from the player's own statistics rather than from the inventory, which is
                // the closest this build gets to the legacy "only what you crafted": the stat is lifetime
                // and monotonic, so handing the stack away does not un-count it. Documented in the
                // schema as the difference.
                int crafted = context.player().getStats().getValue(Stats.ITEM_CRAFTED.get(template.getItem()));
                return Math.min(task.item().count(), crafted);
            }
            return ItemCounting.countIn(context.player().getInventory(), template, task.match(),
                    task.item().count());
        }

        @Override
        public boolean canSubmitByHand(ItemTask task, boolean chapterDefault) {
            // A manual-only task always has a button: the press is the only path that measures it.
            // Otherwise only worth a button when submitting actually does something. A presence-only
            // task completes by itself the moment the player has the items.
            //
            // The chapter's default counts, not just the task's own field: whether the buttons shows
            // has to be the same answer as whether the take happens, or the row promises nothing is
            // taken while the tick takes it.
            return task.manualOnly() || task.consumes(chapterDefault);
        }

        @Override
        public boolean waitsForSubmit(ItemTask task, boolean chapterDefault) {
            // Manual-only never records from the tick — the press is the measurement — so it always
            // waits, even when there is nothing to take.
            return task.manualOnly() || takesResources(task, chapterDefault)
                    && canSubmitByHand(task, chapterDefault);
        }

        @Override
        public boolean manualOnly(ItemTask task) {
            return task.manualOnly();
        }

        @Override
        public boolean takesResources(ItemTask task, boolean chapterDefault) {
            return task.consumes(chapterDefault);
        }

        @Override
        public int take(ItemTask task, ServerPlayer player, int count) {
            // The shared template: matching reads it, and the shrinking below touches the
            // inventory's stacks, never this one.
            ItemStack template = task.item().template();
            if (template.isEmpty()) {
                return 0;
            }
            // From the first matching slots, and a slot holding more than is needed is shrunk rather
            // than eaten whole -- taking three of five logs leaves two behind.
            int remaining = count;
            var inventory = player.getInventory();
            for (int slot = 0; slot < inventory.getContainerSize() && remaining > 0; slot++) {
                ItemStack stack = inventory.getItem(slot);
                if (!task.match().matches(template, stack)) {
                    continue;
                }
                int take = Math.min(remaining, stack.getCount());
                stack.shrink(take);
                remaining -= take;
                inventory.setItem(slot, stack.isEmpty() ? ItemStack.EMPTY : stack);
            }
            inventory.setChanged();
            return count - remaining;
        }
    };

    /**
     * The item and its count, with no label — the client draws the item's own name.
     *
     * <p>Not the name resolved here. The server does not know what language the client is in, and a
     * name baked into English on the wire would be wrong for every player not playing in it.
     */
    public static final java.util.function.Function<ItemTask, TaskDisplay> DISPLAY =
            task -> TaskDisplay.ofItem(task.item(), task.item().count());
}
