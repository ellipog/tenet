package dev.ellipog.tenet.quest.task;

import com.mojang.serialization.Codec;
import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;

import dev.ellipog.tenet.Tenet;
import dev.ellipog.tenet.Constants;
import dev.ellipog.tenet.quest.QuestTask;
import dev.ellipog.tenet.quest.TaskCommon;
import dev.ellipog.tenet.quest.TaskContext;

import net.minecraft.resources.ResourceLocation;

import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;

/**
 * A task whose progress is somebody else's code.
 *
 * <pre>{@code { "type": "tenet:custom", "id": "my_pack:unlock_the_vault", "value": 1 } }</pre>
 *
 * <p>The hook a pack reaches for when no task type anticipated what it wants to ask. A mod registers a
 * handler through {@link CustomTasks#register} during its construction, or a script configures the same
 * registry through the KubeJS integration; the handler is asked how far along the player is, and the quest
 * engine treats the answer exactly as it treats a stat's or a kill count's.
 *
 * <p>Mirrors {@link dev.ellipog.tenet.quest.reward.CustomReward} deliberately -- same id-keyed registry,
 * same "a script configures the same map" -- so there is one shape to learn for both halves of a quest.
 *
 * <h2>Why the id is required</h2>
 *
 * <p>The handler is what measures, so an id-less custom task has nothing to ask and could never complete.
 * That is a property of the engine rather than a choice here: progress is only ever what a behaviour
 * reports -- there is no path by which code or a command says "task 2 of this quest is now done" -- so a
 * custom task is a <i>measured</i> task or it is nothing. A code path that advances a task from outside is
 * what an anonymous one would need, and that belongs with the scripting work rather than with this type.
 */
public record CustomTask(TaskCommon common, String id, int value) implements QuestTask {

    public static final ResourceLocation TYPE = ResourceLocation.fromNamespaceAndPath(Tenet.MOD_ID, "custom");

    public static final Set<String> FIELDS = Set.of("id", "value");

    public static final MapCodec<CustomTask> MAP_CODEC = RecordCodecBuilder.mapCodec(instance -> instance.group(
            TaskCommon.mapCodec(20).forGetter(CustomTask::common),
            Codec.STRING.fieldOf("id").forGetter(CustomTask::id),
            Codec.intRange(1, 1_000_000_000).fieldOf("value").forGetter(CustomTask::value)
    ).apply(instance, CustomTask::new));

    @Override
    public ResourceLocation type() {
        return TYPE;
    }

    public static final TaskBehaviour<CustomTask> BEHAVIOUR = new TaskBehaviour<>() {

        /** The number the author asked for; what it counts is the handler's business. */
        @Override
        public int required(CustomTask task) {
            return task.value();
        }

        /**
         * What the handler says, or zero when nothing provides it -- or when providing it throws.
         *
         * <p>Zero rather than an error, and no log line -- deliberately unlike {@code CustomReward}, which
         * warns once when its handler is missing. A reward grants once and a log line is read once; a task is
         * polled every twenty ticks, and a warning per poll is a log nobody can read. The runtime signal is
         * the row itself, showing no progress, and the author's signal is the validator -- which warns at
         * the file's own line when this build has no handler for the id.
         *
         * <p>A handler that <i>throws</i> is caught and read as zero, with one warning per id: the alternative
         * is a script error crashing the server tick that polled it, which is what happened the first time a
         * script called a record accessor as a property. The warning names the id so the author knows which
         * script to open; the task stays inert until it is fixed.
         */
        @Override
        public int current(CustomTask task, TaskContext context) {
            return CustomTasks.handler(task.id())
                    .map(handler -> {
                        try {
                            return handler.current(task, context);
                        }
                        catch (RuntimeException | Error thrown) {
                            CustomTasks.warnOnce(task.id(), thrown);
                            return 0;
                        }
                    })
                    .orElse(0);
        }

        /**
         * Only if the handler says so: a custom task is measured, and a handler that wants a button asks for
         * one -- {@code canSubmitByHand} is the question the reader's Submit control is built from.
         *
         * <p>Guarded like {@link #current}: this runs on UI paths, and a throwing handler must cost a
         * missing button rather than a render crash.
         */
        @Override
        public boolean canSubmitByHand(CustomTask task, boolean chapterDefault) {
            return CustomTasks.handler(task.id())
                    .map(handler -> {
                        try {
                            return handler.canSubmitByHand(task);
                        }
                        catch (RuntimeException | Error thrown) {
                            CustomTasks.warnOnce(task.id(), thrown);
                            return false;
                        }
                    })
                    .orElse(false);
        }

        /**
         * Never, and that is a limit worth stating rather than a default left unsaid: handing a task in
         * takes resources the engine can <i>name</i> -- so many of an item, so much of a fluid -- and a
         * custom task names nothing. A handler that wants its task to cost something pairs it with an item
         * task, where the cost can be seen by the player before they press anything.
         */
        @Override
        public boolean takesResources(CustomTask task, boolean chapterDefault) {
            return false;
        }
    };

