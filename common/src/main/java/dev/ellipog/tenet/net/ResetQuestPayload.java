package dev.ellipog.tenet.net;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

/**
 * An author pressing Reset on one quest in the quest book.
 *
 * <h2>What the client is allowed to say, and what it is not</h2>
 *
 * <p>This carries a quest id and nothing else. It is a <b>request</b>, not a statement of fact: the
 * client does not say "this quest has no progress" and could not be believed if it did. It says "I
 * would like to clear quest X", and the server decides whether anything was held by looking at its
 * own stored progress — the same {@code ProgressService.reset} that {@code /tenet reset} calls, so
 * a reset that arrives for a quest with no progress clears nothing rather than failing.
 *
 * <p>That is why there is no count and no state on this payload. A payload that could name what it
 * cleared would be a payload a modified client could point at somebody else's book.
 *
 * <p>Unlike a claim, which any player may press for their own rewards, this asks for
 * {@link dev.ellipog.tenet.QuestAuthority#mayEdit} — the same level {@code /tenet reset} declares.
 * Resetting is an operator's act even when the progress being cleared is their own: it moves the
 * whole team's record, which is what makes it playtesting rather than playing.
 *
 * <p>There is no response payload; success and failure are both learned from the progress sync that
 * follows, which is the same one every other change sends.
 *
 * @param questId the quest, by id or alias
 */
public record ResetQuestPayload(String questId) implements CustomPacketPayload {

    /** A blank id matches no quest, so the reset clears nothing. See {@link ResetChapterPayload}. */
    public ResetQuestPayload {
        questId = questId == null ? "" : questId;
    }

    /**
     * The payload's id. The constructor, not {@code createType} — see
     * {@link QuestSyncPayload#TYPE} for why that method silently rewrites the namespace.
     */
    public static final CustomPacketPayload.Type<ResetQuestPayload> TYPE =
            new CustomPacketPayload.Type<>(Identifier.fromNamespaceAndPath("tenet", "reset_quest"));

    public static final StreamCodec<? super RegistryFriendlyByteBuf, ResetQuestPayload> CODEC =
            StreamCodec.composite(
                    ByteBufCodecs.stringUtf8(64), ResetQuestPayload::questId,
                    ResetQuestPayload::new);

    @Override
    public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
