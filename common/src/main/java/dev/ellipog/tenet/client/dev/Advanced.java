package dev.ellipog.tenet.client.dev;

import dev.ellipog.tenet.client.DevMode;
import dev.ellipog.tenet.quest.EditorField;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * What Normal mode hides: the one place the editor's depth is described.
 *
 * <h2>The two depths, and which flag owns them</h2>
 *
 * <p>{@link DevMode#on()} asks whether a player may edit at all; {@link DevMode#advanced()} asks how much
 * of the editor to show. Normal is the default -- the basic set, so a first-time or occasional author meets
 * a page they can read -- and Advanced is everything, which is what the editor drew before this class
 * existed. This class is the only reader of the flag, so "what does Normal hide" is one answer in one file
 * rather than a question each panel answers for itself.
 *
 * <h2>Why the vocabulary is three sets and not one</h2>
 *
 * <p>Because the same word means different things in two panels: {@code aliases} is a quest's extra ids on
 * the settings page and a chapter's on the chapter tab, and the chapter tab's rules are keyed by the same
 * field names the quest's own rules use ({@code minRequired}, {@code prerequisiteMode}). One set would hide
 * a control in a panel nobody asked about, so each vocabulary gets its own:
 *
 * <ul>
 *   <li>{@link #fieldKeys()} -- a task's, a reward's or a condition's own field, by the path its form
 *       declares. Shared by the card's fields, the inspector's rows and a table entry's fold, because all
 *       three name the same field.</li>
 *   <li>{@link #rowKeys()} -- a row of the quest settings page that is not a whole section.</li>
 *   <li>{@link #hiddenSections()} -- a named section of the pack's Assets panel.</li>
 * </ul>
 *
 * <h2>Whole sections are gates, not keys, and the reason is nesting</h2>
 *
 * <p>A filter that dropped a section by its heading key would have to know where the section ends -- and a
 * section can hold headings of its own: the Colours section's rows come in groups, each with a heading, so
 * a cut that stopped at the next heading would let the colours back in on the far side of a group's name.
 * The row builders already have the right structure for this: every section is written inside its own block
 * ({@code if (!folded.contains(RULES))}, and its like), so a section Normal mode hides is one
 * {@code if (Advanced.on())} around the block that builds it. That is also the robust place rather than the
 * fragile one: a row added later inside a hidden section is hidden with it, where a set of keys would have
 * to be kept in step by hand for ever.
 *
 * <h2>What this class does not decide</h2>
 *
 * <p>It never changes a value, never closes a panel and never refuses an edit: a hidden field keeps what
 * the file says, and its value comes back with one press of the latch. <b>Hiding is unconditional</b> -- a
 * field is hidden whether or not the file names it -- because the alternative, showing the ones that are
 * set, would make what a page contains depend on the quest being looked at, and the cut would stop being
 * describable in one file. Nothing here runs on the server side either: a depth is what this client draws,
 * not what the pack contains.
 *
 * <p>The cut is asserted in both directions by {@code AdvancedTest}: every key in every set is produced by
 * a surface that really has it -- so a rename fails the build rather than quietly hiding nothing -- every
 * registered type keeps a field to fill in at the shallow depth, and no heading is left standing over the
 * rows a gate took away.
 */
public final class Advanced {

    /**
     * A task's, a reward's or a condition's own field, by the path its form declares.
     *
     * <h2>Why these, and not the ones that feel similar</h2>
     *
     * <p>Each of these is a refinement of a value the author has already given, or an escape hatch: the
     * cadence a task is re-checked at, how strictly a carried stack must match, whether only crafted items
     * count, one criterion of an advancement rather than the whole thing, the three filters a kill task can
     * carry, whether a kill or a break by automation counts, how long an observation has to last, the
     * permission a reward's command runs at, and the ways a reward can be made quiet, random, extra or
     * exclusive. What stays basic is what the entry <i>is</i> --
     * the item, the count, the dimension, the target, the value.
     *
     * <p>So the rule for a new field is the one this list states: if it says <b>what</b>, it is basic; if it
     * says <b>how exactly</b>, it is Advanced. A field that is neither is a question for whoever adds it,
     * and this list is where the answer goes.
     */
    private static final Set<String> FIELD_KEYS = Set.of(
            // A task's or a reward's own behaviour.
            "autoSubmitTicks", "match", "onlyFromCrafting", "manualOnly",
            // One criterion of an advancement, rather than all of it.
            "criterion",
            // What a kill counts, beyond the entity's id.
            "entityTypeTag", "customName", "nbtFilter",
            // Whether a kill or a break by automation counts too.
            "countFakePlayers",
            // How long an observation lasts.
            "timer",
            // A command reward: the permission it runs at, and whether it says so in chat.
            "permissionLevel", "silent",
            // An item reward: the random extra, and the skip-if-carried rule.
            "randomBonus", "onlyOne",
            // The two switches every reward carries about how it is collected.
            "excludeFromClaimAll", "ignoreRewardBlocking",
            // Whose stages a stage task reads, or a stage reward grants to.
            "teamStage");

    /**
     * A row of the quest settings page that is hidden on its own, inside a section that stays.
     *
     * <p>Only two of that page's sections hold rows of both depths: shape and size, where the rotation and
     * the icon's scale refine a shape and an icon that are basic, and the dependency section, where the list
     * and the two ways to add to it are the point and the four rules that qualify it are not. Every other
     * row Normal hides goes with its whole section, which is a gate at the builder -- see the class comment.
     */
    private static final Set<String> ROW_KEYS = Set.of(
            "rotation", "iconScale",
            "prerequisiteMode", "minRequired", "maxCompletableDependents", "exclusiveGroup");

    /**
     * A section of the pack's Assets panel, by its own name.
     *
     * <p>The tables are what an author meets late: a table is an asset with its own file, a browser and an
     * editor, and a quest that hands one out has to exist before making one is worth the trip. The panel
     * still opens in Normal mode and still lists the quest files, which is the half of it every author uses.
     */
    private static final Set<String> SECTION_KEYS = Set.of("tables");

    /**
     * A canvas element's own field, by the name its rows are keyed with.
     *
     * <h2>Why this is a fourth vocabulary and not an entry in one of the three above</h2>
     *
     * <p>Because a name here means something different from the same name there, and one of them collides
     * today: {@code rotation} is a <b>quest settings row</b> — how far a node is turned — and it is also an
     * image's own angle. Putting it in {@code ROW_KEYS} would hide a picture's rotation whenever it hid a
     * quest's, which is a control disappearing from a panel nobody asked about — the exact fault the class
     * comment gives as the reason each panel gets its own set.
     *
     * <p>The cut is the same rule the other three state: what an element <b>is</b> is basic — its words, its
     * colour, its picture, its box, which way a line points — and what qualifies it is Advanced: where it
     * sits in the draw order, whether it is turned or dimmed or tinted, whether it is a draft, what it waits
     * for, and what pressing it does.
     */
    private static final Set<String> ELEMENT_KEYS = Set.of(
            // Where it sits, and how it is drawn rather than what it is. **Not its `x` and `y`**: where an
            // element is is the most basic fact about it -- the quest settings page's own X and Y rows are
            // basic for the same reason -- and the drag is the fast way to change it, not the only way.
            "order", "rotation", "corner", "tint", "alpha",
            // Whether a reader sees it at all, and what it waits for.
            "dev", "requires",
            // Whether the editor's drag may move it. Advanced for the same reason `dev` is: pinning
            // is something an author reaches for once a layout is done, not while placing it.
            "locked",
            // A refinement of the border an author already gave the box.
            "borderWidth",
            // The words painted into a picture, and every property of them the form offers -- the inset and
            // the shadow included, which the worked example in `tools/quests` sets and which this round gave
            // rows at last. `image.texture` is deliberately absent: choosing the file is what a picture *is*,
            // so its picker is basic.
            "title", "label.onImage", "label.shadow", "label.inset", "label.hAlign", "label.vAlign",
            // And what a press does, which is the one field here that is about behaviour rather than looks.
            "click.type", "click.data");

    private Advanced() {
    }

    /**
     * Whether the editor is showing everything.
     *
     * <h2>Which way this reads, said out loud because it is the one thing here that is easy to invert</h2>
     *
     * <p>{@code on()} is <b>Advanced</b>: true means every menu shows everything, false means the basic set.
     * So a section gate reads {@code if (Advanced.on())} -- add the section -- and every predicate about
     * hiding reads <b>the other way</b>: {@code !on() && key}. The two are inverses of each other by nature
     * and there is no way to make one of them unnecessary, which is why {@link #hidesField} and
     * {@link #hidesRow} are named for the question a caller is actually asking ("does this depth hide it")
     * rather than for the flag.
     *
     * <p>Read through {@link DevMode} rather than held here, because the flag is this client's preference in
     * this client's file and this class is only what reads it.
     */
    public static boolean on() {
        return DevMode.advanced();
    }

    /**
     * Whether one of a form's or a fold's fields is hidden at this depth.
     *
     * <p>A name this build does not know is shown, which is the direction that cannot hide a control by
     * accident: an addon's field is basic the day it registers, and marking it Advanced is then a request
     * rather than a default.
     */
    public static boolean hidesField(String path) {
        return !on() && path != null && FIELD_KEYS.contains(path);
    }

    /** Whether one of the settings page's own rows is hidden at this depth. */
    public static boolean hidesRow(String key) {
        return !on() && key != null && ROW_KEYS.contains(key);
    }

    /**
     * The fields of one entry's form that this depth draws.
     *
     * <h2>Why the filter is here rather than at each of the six callers</h2>
     *
     * <p>Because the answer has to be one answer. {@code QuestPanelLayout.editorFor} and its condition twin
     * are what the card's cells and its per-entry line counts come from, what {@code EntryFormLayout.lines}
     * measures, what the widget pass builds a field for, and what resolves a path back to the field that
     * owns it -- so a hidden field that still had a target, or a drawn field with none, is only possible if
     * two of those disagree. Filtering where they all meet makes that impossible rather than unlikely.
     */
    public static List<EditorField> fields(List<EditorField> fields) {
        if (fields == null || fields.isEmpty() || on()) {
            return fields == null ? List.of() : fields;
        }
        List<EditorField> shown = new ArrayList<>(fields.size());
        for (EditorField field : fields) {
            if (!FIELD_KEYS.contains(field.path())) {
                shown.add(field);
            }
        }
        return List.copyOf(shown);
    }

    /**
     * The rows of the quest settings page that this depth draws, in order.
     *
     * <p>A plain filter -- no heading logic -- and that is because no heading of that page is ever marked:
     * the three sections Normal mode hides whole (visibility, rules and the identity extras) are gated at
     * the builder, where the section is written. What is left for this method is the handful of rows that
     * are hidden <i>inside</i> a section that stays, and a row can be dropped without leaving a heading over
     * nothing. See the class comment for why whole sections are gates rather than keys.
     */
    public static List<QuestSettingsLayout.Row> settings(List<QuestSettingsLayout.Row> rows) {
        if (rows == null || rows.isEmpty() || on()) {
            return rows == null ? List.of() : rows;
        }
        List<QuestSettingsLayout.Row> shown = new ArrayList<>(rows.size());
        for (QuestSettingsLayout.Row row : rows) {
            if (!ROW_KEYS.contains(row.key())) {
                shown.add(row);
            }
        }
        return List.copyOf(shown);
    }

    /** Whether a named section of the Assets panel is shown at this depth. */
    public static boolean showsSection(String name) {
        return name == null || on() || !SECTION_KEYS.contains(name.toLowerCase(Locale.ROOT));
    }

    /**
     * Whether one of the Chapter tab's element rows is hidden at this depth.
     *
     * <p>The key is the field name alone rather than the row's whole key, because the row's key carries the
     * element's id — {@code element.box.rotation} — and the id is the one part of it that has nothing to do
     * with the depth. See {@link #ELEMENT_KEYS} for why the answer is its own set.
     */
    public static boolean hidesElement(String field) {
        return !on() && field != null && ELEMENT_KEYS.contains(field);
    }

    /**
     * Whether one of the Chapter tab's link rows is hidden at this depth.
     *
     * <p>The cut is the same rule the elements keep: what a link <b>is</b> is basic — its target, where
     * it sits, how to point it and where it goes — and what qualifies its drawing is Advanced. A link
     * has six rows and two of them are refinements, so the shallow form is the working form rather
     * than a locked one.
     */
    public static boolean hidesLink(String field) {
        return !on() && field != null && LINK_KEYS.contains(field);
    }

    /**
     * The link fields Normal mode puts away: the shape and the size.
     *
     * <p>Refinements of the drawing an author already placed, and for the same reason the element's
     * tint and rotation are Advanced: a marker that points at the right quest in the wrong shape is
     * a finished job with a nicer shape available, not an unfinished one.
     */
    private static final Set<String> LINK_KEYS = Set.of("shape", "size");

    // ------------------------------------------------------------------
    // What the cut is, for the test that keeps it honest
    // ------------------------------------------------------------------

    /** Every field name this depth hides. Exposed so the cut can be checked against the registries. */
    public static Set<String> fieldKeys() {
        return FIELD_KEYS;
    }

    /** Every settings-page row key this depth hides. */
    public static Set<String> rowKeys() {
        return ROW_KEYS;
    }

    /** Every Assets section this depth hides, in the lower-case spelling {@link #showsSection} reads. */
    public static Set<String> hiddenSections() {
        return SECTION_KEYS;
    }

    /** Every canvas element field this depth hides, by the name {@link #hidesElement} reads. */
    public static Set<String> elementKeys() {
        return ELEMENT_KEYS;
    }

    /** Every quest link field this depth hides, by the name {@link #hidesLink} reads. */
    public static Set<String> linkKeys() {
        return LINK_KEYS;
    }
}
