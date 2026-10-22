package dev.ellipog.tenet.client.dev;

import dev.ellipog.tenet.client.BookGeometry;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The item picker's arithmetic: which rows exist, how tall the list is, and where a pointer lands.
 *
 * <h2>Why this is a class and not numbers at the call site</h2>
 *
 * <p>The drawing and the press have to agree about where a row is -- a row drawn at one rectangle and
 * hit at another is a row an author cannot press, and the failure reads as a broken mouse rather than
 * as arithmetic. One derivation, asserted here over real rectangles, is what keeps them the same
 * derivation. No client is needed: rows are integers.
 */
@DisplayName("the item picker's layout")
class ItemPickerLayoutTest {

    private static final ItemPicker.Entry OAK =
            new ItemPicker.Entry("minecraft:oak_log", "Oak Log", 8);
    private static final ItemPicker.Entry STICK =
            new ItemPicker.Entry("minecraft:stick", "Stick", 1);

    /** The same item as it comes from the registry: carried counts belong to the inventory's rows. */
    private static final ItemPicker.Entry OAK_REGISTRY =
            new ItemPicker.Entry("minecraft:oak_log", "Oak Log", 0);

    /** A frame with a real search box and a list 100 tall, so the scroll rules have somewhere to go. */
    private static ItemPickerLayout.Frame frame() {
        // Through `Frame.of` rather than the record's own constructor: the fixture then exercises the
        // split the screen uses, so a strip that moved would move this too instead of being asserted
        // against a second, hand-built rectangle that agrees with nothing.
        return ItemPickerLayout.Frame.of(BookGeometry.Rect.at(10, 20, 200,
                ItemPickerLayout.SEARCH_HEIGHT + 100));
    }

    @Test
    @DisplayName("an empty picker is no rows at all -- the empty state is the drawing's, not a row's")
    void emptyIsEmpty() {
        assertTrue(ItemPickerLayout.compose(List.of(), List.of(), ItemPickerLayout.Current.NONE, null, "")
                .isEmpty());
    }

    private static final ItemPickerLayout.Current CLEARABLE =
            new ItemPickerLayout.Current("minecraft:oak_log", true, true);

    @Test
    @DisplayName("the current value gets a clear row, the inventory its heading, and counts only over one")
    void sections() {
        List<ItemPickerLayout.Row> rows = ItemPickerLayout.compose(
                List.of(OAK, STICK), List.of(), CLEARABLE, null, "");

        assertEquals(ItemPickerLayout.Kind.CLEAR, rows.get(0).kind(), "clear first, above everything");
        assertEquals("Clear", rows.get(0).label(), "and it says what the press does");
        assertEquals("goes back to the default", rows.get(0).secondary());
        assertEquals(ItemPickerLayout.Kind.HEADING, rows.get(1).kind());
        assertEquals("In your inventory", rows.get(1).label());
        assertEquals("x8 Oak Log", rows.get(2).label(),
                "the row is the item's name; a count worth saying rides in front");
        assertEquals("minecraft:oak_log", rows.get(2).secondary(),
                "and the id is shown, because it is what a typed answer matches");
        assertEquals("Stick", rows.get(3).label());
        assertEquals("minecraft:stick", rows.get(3).secondary());
        assertEquals(4, rows.size(), "no query, no All-items section");
    }

    @Test
    @DisplayName("a query puts the results above the inventory, and the inventory keeps its own")
    void aQueryOpensTheList() {
        List<ItemPickerLayout.Row> rows = ItemPickerLayout.compose(
                List.of(STICK), List.of(OAK_REGISTRY), ItemPickerLayout.Current.NONE, null, "oak");

        assertEquals("All items", rows.get(0).label(), "results first -- the list is what was asked for");
        assertEquals("minecraft:oak_log", rows.get(1).id());
        assertEquals("Oak Log", rows.get(1).label(), "a name search's results read as names");
        assertEquals("minecraft:oak_log", rows.get(1).secondary());
        assertEquals("In your inventory", rows.get(2).label(), "and what you carry follows");
        assertEquals("Stick", rows.get(3).label());
        assertEquals(4, rows.size());
    }

    @Test
    @DisplayName("a current value the build does not have gets its own row, kept and marked")
    void theMissingCurrentValueIsShown() {
        List<ItemPickerLayout.Row> rows = ItemPickerLayout.compose(
                List.of(), List.of(),
                new ItemPickerLayout.Current("someothermod:widget", false, false), null, "");

        assertEquals(ItemPickerLayout.Kind.MISSING, rows.get(0).kind());
        assertEquals("someothermod:widget", rows.get(0).id(), "the id travels, so it can be kept");
        assertEquals("missing \u2014 the id is kept", rows.get(0).secondary());
        assertEquals(1, rows.size(), "and there is nothing else to show");
    }

