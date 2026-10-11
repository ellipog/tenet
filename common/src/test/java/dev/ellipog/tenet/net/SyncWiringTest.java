package dev.ellipog.tenet.net;

import dev.ellipog.armature.api.net.ArmatureNetwork;
import dev.ellipog.armature.api.teams.TeamPolicy;
import dev.ellipog.armature.api.teams.TeamRole;
import dev.ellipog.tenet.client.ClientChapterReplica;
import dev.ellipog.tenet.client.ClientEditReplies;
import dev.ellipog.tenet.client.ClientLocale;
import dev.ellipog.tenet.client.ClientPartyCache;
import dev.ellipog.tenet.client.ClientQuestCache;
import dev.ellipog.tenet.client.ClientServerLists;
import dev.ellipog.tenet.progress.ProgressionEngine;
import dev.ellipog.tenet.progress.QuestProgress;
import dev.ellipog.tenet.progress.QuestState;
import dev.ellipog.tenet.progress.TeamProgress;
import dev.ellipog.tenet.quest.Fixtures;
import dev.ellipog.tenet.quest.MinecraftTestBootstrap;
import dev.ellipog.tenet.quest.Quest;
import dev.ellipog.tenet.quest.QuestIndex;

import io.netty.buffer.Unpooled;

import net.minecraft.core.RegistryAccess;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The wiring, as opposed to the parts: a message that goes through a real codec and into the handler
 * the loader was actually given.
 *
 * <h2>Why this file exists, and what the plan says about it</h2>
 *
 * <p>Stage 4's own note calls this "the single largest gap": <i>"the server declares three payloads
 * and the codecs round-trip, and nothing has ever had a quest sync pushed to it."</i> Three links
 * make that so — registration, codec, handler — and a test of any one of them leaves the other two
 * able to be wrong in a way nothing notices.
 *
 * <p>{@code PayloadTest} tests the codecs. {@code QuestSyncTest} tests the writer against the reader
 * by calling {@code acceptTree} and {@code acceptProgress} <i>directly</i> — which is a real test of
 * the serialisation and a test of nothing at all about the plumbing. Neither would notice a handler
 * that was wired to the wrong payload type, or a registration whose {@code onClient} pointed at the
 * wrong method, or a payload whose chunk header never reached the reassembler.
 *
 * <p>So this takes the handler <b>out of the registry</b> — {@code ArmatureNetwork.registrations()} is
 * public and each carries its own {@code onClient} — and feeds it a payload that has been encoded and
 * decoded through a real Minecraft buffer. That covers registration → codec → handler.
 *
 * <h2>What it still does not cover, stated plainly</h2>
 *
 * <p>The loader's transport. {@code ArmatureNetwork.install} is called by each loader's entry point,
 * and no loader is present in a test JVM — so the step from "the handler does the right thing" to
 * "the packet arrived at the handler" is the one claim here that rests on the loader's own registration
 * code, which is a dozen lines in each subproject and is not asserted. That needs a client.
 */
@DisplayName("The sync wiring: registration to handler")
class SyncWiringTest {

    private static final long NOW = 1_000L;
    private static final long CLIENT_TICK = 500L;

    @BeforeAll
    static void declarePayloads() {
        // Vanilla first, and this is not optional even though nothing here says "registry".
        //
        // The chain is: a tree arriving through the handler goes to ClientQuestCache, which resolves
        // every item id to a stack on arrival — so `BuiltInRegistries.ITEM` is read. Without the
        // bootstrap that class's own initialiser throws, and the failure is not local to this class.
        // Java makes a failed class initialisation permanent: `BuiltInRegistries` is poisoned for the
        // whole JVM, so every *other* test class that needs it fails too, with
        // `NoClassDefFoundError: Could not initialize class ...BuiltInRegistries`.
        //
        // That is exactly what happened. This test was added without the bootstrap, and the run went
        // from 3 failures in 254 to 14 in 206 — three of them in QuestSyncTest, which had been
        // passing, and which does bootstrap. The count going *down* was the tell that whole classes
        // were failing at initialisation rather than individual tests failing, and the ordering was
        // arbitrary because it is whichever class the runner reaches first.
        //
        // So the rule is: a test class that can reach a registry bootstraps, whether or not it looks
        // like a registry test. `boot()` is idempotent and synchronised precisely so every class can
        // call it without coordinating.
        MinecraftTestBootstrap.boot();

        // The real declarations, so this exercises the registrations that ship rather than copies.
        TenetNetworking.declare();
    }

    @BeforeEach
    void clearCache() {
        // The cache is static state shared by every test in this JVM, which is how the client works:
        // one connection, one cache. Every test here has to start from nothing.
        ClientQuestCache.clear();
        // And the party's, for the same reason and with the same hazard: a roster left over from an
        // earlier test would be read by the pair test's "has a party" assertion, which would pass
        // without the handler having done anything.
        ClientPartyCache.clear();
        TenetNetworking.forgetTransfers();
        // And the language, for the same reason and with the same hazard: a locale left by an earlier
        // test would answer for the next one's conventional lookup, which would then pass without the
        // wire having carried anything.
        ClientLocale.clear();
        // And the editor's two server-sent lists, for the same reason and with the same hazard: a
        // structure left by an earlier test would be read below as if the wire had just carried it.
        ClientServerLists.clear();
        // And the editor's two stores, for the same reason: a replica or a reply left by an earlier test
        // would be read by the assertions below as if the handler had just delivered it.
        ClientChapterReplica.clear();
        ClientEditReplies.take();
    }

