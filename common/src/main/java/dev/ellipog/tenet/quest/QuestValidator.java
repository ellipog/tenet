package dev.ellipog.tenet.quest;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.mojang.serialization.DynamicOps;
import com.mojang.serialization.JsonOps;
import com.mojang.serialization.MapCodec;
import dev.ellipog.armature.api.data.Checks;
import dev.ellipog.armature.api.data.JsonDocument;
import dev.ellipog.armature.api.data.Problems;
import dev.ellipog.armature.client.ui.ThemePatch;
import dev.ellipog.tenet.quest.condition.ConditionTypes;
import dev.ellipog.tenet.quest.reward.RewardAutoClaim;
import dev.ellipog.tenet.quest.reward.RewardTypes;
import dev.ellipog.tenet.quest.task.BreakTask;
import dev.ellipog.tenet.quest.task.TaskTypes;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;

import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
/**
 * Checks one quest file against the format, reporting everything wrong with it.
 *
 * <h2>Why this exists when Minecraft has codecs</h2>
 *
 * <p>Codecs turn JSON into objects, and are excellent at it. What they cannot do is say <i>where</i>
 * the problem is, and they ignore fields they do not recognise. Both of those matter enormously for
 * a format people edit by hand:
 *
 * <ul>
 *   <li>{@code "titl": "Punch a Tree"} produces a quest with a blank title, no error, and nothing in
 *       any log. The author compares two files character by character to find it.</li>
 *   <li>A codec failure reports {@code Missing field "title"} and a file name — not the line.</li>
 * </ul>
 *
 * <p>This walks the same file <b>before</b> the codec runs, using the same field sets the records
 * declare, so the two cannot drift: if a record gains a field, this allows it in the same commit.
 * A file with a structural error is not decoded at all, which is why one mistake produces one
 * message rather than a validator complaint followed by a codec complaint about the same thing.
 *
 * <h2>What it checks</h2>
 *
 * <p>Structure and values: every required field present, every field a known one, every enum name
 * valid, every id well-formed, every item that actually exists. What it deliberately does <b>not</b>
 * check is anything needing more than this file — duplicate ids across files, dependencies that point
 * at nothing. That is {@link QuestIndex}, because a file on its own cannot know.
 */
public final class QuestValidator {

    // ------------------------------------------------------------------
    // Field sets. Each mirrors a record's declared fields.
    // ------------------------------------------------------------------

    private static final Set<String> ROOT_FIELDS = Set.of("$schema", "version", "chapterGroups");

    /**
     * The field sets, <b>taken from the records that declare the fields</b>.
     *
     * <p>These used to be copies written out here, under a comment claiming they "mirror a record's
     * declared fields" — which is a description of an intention rather than a mechanism. Adding
     * {@code collapsedByDefault} to {@code ChapterGroup} made the cost concrete: it is a two-file edit
     * where forgetting the second half produces a field the <b>codec reads and the validator calls
     * unknown</b>, so the file loads with checking disabled and is refused with it on. The author's only
     * clue would be which build they had.
     *
     * <p>So the records own their sets, and a version-1 inline group and a version-2 {@code group.json}
     * allow the same fields by construction rather than by two lists agreeing. Version 1 and version 2
     * describe <i>the same objects</i>; only the file layout differs, so the fields a group may carry
     * cannot depend on which format it was written in.
     */
    private static final Set<String> GROUP_FIELDS = ChapterGroup.FIELDS;

    private static final Set<String> CHAPTER_FIELDS = Chapter.FIELDS;

    /**
     * What a version-2 per-kind file may hold at its root: the kind's own fields, plus {@code $schema}.
     *
     * <p>And deliberately <b>no {@code version}</b>. A per-kind file cannot be anything but version 2 —
     * the folder layout settles it, the same way a flat {@code .json} at the root is version 1 by
     * position rather than by the number inside it — so a version field would be a second answer to a
     * question that is already answered, and a second answer is a thing that can disagree.
     */
    private static final Set<String> GROUP_DOCUMENT_FIELDS = withSchema(GROUP_FIELDS);

    private static final Set<String> CHAPTER_DOCUMENT_FIELDS = withSchema(CHAPTER_FIELDS);

    /**
     * A quest's own fields, plus the layout's, the rules' and the presentation's.
     *
     * <p>{@link QuestRules} holds sixteen of these — the repeat flags, the reveal flags, the five hide
     * flags, {@code invisibleUntilTasks}, {@code requiresStage} and {@code autoClaim}, listed in
     * {@link QuestRules#FIELDS} — because {@code RecordCodecBuilder} caps out at sixteen components and
     * the flat quest was over it. {@link QuestPresentation} holds nine more — {@code minWidth},
     * {@code hideDependentLines}, {@code disableToast}, {@code ignoreRewardBlocking},
     * {@code requiresStageTeam}, {@code disableRecipeMod}, {@code hideLockIcon}, {@code tags}
     * and {@code guidePage} — for the same
     * reason, listed in its own {@code FIELDS}. They are flat
     * in JSON regardless; the grouping is only visible in Java.
     */
    private static final Set<String> QUEST_FIELDS = union(
            union(Set.of(
                    "id", "title", "subtitle", "description", "icon", "aliases", "dependsOn",
                    "dependencyLines", "prerequisiteMode", "minRequired", "flexibleProgress",
                    "tasks", "rewards"),
                    QuestLayout.FIELDS),
            union(QuestRules.FIELDS, QuestPresentation.FIELDS));

    /** Every type currently registered, for the fallback when a task's own type is unknown. */
    private static Set<String> allTaskFields() {
        // Remembered by registered-type count rather than rebuilt per task: the union is the
        // allowed set for every task's field check, so a pack of hundreds pays hundreds of walks
        // over every type's fields per load. The registries are append-only — registering an id
        // twice throws — so a count names a set, and a newly registered type misses once and is
        // kept. The sets are only ever unioned, never mutated; see union.
        int count = TaskTypes.count();
        if (taskFieldsMemo == null || count != taskFieldsCount) {
            Set<String> fields = new LinkedHashSet<>();
            for (Identifier id : TaskTypes.ids()) {
                fields.addAll(TaskTypes.fieldsOf(id));
            }
            taskFieldsMemo = fields;
            taskFieldsCount = count;
        }
        return taskFieldsMemo;
    }

    /** What {@link #allTaskFields} was built from. See there for why a count is the key. */
    private static int taskFieldsCount = -1;

    /** The remembered union. See {@link #allTaskFields}. */
    private static Set<String> taskFieldsMemo;

    private static Set<String> allRewardFields() {
        int count = RewardTypes.count();
        if (rewardFieldsMemo == null || count != rewardFieldsCount) {
            Set<String> fields = new LinkedHashSet<>();
            for (Identifier id : RewardTypes.ids()) {
                fields.addAll(RewardTypes.fieldsOf(id));
            }
            rewardFieldsMemo = fields;
            rewardFieldsCount = count;
        }
        return rewardFieldsMemo;
    }

    /** What {@link #allRewardFields} was built from. See {@link #allTaskFields}. */
    private static int rewardFieldsCount = -1;

    /** The remembered union. See {@link #allTaskFields}. */
    private static Set<String> rewardFieldsMemo;

    /**
     * Every condition type's own fields, for the fallback when a condition's type is unknown.
     *
     * <p>A third union, because a condition is a third registry: its field names live one level below a
     * task's, where the task-level union cannot reach them, and a condition switched from one type to
     * another must not have the old type's fields reported as unknown — the same reasoning the task and
     * reward unions give.
     */
    private static Set<String> allConditionFields() {
        int count = ConditionTypes.count();
        if (conditionFieldsMemo == null || count != conditionFieldsCount) {
            Set<String> fields = new LinkedHashSet<>();
            for (Identifier id : ConditionTypes.ids()) {
                fields.addAll(ConditionTypes.fieldsOf(id));
            }
            conditionFieldsMemo = fields;
            conditionFieldsCount = count;
        }
        return conditionFieldsMemo;
    }

    /** What {@link #allConditionFields} was built from. See {@link #allTaskFields}. */
    private static int conditionFieldsCount = -1;

    /** The remembered union. See {@link #allTaskFields}. */
    private static Set<String> conditionFieldsMemo;

    private QuestValidator() {
    }

    /**
     * Validates a version-2 document that is one whole chapter group.
     *
     * <p>The version-2 counterpart of {@link #validate}, and the difference is entirely in where things
     * are: a version-1 file holds {@code chapterGroups[]} and the group is at {@code $.chapterGroups[0]},
     * while a {@code group.json} <i>is</i> the group and its fields are at {@code $}. The checks are the
     * same checks at a different path, which is why they are shared rather than written twice.
     */
    /**
     * One {@code reward_tables/*.json}.
     *
     * <p>Like a chapter: its own keys, then each entry — and each entry's reward is checked by the
     * same {@link #checkReward} a quest's rewards go through, so a table entry cannot be a shape a
     * quest reward could not be. The whole document's codec verdict is the loader's decode step, the
     * same split every other file kind uses.
     */
    public static void validateRewardTableDocument(JsonDocument document, Problems problems) {
        validateRewardTableDocument(document, problems, com.mojang.serialization.JsonOps.INSTANCE);
    }

    /**
     * Validates one reward-table document, decoding codecs against registries.
     *
     * <p>Registry-backed fields (data components, holders, tags) only decode against a registry
     * context: the plain-JSON form cannot see them. Callers without a server use
     * {@link #validateRewardTableDocument(JsonDocument, Problems)} and accept that such fields
     * cannot be judged there.
     */
    public static void validateRewardTableDocument(JsonDocument document, Problems problems,
                                                   com.mojang.serialization.DynamicOps<com.google.gson.JsonElement> ops) {
        if (!isObject(document, "$", problems)) {
            return;
        }
        warnRetiredTableFields(document, "$", problems, false);
        Checks.rejectUnknown(document, "$", union(withSchema(dev.ellipog.tenet.quest.loot.RewardTable.FIELDS),
                RETIRED_TABLE_FIELDS), problems);
        checkTableBounds(document, problems);
        // Table presentation: whether a reward row draws this table's title, and whether it draws
        // an item tooltip. Both are display-only — a typo here changes what the player reads, not
        // whether the file loads — so each is a closed-set check.
        if (document.has("$.useTitle")) {
            Checks.optionalBool(document, "$.useTitle", problems);
        }
        if (document.has("$.hideTooltip")) {
            Checks.optionalBool(document, "$.hideTooltip", problems);
        }
        var entries = Checks.array(document, "$.entries", problems);
        if (entries == null) {
            return;
        }
        if (entries.isEmpty()) {
            // A warning rather than an error: a table an author has made and not filled in yet is a
            // placeholder, the same way a file with no chapterGroups is. It is still worth saying,
            // because a roll of it grants nothing and the file looks finished.
            problems.warn(document, "$.entries", "this table has no entries: a roll of it grants nothing");
        }
        for (int i = 0; i < entries.size(); i++) {
            String path = "$.entries[" + i + "]";
            if (!isObject(document, path, problems)) {
                continue;
            }
            Checks.rejectUnknown(document, path,
                    dev.ellipog.tenet.quest.loot.RewardTable.Entry.FIELDS, problems);
            checkTableEntryReward(document, path + ".reward", problems, ops);
        }
    }

    /**
     * The two numbers a table carries, against the bounds the roll and the codec enforce.
     *
     * <h2>Why {@code emptyWeight} has two severities and not one</h2>
     *
     * <p>A weight that is not a finite number is an <b>error</b>, because it breaks the arithmetic rather
     * than the author's intent: every comparison against an infinity or a NaN is false, so no weighted
     * throw ever lands and the table silently grants only its guaranteed entries — a table that looks
     * weighted and pays like an unweighted one. It arrives as a number that overflowed, not as the
     * literal {@code Infinity}: this project's parser refuses that spelling, along with {@code NaN},
     * before a validator ever sees the file, which is the right place for that refusal.
     *
     * <p>A finite negative is a <b>warning</b>: the roll reads it as zero ({@code RewardTable.rollIndices}
     * clamps), which is a defined answer, and it is the behaviour every file with one already gets — so
     * refusing the file would turn a working pack into one that does not load, over a number the loader
     * can already read. The split is on "does the arithmetic still mean something", not on tidiness.
     *
     * <p>A value that is not a number at all is left to the codec, which reports it in its own words at
     * the same path: one mistake, one message.
     */
    private static void checkTableBounds(JsonDocument document, Problems problems) {
        var weight = document.get("$.emptyWeight").orElse(null);
        if (weight != null && weight.isJsonPrimitive() && weight.getAsJsonPrimitive().isNumber()) {
            double value = weight.getAsDouble();
            if (!Double.isFinite(value)) {
                problems.error(document, "$.emptyWeight",
                        "emptyWeight is " + value + ", which is not a number a roll can use: every "
                                + "weighted throw would compare false, so this table would grant its "
                                + "guaranteed entries and nothing else. Set it to 0 for no empty band.");
            }
            else if (value < 0) {
                problems.warn(document, "$.emptyWeight",
                        "emptyWeight is negative, and a negative weight is read as 0 - so this table "
                                + "has no empty band");
            }
        }
        Checks.optionalInt(document, "$.lootSize", problems).ifPresent(size -> {
            if (size < dev.ellipog.tenet.quest.loot.RewardTable.LOOT_SIZE_MIN
                    || size > dev.ellipog.tenet.quest.loot.RewardTable.LOOT_SIZE_MAX) {
                problems.error(document, "$.lootSize", "lootSize must be between "
                        + dev.ellipog.tenet.quest.loot.RewardTable.LOOT_SIZE_MIN + " and "
                        + dev.ellipog.tenet.quest.loot.RewardTable.LOOT_SIZE_MAX + ", found " + size);
            }
        });
    }

    public static void validateGroupDocument(JsonDocument document, Problems problems) {
        validateGroupDocument(document, problems, com.mojang.serialization.JsonOps.INSTANCE);
    }

    /** Validates a version-2 group document, decoding codecs against registries. */
    public static void validateGroupDocument(JsonDocument document, Problems problems,
                                             com.mojang.serialization.DynamicOps<com.google.gson.JsonElement> ops) {
        validateGroupAt(document, "$", problems, ops, false, GROUP_DOCUMENT_FIELDS);
    }

    /** Validates a version-2 document that is one whole chapter. */
    public static void validateChapterDocument(JsonDocument document, Problems problems) {
        validateChapterDocument(document, problems, com.mojang.serialization.JsonOps.INSTANCE);
    }

    /** Validates a version-2 chapter document, decoding codecs against registries. */
    public static void validateChapterDocument(JsonDocument document, Problems problems,
                                               com.mojang.serialization.DynamicOps<com.google.gson.JsonElement> ops) {
        validateChapterAt(document, "$", problems, ops, false, CHAPTER_DOCUMENT_FIELDS);
    }

    /**
     * Validates a version-2 document that is one whole quest.
     *
     * <p>No "inline children" distinction to make: a quest has no children, so its field check is the
     * same one version 1 uses, at a different root.
     */
    public static void validateQuestDocument(JsonDocument document, Problems problems) {
        validateQuestDocument(document, problems, com.mojang.serialization.JsonOps.INSTANCE);
    }

    /** Validates a version-2 quest document, decoding codecs against registries. */
    public static void validateQuestDocument(JsonDocument document, Problems problems,
                                             com.mojang.serialization.DynamicOps<com.google.gson.JsonElement> ops) {
        validateQuestAt(document, "$", problems, ops, withSchema(QUEST_FIELDS));
    }

