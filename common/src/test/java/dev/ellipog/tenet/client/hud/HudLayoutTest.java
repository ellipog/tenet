package dev.ellipog.tenet.client.hud;

import dev.ellipog.armature.client.ArmatureSlider;
import dev.ellipog.tenet.client.BookGeometry;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The HUD editor's arithmetic, over a sweep of window sizes.
 *
 * <h2>Why these properties</h2>
 *
 * <p>Because each of them is a way an editor goes wrong without saying so, and none can be seen in a
 * screenshot taken at one window size:
 *
 * <ul>
 *   <li>an element must be somewhere on the window at every size, however it was dragged or however the file
 *       was hand-edited -- a control at a coordinate from a larger window is a control that has vanished;</li>
 *   <li>the rows panel must be on the window and its rows must not overlap each other, which is the fault
 *       that reads as a rendering glitch rather than a layout one;</li>
 *   <li>and the default position of every element must be on the window at the smallest size a client can
 *       have, because a default is what somebody sees before they have moved anything.</li>
 * </ul>
 *
 * <p>And the drag's own arithmetic, which is the fourth: it is the pointer less the grab the press took, so
 * it cannot drift. The rule that was there — the element's position plus the frame's rounded mouse movement
 * — is written out longhand below as the thing this is not, because that gap is invisible in one screenshot
 * and grows all drag long.
 *
 * <p>The windows swept are the ones a client can actually have: 176x120 GUI pixels at the smallest -- a
 * window narrower and shorter than any container panel -- up to 1920x1080.
 *
 * <h2>What is deliberately not asserted here</h2>
 *
 * <p>Nothing about where a <i>drawn</i> element is relative to a panel. An earlier version of this class
 * did: positions were offsets from the inventory panel's corner so the button travelled with the panel when
 * the recipe book slid it across the window. That made the editor draw a ghost of a panel it did not have, with
 * a corner that was not the real panel's corner, and the two spaces disagreed -- so the anchor went, and with
 * it every test about it. A window position is the whole of the model now, except the button's opt-in
 * inventory origin: that offset is re-based on the panel's live corner at every placement, and the ghost it
 * is edited against draws from the same centring the game uses, so the two corners agree by construction.
 * What is asserted about it is the re-basing, the round trip, and the row that switches it -- never a pixel
 * the game owns.
 */
@DisplayName("the HUD editor's layout")
class HudLayoutTest {

    private static final int[] WIDTHS = {176, 240, 320, 427, 640, 854, 1280, 1920};
    private static final int[] HEIGHTS = {120, 166, 180, 240, 300, 480, 720, 1080};

    @Test
    @DisplayName("every element is on the window, wherever it was put")
    void anElementIsAlwaysOnTheWindow() {
        HudElement element = HudElement.INVENTORY_BUTTON;
        for (int width : WIDTHS) {
            for (int height : HEIGHTS) {
                for (int x : new int[] {-999, -1, 0, 7, 100, 5000}) {
                    for (int y : new int[] {-60, 0, 33, 4000}) {
                        BookGeometry.Rect box = HudLayout.boxAt(element, width, height, x, y,
                                element.width(), element.height());
                        assertTrue(box.x() >= 0 && box.right() <= Math.max(width, element.width()),
                                "x " + x + " at " + width + " wide landed at " + box);
                        assertTrue(box.y() >= 0 && box.bottom() <= Math.max(height, element.height()),
                                "y " + y + " at " + height + " tall landed at " + box);
                    }
                }
            }
        }
    }

    @Test
    @DisplayName("each element's default position is on the window at every size")
    void defaultsFitEveryWindow() {
        for (HudElement element : HudElement.values()) {
            for (int width : WIDTHS) {
                for (int height : HEIGHTS) {
                    BookGeometry.Rect box = HudLayout.boxAt(element, width, height,
                            element.defaultX(), element.defaultY(), element.width(), element.height());
                    assertTrue(box.x() >= 0 && box.right() <= Math.max(width, element.width()),
                            element + "'s default x is off the window at " + width + "x" + height + ": " + box);
                    assertTrue(box.y() >= 0 && box.bottom() <= Math.max(height, element.height()),
                            element + "'s default y is off the window at " + width + "x" + height + ": " + box);
                }
            }
        }
    }

