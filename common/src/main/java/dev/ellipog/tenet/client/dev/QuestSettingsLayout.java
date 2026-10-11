package dev.ellipog.tenet.client.dev;

import com.google.gson.JsonObject;

import dev.ellipog.armature.client.ui.inspect.InspectLayout;
import dev.ellipog.armature.client.ui.kit.Layout;
import dev.ellipog.armature.client.ui.kit.Measure;
import dev.ellipog.armature.client.ui.kit.Slot;
import dev.ellipog.armature.client.ui.kit.Stack;
import dev.ellipog.armature.client.ui.kit.Viewport;
import dev.ellipog.tenet.client.BookGeometry;
import dev.ellipog.tenet.quest.PrerequisiteMode;
import dev.ellipog.tenet.quest.QuestLayout;
import dev.ellipog.tenet.quest.QuestShape;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Function;

/**
 * The quest settings page's arithmetic: the frame, the rows, the swatch grid, and the sliders.
 *
 * <h2>Why this is a class and not the screen's own sums</h2>
 *
 * <p>Because everything worth being wrong about here is arithmetic on integers — does a swatch's
 * rectangle contain the point the press landed on, does a slider's knob sit where the value says, does
 * the page still lay out when the window is small enough for the card to be at its minimum — and
 * arithmetic on integers is the thing this project's tests can hold without a client. The screen draws
 * what this says and asks it where the controls are; the drawing itself is {@code QuestSettingsPanel}'s.
 *
 * <h2>One derivation for the drawing and the press</h2>
 *
 * <p>{@link #cellRect} is how a swatch is drawn and {@link #cellAt} is how it is pressed, and the second
 * is defined in terms of the first's arithmetic rather than beside it. A grid drawn at one spacing and
 * hit at another is a grid an author cannot aim at, and the failure reads as a broken mouse rather than
 * as arithmetic — which is the same argument the item picker's rows and the tools panel's hotspots are
 * built on.
 *
 * <h2>What the page is</h2>
 *
 * <p>A live preview on the left, a column of controls on the right that scrolls when it does not fit,
 * and a line at the bottom that says what the control under the pointer is for. The rows come from
 * {@link #rows()} in one list, so the drawing, the widgets and the hit tests cannot disagree about what
 * the page contains — which is the fault {@code QuestPanelLayout} exists to prevent for the inspector.
 */
public final class QuestSettingsLayout {

    /** The preview column's width. Wide enough for a 96-pixel node and its name. */
    public static final int PREVIEW_WIDTH = 176;

    /** Between the preview column and the controls. */
    public static final int COLUMN_GAP = 8;

    /** A section's name. */
    public static final int HEADING_HEIGHT = 16;

    /** One control row. */
    public static final int ROW_HEIGHT = 20;

    /** One swatch: the shape, and its name under it. */
    public static final int SWATCH_HEIGHT = 46;

    /** Swatches across, at the normal card width. */
    public static final int SWATCH_COLUMNS = 4;

    /** The room a control's own widgets take at the right of its row. */
    public static final int STRIP_WIDTH = 132;

    /** How far a strip is inset from its row's right edge. */
    public static final int STRIP_INSET = 2;

    /** The help line under everything. */
    public static final int HELP_HEIGHT = 15;

    /** The line under the preview: what is being shown, and at what scale. */
    public static final int CAPTION_HEIGHT = 12;

    /** A slider's track height, and its knob. */
    public static final int TRACK_HEIGHT = 4;
    public static final int KNOB_WIDTH = 5;
    public static final int KNOB_HEIGHT = 12;

    /** A stepper's arrow box, and the value's room beside it. */
    public static final int ARROW_WIDTH = 16;
    public static final int ARROW_HEIGHT = 14;
    public static final int VALUE_WIDTH = 34;

    /**
     * The room a row's label keeps, whatever its control strip would like.
     *
     * <p>Fifty-six is about nine characters at this UI's font — "Min requi…" — which is the difference
     * between a label and a letter. Before this existed, {@link #strip} took {@link #STRIP_WIDTH} of
     * whatever the row had, so a controls column of 148 pixels left the label <b>16</b> of them: the report
     * was a page of rows whose labels read "t", "A", "R", "H". A control narrower than its strip draws
     * smaller; a label narrower than a word says nothing, and the second is worse.
     */
    public static final int LABEL_MIN = 56;

    /**
     * What a switch's control needs: the toggle itself and the air either side of it.
     *
     * <p>The narrowest strip on the page, and the reason the per-kind widths exist at all: five of the
     * visibility rows and four of the rules rows are switches, and every one of them was paying for a
     * slider's room.
     */
    public static final int SWITCH_STRIP = 22;

    /**
     * What a number's control needs: the value slot with a stepper arrow either side of it.
     *
     * <p>Derived from the three constants the stepper is drawn with rather than chosen, so a wider value box
     * moves this with it — the same rule {@code MIN_CONTROLS_WIDTH} follows below.
     */
    public static final int NUMBER_STRIP = VALUE_WIDTH + ARROW_WIDTH * 2 + STRIP_INSET * 2;

    /**
     * What the controls column needs to be usable: a label's room with a control strip beside it.
     *
     * <p>The width the fields claim before the preview is given anything — see {@link Frame#of}.
     */
    public static final int MIN_CONTROLS_WIDTH = LABEL_MIN + STRIP_WIDTH;

    /**
     * Below this the preview is not a picture of anything, so it is dropped and the fields take the width.
     *
     * <p>The fold this page has instead of a scrollbar: a node drawn four pixels wide says less than the
     * rows that would have to shrink to make room for it.
     */
    public static final int PREVIEW_MIN_WIDTH = 96;

