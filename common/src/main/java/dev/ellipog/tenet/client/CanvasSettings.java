package dev.ellipog.tenet.client;

import dev.ellipog.tenet.Constants;

import dev.ellipog.armature.api.ArmatureApi;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * How much of the canvas to draw, and from how far out — the client's own thresholds, in a file.
 *
 * <h2>Why these are a player's settings rather than the mod's constants</h2>
 *
 * <p>Because the right answer depends on the machine and the eye, and neither is the mod's to know. A
 * player on a laptop wants a chapter to stop drawing rings sooner than one on a desktop; an author
 * photographing a canvas wants everything drawn at a zoom where a reader would rather see shapes. The
 * numbers were constants, which made the choice for both of them — and the first thing that went wrong with
 * them was visible in a screenshot: a <b>landmark node lost its icon</b> because a global zoom threshold
 * decided icons were smudges, while the node itself was enormous and had room to spare.
 *
 * <h2>The three values, and what each decides</h2>
 *
 * <ul>
 *   <li><b>{@code iconMinBox}</b> — the smallest an item's box may be, in pixels, before the node stops
 *       drawing its item and draws its stand-in block instead. This one is <b>per node</b>, measured from
 *       that node's own outline, and it is why the zoom no longer decides whether an icon is drawn: a large
 *       node has a large icon at any zoom, and a small one runs out of room at a specific size rather than
 *       at a specific zoom. Default 12.</li>
 *   <li><b>{@code ringsBelow}</b> — below this zoom, no hover or selection ring. Default 0.5.</li>
 *   <li><b>{@code blocksBelow}</b> — below this zoom, no titles and no reward badges. Default 0.3.</li>
 * </ul>
 *
 * <h2>Tolerant, and clamped, in that order</h2>
 *
 * <p>Like the other client files: a missing file is a first run and gets no message, a file that cannot be
 * read gets one and leaves the defaults in force, and an unknown field is ignored. On top of that the
 * numbers are <b>clamped</b>, because a threshold is a number whose wrong values are not errors but
 * nonsense: a zoom threshold of 5 would mean "never draw a ring" on a canvas that only zooms to 2.2, and a
 * blocks threshold above the rings threshold would mean a tier that can never be reached. So a value outside
 * its range takes the nearest end of it, and {@code blocksBelow} is held at or below {@code ringsBelow}.
 * Neither clamp is a guess at intent: both are the only readings under which the ladder means anything.
 */
public final class CanvasSettings {

    /** The client preference this mod writes. Beside {@code appearance.json} and {@code client.json}. */
    public static final String FILE_NAME = "canvas.json";

    /** Below this zoom, no hover or selection ring. */
    public static final float DEFAULT_RINGS_BELOW = 0.5F;

    /** Below this zoom, no titles and no reward badges. */
    public static final float DEFAULT_BLOCKS_BELOW = 0.3F;

    /** The smallest an item's box may be, in pixels, before the node draws its stand-in block instead. */
    public static final int DEFAULT_ICON_MIN_BOX = 12;

    /** The zoom thresholds are clamped into this range: below the first is unreadable, above the second is
     * beyond the canvas's own zoom, so a value outside it means "never" or "always" by accident. */
    private static final float MIN_ZOOM_THRESHOLD = 0.05F;
    private static final float MAX_ZOOM_THRESHOLD = 4.0F;

    /** And the icon box into a range where asking the question means something. */
    private static final int MIN_ICON_BOX = 1;
    private static final int MAX_ICON_BOX = 64;

    private static float ringsBelow = DEFAULT_RINGS_BELOW;
    private static float blocksBelow = DEFAULT_BLOCKS_BELOW;
    private static int iconMinBox = DEFAULT_ICON_MIN_BOX;

    private CanvasSettings() {
    }

    /** Below this zoom, no hover or selection ring. */
    public static float ringsBelow() {
        return ringsBelow;
    }

    /** Below this zoom, no titles and no reward badges; never above {@link #ringsBelow()}. */
    public static float blocksBelow() {
        return blocksBelow;
    }

    /** The smallest an item's box may be, in pixels, before a node draws its stand-in block. */
    public static int iconMinBox() {
        return iconMinBox;
    }

    /** What one file says. A record, so the file is read once and the three answers are one answer. */
    public record Parsed(float ringsBelow, float blocksBelow, int iconMinBox) {
    }

    /** Reads the thresholds from the platform's config directory. Called once by each loader's client. */
    public static void loadFromConfig() {
        Path path = null;
        try {
            path = ArmatureApi.platform().configDir(Constants.MOD_ID).resolve(FILE_NAME);
        }
        catch (RuntimeException e) {
            Constants.LOG.warn("the platform layer was not ready, so the canvas thresholds were not"
                    + " read. Every default is in force.", e);
        }
        if (path != null) {
            load(path);
        }
    }

    /** Reads the thresholds from one file, leaving the defaults in force for anything it does not say. */
    public static void load(Path path) {
        ringsBelow = DEFAULT_RINGS_BELOW;
        blocksBelow = DEFAULT_BLOCKS_BELOW;
        iconMinBox = DEFAULT_ICON_MIN_BOX;

        if (!Files.isRegularFile(path)) {
            return;
        }
        try {
            apply(parse(Files.readString(path, StandardCharsets.UTF_8)));
        }
        catch (IOException | RuntimeException e) {
            Constants.LOG.warn("{} could not be read, so the canvas thresholds are the defaults."
                    + " Deleting the file will stop this message.", path, e);
        }
    }

    /**
     * Parses the file's text into the three values, clamped. See the class note for why each clamp is the
     * only reading under which the ladder means anything.
     *
     * @throws com.google.gson.JsonSyntaxException if the text is not JSON at all; {@link #load} catches it
     */
    public static Parsed parse(String json) {
        JsonObject root = JsonParser.parseString(json).getAsJsonObject();
        float rings = clampZoom((float) number(root, "ringsBelow", DEFAULT_RINGS_BELOW));
        float blocks = Math.min(clampZoom((float) number(root, "blocksBelow", DEFAULT_BLOCKS_BELOW)), rings);
        int box = Math.max(MIN_ICON_BOX, Math.min(MAX_ICON_BOX,
                (int) number(root, "iconMinBox", DEFAULT_ICON_MIN_BOX)));
        return new Parsed(rings, blocks, box);
    }

    /** One field as a number, or the default when the file does not say or says something else. */
    private static double number(JsonObject root, String field, double fallback) {
        if (!root.has(field) || !root.get(field).isJsonPrimitive()
                || !root.getAsJsonPrimitive(field).isNumber()) {
            return fallback;
        }
        return root.get(field).getAsDouble();
    }

    private static float clampZoom(float value) {
        return Math.max(MIN_ZOOM_THRESHOLD, Math.min(MAX_ZOOM_THRESHOLD, value));
    }

    /** The three values, clamped. Split from {@link #parse} so a test can hold the rule. */
    static void apply(Parsed parsed) {
        ringsBelow = parsed.ringsBelow();
        blocksBelow = parsed.blocksBelow();
        iconMinBox = parsed.iconMinBox();
    }
}
