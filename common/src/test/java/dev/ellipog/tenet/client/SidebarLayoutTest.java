package dev.ellipog.tenet.client;

import dev.ellipog.armature.client.ui.kit.Layout;
import dev.ellipog.armature.client.ui.kit.Slot;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The sidebar: which rows exist, what they say, and where they go.
 *
 * <h2>Why this needs no window, no font and no widget</h2>
 *
 * <p>Because everything a sidebar does that can be wrong happens <i>above</i> the drawing. Which rows a
 * collapsed group hides, how deep a chapter is indented, whether a heading gets a disclosure arrow,
 * whether the height the scrollbar is given matches the rows that were placed — not one of those is a
 * question about pixels, and every one of them is a question this class answers.
 *
 * <p>That is the reason {@link SidebarLayout} exists at all rather than the answers living in
 * {@code QuestBookScreen}. The screen extends {@code Screen} and needs a running game to instantiate,
 * so a question asked of the screen is a question no test can ask — which is how the reported control
 * collision survived to a screenshot. The same argument that moved the book's rectangles into
 * {@link BookGeometry} moves the sidebar's rows here.
 *
 * <h2>The one thing this file deliberately does not test</h2>
 *
 * <p>Nothing here asserts on pixels. The absolute x and width of a row are {@link
 * dev.ellipog.armature.client.ui.kit.Stack}'s arithmetic, and its own suite covers it; what is this
 * class's to get right is the <b>indent</b> — that a chapter is one level deeper than its heading and
 * that the indent is in the slot rather than painted on afterwards. So the assertions below are on
 * relative positions and on the indent's presence, which is the part a copy here could disagree with.
 */
@DisplayName("the sidebar")
class SidebarLayoutTest {

    // ------------------------------------------------------------------
    // Fixtures
    // ------------------------------------------------------------------

    /** Two groups: one with two chapters, one with none. Written out of alphabetical order on purpose. */
    private static SidebarLayout twoGroups() {
        return SidebarLayout.of(
                List.of(new SidebarLayout.Group("zzz_tools", "Toolsmith", false),
                        new SidebarLayout.Group("aaa_start", "Getting Started", false)),
                List.of(new SidebarLayout.ChapterRow("first_steps", "First Steps", "aaa_start"),
                        new SidebarLayout.ChapterRow("second_steps", "Second Steps", "aaa_start")));
    }

    /** The keys of the rows that would be drawn, in order. */
    private static List<String> keys(SidebarLayout sidebar) {
        return sidebar.rows().stream().map(SidebarLayout.Row::key).toList();
    }

    /** The titles of the rows that would be drawn, in order. */
    private static List<String> titles(SidebarLayout sidebar) {
        return sidebar.rows().stream().map(SidebarLayout.Row::title).toList();
    }

    /** The row with a given key, or a failure naming what was there instead. */
    private static SidebarLayout.Row row(SidebarLayout sidebar, String key) {
        return sidebar.rows().stream()
                .filter(candidate -> candidate.key().equals(key))
                .findFirst()
                .orElseThrow(() -> new AssertionError("no row for " + key + " -- the rows are "
                        + keys(sidebar)));
    }

    // ------------------------------------------------------------------
    // Building the tree
    // ------------------------------------------------------------------

    @Nested
    @DisplayName("building the tree")
    class Building {

        @Test
        @DisplayName("a chapter hangs under the group it names, one level in")
        void chaptersHangUnderTheirGroup() {
            SidebarLayout sidebar = twoGroups();

            assertEquals(List.of("group:zzz_tools", "group:aaa_start",
                            "chapter:first_steps", "chapter:second_steps"),
                    keys(sidebar));

            assertEquals(0, row(sidebar, "group:aaa_start").depth(), "a heading is not indented");
            assertEquals(1, row(sidebar, "chapter:first_steps").depth(),
                    "and a chapter sits one level in, which is the whole of what the indent says");
        }

        @Test
        @DisplayName("a group's chapters sit directly under it, not in a block at the end")
        void aGroupsChaptersFollowItImmediately() {
            // The interleaving, asserted directly because it is the one thing here that is invisible to
            // every count. The obvious first implementation added every heading and then every chapter,
            // which produces the right number of rows, the right depth for each of them, and a sidebar
            // that reads as somebody having dropped the headings in a heap at the top.
            //
            // `visibleRows()` preserves insertion order, so this is really a claim about the order the
            // outline is built in -- which is why it has to be asserted on the list and not on a size.
            SidebarLayout sidebar = SidebarLayout.of(
                    List.of(new SidebarLayout.Group("one", "One", false),
                            new SidebarLayout.Group("two", "Two", false)),
                    List.of(new SidebarLayout.ChapterRow("a", "A", "one"),
                            new SidebarLayout.ChapterRow("b", "B", "two"),
                            new SidebarLayout.ChapterRow("c", "C", "one")));

            assertEquals(List.of("group:one", "chapter:a", "chapter:c", "group:two", "chapter:b"),
                    keys(sidebar),
                    "each heading should be followed by its own chapters, in the order the chapter list "
                            + "gave them");
        }

