package dev.ellipog.tenet.client.dev;

import dev.ellipog.armature.client.ArmatureTheme;
import dev.ellipog.armature.client.render.GuiGraphicsRenderer;
import dev.ellipog.armature.client.render.GuiRenderer;
import dev.ellipog.armature.client.ui.kit.Colour;
import dev.ellipog.armature.client.ui.kit.TextField;
import dev.ellipog.tenet.client.BookGeometry;

import net.minecraft.util.Util;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.network.chat.Component;

import java.util.function.DoubleConsumer;

/**
 * One number, edited two ways: dragged, or typed.
 *
 * <h2>The two modes, and the gesture that tells them apart</h2>
 *
 * <p>At rest the field shows its value formatted ({@link Scrub#format}) — "14 px", "20%". A press and
 * release in the value box focuses it and marks the whole text, so the first keystroke replaces it: the
 * value is one number, and making a player select it by hand before typing is the kind of friction that
 * makes people drag instead. A press that travels more than {@link Scrub#SLOP} pixels before release is
 * a scrub: the value follows horizontal travel, and the box streams the changes to
 * {@link #onPreview(DoubleConsumer)} — which is what lets the canvas show the edit before anything is
 * written.
 *
 * <h2>One commit, whatever the gesture</h2>
 *
 * <p>The value is written once: on release for a scrub, on Enter or on losing focus for typing, and only
 * when it differs from where the gesture started. That last clause is not an optimisation. Every value a
 * field writes becomes an {@code EditorOp}, and the server snapshots the chapter before each one — so a
 * commit of the value that was already there would be an undo step that undoes nothing.
 *
 * <h2>What is the widget's and what is the screen's</h2>
 *
 * <p>The value box, the gesture, the typing and the {@code ↔} cue are the widget's. The label is the
 * panel's, which is why the press that starts a label scrub arrives through {@link #beginLabelScrub}:
 * a label is not a widget, so the screen (which knows where the rows are) is what finds the field a
 * label belongs to and hands it the gesture.
 */
public final class ScrubField extends AbstractWidget {

    /** The value box's width. The rest of the widget's bounds are its label's, drawn by the panel. */
    public static final int BOX_WIDTH = 84;

    private static final int PAD = 4;

    /** What the field holds. The text is only live while focused; at rest the formatted value is drawn. */
    private final TextField model = TextField.of(12);

    private double value;
    private final double min;
    private final double max;
    private final double step;
    private final String suffix;

    private DoubleConsumer onPreview = next -> {
    };
    private DoubleConsumer onCommit = next -> {
    };

    /** A press has been claimed; whether it is a click or a scrub is not yet decided. */
    private boolean armed;
    /** The press travelled: this gesture is an edit, and its value commits on release. */
    private boolean scrubbing;
    /** Where the press landed, so the drag is measured from it rather than accumulated. */
    private double pressX;
    /** The value the gesture started from: the scrub's anchor and the no-op check's other half. */
    private double startValue;
    /** The value the drag is currently showing. */
    private double previewValue;
    /** The value captured when the box was focused, so Escape can restore it. */
    private double focusValue;
    /** Set while Escape is restoring, so the blur it causes does not commit what was abandoned. */
    private boolean cancelling;
    /** Where the pointer last was during a scrub, so the cue can be drawn beside it. */
    private double pointerX;
    private double pointerY;
    /** Whether the claimed press was in the value box. A label press scrubs but never focuses. */
    private boolean boxPress;

    public ScrubField(double value, double min, double max, double step, String suffix) {
        super(0, 0, 0, 0, Component.empty());
        this.min = min;
        this.max = max;
        this.step = step;
        this.suffix = suffix;
        this.value = Scrub.settle(value, min, max, step);
        this.model.setValue(Scrub.format(this.value, step, suffix));
        this.model.clearHistory();
        this.active = true;
    }

    /** Sets the value from the caller's state, as the panel rebuilds. Does not commit anything. */
    public ScrubField value(double next) {
        value = Scrub.settle(next, min, max, step);
        model.setValue(Scrub.format(value, step, suffix));
        return this;
    }

    public double value() {
        return value;
    }

    /** Streamed during a gesture, without writing anything. */
    public ScrubField onPreview(DoubleConsumer handler) {
        this.onPreview = handler == null ? next -> {
        } : handler;
        return this;
    }

