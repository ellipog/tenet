package dev.ellipog.tenet.quest.task;

import com.mojang.serialization.Codec;

import dev.ellipog.armature.api.data.Codecs;

import net.minecraft.core.component.DataComponentType;
import net.minecraft.world.item.ItemStack;

import java.util.Objects;

/**
 * How exactly an item task's filter has to match a carried stack.
 *
 * <p>the legacy three modes, by their own names: {@code none} compares the item alone, {@code fuzzy}
 * requires every component the filter names to be present and equal while the stack may carry more,
 * and {@code strict} is the whole stack. Strict is the default because it is what Tenet has always
 * done — an enchanted pickaxe is not a plain one — and a default that changed under existing files
 * would silently rewrite what a pack asked for.
 */
public enum ComponentMatch {

    /** The item type alone: any diamond is the filter's diamond, components and all. */
    NONE,

    /** The filter's components must be present and equal; the candidate may carry extra ones. */
    FUZZY,

    /** The whole stack, components included. */
    STRICT;

    public static final Codec<ComponentMatch> CODEC = Codecs.enumByName(ComponentMatch.class);

    /** Whether a carried stack answers this filter. An empty candidate never does. */
    public boolean matches(ItemStack template, ItemStack candidate) {
        if (candidate.isEmpty() || template.isEmpty()) {
            return false;
        }
        if (candidate.getItem() != template.getItem()) {
            return false;
        }
        return switch (this) {
            case NONE -> true;
            case STRICT -> ItemStack.isSameItemSameComponents(candidate, template);
            case FUZZY -> {
                var wanted = template.getComponents();
                var carried = candidate.getComponents();
                boolean subset = true;
                for (DataComponentType<?> type : wanted.keySet()) {
                    if (!Objects.equals(wanted.get(type), carried.get(type))) {
                        subset = false;
                        break;
                    }
                }
                yield subset;
            }
        };
    }
}
