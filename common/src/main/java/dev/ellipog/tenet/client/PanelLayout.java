package dev.ellipog.tenet.client;

import dev.ellipog.armature.client.ui.kit.Easing;
import dev.ellipog.armature.client.ui.kit.Motion;
import dev.ellipog.tenet.client.BookGeometry.Rect;
import dev.ellipog.tenet.client.PanelStack.Fold;

import java.util.List;

/**
 * What is inside a docked column — the header band, the body, the footer — and the four decisions that
 * are arithmetic rather than drawing.
 *
 * <h2>Why this is a class rather than a handful of sums in the screen</h2>
 *
 * <p>For the reason {@code BookGeometry} and {@code ToolsLayout} exist: the screen cannot be built by a
 * test, so anything about <i>where</i> a thing goes has to live where a test can reach it. The column's
 * outer rectangle is {@code BookGeometry}'s; everything inside it is here, and {@code PanelLayoutTest}
 * sweeps the whole of it at four sizes rather than at the one a person happened to look at.
 *
 * <h2>The three bands are the card's own numbers</h2>
 *
 * <p>{@link #BODY_INSET}, {@link #BODY_TOP} and {@link #BODY_BOTTOM} are the figures a centred card
 * used to be drawn with. They are repeated here on purpose and the screen reads them from here: the round
 * that moved every kind into a rail promised that <b>the content does not move relative to its own
 * surface</b>, and that promise is only keepable if the rail measures the numbers the card did. A second
 * copy in the screen would be a second answer to "where does the body start", which is the class of fault
 * {@code BookGeometry} was written to end.
 *
 * <h2>The four decisions</h2>
 *
 * <ul>
 *   <li><b>Fold</b>: whether a second column is shown, or paged into the first. Space decides unless the
 *       player has said otherwise ({@link #folded}).</li>
 *   <li><b>Which column carries the fold control</b>: the outermost one — the second when there are two,
 *       the only one when folded — because that is the column the press is about.</li>
 *   <li><b>The reveal</b>: how much of a column is on screen while it arrives ({@link #revealed}).</li>
 *   <li><b>The canvas's visible edge</b>: the leftmost column's inner edge, so a caller can keep the
 *       quest a reader just clicked out from under it ({@link #visibleRight}).</li>
 * </ul>
 */
public final class PanelLayout {

    // ------------------------------------------------------------------
    // The bands
    // ------------------------------------------------------------------

    /**
     * The header strip's height: the same 45 the card draws its own header in.
     *
     * <p>A title, a state tag and a chapter line sit in it, and the rule under it is at
     * {@code HEADER + 1} — one pixel, square, as the card's is.
     */
    public static final int HEADER = 45;

    /** How far the body is inset from the column's sides. The card's own 18, and for both columns. */
    public static final int BODY_INSET = 18;

    /** Where the body starts below the column's top: the header, its rule, and the air under them. */
    public static final int BODY_TOP = 54;

    /**
     * The air between a body's last row and the footer band below it.
     *
     * <p>Two, and named rather than spelled {@code + 2} in one expression: it is the difference
     * {@link #BODY_BOTTOM} has over the band it was measured with, and the number a column with a footer of
     * its own keeps when the band is not the card's. Declared above {@code BODY_BOTTOM} because that constant
     * is derived from it, and a static initializer cannot read a field declared below it.
     */
    public static final int FOOTER_GAP = 2;

    /**
     * What the footer takes off the bottom: the band, plus the two pixels that separate the last row of a
     * body from it.
     *
     * <p>Forty-six, which is the card's own figure, and it is <b>derived</b> now rather than written: a
     * column that reserves a different footer ({@link #frame(Rect, boolean, int)}) gets a body that stops the
     * same two pixels above <i>its</i> band, so the separation cannot be lost by a caller passing a footer of
     * another height. Not {@code MODAL_FOOTER_HEIGHT} itself — a body that ended flush against the band would
     * have its last row touching the controls, which is the fault {@link #FOOTER_GAP} exists to prevent.
     */
    public static final int BODY_BOTTOM = BookGeometry.MODAL_FOOTER_HEIGHT + FOOTER_GAP;

    /**
     * The fold control's width. Fixed, and its label is truncated to it — see the screen's own rule.
     *
     * <p>Sized for its two words, "Fold" and "Back", like the settings card's Edit. It was 132 -- a
     * footer button's width in a header -- and it took half a narrow column's band, so the picker beside
     * it read "Choose the ..." over a button with one short word in it.
     */
    public static final int FOLD_WIDTH = 56;

