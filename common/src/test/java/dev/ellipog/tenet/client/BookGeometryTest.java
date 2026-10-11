package dev.ellipog.tenet.client;

import dev.ellipog.tenet.client.BookGeometry.Rect;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The quest book's layout, tested without a game.
 *
 * <h2>What this exists to prevent</h2>
 *
 * <p>A screenshot showed two buttons drawn on top of each other in the
 * bottom-right corner. The cause was that "near the bottom right" was written twice, as two
 * expressions that were equal only by coincidence:
 *
 * <pre>
 * Open: canvasRight() - 78,               canvasBottom() + 11
 * Done: panelLeft() + panelWidth() - 68,  panelTop() + panelHeight() - 24
 * </pre>
 *
 * <p>{@code canvasRight() == panelLeft() + panelWidth()}, so the two landed about ten pixels apart in
 * x and fifteen in y — which is to say, on top of each other.
 *
 * <p>The immediate fix was to compute each shared position once. <b>This is the durable fix.</b> The
 * arithmetic now lives in {@link BookGeometry}, which has no Minecraft in it, so the property that was
 * violated can be stated as a test and checked over every window size rather than at one:
 *
 * <pre>
 * no two controls overlap, and every control is inside the surface it belongs to
 * </pre>
 *
 * <p>Reproducing the old bug for the record: at 427x240 GUI pixels — the size the screenshot was
 * taken at — the old Open rectangle was {@code 329,185 70x18} and the old Done was
 * {@code 339,196 60x18}. Those intersect. Every test below that sweeps window sizes would have failed
 * on the old arithmetic.
 *
 * <p>Both of those controls are gone — Open with the summary strip, Done replaced by a close button —
 * so the pair is now reconstructed inside the test that reproduces it rather than existing in the
 * layout. The rectangles are kept verbatim because their <i>overlap</i> is the evidence that the sweep
 * can fail, and that evidence does not expire when the controls do.
 *
 * <h2>Why the sweep rather than a handful of cases</h2>
 *
 * <p>Because the failure mode is size-dependent. The developer's monitor is the one size that
 * certainly works, so a single-case test asserts the least interesting thing. The sweep here starts
 * deliberately smaller than any real window, because a layout that only holds together above
 * 600 pixels wide is a layout with a bug waiting for a laptop.
 */
class BookGeometryTest {

    /** The window the screenshot was taken in, at GUI scale 2. */
    private static final int SCREENSHOT_WIDTH = 427;
    private static final int SCREENSHOT_HEIGHT = 240;

    /**
     * The summary strip's height, as it was.
     *
     * <p>A literal, and deliberately not {@code BookGeometry.STRIP_HEIGHT} -- that constant is deleted
     * with the strip. The two tests that use this reconstruct a layout that no longer exists, so the
     * number has to be the <i>old</i> value rather than a live reference. Following a live constant is
     * how a regression test silently stops reproducing the layout it claims to: it moves with the
     * change and goes on passing.
     */
    private static final int OLD_STRIP_HEIGHT = 46;

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    /**
     * Every control's name and rectangle, for a screen of that size.
     *
     * <h2>Two arguments used to be three, and both removals are the same story</h2>
     *
     * <p>It was {@code controlsAt(width, height, chapters)}, and before that it took a {@code hasOpen}
     * as well. Each argument was a thing the map's contents depended on, and each was removed when the
     * dependency stopped existing rather than when somebody tidied up:
     *
     * <ul>
     *   <li>{@code hasOpen} went with the summary strip. The Open button only existed when a quest was
     *       selected, so the map's contents depended on something that was not a property of the window
     *       at all.</li>
     *   <li>{@code chapters} has now gone too, and its removal is the larger one. The chapter rows were
     *       in this map; they are placed by a {@link dev.ellipog.armature.client.ui.kit.Stack} inside a
     *       scroll view now, so the map holds only the controls whose positions are <b>fixed</b>. Since
     *       nothing in it depends on how many chapters there are, an argument saying how many is not an
     *       input any more — and a test that kept passing it would be describing a layout the screen
     *       does not build.</li>
     * </ul>
     *
     * <p>What is left is a function of the window and nothing else, which is what made it possible to
     * delete the argument rather than keep threading a number through that no caller reads.
     */
    private static Map<String, Rect> controlsAt(int width, int height) {
        return new BookGeometry(width, height).controls();
    }

    /**
     * The two geometries that a window can produce, in production's own two combinations.
     *
     * <p>A reader gets a card with no band ({@code fullBleed} and the band are each {@code mayEdit()}), and
     * an author gets both -- so those are the two shapes to sweep, and the other two combinations are ones
     * nobody can be in. Building all four would double the sweeps below to test two layouts that exist only
     * because the constructor allows them.
     */
    private static List<BookGeometry> geometriesAt(int width, int height) {
        if (width < BookGeometry.MIN_PANEL_WIDTH || height < BookGeometry.MIN_PANEL_HEIGHT) {
            // A window too small to hold the book has no full-bleed geometry worth checking: the panel
            // *becomes* the window, so its own chrome runs off the edges -- and that is a pre-existing
            // property of full bleed rather than anything this round changed. The card's shape is still
            // swept, because the card clamps to the minimum and therefore stays inspectable.
            return List.of(new BookGeometry(width, height));
        }
        return List.of(new BookGeometry(width, height), new BookGeometry(width, height, true, true));
    }

    /** The first overlapping pair, or null if none. Names the two, so a failure is actionable. */
    private static String firstOverlap(Map<String, Rect> controls) {
        List<String> names = new ArrayList<>(controls.keySet());
        for (int i = 0; i < names.size(); i++) {
            for (int j = i + 1; j < names.size(); j++) {
                Rect a = controls.get(names.get(i));
                Rect b = controls.get(names.get(j));
                if (a.intersects(b)) {
                    return names.get(i) + " " + a + " overlaps " + names.get(j) + " " + b;
                }
            }
        }
        return null;
    }

    /** A sweep of sizes, from too small to bigger than any monitor, at deliberately odd steps. */
    private static List<int[]> sizes() {
        List<int[]> out = new ArrayList<>();
        for (int width = 160; width <= 1400; width += 7) {
            for (int height = 100; height <= 1000; height += 11) {
                out.add(new int[] {width, height});
            }
        }
        return out;
    }

    // ------------------------------------------------------------------
    // The property that was violated
    // ------------------------------------------------------------------

    @Nested
    @DisplayName("controls do not overlap")
    class NoOverlap {

        @Test
        @DisplayName("no two controls overlap, at any window size")
        void noTwoControlsOverlapEver() {
            // The chapter count used to be swept here as well — 0 to 6, at every size — because the
            // chapter rows were in this map and a row could collide with a control. They are not in it
            // any more, so the count is no longer an input to this method and sweeping it would be six
            // identical checks per size.
            //
            // That is not a loss of coverage, and the reason is worth stating rather than assuming. Rows
            // cannot collide with each other, because a Stack gives each one its own slot in a column.
            // And the one way a row could collide with the fixed chrome — being drawn up into the header
            // under the close button — is asserted directly below, as a property about *where rows may
            // go*. A property about a region is stronger than a sample of how many things are in it: the
            // sweep over counts only ever tested the six numbers somebody thought to write down.
            for (int[] size : sizes()) {
                Map<String, Rect> controls = controlsAt(size[0], size[1]);
                String overlap = firstOverlap(controls);
                if (overlap != null) {
                    throw new AssertionError("at " + size[0] + "x" + size[1] + ": " + overlap);
                }
            }
        }

        @Test
        @DisplayName("the region the sidebar's rows go in is clear of every fixed control")
        void theSidebarViewportClearsEveryControl() {
            // The replacement for sweeping chapter counts, and it is the property that sweep was
            // actually about: a row drawn under the close button is a row a player can neither read nor
            // click. The rows are placed inside `sidebarViewport()` by a scroll view, so "can a row reach
            // a control" is exactly "does the viewport intersect one".
            //
            // Every control in the map, not just close. The map is what the screen builds from, so a
            // control added to it later is covered here without this test being told — where a test that
            // named `close` would stop covering the case on the day a second header control arrived,
            // while still passing.
            //
            // The view cluster is on the canvas and the sidebar is not, so today only `close` is
            // anywhere near. That is the honest reason this assertion is cheap: it is a one-line
            // invariant that happens to be true for a reason, rather than a sweep that happens to find
            // nothing.
            for (int[] size : sizes()) {
                BookGeometry geometry = new BookGeometry(size[0], size[1]);
                Rect viewport = geometry.sidebarViewport();
                for (Map.Entry<String, Rect> entry : geometry.controls().entrySet()) {
                    assertFalse(viewport.intersects(entry.getValue()),
                            "the sidebar's rows are drawn in " + viewport + ", which overlaps "
                                    + entry.getKey() + " " + entry.getValue()
                                    + " at " + size[0] + "x" + size[1]);
                }
            }
        }

