package dev.ellipog.tenet.quest.condition;

import com.mojang.serialization.Codec;
import com.mojang.serialization.JsonOps;
import com.mojang.serialization.MapCodec;

import dev.ellipog.armature.api.data.TypeDispatch;
import dev.ellipog.armature.api.data.TypeSpec;
import dev.ellipog.armature.api.registry.SimpleRegistry;
import dev.ellipog.tenet.Tenet;
import dev.ellipog.tenet.quest.EditorField;
import dev.ellipog.tenet.quest.EditorSpecs;
import dev.ellipog.tenet.quest.ItemRef;
import dev.ellipog.tenet.quest.task.ComponentMatch;

import net.minecraft.resources.Identifier;

import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * The condition types, and the codec that reads one.
 *
 * <p>A registry rather than a sealed enum, for the reason the plan gives the task and reward registries:
 * this <b>is</b> the addon API — a mod that adds a condition type registers it here — and the cost of
 * having it before the first type is written is nothing, while retrofitting it later touches every
 * condition, every save file and every network message.
 *
 * <p>The three helpers at the bottom exist for the same reason {@code TaskTypes} has its own: an entry
 * is stored wide, because one lookup answers both dispatch and evaluation, and the narrowing is a cast
 * that is sound only because the id, the codec and the behaviour travel together from one registration.
 * The widening wrapper forwards every method the behaviour has, and a test pins that — the item task's
 * {@code takesResources} silently read as false for a round when it did not.
 */
public final class ConditionTypes {

    /** A registered type: what reads it, how it decides, and what represents it. */
    private record Entry(TypeSpec<QuestCondition> spec, ConditionBehaviour<QuestCondition> behaviour,
                         ItemRef icon, Function<QuestCondition, ConditionDisplay> display,
                         List<EditorField> editor, Supplier<QuestCondition> defaults) {
    }

    // Declared first: static initialisation runs in declaration order, and the entries below register
    // into this.
    private static final SimpleRegistry<Entry> REGISTRY = SimpleRegistry.create("quest condition types");

    /** {@code tenet:item} — have enough of an item. */
    public static final ConditionType<ItemCondition> ITEM = register(
            "item", ItemCondition.MAP_CODEC, ItemCondition.FIELDS, List.of(
                    EditorField.item("item", "Require")
                            .hint("the item to have; the picker keeps the data of the one you pick, "
                                    + "so an enchanted sword is not a plain one"),
                    EditorField.number("count", "Count", "\u00d7")
                            .hint("how many of them"),
                    EditorField.choice("match", "Match", "none", "fuzzy", "strict")
                            .hint("how closely a carried stack must match, the item task's own field")),
            ItemCondition.BEHAVIOUR,
            new ItemRef(Identifier.withDefaultNamespace("chest"), 1), ItemCondition.DISPLAY,
            () -> new ItemCondition(new ItemRef(Identifier.withDefaultNamespace("stone"), 1),
                    ComponentMatch.STRICT));

    /** {@code tenet:item_tag} — have enough of any item in a tag. */
    public static final ConditionType<ItemTagCondition> ITEM_TAG = register(
            "item_tag", ItemTagCondition.MAP_CODEC, ItemTagCondition.FIELDS, List.of(
                    EditorField.tag("tag", "Tag")
                            .hint("any item in this tag counts; pick one from the tags the pack declares"),
                    EditorField.number("count", "Count", "\u00d7")
                            .hint("how many of them")),
            ItemTagCondition.BEHAVIOUR,
            new ItemRef(Identifier.withDefaultNamespace("barrel"), 1), ItemTagCondition.DISPLAY,
            () -> new ItemTagCondition(Identifier.withDefaultNamespace("logs"), 1));