    @Test
    @DisplayName("a typed id that matches nothing is offered as a row, so it can be written on purpose")
    void theTypedMissingIdIsOffered() {
        List<ItemPickerLayout.Row> rows = ItemPickerLayout.compose(
                List.of(), List.of(), ItemPickerLayout.Current.NONE,
                "someothermod:widget", "someothermod:widget");

        assertEquals(ItemPickerLayout.Kind.HEADING, rows.get(0).kind());
        assertEquals("All items", rows.get(0).label());
        assertEquals(ItemPickerLayout.Kind.MISSING, rows.get(1).kind());
        assertEquals("someothermod:widget", rows.get(1).id());
        assertEquals("not installed \u2014 use it anyway", rows.get(1).secondary());
    }

    @Test
    @DisplayName("the frame is the body's top strip for the box, the rest for the list, and a strip for the bar")
    void theFrameSplitsTheBody() {
        BookGeometry.Rect body = BookGeometry.Rect.at(5, 7, 210, 130);
        ItemPickerLayout.Frame frame = ItemPickerLayout.Frame.of(body);

        assertEquals(body.x(), frame.search().x());
        assertEquals(body.y(), frame.search().y());
        assertEquals(ItemPickerLayout.SEARCH_HEIGHT, frame.search().height());
        assertEquals(body.width(), frame.search().width());
        assertEquals(frame.search().bottom(), frame.list().y(), "no gap: the list starts under the box");
        assertEquals(body.bottom(), frame.list().bottom(), "and ends with the body");

        // The bar's strip, and the property the two rectangles have to have: they tile the band the
        // rows live in, with no overlap and no seam. A row drawn under the strip is a row whose text is
        // cut off, and a gap between them is a column of card nobody drew.
        assertEquals(frame.list().right(), frame.scrollbar().x(), "the strip starts where the rows stop");
        assertEquals(body.right(), frame.scrollbar().right(), "and ends at the body's own edge");
        assertEquals(body.width(), frame.list().width() + frame.scrollbar().width(),
                "together they are exactly the body's width");
        assertEquals(ItemPickerLayout.SCROLLBAR, frame.scrollbar().width());
        assertTrue(frame.scrollbar().width() >= dev.ellipog.armature.client.ui.kit.ScrollBar.WIDTH,
                "the reserved strip has to hold the bar the kit draws in it");
        assertEquals(frame.list().height(), frame.scrollbar().height(),
                "and the bar is as tall as the list it describes");
    }

    @Test
    @DisplayName("the content is as tall as its rows, and what fits needs no scroll")
    void theScroll() {
        ItemPickerLayout.Frame frame = frame();
        List<ItemPickerLayout.Row> rows = ItemPickerLayout.compose(
                List.of(OAK, STICK), List.of(OAK, STICK), CLEARABLE, null, "oak");

        int content = ItemPickerLayout.contentHeight(rows);
        assertTrue(content > frame.list().height(), "the fixture should overflow, or this proves nothing");
        // The clamp itself is the viewport's now -- the list is told how tall its content is and subtracts
        // its own band -- so what this asserts is the height, which is the one number this class owes.
        assertEquals(5 * ItemPickerLayout.ROW_HEIGHT + 2 * ItemPickerLayout.HEADING_HEIGHT, content,
                "the clear row, two results under their heading, and the inventory under its own");

        assertEquals(0, ItemPickerLayout.contentHeight(List.of()), "nothing to scroll costs nothing");
    }

    @Test
    @DisplayName("a row is drawn and pressed at one rectangle, and the scroll moves both together")
    void rowsMapThroughTheScroll() {
        ItemPickerLayout.Frame frame = frame();
        List<ItemPickerLayout.Row> rows = ItemPickerLayout.compose(
                List.of(OAK), List.of(), ItemPickerLayout.Current.NONE, null, "");

        // Row 0 is the inventory heading, row 1 the item.
        BookGeometry.Rect at0 = ItemPickerLayout.rowRect(rows, frame, 0, 1);
        assertEquals(frame.list().x(), at0.x());
        assertTrue(at0.y() >= frame.list().y(), "inside the list");
        assertEquals(1, ItemPickerLayout.rowAt(rows, frame, 0, at0.y() + 1));
        assertEquals(-1, ItemPickerLayout.rowAt(rows, frame, 0, at0.bottom() + 40),
                "below the last row is no row");

        BookGeometry.Rect scrolled = ItemPickerLayout.rowRect(rows, frame, 10, 1);
        assertEquals(at0.y() - 10, scrolled.y(), "ten scrolled is ten up");
    }

