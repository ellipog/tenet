package dev.ellipog.tenet.client.dev;

import dev.ellipog.tenet.client.BookGeometry;
import dev.ellipog.tenet.quest.loot.RewardTable;
import dev.ellipog.tenet.quest.reward.TableReward;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * The table editor's arithmetic: the breadcrumb, the header, the toolbar, the rows and the footer.
 *
 * <h2>What lives here and what does not</h2>
 *
 * <p>Every rectangle, and the two rules that are easy to get wrong: where a pointer lands in the entry
 * list (including a drag from a recipe viewer, which is the same question), and how a chance is written.
 * The drawing and the presses both ask here, so a control cannot be drawn in one place and pressed in
 * another — the house rule, and the one that keeps a scrolled list's hit test honest.
 *
 * <p>Game-free: a chance is formatted here rather than in the screen, because "20.0% (per roll)" and
 * "48.8% at least once" are arithmetic and a wrong one is a wrong drop rate.
 */
public final class TableEditorLayout {

    /** The breadcrumb strip, one line of ancestors. */
    public static final int CRUMB_HEIGHT = 12;

    /**
     * The header's two lines: the icon, title and id; then the stepper, the numbers and the mode.
     *
     * <p>Forty rather than the thirty-six it was: the third line the first version drew — the rolls
     * stepper at y+30 in a 36-pixel band — ran straight into the toolbar under it, which is what the
     * screenshot showed as a stepper sitting on top of "Add item".
     */
    public static final int HEADER_HEIGHT = 40;

    /** The toolbar: Add item, Import, Test roll, Undo. */
    public static final int TOOLBAR_HEIGHT = 20;

    /** One entry's row. */
    public static final int ROW_HEIGHT = 20;

    /**
     * How far up from the card's bottom the panels' own controls must stop.
     *
     * <p>The footer is the card's — {@code BookGeometry.overlayControls} places Done and the conversions
     * at the same height as every other card's controls, and the body ends above the band they sit in.
     * This is the number the list's bottom is derived from, so a row can never be drawn under a button.
     */
    public static final int FOOTER_RESERVE = dev.ellipog.tenet.client.BookGeometry.MODAL_FOOTER_HEIGHT;

    /** The card's own title strip, above the rule that closes it. */
    public static final int CARD_HEADER = 28;

    /** The rule under the title strip. */
    public static final int CARD_RULE = 2;

    /** The air between that rule and the body, so the first line is not touching the strip. */
    public static final int CARD_GAP = 10;

    /**
     * The card's body: inset, below the title strip and the air under it, above the footer band.
     *
     * <p>One derivation for both table panels, and here rather than in the screen so a test can pin it:
     * the footer is drawn from {@code overlayControls}, which places Back and the submit slot inside the
     * band this subtracts, so a body that did not stop where that band starts would draw a row under a
     * button -- and the button would still take the press.
     */
    public static BookGeometry.Rect body(BookGeometry.Rect card) {
        int top = card.y() + CARD_HEADER + CARD_RULE + CARD_GAP;
        int bottom = card.bottom() - FOOTER_RESERVE;
        return BookGeometry.Rect.at(card.x() + dev.ellipog.tenet.client.BookGeometry.MODAL_INSET, top,
                card.width() - 2 * dev.ellipog.tenet.client.BookGeometry.MODAL_INSET,
                Math.max(0, bottom - top));
    }

    /** A stepper's two chips, and the value between them. */
    public static final int STEPPER_BUTTON = 12;
    public static final int STEPPER_VALUE = 40;

    /** The fold triangle, and the cross that removes a row. */
    public static final int FOLD_WIDTH = 12;
    public static final int REMOVE_WIDTH = 14;

    /** Between two controls. */
    public static final int GAP = 4;

    /** The icon box on a row. */
    public static final int ICON = 16;

