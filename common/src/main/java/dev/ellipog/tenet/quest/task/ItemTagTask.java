package dev.ellipog.tenet.quest.task;

import com.mojang.serialization.Codec;
import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;

import dev.ellipog.tenet.Tenet;
import dev.ellipog.tenet.quest.QuestTask;
import dev.ellipog.tenet.quest.TagKeys;
import dev.ellipog.tenet.quest.TagLabels;
import dev.ellipog.tenet.quest.TaskCommon;
import dev.ellipog.tenet.quest.TaskContext;

import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.TagKey;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

import java.util.Optional;
import java.util.Set;
import java.util.function.Function;

/**
 * Have enough of any item in a tag.
 *
 * <pre>{@code { "type": "tenet:item_tag", "tag": "minecraft:logs", "count": 16 } }</pre>
 *
 * <p>A sibling of {@code tenet:item} rather than a field on it. The legacy format has no tag field of its
 * own — its tags arrive through filter adapters that store a filter stack in the item field — so
 * there is no file shape to match, and a separate type keeps the most-used task's {@code item}
 * required. The same consumption rules apply: presence checks by default, take the items when
 * {@code consumeItems} says so.
 */
public record ItemTagTask(TaskCommon common, Identifier tag, int count,
                          Optional<Boolean> consumeItems, boolean onlyFromCrafting,
                          boolean manualOnly) implements QuestTask {

    public static final Identifier TYPE = Identifier.fromNamespaceAndPath(Tenet.MOD_ID, "item_tag");

    public static final Set<String> FIELDS = Set.of("tag", "count", "consumeItems", "onlyFromCrafting",
            "manualOnly");

    public static final MapCodec<ItemTagTask> MAP_CODEC = RecordCodecBuilder.mapCodec(instance -> instance.group(
            TaskCommon.mapCodec(20).forGetter(ItemTagTask::common),
            Identifier.CODEC.fieldOf("tag").forGetter(ItemTagTask::tag),
            Codec.intRange(1, 6400).optionalFieldOf("count", 1).forGetter(ItemTagTask::count),
            Codec.BOOL.optionalFieldOf("consumeItems").forGetter(ItemTagTask::consumeItems),
            Codec.BOOL.optionalFieldOf("onlyFromCrafting", false).forGetter(ItemTagTask::onlyFromCrafting),
            Codec.BOOL.optionalFieldOf("manualOnly", false).forGetter(ItemTagTask::manualOnly)
    ).apply(instance, ItemTagTask::new));

    @Override
    public Identifier type() {
        return TYPE;
    }

    /** Whether this task takes the items, given what the chapter says when the task does not. */
    public boolean consumes(boolean chapterDefault) {
        return consumeItems.orElse(chapterDefault);
    }

    public static final TaskBehaviour<ItemTagTask> BEHAVIOUR = new TaskBehaviour<>() {

        @Override
        public int required(ItemTagTask task) {
            return task.count();
        }

        @Override
        public boolean readsInventory() {
            return true;
        }

        @Override
        public int current(ItemTagTask task, TaskContext context) {
            if (task.onlyFromCrafting()) {
                // The tag half of the item task's lifetime-stat rule: every member's crafted count
                // added up, so handing the stack away does not un-count it. Documented beside the
                // item task's own flag, with the same difference from the legacy format.
                int crafted = 0;
                for (var holder : net.minecraft.core.registries.BuiltInRegistries.ITEM
                        .getTagOrEmpty(TagKeys.of(Registries.ITEM, task.tag()))) {
                    crafted += context.player().getStats().getValue(
                            net.minecraft.stats.Stats.ITEM_CRAFTED.get(holder.value()));
                    if (crafted >= task.count()) {
                        break;
                    }
                }
                return Math.min(task.count(), crafted);
            }
            TagKey<Item> tag = TagKeys.of(Registries.ITEM, task.tag());
            return ItemCounting.countIn(context.player().getInventory(), tag, task.count());
        }

        @Override
        public boolean canSubmitByHand(ItemTagTask task, boolean chapterDefault) {
            // A manual-only task always has a button: the press is the only path that measures it.
            // Otherwise the chapter's default counts here too: a task that does not say whether it
            // consumes has no button without it, and a task with no button that still takes the
            // items on the tick is the one thing this must not be. See TaskBehaviour#waitsForSubmit.
            return task.manualOnly() || task.consumes(chapterDefault);
        }

        @Override
        public boolean waitsForSubmit(ItemTagTask task, boolean chapterDefault) {
            // Manual-only never records from the tick — the press is the measurement — so it always
            // waits, even when there is nothing to take.
            return task.manualOnly() || takesResources(task, chapterDefault)
                    && canSubmitByHand(task, chapterDefault);
        }

        @Override
        public boolean manualOnly(ItemTagTask task) {
            return task.manualOnly();
        }

        @Override
        public boolean takesResources(ItemTagTask task, boolean chapterDefault) {
            return task.consumes(chapterDefault);
        }

        @Override
        public int take(ItemTagTask task, ServerPlayer player, int count) {
            TagKey<Item> tag = TagKeys.of(Registries.ITEM, task.tag());
            int remaining = count;
            var inventory = player.getInventory();
            for (int slot = 0; slot < inventory.getContainerSize() && remaining > 0; slot++) {
                ItemStack stack = inventory.getItem(slot);
                if (stack.isEmpty() || !stack.is(tag)) {
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

    public static final Function<ItemTagTask, TaskDisplay> DISPLAY = task -> {
        // The tag is the subject, and the count rides the row's progress chip. The subject is the
        // humanized tag -- "Hand in Any Iron Ores", not "Hand in #Ores/iron" -- because item_tag has
        // no per-task title to prefer, and an id is the author's spelling rather than a player's
        // sentence. The raw id is not lost: it travels as the row's tagId, which the book's hover
        // prints and the viewer adapters build their tag ingredients from.
        String label = TagLabels.humanize(task.tag());
        return TaskDisplay.ofTranslatableText("tenet.task.item_tag", "Hand in " + label, label,
                task.count());
    };
}