    public static final Function<CustomTask, TaskDisplay> DISPLAY = task ->
            TaskDisplay.ofTranslatableText("tenet.task.custom", "Custom task: " + task.id(), task.id(),
                    task.value());

    /** The id-keyed handlers a custom task resolves against. */
    public static final class CustomTasks {

        private CustomTasks() {
        }

        /**
         * What a custom task measures.
         *
         * <p>One method, so a lambda is a handler: the number is how far along the player is, counted the
         * way the task's own {@code value} counts.
         */
        @FunctionalInterface
        public interface Handler {

            /** How far along the player is, or zero -- which the engine reads as "not started". */
            int current(CustomTask task, TaskContext context);

            /**
             * Whether the player may hand it in with the Submit button.
             *
             * <p>False unless a handler says otherwise: a measured task needs no button, and a button that
             * does nothing is worse than no button. A handler whose task is "the player told us they did
             * it" returns true here, and then the row reads like a checkmark's.
             */
            default boolean canSubmitByHand(CustomTask task) {
                return false;
            }
        }

        private static final Map<String, Handler> HANDLERS = new ConcurrentHashMap<>();

        /** Handler ids whose failure has already been warned about, so a poll-every-tick throw warns once. */
        private static final Set<String> WARNED = ConcurrentHashMap.newKeySet();

        /**
         * Warns about a throwing handler the first time per id.
         *
         * <p>Once rather than per poll, because the poll runs every twenty ticks per player and a warning
         * per poll is a log nobody can read. Reset by {@link #clear}, so a script reload that is still
         * broken warns again rather than failing silently forever.
         */
        static void warnOnce(String id, Throwable thrown) {
            if (WARNED.add(id)) {
                Constants.LOG.warn("custom task \"{}\" threw while being measured; it reads as no"
                        + " progress until the handler is fixed ({})", id, thrown.toString());
            }
        }

        /**
         * Registers a handler.
         *
         * <p>Called from an addon's construction or a script's init. Registering the same id twice replaces
         * the first -- which is what a reload of a script means, and the alternative, refusing, would make
         * an iteration cycle impossible.
         */
        public static void register(String id, Handler handler) {
            HANDLERS.put(java.util.Objects.requireNonNull(id, "id"),
                    java.util.Objects.requireNonNull(handler, "handler"));
        }

        /** The handler an id names, or empty when nothing provides it. */
        public static Optional<Handler> handler(String id) {
            return Optional.ofNullable(HANDLERS.get(id));
        }

        /** Every registered id, for diagnostics -- and for the validator's "nothing provides this" warning. */
        public static Set<String> ids() {
            return Set.copyOf(HANDLERS.keySet());
        }

        /** Forgets every handler. Called before a script reload, so a reload does not keep a dead script's. */
        public static void clear() {
            HANDLERS.clear();
            WARNED.clear();
        }
    }
}
