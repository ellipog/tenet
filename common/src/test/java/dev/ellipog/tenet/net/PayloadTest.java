package dev.ellipog.tenet.net;

import dev.ellipog.armature.api.net.ArmatureNetwork;
import io.netty.buffer.Unpooled;
import net.minecraft.core.RegistryAccess;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.RegistryFriendlyByteBuf;
import com.google.gson.JsonPrimitive;
import dev.ellipog.tenet.editor.EditorOp;
import dev.ellipog.tenet.editor.EditorOps;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The payloads: their ids, and whether they survive a round trip.
 *
 * <h2>Why this exists, specifically</h2>
 *
 * <p>Because a wrong payload id <b>crashed a real server on startup</b>, and nothing in the build
 * noticed. The code was:
 *
 * <pre>{@code
 * CustomPacketPayload.createType(ResourceLocation.fromNamespaceAndPath("tenet", "quest_sync").toString())
 * }</pre>
 *
 * <p>which reads as though it names the payload {@code tenet:quest_sync}. It does not.
 * {@code createType} is {@code new Type(ResourceLocation.withDefaultNamespace(name))} — it takes a
 * <i>path</i> and hardcodes the {@code minecraft} namespace. So the location became
 * {@code minecraft:tenet:quest_sync}, and the colon left inside the path threw from a static
 * initialiser:
 *
 * <pre>Non [a-z0-9/._-] character in path of location: minecraft:tenet:quest_sync</pre>
 *
 * <p>The failure was a single line in a server log after a 40-second boot. This test finds it in the
 * build in milliseconds, and it keeps finding it — the namespace assertion below is written over
 * <i>every</i> declared payload rather than the three that exist today, so the fourth one cannot
 * reintroduce the same mistake.
 *
 * <h2>The round trip is the other half</h2>
 *
 * <p>A codec with its fields in the wrong order compiles, registers, and then mangles every packet
 * it ever sends — which shows up as a client showing the wrong quest count, or a disconnect, or
 * nothing at all. Encoding and decoding an instance through a real Minecraft buffer catches it
 * without a server.
 *
 * <p>One thing makes that possible without a running game: {@code RegistryAccess.EMPTY}. None of
 * these codecs touch a registry — they are all {@code ByteBufCodecs} primitives over
 * {@code ByteBuf} — so an empty registry access is enough to construct the buffer the codecs expect.
 */
@DisplayName("Network payloads")
class PayloadTest {

    @BeforeAll
    static void declarePayloads() {
        // The real declarations, so this tests the payloads that ship rather than copies of them.
        TenetNetworking.declare();
    }

    // ------------------------------------------------------------------
    // Ids
    // ------------------------------------------------------------------

    @Test
    @DisplayName("every payload is namespaced under the mod, not under minecraft")
    void everyPayloadIsNamespacedCorrectly() {
        List<ArmatureNetwork.Registration<?>> registrations = ArmatureNetwork.registrations();

        assertFalse(registrations.isEmpty(), "no payloads were declared at all");

        for (ArmatureNetwork.Registration<?> registration : registrations) {
            assertEquals("tenet", registration.type().id().getNamespace(),
                    "payload " + registration.type().id() + " is not in the tenet namespace. "
                            + "If this is 'minecraft', the id was built with CustomPacketPayload.createType, "
                            + "which prepends a namespace and leaves any colon inside the path.");
        }
    }

    @Test
    @DisplayName("and has no colon left in its path, which is what actually threw")
    void pathsContainNoColon() {
        // The namespace assertion above would pass for a payload named `tenet:tenet:quest_sync`
        // only by luck; this one names the illegal character directly.
        for (ArmatureNetwork.Registration<?> registration : ArmatureNetwork.registrations()) {
            String path = registration.type().id().getPath();
            assertFalse(path.contains(":"),
                    "the path of " + registration.type().id() + " contains a colon, which no "
                            + "ResourceLocation path may. The namespace has leaked into the path.");
        }
    }

    @Test
    @DisplayName("every payload this mod declares is present, and none has appeared unlisted")
    void declaredPayloadsAreTheExpectedOnes() {
        List<String> ids = ArmatureNetwork.registrations().stream()
                .map(registration -> registration.type().id().toString())
                .sorted()
                .toList();

        // Written out in full rather than counted, because the failure this test exists to catch is a
        // payload registered under a wrong path -- and a count would pass while a name was wrong.
        //
        // `tenet:party_sync` arrived with the party panel, and the editor's pair with the op path. Listing
        // them here is the whole of what adding a payload costs: the two assertions above are written over
        // *every* registration rather than over a list of them, so a new payload is covered by the namespace
        // and colon checks the moment it is declared. Only this one, which names them, has to be told.
        assertEquals(List.of(
                "tenet:chapter_replica",
                "tenet:chapter_replica_chunk",
                "tenet:choice_reward",
                "tenet:claim_all",
                "tenet:claim_chapter",
                "tenet:claim_choice",
                "tenet:claim_choice_result",
                "tenet:claim_reward",
                "tenet:claim_reward_entry",
                "tenet:claim_summary",
                "tenet:click",
                "tenet:complete_quest",
                "tenet:edit_history",
                "tenet:edit_problems",
                "tenet:editor_op",
                "tenet:editor_reply",
                "tenet:intro",
                "tenet:locale_request",
                "tenet:locale_sync",
                "tenet:open_book",
                "tenet:party_sync",
                "tenet:preview",
                "tenet:progress_sync",
                "tenet:quest_sync",
                "tenet:replica_request",
                "tenet:reset_all",
                "tenet:reset_chapter",
                "tenet:reset_quest",
                "tenet:reward_overflow",
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
                "tenet:vitals"), ids);
    }

