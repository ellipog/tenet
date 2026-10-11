package dev.ellipog.tenet.client;

import dev.ellipog.armature.client.ui.kit.Insets;
import dev.ellipog.armature.client.ui.kit.Layout;
import dev.ellipog.armature.client.ui.kit.Measure;
import dev.ellipog.armature.client.ui.kit.Slot;
import dev.ellipog.armature.client.ui.kit.Stack;
import dev.ellipog.tenet.progress.ClaimFilter;

import java.util.List;
import java.util.Objects;

/**
 * The claim menu's composition: chapter banners, the quests under them, the rewards under those, and
 * the three columns every row is read in.
 *
 * <h2>Why this is a layout and not a panel</h2>
 *
 * <p>The same reason every other list in this codebase is one: everything worth being wrong about is
 * arithmetic on integers — does a reward row indent under the right header, does a row's action fit its
 * row, does a collapsed chapter contribute only its banner, and do two rows' reward columns share one
 * vertical axis — and arithmetic is the thing the tests can hold without a client. The screen takes a
 * {@link Layout} from here and asks it where each row and each column went; the drawing and the presses
 * are the screen's, because neither can happen without a game.
 *
 * <h2>The accordion, as rows rather than as a widget tree</h2>
 *
 * <p>A collapsed chapter is a caller that built its row list without that chapter's quests, and a
 * collapsed quest is one that left out its rewards — the same fold {@code QuestPanelLayout} uses for
 * its sections, and the reason the gaps go <i>before</i> each row: a fold leaves no stray gap where the
 * folded rows used to be. Expansion state is the caller's, never stored here, so a rebuild for a scroll
 * or a resize cannot lose it.
 *
 * <h2>The three columns, and why they are derived from the right edge</h2>
 *
 * <p>A reward row is indented under its quest, so a column taken from the row's <i>left</i> edge would
 * shift with the indent — and "the reward icons line up down the page" is the whole point of having
 * columns at all. Every row's right edge is the same (the indent narrows the left edge and nothing
 * else, which {@code RewardInboxLayoutTest} asserts), so the action and reward columns are measured
 * back from it and hold one axis across indented and unindented rows alike. Only the context column
 * moves with the indent, which is what makes an indented row read as indented.
 *
 * <p>{@link #column} keeps the row's own key on every cell, so a hit test on a column names the row it
 * belongs to rather than a rectangle — the same contract {@link #body} has.
 */
public final class RewardInboxLayout {

    /**
     * A chapter's banner: the fold marker, the chapter's icon and title, its badge, and Claim Chapter.
     *
     * <p>Taller than the action it carries, deliberately — see {@link #ACTION_HEIGHT}. At 22 it was the
     * same height as the button, so the button filled the row edge to edge and the banner read as a
     * button with a title beside it rather than as a section heading with a button in it. At 26 it was
     * still one row with a button in it; at 30 with a raised wash it reads as a banner.
     */
    public static final int CHAPTER_HEIGHT = 30;

    /** A quest's header row: chevron, icon, title, and the strip's Claim Quest. */
    public static final int HEADER_HEIGHT = 18;

    /** A reward's row, indented under its header: icon, name, count, and the strip's Claim. */
    public static final int REWARD_HEIGHT = 18;

    /** Air before a chapter's banner. Wider than a section gap: a banner starts a new group. */
    public static final int CHAPTER_GAP = 14;

    /** Air between one quest's block and the next. */
    public static final int SECTION_GAP = 7;

    /** Air between two reward rows of one quest. */
    public static final int ROW_GAP = 1;

    /** How far a reward row is indented under the header it belongs to. */
    public static final int REWARD_INDENT = 14;

    /**
     * How wide the action column is, and its inset from the column's right edge.
     *
     * <p>Wide enough for a container's count — "Claim Chapter (1116)" — so the scope a bulk press
     * states is drawn rather than truncated. Single rows keep the short "Claim"/"Choose" in the same
     * column, left-aligned room to spare rather than a narrower column that clips the banner.
     */
    public static final int ACTION_WIDTH = 124;
    public static final int STRIP_INSET = 2;