        @Test
        @DisplayName("the order inside a group is the order the chapter list gave, not sorted")
        void chapterOrderIsTheServersOrder() {
            // A client that sorted would silently reorder somebody's book. The server's order is the
            // author's -- folder-manifest order for the folder layout -- and nothing here may touch it.
            // The heading's own row is in the list and is asserted with them, rather than skipped. A
            // fixture with one group has no way to leave it out of `keys()`, so an assertion naming only
            // the chapters is asserting a list the class does not produce -- which is what it did, and
            // the failure was one row longer than the expectation rather than a wrong order.
            //
            // Worth being explicit about the trap the assertion is for, since the extra row makes it
            // slightly less obvious: `zzz` sorts after `aaa`, so a client that sorted the chapter list
            // produces them the other way round. The order asserted is the order the *list* gave.
            SidebarLayout sidebar = SidebarLayout.of(
                    List.of(new SidebarLayout.Group("g", "G", false)),
                    List.of(new SidebarLayout.ChapterRow("zzz_first", "Z", "g"),
                            new SidebarLayout.ChapterRow("aaa_second", "A", "g")));

            assertEquals(List.of("group:g", "chapter:zzz_first", "chapter:aaa_second"), keys(sidebar));
        }

        @Test
        @DisplayName("the headings come back in the order the group list gave, not sorted")
        void groupOrderIsTheServersOrder() {
            // Which for the folder layout is folder-name order, computed on the server. Sorting again
            // here would be the same answer most of the time and a different one the moment a pack
            // renames a folder, so the client must not have an opinion.
            SidebarLayout sidebar = SidebarLayout.of(
                    List.of(new SidebarLayout.Group("zzz", "Z", false),
                            new SidebarLayout.Group("aaa", "A", false)),
                    List.of());

            assertEquals(List.of("group:zzz", "group:aaa"), keys(sidebar));
        }

        @Test
        @DisplayName("a group with no chapters is still a row")
        void anEmptyGroupIsStillDrawn() {
            // The server sent it as a heading, so it is a heading. Dropping it would make a group whose
            // chapters are all still being written invisible until the first one existed -- and the
            // distinction is deliberate on the server side, where the heading list is sent explicitly
            // for exactly this reason.
            SidebarLayout sidebar = twoGroups();

            assertTrue(keys(sidebar).contains("group:zzz_tools"));
            assertEquals(2, sidebar.groupCount(), "and it is counted");
        }

        @Test
        @DisplayName("two headings with one id do not throw: the first wins and the rest are dropped")
        void duplicateGroupIdsAreDropped() {
            // The outline refuses a duplicate key outright, so this would be an exception on the way to
            // a screen -- a bad message turning into a book that cannot be opened at all. Neither choice
            // is good; the one that leaves the screen usable is.
            SidebarLayout sidebar = SidebarLayout.of(
                    List.of(new SidebarLayout.Group("same", "First", false),
                            new SidebarLayout.Group("same", "Second", false)),
                    List.of());

            assertEquals(List.of("group:same"), keys(sidebar));
            assertEquals(1, sidebar.groupCount(), "and the second is not counted, since it is not drawn");
            assertEquals("First", row(sidebar, "group:same").title());
        }

        @Test
        @DisplayName("two chapters with one id do not throw either")
        void duplicateChapterIdsAreDropped() {
            SidebarLayout sidebar = SidebarLayout.of(
                    List.of(new SidebarLayout.Group("g", "G", false)),
                    List.of(new SidebarLayout.ChapterRow("same", "First", "g"),
                            new SidebarLayout.ChapterRow("same", "Second", "g")));

            assertEquals(List.of("group:g", "chapter:same"), keys(sidebar));
            assertEquals("First", row(sidebar, "chapter:same").title());
        }
    }

    // ------------------------------------------------------------------
    // The flat fallback
    // ------------------------------------------------------------------

    @Nested
    @DisplayName("a server that describes no groups")
    class FlatFallback {

        @Test
        @DisplayName("chapters with no group become roots, which is today's flat list")
        void chaptersWithNoGroupBecomeRoots() {
            // The compatibility case, and the reason there is no branch for it anywhere. A server older
            // than groups sends no headings, so every chapter's group id is empty; adding each as a root
            // draws exactly the flat chapter list this screen had before the sidebar existed.
            //
            // Depth zero rather than one, which is the assertion that matters: a chapter treated as a
            // member of some implicit group would be indented under nothing.
            SidebarLayout sidebar = SidebarLayout.of(List.of(), List.of(
                    new SidebarLayout.ChapterRow("first_steps", "First Steps", ""),
                    new SidebarLayout.ChapterRow("second_steps", "Second Steps", "")));

            assertEquals(List.of("chapter:first_steps", "chapter:second_steps"), keys(sidebar));
            assertEquals(0, row(sidebar, "chapter:first_steps").depth());
            assertEquals(0, sidebar.groupCount(), "and there are no headings to draw");
        }

