package dev.ellipog.tenet.client;

import dev.ellipog.tenet.client.BookGeometry.Rect;
import dev.ellipog.tenet.client.PanelStack.Fold;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * What is inside a docked column, and the four decisions around it — without a game.
 *
 * <h2>Why the sweep again</h2>
 *
 * <p>Because the failures this guards are size-dependent, exactly as {@code BookGeometryTest}'s are: a
 * column's bands hold together at 340 pixels wide and come apart at 200 on a laptop, and a fold decision
 * that is right on a wide window is wrong on the screenshot-sized one. A single case asserts the least
 * interesting size there is.
 *
 * <p>The clearance between the handle and the body is the one assertion here that is about <b>two
 * constants rather than a computation</b>: nothing in the drawing would break if the handle grew past
 * {@code MODAL_INSET} — a text field's first pixels would simply become a resize — so the relation is
 * asserted rather than left to whoever widens one of the two.
 */
@DisplayName("the docked column's insides")
class PanelLayoutTest {

    /** Rails to sweep: an ordinary column, a wide one, a narrow one, and two degenerate ones. */
    private static List<Rect> rails() {
        List<Rect> out = new ArrayList<>();
        for (int w : new int[] {0, 1, 40, 240, 260, 340, 420, 660}) {
            for (int h : new int[] {0, 60, 120, 260, 480}) {
                out.add(Rect.at(500, 40, w, h));
            }
        }
        return out;
    }

    private static Rect anOrdinaryColumn() {
        return Rect.at(500, 40, PanelStack.WIDTH, 320);
    }

    @Test
    @DisplayName("every band is inside the column, at every size swept")
    void everyBandIsInsideTheColumn() {
        for (Rect rail : rails()) {
            for (boolean carriesFold : new boolean[] {true, false}) {
                PanelLayout.Frame frame = PanelLayout.frame(rail, carriesFold);
                String where = rail + (carriesFold ? " (carries the fold)" : "");

                assertTrue(frame.handle().isInside(rail), "the handle is inside the column: " + where);
                assertTrue(frame.header().isInside(rail), "the header is inside it: " + where);
                assertTrue(frame.body().isInside(rail), "the body is inside it: " + where);
                assertTrue(frame.footer().isInside(rail), "the footer is inside it: " + where);
                if (frame.fold() != null) {
                    assertTrue(frame.fold().isInside(frame.header()),
                            "the fold control is in the header, not over the edge: " + where);
                }
            }
        }
    }

    @Test
    @DisplayName("no band has a negative size, however small the column is")
    void nothingIsNegative() {
        for (Rect rail : rails()) {
            PanelLayout.Frame frame = PanelLayout.frame(rail, true);
            for (Rect band : new Rect[] {frame.handle(), frame.header(), frame.body(), frame.footer()}) {
                assertTrue(band.width() >= 0 && band.height() >= 0,
                        "a rectangle of negative width is a crash waiting for a caller: " + band);
            }
        }
    }

    @Test
    @DisplayName("the body sits between the header and the footer's band, and they do not overlap")
    void theBandsDoNotOverlap() {
        Rect rail = anOrdinaryColumn();
        PanelLayout.Frame frame = PanelLayout.frame(rail, true);

        assertEquals(rail.y() + PanelLayout.BODY_TOP, frame.body().y(), "the body starts below the header");
        assertTrue(frame.header().bottom() <= frame.body().y(),
                "the header's rule is above the first row of the body");
        assertTrue(frame.body().bottom() <= frame.footer().y(),
                "and the last row of the body is above the footer's band");
        assertEquals(rail.x() + PanelLayout.BODY_INSET, frame.body().x(), "the body is inset from the side");
        assertEquals(rail.width() - PanelLayout.BODY_INSET * 2, frame.body().width(),
                "the same inset on both sides, which is also where the scrollbar's room comes from");
    }