    /**
     * The heading strip at the top of the list: what the columns are.
     *
     * <p>Inside the list's own band rather than a fourth frame band, so the frame's arithmetic stays the
     * three lines it was and the headings scroll with nothing -- they are the first thing in the list.
     */
    public static final int HEADING_HEIGHT = 12;

    /**
     * One line of a fold's band: the same sixteen pixels a card's entry line is.
     *
     * <p>The fold used to be one fixed band of eighteen pixels, which was the entry's whole form when
     * every entry was an item and a count. It is not: an item has four fields, a command has three, and
     * the three base mechanics every reward carries — {@code auto}, {@code Claim separately},
     * {@code Ignore blocking} — are a line of their own. A fixed band either clips the controls or
     * leaves a gap, and which of the two it does depends on the entry's type.
     */
    public static final int FOLD_LINE_HEIGHT = 16;

    /** The air above and below a fold's lines, inside the row. */
    public static final int FOLD_PAD = 2;

    /**
     * The width the list gives up on its right edge for the scrollbar.
     *
     * <p>Reserved here rather than drawn over the rows, for the reason the browser's own constant
     * states: this list's rows carry a fold triangle, an icon, four fields and a remove cross, and the
     * last of those is at the row's right edge. The headings and the rows both derive from the
     * rectangle this narrows, so the bar and the content cannot end up in the same pixels.
     */
    public static final int SCROLLBAR = 5;

    /**
     * How much taller a folded row is, for one line of its fields.
     *
     * <p>Kept as the one-line case of {@link #foldHeight}, because eighteen was the number the fixed
     * band had and a test asserting the commonest fold is unchanged is worth more than deleting the
     * name. Part of the row's height, and that is the point: the fold used to be drawn at {@code
     * rect.bottom()} with every row a fixed twenty, so an open entry painted over the row below it and
     * the row below lost the presses its own controls should have taken — the overlap was not just
     * cosmetic, it moved the hit test. A row that grows instead pushes its neighbours down, which is what
     * the author expects to see.
     */
    public static final int FOLD_HEIGHT = FOLD_LINE_HEIGHT + FOLD_PAD;

    /**
     * How tall a fold's band is, for the lines it draws.
     *
     * <p>One line comes to eighteen, which is what the fixed band was — so the commonest entry, an item
     * and a count, is the same height it has always been, and the arithmetic is now a derivation rather
     * than a number that happened to suit one type.
     *
     * <p><b>No lines is no band, and that is a case rather than a formality.</b> Every closed row asks
     * this with zero — {@link Folds#linesOf} answers zero for an entry that is not open — so a version
     * that added the padding unconditionally made every row in the list two pixels taller than a row has
     * ever been, which is a list whose scroll extent and whose hit test are both subtly wrong.
     */
    public static int foldHeight(int lines) {
        return lines <= 0 ? 0 : lines * FOLD_LINE_HEIGHT + FOLD_PAD;
    }