        @Test
        @DisplayName("a chapter naming a heading the server did not send is shown at the top, not dropped")
        void aChapterNamingAMissingGroupIsStillShown() {
            // A server bug rather than a client one, and the two available responses are to lose the
            // chapter or to show it at the top level. Losing content is the worse one, and it is the one
            // a player can neither see nor report -- the chapter simply would not be there.
            SidebarLayout sidebar = SidebarLayout.of(
                    List.of(new SidebarLayout.Group("present", "Present", false)),
                    List.of(new SidebarLayout.ChapterRow("orphan", "Orphan", "absent")));

            assertEquals(List.of("chapter:orphan", "group:present"), keys(sidebar));
            assertEquals(0, row(sidebar, "chapter:orphan").depth(),
                    "an orphan is a root, so it is not indented under a heading that does not exist");
            assertEquals(2, sidebar.chapterCount() + sidebar.groupCount(),
                    "and it is still counted, so it is still drawn");
        }

        @Test
        @DisplayName("a half-converted pack draws its groups and its loose chapters, both in order")
        void groupsAndLooseChaptersCoexist() {
            // Not a hypothetical: the folder layout is read beside the flat one forever, so a pack
            // partway through converting has both. The loose chapters come first, like the legacy format's
            // default group -- which always sorts before the named ones -- so a converted pack's
            // welcome chapter reads as first rather than last.
            SidebarLayout sidebar = SidebarLayout.of(
                    List.of(new SidebarLayout.Group("converted", "Converted", false)),
                    List.of(new SidebarLayout.ChapterRow("inside", "Inside", "converted"),
                            new SidebarLayout.ChapterRow("loose", "Loose", "")));

            assertEquals(List.of("chapter:loose", "group:converted", "chapter:inside"), keys(sidebar));
        }
    }

    // ------------------------------------------------------------------
    // The labels
    // ------------------------------------------------------------------

    @Nested
    @DisplayName("what a row says")
    class Labels {

        @Test
        @DisplayName("an open heading carries the open marker, a closed one the closed marker")
        void headingsAreMarkedByState() {
            SidebarLayout sidebar = twoGroups();

            assertEquals("\u25bc Getting Started", row(sidebar, "group:aaa_start").label(),
                    "an open heading points down, because its chapters are below it");

            sidebar.toggle("group:aaa_start");

            assertEquals("\u203a Getting Started", row(sidebar, "group:aaa_start").label(),
                    "and a closed one points right, because its chapters are to itself");
        }

        @Test
        @DisplayName("a heading with nothing under it carries no marker at all")
        void anEmptyHeadingHasNoArrow() {
            // Not "an arrow that does nothing when clicked". A disclosure arrow on a row that cannot
            // disclose is a control that promises an action and then ignores the press -- which is worse
            // than no affordance, because the player concludes the click failed rather than that there
            // was nothing to open.
            //
            // This is why `collapsible` is a field on Row rather than being derived by whoever draws it:
            // the label and the toggle both need the same answer, and a second derivation is a second
            // place it can be wrong.
            SidebarLayout sidebar = twoGroups();

            SidebarLayout.Row empty = row(sidebar, "group:zzz_tools");
            assertFalse(empty.collapsible(), "an empty heading has nothing to disclose");
            assertEquals("Toolsmith", empty.label(), "so it is drawn as a plain label");

            // And it is still clickable, which is why the label matters: the whole row is the target,
            // so a press on this row has to be answered honestly rather than looking broken.
            assertFalse(sidebar.toggle("group:zzz_tools"),
                    "pressing it reports that nothing changed, so a screen does not rebuild for it");
        }

        @Test
        @DisplayName("a chapter never carries a marker, open or closed")
        void chaptersAreNeverMarked() {
            SidebarLayout sidebar = twoGroups();

            for (SidebarLayout.Row row : sidebar.rows()) {
                if (row.group()) {
                    continue;
                }
                assertEquals(row.title(), row.label(),
                        row.key() + " is a leaf, so its label is its title and nothing else");
                assertFalse(row.collapsible());
                assertFalse(row.expanded(),
                        "a leaf claiming to be expanded would draw an arrow if the collapsible check "
                                + "were ever dropped");
            }
        }
    }

    // ------------------------------------------------------------------
    // Collapsing
    // ------------------------------------------------------------------

    @Nested
    @DisplayName("collapsing")
    class Collapsing {

