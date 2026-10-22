package dev.ellipog.tenet.client;

import dev.ellipog.armature.client.ArmatureTheme;
import dev.ellipog.armature.client.render.GuiRenderer;
import dev.ellipog.armature.client.render.TextEpoch;
import dev.ellipog.armature.client.ui.kit.Hover;
import dev.ellipog.armature.client.ui.kit.Slot;
import dev.ellipog.tenet.client.viewer.RecipeLookups;
import dev.ellipog.tenet.client.dev.QuestPanelLayout;
import dev.ellipog.tenet.progress.QuestState;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.util.Mth;
import net.minecraft.world.item.ItemStack;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * The quest book's task and reward rows: how the quest card draws each one, remembered between frames
 * so a row is measured once rather than once a frame.
 *
 * <p>Moved out of {@link QuestBookScreen} as it stood, with the state only it reads; the screen
 * keeps the state it shares and reaches this through its {@code bookQuestRows} field.
 */
final class BookQuestRows {

    private final QuestBookScreen book;

    BookQuestRows(QuestBookScreen book) {
        this.book = book;
    }

    /** A contributor's face in a task row, its gaps, and how many rows of them fit before "+n". */
    private static final int CONTRIBUTOR_FACE = 8;
    private static final int CONTRIBUTOR_COUNT_GAP = 2;
    private static final int CONTRIBUTOR_GAP = 6;
    private static final int MAX_CONTRIBUTORS = 3;

    /**
     * How far a contributor's number sits below the row's own text line.
     *
     * <p>The same optical point as the party panel's {@link PartyPanelLayout#NAME_DROP}: a label's line
     * box carries descender space under the baseline, so a number sharing a line with a face centred on
     * its own box reads a pixel high. One, rather than that panel's two, because this row's text already
     * sits above the icon's centre.
     */
    private static final int CONTRIBUTOR_TEXT_DROP = 1;

    // ------------------------------------------------------------------
    // Remembered rows
    // ------------------------------------------------------------------

    /**
     * One task, with a highlight that eases in and out as the pointer arrives and leaves.
     *
     * <h2>Why a row highlights at all</h2>
     *
     * <p>Because the row is the thing a click lands on. A task in the list is not a button — there is
     * nothing to press on most of them — but it is the anchor for <b>Hand over</b>, and without any
     * feedback the only way to know which task that button refers to is to click it and see. The
     * highlight is the row saying "this one", which is the same job the hover ring does on a node.
     *
     * <p>It is a wash rather than a fill, and at low alpha. A task row that brightened as much as a
     * button would compete with the quest's actual selection, and the row is a hint rather than a
     * choice.
     *
     * <h2>Everything is measured before anything is drawn</h2>
     *
     * <p>Not a style choice: the wash has to be <b>behind</b> the icon, so it cannot be drawn after the
     * row is painted — which means the width it needs must be known before the row exists on screen.
     * The measurement block below therefore computes every string and x-position the row will use, and
     * the drawing block consumes those same values rather than recomputing them. Two descriptions of
     * one extent is the failure this codebase keeps paying for, and the highlight is exactly the kind
     * of thing where the two would drift unnoticed.
     *
     * <p>One value is assumed rather than known: the text's indent. Whether an item resolves is only
     * answerable by asking the renderer to draw it, and the wash has to come first — so the measurement
     * uses the indented position, which is the wider case. A row whose icon cannot be resolved gets a
     * wash a couple of characters wider than its text, which is invisible.
     *
     * @param slot the placed row, so the highlight is anchored to the box the layout reserved — and so
     *     the box the hover test asks about is the box the highlight is drawn from
     * @param hover 0 at rest, 1 fully hovered, eased. See {@link Hover}
     */
    /**
     * Which list a remembered row belongs to: the task rows, or the reward rows below them.
     */
    private enum RowKind {
        TASK, REWARD
    }

    /**
     * Which tag a row wears, if any. The literals are drawn by the row; nothing here is
     * localised, which is why a tag survives the locale half of the stamp rather than moving it.
     *
     * <p>The cooldown countdown is not a member: it changes every second, so it rides
     * {@link RowStrings#cooldownSecond} instead of this, and the row rebuilds when the second
     * turns rather than never caching at all.
     */
    private enum RowTag {
        NONE, LOCKED, HAND_IN, OPTIONAL
    }

    /**
     * One task row's tag: locked wins the slot, then the hand-in, then the optional flag — and a
     * cooling row with none of those says when it comes back instead of sitting empty.
     *
     * <p>Display-only, and hardcoded English like the other tags: the server stays authoritative,
     * and the press is still refused server-side exactly as today. Whole seconds, ceiling, so the
     * text reaches zero exactly when the hand-in comes back.
     */
    static String tagText(boolean locked, boolean handIn, boolean optional, long cooldownTicks) {
        if (locked) {
            return "locked";
        }
        if (handIn) {
            return "hand in";
        }
        if (optional) {
            return "optional";
        }
        if (cooldownTicks <= 0) {
            return null;
        }
        return "ready in " + ((cooldownTicks + 19) / 20) + "s";
    }

