package dev.ellipog.tenet.quest.task;

import com.mojang.serialization.Codec;
import com.mojang.serialization.MapCodec;
import dev.ellipog.armature.api.data.TypeDispatch;
import dev.ellipog.armature.api.data.TypeSpec;
import dev.ellipog.armature.api.registry.SimpleRegistry;
import dev.ellipog.tenet.Tenet;
import dev.ellipog.tenet.quest.EditorField;
import dev.ellipog.tenet.quest.ItemRef;
import dev.ellipog.tenet.quest.QuestTask;
import dev.ellipog.tenet.quest.QuestText;
import dev.ellipog.tenet.quest.RegistryRef;
import dev.ellipog.tenet.quest.TaskCommon;
import net.minecraft.resources.ResourceLocation;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * Every task type Tenet knows about.
 *
 * <p>The registry is open, which is the whole point: another mod calls {@link #register} during its
 * own construction and its task type works everywhere a built-in one does — in quest files, in the
 * validator, in the editor, and in the engine.
 *
 * <h2>Why {@link #dispatchCodec()} is a method rather than a constant</h2>
 *
 * <p>Because of a class-initialisation cycle that compiled cleanly and failed at runtime, which is
 * worth spelling out because it is invisible in the source:
 *
 * <pre>
 * TaskTypes.&lt;clinit&gt;   reads ItemTask.MAP_CODEC
 *   ItemTask.&lt;clinit&gt;  initialises QuestTask first, because QuestTask declares a default method
 *     QuestTask.&lt;clinit&gt;  reads TaskTypes.DISPATCH_CODEC
 *       TaskTypes is already initialising on this thread, so the JVM hands back null
 *     QuestTask.CODEC = null, permanently, because it is static final
 * </pre>
 *
 * <p>The rule is JLS 12.4.1: initialising a class initialises its superinterfaces that declare
 * default methods. {@code QuestTask.optional()} was a one-line convenience and it was enough to close
 * the loop.
 *
 * <p>The fix is structural rather than a reordering. {@link Dispatch} is a separate class, so its
 * initialisation happens the first time the codec is actually needed — during {@code Quest}'s static
 * init, by which point this class is fully built. Nothing in this class's initialisation touches
 * {@code QuestTask}'s static state, so the cycle cannot re-form.
 *
 * <h2>Why the registry holds an entry rather than a bare spec</h2>
 *
 * <p>Because the engine needs the <b>behaviour</b> for a task instance, and an instance only knows its
 * type id. So the entry carries the widened spec for dispatch and the widened behaviour for
 * evaluation, and one lookup answers both.
 *
 * <p>The widening is a genuine cast and is confined to {@link #widenSpec} and {@link #widenBehaviour}:
 * the downcast can only be reached for a value that was decoded with that same type's codec, because
 * the id, the codec and the behaviour all come from one registration.
 */
public final class TaskTypes {

    /** A registered type: what reads it, how it is evaluated, and what represents it. */
    private record Entry(TypeSpec<QuestTask> spec, TaskBehaviour<QuestTask> behaviour, ItemRef icon,
                         Function<QuestTask, TaskDisplay> display,
                         java.util.List<dev.ellipog.tenet.quest.EditorField> editor,
                         Supplier<QuestTask> defaults) {
    }

    // Declared first: static initialisation runs in declaration order, and the entries below register
    // into this.
    private static final SimpleRegistry<Entry> REGISTRY = SimpleRegistry.create("quest task types");

    /**
     * The settings every task has, as the form draws them: whether it is required, and how often it is
     * tried.
     *
     * <p>Appended to every registered type's form rather than written into each spec, so a type cannot
     * offer a variant of "optional" that means something else. {@code autoSubmitTicks} is the cadence the
     * engine re-checks a task at, which is why its unit is ticks rather than a duration: an item task at
     * every tick is a scan of every player's inventory twenty times a second.
     */
    private static final List<EditorField> COMMON_EDITOR = List.of(
            EditorField.flag("optional", "Optional")
                    .hint("shown to the player, but the quest does not wait for it"),
            EditorField.flag("disableToast", "Quiet task")
                    .hint("finishing this task raises no task toast"),
            EditorField.text("title", "Title", "the words the row wears instead of the type's own")
                    .hint("the words this task's row wears; empty means the type's own sentence"),
            EditorField.icon("icon", "Picture")
                    .hint("the picture this task's row wears; empty means the type's own"),
            EditorField.text("tags", "Tags", "words this task answers to, comma-separated")
                    .hint("words this task answers to in lookups by tag; empty means none"),
            EditorField.number("autoSubmitTicks", "Checked every", "ticks")
                    .hint("how often the server re-checks this task; ticks, twenty to the second"));

    /** {@code tenet:item} — have enough of an item. */
    public static final QuestTaskType<ItemTask> ITEM = register(
            "item", ItemTask.MAP_CODEC, ItemTask.FIELDS, java.util.List.of(
                    EditorField.item("item", "Hand in")
                            .hint("the item to hand over; the picker keeps the data of the one you pick, "
                                    + "so an enchanted sword is not a plain one"),
                    EditorField.number("count", "Count", "\u00d7")
                            .hint("how many of them"),
                    EditorField.flag("consumeItems", "Consume")
                            .hint("handing the task in takes the items from the player"),
                    EditorField.choice("match", "Match", "none", "fuzzy", "strict")
                            .hint("how closely a carried stack must match: none ignores its data, fuzzy "
                                    + "needs the data it names, strict needs the whole stack"),
                    EditorField.flag("onlyFromCrafting", "Crafted only")
                            .hint("only items the player crafted themselves count"),
                    EditorField.flag("manualOnly", "Manual only")
                            .hint("never count from the inventory on its own; only a submit press counts")),
            ItemTask.BEHAVIOUR,
            new ItemRef(ResourceLocation.withDefaultNamespace("chest"), 1), ItemTask.DISPLAY,
            () -> new ItemTask(TaskCommon.DEFAULT,
                    new ItemRef(ResourceLocation.withDefaultNamespace("paper"), 1), Optional.empty(),
                    ComponentMatch.STRICT, false, false));

    /** {@code tenet:item_tag} — have enough of any item in a tag. */
    public static final QuestTaskType<ItemTagTask> ITEM_TAG = register(
            "item_tag", ItemTagTask.MAP_CODEC, ItemTagTask.FIELDS, java.util.List.of(
                    EditorField.tag("tag", "Tag")
                            .hint("any item in this tag counts; pick one from the tags the pack declares"),
                    EditorField.number("count", "Count", "\u00d7")
                            .hint("how many of them"),
                    EditorField.flag("consumeItems", "Consume")
                            .hint("handing the task in takes the items from the player"),
                    EditorField.flag("onlyFromCrafting", "Crafted only")
                            .hint("only items the player crafted themselves count"),
                    EditorField.flag("manualOnly", "Manual only")
                            .hint("never count from the inventory on its own; only a submit press counts")),
            ItemTagTask.BEHAVIOUR,
            new ItemRef(ResourceLocation.withDefaultNamespace("barrel"), 1), ItemTagTask.DISPLAY,
            () -> new ItemTagTask(new TaskCommon(false, 20),
                    ResourceLocation.withDefaultNamespace("logs"), 1, Optional.empty(), false, false));

    /** {@code tenet:filter} — have enough of whatever a filter expression matches. */
    public static final QuestTaskType<FilterTask> FILTER = register(
            "filter", FilterTask.MAP_CODEC, FilterTask.FIELDS, java.util.List.of(
                    EditorField.text("filter", "Filter",
                                    "a filter expression, e.g. or(item(minecraft:coal)item_tag(minecraft:coals))")
                            .hint("which items count, as a filter expression: item, item_tag, mod and "
                                    + "block leaves under and, or and not"),
                    EditorField.number("count", "Count", "\u00d7")
                            .hint("how many matching items"),
                    EditorField.flag("consumeItems", "Consume")
                            .hint("handing the task in takes the items from the player"),
                    EditorField.flag("manualOnly", "Manual only")
                            .hint("never count from the inventory on its own; only a submit press counts")),
            FilterTask.BEHAVIOUR,
            new ItemRef(ResourceLocation.withDefaultNamespace("hopper"), 1), FilterTask.DISPLAY,
            () -> new FilterTask(new TaskCommon(false, 20),
                    FilterParser.parse("item_tag(minecraft:logs)"), 1, Optional.empty(), false));

    /** {@code tenet:checkmark} — the player says they did it. */
    public static final QuestTaskType<CheckmarkTask> CHECKMARK = register(
            "checkmark", CheckmarkTask.MAP_CODEC, CheckmarkTask.FIELDS, java.util.List.of(),
            CheckmarkTask.BEHAVIOUR,
            new ItemRef(ResourceLocation.withDefaultNamespace("knowledge_book"), 1), CheckmarkTask.DISPLAY,
            () -> new CheckmarkTask(new TaskCommon(false, 20, java.util.List.of(), false,
                    java.util.Optional.of(QuestText.literal("Did it")), java.util.Optional.empty(),
                    java.util.List.of())));

    /** {@code tenet:dimension} — be in a dimension. */
    public static final QuestTaskType<DimensionTask> DIMENSION = register(
            "dimension", DimensionTask.MAP_CODEC, DimensionTask.FIELDS, java.util.List.of(
                    EditorField.search("dimension", "Dimension", EditorField.Source.DIMENSION)
                            .hint("the dimension the player has to be in")),
            DimensionTask.BEHAVIOUR,
            new ItemRef(ResourceLocation.withDefaultNamespace("ender_pearl"), 1), DimensionTask.DISPLAY,
            () -> new DimensionTask(new TaskCommon(false, 100),
                    ResourceLocation.withDefaultNamespace("overworld")));

    /** {@code tenet:biome} — be in a biome, or any biome of a tag. */
    public static final QuestTaskType<BiomeTask> BIOME = register(
            "biome", BiomeTask.MAP_CODEC, BiomeTask.FIELDS, java.util.List.of(
                    EditorField.search("biome", "Biome", EditorField.Source.BIOME)
                            .hint("the biome to stand in; a #tag counts any biome of it")),
            BiomeTask.BEHAVIOUR,
            new ItemRef(ResourceLocation.withDefaultNamespace("oak_sapling"), 1), BiomeTask.DISPLAY,
            () -> new BiomeTask(new TaskCommon(false, 20), RegistryRef.parse("minecraft:plains")));

    /** {@code tenet:structure} — be inside a structure, or one of a structure tag. */
    public static final QuestTaskType<StructureTask> STRUCTURE = register(
            "structure", StructureTask.MAP_CODEC, StructureTask.FIELDS, java.util.List.of(
                    EditorField.search("structure", "Structure", EditorField.Source.STRUCTURE)
                            .hint("the structure to be inside; a #tag counts any of them")),
            StructureTask.BEHAVIOUR,
            new ItemRef(ResourceLocation.withDefaultNamespace("filled_map"), 1), StructureTask.DISPLAY,
            () -> new StructureTask(new TaskCommon(false, 20),
                    RegistryRef.parse("minecraft:village_plains")));

    /** {@code tenet:advancement} — earn an advancement, or one criterion of one. */
    public static final QuestTaskType<AdvancementTask> ADVANCEMENT = register(
            "advancement", AdvancementTask.MAP_CODEC, AdvancementTask.FIELDS, java.util.List.of(
                    EditorField.search("advancement", "Advancement", EditorField.Source.ADVANCEMENT)
                            .hint("the advancement to earn"),
                    EditorField.text("criterion", "One criterion",
                                    "leave empty for the whole advancement")
                            .hint("count one criterion of it instead of the whole advancement")),
            AdvancementTask.BEHAVIOUR,
            new ItemRef(ResourceLocation.withDefaultNamespace("book"), 1), AdvancementTask.DISPLAY,
            () -> new AdvancementTask(new TaskCommon(false, 5),
                    ResourceLocation.withDefaultNamespace("story/root"), Optional.empty()));

    /** {@code tenet:stat} — reach a vanilla statistic value. */
    public static final QuestTaskType<StatTask> STAT = register(
            "stat", StatTask.MAP_CODEC, StatTask.FIELDS, java.util.List.of(
                    EditorField.search("stat", "Statistic", EditorField.Source.STAT)
                            .hint("which statistic to watch"),
                    EditorField.number("value", "Reach", "")
                            .hint("the number that statistic has to reach")),
            StatTask.BEHAVIOUR,
            new ItemRef(ResourceLocation.withDefaultNamespace("comparator"), 1), StatTask.DISPLAY,
            () -> new StatTask(new TaskCommon(false, 3),
                    ResourceLocation.withDefaultNamespace("walk_one_cm"), 1000));

    /** {@code tenet:location} — stand in a box, in a dimension or in any. */
    public static final QuestTaskType<LocationTask> LOCATION = register(
            "location", LocationTask.MAP_CODEC, LocationTask.FIELDS, java.util.List.of(
                    EditorField.search("dimension", "Dimension", EditorField.Source.DIMENSION)
                            .hint("the dimension the box is in"),
                    EditorField.flag("ignoreDimension", "Any dimension")
                            .hint("counts anywhere, instead of only in the dimension above"),
                    EditorField.position("position", "Corner")
                            .hint("one corner of the box, in blocks; My position fills it from where you "
                                    + "stand, and the dimension with it"),
                    EditorField.size("size", "Box size")
                            .hint("how far the box reaches from that corner")),
            LocationTask.BEHAVIOUR,
            new ItemRef(ResourceLocation.withDefaultNamespace("compass"), 1), LocationTask.DISPLAY,
            () -> new LocationTask(new TaskCommon(false, 3), Optional.empty(), false,
                    java.util.List.of(0, 0, 0), java.util.List.of(8, 8, 8)));

    /** {@code tenet:xp} — hand over experience points or levels. */
    public static final QuestTaskType<XpTask> XP = register(
            "xp", XpTask.MAP_CODEC, XpTask.FIELDS, java.util.List.of(
                    EditorField.number("value", "Hand in", "XP")
                            .hint("how much experience to hand over"),
                    EditorField.flag("points", "Points")
                            .hint("count experience points rather than whole levels")),
            XpTask.BEHAVIOUR,
            new ItemRef(ResourceLocation.withDefaultNamespace("experience_bottle"), 1), XpTask.DISPLAY,
            () -> new XpTask(new TaskCommon(false, 20), 100, true));

    /** {@code tenet:fluid} — hand over fluid, carried in buckets and containers. */
    public static final QuestTaskType<FluidTask> FLUID = register(
            "fluid", FluidTask.MAP_CODEC, FluidTask.FIELDS, java.util.List.of(
                    EditorField.search("fluid", "Hand in", EditorField.Source.FLUID)
                            .hint("which fluid to hand over, carried in buckets or fluid containers"),
                    EditorField.number("amount", "Amount", "mB")
                            .hint("how much of it, in millibuckets; a bucket is a thousand"),
                    EditorField.flag("manualOnly", "Manual only")
                            .hint("never count from the inventory on its own; only a submit press counts")),
            FluidTask.BEHAVIOUR,
            new ItemRef(ResourceLocation.withDefaultNamespace("water_bucket"), 1), FluidTask.DISPLAY,
            () -> new FluidTask(new TaskCommon(false, 20),
                    ResourceLocation.withDefaultNamespace("water"), 1000, false));

    /** {@code tenet:energy} — hand over stored energy, carried in charged items. */
    public static final QuestTaskType<EnergyTask> ENERGY = register(
            "energy", EnergyTask.MAP_CODEC, EnergyTask.FIELDS, java.util.List.of(
                    EditorField.number("value", "Hand in", "FE")
                            .hint("how much stored energy to hand over, in Forge Energy units"),
                    EditorField.number("maxInput", "Max in", "FE")
                            .hint("the most each item may contribute; 0 means unlimited"),
                    EditorField.flag("manualOnly", "Manual only")
                            .hint("never count from the inventory on its own; only a submit press counts")),
            EnergyTask.BEHAVIOUR,
            new ItemRef(ResourceLocation.withDefaultNamespace("redstone"), 1), EnergyTask.DISPLAY,
            () -> new EnergyTask(new TaskCommon(false, 20), 1000, 0, false));

    /** {@code tenet:observation} — look at a block or entity for long enough. */
    public static final QuestTaskType<ObservationTask> OBSERVATION = register(
            "observation", ObservationTask.MAP_CODEC, ObservationTask.FIELDS, java.util.List.of(
                    EditorField.choice("observeType", "Look for", "block", "block_tag", "block_state",
                                    "block_entity", "block_entity_type", "entity_type", "entity_type_tag")
                            .hint("what counts as looking: a block, a tag of them, one block state, a block "
                                    + "entity, or an entity -- the list below follows this"),
                    EditorField.search("toObserve", "Target", EditorField.Source.OBSERVATION_TARGET)
                            .hint("the block or entity to look at"),
                    EditorField.number("timer", "For", "ticks")
                            .hint("how long to look at it; ticks, twenty to the second")),
            ObservationTask.BEHAVIOUR,
            new ItemRef(ResourceLocation.withDefaultNamespace("spyglass"), 1), ObservationTask.DISPLAY,
            () -> new ObservationTask(new TaskCommon(false, 20), ObservationTask.ObserveType.BLOCK,
                    "minecraft:beacon", 40));

    /** {@code tenet:kill} — kill entities, by type or tag, optionally filtered. */
    public static final QuestTaskType<KillTask> KILL = register(
            "kill", KillTask.MAP_CODEC, KillTask.FIELDS, java.util.List.of(
                    EditorField.search("entity", "Entity", EditorField.Source.ENTITY)
                            .hint("the mob to kill"),
                    EditorField.tag("entityTypeTag", "Entity tag", EditorField.Source.ENTITY_TAG)
                            .hint("or a tag, to count any mob in it"),
                    EditorField.number("value", "Kills", "kills")
                            .hint("how many to kill"),
                    EditorField.text("customName", "Named", "only counts a mob with this name")
                            .hint("only counts a mob with this name"),
                    EditorField.text("nbtFilter", "NBT", "an SNBT filter, for what an id cannot say")
                            .hint("an SNBT filter, for the mobs an id cannot pick out")),
            KillTask.BEHAVIOUR,
            new ItemRef(ResourceLocation.withDefaultNamespace("iron_sword"), 1), KillTask.DISPLAY,
            () -> new KillTask(new TaskCommon(false, 20),
                    Optional.of(ResourceLocation.withDefaultNamespace("zombie")), Optional.empty(), 1,
                    Optional.empty(), Optional.empty()));

    /** {@code tenet:break} — break blocks, by id, tag or state, optionally filtered. */
    public static final QuestTaskType<BreakTask> BREAK = register(
            "break", BreakTask.MAP_CODEC, BreakTask.FIELDS, java.util.List.of(
                    EditorField.search("block", "Block", EditorField.Source.BLOCK)
                            .hint("the block to break"),
                    EditorField.tag("blockTag", "Block tag", EditorField.Source.BLOCK_TAG)
                            .hint("or a tag, to count any block in it"),
                    EditorField.text("blockState", "State", "e.g. minecraft:wheat[age=7]")
                            .hint("only count this block state, for what an id cannot pick out"),
                    EditorField.number("value", "Breaks", "blocks")
                            .hint("how many to break"),
                    EditorField.flag("countFakePlayers", "Automation counts")
                            .hint("a contraption's breaks count too, for the farm-the-thousand-wheat quest")),
            BreakTask.BEHAVIOUR,
            new ItemRef(ResourceLocation.withDefaultNamespace("iron_pickaxe"), 1), BreakTask.DISPLAY,
            () -> new BreakTask(new TaskCommon(false, 20),
                    Optional.of(ResourceLocation.withDefaultNamespace("stone")), Optional.empty(),
                    Optional.empty(), 1, false));

    /**
     * {@code tenet:custom} — progress measured by somebody else's code.
     *
     * <p>The scripting hook, and the one type here whose logic does not live in this mod: a mod or a script
     * registers a handler for the id the task names. See {@link CustomTask} for the whole of it.
     */
    public static final QuestTaskType<CustomTask> CUSTOM = register(
            "custom", CustomTask.MAP_CODEC, CustomTask.FIELDS, java.util.List.of(
                    EditorField.text("id", "Task id", "the id a mod or a script registered")
                            .hint("the id a mod or a script registered a handler for; the validator warns "
                                    + "when this build has nothing registered for it"),
                    EditorField.number("value", "Count", "")
                            .hint("how many the handler has to count to")),
            CustomTask.BEHAVIOUR,
            new ItemRef(ResourceLocation.withDefaultNamespace("jigsaw"), 1), CustomTask.DISPLAY,
            () -> new CustomTask(new TaskCommon(false, 20), "example:custom", 1));

    /**
     * {@code tenet:stage} — the player has a stage.
     *
     * <p>The read half of the flags a pack grants, whose write half is the stage reward, a command or a
     * script. See {@link StageTask}.
     */
    public static final QuestTaskType<StageTask> STAGE = register(
            "stage", StageTask.MAP_CODEC, StageTask.FIELDS, java.util.List.of(
                    EditorField.text("stage", "Stage", "the id a reward, a command or a script grants")
                            .hint("the stage the player has to have; a stage exists by being granted, so "
                                    + "there is no list to pick from and no id to validate"),
                    EditorField.flag("teamStage", "Team stage")
                            .hint("ask about the team's stages rather than the player's own")),
            StageTask.BEHAVIOUR,
            new ItemRef(ResourceLocation.withDefaultNamespace("lever"), 1), StageTask.DISPLAY,
            () -> new StageTask(new TaskCommon(false, 20),
                    ResourceLocation.fromNamespaceAndPath("example", "stage"), false));

    private TaskTypes() {
    }

    /**
     * The codec that reads {@code "type"} and hands the rest to that type's own codec.
     *
     * <p>Lazily built, in its own class — see the class comment for the cycle that makes that necessary
     * rather than merely tidy.
     */
    private static final class Dispatch {
        static final Codec<QuestTask> CODEC = TypeDispatch.codec(
                "quest task", "type", QuestTask::type, () -> REGISTRY.values().stream().map(Entry::spec).toList(),
                // An unregistered type decodes to a placeholder rather than failing, so one addon task in
                // a file no longer costs the author every quest in it -- see UnknownTask and TypeDispatch.
                UnknownTask::of);
    }

    /**
     * {@link UnknownTask#BEHAVIOUR}, widened once.
     *
     * <p>In its own class for the reason {@link Dispatch} gives: reading a widened behaviour during this
     * class's initialisation is the shape that closed the cycle once already. Built on first use, by
     * which point every class involved has finished.
     */
    private static final class Unknown {
        static final TaskBehaviour<QuestTask> BEHAVIOUR = widenBehaviour(UnknownTask.BEHAVIOUR);
    }

    public static Codec<QuestTask> dispatchCodec() {
        return Dispatch.CODEC;
    }

    /**
     * Adds a task type in Tenet's own namespace.
     *
     * <p>Call during your mod's construction on the client and the server.
     *
     * @throws IllegalStateException if the id is taken
     */
    public static <T extends QuestTask> QuestTaskType<T> register(String path,
                                                                  MapCodec<T> codec,
                                                                  Set<String> fields,
                                                                  TaskBehaviour<T> behaviour,
                                                                  ItemRef icon,
                                                                  Function<T, TaskDisplay> display,
                                                                  Supplier<T> defaults) {
        return register(path, codec, fields, java.util.List.of(), behaviour, icon, display, defaults);
    }

    /**
     * The same registration, carrying the type's own editor form.
     *
     * <p>The overload an addon wants: the fields it declared make a form the editor draws, so a new type
     * is a new <i>layout</i> rather than a row of generic boxes. A type that registers without one gets a
     * form derived from its field names -- see {@code EditorSpecs} -- which is a floor rather than a goal.
     */
    public static <T extends QuestTask> QuestTaskType<T> register(String path,
                                                                  MapCodec<T> codec,
                                                                  Set<String> fields,
                                                                  java.util.List<dev.ellipog.tenet.quest.EditorField> editor,
                                                                  TaskBehaviour<T> behaviour,
                                                                  ItemRef icon,
                                                                  Function<T, TaskDisplay> display,
                                                                  Supplier<T> defaults) {
        return register(ResourceLocation.fromNamespaceAndPath(Tenet.MOD_ID, path), codec, fields, editor,
                behaviour, icon, display, defaults);
    }

    /**
     * Adds a task type under an id you choose — which is how an addon registers its own, so the id is
     * namespaced to that mod rather than to Tenet.
     */
    public static <T extends QuestTask> QuestTaskType<T> register(ResourceLocation id,
                                                                  MapCodec<T> codec,
                                                                  Set<String> fields,
                                                                  TaskBehaviour<T> behaviour,
                                                                  ItemRef icon,
                                                                  Function<T, TaskDisplay> display,
                                                                  Supplier<T> defaults) {
        return register(id, codec, fields, java.util.List.of(), behaviour, icon, display, defaults);
    }

    /** The same, under an id you choose, with the type's own editor form. */
    public static <T extends QuestTask> QuestTaskType<T> register(ResourceLocation id,
                                                                  MapCodec<T> codec,
                                                                  Set<String> fields,
                                                                  java.util.List<dev.ellipog.tenet.quest.EditorField> editor,
                                                                  TaskBehaviour<T> behaviour,
                                                                  ItemRef icon,
                                                                  Function<T, TaskDisplay> display,
                                                                  Supplier<T> defaults) {
        // The common settings ride on the form as well as on the field list -- see COMMON_EDITOR.
        List<EditorField> form = new java.util.ArrayList<>(editor);
        form.addAll(COMMON_EDITOR);
        // And on the *registered* set, the way RewardTypes does it, so `fieldsOf` answers "every field
        // this type accepts" rather than "the fields it adds". Without this, `/tenet types` printed a
        // list that omitted `optional`, `autoSubmitTicks` and `conditions` -- the three the validator
        // accepts for every task -- so the command's own claim to print "the list the validator itself
        // uses" was false, and so was the manual's sentence quoting it.
        Set<String> declared = new java.util.LinkedHashSet<>(fields);
        declared.addAll(dev.ellipog.tenet.quest.TaskCommon.FIELDS);
        QuestTaskType<T> typed = new SimpleQuestTaskType<>(id, codec, Set.copyOf(declared), List.copyOf(form),
                behaviour, icon, display, defaults);
        REGISTRY.register(id, new Entry(widenSpec(typed), widenBehaviour(behaviour), icon,
                widenDisplay(display), List.copyOf(form), () -> defaults.get()));
        return typed;
    }

    /**
     * A fresh instance of a registered type, encoded as the tree a quest file stores.
     *
     * <p>What the editor's Add picker inserts: the type says what an empty one looks like, and the
     * encoding is this type's own codec — so what the picker adds is exactly what the loader will read
     * back. Empty for an unregistered type, which is a refusal the picker reports rather than a task
     * the file cannot load.
     */
    public static Optional<com.google.gson.JsonObject> defaultTree(ResourceLocation id) {
        return REGISTRY.get(id).flatMap(entry -> Dispatch.CODEC
                // Through the *dispatch* codec, not the type's own: the dispatch is what writes the
                // "type" field, and a tree without it is a task the loader cannot read back.
                .encodeStart(com.mojang.serialization.JsonOps.INSTANCE, entry.defaults().get())
                .result()
                .filter(com.google.gson.JsonElement::isJsonObject)
                .map(com.google.gson.JsonElement::getAsJsonObject));
    }

    /**
     * What a task asks for, as a client should draw it.
     *
     * <p>A row naming the type for an unknown one — {@link UnknownTask} carries no fields to describe, so
     * the honest row is the one that says which type is missing. It is drawn rather than left blank
     * because a blank row in the middle of a quest is indistinguishable from a task that does nothing,
     * and "some_addon:reticulate" is the sentence that tells the author what to install.
     *
     * <p>{@link TaskDisplay#NONE} otherwise for an unregistered type, which is now reachable only from a
     * task built by hand rather than decoded.
     */
    public static TaskDisplay displayOf(QuestTask task) {
        if (task instanceof UnknownTask unknown) {
            return TaskDisplay.ofTranslatableText("tenet.task.unknown_type",
                    "Unknown task type: " + unknown.type(), unknown.type().toString(), 1);
        }
        TaskDisplay computed = REGISTRY.get(task.type())
                .map(entry -> entry.display().apply(task))
                .orElse(TaskDisplay.NONE);
        return withAuthorOverrides(task.common(), computed);
    }

    /**
     * A task wearing its author's words and picture instead of its type's own.
     *
     * <p>Absent title and icon mean the type decides, which is every file written before the two
     * fields existed. An entity icon resolves to its egg here, on the server that holds the
     * registries; an entity with no egg keeps the type's own picture, because a task row without
     * one is a row that lost its subject rather than a mark about a missing mod.
     */
    private static TaskDisplay withAuthorOverrides(TaskCommon common, TaskDisplay computed) {
        TaskDisplay out = computed;
        if (common.title().isPresent()) {
            out = out.withAuthorTitle(common.title().get());
        }
        if (common.icon().isPresent()) {
            dev.ellipog.tenet.quest.Icon icon = common.icon().get();
            if (icon instanceof dev.ellipog.tenet.quest.Icon.Item item) {
                out = out.withAuthorItem(item.ref());
            }
            else if (icon instanceof dev.ellipog.tenet.quest.Icon.Texture texture) {
                out = out.withAuthorTexture(texture.texture().toString());
            }
            else if (icon instanceof dev.ellipog.tenet.quest.Icon.Sprite sprite) {
                out = out.withAuthorSprite(sprite.sprite().toString());
            }
            else if (icon instanceof dev.ellipog.tenet.quest.Icon.Entity entity) {
                ItemRef egg = eggOf(entity.entity());
                out = egg != null ? out.withAuthorItem(egg) : out;
            }
        }
        return out;
    }

    /**
     * An entity's spawn egg as a reference, or null.
     *
     * <p>The {@code <path>_spawn_egg} convention, asked of the server's own registry: the display is
     * computed where the registries live, so the wire carries the picture rather than the question.
     * Package-visible for the kill display, which wears the egg of its own target.
     */
    static ItemRef eggOf(net.minecraft.resources.ResourceLocation entity) {
        net.minecraft.resources.ResourceLocation egg =
                net.minecraft.resources.ResourceLocation.fromNamespaceAndPath(entity.getNamespace(),
                        entity.getPath() + "_spawn_egg");
        if (!net.minecraft.core.registries.BuiltInRegistries.ITEM.containsKey(egg)) {
            return null;
        }
        return new ItemRef(egg, 1);
    }

    /** Every registered id, sorted. For messages, the validator, and {@code /tenet types}. */
    public static Set<ResourceLocation> ids() {
        return REGISTRY.ids();
    }

    public static int count() {
        return REGISTRY.size();
    }

    /**
     * The type-specific field names for a registered type, for the validator.
     *
     * <p>An empty set for an unknown type, which by then the validator has already complained about.
     */
    public static Set<String> fieldsOf(ResourceLocation id) {
        return REGISTRY.get(id).map(entry -> entry.spec().fields()).orElse(Set.of());
    }

    /**
     * The registered type's own codec, for validating one entry on its own.
     *
     * <p>Not the dispatch: the dispatch reads {@code "type"} and is what the loader runs over a whole
     * file. This is for asking a single already-typed entry whether its fields make a value -- the
     * question the field-name validator cannot answer, and the one whose absence let a task with its
     * {@code "item"} deleted save cleanly and vanish from the tree at the next load.
     *
     * <p>Empty for an unregistered type, which by then the validator has already refused.
     */
    public static Optional<MapCodec<QuestTask>> codecOf(ResourceLocation id) {
        return REGISTRY.get(id).map(entry -> entry.spec().codec());
    }

    /**
     * How to evaluate a task.
     *
     * <p>An {@link UnknownTask} answers with {@link UnknownTask#BEHAVIOUR} — never satisfied, no button —
     * so the engine's "nothing can measure this" reading is stated here rather than left to a caller's
     * {@code orElse}. The numbers are the ones {@code ProgressionEngine} already used for an
     * unregistered behaviour, so no quest's progression changes; what changes is that the file is
     * readable.
     *
     * <p>Empty for an unregistered type that is not a decoded {@code UnknownTask}, which can now only be
     * a task built by hand.
     */
    public static Optional<TaskBehaviour<QuestTask>> behaviourOf(QuestTask task) {
        if (task instanceof UnknownTask) {
            return UNKNOWN_BEHAVIOUR;
        }
        return BEHAVIOURS.computeIfAbsent(task.type(), TaskTypes::lookupBehaviour);
    }

    /** The unregistered answer, built once: these tasks are never satisfied and have no button. */
    private static final Optional<TaskBehaviour<QuestTask>> UNKNOWN_BEHAVIOUR =
            Optional.of(Unknown.BEHAVIOUR);

    /**
     * Behaviours by type id, remembered.
     *
     * <p>Sound without invalidation because the registry is append-only: registering the same id
     * twice throws, so an id's behaviour cannot change under a remembered answer — and a type
     * registered after the first lookup simply misses once and is kept.
     */
    private static final Map<ResourceLocation, Optional<TaskBehaviour<QuestTask>>> BEHAVIOURS =
            new ConcurrentHashMap<>();

    /** One registry read, for the miss half of {@link #behaviourOf}. */
    private static Optional<TaskBehaviour<QuestTask>> lookupBehaviour(ResourceLocation id) {
        return REGISTRY.get(id).map(Entry::behaviour);
    }

    /**
     * The icon for a task's type, for a listing.
     *
     * <p>Falls back to paper for an unregistered type. That is reachable only from a listing command
     * run against a quest that failed to decode, which the validator has already reported.
     */
    public static ItemRef iconOf(ResourceLocation id) {
        return REGISTRY.get(id).map(Entry::icon).orElse(ItemRef.DEFAULT_ICON);
    }

    /**
     * The fields of a registered type as the editor draws them.
     *
     * <p>The type's own form when it declared one, and a form derived from its field names when it did
     * not -- so an addon's type is a form the day it registers, whether or not it thought about the
     * editor. Empty for an id this build does not know, which is the same refusal every other accessor
     * here makes.
     */
    public static java.util.List<dev.ellipog.tenet.quest.EditorField> editorOf(ResourceLocation id) {
        return REGISTRY.get(id)
                .map(entry -> entry.editor().isEmpty()
                        ? dev.ellipog.tenet.quest.EditorSpecs.derive(entry.spec().fields())
                        : entry.editor())
                .orElse(java.util.List.of());
    }

    @SuppressWarnings("unchecked")
    private static <T extends QuestTask> TypeSpec<QuestTask> widenSpec(QuestTaskType<T> type) {
        return new TypeSpec<>() {
            @Override
            public ResourceLocation id() {
                return type.id();
            }

            @Override
            public Set<String> fields() {
                return type.fields();
            }

            @Override
            public MapCodec<QuestTask> codec() {
                return type.codec().xmap(task -> (QuestTask) task, task -> (T) task);
            }
        };
    }

    /**
     * Widens a behaviour to the uniform one the engine needs.
     *
     * <p>The cast in the lambdas is sound for the same reason the spec's is: the behaviour travels
     * with the id and the codec, so the only value ever handed to the downcast is one produced by
     * this very codec.
     *
     * <h2>Every method is forwarded, and the first version of this is why</h2>
     *
     * <p>It forwarded {@code required}, {@code current} and {@code canSubmitByHand} and left the rest
     * to the interface's defaults — which meant an item task's {@code takesResources} was read as
     * {@code false} at every call site that goes through this wrapper, and consuming tasks silently
     * stopped taking anything. The example pack never consumes, so nothing failed. So the rule here
     * is: when {@link TaskBehaviour} grows a method, this wrapper grows a line, and a type's override
     * only exists if it is actually reachable through the registry.
     */
    @SuppressWarnings("unchecked")
    private static <T extends QuestTask> TaskBehaviour<QuestTask> widenBehaviour(TaskBehaviour<T> behaviour) {
        return new TaskBehaviour<>() {
            @Override
            public int required(QuestTask task) {
                return behaviour.required((T) task);
            }

            @Override
            public int current(QuestTask task, dev.ellipog.tenet.quest.TaskContext context) {
                return behaviour.current((T) task, context);
            }

            @Override
            public boolean canSubmitByHand(QuestTask task, boolean chapterDefault) {
                return behaviour.canSubmitByHand((T) task, chapterDefault);
            }

            @Override
            public boolean acceptsClientSubmit(QuestTask task, boolean chapterDefault) {
                return behaviour.acceptsClientSubmit((T) task, chapterDefault);
            }

            @Override
            public boolean takesResources(QuestTask task, boolean chapterDefault) {
                return behaviour.takesResources((T) task, chapterDefault);
            }

            @Override
            public boolean waitsForSubmit(QuestTask task, boolean chapterDefault) {
                return behaviour.waitsForSubmit((T) task, chapterDefault);
            }

            @Override
            public int take(QuestTask task, net.minecraft.server.level.ServerPlayer player, int count) {
                return behaviour.take((T) task, player, count);
            }

            @Override
            public int onEntityDeath(QuestTask task, net.minecraft.server.level.ServerPlayer killer,
                                     net.minecraft.world.entity.LivingEntity killed) {
                return behaviour.onEntityDeath((T) task, killer, killed);
            }

            @Override
            public int onBlockBroken(QuestTask task, net.minecraft.server.level.ServerPlayer player,
                                     net.minecraft.world.level.block.state.BlockState state) {
                return behaviour.onBlockBroken((T) task, player, state);
            }

            @Override
            public boolean readsInventory() {
                return behaviour.readsInventory();
            }

            @Override
            public boolean manualOnly(QuestTask task) {
                return behaviour.manualOnly((T) task);
            }
        };
    }

    @SuppressWarnings("unchecked")
    private static <T extends QuestTask> Function<QuestTask, TaskDisplay> widenDisplay(Function<T, TaskDisplay> display) {
        // Same soundness argument as the spec and the behaviour: the display travels with the id and
        // the codec, so the only value ever handed to this cast came from that codec.
        return task -> display.apply((T) task);
    }

    private record SimpleQuestTaskType<T extends QuestTask>(
            ResourceLocation id, MapCodec<T> codec, Set<String> fields,
            java.util.List<dev.ellipog.tenet.quest.EditorField> editor,
            TaskBehaviour<T> behaviour, ItemRef icon, Function<T, TaskDisplay> display,
            Supplier<T> defaultsSupplier
    ) implements QuestTaskType<T> {

        @Override
        public TaskDisplay display(T task) {
            return display.apply(task);
        }

        @Override
        public T defaults() {
            return defaultsSupplier.get();
        }
    }
}
