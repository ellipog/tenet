package dev.ellipog.tenet.client.dev;

import dev.ellipog.armature.client.ui.CanvasBackground;
import dev.ellipog.armature.client.ui.ThemeToken;
import dev.ellipog.armature.client.ui.inspect.InspectLayout;
import dev.ellipog.armature.client.ui.inspect.InspectRow;
import dev.ellipog.armature.client.ui.kit.Layout;
import dev.ellipog.armature.client.ui.kit.Measure;
import dev.ellipog.armature.client.ui.kit.Slot;
import dev.ellipog.armature.client.ui.kit.Stack;
import dev.ellipog.armature.client.ui.kit.Viewport;
import dev.ellipog.tenet.client.BookGeometry;
import dev.ellipog.tenet.client.DevMode;
import dev.ellipog.tenet.client.PanelLayout;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The tools panel's composition: its bands, its sections, and the rows inside them.
 *
 * <h2>What is asked here, and what cannot be</h2>
 *
 * <p>The panel is a floating column over a canvas that can be any size, so the questions that matter are
 * arithmetic: are its bands inside it at every window, do the sections fold, does every colour the theme
 * registry knows appear exactly once, and do the band's eight channel buttons tile the row they are given.
 * All of it is answerable without a client, which is why the composition is a game-free class -- the
 * screen that draws it is the part no test can instantiate.
 *
 * <h2>And why every test here runs at full depth</h2>
 *
 * <p>Because the appearance block <i>is</i> what most of this file asserts: the palette list, the colour
 * tokens, the canvas's rows, the folded sections. Normal mode drops that whole block -- one gate around one
 * call -- leaving the Book tab's three switches, so the class turns Advanced on and what Normal drops is
 * asserted in {@code AdvancedTest}.
 */
@DisplayName("The tools panel's layout")
class ToolsLayoutTest {

    @BeforeEach
    void atFullDepth() {
        DevMode.setAdvanced(true);
    }

    @AfterEach
    void forgetTheDepth() {
        // A static flag: a test that leaves it set changes the next class to run.
        DevMode.reset();
    }

    private static final Measure MEASURE = Measure.monospace(6, 9);

    private static List<ToolsLayout.Action> open() {
        return ToolsLayout.rows(true, true, true, true);
    }

    // ------------------------------------------------------------------
    // The rows and the sections
    // ------------------------------------------------------------------

    @Test
    @DisplayName("every colour the registry knows is in the panel, once, under its group's name")
    void everyColourIsListed() {
        List<ToolsLayout.Action> rows = open();

        Set<String> keys = new HashSet<>();
        int colours = 0;
        int groups = 0;
        for (ToolsLayout.Action row : rows) {
            assertTrue(keys.add(row.key()), () -> "a duplicate key: " + row.key());
            if (ToolsLayout.tokenId(row.key()) != null) {
                assertNotNull(ThemeToken.byId(ToolsLayout.tokenId(row.key())),
                        () -> "a colour row for an unknown token: " + row.key());
                colours++;
            }
            if (row.key().startsWith("group:")) {
                groups++;
                assertTrue(row.isHeading(),
                        "a group's name is drawn and does nothing -- a row that selects nothing lies");
            }
        }

        assertEquals(ThemeToken.ALL.size(), colours,
                "every token once: the colours list plus the canvas section's Ink");
        assertEquals(ThemeToken.Group.values().length, groups, "one label per group");
        // The ink is an inline chip, not a key of its own: that is what makes the picker, the preview and
        // the commit work on it without a second mechanism.
        assertTrue(rows.stream().anyMatch(row -> "canvasPattern".equals(ToolsLayout.tokenId(row.key()))
                        && row.isChip()),
                "the canvasPattern row is the canvas section's Ink, and still a colour row");
    }

    @Test
    @DisplayName("the colour section folds, and a folded one keeps nothing but its heading")
    void sectionsFold() {
        List<ToolsLayout.Action> openRows = open();
        List<ToolsLayout.Action> folded = ToolsLayout.rows(false, true, true, false);

        assertEquals(List.of("canvasPattern"),
                folded.stream().map(row -> ToolsLayout.tokenId(row.key()))
                        .filter(java.util.Objects::nonNull).toList(),
                "a folded colour section has no colours but the canvas section's Ink");
        for (ToolsLayout.Action row : folded) {
            // The switches, the shape's row, the canvas section's own rows, and headings -- and *no*
            // colour-section row, which is the assertion that matters: folding the section puts its
            // forty rows away and nothing else.
            assertTrue(row.key().equals(ToolsLayout.MOTION)
                            || row.key().equals(ToolsLayout.SNAP)
                            || row.key().equals(ToolsLayout.PROGRESS)
                            || row.key().equals(ToolsLayout.RADIUS)
                            || row.key().equals(ToolsLayout.CANVAS_PATTERN)
                            || row.key().equals(ToolsLayout.CANVAS_SPACING)
                            || row.key().equals(ToolsLayout.CANVAS_SPACE)
                            || row.key().equals(ToolsLayout.CANVAS_OPACITY)
                            || row.key().equals(ToolsLayout.CANVAS_COPY)
                            || row.key().equals(ToolsLayout.tokenKey("canvasPattern"))
                            || row.isHeading(),
                    () -> "a folded panel kept a row it should not have: " + row.key());
        }
        assertTrue(open().stream().anyMatch(row -> row.key().equals(ToolsLayout.RADIUS)),
                "the shape's row is in the panel");
        assertTrue(folded.stream().anyMatch(row -> row.key().equals(ToolsLayout.RADIUS)),
                "and folding the colours does not take it away -- it belongs to another section");
        assertTrue(openRows.size() > folded.size(), "unfolding shows more, which is the whole point");
    }

