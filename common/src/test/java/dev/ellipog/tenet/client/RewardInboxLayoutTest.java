package dev.ellipog.tenet.client;

import dev.ellipog.armature.client.ui.kit.Layout;
import dev.ellipog.armature.client.ui.kit.Measure;
import dev.ellipog.armature.client.ui.kit.Slot;
import dev.ellipog.tenet.progress.ClaimFilter;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The claim menu's arithmetic: which rows exist, how tall they are, where each of the three columns
 * lands, and what a state toggle admits.
 *
 * <h2>Why this is asserted and not eyeballed</h2>
 *
 * <p>The drawing, the action column and the fold's hit test all read the same {@link Layout}, so an
 * indent that is off by a pixel is not a cosmetic fault — it is a row whose button is drawn where it
 * cannot be pressed, or a press that folds a row the player was pointing beside. One derivation,
 * asserted over real rectangles, is what keeps them the same derivation; no client is needed, because
 * the rows are integers.
 *
 * <h2>The one invariant the redesign exists for</h2>
 *
 * <p>A reward row is indented under its quest, and the reward and action columns have to
 * stay on <b>one vertical axis</b> anyway — that is what makes a column a column rather than a
 * per-row arrangement. {@link Columns} is the test of that, and it is the reason the columns are
 * measured back from the row's right edge rather than forward from its left.
 */
@DisplayName("the claim menu's layout")
class RewardInboxLayoutTest {

    private static final Measure MEASURE = Measure.monospace(6, 9);

    /** A column wide enough for three cells, so the arithmetic has room to be wrong in. */
    private static final int COLUMN = 600;

    private static RewardInboxLayout.Row chapter(String chapterId) {
        return RewardInboxLayout.Row.chapter(chapterId, "1 \u00b7 The Basics", 3,
                RewardInboxLayout.Status.READY);
    }

    private static RewardInboxLayout.Row quest(String chapterId, String questId) {
        return RewardInboxLayout.Row.quest(chapterId, questId, "A Quest", 2,
                RewardInboxLayout.Status.READY);
    }

    private static RewardInboxLayout.Row reward(String questId, int index) {
        return RewardInboxLayout.Row.reward("basics", questId, index, "An Item", 3,
                RewardInboxLayout.Status.READY);
    }

    @Test
    @DisplayName("a collapsed chapter is its banner alone, and the gap closes up behind it")
    void collapsedChapterIsTheBannerAlone() {
        Layout one = RewardInboxLayout.build(List.of(chapter("basics")), COLUMN, MEASURE);
        assertEquals(RewardInboxLayout.CHAPTER_HEIGHT, one.height(), "one banner, one row's height");

        Layout two = RewardInboxLayout.build(List.of(chapter("basics"), chapter("stone")), COLUMN,
                MEASURE);
        assertEquals(RewardInboxLayout.CHAPTER_HEIGHT * 2 + RewardInboxLayout.CHAPTER_GAP, two.height(),
                "two chapters are separated by the chapter gap, and a fold leaves no gap behind");
    }

    @Test
    @DisplayName("a chapter's banner is taller than a quest's row, and starts a group")
    void bannersAreTallerThanRows() {
        Layout layout = RewardInboxLayout.build(List.of(chapter("basics"), quest("basics", "a")),
                COLUMN, MEASURE);
        Slot banner = layout.slot(RewardInboxLayout.chapterKey("basics"));
        Slot row = layout.slot(RewardInboxLayout.questKey("a"));

        assertEquals(RewardInboxLayout.CHAPTER_HEIGHT, banner.height(), "a banner carries a whole chapter");
        assertEquals(RewardInboxLayout.HEADER_HEIGHT, row.height(), "a quest's row is one line");
        assertEquals(banner.bottom() + RewardInboxLayout.SECTION_GAP, row.y(),
                "and it sits the section gap under the banner it belongs to");
    }

