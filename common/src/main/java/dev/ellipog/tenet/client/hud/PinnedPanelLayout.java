package dev.ellipog.tenet.client.hud;

import dev.ellipog.armature.client.ui.kit.Measure;
import dev.ellipog.tenet.client.BookGeometry;

import java.util.ArrayList;
import java.util.List;
import java.util.function.IntFunction;

/**
 * The pinned quests' arithmetic: one box per quest, how tall the stack is, and where each row sits.
 *
 * <h2>Why this is a class and not arithmetic in the painter</h2>
 *
 * <p>Because a painted panel cannot be asked a question. The HUD is drawn by a renderer that needs a live
 * client, so "each quest is its own box", "every box shows its own tasks", "a long title is truncated
 * rather than allowed to widen the box off the window" and "the editor's empty sample is still a box you
 * can grab" are facts nothing could check if they lived in a draw call -- and every one of them goes wrong
 * silently, as boxes that merge into one slab or an element that cannot be picked up. Integers and strings
 * here are swept by {@code PinnedPanelLayoutTest}, and the painter <b>builds itself from them</b>: every
 * rectangle drawn comes from a row of this list, so the test and the drawing cannot be describing two
 * different panels. That is the rule {@code HudLayout} and the book's own geometry were extracted for.
 *
 * <h2>One box per quest, and why the focus model is gone</h2>
 *
 * <p>This used to be one panel with a head drawn in full and the rest as names. A surface that cannot be
 * clicked cannot have a focus worth bringing forward -- "Focus on HUD" was a second act for what "Pin to
 * HUD" already did -- so every pin is now drawn the same way: its name, its tasks, its counts. Re-pinning
 * something already pinned changes nothing, and the menu offers Pin or Unpin and nothing else.
 *
 * <h2>The rows carry their own text, already cut to fit</h2>
 *
 * <p>{@link Row#text()} is what will be drawn, not what was authored: the truncation happens here, where the
 * box's width and the font are both known, so the painter has no measurement left to do and no opportunity
 * to disagree with the box the test asserted. The row is then a left-to-right sequence -- an icon, then its
 * text, then a count, then a tick -- and the drawing advances by measuring those same strings, which is the
 * one place a second opinion could have crept in.
 *
 * <h2>What it is given, and what it deliberately is not</h2>
 *
 * <p>Data, not the game: a list of {@link Pin}, assembled one layer up from {@code ClientQuestCache} by the
 * painter, and a {@link Measure} for the font. No {@code ItemStack}, no screen, no window -- so the whole of
 * the stack's shape is assertable headless, and the icon a task draws cannot change where its row is. The
 * task's <i>index within its own quest</i> travels on the row instead of its icon, which is what lets the
 * painter find the stack without this class ever holding one.
 *
 * <p>The <b>words</b> arrive resolved for the same reason the icon does not: this class has no business
 * knowing what language the player reads, and a sentence built here would be one a translator cannot reach.
 * The one exception is {@link Words#moreTasks}, a function rather than a string, because that sentence
 * carries a count nobody knows until the rows are laid out -- and a placeholder a translator can move is
 * better than a number glued into an English fragment.
 */
public final class PinnedPanelLayout {

    /** The inset on every side of a box. */
    public static final int PAD = 4;

    /** The clear space between two rows. */
    public static final int GAP = 2;

    /** The clear space between one quest's box and the next: what tells two pins apart. */
    public static final int BOX_GAP = 6;

    /** A task's icon box, in pixels: the size the book's own task rows use. */
    public static final int ICON = 12;

    /** The gap between a task's icon and its text. */
    public static final int ICON_GAP = 5;

    /** The gap between a row's text and whatever follows it: a count, a tick or a chapter. */
    public static final int COLUMN_GAP = 4;

    /**
     * A task bar's height in pixels: a hairline, not a panel.
     *
     * <p>Two because the bar sits inside the quest's own box under its own sentence: a six-pixel bar with
     * an outline read as a second control competing with the text, while a hairline reads as what it is --
     * how far along the line above it goes. No outline for the same reason: border thicker than content.
     * The track stays -- a grey remainder rather than transparency -- because over the live world an
     * unfilled stretch is invisible, and a half-done task read as a shorter bar floating beside its count.
     */
    public static final int BAR_HEIGHT = 2;

    /** The gap between a task's text and its bar: close enough to belong to it, far enough to read. */
    public static final int BAR_GAP = 2;

    /**
     * Where a task row's text sits below the row's top.
     *
     * <p>Top-anchored rather than centred, because the row is taller than its text now: centring an 8px
     * glyph band in a 20px row would float every sentence halfway down its own box. Title rows keep their
     * centring -- they have no bar -- so the two are one pixel apart at most, which is what makes the
     * column read as lines rather than as alternating rhythms.
     */
    public static final int TEXT_TOP = 1;

