package dev.ellipog.tenet.quest;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.mojang.serialization.Codec;
import com.mojang.serialization.DataResult;
import com.mojang.serialization.JsonOps;
import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;

import dev.ellipog.armature.api.data.Codecs;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * Something drawn on a chapter's canvas that is not a quest: a picture, a label, a line or a box.
 *
 * <pre>{@code
 * {
 *   "id": "the_core",
 *   "title": "The Core",
 *   "elements": [
 *     { "type": "rect",  "id": "tier_one_box",
 *       "x": -224, "y": -96, "width": 352, "height": 224,
 *       "fillColor": "#40101018", "borderColor": "#66204060", "borderWidth": 1 },
 *     { "type": "image", "id": "chapter_four", "x": 64, "y": 32, "width": 333, "height": 64,
 *       "image": { "texture": "atm:textures/questpics/creative_chap4.png" },
 *       "title": { "translate": "element.chapter_four.title", "fallback": "Chapter 4" } },
 *     { "type": "text",  "id": "chapter_one_label", "text": "Chapter 1: The Core",
 *       "scale": 1.5, "color": "#A0A0A0" },
 *     { "type": "line",  "id": "tier_divider", "x1": -224, "y1": 16, "x2": 128, "y2": 16,
 *       "width": 2, "arrowhead": "both" }
 *   ]
 * }
 * }</pre>
 *
 * <h2>Four things, and deliberately only four</h2>
 *
 * <p>An image, a text label, a line with optional arrowheads, and a filled box with a border. Every one of
 * them is a shape the seam can already draw — a blit, a line of text, a run of rectangles — and none of
 * them needs a subsystem: there is no renderer change here that is not a blit, no layout engine, and no
 * second font. The three drawn ones are <b>procedural on purpose</b>: an author labels a section, divides
 * a tier or groups a cluster of quests without opening an image editor or shipping an asset, which is what
 * makes a decorated chapter something a pack author does rather than something they commission.
 *
 * <h2>Images are the legacy chapter images, and the other three are ours</h2>
 *
 * <p>{@link Image} carries every field the legacy format writes on a {@code ChapterImage}, so a converted pack
 * loses nothing: the position, the size, the rotation about the centre (or the corner), the tint and its
 * separate alpha, the draw order, the visibility gate on a quest, the dev-only flag, the hover title and
 * the five properties of text painted on the picture. See {@code Argb}, {@link ImageSource} and
 * {@link ClickAction} for the three fields whose vocabulary needed its own decision.
 *
 * <p>The other three arms have no legacy counterpart at all — the legacy format has no procedural element — so they
 * are new surface with no compatibility question attached, and a converter never emits them.
 *
 * <h2>Decorations, and the one thing they can never do</h2>
 *
 * <p>An element is <b>not</b> content. It is never counted for a chapter's completion, it is never a
 * dependency, it holds no progress and it cannot gate anything — the only relation it has to progression
 * is {@code requires}, which reads a quest's state and changes nothing about it. That is what lets the
 * whole family be classified as a cosmetic edit by the editor, and it is the property to keep in mind when
 * adding a fifth one.
 *
 * <p>All of them draw <b>under</b> the dependency lines and the quest nodes, ordered among themselves by
 * {@code order} and then by the order they are declared in. Which is the legacy format's own arrangement, and the reason
 * a box can be a container for a group of quests rather than a lid over them.
 *
 * <h2>A closed set, and a placeholder for a type this build has never heard of</h2>
 *
 * <p>A sealed interface rather than an open registry: these four are the format, and an addon that wants a
 * fifth is a conversation about the format rather than a registration. {@link Unknown} exists for the same
 * reason {@code UnknownTask} does — one element naming a type from a newer build must not cost the author
 * every quest in the chapter — and it is strictly better than that placeholder in one respect: the common
 * fields are readable without the type's codec, so an unknown element still has an id, an order and a gate,
 * and the editor can still select and delete it.
 */
public sealed interface CanvasElement {

    /** The type name of a picture. */
    String TYPE_IMAGE = "image";

    /** The type name of a text label. */
    String TYPE_TEXT = "text";

    /** The type name of a line or arrow. */
    String TYPE_LINE = "line";

    /** The type name of a filled box. */
    String TYPE_RECT = "rect";

