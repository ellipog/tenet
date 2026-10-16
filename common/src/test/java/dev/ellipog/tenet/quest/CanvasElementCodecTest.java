package dev.ellipog.tenet.quest;

import com.google.gson.JsonParser;
import com.mojang.serialization.JsonOps;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The four kinds of canvas element, as a file writes them.
 *
 * <h2>What this file is for</h2>
 *
 * <p>Every arm here is read by the same dispatch on {@code "type"}, and each has fields with a default, a
 * clamp or a vocabulary behind it. Those three are where a quest format goes quietly wrong: a default that
 * swallows a field is indistinguishable from a field that never travelled, a clamp that reads {@code 0} as
 * {@code 1} changes what a file means, and a wrapping angle read as a clamped one draws the opposite of
 * the picture somebody wrote down. So each is asserted rather than trusted, and the assertions are about
 * the numbers an author would recognise.
 *
 * <p>The unknown-type case is here for the same reason {@code UnknownTask}'s tests are: one element from a
 * newer build must not cost an author every quest in the chapter, and that promise is a behaviour of the
 * codec rather than of any one arm.
 */
@DisplayName("a canvas element")
class CanvasElementCodecTest {

    private static CanvasElement read(String json) {
        return CanvasElement.CODEC.parse(JsonOps.INSTANCE, JsonParser.parseString(json)).getOrThrow();
    }

    private static boolean refuses(String json) {
        return CanvasElement.CODEC.parse(JsonOps.INSTANCE, JsonParser.parseString(json)).error().isPresent();
    }

    private static String rewrite(CanvasElement element) {
        return CanvasElement.CODEC.encodeStart(JsonOps.INSTANCE, element).getOrThrow().toString();
    }

    // ------------------------------------------------------------------
    // The common fields
    // ------------------------------------------------------------------

    @Test
    @DisplayName("every element carries an id, an order, a dev flag and a gate, whatever its type")
    void theCommonFieldsApplyToEveryArm() {
        // The grouping that makes this true is the point of `Common`: four fields that are about an
        // element's identity and sight rather than about what it draws, read by one codec that every arm
        // shares. An arm that read its own copy would be an arm where one of the four could go missing.
        for (String type : List.of(CanvasElement.TYPE_IMAGE, CanvasElement.TYPE_TEXT, CanvasElement.TYPE_LINE,
                CanvasElement.TYPE_RECT)) {
            String body = switch (type) {
                case CanvasElement.TYPE_IMAGE -> "\"image\": { \"texture\": \"pack:textures/x.png\" }";
                case CanvasElement.TYPE_TEXT -> "\"text\": \"hello\"";
                case CanvasElement.TYPE_LINE -> "\"x1\": 0, \"y1\": 0, \"x2\": 10, \"y2\": 10";
                default -> "\"width\": 8, \"height\": 8";
            };
            CanvasElement element = read("{ \"type\": \"" + type + "\", \"id\": \"named\", "
                    + "\"order\": 3, \"dev\": true, \"requires\": \"first_steps\", " + body + " }");

            assertEquals("named", element.id(), type);
            assertEquals(type, element.type(), type);
            assertEquals(3, element.order(), type);
            assertTrue(element.dev(), type);
            assertEquals("first_steps", element.requires().orElseThrow(), type);
        }
    }

    @Test
    @DisplayName("the defaults are what a minimal element gets: order 0, not dev, no gate")
    void theDefaults() {
        CanvasElement element = read("{ \"type\": \"rect\", \"id\": \"box\" }");
        assertEquals(0, element.order());
        assertFalse(element.dev());
        assertTrue(element.requires().isEmpty());

        CanvasElement.Rect rect = assertInstanceOf(CanvasElement.Rect.class, element);
        assertEquals(32, rect.width(), "a box with no size is the default box rather than no box");
        assertEquals(32, rect.height());
        assertEquals(Argb.NONE, rect.fillColor(), "and it names no colours, so it draws nothing");
        assertEquals(0, rect.borderWidth());
    }

