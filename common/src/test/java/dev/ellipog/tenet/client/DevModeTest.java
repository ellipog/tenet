package dev.ellipog.tenet.client;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.EnumMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Developer mode: the flag, its file, its per-kind widths, and what a bad file does.
 *
 * <h2>Why the file is worth this many tests</h2>
 *
 * <p>Because it is a switch that changes what a screen shows, and the two ways it can be wrong are both
 * silent: a typo could leave it stuck on (a book with tools nobody asked for), or a file that cannot be
 * read could leave it stuck off with no way to tell -- and neither prints anything on screen. The
 * tolerance is therefore asserted rather than assumed: no file, empty file, not JSON, wrong type and an
 * unknown field all end at "off, and the client still runs", which is the direction that cannot lie
 * about what is on screen.
 *
 * <h2>And why the widths get as many again</h2>
 *
 * <p>Because they are the one part of this file that is a <b>table</b>, and a table has more ways to be
 * wrong than a flag: a name this build does not have, a name it has but that means nothing as a rail
 * ({@code NONE}), a value that is not a number, and a number outside the kind's own range are each a
 * different fault with the same consequence if it is not handled -- a panel that opens at a width its own
 * layout cannot be read at. Each is asserted to cost that one entry and nothing else.
 *
 * <p>It holds settings that are <b>not</b> gated on the mode -- snap, the sidebar's bars, the depth and the
 * widths -- and that is asserted here too, because a preference that silently required developer mode
 * would be a switch nobody could find the effect of.
 */
@DisplayName("Developer mode")
class DevModeTest {

    @AfterEach
    void forget() {
        // A static flag with a static path: a test that leaves either set changes the next one.
        DevMode.reset();
    }

    @Test
    @DisplayName("off, unless a file says otherwise — and every kind has its own opening width")
    void offByDefault() {
        assertFalse(DevMode.on());
        assertTrue(DevMode.snap(), "the grid is the default, and is not gated on the mode");
        assertTrue(DevMode.progress(), "and so are the sidebar's chapter progress bars");
        assertFalse(DevMode.advanced(), "and the depth is shallow unless a file chose otherwise");
        assertEquals(PanelStack.Fold.AUTO, DevMode.panelFold(), "with the window deciding about folding");
        assertNull(DevMode.file(), "and no file has been read yet");

        // No entry means the kind's own default, which is the width its layout was drawn against: prose at
        // 340, a list at 260, a table at the wide card's width. This is the whole of "one width per kind" --
        // an empty table is a full set of defaults, not a set of zeroes.
        assertTrue(DevMode.panelWidths().isEmpty(), "nothing has been dragged, so nothing is stored");
        assertEquals(PanelStack.WIDTH, DevMode.panelWidth(PanelKind.QUEST));
        assertEquals(PanelStack.WIDTH, DevMode.panelWidth(PanelKind.TOOLS));
        assertEquals(PanelStack.SECOND_WIDTH, DevMode.panelWidth(PanelKind.PICKER));
        assertEquals(PanelStack.SECOND_WIDTH, DevMode.panelWidth(PanelKind.TEXTURE));
        assertEquals(PanelStack.WIDE_WIDTH, DevMode.panelWidth(PanelKind.REWARDS));
        assertEquals(PanelStack.WIDE_WIDTH, DevMode.panelWidth(PanelKind.TABLE_EDITOR));
        assertEquals(0, DevMode.panelWidth(PanelKind.NONE), "nothing has no width");
        assertEquals(0, DevMode.panelWidth(null), "and neither has no kind at all");
    }

