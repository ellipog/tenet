package dev.ellipog.tenet.quest;

import com.mojang.datafixers.util.Either;
import com.mojang.serialization.Codec;
import com.mojang.serialization.DynamicOps;
import com.mojang.serialization.JsonOps;
import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.core.component.DataComponentPatch;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.ItemStack;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * A reference to an item and a count — <b>not</b> an {@link ItemStack}.
 *
 * <p>In JSON, flat: {@code {"item": "minecraft:oak_log", "count": 8}}.
 *
 * <h2>Why a record rather than an ItemStack</h2>
 *
 * <p>On 1.21.1 you can get away with building an {@code ItemStack} while the quest files are
 * loading — the registries exist by then. It is written this way anyway, for a reason that will not
 * be optional later: from 26.1 an {@code ItemStack} genuinely cannot be constructed outside a
 * server level, because it carries a component patch and needs a registry access to resolve one.
 * The data format has to be a plain record of item id plus count, turned into a stack at the moment
 * it is handed to a player.
 *
 * <p>Doing it now also means the quest files never hold live game objects, so nothing in a loaded
 * questline can go stale across a reload or a datapack change.
 */
public record ItemRef(Identifier item, int count, DataComponentPatch components) {

    /** The common case: an item and a count, with nothing custom about it. */
    public ItemRef(Identifier item, int count) {
        this(item, count, DataComponentPatch.EMPTY);
    }

    /**
     * Used when a quest declares no icon.
     *
     * <p>A visible placeholder rather than an error: an icon is presentation, and refusing to load
     * a questline over a missing one would be the wrong trade.
     */
    public static final ItemRef DEFAULT_ICON =
            new ItemRef(Identifier.withDefaultNamespace("paper"), 1);

    /**
     * The most one reference may carry, named once.
     *
     * <p>A reference is not a stack: a table entry asking for six thousand torches is a legitimate
     * thing to write, and granting it already splits into legal stacks. The bound exists so a typo
     * cannot ask for two billion of something, and it belongs here because {@code count} is this
     * record's field — {@code TableImport} restated it as its own {@code MAX_COUNT}, so the number an
     * import clipped at and the number the codec accepted were two claims about one rule.
     */
    public static final int MAX_COUNT = 6400;

    /**
     * The component ids whose values are text, and may be written as strings holding JSON.
     *
     * <p>{@code custom_name} and {@code item_name} hold one component; {@code lore} holds a list
     * of them. Every other component id passes through untouched, so a string-valued component
     * of any other type can never be mistaken for JSON here.
     */
    private static final java.util.Set<String> LENIENT_COMPONENT_KEYS =
            java.util.Set.of("minecraft:custom_name", "minecraft:item_name");

    /** The lore key, whose list elements get the same treatment one by one. */
    private static final String LORE_KEY = "minecraft:lore";

    /**
     * {@code DataComponentPatch.CODEC} with the 1.21 leniency for component-holding strings
     * restored.
     *
     * <p>Vanilla's flat component codec used to read a JSON string value and parse its
     * <i>content</i> as a text component, so {@code "custom_name": "\"Tempered Pickaxe\""} came
     * out as the name. On 26.x the string is kept literally — quotes and all — which turns every
     * hand-written multi-word name into one that reads wrong in game. A bare single word still
     * reads literally either way, so only the quoted form needs help, but both go through the
     * same rule: parse the content as component JSON, and on failure wrap it as a literal, which
     * is exactly what the old lenient parse yielded for a word like {@code Renamed}.
     *
     * <p>Encode delegates straight through, so what is written is still vanilla's nested form —
     * which is what the editor's files carry, and what round-trips.
     */
    static final Codec<DataComponentPatch> LENIENT_PATCH_CODEC =
            Codec.of(DataComponentPatch.CODEC, ItemRef::decodePatch);

    /** The decoder half of {@link #LENIENT_PATCH_CODEC}: lenient pre-pass, then vanilla. */
    private static <T> com.mojang.serialization.DataResult<com.mojang.datafixers.util.Pair<DataComponentPatch, T>> decodePatch(
            DynamicOps<T> ops, T input) {
        if (ops instanceof JsonOps && input instanceof com.google.gson.JsonElement json
                && json.isJsonObject()) {
            lenientComponents(json.getAsJsonObject());
        }
        return DataComponentPatch.CODEC.decode(ops, input);
    }

