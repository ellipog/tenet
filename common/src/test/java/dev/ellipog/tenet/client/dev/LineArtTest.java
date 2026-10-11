package dev.ellipog.tenet.client.dev;

import dev.ellipog.tenet.quest.DependencyStyle;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The line geometry: routes, fills, arrowheads and hit-testing, without a screen.
 *
 * <h2>Why this is worth testing rather than looking at</h2>
 *
 * <p>Four readers share one path — the drawing, the hover, the right-click hit test and the rubber line
 * of an edge drag — and the failure mode of getting it wrong is not a wrong-looking line but a line you
 * cannot click on the pixels it is drawn with: the picture looks fine and the menu never opens. The
 * route, the dashes, the arrow counts and the distance are all arithmetic, so they are asserted here.
 */
@DisplayName("LineArt")
class LineArtTest {

    private static final LineArt.Point A = new LineArt.Point(0, 0);
    private static final LineArt.Point B = new LineArt.Point(40, 40);

    @Test
    @DisplayName("the orthogonal route is the three-segment step the canvas always drew")
    void theOrthogonalRoute() {
        List<LineArt.Point> path = LineArt.path(DependencyStyle.Form.ORTHOGONAL,
                new LineArt.Point(0, 0), new LineArt.Point(10, 10));

        assertEquals(List.of(new LineArt.Point(0, 0), new LineArt.Point(0, 5),
                new LineArt.Point(10, 5), new LineArt.Point(10, 10)), path);
        assertEquals(3, LineArt.fills(path, DependencyStyle.Weight.THIN, DependencyStyle.Dash.SOLID).size(),
                "out, across, in");
    }

    @Test
    @DisplayName("and a same-row line is one fill, as it was before this class existed")
    void aFlatLineIsOneFill() {
        assertEquals(1, LineArt.orthogonalFills(new LineArt.Point(3, 7), new LineArt.Point(20, 7)).size());
        assertEquals(1, LineArt.orthogonalFills(new LineArt.Point(3, 7), new LineArt.Point(3, 30)).size());
    }

    @Test
    @DisplayName("a straight line runs centre to centre, one pixel per step")
    void theStraightRoute() {
        List<LineArt.Point> path = LineArt.path(DependencyStyle.Form.STRAIGHT,
                new LineArt.Point(0, 0), new LineArt.Point(10, 5));

        assertEquals(new LineArt.Point(0, 0), path.get(0), "exactly the source");
        assertEquals(new LineArt.Point(10, 5), path.get(path.size() - 1), "exactly the target");
        assertEquals(11, path.size(), "one point per step along the longer axis");
    }

    @Test
    @DisplayName("a curve bows away from the chord, so two directions read as two lines")
    void theCurveBows() {
        List<LineArt.Point> path = LineArt.path(DependencyStyle.Form.CURVED, A, B);

        assertEquals(A, path.get(0));
        assertEquals(B, path.get(path.size() - 1));
        LineArt.Point middle = LineArt.pointAt(path, LineArt.length(path) / 2);
        // The chord's midpoint is (20, 20); the bow must be clearly off it.
        double off = Math.hypot(middle.x() - 20, middle.y() - 20);
        assertTrue(off > 4, "the middle of the curve is only " + off + " from the chord's middle");

        // And mirrored: the reverse edge bows to the other side, which is what keeps a mutual pair apart.
        List<LineArt.Point> reverse = LineArt.path(DependencyStyle.Form.CURVED, B, A);
        LineArt.Point reverseMiddle = LineArt.pointAt(reverse, LineArt.length(reverse) / 2);
        assertTrue(Math.hypot(reverseMiddle.x() - 20, reverseMiddle.y() - 20) > 4);
        assertTrue(middle.x() != reverseMiddle.x() || middle.y() != reverseMiddle.y(),
                "a pair of quests that depend on each other must not draw one line on top of the other");
    }

    @Test
    @DisplayName("a dashed line leaves gaps, and a solid one does not")
    void dashesLeaveGaps() {
        List<LineArt.Point> path = LineArt.path(DependencyStyle.Form.STRAIGHT,
                new LineArt.Point(0, 10), new LineArt.Point(80, 10));

        List<LineArt.Fill> solid = LineArt.fills(path, DependencyStyle.Weight.THIN, DependencyStyle.Dash.SOLID);
        List<LineArt.Fill> dashed = LineArt.fills(path, DependencyStyle.Weight.THIN, DependencyStyle.Dash.DASHED);

        assertEquals(1, solid.size(), "a solid straight run is one rectangle");
        assertTrue(dashed.size() > 3, "a dashed line is a series of runs: " + dashed.size());
        assertTrue(dashed.size() > solid.size());
    }

    @Test
    @DisplayName("a thick line is as many rows as its weight, straddling the route")
    void thickIsTwoRows() {
        // This used to read "drawn twice, one pixel apart", because the band was built by copying a
        // one-pixel run sideways — always one side. It is a filled band now, so what is worth asserting
        // is the width it covers and that it sits *on* the route rather than under it: the node rims the
        // line ends on are computed from the route, so a band hanging off one side met them off-centre.
        List<LineArt.Point> path = LineArt.path(DependencyStyle.Form.STRAIGHT,
                new LineArt.Point(0, 10), new LineArt.Point(40, 10));

        List<LineArt.Fill> fills = LineArt.fills(path, DependencyStyle.Weight.THICK, DependencyStyle.Dash.SOLID);

        assertEquals(2, spansRows(fills), "a two-pixel line is two rows: " + fills);
        Set<Long> ink = ink(fills);
        assertTrue(ink.contains(key(20, 9)) && ink.contains(key(20, 10)),
                "and the two rows straddle the route's own row: " + fills);
    }

    @Test
    @DisplayName("arrowheads follow the style: none, one at the target, both, or a repeating run")
    void arrowheadsFollowTheStyle() {
        List<LineArt.Point> path = LineArt.path(DependencyStyle.Form.STRAIGHT, A,
                new LineArt.Point(200, 200));

        assertEquals(0, LineArt.arrows(path, DependencyStyle.Arrows.NONE, 10, 10).size());
        List<LineArt.Fill> one = LineArt.arrows(path, DependencyStyle.Arrows.ONE, 10, 10);
        assertTrue(one.size() >= 4, "a chevron is two strokes: " + one.size());
        List<LineArt.Fill> both = LineArt.arrows(path, DependencyStyle.Arrows.BOTH, 10, 10);
        assertTrue(both.size() > one.size(), "two heads are more than one");
        List<LineArt.Fill> many = LineArt.arrows(path, DependencyStyle.Arrows.MANY, 10, 10);
        assertTrue(many.size() > both.size(), "many repeats along the line: " + many.size());

        // The head stops at the node's rim rather than at its centre, so it is not hidden by the node.
        for (LineArt.Fill fill : one) {
            assertTrue(Math.hypot(fill.x1() - 200, fill.y1() - 200) > 10,
                    "an arrowhead at the centre would be drawn under the node: " + fill);
        }
    }

    @Test
    @DisplayName("an orthogonal line is its three whole segments, with no dots and no gaps")
    void theOrthogonalRouteIsWhole() {
        // The reported bug, as a test: the run-merging loop emitted a one-pixel dot at each corner and
        // skipped the segment after it, so an orthogonal line came out as a stub and a speck.
        List<LineArt.Point> path = LineArt.path(DependencyStyle.Form.ORTHOGONAL,
                new LineArt.Point(0, 0), new LineArt.Point(60, 40));
        List<LineArt.Fill> fills = LineArt.fills(path, DependencyStyle.Weight.THIN,
                DependencyStyle.Dash.SOLID);

        assertEquals(3, fills.size(), "out, across, in: " + fills);
        for (LineArt.Fill fill : fills) {
            assertTrue(fill.x2() > fill.x1() || fill.y2() > fill.y1(),
                    "no zero-area fill anywhere: " + fill);
        }
        assertTrue(covers(fills, LineArt.walk(path)), "every pixel of the route is drawn: " + fills);
    }

