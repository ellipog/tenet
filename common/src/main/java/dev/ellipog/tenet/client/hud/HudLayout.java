package dev.ellipog.tenet.client.hud;

import dev.ellipog.armature.client.ArmatureSlider;
import dev.ellipog.tenet.client.BookGeometry;

import java.util.List;

/**
 * Where the HUD editor's parts go: the rows panel, and every element's box.
 *
 * <h2>Why this is a class and not arithmetic in the screen</h2>
 *
 * <p>Because a screen cannot be asked a question. {@code HudEditScreen} needs a live client to exist, so
 * "a dragged element is always somewhere on the window" and "the rows do not overlap each other" are facts
 * nothing could check if they lived in its methods -- and they are exactly the facts that go wrong silently,
 * as a control drawn one row too low or an element left half off the window. Integers and rectangles here
 * are asserted over a sweep of window sizes by {@code HudLayoutTest}, and the screen <b>builds itself from
 * them</b>: every rectangle drawn, hit-tested and dragged comes from one of these methods, so the test and
 * the screen cannot be describing two different layouts. That is the rule the book's own geometry was
 * extracted for.
 *
 * <h2>One coordinate space</h2>
 *
 * <p>An element's position is its position in the window, which is where the HUD is measured from too. The
 * rows panel is chrome the game never sees, so it is kept out of the way -- anchored to the bottom-left,
 * with its own height following how many elements there are -- and nothing converts between the two: a
 * position the editor writes is a position the game draws at, at any window size, which is the property
 * that was missing when a stored offset was measured from a ghost panel the editor had invented.
 */
public final class HudLayout {

    /** The margin between the chrome and the window's edges. */
    public static final int INSET = 4;

    /** The rows panel: wide enough for a name, its switch and Reset on one line. */
    public static final int CHROME_WIDTH = 190;

    /** A name's text line, centred on the control line it shares. */
    public static final int LABEL_LINE = 10;

    /** The line holding a row's name, its switch and its Reset. */
    public static final int CONTROL_LINE = 18;

    /** The gap between one element's row and the next. */
    public static final int ROW_GAP = 4;

    /**
     * One element's whole row: its name, switch and Reset on one line.
     *
     * <p>The name used to have a line of its own above the controls, which read as a word and, under it,
     * a switch belonging to nobody. Side by side, the switch is plainly that row's.
     */
    public static final int ROW_HEIGHT = CONTROL_LINE + ROW_GAP;

    /** The title band at the top of the chrome: the panel says what it is before its rows do. */
    public static final int HEADER_HEIGHT = 16;

    /**
     * The caption column of a row's second line -- "Background" beside the slider, "Anchor" beside the
     * anchor buttons -- so neither control is an unlabelled thing under a row.
     */
    public static final int CAPTION_WIDTH = 62;

    /** The strip at the foot of the chrome, holding Done. */
    public static final int FOOT_HEIGHT = 24;

    /** The switch's own size, from {@code ArmatureSwitch}: a compile-time constant, so this stays game-free. */
    public static final int SWITCH_WIDTH = 22;

    /** The same, vertically. */
    public static final int SWITCH_HEIGHT = 12;

    /** Move and Reset, side by side at the right of a row's control line. */
    public static final int BUTTON_WIDTH = 40;

    /** The gap between them. */
    public static final int BUTTON_GAP = 4;

    /**
     * The anchor switch of the inventory button's row: Window or Inventory.
     *
     * <p>Wide enough for "Inventory" at nine characters without truncating: a switch that reads as
     * "Inven..." would be a control that does not say what it does. "Window" at six leaves the
     * remainder empty, which is the better of the two faults.
     */
    public static final int ANCHOR_WIDTH = 64;

    /**
     * A drawn element's background-strength slider, on its own line under its controls.
     *
     * <p>From {@code ArmatureSlider}: a compile-time constant, so this stays game-free like the switch's
     * own. Only drawn elements get the line -- a control draws itself, and a slider for a button's
     * background would be a control for something nothing reads.
     */
    public static final int SLIDER_LINE = ArmatureSlider.HEIGHT;