        @Test
        @DisplayName("the two controls that collided are gone, and nothing took their places")
        void theReportedCollisionPairIsGone() {
            // This was `doneAndOpenNeverCollide`, and it named the specific pair that collided in the
            // screenshot: Done in the sidebar's footer, Open in the summary strip. Both are gone --
            // Done replaced by a close button in the header, Open with the strip -- so there is no pair
            // left to assert about, and a test that kept naming them would be asserting about two
            // rectangles that are no longer drawn.
            //
            // What is worth keeping, and what this now asserts, is that nothing has quietly taken
            // either position. The old coordinates were a fault, and a future control landing on them
            // exactly would be a coincidence worth failing on.
            //
            // The half that still proves something is kept, and it is the important half: the old
            // pair really did overlap, which is what makes the sweep in NoOverlap a test capable of
            // failing rather than a loop that happens to pass. That evidence does not expire when the
            // controls it was about do.
            for (int[] size : sizes()) {
                BookGeometry geometry = new BookGeometry(size[0], size[1]);
                Rect panel = geometry.panel();

                // The old arithmetic, verbatim, against the canvas as it was: it stopped short of the
                // summary strip, which was carved out of the panel's bottom.
                Rect oldCanvas = Rect.at(panel.x() + BookGeometry.SIDEBAR_WIDTH,
                        panel.y() + BookGeometry.HEADER_HEIGHT,
                        panel.width() - BookGeometry.SIDEBAR_WIDTH,
                        panel.height() - BookGeometry.HEADER_HEIGHT - OLD_STRIP_HEIGHT);
                Rect oldOpen = Rect.at(oldCanvas.right() - 78, oldCanvas.bottom() + 11, 70, 18);
                Rect oldDone = Rect.at(panel.x() + panel.width() - 68,
                        panel.y() + panel.height() - 24, 60, 18);

                assertTrue(oldOpen.intersects(oldDone),
                        "the old placement should overlap, or this test proves nothing: Open "
                                + oldOpen + " vs Done " + oldDone);

                // And nothing has taken either position. There is no pair left to compare, and
                // `assertNotEquals` against a key that does not exist would throw rather than fail --
                // which is why this walks the map instead of indexing it.
                Map<String, Rect> controls = geometry.controls();
                assertFalse(controls.containsKey("open"),
                        "the strip's Open button is still being offered as a control");
                assertFalse(controls.containsKey("done"),
                        "the old Done button is still being offered as a control");
                for (Map.Entry<String, Rect> entry : controls.entrySet()) {
                    assertFalse(entry.getValue().equals(oldOpen),
                            entry.getKey() + " is drawn exactly where the old Open button was");
                    assertFalse(entry.getValue().equals(oldDone),
                            entry.getKey() + " is drawn exactly where the old Done button was");
                }
            }
        }

        @Test
        @DisplayName("the three view buttons do not overlap each other")
        void theClusterFitsTogether() {
            // Three square buttons in a column, placed from one arithmetic series -- y, y + pitch,
            // y + 2 * pitch. They used to be four controls across two rows of the sidebar's footer,
            // which is the only reason that footer had two rows.
            for (int[] size : sizes()) {
                Map<String, Rect> controls = controlsAt(size[0], size[1]);
                Rect in = controls.get("zoomIn");
                Rect out = controls.get("zoomOut");
                Rect centre = controls.get("centre");

                assertFalse(in.intersects(out), "zoom in/out collide at " + size[0] + "x" + size[1]);
                assertFalse(out.intersects(centre), "zoom out/centre collide at " + size[0] + "x" + size[1]);
                assertFalse(in.intersects(centre), "zoom in/centre collide at " + size[0] + "x" + size[1]);

                assertEquals(in.x(), out.x(), "the cluster is a column, so one x");
                assertEquals(in.x(), centre.x());
                assertEquals(in.width(), in.height(), "the buttons are square");
            }
        }

        @Test
        @DisplayName("the view cluster is inside the canvas it floats over")
        void theClusterIsInsideTheCanvas() {
            // This test used to check two things -- the strip and the cluster -- because both floated
            // over the canvas. The strip is gone, so there is one, and the assertion it keeps is the
            // one that generalises: whatever is drawn *on* the canvas has to be inside it, or it is
            // drawn over the sidebar and clickable outside its own surface.
            //
            // That is not hypothetical for this class. A control at a fixed offset from a container's
            // right edge went 4 pixels outside the canvas at a 160-wide window -- see the Open button's
            // history in BookGeometry -- which is the same fault as the two colliding controls with the
            // sign flipped. Swept rather than checked at one size, because the fault exists only below
            // the width where the fixed offset happens to fit.
            for (int[] size : sizes()) {
                BookGeometry geometry = new BookGeometry(size[0], size[1]);
                Rect canvas = geometry.canvas();
                Rect cluster = geometry.viewControls();

                assertTrue(cluster.isInside(canvas),
                        "the cluster " + cluster + " is outside the canvas " + canvas
                                + " at " + size[0] + "x" + size[1]);
                assertTrue(cluster.width() > 0 && cluster.height() > 0,
                        "the cluster has no area at " + size[0] + "x" + size[1]);
            }
        }

        @Test
        @DisplayName("a screenshot-sized window is fine, and so is a deliberately tiny one")
        void smallWindowsAreFineToo() {
            // The real case, and a case smaller than anything real. A minimum panel that merely
            // *looks* big enough on a developer's monitor is how the original bug survived.
            for (int[] size : new int[][] {{SCREENSHOT_WIDTH, SCREENSHOT_HEIGHT}, {200, 120}, {160, 100}}) {
                String overlap = firstOverlap(controlsAt(size[0], size[1]));
                assertTrue(overlap == null, "at " + size[0] + "x" + size[1] + ": " + overlap);
            }
        }

        @Test
        @DisplayName("the panel's Submit and Back stack rather than collide on a narrow window")
        void overlayControlsNeverCollide() {
            // Swept over the panel rail rather than over a card: the rail is what the two controls are
            // placed in now, and it is the narrower of the two at every size -- so a sweep that used a
            // card would be the easier case.
            for (int width = 160; width <= 1400; width += 7) {
                for (int height = 100; height <= 1000; height += 11) {
                    BookGeometry geometry = new BookGeometry(width, height);
                    BookGeometry.Rect rail = geometry.panelRail(PanelStack.WIDTH);
                    for (boolean hasSubmit : new boolean[] {true, false}) {
                        String overlap = firstOverlap(geometry.questFooter(rail, hasSubmit));
                        assertTrue(overlap == null, "overlay at " + width + "x" + height + ": " + overlap);
                    }
                }
            }
        }
    }

    // (`the wide card` stood here: `wideModal`, `wideModalFramed` and the framed invariant, all three of
    // which described the centred presentation. Every kind is a rail now and its width comes from
    // `PanelStack`, so the only thing left of that group is `MAX_WIDE_MODAL_WIDTH` -- which is a live number
    // rather than a dead rectangle, because `PanelStack.WIDE_WIDTH` is it.)

    // ------------------------------------------------------------------
    // Everything inside the surface it belongs to
    // ------------------------------------------------------------------

    @Nested
    @DisplayName("controls are inside their surface")
    class Containment {

        /**
         * Which surface each control is drawn on.
         *
         * <h2>Named lists rather than a chain with a default, and this is a fix rather than a tidy-up</h2>
         *
         * <p>The check below used to read
         * {@code key.startsWith("chapter") ? sidebar : key.equals("close") ? header : canvas} — and the
         * <b>default</b> was the trap. When the two appearance rows arrived they fell through to the
         * canvas, so this test failed on two controls that were in exactly the right place. The
         * geometry was correct and the test was describing a layout that no longer existed.
         *
         * <p>That is the failure mode of a default in a mapping like this: it silently answers for
         * every case nobody thought about, and its answer is wrong in the direction that looks like a
         * real fault. Three lists invert it — a control in none of them returns null and fails with a
         * message saying so, which is the one thing a default cannot do.
         *
         * <p>Kept by hand rather than derived from {@code BookGeometry}, deliberately: "which surface
         * is this control on" is a design statement, and deriving it from the rectangles would make
         * this test agree with the code by construction — which is the property that makes a layout
         * test worthless. A control that moves surface should fail here.
         */
        private static Rect surfaceFor(BookGeometry geometry, String key) {
            if (key.startsWith("chapter")) {
                return geometry.sidebar();
            }
            if (SIDEBAR_CONTROLS.contains(key)) {
                return geometry.sidebar();
            }
            if (HEADER_CONTROLS.contains(key)) {
                return geometry.header();
            }
            if (AUTHOR_BAND_CONTROLS.contains(key)) {
                return geometry.authorBand();
            }
            if (CANVAS_CONTROLS.contains(key)) {
                return geometry.canvas();
            }
            return null;
        }

        /** The sidebar's fixed chrome: controls in the column that are not chapter rows. */
        private static final Set<String> SIDEBAR_CONTROLS = Set.of("addChapter", "addGroup");

        /**
         * The header's controls: Close, Rewards, the party button, the settings button and the pinned
         * button. The author's band
         * is not here even though it is built by the same method -- a control's surface is where it is
         * drawn rather than where it is constructed, and the band is a surface of its own below this one.
         */
        private static final Set<String> HEADER_CONTROLS =
                Set.of("close", "rewards", "party", "settings", "pinned");

        /**
         * The author's band: five controls in a strip of the panel's own chrome.
         *
         * <p>Their own surface rather than the header's or the canvas's, and the three answers are what this
         * test exists to keep told apart: the band is drawn over neither of the other two. It used to be the
         * canvas -- the pills floated over it -- and the move is the whole reason
         * {@link BookGeometry#MIN_CANVAS_WIDTH} shrank.
         */
        private static final Set<String> AUTHOR_BAND_CONTROLS =
                Set.of("author", "assets", "edit", "advanced", "preview");

        /**
         * The view cluster: the one thing that still sits <i>on</i> the canvas.
         *
         * <p>It was two clusters in the two top corners, which is why {@link BookGeometry#MIN_CANVAS_WIDTH}
         * is an arithmetic term. The band left, so the term is the cluster alone -- see that constant.
         */
        private static final Set<String> CANVAS_CONTROLS =
                Set.of("zoomIn", "zoomOut", "centre");

