package dev.ellipog.tenet.client;

import dev.ellipog.armature.client.ArmatureTextArea;
import dev.ellipog.armature.client.ui.kit.Layout;
import dev.ellipog.armature.client.ui.kit.Measure;
import dev.ellipog.armature.client.ui.kit.Slot;
import dev.ellipog.armature.client.ui.kit.Stack;
import dev.ellipog.armature.client.ui.kit.TextArea;
import dev.ellipog.armature.client.ui.kit.TextWrap;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The overlay's body, asserted without a client.
 *
 * <h2>Why this file exists at all</h2>
 *
 * <p>Because {@link OverlayLayout} exists, and a class that composes a layout is only worth
 * extracting if something can check what it composed. This is the reason the extraction was done:
 * {@code QuestBookScreen} extends {@code Screen}, so the height its overlay reports — which the
 * scrollbar's range is computed from — could previously only be judged by looking at a scrollbar.
 *
 * <h2>The two assertions that carry the file</h2>
 *
 * <p>{@link #theHeightMatchesTheHandWrittenMeasureItReplaced()} pins the total against the old
 * {@code measureOverlay} arithmetic written out longhand, so the migration is a refactor rather than
 * a quiet change of how far the pane scrolls. It uses <b>literals</b>, not this class's constants:
 * aliasing them would make the test agree with any change to them, and the point is to make a change
 * to one of those numbers a deliberate act that fails a build.
 *
 * <p>{@link #theTailIsAPlacedSlotBecauseAGapWouldNotBeCounted()} exists because the first version of
 * {@link OverlayLayout} closed with a {@code gap} and was wrong — {@code Layout.height()} is derived
 * from places slots, and a gap places none, so the trailing padding vanished and the content height
 * came out sixteen pixels short. Nothing could see that: the sixteen pixels lost are empty ones at
 * the end. That is the exact shape of the defect this whole round was about, so it gets a test whose
 * name says what the mechanism is.
 */
class OverlayLayoutTest {

    /**
     * Six pixels a character, ten a line — the same stand-in the kit's own tests use.
     *
     * <p>Monospace and a known width per character, so every expected line count in this file can be
     * checked by hand. A proportional fake would let a wrong-by-one wrap hide behind a plausible
     * width, which is the thing the wrap rules are asserted against.
     */
    private static final Measure FONT = Measure.monospace(6, 10);

    /** Ordinary prose: every word fits, no runs of spaces, no blank paragraphs. */
    private static final List<String> PROSE = List.of(
            "Gather eight oak logs from the forest",
            "Then craft a table and four sticks",
            "Finally make a wooden pickaxe");

    /** Empty, for the cases that need the description out of the way. */
    private static final List<String> NOTHING = List.of();

    /**
     * How tall the old screen's own measure said this content was.
     *
     * <h2>Transcribed from {@code QuestBookScreen.measureOverlay}, not paraphrased</h2>
     *
     * <p>Every number below is the literal that method contained, in the order it added them. That is
     * deliberate, and it is the only thing that makes this a regression test rather than a tautology:
     * written against {@link OverlayLayout}'s own constants it would agree with any change to them,
     * and the change it needs to catch is exactly "somebody adjusted an advance".
     *
     * <p>The paragraph term calls {@link TextWrap} for its line count rather than reimplementing the
     * old screen's private {@code wrap}. The two agree line for line on {@link #PROSE} — which is why
     * the parameter below is documented as ordinary prose — and they differ on three inputs that no
     * fixture here contains: a blank paragraph, a word wider than the column, and a run of spaces.
     * {@link OverlayLayout}'s javadoc states those three and why they are acceptable.
     *
     * <p>One deliberate departure from the transcription: the old screen reserved twelve pixels for an
     * empty description's placeholder row, and the eight-pixel gap below the sections belonged to that
     * row's block. A quest with no description now has no description block at all, so both terms are
     * gone for an empty description and the TASKS term below lost the eight it carried.
     */
    private static int oldMeasure(List<String> description, int taskCount, int rewardCount,
                                  int dependencyCount, int width) {
        int height = 0;
        for (OverlayLayout.Prose paragraph : prose(description, width)) {
            // The lines, not the strings: the wrap is the producer's now, and this arithmetic is the one
            // the old screen kept by hand -- so it counts the lines the producer handed over.
            height += paragraph.lines().size() * 10 + 5;
        }
        if (!description.isEmpty()) {
            // The gap that moved down with the prose: part of the description block, so a quest with
            // no description does not pay it.
            height += 8;
        }
        height += 16;                                               // TASKS heading
        height += Math.max(1, taskCount) * 24;
        height += 10 + 16;                                         // REWARDS heading
        height += Math.max(1, rewardCount) * 24;
        if (dependencyCount > 0) {
            height += 10 + 16 + dependencyCount * 14;
        }
        return height + 16;
    }

    private static Layout build(List<String> description, int taskCount, int rewardCount,
                                int dependencyCount, int width) {
        return OverlayLayout.stack(prose(description, width), taskCount, rewardCount, dependencyCount)
                .build(width, FONT);
    }

    /**
     * The descriptions in this file are written as strings, because that is what they are: the wrapping is
     * the producer's ({@code Prose.of}, and {@code RichText.wrap} for the reader's markdown), and these
     * tests are about where the layout puts what it is given.
     */
    private static List<OverlayLayout.Prose> prose(List<String> description, int width) {
        List<OverlayLayout.Prose> prose = new java.util.ArrayList<>(description.size());
        for (String paragraph : description) {
            prose.add(OverlayLayout.Prose.of(paragraph, width, FONT));
        }
        return List.copyOf(prose);
    }

    // ------------------------------------------------------------------
    // The total, which is what the scrollbar is computed from
    // ------------------------------------------------------------------

    @Test
    void theHeightMatchesTheHandWrittenMeasureItReplaced() {
        // Swept rather than spot-checked. The old arithmetic had a term per section, an empty state
        // that replaced a row, and one section that is absent entirely when it has nothing to say --
        // so a single fixture exercises maybe half of the branches, and the branches are where a
        // refactor of this kind goes wrong.
        //
        // Widths from 60 up: at 48 and below a heading is narrower than its own longest word, and the
        // stack wraps it to two lines where the old measure reserved one hardcoded line. That
        // difference is real and is asserted separately below; it is the one place the two disagree,
        // so it must not be folded into a sweep that claims they agree.
        for (int width : new int[] {60, 66, 90, 120, 180, 240}) {
            for (int tasks : new int[] {0, 1, 3, 7}) {
                for (int rewards : new int[] {0, 1, 2}) {
                    for (int dependencies : new int[] {0, 1, 4}) {
                        for (List<String> description : List.of(PROSE, NOTHING)) {
                            int expected = oldMeasure(description, tasks, rewards, dependencies, width);
                            int actual = build(description, tasks, rewards, dependencies, width).height();
                            assertEquals(expected, actual, "width " + width + ", " + tasks + " task(s), "
                                    + rewards + " reward(s), " + dependencies + " dependency(ies), "
                                    + description.size() + " paragraph(s)");
                        }
                    }
                }
            }
        }
    }

    @Test
    void theTailIsAPlacedSlotBecauseAGapWouldNotBeCounted() {
        // The bug this file was written after, and the reason the method is named this way rather than
        // "theTailIsReserved". A gap contributes to the cursor and not to the slots, and the height is
        // the slots -- so a trailing gap is a reservation that does not exist.
        Layout layout = build(PROSE, 1, 1, 0, 120);

        Slot last = layout.slots().get(layout.slots().size() - 1);
        assertEquals(OverlayLayout.BODY_TAIL, last.height(), "the tail is the last thing placed");
        assertFalse(last.interactive(), "and nothing can click empty padding");
        assertEquals(layout.height(), last.bottom(),
                "the tail has to be counted, or the content height stops BODY_TAIL short of the content");
    }

    @Test
    void aTrailingGapWouldBeInvisibleToTheHeightWhichIsWhyTheTailIsARow() {
        // The mechanism, demonstrated rather than described. Two builds of one row, one closed with a
        // gap and one with a placed row, and the heights differ by exactly the row's height.
        //
        // This is the general property of the kit rather than a fact about the overlay, and it is
        // asserted here because here is where it bit: the same two lines read as though they reserve
        // the same space.
        assertEquals(20, Stack.stack().row("a", 20).gap(OverlayLayout.BODY_TAIL).build(120, FONT).height(),
                "a gap advances the cursor and places nothing, so it is not in the height");
        assertEquals(20 + OverlayLayout.BODY_TAIL,
                Stack.stack().row("a", 20).row(null, OverlayLayout.BODY_TAIL).build(120, FONT).height(),
                "a row with a null key is placed, counted, and still not clickable");
    }

    @Test
    void anEmptySectionTakesTheSameRoomAsAOneItemSection() {
        // The rule the old code carried a comment to remember. Here there is one measure rather than
        // two, so the assertion is less "does it agree" and more "is the rule still what it was" --
        // which is worth keeping, because the empty state is what a quest with no rewards looks like
        // and a section that collapsed would move every row under it.
        for (int width : new int[] {60, 120, 200}) {
            assertEquals(build(PROSE, 1, 1, 0, width).height(), build(PROSE, 0, 0, 0, width).height(),
                    "at width " + width);
            assertEquals(build(PROSE, 3, 2, 0, width).height() - build(PROSE, 2, 1, 0, width).height(),
                    OverlayLayout.ROW_ADVANCE * 2,
                    "each extra row costs one pitch, however many there already were");
        }
    }

    // ------------------------------------------------------------------
    // What is placed, and what is absent
    // ------------------------------------------------------------------

    @Test
    void requiresIsAbsentEntirelyWhenThereIsNothingToRequire() {
        // A real asymmetry rather than tidiness: a quest with no prerequisites should not say
        // "REQUIRES: nothing", it should not have the section. The layout must omit it too, or the
        // screen draws a heading for a slot that does not exist -- or worse, the heading is placed and
        // the rows are not.
        Layout none = build(PROSE, 1, 1, 0, 120);
        assertNull(none.slot(OverlayLayout.REQUIRES_HEADING),
                "no prerequisites means no heading, not an empty one");

        Layout one = build(PROSE, 1, 1, 1, 120);
        assertNotNull(one.slot(OverlayLayout.REQUIRES_HEADING));

        // Exactly one heading's worth plus one row: gap(10) + line(10) + gap(6) + 14.
        assertEquals(10 + OverlayLayout.LINE_HEIGHT + 6 + OverlayLayout.DEP_ADVANCE,
                one.height() - none.height());
    }

    @Test
    void everyInteriorSectionIsAlwaysThereEvenWhenEmpty() {
        // Tasks and rewards are the opposite of REQUIRES: present with an empty state, because a
        // quest always has tasks and rewards to report on. Asserted as a pair with the test above
        // so a change that made all three conditional, or none of them, fails something. The
        // description sits on the REQUIRES side now: a reader's card with no description has no
        // description block at all, not an empty one.
        Layout layout = build(NOTHING, 0, 0, 0, 120);
        assertNotNull(layout.slot(OverlayLayout.TASKS_HEADING));
        assertNotNull(layout.slot(OverlayLayout.REWARDS_HEADING));
        assertNull(layout.slot(OverlayLayout.NO_DESCRIPTION),
                "no description means no description block, not an empty one");
        assertNotNull(layout.slot(OverlayLayout.NO_TASKS));
        assertNotNull(layout.slot(OverlayLayout.NO_REWARDS));

        // The editor still places its empty-description row: the field's click target.
        Layout editing = OverlayLayout.stack(prose(NOTHING, 120), 0, 0, 0, true,
                OverlayLayout.Reveal.ALL).build(120, FONT);
        assertNotNull(editing.slot(OverlayLayout.NO_DESCRIPTION));
    }

    @Test
    void everyKeyTheScreenLooksUpIsPlaced() {
        // The keys are the interface between this class and the drawing, and the screen looks them up
        // by name. A missing one is a paragraph that silently does not draw -- no exception, no log,
        // just a gap -- so the contract is asserted rather than assumed.
        Layout layout = build(PROSE, 3, 2, 4, 200);

        for (int i = 0; i < PROSE.size(); i++) {
            assertNotNull(layout.slot(OverlayLayout.proseKey(i)), "paragraph " + i);
        }
        for (int i = 0; i < 3; i++) {
            assertNotNull(layout.slot(OverlayLayout.taskKey(i)), "task " + i);
        }
        for (int i = 0; i < 2; i++) {
            assertNotNull(layout.slot(OverlayLayout.rewardKey(i)), "reward " + i);
        }
        for (int i = 0; i < 4; i++) {
            assertNotNull(layout.slot(OverlayLayout.dependencyKey(i)), "dependency " + i);
        }

        // And past the end: there is no fourth task, and a slot that did exist there would be drawn by
        // the screen's loop under a key it never asked for.
        assertNull(layout.slot(OverlayLayout.taskKey(3)));
        assertNull(layout.slot(OverlayLayout.rewardKey(2)));
        assertNull(layout.slot(OverlayLayout.dependencyKey(4)));
        assertNull(layout.slot(OverlayLayout.proseKey(PROSE.size())));
    }

    @Test
    void theKeysForDifferentSectionsCannotCollide() {
        // Generated with a prefix rather than written as bare indices, and this is why: a task row and
        // a reward row are both "the first row", and two bare integers would make them one slot -- the
        // screen would draw a reward into a task's rectangle and nothing would report it.
        assertFalse(OverlayLayout.taskKey(0).equals(OverlayLayout.rewardKey(0)),
                "taskKey(0) and rewardKey(0) are different keys");
        assertFalse(OverlayLayout.taskKey(0).equals(OverlayLayout.dependencyKey(0)));
        assertFalse(OverlayLayout.proseKey(0).equals(OverlayLayout.taskKey(0)));
        // A null key and a missing key are also different from every generated one, which is what keeps
        // a heading's slot from being found by a lookup the screen meant for a row.
        assertFalse(OverlayLayout.taskKey(0).equals(null));
    }

    // ------------------------------------------------------------------
    // Where things are
    // ------------------------------------------------------------------

    @Test
    void rowsAreIndentedAndProseIsNot() {
        // The old drawing wrote `bodyLeft + 8` and `bodyWidth - 16` at seven call sites and `bodyLeft`
        // for prose. The indent is the layout's now, which is what makes it one number instead of two
        // written out fourteen times -- so it has to actually be applied, and applied to exactly the
        // rows.
        int width = 200;
        Layout filled = build(PROSE, 1, 1, 1, width);

        for (Object key : List.of(OverlayLayout.taskKey(0), OverlayLayout.rewardKey(0),
                OverlayLayout.dependencyKey(0))) {
            Slot slot = filled.slot(key);
            assertNotNull(slot, key + " should be placed");
            assertEquals(OverlayLayout.ROW_INDENT, slot.x(), key + " starts at the row indent");
            assertEquals(width - OverlayLayout.ROW_INDENT * 2, slot.width(), key + " is inset on both sides");
        }

        // The empty states are rows too, and indented the same. They are not in the layout above,
        // because an empty state is only placed when its list is empty -- which is the rule asserted
        // in everyInteriorSectionIsAlwaysThereEvenWhenEmpty. (The description has no empty state on
        // a reader's card anymore; the editor's surviving row is asserted there too.)
        Layout empty = build(NOTHING, 0, 0, 0, width);
        for (Object key : List.of(OverlayLayout.NO_TASKS, OverlayLayout.NO_REWARDS)) {
            Slot slot = empty.slot(key);
            assertNotNull(slot, key + " should be placed");
            assertEquals(OverlayLayout.ROW_INDENT, slot.x(), key + " is a row, so it is indented too");
            assertEquals(width - OverlayLayout.ROW_INDENT * 2, slot.width());
        }

        // Prose and headings start at the body's left edge, not at the indent: they are the body's own
        // text, and indenting them would put a description's first line out of line with its sections.
        assertEquals(0, filled.slot(OverlayLayout.proseKey(0)).x());
        assertEquals(0, filled.slot(OverlayLayout.TASKS_HEADING).x());
    }

    @Test
    void theHeadingRuleSitsOnTheHeadingsOwnLine() {
        // SECTION_LEAD is where the screen draws the rule under a heading, measured from the heading's
        // top. It has to be the line height, or the rule is drawn through the glyphs or floating below
        // them -- and the screen has no way to notice, because it just draws a rectangle at that y.
        assertEquals(OverlayLayout.LINE_HEIGHT, OverlayLayout.SECTION_LEAD);

        // And the heading's slot must be tall enough to contain the line the rule sits under.
        Layout layout = build(NOTHING, 0, 0, 0, 120);
        Slot heading = layout.slot(OverlayLayout.TASKS_HEADING);
        assertTrue(heading.height() >= OverlayLayout.SECTION_LEAD,
                "the heading's slot is " + heading.height() + ", the rule is drawn at "
                        + OverlayLayout.SECTION_LEAD);
    }

    @Test
    void everySlotIsInsideTheBodyColumn() {
        // Swept over widths, because the failure is a row drawn outside its own column and the margin
        // for it depends on the width. The indent is taken off both sides, so a body narrower than
        // twice the indent is the case that clamps rather than the one that goes negative.
        for (int width : new int[] {0, 4, 8, 16, 17, 40, 120, 300}) {
            Layout layout = build(PROSE, 2, 2, 2, width);
            for (Slot slot : layout.slots()) {
                // These two hold at every width, including zero, and they are the pair a renderer needs:
                // a negative width is a rectangle drawn backwards, which is a visible fault rather than
                // an empty one.
                assertTrue(slot.x() >= 0, "x " + slot.x() + " at width " + width);
                assertTrue(slot.width() >= 0, "negative width at width " + width);

                // Containment, above the width at which the element has a minimum of its own. There
                // are two, and both are the kit's stated behaviour rather than a tolerable wart:
                //
                //   * A text slot needs one glyph. TextWrap takes one character even in a column too
                //     narrow for it, because the loop has to make progress and a line one character too
                //     wide is a better failure than a hang. At this measure a glyph is six pixels, so
                //     below six a prose slot is deliberately wider than its column.
                //   * A row needs its own indent. A row's slot is the column less eight pixels a side,
                //     and below sixteen that goes negative, the width clamps to zero and the x stays at
                //     the indent -- so a row's slot ends at 8 in a zero-width column. There is nothing
                //     to draw and the empty slot says so.
                //
                // My first version asserted plain containment at every width, and failed on the
                // zero-width case with the message it should have had. Asserting the two separately is
                // the fix rather than narrowing the sweep: what is true everywhere is still checked
                // everywhere, above, and the exception is named where it applies instead of being
                // swept out of sight.
                int minimum = slot.x() > 0 ? slot.x() : 6;
                if (width >= minimum) {
                    assertTrue(slot.right() <= width,
                            "slot starts at " + slot.x() + " and ends at " + slot.right()
                                    + ", column is " + width);
                }
            }
        }
    }

    @Test
    void slotsArePlacedTopToBottomWithNoOverlap() {
        // The property the kit's whole stacking engine is for, asserted on this caller as well: two
        // rows in the same place would draw over each other, and this is the composition most likely
        // to produce it -- three sections, empty states, and a tail.
        Layout layout = build(PROSE, 2, 0, 1, 160);

        for (int i = 1; i < layout.slots().size(); i++) {
            Slot previous = layout.slots().get(i - 1);
            Slot current = layout.slots().get(i);
            assertTrue(current.y() >= previous.bottom(),
                    "slot " + i + " starts at " + current.y() + ", above " + previous.bottom());
        }
        assertEquals(layout.height(), layout.slots().get(layout.slots().size() - 1).bottom());
    }

    @Test
    void theSectionsAppearInOrder() {
        // Order matters to the reader and to nothing else in the code, which is exactly the sort of
        // thing a refactor can quietly reorder: build a stack in a different sequence and every
        // assertion above still passes.
        Layout layout = build(PROSE, 1, 1, 1, 200);

        int tasks = layout.slot(OverlayLayout.TASKS_HEADING).y();
        int task = layout.slot(OverlayLayout.taskKey(0)).y();
        int rewards = layout.slot(OverlayLayout.REWARDS_HEADING).y();
        int reward = layout.slot(OverlayLayout.rewardKey(0)).y();
        int prose = layout.slot(OverlayLayout.proseKey(0)).y();
        int requires = layout.slot(OverlayLayout.REQUIRES_HEADING).y();
        int dependency = layout.slot(OverlayLayout.dependencyKey(0)).y();

        assertTrue(tasks < task, "a heading comes before its rows");
        assertTrue(task < rewards, "tasks come before rewards");
        assertTrue(rewards < reward);
        assertTrue(reward < prose, "the description comes after the two sections it describes");
        assertTrue(prose < requires, "and the way in stays the last section");
        assertTrue(requires < dependency);
    }

    // ------------------------------------------------------------------
    // The one place it differs from the old rule
    // ------------------------------------------------------------------

    @Test
    void aHeadingTooNarrowForItsOwnWordIsTallerThanTheOldRule() {
        // The single deliberate divergence, and it is asserted rather than left in a comment.
        //
        // The old measure reserved one hardcoded line for a heading -- 8 + 16 for TASKS, whatever the
        // column was. The stack measures the word, so a body narrower than "REQUIRES" wraps it and the
        // section is taller. Two extra lines here: REWARDS is 42 pixels in a 40-pixel column and
        // REQUIRES is 48.
        //
        // It is the safe direction. Being taller than the drawing needs leaves a little slack in the
        // scroll range; being shorter is content you cannot reach, with nothing reporting it. And the
        // widths where it happens are ones where the whole panel is unusable anyway.
        int width = 40;
        int old = oldMeasure(NOTHING, 0, 0, 1, width);
        int now = build(NOTHING, 0, 0, 1, width).height();

        assertEquals(old + 2 * OverlayLayout.LINE_HEIGHT, now,
                "REWARDS and REQUIRES each take one more line than the old fixed reservation");

        // And above the widest heading the two agree exactly -- so the divergence is bounded, and is
        // not the sweep in the first test having been quietly narrowed to hide something.
        for (int roomy : new int[] {48, 60, 90, 200}) {
            assertEquals(oldMeasure(NOTHING, 0, 0, 1, roomy), build(NOTHING, 0, 0, 1, roomy).height(),
                    "at width " + roomy + " every heading fits on one line and they agree");
        }
    }

    // ------------------------------------------------------------------
    // Refusals
    // ------------------------------------------------------------------

    @Test
    void aNegativeCountIsRefusedRatherThanFoldedIntoAnEmptyState() {
        // Silently treating -1 as an empty list would place an empty state where the caller asked for
        // rows, and the screen would draw "Nothing required" for a quest whose task list is a bug.
        assertThrows(IllegalArgumentException.class, () -> OverlayLayout.stack(prose(PROSE, 120), -1, 0, 0));
        assertThrows(IllegalArgumentException.class, () -> OverlayLayout.stack(prose(PROSE, 120), 0, -1, 0));
        assertThrows(IllegalArgumentException.class, () -> OverlayLayout.stack(prose(PROSE, 120), 0, 0, -1));
    }

    @Test
    void aMissingDescriptionIsRefusedRatherThanTreatedAsEmpty() {
        // Empty and absent are different: absent means the caller has a bug upstream -- a null from a
        // cache entry that was not found -- and placing an empty state over it would hide it.
        assertThrows(NullPointerException.class, () -> OverlayLayout.stack(null, 0, 0, 0));
    }

    // ------------------------------------------------------------------
    // The blank line, and the editor that has to agree with it
    // ------------------------------------------------------------------

    /**
     * An empty paragraph is a line, and it takes the height of one.
     *
     * <h2>Why this is asserted rather than assumed</h2>
     *
     * <p>Because a text element measuring {@code ""} comes out <b>no lines tall</b> -- {@link TextWrap}'s
     * "nothing in, nothing out" -- so before this, a blank element reserved only its paragraph gap. The
     * description editor draws a blank hard line one line tall, and the field is transparent (so the text
     * does not move when it is clicked), so the two models disagreeing did not merely misplace a gap: the
     * reader's copy and the editor's copy of every paragraph <i>after</i> the blank line were drawn one
     * line apart, on top of each other. That is the overprinted text from play, and this is the half of
     * the fix that lives in the layout. The other half is the editor's own drawing, which is skipped
     * while the editor is open -- see {@code InlineEdit.replaces}.
     */
    @Test
    void anEmptyParagraphIsALineLikeAnyOther() {
        List<String> withBlank = List.of("one", "", "two");
        Layout blank = build(withBlank, 0, 0, 0, 120);
        Layout without = build(List.of("one", "two"), 0, 0, 0, 120);

        // One line and its gap more than the same text without the blank element.
        assertEquals(without.height() + OverlayLayout.LINE_HEIGHT + OverlayLayout.PARAGRAPH_GAP,
                blank.height(), "the blank line takes a line, and its gap");

        // And the paragraph after it sits below it by exactly that.
        int first = blank.slot(OverlayLayout.proseKey(0)).y();
        int third = blank.slot(OverlayLayout.proseKey(2)).y();
        assertEquals(2 * (OverlayLayout.LINE_HEIGHT + OverlayLayout.PARAGRAPH_GAP), third - first,
                "one line, its gap, the blank line, and its gap");

        // A blank element still gets a slot of its own, and it is a line tall.
        assertEquals(OverlayLayout.LINE_HEIGHT, blank.slot(OverlayLayout.proseKey(1)).height());
    }

    /**
     * The editor's model and the reader's layout put the same visual line at the same y.
     *
     * <h2>The contract the description editor rests on</h2>
     *
     * <p>{@code ArmatureTextArea} draws the value it is editing at its own pitch, and the layout under it
     * was built by {@code OverlayLayout}. If the two disagree about where a line goes, the text moves --
     * and because the field is transparent, a disagreement is not hidden: both drawings are visible at
     * once. The fixture is the shape that broke it: a paragraph, a blank line, then a paragraph that
     * wraps, because a blank element was the input the two models disagreed about.
     *
     * <p>Measured with the same six-pixels-a-character font the rest of this file uses; the real font has
     * the same shape and both models measure with it, so a disagreement here is a disagreement there.
     */
    /**
     * The editor's model and the reader's layout put the same visual line at the same y.
     *
     * <h2>The contract the description editor rests on</h2>
     *
     * <p>{@code ArmatureTextArea} draws the value it is editing at its own pitch, and the layout under it
     * was built by {@code OverlayLayout}. If the two disagree about where a line goes, the text moves --
     * and because the field is transparent, a disagreement is not hidden: both drawings are visible at
     * once. The fixture is the shape that broke it: a paragraph, a blank line, then a paragraph that
     * wraps, because a blank element was the input the two models disagreed about.
     *
     * <p>Measured with the same six-pixels-a-character font the rest of this file uses; the real font has
     * the same shape and both models measure with it, so a disagreement here is a disagreement there.
     */
    @Test
    void theEditorAndTheReaderAgreeOnWhereEveryLineIs() {
        List<String> paragraphs = List.of(
                "Four planks, a square, and suddenly you have options.",
                "",
                "This one also counts itself complete, and its reward is deliberately the",
                "boring one -- eight sticks, which is most of what the next quest asks",
                "you for.");
        int width = 360;                                  // sixty characters at six pixels each
        Layout layout = build(paragraphs, 0, 0, 0, width);

        String joined = String.join("\n", paragraphs);
        List<TextArea.Span> lines = TextArea.wrap(joined, width, FONT::width);

        // The block's own origin, subtracted below: the prose sits after the tasks and rewards now, so
        // the two models agree on the *spacing* they place -- which is what this compares -- and not on
        // a y that is the body's top only while the prose is the first thing on the card.
        int base = layout.slot(OverlayLayout.proseKey(0)).y();

        for (int paragraph = 0; paragraph < paragraphs.size(); paragraph++) {
            int start = 0;
            for (int i = 0; i < paragraph; i++) {
                start += paragraphs.get(i).length() + 1;   // + the newline between them
            }
            int visualLine = TextArea.lineOf(start, lines);
            int slotY = layout.slot(OverlayLayout.proseKey(paragraph)).y() - base;
            int drawnY = TextArea.lineTop(lines, visualLine, OverlayLayout.LINE_HEIGHT,
                    OverlayLayout.PARAGRAPH_GAP);

            assertEquals(slotY, drawnY, "paragraph " + paragraph + " \"" + paragraphs.get(paragraph) + "\"");
        }
    }

    // ------------------------------------------------------------------
    // The frame around the prose
    // ------------------------------------------------------------------

    /**
     * The description's frame: the column opened out by the text area's padding, and never narrower than
     * the prose placed inside it.
     *
     * <h2>Every half was reported from play, and the second half twice</h2>
     *
     * <p>It used to be the first paragraph's slot opened out by nothing. The width half: a description
     * whose first line is short and whose third is long had the long line running out through the frame's
     * right edge -- *"it doesnt cover the entire width"* -- because the frame's width was the first
     * paragraph's longest line. The padding half: its top, left and bottom lines sat exactly on the glyphs
     * -- *"the borders around it are too cramped, there is no padding"*. And then, after both were fixed,
     * a report came back showing the frame still ending short of a long line -- so the second assertion
     * below is the half that says <b>a rectangle drawn around a block contains the block</b>, whatever
     * column the caller believed in. The fixture for it builds the layout at a width the frame is then
     * told less about, which is the only way those two can disagree.
     */
    @Test
    void theDescriptionFrameSpansTheColumnAndIsPadded() {
        List<String> uneven = List.of("Short.", "A later paragraph that is much longer than the first one");
        int width = 240;
        Layout layout = build(uneven, 0, 0, 0, width);
        Slot first = layout.slot(OverlayLayout.proseKey(0));
        Slot last = layout.slot(OverlayLayout.proseKey(1));
        assertNotNull(first);
        assertNotNull(last);

        // The fixture has to exercise the bug: the first paragraph is narrower than the column.
        assertTrue(first.width() < width, "the first paragraph is a short line");
        int widest = Math.max(first.right(), last.right());

        Slot frame = OverlayLayout.proseFrame(first, last.bottom(), widest, width);
        assertEquals(OverlayLayout.PROSE_PAD, first.x() - frame.x(), "padded on the left");
        assertEquals(OverlayLayout.PROSE_PAD, first.y() - frame.y(), "and above the first line");
        assertEquals(OverlayLayout.PROSE_PAD, frame.bottom() - last.bottom(), "and below the last");
        assertEquals(width + OverlayLayout.PROSE_PAD * 2, frame.width(),
                "as wide as the column, not as the first paragraph's longest line");
        assertTrue(frame.right() >= widest + OverlayLayout.PROSE_PAD,
                "so the longest line is inside it with a pad, not poking out");

        // The half that has been wrong twice: the frame contains the prose even when the column it was
        // told about is narrower than the lines the layout actually placed.
        Slot narrow = OverlayLayout.proseFrame(first, last.bottom(), widest, first.right());
        assertTrue(narrow.right() >= widest + OverlayLayout.PROSE_PAD,
                "a frame built from a narrower column still contains the widest line");
        assertEquals(widest - first.x() + OverlayLayout.PROSE_PAD * 2, narrow.width(),
                "it grows to the prose, not to the column");

        // The empty state gets the same frame: the one row, the whole column, padded the same way.
        // Built editing, which is the only mode that still places the row: a reader's card has no
        // description block at all when there is no description.
        Layout empty = OverlayLayout.stack(prose(NOTHING, width), 0, 0, 0, true,
                OverlayLayout.Reveal.ALL).build(width, FONT);
        Slot line = empty.slot(OverlayLayout.NO_DESCRIPTION);
        assertNotNull(line);
        Slot emptyFrame = OverlayLayout.proseFrame(line, line.bottom(), line.right(), width);
        assertEquals(OverlayLayout.PROSE_PAD, line.x() - emptyFrame.x());
        assertEquals(width + OverlayLayout.PROSE_PAD * 2, emptyFrame.width());
    }

    @Test
    void theFramesPaddingIsTheTextAreasOwn() {
        // One number, not two that look alike: the border is drawn around the same rectangle the text
        // area draws its text in, so the padding has to be the area's -- a frame with a padding of its own
        // is a rectangle that only resembles the editor's box.
        assertEquals(ArmatureTextArea.PAD, OverlayLayout.PROSE_PAD);
    }

    @Test
    void aProseParagraphsOwnLineHeightIsTheSpaceReserved() {
        // A heading drawn at one and a half times the font arrives with a pitch of its own, and the space
        // reserved has to be the space the drawing takes -- or the next paragraph sits on its last line.
        Layout tall = OverlayLayout.stack(List.of(new OverlayLayout.Prose(List.of("one", "two"), 30, 15)),
                0, 0, 0).build(200, FONT);
        assertEquals(30, tall.slot(OverlayLayout.proseKey(0)).height(), "two lines at fifteen");

        Layout plain = OverlayLayout.stack(List.of(new OverlayLayout.Prose(List.of("one", "two"), 30)),
                0, 0, 0).build(200, FONT);
        assertEquals(20, plain.slot(OverlayLayout.proseKey(0)).height(),
                "and the ordinary pitch is untouched: two lines at ten");
    }

    @Test
    void aProseWithNoLineHeightIsRefusedRatherThanDrawnFlat() {
        assertThrows(IllegalArgumentException.class,
                () -> new OverlayLayout.Prose(List.of("one"), 20, 0));
        assertThrows(IllegalArgumentException.class,
                () -> new OverlayLayout.Prose(List.of(), 20));
    }

    @Test
    @DisplayName("the prerequisite bar spans the body and is never empty, whatever it is given")
    void theDependencyBarAnswers() {
        // One function for the rectangle a prerequisite row draws as and answers to, because the drawn one
        // and the pressed one were two expressions and disagreed -- see `OverlayLayout.dependencyBar`. The
        // property that broke is asserted first: a bar of no width is a row that answers nothing.
        Slot row = new Slot("requires:0", 120, 40, 180, 14);

        Slot wide = OverlayLayout.dependencyBar(row, 400);
        assertTrue(wide.width() > 0, "a bar with a body to its right has a width: " + wide);
        assertEquals(400, wide.right(), "and reaches the body's edge, which is what 'the whole bar' means");
        assertTrue(wide.contains(row.x(), row.y() + 1), "a press on the row's own box lands in it");
        assertTrue(wide.contains(400 - 1, row.y() + 1), "and so does one on its last pixel inside the edge");
        assertEquals(row.height() + 2, wide.height(), "its height is the row's, with a pixel either side");

        // **The fault that shipped**: a body edge at or left of the row -- which is what a *content*
        // coordinate produced -- must still leave a bar that answers, at least as wide as the row itself.
        for (int bodyRight : List.of(0, 60, row.right(), 4000)) {
            Slot bar = OverlayLayout.dependencyBar(row, bodyRight);
            assertTrue(bar.width() >= row.width(),
                    () -> "body right " + bodyRight + " collapsed the bar: " + bar);
            assertTrue(bar.contains(row.x() + row.width() / 2, row.y() + 1),
                    () -> "body right " + bodyRight + " left the row's middle dead: " + bar);
        }
    }
}