    /** Rewrites component-holding strings in place; see {@link #LENIENT_PATCH_CODEC}. */
    private static void lenientComponents(com.google.gson.JsonObject components) {
        for (java.util.Map.Entry<String, com.google.gson.JsonElement> entry
                : components.entrySet()) {
            if (LENIENT_COMPONENT_KEYS.contains(entry.getKey())
                    && entry.getValue().isJsonPrimitive()
                    && entry.getValue().getAsJsonPrimitive().isString()) {
                entry.setValue(lenientComponent(entry.getValue().getAsString()));
            }
            else if (LORE_KEY.equals(entry.getKey()) && entry.getValue().isJsonArray()) {
                com.google.gson.JsonArray lore = entry.getValue().getAsJsonArray();
                for (int i = 0; i < lore.size(); i++) {
                    com.google.gson.JsonElement line = lore.get(i);
                    if (line.isJsonPrimitive() && line.getAsJsonPrimitive().isString()) {
                        lore.set(i, lenientComponent(line.getAsString()));
                    }
                }
            }
        }
    }

    /** A string as a component: its JSON content when that parses, a literal otherwise. */
    private static com.google.gson.JsonElement lenientComponent(String raw) {
        try {
            com.google.gson.JsonElement parsed = com.google.gson.JsonParser.parseString(raw);
            if (net.minecraft.network.chat.ComponentSerialization.CODEC
                    .parse(JsonOps.INSTANCE, parsed)
                    .result().isPresent()) {
                return parsed;
            }
        }
        catch (com.google.gson.JsonSyntaxException notJson) {
            // Not JSON at all: the bare-word case below.
        }
        com.google.gson.JsonObject literal = new com.google.gson.JsonObject();
        literal.addProperty("text", raw);
        return literal;
    }

    /**
     * A {@link MapCodec}, so that an item's fields sit flat wherever it is embedded — a task
     * declares {@code "item"} and {@code "count"} directly, rather than nesting them under
     * {@code "item": { ... }}.
     */
    public static final MapCodec<ItemRef> MAP_CODEC = RecordCodecBuilder.mapCodec(instance -> instance.group(
            Identifier.CODEC.fieldOf("item").forGetter(ItemRef::item),
            Codec.intRange(1, MAX_COUNT).optionalFieldOf("count", 1).forGetter(ItemRef::count),
            // The 1.21 data components, in the datapack's own spelling: a JSON object keyed by
            // component id. This is what makes a renamed or enchanted item possible in a quest file,
            // and what the picker copies when an author picks the sword they are actually holding
            // rather than "a sword". Lenient for component-holding strings (see above), so files
            // written against the old lenient parse keep reading.
            LENIENT_PATCH_CODEC.optionalFieldOf("components", DataComponentPatch.EMPTY)
                    .forGetter(ItemRef::components)
    ).apply(instance, ItemRef::new));

    public static final Codec<ItemRef> CODEC = MAP_CODEC.codec();

    /** The field names an item reference contributes, for the validator to allow. */
    public static final java.util.Set<String> FIELDS = java.util.Set.of("item", "count", "components");

    /** Whether this names an item that actually exists. False means a typo, or a missing mod. */
    public boolean isKnown() {
        return BuiltInRegistries.ITEM.containsKey(item);
    }

    /**
     * The stack to actually give a player. Call at use time, never while loading.
     *
     * <p>An unknown item resolves to air rather than throwing, and callers are expected to have
     * checked {@link #isKnown} — the validator reports unknown items at load time, so reaching here
     * with one is a bug rather than user error.
     */
    public ItemStack toStack() {
        // Built through the binding-safe helper: tests call this with registries filled but
        // unbound, where a plain constructor throws. Unknown items are air either way.
        ItemStack stack = BuiltInRegistries.ITEM.get(item)
                .map(holder -> ItemStacks.stackOf(holder, count))
                .orElse(ItemStack.EMPTY);
        if (stack.isEmpty()) {
            return ItemStack.EMPTY;
        }
        if (!components.isEmpty()) {
            stack.applyComponents(components);
        }
        return stack;
    }

    /**
     * The stack this reference names, shared across callers that only read it.
     *
     * <p>For the measuring paths — counting an inventory against it, matching a slot against it,
     * reading its size — which rebuild the same stack per evaluation for an answer that cannot
     * move: the reference is immutable and the registries do not change while a client is
     * connected. Callers that hand the stack over or mutate it keep using {@link #toStack},
     * because a shared stack with one fewer log in it is a duplication bug with no stack trace.
     *
     * <p>Bounded by the pack rather than by a cap: one entry per distinct reference in the loaded
     * files.
     */
    private static final Map<ItemRef, ItemStack> TEMPLATES = new ConcurrentHashMap<>();

    public ItemStack template() {
        return TEMPLATES.computeIfAbsent(this, ItemRef::toStack);
    }

    /** For messages: {@code minecraft:oak_log x8}, with the count omitted when it is one. */
    public String describe() {
        return count == 1 ? item.toString() : item + " x" + count;
    }
}