    @Test
    @DisplayName("the sections are marked open or folded, and the switches say what they will do")
    void theRowsSayWhatTheyAre() {
        List<ToolsLayout.Action> rows = open();

        ToolsLayout.Action section = rows.stream()
                .filter(row -> row.key().equals(ToolsLayout.COLOUR_SECTION)).findFirst().orElseThrow();
        assertTrue(section.label().startsWith("\u25bc"), "an open section points down");
        assertTrue(ToolsLayout.rows(true, true, true, false).stream()
                        .filter(row -> row.key().equals(ToolsLayout.COLOUR_SECTION)).findFirst().orElseThrow()
                        .label().startsWith("\u203a"), "a folded one points sideways");

        // The first switch: animation, a reading preference like the rest. Its label is the state, and
        // it is always in the panel.
        ToolsLayout.Action motion = rows.stream()
                .filter(row -> row.key().equals(ToolsLayout.MOTION)).findFirst().orElseThrow();
        assertTrue(motion.hasButton(), "the motion switch is a switch, not a label");
        assertEquals(ToolsLayout.ON, motion.buttonLabel());
        assertEquals(ToolsLayout.OFF, ToolsLayout.rows(false, true, true, true).stream()
                .filter(row -> row.key().equals(ToolsLayout.MOTION)).findFirst().orElseThrow()
                .buttonLabel());

        // The grid an editor's drag lands on, with Alt as the bypass.
        ToolsLayout.Action snap = rows.stream()
                .filter(row -> row.key().equals(ToolsLayout.SNAP)).findFirst().orElseThrow();
        assertTrue(snap.hasButton(), "the snap switch is a switch, not a label");
        assertEquals(ToolsLayout.ON, snap.buttonLabel());
        assertEquals(ToolsLayout.OFF, ToolsLayout.rows(true, false, true, true).stream()
                .filter(row -> row.key().equals(ToolsLayout.SNAP)).findFirst().orElseThrow().buttonLabel());

        // The last: the sidebar's chapter progress bars. A reading preference rather than an edit,
        // and its label is the state like every switch here.
        ToolsLayout.Action progress = rows.stream()
                .filter(row -> row.key().equals(ToolsLayout.PROGRESS)).findFirst().orElseThrow();
        assertTrue(progress.hasButton(), "the bars' switch is a switch, not a label");
        assertEquals(ToolsLayout.ON, progress.buttonLabel());
        assertEquals(ToolsLayout.OFF, ToolsLayout.rows(true, true, false, true).stream()
                .filter(row -> row.key().equals(ToolsLayout.PROGRESS)).findFirst().orElseThrow()
                .buttonLabel());

        List<String> switchOrder = rows.stream()
                .filter(ToolsLayout.Action::hasButton)
                .map(ToolsLayout.Action::key)
                .toList();
        assertEquals(List.of(ToolsLayout.MOTION, ToolsLayout.SNAP, ToolsLayout.PROGRESS),
                switchOrder, "the three switches sit together, in that order");

        assertTrue(rows.stream().noneMatch(row -> ToolsLayout.paletteId(row.key()) != null),
                "this call passes no palette, so it carries no palette rows -- the list is tested "
                        + "where one is offered");
    }

    @Test
    @DisplayName("the palette list is one row per theme, in order, and it folds")
    void thePaletteIsListed() {
        List<ToolsLayout.Palette> palette = List.of(
                new ToolsLayout.Palette("default", "Default"),
                new ToolsLayout.Palette("high_contrast", "High Contrast"),
                new ToolsLayout.Palette("obsidian", "Obsidian"));

        List<ToolsLayout.Action> rows = ToolsLayout.rows(true, true, true, true, palette, true);
        List<ToolsLayout.Action> paletteRows = rows.stream()
                .filter(row -> ToolsLayout.paletteId(row.key()) != null)
                .toList();
        assertEquals(List.of("default", "high_contrast", "obsidian"),
                paletteRows.stream().map(row -> ToolsLayout.paletteId(row.key())).toList(),
                "every palette, in the catalogue's order");
        assertEquals(List.of("Default", "High Contrast", "Obsidian"),
                paletteRows.stream().map(ToolsLayout.Action::label).toList(),
                "with the label the caller gave it -- the id and the display name differ");
        assertTrue(paletteRows.stream().allMatch(ToolsLayout.Action::isControl),
                "a palette row is itself the control, like a colour row");

        ToolsLayout.Action heading = rows.stream()
                .filter(row -> row.key().equals(ToolsLayout.PALETTE_SECTION)).findFirst().orElseThrow();
        assertTrue(heading.label().startsWith("\u25bc"), "an open section points down");

        List<ToolsLayout.Action> folded = ToolsLayout.rows(true, true, true, true, palette, false);
        assertTrue(folded.stream().noneMatch(row -> ToolsLayout.paletteId(row.key()) != null),
                "a folded palette keeps no rows");
        assertTrue(folded.stream().anyMatch(row -> row.key().equals(ToolsLayout.PALETTE_SECTION)),
                "but keeps its heading, which is the way back");
        assertTrue(ToolsLayout.rows(true, true, true, true, List.of(), true).stream()
                        .noneMatch(row -> row.key().equals(ToolsLayout.PALETTE_SECTION)),
                "a caller with no palettes gets no section, not a heading over nothing");

        // Between the switches and Shape: the mode first, then the first decision about the content.
        List<String> order = rows.stream().map(ToolsLayout.Action::key).toList();
        assertTrue(order.indexOf(ToolsLayout.PALETTE_SECTION) > order.indexOf(ToolsLayout.SNAP));
        assertTrue(order.indexOf(ToolsLayout.PALETTE_SECTION) < order.indexOf(ToolsLayout.SHAPE_SECTION));
    }

