package dev.ellipog.tenet.net;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

/**
 * A client asking for a reward table's file, so its editor has something to read.
 *
 * <p>The counterpart of {@link ReplicaRequestPayload}, and it exists for the same reason: a table's
 * entries are wanted by the panel that is editing it and by nothing else. The tree carries a summary —
 * a name, an icon, a count — and the file itself travels only when an editor opens one.
 *
 * <p>Client to server, so the 32767-byte cap that {@code ServerboundCustomPayloadPacket} enforces
 * applies, and one table id is nowhere near it.
 *
 * @param table the table's id, as the loader names it: its file name without the suffix
 */
public record TableReplicaRequestPayload(String table) implements CustomPacketPayload {

    /** The payload's id. The constructor, not {@code createType} — see {@link QuestSyncPayload#TYPE}. */
    public static final CustomPacketPayload.Type<TableReplicaRequestPayload> TYPE =
            new CustomPacketPayload.Type<>(
                    Identifier.fromNamespaceAndPath("tenet", "table_replica_request"));

    public static final StreamCodec<? super RegistryFriendlyByteBuf, TableReplicaRequestPayload> CODEC =
            StreamCodec.composite(
                    ByteBufCodecs.stringUtf8(128), TableReplicaRequestPayload::table,
                    TableReplicaRequestPayload::new);

    @Override
    public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
