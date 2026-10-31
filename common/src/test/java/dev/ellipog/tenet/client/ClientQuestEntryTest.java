package dev.ellipog.tenet.client;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.ellipog.tenet.net.QuestSync;
import dev.ellipog.tenet.quest.Fixtures;
import dev.ellipog.tenet.quest.MinecraftTestBootstrap;
import dev.ellipog.tenet.quest.QuestIndex;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The id index behind {@code ClientQuestCache.entry}.
 *
 * <h2>Why a scan needed replacing, and what the replacement must not change</h2>
 *
 * <p>The lookup is asked per <b>row</b>: the rewards inbox and the choice overlay ask once per row they
 * draw, and a viewer page asks once per task on it. A scan made those O(quests) each, so the cost of
 * one lookup was the size of the pack — invisible in a screenshot and obvious in a large one.
 *
 * <p>Two properties of the old scan are load-bearing and are what this file is mostly about, because an
 * index can quietly break either: it matched <b>exactly</b> (a lookup that folded case would resolve an
 * id the tree does not hold), and it returned the <b>first</b> match in declaration order (a map built
 * with a plain put keeps the last, so a duplicated id would open a different quest than the scan did).
 */
@DisplayName("The client cache's quest index")
class ClientQuestEntryTest {

    private static final String STONE = """
            {"id": "stone", "title": "Punch a tree", "tasks": [
              {"type": "tenet:item", "item": "minecraft:oak_log", "count": 8}]}
            """;

    private static final String TABLE = """
            {"id": "table", "title": "Craft a table", "dependsOn": ["stone"], "tasks": [
              {"type": "tenet:item", "item": "minecraft:oak_planks", "count": 4}]}
            """;

    private static final String OTHER = """
            {"id": "other", "title": "Something else", "tasks": [
              {"type": "tenet:checkmark", "title": "Say hello"}]}
            """;

    @BeforeAll
    static void bootstrap() {
        MinecraftTestBootstrap.boot();
    }

    @BeforeEach
    @AfterEach
    void clearCache() {
        ClientQuestCache.clear();
    }

    private static void accept(String... quests) {
        QuestIndex index = Fixtures.indexOf(Fixtures.file(quests));
        ClientQuestCache.acceptTree(index.questCount(), index.chapterCount(), QuestSync.treeAsJson(index));
    }

    @Test
    @DisplayName("every quest in the tree resolves, and an unknown one is null rather than a crash")
    void everyIdResolves() {
        accept(STONE, TABLE, OTHER);

        for (ClientQuestCache.Entry entry : ClientQuestCache.entries()) {
            assertSame(entry, ClientQuestCache.entry(entry.id()),
                    "the index and the list must agree about every quest in the tree");
        }
        assertNull(ClientQuestCache.entry("nothing_like_this"), "an id the tree does not hold");
        assertNull(ClientQuestCache.entry(null), "a null id: the scan answered null and so does this");
        assertNull(ClientQuestCache.entry(""), "and an empty one, which no quest is called");
    }

    @Test
    @DisplayName("the id is matched exactly, so a differently-cased one is not a hit")
    void idsAreMatchedExactly() {
        accept(STONE);

        assertEquals("stone", ClientQuestCache.entry("stone").id());
        assertNull(ClientQuestCache.entry("STONE"),
                "the server's own identifier map is exact, so folding case here would resolve an id the "
                        + "tree does not hold");
        assertNull(ClientQuestCache.entry("Stone"));
    }

    @Test
    @DisplayName("the index follows the tree, and forgets what it no longer holds")
    void theIndexFollowsTheTree() {
        accept(STONE, TABLE);
        assertNotNull(ClientQuestCache.entry("table"));

        accept(STONE);
        assertNotNull(ClientQuestCache.entry("stone"), "the quest still in the tree still resolves");
        assertNull(ClientQuestCache.entry("table"),
                "a quest the new tree does not carry is not left behind by the old index");
    }

