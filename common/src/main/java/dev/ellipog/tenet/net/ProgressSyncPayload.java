package dev.ellipog.tenet.net;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

import java.util.UUID;

/**
 * One team's progress: one chunk of it, when it changed, and why it was sent.
 *
 * <h2>Sent after a change, not on a timer</h2>
 *
 * <p>The server sends this when something actually moved: a task advanced, a quest completed, a
 * cooldown expired. A periodic sync is easier to write and worse in every way — it puts traffic on the
 * wire proportional to uptime rather than to activity, and it makes every client-side bug look like
 * lag rather than a missed packet.
 *
 * <h2>The team id is on it, and it matters after a party changes</h2>
 *
 * <p>A player can leave a party, and the next sync they receive is for a different team. With the id,
 * the client can tell its cached progress is for the wrong team and start fresh. Without it, it would
 * apply the new progress over the old team's without noticing, showing a questline that belongs to
 * nobody.
 *
 * <p>The UUID goes as two longs rather than a string: fixed size, no parsing, no allocation. Two
 * {@code VAR_LONG}s and not a chance of a malformed one, which a string would permit.
 *
 * <h2>Six components, and that is the ceiling</h2>
 *
 * <p>{@code StreamCodec.composite} has overloads for one through six and no more, so the UUID's two
 * longs plus a game time, a chunk header, the data and a reason is exactly the budget. That is why
 * {@link SyncChunk} folds four fields into one — see its own note. Past six the compiler reports a
 * type inference failure on the combining function, which names the arguments rather than the arity.
 *
 * <h2>The delta, and why the flag lives in the chunk</h2>
 *
 * <p>{@code progressAsJson} used to walk every quest and send all of them, and the client replaced
 * its whole map — so every task tick re-sent the entire questline. Now the server compares against
 * what this player was last sent and sends only what differs, marked as a delta by
 * {@code chunk.full()}.
 *
 * <p>A client that receives a delta it has no full sync to apply onto must <b>refuse</b> it rather
 * than apply it. Applying one produces a cache holding a handful of quests and everything else
 * LOCKED, which looks exactly like a working sync of a very small pack — the failure has no symptom
 * that points at the cause. {@code ClientQuestCache} enforces that, and this record only has to carry
 * the flag honestly.
 *
 * @param teamId   whose progress this is
 * @param gameTime the server's tick count, so a client can tell how fresh this is
 * @param chunk    where this chunk sits in the message
 * @param data     this chunk's packed bytes — see {@link SyncWire#pack}
 * @param reason   why it was sent, for a log line and for a client that reacts differently
 */
public record ProgressSyncPayload(UUID teamId, long gameTime, SyncChunk chunk, byte[] data, int reason)
        implements CustomPacketPayload {

    public static final int REASON_JOIN = 0;
    public static final int REASON_CHANGED = 1;
    public static final int REASON_RELOAD = 2;
    public static final int REASON_TEAM_CHANGED = 3;

    /**
     * The payload's id. The constructor, not {@code createType} — see
     * {@link QuestSyncPayload#TYPE} for why that method silently rewrites the namespace.
     */
    public static final CustomPacketPayload.Type<ProgressSyncPayload> TYPE =
            new CustomPacketPayload.Type<>(Identifier.fromNamespaceAndPath("tenet", "progress_sync"));

    public static final StreamCodec<? super RegistryFriendlyByteBuf, ProgressSyncPayload> CODEC =
            StreamCodec.composite(
                    ByteBufCodecs.VAR_LONG, payload -> payload.teamId().getMostSignificantBits(),
                    ByteBufCodecs.VAR_LONG, payload -> payload.teamId().getLeastSignificantBits(),
                    ByteBufCodecs.VAR_LONG, ProgressSyncPayload::gameTime,
                    SyncChunk.CODEC, ProgressSyncPayload::chunk,
                    ByteBufCodecs.BYTE_ARRAY, ProgressSyncPayload::data,
                    ByteBufCodecs.VAR_INT, ProgressSyncPayload::reason,
                    (most, least, gameTime, chunk, data, reason) ->
                            new ProgressSyncPayload(new UUID(most, least), gameTime, chunk, data, reason));

    @Override
    public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