    /** Called once per finished edit, and only when the value actually changed. */
    public ScrubField onCommit(DoubleConsumer handler) {
        this.onCommit = handler == null ? next -> {
        } : handler;
        return this;
    }

    /** Whether a scrub is in flight, for a screen that wants to keep drawing its own cue. */
    public boolean scrubbing() {
        return scrubbing;
    }

    // ------------------------------------------------------------------
    // The label's half of the gesture
    // ------------------------------------------------------------------

    /**
     * Arms a press that landed on the field's label rather than in its box.
     *
     * <p>The screen calls this when its own hit test finds a press inside the widget's bounds but not in
     * the box — the box itself is claimed by {@link #mouseClicked}, which the widget pass delivers first.
     * The label scrubs but a label click does nothing: there is no text under the pointer to edit.
     */
    public boolean beginLabelScrub(double mouseX, double mouseY) {
        if (!isActive() || !visible || !isMouseOver(mouseX, mouseY) || box().contains(mouseX, mouseY)) {
            return false;
        }
        arm(mouseX, mouseY, false);
        return true;
    }

    /** The screen's drag, once {@link #beginLabelScrub} has claimed the press. */
    public boolean labelDragged(double mouseX, double mouseY, boolean coarse, boolean fine) {
        if (!armed) {
            return false;
        }
        return applyDrag(mouseX, mouseY, coarse, fine);
    }

    /** The screen's release, once {@link #beginLabelScrub} has claimed the press. */
    public void endLabelScrub() {
        if (armed) {
            finishGesture();
        }
    }

    // ------------------------------------------------------------------
    // The box's own gesture
    // ------------------------------------------------------------------

    @Override
    public boolean mouseClicked(net.minecraft.client.input.MouseButtonEvent event, boolean doubleClick) {
        // Only the box: a press on the label area belongs to the screen's own hit test, which forwards
        // it to `beginLabelScrub`. Returning false here is also what keeps a label click from focusing
        // the field -- the container focuses whatever answered the click.
        if (!isActive() || !visible || !isValidClickButton(event.buttonInfo())
                || !isMouseOver(event.x(), event.y()) || !box().contains(event.x(), event.y())) {
            return false;
        }
        arm(event.x(), event.y(), true);
        return true;
    }

    @Override
    public boolean mouseDragged(net.minecraft.client.input.MouseButtonEvent event, double dragX,
            double dragY) {
        if (!armed || !isValidClickButton(event.buttonInfo())) {
            return false;
        }
        return applyDrag(event.x(), event.y(), event.hasShiftDown(),
                event.hasControlDown() || event.hasAltDown());
    }

    @Override
    public boolean mouseReleased(net.minecraft.client.input.MouseButtonEvent event) {
        if (!armed || !isValidClickButton(event.buttonInfo())) {
            return false;
        }
        if (scrubbing) {
            finishGesture();
            return true;
        }
        armed = false;
        if (boxPress) {
            // A click in the box: type mode, with the whole value marked so the first keystroke replaces
            // it. The mark is the visible half of "this number is about to be retyped".
            setFocused(true);
            model.setValue(Scrub.format(value, step, suffix));
            model.selectAll();
        }
        return true;
    }

    private void arm(double mouseX, double mouseY, boolean inBox) {
        armed = true;
        scrubbing = false;
        boxPress = inBox;
        pressX = mouseX;
        startValue = value;
        previewValue = value;
        pointerX = mouseX;
        pointerY = mouseY;
    }

    /** One derivation for the press and the drag: the value follows the pointer's travel from the press. */
    private boolean applyDrag(double mouseX, double mouseY, boolean coarse, boolean fine) {
        if (!scrubbing) {
            if (Math.abs(mouseX - pressX) <= Scrub.SLOP) {
                return true;
            }
            scrubbing = true;
        }
        pointerX = mouseX;
        pointerY = mouseY;
        double next = Scrub.settle(startValue + Scrub.delta(mouseX - pressX, coarse, fine),
                min, max, step);
        if (next != previewValue) {
            previewValue = next;
            onPreview.accept(next);
        }
        return true;
    }

    private void finishGesture() {
        armed = false;
        if (scrubbing && previewValue != startValue) {
            value = previewValue;
            model.setValue(Scrub.format(value, step, suffix));
            onCommit.accept(value);
        }
        scrubbing = false;
    }

    // ------------------------------------------------------------------
    // Typing
    // ------------------------------------------------------------------

