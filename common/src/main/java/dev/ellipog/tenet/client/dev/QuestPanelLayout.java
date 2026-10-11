package dev.ellipog.tenet.client.dev;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;

import dev.ellipog.armature.client.ui.inspect.InspectField;
import dev.ellipog.armature.client.ui.inspect.InspectRow;
import dev.ellipog.tenet.quest.EditorField;
import dev.ellipog.tenet.quest.EditorSpecs;
import dev.ellipog.tenet.quest.condition.ConditionTypes;
import dev.ellipog.tenet.quest.reward.ItemReward;
import dev.ellipog.tenet.quest.reward.RewardTypes;
import dev.ellipog.tenet.quest.task.TaskTypes;

import net.minecraft.resources.ResourceLocation;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;

/**
 * The quest panel's rows, from one quest's own tree.
 *
 * <h2>What reads what</h2>
 *
 * <p>The input is the {@code ClientChapterReplica} copy -- the quest's file as the server holds it, every
 * field in it, including the ones this build has never heard of. The output is a list of
 * {@link InspectRow}s in sections that fold: identity, placement, rules, dependencies, and one section
 * per task and per reward. A section folded is a section absent -- the caller rebuilds with the fold
 * named, and no row of it is built.
 *
 * <p>Row <b>keys are paths</b>. A field row's key is the dotted path its commit goes to
 * ({@code "tasks.0.count"}), because a path is unique within a quest and a second key scheme beside it
 * would be a second thing to keep in step. Rows that are not fields -- the headings, the read-only
 * values, the dependency rows, the raw fallback -- prefix their keys so they cannot collide with a path.
 *
 * <h2>The two voices of per-type</h2>
 *
 * <p>A task or reward whose {@code type} is registered gets its own fields, read from the registry the
 * validator reads -- so a type an addon registers is a panel the day it registers, with no second list
 * anywhere. One this build does not know gets the fallback: a warning heading, then the value itself as
 * it is stored. A fallback that invented rows for fields it did not understand would look like support;
 * showing the value cannot lie, and the tree's own preservation on save is what keeps it safe.
 *
 * <p>The fallback row is this row model's shape of an unknown type; the <b>card</b> is where it is
 * edited, in the multi-line JSON field, and {@code SetField} carries the parsed object whole. This
 * paragraph used to say the opposite -- that the model refused an object on principle -- which stopped
 * being true the day the op grew its {@code JsonElement} value. The stale note made the raw editor look
 * unbuilt while the card had already shipped it, which is worth remembering: a comment that describes a
 * limitation is a claim, and this one outlived its subject.
 */
public final class QuestPanelLayout {

    /** The foldable sections, by their heading key. */
    public static final String IDENTITY = "h:identity";
    public static final String PLACEMENT = "h:placement";
    public static final String RULES = "h:rules";
    public static final String DEPENDENCIES = "h:dependencies";

    /** The row that adds a dependency by typed id, and the prefix of every dependency's own row. */
    public static final String DEPENDENCY_ADD = "dep:add";
    public static final String DEPENDENCY_PICK = "dep:pick";
    public static final String DEPENDENCY_PREFIX = "dep:";

    /** The raw fallback's row prefix. */
    public static final String RAW_PREFIX = "raw:";

    /** The read-only values' prefix. */
    public static final String VALUE_PREFIX = "v:";

    /** The rows that add a task or a reward, and the prefix of a type-picker row. */
    public static final String ADD_TASKS = "add:tasks";
    public static final String ADD_REWARDS = "add:rewards";
    public static final String TYPE_PREFIX = "type:";

    private QuestPanelLayout() {
    }

    /**
     * The whole panel for one quest.
     *
     * @param quest  the quest's own tree from the replica, or null when nothing is selected -- which is
     *               an empty panel with a line saying so, not an empty panel that looks broken
     * @param folded the heading keys currently folded; a folded section contributes only its heading
     */
    public static List<InspectRow> rows(JsonObject quest, String questId, Set<String> folded) {
        Objects.requireNonNull(folded, "folded");
        List<InspectRow> rows = new ArrayList<>();
        if (quest == null) {
            rows.add(InspectRow.value(VALUE_PREFIX + "none", "tenet.dev.panel.quest",
                    questId == null ? Labels.of("tenet.dev.panel.select_quest")
                            : Labels.of("tenet.dev.panel.not_in_chapter", questId)));
            return List.copyOf(rows);
        }

        rows.add(InspectRow.heading(IDENTITY, "tenet.dev.panel.identity"));
        if (!folded.contains(IDENTITY)) {
            rows.add(InspectRow.value(VALUE_PREFIX + "id", "tenet.dev.panel.id",
                    text(quest, "id", questId)));
            rows.add(field(quest, "title"));
            rows.add(field(quest, "subtitle"));
            rows.add(field(quest, "icon.item"));
            int paragraphs = paragraphs(quest);
            // The chapter panel's keys and its noun, because both panels count the same thing: the
            // file's `description` array is a list of paragraphs, not of wrapped lines.
            rows.add(InspectRow.value(VALUE_PREFIX + "description", "tenet.dev.panel.description",
                    Labels.of(paragraphs == 1 ? "tenet.dev.chapter.paragraph_in_file"
                            : "tenet.dev.chapter.paragraphs_in_file", paragraphs)));
            rows.add(InspectRow.field("aliases", "tenet.dev.panel.aliases",
                    String.join(", ", strings(quest, "aliases"))));
            // Words this quest answers to in lookups by tag, comma-separated like the aliases
            // above: `#tag` resolves to the first object carrying it. Empty clears the list.
            rows.add(InspectRow.field("tags", "tenet.dev.panel.tags",
                    String.join(", ", strings(quest, "tags"))));
            // The guide book page this quest belongs to. A reference the quest card shows;
            // Tenet has no guide integration, so this is text and nothing more.
            rows.add(field(quest, "guidePage"));
        }

        rows.add(InspectRow.heading(PLACEMENT, "tenet.dev.panel.placement"));
        if (!folded.contains(PLACEMENT)) {
            rows.add(field(quest, "x"));
            rows.add(field(quest, "y"));
            rows.add(field(quest, "shape"));
            rows.add(field(quest, "size"));
            rows.add(toggle(quest, "showTitle"));
            rows.add(field(quest, "iconScale"));
        }

        rows.add(InspectRow.heading(RULES, "tenet.dev.panel.rules"));
        if (!folded.contains(RULES)) {
            rows.add(toggle(quest, "repeatable"));
            rows.add(field(quest, "repeatCooldownTicks"));
            rows.add(toggle(quest, "sequentialTasks"));
            rows.add(toggle(quest, "invisible"));
            rows.add(field(quest, "exclusiveGroup"));
            rows.add(field(quest, "prerequisiteMode"));
            rows.add(field(quest, "minRequired"));
            // The stage gate, beside the other rules about when a quest opens: it is the one of them that is
            // per player rather than per team, which is what its label says -- unless the row below
            // says the team's stages answer instead.
            rows.add(field(quest, "requiresStage"));
            rows.add(toggle(quest, "requiresStageTeam"));
        }

        rows.add(InspectRow.heading(DEPENDENCIES, "tenet.dev.panel.dependencies"));
        if (!folded.contains(DEPENDENCIES)) {
            for (String dependency : strings(quest, "dependsOn")) {
                rows.add(InspectRow.toggle(DEPENDENCY_PREFIX + dependency, dependency));
            }
            rows.add(InspectRow.field(DEPENDENCY_ADD, "tenet.dev.panel.add_by_id", ""));
            // The second way to say the same thing: the next canvas click names the quest. The third
            // -- dragging an edge between the nodes -- is the canvas's, and needs no row.
            rows.add(InspectRow.action(DEPENDENCY_PICK, "tenet.dev.panel.add_from_canvas"));
        }

        list(quest, "tasks", "tenet.dev.panel.task", folded, rows);
        rows.add(InspectRow.action(ADD_TASKS, "tenet.dev.panel.add_task"));
        list(quest, "rewards", "tenet.dev.panel.reward", folded, rows);
        rows.add(InspectRow.action(ADD_REWARDS, "tenet.dev.panel.add_reward"));
        return List.copyOf(rows);
    }

