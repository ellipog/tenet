package dev.ellipog.tenet.net;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

import java.util.List;

/**
 * The lists the editor searches that this server has, sent to a client so the pickers can offer them.
 *
 * <h2>The rule this payload exists to keep</h2>
 *
 * <p><b>A list may only be read from a registry the client is sent.</b> A client's registry access is
 * two layers — the static registries the game builds at startup, and the ones the server sends on join —
 * and 1.21.1 sends eleven of them: the biomes, the chat types, the trim patterns and materials, the wolf
 * and painting variants, the dimension types, the damage types, the banner patterns, the enchantments and
 * the jukebox songs. Everything else a datapack defines — a structure, a structure set, a placed feature,
 * a level stem — is on the server and nowhere else on a client, in single player as much as on a dedicated
 * server, because the client half of single player is still a client with a connection to talk over.
 *
 * <p>Reading one of those from {@code minecraft.level.registryAccess()} does not throw. It answers an
 * empty registry, so the picker opens with no rows and looks like a field nothing can fill — which is
 * exactly how the structure list shipped. The alternatives to this payload are both worse: a hardcoded
 * vanilla list would be a lie about a pack's own structures, and a text box is a quiz rather than an
 * editor.
 *
 * <h2>What is in it, and why nothing more</h2>
 *
 * <p>Ids only, and a tag as {@code #namespace:path} — the two spellings a {@code RegistryRef} field takes.
 * A list carries no icon and no name a data-driven client can render beyond its id, so a payload carrying
 * more would be carrying a copy of something the client would prettify anyway.
 *
 * <p>Sent on join, with the tree and the roster. A list added by a datapack reload arrives on the next
 * join rather than mid-session, which is the same deal the tree makes and worth knowing rather than
 * discovering.
 */
public record ServerListsPayload(List<String> dimensions, List<String> structures)
        implements CustomPacketPayload {

    /**
     * The payload's id. The constructor, not {@code createType} — see
     * {@link QuestSyncPayload#TYPE} for why that method silently rewrites the namespace.
     */
    public static final CustomPacketPayload.Type<ServerListsPayload> TYPE =
            new CustomPacketPayload.Type<>(
                    Identifier.fromNamespaceAndPath("tenet", "server_lists"));

    /**
     * Both lists as one codec.
     *
     * <p>256 characters an id, which is the cap the dimension list already used and is far past any
     * legitimate one: a resource location's path is bounded by what fits in a namespace and a path in
     * practice, and a structure id is the longest thing either list holds.
     */
    public static final StreamCodec<? super RegistryFriendlyByteBuf, ServerListsPayload> CODEC =
            StreamCodec.composite(
                    ByteBufCodecs.stringUtf8(256).apply(ByteBufCodecs.list()),
                    ServerListsPayload::dimensions,
                    ByteBufCodecs.stringUtf8(256).apply(ByteBufCodecs.list()),
                    ServerListsPayload::structures,
                    ServerListsPayload::new);

    public ServerListsPayload {
        dimensions = List.copyOf(dimensions);
        structures = List.copyOf(structures);
    }

    @Override
    public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