    /**
     * The narrowest the rows can be and still hold their controls.
     *
     * <p>The anchor switch, Under, the gap between them and the panel's own margins: the widest line. Below this the editor
     * cannot be drawn honestly -- a control the player cannot reach is worse than a panel that overlaps
     * something -- so the panel stops shrinking here and overflows instead, which is what a window this small
     * gets. Declared after the parts it is made of, because a constant cannot refer forward.
     */
    public static final int MIN_CHROME_WIDTH =
            INSET + ANCHOR_WIDTH + BUTTON_GAP + BUTTON_WIDTH + INSET;

    private HudLayout() {
    }

    /**
     * The rows panel, in the bottom-left corner. Its height follows which elements there are, and its
     * width gives way to a narrow window before its controls do.
     *
     * <p>The label line shrinks first -- it is a name, and a truncated name is still a name -- which is what
     * keeps <i>Done</i> and the two buttons on the window at sizes well below the panel's natural width.
     * Drawn elements cost an extra slider line each; see {@link #rowHeight}.
     */
    public static BookGeometry.Rect chrome(int screenWidth, int screenHeight,
                                           List<HudElement> elements) {
        int height = chromeHeight(elements);
        int width = Math.max(MIN_CHROME_WIDTH, Math.min(CHROME_WIDTH, screenWidth - INSET * 2));
        return BookGeometry.Rect.at(INSET, Math.max(0, screenHeight - INSET - height), width, height);
    }

    /** The whole height the chrome needs for these rows, footer included. */
    public static int chromeHeight(List<HudElement> elements) {
        // Plus the auto-hide row: finished and collected quests leave the stack on their own unless the
        // player said otherwise, and the switch for that lives here rather than in the book because this
        // is the screen that shows what hiding does. Fixed furniture like the foot, so every chrome has
        // room for it the way every chrome has room for Done.
        int height = INSET * 2 + HEADER_HEIGHT + FOOT_HEIGHT + ROW_HEIGHT;
        for (HudElement element : elements) {
            height += rowHeight(element);
        }
        return height;
    }

    /**
     * One element's whole row: the label, the controls, and -- for a drawn element -- the background slider.
     *
     * <p>Per kind rather than one constant, because only a drawn element has a background to strengthen: a
     * slider line on a control's row would be a control for something nothing reads. The slider needs its
     * own line the way the switch and the buttons share theirs -- a slider squeezed beside them would be a
     * 60-pixel track, which is its own minimum and therefore no room to aim in.
     *
     * <p>The book's button carries a second control line for the same reason: the anchor switch and the
     * Under preset share nothing with the show/hide switch and Reset above them, and squeezed beside them
     * the anchor's own label would truncate. Like the slider line, only this element gets one.
     */
    public static int rowHeight(HudElement element) {
        if (element.kind() == HudElement.Kind.HUD) {
            return ROW_HEIGHT + SLIDER_LINE + ROW_GAP;
        }
        if (element == HudElement.INVENTORY_BUTTON) {
            return ROW_HEIGHT + CONTROL_LINE + ROW_GAP;
        }
        return ROW_HEIGHT;
    }

    /**
     * One coordinate, held inside the window.
     *
     * <p>The single place a position is made legal, used by the editor and by the real control on the
     * inventory screen alike -- so the two can never disagree about where an element ends up, which is the
     * fault the book's own two colliding buttons were. It reads the window size it is given rather than
     * anything remembered, so a resize is answered on the next frame.
     */
    public static int placed(int wanted, int limit) {
        return Math.max(0, Math.min(wanted, limit));
    }

    /**
     * Where a drag puts one coordinate: <b>the pointer, less the offset the press took hold of it by.</b>
     *
     * <h2>Why this is not the pointer's movement added to where the element was</h2>
     *
     * <p>Because that is a sum of rounded numbers and this is one. {@code dragX} and {@code dragY} reach a
     * widget from {@code MouseHandler.handleAccumulatedMovement}, which the game calls <b>once per
     * frame</b>, as the whole frame's mouse movement scaled into GUI pixels -- a fraction. Adding
     * {@code Math.round(dragX)} to the position discards that fraction every frame, so the element falls
     * behind by up to half a pixel a frame, all drag long; and at a GUI scale of two or more a slow drag
     * rounds to <b>zero</b> every frame, which is an element that does not move while the cursor does. It
     * was reported as exactly that: "it follows but drifts, moves the wrong distance".
     *
     * <p>Reading the pointer's own position cannot drift, because nothing accumulates: the grab offset is
     * taken once, at the press, and every later frame is the same subtraction. The rounding that remains
     * is the one an integer position has to do somewhere, and it does not compound.
     *
     * <p>The same shape the book's canvas drag uses -- a grab recorded at the press, the pointer read per
     * event -- and the same clamp, so a drag cannot put an element anywhere a later frame would refuse to
     * draw it.
     *
     * @param pointer the pointer, in the window's own pixels
     * @param grab    where inside the element the press took hold of it, from {@code pointer - position}
     * @param limit   the largest coordinate the element may take, which is the window less its own size
     */
    public static int dragged(double pointer, double grab, int limit) {
        return placed((int) Math.round(pointer - grab), limit);
    }