    /** What separates a quest's name from the chapter riding after it. */
    public static final String NOTE_SEPARATOR = "  \u00b7  ";

    /**
     * The tick a finished task wears.
     *
     * <p>One character, and one the font carries: {@code BookGeometry}'s own note lists the symbols this
     * font does <i>not</i> have, and a missing glyph is a box rather than a wrong colour. It is the same
     * character the book's prerequisite rows already draw.
     */
    public static final String TICK = "\u2714";

    /**
     * The narrowest a box may be.
     *
     * <p>Wide enough for the several characters a title has to show before it is a smudge: below this a box
     * is not a box, and a title truncated to four letters is worse than a wide box on a small window -- see
     * {@link #MAX_WIDTH} for the other end.
     */
    public static final int MIN_WIDTH = 96;

    /** And the widest, because a HUD box is not a book: a long title is truncated, not obeyed. */
    public static final int MAX_WIDTH = 190;

    /**
     * How many of one quest's tasks are listed before the rest are counted.
     *
     * <p>A quest may have twenty tasks, and a HUD element is not a scroll: past this the box says how many
     * are left rather than growing past the bottom of somebody's window.
     */
    public static final int MAX_TASK_ROWS = 6;

    /** What a row is, which decides its order, its ink and nothing else. */
    public enum Kind {

        /** One pinned quest's name, with its chapter after it. */
        TITLE,

        /** One task of the quest whose box this row is in: its icon, its text, its count and its tick. */
        TASK,

        /** How many of the quest's tasks are not listed. */
        MORE,

        /** The quest is done. */
        COMPLETE,

        /** The editor's stand-in, when there is nothing pinned to draw. */
        SAMPLE
    }

    /**
     * One task, as a box needs it.
     *
     * @param text     the sentence the row reads, already resolved
     * @param progress how far along it is
     * @param count    what it is counting to
     * @param done     whether it is finished, and so wears the tick
     * @param checkmark whether this row wears the checkmark state box: a bare checkmark task,
     *                 whose done flag then chooses the checked picture over the empty one
     */
    public record Task(String text, int progress, int count, boolean done, boolean checkmark) {
    }

    /**
     * One pinned quest, as a box needs it.
     *
     * @param title     the quest's name
     * @param chapter   where it sits, drawn faint after the name; empty for none
     * @param complete  whether the quest is done
     * @param claimable whether it is done with rewards still to collect: what its last line says
     *                  instead of merely done
     * @param tasks     its tasks, in the pack's order; empty for a quest with none
     */
    public record Pin(String title, String chapter, boolean complete, boolean claimable,
                      List<Task> tasks) {
    }

    /**
     * The boxes' sentences, resolved by the caller. See the class note for why these are not built here.
     *
     * @param complete  the word a finished quest wears
     * @param claimable the word a finished quest with rewards still out wears instead: done is not the
     *                  news, collectable is
     * @param sample    what the editor draws when nothing is pinned
     * @param moreTasks "and N more", for the tasks past {@link #MAX_TASK_ROWS}
     */
    public record Words(String complete, String claimable, String sample,
                        IntFunction<String> moreTasks) {
    }

    /**
     * One row, placed relative to its own box's top-left corner and already cut to fit.
     *
     * <p>The numbers are relative rather than on screen so that a box can be moved without the layout
     * being recomputed -- a drag in the editor is then one addition, and there is no second place that could
     * measure the same content differently.
     *
     * @param text      what this row draws, truncated to the room it has
     * @param note      the faint second half of a title -- a quest's chapter -- or empty. Truncated too, and
     *                  cut against the title's <i>drawn</i> width, so the two cannot overlap
     * @param y         the row's own top, within its box
     * @param height    its own height: a line for a word, an icon's box for a title and a task
     * @param width     the content width every row is given, which is the box less its insets
     * @param pinIndex  which pin's box this row is in. <b>The row carries it rather than the position
     *                  implying it</b>, because the painter needs that quest's task icons and this class
     *                  deliberately never holds one: the lookup happens one layer up, and a row that said
     *                  "the third task" without saying of which quest would make the drawing depend on the
     *                  order this list happens to be built in
     * @param taskIndex which task of that quest this row is, or -1
     * @param progress  a task's progress, and what the count column is made of
     * @param count     a task's target; one or less draws no count column, which is what the book does
     * @param done      for a task, whether it wears the tick
     * @param barY      the top of this row's progress bar, relative to the row -- or -1 when the row draws
     *                  none. Relative to the row rather than the box for the same reason `y` is: the painter
     *                  is handed the row's own top, so a box-relative number would be counted twice and land
     *                  a row too low. Stored rather than derived because only the build knows the text line
     *                  height the bar sits under, and a painter re-deriving it from the row height would be
     *                  the second opinion this class exists to prevent
     */
    public record Row(Kind kind, String text, String note, int y, int height, int width, int pinIndex,
                      int taskIndex, int progress, int count, boolean done, boolean checkmark, int barY) {
    }