    /**
     * Validates one version-1 file. Reports into {@code problems}, which may already hold problems from
     * other files — the caller decides whether to stop after one file or keep going.
     */
    public static void validate(JsonDocument document, Problems problems) {
        validate(document, problems, com.mojang.serialization.JsonOps.INSTANCE);
    }

    /**
     * Validates one version-1 file, decoding codecs against registries.
     *
     * <p>Reports into {@code problems}, which may already hold problems from
     * other files — the caller decides whether to stop after one file or keep going.
     */
    public static void validate(JsonDocument document, Problems problems,
                                com.mojang.serialization.DynamicOps<com.google.gson.JsonElement> ops) {
        Checks.rejectUnknown(document, "$", ROOT_FIELDS, problems);

        Checks.optionalInt(document, "$.version", problems).ifPresent(version -> {
            if (version < 1) {
                problems.error(document, "$.version", "version must be at least 1, found " + version);
            } else if (version > QuestFile.CURRENT_VERSION) {
                // A WARNING rather than an error, and the reason is the decode: this number is not what
                // decides whether the file can be read, so refusing on it refuses files that read fine.
                // The case it was costing is a newer build that added a *task type* and bumped the
                // version -- the dispatch now decodes that node to an unknown-task placeholder and the
                // rest of the file loads, so the version alone should not be what stops it.
                //
                // It is still reported loudly, because it is the one signal that an author is looking at
                // a file from a build they do not have. What it does not do is claim more than it can:
                // a field this build has never heard of is still an error below, so a file that only
                // *adds a field to a known type* is refused there and not here.
                problems.warn(document, "$.version", "this file is version " + version
                        + ", but this build understands at most " + QuestFile.CURRENT_VERSION
                        + ". It was probably written by a newer version of Tenet; it is read as version "
                        + QuestFile.CURRENT_VERSION + ", and anything this build cannot read is reported"
                        + " below.");
            }
        });

        if (!document.has("$.chapterGroups")) {
            // Not an error. A file with no chapter groups is a legitimate placeholder — an author
            // who has made the file and not started it.
            problems.warn(document, "$", "no \"chapterGroups\" - the file contributes no quests");
            return;
        }

        var groups = Checks.array(document, "$.chapterGroups", problems);
        if (groups == null) {
            return;
        }
        for (int g = 0; g < groups.size(); g++) {
            validateGroup(document, g, problems, ops);
        }
    }

    /** A version-1 group, where the whole tree is one document. */
    private static void validateGroup(JsonDocument document, int g, Problems problems,
                                      DynamicOps<JsonElement> ops) {
        validateGroupAt(document, groupPath(g), problems, ops, true, GROUP_FIELDS);
    }

    /**
     * A group's own fields, at whatever path it sits at, plus its child list in whichever shape applies.
     *
     * <h2>The allowed set is a parameter, and that is the fix for a real duplicate</h2>
     *
     * <p>The unknown-field check is the one thing the two layouts genuinely disagree about: a version-1
     * group sits at {@code $.chapterGroups[0]} inside a file that also holds {@code version}, while a
     * {@code group.json} is the group and its whole document is the object — so it may carry
     * {@code $schema} and may not carry {@code version}. Everything else about a group is the same
     * either way, which is why the body is shared.
     *
     * <p>It was not, at first. The check ran in the wrapper <i>and</i> in this method, at the same path
     * with two different sets — so {@code $schema} was allowed by the outer call and refused by the
     * inner one, and a perfectly good {@code group.json} reported "unknown field $schema" with a field
     * list that plainly contained it. Two descriptions of one field set, one level apart, disagreeing:
     * the same fault this file's own class comment is about, committed inside the fix for it.
     *
     * @param inlineChildren true for version 1, where {@code chapters} holds chapter <i>objects</i>;
     *                       false for version 2, where it holds the <b>names</b> of chapter folders
     * @param allowedFields  what this level may hold. The caller knows whether it is at a file root.
     */
    private static void validateGroupAt(JsonDocument document, String path, Problems problems,
                                        DynamicOps<JsonElement> ops,
                                        boolean inlineChildren, Set<String> allowedFields) {
        if (!isObject(document, path, problems)) {
            return;
        }
        warnRetiredVersion(document, path, problems);
        Checks.rejectUnknown(document, path, union(allowedFields, RETIRED_VERSION), problems);
        Checks.id(document, path + ".id", problems);
        requiredText(document, path + ".title", problems);
        // Checked, and it was not until the codec rejected one. A field the validator does not look at
        // is a field whose mistakes arrive as a codec message at line 1 column 1 — which is exactly what
        // happened to the first group description written as a list of lines.
        //
        // `checkTextOrList`, not `checkTextList`, because a group's description is one of the three
        // whose codec takes either shape -- see the method. Using the list-only check here was the
        // validator being *stricter than the format*: a bare string decoded fine and was then failed by
        // the validator, so the same file loaded with the check disabled and refused with it on. The
        // author's only clue would have been which build they had.
        checkTextOrList(document, path + ".description", problems);
        Checks.optionalStringList(document, path + ".aliases", problems).forEach(alias ->
                checkAlias(document, path + ".aliases", alias, problems));
        checkTags(document, path + ".tags", problems);
        if (document.has(path + ".collapsedByDefault")) {
            Checks.optionalBool(document, path + ".collapsedByDefault", problems);
        }
        // The group's icon, on the same terms as a chapter's: optional, and when present an item that
        // resolves. A typo or a missing mod is reported here rather than as a sidebar row that silently
        // draws nothing -- and it is validated at all because the editor writes this field.
        checkIcon(document, path + ".icon", problems, ops);

        if (!document.has(path + ".chapters")) {
            problems.warn(document, path, "no \"chapters\" - this chapter group is empty");
            return;
        }
        if (inlineChildren) {
            var chapters = Checks.array(document, path + ".chapters", problems);
            if (chapters == null) {
                return;
            }
            for (int c = 0; c < chapters.size(); c++) {
                validateChapterAt(document, path + ".chapters[" + c + "]", problems, ops, true, CHAPTER_FIELDS);
            }
        }
        else {
            checkNameList(document, path + ".chapters", "chapter", problems);
        }
    }

    /**
     * A manifest's list of child names: an array of single path segments, in order.
     *
     * <h2>Shape here, resolution at load, and one message either way</h2>
     *
     * <p>This checks that the list is an array of strings and says so when it is not. It deliberately
     * does <b>not</b> check that a name is a bare segment, or that the thing it names exists, or that the
     * folder it resolves against holds nothing unlisted. All three are {@link QuestFiles}' business — it
     * is the step that holds the folder the names resolve against — and it reports each of them with a
     * message that names the declared string and the path it failed to resolve to.
     *
     * <p>Doing any of it here as well would mean one missing chapter producing two messages about the
     * same absence, which is the fault {@link #validate} exists to avoid: the whole reason a file with a
     * structural error is not decoded is so that one mistake produces one message.
     *
     * <p>The <i>message</i> is worth as much as the check, and it names the format change rather than
     * describing a type error. {@code "chapters": [{"id": "one"}]} is a real thing to write — an element
     * copied straight out of a version-1 file — and "expected a string, found an object" would leave the
     * author looking at a field that looks right to them.
     */
    private static void checkNameList(JsonDocument document, String path, String what, Problems problems) {
        var names = Checks.array(document, path, problems);
        if (names == null) {
            return;
        }
        if (names.isEmpty()) {
            problems.warn(document, path, "this " + what + " list is empty");
            return;
        }
        for (int i = 0; i < names.size(); i++) {
            JsonElement child = names.get(i);
            if (child.isJsonPrimitive() && child.getAsJsonPrimitive().isString()) {
                continue;
            }
            problems.error(document, path + "[" + i + "]", "expected the name of a " + what
                    + " as a string, found " + Checks.kindOf(child)
                    + " - a manifest lists the names of the folders and files sitting beside it, in the"
                    + " order they should appear. This is the version-2 layout: what was nested inline"
                    + " is now a folder of its own, named here.");
        }
    }

    /** A version-1 chapter, where the whole tree is one document. */
    private static void validateChapter(JsonDocument document, int g, int c, Problems problems,
                                        DynamicOps<JsonElement> ops) {
        validateChapterAt(document, chapterPath(g, c), problems, ops, true, CHAPTER_FIELDS);
    }

    /**
     * A chapter's own fields, at whatever path it sits at, plus its quest list in whichever shape.
     *
     * @param inlineChildren true for version 1, where {@code quests} holds quest <i>objects</i>; false
     *                       for version 2, where it holds the <b>file names</b> of quests. The order of
     *                       that list is the progression of a {@code LINEAR} chapter, so it is the one
     *                       list in the format whose order is load-bearing rather than presentational.
     * @param allowedFields  what this level may hold. See {@link #validateGroupAt} for why this is a
     *                       parameter rather than a constant read here.
     */
    private static void validateChapterAt(JsonDocument document, String path, Problems problems,
                                          DynamicOps<JsonElement> ops,
                                          boolean inlineChildren, Set<String> allowedFields) {
        if (!isObject(document, path, problems)) {
            return;
        }
        warnRetiredVersion(document, path, problems);
        Checks.rejectUnknown(document, path, union(allowedFields, RETIRED_VERSION), problems);
        Checks.id(document, path + ".id", problems);
        requiredText(document, path + ".title", problems);
        // A chapter's description was the one text field nothing looked at, and the two layouts' chapter
        // codecs do not agree about its shape: the folder format's takes a list or one bare string (like
        // a group's), while the version-1 chapter's takes a list only. So each is checked with the one
        // that matches the codec it will meet -- a bare string in a version-1 file decoded as an error
        // at line 1 column 1 before this, and a list is what that codec wants.
        if (inlineChildren) {
            checkTextList(document, path + ".description", problems);
        }
        else {
            checkTextOrList(document, path + ".description", problems);
        }
        checkIcon(document, path + ".icon", problems, ops);
        Checks.optionalStringList(document, path + ".aliases", problems).forEach(alias ->
                checkAlias(document, path + ".aliases", alias, problems));
        checkTags(document, path + ".tags", problems);

        // The codec defaults this, so its absence is not a problem — but a misspelled value is, and
        // that is the case an author actually hits.
        if (document.has(path + ".defaultPrerequisiteMode")) {
            Checks.optionalString(document, path + ".defaultPrerequisiteMode", problems)
                    .ifPresent(name -> checkEnum(document, path + ".defaultPrerequisiteMode", name,
                            PrerequisiteMode.class, problems));
        }
        if (document.has(path + ".progressionMode")) {
            Checks.optionalString(document, path + ".progressionMode", problems)
                    .ifPresent(name -> checkEnum(document, path + ".progressionMode", name,
                            ProgressionMode.class, problems));
        }
        if (document.has(path + ".defaultConsumeItems")) {
            Checks.optionalBool(document, path + ".defaultConsumeItems", problems);
        }
        // The chapter default for early task progress: a quest that says nothing about
        // flexibleProgress follows this. Either true makes the quest flexible.
        if (document.has(path + ".defaultFlexibleProgress")) {
            Checks.optionalBool(document, path + ".defaultFlexibleProgress", problems);
        }
        // The chapter rung of the auto-claim ladder, on the chapter walk. Checked like every other
        // closed set, so a typo is reported here rather than silently deferring to the pack setting.
        if (document.has(path + ".autoClaim")) {
            Checks.optionalString(document, path + ".autoClaim", problems)
                    .ifPresent(name -> checkEnum(document, path + ".autoClaim", name,
                            dev.ellipog.tenet.quest.reward.RewardAutoClaim.class, problems));
        }
        checkDependencyStyle(document, path + ".dependencyStyle", problems, false);

        // The chapter's own gate, checked field by field the way a quest's is -- a chapter is a file like
        // a quest is, and the same closed sets and counted fields have the same failure mode here. What
        // cannot be checked in one file is whether a referenced chapter or quest exists: that is
        // QuestIndex's cross-file pass, which is where "this chapter can never be opened" is said.
        checkDependencies(document, path + ".dependsOn", "chapter", problems);
        if (document.has(path + ".prerequisiteMode")) {
            Checks.optionalString(document, path + ".prerequisiteMode", problems)
                    .ifPresent(name -> checkEnum(document, path + ".prerequisiteMode", name,
                            PrerequisiteMode.class, problems));
        }
        if (document.has(path + ".minRequired")) {
            Checks.optionalInt(document, path + ".minRequired", problems).ifPresent(count -> {
                if (count < ChapterRules.MIN_COUNT || count > ChapterRules.MAX_COUNT) {
                    problems.error(document, path + ".minRequired",
                            "minRequired must be between " + ChapterRules.MIN_COUNT + " and "
                                    + ChapterRules.MAX_COUNT + ", found " + count);
                }
            });
        }
        checkDependencies(document, path + ".completesWhen", "quest", problems);
        if (document.has(path + ".hideUntilDependenciesComplete")) {
            Checks.optionalBool(document, path + ".hideUntilDependenciesComplete", problems);
        }
        // Whether this chapter is withheld from every reader, whatever its gate says. Beside the
        // row above because they are the pair an author will confuse: that one withholds the row
        // until the gate is met, this one withholds it always. The gate itself is unaffected.
        if (document.has(path + ".alwaysInvisible")) {
            Checks.optionalBool(document, path + ".alwaysInvisible", problems);
        }
        // Whether this chapter's canvas draws without the zoom tiers. A plain bool check:
        // absence defers to the book's own flag, which is the codec's Optional rather than a
        // validation rule, so there is nothing here to bound beyond "a bool".
        if (document.has(path + ".disableCanvasLod")) {
            Checks.optionalBool(document, path + ".disableCanvasLod", problems);
        }
        // The chapter's defaults for its quests, checked the same way. Note the pair above: the one
        // without `default` withholds the chapter's row, and these decide what its quests do.
        for (String defaulted : new String[] {"defaultHideUntilDependenciesComplete",
                "defaultHideUntilDependenciesVisible", "defaultSequentialTasks"}) {
            if (document.has(path + "." + defaulted)) {
                Checks.optionalBool(document, path + "." + defaulted, problems);
            }
        }
        // The chapter's default panel width for its quests: a quest's own minWidth wins over this,
        // and zero (unset) means the panel kind decides. Same bounds as the quest's own field.
        if (document.has(path + ".defaultMinWidth")) {
            Checks.optionalInt(document, path + ".defaultMinWidth", problems).ifPresent(width -> {
                if (width < QuestPresentation.MIN_WIDTH_MIN
                        || width > QuestPresentation.MIN_WIDTH_MAX) {
                    problems.error(document, path + ".defaultMinWidth",
                            "defaultMinWidth must be between " + QuestPresentation.MIN_WIDTH_MIN + " and "
                                    + QuestPresentation.MIN_WIDTH_MAX + ", found " + width);
                }
            });
        }
        // The quest this chapter centres on when selected. A name, not an id check here: whether
        // it resolves is the index's cross-file pass (a chapter file cannot see its quests' ids
        // from here when quests live in separate files), so this checks only that it names
        // something. An empty string would centre on nothing.
        if (document.has(path + ".autofocus")) {
            Checks.optionalString(document, path + ".autofocus", problems).ifPresent(name -> {
                if (name.isBlank()) {
                    problems.error(document, path + ".autofocus",
                            "an autofocus quest may not be empty - remove the field to centre on the chapter");
                }
            });
        }

        // A theme name is checked for being a non-empty string and nothing more, and that stopping
        // point is the point of it: the theme catalogue is a <b>client</b> concept, and this validator
        // runs on the server too. A dedicated server has no appearance and no themes, so teaching it
        // the list would be a server knowing something only a client can act on — the same shape of
        // mistake as a command that reaches for a client class. The name is checked where it is used,
        // by the client that could not honour it, and the message there names the chapter.
        //
        // An empty string is worth rejecting because the codec would accept it and `Optional.of("")`
        // is not "no opinion" — it is a chapter asking for a theme called nothing, which can only be
        // reported as unrecognised.
        if (document.has(path + ".theme")) {
            Checks.optionalString(document, path + ".theme", problems).ifPresent(name -> {
                if (name.isBlank()) {
                    problems.error(document, path + ".theme",
                            "a theme name may not be empty - remove the field to use the player's own theme");
                }
            });
        }

        // The patch's contents are the toolkit's business, so its own tolerant reader is the check:
        // the messages it produces name the token or the value, which is exactly what an author needs
        // and exactly what a hand-written list of rules here would eventually stop saying. Reported at
        // the field's own line, on the side that can refuse the file — the client that composes the
        // patch reads leniently and simply ignores what it cannot honour.
        if (document.has(path + ".themePatch")) {
            JsonElement patch = document.get(path + ".themePatch").orElse(null);
            if (patch == null || !patch.isJsonObject()) {
                problems.error(document, path + ".themePatch", "expected an object of theme overrides,"
                        + " found " + Checks.kindOf(patch) + ". Write any of "
                        + quoted(ThemePatch.KEYS) + ".");
            }
            else {
                java.util.List<String> patchProblems = new java.util.ArrayList<>();
                ThemePatch.fromJson(patch.getAsJsonObject(), patchProblems);
                for (String message : patchProblems) {
                    problems.error(document, path + ".themePatch", message);
                }
            }
        }

        // The canvas's elements, checked here rather than with the quest list below, and the placement is
        // load-bearing: that block returns early for a chapter with no quests, and an element needs
        // checking whether or not the chapter holds one -- a decorated but questless chapter is a real
        // state, and the one an author is most likely to be editing while the elements are wrong.
        checkElements(document, path + ".elements", problems);

        // And the chapter's markers, for the same reason and in the same place: a link is not a quest,
        // so the quest walk below never sees one, and a chapter of nothing but links is still a file
        // an author can get wrong.
        checkLinks(document, path + ".links", problems);

        if (!document.has(path + ".quests")) {
            problems.warn(document, path, "no \"quests\" - this chapter is empty");
            return;
        }
        if (inlineChildren) {
            var quests = Checks.array(document, path + ".quests", problems);
            if (quests == null) {
                return;
            }
            if (quests.isEmpty()) {
                problems.warn(document, path + ".quests", "this chapter's quest list is empty");
            }
            for (int q = 0; q < quests.size(); q++) {
                validateQuestAt(document, path + ".quests[" + q + "]", problems, ops, QUEST_FIELDS);
            }
        }
        else {
            checkNameList(document, path + ".quests", "quest", problems);
        }
    }

