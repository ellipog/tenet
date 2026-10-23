package dev.ellipog.tenet.client.dev;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;

import dev.ellipog.tenet.quest.DependencyStyle;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * The Chapter tab's rows, from the chapter's own file.
 *
 * <p>The chapter is a file like a quest is, and this is the same inspector over it: identity, rules, and
 * the quest list in its authored order. Row keys are the dotted paths the commits go to, exactly as the
 * quest panel's are -- {@code EditorOp.SetChapter} is the op on the other end.
 *
 * <p>The quest list is shown in order, and it reorders: the rows drag, on the same gesture the card's
 * tasks and rewards use -- one drag for every list, as the note here said it would be. For a LINEAR
 * chapter the order <i>is</i> the progression, so the list is the thing being edited and not a summary
 * of it.
 *
 * <h2>Labels are short on purpose, and the rows stack</h2>
 *
 * <p>The dock is a narrow column, and this tab draws its labelled rows {@link
 * dev.ellipog.armature.client.ui.inspect.InspectLayout.Mode#STACKED}: the label on its own line, the
 * control across the whole width beneath it. That is what lets "Default Prerequisite Mode" fit at all --
 * as a side-by-side row in a 180-pixel panel it truncated to "Default Prereq...", which is the report
 * this shape answers. The names stay the file's own ({@code defaultPrerequisiteMode} is still the key);
 * only the words on screen are shortened to what the column can hold.
 */
public final class ChapterPanelLayout {

    /** The foldable sections, by their heading key. */
    public static final String IDENTITY = "h:identity";
    public static final String RULES = "h:rules";
    public static final String QUESTS = "h:quests";
    public static final String GROUP = "h:group";

    /**
     * The chapter's canvas elements: the list, then the selected one's own fields.
     *
     * <p>Its own section rather than rows inside another, because an element is a thing with a name, a kind
     * and a dozen fields — not a property of the chapter. And its own <b>vocabulary</b> in {@code Advanced},
     * because a name here means a different thing from the same name in the settings page: {@code rotation}
     * is both a quest node's angle and a picture's.
     */
    public static final String ELEMENTS = "h:elements";

    /**
     * What a canvas element's row key starts with.
     *
     * <p>An element is in the chapter's own file but is not the chapter: its fields are its own, and a row key
     * is both the identity in the scroll view and the path a commit goes to. So its paths carry a prefix —
     * the arrangement {@link #GROUP_PREFIX} already has for the group's file — and the commit strips it and
     * sends {@code EditorOp.SetElement}.
     *
     * <p>What follows is {@code <id>.<field>}, and the field may itself be a path: {@code element.box.width}
     * is one member, and {@code element.logo.click.type} is a member of a member. The commit tells them apart
     * by the dot, which is why an element's id may not contain one — the validator's own rule.
     */
    public static final String ELEMENT_PREFIX = "element.";

    /**
     * The chapter's quest links: the list, then the selected one's own fields.
     *
     * <p>Its own section rather than rows inside another, because a link is a thing with a target, a
     * position and a shape — not a property of the chapter. And always present, unlike the elements
     * section: decorations are made on the canvas, but a link is made here, so a heading over an
     * empty list is the way in rather than a heading an author learns to ignore.
     */
    public static final String LINKS = "h:links";

    /**
     * What a quest link's row key starts with.
     *
     * <p>A link is in the chapter's own file but is not the chapter: its fields are its own, and a row key
     * is both the identity in the scroll view and the path a commit goes to. So its paths carry a prefix —
     * the arrangement {@link #ELEMENT_PREFIX} already has for elements — and the commit strips it and
     * sends {@code EditorOp.SetLink}.
     *
     * <p>What follows is {@code <id>.<field>}, and the commit tells them apart by the dot, which is why
     * a link's id may not contain one — the validator's own rule.
     */
    public static final String LINK_PREFIX = "link.";

    /**
     * How many links one section lists before it stops.
     *
     * <p>Like the elements' own limit and for the same reason: the list is a way <i>in</i> rather than a
     * report, and past a couple of dozen the thing an author wants is on the canvas and not in a scroll
     * they have to travel. The count of what was left out is shown, so the truncation is visible rather
     * than silent.
     */
    private static final int LINK_LIST_LIMIT = 24;

    /**
     * How many elements one section lists before it stops.
     *
     * <p>A chapter can hold as many decorations as its author wants, and the list is a way <i>in</i> rather
     * than a report: past a couple of dozen, the thing an author wants is on the canvas and not in a scroll
     * they have to travel. The count of what was left out is shown, so the truncation is visible rather than
     * silent — the same rule the problems block follows.
     */
    private static final int ELEMENT_LIST_LIMIT = 24;

    /**
     * The pack's own faults, above everything else and <b>not foldable</b>.
     *
     * <h2>Why this heading has no fold when every other one does</h2>
     *
     * <p>Because a section is a category a reader may put away and this is a fault. A folded fault is a
     * hidden one, and hiding it is the whole of what it exists to stop: the report used to be a toast that
     * scrolled away, so an author who missed it went on working on a pack whose quests were quietly absent
     * from the tree. So it carries no marker, {@code ToolsLayout.folds} does not list it, and neither the
     * panel nor the screen gives it a press — the arrangement {@code SHAPE_SECTION} already has, and for
     * the same reason. It is also why the key is declared here and not mirrored in {@code ToolsLayout}: that
     * file's copies exist to be named by {@code folds}, and this one must never be.
     *
     * <p>Above Identity, because it is about the pack rather than about this chapter, and the reader who
     * opens this tab is the one who can act on it.
     */
    public static final String PROBLEMS = "h:problems";

    /** The read-only values' prefix. */
    public static final String VALUE_PREFIX = "v:";

    /**
     * What a group row's key starts with.
     *
     * <p>The group is a different file from the chapter, so its paths carry a prefix: a row key is both
     * the identity in the scroll view and the field path a commit goes to, and {@code "title"} alone
     * would be the chapter's. The commit strips it and sends {@code EditorOp.SetGroup}.
     */
    public static final String GROUP_PREFIX = "group.";

    /**
     * The chapter's group, as the panel needs it: what the sidebar row above this chapter carries.
     *
     * <p>Read from the client's tree rather than from a file, because that is where a group exists on
     * this side -- the title, the icon's id, and whether its chapters start collapsed. {@code iconId} is
     * the <b>authored</b> icon alone: empty means "no icon of its own", which is a different fact from
     * the fallback the sidebar draws, and the row has to be able to say which one it is looking at.
     */
    public record GroupInfo(String id, String title, String iconId, boolean collapsedByDefault) {

        public GroupInfo {
            id = id == null ? "" : id;
            title = title == null ? "" : title;
            iconId = iconId == null ? "" : iconId;
        }
    }

    /**
     * The icon row's key, which is also the dotted path a commit goes to.
     *
     * <p>Named here rather than written at the two call sites that must agree about it -- the row's
     * builder and the screen's picker button -- because a key typed twice is a row that commits to the
     * wrong path on the day one of them is edited.
     */
    public static final String ICON = "icon.item";

    private ChapterPanelLayout() {
    }

    /** The chapter's own name, for the tab's header band. */
    public record Header(String title, String subtitle) {

        public Header {
            title = title == null ? "" : title;
            subtitle = subtitle == null ? "" : subtitle;
        }
    }

    /** The header, from the chapter's tree. Empty strings for a chapter whose copy has not arrived. */
    public static Header header(JsonObject chapter) {
        if (chapter == null || chapter.isEmpty()) {
            return new Header("", "");
        }
        return new Header(text(chapter, "title", ""), text(chapter, "subtitle", ""));
    }

    /** The chapter's icon object as it stands in the file, or an empty object when it declares none. */
    public static JsonObject icon(JsonObject chapter) {
        JsonElement found = chapter == null ? null : chapter.get("icon");
        return found != null && found.isJsonObject() ? found.getAsJsonObject() : new JsonObject();
    }

    /** The whole panel for one chapter's tree. */
    public static List<ToolsLayout.Action> rows(JsonObject chapter, Set<String> folded) {
        return rows(chapter, null, folded);
    }

    /**
     * The same, with the chapter's group when it has one.
     *
     * <p>The Group section appears only when there is a group to edit, and sits between the rules and the
     * quest list -- ahead of the list because a chapter with twenty quests would otherwise push the
     * group's fields below a scroll nobody makes. Its rows carry {@link #GROUP_PREFIX} in their keys,
     * because they commit to a different file -- the title and icon the sidebar's heading shows, and the
     * flag that says whether its chapters start closed. The icon's row is a picker button like the
     * chapter's, which is what lets the two be set to different items rather than one inheriting the
     * other.
     */
    public static List<ToolsLayout.Action> rows(JsonObject chapter, GroupInfo group, Set<String> folded) {
        return rows(chapter, group, folded, null);
    }

    /**
     * The same, with the server's own refusal to show when there is no copy.
     *
     * <p>The placeholder used to say "has not arrived yet" whether a copy was in flight or had been
     * <b>refused</b>, which is the difference between waiting and being told no — and a panel that says
     * "still coming" about a chapter the server will never send is a panel nobody can act on. The reason
     * is passed in rather than looked up here so the layout stays a pure function of its inputs.
     *
     * <p>Only reachable in edit mode now: without it the tab takes {@link #notEditing()} instead, so
     * every "has not arrived" here is about a request that really was made.
     *
     * <h2>These are the drawer's rows now, not the inspector's</h2>
     *
     * <p>{@code ToolsLayout.Action}, drawn by {@code ToolsPanel} -- the same record and the same drawer as
     * the Book tab beside it. They were {@code InspectRow}s, drawn by {@code QuestPanel.drawRows}, with the
     * cycling rows' arrows drawn by a third class and hit-tested by the screen: one tab of one panel built
     * three ways, and the two halves of it could not be changed together. The keys and the values are
     * unchanged, which is what kept the commits -- every one of them keyed by string -- untouched.
     */
    public static List<ToolsLayout.Action> rows(JsonObject chapter, GroupInfo group, Set<String> folded,
                                                String missingNote) {
        return rows(chapter, group, folded, missingNote, Problems.NONE);
    }

    /**
     * What the last load found wrong with the pack, as this panel shows it.
     *
     * <p>Declared here rather than passed as {@code ClientEditProblems.Report} so the layout stays a
     * function of its own inputs, the way {@link GroupInfo} and {@link Header} are: this file is asserted
     * without a client, and reaching into the store that holds the news would make the test reach for it
     * too. The screen is where the two shapes meet, which is one line of glue rather than a dependency.
     *
     * @param count how many the server found, which may exceed the lines it could fit
     * @param lines the faults, one per line, as the server rendered them
     */
    public record Problems(int count, List<String> lines) {

        /** Nothing wrong, or nothing heard yet. The two are the same to this panel. */
        public static final Problems NONE = new Problems(0, List.of());
    }

    /**
     * The same, with the pack's own faults to show above everything else.
     *
     * <p>The report is the <b>pack's</b> and not this chapter's, which is why it is a parameter of the
     * panel rather than a field of the tree it draws: a chapter's copy can have arrived perfectly and the
     * pack still be broken. It is passed in rather than read here for the reason {@link Problems} gives.
     */
    public static List<ToolsLayout.Action> rows(JsonObject chapter, GroupInfo group, Set<String> folded,
                                                String missingNote, Problems problems) {
        return rows(chapter, group, folded, missingNote, problems, null);
    }

    /**
     * The same, with the id of the canvas element the author has selected.
     *
     * <p>A plain id rather than an {@code Elements} record of the kind {@link GroupInfo} is, because the
     * elements are <b>in the chapter this method is already handed</b>: they live in {@code chapter.json}, so
     * reading them from a second structure would be two answers to one question. The group needs a record
     * because a group is a different file that the chapter's tree does not carry. What the layout cannot know
     * is which element the author is looking at, and that is the one thing this parameter is.
     */
    public static List<ToolsLayout.Action> rows(JsonObject chapter, GroupInfo group, Set<String> folded,
                                                String missingNote, Problems problems,
                                                String selectedElement) {
        return rows(chapter, group, folded, missingNote, problems, selectedElement, null);
    }

    /**
     * The same, with the id of the quest link the author has selected.
     *
     * <p>A plain id beside the element's, because the links are <b>in the chapter this method is
     * already handed</b> for the same reason the elements are. What the layout cannot know is which
     * link the author is looking at, and that is the one thing this parameter is.
     */
    public static List<ToolsLayout.Action> rows(JsonObject chapter, GroupInfo group, Set<String> folded,
                                                String missingNote, Problems problems,
                                                String selectedElement, String selectedLink) {
        List<ToolsLayout.Action> rows = new ArrayList<>();
        problems(rows, problems);
        if (chapter == null || chapter.isEmpty()) {
            String why = missingNote == null || missingNote.isBlank()
                    ? Labels.of("tenet.dev.chapter.missing_copy")
                    : Labels.of("tenet.dev.chapter.missing_copy_said", missingNote);
            rows.add(ToolsLayout.Action.value(VALUE_PREFIX + "none", "tenet.dev.chapter.chapter", why));
            return List.copyOf(rows);
        }

        rows.add(section(IDENTITY, "tenet.dev.chapter.identity", folded));
        if (!folded.contains(IDENTITY)) {
            rows.add(ToolsLayout.Action.text("title", "tenet.dev.chapter.title",
                    text(chapter, "title", "")));
            rows.add(ToolsLayout.Action.text("subtitle", "tenet.dev.chapter.subtitle",
                    text(chapter, "subtitle", "")));
            rows.add(ToolsLayout.Action.button(ICON, "tenet.dev.chapter.icon", iconId(chapter)));
            rows.add(ToolsLayout.Action.value(VALUE_PREFIX + "description", "tenet.dev.chapter.description",
                    description(chapter)));
            rows.add(ToolsLayout.Action.text("aliases", "tenet.dev.chapter.aliases",
                    String.join(", ", QuestPanelLayout.strings(chapter, "aliases"))));
            // Words this chapter answers to in lookups by tag, comma-separated like the aliases
            // above: `#tag` resolves to the first object carrying it. The validator is what says
            // a tag is misspelled, and empty clears the list.
            rows.add(ToolsLayout.Action.text("tags", "tenet.dev.chapter.tags",
                    String.join(", ", QuestPanelLayout.strings(chapter, "tags"))));
        }

        // **The rules are Advanced, and gated here rather than by their keys.** A chapter's rules are keyed
        // by the field names the *quest's* own rules use -- `minRequired`, `prerequisiteMode`, `autoClaim` --
        // and the same words mean different things in the two files, so a set of keys would hide the wrong
        // control in the other panel. What a chapter is, what it is called and which quests it holds is
        // basic; how its lines are drawn and how its progression is gated is not. See `Advanced`.
        if (Advanced.on()) {
            rows.add(section(RULES, "tenet.dev.chapter.rules", folded));
            if (!folded.contains(RULES)) {
                rows.add(choiceRow(chapter, PROGRESSION));
                rows.add(ToolsLayout.Action.toggle("defaultConsumeItems", "tenet.dev.chapter.consume",
                        flagOn(chapter, "defaultConsumeItems") ? ToolsLayout.ON : ToolsLayout.OFF));
                // Beside the consume default because it is the same shape: one word here spares every
                // quest in the chapter repeating it. Either this or a quest's own flag makes it flexible.
                rows.add(ToolsLayout.Action.toggle("defaultFlexibleProgress",
                        "tenet.dev.chapter.flexible",
                        flagOn(chapter, "defaultFlexibleProgress") ? ToolsLayout.ON : ToolsLayout.OFF));
                // The panel width the chapter's quests want unless one says otherwise: a quest's own
                // minWidth wins over this, and 0 (unset) means the panel kind decides.
                rows.add(ToolsLayout.Action.text("defaultMinWidth", "tenet.dev.chapter.default_min_width",
                        numberText(chapter, "defaultMinWidth")));
                // Whether this chapter's quests require their tasks in order unless one says otherwise.
                // Beside the other quest defaults because it is the same shape: one word here spares
                // every quest in the chapter repeating it. Either this or a quest's own flag makes it
                // sequential.
                rows.add(ToolsLayout.Action.toggle("defaultSequentialTasks",
                        "tenet.dev.chapter.sequential",
                        flagOn(chapter, "defaultSequentialTasks") ? ToolsLayout.ON : ToolsLayout.OFF));
                // The quest this chapter centres on when selected, by id or alias in this chapter.
                // A text row like the gate lists below: the validator is what says a name does not
                // resolve, and empty clears it back to the bounding-box centre.
                rows.add(ToolsLayout.Action.text("autofocus", "tenet.dev.chapter.autofocus",
                        text(chapter, "autofocus", "")));
                rows.add(choiceRow(chapter, PREREQUISITE));
                rows.add(choiceRow(chapter, AUTO_CLAIM));
                // The chapter's own gate, above the line-style rows because it is what the chapter *is* in
                // the progression rather than how its lines are drawn. Both id lists are TEXT rows, the shape
                // the aliases row above already uses: a chapter is picked by name here, and the validator is
                // what says a name does not resolve. A click-to-pick overlay for chapters is the obvious next
                // step and is deliberately not this one -- the picker is shaped around canvas nodes.
                rows.add(choiceRow(chapter, GATE_MODE));
                rows.add(ToolsLayout.Action.text("minRequired", "tenet.dev.chapter.gate_min",
                        numberText(chapter, "minRequired")));
                rows.add(ToolsLayout.Action.text("dependsOn", "tenet.dev.chapter.depends_on",
                        String.join(", ", QuestPanelLayout.strings(chapter, "dependsOn"))));
                rows.add(ToolsLayout.Action.text("completesWhen", "tenet.dev.chapter.completes_when",
                        String.join(", ", QuestPanelLayout.strings(chapter, "completesWhen"))));
                rows.add(ToolsLayout.Action.toggle("hideUntilDependenciesComplete",
                        "tenet.dev.chapter.hide_until_deps",
                        flagOn(chapter, "hideUntilDependenciesComplete") ? ToolsLayout.ON : ToolsLayout.OFF));
                // Whether this chapter is withheld from every reader, whatever its gate says.
                // Beside the row above because they are the pair an author will confuse: that one
                // withholds the row until the gate is met, this one withholds it always. The gate
                // itself is unaffected, and an author still sees the row, or this could not be undone.
                rows.add(ToolsLayout.Action.toggle("alwaysInvisible",
                        "tenet.dev.chapter.always_invisible",
                        flagOn(chapter, "alwaysInvisible") ? ToolsLayout.ON : ToolsLayout.OFF));
                // Whether this chapter's canvas draws without the zoom tiers: icons, rings,
                // titles and badges at every zoom, as legacy packs drew them. Absent defers to
                // the book's own flag, which an explicit `false` here overrides — the toggle
                // writes what it is moved to, so touching it ends the deference either way.
                rows.add(ToolsLayout.Action.toggle("disableCanvasLod",
                        "tenet.dev.chapter.lod_off",
                        flagOn(chapter, "disableCanvasLod") ? ToolsLayout.ON : ToolsLayout.OFF));
                // What this chapter's quests do about their *own* dependencies, unless a quest says
                // otherwise. Beside the row above because they are the pair an author will confuse: that one
                // withholds this chapter's row from a reader, these withhold its quests from everybody.
                rows.add(ToolsLayout.Action.toggle("defaultHideUntilDependenciesComplete",
                        "tenet.dev.chapter.default_hide_deps_complete",
                        flagOn(chapter, "defaultHideUntilDependenciesComplete")
                                ? ToolsLayout.ON : ToolsLayout.OFF));
                rows.add(ToolsLayout.Action.toggle("defaultHideUntilDependenciesVisible",
                        "tenet.dev.chapter.default_hide_deps_visible",
                        flagOn(chapter, "defaultHideUntilDependenciesVisible")
                                ? ToolsLayout.ON : ToolsLayout.OFF));
                rows.add(choiceRow(chapter, LINE_FORM));
                rows.add(choiceRow(chapter, LINE_ARROW_HEAD));
                rows.add(choiceRow(chapter, LINE_ARROW_PLACE));
                rows.add(choiceRow(chapter, LINE_ARROW_DENSITY));
                rows.add(choiceRow(chapter, LINE_DASH));
                rows.add(choiceRow(chapter, LINE_WEIGHT));
            }
        }

        if (group != null) {
            // Before the quest list, not after it: a chapter with twenty quests would push the group's
            // own fields below a scroll nobody makes, and the group is the thing this section exists to
            // make reachable.
            rows.add(section(GROUP, "tenet.dev.chapter.group", folded));
            if (!folded.contains(GROUP)) {
                rows.add(ToolsLayout.Action.text(GROUP_PREFIX + "title", "tenet.dev.chapter.title",
                        group.title()));
                rows.add(ToolsLayout.Action.button(GROUP_PREFIX + ICON, "tenet.dev.chapter.icon",
                        group.iconId()));
                rows.add(ToolsLayout.Action.toggle(GROUP_PREFIX + "collapsedByDefault",
                        "tenet.dev.chapter.collapsed",
                        group.collapsedByDefault() ? ToolsLayout.ON : ToolsLayout.OFF));
            }
        }

        // The chapter's canvas elements, before the quest list and after the group, for the reason the group
        // is where it is: a chapter with twenty quests would push a decoration's fields below a scroll nobody
        // makes, and the section is a way in rather than a report. Absent entirely when the chapter has none,
        // because a heading over an empty list is a heading an author learns to ignore -- and the way to make
        // one is the canvas's own menu, which is where they are looking when they want one.
        List<JsonObject> elements = elementsOf(chapter);
        if (!elements.isEmpty()) {
            rows.add(section(ELEMENTS, "tenet.dev.chapter.elements", folded));
            if (!folded.contains(ELEMENTS)) {
                elementList(rows, elements, selectedElement, folded);
            }
        }

        // The chapter's quest links, after the elements and before the quest list, for the reason both
        // are where they are: a chapter with twenty quests would push a marker's fields below a scroll
        // nobody makes, and the section is a way in rather than a report. Always present, unlike the
        // elements section above it: decorations are made on the canvas, but a link is made here, so a
        // heading over an empty list is the way in rather than a heading an author learns to ignore.
        rows.add(section(LINKS, "tenet.dev.chapter.links", folded));
        if (!folded.contains(LINKS)) {
            linkList(rows, linksOf(chapter), selectedLink, folded);
        }

        rows.add(section(QUESTS, "tenet.dev.chapter.quests", folded));
        if (!folded.contains(QUESTS)) {
            List<String> quests = QuestPanelLayout.strings(chapter, "quests");
            for (int i = 0; i < quests.size(); i++) {
                String name = quests.get(i);
                String id = name.endsWith(".json") ? name.substring(0, name.length() - 5) : name;
                // A read-only row: it holds no widget, and the press it does answer is the *drag* the
                // screen hit-tests from these same rectangles (`chapterQuestRowRects`), which is why it
                // stays a value row rather than becoming one whose widget is the row -- a widget would
                // take the press first and the reorder would have nothing to grab.
                rows.add(ToolsLayout.Action.value(VALUE_PREFIX + "quest:" + id, (i + 1) + ".", id));
            }
        }
        return List.copyOf(rows);
    }

    // ------------------------------------------------------------------
    // The canvas elements
    // ------------------------------------------------------------------

    /**
     * The chapter's own {@code elements} array, as objects.
     *
     * <p>Read here rather than taken as a parameter, for the reason {@link #rows} gives: they are in the file
     * this method already holds. A member that is not an object is skipped rather than refused — a file that
     * malformed is the validator's to report with its line, and a panel that threw on it would take the whole
     * tab down over one bad entry.
     */
    public static List<JsonObject> elementsOf(JsonObject chapter) {
        JsonElement array = chapter == null ? null : chapter.get("elements");
        if (array == null || !array.isJsonArray()) {
            return List.of();
        }
        List<JsonObject> elements = new ArrayList<>();
        for (JsonElement each : array.getAsJsonArray()) {
            if (each != null && each.isJsonObject()) {
                elements.add(each.getAsJsonObject());
            }
        }
        return List.copyOf(elements);
    }

    /**
     * The position of an element by id in a chapter's own tree, or -1.
     *
     * <p>The client's own lookup, and the server has one of its own — {@code QuestEditor.elementIndex} — over
     * the file rather than over the replica. They cannot be one method: the two sides hold different things (a
     * parsed tree here, a writable file there), and the client cannot reach the server's.
     */
    public static int elementIndex(JsonObject chapter, String id) {
        if (id == null) {
            return -1;
        }
        List<JsonObject> elements = elementsOf(chapter);
        for (int i = 0; i < elements.size(); i++) {
            if (id.equals(text(elements.get(i), "id", ""))) {
                return i;
            }
        }
        return -1;
    }

    /** The element with this id in a chapter's own tree, or null. */
    public static JsonObject elementById(JsonObject chapter, String id) {
        return elementById(elementsOf(chapter), id);
    }

    /**
     * The element a field row's key names — {@code {id, field}} — or null when the key is not one.
     *
     * <h2>Why the parsing is here rather than at the screen</h2>
     *
     * <p>Because the spelling of a row key is this class's, and a screen that split the string itself would be
     * a second place the format is known — the fault {@link #GROUP_PREFIX} avoids by keeping its own strip in
     * one caller. A key with no dot after the id is a <b>list</b> row's and not a field's, so it answers null:
     * {@code element.box} is the row an author presses to select the element.
     */
    public static String[] elementFieldOf(String key) {
        if (key == null || !key.startsWith(ELEMENT_PREFIX)) {
            return null;
        }
        String rest = key.substring(ELEMENT_PREFIX.length());
        int cut = rest.indexOf('.');
        return cut <= 0 ? null : new String[] {rest.substring(0, cut), rest.substring(cut + 1)};
    }

    /** The element with this id, or null. */
    private static JsonObject elementById(List<JsonObject> elements, String id) {
        if (id == null) {
            return null;
        }
        for (JsonObject element : elements) {
            if (id.equals(text(element, "id", ""))) {
                return element;
            }
        }
        return null;
    }

    /** The list of elements, then the selected one's own fields. */
    private static void elementList(List<ToolsLayout.Action> rows, List<JsonObject> elements,
                                    String selectedElement, Set<String> folded) {
        int shown = Math.min(elements.size(), ELEMENT_LIST_LIMIT);
        for (int i = 0; i < shown; i++) {
            JsonObject element = elements.get(i);
            String id = text(element, "id", "");
            // A read-only row, like a quest's: it holds no widget, and the press it answers is the
            // *selection*, which the screen reads from the row's own key. The left-hand value is the kind and
            // the right-hand one is what the element says it is, so a list of six decorations is readable.
            rows.add(ToolsLayout.Action.value(VALUE_PREFIX + "element:" + id,
                    typeName(element), elementName(element, id)));
        }
        if (elements.size() > shown) {
            rows.add(ToolsLayout.Action.value(VALUE_PREFIX + "element:more", "\u2026",
                    Labels.of("tenet.dev.chapter.elements_more", elements.size() - shown)));
        }
        JsonObject selected = elementById(elements, selectedElement);
        if (selected != null) {
            elementFields(rows, selected, folded);
        }
    }

    /** What an element calls itself: its words when it has any, and its id when it has none. */
    public static String elementName(JsonObject element, String id) {
        for (String field : List.of("text", "title")) {
            JsonElement words = element.get(field);
            if (words != null && words.isJsonPrimitive() && !words.getAsString().isBlank()) {
                return words.getAsString();
            }
        }
        return id;
    }

    /** The kind an element is, as one short word for the list. */
    private static String typeName(JsonObject element) {
        String type = text(element, "type", "");
        int colon = type.indexOf(':');
        // A namespaced type -- an addon's -- is drawn by its own name rather than as "unknown": the panel has
        // no fields for it, which is a different fact from the file not saying what it is.
        return colon < 0 ? type : type.substring(colon + 1);
    }

    /**
     * The selected element's own fields, by the arm it is.
     *
     * <p>The rows themselves live in {@link ElementPanelLayout}, because the panel that opens when an author
     * clicks an element on the canvas draws the same form -- and a second copy of twenty rows keyed by field
     * name is a second place for a field to be forgotten. This is the Chapter tab's way in: the list, then the
     * chosen element's fields under it.
     */
    private static void elementFields(List<ToolsLayout.Action> rows, JsonObject element,
                                      Set<String> folded) {
        rows.addAll(ElementPanelLayout.rows(element, folded));
    }

    // ------------------------------------------------------------------
    // The quest links
    // ------------------------------------------------------------------

    /**
     * The chapter's own {@code links} array, as objects.
     *
     * <p>Read here rather than taken as a parameter, for the reason {@link #rows} gives for the
     * elements: they are in the file this method already holds. A member that is not an object is
     * skipped rather than refused — a file that fails the codec never reaches this panel, and the
     * validator is what reports it.
     */
    public static List<JsonObject> linksOf(JsonObject chapter) {
        JsonElement array = chapter == null ? null : chapter.get("links");
        if (array == null || !array.isJsonArray()) {
            return List.of();
        }
        List<JsonObject> links = new ArrayList<>();
        for (JsonElement each : array.getAsJsonArray()) {
            if (each.isJsonObject()) {
                links.add(each.getAsJsonObject());
            }
        }
        return List.copyOf(links);
    }

    /**
     * The link a field row's key names — {@code {id, field}} — or null when the key is not one.
     *
     * <p>{@code link.add} is not a link's field: it is the section's own button, and it answers null
     * here so the commit path cannot mistake it for a link called {@code add} with no field.
     */
    public static String[] linkFieldOf(String key) {
        if (key == null || !key.startsWith(LINK_PREFIX)) {
            return null;
        }
        String rest = key.substring(LINK_PREFIX.length());
        int dot = rest.indexOf('.');
        if (dot <= 0 || dot == rest.length() - 1) {
            return null;
        }
        return new String[] {rest.substring(0, dot), rest.substring(dot + 1)};
    }

    /** The link with this id in a chapter's own tree, or null. */
    public static JsonObject linkById(JsonObject chapter, String id) {
        return linkById(linksOf(chapter), id);
    }

    /** The link with this id, or null. */
    private static JsonObject linkById(List<JsonObject> links, String id) {
        for (JsonObject link : links) {
            if (id.equals(text(link, "id", ""))) {
                return link;
            }
        }
        return null;
    }

    /** The list of links, the way in, then the selected one's own fields. */
    private static void linkList(List<ToolsLayout.Action> rows, List<JsonObject> links,
                                 String selectedLink, Set<String> folded) {
        rows.add(ToolsLayout.Action.button(LinkPanelLayout.ADD_LINK, "tenet.dev.link.add", ""));
        int shown = Math.min(links.size(), LINK_LIST_LIMIT);
        for (int i = 0; i < shown; i++) {
            JsonObject link = links.get(i);
            String id = text(link, "id", "");
            // A read-only row, like a quest's and an element's: it holds no widget, and the press it
            // answers is the *selection*, which the screen reads from the row's own key. The left-hand
            // value says what it is and the right-hand one where it points, so a list of six markers
            // is readable.
            rows.add(ToolsLayout.Action.value(VALUE_PREFIX + "link:" + id,
                    "link", linkName(link, id)));
        }
        if (links.size() > shown) {
            rows.add(ToolsLayout.Action.value(VALUE_PREFIX + "link:more", "\u2026",
                    Labels.of("tenet.dev.chapter.links_more", links.size() - shown)));
        }
        JsonObject selected = linkById(links, selectedLink);
        if (selected != null) {
            linkFields(rows, selected, folded);
        }
    }

    /** What a link calls itself: where it points when it points somewhere, and its id otherwise. */
    private static String linkName(JsonObject link, String id) {
        String quest = text(link, "quest", "");
        return quest.isEmpty() ? id : quest;
    }

    /**
     * The selected link's own fields.
     *
     * <p>The rows themselves live in {@link LinkPanelLayout}, beside the element form and for the
     * same reason: a form is a thing with a help table, a value reader and a range table, and the
     * tab is a list of sections. This is the tab's way in: the list, then the chosen link's fields
     * under it.
     */
    private static void linkFields(List<ToolsLayout.Action> rows, JsonObject link,
                                   Set<String> folded) {
        rows.addAll(LinkPanelLayout.rows(link, folded));
    }

    /**
     * One foldable section's heading: the marker, then the key the panel resolves.
     *
     * <p>The marker is part of the drawn string rather than a decoration the panel adds, because the
     * layout is handed the fold state and the marker is part of the label's own width -- the convention
     * {@code Labels.of} documents, and the one the Book tab's headings already follow.
     */
    private static ToolsLayout.Action section(String key, String label, Set<String> folded) {
        return ToolsLayout.Action.heading(key, (folded.contains(key) ? "\u203a " : "\u25bc ") + label);
    }

    /**
     * The pack's faults, above everything and never folded away.
     *
     * <h2>What it draws, and what it deliberately does not</h2>
     *
     * <p>A heading carrying the count, then one line per fault, then a line saying how many were too long to
     * send. The count is in the heading rather than in a row of its own because it is the one thing worth
     * reading at a glance — the badge exists so that a broken pack is visible without opening anything — and
     * the lines are there because the alternative is a number the author has to go to the log to interpret.
     *
     * <p><b>The lines are long and this column is narrow.</b> They are rendered by the server as
     * {@code file:line:column: error: message} and the panel truncates to the band, so a fault may be cut
     * mid-sentence. That is accepted rather than solved: the row is a pointer at a message whose whole text
     * is in the log and in {@code /tenet reload}'s output, and a truncated first clause plus the file name
     * is enough to know which file to open. Worth stating because the truncation is visible and would
     * otherwise read as a rendering fault.
     *
     * <p>A {@code VALUE} row per line, so the screen builds no widget for any of them: a fault is something
     * to read, and a row that looked pressable and did nothing would be the worse of the two.
     */
    private static void problems(List<ToolsLayout.Action> rows, Problems problems) {
        if (problems == null || problems.count() <= 0) {
            return;
        }
        // Resolved here rather than at the draw site: a heading's label goes through `Labels.of` with no
        // values, so a `%s` left in it would be drawn as one. A resolved string is not a key, and the
        // resolver passes it through unchanged -- which is what makes this the one place it can be done.
        rows.add(ToolsLayout.Action.heading(PROBLEMS,
                Labels.of("tenet.dev.chapter.problems", problems.count())));
        for (int i = 0; i < problems.lines().size(); i++) {
            rows.add(ToolsLayout.Action.value(VALUE_PREFIX + "problem:" + i, "", problems.lines().get(i)));
        }
        int hidden = problems.count() - problems.lines().size();
        if (hidden > 0) {
            rows.add(ToolsLayout.Action.value(VALUE_PREFIX + "problems:more", "",
                    Labels.of("tenet.dev.chapter.problems_more", hidden)));
        }
    }

    /** Whether one of the chapter's flags is on, as the file holds it. */
    private static boolean flagOn(JsonObject chapter, String path) {
        JsonElement found = chapter == null ? null : chapter.get(path);
        return found != null && found.isJsonPrimitive() && found.getAsJsonPrimitive().isBoolean()
                && found.getAsBoolean();
    }

    /**
     * The tab for a player who is not in edit mode: one line naming the state, and nothing to press.
     *
     * <h2>Why this is not the "has not arrived" placeholder</h2>
     *
     * <p>The chapter tools are the author's, and edit mode is the switch that says the author is
     * working: without it the client never asks the server for the chapter's copy, and the tab used to
     * report that copy as still coming -- a "wait" about a request that was never made, drawn truncated
     * in the dock's narrow value column. This says the one thing that is true; {@link #EDIT_MODE_HINT},
     * drawn in the band under the tabs, says the one thing to do about it.
     *
     * <p>A {@code VALUE} row deliberately: the screen builds no widget for one, so the not-editing tab
     * cannot show a field, a toggle or an arrow that looks pressable and silently does nothing.
     */
    public static List<ToolsLayout.Action> notEditing() {
        return notEditing(null);
    }

    /**
     * The not-editing line, and the pack's problems under it.
     *
     * <p>The problems are the pack's, not the chapter copy's, so they need no edit mode to be read -- and
     * the reload's toast says "see Chapter tab" whether or not edit mode is on. Before this, an author who
     * followed it out of edit mode found one line and no problems.
     */
    public static List<ToolsLayout.Action> notEditing(Problems problems) {
        List<ToolsLayout.Action> rows = new ArrayList<>();
        rows.add(ToolsLayout.Action.value(VALUE_PREFIX + "notEditing", "tenet.dev.chapter.chapter",
                Labels.of("tenet.dev.chapter.not_editing")));
        problems(rows, problems);
        return List.copyOf(rows);
    }

    // ------------------------------------------------------------------
    // The cycling rows
    // ------------------------------------------------------------------

    /** The field the chapter's line-style default lives in; its axes are one row each. */
    public static final String DEPENDENCY_STYLE = "dependencyStyle";

    /**
     * One cycling row: a closed set stepped by an arrow at each end.
     *
     * <p>This panel's closed sets -- the two progression rules, the consume-items flag (a toggle,
     * because two states are not a list), and the line-style axes -- are cycled rather than typed: the
     * rules were typed by hand once, where a typo is a value only the server refuses, and the picker
     * makes the valid values the only reachable ones and names the state a file that says nothing is in.
     *
     * <p>{@code key} is the row's identity and, for the two rules, the exact path a commit goes to. The
     * line axes are nested inside {@code dependencyStyle}, and a commit writes that whole object — see
     * {@link #choiceEdit} — so their keys are the dotted path read for information rather than the path
     * the op carries.
     *
     * <p>{@code fallback} is what the axis is worth when nothing, anywhere, says otherwise: the
     * manifest's own default for a rule, the built-ins for a line axis. The unset choice is labelled
     * with it, so the row never shows a bare "Default" the reader has to guess about.
     */
    public record Choice(String key, String label, List<String> values, String fallback) {

        public Choice {
            values = List.copyOf(values);
        }

        /** The dependencyStyle axis this row is, or the key itself for a top-level rule. */
        public String axis() {
            return isLineStyle()
                    ? key.substring(DEPENDENCY_STYLE.length() + 1) : key;
        }

        /** Whether this row is one axis of the chapter's line-style default. */
        public boolean isLineStyle() {
            return key.startsWith(DEPENDENCY_STYLE + ".");
        }
    }

    /**
     * The help a chapter row offers on hover, or null for a row whose label says it itself.
     *
     * <h2>Why a table, and why these rows needed one</h2>
     *
     * <p>The same shape {@code ToolsLayout.HELP} uses for the appearance rows, so the two halves of the
     * Chapter tab answer the pointer the same way. The rules are the reason it exists: "Default
     * Prerequisite Mode" is a name rather than an explanation, and the six line-style rows read like
     * JSON keys. The keys are the commit paths, so the table and the rows cannot drift about which key
     * a sentence belongs to.
     */
    private static final Map<String, String> HELP = Map.ofEntries(
            Map.entry("title", "tenet.dev.chapter.help.title"),
            Map.entry("subtitle", "tenet.dev.chapter.help.subtitle"),
            Map.entry(ICON, "tenet.dev.chapter.help.icon"),
            Map.entry("aliases", "tenet.dev.chapter.help.aliases"),
            Map.entry("tags", "tenet.dev.chapter.help.tags"),
            Map.entry("progressionMode", "tenet.dev.chapter.help.progression"),
            Map.entry("defaultConsumeItems", "tenet.dev.chapter.help.consume_items"),
            Map.entry("defaultFlexibleProgress", "tenet.dev.chapter.help.flexible_progress"),
            Map.entry("defaultMinWidth", "tenet.dev.chapter.help.default_min_width"),
            Map.entry("defaultSequentialTasks", "tenet.dev.chapter.help.sequential_tasks"),
            Map.entry("autofocus", "tenet.dev.chapter.help.autofocus"),
            Map.entry("defaultPrerequisiteMode", "tenet.dev.chapter.help.prerequisite"),
            Map.entry("autoClaim", "tenet.dev.chapter.help.auto_claim"),
            Map.entry("prerequisiteMode", "tenet.dev.chapter.help.gate_mode"),
            Map.entry("minRequired", "tenet.dev.chapter.help.gate_min"),
            Map.entry("dependsOn", "tenet.dev.chapter.help.depends_on"),
            Map.entry("completesWhen", "tenet.dev.chapter.help.completes_when"),
            Map.entry("hideUntilDependenciesComplete", "tenet.dev.chapter.help.hide_until_deps"),
            Map.entry("alwaysInvisible", "tenet.dev.chapter.help.always_invisible"),
            Map.entry("disableCanvasLod", "tenet.dev.chapter.help.lod_off"),
            Map.entry("defaultHideUntilDependenciesComplete",
                    "tenet.dev.chapter.help.default_hide_deps_complete"),
            Map.entry("defaultHideUntilDependenciesVisible",
                    "tenet.dev.chapter.help.default_hide_deps_visible"),
            Map.entry(DEPENDENCY_STYLE + ".form", "tenet.dev.chapter.help.line_form"),
            Map.entry(DEPENDENCY_STYLE + ".arrowHead", "tenet.dev.chapter.help.line_head"),
            Map.entry(DEPENDENCY_STYLE + ".arrowPlace", "tenet.dev.chapter.help.line_place"),
            Map.entry(DEPENDENCY_STYLE + ".arrowDensity", "tenet.dev.chapter.help.line_density"),
            Map.entry(DEPENDENCY_STYLE + ".dash", "tenet.dev.chapter.help.line_pattern"),
            Map.entry(DEPENDENCY_STYLE + ".weight", "tenet.dev.chapter.help.line_weight"),
            Map.entry(GROUP_PREFIX + "title", "tenet.dev.chapter.help.group_title"),
            Map.entry(GROUP_PREFIX + ICON, "tenet.dev.chapter.help.group_icon"),
            Map.entry(GROUP_PREFIX + "collapsedByDefault", "tenet.dev.chapter.help.group_collapsed"),
            Map.entry(PROBLEMS, "tenet.dev.chapter.help.problems"));

    /** The help a chapter row key offers, or null. Headings say themselves, except the problems
     * heading, which names its two gestures; quest rows say themselves. */
    public static String help(String key) {
        return key == null ? null : HELP.get(key);
    }

    public static final Choice PROGRESSION =
            new Choice("progressionMode", "tenet.dev.chapter.progression",
                    List.of("flexible", "linear"), "flexible");

    public static final Choice PREREQUISITE = new Choice("defaultPrerequisiteMode",
            "tenet.dev.chapter.prerequisite",
            List.of("all_completed", "one_completed", "all_started", "one_started"), "all_completed");

    /**
     * What this chapter's <b>own</b> dependencies have to reach.
     *
     * <p>The row beside {@link #PREREQUISITE}, and the two are deliberately named apart on screen: this
     * one is about the chapters this chapter waits on, and that one is the mode its quests inherit. On
     * disk they are {@code prerequisiteMode} and {@code defaultPrerequisiteMode} — one letter apart, and
     * the difference is the direction the rule applies in.
     */
    public static final Choice GATE_MODE = new Choice("prerequisiteMode", "tenet.dev.chapter.gate_mode",
            List.of("all_completed", "one_completed", "all_started", "one_started"), "all_completed");

    /**
     * Whether this chapter's quests hand their rewards over on completion.
     *
     * <p>The chapter rung of the auto-claim ladder, and the row that makes the feature an author's
     * rather than fifty per-reward settings: the unset state defers to the pack setting in
     * {@code index.json}, and a quest can still override this for itself.
     */
    public static final Choice AUTO_CLAIM = new Choice("autoClaim", "tenet.dev.chapter.auto_claim",
            List.of("disabled", "enabled", "no_toast", "invisible"), "pack setting");

    /** The chapter's line-style default, one axis per row: what a line's "Use chapter default" resets to. */
    public static final Choice LINE_FORM = new Choice(DEPENDENCY_STYLE + ".form",
            "tenet.dev.chapter.line_form",
            List.of("orthogonal", "chamfered", "straight", "curved"), "chamfered");
    public static final Choice LINE_ARROW_HEAD = new Choice(DEPENDENCY_STYLE + ".arrowHead",
            "tenet.dev.chapter.line_head",
            List.of("chevron", "triangle", "dot", "diamond", "none"), "chevron");
    public static final Choice LINE_ARROW_PLACE = new Choice(DEPENDENCY_STYLE + ".arrowPlace",
            "tenet.dev.chapter.line_place",
            List.of("target", "both", "mid", "stream"), "target");
    public static final Choice LINE_ARROW_DENSITY = new Choice(DEPENDENCY_STYLE + ".arrowDensity",
            "tenet.dev.chapter.line_density", List.of("low", "medium", "high"), "medium");
    public static final Choice LINE_DASH = new Choice(DEPENDENCY_STYLE + ".dash",
            "tenet.dev.chapter.line_pattern",
            List.of("solid", "dashed", "dotted", "dash_dot", "double", "hazard"), "solid");
    public static final Choice LINE_WEIGHT = new Choice(DEPENDENCY_STYLE + ".weight",
            "tenet.dev.chapter.line_weight",
            List.of("thin", "thick", "bold", "conduit"), "thin");

    /** The cycling rows, in the order the Rules section carries them. */
    public static final List<Choice> CHOICES = List.of(PROGRESSION, PREREQUISITE, AUTO_CLAIM, GATE_MODE,
            LINE_FORM, LINE_ARROW_HEAD, LINE_ARROW_PLACE, LINE_ARROW_DENSITY, LINE_DASH, LINE_WEIGHT);

    /** The cycling row a key names, or null for every other row. */
    public static Choice choiceForKey(String key) {
        for (Choice choice : CHOICES) {
            if (choice.key().equals(key)) {
                return choice;
            }
        }
        return null;
    }

    /** Whether a row key is one of the cycling rows. */
    public static boolean isChoiceKey(String key) {
        return choiceForKey(key) != null;
    }

    /** The chapter's line-style default as its file holds it, or an empty object when it declares none. */
    public static JsonObject lineStyle(JsonObject chapter) {
        JsonElement found = chapter == null ? null : chapter.get(DEPENDENCY_STYLE);
        return found != null && found.isJsonObject() ? found.getAsJsonObject() : new JsonObject();
    }

    /**
     * The value in force: the field itself, or the axis inside the chapter's dependencyStyle.
     *
     * <p>An arrow row with no current-axis value but a legacy {@code arrows} value reports what that
     * value means for this row, so a chapter written before the split does not read "Default" while its
     * lines stream — the file genuinely says something about arrows, and the picker's job is to show the
     * state before offering to change it.
     */
    public static String choiceValue(JsonObject chapter, Choice choice) {
        String value = text(choice.isLineStyle() ? lineStyle(chapter) : chapter, choice.axis(), "");
        if (!value.isEmpty() || !choice.isLineStyle()) {
            return value;
        }
        return legacyArrowValue(lineStyle(chapter), choice.axis());
    }

    /** What a legacy arrows value means for one arrow row, or empty for every other row and value. */
    private static String legacyArrowValue(JsonObject style, String axis) {
        String legacy = text(style, DependencyStyle.LEGACY_ARROWS_FIELD, "");
        if (legacy.isEmpty()) {
            return "";
        }
        return switch (axis) {
            case "arrowHead" -> legacy.equals("none") ? "none" : "chevron";
            case "arrowPlace" -> switch (legacy) {
                case "both" -> "both";
                case "many" -> "stream";
                default -> "target";
            };
            default -> "";
        };
    }

    /**
     * A row's values as its menu offers them: the unset state first, then the file's own names.
     *
     * <p>This is what the picker shows, in this order. It was the ring a pair of arrows stepped around --
     * {@code cycleChoice} said where one press landed -- and the arrows are gone: the chooser opens the
     * list and the author takes the value they want, which is the same control the book tab's choices use.
     */
    public static List<String> choiceValues(Choice choice) {
        List<String> all = new ArrayList<>();
        all.add("");
        all.addAll(choice.values());
        return List.copyOf(all);
    }

    /** A value as a person reads it; the unset state names the fallback it falls back to. */
    public static String choiceLabel(Choice choice, String value) {
        if (value == null || value.isEmpty()) {
            return Labels.of("tenet.dev.chapter.default_value",
                    choice.fallback().replace('_', ' '));
        }
        return value.replace('_', ' ');
    }

    /** The op arguments a chosen value is written with: the path, and the value, null to remove. */
    public record Edit(String path, JsonElement value) {
    }

    /**
     * The write one chosen value lands on, as {@code EditorOp.SetChapter} takes it.
     *
     * <p>A rule writes itself: the value, or null for the unset state, which removes the field. A line
     * axis writes the whole {@code dependencyStyle} object, because that is the field the file has --
     * the object is copied, the one axis set or removed, and every other axis and the numeric bend
     * left exactly as they were. And when the last axis goes the object goes with it, the same rule
     * the per-line overrides follow: an empty object is a key that pins nothing.
     */
    public static Edit choiceEdit(JsonObject chapter, Choice choice, String value) {
        if (!choice.isLineStyle()) {
            return new Edit(choice.key(),
                    value == null || value.isEmpty() ? null : new JsonPrimitive(value));
        }
        JsonObject style = lineStyle(chapter).deepCopy();
        if (DependencyStyle.isArrowAxis(choice.axis())) {
            // The legacy arrows axis said the same three things at once; a chapter that speaks the
            // current vocabulary retires it, or a new axis returned to "Default" would fall back to it.
            style.remove(DependencyStyle.LEGACY_ARROWS_FIELD);
        }
        if (value == null || value.isEmpty()) {
            style.remove(choice.axis());
        }
        else {
            style.addProperty(choice.axis(), value);
        }
        return new Edit(DEPENDENCY_STYLE, style.isEmpty() ? null : style);
    }

    /**
     * One cycling row: a choice, which is what gives it a label and the chooser the drawer's other
     * choices have.
     *
     * <p>The value rides on the row because the vocabulary is the <i>file's</i> -- nine axes, each with
     * its own names and its own unset state -- so the class that reads the file states the word and the
     * screen only names it. See {@code ChapterPanelLayout.choiceLabel}.
     */
    private static ToolsLayout.Action choiceRow(JsonObject chapter, Choice choice) {
        return ToolsLayout.Action.choice(choice.key(), choice.label(), choiceValue(chapter, choice));
    }

    private static String text(JsonObject object, String member, String fallback) {
        JsonElement found = QuestPanelLayout.get(object, member);
        return found != null && found.isJsonPrimitive() ? found.getAsString() : fallback;
    }

    /**
     * A numeric field as a text row's value, or empty when the file says nothing.
     *
     * <p>Empty rather than the codec's default, for the same reason every other row here shows the
     * <i>file's</i> value: a row that printed the default would make "unset" and "set to the default"
     * look alike, and the first of those is what an author resets to.
     */
    private static String numberText(JsonObject object, String member) {
        JsonElement found = QuestPanelLayout.get(object, member);
        return found != null && found.isJsonPrimitive() && found.getAsJsonPrimitive().isNumber()
                ? String.valueOf(found.getAsInt()) : "";
    }

    /** The icon's id in whichever arm the chapter declares it, or "" when it declares none. */
    private static String iconId(JsonObject chapter) {
        JsonObject icon = icon(chapter);
        for (String arm : new String[] {"item", "texture", "sprite", "entity"}) {
            if (icon.has(arm) && icon.get(arm).isJsonPrimitive()) {
                return icon.get(arm).getAsString();
            }
        }
        return "";
    }

    /**
     * The description as the panel says it: how many paragraphs, and that they are edited in the file.
     *
     * <p>"Paragraphs" rather than "lines", which is what the model holds: the file's description is a
     * list of paragraphs, and calling them lines promised a wrapping that is not what the count means.
     */
    private static String description(JsonObject chapter) {
        JsonElement description = chapter.get("description");
        int paragraphs = description == null ? 0
                : description.isJsonArray() ? description.getAsJsonArray().size() : 1;
        if (paragraphs == 0) {
            return Labels.of("tenet.dev.chapter.none_in_file");
        }
        // One paragraph and several are two keys rather than one "paragraph(s)": the plural form is the
        // language's business, and a language may not have the same rule English does.
        return Labels.of(paragraphs == 1 ? "tenet.dev.chapter.paragraph_in_file"
                : "tenet.dev.chapter.paragraphs_in_file", paragraphs);
    }
}