    @Test
    @DisplayName("the body has room for a row at any size a panel is actually drawn at")
    void theBodyHasRoomForARow() {
        for (int width : new int[] {PanelStack.MIN_WIDTH, PanelStack.WIDTH, PanelStack.WIDE_MIN_WIDTH,
                PanelStack.WIDE_WIDTH}) {
            Rect rail = Rect.at(500, 40, width, 320);
            PanelLayout.Frame frame = PanelLayout.frame(rail, true);
            assertTrue(frame.body().width() > 0, "a column of " + width + " has a body with width");
            assertTrue(frame.body().height() > 20,
                    "and room for at least one row plus the air around it, at " + width);
        }
    }

    @Test
    @DisplayName("the handle is the column's own inner edge, and no content may be drawn there")
    void theHandleIsInsideTheColumnAndClearsTheBody() {
        Rect rail = anOrdinaryColumn();
        PanelLayout.Frame frame = PanelLayout.frame(rail, true);

        assertEquals(rail.x(), frame.handle().x(), "the handle is on the inner edge");
        assertEquals(BookGeometry.PANEL_HANDLE, frame.handle().width());
        assertTrue(frame.handle().bottom() <= rail.bottom(), "and runs the column's full height");

        // The relation the two constants have to keep: the body's inset is the room that keeps a text
        // field's first pixel from being a resize.
        assertTrue(BookGeometry.PANEL_HANDLE < PanelLayout.BODY_INSET,
                "the handle must be narrower than the body's inset, or a control starts under the grab band");
        assertFalse(frame.handle().intersects(frame.body()), "so the two never touch");
    }

    @Test
    @DisplayName("the fold control is on the column that holds the child, and the title stops short of it")
    void theFoldControlBelongsToTheChildColumn() {
        Rect rail = anOrdinaryColumn();

        assertNotNull(PanelLayout.frame(rail, true).fold(),
                "the column the press is about carries the control");
        assertNull(PanelLayout.frame(rail, false).fold(),
                "there is nothing to fold on the column a child is folded into");

        // Swept rather than asserted at one width, because the header text is *truncated to this number* by
        // three panels now -- a table's name, a picker's subject and a texture's path -- so a width where it
        // crossed the control would be a title drawn through a button rather than a number that drifted.
        for (int width : new int[] {PanelStack.MIN_WIDTH, PanelStack.SECOND_WIDTH, PanelStack.WIDTH,
                PanelStack.MAX_WIDTH, PanelStack.WIDE_MIN_WIDTH, PanelStack.WIDE_WIDTH}) {
            Rect each = Rect.at(500, 40, width, 320);
            PanelLayout.Frame frame = PanelLayout.frame(each, true);
            String at = " at width " + width;

            assertEquals(each.right() - BookGeometry.MODAL_INSET - PanelLayout.FOLD_WIDTH, frame.fold().x(),
                    () -> "the control is inset from the column's outer edge" + at);
            assertTrue(PanelLayout.headerRight(each, true) < frame.fold().x(),
                    () -> "and the header's own content stops before it, so a long title cannot run under it"
                            + at);
            assertTrue(PanelLayout.headerRight(each, true) > each.x() + PanelLayout.BODY_INSET,
                    () -> "while still leaving a title room to be read" + at);
        }

        assertEquals(rail.right() - PanelLayout.BODY_INSET, PanelLayout.headerRight(rail, false),
                "with no control the header uses the body's own inset");
    }

