package dev.ellipog.tenet.client;

import dev.ellipog.armature.client.ArmatureTheme;
import dev.ellipog.armature.client.render.GuiRenderer;
import dev.ellipog.armature.client.ui.kit.Viewport;
import dev.ellipog.tenet.client.dev.LineArt;
import dev.ellipog.tenet.progress.QuestState;
import dev.ellipog.tenet.quest.Argb;
import dev.ellipog.tenet.quest.CanvasElement;
import dev.ellipog.tenet.quest.DependencyStyle;
import dev.ellipog.tenet.quest.ElementLabel;
import dev.ellipog.tenet.quest.ImageSource;
import dev.ellipog.tenet.quest.QuestText;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.Function;

/**
 * How one canvas element is drawn: a picture, a label, a line or a box.
 *
 * <h2>Why this is its own class, like {@code QuestNodeArt}</h2>
 *
 * <p>For the reason that class's own note gives, one level down: the canvas is a screen no test can
 * instantiate, so drawing that lives in it is drawing nothing can assert. Everything here takes a
 * {@link Frame} — a renderer and the four lookups this cannot answer for itself — which means a recording
 * renderer can be handed the same calls a client would get, and "a turned picture is wrapped in a turn",
 * "a box is two fills in this order" and "a shadowed label asked the seam for a shadow, at its own size"
 * become assertions rather than screenshots.
 *
 * <h2>What the caller decides and what this decides</h2>
 *
 * <p>The caller decides <i>whether</i>: which elements exist, which of them a reader may see, and where the
 * canvas is looking. This decides <i>how</i>: the box, the turn, the tint, the route, the ink. The four
 * things it cannot know arrive in the frame — the renderer, the viewport, a way to resolve a translatable
 * word, and a way to ask how far a quest has got — so nothing here reaches for a client singleton, and a
 * test answers all four with a constant.
 *
 * <p><b>The renderer is in the frame rather than passed beside it</b> for a reason that shows up in one arm
 * only: a label's box is its measured text, and measuring text is the font's answer rather than arithmetic
 * about a string. A class that guessed at it — six pixels a character — would place a hit box that disagrees
 * with the ink at every scale but one, and the disagreement would read as a label you cannot click.
 *
 * <h2>Decorations, and the one thing they are not</h2>
 *
 * <p>An element is drawn <b>under</b> the dependency lines and the quest nodes, which is the caller's
 * arrangement rather than this class's — and it is what makes a box a container for a cluster of quests
 * instead of a lid over them. Nothing here reads or changes a player's progress: {@code requires} asks how
 * far a quest has got and nothing else, which is the whole of the relation between a decoration and the
 * questline.
 */
public final class CanvasElementArt {

    private CanvasElementArt() {
    }

    /**
     * Everything about this frame that this class needs and does not hold.
     *
     * @param renderer what draws, and the only thing that can measure a label
     * @param view     the canvas's viewport, which turns a content coordinate into a screen one
     * @param words    a translatable text as the words to draw. The caller resolves it, because resolution
     *                 consults the pack's locale overlay and this class has no business knowing it exists
     * @param states   how far a quest has got, for an element's {@code requires} gate
     * @param devMode  whether this client may edit, for an element's {@code dev} flag
     */
    public record Frame(GuiRenderer renderer, Viewport view, Words words,
                        Function<String, QuestState> states, boolean devMode) {
    }

    /**
     * A translatable text, resolved by the caller.
     *
     * <h2>Why this takes the element and the field name rather than the text alone</h2>
     *
     * <p>Because the pack's <b>conventional key</b> for an element's words is built from the element's own id
     * and the field it came from — {@code element.<id>.text} for a label's words and {@code element.<id>.title}
     * for a picture's. A resolver handed only a {@code QuestText} could not build either, so the two fields
     * would have to share one key, and a pack that translated a picture's title would translate every label
     * with the same id as well.
     *
     * <p>Two fields and no more, because those are the only two pieces of text an element has.
     */
    @FunctionalInterface
    public interface Words {

        /**
         * @param element the element the text belongs to, whose id names the conventional key
         * @param field   which of its texts this is: {@code "text"} or {@code "title"}
         * @param text    the author's own text, a literal or a key with a fallback
         */
        String of(CanvasElement element, String field, QuestText text);
    }

    /**
     * How an element is being looked at this frame.
     *
     * @param selected the author has it selected, so it wears the selection ring
     * @param hovered  the pointer is over it, so it wears the hover ring when nothing is selected
     * @param marked   a reader would not see this at all and an author is seeing it anyway — a dev-only
     *                 element with dev mode off, or one whose gate is not met. Marked the way a hidden quest
     *                 is, because an author who cannot see that a thing is hidden cannot unhide it
     */
    public record Look(boolean selected, boolean hovered, boolean marked) {

        /** Nothing at all: how a reader sees every element. */
        public static final Look NONE = new Look(false, false, false);
    }

    /**
     * An element's box on screen, in the pixels a press arrives in.
     *
     * <p>Half-open at the right and bottom, matching {@code Slot.contains} and {@code GuiRenderer.fill}, so a
     * box and a hit test over the same numbers describe the same pixels.
     */
    public record Box(int left, int top, int right, int bottom) {

        public int width() {
            return right - left;
        }

        public int height() {
            return bottom - top;
        }

        public boolean contains(double x, double y) {
            return x >= left && x < right && y >= top && y < bottom;
        }

        /** Whether this box overlaps another, which is how the canvas culls what it cannot show. */
        public boolean overlaps(Box other) {
            return left < other.right && other.left < right && top < other.bottom && other.top < bottom;
        }
    }

