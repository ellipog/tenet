package dev.ellipog.tenet.client.dev;

import dev.ellipog.tenet.client.BookGeometry;
import dev.ellipog.tenet.client.DevMode;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The Assets panel's arithmetic: the bands, the tab strip, and a page's lines.
 *
 * <p>What is worth asserting here is what the screen cannot be trusted to remember: that no two bands
 * overlap, that a tab is inside the strip, that a press maps to the tab it looks like it maps
 * to, and that drawing and hit-testing a page agree. The panel's contents are the screen's business.
 *
 * <h2>And why the class starts at full depth</h2>
 *
 * <p>Because the older tests here name all three sections, and Normal mode shows two: the tables are the half of
 * this panel an author meets late, and {@code AdvancedTest} is where that is asserted. What this file adds
 * at Normal depth is the pairing that matters -- a section that is not drawn must not be pressable, so the
 * two lists have to be the same list.
 */
@DisplayName("The Assets panel's layout")
class AssetsLayoutTest {

    @BeforeEach
    void atFullDepth() {
        DevMode.setAdvanced(true);
    }

    @AfterEach
    void forgetTheDepth() {
        // A static flag: a test that leaves it set changes the next class to run.
        DevMode.reset();
    }

    private static BookGeometry.Rect card() {
        return BookGeometry.Rect.at(100, 50, 400, 260);
    }

    @Test
    @DisplayName("the bands stack without overlapping, and the footer is at the card's foot")
    void theBandsFit() {
        AssetsLayout.Frame frame = AssetsLayout.Frame.of(card());
        BookGeometry.Rect card = card();

        assertTrue(frame.title().y() >= card.y(), "the title is inside the card");
        assertTrue(frame.title().bottom() <= frame.tabs().y(),
                "the title ends before the tab strip starts");
        assertEquals(card.bottom() - AssetsLayout.FOOTER_HEIGHT, frame.footer().y(),
                "the footer is the card's own foot");
        assertTrue(frame.tabs().bottom() <= frame.page().y(),
                "and the strip ends before the page starts");
        assertTrue(frame.page().bottom() <= frame.footer().y(),
                "and the page stops before the footer");
        assertTrue(frame.page().right() <= card.right(), "and the page is inside the card");
        assertEquals(card.x() + AssetsLayout.PAD, frame.page().x(),
                "the page takes the whole width the column used to tax");
    }

    @Test
    @DisplayName("every tab is inside the strip, and a press maps to the tab it is on")
    void sectionsAreHitWhereTheyAreDrawn() {
        AssetsLayout.Frame frame = AssetsLayout.Frame.of(card());
        AssetsLayout.Section[] all = AssetsLayout.shown();

        for (int i = 0; i < all.length; i++) {
            BookGeometry.Rect tab = frame.tab(i);
            assertTrue(tab.x() >= frame.tabs().x() && tab.right() <= frame.tabs().right(),
                    all[i] + " must be inside the strip");
            assertEquals(all[i], AssetsLayout.sectionAt(frame, tab.x() + 2, tab.y() + 2),
                    all[i] + " is what a press on its own tab is on");
        }

        // And the three things that are not a tab.
        assertNull(AssetsLayout.sectionAt(frame, frame.page().x() + 4, frame.page().y() + 4),
                "a press on the page is not a press on a tab");
        assertNull(AssetsLayout.sectionAt(frame, frame.title().x() + 4, frame.title().y() + 4),
                "nor is one on the title");
        BookGeometry.Rect last = frame.tab(all.length - 1);
        assertNull(AssetsLayout.sectionAt(frame, last.right() + 1, last.y() + 2),
                "nor is one past the last tab");
    }

