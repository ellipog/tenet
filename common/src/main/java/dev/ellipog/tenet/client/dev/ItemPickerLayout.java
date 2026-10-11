package dev.ellipog.tenet.client.dev;

import dev.ellipog.tenet.client.BookGeometry;

import java.util.ArrayList;
import java.util.List;

/**
 * The item picker's arithmetic: which rows exist, how tall the list is, and where a pointer lands.
 *
 * <h2>One derivation for the drawing and the press</h2>
 *
 * <p>A row drawn at one rectangle and hit at another is a row an author cannot press, and the failure
 * reads as a broken mouse rather than as arithmetic. So the rows are composed here, their heights
 * decided here, and {@code rowRect} is the one answer both the drawing and the hit test read.
 *
 * <p>The list's scroll is the caller's, and it is one object's: every method here takes the offset it
 * is drawing at, and {@link #contentHeight} is what the caller's viewport is told — so the clamp, the
 * grip's travel and the rows' positions all come from the same number rather than from three
 * expressions that agree until one of them is edited. A class that also remembered the scroll would be
 * a second place the screen's state lives.
 */
public final class ItemPickerLayout {

    /** The search box's height, which is a control's, not a row's. */
    public static final int SEARCH_HEIGHT = 18;

    /** A section's heading: shorter than a row, because it is a word and not a thing to press. */
    public static final int HEADING_HEIGHT = 14;

    /** One pickable row. */
    public static final int ROW_HEIGHT = 18;

    /** The air between a row's label and its id, so the two never read as one string. */
    public static final int TEXT_GAP = 6;

    /**
     * The narrowest an id may be drawn at: below this there is room for an ellipsis and little
     * else, and a sliver of an id is noise rather than reference. {@link #textRoom} answers zero
     * instead, and the drawing skips the id entirely.
     */
    public static final int MIN_SECONDARY = 12;

    /**
     * The width the list gives up on its right edge for the scrollbar.
     *
     * <h2>Why the list reserves it rather than the bar drawing over the rows</h2>
     *
     * <p>The hand-rolled thumb this replaces was painted at {@code list().right() - 3}: the last three
     * pixels of every row's own rectangle — the row's right margin, one pixel clear of the id it draws
     * there, and still <b>the row</b>. A bar has to own a column of its own or it cannot be a control:
     * a press on those three pixels belonged to the row, and there was nothing for a drag to start on.
     * So the strip is reserved, and the pattern is the one the pack's own page already used
     * ({@code AssetsLayout.rows}/{@code scrollbar}) and the sidebar reserved before either
     * ({@code BookGeometry.SIDEBAR_SCROLLBAR}). Stating it in the <i>layout</i> is the only place it can
     * be stated and still be true of the row rectangles, the clip, the hit test and the bar at once.
     *
     * <p>Five rather than three: a three-pixel bar at the strip's right edge with two pixels of
     * clearance between it and the last character of a row.
     */
    public static final int SCROLLBAR = 5;

    /** What a row is for. Headings name a section; the others can be pressed. */
    public enum Kind {
        HEADING,
        CLEAR,
        /** An id with no item behind it: the field's own value, or one typed on purpose. Kept, marked. */
        MISSING,
        ITEM,
        /**
         * A file in the pack, picked by {@code TexturePicker}.
         *
         * <p>The same list, one more thing a row can be: the texture picker composes its rows through
         * this class so the two lists share the frame, the heights, the scroll and the hit test -- and
         * the drawing is where the two differ, because a picture is not an item. {@link #pickable}
         * accepts it with every kind that is not a heading.
         */
        TEXTURE,
        /**
         * A registered type, picked by {@code TypePicker}: a task, a reward, a condition.
         *
         * <p>Row for row the item picker's shape — icon, name, the id a file spells at the right — which
         * is the point of it: adding a task reads like adding an item, because it is the same act. The
         * rows carry no stack, so their count is always one.
         */
        TYPE
    }