    @Override
    public boolean charTyped(net.minecraft.client.input.CharacterEvent event) {
        if (!isFocused() || !isActive()) {
            return false;
        }
        int codepoint = event.codepoint();
        if (codepoint < ' ' || codepoint > 127) {
            return false;
        }
        char typed = (char) codepoint;
        // Anything that cannot be part of a number is swallowed rather than inserted: the field is one
        // number, and letting letters in would only have `parse` strip them back out at commit time --
        // with the player watching nonsense appear in the meantime.
        if ("0123456789.-".indexOf(typed) < 0) {
            return true;
        }
        model.insert(typed);
        return true;
    }

    @Override
    public boolean keyPressed(net.minecraft.client.input.KeyEvent event) {
        if (!isFocused() || !isActive()) {
            return false;
        }
        // Same scancode note as the one-line field: `key()` is the scancode side, and every literal
        // is an `InputConstants` scancode for the same physical key as before.
        boolean control = event.hasControlDown();
        int key = event.key();

        if (key == com.mojang.blaze3d.platform.InputConstants.KEY_RETURN
                || key == com.mojang.blaze3d.platform.InputConstants.KEY_NUMPADENTER) {
            setFocused(false);
            return true;
        }
        if (key == com.mojang.blaze3d.platform.InputConstants.KEY_ESCAPE) {
            cancelTyped();
            return true;
        }
        if (key == com.mojang.blaze3d.platform.InputConstants.KEY_BACKSPACE) {
            model.backspace();
            return true;
        }
        if (key == com.mojang.blaze3d.platform.InputConstants.KEY_DELETE) {
            model.deleteForward();
            return true;
        }
        if (key == com.mojang.blaze3d.platform.InputConstants.KEY_LEFT) {
            model.left();
            return true;
        }
        if (key == com.mojang.blaze3d.platform.InputConstants.KEY_RIGHT) {
            model.right();
            return true;
        }
        if (key == com.mojang.blaze3d.platform.InputConstants.KEY_HOME) {
            model.home();
            return true;
        }
        if (key == com.mojang.blaze3d.platform.InputConstants.KEY_END) {
            model.end();
            return true;
        }
        if (key == com.mojang.blaze3d.platform.InputConstants.KEY_A) {
            if (control) {
                model.selectAll();
                return true;
            }
        }
        // Matched keys are swallowed whether or not they acted; only an unmatched key propagates.
        if (key == com.mojang.blaze3d.platform.InputConstants.KEY_RETURN
                || key == com.mojang.blaze3d.platform.InputConstants.KEY_NUMPADENTER
                || key == com.mojang.blaze3d.platform.InputConstants.KEY_ESCAPE
                || key == com.mojang.blaze3d.platform.InputConstants.KEY_BACKSPACE
                || key == com.mojang.blaze3d.platform.InputConstants.KEY_DELETE
                || key == com.mojang.blaze3d.platform.InputConstants.KEY_LEFT
                || key == com.mojang.blaze3d.platform.InputConstants.KEY_RIGHT
                || key == com.mojang.blaze3d.platform.InputConstants.KEY_HOME
                || key == com.mojang.blaze3d.platform.InputConstants.KEY_END
                || key == com.mojang.blaze3d.platform.InputConstants.KEY_A) {
            return true;
        }
        return false;
    }

    @Override
    public void setFocused(boolean focused) {
        if (focused && !isFocused()) {
            // The value the field is entered with, kept for Escape. Captured here rather than at the
            // click, because a click that never places the caret (a press that became a scrub) must not
            // overwrite what the last real edit started from.
            focusValue = value;
        }
        // Losing focus is when the typing is done with: a field the player clicks away from has been
        // finished with, and only keeping the text in the box would be keeping it to itself.
        if (!focused && isFocused() && !cancelling) {
            commitTyped();
        }
        super.setFocused(focused);
        // SDL needs explicit start/stop around text input; see the one-line field.
        Minecraft.getInstance().onTextInputFocusChange(this, focused);
    }

    /** Reads the typed text; a value that changed is written once, a value that did not is reformatted. */
    private void commitTyped() {
        Double parsed = Scrub.parse(model.value());
        if (parsed == null) {
            model.setValue(Scrub.format(value, step, suffix));
            return;
        }
        double next = Scrub.settle(parsed, min, max, step);
        model.setValue(Scrub.format(next, step, suffix));
        if (next != value) {
            value = next;
            onCommit.accept(next);
        }
    }

