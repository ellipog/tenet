package dev.ellipog.tenet.progress;

import dev.ellipog.tenet.Constants;
import dev.ellipog.tenet.Tenet;

import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.saveddata.SavedData;

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

    private static final String DATA_NAME = "tenet_progress";

    private final Map<UUID, TeamProgress> byTeam = new LinkedHashMap<>();

    /**
     * Each player's stages, by player UUID.
     *
     * <p>Iteration order is insertion order and each set is a {@link java.util.LinkedHashSet}, so the file
     * is stable between saves: a diff of two saves is then a diff of what actually changed, which is worth
     * the two lines it costs for anyone debugging a pack by reading the file.
     */
    private final Map<UUID, Set<ResourceLocation>> stagesByPlayer = new LinkedHashMap<>();

    /**
     * Each team's stages, by team id.
     *
     * <p>The same insertion-ordered sets as the player's own, for the same stable-file reason. A solo
     * team's id is the player's own UUID, so a solo grant lands in a different map under the same key
     * rather than colliding — the two sets are asked about separately, and only together when the
     * sync builds what one member sees.
     */
    private final Map<UUID, Set<ResourceLocation>> stagesByTeam = new LinkedHashMap<>();

    /** The store for this server. */
    public static ProgressStore of(MinecraftServer server) {
        // Overworld, so the data is global rather than per-dimension. Verified by javap against
        // the actual 1.21.1 artefact (see .utils/find_savedata.py): SavedData.Factory in this
        // version takes THREE arguments -- the constructor, the loader, and a nullable
        // DataFixTypes. Two arguments is the older shape and does not compile.
        //
        // The null is deliberate: DataFixTypes is the vanilla datafixer's migration table, and
        // this is our own format with our own version key, so there is nothing for it to migrate.
        return server.overworld().getDataStorage()
                .computeIfAbsent(new SavedData.Factory<>(ProgressStore::new, ProgressStore::load, null), DATA_NAME);
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
    public Set<ResourceLocation> stagesOf(UUID player) {
        return Set.copyOf(stagesByPlayer.getOrDefault(player, Set.of()));
    }

    public boolean hasStage(UUID player, ResourceLocation stage) {
        return stagesByPlayer.getOrDefault(player, Set.of()).contains(stage);
    }

    /**
     * Grants a stage.
     *
     * @return whether it changed anything -- false when the player already had it, which is what makes a
     *         reward idempotent and what the event firing is gated on
     */
    public boolean addStage(UUID player, ResourceLocation stage) {
        if (!stagesByPlayer.computeIfAbsent(player, id -> new java.util.LinkedHashSet<>()).add(stage)) {
            return false;
        }
        setDirty();
        return true;
    }

    /** Takes a stage away. False when the player did not have it. */
    public boolean removeStage(UUID player, ResourceLocation stage) {
        Set<ResourceLocation> held = stagesByPlayer.get(player);
        if (held == null || !held.remove(stage)) {
            return false;
        }
        if (held.isEmpty()) {
            // A player with no stages is not a player with an empty entry: the file should not grow a row
            // per player who was ever granted one and then had it taken away.
            stagesByPlayer.remove(player);
        }
        setDirty();
        return true;
    }

    /** How many players hold at least one stage. For diagnostics. */
    public int stagedPlayerCount() {
        return stagesByPlayer.size();
    }

    // ------------------------------------------------------------------
    // Team stages: named flags, per team
    // ------------------------------------------------------------------

    /** Every stage this team holds, in the order they were granted. Empty for a team with none. */
    public Set<ResourceLocation> stagesOfTeam(UUID team) {
        return Set.copyOf(stagesByTeam.getOrDefault(team, Set.of()));
    }

    public boolean hasTeamStage(UUID team, ResourceLocation stage) {
        return stagesByTeam.getOrDefault(team, Set.of()).contains(stage);
    }

    /**
     * Grants a team stage.
     *
     * @return whether it changed anything -- false when the team already held it
     */
    public boolean addTeamStage(UUID team, ResourceLocation stage) {
        if (!stagesByTeam.computeIfAbsent(team, id -> new java.util.LinkedHashSet<>()).add(stage)) {
            return false;
        }
        setDirty();
        return true;
    }

    /** Takes a team stage away. False when the team did not hold it. */
    public boolean removeTeamStage(UUID team, ResourceLocation stage) {
        Set<ResourceLocation> held = stagesByTeam.get(team);
        if (held == null || !held.remove(stage)) {
            return false;
        }
        if (held.isEmpty()) {
            // Like the player's own: a team with no stages is not a team with an empty entry.
            stagesByTeam.remove(team);
        }
        setDirty();
        return true;
    }

    /** How many teams hold at least one stage. For diagnostics. */
    public int stagedTeamCount() {
        return stagesByTeam.size();
    }

    // ------------------------------------------------------------------

    static ProgressStore load(CompoundTag tag, HolderLookup.Provider registries) {
        ProgressStore store = new ProgressStore();

        ListTag list = tag.getList("teams", Tag.TAG_COMPOUND);
        for (int i = 0; i < list.size(); i++) {
            CompoundTag entry = list.getCompound(i);
            UUID teamId = readUuid(entry.getString("team"));
            if (teamId == null) {
                Constants.LOG.warn("skipping stored progress with no team id");
                continue;
            }
            store.byTeam.put(teamId, TeamProgress.load(entry, registries));
        }

        // Absent in a file written before stages existed, which is why nothing checks the version: a missing
        // section is a player with no stages, and that is a valid state rather than something to migrate.
        // The same holds for the team section a file written before team stages existed: absent means none.
        ListTag stages = tag.getList("stages", Tag.TAG_COMPOUND);
        for (int i = 0; i < stages.size(); i++) {
            CompoundTag entry = stages.getCompound(i);
            UUID player = readUuid(entry.getString("player"));
            if (player == null) {
                Constants.LOG.warn("skipping stored stages with no player id");
                continue;
            }
            Set<ResourceLocation> held = new java.util.LinkedHashSet<>();
            ListTag ids = entry.getList("ids", Tag.TAG_STRING);
            for (int j = 0; j < ids.size(); j++) {
                ResourceLocation stage = ResourceLocation.tryParse(ids.getString(j));
                if (stage == null) {
                    Constants.LOG.warn("'{}' is not a valid stage id in stored stages", ids.getString(j));
                    continue;
                }
                held.add(stage);
            }
            store.stagesByPlayer.put(player, held);
        }

        ListTag teamStages = tag.getList("team_stages", Tag.TAG_COMPOUND);
        for (int i = 0; i < teamStages.size(); i++) {
            CompoundTag entry = teamStages.getCompound(i);
            UUID team = readUuid(entry.getString("team"));
            if (team == null) {
                Constants.LOG.warn("skipping stored team stages with no team id");
                continue;
            }
            Set<ResourceLocation> held = new java.util.LinkedHashSet<>();
            ListTag ids = entry.getList("ids", Tag.TAG_STRING);
            for (int j = 0; j < ids.size(); j++) {
                ResourceLocation stage = ResourceLocation.tryParse(ids.getString(j));
                if (stage == null) {
                    Constants.LOG.warn("'{}' is not a valid stage id in stored team stages",
                            ids.getString(j));
                    continue;
                }
                held.add(stage);
            }
            store.stagesByTeam.put(team, held);
        }

        return store;
    }

    @Override
    public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
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
            for (ResourceLocation stage : held) {
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
            for (ResourceLocation stage : held) {
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
