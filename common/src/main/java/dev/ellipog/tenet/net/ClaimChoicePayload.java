package dev.ellipog.tenet.net;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

/**
 * The player's answer to a {@link ChoiceRewardPayload}.
 *
 * <p>Like every client-to-server payload here, it is a request and not a statement: the server
 * re-resolves the quest, the reward and the entry index against its own files, and refuses anything
 * that is not an unclaimed choice reward of a finished quest. The index is a position in the table,
 * so the table's own shape is the bound — there is no way to name a reward the table does not hold.
 *
 * @param questId     the quest, by id or alias
 * @param rewardIndex which of its rewards is being answered
 * @param entryIndex  the chosen position in the table
 */
public record ClaimChoicePayload(String questId, int rewardIndex, int entryIndex)
        implements CustomPacketPayload {

    public static final CustomPacketPayload.Type<ClaimChoicePayload> TYPE =
            new CustomPacketPayload.Type<>(
                    Identifier.fromNamespaceAndPath("tenet", "claim_choice"));

    public static final StreamCodec<? super RegistryFriendlyByteBuf, ClaimChoicePayload> CODEC =
            StreamCodec.composite(
                    ByteBufCodecs.stringUtf8(64), ClaimChoicePayload::questId,
                    ByteBufCodecs.VAR_INT, ClaimChoicePayload::rewardIndex,
                    ByteBufCodecs.VAR_INT, ClaimChoicePayload::entryIndex,
                    ClaimChoicePayload::new);

    @Override
    public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