    /**
     * The fields every element declares, whatever its type.
     *
     * <p>Grouped into {@link Common} for the mundane reason that record gives — the codec's sixteen
     * components — and the real one: these four are about the element's <i>identity and sight</i> rather
     * than about what it draws. {@link Image} would otherwise be exactly sixteen components, which is the
     * limit and no headroom; the grouping is what every arm then shares.
     */
    Set<String> FIELDS = Set.of("type", "id", "order", "dev", "requires");

    /**
     * What every element carries: what it is called, when it draws, and what it waits for.
     *
     * <p>Flat in JSON rather than nested, because the codec is a {@link MapCodec} — an element says
     * {@code "order": 2}, not {@code "common": {"order": 2}}. The same choice {@link QuestLayout} makes for
     * a quest's position, and for the same reason: a file about where things go should not need a level of
     * nesting to say so.
     *
     * @param id       its own name within the chapter, unique there, and the key a translation uses
     * @param order    draw order among the chapter's elements; ties go to declaration order
     * @param dev      whether it is drawn only while the editor's dev mode is on
     * @param requires a quest id or alias: drawn only once that quest is completed
     */
    record Common(String id, int order, boolean dev, Optional<String> requires) {

        /** The fields this contributes, for the validator to allow at element level. */
        public static final Set<String> FIELDS = Set.of("id", "order", "dev", "requires");

        public static final MapCodec<Common> MAP_CODEC = RecordCodecBuilder.mapCodec(instance -> instance.group(
                Codec.STRING.fieldOf("id").forGetter(Common::id),
                Codec.INT.optionalFieldOf("order", 0).forGetter(Common::order),
                Codec.BOOL.optionalFieldOf("dev", false).forGetter(Common::dev),
                // A reference, so it is validated rather than resolved here: whether the quest it names
                // exists is a question about the whole tree, which this record cannot see.
                Codec.STRING.optionalFieldOf("requires").forGetter(Common::requires)
        ).apply(instance, Common::new));
    }

    /** The common fields, so an arm's own accessors can be defaults rather than five copies. */
    Common common();

    /** Which arm this is: the value of the {@code "type"} field. */
    String type();

    default String id() {
        return common().id();
    }

    default int order() {
        return common().order();
    }

    default boolean dev() {
        return common().dev();
    }

    default Optional<String> requires() {
        return common().requires();
    }

    /**
     * This element moved by a delta.
     *
     * <p>The one geometric operation the editor performs uniformly: a drag moves every element the same
     * way, and the arms differ only in how many numbers a position is — two for a box, four for a line.
     * Returned rather than mutated, because the records are immutable and an editor that mutated in place
     * would be one more thing the undo history has to know about.
     */
    CanvasElement translated(int dx, int dy);

    /**
     * The same element with a different box, for the three arms that have one.
     *
     * <h2>Why these live on the model rather than in the editor</h2>
     *
     * <p>Because a record has no {@code with}, and the alternative is a thirteen-component constructor call at
     * every call site — a resize, a rotate, and any future gesture — where one wrong argument is a silently
     * transposed field. Naming the four that move keeps the construction noise in the file that declares the
     * components, and it is the same argument {@link #translated} already makes.
     *
     * <p>An arm that has no box is not given a method here: a label's box is its measured text, so a
     * "different box" for one would be a size nothing could honour.
     */
    CanvasElement withBox(int x, int y, int width, int height);

    /**
     * The same element with a different angle, in degrees.
     *
     * <p>Only a picture turns, which is what the interface default refuses for every other arm: a rotated box
     * would need its own corner arithmetic everywhere the box is used, and a rotated line is two points that
     * an author can simply place. The legacy format agrees — {@code rotation} is on the image and nowhere else.
     */
    default CanvasElement withRotation(int degrees) {
        return this;
    }