    @Test
    @DisplayName("the rows are placed in order, do not overlap, and the height counts them all")
    void theListIsPlaced() {
        // With and without a palette offered: the section's rows are ordinary rows and must place like
        // every other kind.
        for (List<ToolsLayout.Palette> offered
                : List.<List<ToolsLayout.Palette>>of(
                        List.of(new ToolsLayout.Palette("tome", "Tome")), List.of())) {
            for (boolean colours : new boolean[] {true, false}) {
                List<ToolsLayout.Action> rows = ToolsLayout.rows(true, true, true, true, offered, true);
                Layout layout = ToolsLayout.build(rows, 288, MEASURE);

                List<Slot> slots = layout.slots();
                assertEquals(rows.size(), slots.size(), "one slot per row");
                int lowest = 0;
                for (int i = 0; i < slots.size(); i++) {
                    final int at = i;
                    Slot slot = slots.get(at);
                    assertTrue(slot.width() >= 0 && slot.height() > 0, () -> "empty row: " + slot);
                    assertTrue(slots.get(at).y() >= (at == 0 ? 0 : slots.get(at - 1).bottom()),
                            () -> "row " + at + " overlaps the one before it");
                    lowest = Math.max(lowest, slot.bottom());
                }
                assertEquals(lowest, layout.height(), "the height is the bottom of the last row placed");
            }
        }
    }

    @Test
    @DisplayName("a switch's button sits in the gap its row reserved, against the column's edge")
    void switchButtonsSitInTheirRows() {
        Layout layout = ToolsLayout.build(open(), 288, MEASURE);

        for (String key : List.of(ToolsLayout.MOTION, ToolsLayout.SNAP, ToolsLayout.PROGRESS)) {
            Slot row = layout.slot(key);
            assertNotNull(row);
            Slot strip = ToolsLayout.strip(row);

            assertTrue(strip.x() >= row.right(),
                    () -> "the button overlaps the row's own label: " + strip);
            assertTrue(strip.right() <= 288 - ToolsLayout.STRIP_INSET,
                    () -> "the button left the column: " + strip);
            assertTrue(strip.y() >= row.y() && strip.bottom() <= row.bottom(),
                    () -> "the button left its row vertically: " + strip);
        }
    }

    // ------------------------------------------------------------------
    // The panel
    // ------------------------------------------------------------------

    @Test
    @DisplayName("the book keeps its actions row, and both tabs keep the list inside the panel")
    void theChapterFrameGivesTheListTheColumn() {
        BookGeometry.Rect rail = BookGeometry.Rect.at(10, 40, 800, 500);
        ToolsLayout.Frame quest = ToolsLayout.frame(rail, ToolsLayout.Tab.CHAPTER);
        ToolsLayout.Frame book = ToolsLayout.frame(rail, ToolsLayout.Tab.BOOK);

        assertEquals(book.panel(), quest.panel(), "one dock, whatever the tab");
        assertEquals(book.tabs(), quest.tabs(), "and one tab strip in it");

        // The one asymmetry left: the book tab reserves the actions row that writes the player's own
        // theme. The chapter tab leaves it empty.
        assertEquals(0, quest.actions().height(), "the chapter tab reserves no actions");
        assertTrue(book.actions().height() > 0, "the book's Revert and Save have their row");

        // The stack, in order, each band at or below the one before it, and every one inside the panel.
        assertTrue(quest.tabs().bottom() <= quest.list().y(), "the tabs are above the list");
        assertTrue(book.list().bottom() <= book.actions().y(), "and the list stops at the actions row");
        for (BookGeometry.Rect band : List.of(quest.tabs(), quest.list(),
                book.list(), book.actions())) {
            assertTrue(band.x() >= quest.panel().x() && band.right() <= quest.panel().right()
                            && band.y() >= quest.panel().y() && band.bottom() <= quest.panel().bottom(),
                    () -> "a band left the panel: " + band + " in " + quest.panel());
        }

        // And the same on a rail too small for the chrome, where the clamp has to keep every band inside
        // the panel rather than letting one go past the floor.
        ToolsLayout.Frame tiny = ToolsLayout.frame(BookGeometry.Rect.at(0, 0, 120, 60),
                ToolsLayout.Tab.CHAPTER);
        assertTrue(tiny.list().y() >= tiny.panel().y() && tiny.list().bottom() <= tiny.panel().bottom(),
                "a tiny rail still keeps the list inside the panel");
    }
    @Test
    @DisplayName("the drawer's new boxes sit inside their rows: the tabs, a chip, and a pair's halves")
    void theNewControlsSitInsideTheirRows() {
        ToolsLayout.Frame frame = ToolsLayout.frame(BookGeometry.Rect.at(10, 40, 300, 500),
                ToolsLayout.Tab.CHAPTER);
        BookGeometry.Rect tabs = frame.tabs();
        BookGeometry.Rect bookTab = ToolsLayout.tabBook(tabs);
        BookGeometry.Rect chapterTab = ToolsLayout.tabChapter(tabs);
        assertTrue(bookTab.isInside(tabs), "the Book tab is inside the strip: " + bookTab);
        assertTrue(chapterTab.isInside(tabs), "the Chapter tab is inside the strip: " + chapterTab);
        assertTrue(bookTab.right() <= chapterTab.x(), "and the two do not meet: "
                + bookTab + " vs " + chapterTab);
        assertEquals(tabs.right(), chapterTab.right(), "the pair reaches the strip's right edge");

        Slot row = new Slot("row", 20, 60, 260, ToolsLayout.ROW_HEIGHT);
        BookGeometry.Rect chip = ToolsLayout.chip(row);
        assertTrue(chip.x() >= row.x() && chip.right() <= row.right(), "the chip is inside its row");
        assertTrue(chip.width() >= 0 && chip.width() <= 132, "the chip is the capped width: " + chip);

        Slot left = ToolsLayout.pairLeft(row);
        Slot right = ToolsLayout.pairRight(row, "second");
        assertTrue(left.x() >= row.x() && left.right() <= right.x(), "the halves are left, then right");
        assertEquals(row.right(), right.right(), "and the right half reaches the row's edge");
        assertEquals("second", right.key(), "under the second action's key");
    }