    @Test
    @DisplayName("the rows panel is on the window, gives way to a narrow one, and grows with every element")
    void theChromeFitsAndGrows() {
        java.util.List<HudElement> all = java.util.List.of(HudElement.values());
        for (int width : WIDTHS) {
            for (int height : HEIGHTS) {
                for (int rows = 1; rows <= 6; rows++) {
                    java.util.List<HudElement> shown = all.subList(0, Math.min(rows, all.size()));
                    BookGeometry.Rect chrome = HudLayout.chrome(width, height, shown);
                    assertTrue(chrome.x() >= 0 && chrome.y() >= 0, "the rows start off the window: " + chrome);
                    assertTrue(chrome.width() <= HudLayout.CHROME_WIDTH,
                            "the rows are never wider than they need to be: " + chrome);

                    if (width >= HudLayout.MIN_CHROME_WIDTH + HudLayout.INSET * 2) {
                        // A window with room for the controls: then nothing may be off it, because a control
                        // the player cannot reach is worse than a panel that overlaps something.
                        assertTrue(chrome.right() <= width, "the rows run off the right edge at " + width
                                + ": " + chrome);
                        assertTrue(chrome.bottom() <= Math.max(height, chrome.height()),
                                "the rows run off the bottom at " + height + ": " + chrome);
                    }
                    else {
                        // Narrower than the controls need. The panel stops shrinking at its floor and the
                        // window gets an overlapping edge, which is the honest outcome -- but it still starts
                        // at the margin rather than somewhere arbitrary.
                        assertEquals(HudLayout.MIN_CHROME_WIDTH, chrome.width(),
                                "below the floor the panel keeps its controls: " + chrome);
                    }

                    if (rows > 1) {
                        assertTrue(chrome.height() > HudLayout.chrome(width, height,
                                        shown.subList(0, shown.size() - 1)).height(),
                                "an extra element has to make room for itself");
                    }
                }
            }
        }
    }

    @Test
    @DisplayName("the rows are inside their panel and never overlap each other")
    void theRowsStayInsideTheirPanel() {
        java.util.List<HudElement> all = java.util.List.of(HudElement.values());
        for (int rows = 1; rows <= 6; rows++) {
            java.util.List<HudElement> shown = all.subList(0, Math.min(rows, all.size()));
            BookGeometry.Rect chrome = HudLayout.chrome(640, 480, shown);
            BookGeometry.Rect previous = null;
            for (int index = 0; index < shown.size(); index++) {
                BookGeometry.Rect label = HudLayout.label(index, chrome, shown);
                BookGeometry.Rect toggle = HudLayout.toggle(index, chrome, shown);
                BookGeometry.Rect reset = HudLayout.reset(index, chrome, shown);

                for (BookGeometry.Rect box : new BookGeometry.Rect[] {label, toggle, reset}) {
                    assertTrue(inside(box, chrome), "row " + index + "'s " + box + " is outside " + chrome);
                }
                assertFalse(overlaps(toggle, reset), "the switch and Reset collide on row " + index);
                assertFalse(overlaps(label, toggle), "the label runs into the switch on row " + index);
                if (shown.get(index).kind() == HudElement.Kind.HUD) {
                    BookGeometry.Rect slider = HudLayout.slider(index, chrome, shown);
                    assertTrue(inside(slider, chrome), "row " + index + "'s slider is outside " + chrome);
                    assertFalse(overlaps(reset, slider), "the slider runs into Reset on row " + index);
                    assertEquals(reset.right(), slider.right(), "the slider's track ends where Reset does");
                    BookGeometry.Rect caption = HudLayout.caption(index, chrome, shown);
                    assertTrue(caption.right() < HudLayout.sliderTrackLeft(slider), "the Background caption runs into the track");
                    assertTrue(slider.width() >= ArmatureSlider.MIN_TRACK, "the slider keeps room to aim in");
                }
                if (previous != null) {
                    assertFalse(overlaps(previous, label), "row " + index + " starts before row " + (index - 1)
                            + " has finished");
                }
                previous = label;
            }
            assertTrue(inside(HudLayout.done(chrome), chrome), "Done is inside the foot");
            for (int index = 0; index < shown.size(); index++) {
                assertFalse(overlaps(HudLayout.done(chrome), HudLayout.reset(index, chrome, shown)),
                        "Done collides with a row's Reset at " + shown.size() + " rows");
            }
        }
    }

