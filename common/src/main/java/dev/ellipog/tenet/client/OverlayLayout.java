package dev.ellipog.tenet.client;

import dev.ellipog.armature.client.ArmatureTextArea;
import dev.ellipog.armature.client.ui.kit.Insets;
import dev.ellipog.armature.client.ui.kit.Measure;
import dev.ellipog.armature.client.ui.kit.Slot;
import dev.ellipog.armature.client.ui.kit.Stack;
import dev.ellipog.armature.client.ui.kit.TextWrap;

import java.util.List;
import java.util.Objects;

/**
 * How the quest overlay's body stacks up: a description, a task list, a reward list, and what the
 * quest requires.
 *
 * <h2>What this is, and what it is not</h2>
 *
 * <p>It is the <i>particular</i> composition of one screen's reading pane — three headings, an empty
 * state under each, and a row per entry. It is not a layout engine: the stacking itself is
 * {@link Stack}'s, in Armature, and this is a caller of it. The split is worth keeping because the
 * generic half is what a second screen can reuse, and nothing below is true of anything but this pane.
 *
 * <h2>Why it is not inside the screen</h2>
 *
 * <p>Because {@code QuestBookScreen} extends {@code Screen} and needs a running Minecraft to
 * instantiate, so nothing in it can be asserted on. This is the same argument that moved the book's
 * control rectangles into {@link BookGeometry}, and it is here for the same reason: the height this
 * class produces is what the scrollbar's range is computed from, and a scrollbar that disagrees with
 * the drawing is the defect the whole extraction was about.
 *
 * <p>So the composition is a pure function of four counts and a list of strings, and
 * {@code OverlayLayoutTest} can ask it how tall it is without a client. What that test asserts is the
 * property that matters — that the height reported and the rows placed are one computation rather
 * than two that agree — plus the specific advances this pane's appearance was built on.
 *
 * <h2>The advances are deliberate, and are not the kit's defaults</h2>
 *
 * <p>{@link Stack#heading} reserves four pixels below its text, which is a good default. This pane
 * reserves six, because its rule is drawn at ten pixels and there were six below it before any of
 * this was a stack. Using the kit's default would have moved every row of the overlay by two pixels
 * per section, for a reason that has nothing to do with removing the duplication. So the headings
 * here are built from a gap, a line and a gap — the same three elements, with this screen's numbers.
 *
 * <p>The total is still the kit's, because the kit still computes it. That is the whole point, and it
 * is why the numbers being different from the kit's defaults costs nothing.
 *
 * <h2>The one place this file is a behaviour claim rather than a refactor</h2>
 *
 * <p>{@link #BODY_TAIL} exists so that the height this produces is <b>exactly</b> what the
 * hand-written {@code measureOverlay} returned. Every other term here is a one-for-one replacement of
 * a line in it. If that claim is wrong the scroll range moves, which is why
 * {@code OverlayLayoutTest} asserts the total against the old arithmetic written out longhand — see
 * its {@code theHeightMatchesTheHandWrittenMeasureItReplaced}.
 */
public final class OverlayLayout {

    // ------------------------------------------------------------------
    // The advances. Numbers the drawing and the measurement both need.
    // ------------------------------------------------------------------

    /** The item box in a task or reward row: {@code 18} pixels square. */
    public static final int ROW_ICON = 18;

    /**
     * The row pitch: the icon box plus the gap under it.
     *
     * <p>The old code had a 13-pixel pitch under a 16-pixel icon, so consecutive icons overlapped each
     * other and the measurement was wrong in the same direction. One number, used to draw *and* to
     * measure, is what stops that.
     */
    public static final int ROW_ADVANCE = ROW_ICON + 6;

    /**
     * One line of an editor entry's form: the badge, or one row of labelled controls.
     *
     * <p>Sixteen rather than fourteen, and the two pixels are the badge's whole point: an item sprite is
     * sixteen pixels square, and a line shorter than that draws it spilling out of its own slot -- which is
     * exactly how the type icons came to look broken. {@link #ENTRY_PAD} gives the two pixels back, so a
     * one-line entry is still 24 pixels tall to the card that places it. See {@code EntryFormLayout}, which
     * is the one place the number of lines is worked out.
     */
    public static final int ENTRY_LINE_HEIGHT = 16;

