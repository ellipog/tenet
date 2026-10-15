package dev.ellipog.tenet.net;

import dev.ellipog.tenet.editor.TableAddress;
import dev.ellipog.tenet.quest.reward.TableReward;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

/**
 * A client asking what a table's dice would do, without granting anything.
 *
 * <h2>Why the server rolls rather than the client</h2>
 *
 * <p>Because a roll is not a property of one file: an entry can point at another table, and resolving
 * "how often did the rare bag come up" needs every table the first one can reach. A client holds the one
 * file it is editing and a summary of the rest, which is enough to draw a table and not enough to roll
 * one — so the editor asks, and the answer is what it draws.
 *
 * <h2>Why the whole mode travels rather than a flag</h2>
 *
 * <p>It sent {@code includeEmpty}, and the server read it as {@code LOOT} or {@code RANDOM} — which
 * collapsed the four readings an author can select into two. With the editor's chip on {@code all_table}
 * or {@code choice}, the report came back as a weighted roll of a table whose own panel said every entry
 * is granted, and the pane printed "rolls as random" underneath that chip. One field with four values
 * cannot lose two of them; a boolean derived from it can, and did.
 *
 * @param address the table, named or inline
 * @param rolls   how many grants to simulate; the server clamps it rather than trusting it
 * @param mode    the reading being previewed, exactly as the editor's chip names it
 */
public record TableRollRequestPayload(TableAddress address, int rolls, TableReward.Mode mode)
        implements CustomPacketPayload {

    /**
     * A mode on the wire: its own spelling, so reordering the enum cannot change what an old client's
     * number means.
     *
     * <p>Throws on a spelling no mode has, which is the same answer {@link TableAddress#CODEC} gives an
     * unknown kind: this is a payload that cannot be read, and the framework's own failure is more
     * honest than guessing a mode and answering a question nobody asked.
     */
    public static final StreamCodec<RegistryFriendlyByteBuf, TableReward.Mode> MODE_CODEC =
            new StreamCodec<>() {

                @Override
                public TableReward.Mode decode(RegistryFriendlyByteBuf buffer) {
                    String wire = buffer.readUtf(16);
                    return TableReward.Mode.ofWire(wire).orElseThrow(() ->
                            new io.netty.handler.codec.DecoderException("unknown table mode " + wire));
                }

                @Override
                public void encode(RegistryFriendlyByteBuf buffer, TableReward.Mode mode) {
                    buffer.writeUtf(mode.wire(), 16);
                }
            };

    /** The payload's id. The constructor, not {@code createType} — see {@link QuestSyncPayload#TYPE}. */
    public static final CustomPacketPayload.Type<TableRollRequestPayload> TYPE =
            new CustomPacketPayload.Type<>(
                    Identifier.fromNamespaceAndPath("tenet", "table_roll_request"));

    public static final StreamCodec<? super RegistryFriendlyByteBuf, TableRollRequestPayload> CODEC =
            StreamCodec.composite(
                    TableAddress.CODEC, TableRollRequestPayload::address,
                    ByteBufCodecs.VAR_INT, TableRollRequestPayload::rolls,
                    MODE_CODEC, TableRollRequestPayload::mode,
                    TableRollRequestPayload::new);

    @Override
    public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
