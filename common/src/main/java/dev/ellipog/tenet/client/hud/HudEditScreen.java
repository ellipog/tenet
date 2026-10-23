package dev.ellipog.tenet.client.hud;

import com.mojang.blaze3d.platform.InputConstants;

import dev.ellipog.armature.api.client.ArmatureClient;
import dev.ellipog.armature.client.ArmatureButton;
import dev.ellipog.armature.client.ArmatureSlider;
import dev.ellipog.armature.client.ArmatureSwitch;
import dev.ellipog.armature.client.ArmatureTheme;
import dev.ellipog.armature.client.render.GuiRenderer;
import dev.ellipog.armature.client.ui.ArmatureScreen;
import dev.ellipog.armature.client.ui.kit.Measure;
import dev.ellipog.tenet.Tenet;
import dev.ellipog.tenet.client.BookGeometry;

import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Util;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * The HUD editor: the list of movable things, and the things themselves, in one screen over the live world.
 *
 * <h2>Why it draws no background</h2>
 *
 * <p>Because the point is to move something you can see. A screen draws over the world <i>and</i> over the
 * HUD -- {@code GameRenderer} renders the HUD on every frame in a world, screens or not -- so every
 * {@code Screen} that fills its background hides exactly what this one is for. {@link ArmatureScreen}
 * already suppresses that (no blur, no panorama, no menu texture) for its own reasons, and this is the first
 * screen that needs the world behind it rather than merely tolerating it.
 *
 * <h2>Two ways in, one place things are stored</h2>
 *
 * <p>Opened by its own key, or from the book's settings card -- with the book waiting to come back, because
 * a player who went through the book to get here did not ask to be dropped into the world when they were
 * finished. Every change writes straight to {@link HudSettings}, there is no Apply, and the
 * inventory-screen button reads the same numbers, so the editor and the thing it edits cannot disagree
 * about where something is.
 *
 * <h2>Nothing stands in for anything</h2>
 *
 * <p>The elements are drawn where they really are, at their real size, in the window's own pixels -- the same
 * space the HUD is measured in and the same space a stored position means. An earlier version drew a ghost
 * of the inventory panel so that a button anchored to that panel's corner had a corner to be anchored to;
 * the ghost's corner was not the real panel's corner, so a number written from one was read from the other
 * and the control moved between the editor and the game. There is one coordinate space now, and no stand-in.
 *
 * <p>Except the one this screen draws when the button is inventory-anchored: an outline of the closed-book
 * survival panel, from the same centring the game uses. An offset needs a corner to be read against, and
 * the editor has no inventory open -- so the ghost is the corner, drawn from {@code InventoryPanel} rather
 * than invented beside it, which is what makes a drop on the outline land on the real panel. It is a guide
 * under the chrome and the previews, never a hit target, and it stands down whenever the button is back in
 * the window frame.
 *
 * <h2>Moving things: drag, and the arrows for the last pixel</h2>
 *
 * <p>Grab the element and drag it; the drop writes where the widget's corner is, clamped onto the window.
 * A press on an element selects it, and the arrows then nudge it a pixel -- ten with Shift -- through the
 * same clamp, for placing something exactly where a drag overshoots by one. There used to be a {@code Move}
 * button that carried the element on the pointer, and it went away because everything here is already
 * grabbable: a second way to move is a second thing to learn, and the arrows cover the precision case the
 * button was really for.
 *
 * <h2>What is drawn over what, and which of the two answers wins</h2>
 *
 * <p>The rows' panel and labels are the bottom layer, drawn by this screen; the elements are widgets on top
 * of them; and the rows' controls -- a switch, <i>Reset</i>, <i>Done</i> -- are widgets added
 * <b>before</b> the elements. Both halves of that are deliberate, and they are the opposite of each other,
 * because a press is offered to children in the order they were added while drawing goes the other way:
 *
 * <ul>
 *   <li>the controls are asked first, so an element left lying across one cannot make it unreachable. An
 *       unreachable control is the one arrangement that traps a player in this screen;</li>
 *   <li>and so an element left lying across a control is drawn <i>over</i> it. It looks covered and still
 *       answers, which is the better of the two faults -- dragging it by any visible part moves it, and
 *       putting it down anywhere the rows are not is a release away.</li>
 * </ul>
 */