    @Test
    @DisplayName("a control's own band is the shared answer: the row side by side, the control band stacked")
    void aControlsBandIsOneDerivation() {
        // What this replaces is two answers to one question. The chip had its own copy of the mode test, and
        // the chooser's menu looked its field up in the layout -- which has no slot for a pair's second half
        // and none at all for a row drawn in another view, so the *element* form's four choosers opened their
        // menu at whatever point the last menu was opened at (the canvas's right-click, or the origin). One
        // derivation, read by the chip, by the menu's anchor and by the widget placement.
        Slot row = new Slot("element.logo.tint", 20, 60, 260, InspectLayout.STACKED_ROW_HEIGHT);
        Slot band = InspectLayout.controlBand(row);

        assertEquals(row, ToolsLayout.controlBandOf(row, InspectLayout.Mode.SIDE_BY_SIDE),
                "side by side a row is its control's band: the label keeps its room on the same line");
        assertEquals(band, ToolsLayout.controlBandOf(row, InspectLayout.Mode.STACKED),
                "stacked it is the band under the label, which is what the widget placement uses too");
        assertEquals(InspectLayout.STACKED_CONTROL_HEIGHT,
                ToolsLayout.controlBandOf(row, InspectLayout.Mode.STACKED).height());
        assertEquals(ToolsLayout.chip(band), ToolsLayout.chipOf(row, InspectLayout.Mode.STACKED),
                "and the chip is built from that band rather than beside it");
        assertEquals(row.key(), ToolsLayout.controlBandOf(row, InspectLayout.Mode.STACKED).key(),
                "under the row's own key, so a widget still matches its slot");
    }

    @Test
    @DisplayName("a switch keeps its own width, against the same right edge the buttons share")
    void aSwitchIsItsOwnWidthInAStrip() {
        // **The toolkit's switch is 22 wide and knows it** (`ArmatureSwitch.WIDTH`, and
        // `PartyPanelLayout.controlSlots` places the party's two at exactly that width inside a row's strip). A
        // row that handed it a 40-pixel strip or a whole band stretched it -- *"switches look horrible"*: one
        // panel-wide track with a knob in the corner. It is a state you read before its label, so it belongs
        // against the column's edge, in the column every control in the list shares.
        Slot row = new Slot("group.collapsedByDefault", 20, 60, 220, ToolsLayout.SWITCH_HEIGHT);

        Slot side = ToolsLayout.switchSlot(row, InspectLayout.Mode.SIDE_BY_SIDE);
        assertEquals(ToolsLayout.SWITCH_WIDTH, side.width(), "its own width, not the strip's");
        assertTrue(side.height() <= row.height(), "and its row's height at most: " + side);
        assertEquals(ToolsLayout.strip(row).right(), side.right(),
                "right-aligned in the gap its row reserved, so switches and buttons share one edge");
        assertTrue(side.x() >= row.right(), "and outside the label's own slot: " + side + " vs " + row);
        assertEquals(row.key(), side.key(), "under the row's key, so the scroll view still matches it");

        // Stacked, the switch's home is the control band under the label -- the same right edge again.
        Slot tall = new Slot("element.p.corner", 20, 60, 260, InspectLayout.STACKED_ROW_HEIGHT);
        Slot band = InspectLayout.controlBand(tall);
        Slot stacked = ToolsLayout.switchSlot(tall, InspectLayout.Mode.STACKED);
        assertEquals(ToolsLayout.SWITCH_WIDTH, stacked.width());
        assertEquals(band.height(), stacked.height(), "the band's height, which is where a stacked control goes");
        assertEquals(band.right(), stacked.right(), "against the band's right edge");
        assertTrue(stacked.y() >= band.y() && stacked.bottom() <= band.bottom(),
                "and inside that band: " + stacked + " in " + band);
    }

    @Test
    @DisplayName("a chip follows its row's composition: the row side by side, the control's band when stacked")
    void chipsFollowTheirRowsComposition() {
        // The fault this pins, in the words of the report: *"colour thing is way too tall"*. A stacked row is
        // two bands -- a label's and its control's -- and the chip was asked for the whole of it, so it stood
        // three times the height of every control beside it with its own name centred down the middle. The two
        // answers have to differ, and the stacked one has to be the band the label leaves for it, because that
        // is where a widget in that row would go.
        Slot row = new Slot("element.logo.tint", 20, 60, 260, InspectLayout.STACKED_ROW_HEIGHT);
        Slot band = InspectLayout.controlBand(row);
        BookGeometry.Rect stacked = ToolsLayout.chipOf(row, InspectLayout.Mode.STACKED);

        assertEquals(ToolsLayout.chip(row), ToolsLayout.chipOf(row, InspectLayout.Mode.SIDE_BY_SIDE),
                "side by side the chip is the row's own rectangle, as it always was");
        assertEquals(ToolsLayout.chip(band), stacked,
                "stacked it is the control band's, which is where the drawing and the press both want it");
        assertEquals(InspectLayout.STACKED_CONTROL_HEIGHT, stacked.height(),
                "so it is one control tall rather than one row");
        assertTrue(stacked.height() < row.height(),
                "and shorter than the row it sits in: " + stacked);
        assertTrue(stacked.y() >= band.y() && stacked.bottom() <= band.bottom(),
                "and inside that band: " + stacked + " in " + band);
        assertEquals(band.right(), stacked.right(), "against the band's right edge, where a column of chips is");
    }