        @Test
        @DisplayName("a collapsed group hides exactly its own chapters, and keeps its row")
        void collapsingHidesOnlyItsOwnChapters() {
            SidebarLayout sidebar = twoGroups();

            assertTrue(sidebar.toggle("group:aaa_start"));

            assertEquals(List.of("group:zzz_tools", "group:aaa_start"), keys(sidebar),
                    "the heading stays -- it is the row carrying the closed marker, and the only way "
                            + "back -- and the other group's chapters are untouched");
            assertEquals(2, sidebar.chapterCount(),
                    "and the hidden chapters still exist: they are hidden, not removed");
            assertEquals(2, sidebar.rowCount(), "while the row count is what is actually drawn");
        }

        @Test
        @DisplayName("collapsing is reversible, and comes back to the same rows")
        void collapsingIsReversible() {
            SidebarLayout sidebar = twoGroups();
            List<String> before = keys(sidebar);

            sidebar.toggle("group:aaa_start");
            sidebar.toggle("group:aaa_start");

            assertEquals(before, keys(sidebar), "a round trip is exactly a round trip");
            assertTrue(sidebar.isExpanded("group:aaa_start"));
        }

        @Test
        @DisplayName("a press on a chapter reports that nothing changed")
        void aChapterCannotBeToggled() {
            // The answer the screen uses to avoid a widget rebuild: rebuilding every button for a press
            // that meant nothing costs a frame and can lose focus, and a chapter row is something a
            // player can press -- selecting it is what the press does, and this is a different question
            // with a different answer.
            SidebarLayout sidebar = twoGroups();

            assertFalse(sidebar.toggle("chapter:first_steps"));
            assertEquals(List.of("group:zzz_tools", "group:aaa_start", "chapter:first_steps",
                    "chapter:second_steps"), keys(sidebar));
        }

