package dev.ellipog.tenet;

import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;

import dev.ellipog.armature.api.teams.Team;
import dev.ellipog.armature.api.teams.TeamFeature;
import dev.ellipog.armature.api.teams.TeamLimits;
import dev.ellipog.armature.api.teams.TeamManager;
import dev.ellipog.armature.api.teams.TeamPolicy;
import dev.ellipog.armature.api.teams.TeamRole;
import dev.ellipog.armature.api.teams.Teams;

import dev.ellipog.tenet.net.ProgressSyncPayload;
import dev.ellipog.tenet.net.TenetNetworking;
import dev.ellipog.tenet.party.PartyMode;
import dev.ellipog.tenet.party.PartyStore;
import dev.ellipog.tenet.progress.ProgressService;

import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * {@code /tenet party} — the party commands, on Armature's teams.
 *
 * <h2>This wraps; it does not implement</h2>
 *
 * <p>Every mutation below is one call to {@link TeamManager}: create, invite, accept, leave, kick,
 * disband. There is no membership logic in this file and there must not be — the whole reason teams
 * live in Armature is that membership is the general primitive, and a second implementation of
 * "who is in the party" here is how the party panel and the party commands come to disagree.
 *
 * <p>What this file adds is the three things a command has and an API does not: a permission
 * question, a player-facing message, and <b>telling the clients</b>.
 *
 * <h2>The source may not be ours, and that is the ordinary case rather than an edge case</h2>
 *
 * <p>A server running FTB Teams or Open Parties and Claims already has parties, and
 * {@link Teams#of} resolves to whichever of them it is. So:
 *
 * <ul>
 *   <li><b>A read-only source refuses loudly.</b> {@code TeamManager}'s mutators throw by default so
 *       that a source which cannot write says so instead of silently doing nothing — but a command
 *       that let that exception escape would present a library's exception as a crash. So every
 *       mutating subcommand asks {@link TeamManager#managesMembership()} first and, when the answer
 *       is no, says which source owns the parties on this server and where to go instead. That is
 *       strictly more useful than the exception, because it names the mod.</li>
 *   <li><b>A silent source needs the push, because nothing else will make it.</b> See
 *       {@link #pushMembershipChange}.</li>
 * </ul>
 *
 * <p><b>The mode is not gated on any of that</b>, and the distinction is worth stating because it
 * looks like an oversight. A {@link PartyMode} is not membership — it is a rule about how
 * <i>quest</i> counts combine, it lives in Tenet's own store, and it is readable and writable on a
 * server whose parties are entirely somebody else's. So {@code /tenet party mode} works everywhere,
 * including on the one server where {@code /tenet party create} has to refuse.
 *
 * <h2>Why promises here are not gated on operator permission</h2>
 *
 * <p>Because none of this is an operator's business. Creating a party, inviting somebody and leaving
 * are things a player does for themselves, and their authority over other players is checked by
 * Armature — {@code kick} refuses an actor who does not outrank the target, and {@code disband}
 * refuses anybody but the owner. Putting an op gate on top would mean a player cannot leave a party
 * without asking an admin, which is the sort of rule that gets a mod uninstalled.
 */
public final class TenetPartyCommand {

    private TenetPartyCommand() {
    }

    /** The {@code party} subtree, for {@link TenetCommand} to hang off {@code /tenet}. */
    public static LiteralArgumentBuilder<CommandSourceStack> node() {
        return Commands.literal("party")
                // No argument: what am I, and how do we count?
                .executes(TenetPartyCommand::info)

                .then(Commands.literal("create")
                        .then(Commands.argument("name", StringArgumentType.greedyString())
                                .executes(TenetPartyCommand::create)))

                .then(Commands.literal("invite")
                        .then(Commands.argument("player", EntityArgument.player())
                                .executes(TenetPartyCommand::invite)))

                // Bare accepts whichever invitation is waiting -- the shape this command had first,
                // and the one a player at a chat prompt wants. With an id it accepts *that* row's
                // invitation, which is what the panel's Accept button needs: a player can hold two
                // invitations, and a button on the second must not join the first party.
                .then(Commands.literal("accept")
                        .executes(TenetPartyCommand::accept)
                        .then(Commands.argument("party", StringArgumentType.word())
                                .executes(TenetPartyCommand::acceptTarget)))

                .then(Commands.literal("decline")
                        .then(Commands.argument("party", StringArgumentType.word())
                                .executes(TenetPartyCommand::decline)))

                // Joining an open party needs no invitation, so it is a command of its own rather
                // than an accept with different preconditions. See TeamManager.joinPublic.
                .then(Commands.literal("join")
                        .then(Commands.argument("party", StringArgumentType.word())
                                .executes(TenetPartyCommand::join)))

                .then(Commands.literal("rename")
                        .then(Commands.argument("name", StringArgumentType.greedyString())
                                .executes(TenetPartyCommand::rename)))

                .then(Commands.literal("transfer")
                        .then(Commands.argument("player", StringArgumentType.word())
                                .executes(TenetPartyCommand::transfer)))

                // Hand over *and* leave, in one validated operation: what the successor picker means.
                // Two commands from one press would leave the actor owned by nobody if the second
                // were refused, and the second is the one that must not half-happen.
                .then(Commands.literal("handover")
                        .then(Commands.argument("player", StringArgumentType.word())
                                .executes(TenetPartyCommand::handover)))

                .then(Commands.literal("uninvite")
                        .then(Commands.argument("player", StringArgumentType.word())
                                .executes(TenetPartyCommand::uninvite)))

                // Both settings read with no argument and write with one, which is the shape `mode`
                // already has. The value is a word rather than Brigadier's bool so that "on" and
                // "off" are what the panel's switches say, and "true"/"false" are accepted too.
                .then(Commands.literal("open")
                        .executes(context -> settings(context, true))
                        .then(Commands.argument("value", StringArgumentType.word())
                                .executes(context -> setSetting(context, true))))

                .then(Commands.literal("member-invites")
                        .executes(context -> settings(context, false))
                        .then(Commands.argument("value", StringArgumentType.word())
                                .executes(context -> setSetting(context, false))))

                .then(Commands.literal("leave")
                        .executes(TenetPartyCommand::leave))

                .then(Commands.literal("kick")
                        .then(Commands.argument("player", EntityArgument.player())
                                .executes(TenetPartyCommand::kick)))

                .then(Commands.literal("disband")
                        .executes(TenetPartyCommand::disband))

                // `mode` with no argument reads, which is the shape every other toggle in this mod
                // has and the reason it is not two commands.
                .then(Commands.literal("mode")
                        .executes(TenetPartyCommand::mode)
                        .then(Commands.argument("mode", StringArgumentType.word())
                                .executes(TenetPartyCommand::setMode)));
    }

    // ------------------------------------------------------------------
    // Reading
    // ------------------------------------------------------------------

    /**
     * {@code /tenet party} — who is in this party, and how it counts.
     *
     * <p>Prints the source as well as the party, and that is the line that answers the question a
     * server operator actually has: whether the parties their players see are Tenet's or another
     * mod's. It is the same answer {@code Tenet}'s own boot log gives, at the moment somebody is
     * looking for it.
     */
    private static int info(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
        ServerPlayer player = context.getSource().getPlayerOrException();
        MinecraftServer server = context.getSource().getServer();
        TeamManager teams = Teams.of(server);
        Team team = teams.teamOf(player.getUUID());

        // The source first, because it is the line that answers the question a server operator
        // actually has — whether the parties their players see are Tenet's or another mod's — and
        // because it is the only place that fact is stated outside a boot log.
        context.getSource().sendSuccess(() -> Component.translatable("tenet.command.party.source",
                teams.name()), false);

        if (!team.persistent()) {
            context.getSource().sendSuccess(() -> Component.translatable("tenet.command.party.solo"), false);
        }
        else {
            // The panel's key for the same state, so an unnamed party reads the same on both surfaces.
            Component name = team.name().isEmpty()
                    ? Component.translatable("tenet.screen.party.unnamed")
                    : Component.literal(team.name());
            context.getSource().sendSuccess(() -> Component.translatable("tenet.command.party.header",
                    name, team.size()), false);

            // Sorted by authority then id, so the owner is first and the listing reads the same way
            // twice. `members()` is an immutable map whose iteration order is deliberately
            // unspecified, so without this the lines would shuffle between calls for no reason a
            // reader could see — and a member scanning for their own name would find it in a
            // different place each time.
            team.members().entrySet().stream()
                    .sorted(java.util.Map.Entry
                            .<UUID, TeamRole>comparingByValue(
                                    java.util.Comparator.comparingInt(TeamRole::authority).reversed())
                            .thenComparing(entry -> entry.getKey().toString()))
                    .forEach(entry -> {
                        String who = nameOf(server, entry.getKey());
                        boolean self = entry.getKey().equals(player.getUUID());
                        // The role word comes from the one mapping the panel uses too, so a translator
                        // changes it once and the command cannot drift from the panel.
                        context.getSource().sendSuccess(() -> Component.translatable(
                                self ? "tenet.command.party.member_you" : "tenet.command.party.member",
                                PartyWords.role(entry.getValue()), who), false);
                    });
        }

        // And how it counts, on the same screen as who is in it — because the two questions are the
        // same question from a player's point of view: what does being in this party mean.
        UUID owner = ownerOf(server, player);
        PartyMode mode = PartyStore.of(server).modeOf(owner);
        context.getSource().sendSuccess(() -> Component.translatable("tenet.command.party.mode",
                mode.id(), mode.description()), false);

        return 1;
    }

    private static int mode(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
        ServerPlayer player = context.getSource().getPlayerOrException();
        MinecraftServer server = context.getSource().getServer();

        UUID owner = TenetPartyCommand.ownerOf(server, player);
        PartyMode current = PartyStore.of(server).modeOf(owner);

        context.getSource().sendSuccess(() -> Component.translatable("tenet.command.party.mode",
                current.id(), current.description()), false);
        context.getSource().sendSuccess(() -> Component.translatable("tenet.command.party.mode.options",
                PartyMode.ids()), false);
        return 1;
    }

    // ------------------------------------------------------------------
    // Writing
    // ------------------------------------------------------------------

    private static int create(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
        ServerPlayer player = context.getSource().getPlayerOrException();
        MinecraftServer server = context.getSource().getServer();
        TeamManager teams = teamsOrRefuse(context);
        if (teams == null) {
            return 0;
        }

        String name = StringArgumentType.getString(context, "name");

        if (teams.realTeamOf(player.getUUID()).isPresent()) {
            context.getSource().sendFailure(Component.translatable("tenet.command.party.already_in"));
            return 0;
        }

        // The name rule is checked here so the refusal can say what is wrong, and the manager checks
        // it again so the rule is true rather than merely reported -- see TeamLimits.
        if (!TeamLimits.isValidName(name)) {
            context.getSource().sendFailure(Component.translatable("tenet.command.party.name_invalid",
                    TeamLimits.MAX_NAME_LENGTH));
            return 0;
        }

        Team[] created = new Team[1];
        try {
            created[0] = teams.create(name, player.getUUID());
        }
        catch (RuntimeException e) {
            // A source's own refusal, with the source's own words. Armature's stored manager throws
            // IllegalStateException for an owner already in a team -- checked above, so reaching here
            // means a foreign source declined for a reason only it can explain.
            context.getSource().sendFailure(Component.literal(e.getMessage() == null
                    ? e.toString() : e.getMessage()));
            return 0;
        }

        // The creator is the only member, and their progress owner has just moved from their own solo
        // id to the party's -- so their client is now showing their solo questline against a party's
        // empty progress. That is a change worth telling them about, and it is the case the
        // TEAM_CREATED listener exists for.
        pushMembershipChange(context.getSource(), teams, Set.of(), created[0].memberIds(), player.getUUID());

        context.getSource().sendSuccess(() -> Component.translatable("tenet.command.party.created",
                created[0].name()), false);
        return 1;
    }

    private static int invite(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
        ServerPlayer player = context.getSource().getPlayerOrException();
        ServerPlayer target = EntityArgument.getPlayer(context, "player");
        TeamManager teams = teamsOrRefuse(context);
        if (teams == null) {
            return 0;
        }

        Optional<Team> mine = teams.realTeamOf(player.getUUID());
        if (mine.isEmpty()) {
            context.getSource().sendFailure(Component.translatable("tenet.command.party.not_in"));
            return 0;
        }
        Team team = mine.get();

        // Who may invite is the team's own rule -- an officer always, an ordinary member while the
        // policy says so -- and asking it rather than re-deriving it here is what keeps this command
        // and the manager from disagreeing about the switch. The message names the switch, because
        // "you may not" with the reason left out is the refusal that gets reported as a bug.
        if (!team.canInvite(player.getUUID())) {
            context.getSource().sendFailure(Component.translatable("tenet.command.party.cannot_invite"));
            return 0;
        }

        // The actor is passed, unlike before, so the manager enforces the same rule the check above
        // just read. A caller known to be the owner would have worked for the old shape; the switch
        // is what makes the actor part of the question.
        if (!teams.invite(player.getUUID(), team.id(), target.getUUID())) {
            context.getSource().sendFailure(Component.translatable("tenet.command.party.invite_failed",
                    target.getScoreboardName()));
            return 0;
        }

        // A source that fires events pushes through Tenet's INVITE_CHANGED listener -- the party's
        // outgoing list moved, and the invitee's incoming list appeared where there was none. A
        // source that fires nothing needs the push from here or not at all, which is the same split
        // pushMembershipChange documents for membership.
        if (!teams.firesEvents()) {
            TenetNetworking.sendPartyToTeam(context.getSource().getServer(), team.id());
            TenetNetworking.sendOwnRosterById(context.getSource().getServer(), target.getUUID());
        }

        context.getSource().sendSuccess(() -> Component.translatable("tenet.command.party.invited",
                target.getScoreboardName(), team.name()), false);
        // The invitee is told even though their progress did not move, because they now have
        // something to accept and no other line will say so.
        target.sendSystemMessage(Component.translatable("tenet.party.invited_you", team.name()));
        return 1;
    }

    private static int accept(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
        // The bare form: whichever invitation is waiting, which is what the command did before an
        // invitation had an id on it. The targeted form is what the panel's row uses.
        return acceptFrom(context, null);
    }

    private static int acceptTarget(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
        return acceptFrom(context, partyArg(context));
    }

    private static int acceptFrom(CommandContext<CommandSourceStack> context, UUID teamId)
            throws CommandSyntaxException {
        ServerPlayer player = context.getSource().getPlayerOrException();
        TeamManager teams = teamsOrRefuse(context);
        if (teams == null) {
            return 0;
        }

        // Both sides captured before the change, because after it the old team may be gone -- when a
        // player accepts an invite while in a party of one, the old team stops existing, and asking
        // for its members afterwards would find nothing and leave its... no: it had one member, who
        // is the player being told anyway. Captured before regardless, because the general case is a
        // player leaving a party of four to join another, and those three are owed a sync.
        Set<UUID> before = memberIdsOf(teams, player.getUUID());
        // And which team that was, because accepting while in a party leaves it -- the same retained
        // copy applies, and only the id can say which record to merge afterwards.
        UUID previousTeam = teams.realTeamOf(player.getUUID()).map(Team::id).orElse(null);

        // A malformed id is "no invitation to accept" rather than a parse failure with a stack: the
        // id came from a panel row, and a row whose party no longer exists is the same state as a row
        // whose id cannot be read.
        Optional<Team> joined = teamId == null
                ? teams.acceptInvite(player.getUUID())
                : teams.acceptInvite(player.getUUID(), teamId);
        if (joined.isEmpty()) {
            context.getSource().sendFailure(Component.translatable("tenet.command.party.no_invite"));
            return 0;
        }

        if (previousTeam != null && !previousTeam.equals(joined.get().id())) {
            // Switching parties is still leaving one, so the nodes earned in the old one are kept.
            ProgressService.retainFor(context.getSource().getServer(), player.getUUID(), previousTeam);
        }

        pushMembershipChange(context.getSource(), teams, before, joined.get().memberIds(), player.getUUID());

        context.getSource().sendSuccess(() -> Component.translatable("tenet.command.party.joined",
                joined.get().name()), false);
        return 1;
    }

    private static int decline(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
        ServerPlayer player = context.getSource().getPlayerOrException();
        TeamManager teams = teamsOrRefuse(context);
        if (teams == null) {
            return 0;
        }
        if (!teams.supports(TeamFeature.INVITE_DECLINE)) {
            return featureRefused(context, teams, "decline an invitation");
        }

        UUID teamId = partyArg(context);
        if (teamId == null || !teams.declineInvite(player.getUUID(), teamId)) {
            context.getSource().sendFailure(Component.translatable("tenet.command.party.decline_failed"));
            return 0;
        }

        String name = teams.byId(teamId).map(Team::name).orElse(teamId.toString());
        context.getSource().sendSuccess(() -> Component.translatable("tenet.command.party.declined", name), false);
        return 1;
    }

    private static int join(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
        ServerPlayer player = context.getSource().getPlayerOrException();
        TeamManager teams = teamsOrRefuse(context);
        if (teams == null) {
            return 0;
        }
        if (!teams.supports(TeamFeature.OPEN_JOIN)) {
            return featureRefused(context, teams, "join an open party");
        }

        UUID teamId = partyArg(context);
        Set<UUID> before = memberIdsOf(teams, player.getUUID());
        Optional<Team> joined = teamId == null
                ? Optional.empty()
                : teams.joinPublic(teamId, player.getUUID());
        if (joined.isEmpty()) {
            context.getSource().sendFailure(Component.translatable("tenet.command.party.join_failed"));
            return 0;
        }

        pushMembershipChange(context.getSource(), teams, before, joined.get().memberIds(), player.getUUID());

        context.getSource().sendSuccess(() -> Component.translatable("tenet.command.party.joined_open",
                joined.get().name()), false);
        return 1;
    }

    private static int rename(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
        ServerPlayer player = context.getSource().getPlayerOrException();
        TeamManager teams = teamsOrRefuse(context);
        if (teams == null) {
            return 0;
        }
        if (!teams.supports(TeamFeature.RENAME)) {
            return featureRefused(context, teams, "rename a party");
        }

        Optional<Team> mine = teams.realTeamOf(player.getUUID());
        if (mine.isEmpty()) {
            context.getSource().sendFailure(Component.translatable("tenet.command.party.not_in"));
            return 0;
        }
        Team team = mine.get();

        String name = StringArgumentType.getString(context, "name");
        if (!TeamLimits.isValidName(name)) {
            context.getSource().sendFailure(Component.translatable("tenet.command.party.name_invalid",
                    TeamLimits.MAX_NAME_LENGTH));
            return 0;
        }
        if (!team.isOwner(player.getUUID())) {
            context.getSource().sendFailure(
                    Component.translatable("tenet.command.party.not_owner_action"));
            return 0;
        }
        if (!teams.rename(player.getUUID(), team.id(), name)) {
            context.getSource().sendFailure(Component.translatable("tenet.command.party.rename_failed"));
            return 0;
        }

        context.getSource().sendSuccess(() -> Component.translatable("tenet.command.party.renamed",
                name.trim()), false);
        return 1;
    }

    private static int transfer(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
        ServerPlayer player = context.getSource().getPlayerOrException();
        TeamManager teams = teamsOrRefuse(context);
        if (teams == null) {
            return 0;
        }
        if (!teams.supports(TeamFeature.TRANSFER)) {
            return featureRefused(context, teams, "hand the party over");
        }

        Optional<Team> mine = teams.realTeamOf(player.getUUID());
        if (mine.isEmpty()) {
            context.getSource().sendFailure(Component.translatable("tenet.command.party.not_in"));
            return 0;
        }
        Team team = mine.get();

        UUID target = playerArg(context);
        if (target == null || !teams.transferOwnership(player.getUUID(), team.id(), target)) {
            context.getSource().sendFailure(Component.translatable("tenet.command.party.transfer_failed"));
            return 0;
        }

        String name = nameOf(context.getSource().getServer(), target);
        context.getSource().sendSuccess(() -> Component.translatable("tenet.command.party.transferred",
                name), false);
        return 1;
    }

    /**
     * {@code /tenet party handover <player>} — give the party away and leave it, in one press.
     *
     * <h2>Why this is one command rather than a transfer followed by a leave</h2>
     *
     * <p>Because the two halves have to happen together or not at all. The successor picker means "I am
     * leaving; they own it now", and a client sending two commands cannot know that the second
     * succeeded: a leave refused after a successful transfer would leave the player owned by somebody
     * else's party while believing they had gone. Here the transfer is validated first (the actor owns
     * it, the target is a member), and once it has happened the leave cannot fail for authority
     * because the actor is an ordinary member. Both mutations run on the server thread in one command,
     * so nothing observes the state between them.
     */
    private static int handover(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
        ServerPlayer player = context.getSource().getPlayerOrException();
        MinecraftServer server = context.getSource().getServer();
        TeamManager teams = teamsOrRefuse(context);
        if (teams == null) {
            return 0;
        }
        if (!teams.supports(TeamFeature.TRANSFER)) {
            return featureRefused(context, teams, "hand the party over");
        }

        Optional<Team> mine = teams.realTeamOf(player.getUUID());
        if (mine.isEmpty()) {
            context.getSource().sendFailure(Component.translatable("tenet.command.party.not_in"));
            return 0;
        }
        Team team = mine.get();

        UUID target = playerArg(context);
        Set<UUID> before = new LinkedHashSet<>(team.memberIds());
        if (target == null || !teams.transferOwnership(player.getUUID(), team.id(), target)) {
            context.getSource().sendFailure(Component.translatable("tenet.command.party.handover_failed"));
            return 0;
        }

        // Their retained copy, then the departure -- the same two steps `/tenet party leave` takes,
        // now that they are an ordinary member of a party somebody else owns.
        ProgressService.retainFor(server, player.getUUID(), team.id());
        if (!teams.leave(player.getUUID())) {
            context.getSource().sendFailure(Component.translatable("tenet.command.party.handover_failed"));
            return 0;
        }

        pushMembershipChange(context.getSource(), teams, before, memberIdsOf(teams, player.getUUID()),
                player.getUUID());

        String name = nameOf(server, target);
        context.getSource().sendSuccess(() -> Component.translatable("tenet.command.party.handovered",
                name), false);
        return 1;
    }

    private static int uninvite(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
        ServerPlayer player = context.getSource().getPlayerOrException();
        TeamManager teams = teamsOrRefuse(context);
        if (teams == null) {
            return 0;
        }
        if (!teams.supports(TeamFeature.INVITE_CANCEL)) {
            return featureRefused(context, teams, "withdraw an invitation");
        }

        Optional<Team> mine = teams.realTeamOf(player.getUUID());
        if (mine.isEmpty()) {
            context.getSource().sendFailure(Component.translatable("tenet.command.party.not_in"));
            return 0;
        }
        Team team = mine.get();

        UUID target = playerArg(context);
        if (target == null || !teams.cancelInvite(player.getUUID(), team.id(), target)) {
            context.getSource().sendFailure(Component.translatable("tenet.command.party.uninvite_failed"));
            return 0;
        }

        String name = nameOf(context.getSource().getServer(), target);
        context.getSource().sendSuccess(() -> Component.translatable("tenet.command.party.uninvited",
                name), false);
        return 1;
    }

    /** Reads one of the two party settings, with no argument to change it. */
    private static int settings(CommandContext<CommandSourceStack> context, boolean open)
            throws CommandSyntaxException {
        ServerPlayer player = context.getSource().getPlayerOrException();
        TeamManager teams = teamsOrRefuse(context);
        if (teams == null) {
            return 0;
        }
        Optional<Team> mine = teams.realTeamOf(player.getUUID());
        if (mine.isEmpty()) {
            context.getSource().sendFailure(Component.translatable("tenet.command.party.not_in"));
            return 0;
        }
        Team team = mine.get();

        if (open) {
            context.getSource().sendSuccess(() -> Component.translatable("tenet.command.party.setting.open",
                    boolWord(team.policy().openJoin())), false);
        }
        else {
            context.getSource().sendSuccess(() -> Component.translatable("tenet.command.party.setting.member_invites",
                    boolWord(team.policy().membersCanInvite())), false);
        }
        return 1;
    }

    /** Writes one of the two party settings. Owner only, like the panel's switches. */
    private static int setSetting(CommandContext<CommandSourceStack> context, boolean open)
            throws CommandSyntaxException {
        ServerPlayer player = context.getSource().getPlayerOrException();
        TeamManager teams = teamsOrRefuse(context);
        if (teams == null) {
            return 0;
        }
        if (!teams.supports(TeamFeature.POLICY)) {
            return featureRefused(context, teams, "change a party's settings");
        }

        Optional<Team> mine = teams.realTeamOf(player.getUUID());
        if (mine.isEmpty()) {
            context.getSource().sendFailure(Component.translatable("tenet.command.party.not_in"));
            return 0;
        }
        Team team = mine.get();

        if (!team.isOwner(player.getUUID())) {
            context.getSource().sendFailure(
                    Component.translatable("tenet.command.party.not_owner_action"));
            return 0;
        }

        String raw = StringArgumentType.getString(context, "value");
        Optional<Boolean> value = parseOnOff(raw);
        if (value.isEmpty()) {
            context.getSource().sendFailure(Component.translatable("tenet.command.party.setting_unknown",
                    raw));
            return 0;
        }

        TeamPolicy next = open
                ? team.policy().withOpenJoin(value.get())
                : team.policy().withMembersCanInvite(value.get());
        if (!teams.setPolicy(player.getUUID(), team.id(), next)) {
            context.getSource().sendFailure(Component.translatable("tenet.command.party.setting_failed"));
            return 0;
        }

        Component which = Component.translatable(open
                ? "tenet.command.party.setting.open_name"
                : "tenet.command.party.setting.member_invites_name");
        context.getSource().sendSuccess(() -> Component.translatable("tenet.command.party.setting_set",
                which, boolWord(value.get())), false);
        return 1;
    }

    private static int leave(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
        ServerPlayer player = context.getSource().getPlayerOrException();
        TeamManager teams = teamsOrRefuse(context);
        if (teams == null) {
            return 0;
        }

        Set<UUID> before = memberIdsOf(teams, player.getUUID());
        if (before.isEmpty()) {
            context.getSource().sendFailure(Component.translatable("tenet.command.party.not_in"));
            return 0;
        }

        // The retained copy, before the leave: what the party earned is merged into the player's own
        // record, so going solo cannot soft-lock a progression-gated pack. Called here as well as from
        // the team listener because the listener only runs where a server lifecycle fired it -- the
        // command is the path this file can guarantee -- and the merge is idempotent, so both running
        // is one result rather than two.
        retainFrom(context, teams, player.getUUID());

        // The manager's answer is checked, unlike before: a source that refuses (an Open Parties and
        // Claims owner cannot leave through our API) used to be told "Left the party" for a leave
        // that never happened.
        if (!teams.leave(player.getUUID())) {
            context.getSource().sendFailure(Component.translatable("tenet.command.party.leave_failed"));
            return 0;
        }

        pushMembershipChange(context.getSource(), teams, before, memberIdsOf(teams, player.getUUID()),
                player.getUUID());

        context.getSource().sendSuccess(() -> Component.translatable("tenet.command.party.left"), false);
        return 1;
    }

    private static int kick(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
        ServerPlayer player = context.getSource().getPlayerOrException();
        ServerPlayer target = EntityArgument.getPlayer(context, "player");
        TeamManager teams = teamsOrRefuse(context);
        if (teams == null) {
            return 0;
        }

        if (target.getUUID().equals(player.getUUID())) {
            context.getSource().sendFailure(Component.translatable("tenet.command.party.kick_self"));
            return 0;
        }

        Set<UUID> before = memberIdsOf(teams, target.getUUID());
        if (before.isEmpty()) {
            context.getSource().sendFailure(Component.translatable("tenet.command.party.target_not_in",
                    target.getScoreboardName()));
            return 0;
        }

        // A kick ends a membership like any other, and the removed player keeps what the party did --
        // a kick is a statement about behaviour, not a confiscation of quest nodes.
        retainFrom(context, teams, target.getUUID());

        // The authority check is Armature's, deliberately. `kick` returns false for an actor who does
        // not outrank the target -- and re-deriving that here would be a second copy of the rule,
        // which is how a command and an API come to disagree about who may remove whom.
        if (!teams.kick(player.getUUID(), target.getUUID())) {
            context.getSource().sendFailure(Component.translatable("tenet.command.party.no_authority",
                    target.getScoreboardName()));
            return 0;
        }

        pushMembershipChange(context.getSource(), teams, before, memberIdsOf(teams, target.getUUID()),
                target.getUUID());

        context.getSource().sendSuccess(() -> Component.translatable("tenet.command.party.kicked",
                target.getScoreboardName()), false);
        target.sendSystemMessage(Component.translatable("tenet.party.you_were_removed"));
        return 1;
    }

    private static int disband(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
        ServerPlayer player = context.getSource().getPlayerOrException();
        MinecraftServer server = context.getSource().getServer();
        TeamManager teams = teamsOrRefuse(context);
        if (teams == null) {
            return 0;
        }

        Optional<Team> mine = teams.realTeamOf(player.getUUID());
        if (mine.isEmpty()) {
            context.getSource().sendFailure(Component.translatable("tenet.command.party.not_in"));
            return 0;
        }
        Team team = mine.get();

        if (!team.owner().equals(player.getUUID())) {
            context.getSource().sendFailure(Component.translatable("tenet.command.party.not_owner"));
            return 0;
        }

        Set<UUID> before = new LinkedHashSet<>(team.memberIds());

        // Every member's retained copy, because a disband ends every membership at once. Before the
        // disband rather than after, so the party's record is still the one the merge reads -- and so
        // a failure to retain cannot leave a party already gone.
        for (UUID member : team.memberIds()) {
            ProgressService.retainFor(server, member, team.id());
        }

        if (!teams.disband(player.getUUID(), team.id())) {
            context.getSource().sendFailure(Component.translatable("tenet.command.party.disband_failed"));
            return 0;
        }

        // The mode goes with the party, and this is the one place Armature's own event cannot do it:
        // a source that fires no events never tells the store a party is gone, so on such a server the
        // entry is cleared here or not at all. A party disbanded through a foreign mod's own command
        // still leaves a line behind -- unreadable, since a team id is fresh every time, and the note
        // on PartyStore.clear says so rather than implying otherwise.
        PartyStore.of(server).clear(team.id());

        pushMembershipChange(context.getSource(), teams, before, Set.of(), player.getUUID());

        context.getSource().sendSuccess(() -> Component.translatable("tenet.command.party.disbanded",
                team.name()), false);
        return 1;
    }

    private static int setMode(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
        ServerPlayer player = context.getSource().getPlayerOrException();
        MinecraftServer server = context.getSource().getServer();
        String wanted = StringArgumentType.getString(context, "mode");

        Optional<PartyMode> mode = PartyMode.byId(wanted);
        if (mode.isEmpty()) {
            // One message for a bad name, saying what the names are. `PartyMode.ids()` rather than a
            // list written here, which is the same reason the mode is spelled one way for the file and
            // the command: a second spelling drifts the first time a mode is renamed.
            context.getSource().sendFailure(Component.translatable("tenet.command.party.mode_unknown",
                    wanted, PartyMode.ids()));
            return 0;
        }

        UUID owner = TenetPartyCommand.ownerOf(server, player);
        PartyStore store = PartyStore.of(server);

        if (store.modeOf(owner) == mode.get()) {
            context.getSource().sendSuccess(() -> Component.translatable("tenet.command.party.mode_same",
                    mode.get().id()), false);
            return 0;
        }

        store.put(owner, mode.get());

        // Pushed, because the counting rule has just changed and the whole party's view may have. A
        // delta rather than a full sync is correct and is what REASON_CHANGED means: progress is
        // monotonic, so a mode change can only raise what a party has, never lower it -- switching
        // from pooled back to one-member does not un-complete anything, because the engine keeps the
        // best count it has seen.
        TenetNetworking.sendProgressToTeam(server, player, ProgressSyncPayload.REASON_CHANGED);

        // And the roster, because the mode rides on the snapshot. `/tenet party` is what prints the
        // counting rule; the panel is re-sent so a party watching it reads the current membership and
        // state after the change rather than the one it had when the screen opened. (The panel does not
        // draw a mode row -- an earlier version of this comment said it did, which was never true.)
        TenetNetworking.sendPartyToTeam(server, owner);

        context.getSource().sendSuccess(() -> Component.translatable("tenet.command.party.mode_set",
                mode.get().id(), mode.get().description()), false);
        return 1;
    }

    // ------------------------------------------------------------------
    // The three things a command adds
    // ------------------------------------------------------------------

    /**
     * The team manager, or a refusal that names the source.
     *
     * <p>Returning null rather than throwing is the whole point of it existing. A source that cannot
     * write throws {@link UnsupportedOperationException} from every mutator — by design, so that it
     * says so rather than silently doing nothing — and letting that escape would present a library's
     * exception to a player as a crash. The message is strictly more useful than the exception,
     * because it names which mod owns the parties on this server.
     */
    private static TeamManager teamsOrRefuse(CommandContext<CommandSourceStack> context) {
        TeamManager teams = Teams.of(context.getSource().getServer());

        // Singleplayer with LAN closed, and the one branch in either mod that treats a singleplayer
        // world as anything other than a server. A party of one on a world nobody can join is a
        // panel that can only ever show an empty roster and a Create button, so it is refused here
        // with the one instruction that changes it. The check is the server's own -- isPublished is
        // what "Open to LAN" sets -- rather than a client-side guess, because a command can be typed
        // without a panel and must answer the same way the panel does.
        MinecraftServer server = context.getSource().getServer();
        if (server.isSingleplayer() && !server.isPublished()) {
            context.getSource().sendFailure(Component.translatable("tenet.command.party.singleplayer"));
            return null;
        }

        if (!teams.managesMembership()) {
            context.getSource().sendFailure(Component.translatable("tenet.command.party.not_ours",
                    teams.name()));
            return null;
        }
        return teams;
    }

    /**
     * A refusal that names the source, for an operation the source writes but cannot do.
     *
     * <p>The same shape as the read-only refusal and for the same reason: Open Parties and Claims
     * manages membership and cannot rename a party, so "which source owns this" is the useful half of
     * the answer, and a raw {@code UnsupportedOperationException} from a library is not.
     */
    private static int featureRefused(CommandContext<CommandSourceStack> context, TeamManager teams,
                                      String what) {
        context.getSource().sendFailure(Component.translatable("tenet.command.party.feature_not_ours",
                teams.name(), what));
        return 0;
    }

    /**
     * Tells the clients whose progress moved, when nothing else will.
     *
     * <h2>Why this is conditional, and why the condition is the honest one</h2>
     *
     * <p>A source that fires events ({@link TeamManager#firesEvents()} is true) announces its own
     * membership changes, and Tenet's listener on those events does the pushing. A source that does
     * not is one where this is the only chance anybody gets — a party changed and Armature was not
     * told. When both fired, every member would receive two full syncs for one change, and the
     * message count is exactly what the playthrough asserts on, so the duplication would be a failing
     * test rather than a shrug.
     *
     * <p>The condition is read off the interface rather than guessed from the source's name, so it
     * cannot go stale: FTB Teams bridges its party events and answers true, Open Parties and Claims
     * offers no party event to bridge and answers false, and this does the right thing for each
     * without naming either.
     *
     * <h2>The audience, and why it is a union</h2>
     *
     * <p>"The people who were in it" and "the people who are in it" are different sets, and a
     * membership change moves progress for both: the members who remain are now sharing with one more
     * or one fewer player, and the player who moved has had their progress owner change under them.
     * A caller that pushed to only the team as it now is would leave the leaver stale — which is the
     * same fault {@code sendTeamChange}'s {@code alsoThis} parameter exists for, at the other end.
     *
     * <p>So a caller passes the membership <b>before</b> and <b>after</b>, and the player, and this
     * works out the rest. Both are needed at the call sites anyway to describe what the command did,
     * and computing them there is what keeps this from having to ask "what changed" after the fact —
     * a question whose answer is already gone by then.
     */
    private static void pushMembershipChange(CommandSourceStack source,
                                             TeamManager teams,
                                             Set<UUID> before,
                                             Set<UUID> after,
                                             UUID subject) {
        if (teams.firesEvents()) {
            // The source's own event carries this, and Tenet's listener acts on it. Doing it here as
            // well would be two syncs for one change.
            return;
        }
        Set<UUID> affected = new LinkedHashSet<>(before);
        affected.addAll(after);
        affected.add(subject);
        pushTo(source, affected);
    }

    /** One full sync, and one roster, per player who needs them, skipping anybody offline. */
    private static void pushTo(CommandSourceStack source, Set<UUID> players) {
        MinecraftServer server = source.getServer();
        if (server == null) {
            return;
        }
        for (UUID player : players) {
            // The full-sync reason rather than REASON_CHANGED: a player whose progress owner has moved
            // is holding the previous team's progress entirely, and a delta against it would merge one
            // party's questline onto another's.
            TenetNetworking.sendProgressToPlayer(server, player, ProgressSyncPayload.REASON_TEAM_CHANGED);
            // And the roster, which this used to leave to the event listeners -- correct on a source
            // that fires events, which is the branch this method never runs on, and a silently stale
            // panel on one that does not. A source whose parties are somebody else's is exactly where
            // a player has no other way to be told their roster moved.
            TenetNetworking.sendOwnRosterById(server, player);
        }
    }

    // ------------------------------------------------------------------
    // Small helpers
    // ------------------------------------------------------------------

    /** The ids of {@code player}'s real team, or empty when they are solo. */
    private static Set<UUID> memberIdsOf(TeamManager teams, UUID player) {
        return teams.realTeamOf(player)
                .map(team -> (Set<UUID>) new LinkedHashSet<>(team.memberIds()))
                .orElseGet(Set::of);
    }

    private static UUID ownerOf(MinecraftServer server, ServerPlayer player) {
        return Teams.teamOf(server, player.getUUID()).id();
    }

    /**
     * The party id argument, or null when it cannot be read.
     *
     * <p>Null rather than a parse failure with a stack trace: the id comes from a panel row, and a row
     * whose party has been disbanded since the roster was drawn is the ordinary way for this to fail.
     * The caller reports the operation's own refusal, which is the same sentence a live-but-wrong id
     * gets.
     */
    private static UUID partyArg(CommandContext<CommandSourceStack> context) {
        String raw = StringArgumentType.getString(context, "party");
        try {
            return UUID.fromString(raw);
        }
        catch (IllegalArgumentException e) {
            return null;
        }
    }

    /**
     * The player a name argument names, online or known to this server.
     *
     * <p>A plain word rather than a player selector, and the reason is that both callers act on
     * somebody who may be offline: a party's outgoing list shows who is invited whether or not they
     * are connected, and cancelling an invitation to somebody who logged off must work. A name is what
     * both lists carry — a roster is drawn from names — so a name is what the argument takes.
     *
     * <p>Online first, because that is the answer whose case and spelling are authoritative, then the
     * profile cache, which is where a player who has ever joined this server is remembered. Null when
     * neither knows them, which the callers report as the operation's own refusal.
     */
    private static UUID playerArg(CommandContext<CommandSourceStack> context) {
        return playerByName(context.getSource().getServer(), StringArgumentType.getString(context, "player"));
    }

    private static UUID playerByName(MinecraftServer server, String name) {
        ServerPlayer online = server.getPlayerList().getPlayerByName(name);
        if (online != null) {
            return online.getUUID();
        }
        // The profile cache is gone; the name-to-id cache is its successor for offline names.
        if (server.services() == null) {
            // The headless test server has none; a real one always does.
            return null;
        }
        return server.services().nameToIdCache().get(name)
                .map(net.minecraft.server.players.NameAndId::id).orElse(null);
    }

    /** How a boolean reads in a message: the words the panel's switches use. */
    private static Component boolWord(boolean value) {
        return Component.translatable(value ? "tenet.command.party.on" : "tenet.command.party.off");
    }

    /** How a boolean is typed into a command: on/off, plus the true/false Brigadier would have taken. */
    private static Optional<Boolean> parseOnOff(String raw) {
        return switch (raw.toLowerCase(Locale.ROOT)) {
            case "on", "true", "yes" -> Optional.of(true);
            case "off", "false", "no" -> Optional.of(false);
            default -> Optional.empty();
        };
    }

    /** A player's name, or their id when they are offline. */
    private static String nameOf(MinecraftServer server, UUID player) {
        ServerPlayer found = server.getPlayerList().getPlayer(player);
        return found != null ? found.getScoreboardName() : player.toString();
    }

    /**
     * The party's progress, kept in the player's own record before their membership ends.
     *
     * <p>The command-side half of what {@code Tenet}'s team listener does; see its comment for why
     * both exist. A no-op for a solo player, which is what makes it safe to call before any mutation
     * that might find nothing to do.
     */
    private static void retainFrom(CommandContext<CommandSourceStack> context, TeamManager teams,
                                   UUID player) {
        MinecraftServer server = context.getSource().getServer();
        teams.realTeamOf(player).ifPresent(team -> ProgressService.retainFor(server, player, team.id()));
    }
}
