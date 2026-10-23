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
        int buttonHeight = BookGeometry.OVERLAY_CONTROL_HEIGHT;
        int box = dev.ellipog.tenet.client.dev.EntryFormLayout.BUTTON;
        // The bottom row reads like every other card's footer: the quiet choice on the left, the
        // buttons on the right with the way forward last and accented. Measured before placing,
        // because the checkbox takes a row of its own when the two do not fit side by side -- a
        // translation longer than the English would otherwise run the box into the buttons -- and
        // the box grows for that row before it is placed, so the placement still holds on screen.
        String keep = Component.translatable("tenet.screen.intro.keep").getString();
        String skip = Component.translatable("tenet.screen.intro.skip").getString();
        String next = Component.translatable("tenet.screen.intro.next").getString();
        String dont = Component.translatable("tenet.screen.intro.dontshow").getString();
        int keepWidth = r.textWidth(keep) + 16;
        int skipWidth = r.textWidth(skip) + 16;
        int nextWidth = r.textWidth(next) + 16;
        int dontWidth = box + 4 + r.textWidth(dont);
        int buttonsWidth = done ? keepWidth : skipWidth + 4 + nextWidth;
        boolean wrapped = dontWidth + 8 + buttonsWidth > boxWidth - pad * 2;
        int checkRow = Math.max(box, lineHeight);
        int boxHeight = pad + lineHeight + 4 + lineHeight * body.size() + 8 + buttonHeight + pad
                + (wrapped ? 4 + checkRow : 0);
        int[] placed = placeIntroBox(anchor, boxWidth, boxHeight);
        int boxX = placed[0];
        int boxY = placed[1];
        introTipBox = BookGeometry.Rect.at(boxX, boxY, boxWidth, boxHeight);

        ArmatureTheme.panel(r, boxX, boxY, boxWidth, boxHeight,
                ArmatureTheme.tooltipFill(), ArmatureTheme.tooltipEdge());
        int textY = boxY + pad;
        r.text(title, boxX + pad, textY, ArmatureTheme.title());
        if (!done) {
            // Where the author stands in the four: a coach that never says how far is left reads as one
            // that may never end, which is the reason people press Skip.
            String count = Component.translatable("tenet.screen.intro.step", step.ordinal() + 1,
                    IntroGuide.Step.DONE.ordinal()).getString();
            r.text(count, boxX + boxWidth - pad - r.textWidth(count), textY, ArmatureTheme.faint());
        }
        textY += lineHeight + 4;
        for (String line : body) {
            r.text(line, boxX + pad, textY, ArmatureTheme.tooltipText());
            textY += lineHeight;
        }
        int buttonsY = boxY + boxHeight - pad - buttonHeight;
        int right = boxX + boxWidth - pad;
        if (done) {
            int keepX = right - keepWidth;
            introKeepBox = BookGeometry.Rect.at(keepX, buttonsY, keepWidth, buttonHeight);
            drawIntroButton(r, introKeepBox, keep, true, introKeepBox.contains(mouseX, mouseY));
            introSkipBox = null;
            introNextBox = null;
        }
        else {
            int nextX = right - nextWidth;
            int skipX = nextX - 4 - skipWidth;
            introSkipBox = BookGeometry.Rect.at(skipX, buttonsY, skipWidth, buttonHeight);
            introNextBox = BookGeometry.Rect.at(nextX, buttonsY, nextWidth, buttonHeight);
            drawIntroButton(r, introSkipBox, skip, false, introSkipBox.contains(mouseX, mouseY));
            drawIntroButton(r, introNextBox, next, true, introNextBox.contains(mouseX, mouseY));
            introKeepBox = null;
        }
        // The "Don't show again" checkbox: on the button row's left when it fits, on its own row above
        // the buttons when it does not. The durable flag and nothing else -- checking it never ends the
        // tour, it only stops the next one. The square is the quest editor's own switch, so the one
        // checkbox in the tour is not a smaller cousin of every other in the book.
        int rowY = wrapped ? buttonsY - 4 - checkRow : buttonsY + (buttonHeight - checkRow) / 2;
        int dontX = boxX + pad;
        introDontBox = BookGeometry.Rect.at(dontX - 2, rowY - 2, dontWidth + 4, checkRow + 4);
        if (introDontBox.contains(mouseX, mouseY)) {
            r.fill(introDontBox.x(), introDontBox.y(), introDontBox.right(), introDontBox.bottom(),
                    ArmatureTheme.rowHover());
        }
        int lift = (dev.ellipog.tenet.client.dev.EntryFormLayout.LINE_HEIGHT - checkRow) / 2;
        book.questEditor.drawSwitch(r, BookGeometry.Rect.at(dontX, rowY - lift, box, checkRow),
                IntroGuide.dontShowAgain());
        r.text(dont, dontX + box + 4, rowY + (checkRow - lineHeight) / 2, ArmatureTheme.tooltipText());
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

    /**
     * One of the tooltip's own buttons, in the book's button style: Skip plain, the way forward accented.
     *
     * <p>They were underlined words, the one place in the book a button was not a box -- and the only
     * thing telling Next from Skip was its order.
     */
    private void drawIntroButton(GuiRenderer r, BookGeometry.Rect at, String label, boolean forward,
            boolean hovered) {
        dev.ellipog.armature.client.ArmatureControlStyle.Variant variant = forward
                ? dev.ellipog.armature.client.ArmatureControlStyle.Variant.ACCENT
                : dev.ellipog.armature.client.ArmatureControlStyle.Variant.PLAIN;
        ArmatureTheme.panel(r, at.x(), at.y(), at.width(), at.height(),
                dev.ellipog.armature.client.ArmatureControlStyle.fillAt(variant, true, false, hovered ? 1F : 0F),
                dev.ellipog.armature.client.ArmatureControlStyle.edge(variant, true, false, hovered));
        int ink = forward ? dev.ellipog.armature.client.ArmatureControlStyle.text(variant, true)
                : hovered ? ArmatureTheme.title() : ArmatureTheme.body();
        r.text(label, at.x() + (at.width() - r.textWidth(label)) / 2,
                at.y() + (at.height() - r.lineHeight()) / 2 + 1, ink);
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
        // Nowhere outside it: the anchor is most of the screen -- the canvas, for the quest and task
        // rungs. Inside it then, along its bottom and centred, which is open canvas. The corner this used
        // to clamp to was the anchor's own, and the canvas's corner is under the floating sidebar.
        int insideX = anchor.x() + (anchor.width() - boxWidth) / 2;
        int insideY = anchor.bottom() - boxHeight - gap * 2;
        return new int[] {
                Math.max(margin, Math.min(insideX, book.width - boxWidth - margin)),
                Math.max(margin, Math.min(insideY, book.height - boxHeight - margin))};
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