    @Test
    @DisplayName("the selection steps over headings, and a stale index lands on a real row")
    void theSelectionSteps() {
        List<ItemPickerLayout.Row> rows = ItemPickerLayout.compose(
                List.of(OAK, STICK), List.of(OAK), CLEARABLE, null, "oak");

        int first = ItemPickerLayout.firstPickable(rows);
        assertEquals(0, first, "the clear row is pickable");
        int second = ItemPickerLayout.step(rows, first, 1);
        assertEquals(2, second, "the heading between them is not a stop");
        int back = ItemPickerLayout.step(rows, second, -1);
        assertEquals(first, back, "and back again");
        assertEquals(rows.size() - 1, ItemPickerLayout.step(rows, rows.size() - 1, 1),
                "the end is the end, not a wrap");
        assertEquals(rows.size() - 1, ItemPickerLayout.clamp(rows, 99),
                "a stale index from a shrunken list lands on the nearest real row, not the first");
        assertEquals(first, ItemPickerLayout.clamp(rows, -5), "and one below zero lands on the first");
        assertEquals(-1, ItemPickerLayout.firstPickable(List.of()), "nothing to select");
    }

    @Test
    @DisplayName("a row that says why it cannot be taken is not a row that can be taken")
    void aRefusalIsNotPickable() {
        // The rule the type picker leans on: a type a table will not hold is offered -- hiding it leaves
        // an author with no way to find out why it is absent -- and it cannot be pressed, landed on by
        // the arrows, or committed by Enter.
        List<ItemPickerLayout.Row> rows = List.of(
                ItemPickerLayout.Row.of(ItemPickerLayout.Kind.HEADING, "", "Rewards", ""),
                ItemPickerLayout.Row.of(ItemPickerLayout.Kind.TYPE, "tenet:item", "Item",
                        "tenet:item"),
                ItemPickerLayout.Row.blocked(ItemPickerLayout.Kind.TYPE, "tenet:choice", "Choice",
                        "tenet:choice", "a table cannot hold a choice"),
                ItemPickerLayout.Row.of(ItemPickerLayout.Kind.TYPE, "tenet:xp", "Xp", "tenet:xp"));

        assertFalse(ItemPickerLayout.pickable(rows.get(2)), "a refused row is not pickable");
        assertTrue(ItemPickerLayout.pickable(rows.get(1)), "and its neighbours are, note and all");
        assertEquals("", rows.get(1).note(), "a row with nothing against it carries no note");

        assertEquals(1, ItemPickerLayout.firstPickable(rows), "the heading is stepped over as well");
        assertEquals(3, ItemPickerLayout.step(rows, 1, 1), "and so is the refusal");
        assertEquals(1, ItemPickerLayout.step(rows, 3, -1));
        assertEquals(1, ItemPickerLayout.clamp(rows, 2),
                "a selection that lands on a refusal slides back to the nearest row that can be taken");
    }

    @Test
    @DisplayName("a row's words keep what they need and its id takes what is left, never overlapping")
    void textRoomGivesTheLabelPriority() {
        // Widths in characters, so the arithmetic is exact: the label is 5, the id is 20.
        java.util.function.ToIntFunction<String> widthOf = String::length;

        ItemPickerLayout.TextRoom wide = ItemPickerLayout.textRoom("Item", "tenet:item", 100, widthOf);
        assertEquals(4, wide.label(), "room to spare, the words are whole");
        assertEquals(10, wide.secondary(), "and so is the id");

        ItemPickerLayout.TextRoom narrow =
                ItemPickerLayout.textRoom("Gather oak logs", "template:built_in/gather_oak_logs", 40, widthOf);
        assertEquals(15, narrow.label(), "the words are whole: the id yields first");
        assertEquals(40 - 15 - ItemPickerLayout.TEXT_GAP, narrow.secondary(),
                "taking exactly what is left");

        ItemPickerLayout.TextRoom squeezed =
                ItemPickerLayout.textRoom("Gather oak logs", "template:built_in/gather_oak_logs", 20, widthOf);
        assertEquals(15, squeezed.label(), "still whole");
        assertEquals(0, squeezed.secondary(),
                "a sliver of an id is noise, so the id goes rather than overlapping");

        ItemPickerLayout.TextRoom tiny = ItemPickerLayout.textRoom("Gather oak logs", "tenet:item", 8, widthOf);
        assertEquals(8, tiny.label(), "past even that, the words truncate to the row itself");
        assertEquals(0, tiny.secondary());

        ItemPickerLayout.TextRoom noId = ItemPickerLayout.textRoom("Item", "", 100, widthOf);
        assertEquals(4, noId.label());
        assertEquals(0, noId.secondary(), "a row with no id has no room for one");
    }
}
