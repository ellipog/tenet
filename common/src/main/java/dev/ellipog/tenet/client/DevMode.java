package dev.ellipog.tenet.client;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import dev.ellipog.armature.api.ArmatureApi;
import dev.ellipog.armature.api.data.JsonWrite;
import dev.ellipog.tenet.Constants;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.EnumMap;
import java.util.Locale;
import java.util.Map;

/**
 * This client's own preferences: whether tools may be drawn, and how the book prefers to be shaped.
 *
 * <h2>Why a mode rather than a set of buttons</h2>
 *
 * <p>Because the quest book is content, and a theme picker is a tool. The two rows that used to sit at
 * the foot of the book's sidebar were removed in the theme round for exactly that reason, and this is
 * where they went: a pack author needs a mode, not a permanent set of controls on somebody else's
 * screen. The legacy screen has the same shape for the same reason.
 *
 * <h2>Why the flag is Tenet's rather than Armature's</h2>
 *
 * <p>Because what it gates is this mod's screens. Armature owns the things a tool *operates* -- the
 * appearance, the themes, the token registry, the file format -- and every one of those already exists
 * and is asserted. What did not exist was a place to put the controls, and the editor for quests that
 * will follow them is Tenet's, so the mode is Tenet's too. The argument that moved the picker *out*
 * of Armature's toolbox and into a setting applies in reverse here: a control wrapped around an
 * operation that already lives in the library can be moved without anything being rewritten.
 *
 * <h2>The file, and what happens when it is wrong</h2>
 *
 * <p>{@code config/tenet/client.json}, a client preference beside Armature's own
 * {@code appearance.json}, and the same tolerance in the same places: a missing file is a first run and
 * is worth no words at all, and a file that exists and cannot be read is worth one that says where it
 * is. The mode flag defaults to off, because a mode that changes what a screen shows should never be on
 * because a file was unreadable. Beside it ride two on-by-default switches: the editor's snap, which
 * changes where a dragged node lands, and the sidebar's chapter progress bars, which change what a
 * chapter row shows. Both are the tidy direction, so a file that does not name them leaves them on.
 *
 * <h2>The depth, which is a third kind of flag again</h2>
 *
 * <p>{@link #advanced} is not the mode and not a switch: it says <b>how much of the editor to show</b>,
 * where {@link #on()} says whether there is one at all. Normal is the default and the basic set; Advanced
 * is today's full view. It lives here rather than beside the code that reads it because it is this
 * client's preference, in this client's file -- and it follows the two switches' own rule rather than the
 * mode flag's: a file that does not name it reads as <b>off</b>, because showing less is the direction
 * that cannot lie about a mode nobody remembers turning on, and one press brings the rest back.
 * {@code Advanced} is what decides what every editor menu contains; see {@code client.dev.Advanced} for
 * the cut itself, which is one table and one predicate rather than a branch per screen.
 *
 * <h2>The panels' own settings, and the one that left</h2>
 *
 * <p>How wide each panel is and whether a child folds into its parent are the same kind of thing the two
 * switches above are: preferences about how this client draws, read before anything can draw it.
 * <b>Neither is gated on {@link #on()}</b>, which is the rule {@code snap} and {@code progress} already
 * follow and which is worth keeping: a player who never turns developer mode on still has a layout, and
 * it is theirs rather than the pack's.
 *
 * <p><b>There used to be a third: whether an overlay is a docked column or a centred card.</b> It is gone,
 * because the card is gone -- every kind occupies a rail now, including the reward question. The key is
 * still tolerated on read, exactly as any unknown key is: a file written when the switch existed must not
 * cost the player the settings beside it, and a value that no longer decides anything is safer ignored
 * than reported. Nothing writes it, so it disappears the first time any setting changes.
 *
 * <h2>One width per kind, because one width was two answers</h2>
 *
 * <p>{@code panelWidths} is a diff from the defaults rather than a full table: a kind the player has never
 * dragged has no entry, and a drag back to the default removes the entry it had. That is
 * {@code ThemeFiles}' own shape and its own reason -- a file that restates every value cannot be read for
 * what the player changed -- and it means the file grows only where somebody made a choice.
 *
 * <h2>The chapter list's own four, and why they are fields rather than a fourth table</h2>
 *
 * <p>{@code sidebarWidth} is a diff like the table above: absent means today's 156-pixel column, and
 * only a dragged width is written. {@code sidebarFloating} says whether the list sits as a box on
 * the canvas rather than docked down the panel's left; {@code sidebarPinned} whether a floating
 * box stays open; {@code sidebarCollapsed} whether it has slid away to its sliver. Each absent
 * means a floating pinned expanded box — docking is a switch in the settings card, not the
 * absence of a key.
 * All four are read before anything draws, and none is gated on {@link #on()}: a player who never
 * turns developer mode on still has a layout, and it is theirs.
 *
 * <h2>The intro's own flag, and why it is one boolean</h2>
 *
 * <p>{@code guideSeen} says whether the first-run intro has been finished or dismissed for good.
 * Absent means unseen, so it is written only when set — the same diff shape as the list's own
 * switches above. The tour's rung-by-rung progress is deliberately <i>not</i> here: it names a
 * server's book and would go stale across one, so it lives in the tour and dies on disconnect.
 */