    @Test
    @DisplayName("each travels the direction it should")
    void directions() {
        // A payload registered the wrong way round is one that never arrives, and the symptom is a
        // screen that stays empty with nothing in either log.
        assertEquals(ArmatureNetwork.Direction.TO_CLIENT, directionOf("tenet:quest_sync"));
        assertEquals(ArmatureNetwork.Direction.TO_CLIENT, directionOf("tenet:progress_sync"));
        assertEquals(ArmatureNetwork.Direction.TO_SERVER, directionOf("tenet:submit_task"));
        assertEquals(ArmatureNetwork.Direction.TO_SERVER, directionOf("tenet:claim_reward"));
        // The rewards panel's per-row press: a request like the single claim's, naming one reward by
        // index. The other way round it would be a row whose Claim does nothing at all.
        assertEquals(ArmatureNetwork.Direction.TO_SERVER, directionOf("tenet:claim_reward_entry"));
        // The rewards panel's one press. A request like the single claim's, and registered the other
        // way round it would be a Claim all button that does nothing at all.
        assertEquals(ArmatureNetwork.Direction.TO_SERVER, directionOf("tenet:claim_all"));
        // A canvas press the client cannot run itself. A request like the single claim's: the other
        // way round a picture's press would do nothing at all, and the server would never hear it.
        assertEquals(ArmatureNetwork.Direction.TO_SERVER, directionOf("tenet:click"));
        // What a grant had to drop. Server to client, because the server is the one that knows: the
        // other way round it would be a book asking a question the action bar already answers.
        assertEquals(ArmatureNetwork.Direction.TO_CLIENT, directionOf("tenet:reward_overflow"));
        // How a sweep ended, and the verdict on a pick: both are answers the panel waits for, so both
        // travel from the server. Registered the other way round, a sweep that stopped would look
        // exactly like one that finished, and a refused pick would look like a card that did nothing.
        assertEquals(ArmatureNetwork.Direction.TO_CLIENT, directionOf("tenet:claim_summary"));
        assertEquals(ArmatureNetwork.Direction.TO_CLIENT, directionOf("tenet:claim_choice_result"));
        // A reward-level message for the book's own stack. Server to client, like the overflow
        // and summary notices: the server already said the same sentence in chat.
        assertEquals(ArmatureNetwork.Direction.TO_CLIENT, directionOf("tenet:reward_toast"));
        // A roster is server state, so it goes one way. Registered the other way round it would never
        // arrive, and the panel would sit on its empty state with nothing in either log.
        assertEquals(ArmatureNetwork.Direction.TO_CLIENT, directionOf("tenet:party_sync"));
        // An edit is a request, so it goes to the server; the answer comes back the other way. Registered
        // either of these the wrong way round is an editor that does nothing at all.
        assertEquals(ArmatureNetwork.Direction.TO_SERVER, directionOf("tenet:editor_op"));
        assertEquals(ArmatureNetwork.Direction.TO_CLIENT, directionOf("tenet:editor_reply"));
        // The panel's copy of a chapter: asked for by the client, answered by the server.
        assertEquals(ArmatureNetwork.Direction.TO_SERVER, directionOf("tenet:replica_request"));
        assertEquals(ArmatureNetwork.Direction.TO_CLIENT, directionOf("tenet:chapter_replica"));
        // The lists the editor searches that the client cannot build itself: the dimensions, which are
        // level data on the server, and the structures, which are a datapack registry a client is never
        // sent. They travel from the server. The other way round neither would ever arrive, and the
        // dimension picker would offer the three vanilla ids forever while the structure picker offered
        // nothing at all -- which is what it did.
        assertEquals(ArmatureNetwork.Direction.TO_CLIENT, directionOf("tenet:server_lists"));
        // A player's stages, to that player. One-way by design: the server is the only authority on what a
        // player has, so the other direction would be a client claiming its own progression.
        assertEquals(ArmatureNetwork.Direction.TO_CLIENT, directionOf("tenet:stage_sync"));
        // A choice is offered by the server and answered by the player; either way round it would be a
        // reward that can never be collected.
        assertEquals(ArmatureNetwork.Direction.TO_CLIENT, directionOf("tenet:choice_reward"));
        assertEquals(ArmatureNetwork.Direction.TO_SERVER, directionOf("tenet:claim_choice"));
        // The table family: the editor's file request, the server's roll and the import a panel asks
        // for all travel one way, and each answer comes back the other. Registered either way round,
        // the table editor sits on "Waiting for this table's file..." or a roll report never arrives.
        assertEquals(ArmatureNetwork.Direction.TO_SERVER, directionOf("tenet:table_replica_request"));
        assertEquals(ArmatureNetwork.Direction.TO_CLIENT, directionOf("tenet:table_replica"));
        // The editor's reset buttons: one quest, one chapter, the whole book. Requests like the
        // single claim's — the other way round a Reset press would do nothing at all, and the
        // server would never hear it.
        assertEquals(ArmatureNetwork.Direction.TO_SERVER, directionOf("tenet:reset_quest"));
        assertEquals(ArmatureNetwork.Direction.TO_SERVER, directionOf("tenet:reset_chapter"));
        assertEquals(ArmatureNetwork.Direction.TO_SERVER, directionOf("tenet:reset_all"));
        // The author's Complete press: a request like the resets above — the other way round a
        // Complete press would do nothing at all, and the server would never hear it.
        assertEquals(ArmatureNetwork.Direction.TO_SERVER, directionOf("tenet:complete_quest"));
        assertEquals(ArmatureNetwork.Direction.TO_SERVER, directionOf("tenet:table_roll_request"));
        assertEquals(ArmatureNetwork.Direction.TO_CLIENT, directionOf("tenet:table_roll"));
        // A table a command told the editor to open, and an import asked for by a panel: one is the
        // server opening a screen on the client, the other is the client asking the server to read a
        // container. Either the wrong way round is a press that does nothing at all.
        assertEquals(ArmatureNetwork.Direction.TO_CLIENT, directionOf("tenet:table_open"));
        assertEquals(ArmatureNetwork.Direction.TO_SERVER, directionOf("tenet:table_import_request"));
        // A book a command told the client to open, on one quest when one was named: the server
        // decides, the client opens. The other way round a book opens for nobody.
        assertEquals(ArmatureNetwork.Direction.TO_CLIENT, directionOf("tenet:open_book"));
        // A preview a command told the client to show: the server decides, the client previews.
        // The other way round a preview shows for nobody.
        assertEquals(ArmatureNetwork.Direction.TO_CLIENT, directionOf("tenet:preview"));
    }