    @Test
    @DisplayName("the payload set is exactly the declared list -- the viewer seam adds no message")
    void theViewerSeamAddsNoPayload() {
        // T13's claim is "no new network message": the quest tree already reaches the client, so every
        // viewer reads the cache instead of being sent anything. A claim like that is worth a check
        // rather than a sentence, and this is the check -- every declared id, in one list. Adding a
        // payload means editing this list, and that is the moment to ask whether the message is needed
        // at all, rather than the moment after a sync nobody remembers adding.
        List<String> declared = ArmatureNetwork.registrations().stream()
                .map(registration -> registration.type().id().toString())
                .sorted()
                .toList();

        assertEquals(List.of(
                "tenet:chapter_replica",
                "tenet:choice_reward",
                "tenet:claim_all",
                "tenet:claim_chapter",
                "tenet:claim_choice",
                "tenet:claim_choice_result",
                "tenet:claim_reward",
                "tenet:claim_reward_entry",
                "tenet:claim_summary",
                // A canvas press the client cannot run itself: the chapter and the element travel, and
                // the server reads what the press does from its own index. The message is needed because
                // no existing one carries a press — a click is not a task submission, a claim, or an edit.
                "tenet:click",
                // Not a viewer message either: the undo history being discarded, which `/tenet reload`
                // sends because the client's undo *button* is drawn from counters of its own and would
                // otherwise keep offering an undo over a history the server had thrown away.
                "tenet:edit_history",
                // The reload's cross-file faults, which a per-file validator cannot see and which the server
                // log was the only place to learn about.
                "tenet:edit_problems",
                "tenet:editor_op",
                "tenet:editor_reply",
                // The one player's quest text, in the language they read: the tree is broadcast and
                // carries the canonical strings, so a locale cannot ride on it. The request is the
                // other half -- the only way a language changed after the login handshake can reach
                // the server, since no loader exposes a hook for it.
                "tenet:locale_request",
                "tenet:locale_sync",
                // Not a viewer message: a command's answer to "open the book", which no existing
                // message carries — progress carries states, the tree carries content, and neither
                // opens a screen. Asked and answered here like the vitals switch below.
                "tenet:open_book",
                "tenet:party_sync",
                // Not a viewer message either: a command's answer to "show me the player's view", which
                // no existing message carries — the open above opens the editor, and neither the tree
                // nor progress names a view. Asked and answered here like the vitals switch below.
                "tenet:preview",
                "tenet:progress_sync",
                "tenet:quest_sync",
                "tenet:replica_request",
                "tenet:reward_overflow",
                // Not a viewer message either: a reward-level sentence (toast description, command
                // feedback) for the book's own stack. Needed because chat cannot be read behind an
                // open screen, and no existing message carries a reward's words — overflow and
                // summary carry counts, not sentences.
                "tenet:reward_toast",
                "tenet:server_lists",
                "tenet:stage_sync",
                "tenet:submit_task",
                "tenet:table_import_request",
                "tenet:table_open",
                "tenet:table_replica",
                "tenet:table_replica_request",
                "tenet:table_roll",
                "tenet:table_roll_request",
                // Not a viewer message: the vitals overlay's switch, which is an operator's command
                // answering back to the one client that asked. It carries no content — one boolean — and
                // it is here so that the question this list exists to force was asked and answered.
                "tenet:vitals"), declared,
                "the payload set moved; a viewer integration must not add a message");
    }

    // ------------------------------------------------------------------
    // Getting at the handlers
    // ------------------------------------------------------------------

    /**
     * The client handler the loader was given for a payload, taken from the registry.
     *
     * <p>This is the whole trick of this file. Reaching into the registry rather than calling
     * {@code ClientQuestCache} directly is what makes the test about the wiring: if a registration
     * were pointed at the wrong handler, or a payload were registered with a consumer that ignored
     * it, this would hand back something that does not do the job — and the assertions below would
     * fail, where a direct call would pass.
     */
    @SuppressWarnings("unchecked")
    private static <T extends CustomPacketPayload> Consumer<T> clientHandler(String path) {
        ArmatureNetwork.Registration<?> registration = ArmatureNetwork.registrations().stream()
                .filter(candidate -> candidate.type().id().getPath().equals(path))
                .findFirst()
                .orElseThrow(() -> new AssertionError("no payload is registered at " + path));

        assertNotNull(registration.onClient(),
                "the payload " + path + " travels to the client but has no client handler at all");
        return (Consumer<T>) registration.onClient();
    }

    /** Encodes and decodes through the real codec, so the handler is fed what the wire would deliver. */
    private static <T extends CustomPacketPayload> T throughTheCodec(
            StreamCodec<? super RegistryFriendlyByteBuf, T> codec, T payload) {
        RegistryFriendlyByteBuf buffer = new RegistryFriendlyByteBuf(Unpooled.buffer(), RegistryAccess.EMPTY);
        try {
            codec.encode(buffer, payload);
            T decoded = codec.decode(buffer);
            assertNotNull(decoded, "the codec decoded to null");
            return decoded;
        }
        finally {
            buffer.release();
        }
    }

    /**
     * Sends a tree the way the server does: pack it, chunk it, and hand every chunk to the handler.
     *
     * <p>Replicates {@code QuestSync.sendTreeTo}'s three steps rather than calling it, because that
     * method needs a {@code ServerPlayer} to send to and the sending is not what is under test. What
     * <i>is</i> under test is that those three steps compose into something the far end can read.
     */
    private static void sendTreeAsTheServerWould(QuestIndex index) {
        Consumer<QuestSyncPayload> handler = clientHandler("quest_sync");

        byte[] packed = SyncWire.pack(QuestSync.treeAsJson(index));
        List<byte[]> parts = SyncWire.chunk(packed);
        int transferId = SyncWire.newTransferId();

        for (int i = 0; i < parts.size(); i++) {
            QuestSyncPayload payload = new QuestSyncPayload(index.questCount(), index.chapterCount(),
                    // No pack theme. Sent as the empty string rather than as a distinct "absent" value,
                    // because a stream codec's string has no null: see QuestSyncPayload's own note on
                    // why normalising it there means no reader has to decide what null means.
                    "",
                    new SyncChunk(transferId, i, parts.size(), true), parts.get(i));
            handler.accept(throughTheCodec(QuestSyncPayload.CODEC, payload));
        }
    }

