package dev.ellipog.tenet.quest;

import com.google.gson.JsonParser;
import com.mojang.serialization.JsonOps;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A chapter's markers, as a file writes them.
 *
 * <h2>What this file is for</h2>
 *
 * <p>A link is the one canvas object with a compatibility obligation besides the picture: each of
 * its keys is a field a converted chapter has to land in, and a missing one is a marker that
 * arrives pointing nowhere, at the wrong size, or not at all. So the field list is asserted rather
 * than sampled, and the canvas grouping — the reason a chapter with links still decodes through a
 * sixteen-component codec — is asserted through the chapter manifest Redux rather than trusted.
 */
@DisplayName("a quest link")
class QuestLinkCodecTest {

    private static QuestLink read(String json) {
        return QuestLink.CODEC.parse(JsonOps.INSTANCE, JsonParser.parseString(json)).getOrThrow();
    }

    private static boolean refuses(String json) {
        return QuestLink.CODEC.parse(JsonOps.INSTANCE, JsonParser.parseString(json)).error().isPresent();
    }

    @Test
    @DisplayName("a link carries every field the legacy format writes on a quest link, plus the two it leaves to the quest")
    void theLinkArm() {
        // The shape a converted link arrives in, with the two fields the legacy format never writes spelled out:
        // each is a key a converted chapter has to land in, and a missing one is a marker that
        // arrives somewhere else, at some other size, or shaped like nothing its target is.
        QuestLink link = read("""
                { "id": "gate_hint", "quest": "the_deep_descent", "x": 336, "y": -64,
                  "shape": "hexagon", "size": 64 }
                """);

        assertEquals("gate_hint", link.id());
        assertEquals("the_deep_descent", link.quest().id());
        assertEquals(336, link.x());
        assertEquals(-64, link.y());
        assertEquals(QuestShape.HEXAGON, link.shape());
        assertEquals(64, link.size());
    }

    @Test
    @DisplayName("an id and a target are required, because a marker without either is nothing")
    void idAndTargetAreRequired() {
        assertTrue(refuses("{ \"quest\": \"a\" }"), "no id");
        assertTrue(refuses("{ \"id\": \"l\" }"), "no target");
        assertTrue(refuses("{ \"id\": \"l\", \"quest\": 42 }"), "a target that is not a name");
    }

    @Test
    @DisplayName("the defaults are the target's own: rounded, 48 pixels, where the file puts it")
    void theDefaults() {
        QuestLink link = read("{ \"id\": \"l\", \"quest\": \"a\" }");

        assertEquals(0, link.x());
        assertEquals(0, link.y());
        assertEquals(QuestShape.ROUNDED, link.shape(), "absent draws as the target does");
        assertEquals(QuestLayout.DEFAULT_SIZE, link.size());

        // Absent is not a zeroed record: the default is the whole documented answer, so no file
        // changes meaning by gaining the field.
        assertEquals(48, link.size());
    }

    @Test
    @DisplayName("a size outside the node's bounds is read as the nearest legal node, like a quest's")
    void sizeIsClamped() {
        assertEquals(QuestLayout.MIN_SIZE, read("{ \"id\": \"l\", \"quest\": \"a\", \"size\": 1 }").size());
        assertEquals(QuestLayout.MAX_SIZE,
                read("{ \"id\": \"l\", \"quest\": \"a\", \"size\": 4000 }").size(),
                "a four-thousand-pixel marker is a typo for forty, not a design");
    }

    @Test
    @DisplayName("a link reads and writes the same object, through the tree as well as a file")
    void theWireShapeIsTheFileShape() {
        // The tree carries links by encoding them with this same codec, which is what makes a field
        // added to a link travel without anybody remembering to send it. This is that property.
        QuestLink original = read("""
                { "id": "gate_hint", "quest": "the_deep_descent", "x": 336, "y": -64 }
                """);

        var json = QuestLink.asJson(original);
        assertEquals("gate_hint", json.get("id").getAsString());
        assertEquals(original, QuestLink.fromJson(json).orElseThrow());
        assertTrue(QuestLink.fromJson(JsonParser.parseString("42")).isEmpty(),
                "and something that is not a link reads as nothing rather than throwing");
    }

    @Test
    @DisplayName("a move touches a link's two numbers and nothing else")
    void translationMovesWhatAPositionIs() {
        QuestLink moved = read("{ \"id\": \"l\", \"quest\": \"a\", \"x\": 10, \"y\": 20, \"size\": 64 }")
                .translated(-5, 7);

        assertEquals(5, moved.x());
        assertEquals(27, moved.y());
        assertEquals(64, moved.size(), "a move is not a resize");
        assertEquals("l", moved.id(), "and it is still the same link");
        assertEquals("a", moved.quest().id());
    }

    @Test
    @DisplayName("the canvas reads both lists flat, exactly as a chapter writes them")
    void theCanvasGroupingIsInvisibleInJson() {
        // The grouping that keeps the chapter codec at sixteen components: both lists stay flat on
        // the chapter, as if they were fields of their own. A chapter that nests either is a file
        // the format does not describe.
        ChapterCanvas canvas = ChapterCanvas.MAP_CODEC.codec().parse(JsonOps.INSTANCE,
                JsonParser.parseString("""
                { "elements": [ { "type": "rect", "id": "box" } ],
                  "links": [ { "id": "l", "quest": "a" } ] }
                """)).getOrThrow();

        assertEquals(1, canvas.elements().size());
        assertInstanceOf(CanvasElement.Rect.class, canvas.elements().get(0));
        assertEquals(1, canvas.links().size());
        assertEquals("a", canvas.links().get(0).quest().id());

        ChapterCanvas empty = ChapterCanvas.MAP_CODEC.codec().parse(JsonOps.INSTANCE,
                JsonParser.parseString("{}")).getOrThrow();
        assertTrue(empty.elements().isEmpty(), "absent is empty, not null");
        assertTrue(empty.links().isEmpty(), "absent is empty, not null");
    }
}