    /**
     * A picture: the legacy chapter image, in every field it carries.
     *
     * <p>{@code x} and {@code y} are the <b>top-left</b> of the image in canvas pixels, which is the
     * convention a quest node uses — so a grid position means the same thing for both, and a converter has
     * one centre-to-corner rule rather than two. {@code width} and {@code height} stretch the picture to
     * fill that box; nothing preserves its aspect ratio, which is also what legacy packs do.
     *
     * @param rotation degrees clockwise, wrapped into 0..359 — so {@code -90}, {@code 270} and {@code 630}
     *                 all read as one quarter turn. Wrapping rather than clamping is {@code wrappedInt}'s
     *                 argument, and it matters here more than anywhere: a clamped {@code -90} is <i>no</i>
     *                 turn, which is the opposite of what the file says.
     * @param corner   whether the turn is about this element's own top-left corner instead of its centre.
     *                 the legacy {@code alignToCorner}, kept under its own reading rather than dropped as
     *                 cosmetic: a real pack uses it on a logo turned slightly off square, and the two
     *                 pivots are visibly different pictures.
     * @param locked   whether the editor's drag leaves this picture where it is. The legacy
     *                 {@code position_locked}: a pinned logo survives a marquee drag and a stray grip grab.
     *                 Readers are unaffected — this gates gestures, not visibility — and a deliberate edit
     *                 still writes, because a lock that could never be opened on purpose would be a picture
     *                 nobody could move. The migration tool maps the legacy spelling onto this key.
     * @param tint     multiplied into the picture, RGB and alpha alike. <b>White is the identity</b>, which
     *                 is its default, and a tint that carries an alpha <i>multiplies</i> with {@code alpha}
     *                 rather than replacing it — so "fade this" has one meaning whichever of the two fields
     *                 an author reaches for. the legacy format's own writer is narrower than that and the difference is a
     *                 converter's problem rather than a reader's: it writes {@code Color4I.rgb()} — no alpha
     *                 in the number at all — and draws the tint with the {@code alpha} field <i>replacing</i>
     *                 the colour's own alpha. So a converted picture is written {@code #FFRRGGBB}, and with
     *                 an opaque tint this format's multiplication and the legacy replacement are the same sum.
     * @param alpha    0..255, multiplied with {@code tint}'s own alpha. Carried separately because the
     *                 legacy format carries it separately, and because a pack that dims a picture should not have to
     *                 rewrite its colour to say so
     * @param title    a hover tooltip, or text painted on the picture when {@code label} says so
     * @param label    how that text is painted, when it is
     * @param click    what pressing it does. See {@link ClickAction} for the four names this build refuses.
     */
    record Image(Common common, int x, int y, int width, int height, int rotation, boolean corner,
                 boolean locked, ImageSource image, int tint, int alpha, Optional<QuestText> title,
                 Optional<ElementLabel> label, ClickAction click) implements CanvasElement {

        /** The fields this arm adds. */
        public static final Set<String> FIELDS = Set.of(
                "x", "y", "width", "height", "rotation", "corner", "locked", "image", "tint", "alpha",
                "title", "label", "click");

        /**
         * The edge bounds, in pixels.
         *
         * <p>Bounded because the canvas draws at a fixed scale, so a four-thousand-pixel picture is a typo
         * for forty rather than a design — and clamped rather than range-checked for the reason
         * {@link QuestLayout} gives: refusing the file costs every quest in the chapter, and the nearest
         * legal size is the honest reading of a number an author got wrong.
         */
        public static final int MIN_EDGE = 1;
        public static final int MAX_EDGE = 4096;

        /** the legacy alpha range, and the reason both ends are inclusive. */
        public static final int MIN_ALPHA = 0;
        public static final int MAX_ALPHA = 255;

        public static final MapCodec<Image> MAP_CODEC = RecordCodecBuilder.mapCodec(instance -> instance.group(
                Common.MAP_CODEC.forGetter(Image::common),
                Codec.INT.optionalFieldOf("x", 0).forGetter(Image::x),
                Codec.INT.optionalFieldOf("y", 0).forGetter(Image::y),
                Codecs.clampedInt(MIN_EDGE, MAX_EDGE).optionalFieldOf("width", 32).forGetter(Image::width),
                Codecs.clampedInt(MIN_EDGE, MAX_EDGE).optionalFieldOf("height", 32).forGetter(Image::height),
                Codecs.wrappedInt(360).optionalFieldOf("rotation", 0).forGetter(Image::rotation),
                Codec.BOOL.optionalFieldOf("corner", false).forGetter(Image::corner),
                Codec.BOOL.optionalFieldOf("locked", false).forGetter(Image::locked),
                ImageSource.CODEC.fieldOf("image").forGetter(Image::image),
                Argb.CODEC.optionalFieldOf("tint", Argb.WHITE).forGetter(Image::tint),
                Codecs.clampedInt(MIN_ALPHA, MAX_ALPHA).optionalFieldOf("alpha", MAX_ALPHA)
                        .forGetter(Image::alpha),
                QuestText.CODEC.optionalFieldOf("title").forGetter(Image::title),
                ElementLabel.CODEC.optionalFieldOf("label").forGetter(Image::label),
                ClickAction.CODEC.optionalFieldOf("click", ClickAction.NONE).forGetter(Image::click)
        ).apply(instance, Image::new));

        @Override
        public String type() {
            return TYPE_IMAGE;
        }

        @Override
        public Image translated(int dx, int dy) {
            return new Image(common, x + dx, y + dy, width, height, rotation, corner, locked, image, tint,
                    alpha, title, label, click);
        }

        @Override
        public Image withBox(int x, int y, int width, int height) {
            return new Image(common, x, y, width, height, rotation, corner, locked, image, tint, alpha, title,
                    label, click);
        }

        @Override
        public Image withRotation(int degrees) {
            return new Image(common, x, y, width, height, degrees, corner, locked, image, tint, alpha, title,
                    label, click);
        }
    }

