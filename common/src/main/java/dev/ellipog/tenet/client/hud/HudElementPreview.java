package dev.ellipog.tenet.client.hud;

import dev.ellipog.armature.client.ArmatureButton;
import dev.ellipog.armature.client.ArmatureTheme;
import dev.ellipog.armature.client.render.GuiRenderer;
import dev.ellipog.tenet.QuestBook;
import dev.ellipog.tenet.client.BookGeometry;

import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;

/**
 * One element in the editor: the thing you grab, and the thing that shows what it will look like.
 *
 * <h2>Why it is the real control rather than a picture of one</h2>
 *
 * <p>Because a picture is a second description of an appearance, which is exactly the fault the toolkit's
 * control table exists to prevent: the outside-the-game preview once drew a selected row hover-coloured
 * while the screen drew it flat, and the invented one is the one people looked at. This extends
 * {@link ArmatureButton}, so the preview <i>is</i> the control -- the same fills, the same edge, the same
 * icon box -- and the ring around it is the only thing added. Nothing here knows what a button looks like.
 *
 * <h2>Why the drag needs no help from the screen</h2>
 *
 * <p>Because this is a widget on <b>our own</b> screen. A container screen drops releases and drags -- the
 * reason the real button opens on press and why the last plan needed a clock to notice a release at all --
 * but {@code Screen} focuses the child that consumed a press, and {@code ContainerEventHandler} then hands
 * that child every drag and the release. So moving an element is the ordinary widget path, and the only
 * thing the screen does is decide where a resting element draws.
 *
 * <h2>Why the drag reads the pointer rather than the movement</h2>
 *
 * <p>Because the movement arrives rounded. The game calls {@code MouseHandler.handleAccumulatedMovement}
 * once per frame with the whole frame's mouse delta in GUI pixels -- a fraction -- and adding its rounded
 * value to the position loses that fraction every frame: the element trails the cursor by up to half a
 * pixel a frame, and at a GUI scale of two or more a slow drag rounds to nothing at all and the element
 * sits still while the cursor moves. So the press records where inside the element it took hold, and every
 * drag is that one subtraction against the pointer; see {@link HudLayout#dragged}. Nothing accumulates,
 * so nothing drifts.
 */
public final class HudElementPreview extends ArmatureButton {

    private final HudElement element;
    private final HudEditScreen screen;

    /** Whether this gesture has actually moved it, so a click that never moved is not written to the file. */
    private boolean moved;

    /**
     * Where inside the element the press took hold of it, as {@code pointer - position}.
     *
     * <p>Doubles, because the pointer is one: rounding the grab once at the press and then working in whole
     * pixels is the same drift in a smaller place. Taken in {@link #mouseClicked} and read by every drag
     * until the release, which is the whole of the state a drag needs.
     */
    private double grabX;
    private double grabY;

    HudElementPreview(HudElement element, HudEditScreen screen) {
        super(0, 0, element.width(), element.height(), Component.translatable(element.labelKey()),
                button -> screen.select(element));
        this.element = element;
        this.screen = screen;

        // The one place that knows what a control looks like before anything draws it, and it borrows the
        // real control's own icon rather than describing one. The inset is the element's, so the preview and
        // the button in the inventory fill their boxes the same way.
        //
        // Only a control: a drawn element has no widget on anybody's screen to borrow from, and its own
        // drawing is what `draw` delegates to below.
        if (element.kind() == HudElement.Kind.CONTROL) {
            icon(dev.ellipog.tenet.quest.ItemStacks.stackOf(QuestBook.ITEM)).iconInset(element.iconInset());
        }
    }

    /** The element this preview stands for. */
    public HudElement element() {
        return element;
    }

    /**
     * Takes the size the element is really drawn at.
     *
     * <p>What a drawn element needs and a control does not: the two are as big as what they hold, so the
     * screen asks {@code HudOverlay} every frame and hands the answer here. The base class's box is what the
     * drag clamps against and what the game will draw, so a preview at the table's starting size would be
     * grabbed in one place and drawn in another.
     */
    void resize(int width, int height) {
        setWidth(width);
        setHeight(height);
    }