    @Test
    @DisplayName("an id is required, because it is what a translation and an edit name it by")
    void anElementWithoutAnIdIsRefused() {
        assertTrue(refuses("{ \"type\": \"rect\" }"));
        assertTrue(refuses("{ \"type\": \"rect\", \"id\": null }"));
    }

    // ------------------------------------------------------------------
    // Each arm
    // ------------------------------------------------------------------

    @Test
    @DisplayName("a picture carries every field the legacy format writes on a chapter image")
    void theImageArm() {
        // The one arm with a compatibility obligation, so this is the field list rather than a sample: each
        // of these is a key a converted chapter has to land in, and a missing one is a picture that arrives
        // somewhere else, at some other size, or not at all.
        CanvasElement.Image image = assertInstanceOf(CanvasElement.Image.class, read("""
                { "type": "image", "id": "chapter_four", "order": 2,
                  "x": 64, "y": 32, "width": 333, "height": 64,
                  "rotation": 270, "corner": true, "locked": true,
                  "image": { "texture": "atm:textures/questpics/chap4.png" },
                  "tint": "#80FF0000", "alpha": 200,
                  "title": { "translate": "element.chapter_four.title", "fallback": "Chapter 4" },
                  "label": { "onImage": true, "shadow": true, "inset": 4, "hAlign": "start",
                             "vAlign": "end" },
                  "click": { "type": "open_uri", "data": "https://example.invalid" },
                  "dev": true, "requires": "the_core" }
                """));

        assertEquals(64, image.x());
        assertEquals(32, image.y());
        assertEquals(333, image.width());
        assertEquals(64, image.height());
        assertEquals(270, image.rotation());
        assertTrue(image.corner());
        assertTrue(image.locked(), "pinned against the editor's drag");
        assertEquals(new ImageSource.Texture(
                net.minecraft.resources.Identifier.parse("atm:textures/questpics/chap4.png")),
                image.image());
        assertEquals(0x80FF0000, image.tint());
        assertEquals(200, image.alpha());
        assertEquals("Chapter 4", image.title().orElseThrow().fallback().orElseThrow());
        assertEquals(ElementLabel.TextAlign.START, image.label().orElseThrow().hAlign());
        assertEquals(ElementLabel.TextAlign.END, image.label().orElseThrow().vAlign());
        assertEquals(ClickAction.Type.OPEN_URI, image.click().type());
        assertTrue(image.dev());
        assertEquals("the_core", image.requires().orElseThrow());
    }

    @Test
    @DisplayName("a picture's source is one of a file or a sprite, and both survive a round trip")
    void theTwoImageArms() {
        assertEquals(new ImageSource.Sprite(
                        net.minecraft.resources.Identifier.parse("minecraft:block/sculk")),
                assertInstanceOf(CanvasElement.Image.class,
                        read("{ \"type\": \"image\", \"id\": \"s\", \"image\": "
                                + "{ \"sprite\": \"minecraft:block/sculk\" } }")).image());

        // Both keys at once reads as the file arm, which is the loader's usual leniency -- the codec cannot
        // refuse it without refusing the document, and `QuestValidator` is the half that reports it. Pinned
        // here so the validator's message and this behaviour stay describing the same thing.
        assertEquals(new ImageSource.Texture(
                        net.minecraft.resources.Identifier.parse("pack:textures/x.png")),
                assertInstanceOf(CanvasElement.Image.class,
                        read("{ \"type\": \"image\", \"id\": \"b\", \"image\": "
                                + "{ \"texture\": \"pack:textures/x.png\", \"sprite\": \"minecraft:air\" } }"))
                        .image());

        // Neither key is refused, because there is nothing to draw and no sensible value to invent.
        assertTrue(refuses("{ \"type\": \"image\", \"id\": \"n\", \"image\": {} }"));
    }

