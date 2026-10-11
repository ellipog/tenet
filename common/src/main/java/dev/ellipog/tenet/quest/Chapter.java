package dev.ellipog.tenet.quest;

import com.google.gson.JsonObject;
import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;

import dev.ellipog.armature.api.data.Codecs;

import java.util.List;
import java.util.Optional;

/**
 * A group of quests drawn on one canvas.
 *
 * <pre>{@code
 * {
 *   "id": "stone_age",
 *   "title": "The Stone Age",
 *   "icon": { "item": "minecraft:cobblestone" },
 *   "defaultPrerequisiteMode": "all_completed",
 *   "progressionMode": "linear",
 *   "quests": [ ... ]
 * }
 * }</pre>
 *
 * <h2>The chapter-level defaults, and why they earn their place</h2>
 *
 * <p>{@code defaultPrerequisiteMode} is the legacy {@code default_quest_prerequisite_mode}. A
 * chapter of thirty quests in a chain would otherwise repeat {@code "prerequisiteMode":
 * "all_completed"} thirty times, and the one place it differed would be easy to miss.
 *
 * <p>{@code progressionMode} goes further: {@code linear} makes the <b>order of the quests list</b>
 * the progression, so a chain needs no {@code dependsOn} at all. Those two together mean a
 * straightforward chapter is a list of quests and nothing else.
 *
 * <p>{@code defaultConsumeItems} is the inheritance the plan called out from the legacy model: a
 * task can decide for itself whether taking the items is required, and a chapter can set the
 * default so that "this pack takes your resources" is one word rather than a decision made
 * thirty times. Defaults to false, because silently taking a player's items is the more surprising
 * of the two behaviours.
 *
 * <h2>{@code theme}: a chapter may dress itself, and it is a weak claim rather than a strong one</h2>
 *
 * <p>Optional, and absent means "no opinion" rather than "the default theme". That distinction is the
 * whole design. The setting a player chooses is the baseline; this is an <b>override</b> that lasts
 * while you are looking at this chapter and is released when you leave. So a gallery chapter can
 * demonstrate a theme by being clicked rather than by being explained, and a pack author's taste can
 * never become a setting the player cannot get out of — clicking the appearance control in the book's
 * sidebar always wins, and clears this at the same time.
 *
 * <p>Validated as a plain string here rather than against a list of theme names, and that is a
 * deliberate boundary rather than an omission: the theme catalogue lives on the client, and a
 * dedicated server has no business knowing what themes a client has. So an unrecognised name is
 * reported by the client that could not honour it, once, naming the chapter — which is the same
 * treatment a value that reached the wrong side would get anywhere else in this codebase.
 */