    /** {@code tenet:score} — have at least this much on a scoreboard objective. */
    public static final ConditionType<ScoreCondition> SCORE = register(
            "score", ScoreCondition.MAP_CODEC, ScoreCondition.FIELDS, List.of(
                    EditorField.text("objective", "Objective",
                                    "the scoreboard objective's name, as the scoreboard command spells it")
                            .hint("the objective to read; one that does not exist reads as zero, so a typo "
                                    + "locks the gate"),
                    EditorField.number("min", "At least", "")
                            .hint("the score to reach")),
            ScoreCondition.BEHAVIOUR,
            new ItemRef(Identifier.withDefaultNamespace("comparator"), 1), ScoreCondition.DISPLAY,
            () -> new ScoreCondition("condition_gallery_standing", 1));

    /** {@code tenet:advancement} — have earned an advancement, or one criterion of one. */
    public static final ConditionType<AdvancementCondition> ADVANCEMENT = register(
            "advancement", AdvancementCondition.MAP_CODEC, AdvancementCondition.FIELDS, List.of(
                    EditorField.search("advancement", "Advancement", EditorField.Source.ADVANCEMENT)
                            .hint("the advancement to have earned"),
                    EditorField.text("criterion", "One criterion",
                                    "leave empty for the whole advancement")
                            .hint("require one criterion of it instead of the whole advancement")),
            AdvancementCondition.BEHAVIOUR,
            new ItemRef(Identifier.withDefaultNamespace("book"), 1), AdvancementCondition.DISPLAY,
            () -> new AdvancementCondition(Identifier.withDefaultNamespace("story/root"), Optional.empty()));

    /** {@code tenet:stage} — have a stage. */
    public static final ConditionType<StageCondition> STAGE = register(
            "stage", StageCondition.MAP_CODEC, StageCondition.FIELDS, List.of(
                    EditorField.text("stage", "Stage", "the id a reward, a command or a script grants")
                            .hint("the stage the player has to have; a stage exists by being granted, so "
                                    + "there is no list to pick from and no id to validate")),
            StageCondition.BEHAVIOUR,
            new ItemRef(Identifier.withDefaultNamespace("lever"), 1), StageCondition.DISPLAY,
            () -> new StageCondition(Identifier.fromNamespaceAndPath("example", "stage")));

    /** {@code tenet:party_size} — be in a party of at least this many, online now. */
    public static final ConditionType<PartySizeCondition> PARTY_SIZE = register(
            "party_size", PartySizeCondition.MAP_CODEC, PartySizeCondition.FIELDS, List.of(
                    EditorField.number("min", "At least", "")
                            .hint("how many members of the party have to be online; you count as one")),
            PartySizeCondition.BEHAVIOUR,
            new ItemRef(Identifier.withDefaultNamespace("player_head"), 1), PartySizeCondition.DISPLAY,
            () -> new PartySizeCondition(2));

    private ConditionTypes() {
    }

    /**
     * The codec that reads {@code "type"} and hands the rest to that type's own codec.
     *
     * <p>Lazily built, in its own class — see {@link QuestCondition} for the class-initialisation cycle
     * that makes that necessary rather than merely tidy.
     */
    private static final class Dispatch {
        static final Codec<QuestCondition> CODEC = TypeDispatch.codec(
                "quest condition", "type", QuestCondition::type,
                () -> REGISTRY.values().stream().map(Entry::spec).toList(),
                // A placeholder rather than a refusal, so one addon condition does not cost the author
                // every quest in the file. It deliberately registers no behaviour: Conditions reads an
                // unregistered type as NOT met, which is the safe direction for a gate.
                UnknownCondition::new);
    }

    public static Codec<QuestCondition> dispatchCodec() {
        return Dispatch.CODEC;
    }

    /** Adds a condition type in Tenet's own namespace, with the type's own editor form. */
    public static <T extends QuestCondition> ConditionType<T> register(String path,
                                                                       MapCodec<T> codec,
                                                                       Set<String> fields,
                                                                       List<EditorField> editor,
                                                                       ConditionBehaviour<T> behaviour,
                                                                       ItemRef icon,
                                                                       Function<T, ConditionDisplay> display,
                                                                       Supplier<T> defaults) {
        return register(Identifier.fromNamespaceAndPath(Tenet.MOD_ID, path), codec, fields, editor,
                behaviour, icon, display, defaults);
    }

