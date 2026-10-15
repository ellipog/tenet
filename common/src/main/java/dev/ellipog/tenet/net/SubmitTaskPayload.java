package dev.ellipog.tenet.net;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

/**
 * A player pressing a task in the quest book.
 *
 * <h2>The whole of the client's authority</h2>
 *
 * <p>This is the only thing a client can ask the server to do about quests, and it asks for exactly
 * one thing: "I am submitting task N of quest X". It cannot complete a quest, cannot grant a reward,
 * and cannot claim to have done the task. The server looks up whether that task is unlocked, whether
 * it can be submitted by hand, and whether the items are actually there — the same checks
 * {@code /tenet submit} runs.
 *
 * <p>That is why there is no "complete this quest" payload. A client that can assert progress is a
 * client that can be made to assert progress it did not earn, and syncing a claimed result is a cheat
 * vector with extra steps.
 *
 * <p>The quest id is a bounded string rather than a free one. A client can send anything, so the codec
 * refuses anything that could not be an id before it reaches a lookup.
 *
 * @param questId   the quest, by id or alias
 * @param taskIndex which task in it
 */
public record SubmitTaskPayload(String questId, int taskIndex) implements CustomPacketPayload {

    /**
     * The payload's id. The constructor, not {@code createType} — see
     * {@link QuestSyncPayload#TYPE} for why that method silently rewrites the namespace.
     */
    public static final CustomPacketPayload.Type<SubmitTaskPayload> TYPE =
            new CustomPacketPayload.Type<>(Identifier.fromNamespaceAndPath("tenet", "submit_task"));

    public static final StreamCodec<? super RegistryFriendlyByteBuf, SubmitTaskPayload> CODEC =
            StreamCodec.composite(
                    ByteBufCodecs.stringUtf8(64), SubmitTaskPayload::questId,
                    ByteBufCodecs.VAR_INT, SubmitTaskPayload::taskIndex,
                    SubmitTaskPayload::new);

    @Override
    public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