    /**
     * Whole seconds of cooldown left, ceiling, or -1 when not cooling: the row cache's clock.
     *
     * <p>Minus one rather than zero so a lapsed cooldown reads differently from a one-second one:
     * the bundle cached under second one must miss once the countdown is gone, or the tag it drew
     * stays past the hand-in's return.
     */
    private static long cooldownSecond(String questId) {
        long ticks = ClientQuestCache.cooldownOf(questId, ClientTicker.ticks());
        return ticks <= 0 ? -1 : (ticks + 19) / 20;
    }

    /** One remembered row, by the list it is in and its place in it. */
    private record RowKey(RowKind kind, String questId, int index) {
    }

    /**
     * The five revisions one card row depends on, read once per row list rather than per row.
     *
     * <p>Tree and progress for the data, marks for the optimistic presses in flight, text for the
     * locale overlay, and the font epoch for the client's own language (which arrives through a
     * font swap). Anything else a row reads — the slot geometry, the hover — is either passed in
     * or drawn, never remembered.
     */
    private record RowStamp(long tree, long progress, long marks, long text, long font) {
    }

    /** The stamp in force for this frame's rows. See {@link RowStamp}. */
    RowStamp rowStamp() {
        return new RowStamp(ClientQuestCache.treeRevision(), ClientQuestCache.progressRevision(),
                book.pendingClaims.revision(), ClientQuestCache.textRevision(), TextEpoch.now());
    }

    /**
     * What one remembered row says and shows, while its stamp holds still.
     *
     * <p>Everything the measured half used to rebuild per frame: the sentence, the count, the tag
     * and the predicates behind it, the progress the bar fills from, and the parsed icon ids. The
     * geometry — which widths, which x — is recomputed per frame from these plus the slot, because
     * a resize must move the row rather than reuse it, and the widths underneath are memoised.
     */
    private record RowStrings(long tree, long progress, long marks, long text, long font,
                              String words, String count, boolean missingItem, RowTag tag,
                              boolean locked, boolean actionable, int progressValue, boolean satisfied,
                              net.minecraft.resources.Identifier texture,
                              net.minecraft.resources.Identifier sprite, long cooldownSecond) {

        /**
         * Whether this bundle was built under this stamp — and, for a cooling row, under this
         * second. A countdown that outlived its second would read stale past the hand-in's
         * return, so the second is part of the match rather than beside it.
         */
        boolean matches(RowStamp stamp, long cooldownSecond) {
            return tree == stamp.tree() && progress == stamp.progress() && marks == stamp.marks()
                    && text == stamp.text() && font == stamp.font()
                    && this.cooldownSecond == cooldownSecond;
        }
    }

    /**
     * A task row's placed contributors, remembered with the geometry they were placed in.
     *
     * <p>The faces' x positions depend on the row's own width, so the geometry rides in the stamp:
     * a resize misses and replaces rather than reusing positions from a narrower row. The data —
     * who and how many — rides on the tree, progress and marks like everything else.
     */
    private record PlacedContributors(long tree, long progress, long marks, int facesRight,
                                      int leftLimit, List<Contributor> placed) {

        /** Whether this placing is still the one. */
        boolean matches(long tree, long progress, long marks, int facesRight, int leftLimit) {
            return this.tree == tree && this.progress == progress && this.marks == marks
                    && this.facesRight == facesRight && this.leftLimit == leftLimit;
        }
    }

    /** Remembered rows, eldest half out at the bound. A screen lives an open card, not a session. */
    private static final int ROW_CACHE_LIMIT = 256;

    private final Map<RowKey, RowStrings> rowStrings = new LinkedHashMap<>();
    private final Map<RowKey, PlacedContributors> rowContributors = new LinkedHashMap<>();

    /** Halves a row table at the bound, eldest first. Same policy as the toolkit's own tables. */
    private static void halveRows(Map<?, ?> table) {
        var it = table.keySet().iterator();
        int remove = table.size() / 2;
        for (int i = 0; i < remove && it.hasNext(); i++) {
            it.next();
            it.remove();
        }
    }