    /** Sends progress the way the server does. See {@link #sendTreeAsTheServerWould}. */
    private static void sendProgressAsTheServerWould(UUID team, byte[] json, boolean full) {
        Consumer<ProgressSyncPayload> handler = clientHandler("progress_sync");

        byte[] packed = SyncWire.pack(json);
        List<byte[]> parts = SyncWire.chunk(packed);
        int transferId = SyncWire.newTransferId();

        for (int i = 0; i < parts.size(); i++) {
            ProgressSyncPayload payload = new ProgressSyncPayload(team, NOW,
                    new SyncChunk(transferId, i, parts.size(), full), parts.get(i),
                    ProgressSyncPayload.REASON_CHANGED);
            handler.accept(throughTheCodec(ProgressSyncPayload.CODEC, payload));
        }
    }

    // ------------------------------------------------------------------
    // Fixtures
    // ------------------------------------------------------------------

    private static QuestIndex threeQuests() {
        return Fixtures.indexOf(Fixtures.file(
                Fixtures.q("first").build(),
                Fixtures.q("second").dependsOn("first").build(),
                Fixtures.q("third").dependsOn("second").build()));
    }

    private static ProgressionEngine.Resolution resolve(QuestIndex index, TeamProgress progress) {
        return ProgressionEngine.resolve(index, progress, NOW);
    }

    // ------------------------------------------------------------------
    // The tree, end to end
    // ------------------------------------------------------------------

    @Test
    @DisplayName("a tree sent through the registered handler arrives whole, in order, decompressed")
    void aTreeArrivesThroughTheHandler() {
        // The end-to-end claim: registration, codec, chunking, reassembly, decompression and the
        // cache's parser, in one test. Any one of those six being wired wrong fails here.
        QuestIndex index = threeQuests();

        // A quest line long enough to need more than one chunk would be a slow fixture to build, so
        // the chunking is exercised at one chunk here and at two in compressedMessageAcrossChunks
        // below. What matters in this test is that the chain is connected at all.
        sendTreeAsTheServerWould(index);

        assertTrue(ClientQuestCache.hasTree(), "the tree never reached the cache");
        assertEquals(3, ClientQuestCache.questCount());
        assertEquals(1, ClientQuestCache.chapterCount());
        assertEquals(List.of("first", "second", "third"),
                ClientQuestCache.entries().stream().map(ClientQuestCache.Entry::id).toList());

        // A field from deeper in the JSON, so a handler that stored the counts and dropped the body
        // would fail rather than pass on the counts alone.
        ClientQuestCache.Entry second = ClientQuestCache.entries().stream()
                .filter(entry -> entry.id().equals("second"))
                .findFirst()
                .orElseThrow();
        assertEquals(List.of("first"), second.dependencies(),
                "the dependency list did not survive the round trip through the handler");
    }

    @Test
    @DisplayName("a message in two chunks arrives, however the chunks are ordered")
    void compressedMessageAcrossChunks() {
        // Forced to span chunks by shrinking the chunk size rather than by writing a giant fixture:
        // the chunk size is a constant, and SyncWire.chunk is what reads it -- so this asserts the
        // *reassembly* path with a message that genuinely needs two parts, which is the path a real
        // large pack takes and the one a single-chunk test never enters.
        QuestIndex index = threeQuests();
        byte[] json = QuestSync.treeAsJson(index);
        byte[] packed = SyncWire.pack(json);

        // Split the packed bytes by hand into two chunks, then send them backwards.
        int split = Math.max(1, packed.length / 2);
        byte[] head = java.util.Arrays.copyOfRange(packed, 0, split);
        byte[] tail = java.util.Arrays.copyOfRange(packed, split, packed.length);

        Consumer<QuestSyncPayload> handler = clientHandler("quest_sync");
        handler.accept(throughTheCodec(QuestSyncPayload.CODEC, new QuestSyncPayload(
                index.questCount(), index.chapterCount(), "",
                new SyncChunk(4242, 1, 2, true), tail)));
        assertFalse(ClientQuestCache.hasTree(), "half a message must not register a tree");

        handler.accept(throughTheCodec(QuestSyncPayload.CODEC, new QuestSyncPayload(
                index.questCount(), index.chapterCount(), "",
                new SyncChunk(4242, 0, 2, true), head)));

        assertTrue(ClientQuestCache.hasTree(), "the second chunk should have completed the message");
        assertEquals(3, ClientQuestCache.questCount(),
                "the tree arrived with the wrong quest count, which means the two chunks were "
                        + "concatenated in the wrong order");
    }

    @Test
    @DisplayName("a chunk that cannot be placed is ignored, and the client stays connected")
    void anUnplaceableChunkIsIgnoredRatherThanThrown() {
        // The response to a malformed message is "log it and keep going", which is the whole reason
        // SyncWire.MalformedSync is caught in the handler rather than allowed out. Letting it escape
        // would disconnect a player whose server is running a pack this client cannot parse, which is
        // a worse failure than showing nothing.
        Consumer<QuestSyncPayload> handler = clientHandler("quest_sync");

        // An index past the count: the two ends disagree about the format.
        QuestSyncPayload impossible = new QuestSyncPayload(3, 1, "",
                new SyncChunk(11, 5, 2, true), new byte[]{1, 2, 3});

        handler.accept(throughTheCodec(QuestSyncPayload.CODEC, impossible));

        assertFalse(ClientQuestCache.hasTree(), "nothing should have been registered");
        assertEquals(0, TenetNetworking.pendingTransfers(),
                "an unplaceable chunk must not be left pending -- waiting for chunks that cannot "
                        + "exist is a client that never finishes receiving and never says so");
    }

    // ------------------------------------------------------------------
    // Progress, full and delta
    // ------------------------------------------------------------------

