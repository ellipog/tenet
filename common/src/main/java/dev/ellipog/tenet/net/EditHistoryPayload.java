package dev.ellipog.tenet.net;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

/**
 * Told that the server's undo history is gone.
 *
 * <h2>Why the client cannot work this out for itself</h2>
 *
 * <p>The undo history belongs to the server — {@code ServerEditors} holds it, and an undo is an op like any
 * other. But the <b>affordance</b> is the client's: the book decides whether to draw its undo button, and
 * whether a Ctrl+Z is worth sending, from counters it keeps itself and moves on each reply it gets. Those
 * counters are a mirror of the server's stack, and nothing else tells the client when the mirror is wrong.
 *
 * <p><b>What that cost.</b> {@code /tenet reload} drops every open editor, deliberately: it is the one
 * gesture that says "the files changed without me", and a history recorded against a model of the files that
 * no longer holds would undo to a state that never existed. The command then broadcasts the new tree, so the
 * canvas is right — but the client's counters are untouched, so the book went on drawing a live undo button
 * over a history that had been thrown away. Pressing it sent an {@code Undo} the server had nothing to answer
 * with: nothing happened, nothing was said, and the button stayed live. An author reads that as a broken
 * Ctrl+Z rather than as "your history was discarded", which is the one thing it meant.
 *
 * <p>A server restart has the same effect and needs no message for it: the client is disconnected, so its
 * screen is closed and its counters go with it. That is why this is sent by the reload command rather than
 * watched for.
 *
 * <p><b>Why a payload rather than a line on an existing one.</b> {@code EditorReplyPayload} carries an
 * answer to a <i>request</i>, and this is not one: it is a statement about the server's state, sent to
 * everyone editing rather than to whoever asked last. Putting it on the reply would mean inventing a request
 * to hang it on, and matching it by position — the thing the reply's request id exists to stop doing.
 *
 * <p>One boolean and no chapter, because the history is the server's and the reload drops all of it: a
 * per-chapter notice would be a claim the server cannot make, since it discarded every chapter's stack at
 * once.
 *
 * @param discarded always true. A boolean rather than an empty payload so that a later "history was kept"
 *                  needs no second type, and so a reader of the wire sees what it is about.
 */
public record EditHistoryPayload(boolean discarded) implements CustomPacketPayload {

    /** The payload's id. The constructor, not {@code createType} — see {@link QuestSyncPayload#TYPE}. */
    public static final CustomPacketPayload.Type<EditHistoryPayload> TYPE =
            new CustomPacketPayload.Type<>(Identifier.fromNamespaceAndPath("tenet", "edit_history"));

    public static final StreamCodec<? super RegistryFriendlyByteBuf, EditHistoryPayload> CODEC =
            StreamCodec.composite(
                    ByteBufCodecs.BOOL, EditHistoryPayload::discarded,
                    EditHistoryPayload::new);

    @Override
    public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
