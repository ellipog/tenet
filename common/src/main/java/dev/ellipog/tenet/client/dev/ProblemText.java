package dev.ellipog.tenet.client.dev;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A pack fault's one line, read back apart for the in-game surfaces that show it.
 *
 * <h2>Why a parser for a string the server just rendered</h2>
 *
 * <p>The payload carries faults as rendered lines ({@code file:line:column: severity: message}),
 * and the Chapter tab draws those lines verbatim — the layout test asserts the server's own
 * sentence reaches the row unreworded. Two interactions need the parts rather than the whole:
 * clicking a row copies the <i>file</i> (not the line and column), and the hover tooltip leads
 * with the <i>header</i> and wraps the message under it.
 *
 * <h2>Why this is pure and game-free</h2>
 *
 * <p>Both readers are string questions — where the first {@code :line:column:} sits, how long a
 * tooltip line may be — so they are answered without a client, a language, or a renderer, and
 * asserted without one. The tooltip's own folding ({@code Tooltips.draw}) still bounds the box
 * on screen; the pre-split here is readability (header first, message wrapped), not geometry.
 *
 * <h2>Why raw lines must bypass {@code Labels}</h2>
 *
 * <p>{@code Labels.of} resolves any substring from {@code tenet.} onward, and fault text can
 * carry that spelling (a write failure naming {@code dev.ellipog.tenet...}). Callers therefore
 * hand these strings to the tooltip and the chat directly — {@code Component.literal}, never a
 * translatable — which is also why the file part is copied rather than reworded.
 */
public final class ProblemText {

    /** The first {@code :line:column:} in a rendered fault, which ends the file part. */
    private static final Pattern LOCATION = Pattern.compile(":(\\d+):(\\d+):");

    /** A whole rendered fault: file, line, column, severity, and message. */
    private static final Pattern FAULT = Pattern.compile("^(.*?):(\\d+):(\\d+):\\s*([A-Za-z]+):\\s*(.*)$",
            Pattern.DOTALL);

    /** How wide a tooltip line may be before the message wraps under its header. */
    static final int TOOLTIP_WIDTH = 64;

    private ProblemText() {
    }

    /**
     * The file a fault is in, for the clipboard: everything before the first
     * {@code :line:column:}.
     *
     * <p>The whole line when it has no location — a fault that cannot be split still names
     * something worth pasting, and an empty clipboard would be the worse answer.
     */
    public static String fileOf(String line) {
        if (line == null || line.isEmpty()) {
            return "";
        }
        Matcher at = LOCATION.matcher(line);
        if (!at.find() || at.start() == 0) {
            return line;
        }
        return line.substring(0, at.start());
    }

    /**
     * A fault as tooltip lines: the {@code file:line:column: severity} header first, then the
     * message wrapped to {@link #TOOLTIP_WIDTH} characters.
     *
     * <p>One element — the line itself — when it is not shaped like a fault, so an unexpected
     * sentence is still shown rather than dropped.
     */
    public static List<String> tooltipLines(String line) {
        if (line == null || line.isEmpty()) {
            return List.of();
        }
        Matcher fault = FAULT.matcher(line);
        if (!fault.matches()) {
            return List.of(line);
        }
        String header = fault.group(1) + ":" + fault.group(2) + ":" + fault.group(3)
                + ": " + fault.group(4);
        String message = fault.group(5).trim();
        if (message.isEmpty()) {
            return List.of(header);
        }
        List<String> lines = new ArrayList<>();
        lines.add(header);
        lines.addAll(wrap(message, TOOLTIP_WIDTH));
        return List.copyOf(lines);
    }

    /**
     * Words wrapped to a width, overlong words split: the tooltip's folding would cut them
     * otherwise, and a hash or id with nowhere to break is exactly what faults carry.
     */
    static List<String> wrap(String text, int width) {
        List<String> lines = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        for (String word : text.split("\\s+")) {
            if (word.isEmpty()) {
                continue;
            }
            while (word.length() > width) {
                if (current.length() > 0) {
                    lines.add(current.toString());
                    current.setLength(0);
                }
                lines.add(word.substring(0, width));
                word = word.substring(width);
            }
            if (current.length() == 0) {
                current.append(word);
            }
            else if (current.length() + 1 + word.length() <= width) {
                current.append(' ').append(word);
            }
            else {
                lines.add(current.toString());
                current.setLength(0);
                current.append(word);
            }
        }
        if (current.length() > 0) {
            lines.add(current.toString());
        }
        if (lines.isEmpty()) {
            lines.add("");
        }
        return List.copyOf(lines);
    }
}
