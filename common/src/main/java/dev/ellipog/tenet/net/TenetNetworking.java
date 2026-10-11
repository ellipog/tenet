package dev.ellipog.tenet.net;

import dev.ellipog.armature.api.net.ArmatureNetwork;
import dev.ellipog.armature.api.teams.Team;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.ellipog.tenet.Constants;
import dev.ellipog.tenet.QuestAuthority;
import dev.ellipog.tenet.client.ClientChapterReplica;
import dev.ellipog.tenet.client.ClientEditReplies;
import dev.ellipog.tenet.client.ClientLocale;
import dev.ellipog.tenet.client.ClientPartyCache;
import dev.ellipog.tenet.client.ClientQuestCache;
import dev.ellipog.tenet.editor.EditorOp;
import dev.ellipog.tenet.editor.EditorOps;
import dev.ellipog.tenet.client.ClientTicker;
import dev.ellipog.tenet.progress.ProgressService;
import dev.ellipog.tenet.quest.CanvasElement;
import dev.ellipog.tenet.quest.ClickAction;
import dev.ellipog.tenet.quest.QuestIndex;
import dev.ellipog.tenet.quest.TenetQuests;
import dev.ellipog.tenet.quest.TreeRefresh;

import net.minecraft.core.Registry;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.levelgen.structure.Structure;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Tenet's payloads, declared once for both loaders, and the handlers that act on them.
 *
 * <h2>The declarations live here; the wiring does not</h2>
 *
 * <p>Armature holds each declaration until the loader asks for it, at whatever moment that loader
 * accepts payloads — which is not the same moment on both. So this class names no loader, and both
 * subprojects register the same list.
 *
 * <h2>Each handler runs where the payload arrived</h2>
 *
 * <p>On that side's game thread, which means a handler may touch game state directly. A client
 * handler must not assume it can reach a server, and a server handler must not assume the sender is
 * still connected by the time it runs. Both are checked where they matter rather than here.
 */
public final class TenetNetworking {

    private static int declared;
    private static boolean alreadyDeclared;

    /**
     * The half-received messages, one reassembler per payload type.
     *
     * <p>Two rather than one because a tree and a progress message are separate transfers with
     * separate ids — an id from one is meaningless in the other, and sharing a reassembler would mean
     * a tree chunk completing a progress message.
     *
     * <p>Static because there is one connection per client. A second connection would need a second
     * reassembler, and there is no second connection: the client's whole cache is static for the same
     * reason.
     */
    private static final SyncWire.Reassembler TREE = new SyncWire.Reassembler();
    private static final SyncWire.Reassembler PROGRESS = new SyncWire.Reassembler();

    /**
     * The locale's own, for the reason the two above are separate from each other.
     *
     * <p>A third rather than sharing the tree's: the locale is sent on its own channel and its transfer
     * ids come from the same counter, so a chunk of one could otherwise complete a message of the other
     * and hand the parser a locale where it expected a questline.
     */
    private static final SyncWire.Reassembler LOCALE = new SyncWire.Reassembler();

    /**
     * Every roster message sent, by the player it went to.
     *
     * <h2>Why a tally lives in production code</h2>
     *
     * <p>{@code QuestSync} keeps the same kind of count for the same reason, and its note applies here
     * word for word: the playthrough drives a real server and has no client, so the client's cache --
     * the thing a panel actually draws from -- cannot be read from a test. What <i>is</i> observable is
     * the message leaving this class, and that is the half a wrong listener gets wrong.
     *
     * <p>It is worth counting because a fault of exactly that shape was found here: the disband path
     * sent every member their progress and no roster, which was invisible while the panel closed on
     * every press and is a panel left reading a party that no longer exists now that it stays open.
     * This is what lets a test say "both members were told" rather than "the code that should have told
     * them was written".
     *
     * <p>A plain {@code HashMap}, written from the server thread and read through
     * {@code callOnServerThread} -- {@code QuestSync}'s arrangement, and its reasoning.
     */
    private static final Map<UUID, Integer> ROSTERS_SENT = new HashMap<>();

    private TenetNetworking() {
    }

    /** How many roster messages one player has been sent. Diagnostics, as {@code QuestSync}'s are. */
    public static int rostersSentTo(UUID player) {
        return ROSTERS_SENT.getOrDefault(player, 0);
    }

    /** Forgets the roster counts, so a caller can measure a window rather than a session. */
    public static void forgetRosters() {
        ROSTERS_SENT.clear();
    }

    /** How many payloads were declared. For a log line that says the wiring happened. */
    public static int declaredCount() {
        return declared;
    }

