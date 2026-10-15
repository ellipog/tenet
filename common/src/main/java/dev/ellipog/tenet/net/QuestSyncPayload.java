package dev.ellipog.tenet.net;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

/**
 * The quest tree: one chunk of it, plus how many quests and chapters the whole tree holds.
 *
 * <h2>One payload, and the counts are not redundant</h2>
 *
 * <p>The counts ride on every chunk rather than only the last because the client uses them before it
 * has parsed anything — "20 quests in 4 chapters" is shown while the rest arrives, and the client can
 * tell a server with no quests from a server whose message has not finished. Recomputing them from
 * the tree would only be possible at the end, which is the moment they stop being useful.
 *
 * <h2>What changed here, and why the first version was wrong</h2>
 *
 * <p>This used to carry the whole tree as one {@code BYTE_ARRAY}, uncompressed. That is correct for
 * the shipped example questline and stops being correct at the scale the plan is written for: a pack
 * with a few thousand quests is a multi-megabyte packet, and vanilla's own fallback codec for an
 * unknown payload — {@code MAX_PAYLOAD_SIZE = 1048576} — says plainly what a payload is expected to
 * hold. The limit is not enforced against a registered payload, which is the trap: nothing throws,
 * and the failure appears as a stalled or dropped connection with no size in the message.
 *
 * <p>So the field is a chunk of a packed message, and {@link SyncChunk} says where it sits. The
 * reassembled bytes are deflated JSON, and {@link SyncWire#unpack} turns them back into the shape the
 * client's parser already reads — so the client's parsing code did not have to change at all, which
 * is the property that made this a safe thing to do to a working wire format.
 *
 * <h2>Why the codec is declared as {@code ? super RegistryFriendlyByteBuf}</h2>
 *
 * <p>Unchanged from the first version, and the reason is now load-bearing in a second place:
 * {@code ByteBufCodecs.BYTE_ARRAY} is defined over {@code ByteBuf}, not
 * {@code RegistryFriendlyByteBuf}, so {@code StreamCodec.composite} infers {@code ByteBuf} as the
 * buffer type. Declaring the field as {@code StreamCodec<RegistryFriendlyByteBuf, ...>} therefore
 * does not compile, even though {@code ByteBuf} is a supertype of it. {@code ? super} matches both
 * what comes out and what both loaders want — Fabric's {@code playS2C().register} and NeoForge's
 * {@code playToClient} both take {@code StreamCodec<? super RegistryFriendlyByteBuf, T>}.
 *
 * @param questCount   how many quests the whole tree holds, not just this chunk
 * @param chapterCount how many chapters, for a summary the client can show before parsing
 * @param chunk        where this chunk sits in the message
 * @param data         this chunk's packed bytes — see {@link SyncWire#pack}
 */
public record QuestSyncPayload(int questCount, int chapterCount, String packTheme, SyncChunk chunk,
                                byte[] data)
        implements CustomPacketPayload {

    /**
     * Absent becomes the empty string, and the empty string means absent.
     *
     * <p>A stream codec has no null for a string: {@code STRING_UTF8} writes a length and then bytes, so
     * a null would be a length of zero anyway. Normalising it here means the field can never be null
     * inside the process, so no reader has to decide what a null means — and "no theme" has exactly one
     * spelling rather than two that behave the same and compare differently.
     */
    public QuestSyncPayload {
        packTheme = packTheme == null ? "" : packTheme;
    }

    /** Whether the pack asked for a theme at all. */
    public boolean hasTheme() {
        return !packTheme.isEmpty();
    }

    /**
     * The payload's id, <b>not</b> built with {@code CustomPacketPayload.createType}.
     *
     * <p>That method looks like the right one and is not. Its bytecode is
     * {@code new Type(Identifier.withDefaultNamespace(name))} — it takes a <i>path</i> and
     * hardcodes the {@code minecraft} namespace, because it exists for vanilla's own payloads. So
     * passing it {@code "tenet:quest_sync"} produces the location {@code minecraft:tenet:quest_sync},
     * and the colon inside the path throws:
     *
     * <pre>Non [a-z0-9/._-] character in path of location: minecraft:tenet:quest_sync</pre>
     *
     * <p>The constructor takes a real {@link Identifier} and accepts a namespace, which is what
     * a mod's payload needs. Worth reading as a rule: when a factory method in vanilla takes a string
     * and the error names a location you did not write, check whether it prepends a namespace.
     */
    public static final CustomPacketPayload.Type<QuestSyncPayload> TYPE =
            new CustomPacketPayload.Type<>(Identifier.fromNamespaceAndPath("tenet", "quest_sync"));

    public static final StreamCodec<? super RegistryFriendlyByteBuf, QuestSyncPayload> CODEC =
            StreamCodec.composite(
                    ByteBufCodecs.VAR_INT, QuestSyncPayload::questCount,
                    ByteBufCodecs.VAR_INT, QuestSyncPayload::chapterCount,
                    // Sent as a plain string with "" for absent, rather than as an optional. A stream
                    // codec's optional costs a boolean on the wire and a generic type argument at this
                    // call site, and the composite's type inference here is already delicate enough
                    // that its own comment warns about `ByteBuf` versus `RegistryFriendlyByteBuf`. One
                    // sentinel that every reader understands is cheaper than a discussion about which
                    // overload the compiler picked.
                    ByteBufCodecs.STRING_UTF8, QuestSyncPayload::packTheme,
                    SyncChunk.CODEC, QuestSyncPayload::chunk,
                    ByteBufCodecs.BYTE_ARRAY, QuestSyncPayload::data,
                    QuestSyncPayload::new);

    @Override
    public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