public final class DevMode {

    /** The client preference this mod writes. Named for the side it is read on. */
    public static final String FILE_NAME = "client.json";

    private static boolean on;
    private static boolean snap = true;
    private static boolean progress = true;
    private static boolean advanced;
    private static Map<PanelKind, Integer> panelWidths = new EnumMap<>(PanelKind.class);
    private static PanelStack.Fold panelFold = PanelStack.Fold.AUTO;
    private static int sidebarWidth = BookGeometry.SIDEBAR_WIDTH;
    private static boolean sidebarFloating = true;
    private static boolean sidebarPinned = true;
    private static boolean sidebarCollapsed;
    private static boolean guideSeen;
    private static Path file;

    /**
     * What one file says, as every flag it holds.
     *
     * <p>A record rather than several parses of one file: {@code load} reads once and takes every answer
     * from the same tree, so the fields cannot disagree about what was on disk.
     *
     * @param dev         whether tools may be drawn
     * @param snap        whether a dragged node lands on the grid; a file that does not say says yes
     * @param progress    whether chapter rows draw their completion bar; likewise yes by default
     * @param advanced    whether every editor menu shows everything; a file that does not say says no
     * @param panelWidths the widths the player has chosen, per kind, keyed only by kinds they have dragged
     * @param panelFold   what the player asked a panel's second column to do
     * @param sidebarWidth the chapter list's expanded width, clamped to its own range on the way in
     * @param sidebarFloating whether the list sits as a box on the canvas; a file that does not
     *     say says floating, like a missing file
     * @param sidebarPinned whether a floating box stays open; docked, there is nothing to pin
     * @param sidebarCollapsed whether the list has slid away to its sliver; docked, always expanded
     * @param guideSeen whether the first-run intro has been finished or dismissed for good
     */
    public record Parsed(boolean dev, boolean snap, boolean progress, boolean advanced,
            Map<PanelKind, Integer> panelWidths, PanelStack.Fold panelFold, int sidebarWidth,
            boolean sidebarFloating, boolean sidebarPinned, boolean sidebarCollapsed,
            boolean guideSeen) {
    }

    private DevMode() {
    }

    /** Whether tools may be drawn on this client's screens. Off unless a file said otherwise. */
    public static boolean on() {
        return on;
    }

    /** Turns the mode on or off and writes the choice. */
    public static void setOn(boolean next) {
        on = next;
        save();
    }

    /** The same, and answers with the state it left behind, so a button can label itself from one call. */
    public static boolean toggle() {
        setOn(!on);
        return on;
    }

    // ------------------------------------------------------------------
    // The editor's grid switch
    // ------------------------------------------------------------------

    /**
     * Whether the editor snaps a moved node to {@link BookGeometry#SNAP_GRID}. On by default, because the
     * grid is what keeps a dragged position a number an author would have typed; Alt asks for the
     * one free placement and does not need a setting flipped to get it.
     */
    public static boolean snap() {
        return snap;
    }

