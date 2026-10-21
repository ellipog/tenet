package dev.ellipog.tenet.progress;

import dev.ellipog.tenet.Constants;
import dev.ellipog.tenet.Tenet;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.level.saveddata.SavedDataType;
import net.minecraft.world.level.storage.LevelResource;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Every team's quest progress, for one world.
 *
 * <h2>Keyed by team id, which is a player's UUID when they are alone</h2>
 *
 * <p>Armature gives a solo player a team whose id <b>is their own UUID</b> (see {@code Team.solo}).
 * That single decision makes this class simple: progress is always looked up by "the id of the team
 * you are in", with no branch for solo players and no second storage layout.
 *
 * <p>It also gives the right behaviour for free when somebody joins a party. A player's solo progress
 * sits under their UUID; the team they join has its own id and its own progress. Joining means they
 * now read the team's — and their own is untouched, waiting if they leave. Legacy packs behave the
 * same way, and the alternative (merging, or importing) is a decision nobody can make well
 * automatically: a new member arriving with four hundred completed quests would hand the whole party
 * a finished questline.
 *
 * <h2>Leaving is the asymmetric half, and it is deliberate</h2>
 *
 * <p>What is refused on a join is done on a departure: {@code ProgressService.retainFor} merges the
 * party's record into the leaver's own, so quest nodes earned together are kept by whoever leaves and
 * a progression-gated pack cannot soft-lock somebody who goes solo. The party's record is not moved —
 * the members still in it read exactly what they did before — and nothing is imported the other way.
 * One direction would finish a questline for free; the other would erase one.
 *
 * <h2>Saved per world</h2>
 *
 * <p>A {@link SavedData} on the overworld rather than per-dimension, so a quest finished in the
 * Nether counts. Marked dirty after every write; forgetting that produces progress that works all
 * session and is gone tomorrow, which is a miserable thing to be handed.
 *
 * <h2>Why the stages live here too, and why they are keyed by player</h2>
 *
 * <p>A stage is a named flag a player has, the thing a pack's scripts and quest gates ask about. It is
 * keyed by <b>player</b> UUID rather than by team, unlike everything else in this class, because that is
 * what a stage means everywhere it is used: GameStages, the legacy stage integration and every pack
 * script written against them ask "does <i>this player</i> have it". A team-mode reward that grants a
 * stage grants it to the player who claimed, which is the legacy behaviour too.
 *
 * <p>They share this file rather than getting one of their own so that a grant and the completion that
 * caused it are written, versioned and backed up together -- one world's quest data in one place.
 *
 * <h2>Team stages, beside the player's own</h2>
 *
 * <p>The legacy {@code team_stage}: a flag a <b>team</b> holds, for the multiplayer pack where one
 * member's induction opens the chapter for everybody. Keyed by team id — which is a solo player's
 * own UUID, so a solo grant reads exactly like a personal one — and stored beside the player's own
 * in the same sectioned shape. A stage task or a quest gate that names {@code teamStage} reads the
 * team's set; everything else reads the player's.
 */
public final class ProgressStore extends SavedData {

    private final Map<UUID, TeamProgress> byTeam = new LinkedHashMap<>();

    /**
     * Each player's stages, by player UUID.
     *
     * <p>Iteration order is insertion order and each set is a {@link java.util.LinkedHashSet}, so the file
     * is stable between saves: a diff of two saves is then a diff of what actually changed, which is worth
     * the two lines it costs for anyone debugging a pack by reading the file.
     */
    private final Map<UUID, Set<Identifier>> stagesByPlayer = new LinkedHashMap<>();

    /**
     * Each team's stages, by team id.
     *
     * <p>The same insertion-ordered sets as the player's own, for the same stable-file reason. A solo
     * team's id is the player's own UUID, so a solo grant lands in a different map under the same key
     * rather than colliding — the two sets are asked about separately, and only together when the
     * sync builds what one member sees.
     */
    private final Map<UUID, Set<Identifier>> stagesByTeam = new LinkedHashMap<>();

    /** Increments whenever any player or team stage is added or removed. */
    private long stageRevision;

    /**
     * The store's serialised form. A plain tag codec over {@link #save} and {@link #load} rather
     * than a field-by-field codec: the format is versioned by hand, with readers for older
     * shapes a generated codec could not express, and the {@code registries} neither side takes
     * mean there is nothing a fancier codec would use.
     */
    public static final com.mojang.serialization.Codec<ProgressStore> CODEC =
            CompoundTag.CODEC.xmap(ProgressStore::load, store -> store.save(new CompoundTag()));

    private static final SavedDataType<ProgressStore> TYPE = new SavedDataType<>(
            Identifier.fromNamespaceAndPath("tenet", "progress"),
            ProgressStore::new,
            CODEC,
            // Null, deliberately: DataFixTypes is vanilla's migration table, and this is our own
            // format with its own version key, so there is nothing for it to migrate. Same null the
            // 1.21.1 `SavedData.Factory` took, for the same reason.
            null);

