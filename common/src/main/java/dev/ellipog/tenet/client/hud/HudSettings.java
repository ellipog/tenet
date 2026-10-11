package dev.ellipog.tenet.client.hud;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import dev.ellipog.armature.api.ArmatureApi;
import dev.ellipog.armature.api.data.JsonWrite;
import dev.ellipog.tenet.Constants;
import dev.ellipog.tenet.client.compat.FtbCompat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.EnumMap;
import java.util.Map;

/**
 * Where this client's HUD things are, and whether they are drawn: {@code config/tenet/hud.json}.
 *
 * <h2>Its own file, and the four it sits beside</h2>
 *
 * <p>{@code appearance.json}, {@code client.json}, {@code canvas.json} and {@code working.json} already
 * exist, one per area of this client's own preferences. A layout is a fifth area and gets a fifth file --
 * rather than a corner of {@code client.json}, which is the tools' file and whose schema is the panel-width
 * table. What lives here is not a developer's setting: it is where a player put their things.
 *
 * <h2>A diff from the effective defaults, not a table</h2>
 *
 * <p>An element the player has never touched has <b>no entry</b>, and a value put back to its default is
 * removed from the entry it was in; an entry with nothing left in it goes. That is
 * {@code DevMode.panelWidths}' own shape, and its own reason: a file that restates every value cannot be read
 * for what the player changed, and a file that grows a line every time somebody drags something and puts it
 * back is a file nobody can diff.
 *
 * <p>"Default" means the <b>effective</b> default: the book's button ships at window {@code (2, 2)} alone
 * and at {@code (22, 2)} with FTB Library's sidebar around -- see {@code FtbCompat} -- so putting it back
 * to the position it visibly shipped at leaves no entry either way. A stored coordinate always wins over
 * both, which is what keeps a player's explicit choice from moving when that sidebar arrives or leaves.
 *
 * <h2>What a stored value is, and what it is not</h2>
 *
 * <p>A <b>position in the window's own pixels</b> -- the same space the HUD is measured in and the same space
 * the editor draws in, so a number written there is a number the game draws at, at any window size -- unless
 * the book's button is anchored to the inventory panel, in which case it is an <b>offset from the panel's
 * top-left corner</b>, re-based on the panel's live corner at every placement. Read
 * through the element's {@code Anchor}: a middle-anchored element's y is an offset from the window's
 * vertical centre rather than an absolute pixel, which is what lets its default of 0 mean "centred" on
 * every window without a constant naming a middle that moves.
 *
 * <p>Nothing here clamps it: the only bound that means anything is the window, and that is not known until
 * something is being drawn or dragged. A hand-edited {@code -500} is therefore kept exactly as it was
 * written and lands at the window's edge, rather than being rewritten to a number the player did not type.
 *
 * <h2>Every way of being wrong</h2>
 *
 * <p>The same tolerance as the file beside it, at the same granularity: a key that names no element this
 * build has, a value that is not an object, and a field of the wrong type each cost <b>that entry</b> -- the
 * element keeps its defaults and its neighbours are untouched -- while text that is not JSON at all costs the
 * whole file, which is the defaults. Neither stops the client.
 */
public final class HudSettings {

    /** The client preference this feature writes. Named for what it holds. */
    public static final String FILE_NAME = "hud.json";

    /**
     * One element's deviation from its defaults; a missing field is the element's own default.
     *
     * <p>Boxed rather than primitive, because "the player did not say" and "the player said the default"
     * have to be different answers: the first writes no field at all, and the second removes one. The
     * anchor is a string rather than an origin for the same reason a hand-edited coordinate is kept as
     * written: a value no build here defines is still a value the file holds, and what is read from it
     * is the default rather than a guess.
     */
    public record Entry(Integer x, Integer y, Boolean on, Double dim, String anchor) {

        /** Whether this says nothing, and so should not be in the file. */
        public boolean empty() {
            return x == null && y == null && on == null && dim == null && anchor == null;
        }
    }

    private static Map<HudElement, Entry> entries = new EnumMap<>(HudElement.class);
    private static Path file;

