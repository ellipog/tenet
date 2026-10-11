package dev.ellipog.tenet.quest;

import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import com.mojang.serialization.JsonOps;

import dev.ellipog.armature.client.ui.kit.Colour;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The colour vocabulary a quest file's elements are written in.
 *
 * <h2>Why this has its own file rather than a case in the element test</h2>
 *
 * <p>Because the rule is written twice, on purpose, and this is where the two copies are held together.
 * {@code Argb} cannot call {@link Colour#fromHex} — that class is the client's, and this model is read by
 * a dedicated server that must not load a UI class — so the rule exists in two places, split by the
 * boundary the rest of this format already draws. The last test in this file is what keeps the split from
 * becoming a drift: it runs every spelling both accept and both refuse through <i>both</i> readers.
 *
 * <p>The rest is the vocabulary itself, including the half that is easy to get wrong: a number, which is
 * how the legacy format writes a colour and therefore how a converted pack arrives.
 */
@DisplayName("a colour in a quest file")
class ArgbTest {

    private static JsonElement json(String text) {
        return JsonParser.parseString(text);
    }

    private static int read(String text) {
        return Argb.CODEC.parse(JsonOps.INSTANCE, json(text)).getOrThrow();
    }

    private static String write(int argb) {
        return Argb.CODEC.encodeStart(JsonOps.INSTANCE, argb).getOrThrow().getAsString();
    }

    @Test
    @DisplayName("six digits is opaque, eight carries its own alpha, and the hash is optional")
    void hexSpellings() {
        assertEquals(0xFF24242E, read("\"#24242E\""));
        assertEquals(0x8024242E, read("\"#8024242E\""));
        // The hash is what a person types and what a theme file already allows omitting, so refusing it
        // here would be one format with two answers about the same six digits.
        assertEquals(0xFF24242E, read("\"24242E\""));
        assertEquals(0x8024242E, read("\"8024242E\""));
        // Whitespace is ignored, for the same reason the toolkit's reader ignores it: a colour is a number
        // somebody typed into a file.
        assertEquals(0xFF24242E, read("\"  #24242E \""));
    }

    @Test
    @DisplayName("a number is the same 32-bit pattern, and both signs mean the same colour")
    void numbers() {
        // the legacy format writes `color` as a number, so this is the arm a converted pack arrives in -- and it is
        // the arm with a trap in it. the legacy format's own writer puts `color.rgb()` on disk and keeps the alpha in a
        // field of its own, so 16711680 is 0x00FF0000: red with NO alpha, because a number here is ARGB just
        // as it is in a theme's colour map. That is the rule, not a bug, and it is why the migration table
        // says the converter writes `#FFRRGGBB`: the legacy format draws the tint with the `alpha` field REPLACING the
        // colour's own alpha, which an opaque tint reproduces exactly under this format's multiplication.
        assertEquals(0x00FF0000, read("16711680"));
        assertEquals(0x007010A5, read("7344293"));
        assertEquals(0x00000000, read("0"));
        // The high half is the interesting one: a colour that carries alpha is above what an int holds, and
        // the legacy format writes one as a negative number. Both are the same colour, and refusing either would lose
        // exactly the translucent colours.
        assertEquals(0xFFFF0000, read("4294901760"));
        assertEquals(0xFFFF0000, read("-65536"));
        assertEquals(0xFFFFFFFF, read("-1"));
    }

    @Test
    @DisplayName("a value that is not a colour is refused, with the spellings in the message")
    void refusals() {
        for (String bad : List.of("\"#FFF\"", "\"#A0A0A\"", "\"#12345\"", "\"#GGGGGG\"", "\"red\"",
                "\"\"", "true", "{}", "[]", "null")) {
            var result = Argb.CODEC.parse(JsonOps.INSTANCE, json(bad));
            assertTrue(result.error().isPresent(), bad + " should not read as a colour");
            assertTrue(result.error().orElseThrow().message().contains("#RRGGBB"),
                    "and the message should say what a colour is, got: "
                            + result.error().orElseThrow().message());
        }
    }

    @Test
    @DisplayName("a colour is written back as #AARRGGBB, which is the form a file should carry")
    void writtenAsHex() {
        assertEquals("#FF24242E", write(0xFF24242E));
        assertEquals("#8024242E", write(0x8024242E));
        assertEquals("#00000000", write(0));
        // A number in and a string out, deliberately: the round trip normalises the spelling rather than
        // preserving it, which is what `QuestText.LIST_OR_ONE` also does and what a file an author reads
        // is better for.
        assertEquals("#00FF0000", write(read("16711680")));
        assertEquals(0x00FF0000, read(com.google.gson.JsonParser.parseString("\"" + write(0x00FF0000) + "\"")
                .toString()));
    }

    @Test
    @DisplayName("parseHex answers strictly, for a caller that has to report a bad value")
    void parseHexIsStrict() {
        // The validator's half. The codec and this agree by construction — the codec is built on it — but a
        // caller reporting a message needs a boolean, not a DataResult it would have to unwrap to ask.
        assertEquals(0xFF24242E, Argb.parseHex("#24242E").orElseThrow());
        assertEquals(0x8024242E, Argb.parseHex("8024242E").orElseThrow());
        assertTrue(Argb.parseHex("#FFF").isEmpty());
        assertTrue(Argb.parseHex("red").isEmpty());
        assertTrue(Argb.parseHex(null).isEmpty());
        assertTrue(Argb.spellings().contains("#AARRGGBB"), "the sentence names both forms");
    }

    @Test
    @DisplayName("the two readers of this vocabulary accept and refuse exactly the same spellings")
    void theToolkitsReaderAgrees() {
        // The test this file exists for. `Argb` is a second copy of a rule that lives in the client's
        // toolkit, because a dedicated server may not load that class -- so the copy is held to the
        // original here rather than trusted. A colour the editor writes and the loader refuses would be an
        // author's file reported as wrong by the mod that offered them the picker.
        for (String accepted : List.of("#24242E", "24242E", "#8024242E", "8024242E", "  #FFFFFF  ",
                "#000000", "#FFFFFFFF")) {
            assertEquals(Colour.fromHex(accepted), (Integer) Argb.parseHex(accepted).orElseThrow(),
                    "both readers should accept " + accepted + " as the same colour");
        }
        for (String refused : List.of("#FFF", "#A0A0A", "#1234567", "#GGGGGG", "red", "", "0x24242E",
                "##24242E")) {
            assertFalse(Colour.fromHex(refused) != null && Argb.parseHex(refused).isPresent(),
                    "neither reader should accept " + refused);
            assertEquals(Colour.fromHex(refused) == null, Argb.parseHex(refused).isEmpty(),
                    "and both should agree about " + refused);
        }
        // Whitespace is the one place the two could have drifted silently, since both trim: it is in the
        // accepted list above rather than here, and this asserts the agreement rather than the trimming.
        assertEquals(Colour.fromHex("#24242E "), (Integer) Argb.parseHex("#24242E ").orElseThrow());
    }
}