    /** A version-1 quest, where the whole tree is one document. */
    private static void validateQuest(JsonDocument document, int g, int c, int q, Problems problems,
                                      DynamicOps<JsonElement> ops) {
        validateQuestAt(document, questPath(g, c, q), problems, ops, QUEST_FIELDS);
    }

    /**
     * A quest's own fields, at whatever path it sits at.
     *
     * <p>Its path is the only thing either layout changes — a quest has no children either way, so
     * unlike a group or a chapter there is no child-list shape to branch on. The allowed set is still a
     * parameter for the same reason as the other two: at a version-2 file root the document may carry
     * {@code $schema}, and one level down it may not.
     */
    private static void validateQuestAt(JsonDocument document, String path, Problems problems,
                                       DynamicOps<JsonElement> ops,
                                       Set<String> allowedFields) {
        if (!isObject(document, path, problems)) {
            return;
        }
        warnRetiredVersion(document, path, problems);
        Checks.rejectUnknown(document, path, union(allowedFields, RETIRED_VERSION), problems);

        Checks.id(document, path + ".id", problems);
        requiredText(document, path + ".title", problems);
        checkText(document, path + ".subtitle", problems);
        checkTextList(document, path + ".description", problems);
        checkIcon(document, path + ".icon", problems, ops);

        Checks.optionalStringList(document, path + ".aliases", problems).forEach(alias ->
                checkAlias(document, path + ".aliases", alias, problems));
        checkTags(document, path + ".tags", problems);

        if (document.has(path + ".prerequisiteMode")) {
            Checks.optionalString(document, path + ".prerequisiteMode", problems)
                    .ifPresent(name -> checkEnum(document, path + ".prerequisiteMode", name,
                            PrerequisiteMode.class, problems));
        }

        if (document.has(path + ".repeatable")) {
            Checks.optionalBool(document, path + ".repeatable", problems);
        }
        if (document.has(path + ".invisible")) {
            Checks.optionalBool(document, path + ".invisible", problems);
        }
        if (document.has(path + ".sequentialTasks")) {
            Checks.optionalBool(document, path + ".sequentialTasks", problems);
        }
        // Whether the quest is marked a side quest. A marker, not a gate: it gates its
        // dependants exactly like any other quest. Read by the engine, so a typo here would
        // silently gate — the drift the closed-set checks prevent.
        if (document.has(path + ".optional")) {
            Checks.optionalBool(document, path + ".optional", problems);
        }
        // Whether tasks may be worked on before dependencies are met. False defers to the chapter's
        // default; either true makes the quest flexible. Same closed set, same reason.
        if (document.has(path + ".flexibleProgress")) {
            Checks.optionalBool(document, path + ".flexibleProgress", problems);
        }
        if (document.has(path + ".showTitle")) {
            Checks.optionalBool(document, path + ".showTitle", problems);
        }
        // The two reveal flags that are three-state in a quest: a boolean when the quest has an opinion,
        // absent when the chapter decides. Neither was in this list, so `"hideUntilDependenciesComplete":
        // "yes"` was refused by the codec at line one column one -- a message that names neither the field
        // nor the line, which is the whole reason this pass exists.
        for (String reveal : new String[] {"hideUntilDependenciesComplete", "hideUntilDependenciesVisible"}) {
            if (document.has(path + "." + reveal)) {
                Checks.optionalBool(document, path + "." + reveal, problems);
            }
        }
        // The quest rung of the auto-claim ladder; the chapter's is checked in the chapter walk. A typo
        // here would silently defer to the chapter, which is the drift this closed-set check prevents.
        if (document.has(path + ".autoClaim")) {
            Checks.optionalString(document, path + ".autoClaim", problems)
                    .ifPresent(name -> checkEnum(document, path + ".autoClaim", name,
                            dev.ellipog.tenet.quest.reward.RewardAutoClaim.class, problems));
        }
        if (document.has(path + ".iconScale")) {
            checkIconScale(document, path + ".iconScale", problems);
        }
        if (document.has(path + ".repeatCooldownTicks")) {
            Checks.optionalInt(document, path + ".repeatCooldownTicks", problems).ifPresent(ticks -> {
                if (ticks < 0) {
                    problems.error(document, path + ".repeatCooldownTicks",
                            "a cooldown may not be negative, found " + ticks + " - use 0 for none");
                }
            });
            // A cooldown on a quest that can only be done once is not an error, but it does nothing,
            // and silently doing nothing is what makes a file hard to reason about.
            if (!boolAt(document, path + ".repeatable")) {
                problems.warn(document, path + ".repeatCooldownTicks",
                        "a cooldown only means something on a repeatable quest; this quest is not repeatable");
            }
        }
        if (document.has(path + ".exclusiveGroup")) {
            Checks.optionalString(document, path + ".exclusiveGroup", problems).ifPresent(group -> {
                if (group.isBlank()) {
                    problems.error(document, path + ".exclusiveGroup",
                            "an exclusive group name may not be empty - remove the field instead");
                }
            });
        }
        if (document.has(path + ".shape")) {
            Checks.optionalString(document, path + ".shape", problems)
                    .ifPresent(name -> checkEnum(document, path + ".shape", name, QuestShape.class, problems));
        }
        if (document.has(path + ".size")) {
            Checks.optionalInt(document, path + ".size", problems).ifPresent(size -> {
                // The bounds come from the record that owns them, so the two cannot disagree -- the rule
                // this file states for the icon scale and the rotation a few lines below.
                //
                // A warning, not an error, because the codec now clamps this field rather than refusing
                // the document: a validator stricter than the format would refuse a file that loads,
                // which is the one thing this file's own javadoc calls the worst of both worlds. The
                // sentence says what the value was read as, because that is the part the author needs.
                if (size < QuestLayout.MIN_SIZE || size > QuestLayout.MAX_SIZE) {
                    problems.warn(document, path + ".size",
                            "size must be between " + QuestLayout.MIN_SIZE + " and " + QuestLayout.MAX_SIZE
                                    + ", found " + size + " - it is read as "
                                    + Math.max(QuestLayout.MIN_SIZE, Math.min(QuestLayout.MAX_SIZE, size))
                                    + (size > QuestLayout.MAX_SIZE ? "; did you mean " + (size / 10) + "?" : ""));
                }
            });
        }

        // The two counted flags of the dependency and visibility families. Bounded here as well as in
        // the codec, so a hand-written file gets a sentence rather than a decode failure -- and the
        // bounds come from the record that owns them, so the two cannot disagree.
        for (String counted : new String[] {"maxCompletableDependents", "invisibleUntilTasks"}) {
            if (document.has(path + "." + counted)) {
                Checks.optionalInt(document, path + "." + counted, problems).ifPresent(count -> {
                    if (count < QuestRules.MIN_COUNT || count > QuestRules.MAX_COUNT) {
                        problems.error(document, path + "." + counted,
                                counted + " must be between " + QuestRules.MIN_COUNT + " and "
                                        + QuestRules.MAX_COUNT + ", found " + count);
                    }
                });
            }
        }

        if (document.has(path + ".rotation")) {
            Checks.optionalInt(document, path + ".rotation", problems).ifPresent(rotation -> {
                // A warning, for the reason given on `size`: the codec clamps, so refusing here would
                // refuse a file that reads.
                if (rotation < QuestLayout.MIN_ROTATION || rotation > QuestLayout.MAX_ROTATION) {
                    problems.warn(document, path + ".rotation",
                            "rotation must be between " + QuestLayout.MIN_ROTATION + " and "
                                    + QuestLayout.MAX_ROTATION + " degrees, found " + rotation + " - it is read as "
                                    + Math.max(QuestLayout.MIN_ROTATION,
                                            Math.min(QuestLayout.MAX_ROTATION, rotation))
                                    + (rotation == 360
                                            ? "; a full turn is the shape itself, so write 0"
                                            : ""));
                }
            });
        }

        if (document.has(path + ".minRequired")) {
            Checks.optionalInt(document, path + ".minRequired", problems).ifPresent(count -> {
                if (count < 0) {
                    problems.error(document, path + ".minRequired",
                            "minRequired may not be negative, found " + count);
                }
            });
        }

        // How this quest presents itself: how wide its card wants to be, which of its outgoing
        // edges are drawn, whether its completion is announced, whether its rewards survive a held
        // payout, which set the stage gate reads, whether recipe viewers list it, and whether it
        // wears its own lock mark. All seven are
        // presentation — a typo here changes what the player sees, not whether the file loads — so
        // each is a closed-set check rather than a codec surprise.
        if (document.has(path + ".minWidth")) {
            Checks.optionalInt(document, path + ".minWidth", problems).ifPresent(width -> {
                if (width < QuestPresentation.MIN_WIDTH_MIN
                        || width > QuestPresentation.MIN_WIDTH_MAX) {
                    problems.error(document, path + ".minWidth",
                            "minWidth must be between " + QuestPresentation.MIN_WIDTH_MIN + " and "
                                    + QuestPresentation.MIN_WIDTH_MAX + ", found " + width);
                }
            });
        }
        if (document.has(path + ".hideDependentLines")) {
            Checks.optionalBool(document, path + ".hideDependentLines", problems);
        }
        if (document.has(path + ".disableToast")) {
            Checks.optionalBool(document, path + ".disableToast", problems);
        }
        if (document.has(path + ".ignoreRewardBlocking")) {
            Checks.optionalBool(document, path + ".ignoreRewardBlocking", problems);
        }
        if (document.has(path + ".requiresStageTeam")) {
            Checks.optionalBool(document, path + ".requiresStageTeam", problems);
        }
        if (document.has(path + ".disableRecipeMod")) {
            Checks.optionalBool(document, path + ".disableRecipeMod", problems);
        }
        if (document.has(path + ".hideLockIcon")) {
            Checks.optionalBool(document, path + ".hideLockIcon", problems);
        }
        // The guide book page this quest belongs to, as a plain string. Tenet has no guide
        // integration, so this is a reference the quest card shows and nothing more; empty
        // means absent, and is refused here rather than carried as a page called nothing.
        if (document.has(path + ".guidePage")) {
            Checks.optionalString(document, path + ".guidePage", problems).ifPresent(page -> {
                if (page.isBlank()) {
                    problems.error(document, path + ".guidePage",
                            "a guide page may not be empty - remove the field when the quest names none");
                }
            });
        }

        checkDependencies(document, path + ".dependsOn", "quest", problems);
        checkDependencyLines(document, path + ".dependencyLines", problems);
        checkTasks(document, path + ".tasks", problems, ops);
        checkRewards(document, path + ".rewards", problems, ops);
    }

    /**
     * Checks the icon scale, which is a fraction and therefore not a field {@link Checks} can read.
     *
     * <p>Read from the JSON directly rather than through a helper, because {@code Checks} has no
     * {@code optionalDouble} and inventing one in Armature for a single consumer would be library work
     * for a caller's convenience. Everything else about this is the same shape as the numeric checks
     * above: read the value, then say what is wrong with it in the terms the author wrote.
     *
     * <p>Bounds come from {@link QuestShape}, which is where the geometry they describe lives — and
     * therefore what the codec uses too. Two numbers in two places is the mistake this whole file is
     * arranged to avoid, and the honest reason a message can say "the largest that fits" and be right.
     *
     * <p>A warning rather than an error, matching the codec's clamp — and matching the wire, which has
     * clamped this same field to these same bounds since before the file codec was written. That the
     * two halves of one format disagreed about one number is the fault this closes.
     */
    private static void checkIconScale(JsonDocument document, String path, Problems problems) {
        JsonElement element = document.get(path).orElse(null);
        if (element == null) {
            return;
        }
        if (!element.isJsonPrimitive() || !element.getAsJsonPrimitive().isNumber()) {
            problems.error(document, path, "expected a number between "
                    + QuestShape.MIN_ICON_SCALE + " and " + QuestShape.MAX_ICON_SCALE
                    + ", found " + Checks.kindOf(element));
            return;
        }

        double scale = element.getAsDouble();
        if (scale < QuestShape.MIN_ICON_SCALE || scale > QuestShape.MAX_ICON_SCALE) {
            problems.warn(document, path, "iconScale must be between "
                    + QuestShape.MIN_ICON_SCALE + " and " + QuestShape.MAX_ICON_SCALE + ", found " + scale
                    + " - it is read as " + Math.max(QuestShape.MIN_ICON_SCALE,
                            Math.min(QuestShape.MAX_ICON_SCALE, scale))
                    + (scale > QuestShape.MAX_ICON_SCALE
                            ? "; 1.0 is the largest icon that fits in the node"
                            : "; below a quarter the item is a smudge, and the default is "
                              + QuestLayout.DEFAULT_ICON_SCALE));
        }
    }

