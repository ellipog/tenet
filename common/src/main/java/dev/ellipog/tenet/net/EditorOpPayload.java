package dev.ellipog.tenet.net;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

/**
 * One edit, from a client that wants it to a server that decides.
 *
 * <h2>A request, like every other payload here</h2>
 *
 * <p>It carries the chapter and the operation and <b>no authority at all</b>: the server checks the sender's
 * permission, opens its own model of that chapter and applies the op to <i>its</i> files. A modified client
 * can send any op it likes and gets exactly what an honest one gets — a refusal unless it may edit — which is
 * the property the design's "the server is the authority" line is for.
 *
 * <p>The op travels as JSON text written by {@link dev.ellipog.tenet.editor.EditorOps}, which is the same
 * code that reads it back: the two ends cannot disagree about what an op is, because there is one description
 * of it in the mod and this payload only carries the bytes.
 *
 * @param chapter   the chapter's id, as the loader names it
 * @param op        the operation, as {@code EditorOps.write} produced it
 * @param requestId which request this is, echoed in the reply, or <b>0</b> when the caller has none
 */
public record EditorOpPayload(String chapter, String op, long requestId) implements CustomPacketPayload {

    /** The payload's id. The constructor, not {@code createType} — see {@link QuestSyncPayload#TYPE}. */
    public static final CustomPacketPayload.Type<EditorOpPayload> TYPE =
            new CustomPacketPayload.Type<>(Identifier.fromNamespaceAndPath("tenet", "editor_op"));

    public static final StreamCodec<? super RegistryFriendlyByteBuf, EditorOpPayload> CODEC =
            StreamCodec.composite(
                    ByteBufCodecs.stringUtf8(64), EditorOpPayload::chapter,
                    // One op, not one chapter: a set field is a path and a value. The cap is generous on
                    // purpose -- a `SetField` carrying a whole description, or a paste of a large quest,
                    // is a legitimate op, and the old 8 KiB ceiling threw on encode, which is a click that
                    // dies with no message anywhere. 256 KiB holds any single field a person could type.
                    ByteBufCodecs.stringUtf8(262144), EditorOpPayload::op,
                    // The id the reply echoes, or 0 for a caller with none. It travels with the op rather
                    // than beside it because the two have to be written together: an op whose id was
                    // recorded but not sent -- or sent but not recorded -- is answered by a reply matched
                    // to the wrong request. See `EditorReplyPayload.requestId`.
                    ByteBufCodecs.VAR_LONG, EditorOpPayload::requestId,
                    EditorOpPayload::new);

    /** The same op with no request id, for a caller that has none to give. */
    public EditorOpPayload(String chapter, String op) {
        this(chapter, op, EditorReplyPayload.NO_REQUEST);
    }

    @Override
    public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