    @Test
    @DisplayName("a solid line is solid whatever its form")
    void solidIsSolid() {
        // The second reported bug: a curved or diagonal line sampled every couple of pixels drew one
        // pixel per sample, so "solid" came out dotted. Every walk point must be covered by some fill.
        for (DependencyStyle.Form form : DependencyStyle.Form.values()) {
            List<LineArt.Point> path = LineArt.path(form, new LineArt.Point(10, 90),
                    new LineArt.Point(150, 20));
            List<LineArt.Fill> fills = LineArt.fills(path, DependencyStyle.Weight.THIN,
                    DependencyStyle.Dash.SOLID);

            assertTrue(covers(fills, LineArt.walk(path)),
                    form + " leaves gaps in a solid line: " + fills);
            for (LineArt.Fill fill : fills) {
                assertTrue(fill.x2() > fill.x1() || fill.y2() > fill.y1(),
                        form + " drew a zero-area fill: " + fill);
            }
        }
    }

    @Test
    @DisplayName("each arrowhead clears its own node, sized by that node rather than by the larger one")
    void arrowsUseTheirOwnEndsSize() {
        // A small node next to a large one: one shared half-size pushed the small end's head away by its
        // neighbour's bulk, which is how a head ended up floating in the middle of a line.
        List<LineArt.Point> path = LineArt.path(DependencyStyle.Form.STRAIGHT,
                new LineArt.Point(0, 0), new LineArt.Point(160, 0));
        List<LineArt.Fill> fills = LineArt.arrows(path, DependencyStyle.Arrows.BOTH, 6, 40);

        for (LineArt.Fill fill : fills) {
            double fromEnd = Math.hypot(fill.x1() - 0, fill.y1() - 0);
            double toEnd = Math.hypot(fill.x1() - 160, fill.y1() - 0);
            assertTrue(fromEnd > 6 || toEnd > 40,
                    "no head pixel may sit inside either node's own half: " + fill);
        }
        assertTrue(fills.stream().anyMatch(fill -> Math.hypot(fill.x1(), fill.y1()) < 20),
                "and the small end's head is close to the small node, not pushed out by the large one");
    }

    @Test
    @DisplayName("an edge too short for its heads has none, rather than heads buried in the nodes")
    void aShortEdgeHasNoArrows() {
        List<LineArt.Point> path = LineArt.path(DependencyStyle.Form.STRAIGHT,
                new LineArt.Point(0, 0), new LineArt.Point(14, 0));

        assertTrue(LineArt.arrows(path, DependencyStyle.Arrows.ONE, 20, 20).isEmpty());
        assertTrue(LineArt.arrows(path, DependencyStyle.Arrows.BOTH, 20, 20).isEmpty());
    }

    @Test
    @DisplayName("the rim is found along the direction asked for, not one of eight")
    void theRimFollowsTheDirection() {
        // The bug this pins: rounding the step's two components independently snapped the walk to 0, 45
        // and 90 degrees, so a line left its node pointing up to 22.5 degrees off.
        LineArt.Point centre = new LineArt.Point(100, 100);
        int size = 40;
        for (double degrees : new double[] { 20, 30, 70, 110, 155, -40 }) {
            LineArt.Point towards = LineArt.anchorPoint(centre, size * 2, degrees);
            LineArt.Point rim = LineArt.rimPoint(centre, towards, size,
                    (x, y) -> Math.hypot(x - centre.x(), y - centre.y()) <= size / 2.0);
            double found = LineArt.anchorAngle(centre, rim.x(), rim.y());
            assertEquals(degrees, found, 3.0, "the rim of a circle at " + degrees + " degrees");
        }
    }

    @Test
    @DisplayName("a square's diagonal rim is reached, not stopped short inside the shape")
    void theRimReachesASquaresCorner() {
        LineArt.Point centre = new LineArt.Point(100, 100);
        int size = 40;
        java.util.function.BiPredicate<Integer, Integer> square =
                (x, y) -> Math.abs(x - centre.x()) <= size / 2 && Math.abs(y - centre.y()) <= size / 2;

        LineArt.Point diagonal = LineArt.rimPoint(centre, new LineArt.Point(200, 200), size, square);
        double distance = Math.hypot(diagonal.x() - centre.x(), diagonal.y() - centre.y());

        assertTrue(distance > size / 2 + 4,
                "the diagonal rim is further out than the circle that fits inside: " + distance);
        assertEquals(size / 2.0 * Math.sqrt(2), distance, 3.0, "and at the corner, 0.71 * size");
    }

    @Test
    @DisplayName("an anchor at 45 degrees lands on a square's rim, not inside it")
    void anAnchorUsesTheRealRim() {
        // What the screen does for an anchored end: walk toward the anchor's direction, not to a radius.
        LineArt.Point centre = new LineArt.Point(50, 50);
        int size = 30;
        java.util.function.BiPredicate<Integer, Integer> square =
                (x, y) -> Math.abs(x - centre.x()) <= size / 2 && Math.abs(y - centre.y()) <= size / 2;

        LineArt.Point rim = LineArt.rimPoint(centre, LineArt.anchorPoint(centre, size * 2, 45), size, square);

        assertTrue(Math.abs(rim.x() - centre.x()) > size / 2 - 3,
                "on the corner rather than at half the size: " + rim);
        assertTrue(square.test(rim.x(), rim.y()), "and still on the shape");
    }

    @Test
    @DisplayName("the tangent is the path's own direction, even three pixels from an end")
    void theTangentNeverGoesFlat() {
        // The flat-chevron bug: two samples six pixels apart, both clamped to the same end point, made
        // atan2(0, 0) zero -- an arrow drawn due east on a vertical line.
        List<LineArt.Point> vertical = LineArt.path(DependencyStyle.Form.STRAIGHT,
                new LineArt.Point(50, 10), new LineArt.Point(50, 90));

        for (double at : new double[] { 0, 3, 40, 77, 80 }) {
            double angle = Math.toDegrees(LineArt.tangentAt(vertical, at));
            assertEquals(90, angle, 1.0, "vertical at " + at + " pixels along");
        }
    }

    @Test
    @DisplayName("and it is each segment's own direction on a stepped route, and the curve's along a bow")
    void theTangentFollowsTheRoute() {
        List<LineArt.Point> stepped = LineArt.path(DependencyStyle.Form.ORTHOGONAL,
                new LineArt.Point(0, 0), new LineArt.Point(80, 40));
        assertEquals(90, Math.toDegrees(LineArt.tangentAt(stepped, 5)), 1.0, "down the first leg");
        assertEquals(0, Math.toDegrees(LineArt.tangentAt(stepped, 40)), 1.0, "across the middle");
        assertEquals(90, Math.toDegrees(LineArt.tangentAt(stepped, 110)), 1.0, "down into the target");

        List<LineArt.Point> curved = LineArt.path(DependencyStyle.Form.CURVED,
                new LineArt.Point(0, 0), new LineArt.Point(100, 0), 0.4);
        double third = Math.toDegrees(LineArt.tangentAt(curved, LineArt.length(curved) / 3));
        double twoThirds = Math.toDegrees(LineArt.tangentAt(curved, LineArt.length(curved) * 2 / 3));
        assertTrue(Math.abs(third - twoThirds) > 10,
                "the direction turns along the arc: " + third + " against " + twoThirds);
    }

    @Test
    @DisplayName("a chevron at a vertical line's arrival points along the line, not east")
    void theArrivalChevronFollowsTheLine() {
        List<LineArt.Point> vertical = LineArt.path(DependencyStyle.Form.STRAIGHT,
                new LineArt.Point(50, 10), new LineArt.Point(50, 90));
        List<LineArt.Fill> fills = LineArt.arrows(vertical, DependencyStyle.Arrows.ONE, 0, 0);

        assertTrue(fills.size() >= 4, "a chevron is two strokes: " + fills.size());
        for (LineArt.Fill fill : fills) {
            assertTrue(Math.abs(fill.x1() - 50) <= 3,
                    "a wing reaching east is the bug: " + fill);
        }
    }