    @Test
    @DisplayName("an op and its answer survive the wire, escapes and all")
    void editorPayloadsRoundTrip() {
        // The op is JSON *inside* a string, which is the one place two escape layers meet: a field's value
        // carries whatever the author typed, quotes and newlines included, and losing one of them turns a
        // title into a different title. So the sample is deliberately awkward rather than tidy.
        // Built by the real writer rather than typed here: the sample is whatever an ugly title
        // becomes on the wire, which is the thing that has to survive, and a hand-written string
        // with two escape layers in it is a sample that tests my quoting rather than the codec.
        EditorOpPayload op = new EditorOpPayload("first_steps", EditorOps.write(new EditorOp.SetField(
                "one", "title", new JsonPrimitive("A \"quote\" and a\nnewline")))
                .toString(), 4321L);

        EditorOpPayload sent = roundTrip(EditorOpPayload.CODEC, op);
        assertEquals(op.chapter(), sent.chapter());
        assertEquals(op.op(), sent.op(), "the bytes are the whole contract of this payload");
        assertEquals(4321L, sent.requestId(),
                "the id is what the reply is matched against, so losing it silently un-matches every answer");

        EditorReplyPayload reply = new EditorReplyPayload("first_steps", false, "one",
                "line one\nline two", 4321L);
        EditorReplyPayload answered = roundTrip(EditorReplyPayload.CODEC, reply);

        assertEquals(reply.chapter(), answered.chapter());
        assertEquals(reply.ok(), answered.ok());
        assertEquals(reply.questId(), answered.questId());
        assertEquals(List.of("line one", "line two"), answered.lines());
        assertEquals(4321L, answered.requestId(), "and the answer carries the request's own id back");
    }

    @Test
    @DisplayName("an answer with no id round-trips as zero, which is what the fallback matches on")
    void aReplyWithNoIdKeepsZero() {
        // The four-argument constructor is the no-id one, and the client's fallback is an equality test
        // against zero. A codec that turned an absent id into anything else would send every broadcast
        // through the by-id path, where it would match nothing.
        EditorReplyPayload answered = roundTrip(EditorReplyPayload.CODEC,
                new EditorReplyPayload("first_steps", true, "", ""));

        assertEquals(0L, answered.requestId());
        assertEquals(EditorReplyPayload.NO_REQUEST, answered.requestId());
    }

    @Test
    @DisplayName("the undo-history notice survives the wire")
    void editHistoryRoundTrips() {
        // One boolean, and it has to arrive true: a notice that decoded as "nothing was discarded" is a
        // client that keeps drawing an undo button over a history the server has thrown away.
        EditHistoryPayload sent = roundTrip(EditHistoryPayload.CODEC, new EditHistoryPayload(true));

        assertTrue(sent.discarded(), "the notice arrived saying the opposite of what it meant");
    }

    @Test
    @DisplayName("the reload's problems survive the wire, count and lines both")
    void editProblemsRoundTrip() {
        // The count is the part that matters when the list is long: it is what lets a truncated report say
        // how many it is not showing rather than reading as the whole of it.
        EditProblemsPayload sent = roundTrip(EditProblemsPayload.CODEC,
                new EditProblemsPayload(3, "quests/a/one.json: dangling dependsOn \"gone\"\n"
                        + "quests/b/two.json: cycle one -> two -> one"));

        assertEquals(3, sent.lines(), "the count is what says the list was cut short");
        assertEquals(List.of("quests/a/one.json: dangling dependsOn \"gone\"",
                "quests/b/two.json: cycle one -> two -> one"), sent.problems(),
                "and the lines are what the author reads");
    }

