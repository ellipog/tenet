package dev.ellipog.tenet.quest;

import net.minecraft.core.Holder;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

/**
 * Building an {@link ItemStack} that works before datapacks bind the registries too.
 *
 * <h2>Why this exists</h2>
 *
 * <p>On 26.x every stack reads its item's components out of the item's holder at construction,
 * and a registry holder has no components until datapacks bind them — filling the registry is
 * not enough, which is why this is not a bootstrap problem. So {@code new ItemStack(item)}
 * throws {@code NullPointerException: Components not bound yet} anywhere before that binding,
 * which is every headless unit test. A bound holder builds exactly the stack the plain
 * constructor would have, so production is unchanged and tests can build stacks freely.
 *
 * <p>The branch is the whole point, and it tests components rather than binding: a holder can be
 * value-bound (registered) while its components are still unbound, and {@code isBound} says yes
 * for those. The fallback carries only the name the binding would have added, because the display
 * name itself is a bound component now ({@code Item.getName} reads {@code ITEM_NAME} off the
 * stack): without it every fallback stack is nameless, and the item's own map is no help
 * because it reads the holder's bound components too. The name is the item's own description id
 * as a translatable, which is what the bound default is. Building every stack this way would
 * snapshot that one entry and silently drop whatever a datapack overrode, so the registry
 * holder is used whenever it is bound.
 */
public final class ItemStacks {

    private ItemStacks() {
    }

    /** A stack of this many of this item, bound or not. */
    public static ItemStack stackOf(Item item, int count) {
        return stackOf(BuiltInRegistries.ITEM.wrapAsHolder(item), count);
    }

    /** A single one. */
    public static ItemStack stackOf(Item item) {
        return stackOf(item, 1);
    }

    /** A stack of this many from a holder, bound or not. */
    public static ItemStack stackOf(Holder<Item> holder, int count) {
        if (holder.areComponentsBound()) {
            return new ItemStack(holder, count);
        }
        // The item's own map is no help: it reads the holder's bound components too. So the
        // fallback is an empty map plus the name, which is the one entry anything here reads.
        net.minecraft.core.component.DataComponentMap defaults =
                net.minecraft.core.component.DataComponentMap.builder()
                        .set(net.minecraft.core.component.DataComponents.ITEM_NAME,
                                net.minecraft.network.chat.Component.translatable(
                                        holder.value().getDescriptionId()))
                        .build();
        return new ItemStack(Holder.direct(holder.value(), defaults), count);
    }

    /** A single one from a holder. */
    public static ItemStack stackOf(Holder<Item> holder) {
        return stackOf(holder, 1);
    }
}
