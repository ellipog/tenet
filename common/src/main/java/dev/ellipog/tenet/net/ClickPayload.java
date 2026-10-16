package dev.ellipog.tenet.net;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

/**
 * A player pressing a canvas element whose click the client cannot run itself.
 *
 * <h2>Identity only, like every other client-to-server press</h2>
 *
 * <p>The client sends which chapter and which element, and nothing else — never the command, never
 * the event id. The server reads the authoritative click from its own index, re-checks that it is
 * still a server-side action, and acts on that. A forged string would be a client choosing what the
 * server runs, which is the cheat vector {@link SubmitTaskPayload} exists to close: a payload that
 * carried a command would let a modified client point it at anything.
 *
 * <p>Both ids are bounded strings rather than free ones. A client can send anything, so the codec
 * refuses anything that could not be an id before it reaches a lookup.
 *
 * @param chapterId the chapter holding the element
 * @param elementId the element that was pressed
 */
public record ClickPayload(String chapterId, String elementId) implements CustomPacketPayload {

    /**
     * The payload's id. The constructor, not {@code createType} — see
     * {@link QuestSyncPayload#TYPE} for why that method silently rewrites the namespace.
     */
    public static final CustomPacketPayload.Type<ClickPayload> TYPE =
            new CustomPacketPayload.Type<>(Identifier.fromNamespaceAndPath("tenet", "click"));

    public static final StreamCodec<? super RegistryFriendlyByteBuf, ClickPayload> CODEC =
            StreamCodec.composite(
                    ByteBufCodecs.stringUtf8(64), ClickPayload::chapterId,
                    ByteBufCodecs.stringUtf8(64), ClickPayload::elementId,
                    ClickPayload::new);

    @Override
    public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
