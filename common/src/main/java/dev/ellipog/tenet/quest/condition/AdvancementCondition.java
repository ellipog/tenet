package dev.ellipog.tenet.quest.condition;

import com.mojang.serialization.Codec;
import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;

import dev.ellipog.tenet.Tenet;

import net.minecraft.advancements.AdvancementProgress;
import net.minecraft.resources.Identifier;

import java.util.Optional;
import java.util.Set;
import java.util.function.Function;

/**
 * Have earned an advancement, or one criterion of one.
 *
 * <pre>{@code { "type": "tenet:advancement", "advancement": "minecraft:story/mine_diamond" } }</pre>
 *
 * <p>The condition half of {@code tenet:advancement}, read the same way: an advancement this build
 * does not have reads as not earned rather than throwing, and the validator reports the missing id at
 * the file's own line.
 */
public record AdvancementCondition(Identifier advancement, Optional<String> criterion)
        implements QuestCondition {

    public static final Identifier TYPE = Identifier.fromNamespaceAndPath(Tenet.MOD_ID, "advancement");

    public static final Set<String> FIELDS = Set.of("advancement", "criterion");

    public static final MapCodec<AdvancementCondition> MAP_CODEC =
            RecordCodecBuilder.mapCodec(instance -> instance.group(
                    Identifier.CODEC.fieldOf("advancement").forGetter(AdvancementCondition::advancement),
                    Codec.STRING.optionalFieldOf("criterion").forGetter(AdvancementCondition::criterion)
            ).apply(instance, AdvancementCondition::new));

    @Override
    public Identifier type() {
        return TYPE;
    }

    public static final ConditionBehaviour<AdvancementCondition> BEHAVIOUR = (condition, context) -> {
        var holder = context.server().getAdvancements().get(condition.advancement());
        if (holder == null) {
            return false;
        }
        AdvancementProgress progress = context.player().getAdvancements().getOrStartProgress(holder);
        // The criterion may name one this advancement does not declare -- the validator cannot know
        // which names exist, so a typo reaches here -- and `getCriterion` returns null for it. Vanilla's
        // own isCriterionDone null-checks the same way; without this, one wrong word in a file
        // dereferences on every tick and every lock refresh. Plain branches rather than the mapped
        // lambda, for the same reason as the task's own: the same answers, typo included.
        if (condition.criterion().isPresent()) {
            var criterion = progress.getCriterion(condition.criterion().get());
            return criterion != null && criterion.isDone();
        }
        return progress.isDone();
    };

    public static final Function<AdvancementCondition, ConditionDisplay> DISPLAY = condition -> {
        // The subject, criterion included, as the task's own sentence does it: a condition watching one
        // criterion is about that criterion, not the whole advancement.
        String what = condition.criterion().map(name -> name + " of " + condition.advancement())
                .orElse(condition.advancement().toString());
        return ConditionDisplay.ofTranslatableText("tenet.condition.advancement", "Have earned " + what, what);
    };
}