    /** One heading of the picker's list, with the types shown under it. */
    public record TypeGroup(String title, List<TypeChoice> types) {
    }

    /**
     * One type's presentation: what the picker calls it, what it is for, whether a row's sentence about
     * it names a registry id the client should prettify before showing -- and, separately, how the row
     * is explained to a <b>player</b>.
     *
     * <h2>Why two hints and not one</h2>
     *
     * <p>They are read by different people. {@code hint} is the picker's line, written for an author
     * choosing a type ("Hand in a count of any item in a tag"), and it travels with the fields and the
     * id in the picker's own tooltip. {@code playerHint} is what a reader sees when they hover the row
     * in a quest card: no field names, no ids, no jargon -- what the row is asking <i>them</i> to do.
     * A type whose two audiences would read the same words still spells both, because the next edit to
     * one of them must not silently change the other.
     *
     * <p>The flag is a property of the <b>sentence</b>, not of the registry. A dimension's row says
     * "Visit minecraft:overworld", so the id is the one thing on it that wants prettifying; a stage's
     * says "Have the stage my_pack:inducted", where the id <i>is</i> the name and prettifying it would
     * invent one. Only the type knows which of those its sentence is, and the table is where the type
     * is already described.
     */
    public record TypeChoice(String id, String name, String hint, String playerHint, boolean friendlyArg) {

        /** A type whose argument is not a registry id, or whose id is the name. */
        public TypeChoice(String id, String name, String hint, String playerHint) {
            this(id, name, hint, playerHint, false);
        }
    }

    /** The heading every registered type the table does not name is listed under. */
    public static final String MORE = "tenet.dev.panel.more";

    /** The key prefix of a group's heading; the index follows, so two groups cannot share a key. */
    public static final String TYPE_GROUP_PREFIX = "h:type:";

    /**
     * The picker's grouping, order, names and hints for the built-in types.
     *
     * <p>Curated, because none of those four things can be derived: a registry knows ids, not what to
     * call them or which belong together. The table is presentation only -- {@link #typeRows} lists what
     * the registries hold, so a type it does not name still appears, under {@link #MORE}, named by its
     * own id.
     */
    private static final List<TypeGroup> TASK_GROUPS = List.of(
            new TypeGroup("tenet.dev.type.group.hand_in", List.of(
                    new TypeChoice("tenet:item", "tenet.dev.type.task.item.name",
                            "tenet.dev.type.task.item.hint", "tenet.dev.type.task.item.player"),
                    new TypeChoice("tenet:item_tag", "tenet.dev.type.task.item_tag.name",
                            "tenet.dev.type.task.item_tag.hint", "tenet.dev.type.task.item_tag.player", true),
                    new TypeChoice("tenet:xp", "tenet.dev.type.task.xp.name",
                            "tenet.dev.type.task.xp.hint", "tenet.dev.type.task.xp.player"),
                    new TypeChoice("tenet:fluid", "tenet.dev.type.task.fluid.name",
                            "tenet.dev.type.task.fluid.hint", "tenet.dev.type.task.fluid.player", true),
                    new TypeChoice("tenet:filter", "tenet.dev.type.task.filter.name",
                            "tenet.dev.type.task.filter.hint", "tenet.dev.type.task.filter.player", true),
                    new TypeChoice("tenet:energy", "tenet.dev.type.task.energy.name",
                            "tenet.dev.type.task.energy.hint", "tenet.dev.type.task.energy.player", true))),
            new TypeGroup("tenet.dev.type.group.go", List.of(
                    new TypeChoice("tenet:dimension", "tenet.dev.type.task.dimension.name",
                            "tenet.dev.type.task.dimension.hint", "tenet.dev.type.task.dimension.player", true),
                    new TypeChoice("tenet:biome", "tenet.dev.type.task.biome.name",
                            "tenet.dev.type.task.biome.hint", "tenet.dev.type.task.biome.player", true),
                    new TypeChoice("tenet:structure", "tenet.dev.type.task.structure.name",
                            "tenet.dev.type.task.structure.hint", "tenet.dev.type.task.structure.player", true),
                    new TypeChoice("tenet:location", "tenet.dev.type.task.location.name",
                            "tenet.dev.type.task.location.hint", "tenet.dev.type.task.location.player"))),
            new TypeGroup("tenet.dev.type.group.progress", List.of(
                    new TypeChoice("tenet:advancement", "tenet.dev.type.task.advancement.name",
                            "tenet.dev.type.task.advancement.hint", "tenet.dev.type.task.advancement.player", true),
                    new TypeChoice("tenet:stat", "tenet.dev.type.task.stat.name",
                            "tenet.dev.type.task.stat.hint", "tenet.dev.type.task.stat.player", true),
                    new TypeChoice("tenet:stage", "tenet.dev.type.task.stage.name",
                            "tenet.dev.type.task.stage.hint", "tenet.dev.type.task.stage.player"),
                    new TypeChoice("tenet:kill", "tenet.dev.type.task.kill.name",
                            "tenet.dev.type.task.kill.hint", "tenet.dev.type.task.kill.player", true),
                    new TypeChoice("tenet:break", "tenet.dev.type.task.break.name",
                            "tenet.dev.type.task.break.hint", "tenet.dev.type.task.break.player", true),
                    new TypeChoice("tenet:observation", "tenet.dev.type.task.observation.name",
                            "tenet.dev.type.task.observation.hint", "tenet.dev.type.task.observation.player", true))),
            new TypeGroup("tenet.dev.type.group.manual", List.of(
                    new TypeChoice("tenet:checkmark", "tenet.dev.type.task.checkmark.name",
                            "tenet.dev.type.task.checkmark.hint", "tenet.dev.type.task.checkmark.player"))),
            // Last, and its own group: this one is not a kind of thing to ask for but a way of asking for
            // anything -- the handler a mod or a script registered decides what it means.
            new TypeGroup("tenet.dev.type.group.other", List.of(
                    new TypeChoice("tenet:custom", "tenet.dev.type.task.custom.name",
                            "tenet.dev.type.task.custom.hint", "tenet.dev.type.task.custom.player"))));