    /** The same, without a declared form — one is derived from the field names. */
    public static <T extends QuestCondition> ConditionType<T> register(String path,
                                                                       MapCodec<T> codec,
                                                                       Set<String> fields,
                                                                       ConditionBehaviour<T> behaviour,
                                                                       ItemRef icon,
                                                                       Function<T, ConditionDisplay> display,
                                                                       Supplier<T> defaults) {
        return register(path, codec, fields, List.of(), behaviour, icon, display, defaults);
    }

    /** Adds a condition type under an id you choose — how an addon registers its own. */
    public static <T extends QuestCondition> ConditionType<T> register(Identifier id,
                                                                       MapCodec<T> codec,
                                                                       Set<String> fields,
                                                                       ConditionBehaviour<T> behaviour,
                                                                       ItemRef icon,
                                                                       Function<T, ConditionDisplay> display,
                                                                       Supplier<T> defaults) {
        return register(id, codec, fields, List.of(), behaviour, icon, display, defaults);
    }

    /** The same, under an id you choose, with the type's own editor form. */
    public static <T extends QuestCondition> ConditionType<T> register(Identifier id,
                                                                       MapCodec<T> codec,
                                                                       Set<String> fields,
                                                                       List<EditorField> editor,
                                                                       ConditionBehaviour<T> behaviour,
                                                                       ItemRef icon,
                                                                       Function<T, ConditionDisplay> display,
                                                                       Supplier<T> defaults) {
        ConditionType<T> typed = new SimpleConditionType<>(id, codec, fields, List.copyOf(editor), behaviour,
                icon, display, defaults);
        REGISTRY.register(id, new Entry(widenSpec(typed), widenBehaviour(behaviour), icon,
                widenDisplay(display), List.copyOf(editor), () -> defaults.get()));
        return typed;
    }

    /**
     * A fresh instance of a registered type, encoded as the tree a quest file stores.
     *
     * <p>What a condition picker inserts: encoded through the <b>dispatch</b> codec, because the type's
     * own codec does not write {@code "type"} and a tree without it is a condition the loader cannot
     * read back. Empty for an unregistered type, which the picker reports rather than inserting.
     */
    public static Optional<com.google.gson.JsonObject> defaultTree(Identifier id) {
        return REGISTRY.get(id).flatMap(entry -> Dispatch.CODEC
                .encodeStart(JsonOps.INSTANCE, entry.defaults().get())
                .result()
                .filter(com.google.gson.JsonElement::isJsonObject)
                .map(com.google.gson.JsonElement::getAsJsonObject));
    }

    /**
     * What a condition asks for, as a client should draw it.
     *
     * <p>A row naming the type for an unknown one — see {@code TaskTypes.displayOf} for why a named row
     * beats a blank one, which matters most here: a gate nothing can evaluate is exactly the thing an
     * author needs to be told about, because {@link Conditions} reads it as <b>not met</b> and the task
     * or reward behind it will never fire.
     */
    public static ConditionDisplay displayOf(QuestCondition condition) {
        if (condition instanceof UnknownCondition unknown) {
            return ConditionDisplay.ofTranslatableText("tenet.condition.unknown_type",
                    "Unknown condition type: " + unknown.type(), unknown.type().toString());
        }
        return REGISTRY.get(condition.type())
                .map(entry -> entry.display().apply(condition))
                .orElse(ConditionDisplay.NONE);
    }

    /** Every registered id, sorted. For messages, the validator, and {@code /tenet types}. */
    public static Set<Identifier> ids() {
        return REGISTRY.ids();
    }

    public static int count() {
        return REGISTRY.size();
    }

