package dev.ellipog.tenet.client;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The direction pulse: where its packets are, which way they move, and when there are none.
 *
 * <h2>What matters here that a screenshot cannot show</h2>
 *
 * <p>That the packets move <i>forward</i> — a flow that ran backwards would read as requirements
 * flowing the wrong way — that the loop wraps without a jump, and that short lines and motionless
 * moments show nothing rather than a strobe. Positions are asserted, not pictures.
 */
@DisplayName("the dependency flow pulse")
class EdgePulseTest {

    @Test
    @DisplayName("routes too short for a dot show nothing")
    void tinyRoutesShowNothing() {
        assertTrue(EdgePulse.centers(0, 1_000L, 7).isEmpty(), "no route, no packets");
        assertTrue(EdgePulse.centers(-40, 1_000L, 7).isEmpty(), "and no negative one either");
        assertTrue(
                EdgePulse.centers(EdgePulse.ARROW_KEEP + EdgePulse.MIN_TRAVEL - 1, 1_000L, 7).isEmpty(),
                "shorter than the minimum travelling end is all arrival");
    }

    @Test
    @DisplayName("short routes carry one short packet rather than nothing")
    void shortRoutesCarryOne() {
        // A zoomed-out stub: 16 pixels of route, 6 of travel. The packet shrinks to the road
        // rather than refusing it, so pulling back keeps the flow instead of losing it.
        double length = 16;
        double travel = length - EdgePulse.ARROW_KEEP;
        assertEquals(6, EdgePulse.packetLength(length), 0.001, "a 6-pixel road carries a 6-pixel comet");
        assertEquals(8, EdgePulse.packetLength(300), 0.001, "and a long road keeps the full one");
        boolean carried = false;
        double cycle = travel + EdgePulse.leadFor(travel);
        long periodMs = Math.round(cycle / EdgePulse.SPEED_PX_PER_SECOND * 1000.0);
        for (long now = 0; now < periodMs; now += 5) {
            for (double at : EdgePulse.centers(length, now, 9)) {
                assertTrue(at >= 0 && at <= travel, "even a short packet stays on its road: " + at);
                carried = true;
            }
        }
        assertTrue(carried, "a 16-pixel line should carry its packet somewhere in its loop");
    }

    @Test
    @DisplayName("the lead shrinks with short routes but never below its floor")
    void leadScalesDown() {
        assertEquals(EdgePulse.LEAD, EdgePulse.leadFor(500), 0.001, "room to spare, the full quiet gap");
        assertEquals(40, EdgePulse.leadFor(40), 0.001, "a 40-pixel road waits 40");
        assertEquals(EdgePulse.MIN_LEAD, EdgePulse.leadFor(5), 0.001,
                "and a loop with no gap at all would read as a strobe");
    }

    @Test
    @DisplayName("a packet brightens towards its head and never fully dies at the tail")
    void packetAlphaRisesToTheHead() {
        assertEquals(0.15F, EdgePulse.packetAlpha(1F, 0), 0.001F, "the tail is quiet, not gone");
        assertEquals(1F, EdgePulse.packetAlpha(1F, 1), 0.001F, "the head carries the full glow");
        assertTrue(EdgePulse.packetAlpha(1F, 0.5) > EdgePulse.packetAlpha(1F, 0.25)
                && EdgePulse.packetAlpha(1F, 1) > EdgePulse.packetAlpha(1F, 0.5),
                "monotonic, so the comet reads as one traveller");
        assertEquals(0.5F, EdgePulse.packetAlpha(0.5F, 1), 0.001F, "easing with the hover, like the ink");
        assertEquals(EdgePulse.packetAlpha(1F, 0), EdgePulse.packetAlpha(1F, -3), 0.001F,
                "past either end clamps rather than inventing brightness");
        assertEquals(EdgePulse.packetAlpha(1F, 1), EdgePulse.packetAlpha(1F, 3), 0.001F);
    }