    @Test
    @DisplayName("a report with no problems round-trips as none, not as one empty line")
    void editProblemsEmpty() {
        // `split("\n")` on the empty string answers `[""]` -- one empty line -- which would be a toast with
        // nothing in it on every coalesced edit that found nothing wrong.
        EditProblemsPayload sent = roundTrip(EditProblemsPayload.CODEC, new EditProblemsPayload(0, ""));

        assertTrue(sent.problems().isEmpty(), "an empty report is no problems, not a blank one");
    }

    @Test
    @DisplayName("the rewards inbox's two payloads survive the wire: an index and a pair of counts")
    void rewardInboxPayloadsRoundTrip() {
        // The per-row press names a position in the quest's reward list; the overflow notice carries
        // two counts. Each is the whole contract of its message, so a codec that dropped a field would
        // be a Claim that takes the wrong row or a notice that under-reports what hit the floor.
        ClaimRewardEntryPayload press = roundTrip(ClaimRewardEntryPayload.CODEC,
                new ClaimRewardEntryPayload("punch_a_tree", 3));
        assertEquals("punch_a_tree", press.questId());
        assertEquals(3, press.rewardIndex(), "the reward's index, and not the task's or an entry's");

        RewardOverflowPayload dropped = roundTrip(RewardOverflowPayload.CODEC,
                new RewardOverflowPayload(2));
        assertEquals(2, dropped.stacks(), "the drop count is what the sentence names");

        // The footer's filter rides with the press: it only narrows, and a codec that dropped it would
        // make "Claim items" silently claim everything.
        ClaimAllPayload filtered = roundTrip(ClaimAllPayload.CODEC,
                new ClaimAllPayload(dev.ellipog.tenet.progress.ClaimFilter.ITEMS));
        assertEquals(dev.ellipog.tenet.progress.ClaimFilter.ITEMS, filtered.filter());

        // And the chapter's own press carries the chapter and the view's filter: a codec that dropped
        // the id would sweep the whole book from a banner, and one that dropped the filter would sweep
        // past what the view is showing -- which the first version of it did, in the choices view.
        ClaimChapterPayload chapter = roundTrip(ClaimChapterPayload.CODEC,
                new ClaimChapterPayload("stone_age", dev.ellipog.tenet.progress.ClaimFilter.CHOICES));
        assertEquals("stone_age", chapter.chapterId(), "the chapter id is the scope of the message");
        assertEquals(dev.ellipog.tenet.progress.ClaimFilter.CHOICES, chapter.filter(),
                "and the filter is what the banner was pressed in");
        assertEquals("", new ClaimChapterPayload(null, null).chapterId(),
                "a missing id is blank rather than null, which matches no chapter and claims nothing");
        assertEquals(dev.ellipog.tenet.progress.ClaimFilter.ALL,
                new ClaimChapterPayload(null, null).filter(),
                "and a missing filter is everything, which is the narrowing that narrows least");

        ClaimSummaryPayload summary = roundTrip(ClaimSummaryPayload.CODEC,
                new ClaimSummaryPayload(3, 8, true));
        assertEquals(3, summary.claimed());
        assertEquals(8, summary.total());
        assertTrue(summary.halted(), "a stopped sweep and a finished one differ by this one bit");

        ClaimChoiceResultPayload verdict = roundTrip(ClaimChoiceResultPayload.CODEC,
                new ClaimChoiceResultPayload("punch_a_tree", 1,
                        ClaimChoiceResultPayload.Result.NO_SPACE));
        assertEquals("punch_a_tree", verdict.questId());
        assertEquals(1, verdict.rewardIndex());
        assertEquals(ClaimChoiceResultPayload.Result.NO_SPACE, verdict.result(),
                "the two refusals are different sentences on the card, so they must survive the wire");

        RewardToastPayload toast = roundTrip(RewardToastPayload.CODEC,
                new RewardToastPayload("pack.toast.vault", true, "The vault is open."));
        assertEquals("pack.toast.vault", toast.value());
        assertTrue(toast.translatable());
        assertEquals("The vault is open.", toast.fallback());
        assertEquals("The vault is open.", toast.message().getString(),
                "a reward-level message is the sentence the book shows while it is open");
    }

    @Test
    @DisplayName("the editor's three reset presses survive the wire: a quest, a chapter, everything")
    void resetPayloadsRoundTrip() {
        // The quest press names one quest by id or alias, like the claim it sits beside: a codec
        // that dropped the id would clear nothing from a menu that promised one quest.
        ResetQuestPayload quest = roundTrip(ResetQuestPayload.CODEC,
                new ResetQuestPayload("punch_a_tree"));
        assertEquals("punch_a_tree", quest.questId());
        assertEquals("", new ResetQuestPayload(null).questId(),
                "a missing id is blank rather than null, which matches no quest and clears nothing");

        // The chapter press carries the scope and nothing else: a codec that dropped the id would
        // clear the whole book from a banner, which is the widest mistake this message can make.
        ResetChapterPayload chapter = roundTrip(ResetChapterPayload.CODEC,
                new ResetChapterPayload("stone_age"));
        assertEquals("stone_age", chapter.chapterId(), "the chapter id is the scope of the message");
        assertEquals("", new ResetChapterPayload(null).chapterId(),
                "and a missing id is blank rather than null, which matches no chapter");

        // The whole-book press carries nothing at all: there is no field a codec could drop, so
        // this pins that an empty message encodes to no bytes and still decodes to itself rather
        // than to null. Written out rather than through `roundTrip`, because that helper asserts
        // something was written — which is exactly what this message must not do.
        FriendlyByteBuf plain = new FriendlyByteBuf(Unpooled.buffer());
        RegistryFriendlyByteBuf empty =
                RegistryFriendlyByteBuf.decorator(RegistryAccess.EMPTY).apply(plain);
        ResetAllPayload.CODEC.encode(empty, new ResetAllPayload());
        assertEquals(0, empty.writerIndex(), "the whole-book press carries no bytes at all");
        assertEquals(new ResetAllPayload(), ResetAllPayload.CODEC.decode(empty),
                "and no bytes still decode to the press rather than to null");
    }

