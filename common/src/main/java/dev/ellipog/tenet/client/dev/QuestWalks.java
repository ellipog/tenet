package dev.ellipog.tenet.client.dev;

import dev.ellipog.tenet.Constants;
import dev.ellipog.tenet.client.DevMode;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * How much of the quest cache the client walked this second — the instrument the per-tick cost is read
 * from.
 *
 * <h2>Why this is not on the frame HUD</h2>
 *
 * <p>Because the walks it counts happen when <b>no screen is open</b>, which is the whole reason they
 * matter: a tick that walks every quest every tick is paid by a player who is mining, not by a player
 * reading the book. {@link CountingRenderer}'s HUD can only draw while the book is up, so a walk count
 * that lived there would be blind to exactly the cost it was added to prove. This reports to the log
 * instead, once a second, whether or not anything is on screen.
 *
 * <h2>What the number is for</h2>
 *
 * <p>Not for tuning. It is the <b>falsifier</b> for a claim of the form "this tick no longer walks the
 * cache": iterations proportional to the quest or task count are the defect, and the fix is to walk a
 * handful of relevant rows instead. A count that stays flat as the quest count grows is the proof; a
 * count that scales with the pack is the defect, still there. That is a claim about a number, so the
 * honest way to settle it is to print the number.
 *
 * <h2>Off, and what it costs when off</h2>
 *
 * <p>One branch per walker. {@link DevMode#on()} is a static boolean read, and a walker that asks while
 * the mode is off allocates nothing, looks nothing up and logs nothing — so this can sit in a tick path
 * without a second thought about the cost of the instrument itself.
 *
 * <h2>The tally, and the thread it is written on</h2>
 *
 * <p>A plain map rather than a concurrent one, because every caller is the client thread: the tick
 * hook that drives them runs there, and there is no version of "the walk happened somewhere else" that
 * would not also be a bug worth a crash. The boxed per-call allocation a {@code Map.merge} would make
 * is avoided too — the value is an {@code int[]} so a call is a lookup and an add.
 */
public final class QuestWalks {

    /** How often the tally is reported, matching the frame counter's own once-a-second line. */
    private static final long REPORT_NANOS = 1_000_000_000L;

    private static long lastReportNanos;

    /**
     * Cache walks since the last report, by the walker's own name.
     *
     * <p>Insertion-ordered, so a line reads in the order the walkers first ran in a second rather than
     * in whatever order a hash happens to produce. Two walkers that alternate are a line that shuffles
     * frame to frame, which is a line nobody can compare against the last one.
     */
    private static final Map<String, int[]> TALLY = new LinkedHashMap<>();

    private QuestWalks() {
    }

    /**
     * Records that one walker visited {@code entries} cache entries.
     *
     * @param walker the walker's own name, a constant at the call site rather than a formatted string —
     *               a name built per call would allocate on a path that exists to find allocation
     * @param entries how many cache entries it visited; zero or fewer is ignored, so a walker that
     *                stopped walking does not keep a line alive at zero
     */
    public static void walked(String walker, int entries) {
        if (!DevMode.on() || entries <= 0) {
            return;
        }
        TALLY.computeIfAbsent(walker, name -> new int[1])[0] += entries;

        long now = System.nanoTime();
        if (lastReportNanos == 0L) {
            // The first walk of a session starts the clock rather than reporting. A line whose total is
            // one tick's worth is not a rate, and clearing on it would publish a number the next
            // report's window does not contain.
            lastReportNanos = now;
            return;
        }
        if (now - lastReportNanos < REPORT_NANOS) {
            return;
        }
        lastReportNanos = now;
        Constants.LOG.info("cache walks -- {}", describe());
        TALLY.clear();
    }

    /** The tally as one line, {@code walker count, walker count}. Names the walkers that ran. */
    private static String describe() {
        StringBuilder line = new StringBuilder();
        for (Map.Entry<String, int[]> each : TALLY.entrySet()) {
            if (line.length() > 0) {
                line.append(", ");
            }
            line.append(each.getKey()).append(' ').append(each.getValue()[0]);
        }
        return line.toString();
    }

    /** Forgets the tally and the clock. For a test, so one test's walkers cannot report in another's. */
    public static void reset() {
        TALLY.clear();
        lastReportNanos = 0L;
    }

    /**
     * What the tally holds right now, by walker — for a test.
     *
     * <p>A copy, and a copy rather than the map itself for a reason that is not defensive: the live
     * tally is <b>cleared on every report</b>, so a test handed the map would watch its own subject
     * vanish from under it a second into the run. Exposed at all because an instrument whose only
     * reader is a log line cannot be asserted, and the number this one produces is the falsifier for a
     * claim about a tick — the same reason {@code ScrollView} reports its placed and culled counts.
     */
    public static Map<String, Integer> tally() {
        Map<String, Integer> snapshot = new LinkedHashMap<>();
        TALLY.forEach((walker, count) -> snapshot.put(walker, count[0]));
        return Map.copyOf(snapshot);
    }
}