    /**
     * Whether a reader sees this element at all.
     *
     * <h2>Two gates, and both are presentation</h2>
     *
     * <p>{@code dev} is drawn only while dev mode is on — how an author drafts a layout without shipping it
     * — and {@code requires} is drawn only once the quest it names is completed. Neither is access control:
     * the element reaches every client either way, and this is the same reading the legacy implementation gives its own two flags.
     *
     * <p>An author is shown a hidden element anyway, <b>marked</b> — see {@link Look#marked} — because the
     * alternative is a decoration that silently is not there while its author is looking at the file that
     * says it is.
     */
    public static boolean shown(CanvasElement element, Frame frame) {
        if (element.dev() && !frame.devMode()) {
            return false;
        }
        Optional<String> requires = element.requires();
        if (requires.isEmpty()) {
            return true;
        }
        return frame.states().apply(requires.get()) == QuestState.COMPLETED;
    }

    /**
     * Where an element is on screen.
     *
     * <p>Every arm but {@link CanvasElement.Line} is a rectangle, and a line's is the rectangle its two
     * endpoints span — which is what the canvas culls against. A hit test does <b>not</b> use this for a line:
     * a press two pixels off a diagonal is a press on the line, and the box around it is mostly empty. See
     * {@link #distanceTo}.
     *
     * <p>A label's box is its <b>measured</b> text, through the frame's own renderer, so the box and the ink
     * cannot disagree: a box that guessed at the width would be a label whose edge is not where it looks.
     */
    public static Box boxOf(CanvasElement element, Frame frame) {
        Viewport view = frame.view();
        return switch (element) {
            case CanvasElement.Rect rect -> new Box(view.screenX(rect.x()), view.screenY(rect.y()),
                    view.screenX(rect.x() + rect.width()), view.screenY(rect.y() + rect.height()));
            case CanvasElement.Image image -> new Box(view.screenX(image.x()), view.screenY(image.y()),
                    view.screenX(image.x() + image.width()), view.screenY(image.y() + image.height()));
            case CanvasElement.Line line -> {
                int left = Math.min(view.screenX(line.x1()), view.screenX(line.x2()));
                int top = Math.min(view.screenY(line.y1()), view.screenY(line.y2()));
                int right = Math.max(view.screenX(line.x1()), view.screenX(line.x2()));
                int bottom = Math.max(view.screenY(line.y1()), view.screenY(line.y2()));
                // At least one pixel on each axis, so a horizontal rule is not a box of height zero that no
                // hit test can be inside and no cull can keep.
                yield new Box(left, top, Math.max(right, left + 1), Math.max(bottom, top + 1));
            }
            case CanvasElement.Text text -> {
                String words = frame.words().of(text, "text", text.text());
                List<String> lines = linesOf(words);
                float size = (float) effectiveScale(text, frame);
                int width = 0;
                for (String line : lines) {
                    width = Math.max(width, widthOf(frame, line, size));
                }
                // A fixed label travels with the canvas like anything placed on it: its position is the
                // canvas mapping at the current zoom, so a wheel-zoom about the pointer keeps it glued
                // to its neighbours instead of sliding it by the zoom's own pan rewrite. What the flag
                // holds is the size alone — the file's own size times the zoom, the same promise a
                // node's size makes. Everything downstream -- the drawing, the hit test,
                // the cull, the grips -- reads this box, so none of them has a second opinion about where
                // a fixed label is or how big it draws.
                int left = view.screenX(text.x());
                int top = view.screenY(text.y());
                yield new Box(left, top, left + Math.max(width, 1),
                        top + Math.max(advanceOf(frame, size) * lines.size(), 1));
            }
            // Nothing to draw and nothing to press: an unknown element is a name this build cannot read, and
            // a box around nothing would be a control that does nothing where the author sees empty canvas.
            case CanvasElement.Unknown ignored -> new Box(0, 0, 0, 0);
        };
    }

    /**
     * How far a point is from an element, for the one arm whose shape is not its box.
     *
     * <h2>Why this takes a box rather than a frame</h2>
     *
     * <p>Because the caller that needs it is a <b>press</b>, and a press happens outside the draw: there is no
     * renderer in hand at that moment, and a label's box is a measured thing. So the canvas measures the boxes
     * once per stamp — where it does have a renderer — and asks this about the box it already has. A version
     * that took a frame would be a hit test that could only run during a frame, which is the fault this
     * signature exists to avoid.
     *
     * <p>A line is measured to its route rather than to its box, because the box around a diagonal is mostly
     * empty: a hit test that used it would take a press well away from the ink. This is the same measurement
     * the dependency lines' own hover uses, so both kinds of line on the canvas answer alike.
     */
    public static double distanceTo(CanvasElement element, Box box, Viewport view, double screenX,
                                    double screenY) {
        if (element instanceof CanvasElement.Line line) {
            return LineArt.distance(routeOf(line, view), screenX, screenY);
        }
        // A turned picture is measured in its own frame, for the same reason its grips are: the press that
        // lands on the part of a picture turning out of its box is a press on the picture, and the
        // axis-aligned box calls it a miss.
        if (degreesOf(element) != 0) {
            double[] local = turned(screenX, screenY, pivotX(element, box), pivotY(element, box),
                    -degreesOf(element));
            screenX = local[0];
            screenY = local[1];
        }
        if (box.contains(screenX, screenY)) {
            return 0;
        }
        // Outside a box the distance is to the nearest edge, so one measurement answers both "is the pointer
        // inside" and "how far off is it" -- which is what lets a caller apply one tolerance to both.
        double dx = Math.max(Math.max(box.left() - screenX, screenX - (box.right() - 1)), 0);
        double dy = Math.max(Math.max(box.top() - screenY, screenY - (box.bottom() - 1)), 0);
        return Math.hypot(dx, dy);
    }

    // ------------------------------------------------------------------
    // The grips a selected element wears
    // ------------------------------------------------------------------