    /**
     * A label: one run of text, at a size and a colour.
     *
     * <p>{@code x} and {@code y} are the top-left of the first line, and a {@code \n} in the text starts
     * another. Plain text with no markup: the field list has no emphasis in it, and an author who types
     * {@code **bold**} gets asterisks rather than a surprise about which renderer they reached.
     *
     * @param scale  multiplies the font's own size, and the player's text-scale setting on top of that
     * @param shadow the font's own drop shadow. Off by default, because a backdrop is the usual answer and
     *               both at once is a smudge; on is what a label floating over a chapter needs, and it is
     *               the reason the seam grew a second text call rather than a boolean on the first.
     * @param fixed  fix the size to the canvas: positioned with the canvas like anything on it, but
     *               drawn at the file's own size times the zoom, the way a node is. Off by default, when
     *               the size stays the file's own on screen at every zoom. (It used to also hold the
     *               position at its zoom-one landing; that half never survived a real zoom gesture, whose
     *               own pan rewrite slid the label about, so the flag is the size and nothing else now.)
     */
    record Text(Common common, int x, int y, QuestText text, double scale, int color, boolean shadow,
                boolean fixed)
            implements CanvasElement {

        /** The fields this arm adds. */
        public static final Set<String> FIELDS = Set.of("x", "y", "text", "scale", "color", "shadow",
                "fixed");

        /**
         * The scale bounds.
         *
         * <p>Wide enough for a section heading and bounded at the bottom so a label cannot vanish into a
         * sub-pixel smudge a reader would report as missing.
         */
        public static final double MIN_SCALE = 0.25;
        public static final double MAX_SCALE = 4.0;

        public static final MapCodec<Text> MAP_CODEC = RecordCodecBuilder.mapCodec(instance -> instance.group(
                Common.MAP_CODEC.forGetter(Text::common),
                Codec.INT.optionalFieldOf("x", 0).forGetter(Text::x),
                Codec.INT.optionalFieldOf("y", 0).forGetter(Text::y),
                // Required, unlike every other arm's content: a label with no words is not a label, and a
                // file that meant to place one and forgot the text is worth being told about.
                QuestText.CODEC.fieldOf("text").forGetter(Text::text),
                Codecs.clampedDouble(MIN_SCALE, MAX_SCALE).optionalFieldOf("scale", 1.0)
                        .forGetter(Text::scale),
                Argb.CODEC.optionalFieldOf("color", Argb.WHITE).forGetter(Text::color),
                Codec.BOOL.optionalFieldOf("shadow", false).forGetter(Text::shadow),
                Codec.BOOL.optionalFieldOf("fixed", false).forGetter(Text::fixed)
        ).apply(instance, Text::new));

        @Override
        public String type() {
            return TYPE_TEXT;
        }

        @Override
        public Text translated(int dx, int dy) {
            return new Text(common, x + dx, y + dy, text, scale, color, shadow, fixed);
        }

        /**
         * The same label at a different position, and <b>the same size</b>.
         *
         * <p>The width and height a caller passes are ignored, and that is the honest answer rather than a
         * shrug: a label's box is its measured text, so there is no size field for a resize to write. A
         * gesture that tried would be writing a number the loader does not read.
         */
        @Override
        public Text withBox(int x, int y, int width, int height) {
            return new Text(common, x, y, text, scale, color, shadow, fixed);
        }
    }

