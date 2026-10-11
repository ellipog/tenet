package dev.ellipog.tenet.client;

import dev.ellipog.armature.client.ArmatureTheme;
import dev.ellipog.armature.client.render.GuiRenderer;
import dev.ellipog.armature.client.ui.kit.Measure;
import dev.ellipog.armature.client.ui.kit.Colour;

import java.util.List;

/**
 * How one notice is drawn: the row, its box, its fade and its truncated sentence.
 *
 * <h2>Why this is a class rather than a loop in each drawer</h2>
 *
 * <p>Because there are two drawers now and they agree about everything except <b>where</b> the stack is.
 * The book stands its notices on the window's bottom edge, centred, where the eye already is when the hand
 * is on the canvas; the HUD's notice element draws them where the player put it. The box, the fill, the
 * alpha and the truncation are one description, and this is it -- the same rule the toolkit's control table
 * exists for, one subject over. Two copies of this arithmetic is exactly what the last several rounds kept
 * finding and deleting.
 *
 * <h2>Why the width is the caller's</h2>
 *
 * <p>Because the two callers answer "how wide" differently, and each answer is right for its surface: the
 * book takes a quarter of its panel, which is a share of the surface it is drawn on, while the HUD element
 * is a placed thing and has to fit its own sentences. So the two bounds live here and the arithmetic
 * between them is the caller's.
 */
public final class ToastArt {

    /** One notice's height in pixels. */
    public static final int LINE = 14;

    /** The clear space between two of them. */
    public static final int GAP = 3;

    /** The room a sentence is given inside its box, on each side. */
    public static final int PAD = 4;

    /**
     * The narrowest a notice box may be.
     *
     * <p>A quarter of the book's own width at a reader's window, and the number that was inline there
     * before: a box narrower than this reads as a clipped word rather than as a sentence.
     */
    public static final int MIN_WIDTH = 90;

    /** And the widest, because a notice is a sentence and not a dialogue, however long the sentence is. */
    public static final int MAX_WIDTH = 240;

    private ToastArt() {
    }

    /**
     * The whole height a stack of this many rows stands in, gaps included.
     *
     * <p>What a measured element needs from its content: the HUD element's own box <i>is</i> this number,
     * so a caller that drew it from a count and a caller that clamped the window against a different one
     * would be two answers to "how tall is this".
     */
    public static int height(int rows) {
        return rows <= 0 ? 0 : rows * LINE + (rows - 1) * GAP;
    }

    /**
     * How wide a box has to be for these sentences: the widest of them, padded, clamped.
     *
     * <p>Not the book's answer, which is a share of its panel -- see the class note.
     */
    public static int width(List<ToastStack.Toast> visible, Measure measure) {
        int widest = MIN_WIDTH;
        for (ToastStack.Toast toast : visible) {
            widest = Math.max(widest, measure.width(toast.text()) + PAD * 2);
        }
        return Math.min(widest, MAX_WIDTH);
    }

    /** How far a fresh notice climbs while it fades in: enough to read as arriving, not floating. */
    public static final int RISE = 4;

    /**
     * Draws a stack, oldest first, with its first row's own top-left at {@code (x, y)}.
     *
     * <p>Oldest first rather than newest, so a caller that anchors the <i>newest</i> at a fixed edge gets
     * the ones already being read moving away rather than being pushed off: that is the order
     * {@link ToastStack#visible} answers in, and reversing it here would put the decision in two places.
     *
     * <p>A fresh row also climbs {@link #RISE} pixels while its alpha ramps: a fade alone brightens in
     * place, while the climb is what says "this just landed". Both come from the toast's own arrival,
     * so the book and the HUD cannot disagree about either.
     *
     * @param dim how much of the fill to draw, 0 to 1. The book passes 1: its stack looks as it always has,
     *            and a HUD element passes its own slider. Multiplied into the per-toast fade alpha, so a
     *            half-dimmed row still fades out rather than popping.
     */
    public static void draw(GuiRenderer r, Measure measure, List<ToastStack.Toast> visible, int x, int y,
                            int width, boolean motion, long now, double dim) {
        for (int i = 0; i < visible.size(); i++) {
            ToastStack.Toast toast = visible.get(i);
            int top = y + i * (LINE + GAP) + Math.round((1F - toast.arrival(now, motion)) * RISE);
            float alpha = toast.alpha(now, motion) * (float) dim;
            int colour = toast.error() ? ArmatureTheme.blocked() : ArmatureTheme.body();
            ArmatureTheme.panel(r, x, top, width, LINE,
                    Colour.alphaOf(ArmatureTheme.raised(), alpha),
                    Colour.alphaOf(toast.error() ? ArmatureTheme.blocked() : ArmatureTheme.panelEdge(), alpha));
            r.text(Measure.truncate(toast.text(), width - PAD * 2, measure), x + PAD,
                    top + (LINE - 8) / 2, Colour.alphaOf(colour, alpha));
        }
    }
}
