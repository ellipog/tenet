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

    /** Team id to the mode that party chose. Absent means {@link PartyMode#DEFAULT}. */
    private final Map<UUID, PartyMode> modes = new LinkedHashMap<>();

    /**
     * The store's serialised form. A plain tag codec over {@link #save} and {@link #load} rather
     * than a field-by-field codec: the readable strings need no migration machinery, and the
     * {@code registries} neither side takes mean there is nothing a fancier codec would use.
     */
    public static final com.mojang.serialization.Codec<PartyStore> CODEC =
            CompoundTag.CODEC.xmap(PartyStore::load, store -> store.save(new CompoundTag()));

    private static final SavedDataType<PartyStore> TYPE = new SavedDataType<>(
            Identifier.fromNamespaceAndPath("tenet", "parties"),
            PartyStore::new,
            CODEC,
            // Null, deliberately: DataFixTypes is vanilla's migration table, and this is our own
            // format with its own version key, so there is nothing for it to migrate. Same null the
            // 1.21.1 `SavedData.Factory` took, for the same reason.
            null);

    /** The 1.21.1 file name, flat under the data folder. Only used once, to move it (see below). */
    private static final String LEGACY_FILE = "tenet_parties.dat";

    /**
     * The store for this server.
     */
    public static PartyStore of(MinecraftServer server) {
        moveLegacyStoreIfPresent(server);
        return server.overworld().getDataStorage().computeIfAbsent(TYPE);
    }

    /**
     * Moves a 1.21.1 store file into the namespaced place 26.x reads from, once.
     *
     * <p>The old storage wrote {@code data/tenet_parties.dat}; the new one resolves the type id to
     * {@code data/tenet/parties.dat}. Without this, a server upgrading versions would forget every
     * party's counting mode while the old file sat beside the new one looking intact. A move rather
     * than a copy, so there is never a moment with two live files; if it throws, the legacy file is
     * untouched and the next boot tries again.
     */
    private static void moveLegacyStoreIfPresent(MinecraftServer server) {
        java.nio.file.Path data =
                server.getWorldPath(net.minecraft.world.level.storage.LevelResource.ROOT).resolve("data");
        java.nio.file.Path legacy = data.resolve(LEGACY_FILE);
        java.nio.file.Path current = data.resolve("tenet").resolve("parties.dat");
        try {
            if (java.nio.file.Files.isRegularFile(legacy)
                    && !java.nio.file.Files.isRegularFile(current)) {
                java.nio.file.Files.createDirectories(current.getParent());
                java.nio.file.Files.move(legacy, current);
                Constants.LOG.info("moved stored party modes from {} to {} (1.21.1 to 26.x storage layout)",
                        legacy.getFileName(), current);
            }
        }
        catch (java.io.IOException e) {
            Constants.LOG.warn("could not move stored party modes from {} (leaving it for the next boot): {}",
                    legacy, e.toString());
        }
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

    static PartyStore load(CompoundTag tag) {
        PartyStore store = new PartyStore();

        ListTag list = tag.getListOrEmpty("parties");
        for (int i = 0; i < list.size(); i++) {
            CompoundTag entry = list.getCompoundOrEmpty(i);
            UUID teamId = readUuid(entry.getStringOr("team", ""));
            if (teamId == null) {
                Constants.LOG.warn("skipping a stored party mode with no team id");
                continue;
            }
            // An unrecognised name is reported and defaulted rather than dropped, for the same reason
            // a mode is stored as a name at all: the file outlives any particular set of modes, and a
            // mode removed in a future version should leave a party counting sensibly rather than
            // leaving it in a state no code can name.
            PartyMode mode = PartyMode.byId(entry.getStringOr("mode", "")).orElse(null);
            if (mode == null) {
                Constants.LOG.warn("stored party mode '{}' is not one of {}; using {}",
                        entry.getStringOr("mode", ""), PartyMode.ids(), PartyMode.DEFAULT.id());
                mode = PartyMode.DEFAULT;
            }
            store.modes.put(teamId, mode);
        }

        return store;
    }

    /**
     * Writes the store. Called by {@link #CODEC}, not overridden from anywhere: 26.x storage
     * serialises through the type's codec, and this is the encoder half.
     */
    public CompoundTag save(CompoundTag tag) {
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
