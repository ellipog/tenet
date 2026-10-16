package dev.ellipog.tenet.quest;

import dev.ellipog.tenet.quest.reward.RewardCommon;

import net.minecraft.resources.Identifier;

import java.util.Optional;

/**
 * Something a player receives for completing a quest.
 *
 * <p>Mirrors {@link QuestTask}, and is separate from it for a reason worth stating: a reward is
 * granted <b>once</b> and must be idempotent, while a task is evaluated repeatedly. Sharing a hierarchy
 * between the two looks tidy and then has to be unpicked the first time a reward needs to record that
 * it has already been claimed.
 *
 * <p>Like {@code QuestTask}, this holds no codec field — see the note there for the initialisation
 * cycle that caused. The codec is at
 * {@link dev.ellipog.tenet.quest.reward.RewardTypes#dispatchCodec()}.
 */
public interface QuestReward {

    /** Which registered type this is. The value of the JSON {@code "type"} field. */
    Identifier type();

    /** The settings every reward has, whatever its type. See {@link RewardCommon}. */
    RewardCommon common();

    /**
     * The reward table this reward rolls from, when it is one of the table-driven types.
     *
     * <p>Empty for every other type, and a default rather than an abstract method so the vast
     * majority of rewards need not know the concept exists. The loader reads it to report a reference
     * to a table that is not there, in the same pass that reports a dangling {@code dependsOn} — one
     * hook for one cross-file fact.
     */
    default Optional<String> tableId() {
        return Optional.empty();
    }

    /**
     * Whether this reward can be handed over without asking the player anything.
     *
     * <p>True for everything that grants what it says; false for the one type that cannot pay without a
     * decision — a choice table, whose payout <i>is</i> the player's pick. The auto-claim paths skip a
     * reward this is false for, leaving it outstanding for the normal claim flow; before this existed
     * a reward marked automatic was marked collected and then granted nothing, which lost the choice.
     */
    default boolean autoGrantable() {
        return true;
    }
}
