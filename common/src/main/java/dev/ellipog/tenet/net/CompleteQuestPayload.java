package dev.ellipog.tenet.net;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/**
 * An author pressing Complete on quests in the quest book.
 *
 * <h2>What the client is allowed to say, and what it is not</h2>
 *
 * <p>This carries quest ids and nothing else — the same trust shape as {@link ResetQuestPayload}.
 * It is a <b>request</b>, not a statement of fact: the client does not say "this quest is complete"
 * and could not be believed if it did ({@link SubmitTaskPayload} explains why a client asserting
 * progress is a cheat vector). It says "I would like quest X completed", and the server resolves
 * each id against its own index, re-checks the permission and walks the command's own guards
 * before anything lands.
 *
 * <p>Unlike a reset, which clears, this grants — but it is playtesting-only, undone by one Reset
 * press, so like Reset it needs no arming. It asks for {@link dev.ellipog.tenet.QuestAuthority#mayEdit},
 * the same level {@code /tenet complete} declares.
 *
 * <p>Rewards land as waiting-to-claim rather than handed over, exactly as the command leaves
 * them: collecting is a separate act, and this press is about reaching a quest, not enriching
 * whoever pressed it.
 *
 * <p>There is no response payload; success and failure are both learned from the progress sync
 * that follows, plus a toast where the author is looking — the same pair every other press sends.
 *
 * @param questId           the quest, by id or alias
 * @param withDependencies  whether the quest's required dependencies complete with it, recursively,
 *                          until the chain is playable — the menu's "Complete with dependencies"
 */
public record CompleteQuestPayload(String questId, boolean withDependencies)
        implements CustomPacketPayload {

    /** A blank id matches no quest, so the press completes nothing. See {@link ResetQuestPayload}. */
    public CompleteQuestPayload {
        questId = questId == null ? "" : questId;
    }

    /**
     * The payload's id. The constructor, not {@code createType} — see
     * {@link QuestSyncPayload#TYPE} for why that method silently rewrites the namespace.
     */
    public static final CustomPacketPayload.Type<CompleteQuestPayload> TYPE =
            new CustomPacketPayload.Type<>(ResourceLocation.fromNamespaceAndPath("tenet", "complete_quest"));

    public static final StreamCodec<? super RegistryFriendlyByteBuf, CompleteQuestPayload> CODEC =
            StreamCodec.composite(
                    ByteBufCodecs.stringUtf8(64), CompleteQuestPayload::questId,
                    ByteBufCodecs.BOOL, CompleteQuestPayload::withDependencies,
                    CompleteQuestPayload::new);

    @Override
    public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