    /**
     * An element's box: where it is, pulled onto the window.
     *
     * <p>What a drop asks -- the pointer put the box <i>here</i>, so what is written is where the element
     * visibly is rather than where the cursor went.
     *
     * <p>Takes the size separately rather than from the element, because two of the four are as big as what
     * they hold: a pin list and a notice stack are measured every frame, and a box clamped against the
     * table's starting size would leave a drawn element hanging off the window the moment its content grew.
     * Reads the element's {@code Anchor} for the vertical axis, so render, preview and drop below all place
     * one element one way -- see {@link #placedTop}.
     */
    public static BookGeometry.Rect boxAt(HudElement element, int screenWidth, int screenHeight, int x, int y,
                                          int width, int height) {
        return boxAt(x, placedTop(element, screenHeight, y, height), width, height, screenWidth,
                screenHeight);
    }

    /**
     * The same, for a size the caller measured: one place either way.
     *
     * <p>The clamp is {@link #placed}'s, and it is deliberately the whole of the rule -- including when the
     * content is wider than the window, where the answer is a box at zero that overflows rather than a box
     * cut in half that answers for a size nothing drew. Same reading as {@link #MIN_CHROME_WIDTH}.
     *
     * <p>Top-left only: this is the primitive the anchored form above converts into. A caller with an
     * element reaches for {@link #boxAt(HudElement, int, int, int, int, int, int)} instead, which is what
     * keeps one element placed one way everywhere it is placed.
     */
    public static BookGeometry.Rect boxAt(int x, int y, int width, int height,
                                          int screenWidth, int screenHeight) {
        return BookGeometry.Rect.at(
                placed(x, screenWidth - width),
                placed(y, screenHeight - height),
                width, height);
    }

    /**
     * An inventory-anchored element's box: its stored offset, re-based on the panel's live corner.
     *
     * <p>The whole of the inventory anchor: the file holds an offset from the panel's top-left, and
     * the panel's live corner is added back every time anything is placed -- an inventory open, a
     * resize, the editor's next frame. A GUI scale or a window size that moves the centred panel
     * therefore moves the button with it, because the number that moved is re-read rather than
     * remembered. The clamp is still the window's, like every other position here.
     */
    public static BookGeometry.Rect boxAtInventory(int panelLeft, int panelTop, int x, int y,
                                                   int width, int height,
                                                   int screenWidth, int screenHeight) {
        return boxAt(panelLeft + x, panelTop + y, width, height, screenWidth, screenHeight);
    }

    /**
     * What the file holds for an inventory-anchored element, from where its box is on the window.
     *
     * <p>The inverse of the re-basing above, and deliberately unclamped: an offset is panel-relative,
     * so a window clamp would be a bound from the wrong space. Negative values place above or left of
     * the panel, and values past the panel's far edge place below or right of it; the window clamp at
     * draw time decides what any of those mean on a window too small to hold them.
     */
    public static int inventoryX(int windowX, int panelLeft) {
        return windowX - panelLeft;
    }

    /** The same, vertically. */
    public static int inventoryY(int windowY, int panelTop) {
        return windowY - panelTop;
    }

    /**
     * Where an inventory-anchored offset lands on the window, before the clamp.
     *
     * <p>What toggling back to the window frame stores: the button stays visibly where it is, and the
     * next placement clamps it like any other window position.
     */
    public static int windowX(int inventoryX, int panelLeft) {
        return panelLeft + inventoryX;
    }