    /** How much padding the node keeps inside the preview pane. */
    public static final int PREVIEW_PAD = 12;

    /**
     * The size range the file format allows. The slider spans it, logarithmically.
     *
     * <p>Delegated rather than restated: {@link QuestLayout} owns the field, so it owns the bounds, and
     * a second pair of literals here is a second thing to keep in step with the codec.
     */
    public static final int MIN_SIZE = QuestLayout.MIN_SIZE;
    public static final int MAX_SIZE = QuestLayout.MAX_SIZE;

    /** The rotation's bounds, in degrees: a full turn is the shape itself, so 360 is written as 0. */
    public static final int MIN_ROTATION = QuestLayout.MIN_ROTATION;
    public static final int MAX_ROTATION = QuestLayout.MAX_ROTATION;

    /** The icon scale's range, re-exported from the geometry that computes the fit. */
    public static final double MIN_ICON_SCALE = QuestShape.MIN_ICON_SCALE;
    public static final double MAX_ICON_SCALE = QuestShape.MAX_ICON_SCALE;

    /** The row that adds every selected canvas node as a prerequisite. */
    public static final String DEPENDENCY_SELECTED = "dep:selected";

    /** The "Add selected" row's two labels: with a count, and without. Keys; see {@code Labels.of}. */
    public static final String ADD_SELECTED = "tenet.dev.quest.add_selected";
    public static final String ADD_SELECTED_COUNT = "tenet.dev.quest.add_selected_count";

    /** The prefix of a prerequisite's own row, matching the inspector's own key scheme. */
    public static final String DEPENDENCY_PREFIX = QuestPanelLayout.DEPENDENCY_PREFIX;

    /**
     * The requirement's values, in the order the picker cycles them.
     *
     * <p>The empty string is the chapter's default, and it is first because it is the state a quest
     * starts in -- the legacy format has the same three-way choice and calls it "Default". A picker rather than
     * a text box, because the four modes are a closed set and a typo in a text box is only refused by
     * the server.
     */
    public static List<String> requirementChoices() {
        List<String> choices = new ArrayList<>();
        choices.add("");
        for (PrerequisiteMode mode : PrerequisiteMode.values()) {
            choices.add(mode.name().toLowerCase(Locale.ROOT));
        }
        return List.copyOf(choices);
    }

    /** The value a step lands on, wrapping at both ends. Empty is the chapter default. */
    public static String cycleRequirement(String current, int step) {
        List<String> choices = requirementChoices();
        int at = choices.indexOf(current == null ? "" : current.toLowerCase(Locale.ROOT));
        if (at < 0) {
            at = 0;
        }
        return choices.get(Math.floorMod(at + step, choices.size()));
    }

    /** A requirement value as a person reads it, with the chapter's default named where it applies. */
    public static String requirementLabel(String value, String chapterDefault) {
        if (value == null || value.isEmpty()) {
            return Labels.of("tenet.dev.quest.chapter_default", chapterDefault.replace('_', ' '));
        }
        return value.replace('_', ' ');
    }

    // ------------------------------------------------------------------
    // The three-state rows: the two reveal flags, and the viewer flag
    // ------------------------------------------------------------------

    /**
     * The rows whose values are three states rather than a closed set of words: the default, on,
     * off. The two reveal flags defer to the chapter; the viewer flag defers to the file's
     * settings — one vocabulary, two fallbacks, which is why the unset <i>label</i> differs while
     * the cycling and the writing stay shared.
     */
    private static final List<String> TRI_STATE_KEYS = List.of("hideUntilDependenciesComplete",
            "hideUntilDependenciesVisible", "disableRecipeMod");

    /**
     * Whether this row's values are the three states of a reveal flag.
     *
     * <p>Asked by the press path as well as the drawing, so one row cannot offer a vocabulary the other
     * does not write -- the two would otherwise be a picker whose third state silently did nothing.
     */
    public static boolean isTriStateKey(String key) {
        return TRI_STATE_KEYS.contains(key);
    }

    /**
     * The three states of a reveal flag: the chapter's default, on, off.
     *
     * <p>Three rather than two, and that is the whole reason these rows exist. A chapter may turn either
     * behaviour on for every quest in it, and one quest has to be able to opt <b>out</b> -- with a switch
     * there is no word in the file for "I meant off rather than unspecified", so a chapter's default
     * would be unopposable.
     */
    public static List<String> triStateChoices() {
        return List.of("", "true", "false");
    }

    /** The state a step lands on, wrapping at both ends. Empty is the chapter's default. */
    public static String cycleTriState(String current, int step) {
        List<String> choices = triStateChoices();
        int at = choices.indexOf(current == null ? "" : current.toLowerCase(Locale.ROOT));
        if (at < 0) {
            at = 0;
        }
        return choices.get(Math.floorMod(at + step, choices.size()));
    }

    /**
     * A state as a person reads it, with the chapter's default named where it applies.
     *
     * <p>The chapter's own value is passed in rather than looked up, because this class holds no cache:
     * "Chapter default (On)" is only useful if it says which way the chapter goes, and that answer lives
     * on the chapter the caller is looking at.
     */
    public static String triStateLabel(String value, boolean chapterDefault) {
        if (value == null || value.isEmpty()) {
            return Labels.of("tenet.dev.quest.chapter_default",
                    Labels.of(chapterDefault ? ToolsLayout.ON : ToolsLayout.OFF));
        }
        return Labels.of("true".equalsIgnoreCase(value) ? ToolsLayout.ON : ToolsLayout.OFF);
    }