    /**
     * How tall a row's action is, whatever the row's own height.
     *
     * <h2>Why the action does not inherit its row's height</h2>
     *
     * <p>It did, and that is invisible on a row that is the same height as a button — a quest's row and
     * its Claim are both 18, so the button filling it looks right. On the chapter's banner it was not:
     * the banner is taller, so the button grew with it and filled the banner edge to edge, and a section
     * heading whose whole height is a button is a heading that reads as a button.
     *
     * <p>So the action has one height and is centred in whatever row carries it. That also makes every
     * button in the menu the same size, which is what "standardised Claim action buttons" means once it
     * is drawn rather than described: the same control, at the same height, on a banner and on a row.
     */
    public static final int ACTION_HEIGHT = 18;

    /** Air between two columns, so a long title cannot run into the count beside it. */
    public static final int COLUMN_GAP = 6;

    /**
     * How wide the reward-details column is: quest preview icons with their counts, then a "+N".
     *
     * <p>It was widened to 200 for a chapter badge that stated a fraction — "52/1116 ready to claim" —
     * and the badge is a bare count now, then moved inline after the chapter title, so the width went
     * back to what the quest previews need. At 110 it holds two icons with counts and the overflow;
     * a third collapses to "+N", which is what the fold is for. The forty pixels it gave up went to
     * the context column, which is where a reader spends them: they are the difference between a
     * truncated chapter title and a whole one.
     *
     * <p>Chapter banners do not draw in this cell: their badge rides inline after the title in
     * {@link Column#CONTEXT}, so the cell stands empty on that kind.
     */
    public static final int REWARDS_WIDTH = 110;

    /** The key a chapter's banner is placed and found by. */
    public static String chapterKey(String chapterId) {
        return "chapter:" + chapterId;
    }

    /** The key a quest's header row is placed and found by. */
    public static String questKey(String questId) {
        return "quest:" + questId;
    }

    /** The key a reward row is placed and found by. */
    public static String rewardKey(String questId, int rewardIndex) {
        return "reward:" + questId + ":" + rewardIndex;
    }

    private RewardInboxLayout() {
    }

    /** Which of the four shapes a row is. */
    public enum Kind {
        /** A chapter: the outermost accordion's handle, with its own Claim Chapter. */
        CHAPTER,
        /** A quest with more than one reward: the inner accordion's handle, with its own Claim Quest. */
        QUEST,
        /**
         * A quest whose definition holds exactly one reward: one row, the reward drawn inline, one
         * Claim.
         *
         * <p>Decided from the <b>definition</b> and never from what is left outstanding. A quest that
         * collapsed from an accordion to a single row the moment a child was claimed would move every
         * row below it — under a pointer that is usually already moving towards the next one.
         */
        SINGLE,
        /** One reward of an expanded quest, with its own Claim. */
        REWARD
    }

    /**
     * Which of the three columns a cell is.
     *
     * <h2>Why there is no progression column</h2>
     *
     * <p>There was one, and it was removed for two reasons that are worth keeping: it meant two
     * different things depending on the row — a quest's <i>task</i> count on one row, a reward's
     * <i>claim status</i> on the next — and neither was worth the width, because the row's own action
     * button already says whether there is anything to take. The one figure that survived is the
     * chapter's ready count, and it sits in {@link #REWARDS} where the badges and the reward icons
     * already share an axis.
     */
    public enum Column {
        /** The row's own context: a quest's icon and title, or a reward's. */
        CONTEXT,
        /** What the row gives: the reward icons and their counts, or a chapter's ready count. */
        REWARDS,
        /** The row's action: Claim Chapter, Claim Quest, Claim or Choose. */
        ACTION
    }

    /**
     * Where a row stands.
     *
     * <p>{@code PENDING} is the optimistic half of a press: the client marks the row collected the
     * moment it asks, and the server's sync either confirms it (the row becomes {@code CLAIMED}) or
     * refuses it (the row becomes {@code READY} again). The two are drawn alike on purpose — the
     * player pressed once and should see one thing — and the server stays the authority either way.
     */
    public enum Status {
        READY,
        LOCKED,
        CLAIMED,
        PENDING
    }

    /**
     * What the view is showing: the state toggle, and the reach of the footer's sweep.
     *
     * <h2>Why the selection rule is here rather than in the screen</h2>
     *
     * <p>Because a screen cannot be instantiated by a test, so a rule that lives only inside one is a
     * rule that is verified by playing. What a toggle admits is exactly the kind of rule this codebase
     * extracts: two arms over a status and a flag, asserted over the whole cross-product.
     *
     * <p>{@code PENDING} is admitted by both, and that is deliberate rather than an oversight: a row
     * whose press is still in flight has to stay on screen, or the row would vanish the instant it was
     * pressed and reappear if the server refused — which reads as a press that did nothing.
     *
     * <h2>Why there is no Claimed view</h2>
     *
     * <p>There was one, and it was dropped. A history of what has been collected is a list nobody can
     * bound: the pack these screenshots come from holds over a thousand rewards in a single chapter, so
     * the view was every one of them, in one 340-pixel list, with no recency and no paging — and the
     * other two views are about what is <i>owed</i>, which is what the menu is for.
     */
    public enum State {

