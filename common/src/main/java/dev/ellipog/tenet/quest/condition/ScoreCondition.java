package dev.ellipog.tenet.quest.condition;

import com.mojang.serialization.Codec;
import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;

import dev.ellipog.tenet.Tenet;

import net.minecraft.resources.Identifier;
import net.minecraft.world.scores.Objective;
import net.minecraft.world.scores.ReadOnlyScoreInfo;

import java.util.Set;
import java.util.function.Function;

/**
 * Have at least this much on a scoreboard objective.
 *
 * <pre>{@code { "type": "tenet:score", "objective": "mypack_reputation", "min": 10 } }</pre>
 *
 * <p>Read through {@link ReadOnlyScoreInfo}, not {@code getOrCreatePlayerScore}: a condition that
 * creates the score it is asking about would make "has a score" true for everyone the moment the book
 * opened.
 *
 * <p>An objective that does not exist reads as 0 — objectives are world state, so the validator cannot
 * check the name, and a typo locks the gate rather than crashing. That is documented for authors in
 * {@code docs/authoring/conditions.md}, which is also where the reason the name carries no colon comes
 * from: {@code /scoreboard objectives add} is what creates it, and it takes a word.
 */
public record ScoreCondition(String objective, int min) implements QuestCondition {

    public static final Identifier TYPE = Identifier.fromNamespaceAndPath(Tenet.MOD_ID, "score");

    public static final Set<String> FIELDS = Set.of("objective", "min");

    public static final MapCodec<ScoreCondition> MAP_CODEC = RecordCodecBuilder.mapCodec(instance -> instance.group(
            Codec.STRING.fieldOf("objective").forGetter(ScoreCondition::objective),
            // At least 1, because that is what makes the documented "a missing objective reads as zero"
            // a *shut* gate rather than an open one: 0 >= min is false for every legal file, so a
            // typo'd objective locks. A min of 0 would be a condition nothing can fail.
            Codec.intRange(1, Integer.MAX_VALUE).fieldOf("min").forGetter(ScoreCondition::min)
    ).apply(instance, ScoreCondition::new));

    @Override
    public Identifier type() {
        return TYPE;
    }

    public static final ConditionBehaviour<ScoreCondition> BEHAVIOUR = (condition, context) -> {
        Objective objective = context.server().getScoreboard().getObjective(condition.objective());
        if (objective == null) {
            return false;
        }
        ReadOnlyScoreInfo score = context.server().getScoreboard()
                .getPlayerScoreInfo(context.player(), objective);
        return score != null && score.value() >= condition.min();
    };

    public static final Function<ScoreCondition, ConditionDisplay> DISPLAY = condition -> {
        String subject = condition.min() + " in " + condition.objective();
        return ConditionDisplay.ofTranslatableText("tenet.condition.score", "Have " + subject, subject);
    };
}