    /**
     * One row of the picker.
     *
     * <p>{@code id} is empty for a heading; {@code secondary} is the id a file spells, or the note that
     * says why a missing row is missing.
     *
     * <p>{@code count} is the size of the stack this row stands for, and it is a field rather than part
     * of the label because a caller has to be able to <i>use</i> it: the label says "x128 Iron Ore" for a
     * person to read, and a table entry built from that row has to become 128 iron rather than one. It
     * was formatted into the label and nowhere else, so the picker showed a number and then threw it
     * away — while a recipe-viewer drop of the same stack carried it. One is the floor: everything that
     * is not a stack (a heading, a clear, a miss) stands for one thing.
     *
     * <p>{@code note} is the sentence that says why a row **cannot be taken** — a type a table refuses,
     * today — and a row with one is not selectable, not walkable by the arrow keys and not committed by
     * Enter. It is deliberately not the same field as {@code secondary}: a missing item's note explains
     * it and the row is still pickable ("pressing the row that appears keeps it"), while a refusal is a
     * thing the file will not take, and the difference between "this is odd" and "this will not work" is
     * one the picker has to make.
     */
    public record Row(Kind kind, String id, String label, String secondary, int count, String note) {

        public Row {
            count = Math.max(1, count);
            note = note == null ? "" : note;
        }

        /** A row that is not a stack: a heading, a clear row, a missing id, a texture, a type. */
        public static Row of(Kind kind, String id, String label, String secondary) {
            return new Row(kind, id, label, secondary, 1, "");
        }

        /** The same, for a row that also says why it cannot be taken. */
        public static Row blocked(Kind kind, String id, String label, String secondary, String note) {
            return new Row(kind, id, label, secondary, 1, note);
        }
    }

    /**
     * The field as it stands: its id (empty when none), whether that id resolves to an item this
     * build knows, and whether the caller has decided it may be cleared.
     *
     * <p>Passed in rather than derived, because "does an item exist" is the game's question and this
     * class is arithmetic; {@link #NONE} is a field with no value at all.
     */
    public record Current(String id, boolean known, boolean clearable) {

        public static final Current NONE = new Current("", true, false);
    }

    /** Where the search box is, where the list is, and the strip the bar is drawn in. */
    public record Frame(BookGeometry.Rect search, BookGeometry.Rect list, BookGeometry.Rect scrollbar) {

        /**
         * The body split in three: the box across its top, the list under it, and the bar's strip down
         * the list's right edge.
         *
         * <p>No gap between the box and the list, because the box is the first thing in the list rather
         * than a header over it -- a strip of card between a search box and the results it searches is a
         * strip that does nothing.
         */
        public static Frame of(BookGeometry.Rect body) {
            BookGeometry.Rect band = BookGeometry.Rect.at(body.x(), body.y() + SEARCH_HEIGHT,
                    body.width(), Math.max(0, body.height() - SEARCH_HEIGHT));
            return new Frame(
                    BookGeometry.Rect.at(body.x(), body.y(), body.width(), SEARCH_HEIGHT),
                    BookGeometry.Rect.at(band.x(), band.y(), Math.max(0, band.width() - SCROLLBAR),
                            band.height()),
                    BookGeometry.Rect.at(band.right() - SCROLLBAR, band.y(),
                            Math.min(SCROLLBAR, band.width()), band.height()));
        }
    }

    /**
     * The rows a picker shows: the clear row when there is a value to clear, the inventory, and the
     * query's matches.
     *
     * <p>The inventory is shown whatever the query says -- it is the list for an empty box, and while
     * a query is typed it stays where it was rather than jumping under the search results, because a
     * list that moves while you type is a list you lose your place in. An empty picker is no rows at
     * all: the empty state's sentence is the drawing's, and a row pretending to be one would be a row
     * the keyboard can land on.
     */
    public static List<Row> compose(List<ItemPicker.Entry> inventory, List<ItemPicker.Entry> matches,
                                    Current current, String typedCandidate, String query) {
        return compose(inventory, matches, current, typedCandidate, query, null);
    }