    @Test
    @DisplayName("the editor's two complete presses survive the wire: one quest, one quest with its chain")
    void completePayloadsRoundTrip() {
        // The press names one quest by id or alias and whether its chain comes with it: a codec
        // that dropped the id would complete nothing from a menu that promised one quest, and a
        // codec that dropped the flag would silently complete — or silently not — a whole chain.
        CompleteQuestPayload single = roundTrip(CompleteQuestPayload.CODEC,
                new CompleteQuestPayload("punch_a_tree", false));
        assertEquals("punch_a_tree", single.questId());
        assertFalse(single.withDependencies());

        CompleteQuestPayload chain = roundTrip(CompleteQuestPayload.CODEC,
                new CompleteQuestPayload("punch_a_tree", true));
        assertEquals("punch_a_tree", chain.questId());
        assertTrue(chain.withDependencies(), "the flag is the whole difference between the two rows");

        CompleteQuestPayload blank = new CompleteQuestPayload(null, false);
        assertEquals("", blank.questId(),
                "a missing id is blank rather than null, which matches no quest and completes nothing");
    }

    private static ArmatureNetwork.Direction directionOf(String id) {
        return ArmatureNetwork.registrations().stream()
                .filter(registration -> registration.type().id().toString().equals(id))
                .findFirst()
                .orElseThrow(() -> new AssertionError("no payload named " + id))
                .direction();
    }

    // ------------------------------------------------------------------
    // Round trips
    // ------------------------------------------------------------------

    @Test
    @DisplayName("one chunk of the quest tree survives a round trip, byte array and chunk header both")
    void questSyncRoundTrip() {
        // Non-ASCII and a newline in the tree, because the tree is JSON and JSON holds both -- and
        // because a byte-array codec that truncated or mis-sized would still pass on "{}".
        byte[] tree = "{\"quests\":[{\"title\":\"Punch a Tree \u2014 it's fine\"}]}".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        // A middle chunk of three, so the header's fields are all non-default and a codec that put
        // them in the wrong order cannot pass by having written zeroes.
        QuestSyncPayload original = new QuestSyncPayload(7, 2, "amethyst",
                new SyncChunk(4096, 1, 3, true), tree);

        QuestSyncPayload decoded = roundTrip(QuestSyncPayload.CODEC, original);

        assertEquals(7, decoded.questCount());
        assertEquals(2, decoded.chapterCount());
        assertEquals("amethyst", decoded.packTheme(),
                "the pack's own theme rides on every chunk, not only the first -- a codec that sent it"
                        + " once would leave a client with no theme when the first chunk was lost and"
                        + " re-requested");
        assertTrue(decoded.hasTheme());
        assertEquals(tree.length, decoded.data().length, "the byte array changed length");
        assertArrayEquals(tree, decoded.data());

        // The chunk header, asserted field by field. It is four components folded into one and the
        // reassembler keys on all of them, so a transposed pair here would assemble chunks in the
        // wrong order or attach them to the wrong transfer -- and the symptom is a JSON parse error
        // at the far end, naming nothing about the codec.
        assertEquals(4096, decoded.chunk().transferId());
        assertEquals(1, decoded.chunk().index());
        assertEquals(3, decoded.chunk().count());
        assertTrue(decoded.chunk().full());
        assertFalse(decoded.chunk().whole(), "three chunks is not a whole message");
    }

    @Test
    @DisplayName("an empty tree round-trips as an empty tree, not as null")
    void questSyncEmptyTree() {
        // The "no quests loaded" case, which is what a fresh install sends. A codec that returned
        // null here would throw on the client at the exact moment a new player opens the book.
        QuestSyncPayload decoded = roundTrip(QuestSyncPayload.CODEC,
                new QuestSyncPayload(0, 0, "", new SyncChunk(1, 0, 1, true), new byte[0]));

        assertEquals(0, decoded.questCount());
        assertEquals(0, decoded.data().length);
        assertTrue(decoded.chunk().whole(), "an empty message is still one chunk, not zero");

        // And "no theme" survives as "no theme" rather than arriving as something that looks like one.
        // The record's own constructor normalises absent to the empty string, so `hasTheme` is the one
        // question a reader has to ask; a codec that wrote a null as the four characters "null" would
        // make a client try to resolve a theme by that name.
        assertEquals("", decoded.packTheme());
        assertFalse(decoded.hasTheme());
    }

