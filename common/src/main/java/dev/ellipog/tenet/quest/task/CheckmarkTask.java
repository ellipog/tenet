package dev.ellipog.tenet.quest.task;

import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import dev.ellipog.tenet.Tenet;
import dev.ellipog.tenet.quest.QuestTask;
import dev.ellipog.tenet.quest.TaskCommon;
import dev.ellipog.tenet.quest.TaskContext;
import net.minecraft.resources.ResourceLocation;

import java.util.Set;

/**
 * A task the player completes by saying so — a button, and a promise.
 *
 * <pre>{@code { "type": "tenet:checkmark", "title": "Did you read the sign?" } }</pre>
 *
 * <p>The words live in the task's {@link TaskCommon#title() common title} rather than on this
 * record: every task type carries an author title now, and a checkmark's is simply the only one
 * that is ever drawn without an item beside it. The key is unchanged — {@code "title"} sat flat on
 * the task before the shared field existed — so every file written before the move reads the same
 * way. A checkmark that says nothing draws a bare button, which is what legacy packs show: a "?" would
 * invent a sentence the author never wrote, and the rows below read the same empty.
 *
 * <p>{@link #required} is one and {@link #current} is always zero, which is what makes it
 * manual-only: no amount of observing the world will ever satisfy it, so the engine's auto-submit
 * pass skips it and only a submit advances it. That falls out of the two numbers rather than needing
 * a special case in the engine, which is the point of the {@link TaskBehaviour} shape.
 */
public record CheckmarkTask(TaskCommon common) implements QuestTask {

    public static final ResourceLocation TYPE = ResourceLocation.fromNamespaceAndPath(Tenet.MOD_ID, "checkmark");

    public static final Set<String> FIELDS = Set.of();

    public static final MapCodec<CheckmarkTask> MAP_CODEC = RecordCodecBuilder.mapCodec(instance -> instance.group(
            TaskCommon.MAP_CODEC.forGetter(CheckmarkTask::common)
    ).apply(instance, CheckmarkTask::new));

    @Override
    public ResourceLocation type() {
        return TYPE;
    }

    public static final TaskBehaviour<CheckmarkTask> BEHAVIOUR = new TaskBehaviour<>() {

        @Override
        public int required(CheckmarkTask task) {
            return 1;
        }

        @Override
        public int current(CheckmarkTask task, TaskContext context) {
            // Nothing observable. The engine's auto-submit pass therefore never satisfies this, and
            // only an explicit submit does -- which is exactly what a checkmark is.
            return 0;
        }

        @Override
        public boolean canSubmitByHand(CheckmarkTask task, boolean chapterDefault) {
            return true;
        }
    };

    /**
     * The title as plain text — this task has no item to draw.
     *
     * <p>The author's own title when the task names one, and empty when it does not: the
     * translation <i>fallback</i> rather than the key alone, because a checkmark's title is
     * written by whoever wrote the quest file and is not necessarily translatable at all. Sending both
     * means the client can show the translation when there is one and the author's own words when
     * there is not — rather than a raw key, which is the single most common way a mod looks
     * unfinished.
     */
    public static final java.util.function.Function<CheckmarkTask, TaskDisplay> DISPLAY = task ->
            task.common().title()
                    .map(title -> title.fallback()
                            .map(fallback -> TaskDisplay.ofTranslatableText(title.value(), fallback,
                                    1))
                            .orElseGet(() -> TaskDisplay.ofText(title.value(), 1)))
                    .orElseGet(() -> TaskDisplay.ofText("", 1));
}
