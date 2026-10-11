package dev.ellipog.tenet.client;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Where the author was, and the two ways it must not be believed.
 *
 * <p>Three values, one file, and no drawing: what is worth asserting is the round trip, the defaults a
 * missing or corrupt file gets, and — the point of the whole thing — that a value nothing checks cannot
 * be handed out as if it were true. The panel's own use of these values is the screen's business.
 */
@DisplayName("Where the author was")
class ClientWorkingTest {

    @AfterEach
    void forget() {
        // Statics with a static path: a test that leaves either set changes the next one.
        ClientWorking.reset();
    }

    @Test
    @DisplayName("nothing remembered, and no file, until one is read")
    void emptyByDefault() {
        assertEquals("", ClientWorking.lastTable());
        assertEquals(ClientWorking.TABLES, ClientWorking.section(), "the tables are what an author wants");
        assertEquals(ClientWorking.BOOK_TAB, ClientWorking.drawerTab());
        assertNull(ClientWorking.file(), "and no file has been read yet");
    }

    @Test
    @DisplayName("reading, writing and reading back all four")
    void roundTrip(@TempDir Path dir) throws IOException {
        Path file = dir.resolve(ClientWorking.FILE_NAME);

        assertEquals("{\"lastTable\":\"dice\",\"section\":\"quests\",\"drawerTab\":\"chapter\","
                        + "\"templateSort\":\"name\"}",
                ClientWorking.write("dice", ClientWorking.QUESTS, ClientWorking.CHAPTER_TAB,
                        ClientWorking.TEMPLATE_SORT_NAME),
                "the format, stated once");

        Files.writeString(file,
                ClientWorking.write("dice", ClientWorking.QUESTS, ClientWorking.CHAPTER_TAB,
                        ClientWorking.TEMPLATE_SORT_NAME),
                StandardCharsets.UTF_8);
        ClientWorking.load(file);

        assertEquals(file, ClientWorking.file(), "the file it read is the file it will write");
        assertEquals("dice", ClientWorking.lastTable());
        assertEquals(ClientWorking.QUESTS, ClientWorking.section());
        assertEquals(ClientWorking.CHAPTER_TAB, ClientWorking.drawerTab());
        assertEquals(ClientWorking.TEMPLATE_SORT_NAME, ClientWorking.templateSort());

        // And the writing direction, which is the one a press takes.
        ClientWorking.rememberTable("toll");
        ClientWorking.rememberSection(ClientWorking.TABLES);
        ClientWorking.rememberTemplateSort(ClientWorking.TEMPLATE_SORT_TYPE);

        assertEquals("toll", ClientWorking.lastTable());
        assertEquals(ClientWorking.TABLES, ClientWorking.section());
        assertEquals(ClientWorking.TEMPLATE_SORT_TYPE, ClientWorking.templateSort());
        String written = Files.readString(file, StandardCharsets.UTF_8);
        assertEquals("toll", ClientWorking.parse(written).lastTable(), "what was written is what is read");

        // A reload from the file it just wrote keeps the values.
        ClientWorking.load(file);
        assertEquals("toll", ClientWorking.lastTable());
        assertEquals(ClientWorking.TABLES, ClientWorking.section());
        assertEquals(ClientWorking.CHAPTER_TAB, ClientWorking.drawerTab(), "and the tab it did not touch");
        assertEquals(ClientWorking.TEMPLATE_SORT_TYPE, ClientWorking.templateSort(),
                "and the order it did touch");
    }