    @Test
    @DisplayName("an expanded quest's rewards sit under it, indented, one row gap apart")
    void expandedRewardsAreIndented() {
        Layout layout = RewardInboxLayout.build(
                List.of(quest("basics", "a"), reward("a", 0), reward("a", 1)), COLUMN, MEASURE);

        Slot head = layout.slot(RewardInboxLayout.questKey("a"));
        Slot first = layout.slot(RewardInboxLayout.rewardKey("a", 0));
        Slot second = layout.slot(RewardInboxLayout.rewardKey("a", 1));

        assertEquals(0, head.x(), "a header starts at the column's left edge");
        assertEquals(RewardInboxLayout.REWARD_INDENT, first.x(),
                "a reward is indented under the header it belongs to");
        assertEquals(RewardInboxLayout.HEADER_HEIGHT + RewardInboxLayout.ROW_GAP, first.y(),
                "and the first sits directly under the header");
        assertEquals(first.bottom() + RewardInboxLayout.ROW_GAP, second.y(),
                "rewards are packed at the row gap");
        assertEquals(head.right(), first.right(),
                "the indent narrows the left edge and nothing else");
    }

    @Nested
    @DisplayName("the three columns")
    class Columns {

        /**
         * The invariant: an indented reward row's reward and action cells are the <b>same rectangles</b>
         * as its unindented header's, and only the context cell moves.
         *
         * <p>This is the whole reason the columns are measured from the right. Measured from the left
         * they would shift by the indent, and a column of reward icons would step sideways down the
         * list — which reads as a broken layout rather than as a deliberate indent.
         */
        @Test
        @DisplayName("hold one vertical axis across an indented row and its header")
        void holdOneAxisAcrossTheIndent() {
            Layout layout = RewardInboxLayout.build(
                    List.of(quest("basics", "a"), reward("a", 0)), COLUMN, MEASURE);
            Slot head = layout.slot(RewardInboxLayout.questKey("a"));
            Slot child = layout.slot(RewardInboxLayout.rewardKey("a", 0));

            for (RewardInboxLayout.Column which : List.of(RewardInboxLayout.Column.REWARDS,
                    RewardInboxLayout.Column.ACTION)) {
                // x and width, not the whole slot: the two rows are on different lines by definition,
                // and the claim is about the axis they share rather than about being the same rectangle.
                Slot onHead = RewardInboxLayout.column(head, which);
                Slot onChild = RewardInboxLayout.column(child, which);
                assertEquals(onHead.x(), onChild.x(),
                        "the " + which + " cell is on one axis, indent or not");
                assertEquals(onHead.width(), onChild.width(), "and the same width");
            }
            assertEquals(RewardInboxLayout.REWARD_INDENT,
                    RewardInboxLayout.column(child, RewardInboxLayout.Column.CONTEXT).x()
                            - RewardInboxLayout.column(head, RewardInboxLayout.Column.CONTEXT).x(),
                    "and only the context cell moves with the indent, by exactly the indent");
        }

        @Test
        @DisplayName("sit in order, left to right, and never overlap")
        void sitInOrder() {
            Layout layout = RewardInboxLayout.build(List.of(chapter("basics")), COLUMN, MEASURE);
            Slot row = layout.slot(RewardInboxLayout.chapterKey("basics"));

            Slot context = RewardInboxLayout.column(row, RewardInboxLayout.Column.CONTEXT);
            Slot rewards = RewardInboxLayout.column(row, RewardInboxLayout.Column.REWARDS);
            Slot action = RewardInboxLayout.column(row, RewardInboxLayout.Column.ACTION);

            assertTrue(context.right() <= rewards.x(), "the context cell ends before the reward one");
            assertTrue(rewards.right() <= action.x(), "the reward cell ends before the action one");
            assertEquals(row.y(), context.y(), "the drawn cells share their row's line");
            assertEquals(row.y(), rewards.y(), "and so does the reward cell");
            assertEquals(row.height(), context.height(), "the drawn cells are their row's height");
        }

