package dev.ellipog.tenet.net;

import dev.ellipog.tenet.quest.loot.TableRoller;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

import java.util.ArrayList;
import java.util.List;

/**
 * What a table's dice did: the report the editor's test roll draws.
 *
 * <h2>Flat, with a depth, rather than nested</h2>
 *
 * <p>A tally is a tree — an entry can be a table, whose entries can be tables — and a recursive stream
 * codec for it would be more machinery than the shape is worth. So the rows travel in order with the
 * depth they belong at, which is also exactly how the panel draws them: an indent per level. The order
 * is the walk's, so a child follows its parent and the reader's eye follows the same path the dice did.
 *
 * <h2>What a row carries</h2>
 *
 * <p>The display fields a reward row uses — item, count, label, fallback, argument — so a roll report
 * and a quest's reward list describe the same entry the same way, translated on the client. And
 * {@code emptyHits}: a nested loot table that paid nothing is the difference between "the rare bag never
 * dropped" and "the rare bag dropped and paid nothing", and a report that hid it would be quietly wrong.
 *
 * <h2>The two ways a report is incomplete travel with it</h2>
 *
 * <p>{@code truncated} says the walk hit the nesting limit and {@code notRolled} names the entries no
 * roll can pay out — a choice inside a table, which offers rather than hands out. Both are fields rather
 * than silences: a report that simply showed fewer rows than the file has reads as those entries not
 * being in the table, and the author goes looking in the wrong file.
 */
public record TableRollPayload(String subject, String mode, int rolls, int emptyHits, boolean truncated,
                               List<Row> rows, List<String> notRolled)
        implements CustomPacketPayload {

    /**
     * One line of the report.
     *
     * @param depth     how deep this row sits, for the indent
     * @param index     the entry's position in its own table
     * @param hits      how many times it was granted across the rolls
     * @param emptyHits for a nested table, the times its own roll paid nothing
     */
    public record Row(int depth, int index, int hits, int emptyHits, Display display) {

        public static final StreamCodec<RegistryFriendlyByteBuf, Row> CODEC =
                StreamCodec.composite(
                        ByteBufCodecs.VAR_INT, Row::depth,
                        ByteBufCodecs.VAR_INT, Row::index,
                        ByteBufCodecs.VAR_INT, Row::hits,
                        ByteBufCodecs.VAR_INT, Row::emptyHits,
                        Display.CODEC, Row::display,
                        Row::new);
    }

    /**
     * What to draw for a row: the display fields a reward row carries.
     *
     * <p>Its own record rather than five more fields on {@link Row}, because
     * {@code StreamCodec.composite} takes <b>six</b> pairs and the flattening already spends four on the
     * numbers — and because these five are one thing: the translated name of a reward. (This said
     * "eight", which is not a number that overload takes: the mistake surfaced the moment this class
     * needed a seventh field, and the compiler's message was about overload resolution rather than
     * about the count. See {@link #CODEC}.)
     */
    public record Display(String item, int count, String label, String fallback, String arg) {

        public static final StreamCodec<RegistryFriendlyByteBuf, Display> CODEC =
                StreamCodec.composite(
                        ByteBufCodecs.stringUtf8(256), Display::item,
                        ByteBufCodecs.VAR_INT, Display::count,
                        ByteBufCodecs.stringUtf8(512), Display::label,
                        ByteBufCodecs.stringUtf8(512), Display::fallback,
                        ByteBufCodecs.stringUtf8(512), Display::arg,
                        Display::new);
    }

    public TableRollPayload {
        rows = List.copyOf(rows);
        notRolled = List.copyOf(notRolled);
    }

    /** The payload's id. The constructor, not {@code createType} — see {@link QuestSyncPayload#TYPE}. */
    public static final CustomPacketPayload.Type<TableRollPayload> TYPE =
            new CustomPacketPayload.Type<>(
                    Identifier.fromNamespaceAndPath("tenet", "table_roll"));

    /** The rows, and the notes beside them, as list codecs. */
    private static final StreamCodec<RegistryFriendlyByteBuf, List<Row>> ROWS =
            Row.CODEC.apply(ByteBufCodecs.list());

    /**
     * {@code ByteBuf} rather than the registry-aware type, because a string codec is not
     * registry-aware: {@code Row} carries an item id that has to be resolved against the registry this
     * connection holds, and a note is a sentence. A {@code RegistryFriendlyByteBuf} is a
     * {@code ByteBuf}, so both buffers are accepted here.
     */
    private static final StreamCodec<io.netty.buffer.ByteBuf, List<String>> NOTES =
            ByteBufCodecs.stringUtf8(512).apply(ByteBufCodecs.list());

    /**
     * Written by hand rather than through {@code StreamCodec.composite}, which takes six pairs and no
     * more: this record has seven fields, and {@code notRolled} is the one that tipped it over. The
     * order is the field order above, spelled out here so a field added later has one place to be
     * added rather than a hunt for an overload that no longer matches.
     */
    public static final StreamCodec<? super RegistryFriendlyByteBuf, TableRollPayload> CODEC =
            new StreamCodec<>() {

                @Override
                public TableRollPayload decode(RegistryFriendlyByteBuf buffer) {
                    String subject = buffer.readUtf(256);
                    String mode = buffer.readUtf(32);
                    int rolls = buffer.readVarInt();
                    int emptyHits = buffer.readVarInt();
                    boolean truncated = buffer.readBoolean();
                    List<Row> rows = ROWS.decode(buffer);
                    List<String> notRolled = NOTES.decode(buffer);
                    return new TableRollPayload(subject, mode, rolls, emptyHits, truncated, rows,
                            notRolled);
                }

                @Override
                public void encode(RegistryFriendlyByteBuf buffer, TableRollPayload payload) {
                    buffer.writeUtf(payload.subject(), 256);
                    buffer.writeUtf(payload.mode(), 32);
                    buffer.writeVarInt(payload.rolls());
                    buffer.writeVarInt(payload.emptyHits());
                    buffer.writeBoolean(payload.truncated());
                    ROWS.encode(buffer, payload.rows());
                    NOTES.encode(buffer, payload.notRolled());
                }
            };

    @Override
    public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    /** A report as rows: the walk, flattened with the depth each tally sits at. */
    public static TableRollPayload of(TableRoller.Report report, String subject) {
        List<Row> rows = new ArrayList<>();
        for (TableRoller.Tally tally : report.entries()) {
            add(rows, tally, 0);
        }
        return new TableRollPayload(subject, report.mode().wire(), report.rolls(), report.emptyHits(),
                report.truncated(), rows, report.notRolled());
    }

    private static void add(List<Row> rows, TableRoller.Tally tally, int depth) {
        var display = tally.display();
        rows.add(new Row(depth, tally.index(), tally.hits(), tally.emptyHits(),
                new Display(display.item().map(ref -> ref.item().toString()).orElse(""),
                        display.count(), display.label(), display.labelFallback(), display.labelArg())));
        for (TableRoller.Tally child : tally.nested()) {
            add(rows, child, depth + 1);
        }
    }
}