    /**
     * Declares every payload, once.
     *
     * <p>Called from common init, so it runs on both sides and before any loader has wired anything
     * up. Declaring is cheap and side-effect free; the loader does the work later.
     *
     * <h2>Idempotent on purpose</h2>
     *
     * <p>A second call would append a second copy of every declaration, and the loader would then
     * reject the duplicate — "payload already registered" — which reads as a mod conflict rather than
     * as a mod initialising twice. That is a real possibility rather than a theoretical one: a client
     * with an integrated server runs two entry points, and a test JVM runs them in one process. One
     * boolean is a cheap way to make the failure impossible instead of diagnosable.
     */
    public static void declare() {
        if (alreadyDeclared) {
            return;
        }
        alreadyDeclared = true;

        // --- the tree, server to client ---

        ArmatureNetwork.register(new ArmatureNetwork.Registration<>(
                QuestSyncPayload.TYPE,
                QuestSyncPayload.CODEC,
                ArmatureNetwork.Direction.TO_CLIENT,
                payload -> acceptTree(payload),
                null));

        // --- progress, server to client ---

        ArmatureNetwork.register(new ArmatureNetwork.Registration<>(
                ProgressSyncPayload.TYPE,
                ProgressSyncPayload.CODEC,
                ArmatureNetwork.Direction.TO_CLIENT,
                // The client's own tick count is read here rather than sent. A cooldown is a
                // countdown, and counting it down from a known point costs one subtraction per frame
                // instead of a packet per second.
                payload -> acceptProgress(payload),
                null));

        // --- the vitals overlay, server to client ---

        // One boolean, and it is the *server's* answer: `/tenet vitals` is an operator's command, so the
        // client is told what to draw rather than deciding for itself. See VitalsPayload for why the switch
        // has to travel through the server at all.
        ArmatureNetwork.register(new ArmatureNetwork.Registration<>(
                VitalsPayload.TYPE,
                VitalsPayload.CODEC,
                ArmatureNetwork.Direction.TO_CLIENT,
                payload -> dev.ellipog.tenet.client.Vitals.set(payload.on()),
                null));

        // --- the undo history being discarded, server to client ---

        // `/tenet reload` drops every open editor, so the server's undo stack is gone -- but the *undo
        // button* is the client's, drawn from counters the client moves itself. Without this the book kept
        // drawing a live undo over a history that had been thrown away, and pressing it did nothing and said
        // nothing. See `EditHistoryPayload` for why it is not a line on the reply.
        ArmatureNetwork.register(new ArmatureNetwork.Registration<>(
                EditHistoryPayload.TYPE,
                EditHistoryPayload.CODEC,
                ArmatureNetwork.Direction.TO_CLIENT,
                payload -> dev.ellipog.tenet.client.ClientEditHistory.discard(),
                null));

        // --- the reload's cross-file problems, server to client ---

        // The per-file validator inside `QuestEditor.save` sees one file at a time, so a dangling dependency,
        // a cycle between files and an id declared in two chapters are all invisible to it and were written
        // silently with only the server log knowing. They are faults of the *pack*, so only the index that
        // reads them all can find them -- and this carries its answer to the authors.
        ArmatureNetwork.register(new ArmatureNetwork.Registration<>(
                EditProblemsPayload.TYPE,
                EditProblemsPayload.CODEC,
                ArmatureNetwork.Direction.TO_CLIENT,
                payload -> dev.ellipog.tenet.client.ClientEditProblems.accept(payload.lines(), payload.text()),
                null));
        // --- submitting a task, client to server ---

        ArmatureNetwork.register(new ArmatureNetwork.Registration<>(
                SubmitTaskPayload.TYPE,
                SubmitTaskPayload.CODEC,
                ArmatureNetwork.Direction.TO_SERVER,
                null,
                TenetNetworking::handleSubmit));

        // --- collecting a finished quest's rewards, client to server ---

        ArmatureNetwork.register(new ArmatureNetwork.Registration<>(
                ClaimRewardPayload.TYPE,
                ClaimRewardPayload.CODEC,
                ArmatureNetwork.Direction.TO_SERVER,
                null,
                TenetNetworking::handleClaim));

        // --- and everything outstanding, for the rewards panel's one press ---

        ArmatureNetwork.register(new ArmatureNetwork.Registration<>(
                ClaimAllPayload.TYPE,
                ClaimAllPayload.CODEC,
                ArmatureNetwork.Direction.TO_SERVER,
                null,
                TenetNetworking::handleClaimAll));

        // --- and one chapter of it, for the claim menu's banner ---

        ArmatureNetwork.register(new ArmatureNetwork.Registration<>(
                ClaimChapterPayload.TYPE,
                ClaimChapterPayload.CODEC,
                ArmatureNetwork.Direction.TO_SERVER,
                null,
                TenetNetworking::handleClaimChapter));

        // --- and one reward on its own, for the rewards panel's rows ---

        ArmatureNetwork.register(new ArmatureNetwork.Registration<>(
                ClaimRewardEntryPayload.TYPE,
                ClaimRewardEntryPayload.CODEC,
                ArmatureNetwork.Direction.TO_SERVER,
                null,
                TenetNetworking::handleClaimRewardEntry));

        // --- a choice reward's entries, and the player's answer ---

        ArmatureNetwork.register(new ArmatureNetwork.Registration<>(
                ChoiceRewardPayload.TYPE,
                ChoiceRewardPayload.CODEC,
                ArmatureNetwork.Direction.TO_CLIENT,
                TenetNetworking::handleChoiceOffer,
                null));

        ArmatureNetwork.register(new ArmatureNetwork.Registration<>(
                ClaimChoicePayload.TYPE,
                ClaimChoicePayload.CODEC,
                ArmatureNetwork.Direction.TO_SERVER,
                null,
                TenetNetworking::handleClaimChoice));

        // --- a canvas press the client cannot run itself ---

        ArmatureNetwork.register(new ArmatureNetwork.Registration<>(
                ClickPayload.TYPE,
                ClickPayload.CODEC,
                ArmatureNetwork.Direction.TO_SERVER,
                null,
                TenetNetworking::handleClick));

        // --- one player's quest text, in the language they read ---
        //
        // The tree is broadcast and carries the canonical strings; a locale is one player's business
        // and travels on its own. The request exists because the server cannot see a language change
        // made after the login handshake -- see LocaleRequestPayload.

        ArmatureNetwork.register(new ArmatureNetwork.Registration<>(
                LocaleSyncPayload.TYPE,
                LocaleSyncPayload.CODEC,
                ArmatureNetwork.Direction.TO_CLIENT,
                TenetNetworking::handleLocale,
                null));

        ArmatureNetwork.register(new ArmatureNetwork.Registration<>(
                LocaleRequestPayload.TYPE,
                LocaleRequestPayload.CODEC,
                ArmatureNetwork.Direction.TO_SERVER,
                null,
                TenetNetworking::handleLocaleRequest));

        // --- what a grant had to drop, so the book can say it while it is open ---

        ArmatureNetwork.register(new ArmatureNetwork.Registration<>(
                RewardOverflowPayload.TYPE,
                RewardOverflowPayload.CODEC,
                ArmatureNetwork.Direction.TO_CLIENT,
                TenetNetworking::handleRewardOverflow,
                null));

        // --- how a sweep ended, and what became of a pick: the two answers the panel waits for ---

        ArmatureNetwork.register(new ArmatureNetwork.Registration<>(
                ClaimSummaryPayload.TYPE,
                ClaimSummaryPayload.CODEC,
                ArmatureNetwork.Direction.TO_CLIENT,
                TenetNetworking::handleClaimSummary,
                null));

        ArmatureNetwork.register(new ArmatureNetwork.Registration<>(
                ClaimChoiceResultPayload.TYPE,
                ClaimChoiceResultPayload.CODEC,
                ArmatureNetwork.Direction.TO_CLIENT,
                TenetNetworking::handleClaimChoiceResult,
                null));

        // --- a reward-level message, so the book can say it while it is open ---
        //
        // The server already says the same sentence in chat; this carries it into the book, where
        // the HUD (and the chat behind the screen) cannot be read. The same routing the overflow
        // and summary notices use, for the same reason.

        ArmatureNetwork.register(new ArmatureNetwork.Registration<>(
                RewardToastPayload.TYPE,
                RewardToastPayload.CODEC,
                ArmatureNetwork.Direction.TO_CLIENT,
                TenetNetworking::handleRewardToast,
                null));

        // --- the lists only the server has, server to client ---
        //
        // The dimensions and the structures, in one message. Both are lists the editor searches and the
        // client cannot build for itself -- a dimension is level data rather than a registry entry, and a
        // structure is a datapack registry a client is never sent -- and carrying them together is what
        // makes the rule readable in one place: a picker may only read a registry the client was given,
        // and anything else the server owns travels here. See ServerListsPayload.

        ArmatureNetwork.register(new ArmatureNetwork.Registration<>(
                ServerListsPayload.TYPE,
                ServerListsPayload.CODEC,
                ArmatureNetwork.Direction.TO_CLIENT,
                TenetNetworking::handleServerLists,
                null));

        // --- one player's stages, to that player ---

        ArmatureNetwork.register(new ArmatureNetwork.Registration<>(
                StageSyncPayload.TYPE,
                StageSyncPayload.CODEC,
                ArmatureNetwork.Direction.TO_CLIENT,
                TenetNetworking::handleStages,
                null));

        // --- a command asked for the quest book: open it, there ---

        ArmatureNetwork.register(new ArmatureNetwork.Registration<>(
                OpenBookPayload.TYPE,
                OpenBookPayload.CODEC,
                ArmatureNetwork.Direction.TO_CLIENT,
                TenetNetworking::handleOpenBook,
                null));

        // --- a command asked for the player's view: preview it, there ---
        //
        // The same shape as the book open above: the command runs on the server and the latch
        // lives on the client, so the server forwards the ask and the client's handler both parks
        // it and opens the book. See `PreviewPayload` for why the toggle travels rather than an
        // absolute the server cannot know.
        ArmatureNetwork.register(new ArmatureNetwork.Registration<>(
                PreviewPayload.TYPE,
                PreviewPayload.CODEC,
                ArmatureNetwork.Direction.TO_CLIENT,
                TenetNetworking::handlePreview,
                null));

        // --- one edit, client to server, and the server's answer ---
        //
        // The op path. A client asks; the server checks permission, applies the operation to its own model
        // of that chapter and writes it, and answers with what happened -- and then the tree is re-sent by
        // the same broadcast `/tenet reload` uses, so the canvas has one source of truth rather than two.

        ArmatureNetwork.register(new ArmatureNetwork.Registration<>(
                EditorOpPayload.TYPE,
                EditorOpPayload.CODEC,
                ArmatureNetwork.Direction.TO_SERVER,
                null,
                TenetNetworking::handleEditorOp));

        ArmatureNetwork.register(new ArmatureNetwork.Registration<>(
                EditorReplyPayload.TYPE,
                EditorReplyPayload.CODEC,
                ArmatureNetwork.Direction.TO_CLIENT,
                TenetNetworking::handleEditorReply,
                null));

        ArmatureNetwork.register(new ArmatureNetwork.Registration<>(
                ReplicaRequestPayload.TYPE,
                ReplicaRequestPayload.CODEC,
                ArmatureNetwork.Direction.TO_SERVER,
                null,
                TenetNetworking::handleReplicaRequest));

        ArmatureNetwork.register(new ArmatureNetwork.Registration<>(
                ChapterReplicaPayload.TYPE,
                ChapterReplicaPayload.CODEC,
                ArmatureNetwork.Direction.TO_CLIENT,
                TenetNetworking::handleChapterReplica,
                null));

        // --- a reward table's file, asked for by an editor ---
        ArmatureNetwork.register(new ArmatureNetwork.Registration<>(
                TableReplicaRequestPayload.TYPE,
                TableReplicaRequestPayload.CODEC,
                ArmatureNetwork.Direction.TO_SERVER,
                null,
                TenetNetworking::handleTableReplicaRequest));

        ArmatureNetwork.register(new ArmatureNetwork.Registration<>(
                TableReplicaPayload.TYPE,
                TableReplicaPayload.CODEC,
                ArmatureNetwork.Direction.TO_CLIENT,
                TenetNetworking::handleTableReplica,
                null));

        // --- a table's dice, rolled by the server ---
        ArmatureNetwork.register(new ArmatureNetwork.Registration<>(
                TableRollRequestPayload.TYPE,
                TableRollRequestPayload.CODEC,
                ArmatureNetwork.Direction.TO_SERVER,
                null,
                TenetNetworking::handleTableRollRequest));

        ArmatureNetwork.register(new ArmatureNetwork.Registration<>(
                TableRollPayload.TYPE,
                TableRollPayload.CODEC,
                ArmatureNetwork.Direction.TO_CLIENT,
                TenetNetworking::handleTableRoll,
                null));

        // --- a table an editor was told to open, and an import asked for ---
        ArmatureNetwork.register(new ArmatureNetwork.Registration<>(
                TableOpenPayload.TYPE,
                TableOpenPayload.CODEC,
                ArmatureNetwork.Direction.TO_CLIENT,
                TenetNetworking::handleTableOpen,
                null));

        ArmatureNetwork.register(new ArmatureNetwork.Registration<>(
                TableImportRequestPayload.TYPE,
                TableImportRequestPayload.CODEC,
                ArmatureNetwork.Direction.TO_SERVER,
                null,
                TenetNetworking::handleTableImport));

        // --- a party's roster, server to client ---
        //
        // Declared on both sides, and useless on a server, which is what every TO_CLIENT payload is: a
        // dedicated server never receives its own roster message. It costs one line in a table that both
        // loaders register from, and declaring it only on the client would mean the two loaders had
        // different payload lists -- which is the shape of bug that presents as "the panel works on
        // Fabric" and nothing says why.
        ArmatureNetwork.register(new ArmatureNetwork.Registration<>(
                PartySyncPayload.TYPE,
                PartySyncPayload.CODEC,
                ArmatureNetwork.Direction.TO_CLIENT,
                TenetNetworking::handlePartySync,
                null));

        declared = ArmatureNetwork.registeredCount();
        Constants.LOG.info("Tenet: declared {} payload(s)", declared);
    }

    // ------------------------------------------------------------------
    // The client's two handlers
    // ------------------------------------------------------------------

    /**
     * One chunk of the tree: reassemble, decompress, hand to the cache.
     *
     * <h2>Silence is the right answer to most of these</h2>
     *
     * <p>A chunk that did not complete a message returns null and nothing else happens — that is the
     * normal case for all but the last chunk, and logging it would be a line per chunk on every join.
     * A chunk that cannot be <i>placed</i> is different: {@link SyncWire.MalformedSync} means the
     * sender and this client disagree about the format, which is worth a line and is still not worth
     * a disconnect. A client that refused to connect because a pack it cannot parse appeared would be
     * a worse failure than one that shows nothing and says so.
     */
    private static void acceptTree(QuestSyncPayload payload) {
        byte[] json = completed(TREE, payload.chunk(), payload.data(), "tree");
        if (json == null) {
            return;
        }
        ClientQuestCache.acceptTree(payload.questCount(), payload.chapterCount(),
                payload.hasTheme() ? payload.packTheme() : null, json);
    }

    /** One chunk of progress: reassemble, decompress, and tell the cache whether it is a delta. */
    private static void acceptProgress(ProgressSyncPayload payload) {
        byte[] json = completed(PROGRESS, payload.chunk(), payload.data(), "progress");
        if (json == null) {
            return;
        }
        ClientQuestCache.acceptProgress(payload.teamId(), payload.gameTime(), json,
                ClientTicker.ticks(), payload.chunk().full());
    }

    /**
     * One chunk of the locale: reassemble, decompress, hand the overlay to the resolver.
     *
     * <p>Silence while a message is incomplete, a log line and a shrug when one cannot be read — the
     * same treatment the tree gets, and for the same reason: a client that refused to connect because
     * one pack file it cannot parse appeared would be a worse failure than one that shows the canonical
     * text and says nothing.
     *
     * <p>The JSON is a flat object of key to text, which is the shape a resource pack's language file
     * has. A member that is not a string is dropped rather than refused: a locale is an overlay, so the
     * worst a malformed entry can do is leave one key reading in the canonical language.
     */
    private static void handleLocale(LocaleSyncPayload payload) {
        byte[] json = completed(LOCALE, payload.chunk(), payload.data(), "locale");
        if (json == null) {
            return;
        }
        java.util.Map<String, String> entries = new java.util.LinkedHashMap<>();
        try {
            JsonObject root = JsonParser.parseString(new String(json, StandardCharsets.UTF_8))
                    .getAsJsonObject();
            for (java.util.Map.Entry<String, JsonElement> each : root.entrySet()) {
                if (each.getValue().isJsonPrimitive()
                        && each.getValue().getAsJsonPrimitive().isString()) {
                    entries.put(each.getKey(), each.getValue().getAsString());
                }
            }
        }
        catch (RuntimeException e) {
            Constants.LOG.warn("tenet: ignored an unreadable locale from the server: {}",
                    e.getMessage());
            return;
        }
        ClientLocale.accept(payload.locale(), payload.served(), entries);
    }

    /**
     * A client says it is reading in a different language now: answer with that locale.
     *
     * <p>The string is the client's own, so it is used as a <b>map key</b> and nothing else — the pack's
     * files were read at load, and this looks a language up among them. Anything that is not a locale id
     * resolves to nothing, and nothing is answered with an empty overlay rather than with silence, so
     * the client stops asking. See {@code QuestSync.sendLocaleFor}.
     */
    private static void handleLocaleRequest(LocaleRequestPayload payload, ServerPlayer sender) {
        QuestSync.sendLocaleFor(sender, payload.locale());
    }

    /**
     * A chunk's bytes once the message is whole, or null while it is not.
     *
     * <p>The one place the two handlers' error handling lives, so a malformed tree and a malformed
     * progress message are treated identically. They want the same treatment — log, ignore, stay
     * connected — and writing it twice is two places for one of them to start throwing instead.
     *
     * @param what what to call the message in a log line, so it names which one failed
     */
    private static byte[] completed(SyncWire.Reassembler reassembler, SyncChunk chunk, byte[] data,
                                    String what) {
        byte[] packed;
        try {
            packed = reassembler.accept(chunk, data);
        }
        catch (SyncWire.MalformedSync e) {
            Constants.LOG.warn("tenet: ignored an unplaceable {} chunk from the server: {}",
                    what, e.getMessage());
            return null;
        }
        if (packed == null) {
            return null;
        }

        try {
            return SyncWire.unpack(packed);
        }
        catch (SyncWire.MalformedSync e) {
            Constants.LOG.warn("tenet: ignored an unreadable {} message from the server: {}",
                    what, e.getMessage());
            return null;
        }
    }