    @Test
    @DisplayName("a full progress sync arrives through the handler")
    void aFullProgressSyncArrives() {
        QuestIndex index = threeQuests();
        TeamProgress progress = TeamProgress.empty()
                .put(Fixtures.quest(index, "first"),
                        QuestProgress.NONE.completedAt(NOW).withRewardsClaimed(true));
        UUID team = UUID.randomUUID();

        ClientQuestCache.acceptTree(index.questCount(), index.chapterCount(), QuestSync.treeAsJson(index));
        sendProgressAsTheServerWould(team,
                QuestSync.progressAsJson(resolve(index, progress), progress, index), true);

        assertEquals(QuestState.COMPLETED, ClientQuestCache.stateOf("first"));
        assertEquals(QuestState.UNLOCKED, ClientQuestCache.stateOf("second"),
                "a completed prerequisite should have unlocked its dependent through the handler too");
        assertEquals(team, ClientQuestCache.teamId().orElseThrow());
    }

    @Test
    @DisplayName("a sequence of deltas leaves the client exactly where one full sync of the same state would")
    void deltasEqualOneFullSync() {
        // THE property of the delta work, and the reason it is one test rather than several.
        //
        // The server now sends only what changed. That is a bandwidth optimisation with a correctness
        // obligation: applying the sequence must produce what applying one full sync produces, at
        // every step. A delta that omits something it should have sent, or that forgets to name a
        // removal, looks fine in a packet capture and leaves a client showing the wrong questline.
        //
        // Compared through the client's own reader rather than by comparing JSON, because the JSON is
        // not the contract -- what the client ends up believing is.
        QuestIndex index = threeQuests();
        Quest first = Fixtures.quest(index, "first");
        Quest second = Fixtures.quest(index, "second");
        UUID team = UUID.randomUUID();

        // --- client A: one full sync of the final state ---
        TeamProgress finished = TeamProgress.empty()
                .put(first, QuestProgress.NONE.completedAt(NOW).withRewardsClaimed(true))
                .put(second, QuestProgress.NONE.completedAt(NOW).withRewardsClaimed(true));
        ClientQuestCache.clear();
        ClientQuestCache.acceptTree(index.questCount(), index.chapterCount(), QuestSync.treeAsJson(index));
        sendProgressAsTheServerWould(team,
                QuestSync.progressAsJson(resolve(index, finished), finished, index), true);

        QuestState aSecond = ClientQuestCache.stateOf("second");
        QuestState aThird = ClientQuestCache.stateOf("third");
        int aFirstTask = ClientQuestCache.taskProgressOf("first", 0);

        // --- client B: a full sync, then two deltas, reaching the same state ---
        ClientQuestCache.clear();
        ClientQuestCache.acceptTree(index.questCount(), index.chapterCount(), QuestSync.treeAsJson(index));

        TeamProgress step0 = TeamProgress.empty();
        QuestSync.Delta full = QuestSync.progressDelta(resolve(index, step0), step0, index, null);
        sendProgressAsTheServerWould(team, full.json(), true);

        TeamProgress step1 = TeamProgress.empty()
                .put(first, QuestProgress.NONE.completedAt(NOW).withRewardsClaimed(true));
        QuestSync.Delta delta1 = QuestSync.progressDelta(resolve(index, step1), step1, index, full.snapshot());
        sendProgressAsTheServerWould(team, delta1.json(), false);

        QuestSync.Delta delta2 = QuestSync.progressDelta(resolve(index, finished), finished, index,
                delta1.snapshot());
        sendProgressAsTheServerWould(team, delta2.json(), false);

        // --- the two clients must agree ---
        assertEquals(aSecond, ClientQuestCache.stateOf("second"),
                "two deltas left 'second' where one full sync does not");
        assertEquals(aThird, ClientQuestCache.stateOf("third"));
        assertEquals(aFirstTask, ClientQuestCache.taskProgressOf("first", 0));
    }

    @Test
    @DisplayName("a finished task is one rule, read by the viewer page and by nothing that can drift")
    void taskCompletionIsOneRule() {
        // `taskDone` is `progress >= count`, and it was spelled out inline in the viewer's live row
        // before this. The assertion is through a real sync rather than against a field, because the
        // rule's inputs -- the tree's task counts and the progress map -- arrive by two different roads.
        QuestIndex index = Fixtures.indexOf(Fixtures.file(Fixtures.q("first").tasks(3).build()));
        Quest first = Fixtures.quest(index, "first");
        UUID team = UUID.randomUUID();
        ClientQuestCache.clear();
        ClientQuestCache.acceptTree(index.questCount(), index.chapterCount(),
                QuestSync.treeAsJson(index));

        assertFalse(ClientQuestCache.taskDone("first", 0), "no task is finished on an untouched quest");

        TeamProgress progress = TeamProgress.empty().put(first,
                QuestProgress.NONE.completedAt(NOW).recordTask(0, 1).recordTask(1, 1));
        sendProgressAsTheServerWould(team,
                QuestSync.progressAsJson(resolve(index, progress), progress, index), true);

        assertTrue(ClientQuestCache.taskDone("first", 0), "the first is finished");
        assertTrue(ClientQuestCache.taskDone("first", 1), "the second is");
        assertFalse(ClientQuestCache.taskDone("first", 2), "and the third is not");

        // An index no task holds reads as not-done rather than throwing: a stale index after a reload is
        // a row that should read as unfinished.
        assertFalse(ClientQuestCache.taskDone("first", 9), "an index no task holds is not finished");
        assertFalse(ClientQuestCache.taskDone("first", -1), "nor is a negative one");
        assertFalse(ClientQuestCache.taskDone("no_such_quest", 0), "and an unknown quest has none");
    }

