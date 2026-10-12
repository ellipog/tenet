package dev.ellipog.tenet.editor;

import dev.ellipog.tenet.Constants;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * What one editing gesture costs on the server, by phase, once a second to the log.
 *
 * <h2>Why this exists, and what it is a falsifier for</h2>
 *
 * <p>{@code QuestLoader} already reports the <b>reload's</b> phase split — read+decode, assemble, index,
 * cycles, tables — and that is the tail of an edit. It is not the whole of one, and the part it cannot see
 * is the part that happens <b>per operation</b>: a field edit is applied, validated and written before any
 * reload is owed, and with a burst of them the per-op half is the whole story. Every number in
 * {@code report-editing-performance.md}'s latency table is arithmetic over measured file sizes precisely
 * because nothing counted this. So it is counted.
 *
 * <p>It is a <b>falsifier</b> in the same sense {@code QuestWalks} is: the claims it settles are of the
 * form "a burst's wall time is dominated by N fsyncs and N full-chapter validations", and the way to settle
 * one is to print the counts and the milliseconds.
 *
 * <h2>The phases, and why these five</h2>
 *
 * <p>They are the ones a decision can be made from, and they are separated because the remedies differ:
 *
 * <ul>
 *   <li>{@code ops} — how many reached the editor. The denominator for everything else, and the number that
 *       says whether a "burst" is fifty or five.</li>
 *   <li>{@code applyMs} — {@code EditorOps.apply}, which is the model change <b>and</b> the save. One number
 *       rather than two because the save is not separable from here: {@code apply} calls it, and a second
 *       timer around a call it makes internally would be a second description of the same interval. What the
 *       save costs <i>on its own</i> is the thing {@code applyMs} is read against {@code ops} for — a mean
 *       that climbs with the chapter is the validation cost, and a mean that stays flat while {@code writes}
 *       climbs is the disk.</li>
 *   <li>{@code writes} — files actually written, counted at the write seam. One for a field edit, two for a
 *       create (the quest and the manifest).</li>
 *   <li>{@code syncs} — atomic writes, and therefore fsyncs: {@code JsonWrite.atomically} opens with
 *       {@code StandardOpenOption.SYNC}, so the two are the same event by construction rather than by
 *       agreement. The name says {@code syncs} rather than {@code fsyncs} because the option is what is
 *       being counted; if it ever stops being SYNC, this number stops meaning fsync and this is where that
 *       is written down.</li>
 *   <li>{@code flush} — the coalesced half: the reload, the index rebuild, the cycle pass, the tree encode
 *       and the deflate. Reported once per flush rather than accumulated per second, because a flush is an
 *       event with an identity and an average of five of them describes none.</li>
 * </ul>
 *
 * <h2>Off, and what it costs when off</h2>
 *
 * <p>One branch per call, and the branch is on a static boolean. Every entry point below returns before it
 * reads a clock, looks anything up or allocates, so this can sit on the apply path without a second thought
 * about the instrument's own cost — the same contract {@code QuestWalks} keeps.
 *
 * <h2>The tally, and the thread it is written on</h2>
 *
 * <p>A plain map, because every caller is the server thread: the payload handler, the tick flush and the
 * save are all on it. There is no version of "the op was applied somewhere else" that would not also be a
 * bug worth a crash. The per-call allocation a {@code Map.merge} would make is avoided as well — the value
 * is a {@code long[]} so a call is a lookup and an add.
 */
public final class EditPhases {

    /**
     * How often the tally is reported, matching the frame counter's and the walk counter's own line.
     *
     * <p>A second rather than per-op, because a burst of fifty in one tick would otherwise be fifty lines
     * interleaved with the replies — and the question is "what did that burst cost", which is one answer
     * about fifty ops rather than fifty answers about one each.
     */
    private static final long REPORT_NANOS = 1_000_000_000L;

    /** The counter's own switch. Off unless somebody asks, because this is a diagnostic. */
    private static volatile boolean on;

    private static long lastReportNanos;

    /** Accumulated since the last report: ops, save millis, writes, syncs, apply millis. */
    private static final Map<String, long[]> TALLY = new LinkedHashMap<>();

    private EditPhases() {
    }

    /** Whether anything is being counted. */
    public static boolean on() {
        return on;
    }

    /**
     * Turns the counter on or off.
     *
     * <p>Not persisted and not a client setting: this is an operator's instrument for a question about a
     * server, and a switch that outlived the session would be a permanent cost for a temporary question.
     */
    public static void set(boolean value) {
        on = value;
        if (!value) {
            reset();
        }
    }