    @Test
    @DisplayName("a picture without a lock is an unlocked picture, which the drag may move")
    void lockedDefaultsToOff() {
        CanvasElement.Image image = assertInstanceOf(CanvasElement.Image.class,
                read("{ \"type\": \"image\", \"id\": \"s\", \"image\": "
                        + "{ \"sprite\": \"minecraft:block/sculk\" } }"));
        assertFalse(image.locked(), "absent is draggable: pinning is opt-in, like the draft flag");

        // And the pin survives the trip the tree and the replica both take.
        CanvasElement roundTripped = CanvasElement.fromJson(CanvasElement.asJson(image)).orElseThrow();
        assertFalse(assertInstanceOf(CanvasElement.Image.class, roundTripped).locked());
    }

    @Test
    @DisplayName("a label needs words, and reads a size, an ink and a shadow")
    void theTextArm() {
        CanvasElement.Text text = assertInstanceOf(CanvasElement.Text.class, read("""
                { "type": "text", "id": "heading", "x": -10, "y": -64,
                  "text": { "translate": "element.heading.text", "fallback": "Chapter 1" },
                  "scale": 1.5, "color": "#A0A0A0", "shadow": true, "fixed": true }
                """));
        assertEquals("Chapter 1", text.text().fallback().orElseThrow());
        assertEquals(1.5, text.scale());
        assertEquals(0xFFA0A0A0, text.color());
        assertTrue(text.shadow());
        assertTrue(text.fixed(), "its size fixed to the canvas rather than to the screen");

        // Absent is canvas-anchored: the default is what every label already is, so no file changes
        // meaning by gaining the field.
        assertFalse(assertInstanceOf(CanvasElement.Text.class,
                read("{ \"type\": \"text\", \"id\": \"plain\", \"text\": \"hi\" }")).fixed());

        // A label with no words is not a label: the field is required rather than defaulted, because a file
        // that forgot it would otherwise place an invisible element and say nothing.
        assertTrue(refuses("{ \"type\": \"text\", \"id\": \"empty\" }"));
    }

    @Test
    @DisplayName("a line's position is its two endpoints, and its heads are its own vocabulary")
    void theLineArm() {
        CanvasElement.Line line = assertInstanceOf(CanvasElement.Line.class, read("""
                { "type": "line", "id": "divider",
                  "x1": -224, "y1": 16, "x2": 128, "y2": 16, "width": 2, "arrowhead": "both" }
                """));
        assertEquals(-224, line.x1());
        assertEquals(16, line.y1());
        assertEquals(128, line.x2());
        assertEquals(16, line.y2());
        assertEquals(2, line.width());
        assertEquals(ArrowEnds.BOTH, line.arrowhead());
        assertTrue(line.arrowhead().atStart());
        assertTrue(line.arrowhead().atEnd());

        assertEquals(ArrowEnds.NONE, assertInstanceOf(CanvasElement.Line.class,
                read("{ \"type\": \"line\", \"id\": \"l\" }")).arrowhead(), "a blunt rule by default");
        assertFalse(ArrowEnds.END.atStart(), "an end head does not also place a start head");
        assertFalse(ArrowEnds.START.atEnd());
        assertFalse(ArrowEnds.NONE.atStart());
        assertFalse(ArrowEnds.NONE.atEnd());
        assertTrue(refuses("{ \"type\": \"line\", \"id\": \"l\", \"arrowhead\": \"around\" }"),
                "the placement a dependency line uses is not this vocabulary");
    }

    @Test
    @DisplayName("a box reads two colours and a border width, and defaults to drawing nothing")
    void theRectArm() {
        CanvasElement.Rect rect = assertInstanceOf(CanvasElement.Rect.class, read("""
                { "type": "rect", "id": "tier_one", "x": -224, "y": -96, "width": 352, "height": 224,
                  "fillColor": "#40101018", "borderColor": "#66204060", "borderWidth": 1 }
                """));
        assertEquals(0x40101018, rect.fillColor());
        assertEquals(0x66204060, rect.borderColor());
        assertEquals(1, rect.borderWidth());
        assertEquals(352, rect.width());
    }