    private HudSettings() {
    }

    /** Whether this element is drawn. On unless the player switched it off. */
    public static boolean on(HudElement element) {
        Boolean said = entry(element).on();
        return said == null ? element.defaultOn() : said;
    }

    /**
     * Where it sits, as a position in the window.
     *
     * <p>The stored coordinate, or the effective default when the player never moved it: {@code (2, 2)}
     * for the book's button alone, {@code (22, 2)} with FTB Library's sidebar around. A stored value
     * always wins, so installing or removing that sidebar never moves an explicitly placed button.
     */
    public static int x(HudElement element) {
        Integer said = entry(element).x();
        return said == null ? FtbCompat.effectiveDefaultX(element) : said;
    }

    /**
     * The same, vertically.
     *
     * <p>Read through the effective default for the same reason: the sidebar grows downward, so the
     * shipped height does not move, only what the file holds does.
     */
    public static int y(HudElement element) {
        Integer said = entry(element).y();
        return said == null ? FtbCompat.effectiveDefaultY(element) : said;
    }

    /** How strong this element's background dim is, 0 for none and 1 for the theme's own wash. */
    public static double dim(HudElement element) {
        Double said = entry(element).dim();
        return said == null ? element.defaultDim() : said;
    }

    /**
     * What a stored position is measured from.
     *
     * <p>Window unless the player chose the inventory panel for the book's button. An anchor no build
     * here defines -- a newer build's vocabulary, or a typo by hand -- reads as the default rather
     * than costing the entry: the position beside it is still the player's, and a window pixel is the
     * reading that cannot strand a control. An anchor on any other element reads as the default too,
     * because only the button has a panel to be measured from; the file keeps what it was handed either
     * way, like every other setting here.
     */
    public static HudElement.Origin origin(HudElement element) {
        if (!element.supportsOrigin(HudElement.Origin.INVENTORY)) {
            return element.defaultOrigin();
        }
        HudElement.Origin said = HudElement.Origin.named(entry(element).anchor());
        return said == null ? element.defaultOrigin() : said;
    }

    /** Switches an element on or off and writes the choice. */
    public static void setOn(HudElement element, boolean next) {
        Entry before = entry(element);
        put(element, new Entry(before.x(), before.y(), next == element.defaultOn() ? null : next,
                before.dim(), before.anchor()));
    }

    /**
     * Remembers where an element was put and writes it.
     *
     * <p>Both coordinates together, because they are one decision: an element is somewhere, not at an x and
     * separately at a y. Compared against the effective default rather than the table's, so putting the
     * button back where it visibly shipped -- {@code (22, 2)} with that sidebar around -- leaves no entry,
     * while explicitly choosing {@code (2, 2)} there is stored and survives that sidebar leaving.
     */
    public static void setPosition(HudElement element, int nextX, int nextY) {
        Entry before = entry(element);
        put(element, new Entry(
                nextX == FtbCompat.effectiveDefaultX(element) ? null : nextX,
                nextY == FtbCompat.effectiveDefaultY(element) ? null : nextY,
                before.on(), before.dim(), before.anchor()));
    }

    /**
     * Remembers how strong an element's background dim is and writes it.
     *
     * <p>Clamped rather than refused: a slider cannot produce a value outside its own range, so anything
     * else arrived by hand, and a hand-edited 2 that silently became the default would be a file that lies
     * about what it holds. A value back at the default removes the field, like every other setting here.
     */
    public static void setDim(HudElement element, double next) {
        Entry before = entry(element);
        double clamped = Math.min(1.0, Math.max(0.0, next));
        put(element, new Entry(before.x(), before.y(), before.on(),
                clamped == element.defaultDim() ? null : clamped, before.anchor()));
    }

