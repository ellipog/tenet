package dev.ellipog.tenet.net;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

/**
 * A party's roster, as it goes to a client.
 *
 * <h2>Sent when the membership changes, and on join</h2>
 *
 * <p>This rides the same pushes as the progress sync, which is what makes it cheap: a team's
 * membership changing is <i>already</i> an event somebody acts on — see {@code Tenet}'s listeners —
 * so a roster update costs one more message on a change that was going to send one anyway, and
 * nothing at all on a tick where nothing moved.
 *
 * <p>A roster cannot be sent only on request, and that is the tempting design: the panel could ask
 * when it opens. What that misses is that the panel is <b>open while the membership changes</b> —
 * somebody accepts an invite, an officer removes somebody — and a roster that only refreshed when the
 * panel was opened would be a panel showing a party that is no longer the one you are in.
 *
 * <h2>Two components, and the id rides the roster</h2>
 *
 * <p>There used to be a third: the team id, as two longs rather than a string, fixed size and no
 * parsing. It was never read — the handler consumes the packed roster alone — and the same id is the
 * first header line {@link PartySnapshot#unpack} reads back, so the payload was carrying a second copy
 * of a fact it already held. The ceiling is six; the roster is one string.
 *
 * @param packed {@link PartySnapshot#pack()} — the roster, as one string
 */
public record PartySyncPayload(String packed) implements CustomPacketPayload {

    /**
     * The payload's id. The constructor, not {@code createType} — that method hardcodes the
     * {@code minecraft} namespace, which produces a startup crash naming a resourcelocator rather than
     * the method that was misused.
     */
    public static final CustomPacketPayload.Type<PartySyncPayload> TYPE =
            new CustomPacketPayload.Type<>(Identifier.fromNamespaceAndPath("tenet", "party_sync"));

    public static final StreamCodec<? super RegistryFriendlyByteBuf, PartySyncPayload> CODEC =
            StreamCodec.composite(
                    // A generous ceiling, and a hard one. The roster is a few hundred bytes for a party
                    // of any plausible size, so this is far above what it needs -- and the point of a
                    // limit is not to be tight, it is to refuse to allocate a string somebody else chose
                    // the length of. The bytes arrive from a server, which is not a stranger, which is
                    // not the same as being safe.
                    ByteBufCodecs.stringUtf8(32768), PartySyncPayload::packed,
                    PartySyncPayload::new);

    @Override
    public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
