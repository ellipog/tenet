package dev.ellipog.tenet.client;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The sweep that decides whether the canvas may draw its icons in one pass. */
@DisplayName("Canvas node overlap")
class CanvasOverlapTest {

    @Test
    @DisplayName("agrees with checking every pair, over random boxes on and off a grid")
    void agreesWithEveryPair() {
        Random random = new Random(26_3);
        for (int round = 0; round < 4000; round++) {
            int count = random.nextInt(12);
            int[] l = new int[count];
            int[] t = new int[count];
            int[] r = new int[count];
            int[] b = new int[count];
            int spread = 1 + random.nextInt(200);
            for (int i = 0; i < count; i++) {
                l[i] = random.nextInt(spread) - spread / 2;
                t[i] = random.nextInt(spread) - spread / 2;
                r[i] = l[i] + random.nextInt(40);
                b[i] = t[i] + random.nextInt(40);
            }
            boolean pairs = false;
            for (int i = 0; i < count && !pairs; i++) {
                for (int j = i + 1; j < count; j++) {
                    boolean empty = r[i] <= l[i] || b[i] <= t[i] || r[j] <= l[j] || b[j] <= t[j];
                    if (!empty && l[i] < r[j] && l[j] < r[i] && t[i] < b[j] && t[j] < b[i]) {
                        pairs = true;
                        break;
                    }
                }
            }
            assertEquals(pairs, CanvasOverlap.any(l, t, r, b, count), "round " + round);
        }
    }

    @Test
    @DisplayName("boxes that only touch along an edge do not overlap, and a shared pixel does")
    void edgesAreHalfOpen() {
        assertFalse(CanvasOverlap.any(new int[] {0, 10}, new int[] {0, 0}, new int[] {10, 20}, new int[] {10, 10}, 2));
        assertFalse(CanvasOverlap.any(new int[] {0, 0}, new int[] {0, 10}, new int[] {10, 10}, new int[] {10, 20}, 2));
        assertTrue(CanvasOverlap.any(new int[] {0, 9}, new int[] {0, 9}, new int[] {10, 20}, new int[] {10, 20}, 2));
    }
}
