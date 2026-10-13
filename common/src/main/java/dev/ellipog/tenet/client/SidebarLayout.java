package dev.ellipog.tenet.client;

import dev.ellipog.armature.client.ui.kit.Insets;
import dev.ellipog.armature.client.ui.kit.Layout;
import dev.ellipog.armature.client.ui.kit.Measure;
import dev.ellipog.armature.client.ui.kit.Outline;
import dev.ellipog.armature.client.ui.kit.Stack;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * The book's sidebar: a collapsible list of group headings and the chapters under them.
 *
 * <h2>What this is, and what it deliberately is not</h2>
 *
 * <p>It is the <i>particular</i> two-level tree one screen shows in its left column. What it is not is
 * any of the three things it sits between: the outline that decides which rows are visible is
 * {@link Outline}'s, the arithmetic that places them is {@link Stack}'s, and the clipping and
 * hit-testing are {@link dev.ellipog.armature.client.ui.kit.ScrollView}'s. This class exists to join
 * them, and it does nothing else — which is the point, because each of those three was extracted for
 * the same reason: a screen that answers a layout question cannot be asked about it.
 *
 * <p>So {@code SidebarLayoutTest} can assert what is actually true of a sidebar — that a collapsed
 * group hides exactly its own chapters, that a chapter is one indent deeper than its heading, that a
 * heading with no chapters carries no disclosure arrow — without a window, a font or a widget.
 *
 * <h2>Row keys, and why they are prefixed strings rather than ids</h2>
 *
 * <p>A group and a chapter are in <b>different namespaces</b>: {@link
 * dev.ellipog.tenet.quest.QuestIndex} keys groups and chapters in separate tables, so a group called
 * {@code stone_age} and a chapter called {@code stone_age} are both legal and both meaningful, and
 * nothing about either is a mistake. Chip-based keys would collide in the outline — a key is required
 * to be unique — so a perfectly good questline would throw when its sidebar was built.
 *
 * <p>That is not hypothetical for the format this reads: a group folder's id is its folder name and a
 * chapter folder's id is its folder name, and the two kinds of folder are drawn from one vocabulary by
 * one author. {@link #groupKey} and {@link #chapterKey} make the two sets disjoint by construction,
 * which is a stronger guarantee than a naming convention anyone has to remember.
 *
 * <h2>The flat fallback is the empty-group case, and it needs no branch</h2>
 *
 * <p>A server older than groups sends no headings at all, so every chapter arrives with no group and
 * is added to the outline as a <b>root</b>. That draws exactly today's flat chapter list, with no
 * indent and no headings to collapse — which is the right screen for a questline that has no groups,
 * and the same drawing as a version-2 questline that declares an empty group list. So absence needs
 * no flag and no code path: it is the same input, and it produces the same rows.
 *
 * <p><b>Names no Minecraft, and holds no widgets.</b> Every field is a string, a number or a boolean,
 * and {@link #rows} returns values rather than controls — which is what lets the sidebar's behaviour be
 * asserted directly instead of being judged by clicking it.
 */
public final class SidebarLayout {

    /**
     * What a group row's key starts with.
     *
     * <p>A prefix rather than a separate key type, because the outline it goes into is keyed by one
     * type and a second type would mean a second outline — see the class note on why the two
     * namespaces have to be kept apart.
     */
    public static final String GROUP_PREFIX = "group:";

    /** What a chapter row's key starts with. */
    public static final String CHAPTER_PREFIX = "chapter:";

    /**
     * What an inline-edit row's key starts with.
     *
     * <p>The sidebar's create and rename happen in the list itself now: the row being typed is a
     * synthetic row the screen splices into the rows below, keyed apart from both namespaces above
     * so it can never collide with a real group or chapter. The layout places it like any row; what
     * goes in it — a field, a drawn status line — is the screen's.
     */
    public static final String INLINE_PREFIX = "inline:";

    /** Drawn before a heading whose chapters are showing. */
    public static final String OPEN_MARKER = "\u25bc ";

    /** Drawn before a heading whose chapters are hidden. */
    public static final String CLOSED_MARKER = "\u203a ";

    /**
     * A measure that is never asked anything.
     *
     * <p>Every element this class contributes is a {@link Stack#row} — a block, whose height is declared
     * rather than measured — because a row is a place a <b>widget</b> goes, and a widget draws its own
     * label. {@code Stack.build} requires a measure regardless, so this satisfies it.
     *
     * <p>Stated plainly rather than left as a mystery argument: if a {@code TEXT} element is ever added
     * to {@link #stack}, this measure starts being consulted, and a sixth of a pixel per character is
     * the wrong answer. That would show up immediately as a row that is too short for its label, which
     * is the honest kind of failure — but the substitution is worth knowing about before it happens.
     */
    private static final Measure UNUSED_MEASURE = Measure.monospace(6, 9);

    /**
     * A group heading.
     *
     * @param id                 the group's id
     * @param title              what the row says
     * @param collapsedByDefault whether its chapters are hidden the first time a tree is seen
     */
    public record Group(String id, String title, boolean collapsedByDefault) {
    }

    /**
     * A chapter, and the group it belongs to.
     *
     * @param id      the chapter's id
     * @param title   what the row says
     * @param groupId the id of the group it hangs under, or empty for a server that describes none
     * @param locked  whether the chapter's own gate is unmet, so the row is drawn shut
     * @param hidden  whether the chapter is withheld from a reader until that gate is met. A hidden row
     *                is <b>absent from the layout</b> rather than flagged on a drawn one — the same
     *                mechanism a collapsed group uses, and for the same reason: a row that is not in the
     *                list cannot be drawn by a caller that forgot to check a flag. See {@link #of}
     */
    public record ChapterRow(String id, String title, String groupId, boolean locked, boolean hidden) {

        /** A chapter with no gate: open, and shown. */
        public ChapterRow(String id, String title, String groupId) {
            this(id, title, groupId, false, false);
        }
    }

    /**
     * One row that should be drawn, and everything the drawing needs to know about it.
     *
     * <p>Produced rather than drawn, so the same list answers the widget pass and any test. A caller
     * that had to ask the outline four questions per row — is it visible, how deep, does it collapse,
     * is it open — would be re-deriving on every frame what was decided once here, and each of those
     * four is a place a two-level list can be got subtly wrong.
     *
     * @param key         what places this row, and what a click on it is routed by
     * @param id          the group's or chapter's own id, which is what the screen selects by
     * @param title       the heading's or chapter's title, without any chevron
     * @param depth       how far to indent, in levels. A group is 0, a chapter under one is 1
     * @param group       whether this row is a heading rather than a chapter
     * @param collapsible whether it has anything under it, and so whether it gets an arrow
     * @param expanded    whether its children are showing
     * @param locked      whether the row is a chapter whose gate is unmet, so the drawing dims it
     */
    public record Row(String key, String id, String title, int depth, boolean group, boolean collapsible,
                      boolean expanded, boolean locked) {

        /** A row with no gate on it: every heading, and every chapter that is open. */
        public Row(String key, String id, String title, int depth, boolean group, boolean collapsible,
                   boolean expanded) {
            this(key, id, title, depth, group, collapsible, expanded, false);
        }

        /**
         * What the row's widget is labelled with.
         *
         * <p>The chevron is part of the label rather than a second control, and that is a decision about
         * what is clickable: the whole row toggles, so the whole row has to look like the thing that
         * toggles. A separate arrow button would be a 10-pixel target beside a 120-pixel row that looks
         * equally pressable and does the same thing — which is one affordance described twice.
         *
         * <p>A heading with nothing under it gets <b>no</b> marker at all, rather than an arrow that does
         * nothing. That is the same rule the class note gives for {@link #isCollapsible}: a disclosure
         * arrow on a row that cannot disclose is a control that promises an action and then ignores the
         * click.
         */
        public String label() {
            if (!group || !collapsible) {
                return title;
            }
            return (expanded ? OPEN_MARKER : CLOSED_MARKER) + title;
        }
    }

    private final Outline<String> outline;
    private final Map<String, Group> groupsByKey;
    private final Map<String, ChapterRow> chaptersByKey;

    private SidebarLayout(Outline<String> outline, Map<String, Group> groupsByKey,
                          Map<String, ChapterRow> chaptersByKey) {
        this.outline = outline;
        this.groupsByKey = groupsByKey;
        this.chaptersByKey = chaptersByKey;
    }

    // ------------------------------------------------------------------
    // Building
    // ------------------------------------------------------------------

    /**
     * Builds a sidebar from the headings a server sent and the chapters that arrived with them.
     *
     * <p>Order is <b>the order the two lists are in</b> — which is the server's declaration order, which
     * for the folder layout is folder-name order for groups and the manifest's order for chapters. That
     * ordering belongs to the caller and is not touched here: an outline draws what it is given, and a
     * class that sorted would silently reorder somebody's book.
     *
     * <p>Chapters with no heading come <b>first</b>, before every group: that is the legacy default
     * group, which always sorts before the named ones, and a converted pack's welcome chapter only
     * reads as first when it draws first. Their relative order is the server's, like everything
     * else here.
     *
     * <p>A chapter whose {@code groupId} names a heading the server did not send is added as a
     * <b>root</b> rather than dropped. That case is a server bug rather than a client one, and the two
     * available responses are to lose the chapter or to show it at the top level; losing content is the
     * worse one, and it is the one a player cannot see or report.
     */
    public static SidebarLayout of(List<Group> groups, List<ChapterRow> chapters) {
        Objects.requireNonNull(groups, "groups");
        Objects.requireNonNull(chapters, "chapters");

        Map<String, Group> groupsByKey = new LinkedHashMap<>();
        Map<String, ChapterRow> chaptersByKey = new LinkedHashMap<>();

        // A chapter names its group, and the two lists arrive separately, so the children are collected
        // per heading before anything is added. That indirection is what lets the outline be built in
        // the order a sidebar *reads* — a heading, the chapters under it, the next heading — rather than
        // in the order the two lists happen to be passed in.
        //
        // Adding every heading and then every chapter was the obvious first version, and it is wrong in
        // a way that is invisible to every test that only counts rows: `visibleRows()` preserves
        // insertion order, so the sidebar would draw all of its headings stacked at the top and all of
        // the chapters below them. Both lists correct, every count correct, and a screen that reads as
        // somebody having dropped the groups in a heap.
        Map<String, List<ChapterRow>> chaptersPerGroup = new LinkedHashMap<>();
        List<ChapterRow> ungrouped = new ArrayList<>();

        for (Group group : groups) {
            // Two headings with one id. Nothing in the client can be asked to resolve this, and both of
            // the alternatives are worse: the outline refuses a duplicate key outright, so throwing
            // would turn a bad message into a screen that cannot be opened at all. First wins, and the
            // rest are dropped.
            groupsByKey.putIfAbsent(groupKey(group.id()), group);
        }

        for (ChapterRow chapter : chapters) {
            if (chapter.hidden()) {
                // Absent rather than flagged, and this is the one place that decides it. A chapter whose
                // author asked for it to be withheld until its gate is met is not a row with a flag on
                // it: it is not a row. The alternative -- drawing it and letting the caller check -- is
                // the shape that produced this class's note about a caller forgetting, one level down.
                //
                // Note what this is *not*: the gate itself. A locked chapter that is not hidden is
                // listed, and `locked` is carried so the drawing can say so.
                continue;
            }
            if (chaptersByKey.putIfAbsent(chapterKey(chapter.id()), chapter) != null) {
                continue;
            }
            String parent = groupKey(chapter.groupId());
            if (groupsByKey.containsKey(parent)) {
                chaptersPerGroup.computeIfAbsent(parent, key -> new ArrayList<>()).add(chapter);
            }
            else {
                // A group the server did not send, or a server older than groups, where the id is empty.
                // Both become a root — see the class note on why losing the chapter would be worse.
                ungrouped.add(chapter);
            }
        }

        Outline<String> outline = Outline.of();
        // The heading-less chapters first: see the order contract above. A group the server did
        // not send, or a server older than groups, reads the same way — roots before headings.
        for (ChapterRow chapter : ungrouped) {
            outline.add(chapterKey(chapter.id()), null, true);
        }
        for (Group group : groupsByKey.values()) {
            String key = groupKey(group.id());
            // `expandedByDefault` is the negation of the authored flag, and that inversion is the whole
            // of how a server's "collapsed" reaches an outline that thinks in terms of "expanded".
            outline.add(key, null, !group.collapsedByDefault());
            for (ChapterRow chapter : chaptersPerGroup.getOrDefault(key, List.of())) {
                outline.add(chapterKey(chapter.id()), key, true);
            }
        }

        // Once, here, and nowhere else. What the player toggles afterwards belongs to the outline for as
        // long as the caller keeps it; this is the state a tree is first shown in, and re-running it on
        // every frame would undo each toggle as it was made. See Outline.seedFromDefaults.
        outline.seedFromDefaults();
        return new SidebarLayout(outline, groupsByKey, chaptersByKey);
    }

    /** A group row's key, from a group id. */
    public static String groupKey(String id) {
        return GROUP_PREFIX + id;
    }

    /** A chapter row's key, from a chapter id. */
    public static String chapterKey(String id) {
        return CHAPTER_PREFIX + id;
    }

    /** Whether a row key names a heading. False for anything unrecognised, which is not a group. */
    public static boolean isGroupKey(String key) {
        return key != null && key.startsWith(GROUP_PREFIX);
    }

    /** Whether a row key names the inline-edit scaffolding rather than a group or a chapter. */
    public static boolean isInlineKey(String key) {
        return key != null && key.startsWith(INLINE_PREFIX);
    }

    /**
     * The id a row key was built from, with its namespace prefix removed.
     *
     * <p>The prefix is dropped rather than skipped by a caller, because the two prefixed forms differ
     * from the id and every use of this — selecting a chapter, toggling a group — wants the id. A caller
     * that stripped it itself would be a second place that knows the prefix exists.
     */
    public static String idOf(String key) {
        if (key == null) {
            return "";
        }
        if (key.startsWith(GROUP_PREFIX)) {
            return key.substring(GROUP_PREFIX.length());
        }
        if (key.startsWith(CHAPTER_PREFIX)) {
            return key.substring(CHAPTER_PREFIX.length());
        }
        return key;
    }

    // ------------------------------------------------------------------
    // Reading and toggling
    // ------------------------------------------------------------------

    /**
     * Every row that should be drawn, in declaration order.
     *
     * <p>Hidden rows are <b>absent</b> rather than flagged, which is the whole reason the outline exists:
     * a caller iterating this cannot draw a chapter whose group is collapsed, because it is not in the
     * list to draw. A visibility flag on every row would be the same information in a form a caller can
     * forget to check.
     */
    public List<Row> rows() {
        List<Row> out = new ArrayList<>();
        for (String key : outline.visibleRows()) {
            Group group = groupsByKey.get(key);
            if (group != null) {
                out.add(new Row(key, group.id(), group.title(), outline.depth(key), true,
                        outline.isCollapsible(key), outline.isExpanded(key)));
                continue;
            }
            ChapterRow chapter = chaptersByKey.get(key);
            if (chapter != null) {
                // A chapter is never collapsible — it is a leaf — so both of its last two fields are
                // constants. `expanded` is false rather than true because false is what "has nothing to
                // show" means for every other reader of this record, and a leaf claiming to be expanded
                // would draw an arrow if the `collapsible` check were ever dropped.
                out.add(new Row(key, chapter.id(), chapter.title(), outline.depth(key), false, false, false,
                        chapter.locked()));
            }
        }
        return List.copyOf(out);
    }

    /**
     * The rows as a stack, laid out in a column of {@code width}.
     *
     * <p>A built {@link Layout} rather than a {@link Stack}, so the height the scrollbar's range comes
     * from and the positions the widgets are moved to are <b>one computation</b>. That is the property
     * the whole kit was extracted for, and it is worth restating here because this is the third place in
     * the repo that could have got it wrong: a caller that built a stack twice — once to measure, once to
     * place — would have a scrollbar that disagrees with its own rows by whatever drifted between the two
     * calls.
     *
     * <p>The indent is an {@link Insets} on the row rather than something the drawing adds, so the
     * rectangle a widget is placed in is the indented one. A caller that drew an indent instead would
     * have a clickable row wider than it looked, which is the fault this project has already recorded
     * once under a different name.
     */
    public Layout stack(int width) {
        return stack(rows(), width);
    }

    /**
     * A stack from the given rows rather than this layout's own: for a caller that spliced an
     * inline-edit row into {@link #rows()}.
     *
     * <p>The rows are values, so splicing is the caller's list work and this stays the one place
     * that turns rows into slots. A synthetic row is placed exactly like a real one — same pitch,
     * same indent rule — which is what keeps the scrollbar honest while an edit is open.
     */
    public Layout stack(List<Row> rows, int width) {
        return composition(rows).build(Math.max(0, width), UNUSED_MEASURE);
    }

    /**
     * The visible index of the row with this key, or -1 when no row carries it.
     *
     * <p>Where the screen replaces a row with its inline editor: a rename edits in place, so the
     * field takes the row's own index rather than being appended somewhere the author has to find.
     */
    public int indexOfKey(String key) {
        List<Row> rows = rows();
        for (int i = 0; i < rows.size(); i++) {
            if (rows.get(i).key().equals(key)) {
                return i;
            }
        }
        return -1;
    }

    /**
     * The visible index one past the last row of the named group's section: where a chapter created
     * into that group is inserted. Empty names the ungrouped section at the top; an unknown group
     * answers the end of the list, and the server appends there too.
     *
     * <p>Sections are contiguous — a heading followed by its chapters — so the end of one is the next
     * heading or the list. A collapsed group's chapters are absent from the rows, which puts the
     * insertion point directly under its heading: exactly where the new row will appear once the
     * broadcast arrives and the group opens onto it.
     */
    public int endOfSection(String groupId) {
        List<Row> rows = rows();
        if (groupId == null || groupId.isEmpty()) {
            int i = 0;
            while (i < rows.size() && !rows.get(i).group()) {
                i++;
            }
            return i;
        }
        String want = groupKey(groupId);
        for (int i = 0; i < rows.size(); i++) {
            if (want.equals(rows.get(i).key())) {
                int j = i + 1;
                while (j < rows.size() && !rows.get(j).group()) {
                    j++;
                }
                return j;
            }
        }
        return rows.size();
    }

    /**
     * One row's pitch: its height and the gap under it.
     *
     * <p>What one wheel notch moves, and it is derived from the same two numbers {@link #composition}
     * spaces rows with rather than being a third. A scroll rate written out at the call site is a scroll
     * rate that can disagree with the spacing — and the symptom of that is subtle enough to live with
     * forever: the list drifts by two pixels a notch, so after a while a row sits half under the header.
     */
    public static int pitch() {
        return BookGeometry.SIDEBAR_ROW_HEIGHT + BookGeometry.SIDEBAR_ROW_GAP;
    }

    /**
     * The rows as an unbuilt stack, for a caller that wants to add to it or measure it itself.
     *
     * <p>The gap goes <b>before</b> each row rather than after it, so the list does not end with one.
     * A trailing gap is four pixels of nothing that {@code Layout.height()} still counts — the height is
     * the bottom edge of the lowest slot, and a gap places no slot, so a trailing one disappears from the
     * height entirely; spacing the rows from the front is what makes the height and the drawing agree.
     * Written the other way first, and what it produced was a list that could be scrolled four pixels
     * past its own last row, giving a scrollbar a range with nothing in it.
     */
    public Stack composition() {
        return composition(rows());
    }

    /**
     * A composition from the given rows rather than this layout's own, for the same caller as
     * {@link #stack(List, int)}: the gap rule is the composition's, so it moves with the loop
     * rather than being reimplemented beside it.
     */
    public Stack composition(List<Row> rows) {
        Stack stack = Stack.stack();
        for (int i = 0; i < rows.size(); i++) {
            if (i > 0) {
                stack.gap(BookGeometry.SIDEBAR_ROW_GAP);
            }
            Row row = rows.get(i);
            stack.row(row.key(), BookGeometry.SIDEBAR_ROW_HEIGHT,
                    new Insets(row.depth() * BookGeometry.SIDEBAR_INDENT, 0, 0, 0));
        }
        return stack;
    }

    /**
     * Flips a heading open or closed, and reports whether the rows changed.
     *
     * <p>False for a chapter, and false for a heading with nothing under it — both because there is
     * nothing to expand and both via {@link Outline#toggle}, so there is one place that decides. The
     * caller uses the answer to avoid a widget rebuild that would change nothing: a screen that rebuilt
     * its controls on every click would drop and recreate every button for a click that meant nothing,
     * which costs a frame and can lose focus.
     */
    public boolean toggle(String key) {
        return outline.toggle(key);
    }

    /** Whether a row's children are showing. */
    public boolean isExpanded(String key) {
        return outline.isExpanded(key);
    }

    /** Every group row's key, whether or not it is showing. */
    public Set<String> groupKeys() {
        return Set.copyOf(groupsByKey.keySet());
    }

    /**
     * Puts remembered expansions back over the authored defaults.
     *
     * <h2>Why this exists</h2>
     *
     * <p>A sidebar is rebuilt from a new layout every time the tree arrives — and every structural edit
     * (a drag, a create, a delete) broadcasts a new tree. Seeding from {@code collapsedByDefault} each
     * time is what snapped every group shut the moment something was dragged into one, so an author
     * working three levels down had to reopen the path after every drop.
     *
     * <p>Only groups that still exist are touched. Everything else in the map is memory of a group that
     * has been renamed or deleted, and applying it would either throw or do nothing; the caller prunes
     * those with {@link #groupKeys()}.
     *
     * @param remembered a group key to whether it was open, as the player left it
     */
    public void applyExpansion(Map<String, Boolean> remembered) {
        for (Map.Entry<String, Boolean> entry : remembered.entrySet()) {
            String key = entry.getKey();
            if (!groupsByKey.containsKey(key) || isExpanded(key) == entry.getValue()) {
                continue;
            }
            toggle(key);
        }
    }

    /** How many rows would be drawn. */
    public int rowCount() {
        return outline.visibleRowCount();
    }

    /** How many chapters there are, hidden or not. */
    public int chapterCount() {
        return chaptersByKey.size();
    }

    /** How many headings there are. */
    public int groupCount() {
        return groupsByKey.size();
    }

    /** The outline itself, for a caller that needs to ask a question this class does not expose. */
    public Outline<String> outline() {
        return outline;
    }

    @Override
    public String toString() {
        return "SidebarLayout(" + groupsByKey.size() + " group(s), " + chaptersByKey.size()
                + " chapter(s), " + rowCount() + " row(s) shown)";
    }
}
