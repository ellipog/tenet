package dev.ellipog.tenet.client.dev;

import dev.ellipog.tenet.client.BookGeometry;
import dev.ellipog.tenet.client.dev.ItemPickerLayout.Kind;
import dev.ellipog.tenet.client.dev.ItemPickerLayout.Row;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The type picker's catalogue: what a blank box shows, what a query shows, and what a refusal does.
 *
 * <p>Game-free, because all of it is arithmetic and words: which group a type is listed under, the order
 * two matches come in, whether a row can be taken. The screen supplies the entries — the registries, and
 * whether a table will accept each one — and this decides everything a person could disagree with, which
 * is the same split {@link ItemPickerTest} makes for items.
 */
@DisplayName("The type picker's catalogue")
class TypePickerTest {

    private static TypePicker.Entry entry(String id, String label, String group) {
        return new TypePicker.Entry(id, label, group);
    }

    private static TypePicker.Entry refused(String id, String label, String group, String why) {
        return new TypePicker.Entry(id, label, group, why);
    }

    /** Two groups, as the task table has them. */
    private static List<TypePicker.Entry> catalogue() {
        return List.of(
                entry("tenet:item", "Item", "Items"),
                entry("tenet:kill", "Kill", "Items"),
                entry("tenet:stage", "Stage", "Progress"),
                entry("mymod:ritual", "mymod:ritual", "More"));
    }

    @Test
    @DisplayName("a blank box lists the whole catalogue, in order, under its groups")
    void blankBoxListsEverything() {
        List<Row> rows = TypePicker.compose(catalogue(), "", null);

        assertEquals(List.of(Kind.HEADING, Kind.TYPE, Kind.TYPE, Kind.HEADING, Kind.TYPE, Kind.HEADING,
                        Kind.TYPE),
                rows.stream().map(Row::kind).toList(), "headings and rows, in the catalogue's own order");
        assertEquals(List.of("Items", "Progress", "More"),
                rows.stream().filter(row -> row.kind() == Kind.HEADING).map(Row::label).toList());
        assertEquals(List.of("tenet:item", "tenet:kill", "tenet:stage", "mymod:ritual"),
                rows.stream().filter(row -> row.kind() == Kind.TYPE).map(Row::id).toList());
        assertTrue(rows.stream().allMatch(row -> ItemPickerLayout.pickable(row)
                        || row.kind() == Kind.HEADING),
                "nothing in this catalogue is refused");
    }

    @Test
    @DisplayName("a heading is the page's own line, and is absent when the chrome already says it")
    void theHeadingIsTheCallers() {
        List<Row> withHeading = TypePicker.compose(catalogue(), "", "Add a task");
        assertEquals("Add a task", withHeading.get(0).label());
        assertEquals(Kind.HEADING, withHeading.get(0).kind());

        List<Row> withoutHeading = TypePicker.compose(catalogue(), "", null);
        assertEquals("Items", withoutHeading.get(0).label(),
                "the table editor's strip names the page, so the list starts at its first group");
    }

    @Test
    @DisplayName("a query ranks id before name, drops the headings, and caps what it offers")
    void aQueryRanksAndCaps() {
        List<TypePicker.Entry> wide = new java.util.ArrayList<>();
        for (int i = 0; i < ItemPicker.LIMIT + 20; i++) {
            wide.add(entry("tenet:thing_" + i, "Thing " + i, "Things"));
        }
        wide.add(entry("tenet:thing", "Thing exact", "Things"));

        List<Row> rows = TypePicker.compose(wide, "tenet:thing", null);

        assertEquals(ItemPicker.LIMIT, rows.size(), "a one-word query is capped, best first");
        assertEquals("tenet:thing", rows.get(0).id(), "an exact id is the first answer");
        assertTrue(rows.stream().noneMatch(row -> row.kind() == Kind.HEADING),
                "matches are answers, not a page: the group headings would only break the ranking up");

        List<Row> byName = TypePicker.compose(catalogue(), "stage", null);
        assertEquals(List.of("tenet:stage"), byName.stream().map(Row::id).toList(),
                "the display name is matched as well as the id");
        assertTrue(TypePicker.compose(catalogue(), "nothing here", null).isEmpty(),
                "and a query that matches nothing is an empty list, not the catalogue");
    }