    @Test
    @DisplayName("reading, writing and reading back every flag")
    void roundTrip(@TempDir Path dir) throws IOException {
        Path file = dir.resolve(DevMode.FILE_NAME);
        Map<PanelKind, Integer> none = new EnumMap<>(PanelKind.class);

        assertEquals("{\"dev\":true,\"snap\":true,\"progress\":true,\"advanced\":false,\"panelWidths\":{},"
                        + "\"panelFold\":\"auto\"}",
                DevMode.write(true, true, true, false, none, PanelStack.Fold.AUTO),
                "the format, stated once");

        Map<PanelKind, Integer> some = new EnumMap<>(PanelKind.class);
        some.put(PanelKind.QUEST, 300);
        some.put(PanelKind.TOOLS, PanelStack.MAX_WIDTH);
        assertEquals("{\"dev\":false,\"snap\":false,\"progress\":false,\"advanced\":true,"
                        + "\"panelWidths\":{\"QUEST\":300,"
                        + "\"TOOLS\":420},\"panelFold\":\"off\"}",
                DevMode.write(false, false, false, true, some, PanelStack.Fold.NEVER),
                "written in the enum's own order rather than the map's, so two clients that chose the same "
                        + "widths write the same bytes and a diff of two files is a diff of the choices");

        Files.writeString(file, DevMode.write(true, true, true, true, some, PanelStack.Fold.AUTO),
                StandardCharsets.UTF_8);
        DevMode.load(file);
        assertEquals(file, DevMode.file(), "the file it read is the file it will write");
        assertTrue(DevMode.on());
        assertTrue(DevMode.snap());
        assertTrue(DevMode.progress());
        assertTrue(DevMode.advanced(), "and the depth comes back with the rest");
        assertEquals(300, DevMode.panelWidth(PanelKind.QUEST), "and the widths come back with it");
        assertEquals(PanelStack.MAX_WIDTH, DevMode.panelWidth(PanelKind.TOOLS));
        assertEquals(PanelStack.SECOND_WIDTH, DevMode.panelWidth(PanelKind.PICKER),
                "a kind the file does not name keeps its own default");
        assertEquals(PanelStack.Fold.AUTO, DevMode.panelFold());

        // And the other direction, which is the one a toggle takes.
        DevMode.setOn(false);
        DevMode.setSnap(false);
        DevMode.setProgress(false);
        DevMode.setAdvanced(false);
        DevMode.setPanelWidth(PanelKind.PICKER, PanelStack.MAX_WIDTH);
        DevMode.setPanelFold(PanelStack.Fold.ALWAYS);
        assertFalse(DevMode.on());
        assertFalse(DevMode.snap());
        assertFalse(DevMode.progress());
        assertFalse(DevMode.advanced());
        String written = Files.readString(file, StandardCharsets.UTF_8);
        assertFalse(DevMode.read(written), "what was written is what is read");
        DevMode.load(file);
        assertFalse(DevMode.snap(), "and the snap flag round-trips through the same file");
        assertFalse(DevMode.progress(), "and so does the bars' switch");
        assertFalse(DevMode.advanced(), "and the depth");
        assertEquals(PanelStack.MAX_WIDTH, DevMode.panelWidth(PanelKind.PICKER), "and the width");
        assertEquals(PanelStack.WIDTH, DevMode.panelWidth(PanelKind.NAMING),
                "and a kind the file has never named came back at its own default rather than at whatever "
                        + "the last panel happened to be dragged to");
        assertEquals(PanelStack.Fold.ALWAYS, DevMode.panelFold(), "and which way the fold was asked for");

        // Every entry is a kind somebody dragged, once each: the two from the first half of this test are
        // still there -- a setting is not forgotten because another one changed -- and the third joined them
        // rather than replacing them.
        Map<PanelKind, Integer> expected = new EnumMap<>(PanelKind.class);
        expected.put(PanelKind.QUEST, 300);
        expected.put(PanelKind.TOOLS, PanelStack.MAX_WIDTH);
        expected.put(PanelKind.PICKER, PanelStack.MAX_WIDTH);
        assertEquals(expected, DevMode.panelWidths(),
                "the file holds exactly the kinds that were dragged, and nothing else");
    }