    @Test
    @DisplayName("the bow is a fraction of the chord, and bendAt is its exact inverse")
    void theBowRoundTrips() {
        LineArt.Point from = new LineArt.Point(0, 0);
        LineArt.Point to = new LineArt.Point(100, 0);

        for (double bend : new double[] { 0.2, -0.5, 0.75 }) {
            List<LineArt.Point> path = LineArt.path(DependencyStyle.Form.CURVED, from, to, bend);
            LineArt.Point middle = LineArt.pointAt(path, LineArt.length(path) / 2);
            assertEquals(bend, LineArt.bendAt(from, to, middle.x(), middle.y()), 0.05,
                    "dragging the middle to where it already is must keep the bow");
            assertEquals(Math.abs(bend) * 100 / 2, Math.abs(middle.y()), 4,
                    "the middle sits half the control's offset off the chord: " + middle);
        }
    }

    @Test
    @DisplayName("a drag is clamped to the limit, and snaps to exactly straight inside the dead zone")
    void bendLimits() {
        assertEquals(DependencyStyle.MAX_BEND, LineArt.limitBend(3.0), 0.0001);
        assertEquals(-DependencyStyle.MAX_BEND, LineArt.limitBend(-3.0), 0.0001);
        assertEquals(0.4, LineArt.limitBend(0.4), 0.0001);
        assertEquals(0.0, LineArt.limitBend(0.02), 0.0001,
                "a hand that lands almost straight means straight");
        assertEquals(0.0, LineArt.limitBend(-0.04), 0.0001);
    }

    /** Whether every point of the walk falls inside one of the fills. */
    private static boolean covers(List<LineArt.Fill> fills, List<LineArt.Point> walk) {
        for (LineArt.Point point : walk) {
            boolean covered = false;
            for (LineArt.Fill fill : fills) {
                if (point.x() >= fill.x1() && point.x() < fill.x2()
                        && point.y() >= fill.y1() && point.y() < fill.y2()) {
                    covered = true;
                    break;
                }
            }
            if (!covered) {
                return false;
            }
        }
        return true;
    }

    @Test
    @DisplayName("distance is to the nearest pixel of the path, and nearest picks the closest line")
    void hitTesting() {
        List<LineArt.Point> flat = LineArt.path(DependencyStyle.Form.STRAIGHT,
                new LineArt.Point(0, 0), new LineArt.Point(100, 0));
        List<LineArt.Point> far = LineArt.path(DependencyStyle.Form.STRAIGHT,
                new LineArt.Point(0, 50), new LineArt.Point(100, 50));

        assertEquals(0.0, LineArt.distance(flat, 50, 0), 0.001, "on the line");
        assertEquals(3.0, LineArt.distance(flat, 50, 3), 0.001, "three pixels off");

        List<LineArt.Candidate<String>> candidates = List.of(
                new LineArt.Candidate<>("flat", flat), new LineArt.Candidate<>("far", far));
        assertEquals("flat", LineArt.nearest(candidates, 50, 2, LineArt.TOLERANCE));
        assertEquals("far", LineArt.nearest(candidates, 50, 48, LineArt.TOLERANCE));
        assertNull(LineArt.nearest(candidates, 50, 25, LineArt.TOLERANCE), "between them is on neither");
        assertNotNull(LineArt.nearest(List.of(new LineArt.Candidate<>("flat", flat)), 50, 4, LineArt.TOLERANCE),
                "the tolerance is inclusive, because a hit test that excluded its own edge is a dead band");
    }

    @Test
    @DisplayName("a line touches its two ends in either direction, and nothing else")
    void aLineTouchesItsEnds() {
        assertTrue(LineArt.touches("a", "b", "a"), "the prerequisite end");
        assertTrue(LineArt.touches("a", "b", "b"), "the dependent end");
        assertFalse(LineArt.touches("a", "b", "c"), "a quest the line does not join");
        assertFalse(LineArt.touches("a", "b", "link:a"), "a marker lives in its own namespace");
    }

    // ------------------------------------------------------------------
    // Split handles: the cubic, and the chord's own frame
    // ------------------------------------------------------------------

    @Test
    @DisplayName("splitting a bow into two control points does not move the line under the hand")
    void splittingABowLeavesTheCurveWhereItWas() {
        // The promise the Split handles menu entry makes: the pair written is the degree-elevated
        // quadratic, so the cubic is the same curve -- same middle point, to the pixel of the shared
        // sampler. If this drifts, every split would visibly snap the line the author was looking at.
        LineArt.Point from = new LineArt.Point(40, 40);
        LineArt.Point to = new LineArt.Point(240, 90);
        for (double bend : new double[] { 0.2, -0.5, 0.75 }) {
            List<LineArt.Point> quadratic = LineArt.path(DependencyStyle.Form.CURVED, from, to, bend);
            List<LineArt.Point> cubic = LineArt.cubic(from, to,
                    LineArt.equivalentFromHandle(bend), LineArt.equivalentToHandle(bend));

            LineArt.Point before = LineArt.pointAt(quadratic, LineArt.length(quadratic) / 2);
            LineArt.Point after = LineArt.pointAt(cubic, LineArt.length(cubic) / 2);
            assertEquals((double) before.x(), (double) after.x(), 2.0,
                    "bend " + bend + ": the middle must stay put");
            assertEquals((double) before.y(), (double) after.y(), 2.0,
                    "bend " + bend + ": the middle must stay put");
        }
    }

    @Test
    @DisplayName("a control point round-trips through the chord's frame, negative across included")
    void controlPointsRoundTripThroughTheChordFrame() {
        // The drag is only honest if `chordFraction` is the exact inverse of `handlePoint`: what the
        // pointer says becomes the value, and the value is drawn back under the pointer. `across` is
        // signed, and a drag to the other side must keep its sign rather than folding to zero.
        LineArt.Point from = new LineArt.Point(-30, 120);
        LineArt.Point to = new LineArt.Point(210, -40);
        double length = Math.hypot(to.x() - from.x(), to.y() - from.y());
        double ux = (to.x() - from.x()) / length;
        double uy = (to.y() - from.y()) / length;

        for (double[] handle : new double[][] { { 0.33, 0.2 }, { 0.66, -0.15 }, { 1.2, 0.0 },
                { -0.25, -0.7 } }) {
            LineArt.Point point = LineArt.handlePoint(from, ux, uy, length,
                    List.of(handle[0], handle[1]));
            List<Double> back = LineArt.chordFraction(from, to, point.x(), point.y());
            assertEquals(handle[0], back.get(0), 0.01, "along " + handle[0] + ", " + handle[1]);
            assertEquals(handle[1], back.get(1), 0.01, "across " + handle[0] + ", " + handle[1]);
        }
    }

    @Test
    @DisplayName("the frame's ends are the nodes: along zero sits on the source, along one on the target")
    void theFrameEndsAreTheNodes() {
        LineArt.Point from = new LineArt.Point(12, -8);
        LineArt.Point to = new LineArt.Point(90, 40);
        double length = Math.hypot(to.x() - from.x(), to.y() - from.y());
        double ux = (to.x() - from.x()) / length;
        double uy = (to.y() - from.y()) / length;

        assertEquals(from, LineArt.handlePoint(from, ux, uy, length, List.of(0.0, 0.0)));
        assertEquals(to, LineArt.handlePoint(from, ux, uy, length, List.of(1.0, 0.0)));

        List<LineArt.Point> path = LineArt.cubic(from, to,
                LineArt.equivalentFromHandle(0.2), LineArt.equivalentToHandle(0.2));
        assertEquals(from, path.get(0), "a cubic starts at its source, whatever its handles");
        assertEquals(to, path.get(path.size() - 1), "and ends at its target");
    }