public record Chapter(
        String id,
        QuestText title,
        Optional<QuestText> subtitle,
        List<QuestText> description,
        Icon icon,
        List<String> aliases,
        PrerequisiteMode defaultPrerequisiteMode,
        ProgressionMode progressionMode,
        boolean defaultConsumeItems,
        /**
         * How this chapter's dependency lines are drawn unless a line overrides it.
         *
         * <p>Any subset of the four axes; the rest come from {@link DependencyStyle#BUILT_IN}. An
         * override on a single line layers over this, which is what makes "all of this chapter's lines
         * are curved" one choice rather than forty-six.
         */
        DependencyStyle dependencyStyle,
        Optional<String> theme,
        /**
         * Token-level overrides for the theme above — a {@code ThemePatch} as the toolkit writes it.
         *
         * <p>Raw JSON rather than a parsed patch, because the parser lives on the client and a dedicated
         * server has no business understanding colours: the model carries the object, the validator
         * checks it with the toolkit's own tolerant reader (so a bad token is reported at the author's
         * line), and the client composes it over the named theme when it draws. Absent means the named
         * theme stands alone.
         */
        Optional<JsonObject> themePatch,
        /**
         * Whether this chapter's quests hand their rewards over the moment they complete.
         *
         * <p>The chapter rung of the auto-claim ladder, and the reason it exists: fifty early-game
         * quests should be one line in the chapter file rather than fifty settings. A quest overrides
         * it, a reward's own {@code auto} overrides that, and {@code DEFAULT} defers to the pack
         * setting in {@code index.json}. Rewards that need a decision are never auto-granted whatever
         * this says — see {@link dev.ellipog.tenet.quest.reward.RewardAutoClaim}.
         */
        dev.ellipog.tenet.quest.reward.RewardAutoClaim autoClaim,
        /**
         * This chapter's own place in the progression: what it waits on, what finishes it, and whether
         * it is shown before then.
         *
         * <p>Grouped into {@link ChapterRules} for the mundane reason that record gives — the codec's
         * sixteen components — and the real one: these five are about how the chapter relates to the
         * <i>rest of the questline</i> rather than about what it holds, which is also why the editor
         * shows them in one section.
         *
         * <p>Read {@code rules()} rather than a delegate per field, and note what that avoids: a
         * {@code prerequisiteMode()} accessor here would sit one letter from
         * {@link #defaultPrerequisiteMode()}, which means the opposite thing. See {@link ChapterRules}.
         */
        ChapterRules rules,
        List<Quest> quests,
        /**
         * Everything drawn on this chapter's canvas that is not a quest: pictures, labels, lines, boxes,
         * and markers pointing at other quests.
         *
         * <p>Decoration and markers, and nothing else — neither holds progress, gates anything or is
         * ever counted for this chapter's completion. What they are for is what a wall of unlabelled
         * nodes cannot say: which cluster is a tech tier, where one section ends and the next begins,
         * what the chapter is called when the sidebar is folded away, and which way a gate points.
         * See {@link CanvasElement} and {@link QuestLink}.
         *
         * <p>Grouped into {@link ChapterCanvas} for the mundane reason that record gives — the codec's
         * sixteen components — and the JSON stays flat regardless: {@code elements} and {@code links}
         * sit beside {@code quests}, as if they were fields of their own.
         *
         * <p>Last rather than first because {@code quests} is what the chapter <i>is</i>; this is what is
         * drawn behind and among it.
         */
        ChapterCanvas canvas
) {

    /**
     * The fields this chapter declares itself: everything but its gate, which {@link ChapterRules}
     * declares beside its own codec, and its canvas, which {@link ChapterCanvas} declares beside
     * its own.
     */
    private static final java.util.Set<String> OWN_FIELDS = java.util.Set.of(
            "id", "title", "subtitle", "description", "icon", "aliases", "defaultPrerequisiteMode",
            "progressionMode", "defaultConsumeItems", "dependencyStyle", "theme", "themePatch", "autoClaim",
            "quests");

    /**
     * The field names this contributes, for the validator to allow.
     *
     * <p>Declared here rather than copied into the validator, for the reason {@link ChapterGroup#FIELDS}
     * gives at length: a second copy is a second thing to remember, and forgetting it produces a field
     * the codec reads and the validator calls unknown. {@link ChapterManifest} declares the same set,
     * because a chapter manifest and the chapter it becomes describe the same object.
     *
     * <p>The gate's five names are <b>taken</b> from {@link ChapterRules#FIELDS} rather than written
     * again here, which is one copy fewer of a list that would otherwise appear three times — here, in
     * {@link ChapterManifest} and in the group that owns the fields. The manifest's own copy is
     * deliberate and pinned by a test; a third inside the record those names are declared in would only
     * be a third thing to forget. The canvas's two names come from {@link ChapterCanvas#FIELDS} the
     * same way, for the same reason.
     */
    public static final java.util.Set<String> FIELDS = java.util.stream.Stream
            .concat(java.util.stream.Stream.concat(OWN_FIELDS.stream(), ChapterRules.FIELDS.stream()),
                    ChapterCanvas.FIELDS.stream())
            .collect(java.util.stream.Collectors.toUnmodifiableSet());

    /** Finds a quest by id or alias. */
    public Optional<Quest> quest(String idOrAlias) {
        return quests.stream().filter(quest -> quest.matches(idOrAlias)).findFirst();
    }

    /**
     * Whether {@code idOrAlias} refers to this chapter.
     *
     * <p>Without regard to letter case, because lookups are. See {@link QuestIndex}.
     */
    public boolean matches(String idOrAlias) {
        return id.equalsIgnoreCase(idOrAlias)
                || aliases.stream().anyMatch(alias -> alias.equalsIgnoreCase(idOrAlias));
    }

    /** The index a quest sits at, or -1. Needed by linear progression, which cares about order. */
    public int indexOf(Quest quest) {
        for (int i = 0; i < quests.size(); i++) {
            if (quests.get(i) == quest) {
                return i;
            }
        }
        return -1;
    }

    /** The quests before {@code index} in the list — the ones linear progression waits on. */
    public List<Quest> questsBefore(int index) {
        if (index <= 0) {
            return List.of();
        }
        return quests.subList(0, Math.min(index, quests.size()));
    }

    /**
     * This chapter's elements in the order they are drawn: by {@code order}, ties by declaration.
     *
     * <p>{@link CanvasElement#inDrawOrder} holds the rule and the argument for it, because the client needs
     * the same answer about a list it built from the tree rather than from a chapter. This is the chapter's
     * way in, so a caller holding a chapter does not have to know that.
     */
    public List<CanvasElement> elementsInDrawOrder() {
        return CanvasElement.inDrawOrder(elements());
    }

    /**
     * Everything drawn on this chapter's canvas that is not a quest.
     *
     * <p>Kept so no caller learns {@link #canvas()}'s name: the grouping is a codec's answer to a
     * codec's limit, and the callers predate it.
     */
    public List<CanvasElement> elements() {
        return canvas.elements();
    }

    /**
     * The chapter's markers pointing at other quests, in declaration order.
     *
     * <p>Like {@link #elements()}, and for the same reason: the grouping is not a concept an author
     * meets, so neither is the accessor that would name it.
     */
    public List<QuestLink> links() {
        return canvas.links();
    }

    public static final Codec<Chapter> CODEC = RecordCodecBuilder.create(instance -> instance.group(
            Codec.STRING.fieldOf("id").forGetter(Chapter::id),
            QuestText.CODEC.fieldOf("title").forGetter(Chapter::title),
            QuestText.CODEC.optionalFieldOf("subtitle").forGetter(Chapter::subtitle),
            QuestText.CODEC.listOf().optionalFieldOf("description", List.of()).forGetter(Chapter::description),
            Icon.CODEC.optionalFieldOf("icon", Icon.DEFAULT_ICON).forGetter(Chapter::icon),
            Codec.STRING.listOf().optionalFieldOf("aliases", List.of()).forGetter(Chapter::aliases),
            PrerequisiteMode.CODEC.optionalFieldOf("defaultPrerequisiteMode", PrerequisiteMode.ALL_COMPLETED)
                    .forGetter(Chapter::defaultPrerequisiteMode),
            // FLEXIBLE is the default deliberately. A chapter where declaring a dependency silently
            // did nothing, because the chapter was linear, would be a confusing thing to debug.
            ProgressionMode.CODEC.optionalFieldOf("progressionMode", ProgressionMode.FLEXIBLE)
                    .forGetter(Chapter::progressionMode),
            Codec.BOOL.optionalFieldOf("defaultConsumeItems", false).forGetter(Chapter::defaultConsumeItems),
            DependencyStyle.CODEC.optionalFieldOf("dependencyStyle", DependencyStyle.UNSET)
                    .forGetter(Chapter::dependencyStyle),
            Codec.STRING.optionalFieldOf("theme").forGetter(Chapter::theme),
            Codecs.jsonObject().optionalFieldOf("themePatch").forGetter(Chapter::themePatch),
            dev.ellipog.tenet.quest.reward.RewardAutoClaim.CODEC
                    .optionalFieldOf("autoClaim", dev.ellipog.tenet.quest.reward.RewardAutoClaim.DEFAULT)
                    .forGetter(Chapter::autoClaim),
            // A MapCodec, so dependsOn/prerequisiteMode/minRequired/completesWhen/
            // hideUntilDependenciesComplete are flat on the chapter in JSON, as a quest's rules are flat
            // on the quest.
            ChapterRules.MAP_CODEC.forGetter(Chapter::rules),
            Quest.CODEC.listOf().optionalFieldOf("quests", List.of()).forGetter(Chapter::quests),
            // The canvas, flat: `elements` and `links` sit on the chapter, as if they were fields of
            // their own. See ChapterCanvas for why the grouping is invisible in JSON.
            ChapterCanvas.MAP_CODEC.forGetter(Chapter::canvas)
    ).apply(instance, Chapter::new));
}
