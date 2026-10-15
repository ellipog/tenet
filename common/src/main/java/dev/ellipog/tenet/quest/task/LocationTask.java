package dev.ellipog.tenet.quest.task;

import com.mojang.serialization.Codec;
import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;

import dev.ellipog.tenet.Tenet;
import dev.ellipog.tenet.quest.QuestTask;
import dev.ellipog.tenet.quest.TaskCommon;
import dev.ellipog.tenet.quest.TaskContext;

import net.minecraft.resources.Identifier;

import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;

/**
 * Stand in a box, in a dimension.
 *
 * <pre>{@code { "type": "tenet:location", "position": [100, 64, -20], "size": [8, 8, 8] } }</pre>
 *
 * <p>The box is inclusive at its minimum corner and exclusive at its maximum, as the legacy is, so
 * a size of one is exactly one block and two adjacent boxes cannot both claim the same block. The
 * dimension is optional and can be ignored, for a box that is meaningful in any world.
 */
public record LocationTask(TaskCommon common, Optional<Identifier> dimension, boolean ignoreDimension,
                           List<Integer> position, List<Integer> size) implements QuestTask {

    public static final Identifier TYPE = Identifier.fromNamespaceAndPath(Tenet.MOD_ID, "location");

    public static final Set<String> FIELDS =
            Set.of("dimension", "ignoreDimension", "position", "size");

    /** Three numbers: a corner or a side length, nothing shorter and nothing longer. */
    public static final Codec<List<Integer>> TRIPLE = Codec.INT.listOf(3, 3);

    public static final MapCodec<LocationTask> MAP_CODEC = RecordCodecBuilder.mapCodec(instance -> instance.group(
            TaskCommon.mapCodec(3).forGetter(LocationTask::common),
            Identifier.CODEC.optionalFieldOf("dimension").forGetter(LocationTask::dimension),
            Codec.BOOL.optionalFieldOf("ignoreDimension", false).forGetter(LocationTask::ignoreDimension),
            TRIPLE.fieldOf("position").forGetter(LocationTask::position),
            TRIPLE.optionalFieldOf("size", List.of(1, 1, 1)).forGetter(LocationTask::size)
    ).apply(instance, LocationTask::new));

    @Override
    public Identifier type() {
        return TYPE;
    }

    public int x() {
        return position.get(0);
    }

    public int y() {
        return position.get(1);
    }

    public int z() {
        return position.get(2);
    }

    /** Whether a point is inside: minimum inclusive, maximum exclusive. */
    public boolean contains(int bx, int by, int bz) {
        return bx >= x() && bx < x() + size.get(0)
                && by >= y() && by < y() + size.get(1)
                && bz >= z() && bz < z() + size.get(2);
    }

    public static final TaskBehaviour<LocationTask> BEHAVIOUR = new TaskBehaviour<>() {

        @Override
        public int required(LocationTask task) {
            return 1;
        }

        @Override
        public int current(LocationTask task, TaskContext context) {
            if (!task.ignoreDimension() && task.dimension().isPresent()
                    && !context.player().level().dimension().identifier().equals(task.dimension().get())) {
                return 0;
            }
            var pos = context.player().blockPosition();
            return task.contains(pos.getX(), pos.getY(), pos.getZ()) ? 1 : 0;
        }

        @Override
        public boolean canSubmitByHand(LocationTask task, boolean chapterDefault) {
            return false;
        }
    };

    public static final Function<LocationTask, TaskDisplay> DISPLAY = task -> {
        // The box, in words, as the sentence's subject: a location is not an id, so there is nothing
        // for the client to prettify and the fallback is the whole sentence.
        String where = "the box at " + task.x() + ", " + task.y() + ", " + task.z();
        return TaskDisplay.ofTranslatableText("tenet.task.location", "Stand in " + where, where, 1);
    };
}