    // ------------------------------------------------------------------
    // Tasks and rewards
    // ------------------------------------------------------------------

    private static void checkTasks(JsonDocument document, String path, Problems problems,
                                    DynamicOps<JsonElement> ops) {
        if (!document.has(path)) {
            return;
        }
        var array = Checks.array(document, path, problems);
        if (array == null) {
            return;
        }
        for (int i = 0; i < array.size(); i++) {
            checkTask(document, path + "[" + i + "]", problems, ops);
        }
    }

    private static void checkTask(JsonDocument document, String path, Problems problems,
                                  DynamicOps<JsonElement> ops) {
        if (!isObject(document, path, problems)) {
            return;
        }
        Set<Identifier> known = TaskTypes.ids();
        Optional<Identifier> type = readType(document, path, known, "quest task", problems);
        if (type.isEmpty()) {
            // Already reported as an error — a missing or malformed "type". Fall back to the union of
            // every task type's fields, so that one bad type does not then produce a complaint about
            // every field on the task.
            Checks.rejectUnknown(document, path,
                    union(union(allTaskFields(), TaskCommon.FIELDS), Set.of("type")), problems);
            return;
        }
        if (!known.contains(type.get())) {
            // Warned by readType, and the node is kept as an unknown-task placeholder. Its fields
            // cannot be judged against a type this build has never heard of, so the field check is
            // skipped rather than reporting the whole node as unknown fields.
            return;
        }

        // Every field any task type declares, plus this one's own and the common ones. Deliberately
        // a union rather than just this type's fields: a task may legitimately be changed from one
        // type to another, and flagging the leftover fields of the old type on every file would be
        // noise. What this catches is a field no type anywhere understands, which is the typo case.
        Checks.rejectUnknown(document, path,
                union(union(allTaskFields(), TaskCommon.FIELDS), Set.of("type")), problems);

        // A task whose logic somebody else provides: its id is checked against what this build actually has
        // registered. A warning rather than an error, because a pack may legitimately ship a quest for a mod
        // that is not installed here -- the reward path already says as much at grant time -- and because a
        // script registering its handler later in the same load is a fact a validator cannot see. What it
        // catches is the typo, and the mod that is simply absent: the two reasons a custom task sits at zero
        // progress forever with nothing on screen saying why.
        checkCustomHandler(document, path, type, CustomType.TASK, problems);

        if (document.has(path + ".optional")) {
            Checks.optionalBool(document, path + ".optional", problems);
        }
        if (document.has(path + ".disableToast")) {
            Checks.optionalBool(document, path + ".disableToast", problems);
        }
        checkTags(document, path + ".tags", problems);
        // The author's words and picture for this task's row: a QuestText and an icon union, checked
        // like every other text and icon in this file rather than by the task's own codec.
        if (document.has(path + ".title")) {
            checkText(document, path + ".title", problems);
        }
        if (document.has(path + ".icon")) {
            checkIcon(document, path + ".icon", problems, ops);
        }
        if (document.has(path + ".autoSubmitTicks")) {
            Checks.optionalInt(document, path + ".autoSubmitTicks", problems).ifPresent(ticks -> {
                if (ticks < 1 || ticks > 72000) {
                    problems.error(document, path + ".autoSubmitTicks",
                            "must be between 1 and 72000 ticks, found " + ticks);
                }
            });
        }

        // Every built-in task type that names an item does so under "item", so validating that field
        // pair generically covers item tasks and anything an addon adds that follows the convention.
        // Note this checks values only: whether the task's own fields are all known was decided above,
        // by the task type's field set.
        if (document.has(path + ".item")) {
            checkItem(document, path, problems);
        }

        // A break task that names both an id and a tag: the tag wins and the id is silently
        // ignored (see BreakTask.matches), so without this the author learns nothing about which
        // one counts. A warning rather than an error — the file still loads and behaves exactly
        // as before, so no working pack can break on it.
        if (type.get().equals(BreakTask.TYPE)
                && document.has(path + ".block") && document.has(path + ".blockTag")) {
            problems.warn(document, path + ".block",
                    "this task also sets \"blockTag\", which takes precedence: \"block\" is ignored."
                    + " Remove one of the two so the file says what it means."
                    + " The task still loads and counts tag matches");
        }

        // A break task with no selector at all: it counts every block broken anywhere, which is
        // almost never what the author meant. A warning rather than an error, for the same
        // compatibility reason as above — the file still loads either way.
        if (type.get().equals(BreakTask.TYPE)
                && !document.has(path + ".block") && !document.has(path + ".blockTag")
                && !document.has(path + ".blockState")) {
            problems.warn(document, path,
                    "a break task with no \"block\", \"blockTag\" or \"blockState\" counts every break."
                    + " The task still loads; name a target if that was not the intent");
        }

        // A break task names its block under "block" rather than under "item", so the check
        // above cannot see it. Same words and same tolerance as a missing item: the id is
        // kept and the quest still loads; it simply never matches. A block tag is not
        // checked, for the same reason item_tag tasks do not check theirs: tags resolve
        // per datapack, so this build cannot know which ones exist.
        if (document.has(path + ".block")) {
            checkBlock(document, path, problems);
        }

        // A break task's state predicate, in the datapack's own spelling. Unparseable
        // predicates are an error rather than a warning: the engine fail-closes on them,
        // so the task would sit at zero forever with nothing on screen saying why.
        if (document.has(path + ".blockState")) {
            Checks.string(document, path + ".blockState", problems).ifPresent(raw -> {
                try {
                    // The registry is its own lookup now: `asLookup()` is gone (see ObservationTask).
                    net.minecraft.commands.arguments.blocks.BlockStateParser.parseForBlock(
                            BuiltInRegistries.BLOCK, raw, false);
                }
                catch (com.mojang.brigadier.exceptions.CommandSyntaxException e) {
                    problems.error(document, path + ".blockState", "\"" + raw
                            + "\" is not a block state predicate;"
                            + " expected something like \"minecraft:wheat[age=7]\": "
                            + e.getMessage());
                }
            });
        }

        // A filter names its items inside the expression string rather than under "item", so the
        // check above cannot see them. Unparseable expressions are left to the codec check below,
        // which reports the syntax; this warns about the ids a removed mod left behind, in the
        // same words and with the same tolerance as a missing item. Tags are not checked, for the
        // same reason item_tag tasks do not check them: they resolve per datapack.
        if (document.has(path + ".filter")) {
            Checks.string(document, path + ".filter", problems).ifPresent(raw -> {
                dev.ellipog.tenet.quest.task.FilterParser.Expr parsed;
                try {
                    parsed = dev.ellipog.tenet.quest.task.FilterParser.parse(raw);
                }
                catch (dev.ellipog.tenet.quest.task.FilterParser.FilterException e) {
                    return;
                }
                for (Identifier id : parsed.itemIds()) {
                    if (!BuiltInRegistries.ITEM.containsKey(id)) {
                        String hint = id.getNamespace().equals("minecraft")
                                ? " - check the spelling; minecraft: has no such item"
                                : " - the mod \"" + id.getNamespace()
                                  + "\" is probably not installed, or is installed on one side only";
                        problems.warn(document, path + ".filter", "there is no item " + id + hint
                                + ". The id is kept and the quest still loads; it simply never matches");
                    }
                }
            });
        }

        int errorsBeforeConditions = problems.errorCount();
        checkConditions(document, path + ".conditions", problems, ops);
        boolean conditionsBroken = problems.errorCount() > errorsBeforeConditions;

        // And the type's own codec gets the last word. The checks above are about names and value
        // shapes; whether the fields that are there *make a value* is the codec's question, and it is
        // the difference between a save that refuses and a file the loader drops at the next read.
        // This gap had a live defect behind it -- deleting an item task's "item" saved cleanly and
        // took the quest out of the tree -- and the loader's own message asked for it to be closed.
        //
        // Skipped when the conditions list already reported an error, because the task's codec decodes
        // the nested list too: without this, one broken condition produces its own precise message and
        // then this one wrapping the same failure in the task's name. One mistake, one message is the
        // principle the class comment states. The cost is the compound case -- a nested error and a
        // missing field of the task's own show up one per read rather than together -- which is the
        // rarer and the less confusing of the two trades.
        if (!conditionsBroken) {
            type.ifPresent(id -> TaskTypes.codecOf(id)
                    .ifPresent(codec -> decodeEntry(document, path, id, codec, problems, ops)));
        }
    }

    /**
     * Which half of a quest a custom id belongs to: the two registries it can be looked up in.
     *
     * <p>One enum rather than two near-identical checks, because the pair is the whole of the difference --
     * the same sentence with a different noun, the same lookup against a different map.
     */
    private enum CustomType {
        TASK("task", dev.ellipog.tenet.quest.task.CustomTask.TYPE,
                dev.ellipog.tenet.quest.task.CustomTask.CustomTasks::ids),
        REWARD("reward", dev.ellipog.tenet.quest.reward.CustomReward.TYPE,
                dev.ellipog.tenet.quest.reward.CustomReward.CustomRewards::ids);

        private final String word;
        private final Identifier type;
        private final java.util.function.Supplier<java.util.Set<String>> handlers;

        CustomType(String word, Identifier type,
                   java.util.function.Supplier<java.util.Set<String>> handlers) {
            this.word = word;
            this.type = type;
            this.handlers = handlers;
        }

        /** The noun the warning uses: "this task does nothing". */
        String word() {
            return word;
        }

        /** The type id a document has to name for this check to apply. */
        Identifier type() {
            return type;
        }

        /** Every id this build has a handler registered for. */
        java.util.Set<String> handlers() {
            return handlers.get();
        }
    }

    /**
     * Warns when a custom task's or reward's id has nothing registered for it in this build.
     *
     * <p>A warning and not an error, and the difference is the point: a pack that ships a quest for a mod
     * the player has not installed meant to, and the quest is not broken -- it is inert. What this catches
     * is the typo, and the mod that is simply absent; both look identical from inside the game, where such a
     * task sits at zero progress forever with nothing on screen saying why.
     */
    private static void checkCustomHandler(JsonDocument document, String path,
                                           Optional<Identifier> type, CustomType kind,
                                           Problems problems) {
        if (type.isEmpty() || !type.get().equals(kind.type())) {
            return;
        }
        Checks.optionalString(document, path + ".id", problems).ifPresent(id -> {
            if (!kind.handlers().contains(id)) {
                problems.warn(document, path + ".id",
                        "nothing is registered for \"" + id + "\" in this build - the mod or script that "
                                + "provides it is not loaded, so this " + kind.word() + " does nothing");
            }
        });
    }

    private static void checkRewards(JsonDocument document, String path, Problems problems,
                                     DynamicOps<JsonElement> ops) {
        if (!document.has(path)) {
            return;
        }
        var array = Checks.array(document, path, problems);
        if (array == null) {
            return;
        }
        for (int i = 0; i < array.size(); i++) {
            checkReward(document, path + "[" + i + "]", problems, ops);
        }
    }

    private static void checkReward(JsonDocument document, String path, Problems problems,
                                    DynamicOps<JsonElement> ops) {
        if (!isObject(document, path, problems)) {
            return;
        }
        Set<Identifier> known = RewardTypes.ids();
        Optional<Identifier> type = readType(document, path, known, "quest reward", problems);
        if (type.isEmpty()) {
            Checks.rejectUnknown(document, path, union(allRewardFields(), Set.of("type")), problems);
            return;
        }
        if (!known.contains(type.get())) {
            // Warned by readType; the node is kept as an unknown-reward placeholder. See checkTask.
            return;
        }
        // The union of every reward type's fields, for the reason given in checkTask.
        Checks.rejectUnknown(document, path, union(allRewardFields(), Set.of("type")), problems);

        // A choice reward cannot be handed over without the player's pick, so an automatic mode on one
        // is a setting that does nothing -- the "validated and then ignored" drift this file exists to
        // catch. A warning rather than an error: the file is not broken, the setting is. The engine
        // skips such a reward rather than eating it (see QuestReward#autoGrantable), so this is an
        // author being told their intent cannot be honoured, not a defect to refuse.
        if (document.has(path + ".disableToast")) {
            Checks.optionalBool(document, path + ".disableToast", problems);
        }
        checkTags(document, path + ".tags", problems);
        // The author's words and picture for this reward's row, checked like the task's own above.
        if (document.has(path + ".title")) {
            checkText(document, path + ".title", problems);
        }
        if (document.has(path + ".icon")) {
            checkIcon(document, path + ".icon", problems, ops);
        }
        // The toast reward's message and the command reward's success line: QuestTexts like the
        // title, so a blank one warns rather than granting silence.
        if (document.has(path + ".description")) {
            checkText(document, path + ".description", problems);
        }
        if (document.has(path + ".feedbackMessage")) {
            checkText(document, path + ".feedbackMessage", problems);
        }
        if (document.has(path + ".auto")) {
            // Which modes are automatic is the enum's own answer, asked rather than spelled out here:
            // three hand-written names were a second copy of `automatic()`, and a sixth mode added to
            // the enum would have been invisible to this warning.
            boolean automatic = Checks.optionalString(document, path + ".auto", problems)
                    .flatMap(RewardAutoClaim::byName)
                    .map(RewardAutoClaim::automatic)
                    .orElse(false);
            boolean choice = type
                    .map(id -> id.equals(dev.ellipog.tenet.quest.reward.TableReward.TYPE_CHOICE))
                    .orElse(false);
            if (automatic && choice) {
                problems.warn(document, path + ".auto",
                        "a choice reward waits for the player's pick, so an automatic \"auto\" does "
                                + "nothing here - remove it, or use a random or loot reward instead");
            }
        }

        // The reward half of the same check: a custom reward whose handler is not installed grants nothing,
        // and the log line at grant time is the runtime signal -- this is the one an author reads.
        checkCustomHandler(document, path, type, CustomType.REWARD, problems);

        // A currency reward whose economy is not installed grants nothing either, for the same
        // reason and with the same tolerance: a warning rather than an error, because a missing
        // economy mod is often temporary and the quest still completes.
        if (type.map(dev.ellipog.tenet.quest.reward.CurrencyReward.TYPE::equals).orElse(false)
                && dev.ellipog.tenet.quest.reward.CurrencyReward.CurrencyProviders.provider()
                        .isEmpty()) {
            problems.warn(document, path + ".type",
                    "this build has no currency provider registered, so this reward grants nothing "
                            + "here - a currency mod or script that pays it is not loaded");
        }

        if (document.has(path + ".item")) {
            checkItem(document, path, problems);
        }

        int errorsBeforeNested = problems.errorCount();
        checkConditions(document, path + ".conditions", problems, ops);
        checkInlineTable(document, path, problems, ops);
        boolean nestedBroken = problems.errorCount() > errorsBeforeNested;

        // The reward half of the codec check in checkTask, for the same defect and the same reason --
        // including the skip when a nested structure already spoke, which keeps one broken condition or
        // table entry from being reported twice.
        if (!nestedBroken) {
            type.ifPresent(id -> RewardTypes.codecOf(id)
                    .ifPresent(codec -> decodeEntry(document, path, id, codec, problems, ops)));
        }
    }

