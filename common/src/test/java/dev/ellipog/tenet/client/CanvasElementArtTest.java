package dev.ellipog.tenet.client;

import com.google.gson.JsonParser;

import dev.ellipog.armature.client.render.GuiRenderer;
import dev.ellipog.armature.client.ui.kit.Viewport;
import dev.ellipog.tenet.client.render.RecordingRenderer;
import dev.ellipog.tenet.progress.QuestState;
import dev.ellipog.tenet.quest.CanvasElement;
import dev.ellipog.tenet.quest.QuestText;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The four kinds of canvas element, as they are drawn.
 *
 * <h2>What a recording renderer can see that a screenshot cannot</h2>
 *
 * <p>Each of these is a claim about <i>which call</i> was made rather than about a picture, and every one of
 * them is a place where two plausible implementations look identical on screen: a box's two fills in the
 * wrong order are a solid block of one colour, a turned picture drawn without a turn is a picture at the
 * wrong angle that reads as the author's mistake, and a label that lost its shadow is a label — legible
 * enough, until it is over something bright. A recorder turns all three into a failing assertion.
 *
 * <p>The frame is built with a fixed viewport and constant lookups, so the numbers in the assertions are the
 * element's own coordinates rather than a client's.
 */
@DisplayName("a canvas element, drawn")
class CanvasElementArtTest {

    private static final int VIEW_WIDTH = 400;
    private static final int VIEW_HEIGHT = 300;

    /** A canvas whose content coordinates map one-to-one onto screen pixels, so the arithmetic is readable. */
    private static Viewport view() {
        return Viewport.fixed().bounds(0, 0, VIEW_WIDTH, VIEW_HEIGHT);
    }

    private static CanvasElementArt.Frame frame(RecordingRenderer r) {
        return frame(r, true);
    }

    /** The same, with the editor's depth chosen, which is what an element's own dev flag is read against. */
    private static CanvasElementArt.Frame frame(RecordingRenderer r, boolean devMode) {
        // The words resolver is the author's own text, unresolved: a test that wanted a translation would
        // answer with one, which is the whole reason the frame asks rather than reaching for the locale.
        return new CanvasElementArt.Frame(r, view(), (element, field, text) -> text.value(),
                id -> QuestState.COMPLETED, devMode);
    }

    /** The same, with the progress a gate is read against chosen. Dev mode is on, so only the gate varies. */
    private static CanvasElementArt.Frame frame(Function<String, QuestState> states) {
        return new CanvasElementArt.Frame(RecordingRenderer.create(), view(),
                (element, field, text) -> text.value(), states, true);
    }

    private static CanvasElement element(String json) {
        return CanvasElement.fromJson(JsonParser.parseString(json))
                .orElseThrow(() -> new AssertionError("the fixture is not an element: " + json));
    }

    private static RecordingRenderer draw(CanvasElement element) {
        RecordingRenderer r = RecordingRenderer.create();
        CanvasElementArt.draw(frame(r), element, CanvasElementArt.Look.NONE);
        return r;
    }

    // ------------------------------------------------------------------
    // The box
    // ------------------------------------------------------------------

    @Test
    @DisplayName("a box is its border across the whole rectangle, then its fill inset by that width")
    void aBoxIsBorderThenInsetFill() {
        // Order is the whole of this arm's correctness, and it is not the obvious order: the border is the
        // whole footprint and the fill is drawn inset over it. Reversed, the border paints over the fill and
        // a wide border leaves a box of one colour -- which reads as the fill colour being wrong.
        RecordingRenderer r = draw(element("""
                { "type": "rect", "id": "box", "x": 10, "y": 20, "width": 100, "height": 60,
                  "fillColor": "#40101018", "borderColor": "#66204060", "borderWidth": 3 }
                """));

        List<RecordingRenderer.Call> fills = r.fills();
        assertEquals(2, fills.size(), "a bordered box is two fills at any width: " + fills);
        assertEquals(0x66204060, fills.get(0).argb(), "the border's footprint is drawn first");
        assertEquals(10, fills.get(0).x());
        assertEquals(20, fills.get(0).y());
        assertEquals(110, fills.get(0).x2());
        assertEquals(80, fills.get(0).y2());
        assertEquals(0x40101018, fills.get(1).argb(), "and the fill over it, inset by the border");
        assertEquals(13, fills.get(1).x(), "inset by the border's own width, not by one pixel");
        assertEquals(23, fills.get(1).y());
        assertEquals(107, fills.get(1).x2());
        assertEquals(77, fills.get(1).y2());
    }

    @Test
    @DisplayName("a box with no border is one fill, and a box with no colour draws nothing at all")
    void aBoxDrawsOnlyWhatItNames() {
        // Both colours default to fully transparent, so a box a file declares without colours is a box that
        // draws nothing -- and issuing the fills anyway would be two primitives per element per frame for a
        // rectangle nobody can see. That matters here more than elsewhere: the canvas is already the most
        // expensive thing on the screen.
        assertEquals(1, draw(element("{ \"type\": \"rect\", \"id\": \"b\", \"fillColor\": \"#FFFFFFFF\" }"))
                .fills().size(), "a fill with no border is one fill");
        assertEquals(1, draw(element("{ \"type\": \"rect\", \"id\": \"b\", \"borderColor\": \"#FFFFFFFF\","
                + " \"borderWidth\": 2 }")).fills().size(), "and a border with no fill is one fill");
        assertTrue(draw(element("{ \"type\": \"rect\", \"id\": \"b\" }")).fills().isEmpty(),
                "and a box that names no colour is not drawn at all");
    }

    // ------------------------------------------------------------------
    // A picture
    // ------------------------------------------------------------------

