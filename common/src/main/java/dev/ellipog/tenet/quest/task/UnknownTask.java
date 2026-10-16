package dev.ellipog.tenet.quest.task;

import dev.ellipog.tenet.quest.QuestTask;
import dev.ellipog.tenet.quest.TaskCommon;
import dev.ellipog.tenet.quest.TaskContext;

import net.minecraft.resources.Identifier;

/**
 * A task whose type this build has no codec for, kept rather than thrown away.
 *
 * <pre>{@code { "type": "some_addon:reticulate", "splines": 4 } }</pre>
 *
 * <h2>What it is for</h2>
 *
 * <p>One node in a file naming a task type that is not registered here: an addon that is not installed,
 * a type an addon registered on the server but not on the client, or a file written by a newer build
 * than this one. Before this existed the dispatch refused to decode the type at all, and because a codec
 * error refuses the <b>whole document</b>, one such node cost the author every quest in the file.
 *
 * <p>So the id survives, the file loads, and the node is drawn as what it is. The payload's own fields
 * are not carried here — a {@link com.mojang.serialization.MapCodec} never sees the raw object — but
 * they are not lost either: the editor writes back the tree it read, so anything this build does not
 * understand is still on disk after a save. See {@code TypeDispatch} for the whole argument.
 *
 * <h2>What it does, and why that is the safe direction</h2>
 *
 * <p>Nothing, deliberately, and {@link #BEHAVIOUR} says so in numbers rather than by omission:
 * {@code required} is 1 and {@code current} is always 0, so the task can never be satisfied and the
 * quest sits visibly incomplete instead of silently completing itself. That is the same reading
 * {@code ProgressionEngine} already gave an unregistered behaviour before this class existed, so no
 * quest's progression changes; what changes is that the file is readable.
 *
 * <p>Completing it instead would be the dangerous direction — a quest whose requirements this build
 * cannot evaluate would hand out its rewards for nothing.
 *
 * <h2>It is never registered</h2>
 *
 * <p>There is no {@code register} call for this type and there must not be: {@link #type()} returns the
 * id that was in the file, which is precisely the id the registry does <b>not</b> have. Registering it
 * would need one id, and there is one of these per unknown id.
 */
public record UnknownTask(TaskCommon common, Identifier type) implements QuestTask {

    /**
     * The shape a decoded unknown task takes: no settings of its own, since the file's own
     * {@code optional} and {@code autoSubmitTicks} cannot be read without the type's codec.
     *
     * <p>{@link TaskCommon#DEFAULT} rather than null, which is what makes {@code optional()} answer
     * false and every other accessor on this record safe to call.
     */
    public static UnknownTask of(Identifier type) {
        return new UnknownTask(TaskCommon.DEFAULT, type);
    }

    /**
     * Never satisfied, never submittable, and never taking anything.
     *
     * <p>Forwarded to the engine through {@code TaskTypes.behaviourOf}, so a caller cannot reach the
     * {@code orElse} fallback by accident and get a different answer from the one written here.
     */
    public static final TaskBehaviour<UnknownTask> BEHAVIOUR = new TaskBehaviour<>() {

        /**
         * One, so the row reads "0 / 1" — "not done" rather than a number the player cannot reach.
         *
         * <p>Not a large number: {@code TaskBehaviour.required}'s javadoc allows an unreachable value for
         * a task that cannot be met, and this one is unreachable through {@link #current} returning zero
         * for every context. A huge count would only be drawn.
         */
        @Override
        public int required(UnknownTask task) {
            return 1;
        }

        /** Zero always: there is no behaviour to ask, and the mod that would answer is not loaded. */
        @Override
        public int current(UnknownTask task, TaskContext context) {
            return 0;
        }

        /**
         * False, because a Submit button on a task nothing can evaluate is a control that does nothing —
         * the fault {@code TaskBehaviour.canSubmitByHand} names.
         */
        @Override
        public boolean canSubmitByHand(UnknownTask task, boolean chapterDefault) {
            return false;
        }
    };
}
