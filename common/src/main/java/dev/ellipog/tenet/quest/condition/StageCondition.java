package dev.ellipog.tenet.quest.condition;

import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;

import dev.ellipog.tenet.Tenet;
import dev.ellipog.tenet.progress.StageService;

import net.minecraft.resources.Identifier;

import java.util.Set;
import java.util.function.Function;

/**
 * Have a stage.
 *
 * <pre>{@code { "type": "tenet:stage", "stage": "my_pack:inducted" } }</pre>
 *
 * <p>The condition half of {@code tenet:stage}: a stage exists by being granted, so there is no list
 * to choose from and no id to validate — the same note the task type carries.
 */
public record StageCondition(Identifier stage) implements QuestCondition {

    public static final Identifier TYPE = Identifier.fromNamespaceAndPath(Tenet.MOD_ID, "stage");

    public static final Set<String> FIELDS = Set.of("stage");

    public static final MapCodec<StageCondition> MAP_CODEC = RecordCodecBuilder.mapCodec(instance -> instance.group(
            Identifier.CODEC.fieldOf("stage").forGetter(StageCondition::stage)
    ).apply(instance, StageCondition::new));

    @Override
    public Identifier type() {
        return TYPE;
    }

    public static final ConditionBehaviour<StageCondition> BEHAVIOUR =
            (condition, context) -> StageService.has(context.server(), context.player(), condition.stage());

    public static final Function<StageCondition, ConditionDisplay> DISPLAY = condition -> {
        String subject = condition.stage().toString();
        return ConditionDisplay.ofTranslatableText("tenet.condition.stage", "Have the stage " + subject, subject);
    };
}