    @Test
    @DisplayName("a picture asks for the arm it names: an atlas region, or a file")
    void aPictureAsksForItsOwnArm() {
        // Two lookups that fail differently -- an absent file draws nothing, an unknown sprite draws the
        // game's own missing-texture marker -- so a test that could not tell which one an element asked for
        // could not hold either behaviour.
        RecordingRenderer sprite = draw(element("""
                { "type": "image", "id": "i", "x": 10, "y": 20, "width": 64, "height": 32,
                  "image": { "sprite": "minecraft:block/sculk" } }
                """));
        assertEquals(1, sprite.sprites().size(), "a sprite element asks for a sprite");
        assertTrue(sprite.textures().isEmpty(), "and not for a file");
        assertEquals("minecraft:block/sculk", sprite.sprites().get(0).text());
        assertEquals(10, sprite.sprites().get(0).x());
        assertEquals(20, sprite.sprites().get(0).y());
        assertEquals(74, sprite.sprites().get(0).x2(), "stretched to the element's box");
        assertEquals(52, sprite.sprites().get(0).y2());
        assertEquals(0xFFFFFFFF, sprite.sprites().get(0).argb(), "and an unauthored tint is the identity");

        RecordingRenderer file = draw(element("""
                { "type": "image", "id": "i", "width": 32, "height": 32,
                  "image": { "texture": "pack:textures/logo.png" } }
                """));
        assertEquals(1, file.textures().size(), "a texture element asks for a file");
        assertTrue(file.sprites().isEmpty(), "and not for a sprite");
        assertEquals("pack:textures/logo.png", file.textures().get(0).text());
    }

    @Test
    @DisplayName("a tint and an alpha multiply, so neither spelling of dimming is lost")
    void aTintAndAnAlphaCompose() {
        // the legacy format carries the two separately and draws the tint with the alpha field REPLACING the colour's own;
        // this format multiplies them, and with an opaque tint the two are the same sum -- which is why a
        // converted picture is written #FFRRGGBB. Asserted because getting it the other way round makes every
        // converted picture invisible: the legacy `color` is an RGB int with no alpha in it at all.
        RecordingRenderer half = draw(element("""
                { "type": "image", "id": "i", "width": 8, "height": 8, "alpha": 128,
                  "image": { "sprite": "minecraft:block/sculk" } }
                """));
        assertEquals(0x80FFFFFF, half.sprites().get(0).argb(), "white at half alpha");

        RecordingRenderer both = draw(element("""
                { "type": "image", "id": "i", "width": 8, "height": 8, "alpha": 128,
                  "tint": "#80FF0000", "image": { "sprite": "minecraft:block/sculk" } }
                """));
        assertEquals(0x40FF0000, both.sprites().get(0).argb(), "two halves multiplied, not one winning");
    }

    @Test
    @DisplayName("a turned picture is wrapped in a turn, about the pivot its corner flag names")
    void aTurnedPictureIsWrapped() {
        // Both pivots are real: a picture turned slightly off square reads as placed, and one pinned by a
        // corner reads as stuck down -- and the legacy own alignToCorner is used in a real pack. A recorder can
        // tell the two apart; on screen they are the same angle and a different position.
        RecordingRenderer centred = draw(element("""
                { "type": "image", "id": "i", "x": 10, "y": 20, "width": 64, "height": 32, "rotation": 90,
                  "image": { "sprite": "minecraft:block/sculk" } }
                """));
        assertEquals(1, centred.turns().size(), "a turned picture opens one turn");
        assertEquals(42, centred.turns().get(0).x(), "about the box's centre");
        assertEquals(36, centred.turns().get(0).y());
        assertEquals(90F, centred.turns().get(0).amount(), "at the angle the file asked for");
        assertTrue(centred.turnsBalanced(), "and the scope closed: " + centred);
        int open = centred.firstIndex(RecordingRenderer.Op.TURN);
        int drawn = centred.firstIndex(RecordingRenderer.Op.SPRITE);
        int close = centred.firstIndex(RecordingRenderer.Op.UNTURN);
        assertTrue(open < drawn && drawn < close,
                "the picture is drawn inside it: " + open + " " + drawn + " " + close);

        RecordingRenderer corner = draw(element("""
                { "type": "image", "id": "i", "x": 10, "y": 20, "width": 64, "height": 32, "rotation": 8,
                  "corner": true, "image": { "sprite": "minecraft:block/sculk" } }
                """));
        assertEquals(10, corner.turns().get(0).x(), "with corner, about the element's own top-left");
        assertEquals(20, corner.turns().get(0).y());

        RecordingRenderer upright = draw(element("""
                { "type": "image", "id": "i", "width": 8, "height": 8,
                  "image": { "sprite": "minecraft:block/sculk" } }
                """));
        assertTrue(upright.turns().isEmpty(),
                "and an unturned picture opens no scope at all, because an identity turn is a pose push per"
                        + " element per frame for nothing");
    }

    // ------------------------------------------------------------------
    // A label
    // ------------------------------------------------------------------

    @Test
    @DisplayName("a shadowed label asks the shadowed call, at its own size")
    void aShadowedLabelAsksForTheShadow() {
        // The gap this feature found in the seam: a shadow is a baked glyph rather than a second pass at an
        // offset, so a bigger shadowed line cannot be made by drawing a plain line twice -- which is why the
        // shadowed call takes a size. A label that asked for a shadow and silently came out at the font's own
        // size would be an author's size setting doing nothing.
        RecordingRenderer r = draw(element("""
                { "type": "text", "id": "t", "x": 10, "y": 20, "text": "Chapter 1",
                  "scale": 1.5, "color": "#FFA0A0A0", "shadow": true }
                """));

        assertEquals(1, r.shadowedTexts().size(), "one call, with the shadow");
        assertEquals("Chapter 1", r.shadowedTexts().get(0).text());
        assertEquals(10, r.shadowedTexts().get(0).x());
        assertEquals(20, r.shadowedTexts().get(0).y());
        assertEquals(0xFFA0A0A0, r.shadowedTexts().get(0).argb());
        assertEquals(1.5F, r.shadowedTexts().get(0).amount(), "and its own size, not the font's");
        assertTrue(r.texts().isEmpty(), "and not also a plain call, which would double-strike the glyphs");
    }

