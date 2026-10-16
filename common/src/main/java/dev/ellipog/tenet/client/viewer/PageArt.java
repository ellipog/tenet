package dev.ellipog.tenet.client.viewer;

import dev.ellipog.armature.client.render.GuiRenderer;
import dev.ellipog.armature.client.render.TextEpoch;
import dev.ellipog.armature.client.ui.kit.Measure;

import net.minecraft.resources.Identifier;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.List;

/**
 * The little drawing every adapter repeats: the truncation fit, a status pill, and the pin star's
 * two textures.
 *
 * <p>Three adapters draw the same row twice over — the same label, the same badge — and the pieces
 * that must not drift are the arithmetic ones: how a string is trimmed to a pixel width, and how big
 * a pill is for the word inside it. They live here, mod-free and viewer-free, so the three pages
 * cannot disagree about a pill's height or a trim's ellipsis. The star is here for the same reason
 * one level up: one adapter draws it, but the image it draws is the content's, not a viewer's.
 */
public final class PageArt {

    /** A pill's height: a word's line plus a pixel of padding above and below. */
    public static final int PILL_HEIGHT = 10;
    private static final int PILL_PADDING = 3;

    private PageArt() {
    }

    /**
     * Trims a string to a pixel width, for text a viewer draws without a wrap.
     *
     * <p>ASCII ellipsis deliberately: the font's coverage is measured, and a codepoint outside it is
     * a box rather than a character.
     *
     * <h2>The measurement is memoized, and the rule is not</h2>
     *
     * <p>This rule is <b>not</b> {@code Measure.truncate}, and that is on purpose: the two differ in
     * their ellipsis (this one is ASCII for the font's sake) and that difference was decided, not
     * inherited. What is shared is the cost: this walks a label a character at a time asking how wide
     * each prefix is, and a viewer redraws its rows every frame with the labels they had last frame. So
     * the measuring goes through a memo and the shrinking stays here, which is the seam that lets the
     * one rule each keep its own answer while both stop paying for the same question.
     *
     * <p>{@link #measuring} is set per call rather than the memo being rebuilt — a memo built per call
     * would be empty every time — and the epoch is what stops a remembered width outliving the font or
     * the player's text scale. See {@link TextEpoch}.
     */
    public static String fit(GuiRenderer renderer, String text, int width) {
        measuring = renderer;
        if (width <= 0 || FIT.width(text) <= width) {
            return width <= 0 ? "" : text;
        }
        // Binary search for the longest prefix whose ellipsis form still fits, rather than
        // shrinking a character at a time: the walk allocated a cut plus a concat per character,
        // and the widths underneath are memoised either way. The probe measures the concatenated
        // form rather than adding two widths, because that is exactly what the walk asked — and
        // what the drawing draws.
        String ellipsis = "...";
        if (FIT.width(ellipsis) > width) {
            return "";
        }
        int lo = 0;
        int hi = text.length();
        while (hi - lo > 1) {
            int mid = (lo + hi) >>> 1;
            if (FIT.width(text.substring(0, mid) + ellipsis) <= width) {
                lo = mid;
            }
            else {
                hi = mid;
            }
        }
        return lo == 0 ? "" : text.substring(0, lo) + ellipsis;
    }

    /**
     * The renderer the memo is measuring with. See {@link #fit}.
     *
     * <p>Static and mutable, which is worth naming rather than leaving to be discovered: a viewer's rows
     * are drawn on the client's render thread — EMI registers its recipes on a worker, but nothing there
     * measures text — so there is one writer and one reader, and the alternative (a memo per renderer)
     * would be a memo per frame with nothing in it.
     */
    private static GuiRenderer measuring;

    /**
     * The memo. Its line height is the font's own and is never read — {@code fit} asks only how wide a
     * string is — but the interface needs a number for the question, and a caller that one day does ask
     * should get a sensible answer rather than a zero.
     */
    private static final Measure FIT =
            Measure.cached(Measure.of(text -> measuring.textWidth(text), 9), TextEpoch::now);

