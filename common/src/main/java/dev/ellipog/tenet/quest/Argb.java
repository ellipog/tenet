package dev.ellipog.tenet.quest;

import com.google.gson.JsonElement;
import com.google.gson.JsonPrimitive;
import com.mojang.serialization.Codec;
import com.mojang.serialization.DataResult;
import com.mojang.serialization.Dynamic;
import com.mojang.serialization.JsonOps;

import java.util.OptionalInt;

/**
 * A colour in a quest file, and the one place the format says what one is.
 *
 * <h2>The vocabulary, which is the toolkit's and not this file's invention</h2>
 *
 * <p>A string of six or eight hex digits with an optional leading {@code #} — {@code #24242E} and
 * {@code #8024242E} — or a number, read as the same 32-bit pattern. Six digits means opaque, which is what
 * anyone writing a colour by hand types, and eight carries alpha, which a translucent wash needs. This is
 * exactly what {@code Colour.fromHex} accepts and what the {@code themePatch} schema documents, so an
 * author who has set a theme's colour already knows how to set a box's.
 *
 * <p><b>A number is read as a long and truncated, and both signs mean the same colour.</b>
 * {@code 16711680} is {@code #00FF0000}; {@code 4294901760} and {@code -65536} are both {@code #FFFF0000}.
 * That is not laxity for its own sake — the theme's own reader does the same, and the legacy format writes a
 * colour with its high bit set as a <i>negative</i> number, so a converter that refused the negative half
 * would lose exactly the colours that carry an alpha.
 *
 * <h2>Why this is not {@code Colour.fromHex}</h2>
 *
 * <p>Because that class is the <b>client's</b>, and this one is read by a dedicated server that has no
 * business loading a UI class — the same boundary that makes {@link Chapter#themePatch} carry raw JSON
 * rather than a parsed patch. The rule is therefore written twice, in the two places the boundary puts it,
 * and <b>the agreement is checked rather than asserted</b>: {@code ArgbTest} compares this reader against
 * {@code Colour.fromHex} over the spellings both accept and both refuse, so the second copy cannot drift
 * from the first without failing the build.
 *
 * <h2>A value that is not a colour is an error here, and the validator is what says so readably</h2>
 *
 * <p>This is the shape every closed vocabulary in this format already has — {@link PrerequisiteMode},
 * {@link ProgressionMode}, {@link QuestShape} are all read by {@code Codecs.enumByName}, which refuses a
 * name it does not know. The reason it is safe is the order the loader works in: a chapter is <b>validated
 * first</b>, the validator reports the offending value at the field's own path with
 * {@link #spellings()} as its advice, and a file with an error on it is skipped — so the codec's own
 * refusal is never the first thing an author meets.
 *
 * <p>It is deliberately <b>not</b> the {@code Codecs.clampedInt} treatment, and that difference is the one
 * that codec's javadoc draws: clamping is right where a number outside its range is a presentation
 * decision with a nearest sensible value, and wrong where "the number is the meaning". A colour that is
 * not a colour has no nearest one, so inventing transparent black for it would hide the mistake rather
 * than report it.
 */
public final class Argb {

    /** No colour at all: fully transparent, which is the default of every colour in this format. */
    public static final int NONE = 0;

    /** Opaque white, which is the identity for a tint. */
    public static final int WHITE = 0xFFFFFFFF;

    /** The type every element's colours are read through. */
    public static final Codec<Integer> CODEC = Codec.PASSTHROUGH.comapFlatMap(
            dynamic -> read(dynamic.convert(JsonOps.INSTANCE).getValue()),
            // Written back as a hex string rather than as the number it may have arrived as, because a hex
            // string is the form an author reads. A round trip through this codec therefore normalises the
            // spelling -- which is what `QuestText.LIST_OR_ONE` does with a one-element list, and worth
            // naming for the same reason: a test asserting "the number I wrote comes back as a number"
            // would be asserting something this format does not promise.
            value -> new Dynamic<>(JsonOps.INSTANCE, new JsonPrimitive(toHex(value))));

    private Argb() {
    }

    /** The colour a JSON value holds, or an error naming what a colour may be. */
    private static DataResult<Integer> read(JsonElement element) {
        if (element == null || !element.isJsonPrimitive()) {
            return DataResult.error(Argb::spellings);
        }
        JsonPrimitive primitive = element.getAsJsonPrimitive();
        if (primitive.isString()) {
            String text = primitive.getAsString();
            OptionalInt value = parseHex(text);
            // Written out rather than folded, because `OptionalInt` has no `map`: it is the one primitive
            // optional whose API stops short, and a chain that looks like it should work does not.
            return value.isPresent()
                    ? DataResult.success(value.getAsInt())
                    : DataResult.error(() -> "'" + text + "' is not a colour - " + spellings());
        }
        if (primitive.isNumber()) {
            return DataResult.success((int) primitive.getAsLong());
        }
        return DataResult.error(Argb::spellings);
    }

    /**
     * A hex colour as an ARGB value, or empty when the text is not one.
     *
     * <p>The strict question, for a caller that has to <i>say</i> something about a bad value: the validator,
     * which reports it at the field's own line, and the editor's colour field. The codec above is built on it.
     */
    public static OptionalInt parseHex(String text) {
        if (text == null) {
            return OptionalInt.empty();
        }
        String trimmed = text.trim();
        if (trimmed.startsWith("#")) {
            trimmed = trimmed.substring(1);
        }
        if (trimmed.length() != 6 && trimmed.length() != 8) {
            return OptionalInt.empty();
        }
        for (int i = 0; i < trimmed.length(); i++) {
            if (Character.digit(trimmed.charAt(i), 16) < 0) {
                return OptionalInt.empty();
            }
        }
        // Long rather than Integer, and this is the trap the toolkit's own reader documents: eight hex
        // digits is exactly 32 bits, so `Integer.parseInt("ff000000", 16)` throws rather than returning a
        // value -- the parse that looks more careful is the one that fails on every opaque colour.
        long value = Long.parseLong(trimmed, 16);
        return OptionalInt.of(trimmed.length() == 6 ? (int) (0xFF000000L | value) : (int) value);
    }

    /** A colour as {@code #AARRGGBB}, which is the form this format writes and a file should carry. */
    public static String toHex(int argb) {
        return String.format("#%08X", argb);
    }

    /**
     * What a colour may be, as one sentence for a message.
     *
     * <p>Here rather than at each reporting site so the codec's error, the validator's error and the editor's
     * tooltip cannot describe three different vocabularies — the failure mode where an author is told to
     * write something the reader then refuses.
     */
    public static String spellings() {
        return "a colour is #RRGGBB or #AARRGGBB (six digits means opaque), or a number";
    }
}
