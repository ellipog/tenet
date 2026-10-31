package dev.ellipog.tenet.client;

import java.util.Arrays;

/**
 * Whether any two of a set of boxes share a pixel.
 *
 * <p>The question the canvas asks before it draws its nodes' icons in one pass: holding the icons back
 * until every shape is drawn moves each icon over the shapes drawn after it, which is only the same picture
 * when nothing drawn after an icon covers it. Nodes that do not overlap give that for free, so the stamp
 * asks this once over every node and the frame reads the answer. See {@code GuiRenderer.iconsLast}.
 *
 * <p>A sweep along x rather than every pair: a chapter of a thousand nodes is half a million pairs, and
 * the sweep compares a box only with the boxes that start before it ends.
 */
final class CanvasOverlap {

    private CanvasOverlap() {
    }

    /**
     * Whether any two of the first {@code count} boxes overlap. Boxes are half-open, so two that only touch
     * along an edge do not; an empty box overlaps nothing.
     */
    static boolean any(int[] lefts, int[] tops, int[] rights, int[] bottoms, int count) {
        long[] order = new long[count];
        int kept = 0;
        for (int i = 0; i < count; i++) {
            if (rights[i] > lefts[i] && bottoms[i] > tops[i]) {
                // The left edge in the high half and the index in the low half, so one sort orders both.
                order[kept++] = ((long) lefts[i] << 32) | (i & 0xFFFFFFFFL);
            }
        }
        Arrays.sort(order, 0, kept);
        for (int a = 0; a < kept; a++) {
            int i = (int) order[a];
            for (int b = a + 1; b < kept; b++) {
                int j = (int) order[b];
                if (lefts[j] >= rights[i]) {
                    break;
                }
                if (tops[j] < bottoms[i] && bottoms[j] > tops[i]) {
                    return true;
                }
            }
        }
        return false;
    }
}