    @Test
    @DisplayName("an empty cache resolves nothing, and an emptied one forgets everything")
    void emptyIsEmpty() {
        assertNull(ClientQuestCache.entry("stone"), "nothing has arrived yet");

        accept(STONE);
        ClientQuestCache.clear();
        assertNull(ClientQuestCache.entry("stone"),
                "a cleared cache holds no quest, and the index is derived from the list so it goes with it");
    }

    @Test
    @DisplayName("the loader no longer sends a duplicate at all")
    void theDuplicateNeverReachesTheWire() {
        // **This half changed.** `QuestIndex` used to add an entry to its list *before* claiming the id, so
        // a duplicate was reported as an error and sent anyway -- two rows for one id, one of which could
        // never be reached: it drew, it could be clicked, and the click opened the other quest. It is now
        // reported and dropped, so there is nothing on this side to disambiguate.
        QuestIndex index = Fixtures.indexOf(Fixtures.file(STONE, STONE));

        assertEquals(1, index.questCount(), "reported and dropped, so the tree carries one");
        ClientQuestCache.acceptTree(index.questCount(), index.chapterCount(), QuestSync.treeAsJson(index));
        assertEquals(1, ClientQuestCache.entries().size(), "and one reaches the client");
        assertNotNull(ClientQuestCache.entry("stone"));
    }

    @Test
    @DisplayName("and a tree that does carry two still resolves to the first in declaration order")
    void theFirstOfADuplicateWins() {
        // Kept as a rule of the client's own rather than deleted along with the loader change, because the
        // wire is not a guarantee: a client on an older server is sent both, and this is the answer that
        // keeps a click opening the quest the server's own map resolves. The tree is therefore built by
        // hand -- no index this build can produce carries two quests under one id any more.
        QuestIndex index = Fixtures.indexOf(Fixtures.file(STONE));
        JsonObject tree = JsonParser.parseString(
                new String(QuestSync.treeAsJson(index), StandardCharsets.UTF_8)).getAsJsonObject();
        JsonArray quests = tree.getAsJsonArray("quests");
        quests.add(quests.get(0).deepCopy());
        ClientQuestCache.acceptTree(quests.size(), index.chapterCount(),
                tree.toString().getBytes(StandardCharsets.UTF_8));

        List<ClientQuestCache.Entry> held = ClientQuestCache.entries();
        assertEquals(2, held.size(), "an older server's tree, which this build no longer produces");
        assertSame(held.get(0), ClientQuestCache.entry("stone"),
                "first wins: a plain put would have kept the second, and the two disagree about which "
                        + "quest a click opens");
    }

    @Test
    @DisplayName("the folded key matches exactly the strings equalsIgnoreCase matches")
    void foldIsEqualsIgnoreCase() {
        // The awkward letters are the point: a dotted capital I, the Kelvin sign, a sharp s, a Greek final
        // sigma and the Cyrillic and Georgian blocks are where "lower-case it" and the character test part.
        List<String> words = List.of("stone", "STONE", "Stone", "st0ne", "", "#", "i", "I", "İ", "ı",
                "k", "K", "K", "ss", "ß", "ẞ", "σ", "ς", "Σ", "д", "Д",
                "ა", "Ა", "a𐐀", "A𐐨", "ǅ", "Ǆ", "ǆ");
        for (String a : words) {
            for (String b : words) {
                assertEquals(a.equalsIgnoreCase(b), ClientQuestCache.fold(a).equals(ClientQuestCache.fold(b)),
                        () -> "fold disagrees with equalsIgnoreCase for " + escape(a) + " and " + escape(b));
            }
        }
    }

    private static String escape(String text) {
        StringBuilder out = new StringBuilder("\"");
        text.codePoints().forEach(cp -> out.append(cp < 128 ? Character.toString(cp) : String.format("\\u%04X", cp)));
        return out.append('"').toString();
    }