    /**
     * The same, with the caller's own words over the results.
     *
     * <p>{@code heading} is what separates the two callers, and not only in wording. The item picker passes
     * null: its results mean something once a query has been typed, and before that what is offered is what
     * the player carries. A search field passes its own heading and shows its list whether or not anything
     * has been typed -- ten rows of what the registry holds is what tells an author what the field can even
     * hold, and an empty box and an empty registry must not look alike.
     */
    public static List<Row> compose(List<ItemPicker.Entry> inventory, List<ItemPicker.Entry> matches,
                                    Current current, String typedCandidate, String query, String heading) {
        List<Row> rows = new ArrayList<>();
        if (current.clearable() && !current.id().isEmpty()) {
            // The row the caller has already decided is legal -- it is only ever offered for the
            // quest's icon, where "clear" means the optional field goes and the default applies.
            rows.add(Row.of(Kind.CLEAR, "", Labels.of("tenet.dev.picker.clear"),
                    Labels.of("tenet.dev.picker.clear_detail")));
        }
        if (!current.id().isEmpty() && !current.known()) {
            // The field's own value when the build cannot resolve it: what a mod that went away looks
            // like. Shown so it is never silent, and pickable so pressing it keeps it.
            rows.add(Row.of(Kind.MISSING, current.id(), current.id(),
                    Labels.of("tenet.dev.picker.missing")));
        }
        boolean results = !matches.isEmpty() || typedCandidate != null;
        boolean show = heading != null ? results
                : results && query != null && !query.isBlank();
        // The results above the inventory, because a query is about the whole registry and the list
        // someone is reading is the answer -- what they carry is the fallback, not the headline.
        if (show) {
            rows.add(heading(heading == null ? Labels.of("tenet.dev.picker.all_items") : heading));
            if (typedCandidate != null) {
                rows.add(Row.of(Kind.MISSING, typedCandidate, typedCandidate,
                        Labels.of("tenet.dev.picker.not_installed")));
            }
            for (ItemPicker.Entry entry : matches) {
                rows.add(item(entry));
            }
        }
        if (!inventory.isEmpty()) {
            rows.add(heading(Labels.of("tenet.dev.picker.inventory")));
            for (ItemPicker.Entry entry : inventory) {
                rows.add(item(entry));
            }
        }
        return List.copyOf(rows);
    }

    /** How tall one row is. */
    public static int heightOf(Row row) {
        return row.kind() == Kind.HEADING ? HEADING_HEIGHT : ROW_HEIGHT;
    }

    /**
     * How tall the whole list is, headings and all.
     *
     * <p>The one number a caller has to state for the list to scroll: the viewport it hands this to
     * clamps the offset from it, and the bar draws its grip as the visible fraction of it. There used to
     * be a {@code maxScroll} beside it — {@code max(0, contentHeight - band)} — and it was a second
     * derivation of the same fact, kept alive by the two callers that clamped an offset by hand. Both of
     * those are the viewport's now, so the band is subtracted in the one place that knows it.
     */
    public static int contentHeight(List<Row> rows) {
        int total = 0;
        for (Row row : rows) {
            total += heightOf(row);
        }
        return total;
    }

    /**
     * One row's rectangle on screen, at this scroll offset.
     *
     * <p>Not clipped to the list: a row half past the edge is the clip's business, and the drawing
     * already holds one. This answers where the row <i>is</i>.
     */
    public static BookGeometry.Rect rowRect(List<Row> rows, Frame frame, int scroll, int index) {
        int y = frame.list().y() - scroll;
        for (int i = 0; i < index; i++) {
            y += heightOf(rows.get(i));
        }
        return BookGeometry.Rect.at(frame.list().x(), y, frame.list().width(), heightOf(rows.get(index)));
    }

    /** The row a pointer at this y is over, or -1 -- headings included, because they are drawn. */
    public static int rowAt(List<Row> rows, Frame frame, int scroll, double y) {
        if (y < frame.list().y() || y >= frame.list().bottom()) {
            return -1;
        }
        for (int i = 0; i < rows.size(); i++) {
            BookGeometry.Rect rect = rowRect(rows, frame, scroll, i);
            if (y >= rect.y() && y < rect.bottom()) {
                return i;
            }
        }
        return -1;
    }

    /**
     * Whether a row can be selected or pressed. A heading is not, and neither is a row that says why it
     * cannot be taken — the refusal is the validator's, and offering a row that the save will reject is
     * how an author ends up with a row in the panel that never reaches the file.
     */
    public static boolean pickable(Row row) {
        return row.kind() != Kind.HEADING && row.note().isEmpty();
    }

