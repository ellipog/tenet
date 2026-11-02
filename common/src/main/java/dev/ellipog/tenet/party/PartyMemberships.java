package dev.ellipog.tenet.party;

import dev.ellipog.tenet.Constants;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.level.saveddata.SavedDataType;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * The party each player was last seen in, for one world: what {@link PartyWatch} compares against.
 *
 * <h2>Why this is saved rather than held in memory</h2>
 *
 * <p>Because the change it exists to catch can happen to somebody who is not there. A parties mod
 * that announces nothing (Open Parties and Claims) lets an owner kick an offline member, and the
 * member keeps what they earned only if somebody notices the membership ended. Noticing needs the
 * previous answer, and an answer held only in memory would be gone after a restart, so a member
 * kicked the day before and logging in after one would never have it noticed at all.
 *
 * <h2>Only players in a real party are stored</h2>
 *
 * <p>A solo player has nothing to keep and nothing to notice leaving, so they are absent rather than
 * written down: the file holds one line per player who is in a party, not one per player who ever
 * logged in. Absent therefore means "solo, or never seen", and {@link PartyWatch} tells the two apart
 * by whether it has seen the player this session.
 *
 * <h2>Saved per world</h2>
 *
 * <p>A {@link SavedData} on the overworld, like {@link PartyStore} and for the same reason: a
 * membership is a server's, not a dimension's. {@link #record} marks the store dirty itself, so there
 * is no call site that could forget to.
 */
public final class PartyMemberships extends SavedData {

    /** Player to the party they were last seen in. Absent means solo or unknown. */
    private final Map<UUID, UUID> parties = new LinkedHashMap<>();

    /**
     * The store's serialised form: a plain tag codec over {@link #save} and {@link #load}, the shape
     * {@link PartyStore} uses and for its reasons.
     */
    public static final com.mojang.serialization.Codec<PartyMemberships> CODEC =
            CompoundTag.CODEC.xmap(PartyMemberships::load, store -> store.save(new CompoundTag()));

    private static final SavedDataType<PartyMemberships> TYPE = new SavedDataType<>(
            Identifier.fromNamespaceAndPath("tenet", "memberships"),
            PartyMemberships::new,
            CODEC,
            // Null, as PartyStore's is: our own format with its own version key, nothing for vanilla's
            // migration table to do.
            null);

    /** The store for this server. */
    public static PartyMemberships of(MinecraftServer server) {
        return server.overworld().getDataStorage().computeIfAbsent(TYPE);
    }

    /** The party this player was last seen in, or null for solo or never seen. */
    public UUID partyOf(UUID player) {
        return parties.get(player);
    }

    /**
     * Records where a player is now, and marks the store for saving when that changed.
     *
     * @param party the party they are in, or null for solo, which removes their line
     */
    public void record(UUID player, UUID party) {
        UUID before = party == null ? parties.remove(player) : parties.put(player, party);
        if (!java.util.Objects.equals(before, party)) {
            setDirty();
        }
    }

    /** A copy of every recorded membership, player to party. */
    public Map<UUID, UUID> snapshot() {
        return Map.copyOf(parties);
    }

    // ------------------------------------------------------------------
    // Persistence
    // ------------------------------------------------------------------

    static PartyMemberships load(CompoundTag tag) {
        PartyMemberships store = new PartyMemberships();
        ListTag list = tag.getListOrEmpty("members");
        for (int i = 0; i < list.size(); i++) {
            CompoundTag entry = list.getCompoundOrEmpty(i);
            UUID player = readUuid(entry.getStringOr("player", ""));
            UUID party = readUuid(entry.getStringOr("party", ""));
            if (player == null || party == null) {
                Constants.LOG.warn("skipping a stored party membership with no player or party id");
                continue;
            }
            store.parties.put(player, party);
        }
        return store;
    }

    /**
     * Writes the store. Called by {@link #CODEC}: 26.x storage serialises through the type's codec,
     * and this is the encoder half.
     */
    public CompoundTag save(CompoundTag tag) {
        tag.putInt("version", 1);
        ListTag list = new ListTag();
        parties.forEach((player, party) -> {
            CompoundTag entry = new CompoundTag();
            // Strings, like PartyStore: greppable when somebody opens the file to see who was where.
            entry.putString("player", player.toString());
            entry.putString("party", party.toString());
            list.add(entry);
        });
        tag.put("members", list);
        return tag;
    }

    private static UUID readUuid(String raw) {
        if (raw.isEmpty()) {
            return null;
        }
        try {
            return UUID.fromString(raw);
        }
        catch (IllegalArgumentException e) {
            Constants.LOG.warn("'{}' is not a valid uuid in a stored party membership", raw);
            return null;
        }
    }
}