    @Test
    @DisplayName("a column's footer is the caller's, and the body stops just above whatever band it is")
    void theFooterIsTheCallers() {
        // The author's dock reserves Revert and Save on one tab and nothing on the other, so the band is an
        // argument rather than the card's constant -- and the body's stop is *derived* from it, which is what
        // keeps the two-pixel separation from being lost by a caller that passes another height.
        Rect rail = Rect.at(500, 40, PanelStack.WIDTH, 400);

        PanelLayout.Frame card = PanelLayout.frame(rail, false);
        assertEquals(BookGeometry.MODAL_FOOTER_HEIGHT, card.footer().height(),
                "with no argument the footer is the card's own band");
        assertEquals(PanelLayout.BODY_BOTTOM, rail.bottom() - card.body().bottom(),
                "and the body stops the card's figure above the floor");

        PanelLayout.Frame bare = PanelLayout.frame(rail, false, 0);
        assertEquals(0, bare.footer().height(), "a tab with no controls reserves no band");
        assertEquals(PanelLayout.BODY_INSET, rail.bottom() - bare.body().bottom(),
                "and its body keeps the panels' own bottom air instead of a footer's");
        assertTrue(bare.body().height() > card.body().height(),
                "so a footerless tab shows more list than one with controls, which is the point of the "
                        + "argument");

        PanelLayout.Frame big = PanelLayout.frame(rail, false, 100);
        assertEquals(100, big.footer().height(), "a taller band is what the caller asked for");
        assertEquals(big.footer().y() - PanelLayout.FOOTER_GAP, big.body().bottom(),
                "and the body stops the separation above it rather than flush against it");

        PanelLayout.Frame huge = PanelLayout.frame(rail, false, rail.height() * 2);
        assertEquals(rail.height(), huge.footer().height(), "a footer taller than the column is clamped to it");
        assertTrue(huge.body().height() >= 0, "leaving a body that collapses rather than inverts");
    }

    @Test
    @DisplayName("two columns fit only when a canvas worth looking at is left beside them")
    void theFitIsExact() {
        int left = PanelStack.WIDTH;
        int right = PanelStack.SECOND_WIDTH;
        int need = left + BookGeometry.PANEL_GAP + right + BookGeometry.MIN_CANVAS_WIDTH;

        assertTrue(PanelLayout.fits(need, left, right), "exactly enough room fits");
        assertFalse(PanelLayout.fits(need - 1, left, right), "one pixel less does not");
        assertTrue(PanelLayout.fits(need + 1, left, right));

        // The case a preview found, stated in the terms it was really about: a *reader's* book has a canvas
        // far too narrow for two columns, which is why every second-column kind is an author's.
        //
        // **And the fixture is derived rather than written down, which is the fix this round needed.** It
        // used to name 644 pixels of canvas -- "leaves 38 of graph showing, and that is not a canvas" -- and
        // that number came from an author's 1400-wide window. The minimum it was read against has since
        // moved downwards twice (the band took the author's controls off the canvas, so `MIN_CANVAS_WIDTH`
        // is the view cluster's own band and nothing else), and 644 is now *more* than the minimum: the
        // assertion's premise had gone while its number stayed. A reader's own canvas cannot go stale that
        // way, and it is the case the sentence is about.
        int readerCanvas = new BookGeometry(427, 240).canvas().width();
        assertFalse(PanelLayout.fits(readerCanvas, left, right),
                "a reader's book folds, and that is deliberate: every second-column kind is an author's");
        assertTrue(PanelLayout.fits(1_244, left, right),
                "an author's full-bleed book on a 1400-wide window has room to spare");
    }

    @Test
    @DisplayName("the window decides only when the player has not, and the player's word is honoured")
    void theFoldFollowsThePlayerFirst() {
        int narrow = 300;
        int wide = 1200;
        int left = PanelStack.WIDTH;
        int right = PanelStack.SECOND_WIDTH;

        assertTrue(PanelLayout.folded(narrow, left, right, Fold.AUTO),
                "no room, so the second column is paged into the first");
        assertFalse(PanelLayout.folded(wide, left, right, Fold.AUTO), "room, so both are shown");

        assertTrue(PanelLayout.folded(wide, left, right, Fold.ALWAYS),
                "someone who asked for one column does not get two because the window grew");
        assertFalse(PanelLayout.folded(narrow, left, right, Fold.NEVER),
                "and someone who asked for two has chosen to cover the graph");
        assertTrue(PanelLayout.folded(narrow, left, right, null),
                "a missing preference is the window deciding, not a crash");
    }