    /** How wide each half of a row's text may be, in pixels: the label's room, then the id's. */
    public record TextRoom(int label, int secondary) {
    }

    /**
     * How a row shares its text zone between its label and its id.
     *
     * <p>The label has priority: it keeps up to its whole width, truncated only by the row itself,
     * and the id takes what is left, truncated to that. The old drawing reserved a fixed thirty
     * pixels for the id and drew it whole whatever fit — so a narrow panel wrote the two strings
     * over each other, and the fix belongs here rather than at the call site because every picker
     * page draws through the one row renderer.
     *
     * @param label     the row's words, or empty
     * @param secondary the row's id, or empty for a row with nothing at its right end
     * @param width     the text zone's width, already less the icon and the row's own insets
     * @param widthOf   how wide a string draws; the drawing passes its own measure, a test passes
     *                  characters
     * @return the two budgets, each already clamped to what fits; a secondary under
     *         {@link #MIN_SECONDARY} is answered as zero, because a sliver of an id is noise
     */
    public static TextRoom textRoom(String label, String secondary, int width,
                                    java.util.function.ToIntFunction<String> widthOf) {
        int room = Math.max(0, width);
        String words = label == null ? "" : label;
        int labelBudget = Math.min(widthOf.applyAsInt(words), room);
        int rest = room - labelBudget;
        int secondaryBudget = 0;
        if (secondary != null && !secondary.isEmpty() && rest >= TEXT_GAP + MIN_SECONDARY) {
            secondaryBudget = Math.min(widthOf.applyAsInt(secondary), rest - TEXT_GAP);
        }
        return new TextRoom(labelBudget, secondaryBudget);
    }

    /** The first pickable row, or -1 when there is none. */
    public static int firstPickable(List<Row> rows) {
        for (int i = 0; i < rows.size(); i++) {
            if (pickable(rows.get(i))) {
                return i;
            }
        }
        return -1;
    }

    /**
     * A valid selection near {@code index}: the nearest pickable row at or before it, else the first
     * after it, else -1.
     *
     * <p>Asked after the list has changed size -- a query keystroke rebuilds the rows, and an index
     * that named the seventh match cannot go on naming it when there are two. Landing on the nearest
     * row rather than on the first is what stops the selection jumping to the top while typing.
     */
    public static int clamp(List<Row> rows, int index) {
        if (rows.isEmpty()) {
            return -1;
        }
        int at = Math.max(0, Math.min(rows.size() - 1, index));
        for (int i = at; i >= 0; i--) {
            if (pickable(rows.get(i))) {
                return i;
            }
        }
        for (int i = at + 1; i < rows.size(); i++) {
            if (pickable(rows.get(i))) {
                return i;
            }
        }
        return -1;
    }

    /** The next pickable row in the given direction, or the selection itself at the ends. */
    public static int step(List<Row> rows, int from, int delta) {
        int at = clamp(rows, from);
        if (at < 0 || delta == 0) {
            return at;
        }
        for (int i = at + delta; i >= 0 && i < rows.size(); i += delta) {
            if (pickable(rows.get(i))) {
                return i;
            }
        }
        return at;
    }

    private static Row heading(String label) {
        return Row.of(Kind.HEADING, "", label, "");
    }

    private static Row item(ItemPicker.Entry entry) {
        // The name is the row and the id is the small print, which is the other way round from the
        // first version and the fix for a real report: a name search matched but every row showed an
        // id, so the match looked like it had not happened. A count rides in front of the name,
        // because it is the one thing about a carried stack that is not already in the id.
        String name = entry.label() == null || entry.label().isBlank() ? entry.id() : entry.label();
        String counted = entry.count() > 1 ? "x" + entry.count() + " " + name : name;
        // And the count travels as well as being printed: whatever this row is used to build takes the
        // stack's size, so the number on screen is the number that lands in the file.
        return new Row(Kind.ITEM, entry.id(), counted, entry.id(), entry.count(), "");
    }

    private ItemPickerLayout() {
    }
}