    /**
     * Forgets every half-received message. Called on disconnect, beside the cache's own clear.
     *
     * <p>Without it a player who disconnects mid-tree leaves chunks in memory, and the transfer id of
     * the next connection's first message is a fresh one — so the leftovers are never completed and
     * never freed. The cap in {@link SyncWire.Reassembler} bounds that, and this is what stops it
     * being reached at all.
     */
    public static void forgetTransfers() {
        TREE.forgetAll();
        PROGRESS.forgetAll();
        LOCALE.forgetAll();
    }

    /** How many transfers are half-received. Diagnostics for a test. */
    public static int pendingTransfers() {
        return TREE.pendingTransfers() + PROGRESS.pendingTransfers() + LOCALE.pendingTransfers();
    }

    /**
     * A player pressed a task.
     *
     * <h2>Everything is re-checked here</h2>
     *
     * <p>The client sent two numbers and nothing else, and neither is trusted. Whether the quest
     * exists, whether it is unlocked, whether the task can be submitted by hand, and whether the items
     * are in the inventory are all decided by {@link ProgressService#submit} — the same method
     * {@code /tenet submit} calls. So a client that sends this for a quest it has not unlocked gets a
     * refusal, not progress.
     */
    private static void handleSubmit(SubmitTaskPayload payload, ServerPlayer sender) {
        QuestIndex index = TenetQuests.index();
        if (index.isEmpty()) {
            return;
        }

        // The index resolves aliases, so a client holding a stale id after a rename still works.
        var entry = index.quest(payload.questId());
        if (entry.isEmpty()) {
            Constants.LOG.warn("tenet: {} asked to submit a task of unknown quest '{}'",
                    sender.getScoreboardName(), payload.questId());
            return;
        }

        ServerPlayer server = sender;
        boolean changed = ProgressService.submit(server.getServer(), server, entry.get(), payload.taskIndex());
        if (changed) {
            // Everyone in the team needs the new state, not just the player who pressed it: progress
            // is shared, so a party member watching the same quest would otherwise not see it move.
            sendToTeam(server, ProgressSyncPayload.REASON_CHANGED);
        }
    }

    /**
     * A player pressed Claim.
     *
     * <h2>The server decides whether anything is owed</h2>
     *
     * <p>All this handler does is ask. {@link ProgressService#claim} checks that the quest is finished,
     * that it has rewards, and that they have not already been collected — so a client that sends this
     * for a quest it has not finished, or sends it twice, gets a refusal and no items.
     *
     * <p>The sync is sent whether or not the claim succeeded, and that is deliberate: a client showing
     * a Claim button the server disagrees with needs to be corrected, and the correction is the
     * progress it already knows how to read. Sending nothing on failure would leave the wrong button
     * on screen until something else happened to push progress.
     */
    private static void handleClaim(ClaimRewardPayload payload, ServerPlayer sender) {
        MinecraftServer server = sender.getServer();
        if (server == null || TenetQuests.index().isEmpty()) {
            return;
        }

        // The index resolves aliases, so a client holding a stale id after a rename still works.
        var entry = TenetQuests.index().quest(payload.questId());
        if (entry.isEmpty()) {
            Constants.LOG.warn("tenet: {} asked to claim the rewards of unknown quest '{}'",
                    sender.getScoreboardName(), payload.questId());
            return;
        }

        ProgressService.claim(server, sender, entry.get());
        sendToTeam(sender, ProgressSyncPayload.REASON_CHANGED);
    }

    /**
     * A player pressed a canvas element whose click runs on the server.
     *
     * <h2>Everything is re-checked here</h2>
     *
     * <p>The client sent a chapter and an element id and nothing else, and none of it is trusted. The
     * command or the event id comes from the server's own index — the file as the server loaded it —
     * and the click still has to <i>be</i> a server-side action: an element the author has since
     * changed to an {@code open_quest} is refused, not run as whatever it used to say. So a forged
     * payload names nothing, a stale one runs nothing, and neither can choose what the server runs.
     *
     * <p>Commands run through the server's own dispatcher with the player as the source, at the level
     * the pack configured — the player's own level, or elevated — and never above it, and never at
     * the presser's own level however high that is. The command's own output is the feedback: the
     * source is not suppressed, so the result gamerule decides what the presser sees, exactly as if
     * they had typed it themselves at that level.
     *
     * <p>Public for the playthrough: this handler is the cheat boundary, and the boundary is what
     * the playthrough presses — with forged ids, stale actions and a listener on the bus.
     */
    public static void handleClick(ClickPayload payload, ServerPlayer sender) {
        QuestIndex index = TenetQuests.index();
        MinecraftServer server = sender.getServer();
        if (server == null || index.isEmpty()) {
            return;
        }

        int level = TenetQuests.settings().clickCommandLevel();
        Optional<ClickCommand> command =
                resolveClickCommand(index, payload.chapterId(), payload.elementId(), level);
        if (command.isPresent()) {
            runClickCommand(server, sender, command.get());
            return;
        }
        Optional<ClickEventFire> event =
                resolveClickEvent(index, payload.chapterId(), payload.elementId());
        if (event.isPresent()) {
            ClickEventFire fire = event.get();
            dev.ellipog.tenet.api.TenetEvents.CLICK_EVENT.invoker()
                    .onClickEvent(sender, fire.event(), fire.chapterId(), fire.elementId());
            return;
        }
        Constants.LOG.warn("tenet: {} pressed a canvas element with no server-side action '{}:{}'",
                sender.getScoreboardName(), payload.chapterId(), payload.elementId());
    }

    /**
     * A press that runs a command: what the server read, and the level it runs at.
     *
     * <p>The level is the configured one, capped at 2 and floored at 0 — never the presser's own,
     * and never above the pack's answer. A click that escalated its presser would be a privilege
     * boundary drawn in the wrong place.
     */
    record ClickCommand(String chapterId, String elementId, String command, int level) {
    }

    /** A press that fires a script event: what the server read, and where the press landed. */
    record ClickEventFire(String chapterId, String elementId, ResourceLocation event) {
    }

    /**
     * The command a press runs, resolved against the authoritative index.
     *
     * <p>Empty for every press that must not run one: an unknown chapter, an element the chapter
     * does not hold, a click that is no longer a command, and a command that is blank. The caller
     * tries the event arm next, so "empty" here is "not a command" rather than "refused" — the
     * refusal is the warning when neither arm answers.
     */
    static Optional<ClickCommand> resolveClickCommand(QuestIndex index, String chapterId,
                                                      String elementId, int configuredLevel) {
        ClickAction click = clickOf(index, chapterId, elementId).orElse(null);
        if (click == null || click.type() != ClickAction.Type.RUN_COMMAND || click.data().isBlank()) {
            return Optional.empty();
        }
        return Optional.of(new ClickCommand(chapterId, elementId, click.data(),
                Math.max(0, Math.min(2, configuredLevel))));
    }

    /**
     * The script event a press fires, resolved against the authoritative index.
     *
     * <p>Empty for every press that must not fire one, for the same reasons as the command arm: an
     * unknown chapter or element, a click that is no longer an event, and an id that is not one.
     */
    static Optional<ClickEventFire> resolveClickEvent(QuestIndex index, String chapterId, String elementId) {
        ClickAction click = clickOf(index, chapterId, elementId).orElse(null);
        if (click == null || click.type() != ClickAction.Type.CUSTOM_EVENT) {
            return Optional.empty();
        }
        ResourceLocation event =
                click.data() == null ? null : ResourceLocation.tryParse(click.data());
        if (event == null) {
            return Optional.empty();
        }
        return Optional.of(new ClickEventFire(chapterId, elementId, event));
    }

    /**
     * The click the chapter holds for the element, or empty.
     *
     * <p>Only images are pressable, so only an image's click can answer: a press naming anything
     * else names no action, whatever the array around it holds.
     */
    private static Optional<ClickAction> clickOf(QuestIndex index, String chapterId, String elementId) {
        if (index == null || chapterId == null || elementId == null) {
            return Optional.empty();
        }
        return index.chapter(chapterId)
                .flatMap(entry -> entry.chapter().elements().stream()
                        .filter(element -> element instanceof CanvasElement.Image)
                        .map(element -> (CanvasElement.Image) element)
                        .filter(image -> image.id().equals(elementId))
                        .map(CanvasElement.Image::click)
                        .findFirst());
    }

    /** Runs a resolved click command as the pressing player, at the resolved level. */
    private static void runClickCommand(MinecraftServer server, ServerPlayer sender, ClickCommand resolved) {
        net.minecraft.commands.CommandSourceStack source = sender.createCommandSourceStack();
        if (resolved.level() > 0) {
            source = source.withPermission(resolved.level());
        }
        var pos = sender.blockPosition();
        server.getCommands().performPrefixedCommand(source, substituteClick(resolved.command(),
                sender.getScoreboardName(), pos.getX(), pos.getY(), pos.getZ(), resolved.chapterId(),
                resolved.elementId()));
    }

    /**
     * A click command with every placeholder filled in.
     *
     * <p>An unknown brace-word is left as written rather than blanked: a command that says
     * {@code {player}} to an operator reading the log is a one-second fix, and a command that
     * silently loses the word is a mystery. That is the command reward's own rule, and a click
     * shares its vocabulary on purpose: a pack moved from the legacy format should not have to learn a
     * second one for the same sentence.
     */
    static String substituteClick(String command, String playerName, int x, int y, int z,
                                  String chapterId, String elementId) {
        return command
                .replace("{p}", playerName)
                .replace("{x}", String.valueOf(x))
                .replace("{y}", String.valueOf(y))
                .replace("{z}", String.valueOf(z))
                .replace("{chapter}", chapterId)
                .replace("{element}", elementId);
    }

    /**
     * A player pressed Claim on one row of the rewards panel.
     *
     * <p>The same contract as {@link #handleClaim}: all this does is ask, and
     * {@link ProgressService#claimReward} decides whether that row is owed. The sync is sent whether
     * or not it paid, for the reason the whole-quest handler documents — a client showing a Claim
     * button the server disagrees with is corrected by the progress it already knows how to read.
     */
    private static void handleClaimRewardEntry(ClaimRewardEntryPayload payload, ServerPlayer sender) {
        MinecraftServer server = sender.getServer();
        if (server == null || TenetQuests.index().isEmpty()) {
            return;
        }

        // The index resolves aliases, so a client holding a stale id after a rename still works.
        var entry = TenetQuests.index().quest(payload.questId());
        if (entry.isEmpty()) {
            Constants.LOG.warn("tenet: {} asked to claim reward {} of unknown quest '{}'",
                    sender.getScoreboardName(), payload.rewardIndex(), payload.questId());
            return;
        }

        ProgressService.claimReward(server, sender, entry.get(), payload.rewardIndex());
        sendToTeam(sender, ProgressSyncPayload.REASON_CHANGED);
    }

    /**
     * The player pressing Claim all in the rewards panel: the whole book's outstanding rewards in one
     * press.
     *
     * <p>{@link ProgressService#claimAll} finds the quests itself and asks the same question the single
     * claim does, so the two controls cannot disagree about what may be taken. The one thing the
     * payload carries is the panel's active filter chip, and it only ever narrows the sweep.
     */
    private static void handleClaimAll(ClaimAllPayload payload, ServerPlayer sender) {
        MinecraftServer server = sender.getServer();
        if (server == null || TenetQuests.index().isEmpty()) {
            return;
        }
        ProgressService.claimAll(server, sender, payload.filter());
        // Sent whether or not anything paid, the same correction the single claim's handler documents:
        // a panel showing rewards the server has already given out is put right by the progress the
        // client already knows how to read.
        sendToTeam(sender, ProgressSyncPayload.REASON_CHANGED);
    }