    /** An entry's air: one line is 24 pixels tall, exactly as it always was. */
    public static final int ENTRY_PAD = 8;

    /** How tall an entry with this many control lines is. */
    public static int entryRowHeight(int lines) {
        return Math.max(1, lines) * ENTRY_LINE_HEIGHT + ENTRY_PAD;
    }

    /** The pitch for a text-only row — a dependency, which has a tick but no icon. */
    public static final int DEP_ADVANCE = 14;

    /**
     * The indent a row's content takes inside the body.
     *
     * <p>Applied as an {@link Insets} on the row rather than added by whoever draws it, and that is the
     * point: the drawing reads the slot's x and width, so the indent exists once. The version this
     * replaces wrote {@code bodyLeft + 8} and {@code bodyWidth - 16} at every one of seven call sites,
     * which is one indent expressed as two numbers in seven places — and an edit to either number
     * would have moved the text inside its row's box for some rows and not others.
     */
    public static final int ROW_INDENT = 8;

    /**
     * The height of one line of body text.
     *
     * <p>Every advance below is a multiple of this. It is here rather than in the screen because the
     * {@code Measure} the layout is built with has to report it, and the caller is the one that builds
     * the measure — see {@code QuestBookScreen.textMeasure}. Two numbers that must be equal but live in
     * different classes is the fault this whole file is an answer to, so it lives in one place and the
     * screen asks for it.
     */
    public static final int LINE_HEIGHT = 10;

    /** What an empty description reserves: the editor's click-target row, and nothing said in it. */
    public static final int DESCRIPTION_ADVANCE = 12;

    /** The space under a description paragraph. After the last one too — see {@link #stack}. */
    public static final int PARAGRAPH_GAP = 5;

    /** The space above the first section heading, which follows prose. */
    public static final int SECTION_GAP = 8;

    /**
     * Where a heading's rule is drawn, measured from the top of the heading's line.
     *
     * <p>Equal to {@link #LINE_HEIGHT} because that is what it is: the rule sits immediately under the
     * line of text, which is where it has always been drawn. Written as the sum rather than as a second
     * ten, so a change to the line box moves the rule with it instead of leaving it behind.
     */
    public static final int SECTION_LEAD = LINE_HEIGHT;

    /** The space under a heading, before its first row. */
    public static final int SECTION_TAIL = 6;

    /** The space above a section that follows content rather than prose. */
    public static final int SECTION_GAP_AFTER_CONTENT = 10;

    /**
     * The padding the description's frame and its text area share: <b>the text area's own</b>, not a
     * second number chosen to look similar.
     *
     * <p>Because the frame <i>is</i> the field's box — the border is drawn around the field, and the
     * field draws its text this far inside its own edge — so the two must be the same number or the frame
     * is a rectangle that merely resembles the editor's. {@link ArmatureTextArea#PAD} is where the number
     * comes from; a report of *"the borders around it are too cramped, there is no padding"* is what
     * happens when the frame has none of its own.
     */
    public static final int PROSE_PAD = ArmatureTextArea.PAD;

    /**
     * A paragraph of prose as its caller has wrapped it: the visual lines, and the width of the widest.
     *
     * <p>The layout places prose; it does not wrap it. {@link #of} is the plain producer — the editor's raw
     * text, and every test — and the reader's markdown goes through {@code RichText.wrap} instead, because
     * only a caller holding a font can measure a bold word.
     *
     * <p><b>A paragraph has at least one line.</b> An empty string is one empty line, which is what a blank
     * line the author wrote has always taken here; the constructor refuses an empty list rather than
     * folding it into no space, because "no lines" and "one blank line" are different pictures and only one
     * of them is what an empty paragraph means.
     *
     * <p>{@code lineHeight} is the paragraph's own pitch, which is {@link #LINE_HEIGHT} for everything the
     * plain producer makes and taller for a markdown heading: a heading drawn at one and a half times the
     * font has to be <b>reserved</b> at one and a half times the line, or the next paragraph is drawn over
     * the bottom of it.
     */
    public record Prose(List<String> lines, int width, int lineHeight) {