    /**
     * The control, with a ring around it when it needs one -- or the element's own drawing, likewise.
     *
     * <p>Drawn here rather than by the screen because a widget cannot draw outside itself and the ring is
     * outside the box by a pixel or two -- and because the ring belongs to the thing it rings: a screen that
     * drew rings for its widget would have to know where every widget ended up, which it already does, twice.
     *
     * <p><b>A drawn element is drawn by the HUD's own switch</b>, through {@code HudOverlay.paint}, and that
     * is the whole point of the branch: a pin list in the editor and a pin list in the world are one
     * description, so the editor cannot show a panel the game does not draw. The other face of that switch,
     * {@code EDITOR}, is what supplies the stand-in sentence when there is nothing pinned -- a preview is also
     * the one place a player can see where a hidden thing is, so hiding it here too would make it
     * unreachable. An element that is switched off still draws here, at full brightness: a second, dimmer
     * look for one thing would be a second thing to learn, and the game draws nothing at all, which neither
     * look matches.
     *
     * <p>The ring draws only while highlighted -- selected, or held mid-drag -- and that is what makes the
     * resting editor pixel-identical to the live HUD. A backdrop behind every element was the whole of the
     * visible gap between the two: in the editor the stack floated on a rounded slab, in the game on the
     * world, and no comment survives a screenshot side by side.
     */
    @Override
    public void draw(GuiRenderer renderer, long nowMillis) {
        if (element.kind() == HudElement.Kind.HUD) {
            if (screen.isHighlighted(element)) {
                int ring = ArmatureTheme.selectedRing();
                ArmatureTheme.fillSurface(renderer, getX() - 2, getY() - 2, width + 4, height + 4, ring,
                        ArmatureTheme.current().cornerRadius() + 2, ArmatureTheme.CORNERS_ALL);
            }
            HudOverlay.paint(element, renderer,
                    BookGeometry.Rect.at(getX(), getY(), width, height),
                    screen.frameMeasure(), HudOverlay.Face.EDITOR, nowMillis, screen.playerId());
            return;
        }
        // An element that is switched off is still drawn here, and drawn dim: this is the one place a
        // player can see where a hidden thing is, so hiding it here too would make it unreachable.
        ink(HudSettings.on(element) || screen.isHighlighted(element) ? Ink.TITLE : Ink.BLOCKED);

        int ring = screen.isHighlighted(element) ? ArmatureTheme.selectedRing() : ArmatureTheme.panelEdge();
        ArmatureTheme.fillSurface(renderer, getX() - 2, getY() - 2, width + 4, height + 4, ring,
                ArmatureTheme.current().cornerRadius() + 2, ArmatureTheme.CORNERS_ALL);

        super.draw(renderer, nowMillis);
    }

    /**
     * The press that takes hold of the element, and where it took hold.
     *
     * <p>Recorded here rather than at the first drag because this is the only moment that knows: a drag
     * event says where the pointer is now and nothing about where it met the control.
     *
     * <p>Only when the base class took the press: an inactive or hidden control records nothing, and a
     * press outside the element is not this widget's at all.
     */
    @Override
    public boolean mouseClicked(net.minecraft.client.input.MouseButtonEvent event, boolean doubleClick) {
        boolean taken = super.mouseClicked(event, doubleClick);
        if (taken) {
            grabX = event.x() - getX();
            grabY = event.y() - getY();
        }
        return taken;
    }

    /**
     * Deliberately not {@code super}: a carried element stays lit while it is carried.
     *
     * <p>{@code ArmatureButton.onDrag} exists to cancel a press that has wandered off the control, so that
     * letting go elsewhere does nothing. A carried HUD element is the opposite case -- the pointer is off the
     * control because the control is following it -- so cancelling would make the thing flicker out from
     * under the cursor.
     *
     * <p>The movement this is handed -- {@code dragX} and {@code dragY}, vanilla's own parameter names --
     * is deliberately unused: adding it up is the drift {@link HudLayout#dragged} exists to remove, and the
     * parameters cannot be renamed away because the signature is the base class's.
     */
    @Override
    protected void onDrag(net.minecraft.client.input.MouseButtonEvent event, double dragX, double dragY) {
        moved = true;
        // Clamped against the window as it is now -- `screen.width`/`height` are read here, per drag event,
        // not remembered from when the widget was built -- and by the same rule the resting position uses, so
        // a drag cannot put the element anywhere a later frame would refuse to draw it. The grab survives a
        // clamp, so an element dragged past an edge comes back with the pointer rather than sticking to it.
        setX(HudLayout.dragged(event.x(), grabX, screen.width - width));
        setY(HudLayout.dragged(event.y(), grabY, screen.height - height));
    }

    @Override
    public boolean mouseReleased(net.minecraft.client.input.MouseButtonEvent event) {
        boolean acted = super.mouseReleased(event);
        if (moved) {
            moved = false;
            screen.drop(element);
        }
        return acted;
    }
}