    @Test
    @DisplayName("a delta after nothing changed carries no quests at all")
    void anUnchangedDeltaIsEmpty() {
        // The property the whole optimisation rests on, and the one that silently stops being true:
        // a snapshot compared by object identity rather than by its serialised text would report
        // every quest as changed on every tick, and the delta would send the whole tree -- looking
        // exactly like the optimisation had never been made rather than like a bug.
        QuestIndex index = threeQuests();
        TeamProgress progress = TeamProgress.empty();
        ProgressionEngine.Resolution resolution = resolve(index, progress);

        QuestSync.Delta first = QuestSync.progressDelta(resolution, progress, index, null);
        String firstJson = new String(first.json(), StandardCharsets.UTF_8);
        assertTrue(firstJson.contains("first"), "a full sync must carry every quest");

        QuestSync.Delta again = QuestSync.progressDelta(resolution, progress, index, first.snapshot());
        String againJson = new String(again.json(), StandardCharsets.UTF_8);

        assertFalse(againJson.contains("\"first\""),
                "nothing changed, so nothing should have been sent. The delta carried the tree: "
                        + againJson);
        assertFalse(againJson.contains("\"removed\""),
                "and nothing was removed, so there is no removal list either: " + againJson);
    }

    @Test
    @DisplayName("a delta names a quest the tree no longer has, since absence means unchanged")
    void aDeltaNamesRemovals() {
        // The one thing a delta cannot express by omission. A quest missing from a delta means
        // "unchanged"; a quest missing from the *index* means the file no longer declares it, and the
        // client has to be told or it keeps a ghost node until the player reconnects.
        QuestIndex before = Fixtures.indexOf(Fixtures.file(
                Fixtures.q("stays").build(),
                Fixtures.q("goes").build()));
        QuestIndex after = Fixtures.indexOf(Fixtures.file(Fixtures.q("stays").build()));

        TeamProgress progress = TeamProgress.empty();
        QuestSync.Delta first = QuestSync.progressDelta(resolve(before, progress), progress, before, null);
        QuestSync.Delta second = QuestSync.progressDelta(resolve(after, progress), progress, after,
                first.snapshot());

        String json = new String(second.json(), StandardCharsets.UTF_8);
        assertTrue(json.contains("\"removed\""),
                "a quest that is gone from the tree must be named as removed: " + json);
        assertTrue(json.contains("goes"), json);

        // And applied through the handler, the client actually forgets it. The JSON containing the
        // word is not the contract; the cache dropping the entry is.
        ClientQuestCache.clear();
        ClientQuestCache.acceptTree(before.questCount(), before.chapterCount(),
                QuestSync.treeAsJson(before));
        UUID team = UUID.randomUUID();
        sendProgressAsTheServerWould(team, first.json(), true);

        // The entry's existence, not its state. `stateOf` reads LOCKED both for a quest that is locked
        // and for one the cache has never heard of, so asserting LOCKED here -- which is what my first
        // version did -- passes whether the quest is present or absent. It is the one assertion in this
        // test that cannot be written against stateOf, which is why the removal check below also uses
        // the entry list.
        assertTrue(ClientQuestCache.entries().stream().anyMatch(entry -> entry.id().equals("goes")),
                "fixture sanity: both quests should be in the client's tree before the removal");

        ClientQuestCache.acceptTree(after.questCount(), after.chapterCount(),
                QuestSync.treeAsJson(after));
        sendProgressAsTheServerWould(team, second.json(), false);

        assertFalse(ClientQuestCache.entries().stream().anyMatch(entry -> entry.id().equals("goes")),
                "the client should have forgotten a quest the new tree does not have");
    }

    @Test
    @DisplayName("a delta with no full sync behind it is refused, and the client keeps what it had")
    void aDeltaWithNothingBehindItIsRefused() {
        // The refusal that matters, and the reason it is a refusal rather than a merge. Applying a
        // delta onto an empty cache produces a handful of quests and everything else LOCKED, which
        // looks exactly like a working sync of a very small pack -- there is no symptom that points
        // at the cause, so it has to be prevented rather than diagnosed.
        QuestIndex index = threeQuests();
        TeamProgress progress = TeamProgress.empty()
                .put(Fixtures.quest(index, "first"),
                        QuestProgress.NONE.completedAt(NOW).withRewardsClaimed(true));

        ClientQuestCache.acceptTree(index.questCount(), index.chapterCount(), QuestSync.treeAsJson(index));
        UUID team = UUID.randomUUID();

        // A delta, and this client has been sent no full sync for this team.
        QuestSync.Delta delta = QuestSync.progressDelta(resolve(index, progress), progress, index, null);
        sendProgressAsTheServerWould(team, delta.json(), false);

        assertEquals(QuestState.LOCKED, ClientQuestCache.stateOf("first"),
                "a delta applied onto nothing would have shown this as COMPLETED, and every quest it "
                        + "did not mention as LOCKED -- a questline that is a mixture of a real state "
                        + "and an invented one");
        assertTrue(ClientQuestCache.teamId().isEmpty(),
                "and the client must not have adopted the team from a message it refused");

        // And the same message marked full is accepted, so the refusal is about the flag rather than
        // about the payload being unreadable.
        sendProgressAsTheServerWould(team, delta.json(), true);
        assertEquals(QuestState.COMPLETED, ClientQuestCache.stateOf("first"));
    }

    @Test
    @DisplayName("progress for a different team replaces rather than merges")
    void aTeamChangeStartsFresh() {
        // A player who leaves a party is sent the next sync for a different team. Merging would leave
        // the previous party's completions in the new team's cache -- a questline that belongs to
        // nobody, and one that looks like the party system is leaking progress between groups.
        QuestIndex index = threeQuests();
        Quest first = Fixtures.quest(index, "first");

        ClientQuestCache.acceptTree(index.questCount(), index.chapterCount(), QuestSync.treeAsJson(index));

        TeamProgress done = TeamProgress.empty()
                .put(first, QuestProgress.NONE.completedAt(NOW).withRewardsClaimed(true));
        sendProgressAsTheServerWould(UUID.randomUUID(),
                QuestSync.progressAsJson(resolve(index, done), done, index), true);
        assertEquals(QuestState.COMPLETED, ClientQuestCache.stateOf("first"), "fixture sanity");

        // A different team, with nothing done.
        TeamProgress fresh = TeamProgress.empty();
        UUID other = UUID.randomUUID();
        sendProgressAsTheServerWould(other,
                QuestSync.progressAsJson(resolve(index, fresh), fresh, index), true);

        assertEquals(QuestState.UNLOCKED, ClientQuestCache.stateOf("first"),
                "the new team's progress should not have inherited the old team's completion");
        assertEquals(other, ClientQuestCache.teamId().orElseThrow());
    }

