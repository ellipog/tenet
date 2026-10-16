package dev.ellipog.tenet.net;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

/**
 * The server telling a client to open a table's editor.
 *
 * <h2>Why this exists rather than the command opening a screen</h2>
 *
 * <p>A command runs on the server, and a server cannot open a client's screen: {@code /tenet table
 * edit} has to say what it wants and let the client do it. That is this message — sent only to a player
 * who may edit, and only after the command has checked the table exists, so a typo is a sentence in chat
 * rather than a modal that can only show a refusal.
 *
 * @param table the table's id
 */
public record TableOpenPayload(String table) implements CustomPacketPayload {

    /** The payload's id. The constructor, not {@code createType} — see {@link QuestSyncPayload#TYPE}. */
    public static final CustomPacketPayload.Type<TableOpenPayload> TYPE =
            new CustomPacketPayload.Type<>(Identifier.fromNamespaceAndPath("tenet", "table_open"));

    public static final StreamCodec<? super RegistryFriendlyByteBuf, TableOpenPayload> CODEC =
            StreamCodec.composite(
                    ByteBufCodecs.stringUtf8(128), TableOpenPayload::table,
                    TableOpenPayload::new);

    @Override
    public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