    /**
     * Remembers what a stored position is measured from and writes it.
     *
     * <p>Coerced rather than refused when the element has no panel to be measured from: only the
     * book's button supports the inventory origin, so anything else asking for it arrived by hand,
     * and a file claiming the pins sit relative to an inventory corner would be a file that lies
     * about what it holds. A value back at the default removes the field, like every other setting
     * here.
     */
    public static void setOrigin(HudElement element, HudElement.Origin next) {
        Entry before = entry(element);
        HudElement.Origin kept = element.supportsOrigin(next) ? next : element.defaultOrigin();
        String stored = kept == null || kept == element.defaultOrigin() ? null : kept.id();
        put(element, new Entry(before.x(), before.y(), before.on(), before.dim(), stored));
    }

    /**
     * Puts one element back to everything it shipped with, and writes that.
     *
     * <p>Removing the entry reveals the effective default, so resetting the book's button with that
     * sidebar around lands it right of the column rather than under it.
     */
    public static void resetElement(HudElement element) {
        entries.remove(element);
        save();
    }

    /**
     * Every element the player has changed, as the file holds it.
     *
     * <p>Exposed because a setting with no way to ask what is in it is hard to believe: the test that asserts
     * the file's format asks here.
     */
    public static Map<HudElement, Entry> changed() {
        return Map.copyOf(entries);
    }

    // ------------------------------------------------------------------
    // Persistence
    // ------------------------------------------------------------------

    /** Reads the layout from the platform's config directory. Called once by each loader's client. */
    public static void loadFromConfig() {
        Path path = null;
        try {
            path = ArmatureApi.platform().configDir(Constants.MOD_ID).resolve(FILE_NAME);
        }
        catch (RuntimeException e) {
            // Reachable only if this runs before the platform is installed, which is a caller ordering
            // fault rather than a player's. The shipped layout is the working default either way.
            Constants.LOG.warn("tenet: the platform layer was not ready, so the HUD layout was not read."
                    + " Every element keeps its default.", e);
        }

        if (path == null) {
            return;
        }
        load(path);
    }

    /**
     * Reads the layout from one file.
     *
     * <p>A missing file is a first run and gets no message; a file that is there and cannot be read gets one
     * that names it. Neither stops the client, and neither moves anything.
     */
    public static void load(Path path) {
        // Reset first, and the order is not cosmetic: `reset` forgets the file as well as the entries, so a
        // version that assigned the path first and reset after it left this feature holding a path it had
        // forgotten -- every change still worked and nothing was ever written.
        reset();
        file = path;

        if (Files.isRegularFile(path)) {
            try {
                entries = new EnumMap<>(parse(Files.readString(path, StandardCharsets.UTF_8)));
            }
            catch (IOException | RuntimeException e) {
                Constants.LOG.warn("tenet: {} could not be read, so every HUD element keeps its default."
                        + " Deleting the file will stop this message.", path, e);
            }
        }
    }

    /**
     * Parses the file's text into the elements it changed.
     *
     * @throws com.google.gson.JsonSyntaxException if the text is not JSON at all; {@link #load} catches it
     */
    public static Map<HudElement, Entry> parse(String json) {
        Map<HudElement, Entry> read = new EnumMap<>(HudElement.class);

        JsonObject root = JsonParser.parseString(json).getAsJsonObject();
        if (!root.has("elements") || !root.get("elements").isJsonObject()) {
            return read;
        }

        for (Map.Entry<String, JsonElement> stored : root.getAsJsonObject("elements").entrySet()) {
            HudElement element = HudElement.named(stored.getKey());
            JsonElement value = stored.getValue();
            if (element == null || value == null || !value.isJsonObject()) {
                continue;
            }
            JsonObject entry = value.getAsJsonObject();
            Entry parsed = read(element, entry);
            if (parsed != null && !parsed.empty()) {
                read.put(element, parsed);
            }
        }
        return read;
    }