public final class HudEditScreen extends ArmatureScreen {

    /** Whether the screen that opened this one wants to be reopened when it closes. */
    private static boolean pendingReturn = false;

    private final Map<HudElement, HudElementPreview> previews = new EnumMap<>(HudElement.class);
    private final Map<HudElement, ArmatureSwitch> switches = new EnumMap<>(HudElement.class);
    private final Map<HudElement, ArmatureButton> resets = new EnumMap<>(HudElement.class);
    private final Map<HudElement, ArmatureSlider> dims = new EnumMap<>(HudElement.class);
    private final Map<HudElement, ArmatureButton> anchors = new EnumMap<>(HudElement.class);
    private final Map<HudElement, ArmatureButton> unders = new EnumMap<>(HudElement.class);
    private ArmatureButton done;

    /**
     * Whether finished and collected quests leave the stack on their own.
     *
     * <p>Its own control rather than one of the element rows': hiding is what the pins do, not where
     * the element sits, and the element rows are about placement. Built with the rest and placed at the
     * hide row, after the elements and before the foot.
     */
    private ArmatureSwitch hideSwitch;

    /** The element last pressed, so the thing touched and the row that names it are visibly the same one. */
    private HudElement selected;

    private final boolean returnToBook;

    public HudEditScreen() {
        super(Component.translatable("tenet.screen.hud_edit.title"));
        // Taken here rather than read later: the flag belongs to one visit to this screen, so a screen
        // somebody else opened in between does not send the next visitor back to the book.
        this.returnToBook = pendingReturn;
        pendingReturn = false;
    }

    /** Opens the editor, and reopens the book when it closes. */
    public static void openFromBook() {
        pendingReturn = true;
        ArmatureClient.openScreen(Tenet.HUD_EDIT_SCREEN);
    }

    /**
     * The controls, built once.
     *
     * <p><b>The rows are added before the elements</b>, and the order is a decision rather than a habit: a
     * press is offered to children in the order they were added, so the rows' controls keep their presses
     * even with an element lying across them. See the class note for what that costs in drawing.
     */
    @Override
    protected void init() {
        for (HudElement element : HudElement.values()) {
            ArmatureSwitch toggle = new ArmatureSwitch(0, 0, HudSettings.on(element));
            toggle.onToggle(() -> HudSettings.setOn(element, toggle.selected()));
            addRenderableWidget(toggle);
            switches.put(element, toggle);

            ArmatureButton reset = new ArmatureButton(0, 0, HudLayout.BUTTON_WIDTH, HudLayout.CONTROL_LINE,
                    Component.translatable("tenet.hud.reset"), () -> resetElement(element));
            addRenderableWidget(reset);
            resets.put(element, reset);

            // A drawn element's background strength, on its own line under its controls: a slider shares
            // nothing with the switch beside it the way Move and Reset share a line, and only a drawn
            // element has a background to strengthen. A control draws itself, so it gets no slider.
            if (element.kind() == HudElement.Kind.HUD) {
                ArmatureSlider dim = new ArmatureSlider(0, 0, HudLayout.CHROME_WIDTH, 0.0, 1.0, 0.05,
                        HudSettings.dim(element));
                dim.onChange(() -> HudSettings.setDim(element, dim.value()));
                addRenderableWidget(dim);
                dims.put(element, dim);
            }

            // The book's button rows its anchor on a second line of its own: what the button is measured
            // from -- the window, or the inventory panel -- plus the Under preset that puts it centred
            // under the panel in one press. Built for the button only, like the slider is built for a
            // drawn element only; the HUD's own two have no panel to be measured from.
            if (element == HudElement.INVENTORY_BUTTON) {
                ArmatureButton anchor = new ArmatureButton(0, 0, HudLayout.ANCHOR_WIDTH,
                        HudLayout.CONTROL_LINE, anchorLabel(element), () -> toggleAnchor(element));
                anchor.tooltip(Component.translatable("tenet.hud.anchor_hint"));
                addRenderableWidget(anchor);
                anchors.put(element, anchor);
                ArmatureButton under = new ArmatureButton(0, 0, HudLayout.BUTTON_WIDTH,
                        HudLayout.CONTROL_LINE, Component.translatable("tenet.hud.anchor_under"),
                        () -> placeUnder(element));
                under.tooltip(Component.translatable("tenet.hud.anchor_under_hint"));
                addRenderableWidget(under);
                unders.put(element, under);
            }
        }

        done = new ArmatureButton(0, 0, HudLayout.BUTTON_WIDTH, HudLayout.CONTROL_LINE,
                Component.translatable("tenet.screen.hud_edit.done"), this::onClose);
        addRenderableWidget(done);

        hideSwitch = new ArmatureSwitch(0, 0, PinnedQuests.hideClaimed());
        hideSwitch.onToggle(() -> PinnedQuests.setHideClaimed(hideSwitch.selected()));
        addRenderableWidget(hideSwitch);

        for (HudElement element : HudElement.values()) {
            HudElementPreview preview = new HudElementPreview(element, this);
            previews.put(element, preview);
            addRenderableWidget(preview);
        }
    }

