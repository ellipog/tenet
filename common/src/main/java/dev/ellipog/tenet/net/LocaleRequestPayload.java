package dev.ellipog.tenet.net;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

/**
 * A client telling the server which language it is now reading in.
 *
 * <h2>Why this message has to exist</h2>
 *
 * <p>The server already knows a player's language when they connect: it arrives in the login handshake
 * and is readable as {@code ServerPlayer.clientInformation().language()}. What it cannot see is a
 * player who changes language <b>mid-session</b> — the option is applied on the client, and no loader
 * exposes a hook for it, so without a message from the client the book would keep the language the
 * player joined with until they reconnected.
 *
 * <p>It carries no content beyond the id: the server looks it up in the locales the pack actually
 * ships and answers with {@link LocaleSyncPayload}. A locale the pack has no file for is not an error
 * and not silence — it is answered with an empty overlay, which is what tells the client it is current
 * and stops it asking again.
 *
 * @param locale the client's selected language, normalised — never a path, only a map key
 */
public record LocaleRequestPayload(String locale) implements CustomPacketPayload {

    /** Absent becomes empty, which the handler reads as "no answer", the same rule every id here has. */
    public LocaleRequestPayload {
        locale = locale == null ? "" : locale;
    }

    /**
     * The payload's id. The constructor, not {@code createType} — see
     * {@link QuestSyncPayload#TYPE} for why that method silently rewrites the namespace.
     */
    public static final CustomPacketPayload.Type<LocaleRequestPayload> TYPE =
            new CustomPacketPayload.Type<>(
                    Identifier.fromNamespaceAndPath("tenet", "locale_request"));

    public static final StreamCodec<? super RegistryFriendlyByteBuf, LocaleRequestPayload> CODEC =
            StreamCodec.composite(
                    // The same 32-byte bound the answer uses. A client is the untrusted end of this
                    // message, so the length is the codec's business rather than the handler's.
                    ByteBufCodecs.stringUtf8(32), LocaleRequestPayload::locale,
                    LocaleRequestPayload::new);

    @Override
    public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