    /** Where everything is, from the card's body. The footer belongs to the card, not to this. */
    public record Frame(BookGeometry.Rect crumb, BookGeometry.Rect header, BookGeometry.Rect toolbar,
                        BookGeometry.Rect list, BookGeometry.Rect scrollbar) {

        public static Frame of(BookGeometry.Rect body) {
            int y = body.y();
            BookGeometry.Rect crumb = BookGeometry.Rect.at(body.x(), y, body.width(), CRUMB_HEIGHT);
            y += CRUMB_HEIGHT;
            BookGeometry.Rect header = BookGeometry.Rect.at(body.x(), y, body.width(), HEADER_HEIGHT);
            y += HEADER_HEIGHT;
            BookGeometry.Rect toolbar = BookGeometry.Rect.at(body.x(), y, body.width(), TOOLBAR_HEIGHT);
            y += TOOLBAR_HEIGHT;
            int listHeight = Math.max(0, body.height() - CRUMB_HEIGHT - HEADER_HEIGHT - TOOLBAR_HEIGHT);
            BookGeometry.Rect band = BookGeometry.Rect.at(body.x(), y, body.width(), listHeight);
            BookGeometry.Rect list = BookGeometry.Rect.at(band.x(), band.y(),
                    Math.max(0, band.width() - SCROLLBAR), band.height());
            // The strip covers the rows and not the headings, because the headings are the first thing in
            // the list and do not scroll: a bar starting at the list's top would claim a range that
            // includes twelve pixels nothing can scroll.
            int rowsTop = band.y() + Math.min(HEADING_HEIGHT, band.height());
            return new Frame(crumb, header, toolbar, list,
                    BookGeometry.Rect.at(band.right() - SCROLLBAR, rowsTop,
                            Math.min(SCROLLBAR, band.width()), Math.max(0, band.bottom() - rowsTop)));
        }

        /** The column headings: the list's first strip, above the first row. */
        public BookGeometry.Rect headings() {
            return BookGeometry.Rect.at(list.x(), list.y(), list.width(),
                    Math.min(HEADING_HEIGHT, list.height()));
        }

        /** Where the rows start: under the headings, and where the scroll's zero is. */
        public int rowsTop() {
            return list.y() + Math.min(HEADING_HEIGHT, list.height());
        }

        /** The band the rows are drawn in and clipped to. */
        public BookGeometry.Rect rows() {
            return BookGeometry.Rect.at(list.x(), rowsTop(), list.width(),
                    Math.max(0, list.bottom() - rowsTop()));
        }
    }

    /** One stepper: two chips and the value between them. The header's rolls control is one. */
    public record Stepper(BookGeometry.Rect minus, BookGeometry.Rect value, BookGeometry.Rect plus) {
    }

    /** A stepper at a point, sized by this class's own numbers. */
    public static Stepper stepper(int x, int y) {
        BookGeometry.Rect minus = BookGeometry.Rect.at(x, y, STEPPER_BUTTON, 12);
        BookGeometry.Rect value = BookGeometry.Rect.at(minus.right() + 2, y, STEPPER_VALUE, 12);
        BookGeometry.Rect plus = BookGeometry.Rect.at(value.right() + 2, y, STEPPER_BUTTON, 12);
        return new Stepper(minus, value, plus);
    }

    /**
     * A stepper inside a fold's cell: the same three parts, fitted to the width it was given.
     *
     * <p>Fitted rather than fixed, and this is the one control that has to be: the header's stepper knows
     * its own width, while a fold's cell is an equal column of whatever the card is — so the value box
     * takes what is left after the two chips, and a narrow cell shrinks the chips rather than pushing the
     * `+` out of the row. A fixed width here drew the `+` past the cell's right edge on a narrow window,
     * over the control beside it.
     */
    public static Stepper foldStepper(BookGeometry.Rect cell) {
        int button = Math.min(STEPPER_BUTTON, Math.max(6, cell.width() / 5));
        int value = Math.max(0, cell.width() - 2 * button - 2 * GAP);
        BookGeometry.Rect minus = BookGeometry.Rect.at(cell.x(), cell.y(), button, cell.height());
        BookGeometry.Rect box = BookGeometry.Rect.at(minus.right() + GAP, cell.y(), value, cell.height());
        BookGeometry.Rect plus = BookGeometry.Rect.at(box.right() + GAP, cell.y(), button, cell.height());
        return new Stepper(minus, box, plus);
    }

    /** One character's width, assumed, for a label this class has to size without a font. */
    public static final int LABEL_CHAR_WIDTH = 6;

    /**
     * What is open above the entry list.
     *
     * <h2>Why one value rather than a flag per page</h2>
     *
     * <p>The way-out decision took a boolean per page kind — "is the picker open", "is a table on the
     * stack" — and a boolean that nobody passes is a page that cannot be left. That is exactly what
     * happened to the roll report: {@code exit(browser, pickerOpen, nested)} had no parameter for it, so
     * with the report on screen the footer said "Done" and closed the whole panel, jumping two steps from
     * where the author was. A value with a case per page cannot be forgotten by a caller that has to name
     * it, and the switch below then fails to compile when a page is added.
     */
    public enum TablePage {
        /** The entries themselves: the editor's own page. */
        LIST,
        /** A page of types over the list. */
        PICKER,
        /** The test roll's report over the list. */
        ROLL
    }