    @Test
    @DisplayName("a label without a shadow is one call, by size: text at one, a styled run above it")
    void aPlainLabelIsOneCall() {
        RecordingRenderer plain = draw(element("""
                { "type": "text", "id": "t", "x": 4, "y": 8, "text": "Tier one", "color": "#FFFFFFFF" }
                """));
        assertEquals(1, plain.texts().size(), "at the font's own size it is a plain line");
        assertEquals("Tier one", plain.texts().get(0).text());
        assertTrue(plain.styled().isEmpty());

        RecordingRenderer scaled = draw(element("""
                { "type": "text", "id": "t", "x": 4, "y": 8, "text": "Tier one", "scale": 2.0,
                  "color": "#FFFFFFFF" }
                """));
        assertEquals(1, scaled.styled().size(), "a size is what a styled run's scale is for");
        assertEquals(1, scaled.styled().get(0).runs().size());
        assertEquals(2F, scaled.styled().get(0).runs().get(0).scale());
        assertTrue(scaled.texts().isEmpty(), "and it is not also drawn plain");
    }

    @Test
    @DisplayName("a newline is another line, and each is drawn at the next line's height")
    void aLabelDrawsEveryLine() {
        RecordingRenderer r = draw(element("""
                { "type": "text", "id": "t", "x": 0, "y": 10, "text": "one\\ntwo", "color": "#FFFFFFFF" }
                """));
        assertEquals(2, r.texts().size(), "two lines, two calls: " + r.texts());
        assertEquals("one", r.texts().get(0).text());
        assertEquals("two", r.texts().get(1).text());
        assertEquals(10, r.texts().get(0).y());
        assertEquals(10 + r.lineHeight(), r.texts().get(1).y(),
                "the second line sits one line height below the first, measured by the renderer that draws it");
    }

    // ------------------------------------------------------------------
    // A line
    // ------------------------------------------------------------------

    @Test
    @DisplayName("a line draws its route at its own width, and a head only at the ends it names")
    void aLineDrawsItsRouteAndItsHeads() {
        // The width is a number rather than one of the four weight words, so the first assertion is that the
        // number reaches the geometry -- and the second is that a head appears where the element says, which
        // is the one thing the dependency-line vocabulary cannot express.
        String line = "{ \"type\": \"line\", \"id\": \"l\", \"x1\": 20, \"y1\": 100, \"x2\": 180,"
                + " \"y2\": 100, \"width\": 3, \"color\": \"#FFFFFFFF\", \"arrowhead\": \"%s\" }";

        List<RecordingRenderer.Call> blunt = draw(element(line.formatted("none"))).fills();
        assertFalse(blunt.isEmpty(), "a line is ink");
        for (RecordingRenderer.Call fill : blunt) {
            assertEquals(3, fill.y2() - fill.y(), "a three-pixel line is three rows: " + fill);
        }
        assertTrue(blunt.get(0).x() < 40, "and it runs from the first endpoint");

        List<RecordingRenderer.Call> headed = draw(element(line.formatted("end"))).fills();
        assertTrue(headed.size() > blunt.size(), "an end head is more ink: " + blunt.size() + " then "
                + headed.size());
        assertTrue(inkInBand(headed, 170, 200) > inkInBand(blunt, 170, 200),
                "and the extra ink is at the end it names: " + inkInBand(blunt, 170, 200) + " then "
                        + inkInBand(headed, 170, 200));

        List<RecordingRenderer.Call> started = draw(element(line.formatted("start"))).fills();
        assertTrue(started.size() > blunt.size(), "a start head is the case the placement cannot say");
        assertTrue(inkInBand(started, 0, 30) > inkInBand(blunt, 0, 30),
                "and its extra ink is at the start: " + inkInBand(blunt, 0, 30) + " then "
                        + inkInBand(started, 0, 30));
    }

    /**
     * How many pixels of ink fall inside a vertical band.
     *
     * <p>A band rather than "the furthest ink from the endpoint", which is what the first version of this
     * measured and why it failed: the furthest ink from one end of a line is the <i>other</i> end, whether or
     * not a head was drawn there. A window at the end being asked about is the only measurement that can tell
     * a head from the route it caps.
     */
    private static int inkInBand(List<RecordingRenderer.Call> fills, int left, int right) {
        int total = 0;
        for (RecordingRenderer.Call fill : fills) {
            total += Math.max(0, Math.min(fill.x2(), right) - Math.max(fill.x(), left));
        }
        return total;
    }

    // ------------------------------------------------------------------
    // The gates, the box and the hit test
    // ------------------------------------------------------------------

    @Test
    @DisplayName("a dev element needs dev mode, and a gated one needs the quest completed")
    void theTwoGatesAreRead() {
        CanvasElement dev = element("{ \"type\": \"rect\", \"id\": \"b\", \"dev\": true,"
                + " \"fillColor\": \"#FFFFFFFF\" }");
        assertFalse(CanvasElementArt.shown(dev, frame(RecordingRenderer.create(), false)),
                "dev mode off hides it, which is how an author drafts a layout without shipping it");
        assertTrue(CanvasElementArt.shown(dev, frame(RecordingRenderer.create(), true)),
                "and dev mode on shows it");

        CanvasElement gated = element("{ \"type\": \"rect\", \"id\": \"b\", \"requires\": \"the_core\","
                + " \"fillColor\": \"#FFFFFFFF\" }");
        assertTrue(CanvasElementArt.shown(gated, frame(id -> QuestState.COMPLETED)), "completed is shown");
        for (QuestState state : List.of(QuestState.LOCKED, QuestState.UNLOCKED, QuestState.STARTED)) {
            assertFalse(CanvasElementArt.shown(gated, frame(id -> state)), state + " is not completed");
        }
    }

