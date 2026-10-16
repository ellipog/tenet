package dev.ellipog.tenet.net;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

/**
 * One locale's quest text, sent to the one player who asked for it.
 *
 * <h2>Why this is not on the tree</h2>
 *
 * <p>Because the tree is <b>broadcast</b> and a locale is not. Every player is sent the same questline
 * — that is what makes one encode serve everybody — and putting fifteen locales on it would send every
 * player fourteen they cannot read, on the message that is already the largest one the mod sends. The
 * tree carries the canonical strings and this carries the overlay, to one player, on its own channel.
 *
 * <h2>Why it carries two locale ids</h2>
 *
 * <p>{@code locale} is what the player asked for — the language their client is set to, or what the
 * server read from the login handshake. {@code served} is the file the server actually used, which may
 * be a relative of it: an {@code es_mx} player reading a pack with only {@code es_es.json} is served
 * {@code es_es}. See {@code QuestLanguages.servedLocale}.
 *
 * <p>Both travel because the client has to compare the right one. It settles on {@code locale} — the
 * language it actually has selected — so a player served a regional relative is not left asking again
 * forever for a locale the pack was never going to have. {@code served} is the answer to "what am I
 * reading", which is worth being able to say.
 *
 * <h2>An empty overlay is a real message</h2>
 *
 * <p>It is sent even when the pack has no file for the locale, and that is not a wasted packet: it is
 * how the client learns it is current, and it is what <b>clears</b> a locale the player has just
 * switched away from. Without it, moving from Spanish to a language the pack does not translate would
 * leave the Spanish text on screen.
 *
 * @param locale what the player asked for, normalised
 * @param served the locale actually used, or empty when the pack has none for it
 * @param chunk  where this chunk sits in the message
 * @param data   this chunk's packed bytes — see {@link SyncWire#pack}
 */
public record LocaleSyncPayload(String locale, String served, SyncChunk chunk, byte[] data)
        implements CustomPacketPayload {

    /**
     * Absent becomes the empty string, and the empty string means absent.
     *
     * <p>The same normalisation {@code QuestSyncPayload} makes for its theme, and for the same reason:
     * a stream codec has no null for a string, so normalising here means no reader has to decide what a
     * null means, and "no locale" has exactly one spelling.
     */
    public LocaleSyncPayload {
        locale = locale == null ? "" : locale;
        served = served == null ? "" : served;
    }

    /**
     * The payload's id. The constructor, not {@code createType} — see
     * {@link QuestSyncPayload#TYPE} for why that method silently rewrites the namespace.
     */
    public static final CustomPacketPayload.Type<LocaleSyncPayload> TYPE =
            new CustomPacketPayload.Type<>(Identifier.fromNamespaceAndPath("tenet", "locale_sync"));

    public static final StreamCodec<? super RegistryFriendlyByteBuf, LocaleSyncPayload> CODEC =
            StreamCodec.composite(
                    // Short by construction: a locale id is `xx_yy` or `xx`. The bound is the codec's
                    // own, so a hand-forged packet cannot ask for a megabyte-long locale name.
                    ByteBufCodecs.stringUtf8(32), LocaleSyncPayload::locale,
                    ByteBufCodecs.stringUtf8(32), LocaleSyncPayload::served,
                    SyncChunk.CODEC, LocaleSyncPayload::chunk,
                    ByteBufCodecs.BYTE_ARRAY, LocaleSyncPayload::data,
                    LocaleSyncPayload::new);

    @Override
    public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