    /**
     * A reward that sits inside a reward table: checked like any reward, then refused the one field a
     * table cannot honour.
     *
     * <p>A table entry is handed out by the roll rather than claimed, so a {@code conditions} list on it
     * would be validated and then ignored -- {@code TableReward.grantAll} pays entries directly, with no
     * player to ask. That is exactly the "parsed, validated and never consumed" drift this file exists
     * to prevent, so it is an error naming the two ways out. The table reward's <b>own</b> conditions
     * stay legal: those are checked on every path that pays the table.
     *
     * <p>The one place an entry's reward is walked at all -- the inline walk below calls it too, which
     * is why inline entries are now checked: before this they were not validated in any way.
     */
    private static void checkTableEntryReward(JsonDocument document, String rewardPath, Problems problems,
                                              DynamicOps<JsonElement> ops) {
        checkReward(document, rewardPath, problems, ops);
        if (document.has(rewardPath + ".conditions")) {
            problems.error(document, rewardPath + ".conditions",
                    "a reward inside a reward table cannot carry \"conditions\": entries are handed out "
                            + "by the roll, not claimed, so a gate here would be validated and then "
                            + "ignored - put the conditions on the table reward itself, or move this "
                            + "reward out of the table");
        }
        // And the other thing a roll cannot do: offer a choice. The engine skips such an entry with a
        // log line (see TableReward.grantAll), which is the runtime's last resort rather than a
        // message an author reads -- so the file says it here, where the line number is. The sentence
        // comes from the model, so the editor's picker -- which draws this type blocked with the same
        // words -- cannot describe the rule differently from the loader that enforces it.
        if (document.has(rewardPath + ".type")) {
            Checks.optionalString(document, rewardPath + ".type", problems).ifPresent(type -> {
                net.minecraft.resources.Identifier id =
                        net.minecraft.resources.Identifier.tryParse(type);
                String refusal = id == null ? ""
                        : dev.ellipog.tenet.quest.reward.TableReward.entryRefusal(id);
                if (!refusal.isEmpty()) {
                    problems.error(document, rewardPath + ".type", refusal);
                }
            });
        }
    }

    /**
     * The entries of an inline table, which nothing walked before this.
     *
     * <p>An inline table is a {@code RewardTable} nested in a reward's own {@code inline} field, and the
     * field-name checks did not descend into it: its entries were checked by the codec alone, and a
     * codec ignores unknown fields -- the exact gap the field-name checks exist to close. This mirrors
     * the named-table walk, entry for entry, so an inline table and a file table are checked the same.
     */
    private static void checkInlineTable(JsonDocument document, String path, Problems problems,
                                         DynamicOps<JsonElement> ops) {
        String inlinePath = path + ".inline";
        if (!document.has(inlinePath) || !isObject(document, inlinePath, problems)) {
            return;
        }
        warnRetiredTableFields(document, inlinePath, problems, true);
        Checks.rejectUnknown(document, inlinePath,
                union(dev.ellipog.tenet.quest.loot.RewardTable.FIELDS, RETIRED_TABLE_FIELDS),
                problems);
        if (document.has(inlinePath + ".useTitle")) {
            Checks.optionalBool(document, inlinePath + ".useTitle", problems);
        }
        if (document.has(inlinePath + ".hideTooltip")) {
            Checks.optionalBool(document, inlinePath + ".hideTooltip", problems);
        }
        var entries = Checks.array(document, inlinePath + ".entries", problems);
        if (entries == null) {
            return;
        }
        for (int i = 0; i < entries.size(); i++) {
            String entryPath = inlinePath + ".entries[" + i + "]";
            if (!isObject(document, entryPath, problems)) {
                continue;
            }
            Checks.rejectUnknown(document, entryPath,
                    dev.ellipog.tenet.quest.loot.RewardTable.Entry.FIELDS, problems);
            checkTableEntryReward(document, entryPath + ".reward", problems, ops);
        }
    }

    /**
     * Checks a {@code "conditions"} list on a task or a reward.
     *
     * <p>A nested list of typed objects, which is a shape the validator's other checks do not have: a
     * task's own fields are flat on the task, while a condition's fields are one level down. So the
     * elements get their own dispatch read, their own unknown-field union, and their own codec
     * backstop — the same three questions as a task, asked at the nested path.
     */
    private static void checkConditions(JsonDocument document, String path, Problems problems,
                                        DynamicOps<JsonElement> ops) {
        if (!document.has(path)) {
            return;
        }
        var array = Checks.array(document, path, problems);
        if (array == null) {
            return;
        }
        for (int i = 0; i < array.size(); i++) {
            checkCondition(document, path + "[" + i + "]", problems, ops);
        }
    }

    private static void checkCondition(JsonDocument document, String path, Problems problems,
                                       DynamicOps<JsonElement> ops) {
        if (!isObject(document, path, problems)) {
            return;
        }
        Set<Identifier> known = ConditionTypes.ids();
        Optional<Identifier> type = readType(document, path, known, "quest condition", problems);
        if (type.isEmpty()) {
            Checks.rejectUnknown(document, path, union(allConditionFields(), Set.of("type")), problems);
            return;
        }
        if (!known.contains(type.get())) {
            // Warned by readType; the node is kept as an unknown-condition placeholder, and Conditions
            // reads it as not met -- the safe direction for a gate. See checkTask for the skipped check.
            return;
        }
        // Every condition type's fields, for the reason checkTask gives for the same union: a condition
        // may legitimately be changed from one type to another, and flagging the old type's leftovers
        // on every file would be noise. What this catches is the field no type anywhere understands.
        Checks.rejectUnknown(document, path, union(allConditionFields(), Set.of("type")), problems);

        // The item half of the same convention checkTask runs: an item condition declares its item
        // under "item", and the item's values are checked the same way wherever the field appears.
        int errorsBeforeItem = problems.errorCount();
        if (document.has(path + ".item")) {
            checkItem(document, path, problems);
        }

        // The codec gets the last word, unless the checks above already spoke -- the same one-mistake-
        // one-message rule the task and reward levels use. Keyed on errors, so a missing item (a warning)
        // still lets the codec report a field that actually cannot form a value.
        if (problems.errorCount() == errorsBeforeItem) {
            type.ifPresent(id -> ConditionTypes.codecOf(id)
                    .ifPresent(codec -> decodeEntry(document, path, id, codec, problems, ops)));
        }
    }

    /**
     * Asks the entry's own codec whether its fields make a value.
     *
     * <p>This is the backstop the field-name checks cannot be: a codec's required fields are not
     * derivable from a field list, and their absence is invisible to every rule above -- an absent
     * field is not an unknown one. The failure is reported at the entry's own path with the codec's
     * message under it, which names the key that is missing.
     *
     * <p>Called only for a type the validator already knows, because the loader's dispatch cannot
     * decode an unknown type at all and that case has its own message above; this asks the narrower
     * question on purpose, so an addon's type is not double-reported.
     */
    private static void decodeEntry(JsonDocument document, String path, Identifier id,
                                    MapCodec<?> codec, Problems problems, DynamicOps<JsonElement> ops) {
        // Through Checks.parse, so an addon's codec that throws is reported against this entry rather
        // than escaping the validator -- which runs inside the load, and inside the editor's save.
        // Decoded with the caller's ops: registry-backed fields (data components, holders, tags)
        // only resolve against registries, and plain JSON ops fail every one of them.
        document.get(path).ifPresent(entry -> Checks.parse(codec.codec(), entry, ops)
                .error().ifPresent(error -> problems.error(document, path,
                        "these fields do not form a " + id + ":\n    "
                                + error.message().replace("\n", "\n    "))));
    }

    // ------------------------------------------------------------------
    // Shared pieces
    // ------------------------------------------------------------------

    /**
     * Reads and resolves a {@code "type"} field.
     *
     * <p>Reporting the unknown-type case here rather than leaving it to the codec is what produces a
     * usable message. The codec's own dispatch does list the valid types — but the file is refused
     * before the codec ever runs, so this is the only message the author sees, which is exactly why it
     * should be the good one.
     *
     * <p>The caller passes which registry it expects, so a task cannot claim a reward's type and be
     * told that every one of its fields is unknown.
     *
     * <h2>An unknown type is a warning, and the id still comes back</h2>
     *
     * <p>It was an error, and an error in this validator means the file is <b>not decoded at all</b> —
     * so one node naming an addon that is not installed cost the author every quest in the file. That is
     * the opposite of the additive compatibility the rest of the format is built for, and it is the
     * same fault the missing-item check was already reversed for: a mod being absent is usually
     * temporary, and a file that cannot be loaded is not recoverable by the person holding it.
     *
     * <p>The dispatch now decodes an unregistered type to a placeholder, so the node survives. The id
     * is returned <i>anyway</i> for that case, which is how the caller knows to skip the per-node field
     * check: the fields of a type this build has never heard of are not unknown fields, they are
     * unreadable ones, and reporting each of them would be a page of noise about a node already
     * reported once at its own line.
     */
    private static Optional<Identifier> readType(JsonDocument document, String path,
                                                       Set<Identifier> known, String kind,
                                                       Problems problems) {
        String typePath = path + ".type";
        Optional<String> raw = Checks.optionalString(document, typePath, problems);
        if (raw.isEmpty()) {
            problems.error(document, typePath, "missing required field \"type\"");
            return Optional.empty();
        }

        Identifier id = Identifier.tryParse(raw.get());
        if (id == null) {
            problems.error(document, typePath, "\"" + raw.get()
                    + "\" is not a valid id; expected something like \"tenet:item\"");
            return Optional.empty();
        }

        if (!known.contains(id)) {
            problems.warn(document, typePath, "unknown " + kind + " type \"" + raw.get() + "\"\n"
                    + "    known types: " + String.join(", ",
                            known.stream().map(Identifier::toString).sorted().toList())
                    + "\n    the node is kept and drawn as an unknown " + kind + ", and nothing in it"
                    + " can make progress until the mod that provides \"" + raw.get() + "\" is loaded");
            return Optional.of(id);
        }
        return Optional.of(id);
    }

    /**
     * Checks an icon object, which is exactly an item reference and nothing else.
     *
     * <p>Separate from {@link #checkItem} because this one owns the object and can therefore reject
     * unknown fields on it, while a task's {@code "item"} fields live on the task itself — where
     * rejecting against the item's field set would flag {@code "type"} and {@code "optional"} as
     * unknown. That mistake would have made every task in every file report two spurious errors.
     */
    private static void checkIcon(JsonDocument document, String path, Problems problems,
                                  DynamicOps<JsonElement> ops) {
        if (!document.has(path)) {
            return;
        }
        if (!isObject(document, path, problems)) {
            return;
        }
        Checks.rejectUnknown(document, path, dev.ellipog.tenet.quest.Icon.FIELDS, problems);

        // Which arm the object names. The item arm wins a tie, because "item" is the key every old
        // file carries and the new keys never appear beside it except by mistake; the codec
        // reads the same way, so the two cannot disagree about which arm a file means.
        List<String> arms = new java.util.ArrayList<>();
        for (String arm : new String[] {"item", "texture", "sprite", "entity"}) {
            if (document.has(path + "." + arm)) {
                arms.add(arm);
            }
        }
        if (arms.size() > 1) {
            // A warning rather than an error, like an unknown item: the codec's precedence is
            // deterministic (item, then texture, sprite, entity), so the file still loads with a
            // known picture, and an error would skip the whole quest over a cosmetic field.
            String winner = arms.contains("item") ? "item"
                    : arms.contains("texture") ? "texture"
                    : arms.contains("sprite") ? "sprite" : "entity";
            List<String> ignored = new java.util.ArrayList<>(arms);
            ignored.remove(winner);
            problems.warn(document, path,
                    "this icon names " + arms.size() + " pictures (" + String.join(", ", arms) + ")"
                            + " - only \"" + winner + "\" is drawn and "
                            + String.join(", ", ignored) + " ignored."
                            + " Keep one arm and remove the other"
                            + (ignored.size() == 1 ? "" : "s"));
        }
        if (document.has(path + ".item")) {
            checkItem(document, path, problems);

            // And the reference's own codec -- the icon's half of the entry check in checkTask: a
            // component patch the codec cannot read would otherwise reach the loader, which skips the
            // whole quest over it. The codec's own message names what it was unhappy about. Decoded
            // with the caller's ops, because registry-backed components only resolve against registries.
            document.get(path).ifPresent(object -> Checks.parse(ItemRef.CODEC, object, ops)
                    .error().ifPresent(error -> problems.error(document, path,
                            "this is not a usable item reference:\n    "
                                    + error.message().replace("\n", "\n    "))));
            return;
        }
        if (document.has(path + ".texture")) {
            // A texture is a client's file, like a theme name: this validator runs on a dedicated
            // server too, which has no textures to check against. So the name is checked for shape
            // and nothing more, and the client draws nothing for a path it cannot resolve — the same
            // treatment a missing image element gets.
            Checks.optionalString(document, path + ".texture", problems).ifPresent(raw -> {
                if (raw.isBlank()) {
                    problems.error(document, path + ".texture",
                            "a texture path may not be empty - write the file's path,"
                                    + " e.g. \"my_pack:textures/gui/emblem.png\"");
                }
                else if (net.minecraft.resources.Identifier.tryParse(raw) == null) {
                    problems.error(document, path + ".texture", "\"" + raw
                            + "\" is not a namespaced path;"
                            + " expected something like \"my_pack:textures/gui/emblem.png\"");
                }
            });
            return;
        }
        if (document.has(path + ".sprite")) {
            // An atlas region, like a chapter element's sprite: the atlas is not catalogued on
            // either side — see the element check — so the id is checked for shape and nothing
            // more, and the client draws the missing mark for a region nothing holds.
            Checks.optionalString(document, path + ".sprite", problems).ifPresent(raw -> {
                if (raw.isBlank()) {
                    problems.error(document, path + ".sprite",
                            "a sprite id may not be empty - write e.g. \"minecraft:block/sculk\"");
                }
                else if (net.minecraft.resources.Identifier.tryParse(raw) == null) {
                    problems.error(document, path + ".sprite", "\"" + raw
                            + "\" is not a namespaced id;"
                            + " expected something like \"minecraft:block/sculk\"");
                }
            });
            return;
        }
        if (document.has(path + ".entity")) {
            Checks.optionalString(document, path + ".entity", problems).ifPresent(raw -> {
                if (raw.isBlank()) {
                    problems.error(document, path + ".entity",
                            "an entity id may not be empty - write e.g. \"minecraft:creeper\"");
                    return;
                }
                net.minecraft.resources.Identifier id =
                        net.minecraft.resources.Identifier.tryParse(raw);
                if (id == null) {
                    problems.error(document, path + ".entity", "\"" + raw
                            + "\" is not a valid entity id;"
                            + " expected something like \"minecraft:creeper\"");
                    return;
                }
                // A warning rather than an error, like an unknown item: a missing mod is often
                // temporary, so the id is kept and the quest loads, and the node says what is missing.
                if (!net.minecraft.core.registries.BuiltInRegistries.ENTITY_TYPE.containsKey(id)) {
                    problems.warn(document, path + ".entity", "there is no entity " + id
                            + " - the mod is probably not installed."
                            + " The id is kept and the quest still loads;"
                            + " its node will say the entity is missing");
                }
            });
            return;
        }
        // No arm key at all: the codec refuses it, and its message is the precise one — but only the
        // validator runs before the loader, so the sentence here names the four keys.
        document.get(path).ifPresent(object -> Checks.parse(
                        dev.ellipog.tenet.quest.Icon.CODEC, object, ops)
                .error().ifPresent(error -> problems.error(document, path,
                        "this is not a usable icon: write one of \"item\", \"texture\", \"sprite\" or \"entity\"")));
    }

