package dev.ellipog.tenet.client.dev;

import com.google.gson.JsonObject;

import dev.ellipog.tenet.client.BookGeometry;
import dev.ellipog.tenet.client.OverlayLayout;
import dev.ellipog.tenet.quest.EditorField;

import java.util.ArrayList;
import java.util.List;

/**
 * One card entry's form: its badge, its labelled fields, and the three controls in its corner.
 *
 * <h2>Why this replaced a flow layout</h2>
 *
 * <p>An entry used to be a flat list of boxes flowed left to right at fixed widths, with the label drawn
 * <i>inside</i> each box as placeholder text. That is why it read as a wall: a box said "Count" when it was
 * empty and "8" when it was not, a flag was a box containing the word "off", six coordinates were six
 * identical boxes, and nothing lined up with anything because every kind had a different width.
 *
 * <p>A form instead. Every entry opens with a badge -- the type's icon and the type's own name, which is
 * what makes a task a *named thing* rather than a sprite jammed against a box -- and then one field per
 * line (two, when both are narrow), each drawn as a label in a fixed column and a control in the column
 * beside it. Controls of a kind are the same size wherever they appear, so the eye can run down the
 * column.
 *
 * <h2>One derivation, again</h2>
 *
 * <p>Drawing and hit-testing both ask here, the house rule: a control drawn in one place and pressed in
 * another is the class of fault this codebase keeps designing out. The line count goes to
 * {@link OverlayLayout#entryRowHeight}, so the card's own arithmetic keeps placing the rows below against
 * the height the drawing will use.
 *
 * <p>Game-free: the editor's fields come from the registry, and nothing here touches a renderer.
 */
public final class EntryFormLayout {

    private EntryFormLayout() {
    }

    /** Between the entry's edge and anything inside it. */
    public static final int PAD = 4;

    /** Between a label and its control, and between two controls on one line. */
    public static final int GAP = 4;

    /** The badge's icon: a whole item sprite, in a box its own size. */
    public static final int ICON_BOX = 16;

    /** The drag grip: the icon and the gutter beside it, which nothing else covers. */
    public static final int GRIP_WIDTH = ICON_BOX + GAP + 2;

    /**
     * The label column's bounds. Its width is the form's own -- the longest label it has to show, clamped
     * -- because one fixed number either truncates "Any dimension" or leaves a gulf beside "Count".
     *
     * <p>Measured at the same six pixels a character the panel's own rows assume
     * ({@code Measure.monospace(6, 9)}): this class draws nothing, so it cannot ask a renderer, and a
     * label's width is the one number it needs to guess. The guess is generous on purpose -- a label a
     * pixel too narrow is truncated text, and a column a pixel too wide is air.
     */
    public static final int MIN_LABEL_WIDTH = 52;
    public static final int MAX_LABEL_WIDTH = 104;

    /** The width one character is assumed to take, for the label column and nothing else. */
    public static final int CHAR_WIDTH = 6;

    /** One line. The badge and every field line are this tall, and the card's arithmetic knows it. */
    public static final int LINE_HEIGHT = OverlayLayout.ENTRY_LINE_HEIGHT;

    /** A stepper's `-` and `+`, and the chip a flag is drawn as. Square, and the height of the line. */
    public static final int BUTTON = 12;

    /**
     * How wide a narrow control is, by kind.
     *
     * <p>Fixed, and that is the point. A stepper sized from what was left of its cell drew its `+` at the
     * cell's far edge, so the same count looked like a different control depending on what it happened to
     * share a line with -- a compact `[− 8 × +]` above a `[−        +]` stretched across a half-line. A
     * control is one width wherever it appears, and the room left over is air.
     */
    public static final int STEPPER_VALUE_WIDTH = 44;
    public static final int CHOICE_WIDTH = 104;

    /** The narrowest a value box may be before the layout stops trying to fit two on a line. */
    public static final int MIN_VALUE_WIDTH = 56;

