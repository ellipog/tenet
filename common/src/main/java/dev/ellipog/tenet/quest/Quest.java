package dev.ellipog.tenet.quest;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import dev.ellipog.tenet.quest.reward.RewardTypes;
import dev.ellipog.tenet.quest.task.TaskTypes;

import net.minecraft.resources.ResourceLocation;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * One quest.
 *
 * <p>In JSON:
 *
 * <pre>{@code
 * {
 *   "id": "punch_a_tree",
 *   "title": "Punch a Tree",
 *   "description": ["Or don't. It's your world."],
 *   "icon": { "item": "minecraft:oak_log", "count": 1 },
 *   "x": 0, "y": 0,
 *   "tasks":  [ { "type": "tenet:item", "item": "minecraft:oak_log", "count": 8 } ],
 *   "rewards": [ { "type": "tenet:item", "item": "minecraft:wooden_axe", "count": 1 } ]
 * }
 * }</pre>
 *
 * <p>The icon is an {@link Icon}: an item, a texture file, an atlas region, or an entity drawn as its egg. A bare
 * item object is the item arm, so every file written before the union reads unchanged.
 *
 * <h2>Three things here that the legacy format does not have</h2>
 *
 * <p><b>{@code aliases}.</b> Progress is stored against a quest's id. Change the id — which every
 * author does, once, after realising {@code stoneage_quest_2} was a bad name — and every player
 * loses that quest. An alias list means the old id still resolves, so a rename costs nothing.
 *
 * <p><b>{@code minRequired}.</b> The four {@link PrerequisiteMode} values cover the shapes anyone
 * actually wants, except one: "any three of these five". A count covers it, and setting it makes the
 * mode irrelevant.
 *
 * <p><b>{@code exclusiveGroup}.</b> The legacy format has this too, so it is not a novelty — but it is
 * grouped into {@link QuestRules} here rather than being another top-level field competing for
 * attention with the quest's actual content.
 *
 * <p>Note {@code description} is a <i>list</i> of paragraphs. A single string with {@code \n} in it
 * is what the legacy format takes, and it is the reason its quest descriptions end up as one
 * undifferentiated wall of text.
 *
 * <h2>Where the fields live</h2>
 *
 * <p>The flags are inside {@link QuestRules} because {@code RecordCodecBuilder} caps out at sixteen
 * components and this record has thirteen plus those fifteen. The accessors below delegate, so callers
 * write {@code quest.repeatable()} and never see the grouping.
 */