    /**
     * What the table panels' way-out button does — and therefore what it says.
     *
     * <h2>The rule, in one line</h2>
     *
     * <p><b>It undoes one step.</b> Whatever is on top — a page of types, the roll report, a nested table,
     * the browser itself — one press leaves that and nothing else; "Done" is kept for the one press that
     * really does end the session in this editor, which is the last step and no more.
     *
     * <p>The label is the screen's (the two words are translated), so what is decided here is only the act.
     */
    public enum Exit {
        /** Out of the browser, which is a panel rather than a page of this one. */
        LEAVE_BROWSER,
        /** Off the picker page, back to the list under it — the panel stays open. */
        LEAVE_PICKER,
        /** Off the roll report, back to the list under it. */
        LEAVE_ROLL,
        /** One table up the stack the author walked into. */
        TABLE_UP,
        /** Out of the editor entirely. */
        CLOSE
    }

    /**
     * Which of the five the way-out button does: the innermost thing on screen first.
     *
     * @param browser the browser is showing, whose way out is the panel itself
     * @param page    what is open above the entry list
     * @param nested  a table is on the stack, so the editor has somewhere to go back to
     */
    public static Exit exit(boolean browser, TablePage page, boolean nested) {
        if (browser) {
            return Exit.LEAVE_BROWSER;
        }
        return switch (page) {
            case PICKER -> Exit.LEAVE_PICKER;
            case ROLL -> Exit.LEAVE_ROLL;
            case LIST -> nested ? Exit.TABLE_UP : Exit.CLOSE;
        };
    }

    /**
     * A folded number's four parts: its label, and the stepper beside it — all inside the cell.
     *
     * <h2>Why the label is here rather than in the drawing</h2>
     *
     * <p>A number's label is a rectangle like any other, and leaving it to the screen is what put it
     * outside the cell: the stepper is stretched to fill whatever it is given, so a label drawn after
     * it — at {@code plus.right() + 4} — began at the cell's own right edge and ran over the control in
     * the next column. On an item's fold that is "Count" printed across the "Extra" stepper's `-`.
     *
     * <p>The label is a guess at its width ({@code LABEL_CHAR_WIDTH} a character, the same guess
     * {@code EntryFormLayout} makes for the card's label column) because this class draws nothing and
     * cannot ask a font. A guess that is generous is air; a guess that is short is a truncated label, so
     * the label is also capped at what is left after a stepper's worth of chips — a label may not take
     * the room its own control needs.
     */
    public record FoldNumber(BookGeometry.Rect label, BookGeometry.Rect minus,
                             BookGeometry.Rect value, BookGeometry.Rect plus) {
    }

    public static FoldNumber foldNumber(BookGeometry.Rect cell, int labelChars) {
        int chips = 3 * STEPPER_BUTTON + 2 * GAP;
        int labelWidth = Math.min(Math.max(0, cell.width() - chips),
                Math.max(0, labelChars) * LABEL_CHAR_WIDTH + GAP);
        BookGeometry.Rect label = BookGeometry.Rect.at(cell.x(), cell.y(), labelWidth, cell.height());
        BookGeometry.Rect rest = BookGeometry.Rect.at(label.right() + GAP, cell.y(),
                Math.max(0, cell.right() - label.right() - GAP), cell.height());
        Stepper stepper = foldStepper(rest);
        return new FoldNumber(label, stepper.minus(), stepper.value(), stepper.plus());
    }

    /**
     * The mode as a chip reads it: a word, not an enum constant.
     *
     * <p>{@code all_table} is the reason this exists — it is the type's id, and a chip that says
     * "all_table" is the file's spelling leaking into a control.
     */
    public static String modeLabel(TableReward.Mode mode) {
        return switch (mode) {
            case RANDOM -> "Random";
            case LOOT -> "Loot";
            case ALL_TABLE -> "All once";
            case CHOICE -> "Choice";
        };
    }

