package dev.ellipog.tenet.client;

import java.util.ArrayList;
import java.util.List;

/**
 * Where the bright packets are on a dependency line right now: travelling from the prerequisite to
 * the dependent, looping with a quiet gap.
 *
 * <h2>Why packets rather than marching dashes</h2>
 *
 * <p>A marching dash would re-phase the line's own pattern every frame, which means rebuilding the
 * edge's fills sixty times a second — the exact cost {@code FrameEdge} exists to avoid. A packet is
 * an overlay: the stamped geometry is re-issued untouched and a few bright dots ride above it, only
 * on the edges that glow, so a chapter with nine hundred quiet lines pays nothing for the three that
 * are lit.
 *
 * <h2>Why the direction is the dependency's</h2>
 *
 * <p>Because the question a hovered node asks is "what flows into and out of here": a packet that ran
 * dependent-to-prerequisite would read as the requirement flowing backwards. The route's own order is
 * prerequisite first — {@code FrameEdge} builds from the dependency to the quest — so distance zero
 * is the source and growing distance is the flow.
 *
 * <h2>Time is passed in, never read</h2>
 *
 * <p>The screen hands its frame's clock, like every other animation here: a test advances time by
 * passing a larger number, so a full loop is asserted in microseconds. Long wall-clock times never
 * reach floating point — the position is derived from the time <i>within</i> one loop, which is a
 * few thousand milliseconds at most.
 */
public final class EdgePulse {

    /**
     * How fast a packet travels, in pixels per second.
     *
     * <p>Unhurried on purpose: fast enough to read as motion on a glance, slow enough that two
     * packets on adjacent lines do not shimmer. A dependency line is read while the pointer rests,
     * not chased.
     */
    public static final double SPEED_PX_PER_SECOND = 90.0;

    /** One packet's bright length along the route. Short: a comet, not a highlighter. */
    public static final double PACKET_LEN = 8.0;

    /** How far apart packets space on long lines. Several short lines show one traveller each. */
    public static final double SPACING = 200.0;

    /**
     * The quiet lead around each loop: the packet spends this long off the route before arriving and
     * after leaving, so it fades in from the source rather than blinking on mid-line.
     */
    public static final double LEAD = 70.0;

    /**
     * How much of the route's end the packets leave alone: the arrowhead's.
     *
     * <p>A packet drawn over the head would strobe the one part of the line that already says which
     * way it points. The head keeps its own ink; the flow stops short of it.
     */
    public static final double ARROW_KEEP = 10.0;

    private EdgePulse() {
    }

    /**
     * Packet centers, in pixels from the route's start, at this moment.
     *
     * <p>Empty for a route too short to carry one: shorter than a packet plus the arrowhead's keep
     * is a line whose whole length is arrival, and a packet on it would sit on the head. Otherwise
     * one packet per spacing that fits, each placed along the loop — the seed spreads loops across
     * edges so neighbouring lines do not march in lockstep.
     *
     * @param length    the route's length, in pixels
     * @param nowMillis the frame's clock; only the time within one loop reaches floating point
     * @param seed      per-edge variety, e.g. the edge's ids hashed
     * @return centers from the route's start (0) to its travelling end ({@code length - ARROW_KEEP})
     */
    public static List<Double> centers(double length, long nowMillis, int seed) {
        double travel = length - ARROW_KEEP;
        if (!(travel >= PACKET_LEN)) {
            return List.of();
        }
        double cycle = travel + LEAD;
        // The loop's own period, so the wrap is exact: deriving the distance from the time within
        // one loop keeps wall-clock magnitudes (a trillion milliseconds and climbing) out of the
        // floating point, where they would quantise the march into visible steps.
        long periodMs = Math.max(1L, Math.round(cycle / SPEED_PX_PER_SECOND * 1000.0));
        long inLoop = Math.floorMod(nowMillis, periodMs);
        double phase = Math.floorMod(seed, 1000) / 1000.0 * cycle;
        double head = (inLoop / 1000.0 * SPEED_PX_PER_SECOND + phase) % cycle;
        List<Double> out = new ArrayList<>();
        // The head plus its trailers, each wrapped into the loop and kept only while on the route:
        // the lead on either side is travelled off-route rather than drawn. One packet per spacing
        // that fits the loop — a short loop carries the head alone rather than aliasing trailers
        // into its window.
        int packets = (int) (cycle / SPACING) + 1;
        for (int k = 0; k < packets; k++) {
            double looped = (head - k * SPACING) % cycle;
            if (looped < 0) {
                looped += cycle;
            }
            double at = looped - LEAD / 2;
            if (at >= 0 && at <= travel) {
                out.add(at);
            }
        }
        return List.copyOf(out);
    }
}
