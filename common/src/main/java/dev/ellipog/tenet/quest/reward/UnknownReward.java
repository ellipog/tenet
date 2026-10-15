package dev.ellipog.tenet.quest.reward;

import dev.ellipog.tenet.quest.QuestReward;

import net.minecraft.resources.Identifier;

/**
 * A reward whose type this build has no codec for, kept rather than thrown away.
 *
 * <p>The reward half of {@link dev.ellipog.tenet.quest.task.UnknownTask}, and the argument for it is
 * the same one: a codec error refuses the whole document, so before this a single reward naming an
 * addon that is not installed cost the author every quest in the file. See {@code TypeDispatch}.
 *
 * <h2>Why {@link #autoGrantable()} is false</h2>
 *
 * <p>It is the one place this record makes a decision rather than staying inert, and it is the decision
 * that matters. An automatic payout path <b>marks a reward collected and then grants it</b>; for a
 * reward whose behaviour this build does not have, that would mark it collected and grant nothing —
 * losing it for good, with no row left to claim. {@code QuestReward.autoGrantable}'s javadoc names
 * exactly this fault for a choice reward, and the answer is the same: leave it outstanding so the
 * normal claim flow still offers it, and the row says why it cannot be paid.
 *
 * <p>{@link RewardCommon#DEFAULT} rather than null, for the reason given on {@code UnknownTask.of}.
 */
public record UnknownReward(RewardCommon common, Identifier type) implements QuestReward {

    /** The shape a decoded unknown reward takes: no settings of its own, since none can be read. */
    public static UnknownReward of(Identifier type) {
        return new UnknownReward(RewardCommon.DEFAULT, type);
    }

    /**
     * Never, so an automatic path leaves it for the claim rather than marking it collected unpaid.
     *
     * <p>False rather than the interface's true default, and that override is the whole of this
     * class's behaviour — see the class note.
     */
    @Override
    public boolean autoGrantable() {
        return false;
    }

    /**
     * Grants nothing, deliberately.
     *
     * <p>Registered through {@code RewardTypes.behaviourOf} so a caller reaches this rather than an
     * empty {@code Optional} it might handle differently: the answer to "pay this" is "there is nothing
     * to pay it with", which is a no-op rather than a failure. Nothing is announced, because a message
     * saying a reward was given when it was not is worse than silence.
     */
    public static final RewardBehaviour<UnknownReward> BEHAVIOUR = (reward, context) -> {
    };
}