        /** The ordinary pitch: what every paragraph but a heading is drawn at. */
        public Prose(List<String> lines, int width) {
            this(lines, width, LINE_HEIGHT);
        }

        public Prose {
            Objects.requireNonNull(lines, "lines");
            if (lines.isEmpty()) {
                throw new IllegalArgumentException("a paragraph is at least one line: a blank line is a line");
            }
            if (width < 0) {
                throw new IllegalArgumentException("width must not be negative: " + width);
            }
            if (lineHeight < 1) {
                throw new IllegalArgumentException("a line has a height: " + lineHeight);
            }
            lines = List.copyOf(lines);
        }

        /**
         * The plain wrapping: {@link TextWrap}'s rule, and the widest line's own width — which is what this
         * layout used to compute for itself, moved to where the styled version lives beside it.
         */
        public static Prose of(String paragraph, int columnWidth, Measure measure) {
            Objects.requireNonNull(paragraph, "paragraph");
            if (paragraph.isBlank()) {
                return new Prose(List.of(""), 0);
            }
            List<String> lines = TextWrap.wrap(paragraph, columnWidth, measure);
            if (lines.isEmpty()) {
                return new Prose(List.of(""), 0);
            }
            int widest = 0;
            for (String line : lines) {
                widest = Math.max(widest, measure.width(line));
            }
            return new Prose(lines, widest);
        }
    }

    /**
     * Padding at the end of the body. Nothing is drawn in it.
     *
     * <p>Sixteen pixels, and it is here only because the hand-written measure this replaces ended with
     * {@code return height + 16}. Keeping it means the scroll range covers the same distance it did:
     * the migration's job is to make the range and the drawing come from one computation, not to change
     * how far the pane scrolls. Removing it would be a defensible tidy-up and a separate decision.
     *
     * <p><b>It has to be a placed row, not a closing {@code gap}.</b> A gap places no slot, and
     * {@code Layout.height()} is the bottom edge of the lowest slot — so a trailing gap is invisible to
     * the height, and the height is what the viewport is told the content is tall. Written as a gap
     * first, this reserved nothing: the scroll range came out sixteen short, which nothing reported
     * because the sixteen pixels it lost are the empty ones at the end. See {@link #stack} for the
     * call itself, and {@code OverlayLayoutTest.theTailIsAPlacedSlotBecauseAGapWouldNotBeCounted} for
     * the assertion that pins it.
     *
     * <h2>Where this is not a pixel-for-pixel replacement, stated rather than hoped</h2>
     *
     * <p>Every <i>advance</i> here is a one-for-one replacement of a line in the old
     * {@code measureOverlay}: the eight and the sixteen around a heading, the row pitch, the ten and
     * sixteen before REWARDS, the tail. What is <b>not</b> identical is the wrap rule, because the
     * layout wraps with {@code TextWrap} now and the old screen had its own {@code wrap} — and the two
     * differ on exactly three inputs: a paragraph that is empty or blank (the old one produced no line,
     * this produces one), a word wider than the column (the old one overflowed, this splits it), and a
     * run of spaces (the old one kept it in the line's width, this does not). On ordinary prose they
     * agree line for line, which is what {@code OverlayLayoutTest} pins.
     *
     * <p>The difference is bounded and self-consistent: a blank paragraph is ten pixels taller than it
     * was, and the drawing is ten pixels lower with it, because the height and the position come from
     * the same pass. The old arrangement could not have said that — the height came from one function
     * and the drawing from another.
     */
    public static final int BODY_TAIL = 16;

    // ------------------------------------------------------------------
    // Keys. What a caller looks a placed element up by.
    // ------------------------------------------------------------------

    /** The lines a caller draws itself: prose, and the two empty states. */
    public static final String NO_DESCRIPTION = "prose:none";
    public static final String NO_TASKS = "task:none";
    public static final String NO_REWARDS = "reward:none";

    /** The section headings, so the screen can draw its own rule under each. */
    public static final String TASKS_HEADING = "heading:tasks";
    public static final String REWARDS_HEADING = "heading:rewards";
    public static final String REQUIRES_HEADING = "heading:requires";

    private OverlayLayout() {
    }

    // ------------------------------------------------------------------
    // Keys, generated rather than hand-written
    // ------------------------------------------------------------------