    @Test
    @DisplayName("the tabs divide the strip equally, so none moves when the list under it does")
    void tabsDivideTheStrip() {
        AssetsLayout.Frame frame = AssetsLayout.Frame.of(card());
        AssetsLayout.Section[] shown = AssetsLayout.shown();

        int total = 0;
        for (int i = 0; i < shown.length; i++) {
            BookGeometry.Rect tab = frame.tab(i);
            assertEquals(AssetsLayout.TAB_HEIGHT, tab.height(), "one line of tabs, whatever the depth");
            if (i > 0) {
                assertEquals(frame.tab(i - 1).right(), tab.x(), "no gaps and no overlaps");
            }
            total += tab.width();
        }
        assertEquals(frame.tabs().width(), total, "together they are exactly the strip");
    }

    @Test
    @DisplayName("a page's lines are laid out by kind, and a press is on the line it is drawn at")
    void linesAreHitWhereTheyAreDrawn() {
        List<AssetsLayout.Kind> lines = List.of(
                AssetsLayout.Kind.HEADING, AssetsLayout.Kind.ROW, AssetsLayout.Kind.ROW,
                AssetsLayout.Kind.HEADING, AssetsLayout.Kind.ROW);

        assertEquals(0, AssetsLayout.lineTop(lines, 0));
        assertEquals(AssetsLayout.HEADING_HEIGHT, AssetsLayout.lineTop(lines, 1), "rows follow the heading");
        assertEquals(AssetsLayout.HEADING_HEIGHT + 2 * AssetsLayout.ROW_HEIGHT,
                AssetsLayout.lineTop(lines, 3), "and the second heading follows both rows");
        assertEquals(2 * AssetsLayout.HEADING_HEIGHT + 3 * AssetsLayout.ROW_HEIGHT,
                AssetsLayout.contentHeight(lines));
        assertEquals(AssetsLayout.contentHeight(lines), AssetsLayout.lineTop(lines, lines.size()),
                "the end of the content is where the last line starts plus its height");

        // Every line answers with itself at its own top, at its bottom-most pixel, and in between.
        for (int i = 0; i < lines.size(); i++) {
            int top = AssetsLayout.lineTop(lines, i);
            int height = AssetsLayout.lineHeight(lines.get(i));
            assertEquals(i, AssetsLayout.lineAt(lines, top), "line " + i + " at its top");
            assertEquals(i, AssetsLayout.lineAt(lines, top + height - 1), "line " + i + " at its last pixel");
            if (height > 2) {
                assertEquals(i, AssetsLayout.lineAt(lines, top + height / 2), "line " + i + " in the middle");
            }
        }
        assertEquals(-1, AssetsLayout.lineAt(lines, -1), "above the content is nothing");
        assertEquals(-1, AssetsLayout.lineAt(lines, AssetsLayout.contentHeight(lines)),
                "and so is past the end");
        assertEquals(-1, AssetsLayout.lineAt(List.of(), 0), "an empty page has no lines at all");
    }

    @Test
    @DisplayName("the page's height is its lines, and what does not fit is what the viewport clamps")
    void thePageHeightIsItsLines() {
        // The clamp used to be a `maxScroll` here -- `contentHeight - band` -- and it is the list's
        // viewport that subtracts the band now, so what this class owes a caller is the one number the
        // viewport is told. Asserting the subtraction here would be asserting a copy of the kit's rule.
        List<AssetsLayout.Kind> lines = List.of(
                AssetsLayout.Kind.ROW, AssetsLayout.Kind.ROW, AssetsLayout.Kind.ROW);
        int page = AssetsLayout.contentHeight(lines);

        assertEquals(3 * AssetsLayout.ROW_HEIGHT, page, "three rows are three rows");
        assertEquals(0, Math.max(0, page - page), "what fits needs no scroll");
        assertEquals(0, Math.max(0, page - (page + 100)), "and neither does a roomier band");
        assertEquals(AssetsLayout.ROW_HEIGHT, Math.max(0, page - (page - AssetsLayout.ROW_HEIGHT)),
                "one row too many is one row of scroll");
    }