    @Test
    @DisplayName("a pack theme travels as a plain string, and an absent one is not the word null")
    void packThemeRoundTripsBothWays() {
        // Worth its own test because the two states are one character apart on the wire and behave
        // very differently: an absent theme means "this pack expresses no preference", and a theme
        // named `null` would mean "look this up and, failing, warn about it once per chapter".
        QuestSyncPayload absent = roundTrip(QuestSyncPayload.CODEC,
                new QuestSyncPayload(1, 1, null, new SyncChunk(1, 0, 1, true), new byte[]{1}));
        assertFalse(absent.hasTheme(), "a null theme should arrive as absent, not as a name");

        // And a name is not lowercased or otherwise touched on the way through: a stream codec that
        // round-tripped through a normalising step would make a file's `high_contrast` arrive as
        // something a lookup could not resolve.
        QuestSyncPayload named = roundTrip(QuestSyncPayload.CODEC,
                new QuestSyncPayload(1, 1, "high_contrast", new SyncChunk(1, 0, 1, true), new byte[]{1}));
        assertEquals("high_contrast", named.packTheme());
    }

    @Test
    @DisplayName("progress survives a round trip, including a UUID's sign bits")
    void progressSyncRoundTrip() {
        // The all-ones UUID is the case a hand-rolled two-long split gets wrong: as signed values
        // both halves are -1, and anything that treats them as positive loses the team id. The
        // real codec uses writeLong, so it is fine -- and this is the test that says so.
        UUID awkward = new UUID(-1L, -1L);
        byte[] data = "{\"states\":{\"a\":\"COMPLETED\"}}".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        ProgressSyncPayload original = new ProgressSyncPayload(awkward, 123_456L,
                new SyncChunk(-7, 2, 5, false), data, ProgressSyncPayload.REASON_CHANGED);

        ProgressSyncPayload decoded = roundTrip(ProgressSyncPayload.CODEC, original);

        assertEquals(awkward, decoded.teamId(), "the UUID's bits did not survive");
        assertEquals(123_456L, decoded.gameTime());
        assertEquals(ProgressSyncPayload.REASON_CHANGED, decoded.reason());
        assertArrayEquals(data, decoded.data());

        // The chunk, and the `full` flag specifically. It is the field the client's refusal hangs on:
        // a delta wrongly marked full is applied onto nothing, and a full wrongly marked delta is
        // refused, leaving a player with a blank book that never fills.
        assertEquals(-7, decoded.chunk().transferId(), "a negative transfer id must survive, since the counter wraps");
        assertEquals(2, decoded.chunk().index());
        assertEquals(5, decoded.chunk().count());
        assertFalse(decoded.chunk().full(), "a delta must not arrive claiming to be a full sync");
    }

    @Test
    @DisplayName("a normal UUID round-trips too, so the awkward case is not the only one tested")
    void progressSyncNormalUuid() {
        UUID team = UUID.fromString("3f2a1b4c-5d6e-7f80-9a0b-1c2d3e4f5061");
        ProgressSyncPayload decoded = roundTrip(ProgressSyncPayload.CODEC,
                new ProgressSyncPayload(team, 0L, new SyncChunk(1, 0, 1, true), new byte[]{1, 2, 3},
                        ProgressSyncPayload.REASON_JOIN));

        assertEquals(team, decoded.teamId());
        assertEquals(0L, decoded.gameTime(), "zero is a valid game time on the first tick");
        assertEquals(3, decoded.data().length);
        assertTrue(decoded.chunk().full(), "a join's progress is a full sync, and the client's accept path branches on it");
    }

    @Test
    @DisplayName("a task submission survives a round trip, and a long id is refused rather than truncated")
    void submitTaskRoundTrip() {
        SubmitTaskPayload decoded = roundTrip(SubmitTaskPayload.CODEC,
                new SubmitTaskPayload("punch_a_tree", 3));

        assertEquals("punch_a_tree", decoded.questId());
        assertEquals(3, decoded.taskIndex());
    }

    @Test
    @DisplayName("a canvas press survives a round trip carrying identity and nothing else")
    void clickPayloadRoundTrip() {
        // The chapter and the element, and deliberately nothing else: a payload that could carry a
        // command would be a payload a modified client could point at anything. The server reads
        // what the press does from its own index, so these two ids are the whole of what a press is.
        ClickPayload decoded = roundTrip(ClickPayload.CODEC,
                new ClickPayload("first_steps", "logo"));

        assertEquals("first_steps", decoded.chapterId());
        assertEquals("logo", decoded.elementId());
    }

    @Test
    @DisplayName("a canvas press with an overlong id is refused at the codec, not looked up")
    void overlyLongClickIdIsRejected() {
        // The client sends both fields, so both are untrusted input. A bounded string codec is what
        // stops a client sending a megabyte of chapter and the server trying to resolve it.
        String huge = "a".repeat(200);
        FriendlyByteBuf plain = new FriendlyByteBuf(Unpooled.buffer());
        RegistryFriendlyByteBuf buffer = RegistryFriendlyByteBuf.decorator(RegistryAccess.EMPTY).apply(plain);

        boolean rejected = false;
        try {
            ClickPayload.CODEC.encode(buffer, new ClickPayload(huge, "logo"));
        }
        catch (RuntimeException e) {
            rejected = true;
        }
        assertTrue(rejected, "the codec accepted a 200-character chapter id");
    }

    @Test
    @DisplayName("a claims request survives a round trip and carries a quest id and nothing else")
    void claimRewardRoundTrip() {
        // The id and nothing else, which is the security property rather than an accident: a payload
        // that could name an item and a count would be a payload a modified client could point at a
        // diamond. Here it can only ever say "I would like quest X", and the server decides.
        ClaimRewardPayload decoded = roundTrip(ClaimRewardPayload.CODEC,
                new ClaimRewardPayload("punch_a_tree"));

        assertEquals("punch_a_tree", decoded.questId());
    }