    @Test
    @DisplayName("a width is clamped by its own kind, and one back at its default is not stored")
    void widthsArePerKindAndStoredAsADiff(@TempDir Path dir) {
        DevMode.load(dir.resolve("not-there.json"));

        DevMode.setPanelWidth(PanelKind.QUEST, -100);
        assertEquals(PanelStack.MIN_WIDTH, DevMode.panelWidth(PanelKind.QUEST), "a prose rail has a floor");
        DevMode.setPanelWidth(PanelKind.QUEST, 10_000);
        assertEquals(PanelStack.MAX_WIDTH, DevMode.panelWidth(PanelKind.QUEST), "and a ceiling");

        // A wide kind goes where an ordinary one may not, which is the whole reason the clamp is per kind.
        DevMode.setPanelWidth(PanelKind.REWARDS, 300);
        assertEquals(PanelStack.WIDE_MIN_WIDTH, DevMode.panelWidth(PanelKind.REWARDS),
                "a rewards inbox opened at a prose width is widened to its own floor instead");
        DevMode.setPanelWidth(PanelKind.REWARDS, 10_000);
        assertEquals(PanelStack.WIDE_WIDTH, DevMode.panelWidth(PanelKind.REWARDS));

        // And the diff: a width back at its default is removed rather than restated beside the default it
        // already has. Two spellings of one state is how a file grows a line every time somebody drags a
        // panel and puts it back -- the shape `ThemeFiles` uses for themes, and for the same reason.
        DevMode.setPanelWidth(PanelKind.TABLE_EDITOR, PanelStack.WIDE_WIDTH);
        assertFalse(DevMode.panelWidths().containsKey(PanelKind.TABLE_EDITOR),
                "the default is the absence of an entry");
        assertEquals(PanelStack.WIDE_WIDTH, DevMode.panelWidth(PanelKind.TABLE_EDITOR),
                "and the answer is the same either way");

        // A kind with no rail is not a width: the request is ignored rather than clamped to a small number.
        Map<PanelKind, Integer> before = DevMode.panelWidths();
        DevMode.setPanelWidth(PanelKind.NONE, 300);
        DevMode.setPanelWidth(null, 300);
        assertEquals(before, DevMode.panelWidths(), "neither of them added an entry");
    }

    @Test
    @DisplayName("the scalar this file used to hold seeds every kind, clamped by each kind's own range")
    void theOldScalarSeedsEveryKind() {
        // A file written before the table existed. The old number was clamped per kind on the way in even
        // then -- a rewards inbox opened at somebody's 300-pixel prose preference was already drawn at 420 --
        // so seeding through the same clamp is what keeps a player's choice meaning what it meant.
        DevMode.Parsed read = DevMode.parse("{\"dev\":true,\"panelWidth\":300}");

        assertTrue(read.dev());
        assertEquals(300, read.panelWidths().get(PanelKind.QUEST), "a prose rail takes it as it is");
        assertEquals(300, read.panelWidths().get(PanelKind.TOOLS));
        assertEquals(PanelStack.WIDE_MIN_WIDTH, read.panelWidths().get(PanelKind.REWARDS),
                "and a wide kind is widened to its own floor, exactly as the clamp did before");
        assertEquals(0, read.panelWidths().getOrDefault(PanelKind.NONE, 0),
                "NONE is not a rail, so it gets no entry");
    }

    @Test
    @DisplayName("the table wins where it has an entry, and the old scalar fills the rest")
    void theTableAndTheScalarTogether() {
        // The ordering that matters and is the only place it does: read the other way round -- the table
        // winning wherever it had *anything* -- a file that named one kind would reset the rest.
        DevMode.Parsed read = DevMode.parse(
                "{\"panelWidths\":{\"QUEST\":280},\"panelWidth\":300}");

        assertEquals(280, read.panelWidths().get(PanelKind.QUEST), "the table's own entry is the player's");
        assertEquals(300, read.panelWidths().get(PanelKind.NAMING), "and the scalar fills the kinds it does not name");
    }

    @Test
    @DisplayName("every way a width entry can be wrong costs that entry and nothing else")
    void badWidthEntriesAreSkipped() {
        // Four faults in one table, because they have to be told apart: a kind this build does not have, a
        // kind that is not a rail, a value that is not a number, and a number outside the range. The first
        // three are dropped; the fourth is clamped, because it is a number and a number has a nearest legal
        // width -- unlike a name, which has no nearest anything.
        DevMode.Parsed read = DevMode.parse("{\"dev\":true,\"panelWidths\":{"
                + "\"QUEST\":\"wide\",\"NONE\":300,\"NOT_A_KIND\":300,\"party\":500,\"REWARDS\":5}}");

        assertTrue(read.dev(), "the file was read: nothing in it was the wrong shape at the top level");
        assertFalse(read.panelWidths().containsKey(PanelKind.QUEST),
                "a string where a number belongs costs that entry");
        assertFalse(read.panelWidths().containsKey(PanelKind.NONE), "NONE is not a rail to be widened");
        assertEquals(PanelStack.MAX_WIDTH, read.panelWidths().get(PanelKind.PARTY),
                "and a lower-case name is one a person will write, so it is read rather than skipped");
        assertEquals(PanelStack.WIDE_MIN_WIDTH, read.panelWidths().get(PanelKind.REWARDS),
                "a number below the floor is clamped up to it rather than dropped");
    }

