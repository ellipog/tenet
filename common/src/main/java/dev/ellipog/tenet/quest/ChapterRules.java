package dev.ellipog.tenet.quest;

import com.mojang.serialization.Codec;
import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;

import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * A chapter's own progression: what it waits on, what finishes it, and whether it is shown before then.
 *
 * <h2>Why these five are grouped rather than fields of {@link Chapter}</h2>
 *
 * <p>The same mundane reason {@link QuestRules} is grouped, with the same real one behind it.
 * {@code RecordCodecBuilder} takes at most sixteen components and {@link Chapter} is a container of
 * fourteen, so five more inline fields do not fit. They belong together anyway: everything here is
 * about how a chapter relates to the <i>rest of the progression</i> rather than about what it holds.
 *
 * <p>In JSON the fields are <b>flat</b> on {@code chapter.json} — {@code "dependsOn": [...]}, not
 * {@code "rules": { ... }} — because this is a {@link MapCodec}, exactly as a quest's flags are flat
 * on the quest. An author should not have to nest an object to say what a chapter waits for.
 *
 * <h2>A chapter's gate, and the field it is not</h2>
 *
 * <p>{@link #prerequisiteMode} is the rule over this chapter's <b>own</b> dependencies: how many of
 * the chapters in {@link #dependsOn} must be satisfied, and whether "satisfied" means completed or
 * merely started. It is deliberately <b>not</b> {@link Chapter#defaultPrerequisiteMode()}, which is
 * the opposite direction — the mode a quest in this chapter inherits for its quest dependencies. The
 * two names sit one accessor apart, which is why the accessors are spelled out rather than left to a
 * reader to infer: {@code chapter.rules().prerequisiteMode()} is this chapter waiting on others, and
 * {@code chapter.defaultPrerequisiteMode()} is a quest inside it waiting on quests.
 *
 * <p>{@link #minRequired} is the same field a quest has, read the same way: it replaces the mode's
 * <b>count</b> and never its bar, so {@code one_started} with {@code minRequired: 2} is "any two of
 * these chapters, started". A value above the size of {@link #dependsOn} is refused at load, the way
 * a quest's is.
 *
 * <h2>{@code completesWhen}: what "this chapter is finished" means, and why it is declared</h2>
 *
 * <p>A chapter is <b>completed</b> when every quest named here is completed. It cannot be derived from
 * "all of its quests are done", and the reason is not a matter of taste: a chapter may hold an
 * {@code exclusiveGroup} — where completing one quest permanently locks its siblings — or a
 * {@code maxCompletableDependents} cap that does the same to a branch. A rule of "every quest in the
 * chapter" would therefore be unsatisfiable in exactly the chapters that use those two fields, and
 * every chapter depending on them would lock forever with nothing to point at. So completion is
 * declared, and a dependency whose bar is <i>completed</i> on a chapter that declares nothing here is
 * reported at load rather than discovered as a chapter that never opens.
 *
 * <p>The quests named may live in any chapter, and are resolved by id or alias like every other
 * reference in this format. A repeatable quest named here counts after its <b>first</b> completion and
 * stays counted however many times it is repeated, which is the same reading a quest dependency gets.
 *
 * <h2>{@code hideUntilDependenciesComplete}: hiding is presentation, the gate is not</h2>
 *
 * <p>The chapter's gate is enforced by the engine whatever this says: a quest in a chapter whose
 * dependencies are unmet cannot be started, finished or claimed. This flag decides only whether the
 * <b>row</b> is drawn before that — {@code true} removes the chapter from the book for a reader until
 * its gate is met, {@code false} (the default) lists it dimmed with what it is waiting for. That split
 * is the same one the quest reveal family makes, and for the same reason: an author needs to keep
 * working on a chapter whose contents are not in play yet, so the flag is honoured by the reader's
 * book and never by the editor.
 */
public record ChapterRules(
        /**
         * The chapters this one waits on, by id or alias.
         *
         * <p>A {@link ChapterRef} rather than a plain string for the reason that record gives: a
         * chapter and a quest may share an id, so "a dependency" that could be either is a shape the
         * compiler would accept and the loader could not disambiguate.
         */
        List<ChapterRef> dependsOn,
        /**
         * The rule over {@link #dependsOn}: how many must be satisfied, and what "satisfied" means.
         *
         * <p>Not {@link Chapter#defaultPrerequisiteMode()} — see the class note. Defaults to
         * {@code all_completed}, the reading that asks the most, because a chapter gate that let
         * something through early would be the surprising direction to be wrong in.
         */
        PrerequisiteMode prerequisiteMode,
        /** How many of {@link #dependsOn} must be satisfied, replacing the mode's own count. */
        int minRequired,
        /**
         * The quests that finish this chapter, by id or alias.
         *
         * <p>Empty means this chapter never reports completed, which is a real state: a chapter that is
         * only ever waited on as "started" needs no completion to declare. A dependency that asks for
         * completed anyway is an error at load — see the class note.
         */
        List<QuestRef> completesWhen,
        /** Whether the row is withheld from a reader until this chapter's gate is met. */
        boolean hideUntilDependenciesComplete,
        /**
         * The chapter's default for its quests' {@code hideUntilDependenciesComplete}, which a quest
         * inherits unless it says otherwise.
         *
         * <p><b>Not the field above.</b> That one withholds this chapter's <i>row</i> from a reader
         * until this chapter's gate is met; this one decides what the quests <i>inside</i> it do about
         * their own dependencies. The two names sit two words apart and mean opposite ends of the
         * chapter, which is why the editor labels them "Hide until open" and "Hide quests until done"
         * rather than trusting the field names to carry it.
         *
         * <p>It is here, beside the chapter's own gate, rather than beside {@code defaultConsumeItems}
         * and {@code defaultPrerequisiteMode}, for a mundane reason with a real consequence:
         * {@link Chapter} is at fifteen of the codec's sixteen components, and this record is already
         * the one grouped field it has room for. The JSON is flat either way.
         */
        boolean defaultHideUntilDependenciesComplete,
        /** The chapter's default for its quests' {@code hideUntilDependenciesVisible}. See above. */
        boolean defaultHideUntilDependenciesVisible,
        /**
         * The chapter's default for its quests' {@code flexibleProgress}: whether they may work
         * their tasks before their dependencies are met.
         *
         * <p>Here, beside the other quest-inherited defaults, rather than beside
         * {@code defaultConsumeItems} for the mundane reason the class note gives: {@link Chapter}
         * is at sixteen codec components, and this record is the grouped field with room. The
         * JSON is flat either way. Either this or a quest's own flag makes it flexible — there
         * is no opt-out, which is why a migration tool inlines the resolved value onto each quest
         * and leaves this off.
         */
        boolean defaultFlexibleProgress,
        /**
         * The chapter's default for its quests' {@code minWidth}: how wide a quest's detail panel
         * wants to be unless the quest says otherwise.
         *
         * <p>Zero means unset — the panel takes its kind's default — because every file written
         * before this field existed says nothing. A quest's own {@code minWidth} wins over this.
         * the legacy {@code default_min_width}.
         */
        int defaultMinWidth,
        /**
         * The quest this chapter centres on when it is selected, by id or alias.
         *
         * <p>the legacy {@code autofocus_id}. Absent means the old behaviour: the canvas centres
         * on the chapter's bounding box. A name that resolves to nothing is reported at load,
         * because the chapter would then centre on nothing; a name that resolves to a quest in
         * another chapter is reported too, for the same reason. Links and image {@code requires}
         * are not the canvas's centre and do not count here.
         */
        Optional<QuestRef> autofocus,
        /**
         * The chapter's default for its quests' {@code sequentialTasks}: whether tasks must be
         * done in order unless a quest says otherwise.
         *
         * <p>the legacy {@code require_sequential_tasks}. Either the quest's own flag or this
         * makes its tasks sequential — there is no opt-out, which is why a migration tool
         * inlines the resolved value onto each quest when this is absent.
         */
        boolean defaultSequentialTasks,
        /**
         * Whether this chapter is withheld from every reader, whatever its gate says.
         *
         * <p>the legacy {@code always_invisible}. Unlike {@link #hideUntilDependenciesComplete},
         * which withholds the row until the gate is met, this withholds it always: a reader never
         * sees the row, its progress reads 100%, and it answers no search. The gate itself is
         * unaffected -- an always-invisible chapter still opens, still completes, and still gates
         * its quests -- and the editor still lists it, or the flag could not be authored.
         */
        boolean alwaysInvisible,
        /**
         * Whether this chapter's canvas draws without the zoom tiers: icons, rings, titles
         * and badges at any zoom.
         *
         * <p>An {@link Optional} rather than a boolean, because absence defers to the book's
         * own {@code disableCanvasLod} — the setting this answers per chapter. Present
         * {@code true} draws everything always; present {@code false} keeps the tiers even
         * when the book disables them, which is the one direction a plain boolean could not
         * say. Every file written before this field existed says nothing, and nothing
         * reads as the book's answer.
         */
        Optional<Boolean> disableCanvasLod,
        /**
         * Words this chapter answers to in lookups by tag.
         *
         * <p>the legacy {@code tags}, present on every object. Each tag is
         * {@code ^[a-z0-9_]{1,64}$} -- the same rule a quest id follows -- and a lookup of
         * {@code "#tag"} resolves to the first quest, chapter or group carrying it. See
         * {@link QuestIndex} for the resolution and the validator for the refusal.
         */
        List<String> tags) {

    /** A chapter with no gate, no declared completion, no hiding and no defaults for its quests. */
    public static final ChapterRules DEFAULT = new ChapterRules(List.of(), PrerequisiteMode.ALL_COMPLETED,
            0, List.of(), false, false, false, false, 0, Optional.empty(), false, false, Optional.empty(),
            List.of());

    /**
     * The bounds of {@link #minRequired}.
     *
     * <p>Taken from {@link QuestRules} rather than written again, because they are the same number for
     * the same reason — a count of dependencies, bounded so that a typo is a sentence rather than a
     * rule nobody can satisfy. A second 64 here would be the second description of one fact this
     * project keeps finding.
     */
    public static final int MIN_COUNT = QuestRules.MIN_COUNT;
    public static final int MAX_COUNT = QuestRules.MAX_COUNT;

    /** The field names this contributes, for the validator to allow. */
    public static final Set<String> FIELDS = Set.of("dependsOn", "prerequisiteMode", "minRequired",
            "completesWhen", "hideUntilDependenciesComplete", "defaultHideUntilDependenciesComplete",
            "defaultHideUntilDependenciesVisible", "defaultFlexibleProgress", "defaultMinWidth",
            "autofocus", "defaultSequentialTasks", "alwaysInvisible", "disableCanvasLod", "tags");

    /** How many of {@link #dependsOn} must be satisfied. */
    public int requiredCount() {
        return PrerequisiteMode.requiredCount(prerequisiteMode, minRequired, dependsOn.size());
    }

    /** Whether this chapter waits on anything at all. */
    public boolean waits() {
        return !dependsOn.isEmpty();
    }

    public static final MapCodec<ChapterRules> MAP_CODEC = RecordCodecBuilder.mapCodec(instance -> instance.group(
            ChapterRef.CODEC.listOf().optionalFieldOf("dependsOn", List.of())
                    .forGetter(ChapterRules::dependsOn),
            PrerequisiteMode.CODEC.optionalFieldOf("prerequisiteMode", PrerequisiteMode.ALL_COMPLETED)
                    .forGetter(ChapterRules::prerequisiteMode),
            // A count only means anything on a chapter that waits on something, but it is not an error to
            // set one anyway -- an author may set it before deciding, and a validator that complains
            // about a harmless combination is one people learn to ignore.
            Codec.intRange(MIN_COUNT, MAX_COUNT).optionalFieldOf("minRequired", 0)
                    .forGetter(ChapterRules::minRequired),
            QuestRef.CODEC.listOf().optionalFieldOf("completesWhen", List.of())
                    .forGetter(ChapterRules::completesWhen),
            // Absent means "listed, dimmed, with what it waits for" rather than "hidden": a gate a
            // reader cannot see is a chapter that looks missing, and a map that hides its own roads is
            // harder to read than one that shows a closed one.
            Codec.BOOL.optionalFieldOf("hideUntilDependenciesComplete", false)
                    .forGetter(ChapterRules::hideUntilDependenciesComplete),
            // The two chapter-level defaults for its quests. False by default, which is what every pack
            // written before they existed says by saying nothing: a quest is drawn as locked until its own
            // prerequisites are met, and an author opts into the reveal behaviour per chapter or per quest.
            Codec.BOOL.optionalFieldOf("defaultHideUntilDependenciesComplete", false)
                    .forGetter(ChapterRules::defaultHideUntilDependenciesComplete),
            Codec.BOOL.optionalFieldOf("defaultHideUntilDependenciesVisible", false)
                    .forGetter(ChapterRules::defaultHideUntilDependenciesVisible),
            Codec.BOOL.optionalFieldOf("defaultFlexibleProgress", false)
                    .forGetter(ChapterRules::defaultFlexibleProgress),
            // Zero is unset, like a quest's own minWidth: every file written before this field
            // existed says nothing, and nothing must read as the old behaviour.
            Codec.intRange(QuestPresentation.MIN_WIDTH_MIN, QuestPresentation.MIN_WIDTH_MAX)
                    .optionalFieldOf("defaultMinWidth", 0).forGetter(ChapterRules::defaultMinWidth),
            QuestRef.CODEC.optionalFieldOf("autofocus").forGetter(ChapterRules::autofocus),
            Codec.BOOL.optionalFieldOf("defaultSequentialTasks", false)
                    .forGetter(ChapterRules::defaultSequentialTasks),
            Codec.BOOL.optionalFieldOf("alwaysInvisible", false)
                    .forGetter(ChapterRules::alwaysInvisible),
            // Absent defers to the book's own flag, so this is the bare optional rather than a
            // defaulted one: only a present value — true or false — crosses into the record.
            Codec.BOOL.optionalFieldOf("disableCanvasLod").forGetter(ChapterRules::disableCanvasLod),
            Codec.STRING.listOf().optionalFieldOf("tags", List.of())
                    .forGetter(ChapterRules::tags)
    ).apply(instance, ChapterRules::new));

    public static final Codec<ChapterRules> CODEC = MAP_CODEC.codec();
}