        @Test
        @DisplayName("every control is inside the surface it belongs to")
        void everyControlIsInsideItsOwnSurface() {
            // Was "everything except Open is inside the sidebar", which was true when the screen had a
            // sidebar footer and one floating control. Neither exists any more, and the honest version
            // says which surface each control belongs to -- so a control that moves surface fails here
            // rather than being quietly exempted from a check that no longer applies to it.
            //
            // Swept over both geometries, because the author's is the one with a band in it and a reader's
            // is the one without: the band's five are in one map and must be absent from the other, and a
            // check that swept one shape would test the other one never.
            for (int[] size : sizes()) {
                for (BookGeometry geometry : geometriesAt(size[0], size[1])) {
                    Map<String, Rect> controls = geometry.controls();

                    for (Map.Entry<String, Rect> entry : controls.entrySet()) {
                        String key = entry.getKey();
                        Rect rect = entry.getValue();
                        Rect surface = surfaceFor(geometry, key);

                        // Asserted before use rather than after, so a control this test does not know
                        // about says *that* rather than reporting an NPE or checking it against the wrong
                        // rectangle. Reachable the moment a control is added to `controls()` and not to
                        // one of the lists above -- which is the moment worth being told about.
                        assertTrue(surface != null,
                                key + " is a control this test has no surface for, at "
                                        + size[0] + "x" + size[1] + ". Add it to the list for the surface"
                                        + " it is drawn on -- one of them has to be right, and guessing"
                                        + " which is how it ended up checked against the canvas.");
                        assertTrue(rect.isInside(surface),
                                key + " " + rect + " is outside its surface " + surface
                                        + " at " + size[0] + "x" + size[1]);
                    }
                }
            }
        }

        @Test
        @DisplayName("the Close button is inside the header")
        void closeIsInsideTheHeader() {
            for (int[] size : sizes()) {
                BookGeometry geometry = new BookGeometry(size[0], size[1]);
                Rect close = geometry.controls().get("close");
                assertTrue(close.isInside(geometry.header()),
                        "Close " + close + " is outside the header " + geometry.header()
                                + " at " + size[0] + "x" + size[1]);
            }
        }

        @Test
        @DisplayName("the header's count stops short of Close, at every size")
        void theHeaderCountClearsClose() {
            // The quest count is right-aligned to this limit. It was `panel width - 12` -- the panel's
            // own inset, a number the Close button knows nothing about -- so it is exactly the mistake
            // that put two controls on top of each other, one surface up.
            for (int[] size : sizes()) {
                BookGeometry geometry = new BookGeometry(size[0], size[1]);
                if (size[0] > 600) {
                    assertTrue(geometry.headerRightLimit() < geometry.closeRect().x(),
                            "the count would run into Close at " + size[0] + "x" + size[1]);
                }
                assertTrue(geometry.headerRightLimit() > geometry.panel().x() + 10,
                        "the header has no room for its own title at " + size[0] + "x" + size[1]);
            }
        }

        @Test
        @DisplayName("the sidebar's viewport stays inside the panel and inside its own column")
        void theSidebarViewportStaysInsideItsSurfaces() {
            // The multi-row version of the reported bug: a row drawn underneath something, where it is
            // both invisible and unclickable. The rows live inside `sidebarViewport()` now, so the thing
            // to assert is that the region itself is inside the surfaces it belongs to — a viewport
            // running past the panel's edge would put a row outside the book, and running into the
            // canvas would put one under the graph.
            //
            // This replaces a loop over `chapterRows()` and `chapterRowY(i)`, which existed because the
            // screen computed each row's y itself. Nothing does now: a Stack places the rows, so there is
            // no row y for this class to be right or wrong about, and the region is the whole of what it
            // still decides.
            for (int[] size : sizes()) {
                BookGeometry geometry = new BookGeometry(size[0], size[1]);
                Rect viewport = geometry.sidebarViewport();

                assertTrue(viewport.isInside(geometry.panel()),
                        "the sidebar's viewport " + viewport + " is outside the panel "
                                + geometry.panel() + " at " + size[0] + "x" + size[1]);
                assertTrue(viewport.x() >= geometry.sidebar().x(),
                        "the viewport starts left of the sidebar at " + size[0] + "x" + size[1]);
                assertTrue(viewport.right() <= geometry.sidebar().right(),
                        "the viewport runs into the canvas at " + size[0] + "x" + size[1]);
            }
        }

        @Test
        @DisplayName("the sidebar's viewport has room for at least one row")
        void atLeastOneRowAlwaysFits() {
            // A sidebar with no room for a row is a book whose only chapter cannot be selected, which is
            // a blank screen with no way forward, and MIN_PANEL_HEIGHT exists to make this true.
            //
            // This is the honest form of what used to be `chapterRows() >= 1`, and it is worth being
            // clear that the new assertion is **weaker** than the old one while being true for a
            // stronger reason. The old promise was that the list was *complete* — every row the geometry
            // counted fitted above the bottom — which required the count to be right. This promises that
            // the region is at least as tall as one row, and that everything past it scrolls. The
            // guarantee is smaller; the arithmetic that can be wrong is gone entirely, because the row
            // count that had to be correct no longer exists.
            //
            // Measured against the row height rather than its pitch: a scroll view scrolls by whole
            // pitches, so a region exactly one pitch tall is what shows a row plus the gap under it, and
            // a region one *row* tall is the smallest in which a row is fully visible at any offset.
            for (int[] size : sizes()) {
                int height = new BookGeometry(size[0], size[1]).sidebarViewport().height();
                assertTrue(height >= BookGeometry.SIDEBAR_ROW_HEIGHT,
                        "the sidebar has " + height + "px of room for rows, less than one "
                                + BookGeometry.SIDEBAR_ROW_HEIGHT + "px row, on a "
                                + size[0] + "x" + size[1] + " screen");
            }
        }

        @Test
        @DisplayName("the sidebar's viewport has width, and the scrollbar's strip still fits beside it")
        void theSidebarViewportHasWidth() {
            // Distinct from the height above, and this is what replaced "at least one chapter control
            // exists". That assertion was about `controls` capping the rows it offered by the rows that
            // fitted, which is a mistake `controls()` can no longer make — it offers fixed chrome and
            // nothing else, so there is no count to get wrong.
            //
            // What can still go wrong is the subtraction that reserves room for the scrollbar: a sidebar
            // narrower than its own insets plus the bar gives a viewport of zero or negative width, and
            // `sidebarViewport` floors that at zero rather than letting it go negative. A zero-width
            // viewport places every row at a negative width, which reads correctly at every use and
            // draws nothing. So the clamp is real and this asserts a real window never relies on it.
            for (int[] size : sizes()) {
                BookGeometry geometry = new BookGeometry(size[0], size[1]);
                assertTrue(geometry.sidebarViewport().width() > 0,
                        "the sidebar's viewport has no width on a " + size[0] + "x" + size[1] + " screen");

                // And the strip the scrollbar is drawn in lands in the sidebar's margin rather than over
                // the end of a row. `ScrollBar` puts the bar four pixels right of the viewport's right
                // edge and three pixels wide — `ScrollView.bar()` binds exactly that strip — so this is
                // the same arithmetic that SIDEBAR_SCROLLBAR is derived from: asserted here rather than
                // left to the comment, because a bar drawn over a row is the fault the reservation
                // exists to prevent and nothing else in the suite would notice it.
                int stripRight = geometry.sidebarViewport().right() + 4 + 3;
                assertTrue(stripRight <= geometry.sidebar().right(),
                        "the scrollbar's strip reaches " + stripRight + ", past the sidebar's right edge "
                                + geometry.sidebar().right() + " at " + size[0] + "x" + size[1]);
            }
        }

        @Test
        @DisplayName("the canvas has room to draw in")
        void theCanvasIsNotDegenerate() {
            for (int[] size : sizes()) {
                BookGeometry geometry = new BookGeometry(size[0], size[1]);
                assertTrue(geometry.canvas().width() >= BookGeometry.MIN_CANVAS_WIDTH,
                        "canvas is " + geometry.canvas().width() + " wide at " + size[0] + "x" + size[1]);
                assertTrue(geometry.canvas().height() > 0,
                        "canvas has no height at " + size[0] + "x" + size[1]);
            }
        }
    }

    // ------------------------------------------------------------------
    // Zoom -- moved out, and why that is worth a note here
    // ------------------------------------------------------------------

    // A `Zooming` nested class used to sit here: four tests of zoom-about-the-pointer, asserting that
    // the world point under the pointer is the same before and after, that zooming in then out comes
    // back, that the limits clamp, and that a zoom already at a limit does not drift.
    //
    // They tested `BookGeometry.zoomAbout`, which was a second implementation of the transform --
    // the screen had its own, as three static fields and the same algebra written out three times.
    // Both are gone. The transform is `ui.kit`'s `Viewport`, which the screen calls, and
    // `ViewportTest.zoomAtKeepsTheContentPointUnderThePointer` asserts the same invariant: swept over
    // more pans and scales than this copy managed, and against the transform itself rather than
    // against a re-derivation of it.
    //
    // So this file now covers one thing rather than two: the framing. Rectangles, the control map,
    // label room, hit-test bounds -- one screen's own layout, and nothing else's. That the split
    // matters at all is the reason to keep this comment: the four tests above were *good tests of the
    // wrong object*, and they were part of why the duplicate went unnoticed. A passing suite is not
    // evidence that the thing it passes against is the thing in use.

    // ------------------------------------------------------------------
    // Label room and hit testing
    // ------------------------------------------------------------------

    @Nested
    @DisplayName("labels and hit testing")
    class LabelsAndHits {

        @Test
        @DisplayName("columns 64 apart give each label 56 pixels, and 56 is less than 64")
        void crowdedColumnsStillLeaveMeasurableRoom() {
            // The bug behind the garbled text: a questline authored 64 pixels apart carrying titles
            // about 90 pixels wide. Room is the narrowest gap less a margin either side, so 64 - 8 = 56.
            //
            // The mind that wrote this test first asserted 0 here, and the comment claimed the screen
            // "draws no labels at all". That was wrong, and the true mechanism is the more interesting
            // one. At 56 the labels are not skipped -- each is *truncated to 56 pixels*, and 56 is
            // strictly less than the 64 the nodes are apart. So two labels cannot touch.
            //
            // What used to happen is the thing to keep in view: whole 90-pixel titles were centred on
            // nodes 64 apart, ran through each other, and read on screen as one corrupted string --
            // "Punch a SomewherStone To...". Three correct titles, drawn where they could not fit.
            assertEquals(56, BookGeometry.labelRoom(List.of(0, 64, 128), 4, 120));
        }