    /**
     * One grip on a selected element.
     *
     * <h2>Which arms have which, and why they are not all the same gesture</h2>
     *
     * <p>A box and a picture are resized by their four corners; a picture additionally <b>turns</b>, because
     * it is the only arm with an angle; and a line's two <b>endpoints</b> move on their own, which is a
     * different gesture from resizing a box: {@code END_FROM} moves one point, where {@code RESIZE_NW} moves a
     * corner and drags the opposite one with it. A label has no grips at all — its box is its measured text,
     * so a resize would be writing a number the loader does not read.
     */
    public enum Handle {
        RESIZE_NW, RESIZE_NE, RESIZE_SW, RESIZE_SE,
        END_FROM, END_TO,
        ROTATE
    }

    /** A handle and where it is on screen. */
    public record Grip(Handle handle, int x, int y) {
    }

    /** How big the drawn grip is, in pixels — smaller than the grab, which is the rule every control follows. */
    public static final int GRIP_SIZE = 5;

    /** How far from a grip's centre a press still counts, in pixels. */
    public static final int GRIP_GRAB = 8;

    /** How far above a picture's top edge the rotate grip sits, in pixels. */
    public static final int ROTATE_OFFSET = 18;

    /**
     * The grips an element wears, and where they are — <b>in the element's own frame</b>.
     *
     * <p>Read from the same box the drawing and the hit test use, so a grip is always exactly on the edge the
     * author sees. For a turned picture that box is the axis-aligned one and these are the corners of the
     * <i>picture</i> rather than of that box: the caller draws them inside a {@code turned} scope and
     * {@link #handleAt} un-turns the press to match, so a rotated picture's frame is rotated with it — which
     * is what makes a turned picture's "bottom right" the corner you can see rather than the corner of a box
     * drawn square to the screen.
     */
    public static List<Grip> handles(CanvasElement element, Box box) {
        List<Grip> grips = new ArrayList<>();
        switch (element) {
            case CanvasElement.Line ignored -> {
                // A line's own two points, which its box's opposite corners are -- see `withBox`'s note.
                grips.add(new Grip(Handle.END_FROM, box.left(), box.top()));
                grips.add(new Grip(Handle.END_TO, box.right(), box.bottom()));
            }
            case CanvasElement.Image ignored -> {
                corners(grips, box);
                // Above the top edge at the middle, which is where every editor puts it and the one place it
                // cannot be confused with a corner.
                grips.add(new Grip(Handle.ROTATE, box.left() + box.width() / 2, box.top() - ROTATE_OFFSET));
            }
            case CanvasElement.Rect ignored -> corners(grips, box);
            case CanvasElement.Text ignored -> {
            }
            case CanvasElement.Unknown ignored -> {
            }
        }
        return List.copyOf(grips);
    }

    private static void corners(List<Grip> grips, Box box) {
        grips.add(new Grip(Handle.RESIZE_NW, box.left(), box.top()));
        grips.add(new Grip(Handle.RESIZE_NE, box.right(), box.top()));
        grips.add(new Grip(Handle.RESIZE_SW, box.left(), box.bottom()));
        grips.add(new Grip(Handle.RESIZE_SE, box.right(), box.bottom()));
    }

    /**
     * Which grip a press at this point is on, or null.
     *
     * <p>The press is <b>un-turned</b> about the picture's pivot first, because the grips are kept in the
     * picture's own frame: a press on the rotated corner the author can see has to be compared against the
     * corner it is a picture of, not against where that corner would be if the picture were straight. With no
     * rotation the two are the same point and this is the plain comparison it looks like.
     */
    public static Handle handleAt(CanvasElement element, Box box, double screenX, double screenY) {
        double[] local = turned(screenX, screenY, pivotX(element, box), pivotY(element, box),
                -degreesOf(element));
        for (Grip grip : handles(element, box)) {
            if (Math.abs(local[0] - grip.x()) <= GRIP_GRAB && Math.abs(local[1] - grip.y()) <= GRIP_GRAB) {
                return grip.handle();
            }
        }
        return null;
    }

    /**
     * The box a cull should test a rotated picture against: its own box, grown by its diagonal.
     *
     * <p>Because a turned picture reaches outside the axis-aligned box it is stored as — by up to half the
     * diagonal for a square turn — so culling on the box alone makes a turned picture vanish while part of it
     * is still on screen. Grown rather than computed exactly, because the conservative answer is the cheap
     * one and the cost of being wrong is drawing nothing.
     */
    public static Box turnedBounds(CanvasElement element, Box box) {
        if (degreesOf(element) == 0) {
            return box;
        }
        int reach = (int) Math.ceil(Math.hypot(box.width(), box.height()) / 2);
        int centreX = box.left() + box.width() / 2;
        int centreY = box.top() + box.height() / 2;
        return new Box(centreX - reach, centreY - reach, centreX + reach, centreY + reach);
    }

    // ------------------------------------------------------------------
    // What a grip does
    // ------------------------------------------------------------------

    /** One field an edit writes: the path relative to the element, and its new value. */
    public record Field(String path, int value) {
    }