    @Test
    @DisplayName("a label's box is its measured text, and a line's hit test is the route rather than the box")
    void theBoxAndTheHitTest() {        RecordingRenderer r = RecordingRenderer.create();
        CanvasElementArt.Frame frame = frame(r);

        // The box, measured rather than guessed: six pixels a character in this recorder, so the width is
        // arithmetic a test can do by hand -- and the point is that it came from the renderer at all.
        CanvasElement text = element("{ \"type\": \"text\", \"id\": \"t\", \"x\": 10, \"y\": 20,"
                + " \"text\": \"abcd\", \"color\": \"#FFFFFFFF\" }");
        CanvasElementArt.Box box = CanvasElementArt.boxOf(text, frame);
        assertEquals(10, box.left());
        assertEquals(20, box.top());
        assertEquals(10 + r.textWidth("abcd"), box.right());
        assertTrue(box.contains(11, 21), "a point inside the label's own words is inside its box");
        assertFalse(box.contains(10 + r.textWidth("abcd") + 5, 21), "and one past them is not");

        // A line, whose box is mostly empty: the hit test measures to the route, which is the difference
        // between a line you can press and a line you can press anywhere near.
        CanvasElement diagonal = element("{ \"type\": \"line\", \"id\": \"l\", \"x1\": 0, \"y1\": 0,"
                + " \"x2\": 100, \"y2\": 100, \"width\": 1 }");
        CanvasElementArt.Box diagonalBox = CanvasElementArt.boxOf(diagonal, frame);
        assertEquals(0, CanvasElementArt.distanceTo(diagonal, diagonalBox, view(), 50, 50), 0.001,
                "a point on the diagonal is on the line");
        assertTrue(CanvasElementArt.distanceTo(diagonal, diagonalBox, view(), 50, 60) > 5,
                "and a point off it is measured to the route rather than to the box it spans");
        assertTrue(CanvasElementArt.distanceTo(diagonal, diagonalBox, view(), 50, 60) < 8,
                "by a distance a tolerance can be applied to");

        // A box element, whose distance is zero inside and to the nearest edge outside.
        CanvasElement rect = element("{ \"type\": \"rect\", \"id\": \"r\", \"x\": 10, \"y\": 10,"
                + " \"width\": 20, \"height\": 20, \"fillColor\": \"#FFFFFFFF\" }");
        CanvasElementArt.Box rectBox = CanvasElementArt.boxOf(rect, frame);
        assertEquals(0, CanvasElementArt.distanceTo(rect, rectBox, view(), 15, 15), 0.001);
        assertEquals(5, CanvasElementArt.distanceTo(rect, rectBox, view(), 5, 15), 0.001,
                "five pixels left of the box is five pixels from it");

        // And an element this build cannot read has no box and no distance, so a press can never land on it.
        CanvasElement unknown = element("{ \"type\": \"tenet:badge\", \"id\": \"b\" }");
        assertNotNull(CanvasElementArt.boxOf(unknown, frame));
        assertEquals(0, CanvasElementArt.boxOf(unknown, frame).width());
        assertTrue(draw(unknown).calls().isEmpty(), "and drawing it asks for nothing at all");
    }

    @Test
    @DisplayName("a fixed-size label travels with the canvas and grows with the zoom, like a node")
    void fixedLabelsTravelWithTheCanvas() {
        // Twice zoomed, from a corner that is not the origin: both labels sit on the zoomed position,
        // because a position held at its zoom-one landing cannot survive a real zoom gesture, whose own
        // pan rewrite slides it about. What the flag holds is the size alone: the anchored label keeps
        // the file's size on screen, and the fixed one draws it times the zoom.
        RecordingRenderer r = RecordingRenderer.create();
        Viewport zoomed = Viewport.of(0.25F, 4F).bounds(100, 50, VIEW_WIDTH, VIEW_HEIGHT);
        assertTrue(zoomed.setScale(2F));
        CanvasElementArt.Frame frame = new CanvasElementArt.Frame(r, zoomed,
                (element, field, text) -> text.value(), id -> QuestState.COMPLETED, true);

        CanvasElement text = element("{ \"type\": \"text\", \"id\": \"t\", \"x\": 10, \"y\": 20,"
                + " \"text\": \"abcd\", \"color\": \"#FFFFFFFF\" }");
        CanvasElementArt.Box box = CanvasElementArt.boxOf(text, frame);
        assertEquals(120, box.left(), "canvas-anchored: the position zooms with the canvas");
        assertEquals(90, box.top());

        CanvasElement fixed = element("{ \"type\": \"text\", \"id\": \"f\", \"x\": 10, \"y\": 20,"
                + " \"text\": \"abcd\", \"color\": \"#FFFFFFFF\", \"fixed\": true }");
        CanvasElementArt.Box still = CanvasElementArt.boxOf(fixed, frame);
        assertEquals(box.left(), still.left(), "fixed: the same zoomed position, glued to its neighbours");
        assertEquals(box.top(), still.top());
        assertEquals(2 * box.width(), still.width(),
                "and the size is the file's own times the zoom, like a node's");
        assertEquals(2 * box.height(), still.height());

        // The ink agrees with the box: the run is drawn at the file's scale times the zoom.
        CanvasElementArt.draw(frame, fixed, CanvasElementArt.Look.NONE);
        assertEquals(1, r.styled().size(), "one scaled run, not a plain-text call: " + r.calls());
        assertEquals(2F, r.styled().get(0).runs().get(0).scale(),
                "drawn at the file's scale times the zoom");

        // Panned as well as zoomed: both labels follow the pan together, glued to each other.
        zoomed.setOffset(30, 40);
        CanvasElementArt.Box moved = CanvasElementArt.boxOf(text, frame);
        assertEquals(150, moved.left(), "canvas-anchored: origin, pan and zoomed position");
        assertEquals(130, moved.top());
        CanvasElementArt.Box held = CanvasElementArt.boxOf(fixed, frame);
        assertEquals(moved.left(), held.left(), "fixed: the same pan, the same zoomed position");
        assertEquals(moved.top(), held.top());
    }

