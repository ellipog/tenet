package dev.ellipog.tenet.client;

import dev.ellipog.armature.client.ArmatureTheme;
import dev.ellipog.armature.client.render.GuiRenderer;
import net.minecraft.network.chat.Component;
import com.mojang.blaze3d.platform.InputConstants;
import java.util.List;

/**
 * The quest book's first-run intro: the tour a new reader is walked through, one rung at a time.
 *
 * <p>Moved out of {@link QuestBookScreen} as it stood, with the state only it reads; the screen
 * keeps the state it shares and reaches this through its {@code bookIntro} field.
 */
final class BookIntro {

    private final QuestBookScreen book;

    BookIntro(QuestBookScreen book) {
        this.book = book;
    }

    private BookGeometry.Rect introTipBox;
    private BookGeometry.Rect introSkipBox;
    private BookGeometry.Rect introNextBox;
    private BookGeometry.Rect introKeepBox;
    private BookGeometry.Rect introDontBox;

    // ------------------------------------------------------------------
    // The first-run intro
    // ------------------------------------------------------------------

    /**
     * The intro's spotlight and tooltip, drawn last so they beat every tooltip.
     *
     * <p>Over everything but owning nothing: the scrim dims the book around the rung's anchor
     * while every control under it stays live, because the rung advances on the author pressing
     * the real {@code + Chapter} strip, the real canvas, the real add rows — never the tooltip.
     * The tooltip's own buttons are the only presses this answers (see
     * {@link #pressIntroGuide}), so a rung can never trap the book: Esc closes over it, and any
     * other press falls straight through.
     *
     * <p>An anchor that is not on screen falls back to the region that holds it — a collapsed
     * sidebar's strip for its toolbar, the canvas for a quest card that is not open — and the
     * copy names the control, so the tour points at where to go rather than at air.
     */
    void drawIntroGuide(GuiRenderer r, int mouseX, int mouseY) {
        if (!IntroGuide.isActive()) {
            introTipBox = null;
            introSkipBox = null;
            introNextBox = null;
            introKeepBox = null;
            introDontBox = null;
            return;
        }
        BookGeometry.Rect anchor = introAnchor();
        if (anchor != null) {
            // Four fills around the anchor rather than one with a hole: a quad over the anchor
            // would dim the control the rung needs pressed.
            int dim = ArmatureTheme.dim();
            r.fill(0, 0, book.width, anchor.y(), dim);
            r.fill(0, anchor.bottom(), book.width, book.height, dim);
            r.fill(0, anchor.y(), anchor.x(), anchor.bottom(), dim);
            r.fill(anchor.right(), anchor.y(), book.width, anchor.bottom(), dim);
            // Two pixels of the title ink: the shape is the spotlight, and the ink is the
            // brightest the theme owns, so it reads on charcoal and parchment alike.
            int ring = ArmatureTheme.title();
            r.fill(anchor.x() - 2, anchor.y() - 2, anchor.right() + 2, anchor.y(), ring);
            r.fill(anchor.x() - 2, anchor.bottom(), anchor.right() + 2, anchor.bottom() + 2, ring);
            r.fill(anchor.x() - 2, anchor.y(), anchor.x(), anchor.bottom(), ring);
            r.fill(anchor.right(), anchor.y(), anchor.right() + 2, anchor.bottom(), ring);
        }
        else {
            // The done card: centred over the whole book, dimmed like everything it celebrates.
            r.fill(0, 0, book.width, book.height, ArmatureTheme.dim());
        }

        if (book.bookState.naming != null || !book.bookState.menu.isEmpty()
                || book.bookState.pickingEntryType != null) {
            // Something the author is reading is open — the naming card, a right-click menu, the
            // type picker — so the tooltip stands aside while the spotlight stays: covering the menu
            // that says "New quest here" with a tooltip that says nearly the same would be the tour
            // getting in its own way. The buttons go with it, so no press can land on a tooltip
            // that is not drawn.
            introTipBox = null;
            introSkipBox = null;
            introNextBox = null;
            introKeepBox = null;
            introDontBox = null;
            return;
        }

        IntroGuide.Step step = IntroGuide.step();
        boolean done = step == IntroGuide.Step.DONE;
        String title = Component.translatable(introTitleKey(step)).getString();
        List<String> body = done && !IntroGuide.completedByDoing()
                ? List.of(Component.translatable("tenet.screen.intro.skipped.1").getString(),
                        Component.translatable("tenet.screen.intro.skipped.2").getString())
                : List.of(Component.translatable(introBodyKey(step, 1)).getString(),
                        Component.translatable(introBodyKey(step, 2)).getString());

        int pad = 8;
        int lineHeight = r.lineHeight();
        int boxWidth = 240;
        int buttonHeight = 13;
        // Measured before placing, because the checkbox drops to its own row rather than
        // overlapping the buttons: a centred button and a right-aligned checkbox share the
        // middle of a narrow box, and a translation longer than the English makes even the
        // left-aligned row collide. The box grows for the second row before it is placed, so
        // the placement still holds wholly on screen.
        String keep = Component.translatable("tenet.screen.intro.keep").getString();
        String skip = Component.translatable("tenet.screen.intro.skip").getString();
        String next = Component.translatable("tenet.screen.intro.next").getString();
        String dont = Component.translatable("tenet.screen.intro.dontshow").getString();
        int keepWidth = r.textWidth(keep) + 12;
        int skipWidth = r.textWidth(skip) + 12;
        int nextWidth = r.textWidth(next) + 12;
        int dontWidth = 9 + 4 + r.textWidth(dont);
        int buttonsEnd = pad + (done ? keepWidth : skipWidth + 6 + nextWidth);
        boolean wrapped = buttonsEnd + 6 + dontWidth > boxWidth - pad;
        int checkHeight = Math.max(9, lineHeight);
        int boxHeight = pad + lineHeight + 4 + lineHeight * body.size() + 6 + buttonHeight + pad
                + (wrapped ? 4 + checkHeight : 0);
        int[] placed = placeIntroBox(anchor, boxWidth, boxHeight);
        int boxX = placed[0];
        int boxY = placed[1];
        introTipBox = BookGeometry.Rect.at(boxX, boxY, boxWidth, boxHeight);

        ArmatureTheme.panel(r, boxX, boxY, boxWidth, boxHeight,
                ArmatureTheme.tooltipFill(), ArmatureTheme.tooltipEdge());
        int textY = boxY + pad;
        r.text(title, boxX + pad, textY, ArmatureTheme.title());
        textY += lineHeight + 4;
        for (String line : body) {
            r.text(line, boxX + pad, textY, ArmatureTheme.tooltipText());
            textY += lineHeight;
        }
        int buttonsY = boxY + boxHeight - pad - buttonHeight - (wrapped ? 4 + checkHeight : 0);
        if (done) {
            int keepX = boxX + pad;
            drawIntroButton(r, keepX, buttonsY, keepWidth, buttonHeight, keep,
                    keepX <= mouseX && mouseX < keepX + keepWidth
                            && buttonsY <= mouseY && mouseY < buttonsY + buttonHeight);
            introKeepBox = BookGeometry.Rect.at(keepX, buttonsY, keepWidth, buttonHeight);
            introSkipBox = null;
            introNextBox = null;
        }
        else {
            int skipX = boxX + pad;
            int nextX = skipX + skipWidth + 6;
            drawIntroButton(r, skipX, buttonsY, skipWidth, buttonHeight, skip,
                    skipX <= mouseX && mouseX < skipX + skipWidth
                            && buttonsY <= mouseY && mouseY < buttonsY + buttonHeight);
            drawIntroButton(r, nextX, buttonsY, nextWidth, buttonHeight, next,
                    nextX <= mouseX && mouseX < nextX + nextWidth
                            && buttonsY <= mouseY && mouseY < buttonsY + buttonHeight);
            introSkipBox = BookGeometry.Rect.at(skipX, buttonsY, skipWidth, buttonHeight);
            introNextBox = BookGeometry.Rect.at(nextX, buttonsY, nextWidth, buttonHeight);
            introKeepBox = null;
        }
        // The "Don't show again" checkbox: on the button row when it fits beside the buttons,
        // on its own row below when it does not. Either way right-aligned, and the durable flag
        // and nothing else — checking it never ends the tour, it only stops the next one.
        int dontX = boxX + boxWidth - pad - dontWidth;
        int dontY = wrapped
                ? buttonsY + buttonHeight + 4 + (checkHeight - 9) / 2
                : buttonsY + (buttonHeight - 9) / 2;
        boolean dontOn = IntroGuide.dontShowAgain();
        boolean dontHover = dontX <= mouseX && mouseX < dontX + dontWidth
                && dontY <= mouseY && mouseY < dontY + 9;
        if (dontHover) {
            r.fill(dontX - 2, dontY - 2, dontX + dontWidth + 2, dontY + 11, ArmatureTheme.rowHover());
        }
        r.fill(dontX, dontY, dontX + 9, dontY + 1, ArmatureTheme.tooltipEdge());
        r.fill(dontX, dontY + 8, dontX + 9, dontY + 9, ArmatureTheme.tooltipEdge());
        r.fill(dontX, dontY, dontX + 1, dontY + 9, ArmatureTheme.tooltipEdge());
        r.fill(dontX + 8, dontY, dontX + 9, dontY + 9, ArmatureTheme.tooltipEdge());
        if (dontOn) {
            r.fill(dontX + 2, dontY + 2, dontX + 7, dontY + 7, ArmatureTheme.title());
        }
        r.text(dont, dontX + 13, dontY + (9 - lineHeight) / 2, ArmatureTheme.tooltipText());
        introDontBox = BookGeometry.Rect.at(dontX - 2, dontY - 2, dontWidth + 4,
                Math.max(13, checkHeight + 4));
    }