    /** What the mode means for the chances beside it, for the chip's hover. */
    public static String modeHint(TableReward.Mode mode) {
        return switch (mode) {
            case RANDOM -> "One roll over the weights - every grant pays something";
            case LOOT -> "One roll over the weights and the empty band - a grant can pay nothing";
            case ALL_TABLE -> "Every entry, once - the weights are not used";
            case CHOICE -> "The player picks one entry - the weights are not used";
        };
    }

    /**
     * The folds as they stand: which entries are open, and how many lines each open one draws.
     *
     * <h2>Why one value rather than two arguments</h2>
     *
     * <p>Every fold-aware function here needs the same two facts, and passing them separately made the
     * drift possible: a call site could hand over a set that says entry 3 is open and a line count that
     * says nothing is, and the row's height and its drawing would then disagree about a rectangle they
     * both derive — the class of fault this file exists to prevent. Here {@link #linesOf} answers zero
     * for anything that is not open, so "open with no lines" is not a state a caller can express.
     *
     * <p>{@code lines} is asked only about entries the caller says are open, so a screen can compute the
     * line count from the entry itself at the moment it is asked.
     */
    public record Folds(java.util.Set<Integer> open, java.util.function.IntUnaryOperator lines) {

        public Folds {
            open = java.util.Set.copyOf(open);
        }

        /** No folds, for a caller that has none and for a test that does not care. */
        public static Folds none() {
            return new Folds(java.util.Set.of(), index -> 0);
        }

        /** The same folds, over a screen's own set and its own line count. */
        public static Folds of(java.util.Set<Integer> open,
                               java.util.function.IntUnaryOperator lines) {
            return new Folds(open, lines);
        }

        public boolean isOpen(int index) {
            return open.contains(index);
        }

        /** The lines this entry's fold draws: zero unless it is open, whatever {@code lines} says. */
        public int linesOf(int index) {
            return isOpen(index) ? Math.max(0, lines.applyAsInt(index)) : 0;
        }
    }

    /** How tall one row is: its own line, plus the fold's band while it is open. */
    public static int heightOf(int index, Folds folds) {
        return ROW_HEIGHT + foldHeight(folds == null ? 0 : folds.linesOf(index));
    }

    /** How tall the whole entry list is, folds and all. What the list's viewport is told. */
    public static int contentHeight(int entries, Folds folds) {
        int total = 0;
        for (int i = 0; i < Math.max(0, entries); i++) {
            total += heightOf(i, folds);
        }
        return total;
    }

    /**
     * One entry's rectangle, at this scroll offset.
     *
     * <p>A folded row is taller and its neighbours start below it: the rectangle a row is drawn at and
     * the one it is hit at are this one answer, which is what stops a fold from stealing the presses of
     * the row it used to be drawn over.
     */
    public static BookGeometry.Rect rowRect(int entries, Frame frame, int scroll, int index,
                                            Folds folds) {
        int y = frame.rowsTop() - scroll;
        for (int i = 0; i < index; i++) {
            y += heightOf(i, folds);
        }
        return BookGeometry.Rect.at(frame.list().x(), y, frame.list().width(),
                heightOf(index, folds));
    }

    /**
     * One line of a row's fold, as a rectangle inside the row.
     *
     * <p>The band's lines are stacked under the row's own line, which is what makes a fold's drawing and
     * any press inside it one derivation. A line past the end of the band is clamped to zero height, so
     * a caller that asks for one line too many draws nothing rather than over its neighbour.
     */
    public static BookGeometry.Rect foldBand(BookGeometry.Rect rect, int line) {
        int line_ = Math.min(ROW_HEIGHT, rect.height());
        int top = rect.y() + line_ + FOLD_PAD / 2 + Math.max(0, line) * FOLD_LINE_HEIGHT;
        int room = Math.max(0, rect.bottom() - top);
        return BookGeometry.Rect.at(rect.x(), top, rect.width(),
                Math.min(FOLD_LINE_HEIGHT, room));
    }