    /**
     * A line between two points, with an optional head at either end.
     *
     * <p>Position is the two endpoints rather than an origin and a size, because that is what a line is and
     * what an author drags. A drag moves all four numbers together, which is what
     * {@link #translated(int, int)} does.
     *
     * @param width     the line's thickness in pixels, 1..16. Bounded because the head grows with it, so a
     *                  width of two hundred is an arrow the size of the canvas
     * @param arrowhead which ends carry a head; {@code none} is a plain rule
     */
    record Line(Common common, int x1, int y1, int x2, int y2, int width, int color, ArrowEnds arrowhead)
            implements CanvasElement {

        /** The fields this arm adds. */
        public static final Set<String> FIELDS =
                Set.of("x1", "y1", "x2", "y2", "width", "color", "arrowhead");

        /** The thickness the head is also sized by, so the bound is about the arrow rather than the line. */
        public static final int MIN_WIDTH = 1;
        public static final int MAX_WIDTH = 16;

        public static final MapCodec<Line> MAP_CODEC = RecordCodecBuilder.mapCodec(instance -> instance.group(
                Common.MAP_CODEC.forGetter(Line::common),
                Codec.INT.optionalFieldOf("x1", 0).forGetter(Line::x1),
                Codec.INT.optionalFieldOf("y1", 0).forGetter(Line::y1),
                Codec.INT.optionalFieldOf("x2", 0).forGetter(Line::x2),
                Codec.INT.optionalFieldOf("y2", 0).forGetter(Line::y2),
                Codecs.clampedInt(MIN_WIDTH, MAX_WIDTH).optionalFieldOf("width", MIN_WIDTH)
                        .forGetter(Line::width),
                Argb.CODEC.optionalFieldOf("color", Argb.WHITE).forGetter(Line::color),
                ArrowEnds.CODEC.optionalFieldOf("arrowhead", ArrowEnds.NONE).forGetter(Line::arrowhead)
        ).apply(instance, Line::new));

        @Override
        public String type() {
            return TYPE_LINE;
        }

        @Override
        public Line translated(int dx, int dy) {
            return new Line(common, x1 + dx, y1 + dy, x2 + dx, y2 + dy, width, color, arrowhead);
        }

        /**
         * The same line, its two endpoints replaced by the box's corners.
         *
         * <p>{@code x, y} is the first endpoint and {@code x + boxWidth, y + boxHeight} the second, which is
         * what makes the box a line's own two points rather than a rectangle around them — see
         * {@link #withEnds} for the gesture that actually moves one end on its own.
         *
         * <p><b>The parameters are named for the box and not for the record's fields</b>, and that is not
         * style: this arm has a {@code width} of its own — how thick the line is — so a parameter of the same
         * name would silently set the thickness to the box's width. It is the one transposition this whole
         * family of helpers exists to make impossible, and it happened anyway the first time this was
         * written.
         */
        @Override
        public Line withBox(int x, int y, int boxWidth, int boxHeight) {
            return new Line(common, x, y, x + boxWidth, y + boxHeight, width, color, arrowhead);
        }

        /** The same line with one or both endpoints moved, which is what a line's handles do. */
        public Line withEnds(int x1, int y1, int x2, int y2) {
            return new Line(common, x1, y1, x2, y2, width, color, arrowhead);
        }
    }

