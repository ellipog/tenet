package dev.ellipog.tenet.net;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

/**
 * What a grant could not fit into the player's inventory, so the book can say it.
 *
 * <p>The action bar carries the same sentence, and for a player who is not looking at the book that
 * is enough. It is not enough for the player who is: the HUD is not drawn behind an open screen, so
 * the one place the sentence matters most — the rewards panel the claim was pressed in — is the one
 * place it cannot be read. The book's own toast stack is the sink; see {@code QuestNotifier} for the
 * same routing decision made for completions.
 *
 * <p>Counts only. The stacks are on the ground by the time this is sent, and a payload carrying item
 * ids would be carrying a copy of something the player can already see. There used to be a second
 * count — how many items those stacks held — and nothing read it: the handler passed the stack count
 * alone into the one sentence that exists for this, so the number travelled, round-tripped and was
 * asserted in tests while no player could ever be shown it. One fact, one component.
 *
 * @param stacks how many stacks were dropped
 */
public record RewardOverflowPayload(int stacks) implements CustomPacketPayload {

    public static final CustomPacketPayload.Type<RewardOverflowPayload> TYPE =
            new CustomPacketPayload.Type<>(
                    Identifier.fromNamespaceAndPath("tenet", "reward_overflow"));

    public static final StreamCodec<? super RegistryFriendlyByteBuf, RewardOverflowPayload> CODEC =
            StreamCodec.composite(
                    ByteBufCodecs.VAR_INT, RewardOverflowPayload::stacks,
                    RewardOverflowPayload::new);

    @Override
    public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
