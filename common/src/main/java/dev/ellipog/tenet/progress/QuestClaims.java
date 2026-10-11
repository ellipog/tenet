package dev.ellipog.tenet.progress;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * Who has collected which of a quest's rewards.
 *
 * <h2>Per player by default, per team on request</h2>
 *
 * <p>A reward that says nothing goes to whoever claims it, and <b>every member</b> may claim their
 * own copy: the quest's progress is the team's, the payout is not. A reward marked {@code team: true}
 * is the other arrangement — the first claim settles it for the whole team — which is what a pack
 * author reaches for when the reward is a one-per-party thing (a shared key, a claim block) rather
 * than a payout.
 *
 * <p>That is the legacy model, and getting it wrong in the obvious direction — one claim per team
 * for everything — is the difference between a party of four each getting a diamond, and only the
 * first one to open the book getting one.
 *
 * <h2>Two maps, and neither is derivable from the other</h2>
 *
 * <p>{@code team} holds indices settled for the team (team-mode claims, and the auto-claims that
 * completion made on the team's behalf); {@code players} holds, per player, the indices they have
 * collected. A team-mode index is in the first and in nobody's second — a player-mode index is in
 * whoever claimed it and never in the first. The reward's own {@code team} flag decides which map a
 * question reads; see {@link #claimed}.
 */
public record QuestClaims(Set<Integer> team, Map<UUID, Set<Integer>> players) {

    public static final QuestClaims NONE = new QuestClaims(Set.of(), Map.of());

    public QuestClaims {
        team = Set.copyOf(team);
        Map<UUID, Set<Integer>> copied = new LinkedHashMap<>();
        players.forEach((player, indices) -> copied.put(player, Set.copyOf(indices)));
        players = Map.copyOf(copied);
    }

    /** Whether a particular claim is settled: the team's, or this player's, as the reward asks. */
    public boolean claimed(UUID player, int index, boolean teamReward) {
        if (teamReward) {
            return team.contains(index);
        }
        return players.getOrDefault(player, Set.of()).contains(index);
    }

    /** Whether any player has collected this index. Used for listings, not for a decision. */
    public boolean claimedByAnyone(int index) {
        if (team.contains(index)) {
            return true;
        }
        for (Set<Integer> indices : players.values()) {
            if (indices.contains(index)) {
                return true;
            }
        }
        return false;
    }

    public QuestClaims withTeamClaim(int index) {
        if (team.contains(index)) {
            return this;
        }
        Set<Integer> next = new LinkedHashSet<>(team);
        next.add(index);
        return new QuestClaims(next, players);
    }

    public QuestClaims withPlayerClaim(UUID player, int index) {
        Objects.requireNonNull(player, "player");
        Set<Integer> mine = players.getOrDefault(player, Set.of());
        if (mine.contains(index)) {
            return this;
        }
        Set<Integer> next = new LinkedHashSet<>(mine);
        next.add(index);
        Map<UUID, Set<Integer>> copied = new LinkedHashMap<>(players);
        copied.put(player, Set.copyOf(next));
        return new QuestClaims(team, copied);
    }

    /** Whether nothing at all is recorded. Cheap, for the wire's sparse fields. */
    public boolean isEmpty() {
        return team.isEmpty() && players.isEmpty();
    }
}
