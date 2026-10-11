package dev.ellipog.tenet.progress;

import dev.ellipog.armature.api.teams.Teams;
import dev.ellipog.tenet.api.TenetEvents;
import dev.ellipog.tenet.net.TenetNetworking;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

import java.util.LinkedHashSet;
import java.util.Set;
import java.util.UUID;

/**
 * Stages, as everything else asks about them: the command, the stage reward, a quest's gate, and -- once
 * the scripting integration is in -- a pack's scripts.
 *
 * <h2>One method per change, not three calls per site</h2>
 *
 * <p>Granting a stage is a write, a sync to that player's client and an event, and doing those three at
 * every call site is how they drift: a stage saved but not synced is a client script that disagrees with
 * the server, and a stage synced but not announced is a listener that never runs. So the three live here
 * together, and a caller asks for the change rather than assembling it.
 *
 * <p>The write is per player, which is what a stage means -- see {@link ProgressStore}'s note. A player who
 * is offline takes the write and skips the rest: a script may legitimately grant a stage to somebody who is
 * not here, and refusing that would be the worse half of the trade.
 */
public final class StageService {

    private StageService() {
    }

    /** Whether this player has the stage. */
    public static boolean has(MinecraftServer server, UUID player, ResourceLocation stage) {
        return ProgressStore.of(server).hasStage(player, stage);
    }

    /** The same, for a player who is here. */
    public static boolean has(MinecraftServer server, ServerPlayer player, ResourceLocation stage) {
        return has(server, player.getUUID(), stage);
    }

    /** Every stage this player has, in the order they were granted. */
    public static Set<ResourceLocation> list(MinecraftServer server, UUID player) {
        return ProgressStore.of(server).stagesOf(player);
    }

    /**
     * Grants a stage.
     *
     * @return whether anything changed -- false when they already had it, which is what makes a stage reward
     *         idempotent and what the event is gated on
     */
    public static boolean add(MinecraftServer server, UUID player, ResourceLocation stage) {
        if (!ProgressStore.of(server).addStage(player, stage)) {
            return false;
        }
        ServerPlayer online = server.getPlayerList().getPlayer(player);
        if (online != null) {
            TenetNetworking.sendStagesTo(online);
            TenetEvents.STAGE_ADDED.invoker().onStageAdded(online, stage);
        }
        return true;
    }

    /** Takes a stage away, and returns whether it was there to take. */
    public static boolean remove(MinecraftServer server, UUID player, ResourceLocation stage) {
        if (!ProgressStore.of(server).removeStage(player, stage)) {
            return false;
        }
        ServerPlayer online = server.getPlayerList().getPlayer(player);
        if (online != null) {
            TenetNetworking.sendStagesTo(online);
            TenetEvents.STAGE_REMOVED.invoker().onStageRemoved(online, stage);
        }
        return true;
    }

    // ------------------------------------------------------------------
    // Team stages: the legacy `team_stage`, for the pack where one member's
    // induction opens the chapter for everybody
    // ------------------------------------------------------------------

    /**
     * Whether this team holds the stage.
     *
     * <p>The team's set, not any member's own: a stage a member holds personally does not answer for
     * the team, and a team stage does not answer for a member asked about personally. The sync is
     * what unions them for display — see {@code TenetNetworking.sendStagesTo}.
     */
    public static boolean hasTeam(MinecraftServer server, UUID team, ResourceLocation stage) {
        return ProgressStore.of(server).hasTeamStage(team, stage);
    }

    /** Every stage this team holds, in the order they were granted. */
    public static Set<ResourceLocation> listTeam(MinecraftServer server, UUID team) {
        return ProgressStore.of(server).stagesOfTeam(team);
    }

    /**
     * Grants a stage to a team.
     *
     * @return whether anything changed -- false when the team already held it
     */
    public static boolean addTeam(MinecraftServer server, UUID team, ResourceLocation stage) {
        if (!ProgressStore.of(server).addTeamStage(team, stage)) {
            return false;
        }
        pushTeam(server, team);
        for (ServerPlayer member : onlineMembersOf(server, team)) {
            TenetEvents.STAGE_ADDED.invoker().onStageAdded(member, stage);
        }
        return true;
    }

    /** Takes a team's stage away, and returns whether it was there to take. */
    public static boolean removeTeam(MinecraftServer server, UUID team, ResourceLocation stage) {
        if (!ProgressStore.of(server).removeTeamStage(team, stage)) {
            return false;
        }
        pushTeam(server, team);
        for (ServerPlayer member : onlineMembersOf(server, team)) {
            TenetEvents.STAGE_REMOVED.invoker().onStageRemoved(member, stage);
        }
        return true;
    }

    /**
     * What one member of a team sees: their own stages and their team's, together.
     *
     * <p>The union is what a client script or a screen asks about — "do I have this" — while the
     * store keeps the two sets apart so that leaving a party takes the team's half away without
     * touching the player's own. Sorted by insertion across both sets: the player's own first,
     * then the team's.
     */
    public static Set<ResourceLocation> effective(MinecraftServer server, UUID player) {
        Set<ResourceLocation> seen = new LinkedHashSet<>();
        seen.addAll(ProgressStore.of(server).stagesOf(player));
        seen.addAll(ProgressStore.of(server).stagesOfTeam(Teams.teamOf(server, player).id()));
        return Set.copyOf(seen);
    }

    /** Tells every online member of a team that its stages moved. */
    private static void pushTeam(MinecraftServer server, UUID team) {
        for (ServerPlayer member : onlineMembersOf(server, team)) {
            TenetNetworking.sendStagesTo(member);
        }
    }

    /** The online players whose progress owner is this team. */
    private static Set<ServerPlayer> onlineMembersOf(MinecraftServer server, UUID team) {
        Set<ServerPlayer> members = new LinkedHashSet<>();
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            if (ProgressService.progressOwner(server, player).equals(team)) {
                members.add(player);
            }
        }
        return members;
    }

    /** Pushes a player's stages to their own client. Called on join, with the tree and the roster. */
    public static void sync(ServerPlayer player) {
        TenetNetworking.sendStagesTo(player);
    }
}
