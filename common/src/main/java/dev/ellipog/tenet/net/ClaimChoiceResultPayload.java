package dev.ellipog.tenet.net;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

/**
 * The server's verdict on a choice pick.
 *
 * <h2>Why the picker needs an answer</h2>
 *
 * <p>Because a pick the player just made must not vanish into a toast. The card stays open with the
 * chosen row disabled until this arrives: {@link Result#OK} closes it and moves to the next queued
 * offer, and the two refusals are drawn <b>on the card</b>, so the player can make room, choose a
 * different entry, or leave — instead of watching their decision be discarded and the next question
 * appear.
 *
 * <p>{@link Result#NO_SPACE} is separate from {@link Result#REFUSED} because the sentences differ:
 * one is about the inventory and is the player's to fix, the other is about the reward and is not.
 *
 * @param questId     the quest the answered offer belonged to, as the offer named it
 * @param rewardIndex which of its rewards was answered
 * @param result      what the server did with the answer
 */
public record ClaimChoiceResultPayload(String questId, int rewardIndex, Result result)
        implements CustomPacketPayload {

    /** What became of the pick. */
    public enum Result {
        /** Granted. The card advances. */
        OK,
        /** Nothing fit — nothing was granted, and the reward is still outstanding. */
        NO_SPACE,
        /** Refused for every other reason: already collected, conditions unmet, a stale answer. */
        REFUSED;

        /** A wire ordinal, defaulting to {@link #REFUSED} for a value this build does not know. */
        public static Result byOrdinal(int ordinal) {
            Result[] values = values();
            return ordinal >= 0 && ordinal < values.length ? values[ordinal] : REFUSED;
        }
    }

    public static final CustomPacketPayload.Type<ClaimChoiceResultPayload> TYPE =
            new CustomPacketPayload.Type<>(
                    Identifier.fromNamespaceAndPath("tenet", "claim_choice_result"));

    public static final StreamCodec<? super RegistryFriendlyByteBuf, ClaimChoiceResultPayload> CODEC =
            StreamCodec.composite(
                    ByteBufCodecs.stringUtf8(64), ClaimChoiceResultPayload::questId,
                    ByteBufCodecs.VAR_INT, ClaimChoiceResultPayload::rewardIndex,
                    ByteBufCodecs.VAR_INT.map(Result::byOrdinal, Result::ordinal),
                    ClaimChoiceResultPayload::result,
                    ClaimChoiceResultPayload::new);

    public ClaimChoiceResultPayload {
        if (result == null) {
            result = Result.REFUSED;
        }
    }

    @Override
    public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