    /** The reserved corner: Copy, the template star, the fold, and the cross, in that order along the badge's line. */
    public static final int COPY_WIDTH = 34;
    public static final int SAVE_WIDTH = 14;
    public static final int FOLD_WIDTH = 14;
    public static final int REMOVE_WIDTH = 16;

    /** How far a condition is indented inside its entry, per level: it is inside the task, not beside it. */
    public static final int CONDITION_INDENT = 12;

    /** How wide the "Add condition" row's control is, at most. */
    public static final int ADD_CONDITION_WIDTH = 120;

    /** No box at all, for the kinds that do not have one. */
    public static final BookGeometry.Rect NONE = BookGeometry.Rect.at(0, 0, 0, 0);

    /**
     * One field as the form draws it.
     *
     * <p>Which boxes are populated follows the kind, and the screen reads them by name rather than by
     * guessing: a number has {@code minus}, {@code value} and {@code plus}; a triple has its three
     * {@code axes} and, for a position, the {@code action} that fills them from where the player stands;
     * everything else has {@code value} alone and the rest are {@link #NONE}.
     */
    public record Cell(EditorField field, BookGeometry.Rect label, BookGeometry.Rect value,
                       BookGeometry.Rect minus, BookGeometry.Rect plus,
                       List<BookGeometry.Rect> axes, BookGeometry.Rect action) {

        public boolean isNumber() {
            return field.kind() == EditorField.Kind.NUMBER;
        }

        public boolean isTriple() {
            return !axes.isEmpty();
        }

        /** The box a press on the value lands in, whichever kind it is. */
        public BookGeometry.Rect pressTarget() {
            return isTriple() ? axes.get(0) : value;
        }
    }

    /**
     * One condition under an entry: its badge line, its own form's cells, and the two controls in
     * its corner — the template star and the cross that removes it.
     *
     * <p>No grip and no fold. A conditions list is an AND, so its order is cosmetic and there is nothing
     * a reorder would mean; and a condition is two or three controls, which folded would still need its
     * badge to say which one it is.
     *
     * @param lines the badge line plus the condition's own field lines, for the entry's total
     */
    public record ConditionRow(int index, BookGeometry.Rect icon, BookGeometry.Rect name,
                               BookGeometry.Rect save, BookGeometry.Rect remove, List<Cell> cells, int lines) {
    }

    /**
     * One entry, laid out: its badge, its fields, its conditions, its grip and the three controls in its
     * corner.
     *
     * <p>Folded, {@link #cells} is empty and {@link #lines} is one: a folded entry is its badge line, which
     * is the same 24 pixels a one-line entry has always been.
     *
     * @param conditions  one row per condition in the entry's list, in the order the file has them
     * @param addCondition the press that opens the condition picker; {@link #NONE} on a folded entry or
     *                     an unknown type, whose conditions are part of its raw JSON instead
     * @param lines the height in lines, which is what {@link OverlayLayout#entryRowHeight} wants
     */
    public record Form(BookGeometry.Rect badge, BookGeometry.Rect icon, BookGeometry.Rect name,
                       BookGeometry.Rect grip, BookGeometry.Rect copy, BookGeometry.Rect save,
                       BookGeometry.Rect fold,
                       BookGeometry.Rect remove, List<Cell> cells, List<ConditionRow> conditions,
                       BookGeometry.Rect addCondition, int lines) {

        public int height() {
            return OverlayLayout.entryRowHeight(lines);
        }
    }

    /** One entry's form, from its type's own fields, within this slot. */
    public static Form form(String member, JsonObject entry, BookGeometry.Rect slot) {
        return form(member, entry, slot, false);
    }