    // ------------------------------------------------------------------
    // The numbers that are easy to get quietly wrong
    // ------------------------------------------------------------------

    @Test
    @DisplayName("a rotation wraps, so -90 and 630 are the quarter turn 270 is")
    void rotationWrapsRatherThanClamping() {
        // The difference between wrapping and clamping is the whole of this test, and it is a picture rather
        // than a number: a clamped -90 reads as NO turn, which is the opposite of what the file says, and
        // the legacy format writes exactly -90 for a picture turned anticlockwise.
        assertEquals(270, image("-90").rotation());
        assertEquals(270, image("630").rotation());
        assertEquals(270, image("270").rotation());
        assertEquals(0, image("360").rotation());
        assertEquals(1, image("361").rotation());
        assertEquals(359, image("-1").rotation());
        assertEquals(8, image("8").rotation(), "and a whole-degree turn is itself");
    }

    /** One picture with a rotation spelled a given way, decoded and cast, since the seam is the interface. */
    private static CanvasElement.Image image(String written) {
        return assertInstanceOf(CanvasElement.Image.class,
                read("{ \"type\": \"image\", \"id\": \"i\", \"rotation\": " + written + ", "
                        + "\"image\": { \"sprite\": \"minecraft:block/sculk\" } }"));
    }

    @Test
    @DisplayName("an out-of-range number is clamped to the nearest legal one rather than refusing the file")
    void numbersClamp() {
        // `Codecs.clampedInt`'s argument, applied here: a size, an alpha and a border width are presentation
        // decisions with a nearest sensible value, so one wrong number in one element must not cost the
        // author every quest in the chapter. The validator reports the range; the codec keeps the file.
        CanvasElement.Rect wide = assertInstanceOf(CanvasElement.Rect.class,
                read("{ \"type\": \"rect\", \"id\": \"r\", \"width\": 99999, \"height\": 0, "
                        + "\"borderWidth\": 99 }"));
        assertEquals(CanvasElement.Rect.MAX_EDGE, wide.width());
        assertEquals(CanvasElement.Rect.MIN_EDGE, wide.height(), "a box of no size is one pixel, not zero");
        assertEquals(CanvasElement.Rect.MAX_BORDER, wide.borderWidth());

        CanvasElement.Image alpha = assertInstanceOf(CanvasElement.Image.class,
                read("{ \"type\": \"image\", \"id\": \"i\", \"alpha\": 900, \"width\": -5, "
                        + "\"image\": { \"sprite\": \"minecraft:air\" } }"));
        assertEquals(CanvasElement.Image.MAX_ALPHA, alpha.alpha());
        assertEquals(CanvasElement.Image.MIN_EDGE, alpha.width());

        CanvasElement.Text text = assertInstanceOf(CanvasElement.Text.class,
                read("{ \"type\": \"text\", \"id\": \"t\", \"text\": \"x\", \"scale\": 99 }"));
        assertEquals(CanvasElement.Text.MAX_SCALE, text.scale());

        CanvasElement.Line thin = assertInstanceOf(CanvasElement.Line.class,
                read("{ \"type\": \"line\", \"id\": \"l\", \"width\": 0 }"));
        assertEquals(CanvasElement.Line.MIN_WIDTH, thin.width());
    }

    @Test
    @DisplayName("a colour that is not a colour refuses the element, and the message says what one is")
    void coloursAreStrict() {
        // Strict because the validator runs first and names the field with a line -- see Argb's own note.
        // A codec that invented transparency for a typo would hide it behind a box that never appears.
        var failure = CanvasElement.CODEC.parse(JsonOps.INSTANCE, JsonParser.parseString(
                "{ \"type\": \"rect\", \"id\": \"r\", \"fillColor\": \"#A0A0A\" }"));
        assertTrue(failure.error().isPresent(), "a five-digit colour is not a colour");
        assertTrue(failure.error().orElseThrow().message().contains("#RRGGBB"), "and says so: "
                + failure.error().orElseThrow().message());
        assertTrue(refuses("{ \"type\": \"rect\", \"id\": \"r\", \"fillColor\": [1, 2, 3] }"));
    }

