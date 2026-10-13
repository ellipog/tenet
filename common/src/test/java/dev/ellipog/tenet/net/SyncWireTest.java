package dev.ellipog.tenet.net;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The wire itself: compression, chunking, and putting chunks back together.
 *
 * <h2>What this file is defending against, in three parts</h2>
 *
 * <p><b>A decompression bomb.</b> Deflate's output size is not bounded by its input size — a few
 * kilobytes can expand to gigabytes — and the bytes arrive from a server. That server is not a
 * stranger, which is not the same as being safe: a broken or malicious pack would be able to exhaust a
 * client's memory, and the client has no way to refuse after the allocation has happened. So
 * {@link SyncWire#inflate} enforces a ceiling <i>during</i> inflation, and
 * {@link #inflateRefusesABombRatherThanAllocatingIt()} is the assertion that it does.
 *
 * <p><b>A message too large for one packet.</b> {@code ClientboundCustomPayloadPacket} carries
 * {@code MAX_PAYLOAD_SIZE = 1048576} and uses it only as the fallback codec for an <i>unknown</i>
 * payload — so a registered payload is not held to it by anything, and exceeding it produces no error
 * naming a size. Chunking is the answer, and the tests below are that a message survives being cut up.
 *
 * <p><b>A half-assembled message.</b> A transfer whose own chunks disagree about how many there are
 * is dropped whole, because the alternative is JSON that parses in the middle and throws at the end —
 * a failure that reads as a serialiser bug rather than as a truncated packet.
 *
 * <p>Nothing here needs Minecraft. That is the point of {@link SyncWire} being a class of its own
 * rather than four methods inside the payload handlers.
 */
@DisplayName("SyncWire")
class SyncWireTest {

    /** JSON of the shape a quest tree has: repetitive keys, which is what makes deflate pay. */
    private static byte[] questishJson(int quests) {
        StringBuilder json = new StringBuilder("{\"version\":1,\"quests\":[");
        for (int i = 0; i < quests; i++) {
            if (i > 0) {
                json.append(',');
            }
            json.append("{\"chapterId\":\"chapter_one\",\"chapterTitle\":\"Getting Started\",");
            json.append("\"id\":\"quest_").append(i).append("\",\"title\":\"A Quest\",");
            json.append("\"icon\":\"minecraft:oak_log\",\"x\":").append(i * 32).append(",\"y\":64,");
            json.append("\"size\":48,\"shape\":\"rounded\",\"description\":[],\"dependsOn\":[],");
            json.append("\"tasks\":[],\"rewards\":[]}");
        }
        return json.append("]}").toString().getBytes(StandardCharsets.UTF_8);
    }

    // ------------------------------------------------------------------
    // Compression
    // ------------------------------------------------------------------

    @Test
    @DisplayName("a quest-shaped tree compresses, and comes back byte for byte")
    void packAndUnpackRoundTrip() {
        byte[] raw = questishJson(200);
        byte[] packed = SyncWire.pack(raw);

        // The whole reason compression is here rather than at a later stage. A few per cent would not
        // be worth a header byte; this asserts the order of magnitude, so a change that accidentally
        // disabled deflate -- or shipped with the flag inverted -- fails here rather than in a packet
        // capture nobody takes.
        assertTrue(packed.length * 4 < raw.length,
                "quest JSON compressed from " + raw.length + " to only " + packed.length
                        + " bytes, which is not the ratio this payload shape is supposed to get. "
                        + "Either deflate is not running or the flag byte is being written wrong.");

        assertArrayEquals(raw, SyncWire.unpack(packed));
    }

    @Test
    @DisplayName("a message too small to benefit is sent raw rather than paying for a header")
    void aTinyMessageIsNotCompressed() {
        // A derived delta on one quest is a couple of hundred bytes, and deflate's own framing can
        // make that bigger. The branch that sends it raw is the one that would otherwise never run,
        // which is exactly the sort of branch that is broken when it is finally needed.
        byte[] raw = "{\"quests\":{}}".getBytes(StandardCharsets.UTF_8);
        byte[] packed = SyncWire.pack(raw);

        assertEquals(raw.length + 1, packed.length,
                "a message this small should have gone raw -- one flag byte and no more");
        assertArrayEquals(raw, SyncWire.unpack(packed), "and it must still come back exactly");
    }

    @Test
    @DisplayName("an empty message survives, since an empty questline still sends a tree")
    void anEmptyMessageRoundTrips() {
        // Not hypothetical: a server with no quest files sends an empty tree so the client can tell
        // "nothing loaded" from "nothing received". A codec that turned empty into null would throw
        // at exactly the moment a new player opens the book.
        byte[] packed = SyncWire.pack(new byte[0]);
        assertEquals(0, SyncWire.unpack(packed).length);
    }

    @Test
    @DisplayName("inflate refuses a bomb rather than allocating it")
    void inflateRefusesABombRatherThanAllocatingIt() {
        // 400 KB of one repeated character compresses to a few hundred bytes -- a ratio of about a
        // thousand to one, which is what makes this shape dangerous. The ceiling is what stops it.
        byte[] bomb = new byte[400_000];
        java.util.Arrays.fill(bomb, (byte) 'a');

        byte[] deflated = SyncWire.deflate(bomb);
        assertTrue(deflated.length < 2_000,
                "fixture sanity: the bomb should have compressed to almost nothing, and it is "
                        + deflated.length + " bytes");

        SyncWire.MalformedSync thrown = assertThrows(SyncWire.MalformedSync.class,
                () -> SyncWire.inflate(deflated, 1_000),
                "inflating past the ceiling must throw rather than produce the bytes");

        // The message has to say what happened, because this arrives at a client as a log line and
        // nothing else. "Malformed" without the size sends someone looking at the packet.
        assertTrue(thrown.getMessage().contains("1000"),
                "the refusal should name the ceiling it refused: " + thrown.getMessage());
    }

    @Test
    @DisplayName("a truncated stream is an error, not a hang and not half a message")
    void inflateRefusesATruncatedStream() {
        // The failure mode this guards: Inflater.inflate returns 0 when it wants more input, and the
        // whole stream was handed over before the loop began -- so retrying would spin forever. A
        // hang inside a payload handler takes the client with it, and has no log line.
        byte[] deflated = SyncWire.deflate(questishJson(50));
        byte[] truncated = java.util.Arrays.copyOf(deflated, deflated.length - 12);

        SyncWire.MalformedSync thrown = assertThrows(SyncWire.MalformedSync.class,
                () -> SyncWire.inflate(truncated, SyncWire.MAX_INFLATED_BYTES));

        assertTrue(thrown.getMessage().toLowerCase().contains("truncated"),
                "a short stream should be named as truncated: " + thrown.getMessage());
    }

    @Test
    @DisplayName("bytes that are not deflate data are refused by name")
    void inflateRefusesNotDeflateData() {
        SyncWire.MalformedSync thrown = assertThrows(SyncWire.MalformedSync.class,
                () -> SyncWire.inflate("this is plain text, not a deflate stream".getBytes(StandardCharsets.UTF_8),
                        SyncWire.MAX_INFLATED_BYTES));

        assertTrue(thrown.getMessage().contains("deflate"), thrown.getMessage());
    }

    @Test
    @DisplayName("an unknown flag byte is refused, since guessing at a body is guessing at a format")
    void unpackRefusesAnUnknownFlag() {
        SyncWire.MalformedSync thrown = assertThrows(SyncWire.MalformedSync.class,
                () -> SyncWire.unpack(new byte[]{9, 1, 2, 3}));

        assertTrue(thrown.getMessage().contains("9"), thrown.getMessage());

        // And an empty message has not even got a flag, which is a different fault from a wrong one.
        assertThrows(SyncWire.MalformedSync.class, () -> SyncWire.unpack(new byte[0]));
    }

    // ------------------------------------------------------------------
    // Chunking
    // ------------------------------------------------------------------

    @Test
    @DisplayName("an empty message is one chunk, not zero")
    void anEmptyMessageIsStillOneChunk() {
        // Zero chunks is a transfer that never completes, and the client would wait for a message
        // that is never coming. One chunk of nothing completes immediately and is unambiguous.
        List<byte[]> chunks = SyncWire.chunk(new byte[0]);
        assertEquals(1, chunks.size());
        assertEquals(0, chunks.get(0).length);
    }

    @Test
    @DisplayName("the boundaries are where they say they are")
    void chunkBoundaries() {
        // A message exactly one chunk long is the case an off-by-one in the loop gets wrong, and the
        // symptom is a second empty chunk that the receiver counts toward the transfer.
        assertEquals(1, SyncWire.chunk(new byte[SyncWire.CHUNK_BYTES]).size());
        assertEquals(2, SyncWire.chunk(new byte[SyncWire.CHUNK_BYTES + 1]).size());
        assertEquals(3, SyncWire.chunk(new byte[SyncWire.CHUNK_BYTES * 2 + 1]).size());
    }

    @Test
    @DisplayName("the chunks concatenate back to the message")
    void chunksConcatenate() {
        // Distinct bytes rather than zeroes, so a chunk placed in the wrong order produces a
        // difference rather than a coincidence.
        byte[] message = new byte[SyncWire.CHUNK_BYTES * 2 + 500];
        for (int i = 0; i < message.length; i++) {
            message[i] = (byte) (i * 31);
        }

        List<byte[]> chunks = SyncWire.chunk(message);
        java.io.ByteArrayOutputStream joined = new java.io.ByteArrayOutputStream();
        for (byte[] chunk : chunks) {
            joined.writeBytes(chunk);
        }

        assertArrayEquals(message, joined.toByteArray());
    }

    @Test
    @DisplayName("a message needing more chunks than the receiver will assemble is refused at the sender")
    void tooManyChunksIsRefused() {
        // Refused here rather than accepted and then quietly dropped at the far end, where the
        // symptom is a client that never finishes receiving and nothing anywhere saying why.
        long tooBig = (long) SyncWire.CHUNK_BYTES * SyncWire.MAX_CHUNKS + 1;

        IllegalArgumentException thrown = assertThrows(IllegalArgumentException.class,
                () -> SyncWire.chunk(new byte[(int) tooBig]));

        assertTrue(thrown.getMessage().contains(String.valueOf(SyncWire.MAX_CHUNKS)),
                "the refusal should name the chunk cap: " + thrown.getMessage());
    }

    // ------------------------------------------------------------------
    // Reassembly
    // ------------------------------------------------------------------

    @Test
    @DisplayName("chunks in order complete, and the result is the message")
    void reassemblerInOrder() {
        SyncWire.Reassembler reassembler = new SyncWire.Reassembler();
        byte[] message = new byte[SyncWire.CHUNK_BYTES + 10];
        for (int i = 0; i < message.length; i++) {
            message[i] = (byte) i;
        }
        List<byte[]> chunks = SyncWire.chunk(message);

        assertNull(reassembler.accept(new SyncChunk(1, 0, 2, true), chunks.get(0)));
        byte[] whole = reassembler.accept(new SyncChunk(1, 1, 2, true), chunks.get(1));

        assertNotNull(whole, "the second of two chunks should have completed the message");
        assertArrayEquals(message, whole);
        assertEquals(1, reassembler.completed());
        assertEquals(0, reassembler.pendingTransfers(), "a completed transfer must not be kept");
    }

    @Test
    @DisplayName("chunks out of order complete, because a transport may reorder them")
    void reassemblerOutOfOrder() {
        SyncWire.Reassembler reassembler = new SyncWire.Reassembler();
        byte[] message = questishJson(20);
        List<byte[]> chunks = SyncWire.chunk(message);

        // Deliberately backwards as well as shuffled: index order is the thing being tested, so
        // sending them in a fixed but non-sequential order is stronger than randomising and hoping.
        List<Integer> order = new ArrayList<>();
        for (int i = chunks.size() - 1; i >= 0; i--) {
            order.add(i);
        }

        byte[] whole = null;
        for (int index : order) {
            byte[] result = reassembler.accept(new SyncChunk(99, index, chunks.size(), true), chunks.get(index));
            if (result != null) {
                whole = result;
            }
        }

        assertNotNull(whole);
        assertArrayEquals(message, whole);
    }

    @Test
    @DisplayName("a resent chunk replaces rather than counting twice")
    void aDuplicateChunkDoesNotCompleteEarly() {
        // A reliable transport may resend, and that is normal rather than exceptional. Counting a
        // duplicate toward the total would complete the message while a hole was still in it -- and
        // the result concatenates to a JSON document with a chunk missing, which throws somewhere
        // unrelated.
        SyncWire.Reassembler reassembler = new SyncWire.Reassembler();
        // 200 quests, not 30. The first version used 30 and its own sanity check caught the problem:
        // this test chunks the *raw* JSON rather than the packed bytes, and 30 quests of JSON is about
        // 6 KB -- comfortably inside one 24 KB chunk. So the whole test was running the single-chunk
        // path and asserting nothing about duplicates at all, while passing.
        byte[] message = questishJson(200);
        List<byte[]> chunks = SyncWire.chunk(message);
        assertTrue(chunks.size() >= 2,
                "fixture sanity: this message should need more than one chunk, and it produced "
                        + chunks.size() + " from " + message.length + " bytes");

        assertNull(reassembler.accept(new SyncChunk(7, 0, chunks.size(), true), chunks.get(0)));
        assertNull(reassembler.accept(new SyncChunk(7, 0, chunks.size(), true), chunks.get(0)),
                "a resend of the first chunk must not advance the count");

        byte[] whole = null;
        for (int i = 1; i < chunks.size(); i++) {
            byte[] result = reassembler.accept(new SyncChunk(7, i, chunks.size(), true), chunks.get(i));
            if (result != null) {
                whole = result;
            }
        }

        assertNotNull(whole, "the message should complete once every distinct index has arrived");
        assertArrayEquals(message, whole);
    }

    @Test
    @DisplayName("chunks that disagree about how many there are drop the transfer rather than half-build it")
    void mismatchedCountDropsTheTransfer() {
        SyncWire.Reassembler reassembler = new SyncWire.Reassembler();

        assertNull(reassembler.accept(new SyncChunk(5, 0, 2, true), new byte[]{1}));
        assertNull(reassembler.accept(new SyncChunk(5, 1, 3, true), new byte[]{2}),
                "a different chunk count under the same transfer id must not be added to the first");
        assertEquals(1, reassembler.droppedMismatched());

        // The count-2 partial is discarded, and the count-3 chunk starts a fresh transfer. My first
        // version asserted `pendingTransfers() == 0`, reading the class note's "dropped whole" as
        // "and nothing replaces it" -- which is not what the code does and not what it should do. A
        // chunk that arrived intact is worth keeping; what must never happen is the two counts'
        // chunks being concatenated, and the assertion that catches *that* is the byte comparison
        // below, not a count of pending transfers.
        assertEquals(1, reassembler.pendingTransfers(),
                "the mismatched chunk should have started a fresh transfer, not been discarded with "
                        + "the old one");

        // And the fresh transfer works, so the drop is a clean slate rather than a poisoned id.
        assertNull(reassembler.accept(new SyncChunk(5, 0, 2, true), new byte[]{3}));
        assertArrayEquals(new byte[]{3, 4}, reassembler.accept(new SyncChunk(5, 1, 2, true), new byte[]{4}));
    }

    @Test
    @DisplayName("chunks that disagree about the full flag are treated as corruption")
    void mismatchedFullFlagDropsTheTransfer() {
        // The flag belongs to the message and rides on every chunk, so a disagreement means one of
        // the two is wrong and there is no way to tell which. A delta applied as a full sync shows a
        // handful of quests and everything else locked, which looks like a working sync of a very
        // small pack.
        SyncWire.Reassembler reassembler = new SyncWire.Reassembler();

        assertNull(reassembler.accept(new SyncChunk(6, 0, 2, true), new byte[]{1}));
        assertNull(reassembler.accept(new SyncChunk(6, 1, 2, false), new byte[]{2}));

        assertEquals(1, reassembler.droppedMismatched());
        assertEquals(0, reassembler.pendingTransfers());
    }

    @Test
    @DisplayName("an unplaceable chunk is refused by name rather than waited on")
    void impossibleChunkHeadersAreRefused() {
        // A count of zero and an index past the count are not data-loss situations, they are the two
        // ends disagreeing about the format. Waiting for chunks that cannot exist would be a client
        // that never finishes receiving and never says so.
        SyncWire.Reassembler reassembler = new SyncWire.Reassembler();

        assertThrows(SyncWire.MalformedSync.class,
                () -> reassembler.accept(new SyncChunk(1, 0, 0, true), new byte[]{1}));
        assertThrows(SyncWire.MalformedSync.class,
                () -> reassembler.accept(new SyncChunk(1, 2, 2, true), new byte[]{1}));
        assertThrows(SyncWire.MalformedSync.class,
                () -> reassembler.accept(new SyncChunk(1, -1, 4, true), new byte[]{1}));
        assertThrows(SyncWire.MalformedSync.class,
                () -> reassembler.accept(new SyncChunk(1, 0, SyncWire.MAX_CHUNKS + 1, true), new byte[]{1}));
    }

    @Test
    @DisplayName("pending transfers are bounded, so a sender that never finishes cannot grow the map forever")
    void pendingTransfersAreBounded() {
        // The keys are chosen by the other end of the wire. A client that disconnects mid-message and
        // comes back, or a server with a bug, would otherwise add a pending transfer per attempt and
        // never free one -- growth that is invisible until memory runs out.
        SyncWire.Reassembler reassembler = new SyncWire.Reassembler();

        for (int i = 0; i < SyncWire.MAX_PENDING_TRANSFERS + 1; i++) {
            assertNull(reassembler.accept(new SyncChunk(i, 0, 2, true), new byte[]{(byte) i}));
        }

        assertEquals(SyncWire.MAX_PENDING_TRANSFERS, reassembler.pendingTransfers());
        assertEquals(1, reassembler.droppedEvicted(), "exactly one should have been evicted");
    }

    @Test
    @DisplayName("forgetting everything empties the pending set, for a disconnect mid-message")
    void forgetAllClearsPending() {
        SyncWire.Reassembler reassembler = new SyncWire.Reassembler();
        for (int i = 0; i < 3; i++) {
            assertNull(reassembler.accept(new SyncChunk(i, 0, 4, true), new byte[]{1}));
        }
        assertEquals(3, reassembler.pendingTransfers());

        reassembler.forgetAll();

        assertEquals(0, reassembler.pendingTransfers(),
                "a disconnect mid-transfer must not leave chunks waiting for a completion that the "
                        + "next connection's fresh transfer ids can never bring");
    }

    @Test
    @DisplayName("transfer ids are distinct, so two live messages cannot be confused for one")
    void transferIdsAreDistinct() {
        // The whole reason the id is on the wire. A constant would make two concurrent transfers
        // merge, and the symptom is a JSON document assembled from two different trees.
        int first = SyncWire.newTransferId();
        int second = SyncWire.newTransferId();
        assertFalse(first == second, "two transfers were given the same id");
    }

    // ------------------------------------------------------------------
    // The chapter replica's frame
    // ------------------------------------------------------------------

    @Test
    @DisplayName("a framed replica splits back into its two texts, byte for byte")
    void replicaFrameRoundTrips() {
        String quests = "{\"a\": 1, \"snowman\": \"☃\"}";
        String tree = "{\"title\": \"ü\"}";
        byte[] framed = ChapterReplicaChunk.frame(quests.getBytes(StandardCharsets.UTF_8),
                tree.getBytes(StandardCharsets.UTF_8));

        ChapterReplicaChunk.Framed back = ChapterReplicaChunk.split(framed)
                .orElseThrow(() -> new AssertionError("a frame this side built must split"));
        assertEquals(quests, back.quests());
        assertEquals(tree, back.chapterTree());
    }

    @Test
    @DisplayName("a corrupt frame is empty, not an exception and not half a chapter")
    void corruptFrameIsEmpty() {
        assertTrue(ChapterReplicaChunk.split(new byte[0]).isEmpty(), "no length prefix");
        assertTrue(ChapterReplicaChunk.split(new byte[] {0, 0}).isEmpty(), "a short prefix");
        assertTrue(ChapterReplicaChunk.split(new byte[] {0, 0, 0, 10, 1, 2}).isEmpty(),
                "a length longer than the frame");
        assertTrue(ChapterReplicaChunk.split(new byte[] {(byte) 0xFF, 0, 0, 0, 1, 2}).isEmpty(),
                "a negative length");
    }

    @Test
    @DisplayName("a replica survives pack, chunk, reassembly and unpack, like the wire does it")
    void replicaSurvivesTheWire() {
        // The overflow path end to end, headless: frame, deflate, cut, reassemble out of order,
        // inflate, split. What the server sends and what the client accepts, with no network.
        String quests = "{\"quests\": [" + "\"quest\", ".repeat(500) + "\"quest\"]}";
        String tree = "{\"title\": \"big\"}";
        byte[] framed = ChapterReplicaChunk.frame(quests.getBytes(StandardCharsets.UTF_8),
                tree.getBytes(StandardCharsets.UTF_8));
        List<byte[]> parts = SyncWire.chunk(SyncWire.pack(framed));

        SyncWire.Reassembler reassembler = new SyncWire.Reassembler();
        int transferId = SyncWire.newTransferId();
        byte[] whole = null;
        // Back to front, because the transport may reorder and the test should prove it survives.
        for (int i = parts.size() - 1; i >= 0; i--) {
            byte[] done = reassembler.accept(
                    new SyncChunk(transferId, i, parts.size(), true), parts.get(i));
            if (done != null) {
                assertNull(whole, "completed twice");
                whole = done;
            }
        }
        assertNotNull(whole, "all chunks offered and nothing completed");

        ChapterReplicaChunk.Framed back = ChapterReplicaChunk.split(SyncWire.unpack(whole))
                .orElseThrow(() -> new AssertionError("a packed frame must split"));
        assertEquals(quests, back.quests());
        assertEquals(tree, back.chapterTree());
    }
}