    /**
     * Checks the values of an {@code "item"} / {@code "count"} pair that sits flat on a larger object.
     *
     * <p>Deliberately does <b>not</b> reject unknown fields: whether the enclosing object has the right
     * fields is a question only its own codec's field set can answer, and its caller has already asked
     * it.
     *
     * <p>Existence is checked here, by reading the JSON, rather than from the decoded records. That
     * keeps every content check in one phase, and means an unknown item is reported at its own line and
     * column instead of as part of a batch of cross-file complaints afterwards.
     *
     * <p>An item whose mod is not installed is a <b>warning</b>, and that is a reversal worth its
     * sentence: it was an error, "a quest requiring it can never be completed" -- and an error skips
     * the file, so a pack whose mod was removed lost the entire quest, node and all, with the id still
     * sitting in the file. A missing mod is often temporary (an update, a server-side absence), so the
     * id is kept, the quest loads, and the row says it is missing. The message still names the mod,
     * because that is the actual fix.
     */
    private static void checkItem(JsonDocument document, String path, Problems problems) {
        String itemPath = path + ".item";
        Optional<String> raw = Checks.string(document, itemPath, problems);
        if (raw.isEmpty()) {
            return;
        }

        Identifier id = Identifier.tryParse(raw.get());
        if (id == null) {
            problems.error(document, itemPath, "\"" + raw.get()
                    + "\" is not a valid item id; expected something like \"minecraft:oak_log\"");
            return;
        }

        if (!BuiltInRegistries.ITEM.containsKey(id)) {
            String hint = id.getNamespace().equals("minecraft")
                    ? " - check the spelling; minecraft: has no such item"
                    : " - the mod \"" + id.getNamespace()
                      + "\" is probably not installed, or is installed on one side only";
            problems.warn(document, itemPath, "there is no item " + id + hint
                    + ". The id is kept and the quest still loads; its row will say the item is missing");
            // Note the messages here are deliberately ASCII. These lines end up in server logs, which
            // get read through terminals and editors with every encoding going; an em dash renders as
            // mojibake in at least one of them, and the message is worth more than the typography.
        }

        Checks.optionalInt(document, path + ".count", problems).ifPresent(count -> {
            if (count < 1) {
                problems.error(document, path + ".count", "count must be at least 1, found " + count
                        + " - to require nothing, remove the task instead");
            }
        });
    }

    /**
     * A break task's block, in the same words and with the same tolerance as {@link #checkItem}.
     *
     * <p>A block whose mod is not installed is a warning rather than an error, for the same
     * reason: a missing mod is often temporary, so the id is kept, the quest loads, and the
     * task simply never matches until the mod is back.
     */
    private static void checkBlock(JsonDocument document, String path, Problems problems) {
        String blockPath = path + ".block";
        Optional<String> raw = Checks.string(document, blockPath, problems);
        if (raw.isEmpty()) {
            return;
        }

        Identifier id = Identifier.tryParse(raw.get());
        if (id == null) {
            problems.error(document, blockPath, "\"" + raw.get()
                    + "\" is not a valid block id; expected something like \"minecraft:stone\"");
            return;
        }

        if (!BuiltInRegistries.BLOCK.containsKey(id)) {
            String hint = id.getNamespace().equals("minecraft")
                    ? " - check the spelling; minecraft: has no such block"
                    : " - the mod \"" + id.getNamespace()
                      + "\" is probably not installed, or is installed on one side only";
            problems.warn(document, blockPath, "there is no block " + id + hint
                    + ". The id is kept and the quest still loads; it simply never matches");
        }
    }

    private static void checkDependencies(JsonDocument document, String path, String what,
                                          Problems problems) {
        var ids = Checks.optionalStringList(document, path, problems);
        for (int i = 0; i < ids.size(); i++) {
            String elementPath = path + "[" + i + "]";
            String candidate = ids.get(i);
            if (candidate.isEmpty()) {
                problems.error(document, elementPath, "a " + what + " id may not be empty");
                continue;
            }
            boolean wellFormed = candidate.length() <= ChapterNaming.MAX_LENGTH;
            for (int j = 0; wellFormed && j < candidate.length(); j++) {
                char ch = candidate.charAt(j);
                wellFormed = (ch >= 'a' && ch <= 'z') || (ch >= '0' && ch <= '9') || ch == '_';
            }
            if (!wellFormed) {
                problems.error(document, elementPath, "'" + candidate
                        + "' is not a valid " + what
                        + " id; only lowercase letters, digits and underscores are allowed");
            }
        }
    }

    /**
     * The lookup words on a group, a chapter, a quest or a task: the legacy {@code tags}, present
     * on every object.
     *
     * <p>Each tag follows the same rule an id does — lowercase letters, digits and underscores, at
     * most {@link ChapterNaming#MAX_LENGTH} characters — because a tag is looked up by spelling,
     * and a tag nothing can spell is a lookup that never answers. An absent list is the common case
     * and costs nothing, which is what every file written before this field existed gets.
     */
    private static void checkTags(JsonDocument document, String path, Problems problems) {
        var tags = Checks.optionalStringList(document, path, problems);
        for (int i = 0; i < tags.size(); i++) {
            String elementPath = path + "[" + i + "]";
            String candidate = tags.get(i);
            if (candidate.isEmpty()) {
                problems.error(document, elementPath, "a tag may not be empty");
                continue;
            }
            boolean wellFormed = candidate.length() <= ChapterNaming.MAX_LENGTH;
            for (int j = 0; wellFormed && j < candidate.length(); j++) {
                char ch = candidate.charAt(j);
                wellFormed = (ch >= 'a' && ch <= 'z') || (ch >= '0' && ch <= '9') || ch == '_';
            }
            if (!wellFormed) {
                problems.error(document, elementPath, "'" + candidate
                        + "' is not a valid tag;"
                        + " only lowercase letters, digits and underscores are allowed");
            }
        }
    }

    /**
     * The per-line overrides: an object keyed by the dependency each entry styles.
     *
     * <p>The keys are dependency ids and are checked as such elsewhere — this checks the shape, so a
     * misspelled setting or an unknown value is reported where the author wrote it rather than silently
     * doing nothing at draw time.
     */
    private static void checkDependencyLines(JsonDocument document, String path, Problems problems) {
        JsonElement element = document.get(path).orElse(null);
        if (element == null) {
            return;
        }
        if (!element.isJsonObject()) {
            problems.error(document, path, "expected an object keyed by dependency id, found "
                    + Checks.kindOf(element) + ". Each entry styles one line:"
                    + " \"a_quest\": { \"form\": \"curved\" }.");
            return;
        }
        for (java.util.Map.Entry<String, JsonElement> entry : element.getAsJsonObject().entrySet()) {
            checkDependencyStyle(document, path + "." + entry.getKey(), problems);
        }
    }

    /**
     * One style object: whose keys are the axes it names and whose values are names this build knows.
     *
     * <p>One check for both places a style can appear — a chapter's default and a line's override —
     * because they are one shape, and two checks would be two vocabularies the day one of them grew.
     * An absent object is fine; the codec fills nothing and the built-ins take over.
     */
    private static void checkDependencyStyle(JsonDocument document, String path, Problems problems) {
        checkDependencyStyle(document, path, problems, true);
    }

    /**
     * The same, told whether this is a line's own style or a chapter's default.
     *
     * <p>Anchors are refused in the chapter's: which rim a line meets is a fact about that line's two
     * ends, so a chapter-wide angle would be wrong for almost every edge. The setting exists as a
     * per-line tool and the message says so rather than leaving an author to wonder why it does nothing.
     */
    private static void checkDependencyStyle(JsonDocument document, String path, Problems problems,
                                             boolean allowAnchors) {
        JsonElement element = document.get(path).orElse(null);
        if (element == null) {
            return;
        }
        if (!element.isJsonObject()) {
            problems.error(document, path, "expected an object of line settings, found "
                    + Checks.kindOf(element) + ". Write any of " + quoted(DependencyStyle.SHARED_FIELDS)
                    + ", and leave out the ones this line does not choose.");
            return;
        }
        for (java.util.Map.Entry<String, JsonElement> entry : element.getAsJsonObject().entrySet()) {
            String key = entry.getKey();
            String fieldPath = path + "." + key;
            if (!allowAnchors && (key.equals("fromAnchor") || key.equals("toAnchor"))) {
                problems.error(document, fieldPath, "an anchor is per line, not per chapter: which rim a"
                        + " line meets depends on where its two ends are, so set it on the line itself");
                continue;
            }
            if (key.equals("fromHandle") || key.equals("toHandle")) {
                if (!allowAnchors) {
                    problems.error(document, fieldPath, "a split control point is per line, not per"
                            + " chapter: set it on the line itself");
                    continue;
                }
                JsonElement value = entry.getValue();
                boolean pair = value.isJsonArray() && value.getAsJsonArray().size() == 2;
                if (pair) {
                    for (JsonElement each : value.getAsJsonArray()) {
                        // Not `pair = ...`: a mixed pair like [0.3, "x"] has to be refused, and an
                        // assignment per element would let the last one overwrite the verdict and then
                        // throw on getAsDouble.
                        if (!each.isJsonPrimitive() || !each.getAsJsonPrimitive().isNumber()) {
                            pair = false;
                            break;
                        }
                    }
                }
                if (!pair) {
                    problems.error(document, fieldPath, "expected two numbers - along and across, as"
                            + " fractions of the line's own chord, like [0.33, 0.2]");
                    continue;
                }
                double along = value.getAsJsonArray().get(0).getAsDouble();
                double across = value.getAsJsonArray().get(1).getAsDouble();
                if (along < -0.5 || along > 1.5 || Math.abs(across) > 0.9) {
                    problems.error(document, fieldPath, "a control point has to stay near its line:"
                            + " along is -0.5 to 1.5 and across is -0.9 to 0.9");
                }
                continue;
            }
            if (key.equals("bend") || key.equals("fromAnchor") || key.equals("toAnchor")) {
                // The one numeric axis: a fraction of the chord, and its limit is the drag's own.
                JsonElement value = entry.getValue();
                if (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isNumber()) {
                    problems.error(document, fieldPath, "expected a number, found " + Checks.kindOf(value));
                    continue;
                }
                double bend = value.getAsDouble();
                if (key.equals("bend") && Math.abs(bend) > DependencyStyle.MAX_BEND) {
                    problems.error(document, fieldPath, "a bend is between -" + DependencyStyle.MAX_BEND
                            + " and " + DependencyStyle.MAX_BEND + " - " + bend
                            + " would double the line back on itself");
                }
                continue;
            }
            Class<? extends Enum<?>> axis = DependencyStyle.axisType(key);
            if (axis == null) {
                // The vocabulary comes from the record's own lists, so an axis added there cannot be
                // forgotten here -- the two hardcoded sentences this replaces were already two lists to
                // update by hand.
                problems.error(document, fieldPath, "unknown line setting \"" + key + "\" - the settings"
                        + " are " + String.join(", ", DependencyStyle.SHARED_FIELDS)
                        + (allowAnchors ? ", and per line also "
                        + String.join(", ", DependencyStyle.LINE_FIELDS) : ""));
                continue;
            }
            JsonElement value = entry.getValue();
            if (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isString()) {
                problems.error(document, fieldPath, "expected a name, found " + Checks.kindOf(value));
                continue;
            }
            String name = value.getAsString();
            if (!DependencyStyle.known(axis, name)) {
                problems.error(document, fieldPath, "\"" + name + "\" is not a " + key + " this build"
                        + " knows - try one of " + names(axis) + ".");
            }
        }
    }

    /** The names an axis accepts, for a message. */
    private static String names(Class<? extends Enum<?>> axis) {
        StringBuilder out = new StringBuilder();
        for (Enum<?> value : axis.getEnumConstants()) {
            if (!out.isEmpty()) {
                out.append(", ");
            }
            out.append(value.name().toLowerCase(java.util.Locale.ROOT));
        }
        return out.toString();
    }

    /** The vocabulary as a message writes it: quoted, comma-separated. */
    private static String quoted(List<String> axes) {
        StringBuilder out = new StringBuilder();
        for (String axis : axes) {
            if (!out.isEmpty()) {
                out.append(", ");
            }
            out.append('"').append(axis).append('"');
        }
        return out.toString();
    }

    /** A text value, in either of its two forms. */
    /**
     * A required text field. Separate from {@link #checkText} only so the message can name it.
     *
     * <p>Was "missing required field" with no name, which is technically true and practically
     * useless at a line and column pointing into an object with eight fields.
     */
    // ------------------------------------------------------------------
    // A chapter's canvas elements
    // ------------------------------------------------------------------

    /** The element types this build draws, in the order a message should list them. */
    private static final List<String> ELEMENT_TYPES = List.of(
            CanvasElement.TYPE_IMAGE, CanvasElement.TYPE_TEXT, CanvasElement.TYPE_LINE,
            CanvasElement.TYPE_RECT);

    /**
     * Everything wrong with a chapter's {@code elements}.
     *
     * <h2>Why a chapter-level list needs its own walk</h2>
     *
     * <p>Because the codec is lenient by design and this is where an author is told what it let through.
     * The codec clamps an out-of-range number and ignores a field it does not know; a validator that did
     * neither would leave an author whose box is one pixel wide, or whose {@code fillColour} is spelled
     * with a {@code u}, with a picture that simply looks wrong and nothing anywhere to read.
     *
     * <p>Two of the checks here are ones only a validator can make. <b>An id has to be unique within its
     * chapter</b>, which is a fact about the file rather than about any one element, and <b>an image must
     * name exactly one source</b>, which the codec cannot refuse without refusing the whole document. The
     * rest — a colour, a click this build cannot run, a gate naming nothing — are reported here so the
     * message has a line rather than a file.
     */
    private static void checkElements(JsonDocument document, String path, Problems problems) {
        if (!document.has(path)) {
            return;
        }
        JsonElement raw = document.get(path).orElse(null);
        if (raw == null || !raw.isJsonArray()) {
            problems.error(document, path, "expected a list of elements, found " + Checks.kindOf(raw)
                    + ". Each entry needs a \"type\", one of " + quoted(ELEMENT_TYPES) + ".");
            return;
        }
        com.google.gson.JsonArray elements = raw.getAsJsonArray();
        Set<String> ids = new LinkedHashSet<>();
        for (int i = 0; i < elements.size(); i++) {
            checkElement(document, path + "[" + i + "]", elements.get(i), ids, problems);
        }
    }