    /**
     * The same, folded to its badge when {@code collapsed}.
     *
     * <p>Folding is done by handing the layout no fields at all rather than by a second code path: the
     * badge, the grip and the corner are the same three things either way, the line count falls out of the
     * packing, and a folded form is therefore one line for the same reason an unknown type's is.
     */
    public static Form form(String member, JsonObject entry, BookGeometry.Rect slot, boolean collapsed) {
        List<EditorField> fields = collapsed ? List.of() : QuestPanelLayout.editorFor(member, entry);
        int left = slot.x() + PAD + GRIP_WIDTH;
        int right = slot.right() - PAD;

        // The badge's line, then the fields. The corner controls share the badge's line, which is what
        // gives the fields the full width: the old row reserved 96 pixels on their right for Copy and the
        // cross, and every control paid for it.
        int badgeY = slot.y() + PAD;
        BookGeometry.Rect icon = BookGeometry.Rect.at(slot.x() + PAD + 1, badgeY, ICON_BOX, LINE_HEIGHT);
        BookGeometry.Rect remove = BookGeometry.Rect.at(right - REMOVE_WIDTH, badgeY, REMOVE_WIDTH,
                LINE_HEIGHT);
        BookGeometry.Rect fold = BookGeometry.Rect.at(remove.x() - GAP - FOLD_WIDTH, badgeY, FOLD_WIDTH,
                LINE_HEIGHT);
        // The star between Copy and the fold: a harmless control beside a harmless one, with the
        // destructive cross still last at the edge — so a mis-aimed press travels past two saves
        // before it can reach the removal.
        BookGeometry.Rect save = BookGeometry.Rect.at(fold.x() - GAP - SAVE_WIDTH, badgeY, SAVE_WIDTH,
                LINE_HEIGHT);
        BookGeometry.Rect copy = BookGeometry.Rect.at(save.x() - GAP - COPY_WIDTH, badgeY, COPY_WIDTH,
                LINE_HEIGHT);

        int nameWidth = Math.max(0, copy.x() - GAP - (icon.right() + GAP));
        BookGeometry.Rect name = BookGeometry.Rect.at(icon.right() + GAP, badgeY, nameWidth, LINE_HEIGHT);

        // The label column is this form's own, so a form of one-word labels stays compact and a form with
        // "Any dimension" in it does not truncate.
        int labelWidth = labelWidth(fields);
        List<List<EditorField>> lines = lines(fields, right - left, labelWidth);
        List<Cell> cells = new ArrayList<>();
        for (int i = 0; i < lines.size(); i++) {
            int y = badgeY + (i + 1) * LINE_HEIGHT;
            List<EditorField> line = lines.get(i);
            if (line.size() == 1) {
                cells.add(cell(line.get(0), left, y, right - left, labelWidth));
            }
            else {
                // Equal columns, so every line's columns start at the same x and the form reads as a grid
                // rather than as controls that happen to share a line. The fit test above is what
                // guarantees a column is wide enough for the field it holds.
                int columns = line.size();
                int each = Math.max(0, (right - left - (columns - 1) * GAP) / columns);
                for (int c = 0; c < columns; c++) {
                    cells.add(cell(line.get(c), left + c * (each + GAP), y, each, labelWidth));
                }
            }
        }
        int lines_ = lines.size() + 1;

        // The conditions, under the entry's own fields: one badge line each, then the condition's own
        // form with the same cell arithmetic, indented. Then the row that opens the picker, which is the
        // entry's last line whenever it is open for editing -- so the height the card reserves and the
        // rows the drawing produces come from this one place, as they do for the fields. Folded, there
        // are none: folding is "no fields", and a condition list is part of them.
        //
        // Not on an unknown type: its whole entry is the raw-JSON fallback, and a condition inserted
        // into a tree whose shape this build cannot read has nowhere to be drawn.
        //
        // **Two questions rather than one, and the difference is the depth.** An entry's conditions are
        // authored content, so Normal mode lists them and lets them be edited or removed: hiding a gate that
        // is really there would leave an author reading a task whose behaviour the panel does not explain.
        // Adding one is the advanced half -- the picker, the six types and the nesting are the part of the
        // format an occasional author never needs -- so the add row is the one that goes.
        List<ConditionRow> conditionRows = new ArrayList<>();
        boolean known = !collapsed && QuestPanelLayout.knownType(typeOf(entry));
        boolean canAddConditions = known && Advanced.on();
        if (known && entry.has("conditions") && entry.get("conditions").isJsonArray()) {
            int used = lines_;
            for (int j = 0; j < entry.getAsJsonArray("conditions").size(); j++) {
                JsonObject condition = conditionAt(entry, j);
                if (condition == null) {
                    continue;
                }
                ConditionRow row = conditionRow(j, condition, left, right, badgeY, used);
                conditionRows.add(row);
                used += row.lines();
            }
            lines_ = used;
        }
        BookGeometry.Rect addCondition = canAddConditions
                ? BookGeometry.Rect.at(left, badgeY + lines_ * LINE_HEIGHT,
                        Math.min(ADD_CONDITION_WIDTH, Math.max(0, right - left)), LINE_HEIGHT)
                : NONE;
        if (canAddConditions) {
            lines_ += 1;
        }

        int gripHeight = Math.max(10, OverlayLayout.entryRowHeight(lines_) - 2 * PAD + 2);
        BookGeometry.Rect grip = BookGeometry.Rect.at(slot.x(), slot.y() + 2, GRIP_WIDTH, gripHeight);
        return new Form(BookGeometry.Rect.at(slot.x(), badgeY, slot.width(), LINE_HEIGHT), icon, name, grip,
                copy, save, fold, remove, List.copyOf(cells), List.copyOf(conditionRows), addCondition, lines_);
    }

