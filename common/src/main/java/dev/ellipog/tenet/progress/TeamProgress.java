package dev.ellipog.tenet.progress;

import dev.ellipog.tenet.quest.Quest;

import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * One team's progress through every quest.
 *
 * <h2>Keys are quest ids, and that is how renaming stops being destructive</h2>
 *
 * <p>Progress is stored under whatever id a quest had when it was recorded. When a quest is looked
 * up, its own id is tried first and then each of its {@code aliases} — so renaming a quest and adding
 * the old name to {@code aliases} means the stored progress is still found, and the author's rename
 * costs nobody anything.
 *
 * <p>That is the promise the format makes, and this class is where it is kept. It costs one extra
 * lookup per quest per evaluation, and it is the difference between a supported operation and one
 * that silently wipes a playthrough.
 *
 * <h2>Unknown ids are kept</h2>
 *
 * <p>Progress for a quest that is no longer in any file is <b>not</b> discarded. A quest removed
 * temporarily, or a file that failed to load, would otherwise silently delete progress — and
 * deleting data to tidy up is a bad trade when the data is a player's. Unknown entries cost a few
 * bytes and are reported by {@link #orphanCount}.
 */
public final class TeamProgress {

    /** Quest id (as recorded) to progress. */
    private final Map<String, QuestProgress> quests = new LinkedHashMap<>();

    /**
     * Whether this team's rewards are blocked. See {@link ProgressService#isBlocked}.
     *
     * <p>A team-level lock, as in the legacy format: an operator can hold the whole team's payouts while an
     * event is on, and rewards marked to ignore blocking keep flowing. Not per-quest: blocking is a
     * statement about the team, not about one quest's file.
     */
    private boolean rewardsBlocked;

    public static TeamProgress empty() {
        return new TeamProgress();
    }

    public boolean rewardsBlocked() {
        return rewardsBlocked;
    }

    public TeamProgress withRewardsBlocked(boolean blocked) {
        TeamProgress out = with(quests);
        out.rewardsBlocked = blocked;
        return out;
    }

    /**
     * The progress for a quest, following aliases.
     *
     * <p>Returns {@link QuestProgress#NONE} rather than empty, so no caller has to write the fallback.
     */
    public QuestProgress progressOf(Quest quest) {
        QuestProgress direct = quests.get(quest.id());
        if (direct != null) {
            return direct;
        }
        for (String alias : quest.aliases()) {
            QuestProgress viaAlias = quests.get(alias);
            if (viaAlias != null) {
                return viaAlias;
            }
        }
        return QuestProgress.NONE;
    }

    /** How many stored entries no loaded quest claims, following aliases. Reported, not deleted. */
    public int orphanCount(java.util.function.Predicate<String> knownId) {
        return (int) quests.keySet().stream().filter(id -> !knownId.test(id)).count();
    }

    public int size() {
        return quests.size();
    }

    public java.util.Set<String> storedIds() {
        return java.util.Set.copyOf(quests.keySet());
    }

    /**
     * Stores progress under the quest's <b>current</b> id.
     *
     * <p>If the quest was found through an alias, this migrates the entry: the old key is removed and
     * the value written under the new one. So a rename is repaired the first time the quest is
     * touched, and the file does not accumulate stale ids forever.
     */
    public TeamProgress put(Quest quest, QuestProgress progress) {
        Map<String, QuestProgress> next = new LinkedHashMap<>(quests);
        for (String alias : quest.aliases()) {
            next.remove(alias);
        }
        next.put(quest.id(), progress);
        return with(next);
    }

    /**
     * Stores progress under an id directly, without a {@link Quest} to hand.
     *
     * <p>The quest-keyed {@link #put} is this plus alias migration. This form exists for the callers
     * that only have the id the entry is stored under — a round trip through a save file, a test, a
     * command naming a quest that is not loaded — and it removes nothing: what an unknown id should do
     * to aliases is a question only the quest itself can answer.
     */
    public TeamProgress withQuest(String id, QuestProgress progress) {
        Map<String, QuestProgress> next = new LinkedHashMap<>(quests);
        next.put(id, progress);
        return with(next);
    }

    /** Stored progress for an id exactly as recorded, aliases not followed. */
    public QuestProgress progressOfId(String id) {
        return quests.getOrDefault(id, QuestProgress.NONE);
    }

    /** A save with no world to hand. The registry provider is not used by this format. */
    public CompoundTag toTag() {
        return save(new CompoundTag());
    }

    /** The load counterpart of {@link #toTag}. */
    public static TeamProgress fromTag(CompoundTag tag) {
        return load(tag, null);
    }

    /** Removes a quest's progress entirely, aliases included. */
    public TeamProgress remove(Quest quest) {
        Map<String, QuestProgress> next = new LinkedHashMap<>(quests);
        next.remove(quest.id());
        quest.aliases().forEach(next::remove);
        return with(next);
    }

    /** Removes everything. */
    public static TeamProgress cleared() {
        return new TeamProgress();
    }

    private TeamProgress with(Map<String, QuestProgress> next) {
        TeamProgress out = new TeamProgress();
        out.quests.putAll(next);
        out.rewardsBlocked = rewardsBlocked;
        return out;
    }

    // ------------------------------------------------------------------
    // Persistence
    // ------------------------------------------------------------------

    public CompoundTag save(CompoundTag tag) {
        // Version 2 added per-reward claimed indices and the team's blocked flag; version 3 made the
        // claims per player. Older files read fine: version 1's single rewardsClaimed boolean and
        // version 2's claimedRewards array both still mean what they meant.
        tag.putInt("version", 3);
        tag.putBoolean("rewardsBlocked", rewardsBlocked);

        ListTag list = new ListTag();
        quests.forEach((id, progress) -> {
            CompoundTag entry = new CompoundTag();
            entry.putString("quest", id);
            entry.putString("state", progress.state().name());
            entry.putInt("timesCompleted", progress.timesCompleted());
            entry.putLong("lastCompletedAt", progress.lastCompletedAt());
            entry.putBoolean("rewardsClaimed", progress.rewardsClaimed());
            entry.putBoolean("legacySettled", progress.legacySettled());

            CompoundTag claims = new CompoundTag();
            claims.putIntArray("team", progress.claims().team().stream()
                    .mapToInt(Integer::intValue).sorted().toArray());
            ListTag players = new ListTag();
            progress.claims().players().forEach((player, indices) -> {
                CompoundTag one = new CompoundTag();
                one.putString("player", player.toString());
                one.putIntArray("rewards", indices.stream()
                        .mapToInt(Integer::intValue).sorted().toArray());
                players.add(one);
            });
            claims.put("players", players);
            entry.put("claims", claims);

            ListTag tasks = new ListTag();
            progress.taskProgress().forEach((index, amount) -> {
                CompoundTag task = new CompoundTag();
                task.putInt("index", index);
                task.putInt("amount", amount);
                tasks.add(task);
            });
            entry.put("tasks", tasks);

            list.add(entry);
        });
        tag.put("quests", list);

        return tag;
    }

    public static TeamProgress load(CompoundTag tag, HolderLookup.Provider registries) {
        TeamProgress progress = new TeamProgress();
        progress.rewardsBlocked = tag.getBoolean("rewardsBlocked");
        // A file written before per-player claims recorded "collected" as a single team-wide fact.
        // It is preserved as legacySettled -- collected for everyone, for good -- so the one thing a
        // migration must never do, pay an old quest's rewards a second time, cannot happen.
        int version = tag.getInt("version");

        ListTag list = tag.getList("quests", Tag.TAG_COMPOUND);
        for (int i = 0; i < list.size(); i++) {
            CompoundTag entry = list.getCompound(i);
            String id = entry.getString("quest");
            if (id.isEmpty()) {
                continue;
            }

            Map<Integer, Integer> tasks = new LinkedHashMap<>();
            ListTag taskList = entry.getList("tasks", Tag.TAG_COMPOUND);
            for (int t = 0; t < taskList.size(); t++) {
                CompoundTag task = taskList.getCompound(t);
                tasks.put(task.getInt("index"), task.getInt("amount"));
            }

            java.util.LinkedHashSet<Integer> teamClaims = new java.util.LinkedHashSet<>();
            // Version 2's flat array was team-wide claims; it reads as exactly that.
            for (int index : entry.getIntArray("claimedRewards")) {
                teamClaims.add(index);
            }
            Map<java.util.UUID, java.util.Set<Integer>> playerClaims = new java.util.LinkedHashMap<>();
            CompoundTag claims = entry.getCompound("claims");
            for (int index : claims.getIntArray("team")) {
                teamClaims.add(index);
            }
            ListTag players = claims.getList("players", Tag.TAG_COMPOUND);
            for (int p = 0; p < players.size(); p++) {
                CompoundTag one = players.getCompound(p);
                java.util.UUID player = parseUuid(one.getString("player"));
                if (player == null) {
                    continue;
                }
                java.util.LinkedHashSet<Integer> indices = new java.util.LinkedHashSet<>();
                for (int index : one.getIntArray("rewards")) {
                    indices.add(index);
                }
                playerClaims.put(player, indices);
            }

            boolean settled = entry.getBoolean("rewardsClaimed");
            progress.quests.put(id, new QuestProgress(
                    readState(entry.getString("state")),
                    tasks,
                    new QuestClaims(teamClaims, playerClaims),
                    settled,
                    version < 3 ? settled : entry.getBoolean("legacySettled"),
                    entry.getInt("timesCompleted"),
                    entry.getLong("lastCompletedAt")));
        }

        return progress;
    }

    /** A stored player id, or null when the string is not one. */
    private static java.util.UUID parseUuid(String raw) {
        try {
            return java.util.UUID.fromString(raw);
        }
        catch (IllegalArgumentException e) {
            return null;
        }
    }

    private static QuestState readState(String raw) {
        try {
            return QuestState.valueOf(raw);
        }
        catch (IllegalArgumentException e) {
            // A state name that no longer exists. LOCKED is the safe reading: it grants nothing, and
            // the quest recomputes its own state from the dependency graph on the next evaluation.
            dev.ellipog.tenet.Constants.LOG.warn("tenet: unknown stored quest state '{}', treating as LOCKED", raw);
            return QuestState.LOCKED;
        }
    }
}