    /**
     * Records one applied operation and how long it took.
     *
     * <p>One call rather than two, because the two numbers are one fact about one op — a caller that
     * recorded the apply and forgot the save would produce a tally that reads as a fast op that saved
     * nothing, which is a lie about the thing being measured.
     *
     * <p><b>No write or sync count here.</b> Those are counted at the write seam by {@link #wrote},
     * because only the seam knows how many files were <i>actually</i> written: an op whose save refused
     * writes none, and a count taken from the request would report a burst as fifty writes when the disk
     * saw nothing. Two places counting one event is how a tally starts disagreeing with itself.
     *
     * @param applyNanos time in {@code EditorOps.apply}, model change and save included
     */
    public static void applied(long applyNanos) {
        if (!on) {
            return;
        }
        add("ops", 1);
        add("applyMs", applyNanos / 1_000_000L);
        report();
    }

    /**
     * Records that one file was written to disk, and the fsync that went with it.
     *
     * <p>Called from the write seam rather than from the apply path, and that is the whole of why it is a
     * separate entry point: the apply path knows how many files it <i>asked</i> for, and only
     * {@code JsonFile.write} knows how many were actually written. An op whose save refused writes none, and
     * a tally that counted the request would report a burst as fifty writes when the disk saw nothing.
     *
     * <p>One call, one write, one sync: {@code JsonWrite.atomically} opens with
     * {@code StandardOpenOption.SYNC}, so the write and the fsync are the same event by construction. See the
     * class note for why the name says sync.
     */
    public static void wrote() {
        if (!on) {
            return;
        }
        add("writes", 1);
        add("syncs", 1);
    }

    /**
     * Records one coalesced flush, as its own line rather than as part of the second's tally.
     *
     * <p>A flush has an identity — it is the one that followed <i>that</i> burst — so averaging several of
     * them into a per-second total would describe none of them. The numbers are the phases
     * {@code QuestLoader} does not already break out: the reload as a whole (its own line has the split),
     * the encode and the deflate.
     */
    public static void flushed(long reloadNanos, long encodeNanos, long deflateNanos, int players) {
        if (!on) {
            return;
        }
        // **Info, like the tally line, and this one was missed when the others were fixed.** It was debug
        // while the mod has no way to raise the log level, so the flush's own phases -- the reload as a
        // whole, the encode and the deflate -- had never been written either. Gated on `on()` above, so at
        // info it prints only while an operator has asked for the counter.
        Constants.LOG.info(
                "edit flush -- reload {} ms, encode {} ms, deflate {} ms, {} player(s)",
                reloadNanos / 1_000_000L, encodeNanos / 1_000_000L, deflateNanos / 1_000_000L, players);
    }

    private static void add(String name, long amount) {
        if (amount == 0L) {
            // A zero is not information and it keeps a line alive for a phase that did not run: a tally
            // that says `applyMs 0` every second for a server nobody is editing reads as a measurement.
            return;
        }
        TALLY.computeIfAbsent(name, key -> new long[1])[0] += amount;
    }

    /**
     * Publishes the window, if a second has passed and anything happened in it.
     *
     * <p>The clock is started by the first op rather than by the first report, so a line's window contains
     * the ops it describes. A window with no ops publishes nothing — see {@link #add} for why an all-zero
     * line is worse than no line.
     */
    private static void report() {
        long now = System.nanoTime();
        if (lastReportNanos == 0L) {
            lastReportNanos = now;
            return;
        }
        if (now - lastReportNanos < REPORT_NANOS) {
            return;
        }
        lastReportNanos = now;
        if (TALLY.isEmpty()) {
            return;
        }
        // **Info and not debug, which is what this was and why the reading could not be taken.** The line was
        // emitted at debug while the mod has no way to raise the log level, so `grep` over a full session
        // found *zero* occurrences of it -- an instrument that cannot be read is not an instrument. It is
        // already behind two gates (the switch in `on()`, and the empty-tally check above), so at info it
        // prints only while an operator has asked for it and only when something happened.
        Constants.LOG.info("edit cost -- {}", describe());
        TALLY.clear();
    }

    /** The tally as one line, {@code phase amount, phase amount}. Names the phases that ran. */
    private static String describe() {
        StringBuilder line = new StringBuilder();
        for (Map.Entry<String, long[]> each : TALLY.entrySet()) {
            if (line.length() > 0) {
                line.append(", ");
            }
            line.append(each.getKey()).append(' ').append(each.getValue()[0]);
        }
        return line.toString();
    }

    /** Forgets the tally and the clock. For a test, so one test's ops cannot report in another's. */
    public static void reset() {
        TALLY.clear();
        lastReportNanos = 0L;
    }

    /**
     * What the tally holds right now, by phase — for a test.
     *
     * <p>A copy, and a copy rather than the map itself for the reason {@code QuestWalks.tally} gives: the
     * live tally is <b>cleared on every report</b>, so a test handed the map would watch its own subject
     * vanish from under it a second into the run. Exposed at all because an instrument whose only reader is
     * a log line cannot be asserted, and the numbers here are what a decision about the save path rests on.
     */
    public static Map<String, Long> tally() {
        Map<String, Long> snapshot = new LinkedHashMap<>();
        TALLY.forEach((phase, count) -> snapshot.put(phase, count[0]));
        return Map.copyOf(snapshot);
    }
}