    @Test
    @DisplayName("a selection is a ring, and a marked element wears the hidden mark")
    void aSelectionAndAHiddenMarkAreDrawn() {
        // The ring is the only thing on the canvas that says what a drag will move, so it is drawn over the
        // element's own ink -- and the hidden mark is the same one a hidden quest wears, so the two read as
        // one fact rather than two.
        CanvasElement rect = element("{ \"type\": \"rect\", \"id\": \"r\", \"width\": 40, \"height\": 20,"
                + " \"fillColor\": \"#FFFFFFFF\" }");

        RecordingRenderer plain = RecordingRenderer.create();
        CanvasElementArt.draw(frame(plain), rect, CanvasElementArt.Look.NONE);
        assertEquals(1, plain.fills().size(), "nothing but the box itself");

        RecordingRenderer selected = RecordingRenderer.create();
        CanvasElementArt.draw(frame(selected), rect, new CanvasElementArt.Look(true, false, false));
        assertEquals(5, selected.fills().size(), "the box and a four-fill ring: " + selected.fills());

        RecordingRenderer marked = RecordingRenderer.create();
        CanvasElementArt.draw(frame(marked), rect, new CanvasElementArt.Look(false, false, true));
        assertTrue(marked.fills().size() > 1, "a marked element wears the dashed square as well");
        assertTrue(marked.clipsBalanced() && marked.turnsBalanced(), "and neither leaves a scope open");
    }

    @Test
    @DisplayName("everything an element draws goes through the frame's renderer, and nothing else")
    void drawingIsOneBatchOfCalls() {
        // The seam is the only route from either mod to the renderer, so a drawing that reached past it would
        // be a file the version port could not fix. Asserted as "the recorder saw the calls", which is the
        // only way a headless test can hold that.
        RecordingRenderer r = RecordingRenderer.create();
        CanvasElementArt.Frame frame = frame(r);
        for (String json : List.of(
                "{ \"type\": \"rect\", \"id\": \"r\", \"width\": 8, \"height\": 8,"
                        + " \"fillColor\": \"#FFFFFFFF\" }",
                "{ \"type\": \"line\", \"id\": \"l\", \"x1\": 0, \"y1\": 0, \"x2\": 8, \"y2\": 8,"
                        + " \"arrowhead\": \"both\" }",
                "{ \"type\": \"text\", \"id\": \"t\", \"text\": \"x\", \"shadow\": true }",
                "{ \"type\": \"image\", \"id\": \"i\", \"width\": 8, \"height\": 8,"
                        + " \"image\": { \"sprite\": \"minecraft:block/sculk\" } }")) {
            CanvasElementArt.draw(frame, element(json), CanvasElementArt.Look.NONE);
        }

        assertFalse(r.calls().isEmpty(), "the recorder has to have been asked for something");
        assertTrue(r.calls().stream().noneMatch(call -> call.op() == RecordingRenderer.Op.BLUR),
                "and for nothing that is not a drawing operation");
        assertNotNull(GuiRenderer.Scoped.class, "the seam's own types are what the drawing is expressed in");
    }

    // ------------------------------------------------------------------
    // The grips a selected element wears
    // ------------------------------------------------------------------

    /** A box and a picture wear four corner grips, a picture a fifth, a line two, and a label none. */
    @Test
    @DisplayName("which grips an arm wears, and where the rotate one is")
    void gripsByArm() {
        RecordingRenderer r = RecordingRenderer.create();
        CanvasElementArt.Frame frame = frame(r);

        CanvasElementArt.Box rect = CanvasElementArt.boxOf(
                element("{ \"type\": \"rect\", \"id\": \"b\", \"x\": 10, \"y\": 20,"
                        + " \"width\": 100, \"height\": 60 }"), frame);
        assertEquals(List.of(CanvasElementArt.Handle.RESIZE_NW, CanvasElementArt.Handle.RESIZE_NE,
                        CanvasElementArt.Handle.RESIZE_SW, CanvasElementArt.Handle.RESIZE_SE),
                CanvasElementArt.handles(element("{ \"type\": \"rect\", \"id\": \"b\" }"), rect)
                        .stream().map(CanvasElementArt.Grip::handle).toList(),
                "a box is resized by its four corners");
        assertEquals(10, CanvasElementArt.handles(element("{ \"type\": \"rect\", \"id\": \"b\" }"), rect)
                .get(0).x(), "and the grips are on the box's own edges");
        assertEquals(110, CanvasElementArt.handles(element("{ \"type\": \"rect\", \"id\": \"b\" }"), rect)
                .get(3).x(), "including the far corner, which is one past the last pixel inside it");

        // A picture turns as well, and the grip sits above the top edge at the middle -- the one place it
        // cannot be confused with a corner.
        CanvasElement image = element("{ \"type\": \"image\", \"id\": \"i\", \"x\": 10, \"y\": 20,"
                + " \"width\": 100, \"height\": 60,"
                + " \"image\": { \"sprite\": \"minecraft:block/stone\" } }");
        List<CanvasElementArt.Grip> grips = CanvasElementArt.handles(image, rect);
        assertEquals(5, grips.size());
        CanvasElementArt.Grip rotate = grips.get(4);
        assertEquals(CanvasElementArt.Handle.ROTATE, rotate.handle());
        assertEquals(60, rotate.x(), "the middle of the top edge");
        assertEquals(20 - CanvasElementArt.ROTATE_OFFSET, rotate.y(), "and above it");

        // A line's grips are its own two endpoints, which its box's opposite corners are.
        CanvasElement line = element("{ \"type\": \"line\", \"id\": \"l\", \"x1\": 4, \"y1\": 8,"
                + " \"x2\": 100, \"y2\": 8 }");
        assertEquals(List.of(CanvasElementArt.Handle.END_FROM, CanvasElementArt.Handle.END_TO),
                CanvasElementArt.handles(line, CanvasElementArt.boxOf(line, frame)).stream()
                        .map(CanvasElementArt.Grip::handle).toList());

        // A label has none: its box is measured, so a resize would be writing a number the loader does not
        // read, and a rotate an angle the arm has no field for.
        CanvasElement text = element("{ \"type\": \"text\", \"id\": \"t\", \"text\": \"x\" }");
        assertTrue(CanvasElementArt.handles(text, CanvasElementArt.boxOf(text, frame)).isEmpty());
    }