    @Test
    @DisplayName("opposite control points make a real S: the curve visits both sides of the chord")
    void oppositeControlPointsCrossTheChord() {
        // The reason the feature exists: one bow cannot make an S, and the sign of `across` is what
        // says which side of the chord a control point pulls towards. Two signs, two sides.
        LineArt.Point from = new LineArt.Point(0, 0);
        LineArt.Point to = new LineArt.Point(200, 0);
        List<LineArt.Point> s = LineArt.cubic(from, to, List.of(0.33, 0.25), List.of(0.66, -0.25));

        assertTrue(s.stream().anyMatch(point -> point.y() > 2), "one loop above the chord: " + s);
        assertTrue(s.stream().anyMatch(point -> point.y() < -2), "and one below it: " + s);

        List<LineArt.Point> arc = LineArt.cubic(from, to, List.of(0.33, 0.25), List.of(0.66, 0.25));
        assertTrue(arc.stream().allMatch(point -> point.y() >= 0),
                "the same sign on both sides is still a one-sided bow: " + arc);
    }

    // ------------------------------------------------------------------
    // The hover's reach: what a hand can be near and still be on the line
    // ------------------------------------------------------------------

    @Test
    @DisplayName("the reach is the nearest arm, and having no arms is out of reach of everything")
    void distanceToAnyPicksTheNearestArm() {
        List<LineArt.Point> ink = LineArt.path(DependencyStyle.Form.STRAIGHT,
                new LineArt.Point(0, 0), new LineArt.Point(100, 0));
        List<LineArt.Point> leash = LineArt.steps(new LineArt.Point(50, 0), new LineArt.Point(50, 60));

        assertEquals(0.0, LineArt.distanceToAny(List.of(ink), 50, 0), 0.001, "on the ink");
        assertEquals(7.0, LineArt.distanceToAny(List.of(ink), 50, 7), 0.001);
        assertEquals(0.0, LineArt.distanceToAny(List.of(ink, leash), 50, 60), 0.001,
                "the leash is its own arm, so its far end is as near as the ink");
        assertEquals(Double.MAX_VALUE, LineArt.distanceToAny(List.of(), 0, 0), 0.001,
                "nothing to be near is out of reach");
    }

    @Test
    @DisplayName("a control point off the chord is reachable only through its leash -- the reveal's own point")
    void anOffChordControlPointIsReachedByItsLeash() {
        // The bug this pins: a split control point sits off the ink by design, and the reveal once knew
        // about the ink alone -- so the diamond vanished exactly as the hand left the line to reach it.
        // The union of ink and leash has no dead gap; the leash is what carries the hover out.
        LineArt.Point from = new LineArt.Point(0, 0);
        LineArt.Point to = new LineArt.Point(200, 0);
        List<LineArt.Point> ink = LineArt.cubic(from, to, List.of(0.33, 0.3), List.of(0.66, -0.3));
        LineArt.Point control = LineArt.handlePoint(from, 1, 0, 200, List.of(0.33, 0.3));
        List<LineArt.Point> leash = LineArt.steps(ink.get(0), control);

        assertTrue(LineArt.distance(ink, control.x(), control.y()) > 20,
                "the diamond really is away from the ink: " + control);
        assertEquals(0.0, LineArt.distanceToAny(List.of(ink, leash), control.x(), control.y()), 0.001,
                "and on its leash, so the hover can reach it");
    }

    @Test
    @DisplayName("the reach's boundary: nine pixels is inside the screen's ten, eleven is outside")
    void theReachBoundary() {
        // The screen's HANDLE_REACH is ten pixels (its HANDLE_GRAB of 8, plus 4); the arithmetic below is
        // what keeps that number meaningful rather than a comment.
        List<LineArt.Point> ink = LineArt.path(DependencyStyle.Form.STRAIGHT,
                new LineArt.Point(0, 0), new LineArt.Point(100, 0));

        assertTrue(LineArt.distanceToAny(List.of(ink), 50, 9) <= 10, "nine pixels is within reach");
        assertTrue(LineArt.distanceToAny(List.of(ink), 50, 11) > 10, "eleven is not");
    }

    // ------------------------------------------------------------------
    // The added form: a circuit trace
    // ------------------------------------------------------------------

    @Test
    @DisplayName("a chamfered route cuts both corners at 45 degrees, a fixed distance back")
    void theChamferedRouteCutsCorners() {
        List<LineArt.Point> path = LineArt.path(DependencyStyle.Form.CHAMFERED,
                new LineArt.Point(0, 0), new LineArt.Point(100, 50));

        assertEquals(List.of(new LineArt.Point(0, 0), new LineArt.Point(0, 19),
                new LineArt.Point(6, 25), new LineArt.Point(94, 25),
                new LineArt.Point(100, 31), new LineArt.Point(100, 50)), path);

        // The cut is the shape's whole point: equal steps on both axes, so 45 degrees.
        for (int i = 0; i < path.size() - 1; i++) {
            LineArt.Point a = path.get(i);
            LineArt.Point b = path.get(i + 1);
            if (a.x() != b.x() && a.y() != b.y()) {
                assertEquals(Math.abs(b.x() - a.x()), Math.abs(b.y() - a.y()),
                        "a diagonal that is not 45 degrees: " + a + " -> " + b);
            }
        }
    }

    @Test
    @DisplayName("a corner whose arms are too short is left square rather than nicked")
    void aShortCornerStaysSquare() {
        // A one-pixel bevel is a nick, not a trace: below the minimum the corner is kept as it was, so a
        // short edge looks like an orthogonal one instead of a fault.
        List<LineArt.Point> path = LineArt.path(DependencyStyle.Form.CHAMFERED,
                new LineArt.Point(0, 0), new LineArt.Point(4, 3));

        assertEquals(List.of(new LineArt.Point(0, 0), new LineArt.Point(0, 1),
                new LineArt.Point(4, 1), new LineArt.Point(4, 3)), path);
    }

    // ------------------------------------------------------------------
    // The added patterns and weights
    // ------------------------------------------------------------------

    @Test
    @DisplayName("a dotted line is single pixels with even gaps, not short dashes")
    void dottedIsSinglePixelDots() {
        List<LineArt.Point> path = LineArt.path(DependencyStyle.Form.STRAIGHT,
                new LineArt.Point(0, 10), new LineArt.Point(40, 10));
        List<LineArt.Fill> dotted =
                LineArt.fills(path, DependencyStyle.Weight.THIN, DependencyStyle.Dash.DOTTED);

        // Forty-one walk points, one on in every four: eleven dots, each exactly one pixel wide.
        assertEquals(11, dotted.size(), "dots: " + dotted);
        int previousX = -1;
        for (LineArt.Fill fill : dotted) {
            assertEquals(1, fill.x2() - fill.x1(), "a dot is one pixel: " + fill);
            assertTrue(previousX < 0 || fill.x1() - previousX == 4, "even four-pixel rhythm: " + dotted);
            previousX = fill.x1();
        }
    }

    @Test
    @DisplayName("a dash-dot line alternates a long run and a single dot")
    void dashDotIsTheLongShortRhythm() {
        List<LineArt.Point> path = LineArt.path(DependencyStyle.Form.STRAIGHT,
                new LineArt.Point(0, 10), new LineArt.Point(40, 10));
        List<LineArt.Fill> fills =
                LineArt.fills(path, DependencyStyle.Weight.THIN, DependencyStyle.Dash.DASH_DOT);

        assertEquals(6, fills.get(0).x2() - fills.get(0).x1(), "the dash: " + fills);
        assertEquals(1, fills.get(1).x2() - fills.get(1).x1(), "the dot: " + fills);
        assertEquals(6, fills.get(2).x2() - fills.get(2).x1(), "then the next dash: " + fills);
        assertEquals(fills.get(0).x1() + 13, fills.get(2).x1(), "one full rhythm apart");
    }

