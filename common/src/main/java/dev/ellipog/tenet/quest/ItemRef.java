package dev.ellipog.tenet.quest;

import com.mojang.datafixers.util.Either;
import com.mojang.serialization.Codec;
import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.core.component.DataComponentPatch;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

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
public record ItemRef(ResourceLocation item, int count, DataComponentPatch components) {

    /** The common case: an item and a count, with nothing custom about it. */
    public ItemRef(ResourceLocation item, int count) {
        this(item, count, DataComponentPatch.EMPTY);
    }

    /**
     * Used when a quest declares no icon.
     *
     * <p>A visible placeholder rather than an error: an icon is presentation, and refusing to load
     * a questline over a missing one would be the wrong trade.
     */
    public static final ItemRef DEFAULT_ICON =
            new ItemRef(ResourceLocation.withDefaultNamespace("paper"), 1);

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
     * A {@link MapCodec}, so that an item's fields sit flat wherever it is embedded — a task
     * declares {@code "item"} and {@code "count"} directly, rather than nesting them under
     * {@code "item": { ... }}.
     */
    public static final MapCodec<ItemRef> MAP_CODEC = RecordCodecBuilder.mapCodec(instance -> instance.group(
            ResourceLocation.CODEC.fieldOf("item").forGetter(ItemRef::item),
            Codec.intRange(1, MAX_COUNT).optionalFieldOf("count", 1).forGetter(ItemRef::count),
            // The 1.21 data components, in the datapack's own spelling: a JSON object keyed by
            // component id. This is what makes a renamed or enchanted item possible in a quest file,
            // and what the picker copies when an author picks the sword they are actually holding
            // rather than "a sword".
            DataComponentPatch.CODEC.optionalFieldOf("components", DataComponentPatch.EMPTY)
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
        Item resolved = BuiltInRegistries.ITEM.get(item);
        if (resolved == Items.AIR) {
            return ItemStack.EMPTY;
        }
        ItemStack stack = new ItemStack(resolved, count);
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