        /** Everything this player could press right now, choices included. */
        READY,

        /** Only the rewards that ask a question, so they can be answered before the rest is swept. */
        CHOICES;

        /**
         * Whether one reward row belongs in this view.
         *
         * @param status the row's status for this player
         * @param choice whether the reward asks a question rather than handing something over
         */
        public boolean accepts(Status status, boolean choice) {
            boolean outstanding = status == Status.READY || status == Status.PENDING;
            return switch (this) {
                case READY -> outstanding;
                case CHOICES -> outstanding && choice;
            };
        }

        /**
         * What the footer's sweep may touch while this view is up.
         *
         * <p>Never null, and that is the shape of the answer rather than an accident: every view this
         * menu has is about something the server still owes, so every view has something a sweep could
         * take. Whether the button is <i>drawn</i> is a different question — the screen omits it when
         * the view is empty, because there is then nothing anywhere for it to reach.
         *
         * <p>The button's label is a promise, so the sweep follows the view: a button that acted on
         * rows the player cannot see would be acting on hidden elements.
         */
        public ClaimFilter filter() {
            return this == CHOICES ? ClaimFilter.CHOICES : ClaimFilter.ALL;
        }
    }

    /**
     * One row of the claim menu.
     *
     * <p>{@code count} is how many the row's action would hand over — a reward's own number, for a
     * quest header the number of rewards waiting inside, and for a chapter the number ready in it. The
     * label is already composed by the caller (the client's resolved reward text, or a chapter's title
     * with its ordinal), because turning an id into a name is a question for the cache and not for
     * arithmetic.
     */
    public record Row(String key, Kind kind, String chapterId, String questId, int rewardIndex,
                      String label, int count, Status status) {

        public Row {
            Objects.requireNonNull(key, "key");
            Objects.requireNonNull(kind, "kind");
            Objects.requireNonNull(chapterId, "chapterId");
            Objects.requireNonNull(questId, "questId");
            Objects.requireNonNull(status, "status");
            label = label == null ? "" : label;
        }

        /** A chapter's banner. Its count is how many of its rewards are ready. */
        public static Row chapter(String chapterId, String label, int ready, Status status) {
            return new Row(chapterKey(chapterId), Kind.CHAPTER, chapterId, "", -1, label, ready, status);
        }

        /** A quest's header. Its own count is how many rewards wait inside. */
        public static Row quest(String chapterId, String questId, String label, int count, Status status) {
            return new Row(questKey(questId), Kind.QUEST, chapterId, questId, -1, label, count, status);
        }

        /**
         * A quest with exactly one reward: the quest's title, and the reward drawn inline.
         *
         * <p>{@code rewardIndex} is the reward's own position in the quest definition, stored rather
         * than assumed to be zero: the row model does not get to know what the definition holds.
         */
        public static Row single(String chapterId, String questId, String label, int rewardIndex,
                                 int count, Status status) {
            return new Row(questKey(questId), Kind.SINGLE, chapterId, questId, rewardIndex, label, count,
                    status);
        }

        /** One reward of an expanded quest. */
        public static Row reward(String chapterId, String questId, int rewardIndex, String label,
                                 int count, Status status) {
            return new Row(rewardKey(questId, rewardIndex), Kind.REWARD, chapterId, questId, rewardIndex,
                    label, count, status);
        }

        /** Whether this row is an accordion's handle — the kinds that fold. */
        public boolean expands() {
            return kind == Kind.CHAPTER || kind == Kind.QUEST;
        }

        /** Whether this row's action has anything to take. Only a ready row's button is live. */
        public boolean pressable() {
            return status == Status.READY;
        }

        /** Whether this row is a reward indented under a header. */
        public boolean isReward() {
            return kind == Kind.REWARD;
        }

        /** Whether this row is a chapter's banner. */
        public boolean isChapter() {
            return kind == Kind.CHAPTER;
        }
    }