    @Test
    @DisplayName("a drag is the pointer less the grab, so the element keeps the offset it was grabbed by")
    void aDragIsThePointerLessTheGrab() {
        double grab = 6.4;
        // Every pointer that leaves the element on the window: its edge is the pointer less the grab, to the
        // pixel, and the offset it was grabbed by does not change from one end of the drag to the other.
        for (double pointer = grab; pointer <= 400; pointer += 0.25) {
            int at = HudLayout.dragged(pointer, grab, 1000);
            assertEquals(pointer - grab, at, 0.5,
                    "the pointer at " + pointer + " with a grab of " + grab);
        }
        // And the two ends, through the same clamp every other position here goes through.
        assertEquals(0, HudLayout.dragged(0, grab, 1000), "a pointer left of the grab parks at the edge");
        assertEquals(1000, HudLayout.dragged(5000, grab, 1000), "and one past the far end parks at the limit");
        assertEquals(0, HudLayout.dragged(grab, grab, 1000), "a pointer on the grab lands the edge on the pointer");
    }

    @Test
    @DisplayName("a drag that arrives in small steps lands where the last pointer says, and the deltas do not")
    void manySmallDragsLandWhereThePointerIs() {
        // The rule this replaced, written out: the element's position plus the frame's rounded movement.
        // The game hands a widget one delta per frame, already scaled into GUI pixels -- so at a GUI scale
        // of three, ten window pixels of movement is 3.333 GUI pixels, which rounds to three. That is a
        // third of a pixel lost every frame of the drag, and a slow drag rounds to nothing at all: an
        // element that sits still while the cursor moves. It was reported as "it follows but drifts".
        int byDeltas = 0;
        double pointer = 0;
        for (int frame = 0; frame < 60; frame++) {
            double next = pointer + 10.0 / 3.0;
            byDeltas += (int) Math.round(next - pointer);
            pointer = next;
        }

        assertEquals(200, HudLayout.dragged(pointer, 0, 1000),
                "the pointer has travelled two hundred pixels, and the element is there");
        assertEquals(180, byDeltas, "while the sum of the rounded deltas is twenty pixels short");
        assertTrue(byDeltas < HudLayout.dragged(pointer, 0, 1000),
                "which is the whole of the drift: a gap that only ever grows");
    }

    @Test
    @DisplayName("a drag past an edge parks the element on the window, and the grab survives it")
    void aDragIsClampedAtEveryEdge() {
        HudElement element = HudElement.INVENTORY_BUTTON;
        for (int width : WIDTHS) {
            for (int height : HEIGHTS) {
                int xLimit = Math.max(0, width - element.width());
                int yLimit = Math.max(0, height - element.height());
                for (double pointer : new double[] {-400, -0.5, 0, 33.5, 5000}) {
                    int x = HudLayout.dragged(pointer, 6.0, xLimit);
                    int y = HudLayout.dragged(pointer, 6.0, yLimit);
                    assertTrue(x >= 0 && x + element.width() <= Math.max(width, element.width()),
                            "a drag to " + pointer + " left the element at " + x + " on a " + width
                                    + "-wide window");
                    assertTrue(y >= 0 && y + element.height() <= Math.max(height, element.height()),
                            "a drag to " + pointer + " left the element at " + y + " on a " + height
                                    + "-tall window");
                }
                // The offset is kept rather than forgotten: the element is parked at the edge and comes back
                // with the pointer, where adding deltas to a clamped position would have made it stick.
                assertEquals(xLimit, HudLayout.dragged(5000, 0, xLimit), "the far edge is reachable");
                assertEquals(xLimit, HudLayout.dragged(5000, 6.0, xLimit),
                        "and a grab cannot push it past the edge either");
            }
        }
    }