    /**
     * A task row's words and predicates, remembered while its stamp holds still.
     *
     * <p>The same reads the measured half has always done — the sentence through the prettifier,
     * the count, the tag's three predicates, the parsed icon ids — moved verbatim behind one map
     * lookup, so a hit draws exactly what a fresh build would have drawn.
     *
     * <p>An advancement row is never remembered, because it reads the client's live advancement
     * tree, which no revision names. A cooling row is remembered by the second: its bundle carries
     * the whole seconds left, so it rebuilds when the second turns — bounded, one rebuild a
     * second for rows that are actually cooling — and every other row keeps its caching. Both
     * rebuilt per frame before; the advancement still does.
     */
    private RowStrings taskStrings(ClientQuestCache.Entry entry, int index, RowStamp stamp) {
        ClientQuestCache.TaskEntry task = entry.tasks().get(index);
        if (task.type().endsWith(":advancement")) {
            return buildTaskStrings(entry, index, task, stamp, -1);
        }
        long cooling = cooldownSecond(entry.id());
        RowKey key = new RowKey(RowKind.TASK, entry.id(), index);
        RowStrings hit = rowStrings.get(key);
        if (hit != null && hit.matches(stamp, cooling)) {
            return hit;
        }
        RowStrings built = buildTaskStrings(entry, index, task, stamp, cooling);
        if (rowStrings.size() >= ROW_CACHE_LIMIT) {
            halveRows(rowStrings);
        }
        rowStrings.put(key, built);
        return built;
    }

    /** The build half of {@link #taskStrings}: the reads, with no decision about keeping them. */
    private RowStrings buildTaskStrings(ClientQuestCache.Entry entry, int index,
                                        ClientQuestCache.TaskEntry task, RowStamp stamp,
                                        long cooling) {
        int progress = ClientQuestCache.taskProgressOf(entry.id(), index);
        boolean locked = !ClientQuestCache.taskLockOf(entry.id(), index).isEmpty();
        boolean handIn = book.bookAssets.submitOfferedWithMarks(entry.id(), index);
        boolean missingItem = !task.hasItem() && !task.itemId().isEmpty();
        return new RowStrings(stamp.tree(), stamp.progress(), stamp.marks(), stamp.text(),
                stamp.font(),
                missingItem ? Component.translatable("tenet.screen.missing_item").getString()
                        : BookQuestEditor.rowText("tasks", task),
                task.count() > 1 ? Math.min(progress, task.count()) + " / " + task.count() : null,
                missingItem,
                locked ? RowTag.LOCKED
                        : handIn ? RowTag.HAND_IN
                        : task.optional() ? RowTag.OPTIONAL : RowTag.NONE,
                locked, handIn, progress, progress >= task.count(),
                task.textureIcon().isEmpty() ? null
                        : net.minecraft.resources.Identifier.tryParse(task.textureIcon()),
                task.spriteIcon().isEmpty() ? null
                        : net.minecraft.resources.Identifier.tryParse(task.spriteIcon()),
                cooling);
    }

    /**
     * A reward row's words and predicates, remembered while its stamp holds still.
     *
     * <p>The task row's rule, one member over: the sentence, the count, the lock, whether a press
     * collects it, and the parsed icon ids. A reward carries no progress and no contributors.
     *
     * <p>An advancement reward is never remembered, for the live-tree reason above. A cooling
     * repeatable's rewards still rebuild per frame, as before: only the task rows carry the
     * countdown, so only they gained the second.
     */
    private RowStrings rewardStrings(ClientQuestCache.Entry entry, int index, RowStamp stamp) {
        ClientQuestCache.RewardEntry reward = entry.rewards().get(index);
        if (reward.type().endsWith(":advancement")
                || ClientQuestCache.cooldownOf(entry.id(), ClientTicker.ticks()) > 0) {
            return buildRewardStrings(entry, index, reward, stamp);
        }
        RowKey key = new RowKey(RowKind.REWARD, entry.id(), index);
        RowStrings hit = rowStrings.get(key);
        if (hit != null && hit.matches(stamp, -1)) {
            return hit;
        }
        RowStrings built = buildRewardStrings(entry, index, reward, stamp);
        if (rowStrings.size() >= ROW_CACHE_LIMIT) {
            halveRows(rowStrings);
        }
        rowStrings.put(key, built);
        return built;
    }

    /** The build half of {@link #rewardStrings}: the reads, with no decision about keeping them. */
    private RowStrings buildRewardStrings(ClientQuestCache.Entry entry, int index,
                                          ClientQuestCache.RewardEntry reward, RowStamp stamp) {
        boolean missingItem = !reward.hasItem() && !reward.itemId().isEmpty();
        boolean locked = !ClientQuestCache.rewardLockOf(entry.id(), index).isEmpty();
        java.util.UUID self = book.minecraft().player == null ? null : book.minecraft().player.getUUID();
        boolean claimable = self != null
                && ClientQuestCache.canClaimReward(self, entry, index)
                && !book.pendingClaims.isMarked(PendingClaims.rewardKey(entry.id(), index))
                && !book.pendingClaims.isMarked(PendingClaims.questKey(entry.id()))
                && !book.pendingClaims.isMarked(PendingClaims.chapterKey(entry.chapterId()));
        return new RowStrings(stamp.tree(), stamp.progress(), stamp.marks(), stamp.text(),
                stamp.font(),
                missingItem ? Component.translatable("tenet.screen.missing_item").getString()
                        : BookQuestEditor.rowText("rewards", reward),
                reward.hasItem() && reward.count() > 1 ? "x" + reward.count() : null,
                missingItem, locked ? RowTag.LOCKED : RowTag.NONE,
                locked, claimable, 0, false,
                reward.textureIcon().isEmpty() ? null
                        : net.minecraft.resources.Identifier.tryParse(reward.textureIcon()),
                reward.spriteIcon().isEmpty() ? null
                        : net.minecraft.resources.Identifier.tryParse(reward.spriteIcon()),
                -1);
    }