    /**
     * The key a description paragraph is placed under.
     *
     * <p>Generated here rather than written as a string literal at both ends. The screen looks a
     * paragraph up by index and the test counts the paragraphs it placed; if those two built their own
     * keys, a change to one would leave the other looking for something that is not there — and the
     * symptom is a slot that silently does not exist, which reads as a paragraph that was never drawn.
     */
    public static String proseKey(int index) {
        return "prose:" + index;
    }

    /**
     * The frame an editor draws around a block of prose: the rectangle the text area occupies, which is
     * the column's full width opened out by {@link #PROSE_PAD} -- and <b>never narrower than the prose
     * placed inside it</b>.
     *
     * <h2>What this replaced, and every half of it was reported from play</h2>
     *
     * <p>The frame used to be the first paragraph's slot, opened out by nothing: its width was the first
     * paragraph's longest line, so a description whose first line was short and whose third was long had
     * the long line running out through the frame's right edge -- *"it doesnt cover the entire width"* --
     * and its top, left and bottom lines sat exactly on the glyphs -- *"the borders around it are too
     * cramped, there is no padding"*.
     *
     * <p>The width is the column opened out, and then <b>at least as wide as the widest line the layout
     * placed</b>. That second half is not belt-and-braces: it is the fix for the report where the frame
     * still came out narrower than the text after the first half was written. The slots are the ground
     * truth of where the prose is — the same slots the drawing uses — while the column width is a second
     * reading of the body, and a frame that can be narrower than the text it frames is a frame that has
     * lost track of which of the two it was built from. A rectangle drawn around a block contains the
     * block; that is the whole rule.
     *
     * <h2>The coordinates are the caller's</h2>
     *
     * <p>{@code first}, {@code bottom} and {@code contentRight} come from the caller's <b>placed</b> slots
     * -- on screen, after the scroll and the cull -- so a description scrolled out of the body has no
     * placed slot and there is no frame to draw. That is deliberate: the frame is what the editor is put
     * over, and an editor over prose that is not on screen is an editor with nowhere to be.
     *
     * @param first        the first paragraph's placed slot, or the empty state's
     * @param bottom       the bottom of the last paragraph, in the same coordinates
     * @param contentRight the right edge of the widest placed paragraph, in the same coordinates
     * @param columnWidth  the body's width, which the frame spans
     */
    public static Slot proseFrame(Slot first, int bottom, int contentRight, int columnWidth) {
        int prose = Math.max(columnWidth, contentRight - first.x());
        return new Slot("prose:frame", first.x() - PROSE_PAD, first.y() - PROSE_PAD,
                prose + PROSE_PAD * 2, bottom - first.y() + PROSE_PAD * 2);
    }

    /** The key a task's row is placed under. */
    public static String taskKey(int index) {
        return "task:" + index;
    }

    /** The key a reward's row is placed under. */
    public static String rewardKey(int index) {
        return "reward:" + index;
    }

    /** The key a dependency's row is placed under. */
    public static String dependencyKey(int index) {
        return "requires:" + index;
    }

    /**
     * The bar a prerequisite row draws as and answers to: the row's own box, widened to the body's edge.
     *
     * <h2>Why this is a function with a test rather than two expressions inline</h2>
     *
     * <p>Because it was two expressions inline and they disagreed. The wash was drawn to
     * {@code Viewport.visibleRight()} — a <b>content</b> coordinate — while the press tested the layout's
     * inset row, so the bar looked narrower than it was and the pixels it did cover were half of them dead.
     * Pointing the press at the same rectangle made it worse rather than better: a content coordinate on
     * that card came out <i>left</i> of the row, the width clamped to zero, and every row stopped
     * answering. One function means the drawn bar and the pressed bar cannot be two rectangles again, and
     * the test below asserts the property that broke — that it is never empty, at any body width.
     *
     * <p>Full width on purpose: the whole bar is the target, which is what "anywhere on it" means. The
     * locate icon inside it is checked first by the press, so widening the bar does not take that gesture
     * away.
     *
     * @param row       the row's own box, in screen coordinates
     * @param bodyRight the body's right edge in <b>screen</b> coordinates
     */
    public static Slot dependencyBar(Slot row, int bodyRight) {
        int left = row.x() - BAR_BLEED;
        // Never narrower than the row it is derived from: a body edge left of the row (a scrolled or
        // re-laid-out card) must not be able to produce a bar that answers nowhere.
        int right = Math.max(bodyRight, row.right());
        return new Slot(row.key(), left, row.y() - 1, Math.max(0, right - left), row.height() + 2);
    }