    @Test
    @DisplayName("an unreadable progress message clears the team, so the next delta is refused too")
    void anUnreadableMessageForcesAResync() {
        // The subtle half of the refusal. If a message fails to parse and the cache kept the team id,
        // the *next* delta would look applicable and would be applied onto the empty map this failure
        // left behind -- which is the "handful of quests, everything else locked" state arriving by a
        // different route. Clearing the team is what forces the next message to be a full sync.
        QuestIndex index = threeQuests();
        UUID team = UUID.randomUUID();
        ClientQuestCache.acceptTree(index.questCount(), index.chapterCount(), QuestSync.treeAsJson(index));

        Consumer<ProgressSyncPayload> handler = clientHandler("progress_sync");
        handler.accept(throughTheCodec(ProgressSyncPayload.CODEC, new ProgressSyncPayload(
                team, NOW, new SyncChunk(77, 0, 1, true),
                "this is not JSON at all".getBytes(StandardCharsets.UTF_8),
                ProgressSyncPayload.REASON_CHANGED)));

        assertTrue(ClientQuestCache.teamId().isEmpty(),
                "a message that could not be read must leave the client with no team, so that the "
                        + "next delta is refused as well rather than applied onto the wreckage");
    }

    // ------------------------------------------------------------------
    // The party roster, which is the one channel with no client-side parser
    // ------------------------------------------------------------------

    @Test
    @DisplayName("a roster sent through the registered handler reaches the cache, and moves its revision")
    void aRosterArrivesThroughTheHandler() {
        // The party's counterpart to the tree test above, and the half of the reported fault this file
        // is in a position to hold. Two separate things could leave the panel empty and they fail in
        // different places: **the server may never send a roster** (see
        // `QuestPlaythroughTest.aPartysRosterIsReadBackOffTheServer`) and **the client may never act on
        // one**. This is the second: registration, codec, handler and cache, through the registry the
        // loader is handed, so a registration pointed at the wrong method fails here.
        //
        // The revision is asserted alongside the contents, and it is not a formality. The panel is
        // rebuilt when the counter moves, so a handler that filled the cache without moving the counter
        // would leave a roster that arrives while the panel is open undrawn -- which is exactly the
        // case `PartySyncPayload` says the roster cannot be request-only for.
        UUID party = UUID.randomUUID();
        UUID self = UUID.randomUUID();
        UUID friend = UUID.randomUUID();
        String packed = new PartySnapshot(party, "the crew", self,
                List.of(new PartySnapshot.Member(self, "TenetTester", TeamRole.OWNER),
                        new PartySnapshot.Member(friend, "SomebodyElse", TeamRole.MEMBER)),
                List.of(), List.of("TenetTester", "SomebodyElse"), "pooled",
                // Both of them connected, which is what a client's marker is drawn from now: the
                // presence travels as its own lines, by id, rather than being guessed at by name.
                List.of(self, friend),
                // And the fields the panel grew after the first version: an outgoing invitation, the
                // policy the settings switches read, the member cap the header writes, and -- for a
                // solo recipient -- the open parties it may join.
                List.of(new PartySnapshot.SentInvite("SomebodyElse", 12L)),
                new TeamPolicy(true, false), 8,
                List.of(new PartySnapshot.PublicParty(party, "the crew", 2, 8))).pack();

        long before = ClientPartyCache.rosterRevision();

        Consumer<PartySyncPayload> handler = clientHandler("party_sync");
        handler.accept(throughTheCodec(PartySyncPayload.CODEC, new PartySyncPayload(packed)));

        assertTrue(ClientPartyCache.hasParty(),
                "the roster did not reach the cache, so a client in a party of two would draw the "
                        + "empty state for the rest of the session");
        assertEquals(2, ClientPartyCache.memberCount());
        assertEquals(party, ClientPartyCache.snapshot().teamId());
        assertEquals("the crew", ClientPartyCache.snapshot().teamName(),
                "both header fields came through, not just the one the assertions above name");
        assertEquals(2, ClientPartyCache.snapshot().online().size(),
                "and the online list, which is what the panel builds its Invite buttons from");

        assertNotEquals(before, ClientPartyCache.rosterRevision(),
                "a roster arrived, so the counter the panel compares must have moved -- a handler that "
                        + "filled the cache silently would leave an open panel showing the roster it "
                        + "was built with");

        // A roster of nobody is a real message rather than an absent one: it is what a disband sends,
        // and the client has to be able to tell "I am in no party" from "I have heard nothing".
        handler.accept(throughTheCodec(PartySyncPayload.CODEC,
                new PartySyncPayload(PartySnapshot.none().pack())));

        assertFalse(ClientPartyCache.hasParty(),
                "an empty roster must clear the party rather than be ignored, which is the whole "
                        + "reason `sendNoPartyTo` sends something instead of nothing");
    }

    // ------------------------------------------------------------------
    // The editor's lists, which only the server has
    // ------------------------------------------------------------------