        @Test
        @DisplayName("a question about a row that does not exist is refused rather than answered false")
        void unknownKeysAreRefused() {
            // Through to the outline, which refuses an undeclared key. The two answers would be
            // indistinguishable to a caller and mean completely different things: a closed group, or a
            // typo in a key. A typo answered with false is a row that silently never appears.
            SidebarLayout sidebar = twoGroups();

            org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class,
                    () -> sidebar.toggle("group:nope"));
            org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class,
                    () -> sidebar.isExpanded("chapter:nope"));
        }
    }

    // ------------------------------------------------------------------
    // collapsedByDefault
    // ------------------------------------------------------------------

    @Nested
    @DisplayName("collapsedByDefault, which applies once")
    class CollapsedByDefault {

        @Test
        @DisplayName("a group declared collapsed starts closed, and its chapters are not drawn")
        void aCollapsedGroupStartsClosed() {
            // The authored intent, honoured the first time a tree is seen. The inversion is the whole of
            // how it travels: the server says "collapsed", the outline thinks in "expanded", and
            // `of` is the one place the two meet.
            SidebarLayout sidebar = SidebarLayout.of(
                    List.of(new SidebarLayout.Group("long", "A Long Progression", true),
                            new SidebarLayout.Group("short", "A Short One", false)),
                    List.of(new SidebarLayout.ChapterRow("deep", "Chapter One", "long"),
                            new SidebarLayout.ChapterRow("quick", "Chapter One", "short")));

            assertEquals(List.of("group:long", "group:short", "chapter:quick"), keys(sidebar));
            assertFalse(sidebar.isExpanded("group:long"), "the authored default is honoured");
            assertTrue(sidebar.isExpanded("group:short"), "and an unflagged group is open, as it always was");
            assertTrue(row(sidebar, "group:long").collapsible(),
                    "and it is still collapsible, so the player can open it");
        }

        @Test
        @DisplayName("nothing the class does afterwards re-applies the default")
        void theDefaultIsAppliedOnceAndThenItIsThePlayers() {
            // The property that makes a player's toggle survive. `of` seeds once, at build; everything
            // after that is the caller's state. A class that re-seeded on every read would undo each
            // toggle as it was made, which presents as the toggle buttons not working.
            SidebarLayout sidebar = SidebarLayout.of(
                    List.of(new SidebarLayout.Group("long", "Long", true)),
                    List.of(new SidebarLayout.ChapterRow("deep", "Deep", "long")));

            sidebar.toggle("group:long");

            assertEquals(List.of("group:long", "chapter:deep"), keys(sidebar),
                    "the player opened it, and reading the rows did not close it again");
        }
    }

    // ------------------------------------------------------------------
    // Keys
    // ------------------------------------------------------------------

    @Nested
    @DisplayName("row keys")
    class Keys {

        @Test
        @DisplayName("a group and a chapter with the same id are two different rows")
        void theTwoNamespacesDoNotCollide() {
            // Not hypothetical for the format this reads: a group folder's id is its folder name and a
            // chapter folder's id is its folder name, and both are drawn from one vocabulary by one
            // author. Without the prefixes the outline would refuse the second declaration -- throwing
            // on a perfectly good questline, with a message about a duplicate key in a tree the author
            // never wrote.
            SidebarLayout sidebar = SidebarLayout.of(
                    List.of(new SidebarLayout.Group("stone_age", "Stone Age", false)),
                    List.of(new SidebarLayout.ChapterRow("stone_age", "Also Stone Age", "stone_age")));

            assertEquals(List.of("group:stone_age", "chapter:stone_age"), keys(sidebar));
            assertEquals(1, sidebar.groupCount());
            assertEquals(1, sidebar.chapterCount(), "both exist, and neither replaced the other");
            assertEquals("Stone Age", row(sidebar, "group:stone_age").title());
            assertEquals("Also Stone Age", row(sidebar, "chapter:stone_age").title());
        }

        @Test
        @DisplayName("isGroupKey distinguishes the two, and says no for anything unrecognised")
        void isGroupKeyDistinguishes() {
            assertTrue(SidebarLayout.isGroupKey(SidebarLayout.groupKey("g")));
            assertFalse(SidebarLayout.isGroupKey(SidebarLayout.chapterKey("c")));
            assertFalse(SidebarLayout.isGroupKey("c"), "a bare id is not a group key");
            assertFalse(SidebarLayout.isGroupKey(""), "and neither is nothing");
            assertFalse(SidebarLayout.isGroupKey(null), "a null key routes somewhere, and not to a heading");
        }

        @Test
        @DisplayName("idOf hands back the id the key was built from")
        void idOfStripsThePrefix() {
            // What a click actually wants: the chapter to select, or the group to toggle. A caller that
            // stripped the prefix itself would be a second place that knows the prefixes exist.
            assertEquals("getting_started", SidebarLayout.idOf(SidebarLayout.groupKey("getting_started")));
            assertEquals("first_steps", SidebarLayout.idOf(SidebarLayout.chapterKey("first_steps")));
            assertEquals("bare", SidebarLayout.idOf("bare"),
                    "and an unprefixed key is handed back rather than mangled");
            assertEquals("", SidebarLayout.idOf(null));
        }

        @Test
        @DisplayName("every key a row carries round-trips through idOf")
        void everyRowKeyRoundTrips() {
            // The property the two methods have to satisfy jointly, asserted over real rows rather than
            // over hand-made strings -- because that is where a prefix could be dropped or doubled
            // without either method being wrong on its own.
            SidebarLayout sidebar = twoGroups();

            for (SidebarLayout.Row row : sidebar.rows()) {
                String id = SidebarLayout.idOf(row.key());
                assertEquals(row.id(), id, row.key() + " should round-trip to its own id");
                assertTrue(id.startsWith("zzz") || id.startsWith("aaa")
                                || id.startsWith("first") || id.startsWith("second"),
                        row.key() + " leaked a namespace prefix into its id: " + id);
            }
        }
    }

    // ------------------------------------------------------------------
    // Placing the rows
    // ------------------------------------------------------------------

    @Nested
    @DisplayName("placing the rows")
    class Placing {

        /** The column the sidebar is given in the real book. */
        private static final int COLUMN = BookGeometry.SIDEBAR_WIDTH - BookGeometry.EDGE * 2;

        @Test
        @DisplayName("the placed rows are the drawn rows, key for key")
        void theLayoutHoldsExactlyTheVisibleRows() {
            // The join between "which rows exist" and "where they go". A layout built from a different
            // list than `rows()` would place widgets for rows that are hidden, or leave a visible row
            // with no slot -- and a widget with no slot is hidden by ScrollView, so the symptom is a
            // row that is in the list and not on screen.
            SidebarLayout sidebar = twoGroups();
            Layout layout = sidebar.stack(COLUMN);

            List<String> placed = layout.slots().stream().map(slot -> String.valueOf(slot.key())).toList();
            assertEquals(keys(sidebar), placed);
        }

        @Test
        @DisplayName("a hidden chapter has no slot at all, rather than one it is not drawn in")
        void hiddenRowsAreAbsentFromTheLayout() {
            // Absent rather than zero-height, which is the whole reason the outline drops them. A slot
            // of height zero still exists, still claims its key, and would be handed a widget by
            // anything that registers one per row -- a widget ScrollView then hides, leaving a control
            // that exists and cannot be reached.
            SidebarLayout sidebar = twoGroups();
            sidebar.toggle("group:aaa_start");

            Layout layout = sidebar.stack(COLUMN);

            assertNull(layout.slot("chapter:first_steps"), "a hidden chapter must not be placed");
            assertNull(layout.slot("chapter:second_steps"));
            assertNotNull(layout.slot("group:aaa_start"), "but its heading still is -- as the marker");
        }

        @Test
        @DisplayName("a chapter row is indented and a heading is not, and the indent is in the slot")
        void theIndentIsInTheSlotRatherThanPaintedOn() {
            // In the slot rather than added by whoever draws the row, so the rectangle a widget is
            // placed in is the indented one. A caller that drew an indent instead would have a clickable
            // row wider than its visible text -- the fault this project has already recorded once, under
            // the name of a control that responded outside its own box.
            SidebarLayout sidebar = twoGroups();
            Layout layout = sidebar.stack(COLUMN);

            Slot heading = layout.slot("group:aaa_start");
            Slot chapter = layout.slot("chapter:first_steps");

            assertEquals(0, heading.x(), "a heading sits at the column's left edge");
            assertEquals(BookGeometry.SIDEBAR_INDENT, chapter.x(),
                    "a chapter is one indent in");
            assertEquals(COLUMN, heading.width(), "and a heading takes the whole column");
            assertEquals(COLUMN - BookGeometry.SIDEBAR_INDENT, chapter.width(),
                    "while a chapter's own box is narrower by the same amount -- which is what keeps the "
                            + "rectangle it is clicked in the one it is drawn in");
        }

        @Test
        @DisplayName("the indent and the width always sum back to the column, at any depth")
        void theIndentAndTheWidthAgree() {
            // The invariant behind the two assertions above, stated so it holds for a depth this fixture
            // does not have. If `Stack` ever stopped subtracting insets from a STRETCH width, the pair
            // would stop summing and this would say so rather than leaving a row that overhangs.
            SidebarLayout sidebar = twoGroups();
            Layout layout = sidebar.stack(COLUMN);

            for (Slot slot : layout.slots()) {
                assertEquals(COLUMN, slot.x() + slot.width(),
                        slot.key() + " does not reach the column's right edge: " + slot);
            }
        }

        @Test
        @DisplayName("the reported height is the bottom of the last row, with no trailing gap")
        void theHeightHasNoTrailingGap() {
            // A real bug, and a quiet one. The gap between rows was written *after* each row first, so
            // the list ended with one -- and `Layout.height()` is the bottom edge of the lowest slot, so
            // a trailing gap contributes nothing to it and simply vanished. The list could then be
            // scrolled four pixels past its own last row, giving the scrollbar a range with nothing in
            // it: an empty strip the player can scroll into and see nothing.
            //
            // Asserted as arithmetic rather than as "no gap", because the arithmetic is what the
            // scrollbar's range comes from: n rows and the n-1 gaps between them, and nothing else.
            SidebarLayout sidebar = twoGroups();
            Layout layout = sidebar.stack(COLUMN);

            int rows = sidebar.rowCount();
            int expected = rows * BookGeometry.SIDEBAR_ROW_HEIGHT
                    + (rows - 1) * BookGeometry.SIDEBAR_ROW_GAP;

            assertEquals(expected, layout.height(),
                    "with " + rows + " rows the content should be " + expected + " tall");
        }

        @Test
        @DisplayName("pitch is the row height plus the gap, and it is what one notch actually moves")
        void pitchMatchesTheRealSpacing() {
            // The wheel's step is derived from this, and the reason is the property asserted here: it is
            // not a third number, it is the distance between two rows. A scroll rate written out at the
            // call site drifts against the spacing by a couple of pixels a notch, and the symptom
            // arrives slowly -- a row that ends up half under the header, with nothing in the code
            // looking wrong.
            SidebarLayout sidebar = twoGroups();
            Layout layout = sidebar.stack(COLUMN);

            assertEquals(BookGeometry.SIDEBAR_ROW_HEIGHT + BookGeometry.SIDEBAR_ROW_GAP,
                    SidebarLayout.pitch());

            List<Slot> slots = layout.slots();
            for (int i = 1; i < slots.size(); i++) {
                assertEquals(SidebarLayout.pitch(), slots.get(i).y() - slots.get(i - 1).y(),
                        "row " + i + " is not one pitch below row " + (i - 1));
            }
        }

        @Test
        @DisplayName("a collapsed group's rows pack up rather than leaving gaps")
        void collapsingRemovesTheSpaceAsWellAsTheRows() {
            // The other half of the outline's "absent rather than zero-height" decision, and the one a
            // reader would not think to check: a list that dropped the rows but kept their height would
            // scroll over a stretch of nothing where a group's chapters used to be.
            SidebarLayout sidebar = twoGroups();
            int expanded = sidebar.stack(COLUMN).height();

            sidebar.toggle("group:aaa_start");
            int collapsed = sidebar.stack(COLUMN).height();

            assertEquals(2, sidebar.rowCount());
            assertEquals(2 * BookGeometry.SIDEBAR_ROW_HEIGHT + BookGeometry.SIDEBAR_ROW_GAP, collapsed,
                    "two rows and the one gap between them");
            assertTrue(collapsed < expanded,
                    "and hiding two rows must make the content shorter: " + collapsed + " vs " + expanded);
        }

        @Test
        @DisplayName("an empty sidebar produces an empty layout rather than throwing")
        void anEmptySidebarIsHarmless() {
            // What a client with no quests has, and what a server older than groups sends before any
            // chapter arrives. A layout of height zero is what the viewport is told, so the scrollbar's
            // range is zero and it is not drawn -- which is right, and is only right if this does not
            // throw on the way there.
            SidebarLayout sidebar = SidebarLayout.of(List.of(), List.of());
            Layout layout = sidebar.stack(COLUMN);

            assertEquals(List.of(), keys(sidebar));
            assertEquals(0, layout.height());
            assertTrue(layout.isEmpty());
            assertEquals(0, sidebar.rowCount());
            assertEquals(0, sidebar.groupCount());
            assertEquals(0, sidebar.chapterCount());
        }

        @Test
        @DisplayName("a column too narrow to hold the indent still produces a slot, not a negative width")
        void aTooNarrowColumnClampsRatherThanGoingNegative() {
            // A window can be dragged narrower than the sidebar's own insets. A negative-width slot
            // places a rectangle that reads correctly at every use and cannot be clicked, which is the
            // shape of bug that survives to a screenshot -- so `Stack` clamps to zero and this asserts
            // the clamp is what happens rather than an exception or a negative.
            SidebarLayout sidebar = twoGroups();
            Layout layout = sidebar.stack(BookGeometry.SIDEBAR_INDENT - 4);

            for (Slot slot : layout.slots()) {
                assertTrue(slot.width() >= 0, slot.key() + " has a negative width: " + slot);
            }
            assertEquals(0, layout.slot("chapter:first_steps").width(),
                    "the chapter's indent is wider than this column, so its box has no width left");
        }

        @Test
        @DisplayName("the same sidebar lays out identically twice")
        void layingOutIsDeterministic() {
            // The layout is rebuilt on every frame that draws, and the widget pass positions its
            // buttons from it. A layout whose rows moved between two calls would be a button that is
            // occasionally somewhere else -- which is unfalsifiable from a screenshot and maddening to
            // reproduce.
            SidebarLayout sidebar = twoGroups();

            Layout first = sidebar.stack(COLUMN);
            Layout second = sidebar.stack(COLUMN);

            assertEquals(first.slots(), second.slots());
            assertEquals(first.height(), second.height());
        }
    }

    // ------------------------------------------------------------------
    // Counts
    // ------------------------------------------------------------------

    @Nested
    @DisplayName("counts")
    class Counts {

        @Test
        @DisplayName("chapterCount counts hidden chapters and rowCount does not")
        void theTwoCountsAnswerDifferentQuestions() {
            // The distinction matters at two call sites and they are easy to conflate. `chapterCount` is
            // "how many chapters does this questline have", which a player-facing summary wants and
            // which collapsing must not change. `rowCount` is "how many rows is this list drawing",
            // which is what the content height is built from and which collapsing must change.
            SidebarLayout sidebar = twoGroups();

            assertEquals(2, sidebar.chapterCount());
            assertEquals(4, sidebar.rowCount(), "two headings and two chapters");

            sidebar.toggle("group:aaa_start");

            assertEquals(2, sidebar.chapterCount(), "hiding a chapter does not delete it");
            assertEquals(2, sidebar.rowCount(), "but it does stop it being drawn");
        }

        @Test
        @DisplayName("the counts agree with the rows actually produced")
        void theCountsMatchTheRows() {
            // A count is worth nothing if it disagrees with the list beside it, and neither of these is
            // derived from the other -- one is the maps' sizes, the other is the outline's. So the
            // agreement is a property to assert rather than a tautology.
            SidebarLayout sidebar = twoGroups();
            sidebar.toggle("group:aaa_start");

            assertEquals(sidebar.rowCount(), sidebar.rows().size());
            assertEquals(sidebar.rows().stream().filter(SidebarLayout.Row::group).count(),
                    sidebar.groupCount());
        }

        @Test
        @DisplayName("toString reports the counts, so a failure message is readable")
        void toStringIsUseful() {
            // Not decoration: this is what appears in the middle of another test's failure message, and
            // "SidebarLayout@1a2b3c" would say nothing about which fixture went wrong.
            String text = twoGroups().toString();

            assertTrue(text.contains("2 group"), text);
            assertTrue(text.contains("2 chapter"), text);
            assertTrue(text.contains("4 row"), text);
        }

        @Test
        @DisplayName("the titles come back without their markers")
        void titlesAreMarkerFree() {
            // `title` and `label` are different fields for a reason: a caller assembling a tooltip or a
            // spoken description wants the word, and a caller drawing a row wants the chevron. A single
            // field would force one of them to strip a character it should not have to know about.
            SidebarLayout sidebar = twoGroups();

            assertEquals(List.of("Toolsmith", "Getting Started", "First Steps", "Second Steps"),
                    titles(sidebar));
            assertTrue(row(sidebar, "group:aaa_start").label().contains("Getting Started"),
                    "while the label carries the marker as well as the word");
        }
    }

    @Nested
    @DisplayName("remembered expansion")
    class RememberedExpansion {

        /**
         * One group whose file says closed, one whose file says open — so "remembered" and "authored"
         * are different answers in both directions and a pass cannot come from one of them alone.
         */
        private SidebarLayout twoGroups() {
            return SidebarLayout.of(
                    List.of(new SidebarLayout.Group("shut", "Shut", true),
                            new SidebarLayout.Group("wide", "Wide", false)),
                    List.of(new SidebarLayout.ChapterRow("a", "A", "shut"),
                            new SidebarLayout.ChapterRow("b", "B", "wide")));
        }

        @Test
        @DisplayName("a group the player opened stays open, though its file says closed")
        void rememberedOpenWins() {
            SidebarLayout layout = twoGroups();
            assertFalse(layout.isExpanded(SidebarLayout.groupKey("shut")), "the authored default first");

            layout.applyExpansion(Map.of(SidebarLayout.groupKey("shut"), true));

            assertTrue(layout.isExpanded(SidebarLayout.groupKey("shut")),
                    "and the player's own choice after — this is what a rebuild was throwing away");
        }

        @Test
        @DisplayName("and one the player closed stays closed")
        void rememberedClosedWins() {
            SidebarLayout layout = twoGroups();

            layout.applyExpansion(Map.of(SidebarLayout.groupKey("wide"), false));

            assertFalse(layout.isExpanded(SidebarLayout.groupKey("wide")));
        }

        @Test
        @DisplayName("memory of a group that is no longer there is ignored")
        void unknownKeysAreIgnored() {
            // A renamed or deleted group leaves its old key behind, and a chapter key is not a group at
            // all. Neither may throw, and neither may touch the groups that do exist.
            SidebarLayout layout = twoGroups();

            layout.applyExpansion(Map.of(
                    SidebarLayout.groupKey("deleted"), true,
                    SidebarLayout.chapterKey("a"), true));

            assertFalse(layout.isExpanded(SidebarLayout.groupKey("shut")));
            assertTrue(layout.isExpanded(SidebarLayout.groupKey("wide")));
        }
    }

    // ------------------------------------------------------------------
    // Chapter gates
    // ------------------------------------------------------------------

    /**
     * The two answers a chapter's own gate can give the sidebar: a row that is shut, and a row that is
     * not there at all.
     *
     * <p>Both are decided by the caller — the state comes from the server and the hiding flag from the
     * file — and both are asserted here rather than in the screen, because "a withheld chapter is not a
     * row" is the kind of rule a screen cannot be asked about.
     */
    @Nested
    @DisplayName("a chapter's gate")
    class ChapterGates {

        @Test
        @DisplayName("a shut chapter is listed, and marked as shut")
        void lockedRowsAreMarked() {
            // The default: a gate a reader can see is a map that shows a closed road, and the row is where
            // the explanation lives on hover.
            SidebarLayout layout = SidebarLayout.of(List.of(), List.of(
                    new SidebarLayout.ChapterRow("open", "Open", "", false, false),
                    new SidebarLayout.ChapterRow("shut", "Shut", "", true, false)));

            assertFalse(row(layout, "chapter:open").locked());
            assertTrue(row(layout, "chapter:shut").locked(), "the drawing has to know to dim it");
            assertEquals(List.of("chapter:open", "chapter:shut"), keys(layout),
                    "and it is still a row: hiding is the author's choice, not the gate's");
        }

        @Test
        @DisplayName("a withheld chapter is absent, not a row with a flag on it")
        void hiddenChaptersAreAbsent() {
            // The mechanism a collapsed group already uses, and for the same reason: a row that is not in
            // the list cannot be drawn by a caller that forgot to check a flag. Nothing downstream of
            // this has to know the chapter exists.
            SidebarLayout layout = SidebarLayout.of(List.of(), List.of(
                    new SidebarLayout.ChapterRow("shown", "Shown", "", false, false),
                    new SidebarLayout.ChapterRow("withheld", "Withheld", "", true, true)));

            assertEquals(List.of("chapter:shown"), keys(layout));
            assertNull(layout.rows().stream()
                            .filter(candidate -> candidate.id().equals("withheld")).findFirst().orElse(null),
                    "a withheld chapter has no row to find");
        }

        @Test
        @DisplayName("a group whose every chapter is withheld still draws its heading")
        void anEmptyGroupStillDraws() {
            // A heading is a heading whether or not anything under it is showing — the same answer a
            // group whose chapters are merely collapsed gives, and the alternative (a group vanishing
            // because its only chapter is gated) would make the book reorder itself as chapters open.
            SidebarLayout layout = SidebarLayout.of(
                    List.of(new SidebarLayout.Group("g", "Group", false)),
                    List.of(new SidebarLayout.ChapterRow("hidden", "Hidden", "g", true, true)));

            assertEquals(List.of("group:g"), keys(layout));
        }
    }
}