    @Test
    @DisplayName("the reveal is exact at both ends, and a theme with no motion is simply there")
    void theRevealIsExactAtBothEnds() {
        assertEquals(0F, PanelLayout.revealed(0, 80), "nothing on the frame it opens");
        assertEquals(1F, PanelLayout.revealed(80, 80), "everything at the end of its duration");
        assertEquals(1F, PanelLayout.revealed(1_000, 80), "and it stays there");
        assertEquals(1F, PanelLayout.revealed(0, 0), "a theme with no motion arrives at once");
        assertEquals(1F, PanelLayout.revealed(0, -5), "and so does a duration that makes no sense");
    }

    @Test
    @DisplayName("the reveal only ever moves forwards, and never leaves zero to one")
    void theRevealIsMonotoneAndBounded() {
        float previous = -1F;
        for (long elapsed = 0; elapsed <= 160; elapsed++) {
            float now = PanelLayout.revealed(elapsed, 80);
            assertTrue(now >= 0F && now <= 1F, "a reveal outside zero to one is a wrongly-scaled wipe");
            assertTrue(now >= previous, "a column that went backwards would flicker");
            previous = now;
        }
        assertTrue(PanelLayout.revealed(40, 80) > 0.5F,
                "eased out rather than linear, so most of the arrival is in its first half");
    }

    @Test
    @DisplayName("the reveal's clip grows from the outer edge and is always inside the column")
    void theRevealClipIsInsideTheColumn() {
        Rect rail = Rect.at(500, 40, 340, 320);

        Rect atStart = PanelLayout.revealRect(rail, 0, 80);
        assertEquals(0, atStart.width(), "at the start nothing is revealed");
        assertEquals(rail.right(), atStart.x(), "...at the column's outer edge");

        Rect atEnd = PanelLayout.revealRect(rail, 80, 80);
        assertEquals(rail, atEnd, "at the end it is the column exactly -- not a pixel short");
        assertEquals(rail, PanelLayout.revealRect(rail, 0, 0), "and no motion means no wipe at all");

        int previousWidth = -1;
        for (long elapsed = 0; elapsed <= 80; elapsed += 4) {
            Rect clip = PanelLayout.revealRect(rail, elapsed, 80);
            assertTrue(clip.isInside(rail), "the clip can never show more than the column");
            assertTrue(clip.width() >= previousWidth, "and it only grows");
            assertEquals(rail.right(), clip.right(), "always anchored to the outer edge");
            previousWidth = clip.width();
        }
    }

    @Test
    @DisplayName("the reveal holds its ends and its bounds for every curve, not just the client's")
    void theRevealHoldsForEveryCurve() {
        for (dev.ellipog.armature.client.ui.kit.Easing easing
                : dev.ellipog.armature.client.ui.kit.Easing.values()) {
            // With room for the curves' own arithmetic: SINE_IN_OUT answers -0.0 at the start, which
            // is zero in every way that matters and in none of the ways `assertEquals` checks.
            assertEquals(0F, PanelLayout.revealed(0, 180, easing), 1.0E-6F,
                    "nothing on the first frame: " + easing);
            assertEquals(1F, PanelLayout.revealed(180, 180, easing), 1.0E-6F,
                    "everything at the end: " + easing);
            assertEquals(1F, PanelLayout.revealed(10_000, 180, easing), "and it stays there: " + easing);
            assertEquals(1F, PanelLayout.revealed(0, 0, easing), "no motion is simply there: " + easing);
            float previous = -1F;
            for (long elapsed = 0; elapsed <= 180; elapsed++) {
                float now = PanelLayout.revealed(elapsed, 180, easing);
                // BACK_OUT overshoots past one mid-flight and comes back: outside the ends it must
                // still arrive exactly, but between them "bounded" is the wrong assertion for it.
                if (easing != dev.ellipog.armature.client.ui.kit.Easing.BACK_OUT) {
                    assertTrue(now >= 0F && now <= 1F, "a reveal outside zero to one: " + easing);
                    assertTrue(now >= previous, "a column that went backwards would flicker: " + easing);
                }
                previous = now;
            }
        }
        // A missing curve is the default rather than a crash: the callers that never name one.
        assertEquals(PanelLayout.revealed(90, 180, dev.ellipog.armature.client.ui.kit.Easing.QUAD_OUT),
                PanelLayout.revealed(90, 180, null), "null names the default curve");
    }

