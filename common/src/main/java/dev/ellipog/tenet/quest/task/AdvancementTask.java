package dev.ellipog.tenet.quest.task;

import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;

import dev.ellipog.tenet.Tenet;
import dev.ellipog.tenet.quest.QuestTask;
import dev.ellipog.tenet.quest.TaskCommon;
import dev.ellipog.tenet.quest.TaskContext;

import net.minecraft.advancements.AdvancementProgress;
import net.minecraft.resources.Identifier;

import java.util.Optional;
import java.util.Set;
import java.util.function.Function;

/**
 * Earn an advancement, or one criterion of one.
 *
 * <pre>{@code { "type": "tenet:advancement", "advancement": "minecraft:story/mine_stone" } }</pre>
 *
 * <p>Polled every five ticks, as legacy packs do: an advancement task is cheap to ask and the player
 * expects the book to notice the moment the toast appears.
 */
public record AdvancementTask(TaskCommon common, Identifier advancement, Optional<String> criterion)
        implements QuestTask {

    public static final Identifier TYPE = Identifier.fromNamespaceAndPath(Tenet.MOD_ID, "advancement");

    public static final Set<String> FIELDS = Set.of("advancement", "criterion");

    public static final MapCodec<AdvancementTask> MAP_CODEC = RecordCodecBuilder.mapCodec(instance -> instance.group(
            TaskCommon.mapCodec(5).forGetter(AdvancementTask::common),
            Identifier.CODEC.fieldOf("advancement").forGetter(AdvancementTask::advancement),
            com.mojang.serialization.Codec.STRING.optionalFieldOf("criterion").forGetter(AdvancementTask::criterion)
    ).apply(instance, AdvancementTask::new));

    @Override
    public Identifier type() {
        return TYPE;
    }

    public static final TaskBehaviour<AdvancementTask> BEHAVIOUR = new TaskBehaviour<>() {

        @Override
        public int required(AdvancementTask task) {
            return 1;
        }

        @Override
        public int current(AdvancementTask task, TaskContext context) {
            var holder = context.player().level().getServer().getAdvancements()
                    .get(task.advancement());
            if (holder == null) {
                // An advancement this build does not have. The validator reports it at the file's own
                // line, and the task sits visibly incomplete rather than silently completing.
                return 0;
            }
            AdvancementProgress progress = context.player().getAdvancements().getOrStartProgress(holder);
            // Null-checked in vanilla's own way: `getCriterion` returns null for a name this
            // advancement does not declare, which the validator has no way to know about, so a typo
            // used to dereference here rather than read as "not done". Plain branches rather than
            // the mapped lambda: one fewer allocation per evaluation for the same answers, typo
            // included.
            if (task.criterion().isPresent()) {
                var criterion = progress.getCriterion(task.criterion().get());
                return criterion != null && criterion.isDone() ? 1 : 0;
            }
            return progress.isDone() ? 1 : 0;
        }

        @Override
        public boolean canSubmitByHand(AdvancementTask task, boolean chapterDefault) {
            return false;
        }
    };

    public static final Function<AdvancementTask, TaskDisplay> DISPLAY = task -> {
        // The subject, criterion included: a task watching one criterion is about that criterion, and
        // the row says so rather than naming the whole advancement.
        String what = task.criterion().map(name -> name + " of " + task.advancement())
                .orElse(task.advancement().toString());
        return TaskDisplay.ofTranslatableText("tenet.task.advancement", "Earn " + what, what, 1);
    };
}