    /** The type an entry names, or empty when it names none or names one badly. */
    private static String typeOf(JsonObject entry) {
        return entry.has("type") && entry.get("type").isJsonPrimitive()
                ? entry.get("type").getAsString() : "";
    }

    /** One of an entry's conditions, or null when the list holds something that is not an object. */
    private static JsonObject conditionAt(JsonObject entry, int index) {
        com.google.gson.JsonElement value = entry.getAsJsonArray("conditions").get(index);
        return value.isJsonObject() ? value.getAsJsonObject() : null;
    }

    /**
     * One condition's row: its badge at the indent, and its own form's cells below it.
     *
     * <p>The indent is per level, not per kind: the badge sits {@code CONDITION_INDENT} in from the
     * entry's content and the fields another step in from that, so what the eye reads is "inside the
     * task" rather than "beside it". The cells come from the same {@link #cell} arithmetic the entry's
     * own fields use, so a count is the same stepper wherever it appears.
     */
    private static ConditionRow conditionRow(int index, JsonObject condition, int left, int right,
                                             int badgeY, int line) {
        int x = left + CONDITION_INDENT;
        int y = badgeY + line * LINE_HEIGHT;
        BookGeometry.Rect icon = BookGeometry.Rect.at(x, y, ICON_BOX, LINE_HEIGHT);
        BookGeometry.Rect remove = BookGeometry.Rect.at(right - REMOVE_WIDTH, y, REMOVE_WIDTH, LINE_HEIGHT);
        BookGeometry.Rect save = BookGeometry.Rect.at(remove.x() - GAP - SAVE_WIDTH, y, SAVE_WIDTH,
                LINE_HEIGHT);
        int nameWidth = Math.max(0, save.x() - GAP - (icon.right() + GAP));
        BookGeometry.Rect name = BookGeometry.Rect.at(icon.right() + GAP, y, nameWidth, LINE_HEIGHT);

        List<EditorField> fields = QuestPanelLayout.conditionEditorFor(condition);
        int fieldLeft = x + CONDITION_INDENT;
        int labelWidth = labelWidth(fields);
        List<List<EditorField>> fieldLines = lines(fields, right - fieldLeft, labelWidth);
        List<Cell> cells = new ArrayList<>();
        for (int i = 0; i < fieldLines.size(); i++) {
            int cellY = y + (i + 1) * LINE_HEIGHT;
            List<EditorField> lineFields = fieldLines.get(i);
            if (lineFields.size() == 1) {
                cells.add(cell(lineFields.get(0), fieldLeft, cellY, right - fieldLeft, labelWidth));
            }
            else {
                int half = (right - fieldLeft - GAP) / 2;
                cells.add(cell(lineFields.get(0), fieldLeft, cellY, half, labelWidth));
                cells.add(cell(lineFields.get(1), fieldLeft + half + GAP, cellY, half, labelWidth));
            }
        }
        return new ConditionRow(index, icon, name, save, remove, List.copyOf(cells), 1 + fieldLines.size());
    }