    /**
     * One entry, or null when a field in it is the wrong type.
     *
     * <p><b>Field by field, and one wrong field costs the entry.</b> That is the granularity
     * {@code panelWidths} chose for the same reason: this file is hand-edited, so a typo should cost the
     * thing it is in and not everything beside it -- and half-applying an entry, keeping the y of a pair whose
     * x was nonsense, would leave an element somewhere nobody asked for, which is harder to explain than the
     * default.
     */
    private static Entry read(HudElement element, JsonObject entry) {
        Integer x = null;
        Integer y = null;
        Boolean on = null;
        Double dim = null;
        String anchor = null;

        if (entry.has("x")) {
            JsonElement value = entry.get("x");
            if (!isNumber(value)) {
                return null;
            }
            x = value.getAsInt();
        }
        if (entry.has("y")) {
            JsonElement value = entry.get("y");
            if (!isNumber(value)) {
                return null;
            }
            y = value.getAsInt();
        }
        if (entry.has("on")) {
            JsonElement value = entry.get("on");
            if (value == null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isBoolean()) {
                return null;
            }
            on = value.getAsBoolean();
        }
        if (entry.has("dim")) {
            JsonElement value = entry.get("dim");
            if (!isNumber(value)) {
                return null;
            }
            dim = value.getAsDouble();
        }
        if (entry.has("anchor")) {
            JsonElement value = entry.get("anchor");
            if (value == null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isString()) {
                return null;
            }
            anchor = value.getAsString();
        }
        return new Entry(x, y, on, dim, anchor);
    }

    private static boolean isNumber(JsonElement value) {
        return value != null && value.isJsonPrimitive() && value.getAsJsonPrimitive().isNumber();
    }

    /**
     * Writes the file. Answer given rather than the default so a test can assert the format.
     *
     * <p>Elements are written in the enum's own order, so two clients that moved the same things write
     * byte-identical files and a diff of two of them is a diff of the choices rather than of iteration order.
     * An empty entry is written as nothing at all: {@link #setPosition}, {@link #setOn} and
     * {@link #setDim} are where a value returning to its default stops being stored, and this writer's job
     * is to say what it was handed.
     */
    public static String write(Map<HudElement, Entry> written) {
        JsonObject elements = new JsonObject();
        for (HudElement element : HudElement.values()) {
            Entry entry = written == null ? null : written.get(element);
            if (entry == null || entry.empty()) {
                continue;
            }
            JsonObject stored = new JsonObject();
            if (entry.x() != null) {
                stored.addProperty("x", entry.x());
            }
            if (entry.y() != null) {
                stored.addProperty("y", entry.y());
            }
            if (entry.on() != null) {
                stored.addProperty("on", entry.on());
            }
            if (entry.dim() != null) {
                stored.addProperty("dim", entry.dim());
            }
            if (entry.anchor() != null) {
                stored.addProperty("anchor", entry.anchor());
            }
            elements.add(element.id(), stored);
        }

        JsonObject root = new JsonObject();
        root.add("elements", elements);
        return root.toString();
    }

    /**
     * The file this client reads and writes, or null before {@link #loadFromConfig} has run.
     *
     * <p>Exposed because a setting with no way to ask where it lives is a setting that is hard to believe:
     * the editor that changes it and the file a person edits have to be the same file.
     */
    public static Path file() {
        return file;
    }

    /** Forgets the layout and the file. For a test, and for a client leaving a world it never owned. */
    public static void reset() {
        entries = new EnumMap<>(HudElement.class);
        file = null;
    }

    // ------------------------------------------------------------------

    private static Entry entry(HudElement element) {
        Entry found = entries.get(element);
        return found == null ? new Entry(null, null, null, null, null) : found;
    }

    private static void put(HudElement element, Entry entry) {
        if (entry.empty()) {
            entries.remove(element);
        }
        else {
            entries.put(element, entry);
        }
        save();
    }

    private static void save() {
        if (file == null) {
            // No path means no platform: in a test, or in a client whose config directory could not be
            // resolved. The layout still works for this session, which is the useful half.
            return;
        }
        try {
            // Through JsonWrite, which creates the directory and writes by rename -- so a crash mid-save
            // leaves the previous layout rather than a half-written file the next load has to guess at.
            JsonWrite.atomically(file, write(entries));
        }
        catch (IOException e) {
            Constants.LOG.warn("tenet: the HUD layout could not be written to {}", file, e);
        }
    }
}
