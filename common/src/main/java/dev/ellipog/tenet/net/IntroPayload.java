package dev.ellipog.tenet.net;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

/**
 * An operator asking for the first-quest intro on their own client.
 *
 * <p>The server half of {@code /tenet intro}. A command runs on the server and the guide's
 * latch lives on the client, so the command sends this to the player it was run for and the
 * client's handler parks a fresh-start ask and opens the book on it — the same shape as
 * {@link PreviewPayload}, which parks its own ask the same way.
 *
 * <p>It carries nothing at all: the ask resets the client's seen flag, so what the guide shows
 * is decided there — an empty book starts at making a chapter, a book with content starts at
 * the first rung that is still undone. A payload naming a rung would be the server deciding
 * what the author's own book still needs.
 */
public record IntroPayload() implements CustomPacketPayload {

    /**
     * The payload's id. The constructor, not {@code createType} — see
     * {@link QuestSyncPayload#TYPE} for why that method silently rewrites the namespace.
     */
    public static final CustomPacketPayload.Type<IntroPayload> TYPE =
            new CustomPacketPayload.Type<>(Identifier.fromNamespaceAndPath("tenet", "intro"));

    public static final StreamCodec<? super RegistryFriendlyByteBuf, IntroPayload> CODEC =
            StreamCodec.unit(new IntroPayload());

    @Override
    public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