    /** The fold control's height: an ordinary footer control's, so it reads as one. */
    public static final int FOLD_HEIGHT = BookGeometry.OVERLAY_CONTROL_HEIGHT;

    /** The air between the header's title and the fold control, when there is one. */
    public static final int HEADER_GAP = 6;

    /** How many ridges the grip on a column's inner edge is drawn with. */
    public static final int GRIP_RIDGES = 3;

    /** One ridge's width. A hairline: the handle's whole strip is the target, and the mark only names it. */
    public static final int GRIP_RIDGE_WIDTH = 2;

    /** One ridge's height, and the air between two of them. Together they are the grip's whole block. */
    public static final int GRIP_RIDGE_HEIGHT = 6;
    public static final int GRIP_GAP = 3;

    private PanelLayout() {
    }

    /**
     * Everything inside one column.
     *
     * @param rail   the column itself, as {@code BookGeometry.panelRail} gave it
     * @param handle the strip a press drags, on the rail's inner edge
     * @param header the header strip, inside the rail's one-pixel border
     * @param body   the region rows and prose may use; its right inset is also the scrollbar's room
     * @param footer the band at the foot that carries the column's controls
     * @param fold   the fold control's cell, or <b>null</b> on a column that does not carry it — there is
     *               nothing to fold on the column a child is folded <i>into</i>
     */
    public record Frame(Rect rail, Rect handle, Rect header, Rect body, Rect footer, Rect fold) {
    }

    /**
     * The bands inside one column.
     *
     * <h2>Every band is clamped into the column, and that is not defensiveness</h2>
     *
     * <p>A column of no height has to give bands of no height rather than bands that stick out of it —
     * a rectangle hanging below its own surface is a press that answers somewhere nothing is drawn, and
     * the sweep is where that is visible rather than on somebody's screenshot. {@code ToolsLayoutTest}
     * found the same fault in its own panel at 80x60 ("a zero-height band could still be placed past the
     * floor"), and this is that lesson applied before it happens rather than after.
     *
     * <p>The one-pixel border a drawn header has is <b>the drawing's</b>, not this method's: these are the
     * bands themselves, so a caller that rounds a corner insets its own fill and the bands stay the
     * arithmetic every hit test reads.
     *
     * @param rail        the column's own rectangle
     * @param carriesFold whether this column carries the fold control. <b>The column that holds the
     *                    child</b>, in both arrangements: the second column when two are shown, because
     *                    that is the one a press folds away, and the single column when folded, because
     *                    the child is what it is showing. The column a child is folded into never carries
     *                    it — there is nothing there to fold.
     */
    public static Frame frame(Rect rail, boolean carriesFold) {
        return frame(rail, carriesFold, BookGeometry.MODAL_FOOTER_HEIGHT);
    }

    /**
     * The same, with the footer's height as the caller's.
     *
     * <h2>Why one column needs a footer of its own</h2>
     *
     * <p>Because not every panel has controls at its foot: the author's dock reserves Revert and Save on its
     * Book tab and <b>nothing</b> on its Chapter tab, and a band reserved for nothing is a strip of panel with
     * no content -- the dead space the drawer's own frame used to avoid with a second arithmetic. So the band
     * is a parameter, and the body stops {@link #FOOTER_GAP} above whatever band it was given: with the card's
     * own footer this is exactly the figure the two-argument form has always produced, and with a footer of
     * nothing the body keeps the panels' {@link #BODY_INSET} at the bottom instead of a footer's air.
     */
    public static Frame frame(Rect rail, boolean carriesFold, int footerHeight) {
        Rect handle = BookGeometry.panelHandle(rail);

        Rect header = Rect.at(rail.x(), rail.y(), rail.width(), Math.min(HEADER, rail.height()));

        int band = Math.max(0, Math.min(rail.height(), footerHeight));
        Rect footer = Rect.at(rail.x(), rail.bottom() - band, rail.width(), band);

        int bodyTop = Math.min(rail.y() + BODY_TOP, rail.bottom());
        int bodyStop = band > 0 ? band + FOOTER_GAP : BODY_INSET;
        int bodyBottom = Math.max(bodyTop, rail.bottom() - bodyStop);
        int bodyLeft = Math.min(rail.x() + BODY_INSET, rail.right());
        int bodyRight = Math.max(bodyLeft, rail.right() - BODY_INSET);
        Rect body = Rect.at(bodyLeft, bodyTop, bodyRight - bodyLeft, bodyBottom - bodyTop);

        Rect fold = carriesFold ? foldCell(rail, header) : null;
        return new Frame(rail, handle, header, body, footer, fold);
    }