    @Test
    @DisplayName("a grip is grabbed from its own edge, where a hit test counts as outside the element")
    void aGripIsGrabbedFromTheEdge() {
        // The fault this exists for, and it is not hypothetical: `handles` puts the far grips ON the box's
        // right and bottom edges, while `Box.contains` is half-open -- so a press on the bottom-right corner
        // is one pixel outside the element. Without a grip test of its own, that press would miss the element
        // entirely and start a marquee.
        CanvasElement rect = element("{ \"type\": \"rect\", \"id\": \"b\", \"x\": 10, \"y\": 20,"
                + " \"width\": 100, \"height\": 60 }");
        CanvasElementArt.Box box = CanvasElementArt.boxOf(rect, frame(RecordingRenderer.create()));
        assertFalse(box.contains(box.right(), box.bottom()), "the far corner is outside the box's own hit test");
        assertEquals(CanvasElementArt.Handle.RESIZE_SE,
                CanvasElementArt.handleAt(rect, box, box.right(), box.bottom()),
                "and the grip is claimed from there anyway");

        // The grab is generous around the ink, and it is a square rather than a circle: a press a pixel or two
        // off still lands, and one well away does not.
        assertEquals(CanvasElementArt.Handle.RESIZE_NW,
                CanvasElementArt.handleAt(rect, box, box.left() + 2, box.top() - 3));
        assertNull(CanvasElementArt.handleAt(rect, box, box.left() + 40, box.top() + 30),
                "the middle of the element is the element's, not a grip's");
        assertNull(CanvasElementArt.handleAt(rect, box, box.left() - CanvasElementArt.GRIP_GRAB - 2,
                box.top()), "and the grab has an edge");
    }

    @Test
    @DisplayName("a resize is anchored at the corner the author is not holding")
    void aResizeIsAnchored() {
        CanvasElement rect = element("{ \"type\": \"rect\", \"id\": \"b\", \"x\": 10, \"y\": 20,"
                + " \"width\": 100, \"height\": 60 }");

        // Dragging the far corner out: the near one does not move, which is what makes a resize predictable.
        CanvasElement grown = CanvasElementArt.resized(rect, CanvasElementArt.Handle.RESIZE_SE, 150, 120);
        assertEquals(10, CanvasElementArt.originX(grown), "the anchored corner is exactly where it was");
        assertEquals(20, CanvasElementArt.originY(grown));
        assertEquals(List.of(new CanvasElementArt.Field("width", 140),
                        new CanvasElementArt.Field("height", 100)),
                CanvasElementArt.geometry(rect, grown),
                "and only the size changed, because the anchor did not");

        // Dragging the near corner: now the origin moves and the far corner stays.
        CanvasElement pulled = CanvasElementArt.resized(rect, CanvasElementArt.Handle.RESIZE_NW, 30, 40);
        assertEquals(List.of(new CanvasElementArt.Field("x", 30), new CanvasElementArt.Field("y", 40),
                        new CanvasElementArt.Field("width", 80), new CanvasElementArt.Field("height", 40)),
                CanvasElementArt.geometry(rect, pulled));

        // And a drag past the anchor is clamped rather than inverted: a box of no size is a box nobody can
        // grab again.
        CanvasElement squashed = CanvasElementArt.resized(rect, CanvasElementArt.Handle.RESIZE_SE, 5, 5);
        assertEquals(1, CanvasElementArt.geometry(rect, squashed).stream()
                .filter(field -> field.path().equals("width")).findFirst().orElseThrow().value());
        assertEquals(1, CanvasElementArt.geometry(rect, squashed).stream()
                .filter(field -> field.path().equals("height")).findFirst().orElseThrow().value());

        // The codec's own maximum is respected, so a long drag cannot write a size the loader would clamp
        // silently -- the file would then disagree with the screen.
        CanvasElement huge = CanvasElementArt.resized(rect, CanvasElementArt.Handle.RESIZE_SE, 999_999, 999_999);
        assertEquals(CanvasElement.Rect.MAX_EDGE, CanvasElementArt.geometry(rect, huge).stream()
                .filter(field -> field.path().equals("width")).findFirst().orElseThrow().value());
    }

    @Test
    @DisplayName("a line's grip moves one endpoint and leaves the other exactly where it was")
    void aLineGripMovesOneEnd() {
        // The opposite of a resize, and the reason a line's grips are their own kind: an endpoint is placed,
        // not derived, so moving one must not drag the other with it.
        CanvasElement line = element("{ \"type\": \"line\", \"id\": \"l\", \"x1\": 4, \"y1\": 8,"
                + " \"x2\": 100, \"y2\": 8, \"width\": 3, \"color\": \"#FFFFFFFF\" }");

        CanvasElement moved = CanvasElementArt.endMoved(line, CanvasElementArt.Handle.END_TO, 40, 90);
        assertEquals(List.of(new CanvasElementArt.Field("x2", 40), new CanvasElementArt.Field("y2", 90)),
                CanvasElementArt.geometry(line, moved), "only the end that was dragged");

        CanvasElement other = CanvasElementArt.endMoved(line, CanvasElementArt.Handle.END_FROM, 1, 2);
        assertEquals(List.of(new CanvasElementArt.Field("x1", 1), new CanvasElementArt.Field("y1", 2)),
                CanvasElementArt.geometry(line, other));
        assertEquals(100, ((CanvasElement.Line) other).x2(), "and the far end is untouched");

        // The thickness survives a box write, which is the transposition this arm's own `withBox` warns
        // about: `width` means how thick the line is, and a box's width is a different number entirely.
        CanvasElement boxed = line.withBox(0, 0, 200, 50);
        assertEquals(3, ((CanvasElement.Line) boxed).width(), "the line is still three pixels thick");
        assertEquals(200, ((CanvasElement.Line) boxed).x2(), "and it now reaches the box's own far corner");
    }