    /**
     * A task row's placed contributors, remembered with the geometry they were placed in.
     *
     * <p>The placing loop's count strings and widths are the per-frame cost this removes; the
     * faces themselves draw from the placed positions either way. Geometry rides the stamp, so a
     * resize replaces rather than reuses.
     */
    private List<Contributor> taskContributors(GuiRenderer r, String questId, int taskIndex,
                                               int facesRight, int leftLimit) {
        RowKey key = new RowKey(RowKind.TASK, questId, taskIndex);
        long tree = ClientQuestCache.treeRevision();
        long progress = ClientQuestCache.progressRevision();
        long marks = book.pendingClaims.revision();
        PlacedContributors hit = rowContributors.get(key);
        if (hit != null && hit.matches(tree, progress, marks, facesRight, leftLimit)) {
            return hit.placed();
        }
        List<Contributor> placed = placeContributors(r, questId, taskIndex, facesRight, leftLimit);
        if (rowContributors.size() >= ROW_CACHE_LIMIT) {
            halveRows(rowContributors);
        }
        rowContributors.put(key,
                new PlacedContributors(tree, progress, marks, facesRight, leftLimit, placed));
        return placed;
    }

    void drawTaskRow(GuiRenderer r, ClientQuestCache.Entry entry, int index, Slot slot,
                             float hover, int mouseX, int mouseY, RowStamp stamp) {
        // The row's words and predicates, remembered while the stamp holds still: the same reads
        // the measured half below has always done, decided once per stamp rather than once per frame.
        RowStrings strings = taskStrings(entry, index, stamp);
        ClientQuestCache.TaskEntry task = entry.tasks().get(index);
        int progress = strings.progressValue();
        boolean satisfied = strings.satisfied();

        int x = slot.x();
        int y = slot.y();
        int availableWidth = slot.width();

        // The text sits on the centre line of the icon's box, and the box is ROW_ICON square. Before,
        // the text was on the row's top edge while the icon was drawn 4px above it at 16px tall, so
        // neither lined up with the other and the icon bled into the row above.
        int textY = y + (QuestBookScreen.ROW_ICON - 8) / 2;

        // --- measured, so the wash can be drawn behind the row at the width it will occupy ---

        // An id with no item behind it is still the row's subject: the row says an item is missing,
        // where the item's name would be, in the ink that says something is wrong. The id itself is
        // the author's business -- the picker names it -- and a player can act on "missing", not on
        // "minecraft:oak_log".
        boolean missingItem = strings.missingItem();
        String text = strings.words();
        int measuredTextX = x + QuestBookScreen.ROW_ICON + 5;
        int measuredTextRight = measuredTextX + r.textWidth(text);

        String count = strings.count();
        int barX = count == null
                ? measuredTextRight + 8
                : measuredTextRight + 8 + r.textWidth(count) + 8;
        int barWidth = count == null ? 0 : Mth.clamp(availableWidth - (barX - x) - 70, 24, 120);

        // Both tags are right-aligned to the row, which is the one case where the content really does
        // reach the row's far edge -- so a task that can be handed in has a highlight that spans it,
        // and that is correct rather than a leftover of the old full-width wash.
        boolean optional = task.optional();
        // Whether this row offers the hand-in right now: the button's own predicate, so the tag and the
        // button cannot disagree. Not the same as the type's `manual` flag -- a task that takes is handed
        // in once the player can pay for it, and a task already handed in offers nothing.
        boolean handIn = strings.actionable();
        // A condition this player does not meet. Locked wins the one tag slot: "hand in" would be a lie
        // about a button that is not drawn, and the explanation is the whole point of the tag.
        boolean locked = strings.locked();
        String tag = tagText(locked, handIn, optional,
                ClientQuestCache.cooldownOf(entry.id(), ClientTicker.ticks()));
        int tagX = tag == null ? 0 : x + availableWidth - r.textWidth(tag)
                - (handIn && optional ? r.textWidth("optional") + 6 : 0);

        // Who is contributing, right-aligned before the tag. Placed here rather than down at the
        // drawing, because the wash behind the row reaches the same edge -- two expressions of "where
        // the faces end" that happen to agree today is the fault this file's geometry exists against.
        // Remembered with the geometry they were placed in: same faces, same row width, no re-walk.
        int facesRight = tag == null ? x + availableWidth : tagX - 6;
        List<Contributor> contributors = taskContributors(r, entry.id(), index, facesRight,
                barX + barWidth + 8);

        int contentRight = Math.max(measuredTextRight, barX + barWidth);
        if (tag != null) {
            contentRight = Math.max(contentRight, tagX + r.textWidth(tag));
        }
        if (!contributors.isEmpty()) {
            contentRight = Math.max(contentRight, facesRight);
        }

        // The row's own box rather than its slot: see `rowBox` for the six pixels of gap between them.
        // The same box answers the hover and registers the row's explanation, so the row that lights up
        // is exactly the row that explains itself.
        Slot row = BookSettings.rowBox(slot);
        // The row's target, from the rule the tests hold (BookRowTargets): an item task, or a tag
        // task, and nothing for a row with neither. Collected from the drawing pass so the press and
        // the hover come from one description of where a row is -- the rowTooltips contract.
        RecipeLookups.Target target = BookRowTargets.ofTask(task);
        if (target != null) {
            book.rowItems.add(new QuestBookScreen.RowItem(row, target));
        }
        // A checkmark hands in on press, like the legacy format's: the row is the button. Only with no
        // recipe target (a checkmark never has one, but the rule is the target's, not the type's)
        // and only while the hand-in is actually offered — a locked or finished row must not
        // swallow the press its tag already explains.
        boolean checkmarkHandsIn = target == null && "tenet:checkmark".equals(task.type())
                && handIn && !locked;
        if (checkmarkHandsIn) {
            book.checkmarkRows.add(new QuestBookScreen.CheckmarkRow(row, entry.id(), index));
        }
        if (!task.filter().isEmpty()) {
            book.filterRows.add(new QuestBookScreen.FilterRow(row, task.filter()));
        }
        if (row.contains(mouseX, mouseY)) {
            // The player's explanation, not the author's: this hover is read by someone who has never
            // heard of a task type. Its second line is about handing the task in, so it is told both
            // halves of that: whether the button is drawn (a locked task has none, and telling the player
            // to press one that is not there is the one way this hover can lie) and whether handing it in
            // takes anything -- which is true of a locked consuming row too, and is why the sentence may
            // not be read off the type id.
            List<String> lines = new ArrayList<>(QuestPanelLayout.playerTooltip("tasks", task.type(),
                    task.manual() && !locked, task.waits()));
            // The tag's raw id used to be inserted here as "#minecraft:logs", for the row whose label
            // reads "Any Logs". A player cannot hand in an id -- the label already names the tag, and
            // "Click for recipes" below is how they find out which items are in it. The id is in the
            // picker and in the row's own `tagId` for the recipe viewers, which is where it is data.
            appendConditionLines(lines, task.conditions(),
                    ClientQuestCache.taskLockOf(entry.id(), index));
            if (!task.filter().isEmpty()) {
                // What a filter names, in the player's own words: the count is exact, the list
                // itself is one click away. An empty match list says so outright — it is also
                // why a task with one can never complete, which deserves naming, not silence.
                var matches = dev.ellipog.tenet.client.FilterMatches.of(task.filter());
                if (matches.isEmpty()) {
                    lines.add("Matches nothing — check the expression");
                }
                else {
                    lines.add("Matches " + matches.total() + " item" + (matches.total() == 1 ? "" : "s")
                            + (matches.truncated() ? " (first " + matches.shown().size() + " shown)" : ""));
                    lines.add("Click to preview");
                }
            }
            if (RecipeLookups.canOpen(target)) {
                lines.add("Click for recipes");
            }
            if (checkmarkHandsIn) {
                // The row is the button here, so the hover says so: a player who has to discover
                // the footer Submit by accident is a player the row failed.
                lines.add("Click to hand in");
            }
            book.rowTooltips.add(new QuestBookScreen.RowTooltip(row, lines));
        }
        BookSettings.rowWash(r, row, contentRight, hover);

        // --- drawn ---

        int textX = x;
        ItemStack toDraw = task.shown();
        net.minecraft.resources.Identifier taskTexture = strings.texture();
        net.minecraft.resources.Identifier taskSprite = strings.sprite();
        if (!task.filter().isEmpty() && taskTexture == null && taskSprite == null) {
            // A filter task cycles through what it names, one picture a second — the legacy cadence.
            // The author's texture or sprite still wins when set; an empty match list keeps the
            // static picture, which is the honest answer for an expression nothing answers to.
            ItemStack frame = ClientQuestCache.filterFrame(task);
            if (!frame.isEmpty()) {
                toDraw = frame;
            }
        }
        if (missingItem) {
            QuestBookScreen.drawItemPlaceholder(r, x, y, QuestBookScreen.ROW_ICON);
            textX = x + QuestBookScreen.ROW_ICON + 5;
        }
        else if (dev.ellipog.tenet.client.CheckmarkArt.wearsBox(task)) {
            // A bare checkmark's state box: the empty box while todo, the checked one once done.
            // An author picture wins when present (see wearsBox); a checkmark has no item, so it
            // can never be missing, and the box is drawn before every other arm.
            dev.ellipog.tenet.client.CheckmarkArt.draw(r, x, y, QuestBookScreen.ROW_ICON, satisfied);
            textX = x + QuestBookScreen.ROW_ICON + 5;
        }
        else if (taskTexture != null) {
            // The author's texture: blitted rather than drawn as a stack, which the cache keeps empty
            // for a texture so no missing branch fires for a picture.
            r.texture(taskTexture, x, y, QuestBookScreen.ROW_ICON, QuestBookScreen.ROW_ICON);
            textX = x + QuestBookScreen.ROW_ICON + 5;
        }
        else if (taskSprite != null) {
            // The author's atlas sprite: drawn from the atlas, like the texture arm above.
            r.sprite(taskSprite, x, y, QuestBookScreen.ROW_ICON, QuestBookScreen.ROW_ICON, 0xFFFFFFFF);
            textX = x + QuestBookScreen.ROW_ICON + 5;
        }
        else if (r.icon(toDraw, x, y, QuestBookScreen.ROW_ICON)) {
            // Only indent the text when something was actually drawn, so a task whose item the client
            // cannot resolve is not left with a gap where an icon should be.
            textX = x + QuestBookScreen.ROW_ICON + 5;
        }

        int colour = missingItem ? ArmatureTheme.blocked()
                : satisfied ? ArmatureTheme.complete()
                : locked || ClientQuestCache.stateOf(entry.id()) == QuestState.LOCKED
                        ? ArmatureTheme.blocked()
                : ArmatureTheme.body();
        r.text(text, textX, textY, colour);

        int after = textX + r.textWidth(text) + 8;

        // The progress, and a bar for it. The bar is what makes "5 / 8" readable at a glance rather
        // than something you have to stop and parse.
        if (count != null) {
            r.text(count, after, textY, satisfied ? ArmatureTheme.complete() : ArmatureTheme.faint());
            after += r.textWidth(count) + 8;

            int barY = textY + 1;
            int filled = Math.round(barWidth * Math.min(1F, progress / (float) task.count()));
            r.fill(after, barY, after + barWidth, barY + 6, ArmatureTheme.recessed());
            ArmatureTheme.outline(r, after, barY, barWidth, 6, ArmatureTheme.panelEdge());
            if (filled > 0) {
                r.fill(after + 1, barY + 1, after + Math.max(2, filled), barY + 5,
                        satisfied ? ArmatureTheme.complete() : ArmatureTheme.available());
            }
        }

        // The contributors, from the list placed above: each member's face and what they hold. The face
        // is the part that answers "who", and the number beside it is what makes the answer worth
        // reading -- a party's total says how the quest is going, this says who is doing it.
        //
        // Drawn for a finished task as well, on purpose: the picture is frozen at the evaluation that
        // completed the task, so a done row says who did it. See `ProgressService.CONTRIBUTIONS`.
        for (Contributor contributor : contributors) {
            r.face(contributor.who(), contributor.x(), y + (QuestBookScreen.ROW_ICON - CONTRIBUTOR_FACE) / 2,
                    CONTRIBUTOR_FACE);
            r.text(contributor.count(), contributor.countX(), textY + CONTRIBUTOR_TEXT_DROP,
                    ArmatureTheme.faint());
        }

        // The optional tag is the one case where two tags share the row's far edge, and it is drawn by
        // its own branch below -- so a locked row draws "locked" there instead, not both on top of
        // each other.
        if (optional && !locked) {
            r.text("optional", x + availableWidth - r.textWidth("optional"), textY, ArmatureTheme.faint());
        }
        if (handIn) {
            r.text("hand in", tagX, textY, ArmatureTheme.available());
        }
        if (locked) {
            r.text("locked", tagX, textY, ArmatureTheme.blocked());
        }
        else if (!optional && !handIn && tag != null) {
            // The cooldown countdown, and only it reaches here: locked took the branch above,
            // hand-in and optional drew theirs, and the tag is null for every other state — so
            // no row that drew before draws differently now.
            r.text(tag, tagX, textY, ArmatureTheme.faint());
        }
    }