    /**
     * The fold control's cell: the right-hand end of the header strip, inside it at any column size.
     *
     * <p>Clamped to the header rather than to the column, so a narrow column gets a narrower control
     * instead of a control drawn over its own edge — and its label is truncated to what is left, which is
     * the rule every control in this mod with a variable-width label already follows.
     */
    private static Rect foldCell(Rect rail, Rect header) {
        int width = Math.min(FOLD_WIDTH, header.width());
        int left = Math.max(header.x(), rail.right() - BookGeometry.MODAL_INSET - width);
        int height = Math.min(FOLD_HEIGHT, header.height());
        int top = Math.min(header.y() + (HEADER - FOLD_HEIGHT) / 2, header.bottom() - height);
        return Rect.at(left, Math.max(header.y(), top), width, Math.max(0, height));
    }

    /**
     * Where the header's own content has to stop.
     *
     * <p>The title, the state tag and the chapter line are drawn left to right and the fold control sits
     * at the right end, so this is the one number that keeps a long quest's name from being drawn through
     * the control that folds the column. One expression for the same reason the card's own
     * {@code headerRightLimit} is one: two would be two answers, and a title that ran under a control is
     * exactly what that produces.
     */
    public static int headerRight(Rect rail, boolean hasFold) {
        if (!hasFold) {
            return rail.right() - BODY_INSET;
        }
        return rail.right() - BookGeometry.MODAL_INSET - FOLD_WIDTH - HEADER_GAP;
    }

    // ------------------------------------------------------------------
    // Whether two columns fit
    // ------------------------------------------------------------------

    /**
     * Whether two columns of these widths fit beside a canvas worth looking at.
     *
     * <h2>Not merely "do they overlap the sidebar"</h2>
     *
     * <p>The first version of this asked only whether the two columns fitted inside the canvas, and a
     * preview of a 1200-wide window showed what that means: 340 and 260 do fit a 644-pixel canvas, and
     * leave <b>38 pixels</b> of graph showing down the left. A sliver of canvas is not a canvas — it reads
     * as a drawing fault rather than as a panel — so the fit is measured against
     * {@link BookGeometry#MIN_CANVAS_WIDTH}, the same number that already says how much canvas the graph
     * and its two floating clusters need to be worth showing.
     *
     * <p><b>The consequence is deliberate and worth stating:</b> a reader's book is capped at
     * {@code MAX_PANEL_WIDTH} (800), so its canvas is at most 644 — and two columns plus a minimum canvas
     * need 782. <b>A reader therefore never sees two columns; they always fold.</b> That is right rather
     * than a shame: every kind that can occupy the second column is an author's tool (a picker, a table),
     * so the second column is an author's arrangement — and an author's book is full-bleed, where a
     * 1400-wide window gives 1244 of canvas and the pair fits with 462 to spare.
     *
     * <p>The single column is unaffected: one panel may cover the whole canvas, which is what "the panel
     * wins" means. This floor is about two of them at once.
     *
     * <h2>What {@code available} is now that the dock has a rail of its own</h2>
     *
     * <p>The room the <b>pair</b> has, which is the canvas less the dock's rail and its two gaps when the
     * author has their tools latched. Passing the canvas's whole width with a dock open would let the pair
     * count the dock's rail as spare room and unfold itself into it — and the dock is the one rail no
     * transition may move, so the arithmetic that decides the fold is the one place that has to know.
     */
    public static boolean fits(int available, int leftWidth, int rightWidth) {
        return leftWidth + BookGeometry.PANEL_GAP + rightWidth + BookGeometry.MIN_CANVAS_WIDTH
                <= available;
    }

    /**
     * Whether the second column is folded into the first.
     *
     * <p>{@link Fold#ALWAYS} and {@link Fold#NEVER} are the player's answers and are honoured whatever the
     * window is: someone who has said "one column" does not want it back because the window grew, and
     * someone who has said "two" has chosen to let the column cover the graph. {@link Fold#AUTO} — the
     * default, and what a player who never opened the settings file gets — asks {@link #fits}.
     */
    public static boolean folded(int available, int leftWidth, int rightWidth, Fold asked) {
        return switch (asked == null ? Fold.AUTO : asked) {
            case ALWAYS -> true;
            case NEVER -> false;
            case AUTO -> !fits(available, leftWidth, rightWidth);
        };
    }