    /**
     * A file-defaulted flag as a person reads it: the file's default, on, off.
     *
     * <p>Unlike {@link #triStateLabel}, the unset state does not name which way the file goes: the
     * file's settings are not synced to this client, so the page cannot know. "File default" is
     * the honest label — the author reads the file for the value — and the picker still offers
     * all three states, so one quest can opt out of a file that hides its quests by default.
     */
    public static String fileDefaultLabel(String value) {
        if (value == null || value.isEmpty()) {
            return Labels.of("tenet.dev.quest.file_default");
        }
        return Labels.of("true".equalsIgnoreCase(value) ? ToolsLayout.ON : ToolsLayout.OFF);
    }

    /**
     * The value a chosen state is written as: absent for the chapter's default, else a real boolean.
     *
     * <p>A {@code JsonPrimitive} rather than a Boolean, because that is what the op carries -- and the
     * distinction is load-bearing rather than cosmetic: writing the string {@code "true"} would be a
     * file the codec refuses, and writing {@code false} for the unset state would pin the chapter's
     * default the moment somebody looked at the quest.
     */
    public static com.google.gson.JsonElement triStateValue(String value) {
        return value == null || value.isEmpty() ? null
                : new com.google.gson.JsonPrimitive(Boolean.parseBoolean(value));
    }

    /**
     * The auto-claim modes this row cycles, the unset state first.
     *
     * <p>The unset state and the file's own {@code "default"} mean the same thing — defer to the
     * chapter — so only one of them is offered: two choices that do the same thing is a picker with a
     * trap in it. The modes themselves are the reward-level vocabulary, so an author meets one set of
     * words wherever auto-claim is configured.
     */
    public static final List<String> AUTO_CLAIM_CHOICES =
            List.of("", "disabled", "enabled", "no_toast", "invisible");

    /** The value a step lands on, wrapping. Empty means "the chapter decides". */
    public static String cycleAutoClaim(String current, int step) {
        int at = AUTO_CLAIM_CHOICES.indexOf(current == null ? "" : current.toLowerCase(Locale.ROOT));
        if (at < 0) {
            at = 0;
        }
        return AUTO_CLAIM_CHOICES.get(Math.floorMod(at + step, AUTO_CLAIM_CHOICES.size()));
    }

    /** An auto-claim value as a person reads it; the unset state names what it defers to. */
    public static String autoClaimLabel(String value) {
        return value == null || value.isEmpty() ? "Chapter default" : value.replace('_', ' ');
    }

    private QuestSettingsLayout() {
    }

    /**
     * One row of the page.
     *
     * <p>{@code kind} is what the row <i>is</i>, not what it looks like: the drawing switches on it, the
     * screen builds a widget for the ones that need one, and the hit test asks it whether a press is the
     * row's business. {@code label} is the words beside the control.
     */
    public record Row(String key, Kind kind, String label, int controlWidth) {

    /**
     * A row whose control takes the width its kind is drawn from.
     *
     * <p>The three-argument form, kept because it is what every row is built with and because it is the
     * honest default: with nothing measured, a control is exactly as wide as its own kind needs. The screen
     * replaces the rows with measured ones ({@link #controlWidths}) once it has a language to measure in;
     * a dump or a still never does, and gets this.
     */
    public Row(String key, Kind kind, String label) {
        this(key, kind, label, stripWidth(kind));
    }

        public enum Kind {
            /** A section's name; nothing to press. */
            HEADING,
            /** One value of a closed set, cycled by its arrows. See {@link #requirementChoices}. */
            CHOICE,
            /** One prerequisite: its title, and a control in the strip that removes it. */
            DEPENDENCY,
            /** A row that is itself a button. */
            ACTION,
            /** The swatch grid: one block of rows, not a control. */
            SHAPE_GRID,
            /** A track with a knob, plus steppers for the exact value. */
            SLIDER,
            /** A scrubbable number: dragged or typed at the row, not stepped by arrows. */
            NUMBER,
            /** A two-state control. */
            SWITCH,
            /** A text field. */
            FIELD,
            /** A read-only value. */
            VALUE,
            /** The icon, and a button that opens the item picker. */
            ICON
        }
    }

    /**
     * The page's rows, in order.
     *
     * <p>One list, read by the layout, the drawing and the press. The order is the order of use: the
     * shape first because it is what an author opens this page for, then the size and the icon, then
     * placement, then the rules, then the identity extras that are almost never touched.
     */
    public static List<Row> rows() {
        return rows(new JsonObject());
    }

    /**
     * The page's rows for one quest, in order.
     *
     * <p>One list, read by the layout, the drawing and the press. The order is the order of use: the
     * shape first because it is what an author opens this page for, then the size and the icon, then
     * placement, then the dependencies and rules, then the identity extras that are almost never
     * touched.
     *
     * <p>Takes the quest because the dependency section has <b>one row per prerequisite</b>, which a
     * fixed list cannot say. The screen rebuilds on every replica arrival, so a prerequisite added by
     * any of the three paths appears in the list as soon as the server has it.
     */
    public static List<Row> rows(JsonObject quest) {
        return rows(quest, id -> id, 0);
    }

