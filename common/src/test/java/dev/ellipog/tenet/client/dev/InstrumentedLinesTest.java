package dev.ellipog.tenet.client.dev;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The four instrumented lines must be readable, and this is what says so.
 *
 * <h2>Why a structural test rather than an assertion about behaviour</h2>
 *
 * <p>Every one of these instruments had a passing unit test — the tally counts, the ratio divides, the switch
 * gates — and **two of them had never once been written to a log.** They were emitted at {@code LOG.debug}
 * while the mod has no way to raise the log level, so {@code grep} over a full session found zero occurrences:
 * an instrument nothing can read is not an instrument, and the baseline table had two rows nobody could fill
 * because of how they were written.
 *
 * <p>No behavioural test can catch that. It is not a fault in what the code computes; it is a fault in whether
 * the answer ever reaches a person. So this reads the source and asserts the level, which is the only place the
 * fault is visible — and it is the same shape as {@code check_duplicates.py}: a rule about the code that the
 * code cannot state about itself.
 *
 * <p><b>The rule is not "never use debug".</b> It is that a line <i>named in the baseline table</i> has to be
 * readable by default, because the table is filled by a person reading a log. A debug line that is not in the
 * table is fine and there are several.
 */
@DisplayName("the instrumented lines are readable")
class InstrumentedLinesTest {

    /**
     * The lines the baseline table names, each with the file that writes it.
     *
     * <p>Hard-coded rather than discovered, and deliberately: the point is that these specific lines are
     * readable, and a scan that found "some line somewhere" would pass while one of them regressed.
     *
     * <p><b>`edit flush` is the fifth and it was added after the first four were fixed</b>, which is the
     * whole argument for listing them: the sweep that corrected `edit cost` and `load phases` walked the
     * four lines the table's *log-lines* list names and did not walk the tally line's own companion. A later
     * session found it still at debug, having never once been written.
     */
    private static final String[][] LINES = {
        {"cache walks", "common/src/main/java/dev/ellipog/tenet/client/dev/QuestWalks.java"},
        {"cache hits", "common/src/main/java/dev/ellipog/tenet/client/dev/CountingRenderer.java"},
        {"edit cost", "common/src/main/java/dev/ellipog/tenet/editor/EditPhases.java"},
        {"edit flush", "common/src/main/java/dev/ellipog/tenet/editor/EditPhases.java"},
        {"load phases", "common/src/main/java/dev/ellipog/tenet/quest/QuestLoader.java"},
    };

    /** The repo root, found by walking up — a module's tests run with that module as the working directory. */
    private static Path repoRoot() {
        Path here = Path.of("").toAbsolutePath();
        for (Path candidate = here; candidate != null; candidate = candidate.getParent()) {
            if (Files.isDirectory(candidate.resolve("common/src/main/java"))) {
                return candidate;
            }
        }
        throw new AssertionError("cannot find the module root above " + here);
    }

    @Test
    @DisplayName("each of the four named lines is emitted at info, not at debug")
    void everyNamedLineIsReadable() throws IOException {
        List<String> wrong = new ArrayList<>();
        for (String[] line : LINES) {
            String what = line[0];
            Path source = repoRoot().resolve(line[1]);
            assertTrue(Files.exists(source), "the file that writes " + what + " has moved: " + source);
            String text = Files.readString(source);

            // The call that writes this line, whatever level it names.
            Matcher call = Pattern.compile("LOG\\.(\\w+)\\(\\s*\"" + Pattern.quote(what)).matcher(text);
            if (!call.find()) {
                wrong.add(what + " is no longer written by " + line[1]);
                continue;
            }
            if (!"info".equals(call.group(1))) {
                wrong.add(what + " is at LOG." + call.group(1)
                        + ", and the mod has no way to raise the log level — a person filling the baseline"
                        + " table cannot read it");
            }
        }
        assertTrue(wrong.isEmpty(), String.join("; ", wrong));
    }

    @Test
    @DisplayName("the guard can find all four, so it is not passing vacuously")
    void theGuardFindsEveryLine() throws IOException {
        // The failure mode of a source-scanning guard is finding nothing and passing. This asserts the scan
        // works, which is what its first version did not do for the field-name tables in the generator.
        for (String[] line : LINES) {
            Path source = repoRoot().resolve(line[1]);
            assertTrue(Files.readString(source).contains(line[0]),
                    line[1] + " no longer contains the text " + line[0]
                            + ", so the check above would pass by finding nothing");
        }
    }
}