    @Test
    @DisplayName("the slide offset is the rail's width at first, zero at settle, and only ever shrinks")
    void theSlideOffsetTravelsWithTheReveal() {
        for (Rect rail : rails()) {
            assertEquals(rail.width(), PanelLayout.slideOffset(rail, 0, 180),
                    "on the first frame the sheet stands a whole rail off: " + rail);
            assertEquals(0, PanelLayout.slideOffset(rail, 180, 180),
                    "settled means no offset to apply: " + rail);
            assertEquals(0, PanelLayout.slideOffset(rail, 10_000, 180), "and it stays settled: " + rail);
            assertEquals(0, PanelLayout.slideOffset(rail, 0, 0), "no motion is no slide: " + rail);

            int previous = Integer.MAX_VALUE;
            for (long elapsed = 0; elapsed <= 180; elapsed += 6) {
                int offset = PanelLayout.slideOffset(rail, elapsed, 180);
                assertTrue(offset >= 0 && offset <= rail.width(),
                        "the sheet is never past either end: " + rail);
                assertTrue(offset <= previous, "and it only moves left: " + rail);
                assertEquals(rail.x() + offset,
                        PanelLayout.revealRect(rail, elapsed, 180).x(),
                        "the clip's edge and the sheet agree about where the arrival stands: " + rail);
                previous = offset;
            }
        }
    }

    @Test
    @DisplayName("the depart is the enter in reverse: whole rail at close, nothing at settle, only sinking")
    void theDepartIsTheReverseOfTheEnter() {
        for (Rect rail : rails()) {
            assertEquals(0, PanelLayout.departOffset(rail, 0, 150),
                    "on the frame it closes the sheet has nowhere to go yet: " + rail);
            assertEquals(rail.width(), PanelLayout.departOffset(rail, 150, 150),
                    "gone means a whole rail travelled: " + rail);
            assertEquals(rail.width(), PanelLayout.departOffset(rail, 10_000, 150),
                    "and it stays gone: " + rail);
            assertEquals(0, PanelLayout.departOffset(rail, 0, 0), "no motion is no exit: " + rail);

            assertEquals(rail, PanelLayout.departRect(rail, 0, 150),
                    "the clip is the whole rail on the first frame: " + rail);
            assertEquals(0, PanelLayout.departRect(rail, 150, 150).width(),
                    "and nothing once the duration is spent: " + rail);
            assertEquals(rail.right(), PanelLayout.departRect(rail, 75, 150).right(),
                    "always anchored to the outer edge: " + rail);

            int previous = -1;
            for (long elapsed = 0; elapsed <= 150; elapsed += 5) {
                int offset = PanelLayout.departOffset(rail, elapsed, 150);
                assertTrue(offset >= 0 && offset <= rail.width(),
                        "the sheet is never past either end: " + rail);
                assertTrue(offset >= previous, "and it only sinks right: " + rail);
                assertEquals(rail.width() - offset,
                        PanelLayout.departRect(rail, elapsed, 150).width(),
                        "the clip and the sheet agree about what is left: " + rail);
                previous = offset;
            }
        }
    }

    // ------------------------------------------------------------------
    // The grip
    // ------------------------------------------------------------------