    /** Turns the grid on or off and writes the choice. */
    public static void setSnap(boolean next) {
        snap = next;
        save();
    }

    // ------------------------------------------------------------------
    // The sidebar's progress bars
    // ------------------------------------------------------------------

    /**
     * Whether chapter rows draw their completion bar. On by default: the bar is the sidebar's quiet
     * answer to "how far through is this chapter", and a preference that defaulted off would hide a
     * feature behind a switch nobody knows to look for.
     */
    public static boolean progress() {
        return progress;
    }

    /** Turns the bars on or off and writes the choice. */
    public static void setProgress(boolean next) {
        progress = next;
        save();
    }

    // ------------------------------------------------------------------
    // The editor's depth
    // ------------------------------------------------------------------

    /**
     * Whether every editor menu shows everything, rather than the basic set.
     *
     * <p>Off by default, and <b>not gated on {@link #on()}</b>, which is the rule {@code snap} and
     * {@code progress} already follow: the mode says whether the tools exist, and this says how much of
     * them to draw. A reader who is not editing has no rows to trim either way, so the flag costs nothing
     * while edit mode is off -- and it keeps its value across a turn of the mode, which is what an author
     * toggling Edit off and on again expects.
     */
    public static boolean advanced() {
        return advanced;
    }

    /** Turns the depth on or off and writes the choice. */
    public static void setAdvanced(boolean next) {
        advanced = next;
        save();
    }

    /** The same, and answers with the state it left behind, so a button can label itself from one call. */
    public static boolean toggleAdvanced() {
        setAdvanced(!advanced);
        return advanced;
    }

    // ------------------------------------------------------------------
    // The panels
    // ------------------------------------------------------------------

    /**
     * How wide this kind's panel is drawn, in GUI pixels.
     *
     * <p>A kind the player has never dragged answers with {@link PanelStack#defaultWidth}, which is the
     * width its own layout was drawn against -- a list at 260, a table at 660, everything that carries prose
     * at 340. The clamp is applied where the value enters the map rather than here, so this is one lookup:
     * a hand-edited file is made legal once, on the way in, and every later reader gets the same number.
     */
    public static int panelWidth(PanelKind kind) {
        if (kind == null || kind == PanelKind.NONE) {
            return 0;
        }
        return panelWidths.getOrDefault(kind, PanelStack.defaultWidth(kind));
    }

    /**
     * Remembers a width and writes it.
     *
     * <p><b>A width back at its default is removed from the map rather than stored beside it</b>, which is
     * what makes this file a diff: the entry's absence already says "the default", so storing it as well
     * would be two spellings of one state and the file would grow a line every time somebody dragged a panel
     * and put it back. See the class comment -- {@code ThemeFiles} is the same shape for the same reason.
     */
    public static void setPanelWidth(PanelKind kind, int next) {
        if (kind == null || kind == PanelKind.NONE) {
            return;
        }
        int clamped = PanelStack.clampWidth(next, kind);
        if (clamped == PanelStack.defaultWidth(kind)) {
            panelWidths.remove(kind);
        }
        else {
            panelWidths.put(kind, clamped);
        }
        save();
    }

    /**
     * Every width the player has chosen, as the file holds it.
     *
     * <p>Exposed because a setting with no way to ask what is in it is a setting that is hard to believe:
     * the test that asserts the file's format asks here, and so does the screen when it builds a rail. A
     * lookup per kind is {@link #panelWidth}, which is what a caller with a kind in hand wants.
     */
    public static Map<PanelKind, Integer> panelWidths() {
        return Map.copyOf(panelWidths);
    }

    /** What the player asked a panel's second column to do. */
    public static PanelStack.Fold panelFold() {
        return panelFold;
    }

    /** Remembers that choice and writes it. */
    public static void setPanelFold(PanelStack.Fold next) {
        panelFold = next == null ? PanelStack.Fold.AUTO : next;
        save();
    }

    // ------------------------------------------------------------------
    // The chapter list
    // ------------------------------------------------------------------