    @Test
    @DisplayName("the presentation switch that is gone is tolerated, and decides nothing")
    void theOldPanelsKeyIsIgnored(@TempDir Path dir) throws IOException {
        // The key is still read as *text* by a parser that ignores what it does not know, which is what
        // makes an old file harmless. What matters is that it changes nothing and costs nothing: the flags
        // beside it are still read, so a player who had turned the card presentation off does not lose their
        // editor's snap setting with it.
        Path file = dir.resolve(DevMode.FILE_NAME);
        Files.writeString(file, "{\"dev\":true,\"snap\":false,\"panels\":false,\"panelWidth\":300}",
                StandardCharsets.UTF_8);
        DevMode.load(file);

        assertTrue(DevMode.on());
        assertFalse(DevMode.snap(), "the setting beside the dead key is still read");
        assertEquals(300, DevMode.panelWidth(PanelKind.QUEST), "and so is the width it used to hold");

        // And the key is not written again. It is not stripped on load -- a read must not rewrite a file --
        // so the assertion is about the *next* save, which is what carries it away.
        DevMode.setSnap(true);
        String written = Files.readString(file, StandardCharsets.UTF_8);
        assertFalse(written.contains("panels"), "nothing writes the dead key again: " + written);
        assertFalse(written.contains("\"panelWidth\":"),
                "and the scalar it was beside is written as the table now: " + written);
        assertTrue(written.contains("\"panelWidths\""), "with every kind's own entry in it: " + written);
    }

    @Test
    @DisplayName("a file from before the grid existed reads as snapping on")
    void oldFileStillSnaps(@TempDir Path dir) throws IOException {
        Path old = dir.resolve(DevMode.FILE_NAME);
        Files.writeString(old, "{\"dev\":true}", StandardCharsets.UTF_8);
        DevMode.load(old);
        assertTrue(DevMode.on());
        assertTrue(DevMode.snap(), "a missing field takes its default, which is on for the grid");
        assertTrue(DevMode.progress(), "and for the progress bars");
        assertFalse(DevMode.advanced(), "and the depth's default is off, so the editor reads as Normal");
        assertEquals(PanelStack.WIDTH, DevMode.panelWidth(PanelKind.QUEST), "at the ordinary width");
        assertEquals(PanelStack.Fold.AUTO, DevMode.panelFold(), "with the window deciding about folding");
        assertTrue(DevMode.isSidebarFloating(), "and the list floats, like a new book");
    }

    @Test
    @DisplayName("a width that is not a number discards the file, like any other unreadable one")
    void aWrongTypedWidthIsABadFile(@TempDir Path dir) throws IOException {
        Path file = dir.resolve(DevMode.FILE_NAME);
        Files.writeString(file, "{\"dev\":true,\"panelWidth\":\"wide\"}", StandardCharsets.UTF_8);
        DevMode.load(file);

        // The scalar is read as an *int*, so a string there is a wrong type at the top level and the whole
        // file goes -- the rule every other wrong type already follows, and the safe direction: a client that
        // cannot read the file draws what it shipped with. The table's own entries are the other case, and
        // are asserted above: an entry is read on its own, so one bad entry costs that entry.
        assertFalse(DevMode.on());
        assertEquals(PanelStack.WIDTH, DevMode.panelWidth(PanelKind.QUEST),
                "a refused file leaves the defaults");
    }

    @Test
    @DisplayName("a fold word nobody knows means the window decides, rather than a discarded file")
    void anUnknownFoldWordIsAuto(@TempDir Path dir) throws IOException {
        Path file = dir.resolve(DevMode.FILE_NAME);
        Files.writeString(file, "{\"dev\":true,\"panelFold\":\"sometimes\"}", StandardCharsets.UTF_8);
        DevMode.load(file);

        // Unlike a wrong *type*, an unknown word is a preference this build does not have a name for, and
        // the rest of the file is perfectly readable -- so only that one setting falls back.
        assertTrue(DevMode.on(), "the file was read, because nothing in it was the wrong shape");
        assertEquals(PanelStack.Fold.AUTO, DevMode.panelFold());
    }