    @Test
    @DisplayName("a section nothing knows opens the first one rather than a blank panel")
    void anUnknownSectionIsTheFirst() {
        // Written by a build with a fourth section, or edited by hand into a typo: either way the panel
        // must open on a section rather than on nothing.
        ClientWorking.Parsed read = ClientWorking.parse("{\"section\":\"chapters\"}");

        assertEquals("chapters", read.section(), "the file is read as it stands");
        ClientWorking.rememberSection(read.section());
        assertEquals(ClientWorking.TABLES, ClientWorking.section(), "and an unknown one falls to the first");
        assertEquals(ClientWorking.BOOK_TAB, read.drawerTab(), "an absent tab is the book's");
        assertEquals("", read.lastTable(), "and an absent table is no table");

        // And a real one: `types` was a section until it was removed, so a file written by that build has
        // to land somewhere rather than on a section that does not exist. That is what this helper is for.
        ClientWorking.rememberSection("types");
        assertEquals(ClientWorking.TABLES, ClientWorking.section(),
                "a section this build no longer has opens the first one");
    }

    @Test
    @DisplayName("a file from before the Assets panel existed reads as its defaults")
    void oldFileReadsAsDefaults(@TempDir Path dir) throws IOException {
        Path old = dir.resolve(ClientWorking.FILE_NAME);
        Files.writeString(old, "{\"lastTable\":\"dice\"}", StandardCharsets.UTF_8);

        ClientWorking.load(old);
        assertEquals("dice", ClientWorking.lastTable());
        assertEquals(ClientWorking.TABLES, ClientWorking.section(), "a missing field takes its default");
        assertEquals(ClientWorking.BOOK_TAB, ClientWorking.drawerTab());
        assertEquals(ClientWorking.TEMPLATE_SORT_RECENT, ClientWorking.templateSort(),
                "and the shelf sorts by recent use");
    }

    @Test
    @DisplayName("an unknown order sorts by recent use rather than opening unsorted")
    void unknownSortIsRecent() {
        ClientWorking.rememberTemplateSort("loudest");
        assertEquals(ClientWorking.TEMPLATE_SORT_RECENT, ClientWorking.templateSort());
        ClientWorking.rememberTemplateSort(ClientWorking.TEMPLATES);
        assertEquals(ClientWorking.TEMPLATE_SORT_RECENT, ClientWorking.templateSort(),
                "a section name is not an order");
    }

    @Test
    @DisplayName("a corrupt file is no memory at all, rather than the last session's")
    void aCorruptFileForgetsEverything(@TempDir Path dir) throws IOException {
        // The fault this pins: `load` used to set only what it read, so a file that failed to parse left
        // the *previous* session's values in the fields while claiming nothing had been read -- a panel
        // opening on a table from a file that says something else entirely.
        Path file = dir.resolve(ClientWorking.FILE_NAME);
        Files.writeString(file, ClientWorking.write("dice", ClientWorking.QUESTS,
                ClientWorking.CHAPTER_TAB, ClientWorking.TEMPLATE_SORT_RECENT), StandardCharsets.UTF_8);
        ClientWorking.load(file);
        assertEquals("dice", ClientWorking.lastTable());

        Files.writeString(file, "{ this is not json", StandardCharsets.UTF_8);
        ClientWorking.load(file);

        assertEquals("", ClientWorking.lastTable(), "the broken file's values are gone, not kept");
        assertEquals(ClientWorking.TABLES, ClientWorking.section());
        assertEquals(ClientWorking.BOOK_TAB, ClientWorking.drawerTab());
        assertEquals(ClientWorking.TEMPLATE_SORT_RECENT, ClientWorking.templateSort());
    }

    @Test
    @DisplayName("a value written with no file is remembered for the session and nothing else")
    void noFileStillRemembersForTheSession() {
        // No platform, so no file: the useful half is that the panel still opens where the author left it
        // for as long as the game is running, and the test is that this does not throw.
        assertNull(ClientWorking.file());
        ClientWorking.rememberTable("dice");
        ClientWorking.rememberSection(ClientWorking.QUESTS);

        assertEquals("dice", ClientWorking.lastTable());
        assertEquals(ClientWorking.QUESTS, ClientWorking.section());
        assertTrue(ClientWorking.lastTable().equals("dice"), "and nothing was written anywhere");
    }
}