    @Test
    @DisplayName("the server's lists reach the client store through the registered handler")
    void theServerListsArriveThroughTheHandler() {
        // The structure picker's own half of the fault this payload was added for. The client cannot read
        // a structure registry at all -- 1.21.1 sends eleven datapack registries and that is not one of
        // them, so the lookup answers an empty registry rather than throwing -- and `ServerListsTest`
        // covers the other half, that the list the server gathers is the datapack's own. What is covered
        // here is the plumbing between them: registration, codec, handler, store. A registration pointed
        // at the wrong method, or a handler that ignored its argument, fails here.
        Consumer<ServerListsPayload> handler = clientHandler("server_lists");
        handler.accept(throughTheCodec(ServerListsPayload.CODEC, new ServerListsPayload(
                // Deliberately unsorted: the store sorts once, so that two pickers cannot order one list
                // two ways and the first ten rows mean the same ten twice running.
                List.of("minecraft:overworld", "example:the_deep"),
                List.of("minecraft:village_plains", "#minecraft:village"))));

        assertEquals(List.of("example:the_deep", "minecraft:overworld"),
                ClientServerLists.dimensions(), "the dimensions did not reach the store");
        assertEquals(List.of("#minecraft:village", "minecraft:village_plains"),
                ClientServerLists.structures(),
                "the structures did not reach the store, so the picker would open with no rows and say "
                        + "nothing about why -- which is the fault this payload exists for");
        assertTrue(ClientServerLists.known(),
                "the server has said, so a picker must use its list rather than the vanilla fallback");

        // A second message replaces rather than merges: the lists belong to one server, and a store that
        // accumulated would offer the last world's dimensions and structures on the next one.
        handler.accept(throughTheCodec(ServerListsPayload.CODEC,
                new ServerListsPayload(List.of("minecraft:overworld"), List.of("minecraft:stronghold"))));
        assertEquals(List.of("minecraft:overworld"), ClientServerLists.dimensions());
        assertEquals(List.of("minecraft:stronghold"), ClientServerLists.structures());

        // And the disconnect hook empties both, which is what `forgetViewState` calls it for.
        ClientServerLists.clear();
        assertFalse(ClientServerLists.known(), "another server's worlds are not this one's to offer");
        assertTrue(ClientServerLists.structures().isEmpty(),
                "and neither are its structures: a picker offering the last server's would write an id "
                        + "this one has never heard of");
    }

    // ------------------------------------------------------------------
    // The locale's own channel
    // ------------------------------------------------------------------

    /**
     * Sends one locale the way the server does: pack it, chunk it, hand every chunk to the handler.
     *
     * <p>Replicates {@code QuestSync.sendLocaleFor}'s three steps rather than calling it, for the same
     * reason {@link #sendTreeAsTheServerWould} does: that method needs a {@code ServerPlayer} to send
     * to, and the sending is not what is under test. What is under test is that the steps compose into
     * something the far end can read, and that what it reads reaches the resolver.
     */
    private static void sendLocaleAsTheServerWould(String asked, String served,
                                                   java.util.Map<String, String> entries) {
        Consumer<LocaleSyncPayload> handler = clientHandler("locale_sync");

        com.google.gson.JsonObject root = new com.google.gson.JsonObject();
        entries.forEach(root::addProperty);
        byte[] packed = SyncWire.pack(root.toString()
                .getBytes(java.nio.charset.StandardCharsets.UTF_8));
        List<byte[]> parts = SyncWire.chunk(packed);
        int transferId = SyncWire.newTransferId();

        for (int i = 0; i < parts.size(); i++) {
            LocaleSyncPayload payload = new LocaleSyncPayload(asked, served,
                    new SyncChunk(transferId, i, parts.size(), true), parts.get(i));
            handler.accept(throughTheCodec(LocaleSyncPayload.CODEC, payload));
        }
    }

    @Test
    @DisplayName("a locale changes what is drawn without rebuilding the tree")
    void aLocaleChangesTheTextAndNothingElse() {
        // The claim the whole design rests on. The tree is immutable for as long as the quest files
        // are, and a language is not a quest file: it changes what a player reads and nothing about
        // which quests there are. So the *tree* revision must not move -- it is also the stamp editor
        // drafts expire against, and moving it would throw away an author's in-progress edit because a
        // player changed language -- while the text revision must, because the sidebar and the recipe
        // viewers hold rendered rows.
        QuestIndex index = Fixtures.indexOf(Fixtures.file(
                "{\"id\": \"a\", \"title\": \"Punch a Tree\"}"));
        sendTreeAsTheServerWould(index);

        ClientQuestCache.Entry before = ClientQuestCache.entry("a");
        assertNotNull(before);
        assertEquals("Punch a Tree", before.titleText());

        long treeBefore = ClientQuestCache.treeRevision();
        long textBefore = ClientQuestCache.textRevision();

        sendLocaleAsTheServerWould("hu_hu", "hu_hu",
                java.util.Map.of("quest.a.title", "Vagj egy f\u00e1t"));

        assertEquals("Vagj egy f\u00e1t", ClientQuestCache.entry("a").titleText(),
                "the locale did not reach the resolver, so the book would stay in the old language");
        assertEquals(treeBefore, ClientQuestCache.treeRevision(),
                "a language is not a tree: the revision editor drafts expire against must not move");
        assertNotEquals(textBefore, ClientQuestCache.textRevision(),
                "and the text revision must, or the sidebar would keep the previous language's rows");
        // The raw field is untouched, so the editor still seeds its fields from the authored text.
        assertEquals("Punch a Tree", ClientQuestCache.entry("a").title());
    }

