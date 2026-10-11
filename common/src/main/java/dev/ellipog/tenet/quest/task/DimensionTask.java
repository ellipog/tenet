package dev.ellipog.tenet.quest.task;

import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;

import dev.ellipog.tenet.Tenet;
import dev.ellipog.tenet.quest.QuestTask;
import dev.ellipog.tenet.quest.TaskCommon;
import dev.ellipog.tenet.quest.TaskContext;

import net.minecraft.resources.ResourceLocation;

import java.util.Set;
import java.util.function.Function;

/**
 * Be in a dimension.
 *
 * <pre>{@code { "type": "tenet:dimension", "dimension": "minecraft:the_nether" } }</pre>
 *
 * <p>Check on the type's own slow cadence: a player does not change dimension between two ticks, and
 * Legacy packs poll this one every hundred ticks for the same reason.
 */
public record DimensionTask(TaskCommon common, ResourceLocation dimension) implements QuestTask {

    public static final ResourceLocation TYPE = ResourceLocation.fromNamespaceAndPath(Tenet.MOD_ID, "dimension");

    public static final Set<String> FIELDS = Set.of("dimension");

    public static final MapCodec<DimensionTask> MAP_CODEC = RecordCodecBuilder.mapCodec(instance -> instance.group(
            TaskCommon.mapCodec(100).forGetter(DimensionTask::common),
            ResourceLocation.CODEC.fieldOf("dimension").forGetter(DimensionTask::dimension)
    ).apply(instance, DimensionTask::new));

    @Override
    public ResourceLocation type() {
        return TYPE;
    }

    public static final TaskBehaviour<DimensionTask> BEHAVIOUR = new TaskBehaviour<>() {

        @Override
        public int required(DimensionTask task) {
            return 1;
        }

        @Override
        public int current(DimensionTask task, TaskContext context) {
            return context.player().level().dimension().location().equals(task.dimension()) ? 1 : 0;
        }

        @Override
        public boolean canSubmitByHand(DimensionTask task, boolean chapterDefault) {
            // There is nothing to confirm: being there is the whole task, and it completes on its own.
            return false;
        }
    };

    public static final Function<DimensionTask, TaskDisplay> DISPLAY = task ->
            TaskDisplay.ofTranslatableText("tenet.task.dimension",
                    "Visit " + task.dimension(), task.dimension().toString(), 1);
}