    /**
     * The page's rows for one quest, with the dependency list named and the selection counted.
     *
     * @param titles  what a prerequisite is called on screen -- the client cache's title, or the id when
     *     the cache has never heard of it. A function rather than a map because "unknown id" is a real
     *     answer that has to be produced for every miss, and a map that is missing a key produces null.
     * @param selected how many canvas nodes the "Add selected" row would add, for its label. Zero draws
     *     the row dimmed; the press is refused either way, so the label and the behaviour agree.
     */
    public static List<Row> rows(JsonObject quest, Function<String, String> titles, int selected) {
        if (quest == null) {
            // No replica yet: the fixed rows still lay out, and the dependency list is empty rather
            // than a crash. The page is redrawn when the replica arrives.
            quest = new JsonObject();
        }
        List<Row> rows = new ArrayList<>();
        rows.add(new Row("h:shape", Row.Kind.HEADING, "tenet.dev.quest.shape"));
        rows.add(new Row("shape", Row.Kind.SHAPE_GRID, "tenet.dev.quest.shape"));
        rows.add(new Row("rotation", Row.Kind.SLIDER, "tenet.dev.quest.rotation"));
        rows.add(new Row("h:size", Row.Kind.HEADING, "tenet.dev.quest.size_and_icon"));
        rows.add(new Row("size", Row.Kind.SLIDER, "tenet.dev.quest.size"));
        rows.add(new Row("iconScale", Row.Kind.SLIDER, "tenet.dev.quest.icon_scale"));
        rows.add(new Row("icon", Row.Kind.ICON, "tenet.dev.quest.icon"));
        rows.add(switchRow("showTitle", "tenet.dev.quest.show_title"));
        // How wide this quest's card wants to be, beside the size it is drawn at: a converted pack sets
        // it on hundreds of quests, so it sits with the basic placement rather than behind Advanced.
        rows.add(new Row("minWidth", Row.Kind.NUMBER, "tenet.dev.quest.min_width"));
        rows.add(new Row("h:placement", Row.Kind.HEADING, "tenet.dev.quest.placement"));
        // X and Y are axis letters, not prose: every language names a coordinate the same way, and the
        // sweep's boundary is the same one that leaves ids and enum values alone.
        rows.add(new Row("x", Row.Kind.NUMBER, "X"));
        rows.add(new Row("y", Row.Kind.NUMBER, "Y"));
        rows.add(new Row("h:dependencies", Row.Kind.HEADING, "tenet.dev.quest.dependencies"));
        // One row per prerequisite, then the three ways to add one. The list is the point of the
        // section: an author reads what a quest needs before changing what it needs.
        for (String dependency : QuestPanelLayout.strings(quest, "dependsOn")) {
            // The key keeps the id -- that is what the press needs -- and the label is the title,
            // because an author recognises "Smelt Iron" and not "smelt_iron".
            rows.add(new Row(DEPENDENCY_PREFIX + dependency, Row.Kind.DEPENDENCY,
                    titles.apply(dependency)));
        }
        rows.add(new Row(QuestPanelLayout.DEPENDENCY_ADD, Row.Kind.FIELD, "tenet.dev.quest.add_by_id"));
        rows.add(new Row(QuestPanelLayout.DEPENDENCY_PICK, Row.Kind.ACTION,
                "tenet.dev.quest.pick_on_canvas"));
        rows.add(new Row(DEPENDENCY_SELECTED, Row.Kind.ACTION,
                // Resolved here rather than at the draw site, because the count is this method's
                // argument and nowhere else's -- the label is what the row carries, as it always was.
                // Both branches resolve: a label that were a key in one state and a sentence in the
                // other is the kind of asymmetry that makes the next reader check which one they hold.
                selected > 0 ? Labels.of(ADD_SELECTED_COUNT, selected) : Labels.of(ADD_SELECTED)));
        // The rule the list is judged by, then the two counts that qualify it. the legacy three
        // controls, in its order: the requirement, the minimum, and the cap on dependents.
        rows.add(new Row("prerequisiteMode", Row.Kind.CHOICE, "tenet.dev.quest.requirement"));
        rows.add(new Row("minRequired", Row.Kind.NUMBER, "tenet.dev.quest.min_required"));
        rows.add(new Row("maxCompletableDependents", Row.Kind.NUMBER, "tenet.dev.quest.max_dependents"));
        rows.add(new Row("exclusiveGroup", Row.Kind.FIELD, "tenet.dev.quest.exclusive_group"));
        // Beside the other dependency rules rather than behind Advanced: this is the side quest, and
        // a converted pack sets it hundreds of times. Burying the most-used migration flag would be
        // the editor hiding exactly what an author is looking for.
        rows.add(switchRow("optional", "tenet.dev.quest.optional"));
        rows.add(switchRow("flexibleProgress", "tenet.dev.quest.flexible_progress"));
        // **The three sections Normal mode puts away, each gated where it is written.** One `if` around a
        // block rather than a key per row, because a section is what is being hidden -- and because the
        // block is the only place that knows where the section ends. See `Advanced` for the cut and for why
        // a filter over heading keys could not do this (a section can hold headings of its own).
        if (Advanced.on()) {
            rows.add(new Row("h:visibility", Row.Kind.HEADING, "tenet.dev.quest.visibility"));
            rows.add(switchRow("invisible", "tenet.dev.quest.invisible"));
            rows.add(new Row("invisibleUntilTasks", Row.Kind.NUMBER, "tenet.dev.quest.visible_after_tasks"));
            // Pickers rather than switches, because each is three states: the chapter's default, on, off. A
            // chapter can turn either behaviour on for all of its quests, and a single quest -- the hub, the
            // one that shows the road ahead -- has to be able to opt back out. See `triStateChoices`.
            rows.add(new Row("hideUntilDependenciesComplete", Row.Kind.CHOICE,
                    "tenet.dev.quest.hide_until_deps_done"));
            rows.add(new Row("hideUntilDependenciesVisible", Row.Kind.CHOICE,
                    "tenet.dev.quest.hide_until_deps_shown"));
            rows.add(switchRow("hideDependencyLines", "tenet.dev.quest.hide_dependency_lines"));
            // The outgoing half, beside the incoming one: a quest that fans out to twenty dependants
            // draws twenty lines across the chapter, and the author may want the quest without them.
            rows.add(switchRow("hideDependentLines", "tenet.dev.quest.hide_dependent_lines"));
            rows.add(switchRow("hideTextUntilComplete", "tenet.dev.quest.hide_text_until_done"));
            rows.add(switchRow("hideDetailsUntilStartable", "tenet.dev.quest.hide_details_until_startable"));
            // Whether this quest's completion is announced, beside the other visibility answers: either
            // this or a silent auto-claim mode quiets it, and the two are one author's answer from
            // opposite ends.
            rows.add(switchRow("disableToast", "tenet.dev.quest.disable_toast"));
            // Whether this quest's rewards survive a held payout, beside the announcement answer: the
            // quest's flag covers every reward on it, a reward's own flag covers just itself.
            rows.add(switchRow("ignoreRewardBlocking", "tenet.dev.quest.ignore_reward_blocking"));
            // Whether recipe viewers list this quest, beside the other answers about who sees what:
            // a picker rather than a switch for the same reason as the reveal flags above — absent
            // defers to the file's default, and one quest has to be able to opt back out of a file
            // that hides its quests. See `fileDefaultLabel` for why the unset state names no value.
            rows.add(new Row("disableRecipeMod", Row.Kind.CHOICE, "tenet.dev.quest.disable_recipe_mod"));
            // Whether this quest wears its own lock mark: the quest's half of the file's
            // `showLockIcons`, beside the other answers about who sees what. A switch rather
            // than a picker — absent defers to the file, and either silence wins, so there
            // is no third state.
            rows.add(switchRow("hideLockIcon", "tenet.dev.quest.hide_lock_icon"));
            rows.add(new Row("h:rules", Row.Kind.HEADING, "tenet.dev.quest.rules"));
            rows.add(switchRow("repeatable", "tenet.dev.quest.repeatable"));
            rows.add(new Row("repeatCooldownTicks", Row.Kind.NUMBER, "tenet.dev.quest.repeat_cooldown"));
            rows.add(switchRow("sequentialTasks", "tenet.dev.quest.sequential_tasks"));
            // The quest rung of the auto-claim ladder, as a closed set the picker cycles: the unset state
            // means "the chapter decides", which is why it is first and labelled with what it defers to.
            rows.add(new Row("autoClaim", Row.Kind.CHOICE, "tenet.dev.quest.auto_claim"));
            rows.add(new Row("h:identity", Row.Kind.HEADING, "tenet.dev.quest.identity_extras"));
            rows.add(new Row("id", Row.Kind.VALUE, "tenet.dev.quest.id"));
            rows.add(new Row("aliases", Row.Kind.FIELD, "tenet.dev.quest.aliases"));
            // Words this quest answers to in lookups by tag, comma-separated like the aliases
            // above: `#tag` resolves to the first object carrying it. Beside the aliases because
            // it is the same shape: one field for a list of words.
            rows.add(new Row("tags", Row.Kind.FIELD, "tenet.dev.quest.tags"));
            // The guide book page this quest belongs to. A reference the quest card shows;
            // Tenet has no guide integration, so this is text and nothing more.
            rows.add(new Row("guidePage", Row.Kind.FIELD, "tenet.dev.quest.guide_page"));
        }
        // And the two rows hidden inside sections that stay: the rotation and the icon's scale, which
        // refine a shape and an icon that are basic, and the four rules that qualify the dependency list,
        // which is itself the point of its section. A plain filter, because a marked row needs no heading
        // logic -- see `Advanced.settings`.
        return Advanced.settings(rows);
    }

