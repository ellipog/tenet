package dev.ellipog.tenet.client;

import dev.ellipog.tenet.net.QuestSync;
import dev.ellipog.tenet.quest.Fixtures;
import dev.ellipog.tenet.quest.MinecraftTestBootstrap;
import dev.ellipog.tenet.quest.QuestIndex;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Which quest titles count as duplicates, in the words a player reads.
 *
 * <h2>Why this is worth its own class</h2>
 *
 * <p>The rule is one line — a set of the titles that appear twice, so a chapter's rows can be numbered
 * — and the only thing that can be wrong with it is <b>which accessor it reads</b>. Reading the raw
 * field instead of the resolved text is invisible in English, correct-looking in review, and wrong the
 * moment a pack ships a translation: two quests whose titles differ in the file and read identically
 * to a player are exactly the pair the number exists to tell apart, and two quests whose file titles
 * match but whose translations do not are exactly the pair it must leave alone.
 *
 * <p>The entries come from the real cache rather than being built by hand, because a {@code Entry} has
 * some forty components and a test that assembled one would be asserting against its own idea of the
 * wire rather than against what the wire carries.
 */
@DisplayName("a chapter's repeated quest titles")
class RepeatedQuestTitlesTest {

    @BeforeAll
    static void bootstrap() {
        // The tree parser resolves item ids against the item registry as the tree arrives.
        MinecraftTestBootstrap.boot();
    }

    @BeforeEach
    @AfterEach
    void clearCache() {
        ClientQuestCache.clear();
        ClientLocale.clear();
    }

    private static void accept(String... quests) {
        QuestIndex index = Fixtures.indexOf(Fixtures.file(quests));
        ClientQuestCache.acceptTree(index.questCount(), index.chapterCount(), QuestSync.treeAsJson(index));
    }

    private static Set<String> repeated() {
        return BookRewards.repeatedTitles(ClientQuestCache.entries());
    }

    @Test
    @DisplayName("two quests with the same title are a duplicate, and one alone is not")
    void thePlainCase() {
        accept("{\"id\": \"a\", \"title\": \"Make a Table\"}",
                "{\"id\": \"b\", \"title\": \"Make a Table\"}",
                "{\"id\": \"c\", \"title\": \"Punch a Tree\"}");

        assertTrue(repeated().contains("Make a Table"));
        assertFalse(repeated().contains("Punch a Tree"),
                "numbering every row would put a number on the ninety per cent that need none");
        assertFalse(repeated().isEmpty());
    }

    @Test
    @DisplayName("titles that differ in the file but read the same are a duplicate")
    void aTranslationCanCreateADuplicate() {
        // The case the numbering exists for, and the one reading the raw field gets wrong: in the
        // player's language these two rows read identically, so without a number they are
        // indistinguishable on screen.
        accept("{\"id\": \"a\", \"title\": \"Punch a Tree\"}",
                "{\"id\": \"b\", \"title\": \"Hit a Tree\"}");
        assertFalse(repeated().contains("Vagj egy f\u00e1t"));

        ClientLocale.accept("hu_hu", "hu_hu", Map.of(
                "quest.a.title", "Vagj egy f\u00e1t",
                "quest.b.title", "Vagj egy f\u00e1t"));

        assertTrue(repeated().contains("Vagj egy f\u00e1t"),
                "two rows a player cannot tell apart have to be numbered");
    }

    @Test
    @DisplayName("titles that match in the file but read differently are not a duplicate")
    void aTranslationCanRemoveADuplicate() {
        // The other direction, and the reason this is not merely cosmetic: numbering two rows that
        // read differently would put "2 - " in front of a quest whose name already distinguishes it.
        accept("{\"id\": \"a\", \"title\": \"Make a Table\"}",
                "{\"id\": \"b\", \"title\": \"Make a Table\"}");
        assertTrue(repeated().contains("Make a Table"));

        ClientLocale.accept("hu_hu", "hu_hu", Map.of(
                "quest.a.title", "Asztalt csinalni",
                "quest.b.title", "Asztalt \u00f6sszerakni"));

        assertFalse(repeated().contains("Asztalt csinalni"));
        assertFalse(repeated().contains("Asztalt \u00f6sszerakni"));
        assertTrue(repeated().isEmpty(), () -> "nothing reads the same any more: " + repeated());
    }

    @Test
    @DisplayName("a duplicate's label is the numbered text, not the wire's")
    void theLabelIsNumberedInThePlayersWords() {
        // What the two above are in aid of: the row a player sees. `questLabel` draws the number only
        // for a title the set holds, and draws the title it resolved -- so the two have to agree about
        // which string they are talking about.
        accept("{\"id\": \"a\", \"title\": \"Make a Table\"}",
                "{\"id\": \"b\", \"title\": \"Make a Table\"}");
        ClientLocale.accept("hu_hu", "hu_hu", Map.of(
                "quest.a.title", "Asztalt",
                "quest.b.title", "Asztalt"));

        List<ClientQuestCache.Entry> entries = ClientQuestCache.entries();
        Set<String> repeated = BookRewards.repeatedTitles(entries);

        assertTrue(BookRewards.questLabel(entries.get(0), 0, repeated).endsWith("Asztalt"),
                "the numbered row still reads in the player's language");
        assertTrue(BookRewards.questLabel(entries.get(0), 0, repeated).startsWith("1"));
        assertFalse(BookRewards.questLabel(entries.get(0), 0, Set.of()).startsWith("1"),
                "and an unnumbered row carries no number at all");
    }
}