    // ------------------------------------------------------------------
    // The reveal
    // ------------------------------------------------------------------

    /**
     * How much of a column is on screen, {@code elapsed} milliseconds into its arrival.
     *
     * <p>Eased with the client's own curve — the theme's, through {@link Motion#defaultEasing()} — so a
     * theme that moves linearly reveals linearly and the default eases out like every other arrival.
     * <b>Exact at both ends</b>: a column that arrived at 99 percent would leave a one-pixel strip of
     * canvas showing down its inner edge for the rest of the session, and one that started above zero
     * would appear to jump.
     *
     * <p>A duration of zero or less is <b>one</b>, not zero: this is what a theme with no motion asks for,
     * and for the accessibility switch being off — so the column is simply there, on the frame it opens,
     * with no window in which it is half-drawn. That is the same rule {@code Motion.setEnabled} wins with
     * over a theme, one level down.
     */
    public static float revealed(long elapsedMillis, int durationMillis) {
        return revealed(elapsedMillis, durationMillis, Motion.defaultEasing());
    }

    /**
     * The same, with the curve as the caller's.
     *
     * <p>The form the tests pin the ends and the monotonicity on: the client's curve is ambient state and
     * ambient state is what makes a test order-dependent, so the properties that must hold for <i>any</i>
     * curve are asserted here, against explicit ones, rather than against whatever the client last set.
     */
    public static float revealed(long elapsedMillis, int durationMillis, Easing easing) {
        if (durationMillis <= 0) {
            return 1F;
        }
        float t = Math.max(0F, Math.min(1F, (float) elapsedMillis / durationMillis));
        return (easing == null ? Easing.QUAD_OUT : easing).ease(t);
    }

    /**
     * The part of a column that is on screen so far, as a clip.
     *
     * <p>It grows from the column's <b>outer</b> edge, which is the one against the book's right: the
     * column appears to slide in from where it lives rather than to be painted left to right over the
     * graph. The clip is what a caller draws its content inside, so the controls and the rows arrive with
     * the surface instead of sitting on the canvas before it does.
     */
    public static Rect revealRect(Rect rail, long elapsedMillis, int durationMillis) {
        int width = Math.round(rail.width() * revealed(elapsedMillis, durationMillis));
        return Rect.at(rail.right() - width, rail.y(), width, rail.height());
    }

    /**
     * How far the column's contents have still to travel, {@code elapsed} milliseconds in: the distance
     * between where the column lives and where its revealed edge currently stands.
     *
     * <p>The one number the slide shares. The surface drawing shifts right by it (into the clip above,
     * which stays put) and every widget the column owns shifts right by it from its built position, so
     * the sheet and its controls arrive as one thing moving left. The rail's own width on the first
     * frame, zero once settled — and zero with no motion, where there is no slide at all.
     */
    public static int slideOffset(Rect rail, long elapsedMillis, int durationMillis) {
        return revealRect(rail, elapsedMillis, durationMillis).x() - rail.x();
    }

    /**
     * The mirror: how far a departing column has travelled back out, {@code elapsed} milliseconds in.
     *
     * <p>Zero on the frame it closes, the rail's whole width once gone — the enter slide played
     * backwards, over its own (shorter) duration. The drawing clips to what is left and shifts the
     * surface right by this, so the sheet sinks into the edge it arrived from. Zero with no motion,
     * where a close is simply gone on the frame it is pressed.
     */
    public static int departOffset(Rect rail, long elapsedMillis, int durationMillis) {
        if (durationMillis <= 0) {
            // No motion is no travel rather than instant travel: the clip is already nothing (see
            // `departRect`), so there is no sheet to shift, and a width here would only move one.
            return 0;
        }
        return rail.width() - departRect(rail, elapsedMillis, durationMillis).width();
    }

    /**
     * The part of a departing column that is still on screen, as a clip.
     *
     * <p>The enter clip in reverse: anchored to the column's outer edge and shrinking into it, exact
     * at both ends — the whole rail on the first frame, nothing once the duration is spent.
     */
    public static Rect departRect(Rect rail, long elapsedMillis, int durationMillis) {
        int width = Math.round(rail.width() * (1F - revealed(elapsedMillis, durationMillis)));
        return Rect.at(rail.right() - width, rail.y(), width, rail.height());
    }