        @Test
        @DisplayName("room is always strictly less than the gap, so two labels can never touch")
        void roomIsAlwaysLessThanTheGap() {
            // The invariant the whole mechanism rests on, and the reason it is asserted over a sweep
            // rather than at one value. If room ever equalled or exceeded the gap, two adjacent labels
            // drawn to their full room would meet or overlap -- reintroducing exactly the reported
            // symptom, with the code looking correct at any single point tested.
            int gap = 4;
            for (int step = 10; step <= 400; step += 3) {
                for (int margin = 1; margin <= 8; margin++) {
                    int room = BookGeometry.labelRoom(List.of(0, step), margin, 120);
                    assertTrue(room < step || room == 120,
                            "gap " + step + " margin " + margin + " gave room " + room
                                    + ", which is not less than the gap");
                }
            }
            // And the same thing said plainly, at the value from the bug report.
            assertTrue(BookGeometry.labelRoom(List.of(0, 64), gap, 120) < 64);
        }

        @Test
        @DisplayName("columns far enough apart give the label the full cap")
        void generousColumnsAllowTheFullCap() {
            assertEquals(120, BookGeometry.labelRoom(List.of(0, 400), 4, 120));
        }

        @Test
        @DisplayName("the narrowest gap decides, and the gap either side is subtracted")
        void theNarrowestGapDecides() {
            // 0, 300, 364: the answer comes from the narrow pair and not the wide one, so a single
            // crowded pair in an otherwise airy row is what limits every label in it.
            assertEquals(56, BookGeometry.labelRoom(List.of(0, 300, 364), 4, 120));
            // With no margin the whole gap is available: 64, not 68. The first version of this test
            // said 68, which is 4 more than the arithmetic can produce and was simply mis-written.
            assertEquals(64, BookGeometry.labelRoom(List.of(0, 300, 364), 0, 120));
        }

        @Test
        @DisplayName("room below the minimum is too small to draw, and only a tiny gap gives zero")
        void roomUnderTheMinimumMeansNoLabels() {
            // Two thresholds, and they are different ones -- conflating them is what made the first
            // version of this test wrong. `labelRoom` returns zero only when a gap is no wider than the
            // two margins together (8 pixels at the usual margin). In between sits a band where the
            // room is positive and still too narrow to be worth drawing, and there the *screen* skips
            // the labels because the room is under MIN_LABEL_WIDTH.
            assertEquals(12, BookGeometry.labelRoom(List.of(0, 20), 4, 120));
            assertTrue(BookGeometry.labelRoom(List.of(0, 20), 4, 120) < BookGeometry.MIN_LABEL_WIDTH,
                    "20px apart should be too narrow to label");

            // Only these are zero: nodes all but stacked.
            assertEquals(0, BookGeometry.labelRoom(List.of(0, 8), 4, 120), "exactly the margins");
            assertEquals(0, BookGeometry.labelRoom(List.of(0, 4), 4, 120), "closer than the margins");
        }

        @Test
        @DisplayName("one column gets the full cap, and an empty list gets the same")
        void singleColumnUsesTheCap() {
            // Fewer than two columns means no neighbour to crowd, so the cap applies. That covers the
            // empty list too: there is nothing to draw, the caller iterates no quests, and the answer
            // is moot -- so it is the same branch rather than a special case with no consequences.
            assertEquals(120, BookGeometry.labelRoom(List.of(500), 4, 120));
            assertEquals(120, BookGeometry.labelRoom(List.of(), 4, 120));
        }

        @Test
        @DisplayName("each node gets its own neighbours' room, not the chapter's narrowest gap")
        void roomIsPerNode() {
            // Columns 0, 300, 340: the crowded pair at 300, 340 used to decide the node at 0 as
            // well, so every label in an otherwise airy chapter truncated to the pair's gap. Now
            // the airy node gets the cap and only the pair shares the narrow room.
            assertEquals(120, BookGeometry.labelRoomFor(List.of(0, 300, 340), 0, 4, 120),
                    "nowhere near the pair, so the cap");
            assertEquals(72, BookGeometry.labelRoomFor(List.of(0, 300, 340), 300, 4, 120),
                    "twice the nearer gap less the margins: 2*40 - 8");
            assertEquals(72, BookGeometry.labelRoomFor(List.of(0, 300, 340), 340, 4, 120),
                    "the pair's other half reads the same gap");
            // And the nearer side rules, not the airy one: 140 sits 40 from 100 and 260 from 400.
            assertEquals(72, BookGeometry.labelRoomFor(List.of(100, 140, 400), 140, 4, 120));
        }

        @Test
        @DisplayName("a node alone, or sharing its column, gets the cap")
        void lonelyNodesGetTheCap() {
            assertEquals(120, BookGeometry.labelRoomFor(List.of(500), 500, 4, 120));
            assertEquals(120, BookGeometry.labelRoomFor(List.of(), 500, 4, 120),
                    "no columns at all is no neighbour to crowd");
            assertEquals(120, BookGeometry.labelRoomFor(List.of(200, 200, 400), 200, 4, 120),
                    "a column-mate is not a neighbour: strict inequalities skip equals");
        }

        @Test
        @DisplayName("a centred label at full room never reaches its nearest neighbour")
        void halfRoomClearsTheNearestGap() {
            // The invariant the per-node rule rests on, swept like the global one above: the room
            // is a centred label's *whole* width, so half of it must clear the nearer gap less the
            // margin. Where the cap binds the half-width is 60, which only happens past a gap of 60.
            for (int distance = 9; distance <= 400; distance++) {
                int room = BookGeometry.labelRoomFor(List.of(0, distance), 0, 4, 120);
                assertTrue(room / 2 < distance,
                        "gap " + distance + " gave room " + room + ", whose half reaches the neighbour");
            }
            assertEquals(8, BookGeometry.labelRoomFor(List.of(0, 8), 0, 4, 120),
                    "a gap of exactly the margins leaves nothing after them");
        }

        @Test
        @DisplayName("hit testing picks the last drawn, which is the one on top")
        void hitTestPicksTheTopmost() {
            // Quests may legitimately share a position -- the index warns about it -- and picking the
            // first would select whichever was authored earlier rather than the one you can see.
            List<Rect> rects = List.of(Rect.at(0, 0, 40, 40), Rect.at(20, 20, 40, 40));

            assertEquals(1, BookGeometry.hitTest(rects, 30, 30), "the later rectangle is on top");
            assertEquals(0, BookGeometry.hitTest(rects, 5, 5), "only the first covers this point");
            assertEquals(-1, BookGeometry.hitTest(rects, 100, 100), "nothing there");
        }

        @Test
        @DisplayName("hit testing is exclusive at the far edge, so adjacent nodes do not both match")
        void hitTestEdgesAreExclusive() {
            Rect rect = Rect.at(10, 10, 20, 20);

            assertTrue(rect.contains(10, 10), "the top-left corner is inside");
            assertTrue(rect.contains(29, 29), "one pixel inside the far edge is inside");
            assertFalse(rect.contains(30, 30), "the far edge itself is outside");
            assertFalse(rect.contains(9, 15), "one pixel before the left edge is outside");
        }

        // ------------------------------------------------------------------
        // The editor's grid
        // ------------------------------------------------------------------

        @Test
        @DisplayName("snapping rounds to the grid, and a coordinate already on it stays put")
        void snapRoundsToTheGrid() {
            int grid = BookGeometry.SNAP_GRID;

            assertEquals(0, BookGeometry.snap(3, grid, true), "three units under the midpoint rounds down");
            assertEquals(8, BookGeometry.snap(4, grid, true), "the midpoint rounds up, like Math.round");
            assertEquals(8, BookGeometry.snap(7.9, grid, true), "one tick short still lands on the grid");
            assertEquals(8, BookGeometry.snap(8, grid, true), "already on the grid, so it stays");
            assertEquals(-8, BookGeometry.snap(-5, grid, true), "and the same below zero, off the canvas");
            assertEquals(24, BookGeometry.snap(20, grid, true), "three steps up from the origin");
        }

        @Test
        @DisplayName("snapping off, or a grid that cannot divide, answers the coordinate it was given")
        void snapOffIsTheValueItself() {
            assertEquals(37.4182, BookGeometry.snap(37.4182, 8, false),
                    "the switch off is free placement, exactly as the pointer left it");
            assertEquals(37.4182, BookGeometry.snap(37.4182, 0, true), "a zero grid cannot divide");
            assertEquals(37.4182, BookGeometry.snap(37.4182, -8, true), "and neither can a negative one");
        }

        @Test
        @DisplayName("re-snapping an already-snapped coordinate is the same coordinate")
        void snapIsIdempotent() {
            // A held drag re-derives the follow position on every mouse move, so a snap that was not
            // idempotent would let a resting node drift under a still pointer.
            for (double x = -40; x <= 40; x += 0.5) {
                double once = BookGeometry.snap(x, BookGeometry.SNAP_GRID, true);
                assertEquals(once, BookGeometry.snap(once, BookGeometry.SNAP_GRID, true),
                        "snapping twice moved the node, at " + x);
            }
        }

        @Test
        @DisplayName("two rectangles sharing only an edge do not count as overlapping")
        void touchingIsNotOverlapping() {
            // What makes the overlap assertions usable: two controls drawn edge to edge are touching,
            // not colliding, and a test that called that a collision would be wrong and would have to
            // be weakened until it caught nothing.
            Rect left = Rect.at(0, 0, 10, 10);
            Rect right = Rect.at(10, 0, 10, 10);

            assertFalse(left.intersects(right));
            assertTrue(left.intersects(Rect.at(9, 0, 10, 10)), "one pixel of overlap is overlap");
        }
    }

    // ------------------------------------------------------------------
    // The reported bug, reproduced from the old arithmetic
    // ------------------------------------------------------------------

