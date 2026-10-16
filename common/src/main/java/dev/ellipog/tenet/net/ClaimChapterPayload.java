package dev.ellipog.tenet.net;

import dev.ellipog.tenet.progress.ClaimFilter;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

/**
 * Every outstanding reward of one chapter, collected from the claim menu's banner.
 *
 * <p>The fourth rung of the same ladder: {@link ClaimRewardEntryPayload} is one reward,
 * {@link ClaimRewardPayload} one quest, this one a chapter, and {@link ClaimAllPayload} the whole book.
 * Like every client-to-server payload here it is a request and not a statement — the chapter is a
 * <b>scope</b>, not a list of quests, and the server resolves it against its own files and then applies
 * the same {@code canClaimFor} the single claim applies. A forged id therefore buys a modified client
 * nothing: it can name a chapter it could have pressed a button for, and that is all.
 *
 * <h2>Why a chapter id and not a filter, and why a payload and not a field</h2>
 *
 * <p>{@link ClaimAllPayload} carries a filter and deliberately nothing else, because a payload naming
 * quests would be a client deciding what it is owed. A chapter id does not break that rule — which of
 * its quests are owed stays the server's to decide — but it is a different question from "what kind of
 * reward", so it is a different message rather than a second field on one whose contract is already
 * stated as narrow.
 *
 * <h2>Why it carries the filter as well</h2>
 *
 * <p>Because the menu's banner is drawn in a view, and the view can be *Choices pending*. A press that
 * reached past what the player can see would break the rule the whole card is built on — the footer's
 * sweep follows the view, so a banner's must too — and the first version of this payload did exactly
 * that: in the choices view, Claim Chapter handed over item rewards that were not on screen. The filter
 * only ever <b>narrows</b>: every reward it does reach is still checked against the stored progress, so
 * the worst a forged value can do is claim less than the player asked for.
 *
 * <p>There is no response payload of its own, exactly as for the other three: the outcome arrives as
 * the progress sync, and a sweep that stopped early as {@link ClaimSummaryPayload}.
 *
 * @param chapterId the chapter whose rewards are being collected, by id
 * @param filter    what the sweep is allowed to touch — the view the banner was pressed in
 */
public record ClaimChapterPayload(String chapterId, ClaimFilter filter) implements CustomPacketPayload {

    /**
     * The payload's id. The constructor, not {@code createType} — see
     * {@link QuestSyncPayload#TYPE} for why that method silently rewrites the namespace.
     */
    public static final CustomPacketPayload.Type<ClaimChapterPayload> TYPE =
            new CustomPacketPayload.Type<>(
                    Identifier.fromNamespaceAndPath("tenet", "claim_chapter"));

    public static final StreamCodec<? super RegistryFriendlyByteBuf, ClaimChapterPayload> CODEC =
            StreamCodec.composite(
                    ByteBufCodecs.stringUtf8(64), ClaimChapterPayload::chapterId,
                    ByteBufCodecs.VAR_INT.map(ClaimFilter::byOrdinal, ClaimFilter::ordinal),
                    ClaimChapterPayload::filter,
                    ClaimChapterPayload::new);

    public ClaimChapterPayload {
        if (chapterId == null) {
            // A blank id matches no chapter, so the sweep claims nothing and says nothing. The
            // alternative -- refusing the packet -- would be a disconnection over a message whose
            // worst outcome is already nothing.
            chapterId = "";
        }
        if (filter == null) {
            filter = ClaimFilter.ALL;
        }
    }

    @Override
    public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