    private static final List<TypeGroup> REWARD_GROUPS = List.of(
            new TypeGroup("tenet.dev.type.group.items", List.of(
                    new TypeChoice("tenet:item", "tenet.dev.type.reward.item.name",
                            "tenet.dev.type.reward.item.hint", "tenet.dev.type.reward.item.player"),
                    new TypeChoice("tenet:random", "tenet.dev.type.reward.random.name",
                            "tenet.dev.type.reward.random.hint", "tenet.dev.type.reward.random.player"),
                    new TypeChoice("tenet:loot", "tenet.dev.type.reward.loot.name",
                            "tenet.dev.type.reward.loot.hint", "tenet.dev.type.reward.loot.player"),
                    new TypeChoice("tenet:all_table", "tenet.dev.type.reward.all_table.name",
                            "tenet.dev.type.reward.all_table.hint", "tenet.dev.type.reward.all_table.player"))),
            new TypeGroup("tenet.dev.type.group.choice", List.of(
                    new TypeChoice("tenet:choice", "tenet.dev.type.reward.choice.name",
                            "tenet.dev.type.reward.choice.hint", "tenet.dev.type.reward.choice.player"))),
            new TypeGroup("tenet.dev.type.group.server", List.of(
                    new TypeChoice("tenet:command", "tenet.dev.type.reward.command.name",
                            "tenet.dev.type.reward.command.hint", "tenet.dev.type.reward.command.player"),
                    new TypeChoice("tenet:toast", "tenet.dev.type.reward.toast.name",
                            "tenet.dev.type.reward.toast.hint", "tenet.dev.type.reward.toast.player"),
                    new TypeChoice("tenet:advancement", "tenet.dev.type.reward.advancement.name",
                            "tenet.dev.type.reward.advancement.hint", "tenet.dev.type.reward.advancement.player", true),
                    new TypeChoice("tenet:custom", "tenet.dev.type.reward.custom.name",
                            "tenet.dev.type.reward.custom.hint", "tenet.dev.type.reward.custom.player"))),
            new TypeGroup("tenet.dev.type.group.progress", List.of(
                    new TypeChoice("tenet:xp", "tenet.dev.type.reward.xp.name",
                            "tenet.dev.type.reward.xp.hint", "tenet.dev.type.reward.xp.player"),
                    new TypeChoice("tenet:currency", "tenet.dev.type.reward.currency.name",
                            "tenet.dev.type.reward.currency.hint", "tenet.dev.type.reward.currency.player"),
                    new TypeChoice("tenet:stage", "tenet.dev.type.reward.stage.name",
                            "tenet.dev.type.reward.stage.hint", "tenet.dev.type.reward.stage.player"))));

    private static final List<TypeGroup> CONDITION_GROUPS = List.of(
            new TypeGroup("tenet.dev.type.group.items", List.of(
                    new TypeChoice("tenet:item", "tenet.dev.type.condition.item.name",
                            "tenet.dev.type.condition.item.hint", "tenet.dev.type.condition.item.player"),
                    new TypeChoice("tenet:item_tag", "tenet.dev.type.condition.item_tag.name",
                            "tenet.dev.type.condition.item_tag.hint", "tenet.dev.type.condition.item_tag.player"))),
            new TypeGroup("tenet.dev.type.group.progress", List.of(
                    new TypeChoice("tenet:advancement", "tenet.dev.type.condition.advancement.name",
                            "tenet.dev.type.condition.advancement.hint", "tenet.dev.type.condition.advancement.player"),
                    new TypeChoice("tenet:score", "tenet.dev.type.condition.score.name",
                            "tenet.dev.type.condition.score.hint", "tenet.dev.type.condition.score.player"),
                    new TypeChoice("tenet:stage", "tenet.dev.type.condition.stage.name",
                            "tenet.dev.type.condition.stage.hint", "tenet.dev.type.condition.stage.player"))),
            new TypeGroup("tenet.dev.type.group.party", List.of(
                    new TypeChoice("tenet:party_size", "tenet.dev.type.condition.party_size.name",
                            "tenet.dev.type.condition.party_size.hint", "tenet.dev.type.condition.party_size.player"))));

    /**
     * The type picker's rows: the page's own heading, then one group heading per table group with the
     * registered types of the member being added to under it.
     *
     * <p>From the registries, so an addon's type is in the list the day it registers. The table supplies
     * the grouping, the order, the name and the hint; a registered id it does not name is still listed,
     * under {@link #MORE} and named by its id -- the spelling an author meets in the JSON, in the entry
     * row's hover label and in every error message.
     */
    public static List<InspectRow> typeRows(String member) {
        Set<String> registered = new TreeSet<>();
        for (ResourceLocation id : ("rewards".equals(member) ? RewardTypes.ids() : TaskTypes.ids())) {
            registered.add(id.toString());
        }
        return typeRows(member, registered);
    }

    /**
     * The same, for a page whose <b>chrome</b> carries the page's name: no heading row at all.
     *
     * <h2>Where a page's name lives, and why this is a second method rather than a flag</h2>
     *
     * <p>The table editor's strip says "Add a reward" while the page is open, so a heading row naming the
     * page again put one sentence on screen twice — and naming the *table* there instead ("dregs") was no
     * better: the line said nothing the chrome had not, and it was the first thing the eye landed on. So
     * this page has no first line: its list begins at its first group heading.
     *
     * <p>A quest's own card cannot do that: its chrome is the quest's identity, and the page is a guest in
     * it, so that host keeps {@link #typeRows(String)} and names the act on its first list line. Two
     * methods, because "who names this page" is the difference — a boolean would say the same thing with no
     * name for it.
     */
    public static List<InspectRow> typeRowsNamedInChrome(String member) {
        Set<String> registered = new TreeSet<>();
        for (ResourceLocation id : ("rewards".equals(member) ? RewardTypes.ids() : TaskTypes.ids())) {
            registered.add(id.toString());
        }
        return rowsNamed("", groupsFor(member), registered);
    }

    /**
     * The same, over an id set the caller supplies.
     *
     * <p>How the fallback group is tested: an id no table names is what an addon's type looks like, and
     * the alternative -- registering one into a registry the whole test JVM shares -- would leave every
     * other test running against a build with an addon installed.
     */
    static List<InspectRow> typeRows(String member, Set<String> registered) {
        boolean tasks = !"rewards".equals(member);
        // The picker's own heading, which is a sentence about adding -- "Add a task" -- rather than the
        // dock's button label ("Add task"): two surfaces, two keys, and the words differ.
        return rowsFor(tasks ? "tenet.dev.panel.add_a_task" : "tenet.dev.panel.add_a_reward",
                groupsFor(member), registered);
    }

    /** The groups one member's types are listed under: tasks grouped one way, rewards another. */
    private static List<TypeGroup> groupsFor(String member) {
        return "rewards".equals(member) ? REWARD_GROUPS : TASK_GROUPS;
    }

    /**
     * The condition picker's rows: the same machinery as the task and reward pickers, over the third
     * registry.
     *
     * <p>Its own headings rather than a shared "Add a task" one, because a condition is not a task and
     * the picker is the only place an author learns the six types' names.
     */
    public static List<InspectRow> conditionTypeRows() {
        Set<String> registered = new TreeSet<>();
        for (ResourceLocation id : ConditionTypes.ids()) {
            registered.add(id.toString());
        }
        return rowsFor("tenet.dev.panel.add_condition", CONDITION_GROUPS, registered);
    }