    /**
     * How wide the expanded chapter list is drawn, in GUI pixels.
     *
     * <p>The player's width, defaulting to {@link BookGeometry#SIDEBAR_WIDTH} — today's column —
     * so a file that never heard of it draws what it always drew. The clamp is applied where the
     * value enters, like {@link #panelWidth}'s, so a hand-edited file is made legal once, on the
     * way in, and every later reader gets the same number.
     */
    public static int sidebarWidth() {
        return sidebarWidth;
    }

    /**
     * Remembers a width and writes it.
     *
     * <p>A width back at its default is not stored: the absence of the key already says "today's
     * column", so storing it as well would be two spellings of one state. See
     * {@link #setPanelWidth} for the same rule on panels.
     */
    public static void setSidebarWidth(int next) {
        sidebarWidth = BookGeometry.clampSidebarWidth(next);
        save();
    }

    /**
     * Whether the chapter list sits as a box on the canvas rather than docked down the panel's
     * left. Floating by default: a new book opens on the map beside an open box, and docking is
     * the settings card's switch rather than the absence of a key. The mode lives in the settings
     * card rather than the header, because the header is
     * the book's busiest row and the choice is made once, not pressed daily.
     */
    public static boolean isSidebarFloating() {
        return sidebarFloating;
    }

    /** Floats the list onto the canvas, or docks it back into the panel, and writes the choice. */
    public static void setSidebarFloating(boolean next) {
        sidebarFloating = next;
        save();
    }

    /**
     * Whether a floating box stays open. Pinned by default, so a freshly floated list does not
     * slide away from under the hand that floated it; unpinned, it slides to its sliver when the
     * pointer leaves it, like a peek that never ends. Docked, there is nothing to pin and this
     * decides nothing.
     */
    public static boolean isSidebarPinned() {
        return sidebarPinned;
    }

    /** Pins the floating box open, or lets it slide away, and writes the choice. */
    public static void setSidebarPinned(boolean next) {
        sidebarPinned = next;
        save();
    }

    /** Whether the list has slid away to its sticking-out sliver. Expanded by default. */
    public static boolean isSidebarCollapsed() {
        return sidebarCollapsed;
    }

    /** Slides the list away to its sliver, or brings it back, and writes the choice. */
    public static void setSidebarCollapsed(boolean next) {
        sidebarCollapsed = next;
        save();
    }

    /**
     * Whether the first-run intro has been finished or dismissed for good.
     *
     * <p>Off unless a dismissal said otherwise — and {@link #parse} reads a file that never
     * heard of it as off, so an author updating from before the intro existed meets it once.
     * Not gated on {@link #on()}: the flag outlives edit mode the way the widths do, because a
     * dismissal made outside edit mode is still a dismissal.
     */
    public static boolean hasSeenGuide() {
        return guideSeen;
    }

    /** Remembers that choice and writes it. */
    public static void setGuideSeen(boolean next) {
        guideSeen = next;
        save();
    }

    /** The four answers together, as the file holds them. */
    public static SidebarState sidebarState() {
        return new SidebarState(sidebarWidth, sidebarFloating, sidebarPinned, sidebarCollapsed);
    }

    // ------------------------------------------------------------------
    // Persistence
    // ------------------------------------------------------------------

    /** Reads the setting from the platform's config directory. Called once by each loader's client. */
    public static void loadFromConfig() {
        Path path = null;
        try {
            path = ArmatureApi.platform().configDir(Constants.MOD_ID).resolve(FILE_NAME);
        }
        catch (RuntimeException e) {
            // Reachable only if this runs before the platform is installed, which would be a caller
            // ordering fault rather than a player's. Off is the working default either way.
            Constants.LOG.warn("tenet: the platform layer was not ready, so developer mode was not read."
                    + " It is off.", e);
        }

        if (path == null) {
            return;
        }
        load(path);
    }