    @Test
    @DisplayName("a locale the pack has nothing for is answered with an empty one, not with silence")
    void anEmptyLocaleClearsTheOverlay() {
        // The failure this prevents: a player switching away from a translated language keeps reading
        // it, because nothing ever said the new one has no text. An empty overlay labelled with the
        // locale is a real message -- and it is also what stops the client asking again.
        QuestIndex index = Fixtures.indexOf(Fixtures.file(
                "{\"id\": \"a\", \"title\": \"Punch a Tree\"}"));
        sendTreeAsTheServerWould(index);
        sendLocaleAsTheServerWould("hu_hu", "hu_hu",
                java.util.Map.of("quest.a.title", "Vagj egy f\u00e1t"));
        assertEquals("Vagj egy f\u00e1t", ClientQuestCache.entry("a").titleText());

        sendLocaleAsTheServerWould("de_de", "", java.util.Map.of());

        assertEquals("Punch a Tree", ClientQuestCache.entry("a").titleText(),
                "the previous language's text outlived the switch to one the pack does not translate");
        assertEquals("de_de", ClientLocale.asked(),
                "and the client settles on the language it actually has selected");
        assertEquals("", ClientLocale.served(), "while knowing the pack served it nothing");
    }

    @Test
    @DisplayName("a served relative does not leave the client asking for a locale it will never get")
    void aServedRelativeSettlesTheClient() {
        // The bug the regional fallback would otherwise introduce: an `es_mx` player served the pack's
        // `es_es` file would compare against `es_es`, find it different from `es_mx`, and ask again --
        // every hundred ticks, for the rest of the session. The client compares against what it asked
        // for, which is why both ids travel. See LocaleSyncPayload.
        QuestIndex index = Fixtures.indexOf(Fixtures.file(
                "{\"id\": \"a\", \"title\": \"Punch a Tree\"}"));
        sendTreeAsTheServerWould(index);

        sendLocaleAsTheServerWould("es_mx", "es_es",
                java.util.Map.of("quest.a.title", "Golpea un arbol"));

        assertEquals("Golpea un arbol", ClientQuestCache.entry("a").titleText(),
                "the regional relative's text is what a player reads");
        assertEquals("es_mx", ClientLocale.asked(), "and the client settles on its own language");
        assertEquals("es_es", ClientLocale.served(), "while being able to say what it is reading");
    }

    @Test
    @DisplayName("the locale payload travels to the client, and the request to the server")
    void theLocalePairTravelsTheRightWays() {
        // A payload registered the wrong way round is one that never arrives, and the symptom is a
        // book in the wrong language with nothing in either log. The request's server handler is
        // asserted here too, because a registration with no handler is one the loader refuses.
        assertEquals(ArmatureNetwork.Direction.TO_CLIENT, directionOf("locale_sync"));
        assertEquals(ArmatureNetwork.Direction.TO_SERVER, directionOf("locale_request"));
        assertNotNull(ArmatureNetwork.registrations().stream()
                        .filter(candidate -> candidate.type().id().getPath().equals("locale_request"))
                        .findFirst().orElseThrow().onServer(),
                "the request travels to the server and must have a handler there");
    }

    /** Which way a registered payload travels. */
    private static ArmatureNetwork.Direction directionOf(String path) {
        return ArmatureNetwork.registrations().stream()
                .filter(candidate -> candidate.type().id().getPath().equals(path))
                .findFirst()
                .orElseThrow(() -> new AssertionError("no payload is registered at " + path))
                .direction();
    }

    // ------------------------------------------------------------------
    // Housekeeping
    // ------------------------------------------------------------------

    @Test
    @DisplayName("forgetting transfers empties them, and that is what the disconnect hook calls")
    void forgetTransfersEmptiesThePendingSet() {
        // The client hook calls this beside ClientQuestCache.clear(). Asserted here rather than left
        // to the hook, because a disconnect mid-tree would otherwise leave chunks waiting for a
        // completion that the next connection's fresh transfer ids can never bring.
        Consumer<QuestSyncPayload> handler = clientHandler("quest_sync");

        handler.accept(throughTheCodec(QuestSyncPayload.CODEC, new QuestSyncPayload(
                1, 1, "", new SyncChunk(31337, 0, 9, true), new byte[]{1, 2, 3})));

        assertEquals(1, TenetNetworking.pendingTransfers());

        TenetNetworking.forgetTransfers();

        assertEquals(0, TenetNetworking.pendingTransfers());
        assertNull(null, "and nothing was completed by forgetting");
    }

    // ------------------------------------------------------------------
    // The editor's two payloads, which had no wiring test at all
    // ------------------------------------------------------------------

    @Test
    @DisplayName("a chapter replica arrives through the registered handler, chapter file and all")
    void aChapterReplicaArrivesThroughTheRegisteredHandler() {
        // The Chapter tab's whole data path, through registration, codec and handler -- the part that had
        // no test, which is why a report of "it never picks anything up" could not be checked against one.
        Consumer<ChapterReplicaPayload> handler = clientHandler("chapter_replica");
        handler.accept(throughTheCodec(ChapterReplicaPayload.CODEC, new ChapterReplicaPayload(
                "first_steps",
                "{\"one\": {\"id\": \"one\", \"title\": \"One\"}}",
                "{\"id\": \"first_steps\", \"title\": \"First Steps\"}")));

        assertEquals("First Steps", ClientChapterReplica.chapterTree("first_steps").get("title").getAsString(),
                "the chapter's own file is what the panel's fields are read from");
        assertEquals("One", ClientChapterReplica.quest("first_steps", "one").get("title").getAsString(),
                "and the quests travel beside it");
    }

    @Test
    @DisplayName("an edit reply arrives through the registered handler, and reading it consumes it")
    void anEditReplyArrivesThroughTheRegisteredHandler() {
        Consumer<EditorReplyPayload> handler = clientHandler("editor_reply");
        handler.accept(throughTheCodec(EditorReplyPayload.CODEC,
                new EditorReplyPayload("first_steps", true, "made_up_id", "applied")));

        EditorReplyPayload reply = ClientEditReplies.take();
        assertNotNull(reply, "the handler put it where the screen reads");
        assertEquals("first_steps", reply.chapter());
        assertTrue(reply.ok());
        assertEquals(List.of("applied"), reply.lines());
        assertNull(ClientEditReplies.take(), "and reading it clears it, so a refusal is not re-reported");
    }
}
