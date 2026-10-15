package dev.ellipog.tenet.quest.task;

import com.mojang.serialization.Codec;
import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;

import dev.ellipog.tenet.Tenet;
import dev.ellipog.tenet.quest.QuestTask;
import dev.ellipog.tenet.quest.TaskCommon;
import dev.ellipog.tenet.quest.TaskContext;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.stats.Stats;

import java.util.Set;
import java.util.function.Function;

/**
 * Reach a value of a vanilla statistic.
 *
 * <pre>{@code { "type": "tenet:stat", "stat": "minecraft:walk_one_cm", "value": 1000 } }</pre>
 *
 * <p>Progress is shown as the stat's own number — "434 / 1000" — and clamped to the requirement, as
 * The legacy format clamps it, so a stat in the millions does not overflow a row. Polled every three ticks:
 * reading a stat is a map lookup, and it is the cadence the legacy format gives it.
 */
public record StatTask(TaskCommon common, Identifier stat, int value) implements QuestTask {

    public static final Identifier TYPE = Identifier.fromNamespaceAndPath(Tenet.MOD_ID, "stat");

    public static final Set<String> FIELDS = Set.of("stat", "value");

    public static final MapCodec<StatTask> MAP_CODEC = RecordCodecBuilder.mapCodec(instance -> instance.group(
            TaskCommon.mapCodec(3).forGetter(StatTask::common),
            Identifier.CODEC.fieldOf("stat").forGetter(StatTask::stat),
            Codec.intRange(1, 1_000_000_000).fieldOf("value").forGetter(StatTask::value)
    ).apply(instance, StatTask::new));

    @Override
    public Identifier type() {
        return TYPE;
    }

    public static final TaskBehaviour<StatTask> BEHAVIOUR = new TaskBehaviour<>() {

        @Override
        public int required(StatTask task) {
            return task.value();
        }

        @Override
        public int current(StatTask task, TaskContext context) {
            // By id, the way ObjectiveCriteria resolves a custom criterion: the registry lookup
            // hands back the registered value, which is the one instance the StatType's
            // identity-keyed map already holds. Asking the StatType directly for a decoded id
            // builds a Stat whose name needs the registry's reverse lookup -- which is null here
            // -- and the NPE lands mid-tick, in the playthrough, on the first stat task any
            // example has ever carried. A stat this build does not have counts nothing; the
            // validator already said so at load.
            return BuiltInRegistries.CUSTOM_STAT.getOptional(task.stat())
                    .map(id -> context.player().getStats().getValue(Stats.CUSTOM.get(id)))
                    .orElse(0);
        }

        @Override
        public boolean canSubmitByHand(StatTask task, boolean chapterDefault) {
            return false;
        }
    };

    public static final Function<StatTask, TaskDisplay> DISPLAY = task ->
            TaskDisplay.ofTranslatableText("tenet.task.stat", "Reach " + task.stat(),
                    task.stat().toString(), task.value());
}