    /** One element: its type first, because the type decides which fields are even checkable. */
    private static void checkElement(JsonDocument document, String at, JsonElement entry,
                                     Set<String> ids, Problems problems) {
        if (entry == null || !entry.isJsonObject()) {
            problems.error(document, at, "expected an element object, found " + Checks.kindOf(entry));
            return;
        }
        JsonObject object = entry.getAsJsonObject();

        JsonElement declared = object.get("type");
        if (declared == null || !declared.isJsonPrimitive() || !declared.getAsJsonPrimitive().isString()) {
            problems.error(document, at + ".type", "every element needs a \"type\" string, one of "
                    + quoted(ELEMENT_TYPES));
            return;
        }
        String type = declared.getAsString();

        Set<String> allowed = CanvasElement.fieldsOf(type);
        if (allowed.isEmpty()) {
            // An unknown type is one warning, and the payload is then left alone -- the same treatment an
            // unknown task type gets, and for the same reason: this build has no idea what those fields
            // are, so calling every one of them unknown would be a page of noise about a value the author
            // has already been told about once. See CanvasElement.fieldsOf.
            problems.warn(document, at + ".type", "unknown element type \"" + type + "\"\n"
                    + "    the element is kept, draws nothing, and its other fields are not checked"
                    + "\n    this build draws: " + quoted(ELEMENT_TYPES));
            return;
        }

        Checks.rejectUnknown(document, at, allowed, problems);
        checkElementIdentity(document, at, ids, problems);
        checkElementRequires(document, at, problems);

        switch (type) {
            case CanvasElement.TYPE_IMAGE -> checkImageElement(document, at, problems);
            case CanvasElement.TYPE_TEXT -> checkTextElement(document, at, problems);
            case CanvasElement.TYPE_LINE -> checkLineElement(document, at, problems);
            case CanvasElement.TYPE_RECT -> checkRectElement(document, at, problems);
            default -> {
                // Unreachable: `fieldsOf` answered for exactly these four, and answered empty for
                // everything else. Written as a no-op rather than omitted so the compiler keeps saying so.
            }
        }
    }

    /**
     * An element's id: present, well shaped, and not already used in this chapter.
     *
     * <p>The charset is {@link Checks#id}, which is the same rule a quest's id gets — one answer to "what
     * is an id", rather than two that agree until one of them changes. The uniqueness half is the check
     * only this walk can make, and it is a hard error rather than a warning: an element's id is what a
     * translation key, an editor operation and a duplicate report all name it by, so two elements sharing
     * one is a file in which the second can never be addressed.
     */
    private static void checkElementIdentity(JsonDocument document, String at, Set<String> ids,
                                             Problems problems) {
        Optional<String> id = Checks.id(document, at + ".id", problems);
        if (id.isEmpty()) {
            return;
        }
        if (!ids.add(id.get())) {
            problems.error(document, at + ".id", "two elements in this chapter share the id \"" + id.get()
                    + "\" - an element's id has to be unique within its chapter, because it is what a"
                    + " translation key and an edit both name it by");
        }
    }

    /** A gate, when there is one: what it names is resolved by the index, which can see every quest. */
    private static void checkElementRequires(JsonDocument document, String at, Problems problems) {
        if (!document.has(at + ".requires")) {
            return;
        }
        Checks.optionalString(document, at + ".requires", problems).ifPresent(name -> {
            if (name.isBlank()) {
                problems.error(document, at + ".requires", "\"requires\" is a quest's id or alias, and this"
                        + " one is empty - remove the field to draw the element unconditionally");
            }
        });
    }

    private static void checkImageElement(JsonDocument document, String at, Problems problems) {
        checkImageSource(document, at + ".image", problems);
        checkRange(document, at + ".width", CanvasElement.Image.MIN_EDGE, CanvasElement.Image.MAX_EDGE,
                problems);
        checkRange(document, at + ".height", CanvasElement.Image.MIN_EDGE, CanvasElement.Image.MAX_EDGE,
                problems);
        checkRange(document, at + ".alpha", CanvasElement.Image.MIN_ALPHA, CanvasElement.Image.MAX_ALPHA,
                problems);
        // `rotation` is deliberately absent: the codec wraps it, so every whole number is a legal turn and
        // there is nothing to be out of range. See Codecs.wrappedInt for why wrapping rather than clamping
        // is the only reading of an angle that is not a lie.
        // `corner` needs no check either: a boolean is a boolean, and anything else fails the codec with
        // the file's own line. `locked` is the same shape with one thing worth saying — it must be a real
        // boolean rather than a truthy word — so it is checked like the label's flags.
        Checks.optionalBool(document, at + ".locked", problems);
        checkColour(document, at + ".tint", problems);
        checkText(document, at + ".title", problems);
        checkClick(document, at + ".click", problems);
        checkLabel(document, at + ".label", problems);
    }

    private static void checkTextElement(JsonDocument document, String at, Problems problems) {
        requiredText(document, at + ".text", problems);
        checkDecimalRange(document, at + ".scale", CanvasElement.Text.MIN_SCALE,
                CanvasElement.Text.MAX_SCALE, problems);
        checkColour(document, at + ".color", problems);
        Checks.optionalBool(document, at + ".shadow", problems);
    }

    private static void checkLineElement(JsonDocument document, String at, Problems problems) {
        checkRange(document, at + ".width", CanvasElement.Line.MIN_WIDTH, CanvasElement.Line.MAX_WIDTH,
                problems);
        checkColour(document, at + ".color", problems);
        if (document.has(at + ".arrowhead")) {
            Checks.optionalString(document, at + ".arrowhead", problems)
                    .ifPresent(name -> checkEnum(document, at + ".arrowhead", name, ArrowEnds.class,
                            problems));
        }
    }

    private static void checkRectElement(JsonDocument document, String at, Problems problems) {
        checkRange(document, at + ".width", CanvasElement.Rect.MIN_EDGE, CanvasElement.Rect.MAX_EDGE,
                problems);
        checkRange(document, at + ".height", CanvasElement.Rect.MIN_EDGE, CanvasElement.Rect.MAX_EDGE,
                problems);
        checkRange(document, at + ".borderWidth", CanvasElement.Rect.MIN_BORDER,
                CanvasElement.Rect.MAX_BORDER, problems);
        checkColour(document, at + ".fillColor", problems);
        checkColour(document, at + ".borderColor", problems);
    }

    /**
     * Everything wrong with a chapter's {@code links}.
     *
     * <h2>Shape here, resolution in the index</h2>
     *
     * <p>Like the elements above it: this walk can say whether each link names its fields well, and
     * only the post-assembly pass can say whether the quest it points at exists. A link id has to be
     * unique within its chapter, which is the check only this walk can make; whether it collides
     * with a <i>quest</i> is a question about the whole tree, so that half lives in
     * {@code QuestIndex} beside the dangling-target check.
     */
    private static void checkLinks(JsonDocument document, String path, Problems problems) {
        if (!document.has(path)) {
            return;
        }
        JsonElement raw = document.get(path).orElse(null);
        if (raw == null || !raw.isJsonArray()) {
            problems.error(document, path, "expected a list of links, found " + Checks.kindOf(raw)
                    + ". Each entry names a quest with \"quest\", by id or alias.");
            return;
        }
        com.google.gson.JsonArray links = raw.getAsJsonArray();
        Set<String> ids = new LinkedHashSet<>();
        for (int i = 0; i < links.size(); i++) {
            checkLink(document, path + "[" + i + "]", links.get(i), ids, problems);
        }
    }

    /** One link: its fields first, because only known fields are checkable at all. */
    private static void checkLink(JsonDocument document, String at, JsonElement entry,
                                  Set<String> ids, Problems problems) {
        if (entry == null || !entry.isJsonObject()) {
            problems.error(document, at, "expected a link object, found " + Checks.kindOf(entry));
            return;
        }
        Checks.rejectUnknown(document, at, QuestLink.FIELDS, problems);

        // Present, well shaped, and not already used in this chapter. The charset is the same rule
        // a quest's id gets — one answer to "what is an id" — and the uniqueness half is the check
        // only this walk can make: a link's id is what an edit names it by, so two links sharing
        // one is a file in which the second can never be addressed.
        Optional<String> id = Checks.id(document, at + ".id", problems);
        if (id.isPresent() && !ids.add(id.get())) {
            problems.error(document, at + ".id", "two links in this chapter share the id \"" + id.get()
                    + "\" - a link's id has to be unique within its chapter, because it is what an"
                    + " edit names it by");
        }

        Checks.string(document, at + ".quest", problems).ifPresent(name -> {
            if (name.isBlank()) {
                problems.error(document, at + ".quest", "\"quest\" is a quest's id or alias, and this"
                        + " one is empty - remove the link to draw nothing, or name its target");
            }
        });

        if (document.has(at + ".shape")) {
            Checks.optionalString(document, at + ".shape", problems)
                    .ifPresent(name -> checkEnum(document, at + ".shape", name, QuestShape.class,
                            problems));
        }
        if (document.has(at + ".size")) {
            // A warning, not an error, for the reason the quest's own size gives: the codec clamps,
            // so refusing here would refuse a file that loads. The sentence says what the value was
            // read as, because that is the part the author needs.
            Checks.optionalInt(document, at + ".size", problems).ifPresent(size -> {
                if (size < QuestLayout.MIN_SIZE || size > QuestLayout.MAX_SIZE) {
                    problems.warn(document, at + ".size",
                            "size must be between " + QuestLayout.MIN_SIZE + " and "
                                    + QuestLayout.MAX_SIZE + ", found " + size + " - it is read as "
                                    + Math.max(QuestLayout.MIN_SIZE,
                                            Math.min(QuestLayout.MAX_SIZE, size)));
                }
            });
        }
    }

    /**
     * The two arms of a picture's source, and the one thing the codec cannot say: exactly one of them.
     *
     * <p>{@code ImageSource.CODEC} reads the file arm first, so an object carrying both would quietly lose
     * its sprite — the ordinary leniency of a loader that must load. This is the half that can attach a
     * message to it, and it names both keys rather than guessing which one was meant.
     */
    private static void checkImageSource(JsonDocument document, String at, Problems problems) {
        JsonElement element = document.get(at).orElse(null);
        if (element == null) {
            problems.error(document, at, "an image needs a source: { \"texture\": \"ns:textures/x.png\" }"
                    + " for a file, or { \"sprite\": \"ns:block/x\" } for a region of the block atlas");
            return;
        }
        if (!element.isJsonObject()) {
            problems.error(document, at, "expected { \"texture\": ... } or { \"sprite\": ... }, found "
                    + Checks.kindOf(element));
            return;
        }
        JsonObject source = element.getAsJsonObject();
        Checks.rejectUnknown(document, at, ImageSource.FIELDS, problems);

        boolean texture = source.has("texture");
        boolean sprite = source.has("sprite");
        if (texture && sprite) {
            problems.error(document, at, "this image names both a \"texture\" and a \"sprite\" - a file and"
                    + " a region of an atlas are different lookups, so keep the one it draws from");
        }
        else if (!texture && !sprite) {
            problems.error(document, at, "an image needs a source: \"texture\" for a file, or \"sprite\" for"
                    + " a region of the block atlas");
        }
        if (texture) {
            checkResourceId(document, at + ".texture", problems);
        }
        if (sprite) {
            // Not checked against the atlas, and that is a boundary rather than an omission: which sprites
            // exist is a client's resource set, and this validator runs on a dedicated server that has no
            // sheets at all. An id the atlas does not hold draws the game's missing-texture marker, which
            // is the report an author gets -- in the game, on the picture, where it is unmissable.
            checkResourceId(document, at + ".sprite", problems);
        }
    }

    /** How an image's title is painted, when it says. */
    private static void checkLabel(JsonDocument document, String at, Problems problems) {
        JsonElement element = document.get(at).orElse(null);
        if (element == null) {
            return;
        }
        if (!element.isJsonObject()) {
            problems.error(document, at, "expected an object of label properties, found "
                    + Checks.kindOf(element));
            return;
        }
        Checks.rejectUnknown(document, at, ElementLabel.FIELDS, problems);
        Checks.optionalBool(document, at + ".onImage", problems);
        Checks.optionalBool(document, at + ".shadow", problems);
        checkDecimalRange(document, at + ".inset", ElementLabel.MIN_INSET, ElementLabel.MAX_INSET, problems);
        for (String axis : new String[] {"hAlign", "vAlign"}) {
            if (document.has(at + "." + axis)) {
                Checks.optionalString(document, at + "." + axis, problems)
                        .ifPresent(name -> checkEnum(document, at + "." + axis, name,
                                ElementLabel.TextAlign.class, problems));
            }
        }
    }

    /**
     * What pressing an element does.
     *
     * <p>All seven names are readable and all seven run, so there is no refusal branch: a name no
     * version has is refused by the codec, and every name the codec reads has an arm below. An arm
     * added without a case here is a compile error rather than a silent gap, because the switch is
     * exhaustive over the enum.
     */
    private static void checkClick(JsonDocument document, String at, Problems problems) {
        JsonElement element = document.get(at).orElse(null);
        if (element == null) {
            return;
        }
        if (!element.isJsonObject()) {
            problems.error(document, at, "expected { \"type\": ..., \"data\": ... }, found "
                    + Checks.kindOf(element));
            return;
        }
        JsonObject click = element.getAsJsonObject();
        Checks.rejectUnknown(document, at, ClickAction.FIELDS, problems);

        Optional<String> declared = Checks.optionalString(document, at + ".type", problems);
        if (declared.isEmpty()) {
            // Absent, or not a string -- and either way nothing more can be said about the action.
            return;
        }
        String lowered = declared.get().toLowerCase(java.util.Locale.ROOT);
        if (click.has("type")) {
            checkEnum(document, at + ".type", declared.get(), ClickAction.Type.class, problems);
        }
        ClickAction.Type type = null;
        for (ClickAction.Type candidate : ClickAction.Type.values()) {
            if (candidate.name().toLowerCase(java.util.Locale.ROOT).equals(lowered)) {
                type = candidate;
            }
        }
        if (type == null) {
            // `checkEnum` has already listed the names there are, so adding a second message here would
            // be two reports of one mistake.
            return;
        }

        String data = Checks.optionalString(document, at + ".data", problems).orElse("");
        switch (type) {
            case OPEN_QUEST -> {
                if (data.isBlank()) {
                    problems.error(document, at + ".data", "open_quest needs a quest id or alias to open");
                }
            }
            case OPEN_URI -> checkUri(document, at + ".data", data, problems);
            case SHOW_RECIPE -> {
                if (data.isBlank()) {
                    problems.error(document, at + ".data", "show_recipe needs an item id to look up");
                }
                else if (Identifier.tryParse(data) == null) {
                    problems.error(document, at + ".data", "\"" + data + "\" is not an id this game can"
                            + " resolve - write namespace:path in lowercase, e.g. minecraft:blast_furnace");
                }
            }
            case SHOW_DOCS -> {
                // `<mod>,<book>[,<page>[,<anchor>]]`: the mod and the book name the shelf, and the rest
                // names the page on it. Checked for shape rather than existence, because whether a guide
                // book exists is a client's mod list, and this validator runs on a dedicated server that
                // has no catalogue at all.
                String[] parts = data.split(",", -1);
                if (parts.length < 2 || parts[0].isBlank() || parts[1].isBlank()) {
                    problems.error(document, at + ".data", "show_docs names <mod>,<book>[,<page>[,<anchor>]]"
                            + " - the mod and the book it opens, with the page after them");
                }
            }
            case RUN_COMMAND -> {
                if (data.isBlank()) {
                    problems.error(document, at + ".data", "run_command needs a command to run");
                }
            }
            case CUSTOM_EVENT -> {
                // A `namespace:path` id, checked for shape rather than listeners: whether a script
                // listens is a server's runtime, and this validator runs at load. An id that is not
                // one is refused here rather than silently never firing there.
                if (data.isBlank()) {
                    problems.error(document, at + ".data",
                            "custom_event needs an event id, namespace:path");
                }
                else if (Identifier.tryParse(data) == null) {
                    problems.error(document, at + ".data", "\"" + data + "\" is not an event id -"
                            + " write namespace:path in lowercase, e.g. my_pack:gate_opened");
                }
            }
            case NONE -> {
                // Carries no data.
            }
        }
    }

