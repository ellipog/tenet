package dev.ellipog.tenet.net;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

/**
 * What the server did about an edit: yes or no, and the words for it.
 *
 * <h2>Why this one answers, when the claim payload does not</h2>
 *
 * <p>Because an edit has more than one interesting outcome and one of them is a question the client asked.
 * A claim either happens or not, and both are visible in the progress sync that follows; an edit can be
 * refused for a reason the author has to read — the validator's own message, at the line it found — and can
 * <i>make something</i>, whose id the client cannot guess. So the reply carries the outcome, the id of a quest
 * that was created or duplicated, and the messages, which are the same ones the model produced.
 *
 * <p>The tree still arrives separately, as a reload broadcast, and this payload deliberately carries none of
 * it: the files are the server's answer to "what is the questline now", and a second copy of that answer on
 * this payload would be a second thing to keep true.
 *
 * @param chapter  the chapter the edit was about, so a client with two open can tell them apart
 * @param ok       whether it was applied and written
 * @param questId  the quest a create or a duplicate made, or "" when the op made none
 * @param messages the refusal's reasons, one per line, or "" when there were none
 * @param requestId which request this answers, or <b>0</b> when the server does not know
 *
 * <h2>Why a request id, when position matching worked</h2>
 *
 * <p>Because position matching only works while the two ends agree about how many requests are in flight, and
 * they cannot. The client's marker queue is bounded, so a burst that outruns the answers either drops a
 * marker — which mis-aligns every answer after it, silently — or refuses to send, which loses an edit. And a
 * reply that carries a chapter's <i>problems</i> (a dangling dependency, a cycle) has to be matched to the
 * request that asked rather than to whichever request is next in a queue.
 *
 * <p><b>Zero means "no id"</b> rather than an optional field: a stream codec's optional costs a boolean on the
 * wire and a generic argument at every use, and this payload already sends its absent values as empty strings
 * for the same reason. A zero is never a real id, because the client's ids start at one.
 */
public record EditorReplyPayload(String chapter, boolean ok, String questId, String messages, long requestId)
        implements CustomPacketPayload {

    /** The payload's id. The constructor, not {@code createType} — see {@link QuestSyncPayload#TYPE}. */
    public static final CustomPacketPayload.Type<EditorReplyPayload> TYPE =
            new CustomPacketPayload.Type<>(Identifier.fromNamespaceAndPath("tenet", "editor_reply"));

    /** A reply to a request that carried no id, which is what a server that does not echo one produces. */
    public static final long NO_REQUEST = 0L;

    public static final StreamCodec<? super RegistryFriendlyByteBuf, EditorReplyPayload> CODEC =
            StreamCodec.composite(
                    ByteBufCodecs.stringUtf8(64), EditorReplyPayload::chapter,
                    ByteBufCodecs.BOOL, EditorReplyPayload::ok,
                    ByteBufCodecs.stringUtf8(64), EditorReplyPayload::questId,
                    // A validator can produce a paragraph of messages; one string with newlines, because the
                    // feedback line shows one and the chat shows them one after another.
                    ByteBufCodecs.stringUtf8(4096), EditorReplyPayload::messages,
                    ByteBufCodecs.VAR_LONG, EditorReplyPayload::requestId,
                    EditorReplyPayload::new);

    /**
     * The same reply with no request id, for a caller that has none to give.
     *
     * <p>Kept so the two-argument-and-two-string shape stays readable at the call sites that answer a
     * broadcast rather than a request — a refusal nobody asked for is still news, and it is matched by
     * position as it always was.
     */
    public EditorReplyPayload(String chapter, boolean ok, String questId, String messages) {
        this(chapter, ok, questId, messages, NO_REQUEST);
    }

    /** The messages as a list, which is how both readers of them want them. */
    public java.util.List<String> lines() {
        return messages == null || messages.isEmpty()
                ? java.util.List.of()
                : java.util.List.of(messages.split("\n"));
    }

    @Override
    public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