    /**
     * The intro's title key for a rung, written whole.
     *
     * <p>Whole literals rather than a prefix plus the rung's name, because the translation sweep
     * reads literals rather than concatenations: a built key would render fine in game and fail
     * the sweep as an orphan, which is exactly the failure that sweep exists to catch.
     */
    private static String introTitleKey(IntroGuide.Step step) {
        return switch (step) {
            case CHAPTER -> "tenet.screen.intro.chapter.title";
            case QUEST -> "tenet.screen.intro.quest.title";
            case TASK -> "tenet.screen.intro.task.title";
            case REWARD -> "tenet.screen.intro.reward.title";
            case DONE -> "tenet.screen.intro.done.title";
        };
    }

    /** The intro's body key for a rung's line, written whole for the sweep's reason above. */
    private static String introBodyKey(IntroGuide.Step step, int line) {
        return switch (step) {
            case CHAPTER -> line == 1 ? "tenet.screen.intro.chapter.1" : "tenet.screen.intro.chapter.2";
            case QUEST -> line == 1 ? "tenet.screen.intro.quest.1" : "tenet.screen.intro.quest.2";
            case TASK -> line == 1 ? "tenet.screen.intro.task.1" : "tenet.screen.intro.task.2";
            case REWARD -> line == 1 ? "tenet.screen.intro.reward.1" : "tenet.screen.intro.reward.2";
            case DONE -> line == 1 ? "tenet.screen.intro.done.1" : "tenet.screen.intro.done.2";
        };
    }