    /**
     * One line of a fold, split into {@code columns} equal cells.
     *
     * <p>Equal columns rather than "as many as fit", for the reason {@code EntryFormLayout} gives: a form
     * whose shape depends on the width of the card is a form an author has to re-read every time the
     * window changes. The cells are laid out through the same arithmetic for the drawing and the press.
     */
    public static java.util.List<BookGeometry.Rect> foldCells(BookGeometry.Rect band, int columns) {
        int count = Math.max(1, columns);
        int each = Math.max(0, (band.width() - (count - 1) * GAP) / count);
        java.util.List<BookGeometry.Rect> cells = new java.util.ArrayList<>(count);
        for (int c = 0; c < count; c++) {
            cells.add(BookGeometry.Rect.at(band.x() + c * (each + GAP), band.y(),
                    c == count - 1 ? Math.max(0, band.right() - (band.x() + c * (each + GAP))) : each,
                    band.height()));
        }
        return java.util.List.copyOf(cells);
    }

    /**
     * Where a drop or a press on the list lands: an entry index, or -1 for nothing.
     *
     * <p>The bounds check is the <b>list</b>, not the rows: a pointer below the last row of a short table
     * is still inside the list, and answering -1 there would make dropping into an empty table
     * impossible — which is the state a table is in when an author most wants to drop something on it.
     * So: outside the list, nothing; inside it with no entries, the first position; otherwise the row
     * under the pointer. The heading strip counts as "before the first row", because a drag held over the
     * headings is a drag aimed at the top of the list.
     */
    public static int dropIndexAt(int entries, Frame frame, int scroll, double x, double y,
                                  Folds folds) {
        if (x < frame.list().x() || x >= frame.list().right()
                || y < frame.list().y() || y >= frame.list().bottom()) {
            return -1;
        }
        if (entries <= 0) {
            return 0;
        }
        int at = frame.rowsTop() - scroll;
        for (int i = 0; i < entries; i++) {
            int height = heightOf(i, folds);
            if (y < at + height) {
                return i;
            }
            at += height;
        }
        return entries;
    }

    /** One row's controls: the badge, the name, the stepper, the fold and the cross. */
    public record Row(BookGeometry.Rect icon, BookGeometry.Rect name, BookGeometry.Rect minus,
                      BookGeometry.Rect value, BookGeometry.Rect plus, BookGeometry.Rect fold,
                      BookGeometry.Rect remove) {
    }

    public static Row row(BookGeometry.Rect rect) {
        // The controls are the row's own line, not its whole rectangle: a folded row is taller because
        // of the band under it, and the chips and the cross must stay on the line they belong to.
        int line = Math.min(ROW_HEIGHT, rect.height());
        BookGeometry.Rect remove = BookGeometry.Rect.at(rect.right() - REMOVE_WIDTH, rect.y(),
                REMOVE_WIDTH, line);
        BookGeometry.Rect fold = BookGeometry.Rect.at(remove.x() - GAP - FOLD_WIDTH, rect.y(),
                FOLD_WIDTH, line);
        BookGeometry.Rect plus = BookGeometry.Rect.at(fold.x() - GAP - STEPPER_BUTTON, rect.y(),
                STEPPER_BUTTON, line);
        BookGeometry.Rect value = BookGeometry.Rect.at(plus.x() - GAP - STEPPER_VALUE, rect.y(),
                STEPPER_VALUE, line);
        BookGeometry.Rect minus = BookGeometry.Rect.at(value.x() - GAP - STEPPER_BUTTON, rect.y(),
                STEPPER_BUTTON, line);
        BookGeometry.Rect icon = BookGeometry.Rect.at(rect.x() + 2, rect.y() + (line - ICON) / 2,
                ICON, ICON);
        // The name runs from the icon to the stepper, with room for the odds text under it: the odds
        // are drawn at the name's right end, which is why the name is not allowed to reach the stepper.
        BookGeometry.Rect name = BookGeometry.Rect.at(icon.right() + GAP, rect.y(),
                Math.max(0, minus.x() - GAP - (icon.right() + GAP)), line);
        return new Row(icon, name, minus, value, plus, fold, remove);
    }

