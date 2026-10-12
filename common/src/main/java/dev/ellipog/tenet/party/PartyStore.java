package dev.ellipog.tenet.party;

import dev.ellipog.tenet.Constants;

import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.saveddata.SavedData;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Each party's choice of {@link PartyMode}, for one world.
 *
 * <h2>Why this is not a field on {@code TeamProgress}</h2>
 *
 * <p>Because a mode is not progress, and the two have different lifetimes. Putting it on
 * {@code TeamProgress} would have been smaller and would have put a party's preference inside the
 * record that <b>{@code /tenet reset} clears</b>: reset with no quest named removes the whole entry,
 * so clearing everybody's progress would silently reset how the party counts. A player who reset a
 * questline would find their party's mode had changed, with nothing connecting the two acts.
 *
 * <p>So they are two files with two rules, and the rule this one follows is: <b>a mode is remembered
 * until the party is gone.</b> Nothing a player does to their progress touches it, and the only thing
 * that forgets one is the party ceasing to exist — see the note on {@link #clear}.
 *
 * <h2>Only parties that chose are stored</h2>
 *
 * <p>An unknown team reads as {@link PartyMode#DEFAULT} rather than being absent, so a world where
 * nobody has ever touched this feature has an empty file rather than one entry per player who logged
 * in. That is the whole of the storage rule: a party appears here once it has said something, and a
 * mode set back to the default stays recorded — which is the truthful reading, because the party
 * chose it, and "chose the default" and "has not chosen" are the same behaviour with different
 * histories.
 *
 * <h2>Saved per world</h2>
 *
 * <p>A {@link SavedData} on the overworld, like {@code ProgressStore} and for the same reason: a mode
 * is set on a server, not per dimension. And {@link #put} marks the store dirty itself, so there is
 * no call site that could forget to.
 */
public final class PartyStore extends SavedData {

    private static final String DATA_NAME = "tenet_parties";

    /** Team id to the mode that party chose. Absent means {@link PartyMode#DEFAULT}. */
    private final Map<UUID, PartyMode> modes = new LinkedHashMap<>();

    /**
     * The store for this server.
     *
     * <p>The {@code null} is {@code DataFixTypes}, and it is deliberate rather than a shortcut — the
     * same argument {@code ProgressStore} records: this is our own format with our own version key,
     * so the vanilla datafixer has nothing to migrate. In 1.21.1 the factory takes three arguments;
     * the two-argument shape is older and does not compile.
     */
    public static PartyStore of(MinecraftServer server) {
        return server.overworld().getDataStorage()
                .computeIfAbsent(new SavedData.Factory<>(PartyStore::new, PartyStore::load, null), DATA_NAME);
    }

    /**
     * How this party counts.
     *
     * <p>Never null and never empty: a team with no entry has chosen nothing, which is a valid state
     * and is exactly what the default means. So no caller has to write a fallback, and there is no
     * "no mode" case for one to get wrong.
     */
    public PartyMode modeOf(UUID teamId) {
        return modes.getOrDefault(teamId, PartyMode.DEFAULT);
    }

    /** Records a party's choice, and marks the store for saving. */
    public void put(UUID teamId, PartyMode mode) {
        modes.put(teamId, mode);
        setDirty();
    }

    /**
     * Forgets a party's choice, for a party that no longer exists.
     *
     * <h2>Why this is worth a call rather than being left behind</h2>
     *
     * <p>Because a team id is a fresh {@code UUID} every time a party is formed, so an entry for a
     * disbanded party can never be read again — it is not a stale mode that will be picked up by the
     * next party, it is a line in a file that only ever grows. Clearing on disband is tidiness with a
     * floor under it rather than correctness, and it is the <i>only</i> thing that forgets a mode: a
     * member leaving a party that still exists changes nothing, which is right, because the mode
     * belongs to the party rather than to anybody in it.
     *
     * <p>Idempotent, which it has to be: a disband reaches here once per member for Armature's own
     * teams, since each member gets their own {@code MEMBER_LEFT}.
     *
     * @return whether anything was forgotten, so a caller that cares can tell
     */
    public boolean clear(UUID teamId) {
        if (modes.remove(teamId) == null) {
            return false;
        }
        setDirty();
        return true;
    }

    /** Whether this party has ever chosen. Diagnostics, and a test for the storage rule above. */
    public boolean has(UUID teamId) {
        return modes.containsKey(teamId);
    }

    /** How many parties have chosen. Diagnostics. */
    public int size() {
        return modes.size();
    }

    // ------------------------------------------------------------------
    // Persistence
    // ------------------------------------------------------------------

    static PartyStore load(CompoundTag tag, HolderLookup.Provider registries) {
        PartyStore store = new PartyStore();

        ListTag list = tag.getList("parties", Tag.TAG_COMPOUND);
        for (int i = 0; i < list.size(); i++) {
            CompoundTag entry = list.getCompound(i);
            UUID teamId = readUuid(entry.getString("team"));
            if (teamId == null) {
                Constants.LOG.warn("skipping a stored party mode with no team id");
                continue;
            }
            // An unrecognised name is reported and defaulted rather than dropped, for the same reason
            // a mode is stored as a name at all: the file outlives any particular set of modes, and a
            // mode removed in a future version should leave a party counting sensibly rather than
            // leaving it in a state no code can name.
            PartyMode mode = PartyMode.byId(entry.getString("mode")).orElse(null);
            if (mode == null) {
                Constants.LOG.warn("stored party mode '{}' is not one of {}; using {}",
                        entry.getString("mode"), PartyMode.ids(), PartyMode.DEFAULT.id());
                mode = PartyMode.DEFAULT;
            }
            store.modes.put(teamId, mode);
        }

        return store;
    }

    @Override
    public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
        tag.putInt("version", 1);

        ListTag list = new ListTag();
        modes.forEach((teamId, mode) -> {
            CompoundTag entry = new CompoundTag();
            // Both as strings: greppable when somebody opens the file to work out why a party counts
            // the way it does, and the mode's id is the same spelling the command and the file use.
            entry.putString("team", teamId.toString());
            entry.putString("mode", mode.id());
            list.add(entry);
        });
        tag.put("parties", list);

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
            Constants.LOG.warn("'{}' is not a valid team uuid in a stored party mode", raw);
            return null;
        }
    }
}