    @Test
    @DisplayName("a double line is two hairlines either side of the route, and the weight is ignored")
    void doubleIsTwoHairlines() {
        List<LineArt.Point> path = LineArt.path(DependencyStyle.Form.STRAIGHT,
                new LineArt.Point(0, 10), new LineArt.Point(40, 10));

        List<LineArt.Fill> thin =
                LineArt.fills(path, DependencyStyle.Weight.THIN, DependencyStyle.Dash.DOUBLE);
        assertEquals(2, thin.size(), "two runs, not one: " + thin);
        assertEquals(9, thin.get(0).y1(), "one above the route");
        assertEquals(11, thin.get(1).y1(), "and one below it");

        assertEquals(thin, LineArt.fills(path, DependencyStyle.Weight.CONDUIT, DependencyStyle.Dash.DOUBLE),
                "a double line is hairlines by definition: the weight axis does not thicken it");
    }

    @Test
    @DisplayName("a hazard line is its run plus hatch marks crossing it")
    void hazardAddsBarbs() {
        List<LineArt.Point> path = LineArt.path(DependencyStyle.Form.STRAIGHT,
                new LineArt.Point(0, 10), new LineArt.Point(60, 10));

        assertEquals(1, LineArt.fills(path, DependencyStyle.Weight.THIN, DependencyStyle.Dash.SOLID).size());
        List<LineArt.Fill> hazard =
                LineArt.fills(path, DependencyStyle.Weight.THIN, DependencyStyle.Dash.HAZARD);
        assertTrue(hazard.size() > 1, "the barbs are extra ink: " + hazard.size());
        assertTrue(hazard.stream().anyMatch(fill -> fill.y1() > 10), "the barbs cross the route: " + hazard);
    }

    @Test
    @DisplayName("the band is as wide as the weight, and a conduit runs border, body, core")
    void weightsAreTheirWidth() {
        // The counts here used to be fill counts — one rectangle per copied run — because that is what
        // the drawing was. A filled band merges rows, so the count says nothing about the width; the
        // rasterised width does, and the tones are read from the fills themselves.
        List<LineArt.Point> path = LineArt.path(DependencyStyle.Form.STRAIGHT,
                new LineArt.Point(0, 10), new LineArt.Point(40, 10));

        for (DependencyStyle.Weight weight : DependencyStyle.Weight.values()) {
            List<LineArt.Fill> fills = LineArt.fills(path, weight, DependencyStyle.Dash.SOLID);
            assertEquals(weight.width(), spansRows(fills),
                    "the band is not its own width: " + weight + " " + fills);
            assertTrue(ink(fills).size() >= weight.width() * 40,
                    "and it runs the route's length: " + weight);
        }

        List<LineArt.Fill> conduit =
                LineArt.fills(path, DependencyStyle.Weight.CONDUIT, DependencyStyle.Dash.SOLID);
        assertEquals(6, conduit.size(), "a conduit's six rows are their own tones: " + conduit);
        assertEquals(List.of(LineArt.Tone.EDGE, LineArt.Tone.MAIN, LineArt.Tone.CORE,
                        LineArt.Tone.CORE, LineArt.Tone.MAIN, LineArt.Tone.EDGE),
                conduit.stream().map(LineArt.Fill::tone).toList(),
                "dark border, body, light core, and the same back down");
        for (int i = 1; i < conduit.size(); i++) {
            assertEquals(conduit.get(i - 1).y2(), conduit.get(i).y1(), "the band is contiguous at " + i);
        }
    }

    // ------------------------------------------------------------------
    // The heads: four glyphs, three placements, one density
    // ------------------------------------------------------------------

    @Test
    @DisplayName("the glyphs are distinct shapes, and none is no ink at all")
    void theGlyphsAreDistinct() {
        List<LineArt.Point> path = LineArt.path(DependencyStyle.Form.STRAIGHT,
                new LineArt.Point(0, 0), new LineArt.Point(200, 0));

        assertEquals(0, LineArt.arrows(path, DependencyStyle.ArrowHead.NONE,
                DependencyStyle.ArrowPlace.TARGET, 32, 10, 10).size(), "a blunt end is no head");
        List<LineArt.Fill> chevron = LineArt.arrows(path, DependencyStyle.ArrowHead.CHEVRON,
                DependencyStyle.ArrowPlace.TARGET, 32, 10, 10);
        List<LineArt.Fill> triangle = LineArt.arrows(path, DependencyStyle.ArrowHead.TRIANGLE,
                DependencyStyle.ArrowPlace.TARGET, 32, 10, 10);
        List<LineArt.Fill> dot = LineArt.arrows(path, DependencyStyle.ArrowHead.DOT,
                DependencyStyle.ArrowPlace.TARGET, 32, 10, 10);
        List<LineArt.Fill> diamond = LineArt.arrows(path, DependencyStyle.ArrowHead.DIAMOND,
                DependencyStyle.ArrowPlace.TARGET, 32, 10, 10);

        for (List<LineArt.Fill> glyph : List.of(chevron, triangle, dot, diamond)) {
            assertFalse(glyph.isEmpty(), "every glyph draws something");
        }
        assertEquals(5, dot.size(), "a bead is five pixels: " + dot);
        assertTrue(triangle.size() > dot.size(), "a triangle is a filled wedge: " + triangle.size());
        assertTrue(diamond.size() > dot.size(), "and so is a diamond: " + diamond.size());

        // Every head is a cluster, not a scribble: all of its pixels sit together at the tip.
        LineArt.Point tip = LineArt.pointAt(path, LineArt.length(path) - 13);
        for (LineArt.Fill fill : triangle) {
            assertTrue(Math.hypot(fill.x1() - tip.x(), fill.y1() - tip.y()) <= 10,
                    "a triangle pixel away from its tip: " + fill);
        }
    }

    @Test
    @DisplayName("placements: one at the target, both ends, or one dead-centre pointing along the route")
    void placementsPlaceTheHeads() {
        List<LineArt.Point> path = LineArt.path(DependencyStyle.Form.STRAIGHT,
                new LineArt.Point(0, 0), new LineArt.Point(200, 0));

        List<LineArt.Fill> target = LineArt.arrows(path, DependencyStyle.ArrowHead.TRIANGLE,
                DependencyStyle.ArrowPlace.TARGET, 32, 10, 10);
        List<LineArt.Fill> both = LineArt.arrows(path, DependencyStyle.ArrowHead.TRIANGLE,
                DependencyStyle.ArrowPlace.BOTH, 32, 10, 10);
        assertEquals(target.size() * 2, both.size(), "both ends are two heads: " + both);

        List<LineArt.Fill> mid = LineArt.arrows(path, DependencyStyle.ArrowHead.TRIANGLE,
                DependencyStyle.ArrowPlace.MID, 32, 10, 10);
        assertEquals(target.size(), mid.size(), "the middle placement is one head, not two");
        for (LineArt.Fill fill : mid) {
            assertTrue(fill.x1() >= 90 && fill.x1() <= 110, "a mid head belongs at the middle: " + fill);
        }
    }