    @Test
    @DisplayName("the rotate grip is straight up at rest and turns clockwise")
    void theRotateGripStartsAtZero() {
        // Straight up is zero because that is where the grip sits when the picture is straight: grabbing it and
        // letting go without moving must change nothing at all.
        CanvasElement image = element("{ \"type\": \"image\", \"id\": \"i\", \"x\": 0, \"y\": 0,"
                + " \"width\": 64, \"height\": 64, \"image\": { \"sprite\": \"minecraft:block/stone\" } }");
        assertEquals(0, CanvasElementArt.rotationTo(image, 32, 14), "directly above the centre is upright");
        assertEquals(90, CanvasElementArt.rotationTo(image, 50, 32),
                "to the right of the centre is a quarter turn clockwise, which is the way the seam turns");
        assertEquals(180, CanvasElementArt.rotationTo(image, 32, 50));
        assertEquals(270, CanvasElementArt.rotationTo(image, 14, 32));

        // And it wraps, so a drag that goes round twice writes the number a file would hold. The angle is read
        // from the box, which the number it already carries cannot move -- so a second pass over the same point
        // gives the same answer, and that is what makes the gesture stable while the picture turns under it.
        assertEquals(69, CanvasElementArt.rotationTo(image, 50, 25));
        assertEquals(CanvasElementArt.rotationTo(image, 50, 25),
                CanvasElementArt.rotationTo(image.withRotation(370), 50, 25),
                "an angle is read from the box, so the number it already carries cannot compound it");
    }

    @Test
    @DisplayName("the geometry diff writes what changed and nothing else")
    void theGeometryDiff() {
        // The one commit path all three gestures share, so this is where "a gesture writes only what it moved"
        // is decided. An empty diff is the case a click that jittered produces: it must cost nothing.
        CanvasElement rect = element("{ \"type\": \"rect\", \"id\": \"b\", \"x\": 10, \"y\": 20,"
                + " \"width\": 100, \"height\": 60, \"fillColor\": \"#40FFFFFF\" }");
        assertTrue(CanvasElementArt.geometry(rect, rect).isEmpty(), "nothing changed, nothing written");
        assertEquals(List.of(new CanvasElementArt.Field("x", 15), new CanvasElementArt.Field("y", 27)),
                CanvasElementArt.geometry(rect, rect.translated(5, 7)),
                "a move writes the position, not the size and not the colour");

        CanvasElement line = element("{ \"type\": \"line\", \"id\": \"l\", \"x1\": 4, \"y1\": 8,"
                + " \"x2\": 100, \"y2\": 8 }");
        assertEquals(4, CanvasElementArt.geometry(line, line.translated(5, 7)).size(),
                "a line's position is its four endpoints, so a move writes four fields");

        CanvasElement image = element("{ \"type\": \"image\", \"id\": \"i\", \"x\": 0, \"y\": 0,"
                + " \"width\": 64, \"height\": 64, \"rotation\": 30,"
                + " \"image\": { \"sprite\": \"minecraft:block/stone\" } }");
        assertEquals(List.of(new CanvasElementArt.Field("rotation", 45)),
                CanvasElementArt.geometry(image, image.withRotation(45)),
                "a rotate writes one field, and never the box");

        // Different arms cannot happen -- a gesture copies the element it started from -- and the empty answer
        // rather than an exception is deliberate: this runs on the release path, where a throw is a crash.
        assertTrue(CanvasElementArt.geometry(rect, line).isEmpty(), "and two different arms write nothing");
    }

    // ------------------------------------------------------------------
    // A picture that turns
    // ------------------------------------------------------------------

    /** A picture at a known place, with a known angle. */
    private static CanvasElement picture(int x, int y, int width, int height, int rotation,
                                         boolean corner) {
        return element("{ \"type\": \"image\", \"id\": \"i\", \"x\": " + x + ", \"y\": " + y
                + ", \"width\": " + width + ", \"height\": " + height + ", \"rotation\": " + rotation
                + ", \"corner\": " + corner
                + ", \"image\": { \"sprite\": \"minecraft:block/stone\" } }");
    }

    /** A box in content coordinates, for the geometry that needs no viewport. */
    private static CanvasElementArt.Box boxOf(int x, int y, int width, int height) {
        return new CanvasElementArt.Box(x, y, x + width, y + height);
    }

    /** Where a corner of a turned picture really is, in content coordinates. */
    private static double[] cornerContent(CanvasElement image, int cornerX, int cornerY) {
        CanvasElementArt.Box box = boxOf(CanvasElementArt.originX(image), CanvasElementArt.originY(image),
                ((CanvasElement.Image) image).width(), ((CanvasElement.Image) image).height());
        double[] pivot = {CanvasElementArt.pivotX(image, box), CanvasElementArt.pivotY(image, box)};
        double[] turned = CanvasElementArt.turned(cornerX - pivot[0], cornerY - pivot[1], 0, 0,
                CanvasElementArt.degreesOf(image));
        return new double[] {turned[0] + pivot[0], turned[1] + pivot[1]};
    }

    @Test
    @DisplayName("the pivot is the centre, or the picture's own top-left when it says so")
    void thePivotFollowsTheCornerFlag() {
        CanvasElementArt.Box box = boxOf(10, 20, 100, 60);
        CanvasElement centred = picture(10, 20, 100, 60, 30, false);
        assertEquals(60, CanvasElementArt.pivotX(centred, box), "the middle of the box");
        assertEquals(50, CanvasElementArt.pivotY(centred, box));

        CanvasElement cornered = picture(10, 20, 100, 60, 30, true);
        assertEquals(10, CanvasElementArt.pivotX(cornered, box), "and the picture's own corner with corner");
        assertEquals(20, CanvasElementArt.pivotY(cornered, box));

        // An element that cannot turn has a pivot anyway -- the middle of its box -- so nothing that reads one
        // has to ask whether there is a turn at all.
        assertEquals(60, CanvasElementArt.pivotX(
                element("{ \"type\": \"rect\", \"id\": \"b\" }"), box));
    }

