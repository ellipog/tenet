package dev.ellipog.tenet.net;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

/**
 * A reward table's file, whole, on its way to a client that asked for it.
 *
 * <h2>Why the text rather than a decoded table</h2>
 *
 * <p>The same argument {@link ChapterReplicaPayload} makes for a chapter: the client's copy is a copy
 * rather than a translation. A table written by a newer build, or one holding a reward type this build
 * has never heard of, is still something an author should be able to look at — and an editor that
 * re-encoded what it was sent would silently drop the fields it did not understand.
 *
 * <p>The file cap is the chapter payload's, for the same measured reason: {@code
 * ClientboundCustomPayloadPacket.MAX_PAYLOAD_SIZE} is 1048576 bytes in this version, and a table is a
 * few kilobytes. A pack that somehow exceeds it gets a refusal rather than a truncated table, because
 * the codec's limit is the check.
 *
 * @param table the table's id
 * @param json  the file's own text, or empty when it could not be read
 * @param usedBy who still points at this table — a quest, or another table file
 */
public record TableReplicaPayload(String table, String json, java.util.List<String> usedBy)
        implements CustomPacketPayload {

    /** The payload's id. The constructor, not {@code createType} — see {@link QuestSyncPayload#TYPE}. */
    public static final CustomPacketPayload.Type<TableReplicaPayload> TYPE =
            new CustomPacketPayload.Type<>(
                    Identifier.fromNamespaceAndPath("tenet", "table_replica"));

    /**
     * The referrers, as the short sentences the server's own guard already composes.
     *
     * <p>On this payload rather than in one of its own because this is the only moment the client is
     * asking about one particular table — and the one panel that wants the answer is the editor that just
     * asked. A table nobody points at sends an empty list, which is a fact the editor states rather than
     * an absence it stays quiet about.
     */
    private static final StreamCodec<io.netty.buffer.ByteBuf, java.util.List<String>> USED_BY =
            ByteBufCodecs.stringUtf8(256).apply(ByteBufCodecs.list());

    public static final StreamCodec<? super RegistryFriendlyByteBuf, TableReplicaPayload> CODEC =
            StreamCodec.composite(
                    ByteBufCodecs.stringUtf8(128), TableReplicaPayload::table,
                    ByteBufCodecs.stringUtf8(1048576), TableReplicaPayload::json,
                    USED_BY, TableReplicaPayload::usedBy,
                    TableReplicaPayload::new);

    @Override
    public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