    // ------------------------------------------------------------------
    // What the canvas still shows
    // ------------------------------------------------------------------

    /**
     * The canvas's right edge as the player can still see it: the innermost column's inner edge.
     *
     * <p>What the graph's own furniture asks — a node's caption, a label's clamp, where a new quest is
     * born, and whether the quest a reader just opened is hidden under the column that opened for it.
     * With no columns it is the canvas's own edge, so a caller does not have to know which presentation
     * it is in to ask the question.
     */
    public static int visibleRight(Rect canvas, List<Rect> rails) {
        int right = canvas.right();
        for (Rect rail : rails) {
            right = Math.min(right, rail.x());
        }
        return right;
    }

    /**
     * How far a box has to move for the columns to stop covering it, or zero when they already do not.
     *
     * <p>The one number the auto-pan needs, and the zero is the half that matters: a view that moved by a
     * computed nought would still count as "the caller moved it", and the chapter's own auto-centre would
     * then never take over again. So "already clear" has to be a positive answer rather than a small one.
     *
     * @param right       the box's right edge, in screen coordinates
     * @param visibleRight where the canvas stops being visible — {@link #visibleRight}'s answer
     * @param margin      how much air to leave between the two
     */
    public static int panToClear(int right, int visibleRight, int margin) {
        return Math.max(0, right + margin - visibleRight);
    }

    // ------------------------------------------------------------------
    // The grip
    // ------------------------------------------------------------------

    /**
     * The ridges on a column's inner edge that say the edge can be dragged.
     *
     * <h2>Why an edge has to say so</h2>
     *
     * <p>Because a control announces itself by looking like a control, and an edge that does nothing until
     * it is pressed announces nothing at all. Three short ridges centred in the handle's strip is what
     * every window manager does for the same job, and it is one of the few affordances this UI cannot
     * borrow from its own vocabulary: there is no control whose shape means "drag", only controls whose
     * shape means "press". The first playtest of this feature said it in three words — no mouse indicator —
     * and this is the answer to it.
     *
     * <p>Geometry rather than a drawing, because where the ridges land is the thing a picture cannot check:
     * the test asserts they are inside the handle, spaced by a whole number of pixels (so two of them can
     * never half-overlap at one column height and not another), centred both ways, and that a strip with no
     * room yields none rather than three clipped marks.
     *
     * <p>Empty for a column too short to hold the block: a mark shorter than its own spacing reads as damage
     * rather than as a grip.
     */
    public static List<Rect> grip(Rect rail) {
        Rect handle = BookGeometry.panelHandle(rail);
        int block = GRIP_RIDGE_HEIGHT * GRIP_RIDGES + GRIP_GAP * (GRIP_RIDGES - 1);
        if (handle.width() <= 0 || handle.height() < block + 4) {
            return List.of();
        }
        int width = Math.min(GRIP_RIDGE_WIDTH, handle.width());
        int left = handle.x() + (handle.width() - width) / 2;
        int top = handle.y() + (handle.height() - block) / 2;
        int step = GRIP_RIDGE_HEIGHT + GRIP_GAP;
        return List.of(
                Rect.at(left, top, width, GRIP_RIDGE_HEIGHT),
                Rect.at(left, top + step, width, GRIP_RIDGE_HEIGHT),
                Rect.at(left, top + step * 2, width, GRIP_RIDGE_HEIGHT));
    }

    /**
     * The same ridges, on the chapter list's own inner (right) edge.
     *
     * <p>A separate method rather than a flag on {@link #grip} because the two handles are on
     * opposite sides: rails dock right so their grip is left, the list docks left so its grip is
     * right. Same three ridges, same centring, same too-short-yields-nothing rule — one vocabulary
     * for "this edge drags" wherever the edge is.
     */
    public static List<Rect> sidebarGrip(Rect list) {
        Rect handle = BookGeometry.sidebarHandle(list);
        int block = GRIP_RIDGE_HEIGHT * GRIP_RIDGES + GRIP_GAP * (GRIP_RIDGES - 1);
        if (handle.width() <= 0 || handle.height() < block + 4) {
            return List.of();
        }
        int width = Math.min(GRIP_RIDGE_WIDTH, handle.width());
        int left = handle.x() + (handle.width() - width) / 2;
        int top = handle.y() + (handle.height() - block) / 2;
        int step = GRIP_RIDGE_HEIGHT + GRIP_GAP;
        return List.of(
                Rect.at(left, top, width, GRIP_RIDGE_HEIGHT),
                Rect.at(left, top + step, width, GRIP_RIDGE_HEIGHT),
                Rect.at(left, top + step * 2, width, GRIP_RIDGE_HEIGHT));
    }