        /**
         * The banner is taller than the button it carries, and the button is centred in it.
         *
         * <p>This is the property that was wrong and that a screenshot showed: the action inherited its
         * row's height, so on the banner — the one row taller than a button — the button filled the row
         * edge to edge, and a section heading whose whole height is a button reads as a button with a
         * title beside it.
         */
        @Test
        @DisplayName("leave a banner taller than its button, with the button centred in it")
        void theBannerIsTallerThanItsButton() {
            Layout layout = RewardInboxLayout.build(List.of(chapter("basics")), COLUMN, MEASURE);
            Slot row = layout.slot(RewardInboxLayout.chapterKey("basics"));
            Slot action = RewardInboxLayout.column(row, RewardInboxLayout.Column.ACTION);

            assertEquals(RewardInboxLayout.ACTION_HEIGHT, action.height(),
                    "a row's action is one height, whatever its row is");
            assertTrue(row.height() > action.height(),
                    "and a banner is taller than the button in it: " + row.height() + " against "
                            + action.height());
            assertEquals(row.y() + (row.height() - action.height()) / 2, action.y(),
                    "with the button centred rather than pinned to the top");
            assertTrue(action.y() > row.y() && action.bottom() < row.bottom(),
                    "so there is air above and below it: " + action + " in " + row);
        }

        /**
         * The cell quest previews are drawn in must hold a count with its icons, which is what stops
         * the collision the screenshots showed.
         *
         * <p>It is a real invariant rather than a nicety: cells are packed left to right with an
         * overflow "+N", and a cell narrower than an icon plus its count forces everything into the
         * overflow. The chapter badge used to live here too — "%s/%s ready to claim" over a chapter's
         * whole reward count — and now rides inline after the chapter title, so this guards the quest
         * previews that remain.
         */
        @Test
        @DisplayName("hold the longest badge the banner can state")
        void theRewardCellHoldsTheBadge() {
            Layout layout = RewardInboxLayout.build(List.of(chapter("basics")), COLUMN, MEASURE);
            Slot row = layout.slot(RewardInboxLayout.chapterKey("basics"));
            Slot rewards = RewardInboxLayout.column(row, RewardInboxLayout.Column.REWARDS);

            // "1116 ready" -- a count in the thousands, which is what the screenshots' pack holds.
            int longest = MEASURE.width("1116 ready");
            assertTrue(rewards.width() >= longest,
                    "the reward cell is " + rewards.width() + " wide and the longest badge is " + longest
                            + "; a badge wider than its cell runs into the context column beside it");
        }

        @Test
        @DisplayName("put the action at the column's right edge, outside the row and inside the column")
        void theActionIsAtTheColumnsEdge() {
            Layout layout = RewardInboxLayout.build(List.of(chapter("basics"), reward("a", 0)), COLUMN,
                    MEASURE);

            for (String key : List.of(RewardInboxLayout.chapterKey("basics"),
                    RewardInboxLayout.rewardKey("a", 0))) {
                Slot row = layout.slot(key);
                Slot action = RewardInboxLayout.column(row, RewardInboxLayout.Column.ACTION);
                assertEquals(RewardInboxLayout.strip(row), action,
                        "the action cell is the strip the layout reserved");
                assertTrue(action.x() >= row.right(), "the action is outside the row's narrowed slot");
                assertEquals(COLUMN - RewardInboxLayout.STRIP_INSET, action.right(),
                        "and its outer edge is the column's, inset the same on every row");
            }
        }

        @Test
        @DisplayName("clamp rather than going negative when the row has no room for the cells")
        void narrowRowsClamp() {
            int narrow = RewardInboxLayout.ACTION_WIDTH + RewardInboxLayout.STRIP_INSET * 2 + 4;
            Layout layout = RewardInboxLayout.build(List.of(quest("basics", "a")), narrow, MEASURE);
            Slot row = layout.slot(RewardInboxLayout.questKey("a"));

            for (RewardInboxLayout.Column which : RewardInboxLayout.Column.values()) {
                Slot cell = RewardInboxLayout.column(row, which);
                assertTrue(cell.width() >= 0, which + " has a negative width on a narrow row: " + cell);
            }
        }

        @Test
        @DisplayName("name the row they belong to, so a hit test on a cell names a row and not a box")
        void cellsCarryTheirRowsKey() {
            Layout layout = RewardInboxLayout.build(List.of(quest("basics", "a")), COLUMN, MEASURE);
            Slot row = layout.slot(RewardInboxLayout.questKey("a"));

            for (RewardInboxLayout.Column which : RewardInboxLayout.Column.values()) {
                assertEquals(row.key(), RewardInboxLayout.column(row, which).key(),
                        "the " + which + " cell answers to its row's key");
            }
        }
    }