    @Test
    @DisplayName("a stream's rhythm is its spacing, and the legacy many keeps the old one")
    void streamsFollowTheirDensity() {
        List<LineArt.Point> path = LineArt.path(DependencyStyle.Form.STRAIGHT,
                new LineArt.Point(0, 0), new LineArt.Point(600, 0));

        List<LineArt.Fill> low = LineArt.arrows(path, DependencyStyle.ArrowHead.CHEVRON,
                DependencyStyle.ArrowPlace.STREAM, DependencyStyle.ArrowDensity.LOW.spacing(), 0, 0);
        List<LineArt.Fill> high = LineArt.arrows(path, DependencyStyle.ArrowHead.CHEVRON,
                DependencyStyle.ArrowPlace.STREAM, DependencyStyle.ArrowDensity.HIGH.spacing(), 0, 0);
        assertTrue(high.size() > low.size() * 2,
                "high repeats far more often: " + high.size() + " vs " + low.size());

        // The legacy axis draws through the same code, with the 24-pixel rhythm it always had.
        List<LineArt.Fill> many = LineArt.arrows(path, DependencyStyle.Arrows.MANY, 0, 0);
        assertFalse(many.isEmpty());
        assertEquals(LineArt.arrows(path, DependencyStyle.ArrowHead.CHEVRON,
                        DependencyStyle.ArrowPlace.STREAM, DependencyStyle.LEGACY_STREAM_SPACING, 0, 0),
                many, "the legacy many and a stream at 24 pixels are the same drawing");
    }

    // ------------------------------------------------------------------
    // The stroke: as thick as its weight, and whole at every form
    // ------------------------------------------------------------------

    @Test
    @DisplayName("a thick line is whole through every corner, at every form and weight")
    void cornersAreWhole() {
        // The reported fault, as a test. The old band grew downward along a horizontal run and rightward
        // along a vertical one, both measured absolutely rather than along the route, so a turn whose
        // arms opened the other way left a whole width-wide block of the elbow empty -- a bite out of the
        // corner, which is what "unexpected gaps and holes" was looking at.
        for (DependencyStyle.Form form : List.of(DependencyStyle.Form.ORTHOGONAL,
                DependencyStyle.Form.CHAMFERED)) {
            for (DependencyStyle.Weight weight : DependencyStyle.Weight.values()) {
                for (int[] ends : new int[][] { { 60, 10, 10, 60 }, { 10, 10, 60, 60 },
                        { 60, 60, 10, 10 } }) {
                    List<LineArt.Point> path = LineArt.path(form, new LineArt.Point(ends[0], ends[1]),
                            new LineArt.Point(ends[2], ends[3]));
                    assertWhole(LineArt.fills(path, weight, DependencyStyle.Dash.SOLID), path, weight,
                            form + " " + weight);
                }
            }
        }
    }

    @Test
    @DisplayName("a diagonal is as thick as a straight run, and the band follows the route")
    void diagonalsAreWhole() {
        // The second half of the report: a chip offset "diagonally" regardless of the slope made a
        // shallow diagonal a different apparent thickness from a steep one, and left the band oblique to
        // the line it was meant to be. Area is the honest measure -- a stroke's ink is its width times
        // its length whatever the angle -- so it is asserted, along with the same wholeness as above.
        for (DependencyStyle.Form form : DependencyStyle.Form.values()) {
            for (DependencyStyle.Weight weight : DependencyStyle.Weight.values()) {
                List<LineArt.Point> path = LineArt.path(form, new LineArt.Point(10, 90),
                        new LineArt.Point(150, 20));
                String at = form + " " + weight;
                List<LineArt.Fill> fills = LineArt.fills(path, weight, DependencyStyle.Dash.SOLID);

                assertWhole(fills, path, weight, at);
                double area = ink(fills).size();
                double expected = weight.width() * LineArt.length(path);
                assertTrue(area >= expected * 0.8 && area <= expected * 1.3,
                        at + " inks " + area + " pixels for a stroke of about " + Math.round(expected)
                                + ": the band is not the line's own width");
            }
        }
    }

    @Test
    @DisplayName("a pattern stays a pattern: caps are square, gaps are real, and dots do not touch")
    void patternsScaleWithTheWeight() {
        List<LineArt.Point> path = LineArt.path(DependencyStyle.Form.STRAIGHT,
                new LineArt.Point(0, 10), new LineArt.Point(120, 10));

        for (DependencyStyle.Weight weight : DependencyStyle.Weight.values()) {
            int w = weight.width();
            String at = " at " + weight;
            var columns = inkedColumns(LineArt.fills(path, weight, DependencyStyle.Dash.DASHED));

            // A dash's cap is square to the path: every column of a dash carries the whole width.
            for (int x : columns) {
                assertEquals(w, columnHeight(LineArt.fills(path, weight, DependencyStyle.Dash.DASHED), x),
                        "a dash's cap is not square" + at + " at x=" + x);
            }
            // And the gaps are at least as wide as the line, so a heavy dash still reads as dashes.
            for (int[] run : columnRuns(columns, 120)) {
                assertTrue(run[1] - run[0] + 1 >= w || run[0] == 0 || run[1] == 119,
                        "a dash or a gap narrower than the line itself" + at + ": " + run[0] + ".." + run[1]);
            }

            // A dotted line's beads do not touch at any weight, which is the fault the old fixed
            // four-pixel period had at six pixels wide: the beads merged into a solid line. A bead of the
            // *trunk* is one pixel of route and the whole weight tall -- the wide plus is the arrowhead's
            // bead, not this -- so the test reads the column heights and the gaps between them.
            var dots = inkedColumns(LineArt.fills(path, weight, DependencyStyle.Dash.DOTTED));
            var dotRuns = columnRuns(dots, 120);
            assertTrue(dotRuns.size() > 3, "a dotted line is a run of beads" + at + ": " + dotRuns.size());
            for (int[] run : dotRuns) {
                assertEquals(1, run[1] - run[0] + 1, "a bead is one route pixel long" + at + ": " + run[0]);
                assertEquals(w, columnHeight(LineArt.fills(path, weight, DependencyStyle.Dash.DOTTED),
                                run[0]),
                        "and as tall as the line is thick" + at + " at x=" + run[0]);
            }
            for (int i = 1; i < dotRuns.size(); i++) {
                assertTrue(dotRuns.get(i)[0] > dotRuns.get(i - 1)[1] + 1,
                        "two beads touch" + at + ": " + dotRuns.get(i - 1)[1] + " and " + dotRuns.get(i)[0]);
            }
            assertTrue(dotRuns.size() < 120 / 2, "and the line is not solid" + at);
        }
    }

    @Test
    @DisplayName("a head is as heavy as the line it caps, and stays attached to it")
    void headsFollowTheWeight() {
        for (DependencyStyle.ArrowHead head : List.of(DependencyStyle.ArrowHead.CHEVRON,
                DependencyStyle.ArrowHead.TRIANGLE, DependencyStyle.ArrowHead.DIAMOND,
                DependencyStyle.ArrowHead.DOT)) {
            for (DependencyStyle.Weight weight : DependencyStyle.Weight.values()) {
                String at = head + " at " + weight;
                List<LineArt.Point> path = LineArt.path(DependencyStyle.Form.STRAIGHT,
                        new LineArt.Point(16, 16), new LineArt.Point(216, 16));
                List<LineArt.Fill> trunk = LineArt.fills(path, weight, DependencyStyle.Dash.SOLID);
                List<LineArt.Fill> marks = LineArt.arrows(path, head, DependencyStyle.ArrowPlace.TARGET,
                        32, 0, 0, weight);

                assertFalse(marks.isEmpty(), at + " drew no head at all");
                assertTrue(spansRows(marks) >= weight.width(),
                        at + " is thinner than the line it caps: " + spansRows(marks));
                assertTrue(adjacent(ink(trunk), ink(marks)),
                        at + " floats off its own line, which is the unexpected gap this pins");
            }
        }
    }

