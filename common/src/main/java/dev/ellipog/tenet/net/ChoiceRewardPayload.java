package dev.ellipog.tenet.net;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

import java.util.List;

/**
 * A choice reward's entries, offered to the client that just claimed it.
 *
 * <p>The reward is not marked claimed when this is sent — it stays outstanding until the answer comes
 * back, which is why a crash between the two loses nothing. The client renders the entries and
 * replies with {@link ClaimChoicePayload}; the server grants the chosen one and records the claim.
 *
 * <p>Only the display of each entry travels: an item id, a count, and a label pair. The rewards
 * themselves are the server's to resolve, and a payload that carried them would be a payload a
 * modified client could edit.
 *
 * @param questId     the quest the reward belongs to
 * @param rewardIndex which of that quest's rewards this offer answers
 * @param entries     the table's entries, in the order the picker must show them
 */
public record ChoiceRewardPayload(String questId, int rewardIndex, List<Entry> entries)
        implements CustomPacketPayload {

    /**
     * One entry, as the picker draws it.
     *
     * <p>{@code labelArg} is the subject the label's translation key is formatted with — the table an
     * entry rolls, the stage it grants. It was missing here, and the card filled the gap with the entry's
     * <i>count</i>: a nested table entry therefore read "Roll the 1 table" instead of naming the table it
     * rolls. The reward inbox has carried all four of these since it was written; this is the same four.
     */
    public record Entry(String item, int count, String label, String labelFallback, String labelArg) {

        // The exact buffer type, not `? super`: `apply(ByteBufCodecs.list())` needs the codec's own
        // type to line up with the list operation, and the wildcard breaks that inference.
        public static final StreamCodec<RegistryFriendlyByteBuf, Entry> CODEC =
                StreamCodec.composite(
                        ByteBufCodecs.stringUtf8(256), Entry::item,
                        ByteBufCodecs.VAR_INT, Entry::count,
                        ByteBufCodecs.stringUtf8(512), Entry::label,
                        ByteBufCodecs.stringUtf8(512), Entry::labelFallback,
                        ByteBufCodecs.stringUtf8(512), Entry::labelArg,
                        Entry::new);
    }

    public static final CustomPacketPayload.Type<ChoiceRewardPayload> TYPE =
            new CustomPacketPayload.Type<>(
                    Identifier.fromNamespaceAndPath("tenet", "choice_reward"));

    public static final StreamCodec<? super RegistryFriendlyByteBuf, ChoiceRewardPayload> CODEC =
            StreamCodec.composite(
                    ByteBufCodecs.stringUtf8(64), ChoiceRewardPayload::questId,
                    ByteBufCodecs.VAR_INT, ChoiceRewardPayload::rewardIndex,
                    Entry.CODEC.apply(ByteBufCodecs.list()), ChoiceRewardPayload::entries,
                    ChoiceRewardPayload::new);

    public ChoiceRewardPayload {
        entries = List.copyOf(entries);
    }

    @Override
    public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