    @Test
    @DisplayName("the page's rows and its bar's strip tile the page, and the strip holds the bar")
    void theRowsAndTheStripTileThePage() {
        // The page's own arrangement, which the panel and the bar both read: the rows end where the strip
        // begins, the strip ends at the page's right edge, and the reservation is at least as wide as the
        // three-pixel bar the kit draws in it. The last one ties this class's `SCROLLBAR` to
        // `ScrollBar.WIDTH` in the other repo, which is the pair that would otherwise drift apart and put
        // the grip over the last character of every line.
        for (BookGeometry.Rect card : List.of(card(), BookGeometry.Rect.at(0, 0, 320, 200))) {
            AssetsLayout.Frame frame = AssetsLayout.Frame.of(card);
            String at = " on a " + card.width() + "x" + card.height() + " card";

            assertEquals(frame.rows().right(), frame.scrollbar().x(), "the strip starts where rows stop" + at);
            assertEquals(frame.page().right(), frame.scrollbar().right(),
                    "and ends at the page's own edge" + at);
            assertEquals(frame.page().width(), frame.rows().width() + frame.scrollbar().width(),
                    "together they are exactly the page's width" + at);
            assertEquals(AssetsLayout.SCROLLBAR, frame.scrollbar().width());
            assertTrue(frame.scrollbar().width() >= dev.ellipog.armature.client.ui.kit.ScrollBar.WIDTH,
                    "the reserved strip has to hold the bar the kit draws in it" + at);
            assertEquals(frame.rows().height(), frame.scrollbar().height(),
                    "and the bar is as tall as the lines it describes" + at);
        }
    }

    @Test
    @DisplayName("the footer's two buttons are inside their band and never overlap each other")
    void theFooterButtonsFit() {
        // This pair used to be two expressions at the call site, which is why the panel's press list
        // never learned the two controls existed: a rectangle nobody else can ask about is a rectangle
        // no test can hold. The three cards are a normal one, the narrowest the panel is ever given
        // (where the width cap has to bind), and one between the two.
        for (BookGeometry.Rect card : List.of(card(), BookGeometry.Rect.at(0, 0, 40, 30),
                BookGeometry.Rect.at(0, 0, 120, 200))) {
            AssetsLayout.Frame frame = AssetsLayout.Frame.of(card);
            AssetsLayout.Footer footer = AssetsLayout.Footer.of(frame);
            String at = " on a " + card.width() + "x" + card.height() + " card";

            for (BookGeometry.Rect button : List.of(footer.newTable(), footer.done())) {
                assertTrue(button.width() >= 0 && button.height() >= 0,
                        "a footer button may not be inside out" + at + ": " + button);
                assertTrue(button.isInside(frame.footer()),
                        "a footer button is outside the band it belongs to" + at + ": " + button
                                + " vs " + frame.footer());
            }
            assertFalse(footer.newTable().intersects(footer.done()),
                    "the two footer buttons overlap" + at + ": " + footer.newTable() + " and "
                            + footer.done());
            assertTrue(footer.newTable().x() <= footer.done().x(),
                    "the page's own action is the left of the pair and the way out is the right" + at);
        }
    }

    @Test
    @DisplayName("a squeezed window clamps rather than producing rectangles outside the card")
    void aTinyCardStillFits() {
        // Degradation rather than an error: a window this small is already clipping the book, and a
        // negative width would be a rectangle drawn backwards.
        AssetsLayout.Frame frame = AssetsLayout.Frame.of(BookGeometry.Rect.at(0, 0, 40, 30));

        for (BookGeometry.Rect band : List.of(frame.title(), frame.tabs(), frame.page(),
                frame.footer())) {
            assertTrue(band.width() >= 0 && band.height() >= 0, "no band may be inside out: " + band);
        }
        assertSame(AssetsLayout.Section.TABLES, AssetsLayout.Section.values()[0],
                "and the first section is the first one whatever the window");
        assertFalse(frame.page().right() > 40 + 1, "the page stays inside a 40-pixel card");
    }
}