public record Quest(
        String id,
        QuestText title,
        Optional<QuestText> subtitle,
        List<QuestText> description,
        Icon icon,
        QuestLayout layout,
        List<String> aliases,
        List<QuestRef> dependencies,
        /**
         * Per-line overrides, keyed by the dependency they style.
         *
         * <p>Beside {@code dependsOn} rather than inside it, and that is a format decision rather than a
         * storage one: a dependency is a name, and has been since the first file — turning the entries
         * into objects to carry a style would break every reader of every pack for a field most edges
         * never set. A map keyed by the dependency id leaves the list alone and disappears when unused.
         */
        Map<String, DependencyStyle> dependencyLines,
        Optional<PrerequisiteMode> prerequisiteMode,
        int minRequired,
        /**
         * Whether this quest's tasks may be worked on before its dependencies are satisfied.
         *
         * <p>the legacy {@code flexible} progression mode, as a flag rather than a mode: tasks
         * accumulate while the gate is shut, completion waits for it to open, and already-maxed
         * tasks finish on the first tick after it does. A quest that says nothing defers to its
         * chapter's {@code defaultFlexibleProgress} — either true makes the quest flexible, which
         * is why a migration tool inlines the resolved value onto each quest and leaves the
         * chapter default off.
         *
         * <p>Top-level rather than in {@link QuestRules} for a mundane reason: the rules record is
         * at sixteen codec components, which is all {@code RecordCodecBuilder} takes. It sits
         * beside {@code prerequisiteMode} and {@code minRequired} because it qualifies the same
         * thing they do — what "waiting on dependencies" means.
         *
         * <p>Do not confuse this with the chapter's {@code progressionMode}: that one chains a
         * chapter's quest list in order (flexible/linear list chaining), and this one is about
         * whether <i>dependency edges</i> block task progress. Two different axes.
         */
        boolean flexibleProgress,
        List<QuestTask> tasks,
        List<QuestReward> rewards,
        QuestRules rules,
        /**
         * How this quest presents itself: how wide its card wants to be, which of its outgoing
         * edges are drawn, and whether its completion is announced.
         *
         * <p>Grouped for the mundane reason {@link QuestRules} gives — the codec's sixteen
         * components — and the JSON stays flat regardless: {@code minWidth},
         * {@code hideDependentLines} and {@code disableToast} sit on the quest beside every other
         * flag. Read the delegates below rather than this field; the grouping is a codec's answer
         * to a codec's limit, not a concept an author meets.
         */
        QuestPresentation presentation
) {

    /** A quest with nothing in it. The starting point for the editor's "new quest" button. */
    public static Quest blank(String id, QuestText title) {
        return new Quest(id, title, Optional.empty(), List.of(), Icon.DEFAULT_ICON, QuestLayout.DEFAULT,
                List.of(), List.of(), Map.of(), Optional.empty(), 0, false, List.of(), List.of(),
                QuestRules.DEFAULT, QuestPresentation.DEFAULT);
    }

    // ------------------------------------------------------------------
    // Delegated flags, so the grouping stays an implementation detail
    // ------------------------------------------------------------------

    public boolean repeatable() {
        return rules.repeatable();
    }

    public int repeatCooldownTicks() {
        return rules.repeatCooldownTicks();
    }

    public boolean sequentialTasks() {
        return rules.sequentialTasks();
    }

    /**
     * Whether this quest's tasks must be done in order, with the chapter's default for a quest
     * that says nothing.
     *
     * <p>Either true makes the quest sequential — there is no opt-out, which is why a migration
     * tool inlines the resolved value when the chapter default is absent. The legacy
     * {@code require_sequential_tasks} on the chapter.
     */
    public boolean sequentialTasks(boolean chapterDefault) {
        return sequentialTasks() || chapterDefault;
    }

    /**
     * Whether this quest is marked a side quest.
     *
     * <p>A marker, not a gate: an optional quest gates the quests that depend on it exactly
     * like any other quest — the legacy `optional` only excuses a quest from chapter
     * completion, never from a prerequisite rule. See {@link QuestRules#optional} and
     * {@link dev.ellipog.tenet.progress.ProgressionEngine}.
     */
    public boolean optional() {
        return rules.optional();
    }

    /** The auto-claim mode in force for this quest; see {@link QuestRules#autoClaim}. */
    public dev.ellipog.tenet.quest.reward.RewardAutoClaim autoClaim(
            dev.ellipog.tenet.quest.reward.RewardAutoClaim fallback) {
        return rules.autoClaim(fallback);
    }

    public boolean invisible() {
        return rules.invisible();
    }

    /**
     * Whether the book draws this quest's name under its node.
     *
     * <p>False by default. A node is an icon; a canvas of fifty names is a wall of text, and the name
     * is available on hover for every quest whether this is set or not. An author asks for the ones
     * worth naming.
     */
    public boolean showTitle() {
        return rules.showTitle();
    }

    public Optional<String> exclusiveGroup() {
        return rules.exclusiveGroup();
    }

    /**
     * The stage a player needs for this quest to be open to them, if any.
     *
     * <p>Per player rather than per team, because a stage is -- see {@code QuestRules#requiresStage}. The
     * gate is checked where a player's own view is built (they see the quest locked) and where a quest could
     * be finished or collected, so a quest with a shut gate cannot complete or pay out for a player who
     * never had the stage. A pack that wants a whole party gated grants the stage to every member, which is
     * how it does that for anything else too -- or names {@link #requiresStageTeam} and grants once to
     * the team.
     */
    public Optional<ResourceLocation> requiresStage() {
        return rules.requiresStage();
    }

    /**
     * The minimum width of this quest's detail panel, or 0 when unset.
     *
     * <p>Zero defers to the chapter's {@code defaultMinWidth}; either set wins over the panel
     * kind's default. See {@link QuestPresentation}.
     */
    public int minWidth() {
        return presentation.minWidth();
    }

    /**
     * Whether the dependency lines <i>leaving</i> this quest for its dependants are drawn.
     *
     * <p>The outgoing half of {@code hideDependencyLines}: that flag hides the lines arriving at
     * a quest, this one hides the lines it sends on. See {@link QuestPresentation}.
     */
    public boolean hideDependentLines() {
        return presentation.hideDependentLines();
    }

    /**
     * Whether completing this quest raises no toast.
     *
     * <p>the legacy {@code disable_toast} on the quest. ORed with the silent auto-claim modes
     * where notices are decided; see {@link QuestPresentation}.
     */
    public boolean disableToast() {
        return presentation.disableToast();
    }

    /**
     * Whether this quest's rewards keep flowing while the team's payouts are held.
     *
     * <p>the legacy {@code ignore_reward_blocking} on the quest. Either this or a reward's own
     * flag exempts that reward; see {@link QuestPresentation} and
     * {@link dev.ellipog.tenet.progress.ProgressService#isBlocked}.
     */
    public boolean ignoreRewardBlocking() {
        return presentation.ignoreRewardBlocking();
    }

    /**
     * Whether the quest's stage gate reads the team's stages rather than the player's own.
     *
     * <p>Qualifies {@link #requiresStage}: one member's induction then opens the quest for
     * everybody. See {@link QuestPresentation}.
     */
    public boolean requiresStageTeam() {
        return presentation.requiresStageTeam();
    }

    /**
     * Whether this quest hides itself from recipe viewers, if it has an opinion.
     *
     * <p>the legacy {@code disable_recipe_mod} on the quest. A tristate: absent defers to the
     * file's {@code defaultDisableRecipeMod} — which is what every file written before this field
     * existed says — so callers that need an answer take {@link #showInRecipeMod}. See
     * {@link QuestPresentation}.
     */
    public Optional<Boolean> disableRecipeMod() {
        return presentation.disableRecipeMod();
    }

    /**
     * Whether this quest hides itself from recipe viewers, with the file's default for a quest
     * that says nothing.
     *
     * <p>The quest's own flag wins when set; otherwise the file decides. An explicit false opts
     * back out of a file that hides its quests by default — which is why the file stores a
     * tristate rather than a boolean, and why the editor offers all three states.
     */
    public boolean effectiveDisableRecipeMod(boolean fileDefault) {
        return disableRecipeMod().orElse(fileDefault);
    }

    /**
     * Whether recipe viewers (JEI, REI, EMI) list this quest.
     *
     * <p>the legacy {@code showInRecipeMod}: the negation of the effective flag above. The viewer
     * content is built from this answer rather than from the tristate, because the client holds no
     * file record to resolve one from — the server resolves it onto the wire.
     */
    public boolean showInRecipeMod(boolean fileDefault) {
        return !effectiveDisableRecipeMod(fileDefault);
    }

    /**
     * Whether this quest wears no lock mark of its own on the canvas.
     *
     * <p>the legacy {@code hide_lock_icon} on the quest: the quest's half of the file's
     * {@code showLockIcons}. Either silence wins — see {@link QuestVisibility#drawsLockMark} for
     * the answer the canvas actually draws with. Absent defers to the file, which is what every
     * file written before this field existed says.
     */
    public boolean hideLockIcon() {
        return presentation.hideLockIcon();
    }

    /**
     * Words this quest answers to in lookups by tag.
     *
     * <p>the legacy {@code tags}, present on every object. A lookup of {@code "#tag"} resolves
     * to the first quest, chapter or group carrying it -- see {@link QuestIndex}. Each tag is
     * {@code ^[a-z0-9_]{1,64}$}, the same rule an id follows.
     */
    public List<String> tags() {
        return presentation.tags();
    }

    /**
     * The guide book page this quest belongs to, or empty when it names none.
     *
     * <p>the legacy {@code guide_page}, as a plain string. Tenet has no guide integration: the
     * quest card shows this as a reference and nothing reads it further. Empty means absent,
     * which is what every file written before this field existed says.
     */
    public String guidePage() {
        return presentation.guidePage();
    }

    // ------------------------------------------------------------------

    /**
     * The prerequisite mode in force for this quest.
     *
     * @param chapterDefault what the chapter says, used when the quest does not say
     */
    public PrerequisiteMode prerequisiteMode(PrerequisiteMode chapterDefault) {
        return prerequisiteMode.orElse(chapterDefault);
    }

    /**
     * Whether {@code idOrAlias} refers to this quest.
     *
     * <p>Checked against the id and every alias without regard to letter case, because lookups
     * are: a self-dependency written in the wrong case is still a self-dependency. See
     * {@link QuestIndex}.
     */
    public boolean matches(String idOrAlias) {
        return id.equalsIgnoreCase(idOrAlias)
                || aliases.stream().anyMatch(alias -> alias.equalsIgnoreCase(idOrAlias));
    }

    /** How many dependencies must be satisfied, given the effective mode. {@code minRequired} wins when set. */
    public int requiredCount(PrerequisiteMode effectiveMode) {
        return PrerequisiteMode.requiredCount(effectiveMode, minRequired, dependencies.size());
    }

    /**
     * How many tasks must be done for the quest to complete.
     *
     * <p>Every task that is not marked optional — with the one exception the plan names: if every
     * task is optional, then any one of them completes the quest. A quest where nothing is required
     * would otherwise complete itself the moment it unlocked.
     */
    public int requiredTaskCount() {
        long mandatory = tasks.stream().filter(task -> !task.optional()).count();
        if (mandatory > 0) {
            return (int) mandatory;
        }
        // All optional: one will do, unless there are none at all, in which case there is nothing
        // to do and the quest is completable by declaration.
        return tasks.isEmpty() ? 0 : 1;
    }

    /** Whether {@code index} is unlocked, given the tasks before it. Always true when not sequential. */
    public boolean isTaskUnlocked(int index, java.util.function.IntPredicate earlierTaskDone) {
        return isTaskUnlocked(index, earlierTaskDone, false);
    }

    /**
     * The same, with the chapter's {@code defaultSequentialTasks} for a quest that says nothing.
     *
     * <p>The engine calls this overload: a quest that says nothing follows its chapter, and either
     * true locks later tasks until earlier ones are done. The single-argument overload is the
     * quest's own answer, for callers with no chapter to hand (commands, tests).
     */
    public boolean isTaskUnlocked(int index, java.util.function.IntPredicate earlierTaskDone,
                                  boolean chapterDefault) {
        if (!sequentialTasks(chapterDefault)) {
            return true;
        }
        for (int earlier = 0; earlier < index; earlier++) {
            if (!earlierTaskDone.test(earlier)) {
                return false;
            }
        }
        return true;
    }

    public static final Codec<Quest> CODEC = RecordCodecBuilder.create(instance -> instance.group(
            Codec.STRING.fieldOf("id").forGetter(Quest::id),
            QuestText.CODEC.fieldOf("title").forGetter(Quest::title),
            QuestText.CODEC.optionalFieldOf("subtitle").forGetter(Quest::subtitle),
            QuestText.CODEC.listOf().optionalFieldOf("description", List.of()).forGetter(Quest::description),
            Icon.CODEC.optionalFieldOf("icon", Icon.DEFAULT_ICON).forGetter(Quest::icon),
            // A MapCodec, so x/y/shape/size are flat on the quest in JSON.
            QuestLayout.MAP_CODEC.forGetter(Quest::layout),
            Codec.STRING.listOf().optionalFieldOf("aliases", List.of()).forGetter(Quest::aliases),
            QuestRef.CODEC.listOf().optionalFieldOf("dependsOn", List.of()).forGetter(Quest::dependencies),
            // Keyed by dependency id. Absent means every line follows the chapter's style, which is what
            // every file written before this field existed says.
            Codec.unboundedMap(Codec.STRING, DependencyStyle.CODEC)
                    .optionalFieldOf("dependencyLines", Map.of()).forGetter(Quest::dependencyLines),
            PrerequisiteMode.CODEC.optionalFieldOf("prerequisiteMode").forGetter(Quest::prerequisiteMode),
            Codec.intRange(0, 64).optionalFieldOf("minRequired", 0).forGetter(Quest::minRequired),
            Codec.BOOL.optionalFieldOf("flexibleProgress", false).forGetter(Quest::flexibleProgress),
            // Accessor methods rather than constants: caching these in a static field is what caused a
            // class-initialisation cycle that compiled cleanly and failed only at runtime. See the note
            // in QuestTask, which explains it in full.
            TaskTypes.dispatchCodec().listOf().optionalFieldOf("tasks", List.of()).forGetter(Quest::tasks),
            RewardTypes.dispatchCodec().listOf().optionalFieldOf("rewards", List.of()).forGetter(Quest::rewards),
            // Also a MapCodec: the flags stay flat on the quest.
            QuestRules.MAP_CODEC.forGetter(Quest::rules),
            // And this one: the presentation flags stay flat for the same reason. The sixteenth
            // component — this record is full, and the next quest-level group needs a new home
            // rather than a seventeenth field.
            QuestPresentation.MAP_CODEC.forGetter(Quest::presentation)
    ).apply(instance, Quest::new));
}