    /**
     * A switch row. The state is <b>not</b> part of the label: the row is built once and the tree can
     * change under it -- a press writes a draft the server has not answered -- so the panel composes the
     * state from the live flag when it draws ({@code QuestSettingsPanel.drawSwitch}). A label that
     * cached the state would be a switch whose word and knob disagree, which is the fault this row's
     * own test exists for.
     */
    private static Row switchRow(String key, String label) {
        return new Row(key, Row.Kind.SWITCH, label);
    }

    /** The swatch grid's block height: as many rows of swatches as the shapes need. */
    public static int gridHeight() {
        int count = QuestShape.values().length;
        int lines = (count + SWATCH_COLUMNS - 1) / SWATCH_COLUMNS;
        return lines * SWATCH_HEIGHT;
    }

    /**
     * The page's rectangles, from the card's body.
     *
     * <p>The preview is a fixed column on the left; the controls take the rest and scroll; the help line
     * is pinned along the bottom, because a line that scrolls away is a line that is not there when the
     * pointer is over the control it describes. Every rectangle is clamped so a card at its minimum size
     * produces empty rectangles rather than negative ones — the same rule the panels follow, and for the
     * same reason: a negative width draws backwards.
     */
    public record Frame(BookGeometry.Rect preview, BookGeometry.Rect caption,
                        BookGeometry.Rect controls, BookGeometry.Rect help) {

        public static Frame of(BookGeometry.Rect body) {
            int helpHeight = Math.min(HELP_HEIGHT, Math.max(0, body.height()));
            BookGeometry.Rect help = BookGeometry.Rect.at(body.x(), body.bottom() - helpHeight,
                    Math.max(0, body.width()), helpHeight);

            int tall = Math.max(0, body.height() - helpHeight - 2);

            // **The fields win the width, and the preview folds away rather than squeezing them.**
            //
            // The two columns used to split the body in half, which at a docked column's width left the
            // controls about 148 pixels and the label column 16 of them. So the controls are given what they
            // need first, the preview takes what is left, and below `PREVIEW_MIN_WIDTH` the preview is
            // dropped altogether — a four-pixel node is not a picture, and the fields then take the whole
            // width. That is the fold, and it is why this is a rule rather than a second number.
            int room = Math.max(0, body.width() - COLUMN_GAP);
            int controlsWidth = Math.min(room, Math.max(MIN_CONTROLS_WIDTH, room - PREVIEW_WIDTH));
            int previewWidth = room - controlsWidth;
            int gap = COLUMN_GAP;
            if (previewWidth < PREVIEW_MIN_WIDTH) {
                previewWidth = 0;
                controlsWidth = Math.max(0, body.width());
                // No column, no gap: the fields start at the body's own edge rather than eight pixels in
                // from nothing.
                gap = 0;
            }

            int captionHeight = Math.min(CAPTION_HEIGHT, Math.max(0, tall - 24));
            BookGeometry.Rect caption = BookGeometry.Rect.at(body.x(),
                    body.y() + tall - captionHeight, previewWidth, captionHeight);
            BookGeometry.Rect preview = BookGeometry.Rect.at(body.x(), body.y(), previewWidth,
                    Math.max(0, tall - captionHeight - 2));
            BookGeometry.Rect controls = BookGeometry.Rect.at(body.x() + previewWidth + gap,
                    body.y(), Math.max(0, body.width() - previewWidth - gap), tall);
            return new Frame(preview, caption, controls, help);
        }
    }