    /**
     * How wide the odds column is.
     *
     * <p>Named because a second number depends on it: a row's name has to stop short of the column, and
     * that reserve was written as {@code 100} beside this {@code 96} — two literals that had to agree,
     * in two files, with nothing checking that they did. A name one pixel too long runs under the
     * percentage it is next to.
     */
    public static final int ODDS_WIDTH = 96;

    /** The room a row's name leaves for the odds column: the column, plus the air beside it. */
    public static final int ODDS_RESERVE = ODDS_WIDTH + GAP;

    /** Where the odds text is drawn on a row: the right end of the name's band. */
    public static BookGeometry.Rect odds(BookGeometry.Rect rect) {
        Row row = row(rect);
        return BookGeometry.Rect.at(Math.max(row.name().x(), row.name().right() - ODDS_WIDTH), rect.y(),
                ODDS_WIDTH, Math.min(ROW_HEIGHT, rect.height()));
    }

    /**
     * A chance, as an author reads it.
     *
     * <p>Four cases, and each one exists because the other three would be a lie somewhere:
     *
     * <ul>
     *   <li>An always-granted entry is <b>Always</b>: {@code weight <= 0} means it lands whatever the
     *       dice say, and "0.0%" is the opposite of what it does.</li>
     *   <li>An {@code all_table} preview is <b>100%</b>: there are no dice.</li>
     *   <li>A choice preview is <b>Pick</b>: the entries are offered, not rolled.</li>
     *   <li>Otherwise a percentage, qualified as <b>per roll</b> when the table throws more than once —
     *       three throws at 20% is not a 20% chance of seeing the entry, and an author tuning weights
     *       against the wrong number is the fault this line exists to prevent.</li>
     * </ul>
     */
    public static String chance(RewardTable.Chance chance, TableReward.Mode mode, int lootSize) {
        if (chance.always()) {
            // First, and before the mode: an entry that is granted whatever the dice say is Always in
            // every mode, including the two with no dice at all. Saying "100%" for it would be true and
            // would lose the fact that it is a guarantee rather than a probability.
            return "Always";
        }
        String words = modeWords(mode);
        if (!words.isEmpty()) {
            return words;
        }
        String percent = percent(chance.perRoll());
        return lootSize > 1 ? percent + " (per roll)" : percent;
    }

    /**
     * The two modes that have no percentage to show, in their own words. Empty for the two that roll.
     *
     * <p>One description, because two callers ask it: {@link #chance} about an entry the author is
     * tuning, and {@link #reportRate} about one the dice have already been thrown for. Written twice,
     * the panel's own words for {@code all_table} and the pane's could come apart.
     */
    private static String modeWords(TableReward.Mode mode) {
        return switch (mode) {
            case ALL_TABLE -> "100%";
            case CHOICE -> "Pick";
            case RANDOM, LOOT -> "";
        };
    }

    /**
     * One line of a roll report, as the pane prints its rate.
     *
     * <p>The <b>report's</b> mode decides the words rather than the chip the author is looking at: a
     * report is a fact about a roll that has already happened, and drawing it through the live preview
     * would relabel it the moment the chip was pressed — "100%" printed over numbers that came from
     * dice, which is the same fault the request had, one step later in the same round trip.
     */
    public static String reportRate(TableReward.Mode mode, int hits, int rolls) {
        String words = modeWords(mode);
        return words.isEmpty() ? percent(hits / (double) Math.max(1, rolls)) : words;
    }