    /**
     * Where the chapter list's sliding surface is, {@code elapsed} milliseconds into a collapse,
     * an expand, a pin or a peek: the wipe from one rectangle to the other.
     *
     * <p>Every side travels, not just the width, because docking, floating and stripping move
     * different edges: a collapse wipes the inner edge to the strip, a pin docks the whole card
     * six pixels over, and a peek grows the tab into the box. Lerping the rectangle lerps all of
     * those as one motion, and the rows travel by the same wipe's shortfall, so the card and its
     * rows arrive as one thing. Exact at both ends for the reason {@link #revealed} states, and
     * instant at zero duration, which is what no-motion asks for.
     *
     * @param from           the rectangle the wipe starts at (the list as the action found it)
     * @param to             the rectangle it lands on (the list as the geometry now says)
     * @param elapsedMillis  milliseconds since the action
     * @param durationMillis how long the wipe takes; zero or less lands immediately
     */
    public static Rect sidebarWipeRect(Rect from, Rect to, long elapsedMillis, int durationMillis) {
        if (durationMillis <= 0) {
            return to;
        }
        float t = revealed(elapsedMillis, durationMillis);
        return Rect.at(Math.round(from.x() + (to.x() - from.x()) * t),
                Math.round(from.y() + (to.y() - from.y()) * t),
                Math.round(from.width() + (to.width() - from.width()) * t),
                Math.round(from.height() + (to.height() - from.height()) * t));
    }

    /**
     * Which rectangle the chapter list's travelling rows were built for.
     *
     * <p>An expanding wipe builds its rows for the target and anchors to it; a departing wipe
     * keeps the rows it has and must anchor to the rectangle they were <i>built</i> for — the
     * expanded box — rather than to the rectangle the wipe starts from. The two are the same
     * when a collapse starts settled, and differ when it reverses an expand (or a peek) mid-wipe:
     * anchoring to the mid-wipe start snaps the rows back to their built positions for a frame,
     * flashing the full list where it was while the surface is already halfway out. The rails
     * never hit this because a close snapshots its rail and redraws its content immediate-mode
     * instead of keeping widgets.
     *
     * @param departing whether the list is sliding out with its rows still aboard
     * @param from      the rectangle the wipe starts at (the list as the action found it)
     * @param target    the rectangle it lands on (the list as the geometry now says)
     * @param expanded  the expanded box the kept rows were built for, or null when unknown
     */
    public static Rect sidebarTravelAnchor(boolean departing, Rect from, Rect target, Rect expanded) {
        if (!departing) {
            return target;
        }
        return expanded != null ? expanded : (from != null ? from : target);
    }

    // ------------------------------------------------------------------
    // What a point belongs to
    // ------------------------------------------------------------------

    /**
     * One column as a press sees it: what it holds, where it is, and how much of it has arrived.
     *
     * @param kind     what the column holds
     * @param rail     the column's whole rectangle
     * @param revealed the part of it that is on screen so far — the whole of {@code rail} once settled
     */
    public record PanelColumn(PanelKind kind, Rect rail, Rect revealed) {
    }

    /**
     * Which column a point belongs to, or null when it belongs to the book beside them.
     *
     * <h2>The revealed part decides, and that is the point</h2>
     *
     * <p>A column that is still arriving is only partly on screen, and the part that is not on screen is
     * <b>canvas</b> — the player sees graph there and nothing else. So a press in that strip pans, selects
     * or opens, which is exactly what the picture promises; only the part that has arrived belongs to the
     * panel. Absorbing the whole rail instead would make a press on visible canvas do nothing for eighty
     * milliseconds, which is the class of fault the reveal exists to avoid.
     *
     * <p>The first match wins and the caller passes the columns in draw order, so two columns can never
     * both answer one press — the failure a shared rectangle would produce, and the reason this is one
     * function rather than a test at each of the three callers.
     */
    public static PanelKind columnAt(List<PanelColumn> columns, double x, double y) {
        for (PanelColumn column : columns) {
            if (column.revealed().contains(x, y)) {
                return column.kind();
            }
        }
        return null;
    }
}