    @Test
    @DisplayName("the grip is three ridges inside the handle, centred, at every size it fits in")
    void theGripIsInsideTheHandle() {
        for (Rect rail : rails()) {
            Rect handle = BookGeometry.panelHandle(rail);
            List<Rect> ridges = PanelLayout.grip(rail);
            String where = rail + " handle " + handle;

            if (ridges.isEmpty()) {
                assertTrue(handle.height() < PanelLayout.GRIP_RIDGE_HEIGHT * PanelLayout.GRIP_RIDGES
                                + PanelLayout.GRIP_GAP * (PanelLayout.GRIP_RIDGES - 1) + 4
                                || handle.width() == 0,
                        "ridges are only absent when there is no room for them: " + where);
                continue;
            }
            assertEquals(PanelLayout.GRIP_RIDGES, ridges.size(), "the grip is three ridges: " + where);
            for (Rect ridge : ridges) {
                assertTrue(ridge.isInside(handle), "every ridge is on the handle: " + where);
                assertTrue(ridge.width() <= handle.width(), "and no wider than it: " + where);
            }
            // Spaced by a whole number of pixels, so two ridges can never half-overlap at one column height
            // and not at another -- the reason the block's arithmetic is stated rather than eyeballed.
            for (int i = 1; i < ridges.size(); i++) {
                assertEquals(PanelLayout.GRIP_RIDGE_HEIGHT + PanelLayout.GRIP_GAP,
                        ridges.get(i).y() - ridges.get(i - 1).y(), "the ridges are evenly spaced: " + where);
            }
            // Centred both ways in the strip it names.
            int block = ridges.get(0).height() * ridges.size()
                    + PanelLayout.GRIP_GAP * (ridges.size() - 1);
            assertEquals(handle.y() + (handle.height() - block) / 2, ridges.get(0).y(),
                    "the block is centred in the handle's height: " + where);
            assertEquals(handle.x() + (handle.width() - ridges.get(0).width()) / 2, ridges.get(0).x(),
                    "and across its width: " + where);
        }
    }

    @Test
    @DisplayName("a column too short for the grip gets none, rather than three clipped marks")
    void aShortColumnHasNoGrip() {
        assertEquals(List.of(), PanelLayout.grip(Rect.at(500, 40, 340, 20)),
                "20 pixels cannot hold a 24-pixel block plus its air");
        assertEquals(List.of(), PanelLayout.grip(Rect.at(500, 40, 0, 400)), "and no width is no mark");
        assertEquals(PanelLayout.GRIP_RIDGES, PanelLayout.grip(Rect.at(500, 40, 340, 28)).size(),
                "but 28 -- the block plus two pixels either side -- is enough");
    }

    @Test
    @DisplayName("the visible canvas edge is the innermost column's, or the canvas's own with none")
    void theVisibleEdge() {
        Rect canvas = Rect.at(156, 26, 644, 440);

        assertEquals(canvas.right(), PanelLayout.visibleRight(canvas, List.of()),
                "with no column the canvas shows all of itself");

        Rect first = Rect.at(500, 32, 300, 428);
        assertEquals(first.x(), PanelLayout.visibleRight(canvas, List.of(first)));

        Rect second = Rect.at(240, 32, 254, 428);
        assertEquals(second.x(), PanelLayout.visibleRight(canvas, List.of(second, first)),
                "with two columns it is the inner one that decides what the graph still shows");
    }

    @Test
    @DisplayName("the auto-pan moves a box the least it can, and not at all when it is already clear")
    void theAutoPan() {
        // The zero is the half that matters: a view told to move by a computed nought still counts as
        // moved, and the chapter's own centring would then never take over again.
        assertEquals(0, PanelLayout.panToClear(400, 640, 12), "a box well clear needs no move");
        assertEquals(0, PanelLayout.panToClear(628, 640, 12),
                "and one inside the margin but still visible needs none either");
        assertEquals(0, PanelLayout.panToClear(628, 640, 12) - 0, "the boundary is a positive zero");
        assertEquals(12, PanelLayout.panToClear(640, 640, 12),
                "a box exactly at the edge moves by the margin alone");
        assertEquals(112, PanelLayout.panToClear(740, 640, 12),
                "and one hanging 100 under the column moves by that and the margin");
    }