    /** One of the tooltip's own buttons: a wash on hover and the label centred in it. */
    private void drawIntroButton(GuiRenderer r, int x, int y, int w, int h, String label,
            boolean hovered) {
        if (hovered) {
            r.fill(x, y, x + w, y + h, ArmatureTheme.rowHover());
        }
        r.fill(x, y + h - 1, x + w, y + h, ArmatureTheme.tooltipEdge());
        r.text(label, x + (w - r.textWidth(label)) / 2, y + (h - r.lineHeight()) / 2,
                hovered ? ArmatureTheme.title() : ArmatureTheme.tooltipText());
    }

    /**
     * Where the tooltip goes: beside its anchor, or centred for the done card.
     *
     * <p>Below, above, right, left — the first that fits wholly on screen, like the tooltip
     * toolkit's own caption placement, because a tour that covers its own spotlight is pointing
     * at itself. A box that fits nowhere is clamped onto the screen rather than left hanging
     * off it, which is degradation rather than an error: a window that small is already
     * clipping the book.
     */
    private int[] placeIntroBox(BookGeometry.Rect anchor, int boxWidth, int boxHeight) {
        if (anchor == null) {
            return new int[] {(book.width - boxWidth) / 2, (book.height - boxHeight) / 2};
        }
        int margin = 4;
        int gap = 6;
        int[][] candidates = {
                {anchor.x(), anchor.bottom() + gap},
                {anchor.x(), anchor.y() - gap - boxHeight},
                {anchor.right() + gap, anchor.y()},
                {anchor.x() - gap - boxWidth, anchor.y()}};
        for (int[] at : candidates) {
            if (at[0] >= margin && at[1] >= margin
                    && at[0] + boxWidth <= book.width - margin
                    && at[1] + boxHeight <= book.height - margin) {
                return at;
            }
        }
        return new int[] {
                Math.max(margin, Math.min(candidates[0][0], book.width - boxWidth - margin)),
                Math.max(margin, Math.min(candidates[0][1], book.height - boxHeight - margin))};
    }