    /**
     * Reads the setting from one file.
     *
     * <p>A missing file is a first run and gets no message; a file that is there and cannot be read gets
     * one that names it. Neither stops the client, and neither turns the mode on.
     */
    public static void load(Path path) {
        // Reset first, and the order is not cosmetic: `reset` clears the file as well as the flags, so a
        // version that assigned the path first and reset after it left this screen holding a path it had
        // forgotten -- every setting still worked and nothing was ever written.
        reset();
        file = path;

        if (Files.isRegularFile(path)) {
            try {
                Parsed read = parse(Files.readString(path, StandardCharsets.UTF_8));
                on = read.dev();
                snap = read.snap();
                progress = read.progress();
                advanced = read.advanced();
                // Filled rather than wrapped: `new EnumMap<>(map)` cannot infer its key type from an
                // empty table and throws, so a file with no dragged widths — which is every file that
                // only names the chapter list — would discard itself into the defaults on every load.
                panelWidths = new EnumMap<>(PanelKind.class);
                panelWidths.putAll(read.panelWidths());
                panelFold = read.panelFold() == null ? PanelStack.Fold.AUTO : read.panelFold();
                sidebarWidth = read.sidebarWidth();
                sidebarFloating = read.sidebarFloating();
                sidebarPinned = read.sidebarPinned();
                sidebarCollapsed = read.sidebarCollapsed();
                guideSeen = read.guideSeen();
            }
            catch (IOException | RuntimeException e) {
                Constants.LOG.warn("tenet: {} could not be read, so developer mode is off. Deleting the"
                        + " file will stop this message.", path, e);
            }
        }
    }

    /**
     * Parses the file's text into every flag.
     *
     * <p>Tolerant rather than strict, and for a stronger reason than Appearance's: this file is a
     * developer's, so it will be hand-edited, and a typo in it should cost a mode that stays off rather
     * than a client that will not start. An unknown field is ignored; a missing one takes its default --
     * which for {@code snap} and {@code progress} is on, so a file written before either existed reads as
     * the behaviour it was already getting, and which for {@code advanced} is off, so a file written
     * before <i>it</i> existed reads as the basic editor rather than as the full one. The asymmetry is the
     * point rather than an oversight: a switch nobody asked for should be on, and a depth nobody chose
     * should be the shallow end.
     *
     * <h2>The width table, and the scalar it replaced</h2>
     *
     * <p>{@code panelWidths} is read entry by entry, and <b>every way an entry can be wrong costs that
     * entry and nothing else</b>: a name that is not a kind, a {@code NONE}, a value that is not a number,
     * and a number outside the kind's own range are each skipped or clamped rather than thrown. A whole
     * table that cannot be read is therefore the defaults, which is a layout rather than a crash.
     *
     * <p>{@code panelWidth} is the one scalar this file used to hold, and it <b>seeds every kind the table
     * does not name</b> instead of being ignored. That ordering matters and is the only place it does: a
     * player who had chosen 300 pixels for every panel and then opened a rewards inbox was already getting
     * the wide kind's own floor rather than 300, and seeding through {@link PanelStack#clampWidth} is what
     * keeps that true for the kinds the new table has no entry for. Written the other way round -- the table
     * winning wherever it had anything -- a file that named one kind would quietly reset the rest.
     *
     * @throws com.google.gson.JsonSyntaxException if the text is not JSON at all; {@link #load} catches it
     */
    public static Parsed parse(String json) {
        JsonObject root = JsonParser.parseString(json).getAsJsonObject();
        Map<PanelKind, Integer> widths = new EnumMap<>(PanelKind.class);

        if (root.has("panelWidths") && root.get("panelWidths").isJsonObject()) {
            for (Map.Entry<String, JsonElement> entry : root.getAsJsonObject("panelWidths").entrySet()) {
                PanelKind kind = kindNamed(entry.getKey());
                JsonElement value = entry.getValue();
                if (kind == null || value == null || !value.isJsonPrimitive()
                        || !value.getAsJsonPrimitive().isNumber()) {
                    continue;
                }
                widths.put(kind, PanelStack.clampWidth(value.getAsInt(), kind));
            }
        }

        if (root.has("panelWidth") && root.get("panelWidth").isJsonPrimitive()) {
            int legacy = root.get("panelWidth").getAsInt();
            for (PanelKind kind : PanelKind.values()) {
                if (kind != PanelKind.NONE && !widths.containsKey(kind)) {
                    widths.put(kind, PanelStack.clampWidth(legacy, kind));
                }
            }
        }

        return new Parsed(
                root.has("dev") && root.get("dev").getAsBoolean(),
                !root.has("snap") || root.get("snap").getAsBoolean(),
                !root.has("progress") || root.get("progress").getAsBoolean(),
                root.has("advanced") && root.get("advanced").getAsBoolean(),
                Map.copyOf(widths),
                root.has("panelFold")
                        ? PanelStack.foldOf(root.get("panelFold").getAsString())
                        : PanelStack.Fold.AUTO,
                root.has("sidebarWidth") && root.get("sidebarWidth").isJsonPrimitive()
                        && root.getAsJsonPrimitive("sidebarWidth").isNumber()
                        ? BookGeometry.clampSidebarWidth(root.get("sidebarWidth").getAsInt())
                        : BookGeometry.SIDEBAR_WIDTH,
                // A file that never heard of the list says none of those, and floats like a new
                // one: floating pinned expanded is what absence already says for each key below.
                !root.has("sidebarFloating") || booleanOf(root, "sidebarFloating", true),
                !root.has("sidebarPinned") || booleanOf(root, "sidebarPinned", true),
                root.has("sidebarCollapsed") && booleanOf(root, "sidebarCollapsed", false),
                // Absent means unseen: a file written before the intro existed belongs to an
                // author who never met it, and showing it once is the direction that teaches.
                root.has("guideSeen") && booleanOf(root, "guideSeen", false));
    }