    @Test
    @DisplayName("a row's body is the row itself, which is what a fold is hit-tested in")
    void bodyIsTheRow() {
        Layout layout = RewardInboxLayout.build(List.of(chapter("basics")), COLUMN, MEASURE);
        Slot row = layout.slot(RewardInboxLayout.chapterKey("basics"));
        Slot body = RewardInboxLayout.body(row);
        Slot strip = RewardInboxLayout.strip(row);

        assertEquals(row.x(), body.x());
        assertEquals(row.right(), body.right());
        // Tested at the strip's own line rather than the row's top, because the action is centred in a
        // banner: a point at the row's top is above the button and would pass this without proving
        // anything about where the button is.
        assertFalse(strip.contains(body.right() - 1, strip.y() + strip.height() / 2),
                "the body ends where the action begins, so the row's press and its button's cannot "
                        + "overlap -- a click on Claim Chapter must never also fold the row");
    }

    @Test
    @DisplayName("a single row is a quest's shape, does not fold, and carries the reward's own index")
    void singleRowsDoNotFold() {
        RewardInboxLayout.Row single = RewardInboxLayout.Row.single("basics", "a", "A Quest", 0, 3,
                RewardInboxLayout.Status.READY);
        Layout layout = RewardInboxLayout.build(List.of(single), COLUMN, MEASURE);

        assertEquals(RewardInboxLayout.HEADER_HEIGHT, layout.height(), "one line, like a quest's row");
        assertFalse(single.expands(), "nothing to fold: the definition holds one reward");
        assertEquals(RewardInboxLayout.questKey("a"), single.key(),
                "the row answers to the quest's key, which is what its action button is placed by");
        assertEquals(0, single.rewardIndex(), "and it carries the reward's own index, not an assumption");
        assertTrue(chapter("basics").expands(), "a chapter folds");
        assertTrue(quest("basics", "a").expands(), "a quest header folds");
        assertFalse(reward("a", 0).expands(), "and a child does not");
    }

    @Test
    @DisplayName("the three key namespaces are disjoint, so a group and a chapter may share an id")
    void keysAreDisjoint() {
        assertEquals("chapter:stone_age", RewardInboxLayout.chapterKey("stone_age"));
        assertEquals("quest:stone_age", RewardInboxLayout.questKey("stone_age"));
        assertEquals("reward:stone_age:2", RewardInboxLayout.rewardKey("stone_age", 2));

        // A chapter and a quest really can be called the same thing -- the sidebar's own note records
        // why -- and the prefixes are what keeps one from hiding the other in a layout keyed by string.
        assertNotEquals(RewardInboxLayout.chapterKey("x"), RewardInboxLayout.questKey("x"));
        assertNotEquals(RewardInboxLayout.questKey("x"), RewardInboxLayout.rewardKey("x", 0));
        assertNull(RewardInboxLayout.build(List.of(chapter("basics")), COLUMN, MEASURE)
                        .slot(RewardInboxLayout.chapterKey("x")),
                "a key nothing declared answers null rather than a stale rectangle");
    }

    @Test
    @DisplayName("a row's status decides whether it can be pressed, and a banner carries its ready count")
    void keysAndStatuses() {
        assertTrue(reward("a", 0).pressable(), "a ready row has something to take");
        assertTrue(quest("basics", "a").pressable(), "and a listed header's claim is live");
        assertFalse(RewardInboxLayout.Row.reward("basics", "a", 0, "x", 1,
                RewardInboxLayout.Status.LOCKED).pressable(), "a gated row does not");
        assertFalse(RewardInboxLayout.Row.reward("basics", "a", 0, "x", 1,
                RewardInboxLayout.Status.CLAIMED).pressable(), "nor a collected one");
        assertTrue(chapter("basics").isChapter(), "a banner knows it is one");
        assertFalse(reward("a", 0).isChapter(), "and a reward does not");
        assertEquals(3, chapter("basics").count(),
                "a banner's count is the number of its rewards that are ready, which is what its badge "
                        + "states and what its press would hand over");
    }