    @Test
    @DisplayName("every control's sprite fits inside its box, inset on every side")
    void everySpriteFitsItsBox() {
        for (HudElement element : HudElement.values()) {
            // A drawn element has no sprite and no box to inset one into: its own drawing decides where its
            // icon goes, and the entry carries a zero inset for that reason. Only a control fills the table's
            // box with a sprite.
            if (element.kind() != HudElement.Kind.CONTROL) {
                assertEquals(0, element.iconInset(),
                        element + " is drawn by the HUD and still declares a sprite inset");
                continue;
            }
            assertTrue(element.iconInset() > 0,
                    element + " has no inset, so its sprite would touch its own edge");
            assertTrue(element.iconInset() * 2 <= Math.min(element.width(), element.height()),
                    element + " draws a " + (element.width() - element.iconInset() * 2) + "-pixel sprite in a "
                            + element.width() + "-pixel box");
        }
    }

    /**
     * A drawn element is as big as what it holds, so the box is the caller's size rather than the entry's.
     *
     * <h2>Why the size is asserted as well as the position</h2>
     *
     * <p>Because the clamp's arithmetic is {@code screenWidth - width}, and a version that clamped against
     * the element's own table size while drawing the measured one would put a six-pin panel half off the
     * window -- while every position assertion here still passed, because the position it landed at is a
     * legal one for a box of the wrong size. The size is therefore part of the claim.
     */
    @Test
    @DisplayName("a box the caller measured is clamped against its own size, however big that is")
    void aMeasuredBoxIsClampedToo() {
        for (int width : WIDTHS) {
            for (int height : HEIGHTS) {
                for (int boxWidth : new int[] {1, 40, PinnedPanelLayout.MAX_WIDTH, 4000}) {
                    for (int boxHeight : new int[] {1, 14, 300, 4000}) {
                        BookGeometry.Rect box = HudLayout.boxAt(-99, 9999, boxWidth, boxHeight, width, height);
                        assertEquals(boxWidth, box.width(), "the size is the caller's, never rewritten");
                        assertEquals(boxHeight, box.height(), "the same, vertically");
                        assertTrue(box.x() >= 0 && box.right() <= Math.max(width, boxWidth),
                                "a " + boxWidth + "-wide box on a " + width + "-wide window landed at " + box);
                        assertTrue(box.y() >= 0 && box.bottom() <= Math.max(height, boxHeight),
                                "a " + boxHeight + "-tall box on a " + height + "-tall window landed at " + box);
                    }
                }
            }
        }
    }

    @Test
    @DisplayName("the element form is the measured form, placed through the element's anchor")
    void theElementFormIsTheMeasuredForm() {
        for (HudElement element : HudElement.values()) {
            for (int width : WIDTHS) {
                for (int height : HEIGHTS) {
                    for (int x : new int[] {-40, 0, 55, 9000}) {
                        for (int boxHeight : new int[] {14, 200, 4000}) {
                            BookGeometry.Rect box = HudLayout.boxAt(element, width, height, x, 12,
                                    element.width(), boxHeight);
                            assertEquals(element.width(), box.width(), "the size is the caller's");
                            assertEquals(boxHeight, box.height());
                            if (element.anchor() == HudElement.Anchor.TOP_LEFT) {
                                assertEquals(
                                        HudLayout.boxAt(x, 12, element.width(), boxHeight, width, height),
                                        box, element + " has two answers to what its box is");
                            }
                            else {
                                // A middle-anchored element centres what the top-left form would corner:
                                // the same x, and a y the stored offset moves equally both ways.
                                assertEquals(
                                        HudLayout.boxAt(x, height / 2 + 12 - boxHeight / 2, element.width(),
                                                boxHeight, width, height),
                                        box, element + " centres rather than corners");
                            }
                        }
                    }
                }
            }
        }
    }