    @Test
    @DisplayName("a player's stage list survives a round trip, ids and order")
    void stageSyncRoundTrip() {
        StageSyncPayload decoded = roundTrip(StageSyncPayload.CODEC, new StageSyncPayload(
                List.of("my_pack:left_the_village", "my_pack:met_the_council")));

        assertEquals(List.of("my_pack:left_the_village", "my_pack:met_the_council"), decoded.stages(),
                "a codec that dropped or reordered these would leave a client script reading the wrong flags");
    }

    @Test
    @DisplayName("the server's lists survive a round trip, ids and order, tags included")
    void serverListsRoundTrip() {
        // The ids a modpack has: three vanilla dimensions, a modded one and a datapack one -- and the
        // structures, where the tag spelling is half of what a `structure` field takes. A codec that
        // dropped or reordered them would leave the picker offering a different world, or a different set
        // of structures, than the server has.
        ServerListsPayload decoded = roundTrip(ServerListsPayload.CODEC, new ServerListsPayload(
                List.of("minecraft:overworld", "minecraft:the_nether", "minecraft:the_end",
                        "twilightforest:twilight_forest", "example:the_deep"),
                List.of("#minecraft:village", "minecraft:stronghold", "minecraft:village_plains",
                        "terralith:underground_city")));

        assertEquals(List.of("minecraft:overworld", "minecraft:the_nether", "minecraft:the_end",
                "twilightforest:twilight_forest", "example:the_deep"), decoded.dimensions());
        assertEquals(List.of("#minecraft:village", "minecraft:stronghold", "minecraft:village_plains",
                "terralith:underground_city"), decoded.structures());
    }

    @Test
    @DisplayName("a locale travels with both ids, its bytes and its place in the message")
    void localeSyncRoundTrip() {
        // Both ids, because they answer different questions and the client compares the first one: an
        // `es_mx` player served the pack's `es_es` file has to settle on `es_mx`, or it would ask for a
        // locale the pack was never going to have, forever. See LocaleSyncPayload.
        LocaleSyncPayload decoded = roundTrip(LocaleSyncPayload.CODEC, new LocaleSyncPayload(
                "es_mx", "es_es", new SyncChunk(4, 1, 3, true), new byte[]{9, 8, 7}));

        assertEquals("es_mx", decoded.locale());
        assertEquals("es_es", decoded.served());
        assertEquals(4, decoded.chunk().transferId());
        assertEquals(1, decoded.chunk().index());
        assertEquals(3, decoded.chunk().count());
        assertTrue(decoded.chunk().full());
        assertArrayEquals(new byte[]{9, 8, 7}, decoded.data());
    }

    @Test
    @DisplayName("a locale with nothing to serve travels as empty, not as the word null")
    void localeSyncNullsBecomeEmpty() {
        // The two states are one character apart on the wire and behave very differently: an empty
        // locale is "this pack has nothing for you", and a locale named `null` is a key that would
        // never match and a client that would keep asking. The same normalisation QuestSyncPayload
        // makes for its theme, for the same reason.
        LocaleSyncPayload decoded = roundTrip(LocaleSyncPayload.CODEC,
                new LocaleSyncPayload(null, null, new SyncChunk(1, 0, 1, true), new byte[]{1}));

        assertEquals("", decoded.locale());
        assertEquals("", decoded.served());
    }

    @Test
    @DisplayName("a client's language request survives a round trip")
    void localeRequestRoundTrip() {
        LocaleRequestPayload decoded = roundTrip(LocaleRequestPayload.CODEC,
                new LocaleRequestPayload("pt_br"));

        assertEquals("pt_br", decoded.locale(),
                "a codec that lowercased or trimmed this would ask for a locale the files are not keyed by");
        // And absent is empty rather than null, so the handler has one spelling of "no answer" to read.
        assertEquals("", roundTrip(LocaleRequestPayload.CODEC, new LocaleRequestPayload(null)).locale());
    }

    @Test
    @DisplayName("the claim-all press carries its filter and no quest list")
    void claimAllRoundTrip() {
        // It used to carry nothing at all, and the buffer staying empty was the property the wire
        // trusted. Now the panel's active chip rides with the press, because the footer button follows
        // the filter and the server has to know which one it was following. One small int -- and still
        // no quest ids: which quests are owed remains the server's to decide.
        FriendlyByteBuf plain = new FriendlyByteBuf(Unpooled.buffer());
        RegistryFriendlyByteBuf buffer = RegistryFriendlyByteBuf.decorator(RegistryAccess.EMPTY).apply(plain);

        ClaimAllPayload.CODEC.encode(buffer, new ClaimAllPayload(
                dev.ellipog.tenet.progress.ClaimFilter.CHOICES));
        assertEquals(1, buffer.writerIndex(), "one small int for the filter, and no quest list");

        assertEquals(dev.ellipog.tenet.progress.ClaimFilter.CHOICES,
                ClaimAllPayload.CODEC.decode(buffer).filter());
    }

    @Test
    @DisplayName("an open-book request survives the wire, quest id and all")
    void openBookRoundTrip() {
        // One string, both spellings: a named quest opens on it, and empty opens the book as it
        // was. A codec that dropped the id would open the book plainly for a command that named
        // a quest, and one that invented an id would focus a quest nobody asked for.
        OpenBookPayload named = roundTrip(OpenBookPayload.CODEC, new OpenBookPayload("punch_a_tree"));
        assertEquals("punch_a_tree", named.questId());

        OpenBookPayload plain = roundTrip(OpenBookPayload.CODEC, new OpenBookPayload(""));
        assertEquals("", plain.questId(), "empty is the plain opening, not a quest");
    }