    @Test
    @DisplayName("every control is found by its own key, a pair's second half included")
    void everyControlIsFoundByItsKey() {
        // Two lists, because the two canvas shapes build different rows: a procedural pattern's is the
        // only place a PAIR exists at all -- the opacity field beside the space chooser -- and an
        // image's has none. A sweep that only ever saw the second list would have had nothing to check,
        // which is part of why the space chooser's press stayed broken under a green suite: no test had
        // ever put a pair row in a layout and asked for its second half.
        List<List<ToolsLayout.Action>> lists = List.of(
                ToolsLayout.canvasRows(new CanvasBackground(CanvasBackground.Kind.DOTS,
                        CanvasBackground.Space.GRAPH, 24), true, null),
                everyRow());

        int pairs = 0;
        for (List<ToolsLayout.Action> rows : lists) {
            Layout layout = ToolsLayout.build(rows, 288, MEASURE);
            for (ToolsLayout.Action row : rows) {
                Slot own = layout.slot(row.key());
                assertNotNull(own, "the layout placed no slot for " + row.key());
                assertEquals(own, ToolsLayout.controlSlot(layout, rows, row.key()),
                        "a row is found at its own slot: " + row.key());
                if (row.right() == null) {
                    continue;
                }
                pairs++;
                Slot half = ToolsLayout.controlSlot(layout, rows, row.right().key());
                assertEquals(ToolsLayout.pairRight(own, row.right().key()), half,
                        "a pair's second half is found where the panel draws it: " + row.right().key());
                assertTrue(half.x() >= own.x() && half.right() <= own.right(),
                        "and inside its own row: " + half + " in " + own);
            }
            assertNull(ToolsLayout.controlSlot(layout, rows, "no:such:control"),
                    "a key that names no control answers nothing");
        }
        assertTrue(pairs > 0, "no pair row was swept, so the half of the panel that has the bug this "
                + "exists for went unchecked");
        assertNull(ToolsLayout.controlSlot(null, List.of(), "x"), "no layout and no rows is nothing");
    }

    @Test
    @DisplayName("the dock's bands are the shared frame's, and its controls sit in the footer band")
    void theDockUsesTheSharedBands() {
        // **The point of the move.** The dock had a band arithmetic of its own -- six-pixel insets, a strip at
        // the panel's top, an actions row measured up from the floor. The three bands are the shared ones now,
        // so this asserts the *shared* constants rather than figures of this file's: the list is
        // `PanelLayout`'s body, the actions band is its footer, and the strip is inside its header.
        BookGeometry.Rect rail = BookGeometry.Rect.at(500, 40,
                dev.ellipog.tenet.client.PanelStack.WIDTH, 500);
        ToolsLayout.Frame book = ToolsLayout.frame(rail, ToolsLayout.Tab.BOOK);
        ToolsLayout.Frame chapter = ToolsLayout.frame(rail, ToolsLayout.Tab.CHAPTER);
        PanelLayout.Frame shared = PanelLayout.frame(rail, false, BookGeometry.MODAL_FOOTER_HEIGHT);
        PanelLayout.Frame bare = PanelLayout.frame(rail, false, 0);

        assertEquals(rail, book.panel(), "the panel is the rail it was given");
        assertEquals(shared.body(), book.list(), "the list *is* the shared body on the book tab");
        assertEquals(bare.body(), chapter.list(),
                "and on the chapter tab, whose frame reserves no footer at all");
        assertEquals(shared.footer(), book.actions(), "the book tab's controls band is the shared footer");
        assertEquals(0, chapter.actions().height(), "and the chapter tab reserves no band");

        // The strip lives in the header band and is centred in it, which is the one judgement this step made:
        // a two-tab strip is what names the panel, so it reads as that band's title rather than as a card
        // header with its title missing.
        BookGeometry.Rect header = shared.header();
        assertTrue(book.tabs().isInside(header), () -> "the strip left the header: " + book.tabs());
        assertEquals(ToolsLayout.TAB_HEIGHT, book.tabs().height(), "and keeps its own height");
        assertEquals(header.y() + header.height() / 2, book.tabs().y() + book.tabs().height() / 2,
                "centred in the band, at an odd height as well");

        // Revert and Save are controls *in* that band rather than the band itself: an ordinary control's
        // height, the panels' insets, and a gap between them.
        BookGeometry.Rect revert = ToolsLayout.revert(book.actions());
        BookGeometry.Rect save = ToolsLayout.save(book.actions());
        assertEquals(BookGeometry.OVERLAY_CONTROL_HEIGHT, revert.height(), "a control's height, not a band's");
        assertEquals(BookGeometry.MODAL_INSET, revert.x() - book.actions().x(), "inset as every footer is");
        assertEquals(BookGeometry.MODAL_INSET, book.actions().right() - save.right());
        assertEquals(BookGeometry.MODAL_FOOTER_GAP, revert.y() - book.actions().y(),
                "and sitting where a footer's controls sit");
        assertTrue(revert.isInside(book.actions()) && save.isInside(book.actions()),
                () -> "a control left its band: " + revert + " / " + save);
        assertFalse(revert.intersects(save), "the two do not meet");
    }

