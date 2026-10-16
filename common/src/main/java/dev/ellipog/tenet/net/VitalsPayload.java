package dev.ellipog.tenet.net;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

/**
 * An operator's client being told whether to draw the frame rate.
 *
 * <h2>Why this travels at all</h2>
 *
 * <p>Because the switch has to be an operator's, and the drawing is the client's. Vanilla has no client
 * command, so {@code /tenet vitals} is a server command — which is also the only place an ops check can
 * live, since a client asking for it is not an authority on whether it may. The server decides, and this
 * carries the answer to the one client that asked. One boolean, no chunking and no revision: it is a
 * preference, not content.
 *
 * @param on whether that player's client should draw the vitals overlay
 */
public record VitalsPayload(boolean on) implements CustomPacketPayload {

    /** The payload's id. The constructor, not {@code createType} — see {@link QuestSyncPayload#TYPE}. */
    public static final CustomPacketPayload.Type<VitalsPayload> TYPE =
            new CustomPacketPayload.Type<>(Identifier.fromNamespaceAndPath("tenet", "vitals"));

    public static final StreamCodec<? super RegistryFriendlyByteBuf, VitalsPayload> CODEC =
            StreamCodec.composite(
                    ByteBufCodecs.BOOL, VitalsPayload::on,
                    VitalsPayload::new);

    @Override
    public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
