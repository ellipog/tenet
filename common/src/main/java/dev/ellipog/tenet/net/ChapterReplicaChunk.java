package dev.ellipog.tenet.net;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Optional;

/**
 * A chapter's quests in pieces, for chapters that do not fit in one message.
 *
 * <p>The small path stays {@link ChapterReplicaPayload}: one message, two strings, no compression.
 * This one is only for chapters whose encoded text would not fit that message's per-string cap —
 * thousands of quests, where the single message is a refusal rather than a chapter. Past the
 * threshold the two strings travel framed, deflated and chunked through {@link SyncWire}, exactly
 * like the tree and the progress do, and the client accepts the same replica either way.
 *
 * <p>The frame is one transfer, not two: a four-byte big-endian quests length, then the quests
 * bytes, then the chapter-tree bytes. One transfer means one completion and no cross-message
 * state — no stash of half a replica waiting for its other half, and nothing to forget on
 * disconnect beyond what the reassembler already forgets.
 *
 * @param chapter which chapter this chunk belongs to
 * @param chunk   where this chunk sits in the message
 * @param data    this chunk's packed bytes — see {@link SyncWire#pack}
 */
public record ChapterReplicaChunk(String chapter, SyncChunk chunk, byte[] data)
        implements CustomPacketPayload {

    /** The payload's id. The constructor, not {@code createType} — see {@link QuestSyncPayload#TYPE}. */
    public static final CustomPacketPayload.Type<ChapterReplicaChunk> TYPE =
            new CustomPacketPayload.Type<>(Identifier.fromNamespaceAndPath("tenet", "chapter_replica_chunk"));

    public static final StreamCodec<? super RegistryFriendlyByteBuf, ChapterReplicaChunk> CODEC =
            StreamCodec.composite(
                    ByteBufCodecs.stringUtf8(64), ChapterReplicaChunk::chapter,
                    SyncChunk.CODEC, ChapterReplicaChunk::chunk,
                    ByteBufCodecs.BYTE_ARRAY, ChapterReplicaChunk::data,
                    ChapterReplicaChunk::new);

    @Override
    public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    /** The two replica strings framed for one transfer: quests length, quests, chapter tree. */
    public static byte[] frame(byte[] quests, byte[] chapterTree) {
        ByteBuffer framed = ByteBuffer.allocate(4 + quests.length + chapterTree.length);
        framed.putInt(quests.length);
        framed.put(quests);
        framed.put(chapterTree);
        return framed.array();
    }

    /** The framed strings, split back apart; empty when the frame is corrupt. */
    public record Framed(String quests, String chapterTree) {
    }

    /**
     * Splits a framed transfer back into its two strings.
     *
     * <p>Empty rather than throwing, because this runs on the packet's arrival: corruption here is
     * a message to log and ignore — the same treatment {@code completed} gives a malformed tree —
     * not a reason to unwind the connection. A frame that lies about its lengths is corruption,
     * not a version to tolerate.
     */
    public static Optional<Framed> split(byte[] framed) {
        if (framed.length < 4) {
            return Optional.empty();
        }
        int questsLength = ByteBuffer.wrap(framed).getInt();
        if (questsLength < 0 || questsLength > framed.length - 4) {
            return Optional.empty();
        }
        return Optional.of(new Framed(
                new String(framed, 4, questsLength, StandardCharsets.UTF_8),
                new String(framed, 4 + questsLength, framed.length - 4 - questsLength,
                        StandardCharsets.UTF_8)));
    }
}