    /**
     * The middle-anchored element grows from its middle, and a stored position round-trips.
     *
     * <p>Because that is the whole of the anchor: a stack that centred itself in the default case and
     * cornered itself the moment anything about it moved would jump the first time a pin landed, and a
     * drop that stored a corner the game reads as a centre would move the stack by half its height on
     * the next frame. Both are asserted here rather than argued about, over the same sweep of windows
     * as everything else in this file.
     */
    @Test
    @DisplayName("a middle-anchored box stays centred as it grows, and a drop stores its centre")
    void middleAnchorGrowsBothWays() {
        HudElement element = HudElement.PINNED_QUESTS;
        for (int width : WIDTHS) {
            for (int height : HEIGHTS) {
                // At the default offset of zero, the middle of the box is the middle of the window --
                // whatever the box holds. That is the claim "middle-left by default" reduces to.
                for (int boxHeight : new int[] {14, 100, 300}) {
                    if (boxHeight > height) {
                        continue;
                    }
                    BookGeometry.Rect box = HudLayout.boxAt(element, width, height, 4, 0, 150, boxHeight);
                    assertEquals(height / 2, box.y() + box.height() / 2,
                            "a default stack is centred at " + width + "x" + height + ": " + box);
                }
                // A taller stack than the window parks at the top rather than centring off both edges,
                // which is `placed`'s answer for a box bigger than its limit.
                BookGeometry.Rect over = HudLayout.boxAt(element, width, height, 4, 0, 150, height + 400);
                assertEquals(0, over.y(), "an overflowing stack parks at the top: " + over);

                // The round trip a drop depends on: place it, store it, place it again, same box.
                for (int y : new int[] {-200, -3, 0, 40, 900}) {
                    BookGeometry.Rect placed = HudLayout.boxAt(element, width, height, 4, y, 150, 120);
                    int stored = HudLayout.storedY(element, height, placed.y(), placed.height());
                    BookGeometry.Rect again = HudLayout.boxAt(element, width, height, 4, stored, 150, 120);
                    assertEquals(placed, again, "a drop at offset " + y + " moved the stack to " + again);
                }
                // And the path a real drop takes, which the round trip above does not model: the widget
                // hands over its corner, not a placed box, and routing that corner through `boxAt` would
                // read it as a centre-offset a second time -- the box jumping by half its height on release.
                // A drop clamps the corner first and converts once, so the next frame draws it where the
                // pointer put it down.
                for (int corner : new int[] {-50, 0, 200, 1000}) {
                    int top = HudLayout.placed(corner, height - 120);
                    int stored = HudLayout.storedY(element, height, top, 120);
                    BookGeometry.Rect drawn = HudLayout.boxAt(element, width, height, 4, stored, 150, 120);
                    assertEquals(top, drawn.y(), "a drop at corner " + corner + " drew at " + drawn.y());
                }
                // And a top-left element stores the corner it was given, as it always has.
                BookGeometry.Rect corner = HudLayout.boxAt(HudElement.NOTIFICATIONS, width, height,
                        9, 44, 150, 14);
                assertEquals(corner.y(),
                        HudLayout.storedY(HudElement.NOTIFICATIONS, height, corner.y(), corner.height()));
            }
        }
    }

    /**
     * The middle default is centred, at every swept size.
     *
     * <p>A default is what a player sees before they have moved anything, and "middle-left" that arrived
     * somewhere else would read as the anchor not working rather than as a setting not yet visited. Every
     * swept window is taller than the table's own box, so this is the centred case throughout; the parked
     * case -- a stack taller than its window -- is covered by the round trip below, which sweeps a 4000px
     * box against the same windows.
     */
    @Test
    @DisplayName("the middle-anchored default is centred wherever it fits")
    void theMiddleDefaultIsCentred() {
        HudElement element = HudElement.PINNED_QUESTS;
        for (int width : WIDTHS) {
            for (int height : HEIGHTS) {
                BookGeometry.Rect box = HudLayout.boxAt(element, width, height,
                        element.defaultX(), element.defaultY(), element.width(), element.height());
                assertEquals(height / 2, box.y() + box.height() / 2,
                        "centred at " + width + "x" + height + ": " + box);
            }
        }
    }