    /**
     * The rows one picker shows: the page's own heading, then each table group with the registered types
     * it names, then everything the table does not name under {@link #MORE}.
     *
     * <p>Shared by all three pickers so a type's row, a group's heading and the fallback group cannot
     * mean different things in different lists — and so an addon's type is in each list the day it
     * registers, named by its id where the table has nothing to call it.
     */
    private static List<InspectRow> rowsFor(String heading, List<TypeGroup> groups,
                                            Set<String> registered) {
        return rowsNamed(Labels.of(heading), groups, registered);
    }

    /**
     * The rows, with the first line's text already decided.
     *
     * <p>The two callers disagree about that one line and agree about everything else: one names the page
     * from its own key, the other has the page's name in the chrome and asks for no line at all. A blank
     * title is therefore not a heading with nothing in it — it is no heading, which is what
     * {@link #typeRowsNamedInChrome} means. The groups, the fallback group and the type rows are the same
     * in both, which is the part that must not drift.
     */
    private static List<InspectRow> rowsNamed(String titled, List<TypeGroup> groups,
                                              Set<String> registered) {
        List<InspectRow> rows = new ArrayList<>();
        if (!titled.isBlank()) {
            rows.add(InspectRow.heading("h:type", titled));
        }
        Set<String> named = new TreeSet<>();
        int group = 0;
        for (TypeGroup each : groups) {
            List<TypeChoice> shown = each.types().stream()
                    .filter(choice -> registered.contains(choice.id())).toList();
            if (shown.isEmpty()) {
                continue;
            }
            rows.add(InspectRow.heading(TYPE_GROUP_PREFIX + group++, Labels.of(each.title())));
            for (TypeChoice choice : shown) {
                // The id rides in the row's value slot, which is what the row drawing puts at the right
                // end — the same place the item picker puts an item's id, so the two lists read alike and
                // the spelling a file uses is on screen rather than only in a hover.
                rows.add(new InspectRow(TYPE_PREFIX + choice.id(), InspectRow.Kind.ACTION,
                        Labels.of(choice.name()), choice.id()));
                named.add(choice.id());
            }
        }
        Set<String> unnamed = new TreeSet<>(registered);
        unnamed.removeAll(named);
        if (!unnamed.isEmpty()) {
            rows.add(InspectRow.heading(TYPE_GROUP_PREFIX + group, Labels.of(MORE)));
            for (String id : unnamed) {
                rows.add(InspectRow.action(TYPE_PREFIX + id, id));
            }
        }
        return List.copyOf(rows);
    }

    /**
     * A condition type's name from the picker's table, or its prettified path for one the table does not
     * name — an addon's, which the badge names by the spelling a file uses.
     */
    public static String conditionName(String typeId) {
        for (TypeGroup group : CONDITION_GROUPS) {
            for (TypeChoice choice : group.types()) {
                if (choice.id().equals(typeId)) {
                    return Labels.of(choice.name());
                }
            }
        }
        ResourceLocation id = ResourceLocation.tryParse(typeId);
        return id == null ? typeId : EditorSpecs.label(id.getPath());
    }

    /**
     * The declared form for a condition's type, or empty for one this build does not know — the raw-JSON
     * fallback's trigger, the same as an entry's own unknown type.
     */
    public static List<EditorField> conditionEditorFor(JsonObject condition) {
        String type = text(condition, "type", "");
        ResourceLocation id = ResourceLocation.tryParse(type);
        return id == null ? List.of() : Advanced.fields(ConditionTypes.editorOf(id));
    }

    /**
     * The author's description of a condition type, for the picker's button hover: what the table says it
     * is for, and the fields it will ask for. Two lines at most -- see {@link #typeTooltip}.
     */
    public static List<String> conditionTypeTooltip(String typeId) {
        List<String> lines = new ArrayList<>();
        for (TypeGroup group : CONDITION_GROUPS) {
            for (TypeChoice choice : group.types()) {
                if (choice.id().equals(typeId)) {
                    lines.add(Labels.of(choice.hint()));
                }
            }
        }
        ResourceLocation id = ResourceLocation.tryParse(typeId);
        if (id != null && ConditionTypes.ids().contains(id)) {
            Set<String> fields = ConditionTypes.fieldsOf(id);
            if (!fields.isEmpty()) {
                lines.add(Labels.of("tenet.dev.tip.fields", String.join(", ", new TreeSet<>(fields))));
            }
        }
        else {
            lines.add(typeId);
        }
        return List.copyOf(lines);
    }

    /**
     * Whether a type's sentence names a registry id the client should prettify before it is shown.
     *
     * <p>False for a type the table does not name: an addon's sentence is its own, and rewriting an id
     * inside it would be a guess about a shape this build has never seen.
     */
    public static boolean prettifiesIds(String member, String typeId) {
        TypeChoice choice = choiceFor(member, typeId);
        return choice != null && choice.friendlyArg();
    }

    /**
     * A registry id as a row reads it: the path, prettified, with a tag's leading {@code #} kept.
     *
     * <p>A string that is not a valid id at all is returned unchanged -- but a bare word is not such a
     * string: since 1.21 it parses as {@code minecraft:<word>}, so this is the rewrite to apply once a
     * token is known to be an id, not the test for whether it is one. That test is
     * {@link #prettiedArgument}'s, and it asks for a namespace.
     */
    public static String friendlyId(String id) {
        boolean tag = id.startsWith("#");
        ResourceLocation location = ResourceLocation.tryParse(tag ? id.substring(1) : id);
        if (location == null) {
            return id;
        }
        return (tag ? "#" : "") + EditorSpecs.label(location.getPath());
    }

    /**
     * A type's argument, with the registry ids in it prettified when the type's table entry says its
     * argument names one.
     *
     * <h2>Why token by token, and why a token must be a full id</h2>
     *
     * <p>Because some sentences put more than an id in the argument. A fluid task's is
     * "1000 mB of minecraft:water" and an observation's is "minecraft:beacon for 2.0s", and the words
     * around the id are not registry ids -- running the whole argument through the prettifier would
     * mangle them.
     *
     * <p>And a bare word is not evidence of an id: since 1.21 a namespace-less string parses as
     * {@code minecraft:<word>}, so prettifying every parseable token would turn the "of" in that fluid
     * sentence into "Of" and a kill task's "anything" into "Anything". Only a token that spells a
     * namespace is an id here -- which is what the sentences write, since they all stringify a
     * {@code ResourceLocation} -- and everything else passes through exactly as the server wrote it.
     */
    public static String prettiedArgument(String member, String typeId, String arg) {
        return prettiedArgument(member, typeId, arg, null);
    }