    /** How far the bar reaches past the row it belongs to, left and right. */
    public static final int BAR_BLEED = 3;

    // ------------------------------------------------------------------
    // The composition
    // ------------------------------------------------------------------

    /**
     * The body's elements, in order, as a stack the caller builds with its own column width and font.
     *
     * <p>Returning a {@link Stack} rather than a {@code Layout} is deliberate: a stack has not been
     * measured yet, so the caller supplies the width and the {@code Measure}, and the test can supply a
     * known width per character. The height is produced by the same {@code build} call that produces
     * the rows, which is the property that makes the scrollbar honest.
     *
     * <p>The gap after every paragraph includes the last, which looks like a mistake and is not: the
     * old measure added five pixels after each one and the scroll range was computed from that total.
     * Dropping the last gap would leave the drawing and the range disagreeing by five pixels, which is
     * precisely the class of defect this class exists to remove.
     *
     * <p>The body's order is the reader's: the tasks and rewards first — what the quest asks of you and
     * gives you — then the description as the context underneath them, then REQUIRES. A quest whose
     * details are withheld keeps the description first, because the two sections above it are absent
     * entirely.
     *
     * @param description      the prose paragraphs, in order. Empty places no prose on a reader's
     *                           card; the editor keeps one row as the description field's click target.
     * @param taskCount        how many tasks. Zero means the empty state is placed.
     * @param rewardCount      how many rewards. Zero means the empty state is placed.
     * @param dependencyCount  how many prerequisites. Zero places no REQUIRES section at all.
     */
    public static Stack stack(List<Prose> description, int taskCount, int rewardCount, int dependencyCount) {
        return stack(description, taskCount, rewardCount, dependencyCount, false);
    }

    /** The keys of the editor's own rows: the add rows, which a reader never sees. */
    public static final String TASKS_ADD = "tasks:add";
    public static final String REWARDS_ADD = "rewards:add";
    public static final String REQUIRES_ADD = "requires:add";

    /**
     * The same layout, with the editor's add rows when {@code editing}.
     *
     * <p>One layout for both modes rather than a second one for the editor: the add row after the
     * tasks pushes the rewards heading down by exactly its own height, and a parallel layout would be
     * a second place for that arithmetic to be wrong. A reader's stack is this one with the rows
     * absent, so the two cannot disagree about where anything sits.
     */
    public static Stack stack(List<Prose> description, int taskCount, int rewardCount,
                              int dependencyCount, boolean editing) {
        return stack(description, taskCount, rewardCount, dependencyCount, editing, Reveal.ALL);
    }

    /**
     * The editor's card, when its entries are more than one line tall.
     *
     * <p>{@code taskLines} and {@code rewardLines} carry, per entry, how many control lines it needs —
     * computed by {@code EntryFormLayout} from the same width this layout is built for. The reader's
     * card does not need it: a reader's row draws one line and has no controls.
     */
    public static Stack stack(List<Prose> description, List<Integer> taskLines, List<Integer> rewardLines,
                              int dependencyCount, boolean editing) {
        return stack(description, taskLines, rewardLines, dependencyCount, editing, Reveal.ALL);
    }

    private static List<Integer> ones(int count) {
        return java.util.Collections.nCopies(count, 1);
    }

    /**
     * What a reader is allowed to see of a card: the description, and the task and reward details.
     *
     * <p>The layout has to know, and not only the drawing, because a section that is not drawn but is
     * still <b>placed</b> leaves a hole where it would have been -- the card shows a gap the height of a
     * paragraph, which reads as a rendering fault rather than as a withheld description. So a hidden
     * section places no slot at all, exactly like a REQUIRES section on a quest with no prerequisites,
     * and the drawing finds nothing to draw in the same way.
     *
     * <p>The editor is never revealed-from: an author looking at a card must see every field they can
     * edit, whatever the reader would be shown. These flags are about the reader's view, so the editor's
     * own stack passes {@link #ALL}.
     */
    public record Reveal(boolean text, boolean details) {