    // ------------------------------------------------------------------
    // What a point belongs to
    // ------------------------------------------------------------------

    private static PanelLayout.PanelColumn column(PanelKind kind, int x, int width, int revealedWidth) {
        Rect rail = Rect.at(x, 40, width, 300);
        return new PanelLayout.PanelColumn(kind, rail,
                Rect.at(rail.right() - revealedWidth, rail.y(), revealedWidth, rail.height()));
    }

    @Test
    @DisplayName("a press belongs to the column where the column actually is, and to the canvas where it is not")
    void theRevealedPartDecides() {
        // Half arrived: 200 of a 340-wide column, so the strip from 500 to 640 is canvas.
        PanelLayout.PanelColumn half = column(PanelKind.QUEST, 640, 340, 200);
        List<PanelLayout.PanelColumn> columns = List.of(half);

        assertEquals(PanelKind.QUEST, PanelLayout.columnAt(columns, 900, 100),
                "a press on the part that has arrived is the panel's");
        assertEquals(PanelKind.QUEST, PanelLayout.columnAt(columns, 780, 100),
                "and so is the first pixel inside its revealed edge");
        assertNull(PanelLayout.columnAt(columns, 779, 100),
                "while the pixel before it is canvas -- the picture shows graph in the strip to come");
        assertNull(PanelLayout.columnAt(columns, 641, 100), "and so is the rest of that strip");
        assertNull(PanelLayout.columnAt(columns, 500, 100), "and further left is plainly the graph");
    }

    @Test
    @DisplayName("a settled column owns its whole rail, exactly and exclusively at the far edge")
    void aSettledColumnOwnsItsRail() {
        PanelLayout.PanelColumn settled = column(PanelKind.QUEST, 640, 340, 340);
        List<PanelLayout.PanelColumn> columns = List.of(settled);

        assertEquals(PanelKind.QUEST, PanelLayout.columnAt(columns, 640, 100), "its inner edge is its own");
        assertEquals(PanelKind.QUEST, PanelLayout.columnAt(columns, 979, 100), "and its last pixel");
        assertNull(PanelLayout.columnAt(columns, 980, 100),
                "the pixel past it is not -- the same exclusive edge every hit test in this UI uses");
        assertNull(PanelLayout.columnAt(columns, 639, 100));
    }

    @Test
    @DisplayName("with two columns the draw order decides, so one press never answers twice")
    void twoColumnsNeverBothAnswer() {
        List<PanelLayout.PanelColumn> columns = List.of(
                column(PanelKind.PICKER, 400, 240, 240),    // the inner column, drawn first
                column(PanelKind.QUEST, 646, 340, 340));    // the outer one, drawn over it

        assertEquals(PanelKind.PICKER, PanelLayout.columnAt(columns, 500, 100));
        assertEquals(PanelKind.QUEST, PanelLayout.columnAt(columns, 700, 100));
        assertNull(PanelLayout.columnAt(columns, 644, 100), "the gap between them is nobody's");
        assertNull(PanelLayout.columnAt(columns, 300, 100), "and the canvas to the left is the canvas");

        // Given one rectangle twice, the first is the answer -- which is what stops a shared rectangle
        // from being a press that does two things.
        Rect shared = Rect.at(500, 40, 100, 100);
        List<PanelLayout.PanelColumn> same = List.of(
                new PanelLayout.PanelColumn(PanelKind.PICKER, shared, shared),
                new PanelLayout.PanelColumn(PanelKind.QUEST, shared, shared));
        assertEquals(PanelKind.PICKER, PanelLayout.columnAt(same, 550, 50));
    }

    @Test
    @DisplayName("no columns means every point is the book's")
    void nothingPresentedIsNobodysColumn() {
        assertNull(PanelLayout.columnAt(List.of(), 500, 100));
        assertNull(PanelLayout.columnAt(List.of(column(PanelKind.QUEST, 640, 0, 0)), 640, 100),
                "a column of no width answers nothing, however it is asked");
    }
}