    @Override
    protected void renderContent(GuiRenderer renderer, int mouseX, int mouseY, float partialTick) {
        // Kept for the widget pass, which runs after this one: a preview of a *drawn* element draws itself
        // through `HudOverlay`, and that needs the frame's measure. Read from the renderer in force rather
        // than remembered, for the same reason every position here is asked for rather than stored.
        frameMeasure = Measure.of(renderer::textWidth, renderer.lineHeight());
        long now = Util.getMillis();

        drawGhost(renderer);
        for (HudElement element : HudElement.values()) {
            follow(element, mouseX, mouseY, frameMeasure, now);
        }
        drawChrome(renderer, chrome(), frameMeasure);
    }

    /** The measure this frame is drawing with; see {@link #renderContent}. */
    Measure frameMeasure() {
        return frameMeasure;
    }

    /**
     * The player arranging things, or null before the client has one.
     *
     * <p>Whose claimed quests hide: the editor arranges the boxes the world draws, so it hides what the
     * world hides rather than arranging boxes that are gone.
     */
    java.util.UUID playerId() {
        return minecraft == null || minecraft.player == null ? null : minecraft.player.getUUID();
    }

    private Measure frameMeasure;

    /**
     * The inventory panel's ghost: the closed-book survival panel, drawn from the same centring the
     * game uses.
     *
     * <p>Only while the button is inventory-anchored: in the window frame there is no panel to show,
     * and an outline that is always there would be chrome claiming to be content. Drawn under the
     * chrome and the previews, so it never covers anything -- a guide is looked <i>at</i> while placing
     * and looked <i>past</i> the rest of the time.
     *
     * <p>This is the ghost the first panel anchor could not have: that one centred a panel the editor
     * invented while the game centred its own, so one stored number read two corners. This one draws
     * {@link InventoryPanel#rect} with the book closed, which is the same call the game makes -- so
     * dropping the button on this outline and opening the inventory lands it on the real panel. With
     * the recipe book open the live panel sits left of this outline and the button follows it there;
     * that delta is the anchor working, not the editor misreading.
     */
    private void drawGhost(GuiRenderer renderer) {
        if (HudSettings.origin(HudElement.INVENTORY_BUTTON) != HudElement.Origin.INVENTORY) {
            return;
        }
        BookGeometry.Rect ghost = ghostPanel();
        ArmatureTheme.outline(renderer, ghost.x(), ghost.y(), ghost.width(), ghost.height(),
                ArmatureTheme.panelEdge());
    }

