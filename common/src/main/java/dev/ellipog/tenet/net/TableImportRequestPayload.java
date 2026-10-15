package dev.ellipog.tenet.net;

import dev.ellipog.tenet.editor.TableAddress;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

/**
 * A client asking the server to fill a table from an inventory.
 *
 * <h2>Why this is a request rather than a batch of items</h2>
 *
 * <p>Because the items do not need to travel. {@code EditorOpPayload} is a client-to-server string under
 * {@code ServerboundCustomPayloadPacket}'s 32767-byte cap, and a chest of shulker boxes or written books
 * clears that on its own — the components alone are bigger than the limit. So the client says <i>which</i>
 * inventory and the server reads it, which also means the numbers are the server's own and no client can
 * import items it does not have.
 *
 * @param address  the table to fill
 * @param container true for the container the player is looking at, false for their own inventory
 */
public record TableImportRequestPayload(TableAddress address, boolean container)
        implements CustomPacketPayload {

    /** The payload's id. The constructor, not {@code createType} — see {@link QuestSyncPayload#TYPE}. */
    public static final CustomPacketPayload.Type<TableImportRequestPayload> TYPE =
            new CustomPacketPayload.Type<>(
                    Identifier.fromNamespaceAndPath("tenet", "table_import_request"));

    public static final StreamCodec<? super RegistryFriendlyByteBuf, TableImportRequestPayload> CODEC =
            StreamCodec.composite(
                    TableAddress.CODEC, TableImportRequestPayload::address,
                    ByteBufCodecs.BOOL, TableImportRequestPayload::container,
                    TableImportRequestPayload::new);

    @Override
    public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
