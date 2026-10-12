package dev.ellipog.tenet.client;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.ellipog.armature.api.ArmatureApi;
import dev.ellipog.armature.api.data.JsonWrite;
import dev.ellipog.tenet.Constants;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Where the author was: the last table opened, the drawer's tab, the Assets panel's section.
 *
 * <h2>Why this is a third settings file rather than a field on the screen</h2>
 *
 * <p>Because it has to outlive the screen, and the screen is rebuilt on every resize, every reload and
 * every overlay — a field on it would be a memory that forgets the moment anything happens. It is the
 * same shape as {@link DevMode} and {@link ClientAppearance} deliberately: one file, read by the same two
 * lines of each loader's client initialiser, written when a value changes.
 *
 * <h2>What these values are not</h2>
 *
 * <p><b>They are hints, not state.</b> None of them decides what is on screen; each one decides what is
 * highlighted, sorted first, or selected when a panel opens. That distinction is what makes a stale file
 * harmless: the last table may have been deleted, or belong to a different world on the same client —
 * because this file is per client, not per world — and in either case the panel opens on its list with
 * nothing highlighted rather than on a file that is not there.
 *
 * <p>So every reader asks "is this value still true" before using it. A value that is written and never
 * checked is the fault this note exists to prevent.
 */
public final class ClientWorking {

    /** The file, beside {@link DevMode}'s and {@link ClientAppearance}'s. */
    public static final String FILE_NAME = "working.json";

    /** The Assets panel's sections, as the file spells them. */
    public static final String TABLES = "tables";
    public static final String QUESTS = "quests";
    public static final String TEMPLATES = "templates";

    /** The Templates page's orders, as the file spells them. */
    public static final String TEMPLATE_SORT_NAME = "name";
    public static final String TEMPLATE_SORT_TYPE = "type";
    public static final String TEMPLATE_SORT_RECENT = "recent";

    /** The drawer's two tabs, as the file spells them. */
    public static final String BOOK_TAB = "book";
    public static final String CHAPTER_TAB = "chapter";

    private static volatile String lastTable = "";
    private static volatile String section = TABLES;
    private static volatile String drawerTab = BOOK_TAB;
    private static volatile String templateSort = TEMPLATE_SORT_RECENT;
    private static volatile Path file;

    private ClientWorking() {
    }

    /** The table the editor was last opened on, or empty. A hint: see the class note. */
    public static String lastTable() {
        return lastTable;
    }

    /** Remembers a table the author opened, and writes it. */
    public static void rememberTable(String id) {
        lastTable = id == null ? "" : id;
        save();
    }

    /** The Assets panel's section: one of {@link #TABLES}, {@link #QUESTS} or {@link #TEMPLATES}. */
    public static String section() {
        return section;
    }

    /** Remembers which section the author was reading, and writes it. */
    public static void rememberSection(String next) {
        section = knownSection(next);
        save();
    }

    /** The drawer's tab: {@link #BOOK_TAB} or {@link #CHAPTER_TAB}. */
    public static String drawerTab() {
        return drawerTab;
    }

    /** Remembers which of the drawer's two tabs was open, and writes it. */
    public static void rememberDrawerTab(String next) {
        drawerTab = CHAPTER_TAB.equals(next) ? CHAPTER_TAB : BOOK_TAB;
        save();
    }

    /** The Templates page's order: one of the {@code TEMPLATE_SORT_} spellings. */
    public static String templateSort() {
        return templateSort;
    }

    /** Remembers how the Templates page is ordered, and writes it. */
    public static void rememberTemplateSort(String next) {
        templateSort = knownSort(next);
        save();
    }

    // ------------------------------------------------------------------
    // Persistence
    // ------------------------------------------------------------------

    /** Reads the values from the platform's config directory. Called once by each loader's client. */
    public static void loadFromConfig() {
        Path path = null;
        try {
            path = ArmatureApi.platform().configDir(Constants.MOD_ID).resolve(FILE_NAME);
        }
        catch (RuntimeException e) {
            // Reachable only if this runs before the platform is installed, which is a caller's ordering
            // fault rather than a player's. The defaults are what a first run gets either way.
            Constants.LOG.warn("the platform layer was not ready, so where you were was not read.",
                    e);
        }
        if (path != null) {
            load(path);
        }
    }