    /**
     * The shipped positions do not start on top of each other at an ordinary size.
     *
     * <p>A default is what a player sees before they have moved anything, and a client whose pinned stack
     * and whose notices are drawn on top of each other on the first quest completed reads as a broken mod
     * rather than as a setting they have not visited yet. Asserted at one ordinary window rather than swept:
     * the pins are middle-anchored now, so on a short enough window any two fixed rows meet -- which is what
     * the editor is for, and what a sweep would misread as a fault.
     */
    @Test
    @DisplayName("the two HUD elements do not ship on top of each other")
    void theDefaultsDoNotSitOnEachOther() {
        BookGeometry.Rect pinned = HudLayout.boxAt(HudElement.PINNED_QUESTS, 320, 240,
                HudElement.PINNED_QUESTS.defaultX(), HudElement.PINNED_QUESTS.defaultY(),
                HudElement.PINNED_QUESTS.width(), HudElement.PINNED_QUESTS.height());
        BookGeometry.Rect notices = HudLayout.boxAt(HudElement.NOTIFICATIONS, 320, 240,
                HudElement.NOTIFICATIONS.defaultX(), HudElement.NOTIFICATIONS.defaultY(),
                HudElement.NOTIFICATIONS.width(), HudElement.NOTIFICATIONS.height());
        assertFalse(overlaps(pinned, notices), "the shipped layout draws the pinned stack over the"
                + " notices at 320x240: " + pinned + " and " + notices);
    }

    @Test
    @DisplayName("a drawn row is taller than a control row by exactly its slider line")
    void hudRowsCarryASliderLine() {        assertEquals(
                HudLayout.ROW_HEIGHT + HudLayout.CONTROL_LINE + HudLayout.ROW_GAP,
                HudLayout.rowHeight(HudElement.INVENTORY_BUTTON),
                "the button's second line is the anchor switch and the Under preset, not a slider");
        assertEquals(HudLayout.ROW_HEIGHT + HudLayout.SLIDER_LINE + HudLayout.ROW_GAP,
                HudLayout.rowHeight(HudElement.PINNED_QUESTS));
        assertEquals(HudLayout.ROW_HEIGHT + HudLayout.SLIDER_LINE + HudLayout.ROW_GAP,
                HudLayout.rowHeight(HudElement.NOTIFICATIONS));

        java.util.List<HudElement> all = java.util.List.of(HudElement.values());
        BookGeometry.Rect chrome = HudLayout.chrome(640, 480, all);
        for (int index = 0; index < all.size(); index++) {
            if (all.get(index).kind() != HudElement.Kind.HUD) {
                continue;
            }
            BookGeometry.Rect slider = HudLayout.slider(index, chrome, all);
            BookGeometry.Rect reset = HudLayout.reset(index, chrome, all);
            assertEquals(reset.y() + HudLayout.CONTROL_LINE + HudLayout.ROW_GAP, slider.y(),
                    "the slider sits on its own line under the controls of row " + index);
            assertEquals(HudLayout.SLIDER_LINE, slider.height());
        }
    }

    private static boolean overlaps(BookGeometry.Rect one, BookGeometry.Rect other) {
        return one.x() < other.right() && other.x() < one.right()
                && one.y() < other.bottom() && other.y() < one.bottom();
    }

    @Test
    @DisplayName("an inventory-anchored box is the corner plus the offset, clamped like any other box")
    void inventoryAnchorRebasesTheCorner() {
        for (int width : WIDTHS) {
            for (int height : HEIGHTS) {
                for (boolean survival : new boolean[] {true, false}) {
                    for (boolean book : new boolean[] {true, false}) {
                        BookGeometry.Rect panel =
                                InventoryPanel.rect(width, height, survival, book);
                        for (int[] offset : new int[][] {{0, 0}, {80, 170}, {-30, -30}, {2000, 2000}}) {
                            BookGeometry.Rect box = HudLayout.boxAtInventory(panel.x(), panel.y(),
                                    offset[0], offset[1], 16, 16, width, height);
                            assertEquals(
                                    HudLayout.boxAt(panel.x() + offset[0], panel.y() + offset[1],
                                            16, 16, width, height),
                                    box, "one place either way at " + width + "x" + height);
                        }
                    }
                }
            }
        }
    }