    @Test
    @DisplayName("the frame bands the rail it is given, and leaves every band inside it")
    void theFrameBandsTheRailItWasGiven() {
        // The rail is the caller's now: the screen asks `BookGeometry.panelRail` for it, which is the same
        // expression a docked column is anchored by, so the drawer and a column cannot disagree about the
        // edge they share. What this class owns is the bands *inside* it -- and the one rule those keep is
        // that none of them leaves it, because a band placed past its own surface is a press that answers
        // where nothing is drawn.
        BookGeometry.Rect rail = BookGeometry.Rect.at(150, 30,
                dev.ellipog.tenet.client.PanelStack.WIDTH, 500);
        ToolsLayout.Frame frame = ToolsLayout.frame(rail, ToolsLayout.Tab.BOOK);

        assertEquals(rail, frame.panel(), "the panel is the rail it was given, not one computed inside it");
        for (BookGeometry.Rect band : List.of(frame.tabs(), frame.list(), frame.actions())) {
            assertTrue(band.isInside(rail), () -> "a band left its rail: " + band + " in " + rail);
        }

        // A rail too small for its own chrome collapses its bands rather than inverting them. Bands of no
        // size are allowed to sit at a corner of a panel of no size -- there is nothing to press there --
        // so containment is asserted only where a band has an area to be contained.
        for (BookGeometry.Rect small : List.of(BookGeometry.Rect.at(0, 0, 120, 60),
                BookGeometry.Rect.at(0, 0, 0, 0))) {
            ToolsLayout.Frame tiny = ToolsLayout.frame(small, ToolsLayout.Tab.BOOK);
            assertTrue(tiny.list().width() >= 0 && tiny.list().height() >= 0,
                    () -> "a tiny rail inverted a band: " + tiny.list());
            if (tiny.list().width() > 0 && tiny.list().height() > 0) {
                assertTrue(tiny.list().isInside(small),
                        () -> "a tiny rail put its list outside itself: " + tiny.list() + " in " + small);
            }
        }
    }




    @Test
    @DisplayName("the book's rows fold to their heading, and a field keeps its label's room")
    void theBookRowsFold() {
        List<ToolsLayout.Action> folded = ToolsLayout.bookRows(false);
        assertEquals(1, folded.size(), "folded to its heading: " + folded);
        assertEquals(ToolsLayout.BOOK_SECTION, folded.get(0).key());
        assertTrue(folded.get(0).label().startsWith("\u203a"), "a folded section points sideways");

        List<ToolsLayout.Action> open = ToolsLayout.bookRows(true);
        assertEquals(List.of(ToolsLayout.BOOK_SECTION, ToolsLayout.BOOK_TITLE, ToolsLayout.BOOK_ICON,
                        ToolsLayout.BOOK_LOD, ToolsLayout.BOOK_STARTER),
                open.stream().map(ToolsLayout.Action::key).toList(), "the heading, then the fields");
        assertEquals(ToolsLayout.OFF,
                open.stream().filter(row -> row.key().equals(ToolsLayout.BOOK_LOD)).findFirst()
                        .orElseThrow().buttonLabel(),
                "tiered until the pack says otherwise");
        assertEquals(ToolsLayout.OFF,
                open.stream().filter(row -> row.key().equals(ToolsLayout.BOOK_STARTER)).findFirst()
                        .orElseThrow().buttonLabel(),
                "and no book on join until the pack asks for one");
        assertEquals(ToolsLayout.ON,
                ToolsLayout.bookRows(true, true).stream()
                        .filter(row -> row.key().equals(ToolsLayout.BOOK_LOD)).findFirst()
                        .orElseThrow().buttonLabel(),
                "and the switch shows the pack's own answer");

        Slot row = new Slot(ToolsLayout.BOOK_TITLE, 10, 20, 200, 16);
        Slot field = ToolsLayout.valueField(row, ToolsLayout.LABEL_ROOM);
        assertEquals(10 + ToolsLayout.LABEL_ROOM, field.x(), "the label's room is kept on the left");
        assertEquals(200 - ToolsLayout.LABEL_ROOM, field.width(), "and the field takes the rest");
    }

    // ------------------------------------------------------------------
    // Folds, help, and the texture row's controls
    // ------------------------------------------------------------------

    /** An image background at a tile size the tile case can recognize. */
    private static CanvasBackground image(CanvasBackground.Fit fit) {
        return new CanvasBackground(CanvasBackground.Kind.IMAGE, CanvasBackground.Space.SCREEN, 24,
                CanvasBackground.Tuning.DEFAULT,
                new CanvasBackground.Image("minecraft:textures/gui/bg.png", fit, 48));
    }

    /** Every row the three entry points build, with everything unfolded and a palette offered. */
    private static List<ToolsLayout.Action> everyRow() {
        List<ToolsLayout.Palette> palette = List.of(new ToolsLayout.Palette("tome", "Tome"));
        CanvasBackground image = image(CanvasBackground.Fit.COVER);
        List<ToolsLayout.Action> rows = new java.util.ArrayList<>();
        rows.addAll(ToolsLayout.rows(true, true, true, true, palette, true, image, true, null));
        rows.addAll(ToolsLayout.chapterAppearanceRows(palette, true, true, true, image, true, null));
        rows.addAll(ToolsLayout.bookRows(true));
        return List.copyOf(rows);
    }

    @Test
    @DisplayName("every section of both tabs folds, and nothing else does")
    void foldsCoversEverySection() {
        // The predicate both the drawing and the widget pass read. It was a hand-written list of three
        // at the widget site once, and the Canvas and Quest Book headings were drawn promising a fold
        // with nothing behind them -- so the tool rows' half is asserted as an exact set, not a "contains".
        Set<String> foldable = everyRow().stream().map(ToolsLayout.Action::key)
                .filter(ToolsLayout::folds).collect(java.util.stream.Collectors.toSet());
        assertEquals(Set.of(ToolsLayout.APPEARANCE_SECTION, ToolsLayout.BOOK_SECTION,
                        ToolsLayout.CANVAS_SECTION, ToolsLayout.COLOUR_SECTION,
                        ToolsLayout.PALETTE_SECTION),
                foldable, "the five book sections, exactly -- an extra key would be a heading with a widget "
                        + "the panel does not mark, and a missing one a marker with no widget behind it");

        // And the chapter's four, which are not in those rows: one predicate for both tabs, because a
        // heading folds when it is a section whichever file the section is about. The chapter tab's
        // headings get their widget from the screen's own loop, so a key here that the screen did not
        // place would be a section drawn with a rule and no press -- which is the fault this list is for.
        for (String section : List.of(ToolsLayout.CHAPTER_IDENTITY, ToolsLayout.CHAPTER_RULES,
                ToolsLayout.CHAPTER_GROUP, ToolsLayout.CHAPTER_QUESTS)) {
            assertTrue(ToolsLayout.folds(section), section + " is a section and must fold");
        }
        assertFalse(ToolsLayout.folds(ToolsLayout.SHAPE_SECTION),
                "Shape is a heading but not a fold: it has nothing to put away");
        assertFalse(ToolsLayout.folds(null), "and no key at all folds");
    }