    /**
     * One field as a boolean, or the default when the file does not say or says something else.
     *
     * <p>A wrong type falls back rather than failing the file: a typo should cost a preference,
     * not the settings beside it.
     */
    private static boolean booleanOf(JsonObject root, String field, boolean fallback) {
        if (!root.has(field) || !root.get(field).isJsonPrimitive()
                || !root.getAsJsonPrimitive(field).isBoolean()) {
            return fallback;
        }
        return root.get(field).getAsBoolean();
    }

    /**
     * The kind a key names, or null when it names none this build has.
     *
     * <p>Folded to upper case before the lookup, because this file is hand-edited and {@code "quest"} is a
     * spelling a person will write. {@code NONE} is refused here rather than at the caller: it is the
     * absence of a panel, so a width for it is not a small number, it is a key with no subject.
     */
    private static PanelKind kindNamed(String name) {
        if (name == null) {
            return null;
        }
        try {
            PanelKind kind = PanelKind.valueOf(name.trim().toUpperCase(Locale.ROOT));
            return kind == PanelKind.NONE ? null : kind;
        }
        catch (IllegalArgumentException e) {
            // A kind this build does not have: written by a newer one, or misspelt by hand. It costs that
            // entry, and the kind keeps its default.
            return null;
        }
    }

    /** Whether the mode flag alone is set. See {@link #parse} for every field. */
    public static boolean read(String json) {
        return parse(json).dev();
    }

    /**
     * Writes the file. Answer given rather than the default so a test can assert the format.
     *
     * <p>The width table is written in the enum's own order, so two clients that dragged the same panels
     * write byte-identical files and a diff of two of them is a diff of the choices rather than of iteration
     * order. Each width goes through {@link PanelStack#clampWidth} on the way out as well as on the way in,
     * which is not belt-and-braces: this is a public method taking a map, and a caller that hands it a number
     * the kind cannot be drawn at would otherwise write a file that reads back as a different number.
     *
     * <p>An entry that <i>is</i> its kind's default is written as given rather than dropped: removing it is
     * {@link #setPanelWidth}'s job, where the caller is a drag that just landed on the default. A file-writing
     * function that silently edited its argument's meaning would be the wrong place for that rule.
     */
    public static String write(boolean dev, boolean snap, boolean progress, boolean advanced,
            Map<PanelKind, Integer> widths, PanelStack.Fold fold) {
        return write(dev, snap, progress, advanced, widths, fold, BookGeometry.SIDEBAR_WIDTH, true,
                true, false);
    }