    /** The type-specific field names for a registered type, for the validator. */
    public static Set<String> fieldsOf(Identifier id) {
        return REGISTRY.get(id).map(entry -> entry.spec().fields()).orElse(Set.of());
    }

    /** The registered type's own codec, for validating one entry on its own. */
    public static Optional<MapCodec<QuestCondition>> codecOf(Identifier id) {
        return REGISTRY.get(id).map(entry -> entry.spec().codec());
    }

    /**
     * How to evaluate a condition.
     *
     * <p>Empty for an {@link UnknownCondition}, and that emptiness <b>is</b> the behaviour:
     * {@link Conditions} reads a missing entry as not met. The direction is the safe one for a gate —
     * a lock that cannot be read stays shut — and it is stated here rather than left implicit because
     * an unknown type can now actually reach this method, where before the dispatch refused to decode
     * one at all. There is no {@code UnknownCondition.BEHAVIOUR} to find, on purpose; see that record.
     */
    public static Optional<ConditionBehaviour<QuestCondition>> behaviourOf(QuestCondition condition) {
        return REGISTRY.get(condition.type()).map(Entry::behaviour);
    }

    /** The icon for a condition's type, for a listing or a picker. */
    public static ItemRef iconOf(Identifier id) {
        return REGISTRY.get(id).map(Entry::icon).orElse(ItemRef.DEFAULT_ICON);
    }

    /** The fields of a registered type as the editor draws them, declared form or derived. */
    public static List<EditorField> editorOf(Identifier id) {
        return REGISTRY.get(id)
                .map(entry -> entry.editor().isEmpty() ? EditorSpecs.derive(entry.spec().fields()) : entry.editor())
                .orElse(List.of());
    }

    @SuppressWarnings("unchecked")
    private static <T extends QuestCondition> TypeSpec<QuestCondition> widenSpec(ConditionType<T> type) {
        return new TypeSpec<>() {
            @Override
            public Identifier id() {
                return type.id();
            }

            @Override
            public Set<String> fields() {
                return type.fields();
            }

            @Override
            public MapCodec<QuestCondition> codec() {
                return type.codec().xmap(condition -> (QuestCondition) condition, condition -> (T) condition);
            }
        };
    }

    /**
     * Widens a behaviour to the uniform one the engine needs.
     *
     * <p>Package-private rather than private so the forwarding test can call it directly with a
     * hand-built condition: registering a test-only type into the shared registry is how one test
     * leaks into every other test in the JVM — the same reason
     * {@code QuestPanelLayout.typeRows(String, Set)} is package-private, and the same note.
     */
    @SuppressWarnings("unchecked")
    static <T extends QuestCondition> ConditionBehaviour<QuestCondition> widenBehaviour(
            ConditionBehaviour<T> behaviour) {
        // One method, forwarded by name. The comment stays because the failure mode is the one
        // TaskTypes.widenBehaviour documents: a wrapper that leaves a method to the interface's default
        // reads as "this type does not do that" at every call site that goes through the registry.
        return (condition, context) -> behaviour.test((T) condition, context);
    }

    @SuppressWarnings("unchecked")
    private static <T extends QuestCondition> Function<QuestCondition, ConditionDisplay> widenDisplay(
            Function<T, ConditionDisplay> display) {
        // The same soundness argument as the spec's and the behaviour's: the display travels with the
        // id and the codec, so the only value ever handed to this cast came from that codec.
        return condition -> display.apply((T) condition);
    }

    private record SimpleConditionType<T extends QuestCondition>(
            Identifier id, MapCodec<T> codec, Set<String> fields, List<EditorField> editor,
            ConditionBehaviour<T> behaviour, ItemRef icon, Function<T, ConditionDisplay> display,
            Supplier<T> defaultsSupplier
    ) implements ConditionType<T> {

        @Override
        public ConditionDisplay display(T condition) {
            return display.apply(condition);
        }

        @Override
        public T defaults() {
            return defaultsSupplier.get();
        }
    }
}