    /**
     * What changed between two versions of one element, as the fields a commit must write.
     *
     * <h2>Why a diff rather than a list of fields per gesture</h2>
     *
     * <p>Because a gesture does not know what it changed, and should not have to: a move changes two fields for
     * a box and four for a line, a resize changes two or four depending on which corner was grabbed, and a
     * rotate changes one. Written as a diff against the element the file holds, all three gestures share one
     * commit path, and a gesture that moved nothing writes nothing — which is what keeps a click that happened
     * to jitter from costing a save and a history step.
     *
     * <p>Empty for a pair of different arms, which cannot happen: a gesture copies the element it started from,
     * so the arm is the same on both sides. Written as the empty answer rather than an exception because this
     * is called on the release path, where a throw would be a crash in a frame.
     */
    public static List<Field> geometry(CanvasElement from, CanvasElement to) {
        List<Field> fields = new ArrayList<>();
        switch (to) {
            case CanvasElement.Line now when from instanceof CanvasElement.Line was -> {
                changed(fields, "x1", was.x1(), now.x1());
                changed(fields, "y1", was.y1(), now.y1());
                changed(fields, "x2", was.x2(), now.x2());
                changed(fields, "y2", was.y2(), now.y2());
            }
            case CanvasElement.Image now when from instanceof CanvasElement.Image was -> {
                changed(fields, "x", was.x(), now.x());
                changed(fields, "y", was.y(), now.y());
                changed(fields, "width", was.width(), now.width());
                changed(fields, "height", was.height(), now.height());
                changed(fields, "rotation", was.rotation(), now.rotation());
            }
            case CanvasElement.Rect now when from instanceof CanvasElement.Rect was -> {
                changed(fields, "x", was.x(), now.x());
                changed(fields, "y", was.y(), now.y());
                changed(fields, "width", was.width(), now.width());
                changed(fields, "height", was.height(), now.height());
            }
            // A label moves and is not resized: its box is measured, so `x` and `y` are the whole of its
            // geometry and a size would be a field it does not have.
            case CanvasElement.Text now when from instanceof CanvasElement.Text was -> {
                changed(fields, "x", was.x(), now.x());
                changed(fields, "y", was.y(), now.y());
            }
            default -> {
            }
        }
        return List.copyOf(fields);
    }

    private static void changed(List<Field> fields, String path, int was, int now) {
        if (was != now) {
            fields.add(new Field(path, now));
        }
    }

    /**
     * A resize: the element's box with one corner moved, anchored at the opposite one.
     *
     * <p>Anchored rather than sliding, which is what makes a resize predictable: the corner the author is not
     * holding stays exactly where it was. The size is clamped to at least one pixel, because a box of no size
     * is a box nobody can grab again, and to the arm's own maximum, because a drag past the codec's limit
     * would otherwise write a size the loader silently clamps.
     *
     * <p>Anything that is not a box or a picture comes back unchanged, which is the honest answer for a line
     * (its grips move endpoints) and for a label (it has no size).
     *
     * <h2>A turned picture resizes in its own frame</h2>
     *
     * <p>Two things have to be true at once, and neither is obvious: the size must change along the
     * <b>picture's</b> axes rather than the screen's, and the corner the author is not holding must stay
     * exactly where it was <b>on the canvas</b>. So the size is taken from the pointer measured <i>from that
     * anchor</i> and expressed in the picture's frame — not from the pointer's position inside the local box,
     * which would leave the dragged corner lagging half of every size change behind the pointer, because the
     * anchor is what is fixed and the pivot moves as the box grows. The box is then placed by solving for
     * where its origin must be for the anchor to land back on the point it started at.
     */
    public static CanvasElement resized(CanvasElement element, Handle handle, int pointerX, int pointerY) {
        if (!(element instanceof CanvasElement.Image) && !(element instanceof CanvasElement.Rect)) {
            return element;
        }
        int left = originX(element);
        int top = originY(element);
        int width = edgeWidth(element);
        int height = edgeHeight(element);
        if (degreesOf(element) != 0) {
            return resizedTurned((CanvasElement.Image) element, handle, pointerX, pointerY, left, top,
                    width, height);
        }
        int right = left + width;
        int bottom = top + height;
        if (handle == Handle.RESIZE_NW || handle == Handle.RESIZE_SW) {
            left = Math.min(pointerX, right - 1);
        }
        if (handle == Handle.RESIZE_NE || handle == Handle.RESIZE_SE) {
            right = Math.max(pointerX, left + 1);
        }
        if (handle == Handle.RESIZE_NW || handle == Handle.RESIZE_NE) {
            top = Math.min(pointerY, bottom - 1);
        }
        if (handle == Handle.RESIZE_SW || handle == Handle.RESIZE_SE) {
            bottom = Math.max(pointerY, top + 1);
        }
        int most = maxEdge(element);
        return element.withBox(left, top, Math.min(right - left, most), Math.min(bottom - top, most));
    }