    /**
     * The same, with a chance to name a full id better than its path can.
     *
     * <p>{@code betterName} is asked for each id token, with the token's id as its argument, and its
     * answer is used when it has one. An advancement is the caller that needs this -- the client holds
     * the advancement's own title, which is what the player knows it by -- and every other type passes
     * null.
     */
    public static String prettiedArgument(String member, String typeId, String arg,
                                          java.util.function.Function<String, String> betterName) {
        if (!prettifiesIds(member, typeId)) {
            return arg;
        }
        StringBuilder out = new StringBuilder();
        for (String token : arg.split(" ")) {
            if (!out.isEmpty()) {
                out.append(' ');
            }
            String bare = token.startsWith("#") ? token.substring(1) : token;
            if (bare.indexOf(':') < 0) {
                out.append(token);
                continue;
            }
            String better = betterName == null ? null : betterName.apply(bare);
            out.append(better != null ? better : friendlyId(token));
        }
        return out.toString();
    }

    /**
     * The hover description of a type in the picker: what it is for, the fields it takes, and the id a
     * file spells.
     *
     * <p>Plain strings, because that is what the picker's buttons draw with. This is the <b>author's</b>
     * description and it says so: field names are what an author edits, and the picker is a tool only
     * edit mode reaches. What a player reads on a row's hover is {@link #playerTooltip}, which shares
     * none of these lines.
     *
     * <p><b>Two lines at most.</b> The hint says what the type is for and the second line names the
     * fields it will ask for, because that is the question a picker row is being asked. The id is a
     * third line only when nothing else names the type -- an addon's, whose id <i>is</i> its name; for a
     * type this build knows, the hint and the fields have already said which one it is, and a hover that
     * repeats them is a hover nobody finishes reading.
     */
    public static List<String> typeTooltip(String member, String typeId) {
        TypeChoice choice = choiceFor(member, typeId);
        List<String> lines = new ArrayList<>();
        if (choice != null) {
            lines.add(Labels.of(choice.hint()));
        }
        ResourceLocation id = ResourceLocation.tryParse(typeId);
        if (id != null) {
            Set<String> fields = "rewards".equals(member) ? RewardTypes.fieldsOf(id) : TaskTypes.fieldsOf(id);
            if (!fields.isEmpty()) {
                lines.add(Labels.of("tenet.dev.tip.fields", String.join(", ", new TreeSet<>(fields))));
            }
        }
        if (choice == null) {
            lines.add(typeId);
        }
        return List.copyOf(lines);
    }

    /** The player line for a type the table does not name: true of every one of them, and nothing else. */
    private static final String UNKNOWN_TASK = "tenet.screen.tip.unknown_task";
    private static final String UNKNOWN_REWARD = "tenet.screen.tip.unknown_reward";

    /** The type's player-facing line, or null for one the table does not name. */
    public static String playerHint(String member, String typeId) {
        TypeChoice choice = choiceFor(member, typeId);
        return choice == null ? null : Labels.of(choice.playerHint());
    }

    /**
     * What a player reads when they hover a task or reward row in a quest card.
     *
     * <h2>Written for the reader, not the author</h2>
     *
     * <p>No field names, no ids, no type names: the row beside the pointer already says what the row
     * asks, and this says what that <i>means</i> and how it is completed. An author who wants the fields
     * and the id has the picker's tooltip, which is where that belongs -- see {@link #typeTooltip}.
     *
     * <h2>The two row-specific facts, and why they are asked rather than guessed</h2>
     *
     * <p>{@code byHand} is whether this row offers the Submit button right now. {@code takes} is whether
     * the task takes what it asks for when it is handed in -- the server's answer, sent with the tree as
     * {@code waits}, rather than a list of type ids copied here. The copy was wrong twice over: it read
     * {@code tenet:item} as always taking, so a chapter's consume-items default was invisible to it, and
     * it printed "nothing is taken" on a locked consuming row, which is the one sentence this hover must
     * never print when the task takes.
     *
     * <p>What the line claims therefore depends on {@code takes} first: a task that takes says so whether
     * or not the button is drawn yet (a locked row and a row whose count is unmet both take the items when
     * they are handed in), and a task that takes nothing says that instead -- the surprising half of the
     * two behaviours, and the half a player has no other way to learn. A checkmark and an addon's handler
     * each get their own neutral wording.
     */
    public static List<String> playerTooltip(String member, String typeId, boolean byHand, boolean takes) {
        boolean rewards = "rewards".equals(member);
        String line = playerHint(member, typeId);
        List<String> lines = new ArrayList<>();
        lines.add(Labels.of(line == null ? (rewards ? UNKNOWN_REWARD : UNKNOWN_TASK) : line));
        if (takes) {
            lines.add(Labels.of(byHand
                    ? "tenet.screen.tip.submit_takes"
                    : "tenet.screen.tip.takes_when_handed_in"));
            return List.copyOf(lines);
        }
        if (!byHand) {
            if (!rewards && carriesItems(typeId)) {
                lines.add(Labels.of("tenet.screen.tip.not_taken"));
            }
            return List.copyOf(lines);
        }
        if ("tenet:checkmark".equals(typeId)) {
            lines.add(Labels.of("tenet.screen.tip.submit_checkmark"));
        }
        else {
            lines.add(Labels.of("tenet.screen.tip.submit"));
        }
        return List.copyOf(lines);
    }

    /** The task kinds a row can carry in an inventory: their sentences are about having, not doing. */
    private static boolean carriesItems(String typeId) {
        return "tenet:item".equals(typeId) || "tenet:item_tag".equals(typeId);
    }

    /**
     * The name the picker and an entry's badge show for a type.
     *
     * <p>The table's own word, or the id for a type it does not name -- an addon's, which is the spelling
     * its author meets in the file and in every error message, and therefore the honest fallback.
     */
    public static String typeName(String member, String typeId) {
        TypeChoice choice = choiceFor(member, typeId);
        return choice == null ? typeId : Labels.of(choice.name());
    }

    private static TypeChoice choiceFor(String member, String typeId) {
        for (TypeGroup group : "rewards".equals(member) ? REWARD_GROUPS : TASK_GROUPS) {
            for (TypeChoice choice : group.types()) {
                if (choice.id().equals(typeId)) {
                    return choice;
                }
            }
        }
        return null;
    }

    /**
     * The fields of one entry, from its own type's registration: the type's form, with the settings every
     * task or reward has already appended to it.
     *
     * <p>Empty for a type this build does not know, and for an entry whose {@code type} is missing or
     * malformed. There is no form to draw for those, and the card draws the raw value instead -- the same
     * refusal the reader makes, and the one that keeps an unknown type editable rather than silently
     * blank.
     */
    public static List<dev.ellipog.tenet.quest.EditorField> editorFor(String member, JsonObject entry) {
        String type = text(entry, "type", "");
        ResourceLocation id = ResourceLocation.tryParse(type);
        if (id == null) {
            return List.of();
        }
        // Through the depth, because this is where six readers meet: the card's cells and its line counts,
        // the widget pass, the path-to-field lookup that commits and targets go through, and the "My
        // position" press. See `Advanced.fields` for why the filter is at that seam rather than at each
        // caller -- and note that it is applied *after* the registry's own form, because
        // `EditorSpecsTest` holds that form equal to the codec's field set in both directions.
        return Advanced.fields("rewards".equals(member) ? RewardTypes.editorOf(id) : TaskTypes.editorOf(id));
    }

    /** Whether a type id is one this build can add to the given member. */
    public static boolean canAdd(String member, String typeId) {
        ResourceLocation id = ResourceLocation.tryParse(typeId);
        if (id == null) {
            return false;
        }
        return "rewards".equals(member) ? RewardTypes.ids().contains(id) : TaskTypes.ids().contains(id);
    }