    @Test
    @DisplayName("a weighted band is one piece on a straight run, with no pixel inked twice")
    void aStraightBandIsTiled() {
        // Where the geometry is exact -- an axis-aligned run -- the band must be a clean tiling: no
        // overlap at all, and one tone per pixel. On a diagonal the band is a staircase and its own
        // shapes overlap by a pixel here and there, which is why this is asserted where it is meaningful
        // rather than everywhere; the coverage and area assertions above cover the rest.
        for (DependencyStyle.Weight weight : DependencyStyle.Weight.values()) {
            List<LineArt.Point> path = LineArt.path(DependencyStyle.Form.STRAIGHT,
                    new LineArt.Point(0, 10), new LineArt.Point(50, 10));

            List<LineArt.Fill> fills = LineArt.fills(path, weight, DependencyStyle.Dash.SOLID);
            int expected = weight == DependencyStyle.Weight.CONDUIT ? weight.width() : 1;
            assertEquals(expected, fills.size(),
                    "an axis run is one rectangle per tone band: " + weight + " " + fills);
            Set<Long> seen = new HashSet<>();
            for (LineArt.Fill fill : fills) {
                for (int y = fill.y1(); y < fill.y2(); y++) {
                    for (int x = fill.x1(); x < fill.x2(); x++) {
                        assertTrue(seen.add(key(x, y)), "the band inks " + x + "," + y + " twice: " + fills);
                    }
                }
            }
            if (weight == DependencyStyle.Weight.CONDUIT) {
                assertEquals(List.of(LineArt.Tone.EDGE, LineArt.Tone.MAIN, LineArt.Tone.CORE,
                                LineArt.Tone.CORE, LineArt.Tone.MAIN, LineArt.Tone.EDGE),
                        fills.stream().map(LineArt.Fill::tone).toList(),
                        "a conduit is a border, a body and a core, in that order");
            }
        }
    }

    // ------------------------------------------------------------------
    // The raster helpers the stroke tests read
    // ------------------------------------------------------------------

    /** Every pixel a fill list inks, as packed coordinates. */
    private static Set<Long> ink(List<LineArt.Fill> fills) {
        Set<Long> out = new HashSet<>();
        for (LineArt.Fill fill : fills) {
            for (int y = fill.y1(); y < fill.y2(); y++) {
                for (int x = fill.x1(); x < fill.x2(); x++) {
                    out.add(key(x, y));
                }
            }
        }
        return out;
    }

    private static long key(int x, int y) {
        return ((long) x << 32) | (y & 0xFFFFFFFFL);
    }

    private static int keyX(long key) {
        return (int) (key >> 32);
    }

    private static int keyY(long key) {
        return (int) key;
    }

    /**
     * The whole stroke, stated two ways that a notch cannot pass.
     *
     * <p><b>Nothing inside the band is missing.</b> Every pixel whose perpendicular distance to the route
     * is at most half the width — less half a pixel, which is the rasterisation's own rim and where a
     * staircase's single-pixel nicks live — must be inked. The care over the rim is deliberate rather
     * than convenient: a diagonal edge is a staircase in any rasteriser, so demanding the exact rim would
     * be demanding anti-aliasing. The half-pixel allowance still catches what was reported, which was a
     * block a whole width across missing from the outside of a corner.
     *
     * <p><b>And the ink is one piece.</b> Eight-connected throughout, from the first walk point's own ink
     * to the last. Eight rather than four because a hairline diagonal is a staircase of diagonally
     * adjacent pixels — that is what a one-pixel line at 45 degrees *is* in any rasteriser — so a
     * four-connected requirement would fail on a drawing that is entirely correct. What this still
     * catches is a band drawn in two separate pieces, which a corner drawn by two one-sided shifts is
     * once the arms are thick enough to overshoot each other.
     */
    private static void assertWhole(List<LineArt.Fill> fills, List<LineArt.Point> path,
                                    DependencyStyle.Weight weight, String at) {
        Set<Long> ink = ink(fills);
        assertFalse(ink.isEmpty(), at + " drew nothing at all");

        double inside = (weight.width() - 1) / 2.0 - 0.5;
        if (inside > 0) {
            Set<Long> candidates = new HashSet<>();
            int reach = weight.width() + 1;
            for (LineArt.Point point : LineArt.walk(path)) {
                for (int dx = -reach; dx <= reach; dx++) {
                    for (int dy = -reach; dy <= reach; dy++) {
                        candidates.add(key(point.x() + dx, point.y() + dy));
                    }
                }
            }
            for (long pixel : candidates) {
                int x = keyX(pixel);
                int y = keyY(pixel);
                // Measured from the pixel's **centre**, because that is what the stroke's geometry is
                // built on: a fill of (x..x+1, y..y+1) is the pixel whose centre is (x+.5, y+.5), and
                // measuring from its corner would demand ink half a pixel outside the band everywhere.
                if (insideBand(path, x + 0.5, y + 0.5, inside)) {
                    assertTrue(ink.contains(pixel),
                            at + " leaves " + x + "," + y + " empty, and it is inside the band ("
                                    + LineArt.distance(path, x + 0.5, y + 0.5) + " from the route of a "
                                    + weight.width() + "-pixel stroke)");
                }
            }
        }

        java.util.Deque<Long> frontier = new java.util.ArrayDeque<>();
        Set<Long> seen = new HashSet<>();
        frontier.add(ink.iterator().next());
        while (!frontier.isEmpty()) {
            long pixel = frontier.removeFirst();
            if (!seen.add(pixel)) {
                continue;
            }
            int x = keyX(pixel);
            int y = keyY(pixel);
            for (int dx = -1; dx <= 1; dx++) {
                for (int dy = -1; dy <= 1; dy++) {
                    long next = key(x + dx, y + dy);
                    if (ink.contains(next)) {
                        frontier.add(next);
                    }
                }
            }
        }
        assertEquals(ink.size(), seen.size(),
                at + " draws its band in more than one piece: " + (ink.size() - seen.size())
                        + " pixel(s) are cut off from the rest");
    }

    /** The columns a fill list inks, ascending and distinct. */
    private static List<Integer> inkedColumns(List<LineArt.Fill> fills) {
        java.util.TreeSet<Integer> out = new java.util.TreeSet<>();
        for (long pixel : ink(fills)) {
            out.add(keyX(pixel));
        }
        return List.copyOf(out);
    }

    /**
     * Whether a pixel is inside the band's body: within {@code half} of one of the route's own segments,
     * and past neither of that segment's ends.
     *
     * <p>The ends matter, and this is not pedantry: {@link LineArt#distance} measures to a segment, so a
     * point just *beyond* the route's first pixel is one diagonal away from it — and the stroke's cap is
     * flat there, not a disc. Asking for ink outside the cap would demand a round cap nobody specified.
     */
    private static boolean insideBand(List<LineArt.Point> path, double x, double y, double half) {
        for (int i = 0; i < path.size() - 1; i++) {
            LineArt.Point a = path.get(i);
            LineArt.Point b = path.get(i + 1);
            double dx = b.x() - a.x();
            double dy = b.y() - a.y();
            double length = Math.hypot(dx, dy);
            if (length < 0.001) {
                continue;
            }
            double along = ((x - a.x()) * dx + (y - a.y()) * dy) / length;
            if (along < 0 || along > length) {
                continue;
            }
            double across = Math.abs((x - a.x()) * (-dy / length) + (y - a.y()) * (dx / length));
            if (across <= half) {
                return true;
            }
        }
        return false;
    }

    /** How many pixels of one column are inked. */
    private static int columnHeight(List<LineArt.Fill> fills, int x) {
        int height = 0;
        for (long pixel : ink(fills)) {
            if (keyX(pixel) == x) {
                height++;
            }
        }
        return height;
    }

    /** The runs of consecutive columns in a set of columns, as inclusive pairs. */
    private static List<int[]> columnRuns(List<Integer> columns, int limit) {
        List<int[]> out = new java.util.ArrayList<>();
        int start = -1;
        int previous = -2;
        for (int x : columns) {
            if (start < 0) {
                start = x;
            }
            else if (x != previous + 1) {
                out.add(new int[] { start, previous });
                start = x;
            }
            previous = x;
        }
        if (start >= 0) {
            out.add(new int[] { start, previous });
        }
        return out;
    }

    /** How many rows a fill list's ink spans: a head is at least as tall as the line is wide. */
    private static int spansRows(List<LineArt.Fill> fills) {
        int min = Integer.MAX_VALUE;
        int max = Integer.MIN_VALUE;
        for (LineArt.Fill fill : fills) {
            min = Math.min(min, fill.y1());
            max = Math.max(max, fill.y2());
        }
        return max - min;
    }