    /** The same, vertically. */
    public static int windowY(int inventoryY, int panelTop) {
        return panelTop + inventoryY;
    }

    /**
     * Where the top of a box goes, from what the file holds.
     *
     * <p>Top-left for a top-left element: the stored number <i>is</i> the top. Centred for a middle
     * element: the stored number is an offset from the window's vertical centre, so the box grows equally
     * both ways as its content changes height -- and a stack that centred itself only in the default case
     * would jump the first time anything about it moved.
     *
     * <p>Clamped like everything else here: a stack taller than the window parks at the top rather than
     * centring off both edges, which is {@link #placed}'s answer for a box bigger than its limit.
     */
    public static int placedTop(HudElement element, int screenHeight, int y, int height) {
        if (element.anchor() == HudElement.Anchor.MIDDLE_LEFT) {
            return placed(screenHeight / 2 + y - height / 2, screenHeight - height);
        }
        return placed(y, screenHeight - height);
    }

    /**
     * What the file holds, from where a box was put.
     *
     * <p>The inverse of {@link #placedTop}, and it has to be: a drop writes the widget's top-left corner,
     * but a middle-anchored element is <i>read</i> from its centre, so storing the corner would move the
     * box by half its height on the next frame. Read and write go through the same anchor, which is what
     * makes a round trip land where the pointer put it down.
     */
    public static int storedY(HudElement element, int screenHeight, int top, int height) {
        if (element.anchor() == HudElement.Anchor.MIDDLE_LEFT) {
            return top + height / 2 - screenHeight / 2;
        }
        return top;
    }

    /** The panel's title, in the band at its top. */
    public static BookGeometry.Rect title(BookGeometry.Rect chrome) {
        return BookGeometry.Rect.at(chrome.x() + INSET, chrome.y() + INSET,
                Math.max(0, chrome.width() - INSET * 2), LABEL_LINE);
    }

    /** One row's name: left of its switch, centred on the control line they share. */
    public static BookGeometry.Rect label(int index, BookGeometry.Rect chrome,
                                          List<HudElement> elements) {
        int left = chrome.x() + INSET;
        int right = toggle(index, chrome, elements).x() - BUTTON_GAP;
        return BookGeometry.Rect.at(left, controlTop(index, chrome, elements) + (CONTROL_LINE - LABEL_LINE) / 2,
                Math.max(0, right - left), LABEL_LINE);
    }

    /**
     * The caption of a row's second line: "Background" beside the slider, "Anchor" beside the anchor
     * buttons. Never wider than the room left of the control it names.
     */
    public static BookGeometry.Rect caption(int index, BookGeometry.Rect chrome,
                                            List<HudElement> elements) {
        boolean drawn = elements.get(index).kind() == HudElement.Kind.HUD;
        int left = chrome.x() + INSET;
        int control = drawn ? sliderTrackLeft(slider(index, chrome, elements))
                : anchorToggle(index, chrome, elements).x();
        int line = drawn ? SLIDER_LINE : CONTROL_LINE;
        return BookGeometry.Rect.at(left, anchorTop(index, chrome, elements) + (line - LABEL_LINE) / 2,
                Math.max(0, Math.min(CAPTION_WIDTH, control - BUTTON_GAP - left)), LABEL_LINE);
    }

    /**
     * The show/hide switch of one row, docked left of its Reset button.
     *
     * <p>Right, with the row's actions, rather than left under the label's start: a 22-pixel switch
     * alone at the left edge with Reset alone at the right left a row's whole middle empty, and the
     * state read as disconnected from the row it belonged to. Labels keep the full width above; the
     * controls share one right edge below. The hide row docks to the same line through
     * {@link #hideToggle}, so every switch in the chrome answers to one vertical.
     */
    public static BookGeometry.Rect toggle(int index, BookGeometry.Rect chrome,
                                           List<HudElement> elements) {
        BookGeometry.Rect reset = reset(index, chrome, elements);
        return BookGeometry.Rect.at(reset.x() - BUTTON_GAP - SWITCH_WIDTH,
                reset.y() + (CONTROL_LINE - SWITCH_HEIGHT) / 2, SWITCH_WIDTH, SWITCH_HEIGHT);
    }