    @Test
    @DisplayName("the grips turn with the picture, and a press finds them where they are drawn")
    void theGripsTurnWithThePicture() {
        // A square picture turned a quarter about its centre: its corners land on each other's places, so the
        // grips are all still on the old corners -- permuted. That is the assertion from one side; the identity
        // of each grip is the other, and it is the one that fails if the frame does not turn.
        CanvasElement turned = picture(0, 0, 64, 64, 90, false);
        CanvasElementArt.Box box = boxOf(0, 0, 64, 64);

        assertEquals(CanvasElementArt.Handle.RESIZE_NW,
                CanvasElementArt.handleAt(turned, box, 64, 0),
                "the top-left grip is a quarter turn clockwise from where it started");
        assertEquals(CanvasElementArt.Handle.RESIZE_SW, CanvasElementArt.handleAt(turned, box, 0, 0),
                "and the one at the old top-left corner is now the bottom-left");
        assertEquals(CanvasElementArt.Handle.RESIZE_SE,
                CanvasElementArt.handleAt(turned, box, 0, 64), "the far corner moved to match");

        // A picture that is not square makes the other half visible: the frame's own corner is now empty,
        // because the picture's corners have turned away from it.
        CanvasElement longPicture = picture(0, 0, 100, 20, 90, false);
        CanvasElementArt.Box longBox = boxOf(0, 0, 100, 20);
        assertNull(CanvasElementArt.handleAt(longPicture, longBox, 0, 0),
                "nothing is grabbable at the corner a frame square to the screen would have drawn");
        assertNotNull(CanvasElementArt.handleAt(longPicture, longBox, 60, -40),
                "and the turned corner is grabbable where it is drawn");

        // Upright, the same press is the plain one -- so this is the rotation talking and not the grab.
        CanvasElement upright = picture(0, 0, 64, 64, 0, false);
        assertEquals(CanvasElementArt.Handle.RESIZE_NW, CanvasElementArt.handleAt(upright, box, 0, 0));
    }

    @Test
    @DisplayName("a press inside the part of a picture turning out of its box is a press on the picture")
    void theBodyFollowsTheTurn() {
        // The fault this exists for: a long thin picture turned upright reaches well outside the box it is
        // stored as, and a hit test that used the box would call a press on the picture's middle a miss --
        // so the one part of it the author can clearly see would be the part that cannot be selected.
        CanvasElement turned = picture(0, 0, 100, 20, 90, false);
        CanvasElementArt.Box box = boxOf(0, 0, 100, 20);
        CanvasElementArt.Frame frame = frame(RecordingRenderer.create());

        assertTrue(CanvasElementArt.distanceTo(turned, box, view(), 50, -30) == 0,
                "above the box and inside the turned picture is a press on the picture");
        assertFalse(box.contains(50, -30), "and the box alone would have said otherwise");
        assertTrue(CanvasElementArt.distanceTo(turned, box, view(), 50, 10) == 0,
                "the middle is still the middle");
        assertTrue(CanvasElementArt.distanceTo(turned, box, view(), 90, 10) > 0,
                "and a point beside the turned picture is still outside it");
    }

    @Test
    @DisplayName("resizing a turned picture keeps the opposite corner exactly where it was")
    void aTurnedResizeIsAnchored() {
        // The property, stated as the geometry: grab the far corner of a turned picture, drag it out, and the
        // corner the author is not holding has to be at the same place on the canvas. Getting this wrong is
        // the picture sliding sideways as it grows, which is the fault the whole local-frame algorithm is for.
        CanvasElement before = picture(0, 0, 64, 64, 90, false);
        double[] anchorBefore = cornerContent(before, 0, 0);

        // The pointer that asks for 128x128: the size wanted, turned into the picture's frame and measured from
        // the anchor's *content* position -- which is not the anchor's place in the box, because the picture is
        // turned. This is the contract the resize is written to, stated as the fixture.
        double[] wanted = CanvasElementArt.turned(128, 128, 0, 0, 90);
        int pointerX = (int) Math.round(anchorBefore[0] + wanted[0]);
        int pointerY = (int) Math.round(anchorBefore[1] + wanted[1]);
        CanvasElement after = CanvasElementArt.resized(before, CanvasElementArt.Handle.RESIZE_SE,
                pointerX, pointerY);
        CanvasElement.Image grown = (CanvasElement.Image) after;
        assertEquals(128, grown.width(), "the size asked for, along the picture's own axes");
        assertEquals(128, grown.height());

        double[] anchorAfter = cornerContent(after, grown.x(), grown.y());
        assertEquals(anchorBefore[0], anchorAfter[0], 0.6, "the anchor corner has not moved");
        assertEquals(anchorBefore[1], anchorAfter[1], 0.6);

        // And the corner that was dragged is under the pointer, which is what measuring the size *from the
        // anchor* buys: a size taken from the pointer's place in the local box would leave it lagging.
        double[] draggedAfter = cornerContent(after, grown.x() + grown.width(),
                grown.y() + grown.height());
        assertEquals(pointerX, draggedAfter[0], 0.6, "the dragged corner is where the pointer was");
        assertEquals(pointerY, draggedAfter[1], 0.6);
    }

    @Test
    @DisplayName("a turned picture is culled against the box it can reach, not the box it is stored as")
    void theCullBoundGrowsWithTheTurn() {
        CanvasElement upright = picture(0, 0, 100, 20, 0, false);
        CanvasElementArt.Box box = boxOf(0, 0, 100, 20);
        assertEquals(box, CanvasElementArt.turnedBounds(upright, box),
                "an upright picture is its own box, with no arithmetic at all");

        CanvasElementArt.Box turned = CanvasElementArt.turnedBounds(picture(0, 0, 100, 20, 90, false),
                box);
        assertTrue(turned.width() >= 100 && turned.height() >= 100,
                "a quarter turn reaches the diagonal: " + turned);
        assertTrue(turned.contains(50, -30),
                "which is what keeps a turned picture on screen while part of it is showing");
    }
}