    @Test
    @DisplayName("a file that is missing, empty, wrong or unreadable leaves it off")
    void aBadFileIsOff(@TempDir Path dir) throws IOException {
        Path missing = dir.resolve("not-there.json");
        DevMode.load(missing);
        assertFalse(DevMode.on(), "a first run is off, and says nothing");

        Path empty = dir.resolve("empty.json");
        Files.writeString(empty, "", StandardCharsets.UTF_8);
        DevMode.load(empty);
        assertFalse(DevMode.on(), "an empty file is not JSON, and is not a crash");

        Path wrongType = dir.resolve("wrong.json");
        Files.writeString(wrongType, "{\"dev\": \"yes please\"}", StandardCharsets.UTF_8);
        DevMode.load(wrongType);
        assertFalse(DevMode.on(), "a string where a boolean belongs");

        Path unknown = dir.resolve("unknown.json");
        Files.writeString(unknown, "{\"developer\": true}", StandardCharsets.UTF_8);
        DevMode.load(unknown);
        assertFalse(DevMode.on(), "a misspelled field is not the field");

        // Nothing above threw, which is half the assertion: this file is read on the client's way in,
        // and a mode that cannot be read is worth less than a client that cannot start.
        assertTrue(Files.exists(missing.getParent()), "the directory was not consumed by any of them");
    }

    @Test
    @DisplayName("without a file the mode still toggles, and a width is still remembered for the session")
    void noFileStillWorks() {
        // The state a test runs in, and the state a client runs in if the platform could not resolve a
        // config directory: the setting works and simply forgets.
        assertTrue(DevMode.toggle());
        assertTrue(DevMode.on());
        assertFalse(DevMode.toggle());
        assertFalse(DevMode.on());

        // The depth is not the mode and does not follow it: an author who turns Edit off and on again
        // keeps the view they were working in, which is the whole reason the two are separate flags.
        assertTrue(DevMode.toggleAdvanced());
        assertTrue(DevMode.advanced());
        DevMode.setOn(true);
        DevMode.setOn(false);
        assertTrue(DevMode.advanced(), "the mode does not carry the depth with it");
        assertFalse(DevMode.toggleAdvanced());
        assertFalse(DevMode.advanced());

        DevMode.setPanelWidth(PanelKind.ASSETS, PanelStack.WIDE_WIDTH - 1);
        assertEquals(PanelStack.WIDE_WIDTH - 1, DevMode.panelWidth(PanelKind.ASSETS),
                "a save that has nowhere to go still answers with what was set");
        assertNull(DevMode.file());
    }

    @Test
    @DisplayName("the chapter list is a floating pinned box unless a file says otherwise")
    void theSidebarDefaultsToTodaysBook() {
        assertEquals(BookGeometry.SIDEBAR_WIDTH, DevMode.sidebarWidth(),
                "156 wide");
        assertTrue(DevMode.isSidebarFloating(), "floating as a box on the canvas");
        assertTrue(DevMode.isSidebarPinned(), "pinned open");
        assertFalse(DevMode.isSidebarCollapsed(), "and expanded, because it was never slid away");
        assertEquals(SidebarState.DEFAULT, DevMode.sidebarState(),
                "so the four answers together are the default state");
    }

    @Test
    @DisplayName("the list's width is clamped by its own range, and the default is not stored")
    void theSidebarWidthIsClamped(@TempDir Path dir) {
        DevMode.load(dir.resolve("not-there.json"));

        DevMode.setSidebarWidth(-100);
        assertEquals(BookGeometry.SIDEBAR_MIN_WIDTH, DevMode.sidebarWidth(), "a floor");
        DevMode.setSidebarWidth(10_000);
        assertEquals(BookGeometry.SIDEBAR_MAX_WIDTH, DevMode.sidebarWidth(), "and a ceiling");

        DevMode.setSidebarWidth(BookGeometry.SIDEBAR_WIDTH);
        assertEquals(BookGeometry.SIDEBAR_WIDTH, DevMode.sidebarWidth(),
                "the default answers the same either way");
    }

