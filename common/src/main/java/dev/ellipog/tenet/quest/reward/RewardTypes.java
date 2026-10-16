package dev.ellipog.tenet.quest.reward;

import com.mojang.serialization.Codec;
import com.mojang.serialization.MapCodec;
import dev.ellipog.armature.api.data.TypeDispatch;
import dev.ellipog.armature.api.data.TypeSpec;
import dev.ellipog.armature.api.registry.SimpleRegistry;
import dev.ellipog.tenet.Tenet;
import dev.ellipog.tenet.quest.ItemRef;
import dev.ellipog.tenet.quest.QuestReward;
import net.minecraft.resources.Identifier;

import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * Every reward type Tenet knows about.
 *
 * <p>Mirrors {@link dev.ellipog.tenet.quest.task.TaskTypes}, including why
 * {@link #dispatchCodec()} is a method rather than a constant and why the registry holds an entry
 * rather than a bare spec. Both are explained there.
 */
public final class RewardTypes {

    /** A registered type: what reads it, how it is granted, and what represents it. */
    private record Entry(TypeSpec<QuestReward> spec, RewardBehaviour<QuestReward> behaviour, ItemRef icon,
                         Function<QuestReward, RewardDisplay> display,
                         java.util.List<dev.ellipog.tenet.quest.EditorField> editor,
                         Supplier<QuestReward> defaults) {
    }

    /** The registered type's own codec; the reward half of {@link dev.ellipog.tenet.quest.task.TaskTypes#codecOf}. */
    public static java.util.Optional<MapCodec<QuestReward>> codecOf(Identifier id) {
        return REGISTRY.get(id).map(entry -> entry.spec().codec());
    }

    private static final SimpleRegistry<Entry> REGISTRY = SimpleRegistry.create("quest reward types");

    /**
     * The base mechanics every reward has, as the form draws them: when it is given, and the two switches
     * that decide what a claim-all may take.
     *
     * <p>Appended to every registered type's form rather than written into each spec, for the same reason
     * {@link RewardCommon#FIELDS} is unioned into every type's field list: a type cannot offer a variant
     * of "automatic" that means something else. {@code team} is deliberately absent -- it is an optional
     * boolean rather than a switch, and the form's controls write the values their kinds can write; the
     * panel's field list is where a tri-state is set.
     */
    private static final java.util.List<dev.ellipog.tenet.quest.EditorField> COMMON_EDITOR =
            java.util.List.of(
                    // The ring comes from the enum, because a hand-written list here was three of its five
                    // words -- see RewardAutoClaim#wireValues.
                    dev.ellipog.tenet.quest.EditorField.choice("auto", "Given",
                                    RewardAutoClaim.wireValues().toArray(String[]::new))
                            .hint("when it is handed over: default follows the quest's own setting, enabled "
                                    + "gives it on completion with a notification, no_toast gives it "
                                    + "silently, invisible gives it with no trace at all, disabled waits "
                                    + "for a claim"),
                    dev.ellipog.tenet.quest.EditorField.flag("excludeFromClaimAll", "Claim separately")
                            .hint("Claim all leaves this one for its own press"),
                    dev.ellipog.tenet.quest.EditorField.flag("ignoreRewardBlocking", "Ignore blocking")
                            .hint("give it even while the team's rewards are being held"),
                    dev.ellipog.tenet.quest.EditorField.flag("disableToast", "Quiet reward")
                            .hint("collecting this reward raises no toast"),
                    dev.ellipog.tenet.quest.EditorField.text("title", "Title",
                                    "the words the row wears instead of the type's own")
                            .hint("the words this reward's row wears; empty means the type's own sentence"),
                    dev.ellipog.tenet.quest.EditorField.icon("icon", "Picture")
                            .hint("the picture this reward's row wears; empty means the type's own"),
                    dev.ellipog.tenet.quest.EditorField.text("tags", "Tags",
                                    "words this reward answers to, comma-separated")
                            .hint("words this reward answers to in lookups by tag; empty means none"));

    /**
     * The four table-backed rewards share one form: <b>one</b> control.
     *
     * <p>It used to be two text boxes — an id to type and a JSON blob to paste — which is the state this
     * whole feature exists to get out of: an author who did not know a table's file name could not set
     * one, and an author who did had to write the table's body by hand. The control draws the table's
     * icon and name, opens a browser to choose one, and opens an editor to change one; it owns both the
     * {@code table} reference and the {@code inline} body beside it, because a reward rolls one table or
     * the other, never both. See {@code EditorField.Kind.TABLE}.
     */
    private static final java.util.List<dev.ellipog.tenet.quest.EditorField> TABLE_EDITOR =
            java.util.List.of(
                    dev.ellipog.tenet.quest.EditorField.table("table", "Table")
                            .hint("the table this reward rolls: press to choose one, or to edit the one "
                                    + "it has. Tables live in files under reward_tables/."));

    /** {@code tenet:item} — some items. */
    public static final QuestRewardType<ItemReward> ITEM = register(
            "item", ItemReward.MAP_CODEC, ItemReward.FIELDS, java.util.List.of(
                    dev.ellipog.tenet.quest.EditorField.item("item", "Give")
                            .hint("the item to give; the picker keeps the data of the one you pick"),
                    dev.ellipog.tenet.quest.EditorField.number("count", "Count", "\u00d7")
                            .hint("how many"),
                    dev.ellipog.tenet.quest.EditorField.number("randomBonus", "Extra", "extra")
                            .hint("up to this many more, rolled at random on top of the count"),
                    dev.ellipog.tenet.quest.EditorField.flag("onlyOne", "Only one")
                            .hint("skip it if the player already carries this item")),
            ItemReward.BEHAVIOUR,
            new ItemRef(Identifier.withDefaultNamespace("diamond"), 1), ItemReward.DISPLAY,
            () -> new ItemReward(RewardCommon.DEFAULT,
                    new ItemRef(Identifier.withDefaultNamespace("paper"), 1), 0, false));

    /** {@code tenet:xp} — experience points or levels. */
    public static final QuestRewardType<XpReward> XP = register(
            "xp", XpReward.MAP_CODEC, XpReward.FIELDS, java.util.List.of(
                    dev.ellipog.tenet.quest.EditorField.number("amount", "Give", "XP")
                            .hint("how much experience to give"),
                    dev.ellipog.tenet.quest.EditorField.flag("levels", "Levels instead")
                            .hint("give levels rather than points")),
            XpReward.BEHAVIOUR,
            new ItemRef(Identifier.withDefaultNamespace("experience_bottle"), 1), XpReward.DISPLAY,
            () -> new XpReward(RewardCommon.DEFAULT, 1, false));

    /** {@code tenet:currency} — money, paid through the installed economy. */
    public static final QuestRewardType<CurrencyReward> CURRENCY = register(
            "currency", CurrencyReward.MAP_CODEC, CurrencyReward.FIELDS, java.util.List.of(
                    dev.ellipog.tenet.quest.EditorField.number("amount", "Give", "coins")
                            .hint("how much currency to pay through the installed economy")),
            CurrencyReward.BEHAVIOUR,
            new ItemRef(Identifier.withDefaultNamespace("gold_nugget"), 1), CurrencyReward.DISPLAY,
            () -> new CurrencyReward(RewardCommon.DEFAULT, 1));

    /** {@code tenet:random} — one guaranteed roll from a table. */
    public static final QuestRewardType<TableReward> RANDOM = register(
            "random", TableReward.mapCodec(TableReward.Mode.RANDOM), TableReward.FIELDS, TABLE_EDITOR,
            TableReward.BEHAVIOUR,
            new ItemRef(Identifier.withDefaultNamespace("dispenser"), 1), TableReward.DISPLAY,
            () -> new TableReward(RewardCommon.DEFAULT, TableReward.Mode.RANDOM, java.util.Optional.of("loot"),
                    java.util.Optional.empty()));

    /** {@code tenet:loot} — a roll that can come up empty. */
    public static final QuestRewardType<TableReward> LOOT = register(
            "loot", TableReward.mapCodec(TableReward.Mode.LOOT), TableReward.FIELDS, TABLE_EDITOR,
            TableReward.BEHAVIOUR,
            new ItemRef(Identifier.withDefaultNamespace("chest"), 1), TableReward.DISPLAY,
            () -> new TableReward(RewardCommon.DEFAULT, TableReward.Mode.LOOT, java.util.Optional.of("loot"),
                    java.util.Optional.empty()));

    /** {@code tenet:all_table} — every entry once, weights ignored. */
    public static final QuestRewardType<TableReward> ALL_TABLE = register(
            "all_table", TableReward.mapCodec(TableReward.Mode.ALL_TABLE), TableReward.FIELDS, TABLE_EDITOR,
            TableReward.BEHAVIOUR,
            new ItemRef(Identifier.withDefaultNamespace("shulker_box"), 1), TableReward.DISPLAY,
            () -> new TableReward(RewardCommon.DEFAULT, TableReward.Mode.ALL_TABLE,
                    java.util.Optional.of("loot"), java.util.Optional.empty()));

    /** {@code tenet:choice} — the player picks one entry. */
    public static final QuestRewardType<TableReward> CHOICE = register(
            "choice", TableReward.mapCodec(TableReward.Mode.CHOICE), TableReward.FIELDS, TABLE_EDITOR,
            TableReward.BEHAVIOUR,
            new ItemRef(Identifier.withDefaultNamespace("bundle"), 1), TableReward.DISPLAY,
            () -> new TableReward(RewardCommon.DEFAULT, TableReward.Mode.CHOICE, java.util.Optional.of("loot"),
                    java.util.Optional.empty()));

    /** {@code tenet:command} — run a command as the player. */
    public static final QuestRewardType<CommandReward> COMMAND = register(
            "command", CommandReward.MAP_CODEC, CommandReward.FIELDS, java.util.List.of(
                    dev.ellipog.tenet.quest.EditorField.text("command", "Command",
                                    "run as the player, without the leading slash")
                            .hint("run as the player when the reward is collected, without the slash"),
                    dev.ellipog.tenet.quest.EditorField.number("permissionLevel", "As", "level")
                            .hint("the permission level to run it at; 2 is a command block's"),
                    dev.ellipog.tenet.quest.EditorField.flag("silent", "Quiet")
                            .hint("do not say in chat that it ran"),
                    dev.ellipog.tenet.quest.EditorField.text("feedbackMessage", "Feedback",
                                    "shown when the command runs; empty shows nothing")
                            .hint("the message shown when the command runs; empty means nothing extra")),
            CommandReward.BEHAVIOUR,
            new ItemRef(Identifier.withDefaultNamespace("command_block"), 1), CommandReward.DISPLAY,
            () -> new CommandReward(RewardCommon.DEFAULT, "say hello", 2, false, java.util.Optional.empty()));

    /** {@code tenet:advancement} — award an advancement, or one criterion of one. */
    public static final QuestRewardType<AdvancementReward> ADVANCEMENT = register(
            "advancement", AdvancementReward.MAP_CODEC, AdvancementReward.FIELDS, java.util.List.of(
                    dev.ellipog.tenet.quest.EditorField.search("advancement", "Advancement",
                                    dev.ellipog.tenet.quest.EditorField.Source.ADVANCEMENT)
                            .hint("the advancement to award"),
                    dev.ellipog.tenet.quest.EditorField.text("criterion", "One criterion",
                                    "leave empty for the whole advancement")
                            .hint("award one criterion of it instead of the whole advancement")),
            AdvancementReward.BEHAVIOUR,
            new ItemRef(Identifier.withDefaultNamespace("dragon_egg"), 1), AdvancementReward.DISPLAY,
            () -> new AdvancementReward(RewardCommon.DEFAULT,
                    Identifier.withDefaultNamespace("story/root"), java.util.Optional.empty()));

    /** {@code tenet:custom} — somebody else's code, by id. */
    public static final QuestRewardType<CustomReward> CUSTOM = register(
            "custom", CustomReward.MAP_CODEC, CustomReward.FIELDS, java.util.List.of(
                    dev.ellipog.tenet.quest.EditorField.text("id", "Id",
                                    "the id another mod registered")
                            .hint("the id another mod registered for its own reward")),
            CustomReward.BEHAVIOUR,
            new ItemRef(Identifier.withDefaultNamespace("structure_block"), 1), CustomReward.DISPLAY,
            () -> new CustomReward(RewardCommon.DEFAULT, "example:custom"));

    /**
     * {@code tenet:stage} — set a stage, or take one away.
     *
     * <p>The write half of the flags {@link dev.ellipog.tenet.quest.task.StageTask} asks about.
     */
    public static final QuestRewardType<StageReward> STAGE = register(
            "stage", StageReward.MAP_CODEC, StageReward.FIELDS, java.util.List.of(
                    dev.ellipog.tenet.quest.EditorField.text("stage", "Stage",
                                    "the id to set; any id, since a stage exists by being granted")
                            .hint("the stage to set when this reward is collected"),
                    dev.ellipog.tenet.quest.EditorField.flag("remove", "Take away")
                            .hint("take the stage away instead of granting it"),
                    dev.ellipog.tenet.quest.EditorField.flag("teamStage", "Team stage")
                            .hint("grant to the team rather than the claiming player")),
            StageReward.BEHAVIOUR,
            new ItemRef(Identifier.withDefaultNamespace("oak_sign"), 1), StageReward.DISPLAY,
            () -> new StageReward(RewardCommon.DEFAULT,
                    Identifier.fromNamespaceAndPath("example", "stage"), false, false));

    /** {@code tenet:toast} — show the player a message. */
    public static final QuestRewardType<ToastReward> TOAST = register(
            "toast", ToastReward.MAP_CODEC, ToastReward.FIELDS, java.util.List.of(
                    dev.ellipog.tenet.quest.EditorField.text("description", "Message",
                                    "shown when the reward is collected")
                            .hint("the message shown when this reward is collected")),
            ToastReward.BEHAVIOUR,
            new ItemRef(Identifier.withDefaultNamespace("bell"), 1), ToastReward.DISPLAY,
            () -> new ToastReward(RewardCommon.DEFAULT,
                    dev.ellipog.tenet.quest.QuestText.literal("Hello")));

    private RewardTypes() {
    }

    private static final class Dispatch {
        static final Codec<QuestReward> CODEC = TypeDispatch.codec(
                "quest reward", "type", QuestReward::type,
                () -> REGISTRY.values().stream().map(Entry::spec).toList(),
                // A placeholder rather than a refusal, for the reason UnknownReward gives: one addon
                // reward in a file must not cost the author every quest in it.
                UnknownReward::of);
    }

    /**
     * {@link UnknownReward#BEHAVIOUR}, widened once, in its own class for the reason
     * {@code TaskTypes.Dispatch} gives: nothing here may be read while this class is initialising.
     */
    private static final class Unknown {
        static final RewardBehaviour<QuestReward> BEHAVIOUR = widenBehaviour(UnknownReward.BEHAVIOUR);
    }

    public static Codec<QuestReward> dispatchCodec() {
        return Dispatch.CODEC;
    }

    public static <T extends QuestReward> QuestRewardType<T> register(String path,
                                                                      MapCodec<T> codec,
                                                                      Set<String> fields,
                                                                      RewardBehaviour<T> behaviour,
                                                                      ItemRef icon,
                                                                      Function<T, RewardDisplay> display,
                                                                      Supplier<T> defaults) {
        return register(path, codec, fields, java.util.List.of(), behaviour, icon, display, defaults);
    }

    /**
     * The same registration, carrying the type's own editor form. See
     * {@link dev.ellipog.tenet.quest.task.TaskTypes#register(String, MapCodec, Set, java.util.List,
     * TaskBehaviour, ItemRef, Function, Supplier)} for the whole of the argument.
     */
    public static <T extends QuestReward> QuestRewardType<T> register(
            String path, MapCodec<T> codec, Set<String> fields,
            java.util.List<dev.ellipog.tenet.quest.EditorField> editor,
            RewardBehaviour<T> behaviour, ItemRef icon, Function<T, RewardDisplay> display,
            Supplier<T> defaults) {
        return register(Identifier.fromNamespaceAndPath(Tenet.MOD_ID, path), codec, fields, editor,
                behaviour, icon, display, defaults);
    }

    public static <T extends QuestReward> QuestRewardType<T> register(Identifier id,
                                                                      MapCodec<T> codec,
                                                                      Set<String> fields,
                                                                      RewardBehaviour<T> behaviour,
                                                                      ItemRef icon,
                                                                      Function<T, RewardDisplay> display,
                                                                      Supplier<T> defaults) {
        return register(id, codec, fields, java.util.List.of(), behaviour, icon, display, defaults);
    }

    /** The same, under an id you choose, with the type's own editor form. */
    public static <T extends QuestReward> QuestRewardType<T> register(
            Identifier id, MapCodec<T> codec, Set<String> fields,
            java.util.List<dev.ellipog.tenet.quest.EditorField> editor,
            RewardBehaviour<T> behaviour, ItemRef icon, Function<T, RewardDisplay> display,
            Supplier<T> defaults) {
        // The base fields ride along with every type, so a type cannot declare a codec that accepts
        // `"auto"` and a field list that does not -- which the validator would report as an unknown
        // field on every reward using it. An addon whose codec omits them is the one case this cannot
        // save, and there the validator's own codec check names the field and the line.
        java.util.LinkedHashSet<String> declared = new java.util.LinkedHashSet<>(fields);
        declared.addAll(RewardCommon.FIELDS);
        // And the base mechanics ride on the form as well as on the field list, for the same reason: a
        // form that showed every type's own fields and none of these would send an author to another
        // panel for the switches they came to set.
        java.util.List<dev.ellipog.tenet.quest.EditorField> form = new java.util.ArrayList<>(editor);
        form.addAll(COMMON_EDITOR);
        QuestRewardType<T> typed = new SimpleQuestRewardType<>(id, codec, Set.copyOf(declared), form, behaviour,
                icon, display, defaults);
        REGISTRY.register(id, new Entry(widenSpec(typed), widenBehaviour(behaviour), icon,
                widenDisplay(display), java.util.List.copyOf(form), () -> defaults.get()));
        return typed;
    }

    /**
     * A fresh instance of a registered type, encoded as the tree a quest file stores. See
     * {@link dev.ellipog.tenet.quest.task.TaskTypes#defaultTree} for the whole of the argument.
     */
    public static Optional<com.google.gson.JsonObject> defaultTree(Identifier id) {
        return REGISTRY.get(id).flatMap(entry -> Dispatch.CODEC
                // Through the dispatch codec: it is what writes the "type" field -- see TaskTypes.
                .encodeStart(com.mojang.serialization.JsonOps.INSTANCE, entry.defaults().get())
                .result()
                .filter(com.google.gson.JsonElement::isJsonObject)
                .map(com.google.gson.JsonElement::getAsJsonObject));
    }

    /**
     * What a reward gives, as a client should draw it.
     *
     * <p>A row naming the type for an unknown one — see {@code TaskTypes.displayOf} for why a named row
     * beats a blank one. {@link RewardDisplay#NONE} otherwise for an unregistered type, which is now
     * reachable only from a reward built by hand rather than decoded.
     */
    public static RewardDisplay displayOf(QuestReward reward) {
        if (reward instanceof UnknownReward unknown) {
            return RewardDisplay.ofTranslatableText("tenet.reward.unknown_type",
                    "Unknown reward type: " + unknown.type(), unknown.type().toString(), 1);
        }
        RewardDisplay computed = REGISTRY.get(reward.type())
                .map(entry -> entry.display().apply(reward))
                .orElse(RewardDisplay.NONE);
        return withAuthorOverrides(reward.common(), computed);
    }

    /**
     * A reward wearing its author's words and picture instead of its type's own.
     *
     * <p>Absent title and icon mean the type decides, which is every file written before the two
     * fields existed. An entity icon resolves to its egg on the server that holds the registries;
     * an entity with no egg keeps the type's own picture, for the reason
     * {@code TaskTypes} gives beside its own egg lookup.
     */
    private static RewardDisplay withAuthorOverrides(RewardCommon common, RewardDisplay computed) {
        RewardDisplay out = computed;
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

    private static ItemRef eggOf(net.minecraft.resources.Identifier entity) {
        net.minecraft.resources.Identifier egg =
                net.minecraft.resources.Identifier.fromNamespaceAndPath(entity.getNamespace(),
                        entity.getPath() + "_spawn_egg");
        if (!net.minecraft.core.registries.BuiltInRegistries.ITEM.containsKey(egg)) {
            return null;
        }
        return new ItemRef(egg, 1);
    }

    /** The icon for a reward's type, for a listing. Paper for an unregistered type. */
    public static ItemRef iconOf(Identifier id) {
        return REGISTRY.get(id).map(Entry::icon).orElse(ItemRef.DEFAULT_ICON);
    }

    /**
     * The fields of a registered type as the editor draws them: the type's own form, or one derived from
     * its field names. See {@link dev.ellipog.tenet.quest.task.TaskTypes#editorOf(Identifier)}.
     */
    public static java.util.List<dev.ellipog.tenet.quest.EditorField> editorOf(Identifier id) {
        return REGISTRY.get(id)
                .map(entry -> entry.editor().isEmpty()
                        ? dev.ellipog.tenet.quest.EditorSpecs.derive(entry.spec().fields())
                        : entry.editor())
                .orElse(java.util.List.of());
    }

    public static Set<Identifier> ids() {
        return REGISTRY.ids();
    }

    public static int count() {
        return REGISTRY.size();
    }

    public static Set<String> fieldsOf(Identifier id) {
        return REGISTRY.get(id).map(entry -> entry.spec().fields()).orElse(Set.of());
    }

    /**
     * How to grant a reward.
     *
     * <p>An {@link UnknownReward} answers with {@link UnknownReward#BEHAVIOUR} — a no-op — so a caller
     * that reaches a payout path finds "there is nothing to pay it with" rather than an empty
     * {@code Optional} it might handle as a failure. Its {@code autoGrantable} is false, so the
     * automatic paths leave it for the claim instead of marking it collected unpaid.
     *
     * <p>Empty for an unregistered type that is not a decoded {@code UnknownReward}, which can now only
     * be a reward built by hand.
     */
    public static Optional<RewardBehaviour<QuestReward>> behaviourOf(QuestReward reward) {
        if (reward instanceof UnknownReward) {
            return Optional.of(Unknown.BEHAVIOUR);
        }
        return REGISTRY.get(reward.type()).map(Entry::behaviour);
    }

    @SuppressWarnings("unchecked")
    private static <T extends QuestReward> TypeSpec<QuestReward> widenSpec(QuestRewardType<T> type) {
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
            public MapCodec<QuestReward> codec() {
                return type.codec().xmap(reward -> (QuestReward) reward, reward -> (T) reward);
            }
        };
    }

    @SuppressWarnings("unchecked")
    private static <T extends QuestReward> RewardBehaviour<QuestReward> widenBehaviour(RewardBehaviour<T> behaviour) {
        return (reward, context) -> behaviour.grant((T) reward, context);
    }

    @SuppressWarnings("unchecked")
    private static <T extends QuestReward> java.util.function.Function<QuestReward, RewardDisplay> widenDisplay(
            java.util.function.Function<T, RewardDisplay> display) {
        // Sound for the same reason the spec's and the behaviour's casts are: the display travels with
        // the id and the codec, so the only value ever handed to this downcast came from that codec.
        return reward -> display.apply((T) reward);
    }

    private record SimpleQuestRewardType<T extends QuestReward>(
            Identifier id, MapCodec<T> codec, Set<String> fields,
            java.util.List<dev.ellipog.tenet.quest.EditorField> editor,
            RewardBehaviour<T> behaviour, ItemRef icon, Function<T, RewardDisplay> display,
            Supplier<T> defaultsSupplier
    ) implements QuestRewardType<T> {

        @Override
        public RewardDisplay display(T reward) {
            return display.apply(reward);
        }

        @Override
        public T defaults() {
            return defaultsSupplier.get();
        }
    }
}