    @Test
    @DisplayName("a trailing group keeps its heading last, and a row can spell its own right end")
    void trailingGroupAndOwnSpelling() {
        // The shelf the screen appends: templates below the catalogue, showing the type a file
        // would spell rather than the shelf's own row id.
        List<TypePicker.Entry> entries = new java.util.ArrayList<>(catalogue());
        entries.add(new TypePicker.Entry("template:built_in/gather_oak_logs", "Gather oak logs",
                "Templates", "", "tenet:item"));

        List<Row> rows = TypePicker.compose(entries, "", null);
        assertEquals("Templates",
                rows.stream().filter(row -> row.kind() == Kind.HEADING).map(Row::label).toList()
                        .getLast(),
                "the appended group closes the page rather than opening it");
        Row template = rows.stream().filter(row -> row.id().startsWith("template:")).findFirst()
                .orElseThrow();
        assertEquals("Gather oak logs", template.label());
        assertEquals("tenet:item", template.secondary(),
                "the file's spelling, not the row's own id");
        assertEquals("tenet:item", rows.stream().filter(row -> "tenet:item".equals(row.id())).findFirst()
                .orElseThrow().secondary(), "while an ordinary row still shows its own id");
    }

    @Test
    @DisplayName("a refused type is offered, and cannot be taken")
    void aRefusalIsOfferedAndBlocked() {
        List<TypePicker.Entry> entries = List.of(
                entry("tenet:item", "Item", "Items"),
                refused("tenet:choice", "Choice", "Items", "a table cannot hold a choice"));

        for (String query : List.of("", "choice")) {
            List<Row> rows = TypePicker.compose(entries, query, "Add a reward");
            Row blocked = rows.stream().filter(row -> "tenet:choice".equals(row.id())).findFirst()
                    .orElseThrow();

            assertEquals("a table cannot hold a choice", blocked.note(),
                    "the validator's own sentence travels with the row, at query or not");
            assertFalse(ItemPickerLayout.pickable(blocked),
                    "a row the save will reject is not a row that can be pressed");
        }

        // And it does not take the catalogue with it: the same list without the refusal is untouched, and
        // a blank box still has rows to press.
        List<Row> whole = TypePicker.compose(entries, "", null);
        assertTrue(whole.stream().anyMatch(ItemPickerLayout::pickable),
                "one refused type does not empty the page");

        // The keyboard cannot land on it either, which is the half a press test would miss.
        assertEquals(ItemPickerLayout.step(whole, 0, 1), ItemPickerLayout.step(whole, 0, 1));
        assertNotEquals(2, ItemPickerLayout.firstPickable(List.of(whole.get(1), whole.get(2))),
                "a list whose only row is refused has nothing to select");
    }

    @Test
    @DisplayName("every row is the list's own width, and a press lands on the row drawn where it pressed")
    void rowsAreWhereTheyAreDrawn() {
        BookGeometry.Rect band = BookGeometry.Rect.at(20, 30, 200, 120);
        ItemPickerLayout.Frame frame = ItemPickerLayout.Frame.of(band);
        List<Row> rows = TypePicker.compose(catalogue(), "", "Add a task");

        assertTrue(rows.size() > 3, "the fixture is a list worth scrolling");
        for (int i = 0; i < rows.size(); i++) {
            BookGeometry.Rect rect = ItemPickerLayout.rowRect(rows, frame, 0, i);
            // The column is the list's, every row; the *rows* may run past the band, which is what the
            // scroll is for -- and a row wholly past it is skipped by both the drawing and the press.
            assertEquals(frame.list().x(), rect.x(), "row " + i + " is not in the list's column");
            assertEquals(frame.list().width(), rect.width(), "row " + i + " is not the list's width");
            double middle = rect.y() + rect.height() / 2.0;
            if (middle < frame.list().y() || middle >= frame.list().bottom()) {
                // A row half past the band is half drawn and its middle is not in the list, so the press
                // cannot answer for it: that is the clip's business, and the scroll brings it back.
                continue;
            }
            assertEquals(i, ItemPickerLayout.rowAt(rows, frame, 0, middle),
                    "row " + i + " answers for the point it is drawn at");
        }

        // And the list is what scrolls: the last row, brought into the band, answers at its new place.
        int scroll = Math.max(0, ItemPickerLayout.contentHeight(rows) - frame.list().height());
        assertTrue(scroll > 0, "the fixture should overflow, or the scrolled case proves nothing");
        BookGeometry.Rect last = ItemPickerLayout.rowRect(rows, frame, scroll, rows.size() - 1);
        assertTrue(last.bottom() <= frame.list().bottom(), "scrolled to the end, the last row is visible");
        assertEquals(rows.size() - 1,
                ItemPickerLayout.rowAt(rows, frame, scroll, last.y() + last.height() / 2.0));
        assertEquals(-1, ItemPickerLayout.rowAt(rows, frame, 0, frame.list().bottom() + 5),
                "a point past the band is not a row");
    }
}