    /**
     * The same for a turned picture: the size in the picture's frame, the anchor fixed on the canvas.
     *
     * <p>Four steps, and each one is a fact about the geometry rather than a tune:
     *
     * <ol>
     *   <li>The anchor is the corner opposite the one grabbed, and it is the one thing that does not move.</li>
     *   <li>The pointer, measured from that anchor and turned back into the picture's frame, gives the new
     *       size along the picture's own axes — this is what makes the dragged corner land exactly under the
     *       pointer rather than near it.</li>
     *   <li>The anchor's offset from the pivot depends only on the size and which corner it is, so it can be
     *       worked out for the <i>new</i> box before the box has a position.</li>
     *   <li>Turning that offset and adding the pivot gives where the anchor would be for an origin at zero;
     *       the difference from where it really is <i>is</i> the origin.</li>
     * </ol>
     */
    private static CanvasElement resizedTurned(CanvasElement.Image image, Handle handle, int pointerX,
                                               int pointerY, int left, int top, int width, int height) {
        int degrees = image.rotation();
        // Which way the box grows from the anchor, per grip: `growX`/`growY` are +1 when the grabbed corner
        // is right of/below the anchor, and -1 when it is left/above.
        int growX = handle == Handle.RESIZE_NE || handle == Handle.RESIZE_SE ? 1 : -1;
        int growY = handle == Handle.RESIZE_SW || handle == Handle.RESIZE_SE ? 1 : -1;
        // The anchor itself: the corner the growth does not move. In the picture's frame, and then where that
        // corner really is on the canvas -- which is not the same place, because the picture is turned. Every
        // measurement below is from that point, and getting the two confused is a picture that grows by half
        // of what was asked for.
        int anchorX = growX > 0 ? left : left + width;
        int anchorY = growY > 0 ? top : top + height;
        double[] pivot = contentPivot(image, left, top, width, height);
        double[] anchorNow = turned(anchorX - pivot[0], anchorY - pivot[1], 0, 0, degrees);
        double anchorContentX = anchorNow[0] + pivot[0];
        double anchorContentY = anchorNow[1] + pivot[1];

        double[] fromAnchor = turned(pointerX - anchorContentX, pointerY - anchorContentY, 0, 0,
                -degrees);
        int most = maxEdge(image);
        int grownWidth = Math.min(most, Math.max(1, (int) Math.round(growX * fromAnchor[0])));
        int grownHeight = Math.min(most, Math.max(1, (int) Math.round(growY * fromAnchor[1])));

        // Where the anchor sits relative to the pivot, for the new size — and where the origin sits relative
        // to the anchor, which is the same fact read the other way. Both come in two shapes, because the pivot
        // is the centre for a plain picture and the picture's own top-left corner when `corner` is set: with a
        // centred pivot the anchor is half a box away from it, and with a corner pivot it is the whole box
        // away when the anchor is the far one and nothing at all when it is the near one.
        double offsetX;
        double offsetY;
        double backX;
        double backY;
        if (image.corner()) {
            offsetX = growX < 0 ? grownWidth : 0;
            offsetY = growY < 0 ? grownHeight : 0;
            backX = 0;
            backY = 0;
        }
        else {
            offsetX = -growX * grownWidth / 2.0;
            offsetY = -growY * grownHeight / 2.0;
            backX = grownWidth / 2.0;
            backY = grownHeight / 2.0;
        }
        double[] turnedOffset = turned(offsetX, offsetY, 0, 0, degrees);
        int placedX = (int) Math.round(anchorContentX - turnedOffset[0] - backX);
        int placedY = (int) Math.round(anchorContentY - turnedOffset[1] - backY);
        return image.withBox(placedX, placedY, grownWidth, grownHeight);
    }

    /**
     * A line with one endpoint moved to a point, which is what its two grips do.
     *
     * <p>One endpoint and not both: a line's ends are placed independently, so moving one must not drag the
     * other — the opposite of a resize, and the reason these are their own handles rather than a box.
     */
    public static CanvasElement endMoved(CanvasElement element, Handle handle, int contentX, int contentY) {
        if (!(element instanceof CanvasElement.Line line)) {
            return element;
        }
        return handle == Handle.END_FROM
                ? line.withEnds(contentX, contentY, line.x2(), line.y2())
                : line.withEnds(line.x1(), line.y1(), contentX, contentY);
    }

    /**
     * The angle a rotate grip names: from the element's own centre to the pointer, with the resting position
     * straight up.
     *
     * <h2>Content coordinates, not screen ones</h2>
     *
     * <p>Because the caller is a <b>drag</b>, and a drag happens outside the draw where there is no renderer —
     * and a box measured on screen would need one, since a label's box is its measured text. An angle does not
     * need measuring: the centre of a picture's box is arithmetic about four numbers the element already
     * carries, and the viewport's mapping is linear, so an angle in content coordinates is the angle on
     * screen.
     *
     * <p>Straight up is zero because that is where the grip sits when the picture is straight, so grabbing it
     * and letting go without moving changes nothing. Dragging it to the right of the centre turns the picture
     * clockwise, which is the direction the seam's positive degrees turn it — see {@code GuiRenderer.turned}.
     * Wrapped to 0..359, which is the range the codec's own {@code wrappedInt(360)} produces, so a drag that
     * goes round twice writes the same number a file would hold.
     */
    public static int rotationTo(CanvasElement element, double contentX, double contentY) {
        double centreX = originX(element) + edgeWidth(element) / 2.0;
        double centreY = originY(element) + edgeHeight(element) / 2.0;
        double degrees = Math.toDegrees(Math.atan2(contentY - centreY, contentX - centreX)) + 90;
        return Math.floorMod(Math.round(degrees), 360);
    }

    /** A box arm's own width, in content pixels. */
    private static int edgeWidth(CanvasElement element) {
        return switch (element) {
            case CanvasElement.Image image -> image.width();
            case CanvasElement.Rect rect -> rect.width();
            case CanvasElement.Line line -> Math.abs(line.x2() - line.x1());
            case CanvasElement.Text ignored -> 0;
            case CanvasElement.Unknown ignored -> 0;
        };
    }

    /** The same, vertically. */
    private static int edgeHeight(CanvasElement element) {
        return switch (element) {
            case CanvasElement.Image image -> image.height();
            case CanvasElement.Rect rect -> rect.height();
            case CanvasElement.Line line -> Math.abs(line.y2() - line.y1());
            case CanvasElement.Text ignored -> 0;
            case CanvasElement.Unknown ignored -> 0;
        };
    }

    /** The largest edge the arm's codec accepts, so a drag cannot write a size the loader clamps. */
    private static int maxEdge(CanvasElement element) {
        return element instanceof CanvasElement.Image ? CanvasElement.Image.MAX_EDGE
                : CanvasElement.Rect.MAX_EDGE;
    }

    /**
     * The point a picture turns about: its centre, or its own top-left when {@code corner} is set.
     *
     * <h2>Why this is a function rather than three lines inside the drawing</h2>
     *
     * <p>Because four things have to agree about it — the picture's own turn, the selection frame and its
     * grips, the hit test that decides whether a press is on a grip, and the resize arithmetic — and a pivot
     * computed twice is a frame that drifts off the picture the second one of them changes. The legacy format has the same
     * pair, and uses both in one real pack, which is why the corner case is not decoration.
     */
    public static int pivotX(CanvasElement element, Box box) {
        return cornerPivot(element) ? box.left() : box.left() + box.width() / 2;
    }

    /** The same, vertically. See {@link #pivotX}. */
    public static int pivotY(CanvasElement element, Box box) {
        return cornerPivot(element) ? box.top() : box.top() + box.height() / 2;
    }