    /**
     * The player pressing Claim Chapter on the claim menu's banner: one chapter's outstanding rewards.
     *
     * <p>The same contract as {@link #handleClaimAll} one rung down. The chapter id is a scope rather
     * than a list: {@link ProgressService#claimChapter} walks its own index, keeps the quests whose
     * chapter matches, and asks the same {@code canClaimFor} the single claim asks — so an id a
     * modified client invented reaches nothing, and an id naming a real chapter reaches only what that
     * chapter actually owes this player. The filter narrows it further, to the view the banner was
     * pressed in.
     */
    private static void handleClaimChapter(ClaimChapterPayload payload, ServerPlayer sender) {
        MinecraftServer server = sender.getServer();
        if (server == null || TenetQuests.index().isEmpty()) {
            return;
        }
        ProgressService.claimChapter(server, sender, payload.chapterId(), payload.filter());
        sendToTeam(sender, ProgressSyncPayload.REASON_CHANGED);
    }

    /**
     * The player answered a choice reward.
     *
     * <p>The payload names positions, not rewards, and {@link ProgressService#claimChoice} resolves
     * all three of them against the server's own files — so the worst a modified client can do is
     * pick entry 2 instead of entry 1 of a table it was legitimately offered.
     */
    private static void handleClaimChoice(ClaimChoicePayload payload, ServerPlayer sender) {
        MinecraftServer server = sender.getServer();
        if (server == null || TenetQuests.index().isEmpty()) {
            return;
        }
        var entry = TenetQuests.index().quest(payload.questId());
        if (entry.isEmpty()) {
            Constants.LOG.warn("tenet: {} answered a choice on unknown quest '{}'",
                    sender.getScoreboardName(), payload.questId());
            return;
        }
        ClaimChoiceResultPayload.Result result = ProgressService.claimChoice(server, sender, entry.get(),
                payload.rewardIndex(), payload.entryIndex());
        if (result == ClaimChoiceResultPayload.Result.OK) {
            sendToTeam(sender, ProgressSyncPayload.REASON_CHANGED);
        }
        // The verdict goes back either way, and it is what the card is waiting for: a refusal must
        // reach the player as an answer on the card rather than as a card that closed and a decision
        // that vanished. The quest id echoed is the canonical one, so a client holding an alias still
        // matches it to the offer it is showing.
        send(sender, new ClaimChoiceResultPayload(entry.get().quest().id(), payload.rewardIndex(), result));
    }

    /**
     * How a bulk claim ended, routed to the book.
     *
     * <p>Routed rather than held, like the overflow notice: {@code QuestNotifier} owns the client-side
     * sinks, and the sentence has to reach the book's own stack while the book is open. Outside the
     * book there is nothing to do here — the action bar the server also sends is where that sentence
     * belongs.
     */
    private static void handleClaimSummary(ClaimSummaryPayload payload) {
        dev.ellipog.tenet.client.QuestNotifier.claimSummary(payload.claimed(), payload.total(),
                payload.halted());
    }

    /**
     * The server's verdict on a pick, routed to the card that asked.
     *
     * <p>The handler tolerates a card that is gone: Escape during the round trip clears the offers, and
     * a verdict for an offer nobody is showing is dropped rather than matched to whatever is on screen
     * now. See {@code QuestNotifier.choiceResult}.
     */
    private static void handleClaimChoiceResult(ClaimChoiceResultPayload payload) {
        dev.ellipog.tenet.client.QuestNotifier.choiceResult(payload.questId(), payload.rewardIndex(),
                payload.result());
    }

    /**
     * A reward-level message, routed to the book.
     *
     * <p>Routed rather than held, like the overflow and summary notices: the server already said the
     * same sentence in chat, and this is what reaches the player looking at the book, where the chat
     * cannot be read. Outside the book there is nothing to do — chat is where that sentence belongs.
     */
    private static void handleRewardToast(RewardToastPayload payload) {
        dev.ellipog.tenet.client.QuestNotifier.rewardToast(payload.message());
    }

    // ------------------------------------------------------------------
    // Sending
    // ------------------------------------------------------------------

    /** Pushes the tree and this player's progress. Called on join. */
    public static void sendEverythingTo(ServerPlayer player) {
        MinecraftServer server = player.getServer();
        if (server == null) {
            return;
        }
        QuestSync.sendEverythingTo(player, server);
        // And their party's roster, which is the third thing a client has no way to know and needs
        // before it can draw a panel. Sent through the player object rather than looked up by id --
        // see `sendOwnRosterTo` for the whole of that fault.
        sendOwnRosterTo(player);
        // And the lists this server has that the editor searches: the dimensions, which are level data
        // rather than a registry entry, and the structures, which are a datapack registry the client is
        // never sent. See ServerListsPayload for the rule the two are together for.
        ArmatureNetwork.sendToPlayer(player, serverListsFor(server));
        // And their stages, which are theirs alone rather than the team's -- see ProgressStore.
        sendStagesTo(player);
    }

    /**
     * The lists a joining client is sent, gathered from this server.
     *
     * <p>Public so the test that checks them can ask for the very object the wire carries rather than a
     * copy of the gathering: a list built one way here and another way in the test is a test of the
     * test. See {@code ServerListsTest}.
     */
    public static ServerListsPayload serverListsFor(MinecraftServer server) {
        return new ServerListsPayload(dimensionIds(server), structureIds(server.registryAccess()));
    }

    /** Every dimension this server has, by id: vanilla, modded and datapack alike. */
    private static List<String> dimensionIds(MinecraftServer server) {
        return server.levelKeys().stream()
                .map(key -> key.location().toString())
                .sorted()
                .toList();
    }

    /**
     * Every structure this server has, and every structure tag, in the spelling a file uses.
     *
     * <h2>Why this cannot be asked on the client</h2>
     *
     * <p>The structure registry is a datapack one, and 1.21.1 sends a client eleven registries -- the
     * biomes, enchantments, trim materials and the rest -- and not this. So a client's own
     * {@code registryAccess()} answers an <i>empty</i> structure registry rather than throwing, which is
     * how the structure picker came to open with no rows at all. The tags are missing for the same
     * reason: they are serialized from the registries that are sent, so a structure tag never reaches a
     * client either.
     *
     * <p>Tags are included because the field takes one: {@code structure} is a {@code RegistryRef}, so
     * {@code #minecraft:village} is a legitimate value and not a decoration of one.
     *
     * <p>The registry is asked for optionally rather than with {@code registryOrThrow}, which is the one
     * place this differs from {@code StructureTask}: a server always has this registry, so the empty arm
     * is belt and braces -- but this runs on the join path, and a field that could never be listed is
     * worth less than the player it would otherwise drop.
     */
    public static List<String> structureIds(RegistryAccess access) {
        Registry<Structure> registry = access.registry(Registries.STRUCTURE).orElse(null);
        if (registry == null) {
            return List.of();
        }
        List<String> out = new ArrayList<>();
        registry.keySet().forEach(id -> out.add(id.toString()));
        registry.getTagNames().forEach(tag -> out.add("#" + tag.location()));
        out.sort(java.util.Comparator.naturalOrder());
        return List.copyOf(out);
    }

    /** Pushes the tree to every connected player, then their progress in full. Called after a reload. */
    public static void sendTreeToAll(MinecraftServer server) {
        sendTreeToAll(server, TreeRefresh.Touch.Progress.FULL);
    }