    /** One quest's box: its size, and every row in it, placed relative to the box's own top-left. */
    public record Box(int width, int height, List<Row> rows) {
    }

    /** One box placed in the stack: the box, and its top relative to the stack's own top. */
    public record PlacedBox(Box box, int y) {
    }

    /** The whole stack: its size, and every box in it. */
    public record Column(int width, int height, List<PlacedBox> boxes) {

        /**
         * Whether there is nothing to draw.
         *
         * <p>The live answer for a player with nothing pinned: the element is on and it draws nothing at
         * all, rather than an empty frame. The editor never sees this, because its own face supplies the
         * sample.
         */
        public boolean empty() {
            return boxes.isEmpty();
        }
    }

    private PinnedPanelLayout() {
    }

    /**
     * The stack for this content.
     *
     * @param pins    pinned quests, in the order they are drawn; an empty list is legal and is a player's
     *                usual state
     * @param words   the sentences, resolved
     * @param measure the font, which is the only thing here that knows how wide a character is
     * @param editor  whether this is the HUD editor's face, which draws a sample rather than nothing when
     *                there is nothing to draw -- a zero-sized element would be invisible <i>and</i>
     *                ungrabbable, which is the one arrangement that traps a player in that screen
     */
    public static Column column(List<Pin> pins, Words words, Measure measure, boolean editor) {
        if (pins.isEmpty() && !editor) {
            // See Column.empty: nothing pinned draws nothing. Asked here rather than by the painter, so
            // that "is this element empty" and "what does it hold" are one answer.
            return new Column(0, 0, List.of());
        }

        int line = Math.max(8, measure.lineHeight());
        int titleRow = Math.max(ICON, line) + GAP;
        // A task row is its text line, its bar, and breathing room: the bar belongs to the text the way
        // the tick belongs to the count, so the three travel as one height and a row can never separate
        // a sentence from the bar that says how far along it is.
        int taskRow = Math.max(ICON, TEXT_TOP + line + BAR_GAP + BAR_HEIGHT + TEXT_TOP) + GAP;

        // Raw rows first, untruncated: the column is one width, so no box can be cut until the widest row
        // anywhere in the stack is known. A box that measured itself would be a second opinion about the
        // width, and two opinions about one column is how a title ends up wider than its own box.
        List<List<Row>> raw = new ArrayList<>();
        if (pins.isEmpty()) {
            // The editor's sample: one line, so the element has a box and a grab.
            List<Row> rows = new ArrayList<>();
            int cursor = PAD;
            cursor = add(rows, Kind.SAMPLE, words.sample(), "", cursor, line, 0, -1);
            raw.add(rows);
        }
        else {
            for (int p = 0; p < pins.size(); p++) {
                Pin pin = pins.get(p);
                List<Row> rows = new ArrayList<>();
                int cursor = PAD;
                cursor = add(rows, Kind.TITLE, pin.title(), pin.chapter(), cursor, titleRow, p, -1);
                int listed = Math.min(MAX_TASK_ROWS, pin.tasks().size());
                for (int i = 0; i < listed; i++) {
                    Task task = pin.tasks().get(i);
                    // Row-local, like everything else the painter reads: the painter is handed the row's
                    // own top, so a box-relative number here would be counted twice and the bar would land
                    // a row too low -- outside the box it belongs to.
                    // A bar only where a count would be, and only those rows pay for one: a task counting
                    // to one has no progress to show -- the same rule that hides its count column -- so it
                    // takes the short row rather than an empty band where a bar will never be. A countable
                    // task keeps its slot even at zero, so the bar appearing does not reflow the stack.
                    boolean barred = task.count() > 1;
                    int rowH = barred ? taskRow : titleRow;
                    int barY = barred ? TEXT_TOP + line + BAR_GAP : -1;
                    rows.add(new Row(Kind.TASK, task.text(), "", cursor, rowH, 0, p, i,
                            task.progress(), task.count(), task.done(), task.checkmark(), barY));
                    cursor += rowH + GAP;
                }
                if (pin.tasks().size() > listed) {
                    cursor = add(rows, Kind.MORE, words.moreTasks().apply(pin.tasks().size() - listed),
                            "", cursor, line, p, -1);
                }
                if (pin.complete()) {
                    // `done` carries "claimable" on this row: the one line on the HUD that asks the
                    // player to do something, so it is drawn in the call-to-action ink, not the faint.
                    rows.add(new Row(Kind.COMPLETE, pin.claimable() ? words.claimable() : words.complete(), "",
                            cursor, line, 0, p, -1, 0, 0, pin.claimable(), false, -1));
                    cursor += line + GAP;
                }
                raw.add(rows);
            }
        }

        int widest = MIN_WIDTH;
        for (List<Row> rows : raw) {
            for (Row row : rows) {
                widest = Math.max(widest, wanted(row, measure) + PAD * 2);
            }
        }
        int width = Math.min(widest, MAX_WIDTH);
        int content = width - PAD * 2;

        // Stacked with a gap between boxes. A box is its last row's foot plus the closing pad --
        // `bottom` is already past the last row, so unlike the cursor above it carries no trailing gap
        // to subtract.
        List<PlacedBox> placed = new ArrayList<>(raw.size());
        int cursor = 0;
        for (List<Row> rows : raw) {
            List<Row> cut = new ArrayList<>(rows.size());
            int bottom = 0;
            for (Row row : rows) {
                Row drawn = truncated(row, content, measure);
                cut.add(drawn);
                bottom = drawn.y() + drawn.height();
            }
            int height = bottom + PAD;
            placed.add(new PlacedBox(new Box(width, height, List.copyOf(cut)), cursor));
            cursor += height + BOX_GAP;
        }
        int height = placed.isEmpty() ? 0 : cursor - BOX_GAP;
        return new Column(width, height, List.copyOf(placed));
    }