    /**
     * The corner every inventory-anchored number in this screen is measured from.
     *
     * <p>The closed-book survival panel at this window's size: the same centring the game uses, so a
     * drop here reads the same corner an inventory open reads. Creative's panel is wider and shorter
     * and an open recipe book pushes survival's left -- both documented deltas the live placement
     * follows and this fixed guide cannot.
     */
    private BookGeometry.Rect ghostPanel() {
        return InventoryPanel.rect(width, height, true, false);
    }

    /**
     * Where each element draws: where it is stored.
     *
     * <p>Every frame, against the window as it is <i>now</i> -- which is what makes this work at any size.
     * The dimensions are asked for rather than remembered, so a window that has just been resized has its
     * elements pulled inside it on the next frame instead of leaving one off the edge until something else
     * happens to move it.
     *
     * <p><b>The size is refreshed here too, and only for a drawn element.</b> A later round made two of the
     * four as big as what they hold, so a preview left at the size its table entry ships would be a box the
     * game never draws -- grabbed in one place and drawn in another, which is the class of fault this
     * editor was rebuilt to remove. {@code HudOverlay} is the only thing that measures them, so it is asked,
     * with the editor's own face: an element with nothing pinned still gets the sample's box, which is what
     * keeps it reachable. A control's size is a constant and is left as it is.
     *
     * <p>Skipped for an element the widget pass is currently carrying: that one is where the pointer put it,
     * and putting it back to the stored position every frame would fight the drag that has not finished.
     *
     * <p>An inventory-anchored button is re-based on the ghost's corner, the way the game re-bases it on
     * the live panel's: the stored number is an offset, so drawing it at the offset alone would park it
     * in the window's corner while the file means the panel's.
     */
    private void follow(HudElement element, int mouseX, int mouseY, Measure measure, long now) {
        HudElementPreview preview = previews.get(element);
        if (preview == null || preview.isHeld()) {
            return;
        }
        if (element.kind() == HudElement.Kind.HUD) {
            // The editor's own player, so the boxes it arranges are the boxes the world draws: a claimed
            // quest hidden out there is hidden here too, rather than arranged and then gone.
            HudOverlay.Size size = HudOverlay.size(element, measure, HudOverlay.Face.EDITOR, now, playerId());
            preview.resize(size.width(), size.height());
        }
        BookGeometry.Rect box;
        if (element == HudElement.INVENTORY_BUTTON
                && HudSettings.origin(element) == HudElement.Origin.INVENTORY) {
            BookGeometry.Rect ghost = ghostPanel();
            box = HudLayout.boxAtInventory(ghost.x(), ghost.y(),
                    HudSettings.x(element), HudSettings.y(element),
                    preview.getWidth(), preview.getHeight(), width, height);
        }
        else {
            box = HudLayout.boxAt(element, width, height,
                    HudSettings.x(element), HudSettings.y(element),
                    preview.getWidth(), preview.getHeight());
        }
        preview.at(box.x(), box.y());
    }