    /**
     * Tells every connected player <b>who may edit</b> that the server's undo history is gone.
     *
     * <p>Sent by {@code /tenet reload}, which drops every open editor: the history was recorded against a
     * model of the files that the reload has just replaced, so it is discarded rather than kept. The client's
     * undo button is drawn from counters of its own, and without this it went on offering an undo over a
     * history that no longer existed — a Ctrl+Z that sent an op, got nothing back, and said nothing.
     *
     * <h2>Why the editors and not everyone</h2>
     *
     * <p>It went to everyone, on the argument that the history that was dropped is every player's. It is not:
     * the history is the <i>editors'</i>, one stack per chapter in {@code ServerEditors}, and a player who may
     * not edit has no undo button to correct. What they got was a sentence about a stack they cannot reach.
     * The same rule as {@link #sendProblemsToAll}: the fact is the server's, and the acting is an author's.
     */
    public static void sendEditHistoryDiscardedToAll(MinecraftServer server) {
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            if (QuestAuthority.mayEdit(player)) {
                ArmatureNetwork.sendToPlayer(player, new EditHistoryPayload(true));
            }
        }
    }

    /**
     * Tells every connected player <b>who may edit</b> what the reload found wrong with the pack.
     *
     * <p>Called after the tree goes out, and only when there is something to say. The faults it carries are the
     * ones a single file cannot show — a {@code dependsOn} naming a quest that is not there, a cycle between
     * files, an id declared in two chapters — and before this they were written silently with only the server
     * log knowing, so the author's quest was quietly missing from the tree or unreachable.
     *
     * <h2>Why this is no longer "to everyone"</h2>
     *
     * <p>It was, and the note here argued that the faults belong to the pack and an author who is not the one
     * who caused one still has to work with the tree they describe. The first half is true and the second is
     * not: <b>working with the tree is the thing a player who may not edit cannot do.</b> What arrived on
     * their screen was a file path, a line, a column and a JSON path, with no control anywhere in the book
     * that could act on any of it — and the payload's own doc already claimed it went "to the authors who are
     * editing", so the intent and the code disagreed and the code won.
     */
    public static void sendProblemsToAll(MinecraftServer server,
                                         dev.ellipog.armature.api.data.Problems problems) {
        EditProblemsPayload payload = EditProblemsPayload.of(problems);
        if (payload == null) {
            return;
        }
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            if (QuestAuthority.mayEdit(player)) {
                ArmatureNetwork.sendToPlayer(player, payload);
            }
        }
    }

    /**
     * The same report, to <b>one</b> player — what a join needs.
     *
     * <h2>Why the join does not use the broadcast</h2>
     *
     * <p>Because the report is produced by a reload and this is not one. A player arriving has to be told
     * what the <i>last</i> load found, and re-broadcasting it would repeat the whole report to everyone
     * already online, once per join, for a pack none of them changed. The faults are the pack's and are the
     * same for everyone; the <i>telling</i> is per player, because arriving is — and only an arriving
     * <b>author</b> is told, for the reason {@link #sendProblemsToAll} now carries.
     */
    public static void sendProblemsTo(ServerPlayer player, dev.ellipog.armature.api.data.Problems problems) {
        if (!QuestAuthority.mayEdit(player)) {
            return;
        }
        EditProblemsPayload payload = EditProblemsPayload.of(problems);
        if (payload != null) {
            ArmatureNetwork.sendToPlayer(player, payload);
        }
    }

    /**
     * The same, with the progress channel the caller owes.
     *
     * <h2>What the three answers mean, and why this is not a boolean any more</h2>
     *
     * <p>The tree always goes out — every client draws it. What differs is progress, and the three
     * cases are genuinely different things rather than degrees of one:
     *
     * <ul>
     *   <li>{@code NONE}: no player's resolved state can have moved. A table edit, and a node nudge.</li>
     *   <li>{@code DELTA}: states may have moved, but no row's <i>position</i> has, so the delta is
     *       keyed by quest id and recomputes every state before sending what differs — the same message
     *       a quest completing sends.</li>
     *   <li>{@code FULL}: a row's position may have moved under a stored count, so every player is
     *       re-sent the whole of it. See {@code TreeRefresh.Touch} for the rule.</li>
     * </ul>
     *
     * <p>The reasons travel with the messages rather than being invented here: {@code REASON_CHANGED}
     * is what the progress channel already treats as "sent relative to what this player has", and
     * {@code REASON_RELOAD} is what it already treats as "the whole of it".
     */
    public static void sendTreeToAll(MinecraftServer server, TreeRefresh.Touch.Progress progress) {
        QuestIndex index = TenetQuests.index();
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            QuestSync.sendTreeTo(player, index);
            // And their language, on the same broadcast. A reload is how an author iterates on a lang
            // file, so the tree arriving without the text it names would leave the one person who just
            // edited a translation looking at the old one. Cheap by construction: the message is packed
            // once per language however many players are on it -- see `QuestSync.localeChunks`.
            QuestSync.sendLocaleTo(player);
            switch (progress) {
                case NONE -> {
                    // Nothing about a player has moved. The tree above is the whole of the message.
                }
                case DELTA -> QuestSync.sendProgress(server, player,
                        ProgressSyncPayload.REASON_CHANGED);
                case FULL -> QuestSync.sendProgress(server, player,
                        ProgressSyncPayload.REASON_RELOAD);
            }
        }
    }

    /**
     * Reloads the loaded tree and broadcasts it: the coalesced half of an edit's server work.
     *
     * <p>Called by the tick flush rather than per operation — see {@link TreeRefresh} — so a burst of
     * edits pays for one reload and one broadcast. The op itself is still applied synchronously by
     * {@link #handleEditorOp}, and its reply is still immediate.
     *
     * <p>What it re-reads and what it re-sends are both the touch's answer, and handing the touch on
     * rather than deciding here is deliberate: the caller that decided this for itself is the reason a
     * coordinate nudge used to cost every player a full progress sync.
     */
    public static void refreshTree(MinecraftServer server, TreeRefresh.Touch touch,
                                   java.util.Set<String> chapters) {
        // The flush's own timing, around the two halves `QuestLoader`'s phase line cannot see: the reload
        // as a whole (its split has its own line) and the broadcast. Gated before the first clock read, for
        // the reason `handleEditorOp` gives.
        boolean timing = dev.ellipog.tenet.editor.EditPhases.on();
        long reloadStarted = timing ? System.nanoTime() : 0L;
        // Kept rather than discarded: its problems are the cross-file faults nothing else can see, and they
        // are only knowable here -- the op was applied and answered synchronously, before this reload ran.
        dev.ellipog.tenet.quest.QuestLoader.Result reloaded;
        dev.ellipog.armature.api.data.Problems before = TenetQuests.problems();
        boolean tables = TreeRefresh.drainTables();
        if (touch.scope() == TreeRefresh.Touch.Scope.TABLES) {
            TenetQuests.reloadTables(server);
            reloaded = null;
        }
        else if (touch == TreeRefresh.Touch.COSMETIC && chapters != null && !chapters.isEmpty()
                && TenetQuests.refreshChapters(chapters, serverOps(server))) {
            // The cheap path: the chapters patched in place, so there is nothing new to say about
            // problems (a cosmetic op cannot have made any) and nothing to re-resolve. A table edit
            // that shared the tick is re-read first — cheap beside the chapters, and dropping it
            // would leave its file unwritten to the tree until the next full reload. The tree
            // broadcast below is the whole of what anyone is owed.
            if (tables) {
                TenetQuests.reloadTables(server);
            }
            long reloadNanos = timing ? System.nanoTime() - reloadStarted : 0L;
            sendTreeToAll(server, touch.progress());
            if (timing) {
                QuestSync.WireTiming wire = QuestSync.drainWireTiming();
                dev.ellipog.tenet.editor.EditPhases.flushed(reloadNanos, wire.encodeNanos(),
                        wire.deflateNanos(), server.getPlayerList().getPlayers().size());
            }
            return;
        }
        else {
            reloaded = TenetQuests.reload(server);
        }
        long reloadNanos = timing ? System.nanoTime() - reloadStarted : 0L;
        int players = server.getPlayerList().getPlayers().size();
        sendTreeToAll(server, touch.progress());
        // After the tree, so a client that reads both in one frame draws the pack before being told what is
        // wrong with it -- and only when there is something NEW to say. Re-sending an unchanged report
        // re-toasts every fault on every edit, which is how a drag learned to announce sixteen warnings.
        if (reloaded != null && ProblemsSync.changed(before, reloaded.problems())) {
            sendProblemsToAll(server, reloaded.problems());
        }
        if (timing) {
            QuestSync.WireTiming wire = QuestSync.drainWireTiming();
            dev.ellipog.tenet.editor.EditPhases.flushed(reloadNanos, wire.encodeNanos(),
                    wire.deflateNanos(), players);
        }
    }

    /**
     * The same, for an edit that only touched the reward tables.
     *
     * <p>The tables are re-read; the quest index and the settings are kept, and progress is left alone.
     * See {@code TreeRefresh.Touch} for why the two refreshes exist.
     */
    public static void refreshTables(MinecraftServer server) {
        refreshTree(server, TreeRefresh.Touch.TABLES, java.util.Set.of());
    }

    /**
     * Pushes progress to every online member of the teams named in {@code owners}.
     *
     * <h2>Why this is the method that was missing</h2>
     *
     * <p>The automatic half of the engine had no way to reach a client. The only code anywhere that
     * pushed progress to a player was the handler for <i>pressing Submit</i>, so submitting a task
     * updated the screen and <b>nothing else did</b>. Gathering eight oak logs completed the quest on the server,
     * granted its reward and printed the completion message, while the book went on showing
     * {@code 0 / 8} for the rest of the session.
     *
     * <p>That is what a player reports as "it doesn't register the logs in my inventory", and it is the
     * most misleading possible form for this bug to take: the counting was correct the whole time. The
     * natural experiments all fail to help — throwing the items on the ground and picking them up
     * again, reconnecting, gathering more — because none of them is a code path that pushes progress.
     * The one gesture that would have worked was pressing Submit on a task that has no button.
     *
     * <p>Keyed by <b>owner</b> rather than by player, because progress belongs to a team: one changed
     * owner can mean two or more players who each need to hear about it.
     */
    public static void sendProgressToOwners(MinecraftServer server, Collection<UUID> owners, int reason) {
        if (owners.isEmpty()) {
            return;
        }
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            if (owners.contains(ProgressService.progressOwner(server, player))) {
                QuestSync.sendProgress(server, player, reason);
            }
        }
    }

    /**
     * Pushes progress to one player, by id, if they are still connected.
     *
     * <h2>Why this exists rather than a second team lookup at the call site</h2>
     *
     * <p>Two callers need it and neither has a {@link ServerPlayer} to hand: a team event carries a
     * player's <b>UUID</b> and nothing else, and the player it names is routinely offline — the whole
     * point of the event is that the membership changed, which is not the same as the player being
     * present. So the offline case is the common one rather than an edge case, and it is handled here
     * once instead of at every caller.
     *
     * <p>Doing nothing for an absent player is correct rather than merely safe: a snapshot describes
     * what <i>this connection</i> was last sent, and a player who is not connected has been sent
     * nothing. {@code sendProgress} would push a row into that map for somebody who will never read
     * it, and {@code sendEverythingTo} drops the row on their next join anyway. So this is not just
     * avoiding a null — it is declining to record knowledge nobody has.
     *
     * <p>An absent player is also not a reason for the <i>sender</i> to hear about it: the events that
     * call this fire for changes a listener should react to, not for changes it should report.
     */
    public static void sendProgressToPlayer(MinecraftServer server, UUID playerId, int reason) {
        ServerPlayer player = server.getPlayerList().getPlayer(playerId);
        if (player == null) {
            return;
        }
        QuestSync.sendProgress(server, player, reason);
    }

    /**
     * Pushes progress to the sender's whole team, including the sender.
     *
     * <p>The team's progress is shared, so a change caused by one member is news to all of them. The
     * sender is included deliberately: the client made no local change when it asked to submit, so
     * this sync is how it learns the outcome — including when the answer was no.
     */
    public static void sendToTeam(ServerPlayer sender, int reason) {
        // int rather than byte, matching the payload. A byte here and an int there meant three
        // silent narrowing conversions the compiler refused -- which is the good outcome, but the
        // reason field is a code, not a value with a width, so int is the honest type for it.
        MinecraftServer server = sender.getServer();
        if (server == null) {
            return;
        }
        sendProgressToTeam(server, sender, reason);
    }

    /**
     * The same, for a caller that already holds the server.
     *
     * <h2>Why the server is a parameter here</h2>
     *
     * <p>Because taking it from the player is a second question with a second answer. A
     * {@link ServerPlayer} carries its server in production and does not necessarily carry one in a
     * test — the playthrough harness builds players that answer {@code null} — so a command that
     * derived the server from its own source would silently push nothing there, and the push would
     * look tested when it was not. A command source always knows its server, so it passes it.
     *
     * <p>That is not a test convenience dressed up as design: it is the honest direction of the
     * dependency. The sender is what decides <i>whose</i> progress moved; the server is what the
     * message is sent through, and the caller that has one should not have to ask a player for it.
     */
    public static void sendProgressToTeam(MinecraftServer server, ServerPlayer sender, int reason) {
        QuestSync.sendProgressToTeam(server, membersOf(server, sender), reason);
    }

    /**
     * Pushes progress to everyone who has to hear that a team changed.
     *
     * <h2>Why the team's members <i>and</i> one named player</h2>
     *
     * <p>A membership change moves progress for two sets of people, and they are not nested. The team
     * keeps what it recorded, so every member still in it has a view that may have changed. And the
     * player who left is keyed by their own id again — Armature's {@code MEMBER_LEFT} fires with the
     * team as it now is, which does not include them, so a caller that only walked the team would
     * leave exactly the person whose progress actually moved as the one who was never told.
     *
     * <p>That is why this takes the pair rather than a single team: the recipient set genuinely is
     * "the team, plus this player", and a caller that had to work that out itself would be the second
     * place that knows it.
     *
     * <h2>Why the reason travels</h2>
     *
     * <p>{@code REASON_TEAM_CHANGED} rather than {@code REASON_CHANGED}, and it is not decoration. It
     * is what makes {@code QuestSync} send a <b>full</b> sync rather than a delta — a client that has
     * changed teams is holding the previous team's progress, and a delta would merge one party's
     * questline onto another's. See {@code sendProgress} for that branch.
     *
     * @param team     the team as the event reports it — after a join, and before a disband
     * @param alsoThis a player who is no longer in the team and still needs to hear, or null
     */
    public static void sendTeamChange(MinecraftServer server, Team team, UUID alsoThis) {
        for (UUID member : team.memberIds()) {
            sendProgressToPlayer(server, member, ProgressSyncPayload.REASON_TEAM_CHANGED);
        }
        if (alsoThis != null) {
            sendProgressToPlayer(server, alsoThis, ProgressSyncPayload.REASON_TEAM_CHANGED);
        }
    }

    /**
     * A party's roster, from the server.
     *
     * <h2>Unpacked here rather than trusted as fields</h2>
     *
     * <p>Because the roster arrives as one string — see {@link PartySyncPayload} — and there is exactly
     * one place that knows how to read it. {@code PartySnapshot.unpack} never throws and skips what it
     * cannot read, so a malformed message draws "no party" rather than disconnecting the client: a
     * payload that was only ever going to fill a side panel is not worth a lost connection.
     */
    /**
     * A choice offer arrived: hold it for the picker.
     *
     * <p>Held rather than acted on, because the picker is a screen and this runs wherever the network
     * thread reached; the screen reads {@code ClientChoiceOffers} when it opens.
     */
    private static void handleChoiceOffer(ChoiceRewardPayload payload) {
        dev.ellipog.tenet.client.ClientChoiceOffers.accept(payload.questId(), payload.rewardIndex(),
                payload.entries());
    }

    /**
     * A grant overflowed and dropped stacks at the player's feet.
     *
     * <p>Routed rather than held: {@code QuestNotifier} owns the client-side notice sinks, and the
     * sentence has to reach the book's own stack while the book is open — the action bar the server
     * also sends is part of the HUD, which is not drawn behind a screen. Outside the book there is
     * nothing to do here, because the HUD is exactly where that sentence belongs.
     */
    private static void handleRewardOverflow(RewardOverflowPayload payload) {
        dev.ellipog.tenet.client.QuestNotifier.rewardOverflow(payload.stacks());
    }

    /**
     * The server's lists arrived: hold them for the editor's search.
     *
     * <p>Held rather than acted on, for the same reason the choice offer is: this runs wherever the network
     * thread reached, and the screen reads the lists when it draws a field that needs one.
     */
    private static void handleServerLists(ServerListsPayload payload) {
        dev.ellipog.tenet.client.ClientServerLists.accept(payload.dimensions(), payload.structures());
    }

    /**
     * A player's stages arrived: hold them for a script or a screen to read.
     *
     * <p>Held rather than acted on, like every other client payload here: this runs wherever the network
     * thread reached, and the reader asks when it needs to know.
     */
    private static void handleStages(StageSyncPayload payload) {
        dev.ellipog.tenet.client.ClientStages.accept(payload.stages());
    }

    /**
     * Pushes one player's stages to their own client. Called on join, and after every change.
     *
     * <p>What the player sees is their own stages and their team's together: a stage task or a quest
     * gate that reads the team's set must agree with what a client script asks about, and the store
     * keeps them apart so that leaving a party takes the team's half away. See
     * {@code StageService.effective}.
     */
    public static void sendStagesTo(ServerPlayer player) {
        MinecraftServer server = player.getServer();
        if (server == null) {
            return;
        }
        List<String> ids = dev.ellipog.tenet.progress.StageService.effective(server, player.getUUID()).stream()
                .map(net.minecraft.resources.ResourceLocation::toString)
                .sorted()
                .toList();
        ArmatureNetwork.sendToPlayer(player, new StageSyncPayload(ids));
    }

    /** Asks one player's client to open the quest book, on one quest when one is named. */
    public static void sendOpenBookTo(ServerPlayer player, String questId) {
        ArmatureNetwork.sendToPlayer(player, new OpenBookPayload(questId == null ? "" : questId));
    }

    /** Asks one player's client to preview the book as a player sees it. See {@link PreviewPayload}. */
    public static void sendPreviewTo(ServerPlayer player, boolean toggle, boolean on) {
        ArmatureNetwork.sendToPlayer(player, new PreviewPayload(toggle, on));
    }

    private static void handlePartySync(PartySyncPayload payload) {
        ClientPartyCache.accept(payload.packed());
        // Info rather than debug, paired with the sender's line: "the server sent it" and "the client
        // took it" are two claims, and a panel that shows the wrong party is almost always one of them
        // being false while the other looks fine.
        Constants.LOG.info("tenet: roster received ({} member(s))",
                ClientPartyCache.memberCount());
    }

    /**
     * A chapter's files, asked for by an editor's client.
     *
     * <p>The same permission the ops need, because a chapter's trees are the author's working material and not
     * a player's business — and because a client that may not edit has no panel that would show them. A refusal
     * is an {@link EditorReplyPayload} rather than silence: the author gets a sentence in the same place an op's
     * refusal appears.
     */
    private static void handleReplicaRequest(ReplicaRequestPayload payload, ServerPlayer sender) {
        if (!QuestAuthority.mayEdit(sender)) {
            reply(sender, payload.chapter(), false, "",
                    "You may not read the quest files (permission level " + QuestAuthority.EDIT_LEVEL + ")");
            return;
        }
        JsonObject quests = TenetQuests.editors().replica(payload.chapter());
        if (quests == null) {
            reply(sender, payload.chapter(), false, "",
                    "no chapter called \"" + payload.chapter() + "\"");
            return;
        }
        com.google.gson.JsonObject chapterTree = TenetQuests.editors().chapterTree(payload.chapter());
        ArmatureNetwork.sendToPlayer(sender, new ChapterReplicaPayload(payload.chapter(), quests.toString(),
                chapterTree == null ? "{}" : chapterTree.toString()));
    }

    /**
     * A chapter's files, arriving. Kept where the panel will read them.
     *
     * <p>Stamped with the tree revision of the moment it arrived, which is the version {@link ClientChapterReplica}
     * compares against: a copy and a tree that disagree are a copy to ask for again.
     */
    private static void handleChapterReplica(ChapterReplicaPayload payload) {
        // One marker goes with it: the fetch is answered here when it succeeds and by an edit reply when
        // it is refused, and both doors have to move the same needle -- see `ClientEditReplies.noteSent`.
        ClientEditReplies.takeSent();
        ClientChapterReplica.accept(payload.chapter(), payload.quests(), payload.chapterTree(),
                ClientQuestCache.treeRevision());
        Constants.LOG.info("tenet: chapter \"{}\" replica received ({} bytes)",
                payload.chapter(), payload.quests().length());
    }

    /**
     * One edit, applied. The whole of the server's side of the editor.
     *
     * <h2>The four things that happen, in order, and why in that order</h2>
     *
     * <p><b>Permission first</b>, through the same {@link QuestAuthority} the commands read, so a client that
     * asked anyway is refused before anything is opened or read. Then the op is <b>applied and validated</b>
     * by the model — see {@code EditorOps.apply}: a save that refused wrote nothing and undid itself, so a
     * refusal here has changed no file. Then, if it landed, the <b>index is reloaded</b> from the files the op
     * just wrote, which is what the tree broadcast sends to everyone: the author's own client included, which
     * is how the canvas stops drawing its remembered value. Finally the <b>reply</b>, which the client needs
     * for two things it cannot work out for itself: the reason for a refusal, and the id of a quest that was
     * created or duplicated.
     *
     * <p>The reply goes to the sender alone, unlike the tree: a refusal is between the author and their own
     * edit, and broadcasting one author's validation error to the whole server would be telling other players
     * about a file they are not editing.
     */
    /**
     * The ops an edit validates with: the server's own registry ops, which is what a load judges
     * with. Saving with blind ops refuses files the loader accepts — registry-backed components
     * decode against registries — so the two must agree, or one enchanted reward vetoes every
     * edit in its chapter. See {@code TenetQuests.reload(MinecraftServer)} for the load half.
     */
    private static com.mojang.serialization.DynamicOps<com.google.gson.JsonElement> serverOps(
            ServerPlayer sender) {
        return serverOps(sender.getServer());
    }

    /** The same, where only the server is at hand: the refresh flush owns no player. */
    private static com.mojang.serialization.DynamicOps<com.google.gson.JsonElement> serverOps(
            net.minecraft.server.MinecraftServer server) {
        return server.registryAccess()
                .createSerializationContext(com.mojang.serialization.JsonOps.INSTANCE);
    }

    private static void handleEditorOp(EditorOpPayload payload, ServerPlayer sender) {
        if (!QuestAuthority.mayEdit(sender)) {
            // Refused, not ignored, and with the level named: an author whose permissions are short needs to
            // know that is the reason rather than watching an edit do nothing.
            reply(sender, payload.chapter(), false, "",
                    "You may not edit the questline (permission level " + QuestAuthority.EDIT_LEVEL + ")",
                    payload.requestId());
            return;
        }

        JsonObject json = parse(payload.op());
        // One payload carries both families of edit, and the table family is asked first: a table op is
        // applied by a table draft over a file rather than by a chapter's editor, and the two would
        // otherwise have to agree about what a "chapter" field means for a table.
        dev.ellipog.tenet.editor.TableOp tableOp = dev.ellipog.tenet.editor.TableOps.read(json);
        if (tableOp != null) {
            EditorOps.Applied applied = TenetQuests.tables().apply(tableOp, serverOps(sender));
            if (applied.ok()) {
                // Only the tables can have moved, so only the tables are re-read and progress is left
                // alone -- see `TreeRefresh.Touch`.
                TreeRefresh.requestTables();
            }
            reply(sender, payload.chapter(), applied.ok(),
                    applied.questId() == null ? "" : applied.questId(),
                    String.join("\n", applied.messages()), payload.requestId());
            return;
        }

        EditorOp op = EditorOps.read(json);
        if (op == null) {
            reply(sender, payload.chapter(), false, "", "that is not an edit this version knows",
                    payload.requestId());
            return;
        }

        // Timed around the apply, which is the model change *and* the save -- see `EditPhases` for why the
        // two are one number from here. The gate is checked **before** the clock is read, so a server with
        // the counter off pays one static-boolean branch and no `nanoTime` call at all; that is the contract
        // every instrument in this codebase keeps, and it is what lets one sit on an apply path.
        boolean timing = dev.ellipog.tenet.editor.EditPhases.on();
        long applyStarted = timing ? System.nanoTime() : 0L;
        EditorOps.Applied applied = TenetQuests.editors().apply(payload.chapter(), op, serverOps(sender));
        if (timing) {
            dev.ellipog.tenet.editor.EditPhases.applied(System.nanoTime() - applyStarted);
        }
        if (applied.ok()) {
            // The reload and the all-player tree broadcast are coalesced to one per server tick -- see
            // TreeRefresh -- so a burst of edits pays for them once. The reply below stays per op, so
            // the author hears about every operation immediately.
            //
            // Armed with what *this op* can have moved rather than with "something happened", which is
            // the whole point of the reach: a drag sends one op per tick, and the old flag made every
            // one of them owe a full progress sync to every connected player. The chapter travels
            // beside it when the op is chapter-scoped, so a cosmetic flush re-reads that chapter
            // instead of the whole tree.
            TreeRefresh.request(EditorOps.reachOf(op),
                    EditorOps.refreshChapter(op, payload.chapter()).orElse(null));
        }
        reply(sender, payload.chapter(), applied.ok(),
                applied.questId() == null ? "" : applied.questId(),
                String.join("\n", applied.messages()), payload.requestId());
    }

    /**
     * A table's file, for an editor that asked.
     *
     * <p>Refused rather than answered with nothing when the id is unknown: the client remembers the
     * refusal and draws it, which is a sentence an author can act on -- an empty file would look like a
     * table that is empty.
     */
    private static void handleTableReplicaRequest(TableReplicaRequestPayload payload, ServerPlayer sender) {
        if (!QuestAuthority.mayEdit(sender)) {
            reply(sender, "", false, "",
                    "You may not read the quest files (permission level " + QuestAuthority.EDIT_LEVEL + ")");
            return;
        }
        // Read from the file rather than gating on the loaded map. A table the author just made has a
        // file and is not in that map until the next reload -- which the op itself schedules -- so the
        // gate refused the editor's first request, and the retry then waited out the replica throttle:
        // a brand-new table showed "Waiting for this table's file..." and a red refusal for its file
        // that was right there. The replica is a file read; the map is only for the message.
        String json = TenetQuests.tables().replica(payload.table());
        if (json.isBlank()) {
            reply(sender, "", false, "", TenetQuests.tables().exists(payload.table())
                    ? "reward_tables/" + payload.table() + ".json could not be read"
                    : "no reward table named \"" + payload.table() + "\"");
            return;
        }
        ArmatureNetwork.sendToPlayer(sender, new TableReplicaPayload(payload.table(), json,
                TenetQuests.tables().referrers(payload.table())));
    }

    private static void handleTableReplica(TableReplicaPayload payload) {
        // The fetch's own answer, so its marker is consumed here rather than left to mis-align the next
        // op's reply -- a refusal comes back as an edit reply and pops it there instead.
        ClientEditReplies.takeSent();
        dev.ellipog.tenet.client.ClientTableReplica.accept(payload.table(), payload.json(),
                dev.ellipog.tenet.client.ClientQuestCache.treeRevision(), payload.usedBy());
    }

    /**
     * Rolls a table and answers with what came up.
     *
     * <p>Rolled on the server because a table's entries can point at other tables: the client holds the
     * one file it is editing and a summary of the rest, which is enough to draw a table and not enough
     * to roll one. The count is clamped by {@code TableRoller}, so a forged one cannot spin the thread.
     *
     * <p>The reading is the client's, taken as it stands. It used to be inferred here from an
     * {@code includeEmpty} flag, which meant {@code all_table} and {@code choice} were reported as
     * weighted rolls of a table whose own panel said otherwise — see {@link TableRollRequestPayload}.
     */
    private static void handleTableRollRequest(TableRollRequestPayload payload, ServerPlayer sender) {
        if (!QuestAuthority.mayEdit(sender)) {
            reply(sender, "", false, "",
                    "You may not read the quest files (permission level " + QuestAuthority.EDIT_LEVEL + ")");
            return;
        }
        java.util.Optional<dev.ellipog.tenet.quest.loot.RewardTable> table =
                TenetQuests.tables().resolveTable(payload.address());
        if (table.isEmpty()) {
            reply(sender, "", false, "", "that table could not be read");
            return;
        }
        dev.ellipog.tenet.quest.loot.TableRoller.Report report =
                dev.ellipog.tenet.quest.loot.TableRoller.roll(table.get(), payload.mode(),
                        payload.rolls(), sender.getRandom(),
                        id -> TenetQuests.rewardTables().get(id));
        ArmatureNetwork.sendToPlayer(sender, TableRollPayload.of(report, payload.address().describe()));
    }

    private static void handleTableRoll(TableRollPayload payload) {
        // Same door as the replica: a roll is answered by this payload when it works and by an edit
        // reply when the table cannot be rolled, so one marker is consumed either way.
        ClientEditReplies.takeSent();
        dev.ellipog.tenet.client.ClientTableRoll.accept(payload.subject(), payload);
    }

    /** A command asked for the quest book: open it, on the named quest when one was named. */
    private static void handleOpenBook(OpenBookPayload payload) {
        // Refused the same way as every other client-side open when the pack disabled its book:
        // the server already refused the command itself, but a payload forged or delayed past a
        // reload must not open what the file says is closed. `openOn` asks again for the quest
        // branch, so this gate covers the plain branch.
        if (!dev.ellipog.tenet.client.QuestBookScreen.checkOpenAllowed()) {
            return;
        }
        if (payload.questId() == null || payload.questId().isEmpty()) {
            dev.ellipog.armature.api.client.ArmatureClient.openScreen(dev.ellipog.tenet.Tenet.QUEST_BOOK_SCREEN);
        }
        else {
            dev.ellipog.tenet.client.QuestBookScreen.openOn(payload.questId());
        }
    }

    /** A command asked for the player's view: park the preview ask and open the book on it. */
    private static void handlePreview(PreviewPayload payload) {
        if (!dev.ellipog.tenet.client.QuestBookScreen.checkOpenAllowed()) {
            return;
        }
        dev.ellipog.tenet.client.QuestBookScreen.requestPreview(payload.toggle(), payload.on());
        dev.ellipog.armature.api.client.ArmatureClient.openScreen(dev.ellipog.tenet.Tenet.QUEST_BOOK_SCREEN);
    }

    /** A command asked for a table's editor: park the request and make sure the book is on screen. */
    private static void handleTableOpen(TableOpenPayload payload) {
        dev.ellipog.tenet.client.ClientTableOpen.request(payload.table());
        dev.ellipog.armature.api.client.ArmatureClient.openScreen(dev.ellipog.tenet.Tenet.QUEST_BOOK_SCREEN);
    }

    /**
     * Fills a table from an inventory, on the server.
     *
     * <p>The items never travel -- see {@code TableImportRequestPayload} -- and the entries are inserted
     * as one edit, so a whole chest is one Ctrl+Z. What the author hears back is the count, and the
     * names of anything that had to be clipped.
     */
    private static void handleTableImport(TableImportRequestPayload payload, ServerPlayer sender) {
        if (!QuestAuthority.mayEdit(sender)) {
            reply(sender, "", false, "",
                    "You may not edit the questline (permission level " + QuestAuthority.EDIT_LEVEL + ")");
            return;
        }
        dev.ellipog.tenet.quest.loot.TableImport.Imported imported;
        if (payload.container()) {
            java.util.Optional<net.minecraft.world.Container> container =
                    dev.ellipog.tenet.quest.loot.TableImport.containerLookingAt(sender);
            if (container.isEmpty()) {
                reply(sender, "", false, "", dev.ellipog.tenet.quest.loot.TableImport
                        .lookingAtBlock(sender)
                        ? "Targeted block is not a container"
                        : "Look at a container to import");
                return;
            }
            imported = dev.ellipog.tenet.quest.loot.TableImport.fromContainer(container.get());
        }
        else {
            imported = dev.ellipog.tenet.quest.loot.TableImport.fromPlayer(sender);
        }
        EditorOps.Applied applied =
                TenetQuests.tables().importInto(payload.address(), imported.entries(), serverOps(sender));
        if (applied.ok()) {
            TreeRefresh.requestTables();
        }
        reply(sender, "", applied.ok(), "",
                applied.ok()
                        ? dev.ellipog.tenet.quest.loot.TableImport.describe(imported,
                                payload.address().describe())
                        : String.join("\n", applied.messages()));
    }

    /** An op's JSON, or null: a payload from a newer client is a refusal rather than a parse crash. */
    private static JsonObject parse(String json) {
        try {
            JsonElement parsed = JsonParser.parseString(json);
            return parsed.isJsonObject() ? parsed.getAsJsonObject() : null;
        }
        catch (RuntimeException malformed) {
            return null;
        }
    }

    /**
     * The server's answer to one request, carrying that request's id back.
     *
     * <p>The id is a parameter rather than a field read here because a handler answers more than one request
     * in a single pass — a bulk op replies once per quest it touched — and every one of those answers belongs
     * to the same request. Threading it makes each handler name the request it is answering, which is exactly
     * what the client matches on.
     */
    private static void reply(ServerPlayer sender, String chapter, boolean ok, String questId,
                              String messages, long requestId) {
        ArmatureNetwork.sendToPlayer(sender,
                new EditorReplyPayload(chapter, ok, questId, messages, requestId));
    }

    /** An answer to a request that carried no id, matched by position as it always was. */
    private static void reply(ServerPlayer sender, String chapter, boolean ok, String questId,
                              String messages) {
        reply(sender, chapter, ok, questId, messages, EditorReplyPayload.NO_REQUEST);
    }

    /**
     * The server's answer, put where the open screen will find it.
     *
     * <p>Through a cache rather than into a screen, for the reason every other client payload here does: this
     * runs on the client's game thread with whatever is open, which may be nothing — a player who closes the
     * book while an op is in flight still gets the answer, and it is dropped rather than sent to a screen that
     * is gone.
     */
    private static void handleEditorReply(EditorReplyPayload payload) {
        ClientEditReplies.accept(payload);
        Constants.LOG.info("tenet: edit reply for \"{}\" -- {}",
                payload.chapter(), payload.ok() ? "applied" : "refused");
    }

    /**
     * Asks the server to apply one edit.
     *
     * <p>The client's whole side of the editor: it never writes a file, and it does not decide whether the
     * edit is allowed or possible. What it gets back is a {@link EditorReplyPayload} into
     * {@link ClientEditReplies}, and the changed tree as a reload broadcast — the canvas keeps drawing its
     * remembered value until that arrives, which is the one exception the design allows and it expires.
     */
    /**
     * Asks for a chapter's files, when a panel is about to need them.
     *
     * <p>One request per revision, decided by {@link ClientChapterReplica#claim}, which the caller uses as the
     * gate: this method does not ask twice on its own, because a screen asking every frame is a screen the
     * server sees as a flood.
     */
    public static void requestReplica(String chapter) {
        ArmatureNetwork.sendToServer(new ReplicaRequestPayload(chapter));
    }

    public static void sendEditorOp(String chapter, EditorOp op, long requestId) {
        ArmatureNetwork.sendToServer(
                new EditorOpPayload(chapter, EditorOps.write(op).toString(), requestId));
    }

    /** The same, with no request id: matched by position, as it was before the id existed. */
    public static void sendEditorOp(String chapter, EditorOp op) {
        sendEditorOp(chapter, op, EditorReplyPayload.NO_REQUEST);
    }

    /**
     * The same, for a table edit: one payload, two families.
     *
     * <p>The chapter argument is empty for a table op and the server ignores it — a table is a file
     * rather than a chapter's — but it is still an argument rather than a second payload, because the
     * two are the same message with different subjects and the client already has one channel for it.
     */
    public static void sendEditorOp(String chapter, dev.ellipog.tenet.editor.TableOp op, long requestId) {
        ArmatureNetwork.sendToServer(new EditorOpPayload(chapter,
                dev.ellipog.tenet.editor.TableOps.write(op).toString(), requestId));
    }

    /** The same, with no request id. */
    public static void sendEditorOp(String chapter, dev.ellipog.tenet.editor.TableOp op) {
        sendEditorOp(chapter, op, EditorReplyPayload.NO_REQUEST);
    }

    /**
     * Pushes a party's roster to everyone who can see it.
     *
     * <h2>Who is told, and why it is not only the members</h2>
     *
     * <p>The members, because their own panel is describing themselves. And <b>nobody else</b> — a
     * roster is not public information, and a server that broadcast every party's membership would be
     * telling players who is grouped with whom without being asked to.
     *
     * <h2>Called from the same places as the progress push</h2>
     *
     * <p>Which is what makes it nearly free. A membership change already sends every member a progress
     * sync — that is what {@code sendTeamChange} is for — so the roster rides along on a message that
     * was going out anyway, and an idle tick sends nothing at all.
     */
    public static void sendPartyToTeam(MinecraftServer server, UUID teamId) {
        PartySnapshot snapshot = PartySnapshot.of(server, teamId);
        if (!snapshot.isPresent()) {
            // The team is gone, or nobody is in it. An empty roster tells nobody, and that is not a
            // gap: whoever the roster *would* name is named by the caller instead -- see sendNoPartyTo
            // and its note on why a disband has to send something rather than nothing.
            return;
        }
        // Two lists travel per *recipient* rather than with the party, and that is what `withPlayers`
        // is for: two members of one party receive different snapshots, because each has their own
        // invitations. The online list is the same for both, and is filled here anyway so there is one
        // call that produces a complete snapshot rather than two that have to agree.
        //
        // This is also what makes the panel usable without typing: an invitation the client cannot see
        // is an invitation it cannot offer an Accept button for.
        for (PartySnapshot.Member member : snapshot.members()) {
            sendRosterTo(server, member.id(), snapshot
                    .withPlayers(server, member.id(), id -> invitesFor(server, id))
                    .withMode(dev.ellipog.tenet.party.PartyStore.of(server).modeOf(teamId).id()));
        }
    }

    /**
     * The invitations a player is holding, as the snapshot's own record.
     *
     * <h2>Why the adapter is here rather than in the snapshot</h2>
     *
     * <p>Because the snapshot is a plain value -- it holds ids, names and an enum, and nothing about
     * teams -- and `TeamInvite` would drag Armature's teams into a message format. So the conversion
     * from a {@code Team} to the two fields the client needs happens at the one place that already
     * deals with both, and the format stays a format.
     */
    private static java.util.List<PartySnapshot.Invite> invitesFor(MinecraftServer server, UUID player) {
        java.util.List<PartySnapshot.Invite> out = new java.util.ArrayList<>();
        for (Team team : dev.ellipog.armature.api.teams.Teams.of(server).invitesFor(player)) {
            // Who sent it and when are the two facts the invitation row shows besides the party's
            // name. A source that cannot say stores the owner and a zero time -- see TeamInvite --
            // and both truthfully reach the panel through here rather than being flattened to a
            // team id and a name, which is what this line used to do.
            var invite = team.inviteOf(player).orElse(null);
            UUID inviter = invite == null ? team.owner() : invite.inviter();
            // Stored timestamp to wire age, while the server clock is in hand: the client has no
            // server clock, so a raw game time would render as however old the world is.
            long age = invite == null || invite.at() <= 0L
                    ? 0L
                    : Math.max(0L, server.overworld().getGameTime() - invite.at());
            out.add(new PartySnapshot.Invite(team.id(), team.name(), inviter,
                    PartySnapshot.nameOf(server, inviter), age));
        }
        return out;
    }

    /** One player's roster, if they are still connected. */
    public static void sendRosterTo(MinecraftServer server, UUID playerId, PartySnapshot snapshot) {
        ServerPlayer player = server.getPlayerList().getPlayer(playerId);
        if (player == null) {
            // Debug rather than info, and it is the one silent exit left in this path: a member who has
            // disconnected between a change and the push. The *expected* null -- the joining player,
            // who is not in the list yet -- no longer comes through here at all. See `sendOwnRosterTo`.
            Constants.LOG.debug("tenet: no player {} to send a roster to", playerId);
            return;
        }
        sendRoster(player, snapshot);
    }

    /**
     * A roster, to one player, through the player object rather than through a lookup.
     *
     * <h2>Why a message's recipient must not be found by id</h2>
     *
     * <p>Because at {@code PLAYER_JOIN} the lookup has not been populated yet: the event fires while the
     * connection is still being accepted, and {@code getPlayerList().getPlayer(uuid)} answers null for
     * the player who is joining. Every other push in this class can afford that -- a party change
     * happens long after everybody is listed -- and the join push cannot, because its whole audience is
     * the one player who is not in the list yet. Which is exactly what a party did: the tree and the
     * progress arrived on a login, because they are sent through the {@code ServerPlayer} the event
     * hands over, and the roster -- the only message that looked its own recipient up -- was dropped
     * without a word, on every login, with every command still insisting the party existed.
     */
    private static void sendRoster(ServerPlayer player, PartySnapshot snapshot) {
        ROSTERS_SENT.merge(player.getUUID(), 1, Integer::sum);
        // Logged at info rather than debug, and one line per message: a party's messages are a handful
        // per session, and "was the client told" is the question every fault in this area has turned
        // on. Both halves of it are logged -- see `handlePartySync` for the receiving side.
        Constants.LOG.info("Tenet: roster -> {} ({} member(s))",
                player.getScoreboardName(), snapshot.members().size());
        send(player, new PartySyncPayload(snapshot.pack()));
    }

    /**
     * Every party's roster, to its members: for a change that is not about any one party.
     *
     * <h2>Why a player joining the server moves a panel they are not in</h2>
     *
     * <p>Because a party's panel lists the players who are <b>not</b> in it — an Invite row each — and
     * marks which members are online. Both of those are facts about the server rather than about the
     * party, so somebody logging in or out changes every open panel on it, and no team event fires to
     * say so: TEAM_CREATED, MEMBER_JOINED and MEMBER_LEFT are all about membership.
     *
     * <p>The cost is one message per member per party, on an event that happens when somebody joins or
     * leaves the server. That is worth naming rather than leaving to be discovered: on a large server it
     * is a push per party per arrival, and the alternative is a panel whose Invite list is as stale as
     * the server is busy.
     */
    public static void sendRostersToAllParties(MinecraftServer server) {
        if (server == null) {
            return;
        }
        for (Team team : dev.ellipog.armature.api.teams.Teams.of(server).teams()) {
            // Skips itself for a team that is gone or has nobody in it, which is what makes this safe
            // to call for every team the source knows about.
            sendPartyToTeam(server, team.id());
        }
    }

    /**
     * The roster of the player who is logging in, sent to them.
     *
     * <h2>Why this exists beside {@code sendEverythingTo}'s other half</h2>
     *
     * <p>Because the login is the one moment a player is not in the player list, so it is the one push
     * that has to carry its own recipient. See {@link #sendRoster} for the fault that made that
     * concrete: a party that no login could see, while every command and every event knew about it.
     *
     * <p>The caller is the join hook, so the player is by definition not in a team of one: this asks
     * the team source, and a player with no party is <b>told</b> they have none rather than left in
     * silence. That matters across a relog in a single client -- one client can open two worlds, and a
     * cache that is never told otherwise describes the previous world's party.
     */
    public static void sendOwnRosterTo(ServerPlayer player) {
        MinecraftServer server = player.getServer();
        if (server == null) {
            return;
        }
        Optional<Team> mine = dev.ellipog.armature.api.teams.Teams.of(server).realTeamOf(player.getUUID());
        if (mine.isEmpty()) {
            // The public list rides on the solo snapshot: a player in no party is the one who can use
            // a browse list of open parties, and their panel draws it under the invitations. Filled
            // here rather than for every snapshot because a member's panel never draws it.
            sendRoster(player, PartySnapshot.none().withPublic(server));
            return;
        }
        UUID teamId = mine.get().id();
        sendRoster(player, PartySnapshot.of(server, teamId)
                .withArriving(server, player, id -> invitesFor(server, id))
                .withMode(dev.ellipog.tenet.party.PartyStore.of(server).modeOf(teamId).id()));
    }

    /**
     * A player's current roster, built for a recipient who is already in the player list.
     *
     * <h2>Why this exists beside {@link #sendOwnRosterTo}</h2>
     *
     * <p>Because the two answer "who is this player, as far as the server can see". A login needs
     * {@code withArriving} — the player list does not yet contain them, and the whole reason that
     * method exists is a login that drew its own row as offline. Every other push happens long after
     * the join, where the list is the right source and the player object is not in hand: a command
     * knows the target's id, not their {@code ServerPlayer}. Splitting the two keeps the login's
     * exceptional shape at the login, where it is a fact rather than a guess.
     */
    public static void sendOwnRosterById(MinecraftServer server, UUID playerId) {
        if (server == null) {
            return;
        }
        Optional<Team> mine = dev.ellipog.armature.api.teams.Teams.of(server).realTeamOf(playerId);
        if (mine.isEmpty()) {
            sendRosterTo(server, playerId, PartySnapshot.none().withPublic(server));
            return;
        }
        UUID teamId = mine.get().id();
        sendRosterTo(server, playerId, PartySnapshot.of(server, teamId)
                .withPlayers(server, playerId, id -> invitesFor(server, id))
                .withMode(dev.ellipog.tenet.party.PartyStore.of(server).modeOf(teamId).id()));
    }

    /**
     * Every solo player's public list, refreshed.
     *
     * <h2>Why only the solo players</h2>
     *
     * <p>Because the list is drawn on the solo screen. A member's snapshot is pushed by
     * {@link #sendPartyToTeam} when their party changes, and their panel never draws the browse list,
     * so sending it to them would be a message per member per party event for a list nobody sees.
     *
     * <p>Called on the events that change the <i>set</i> of public parties or the counts in it: a
     * party formed, disbanded, joined, or switched public. Each of those is rare, and the alternative
     * is a browse list that is as stale as the server is busy.
     */
    public static void sendPublicLists(MinecraftServer server) {
        if (server == null) {
            return;
        }
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            if (dev.ellipog.armature.api.teams.Teams.of(server).realTeamOf(player.getUUID()).isEmpty()) {
                sendOwnRosterById(server, player.getUUID());
            }
        }
    }

    /**
     * Tells one player they are in no party.
     *
     * <h2>Why an empty roster has to be sent rather than inferred</h2>
     *
     * <p>Because a client's cache is <b>stale by default</b>: it holds whatever it was last told, and if
     * a party is disbanded while a panel is open, nothing else would ever tell that client. The panel
     * would go on drawing a party that no longer exists — with real player names in it, which is what
     * makes it convincing. So a disband sends this to each former member rather than sending nothing.
     */
    public static void sendNoPartyTo(MinecraftServer server, UUID playerId) {
        // With the public list, like every other solo push: a player who has just left or disbanded is
        // the one looking at the browse list, and a bare `none()` would show them "No public parties
        // right now" until some later event refreshed it.
        sendRosterTo(server, playerId, PartySnapshot.none().withPublic(server));
    }

    /**
     * Sends one payload, tolerating a player who has gone.
     *
     * <p>The check is not defensive noise. A sync is produced by several paths that run on a tick — the
     * engine reporting a change, a team membership change, a command — and a player can disconnect
     * between the moment a change is noticed and the moment it is sent. Sending to a disconnected player
     * is a no-op on Fabric and throws on NeoForge, which is the worst combination: it works on one loader
     * and breaks the other, from the same source file.
     */
    private static void send(ServerPlayer player, CustomPacketPayload payload) {
        if (player.connection == null || player.hasDisconnected()) {
            return;
        }
        ArmatureNetwork.sendToPlayer(player, payload);
    }

    /** Everyone online who shares this player's progress. */
    private static List<ServerPlayer> membersOf(MinecraftServer server, ServerPlayer sender) {
        UUID owner = ProgressService.progressOwner(server, sender);
        List<ServerPlayer> members = new ArrayList<>();
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            if (ProgressService.progressOwner(server, player).equals(owner)) {
                members.add(player);
            }
        }
        return members;
    }

    // ------------------------------------------------------------------
    // The vitals overlay
    // ------------------------------------------------------------------

    /**
     * The players who asked for the vitals overlay, for this session.
     *
     * <p>Server-side state, and it has to be: the client cannot be told "toggle" without being asked what it
     * currently shows, and a client answering that question is a client deciding whether it may have the
     * instrument. So the server remembers, the command flips it, and the client is told the result.
     *
     * <p>Session-only, deliberately: nothing is written to disk, so a restart forgets. Persisting it is a
     * per-player record and a hook on join, which is a bigger change than the switch is worth today — and
     * the join case is the one that would need it, since a client that has never been told draws nothing.
     */
    private static final java.util.Set<UUID> VITALS = new java.util.HashSet<>();

    /** Whether this player's client has been asked to draw vitals. */
    public static boolean vitals(ServerPlayer player) {
        return VITALS.contains(player.getUUID());
    }

    /**
     * Turns the vitals overlay on or off for one player and tells their client.
     *
     * <p>The answer is returned rather than assumed on the caller's side, so the command that flips it
     * reports what actually happened — including the case where the player has disconnected between the
     * command and the send, which {@link #send} drops.
     */
    public static boolean setVitals(ServerPlayer player, boolean on) {
        if (on) {
            VITALS.add(player.getUUID());
        }
        else {
            VITALS.remove(player.getUUID());
        }
        send(player, new VitalsPayload(on));
        return on;
    }
}