    /** The rows laid out into the controls column, so a scrolling view can place and cull them. */
    public static Layout build(List<Row> rows, int width, Measure measure) {
        Stack stack = Stack.stack();
        boolean first = true;
        for (Row row : rows) {
            if (!first) {
                stack.gap(row.kind() == Row.Kind.HEADING ? 8 : 1);
            }
            first = false;
            switch (row.kind()) {
                case HEADING -> stack.row(row.key(), HEADING_HEIGHT);
                case SHAPE_GRID -> stack.row(row.key(), gridHeight());
                // Every row is full width, and the control's strip is taken from its right end by
                // `strip`. Reserving the strip as a row *inset* as well -- which is what the inspector
                // does -- narrows the slot and then takes the strip out of the narrowed slot, so the
                // controls land a whole strip's width short of the column's edge and the label is
                // truncated into the space that was reserved for it. One reserve, not two.
                case VALUE, SLIDER, NUMBER, SWITCH, FIELD, ICON, CHOICE, DEPENDENCY, ACTION ->
                        stack.row(row.key(), ROW_HEIGHT);
            }
        }
        return stack.build(Math.max(0, width), measure);
    }

    /**
     * How wide one row's control is, from the label it must fit beside.
     *
     * <h2>What the measurement adds</h2>
     *
     * <p>{@link #strip}'s answer is the control's <i>least</i>: what it is drawn from. That is the right
     * answer when nothing has been measured, and it is one number short of the right one when something has:
     * a short label — "Size", "X" — leaves room that a slider can be longer in or a field wider for, and a
     * long one should take that room back rather than being truncated while a control sits in space it does
     * not use. So the label is measured and the control takes what is left, within two limits: it never goes
     * below its kind's least, and it only grows where a wider control is a better one ({@link #grows}).
     *
     * <p><b>Where the measurement comes from is the caller's business</b>, and that is deliberate: the screen
     * resolves labels through the game's language and measures them once per rebuild ({@link #controlWidths}),
     * while the still-and-dump tools have no language to resolve in at all — {@code Labels.of} throws headless
     * — so they keep the unmeasured answer. Two answers for two situations rather than one that quietly means
     * "the font, which I could not read".
     *
     * @param row         the row whose label and control are being placed
     * @param columnWidth the whole column the row sits in
     * @param measure     what draws text, for the label's own width
     * @param resolve     the row's label as a sentence: its key is not what is on screen
     */
    public static int controlWidth(Row row, int columnWidth, Measure measure,
            Function<String, String> resolve) {
        int room = Math.max(0, columnWidth);
        int least = Math.min(stripWidth(row.kind()), room);
        int floor = Math.min(LABEL_MIN, room);
        String label = resolve == null ? null : resolve.apply(row.label());
        int wanted = label == null ? 0 : Math.max(0, measure.width(label));

        if (wanted + least <= room) {
            // It fits beside the control's least. The control keeps that much, and takes the rest only where
            // more room is a better control rather than a wider empty box beside a word.
            return grows(row.kind()) ? Math.max(least, room - Math.max(floor, wanted)) : least;
        }
        // It does not fit, so the control gives way — as far as its least, and no further than the label's own
        // floor allows. Past that the label truncates, which is what a narrow column means.
        return Math.min(least, Math.max(0, room - floor));
    }

    /**
     * Every row's control width, measured in one pass.
     *
     * <p>Asked once per rebuild, never on the frame path: the labels are fixed strings and the answers are
     * four hundred small integers, so the cost is paid where the layout is already being built and nowhere
     * else. {@code Measure.cached} is the kit's own facility if a caller ever needs this per frame.
     *
     * @return one width per row key, in the order the rows came in
     */
    public static Map<String, Integer> controlWidths(List<Row> rows, int columnWidth, Measure measure,
            Function<String, String> resolve) {
        Map<String, Integer> widths = new LinkedHashMap<>();
        for (Row row : rows) {
            widths.put(row.key(), controlWidth(row, columnWidth, measure, resolve));
        }
        return Map.copyOf(widths);
    }