    private void drawChrome(GuiRenderer renderer, BookGeometry.Rect chrome, Measure measure) {
        ArmatureTheme.panel(renderer, chrome.x(), chrome.y(), chrome.width(), chrome.height(),
                ArmatureTheme.raised(), ArmatureTheme.panelEdge());

        // The title heads the panel, over a rule, the way every book panel's does: it used to sit in the
        // foot beside Done, where the panel only said what it was after its rows had.
        BookGeometry.Rect title = HudLayout.title(chrome);
        renderer.text(Measure.truncate(getTitle().getString(), title.width(), measure),
                title.x(), title.y() + 1, ArmatureTheme.title());
        int rule = chrome.y() + HudLayout.INSET + HudLayout.HEADER_HEIGHT - 4;
        renderer.fill(chrome.x() + 1, rule, chrome.right() - 1, rule + 1, ArmatureTheme.panelEdge());

        int index = 0;
        List<HudElement> order = List.of(HudElement.values());
        for (HudElement element : order) {
            BookGeometry.Rect label = HudLayout.label(index, chrome, order);
            // The element that was last pressed reads bright, so a row and the thing it names are visibly
            // the same thing -- the rows are words and the elements are icons, and that is the join.
            int ink = element == selected ? ArmatureTheme.title() : ArmatureTheme.body();
            renderer.text(Measure.truncate(Component.translatable(element.labelKey()).getString(),
                            label.width(), measure),
                    label.x(), label.y() + 1, ink);

            place(switches.get(element), HudLayout.toggle(index, chrome, order));
            place(resets.get(element), HudLayout.reset(index, chrome, order));
            ArmatureSlider dim = dims.get(element);
            if (dim != null) {
                placeSlider(dim, HudLayout.slider(index, chrome, order));
                drawCaption(renderer, "tenet.hud.background", HudLayout.caption(index, chrome, order), measure);
            }
            if (anchors.containsKey(element)) {
                drawCaption(renderer, "tenet.hud.anchor", HudLayout.caption(index, chrome, order), measure);
            }
            place(anchors.get(element), HudLayout.anchorToggle(index, chrome, order));
            place(unders.get(element), HudLayout.anchorUnder(index, chrome, order));
            index++;
        }

        // The auto-hide, after the elements and before the foot: finished and collected quests leave the
        // stack on their own unless the player said otherwise, and this screen is where hiding shows.
        BookGeometry.Rect hideLabel = HudLayout.hideLabel(chrome, order);
        renderer.text(Measure.truncate(Component.translatable("tenet.screen.pinned.hide_claimed").getString(),
                        hideLabel.width(), measure),
                hideLabel.x(), hideLabel.y() + 1, ArmatureTheme.body());
        place(hideSwitch, HudLayout.hideToggle(chrome, order));

        // The foot says how the rest of the screen works: the elements out on the window are the other
        // half of this editor, and nothing else on the panel says they can be picked up.
        BookGeometry.Rect foot = HudLayout.done(chrome);
        place(done, foot);
        renderer.text(Measure.truncate(Component.translatable("tenet.screen.hud_edit.hint").getString(),
                        Math.max(0, foot.x() - chrome.x() - HudLayout.INSET * 2), measure),
                chrome.x() + HudLayout.INSET, foot.y() + 5, ArmatureTheme.faint());
    }

    /** A second line's caption, faint: it names the control beside it rather than an element. */
    private static void drawCaption(GuiRenderer renderer, String key, BookGeometry.Rect caption, Measure measure) {
        renderer.text(Measure.truncate(Component.translatable(key).getString(), caption.width(), measure),
                caption.x(), caption.y() + 1, ArmatureTheme.faint());
    }

    private static void place(AbstractWidget widget, BookGeometry.Rect box) {
        if (widget != null) {
            widget.setX(box.x());
            widget.setY(box.y());
        }
    }

    /**
     * Places a background slider, with its width as well as its corner.
     *
     * <p>The one control on this screen sized per frame rather than built to size: its width <i>is</i> the
     * row's content, so a slider built to the chrome's full width would overhang the panel on a narrow
     * window, and one built to the minimum would leave dead track on a wide one. Buttons keep fixed sizes
     * because a fixed size is what they are; a slider's width is what it is for.
     */
    private static void placeSlider(ArmatureSlider slider, BookGeometry.Rect box) {
        if (slider != null) {
            slider.setX(box.x());
            slider.setY(box.y());
            slider.setWidth(Math.max(ArmatureSlider.MIN_TRACK, box.width()));
        }
    }

    // ------------------------------------------------------------------
    // Input
    // ------------------------------------------------------------------

    // There is no `mouseClicked` override here, and that is the whole point of dragging an element
    // directly: the widget path carries press, drag and release, so there is no second gesture to keep in
    // step with the first.

