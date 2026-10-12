package dev.ellipog.tenet.client;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The translation keys and the code, held in step in both directions.
 *
 * <h2>What is swept</h2>
 *
 * <p>{@code SWEPT} names the namespaces this sweep has converted — the player-facing screen strings,
 * status lines, state words, the command read-outs, the notices, the task/reward/condition/party/
 * quest/viewer/notice vocabularies, and the editor's own {@code tenet.dev.*}, which was converted
 * last. Every key there must exist in {@code en_us} <b>and</b> be referenced by code, so an orphan
 * and a missing key both fail. Deliberately not swept: {@code tenet.hud.}, whose labels are derived
 * by concatenation (see {@code HudElement.labelKey}) and would all read as orphans.
 *
 * <p>The editor's namespace is reached the second way: a layout record holds the key and the draw
 * site resolves it, so {@code literalsThatAreKeys} reads those records rather than a
 * {@code translatable(...)} call. Converting a surface is adding its prefix to {@code SWEPT}, and the
 * note in TESTING.md says which are in.
 *
 * <p>Keys are found by scanning the sources for {@code translatable(...)} and
 * {@code translatableWithFallback(...)} with a literal first argument. That is a scan of <b>keys</b>,
 * not of prose: a key is a machine name in one known call shape, while a scan for English literals
 * would be blind to layout records and noisy on glyphs — which is why the reverse direction is
 * scoped rather than global.
 */
@DisplayName("the translation keys and the code agree")
class LangSweepTest {

    /** The test working directory is the common module, so the sources are one level in. */
    private static final Path SOURCE = Path.of("src", "main", "java");
    private static final Path LANG =
            Path.of("src", "main", "resources", "assets", "tenet", "lang", "en_us.json");

    /** The namespaces the sweep has converted. Add a prefix as each surface is converted. */
    private static final List<String> SWEPT = List.of(
            "tenet.screen.",
            "tenet.status.",
            "tenet.state.",
            "tenet.command.text.",
            "tenet.narrate.",
            "tenet.toast.",
            // The editor's own namespace, converted last: the layout records hold these keys and the
            // draw sites resolve them, so a key here is referenced by a record rather than by a
            // `translatable(...)` call -- which is the second road `literalsThatAreKeys` reads.
            "tenet.dev.",
            "tenet.task.",
            "tenet.reward.",
            "tenet.condition.",
            "tenet.party.",
            "tenet.quest.",
            "tenet.viewer.",
            "tenet.notice.");

    private static final Pattern KEY = Pattern.compile(
            "translatable(?:WithFallback)?\\(\\s*\"([^\"]+)\"");

    @Test
    @DisplayName("every key the code names exists in en_us")
    void everyKeyUsedInCodeExists() throws IOException {
        Set<String> defined = definedKeys();

        Set<String> missing = new TreeSet<>();
        for (String key : translatableKeys()) {
            if (!defined.contains(key)) {
                missing.add(key);
            }
        }
        assertTrue(missing.isEmpty(), () -> "the code names keys en_us does not define, so those "
                + "strings render as their key in game: " + missing);
    }

    @Test
    @DisplayName("every swept key is referenced, so an orphan cannot sit there looking translated")
    void everySweptKeyIsUsed() throws IOException {
        Set<String> defined = definedKeys();
        Set<String> referenced = literalsThatAreKeys(defined);

        Set<String> orphans = new TreeSet<>();
        for (String key : defined) {
            boolean swept = SWEPT.stream().anyMatch(key::startsWith);
            if (swept && !referenced.contains(key)) {
                orphans.add(key);
            }
        }
        assertTrue(orphans.isEmpty(), () -> "these keys are in the swept namespaces and nothing "
                + "references them -- a dead key reads as a translated string that is never shown: "
                + orphans);
    }

    private static Set<String> definedKeys() throws IOException {
        JsonObject root = JsonParser.parseString(Files.readString(LANG)).getAsJsonObject();
        return new TreeSet<>(root.keySet());
    }

    /**
     * Keys named in a {@code translatable(...)} call, for the forward direction: these are the keys
     * the code will look up at runtime, so one that is not defined renders as its own key.
     */
    private static Set<String> translatableKeys() throws IOException {
        Set<String> keys = new TreeSet<>();
        try (Stream<Path> files = Files.walk(SOURCE)) {
            for (Path file : files.filter(p -> p.toString().endsWith(".java")).toList()) {
                Matcher matcher = KEY.matcher(Files.readString(file));
                while (matcher.find()) {
                    String key = matcher.group(1);
                    // A key ending in a dot is a concatenation prefix -- `"tenet.screen.party.role."
                    // + role` -- and the full key is built at runtime from an enum. Not a missing key,
                    // and not something this scan can see whole.
                    if (!key.endsWith(".")) {
                        keys.add(key);
                    }
                }
            }
        }
        return keys;
    }

    /**
     * Every string literal in the sources that is a defined key, whole or in part.
     *
     * <p>Literal equality rather than a call-shape scan, because keys reach the screen by more than
     * one road: a party panel's row label <i>is</i> its key (the layout holds it and the screen
     * translates it), and a ternary picks between two keys. Only a literal that is already a defined
     * key counts, so prose cannot be mistaken for one -- and a key built at runtime, like the role
     * words' {@code "tenet.screen.party.role." + role}, is simply outside what any static scan can
     * see, which its own test covers instead.
     *
     * <p>The second road is a key with a marker in front: a section heading carries its fold triangle
     * in the layout record — {@code "\u25bc tenet.dev.tools.palette"} — and the key runs to the end of
     * the literal, so the substring from the mod's own prefix is the candidate. That shape is
     * {@code Labels.of}'s, and without this the marked headings would read as orphans.
     */
    private static Set<String> literalsThatAreKeys(Set<String> defined) throws IOException {
        Set<String> keys = new TreeSet<>();
        // Escape-aware, and that is not pedantry: a naive `"([^"]+)"` pairs quotes wrongly the moment
        // the file contains `\"` anywhere before the string being looked for, and then every literal
        // after it is misread. This pattern steps over an escaped character instead.
        Pattern literal = Pattern.compile("\"((?:[^\"\\\\]|\\\\.)*)\"");
        try (Stream<Path> files = Files.walk(SOURCE)) {
            for (Path file : files.filter(p -> p.toString().endsWith(".java")).toList()) {
                Matcher matcher = literal.matcher(Files.readString(file));
                while (matcher.find()) {
                    String text = matcher.group(1);
                    if (defined.contains(text)) {
                        keys.add(text);
                        continue;
                    }
                    int at = text.indexOf("tenet.");
                    if (at > 0 && defined.contains(text.substring(at))) {
                        keys.add(text.substring(at));
                    }
                }
            }
        }
        return keys;
    }
}