    @Test
    @DisplayName("the list's four answers round-trip, and a file without them reads as today")
    void theSidebarRoundTrips(@TempDir Path dir) throws IOException {
        Path file = dir.resolve(DevMode.FILE_NAME);
        Map<PanelKind, Integer> none = new EnumMap<>(PanelKind.class);

        // A file that never heard of the list writes not one key for it: the absence of each
        // already says "today's book", so the update changes nobody's bytes.
        assertEquals("{\"dev\":false,\"snap\":true,\"progress\":true,\"advanced\":false,"
                        + "\"panelWidths\":{},\"panelFold\":\"auto\"}",
                DevMode.write(false, true, true, false, none, PanelStack.Fold.AUTO),
                "the old shape still writes the old bytes");

        Files.writeString(file,
                DevMode.write(false, true, true, false, none, PanelStack.Fold.AUTO, 200, true,
                        false, true),
                StandardCharsets.UTF_8);
        DevMode.load(file);
        assertEquals(200, DevMode.sidebarWidth(), "a dragged width comes back");
        assertTrue(DevMode.isSidebarFloating(), "floating comes back");
        assertFalse(DevMode.isSidebarPinned(), "unpinned comes back");
        assertTrue(DevMode.isSidebarCollapsed(), "and slid-away comes back");

        // And the other direction, through the setters a toggle takes.
        DevMode.setSidebarWidth(BookGeometry.SIDEBAR_WIDTH);
        DevMode.setSidebarFloating(true);
        DevMode.setSidebarPinned(true);
        DevMode.setSidebarCollapsed(false);
        String written = Files.readString(file, StandardCharsets.UTF_8);
        assertFalse(written.contains("sidebar"),
                "everything back at its default leaves no key: " + written);

        // While docking writes its one key: absence already says floating.
        DevMode.setSidebarFloating(false);
        assertTrue(Files.readString(file, StandardCharsets.UTF_8).contains("\"sidebarFloating\":false"),
                "a docked list says so: " + Files.readString(file, StandardCharsets.UTF_8));
    }

    @Test
    @DisplayName("a file that never heard of the list floats like a new book")
    void aFileWithoutTheListFloats() {
        // Absence says floating pinned expanded: docking is the settings switch, not a missing key.
        DevMode.Parsed rail = DevMode.parse("{\"sidebarCollapsed\":true}");
        assertTrue(rail.sidebarFloating(), "a slid-away list was floating");
        assertTrue(rail.sidebarPinned(), "with the pin it stored");
        assertTrue(rail.sidebarCollapsed(), "and still slid away");

        DevMode.Parsed card = DevMode.parse("{\"sidebarPinned\":false}");
        assertTrue(card.sidebarFloating(), "an unpinned list was floating");
        assertFalse(card.sidebarPinned(), "as it said");

        DevMode.Parsed plain = DevMode.parse("{\"dev\":true}");
        assertTrue(plain.sidebarFloating(), "while a file that never heard of it floats");

        DevMode.Parsed docked = DevMode.parse("{\"sidebarFloating\":false}");
        assertFalse(docked.sidebarFloating(), "and a docked list stays docked");
    }

    @Test
    @DisplayName("every way a sidebar entry can be wrong costs that entry and nothing else")
    void badSidebarEntriesAreSkipped(@TempDir Path dir) throws IOException {
        Path file = dir.resolve(DevMode.FILE_NAME);
        Files.writeString(file,
                "{\"dev\":true,\"sidebarWidth\":\"wide\",\"sidebarPinned\":\"yes\",\"sidebarCollapsed\":1}",
                StandardCharsets.UTF_8);
        DevMode.load(file);

        assertTrue(DevMode.on(), "the file was read: nothing in it was the wrong shape");
        assertEquals(BookGeometry.SIDEBAR_WIDTH, DevMode.sidebarWidth(),
                "a string where a number belongs costs the width");
        assertTrue(DevMode.isSidebarPinned(), "a string where a boolean belongs costs the pin");
        assertFalse(DevMode.isSidebarCollapsed(), "and a number where a boolean belongs costs the collapse");

        // While a number outside the range is clamped, because it is a number and a number has a
        // nearest legal width -- unlike a name, which has no nearest anything.
        Files.writeString(file, "{\"sidebarWidth\":10000}", StandardCharsets.UTF_8);
        DevMode.load(file);
        assertEquals(BookGeometry.SIDEBAR_MAX_WIDTH, DevMode.sidebarWidth());
    }
}