    private static boolean cornerPivot(CanvasElement element) {
        return element instanceof CanvasElement.Image image && image.corner();
    }

    /**
     * An element's own angle, which only a picture has. Everything else is upright.
     *
     * <p>Public because the caller drawing the selection frame has to ask it: the frame is drawn inside a turn
     * of this many degrees, so "is there a turn at all" is a question about the element rather than about the
     * frame — and one answer, here, is what keeps the frame and the picture turning about the same pivot by
     * the same amount.
     */
    public static int degreesOf(CanvasElement element) {
        return element instanceof CanvasElement.Image image ? image.rotation() : 0;
    }

    /**
     * A point turned about a pivot, clockwise on screen for positive degrees.
     *
     * <p>The same direction the seam turns in — {@code GuiRenderer.turned} multiplies a
     * {@code ZP.rotationDegrees} — so a grip drawn inside that scope and a grip hit-tested by this function
     * are the same point. The inverse is this with the sign flipped, which is why one function serves both.
     */
    public static double[] turned(double x, double y, double pivotX, double pivotY, int degrees) {
        double radians = Math.toRadians(degrees);
        double cos = Math.cos(radians);
        double sin = Math.sin(radians);
        double dx = x - pivotX;
        double dy = y - pivotY;
        return new double[] {pivotX + dx * cos - dy * sin, pivotY + dx * sin + dy * cos};
    }

    /**
     * The same, in content coordinates, for the pivot a <b>box</b> has without a viewport.
     *
     * <p>Used by the resize arithmetic, which works in the file's own coordinates: a gesture there must not
     * depend on the zoom, and a pivot read off the screen would.
     */
    private static double[] contentPivot(CanvasElement element, int left, int top, int width, int height) {
        return cornerPivot(element)
                ? new double[] {left, top}
                : new double[] {left + width / 2.0, top + height / 2.0};
    }

    /**
     * An element's own origin, in content coordinates: the point a drag takes hold of.
     *
     * <h2>Why this is not the box's corner</h2>
     *
     * <p>Because a drag needs a point that <b>moves with the element and means the same thing before and
     * after</b>, and the box's corner is derived: for a turned picture it is the corner of the axis-aligned
     * box, which is not a corner of the picture. So the origin is the element's own first coordinate — the
     * corner for the three box arms, and the first endpoint for a line — which is exactly what
     * {@link CanvasElement#translated} moves. A drag is then "take hold here, put this point there", and the
     * delta it produces is the same one the file will be written with.
     */
    public static int originX(CanvasElement element) {
        return switch (element) {
            case CanvasElement.Rect rect -> rect.x();
            case CanvasElement.Image image -> image.x();
            case CanvasElement.Text text -> text.x();
            case CanvasElement.Line line -> line.x1();
            // Nothing to take hold of: an element of a type this build cannot read has no known geometry, so a
            // drag of one moves nothing rather than moving whichever fields happen to look like positions.
            case CanvasElement.Unknown ignored -> 0;
        };
    }

    /** The same, vertically. See {@link #originX}. */
    public static int originY(CanvasElement element) {
        return switch (element) {
            case CanvasElement.Rect rect -> rect.y();
            case CanvasElement.Image image -> image.y();
            case CanvasElement.Text text -> text.y();
            case CanvasElement.Line line -> line.y1();
            case CanvasElement.Unknown ignored -> 0;
        };
    }

    /** Draws one element. The caller has already decided that a reader may see it, or that it is marked. */
    public static void draw(Frame frame, CanvasElement element, Look look) {
        GuiRenderer r = frame.renderer();
        Box box = boxOf(element, frame);
        switch (element) {
            case CanvasElement.Rect rect -> drawRect(r, rect, frame, box);
            case CanvasElement.Image image -> drawImage(r, image, frame, box);
            case CanvasElement.Line line -> drawLine(r, line, frame, box);
            case CanvasElement.Text text -> drawText(r, text, frame, box);
            // An element whose type this build does not know has nothing to draw, and saying so is the
            // validator's job rather than a picture's. A placeholder here would be a mark nobody could act
            // on, in a place the author was not told to look.
            case CanvasElement.Unknown ignored -> {
            }
        }
        if (look.marked()) {
            drawHiddenMark(r, box);
        }
        if (look.selected() || look.hovered()) {
            // The ring is drawn last so it is over the element's own ink, and it is the same pair of tokens a
            // node's ring uses: a decoration and a node are selected by the same gesture, so they are marked
            // with the same ink.
            ring(r, element, box, look.selected() ? ArmatureTheme.selectedRing() : ArmatureTheme.hoverRing());
        }
    }

    /**
     * The selection ring: four one-pixel edges, turned with the picture and never drawn round a line.
     *
     * <h2>Why a line has none</h2>
     *
     * <p>Because a line is not a rectangle: its box is the rectangle its two endpoints span, so a ring round it
     * is a frame around mostly empty space — which reads as the selection being of the empty space rather than
     * of the line. A line's selection is its two endpoint grips, and those are drawn where the line really is.
     *
     * <h2>Why it turns</h2>
     *
     * <p>Because a frame square to the screen around a turned picture does not describe it: the picture's own
     * edges are the ones an author is looking at, and the grips already sit on them — a square ring and turned
     * grips disagree about where the picture ends. One turn, about the same pivot the picture and its grips use.
     */
    private static void ring(GuiRenderer r, CanvasElement element, Box box, int ink) {
        if (element instanceof CanvasElement.Line) {
            return;
        }
        int degrees = degreesOf(element);
        GuiRenderer.Scoped turn = degrees == 0 ? null
                : r.turned(pivotX(element, box), pivotY(element, box), degrees);
        try {
            outline(r, box, ink);
        }
        finally {
            if (turn != null) {
                turn.close();
            }
        }
    }

