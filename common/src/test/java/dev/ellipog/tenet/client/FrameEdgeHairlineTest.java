package dev.ellipog.tenet.client;

import dev.ellipog.tenet.client.dev.LineArt;
import dev.ellipog.tenet.client.render.RecordingRenderer;
import dev.ellipog.tenet.quest.DependencyStyle;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.EnumMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A dependency line sent as a route for the renderer's one-pixel line: only when it is fewer calls, and always
 * the same pixels its pieces would have drawn.
 */
@DisplayName("A frame edge's hairline")
class FrameEdgeHairlineTest {

    private static DependencyStyle style(String json) {
        return DependencyStyle.from(com.google.gson.JsonParser.parseString(json));
    }

    private static LineArt.EdgeGeometry geometry(DependencyStyle style, int dx, int dy) {
        List<LineArt.Point> path = BookCanvas.stylePath(style, new LineArt.Point(100, 200),
                new LineArt.Point(100 + dx, 200 + dy));
        return LineArt.geometry(path, style, LineArt.ArrowSpec.of(style));
    }

    @Test
    @DisplayName("draws exactly the pixels its pieces draw, each once, for every form it is used for")
    void theSamePixelsAsThePieces() {
        Map<DependencyStyle.Form, Integer> used = new EnumMap<>(DependencyStyle.Form.class);
        for (DependencyStyle.Form form : DependencyStyle.Form.values()) {
            DependencyStyle style = style("{\"form\":\"" + form.name().toLowerCase(java.util.Locale.ROOT)
                    + "\",\"weight\":\"thin\",\"dash\":\"solid\"}");
            for (int dx = -77; dx <= 77; dx += 7) {
                for (int dy = -81; dy <= 81; dy += 9) {
                    LineArt.EdgeGeometry geometry = geometry(style, dx, dy);
                    int[] route = BookCanvas.FrameEdge.hairlineOf(geometry, style);
                    if (route == null) {
                        continue;
                    }
                    used.merge(form, 1, Integer::sum);
                    assertTrue(route.length / 2 - 1 < geometry.fills().size(), "only when it is fewer calls");

                    Set<Long> pieces = new HashSet<>();
                    for (LineArt.Fill fill : geometry.fills()) {
                        cover(fill.x1(), fill.y1(), fill.x2(), fill.y2(), pieces, null);
                    }
                    RecordingRenderer r = RecordingRenderer.create();
                    r.hairline(0, 0, route, 0xFF336699);
                    Set<Long> drawn = new HashSet<>();
                    for (RecordingRenderer.Call fill : r.fills()) {
                        cover(fill.x(), fill.y(), fill.x2(), fill.y2(), drawn, form + " " + dx + "," + dy);
                    }
                    assertEquals(pieces, drawn, form + " " + dx + "," + dy);
                }
            }
        }
        assertTrue(used.getOrDefault(DependencyStyle.Form.STRAIGHT, 0) > 300, used.toString());
        assertTrue(used.getOrDefault(DependencyStyle.Form.CHAMFERED, 0) > 100, used.toString());
        // A route along the axes is one piece per segment already, so there is nothing to win.
        assertEquals(0, used.getOrDefault(DependencyStyle.Form.ORTHOGONAL, 0), used.toString());
    }

    @Test
    @DisplayName("sends a straight line as its two ends")
    void aStraightLineIsItsEnds() {
        DependencyStyle style = style("{\"form\":\"straight\"}");
        int[] route = BookCanvas.FrameEdge.hairlineOf(geometry(style, 37, -11), style);
        assertNotNull(route);
        assertArrayEquals(new int[] {0, 0, 37, -11}, route);
    }

    @Test
    @DisplayName("is not used for a heavier or a broken line")
    void onlyThinSolidLines() {
        for (String json : List.of("{\"form\":\"straight\",\"weight\":\"thick\"}",
                "{\"form\":\"straight\",\"dash\":\"dashed\"}",
                "{\"form\":\"straight\",\"dash\":\"double\"}",
                "{\"form\":\"straight\",\"weight\":\"conduit\"}")) {
            DependencyStyle style = style(json);
            assertNull(BookCanvas.FrameEdge.hairlineOf(geometry(style, 40, 25), style), json);
        }
    }

    @Test
    @DisplayName("is not used for a route that passes over its own ink")
    void notForARouteThatRevisitsAPixel() {
        DependencyStyle style = style("{\"form\":\"straight\"}");
        List<LineArt.Point> path = List.of(new LineArt.Point(0, 0), new LineArt.Point(20, 9),
                new LineArt.Point(0, 0), new LineArt.Point(-20, 9));
        LineArt.EdgeGeometry geometry = LineArt.geometry(path, style, LineArt.ArrowSpec.of(style));
        assertNull(BookCanvas.FrameEdge.hairlineOf(geometry, style));
    }

    /** Adds a rectangle's pixels to the set; with a label, fails if one is already there. */
    private static void cover(int x1, int y1, int x2, int y2, Set<Long> into, String label) {
        for (int x = Math.min(x1, x2); x < Math.max(x1, x2); x++) {
            for (int y = Math.min(y1, y2); y < Math.max(y1, y2); y++) {
                boolean fresh = into.add(((long) x << 32) ^ (y & 0xFFFFFFFFL));
                if (label != null) {
                    assertTrue(fresh, "pixel " + x + "," + y + " drawn twice in " + label);
                }
            }
        }
    }
}