    @Test
    @DisplayName("toggling frames keeps the visible place: the conversions round-trip")
    void frameConversionsRoundTrip() {
        for (int corner : new int[] {-300, 0, 62, 5000}) {
            for (int offset : new int[] {-500, -8, 0, 80, 4000}) {
                assertEquals(corner, HudLayout.windowX(HudLayout.inventoryX(corner, offset), offset),
                        "window to offset and back through a corner at " + corner);
                assertEquals(offset, HudLayout.inventoryX(HudLayout.windowX(offset, corner), corner),
                        "offset to window and back through a corner at " + corner);
                assertEquals(corner, HudLayout.windowY(HudLayout.inventoryY(corner, offset), offset));
                assertEquals(offset, HudLayout.inventoryY(HudLayout.windowY(offset, corner), corner));
            }
        }
    }

    @Test
    @DisplayName("the button's row carries an anchor line the other rows do not")
    void buttonRowCarriesTheAnchorLine() {
        assertEquals(HudLayout.ROW_HEIGHT + HudLayout.CONTROL_LINE + HudLayout.ROW_GAP,
                HudLayout.rowHeight(HudElement.INVENTORY_BUTTON),
                "a second control line, like the slider line on a drawn row");
        assertEquals(HudLayout.ROW_HEIGHT + HudLayout.SLIDER_LINE + HudLayout.ROW_GAP,
                HudLayout.rowHeight(HudElement.NOTIFICATIONS),
                "a drawn row keeps its slider line, anchor or no anchor");

        java.util.List<HudElement> all = java.util.List.of(HudElement.values());
        BookGeometry.Rect chrome = HudLayout.chrome(640, 480, all);
        int button = all.indexOf(HudElement.INVENTORY_BUTTON);
        BookGeometry.Rect toggle = HudLayout.anchorToggle(button, chrome, all);
        BookGeometry.Rect under = HudLayout.anchorUnder(button, chrome, all);

        assertTrue(inside(toggle, chrome), "the anchor switch is inside the chrome: " + toggle);
        assertTrue(inside(under, chrome), "and the Under preset with it: " + under);
        assertFalse(overlaps(toggle, under), "the two do not collide");
        assertEquals(HudLayout.CONTROL_LINE, toggle.height(), "on a control line of their own");
        assertEquals(HudLayout.ANCHOR_WIDTH + HudLayout.BUTTON_GAP, under.x() - toggle.x(),
                "side by side like the switch and Reset above them");
        assertFalse(overlaps(toggle, HudLayout.reset(button, chrome, all)),
                "clear of the row's own Reset");
        assertFalse(overlaps(under, HudLayout.done(chrome)), "and clear of the foot");
        assertEquals(HudLayout.reset(button, chrome, all).right(), under.right(),
                "right-aligned with the row's actions");
        assertFalse(overlaps(HudLayout.caption(button, chrome, all), toggle), "the Anchor caption stays clear");
        for (int index = 0; index < all.size(); index++) {
            if (index == button) {
                continue;
            }
            assertFalse(overlaps(toggle, HudLayout.label(index, chrome, all)),
                    "and clear of row " + index);
        }
    }

    @Test
    @DisplayName("the auto-hide row sits after the elements and inside the chrome, clear of Done")
    void theHideRowHasItsOwnLine() {
        java.util.List<HudElement> all = java.util.List.of(HudElement.values());
        BookGeometry.Rect chrome = HudLayout.chrome(640, 480, all);
        BookGeometry.Rect label = HudLayout.hideLabel(chrome, all);
        BookGeometry.Rect toggle = HudLayout.hideToggle(chrome, all);

        assertTrue(inside(label, chrome) && inside(toggle, chrome),
                "the hide row is chrome furniture: " + label + " and " + toggle);
        assertFalse(overlaps(label, toggle), "the label runs into its own switch");
        assertTrue(inside(HudLayout.done(chrome), chrome), "and Done still fits");
        assertFalse(overlaps(toggle, HudLayout.done(chrome)), "clear of the foot");
        for (int index = 0; index < all.size(); index++) {
            assertFalse(overlaps(HudLayout.reset(index, chrome, all), label),
                    "and clear of row " + index);
        }
    }

    private static boolean inside(BookGeometry.Rect box, BookGeometry.Rect outer) {
        return box.x() >= outer.x() && box.y() >= outer.y()
                && box.right() <= outer.right() && box.bottom() <= outer.bottom();
    }
}