    @Test
    @DisplayName("the stacked composition gives a control's row the label's band and the control's")
    void theStackedCompositionIsTaller() {
        // The chapter tab is one file's labels at the dock's width, and its longest is longer than any
        // strip this panel reserves -- so its rows stack, and this is the arithmetic that has to survive
        // the move onto the tools rows. The heights are the kit's own (`InspectLayout`), read here rather
        // than restated: a second copy of them would be a second answer to "how tall is a stacked row",
        // and the drawing and the widget placement would then be able to disagree about where the line
        // between the two bands is.
        List<ToolsLayout.Action> rows = List.of(
                ToolsLayout.Action.heading(ToolsLayout.CHAPTER_RULES, "Rules"),
                ToolsLayout.Action.field("defaultPrerequisiteMode", "Prereq Mode"),
                ToolsLayout.Action.choice("progression", "Progression"),
                ToolsLayout.Action.toggle("group.collapsedByDefault", "Collapsed", "Off"),
                ToolsLayout.Action.value("v:description", "Description", "A chapter's own words"),
                ToolsLayout.Action.row("v:quest:a", "1. a_quest"));

        Layout sideBySide = ToolsLayout.build(rows, 200, MEASURE);
        Layout stacked = ToolsLayout.build(rows, 200, MEASURE, InspectLayout.Mode.STACKED);

        assertEquals(ToolsLayout.ROW_HEIGHT, sideBySide.slot("progression").height(),
                "side by side, a field, a choice and a row are one row tall");
        assertEquals(InspectLayout.STACKED_ROW_HEIGHT, stacked.slot("progression").height(),
                "stacked, a choice gets the label's band and the control's beneath it");
        assertEquals(InspectLayout.STACKED_ROW_HEIGHT, stacked.slot("defaultPrerequisiteMode").height(),
                "and so does a field -- which is the row that needed the composition");
        assertEquals(InspectLayout.STACKED_ROW_HEIGHT, stacked.slot("group.collapsedByDefault").height(),
                "and a switch, whose button spans the band instead of sitting in a strip");
        assertEquals(ToolsLayout.ROW_HEIGHT, stacked.slot("v:description").height(),
                "while a read-only row is one line in either composition: it has no control to band");
        assertEquals(ToolsLayout.ROW_HEIGHT, stacked.slot("v:quest:a").height(),
                "and so is a row that *is* the control -- its widget draws its own label, so a label band "
                        + "above it would print the same name twice");

        Slot choice = stacked.slot("progression");
        Slot label = InspectLayout.labelBand(choice);
        Slot control = InspectLayout.controlBand(choice);
        assertEquals(InspectLayout.STACKED_LABEL_HEIGHT, label.height());
        assertEquals(InspectLayout.STACKED_CONTROL_HEIGHT, control.height());
        assertEquals(choice.height(), label.height() + control.height(),
                "the two bands tile the row exactly once, with nothing left over and nothing shared");
        assertTrue(label.bottom() <= control.y(), "and the label's band ends where the control's starts");
    }

    @Test
    @DisplayName("every row that is not a colour or a group name offers its help")
    void everyRowHasHelp() {
        // The table is a property of the key, so "every control has help" is a thing a test asserts
        // rather than a sentence a reader hopes. Colour rows and group names are deliberately absent:
        // a swatch shows the colour and the preview rings where it paints, so a sentence would be a
        // third description -- see `ToolsLayout.help`.
        for (ToolsLayout.Action row : everyRow()) {
            if (ToolsLayout.tokenId(row.key()) != null || row.key().startsWith("group:")) {
                continue;
            }
            assertNotNull(ToolsLayout.help(row.key()), () -> "no help for row " + row.key());
        }
    }

    @Test
    @DisplayName("helpAt names the row under a point, and answers nothing outside one")
    void helpAtFindsTheRowUnderThePointer() {
        List<ToolsLayout.Action> rows = everyRow();
        Layout layout = ToolsLayout.build(rows, 288, MEASURE);
        Viewport view = Viewport.fixed().bounds(40, 60, 288, 400);

        Slot radius = ToolsLayout.onScreen(view, layout.slot(ToolsLayout.RADIUS));
        ToolsLayout.Hovered hit = ToolsLayout.helpAt(rows, layout, view, radius.x() + 1, radius.y() + 1);
        assertNotNull(hit, "the radius row is under the pointer");
        assertEquals(ToolsLayout.RADIUS, hit.key(), "and the key travels with the sentence");
        assertEquals(ToolsLayout.help(ToolsLayout.RADIUS), hit.help());

        // A colour row is a real row with no help: the answer is nothing, not the row beside it.
        Slot colour = ToolsLayout.onScreen(view, layout.slot(ToolsLayout.tokenKey("panel")));
        assertNull(ToolsLayout.helpAt(rows, layout, view, colour.x() + 1, colour.y() + 1),
                "a colour row offers no help, and no other row answers for it");
        // And outside the list there is no row at all, in either direction.
        assertNull(ToolsLayout.helpAt(rows, layout, view, view.originX() - 1, radius.y() + 1),
                "left of the list is no row");
        assertNull(ToolsLayout.helpAt(rows, layout, view, radius.x() + 1, view.originY() - 1),
                "and above it neither");
    }