    /**
     * The rung's anchor, in screen coordinates — or null for the done card, which is centred.
     *
     * <p>Live every frame rather than remembered, because the sidebar slides, the window
     * resizes and the quest card opens: an anchor from last frame is where the control was, and
     * the spotlight must be where it is. Whatever is missing falls back to the region that
     * holds it, so the tour degrades to pointing at the neighbourhood rather than at air.
     */
    private BookGeometry.Rect introAnchor() {
        return switch (IntroGuide.step()) {
            case CHAPTER -> {
                // The strip's own + Chapter half; the strip itself when collapsed, which has no
                // halves — pressing it expands the list, and the next frame anchors the half.
                BookGeometry.Rect add =
                        book.geometry().sidebarToolbar().get("addChapter");
                yield add != null ? add : book.geometry().sidebar();
            }
            case QUEST -> book.geometry().canvas();
            // The card's own add rows once it is open, else the canvas they will be made on.
            case TASK -> book.bookCanvas.introTaskBox != null ? book.bookCanvas.introTaskBox : book.geometry().canvas();
            case REWARD -> book.bookCanvas.introRewardBox != null ? book.bookCanvas.introRewardBox
                    : book.geometry().canvas();
            case DONE -> null;
        };
    }

    /**
     * One of the tooltip's own presses — and nothing else.
     *
     * <p>Asked first in {@code mouseClicked} and answering only its own buttons, so the press on
     * the spotlighted control falls through to the book untouched: the tour watches the author
     * work, it never works for them.
     */
    boolean pressIntroGuide(double mouseX, double mouseY, int button) {
        if (button != InputConstants.MOUSE_BUTTON_LEFT) {
            return false;
        }
        if (introKeepBox != null && introKeepBox.contains(mouseX, mouseY)) {
            IntroGuide.dismiss();
            return true;
        }
        if (introNextBox != null && introNextBox.contains(mouseX, mouseY)) {
            IntroGuide.skipStep();
            return true;
        }
        if (introSkipBox != null && introSkipBox.contains(mouseX, mouseY)) {
            IntroGuide.skip();
            return true;
        }
        if (introDontBox != null && introDontBox.contains(mouseX, mouseY)) {
            IntroGuide.setDontShowAgain(!IntroGuide.dontShowAgain());
            return true;
        }
        return false;
    }
}