    @Test
    @DisplayName("a lookup by id or alias in any case answers what the scan answered, first match included")
    void namedAgreesWithTheScan() {
        // Built by hand, like the duplicate above, so an alias can collide with a later quest's id and a
        // second quest can claim an alias the first already holds: the cases where "first wins" decides.
        QuestIndex index = Fixtures.indexOf(Fixtures.file(STONE, TABLE, OTHER));
        JsonObject tree = JsonParser.parseString(
                new String(QuestSync.treeAsJson(index), StandardCharsets.UTF_8)).getAsJsonObject();
        JsonArray quests = tree.getAsJsonArray("quests");
        for (int i = 0; i < quests.size(); i++) {
            JsonObject quest = quests.get(i).getAsJsonObject();
            JsonArray aliases = new JsonArray();
            switch (quest.get("id").getAsString()) {
                case "stone" -> {
                    aliases.add("Old_Stone");
                    aliases.add("table");
                }
                case "table" -> aliases.add("OLD_STONE");
                case "other" -> aliases.add("Elsewhere");
                default -> { }
            }
            quest.add("aliases", aliases);
        }
        ClientQuestCache.acceptTree(quests.size(), index.chapterCount(),
                tree.toString().getBytes(StandardCharsets.UTF_8));

        List<String> probes = List.of("stone", "STONE", "table", "Table", "other", "old_stone", "OLD_STONE",
                "elsewhere", "ELSEWHERE", "nothing", "", "#", "st", "stones");
        for (String probe : probes) {
            ClientQuestCache.Entry scanned = ClientQuestCache.entries().stream()
                    .filter(entry -> entry.matches(probe)).findFirst().orElse(null);
            assertSame(scanned, ClientQuestCache.named(probe), "the index and the scan disagree about " + probe);
        }
        assertEquals("stone", ClientQuestCache.named("TABLE").id(),
                "stone carries table as an alias and comes first, so the scan stops there and so must this");
        assertNull(ClientQuestCache.named(null));
    }

    @Test
    @DisplayName("adoption cycling is the adopted icon's own walk, asked as a question")
    void adoptionCyclingMirrorsAdoption() {
        // A filter naming many frames cycles; a single-frame filter, a plain item
        // requirement, an explicit icon and a quest of nothing all stand still. The
        // stamp restamps the second only for the first, so the question and the walk
        // it mirrors must agree about which quests move.
        accept("""
                {"id": "cycling", "title": "Many", "tasks": [
                  {"type": "tenet:filter", "filter": "mod(minecraft)", "count": 1}]}
                """,
                """
                {"id": "still", "title": "One", "tasks": [
                  {"type": "tenet:filter", "filter": "item(minecraft:stone)", "count": 1}]}
                """,
                """
                {"id": "plain", "title": "Logs", "tasks": [
                  {"type": "tenet:item", "item": "minecraft:oak_log", "count": 8}]}
                """,
                """
                {"id": "dressed", "title": "Wearing", "icon": {"item": "minecraft:diamond"},
                  "tasks": [{"type": "tenet:filter", "filter": "mod(minecraft)", "count": 1}]}
                """,
                """
                {"id": "bare", "title": "Nothing", "tasks": [
                  {"type": "tenet:checkmark", "title": "Say hello"}]}
                """);

        assertTrue(ClientQuestCache.adoptionCycles(ClientQuestCache.entry("cycling")),
                "a filter with frames to show moves once a second");
        assertFalse(ClientQuestCache.adoptionCycles(ClientQuestCache.entry("still")),
                "one frame is still, however it arrived");
        assertFalse(ClientQuestCache.adoptionCycles(ClientQuestCache.entry("plain")),
                "an adopted requirement does not move");
        assertFalse(ClientQuestCache.adoptionCycles(ClientQuestCache.entry("dressed")),
                "an explicit icon wins over every task");
        assertFalse(ClientQuestCache.adoptionCycles(ClientQuestCache.entry("bare")),
                "and a quest with nothing to lend stands still too");
    }
}
