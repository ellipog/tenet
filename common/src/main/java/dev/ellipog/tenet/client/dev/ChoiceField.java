package dev.ellipog.tenet.client.dev;

import dev.ellipog.armature.client.ArmatureTheme;
import dev.ellipog.armature.client.render.GuiGraphicsRenderer;
import dev.ellipog.armature.client.render.GuiRenderer;
import dev.ellipog.tenet.client.BookGeometry;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.network.chat.Component;

/**
 * A value chosen from a short list: the current word and a triangle, and a press opens the menu.
 *
 * <h2>Why not a cycle</h2>
 *
 * <p>Because a control that cycles hides its options: pressing "Hatch" and watching it become "Dots" is
 * the only way to learn what the field could have been, and a wrong press costs a round trip through
 * every value to get back. A menu opened by the triangle shows the whole list at once, which is what the
 * design's {@code [ Hatch ▾ ]} is asking for. The triangle is {@code \u25BC} -- the font carries it; the
 * empty {@code \u25BE} it would have been is one of the glyphs this font does not have.
 *
 * <p>It holds no keyboard state: choosing happens in the menu, so this is a button in a field's box
 * rather than a field. The screen supplies {@link #onPress} and opens the chooser there, which is where
 * the options and their labels live.
 */
public final class ChoiceField extends AbstractWidget {

    /** The box's width, matching a scrub field's so a column of rows lines up. */
    public static final int BOX_WIDTH = ScrubField.BOX_WIDTH;

    private static final int PAD = 4;

    private String text;
    private Runnable onPress = () -> {
    };

    public ChoiceField(String text) {
        super(0, 0, 0, 0, Component.empty());
        this.text = text == null ? "" : text;
        this.active = true;
    }

    /** Sets the word shown, as the panel rebuilds. */
    public ChoiceField text(String next) {
        this.text = next == null ? "" : next;
        return this;
    }

    public ChoiceField onPress(Runnable handler) {
        this.onPress = handler == null ? () -> {
        } : handler;
        return this;
    }

    /** The box, which is the whole widget for this control: there is no label area of its own. */
    private BookGeometry.Rect box() {
        return BookGeometry.Rect.at(getX(), getY(), getWidth(), getHeight());
    }

    @Override
    public boolean mouseClicked(net.minecraft.client.input.MouseButtonEvent event, boolean doubleClick) {
        if (!isActive() || !visible || !isValidClickButton(event.buttonInfo())
                || !isMouseOver(event.x(), event.y())) {
            return false;
        }
        onPress.run();
        return true;
    }

    @Override
    protected void extractWidgetRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY,
            float partialTick) {
        render(new GuiGraphicsRenderer(graphics), mouseX, mouseY);
    }

    /** The same, through the seam. */
    public void render(GuiRenderer r, int mouseX, int mouseY) {
        BookGeometry.Rect box = box();
        boolean hot = isActive() && isMouseOver(mouseX, mouseY);
        r.fill(box.x(), box.y(), box.right(), box.bottom(), ArmatureTheme.panelEdge());
        r.fill(box.x() + 1, box.y() + 1, box.right() - 1, box.bottom() - 1, ArmatureTheme.recessed());
        if (hot) {
            r.fill(box.x() + 1, box.y() + 1, box.right() - 1, box.bottom() - 1, ArmatureTheme.rowHover());
        }
        int line = box.y() + (box.height() - 8) / 2;
        int ink = isActive() ? ArmatureTheme.title() : ArmatureTheme.faint();
        int triangle = r.textWidth("\u25BC");
        try (GuiRenderer.Scoped clip = r.clip(box.x() + 1, box.y() + 1, box.right() - 1,
                box.bottom() - 1)) {
            r.text(text, box.x() + PAD, line, ink);
            r.text("\u25BC", box.right() - PAD - triangle, line, ArmatureTheme.faint());
        }
    }

    @Override
    protected void updateWidgetNarration(NarrationElementOutput output) {
        defaultButtonNarrationText(output);
    }
}