    @Test
    @DisplayName("the old placement is rejected, so this test would have caught the bug")
    void theOldPlacementWouldFail() {
        // The old expressions, kept as arithmetic so that this test proves the sweep above is capable
        // of failing. Without it, a test that has never seen a failure is a test nobody should trust:
        // an assertion with a wrong comparison, or a loop that runs zero times, passes silently.
        //
        // These are verbatim from the first version of QuestBookScreen:
        //     Open: canvasRight() - 78,               canvasBottom() + 11
        //     Done: panelLeft() + panelWidth() - 68,  panelTop() + panelHeight() - 24
        //
        // <h2>And it stopped proving anything, which is worth the paragraph</h2>
        //
        // <p>{@code canvasBottom()} is not the same number it was. The canvas used to stop short of the
        // strip — the strip was carved out of the panel's bottom, 46 pixels tall — and it now runs the
        // full height with the bar floating over it. So the old Open expression landed 46 pixels lower
        // than it did on the screen being described, the two rectangles stopped overlapping, and this
        // assertion failed with the message it prints when it has no evidence.
        //
        // <p>The fix is to reconstruct the <b>old</b> canvas rather than to reuse the new one, because
        // the case under test is a layout that no longer exists. The alternative — deleting the test
        // because it "no longer applies" — would have thrown away the only proof that this sweep can
        // fail. A regression test pinned to a moving number is a test that quietly retires itself, and
        // the failure message is the only reason it did not.
        //
        // **The fixture is a window whose panel is clamped to the minimum now.** It used to be the
        // photographed window -- 427 wide, whose panel was the window less its two margins, 387 -- because
        // the minimum was smaller than that. The author's pills raised `MIN_CANVAS_WIDTH` and with it
        // `MIN_PANEL_WIDTH` to 407, so the photographed window became one the minimum sized; and the band
        // took the pills out of the canvas again, dropping the minimum to 297 and the photographed window
        // back above it. So the fixture asks for a window that is smaller than the panel it gets, which is
        // the property it wants: `assert`ing it rather than working around it, because the sentence says
        // which of the two it expects and a change to either term therefore reports itself here.
        //
        // What the reconstruction proves does not depend on it, and that is why the fixture survives: both
        // old expressions are anchored to the panel's own edges -- `oldOpen` to its right edge, `oldDone` to
        // the same edge less 68 -- so their overlap is a property of the pair rather than of the size.
        int width = BookGeometry.MIN_PANEL_WIDTH + BookGeometry.PANEL_MARGIN * 2 - 17;
        int height = SCREENSHOT_HEIGHT;
        BookGeometry geometry = new BookGeometry(width, height);
        Rect panel = geometry.panel();

        Rect oldCanvas = Rect.at(panel.x() + BookGeometry.SIDEBAR_WIDTH, panel.y() + BookGeometry.HEADER_HEIGHT,
                panel.width() - BookGeometry.SIDEBAR_WIDTH,
                panel.height() - BookGeometry.HEADER_HEIGHT - OLD_STRIP_HEIGHT);

        Rect oldOpen = Rect.at(oldCanvas.right() - 78, oldCanvas.bottom() + 11, 70, 18);
        Rect oldDone = Rect.at(panel.x() + panel.width() - 68, panel.y() + panel.height() - 24, 60, 18);

        assertTrue(oldOpen.intersects(oldDone),
                "the old placement should overlap, or this regression test proves nothing: Open "
                        + oldOpen + " vs Done " + oldDone);
        assertEquals(BookGeometry.MIN_PANEL_WIDTH, panel.width(),
                "fixture sanity: the band's minimum is what sizes the panel at this window now");

        // And nothing is drawn where either of them was, which is the only form this assertion can
        // take now: both controls are gone (Open with the strip, Done replaced by Close), so there is
        // no pair left to compare. `assertNotEquals` against a key that does not exist would throw
        // rather than fail, which is why this reads the rectangles positionally.
        //
        // What it still proves is the thing worth keeping: the reconstructed collision above is real,
        // so the sweep in NoOverlap is a test that can fail. That was the whole reason this test
        // existed, and it survives the controls it was about.
        //
        // The chapter count is gone from this call, and that this block was one of its call sites is
        // the argument for removing it rather than leaving it as an ignored parameter. There were two
        // byte-identical blocks reading `geometry.controls(4)` in this file — this one and the one in
        // `NoOverlap` — so a fix applied to the first match of that text fixed exactly one of them, and
        // the compiler caught the other. A parameter no caller reads is a parameter that can be left
        // behind at one site out of six, and in every other case the mistake is silent, because a
        // redundant argument still compiles. Removing it turned a would-be divergence into an error.
        Map<String, Rect> controls = geometry.controls();
        assertFalse(controls.containsKey("open"),
                "the strip's Open button is still being offered as a control");
        assertFalse(controls.containsKey("done"),
                "the old Done button is still being offered as a control");
        for (Map.Entry<String, Rect> entry : controls.entrySet()) {
            assertFalse(entry.getValue().equals(oldOpen),
                    entry.getKey() + " is drawn exactly where the old Open button was");
            assertFalse(entry.getValue().equals(oldDone),
                    entry.getKey() + " is drawn exactly where the old Done button was");
        }
    }

    @Test
    @DisplayName("a control named in the map is the one the geometry helper points at")
    void theMapAndTheHelpersAgree() {
        // The screen draws to two numbers that nothing else creates: the header's right limit, which
        // the quest count is aligned to, and the cluster's rectangle, which the mat is painted from.
        // A drift in either would be invisible until the two met -- which is the class of bug this
        // whole file exists for.
        //
        // Open and its bar used to be checked here too and are gone with the strip. Nothing replaces
        // them, and that is honest rather than a gap: there is no longer a control whose position is
        // computed in two places.
        BookGeometry geometry = new BookGeometry(SCREENSHOT_WIDTH, SCREENSHOT_HEIGHT);

        assertTrue(geometry.headerRightLimit() <= geometry.closeRect().x(),
                "the count's limit must not run past the Close button");
        assertTrue(geometry.viewControls().isInside(geometry.canvas()),
                "the cluster's mat is painted outside the canvas it sits on");
        assertTrue(geometry.viewControls().width() > BookGeometry.VIEW_BUTTON,
                "the mat is the whole reason the cluster reads as one group, so it must be bigger "
                        + "than a single button: " + geometry.viewControls());

        // The author's band, whose surface the screen paints and whose buttons it draws by hand over the
        // widget pass's clip: it has to be inside the panel, above the body on both sides of it, and taller
        // than one row of controls.
        BookGeometry author = new BookGeometry(SCREENSHOT_WIDTH, SCREENSHOT_HEIGHT, true, true);
        assertTrue(author.authorBand().isInside(author.panel()),
                "the band is painted outside the panel it is a strip of");
        assertTrue(author.authorBand().height() > BookGeometry.ROW_HEIGHT,
                "the band exists to hold a row of controls: " + author.authorBand());
        assertTrue(author.authorBand().bottom() <= author.canvas().y(),
                "the canvas starts under the band, not through it");
        assertTrue(author.authorBand().bottom() <= author.sidebar().y(),
                "and so does the sidebar");
        assertTrue(author.sidebar().y() > author.header().bottom(),
                "the band reserves its height between the header and the body: "
                        + author.header() + " then " + author.sidebar());
        // The drawer's rail is a column's rail now: anchored to the canvas's right edge, one gap in, and
        // starting at the canvas's own top edge -- which the band pushed down. **The two are not asserted
        // apart**, deliberately: a column may cover the canvas, and on a window too narrow for a 300-pixel
        // dock it overlaps, which is what a narrow window means. That the rail is inside the canvas, inset
        // by the gap, is asserted for every width in the docked-column sweep above, where the arithmetic
        // lives.
        for (int[] size : sizes()) {
            BookGeometry window = new BookGeometry(size[0], size[1]);
            Rect rail = window.panelRail(PanelStack.WIDTH);
            String at = " at " + size[0] + "x" + size[1];

            assertEquals(window.canvas().y() + BookGeometry.PANEL_GAP, rail.y(),
                    () -> "the drawer's rail does not start at the canvas's own top edge" + at);
            assertEquals(window.canvas().right() - BookGeometry.PANEL_GAP, rail.right(),
                    () -> "and it is not anchored to the canvas's right edge" + at);
        }
    }

