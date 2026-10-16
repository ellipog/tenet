package dev.ellipog.tenet.net;

import dev.ellipog.tenet.progress.ClaimFilter;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

/**
 * A player pressing the rewards panel's footer button.
 *
 * <h2>Why this carries a filter and nothing else</h2>
 *
 * <p>Which quests are owed is the server's to decide, exactly as it is for {@link ClaimRewardPayload}:
 * a payload naming quests would be a client deciding what it is owed, and {@code
 * ProgressService.claimAll} asks the same {@code canClaimFor} the single claim does rather than
 * trusting a list.
 *
 * <p>The one thing it does carry is the panel's active filter chip, because the footer button follows
 * it: "Claim items" while the list shows items must not reach past what the player can see. The filter
 * only narrows — every reward it does reach is validated against stored progress — so the worst a
 * modified client can do with it is claim less.
 *
 * <p>Like the single claim, there is no response payload of its own: the outcome arrives as the
 * progress sync and as {@link ClaimSummaryPayload}.
 *
 * @param filter what the sweep is allowed to touch
 */
public record ClaimAllPayload(ClaimFilter filter) implements CustomPacketPayload {

    /**
     * The payload's id. The constructor, not {@code createType} — see
     * {@link QuestSyncPayload#TYPE} for why that method silently rewrites the namespace.
     */
    public static final CustomPacketPayload.Type<ClaimAllPayload> TYPE =
            new CustomPacketPayload.Type<>(Identifier.fromNamespaceAndPath("tenet", "claim_all"));

    public static final StreamCodec<? super RegistryFriendlyByteBuf, ClaimAllPayload> CODEC =
            StreamCodec.composite(
                    ByteBufCodecs.VAR_INT.map(ClaimFilter::byOrdinal, ClaimFilter::ordinal),
                    ClaimAllPayload::filter,
                    ClaimAllPayload::new);

    public ClaimAllPayload {
        if (filter == null) {
            filter = ClaimFilter.ALL;
        }
    }

    @Override
    public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