    /**
     * The label column for these fields: the longest label, measured at the same six pixels a character
     * the panel's own rows assume, clamped.
     *
     * <p>Clamped at both ends because neither extreme is right. A form of one-word labels should not
     * reserve a third of its width for air, and a form whose longest label is a sentence would push every
     * control off the card -- there a truncated label is the lesser fault, and the hover shows the whole
     * of it.
     */
    private static int labelWidth(List<EditorField> fields) {
        int widest = 0;
        for (EditorField field : fields) {
            widest = Math.max(widest, field.label().length() * CHAR_WIDTH + 6);
        }
        return Math.max(MIN_LABEL_WIDTH, Math.min(MAX_LABEL_WIDTH, widest));
    }

    /**
     * The lines the fields are packed into: a narrow field shares its line with the next narrow one when
     * both fit, and everything else takes a line of its own.
     *
     * <p>Two per line rather than "as many as fit": three steppers abreast on a wide card would look
     * nothing like the same form on a narrow one, and a form whose shape depends on the window is a form
     * an author has to re-read every time they resize.
     */
    private static List<List<EditorField>> lines(List<EditorField> fields, int available, int labelWidth) {
        // A line takes as many narrow controls as fit, rather than a fixed one or two. The case this
        // exists for is the reward's common settings: `Given`, `Claim separately` and `Ignore blocking`
        // are three short switches, and packing them two-and-one made a column of loose chips with the
        // third stranded on its own line -- which is what the screenshot showed as controls floating at
        // the right. Widths are the field's own, not a shared minimum: a flag's control is twelve pixels
        // and asking for a stepper's room on its behalf would keep the third switch off the line.
        List<List<EditorField>> out = new ArrayList<>();
        List<EditorField> line = new ArrayList<>();
        int widest = 0;
        for (EditorField field : fields) {
            if (!narrow(field)) {
                flush(out, line);
                widest = 0;
                out.add(List.of(field));
                continue;
            }
            int need = labelWidth + GAP + minControl(field);
            // Equal columns, so the test is the widest field on the line rather than the sum: adding a
            // twelve-pixel flag to a line that holds a stepper must not shrink the stepper's boxes.
            int widestNext = Math.max(widest, need);
            int columns = line.size() + 1;
            if (!line.isEmpty() && columns * widestNext + (columns - 1) * GAP > available) {
                flush(out, line);
                widest = 0;
                widestNext = need;
                columns = 1;
            }
            widest = widestNext;
            line.add(field);
        }
        flush(out, line);
        return out;
    }

    /**
     * Whether a cell shares its line with another: a switch there draws its label against its own chip.
     *
     * <p>Left-aligned in the label column, a flag's word sat a column's width from its chip and a gap's
     * width from the previous one's, so "Consume [ ] Optional [ ] Quiet task [ ]" read as each chip
     * belonging to the word after it. Only the drawing changes: the label keeps its column, and so its
     * press.
     */
    public static boolean sharesLine(Cell cell, List<Cell> cells) {
        for (Cell other : cells) {
            if (other != cell && other.label().y() == cell.label().y()) {
                return true;
            }
        }
        return false;
    }

    /** The narrowest a control may be drawn: what the fit test above measures a field by. */
    private static int minControl(EditorField field) {
        return switch (field.kind()) {
            case FLAG -> BUTTON;
            case CHOICE -> Math.min(CHOICE_WIDTH, MIN_VALUE_WIDTH + 40);
            case NUMBER -> 2 * BUTTON + STEPPER_VALUE_WIDTH + 2 * GAP;
            default -> MIN_VALUE_WIDTH;
        };
    }

    private static void flush(List<List<EditorField>> out, List<EditorField> line) {
        if (!line.isEmpty()) {
            out.add(List.copyOf(line));
            line.clear();
        }
    }

    /** Whether a field may share its line: the kinds whose controls are small and word-shaped. */
    private static boolean narrow(EditorField field) {
        return switch (field.kind()) {
            case NUMBER, FLAG, CHOICE -> true;
            default -> false;
        };
    }