    // ------------------------------------------------------------------
    // The four arms
    // ------------------------------------------------------------------

    /**
     * A box: the border across the whole rectangle, then the fill inset by the border's width.
     *
     * <p>Two fills at any width, and in this order, which is {@code ArmatureTheme.panel}'s argument one level
     * down: drawn the other way round — fill the box, then paint a border over it — the border would cover
     * the fill at the edges, and a wide border would leave nothing else. Inset by the border's width rather
     * than by one pixel, because unlike a panel's this width is the author's.
     *
     * <p>Both colours are skipped when they are fully transparent, which is the default: a box that names no
     * colour draws nothing, and issuing the fill anyway would be a primitive per element per frame for a
     * rectangle nobody can see.
     */
    private static void drawRect(GuiRenderer r, CanvasElement.Rect rect, Frame frame, Box box) {
        int border = Math.max(0, frame.view().scaled(rect.borderWidth()));
        if (border > 0 && (rect.borderColor() >>> 24) != 0) {
            r.fill(box.left(), box.top(), box.right(), box.bottom(), rect.borderColor());
        }
        if (box.width() > border * 2 && box.height() > border * 2 && (rect.fillColor() >>> 24) != 0) {
            r.fill(box.left() + border, box.top() + border, box.right() - border, box.bottom() - border,
                    rect.fillColor());
        }
    }

    /**
     * A picture: a file or an atlas region, tinted, turned, and with its title painted into it when it asks.
     *
     * <h2>The texture's own size is the destination's, and that is the trick {@code texture} documents</h2>
     *
     * <p>A blit's source region is expressed in the texture's pixels, so drawing a whole file into a box
     * needs the file's real size — which would mean a lookup, a cache, and a cache that can go stale when a
     * pack reload replaces the file. It does not: naming the <b>destination</b> as the texture's size makes
     * the sampled region the whole file whatever the file is, which is exactly what stretching to fill means.
     * So there is no size lookup here, and nothing that can be wrong after a reload.
     *
     * <h2>The turn is about the centre, or about the corner when the element says so</h2>
     *
     * <p>Both pivots are real: a picture turned slightly off square reads as placed, and one pinned by a
     * corner reads as stuck down. Legacy packs have the same pair, and uses both in one real pack.
     */
    private static void drawImage(GuiRenderer r, CanvasElement.Image image, Frame frame, Box box) {
        int width = box.width();
        int height = box.height();
        if (width <= 0 || height <= 0) {
            return;
        }
        int tint = tintOf(image);
        int pivotX = pivotX(image, box);
        int pivotY = pivotY(image, box);

        GuiRenderer.Scoped turn = image.rotation() == 0 ? null : r.turned(pivotX, pivotY, image.rotation());
        try {
            switch (image.image()) {
                case ImageSource.Sprite sprite -> r.sprite(sprite.sprite(), box.left(), box.top(), width,
                        height, tint);
                case ImageSource.Texture texture -> r.scaled(texture.file(), box.left(), box.top(), width,
                        height, 0F, 0F, width, height, width, height, tint);
            }
            // Painted inside the same scope, so words on a turned picture turn with it -- which is the only
            // reading of "on the image" that survives a rotation.
            image.label().filter(ElementLabel::onImage).ifPresent(label ->
                    image.title().ifPresent(title ->
                            drawOnImage(r, frame.words().of(image, "title", title), label, frame, box)));
        }
        finally {
            if (turn != null) {
                turn.close();
            }
        }
    }

    /**
     * The words an author painted into a picture, placed against the edges its label names.
     *
     * <p>One line, at the font's own size: the label properties the legacy format carries for this are an inset and two
     * alignments, and none of them is a size — so a size here would be a field this format does not have and
     * a picture's title drawn at a scale nothing could set.
     */
    private static void drawOnImage(GuiRenderer r, String words, ElementLabel label, Frame frame, Box box) {
        if (words.isEmpty()) {
            return;
        }
        int inset = Math.max(0, frame.view().scaled((int) Math.round(label.inset())));
        int width = widthOf(frame, words, 1F);
        int line = frame.renderer().lineHeight();
        int x = switch (label.hAlign()) {
            case START -> box.left() + inset;
            case MIDDLE -> box.left() + (box.width() - width) / 2;
            case END -> box.right() - inset - width;
        };
        int y = switch (label.vAlign()) {
            case START -> box.top() + inset;
            case MIDDLE -> box.top() + (box.height() - line) / 2;
            case END -> box.bottom() - inset - line;
        };
        // White, because this is the one piece of element text whose colour is not its own field: the legacy format has no
        // colour for text on an image, and a title drawn in a colour nobody chose is a colour nobody can
        // change. The shadow is the one property it does carry, and it is exactly what a busy picture wants.
        drawWords(r, words, x, y, Argb.WHITE, label.shadow(), 1F);
    }

    /**
     * A line: its route, its own width, and a head at whichever ends it names.
     *
     * <h2>Every tone is the element's one colour</h2>
     *
     * <p>{@code LineArt} buckets a stroke's pixels into tones so a conduit's borders can be darker than its
     * core — a dependency line's look. A canvas line is one colour, so every tone maps to it. That is not a
     * loss: the tones exist to let a caller ink the same rectangles three ways, and this caller inks them one.
     */
    private static void drawLine(GuiRenderer r, CanvasElement.Line line, Frame frame, Box box) {
        List<LineArt.Point> route = routeOf(line, frame.view());
        int width = Math.max(1, frame.view().scaled(line.width()));
        for (LineArt.Fill fill : LineArt.fillsAtWidth(route, width, DependencyStyle.Dash.SOLID)) {
            r.fill(fill.x1(), fill.y1(), fill.x2(), fill.y2(), line.color());
        }
        // No nodes to clear, so both halves are zero and the head rides at the endpoint itself.
        for (LineArt.Fill fill : LineArt.arrows(route, DependencyStyle.ArrowHead.TRIANGLE,
                line.arrowhead().atStart(), line.arrowhead().atEnd(), 0, 0, width)) {
            r.fill(fill.x1(), fill.y1(), fill.x2(), fill.y2(), line.color());
        }
    }

