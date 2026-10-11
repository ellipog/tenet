package dev.ellipog.tenet.client.dev;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A fault's one line, read back apart.
 *
 * <h2>Why these are strings and not a report</h2>
 *
 * <p>The readers are string questions — which file a line blames, how a tooltip leads with the
 * header — so they are answered from the line alone, with no client and no report. What the
 * report carries, and that it reaches the rows unreworded, is {@code ChapterPanelLayoutTest}'s
 * question rather than this file's.
 */
@DisplayName("a fault's line, read back apart")
class ProblemTextTest {

    @Test
    @DisplayName("the file is everything before the first line:column")
    void theFileIsBeforeTheLocation() {
        assertEquals("a.json",
                ProblemText.fileOf("a.json:4:9: error: no quest with id or alias \"gone\" exists"));
        assertEquals("quests/b.json",
                ProblemText.fileOf("quests/b.json:1:1: error: circular dependency: p -> q -> p"));
    }

    @Test
    @DisplayName("a line with no location still names something worth pasting")
    void noLocationIsTheWholeLine() {
        assertEquals("something broke, somewhere",
                ProblemText.fileOf("something broke, somewhere"));
        assertEquals("", ProblemText.fileOf(""));
        assertEquals("", ProblemText.fileOf(null));
    }

    @Test
    @DisplayName("the tooltip leads with the header and wraps the message under it")
    void theTooltipIsAHeaderOverAWrappedMessage() {
        List<String> lines = ProblemText.tooltipLines(
                "a.json:4:9: error: no quest with id or alias \"gone\" exists");
        assertEquals("a.json:4:9: error", lines.get(0));
        assertEquals("no quest with id or alias \"gone\" exists",
                String.join(" ", lines.subList(1, lines.size())));
    }

    @Test
    @DisplayName("a long message wraps, and every line fits the box")
    void aLongMessageWraps() {
        List<String> lines = ProblemText.tooltipLines("a.json:1:1: error: "
                + "unknown field \"titl\" - did you mean \"title\"? there is a great deal more to say "
                + "about this fault than fits on one line of any tooltip");
        assertTrue(lines.size() > 2, "a sentence that long is more than a header and a line");
        for (String line : lines) {
            assertTrue(line.length() <= 64, "a tooltip line that overflows: " + line);
        }
        assertEquals("a.json:1:1: error", lines.get(0));
    }

    @Test
    @DisplayName("a line shaped like nothing in particular is still shown")
    void anUnshapedLineIsShownAsIs() {
        assertEquals(List.of("just words"), ProblemText.tooltipLines("just words"));
        assertEquals(List.of(), ProblemText.tooltipLines(""));
        assertEquals(List.of(), ProblemText.tooltipLines(null));
    }

    @Test
    @DisplayName("a word longer than the width is split rather than lost")
    void anOverlongWordIsSplit() {
        String word = "a".repeat(100);
        List<String> lines = ProblemText.tooltipLines("a.json:1:1: error: " + word);
        for (String line : lines) {
            assertTrue(line.length() <= 64, "a tooltip line that overflows: " + line);
        }
        assertEquals(word, String.join("", lines.subList(1, lines.size())));
    }
}
