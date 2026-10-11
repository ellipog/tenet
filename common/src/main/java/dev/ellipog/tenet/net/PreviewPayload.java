package dev.ellipog.tenet.net;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/**
 * An operator asking to see the quest book as a normal player sees it.
 *
 * <p>The server half of {@code /tenet preview}. A command runs on the server and the preview
 * latch lives on the client, so the command sends this to the player it was run for and the
 * client's handler flips the book into (or out of) its player preview — the same latch the
 * Preview pill owns, so chat and the pill cannot disagree about whether a preview is showing.
 *
 * <p>Tri-state by two booleans rather than an optional: {@code toggle} set means "flip whatever
 * is showing", which is what a bare {@code /tenet preview} asks; otherwise {@code on} is the
 * absolute answer, which is what {@code /tenet preview on|off} asks. One boolean would force
 * the server to know the client's latch, and the server is the one side that cannot.
 *
 * @param toggle whether to flip the current preview rather than set it
 * @param on     the absolute answer when {@code toggle} is false
 */
public record PreviewPayload(boolean toggle, boolean on) implements CustomPacketPayload {

    /** The payload's id. The constructor, not {@code createType} — see {@link QuestSyncPayload#TYPE}. */
    public static final CustomPacketPayload.Type<PreviewPayload> TYPE =
            new CustomPacketPayload.Type<>(ResourceLocation.fromNamespaceAndPath("tenet", "preview"));

    public static final StreamCodec<? super RegistryFriendlyByteBuf, PreviewPayload> CODEC =
            StreamCodec.composite(
                    ByteBufCodecs.BOOL, PreviewPayload::toggle,
                    ByteBufCodecs.BOOL, PreviewPayload::on,
                    PreviewPayload::new);

    @Override
    public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