    @Test
    @DisplayName("an intro ask survives the wire carrying nothing at all")
    void introRoundTrip() {
        // Empty by design: the ask resets the client's seen flag, and which rung to start on
        // is the client's to work out from the book it holds. A codec that wrote anything would
        // be a server deciding what the author's own book still needs. The shared round-trip
        // helper asserts something was written, so this spells the empty trip out directly.
        FriendlyByteBuf plain = new FriendlyByteBuf(Unpooled.buffer());
        RegistryFriendlyByteBuf buffer = RegistryFriendlyByteBuf.decorator(RegistryAccess.EMPTY).apply(plain);

        IntroPayload.CODEC.encode(buffer, new IntroPayload());
        assertEquals(0, buffer.writerIndex(), "the ask is the message, and there is nothing else on the wire");
        assertEquals(new IntroPayload(), IntroPayload.CODEC.decode(buffer));
    }

    @Test
    @DisplayName("the vitals switch is one boolean, both ways")
    void vitalsRoundTrip() {        // One byte, and it has to carry both answers: "off" is a value, not the absence of a message — a
        // client that is not told anything keeps whatever it had, so an operator turning the overlay off
        // must be told exactly as clearly as one turning it on.
        for (boolean on : new boolean[] {true, false}) {
            FriendlyByteBuf plain = new FriendlyByteBuf(Unpooled.buffer());
            RegistryFriendlyByteBuf buffer =
                    RegistryFriendlyByteBuf.decorator(RegistryAccess.EMPTY).apply(plain);

            VitalsPayload.CODEC.encode(buffer, new VitalsPayload(on));
            assertEquals(1, buffer.writerIndex(), "one boolean, and nothing else on the wire");
            assertEquals(on, VitalsPayload.CODEC.decode(buffer).on());
        }
    }

    @Test
    @DisplayName("a quest id longer than any real one is rejected at the codec, not accepted silently")
    void overlyLongQuestIdIsRejected() {
        // The client sends this field, so it is untrusted input. A bounded string codec is what stops
        // a client sending a megabyte of id and the server trying to look it up.
        String huge = "a".repeat(200);
        FriendlyByteBuf plain = new FriendlyByteBuf(Unpooled.buffer());
        RegistryFriendlyByteBuf buffer = RegistryFriendlyByteBuf.decorator(RegistryAccess.EMPTY).apply(plain);

        SubmitTaskPayload payload = new SubmitTaskPayload(huge, 0);

        boolean rejected = false;
        try {
            SubmitTaskPayload.CODEC.encode(buffer, payload);
        }
        catch (RuntimeException e) {
            rejected = true;
        }
        assertTrue(rejected, "the codec accepted a 200-character quest id");
    }

    // ------------------------------------------------------------------
    // The decoder must not invent data
    // ------------------------------------------------------------------

    @Test
    @DisplayName("decoding from an untouched buffer fails rather than producing a zeroed payload")
    void decodingAnEmptyBufferFails() {
        // A real hazard: a mis-sized packet leaves the decoder reading past what was written. It
        // must throw, because the alternative is a payload of zeroes that looks like a legitimate
        // "0 quests, empty tree" and quietly clears the client's cache.
        FriendlyByteBuf plain = new FriendlyByteBuf(Unpooled.buffer());
        RegistryFriendlyByteBuf buffer = RegistryFriendlyByteBuf.decorator(RegistryAccess.EMPTY).apply(plain);

        boolean threw = false;
        try {
            QuestSyncPayload.CODEC.decode(buffer);
        }
        catch (RuntimeException e) {
            threw = true;
        }
        assertTrue(threw, "decoding an empty buffer produced a payload instead of failing");
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    /**
     * Encodes and decodes through a real Minecraft buffer.
     *
     * <p>{@code RegistryFriendlyByteBuf.decorator} is how a plain netty buffer becomes one the codecs
     * accept. {@code RegistryAccess.EMPTY} is sufficient because none of these codecs resolve a
     * registry — they are all {@code ByteBufCodecs} primitives.
     */
    private static <T extends CustomPacketPayload> T roundTrip(
            StreamCodec<? super RegistryFriendlyByteBuf, T> codec, T payload) {
        FriendlyByteBuf plain = new FriendlyByteBuf(Unpooled.buffer());
        RegistryFriendlyByteBuf buffer = RegistryFriendlyByteBuf.decorator(RegistryAccess.EMPTY).apply(plain);

        codec.encode(buffer, payload);

        // A fresh buffer reads from index 0, so the same buffer decodes back. Asserted rather than
        // assumed, because a codec that consumed as it encoded would silently decode zeroes here.
        assertEquals(0, buffer.readerIndex(), "encoding moved the read index, so this is not a round trip");

        int written = buffer.writerIndex();
        T decoded = codec.decode(buffer);
        assertTrue(written > 0, "nothing was written");
        return decoded;
    }

    private static void assertArrayEquals(byte[] expected, byte[] actual) {
        org.junit.jupiter.api.Assertions.assertArrayEquals(expected, actual);
    }
}