    /**
     * The second line for a table that throws more than once: the chance of seeing the entry at all.
     *
     * <p>Empty when there is nothing to add — one throw, an always-granted entry, or a mode with no dice
     * — because a second number that repeats the first is noise on a row that has room for one.
     */
    public static String atLeastOnce(RewardTable.Chance chance, TableReward.Mode mode, int lootSize) {
        if (lootSize <= 1 || chance.always() || mode == TableReward.Mode.ALL_TABLE
                || mode == TableReward.Mode.CHOICE) {
            return "";
        }
        return percent(chance.atLeastOnce(lootSize)) + " at least once";
    }

    /**
     * One decimal place, which is as precise as a weight an author types ever is.
     *
     * <p>Remembered by exact value rather than reformatted: a formatter is built per call, and the
     * rows that ask are drawn per frame while the table editor is open. The key is the double's
     * own bits rather than a rounded bucket, so two fractions that print differently can never
     * share an entry — and the formatting itself is untouched, down to the rounding the
     * formatter applies.
     */
    public static String percent(double fraction) {
        double percent = Math.max(0.0, Math.min(1.0, fraction)) * 100.0;
        long key = Double.doubleToLongBits(percent);
        String hit = PERCENTS.get(key);
        if (hit != null) {
            return hit;
        }
        String built = String.format(Locale.ROOT, "%.1f%%", percent);
        if (PERCENTS.size() >= PERCENT_LIMIT) {
            var it = PERCENTS.keySet().iterator();
            int remove = PERCENTS.size() / 2;
            for (int i = 0; i < remove && it.hasNext(); i++) {
                it.next();
                it.remove();
            }
        }
        PERCENTS.put(key, built);
        return built;
    }

    /** Formatted percents, eldest half out at the bound. Client thread only, like every frame cache. */
    private static final Map<Long, String> PERCENTS = new LinkedHashMap<>();

    private static final int PERCENT_LIMIT = 256;

    /**
     * The preview modes the header cycles, in the order it cycles them.
     *
     * <p>The registry's own ring, not a second list: the chip steps through {@code Mode.all()} and
     * {@code /tenet table roll} offers its argument in the same order, so "the four modes" is one
     * list rather than three that agree by inspection.
     */
    public static List<TableReward.Mode> previews() {
        return TableReward.Mode.all();
    }

    /** The next preview in the ring. */
    public static TableReward.Mode nextPreview(TableReward.Mode mode) {
        List<TableReward.Mode> ring = previews();
        int at = ring.indexOf(mode);
        return ring.get(at < 0 ? 0 : (at + 1) % ring.size());
    }

    /** Whether a preview includes the empty band, which is what {@code odds} is asked with. */
    public static boolean includesEmpty(TableReward.Mode mode) {
        return mode == TableReward.Mode.LOOT;
    }

    /**
     * Whether a mode throws dice at all.
     *
     * <p>{@code all_table} grants every entry once and {@code choice} offers them for a pick; neither
     * reads {@code lootSize} and neither reads a weight — {@code RewardTable.all} and
     * {@code RewardTable.choice} take no dice. So the two controls that set those are drawn blocked in
     * those two readings, and register no target: a live stepper that changes nothing about what the
     * file does is a control that lies, and an author tuning weights against a mode that ignores them
     * is the fault this answers.
     */
    public static boolean rolls(TableReward.Mode mode) {
        return mode == TableReward.Mode.RANDOM || mode == TableReward.Mode.LOOT;
    }

    /**
     * The sentence for a control the current reading does not use.
     *
     * <p>One sentence, from the mode, because the header's stepper and every row's weight stepper give
     * the same reason and two spellings of it would read as two different rules.
     */
    public static String unusedByMode(TableReward.Mode mode) {
        return mode == TableReward.Mode.ALL_TABLE
                ? "an all-once table grants every entry, so the weights and the rolls are not used"
                : "a choice offers its entries for a pick, so the weights and the rolls are not used";
    }

    private TableEditorLayout() {
    }
}
