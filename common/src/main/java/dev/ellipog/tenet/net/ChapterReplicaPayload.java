package dev.ellipog.tenet.net;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

/**
 * A chapter's quests, whole, on their way to a client that asked for them.
 *
 * <h2>One payload, and the number that says it can be</h2>
 *
 * <p>Measured rather than hoped: {@code ClientboundCustomPayloadPacket.MAX_PAYLOAD_SIZE} is <b>1048576</b>
 * bytes in this version, so a chapter travels as one message. The examples are tens of kilobytes and a large
 * hand-written pack with two hundred quests in it is a few hundred — an order of magnitude under the cap, with
 * the id and a quest's own fields no larger than anybody types. If a pack ever does exceed it the failure is a
 * refusal the client can see, not a truncated chapter, because the codec's limit is the check.
 *
 * <p>Encoded as a JSON object of id to that quest's tree — the same shape the files are in, so the client's
 * copy is a copy rather than a translation.
 *
 * @param chapter     the chapter the quests belong to
 * @param quests      every quest's tree, keyed by id
 * @param chapterTree the chapter's own file -- title, icon, rules, quest list -- as one tree
 */
public record ChapterReplicaPayload(String chapter, String quests, String chapterTree)
        implements CustomPacketPayload {

    /** The payload's id. The constructor, not {@code createType} — see {@link QuestSyncPayload#TYPE}. */
    public static final CustomPacketPayload.Type<ChapterReplicaPayload> TYPE =
            new CustomPacketPayload.Type<>(Identifier.fromNamespaceAndPath("tenet", "chapter_replica"));

    public static final StreamCodec<? super RegistryFriendlyByteBuf, ChapterReplicaPayload> CODEC =
            StreamCodec.composite(
                    ByteBufCodecs.stringUtf8(64), ChapterReplicaPayload::chapter,
                    ByteBufCodecs.stringUtf8(1048576), ChapterReplicaPayload::quests,
                    ByteBufCodecs.stringUtf8(1048576), ChapterReplicaPayload::chapterTree,
                    ChapterReplicaPayload::new);

    @Override
    public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