    /**
     * Arrows nudge the selected element a pixel -- ten with Shift -- through the same clamp-and-convert
     * as a drop, and write straight through it.
     *
     * <p>Not into a focused slider: the slider spends arrows on its own value, and stealing them would make
     * a strength unreachable from the keyboard that reaches everything else.
     */
    @Override
    public boolean keyPressed(net.minecraft.client.input.KeyEvent event) {
        int keyCode = event.key();
        if (selected != null && !(getFocused() instanceof ArmatureSlider)
                && (keyCode == InputConstants.KEY_UP || keyCode == InputConstants.KEY_DOWN
                        || keyCode == InputConstants.KEY_LEFT || keyCode == InputConstants.KEY_RIGHT)) {
            nudgeSelected(keyCode == InputConstants.KEY_LEFT ? -1 : keyCode == InputConstants.KEY_RIGHT ? 1 : 0,
                    keyCode == InputConstants.KEY_UP ? -1 : keyCode == InputConstants.KEY_DOWN ? 1 : 0,
                    event.hasShiftDown() ? 10 : 1);
            return true;
        }
        return super.keyPressed(event);
    }

    /** Moves the selected element by exact pixels, and writes where it landed. */
    private void nudgeSelected(int dx, int dy, int step) {
        HudElementPreview preview = previews.get(selected);
        if (preview == null) {
            return;
        }
        if (selected == HudElement.INVENTORY_BUTTON
                && HudSettings.origin(selected) == HudElement.Origin.INVENTORY) {
            // An offset, not a window pixel: nudged exactly, with no clamp from the wrong space. The
            // window clamp at draw time decides what an offset past the panel means on a small window.
            HudSettings.setPosition(selected, HudSettings.x(selected) + dx * step,
                    HudSettings.y(selected) + dy * step);
            return;
        }
        int left = HudLayout.placed(HudSettings.x(selected) + dx * step, width - preview.getWidth());
        int top = HudLayout.placed(HudLayout.placedTop(selected, height, HudSettings.y(selected),
                preview.getHeight()) + dy * step, height - preview.getHeight());
        HudSettings.setPosition(selected, left, HudLayout.storedY(selected, height, top, preview.getHeight()));
    }

    /**
     * Replaces this screen, which is how it closes.
     *
     * <p>Not {@code super.onClose()} on the way back to the book: setting a screen <i>is</i> closing this
     * one -- the game calls {@code removed()} on the way out -- and doing both would close a screen twice.
     */
    @Override
    public void onClose() {
        if (returnToBook) {
            ArmatureClient.openScreen(Tenet.QUEST_BOOK_SCREEN);
            return;
        }
        super.onClose();
    }

    // ------------------------------------------------------------------
    // What the rows and the elements do
    // ------------------------------------------------------------------

    private void resetElement(HudElement element) {
        HudSettings.resetElement(element);
        ArmatureSwitch toggle = switches.get(element);
        if (toggle != null) {
            // Reset puts the switch back too: an element switched off and then reset is an element the
            // player asked to have back.
            toggle.setSelected(HudSettings.on(element));
        }
        ArmatureSlider dim = dims.get(element);
        if (dim != null) {
            // And the slider, for the same reason: a dim left where it was after everything else went
            // home would be a reset that did not.
            dim.setValue(HudSettings.dim(element));
        }
        // And the anchor, for the same reason again: the shipped layout is window pixels, so a reset
        // that left the inventory origin behind would not be home.
        refreshAnchorButton(element);
    }

    /**
     * The anchor switch's label: what the button is measured from right now.
     *
     * <p>The switch <i>is</i> its label -- Window or Inventory -- rather than a bare toggle beside a
     * name, because the row's label line already names the element and a second switch with no words
     * of its own would read as a second show/hide.
     */
    private static Component anchorLabel(HudElement element) {
        return Component.translatable(HudSettings.origin(element) == HudElement.Origin.INVENTORY
                ? "tenet.hud.anchor_inventory" : "tenet.hud.anchor_window");
    }

    /** Re-reads the anchor switch after anything that changed what it names. */
    private void refreshAnchorButton(HudElement element) {
        ArmatureButton anchor = anchors.get(element);
        if (anchor != null) {
            anchor.setMessage(anchorLabel(element));
        }
    }