        /** Everything: what a reader sees when the quest hides nothing. */
        public static final Reveal ALL = new Reveal(true, true);
    }

    /** The int-arg form, for the reader's card and the tests: every entry is one line. */
    public static Stack stack(List<Prose> description, int taskCount, int rewardCount,
                              int dependencyCount, boolean editing, Reveal reveal) {
        if (taskCount < 0 || rewardCount < 0 || dependencyCount < 0) {
            throw new IllegalArgumentException("counts must not be negative: " + taskCount + ", "
                    + rewardCount + ", " + dependencyCount);
        }
        return stack(description, ones(taskCount), ones(rewardCount), dependencyCount, editing, reveal);
    }

    /**
     * The full form: one line count per entry, so an entry with wrapped controls is as tall as it
     * needs and everything under it is placed where the drawing will find it.
     *
     * <p>The counts are the caller's because only the caller has the entries and the width the slots
     * will be built at; the arithmetic that turns them into heights is here, beside the rows they
     * belong to, so a row added between entries cannot be placed against a stale total.
     */
    public static Stack stack(List<Prose> description, List<Integer> taskLines, List<Integer> rewardLines,
                              int dependencyCount, boolean editing, Reveal reveal) {
        Objects.requireNonNull(description, "description");
        Objects.requireNonNull(reveal, "reveal");
        Objects.requireNonNull(taskLines, "taskLines");
        Objects.requireNonNull(rewardLines, "rewardLines");
        if (dependencyCount < 0) {
            throw new IllegalArgumentException("counts must not be negative: dependencyCount="
                    + dependencyCount);
        }
        int taskCount = taskLines.size();
        int rewardCount = rewardLines.size();

        Stack stack = Stack.stack();

        // The heading by hand rather than through Stack.heading() -- see the class note on why this
        // pane's tail is six and not four.
        //
        // The whole section, headings included, is skipped when the details are withheld. REQUIRES is
        // deliberately *not*: a locked quest's prerequisites are the one thing its reader most needs --
        // they are what says how to unlock it -- so `hideDetailsUntilStartable` hides what the quest
        // asks of you and gives you, and leaves the way in.
        if (reveal.details()) {
            // No leading gap: the tasks are the first thing on the card now. The gap that used to sit
            // here was the prose's, and it moved down with the prose to below these two sections.
            stack.text(TASKS_HEADING, "TASKS", Stack.Align.LEFT)
                    .gap(SECTION_TAIL);
            if (taskCount == 0) {
                // A row either way, so an empty list and a one-item list take the same space. The old code
                // had to be reminded of that in a comment; here there is no separate measure to remind.
                stack.row(NO_TASKS, ROW_ADVANCE, inset());
            }
            for (int i = 0; i < taskCount; i++) {
                stack.row(taskKey(i), entryRowHeight(taskLines.get(i)), inset());
            }
            if (editing) {
                stack.row(TASKS_ADD, ROW_ADVANCE, inset());
            }

            stack.gap(SECTION_GAP_AFTER_CONTENT)
                    .text(REWARDS_HEADING, "REWARDS", Stack.Align.LEFT)
                    .gap(SECTION_TAIL);
            if (rewardCount == 0) {
                stack.row(NO_REWARDS, ROW_ADVANCE, inset());
            }
            for (int i = 0; i < rewardCount; i++) {
                stack.row(rewardKey(i), entryRowHeight(rewardLines.get(i)), inset());
            }
            if (editing) {
                stack.row(REWARDS_ADD, ROW_ADVANCE, inset());
            }
        }

        // The description, below the two sections it describes: a reader deciding what to do reads the
        // tasks and rewards first, and the prose is the context underneath them. The gap is the one the
        // prose used to sit *after* (SECTION_GAP), so the card's total height is unchanged and only the
        // order moved. When the details are withheld the sections above are absent and the prose is
        // still first, which is what a locked quest's card has always looked like.
        if (!reveal.text()) {
            // Nothing at all, not even the empty state: a quest whose text is withheld still has a
            // description, and the layout must not place a row that claims otherwise.
            description = List.of();
        }
        // Revealed but empty, on a reader's card, the block is absent entirely -- no placeholder
        // row and no leading gap -- the way REQUIRES is absent when there is nothing to require.
        // The editor keeps its row below as the description field's click target.
        boolean collapses = description.isEmpty() && reveal.text() && !editing;
        if (!collapses) {
            if (reveal.details()) {
                stack.gap(SECTION_GAP);
            }
            if (description.isEmpty() && editing) {
                // The editor's empty description: the row stays as the field's click target (see
                // QuestBookScreen.descriptionBox) with nothing drawn in it. A withheld description
                // keeps the gap it has always had, and nothing else.
                stack.row(NO_DESCRIPTION, DESCRIPTION_ADVANCE);
            }
        }
        for (int i = 0; i < description.size(); i++) {
            Prose paragraph = description.get(i);
            /*
             * Placed as the caller measured it, not wrapped here.
             *
             * A paragraph arrives with its visual lines and the width of the widest, because whoever wraps
             * it is the only one who can: a markdown paragraph's bold spans are wider than the same words
             * plain, so only the caller with a font can say where its lines break, and the editor's raw
             * text is wrapped by the plain measure instead. One shape, two producers -- and the rule that a
             * blank line is a line is the producer's: `Prose.of` turns an empty string into one empty line,
             * where `TextWrap` alone would return no lines at all ("nothing in, nothing out") and the blank
             * line would reserve nothing.
             */
            stack.block(proseKey(i), paragraph.width(), paragraph.lines().size() * paragraph.lineHeight(),
                    Stack.Align.LEFT);
            stack.gap(PARAGRAPH_GAP);
        }

        // Absent entirely when there is nothing to require, which is a real difference from the other
        // two: a quest with no prerequisites should not say "REQUIRES: nothing", it should not have the
        // section. That asymmetry is the screen's design and is stated here so the test can hold it.
        if (dependencyCount > 0 || editing) {
            // The editor keeps the section even when it is empty: the add row has to live somewhere,
            // and a quest with no prerequisites is exactly the one an author is about to give some.
            stack.gap(SECTION_GAP_AFTER_CONTENT)
                    .text(REQUIRES_HEADING, "REQUIRES", Stack.Align.LEFT)
                    .gap(SECTION_TAIL);
            for (int i = 0; i < dependencyCount; i++) {
                stack.row(dependencyKey(i), DEP_ADVANCE, inset());
            }
            if (editing) {
                // A real row's height, not the dependency row's: this row carries an id field and a
                // button, and DEP_ADVANCE is fourteen pixels -- a stub you cannot aim at.
                stack.row(REQUIRES_ADD, ROW_ADVANCE, inset());
            }
        }

        // A placed row rather than a closing gap, and that is a bug fix rather than a preference.
        //
        // Layout.height() is derived from its slots -- the bottom edge of the lowest one -- so a gap
        // contributes nothing to it, because a gap places no slot at all. A trailing gap is therefore
        // invisible to the height, and the height is what ScrollView tells the viewport the content is
        // tall. With a gap here the reported content height was sixteen pixels short of what the old
        // hand-written measure returned, so the scroll range was short by sixteen -- silently, because
        // the sixteen pixels it lost are the empty ones at the end and nothing is drawn in them.
        //
        // It is exactly the fault this class exists to remove: a number that is *nearly* right, in a
        // direction that loses a little reachable space, with nothing anywhere reporting it. A row with
        // a null key is what the kit offers for space that is part of the content -- placed, counted,
        // and skipped by a hit test.
        return stack.row(null, BODY_TAIL);
    }

    /**
     * A row's indent, top and bottom included because a row's content sits on its own top edge.
     *
     * <p>One method rather than {@code Insets.symmetric(ROW_INDENT, 0)} written into six row calls: the
     * vertical part is zero on purpose — a row's {@code y} is where its icon is drawn, and the old code
     * drew at exactly the row's top edge — so the pair belongs together where it cannot be taken apart.
     */
    private static Insets inset() {
        return Insets.symmetric(ROW_INDENT, 0);
    }
}