    @Test
    @DisplayName("the author's band is five buttons one seam apart, and a reader's geometry has none")
    void theAuthorBandIsOneRow() {
        // The seam is the one part of this row a person can measure, and it is the part a playtest reported
        // as a pixel too wide when it was a pair of pills. It is asserted rather than described because each
        // button is placed *from* the constant: a change to either the constant or the placement that
        // stopped the two agreeing would otherwise draw a gap nobody could point at a number for.
        BookGeometry geometry = new BookGeometry(SCREENSHOT_WIDTH, SCREENSHOT_HEIGHT, true, true);
        Map<String, Rect> band = geometry.authorBandButtons();
        Rect author = band.get("author");
        Rect assets = band.get("assets");
        Rect edit = band.get("edit");
        Rect advanced = band.get("advanced");
        Rect preview = band.get("preview");

        assertEquals(BookGeometry.BAND_BUTTON_GAP, assets.x() - author.right(),
                "the seam between Author and Assets is not the constant: " + author + " and " + assets);
        assertEquals(BookGeometry.BAND_BUTTON_GAP, edit.x() - assets.right(),
                "nor between Assets and Edit: " + assets + " and " + edit);
        assertEquals(BookGeometry.BAND_BUTTON_GAP, advanced.x() - edit.right(),
                "nor between Edit and Advanced: " + edit + " and " + advanced);
        assertEquals(BookGeometry.BAND_BUTTON_GAP, preview.x() - advanced.right(),
                "nor between Advanced and Preview: " + advanced + " and " + preview);
        assertEquals(author.y(), preview.y(), "the five are one row: " + author + " and " + preview);
        assertEquals(author.height(), preview.height(), "a row of different heights is not one row");

        // And the buttons really are inside the strip drawn behind them, which is the whole reason the band
        // is a rectangle of the geometry rather than five offsets: a strip that stopped short of the last
        // control would make the band a row floating over the sidebar.
        for (Rect button : band.values()) {
            assertTrue(button.isInside(geometry.authorBand()),
                    "a band button is outside the strip drawn behind it: " + button + " vs "
                            + geometry.authorBand());
        }

        // A reader has no band, no strip and no buttons -- the one place this map's contents depend on
        // something other than the window. Asserted in both directions, because "absent for a reader" and
        // "missing by mistake" are the same map entry away from each other.
        BookGeometry reader = new BookGeometry(SCREENSHOT_WIDTH, SCREENSHOT_HEIGHT);
        assertFalse(reader.hasAuthorBand());
        assertTrue(reader.authorBandButtons().isEmpty(), "a reader's geometry invented five controls");
        assertEquals(0, reader.authorBand().height(), "and a strip to draw them on");
        for (String key : List.of("author", "assets", "edit", "advanced", "preview")) {
            assertFalse(reader.controls().containsKey(key), key + " is in a reader's control map");
            assertTrue(geometry.controls().containsKey(key), key + " is missing from an author's");
        }
        assertEquals(reader.canvas().y(), reader.header().bottom(),
                "a reader's canvas starts at the header, with no band in between");

        // And where the row sits: the panel's own edge, left to right, which is what makes it a strip of
        // the chrome rather than a cluster floating over the canvas. Swept over the sizes a window can be,
        // because the panel can be narrower than the row and the buttons have to give way rather than run
        // off it -- the author's panel *is* the window when the book is full-bleed.
        for (int[] size : sizes()) {
            BookGeometry window = new BookGeometry(size[0], size[1], true, true);
            Rect strip = window.authorBand();
            String at = " at " + size[0] + "x" + size[1];
            Rect first = window.authorBandButtons().get("author");

            assertEquals(strip.x() + BookGeometry.EDGE, first.x(),
                    () -> "the band no longer starts at the panel's own inset" + at);
            assertEquals(strip.y() + BookGeometry.BAND_PAD, first.y(),
                    () -> "and it is not inset inside the strip it is drawn on" + at);
            for (Rect button : window.authorBandButtons().values()) {
                assertTrue(button.isInside(strip),
                        () -> "a band button ran off the band" + at + ": " + button + " vs " + strip);
            }
            // The last button's right edge, which is what a shared width is for: at the panel's minimum
            // nothing narrows, and below it the five share what there is. Either way the row ends inside the
            // panel, which is the property the clamp exists for.
            Rect last = window.authorBandButtons().get("preview");
            assertTrue(last.right() <= window.panel().right(),
                    () -> "the band's row ran off the panel" + at + ": " + last);
        }
    }

    @Test
    @DisplayName("the editor's bar fills the footer's free width and stops short of Back")
    void theEditorBarFits() {
        // The bar the quest editor builds its four controls in -- Delete, Duplicate, Copy and Settings.
        // It was placed from the reader's Submit slot, which `questFooter(rail, false)` deliberately does
        // not have, so the bar was skipped whole and the editor lost the button that opens a node's
        // shape, size, placement and rules. This is the test that would have said so.
        for (int[] size : sizes()) {
            BookGeometry geometry = new BookGeometry(size[0], size[1]);
            BookGeometry.Rect rail = geometry.panelRail(PanelStack.WIDTH);
            BookGeometry.Rect bar = geometry.editorBar(rail);
            BookGeometry.Rect back = geometry.questFooter(rail, false).get("back");
            String at = " at " + size[0] + "x" + size[1];

            assertNotNull(back, "the editor's footer has no Back to place its bar against" + at);
            assertNotNull(bar, "no bar" + at);
            assertTrue(bar.isInside(rail), "the bar left the panel" + at + ": " + bar);
            assertEquals(back.y(), bar.y(), "the bar is not in Back's row" + at);
            assertEquals(back.height(), bar.height(), "and not Back's height" + at);
            assertTrue(bar.right() <= back.x(), "the bar runs into Back" + at + ": " + bar);
            assertEquals(rail.x() + BookGeometry.MODAL_INSET, bar.x(),
                    "the bar does not start at the panel's own inset" + at);
            assertTrue(bar.width() > 0, "a bar with no width is four controls nobody can press" + at);
        }
    }

    @Test
    @DisplayName("full bleed: the panel is the whole window, and everything follows it")
    void fullBleed() {
        for (int[] size : new int[][] {{854, 480}, {427, 240}}) {
            // The author's geometry, which is the full-bleed one in production and the only shape with a
            // band in it: the band is what this test gained, and the five buttons are the parts most likely
            // to leave the window, because a full-bleed panel is the window at its own minimum and no more.
            BookGeometry window = new BookGeometry(size[0], size[1], true, true);
            String at = " at " + size[0] + "x" + size[1];

            assertEquals(BookGeometry.Rect.at(0, 0, size[0], size[1]), window.panel(),
                    () -> "the full-bleed panel is not the window" + at);

            List<BookGeometry.Rect> parts = new java.util.ArrayList<>(List.of(window.header(),
                    window.sidebar(), window.canvas(), window.authorBand(),
                    window.controls().get("close")));
            parts.addAll(window.authorBandButtons().values());
            for (BookGeometry.Rect part : parts) {
                assertTrue(part.x() >= 0 && part.y() >= 0 && part.right() <= size[0]
                                && part.bottom() <= size[1],
                        () -> "a part left the window" + at + ": " + part);
            }

            // And the card shape is unchanged: the flag moves the panel, not the parts.
            BookGeometry card = new BookGeometry(size[0], size[1]);
            assertTrue(card.panel().width() < size[0] || card.panel().height() < size[1],
                    () -> "the card should keep its margin" + at);
        }
    }

    @Test
    @DisplayName("the same size always gives the same rectangles")
    void geometryIsDeterministic() {
        // The screen rebuilds its geometry whenever the size changes and asks for the control map on
        // every init. A map built in iteration order that varied would move controls between rebuilds,
        // which shows up as a button that occasionally cannot be clicked.
        //
        // This method had no `@Test` for a while -- it was written and never ran, which is why its key
        // list could go stale without anything failing. The annotation is the fix, and the list below is
        // the corrected one.
        Map<String, Rect> first = new LinkedHashMap<>(controlsAt(SCREENSHOT_WIDTH, SCREENSHOT_HEIGHT));
        Map<String, Rect> second = new LinkedHashMap<>(controlsAt(SCREENSHOT_WIDTH, SCREENSHOT_HEIGHT));

        assertEquals(first, second);
        // No "open" any more: the strip's button went with the strip, and it was the only entry here
        // whose presence depended on something other than the window.
        //
        // And no chapter rows any more either, which is the change worth recording because it is the one
        // that altered what this list *is*. It used to be "the fixed chrome, plus one entry per chapter
        // that fits" -- a mixed bag whose length was a sum of two unrelated things. It is now only the
        // fixed chrome, so this assertion is a statement about the screen's controls rather than about a
        // particular window's worth of them.
        //
        // The order is asserted, not just the contents, and that is the half that catches a rebuild
        // reordering them: this method's callers index nothing, but a screen that later walks this map
        // to place controls would draw them in a different order between two inits of the same size --
        // which shows up as a control that is occasionally somewhere else.
        //
        // The source order in `controls()` is: close, the rewards button, the party button, the settings
        // button, the pinned button, the author's band, the sidebar's two add buttons, then the view
        // cluster. The chapter rows
        // were ahead of close and are gone; the two appearance rows were between close and the cluster and
        // are gone -- see the note in that method for why each went.
        //
        // **This list is a reader's, and the band's five are deliberately absent from it.** That is the one
        // place the map's contents depend on something other than the window, and the band's own test asserts
        // the other direction; a list here that named five keys the geometry under test does not have would
        // be asserting a lie.
        assertEquals(
                List.of("close", "rewards", "party", "settings", "pinned",
                        "addChapter", "addGroup", "zoomIn", "zoomOut", "centre"),
                List.copyOf(first.keySet()));

        // And an author's map, at the same size: the reader's list plus the band, in the band's own reading
        // order, in the slot it holds between the header's controls and the sidebar's two.
        BookGeometry author = new BookGeometry(SCREENSHOT_WIDTH, SCREENSHOT_HEIGHT, true, true);
        assertEquals(
                List.of("close", "rewards", "party", "settings", "pinned", "author", "assets", "edit",
                        "advanced", "preview",
                        "addChapter", "addGroup", "zoomIn", "zoomOut", "centre"),
                List.copyOf(author.controls().keySet()));
        assertEquals(author.controls(), new BookGeometry(SCREENSHOT_WIDTH, SCREENSHOT_HEIGHT, true, true)
                        .controls(),
                "and two builds of one shape agree, band and all");
    }

    // ------------------------------------------------------------------
    // The docked column
    // ------------------------------------------------------------------

    /** Widths to sweep a column at: degenerate, the three the kinds ask for, and absurd. */
    private static int[] columnWidths() {
        return new int[] {0, 1, -50, 100, PanelStack.MIN_WIDTH, PanelStack.SECOND_WIDTH,
                PanelStack.WIDTH, PanelStack.MAX_WIDTH, PanelStack.WIDE_MIN_WIDTH, PanelStack.WIDE_WIDTH,
                5_000};
    }

    @Nested
    @DisplayName("the docked column")
    class Docked {