    /**
     * Whether a wider control is a better one.
     *
     * <p>A slider is dragged and a field is typed into, so both use whatever room they are given. A switch, a
     * stepper, a choice and a button are drawn at their own size against the right edge: giving them more
     * would be a wider empty rectangle beside the label, which is the space the label wanted. That is a
     * statement about the controls rather than a preference, so it is one function rather than a flag on
     * every row.
     */
    public static boolean grows(Row.Kind kind) {
        return kind == Row.Kind.SLIDER || kind == Row.Kind.FIELD;
    }

    /**
     * Where a row's control goes: in the room at the right of its row, against the column's edge.
     *
     * <p>This is the answer when nothing has been measured — see {@link #controlWidth} for the other one and
     * for why both exist. It is what a still, a dump and any caller with no language to resolve in use.
     */
    public static Slot strip(Row.Kind kind, Slot row) {
        // **The label keeps its room, the control gives way, and each kind of control gives way differently.**
        //
        // Two faults met here. The strip took `STRIP_WIDTH` of whatever the row had, so a narrow column left
        // the label a single glyph; and it took that *same* 132 pixels for every kind of control, so a switch
        // row handed 132 pixels of room to a 22-pixel toggle and charged the label for it. At a docked
        // column's width that left five rows reading "Hide " and nothing else — and a page of rows an author
        // cannot tell apart is worse than a page of narrow ones. A control that does not use the room is room
        // the label should have.
        return strip(stripWidth(kind), row);
    }

    /**
     * Where a row's control goes, at the width that row carries.
     *
     * <p>The row rather than its kind, because a measured width belongs to the row: two switches on the page
     * have labels of different lengths, so one may keep more room than the other. A row built without a
     * measurement carries its kind's own width — see {@link Row}'s three-argument constructor — so this
     * answers exactly what {@link #strip(Row.Kind, Slot)} answers until the screen has measured.
     */
    public static Slot strip(Row row, Slot slot) {
        return strip(row.controlWidth(), slot);
    }

    /** The same, from a width a caller has already measured or chosen. */
    public static Slot strip(int controlWidth, Slot row) {
        int width = Math.min(Math.max(0, controlWidth), Math.max(0, row.width()));
        // The label keeps its floor even here: a control is never given room the label needs, whatever a
        // caller passes, because that would be the fault this pair exists to prevent.
        width = Math.min(width, Math.max(0, row.width() - Math.min(LABEL_MIN, row.width())));
        return new Slot(row.key(), row.right() - width, row.y(), width, row.height());
    }

    /**
     * How much room a row's control needs, by kind.
     *
     * <p>The three numbers are what the controls themselves are drawn from — a toggle's own width, a value
     * with its two arrows, and the room a slider needs to be draggable or a field to be typed into — so this
     * is a reading of {@link QuestSettingsPanel} rather than a preference. A kind that is not listed takes
     * the widest, which is the safe direction: a control given more than it needs is a narrower label, and
     * one given less is a control drawn outside its own rectangle.
     */
    public static int stripWidth(Row.Kind kind) {
        return switch (kind) {
            case SWITCH -> SWITCH_STRIP;
            case NUMBER -> NUMBER_STRIP;
            default -> STRIP_WIDTH;
        };
    }

    // ------------------------------------------------------------------
    // The swatch grid
    // ------------------------------------------------------------------

    /** How many swatches fit across a grid of the given width, at least one. */
    public static int columns(int gridWidth) {
        return Math.max(1, Math.min(SWATCH_COLUMNS, gridWidth / 40));
    }

    /**
     * One swatch's rectangle, in the grid block's own coordinates.
     *
     * <p>Every cell is the same size, so the grid is a division rather than a running x — which is what
     * makes {@link #cellAt} the same arithmetic backwards instead of a second walk.
     */
    public static BookGeometry.Rect cellRect(Slot grid, int index, int count) {
        int columns = columns(grid.width());
        int cellWidth = Math.max(1, grid.width() / columns);
        int row = index / columns;
        int column = index % columns;
        int x = grid.x() + column * cellWidth;
        int y = grid.y() + row * SWATCH_HEIGHT;
        int width = column == columns - 1
                ? Math.max(0, grid.right() - x) : cellWidth;
        int height = Math.min(SWATCH_HEIGHT, Math.max(0, grid.bottom() - y));
        return BookGeometry.Rect.at(x, y, Math.max(0, width), Math.max(0, height));
    }

    /** Which swatch a point is over, or -1. The drawing's own arithmetic, run backwards. */
    public static int cellAt(Slot grid, double px, double py, int count) {
        if (px < grid.x() || px >= grid.right() || py < grid.y() || py >= grid.bottom()) {
            return -1;
        }
        for (int index = 0; index < count; index++) {
            if (cellRect(grid, index, count).contains(px, py)) {
                return index;
            }
        }
        return -1;
    }

    // ------------------------------------------------------------------
    // The sliders
    // ------------------------------------------------------------------

    /**
     * A slider's track: between the down arrow and the value, and clear of both.
     *
     * <p>The track is the slider's own; the arrows either side of it are the exact-value steppers, so a
     * value the track cannot reach — a size of 17 on a logarithmic track — is still one press away.
     */
    public static BookGeometry.Rect track(Slot strip) {
        int left = strip.x() + ARROW_WIDTH + 4;
        int right = strip.right() - ARROW_WIDTH - VALUE_WIDTH - 4;
        int y = strip.y() + (strip.height() - TRACK_HEIGHT) / 2;
        return BookGeometry.Rect.at(left, y, Math.max(0, right - left), TRACK_HEIGHT);
    }

    /** Where a slider's knob sits for a value, as a rectangle centred on it. */
    public static BookGeometry.Rect knob(BookGeometry.Rect track, double value, double min, double max,
                                         boolean logarithmic) {
        int x = knobX(track, value, min, max, logarithmic);
        int y = track.y() + track.height() / 2 - KNOB_HEIGHT / 2;
        return BookGeometry.Rect.at(x - KNOB_WIDTH / 2, y, KNOB_WIDTH, KNOB_HEIGHT);
    }