    /**
     * A filled box, with an optional border.
     *
     * <p>The container an author puts behind a cluster of quests. Both colours default to fully transparent,
     * so a box a file declares without colours draws nothing at all — which is the honest reading of a
     * field nobody filled in, and the reason the <i>editor's</i> "add a box" writes a visible wash rather
     * than relying on the codec's default.
     *
     * @param borderWidth 0..16. Zero draws no border, and the fill is drawn first so a border sits over it
     */
    record Rect(Common common, int x, int y, int width, int height, int fillColor, int borderColor,
                int borderWidth) implements CanvasElement {

        /** The fields this arm adds. */
        public static final Set<String> FIELDS = Set.of(
                "x", "y", "width", "height", "fillColor", "borderColor", "borderWidth");

        /**
         * The box bounds, wider than a picture's because a container has to hold a whole tier.
         *
         * <p>Still bounded, and for the reason {@link #MAX_EDGE} is: a box is drawn as a fill and an inset
         * fill, so a hundred-thousand-pixel one is one very large rectangle rather than a crash — but it is
         * certainly a typo, and clamping is what keeps the canvas's culling meaningful.
         */
        public static final int MIN_EDGE = 1;
        public static final int MAX_EDGE = 8192;

        /** How thick a border may be. Past this it is not a border, it is a second box. */
        public static final int MIN_BORDER = 0;
        public static final int MAX_BORDER = 16;

        public static final MapCodec<Rect> MAP_CODEC = RecordCodecBuilder.mapCodec(instance -> instance.group(
                Common.MAP_CODEC.forGetter(Rect::common),
                Codec.INT.optionalFieldOf("x", 0).forGetter(Rect::x),
                Codec.INT.optionalFieldOf("y", 0).forGetter(Rect::y),
                Codecs.clampedInt(MIN_EDGE, MAX_EDGE).optionalFieldOf("width", 32).forGetter(Rect::width),
                Codecs.clampedInt(MIN_EDGE, MAX_EDGE).optionalFieldOf("height", 32).forGetter(Rect::height),
                Argb.CODEC.optionalFieldOf("fillColor", Argb.NONE).forGetter(Rect::fillColor),
                Argb.CODEC.optionalFieldOf("borderColor", Argb.NONE).forGetter(Rect::borderColor),
                Codecs.clampedInt(MIN_BORDER, MAX_BORDER).optionalFieldOf("borderWidth", 0)
                        .forGetter(Rect::borderWidth)
        ).apply(instance, Rect::new));

        @Override
        public String type() {
            return TYPE_RECT;
        }

        @Override
        public Rect translated(int dx, int dy) {
            return new Rect(common, x + dx, y + dy, width, height, fillColor, borderColor, borderWidth);
        }

        @Override
        public Rect withBox(int x, int y, int width, int height) {
            return new Rect(common, x, y, width, height, fillColor, borderColor, borderWidth);
        }
    }

    /**
     * An element whose type this build has never heard of, kept rather than thrown away.
     *
     * <pre>{@code { "type": "some_addon:badge", "id": "welcome", "x": 40, "y": 40 } }</pre>
     *
     * <h2>Why the common fields survive here, and do not on an unknown task</h2>
     *
     * <p>{@code UnknownTask} can keep nothing but its type, because a task's settings are read by the type's
     * own codec and there is none. The four fields every element shares are not like that: they are read
     * from the same object by the same codec whatever the type is, so an unknown badge still has an id, an
     * order, a dev flag and a gate — which means an author can still see it in the element list, order it,
     * and delete it. Only what it <i>draws</i> is unavailable, and that is the honest loss.
     *
     * <p>The payload's own fields are not carried: a {@link MapCodec} never sees the raw object. They are
     * not lost either — the editor writes back the tree it read, so everything this build does not
     * understand is still on disk after a save. That is {@code TypeDispatch}'s argument, and it holds here
     * unchanged.
     */
    record Unknown(Common common, String type) implements CanvasElement {

        /**
         * The codec for one unknown type.
         *
         * <p>Closes over the type rather than reading it, because the dispatch has already consumed it: a
         * map codec that read {@code "type"} again would be reading a field the dispatch owns.
         */
        static MapCodec<Unknown> codec(String type) {
            return RecordCodecBuilder.mapCodec(instance -> instance.group(
                    Common.MAP_CODEC.forGetter(Unknown::common)
            ).apply(instance, common -> new Unknown(common, type)));
        }

        @Override
        public Unknown translated(int dx, int dy) {
            // Nothing to move: the arm has no geometry, because the geometry of an unknown type is written
            // in fields this build cannot read. Moving it would mean guessing which of them are positions.
            return this;
        }

        @Override
        public Unknown withBox(int x, int y, int width, int height) {
            // And nothing to resize, for the same reason: a gesture that wrote `width` and `height` here would
            // be writing fields this arm may not even have.
            return this;
        }
    }