        @Test
        @DisplayName("it is inside the canvas, inset by the gap, at every window size and width")
        void itIsInsideTheCanvas() {
            for (int[] size : sizes()) {
                BookGeometry window = new BookGeometry(size[0], size[1]);
                Rect canvas = window.canvas();
                for (int width : columnWidths()) {
                    Rect rail = window.panelRail(width);
                    String at = " at " + size[0] + "x" + size[1] + ", width " + width;

                    assertTrue(rail.width() >= 0 && rail.height() >= 0,
                            () -> "a column of negative size is a crash waiting for a caller" + at);
                    assertTrue(rail.x() >= canvas.x(), () -> "the column reached the chapter list" + at);
                    assertTrue(rail.right() <= canvas.right(),
                            () -> "the column ran off the canvas's outer edge" + at);
                    assertEquals(canvas.right() - BookGeometry.PANEL_GAP, rail.right(),
                            () -> "the column is anchored to the canvas's outer edge, one gap in" + at);

                    if (canvas.width() >= BookGeometry.PANEL_GAP * 2
                            && canvas.height() >= BookGeometry.PANEL_GAP * 2) {
                        assertTrue(rail.isInside(canvas), () -> "the column left the canvas" + at);
                        assertEquals(canvas.height() - BookGeometry.PANEL_GAP * 2, rail.height(),
                                () -> "and keeps the same gap above and below" + at);
                    }
                }
            }
        }

        @Test
        @DisplayName("a column wider than the canvas is clamped, not allowed to run off it")
        void theWidthIsClampedToTheCanvas() {
            for (int[] size : sizes()) {
                BookGeometry window = new BookGeometry(size[0], size[1]);
                Rect canvas = window.canvas();
                Rect absurd = window.panelRail(5_000);

                assertTrue(absurd.width() <= Math.max(0, canvas.width() - BookGeometry.PANEL_GAP * 2),
                        "the widest a column gets leaves a gap on both sides of the canvas");
                assertEquals(window.panelRail(canvas.width() - BookGeometry.PANEL_GAP * 2), absurd,
                        "and asking for more than that is the same as asking for the cap");
            }
        }

        @Test
        @DisplayName("a window too small for a kind's floor draws the column at the canvas, and that is the rule")
        void theCanvasWinsOverTheKindsFloor() {
            // Two floors meet here and only one of them can win. `PanelStack.minimumWidth` is what the *drag*
            // and a kind's own request are held to; the canvas is what the *drawing* is held to, because a
            // column is anchored inside the canvas and may never reach the chapter list -- hiding the
            // navigation to show a panel is the trade nobody asked for. On a window too small for the floor
            // the canvas therefore wins, and the column is drawn narrower than its kind asked for. Written
            // down because the alternative was a rule that lived in nobody's comment: the floor and the clamp
            // are in two classes, and this is the one place that says which of them has the last word.
            for (int[] size : new int[][] {{400, 300}, {320, 200}}) {
                BookGeometry window = new BookGeometry(size[0], size[1]);
                Rect rail = window.panelRail(PanelStack.WIDE_WIDTH);
                String at = " at " + size[0] + "x" + size[1];

                assertTrue(rail.width() < PanelStack.WIDE_MIN_WIDTH,
                        () -> "a wide kind cannot keep its floor on a window this small" + at);
                assertEquals(Math.max(0, window.canvas().width() - BookGeometry.PANEL_GAP * 2),
                        rail.width(), () -> "the column is the canvas it was given and nothing else" + at);
                assertTrue(rail.x() >= window.canvas().x(),
                        () -> "and it still has not touched the chapter list" + at);
            }
        }

        @Test
        @DisplayName("a width of zero or less is a column of no width, never of negative width")
        void aWidthOfNothingIsNothing() {
            BookGeometry window = new BookGeometry(SCREENSHOT_WIDTH, SCREENSHOT_HEIGHT);
            for (int width : new int[] {0, -1, Integer.MIN_VALUE}) {
                assertEquals(0, window.panelRail(width).width(), "width " + width + " is no column at all");
            }
        }

        @Test
        @DisplayName("the second column sits one gap inside the first, and the two never overlap")
        void theSecondColumnSitsInsideTheFirst() {
            for (int[] size : sizes()) {
                BookGeometry window = new BookGeometry(size[0], size[1]);
                Rect canvas = window.canvas();
                for (int firstWidth : new int[] {PanelStack.WIDTH, PanelStack.WIDE_WIDTH}) {
                    Rect first = window.panelRail(firstWidth);
                    for (int secondWidth : columnWidths()) {
                        Rect second = window.panelRail2(secondWidth, firstWidth);
                        String at = " at " + size[0] + "x" + size[1] + ", " + firstWidth + "+"
                                + secondWidth;

                        assertFalse(first.intersects(second),
                                () -> "the two columns overlap" + at);
                        assertTrue(second.width() >= 0, () -> "a negative second column" + at);
                        assertEquals(first.x() - BookGeometry.PANEL_GAP, second.right(),
                                () -> "the second column is one gap inside the first" + at);
                        assertTrue(second.x() >= canvas.x(),
                                () -> "the pair reached the chapter list" + at);
                        assertTrue(second.isInside(canvas), () -> "it left the canvas" + at);
                    }
                }
            }
        }

        @Test
        @DisplayName("with no room left, the second column is of no width rather than of negative width")
        void noRoomMeansNoSecondColumn() {
            // A narrow canvas: 240 of first column leaves nothing for a second one.
            BookGeometry window = new BookGeometry(160, 300);
            Rect first = window.panelRail(PanelStack.WIDE_WIDTH);
            Rect second = window.panelRail2(PanelStack.SECOND_WIDTH, PanelStack.WIDE_WIDTH);

            assertEquals(0, second.width(), "there is nowhere for it to go, so it is nothing");
            assertEquals(first.x() - BookGeometry.PANEL_GAP, second.x(),
                    "and it is placed where it would have been, rather than at the canvas's left edge");
        }

        @Test
        @DisplayName("the handle is the column's inner edge, and is inside the column at every size")
        void theHandleIsInsideTheColumn() {
            for (int[] size : sizes()) {
                BookGeometry window = new BookGeometry(size[0], size[1]);
                for (int width : columnWidths()) {
                    Rect rail = window.panelRail(width);
                    Rect handle = BookGeometry.panelHandle(rail);
                    String at = " at " + size[0] + "x" + size[1] + ", width " + width;

                    assertTrue(handle.isInside(rail) || rail.width() == 0,
                            () -> "the grab strip left the column" + at);
                    assertEquals(rail.x(), handle.x(), () -> "it is on the inner edge" + at);
                    assertEquals(Math.min(BookGeometry.PANEL_HANDLE, rail.width()), handle.width(),
                            () -> "and is the handle's width, or the whole column when it is narrower"
                                    + at);
                    assertEquals(rail.height(), handle.height(),
                            () -> "the whole height of the column is grabbable" + at);
                }
            }
        }

        @Test
        @DisplayName("a wider column is never further right, and the anchor does not move")
        void theAnchorDoesNotMove() {
            BookGeometry window = new BookGeometry(900, 500);
            Rect narrow = window.panelRail(PanelStack.MIN_WIDTH);
            Rect wide = window.panelRail(PanelStack.WIDE_WIDTH);

            assertEquals(narrow.right(), wide.right(), "both are anchored to the same edge");
            assertTrue(wide.x() <= narrow.x(), "so a wider column grows towards the canvas");
        }
    }

    // ------------------------------------------------------------------
    // The chapter list as a panel
    // ------------------------------------------------------------------

    @Nested
    @DisplayName("the chapter list as a pinnable panel")
    class SidebarPanel {

        /** The states to sweep: docked, floating, slivered, at several widths. */
        private static List<SidebarState> states() {
            List<SidebarState> out = new ArrayList<>();
            for (int width : new int[] {50, 156, 500}) {
                for (boolean floating : new boolean[] {false, true}) {
                    for (boolean pinned : new boolean[] {true, false}) {
                        for (boolean collapsed : new boolean[] {false, true}) {
                            out.add(new SidebarState(width, floating, pinned, collapsed));
                        }
                    }
                }
            }
            return out;
        }

        /** A coarser sweep than `sizes()`: the state space multiplies it by twenty-four. */
        private static List<int[]> stateSizes() {
            List<int[]> out = new ArrayList<>();
            for (int width = 160; width <= 1400; width += 41) {
                for (int height = 100; height <= 1000; height += 67) {
                    out.add(new int[] {width, height});
                }
            }
            return out;
        }

        private static List<BookGeometry> geometriesAt(int width, int height, SidebarState state) {
            // Production's own combinations, like the outer sweep: a window too small to hold the
            // book has no full-bleed geometry worth checking, because the panel becomes the window
            // and its chrome runs off the edges.
            if (width < BookGeometry.MIN_PANEL_WIDTH || height < BookGeometry.MIN_PANEL_HEIGHT) {
                return List.of(new BookGeometry(width, height, false, false, state));
            }
            return List.of(new BookGeometry(width, height, false, false, state),
                    new BookGeometry(width, height, true, true, state));
        }

        @Test
        @DisplayName("the default state is a floating pinned box, to the pixel")
        void theDefaultIsTodaysBook() {
            BookGeometry geometry = new BookGeometry(SCREENSHOT_WIDTH, SCREENSHOT_HEIGHT);
            assertEquals(SidebarState.DEFAULT, geometry.sidebarState(),
                    "a geometry that is not told otherwise holds the default state");
            assertEquals(BookGeometry.SIDEBAR_WIDTH, geometry.sidebarWidth(),
                    "drawn at today's width");
            assertEquals(geometry.panel().x(), geometry.canvas().x(),
                    "with the canvas taking the whole body under the floating box");
            assertEquals(geometry.panel().x() + BookGeometry.PANEL_GAP, geometry.sidebar().x(),
                    "and the box inset from the panel's edge");
            assertEquals(BookGeometry.SIDEBAR_WIDTH, geometry.sidebar().width(),
                    "at today's width");
        }