    /**
     * The rows as an unbuilt stack.
     *
     * <p>A chapter's gap is the chapter gap, a quest's the section gap and a reward's the row gap, so a
     * collapsed chapter's neighbours close up exactly as far as the rows that went away. A reward row
     * reserves its indent on the left and its action's room on the right, which is what makes the
     * indentation a property of the slot rather than a correction at each of the drawing, the strip and
     * the hit test.
     */
    public static Stack stack(List<Row> rows) {
        Objects.requireNonNull(rows, "rows");
        Stack stack = Stack.stack();
        for (int i = 0; i < rows.size(); i++) {
            Row row = rows.get(i);
            if (i > 0) {
                stack.gap(gapBefore(row));
            }
            stack.row(row.key(), heightOf(row), stripRoom(row.isReward() ? REWARD_INDENT : 0));
        }
        return stack;
    }

    /** The air before one row: a chapter starts a group, a reward is packed under its header. */
    private static int gapBefore(Row row) {
        if (row.isChapter()) {
            return CHAPTER_GAP;
        }
        return row.isReward() ? ROW_GAP : SECTION_GAP;
    }

    /** One row's line height. A banner is taller than a row: it carries a chapter's whole identity. */
    private static int heightOf(Row row) {
        if (row.isChapter()) {
            return CHAPTER_HEIGHT;
        }
        return row.isReward() ? REWARD_HEIGHT : HEADER_HEIGHT;
    }

    /** The rows laid out into a column of the given width. */
    public static Layout build(List<Row> rows, int width, Measure measure) {
        Objects.requireNonNull(measure, "measure");
        return stack(rows).build(Math.max(0, width), measure);
    }

    /** The room a row reserves for its action: the column plus its inset on each side. */
    private static Insets stripRoom(int indent) {
        return new Insets(indent, 0, ACTION_WIDTH + STRIP_INSET * 2, 0);
    }

    /**
     * Where a row's action goes: in the room its insets reserved, against the column's right edge.
     *
     * <p>The row slot is narrowed by its insets, so the action is <i>outside</i> the slot's right edge
     * — the placement {@code ToolsLayout.strip} uses, and the one every row control in this UI reads
     * as: a button at the list's edge, with the label owning what is left of the line.
     *
     * <p>Its height is {@link #ACTION_HEIGHT} and it is centred in the row, so a banner taller than a
     * button has the button in the middle of it rather than filling it. A row shorter than the action
     * clamps it instead of overflowing — which cannot happen at today's metrics, and would be a button
     * drawn over its neighbours if it ever did.
     */
    public static Slot strip(Slot row) {
        Objects.requireNonNull(row, "row");
        int width = Math.min(ACTION_WIDTH, Math.max(0, row.width()));
        int height = Math.min(ACTION_HEIGHT, row.height());
        return new Slot(row.key(), row.right() + STRIP_INSET, row.y() + (row.height() - height) / 2,
                width, height);
    }

    /**
     * One of a row's three cells.
     *
     * <p>Measured back from the action's left edge rather than forward from the row's, which is what
     * holds one vertical axis across indented and unindented rows — see the class note. Each cell is
     * clamped to the room actually left of the one before it, so a row too narrow for the columns
     * truncates rather than producing a negative width.
     */
    public static Slot column(Slot row, Column which) {
        Objects.requireNonNull(row, "row");
        Objects.requireNonNull(which, "which");

        Slot action = strip(row);
        if (which == Column.ACTION) {
            return action;
        }
        int rewardsWidth = Math.min(REWARDS_WIDTH, Math.max(0, action.x() - COLUMN_GAP - row.x()));
        Slot rewards = new Slot(row.key(), action.x() - COLUMN_GAP - rewardsWidth, row.y(),
                rewardsWidth, row.height());
        if (which == Column.REWARDS) {
            return rewards;
        }
        return new Slot(row.key(), row.x(), row.y(),
                Math.max(0, rewards.x() - COLUMN_GAP - row.x()), row.height());
    }

    /**
     * A row's own area — everything left of its action.
     *
     * <p>Where a banner's or a header's toggle is hit-tested. The action is a widget and answers first,
     * so a press that reaches here is a press on the row and not on its button. Only {@link Kind#CHAPTER}
     * and {@link Kind#QUEST} rows are tested: a single row has nothing to fold.
     */
    public static Slot body(Slot row) {
        Objects.requireNonNull(row, "row");
        return new Slot(row.key(), row.x(), row.y(), row.width(), row.height());
    }
}
