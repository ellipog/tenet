package dev.ellipog.tenet.net;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

/**
 * A client asking for a chapter's files, so a panel has something to read.
 *
 * <p>It carries the chapter's id and nothing else: this is a read, and the server decides whether the sender
 * may have it — an editor's client needs the chapter's own trees to show fields the synced tree does not
 * carry, and nobody else has a reason to ask.
 *
 * <p>Client to server, so the 32767-byte cap that
 * {@code ServerboundCustomPayloadPacket} enforces applies, and one chapter id is nowhere near it.
 *
 * @param chapter the chapter's id, as the loader names it
 */
public record ReplicaRequestPayload(String chapter) implements CustomPacketPayload {

    /** The payload's id. The constructor, not {@code createType} — see {@link QuestSyncPayload#TYPE}. */
    public static final CustomPacketPayload.Type<ReplicaRequestPayload> TYPE =
            new CustomPacketPayload.Type<>(Identifier.fromNamespaceAndPath("tenet", "replica_request"));

    public static final StreamCodec<? super RegistryFriendlyByteBuf, ReplicaRequestPayload> CODEC =
            StreamCodec.composite(
                    ByteBufCodecs.stringUtf8(64), ReplicaRequestPayload::chapter,
                    ReplicaRequestPayload::new);

    @Override
    public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