    /**
     * Writes the file, with the chapter list's four answers. Answer given rather than the default
     * so a test can assert the format.
     *
     * <p>Each is written only when it differs from the defaults, so a file that never heard of
     * the list stays byte-identical to what it always wrote: the absence of each key already says
     * "floating, pinned, expanded, today's width". A width that is not its default but clamps to
     * one is written clamped, because the file must read back as what is drawn.
     */
    public static String write(boolean dev, boolean snap, boolean progress, boolean advanced,
            Map<PanelKind, Integer> widths, PanelStack.Fold fold, int sidebarWidth,
            boolean sidebarFloating, boolean sidebarPinned, boolean sidebarCollapsed) {
        return write(dev, snap, progress, advanced, widths, fold, sidebarWidth, sidebarFloating,
                sidebarPinned, sidebarCollapsed, false);
    }

    /**
     * Writes the file, with the chapter list's four answers and whether the intro has been seen.
     *
     * <p>The intro flag is written only when it is set, like the list's own three switches: the
     * absence of the key already says "unseen", so a file that never met the guide stays
     * byte-identical to what it always wrote.
     */
    public static String write(boolean dev, boolean snap, boolean progress, boolean advanced,
            Map<PanelKind, Integer> widths, PanelStack.Fold fold, int sidebarWidth,
            boolean sidebarFloating, boolean sidebarPinned, boolean sidebarCollapsed,
            boolean guideSeen) {
        JsonObject root = new JsonObject();
        root.addProperty("dev", dev);
        root.addProperty("snap", snap);
        root.addProperty("progress", progress);
        root.addProperty("advanced", advanced);
        JsonObject table = new JsonObject();
        for (PanelKind kind : PanelKind.values()) {
            Integer width = widths == null ? null : widths.get(kind);
            if (kind != PanelKind.NONE && width != null) {
                table.addProperty(kind.name(), PanelStack.clampWidth(width, kind));
            }
        }
        root.add("panelWidths", table);
        root.addProperty("panelFold", PanelStack.foldWord(fold));
        int listWidth = BookGeometry.clampSidebarWidth(sidebarWidth);
        if (listWidth != BookGeometry.SIDEBAR_WIDTH) {
            root.addProperty("sidebarWidth", listWidth);
        }
        if (!sidebarFloating) {
            root.addProperty("sidebarFloating", false);
        }
        if (!sidebarPinned) {
            root.addProperty("sidebarPinned", false);
        }
        if (sidebarCollapsed) {
            root.addProperty("sidebarCollapsed", true);
        }
        if (guideSeen) {
            root.addProperty("guideSeen", true);
        }
        return root.toString();
    }

    /**
     * The file this client reads and writes, or null before {@link #loadFromConfig} has run.
     *
     * <p>Exposed because a setting with no way to ask where it lives is a setting that is hard to
     * believe: the screen that changes it and the file a person edits have to be the same file.
     */
    public static Path file() {
        return file;
    }

    /** Forgets the settings and the file. For a test, and for a client leaving a world it never owned. */
    public static void reset() {
        on = false;
        snap = true;
        progress = true;
        advanced = false;
        panelWidths = new EnumMap<>(PanelKind.class);
        panelFold = PanelStack.Fold.AUTO;
        sidebarWidth = BookGeometry.SIDEBAR_WIDTH;
        sidebarFloating = true;
        sidebarPinned = true;
        sidebarCollapsed = false;
        guideSeen = false;
        file = null;
    }

    private static void save() {
        if (file == null) {
            // No path means no platform: in a test, or in a client whose config directory could not be
            // resolved. The mode still works for this session, which is the useful half.
            return;
        }
        try {
            // Through JsonWrite, which creates the directory and writes by rename -- so a crash mid-save
            // leaves the previous flags rather than a half-written file the next load has to guess at.
            // This file's whole contract is that every way of being wrong reads as the safe direction,
            // and a truncated file is the one way that could not be honoured. See JsonWrite.
            JsonWrite.atomically(file,
                    write(on, snap, progress, advanced, panelWidths, panelFold, sidebarWidth,
                            sidebarFloating, sidebarPinned, sidebarCollapsed, guideSeen));
        }
        catch (IOException e) {
            Constants.LOG.warn("tenet: developer mode could not be written to {}", file, e);
        }
    }
}
