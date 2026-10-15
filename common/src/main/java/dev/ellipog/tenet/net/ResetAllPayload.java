package dev.ellipog.tenet.net;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

/**
 * An author pressing Reset on the whole book in the quest book.
 *
 * <p>The third rung of the same ladder: {@link ResetQuestPayload} is one quest,
 * {@link ResetChapterPayload} one chapter, and this one everything — the explicit spelling of a
 * bare {@code /tenet reset}, and the shape a menu press takes where a command takes a missing
 * argument. It carries nothing at all: which quests are held is the server's to decide, exactly as
 * it is for the single reset, and a payload naming quests would be a client deciding what it
 * clears.
 *
 * <p>Like the other two this asks for {@link dev.ellipog.tenet.QuestAuthority#mayEdit}, and the
 * menu that sends it arms first — the one control here whose mistake is a whole book, which is why
 * the issue asked for the confirm on this rung and not on the quest's.
 *
 * <p>There is no response payload of its own: the outcome arrives as the progress sync, which is
 * the same one every other change sends.
 */
public record ResetAllPayload() implements CustomPacketPayload {

    /**
     * The payload's id. The constructor, not {@code createType} — see
     * {@link QuestSyncPayload#TYPE} for why that method silently rewrites the namespace.
     */
    public static final CustomPacketPayload.Type<ResetAllPayload> TYPE =
            new CustomPacketPayload.Type<>(Identifier.fromNamespaceAndPath("tenet", "reset_all"));

    public static final StreamCodec<? super RegistryFriendlyByteBuf, ResetAllPayload> CODEC =
            StreamCodec.unit(new ResetAllPayload());

    @Override
    public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