    @Test
    @DisplayName("the texture row is a picture, then a field, then a browse button")
    void theTextureRowHasThreeBoxes() {
        Slot row = new Slot(ToolsLayout.CANVAS_TEXTURE, 10, 20, 288, ToolsLayout.ROW_HEIGHT);
        Slot thumb = ToolsLayout.textureThumb(row);
        Slot field = ToolsLayout.textureField(row);
        Slot browse = ToolsLayout.textureBrowse(row);

        assertEquals(row.key(), field.key(), "the field is the row's own widget, under the row's key");
        assertEquals(ToolsLayout.TEXTURE_THUMB, thumb.width(), "the picture's box is its own size");
        assertEquals(thumb.width(), thumb.height(), "and square");
        assertTrue(thumb.right() <= field.x(), () -> "the picture overlaps the field: " + thumb + field);
        assertTrue(field.right() <= browse.x(), () -> "the field runs under the button: " + field + browse);
        assertEquals(ToolsLayout.TEXTURE_BROWSE, browse.width(), "the button matches a stepper's arrow");
        assertTrue(browse.right() <= row.right() - ToolsLayout.STRIP_INSET,
                "and stays inside the row's own inset");

        // The hit test is the drawing's own derivation, so the button that is seen is the one pressed.
        Viewport view = Viewport.fixed().bounds(40, 60, 288, 120);
        int midY = 60 + row.y() + row.height() / 2;
        assertTrue(ToolsLayout.textureBrowseAt(view, row, 40 + browse.x() + browse.width() / 2.0, midY),
                "the browse button answers where it is drawn");
        assertFalse(ToolsLayout.textureBrowseAt(view, row, 40 + thumb.x() + thumb.width() / 2.0, midY),
                "the picture is not a button");
        assertFalse(ToolsLayout.textureBrowseAt(view, row, 40 + field.x() + field.width() / 2.0, midY),
                "and neither is the field");
        assertFalse(ToolsLayout.textureBrowseAt(view, null, midY, midY),
                "a tab without the row has no button to hit");
    }

    @Test
    @DisplayName("a tiled image pairs opacity with the space chooser; a cover keeps opacity alone")
    void imageTileShowsItsSpace() {
        List<ToolsLayout.Action> tiled =
                ToolsLayout.canvasRows(image(CanvasBackground.Fit.TILE), true, null);
        ToolsLayout.Action opacity = tiled.stream()
                .filter(row -> row.key().equals(ToolsLayout.CANVAS_OPACITY))
                .findFirst().orElseThrow(() -> new AssertionError("no opacity row for a tile: " + tiled));
        assertNotNull(opacity.right(), "a tile pairs opacity with its anchor, like the flat patterns");
        assertEquals(ToolsLayout.CANVAS_SPACE, opacity.right().key());

        List<ToolsLayout.Action> covered =
                ToolsLayout.canvasRows(image(CanvasBackground.Fit.COVER), true, null);
        assertTrue(covered.stream().noneMatch(row ->
                        row.key().equals(ToolsLayout.CANVAS_SPACE)
                                || (row.right() != null
                                        && row.right().key().equals(ToolsLayout.CANVAS_SPACE))),
                "a cover is one screen-fixed blit and offers no anchor: " + covered);
        ToolsLayout.Action alone = covered.stream()
                .filter(row -> row.key().equals(ToolsLayout.CANVAS_OPACITY))
                .findFirst().orElseThrow(() -> new AssertionError("no opacity row for a cover: " + covered));
        assertNull(alone.right(), "so a cover's opacity takes the full row");
    }

    private static boolean inside(BookGeometry.Rect inner, BookGeometry.Rect outer) {
        return inner.x() >= outer.x() && inner.y() >= outer.y()
                && inner.right() <= outer.right() && inner.bottom() <= outer.bottom();
    }

    @Test
    @DisplayName("a colour's channel value comes out of the packed colour it is drawn from")
    void channelValues() {
        int argb = 0x80402010;
        assertEquals(0x80, ToolsPanel.channelValue(argb, "A"));
        assertEquals(0x40, ToolsPanel.channelValue(argb, "R"));
        assertEquals(0x20, ToolsPanel.channelValue(argb, "G"));
        assertEquals(0x10, ToolsPanel.channelValue(argb, "B"));
        assertFalse(ToolsLayout.rows(true, true, true, true).isEmpty(),
                "the switches are always there, whatever else is folded");
    }

    // ------------------------------------------------------------------
    // Where the radius row is pressed
    // ------------------------------------------------------------------

    /**
     * The list's coordinates to the screen's, checked against the viewport's own documented meaning rather
     * than against the method itself.
     *
     * <p>This is the fault that made the arrows invisible: a row's slot is in the <i>list's</i> space, and a
     * caller that used it as a screen rectangle placed the controls outside the panel. So the expectation
     * below is written out by hand — the view's left and top, plus the content position, minus the scroll —
     * and never obtained by calling the thing under test, which would agree with a mapping that put the row
     * anywhere at all.
     */
    @Test
    @DisplayName("a row's slot is put where the viewport says it is, scroll and all")
    void rowsMapThroughTheViewport() {
        Layout layout = ToolsLayout.build(open(), 200, MEASURE);
        Slot row = layout.slot(ToolsLayout.RADIUS);

        Viewport view = Viewport.fixed().bounds(40, 60, 200, 120);
        Slot mapped = ToolsLayout.onScreen(view, row);
        assertEquals(40 + row.x(), mapped.x());
        assertEquals(60 + row.y(), mapped.y());
        assertEquals(row.width(), mapped.width());
        assertEquals(row.height(), mapped.height());

        // And it follows the list: the same row, scrolled up by thirty, is thirty pixels higher.
        view.setOffset(0, -30);
        Slot scrolled = ToolsLayout.onScreen(view, row);
        assertEquals(40 + row.x(), scrolled.x());
        assertEquals(60 + row.y() - 30, scrolled.y());
    }
}
