package dev.ellipog.tenet.quest.reward;

import com.mojang.serialization.Codec;
import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;

import dev.ellipog.tenet.Tenet;
import dev.ellipog.tenet.progress.StageService;
import dev.ellipog.tenet.quest.QuestReward;

import net.minecraft.resources.Identifier;

import java.util.Set;
import java.util.function.Function;

/**
 * Grant a stage, or take one away.
 *
 * <pre>{@code { "type": "tenet:stage", "stage": "my_pack:left_the_village" } }</pre>
 *
 * <p>The write half of {@link dev.ellipog.tenet.quest.task.StageTask}: a quest that unlocks a chapter, or
 * closes one, does it by setting a flag rather than by leaving the player to remember. {@code remove} turns
 * the grant into its inverse, for the rarer case of a quest that revokes something.
 *
 * <p>Idempotent by construction -- the store reports whether anything changed and the event only fires when
 * it did -- so a repeatable quest granting the same stage twice is a no-op the second time rather than an
 * event storm.
 *
 * <h2>Who gets it</h2>
 *
 * <p>The player who claimed, even on a team-mode quest, because a stage is per player everywhere it is
 * used: GameStages, the legacy stage integration, and every pack script written against them. A team-wide
 * grant is a script's loop over the roster, which is also how a pack does it elsewhere.
 *
 * <p>{@code teamStage} is the legacy {@code team_stage}: grant to the team instead. The whole party
 * then holds the flag, which is what a multiplayer pack's induction quest hands over.
 */
public record StageReward(RewardCommon common, Identifier stage, boolean remove,
                          boolean teamStage) implements QuestReward {

    public static final Identifier TYPE = Identifier.fromNamespaceAndPath(Tenet.MOD_ID, "stage");

    public static final Set<String> FIELDS = Set.of("stage", "remove", "teamStage");

    public static final MapCodec<StageReward> MAP_CODEC = RecordCodecBuilder.mapCodec(instance -> instance.group(
            RewardCommon.MAP_CODEC.forGetter(StageReward::common),
            Identifier.CODEC.fieldOf("stage").forGetter(StageReward::stage),
            Codec.BOOL.optionalFieldOf("remove", false).forGetter(StageReward::remove),
            Codec.BOOL.optionalFieldOf("teamStage", false).forGetter(StageReward::teamStage)
    ).apply(instance, StageReward::new));

    @Override
    public Identifier type() {
        return TYPE;
    }

    public static final RewardBehaviour<StageReward> BEHAVIOUR = (reward, context) -> {
        if (reward.teamStage()) {
            if (reward.remove()) {
                StageService.removeTeam(context.server(), context.owner(), reward.stage());
            }
            else {
                StageService.addTeam(context.server(), context.owner(), reward.stage());
            }
        }
        else if (reward.remove()) {
            StageService.remove(context.player().level().getServer(), context.player().getUUID(), reward.stage());
        }
        else {
            StageService.add(context.player().level().getServer(), context.player().getUUID(), reward.stage());
        }
    };

    public static final Function<StageReward, RewardDisplay> DISPLAY = reward -> {
        // Two keys rather than one sentence with a verb inside it: a grant and a revocation are
        // different acts, and a translator should be able to say them differently.
        String key = reward.remove() ? "tenet.reward.stage.remove" : "tenet.reward.stage.grant";
        String fallback = (reward.remove() ? "Remove the stage " : "Grant the stage ") + reward.stage();
        return RewardDisplay.ofTranslatableText(key, fallback, reward.stage().toString(), 1);
    };
}
