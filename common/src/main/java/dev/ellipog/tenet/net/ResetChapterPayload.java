package dev.ellipog.tenet.net;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

/**
 * An author pressing Reset on one chapter in the quest book.
 *
 * <p>The second rung of the same ladder: {@link ResetQuestPayload} is one quest, this one a
 * chapter, and {@link ResetAllPayload} the whole book. Like every client-to-server payload here it
 * is a request and not a statement — the chapter is a <b>scope</b>, not a list of quests, and the
 * server resolves it against its own files and then clears each member through the same
 * {@code ProgressService.reset} the single quest uses. A forged id therefore buys a modified
 * client nothing: it can name a chapter it could have pressed a button for, and that is all.
 *
 * <p>A chapter id matching nothing — a typo, a renamed chapter, a forged packet — walks the index
 * and clears nothing, which is the honest outcome rather than an error: there is nothing to
 * refuse. The progress sync is still sent, so a stale client is corrected by the progress it
 * already knows how to read.
 *
 * <p>Like the single quest this asks for {@link dev.ellipog.tenet.QuestAuthority#mayEdit}, and for
 * the same reason: it moves the whole team's record.
 *
 * @param chapterId the chapter whose quests are being cleared, by id or alias
 */
public record ResetChapterPayload(String chapterId) implements CustomPacketPayload {

    /** A blank id matches no chapter, so the reset clears nothing and says nothing. */
    public ResetChapterPayload {
        chapterId = chapterId == null ? "" : chapterId;
    }

    /**
     * The payload's id. The constructor, not {@code createType} — see
     * {@link QuestSyncPayload#TYPE} for why that method silently rewrites the namespace.
     */
    public static final CustomPacketPayload.Type<ResetChapterPayload> TYPE =
            new CustomPacketPayload.Type<>(
                    Identifier.fromNamespaceAndPath("tenet", "reset_chapter"));

    public static final StreamCodec<? super RegistryFriendlyByteBuf, ResetChapterPayload> CODEC =
            StreamCodec.composite(
                    ByteBufCodecs.stringUtf8(64), ResetChapterPayload::chapterId,
                    ResetChapterPayload::new);

    @Override
    public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
