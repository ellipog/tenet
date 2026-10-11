package dev.ellipog.tenet.quest;

import com.mojang.serialization.Codec;
import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;

import dev.ellipog.tenet.quest.condition.ConditionTypes;
import dev.ellipog.tenet.quest.condition.QuestCondition;

import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * The settings every task has, whatever its type.
 *
 * <p>A separate record so these fields are declared once. It is a {@link MapCodec} so that each
 * task type embeds it <b>flat</b> — a task says {@code "optional": true} at its own level, not
 * {@code "common": {"optional": true}}. Each type's codec merges this in and adds its own fields,
 * which keeps a type's own codec down to the fields that make it that type.
 *
 * <p>{@code autoSubmitTicks} is borrowed from the legacy format, which tries a task on a per-type schedule
 * rather than every tick — a short interval for a checkmark, a long one for an observation. One
 * field, and the alternative is a mod that scans every player's inventory twenty times a second for
 * nothing.
 *
 * <p>{@code conditions} is the gate: every condition must hold before this task counts for a player,
 * asked per member exactly where the engine asks for their count. Empty is the common case and costs
 * nothing — see {@link dev.ellipog.tenet.quest.condition.Conditions}. The short constructor keeps the
 * seventeen task types from each carrying a list they never fill; the codec still reads and writes the
 * field flat, beside {@code optional}:
 *
 * <pre>{@code
 * { "type": "tenet:checkmark", "conditions": [ { "type": "tenet:stage", "stage": "pack:marked" } ] }
 * }</pre>
 *
 * <p>{@code disableToast} is the legacy {@code disable_toast} on the task: this task finishing
 * raises no task toast. It ORs with the quest's own flag and the silent auto-claim modes where
 * notices are decided — a quieted task in an announced quest stays quiet, and an announced task
 * in a quieted quest stays quiet too.
 *
 * <p>{@code title} and {@code icon} are the author's overrides: the words the row wears instead of
 * the type's own sentence, and the picture it wears instead of the type's own. Absent means the
 * type decides, which is every file written before these fields existed. See
 * {@link dev.ellipog.tenet.quest.task.TaskTypes#displayOf} for the resolution.
 *
 * <p>{@code tags} is the legacy {@code tags}, present on every object: words this task answers
 * to in lookups by tag. Each tag is {@code ^[a-z0-9_]{1,64}$}, the same rule an id follows.
 */
public record TaskCommon(boolean optional, int autoSubmitTicks, List<QuestCondition> conditions,
                         boolean disableToast, Optional<QuestText> title, Optional<Icon> icon,
                          List<String> tags) {

    /** The shape every task type's defaults use: no conditions, announced, computed words. */
    public TaskCommon(boolean optional, int autoSubmitTicks) {
        this(optional, autoSubmitTicks, List.of(), false, Optional.empty(), Optional.empty(),
                List.of());
    }

    /** The shape for a task with conditions but the default announcement and words. */
    public TaskCommon(boolean optional, int autoSubmitTicks, List<QuestCondition> conditions) {
        this(optional, autoSubmitTicks, conditions, false, Optional.empty(), Optional.empty(),
                List.of());
    }

    /** The shape for a task with conditions and announcement but computed words. */
    public TaskCommon(boolean optional, int autoSubmitTicks, List<QuestCondition> conditions,
                      boolean disableToast) {
        this(optional, autoSubmitTicks, conditions, disableToast, Optional.empty(), Optional.empty(),
                List.of());
    }

    public static final TaskCommon DEFAULT = new TaskCommon(false, 20);

    /** The field names this contributes, for the validator to allow at task level. */
    public static final Set<String> FIELDS = Set.of("optional", "autoSubmitTicks", "conditions",
            "disableToast", "title", "icon", "tags");

    public static final MapCodec<TaskCommon> MAP_CODEC = mapCodec(20);

    /**
     * The same, with a type's own default interval.
     *
     * <p>legacy packs give each type a cadence rather than one number for everything — a stat task can
     * afford to be asked every three ticks, a structure lookup every twenty, a dimension check every
     * hundred — and an author who says nothing should get the type's cadence rather than the
     * cheapest-to-implement one. The field still overrides it per task.
     */
    public static MapCodec<TaskCommon> mapCodec(int defaultInterval) {
        return RecordCodecBuilder.mapCodec(instance -> instance.group(
                Codec.BOOL.optionalFieldOf("optional", false).forGetter(TaskCommon::optional),
                Codec.intRange(1, 72000).optionalFieldOf("autoSubmitTicks", defaultInterval)
                        .forGetter(TaskCommon::autoSubmitTicks),
                // Read through the dispatch codec, which is the only one that knows "type". Built lazily
                // there, so this static does not capture a half-initialised registry -- see QuestTask.
                ConditionTypes.dispatchCodec().listOf().optionalFieldOf("conditions", List.of())
                        .forGetter(TaskCommon::conditions),
                Codec.BOOL.optionalFieldOf("disableToast", false).forGetter(TaskCommon::disableToast),
                QuestText.CODEC.optionalFieldOf("title").forGetter(TaskCommon::title),
                Icon.CODEC.optionalFieldOf("icon").forGetter(TaskCommon::icon),
                Codec.STRING.listOf().optionalFieldOf("tags", List.of()).forGetter(TaskCommon::tags)
        ).apply(instance, TaskCommon::new));
    }
}
