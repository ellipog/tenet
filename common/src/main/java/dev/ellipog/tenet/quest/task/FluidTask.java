package dev.ellipog.tenet.quest.task;

import com.mojang.serialization.Codec;
import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;

import dev.ellipog.tenet.Tenet;
import dev.ellipog.tenet.quest.QuestTask;
import dev.ellipog.tenet.quest.TaskCommon;
import dev.ellipog.tenet.quest.TaskContext;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.material.Fluid;

import java.util.Set;
import java.util.function.Function;

/**
 * Hand over fluid, measured in millibuckets, carried in the buckets the player holds.
 *
 * <pre>{@code { "type": "tenet:fluid", "fluid": "minecraft:water", "amount": 1000 } }</pre>
 *
 * <p>the legacy fluid task is filled at a task screen block; Tenet has no such block yet, so this
 * is the carried-container path: buckets count as a thousand millibuckets each, and anything else
 * that holds fluid — tanks, capsules, canisters — counts through the loader's fluid access, which
 * is also what a submit drains. A fluid with no bucket item at all still works where containers
 * carry it. The screen path arrives with the blocks in T11, and the file's shape already carries
 * everything it will need.
 */
public record FluidTask(TaskCommon common, ResourceLocation fluid, int amount, boolean manualOnly) implements QuestTask {

    public static final ResourceLocation TYPE = ResourceLocation.fromNamespaceAndPath(Tenet.MOD_ID, "fluid");

    public static final Set<String> FIELDS = Set.of("fluid", "amount", "manualOnly");

    /** One bucket is a thousand millibuckets, which is the unit the file and the row use. */
    public static final int BUCKET = 1000;

    public static final MapCodec<FluidTask> MAP_CODEC = RecordCodecBuilder.mapCodec(instance -> instance.group(
            TaskCommon.mapCodec(20).forGetter(FluidTask::common),
            ResourceLocation.CODEC.fieldOf("fluid").forGetter(FluidTask::fluid),
            Codec.intRange(1, 1_000_000).fieldOf("amount").forGetter(FluidTask::amount),
            Codec.BOOL.optionalFieldOf("manualOnly", false).forGetter(FluidTask::manualOnly)
    ).apply(instance, FluidTask::new));

    @Override
    public ResourceLocation type() {
        return TYPE;
    }

    /** The bucket item that carries this fluid, or air when the fluid has none. */
    public static Item bucketFor(ResourceLocation fluid) {
        Fluid found = BuiltInRegistries.FLUID.get(fluid);
        return found.getBucket();
    }

    public static final TaskBehaviour<FluidTask> BEHAVIOUR = new TaskBehaviour<>() {

        @Override
        public int required(FluidTask task) {
            return task.amount();
        }

        @Override
        public boolean readsInventory() {
            return true;
        }

        @Override
        public int current(FluidTask task, TaskContext context) {
            Item bucket = bucketFor(task.fluid());
            int carried = 0;
            var inventory = context.player().getInventory();
            if (bucket != Items.AIR) {
                for (int slot = 0; slot < inventory.getContainerSize(); slot++) {
                    ItemStack stack = inventory.getItem(slot);
                    if (stack.is(bucket)) {
                        carried += stack.getCount() * BUCKET;
                    }
                }
            }
            // Whatever the buckets did not cover, in containers — which is also the whole of it
            // for a fluid with no bucket item. The loader skips the bucket stacks themselves, so
            // the two halves cannot count one vessel twice.
            carried += dev.ellipog.tenet.inventory.FluidAccesses.current()
                    .storedOf(context.player(), task.fluid(), bucket);
            return Math.min(carried, task.amount());
        }

        @Override
        public boolean canSubmitByHand(FluidTask task, boolean chapterDefault) {
            return true;
        }

        @Override
        public boolean manualOnly(FluidTask task) {
            return task.manualOnly();
        }

        @Override
        public boolean takesResources(FluidTask task, boolean chapterDefault) {
            // Always: the fluid is what is being asked for, so handing it over is the task.
            return true;
        }

        @Override
        public int take(FluidTask task, ServerPlayer player, int count) {
            Item bucket = bucketFor(task.fluid());
            int remaining = count;
            var inventory = player.getInventory();
            if (bucket != Items.AIR) {
                int buckets = 0;
                for (int slot = 0; slot < inventory.getContainerSize() && remaining >= BUCKET; slot++) {
                    ItemStack stack = inventory.getItem(slot);
                    if (!stack.is(bucket)) {
                        continue;
                    }
                    while (remaining >= BUCKET && !stack.isEmpty()) {
                        stack.shrink(1);
                        remaining -= BUCKET;
                        buckets++;
                    }
                    inventory.setItem(slot, stack.isEmpty() ? ItemStack.EMPTY : stack);
                }
                inventory.setChanged();
                // Handed back after the slots are cleared, not as each bucket empties: placing an
                // empty into a slot still holding its own dead stack reads as free space, and the
                // clearing write that follows would erase what was just handed back.
                for (int i = 0; i < buckets; i++) {
                    // The empty bucket comes straight back, as it does out of a crafting table. One
                    // that no longer fits is dropped rather than discarded: `add` mutates its
                    // argument down to the remainder, which is what goes over the side.
                    ItemStack empty = new ItemStack(Items.BUCKET);
                    inventory.add(empty);
                    if (!empty.isEmpty()) {
                        player.drop(empty, false);
                    }
                }
            }
            // Buckets first, containers for the rest: the press this answers measured both in the
            // same order, so what it counted is what this drains. Emptied containers land back in
            // their own slots through the loader, so there is no overflow to place or drop.
            if (remaining > 0) {
                int drained = dev.ellipog.tenet.inventory.FluidAccesses.current()
                        .drainFrom(player, task.fluid(), remaining, bucket);
                remaining -= drained;
            }
            return count - remaining;
        }
    };

    public static final Function<FluidTask, TaskDisplay> DISPLAY = task -> {
        // The amount and the fluid in one phrase, so the client can prettify the id inside it: the row
        // reads "Hand in 1000 mB of Water" rather than naming a bucket count and an id.
        String what = task.amount() + " mB of " + task.fluid();
        // The count in millibuckets, like the progress it is drawn against: every row, bar and
        // done-check in the client divides progress by this number, so a bucket count here would
        // read a half-held thousand as "2 / 2" complete. Tanks count millibuckets, buckets count a
        // thousand of them each, and the row speaks the same unit for both.
        return TaskDisplay.ofTranslatableText("tenet.task.fluid", "Hand in " + what, what,
                task.amount());
    };
}