    /** One cell: the label column at {@code x}, and the control beside it. */
    private static Cell cell(EditorField field, int x, int y, int width, int labelWidth) {
        BookGeometry.Rect label = BookGeometry.Rect.at(x, y, labelWidth, LINE_HEIGHT);
        int controlLeft = x + labelWidth + GAP;
        int room = Math.max(0, width - labelWidth - GAP);
        BookGeometry.Rect control = BookGeometry.Rect.at(controlLeft, y, room, LINE_HEIGHT);

        if (field.kind() == EditorField.Kind.NUMBER) {
            // Three boxes rather than one: a number is almost always nudged rather than typed, and the
            // buttons say which direction and by how much without a keyboard. All three are fixed sizes,
            // so a stepper is the same control on a half-line as on a line of its own.
            int valueWidth = Math.min(STEPPER_VALUE_WIDTH,
                    Math.max(0, room - 2 * (BUTTON + GAP)));
            BookGeometry.Rect minus = BookGeometry.Rect.at(controlLeft, y, BUTTON, LINE_HEIGHT);
            BookGeometry.Rect value = BookGeometry.Rect.at(minus.right() + GAP, y, valueWidth, LINE_HEIGHT);
            BookGeometry.Rect plus = BookGeometry.Rect.at(value.right() + GAP, y, BUTTON, LINE_HEIGHT);
            return new Cell(field, label, value, minus, plus, List.of(), NONE);
        }
        if (field.kind() == EditorField.Kind.CHOICE) {
            // A word box, wide enough for "Block entity type" and truncated inside when an option is
            // longer than that -- the hover lists the whole ring.
            BookGeometry.Rect value = BookGeometry.Rect.at(controlLeft, y,
                    Math.min(CHOICE_WIDTH, room), LINE_HEIGHT);
            return new Cell(field, label, value, NONE, NONE, List.of(), NONE);
        }
        if (field.kind() == EditorField.Kind.FLAG) {
            // The chip, with its label in the label column: the two read as one control because they share
            // a line, and the press covers both.
            BookGeometry.Rect chip = BookGeometry.Rect.at(controlLeft, y, BUTTON, LINE_HEIGHT);
            return new Cell(field, label, chip, NONE, NONE, List.of(), NONE);
        }
        if (field.isTriple()) {
            // The three axes across the control column, and -- for a position -- the button that fills
            // them from the player, on the right where a button belongs.
            boolean position = field.kind() == EditorField.Kind.POSITION;
            int actionWidth = position ? Math.min(76, Math.max(0, room / 3)) : 0;
            int each = Math.max(0, (room - (position ? actionWidth + GAP : 0) - 2 * GAP) / 3);
            List<BookGeometry.Rect> axes = new ArrayList<>();
            for (int axis = 0; axis < 3; axis++) {
                axes.add(BookGeometry.Rect.at(controlLeft + axis * (each + GAP), y, each, LINE_HEIGHT));
            }
            BookGeometry.Rect action = position
                    ? BookGeometry.Rect.at(control.right() - actionWidth, y, actionWidth, LINE_HEIGHT)
                    : NONE;
            return new Cell(field, label, NONE, NONE, NONE, List.copyOf(axes), action);
        }
        return new Cell(field, label, control, NONE, NONE, List.of(), NONE);
    }

    /** How many lines this entry's form takes. The card layout's height input. */
    public static int lines(String member, JsonObject entry, int slotWidth) {
        return lines(member, entry, slotWidth, false);
    }

    /**
     * The same, for an entry the card is folding.
     *
     * <p>The card asks for every entry's height on every frame, so a fold reflows the list the moment it is
     * pressed -- there is no widget to rebuild and no cached height to invalidate.
     */
    public static int lines(String member, JsonObject entry, int slotWidth, boolean collapsed) {
        return form(member, entry,
                BookGeometry.Rect.at(0, 0, slotWidth, OverlayLayout.entryRowHeight(1)), collapsed).lines();
    }
}