    /** The Reset button of one row, against the chrome's right inset. */
    public static BookGeometry.Rect reset(int index, BookGeometry.Rect chrome,
                                          List<HudElement> elements) {
        return BookGeometry.Rect.at(chrome.right() - INSET - BUTTON_WIDTH,
                controlTop(index, chrome, elements), BUTTON_WIDTH, CONTROL_LINE);
    }

    /**
     * A drawn element's background-strength slider, on its own line under its controls.
     *
     * <p>Full inset width: the slider keeps its track on the right half of itself and leaves the left
     * half for a label, which is where the "Background" caption goes -- so the track ends where Reset
     * above it does. Only meaningful for {@code Kind.HUD} rows -- the screen builds no slider for a
     * control, so asking this for one is a caller error the same way drawing one would be.
     */
    public static BookGeometry.Rect slider(int index, BookGeometry.Rect chrome,
                                           List<HudElement> elements) {
        return BookGeometry.Rect.at(chrome.x() + INSET, anchorTop(index, chrome, elements),
                Math.max(0, chrome.width() - INSET * 2), SLIDER_LINE);
    }

    /** Where a slider of this box starts its track: {@code ArmatureSlider}'s own half-and-minimum rule. */
    public static int sliderTrackLeft(BookGeometry.Rect slider) {
        return slider.right() - Math.max(ArmatureSlider.MIN_TRACK, slider.width() / 2);
    }

    /**
     * The book's button row's anchor switch, on its own line under its controls.
     *
     * <p>Right-aligned with the row's actions, behind an "Anchor" caption: alone at the left edge of an
     * unlabelled line, "Window" read as a button that opened something. Only meaningful for the button's
     * row.
     */
    public static BookGeometry.Rect anchorToggle(int index, BookGeometry.Rect chrome,
                                                 List<HudElement> elements) {
        return BookGeometry.Rect.at(anchorUnder(index, chrome, elements).x() - BUTTON_GAP - ANCHOR_WIDTH,
                anchorTop(index, chrome, elements), ANCHOR_WIDTH, CONTROL_LINE);
    }

    /**
     * The Under preset beside it: one press puts the button centred under the inventory panel.
     *
     * <p>Beside the switch the way Reset sits beside the show/hide switch: the two answer one question
     * -- what the button is measured from, and where under the panel that is -- so they share a line.
     */
    public static BookGeometry.Rect anchorUnder(int index, BookGeometry.Rect chrome,
                                                List<HudElement> elements) {
        return BookGeometry.Rect.at(chrome.right() - INSET - BUTTON_WIDTH,
                anchorTop(index, chrome, elements), BUTTON_WIDTH, CONTROL_LINE);
    }

    /** Done, in the chrome's foot. */
    public static BookGeometry.Rect done(BookGeometry.Rect chrome) {
        return BookGeometry.Rect.at(chrome.right() - INSET - BUTTON_WIDTH,
                chrome.bottom() - INSET - CONTROL_LINE, BUTTON_WIDTH, CONTROL_LINE);
    }

    /**
     * The auto-hide row's label line, after every element's row.
     *
     * <p>Index one past the elements, which the arithmetic already allows: {@code rowTop} sums what is
     * there, so an extra row needs no new rule, only a name for where it lands.
     */
    public static BookGeometry.Rect hideLabel(BookGeometry.Rect chrome, List<HudElement> elements) {
        return label(elements.size(), chrome, elements);
    }

    /** The auto-hide row's switch, on the same vertical as every element's. */
    public static BookGeometry.Rect hideToggle(BookGeometry.Rect chrome, List<HudElement> elements) {
        return toggle(elements.size(), chrome, elements);
    }

    private static int rowTop(int index, BookGeometry.Rect chrome, List<HudElement> elements) {
        int top = chrome.y() + INSET + HEADER_HEIGHT;
        for (int i = 0; i < index && i < elements.size(); i++) {
            top += rowHeight(elements.get(i));
        }
        return top;
    }

    private static int controlTop(int index, BookGeometry.Rect chrome,
                                  List<HudElement> elements) {
        return rowTop(index, chrome, elements);
    }

    private static int anchorTop(int index, BookGeometry.Rect chrome,
                                  List<HudElement> elements) {
        return controlTop(index, chrome, elements) + CONTROL_LINE + ROW_GAP;
    }
}