    /** One member's mark in a task row: their face, what they hold, and where both go. */
    private record Contributor(UUID who, String count, int x, int countX) {
    }

    /**
     * The contributing members a task row can show, placed right-aligned from {@code right}.
     *
     * <h2>Measured once, drawn from the result</h2>
     *
     * <p>The alternative is measuring the cluster to decide the wash's width and then re-walking the
     * members to draw them, which is two expressions of one layout -- and the second one is the one
     * that would drift. So this returns positions and the drawing reads them.
     *
     * <h2>Empty rather than squeezed</h2>
     *
     * <p>A row with no room for faces draws none: a narrow overlay, or a long task name. Returning
     * nothing is the honest answer where overlapping the name would be a fault, and the caller has one
     * thing to check instead of a special case per reason.
     */
    private static List<Contributor> placeContributors(GuiRenderer r, String questId, int taskIndex,
                                                       int right, int leftLimit) {
        Map<UUID, Integer> picture = ClientQuestCache.contributorsOf(questId, taskIndex);
        if (picture.isEmpty()) {
            return List.of();
        }

        List<Contributor> placed = new ArrayList<>();
        int x = right;
        int shown = 0;
        for (Map.Entry<UUID, Integer> each : picture.entrySet()) {
            String count = String.valueOf(each.getValue());
            if (shown == MAX_CONTRIBUTORS) {
                // The rest as a count of their own, so a party of eight does not push the row's own
                // text off it -- and so the row still says that somebody is missing from it.
                count = "+" + (picture.size() - MAX_CONTRIBUTORS);
                x -= r.textWidth(count);
                placed.add(new Contributor(null, count, x, x));
                break;
            }
            int width = CONTRIBUTOR_FACE + CONTRIBUTOR_COUNT_GAP + r.textWidth(count);
            x -= width + CONTRIBUTOR_GAP;
            placed.add(new Contributor(each.getKey(), count, x,
                    x + CONTRIBUTOR_FACE + CONTRIBUTOR_COUNT_GAP));
            shown++;
        }

        // Measured against what the row already holds -- its name, its count and its bar -- and not
        // against the row's edge. A cluster that reaches back past them would be printed over the
        // task's own name, and "no faces" is a smaller fault than a face on top of a word.
        if (x < leftLimit) {
            return List.of();
        }
        return List.copyOf(placed);
    }

