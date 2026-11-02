package dev.ellipog.tenet.party;

import dev.ellipog.armature.api.teams.Team;
import dev.ellipog.armature.api.teams.TeamManager;
import dev.ellipog.armature.api.teams.Teams;
import dev.ellipog.tenet.Constants;
import dev.ellipog.tenet.net.ProgressSyncPayload;
import dev.ellipog.tenet.net.TenetNetworking;
import dev.ellipog.tenet.progress.ProgressService;

import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * Notices party changes nobody told Tenet about, and does what the announcement would have done.
 *
 * <h2>Why this exists</h2>
 *
 * <p>Everything a membership change owes is done by Tenet's team listeners (Armature's own parties,
 * FTB Teams) or by Tenet's own {@code /tenet party} commands. A parties mod that announces nothing,
 * Open Parties and Claims, does neither when a player uses <i>its</i> commands: the progress owner moves
 * (every lookup asks the parties mod), but a player who left or was kicked lost what the party had
 * earned, because nothing ran {@link ProgressService#retainFor}, and every client went on drawing the
 * previous party's questline and roster until something unrelated refreshed it.
 *
 * <p>So once a second this asks where every player is, compares it with where they were last seen,
 * and for each player who moved: keeps what the party they left had earned in their own record, then
 * sends a full progress sync and a roster to everybody the move touched. That is the same work
 * {@code TenetPartyCommand.pushMembershipChange} does for a command on such a server.
 *
 * <h2>Why it does not double what the listeners and commands already do</h2>
 *
 * <p>Because they {@link #settle} what they handled: a command or a team event records where its
 * players now are, so the next look sees no difference. What is left to notice is exactly what nobody
 * announced. That makes the rule independent of the source, which matters more than it looks: the
 * source can change under a running server (Armature moves from its own parties to a parties mod when
 * that mod gains its first party), and a player whose party stopped being the answer has moved as
 * surely as one who typed a command.
 *
 * <h2>Who is asked</h2>
 *
 * <p>Everyone online, and everyone {@link PartyMemberships} has in a party, online or not. The second
 * half is what catches an offline member being kicked: what they keep is merged within a second of the
 * kick, not on their next login, when the party's record would hold whatever it did after they left.
 *
 * <p>A player with no record who was not seen this session is taken as they are, not as a move: absent
 * means solo or never seen, and the first look at somebody is a baseline. That covers the first start
 * after this was installed, when everybody already in a party is new to the record.
 */
public final class PartyWatch {

    /** How often the watch runs, in server ticks: once a second, like the lock refresh. */
    static final int POLL_TICKS = 20;

    /**
     * One membership that changed without being announced.
     *
     * @param player who moved
     * @param from   the party they were last seen in, or null for solo
     * @param to     the party they are in now, or null for solo
     */
    public record Move(UUID player, UUID from, UUID to) {
    }

    /** The watch's memory for one server. Server thread only. */
    private static final class State {
        private final MinecraftServer server;

        /** Players whose party is known this session, so a missing record means solo. */
        private final Set<UUID> seen = new HashSet<>();

        /** The game tick of the last poll, or null before the first. */
        private Long lastPoll;

        /** Whether a failure to ask the parties mod has been reported, so it is not every second. */
        private boolean failureReported;

        private State(MinecraftServer server) {
            this.server = server;
        }
    }

    private static State state;

    private PartyWatch() {
    }

    /**
     * The once-a-second check, called from the player tick.
     *
     * <p>A comparison against the game time rather than a counter, because the player tick fires once
     * per player and a counter would run faster with more people online. A clock that has gone backwards
     * is due, the rule {@code QuestSync.refreshLocks} follows for the same reason.
     */
    public static void tick(MinecraftServer server) {
        State current = stateFor(server);
        long now = server.overworld().getGameTime();
        if (current.lastPoll != null && now - current.lastPoll < POLL_TICKS && now >= current.lastPoll) {
            return;
        }
        current.lastPoll = now;
        try {
            poll(server, Teams.of(server));
        }
        catch (RuntimeException e) {
            // A parties mod whose API throws must not take the player tick with it. Reported once, and
            // quietly after that: the same failure every second is not news.
            if (!current.failureReported) {
                current.failureReported = true;
                Constants.LOG.warn("could not check party memberships ({}); a party change made "
                        + "through another mod's own commands may not be noticed", e.toString());
            }
            else {
                Constants.LOG.debug("could not check party memberships ({})", e.toString());
            }
        }
    }

    /**
     * One look at every membership, and the work for each one that moved.
     *
     * <p>The tick without its clock, and with the source passed in, so a test can hand it a parties mod
     * that announces nothing and move players around in it.
     *
     * @return the moves it found, in the order it acted on them
     */
    public static List<Move> poll(MinecraftServer server, TeamManager teams) {
        State current = stateFor(server);
        PartyMemberships store = PartyMemberships.of(server);

        Set<UUID> online = new LinkedHashSet<>();
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            online.add(player.getUUID());
        }
        Map<UUID, UUID> known = store.snapshot();
        Set<UUID> asked = new LinkedHashSet<>(online);
        asked.addAll(known.keySet());

        Map<UUID, UUID> now = new LinkedHashMap<>();
        for (UUID player : asked) {
            now.put(player, partyOf(teams, player));
        }

        List<Move> moves = moves(known, current.seen, now);
        now.forEach(store::record);
        current.seen.addAll(online);

        if (!moves.isEmpty()) {
            apply(server, teams, moves);
        }
        return moves;
    }

    /**
     * The moves between where players were last seen and where they are now.
     *
     * <p>The whole decision, and game-free so it can be tested case by case.
     *
     * @param known where each player was last seen; a player last seen solo is absent
     * @param seen  the players whose party is known this session, so absent from {@code known} means solo
     * @param now   where each asked player is now, null for solo
     */
    public static List<Move> moves(Map<UUID, UUID> known, Set<UUID> seen, Map<UUID, UUID> now) {
        List<Move> out = new ArrayList<>();
        now.forEach((player, party) -> {
            UUID before = known.get(player);
            if (before == null && !seen.contains(player)) {
                // The first look at somebody with no record: a baseline, not a move. See the class note.
                return;
            }
            if (!Objects.equals(before, party)) {
                out.add(new Move(player, before, party));
            }
        });
        return out;
    }

    /**
     * Records where these players are now, because whoever calls this has already done the work.
     *
     * <p>Called by the team listeners and by the party commands after a change they handled, so the
     * next look does not take it for one nobody announced and send everything a second time.
     */
    public static void settle(MinecraftServer server, Collection<UUID> players) {
        if (server == null || players.isEmpty()) {
            return;
        }
        TeamManager teams = Teams.of(server);
        PartyMemberships store = PartyMemberships.of(server);
        State current = stateFor(server);
        for (UUID player : players) {
            store.record(player, partyOf(teams, player));
            current.seen.add(player);
        }
    }

    /**
     * What an announcement would have done: keep what the left party earned, then tell everyone touched.
     *
     * <p>Retained first, so the sync each leaver receives already holds what they kept. The audience is
     * the mover and the members of both parties as they are now, and each gets a full sync (a delta
     * against another party's questline would merge the two) and their own roster, which is the
     * same pair {@code TenetPartyCommand.pushTo} sends. The browse list last, because a party's member
     * count moved and the solo screen draws it.
     */
    private static void apply(MinecraftServer server, TeamManager teams, List<Move> moves) {
        Set<UUID> affected = new LinkedHashSet<>();
        for (Move move : moves) {
            Constants.LOG.info("{} moved from {} to {} without Tenet being told (teams from '{}'); "
                            + "keeping what they earned and refreshing their book",
                    move.player(), describe(move.from()), describe(move.to()), teams.name());
            if (move.from() != null) {
                ProgressService.retainFor(server, move.player(), move.from());
                teams.byId(move.from()).ifPresent(team -> affected.addAll(team.memberIds()));
            }
            if (move.to() != null) {
                teams.byId(move.to()).ifPresent(team -> affected.addAll(team.memberIds()));
            }
            affected.add(move.player());
        }
        for (UUID player : affected) {
            TenetNetworking.sendProgressToPlayer(server, player, ProgressSyncPayload.REASON_TEAM_CHANGED);
            TenetNetworking.sendOwnRosterById(server, player);
        }
        TenetNetworking.sendPublicLists(server);
    }

    private static String describe(UUID party) {
        return party == null ? "no party" : "party " + party;
    }

    private static UUID partyOf(TeamManager teams, UUID player) {
        return teams.realTeamOf(player).map(Team::id).orElse(null);
    }

    private static State stateFor(MinecraftServer server) {
        if (state == null || state.server != server) {
            state = new State(server);
        }
        return state;
    }
}