    /** The x a value's knob is centred on, in the track's own coordinates. */
    public static int knobX(BookGeometry.Rect track, double value, double min, double max,
                            boolean logarithmic) {
        double fraction = fractionOf(value, min, max, logarithmic);
        return track.x() + (int) Math.round(fraction * Math.max(0, track.width() - 1));
    }

    /**
     * The value a point on a track names.
     *
     * <p>Logarithmic when asked, and that is the right shape for a size: a node goes from 16 to 512, and
     * on a linear track the useful 16..64 would be a fifth of the travel while a doubling of the size
     * would be a different distance at every scale. On a logarithmic track a doubling is the same
     * distance everywhere, which is what the eye expects of a size.
     */
    public static int valueAt(BookGeometry.Rect track, double px, int min, int max, boolean logarithmic) {
        int span = Math.max(1, track.width() - 1);
        double fraction = Math.max(0, Math.min(1, (px - track.x()) / (double) span));
        double value = logarithmic
                ? min * Math.pow(max / (double) min, fraction)
                : min + fraction * (max - min);
        return Math.max(min, Math.min(max, (int) Math.round(value)));
    }

    /**
     * The value a <b>screen</b> point names on a row's slider.
     *
     * <h2>Why this is a method here and not three lines in the screen</h2>
     *
     * <p>Because the screen got it wrong in the one way that is invisible in the drawing and obvious in
     * the hand: it derived the track from the <b>layout's</b> slot — whose x is a distance across the
     * column, counted from zero — and compared that against a <b>screen</b> pointer. Every press in the
     * column was therefore far to the right of the track it thought it was on, so the value clamped to
     * the maximum on the first pixel of a drag and stayed there. The drawing had been mapped through the
     * viewport; the input had not.
     *
     * <p>So the mapping is a function of (the row's layout slot, the viewport the page is drawn through,
     * the pointer) and nothing else, which makes it something a test can hold: a press at the track's
     * left end names the minimum and one at its right end the maximum, with the track computed in the
     * same space the pointer is in.
     */
    public static int valueAtScreen(Slot layoutSlot, Viewport column, double mouseX, int min, int max,
                                    boolean logarithmic) {
        // A slider's own kind, because that is the only control this can be asked about: the track and the
        // knob are the slider's, and naming the kind here keeps the one strip width rule in one place.
        Slot onScreen = InspectLayout.onScreen(column, strip(Row.Kind.SLIDER, layoutSlot));
        return valueAt(track(onScreen), mouseX, min, max, logarithmic);
    }

    /** Where a value sits on its track, 0 at the left and 1 at the right. */
    public static double fractionOf(double value, double min, double max, boolean logarithmic) {
        double clamped = Math.max(min, Math.min(max, value));
        if (logarithmic) {
            return Math.log(clamped / min) / Math.log(max / min);
        }
        return (clamped - min) / (max - min);
    }

    // ------------------------------------------------------------------
    // The arrows
    // ------------------------------------------------------------------

    /** One of a row's two arrows, in the strip's own coordinates: what is drawn and hit alike. */
    public static BookGeometry.Rect arrowBox(Slot strip, String way) {
        int x = way.equals("down") ? strip.x() : strip.right() - ARROW_WIDTH;
        int y = strip.y() + (strip.height() - ARROW_HEIGHT) / 2;
        return BookGeometry.Rect.at(x, y, ARROW_WIDTH, ARROW_HEIGHT);
    }

    /**
     * Which way a press at a point steps a row: -1, +1, or null. The drawing's own boxes.
     *
     * <p>The rows that still have arrows are the slider's (a track with exact steps beside it) and a
     * choice's (a cycle through a closed set); a numeric row is a {@code ScrubField} now and takes no
     * part in this.
     */
    public static Integer arrowStepAt(Slot strip, double px, double py) {
        if (arrowBox(strip, "down").contains(px, py)) {
            return -1;
        }
        if (arrowBox(strip, "up").contains(px, py)) {
            return 1;
        }
        return null;
    }

    /**
     * Where a prerequisite's remove control sits: the right of the strip, square.
     *
     * <p>Square and small, and at the far right of every row so the column of x's lines up -- a control
     * that moves with the length of the title beside it is a control an author has to hunt for.
     */
    public static BookGeometry.Rect removeBox(Slot strip) {
        int side = Math.min(12, strip.height());
        return BookGeometry.Rect.at(strip.right() - side, strip.y() + (strip.height() - side) / 2,
                side, side);
    }

    /** Whether a press removes the prerequisite on this row. The box the drawing uses. */
    public static boolean removeAt(Slot strip, double px, double py) {
        return removeBox(strip).contains(px, py);
    }

    /** Where an action row's button sits: the whole strip, less a pixel at each end. */
    public static BookGeometry.Rect actionBox(Slot strip) {
        return BookGeometry.Rect.at(strip.x(), strip.y() + 1, strip.width(),
                Math.max(0, strip.height() - 2));
    }

    /** Whether a press lands on an action row's button. */
    public static boolean actionAt(Slot strip, double px, double py) {
        return actionBox(strip).contains(px, py);
    }

    /** Where a switch's track sits: at the right of the strip, with room for its label to the left. */
    public static BookGeometry.Rect switchTrack(Slot strip) {
        int width = 26;
        int height = 12;
        return BookGeometry.Rect.at(strip.right() - width, strip.y() + (strip.height() - height) / 2,
                width, height);
    }
}