    /** The 1.21.1 file name, flat under the data folder. Only used once, to move it (see below). */
    private static final String LEGACY_FILE = "tenet_progress.dat";

    /** The store for this server. */
    public static ProgressStore of(MinecraftServer server) {
        // Overworld, so the data is global rather than per-dimension.
        moveLegacyStoreIfPresent(server);
        return server.overworld().getDataStorage().computeIfAbsent(TYPE);
    }

    /**
     * Moves a 1.21.1 store file into the namespaced place 26.x reads from, once.
     *
     * <p>The old storage wrote {@code data/tenet_progress.dat}; the new one resolves the type id to
     * {@code data/tenet/progress.dat}. Without this, a server upgrading versions would start with no
     * progress while the old file sat beside the new one looking intact. A move rather than a copy,
     * so there is never a moment with two live files; if it throws, the legacy file is untouched and
     * the next boot tries again.
     */
    private static void moveLegacyStoreIfPresent(MinecraftServer server) {
        java.nio.file.Path data = server.getWorldPath(LevelResource.ROOT).resolve("data");
        java.nio.file.Path legacy = data.resolve(LEGACY_FILE);
        java.nio.file.Path current = data.resolve("tenet").resolve("progress.dat");
        try {
            if (java.nio.file.Files.isRegularFile(legacy)
                    && !java.nio.file.Files.isRegularFile(current)) {
                java.nio.file.Files.createDirectories(current.getParent());
                java.nio.file.Files.move(legacy, current);
                Constants.LOG.info("moved stored progress from {} to {} (1.21.1 to 26.x storage layout)",
                        legacy.getFileName(), current);
            }
        }
        catch (java.io.IOException e) {
            Constants.LOG.warn("could not move stored progress from {} (leaving it for the next boot): {}",
                    legacy, e.toString());
        }
    }

    /** The progress of one team. Never null — an unknown team has no completed quests, which is a valid state. */
    public TeamProgress progressOf(UUID teamId) {
        return byTeam.getOrDefault(teamId, TeamProgress.empty());
    }

    public boolean has(UUID teamId) {
        return byTeam.containsKey(teamId);
    }

    /** Replaces a team's progress and marks the store for saving. */
    public void put(UUID teamId, TeamProgress progress) {
        byTeam.put(teamId, progress);
        setDirty();
    }

    /** Forgets a team entirely. Used by {@code /tenet reset}, not by leaving a party. */
    public void clear(UUID teamId) {
        byTeam.remove(teamId);
        setDirty();
    }

    public int teamCount() {
        return byTeam.size();
    }

    // ------------------------------------------------------------------
    // Stages: named flags, per player
    // ------------------------------------------------------------------

    /** Every stage this player has, in the order they were granted. Empty for a player with none. */
    public Set<Identifier> stagesOf(UUID player) {
        return Set.copyOf(stagesByPlayer.getOrDefault(player, Set.of()));
    }

    public boolean hasStage(UUID player, Identifier stage) {
        return stagesByPlayer.getOrDefault(player, Set.of()).contains(stage);
    }

    /**
     * Grants a stage.
     *
     * @return whether it changed anything -- false when the player already had it, which is what makes a
     *         reward idempotent and what the event firing is gated on
     */
    public boolean addStage(UUID player, Identifier stage) {
        if (!stagesByPlayer.computeIfAbsent(player, id -> new java.util.LinkedHashSet<>()).add(stage)) {
            return false;
        }
        stageRevision++;
        setDirty();
        return true;
    }

    /** Takes a stage away. False when the player did not have it. */
    public boolean removeStage(UUID player, Identifier stage) {
        Set<Identifier> held = stagesByPlayer.get(player);
        if (held == null || !held.remove(stage)) {
            return false;
        }
        if (held.isEmpty()) {
            // A player with no stages is not a player with an empty entry: the file should not grow a row
            // per player who was ever granted one and then had it taken away.
            stagesByPlayer.remove(player);
        }
        stageRevision++;
        setDirty();
        return true;
    }

    /** How many players hold at least one stage. For diagnostics. */
    public int stagedPlayerCount() {
        return stagesByPlayer.size();
    }

    /** Monotonic revision of all stage grants and removals in this store. */
    public long stageRevision() {
        return stageRevision;
    }

    // ------------------------------------------------------------------
    // Team stages: named flags, per team
    // ------------------------------------------------------------------

    /** Every stage this team holds, in the order they were granted. Empty for a team with none. */
    public Set<Identifier> stagesOfTeam(UUID team) {
        return Set.copyOf(stagesByTeam.getOrDefault(team, Set.of()));
    }

    public boolean hasTeamStage(UUID team, Identifier stage) {
        return stagesByTeam.getOrDefault(team, Set.of()).contains(stage);
    }

    /**
     * Grants a team stage.
     *
     * @return whether it changed anything -- false when the team already held it
     */
    public boolean addTeamStage(UUID team, Identifier stage) {
        if (!stagesByTeam.computeIfAbsent(team, id -> new java.util.LinkedHashSet<>()).add(stage)) {
            return false;
        }
        stageRevision++;
        setDirty();
        return true;
    }