    /** How wide a pill is for this word. */
    public static int pillWidth(GuiRenderer renderer, String text) {
        return renderer.textWidth(text) + 2 * PILL_PADDING;
    }

    /**
     * Draws a status pill: a filled rectangle with its word on it, white so the fill carries it.
     *
     * @return the pill's width, for a caller placing it against a right edge
     */
    public static int pill(GuiRenderer renderer, String text, int x, int y, int colour) {
        int width = pillWidth(renderer, text);
        renderer.fill(x, y, x + width, y + PILL_HEIGHT, colour);
        renderer.text(text, x + PILL_PADDING, y + 1, PagePalette.PILL_TEXT);
        return width;
    }

    /**
     * A reward row's standing, or null when the quest is not finished and there is nothing to say.
     *
     * <p>One rule for three adapters, so a page cannot call the same row Ready in one viewer and
     * Claimed in another.
     */
    public static QuestContent.RewardStatus rewardStatus(QuestRow live) {
        if (live.locked()) {
            return QuestContent.RewardStatus.LOCKED;
        }
        if (live.done()) {
            return QuestContent.RewardStatus.CLAIMED;
        }
        return live.claimable() ? QuestContent.RewardStatus.READY : null;
    }

    /** The colour a reward status is drawn in: the same three the palette uses elsewhere. */
    public static int rewardStatusColour(QuestContent.RewardStatus status) {
        return switch (status) {
            case READY -> PagePalette.COMPLETE;
            case LOCKED -> PagePalette.LOCKED;
            case CLAIMED -> PagePalette.MUTED;
        };
    }

    // ------------------------------------------------------------------
    // The star, and what a quest looks like in a viewer's sidebar
    // ------------------------------------------------------------------

    /**
     * The two pin-star textures: the empty outline for an unpinned quest, the filled one for a
     * pinned quest.
     *
     * <p>Files rather than a drawn shape, and that is the point now. The old mask guaranteed the two
     * states were the same star by computing the hollow ring from the filled pixels; two images
     * cannot be made to agree that way, so they are drawn as a pair and ship together — a pack that
     * replaces one replaces both.
     */
    public static final Identifier STAR_EMPTY =
            Identifier.fromNamespaceAndPath("tenet", "textures/gui/star_empty.png");
    public static final Identifier STAR_FILLED =
            Identifier.fromNamespaceAndPath("tenet", "textures/gui/star_filled.png");

    /** The star's side in pixels: the two images' size, and so the pin button's. */
    private static final int STAR_SIZE = 12;

    /** The star's drawn size in pixels -- and so the pin button's, which the layout reserves. */
    public static int starSize() {
        return STAR_SIZE;
    }

    /**
     * Draws the pin star: the filled texture when the quest is pinned, the empty one otherwise.
     *
     * <p>Hover is not this method's: the header draws its own wash behind the button, so the star
     * itself is one texture and no state.
     */
    public static void star(GuiRenderer renderer, int x, int y, boolean pinned) {
        renderer.texture(pinned ? STAR_FILLED : STAR_EMPTY, x, y, STAR_SIZE, STAR_SIZE);
    }

    /**
     * What a viewer's recipe for this quest offers as its outputs.
     *
     * <p>The item rewards, in order; and when there are none — an xp-only, stage or command reward —
     * the quest's own icon, with the category's icon behind it as a last resort. Never empty, and
     * that is the point: a viewer favourites a recipe by its first output, draws the sidebar entry
     * from it, and refuses an empty stack, so an empty list is the difference between a quest that
     * can be pinned and one that cannot.
     */
    public static List<ItemStack> outputs(QuestPage page, ItemStack fallbackIcon) {
        List<ItemStack> out = new ArrayList<>();
        for (QuestRow row : page.rewards()) {
            if (!row.icon().isEmpty()) {
                out.add(row.icon());
            }
        }
        if (out.isEmpty() && !page.quest().icon().isEmpty()) {
            out.add(page.quest().icon());
        }
        if (out.isEmpty() && fallbackIcon != null && !fallbackIcon.isEmpty()) {
            out.add(fallbackIcon);
        }
        return List.copyOf(out);
    }
}