        @Test
        @DisplayName("no two controls overlap, in any list state at any size swept")
        void noTwoControlsOverlapInAnyState() {
            for (int[] size : stateSizes()) {
                for (SidebarState state : states()) {
                    for (BookGeometry geometry : geometriesAt(size[0], size[1], state)) {
                        Map<String, Rect> controls = geometry.controls();
                        String overlap = firstOverlap(controls);
                        assertTrue(overlap == null,
                                () -> "at " + size[0] + "x" + size[1] + " " + state + ": " + overlap);
                    }
                }
            }
        }

        @Test
        @DisplayName("every surface stays inside the panel, and the canvas keeps its minimum")
        void theSurfacesStayInside() {
            for (int[] size : stateSizes()) {
                for (SidebarState state : states()) {
                    for (BookGeometry geometry : geometriesAt(size[0], size[1], state)) {
                        String at = " at " + size[0] + "x" + size[1] + " " + state;
                        if (!state.collapsed()) {
                            assertTrue(geometry.sidebar().isInside(geometry.panel()),
                                    () -> "the list left the panel" + at);
                        }
                        // A sliver is mostly past the panel's left edge on purpose — asserted
                        // below — so only its size is held here, not its place.
                        assertTrue(geometry.canvas().isInside(geometry.panel()),
                                () -> "the canvas left the panel" + at);
                        assertTrue(geometry.canvas().width() >= 0, () -> "a negative canvas" + at);
                        assertTrue(geometry.sidebar().width() >= 0, () -> "a negative list" + at);
                        Rect viewport = geometry.sidebarViewport();
                        for (Map.Entry<String, Rect> entry : geometry.controls().entrySet()) {
                            assertFalse(viewport.intersects(entry.getValue()),
                                    () -> "the rows reach " + entry.getKey() + at);
                        }
                        if (!state.floating()) {
                            assertEquals(geometry.panel().x() + geometry.sidebarWidth(),
                                    geometry.canvas().x(), () -> "docked, the canvas starts past the list" + at);
                        }
                        else {
                            assertEquals(geometry.panel().x(), geometry.canvas().x(),
                                    () -> "otherwise the graph gets the whole body" + at);
                        }
                        if (state.floating() && state.collapsed()) {
                            // The sliver is the box slid left: same size, almost fully past the edge.
                            int peek = BookGeometry.SIDEBAR_PEEK_WIDTH;
                            int boxWidth = geometry.sidebar().width();
                            assertEquals(
                                    geometry.panel().x() + BookGeometry.PANEL_GAP
                                            + Math.min(boxWidth, peek),
                                    geometry.sidebar().right(),
                                    () -> "sticking out by its peek" + at);
                        }
                    }
                }
            }
        }

        @Test
        @DisplayName("the view cluster sits beside the list at the rows' height, in every state")
        void theViewClusterClearsTheList() {
            for (int[] size : stateSizes()) {
                for (SidebarState state : states()) {
                    for (BookGeometry geometry : geometriesAt(size[0], size[1], state)) {
                        String at = " at " + size[0] + "x" + size[1] + " " + state;
                        Map<String, Rect> controls = geometry.controls();
                        // The first button sits three below the chapter bar; the other two stack
                        // beneath it in their column (held by the cluster's own test).
                        assertEquals(geometry.bodyTop() + BookGeometry.CHAPTER_GAP + BookGeometry.VIEW_DROP,
                                controls.get("zoomIn").y(),
                                () -> "zoomIn sits three below the chapter bar" + at);
                        for (String key : List.of("zoomIn", "zoomOut", "centre")) {
                            Rect button = controls.get(key);
                            assertEquals(geometry.sidebar().right() + BookGeometry.EDGE, button.x(),
                                    () -> key + " sits beside the list" + at);
                            assertFalse(button.intersects(geometry.sidebar()),
                                    () -> key + " sits under the list" + at);
                        }
                        assertEquals(controls.get("zoomIn").x() - BookGeometry.VIEW_MAT,
                                geometry.viewControls().x(),
                                () -> "the backing sits behind its buttons" + at);
                    }
                }
            }
        }

        @Test
        @DisplayName("the clamp holds its range, and the draw-time cap keeps the canvas")
        void theClampAndTheCap() {
            assertEquals(BookGeometry.SIDEBAR_MIN_WIDTH, BookGeometry.clampSidebarWidth(-100), "a floor");
            assertEquals(BookGeometry.SIDEBAR_MAX_WIDTH, BookGeometry.clampSidebarWidth(10_000),
                    "and a ceiling");
            assertEquals(156, BookGeometry.clampSidebarWidth(156), "with the default passing through");

            // A remembered 260 on a panel barely wider than the minimum: the list keeps the
            // canvas beside it rather than covering it.
            int capped = BookGeometry.drawnSidebarWidth(BookGeometry.MIN_PANEL_WIDTH, 260);
            assertTrue(capped <= BookGeometry.MIN_PANEL_WIDTH - BookGeometry.MIN_CANVAS_WIDTH
                    - BookGeometry.EDGE, "the cap keeps the canvas: " + capped);
            assertTrue(capped >= 0, "and never goes negative: " + capped);
            assertEquals(156, BookGeometry.drawnSidebarWidth(800, 156),
                    "while a window with room draws what was asked");
        }

        @Test
        @DisplayName("the drag mirrors the rails': right widens the left-docked list")
        void theDragMirrors() {
            assertEquals(160, BookGeometry.sidebarWidthWhileDragging(156, 100, 104),
                    "four pixels right is four wider");
            assertEquals(152, BookGeometry.sidebarWidthWhileDragging(156, 100, 96),
                    "and four left is four narrower");
            assertEquals(BookGeometry.SIDEBAR_MIN_WIDTH,
                    BookGeometry.sidebarWidthWhileDragging(120, 100, -10_000), "floored");
            assertEquals(BookGeometry.SIDEBAR_MAX_WIDTH,
                    BookGeometry.sidebarWidthWhileDragging(260, 100, 10_000), "and ceilinged");
        }

        @Test
        @DisplayName("the handle is the list's own right edge, inside it")
        void theHandleIsTheListsRightEdge() {
            for (int width : new int[] {0, 1, 20, 28, 120, 156, 260}) {
                Rect list = Rect.at(20, 30, width, 300);
                Rect handle = BookGeometry.sidebarHandle(list);
                String at = "width " + width;
                assertTrue(handle.isInside(list) || width == 0, () -> "the strip left the list: " + at);
                assertEquals(list.right(), handle.right(), () -> "it is on the inner edge: " + at);
                assertEquals(Math.min(BookGeometry.SIDEBAR_HANDLE, width), handle.width(),
                        () -> "and is the handle's width: " + at);
                assertEquals(list.height(), handle.height(), () -> "for the list's whole height: " + at);
            }
        }

        @Test
        @DisplayName("hover room grows every side without moving the rectangle")
        void inflateIsHoverRoom() {
            Rect grown = Rect.at(20, 50, 156, 400).inflate(6);
            assertEquals(Rect.at(14, 44, 168, 412), grown, "six pixels a side");
            assertEquals(Rect.at(20, 50, 156, 400), grown.inflate(-6), "and back again");
        }

        @Test
        @DisplayName("the screenshot-sized window holds every state")
        void theScreenshotSizeHoldsEveryState() {
            for (SidebarState state : states()) {
                for (BookGeometry geometry : geometriesAt(SCREENSHOT_WIDTH, SCREENSHOT_HEIGHT, state)) {
                    String overlap = firstOverlap(geometry.controls());
                    assertTrue(overlap == null, () -> state + ": " + overlap);
                    if (!state.collapsed()) {
                        assertTrue(geometry.sidebar().isInside(geometry.panel()),
                                () -> state + " left");
                    }
                    if (!state.floating()) {
                        assertTrue(geometry.canvas().width() >= BookGeometry.MIN_CANVAS_WIDTH,
                                () -> state + " squeezed the graph to " + geometry.canvas().width());
                    }
                }
            }
        }

        @Test
        @DisplayName("the floating list is a box on the canvas, like a rail anchored left")
        void theFloatingListIsABox() {
            for (int[] size : stateSizes()) {
                SidebarState floating = new SidebarState(200, true, true, false);
                for (BookGeometry geometry : geometriesAt(size[0], size[1], floating)) {
                    String at = " at " + size[0] + "x" + size[1];
                    Rect box = geometry.sidebar();
                    Rect canvas = geometry.canvas();
                    assertEquals(canvas.x() + BookGeometry.PANEL_GAP, box.x(),
                            () -> "inset from the canvas's left edge" + at);
                    assertEquals(geometry.bodyTop() + BookGeometry.PANEL_GAP, box.y(),
                            () -> "and from its top" + at);
                    assertTrue(box.isInside(geometry.panel()), () -> "inside the panel" + at);
                    assertTrue(box.width() <= canvas.width() - BookGeometry.PANEL_GAP * 2,
                            () -> "never wider than the canvas less its gaps" + at);

                    // The box's own chrome lives inside it: the pin zone, the rows and the
                    // toolbar all measure from the box rather than the panel.
                    assertTrue(geometry.sidebarBoxPin().isInside(box),
                            () -> "the pin zone sits in its header" + at);
                    assertTrue(geometry.sidebarViewport().isInside(box),
                            () -> "the rows stay in the box" + at);
                    for (Rect toolbar : geometry.sidebarToolbar().values()) {
                        assertTrue(toolbar.isInside(box), () -> "and the toolbar" + at);
                    }
                }
            }
        }

        @Test
        @DisplayName("the pin zone sits in the floating box's header")
        void thePinZoneIsInTheBoxHeader() {
            BookGeometry floating =
                    new BookGeometry(900, 500, false, false, new SidebarState(200, true, true, false));
            Rect pin = floating.sidebarBoxPin();
            assertTrue(pin.isInside(floating.sidebar()), "the zone is in the box");
            assertEquals(floating.sidebar().right() - 4 - BookGeometry.ROW_HEIGHT, pin.x(),
                    "right-aligned like a close square");
            assertEquals(BookGeometry.ROW_HEIGHT, pin.height(), "square, like the controls");
        }
    }
}