    /**
     * One reward, with the same eased wash a task row gets.
     *
     * <p>A reward is no more clickable than a task is, but the two sit in one list one above the other:
     * a highlight on one kind and not the other reads as the list being broken partway down.
     *
     * <p>Measured before drawn, for the reason {@code drawTaskRow} explains at length — the wash is
     * behind the icon, so its width has to be known before the row is painted. A reward's content is
     * simpler than a task's: an icon, a name, and a count when there is one, with no tags reaching the
     * row's far edge.
     */
    void drawRewardRow(GuiRenderer r, ClientQuestCache.Entry entry, int index, Slot slot,
                               float hover, int mouseX, int mouseY, RowStamp stamp) {
        // The row's words and predicates, remembered while the stamp holds still: the task row's
        // rule, one member over.
        RowStrings strings = rewardStrings(entry, index, stamp);
        ClientQuestCache.RewardEntry reward = entry.rewards().get(index);
        int x = slot.x();
        int y = slot.y();
        int textY = y + (QuestBookScreen.ROW_ICON - 8) / 2;

        // An item this pack no longer has is named for what it is rather than by its id: see
        // `rewardInboxRow` for the row that is drawn rather than hovered, and the same rule here.
        boolean missingItem = strings.missingItem();
        String text = strings.words();
        String count = strings.count();
        // A condition this player does not meet on this reward. The row is drawn shut and the tag says
        // so; the claim button is not this row's -- see BookGeometry's claimable -- and the server
        // refuses the payout anyway, with a message, if the press gets there.
        boolean locked = strings.locked();
        // Whether a press on this row collects it: the quest finished, this reward unclaimed and
        // ungated for this player, and no press for it already in flight. A claimable row is
        // claim-only — it carries no recipe target, so one press is never two answers — while
        // anything else keeps today's behaviour, recipes included.
        boolean claimable = strings.actionable();
        // Whether this player already collected it: claimed rows wear their mark below and,
        // like claimable ones, carry no recipe target. A collected reward is not an ingredient
        // to look up, and a press on one must not open recipes for something already owned.
        // The entry overload, not the id lookup: the lookup walks the whole tree, and this runs
        // per reward row per frame (see ClientQuestCache#rewardClaimedBy for why both exist).
        java.util.UUID self = book.bookRewards.selfId();
        boolean claimed = self != null && ClientQuestCache.rewardClaimedBy(self, entry, index);

        int contentRight = x + QuestBookScreen.ROW_ICON + 5 + r.textWidth(text);
        if (count != null) {
            contentRight += 5 + r.textWidth(count);
        }
        if (locked) {
            // The tag is right-aligned, so the wash has to reach the row's own edge -- the task row's
            // rule, where the tag's x plus its width is exactly that. Measuring from the text's end
            // instead left the tag floating outside its own highlight.
            contentRight = Math.max(contentRight, x + slot.width());
        }
        else if (claimed) {
            // Claimed rows wear the fact the way locked rows wear theirs: right-aligned, in the
            // dim ink, where the claim button used to be. The button is gone (nothing left to
            // collect) and so is the recipe target above — a collected reward is looked at, not
            // looked up.
            contentRight = Math.max(contentRight, x + slot.width());
        }

        // The row's own box rather than its slot: see `rowBox` for the six pixels of gap between them.
        // Registered exactly as the task row's is, one member over: the same box answers the hover and
        // carries the explanation.
        Slot row = BookSettings.rowBox(slot);
        // The row's target, from the same rule the task row uses: an item reward, and nothing for a
        // reward with no item — nothing while the row is claimable, when the press collects instead,
        // and nothing once it is claimed, when there is nothing left to collect. An unclaimed row
        // that is not claimable keeps its lookup: a locked reward is still something to identify.
        // (In a viewer, reward icons never open recipes at all — see the adapters — because there
        // a reward is display-only; here the row carries the conditions that explain the gate.)
        // Collected from the drawing pass, like the rowTooltips below.
        RecipeLookups.Target target = (claimable || claimed) ? null : BookRowTargets.ofReward(reward);
        if (target != null) {
            book.rowItems.add(new QuestBookScreen.RowItem(row, target));
        }
        // A claimable row answers the press the way the Rewards menu's button does.
        if (claimable) {
            book.rewardClaimRows.add(new QuestBookScreen.RewardClaimRow(row, entry.id(), index));
        }
        if (row.contains(mouseX, mouseY)) {
            // The task row's rule, one member over: the player's explanation, never the author's.
            // A reward is collected rather than handed in, so there is no second line to pick.
            //
            // A table that hides its tooltip draws no hover here — the legacy `hide_tooltip` — unless
            // the row is locked: a gate the player does not meet is still explained, because hiding
            // why a reward is shut would read as a broken row rather than a quiet one.
            ClientQuestCache.TableSummary table = BookQuestEditor.tableOf(reward);
            boolean hideTooltip = table != null && table.hideTooltip() && !locked;
            if (!hideTooltip) {
                List<String> lines = new ArrayList<>(
                        QuestPanelLayout.playerTooltip("rewards", reward.type(), false, false));
                appendConditionLines(lines, reward.conditions(),
                        ClientQuestCache.rewardLockOf(entry.id(), index));
                if (claimable) {
                    lines.add(Component.translatable("tenet.screen.rewards.claim").getString());
                }
                else if (RecipeLookups.canOpen(target)) {
                    lines.add("Click for recipes");
                }
                book.rowTooltips.add(new QuestBookScreen.RowTooltip(row, lines));
            }
        }
        BookSettings.rowWash(r, row, contentRight, hover);

        int textX = x;
        ItemStack toDraw = reward.shown();
        net.minecraft.resources.Identifier rewardTexture = strings.texture();
        net.minecraft.resources.Identifier rewardSprite = strings.sprite();
        if (missingItem) {
            QuestBookScreen.drawItemPlaceholder(r, x, y, QuestBookScreen.ROW_ICON);
            textX = x + QuestBookScreen.ROW_ICON + 5;
        }
        else if (rewardTexture != null) {
            // The author's texture, blitted rather than drawn as a stack — the task row's rule, one
            // member over.
            r.texture(rewardTexture, x, y, QuestBookScreen.ROW_ICON, QuestBookScreen.ROW_ICON);
            textX = x + QuestBookScreen.ROW_ICON + 5;
        }
        else if (rewardSprite != null) {
            // The author's atlas sprite, drawn from the atlas — the task row's rule, one member over.
            r.sprite(rewardSprite, x, y, QuestBookScreen.ROW_ICON, QuestBookScreen.ROW_ICON, 0xFFFFFFFF);
            textX = x + QuestBookScreen.ROW_ICON + 5;
        }
        else if (r.icon(toDraw, x, y, QuestBookScreen.ROW_ICON)) {
            textX = x + QuestBookScreen.ROW_ICON + 5;
        }

        r.text(text, textX, textY,
                missingItem || locked ? ArmatureTheme.blocked() : ArmatureTheme.body());
        if (count != null) {
            r.text(count, textX + r.textWidth(text) + 5, textY, ArmatureTheme.faint());
        }
        if (locked) {
            r.text("locked", x + slot.width() - r.textWidth("locked"), textY, ArmatureTheme.blocked());
        }
        else if (claimed) {
            r.text("claimed", x + slot.width() - r.textWidth("claimed"), textY, ArmatureTheme.faint());
        }
    }

    // ------------------------------------------------------------------
    // Remembered rows
    // ------------------------------------------------------------------

    /**
     * Appends a locked row's explanation: the gates it is missing, by the server's own naming.
     *
     * <p>Only the unmet ones. The server sends which condition indices failed, and the tree carried what
     * those conditions say, so a row with three gates and one unmet names the one — and the list is the
     * same order the author wrote, because the mask indexes into it.
     */
    static void appendConditionLines(List<String> lines,
                                             List<ClientQuestCache.ConditionEntry> conditions,
                                             List<Integer> unmet) {
        if (unmet.isEmpty() || conditions.isEmpty()) {
            return;
        }
        lines.add("Locked - needs:");
        for (int index : unmet) {
            if (index >= 0 && index < conditions.size()) {
                lines.add("  " + conditions.get(index).line());
            }
        }
    }
}
