package dev.ellipog.tenet.quest.task;

import com.mojang.serialization.Codec;
import com.mojang.serialization.DataResult;
import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import dev.ellipog.tenet.Tenet;
import dev.ellipog.tenet.quest.QuestTask;
import dev.ellipog.tenet.quest.TaskCommon;
import dev.ellipog.tenet.quest.TaskContext;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;

import java.util.Optional;
import java.util.Set;
import java.util.function.Function;

/**
 * Hand in items matching a filter expression.
 *
 * <pre>{@code { "type": "tenet:filter", "filter": "or(item(minecraft:coal)item_tag(minecraft:coals))", "count": 4 } }</pre>
 *
 * <p>The legacy format has no tag field of its own: tags — and the {@code mod}, {@code and}, {@code or}
 * and {@code not} combinators — arrive through filter stacks, an {@code ftbfiltersystem:smart_filter}
 * item carrying the expression in its {@code ftbfiltersystem:filter} component. The migration tool
 * passes that string through verbatim into {@code filter}; see {@link FilterParser} for the grammar
 * and for which functions a "hand in N" task can answer.
 *
 * <p>Counting, taking and the chapter's consume-items default work exactly as for
 * {@code tenet:item_tag}: any carried stack the expression matches counts, and handing the task in
 * takes the matching stacks.
 */
public record FilterTask(TaskCommon common, FilterParser.Expr filter, int count,
                         Optional<Boolean> consumeItems, boolean manualOnly) implements QuestTask {

    public static final ResourceLocation TYPE = ResourceLocation.fromNamespaceAndPath(Tenet.MOD_ID, "filter");

    public static final Set<String> FIELDS = Set.of("filter", "count", "consumeItems", "manualOnly");

    /** The expression as the file spells it: parsed on read, printed back on write. */
    public static final Codec<FilterParser.Expr> EXPR_CODEC = Codec.STRING.flatXmap(
            raw -> {
                try {
                    return DataResult.success(FilterParser.parse(raw));
                }
                catch (FilterParser.FilterException e) {
                    return DataResult.error(e::getMessage);
                }
            },
            expr -> DataResult.success(expr.toString()));

    public static final MapCodec<FilterTask> MAP_CODEC = RecordCodecBuilder.mapCodec(instance -> instance.group(
            TaskCommon.mapCodec(20).forGetter(FilterTask::common),
            EXPR_CODEC.fieldOf("filter").forGetter(FilterTask::filter),
            Codec.intRange(1, 6400).optionalFieldOf("count", 1).forGetter(FilterTask::count),
            Codec.BOOL.optionalFieldOf("consumeItems").forGetter(FilterTask::consumeItems),
            Codec.BOOL.optionalFieldOf("manualOnly", false).forGetter(FilterTask::manualOnly)
    ).apply(instance, FilterTask::new));

    @Override
    public ResourceLocation type() {
        return TYPE;
    }

    /** Whether this task takes the items, given what the chapter says when the task does not. */
    public boolean consumes(boolean chapterDefault) {
        return consumeItems.orElse(chapterDefault);
    }

    public static final TaskBehaviour<FilterTask> BEHAVIOUR = new TaskBehaviour<>() {

        @Override
        public int required(FilterTask task) {
            return task.count();
        }

        @Override
        public boolean readsInventory() {
            return true;
        }

        @Override
        public int current(FilterTask task, TaskContext context) {
            int found = 0;
            var inventory = context.player().getInventory();
            for (int slot = 0; slot < inventory.getContainerSize() && found < task.count(); slot++) {
                ItemStack stack = inventory.getItem(slot);
                if (!stack.isEmpty() && task.filter().test(stack)) {
                    found += stack.getCount();
                }
            }
            return Math.min(found, task.count());
        }

        @Override
        public boolean canSubmitByHand(FilterTask task, boolean chapterDefault) {
            // A manual-only task always has a button: the press is the only path that measures it.
            // Otherwise the chapter's default counts here too, for the reason ItemTagTask gives.
            return task.manualOnly() || task.consumes(chapterDefault);
        }

        @Override
        public boolean waitsForSubmit(FilterTask task, boolean chapterDefault) {
            // Manual-only never records from the tick — the press is the measurement — so it always
            // waits, even when there is nothing to take.
            return task.manualOnly() || takesResources(task, chapterDefault)
                    && canSubmitByHand(task, chapterDefault);
        }

        @Override
        public boolean takesResources(FilterTask task, boolean chapterDefault) {
            return task.consumes(chapterDefault);
        }

        @Override
        public int take(FilterTask task, ServerPlayer player, int count) {
            int remaining = count;
            var inventory = player.getInventory();
            for (int slot = 0; slot < inventory.getContainerSize() && remaining > 0; slot++) {
                ItemStack stack = inventory.getItem(slot);
                if (stack.isEmpty() || !task.filter().test(stack)) {
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

        @Override
        public boolean manualOnly(FilterTask task) {
            return task.manualOnly();
        }
    };

    public static final Function<FilterTask, TaskDisplay> DISPLAY = task ->
            TaskDisplay.ofTranslatableText("tenet.task.filter", "Hand in " + task.filter().summary(),
                    task.filter().summary(), task.count());
}