    /**
     * The tree a fresh <b>task or reward</b> of this type starts as, or null for a type this build
     * cannot add.
     *
     * <h2>This returns the element of a quest's list, not of a table's</h2>
     *
     * <p>A quest's {@code rewards[]} holds rewards and a table's {@code entries[]} holds
     * {@code {weight, reward}} pairs, so this tree is the right element for exactly one of the two
     * lists. It said "a fresh entry of this type", and that word is what cost the table editor every
     * press of its {@code + Reward} button: the caller read "entry", passed the result to
     * {@code TableOp.Insert} — which documents itself as taking one entry — and the file was written
     * with a reward where an entry belongs, refused by the validator as {@code unknown field "type"}.
     *
     * <p>A table's element is built by {@link #tableEntry}, and the two are deliberately separate
     * methods rather than one that guesses: nothing about a reward tree says which list it is for.
     */
    public static JsonObject defaultElement(String member, String typeId) {
        ResourceLocation id = ResourceLocation.tryParse(typeId);
        if (id == null) {
            return null;
        }
        return ("rewards".equals(member) ? RewardTypes.defaultTree(id) : TaskTypes.defaultTree(id))
                .orElse(null);
    }

    /**
     * One <b>table entry</b>, from a reward tree: {@code {"weight": 1, "reward": …}}.
     *
     * <p>The shape is the file's — {@code RewardTable.Entry}'s two fields — and it lives here, beside
     * {@link #defaultElement}, because that pairing is the whole lesson: a reward and a table entry are
     * two different elements, and the three places that add an entry (the type picker, an item pick, a
     * drag from a recipe viewer) each built this by hand. Two of them wrapped the reward and one did
     * not, which is why adding an item worked and adding anything else was refused.
     */
    public static JsonObject tableEntry(JsonObject reward) {
        JsonObject entry = new JsonObject();
        // One, and not a share of anything: a weight added by a press is the neutral starting point an
        // author tunes from. Zero would mean "always granted", which is a statement about the entry
        // rather than a default.
        entry.addProperty("weight", 1);
        entry.add("reward", reward);
        return entry;
    }

    /**
     * One table entry holding an item, with the count of the stack it came from.
     *
     * <h2>Why the count is a parameter rather than the caller's business</h2>
     *
     * <p>The two ways an item gets into a table disagreed about it: a recipe-viewer drop carried the
     * stack's count — a dragged stack of eight became eight — while an item picked in the picker, which
     * <i>shows</i> the carried count ("x128 Iron Ore"), wrote no count at all and became one. The same
     * stack, the same panel, two answers; and the picker was showing a number it then threw away.
     *
     * <p>A count of one is written as <b>absent</b>, because that is what the format's default is: an
     * entry carrying no {@code count} and one carrying {@code 1} are the same entry, and the shorter file
     * is the one an author reads.
     */
    public static JsonObject itemEntry(String itemId, int count, com.google.gson.JsonElement components) {
        JsonObject reward = new JsonObject();
        reward.addProperty("type", ItemReward.TYPE.toString());
        reward.addProperty("item", itemId);
        if (count > 1) {
            reward.addProperty("count", count);
        }
        if (components != null && !components.isJsonNull()) {
            reward.add("components", components);
        }
        return tableEntry(reward);
    }

    /**
     * One task's or one reward's section: its type's fields, or the fallback.
     *
     * <p>A registered type's rows are one per field name in the registry's own set, sorted so the panel
     * is stable across rebuilds -- a set's order is not, and a panel whose rows shuffle between frames
     * is a panel an author cannot aim at.
     */
    private static void list(JsonObject quest, String member, String whatKey, Set<String> folded,
                             List<InspectRow> rows) {
        if (!quest.has(member) || !quest.get(member).isJsonArray()) {
            return;
        }
        // Resolved here rather than carried as a key: the entry heading is composed from it, and a key
        // concatenated with a number and a type id is a string no language file could translate whole.
        String what = Labels.of(whatKey);
        var array = quest.getAsJsonArray(member);
        for (int i = 0; i < array.size(); i++) {
            JsonElement element = array.get(i);
            if (!element.isJsonObject()) {
                continue;
            }
            JsonObject entry = element.getAsJsonObject();
            String headingKey = "h:" + member + "." + i;
            String type = text(entry, "type", "not stated");
            boolean known = knownType(type);

            if (known) {
                // An entry row rather than a plain heading: its controls -- duplicate, remove -- go on
                // the line that names what they act on.
                rows.add(InspectRow.entry(headingKey,
                        Labels.of("tenet.dev.panel.entry", what, i + 1, type)));
            }
            else {
                rows.add(InspectRow.warning(headingKey,
                        Labels.of("tenet.dev.panel.entry_unknown", what, i + 1, type)));
            }
            if (folded.contains(headingKey)) {
                continue;
            }
            if (known) {
                for (String fieldName : new TreeSet<>(fieldsOf(type, entry))) {
                    // The value is read from the quest's own root, with the full path: the commit goes
                    // back through the same path, and a read that started at the task object would be
                    // answering a path that names nothing from where it stands.
                    rows.add(field(quest, member + "." + i + "." + fieldName));
                }
            }
            else {
                rows.add(InspectRow.raw(RAW_PREFIX + member + "." + i,
                        Labels.of("tenet.dev.panel.as_stored"), entry.toString()));
            }
        }
    }

    /** Whether a type id is one this build has a panel for. The fallback is not a panel. */
    public static boolean knownType(String type) {
        ResourceLocation id = ResourceLocation.tryParse(type);
        if (id == null) {
            return false;
        }
        return TaskTypes.ids().contains(id) || RewardTypes.ids().contains(id);
    }

    /** A registered type's own field names, sorted. Empty for an unregistered one. */
    private static Set<String> fieldsOf(String type, JsonObject entry) {
        ResourceLocation id = ResourceLocation.tryParse(type);
        if (id == null) {
            return Set.of();
        }
        Set<String> fields = TaskTypes.ids().contains(id) ? TaskTypes.fieldsOf(id) : Set.of();
        if (fields.isEmpty()) {
            fields = RewardTypes.ids().contains(id) ? RewardTypes.fieldsOf(id) : Set.of();
        }
        // Structured fields are not rows of this panel: a text field for an object or an array would
        // offer to write a *string* where the format holds a structure -- a row that corrupts the file
        // it is drawn from. `components` is the named case; a field whose stored value is an array or
        // an object -- a location's position -- is the general one. The editor's card still edits
        // those, as indexed numbers or through the picker. An absent structured field stays a row
        // (as text), which is how it gets created in the first place.
        return fields.stream().filter(field -> {
            // Structured fields are not rows of this panel, named or otherwise: `components` because the
            // item control owns it, and `conditions` because the card draws the list as its own section
            // -- a text row here would offer to write a string where the format holds a list, which is
            // the corruption this filter exists to prevent.
            if ("components".equals(field) || "conditions".equals(field)) {
                return false;
            }
            JsonElement value = entry.get(field);
            return value == null || (!value.isJsonArray() && !value.isJsonObject());
        }).collect(java.util.stream.Collectors.toUnmodifiableSet());
    }