    /**
     * A URL, by the same rule the client opens one with.
     *
     * <p>Mirrored rather than approximated, and the reason is the failure it prevents: a validator that
     * accepted a scheme the opener refuses would let an author ship a link that passes every check and
     * then says "only http and https links open" when a player presses it. So the parse and the scheme test
     * are the same two steps {@code QuestBookScreen.openLink} takes, in the same order — a malformed
     * address is refused as malformed rather than as a wrong scheme.
     */
    private static void checkUri(JsonDocument document, String path, String url, Problems problems) {
        if (url.isBlank()) {
            problems.error(document, path, "open_uri needs a URL");
            return;
        }
        java.net.URI uri;
        try {
            uri = java.net.URI.create(url);
        }
        catch (IllegalArgumentException malformed) {
            problems.error(document, path, "\"" + url + "\" is not a valid address: "
                    + malformed.getMessage());
            return;
        }
        String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(java.util.Locale.ROOT);
        if (!scheme.equals("http") && !scheme.equals("https")) {
            problems.error(document, path, "open_uri opens http and https addresses only, and this one is"
                    + (scheme.isEmpty() ? " not an address with a scheme at all" : " \"" + scheme + "\""));
        }
    }

    /** A namespaced id in this game's spelling, which the codec would refuse the whole file over. */
    private static void checkResourceId(JsonDocument document, String path, Problems problems) {
        Optional<String> value = Checks.optionalString(document, path, problems);
        if (value.isEmpty()) {
            return;
        }
        if (Identifier.tryParse(value.get()) == null) {
            problems.error(document, path, "\"" + value.get() + "\" is not an id this game can resolve -"
                    + " write namespace:path in lowercase, e.g. minecraft:textures/gui/star.png for a file"
                    + " or minecraft:block/sculk for a sprite");
        }
    }

    /**
     * A colour, which is a hex string or a number.
     *
     * <p>The string arm is the one that can be wrong in a way nobody notices — {@code #A0A0A} is one digit
     * short, and a fill with no colour is invisible rather than obviously broken — so it is checked here
     * while the number arm is taken as read. {@link Argb} holds the vocabulary and the sentence, so what an
     * author is told here is what the codec's own message would have said.
     */
    private static void checkColour(JsonDocument document, String path, Problems problems) {
        JsonElement element = document.get(path).orElse(null);
        if (element == null) {
            return;
        }
        if (!element.isJsonPrimitive()) {
            problems.error(document, path, "expected a colour, found " + Checks.kindOf(element) + " - "
                    + Argb.spellings());
            return;
        }
        if (element.getAsJsonPrimitive().isString()) {
            String text = element.getAsString();
            if (Argb.parseHex(text).isEmpty()) {
                problems.error(document, path, "\"" + text + "\" is not a colour - " + Argb.spellings());
            }
            return;
        }
        if (!element.getAsJsonPrimitive().isNumber()) {
            problems.error(document, path, "expected a colour, found " + Checks.kindOf(element) + " - "
                    + Argb.spellings());
        }
    }

    /**
     * A whole number that has to sit between two bounds, reported rather than clamped.
     *
     * <p>The two halves work together, and this is the arrangement {@code minRequired} already has: the
     * <b>codec clamps</b>, so a document written for a build with wider bounds still loads and still draws
     * something rather than being refused wholesale; and the <b>validator reports</b>, so an author writing
     * for this build is told that their number was not the one that was read. Clamping alone would be
     * silent, and refusing alone would cost the file.
     */
    private static void checkRange(JsonDocument document, String path, int min, int max, Problems problems) {
        if (!document.has(path)) {
            return;
        }
        Checks.optionalInt(document, path, problems).ifPresent(value -> {
            if (value < min || value > max) {
                problems.error(document, path, "must be between " + min + " and " + max + ", found "
                        + value);
            }
        });
    }

    /** The same, for a fraction. See {@link #checkRange}. */
    private static void checkDecimalRange(JsonDocument document, String path, double min, double max,
                                          Problems problems) {
        if (!document.has(path)) {
            return;
        }
        JsonElement element = document.get(path).orElse(null);
        if (element == null || !element.isJsonPrimitive() || !element.getAsJsonPrimitive().isNumber()) {
            // Reported by whatever reads it as a number; saying so twice would be two messages for one
            // mistake, which is the shape of report an author learns to skim.
            return;
        }
        double value = element.getAsDouble();
        if (value < min || value > max) {
            problems.error(document, path, "must be between " + min + " and " + max + ", found " + value);
        }
    }

    private static void requiredText(JsonDocument document, String path, Problems problems) {
        if (!document.has(path)) {
            problems.error(document, path, "missing required field " + Checks.nameOf(path));
            return;
        }
        checkText(document, path, problems);
    }

    private static void checkText(JsonDocument document, String path, Problems problems) {
        checkText(document, path, problems, true);
    }

    /**
     * The text check, with the blank warning optional.
     *
     * @param warnIfBlank whether an empty string is worth reporting. True for a title or a subtitle —
     *     an empty one is a quest with no name. False inside a list of paragraphs, where a blank entry
     *     is how an author writes a line break: each entry is drawn as its own paragraph, so there is
     *     no other way to say "skip a line". That distinction was found the hard way, by the shipped
     *     questlines warning on every deliberate blank line in them.
     */
    private static void checkText(JsonDocument document, String path, Problems problems, boolean warnIfBlank) {
        JsonElement element = document.get(path).orElse(null);
        if (element == null) {
            return;
        }
        if (element.isJsonPrimitive() && element.getAsJsonPrimitive().isString()) {
            if (warnIfBlank && element.getAsString().isBlank()) {
                problems.warn(document, path, "this text is empty, so nothing will be shown");
            }
            return;
        }
        if (element.isJsonObject()) {
            JsonObject object = element.getAsJsonObject();
            Checks.rejectUnknown(document, path, Set.of("translate", "fallback"), problems);
            if (!object.has("translate")) {
                problems.error(document, path,
                        "a translatable text needs a \"translate\" key, e.g. "
                                + "{ \"translate\": \"quest.tenet.example\", \"fallback\": \"Example\" }");
            } else {
                Checks.string(document, path + ".translate", problems);
            }
            if (object.has("fallback")) {
                Checks.string(document, path + ".fallback", problems);
            } else {
                problems.warn(document, path + ".translate",
                        "no \"fallback\" - if a translation is ever missing this will show the raw key");
            }
            return;
        }
        problems.error(document, path, "expected text: either a string, or { \"translate\": ..., "
                + "\"fallback\": ... } - but found " + Checks.kindOf(element));
    }

    /**
     * A list of paragraphs, where a blank entry is legal and a list that is *all* blank is not.
     *
     * <p>An empty entry means a blank line, which is the only way an author can write one: each entry
     * is drawn as its own paragraph. So the per-paragraph blank warning is off here, and the question
     * worth asking moves to the whole list — a description where nothing at all would be shown.
     *
     * <p>Found by the shipped questlines reporting four warnings each on their deliberate line breaks,
     * which is the worst possible shape for a check to take: it was not wrong about the fact, it was
     * wrong about whether the fact was a problem, and a warning like that teaches an author to skim
     * output.
     */
    private static void checkTextList(JsonDocument document, String path, Problems problems) {
        var array = Checks.optionalArray(document, path, problems);
        if (array.isEmpty()) {
            return;
        }

        boolean anyText = false;
        for (int i = 0; i < array.get().size(); i++) {
            JsonElement element = array.get().get(i);
            if (!element.isJsonPrimitive() || !element.getAsJsonPrimitive().isString()
                    || !element.getAsString().isBlank()) {
                anyText = true;
            }
            checkText(document, path + "[" + i + "]", problems, false);
        }

        if (!array.get().isEmpty() && !anyText) {
            problems.warn(document, path, "every paragraph here is empty, so nothing will be shown");
        }
    }

    /**
     * A description the format accepts in either shape: a list of paragraphs, or one bare string.
     *
     * <p>Three codecs take the union — a chapter group's, a group's in either layout, and the folder
     * format's chapter — and their javadocs argue why. The validator has to accept both <b>because a
     * codec does</b>. A validator stricter
     * than the format is the worst of both worlds: the file decodes, so the format says it is fine, and
     * is then refused, so the tool says it is not — and the author has no way to tell which is
     * authoritative. This is the same fault as a validator that is too *lax*, just pointing the other
     * way: in both cases the two descriptions of the format have drifted and the message names the
     * wrong thing.
     *
     * <p>Found by a test written to assert both spellings work. The test believed the codec and failed
     * against the validator, which is the right way round for that to be discovered — the codec is the
     * thing that decides whether a file loads.
     */
    private static void checkTextOrList(JsonDocument document, String path, Problems problems) {
        JsonElement element = document.get(path).orElse(null);
        if (element == null) {
            return;
        }
        if (element.isJsonArray()) {
            checkTextList(document, path, problems);
            return;
        }
        // A bare string is one paragraph, so a blank one means nothing is shown at all — the same
        // question `checkTextList` asks of a list that is entirely blank, asked of the one-paragraph
        // case. Which is why this uses the warning-on-blank form rather than the list's tolerant one.
        checkText(document, path, problems);
    }

    /**
     * An alias has looser rules than an id: letters of either case, digits and underscores.
     *
     * <p>Ids stay lowercase — the lookup normalises, but the files do not, so a mixed-case id is
     * still an authoring error. Aliases accept uppercase because converted packs arrive with
     * uppercase hexadecimal names, and refusing them would refuse the pack. Length is the same
     * bound an id gets. See {@link QuestIndex} for why the two jobs are split that way.
     */
    private static void checkAlias(JsonDocument document, String path, String alias, Problems problems) {
        if (alias.isEmpty() || alias.length() > ChapterNaming.MAX_LENGTH) {
            problems.error(document, path, "an alias must be between 1 and "
                    + ChapterNaming.MAX_LENGTH + " characters");
            return;
        }
        for (int i = 0; i < alias.length(); i++) {
            char c = alias.charAt(i);
            if (!((c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9')
                    || c == '_')) {
                problems.error(document, path, "alias '" + alias + "' is not valid; only letters, "
                        + "digits and underscores are allowed");
                return;
            }
        }
    }

    private static <E extends Enum<E>> void checkEnum(JsonDocument document, String path, String value,
                                                      Class<E> type, Problems problems) {
        for (E constant : type.getEnumConstants()) {
            if (constant.name().equalsIgnoreCase(value)) {
                return;
            }
        }
        Set<String> valid = new LinkedHashSet<>();
        for (E constant : type.getEnumConstants()) {
            valid.add(constant.name().toLowerCase(java.util.Locale.ROOT));
        }
        problems.error(document, path, "'" + value + "' is not one of: " + String.join(", ", valid));
    }

    /** A boolean field's value, defaulting to false. For cross-field checks that need one. */
    private static boolean boolAt(JsonDocument document, String path) {
        return document.get(path)
                .filter(element -> element.isJsonPrimitive() && element.getAsJsonPrimitive().isBoolean())
                .map(element -> element.getAsBoolean())
                .orElse(false);
    }

    private static boolean isObject(JsonDocument document, String path, Problems problems) {
        JsonElement element = document.get(path).orElse(null);
        if (element == null) {
            return false;
        }
        if (!element.isJsonObject()) {
            problems.error(document, path, "expected an object, found " + Checks.kindOf(element));
            return false;
        }
        return true;
    }

    // ------------------------------------------------------------------
    // Paths
    // ------------------------------------------------------------------

    // Shared with QuestIndex so that both agree on where a quest is. If one of these changes, the
    // other's error messages move with it rather than silently pointing somewhere else.

    /**
     * Fields an older schema or a converted file may still carry, warned about rather than refused.
     *
     * <p>Each one is harmless because the codec ignores it — which is exactly why refusing the
     * file over it would be wrong. Anything else unknown is still an error: a typo must fail
     * loudly, and the names below are the closed list that does not. See {@link
     * #warnRetiredVersion} and {@link #warnRetiredTableFields} for what each name is.
     */
    private static final Set<String> RETIRED_VERSION = Set.of("version");

    /** The table roots' retired names, beside the version every per-kind root tolerates. */
    private static final Set<String> RETIRED_TABLE_FIELDS = Set.of("version", "id", "loot_crate");

    /**
     * Warns for a {@code version} where the folder layout already answers the format question.
     *
     * <p>A version-1 file carries {@code version} at its root, and that root keeps its own
     * strict rule (an error below 1, a warning past what this build reads). A per-kind file
     * split out of one may have kept it — and it means nothing there, because a file's format
     * is its position in the tree rather than a number an author remembers to update. So it is
     * a warning naming the removal, and the file still loads: the codec never read it.
     */
    private static void warnRetiredVersion(JsonDocument document, String path, Problems problems) {
        if (document.has(path + ".version")) {
            problems.warn(document, path + ".version",
                    "\"version\" means nothing here - remove it."
                            + " Only a version-1 file's root reads it; the folder layout says"
                            + " which format every other file is.");
        }
    }

    /**
     * Warns for the table roots' retired names.
     *
     * <p>A legacy reward table carries {@code id}; Tenet tables are named by their file, so the id is
     * not read — while an inline table is addressed by its {@code uid} handle, which is the rename
     * the warning offers there. A legacy table may carry {@code loot_crate}; Tenet has no crate home,
     * so the crate is not read while the table's entries still are. Refusing the file over either
     * would cost every entry in the table, and every quest reward pointing at it, over one
     * sub-object nothing reads.
     *
     * @param inline whether this root is an inline table rather than a table file: only the inline
     *               one has a use for {@code uid} (a table file's root {@code uid} is ignored, like
     *               the id), so only it is offered the rename
     */
    private static void warnRetiredTableFields(JsonDocument document, String path, Problems problems,
                                               boolean inline) {
        warnRetiredVersion(document, path, problems);
        if (document.has(path + ".id")) {
            problems.warn(document, path + ".id", inline
                    ? "\"id\" is not read: an inline table is addressed by its \"uid\" handle -"
                            + " rename this to \"uid\", or remove it."
                    : "\"id\" is not read: a table in its own file is named by the file,"
                            + " reward_tables/<name>.json - remove this.");
        }
        if (document.has(path + ".loot_crate")) {
            problems.warn(document, path + ".loot_crate",
                    "\"loot_crate\" has no Tenet home, so the crate is not read - but the table's"
                            + " entries still are. Loot crates are out of scope for migration: the"
                            + " physical crate, its opener and its drops are manual work.");
        }
    }

    /**
     * A field set plus {@code $schema}, for the root of a version-2 per-kind document.
     *
     * <p>Every kind allows it and none reads it: it is an editor's pointer at the schema in
     * {@code _schema/}, and the folder holding those is skipped by name so the walk never trips over it.
     */
    private static Set<String> withSchema(Set<String> fields) {
        Set<String> out = new HashSet<>(fields);
        out.add("$schema");
        return out;
    }

    public static String groupPath(int g) {
        return "$.chapterGroups[" + g + "]";
    }

    public static String chapterPath(int g, int c) {
        return groupPath(g) + ".chapters[" + c + "]";
    }

    public static String questPath(int g, int c, int q) {
        return chapterPath(g, c) + ".quests[" + q + "]";
    }

    private static Set<String> union(Set<String> a, Set<String> b) {
        Set<String> out = new HashSet<>(a);
        out.addAll(b);
        return out;
    }
}
