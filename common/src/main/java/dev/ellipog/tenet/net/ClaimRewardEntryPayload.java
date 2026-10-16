package dev.ellipog.tenet.net;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

/**
 * One reward of a finished quest, collected on its own.
 *
 * <p>The rewards panel's per-row Claim, as against {@link ClaimRewardPayload}'s whole quest and
 * {@link ClaimAllPayload}'s whole book. Like every client-to-server payload here it is a request and
 * not a statement: the server re-resolves the quest and the index against its own files, and refuses
 * anything out of range, already collected, gated by an unmet condition, or held by the team's block.
 * A forged index therefore buys a modified client nothing — it can only ask for a reward it could
 * have pressed a button for.
 *
 * @param questId     the quest, by id or alias
 * @param rewardIndex which of its rewards is being collected
 */
public record ClaimRewardEntryPayload(String questId, int rewardIndex) implements CustomPacketPayload {

    public static final CustomPacketPayload.Type<ClaimRewardEntryPayload> TYPE =
            new CustomPacketPayload.Type<>(
                    Identifier.fromNamespaceAndPath("tenet", "claim_reward_entry"));

    public static final StreamCodec<? super RegistryFriendlyByteBuf, ClaimRewardEntryPayload> CODEC =
            StreamCodec.composite(
                    ByteBufCodecs.stringUtf8(64), ClaimRewardEntryPayload::questId,
                    ByteBufCodecs.VAR_INT, ClaimRewardEntryPayload::rewardIndex,
                    ClaimRewardEntryPayload::new);

    @Override
    public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