    // ------------------------------------------------------------------
    // The fields, and what type each one edits as
    // ------------------------------------------------------------------

    /**
     * The typed field for editing whatever is at a path <b>right now</b>.
     *
     * <p>Asked again at commit time rather than remembered from when the rows were built, because the
     * tree may have changed underneath the panel -- a revision boundary is the only thing that rebuilds
     * it, and a value read from a stale tree is a value the server does not have.
     *
     * <p>The kind comes from three answers, in order: what the format <b>declares</b> the field to be,
     * which holds even when the value is absent and is what lets a panel add an {@code iconScale} that
     * was never there; else what is <b>there</b> -- a number that is whole edits whole and one that is
     * not as a decimal, a boolean as a flag; else text, which is the honest answer for a field whose
     * value is a string or has never been written.
     */
    public static InspectField<?> fieldFor(JsonObject quest, String path) {
        String label = labelFor(path);
        InspectField<?> declared = declared(path, label);
        if (declared != null) {
            return declared;
        }
        JsonElement current = get(quest, path);
        if (current != null && current.isJsonPrimitive()) {
            JsonPrimitive primitive = current.getAsJsonPrimitive();
            if (primitive.isBoolean()) {
                return InspectField.flag(label);
            }
            if (primitive.isNumber()) {
                double value = primitive.getAsDouble();
                return value == Math.floor(value)
                        ? InspectField.integer(label, Integer.MIN_VALUE, Integer.MAX_VALUE)
                        : InspectField.decimal(label, -1.0E9, 1.0E9);
            }
        }
        return InspectField.text(label);
    }

    /**
     * The kind the format declares for the quest-level fields, and for the per-type fields whose names
     * carry it. Bounds here are generous -- the loader's own validator is what refuses an impossible
     * value, on the server, with its own message.
     */
    private static InspectField<?> declared(String path, String label) {
        if (path.equals("x") || path.equals("y") || path.equals("size")
                || path.equals("repeatCooldownTicks") || path.equals("minRequired")
                || path.equals("minWidth")
                || path.endsWith(".count") || path.endsWith(".amount")
                || path.endsWith(".autoSubmitTicks")) {
            return InspectField.integer(label, -1000000, 1000000);
        }
        if (path.equals("iconScale")) {
            return InspectField.decimal(label, 0.25, 8.0);
        }
        if (path.equals("showTitle") || path.equals("repeatable") || path.equals("sequentialTasks")
                || path.equals("invisible") || path.equals("hideDependentLines")
                || path.equals("disableToast") || path.endsWith(".disableToast")
                || path.equals("disableRecipeMod") || path.equals("hideLockIcon")
                || path.equals("ignoreRewardBlocking") || path.equals("requiresStageTeam")
                || path.endsWith(".consumeItems")
                || path.endsWith(".optional")) {
            return InspectField.flag(label);
        }
        return null;
    }

    /**
     * The field labels this build names, by the path shape the panel asks with.
     *
     * <p>Keyed by the path with its indices and list members dropped -- {@code "tasks.0.icon.item"} and
     * {@code "icon.item"} are the same row -- and the value is a key, so the English lives in en_us like
     * every other label. A path the table does not name is still labelled, by {@link #derivedLabel}: an
     * addon's field must not draw as a bare path, and the derived words are the honest fallback for a
     * field this build has never heard of -- the same answer the picker gives an unknown type.
     */
    private static final Map<String, String> FIELD_LABELS = Map.ofEntries(
            Map.entry("advancement", "tenet.dev.field.advancement"),
            Map.entry("aliases", "tenet.dev.field.aliases"),
            Map.entry("amount", "tenet.dev.field.amount"),
            Map.entry("auto", "tenet.dev.field.auto"),
            Map.entry("autoSubmitTicks", "tenet.dev.field.auto_submit_ticks"),
            Map.entry("biome", "tenet.dev.field.biome"),
            Map.entry("command", "tenet.dev.field.command"),
            Map.entry("consumeItems", "tenet.dev.field.consume_items"),
            Map.entry("count", "tenet.dev.field.count"),
            Map.entry("criterion", "tenet.dev.field.criterion"),
            Map.entry("customName", "tenet.dev.field.custom_name"),
            Map.entry("description", "tenet.dev.field.description"),
            Map.entry("dimension", "tenet.dev.field.dimension"),
            Map.entry("disableRecipeMod", "tenet.dev.field.disable_recipe_mod"),
            Map.entry("disableToast", "tenet.dev.field.disable_toast"),
            Map.entry("entity", "tenet.dev.field.entity"),
            Map.entry("entityTypeTag", "tenet.dev.field.entity_type_tag"),
            Map.entry("excludeFromClaimAll", "tenet.dev.field.exclude_from_claim_all"),
            Map.entry("exclusiveGroup", "tenet.dev.field.exclusive_group"),
            Map.entry("filter", "tenet.dev.field.filter"),
            Map.entry("fluid", "tenet.dev.field.fluid"),
            Map.entry("guidePage", "tenet.dev.field.guide_page"),
            Map.entry("icon.item", "tenet.dev.field.icon_item"),
            Map.entry("icon.texture", "tenet.dev.field.icon_texture"),
            Map.entry("icon.sprite", "tenet.dev.field.icon_sprite"),
            Map.entry("icon.entity", "tenet.dev.field.icon_entity"),
            Map.entry("iconScale", "tenet.dev.field.icon_scale"),
            Map.entry("id", "tenet.dev.field.id"),
            Map.entry("ignoreDimension", "tenet.dev.field.ignore_dimension"),
            Map.entry("ignoreRewardBlocking", "tenet.dev.field.ignore_reward_blocking"),
            Map.entry("inline", "tenet.dev.field.inline"),
            Map.entry("invisible", "tenet.dev.field.invisible"),
            Map.entry("item", "tenet.dev.field.item"),
            Map.entry("levels", "tenet.dev.field.levels"),
            Map.entry("manualOnly", "tenet.dev.field.manual_only"),
            Map.entry("match", "tenet.dev.field.match"),
            Map.entry("maxInput", "tenet.dev.field.max_input"),
            Map.entry("min", "tenet.dev.field.min"),
            Map.entry("minRequired", "tenet.dev.field.min_required"),
            Map.entry("nbtFilter", "tenet.dev.field.nbt_filter"),
            Map.entry("objective", "tenet.dev.field.objective"),
            Map.entry("observeType", "tenet.dev.field.observe_type"),
            Map.entry("onlyFromCrafting", "tenet.dev.field.only_from_crafting"),
            Map.entry("onlyOne", "tenet.dev.field.only_one"),
            Map.entry("optional", "tenet.dev.field.optional"),
            Map.entry("permissionLevel", "tenet.dev.field.permission_level"),
            Map.entry("points", "tenet.dev.field.points"),
            Map.entry("position", "tenet.dev.field.position"),
            Map.entry("prerequisiteMode", "tenet.dev.field.prerequisite_mode"),
            Map.entry("randomBonus", "tenet.dev.field.random_bonus"),
            Map.entry("remove", "tenet.dev.field.remove"),
            Map.entry("repeatCooldownTicks", "tenet.dev.field.repeat_cooldown_ticks"),
            Map.entry("repeatable", "tenet.dev.field.repeatable"),
            Map.entry("requiresStage", "tenet.dev.field.requires_stage"),
            Map.entry("requiresStageTeam", "tenet.dev.field.requires_stage_team"),
            Map.entry("sequentialTasks", "tenet.dev.field.sequential_tasks"),
            Map.entry("shape", "tenet.dev.field.shape"),
            Map.entry("showTitle", "tenet.dev.field.show_title"),
            Map.entry("silent", "tenet.dev.field.silent"),
            Map.entry("size", "tenet.dev.field.size"),
            Map.entry("stage", "tenet.dev.field.stage"),
            Map.entry("stat", "tenet.dev.field.stat"),
            Map.entry("structure", "tenet.dev.field.structure"),
            Map.entry("subtitle", "tenet.dev.field.subtitle"),
            Map.entry("table", "tenet.dev.field.table"),
            Map.entry("tag", "tenet.dev.field.tag"),
            Map.entry("tags", "tenet.dev.field.tags"),
            Map.entry("timer", "tenet.dev.field.timer"),
            Map.entry("title", "tenet.dev.field.title"),
            Map.entry("toObserve", "tenet.dev.field.to_observe"),
            Map.entry("value", "tenet.dev.field.value"),
            Map.entry("x", "tenet.dev.field.x"),
            Map.entry("y", "tenet.dev.field.y"));