    // ------------------------------------------------------------------
    // A canvas line: its own width, and its own two ends
    // ------------------------------------------------------------------

    @Test
    @DisplayName("a line at an arbitrary width is that many rows, and one width is not another's answer")
    void aLineCanBeAnyWidth() {
        // The width an author types rather than the four words a dependency line's weight is. Two things
        // are worth asserting and they fail differently: that the number reaches the geometry, and that the
        // memo cannot serve one width's rectangles for another's -- which is what the key would do if the
        // width were left out of it, and which no picture would ever show, because the first caller's
        // width would simply be drawn for every line after it.
        List<LineArt.Point> path = LineArt.path(DependencyStyle.Form.STRAIGHT,
                new LineArt.Point(0, 20), new LineArt.Point(60, 20));

        for (int width : new int[] {1, 2, 3, 5, 6, 11}) {
            List<LineArt.Fill> fills = LineArt.fillsAtWidth(path, width, DependencyStyle.Dash.SOLID);
            assertEquals(width, spansRows(fills), "a " + width + "-pixel line is " + width + " rows: " + fills);
        }

        // The falsifier for the cache key, and it has to be in this order: ask for a thin line first, so a
        // key that ignored the width has the wrong answer already remembered when the wide one arrives.
        LineArt.fillsAtWidth(path, 1, DependencyStyle.Dash.SOLID);
        assertEquals(7, spansRows(LineArt.fillsAtWidth(path, 7, DependencyStyle.Dash.SOLID)),
                "the width is part of what a set of rectangles is a function of");
        assertEquals(1, spansRows(LineArt.fillsAtWidth(path, 1, DependencyStyle.Dash.SOLID)),
                "and the thin answer is still thin, in the other order");

        // A hairline through this entry point is the same picture the weight-based one draws, which is what
        // makes the two entry points one implementation rather than two.
        assertEquals(LineArt.fills(path, DependencyStyle.Weight.THIN, DependencyStyle.Dash.SOLID),
                LineArt.fillsAtWidth(path, 1, DependencyStyle.Dash.SOLID));

        // And a width below one is read as one rather than as nothing: a zero-pixel line is a line nobody
        // can see, and the codec clamps the field, so this is the second line of defence rather than the
        // first -- but a band of width zero would produce no fills at all and read as a broken element.
        assertEquals(1, spansRows(LineArt.fillsAtWidth(path, 0, DependencyStyle.Dash.SOLID)));
    }

    @Test
    @DisplayName("a canvas line's heads are named by end, including the start only")
    void aLineCanPointAtEitherEnd() {
        // The placement vocabulary a dependency line uses has no word for "the start only", and adding one
        // would have had to be documented in three schemas. So a canvas line names its two ends, and the
        // whole point of the two booleans is that both orders are expressible.
        List<LineArt.Point> path = LineArt.path(DependencyStyle.Form.STRAIGHT,
                new LineArt.Point(0, 0), new LineArt.Point(120, 120));
        DependencyStyle.ArrowHead chevron = DependencyStyle.ArrowHead.CHEVRON;

        List<LineArt.Fill> none = LineArt.arrows(path, chevron, false, false, 0, 0, 1);
        List<LineArt.Fill> atEnd = LineArt.arrows(path, chevron, false, true, 0, 0, 1);
        List<LineArt.Fill> atStart = LineArt.arrows(path, chevron, true, false, 0, 0, 1);
        List<LineArt.Fill> both = LineArt.arrows(path, chevron, true, true, 0, 0, 1);

        assertTrue(none.isEmpty(), "neither end is no head at all");
        assertFalse(atEnd.isEmpty(), "an end head is drawn");
        assertFalse(atStart.isEmpty(), "and a start head is the case the placement vocabulary cannot say");
        assertNotEquals(atStart, atEnd, "the two ends are two different pictures");
        assertEquals(atEnd.size() + atStart.size(), both.size(), "and both ends are both of them");

        // Which end is which, by where the ink lands: the path runs (0,0) to (120,120), so a start head is
        // near the origin and an end head is near the far corner.
        assertTrue(furthestFrom(atStart, 0, 0) < 40, "the start head is at the start: " + atStart);
        assertTrue(furthestFrom(atEnd, 0, 0) > 100, "and the end head is at the end: " + atEnd);

        // A placement of TARGET is the arrival-only picture, so the two entry points share the memo rather
        // than holding two copies of one answer. Asserted as equality of the lists, which is what a shared
        // entry returns.
        assertEquals(atEnd, LineArt.arrows(path, chevron, DependencyStyle.ArrowPlace.TARGET, 0, 0, 0,
                DependencyStyle.Weight.THIN));
    }

    @Test
    @DisplayName("a head is built for the trunk it caps, at a canvas line's own width")
    void aHeadGrowsWithTheLinesWidth() {
        // The head's length, half-width and standoff all derive from the trunk, and a canvas line's trunk
        // is a number rather than a weight -- so a six-pixel rule with a hairline's arrow is exactly the
        // "arrow that belongs to another line" the weight axis was introduced to fix, one level down.
        List<LineArt.Point> path = LineArt.path(DependencyStyle.Form.STRAIGHT,
                new LineArt.Point(0, 0), new LineArt.Point(120, 120));

        List<LineArt.Fill> thin = LineArt.arrows(path, DependencyStyle.ArrowHead.TRIANGLE, false, true,
                0, 0, 1);
        List<LineArt.Fill> thick = LineArt.arrows(path, DependencyStyle.ArrowHead.TRIANGLE, false, true,
                0, 0, 6);

        assertTrue(thick.size() > thin.size(), "a wider trunk has a bigger head: " + thin.size() + " then "
                + thick.size());
        assertTrue(spanFrom(thick, 120, 120) > spanFrom(thin, 120, 120),
                "and the glyph covers more of the line, because both its length and its standoff are the"
                        + " trunk's: " + spanFrom(thin, 120, 120) + " then " + spanFrom(thick, 120, 120));
    }

    /** How far the furthest pixel of a fill list is from a point, for asking which end ink landed on. */
    private static double furthestFrom(List<LineArt.Fill> fills, int x, int y) {
        double furthest = 0;
        for (LineArt.Fill fill : fills) {
            furthest = Math.max(furthest, Math.hypot(fill.x1() - x, fill.y1() - y));
            furthest = Math.max(furthest, Math.hypot(fill.x2() - x, fill.y2() - y));
        }
        return furthest;
    }

    /**
     * How much of a line's length a fill list's ink covers, measured from a point.
     *
     * <p>A span rather than a distance, and the difference is what the assertion needs: a bigger head is
     * both placed further back and longer, so its ink covers more ground — while "the furthest pixel is
     * further away" is true of it and also of a head that merely moved, which is not the claim.
     */
    private static double spanFrom(List<LineArt.Fill> fills, int x, int y) {
        double min = Double.MAX_VALUE;
        double max = 0;
        for (LineArt.Fill fill : fills) {
            for (int[] corner : new int[][] {{fill.x1(), fill.y1()}, {fill.x2(), fill.y2()}}) {
                double distance = Math.hypot(corner[0] - x, corner[1] - y);
                min = Math.min(min, distance);
                max = Math.max(max, distance);
            }
        }
        return max - min;
    }

    /** Whether two ink sets touch, eight-connected: a head that floats off its line fails this. */
    private static boolean adjacent(Set<Long> first, Set<Long> second) {
        for (long pixel : second) {
            int x = keyX(pixel);
            int y = keyY(pixel);
            for (int dx = -1; dx <= 1; dx++) {
                for (int dy = -1; dy <= 1; dy++) {
                    if (first.contains(key(x + dx, y + dy))) {
                        return true;
                    }
                }
            }
        }
        return false;
    }
}