    /**
     * A label: one call per line, at the element's size, with or without the font's shadow.
     *
     * <p>Two different operations on this seam, and the difference is not decoration: a shadow is a baked
     * glyph the font draws for you, and a size is a pose. A caller with both asks the shadowed call, which
     * takes the size for exactly that reason — see {@code GuiRenderer.shadowedText}.
     */
    private static void drawText(GuiRenderer r, CanvasElement.Text text, Frame frame, Box box) {
        float scale = (float) effectiveScale(text, frame);
        int advance = advanceOf(frame, scale);
        int y = box.top();
        for (String line : linesOf(frame.words().of(text, "text", text.text()))) {
            drawWords(r, line, box.left(), y, text.color(), text.shadow(), scale);
            y += advance;
        }
    }

    /**
     * The size a label draws and measures at: the file's own size, times the zoom for a fixed one.
     *
     * <p>One method because the box and the ink must agree: {@link #boxOf} measures the width and the
     * line advance through it, and {@link #drawText} draws through it, so a label's edge is always
     * where its words are, at any zoom. A fixed label is canvas pixels at zoom one — the same promise
     * a node's own size makes — and an anchored one keeps the file's size on screen at every zoom.
     * The position is the canvas mapping either way: the flag is the size, not the place.
     */
    private static double effectiveScale(CanvasElement.Text text, Frame frame) {
        return text.fixed() ? text.scale() * frame.view().scale() : text.scale();
    }

    /** One line of words, by whichever of the seam's three calls its size and shadow ask for. */
    private static void drawWords(GuiRenderer r, String words, int x, int y, int colour, boolean shadow,
                                  float scale) {
        if (shadow) {
            r.shadowedText(words, x, y, colour, scale);
        }
        else if (scale == 1F) {
            r.text(words, x, y, colour);
        }
        else {
            // A run, because a size without a shadow is what `StyledRun`'s scale is for. Measured through
            // `styledWidth` in the same three flags, so the box and the ink agree at any size.
            r.styledText(List.of(new GuiRenderer.StyledRun(words, false, false, false, scale)), x, y, colour);
        }
    }

    // ------------------------------------------------------------------
    // The pieces the arms share
    // ------------------------------------------------------------------

    /** A line's route on screen, one point per pixel, which is what {@code LineArt} measures against. */
    private static List<LineArt.Point> routeOf(CanvasElement.Line line, Viewport view) {
        return LineArt.path(DependencyStyle.Form.STRAIGHT,
                new LineArt.Point(view.screenX(line.x1()), view.screenY(line.y1())),
                new LineArt.Point(view.screenX(line.x2()), view.screenY(line.y2())));
    }

    /**
     * The tint a picture is drawn in: its colour's RGB with its two alphas multiplied.
     *
     * <p>White is the identity, which is the default, and a tint that carries an alpha multiplies with the
     * {@code alpha} field rather than replacing it — so "dim this" has one meaning whichever field an author
     * reaches for. the legacy format carries the two separately and draws the tint with the alpha field <i>replacing</i>
     * the colour's own; with an opaque tint the two are the same sum, which is why a converted picture is
     * written {@code #FFRRGGBB}. See {@code CanvasElement.Image}.
     */
    private static int tintOf(CanvasElement.Image image) {
        int alpha = (((image.tint() >>> 24) & 0xFF) * image.alpha()) / 255;
        return (alpha << 24) | (image.tint() & 0xFFFFFF);
    }

    /** The lines a label's text is drawn as: a newline starts another, and an empty text is one blank. */
    private static List<String> linesOf(String text) {
        if (text == null || text.isEmpty()) {
            return List.of("");
        }
        return List.of(text.split("\n", -1));
    }

    /** One line's width at a size, through the renderer that will draw it. */
    private static int widthOf(Frame frame, String line, float scale) {
        return scale == 1F
                ? frame.renderer().textWidth(line)
                : frame.renderer().styledWidth(line, false, false, scale);
    }

    /** How far down the next line of a label sits: the font's own line height at the element's size. */
    private static int advanceOf(Frame frame, double scale) {
        return (int) Math.round(frame.renderer().lineHeight() * scale);
    }

    /** A one-pixel ring around a box: the marquee's own shape, which is what makes a selection read. */
    private static void outline(GuiRenderer r, Box box, int ink) {
        r.fill(box.left() - 1, box.top() - 1, box.right() + 1, box.top(), ink);
        r.fill(box.left() - 1, box.bottom(), box.right() + 1, box.bottom() + 1, ink);
        r.fill(box.left() - 1, box.top(), box.left(), box.bottom(), ink);
        r.fill(box.right(), box.top(), box.right() + 1, box.bottom(), ink);
    }

    /**
     * The dashed square a hidden thing wears, which is the canvas's own mark for "a reader does not see
     * this" — the same one a hidden quest gets, so the two read as one fact rather than two.
     */
    private static void drawHiddenMark(GuiRenderer r, Box box) {
        int ink = ArmatureTheme.faint();
        int size = Math.max(box.width(), box.height());
        for (int i = 0; i < size; i += 4) {
            int length = Math.min(2, size - i);
            r.fill(box.left() + i, box.top(), box.left() + i + length, box.top() + 1, ink);
            r.fill(box.left() + i, box.bottom() - 1, box.left() + i + length, box.bottom(), ink);
            r.fill(box.left(), box.top() + i, box.left() + 1, box.top() + i + length, ink);
            r.fill(box.right() - 1, box.top() + i, box.right(), box.top() + i + length, ink);
        }
    }
}