    /**
     * A row's label, from its path: the table's key for a field this build names, and the derived words
     * otherwise.
     */
    public static String labelFor(String path) {
        String shape = shapeOf(path);
        String key = FIELD_LABELS.get(shape);
        return key != null ? Labels.of(key) : derivedLabel(shape);
    }

    /** The path without its indices or list members: {@code "tasks.0.icon.item"} -> {@code "icon.item"}. */
    private static String shapeOf(String path) {
        List<String> parts = new ArrayList<>();
        for (String step : path.split("\\.")) {
            if (!step.matches("\\d+") && !step.equals("tasks") && !step.equals("rewards")) {
                parts.add(step);
            }
        }
        return String.join(".", parts);
    }

    /** The words a field's own name makes, for a field this build's table does not name. */
    private static String derivedLabel(String path) {
        List<String> parts = new ArrayList<>(List.of(path.split("\\.")));
        parts.removeIf(String::isEmpty);
        if (parts.isEmpty()) {
            return path;
        }
        StringBuilder out = new StringBuilder();
        for (String word : parts.get(parts.size() - 1).split("(?=[A-Z])|_")) {
            if (word.isEmpty()) {
                continue;
            }
            if (out.length() > 0) {
                out.append(' ');
            }
            out.append(Character.toUpperCase(word.charAt(0)))
                    .append(word.substring(1).toLowerCase(Locale.ROOT));
        }
        // The leaf is the field's own name; the container before it is said whole, so that "Icon Item"
        // and not "Icon" -- a label of the container alone would name the section twice.
        if (parts.size() >= 2) {
            out.insert(0, capitalised(parts.get(parts.size() - 2)) + " ");
        }
        return out.toString();
    }

    private static String capitalised(String word) {
        String[] parts = word.split("(?=[A-Z])|_");
        StringBuilder out = new StringBuilder();
        for (String part : parts) {
            if (part.isEmpty()) {
                continue;
            }
            if (out.length() > 0) {
                out.append(' ');
            }
            out.append(Character.toUpperCase(part.charAt(0)))
                    .append(part.substring(1).toLowerCase(Locale.ROOT));
        }
        return out.toString();
    }

    // ------------------------------------------------------------------
    // The row factories, which read the tree to say what is there
    // ------------------------------------------------------------------

    private static InspectRow field(JsonObject quest, String path) {
        return InspectRow.field(path, labelFor(path), displayValue(quest, path));
    }

    private static InspectRow field(JsonObject quest, String path, String label) {
        return InspectRow.field(path, label, displayValue(quest, path));
    }

    private static InspectRow toggle(JsonObject quest, String path) {
        boolean on = quest.has(path) && quest.get(path).isJsonPrimitive()
                && quest.get(path).getAsJsonPrimitive().isBoolean()
                && quest.get(path).getAsBoolean();
        return InspectRow.toggle(path, labelFor(path) + (on ? " \u00b7 on" : " \u00b7 off"));
    }

    /** A field's value as the panel shows it, with absent said rather than implied. */
    private static String displayValue(JsonObject quest, String path) {
        JsonElement found = get(quest, path);
        if (found == null) {
            return "";
        }
        if (found.isJsonPrimitive()) {
            return found.getAsString();
        }
        return found.toString();
    }

    private static String text(JsonObject object, String member, String fallback) {
        return object.has(member) && object.get(member).isJsonPrimitive()
                ? object.get(member).getAsString() : fallback;
    }

    /** The strings of an array at a top-level member of the quest's tree. Absent is empty. */
    public static List<String> strings(JsonObject object, String member) {
        List<String> out = new ArrayList<>();
        // Null-tolerant, like `get`: the settings page builds its rows from the replica, and a replica
        // that has not arrived yet is a real state rather than a caller's mistake.
        if (object != null && object.has(member) && object.get(member).isJsonArray()) {
            for (JsonElement element : object.getAsJsonArray(member)) {
                if (element.isJsonPrimitive()) {
                    out.add(element.getAsString());
                }
            }
        }
        return List.copyOf(out);
    }

    private static int paragraphs(JsonObject quest) {
        JsonElement description = quest.get("description");
        if (description == null || !description.isJsonArray()) {
            return description == null ? 0 : 1;
        }
        return description.getAsJsonArray().size();
    }

    // ------------------------------------------------------------------
    // Reading a tree, the one way the panel does it
    // ------------------------------------------------------------------

    /**
     * An element at a dotted path of the quest's tree, or null.
     *
     * <p>The panel's own read, not the model's: the replica is a plain {@link JsonObject}, and importing
     * {@code JsonFile} to walk it would be opening a file that is not there. The array steps are the
     * same shape the model writes, so a path this read answers is a path a commit can go back through.
     */
    public static JsonElement get(JsonObject root, String path) {
        JsonElement at = root;
        for (String step : path.split("\\.")) {
            if (at == null) {
                return null;
            }
            if (at.isJsonObject()) {
                at = at.getAsJsonObject().get(step);
            }
            else if (at.isJsonArray()) {
                int index;
                try {
                    index = Integer.parseInt(step);
                }
                catch (NumberFormatException notAnIndex) {
                    return null;
                }
                var array = at.getAsJsonArray();
                at = index < 0 || index >= array.size() ? null : array.get(index);
            }
            else {
                return null;
            }
        }
        return at;
    }

    /**
     * A boolean field, false for anything else — absent, null, or the wrong type.
     *
     * <p>Shared by the quest page's panel and its layout, and that is the point: the layout picks the
     * label a switch row carries from the same read the panel draws the knob with, so the word and the
     * knob cannot disagree about the state.
     */
    public static boolean flag(JsonObject root, String path) {
        JsonElement value = root == null ? null : get(root, path);
        return value != null && value.isJsonPrimitive() && value.getAsJsonPrimitive().isBoolean()
                && value.getAsBoolean();
    }
}
