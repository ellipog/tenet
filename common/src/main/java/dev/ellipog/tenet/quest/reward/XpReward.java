package dev.ellipog.tenet.quest.reward;

import com.mojang.serialization.Codec;
import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import dev.ellipog.tenet.Tenet;
import dev.ellipog.tenet.quest.QuestReward;
import net.minecraft.resources.Identifier;

import java.util.Set;

/**
 * Give the player experience — either a number of points, or a number of whole levels.
 *
 * <pre>{@code { "type": "tenet:xp", "amount": 3, "levels": true } }</pre>
 *
 * <p>{@code levels} exists because they are genuinely different rewards: thirty points is a small
 * nudge, thirty levels is most of a playthrough. The legacy format has both as separate reward types, which
 * is two entries in its type list for one idea.
 */
public record XpReward(RewardCommon common, int amount, boolean levels) implements QuestReward {

    public static final Identifier TYPE = Identifier.fromNamespaceAndPath(Tenet.MOD_ID, "xp");

    /** The type's own fields; {@link RewardCommon#FIELDS} is unioned in at registration. */
    public static final Set<String> FIELDS = Set.of("amount", "levels");

    public static final MapCodec<XpReward> MAP_CODEC = RecordCodecBuilder.mapCodec(instance -> instance.group(
            RewardCommon.MAP_CODEC.forGetter(XpReward::common),
            Codec.intRange(1, 100_000).fieldOf("amount").forGetter(XpReward::amount),
            Codec.BOOL.optionalFieldOf("levels", false).forGetter(XpReward::levels)
    ).apply(instance, XpReward::new));

    @Override
    public Identifier type() {
        return TYPE;
    }

    public static final RewardBehaviour<XpReward> BEHAVIOUR = (reward, context) -> {
        if (reward.levels()) {
            // giveExperienceLevels adjusts the level and *removes* the accumulated points towards the
            // next one, which is what a player means by "three levels" -- and is why it is not the
            // same as giving three levels' worth of points.
            context.player().giveExperienceLevels(reward.amount());
        }
        else {
            context.player().giveExperiencePoints(reward.amount());
        }
    };

    /**
     * Experience has no item to look up, so the label goes as a key with an English fallback.
     *
     * <p>Two keys rather than one because "five experience points" and "five levels" are different
     * rewards with different wording, and a single key with a plural would need the client to decide
     * which noun to use — which is the server's question to answer, since the server is the one that
     * knows whether {@code levels} was set.
     *
     * <p>And three keys, because the singular is the server's answer too: the key is what a client with
     * a language renders, so a key written "%s level(s)" is what a player would read while the fallback
     * beside it — the same reward on a client with no language — read "1 level". Two spellings of one
     * reward is the drift this file's fallbacks exist to prevent, and the count that decides between
     * them is already in the server's hand.
     */
    public static final java.util.function.Function<XpReward, RewardDisplay> DISPLAY = reward ->
            reward.levels()
                    ? RewardDisplay.ofTranslatableText(
                            reward.amount() == 1 ? "tenet.reward.xp.level" : "tenet.reward.xp.levels",
                            reward.amount() + (reward.amount() == 1 ? " level" : " levels"), reward.amount())
                    : RewardDisplay.ofTranslatableText("tenet.reward.xp.points",
                            reward.amount() + " XP", reward.amount());
}