    /**
     * Flips what the button is measured from, without moving it on screen.
     *
     * <p>Converted through the ghost's corner at this window's size, so the preview -- and, with the
     * book closed, the game -- draws the button where it already is: switching frames rewrites the
     * numbers, not the place. Both writes land because they are one decision in two fields: the origin
     * the position is read through, and the position in it.
     */
    private void toggleAnchor(HudElement element) {
        BookGeometry.Rect ghost = ghostPanel();
        if (HudSettings.origin(element) == HudElement.Origin.INVENTORY) {
            int windowX = HudLayout.windowX(HudSettings.x(element), ghost.x());
            int windowY = HudLayout.windowY(HudSettings.y(element), ghost.y());
            HudSettings.setOrigin(element, HudElement.Origin.WINDOW);
            HudSettings.setPosition(element, windowX, windowY);
        }
        else {
            int inventoryX = HudLayout.inventoryX(HudSettings.x(element), ghost.x());
            int inventoryY = HudLayout.inventoryY(HudSettings.y(element), ghost.y());
            HudSettings.setOrigin(element, HudElement.Origin.INVENTORY);
            HudSettings.setPosition(element, inventoryX, inventoryY);
        }
        refreshAnchorButton(element);
    }

    /**
     * Puts the button centred under the inventory panel in one press.
     *
     * <p>Anchors to the inventory first when it is not there already: a preset that left the window
     * frame behind would write a panel offset the game reads as a window corner, which is the jump
     * every conversion here exists to prevent. Measured against the ghost, so the editor and the
     * closed-book game agree; the preview follows on the next frame.
     */
    private void placeUnder(HudElement element) {
        HudElementPreview preview = previews.get(element);
        BookGeometry.Rect ghost = ghostPanel();
        int[] offset = InventoryPanel.underOffset(ghost.width(), ghost.height(),
                preview == null ? element.width() : preview.getWidth());
        HudSettings.setOrigin(element, HudElement.Origin.INVENTORY);
        HudSettings.setPosition(element, offset[0], offset[1]);
        refreshAnchorButton(element);
    }

    /**
     * Writes where an element has been put.
     *
     * <p>The widget's corner, clamped onto the window as it is now -- so a drop near an edge stores where
     * the element visibly is rather than where the cursor went, and a window that changed size since the
     * drag began cannot have a position written that this frame would refuse to draw. Converted to what
     * the file holds exactly once, through the element's anchor: routing the corner through
     * {@code boxAt} instead would read it as a centre-offset a second time, and the box would jump by
     * half its height on release -- which is the fault this shape exists to prevent.
     *
     * <p>An inventory-anchored button stores the corner less the ghost's corner instead, unclamped: the
     * offset is panel-relative, so the window clamp would be a bound from the wrong space, and routing
     * it through {@code boxAtInventory} would re-add the corner a second time.
     */
    void drop(HudElement element) {
        HudElementPreview preview = previews.get(element);
        if (preview == null) {
            return;
        }
        if (element == HudElement.INVENTORY_BUTTON
                && HudSettings.origin(element) == HudElement.Origin.INVENTORY) {
            BookGeometry.Rect ghost = ghostPanel();
            HudSettings.setPosition(element,
                    HudLayout.inventoryX(preview.getX(), ghost.x()),
                    HudLayout.inventoryY(preview.getY(), ghost.y()));
            return;
        }
        int left = HudLayout.placed(preview.getX(), width - preview.getWidth());
        int top = HudLayout.placed(preview.getY(), height - preview.getHeight());
        HudSettings.setPosition(element, left,
                HudLayout.storedY(element, height, top, preview.getHeight()));
    }

    /** A press on an element itself: it becomes the selected one, for the ring and the arrows. */
    void select(HudElement element) {
        selected = element;
    }

    /** Whether this element is the one last touched or being dragged, for the ring it draws. */
    boolean isHighlighted(HudElement element) {
        HudElementPreview preview = previews.get(element);
        return element == selected || (preview != null && preview.isHeld());
    }

    // ------------------------------------------------------------------

    private BookGeometry.Rect chrome() {
        return HudLayout.chrome(width, height, List.of(HudElement.values()));
    }
}
