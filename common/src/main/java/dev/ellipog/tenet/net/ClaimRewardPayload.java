package dev.ellipog.tenet.net;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

/**
 * A player pressing Claim in the quest book.
 *
 * <h2>What the client is allowed to say, and what it is not</h2>
 *
 * <p>This carries a quest id and nothing else. It is a <b>request</b>, not a statement of fact: the
 * client does not say "these rewards are owed" and could not be believed if it did. It says "I would
 * like to collect quest X", and the server decides whether anything is owed by looking at its own
 * stored progress — the same {@code ProgressService.canClaim} that the command and the wire format
 * use, so a claim that arrives for a quest with nothing waiting is refused rather than honoured.
 *
 * <p>That is why there is no amount, no item and no count on this payload. A payload that could name
 * what it wanted would be a payload a modified client could point at a diamond.
 *
 * <p>Unlike {@link SubmitTaskPayload}, which can fail for four different reasons, a claim has exactly
 * one interesting outcome: it worked, or it did not. So there is no response payload; success and
 * failure are both learned from the progress sync that follows, which is the same one every other
 * change sends.
 *
 * @param questId the quest, by id or alias
 */
public record ClaimRewardPayload(String questId) implements CustomPacketPayload {

    /**
     * The payload's id. The constructor, not {@code createType} — see
     * {@link QuestSyncPayload#TYPE} for why that method silently rewrites the namespace.
     */
    public static final CustomPacketPayload.Type<ClaimRewardPayload> TYPE =
            new CustomPacketPayload.Type<>(Identifier.fromNamespaceAndPath("tenet", "claim_reward"));

    public static final StreamCodec<? super RegistryFriendlyByteBuf, ClaimRewardPayload> CODEC =
            StreamCodec.composite(
                    ByteBufCodecs.stringUtf8(64), ClaimRewardPayload::questId,
                    ClaimRewardPayload::new);

    @Override
    public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