    @Test
    @DisplayName("packets stay on the route, clear of the arrowhead")
    void packetsStayOnTheRoute() {
        double length = 300;
        for (long now = 0; now < 20_000; now += 137) {
            for (double at : EdgePulse.centers(length, now, 42)) {
                assertTrue(at >= 0 && at <= length - EdgePulse.ARROW_KEEP,
                        "a packet past its ends is a strobe on a node: " + at);
            }
        }
    }

    @Test
    @DisplayName("packets travel from the prerequisite to the dependent")
    void packetsTravelForward() {
        // A short line carries one packet at a time, so its center must grow with the clock until
        // the loop wraps: sampled finely enough that no step crosses the wrap.
        double length = 120;
        double previous = -1;
        for (long now = 0; now < 2_000; now += 25) {
            List<Double> centers = EdgePulse.centers(length, now, 0);
            if (centers.size() == 1 && previous >= 0 && centers.get(0) > 1) {
                assertTrue(centers.get(0) >= previous,
                        "a packet that went backwards flows the wrong way: " + previous + " -> "
                                + centers.get(0));
            }
            if (centers.size() == 1) {
                previous = centers.get(0);
            }
        }
        assertTrue(previous >= 0, "a 120-pixel line should carry a packet most of the loop");
    }

    @Test
    @DisplayName("the loop wraps back to its start without a jump")
    void theLoopWraps() {
        double length = 250;
        double travel = length - EdgePulse.ARROW_KEEP;
        double cycle = travel + EdgePulse.LEAD;
        long periodMs = Math.round(cycle / EdgePulse.SPEED_PX_PER_SECOND * 1000.0);
        // A moment that actually carries packets, then the same moment one loop later.
        long start = -1;
        for (long t = 0; t < periodMs; t += 50) {
            if (!EdgePulse.centers(length, t, 11).isEmpty()) {
                start = t;
                break;
            }
        }
        assertTrue(start >= 0, "a 250-pixel line should carry packets somewhere in its loop");
        // Same seed, one period apart: the same packets, to sub-pixel agreement.
        List<Double> first = EdgePulse.centers(length, start, 11);
        List<Double> wrapped = EdgePulse.centers(length, start + periodMs, 11);
        assertEquals(first.size(), wrapped.size(), "the loop carries its packets around");
        for (int i = 0; i < first.size(); i++) {
            assertEquals(first.get(i), wrapped.get(i), 0.5,
                    "packet " + i + " jumped at the wrap");
        }
    }

    @Test
    @DisplayName("long lines carry several packets, and the seed spreads them across edges")
    void longLinesCarrySeveral() {
        List<Double> many = EdgePulse.centers(900, 5_000L, 3);
        assertTrue(many.size() >= 2, "a 900-pixel line with 200-pixel spacing carries more than one: "
                + many.size());
        HashSet<Double> phases = new HashSet<>();
        for (int seed = 0; seed < 8; seed++) {
            phases.addAll(EdgePulse.centers(300, 5_000L, seed));
        }
        assertTrue(phases.size() > 8, "eight edges at one moment should not march in lockstep");
    }

    @Test
    @DisplayName("one moment is one answer, whatever the clock's magnitude")
    void wallClockMagnitudesDoNotQuantise() {
        double length = 300;
        // A moment far into wall-clock time that carries a packet, then the millisecond after it:
        // if the trillion-magnitude clock ever reached the floating point, the march would step.
        long base = 1_743_000_000_000L;
        long start = -1;
        for (long t = base; t < base + 5_000; t += 37) {
            if (!EdgePulse.centers(length, t, 5).isEmpty()
                    && !EdgePulse.centers(length, t + 1, 5).isEmpty()) {
                start = t;
                break;
            }
        }
        assertTrue(start >= 0, "a 300-pixel line should carry packets most of its loop");
        double a = EdgePulse.centers(length, start, 5).stream().mapToDouble(d -> d).min()
                .orElseThrow();
        double b = EdgePulse.centers(length, start + 1, 5).stream().mapToDouble(d -> d).min()
                .orElseThrow();
        assertTrue(Math.abs(b - a) < 1.0, "one millisecond moves a packet a tenth of a pixel, not a step: "
                + a + " -> " + b);
    }
}