    /**
     * A list of elements as a canvas draws them: by {@code order}, ties by declaration.
     *
     * <h2>Why this is here rather than at each of the four callers</h2>
     *
     * <p>Because four things need the same answer and two of them are a picture and a hit test. The canvas
     * draws this list forwards; picking an element walks it <b>backwards</b>, because the one drawn last is
     * the one on top; the tree sends a chapter's elements in declaration order and the client sorts them
     * with this; and the chapter tab lists the same order. A caller that sorted for itself, or that walked
     * the declaration list, would pick a different element than the one the author can see — a fault with
     * no visible cause, since both orders are plausible.
     *
     * <p><b>The sort is stable and that is load-bearing.</b> Two elements may declare the same
     * {@code order} — a file written by hand need not number them at all — and the tie is broken by which
     * one the author wrote first, which is the only tie-break that cannot surprise anybody. {@code List.sort}
     * is a stable sort by contract, so ordering by the key alone gives exactly that; a comparator with a
     * second term would be the same answer written out twice.
     */
    static List<CanvasElement> inDrawOrder(List<CanvasElement> elements) {
        if (elements.size() < 2) {
            return elements;
        }
        java.util.List<CanvasElement> sorted = new java.util.ArrayList<>(elements);
        sorted.sort(java.util.Comparator.comparingInt(CanvasElement::order));
        return List.copyOf(sorted);
    }

    /**
     * Every arm, as one codec: the {@code "type"} field chooses the rest.
     *
     * <p>Written out rather than built from a registry, because the set is closed — see the class note. An
     * unregistered type decodes to {@link Unknown} rather than failing, which is what keeps one odd element
     * from costing the author the chapter.
     */
    Codec<CanvasElement> CODEC = Codec.STRING.dispatch("type", CanvasElement::type, CanvasElement::codecFor);

    /**
     * The codec for one type name, which is where the closed set is spelled out.
     *
     * <p>A {@link MapCodec} rather than a codec, because that is what {@code Codec.dispatch} takes in this
     * version of the library — the trap {@code TypeDispatch} documents at length, where the older shape
     * takes a {@code Codec} and every example online is written against it.
     */
    static MapCodec<? extends CanvasElement> codecFor(String type) {
        MapCodec<? extends CanvasElement> chosen = switch (type) {
            case TYPE_IMAGE -> Image.MAP_CODEC;
            case TYPE_TEXT -> Text.MAP_CODEC;
            case TYPE_LINE -> Line.MAP_CODEC;
            case TYPE_RECT -> Rect.MAP_CODEC;
            default -> Unknown.codec(type);
        };
        return chosen;
    }

    /**
     * The fields one type's object may carry, or empty when the type is not one this build knows.
     *
     * <p>Empty for an unknown type, and that is the load-bearing part: the validator reports the unknown
     * type once and then has nothing to compare the payload against, so it stays quiet about fields it
     * could not possibly have an opinion on — the same treatment an unknown task gets. A union of every
     * arm's fields would have reported all of them.
     */
    static Set<String> fieldsOf(String type) {
        return switch (type) {
            case TYPE_IMAGE -> union(FIELDS, Image.FIELDS);
            case TYPE_TEXT -> union(FIELDS, Text.FIELDS);
            case TYPE_LINE -> union(FIELDS, Line.FIELDS);
            case TYPE_RECT -> union(FIELDS, Rect.FIELDS);
            default -> Set.of();
        };
    }

    /**
     * Every field any known element may carry, for a schema to be compared against.
     *
     * <p>Not what the validator uses — see {@link #fieldsOf(String)} — but what a test needs to hold the
     * published schema and this model in step, in both directions, the way {@code SchemaCoverageTest}
     * already holds the task and reward families.
     */
    static Set<String> allFields() {
        Set<String> all = new LinkedHashSet<>(FIELDS);
        for (Set<String> arm : List.of(Image.FIELDS, Text.FIELDS, Line.FIELDS, Rect.FIELDS)) {
            all.addAll(arm);
        }
        return Set.copyOf(all);
    }

    /** One element as the JSON object a chapter file and the synced tree both carry. */
    static JsonObject asJson(CanvasElement element) {
        DataResult<JsonElement> encoded = CODEC.encodeStart(JsonOps.INSTANCE, element);
        JsonElement written = encoded.result().orElseThrow(
                () -> new IllegalStateException("an element did not encode: " + element));
        if (!written.isJsonObject()) {
            throw new IllegalStateException("an element encoded as something other than an object: "
                    + written);
        }
        return written.getAsJsonObject();
    }

    /** One element read back from the object a file or the tree carries, or empty when it will not read. */
    static Optional<CanvasElement> fromJson(JsonElement json) {
        return CODEC.parse(JsonOps.INSTANCE, json).result();
    }

    private static Set<String> union(Set<String> first, Set<String> second) {
        Set<String> both = new LinkedHashSet<>(first);
        both.addAll(second);
        return Set.copyOf(both);
    }
}