    // ------------------------------------------------------------------
    // The action vocabulary
    // ------------------------------------------------------------------

    @Test
    @DisplayName("all seven actions the legacy format can write are readable, and all seven can run")
    void theClickVocabulary() {
        // Reading every name is what makes the validator's per-type checks possible: a codec that
        // dropped one would leave a converted pack with a dead button and nothing said about it --
        // the failure mode T6 of the migration plan exists to prevent.
        for (ClickAction.Type type : ClickAction.Type.values()) {
            String name = type.name().toLowerCase(java.util.Locale.ROOT);
            CanvasElement.Image image = assertInstanceOf(CanvasElement.Image.class,
                    read("{ \"type\": \"image\", \"id\": \"i\", "
                            + "\"image\": { \"sprite\": \"minecraft:air\" }, "
                            + "\"click\": { \"type\": \"" + name + "\", \"data\": \"d\" } }"));
            assertEquals(type, image.click().type(), name);
            assertEquals("d", image.click().data(), name);
        }

        Set<ClickAction.Type> runnable = java.util.Arrays.stream(ClickAction.Type.values())
                .filter(ClickAction.Type::supported)
                .collect(java.util.stream.Collectors.toSet());
        assertEquals(java.util.EnumSet.allOf(ClickAction.Type.class),
                runnable, "every name runs: the set is pinned so a future action that cannot run fails here");

        // A name no version has is refused by the codec, which names the seven there are. On an image, since
        // that is the arm that has a `click` at all -- a field on the wrong arm is silently ignored by the
        // codec, which is a different property and is the validator's to report.
        var failure = CanvasElement.CODEC.parse(JsonOps.INSTANCE, JsonParser.parseString(
                "{ \"type\": \"image\", \"id\": \"i\", \"image\": { \"sprite\": \"minecraft:air\" }, "
                        + "\"click\": { \"type\": \"teleport\" } }"));
        assertTrue(failure.error().orElseThrow().message().contains("open_quest"),
                "the error lists the names that work: " + failure.error().orElseThrow().message());

        assertEquals(ClickAction.NONE, image("0").click(), "and none is the default, on the arm that has one");
    }

    // ------------------------------------------------------------------
    // An unknown type, and the fields around it
    // ------------------------------------------------------------------

    @Test
    @DisplayName("an element of a type this build does not know is kept, with everything it can still read")
    void anUnknownTypeSurvives() {
        CanvasElement element = read("""
                { "type": "some_addon:badge", "id": "welcome", "order": 4, "dev": true,
                  "requires": "first_steps", "glow": 12 }
                """);

        CanvasElement.Unknown unknown = assertInstanceOf(CanvasElement.Unknown.class, element);
        assertEquals("some_addon:badge", unknown.type(), "the type is kept as it was written");
        assertEquals("welcome", element.id(), "and so is everything the common codec can read");
        assertEquals(4, element.order());
        assertTrue(element.dev());
        assertEquals("first_steps", element.requires().orElseThrow());
        assertEquals(element, element.translated(10, 10),
                "an unknown element cannot be moved, because which of its fields are positions is exactly"
                        + " what this build does not know");

        // It writes back, so a round trip through the tree does not lose the type.
        assertEquals(element, read(rewrite(element)));
    }

