package dev.ellipog.tenet.client.hud;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import dev.ellipog.armature.api.ArmatureApi;
import dev.ellipog.armature.api.data.JsonWrite;
import dev.ellipog.tenet.Constants;
import dev.ellipog.tenet.client.ClientQuestCache;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Which quests this player has pinned to the HUD, and in what order: {@code config/tenet/pinned.json}.
 *
 * <h2>Its own file, and why not a corner of {@code hud.json}</h2>
 *
 * <p>{@code hud.json} is a <b>layout</b>: a diff from the defaults, one entry per element the player has
 * moved or switched, and a value put back to its default leaves the file. This is <b>content</b>: an ordered
 * list of quests, where "nothing here" and "here is where I put it" are different kinds of fact and an
 * empty list is a real answer rather than an absence. Mixing the two would give one file two shapes and one
 * reader two questions, which is the fault {@code HudSettings}'s own note about five files was written to
 * avoid -- so a pin list is a sixth area and gets its own file.
 *
 * <h2>The order is pin order, and every pin is drawn the same way</h2>
 *
 * <p>Pinning a quest puts it first, and the HUD draws the list top to bottom in that order -- each quest its
 * own box, with its own tasks. There is no focus and no front to bring forward to, so pinning something
 * already pinned changes nothing, and the book's menu offers Pin or Unpin and nothing else.
 *
 * <h2>Why a stale id is harmless, and why it is pruned anyway</h2>
 *
 * <p>This file is per client and not per world, so a pin can name a quest the server being played does not
 * have -- or one a pack edit has since removed. Nothing breaks: the panel skips an id the tree does not
 * hold, and the skip does not spend one of {@link #MAX_PINS} drawn slots. But an unbounded list of dead ids
 * would grow for as long as somebody plays servers, so {@link #tick} drops them once per tree revision --
 * and <b>only</b> against a tree that is actually there, because an empty cache is a disconnect and pruning
 * against it would erase every pin on the way out of a world.
 *
 * <h2>Every way of being wrong</h2>
 *
 * <p>The same tolerance as the file beside it: a pin that is not a string, a blank one, a duplicate and a
 * "pins" that is not a list each cost what they should -- duplicates and blanks cost nothing, the others cost
 * the list -- while text that is not JSON at all costs the whole file, which is no pins. None of it stops
 * the client.
 */
public final class PinnedQuests {

    /** The client preference this feature writes. Named for what it holds. */
    public static final String FILE_NAME = "pinned.json";

    /**
     * How many quests may be pinned at once.
     *
     * <p>A cap rather than a scroll: the panel is drawn on a world HUD and cannot be scrolled, so a seventh
     * pin would be a seventh line on somebody's screen. Six is the drawer's own count -- the panel's height
     * for a full list is about a third of a 1080p window at GUI scale 2, which is as much as a HUD element
     * may take. Kept here so the store, the panel and the refusal the book shows all read one number.
     */
    public static final int MAX_PINS = 6;

    private static List<String> pins = List.of();

    /**
     * Whether a quest the player is done with leaves the HUD on its own.
     *
     * <p>On unless the player said otherwise: a pin is a promise to watch something, and a quest whose
     * rewards are all collected has nothing left to watch. The pin itself stays -- unpinning is the
     * player's own act, and an auto-hide that deleted pins would spend what somebody placed.
     */
    private static boolean hideClaimed = true;

    private static Path file;

    /** The tree revision the pins were last checked against; -1 means "not yet". */
    private static long lastTree = -1L;

    private PinnedQuests() {
    }

    /** The pinned quests, in the order they are drawn. Never null. */
    public static List<String> pinned() {
        return pins;
    }

    /** How many are pinned. */
    public static int count() {
        return pins.size();
    }

    /** Whether this quest is pinned at all. */
    public static boolean isPinned(String questId) {
        return questId != null && pins.contains(questId);
    }

    /**
     * Pins a quest, and writes the list.
     *
     * <p>Pinning something already pinned changes nothing: every pin is drawn the same way, so there is no
     * front to bring forward to, and a call that reordered the stack would move boxes a player had arranged
     * themselves to read. The menu offers Pin or Unpin and nothing else for the same reason.
     *
     * @return false when the list is full and this is a quest not already in it -- nothing is dropped to
     *     make room, and the caller is expected to say so; see {@link #MAX_PINS}
     */
    public static boolean pin(String questId) {
        if (questId == null || questId.isBlank()) {
            return false;
        }
        if (pins.contains(questId)) {
            return true;
        }
        if (pins.size() >= MAX_PINS) {
            return false;
        }
        List<String> next = new ArrayList<>(pins.size() + 1);
        next.add(questId);
        next.addAll(pins);
        put(next);
        return true;
    }

    /** Unpins a quest, and writes the list. Pinned nowhere is not an error: it is the state asked for. */
    public static void unpin(String questId) {
        if (questId == null || !pins.contains(questId)) {
            return;
        }
        List<String> next = new ArrayList<>(pins);
        next.remove(questId);
        put(next);
    }

    /** Whether finished quests hide themselves once their rewards are collected. On by default. */
    public static boolean hideClaimed() {
        return hideClaimed;
    }

    /** Sets the auto-hide and writes it. A value already in force writes nothing new. */
    public static void setHideClaimed(boolean next) {
        if (hideClaimed == next) {
            return;
        }
        hideClaimed = next;
        save();
    }

    /**
     * Drops the pins the tree no longer holds, once per tree revision.
     *
     * <p>Called from each loader's client tick, beside the notifier's own.
     *
     * <p><b>An empty tree is not an empty pack.</b> {@code clear()} runs on a disconnect, so a prune that ran
     * against it would erase every pin the moment a player left a world -- and it would do it silently, since
     * the write is the whole of the effect. So the guard is {@link ClientQuestCache#hasTree()}, and the
     * revision is still consumed: a join sends a tree, and the prune happens then.
     */
    public static void tick() {
        long revision = ClientQuestCache.treeRevision();
        if (revision == lastTree) {
            return;
        }
        lastTree = revision;
        if (!ClientQuestCache.hasTree() || pins.isEmpty()) {
            return;
        }
        Set<String> known = new HashSet<>();
        for (ClientQuestCache.Entry entry : ClientQuestCache.entries()) {
            known.add(entry.id());
        }
        List<String> next = new ArrayList<>(pins.size());
        for (String id : pins) {
            // Kept rather than rebuilt from the tree, because the order is the player's: the first is the
            // most recently pinned, and a walk of the cache would sort it by the pack's own.
            if (known.contains(id)) {
                next.add(id);
            }
        }
        if (next.size() != pins.size()) {
            put(next);
        }
    }

    // ------------------------------------------------------------------
    // Persistence
    // ------------------------------------------------------------------

    /** Reads the pins from the platform's config directory. Called once by each loader's client. */
    public static void loadFromConfig() {
        Path path = null;
        try {
            path = ArmatureApi.platform().configDir(Constants.MOD_ID).resolve(FILE_NAME);
        }
        catch (RuntimeException e) {
            // Reachable only if this runs before the platform is installed, which is a caller ordering
            // fault rather than a player's. No pins is the working default either way.
            Constants.LOG.warn("the platform layer was not ready, so the pinned quests were not read."
                    + " Nothing is pinned.", e);
        }

        if (path == null) {
            return;
        }
        load(path);
    }

    /**
     * Reads the pins from one file.
     *
     * <p>A missing file is a first run and gets no message; a file that is there and cannot be read gets one
     * that names it. Neither stops the client, and neither leaves the previous session's pins behind.
     */
    public static void load(Path path) {
        reset();
        file = path;

        if (Files.isRegularFile(path)) {
            try {
                String text = Files.readString(path, StandardCharsets.UTF_8);
                pins = parse(text);
                hideClaimed = parseHideClaimed(text);
            }
            catch (IOException | RuntimeException e) {
                Constants.LOG.warn("{} could not be read, so nothing is pinned. Deleting the file will"
                        + " stop this message.", path, e);
            }
        }
    }

    /**
     * Parses the file's text into the pinned ids.
     *
     * <p>Fields are read one at a time and a wrong one costs the list rather than the client, which is
     * {@code HudSettings}'s granularity one kind of file over. Duplicates and blanks cost nothing: a repeated
     * id is the same pin twice, and a hand-edited empty string names no quest.
     *
     * @throws com.google.gson.JsonSyntaxException if the text is not JSON at all; {@link #load} catches it
     */
    public static List<String> parse(String json) {
        JsonObject root = JsonParser.parseString(json).getAsJsonObject();
        List<String> read = new ArrayList<>();
        if (!root.has("pins")) {
            return List.of();
        }
        JsonElement stored = root.get("pins");
        if (stored == null || !stored.isJsonArray()) {
            return List.of();
        }
        JsonArray array = stored.getAsJsonArray();
        for (JsonElement element : array) {
            if (element == null || !element.isJsonPrimitive()
                    || !element.getAsJsonPrimitive().isString()) {
                // A number or an object where an id belongs costs that entry and nothing else. Skipped
                // rather than refusing the list, because the rest of it is still somebody's screen.
                continue;
            }
            String id = element.getAsString().trim();
            if (id.isEmpty() || read.contains(id)) {
                continue;
            }
            read.add(id);
            if (read.size() >= MAX_PINS) {
                break;
            }
        }
        return List.copyOf(read);
    }

    /**
     * The auto-hide the file asks for: on unless it says {@code false}.
     *
     * <p>Absent, mistyped or unreadable is the default rather than a refusal: this flag refines the list
     * rather than replacing it, and a hand edit that meant to change the pins must not silently change
     * the hiding with it. Anything that is not JSON at all never reaches here -- {@link #load} catches
     * that first, and the default holds.
     */
    static boolean parseHideClaimed(String json) {
        try {
            JsonElement flag = JsonParser.parseString(json).getAsJsonObject().get("hideClaimed");
            if (flag != null && flag.isJsonPrimitive() && flag.getAsJsonPrimitive().isBoolean()) {
                return flag.getAsBoolean();
            }
        }
        catch (RuntimeException ignored) {
            // Not an object, or not JSON: the caller's contract, and the default.
        }
        return true;
    }

    /** Writes the file. Answer given rather than the default so a test can assert the format. */
    public static String write(List<String> written) {
        JsonArray array = new JsonArray();
        int kept = 0;
        for (String id : written == null ? List.<String>of() : written) {
            if (id == null || id.isBlank() || kept >= MAX_PINS) {
                continue;
            }
            array.add(id);
            kept++;
        }

        JsonObject root = new JsonObject();
        root.add("pins", array);
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

    /** Forgets the pins and the file. For a test, and for a client leaving a world it never owned. */
    public static void reset() {
        pins = List.of();
        hideClaimed = true;
        file = null;
        lastTree = -1L;
    }

    // ------------------------------------------------------------------

    private static void put(List<String> next) {
        pins = List.copyOf(next);
        save();
    }

    private static void save() {
        if (file == null) {
            // No path means no platform: in a test, or in a client whose config directory could not be
            // resolved. The pins still work for this session, which is the useful half.
            return;
        }
        try {
            // Through JsonWrite, which creates the directory and writes by rename -- so a crash mid-save
            // leaves the previous list rather than a half-written file the next load has to guess at.
            // The flag travels only when it differs from the default, the file's own diff convention:
            // a file that restates every value cannot be read for what the player changed.
            JsonObject root = JsonParser.parseString(write(pins)).getAsJsonObject();
            if (!hideClaimed) {
                root.addProperty("hideClaimed", false);
            }
            JsonWrite.atomically(file, root.toString());
        }
        catch (IOException e) {
            Constants.LOG.warn("the pinned quests could not be written to {}", file, e);
        }
    }
}
