package dev.ellipog.tenet.client.dev;

import dev.ellipog.armature.client.render.GuiGraphicsRenderer;
import dev.ellipog.armature.client.render.GuiRenderer;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.network.chat.Component;

/**
 * Two controls sharing one row, for the scalar pairs the panel would otherwise spend a band each on.
 *
 * <h2>Why this exists rather than two widgets</h2>
 *
 * <p>Because of how a row is placed. The list is a {@code ScrollView}: a row's rectangle is found by its
 * key, and one key places one widget — the scroll, the clip and the "hide a half-scrolled row" rule all
 * read that one rectangle. Two widgets stacked at the same y would mean a second placement path that
 * could disagree with the first about where the row is, which is the class of fault this codebase writes
 * comments about. So the row is one widget and the two halves are its children: they are positioned from
 * the row's own bounds, and they are never added to the screen themselves.
 *
 * <h2>What the children keep</h2>
 *
 * <p>Everything: the pair forwards the pointer to whichever half was pressed and the keyboard to whichever
 * half has the caret, and blurs the other when focus moves. A half that was never pressed answers
 * nothing, which is what makes a press on the row's label area fall through to the screen — labels are
 * drawn by the panel, and a label press that becomes a drag is the screen's gesture to route (see
 * {@link ScrubField#beginLabelScrub}).
 */
public final class ScrubPairField extends AbstractWidget {

    private final AbstractWidget left;
    private final AbstractWidget right;

    /** The half the last press landed on, so the drag and release go to the same one. */
    private AbstractWidget pressed;

    /** The half the keyboard belongs to. Remembered across blurs, so a returned focus lands back in it. */
    private AbstractWidget lastFocused;

    public ScrubPairField(AbstractWidget left, AbstractWidget right) {
        super(0, 0, 0, 0, Component.empty());
        this.left = left;
        this.right = right;
        this.active = true;
    }

    /**
     * Positions both halves from the widget's own bounds.
     *
     * <p>Called at the top of every entry point rather than once, because a widget is moved by its caller
     * assigning x and y -- there is no "on placed" hook to hang this on — and both the drawing and the
     * hit test have to see the same halves the current frame's scroll implies. The split is
     * {@link ToolsLayout#PAIR_GAP}'s, the same one the panel draws the two labels by.
     */
    private void place() {
        int half = Math.max(0, (getWidth() - ToolsLayout.PAIR_GAP) / 2);
        lay(left, getX(), half);
        lay(right, getX() + half + ToolsLayout.PAIR_GAP,
                Math.max(0, getWidth() - half - ToolsLayout.PAIR_GAP));
    }

    private void lay(AbstractWidget widget, int x, int width) {
        widget.setX(x);
        widget.setY(getY());
        widget.setWidth(width);
        widget.setHeight(getHeight());
        widget.visible = visible;
        widget.active = active;
    }

    @Override
    public boolean mouseClicked(net.minecraft.client.input.MouseButtonEvent event, boolean doubleClick) {
        place();
        // Inactive or hidden answers nothing at all, so a press falls through to whatever is behind --
        // the same rule the single-field rows follow.
        if (!isActive() || !visible || !isValidClickButton(event.buttonInfo())
                || !isMouseOver(event.x(), event.y())) {
            return false;
        }
        if (left.mouseClicked(event, doubleClick)) {
            pressed = left;
            return true;
        }
        if (right.mouseClicked(event, doubleClick)) {
            pressed = right;
            return true;
        }
        // The label area between and around the boxes: not the pair's. Returning false keeps the
        // container from focusing this row, and the screen's own hit test then gets its chance to start
        // a label scrub.
        return false;
    }

    @Override
    public boolean mouseDragged(net.minecraft.client.input.MouseButtonEvent event, double dragX,
            double dragY) {
        place();
        return pressed != null && pressed.mouseDragged(event, dragX, dragY);
    }

    @Override
    public boolean mouseReleased(net.minecraft.client.input.MouseButtonEvent event) {
        place();
        if (pressed == null) {
            return false;
        }
        AbstractWidget was = pressed;
        pressed = null;
        if (was.isFocused()) {
            lastFocused = was;
        }
        return was.mouseReleased(event);
    }

    @Override
    public boolean keyPressed(net.minecraft.client.input.KeyEvent event) {
        AbstractWidget target = keyboard();
        return target != null && target.keyPressed(event);
    }

    @Override
    public boolean charTyped(net.minecraft.client.input.CharacterEvent event) {
        AbstractWidget target = keyboard();
        return target != null && target.charTyped(event);
    }

    /** The half holding the caret, if any. */
    private AbstractWidget keyboard() {
        if (left.isFocused()) {
            return left;
        }
        return right.isFocused() ? right : null;
    }

    @Override
    public void setFocused(boolean focused) {
        super.setFocused(focused);
        if (focused) {
            AbstractWidget target = keyboard() != null ? keyboard() : lastFocused;
            if (target != null) {
                target.setFocused(true);
            }
        }
        else {
            // Blur is a commit for a field: both halves are told, and each decides whether it was
            // holding anything to write.
            if (left.isFocused()) {
                left.setFocused(false);
            }
            if (right.isFocused()) {
                right.setFocused(false);
            }
        }
    }

    @Override
    protected void extractWidgetRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY,
            float partialTick) {
        render(new GuiGraphicsRenderer(graphics), mouseX, mouseY);
    }

    /** The same, through the seam: the pair draws both halves with the frame's own pointer. */
    public void render(GuiRenderer r, int mouseX, int mouseY) {
        place();
        if (left instanceof ScrubField field) {
            field.render(r, mouseX, mouseY);
        }
        else if (left instanceof ChoiceField choice) {
            choice.render(r, mouseX, mouseY);
        }
        if (right instanceof ScrubField field) {
            field.render(r, mouseX, mouseY);
        }
        else if (right instanceof ChoiceField choice) {
            choice.render(r, mouseX, mouseY);
        }
    }

    @Override
    protected void updateWidgetNarration(NarrationElementOutput output) {
        defaultButtonNarrationText(output);
    }
}