    @Test
    @DisplayName("a ready reward row claims on press, and nothing else does")
    void rowPressClaimsOnlyReadyRewards() {
        assertTrue(BookRewards.claimsOnRowPress(reward("a", 0)),
                "a ready reward row is the button by another gesture");
        assertTrue(BookRewards.claimsOnRowPress(
                RewardInboxLayout.Row.single("basics", "a", "A Quest", 0, 3,
                        RewardInboxLayout.Status.READY)),
                "a single-reward quest's one row too");
        for (RewardInboxLayout.Status status : RewardInboxLayout.Status.values()) {
            boolean ready = status == RewardInboxLayout.Status.READY;
            assertEquals(ready, BookRewards.claimsOnRowPress(
                    RewardInboxLayout.Row.reward("basics", "a", 0, "x", 1, status)),
                    "a " + status + " reward row " + (ready ? "claims" : "keeps its behaviour"));
            assertEquals(ready, BookRewards.claimsOnRowPress(
                    RewardInboxLayout.Row.single("basics", "a", "A Quest", 0, 1, status)),
                    "a " + status + " single row " + (ready ? "claims" : "keeps its behaviour"));
            assertFalse(BookRewards.claimsOnRowPress(
                    RewardInboxLayout.Row.quest("basics", "a", "A Quest", 2, status)),
                    "a quest header folds rather than pays: " + status);
            assertFalse(BookRewards.claimsOnRowPress(
                    RewardInboxLayout.Row.chapter("basics", "1 \u00b7 The Basics", 3, status)),
                    "a chapter banner folds rather than pays: " + status);
        }
    }

    @Nested
    @DisplayName("the state toggle")
    class States {

        /** Every status crossed with both answers to "is this a choice". */
        private static final RewardInboxLayout.Status[] STATUSES = RewardInboxLayout.Status.values();

        @Test
        @DisplayName("Ready admits what is pressable, and keeps a press in flight on screen")
        void readyAdmitsWhatIsPressable() {
            for (RewardInboxLayout.Status status : STATUSES) {
                boolean admitted = RewardInboxLayout.State.READY.accepts(status, false);
                assertEquals(status == RewardInboxLayout.Status.READY
                                || status == RewardInboxLayout.Status.PENDING, admitted,
                        "Ready and a press in flight belong in the ready view; " + status + " does not");
            }
        }

        @Test
        @DisplayName("Choices admits only the rewards that ask a question")
        void choicesAdmitsOnlyQuestions() {
            for (RewardInboxLayout.Status status : STATUSES) {
                for (boolean choice : new boolean[] {true, false}) {
                    boolean admitted = RewardInboxLayout.State.CHOICES.accepts(status, choice);
                    boolean outstanding = status == RewardInboxLayout.Status.READY
                            || status == RewardInboxLayout.Status.PENDING;
                    assertEquals(outstanding && choice, admitted,
                            "a choice view holds outstanding questions and nothing else: " + status);
                }
            }
        }

        @Test
        @DisplayName("no view admits a locked or collected reward, and every view admits something")
        void lockedIsNeverAdmitted() {
            for (RewardInboxLayout.State state : RewardInboxLayout.State.values()) {
                for (boolean choice : new boolean[] {true, false}) {
                    assertFalse(state.accepts(RewardInboxLayout.Status.LOCKED, choice),
                            state + " admits a locked reward, which the server would refuse");
                    assertFalse(state.accepts(RewardInboxLayout.Status.CLAIMED, choice),
                            state + " admits a collected reward, and no view here is a history");
                }
                // Each view holds at least one of the two things it is about, or it would be a toggle
                // that always empties the list -- a control promising a view that cannot exist.
                boolean aboutChoices = state == RewardInboxLayout.State.CHOICES;
                assertTrue(state.accepts(RewardInboxLayout.Status.READY, aboutChoices),
                        state + " admits nothing at all, so its view is always empty");
            }
        }

        @Test
        @DisplayName("the sweep follows the view, and every view has one")
        void theSweepFollowsTheView() {
            assertEquals(ClaimFilter.ALL, RewardInboxLayout.State.READY.filter(),
                    "a view of everything outstanding sweeps everything outstanding");
            assertEquals(ClaimFilter.CHOICES, RewardInboxLayout.State.CHOICES.filter(),
                    "a view of questions sweeps questions, which is what its label promises");
            for (RewardInboxLayout.State state : RewardInboxLayout.State.values()) {
                assertNotNull(state.filter(),
                        state + " has no sweep, and every view here is about something still owed -- "
                                + "whether the button is drawn is the screen's question, not this one's");
            }
        }
    }
}