    /** Takes a team stage away. False when the team did not hold it. */
    public boolean removeTeamStage(UUID team, Identifier stage) {
        Set<Identifier> held = stagesByTeam.get(team);
        if (held == null || !held.remove(stage)) {
            return false;
        }
        if (held.isEmpty()) {
            // Like the player's own: a team with no stages is not a team with an empty entry.
            stagesByTeam.remove(team);
        }
        stageRevision++;
        setDirty();
        return true;
    }

    /** How many teams hold at least one stage. For diagnostics. */
    public int stagedTeamCount() {
        return stagesByTeam.size();
    }

    // ------------------------------------------------------------------

    static ProgressStore load(CompoundTag tag) {
        ProgressStore store = new ProgressStore();

        ListTag list = tag.getListOrEmpty("teams");
        for (int i = 0; i < list.size(); i++) {
            CompoundTag entry = list.getCompoundOrEmpty(i);
            UUID teamId = readUuid(entry.getStringOr("team", ""));
            if (teamId == null) {
                Constants.LOG.warn("skipping stored progress with no team id");
                continue;
            }
            store.byTeam.put(teamId, TeamProgress.load(entry));
        }

        // Absent in a file written before stages existed, which is why nothing checks the version: a missing
        // section is a player with no stages, and that is a valid state rather than something to migrate.
        // The same holds for the team section a file written before team stages existed: absent means none.
        ListTag stages = tag.getListOrEmpty("stages");
        for (int i = 0; i < stages.size(); i++) {
            CompoundTag entry = stages.getCompoundOrEmpty(i);
            UUID player = readUuid(entry.getStringOr("player", ""));
            if (player == null) {
                Constants.LOG.warn("skipping stored stages with no player id");
                continue;
            }
            Set<Identifier> held = new java.util.LinkedHashSet<>();
            ListTag ids = entry.getListOrEmpty("ids");
            for (int j = 0; j < ids.size(); j++) {
                Identifier stage = Identifier.tryParse(ids.getStringOr(j, ""));
                if (stage == null) {
                    Constants.LOG.warn("'{}' is not a valid stage id in stored stages", ids.getStringOr(j, ""));
                    continue;
                }
                held.add(stage);
            }
            store.stagesByPlayer.put(player, held);
        }

        ListTag teamStages = tag.getListOrEmpty("team_stages");
        for (int i = 0; i < teamStages.size(); i++) {
            CompoundTag entry = teamStages.getCompoundOrEmpty(i);
            UUID team = readUuid(entry.getStringOr("team", ""));
            if (team == null) {
                Constants.LOG.warn("skipping stored team stages with no team id");
                continue;
            }
            Set<Identifier> held = new java.util.LinkedHashSet<>();
            ListTag ids = entry.getListOrEmpty("ids");
            for (int j = 0; j < ids.size(); j++) {
                Identifier stage = Identifier.tryParse(ids.getStringOr(j, ""));
                if (stage == null) {
                    Constants.LOG.warn("'{}' is not a valid stage id in stored team stages",
                            ids.getStringOr(j, ""));
                    continue;
                }
                held.add(stage);
            }
            store.stagesByTeam.put(team, held);
        }

        return store;
    }

    /**
     * Writes the store. Called by {@link #CODEC}, not overridden from anywhere: 26.x storage
     * serialises through the type's codec, and this is the encoder half.
     */
    public CompoundTag save(CompoundTag tag) {
        // 3 since team stages were added. The version is written for anyone reading the file; nothing reads it
        // back, because every section is additive and a missing one means "none" rather than "old shape".
        tag.putInt("version", 3);

        ListTag list = new ListTag();
        byTeam.forEach((teamId, progress) -> {
            CompoundTag entry = new CompoundTag();
            // As a string rather than putUUID: greppable when someone opens the file to debug, and
            // getUUID returns null for a missing key rather than failing where the problem is.
            entry.putString("team", teamId.toString());
            progress.save(entry);
            list.add(entry);
        });
        tag.put("teams", list);

        ListTag stages = new ListTag();
        stagesByPlayer.forEach((player, held) -> {
            CompoundTag entry = new CompoundTag();
            entry.putString("player", player.toString());
            ListTag ids = new ListTag();
            for (Identifier stage : held) {
                ids.add(net.minecraft.nbt.StringTag.valueOf(stage.toString()));
            }
            entry.put("ids", ids);
            stages.add(entry);
        });
        tag.put("stages", stages);

        ListTag teamStages = new ListTag();
        stagesByTeam.forEach((team, held) -> {
            CompoundTag entry = new CompoundTag();
            entry.putString("team", team.toString());
            ListTag ids = new ListTag();
            for (Identifier stage : held) {
                ids.add(net.minecraft.nbt.StringTag.valueOf(stage.toString()));
            }
            entry.put("ids", ids);
            teamStages.add(entry);
        });
        tag.put("team_stages", teamStages);

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
            Constants.LOG.warn("'{}' is not a valid team uuid in stored progress", raw);
            return null;
        }
    }
}