    /**
     * Reads the values from one file.
     *
     * <p>A missing file is a first run and gets no message; a file that is there and cannot be read gets
     * one that names it. Neither stops the client, and neither leaves a value half-set: everything is
     * reset to its default before the read, so a corrupt file cannot leave the previous session's values
     * behind in the fields.
     */
    public static void load(Path path) {
        file = path;
        lastTable = "";
        section = TABLES;
        drawerTab = BOOK_TAB;
        templateSort = TEMPLATE_SORT_RECENT;

        if (Files.isRegularFile(path)) {
            try {
                Parsed read = parse(Files.readString(path, StandardCharsets.UTF_8));
                lastTable = read.lastTable();
                section = knownSection(read.section());
                drawerTab = CHAPTER_TAB.equals(read.drawerTab()) ? CHAPTER_TAB : BOOK_TAB;
                templateSort = knownSort(read.templateSort());
            }
            catch (IOException | RuntimeException e) {
                Constants.LOG.warn("{} could not be read, so nothing is remembered. Deleting the"
                        + " file will stop this message.", path, e);
            }
        }
    }

    /** The three values as read from a file. */
    public record Parsed(String lastTable, String section, String drawerTab, String templateSort) {
    }

    /**
     * Parses the file's text.
     *
     * <p>Tolerant for {@link DevMode}'s reason — this is a small file a person may well open — and one
     * step further: an unknown <i>section</i> is not an error and not an empty panel, it is the first
     * section. A file written by a build with a fourth section in it, or edited by hand into a typo, must
     * open a panel rather than a blank one.
     *
     * @throws com.google.gson.JsonSyntaxException if the text is not JSON at all; {@link #load} catches it
     */
    public static Parsed parse(String json) {
        JsonObject root = JsonParser.parseString(json).getAsJsonObject();
        return new Parsed(
                root.has("lastTable") ? root.get("lastTable").getAsString() : "",
                root.has("section") ? root.get("section").getAsString() : TABLES,
                root.has("drawerTab") ? root.get("drawerTab").getAsString() : BOOK_TAB,
                root.has("templateSort") ? root.get("templateSort").getAsString() : TEMPLATE_SORT_RECENT);
    }

    /** Writes the file. Answer given rather than the default so a test can assert the format. */
    public static String write(String lastTable, String section, String drawerTab, String templateSort) {
        JsonObject root = new JsonObject();
        root.addProperty("lastTable", lastTable == null ? "" : lastTable);
        root.addProperty("section", knownSection(section));
        root.addProperty("drawerTab", CHAPTER_TAB.equals(drawerTab) ? CHAPTER_TAB : BOOK_TAB);
        root.addProperty("templateSort", knownSort(templateSort));
        return root.toString();
    }

    /**
     * The file this client reads and writes, or null before {@link #loadFromConfig} has run.
     *
     * <p>Exposed for {@link DevMode#file()}'s reason: a person editing the file and a panel reading it
     * have to be the same file.
     */
    public static Path file() {
        return file;
    }

    /** Forgets the values and the file. For a test, and for a client leaving a world it never owned. */
    public static void reset() {
        lastTable = "";
        section = TABLES;
        drawerTab = BOOK_TAB;
        templateSort = TEMPLATE_SORT_RECENT;
        file = null;
    }

    /**
     * One of the three, or the first: see {@link #parse}.
     *
     * <p>Which is what makes a file written by a build with an Assets <b>Types</b> section — since removed,
     * because the card's own form shows a type's fields where the author is already writing the entry —
     * open on the Tables rather than on a section that no longer exists.
     */
    private static String knownSection(String candidate) {
        if (QUESTS.equals(candidate) || TEMPLATES.equals(candidate)) {
            return candidate;
        }
        return TABLES;
    }

    /**
     * One of the three orders, or the most recently used: an unknown order sorts by what the
     * author reaches for rather than opening an unsorted shelf.
     */
    private static String knownSort(String candidate) {
        if (TEMPLATE_SORT_NAME.equals(candidate) || TEMPLATE_SORT_TYPE.equals(candidate)) {
            return candidate;
        }
        return TEMPLATE_SORT_RECENT;
    }

    private static void save() {
        if (file == null) {
            // No path means no platform: in a test, or in a client whose config directory could not be
            // resolved. The values still work for this session, which is the useful half.
            return;
        }
        try {
            // Through JsonWrite, which creates the directory and writes by rename. Where the player was
            // is small and re-derivable, so the stakes are lower than a chapter's -- but a file that
            // parses to nothing is worse than one that is merely out of date, and the guard is the same
            // one either way. See JsonWrite.
            JsonWrite.atomically(file, write(lastTable, section, drawerTab, templateSort));
        }
        catch (IOException e) {
            Constants.LOG.warn("where you were could not be written to {}", file, e);
        }
    }
}