    @Test
    @DisplayName("the fields one type allows are that type's, and an unknown type allows none")
    void theFieldSets() {
        // Per type rather than a union, and the difference is the quality of the report: an `x1` on a box is
        // a mistake worth naming, and a union would have accepted it because a line legitimises the name.
        assertTrue(CanvasElement.fieldsOf(CanvasElement.TYPE_LINE).contains("x1"));
        assertFalse(CanvasElement.fieldsOf(CanvasElement.TYPE_RECT).contains("x1"),
                "a line's endpoint is not a box's field");
        assertFalse(CanvasElement.fieldsOf(CanvasElement.TYPE_IMAGE).contains("fillColor"));
        for (String type : List.of(CanvasElement.TYPE_IMAGE, CanvasElement.TYPE_TEXT, CanvasElement.TYPE_LINE,
                CanvasElement.TYPE_RECT)) {
            assertTrue(CanvasElement.fieldsOf(type).containsAll(CanvasElement.FIELDS),
                    type + " carries the common fields too");
        }
        assertTrue(CanvasElement.fieldsOf("some_addon:badge").isEmpty(),
                "an unknown type's payload is not checked field by field, because this build cannot know"
                        + " what belongs there");

        // The union is for the schema comparison, and it is the arms' fields plus the common ones.
        Set<String> all = CanvasElement.allFields();
        assertTrue(all.containsAll(CanvasElement.FIELDS));
        assertTrue(all.containsAll(CanvasElement.Image.FIELDS));
        assertTrue(all.containsAll(CanvasElement.Rect.FIELDS));
        assertEquals(31, all.size(), "four arms and the common set, minus the fields they share: " + all);
    }

    @Test
    @DisplayName("an element reads and writes the same object, through the tree as well as a file")
    void theWireShapeIsTheFileShape() {
        // The tree carries elements by encoding them with this same codec, which is what makes a field added
        // to an arm travel without anybody remembering to send it. This is that property, stated once.
        CanvasElement original = read("""
                { "type": "image", "id": "logo", "order": 1, "x": 8, "y": -8, "width": 64, "height": 64,
                  "rotation": 8, "corner": true, "image": { "sprite": "minecraft:block/sculk" },
                  "tint": "#80FFFFFF", "alpha": 128,
                  "title": { "translate": "element.logo.title", "fallback": "The Orrery" },
                  "label": { "onImage": true, "hAlign": "end" },
                  "click": { "type": "open_quest", "data": "the_core" } }
                """);

        var json = CanvasElement.asJson(original);
        assertEquals(CanvasElement.TYPE_IMAGE, json.get("type").getAsString());
        assertEquals(original, CanvasElement.fromJson(json).orElseThrow());
        assertTrue(CanvasElement.fromJson(JsonParser.parseString("42")).isEmpty(),
                "and something that is not an element reads as nothing rather than throwing");
    }

    @Test
    @DisplayName("a move touches a box's two numbers and a line's four")
    void translationMovesWhatAPositionIs() {
        // The editor's one geometric operation, and the arms disagree about how many numbers a position is.
        // A line moved by its first endpoint only would stretch rather than move, which is a picture nobody
        // asked for and a fault the drag path could not see.
        CanvasElement.Rect rect = assertInstanceOf(CanvasElement.Rect.class,
                read("{ \"type\": \"rect\", \"id\": \"r\", \"x\": 10, \"y\": 20, \"width\": 4, \"height\": 6 }")
                        .translated(-5, 7));
        assertEquals(5, rect.x());
        assertEquals(27, rect.y());
        assertEquals(4, rect.width(), "a move is not a resize");
        assertEquals(6, rect.height());

        CanvasElement.Line line = assertInstanceOf(CanvasElement.Line.class,
                read("{ \"type\": \"line\", \"id\": \"l\", \"x1\": 0, \"y1\": 0, \"x2\": 10, \"y2\": 0 }")
                        .translated(3, 4));
        assertEquals(3, line.x1());
        assertEquals(4, line.y1());
        assertEquals(13, line.x2());
        assertEquals(4, line.y2());

        CanvasElement.Text text = assertInstanceOf(CanvasElement.Text.class,
                read("{ \"type\": \"text\", \"id\": \"t\", \"text\": \"x\", \"x\": 1, \"y\": 2 }")
                        .translated(10, 10));
        assertEquals(11, text.x());
        assertEquals(12, text.y());
    }
}
