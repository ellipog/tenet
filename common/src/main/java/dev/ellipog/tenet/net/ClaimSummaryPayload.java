package dev.ellipog.tenet.net;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

/**
 * How a bulk claim ended: how many rewards it took, out of how many it could have.
 *
 * <h2>Why a sweep needs saying out loud</h2>
 *
 * <p>Because a sweep that stops early and a sweep that finished look identical from the inbox — rows
 * disappear either way — and the difference matters: one means "come back when you have room", the
 * other means "you are done". The action bar carries the sentence, and this payload carries it into
 * the book, where the HUD is not drawn. See {@code QuestNotifier} for the same routing the completion
 * and overflow notices use.
 *
 * <p>{@code halted} distinguishes the two endings; the numbers are the same shape either way, so a
 * successful sweep can say "Claimed 8 of 8" rather than falling silent.
 *
 * @param claimed how many rewards were handed over
 * @param total   how many the sweep could have taken when it started
 * @param halted  whether it stopped because something did not fit
 */
public record ClaimSummaryPayload(int claimed, int total, boolean halted) implements CustomPacketPayload {

    public static final CustomPacketPayload.Type<ClaimSummaryPayload> TYPE =
            new CustomPacketPayload.Type<>(
                    Identifier.fromNamespaceAndPath("tenet", "claim_summary"));

    public static final StreamCodec<? super RegistryFriendlyByteBuf, ClaimSummaryPayload> CODEC =
            StreamCodec.composite(
                    ByteBufCodecs.VAR_INT, ClaimSummaryPayload::claimed,
                    ByteBufCodecs.VAR_INT, ClaimSummaryPayload::total,
                    ByteBufCodecs.BOOL, ClaimSummaryPayload::halted,
                    ClaimSummaryPayload::new);

    @Override
    public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