    /** Adds one text row and answers where the next one starts. */
    private static int add(List<Row> rows, Kind kind, String text, String note, int top, int height,
                           int pinIndex, int taskIndex) {
        rows.add(new Row(kind, text == null ? "" : text, note == null ? "" : note, top, height, 0,
                pinIndex, taskIndex, 0, 0, false, false, -1));
        return top + height + GAP;
    }

    /**
     * A task's count, as the row writes it.
     *
     * <p>Clamped, because a counter may overshoot its target and the row is a claim about the target rather
     * than about the counter -- the same reading {@code ClientQuestCache.taskDone} compares against.
     */
    public static String countText(Row row) {
        return Math.min(row.progress(), row.count()) + " / " + row.count();
    }

    /** Whether a task row draws a count at all: a task counting to one has nothing to count. */
    public static boolean hasCount(Row row) {
        return row.kind() == Kind.TASK && row.count() > 1;
    }

    /**
     * Everything a task row draws <b>after</b> its text, as one number.
     *
     * <p>One derivation for the two callers that need it -- the width the row wants, and the room its text
     * has left -- because those two are the same subtraction read in opposite directions, and a painter that
     * reserved its text one width and drew its count at another is the fault this method exists to make
     * impossible.
     */
    private static int afterText(Row row, Measure measure) {
        int width = 0;
        if (hasCount(row)) {
            width += COLUMN_GAP + measure.width(countText(row));
        }
        if (row.done()) {
            width += COLUMN_GAP + measure.width(TICK);
        }
        return width;
    }

    /** How wide one row wants to be, before the column's own clamp and the insets. */
    private static int wanted(Row row, Measure measure) {
        if (row.kind() == Kind.TASK) {
            return ICON + ICON_GAP + measure.width(row.text()) + afterText(row, measure);
        }
        int width = measure.width(row.text());
        if (!row.note().isEmpty()) {
            width += measure.width(NOTE_SEPARATOR) + measure.width(row.note());
        }
        return width;
    }

    /**
     * The same row with its two strings cut to the room the clamped column leaves them.
     *
     * <h2>The chapter never takes more than half the row</h2>
     *
     * <p>Because it is the decoration and the name is the row. Reserving the note's own width first has a
     * failure that only shows up on a long chapter name: the title is left with whatever is over — which at
     * one point was two pixels, so a quest drew a blank line on the box and its chapter drew in full. Half
     * the row each is a rule rather than a tuned number, and the drawing note is cut to what is actually
     * left after the title, which is what stops the two overlapping when the title gives way.
     */
    private static Row truncated(Row row, int content, Measure measure) {
        String text;
        String note = row.note();
        if (row.kind() == Kind.TASK) {
            text = Measure.truncate(row.text(), Math.max(0, content - ICON - ICON_GAP
                    - afterText(row, measure)), measure);
        }
        else {
            int noteRoom = note.isEmpty() ? 0 : Math.min(
                    measure.width(NOTE_SEPARATOR) + measure.width(note), content / 2);
            text = Measure.truncate(row.text(), Math.max(0, content - noteRoom), measure);
            if (!note.isEmpty()) {
                int room = content - measure.width(text) - measure.width(NOTE_SEPARATOR);
                note = Measure.truncate(note, Math.max(0, room), measure);
            }
        }
        return new Row(row.kind(), text, note, row.y(), row.height(), content, row.pinIndex(),
                row.taskIndex(), row.progress(), row.count(), row.done(), row.checkmark(), row.barY());
    }
}
