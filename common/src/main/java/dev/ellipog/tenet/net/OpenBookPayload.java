package dev.ellipog.tenet.net;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

/**
 * A command asked for the quest book: open it, on one quest when one is named.
 *
 * <p>The server half of {@code /tenet open_book}. A command runs on the server and a screen opens
 * on the client, so the command sends this to the player it was run for and the client's handler
 * opens the book — on the named quest through {@code QuestBookScreen.openOn}, which ignores a
 * quest the tree does not hold, or plainly when no quest was named. Empty means plain, so one
 * payload serves both spellings.
 *
 * @param questId the quest to show, or empty for the book as it was
 */
public record OpenBookPayload(String questId) implements CustomPacketPayload {

    /** The payload's id. The constructor, not {@code createType} — see {@link QuestSyncPayload#TYPE}. */
    public static final CustomPacketPayload.Type<OpenBookPayload> TYPE =
            new CustomPacketPayload.Type<>(Identifier.fromNamespaceAndPath("tenet", "open_book"));

    public static final StreamCodec<? super RegistryFriendlyByteBuf, OpenBookPayload> CODEC =
            StreamCodec.composite(
                    ByteBufCodecs.STRING_UTF8, OpenBookPayload::questId,
                    OpenBookPayload::new);

    @Override
    public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