    /** Escape: put the value back to what it was when the field was entered, and leave without writing. */
    private void cancelTyped() {
        cancelling = true;
        try {
            value = focusValue;
            model.setValue(Scrub.format(value, step, suffix));
            setFocused(false);
        }
        finally {
            cancelling = false;
        }
    }

    // ------------------------------------------------------------------
    // Drawing
    // ------------------------------------------------------------------

    /** The value box: the right-hand part of the widget's bounds, where the label is not. */
    private BookGeometry.Rect box() {
        return BookGeometry.Rect.at(getX() + Math.max(0, getWidth() - BOX_WIDTH), getY(),
                Math.min(BOX_WIDTH, getWidth()), getHeight());
    }

    @Override
    protected void extractWidgetRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY,
            float partialTick) {
        render(new GuiGraphicsRenderer(graphics), mouseX, mouseY);
    }

    /** The same, through the seam, so the modal bands can draw it with their own pointer. */
    public void render(GuiRenderer r, int mouseX, int mouseY) {
        BookGeometry.Rect box = box();
        boolean hot = isActive() && isMouseOver(mouseX, mouseY);
        r.fill(box.x(), box.y(), box.right(), box.bottom(), ArmatureTheme.panelEdge());
        r.fill(box.x() + 1, box.y() + 1, box.right() - 1, box.bottom() - 1, ArmatureTheme.recessed());
        if (hot && !isFocused() && !scrubbing) {
            r.fill(box.x() + 1, box.y() + 1, box.right() - 1, box.bottom() - 1, ArmatureTheme.rowHover());
        }

        int line = box.y() + (box.height() - 8) / 2;
        int ink = isActive() ? ArmatureTheme.title() : ArmatureTheme.faint();
        try (GuiRenderer.Scoped clip = r.clip(box.x() + 1, box.y() + 1, box.right() - 1,
                box.bottom() - 1)) {
            if (scrubbing) {
                // A scrub shows its preview whatever the focus says: the field may still hold the
                // keyboard from before the press, and the model's text is then the old value's.
                String text = Scrub.format(previewValue, step, suffix);
                r.text(text, box.right() - PAD - r.textWidth(text), line, ink);
            }
            else if (isFocused()) {
                drawTyped(r, box, line, ink);
            }
            else {
                String text = Scrub.format(value, step, suffix);
                r.text(text, box.right() - PAD - r.textWidth(text), line, ink);
            }
        }
        if (scrubbing) {
            drawCue(r);
        }
    }

    /** The focused field: the typed text, its mark, and the caret -- the same picture a text field draws. */
    private void drawTyped(GuiRenderer r, BookGeometry.Rect box, int line, int ink) {
        Minecraft minecraft = Minecraft.getInstance();
        int room = box.width() - PAD * 2;
        int offset = minecraft.font == null ? 0 : model.scrollOffset(room, minecraft.font::width);
        int textX = box.x() + PAD - offset;
        if (model.hasSelection()) {
            int from = textX + r.textWidth(model.value().substring(0, model.selectionStart()));
            int to = textX + r.textWidth(model.value().substring(0, model.selectionEnd()));
            r.fill(from, line - 1, to, line + 9, Colour.alphaOf(ink, 0.35F));
        }
        r.text(model.value(), textX, line, ink);
        if (model.caretVisible(Util.getMillis()) && minecraft.font != null) {
            int caret = textX + r.textWidth(model.value().substring(0,
                    Math.min(model.caret(), model.length())));
            r.fill(caret, line - 1, caret + 1, line + 9, ink);
        }
    }

    /**
     * The scrubbing cue: a {@code ↔} badge beside the pointer.
     *
     * <p>Beside rather than instead of the cursor, because a screen cannot hide one -- nothing in either
     * repo touches the OS cursor -- and a glyph drawn under the pointer would be a glyph the pointer
     * covers. Offset down-right by half a character, it reads as "this gesture is horizontal", which is
     * what the arrow is for.
     */
    private void drawCue(GuiRenderer r) {
        int x = (int) pointerX + 6;
        int y = (int) pointerY + 6;
        r.fill(x - 1, y - 1, x + 10, y + 10, ArmatureTheme.panelEdge());
        r.fill(x, y, x + 9, y + 9, ArmatureTheme.raised());
        r.text("\u2194", x + 1, y + 1, ArmatureTheme.title());
    }

    @Override
    protected void updateWidgetNarration(NarrationElementOutput output) {
        defaultButtonNarrationText(output);
    }
}
